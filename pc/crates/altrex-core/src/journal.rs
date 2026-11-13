//! Le journal d'une session Forge : une ligne JSON par événement, en ajout seul. Même format que
//! le téléphone (`forge/journal/ForgeEvent.kt`, version 1) : une session écrite d'un côté se relit
//! de l'autre. Les événements que le PC ne produit pas encore (instantanés, Build…) sont gardés
//! tels quels ([`Evenement::Autre`]).

use crate::appels;
use crate::permissions::{Accords, Mode};
use serde_json::{json, Map, Value};
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

pub const VERSION_FORMAT: i64 = 1;

#[derive(Debug, Clone, PartialEq)]
pub struct Tache {
    pub content: String,
    pub status: String,
    pub active_form: String,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Evenement {
    Session {
        id: String,
        project: String,
        model: String,
        mode: String,
        title: String,
    },
    DebutTour {
        turn: i64,
    },
    Utilisateur {
        turn: i64,
        text: String,
        auto: bool,
    },
    Assistant {
        turn: i64,
        raw: String,
        display: String,
        thinking: String,
    },
    AppelOutil {
        turn: i64,
        id: String,
        name: String,
        args: String,
    },
    ResultatOutil {
        turn: i64,
        id: String,
        name: String,
        status: String,
        model: String,
        summary: String,
        detail: String,
        added: i64,
        removed: i64,
        path: Option<String>,
    },
    Accord {
        turn: i64,
        id: String,
        decision: String,
        reason: String,
        grant: Option<String>,
        key: String,
    },
    Taches {
        turn: i64,
        items: Vec<Tache>,
    },
    Mode {
        mode: String,
    },
    Plan {
        turn: i64,
        id: String,
        markdown: String,
    },
    DecisionPlan {
        turn: i64,
        id: String,
        approved: bool,
        feedback: String,
        mode: Option<String>,
    },
    Question {
        turn: i64,
        id: String,
        question: String,
        options: Vec<String>,
    },
    Reponse {
        turn: i64,
        id: String,
        answer: String,
    },
    Note {
        turn: i64,
        kind: String,
        text: String,
        for_model: bool,
        shown: bool,
    },
    FinTour {
        turn: i64,
        reason: String,
        detail: String,
    },
    /// Événement connu du téléphone mais pas (encore) du PC : relu et réécrit sans perte.
    Autre(Value),
}

fn s(o: &Value, k: &str) -> String {
    match o.get(k) {
        Some(Value::String(x)) => x.clone(),
        Some(Value::Null) | None => String::new(),
        Some(v) => v.to_string(),
    }
}
fn i(o: &Value, k: &str) -> i64 {
    o.get(k).and_then(|v| v.as_i64()).unwrap_or(0)
}
fn b(o: &Value, k: &str, defaut: bool) -> bool {
    o.get(k).and_then(|v| v.as_bool()).unwrap_or(defaut)
}
fn opt(o: &Value, k: &str) -> Option<String> {
    o.get(k).map(|_| s(o, k))
}
fn liste(o: &Value, k: &str) -> Vec<String> {
    o.get(k)
        .and_then(|v| v.as_array())
        .map(|a| {
            a.iter()
                .map(|x| x.as_str().map(str::to_string).unwrap_or_else(|| x.to_string()))
                .collect()
        })
        .unwrap_or_default()
}

const TYPES_AUTRES: [&str; 6] = ["checkpoint", "undo", "compaction", "summary", "learning", "build"];

impl Evenement {
    pub fn type_(&self) -> &str {
        match self {
            Evenement::Session { .. } => "session",
            Evenement::DebutTour { .. } => "turn_start",
            Evenement::Utilisateur { .. } => "user",
            Evenement::Assistant { .. } => "assistant",
            Evenement::AppelOutil { .. } => "tool_call",
            Evenement::ResultatOutil { .. } => "tool_result",
            Evenement::Accord { .. } => "approval",
            Evenement::Taches { .. } => "todos",
            Evenement::Mode { .. } => "mode",
            Evenement::Plan { .. } => "plan",
            Evenement::DecisionPlan { .. } => "plan_decision",
            Evenement::Question { .. } => "question",
            Evenement::Reponse { .. } => "answer",
            Evenement::Note { .. } => "note",
            Evenement::FinTour { .. } => "turn_end",
            Evenement::Autre(v) => v.get("t").and_then(|t| t.as_str()).unwrap_or(""),
        }
    }

