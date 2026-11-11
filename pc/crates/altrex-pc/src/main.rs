//! Altrex pour PC : la fenêtre. Trois écrans (Projets, Session, Réglages) aux couleurs de Forge
//! (`docs/forge/maquettes/B-technique/tokens.md`). Toute la logique est dans `altrex-core` et
//! `session.rs` ; ce fichier ne fait que dessiner.
#![cfg_attr(all(windows, not(debug_assertions)), windows_subsystem = "windows")]

mod reglages;
mod session;

use altrex_core::coffre::Coffre;
use altrex_core::espace::{Espace, InfoProjet, InfoSession};
use altrex_core::journal::{self, Evenement};
use altrex_core::modeles::{self, FOURNISSEURS};
use altrex_core::moteur::{self, Choix, RevuePlan};
use altrex_core::permissions::Mode;
use eframe::egui::{self, Color32, FontFamily, FontId, RichText, Stroke};
use reglages::{Reglages, Source};
use session::{Attente, ReponseEcran, Session, DELAI_REPONSE};
use std::path::PathBuf;
use std::sync::Arc;

const BG: Color32 = Color32::from_rgb(0x0C, 0x15, 0x1B);
const SURFACE: Color32 = Color32::from_rgb(0x10, 0x1B, 0x22);
const SURFACE2: Color32 = Color32::from_rgb(0x15, 0x23, 0x2B);
const LINE: Color32 = Color32::from_rgb(0x1F, 0x2F, 0x38);
const LINE2: Color32 = Color32::from_rgb(0x2B, 0x3F, 0x4A);
const TEXT: Color32 = Color32::from_rgb(0xE3, 0xEB, 0xEF);
const MUTED: Color32 = Color32::from_rgb(0x90, 0xA4, 0xAF);
const ACCENT: Color32 = Color32::from_rgb(0x72, 0xD6, 0xDB);
const ON_ACCENT: Color32 = Color32::from_rgb(0x05, 0x19, 0x1B);
const OK: Color32 = Color32::from_rgb(0x5F, 0xD4, 0xA0);
const DEL: Color32 = Color32::from_rgb(0xF0, 0x8F, 0x8F);
const WARN: Color32 = Color32::from_rgb(0xE6, 0xC4, 0x6A);

// Les polices de l'appli Android (SIL OFL 1.1), reprises telles quelles.
const MANROPE: &[u8] = include_bytes!("../../../../app/src/main/res/font/manrope_medium.ttf");
const MONO: &[u8] = include_bytes!("../../../../app/src/main/res/font/jetbrains_mono_regular.ttf");

/// Un filet d'un pixel.
fn filet(couleur: Color32) -> Stroke {
    Stroke::new(1.0_f32, couleur)
}

fn habiller(ctx: &egui::Context) {
    let mut polices = egui::FontDefinitions::default();
    polices
        .font_data
        .insert("manrope".into(), egui::FontData::from_static(MANROPE));
    polices
        .font_data
        .insert("jbmono".into(), egui::FontData::from_static(MONO));
    polices
        .families
        .entry(FontFamily::Proportional)
        .or_default()
        .insert(0, "manrope".into());
    polices
        .families
        .entry(FontFamily::Monospace)
        .or_default()
        .insert(0, "jbmono".into());
    ctx.set_fonts(polices);

    let mut v = egui::Visuals::dark();
    v.panel_fill = BG;
    v.window_fill = SURFACE;
    v.extreme_bg_color = SURFACE;
    v.faint_bg_color = SURFACE2;
    v.override_text_color = Some(TEXT);
    v.hyperlink_color = ACCENT;
    v.selection.bg_fill = ACCENT.gamma_multiply(0.35);
    v.selection.stroke = filet(ACCENT);
    v.window_stroke = filet(LINE);
    for w in [
        &mut v.widgets.noninteractive,
        &mut v.widgets.inactive,
        &mut v.widgets.hovered,
        &mut v.widgets.active,
        &mut v.widgets.open,
    ] {
        w.rounding = egui::Rounding::same(6.0);
        w.bg_stroke = filet(LINE2);
    }
    v.widgets.noninteractive.bg_stroke = filet(LINE);
    v.widgets.inactive.bg_fill = SURFACE2;
    v.widgets.inactive.weak_bg_fill = SURFACE2;
    v.widgets.hovered.bg_fill = LINE2;
    v.widgets.hovered.weak_bg_fill = LINE2;
    v.widgets.hovered.bg_stroke = filet(ACCENT);
    ctx.set_visuals(v);

    let mut style = (*ctx.style()).clone();
    style.spacing.item_spacing = egui::vec2(8.0, 8.0);
    style.spacing.button_padding = egui::vec2(12.0, 6.0);
    style
        .text_styles
        .insert(egui::TextStyle::Body, FontId::new(14.0, FontFamily::Proportional));
    style.text_styles.insert(
        egui::TextStyle::Button,
        FontId::new(14.0, FontFamily::Proportional),
    );
    style.text_styles.insert(
        egui::TextStyle::Heading,
        FontId::new(19.0, FontFamily::Proportional),
    );
    style.text_styles.insert(
        egui::TextStyle::Monospace,
        FontId::new(13.0, FontFamily::Monospace),
    );
    style
        .text_styles
        .insert(egui::TextStyle::Small, FontId::new(11.0, FontFamily::Monospace));
    ctx.set_style(style);
}

