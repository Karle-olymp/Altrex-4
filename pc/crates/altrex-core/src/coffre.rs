//! Le coffre des clés d'API : jamais en clair sur le disque.
//!
//! - Windows : DPAPI (`CryptProtectData`), liée au compte Windows de l'utilisateur.
//! - Linux, macOS : AES-256-GCM avec une clé locale (`coffre.cle`, droits 0600). Cela protège d'une
//!   copie du fichier de clés seul, pas de quelqu'un qui a accès au compte.

use base64::Engine;
use serde_json::{Map, Value};
use std::fs;
use std::path::{Path, PathBuf};

pub struct Coffre {
    dossier: PathBuf,
}

const B64: base64::engine::GeneralPurpose = base64::engine::general_purpose::STANDARD;

#[cfg(windows)]
mod plateforme {
    use std::path::Path;
    use windows_sys::Win32::Foundation::LocalFree;
    use windows_sys::Win32::Security::Cryptography::{
        CryptProtectData, CryptUnprotectData, CRYPT_INTEGER_BLOB,
    };

    pub const PREFIXE: &str = "dpapi:";

    fn appeler(entree: &[u8], chiffrer: bool) -> Option<Vec<u8>> {
        let e = CRYPT_INTEGER_BLOB {
            cbData: entree.len() as u32,
            pbData: entree.as_ptr() as *mut u8,
        };
        let mut s = CRYPT_INTEGER_BLOB {
            cbData: 0,
            pbData: std::ptr::null_mut(),
        };
        // SAFETY : `e` pointe sur `entree` pendant tout l'appel ; `s` est rempli par Windows et
        // libéré par LocalFree après copie.
        unsafe {
            let ok = if chiffrer {
                CryptProtectData(
                    &e,
                    std::ptr::null(),
                    std::ptr::null(),
                    std::ptr::null(),
                    std::ptr::null(),
                    0,
                    &mut s,
                )
            } else {
                CryptUnprotectData(
                    &e,
                    std::ptr::null_mut(),
                    std::ptr::null(),
                    std::ptr::null(),
                    std::ptr::null(),
                    0,
                    &mut s,
                )
            };
            if ok == 0 || s.pbData.is_null() {
                return None;
            }
            let v = std::slice::from_raw_parts(s.pbData, s.cbData as usize).to_vec();
            LocalFree(s.pbData as _);
            Some(v)
        }
    }

    pub fn chiffrer(_dossier: &Path, clair: &[u8]) -> Option<Vec<u8>> {
        appeler(clair, true)
    }
    pub fn dechiffrer(_dossier: &Path, chiffre: &[u8]) -> Option<Vec<u8>> {
        appeler(chiffre, false)
    }
}

#[cfg(not(windows))]
mod plateforme {
    use aes_gcm::aead::{Aead, KeyInit};
    use aes_gcm::{Aes256Gcm, Nonce};
    use std::fs;
    use std::path::Path;

    pub const PREFIXE: &str = "aesgcm:";

    fn cle(dossier: &Path) -> Option<[u8; 32]> {
        let f = dossier.join("coffre.cle");
        if let Ok(o) = fs::read(&f) {
            if let Ok(k) = <[u8; 32]>::try_from(o.as_slice()) {
                return Some(k);
            }
        }
        let mut k = [0u8; 32];
        getrandom::getrandom(&mut k).ok()?;
        fs::create_dir_all(dossier).ok()?;
        fs::write(&f, k).ok()?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&f, fs::Permissions::from_mode(0o600)).ok()?;
        }
        Some(k)
    }

    pub fn chiffrer(dossier: &Path, clair: &[u8]) -> Option<Vec<u8>> {
        let c = Aes256Gcm::new_from_slice(&cle(dossier)?).ok()?;
        let mut nonce = [0u8; 12];
        getrandom::getrandom(&mut nonce).ok()?;
        let mut out = nonce.to_vec();
        out.extend(c.encrypt(Nonce::from_slice(&nonce), clair).ok()?);
        Some(out)
    }

    pub fn dechiffrer(dossier: &Path, chiffre: &[u8]) -> Option<Vec<u8>> {
        if chiffre.len() < 13 {
            return None;
        }
        let c = Aes256Gcm::new_from_slice(&cle(dossier)?).ok()?;
        c.decrypt(Nonce::from_slice(&chiffre[..12]), &chiffre[12..]).ok()
    }
}

impl Coffre {
    /// `dossier` : le dossier de réglages de l'appli (les clés sont dans `cles.json`).
    pub fn nouveau(dossier: &Path) -> Coffre {
        Coffre {
            dossier: dossier.to_path_buf(),
        }
    }

    fn fichier(&self) -> PathBuf {
        self.dossier.join("cles.json")
    }

    fn tout(&self) -> Map<String, Value> {
        fs::read_to_string(self.fichier())
            .ok()
            .and_then(|t| serde_json::from_str::<Value>(&t).ok())
            .and_then(|v| v.as_object().cloned())
            .unwrap_or_default()
    }

    /// La clé enregistrée sous `nom` (un fournisseur), ou `None`.
    pub fn lire(&self, nom: &str) -> Option<String> {
        let v = self.tout();
        let chiffre = v
            .get(nom)?
            .as_str()?
            .strip_prefix(plateforme::PREFIXE)?
            .to_string();
        let clair = plateforme::dechiffrer(&self.dossier, &B64.decode(chiffre).ok()?)?;
        String::from_utf8(clair)
            .ok()
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
    }

    pub fn a(&self, nom: &str) -> bool {
        self.lire(nom).is_some()
    }

    /// Enregistre la clé (vide : la supprime). Faux si le chiffrement ou l'écriture a échoué :
    /// la clé n'est alors jamais écrite en clair.
    pub fn enregistrer(&self, nom: &str, cle: &str) -> bool {
        let mut v = self.tout();
        if cle.trim().is_empty() {
            v.remove(nom);
        } else {
            let Some(chiffre) = plateforme::chiffrer(&self.dossier, cle.trim().as_bytes()) else {
                return false;
            };
            v.insert(
                nom.to_string(),
                Value::String(format!("{}{}", plateforme::PREFIXE, B64.encode(chiffre))),
            );
        }
        let texte = serde_json::to_string_pretty(&Value::Object(v)).unwrap_or_default();
        crate::outils::fichiers::ecrire_atomiquement(&self.fichier(), texte.as_bytes()).is_ok()
    }
}