    /// Le tour de l'événement (0 : hors tour).
    pub fn tour(&self) -> i64 {
        match self {
            Evenement::DebutTour { turn }
            | Evenement::Utilisateur { turn, .. }
            | Evenement::Assistant { turn, .. }
            | Evenement::AppelOutil { turn, .. }
            | Evenement::ResultatOutil { turn, .. }
            | Evenement::Accord { turn, .. }
            | Evenement::Taches { turn, .. }
            | Evenement::Plan { turn, .. }
            | Evenement::DecisionPlan { turn, .. }
            | Evenement::Question { turn, .. }
            | Evenement::Reponse { turn, .. }
            | Evenement::Note { turn, .. }
            | Evenement::FinTour { turn, .. } => *turn,
            Evenement::Autre(v) if v.get("t").and_then(|t| t.as_str()) != Some("learning") => i(v, "turn"),
            _ => 0,
        }
    }

    /// La ligne JSON (clés dans l'ordre du téléphone), avec l'heure `ts` en millisecondes.
    pub fn en_json(&self, ts: i64) -> Value {
        if let Evenement::Autre(v) = self {
            return v.clone();
        }
        let mut o = Map::new();
        o.insert("t".into(), json!(self.type_()));
        o.insert("ts".into(), json!(ts));
        let mut put = |k: &str, v: Value| {
            o.insert(k.to_string(), v);
        };
        match self {
            Evenement::Session {
                id,
                project,
                model,
                mode,
                title,
            } => {
                put("v", json!(VERSION_FORMAT));
                put("id", json!(id));
                put("project", json!(project));
                put("model", json!(model));
                put("mode", json!(mode));
                put("title", json!(title));
            }
            Evenement::DebutTour { turn } => put("turn", json!(turn)),
            Evenement::Utilisateur { turn, text, auto } => {
                put("turn", json!(turn));
                put("text", json!(text));
                if *auto {
                    put("auto", json!(true));
                }
            }
            Evenement::Assistant {
                turn,
                raw,
                display,
                thinking,
            } => {
                put("turn", json!(turn));
                put("raw", json!(raw));
                put("display", json!(display));
                if !thinking.is_empty() {
                    put("thinking", json!(thinking));
                }
            }
            Evenement::AppelOutil { turn, id, name, args } => {
                put("turn", json!(turn));
                put("id", json!(id));
                put("name", json!(name));
                put("args", json!(args));
            }
            Evenement::ResultatOutil {
                turn,
                id,
                name,
                status,
                model,
                summary,
                detail,
                added,
                removed,
                path,
            } => {
                put("turn", json!(turn));
                put("id", json!(id));
                put("name", json!(name));
                put("status", json!(status));
                put("model", json!(model));
                if !summary.is_empty() {
                    put("summary", json!(summary));
                }
                if !detail.is_empty() {
                    put("detail", json!(detail));
                }
                if *added != 0 {
                    put("added", json!(added));
                }
                if *removed != 0 {
                    put("removed", json!(removed));
                }
                if let Some(p) = path {
                    put("path", json!(p));
                }
            }
            Evenement::Accord {
                turn,
                id,
                decision,
                reason,
                grant,
                key,
            } => {
                put("turn", json!(turn));
                put("id", json!(id));
                put("decision", json!(decision));
                if !reason.is_empty() {
                    put("reason", json!(reason));
                }
                if let Some(g) = grant {
                    put("grant", json!(g));
                }
                if !key.is_empty() {
                    put("key", json!(key));
                }
            }
            Evenement::Taches { turn, items } => {
                put("turn", json!(turn));
                let arr: Vec<Value> = items
                    .iter()
                    .map(|t| json!({"content": t.content, "status": t.status, "activeForm": t.active_form}))
                    .collect();
                put("items", Value::Array(arr));
            }
            Evenement::Mode { mode } => put("mode", json!(mode)),
            Evenement::Plan { turn, id, markdown } => {
                put("turn", json!(turn));
                put("id", json!(id));
                put("markdown", json!(markdown));
            }
            Evenement::DecisionPlan {
                turn,
                id,
                approved,
                feedback,
                mode,
            } => {
                put("turn", json!(turn));
                put("id", json!(id));
                put("approved", json!(approved));
                put("feedback", json!(feedback));
                if let Some(m) = mode {
                    put("mode", json!(m));
                }
            }
            Evenement::Question {
                turn,
                id,
                question,
                options,
            } => {
                put("turn", json!(turn));
                put("id", json!(id));
                put("question", json!(question));
                put("options", json!(options));
            }
            Evenement::Reponse { turn, id, answer } => {
                put("turn", json!(turn));
                put("id", json!(id));
                put("answer", json!(answer));
            }
            Evenement::Note {
                turn,
                kind,
                text,
                for_model,
                shown,
            } => {
                put("turn", json!(turn));
                put("kind", json!(kind));
                put("text", json!(text));
                put("forModel", json!(for_model));
                put("shown", json!(shown));
            }
            Evenement::FinTour { turn, reason, detail } => {
                put("turn", json!(turn));
                put("reason", json!(reason));
                if !detail.is_empty() {
                    put("detail", json!(detail));
                }
            }
            Evenement::Autre(_) => unreachable!(),
        }
        Value::Object(o)
    }