/// En-tête de section : capitales en mono, comme dans la maquette.
fn section(ui: &mut egui::Ui, texte: &str) {
    ui.label(RichText::new(texte.to_uppercase()).small().color(MUTED));
}

fn carte<R>(ui: &mut egui::Ui, fond: Color32, contenu: impl FnOnce(&mut egui::Ui) -> R) -> R {
    egui::Frame::none()
        .fill(fond)
        .stroke(filet(LINE))
        .rounding(8.0)
        .inner_margin(egui::Margin::same(12.0))
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            contenu(ui)
        })
        .inner
}

/// Un petit carré d'état, dessiné (les polices embarquées n'ont pas ces symboles).
fn pastille(ui: &mut egui::Ui, couleur: Color32, plein: bool) {
    let (zone, _) = ui.allocate_exact_size(egui::vec2(10.0, 14.0), egui::Sense::hover());
    let carre = egui::Rect::from_center_size(zone.center(), egui::vec2(8.0, 8.0));
    if plein {
        ui.painter().rect_filled(carre, 2.0, couleur);
    } else {
        ui.painter().rect_stroke(carre, 2.0, filet(couleur));
    }
}

fn bouton_action(ui: &mut egui::Ui, texte: &str) -> bool {
    ui.add(egui::Button::new(RichText::new(texte).color(ON_ACCENT).strong()).fill(ACCENT))
        .clicked()
}

fn ouvrir_dans_le_navigateur(adresse: &str) {
    let mut c = if cfg!(windows) {
        let mut c = std::process::Command::new("cmd");
        c.args(["/C", "start", "", adresse]);
        c
    } else if cfg!(target_os = "macos") {
        let mut c = std::process::Command::new("open");
        c.arg(adresse);
        c
    } else {
        let mut c = std::process::Command::new("xdg-open");
        c.arg(adresse);
        c
    };
    let _ = c.spawn();
}

/// Un diff ou une sortie de commande, en mono, lignes ajoutées en vert et retirées en rouge.
fn bloc_mono(ui: &mut egui::Ui, texte: &str, diff: bool) {
    egui::Frame::none()
        .fill(BG)
        .stroke(filet(LINE))
        .rounding(4.0)
        .inner_margin(egui::Margin::same(8.0))
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.spacing_mut().item_spacing.y = 1.0;
            for ligne in texte.lines().take(400) {
                let couleur = match ligne.chars().next() {
                    Some('+') if diff && !ligne.starts_with("+++") => OK,
                    Some('-') if diff && !ligne.starts_with("---") => DEL,
                    Some('@') if diff => ACCENT,
                    _ => MUTED,
                };
                ui.label(RichText::new(ligne).monospace().color(couleur));
            }
        });
}

#[derive(PartialEq)]
enum Ecran {
    Projets,
    Session,
    Reglages,
}

struct Appli {
    dossier_reglages: PathBuf,
    reglages: Reglages,
    coffre: Coffre,
    ecran: Ecran,
    projets: Vec<InfoProjet>,
    sessions: Vec<InfoSession>,
    projet_choisi: Option<String>,
    session: Option<Session>,
    message: String,
    saisie: String,
    nouveau_projet: String,
    dossier_a_ouvrir: String,
    cle_saisie: String,
    motif_refus: String,
    reponse_libre: String,
    retour_plan: String,
    modeles_du_fournisseur: Vec<String>,
}

impl Appli {
    fn nouveau(cc: &eframe::CreationContext<'_>) -> Appli {
        habiller(&cc.egui_ctx);
        let dossier_reglages = reglages::dossier_reglages();
        let reglages = Reglages::charger(&dossier_reglages);
        let mut a = Appli {
            coffre: Coffre::nouveau(&dossier_reglages),
            dossier_reglages,
            reglages,
            ecran: Ecran::Projets,
            projets: Vec::new(),
            sessions: Vec::new(),
            projet_choisi: None,
            session: None,
            message: String::new(),
            saisie: String::new(),
            nouveau_projet: String::new(),
            dossier_a_ouvrir: String::new(),
            cle_saisie: String::new(),
            motif_refus: String::new(),
            reponse_libre: String::new(),
            retour_plan: String::new(),
            modeles_du_fournisseur: Vec::new(),
        };
        a.rafraichir();
        // « altrex-pc <dossier> » : ouvre ce dossier comme projet et reprend sa dernière session.
        if let Some(dossier) = std::env::args().nth(1) {
            match a.espace().ouvrir_dossier(std::path::Path::new(&dossier)) {
                Ok(nom) => {
                    a.projet_choisi = Some(nom.clone());
                    a.rafraichir();
                    let derniere = a.sessions.first().map(|s| s.id.clone());
                    a.ouvrir_session(&cc.egui_ctx, &nom, derniere.as_deref());
                }
                Err(e) => a.message = format!("{dossier} : {e}"),
            }
        }
        a
    }

