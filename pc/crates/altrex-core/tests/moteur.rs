//! Le moteur de bout en bout, avec un faux modèle et un faux écran : outils, autorisations, mode
//! Plan, rappels, journal.

#![cfg_attr(windows, allow(dead_code, unused_imports))]
mod commun;

use altrex_core::commandes::Dialecte;
use altrex_core::journal::{self, Evenement, Journal};
use altrex_core::memoire::Environnement;
use altrex_core::moteur::{Choix, Moteur, RevuePlan, Signal};
use altrex_core::permissions::{Approbation, Bac, Mode};
use altrex_core::shell::Shell;
use commun::{dossier, FauxEcran, FauxModele};
use serde_json::Value;
use std::fs;
use std::path::Path;
use std::sync::{Arc, Mutex};

fn appel(nom: &str, args: &str) -> String {
    format!("<tool_call>\n{{\"name\": \"{nom}\", \"arguments\": {args}}}\n</tool_call>")
}

fn moteur(projet: &Path, modele: Arc<FauxModele>, ecran: Arc<FauxEcran>, mode: Mode) -> Moteur {
    let jr = Arc::new(Journal::nouveau(dossier("sessions").join("s1.jsonl")));
    jr.ajouter(Evenement::Session {
        id: "s1".into(),
        project: "p".into(),
        model: "faux".into(),
        mode: mode.cle(),
        title: String::new(),
    })
    .unwrap();
    let env = Environnement {
        systeme: "Linux".into(),
        shell: "sh".into(),
        dialecte: Dialecte::Posix,
    };
    let shell = Arc::new(Shell::nouveau(projet, Dialecte::Posix));
    Moteur::nouveau(
        jr,
        modele,
        projet,
        shell,
        ecran,
        "Tu es Forge.".into(),
        env,
        Box::new(|_| {}),
    )
}

fn resultats(m: &Moteur) -> Vec<(String, String, Value)> {
    m.journal
        .evenements()
        .into_iter()
        .filter_map(|e| match e {
            Evenement::ResultatOutil {
                name, status, model, ..
            } => Some((name, status, serde_json::from_str(&model).unwrap())),
            _ => None,
        })
        .collect()
}

const DEMANDER: Mode = Mode::DEFAUT;
const AUTO: Mode = Mode {
    bac: Bac::Projet,
    approbation: Approbation::Jamais,
};
const TOTAL: Mode = Mode {
    bac: Bac::Total,
    approbation: Approbation::Jamais,
};

