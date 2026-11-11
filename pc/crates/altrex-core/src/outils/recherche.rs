//! Glob et Grep (résultats au format de `docs/forge/OUTILS.md`).

use super::{json_ordonne, Chemins, Resultat};
use regex::{Regex, RegexBuilder};
use serde_json::json;
use std::fs;
use std::path::{Path, PathBuf};
use std::time::SystemTime;

pub const MAX_GLOB: usize = 200;
pub const LIMITE_GREP: usize = 100;
const MAX_FICHIERS: usize = 20_000;
const MAX_OCTETS_GREP: u64 = 2 * 1024 * 1024;

/// Dossiers ignorés, sauf si la recherche les nomme.
pub const IGNORES: &[&str] = &[
    ".git",
    "node_modules",
    ".altrex",
    ".gradle",
    "build",
    "dist",
    "target",
    "__pycache__",
    ".venv",
    "venv",
    ".next",
    ".idea",
];

/// Traduit un motif de fichiers en expression régulière sur le chemin relatif (avec des « / »).
pub fn motif_en_regex(motif: &str) -> Option<Regex> {
    let m = motif.trim().trim_start_matches("./");
    let mut re = String::from("^");
    if !m.contains('/') {
        re.push_str("(?:.*/)?");
    }
    let c: Vec<char> = m.chars().collect();
    let mut k = 0;
    let mut accolades = 0;
    while k < c.len() {
        match c[k] {
            '*' if c.get(k + 1) == Some(&'*') => {
                if c.get(k + 2) == Some(&'/') {
                    re.push_str("(?:.*/)?");
                    k += 3;
                } else {
                    re.push_str(".*");
                    k += 2;
                }
                continue;
            }
            '*' => re.push_str("[^/]*"),
            '?' => re.push_str("[^/]"),
            '{' => {
                accolades += 1;
                re.push_str("(?:");
            }
            '}' if accolades > 0 => {
                accolades -= 1;
                re.push(')');
            }
            ',' if accolades > 0 => re.push('|'),
            x => re.push_str(&regex::escape(&x.to_string())),
        }
        k += 1;
    }
    if accolades != 0 {
        return None;
    }
    re.push('$');
    Regex::new(&re).ok()
}

struct Trouve {
    rel: String,
    fichier: PathBuf,
    modifie: SystemTime,
}

fn parcourir(racine: &Path, depart: &Path, nomme: &str, out: &mut Vec<Trouve>) {
    let Ok(lecture) = fs::read_dir(depart) else { return };
    for e in lecture.flatten() {
        if out.len() >= MAX_FICHIERS {
            return;
        }
        let nom = e.file_name().to_string_lossy().to_string();
        let Ok(ft) = e.file_type() else { continue };
        let p = e.path();
        if ft.is_symlink() {
            continue;
        }
        if ft.is_dir() {
            if IGNORES.contains(&nom.as_str()) && !nomme.split('/').any(|x| x == nom) {
                continue;
            }
            parcourir(racine, &p, nomme, out);
        } else if ft.is_file() {
            let rel = p
                .strip_prefix(racine)
                .map(|r| r.to_string_lossy().replace('\\', "/"))
                .unwrap_or_default();
            let modifie = e
                .metadata()
                .and_then(|m| m.modified())
                .unwrap_or(SystemTime::UNIX_EPOCH);
            out.push(Trouve {
                rel,
                fichier: p,
                modifie,
            });
        }
    }
}

pub fn glob(chemins: &Chemins, motif: &str, dossier: &str) -> Resultat {
    if motif.trim().is_empty() {
        return Resultat::erreur("Glob demande un « pattern ».");
    }
    let Some(base) = chemins.resoudre(dossier) else {
        return Resultat::erreur(format!("« {dossier} » est hors du projet."));
    };
    if !base.fichier.is_dir() {
        return Resultat::erreur(format!("Le dossier {} n'existe pas.", base.rel));
    }
    let Some(re) = motif_en_regex(motif) else {
        return Resultat::erreur(format!("Motif invalide : {motif}"));
    };
    let mut tous = Vec::new();
    parcourir(
        &chemins.racine,
        &base.fichier,
        &format!("{}/{}", base.rel, motif),
        &mut tous,
    );
    let prefixe = if base.rel.is_empty() {
        String::new()
    } else {
        format!("{}/", base.rel)
    };
    let mut gardes: Vec<Trouve> = tous
        .into_iter()
        .filter(|t| re.is_match(t.rel.strip_prefix(&prefixe).unwrap_or(&t.rel)))
        .collect();
    gardes.sort_by(|a, b| b.modifie.cmp(&a.modifie).then(a.rel.cmp(&b.rel)));
    let total = gardes.len();
    let fichiers: Vec<String> = gardes.into_iter().take(MAX_GLOB).map(|t| t.rel).collect();
    let note = if total > MAX_GLOB {
        json!(format!(
            "{total} fichiers trouvés, les {MAX_GLOB} plus récents sont listés : précise le motif."
        ))
    } else if total == 0 {
        json!("Aucun fichier ne correspond.")
    } else {
        serde_json::Value::Null
    };
    Resultat {
        statut: "ok".into(),
        modele: json_ordonne(vec![
            ("pattern", json!(motif)),
            ("count", json!(total)),
            ("files", json!(fichiers)),
            ("note", note),
            ("status", json!("ok")),
        ]),
        resume: format!("{total} fichier(s)"),
        ..Default::default()
    }
}

