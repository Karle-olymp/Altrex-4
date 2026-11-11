//! L'aperçu local : un petit serveur HTTP statique sur `127.0.0.1`, confiné au dossier du projet,
//! que l'on ouvre dans le navigateur du système.

use crate::outils::Chemins;
use std::io::{BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

pub struct Apercu {
    pub port: u16,
    arret: Arc<AtomicBool>,
}

pub fn type_mime(chemin: &str) -> &'static str {
    match chemin.rsplit('.').next().unwrap_or("").to_lowercase().as_str() {
        "html" | "htm" => "text/html; charset=utf-8",
        "css" => "text/css; charset=utf-8",
        "js" | "mjs" => "text/javascript; charset=utf-8",
        "json" | "map" => "application/json; charset=utf-8",
        "svg" => "image/svg+xml",
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "ico" => "image/x-icon",
        "woff" => "font/woff",
        "woff2" => "font/woff2",
        "ttf" => "font/ttf",
        "wasm" => "application/wasm",
        "txt" | "md" => "text/plain; charset=utf-8",
        "xml" => "application/xml; charset=utf-8",
        "mp4" => "video/mp4",
        "mp3" => "audio/mpeg",
        _ => "application/octet-stream",
    }
}

/// Décode les `%XX` d'un chemin d'URL (`None` si l'encodage est invalide).
pub fn decoder_url(s: &str) -> Option<String> {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut k = 0;
    while k < b.len() {
        if b[k] == b'%' {
            let h = std::str::from_utf8(b.get(k + 1..k + 3)?).ok()?;
            out.push(u8::from_str_radix(h, 16).ok()?);
            k += 3;
        } else {
            out.push(b[k]);
            k += 1;
        }
    }
    String::from_utf8(out).ok()
}

fn repondre(flux: &mut TcpStream, code: &str, mime: &str, corps: &[u8], tete_seule: bool) {
    let entete = format!(
        "HTTP/1.1 {code}\r\nContent-Type: {mime}\r\nContent-Length: {}\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n",
        corps.len()
    );
    let _ = flux.write_all(entete.as_bytes());
    if !tete_seule {
        let _ = flux.write_all(corps);
    }
}

fn servir(mut flux: TcpStream, chemins: &Chemins) {
    let _ = flux.set_read_timeout(Some(Duration::from_secs(5)));
    let mut ligne = String::new();
    {
        let mut lecteur = BufReader::new(&flux);
        if lecteur.read_line(&mut ligne).is_err() {
            return;
        }
        // Le reste des en-têtes est lu puis ignoré.
        let mut autre = String::new();
        while lecteur.read_line(&mut autre).map(|n| n > 2).unwrap_or(false) {
            autre.clear();
        }
    }
    let mut parties = ligne.split_whitespace();
    let (methode, cible) = (parties.next().unwrap_or(""), parties.next().unwrap_or("/"));
    let tete_seule = methode == "HEAD";
    if methode != "GET" && !tete_seule {
        return repondre(
            &mut flux,
            "405 Method Not Allowed",
            "text/plain; charset=utf-8",
            "Méthode refusée.".as_bytes(),
            false,
        );
    }
    let chemin = cible.split(['?', '#']).next().unwrap_or("/");
    let Some(chemin) = decoder_url(chemin) else {
        return repondre(
            &mut flux,
            "400 Bad Request",
            "text/plain; charset=utf-8",
            "Adresse invalide.".as_bytes(),
            tete_seule,
        );
    };
    let rel = chemin.trim_start_matches('/');
    // Les dossiers de Forge et de Git ne sont jamais servis.
    let interdit =
        rel.split('/').any(|p| p == ".git" || p == ".altrex" || p == "..") || chemin.contains('\\');
    let resolu = if interdit {
        None
    } else {
        chemins.resoudre(if rel.is_empty() { "." } else { rel })
    };
    let Some(r) = resolu else {
        return repondre(
            &mut flux,
            "403 Forbidden",
            "text/plain; charset=utf-8",
            "Hors du projet.".as_bytes(),
            tete_seule,
        );
    };
    let fichier = if r.fichier.is_dir() {
        r.fichier.join("index.html")
    } else {
        r.fichier
    };
    match std::fs::read(&fichier) {
        Ok(octets) => repondre(
            &mut flux,
            "200 OK",
            type_mime(&fichier.to_string_lossy()),
            &octets,
            tete_seule,
        ),
        Err(_) => repondre(
            &mut flux,
            "404 Not Found",
            "text/plain; charset=utf-8",
            "Fichier introuvable.".as_bytes(),
            tete_seule,
        ),
    }
}

impl Apercu {
    /// Démarre le serveur sur un port libre de `127.0.0.1` (jamais sur le réseau).
    pub fn demarrer(projet: &Path) -> std::io::Result<Apercu> {
        let ecoute = TcpListener::bind(("127.0.0.1", 0))?;
        let port = ecoute.local_addr()?.port();
        ecoute.set_nonblocking(true)?;
        let arret = Arc::new(AtomicBool::new(false));
        let (a, chemins) = (arret.clone(), Chemins::nouveau(projet));
        std::thread::spawn(move || {
            while !a.load(Ordering::SeqCst) {
                match ecoute.accept() {
                    Ok((flux, _)) => {
                        let _ = flux.set_nonblocking(false);
                        let c = chemins.clone();
                        std::thread::spawn(move || servir(flux, &c));
                    }
                    Err(_) => std::thread::sleep(Duration::from_millis(40)),
                }
            }
        });
        Ok(Apercu { port, arret })
    }

    pub fn adresse(&self) -> String {
        format!("http://127.0.0.1:{}/", self.port)
    }
}

impl Drop for Apercu {
    fn drop(&mut self) {
        self.arret.store(true, Ordering::SeqCst);
    }
}