#[test]
fn lire_puis_modifier_avec_accord() {
    let p = dossier("moteur");
    fs::write(p.join("a.txt"), "bonjour\n").unwrap();
    let modele = FauxModele::textes(&[
        &format!("Je lis.\n{}", appel("Read", r#"{"file_path": "a.txt"}"#)),
        &appel(
            "edit_file",
            r#"{"path": "a.txt", "old_string": "bonjour", "new_string": "salut"}"#,
        ),
        "C'est fait.",
    ]);
    let ecran = FauxEcran::nouveau(Choix::Oui);
    let m = moteur(&p, modele.clone(), ecran.clone(), DEMANDER);
    assert_eq!(m.tour("Remplace bonjour par salut"), "done");
    assert_eq!(fs::read_to_string(p.join("a.txt")).unwrap(), "salut\n");

    let r = resultats(&m);
    assert_eq!(r[0].0, "Read");
    assert_eq!(r[1].0, "Edit", "edit_file et path sont des alias tolérés");
    assert_eq!(r[1].2["replacements"], 1);
    let d = ecran.demandes.lock().unwrap();
    assert_eq!(d.len(), 1, "la lecture ne demande rien, la modification oui");
    assert_eq!(d[0].titre, "Forge veut modifier a.txt");
    assert!(d[0].est_diff && d[0].detail.contains("+salut"));
    assert_eq!(d[0].libelle_toujours.as_deref(), Some("les fichiers"));
    // Le modèle a reçu le résultat au format <tool_response>.
    assert!(modele
        .dernier_recu()
        .starts_with("<tool_response>\n{\"file\":\"a.txt\",\"replacements\":1"));
    // Le journal garde l'accord et se relit.
    let ev = m.journal.evenements();
    assert!(ev.iter().any(
        |e| matches!(e, Evenement::Accord { decision, key, .. } if decision == "oui" && key == "write:a.txt")
    ));
    assert!(matches!(ev.last(), Some(Evenement::FinTour { reason, .. }) if reason == "done"));
    let relu = Journal::charger(&m.journal.fichier).unwrap();
    assert_eq!(relu.evenements(), ev);
}

#[test]
fn refus_puis_toujours() {
    let p = dossier("moteur");
    let modele = FauxModele::textes(&[
        &appel("Write", r#"{"file_path": "n.txt", "content": "x"}"#),
        &appel("Write", r#"{"file_path": "n.txt", "content": "x"}"#),
        "Je m'arrête.",
        &appel("Write", r#"{"file_path": "o.txt", "content": "y"}"#),
        &appel("Write", r#"{"file_path": "q.txt", "content": "z"}"#),
        "Fini.",
    ]);
    let ecran = FauxEcran::nouveau(Choix::Non);
    let m = moteur(&p, modele, ecran.clone(), DEMANDER);
    m.tour("Crée n.txt");
    let r = resultats(&m);
    assert_eq!(r[0].1, "refused");
    assert_eq!(r[0].2["refused"], "L'utilisateur a refusé.");
    assert!(r[1].2["refused"]
        .as_str()
        .unwrap()
        .starts_with("L'utilisateur a déjà refusé cette action"));
    assert_eq!(
        ecran.demandes.lock().unwrap().len(),
        1,
        "une action refusée n'est pas redemandée"
    );
    assert!(!p.join("n.txt").exists());

    // « Toujours » pour les fichiers : la deuxième écriture ne demande plus rien.
    *ecran.choix.lock().unwrap() = Choix::Toujours;
    m.tour("Crée o.txt et q.txt");
    assert_eq!(ecran.demandes.lock().unwrap().len(), 2);
    assert!(p.join("o.txt").exists() && p.join("q.txt").exists());
    assert!(journal::replier(&m.journal.evenements()).accords.fichiers);
}

#[test]
fn arreter_finit_le_tour() {
    let p = dossier("moteur");
    let modele = FauxModele::textes(&[&appel("Bash", r#"{"command": "touch x"}"#), "jamais atteint"]);
    let m = moteur(&p, modele.clone(), FauxEcran::nouveau(Choix::Arreter), DEMANDER);
    assert_eq!(m.tour("go"), "stopped");
    assert_eq!(
        resultats(&m)[0].2["refused"],
        "L'utilisateur a refusé et arrêté le tour."
    );
    assert_eq!(modele.reponses.lock().unwrap().len(), 1);
    assert!(!p.join("x").exists());
}

// Lance de vraies commandes sh.
#[cfg(unix)]
#[test]
fn commandes_lecture_libre_ecriture_demandee() {
    let p = dossier("moteur");
    fs::write(p.join("a.txt"), "un\n").unwrap();
    let modele = FauxModele::textes(&[
        &appel("Bash", r#"{"command": "cat a.txt"}"#),
        &appel("Bash", r#"{"cmd": "mkdir sous && cd sous && echo fait > b.txt"}"#),
        &appel("Bash", r#"{"command": "pwd"}"#),
        &appel("Bash", r#"{"command": "exit 3"}"#),
        "Fini.",
    ]);
    let ecran = FauxEcran::nouveau(Choix::Oui);
    let m = moteur(&p, modele, ecran.clone(), AUTO);
    assert_eq!(m.tour("go"), "done");
    let r = resultats(&m);
    assert_eq!(r[0].2["output"], "un");
    assert_eq!(r[0].2["exit_code"], 0);
    assert_eq!(fs::read_to_string(p.join("sous/b.txt")).unwrap(), "fait\n");
    assert!(
        r[2].2["output"].as_str().unwrap().ends_with("/sous"),
        "le dossier courant est gardé d'une commande à l'autre"
    );
    assert_eq!(
        (r[3].1.as_str(), &r[3].2["exit_code"]),
        ("error", &serde_json::json!(3))
    );
    let d = ecran.demandes.lock().unwrap();
    assert_eq!(
        d.len(),
        2,
        "cat et pwd passent seuls ; mkdir… et exit sont demandés même en « Éditions auto »"
    );
    assert_eq!(d[0].titre, "Forge veut lancer une commande");
    assert!(!d[0].est_diff);
}

// Lance de vraies commandes sh.
#[cfg(unix)]
#[test]
fn tout_autoriser_demande_quand_meme_le_risque() {
    let p = dossier("moteur");
    let modele = FauxModele::textes(&[
        &appel("Write", r#"{"file_path": "a.txt", "content": "x"}"#),
        &appel("Bash", r#"{"command": "echo ok > b.txt"}"#),
        &appel("Bash", r#"{"command": "sudo rm -rf /"}"#),
        &appel("Write", r#"{"file_path": ".git/config", "content": "x"}"#),
        "Fini.",
    ]);
    let ecran = FauxEcran::nouveau(Choix::Non);
    let m = moteur(&p, modele, ecran.clone(), TOTAL);
    m.tour("go");
    assert!(p.join("a.txt").exists() && p.join("b.txt").exists());
    let d = ecran.demandes.lock().unwrap();
    assert_eq!(d.len(), 2);
    assert!(d[0].risque && d[0].titre == "Commande à risque" && d[0].libelle_toujours.is_none());
    assert!(d[1].risque && d[1].titre.contains("dossier protégé"));
    assert!(!p.join(".git/config").exists());
}

// Lance de vraies commandes sh.
#[cfg(unix)]
#[test]
fn mode_plan_lecture_seule_puis_validation() {
    let p = dossier("moteur");
    fs::write(p.join("a.txt"), "un\n").unwrap();
    let modele = FauxModele::textes(&[
        &appel("Write", r#"{"file_path": "a.txt", "content": "x"}"#),
        &appel("Bash", r#"{"command": "rm a.txt"}"#),
        &appel("Bash", r#"{"command": "ls"}"#),
        &appel(
            "Write",
            r#"{"file_path": "blueprint/idee.md", "content": "notes"}"#,
        ),
        &appel("ExitPlanMode", r##"{"plan": "# Refonte du site\n1. Faire"}"##),
        &appel("Write", r#"{"file_path": "c.txt", "content": "après le plan"}"#),
        "Plan réalisé.",
    ]);
    let ecran = FauxEcran::nouveau(Choix::Oui);
    *ecran.plan.lock().unwrap() = RevuePlan::Valide(AUTO);
    let m = moteur(&p, modele.clone(), ecran.clone(), Mode::PLAN);
    assert_eq!(m.tour("Prépare la refonte"), "done");
    let r = resultats(&m);
    let plan = "Mode Plan : lecture seule. Tu peux seulement écrire dans blueprint/ ou .altrex/plans/. Termine ton plan et présente-le avec ExitPlanMode ; l'utilisateur le validera.";
    assert_eq!(r[0].2["refused"], plan);
    assert_eq!(r[1].2["refused"], plan);
    assert_eq!(r[2].1, "ok", "une commande de lecture passe en mode Plan");
    assert_eq!(r[3].1, "ok", "blueprint/ est modifiable en mode Plan");
    assert_eq!(fs::read_to_string(p.join("a.txt")).unwrap(), "un\n");
    let texte = r[4].2["result"].as_str().unwrap();
    assert!(
        texte.starts_with("Plan validé par l'utilisateur, enregistré dans .altrex/plans/"),
        "{texte}"
    );
    assert!(texte.contains("-refonte-du-site.md. Mode : Éditions auto."));
    assert_eq!(r[5].1, "ok", "après validation, le mode choisi s'applique");
    assert!(p.join("c.txt").exists());
    assert_eq!(m.mode(), AUTO);
    assert!(ecran.demandes.lock().unwrap().is_empty());
    let plans: Vec<_> = fs::read_dir(p.join(".altrex/plans")).unwrap().flatten().collect();
    assert_eq!(plans.len(), 1);
    // Le rappel du mode Plan accompagne la demande.
    let premier = &modele.recus.lock().unwrap()[0];
    assert!(premier[0]
        .texte_complet()
        .contains("<rappel-forge>Mode Plan : lecture seule."));
}

#[test]
fn plan_a_modifier_puis_refuse() {
    let p = dossier("moteur");
    let modele = FauxModele::textes(&[
        &appel("ExitPlanMode", r##"{"plan": "# P"}"##),
        "D'accord, j'ajuste.",
        &appel("ExitPlanMode", r##"{"plan": "# P2"}"##),
        "jamais atteint",
    ]);
    let ecran = FauxEcran::nouveau(Choix::Oui);
    *ecran.plan.lock().unwrap() = RevuePlan::AModifier("plus court".into());
    let m = moteur(&p, modele.clone(), ecran.clone(), Mode::PLAN);
    m.tour("plan");
    assert_eq!(
        resultats(&m)[0].2["result"],
        "L'utilisateur demande des changements au plan : plus court. Reste en mode Plan, ajuste le plan, puis présente-le de nouveau avec ExitPlanMode."
    );
    assert!(m.mode().est_plan());
    *ecran.plan.lock().unwrap() = RevuePlan::Refuse;
    m.tour("encore");
    assert_eq!(
        resultats(&m)[1].2["refused"],
        "L'utilisateur a refusé le plan. Arrête-toi et attends sa prochaine demande."
    );
    assert_eq!(
        modele.reponses.lock().unwrap().len(),
        1,
        "un plan refusé finit le tour"
    );

    // Hors du mode Plan, ExitPlanMode ne fait rien.
    let m2 = moteur(
        &p,
        FauxModele::textes(&[&appel("ExitPlanMode", r##"{"plan": "# P"}"##), "ok"]),
        ecran,
        DEMANDER,
    );
    m2.tour("x");
    assert_eq!(
        resultats(&m2)[0].2["result"],
        "ExitPlanMode ne sert qu'en mode Plan. Tu n'y es pas : continue ton travail normalement."
    );
}

#[test]
fn taches_question_et_rappels() {
    let p = dossier("moteur");
    let modele = FauxModele::textes(&[
        &appel(
            "todo_write",
            r#"{"todos": "[{\"content\": \"Écrire\", \"status\": \"in_progress\"}, {\"content\": \"Tester\", \"status\": \"pending\"}]"}"#,
        ),
        &appel(
            "AskUserQuestion",
            r#"{"question": "React ou Vue ?", "options": ["React", "Vue"]}"#,
        ),
        "Voilà.",
        "",
        "Il reste à tester.",
    ]);
    let m = moteur(&p, modele.clone(), FauxEcran::nouveau(Choix::Oui), DEMANDER);
    assert_eq!(m.tour("go"), "done");
    let r = resultats(&m);
    assert_eq!(r[0].2["todos"], "0/2 terminées");
    assert_eq!(r[1].2["answer"], "React");
    let recus = modele.recus.lock().unwrap();
    // Fin de tour avec des tâches non terminées : un rappel, une seule fois.
    assert!(recus[3].last().unwrap().texte_complet().contains(
        "<rappel-forge>Ta liste de tâches n'est pas à jour : 2 tâche(s) non terminée(s) (« Écrire », « Tester »)."
    ));
    // Réponse vide : un rappel, une seule fois.
    assert!(recus[4]
        .last()
        .unwrap()
        .texte_complet()
        .contains("Tu n'as rien écrit pour l'utilisateur."));
    assert_eq!(recus.len(), 5);
    assert_eq!(journal::replier(&m.journal.evenements()).taches.len(), 2);
}

#[test]
fn appels_illisibles_inconnus_et_limite_de_quatre() {
    let p = dossier("moteur");
    fs::write(p.join("a.txt"), "x\n").unwrap();
    let cinq: String = (0..5)
        .map(|_| appel("Read", r#"{"file_path": "a.txt"}"#))
        .collect::<Vec<_>>()
        .join("\n");
    let modele = FauxModele::textes(&[
        "<tool_call>\nRead a.txt\n</tool_call>",
        &appel("Voler", "{}"),
        &appel("WebSearch", r#"{"query": "x"}"#),
        &cinq,
        "Fini.",
    ]);
    let m = moteur(&p, modele, FauxEcran::nouveau(Choix::Oui), DEMANDER);
    m.tour("go");
    let r = resultats(&m);
    assert!(r[0].2["error"]
        .as_str()
        .unwrap()
        .starts_with("Appel d'outil illisible (JSON invalide)."));
    assert!(r[1].2["error"].as_str().unwrap().starts_with(
        "L'outil « Voler » n'existe pas. Outils disponibles : Read, Write, Edit, Glob, Grep, Bash,"
    ));
    assert!(r[2].2["error"]
        .as_str()
        .unwrap()
        .contains("pas encore disponible dans la version PC"));
    assert_eq!(r.len(), 3 + 4, "4 appels au plus par réponse");
}

#[test]
fn conflit_pendant_l_accord_et_modele_qui_boucle() {
    let p = dossier("moteur");
    fs::write(p.join("a.txt"), "un\n").unwrap();
    let modele = FauxModele::textes(&[
        &appel("Read", r#"{"file_path": "a.txt"}"#),
        &appel(
            "Edit",
            r#"{"file_path": "a.txt", "old_string": "un", "new_string": "deux"}"#,
        ),
        "Fini.",
    ]);
    let ecran = FauxEcran::nouveau(Choix::Oui);
    let f = p.join("a.txt");
    *ecran.pendant.lock().unwrap() = Some(Box::new(move || {
        fs::write(&f, "changé par l'utilisateur\n").unwrap()
    }));
    let m = moteur(&p, modele, ecran, DEMANDER);
    m.tour("go");
    assert!(resultats(&m)[1].2["error"]
        .as_str()
        .unwrap()
        .starts_with("Conflit : a.txt a été modifié en dehors de Forge"));
    assert_eq!(
        fs::read_to_string(p.join("a.txt")).unwrap(),
        "changé par l'utilisateur\n",
        "rien n'est écrasé"
    );

    let boucle: Vec<String> = (0..10)
        .map(|_| appel("Glob", r#"{"pattern": "*.rien"}"#))
        .collect();
    let refs: Vec<&str> = boucle.iter().map(|s| s.as_str()).collect();
    let m = moteur(
        &p,
        FauxModele::textes(&refs),
        FauxEcran::nouveau(Choix::Oui),
        DEMANDER,
    );
    assert_eq!(m.tour("go"), "blocked");
}

#[test]
fn erreur_du_modele_arret_et_signaux() {
    use altrex_core::modeles::Erreur;
    let p = dossier("moteur");
    let modele = FauxModele::textes(&[]);
    modele.reponses.lock().unwrap().push(Err(Erreur::Http {
        code: 401,
        message: "clé invalide".into(),
    }));
    let m = moteur(&p, modele, FauxEcran::nouveau(Choix::Oui), DEMANDER);
    assert_eq!(m.tour("go"), "error");
    assert!(
        matches!(m.journal.evenements().last(), Some(Evenement::FinTour { detail, .. }) if detail == "Erreur 401 : clé invalide")
    );

    let modele = FauxModele::textes(&[]);
    modele.reponses.lock().unwrap().push(Err(Erreur::Arrete));
    let mut m = moteur(&p, modele, FauxEcran::nouveau(Choix::Oui), DEMANDER);
    let vus = Arc::new(Mutex::new(0usize));
    let v = vus.clone();
    m.signal = Box::new(move |s| {
        if s == Signal::Journal {
            *v.lock().unwrap() += 1;
        }
    });
    assert_eq!(m.tour("go"), "stopped");
    assert!(*vus.lock().unwrap() >= 3);

    // Changer de mode entre deux tours : le modèle en est averti.
    let modele = FauxModele::textes(&["un", "deux"]);
    let m = moteur(&p, modele.clone(), FauxEcran::nouveau(Choix::Oui), DEMANDER);
    m.tour("premier");
    m.changer_mode(TOTAL);
    m.tour("second");
    assert!(modele.dernier_recu().contains(
        "<rappel-forge>Mode : Tout autoriser (bac à sable : accès total ; approbation : jamais).</rappel-forge>"
    ));
}