    /// `None` pour une ligne inconnue ou abîmée (arrêt au milieu d'une écriture).
    pub fn depuis_json(o: &Value) -> Option<Evenement> {
        if !o.is_object() {
            return None;
        }
        let turn = i(o, "turn");
        Some(match s(o, "t").as_str() {
            "session" => Evenement::Session {
                id: s(o, "id"),
                project: s(o, "project"),
                model: s(o, "model"),
                mode: s(o, "mode"),
                title: s(o, "title"),
            },
            "turn_start" => Evenement::DebutTour { turn },
            "user" => Evenement::Utilisateur {
                turn,
                text: s(o, "text"),
                auto: b(o, "auto", false),
            },
            "assistant" => Evenement::Assistant {
                turn,
                raw: s(o, "raw"),
                display: s(o, "display"),
                thinking: s(o, "thinking"),
            },
            "tool_call" => Evenement::AppelOutil {
                turn,
                id: s(o, "id"),
                name: s(o, "name"),
                args: s(o, "args"),
            },
            "tool_result" => Evenement::ResultatOutil {
                turn,
                id: s(o, "id"),
                name: s(o, "name"),
                status: s(o, "status"),
                model: s(o, "model"),
                summary: s(o, "summary"),
                detail: s(o, "detail"),
                added: i(o, "added"),
                removed: i(o, "removed"),
                path: opt(o, "path"),
            },
            "approval" => Evenement::Accord {
                turn,
                id: s(o, "id"),
                decision: s(o, "decision"),
                reason: s(o, "reason"),
                grant: opt(o, "grant"),
                key: s(o, "key"),
            },
            "todos" => Evenement::Taches {
                turn,
                items: o
                    .get("items")
                    .and_then(|v| v.as_array())
                    .map(|a| {
                        a.iter()
                            .filter(|x| x.is_object())
                            .map(|x| Tache {
                                content: s(x, "content"),
                                status: s(x, "status"),
                                active_form: s(x, "activeForm"),
                            })
                            .collect()
                    })
                    .unwrap_or_default(),
            },
            "mode" => Evenement::Mode { mode: s(o, "mode") },
            "plan" => Evenement::Plan {
                turn,
                id: s(o, "id"),
                markdown: s(o, "markdown"),
            },
            "plan_decision" => Evenement::DecisionPlan {
                turn,
                id: s(o, "id"),
                approved: b(o, "approved", false),
                feedback: s(o, "feedback"),
                mode: opt(o, "mode"),
            },
            "question" => Evenement::Question {
                turn,
                id: s(o, "id"),
                question: s(o, "question"),
                options: liste(o, "options"),
            },
            "answer" => Evenement::Reponse {
                turn,
                id: s(o, "id"),
                answer: s(o, "answer"),
            },
            "note" => Evenement::Note {
                turn,
                kind: s(o, "kind"),
                text: s(o, "text"),
                for_model: b(o, "forModel", false),
                shown: b(o, "shown", true),
            },
            "turn_end" => Evenement::FinTour {
                turn,
                reason: s(o, "reason"),
                detail: s(o, "detail"),
            },
            t if TYPES_AUTRES.contains(&t) => Evenement::Autre(o.clone()),
            _ => return None,
        })
    }
}

pub fn maintenant_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// Un journal sur disque. Chaque événement est écrit et synchronisé avant de continuer.
pub struct Journal {
    pub fichier: PathBuf,
    evenements: Mutex<Vec<(i64, Evenement)>>,
}

impl Journal {
    pub fn nouveau(fichier: PathBuf) -> Journal {
        Journal {
            fichier,
            evenements: Mutex::new(Vec::new()),
        }
    }

