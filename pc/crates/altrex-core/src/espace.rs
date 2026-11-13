//! Le dossier de travail de Forge, avec la même arborescence que sur le téléphone
//! (`forge/workspace/ForgeWorkspace.kt`) : `projets/`, `meta/`, `sessions/`, `ALTREX.md`.
//! Sur PC, un projet peut aussi être un dossier existant du disque (`path` dans ses métadonnées).

use crate::journal::{self, Evenement, Journal};
use serde_json::{json, Value};
use std::fs;
use std::path::{Path, PathBuf};

pub const ORIGINE_CREE: &str = "créé";
pub const ORIGINE_IMPORTE: &str = "importé";

#[derive(Debug, Clone)]
pub struct Espace {
    pub base: PathBuf,
}

#[derive(Debug, Clone, PartialEq)]
pub struct InfoProjet {
    pub nom: String,
    pub dossier: PathBuf,
    /// « Next.js », « Python », « Rust »… deviné d'après les fichiers, ou vide.
    pub genre: String,
    pub branche: Option<String>,
    pub approuve: bool,
    pub origine: String,
    pub modifie: i64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct InfoSession {
    pub projet: String,
    pub id: String,
    pub titre: String,
    pub modifie: i64,
    /// `None` : jamais terminée ; sinon « done », « stopped », « interrupted »…
    pub derniere_fin: Option<String>,
    pub taches_faites: usize,
    pub taches: usize,
}

fn ms(t: std::io::Result<std::time::SystemTime>) -> i64 {
    t.ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// Null quand `nom` est un nom de projet valide, sinon pourquoi.
pub fn verifier_nom(nom: &str) -> Option<&'static str> {
    let n = nom.trim();
    let valide = n
        .chars()
        .next()
        .map(|c| c.is_ascii_lowercase() || c.is_ascii_digit())
        .unwrap_or(false)
        && n.chars()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || matches!(c, '.' | '_' | '-'));
    if n.is_empty() {
        Some("Donne un nom au projet.")
    } else if n.chars().count() > 40 {
        Some("Le nom fait au plus 40 caractères.")
    } else if n == "." || n == ".." || n.contains("..") {
        Some("Ce nom n'est pas permis.")
    } else if !valide {
        Some("Lettres minuscules sans accent, chiffres, « - », « _ » ou « . » seulement (ex. mon-site).")
    } else {
        None
    }
}

fn sans_accent(c: char) -> char {
    match c {
        'à' | 'â' | 'ä' | 'á' | 'ã' | 'å' => 'a',
        'ç' => 'c',
        'é' | 'è' | 'ê' | 'ë' => 'e',
        'î' | 'ï' | 'í' | 'ì' => 'i',
        'ñ' => 'n',
        'ô' | 'ö' | 'ó' | 'ò' | 'õ' => 'o',
        'ù' | 'û' | 'ü' | 'ú' => 'u',
        'ÿ' | 'ý' => 'y',
        x => x,
    }
}

/// « Mon Site Vitrine ! » → « mon-site-vitrine ».
pub fn slug(texte: &str) -> String {
    let mut out = String::new();
    for c in texte.trim().to_lowercase().chars().map(sans_accent) {
        if c.is_ascii_lowercase() || c.is_ascii_digit() || matches!(c, '.' | '_' | '-') {
            out.push(c);
        } else if !out.ends_with('-') {
            out.push('-');
        }
    }
    while out.contains("--") {
        out = out.replace("--", "-");
    }
    let bords: &[char] = &['-', '.', '_'];
    let out: String = out.trim_matches(bords).chars().take(40).collect();
    out.trim_end_matches(bords).to_string()
}

/// La branche Git courante, lue dans `.git/HEAD` (sans Git).
pub fn branche_git(dossier: &Path) -> Option<String> {
    let t = fs::read_to_string(dossier.join(".git/HEAD")).ok()?;
    let t = t.trim();
    match t.strip_prefix("ref: refs/heads/") {
        Some(b) => Some(b.to_string()),
        None if t.is_empty() => None,
        None => Some(t.chars().take(7).collect()),
    }
}

