//! Les 14 outils de Forge : schémas figés de `docs/forge/OUTILS.md` (copie exacte dans
//! `schemas.jsonl`, vérifiée par un test), noms tolérés et lecture des arguments.

use serde_json::Value;

/// Les 14 lignes JSON du § 2 de `docs/forge/OUTILS.md`, dans l'ordre.
pub const SCHEMAS_JSONL: &str = include_str!("schemas.jsonl");

pub const READ: &str = "Read";
pub const WRITE: &str = "Write";
pub const EDIT: &str = "Edit";
pub const GLOB: &str = "Glob";
pub const GREP: &str = "Grep";
pub const BASH: &str = "Bash";
pub const BASH_OUTPUT: &str = "BashOutput";
pub const KILL_SHELL: &str = "KillShell";
pub const TODO_WRITE: &str = "TodoWrite";
pub const EXIT_PLAN_MODE: &str = "ExitPlanMode";
pub const ASK_USER_QUESTION: &str = "AskUserQuestion";
pub const MULTI_EDIT: &str = "MultiEdit";
pub const WEB_FETCH: &str = "WebFetch";
pub const WEB_SEARCH: &str = "WebSearch";

/// Ce que le PC change aux descriptions (jamais aux noms ni aux paramètres) : l'environnement
/// n'est pas le Linux Alpine du téléphone.
pub struct Adaptation<'a> {
    /// « PowerShell », « sh »…
    pub shell: &'a str,
    /// « Windows », « Linux », « macOS ».
    pub systeme: &'a str,
}

/// Les schémas tels quels (ceux du téléphone et du jeu de données Argora).
pub fn schemas_figes() -> Vec<Value> {
    SCHEMAS_JSONL
        .lines()
        .filter(|l| !l.trim().is_empty())
        .map(|l| serde_json::from_str(l).expect("schéma d'outil invalide"))
        .collect()
}

/// Les schémas donnés au modèle sur PC : mêmes outils, mêmes paramètres, deux descriptions adaptées.
pub fn schemas_pc(a: &Adaptation) -> Vec<Value> {
    let mut v = schemas_figes();
    for s in v.iter_mut() {
        let f = &mut s["function"];
        match f["name"].as_str().unwrap_or("") {
            BASH => {
                f["description"] = Value::String(format!(
                    "Lance une commande shell ({}) sur l'ordinateur de l'utilisateur ({}), depuis le dossier du projet. \
                     Le dossier courant est gardé d'une commande à l'autre. Pour un serveur ou une tâche longue \
                     (installation, compilation), mets run_in_background à true puis lis la sortie avec BashOutput. \
                     Une commande restée trop longtemps sans sortie passe d'elle-même en arrière-plan (job_id dans le résultat).",
                    a.shell, a.systeme
                ));
            }
            READ => {
                f["parameters"]["properties"]["file_path"]["description"] = Value::String(
                    "Chemin du fichier, relatif au projet (src/app.js) ou absolu dans le dossier du projet."
                        .into(),
                );
            }
            GREP => {
                f["parameters"]["properties"]["pattern"]["description"] =
                    Value::String("L'expression régulière.".into());
            }
            _ => {}
        }
    }
    v
}

pub fn noms() -> Vec<String> {
    schemas_figes()
        .iter()
        .map(|s| s["function"]["name"].as_str().unwrap_or("").to_string())
        .collect()
}

/// Nom canonique d'un outil : casse, `_` et `-` ignorés, plus quelques alias.
pub fn nom_canonique(brut: &str) -> Option<String> {
    let n = brut.trim().to_lowercase().replace(['_', '-'], "");
    if let Some(x) = noms().into_iter().find(|x| x.to_lowercase() == n) {
        return Some(x);
    }
    let alias = match n.as_str() {
        "readfile" => READ,
        "writefile" => WRITE,
        "editfile" => EDIT,
        "runcommand" | "shell" => BASH,
        "checkjob" => BASH_OUTPUT,
        "todo" | "todos" => TODO_WRITE,
        "edits" => MULTI_EDIT,
        "fetch" | "fetchurl" | "readurl" => WEB_FETCH,
        "search" | "recherche" => WEB_SEARCH,
        _ => return None,
    };
    Some(alias.to_string())
}

/// Première chaîne non vide parmi `noms` (un nombre ou un booléen est rendu en texte).
pub fn chaine(args: &Value, noms: &[&str]) -> String {
    for n in noms {
        match args.get(n) {
            Some(Value::String(s)) if !s.is_empty() => return s.clone(),
            Some(Value::String(_)) | Some(Value::Null) | None => {}
            Some(v) => return v.to_string(),
        }
    }
    String::new()
}

/// Texte qui peut être vide (new_string, content).
pub fn texte(args: &Value, noms: &[&str]) -> Option<String> {
    for n in noms {
        match args.get(n) {
            Some(Value::Null) | None => {}
            Some(Value::String(s)) => return Some(s.clone()),
            Some(v) => return Some(v.to_string()),
        }
    }
    None
}

pub fn entier(args: &Value, noms: &[&str]) -> Option<i64> {
    for n in noms {
        match args.get(n) {
            Some(Value::Number(x)) => return x.as_i64().or_else(|| x.as_f64().map(|f| f as i64)),
            Some(Value::String(s)) => {
                if let Ok(i) = s.trim().parse::<i64>() {
                    return Some(i);
                }
            }
            _ => {}
        }
    }
    None
}

pub fn booleen(args: &Value, noms: &[&str]) -> bool {
    for n in noms {
        match args.get(n) {
            Some(Value::Bool(b)) => return *b,
            Some(Value::String(s)) if s.eq_ignore_ascii_case("true") => return true,
            _ => {}
        }
    }
    false
}

/// Un tableau, accepté aussi sous forme de texte JSON (les petits modèles envoient souvent une chaîne).
pub fn tableau(args: &Value, noms: &[&str]) -> Option<Vec<Value>> {
    for n in noms {
        match args.get(n) {
            Some(Value::Array(a)) => return Some(a.clone()),
            Some(Value::String(s)) => {
                if let Ok(Value::Array(a)) = serde_json::from_str::<Value>(s.trim()) {
                    return Some(a);
                }
            }
            _ => {}
        }
    }
    None
}
