//! Noyau d'Altrex pour PC : le moteur de Forge en Rust, sans interface.
//!
//! Le comportement suit celui du téléphone (Kotlin, `app/src/main/java/.../forge`) ; le contrat
//! commun est décrit dans `docs/pc/PLAN.md` § 5 et vérifié par `tests/contrat.rs`.

// Un outil qui échoue rend son résultat complet (ce que lit le modèle) comme erreur : c'est voulu.
#![allow(clippy::result_large_err)]

pub mod appels;
pub mod commandes;
pub mod journal;
pub mod outils;
pub mod permissions;
