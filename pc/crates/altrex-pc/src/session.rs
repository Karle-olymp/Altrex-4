//! Une session Forge ouverte, sans rien de graphique : le moteur tourne sur son propre fil, l'écran
//! lit le journal et répond aux questions (accord, question, plan). Testé sans fenêtre.

use crate::reglages::{Reglages, Source};
use altrex_core::apercu::Apercu;
use altrex_core::coffre::Coffre;
use altrex_core::espace::Espace;
use altrex_core::journal::{self, Journal};
use altrex_core::llama::{self, Serveur};
use altrex_core::memoire::{self, Environnement};
use altrex_core::modeles::{self, Modele, ModeleAnthropic, ModeleOpenAi, Protocole};
use altrex_core::moteur::{Choix, DemandeAccord, Interactions, Moteur, ReponseAccord, RevuePlan, Signal};
use altrex_core::permissions::Mode;
use altrex_core::shell::Shell;
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Sans réponse de l'utilisateur au bout de ce délai : un refus (5 min, comme sur le téléphone).
pub const DELAI_REPONSE: Duration = Duration::from_secs(5 * 60);

/// Ce que le moteur attend de l'utilisateur.
#[derive(Debug, Clone, PartialEq)]
pub enum Attente {
    Accord(DemandeAccord),
    Question { question: String, options: Vec<String> },
    Plan { markdown: String },
}

/// La réponse de l'écran.
#[derive(Debug, Clone, PartialEq)]
pub enum ReponseEcran {
    Accord(Choix, String),
    Question(Option<String>),
    Plan(RevuePlan),
}

/// Le pont entre le fil du moteur (qui attend) et l'écran (qui répond).
pub struct Pont {
    attente: Mutex<Option<(Attente, Sender<ReponseEcran>)>>,
    arret: Mutex<Option<Arc<AtomicBool>>>,
    reveil: Box<dyn Fn() + Send + Sync>,
    delai: Duration,
}

impl Pont {
    pub fn nouveau(reveil: Box<dyn Fn() + Send + Sync>, delai: Duration) -> Pont {
        Pont {
            attente: Mutex::new(None),
            arret: Mutex::new(None),
            reveil,
            delai,
        }
    }

    /// Ce qui attend une réponse, s'il y a lieu.
    pub fn en_attente(&self) -> Option<Attente> {
        self.attente.lock().unwrap().as_ref().map(|a| a.0.clone())
    }

    /// Donne la réponse de l'utilisateur à ce qui attend.
    pub fn repondre(&self, r: ReponseEcran) {
        if let Some((_, tx)) = self.attente.lock().unwrap().take() {
            let _ = tx.send(r);
        }
    }

    fn attendre(&self, a: Attente) -> Option<ReponseEcran> {
        let (tx, rx): (Sender<ReponseEcran>, Receiver<ReponseEcran>) = mpsc::channel();
        *self.attente.lock().unwrap() = Some((a, tx));
        (self.reveil)();
        let debut = Instant::now();
        let reponse = loop {
            if let Ok(r) = rx.recv_timeout(Duration::from_millis(60)) {
                break Some(r);
            }
            let arrete = self
                .arret
                .lock()
                .unwrap()
                .as_ref()
                .map(|a| a.load(Ordering::SeqCst))
                .unwrap_or(false);
            if arrete || debut.elapsed() >= self.delai {
                break None;
            }
        };
        *self.attente.lock().unwrap() = None;
        (self.reveil)();
        reponse
    }

    fn arrete(&self) -> bool {
        self.arret
            .lock()
            .unwrap()
            .as_ref()
            .map(|a| a.load(Ordering::SeqCst))
            .unwrap_or(false)
    }
}

impl Interactions for Pont {
    fn approuver(&self, d: &DemandeAccord) -> ReponseAccord {
        match self.attendre(Attente::Accord(d.clone())) {
            Some(ReponseEcran::Accord(choix, motif)) => ReponseAccord { choix, motif },
            _ if self.arrete() => ReponseAccord {
                choix: Choix::Arreter,
                motif: String::new(),
            },
            _ => ReponseAccord {
                choix: Choix::SansReponse,
                motif: String::new(),
            },
        }
    }
    fn demander(&self, _id: &str, question: &str, options: &[String]) -> Option<String> {
        match self.attendre(Attente::Question {
            question: question.to_string(),
            options: options.to_vec(),
        }) {
            Some(ReponseEcran::Question(r)) => r,
            _ => None,
        }
    }
    fn revoir_plan(&self, _id: &str, markdown: &str) -> RevuePlan {
        match self.attendre(Attente::Plan {
            markdown: markdown.to_string(),
        }) {
            Some(ReponseEcran::Plan(r)) => r,
            _ if self.arrete() => RevuePlan::Refuse,
            _ => RevuePlan::AModifier("pas de réponse de l'utilisateur".into()),
        }
    }
}