    fn espace(&self) -> Espace {
        Espace::nouveau(&self.reglages.espace)
    }

    fn rafraichir(&mut self) {
        let e = self.espace();
        if let Err(err) = e.preparer() {
            self.message = format!("Dossier de travail inaccessible : {err}");
        }
        self.projets = e.lister();
        self.sessions = self
            .projet_choisi
            .as_ref()
            .map(|p| e.sessions(p))
            .unwrap_or_default();
    }

    fn ouvrir_session(&mut self, ctx: &egui::Context, projet: &str, id: Option<&str>) {
        self.message.clear();
        let (modele, serveur) = match session::preparer_modele(&self.reglages, &self.coffre) {
            Ok(x) => x,
            Err(e) => {
                self.message = e;
                self.ecran = Ecran::Reglages;
                return;
            }
        };
        let c = ctx.clone();
        let reveil: Arc<dyn Fn() + Send + Sync> = Arc::new(move || c.request_repaint());
        match Session::ouvrir(
            &self.espace(),
            projet,
            id,
            modele,
            serveur,
            Mode::DEFAUT,
            reveil,
            DELAI_REPONSE,
        ) {
            Ok(s) => {
                self.session = Some(s);
                self.ecran = Ecran::Session;
            }
            Err(e) => self.message = e,
        }
    }