    pub fn id(&self) -> String {
        self.fichier
            .file_stem()
            .map(|s| s.to_string_lossy().to_string())
            .unwrap_or_default()
    }

    pub fn evenements(&self) -> Vec<Evenement> {
        self.evenements
            .lock()
            .unwrap()
            .iter()
            .map(|(_, e)| e.clone())
            .collect()
    }

    /// Heure du dernier événement (millisecondes), 0 si vide.
    pub fn dernier_ts(&self) -> i64 {
        self.evenements
            .lock()
            .unwrap()
            .iter()
            .map(|(t, _)| *t)
            .max()
            .unwrap_or(0)
    }

    pub fn ajouter(&self, e: Evenement) -> std::io::Result<()> {
        let mut ev = self.evenements.lock().unwrap();
        let ts = maintenant_ms();
        let ligne = e.en_json(ts).to_string() + "\n";
        if let Some(parent) = self.fichier.parent() {
            fs::create_dir_all(parent)?;
        }
        let mut f = OpenOptions::new().create(true).append(true).open(&self.fichier)?;
        f.write_all(ligne.as_bytes())?;
        f.sync_all()?;
        ev.push((ts, e));
        Ok(())
    }

    /// Relit un journal. Un tour resté ouvert (appli fermée pendant le tour) est fermé ici par des
    /// lignes ajoutées, jamais en coupant le fichier.
    pub fn charger(fichier: &Path) -> std::io::Result<Journal> {
        let j = Journal::nouveau(fichier.to_path_buf());
        if fichier.is_file() {
            let texte = String::from_utf8_lossy(&fs::read(fichier)?).to_string();
            // Une dernière ligne coupée (arrêt pendant l'écriture) est fermée, pour que la suite
            // ne se colle pas à elle.
            if !texte.is_empty() && !texte.ends_with('\n') {
                OpenOptions::new().append(true).open(fichier)?.write_all(b"\n")?;
            }
            let mut ev = j.evenements.lock().unwrap();
            for ligne in texte.lines() {
                if ligne.trim().is_empty() {
                    continue;
                }
                let Ok(v) = serde_json::from_str::<Value>(ligne) else {
                    continue;
                };
                if let Some(e) = Evenement::depuis_json(&v) {
                    ev.push((i(&v, "ts"), e));
                }
            }
        }
        for r in reparations(&j.evenements()) {
            j.ajouter(r)?;
        }
        Ok(j)
    }
}

pub const INTERROMPU: &str = r#"{"error":"Interrompu : l'appli a été fermée pendant cet appel. Il n'a pas été relancé ; vérifie son effet avant de le refaire.","status":"error"}"#;

/// Les événements qui ferment ce qu'un arrêt brutal a laissé ouvert ; vide si tout est fermé.
pub fn reparations(ev: &[Evenement]) -> Vec<Evenement> {
    let Some(dernier) = ev.iter().rev().find_map(|e| match e {
        Evenement::DebutTour { turn } => Some(*turn),
        _ => None,
    }) else {
        return Vec::new();
    };
    if ev
        .iter()
        .any(|e| matches!(e, Evenement::FinTour { turn, .. } if *turn == dernier))
    {
        return Vec::new();
    }
    let repondus: Vec<&String> = ev
        .iter()
        .filter_map(|e| match e {
            Evenement::ResultatOutil { id, .. } => Some(id),
            _ => None,
        })
        .collect();
    let mut out = Vec::new();
    for e in ev {
        if let Evenement::AppelOutil { turn, id, name, .. } = e {
            if *turn == dernier && !repondus.contains(&id) {
                out.push(Evenement::ResultatOutil {
                    turn: *turn,
                    id: id.clone(),
                    name: name.clone(),
                    status: "error".into(),
                    model: INTERROMPU.into(),
                    summary: "interrompu".into(),
                    detail: String::new(),
                    added: 0,
                    removed: 0,
                    path: None,
                });
            }
        }
    }
    out.push(Evenement::FinTour {
        turn: dernier,
        reason: "interrupted".into(),
        detail: "L'appli a été fermée pendant ce tour.".into(),
    });
    out
}

/// L'état d'une session, reconstruit du journal.
#[derive(Debug, Clone, Default)]
pub struct Etat {
    pub id: String,
    pub projet: String,
    pub modele: String,
    pub titre: String,
    pub mode: Option<Mode>,
    pub accords: Accords,
    pub taches: Vec<Tache>,
    pub dernier_tour: i64,
    pub tour_ouvert: bool,
    pub derniere_fin: Option<String>,
    pub a_un_message: bool,
}

impl Etat {
    pub fn mode(&self) -> Mode {
        self.mode.unwrap_or(Mode::DEFAUT)
    }
}

pub fn appliquer_accord(a: &mut Accords, decision: &str, reason: &str, grant: Option<&str>, key: &str) {
    if let Some(g) = grant {
        a.accorder(g);
    }
    if key.is_empty() {
        return;
    }
    if decision == "non" || decision == "sans_reponse" || decision == "arreter" {
        if a.refus.contains_key(key) {
            a.escalades.insert(key.to_string());
        }
        a.refus.insert(key.to_string(), reason.to_string());
    } else if decision == "oui" && a.refus.contains_key(key) {
        a.escalades.insert(key.to_string());
    }
}

pub fn replier(ev: &[Evenement]) -> Etat {
    let mut e = Etat::default();
    for x in ev {
        match x {
            Evenement::Session {
                id,
                project,
                model,
                mode,
                title,
            } => {
                e.id = id.clone();
                e.projet = project.clone();
                e.modele = model.clone();
                e.titre = title.clone();
                if let Some(m) = Mode::lire(mode) {
                    e.mode = Some(m);
                }
            }
            Evenement::DebutTour { turn } => {
                e.dernier_tour = *turn;
                e.tour_ouvert = true;
            }
            Evenement::Utilisateur { text, auto, .. } => {
                if !auto {
                    e.a_un_message = true;
                    if e.titre.is_empty() {
                        e.titre = text.lines().next().unwrap_or("").chars().take(60).collect();
                    }
                }
            }
            Evenement::Accord {
                decision,
                reason,
                grant,
                key,
                ..
            } => appliquer_accord(&mut e.accords, decision, reason, grant.as_deref(), key),
            Evenement::Taches { items, .. } => e.taches = items.clone(),
            Evenement::Mode { mode } => {
                if let Some(m) = Mode::lire(mode) {
                    e.mode = Some(m);
                }
            }
            Evenement::DecisionPlan { approved, mode, .. } => {
                if *approved {
                    if let Some(m) = mode.as_deref().and_then(Mode::lire) {
                        e.mode = Some(m);
                    }
                }
            }
            Evenement::FinTour { reason, .. } => {
                e.tour_ouvert = false;
                e.derniere_fin = Some(reason.clone());
            }
            _ => {}
        }
    }
    e
}

/// Un message de l'historique donné au modèle. `appels` et `resultats` gardent la structure pour
/// les modèles qui ont des appels d'outils natifs ; [`Message::texte_complet`] donne le protocole
/// texte, identique à celui du téléphone.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct Message {
    /// « user » ou « assistant ».
    pub role: String,
    /// Texte hors appels et hors résultats.
    pub texte: String,
    /// (id, nom, arguments) des appels d'un message de l'assistant.
    pub appels: Vec<(String, String, Value)>,
    /// (id, résultat) des outils, dans un message « user ».
    pub resultats: Vec<(String, String)>,
    /// Texte placé avant `texte` dans le protocole texte (résultats d'abord, rappels ensuite).
    pub brut_assistant: Option<String>,
}

