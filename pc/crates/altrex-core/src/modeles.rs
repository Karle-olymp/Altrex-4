//! Les modèles : API cloud (Anthropic en natif, les autres au format OpenAI) et `llama-server`
//! local (format OpenAI, protocole texte de Forge). Les clés ne quittent l'appli que vers l'hôte
//! fixe de leur fournisseur.

use crate::appels;
use crate::journal::Message;
use serde_json::{json, Map, Value};
use std::collections::HashMap;
use std::io::{BufRead, BufReader};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc;
use std::sync::Mutex;
use std::time::Duration;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Protocole {
    Anthropic,
    OpenAi,
}

/// Un fournisseur cloud (mêmes identifiants que sur le téléphone, `runtime/CloudApi.kt`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Fournisseur {
    pub id: &'static str,
    pub libelle: &'static str,
    pub base: &'static str,
    pub protocole: Protocole,
    /// Où l'utilisateur crée une clé.
    pub page_cle: &'static str,
    pub suggestions: &'static [&'static str],
}

pub const FOURNISSEURS: [Fournisseur; 8] = [
    Fournisseur {
        id: "anthropic",
        libelle: "Claude (Anthropic)",
        base: "https://api.anthropic.com/v1",
        protocole: Protocole::Anthropic,
        page_cle: "https://platform.claude.com/settings/keys",
        suggestions: &["claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"],
    },
    Fournisseur {
        id: "openai",
        libelle: "GPT (OpenAI)",
        base: "https://api.openai.com/v1",
        protocole: Protocole::OpenAi,
        page_cle: "https://platform.openai.com/api-keys",
        suggestions: &[],
    },
    Fournisseur {
        id: "gemini",
        libelle: "Gemini (Google)",
        base: "https://generativelanguage.googleapis.com/v1beta/openai",
        protocole: Protocole::OpenAi,
        page_cle: "https://aistudio.google.com/apikey",
        suggestions: &[],
    },
    Fournisseur {
        id: "xai",
        libelle: "Grok (xAI)",
        base: "https://api.x.ai/v1",
        protocole: Protocole::OpenAi,
        page_cle: "https://console.x.ai",
        suggestions: &[],
    },
    Fournisseur {
        id: "mistral",
        libelle: "Mistral",
        base: "https://api.mistral.ai/v1",
        protocole: Protocole::OpenAi,
        page_cle: "https://console.mistral.ai/api-keys",
        suggestions: &[],
    },
    Fournisseur {
        id: "deepseek",
        libelle: "DeepSeek",
        base: "https://api.deepseek.com",
        protocole: Protocole::OpenAi,
        page_cle: "https://platform.deepseek.com/api_keys",
        suggestions: &[],
    },
    Fournisseur {
        id: "openrouter",
        libelle: "OpenRouter (des centaines de modèles)",
        base: "https://openrouter.ai/api/v1",
        protocole: Protocole::OpenAi,
        page_cle: "https://openrouter.ai/settings/keys",
        suggestions: &[],
    },
    Fournisseur {
        id: "groq",
        libelle: "Groq",
        base: "https://api.groq.com/openai/v1",
        protocole: Protocole::OpenAi,
        page_cle: "https://console.groq.com/keys",
        suggestions: &[],
    },
];

pub fn fournisseur(id: &str) -> Option<Fournisseur> {
    FOURNISSEURS.iter().copied().find(|f| f.id == id)
}

/// La réponse d'un modèle à une étape.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct Reponse {
    /// Le texte pour l'utilisateur (sans réflexion ; en protocole texte, les blocs d'appel y sont encore).
    pub texte: String,
    pub reflexion: String,
    /// Appels natifs : (id, nom, arguments). Vide en protocole texte.
    pub appels: Vec<(String, String, Value)>,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Erreur {
    /// Trop de requêtes (HTTP 429) ; l'attente demandée par le fournisseur, s'il l'a dite.
    Limite {
        message: String,
        attente_ms: Option<u64>,
    },
    Http {
        code: u16,
        message: String,
    },
    Reseau(String),
    /// Le modèle a refusé de répondre (filtre de sécurité du fournisseur).
    Refus(String),
    Arrete,
}