/// Le modèle des réglages, prêt à servir. Le serveur llama lancé par l'appli est rendu avec lui
/// (il s'arrête quand il est lâché).
pub fn preparer_modele(r: &Reglages, coffre: &Coffre) -> Result<(Arc<dyn Modele>, Option<Serveur>), String> {
    match r.source {
        Source::Cloud => {
            let f = modeles::fournisseur(&r.fournisseur)
                .ok_or_else(|| format!("Fournisseur inconnu : {}", r.fournisseur))?;
            let cle = coffre
                .lire(f.id)
                .ok_or_else(|| format!("Aucune clé {} : ajoute-la dans les Réglages.", f.libelle))?;
            let modele = r.modele_cloud.trim();
            if modele.is_empty() {
                return Err("Choisis un modèle dans les Réglages.".into());
            }
            let m: Arc<dyn Modele> = match f.protocole {
                Protocole::Anthropic => Arc::new(ModeleAnthropic::nouveau(&cle, modele)),
                Protocole::OpenAi => Arc::new(ModeleOpenAi {
                    libelle: modele.to_string(),
                    base: f.base.to_string(),
                    cle: Some(cle),
                    modele: modele.to_string(),
                    natif: true,
                    local: false,
                    budget_car: 120_000,
                }),
            };
            Ok((m, None))
        }
        Source::Llama => {
            let (base, serveur) = if !r.llama_adresse.trim().is_empty() {
                let base = r.llama_adresse.trim().trim_end_matches('/').to_string();
                if !llama::en_bonne_sante(&base) {
                    return Err(format!("Aucun llama-server ne répond à {base}. Lance-le, ou laisse l'adresse vide pour qu'Altrex le lance."));
                }
                (base, None)
            } else {
                let reglage = Some(Path::new(r.llama_binaire.trim())).filter(|p| !p.as_os_str().is_empty());
                let binaire = llama::trouver_binaire(reglage).ok_or_else(|| {
                    format!(
                        "llama-server est introuvable. Télécharge-le ({}) et indique son chemin dans les Réglages.",
                        llama::PAGE_TELECHARGEMENT
                    )
                })?;
                if r.llama_gguf.trim().is_empty() {
                    return Err("Indique le fichier GGUF du modèle dans les Réglages.".into());
                }
                let s = Serveur::demarrer(
                    &binaire,
                    Path::new(r.llama_gguf.trim()),
                    r.llama_contexte,
                    r.llama_couches_gpu,
                    Duration::from_secs(180),
                )?;
                (s.base(), Some(s))
            };
            let (ctx, nom) = llama::proprietes(&base);
            let jetons = ctx.unwrap_or(r.llama_contexte as usize);
            let libelle = nom.unwrap_or_else(|| "modèle local".into());
            let m = ModeleOpenAi {
                libelle,
                base: format!("{base}/v1"),
                cle: None,
                modele: "local".into(),
                natif: false,
                local: true,
                // Environ 3 caractères par jeton ; un tiers du contexte reste libre pour la réponse.
                budget_car: jetons * 2,
            };
            Ok((Arc::new(m), serveur))
        }
    }
}

/// Une session ouverte.
pub struct Session {
    pub projet: String,
    pub journal: Arc<Journal>,
    pub moteur: Arc<Moteur>,
    pub pont: Arc<Pont>,
    pub nom_modele: String,
    en_cours: Arc<AtomicBool>,
    /// Le texte de la réponse en cours d'écriture.
    pub direct: Arc<Mutex<String>>,
    pub outil: Arc<Mutex<String>>,
    apercu: Mutex<Option<Apercu>>,
    _serveur: Option<Serveur>,
}

