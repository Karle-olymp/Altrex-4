#![allow(dead_code)]
//! Outils communs aux tests : dossier temporaire, faux modèle, faux écran, faux serveur HTTP.

use altrex_core::journal::Message;
use altrex_core::modeles::{Erreur, Modele, Reponse};
use altrex_core::moteur::{Choix, DemandeAccord, Interactions, ReponseAccord, RevuePlan};
use serde_json::Value;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpListener;
use std::path::PathBuf;
use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex};

pub fn dossier(nom: &str) -> PathBuf {
    static N: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
    let d = std::env::temp_dir().join(format!(
        "altrex-test-{nom}-{}-{}",
        std::process::id(),
        N.fetch_add(1, std::sync::atomic::Ordering::SeqCst)
    ));
    let _ = std::fs::remove_dir_all(&d);
    std::fs::create_dir_all(&d).unwrap();
    d
}

/// Un modèle qui rend des réponses écrites d'avance et garde ce qu'on lui a envoyé.
pub struct FauxModele {
    pub reponses: Mutex<Vec<Result<Reponse, Erreur>>>,
    pub recus: Mutex<Vec<Vec<Message>>>,
    pub local: bool,
}

impl FauxModele {
    pub fn textes(reponses: &[&str]) -> Arc<FauxModele> {
        Arc::new(FauxModele {
            reponses: Mutex::new(
                reponses
                    .iter()
                    .map(|t| {
                        Ok(Reponse {
                            texte: t.to_string(),
                            ..Default::default()
                        })
                    })
                    .collect(),
            ),
            recus: Mutex::new(Vec::new()),
            local: true,
        })
    }
    pub fn dernier_recu(&self) -> String {
        self.recus
            .lock()
            .unwrap()
            .last()
            .map(|m| m.last().map(|x| x.texte_complet()).unwrap_or_default())
            .unwrap_or_default()
    }
}

impl Modele for FauxModele {
    fn nom(&self) -> String {
        "faux".into()
    }
    fn local(&self) -> bool {
        self.local
    }
    fn budget_car(&self) -> usize {
        60_000
    }
    fn generer(
        &self,
        _systeme: &str,
        messages: &[Message],
        _outils: &[Value],
        sur_texte: &mut dyn FnMut(&str),
        _arret: &AtomicBool,
    ) -> Result<Reponse, Erreur> {
        self.recus.lock().unwrap().push(messages.to_vec());
        let mut r = self.reponses.lock().unwrap();
        if r.is_empty() {
            return Ok(Reponse {
                texte: "Terminé.".into(),
                ..Default::default()
            });
        }
        let rep = r.remove(0);
        if let Ok(x) = &rep {
            sur_texte(&x.texte);
        }
        rep
    }
}

/// Un écran qui répond toujours la même chose et note ce qu'on lui a demandé.
pub struct FauxEcran {
    pub choix: Mutex<Choix>,
    pub plan: Mutex<RevuePlan>,
    pub reponse: Option<String>,
    pub demandes: Mutex<Vec<DemandeAccord>>,
    /// Appelé pendant l'attente de l'accord (pour simuler un changement extérieur).
    pub pendant: Mutex<Option<Box<dyn Fn() + Send>>>,
}

impl FauxEcran {
    pub fn nouveau(choix: Choix) -> Arc<FauxEcran> {
        Arc::new(FauxEcran {
            choix: Mutex::new(choix),
            plan: Mutex::new(RevuePlan::Refuse),
            reponse: Some("React".into()),
            demandes: Mutex::new(Vec::new()),
            pendant: Mutex::new(None),
        })
    }
}

impl Interactions for FauxEcran {
    fn approuver(&self, d: &DemandeAccord) -> ReponseAccord {
        self.demandes.lock().unwrap().push(d.clone());
        if let Some(f) = self.pendant.lock().unwrap().as_ref() {
            f();
        }
        ReponseAccord {
            choix: *self.choix.lock().unwrap(),
            motif: String::new(),
        }
    }
    fn demander(&self, _id: &str, _q: &str, _o: &[String]) -> Option<String> {
        self.reponse.clone()
    }
    fn revoir_plan(&self, _id: &str, _m: &str) -> RevuePlan {
        self.plan.lock().unwrap().clone()
    }
}

/// Un serveur HTTP qui répond `reponse` (réponse HTTP complète) à chaque requête et garde les corps reçus.
/// Les requêtes reçues par le faux serveur : (en-têtes, corps).
pub type Recus = Arc<Mutex<Vec<(String, String)>>>;

pub fn faux_serveur(reponse: String) -> (String, Recus) {
    let ecoute = TcpListener::bind(("127.0.0.1", 0)).unwrap();
    let port = ecoute.local_addr().unwrap().port();
    let recus = Arc::new(Mutex::new(Vec::new()));
    let r = recus.clone();
    std::thread::spawn(move || {
        for flux in ecoute.incoming() {
            let Ok(mut flux) = flux else { continue };
            let mut lecteur = BufReader::new(flux.try_clone().unwrap());
            let mut entetes = String::new();
            let mut taille = 0usize;
            loop {
                let mut l = String::new();
                if lecteur.read_line(&mut l).unwrap_or(0) == 0 || l == "\r\n" {
                    break;
                }
                if let Some(v) = l.to_lowercase().strip_prefix("content-length:") {
                    taille = v.trim().parse().unwrap_or(0);
                }
                entetes.push_str(&l);
            }
            let mut corps = vec![0u8; taille];
            let _ = lecteur.read_exact(&mut corps);
            r.lock()
                .unwrap()
                .push((entetes, String::from_utf8_lossy(&corps).to_string()));
            let _ = flux.write_all(reponse.as_bytes());
        }
    });
    (format!("http://127.0.0.1:{port}"), recus)
}

pub fn sse(evenements: &[&str]) -> String {
    let corps: String = evenements.iter().map(|e| format!("data: {e}\n\n")).collect();
    format!("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{corps}", corps.len())
}