pub fn deviner_genre(dossier: &Path) -> String {
    let a = |n: &str| dossier.join(n).exists();
    if a("package.json") {
        let p = fs::read_to_string(dossier.join("package.json")).unwrap_or_default();
        return if p.contains("\"next\"") {
            "Next.js"
        } else if p.contains("\"react\"") {
            "React"
        } else if p.contains("\"vue\"") {
            "Vue"
        } else if p.contains("\"svelte\"") {
            "Svelte"
        } else {
            "Node"
        }
        .to_string();
    }
    if a("pyproject.toml") || a("requirements.txt") || a("main.py") {
        "Python"
    } else if a("Cargo.toml") {
        "Rust"
    } else if a("go.mod") {
        "Go"
    } else if a("build.gradle.kts") || a("build.gradle") {
        "Kotlin"
    } else if a("index.html") {
        "Web"
    } else {
        ""
    }
    .to_string()
}

/// (année, mois, jour, heure, minute, seconde) en UTC, à partir de millisecondes depuis 1970.
pub fn date_utc(ms: i64) -> (i64, u32, u32, u32, u32, u32) {
    let s = ms.div_euclid(1000);
    let jours = s.div_euclid(86_400);
    let reste = s.rem_euclid(86_400);
    let z = jours + 719_468;
    let ere = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let j = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    let a = yoe + ere * 400 + if m <= 2 { 1 } else { 0 };
    (
        a,
        m,
        j,
        (reste / 3600) as u32,
        ((reste % 3600) / 60) as u32,
        (reste % 60) as u32,
    )
}

impl Espace {
    pub fn nouveau(base: &Path) -> Espace {
        Espace {
            base: base.to_path_buf(),
        }
    }
    pub fn dossier_projets(&self) -> PathBuf {
        self.base.join("projets")
    }
    pub fn dossier_meta(&self) -> PathBuf {
        self.base.join("meta")
    }
    pub fn dossier_sessions(&self) -> PathBuf {
        self.base.join("sessions")
    }
    pub fn memoire_globale(&self) -> PathBuf {
        self.base.join("ALTREX.md")
    }

    pub fn preparer(&self) -> std::io::Result<()> {
        fs::create_dir_all(self.dossier_projets())?;
        fs::create_dir_all(self.dossier_meta())?;
        fs::create_dir_all(self.dossier_sessions())
    }

    fn lire_meta(&self, nom: &str) -> Option<Value> {
        serde_json::from_str(&fs::read_to_string(self.dossier_meta().join(format!("{nom}.json"))).ok()?).ok()
    }

    fn ecrire_meta(
        &self,
        nom: &str,
        approuve: bool,
        origine: &str,
        chemin: Option<&Path>,
    ) -> std::io::Result<()> {
        let mut o =
            json!({"name": nom, "trusted": approuve, "origin": origine, "updated": journal::maintenant_ms()});
        if let Some(c) = chemin {
            o["path"] = json!(c.to_string_lossy());
        }
        crate::outils::fichiers::ecrire_atomiquement(
            &self.dossier_meta().join(format!("{nom}.json")),
            serde_json::to_string_pretty(&o).unwrap_or_default().as_bytes(),
        )
    }

    /// Le dossier d'un projet : celui de ses métadonnées (dossier existant du disque), sinon `projets/<nom>`.
    pub fn dossier(&self, nom: &str) -> PathBuf {
        self.lire_meta(nom)
            .and_then(|m| m.get("path").and_then(|p| p.as_str()).map(PathBuf::from))
            .unwrap_or_else(|| self.dossier_projets().join(nom))
    }

    pub fn existe(&self, nom: &str) -> bool {
        self.dossier_projets().join(nom).is_dir() || self.lire_meta(nom).is_some()
    }

