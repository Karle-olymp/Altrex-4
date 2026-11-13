//! Les réglages de l'appli (`reglages.json`, sans aucun secret : les clés sont dans le coffre).

use serde_json::{json, Value};
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, PartialEq)]
pub enum Source {
    /// Un fournisseur cloud (`anthropic`, `openai`…) et un identifiant de modèle.
    Cloud,
    /// `llama-server`, lancé par l'appli ou déjà en route.
    Llama,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Reglages {
    /// Le dossier de travail (projets, sessions, ALTREX.md).
    pub espace: PathBuf,
    pub source: Source,
    pub fournisseur: String,
    pub modele_cloud: String,
    /// Adresse d'un llama-server déjà lancé (« http://127.0.0.1:8080 »), ou vide pour que l'appli le lance.
    pub llama_adresse: String,
    pub llama_binaire: String,
    pub llama_gguf: String,
    pub llama_contexte: u32,
    /// Couches du modèle envoyées au GPU (0 : processeur seul ; 99 : tout).
    pub llama_couches_gpu: i32,
}

/// Le dossier personnel de l'utilisateur.
pub fn dossier_personnel() -> PathBuf {
    let var = if cfg!(windows) { "USERPROFILE" } else { "HOME" };
    std::env::var_os(var)
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."))
}

/// Le dossier des réglages : `%APPDATA%\Altrex`, `~/Library/Application Support/Altrex` ou `~/.config/altrex`.
pub fn dossier_reglages() -> PathBuf {
    if let Some(d) = std::env::var_os("ALTREX_CONFIG") {
        return PathBuf::from(d);
    }
    if cfg!(windows) {
        std::env::var_os("APPDATA")
            .map(PathBuf::from)
            .unwrap_or_else(dossier_personnel)
            .join("Altrex")
    } else if cfg!(target_os = "macos") {
        dossier_personnel().join("Library/Application Support/Altrex")
    } else {
        std::env::var_os("XDG_CONFIG_HOME")
            .map(PathBuf::from)
            .unwrap_or_else(|| dossier_personnel().join(".config"))
            .join("altrex")
    }
}

impl Default for Reglages {
    fn default() -> Reglages {
        Reglages {
            espace: dossier_personnel().join("Altrex"),
            source: Source::Cloud,
            fournisseur: "anthropic".into(),
            modele_cloud: "claude-opus-5-5".into(),
            llama_adresse: String::new(),
            llama_binaire: String::new(),
            llama_gguf: String::new(),
            llama_contexte: 16_384,
            llama_couches_gpu: 99,
        }
    }
}

impl Reglages {
    pub fn depuis_json(v: &Value) -> Reglages {
        let d = Reglages::default();
        let s = |k: &str, defaut: &str| {
            v[k].as_str()
                .map(str::to_string)
                .unwrap_or_else(|| defaut.to_string())
        };
        Reglages {
            espace: v["espace"]
                .as_str()
                .filter(|x| !x.trim().is_empty())
                .map(PathBuf::from)
                .unwrap_or(d.espace),
            source: if v["source"] == "llama" {
                Source::Llama
            } else {
                Source::Cloud
            },
            fournisseur: s("fournisseur", &d.fournisseur),
            modele_cloud: s("modele_cloud", &d.modele_cloud),
            llama_adresse: s("llama_adresse", ""),
            llama_binaire: s("llama_binaire", ""),
            llama_gguf: s("llama_gguf", ""),
            llama_contexte: v["llama_contexte"]
                .as_u64()
                .map(|n| n as u32)
                .filter(|n| *n >= 1024)
                .unwrap_or(d.llama_contexte),
            llama_couches_gpu: v["llama_couches_gpu"]
                .as_i64()
                .map(|n| n as i32)
                .unwrap_or(d.llama_couches_gpu),
        }
    }

    pub fn en_json(&self) -> Value {
        json!({
            "espace": self.espace.to_string_lossy(),
            "source": if self.source == Source::Llama { "llama" } else { "cloud" },
            "fournisseur": self.fournisseur,
            "modele_cloud": self.modele_cloud,
            "llama_adresse": self.llama_adresse,
            "llama_binaire": self.llama_binaire,
            "llama_gguf": self.llama_gguf,
            "llama_contexte": self.llama_contexte,
            "llama_couches_gpu": self.llama_couches_gpu,
        })
    }

    pub fn charger(dossier: &Path) -> Reglages {
        std::fs::read_to_string(dossier.join("reglages.json"))
            .ok()
            .and_then(|t| serde_json::from_str::<Value>(&t).ok())
            .map(|v| Reglages::depuis_json(&v))
            .unwrap_or_default()
    }

    pub fn enregistrer(&self, dossier: &Path) -> std::io::Result<()> {
        altrex_core::outils::fichiers::ecrire_atomiquement(
            &dossier.join("reglages.json"),
            serde_json::to_string_pretty(&self.en_json())
                .unwrap_or_default()
                .as_bytes(),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reglages_relus_et_valeurs_par_defaut() {
        let d = std::env::temp_dir().join(format!("altrex-reglages-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        assert_eq!(
            Reglages::charger(&d),
            Reglages::default(),
            "sans fichier : les valeurs par défaut"
        );
        let r = Reglages {
            source: Source::Llama,
            llama_gguf: "C:\\modeles\\argora-9b-v0.1-Q5_K_M.gguf".into(),
            llama_contexte: 32_768,
            llama_couches_gpu: 40,
            espace: d.join("travail"),
            ..Reglages::default()
        };
        r.enregistrer(&d).unwrap();
        assert_eq!(Reglages::charger(&d), r);
        // Aucun secret dans ce fichier : il n'a pas de champ pour une clé.
        let texte = std::fs::read_to_string(d.join("reglages.json")).unwrap();
        assert!(!texte.to_lowercase().contains("cle") && !texte.to_lowercase().contains("key"));
        // Un fichier abîmé ou partiel ne plante pas.
        std::fs::write(
            d.join("reglages.json"),
            "{\"source\":\"llama\",\"llama_contexte\":12}",
        )
        .unwrap();
        let r = Reglages::charger(&d);
        assert_eq!(
            (r.source, r.llama_contexte, r.fournisseur.as_str()),
            (Source::Llama, 16_384, "anthropic")
        );
        std::fs::write(d.join("reglages.json"), "pas du json").unwrap();
        assert_eq!(Reglages::charger(&d), Reglages::default());
        assert_eq!(Reglages::default().modele_cloud, "claude-opus-5-5");
        let _ = std::fs::remove_dir_all(&d);
    }
}