    fn barre(&mut self, ctx: &egui::Context) {
        egui::TopBottomPanel::top("barre")
            .frame(
                egui::Frame::none()
                    .fill(SURFACE)
                    .inner_margin(egui::Margin::symmetric(16.0, 10.0)),
            )
            .show(ctx, |ui| {
                ui.horizontal(|ui| {
                    ui.label(RichText::new("ALTREX").monospace().color(ACCENT).strong());
                    ui.label(RichText::new("Forge").heading());
                    ui.add_space(16.0);
                    for (e, nom) in [
                        (Ecran::Projets, "Projets"),
                        (Ecran::Session, "Session"),
                        (Ecran::Reglages, "Réglages"),
                    ] {
                        let actif = self.ecran == e;
                        if e == Ecran::Session && self.session.is_none() {
                            continue;
                        }
                        if ui.selectable_label(actif, nom).clicked() {
                            if e == Ecran::Projets {
                                self.rafraichir();
                            }
                            self.ecran = e;
                        }
                    }
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        let modele = match self.reglages.source {
                            Source::Cloud => {
                                format!("{} · {}", self.reglages.fournisseur, self.reglages.modele_cloud)
                            }
                            Source::Llama => "llama-server (local)".to_string(),
                        };
                        ui.label(RichText::new(modele).small().color(MUTED));
                    });
                });
            });
    }

    fn ecran_projets(&mut self, ctx: &egui::Context) {
        egui::CentralPanel::default().frame(egui::Frame::none().fill(BG).inner_margin(egui::Margin::same(16.0))).show(ctx, |ui| {
            if !self.message.is_empty() {
                ui.label(RichText::new(&self.message).color(WARN));
            }
            ui.columns(2, |col| {
                let ui = &mut col[0];
                section(ui, "Projets");
                let mut a_ouvrir: Option<String> = None;
                egui::ScrollArea::vertical().id_salt("projets").auto_shrink([false, false]).max_height(ui.available_height() - 150.0).show(ui, |ui| {
                    if self.projets.is_empty() {
                        ui.label(RichText::new("Aucun projet. Crée-en un, ou ouvre un dossier de ton ordinateur.").color(MUTED));
                    }
                    for p in &self.projets {
                        let choisi = self.projet_choisi.as_deref() == Some(&p.nom);
                        let r = carte(ui, if choisi { SURFACE2 } else { SURFACE }, |ui| {
                            ui.horizontal(|ui| {
                                ui.label(RichText::new(&p.nom).strong().color(if choisi { ACCENT } else { TEXT }));
                                if !p.genre.is_empty() {
                                    ui.label(RichText::new(&p.genre).small().color(MUTED));
                                }
                                if let Some(b) = &p.branche {
                                    ui.label(RichText::new(format!("branche {b}")).small().color(MUTED));
                                }
                                if !p.approuve {
                                    ui.label(RichText::new("non approuvé").small().color(WARN));
                                }
                            });
                            ui.add(egui::Label::new(RichText::new(p.dossier.to_string_lossy()).small().color(MUTED)).truncate());
                            ui.button("Ouvrir").clicked()
                        });
                        if r {
                            a_ouvrir = Some(p.nom.clone());
                        }
                    }
                });
                if let Some(p) = a_ouvrir {
                    self.projet_choisi = Some(p);
                    self.rafraichir();
                }
                ui.separator();
                ui.horizontal(|ui| {
                    ui.add(egui::TextEdit::singleline(&mut self.nouveau_projet).hint_text("nom du projet (ex. mon-site)").desired_width(220.0));
                    if bouton_action(ui, "Créer") {
                        let e = self.espace();
                        let nom = if altrex_core::espace::verifier_nom(&self.nouveau_projet).is_none() {
                            self.nouveau_projet.trim().to_string()
                        } else {
                            e.nom_libre(&self.nouveau_projet)
                        };
                        match e.creer(&nom) {
                            Ok(n) => {
                                self.projet_choisi = Some(n);
                                self.nouveau_projet.clear();
                                self.message.clear();
                            }
                            Err(err) => self.message = err,
                        }
                        self.rafraichir();
                    }
                });
                ui.horizontal(|ui| {
                    ui.add(egui::TextEdit::singleline(&mut self.dossier_a_ouvrir).hint_text("chemin d'un dossier existant").desired_width(220.0));
                    if ui.button("Ouvrir ce dossier").clicked() {
                        match self.espace().ouvrir_dossier(std::path::Path::new(self.dossier_a_ouvrir.trim())) {
                            Ok(n) => {
                                self.projet_choisi = Some(n);
                                self.dossier_a_ouvrir.clear();
                                self.message.clear();
                            }
                            Err(err) => self.message = err,
                        }
                        self.rafraichir();
                    }
                });

                let ui = &mut col[1];
                let Some(projet) = self.projet_choisi.clone() else {
                    section(ui, "Sessions");
                    ui.label(RichText::new("Choisis un projet.").color(MUTED));
                    return;
                };
                section(ui, &format!("Sessions de {projet}"));
                if bouton_action(ui, "Nouvelle session") {
                    self.ouvrir_session(ctx, &projet, None);
                }
                if let Some(info) = self.projets.iter().find(|p| p.nom == projet).filter(|p| !p.approuve) {
                    let nom = info.nom.clone();
                    carte(ui, SURFACE, |ui| {
                        ui.label(RichText::new("Ce dossier vient de l'extérieur : ses consignes (ALTREX.md) ne sont pas lues tant que tu ne l'approuves pas.").color(WARN));
                        if ui.button("Approuver ce projet").clicked() {
                            let _ = self.espace().approuver(&nom, true);
                            self.rafraichir();
                        }
                    });
                }
                let mut a_reprendre: Option<String> = None;
                egui::ScrollArea::vertical().id_salt("sessions").auto_shrink([false, false]).show(ui, |ui| {
                    for s in &self.sessions {
                        carte(ui, SURFACE, |ui| {
                            ui.label(RichText::new(&s.titre).strong());
                            ui.horizontal(|ui| {
                                let (etat, couleur) = match s.derniere_fin.as_deref() {
                                    Some("done") => ("terminée", OK),
                                    Some("stopped") => ("arrêtée", WARN),
                                    Some("interrupted") => ("interrompue", WARN),
                                    Some("error") => ("erreur", DEL),
                                    Some(autre) => (autre, MUTED),
                                    None => ("en cours", ACCENT),
                                };
                                ui.label(RichText::new(etat).small().color(couleur));
                                if s.taches > 0 {
                                    ui.label(RichText::new(format!("{}/{} tâches", s.taches_faites, s.taches)).small().color(MUTED));
                                }
                                if ui.button("Reprendre").clicked() {
                                    a_reprendre = Some(s.id.clone());
                                }
                            });
                        });
                    }
                });
                if let Some(id) = a_reprendre {
                    self.ouvrir_session(ctx, &projet, Some(&id));
                }
            });
        });
    }

    fn ecran_reglages(&mut self, ctx: &egui::Context) {
        egui::CentralPanel::default().frame(egui::Frame::none().fill(BG).inner_margin(egui::Margin::same(16.0))).show(ctx, |ui| {
            egui::ScrollArea::vertical().auto_shrink([false, false]).show(ui, |ui| {
                ui.set_max_width(760.0);
                if !self.message.is_empty() {
                    ui.label(RichText::new(&self.message).color(WARN));
                }
                section(ui, "Modèle");
                ui.horizontal(|ui| {
                    ui.selectable_value(&mut self.reglages.source, Source::Cloud, "Modèle cloud");
                    ui.selectable_value(&mut self.reglages.source, Source::Llama, "Modèle local (llama-server)");
                });
                if self.reglages.source == Source::Cloud {
                    carte(ui, SURFACE, |ui| {
                        let avant = self.reglages.fournisseur.clone();
                        egui::ComboBox::from_id_salt("fournisseur")
                            .selected_text(modeles::fournisseur(&self.reglages.fournisseur).map(|f| f.libelle).unwrap_or("?"))
                            .show_ui(ui, |ui| {
                                for f in FOURNISSEURS {
                                    ui.selectable_value(&mut self.reglages.fournisseur, f.id.to_string(), f.libelle);
                                }
                            });
                        let Some(f) = modeles::fournisseur(&self.reglages.fournisseur) else { return };
                        if avant != self.reglages.fournisseur {
                            self.modeles_du_fournisseur.clear();
                            self.reglages.modele_cloud = f.suggestions.first().copied().unwrap_or("").to_string();
                        }
                        ui.horizontal(|ui| {
                            ui.label("Modèle");
                            ui.add(egui::TextEdit::singleline(&mut self.reglages.modele_cloud).desired_width(300.0).font(egui::TextStyle::Monospace));
                        });
                        ui.horizontal_wrapped(|ui| {
                            let propositions: Vec<String> = if self.modeles_du_fournisseur.is_empty() {
                                f.suggestions.iter().map(|s| s.to_string()).collect()
                            } else {
                                self.modeles_du_fournisseur.clone()
                            };
                            for m in propositions.iter().take(40) {
                                if ui.small_button(m).clicked() {
                                    self.reglages.modele_cloud = m.clone();
                                }
                            }
                        });
                        ui.separator();
                        let a_cle = self.coffre.a(f.id);
                        ui.label(RichText::new(if a_cle { "Clé enregistrée (chiffrée sur cet ordinateur)." } else { "Aucune clé enregistrée." }).color(if a_cle { OK } else { WARN }));
                        ui.horizontal(|ui| {
                            ui.add(egui::TextEdit::singleline(&mut self.cle_saisie).password(true).hint_text("clé d'API").desired_width(300.0));
                            if ui.button("Enregistrer la clé").clicked() {
                                self.message = if self.coffre.enregistrer(f.id, &self.cle_saisie) {
                                    "Clé enregistrée.".into()
                                } else {
                                    "La clé n'a pas pu être chiffrée : elle n'a pas été enregistrée.".into()
                                };
                                self.cle_saisie.clear();
                            }
                            if a_cle && ui.button("Supprimer").clicked() {
                                self.coffre.enregistrer(f.id, "");
                            }
                            if a_cle && ui.button("Lister les modèles").clicked() {
                                match self.coffre.lire(f.id).map(|c| modeles::lister_modeles(f, &c)) {
                                    Some(Ok(l)) => self.modeles_du_fournisseur = l,
                                    Some(Err(e)) => self.message = e.to_string(),
                                    None => {}
                                }
                            }
                        });
                        ui.hyperlink_to("Créer une clé", f.page_cle);
                    });
                } else {
                    carte(ui, SURFACE, |ui| {
                        ui.label(RichText::new("Altrex lance llama-server avec ton modèle GGUF (Argora ou autre), ou se branche sur un serveur déjà lancé.").color(MUTED));
                        let champ = |ui: &mut egui::Ui, nom: &str, valeur: &mut String, aide: &str| {
                            ui.horizontal(|ui| {
                                ui.add_sized([150.0, 20.0], egui::Label::new(nom));
                                ui.add(egui::TextEdit::singleline(valeur).hint_text(aide).desired_width(440.0).font(egui::TextStyle::Monospace));
                            });
                        };
                        champ(ui, "Serveur déjà lancé", &mut self.reglages.llama_adresse, "vide, ou http://127.0.0.1:8080");
                        champ(ui, "llama-server", &mut self.reglages.llama_binaire, "chemin du programme (vide : cherché dans le PATH)");
                        champ(ui, "Modèle GGUF", &mut self.reglages.llama_gguf, "chemin de argora-9b-…-Q5_K_M.gguf");
                        ui.horizontal(|ui| {
                            ui.add_sized([150.0, 20.0], egui::Label::new("Contexte (jetons)"));
                            ui.add(egui::DragValue::new(&mut self.reglages.llama_contexte).range(2048..=262_144).speed(512));
                            ui.label("Couches sur le GPU");
                            ui.add(egui::DragValue::new(&mut self.reglages.llama_couches_gpu).range(0..=999));
                        });
                        ui.hyperlink_to("Télécharger llama-server (versions CPU, CUDA, Vulkan)", altrex_core::llama::PAGE_TELECHARGEMENT);
                    });
                }
                section(ui, "Dossier de travail");
                let mut espace = self.reglages.espace.to_string_lossy().to_string();
                if ui.add(egui::TextEdit::singleline(&mut espace).desired_width(520.0).font(egui::TextStyle::Monospace)).changed() {
                    self.reglages.espace = PathBuf::from(espace);
                }
                ui.label(RichText::new("Les projets, les sessions et ALTREX.md y sont rangés, au même format que sur le téléphone.").small().color(MUTED));
                ui.add_space(8.0);
                if bouton_action(ui, "Enregistrer les réglages") {
                    self.message = match self.reglages.enregistrer(&self.dossier_reglages) {
                        Ok(()) => "Réglages enregistrés. Ils s'appliquent à la prochaine session ouverte.".into(),
                        Err(e) => format!("Enregistrement impossible : {e}"),
                    };
                    self.rafraichir();
                }
            });
        });
    }

    fn ecran_session(&mut self, ctx: &egui::Context) {
        let Some(s) = &self.session else { return };
        let ev = s.journal.evenements();
        let etat = journal::replier(&ev);
        let en_cours = s.en_cours();
        let attente = s.pont.en_attente();

        // Tâches et état, à droite.
        egui::SidePanel::right("taches")
            .exact_width(260.0)
            .frame(
                egui::Frame::none()
                    .fill(SURFACE)
                    .inner_margin(egui::Margin::same(12.0)),
            )
            .show(ctx, |ui| {
                section(ui, "Projet");
                ui.label(RichText::new(&s.projet).strong());
                ui.label(RichText::new(&s.nom_modele).small().color(MUTED));
                ui.label(
                    RichText::new("Pas de bac à sable : une commande approuvée agit sur cet ordinateur.")
                        .small()
                        .color(WARN),
                );
                if ui.button("Aperçu dans le navigateur").clicked() {
                    match s.apercu() {
                        Ok(a) => ouvrir_dans_le_navigateur(&a),
                        Err(e) => self.message = e,
                    }
                }
                ui.separator();
                section(ui, "Tâches");
                if etat.taches.is_empty() {
                    ui.label(RichText::new("Aucune tâche pour l'instant.").color(MUTED));
                }
                for t in &etat.taches {
                    let (plein, couleur) = match t.status.as_str() {
                        "completed" => (true, OK),
                        "in_progress" => (true, ACCENT),
                        _ => (false, MUTED),
                    };
                    ui.horizontal_wrapped(|ui| {
                        pastille(ui, couleur, plein);
                        ui.label(RichText::new(&t.content).color(if t.status == "completed" {
                            MUTED
                        } else {
                            TEXT
                        }));
                    });
                }
            });

        // Saisie, modes et Stop, en bas.
        let mut a_envoyer = false;
        egui::TopBottomPanel::bottom("saisie").frame(egui::Frame::none().fill(SURFACE).inner_margin(egui::Margin::same(12.0))).show(ctx, |ui| {
            ui.horizontal(|ui| {
                let mode = s.mode();
                for (nom, aide, m) in moteur::puces() {
                    let actif = m == mode;
                    let couleur = if actif { if nom == "Tout autoriser" { WARN } else { ACCENT } } else { MUTED };
                    if ui.add_enabled(!en_cours, egui::SelectableLabel::new(actif, RichText::new(nom).color(couleur))).on_hover_text(aide).clicked() {
                        s.moteur.changer_mode(m);
                    }
                }
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    let outil = s.outil.lock().unwrap().clone();
                    let etat_txt = if !en_cours {
                        "prêt".to_string()
                    } else if attente.is_some() {
                        "attend ta réponse".to_string()
                    } else if outil.is_empty() {
                        "réfléchit…".to_string()
                    } else {
                        format!("{outil}…")
                    };
                    ui.label(RichText::new(etat_txt).small().color(if en_cours { ACCENT } else { MUTED }));
                });
            });
            ui.horizontal(|ui| {
                let champ = ui.add(
                    egui::TextEdit::multiline(&mut self.saisie)
                        .desired_rows(2)
                        .desired_width(ui.available_width() - 110.0)
                        .hint_text("Décris ce que Forge doit faire (Entrée pour envoyer, Maj+Entrée pour une ligne)"),
                );
                let entree = champ.has_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter) && !i.modifiers.shift);
                if en_cours {
                    if ui.add(egui::Button::new(RichText::new("Arrêter").color(DEL))).clicked() {
                        s.arreter();
                    }
                } else if bouton_action(ui, "Envoyer") || entree {
                    a_envoyer = true;
                }
            });
        });
        if a_envoyer && s.envoyer(&self.saisie) {
            self.saisie.clear();
        }

        // Le fil.
        let mut reponse: Option<ReponseEcran> = None;
        egui::CentralPanel::default()
            .frame(
                egui::Frame::none()
                    .fill(BG)
                    .inner_margin(egui::Margin::same(16.0)),
            )
            .show(ctx, |ui| {
                if !self.message.is_empty() {
                    ui.label(RichText::new(&self.message).color(WARN));
                }
                egui::ScrollArea::vertical()
                    .auto_shrink([false, false])
                    .stick_to_bottom(true)
                    .show(ui, |ui| {
                        ui.set_width(ui.available_width());
                        if !etat.a_un_message {
                            ui.label(
                                RichText::new(
                                    "Nouvelle session. Dis à Forge ce que tu veux faire dans ce projet.",
                                )
                                .color(MUTED),
                            );
                        }
                        for e in &ev {
                            match e {
                                Evenement::Utilisateur { text, .. } => {
                                    carte(ui, SURFACE2, |ui| {
                                        ui.label(text);
                                    });
                                }
                                Evenement::Assistant {
                                    display, thinking, ..
                                } => {
                                    if !thinking.trim().is_empty() {
                                        egui::CollapsingHeader::new(
                                            RichText::new("Réflexion").small().color(MUTED),
                                        )
                                        .id_salt(e as *const _)
                                        .show(ui, |ui| {
                                            ui.label(RichText::new(thinking).color(MUTED));
                                        });
                                    }
                                    if !display.trim().is_empty() {
                                        ui.label(display);
                                    }
                                }
                                Evenement::ResultatOutil {
                                    id,
                                    name,
                                    status,
                                    summary,
                                    detail,
                                    path,
                                    ..
                                } => {
                                    let couleur = match status.as_str() {
                                        "ok" => OK,
                                        "refused" => WARN,
                                        _ => DEL,
                                    };
                                    let args = ev.iter().find_map(|x| match x {
                                        Evenement::AppelOutil { id: i, args, .. } if i == id => {
                                            serde_json::from_str::<serde_json::Value>(args).ok()
                                        }
                                        _ => None,
                                    });
                                    let cible = path.clone().or_else(|| {
                                        args.as_ref().and_then(|a| {
                                            ["command", "pattern", "file_path", "question", "job_id"]
                                                .iter()
                                                .find_map(|k| a[*k].as_str().map(str::to_string))
                                        })
                                    });
                                    let titre = format!(
                                        "{name}  {}",
                                        cible
                                            .unwrap_or_default()
                                            .lines()
                                            .next()
                                            .unwrap_or("")
                                            .chars()
                                            .take(90)
                                            .collect::<String>()
                                    );
                                    carte(ui, SURFACE, |ui| {
                                        ui.horizontal(|ui| {
                                            pastille(ui, couleur, true);
                                            ui.label(RichText::new(titre).monospace());
                                            ui.label(RichText::new(summary).small().color(MUTED));
                                        });
                                        if !detail.trim().is_empty() {
                                            egui::CollapsingHeader::new(
                                                RichText::new("Détail").small().color(MUTED),
                                            )
                                            .id_salt(("detail", id))
                                            .show(ui, |ui| {
                                                bloc_mono(
                                                    ui,
                                                    detail,
                                                    matches!(name.as_str(), "Write" | "Edit" | "MultiEdit"),
                                                );
                                            });
                                        }
                                    });
                                }
                                Evenement::Plan { markdown, .. } => {
                                    carte(ui, SURFACE, |ui| {
                                        section(ui, "Plan proposé");
                                        ui.label(markdown);
                                    });
                                }
                                Evenement::DecisionPlan {
                                    approved, feedback, ..
                                } => {
                                    let t = if *approved {
                                        "Plan validé.".to_string()
                                    } else if feedback.is_empty() {
                                        "Plan refusé.".to_string()
                                    } else {
                                        format!("Plan à modifier : {feedback}")
                                    };
                                    ui.label(RichText::new(t).small().color(if *approved {
                                        OK
                                    } else {
                                        WARN
                                    }));
                                }
                                Evenement::Question { question, .. } => {
                                    ui.label(RichText::new(format!("Question : {question}")).color(ACCENT));
                                }
                                Evenement::Reponse { answer, .. } => {
                                    ui.label(RichText::new(format!("Ta réponse : {answer}")).color(MUTED));
                                }
                                Evenement::Note {
                                    text, shown: true, ..
                                } => {
                                    ui.label(RichText::new(text).small().color(WARN));
                                }
                                Evenement::FinTour { reason, detail, .. } if reason != "done" => {
                                    let t = match reason.as_str() {
                                        "stopped" => "Tour arrêté.".to_string(),
                                        "limit" => "Limite du tour atteinte.".to_string(),
                                        "interrupted" => {
                                            "Tour interrompu (l'appli a été fermée).".to_string()
                                        }
                                        "blocked" => format!("Tour bloqué : {detail}"),
                                        _ => format!("Erreur : {detail}"),
                                    };
                                    ui.label(RichText::new(t).small().color(if reason == "error" {
                                        DEL
                                    } else {
                                        WARN
                                    }));
                                }
                                _ => {}
                            }
                        }
                        let direct = s.direct.lock().unwrap().clone();
                        let direct = altrex_core::appels::texte_affiche(&direct);
                        if en_cours && !direct.is_empty() {
                            ui.label(RichText::new(direct).color(TEXT));
                        }

                        // Ce que Forge attend de l'utilisateur.
                        match &attente {
                            Some(Attente::Accord(d)) => {
                                carte(ui, SURFACE2, |ui| {
                                    ui.label(RichText::new(&d.titre).strong().color(if d.risque {
                                        WARN
                                    } else {
                                        ACCENT
                                    }));
                                    if d.escalade {
                                        ui.label(
                                            RichText::new("Forge redemande une action que tu as refusée.")
                                                .small()
                                                .color(WARN),
                                        );
                                    }
                                    if let Some(j) = &d.justification {
                                        ui.label(RichText::new(format!("Sa raison : {j}")).color(MUTED));
                                    }
                                    bloc_mono(ui, &d.detail, d.est_diff);
                                    ui.horizontal(|ui| {
                                        if bouton_action(ui, "Oui") {
                                            reponse = Some(ReponseEcran::Accord(Choix::Oui, String::new()));
                                        }
                                        if let Some(l) = &d.libelle_toujours {
                                            if ui.button(format!("Toujours pour {l}")).clicked() {
                                                reponse = Some(ReponseEcran::Accord(
                                                    Choix::Toujours,
                                                    String::new(),
                                                ));
                                            }
                                        }
                                        if ui.button("Non").clicked() {
                                            reponse = Some(ReponseEcran::Accord(
                                                Choix::Non,
                                                std::mem::take(&mut self.motif_refus),
                                            ));
                                        }
                                        if ui
                                            .add(egui::Button::new(RichText::new("Arrêter").color(DEL)))
                                            .clicked()
                                        {
                                            reponse =
                                                Some(ReponseEcran::Accord(Choix::Arreter, String::new()));
                                        }
                                    });
                                    ui.add(
                                        egui::TextEdit::singleline(&mut self.motif_refus)
                                            .hint_text("pourquoi non (facultatif, dit à Forge)")
                                            .desired_width(420.0),
                                    );
                                });
                            }
                            Some(Attente::Question { question, options }) => {
                                carte(ui, SURFACE2, |ui| {
                                    ui.label(RichText::new(question).strong().color(ACCENT));
                                    ui.horizontal_wrapped(|ui| {
                                        for o in options {
                                            if ui.button(o).clicked() {
                                                reponse = Some(ReponseEcran::Question(Some(o.clone())));
                                            }
                                        }
                                    });
                                    ui.horizontal(|ui| {
                                        ui.add(
                                            egui::TextEdit::singleline(&mut self.reponse_libre)
                                                .hint_text("autre réponse")
                                                .desired_width(320.0),
                                        );
                                        if ui.button("Répondre").clicked()
                                            && !self.reponse_libre.trim().is_empty()
                                        {
                                            reponse = Some(ReponseEcran::Question(Some(std::mem::take(
                                                &mut self.reponse_libre,
                                            ))));
                                        }
                                        if ui.button("Passer").clicked() {
                                            reponse = Some(ReponseEcran::Question(None));
                                        }
                                    });
                                });
                            }
                            Some(Attente::Plan { .. }) => {
                                carte(ui, SURFACE2, |ui| {
                                    ui.label(RichText::new("Valider ce plan ?").strong().color(ACCENT));
                                    ui.horizontal_wrapped(|ui| {
                                        for (nom, _, m) in
                                            moteur::puces().into_iter().filter(|p| !p.2.est_plan())
                                        {
                                            if bouton_action(ui, &format!("Valider · {nom}")) {
                                                reponse = Some(ReponseEcran::Plan(RevuePlan::Valide(m)));
                                            }
                                        }
                                        if ui.button("Refuser").clicked() {
                                            reponse = Some(ReponseEcran::Plan(RevuePlan::Refuse));
                                        }
                                    });
                                    ui.horizontal(|ui| {
                                        ui.add(
                                            egui::TextEdit::singleline(&mut self.retour_plan)
                                                .hint_text("ce qu'il faut changer au plan")
                                                .desired_width(420.0),
                                        );
                                        if ui.button("Demander des changements").clicked()
                                            && !self.retour_plan.trim().is_empty()
                                        {
                                            reponse = Some(ReponseEcran::Plan(RevuePlan::AModifier(
                                                std::mem::take(&mut self.retour_plan),
                                            )));
                                        }
                                    });
                                });
                            }
                            None => {}
                        }
                    });
            });
        if let Some(r) = reponse {
            s.pont.repondre(r);
        }
    }
}

impl eframe::App for Appli {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        self.barre(ctx);
        match self.ecran {
            Ecran::Projets => self.ecran_projets(ctx),
            Ecran::Reglages => self.ecran_reglages(ctx),
            Ecran::Session => self.ecran_session(ctx),
        }
    }
}

fn main() -> eframe::Result<()> {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title("Altrex")
            .with_inner_size([1180.0, 780.0])
            .with_min_inner_size([820.0, 520.0]),
        ..Default::default()
    };
    eframe::run_native("Altrex", options, Box::new(|cc| Ok(Box::new(Appli::nouveau(cc)))))
}