pub struct Grep<'a> {
    pub motif: &'a str,
    pub chemin: &'a str,
    pub glob: &'a str,
    pub mode: &'a str,
    pub sans_casse: bool,
    pub contexte: usize,
    pub limite: Option<usize>,
}

pub fn grep(chemins: &Chemins, g: &Grep) -> Resultat {
    if g.motif.is_empty() {
        return Resultat::erreur("Grep demande un « pattern ».");
    }
    let re = match RegexBuilder::new(g.motif).case_insensitive(g.sans_casse).build() {
        Ok(r) => r,
        Err(e) => {
            return Resultat::erreur(format!(
                "Expression régulière invalide : {}",
                e.to_string().lines().last().unwrap_or("")
            ))
        }
    };
    let Some(base) = chemins.resoudre(g.chemin) else {
        return Resultat::erreur(format!("« {} » est hors du projet.", g.chemin));
    };
    if !base.fichier.exists() {
        return Resultat::erreur(format!("{} n'existe pas.", base.rel));
    }
    let filtre = if g.glob.trim().is_empty() {
        None
    } else {
        motif_en_regex(g.glob)
    };
    let mut fichiers = Vec::new();
    if base.fichier.is_file() {
        let modifie = SystemTime::UNIX_EPOCH;
        fichiers.push(Trouve {
            rel: base.rel.clone(),
            fichier: base.fichier.clone(),
            modifie,
        });
    } else {
        parcourir(&chemins.racine, &base.fichier, &base.rel, &mut fichiers);
        fichiers.sort_by(|a, b| a.rel.cmp(&b.rel));
    }
    let limite = g.limite.filter(|l| *l > 0).unwrap_or(LIMITE_GREP);
    let contexte = g.contexte.min(10);
    let mode = match g.mode {
        "content" | "count" => g.mode,
        _ => "files_with_matches",
    };
    let mut avec: Vec<String> = Vec::new();
    let mut lignes: Vec<String> = Vec::new();
    let mut comptes: Vec<String> = Vec::new();
    let mut total = 0usize;
    let mut coupe = false;
    for t in &fichiers {
        if let Some(f) = &filtre {
            if !f.is_match(&t.rel) {
                continue;
            }
        }
        if fs::metadata(&t.fichier).map(|m| m.len()).unwrap_or(0) > MAX_OCTETS_GREP {
            continue;
        }
        let Ok(octets) = fs::read(&t.fichier) else {
            continue;
        };
        if super::fichiers::semble_binaire(&octets) {
            continue;
        }
        let texte = String::from_utf8_lossy(&octets);
        let ls: Vec<&str> = texte.lines().collect();
        let touches: Vec<usize> = ls
            .iter()
            .enumerate()
            .filter(|(_, l)| re.is_match(l))
            .map(|(n, _)| n)
            .collect();
        if touches.is_empty() {
            continue;
        }
        total += touches.len();
        match mode {
            "files_with_matches" => {
                if avec.len() < limite {
                    avec.push(t.rel.clone());
                } else {
                    coupe = true;
                }
            }
            "count" => {
                if comptes.len() < limite {
                    comptes.push(format!("{}:{}", t.rel, touches.len()));
                } else {
                    coupe = true;
                }
            }
            _ => {
                let mut dernier: Option<usize> = None;
                for n in &touches {
                    if lignes.len() >= limite {
                        coupe = true;
                        break;
                    }
                    let de = n
                        .saturating_sub(contexte)
                        .max(dernier.map(|d| d + 1).unwrap_or(0));
                    let a = (n + contexte).min(ls.len() - 1);
                    for (k, ligne) in ls.iter().enumerate().take(a + 1).skip(de) {
                        let l: String = ligne.chars().take(300).collect();
                        let sep = if touches.contains(&k) { ':' } else { '-' };
                        lignes.push(format!("{}{sep}{}{sep}{l}", t.rel, k + 1));
                    }
                    dernier = Some(a);
                }
            }
        }
    }
    let note = if coupe {
        json!(format!(
            "Résultats coupés à {limite} : précise le motif ou augmente head_limit."
        ))
    } else {
        serde_json::Value::Null
    };
    let modele = match mode {
        "content" => json_ordonne(vec![
            ("pattern", json!(g.motif)),
            ("matches", json!(total)),
            ("lines", json!(lignes.join("\n"))),
            ("note", note),
            ("status", json!("ok")),
        ]),
        "count" => json_ordonne(vec![
            ("pattern", json!(g.motif)),
            ("matches", json!(total)),
            ("counts", json!(comptes.join("\n"))),
            ("note", note),
            ("status", json!("ok")),
        ]),
        _ => json_ordonne(vec![
            ("pattern", json!(g.motif)),
            ("count", json!(avec.len())),
            ("files", json!(avec)),
            ("note", note),
            ("status", json!("ok")),
        ]),
    };
    Resultat {
        statut: "ok".into(),
        modele,
        resume: format!("{total} résultat(s)"),
        ..Default::default()
    }
}
