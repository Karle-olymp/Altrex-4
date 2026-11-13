//! Le contrat partagé avec le téléphone : les mêmes fichiers `docs/forge/contrat/*.json` sont
//! exécutés par le test Kotlin `ContratPartageTest`. Voir `docs/pc/PLAN.md` § 5.

use altrex_core::journal::{replier, Evenement};
use altrex_core::outils::specs;
use altrex_core::permissions::{decider, Accords, Action, Decision, Mode};
use altrex_core::{appels, commandes};
use serde_json::Value;
use std::path::PathBuf;

fn depot() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../..")
}

fn vecteurs(nom: &str) -> Value {
    let p = depot().join("docs/forge/contrat").join(nom);
    serde_json::from_str(&std::fs::read_to_string(&p).unwrap_or_else(|e| panic!("{}: {e}", p.display())))
        .unwrap()
}

#[test]
fn commandes_shell() {
    let v = vecteurs("commandes.json");
    let cas = v["cas"].as_array().unwrap();
    assert!(cas.len() >= 30);
    for c in cas {
        let cmd = c["commande"].as_str().unwrap();
        assert_eq!(
            commandes::est_simple(cmd),
            c["simple"].as_bool().unwrap(),
            "simple : {cmd}"
        );
        assert_eq!(
            commandes::lecture_seule(cmd),
            c["lecture_seule"].as_bool().unwrap(),
            "lecture seule : {cmd}"
        );
        assert_eq!(
            commandes::programme(cmd).as_deref(),
            c["programme"].as_str(),
            "programme : {cmd}"
        );
        assert_eq!(
            commandes::est_dangereuse(cmd),
            c["dangereuse"].as_bool().unwrap(),
            "à risque : {cmd}"
        );
    }
}

fn accords(v: &Value) -> Accords {
    let mut a = Accords::default();
    for p in v["programmes"].as_array().unwrap() {
        a.programmes.insert(p.as_str().unwrap().to_string());
    }
    a.fichiers = v["fichiers"].as_bool().unwrap();
    for (k, m) in v["refus"].as_object().unwrap() {
        a.refus.insert(k.clone(), m.as_str().unwrap().to_string());
    }
    for e in v["escalades"].as_array().unwrap() {
        a.escalades.insert(e.as_str().unwrap().to_string());
    }
    a
}

#[test]
fn permissions() {
    let v = vecteurs("permissions.json");
    let cas = v["cas"].as_array().unwrap();
    assert!(cas.len() >= 25);
    for (n, c) in cas.iter().enumerate() {
        let mode = Mode::lire(c["mode"].as_str().unwrap()).unwrap();
        let a = &c["action"];
        let action = if a["type"] == "ecrire" {
            Action::Ecrire {
                chemin: a["chemin"].as_str().map(str::to_string),
                nouveau: a["nouveau"].as_bool().unwrap(),
            }
        } else {
            Action::Commande {
                commande: a["commande"].as_str().unwrap().to_string(),
                justification: a["justification"].as_str().map(str::to_string),
            }
        };
        let att = &c["attendu"];
        let d = decider(mode, &accords(&c["accords"]), &action);
        match (&d, att["decision"].as_str().unwrap()) {
            (Decision::Autoriser, "autoriser") => {}
            (Decision::Refuser(m), "refuser") => assert_eq!(m, att["motif"].as_str().unwrap(), "cas {n}"),
            (Decision::Demander(q), "demander") => {
                assert_eq!(q.titre, att["titre"].as_str().unwrap(), "cas {n}");
                assert_eq!(q.grant.as_deref(), att["grant"].as_str(), "cas {n}");
                assert_eq!(q.risque, att["risque"].as_bool().unwrap(), "cas {n}");
                assert_eq!(q.escalade, att["escalade"].as_bool().unwrap(), "cas {n}");
            }
            _ => panic!("cas {n} : {d:?} au lieu de {att}"),
        }
    }
}

