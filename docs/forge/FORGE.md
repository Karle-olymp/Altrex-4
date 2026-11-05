# Forge : cahier des charges (version 1)

Forge est le mode de programmation d'Altrex. Il remplace l'ancien écran Code. Version 1 du 2026-11-05, remplace `docs/code/ALTREX_CODE.md`. Document de travail : tu relis, tu corriges, puis on construit par étapes.

## 1. Le principe

| | **Discuter** | **Forge** |
|---|---|---|
| Pour quoi | Le quotidien : questions, notes, documents, recherche, rappels, connecteurs | Concevoir et construire : une appli, un site, un système ; corriger, tester, améliorer |
| Comparable à | Claude (l'appli de discussion) | Claude Code, avec ton propre look et tes propres modes |
| Façon de travailler | Conversation, réponses courtes | Un agent qui lit, écrit, lance, vérifie, dans un projet, étape par étape |
| Espace de travail | Aucun | Un projet, dans le Linux d'Altrex |

**Décisions prises :**
- **Nom (décidé le 2026-11-05) :** le nom complet est **Altrex Forge** ; « Forge » n'est que le diminutif, utilisé dans l'appli là où le contexte est clair (entrée du menu, écrans). Toute communication publique dit « Altrex Forge ». Le mot « Forge » seul est déjà utilisé ailleurs (Laravel Forge, Atlassian Forge, Minecraft Forge, un agent de code nommé Forge, une maquette « LocalForge ») : d'où le nom complet. **Vérification de marque à faire avant toute publication**, comme pour le logo.
- **Projets :** dans le Linux d'Altrex, dans un dossier commun avec ton Terminal.
- **Ancien Code :** remplacé, en gardant l'aperçu des pages web et le scan de sécurité.
- **Noms des outils :** ceux de Claude Code, dans Forge seulement (voir 3). Discuter garde les siens.
- **Sous-agents :** à l'étape 4, mais le moteur est préparé dès l'étape 1 (voir 4).
- **Le look :** le tien, pas celui de Claude Code (voir 8).

## 2. Règle d'origine : on s'inspire, on ne copie pas

Forge est une œuvre originale d'Altrex. Les prompts, les descriptions d'outils, les formats de hooks et les limites sont écrits par nous.
- **OpenClaude : exclu.** Son propre fichier de licence reconnaît que son code est dérivé du code propriétaire de Claude Code, sans autorisation de distribution. On ne l'utilise pas, on ne s'en inspire pas.
- **DeepSeek Harness (MIT)** : idées seulement, aucun code ni prompt. On ne reprend ni son nom ni ses visuels (marque déposée), ni son paquet qui installe le binaire propriétaire de Claude Code, ni son pont de hooks au format Claude Code. Forge aura son propre format de hooks.
- **AutoGen** (code MIT, documentation CC-BY-4.0) et **Agent-Reach** (MIT) : idées et patrons de conception, sans copie ; les prompts sont reformulés.
- **local-ai-runner** : aucune licence, donc rien à copier.
- Les noms d'outils de Claude Code sont des conventions d'interface publiques : on les adopte, avec notre propre implémentation et nos propres descriptions.

## 3. Les outils de Forge

| Outil | Rôle | Vient d'Altrex ou à faire |
|---|---|---|
| `Read` | Lire un fichier, avec numéros de ligne | Existe (`read_file`) |
| `Write` | Créer ou remplacer un fichier | Existe (`write_file`) |
| `Edit` | Remplacer un passage exact | Existe (`edit_file`) |
| `MultiEdit` | Plusieurs remplacements dans un fichier en un appel | Étape 2 |
| `Glob`, `Grep` | Chercher des fichiers et du texte | Étape 1 |
| `Bash`, `BashOutput`, `KillShell` | Commandes, tâches de fond | Existent (`run_command`, `check_job`) |
| `TodoWrite` | Liste de tâches (renvoie la liste entière) | Étape 1 |
| `WebSearch`, `WebFetch` | Recherche web et lecture d'une page | `WebSearch` existe, `WebFetch` à faire (étape 2) |
| `ExitPlanMode` | Fin du plan : présente le plan complet à valider | Étape 1 |
| `AskUserQuestion` | Poser une question à choix | Étape 1 |
| `Task` | Lancer un sous-agent | Étape 4 |

**Sécurité d'écriture, issue de l'étude :** puisque toi et le modèle travaillez dans les mêmes fichiers, `Edit` et `Write` **refusent si le fichier n'a pas été lu** ou a changé depuis la lecture (jeton de version), et écrivent de façon atomique.

Le format exact (schémas et descriptions) sera figé à la fin de l'étape 1 et transmis à la session Argora.

## 4. Le moteur

Boucle d'agent de Discuter, avec appels d'outils natifs pour les modèles cloud, et une configuration propre à Forge. Éléments retenus des études :

**Autorisations**
- **Deux réglages séparés, un seul sélecteur.** Bac à sable (lecture seule / écriture dans le projet / accès total) et approbation (demander / jamais) se combinent en 4 puces : **Demander**, **Éditions auto**, **Plan**, **Tout autoriser**. Un état hors de ces combinaisons s'affiche « Personnalisé ».
- **Refus par défaut :** une demande sans réponse est un refus. Un refus porte un **motif** que le modèle reçoit. Une commande bloquée peut être relancée **une fois** avec plus de droits, avec une justification, et seulement si tu valides.
- **Honnêteté sur le bac à sable :** PRoot n'isole pas vraiment. L'écran le dit, et Forge ne promet pas plus que les autorisations demandées.

**Mode Plan**
- Une consigne dans le prompt ; la sécurité vient des autorisations (lecture seule).
- **La liste d'outils ne change pas d'un mode à l'autre** (garde le cache du prompt).
- On sort par `ExitPlanMode` avec le plan complet en Markdown. Un « oui » dans la conversation ne vaut pas validation : seul le bouton du plan la donne.

**Limites et boucles**
- **Limites combinables :** nombre d'étapes, jetons, durée, bouton Arrêter.
- **Rappel anti-boucle :** même outil avec les mêmes arguments 3, 5 puis 8 fois de suite : rappel de plus en plus précis, puis arrêt avec explication. Essentiel pour les petits modèles et pour Argora.
- **Compteur de blocage :** il monte quand l'agent n'avance pas ; au-delà de 3, il replanifie en disant pourquoi.

**Sessions et mémoire**
- **Journal à ajout seul :** la session est un journal ; la liste de tâches, le mode et les autorisations se recalculent depuis lui. Après un plantage ou une fermeture par Android, le tour resté ouvert est marqué « interrompu », pas coupé. La reprise restaure tout l'état.
- **`ALTREX.md` :** un fichier global, puis la chaîne jusqu'au dossier du projet ; ceux des sous-dossiers se chargent quand le modèle y travaille ; doublons écartés ; balises de contrôle neutralisées. Créé par `/init`.
- **Compaction à deux niveaux :** d'abord raccourcir les vieux résultats d'outils (début et fin gardés), puis seulement un résumé par le modèle. Les sorties trop longues sont écrites dans un fichier dont le modèle reçoit le chemin.

**Prêt pour les sous-agents.** La boucle actuelle ne sait gérer qu'une conversation à la fois. L'étape 1 la rend capable de tourner plusieurs fois indépendamment (historique et état propres à chaque instance), pour que l'étape 4 n'ait pas à tout reconstruire. Un sous-agent sera appelé comme un outil, ne renverra qu'un résumé, avec ses propres limites. **Pas de discussions de groupe** où chaque tour demande un appel au modèle pour choisir qui parle : trop lent et trop cher sur téléphone.

## 5. Les modes de travail

- **Mode Apprentissage.** Le modèle annonce l'outil qu'il va utiliser et pourquoi, explique le code qu'il écrit, propose de petits exercices. Réglage activable à tout moment.
- **Mode Build.** Tu donnes une idée de projet :
  1. l'agent pose quelques questions courtes pour cadrer ;
  2. il recherche les options actuelles et te propose 2 ou 3 architectures et piles de technologies avec leurs compromis, tu choisis ;
  3. il construit, avec un **objectif durable** : nombre de tours plafonné, et une **preuve** (test qui passe, page qui s'affiche) avant de déclarer « terminé » ou « bloqué » ;
  4. il fait une **passe d'amélioration** : revue du code, tests, scan de sécurité, performance, accessibilité ;
  5. il te remet **une seule fois, à la fin**, une liste de propositions classées (fonctions à ajouter, technologies meilleures, corrections) ; tu coches ce qu'il applique. Aucune interruption en cours de route.
- **Modes personnalisés.** Plus tard : tu peux créer tes propres modes (un prompt, des outils, des autorisations) pour ta touche personnelle.

## 6. Blueprint : le lieu où l'on conçoit les systèmes

Un onglet du projet où le modèle et toi concevez : architecture, flux, schémas de données, API.
- Les plans sont des fichiers du projet (`blueprint/`), lisibles sans Altrex.
- Diagrammes dessinés par le modèle puis retouchables ; sur petit écran, **par formulaires et listes** plus que par canevas libre.
- Bascule **Visuel ⇄ source**, annuler/rétablir, **validation qui propose une correction**, et vue en direct quand une exécution suit un plan.
- Un plan peut alimenter le mode Build (« construis ce qui est dans le blueprint »).
- Les Notes vocales peuvent envoyer une idée vers un nouveau projet Forge.
- À étudier : Mermaid (MIT) pour les diagrammes, Excalidraw (MIT) pour les tableaux à main levée.

## 7. Outils et environnement

- **`/doctor`.** Lance vraiment chaque outil (`--version` avec délai) au lieu de vérifier sa présence : distingue absent / cassé / trop lent, donne la consigne de réparation, produit un résumé que le modèle lit. Précieux sous PRoot.
- **Installer.** Par défaut Forge ne fait que **vérifier**. Installer dans le Linux demande ton accord. Consigne fixe au modèle : pas de `sudo`, aucun outil installé dans le dossier des projets.
- **Registre des outils et connecteurs.** Chaque outil a un niveau de configuration, une alternative de repli et un choix forcé possible. Les outils MCP peuvent être **renommés plus court ou en français** pour les petits modèles.
- **Rangée de livrables.** En fin de tour, des puces « fichiers produits », calculées d'après les modifications réellement faites, pas d'après ce que le modèle écrit.
- **Aperçu.** On donne au modèle le vrai port et on **vérifie le rendu**, pas seulement un code HTTP 200. À vérifier sur téléphone : un serveur lancé dans le Linux doit être accessible depuis la WebView.
- **Scan de sécurité** (`/scan`) : fichiers du projet envoyés à SecurCode après ton accord, comme aujourd'hui.
- **Tests :** on vérifie l'état réel (fichier écrit, commande passée), pas ce que l'agent affirme.
- **Git :** instantané automatique avant chaque série de modifications ; `/undo` et `/diff`.
- **Ligne d'état :** modèle · projet · branche Git · contexte utilisé ; avec un modèle local, mémoire et jetons par seconde.

## 8. L'écran et le look

```
┌──────────────────────────────────────┐
│ ☰  monsite · main        ● modèle    │  ligne d'état
│ ▸ Tâches 2/5                         │  liste épinglée, repliable
│──────────────────────────────────────│
│  toi : ajoute un formulaire de…      │
│  ▸ Lu  src/app.js                    │  cartes d'outils repliables
│  ▸ Modifié  src/app.js  +12 −3       │
│  ▸ Commande  npm test  ✓             │
│  réponse du modèle…                  │
│  [app.js] [style.css]                │  livrables
│──────────────────────────────────────│
│ [Demander ▾]  /  @  📎  ┃ message… ➤ │
└──────────────────────────────────────┘
```
- **Intuitif et fluide, mais complet :** les fonctions avancées sont à portée de main sans surcharger le premier écran.
- **Accueil :** tes projets, les sessions récentes, un gros bouton « Nouveau projet » (avec le mode Build).
- **Panneau latéral :** fichiers, aperçu, terminal du projet, Blueprint.
- **Look propre à Forge**, distinct de Claude Code. **Décision du 2026-11-05 : « B + C »** (maquettes dans `docs/forge/maquettes/`, jetons dans `B-technique/tokens.md` et `C-plan/tokens.md`) :
  - **Au quotidien (accueil, session, réglages) : B « Technique sobre »** : fond nuit Altrex #0C151B, surfaces #101B22 / #15232B, filets 1 px #1F2F38, un seul accent cyan #72D6DB, vert #5FD4A0 pour le sens (réussite, ajouts), rouge #F08F8F (suppressions), jaune #E6C46A (indices, interrompu, Tout autoriser). Manrope + JetBrains Mono, rayons 8/6/4, aucune ombre, animations 120–180 ms sans rebond.
  - **Pour concevoir (mode Plan et Blueprint) : C « Plan d'architecte »** : papier #0A1C2E quadrillé, cadres à repères d'angle cyan, cartouche, pointillés pour le provisoire, crayon jaune #F2D27C pour les annotations ; le tracé des liens et cadres se dessine quand le modèle propose un plan (400–600 ms), mouvement réduit = apparition directe.
  - Passage de B à C : changer de mode (Plan) ou ouvrir Blueprint fait « passer sur la planche à dessin » ; le reste de l'appli garde B. La marque Altrex (bouclier d'accolades) reste le fil commun.

## 9. Étapes

| Étape | Contenu | Taille |
|---|---|---|
| **0. Le look** | Fait : B + C choisis (maquettes dans `docs/forge/maquettes/`) | S |
| **1. Fondations** | Dossier de travail commun ; nouvel écran (projets, flux à cartes, ligne d'état) ; outils `Glob`, `Grep`, `TodoWrite`, `ExitPlanMode`, `AskUserQuestion` aux noms de Claude Code ; sélecteur de modes et mode Plan ; refus avec motif ; lecture avant écriture ; limites, rappel anti-boucle ; journal de session avec reprise après interruption ; `ALTREX.md` et `/init` ; `/doctor` ; commandes `/` de base ; service de premier plan et notifications ; moteur ré-entrant | L |
| **2. Confort** | Diff et retours Git (`/undo`, `/diff`) ; `@fichier` ; sessions complètes ; compaction à deux niveaux ; `WebFetch`, `MultiEdit` ; rangée de livrables ; connecteurs et skills ; modes Apprentissage et Build (première version) | L |
| **3. Aperçu, scan, Blueprint** | Aperçu vérifié ; `/scan` ; terminal du projet ; Blueprint (première version) | M à L |
| **4. Plus loin** | Sous-agents, hooks à notre format, commandes `/` à toi, modes personnalisés, Blueprint avancé, relecteur d'approbations | L |

Chaque étape se vérifie par les tests et la compilation, puis tu l'essaies sur ton téléphone avant la suivante.

## 10. Risques

- **Tâches longues interrompues** par Android si le service de premier plan est mal géré : le journal avec « tour interrompu » limite les dégâts.
- **Aperçu local** : dépend du réseau partagé de PRoot, à vérifier.
- **Qualité du modèle** : un modèle faible se perd dans un long chantier ; limites, anti-boucle et compaction aident mais ne remplacent pas un bon modèle.
- **Taille du chantier** : c'est le plus gros de l'appli. D'où les étapes.
- **Migration de l'ancien Code** : ses projets (dossiers du téléphone) devront être importés vers l'espace commun.
- **PC** : le format des projets, de `ALTREX.md` et des sessions sera pensé pour qu'une future version PC puisse les relire ; la version PC elle-même reste à part.

## 11. À décider

1. **Le look** : décidé (B + C), voir section 8.
2. **Mermaid et Excalidraw** pour Blueprint : on les étudie à l'étape 3 (vérifier leur licence et leur poids dans l'appli).
3. **Le nom** : décidé (Altrex Forge) ; reste la vérification de marque avant publication.

## 12. Projets open source de référence

Décision du 2026-11-05 : on s'appuie sur ces projets comme sources d'idées (jamais de copie de code ni de prompt). Licences données de mémoire, **vérifiées dans chaque dépôt par l'étude** (`docs/forge/recherche/`).

| Projet | Ce qu'on en attend | Étape |
|---|---|---|
| Aider | Carte du dépôt (tree-sitter), commit Git par modification, `/undo`, boucle tests puis corrections | 1-2 |
| Cline | Modes Plan et Action, points de retour, présentation des outils | 1-2 |
| Roo Code | Modes personnalisés (prompt + outils + autorisations), sous-tâches | 4 |
| OpenAI Codex CLI | Règles de bac à sable et d'approbation, mémoire de projet, présentation des diffs | 1 |
| Plandex | Changements en attente de ta validation (revue avant application) | 2 |
| Goose | Extensions et « recettes » (enchaînements réutilisables) | 4 |
| OpenCode | Séparation moteur / écran, sessions | 2-4 |
| OpenHands | Environnement d'exécution isolé, journal d'événements (hors dossier `enterprise/`) | 2-4 |
| Mermaid, Excalidraw | Diagrammes et tableaux de Blueprint (licence MIT à confirmer, poids dans l'appli) | 3 |
| CodeMirror 6 | Éditeur de code léger dans une WebView | 2-3 |
| tree-sitter | Structure du code pour la carte du dépôt | 2 |
| Open Interpreter | Licence plus contraignante (AGPL) : idées seulement | - |

Étude détaillée des agents de code : `docs/forge/recherche/coding-agents.md`. Dépôts déplacés : goose → `aaif-goose/goose`, opencode → `anomalyco/opencode`, OpenHands (code de l'agent) → `OpenHands/software-agent-sdk`. Codex CLI contient un `vendor/bubblewrap` sous LGPL : on ne le reprend pas. Pas de lecture du dossier `enterprise/` d'OpenHands. Local-ai-runner : pas de licence, donc rien à reprendre.

## 13. Idées retenues de l'étude des agents de code

Idées seulement, réécrites à notre façon. Étape entre parenthèses.

**Autorisations et sûreté**
- Décision d'approbation riche : refus par défaut, avec motif renvoyé au modèle (1).
- Règles par action / ressource / effet ; sous-dossiers protégés (`.git`, `.altrex`) (1).
- Mode Plan : seules les écritures dans `blueprint/` et `.altrex/plans/` sont permises (1).
- Projet non fiable : `ALTREX.md` d'un projet importé n'est lu qu'après ton accord (1).
- Listes d'autorisation par connecteur MCP et drapeau lecture seule (2).

**Moteur**
- Détecteur de blocage : répétition d'un même outil, monologue, alternance entre deux actions (1).
- Moteur dans un service de premier plan, journal rejouable (1).
- Erreur d'`Edit` : montrer les lignes réelles les plus proches (1).
- Appels d'outils natifs ou émulés pour le modèle local (Argora) (1-2).
- Compaction « pierre tombale » : l'ancien contenu est remplacé par une trace récupérable (2).
- Saisie pendant que l'agent travaille : orienter ou mettre en file (2).

**Écran**
- Cartes d'outils construites depuis les métadonnées, `+N −M`, repli au-delà de 5 lignes (1).
- `/doctor` qui teste aussi le modèle (outils, taille de contexte) (1).

**Sécurité des modifications**
- Points de retour dans un dépôt fantôme avec restauration transactionnelle ; `/undo` protégé (2).
- Revue finale à cases à cocher façon Plandex, avant d'appliquer (2-3).

**Build et au-delà**
- Recette Build : objectif, nombre de tours max, vérifications, essais max, juge d'achèvement (2).
- Modes personnalisés au format Roo, recettes comme commandes `/`, relecteur d'approbations, orchestrateur séquentiel `new_task`, séparation architecte / éditeur (4).
- Carte du dépôt v1 par motifs `rg`, tree-sitter ensuite (2-3).

**À éviter** : bac à sable par espaces de noms / Landlock / Docker (indisponibles sous PRoot) ; dépendances lourdes ; faire classer les commandes « sûres » par le modèle ; commits automatiques sur ta branche ; piloter le binaire Claude Code ; noms de hooks façon Claude Code.
