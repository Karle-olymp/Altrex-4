//! Le shell natif de Forge sur PC : PowerShell sous Windows, `sh` ailleurs. Pas de bac à sable :
//! c'est la politique d'autorisation qui décide avant de lancer quoi que ce soit.
//!
//! Comme sur le téléphone : le dossier courant est gardé d'une commande à l'autre (dans le projet
//! seulement), une commande silencieuse trop longtemps passe en arrière-plan sans être tuée, et
//! Arrêter tue tout l'arbre de processus.

use crate::commandes::Dialecte;
use std::collections::HashMap;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

const MARQUE: &str = "\u{1e}ALTREX_CWD=";
const MAX_SORTIE: usize = 2 * 1024 * 1024;
pub const SILENCE_DEFAUT_S: u64 = 120;
pub const SILENCE_MAX_S: u64 = 600;
pub const PLAFOND_MS: u64 = 30 * 60_000;

/// Combien de temps suivre une commande : `silence_ms` sans sortie, ou `plafond_ms` en tout.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Attente {
    pub silence_ms: u64,
    pub plafond_ms: u64,
}

const LENTES: &[&str] = &[
    "npm install",
    "npm ci",
    "npm i ",
    "pnpm ",
    "yarn",
    "pip install",
    "pip3 install",
    "cargo ",
    "go build",
    "make",
    "cmake",
    "gradle",
    "git clone",
    "git fetch",
    "git pull",
    "npx ",
    "apt ",
    "apt-get ",
    "winget ",
    "choco ",
    "dotnet ",
    "msbuild",
];
const SERVEURS: &[&str] = &[
    "http.server",
    "npm run dev",
    "npm start",
    "npm run start",
    "vite",
    "flask run",
    "uvicorn",
    "node server",
    "next dev",
    "php -S",
    "serve ",
];

/// L'attente d'une commande, d'après ce qu'elle est et le `timeout` demandé (secondes).
pub fn attente(commande: &str, timeout_s: Option<i64>, plafond_ms: u64) -> Attente {
    let c = commande.to_lowercase();
    let defaut = if SERVEURS.iter().any(|s| c.contains(&s.to_lowercase())) {
        20
    } else if LENTES.iter().any(|s| c.contains(s)) {
        300
    } else {
        SILENCE_DEFAUT_S
    };
    let s = timeout_s
        .filter(|t| *t > 0)
        .map(|t| (t as u64).min(SILENCE_MAX_S))
        .unwrap_or(defaut);
    Attente {
        silence_ms: s * 1000,
        plafond_ms: plafond_ms.min(PLAFOND_MS),
    }
}

/// Comment une commande au premier plan s'est terminée. `tache` : elle tournait encore quand
/// l'attente a pris fin (`detachee` = « idle » ou « cap ») et continue en arrière-plan.
#[derive(Debug, Clone, PartialEq)]
pub struct Fin {
    pub code: i32,
    pub sortie: String,
    pub cwd: Option<String>,
    pub tache: Option<String>,
    pub detachee: Option<&'static str>,
    pub arretee: bool,
}

struct Tache {
    commande: String,
    enfant: Mutex<Child>,
    sortie: Arc<Mutex<Vec<u8>>>,
    activite: Arc<Mutex<Instant>>,
    lu: AtomicUsize,
    arretee: AtomicBool,
}

pub struct Shell {
    projet: PathBuf,
    pub dialecte: Dialecte,
    cwd: Mutex<PathBuf>,
    taches: Mutex<HashMap<String, Arc<Tache>>>,
    compteur: AtomicUsize,
}

fn utf16_base64(texte: &str) -> String {
    use base64::Engine;
    let octets: Vec<u8> = texte.encode_utf16().flat_map(|u| u.to_le_bytes()).collect();
    base64::engine::general_purpose::STANDARD.encode(octets)
}

/// Le programme PowerShell à lancer : `pwsh` (PowerShell 7) s'il est installé, sinon celui de Windows.
pub fn powershell() -> &'static str {
    static P: std::sync::OnceLock<&'static str> = std::sync::OnceLock::new();
    P.get_or_init(|| {
        let ok = Command::new("pwsh")
            .args(["-NoLogo", "-NoProfile", "-Command", "exit 0"])
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .map(|s| s.success())
            .unwrap_or(false);
        if ok {
            "pwsh"
        } else {
            "powershell.exe"
        }
    })
}

/// Le script PowerShell qui lance `commande`, puis écrit le dossier courant et rend son code.
pub fn script_powershell(commande: &str, premier_plan: bool) -> String {
    let fin = if premier_plan {
        "\n$__ok = $?\n$__rc = if ($null -ne $LASTEXITCODE) { $LASTEXITCODE } elseif ($__ok) { 0 } else { 1 }\n\
         Write-Output (\"`n\" + [char]30 + 'ALTREX_CWD=' + (Get-Location).Path)\nexit $__rc\n"
    } else {
        "\nif ($null -ne $LASTEXITCODE) { exit $LASTEXITCODE }\n"
    };
    format!("[Console]::OutputEncoding = [System.Text.Encoding]::UTF8\n$ProgressPreference = 'SilentlyContinue'\n{commande}{fin}")
}