impl std::fmt::Display for Erreur {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Erreur::Limite { message, .. } => write!(f, "Trop de requêtes : {message}"),
            Erreur::Http { code, message } => write!(f, "Erreur {code} : {message}"),
            Erreur::Reseau(m) => write!(f, "Réseau : {m}"),
            Erreur::Refus(m) => write!(f, "Le modèle a refusé de répondre : {m}"),
            Erreur::Arrete => write!(f, "Arrêté."),
        }
    }
}

/// Un modèle, quel que soit ce qui le fait tourner.
pub trait Modele: Send + Sync {
    fn nom(&self) -> String;
    /// Un modèle local : limites plus petites par défaut.
    fn local(&self) -> bool;
    /// Caractères d'historique qui tiennent dans le contexte, prompt système non compris.
    fn budget_car(&self) -> usize;
    /// Une réponse à `messages`, en flux vers `sur_texte` (morceaux de texte visible).
    fn generer(
        &self,
        systeme: &str,
        messages: &[Message],
        outils: &[Value],
        sur_texte: &mut dyn FnMut(&str),
        arret: &AtomicBool,
    ) -> Result<Reponse, Erreur>;
}

// ------------------------------------------------------------------------------------------ HTTP

fn agent() -> ureq::Agent {
    ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(15))
        .timeout_read(Duration::from_secs(600))
        .build()
}

/// Le message d'erreur d'une réponse HTTP (`error.message` du JSON s'il y en a un).
pub fn message_erreur(corps: &str) -> String {
    let v: Value = serde_json::from_str(corps).unwrap_or(Value::Null);
    let m = v["error"]["message"]
        .as_str()
        .or_else(|| v["error"].as_str())
        .or_else(|| v["message"].as_str());
    m.map(str::to_string)
        .unwrap_or_else(|| corps.chars().take(300).collect())
}

fn erreur_http(code: u16, attente: Option<&str>, corps: &str) -> Erreur {
    let message = message_erreur(corps);
    if code == 429 {
        let attente_ms = attente
            .and_then(|s| s.trim().parse::<f64>().ok())
            .map(|s| (s * 1000.0) as u64);
        Erreur::Limite { message, attente_ms }
    } else {
        Erreur::Http { code, message }
    }
}

/// Envoie `corps` en POST et rend les événements SSE (événement, données) un par un à `sur_evenement`.
/// La lecture se fait sur un autre fil : `arret` interrompt l'attente tout de suite, même si le flux se tait.
fn post_sse(
    url: &str,
    entetes: &[(&str, String)],
    corps: &Value,
    arret: &AtomicBool,
    sur_evenement: &mut dyn FnMut(&str, &str) -> Result<(), Erreur>,
) -> Result<(), Erreur> {
    let mut req = agent()
        .post(url)
        .set("content-type", "application/json")
        .set("accept", "text/event-stream");
    for (k, v) in entetes {
        req = req.set(k, v);
    }
    let texte = corps.to_string();
    let (tx, rx) = mpsc::channel::<Result<(String, String), Erreur>>();
    std::thread::spawn(move || {
        let rep = match req.send_string(&texte) {
            Ok(r) => r,
            Err(ureq::Error::Status(code, r)) => {
                let attente = r.header("retry-after").map(str::to_string);
                let corps = r.into_string().unwrap_or_default();
                let _ = tx.send(Err(erreur_http(code, attente.as_deref(), &corps)));
                return;
            }
            Err(e) => {
                let _ = tx.send(Err(Erreur::Reseau(e.to_string())));
                return;
            }
        };
        let (mut evenement, mut donnees) = (String::new(), String::new());
        for ligne in BufReader::new(rep.into_reader()).lines() {
            let Ok(ligne) = ligne else {
                let _ = tx.send(Err(Erreur::Reseau("connexion coupée pendant la réponse".into())));
                return;
            };
            if let Some(e) = ligne.strip_prefix("event:") {
                evenement = e.trim().to_string();
            } else if let Some(d) = ligne.strip_prefix("data:") {
                if !donnees.is_empty() {
                    donnees.push('\n');
                }
                donnees.push_str(d.trim_start());
            } else if ligne.is_empty()
                && !donnees.is_empty()
                && tx
                    .send(Ok((std::mem::take(&mut evenement), std::mem::take(&mut donnees))))
                    .is_err()
            {
                return;
            }
        }
        if !donnees.is_empty() {
            let _ = tx.send(Ok((evenement, donnees)));
        }
    });
    loop {
        if arret.load(Ordering::SeqCst) {
            return Err(Erreur::Arrete);
        }
        match rx.recv_timeout(Duration::from_millis(80)) {
            Ok(Ok((e, d))) => sur_evenement(&e, &d)?,
            Ok(Err(e)) => return Err(e),
            Err(mpsc::RecvTimeoutError::Timeout) => {}
            Err(mpsc::RecvTimeoutError::Disconnected) => return Ok(()),
        }
    }
}

