//! Noyau d'Altrex pour PC : le moteur de Forge en Rust, sans interface.
//!
//! Le comportement suit celui du téléphone (Kotlin, `app/src/main/java/.../forge`) ; le contrat
//! commun est décrit dans `docs/pc/PLAN.md` § 5 et vérifié par `tests/contrat.rs`.

// Un outil qui échoue rend son résultat complet (ce que lit le modèle) comme erreur : c'est voulu.
#![allow(clippy::result_large_err)]

pub mod apercu;
pub mod appels;
pub mod coffre;
pub mod commandes;
pub mod espace;
pub mod journal;
pub mod llama;
pub mod memoire;
pub mod modeles;
pub mod moteur;
pub mod outils;
pub mod permissions;
pub mod shell;