impl Session {
    /// Ouvre la session `id` du projet, ou en commence une nouvelle.
    #[allow(clippy::too_many_arguments)]
    pub fn ouvrir(
        espace: &Espace,
        projet: &str,
        id: Option<&str>,
        modele: Arc<dyn Modele>,
        serveur: Option<Serveur>,
        mode: Mode,
        reveil: Arc<dyn Fn() + Send + Sync>,
        delai: Duration,
    ) -> Result<Session, String> {
        let journal = match id.and_then(|i| espace.ouvrir_session(projet, i)) {
            Some(j) => j,
            None => espace
                .nouvelle_session(projet, &modele.nom(), &mode.cle())
                .map_err(|e| e.to_string())?,
        };
        let journal = Arc::new(journal);
        let env = Environnement::detecter();
        let dossier = espace.dossier(projet);
        let systeme = memoire::prompt_systeme(espace, projet, &modele.nom(), &env);
        let shell = Arc::new(Shell::nouveau(&dossier, env.dialecte));
        let r = reveil.clone();
        let pont = Arc::new(Pont::nouveau(Box::new(move || r()), delai));
        let direct = Arc::new(Mutex::new(String::new()));
        let outil = Arc::new(Mutex::new(String::new()));
        let (d, o) = (direct.clone(), outil.clone());
        let signal = Box::new(move |s: Signal| {
            match s {
                Signal::Texte(t) => d.lock().unwrap().push_str(&t),
                Signal::Journal => d.lock().unwrap().clear(),
                Signal::Outil(n) => *o.lock().unwrap() = n,
            }
            reveil();
        });
        let nom_modele = modele.nom();
        let moteur = Arc::new(Moteur::nouveau(
            journal.clone(),
            modele,
            &dossier,
            shell,
            pont.clone(),
            systeme,
            env,
            signal,
        ));
        *pont.arret.lock().unwrap() = Some(moteur.arret.clone());
        Ok(Session {
            projet: projet.to_string(),
            journal,
            moteur,
            pont,
            nom_modele,
            en_cours: Arc::new(AtomicBool::new(false)),
            direct,
            outil,
            apercu: Mutex::new(None),
            _serveur: serveur,
        })
    }

    pub fn en_cours(&self) -> bool {
        self.en_cours.load(Ordering::SeqCst)
    }

    /// Envoie `texte` comme prochain tour (ignoré pendant qu'un tour tourne).
    pub fn envoyer(&self, texte: &str) -> bool {
        if texte.trim().is_empty() || self.en_cours.swap(true, Ordering::SeqCst) {
            return false;
        }
        let (moteur, en_cours, texte) = (
            self.moteur.clone(),
            self.en_cours.clone(),
            texte.trim().to_string(),
        );
        std::thread::spawn(move || {
            moteur.tour(&texte);
            en_cours.store(false, Ordering::SeqCst);
            (moteur.signal)(Signal::Journal);
        });
        true
    }

    /// Arrêter : la réponse en cours s'arrête, ce qui attend l'utilisateur est refusé, la commande en cours est tuée.
    pub fn arreter(&self) {
        self.moteur.arret.store(true, Ordering::SeqCst);
    }

    pub fn mode(&self) -> Mode {
        journal::replier(&self.journal.evenements()).mode()
    }

    /// L'adresse de l'aperçu local du projet (le serveur est lancé au premier appel).
    pub fn apercu(&self) -> Result<String, String> {
        let mut a = self.apercu.lock().unwrap();
        if a.is_none() {
            *a = Some(Apercu::demarrer(&self.moteur.chemins.racine).map_err(|e| e.to_string())?);
        }
        Ok(a.as_ref().map(|x| x.adresse()).unwrap_or_default())
    }
}