// ---------------------------------------------------------------------------- format OpenAI

/// Un modèle au format OpenAI : un fournisseur cloud (appels d'outils natifs) ou `llama-server`
/// (protocole texte de Forge, celui sur lequel Argora est entraîné).
pub struct ModeleOpenAi {
    pub libelle: String,
    /// « https://api.openai.com/v1 » ou « http://127.0.0.1:8080/v1 ».
    pub base: String,
    pub cle: Option<String>,
    pub modele: String,
    pub natif: bool,
    pub local: bool,
    pub budget_car: usize,
}

/// Fusionne les messages consécutifs du même rôle (certains gabarits exigent l'alternance).
pub fn messages_texte(systeme: &str, messages: &[Message]) -> Vec<Value> {
    let mut out: Vec<(String, String)> = vec![("system".into(), systeme.to_string())];
    for m in messages {
        let t = m.texte_complet();
        match out.last_mut() {
            Some(last) if last.0 == m.role => {
                last.1.push_str("\n\n");
                last.1.push_str(&t);
            }
            _ => out.push((m.role.clone(), t)),
        }
    }
    out.into_iter()
        .map(|(r, c)| json!({"role": r, "content": c}))
        .collect()
}

pub fn messages_openai(systeme: &str, messages: &[Message]) -> Vec<Value> {
    let mut out = vec![json!({"role": "system", "content": systeme})];
    for m in messages {
        if m.role == "assistant" {
            let mut o = Map::new();
            o.insert("role".into(), json!("assistant"));
            o.insert(
                "content".into(),
                if m.texte.trim().is_empty() && !m.appels.is_empty() {
                    Value::Null
                } else {
                    json!(m.texte)
                },
            );
            if !m.appels.is_empty() {
                let appels: Vec<Value> = m
                    .appels
                    .iter()
                    .map(|(id, nom, args)| json!({"id": id, "type": "function", "function": {"name": nom, "arguments": args.to_string()}}))
                    .collect();
                o.insert("tool_calls".into(), Value::Array(appels));
            }
            out.push(Value::Object(o));
        } else {
            for (id, r) in &m.resultats {
                out.push(json!({"role": "tool", "tool_call_id": id, "content": r}));
            }
            if !m.texte.trim().is_empty() || m.resultats.is_empty() {
                out.push(json!({"role": "user", "content": m.texte}));
            }
        }
    }
    out
}

/// Lit un morceau du flux OpenAI ; rend le texte visible ajouté.
#[derive(Default)]
pub struct FluxOpenAi {
    pub reponse: Reponse,
    partiels: Vec<(String, String, String)>,
}

