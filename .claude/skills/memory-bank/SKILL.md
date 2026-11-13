---
name: memory-bank
description: Tient la mémoire d'un projet dans un fichier `brief.md` à la racine du dépôt (objectif, stack, architecture, roadmap, journal des décisions) pour reprendre le travail sans perte de contexte entre sessions ou entre développeurs (humains ou IA). À utiliser au démarrage d'un nouveau projet, à chaque reprise de session, et après tout changement important (fonctionnalité, bug majeur, structure, changement de techno). Déclencheurs - « brief.md », « memory bank », « memory-bank.md », « mémoire du projet », « reprendre le projet », « où en est-on », « mets à jour le brief », « consigne cette décision », « project brief », « resume the project », « update the brief », « handoff ».
---

# Memory Bank — gestionnaire de mémoire de projet

Tu es la « seconde mémoire » technique du projet. Le fichier `brief.md` (ou `memory-bank.md` s'il existe déjà) à la racine du dépôt doit permettre à quelqu'un qui n'a rien suivi de reprendre le travail en deux minutes de lecture.

## Règles fondamentales

1. **Reprise de session : lire d'abord.** Avant toute action sur un projet, lis `brief.md` (et `CLAUDE.md` s'il existe). Commence par « Prochaine action immédiate » et « Pièges ». Si le brief contredit l'état réel du dépôt, c'est le dépôt qui a raison : corrige le brief.
2. **Création systématique.** Dès la première requête qui lance un projet, crée `brief.md` à partir de `references/brief-template.md`. S'il n'existe pas sur un projet déjà commencé, crée-le à partir de ce que montre le dépôt.
3. **Mise à jour continue.** Après chaque changement important (fonctionnalité, bug majeur corrigé, structure, techno, décision), mets le brief à jour dans le même lot de travail que le code (même commit si possible). Annonce-le en une phrase : « Je mets à jour le `brief.md`. »
4. **Le pourquoi, pas seulement le comment.** Toute décision est consignée avec : la décision, sa raison, les alternatives écartées (et pourquoi). Une décision en attente de validation est marquée comme telle.

## Structure exigée de `brief.md`

Toujours ces cinq sections, dans cet ordre (modèle complet : `references/brief-template.md`) :

1. **Aperçu du projet** — nom, objectif principal (1-2 phrases), état actuel (En conception / MVP en cours / En production…), **prochaine action immédiate**.
2. **Stack technologique** — langages, frameworks et bibliothèques (versions si connues), outils (base de données, déploiement…).
3. **Architecture et structure** — arbre des dossiers principaux, choix architecturaux, **pièges** (ce qui a déjà fait perdre du temps, contraintes non évidentes, règles à ne pas enfreindre).
4. **Roadmap et plan d'action** — Terminé `[x]` / En cours `[ ]` / À faire `[ ]`.
5. **Journal de bord et décisions** — entrées datées (les plus récentes en haut) + problèmes connus / bugs à corriger.

## Règles de rédaction

- **Court et dense.** Des puces, pas de roman. Vise moins de 150 lignes ; au-delà, condense les vieilles entrées du journal en une ligne par jalon. Supprime ce qui est périmé au lieu d'empiler.
- **Dates absolues** au format `AAAA-MM-JJ`. Jamais « hier », « la semaine dernière », « récemment ».
- **Aucun secret.** Jamais de clé API, jeton, mot de passe ni donnée personnelle dans le brief : note seulement où se trouve le secret (nom de variable d'environnement, coffre).
- **Ne pas dupliquer `CLAUDE.md`** ni le README : y renvoyer (« Règles : voir `CLAUDE.md` §4 »). Le brief porte l'état et les décisions ; `CLAUDE.md` porte les consignes permanentes.
- **Vérifiable.** N'écris « terminé » que pour ce qui a été réellement testé ; sinon précise « écrit, non testé ». Distingue les faits des hypothèses.
- **Langue du projet.** Rédige dans la langue utilisée par le propriétaire du projet.

## Comportements attendus

- **Nouvelle fonctionnalité demandée** → code-la, puis mets à jour « Roadmap », « Journal » et « Prochaine action immédiate ».
- **Changement d'avis sur une techno** → modifie le code, mets à jour « Stack technologique », consigne la raison et l'alternative abandonnée dans le « Journal ».
- **Obstacle ou bug non résolu** → ajoute-le aux « Problèmes connus » avec ce qui a déjà été essayé.
- **Fin de session ou passation** → vérifie que « Prochaine action immédiate » dit exactement par où reprendre.
- **Changement mineur** (faute de frappe, renommage local) → pas de mise à jour du brief.