const SH_PREMIER_PLAN: &str = "__altrex_cmd=$(cat)\neval \"$__altrex_cmd\" </dev/null\n__altrex_rc=$?\nprintf '\\n\\036ALTREX_CWD=%s\\n' \"$(pwd)\"\nexit $__altrex_rc\n";
const SH_FOND: &str = "__altrex_cmd=$(cat)\nexec sh -c \"$__altrex_cmd\" </dev/null\n";

fn tuer_arbre(pid: u32) {
    #[cfg(windows)]
    {
        let _ = Command::new("taskkill")
            .args(["/PID", &pid.to_string(), "/T", "/F"])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
    }
    #[cfg(not(windows))]
    {
        // Le processus est chef de son groupe : le signal va à tout le groupe.
        let _ = Command::new("kill")
            .args(["-KILL", "--", &format!("-{pid}")])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
    }
}

/// Sépare le rapport de dossier courant de la sortie d'une commande.
pub fn separer_cwd(brut: &str) -> (String, Option<String>) {
    match brut.rfind(MARQUE) {
        None => (brut.to_string(), None),
        Some(p) => {
            let cwd = brut[p + MARQUE.len()..]
                .trim()
                .lines()
                .next()
                .map(|s| s.trim().to_string());
            (brut[..p].trim_end_matches(['\n', '\r']).to_string(), cwd)
        }
    }
}

impl Shell {
    pub fn nouveau(projet: &Path, dialecte: Dialecte) -> Shell {
        let projet = crate::outils::canoniser(projet).unwrap_or_else(|_| projet.to_path_buf());
        Shell {
            cwd: Mutex::new(projet.clone()),
            projet,
            dialecte,
            taches: Mutex::new(HashMap::new()),
            compteur: AtomicUsize::new(0),
        }
    }

    pub fn dossier_courant(&self) -> PathBuf {
        self.cwd.lock().unwrap().clone()
    }