impl FluxOpenAi {
    pub fn morceau(&mut self, donnees: &str) -> Result<String, Erreur> {
        if donnees.trim() == "[DONE]" {
            return Ok(String::new());
        }
        let Ok(v) = serde_json::from_str::<Value>(donnees) else {
            return Ok(String::new());
        };
        if v.get("error").is_some() {
            return Err(Erreur::Http {
                code: 0,
                message: message_erreur(donnees),
            });
        }
        let delta = &v["choices"][0]["delta"];
        let mut ajout = String::new();
        if let Some(t) = delta["content"].as_str() {
            self.reponse.texte.push_str(t);
            ajout.push_str(t);
        }
        if let Some(t) = delta["reasoning_content"]
            .as_str()
            .or_else(|| delta["reasoning"].as_str())
        {
            self.reponse.reflexion.push_str(t);
        }
        if let Some(appels) = delta["tool_calls"].as_array() {
            for a in appels {
                let k = a["index"]
                    .as_u64()
                    .unwrap_or(self.partiels.len().saturating_sub(1) as u64) as usize;
                while self.partiels.len() <= k {
                    self.partiels.push(Default::default());
                }
                let p = &mut self.partiels[k];
                if let Some(id) = a["id"].as_str() {
                    p.0 = id.to_string();
                }
                if let Some(n) = a["function"]["name"].as_str() {
                    p.1.push_str(n);
                }
                if let Some(x) = a["function"]["arguments"].as_str() {
                    p.2.push_str(x);
                }
            }
        }
        Ok(ajout)
    }

    pub fn finir(mut self) -> Reponse {
        for (k, (id, nom, args)) in self.partiels.into_iter().enumerate() {
            if nom.is_empty() {
                continue;
            }
            let id = if id.is_empty() { format!("call_{k}") } else { id };
            let args = serde_json::from_str::<Value>(&args)
                .ok()
                .filter(|v| v.is_object())
                .unwrap_or_else(|| json!({}));
            self.reponse.appels.push((id, nom, args));
        }
        self.reponse
    }
}

impl Modele for ModeleOpenAi {
    fn nom(&self) -> String {
        self.libelle.clone()
    }
    fn local(&self) -> bool {
        self.local
    }
    fn budget_car(&self) -> usize {
        self.budget_car
    }
    fn generer(
        &self,
        systeme: &str,
        messages: &[Message],
        outils: &[Value],
        sur_texte: &mut dyn FnMut(&str),
        arret: &AtomicBool,
    ) -> Result<Reponse, Erreur> {
        let mut corps = json!({"model": self.modele, "stream": true});
        if self.natif {
            corps["messages"] = Value::Array(messages_openai(systeme, messages));
            corps["tools"] = Value::Array(outils.to_vec());
        } else {
            let s = format!("{systeme}\n\n{}", appels::bloc_outils(outils));
            corps["messages"] = Value::Array(messages_texte(&s, messages));
        }
        let mut entetes = Vec::new();
        if let Some(c) = &self.cle {
            entetes.push(("authorization", format!("Bearer {c}")));
        }
        let mut flux = FluxOpenAi::default();
        post_sse(
            &format!("{}/chat/completions", self.base.trim_end_matches('/')),
            &entetes,
            &corps,
            arret,
            &mut |_, d| {
                let ajout = flux.morceau(d)?;
                if !ajout.is_empty() {
                    sur_texte(&ajout);
                }
                Ok(())
            },
        )?;
        Ok(flux.finir())
    }
}

// ------------------------------------------------------------------------------- Anthropic

/// Claude par l'API Messages d'Anthropic (HTTP direct : il n'y a pas de SDK officiel pour Rust).
pub struct ModeleAnthropic {
    pub cle: String,
    pub modele: String,
    pub base: String,
    /// Les blocs exacts des réponses du tour en cours (réflexion signée comprise), par identifiant du
    /// premier appel : ils sont renvoyés tels quels, comme l'API le demande pendant une suite d'outils.
    blocs: Mutex<HashMap<String, Vec<Value>>>,
}

pub const VERSION_ANTHROPIC: &str = "2023-06-01";
pub const MAX_TOKENS: u64 = 32_000;

impl ModeleAnthropic {
    pub fn nouveau(cle: &str, modele: &str) -> ModeleAnthropic {
        ModeleAnthropic {
            cle: cle.to_string(),
            modele: modele.to_string(),
            base: "https://api.anthropic.com/v1".into(),
            blocs: Mutex::new(HashMap::new()),
        }
    }
}

