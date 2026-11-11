//! Protocole texte des appels d'outils (`<tool_call>` … `</tool_call>`), le même que sur le
//! téléphone (`agent/ToolCalling.kt`) et que dans le jeu de données Argora.

use serde_json::{Map, Value};

pub const CALL_OPEN: &str = "<tool_call>";
pub const CALL_CLOSE: &str = "</tool_call>";
pub const RESP_OPEN: &str = "<tool_response>";
pub const RESP_CLOSE: &str = "</tool_response>";
pub const THINK_OPEN: &str = "<think>";
pub const THINK_CLOSE: &str = "</think>";

/// Un appel lu dans la réponse du modèle. `nom` vide : appel illisible (`brut` garde le texte).
#[derive(Debug, Clone, PartialEq)]
pub struct Appel {
    pub nom: String,
    pub arguments: Value,
    pub brut: String,
}

/// Le bloc d'outils ajouté au prompt système (§ 1 de `docs/forge/OUTILS.md`).
pub fn bloc_outils(schemas: &[Value]) -> String {
    let mut s = String::from(
        "# Tools\n\nYou may call one or more functions to assist with the user query.\n\n\
         You are provided with function signatures within <tools></tools> XML tags:\n<tools>\n",
    );
    for t in schemas {
        s.push_str(&t.to_string());
        s.push('\n');
    }
    s.push_str(
        "</tools>\n\nFor each function call, return a json object with function name and arguments within \
         <tool_call></tool_call> XML tags:\n<tool_call>\n{\"name\": <function-name>, \"arguments\": <args-json-object>}\n</tool_call>",
    );
    s
}

fn un_appel(corps: &str) -> Option<Appel> {
    let obj: Value = serde_json::from_str(corps).ok()?;
    let o = obj.as_object()?;
    let nom = match o.get("name") {
        Some(Value::String(s)) => s.clone(),
        Some(Value::Null) | None => String::new(),
        Some(v) => v.to_string(),
    };
    if nom.is_empty() {
        return None;
    }
    let brut_args = o.get("arguments").or_else(|| o.get("parameters"));
    let arguments = match brut_args {
        Some(Value::Object(m)) => Value::Object(m.clone()),
        Some(Value::String(s)) => match serde_json::from_str::<Value>(s) {
            Ok(Value::Object(m)) => Value::Object(m),
            _ => Value::Object(Map::new()),
        },
        _ => Value::Object(Map::new()),
    };
    Some(Appel {
        nom,
        arguments,
        brut: corps.to_string(),
    })
}

/// Une réponse qui n'est que `{"name": …, "arguments": {…}}`, sans les balises.
pub fn appel_nu(texte: &str) -> Option<Appel> {
    let t = texte.trim();
    if !t.starts_with('{') || !t.ends_with('}') {
        return None;
    }
    let obj: Value = serde_json::from_str(t).ok()?;
    let o = obj.as_object()?;
    if !o.contains_key("name") || !(o.contains_key("arguments") || o.contains_key("parameters")) {
        return None;
    }
    un_appel(t)
}

pub fn lire_appels(texte: &str) -> Vec<Appel> {
    let mut appels = Vec::new();
    let mut idx = 0;
    while let Some(pos) = texte[idx..].find(CALL_OPEN) {
        let debut = idx + pos + CALL_OPEN.len();
        let fin = texte[debut..].find(CALL_CLOSE).map(|p| debut + p);
        let corps = match fin {
            Some(f) => &texte[debut..f],
            None => &texte[debut..],
        }
        .trim();
        idx = match fin {
            Some(f) => f + CALL_CLOSE.len(),
            None => texte.len(),
        };
        appels.push(un_appel(corps).unwrap_or(Appel {
            nom: String::new(),
            arguments: Value::Object(Map::new()),
            brut: corps.to_string(),
        }));
    }
    if appels.is_empty() {
        if let Some(a) = appel_nu(texte) {
            appels.push(a);
        }
    }
    appels
}

fn retirer_blocs(texte: &str, ouvre: &str, ferme: &str) -> String {
    let mut out = String::new();
    let mut idx = 0;
    while let Some(pos) = texte[idx..].find(ouvre) {
        out.push_str(&texte[idx..idx + pos]);
        let apres = idx + pos + ouvre.len();
        match texte[apres..].find(ferme) {
            Some(p) => idx = apres + p + ferme.len(),
            None => {
                idx = texte.len();
            }
        }
    }
    out.push_str(&texte[idx..]);
    out
}

/// Le texte du modèle sans ses blocs d'appel (un bloc non terminé est retiré aussi).
pub fn sans_appels(texte: &str) -> String {
    if appel_nu(texte).is_some() {
        return String::new();
    }
    retirer_blocs(texte, CALL_OPEN, CALL_CLOSE).trim().to_string()
}

/// La réponse sans réflexion (bloc non terminé compris) ni balise fermante isolée.
pub fn sans_reflexion(texte: &str) -> String {
    retirer_blocs(texte, THINK_OPEN, THINK_CLOSE).replace(THINK_CLOSE, "")
}

/// La réflexion seule (contenu des blocs `<think>`).
pub fn reflexion(texte: &str) -> String {
    let mut out = String::new();
    let mut idx = 0;
    while let Some(pos) = texte[idx..].find(THINK_OPEN) {
        let debut = idx + pos + THINK_OPEN.len();
        let fin = texte[debut..]
            .find(THINK_CLOSE)
            .map(|p| debut + p)
            .unwrap_or(texte.len());
        out.push_str(texte[debut..fin].trim());
        out.push('\n');
        idx = (fin + THINK_CLOSE.len()).min(texte.len());
    }
    out.trim().to_string()
}

/// Ce que l'utilisateur voit d'une réponse brute : ni réflexion, ni JSON d'appel.
pub fn texte_affiche(brut: &str) -> String {
    sans_appels(&sans_reflexion(brut))
}

/// Le bloc d'un appel, tel qu'il est gardé dans l'historique (`raw` du journal).
pub fn bloc_appel(nom: &str, arguments: &Value) -> String {
    let mut o = Map::new();
    o.insert("name".into(), Value::String(nom.to_string()));
    o.insert("arguments".into(), arguments.clone());
    format!("{CALL_OPEN}\n{}\n{CALL_CLOSE}", Value::Object(o))
}

/// Les résultats d'outils, renvoyés au modèle dans un message `user`.
pub fn message_resultats(resultats: &[String]) -> String {
    let mut s = String::new();
    for r in resultats {
        s.push_str(RESP_OPEN);
        s.push('\n');
        s.push_str(r);
        s.push('\n');
        s.push_str(RESP_CLOSE);
        s.push('\n');
    }
    s.trim_end().to_string()
}