    fn lancer(&self, commande: &str, premier_plan: bool) -> std::io::Result<Tache> {
        let cwd = self.dossier_courant();
        let cwd = if cwd.is_dir() { cwd } else { self.projet.clone() };
        let mut cmd = match self.dialecte {
            Dialecte::PowerShell => {
                let mut c = Command::new(powershell());
                c.args(["-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand"]);
                c.arg(utf16_base64(&script_powershell(commande, premier_plan)));
                c.stdin(Stdio::null());
                c
            }
            Dialecte::Posix => {
                let mut c = Command::new("sh");
                c.arg("-c")
                    .arg(if premier_plan { SH_PREMIER_PLAN } else { SH_FOND });
                c.stdin(Stdio::piped());
                c
            }
        };
        cmd.current_dir(&cwd)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        #[cfg(unix)]
        {
            use std::os::unix::process::CommandExt;
            cmd.process_group(0);
        }
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            // CREATE_NO_WINDOW : pas de console qui s'ouvre devant l'appli.
            cmd.creation_flags(0x0800_0000);
        }
        let mut enfant = cmd.spawn()?;
        if let Some(mut entree) = enfant.stdin.take() {
            let texte = commande.to_string();
            std::thread::spawn(move || {
                let _ = entree.write_all(texte.as_bytes());
            });
        }
        let sortie = Arc::new(Mutex::new(Vec::new()));
        let activite = Arc::new(Mutex::new(Instant::now()));
        fn pomper(
            mut r: impl Read + Send + 'static,
            sortie: Arc<Mutex<Vec<u8>>>,
            activite: Arc<Mutex<Instant>>,
        ) {
            std::thread::spawn(move || {
                let mut tampon = [0u8; 8192];
                while let Ok(n) = r.read(&mut tampon) {
                    if n == 0 {
                        break;
                    }
                    let mut s = sortie.lock().unwrap();
                    s.extend_from_slice(&tampon[..n]);
                    if s.len() > MAX_SORTIE {
                        let trop = s.len() - MAX_SORTIE;
                        s.drain(..trop);
                    }
                    *activite.lock().unwrap() = Instant::now();
                }
            });
        }
        if let Some(o) = enfant.stdout.take() {
            pomper(o, sortie.clone(), activite.clone());
        }
        if let Some(e) = enfant.stderr.take() {
            pomper(e, sortie.clone(), activite.clone());
        }
        Ok(Tache {
            commande: commande.to_string(),
            enfant: Mutex::new(enfant),
            sortie,
            activite,
            lu: AtomicUsize::new(0),
            arretee: AtomicBool::new(false),
        })
    }

    fn enregistrer(&self, t: Tache) -> String {
        let id = format!("job{}", self.compteur.fetch_add(1, Ordering::SeqCst) + 1);
        self.taches.lock().unwrap().insert(id.clone(), Arc::new(t));
        id
    }

    fn texte(t: &Tache) -> String {
        String::from_utf8_lossy(&t.sortie.lock().unwrap()).to_string()
    }

    /// Lance `commande` au premier plan et la suit tant que `attente` le permet. `arret` passé à vrai
    /// tue la commande et tout son arbre de processus.
    pub fn executer(&self, commande: &str, attente: Attente, arret: &AtomicBool) -> std::io::Result<Fin> {
        let t = self.lancer(commande, true)?;
        let debut = Instant::now();
        loop {
            let etat = t.enfant.lock().unwrap().try_wait()?;
            if let Some(statut) = etat {
                // Laisse aux lecteurs le temps de vider les tuyaux.
                std::thread::sleep(Duration::from_millis(40));
                let (sortie, cwd) = separer_cwd(&Self::texte(&t));
                if let Some(c) = &cwd {
                    let p = PathBuf::from(c);
                    let dedans = p
                        .canonicalize()
                        .map(|r| r.starts_with(&self.projet))
                        .unwrap_or(false);
                    *self.cwd.lock().unwrap() = if dedans { p } else { self.projet.clone() };
                }
                return Ok(Fin {
                    code: statut.code().unwrap_or(-1),
                    sortie,
                    cwd,
                    tache: None,
                    detachee: None,
                    arretee: false,
                });
            }
            if arret.load(Ordering::SeqCst) {
                let pid = t.enfant.lock().unwrap().id();
                tuer_arbre(pid);
                let _ = t.enfant.lock().unwrap().kill();
                let _ = t.enfant.lock().unwrap().wait();
                let (sortie, _) = separer_cwd(&Self::texte(&t));
                return Ok(Fin {
                    code: -1,
                    sortie,
                    cwd: None,
                    tache: None,
                    detachee: None,
                    arretee: true,
                });
            }
            let silence = t.activite.lock().unwrap().elapsed().as_millis() as u64;
            let detachee = if debut.elapsed().as_millis() as u64 >= attente.plafond_ms {
                Some("cap")
            } else if silence >= attente.silence_ms {
                Some("idle")
            } else {
                None
            };
            if let Some(d) = detachee {
                let sortie = Self::texte(&t);
                t.lu.store(t.sortie.lock().unwrap().len(), Ordering::SeqCst);
                let id = self.enregistrer(t);
                return Ok(Fin {
                    code: 0,
                    sortie,
                    cwd: None,
                    tache: Some(id),
                    detachee: Some(d),
                    arretee: false,
                });
            }
            std::thread::sleep(Duration::from_millis(30));
        }
    }

    /// Lance une tâche de fond ; rend son identifiant.
    pub fn demarrer_tache(&self, commande: &str) -> std::io::Result<String> {
        Ok(self.enregistrer(self.lancer(commande, false)?))
    }

    /// L'état d'une tâche et sa nouvelle sortie ; `stop` l'arrête. `None` : identifiant inconnu.
    /// Clés : `command`, `state` (running, finished, stopped), `new_output`, `exit_code`.
    pub fn lire_tache(&self, id: &str, stop: bool, max_car: usize) -> Option<Vec<(&'static str, String)>> {
        let t = self.taches.lock().unwrap().get(id).cloned()?;
        if stop && t.enfant.lock().unwrap().try_wait().ok().flatten().is_none() {
            t.arretee.store(true, Ordering::SeqCst);
            let pid = t.enfant.lock().unwrap().id();
            tuer_arbre(pid);
            let _ = t.enfant.lock().unwrap().kill();
            let _ = t.enfant.lock().unwrap().wait();
            std::thread::sleep(Duration::from_millis(40));
        }
        let statut = t.enfant.lock().unwrap().try_wait().ok().flatten();
        let octets = t.sortie.lock().unwrap().clone();
        let de = t.lu.swap(octets.len(), Ordering::SeqCst).min(octets.len());
        let (nouveau, _) = separer_cwd(&String::from_utf8_lossy(&octets[de..]));
        let n = nouveau.chars().count();
        let nouveau = if n > max_car {
            format!(
                "[… {} caractères plus tôt]\n{}",
                n - max_car,
                nouveau.chars().skip(n - max_car).collect::<String>()
            )
        } else {
            nouveau
        };
        let mut v = vec![("command", t.commande.clone())];
        match statut {
            None => v.push(("state", "running".into())),
            Some(s) => {
                v.push((
                    "state",
                    if t.arretee.load(Ordering::SeqCst) {
                        "stopped"
                    } else {
                        "finished"
                    }
                    .into(),
                ));
                v.push(("exit_code", s.code().unwrap_or(-1).to_string()));
            }
        }
        v.push(("new_output", nouveau));
        Some(v)
    }

    /// Arrête toutes les tâches de fond (fermeture de la session).
    pub fn tout_arreter(&self) {
        let ids: Vec<String> = self.taches.lock().unwrap().keys().cloned().collect();
        for id in ids {
            let _ = self.lire_tache(&id, true, 0);
        }
    }
}

impl Drop for Shell {
    fn drop(&mut self) {
        self.tout_arreter();
    }
}