/// Les outils au format d'Anthropic (`input_schema`), à partir des schémas au format OpenAI.
pub fn outils_anthropic(outils: &[Value]) -> Vec<Value> {
    outils
        .iter()
        .map(|o| {
            let f = &o["function"];
            json!({"name": f["name"], "description": f["description"], "input_schema": f["parameters"]})
        })
        .collect()
}

pub fn messages_anthropic(messages: &[Message], gardes: &HashMap<String, Vec<Value>>) -> Vec<Value> {
    let mut out: Vec<Value> = Vec::new();
    for m in messages {
        let contenu: Vec<Value> = if m.role == "assistant" {
            match m.appels.first().and_then(|a| gardes.get(&a.0)) {
                Some(blocs) => blocs.clone(),
                None => {
                    let mut c = Vec::new();
                    if !m.texte.trim().is_empty() || m.appels.is_empty() {
                        c.push(json!({"type": "text", "text": if m.texte.trim().is_empty() { "…" } else { m.texte.as_str() }}));
                    }
                    for (id, nom, args) in &m.appels {
                        c.push(json!({"type": "tool_use", "id": id, "name": nom, "input": args}));
                    }
                    c
                }
            }
        } else {
            let mut c: Vec<Value> = m
                .resultats
                .iter()
                .map(|(id, r)| json!({"type": "tool_result", "tool_use_id": id, "content": r}))
                .collect();
            if !m.texte.trim().is_empty() || c.is_empty() {
                c.push(json!({"type": "text", "text": if m.texte.trim().is_empty() { "…" } else { m.texte.as_str() }}));
            }
            c
        };
        match out.last_mut() {
            // Deux messages de suite du même rôle sont réunis.
            Some(last) if last["role"] == m.role.as_str() => {
                if let Some(a) = last["content"].as_array_mut() {
                    a.extend(contenu);
                }
            }
            _ => out.push(json!({"role": m.role, "content": contenu})),
        }
    }
    out
}

/// Lit le flux d'événements de l'API Messages.
#[derive(Default)]
pub struct FluxAnthropic {
    pub reponse: Reponse,
    /// Les blocs complets de la réponse, dans l'ordre (pour les renvoyer tels quels).
    pub blocs: Vec<Value>,
    json_partiel: String,
    pub raison_fin: String,
}

impl FluxAnthropic {
    pub fn evenement(&mut self, donnees: &str) -> Result<String, Erreur> {
        let Ok(v) = serde_json::from_str::<Value>(donnees) else {
            return Ok(String::new());
        };
        let mut ajout = String::new();
        match v["type"].as_str().unwrap_or("") {
            "content_block_start" => {
                self.blocs.push(v["content_block"].clone());
                self.json_partiel.clear();
            }
            "content_block_delta" => {
                let d = &v["delta"];
                let Some(bloc) = self.blocs.last_mut() else {
                    return Ok(ajout);
                };
                let ajouter = |bloc: &mut Value, cle: &str, t: &str| {
                    let avant = bloc[cle].as_str().unwrap_or("").to_string();
                    bloc[cle] = json!(avant + t);
                };
                match d["type"].as_str().unwrap_or("") {
                    "text_delta" => {
                        let t = d["text"].as_str().unwrap_or("");
                        ajouter(bloc, "text", t);
                        self.reponse.texte.push_str(t);
                        ajout.push_str(t);
                    }
                    "thinking_delta" => {
                        let t = d["thinking"].as_str().unwrap_or("");
                        ajouter(bloc, "thinking", t);
                        self.reponse.reflexion.push_str(t);
                    }
                    "signature_delta" => ajouter(bloc, "signature", d["signature"].as_str().unwrap_or("")),
                    "input_json_delta" => self
                        .json_partiel
                        .push_str(d["partial_json"].as_str().unwrap_or("")),
                    _ => {}
                }
            }
            "content_block_stop" => {
                if let Some(bloc) = self.blocs.last_mut() {
                    if bloc["type"] == "tool_use" {
                        // Une entrée coupée ou invalide devient un objet vide : l'outil répondra par une erreur claire.
                        let entree = serde_json::from_str::<Value>(&self.json_partiel)
                            .ok()
                            .filter(|x| x.is_object());
                        bloc["input"] = entree.unwrap_or_else(|| json!({}));
                        self.reponse.appels.push((
                            bloc["id"].as_str().unwrap_or("").to_string(),
                            bloc["name"].as_str().unwrap_or("").to_string(),
                            bloc["input"].clone(),
                        ));
                    }
                }
                self.json_partiel.clear();
            }
            "message_delta" => {
                if let Some(r) = v["delta"]["stop_reason"].as_str() {
                    self.raison_fin = r.to_string();
                }
            }
            "error" => {
                let m = v["error"]["message"]
                    .as_str()
                    .unwrap_or("erreur du fournisseur")
                    .to_string();
                return Err(
                    if v["error"]["type"] == "rate_limit_error" || v["error"]["type"] == "overloaded_error" {
                        Erreur::Limite {
                            message: m,
                            attente_ms: None,
                        }
                    } else {
                        Erreur::Http { code: 0, message: m }
                    },
                );
            }
            _ => {}
        }
        Ok(ajout)
    }
}