impl Message {
    pub fn user(t: impl Into<String>) -> Message {
        Message {
            role: "user".into(),
            texte: t.into(),
            ..Default::default()
        }
    }
    pub fn assistant(t: impl Into<String>) -> Message {
        Message {
            role: "assistant".into(),
            texte: t.into(),
            ..Default::default()
        }
    }

    /// Le contenu en protocole texte.
    pub fn texte_complet(&self) -> String {
        if self.role == "assistant" {
            return match &self.brut_assistant {
                Some(b) if !b.trim().is_empty() => b.clone(),
                _ if self.texte.trim().is_empty() => "…".into(),
                _ => self.texte.clone(),
            };
        }
        let res: Vec<String> = self.resultats.iter().map(|r| r.1.clone()).collect();
        let bloc = if res.is_empty() {
            String::new()
        } else {
            appels::message_resultats(&res)
        };
        [bloc, self.texte.clone()]
            .into_iter()
            .filter(|x| !x.trim().is_empty())
            .collect::<Vec<_>>()
            .join("\n\n")
    }
}

pub fn orientation(texte: &str) -> String {
    format!(
        "<rappel-forge>Message de l'utilisateur pendant ton travail : « {} ». Tiens-en compte dès maintenant.</rappel-forge>",
        texte.trim()
    )
}

