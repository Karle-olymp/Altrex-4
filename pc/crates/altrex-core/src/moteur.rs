//! Le moteur de Forge : un tour = la demande de l'utilisateur, puis des étapes (réponse du modèle,
//! appels d'outils, résultats) jusqu'à une réponse sans appel. Tout passe par le journal ; l'écran
//! ne fait que le relire. Les outils qui modifient passent par la politique d'autorisation.

use crate::appels;
use crate::espace::date_utc;
use crate::journal::{self, Evenement, Journal, Message, Tache};
use crate::memoire::Environnement;
use crate::modeles::{Erreur, Modele};
use crate::outils::fichiers::{Ecriture, Fichiers, Suivi};
use crate::outils::recherche::{self, Grep};
use crate::outils::specs::{self, booleen, chaine, entier, tableau, texte};
use crate::outils::{json_ordonne, Chemins, Resultat};
use crate::permissions::{self, Action, Decision, Demande, Mode, PRESETS};
use crate::shell::{self, Shell};
use serde_json::{json, Value};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Une question à l'utilisateur sur une action.
#[derive(Debug, Clone, PartialEq)]
pub struct DemandeAccord {
    pub id: String,
    pub outil: String,
    pub titre: String,
    /// La commande, ou le diff du changement de fichier.
    pub detail: String,
    pub est_diff: bool,
    pub risque: bool,
    pub escalade: bool,
    pub justification: Option<String>,
    /// Ce que « Toujours » couvre (« npm », « les fichiers »), ou rien quand il n'est pas proposé.
    pub libelle_toujours: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Choix {
    Oui,
    Toujours,
    Non,
    /// Refuser et finir le tour.
    Arreter,
    /// Personne n'a répondu à temps : un refus.
    SansReponse,
}

impl Choix {
    pub fn journal(self) -> &'static str {
        match self {
            Choix::Oui => "oui",
            Choix::Toujours => "toujours",
            Choix::Non => "non",
            Choix::Arreter => "arreter",
            Choix::SansReponse => "sans_reponse",
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct ReponseAccord {
    pub choix: Choix,
    pub motif: String,
}

#[derive(Debug, Clone, PartialEq)]
pub enum RevuePlan {
    /// Validé : le mode à prendre pour réaliser le plan.
    Valide(Mode),
    AModifier(String),
    Refuse,
}

/// L'écran, vu du moteur. Chaque appel attend l'utilisateur ; pas de réponse vaut refus.
pub trait Interactions: Send + Sync {
    fn approuver(&self, demande: &DemandeAccord) -> ReponseAccord;
    fn demander(&self, id: &str, question: &str, options: &[String]) -> Option<String>;
    fn revoir_plan(&self, id: &str, markdown: &str) -> RevuePlan;
}

/// Ce que le moteur signale pendant qu'il travaille.
#[derive(Debug, Clone, PartialEq)]
pub enum Signal {
    /// Un morceau de la réponse en cours d'écriture.
    Texte(String),
    /// Le journal a changé (l'écran le relit).
    Journal,
    /// L'outil en cours (« Bash », « Read »…), ou vide.
    Outil(String),
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Limites {
    pub max_etapes: usize,
    pub max_duree_ms: u64,
    /// Plus long résultat d'outil donné au modèle ; au-delà, la sortie d'une commande va dans un fichier.
    pub max_car_resultat: usize,
    /// Lignes que donne Read quand le modèle ne dit rien.
    pub lignes_read: usize,
}

impl Limites {
    /// Les valeurs du téléphone (`ForgeLimits.forModel`), avec plus d'étapes en local : un PC est plus rapide.
    pub fn pour(modele: &dyn Modele) -> Limites {
        if modele.local() {
            let petit = modele.budget_car() < 16_000;
            Limites {
                max_etapes: 30,
                max_duree_ms: 45 * 60_000,
                max_car_resultat: if petit { 2_500 } else { 6_000 },
                lignes_read: if petit { 120 } else { 250 },
            }
        } else {
            Limites {
                max_etapes: 40,
                max_duree_ms: 30 * 60_000,
                max_car_resultat: 12_000,
                lignes_read: 400,
            }
        }
    }
}

pub const MAX_APPELS_PAR_REPONSE: usize = 4;
pub const RAPPEL_PLAN: &str = "<rappel-forge>Mode Plan : lecture seule. Explore (Read, Glob, Grep, commandes de lecture), pose tes questions avec AskUserQuestion si un choix revient à l'utilisateur, puis présente un plan complet avec ExitPlanMode. Ne modifie pas le projet : seuls blueprint/ et .altrex/plans/ sont modifiables.</rappel-forge>";
pub const RAPPEL_LIMITE: &str = "<rappel-forge>Limite d'étapes de ce tour atteinte. N'appelle plus d'outil : dis où tu en es et ce qui reste à faire.</rappel-forge>";
pub const RAPPEL_VIDE: &str =
    "<rappel-forge>Tu n'as rien écrit pour l'utilisateur. Réponds-lui maintenant, directement.</rappel-forge>";
const RAPPEL_BOUCLE: &str = "<rappel-forge>Tu viens de refaire plusieurs fois le même appel avec le même résultat. Ne le relance pas : change d'approche, ou explique à l'utilisateur ce qui bloque.</rappel-forge>";

pub fn rappel_mode(m: Mode) -> String {
    format!(
        "<rappel-forge>Mode : {} (bac à sable : {} ; approbation : {}).</rappel-forge>",
        m.libelle(),
        m.bac.libelle(),
        m.approbation.libelle()
    )
}

pub fn rappel_taches(restantes: &[&Tache]) -> String {
    let cites: Vec<String> = restantes
        .iter()
        .take(5)
        .map(|t| format!("« {} »", t.content.chars().take(60).collect::<String>()))
        .collect();
    format!(
        "<rappel-forge>Ta liste de tâches n'est pas à jour : {} tâche(s) non terminée(s) ({}). Si elles sont faites, passe-les en completed avec TodoWrite ; sinon, termine-les ou dis à l'utilisateur ce qui reste à faire. Puis réponds-lui.</rappel-forge>",
        restantes.len(),
        cites.join(", ")
    )
}

/// Le résultat mis à la place d'un ancien résultat d'outil quand le contexte déborde.
fn pierre_tombale() -> String {
    json_ordonne(vec![
        (
            "compacte",
            json!("Résultat retiré du contexte pour gagner de la place."),
        ),
        ("note", json!("Refais l'appel si tu en as besoin.")),
        ("status", json!("ok")),
    ])
}

/// Garde l'historique sous `budget` caractères en retirant d'abord les plus anciens résultats d'outils.
pub fn reduire(messages: &mut [Message], budget: usize) {
    let taille = |ms: &[Message]| ms.iter().map(|m| m.texte_complet().len()).sum::<usize>();
    let tombe = pierre_tombale();
    let dernier = messages.len().saturating_sub(1);
    for k in 0..dernier {
        if taille(messages) <= budget {
            return;
        }
        for r in messages[k].resultats.iter_mut() {
            if r.1.len() > tombe.len() + 50 {
                r.1 = tombe.clone();
            }
        }
    }
}

pub struct Moteur {
    pub journal: Arc<Journal>,
    pub modele: Arc<dyn Modele>,
    pub chemins: Chemins,
    pub shell: Arc<Shell>,
    pub interactions: Arc<dyn Interactions>,
    /// Le prompt système, sans le bloc d'outils.
    pub systeme: String,
    pub env: Environnement,
    pub limites: Limites,
    pub arret: Arc<AtomicBool>,
    pub signal: Box<dyn Fn(Signal) + Send + Sync>,
    suivi: Mutex<Suivi>,
    mode_annonce: Mutex<Option<Mode>>,
}

/// Comment un appel d'outil s'est fini, pour la boucle.
struct Issue {
    resultat: Resultat,
    /// Le tour doit s'arrêter (« Arrêter », plan refusé).
    fin_du_tour: Option<&'static str>,
}

impl From<Resultat> for Issue {
    fn from(resultat: Resultat) -> Issue {
        Issue {
            resultat,
            fin_du_tour: None,
        }
    }
}

impl Moteur {
    #[allow(clippy::too_many_arguments)]
    pub fn nouveau(
        journal: Arc<Journal>,
        modele: Arc<dyn Modele>,
        projet: &std::path::Path,
        shell: Arc<Shell>,
        interactions: Arc<dyn Interactions>,
        systeme: String,
        env: Environnement,
        signal: Box<dyn Fn(Signal) + Send + Sync>,
    ) -> Moteur {
        let limites = Limites::pour(modele.as_ref());
        Moteur {
            journal,
            modele,
            chemins: Chemins::nouveau(projet),
            shell,
            interactions,
            systeme,
            env,
            limites,
            arret: Arc::new(AtomicBool::new(false)),
            signal,
            suivi: Mutex::new(Suivi::default()),
            mode_annonce: Mutex::new(None),
        }
    }

    pub fn mode(&self) -> Mode {
        journal::replier(&self.journal.evenements()).mode()
    }

    /// Change le mode (une puce de l'écran) ; noté dans le journal.
    pub fn changer_mode(&self, m: Mode) {
        if m != self.mode() {
            self.noter(Evenement::Mode { mode: m.cle() });
        }
    }

    fn noter(&self, e: Evenement) {
        if let Err(err) = self.journal.ajouter(e) {
            eprintln!("journal : {err}");
        }
        (self.signal)(Signal::Journal);
    }

    fn rappel(&self, tour: i64, texte: &str) {
        self.noter(Evenement::Note {
            turn: tour,
            kind: "rappel".into(),
            text: texte.to_string(),
            for_model: true,
            shown: false,
        });
    }

    fn schemas(&self) -> Vec<Value> {
        specs::schemas_pc(&specs::Adaptation {
            shell: &self.env.shell,
            systeme: &self.env.systeme,
        })
    }

    fn finir(&self, tour: i64, raison: &str, detail: &str) -> String {
        self.noter(Evenement::FinTour {
            turn: tour,
            reason: raison.into(),
            detail: detail.into(),
        });
        (self.signal)(Signal::Outil(String::new()));
        raison.to_string()
    }

    /// Lance un tour avec la demande `demande`. Rend la raison de fin : « done », « stopped »,
    /// « limit », « error », « blocked ».
    pub fn tour(&self, demande: &str) -> String {
        self.arret.store(false, Ordering::SeqCst);
        let etat = journal::replier(&self.journal.evenements());
        let tour = etat.dernier_tour + 1;
        let mode = etat.mode();
        self.noter(Evenement::DebutTour { turn: tour });
        self.noter(Evenement::Utilisateur {
            turn: tour,
            text: demande.to_string(),
            auto: false,
        });
        {
            let mut annonce = self.mode_annonce.lock().unwrap();
            if mode.est_plan() {
                self.rappel(tour, RAPPEL_PLAN);
            } else if matches!(*annonce, Some(a) if a != mode) {
                self.rappel(tour, &rappel_mode(mode));
            }
            *annonce = Some(mode);
        }
        let debut = Instant::now();
        let schemas = self.schemas();
        let (mut etapes, mut n_appel) = (0usize, 0usize);
        let (mut rappel_vide, mut rappel_taches_fait, mut limite_dite) = (false, false, false);
        let mut precedent: Option<(String, String)> = None;
        let mut repetitions = 0usize;
        loop {
            if self.arret.load(Ordering::SeqCst) {
                return self.finir(tour, "stopped", "Arrêté par l'utilisateur.");
            }
            if debut.elapsed().as_millis() as u64 > self.limites.max_duree_ms {
                return self.finir(tour, "limit", "Durée maximale du tour atteinte.");
            }
            if etapes >= self.limites.max_etapes && !limite_dite {
                limite_dite = true;
                self.rappel(tour, RAPPEL_LIMITE);
            }
            etapes += 1;
            let mut messages = journal::messages_modele(&self.journal.evenements(), tour);
            reduire(&mut messages, self.modele.budget_car());
            let reponse = match self.generer(&messages, &schemas) {
                Ok(r) => r,
                Err(Erreur::Arrete) => return self.finir(tour, "stopped", "Arrêté par l'utilisateur."),
                Err(e) => return self.finir(tour, "error", &e.to_string()),
            };
            // Appels natifs (cloud) ou lus dans le texte (protocole <tool_call>).
            let propre = appels::sans_reflexion(&reponse.texte);
            let mut lus: Vec<(Option<String>, appels::Appel)> = if reponse.appels.is_empty() {
                appels::lire_appels(&propre)
                    .into_iter()
                    .map(|a| (None, a))
                    .collect()
            } else {
                reponse
                    .appels
                    .iter()
                    .map(|(id, nom, args)| {
                        (
                            Some(id.clone()),
                            appels::Appel {
                                nom: nom.clone(),
                                arguments: args.clone(),
                                brut: args.to_string(),
                            },
                        )
                    })
                    .collect()
            };
            lus.truncate(MAX_APPELS_PAR_REPONSE);
            let affiche = appels::texte_affiche(&reponse.texte);
            let brut = if reponse.appels.is_empty() {
                propre.trim().to_string()
            } else {
                let blocs: Vec<String> = lus
                    .iter()
                    .map(|(_, a)| appels::bloc_appel(&a.nom, &a.arguments))
                    .collect();
                [affiche.clone(), blocs.join("\n")]
                    .into_iter()
                    .filter(|x| !x.is_empty())
                    .collect::<Vec<_>>()
                    .join("\n")
            };
            let reflexion = if reponse.reflexion.trim().is_empty() {
                appels::reflexion(&reponse.texte)
            } else {
                reponse.reflexion.trim().to_string()
            };
            self.noter(Evenement::Assistant {
                turn: tour,
                raw: brut,
                display: affiche.clone(),
                thinking: reflexion,
            });

            if lus.is_empty() {
                if affiche.trim().is_empty() && !rappel_vide {
                    rappel_vide = true;
                    self.rappel(tour, RAPPEL_VIDE);
                    continue;
                }
                let etat = journal::replier(&self.journal.evenements());
                let restantes: Vec<&Tache> = etat.taches.iter().filter(|t| t.status != "completed").collect();
                if !restantes.is_empty() && !rappel_taches_fait && !limite_dite {
                    rappel_taches_fait = true;
                    self.rappel(tour, &rappel_taches(&restantes));
                    continue;
                }
                return self.finir(tour, if limite_dite { "limit" } else { "done" }, "");
            }
            if limite_dite && etapes > self.limites.max_etapes + 1 {
                return self.finir(tour, "limit", "Limite d'étapes du tour atteinte.");
            }
            for (id_natif, appel) in lus {
                n_appel += 1;
                let id = id_natif
                    .filter(|i| {
                        !i.is_empty()
                            && i.chars()
                                .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
                    })
                    .unwrap_or_else(|| format!("t{tour}c{n_appel}"));
                let nom = specs::nom_canonique(&appel.nom);
                let nom_journal = nom.clone().unwrap_or_else(|| appel.nom.clone());
                self.noter(Evenement::AppelOutil {
                    turn: tour,
                    id: id.clone(),
                    name: nom_journal.clone(),
                    args: appel.arguments.to_string(),
                });
                (self.signal)(Signal::Outil(nom_journal.clone()));
                let issue: Issue = match &nom {
                    _ if appel.nom.is_empty() => Resultat::erreur(
                        "Appel d'outil illisible (JSON invalide). Réécris-le exactement au format {\"name\": …, \"arguments\": {…}}.",
                    )
                    .into(),
                    None => Resultat::erreur(format!(
                        "L'outil « {} » n'existe pas. Outils disponibles : {}.",
                        appel.nom,
                        specs::noms().join(", ")
                    ))
                    .into(),
                    Some(n) => self.executer(tour, &id, n, &appel.arguments, debut),
                };
                let r = issue.resultat;
                // Anti-boucle : le même appel avec le même résultat, encore et encore.
                let cle = (format!("{nom_journal}:{}", appel.arguments), r.modele.clone());
                repetitions = if precedent.as_ref() == Some(&cle) {
                    repetitions + 1
                } else {
                    0
                };
                precedent = Some(cle);
                self.noter(Evenement::ResultatOutil {
                    turn: tour,
                    id,
                    name: nom_journal,
                    status: r.statut,
                    model: r.modele,
                    summary: r.resume,
                    detail: r.detail,
                    added: r.ajoutees,
                    removed: r.retirees,
                    path: r.chemin,
                });
                (self.signal)(Signal::Outil(String::new()));
                if let Some(raison) = issue.fin_du_tour {
                    return self.finir(tour, raison, "");
                }
                if self.arret.load(Ordering::SeqCst) {
                    return self.finir(tour, "stopped", "Arrêté par l'utilisateur.");
                }
                if repetitions == 2 {
                    self.rappel(tour, RAPPEL_BOUCLE);
                } else if repetitions >= 4 {
                    return self.finir(tour, "blocked", "Le modèle répète le même appel sans avancer.");
                }
            }
        }
    }

    /// Une réponse du modèle ; une limite de débit (429) est réessayée trois fois.
    fn generer(&self, messages: &[Message], schemas: &[Value]) -> Result<crate::modeles::Reponse, Erreur> {
        let mut essai = 0;
        loop {
            let mut sur_texte = |t: &str| (self.signal)(Signal::Texte(t.to_string()));
            match self
                .modele
                .generer(&self.systeme, messages, schemas, &mut sur_texte, &self.arret)
            {
                Err(Erreur::Limite { message, attente_ms }) if essai < 3 => {
                    essai += 1;
                    let attente = attente_ms.unwrap_or(5_000 * (1 << essai)).min(60_000);
                    let fin = Instant::now() + Duration::from_millis(attente);
                    while Instant::now() < fin {
                        if self.arret.load(Ordering::SeqCst) {
                            return Err(Erreur::Arrete);
                        }
                        std::thread::sleep(Duration::from_millis(50));
                    }
                    let _ = message;
                }
                autre => return autre,
            }
        }
    }

    fn accords(&self) -> permissions::Accords {
        journal::replier(&self.journal.evenements()).accords
    }

    /// Demande l'accord de l'utilisateur ; `Ok` s'il dit oui, sinon le refus à rendre au modèle.
    #[allow(clippy::too_many_arguments)]
    fn demander_accord(
        &self,
        tour: i64,
        id: &str,
        outil: &str,
        q: &Demande,
        detail: String,
        est_diff: bool,
        cle: &str,
    ) -> Result<(), Issue> {
        let libelle = q.grant.as_ref().map(|g| {
            if g == permissions::GRANT_EDITS {
                "les fichiers".to_string()
            } else {
                g.clone()
            }
        });
        let rep = self.interactions.approuver(&DemandeAccord {
            id: id.to_string(),
            outil: outil.to_string(),
            titre: q.titre.clone(),
            detail,
            est_diff,
            risque: q.risque,
            escalade: q.escalade,
            justification: q.justification.clone(),
            libelle_toujours: libelle,
        });
        self.noter(Evenement::Accord {
            turn: tour,
            id: id.to_string(),
            decision: rep.choix.journal().into(),
            reason: rep.motif.trim().to_string(),
            grant: if rep.choix == Choix::Toujours {
                q.grant.clone()
            } else {
                None
            },
            key: cle.to_string(),
        });
        match rep.choix {
            Choix::Oui | Choix::Toujours => Ok(()),
            Choix::Non => Err(Resultat::refus(if rep.motif.trim().is_empty() {
                "L'utilisateur a refusé.".to_string()
            } else {
                format!("L'utilisateur a refusé : {}", rep.motif.trim())
            })
            .into()),
            Choix::Arreter => Err(Issue {
                resultat: Resultat::refus("L'utilisateur a refusé et arrêté le tour."),
                fin_du_tour: Some("stopped"),
            }),
            Choix::SansReponse => {
                Err(Resultat::refus("Pas de réponse de l'utilisateur : refusé par défaut.").into())
            }
        }
    }

    fn ecrire(&self, tour: i64, id: &str, preparee: Result<Ecriture, Resultat>) -> Issue {
        let e = match preparee {
            Ok(e) => e,
            Err(r) => return r.into(),
        };
        let action = Action::Ecrire {
            chemin: Some(e.rel.clone()),
            nouveau: e.nouveau,
        };
        match permissions::decider_pour(self.mode(), &self.accords(), &action, self.env.dialecte) {
            Decision::Autoriser => {}
            Decision::Refuser(m) => return Resultat::refus(m).into(),
            Decision::Demander(q) => {
                if let Err(issue) =
                    self.demander_accord(tour, id, e.outil, &q, e.diff.clone(), true, &action.cle())
                {
                    return issue;
                }
            }
        }
        let mut suivi = self.suivi.lock().unwrap();
        let mut f = Fichiers {
            chemins: &self.chemins,
            suivi: &mut suivi,
        };
        if let Some(conflit) = f.conflit(&e) {
            self.noter(Evenement::Note {
                turn: tour,
                kind: "conflit".into(),
                text: format!(
                    "Conflit sur {} : le fichier a changé pendant l'attente, rien n'a été écrit.",
                    e.rel
                ),
                for_model: false,
                shown: true,
            });
            return Resultat::erreur(conflit).into();
        }
        f.appliquer(&e).into()
    }

    /// Un refus sans question (mode Plan, action déjà refusée) est dit avant toute autre erreur du fichier.
    fn refus_d_emblee(&self, brut: &str) -> Option<Resultat> {
        let r = self.chemins.resoudre(brut)?;
        let action = Action::Ecrire {
            chemin: Some(r.rel),
            nouveau: !r.fichier.exists(),
        };
        match permissions::decider_pour(self.mode(), &self.accords(), &action, self.env.dialecte) {
            Decision::Refuser(m) => Some(Resultat::refus(m)),
            _ => None,
        }
    }

    fn executer(&self, tour: i64, id: &str, nom: &str, a: &Value, debut: Instant) -> Issue {
        let chemin = || chaine(a, &["file_path", "path", "file"]);
        if matches!(nom, specs::WRITE | specs::EDIT | specs::MULTI_EDIT) {
            if let Some(r) = self.refus_d_emblee(&chemin()) {
                return r.into();
            }
        }
        match nom {
            specs::READ => {
                let mut suivi = self.suivi.lock().unwrap();
                let mut f = Fichiers { chemins: &self.chemins, suivi: &mut suivi };
                f.lire(&chemin(), entier(a, &["offset"]), entier(a, &["limit"]), self.limites.lignes_read).into()
            }
            specs::WRITE => {
                let Some(contenu) = texte(a, &["content"]) else {
                    return Resultat::erreur("Write demande un « content » (le contenu complet du fichier).").into();
                };
                let p = {
                    let mut suivi = self.suivi.lock().unwrap();
                    Fichiers { chemins: &self.chemins, suivi: &mut suivi }.preparer_ecriture(&chemin(), &contenu)
                };
                self.ecrire(tour, id, p)
            }
            specs::EDIT => {
                let (ancien, nouveau) =
                    (texte(a, &["old_string"]).unwrap_or_default(), texte(a, &["new_string"]).unwrap_or_default());
                let p = {
                    let mut suivi = self.suivi.lock().unwrap();
                    Fichiers { chemins: &self.chemins, suivi: &mut suivi }.preparer_edit(
                        &chemin(),
                        &ancien,
                        &nouveau,
                        booleen(a, &["replace_all"]),
                    )
                };
                self.ecrire(tour, id, p)
            }
            specs::MULTI_EDIT => {
                let modifs: Vec<(String, String, bool)> = tableau(a, &["edits"])
                    .unwrap_or_default()
                    .iter()
                    .map(|e| {
                        (
                            texte(e, &["old_string"]).unwrap_or_default(),
                            texte(e, &["new_string"]).unwrap_or_default(),
                            booleen(e, &["replace_all"]),
                        )
                    })
                    .collect();
                let p = {
                    let mut suivi = self.suivi.lock().unwrap();
                    Fichiers { chemins: &self.chemins, suivi: &mut suivi }.preparer_multi_edit(&chemin(), &modifs)
                };
                self.ecrire(tour, id, p)
            }
            specs::GLOB => recherche::glob(&self.chemins, &chaine(a, &["pattern"]), &chaine(a, &["path"])).into(),
            specs::GREP => recherche::grep(
                &self.chemins,
                &Grep {
                    motif: &chaine(a, &["pattern"]),
                    chemin: &chaine(a, &["path"]),
                    glob: &chaine(a, &["glob"]),
                    mode: &chaine(a, &["output_mode"]),
                    sans_casse: booleen(a, &["case_insensitive"]),
                    contexte: entier(a, &["context"]).unwrap_or(0).max(0) as usize,
                    limite: entier(a, &["head_limit"]).filter(|l| *l > 0).map(|l| l as usize),
                },
            )
            .into(),
            specs::BASH => self.bash(tour, id, a, debut),
            specs::BASH_OUTPUT | specs::KILL_SHELL => {
                let job = chaine(a, &["job_id", "bash_id", "shell_id"]);
                match self.shell.lire_tache(&job, nom == specs::KILL_SHELL, self.limites.max_car_resultat) {
                    None => Resultat::erreur(format!("Aucune tâche de fond « {job} ».")).into(),
                    Some(champs) => {
                        let get = |k: &str| champs.iter().find(|c| c.0 == k).map(|c| c.1.clone());
                        let mut paires = vec![("job_id", json!(job)), ("command", json!(get("command")))];
                        paires.push(("state", json!(get("state"))));
                        if let Some(c) = get("exit_code") {
                            paires.push(("exit_code", json!(c.parse::<i64>().unwrap_or(-1))));
                        }
                        paires.push(("new_output", json!(get("new_output"))));
                        paires.push(("status", json!("ok")));
                        Resultat { resume: get("state").unwrap_or_default(), ..Resultat::ok(json_ordonne(paires)) }.into()
                    }
                }
            }
            specs::TODO_WRITE => self.taches(tour, a).into(),
            specs::ASK_USER_QUESTION => {
                let question = chaine(a, &["question"]);
                if question.trim().is_empty() {
                    return Resultat::erreur("AskUserQuestion demande une « question ».").into();
                }
                let options: Vec<String> = tableau(a, &["options"])
                    .unwrap_or_default()
                    .iter()
                    .map(|o| o.as_str().map(str::to_string).unwrap_or_else(|| o.to_string()))
                    .take(4)
                    .collect();
                self.noter(Evenement::Question { turn: tour, id: id.into(), question: question.clone(), options: options.clone() });
                match self.interactions.demander(id, &question, &options) {
                    Some(r) if !r.trim().is_empty() => {
                        self.noter(Evenement::Reponse { turn: tour, id: id.into(), answer: r.clone() });
                        Resultat { resume: r.clone(), ..Resultat::ok(json_ordonne(vec![("answer", json!(r)), ("status", json!("ok"))])) }
                            .into()
                    }
                    _ => Resultat::ok(json_ordonne(vec![
                        ("note", json!("Pas de réponse de l'utilisateur. Ne suppose rien : arrête-toi ou continue avec ce qui ne dépend pas de ce choix.")),
                        ("status", json!("ok")),
                    ]))
                    .into(),
                }
            }
            specs::EXIT_PLAN_MODE => self.sortir_du_plan(tour, id, a),
            _ => Resultat::erreur(format!(
                "{nom} n'est pas encore disponible dans la version PC. Dis à l'utilisateur ce qu'il faudrait chercher ou lire."
            ))
            .into(),
        }
    }

    fn taches(&self, tour: i64, a: &Value) -> Resultat {
        let Some(brutes) = tableau(a, &["todos"]) else {
            return Resultat::erreur("TodoWrite demande « todos » : la liste complète des tâches.");
        };
        if brutes.len() > 20 {
            return Resultat::erreur("TodoWrite accepte 20 tâches au plus : regroupe-les.");
        }
        let mut items: Vec<Tache> = Vec::new();
        for t in &brutes {
            let content = chaine(t, &["content"]).trim().to_string();
            let status = chaine(t, &["status"]);
            if content.is_empty() {
                return Resultat::erreur("Chaque tâche a un « content » non vide.");
            }
            if !["pending", "in_progress", "completed"].contains(&status.as_str()) {
                return Resultat::erreur(format!(
                    "Statut inconnu « {status} » : pending, in_progress ou completed."
                ));
            }
            if items.iter().any(|x| x.content == content) {
                return Resultat::erreur(format!("La tâche « {content} » est en double."));
            }
            items.push(Tache {
                content,
                status,
                active_form: chaine(t, &["activeForm", "active_form"]),
            });
        }
        if items.iter().filter(|t| t.status == "in_progress").count() > 1 {
            return Resultat::erreur("Une seule tâche in_progress à la fois.");
        }
        let faites = items.iter().filter(|t| t.status == "completed").count();
        let resume = format!("{faites}/{} terminées", items.len());
        self.noter(Evenement::Taches { turn: tour, items });
        Resultat {
            resume: resume.clone(),
            ..Resultat::ok(json_ordonne(vec![
                ("todos", json!(resume)),
                (
                    "note",
                    json!("Liste enregistrée. Garde-la à jour au fil du travail."),
                ),
                ("status", json!("ok")),
            ]))
        }
    }

    fn sortir_du_plan(&self, tour: i64, id: &str, a: &Value) -> Issue {
        let resultat =
            |t: String| Resultat::ok(json_ordonne(vec![("result", json!(t)), ("status", json!("ok"))]));
        if !self.mode().est_plan() {
            return resultat(
                "ExitPlanMode ne sert qu'en mode Plan. Tu n'y es pas : continue ton travail normalement."
                    .into(),
            )
            .into();
        }
        let plan = chaine(a, &["plan"]);
        if plan.trim().is_empty() {
            return Resultat::erreur("ExitPlanMode demande un « plan » en Markdown.").into();
        }
        self.noter(Evenement::Plan {
            turn: tour,
            id: id.into(),
            markdown: plan.clone(),
        });
        match self.interactions.revoir_plan(id, &plan) {
            RevuePlan::Valide(mode) => {
                let titre = plan
                    .lines()
                    .find(|l| !l.trim().is_empty())
                    .unwrap_or("plan")
                    .trim_start_matches('#')
                    .trim();
                let (an, m, j, ..) = date_utc(journal::maintenant_ms());
                let rel = format!(".altrex/plans/{an:04}-{m:02}-{j:02}-{}.md", {
                    let s = crate::espace::slug(titre);
                    if s.is_empty() {
                        "plan".to_string()
                    } else {
                        s
                    }
                });
                if let Some(r) = self.chemins.resoudre(&rel) {
                    let _ = crate::outils::fichiers::ecrire_atomiquement(&r.fichier, plan.as_bytes());
                }
                self.noter(Evenement::DecisionPlan {
                    turn: tour,
                    id: id.into(),
                    approved: true,
                    feedback: String::new(),
                    mode: Some(mode.cle()),
                });
                *self.mode_annonce.lock().unwrap() = Some(mode);
                Resultat { resume: "plan validé".into(), ..resultat(format!(
                    "Plan validé par l'utilisateur, enregistré dans {rel}. Mode : {}. Tu peux maintenant modifier le projet : reprends les étapes du plan dans TodoWrite, puis réalise-les une par une.",
                    mode.libelle()
                )) }
                .into()
            }
            RevuePlan::AModifier(retour) => {
                self.noter(Evenement::DecisionPlan {
                    turn: tour,
                    id: id.into(),
                    approved: false,
                    feedback: retour.clone(),
                    mode: None,
                });
                Resultat { resume: "plan à modifier".into(), ..resultat(format!(
                    "L'utilisateur demande des changements au plan : {retour}. Reste en mode Plan, ajuste le plan, puis présente-le de nouveau avec ExitPlanMode."
                )) }
                .into()
            }
            RevuePlan::Refuse => {
                self.noter(Evenement::DecisionPlan {
                    turn: tour,
                    id: id.into(),
                    approved: false,
                    feedback: String::new(),
                    mode: None,
                });
                Issue {
                    resultat: Resultat::refus(
                        "L'utilisateur a refusé le plan. Arrête-toi et attends sa prochaine demande.",
                    ),
                    fin_du_tour: Some("done"),
                }
            }
        }
    }

    fn bash(&self, tour: i64, id: &str, a: &Value, debut: Instant) -> Issue {
        let commande = chaine(a, &["command", "cmd"]);
        let justification = Some(chaine(a, &["justification"])).filter(|j| !j.trim().is_empty());
        let action = Action::Commande {
            commande: commande.clone(),
            justification,
        };
        match permissions::decider_pour(self.mode(), &self.accords(), &action, self.env.dialecte) {
            Decision::Autoriser => {}
            Decision::Refuser(m) => return Resultat::refus(m).into(),
            Decision::Demander(q) => {
                if let Err(issue) =
                    self.demander_accord(tour, id, specs::BASH, &q, commande.clone(), false, &action.cle())
                {
                    return issue;
                }
            }
        }
        if booleen(a, &["run_in_background", "background"]) {
            return match self.shell.demarrer_tache(&commande) {
                Ok(job) => Resultat {
                    resume: format!("arrière-plan ({job})"),
                    ..Resultat::ok(json_ordonne(vec![
                        ("job_id", json!(job)),
                        ("note", json!(format!("Lancée en arrière-plan. Lis sa sortie avec BashOutput(\"{job}\"), arrête-la avec KillShell(\"{job}\")."))),
                        ("status", json!("ok")),
                    ]))
                }
                .into(),
                Err(e) => Resultat::erreur(format!("Impossible de lancer la commande : {e}")).into(),
            };
        }
        let reste = self
            .limites
            .max_duree_ms
            .saturating_sub(debut.elapsed().as_millis() as u64)
            .max(5_000);
        let attente = shell::attente(&commande, entier(a, &["timeout"]), reste);
        let fin = match self.shell.executer(&commande, attente, &self.arret) {
            Ok(f) => f,
            Err(e) => return Resultat::erreur(format!("Impossible de lancer la commande : {e}")).into(),
        };
        if fin.arretee {
            return Resultat::erreur("Commande arrêtée par l'utilisateur.").into();
        }
        // Sortie longue : la fin seulement pour le modèle, le tout dans un fichier du projet.
        let max = self.limites.max_car_resultat;
        let n = fin.sortie.chars().count();
        let (sortie, complet) = if n > max {
            let rel = format!(
                ".altrex/sorties/{}.txt",
                format!("{}-{id}", self.journal.id())
                    .replace(|c: char| !(c.is_ascii_alphanumeric() || "._-".contains(c)), "_")
            );
            let ecrit = self
                .chemins
                .resoudre(&rel)
                .map(|r| {
                    crate::outils::fichiers::ecrire_atomiquement(&r.fichier, fin.sortie.as_bytes()).is_ok()
                })
                .unwrap_or(false);
            (
                fin.sortie.chars().skip(n - max).collect::<String>(),
                if ecrit { Some(rel) } else { None },
            )
        } else {
            (fin.sortie.clone(), None)
        };
        let note_complet = complet.as_ref().map(|rel| {
            format!("Sortie longue ({n} caractères) : seule la fin est montrée. Le tout est dans {rel} : lis-le avec Read ou cherche dedans avec Grep.")
        });
        if let Some(job) = &fin.tache {
            let depuis = if fin.detachee == Some("cap") {
                "Toujours en cours après 30 min".to_string()
            } else {
                format!(
                    "Toujours en cours, sans nouvelle sortie depuis {} s",
                    attente.silence_ms / 1000
                )
            };
            return Resultat {
                resume: format!("en cours ({job})"),
                ..Resultat::ok(json_ordonne(vec![
                    ("still_running", json!(true)),
                    ("job_id", json!(job)),
                    ("output", json!(sortie)),
                    ("note", json!(format!("{depuis} : la commande n'est pas arrêtée, elle continue en arrière-plan ({job}). Suis-la avec BashOutput(\"{job}\") jusqu'à state=finished et vérifie son exit_code ; arrête-la avec KillShell(\"{job}\"). Ne la relance pas."))),
                    ("status", json!("ok")),
                ]))
            }
            .into();
        }
        let statut = if fin.code == 0 { "ok" } else { "error" };
        Resultat {
            statut: statut.into(),
            modele: json_ordonne(vec![
                ("exit_code", json!(fin.code)),
                ("output", json!(sortie)),
                ("cwd", json!(fin.cwd)),
                ("full_output", json!(complet)),
                ("note", json!(note_complet)),
                ("status", json!(statut)),
            ]),
            resume: format!("code {}", fin.code),
            detail: fin.sortie.chars().skip(n.saturating_sub(4_000)).collect(),
            ..Default::default()
        }
        .into()
    }
}

/// Les puces de mode, pour l'écran.
pub fn puces() -> Vec<(&'static str, &'static str, Mode)> {
    PRESETS.to_vec()
}