#[test]
fn appels_d_outils() {
    let v = vecteurs("appels.json");
    for c in v["cas"].as_array().unwrap() {
        let texte = c["texte"].as_str().unwrap();
        let lus = appels::lire_appels(texte);
        let attendus = c["appels"].as_array().unwrap();
        assert_eq!(lus.len(), attendus.len(), "{texte}");
        for (l, a) in lus.iter().zip(attendus) {
            assert_eq!(l.nom, a["nom"].as_str().unwrap(), "{texte}");
            assert_eq!(l.arguments, a["arguments"], "{texte}");
        }
        assert_eq!(
            appels::texte_affiche(texte),
            c["affiche"].as_str().unwrap(),
            "{texte}"
        );
    }
}

#[test]
fn journal_relu_a_l_identique_et_replie() {
    let v = vecteurs("journal.json");
    let mut ev = Vec::new();
    for l in v["lignes"].as_array().unwrap() {
        let e = Evenement::depuis_json(l).unwrap_or_else(|| panic!("ligne non lue : {l}"));
        assert_eq!(&e.en_json(l["ts"].as_i64().unwrap()), l, "réécriture différente");
        ev.push(e);
    }
    let e = replier(&ev);
    let att = &v["etat"];
    assert_eq!(e.mode().cle(), att["mode"].as_str().unwrap());
    let prog: Vec<&str> = e.accords.programmes.iter().map(|s| s.as_str()).collect();
    let att_prog: Vec<&str> = att["programmes"]
        .as_array()
        .unwrap()
        .iter()
        .map(|x| x.as_str().unwrap())
        .collect();
    assert_eq!(prog, att_prog);
    assert_eq!(e.accords.fichiers, att["fichiers"].as_bool().unwrap());
    for (k, m) in att["refus"].as_object().unwrap() {
        assert_eq!(e.accords.refus.get(k).map(|s| s.as_str()), m.as_str());
    }
    assert_eq!(e.accords.refus.len(), att["refus"].as_object().unwrap().len());
    assert_eq!(e.taches.len() as i64, att["taches_total"].as_i64().unwrap());
    assert_eq!(
        e.taches.iter().filter(|t| t.status == "completed").count() as i64,
        att["taches_terminees"].as_i64().unwrap()
    );
    assert_eq!(e.titre, att["titre"].as_str().unwrap());
    assert_eq!(e.dernier_tour, att["dernier_tour"].as_i64().unwrap());
    assert_eq!(e.tour_ouvert, att["tour_ouvert"].as_bool().unwrap());
    assert_eq!(e.derniere_fin.as_deref(), att["derniere_fin"].as_str());
}

/// Les schémas embarqués sont exactement ceux de `docs/forge/OUTILS.md` § 2, et le PC ne change
/// que des descriptions.
#[test]
fn schemas_d_outils_figes() {
    let md = std::fs::read_to_string(depot().join("docs/forge/OUTILS.md")).unwrap();
    let du_doc: Vec<Value> = md
        .lines()
        .filter(|l| l.starts_with("{\"type\":\"function\""))
        .map(|l| serde_json::from_str(l).unwrap())
        .collect();
    assert_eq!(du_doc.len(), 14);
    assert_eq!(du_doc, specs::schemas_figes());
    let pc = specs::schemas_pc(&specs::Adaptation {
        shell: "PowerShell",
        systeme: "Windows",
    });
    fn sans_descriptions(v: &Value) -> Value {
        match v {
            Value::Object(m) => Value::Object(
                m.iter()
                    .filter(|(k, _)| *k != "description")
                    .map(|(k, x)| (k.clone(), sans_descriptions(x)))
                    .collect(),
            ),
            Value::Array(a) => Value::Array(a.iter().map(sans_descriptions).collect()),
            x => x.clone(),
        }
    }
    for (a, b) in du_doc.iter().zip(&pc) {
        assert_eq!(sans_descriptions(a), sans_descriptions(b));
    }
    assert!(pc[5]["function"]["description"]
        .as_str()
        .unwrap()
        .contains("PowerShell"));
    assert!(!pc[5]["function"]["description"]
        .as_str()
        .unwrap()
        .contains("Alpine"));
    assert_eq!(specs::nom_canonique("todo_write").as_deref(), Some("TodoWrite"));
    assert_eq!(specs::nom_canonique("run_command").as_deref(), Some("Bash"));
    assert_eq!(specs::nom_canonique("inconnu"), None);
}