/// L'historique pour le modèle : les tours passés condensés, le tour courant en entier
/// (même découpage que `SessionState.modelMessages` du téléphone ; la compaction du téléphone,
/// événements `compaction` et `summary`, n'est pas encore appliquée sur PC).
pub fn messages_modele(ev: &[Evenement], tour_courant: i64) -> Vec<Message> {
    let mut out: Vec<Message> = Vec::new();
    let mut tours: Vec<i64> = ev
        .iter()
        .map(|e| e.tour())
        .filter(|t| *t >= 1 && *t <= tour_courant)
        .collect();
    tours.sort();
    tours.dedup();
    for t in tours {
        let du_tour: Vec<&Evenement> = ev.iter().filter(|e| e.tour() == t).collect();
        if t < tour_courant {
            condense(&du_tour, &mut out);
        } else {
            entier(&du_tour, &mut out);
        }
    }
    out
}

fn condense(ev: &[&Evenement], out: &mut Vec<Message>) {
    let user: Vec<&str> = ev
        .iter()
        .filter_map(|e| match e {
            Evenement::Utilisateur { text, .. } => Some(text.as_str()),
            _ => None,
        })
        .collect();
    let user = user.join("\n\n");
    if user.is_empty() {
        return;
    }
    let notes: Vec<&str> = ev
        .iter()
        .filter_map(|e| match e {
            Evenement::Note {
                kind,
                text,
                for_model: true,
                ..
            } if kind == "contexte" => Some(text.as_str()),
            _ => None,
        })
        .collect();
    let notes = notes.join("\n\n");
    out.push(Message::user(
        [notes, user]
            .into_iter()
            .filter(|x| !x.trim().is_empty())
            .collect::<Vec<_>>()
            .join("\n\n"),
    ));
    let outils: Vec<String> = ev
        .iter()
        .filter_map(|e| match e {
            Evenement::ResultatOutil {
                name,
                path,
                added,
                removed,
                status,
                ..
            } => Some(format!(
                "{}{}{}{}",
                name,
                path.as_ref().map(|p| format!(" {p}")).unwrap_or_default(),
                if added + removed > 0 {
                    format!(" (+{added} −{removed})")
                } else {
                    String::new()
                },
                if status != "ok" {
                    format!(" [{status}]")
                } else {
                    String::new()
                }
            )),
            _ => None,
        })
        .collect();
    let outils = outils.join(", ");
    let reponse = ev
        .iter()
        .rev()
        .find_map(|e| match e {
            Evenement::Assistant { display, .. } if !display.trim().is_empty() => Some(display.clone()),
            _ => None,
        })
        .unwrap_or_default();
    let fin = ev.iter().rev().find_map(|e| match e {
        Evenement::FinTour { reason, detail, .. } => Some((reason, detail)),
        _ => None,
    });
    let note_fin = match fin {
        Some((r, d)) if r != "done" => {
            format!(
                "[Tour terminé : {r}{}]",
                if d.is_empty() {
                    String::new()
                } else {
                    format!(" — {d}")
                }
            )
        }
        _ => String::new(),
    };
    let outils_txt = if outils.is_empty() {
        String::new()
    } else {
        format!(
            "[Outils utilisés : {}]",
            outils.chars().take(1500).collect::<String>()
        )
    };
    let texte = [outils_txt, reponse, note_fin]
        .into_iter()
        .filter(|x| !x.trim().is_empty())
        .collect::<Vec<_>>()
        .join("\n");
    out.push(Message::assistant(if texte.is_empty() {
        "…".to_string()
    } else {
        texte
    }));
}