    /// Un nom libre et valide proche de `voulu` (« Mon Site ! » → « mon-site », puis « mon-site-2 »).
    pub fn nom_libre(&self, voulu: &str) -> String {
        let base = match slug(voulu) {
            s if s.is_empty() || verifier_nom(&s).is_some() => "projet".to_string(),
            s => s,
        };
        if !self.existe(&base) {
            return base;
        }
        (2..)
            .map(|i| format!("{base}-{i}"))
            .find(|n| !self.existe(n))
            .unwrap()
    }

    /// Crée un projet vide (approuvé : c'est celui de l'utilisateur).
    pub fn creer(&self, voulu: &str) -> Result<String, String> {
        self.preparer().map_err(|e| e.to_string())?;
        if let Some(m) = verifier_nom(voulu) {
            return Err(m.to_string());
        }
        let nom = voulu.trim();
        if self.existe(nom) {
            return Err(format!("Un projet « {nom} » existe déjà."));
        }
        fs::create_dir_all(self.dossier_projets().join(nom))
            .map_err(|_| "Impossible de créer le dossier du projet.".to_string())?;
        self.ecrire_meta(nom, true, ORIGINE_CREE, None)
            .map_err(|e| e.to_string())?;
        Ok(nom.to_string())
    }

    /// Ajoute un dossier existant du disque comme projet (pas approuvé tant que l'utilisateur ne le dit pas :
    /// son ALTREX.md n'est pas lu).
    pub fn ouvrir_dossier(&self, dossier: &Path) -> Result<String, String> {
        self.preparer().map_err(|e| e.to_string())?;
        let reel = dossier
            .canonicalize()
            .map_err(|_| "Ce dossier n'existe pas.".to_string())?;
        if !reel.is_dir() {
            return Err("Ce n'est pas un dossier.".into());
        }
        if let Some(deja) = self
            .lister()
            .into_iter()
            .find(|p| crate::outils::canoniser(&p.dossier).ok().as_deref() == Some(&reel))
        {
            return Ok(deja.nom);
        }
        let nom = self.nom_libre(
            &reel
                .file_name()
                .map(|n| n.to_string_lossy().to_string())
                .unwrap_or_default(),
        );
        self.ecrire_meta(&nom, false, ORIGINE_IMPORTE, Some(&reel))
            .map_err(|e| e.to_string())?;
        Ok(nom)
    }

    pub fn approuver(&self, nom: &str, approuve: bool) -> std::io::Result<()> {
        let m = self.lire_meta(nom);
        let origine = m
            .as_ref()
            .and_then(|m| m["origin"].as_str())
            .unwrap_or(ORIGINE_CREE)
            .to_string();
        let chemin = m.as_ref().and_then(|m| m["path"].as_str()).map(PathBuf::from);
        self.ecrire_meta(nom, approuve, &origine, chemin.as_deref())
    }

    pub fn info(&self, nom: &str) -> InfoProjet {
        let dossier = self.dossier(nom);
        let meta = self.lire_meta(nom);
        let derniere_session = self
            .fichiers_sessions(nom)
            .iter()
            .map(|f| ms(fs::metadata(f).and_then(|m| m.modified())))
            .max();
        InfoProjet {
            nom: nom.to_string(),
            genre: deviner_genre(&dossier),
            branche: branche_git(&dossier),
            approuve: meta.as_ref().and_then(|m| m["trusted"].as_bool()).unwrap_or(true),
            origine: meta
                .as_ref()
                .and_then(|m| m["origin"].as_str())
                .unwrap_or(ORIGINE_CREE)
                .to_string(),
            modifie: ms(fs::metadata(&dossier).and_then(|m| m.modified())).max(derniere_session.unwrap_or(0)),
            dossier,
        }
    }

