//! Read, Write, Edit, MultiEdit : mêmes règles et mêmes messages que le téléphone
//! (`forge/tools/FileTools.kt`). Un fichier existant doit avoir été lu avant d'être modifié, et
//! rien n'est écrasé s'il a changé depuis.

use super::{json_ordonne, Chemins, Resultat};
use serde_json::{json, Value};
use std::collections::hash_map::DefaultHasher;
use std::collections::HashMap;
use std::fs;
use std::hash::{Hash, Hasher};
use std::io::Write;
use std::path::{Path, PathBuf};

pub const MAX_EDITS: usize = 20;
pub const LIGNES_PAR_DEFAUT: usize = 400;
pub const MAX_LIGNES: usize = 2_000;
pub const MAX_CAR_LIGNE: usize = 500;
pub const MAX_OCTETS: u64 = 2 * 1024 * 1024;
pub const PREFIXE_CONFLIT: &str = "Conflit : ";

fn empreinte(octets: &[u8]) -> u64 {
    let mut h = DefaultHasher::new();
    octets.hash(&mut h);
    h.finish()
}

/// Ce que le modèle a lu : chemin → empreinte du contenu au moment de la lecture.
#[derive(Default)]
pub struct Suivi {
    versions: HashMap<String, u64>,
}

impl Suivi {
    pub fn noter(&mut self, rel: &str, octets: &[u8]) {
        self.versions.insert(rel.to_string(), empreinte(octets));
    }
    pub fn oublier(&mut self, rel: &str) {
        self.versions.remove(rel);
    }
    pub fn a_lu(&self, rel: &str) -> bool {
        self.versions.contains_key(rel)
    }
    fn verifier_avant_ecriture(&self, rel: &str, actuel: Option<&[u8]>) -> Option<String> {
        let actuel = actuel?;
        match self.versions.get(rel) {
            None => Some(format!(
                "Le fichier {rel} existe et tu ne l'as pas encore lu dans cette session. Lis-le d'abord avec Read."
            )),
            Some(v) if *v != empreinte(actuel) => Some(format!(
                "Le fichier {rel} a changé depuis ta dernière lecture (modifié par l'utilisateur ou par une commande). Relis-le avec Read avant de le modifier."
            )),
            _ => None,
        }
    }
}

/// Une écriture prête, à montrer à l'utilisateur (diff) puis à appliquer.
#[derive(Debug, Clone)]
pub struct Ecriture {
    pub rel: String,
    pub fichier: PathBuf,
    pub nouveau_texte: String,
    pub nouveau: bool,
    pub diff: String,
    pub ajoutees: i64,
    pub retirees: i64,
    pub remplacements: usize,
    pub outil: &'static str,
    base: Option<u64>,
    pub modifs: usize,
}

pub fn semble_binaire(octets: &[u8]) -> bool {
    octets.iter().take(8 * 1024).any(|b| *b == 0)
}

/// Diff ligne à ligne (« + », « - », contexte) et nombres de lignes ajoutées et retirées.
pub fn diff(ancien: &str, nouveau: &str) -> (String, i64, i64) {
    let d = similar::TextDiff::from_lines(ancien, nouveau);
    let (mut plus, mut moins) = (0, 0);
    for c in d.iter_all_changes() {
        match c.tag() {
            similar::ChangeTag::Insert => plus += 1,
            similar::ChangeTag::Delete => moins += 1,
            similar::ChangeTag::Equal => {}
        }
    }
    let texte = d.unified_diff().context_radius(3).to_string();
    (texte, plus, moins)
}

pub fn ecrire_atomiquement(cible: &Path, octets: &[u8]) -> std::io::Result<()> {
    let dossier = cible
        .parent()
        .ok_or_else(|| std::io::Error::other("dossier introuvable"))?;
    fs::create_dir_all(dossier)?;
    let nom = cible
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_default();
    let tmp = dossier.join(format!(".{nom}.forge-{}.tmp", crate::journal::maintenant_ms()));
    let res = (|| {
        let mut f = fs::File::create(&tmp)?;
        f.write_all(octets)?;
        f.sync_all()?;
        #[cfg(unix)]
        if let Ok(m) = fs::metadata(cible) {
            let _ = fs::set_permissions(&tmp, m.permissions());
        }
        fs::rename(&tmp, cible)
    })();
    if res.is_err() {
        let _ = fs::remove_file(&tmp);
    }
    res
}

fn montre(rel: &str) -> &str {
    if rel.is_empty() {
        "La racine du projet"
    } else {
        rel
    }
}