impl Drop for Session {
    fn drop(&mut self) {
        self.arreter();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use altrex_core::journal::{Evenement, Message};
    use altrex_core::modeles::{Erreur, Reponse};
    use serde_json::Value;

    /// Un modèle qui rend des textes écrits d'avance.
    struct Faux(Mutex<Vec<String>>);

    impl Modele for Faux {
        fn nom(&self) -> String {
            "faux".into()
        }
        fn local(&self) -> bool {
            true
        }
        fn budget_car(&self) -> usize {
            40_000
        }
        fn generer(
            &self,
            _s: &str,
            _m: &[Message],
            _o: &[Value],
            sur: &mut dyn FnMut(&str),
            _a: &AtomicBool,
        ) -> Result<Reponse, Erreur> {
            let mut r = self.0.lock().unwrap();
            let texte = if r.is_empty() {
                "Fini.".to_string()
            } else {
                r.remove(0)
            };
            sur(&texte);
            Ok(Reponse {
                texte,
                ..Default::default()
            })
        }
    }

    fn dossier(nom: &str) -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!("altrex-pc-{nom}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    fn session(nom: &str, reponses: &[&str], delai: Duration) -> (Espace, Session) {
        let e = Espace::nouveau(&dossier(nom));
        e.creer("p").unwrap();
        let m: Arc<dyn Modele> = Arc::new(Faux(Mutex::new(reponses.iter().map(|s| s.to_string()).collect())));
        let s = Session::ouvrir(&e, "p", None, m, None, Mode::DEFAUT, Arc::new(|| {}), delai).unwrap();
        (e, s)
    }

    fn attendre(s: &Session, quoi: impl Fn(&Session) -> bool) {
        let debut = Instant::now();
        while !quoi(s) {
            assert!(debut.elapsed() < Duration::from_secs(10), "attente trop longue");
            std::thread::sleep(Duration::from_millis(20));
        }
    }

    const ECRIRE: &str = "<tool_call>\n{\"name\": \"Write\", \"arguments\": {\"file_path\": \"index.html\", \"content\": \"<h1>Bonjour</h1>\"}}\n</tool_call>";

    #[test]
    fn session_de_bout_en_bout_avec_accord_et_apercu() {
        let (e, s) = session("bout", &[ECRIRE, "Page créée."], DELAI_REPONSE);
        assert!(s.envoyer("Crée une page d'accueil"));
        assert!(!s.envoyer("pendant le tour"), "un seul tour à la fois");
        attendre(&s, |s| s.pont.en_attente().is_some());
        let Some(Attente::Accord(d)) = s.pont.en_attente() else {
            panic!("un accord est attendu")
        };
        assert_eq!(d.titre, "Forge veut créer index.html");
        assert!(d.est_diff && d.detail.contains("+<h1>Bonjour</h1>"));
        assert!(
            !e.dossier("p").join("index.html").exists(),
            "rien n'est écrit avant l'accord"
        );
        s.pont.repondre(ReponseEcran::Accord(Choix::Oui, String::new()));
        attendre(&s, |s| !s.en_cours());
        assert_eq!(
            std::fs::read_to_string(e.dossier("p").join("index.html")).unwrap(),
            "<h1>Bonjour</h1>"
        );
        assert!(s.pont.en_attente().is_none());
        assert_eq!(s.mode(), Mode::DEFAUT);

        // La session est listée et se reprend avec son historique.
        let liste = e.sessions("p");
        assert_eq!(
            (
                liste.len(),
                liste[0].titre.as_str(),
                liste[0].derniere_fin.as_deref()
            ),
            (1, "Crée une page d'accueil", Some("done"))
        );
        let m: Arc<dyn Modele> = Arc::new(Faux(Mutex::new(vec![])));
        let reprise = Session::ouvrir(
            &e,
            "p",
            Some(&liste[0].id),
            m,
            None,
            Mode::DEFAUT,
            Arc::new(|| {}),
            DELAI_REPONSE,
        )
        .unwrap();
        assert_eq!(reprise.journal.evenements(), s.journal.evenements());

        // L'aperçu sert le projet sur 127.0.0.1.
        let adresse = s.apercu().unwrap();
        assert_eq!(adresse, s.apercu().unwrap(), "un seul serveur par session");
        let page = ureq_get(&adresse);
        assert!(page.contains("<h1>Bonjour</h1>"), "{page}");
    }

    fn ureq_get(adresse: &str) -> String {
        use std::io::{Read, Write};
        let mut f = std::net::TcpStream::connect(adresse.trim_start_matches("http://").trim_end_matches('/'))
            .unwrap();
        f.write_all(b"GET / HTTP/1.1\r\nHost: x\r\n\r\n").unwrap();
        let mut t = String::new();
        f.read_to_string(&mut t).unwrap();
        t
    }

    #[test]
    fn sans_reponse_c_est_un_refus_et_arreter_libere_l_attente() {
        let (e, s) = session("delai", &[ECRIRE, "Tant pis."], Duration::from_millis(150));
        s.envoyer("Crée la page");
        attendre(&s, |s| !s.en_cours());
        assert!(!e.dossier("p").join("index.html").exists());
        let ev = s.journal.evenements();
        assert!(ev
            .iter()
            .any(|x| matches!(x, Evenement::Accord { decision, .. } if decision == "sans_reponse")));
        assert!(ev.iter().any(|x| matches!(x, Evenement::ResultatOutil { model, .. } if model.contains("Pas de réponse de l'utilisateur : refusé par défaut."))));

        let (e, s) = session("stop", &[ECRIRE, "jamais"], DELAI_REPONSE);
        s.envoyer("Crée la page");
        attendre(&s, |s| s.pont.en_attente().is_some());
        let debut = Instant::now();
        s.arreter();
        attendre(&s, |s| !s.en_cours());
        assert!(
            debut.elapsed() < Duration::from_secs(2),
            "Arrêter répond en moins de 2 s"
        );
        assert!(!e.dossier("p").join("index.html").exists());
        assert!(
            matches!(s.journal.evenements().last(), Some(Evenement::FinTour { reason, .. }) if reason == "stopped")
        );
        // Après un arrêt, la session repart.
        assert!(s.envoyer("encore"));
        attendre(&s, |s| !s.en_cours());
    }

    #[test]
    fn question_et_plan_passent_par_le_pont() {
        let q = "<tool_call>\n{\"name\": \"AskUserQuestion\", \"arguments\": {\"question\": \"React ou Vue ?\", \"options\": [\"React\", \"Vue\"]}}\n</tool_call>";
        let plan = "<tool_call>\n{\"name\": \"ExitPlanMode\", \"arguments\": {\"plan\": \"# Plan\\n1. Faire\"}}\n</tool_call>";
        let (_e, s) = session("plan", &[q, plan, "C'est parti."], DELAI_REPONSE);
        s.moteur.changer_mode(Mode::PLAN);
        s.envoyer("Prépare");
        attendre(&s, |s| s.pont.en_attente().is_some());
        assert_eq!(
            s.pont.en_attente(),
            Some(Attente::Question {
                question: "React ou Vue ?".into(),
                options: vec!["React".into(), "Vue".into()]
            })
        );
        s.pont.repondre(ReponseEcran::Question(Some("Vue".into())));
        attendre(&s, |s| matches!(s.pont.en_attente(), Some(Attente::Plan { .. })));
        s.pont
            .repondre(ReponseEcran::Plan(RevuePlan::Valide(Mode::DEFAUT)));
        attendre(&s, |s| !s.en_cours());
        assert_eq!(s.mode(), Mode::DEFAUT);
        let ev = s.journal.evenements();
        assert!(ev
            .iter()
            .any(|x| matches!(x, Evenement::Reponse { answer, .. } if answer == "Vue")));
        assert!(ev
            .iter()
            .any(|x| matches!(x, Evenement::DecisionPlan { approved: true, .. })));
    }

    #[test]
    fn modele_des_reglages() {
        let d = dossier("modele");
        let coffre = Coffre::nouveau(&d);
        let mut r = Reglages::default();
        assert_eq!(
            preparer_modele(&r, &coffre).err().unwrap(),
            "Aucune clé Claude (Anthropic) : ajoute-la dans les Réglages."
        );
        coffre.enregistrer("anthropic", "sk-ant-test");
        let (m, serveur) = preparer_modele(&r, &coffre).unwrap();
        assert_eq!(
            (m.nom().as_str(), m.local(), serveur.is_none()),
            ("claude-opus-5-5", false, true)
        );
        r.fournisseur = "mistral".into();
        r.modele_cloud = "mistral-large".into();
        coffre.enregistrer("mistral", "cle");
        assert_eq!(preparer_modele(&r, &coffre).unwrap().0.nom(), "mistral-large");

        r.source = Source::Llama;
        r.llama_adresse = "http://127.0.0.1:1".into();
        assert!(preparer_modele(&r, &coffre)
            .err()
            .unwrap()
            .starts_with("Aucun llama-server ne répond à http://127.0.0.1:1."));
        r.llama_adresse.clear();
        r.llama_binaire = d.join("absent").to_string_lossy().to_string();
        if llama::trouver_binaire(None).is_none() {
            assert!(preparer_modele(&r, &coffre)
                .err()
                .unwrap()
                .starts_with("llama-server est introuvable."));
        }
    }
}