    /// Les projets, le plus récent d'abord.
    pub fn lister(&self) -> Vec<InfoProjet> {
        let mut noms: Vec<String> = Vec::new();
        if let Ok(l) = fs::read_dir(self.dossier_projets()) {
            for e in l.flatten() {
                let n = e.file_name().to_string_lossy().to_string();
                if e.path().is_dir() && !n.starts_with('.') {
                    noms.push(n);
                }
            }
        }
        if let Ok(l) = fs::read_dir(self.dossier_meta()) {
            for e in l.flatten() {
                if let Some(n) = e.file_name().to_string_lossy().strip_suffix(".json") {
                    if !noms.iter().any(|x| x == n) && self.dossier(n).is_dir() {
                        noms.push(n.to_string());
                    }
                }
            }
        }
        let mut v: Vec<InfoProjet> = noms.iter().map(|n| self.info(n)).collect();
        v.sort_by(|a, b| b.modifie.cmp(&a.modifie).then(a.nom.cmp(&b.nom)));
        v
    }

    fn fichiers_sessions(&self, projet: &str) -> Vec<PathBuf> {
        fs::read_dir(self.dossier_sessions().join(projet))
            .map(|l| {
                l.flatten()
                    .map(|e| e.path())
                    .filter(|p| p.is_file() && p.extension().map(|x| x == "jsonl").unwrap_or(false))
                    .collect()
            })
            .unwrap_or_default()
    }

    /// Un nouveau journal de session, commencé par son premier événement.
    pub fn nouvelle_session(&self, projet: &str, modele: &str, mode: &str) -> std::io::Result<Journal> {
        let t = journal::maintenant_ms();
        let (a, m, j, h, mi, s) = date_utc(t);
        let id = format!("s{a:04}{m:02}{j:02}-{h:02}{mi:02}{s:02}-{:x}", t.rem_euclid(4096));
        let jr = Journal::nouveau(self.dossier_sessions().join(projet).join(format!("{id}.jsonl")));
        jr.ajouter(Evenement::Session {
            id,
            project: projet.to_string(),
            model: modele.to_string(),
            mode: mode.to_string(),
            title: String::new(),
        })?;
        Ok(jr)
    }

    pub fn ouvrir_session(&self, projet: &str, id: &str) -> Option<Journal> {
        let f = self.dossier_sessions().join(projet).join(format!("{id}.jsonl"));
        if f.is_file() {
            Journal::charger(&f).ok()
        } else {
            None
        }
    }

    /// Les sessions d'un projet qui ont au moins un message, la plus récente d'abord (lecture seule :
    /// rien n'est réparé ici).
    pub fn sessions(&self, projet: &str) -> Vec<InfoSession> {
        let mut v: Vec<InfoSession> = self
            .fichiers_sessions(projet)
            .into_iter()
            .filter_map(|f| {
                let texte = String::from_utf8_lossy(&fs::read(&f).ok()?).to_string();
                let ev: Vec<Evenement> = texte
                    .lines()
                    .filter_map(|l| serde_json::from_str::<Value>(l).ok())
                    .filter_map(|v| Evenement::depuis_json(&v))
                    .collect();
                let e = journal::replier(&ev);
                if !e.a_un_message {
                    return None;
                }
                Some(InfoSession {
                    projet: projet.to_string(),
                    id: f.file_stem()?.to_string_lossy().to_string(),
                    titre: if e.titre.is_empty() {
                        "Session".into()
                    } else {
                        e.titre.clone()
                    },
                    modifie: ms(fs::metadata(&f).and_then(|m| m.modified())),
                    derniere_fin: if e.tour_ouvert {
                        None
                    } else {
                        e.derniere_fin.clone()
                    },
                    taches_faites: e.taches.iter().filter(|t| t.status == "completed").count(),
                    taches: e.taches.len(),
                })
            })
            .collect();
        v.sort_by(|a, b| b.modifie.cmp(&a.modifie).then(b.id.cmp(&a.id)));
        v
    }
}
