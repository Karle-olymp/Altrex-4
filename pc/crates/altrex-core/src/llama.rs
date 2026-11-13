//! `llama-server` (llama.cpp, licence MIT) : un programme séparé que l'appli lance et pilote en
//! HTTP. Le binaire est fourni par l'utilisateur (voir `docs/pc/README.md` pour le télécharger).

use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

pub const PAGE_TELECHARGEMENT: &str = "https://github.com/ggml-org/llama.cpp/releases";

fn nom_binaire() -> &'static str {
    if cfg!(windows) {
        "llama-server.exe"
    } else {
        "llama-server"
    }
}

/// Le binaire à lancer : celui des réglages, sinon à côté de l'exécutable, sinon dans le `PATH`.
pub fn trouver_binaire(reglage: Option<&Path>) -> Option<PathBuf> {
    if let Some(p) = reglage {
        if p.is_file() {
            return Some(p.to_path_buf());
        }
        if p.join(nom_binaire()).is_file() {
            return Some(p.join(nom_binaire()));
        }
    }
    if let Some(d) = std::env::current_exe()
        .ok()
        .and_then(|e| e.parent().map(Path::to_path_buf))
    {
        for c in [d.join(nom_binaire()), d.join("llama").join(nom_binaire())] {
            if c.is_file() {
                return Some(c);
            }
        }
    }
    let chemins = std::env::var_os("PATH")?;
    std::env::split_paths(&chemins)
        .map(|d| d.join(nom_binaire()))
        .find(|c| c.is_file())
}

fn agent() -> ureq::Agent {
    ureq::AgentBuilder::new().timeout(Duration::from_secs(3)).build()
}

/// Le serveur répond-il (modèle chargé) ? `base` : « http://127.0.0.1:8080 ».
pub fn en_bonne_sante(base: &str) -> bool {
    agent()
        .get(&format!("{}/health", base.trim_end_matches('/')))
        .call()
        .map(|r| r.status() == 200)
        .unwrap_or(false)
}

/// La taille de contexte (en jetons) et le nom du modèle que sert le serveur, s'il les donne (`/props`).
pub fn proprietes(base: &str) -> (Option<usize>, Option<String>) {
    let v: serde_json::Value = agent()
        .get(&format!("{}/props", base.trim_end_matches('/')))
        .call()
        .ok()
        .and_then(|r| r.into_string().ok())
        .and_then(|t| serde_json::from_str(&t).ok())
        .unwrap_or(serde_json::Value::Null);
    let ctx = v["default_generation_settings"]["n_ctx"]
        .as_u64()
        .map(|n| n as usize);
    let nom = v["model_path"]
        .as_str()
        .and_then(|p| Path::new(p).file_stem().map(|s| s.to_string_lossy().to_string()))
        .or_else(|| v["model_alias"].as_str().map(str::to_string));
    (ctx, nom)
}

/// Les arguments de lancement.
pub fn arguments(gguf: &Path, port: u16, contexte: u32, couches_gpu: i32) -> Vec<String> {
    vec![
        "-m".into(),
        gguf.to_string_lossy().to_string(),
        "--host".into(),
        "127.0.0.1".into(),
        "--port".into(),
        port.to_string(),
        "-c".into(),
        contexte.to_string(),
        "-ngl".into(),
        couches_gpu.to_string(),
    ]
}

/// Un `llama-server` lancé par l'appli ; arrêté quand cette valeur disparaît.
pub struct Serveur {
    enfant: Child,
    pub port: u16,
}

impl Serveur {
    pub fn base(&self) -> String {
        format!("http://127.0.0.1:{}", self.port)
    }

    /// Lance le serveur sur un port libre et attend qu'il ait chargé le modèle (jusqu'à `attente`).
    pub fn demarrer(
        binaire: &Path,
        gguf: &Path,
        contexte: u32,
        couches_gpu: i32,
        attente: Duration,
    ) -> Result<Serveur, String> {
        if !gguf.is_file() {
            return Err(format!("Modèle introuvable : {}", gguf.display()));
        }
        let port = std::net::TcpListener::bind(("127.0.0.1", 0))
            .and_then(|l| l.local_addr())
            .map(|a| a.port())
            .map_err(|e| format!("Aucun port libre : {e}"))?;
        let mut cmd = Command::new(binaire);
        cmd.args(arguments(gguf, port, contexte, couches_gpu))
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            cmd.creation_flags(0x0800_0000);
        }
        let enfant = cmd
            .spawn()
            .map_err(|e| format!("Impossible de lancer {} : {e}", binaire.display()))?;
        let mut s = Serveur { enfant, port };
        let debut = Instant::now();
        while debut.elapsed() < attente {
            if let Ok(Some(statut)) = s.enfant.try_wait() {
                return Err(format!(
                    "llama-server s'est arrêté au démarrage ({statut}). Vérifie le modèle, la mémoire disponible et le nombre de couches GPU."
                ));
            }
            if en_bonne_sante(&s.base()) {
                return Ok(s);
            }
            std::thread::sleep(Duration::from_millis(300));
        }
        Err("llama-server n'a pas fini de charger le modèle à temps.".into())
    }
}

impl Drop for Serveur {
    fn drop(&mut self) {
        let _ = self.enfant.kill();
        let _ = self.enfant.wait();
    }
}