impl Modele for ModeleAnthropic {
    fn nom(&self) -> String {
        self.modele.clone()
    }
    fn local(&self) -> bool {
        false
    }
    fn budget_car(&self) -> usize {
        120_000
    }
    fn generer(
        &self,
        systeme: &str,
        messages: &[Message],
        outils: &[Value],
        sur_texte: &mut dyn FnMut(&str),
        arret: &AtomicBool,
    ) -> Result<Reponse, Erreur> {
        let gardes = self.blocs.lock().unwrap().clone();
        // Ni `thinking` ni `temperature` : les modèles actuels règlent leur réflexion eux-mêmes et
        // refusent les anciens paramètres.
        let corps = json!({
            "model": self.modele,
            "max_tokens": MAX_TOKENS,
            "system": systeme,
            "messages": messages_anthropic(messages, &gardes),
            "tools": outils_anthropic(outils),
            "stream": true,
        });
        let entetes = [
            ("x-api-key", self.cle.clone()),
            ("anthropic-version", VERSION_ANTHROPIC.to_string()),
        ];
        let mut flux = FluxAnthropic::default();
        post_sse(
            &format!("{}/messages", self.base.trim_end_matches('/')),
            &entetes,
            &corps,
            arret,
            &mut |_, d| {
                let ajout = flux.evenement(d)?;
                if !ajout.is_empty() {
                    sur_texte(&ajout);
                }
                Ok(())
            },
        )?;
        if flux.raison_fin == "refusal" {
            return Err(Erreur::Refus(
                "la demande a été déclinée par les filtres du fournisseur".into(),
            ));
        }
        if let Some(premier) = flux.reponse.appels.first() {
            let mut g = self.blocs.lock().unwrap();
            if g.len() > 200 {
                g.clear();
            }
            g.insert(premier.0.clone(), flux.blocs.clone());
        }
        Ok(flux.reponse)
    }
}

/// Les modèles qu'une clé peut utiliser (liste du fournisseur).
pub fn lister_modeles(f: Fournisseur, cle: &str) -> Result<Vec<String>, Erreur> {
    let url = format!("{}/models", f.base);
    let req = match f.protocole {
        Protocole::Anthropic => agent()
            .get(&url)
            .set("x-api-key", cle)
            .set("anthropic-version", VERSION_ANTHROPIC),
        Protocole::OpenAi => agent().get(&url).set("authorization", &format!("Bearer {cle}")),
    };
    match req.call() {
        Ok(r) => {
            let v: Value = serde_json::from_str(&r.into_string().unwrap_or_default()).unwrap_or(Value::Null);
            let mut ids: Vec<String> = v["data"]
                .as_array()
                .map(|a| {
                    a.iter()
                        .filter_map(|m| m["id"].as_str())
                        .map(|s| s.trim_start_matches("models/").to_string())
                        .collect()
                })
                .unwrap_or_default();
            ids.sort();
            Ok(ids)
        }
        Err(ureq::Error::Status(code, r)) => {
            Err(erreur_http(code, None, &r.into_string().unwrap_or_default()))
        }
        Err(e) => Err(Erreur::Reseau(e.to_string())),
    }
}