fn entier(ev: &[&Evenement], out: &mut Vec<Message>) {
    let mut notes: Vec<String> = Vec::new();
    let mut resultats: Vec<(String, String)> = Vec::new();
    fn vider(out: &mut Vec<Message>, resultats: &mut Vec<(String, String)>, notes: &mut Vec<String>) {
        if resultats.is_empty() && notes.is_empty() {
            return;
        }
        let rappel = notes.join("\n\n");
        match out.last_mut() {
            Some(last) if last.role == "user" && resultats.is_empty() => {
                last.texte = format!("{}\n\n{}", last.texte, rappel);
            }
            _ => out.push(Message {
                role: "user".into(),
                texte: rappel,
                resultats: std::mem::take(resultats),
                ..Default::default()
            }),
        }
        resultats.clear();
        notes.clear();
    }
    let mut appels_du_message: Vec<(String, String, Value)> = Vec::new();
    for (k, e) in ev.iter().enumerate() {
        match e {
            Evenement::Utilisateur { text, .. } => {
                vider(out, &mut resultats, &mut notes);
                out.push(Message::user(text.clone()));
            }
            Evenement::Assistant { raw, display, .. } => {
                vider(out, &mut resultats, &mut notes);
                // Les appels de cette réponse : les événements tool_call qui la suivent.
                appels_du_message.clear();
                for suivant in &ev[k + 1..] {
                    match suivant {
                        Evenement::AppelOutil { id, name, args, .. } => {
                            let a = serde_json::from_str::<Value>(args).unwrap_or_else(|_| json!({}));
                            appels_du_message.push((id.clone(), name.clone(), a));
                        }
                        Evenement::Assistant { .. } | Evenement::Utilisateur { .. } => break,
                        _ => {}
                    }
                }
                out.push(Message {
                    role: "assistant".into(),
                    texte: display.clone(),
                    appels: appels_du_message.clone(),
                    resultats: Vec::new(),
                    brut_assistant: Some(raw.clone()),
                });
            }
            Evenement::ResultatOutil { id, model, .. } => resultats.push((id.clone(), model.clone())),
            Evenement::Note {
                kind,
                text,
                for_model: true,
                ..
            } => {
                let t = if kind == "orientation" {
                    orientation(text)
                } else {
                    text.clone()
                };
                match out.last_mut() {
                    Some(last)
                        if resultats.is_empty() && last.role == "user" && last.resultats.is_empty() =>
                    {
                        last.texte = if kind == "contexte" {
                            format!("{t}\n\n{}", last.texte)
                        } else {
                            format!("{}\n\n{t}", last.texte)
                        };
                    }
                    _ => notes.push(t),
                }
            }
            _ => {}
        }
    }
    vider(out, &mut resultats, &mut notes);
}