pub struct Fichiers<'a> {
    pub chemins: &'a Chemins,
    pub suivi: &'a mut Suivi,
}

impl Fichiers<'_> {
    fn dehors(&self, brut: &str) -> Resultat {
        Resultat::erreur(format!(
            "« {brut} » est hors du projet ({}). Les outils de fichiers restent dans le projet ; pour le reste de l'ordinateur, utilise Bash.",
            self.chemins.racine.display()
        ))
    }

    pub fn lire(&mut self, brut: &str, offset: Option<i64>, limite: Option<i64>, defaut: usize) -> Resultat {
        if brut.trim().is_empty() {
            return Resultat::erreur("Read demande un « file_path ».");
        }
        let Some(r) = self.chemins.resoudre(brut) else {
            return self.dehors(brut);
        };
        if !r.fichier.exists() {
            let parent = r.rel.rsplit_once('/').map(|x| x.0).unwrap_or("");
            let proches = self
                .chemins
                .resoudre(parent)
                .and_then(|d| fs::read_dir(d.fichier).ok())
                .map(|it| {
                    let mut noms: Vec<String> = it
                        .flatten()
                        .map(|e| e.file_name().to_string_lossy().to_string())
                        .collect();
                    noms.sort();
                    noms.truncate(20);
                    noms.join(", ")
                });
            let suite = match proches {
                Some(p) if !p.is_empty() => format!(" Contenu de {} : {p}.", montre(parent)),
                _ => String::new(),
            };
            return Resultat::erreur(format!("Le fichier {} n'existe pas.{suite}", r.rel));
        }
        if r.fichier.is_dir() {
            return Resultat::erreur(format!(
                "{} est un dossier. Utilise Glob pour lister les fichiers, ou Bash avec ls.",
                montre(&r.rel)
            ));
        }
        let taille = fs::metadata(&r.fichier).map(|m| m.len()).unwrap_or(0);
        if taille > MAX_OCTETS {
            return Resultat::erreur(format!(
                "{} est trop gros pour Read ({} Ko). Cherche ce qu'il te faut avec Grep, ou lis une partie avec Bash.",
                r.rel,
                taille / 1024
            ));
        }
        let octets = match fs::read(&r.fichier) {
            Ok(o) => o,
            Err(e) => return Resultat::erreur(format!("Lecture impossible de {} : {e}", r.rel)),
        };
        if semble_binaire(&octets) {
            return Resultat::erreur(format!("{} n'est pas un fichier texte (binaire).", r.rel));
        }
        self.suivi.noter(&r.rel, &octets);
        let texte = String::from_utf8_lossy(&octets);
        let texte = texte.strip_suffix('\n').unwrap_or(&texte);
        let lignes: Vec<&str> = if texte.is_empty() {
            Vec::new()
        } else {
            texte.split('\n').collect()
        };
        let total = lignes.len();
        let de = offset.unwrap_or(1).max(1) as usize;
        let nombre = limite
            .filter(|l| *l > 0)
            .map(|l| l as usize)
            .unwrap_or(defaut)
            .min(MAX_LIGNES);
        let a = total.min(de + nombre - 1);
        let mut contenu = String::new();
        for n in de..=a {
            let l = lignes[n - 1].trim_end_matches('\r');
            let l: String = if l.chars().count() > MAX_CAR_LIGNE {
                l.chars().take(MAX_CAR_LIGNE).collect::<String>() + " […]"
            } else {
                l.to_string()
            };
            contenu.push_str(&format!("{n:>6}\t{l}\n"));
        }
        let note = if total == 0 {
            json!("Le fichier est vide.")
        } else if de > total {
            json!(format!("Le fichier n'a que {total} lignes."))
        } else if a < total {
            json!(format!(
                "Lignes {} à {total} non affichées : rappelle Read avec offset={}.",
                a + 1,
                a + 1
            ))
        } else {
            Value::Null
        };
        let plage = if total == 0 || de > total {
            format!("0/{total}")
        } else {
            format!("{de}-{a}/{total}")
        };
        Resultat {
            statut: "ok".into(),
            modele: json_ordonne(vec![
                ("file", json!(r.rel)),
                ("lines", json!(plage)),
                ("content", json!(contenu.trim_end_matches('\n'))),
                ("note", note),
                ("status", json!("ok")),
            ]),
            resume: format!("{total} l."),
            chemin: Some(r.rel),
            ..Default::default()
        }
    }

    pub fn preparer_ecriture(&mut self, brut: &str, contenu: &str) -> Result<Ecriture, Resultat> {
        if brut.trim().is_empty() {
            return Err(Resultat::erreur("Write demande un « file_path »."));
        }
        let r = self.chemins.resoudre(brut).ok_or_else(|| self.dehors(brut))?;
        if r.rel.is_empty() || r.fichier.is_dir() {
            return Err(Resultat::erreur(format!(
                "{} est un dossier : donne le chemin d'un fichier.",
                montre(&r.rel)
            )));
        }
        let actuel = if r.fichier.is_file() {
            fs::read(&r.fichier).ok()
        } else {
            None
        };
        if let Some(m) = self.suivi.verifier_avant_ecriture(&r.rel, actuel.as_deref()) {
            return Err(Resultat::erreur(m));
        }
        let ancien = actuel
            .as_ref()
            .map(|o| String::from_utf8_lossy(o).to_string())
            .unwrap_or_default();
        let (d, plus, moins) = diff(&ancien, contenu);
        Ok(Ecriture {
            rel: r.rel,
            fichier: r.fichier,
            nouveau_texte: contenu.to_string(),
            nouveau: actuel.is_none(),
            diff: d,
            ajoutees: plus,
            retirees: moins,
            remplacements: 0,
            outil: "Write",
            base: actuel.as_deref().map(empreinte),
            modifs: 0,
        })
    }

    fn ouvrir_pour_modifier(
        &mut self,
        outil: &str,
        brut: &str,
    ) -> Result<(super::Resolu, Vec<u8>), Resultat> {
        if brut.trim().is_empty() {
            return Err(Resultat::erreur(format!("{outil} demande un « file_path ».")));
        }
        let r = self.chemins.resoudre(brut).ok_or_else(|| self.dehors(brut))?;
        if !r.fichier.is_file() {
            return Err(Resultat::erreur(if r.fichier.is_dir() {
                format!("{} est un dossier.", montre(&r.rel))
            } else {
                format!("Le fichier {} n'existe pas. Pour le créer, utilise Write.", r.rel)
            }));
        }
        let octets = fs::read(&r.fichier)
            .map_err(|e| Resultat::erreur(format!("Lecture impossible de {} : {e}", r.rel)))?;
        if !self.suivi.a_lu(&r.rel) {
            return Err(Resultat::erreur(format!(
                "Tu n'as pas encore lu {} dans cette session. Lis-le d'abord avec Read, puis copie le passage exact.",
                r.rel
            )));
        }
        if let Some(m) = self.suivi.verifier_avant_ecriture(&r.rel, Some(&octets)) {
            return Err(Resultat::erreur(m));
        }
        Ok((r, octets))
    }

    pub fn preparer_edit(
        &mut self,
        brut: &str,
        ancien: &str,
        nouveau: &str,
        partout: bool,
    ) -> Result<Ecriture, Resultat> {
        let (r, octets) = self.ouvrir_pour_modifier("Edit", brut)?;
        if ancien.is_empty() {
            return Err(Resultat::erreur(
                "old_string est vide. Pour remplir un fichier entier, utilise Write.",
            ));
        }
        if ancien == nouveau {
            return Err(Resultat::erreur(
                "old_string et new_string sont identiques : rien à changer.",
            ));
        }
        let texte = String::from_utf8_lossy(&octets).to_string();
        let (resultat, n) = remplacer(&texte, ancien, nouveau, partout).map_err(Resultat::erreur)?;
        let (d, plus, moins) = diff(&texte, &resultat);
        Ok(Ecriture {
            rel: r.rel,
            fichier: r.fichier,
            nouveau_texte: resultat,
            nouveau: false,
            diff: d,
            ajoutees: plus,
            retirees: moins,
            remplacements: n,
            outil: "Edit",
            base: Some(empreinte(&octets)),
            modifs: 0,
        })
    }

    pub fn preparer_multi_edit(
        &mut self,
        brut: &str,
        modifs: &[(String, String, bool)],
    ) -> Result<Ecriture, Resultat> {
        if modifs.is_empty() {
            return Err(Resultat::erreur(
                "MultiEdit demande au moins un remplacement dans « edits ».",
            ));
        }
        if modifs.len() > MAX_EDITS {
            return Err(Resultat::erreur(format!(
                "MultiEdit accepte {MAX_EDITS} remplacements au plus par appel."
            )));
        }
        let (r, octets) = self.ouvrir_pour_modifier("MultiEdit", brut)?;
        let original = String::from_utf8_lossy(&octets).to_string();
        let mut texte = original.clone();
        let mut total = 0;
        for (k, (ancien, nouveau, partout)) in modifs.iter().enumerate() {
            let prefixe = format!("Remplacement n° {} sur {} : ", k + 1, modifs.len());
            if ancien.is_empty() {
                return Err(Resultat::erreur(
                    prefixe + "old_string est vide. Aucun remplacement n'a été appliqué.",
                ));
            }
            if ancien == nouveau {
                return Err(Resultat::erreur(
                    prefixe
                        + "old_string et new_string sont identiques. Aucun remplacement n'a été appliqué.",
                ));
            }
            match remplacer(&texte, ancien, nouveau, *partout) {
                Ok((t, n)) => {
                    texte = t;
                    total += n;
                }
                Err(m) => {
                    return Err(Resultat::erreur(format!(
                        "{prefixe}{m}\nAucun remplacement n'a été appliqué."
                    )))
                }
            }
        }
        let (d, plus, moins) = diff(&original, &texte);
        Ok(Ecriture {
            rel: r.rel,
            fichier: r.fichier,
            nouveau_texte: texte,
            nouveau: false,
            diff: d,
            ajoutees: plus,
            retirees: moins,
            remplacements: total,
            outil: "MultiEdit",
            base: Some(empreinte(&octets)),
            modifs: modifs.len(),
        })
    }

    /// Le fichier a-t-il changé pendant l'attente de l'accord ? Alors rien n'est écrit.
    pub fn conflit(&mut self, e: &Ecriture) -> Option<String> {
        let maintenant = if e.fichier.is_file() {
            fs::read(&e.fichier).ok().map(|o| empreinte(&o))
        } else {
            None
        };
        if maintenant == e.base {
            return None;
        }
        self.suivi.oublier(&e.rel);
        Some(format!(
            "{PREFIXE_CONFLIT}{} a été modifié en dehors de Forge pendant que tu attendais l'accord : rien n'a été écrit. Relis-le avec Read, puis refais ta modification sur la nouvelle version.",
            e.rel
        ))
    }

    pub fn appliquer(&mut self, e: &Ecriture) -> Resultat {
        if let Err(err) = ecrire_atomiquement(&e.fichier, e.nouveau_texte.as_bytes()) {
            return Resultat::erreur(format!("Écriture impossible de {} : {err}", e.rel));
        }
        self.suivi.noter(&e.rel, e.nouveau_texte.as_bytes());
        let modele = match e.outil {
            "Edit" => json_ordonne(vec![
                ("file", json!(e.rel)),
                ("replacements", json!(e.remplacements)),
                ("added", json!(e.ajoutees)),
                ("removed", json!(e.retirees)),
                ("status", json!("ok")),
            ]),
            "MultiEdit" => json_ordonne(vec![
                ("file", json!(e.rel)),
                ("edits", json!(e.modifs)),
                ("replacements", json!(e.remplacements)),
                ("added", json!(e.ajoutees)),
                ("removed", json!(e.retirees)),
                ("status", json!("ok")),
            ]),
            _ => {
                let lignes = if e.nouveau_texte.is_empty() {
                    0
                } else {
                    e.nouveau_texte.trim_end_matches('\n').split('\n').count()
                };
                json_ordonne(vec![
                    ("file", json!(e.rel)),
                    ("created", json!(e.nouveau)),
                    ("lines", json!(lignes)),
                    ("added", json!(e.ajoutees)),
                    ("removed", json!(e.retirees)),
                    ("status", json!("ok")),
                ])
            }
        };
        Resultat {
            statut: "ok".into(),
            modele,
            resume: format!("+{} −{}", e.ajoutees, e.retirees),
            detail: e.diff.clone(),
            ajoutees: e.ajoutees,
            retirees: e.retirees,
            chemin: Some(e.rel.clone()),
        }
    }
}

/// La règle d'Edit : `ancien` doit être trouvé, et une seule fois sauf avec `partout`.
pub fn remplacer(texte: &str, ancien: &str, nouveau: &str, partout: bool) -> Result<(String, usize), String> {
    let positions: Vec<usize> = texte.match_indices(ancien).map(|m| m.0).collect();
    match positions.len() {
        0 => Err("old_string est introuvable dans le fichier (le texte doit être identique, espaces et indentation compris). \
                  Relis le fichier avec Read et copie le passage exact."
            .to_string()),
        n if n > 1 && !partout => {
            let lignes: Vec<String> =
                positions.iter().take(8).map(|p| (texte[..*p].matches('\n').count() + 1).to_string()).collect();
            Err(format!(
                "old_string apparaît {n} fois (lignes {}). Ajoute des lignes voisines pour qu'il soit unique, ou mets replace_all à true.",
                lignes.join(", ")
            ))
        }
        n => Ok((texte.replace(ancien, nouveau), n)),
    }
}
