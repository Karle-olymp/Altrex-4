//! Les autorisations de Forge, sur deux axes (bac à sable, approbation), et la politique unique
//! qui décide si une action passe, est demandée ou est refusée. Port fidèle de
//! `forge/engine/ForgeMode.kt` et `forge/engine/PermissionPolicy.kt` ; les vecteurs de
//! `docs/forge/contrat/permissions.json` sont exécutés des deux côtés.

use crate::commandes::{self, Dialecte};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Bac {
    Lecture,
    Projet,
    Total,
}

impl Bac {
    pub fn nom(self) -> &'static str {
        match self {
            Bac::Lecture => "LECTURE",
            Bac::Projet => "PROJET",
            Bac::Total => "TOTAL",
        }
    }
    pub fn libelle(self) -> &'static str {
        match self {
            Bac::Lecture => "lecture seule",
            Bac::Projet => "écriture dans le projet",
            Bac::Total => "accès total",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Approbation {
    Demander,
    Jamais,
}

impl Approbation {
    pub fn nom(self) -> &'static str {
        match self {
            Approbation::Demander => "DEMANDER",
            Approbation::Jamais => "JAMAIS",
        }
    }
    pub fn libelle(self) -> &'static str {
        match self {
            Approbation::Demander => "demander",
            Approbation::Jamais => "jamais",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Mode {
    pub bac: Bac,
    pub approbation: Approbation,
}

/// Les quatre puces de l'écran.
pub const PRESETS: [(&str, &str, Mode); 4] = [
    (
        "Demander",
        "Demande avant chaque modification.",
        Mode {
            bac: Bac::Projet,
            approbation: Approbation::Demander,
        },
    ),
    (
        "Éditions auto",
        "Modifie seul, demande pour les commandes.",
        Mode {
            bac: Bac::Projet,
            approbation: Approbation::Jamais,
        },
    ),
    (
        "Plan",
        "Lecture seule, puis un plan à valider.",
        Mode {
            bac: Bac::Lecture,
            approbation: Approbation::Jamais,
        },
    ),
    (
        "Tout autoriser",
        "Agit sans demander, dans le projet.",
        Mode {
            bac: Bac::Total,
            approbation: Approbation::Jamais,
        },
    ),
];

impl Mode {
    pub const DEFAUT: Mode = Mode {
        bac: Bac::Projet,
        approbation: Approbation::Demander,
    };
    pub const PLAN: Mode = Mode {
        bac: Bac::Lecture,
        approbation: Approbation::Jamais,
    };

    pub fn est_plan(self) -> bool {
        self.bac == Bac::Lecture
    }

    pub fn libelle(self) -> &'static str {
        PRESETS
            .iter()
            .find(|p| p.2 == self)
            .map(|p| p.0)
            .unwrap_or("Personnalisé")
    }

    /// Clé gardée dans le journal : « PROJET/DEMANDER ».
    pub fn cle(self) -> String {
        format!("{}/{}", self.bac.nom(), self.approbation.nom())
    }

    pub fn lire(cle: &str) -> Option<Mode> {
        let (b, a) = cle.split_once('/')?;
        let bac = [Bac::Lecture, Bac::Projet, Bac::Total]
            .into_iter()
            .find(|x| x.nom() == b)?;
        let approbation = [Approbation::Demander, Approbation::Jamais]
            .into_iter()
            .find(|x| x.nom() == a)?;
        Some(Mode { bac, approbation })
    }
}

/// Ce qu'un outil veut faire.
#[derive(Debug, Clone, PartialEq)]
pub enum Action {
    /// `chemin` relatif à la racine du projet, normalisé ; `None` : hors du projet.
    Ecrire { chemin: Option<String>, nouveau: bool },
    Commande {
        commande: String,
        justification: Option<String>,
    },
}

impl Action {
    /// Clé qui retient un refus pour cette action exacte.
    pub fn cle(&self) -> String {
        match self {
            Action::Ecrire { chemin, .. } => format!("write:{}", chemin.as_deref().unwrap_or("?")),
            Action::Commande { commande, .. } => {
                format!(
                    "cmd:{}",
                    commande.split_whitespace().collect::<Vec<_>>().join(" ")
                )
            }
        }
    }
}

pub const GRANT_EDITS: &str = "*fichiers*";

#[derive(Debug, Clone, PartialEq)]
pub struct Demande {
    pub titre: String,
    pub detail: String,
    pub risque: bool,
    pub escalade: bool,
    pub justification: Option<String>,
    /// Ce que « Toujours » couvrirait : un programme, [`GRANT_EDITS`], ou rien.
    pub grant: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Decision {
    Autoriser,
    Refuser(String),
    Demander(Demande),
}

/// Ce que l'utilisateur a permis ou refusé pendant une session (gardé dans le journal).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Accords {
    pub programmes: BTreeSet<String>,
    pub fichiers: bool,
    /// Clé d'action → motif du refus.
    pub refus: BTreeMap<String, String>,
    /// Actions déjà redemandées une fois avec une justification.
    pub escalades: BTreeSet<String>,
}

impl Accords {
    pub fn accorder(&mut self, grant: &str) {
        if grant == GRANT_EDITS {
            self.fichiers = true;
        } else {
            self.programmes.insert(grant.to_string());
        }
    }
}

const PLAN_DIRS: [&str; 2] = ["blueprint/", ".altrex/plans/"];
pub const MOTIF_PLAN: &str =
    "Mode Plan : lecture seule. Tu peux seulement écrire dans blueprint/ ou .altrex/plans/. \
Termine ton plan et présente-le avec ExitPlanMode ; l'utilisateur le validera.";

pub fn chemin_de_plan(chemin: &str) -> bool {
    PLAN_DIRS
        .iter()
        .any(|d| chemin == d.trim_end_matches('/') || chemin.starts_with(d))
}

pub fn est_protege(chemin: &str) -> bool {
    let p = chemin.trim_start_matches('/');
    if chemin_de_plan(p) {
        return false;
    }
    p == ".git" || p.starts_with(".git/") || p == ".altrex" || p.starts_with(".altrex/")
}

fn programme_accorde(commande: &str, accords: &Accords) -> bool {
    if accords.programmes.is_empty() || !commandes::est_simple(commande) {
        return false;
    }
    commande.split('|').all(|seg| {
        commandes::programme_nu(seg.split_whitespace().next())
            .map(|p| accords.programmes.contains(&p))
            .unwrap_or(false)
    })
}

/// La décision, avec le dialecte POSIX (celui du téléphone et des vecteurs partagés).
pub fn decider(mode: Mode, accords: &Accords, action: &Action) -> Decision {
    decider_pour(mode, accords, action, Dialecte::Posix)
}

pub fn decider_pour(mode: Mode, accords: &Accords, action: &Action, d: Dialecte) -> Decision {
    let cle = action.cle();
    let base = match action {
        Action::Ecrire { chemin, nouveau } => decider_ecriture(mode, accords, chemin.as_deref(), *nouveau),
        Action::Commande {
            commande,
            justification,
        } => decider_commande(mode, accords, commande, justification.as_deref(), d),
    };
    let Decision::Demander(demande) = base else {
        return base;
    };
    // Refusée plus tôt dans la session : refusée de nouveau, sauf justification (une seule fois).
    let Some(precedent) = accords.refus.get(&cle) else {
        return Decision::Demander(demande);
    };
    let justification = match action {
        Action::Commande {
            justification: Some(j),
            ..
        } => j.trim().to_string(),
        _ => String::new(),
    };
    if justification.is_empty() || accords.escalades.contains(&cle) {
        let motif = if precedent.trim().is_empty() {
            String::new()
        } else {
            format!(" (motif : {precedent})")
        };
        return Decision::Refuser(format!(
            "L'utilisateur a déjà refusé cette action dans cette session{motif}. Ne la redemande pas ; change d'approche ou demande-lui ce qu'il préfère."
        ));
    }
    Decision::Demander(Demande {
        escalade: true,
        justification: Some(justification),
        grant: None,
        ..demande
    })
}

fn demande(titre: String, detail: &str) -> Demande {
    Demande {
        titre,
        detail: detail.to_string(),
        risque: false,
        escalade: false,
        justification: None,
        grant: None,
    }
}

fn decider_ecriture(mode: Mode, accords: &Accords, chemin: Option<&str>, nouveau: bool) -> Decision {
    let Some(chemin) = chemin else {
        return Decision::Refuser(
            "Ce chemin est hors du projet : les outils de fichiers restent dans le dossier du projet.".into(),
        );
    };
    let verbe = if nouveau { "créer" } else { "modifier" };
    if mode.bac == Bac::Lecture {
        if !chemin_de_plan(chemin) {
            return Decision::Refuser(MOTIF_PLAN.into());
        }
        return if mode.approbation == Approbation::Demander {
            Decision::Demander(Demande {
                grant: Some(GRANT_EDITS.into()),
                ..demande(format!("Forge veut {verbe} {chemin}"), chemin)
            })
        } else {
            Decision::Autoriser
        };
    }
    if est_protege(chemin) {
        return Decision::Demander(Demande {
            risque: true,
            ..demande(
                format!("Forge veut {verbe} {chemin} (dossier protégé)"),
                &format!(
                    "{chemin} fait partie d'un dossier protégé (.git ou .altrex) : il est toujours demandé."
                ),
            )
        });
    }
    if mode.approbation == Approbation::Demander && !accords.fichiers {
        return Decision::Demander(Demande {
            grant: Some(GRANT_EDITS.into()),
            ..demande(format!("Forge veut {verbe} {chemin}"), chemin)
        });
    }
    Decision::Autoriser
}

fn decider_commande(
    mode: Mode,
    accords: &Accords,
    commande: &str,
    justification: Option<&str>,
    d: Dialecte,
) -> Decision {
    let cmd = commande.trim();
    if cmd.is_empty() {
        return Decision::Refuser("La commande est vide.".into());
    }
    let dangereuse = commandes::est_dangereuse_pour(cmd, d);
    if !dangereuse && commandes::lecture_seule_pour(cmd, d) {
        return Decision::Autoriser;
    }
    if mode.bac == Bac::Lecture {
        return Decision::Refuser(MOTIF_PLAN.into());
    }
    if dangereuse {
        return Decision::Demander(Demande {
            risque: true,
            justification: justification.map(str::to_string),
            ..demande("Commande à risque".into(), cmd)
        });
    }
    if programme_accorde(cmd, accords) {
        return Decision::Autoriser;
    }
    let question = Decision::Demander(Demande {
        justification: justification.map(str::to_string),
        grant: commandes::programme(cmd),
        ..demande("Forge veut lancer une commande".into(), cmd)
    });
    match (mode.approbation, mode.bac) {
        (Approbation::Demander, _) => question,
        (_, Bac::Total) => Decision::Autoriser,
        // PROJET : rien ne retient une commande dans le projet, elle est donc demandée.
        _ => question,
    }
}
