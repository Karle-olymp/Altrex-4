//! Les outils de Forge sur PC.

pub mod fichiers;
pub mod recherche;
pub mod specs;

use serde_json::{Map, Value};
use std::path::{Component, Path, PathBuf};

/// Le résultat d'un outil : ce que reçoit le modèle (`modele`, JSON aux clés ordonnées, `status`
/// en dernier) et ce que montre l'écran.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct Resultat {
    /// « ok », « error » ou « refused ».
    pub statut: String,
    pub modele: String,
    pub resume: String,
    pub detail: String,
    pub ajoutees: i64,
    pub retirees: i64,
    pub chemin: Option<String>,
}

pub const NOTE_REFUS: &str =
    "Ne relance pas la même action telle quelle : adapte ton approche, ou explique la situation à l'utilisateur.";

/// JSON aux clés dans l'ordre donné ; les valeurs nulles sont omises.
pub fn json_ordonne(paires: Vec<(&str, Value)>) -> String {
    let mut m = Map::new();
    for (k, v) in paires {
        if !v.is_null() {
            m.insert(k.to_string(), v);
        }
    }
    Value::Object(m).to_string()
}

impl Resultat {
    pub fn ok(modele: String) -> Resultat {
        Resultat {
            statut: "ok".into(),
            modele,
            ..Default::default()
        }
    }
    pub fn erreur(message: impl Into<String>) -> Resultat {
        let message = message.into();
        Resultat {
            statut: "error".into(),
            modele: json_ordonne(vec![
                ("error", Value::String(message.clone())),
                ("status", "error".into()),
            ]),
            resume: message.lines().next().unwrap_or("").chars().take(80).collect(),
            ..Default::default()
        }
    }
    pub fn refus(motif: impl Into<String>) -> Resultat {
        let motif = motif.into();
        Resultat {
            statut: "refused".into(),
            modele: json_ordonne(vec![
                ("refused", Value::String(motif.clone())),
                ("note", NOTE_REFUS.into()),
                ("status", "refused".into()),
            ]),
            resume: "refusé".into(),
            detail: motif,
            ..Default::default()
        }
    }
}

/// Un chemin résolu dans le projet : `rel` avec des « / », `fichier` sur le disque.
#[derive(Debug, Clone, PartialEq)]
pub struct Resolu {
    pub rel: String,
    pub fichier: PathBuf,
}

/// La racine d'un projet et la résolution des chemins que donne le modèle.
#[derive(Debug, Clone)]
pub struct Chemins {
    pub racine: PathBuf,
}

impl Chemins {
    pub fn nouveau(racine: &Path) -> Chemins {
        Chemins {
            racine: racine.canonicalize().unwrap_or_else(|_| racine.to_path_buf()),
        }
    }

    /// `None` : hors du projet (chemin absolu ailleurs, `..` qui sort, lien symbolique vers dehors).
    pub fn resoudre(&self, brut: &str) -> Option<Resolu> {
        let brut = brut.trim();
        let brut = if cfg!(windows) {
            brut.replace('\\', "/")
        } else {
            brut.to_string()
        };
        let p = Path::new(&brut);
        let relatif: PathBuf = if p.is_absolute() {
            let abs = normaliser(p)?;
            abs.strip_prefix(&self.racine).ok()?.to_path_buf()
        } else {
            p.to_path_buf()
        };
        let mut parties: Vec<String> = Vec::new();
        for c in relatif.components() {
            match c {
                Component::CurDir => {}
                Component::ParentDir => {
                    parties.pop()?;
                }
                Component::Normal(x) => parties.push(x.to_string_lossy().to_string()),
                _ => return None,
            }
        }
        let rel = parties.join("/");
        let fichier = parties.iter().fold(self.racine.clone(), |a, x| a.join(x));
        // Un lien symbolique ne doit pas mener hors du projet : l'ancêtre existant le plus profond
        // est résolu et doit rester sous la racine.
        let mut existant = fichier.as_path();
        loop {
            if existant.exists() || existant.symlink_metadata().is_ok() {
                break;
            }
            existant = existant.parent()?;
        }
        let reel = existant.canonicalize().ok()?;
        if !reel.starts_with(&self.racine) {
            return None;
        }
        Some(Resolu { rel, fichier })
    }
}

fn normaliser(p: &Path) -> Option<PathBuf> {
    let mut out = PathBuf::new();
    for c in p.components() {
        match c {
            Component::ParentDir => {
                if !out.pop() {
                    return None;
                }
            }
            Component::CurDir => {}
            autre => out.push(autre.as_os_str()),
        }
    }
    Some(out)
}
