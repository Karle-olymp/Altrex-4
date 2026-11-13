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

Le format exact (schémas, descriptions, résultats, prompts) est **figé** dans `docs/forge/OUTILS.md` (version 1, fin de l'étape 1) pour la session Argora.

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
| **1. Fondations** | Dossier de travail commun ; nouvel écran (projets, flux à cartes, ligne d'état) ; outils `Glob`, `Grep`, `TodoWrite`, `ExitPlanMode`, `AskUserQuestion` aux noms de Claude Code ; sélecteur de modes et mode Plan ; refus avec motif ; lecture avant écriture ; limites, rappel anti-boucle ; journal de session avec reprise après interruption ; `ALTREX.md` et `/init` ; `/doctor` ; commandes `/` de base ; service de premier plan et notifications ; moteur ré-entrant. **Code fait le 2026-11-05 (voir § 14), à essayer sur le téléphone** | L |
| **2. Confort** | Diff et retours Git (`/undo`, `/diff`) ; `@fichier` ; sessions complètes ; compaction à deux niveaux ; `WebFetch`, `MultiEdit` ; rangée de livrables ; connecteurs et skills ; modes Apprentissage et Build (première version). **Code fait le 2026-11-11 (phases A, B, C, voir § 18), à essayer sur le téléphone ; restent pour plus tard : connecteurs et skills dans Forge, éditeur (étape 2b)** | L |
| **3. Aperçu, scan, Blueprint** | Aperçu vérifié ; `/scan` ; terminal du projet ; Blueprint (première version) | M à L |
| **4. Plus loin** | Sous-agents, hooks à notre format, commandes `/` à toi, modes personnalisés, Blueprint avancé, relecteur d'approbations | L |

Chaque étape se vérifie par les tests et la compilation, puis tu l'essaies sur ton téléphone avant la suivante.

## 10. Risques

- **Tâches longues interrompues** par Android si le service de premier plan est mal géré : le journal avec « tour interrompu » limite les dégâts.
- **Aperçu local** : dépend du réseau partagé de PRoot, à vérifier.
- **Qualité du modèle** : un modèle faible se perd dans un long chantier ; limites, anti-boucle et compaction aident mais ne remplacent pas un bon modèle.
- **Taille du chantier** : c'est le plus gros de l'appli. D'où les étapes.
- **Migration de l'ancien Code** : ses projets (dossiers du téléphone) devront être importés vers l'espace commun.
- **PC** : la version PC a démarré le 2026-11-11 (§ 19). Elle est écrite en Rust (`pc/`), à part de l'appli Android ; les deux moteurs restent alignés par un contrat partagé (`docs/forge/contrat/`). Le risque est la divergence : tout changement de comportement de Forge commence par un cas ajouté au contrat.

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

## 14. Statut de l'étape 1 (2026-11-05)

Fait dans le code (paquet `com.google.ai.edge.gallery.forge`), compilé et couvert par des tests unitaires ; **rien n'a encore été essayé sur le téléphone**.

**Moteur et outils** (`forge/engine`, `forge/tools`, `forge/journal`)
- `ForgeEngine` : boucle d'agent par session, ré-entrante (aucun état global ; modèle, Linux, écran et contexte passent par des interfaces). Même protocole texte que Discuter (`agent/ToolCalling.kt`, déplacé hors de l'ancien Code) : llama.cpp et Spaces en direct, modèles cloud via les appels natifs de `CloudTools`, LiteRT-LM avec une conversation reconstruite à chaque étape.
- 11 outils aux noms de Claude Code, descriptions à nous : Read, Write, Edit, Glob, Grep, Bash, BashOutput, KillShell, TodoWrite, ExitPlanMode, AskUserQuestion. Liste constante dans tous les modes. Schémas et prompts figés : `docs/forge/OUTILS.md`.
- Read/Write/Edit faits par l'appli sur le dossier du projet (rapide, testable) : lecture avant écriture avec jeton de version (empreinte), écriture atomique (fichier temporaire + renommage), refus de tout chemin ou lien qui sort du projet ; erreur d'Edit avec les lignes réelles les plus proches. Glob et Grep écrits en Kotlin (bornés : fichiers, résultats, temps). Bash = le shell du modèle (`AgentShell`, `AgentJobs`) avec un état propre au projet ; sorties longues écrites dans `.altrex/sorties/`.
- Autorisations : 2 axes (bac à sable × approbation), 4 puces, « Personnalisé » sinon ; décision Oui / Toujours / Non, plutôt… (motif renvoyé au modèle) / Arrêter ; refus par défaut (5 min) ; une seule escalade justifiée ; `.git` et `.altrex` protégés ; commandes dangereuses toujours demandées. Règle unique et testée : `PermissionPolicy`.
- Mode Plan : lecture seule, écritures limitées à `blueprint/` et `.altrex/plans/`, rappel à chaque tour, sortie seulement par ExitPlanMode validé avec le bouton ; le plan validé est enregistré dans `.altrex/plans/`.
- Limites (étapes, jetons, durée, Arrêter), rappel anti-boucle 3/5/8, compteur de blocage (échecs, répétitions, alternance A B A B, monologue) avec replanification puis arrêt.
- Journal de session JSON Lines à ajout seul, hors du projet ; tour ouvert marqué « interrompu » au chargement ; écran, historique, tâches, mode et autorisations recalculés depuis le journal.

**Espace de travail** (`forge/workspace`)
- Dossier commun : `files/forge/projets` du téléphone, monté par PRoot en `/root/projets` dans le terminal de l'utilisateur **et** dans le Linux du modèle (le Linux de Discuter le voit donc aussi).
- Projets créés, importés (dossier du téléphone, copié), clonés (git dans le Linux du modèle ; jeton GitHub des Réglages pour un dépôt privé) ; ceux venus de l'extérieur sont « non approuvés » : leur ALTREX.md n'est pas lu avant « Faire confiance ».
- Migration : l'ancien projet de l'écran Code (dossier choisi avec le sélecteur d'Android) est proposé à l'import sur l'accueil de Forge ; l'original n'est pas touché.
- `ALTREX.md` : global, `/root/projets`, projet (repli AGENTS.md / CLAUDE.md), sous-dossiers à la première visite ; doublons écartés ; balises neutralisées. `/init` le fait écrire par l'agent.
- `/doctor` lance vraiment chaque outil (absent / cassé / trop lent) et donne la réparation, sans rien installer.
- Commandes : `/aide`, `/init`, `/doctor`, `/plan`, `/mode`, `/scan`, `/apercu`, `/nouvelle`, `/sessions`, `/confiance`, `/limites`, `/arreter`.

**Écran et service** (`forge/ui`, `forge/service`)
- Accueil (Build, Importer un dossier, Cloner un dépôt, projets, sessions récentes), session (ligne d'état branche · modèle · contexte · t/s en local, tâches épinglées à segments, flux à cartes d'outils repliables avec `+N −M` et diff numéroté, carte d'autorisation, carte de plan au look C, questions à choix, fichiers produits, puce de mode et feuille « Mode de travail », honnêteté sur PRoot). Look B au quotidien, C (papier quadrillé) en mode Plan.
- `ForgeService` (premier plan, type dataSync) pendant un tour, notification avec « Arrêter » ; alerte « Forge attend ton accord » et « terminé » quand l'appli n'est pas à l'écran.
- L'ancien Code est supprimé (`ui/llmsingleturn`, import GitHub par zip, outils de sécurité dédiés). Gardés : l'**aperçu** (fichiers HTML/SVG/Markdown/JS/Python, plus page d'un serveur `http://localhost:PORT`) et le **scan SecurCode** (`/scan`, avec l'accord par projet).

**Non vérifié sans téléphone** : montage `/root/projets` sous PRoot réel (droits, liens) ; Bash et tâches de fond dans le projet ; clonage git ; service de premier plan et notifications (Android 14/15) ; aperçu d'un serveur local dans la WebView ; comportement des modèles locaux (llama.cpp, LiteRT-LM) avec 11 outils et un prompt d'environ 2 000 jetons ; import d'un dossier du téléphone et migration ; rendu réel des écrans (seulement dessinés sous Robolectric).

**Reste pour l'étape 2** : `MultiEdit`, `WebFetch`/`WebSearch` dans Forge, `@fichier`, pièces jointes, compaction à deux niveaux, Git (`/undo`, `/diff`, instantanés), saisie pendant que l'agent travaille, modes Apprentissage et Build complets (la carte Build ouvre pour l'instant un projet en mode Plan), édition de l'ALTREX.md global dans l'appli, réglage des limites.

## 15. Journal des correctifs

### 2026-11-05 — après le premier essai sur téléphone (APK debug, Groq gpt-oss-120b)

**1. Gel de session : « Forge réfléchit… » sans fin, Arrêter sans effet (écran et notification).**
- *Cause racine* : Arrêter ne faisait que lever un drapeau (`requestStop`) et appeler `stopResponse` du modèle ; il n'annulait rien. Après la commande `apk update && apk add nodejs python3` (coupée par le délai), l'étape suivante attendait la réponse du modèle cloud : le moteur était suspendu sur `done.await()`, que seul le fil réseau pouvait compléter, et ce fil était bloqué dans une lecture de flux SSE (`readLine`, délai de lecture de 10 min) qui ne regarde le drapeau d'arrêt qu'entre deux lignes. Le drapeau n'était relu par le moteur qu'*après* le retour du modèle : rien ne pouvait donc débloquer le tour avant 10 min. Facteurs aggravants : `LinuxEnv.exec` attendait le processus par un `waitFor` bloquant non annulable, l'arrêt de PRoot pouvait laisser `apk` vivant (verrou de la base et tube de sortie gardés ouverts), et « Arrêter » de la notification démarrait le service depuis l'arrière-plan.
- *Correctif* : Arrêter annule maintenant la coroutine du tour (`ForgeSessionController.stop`) ; toutes les attentes sont annulables (modèle, commande, utilisateur, attente de limite de débit) ; le modèle cloud est appelé directement par Forge avec une connexion qu'Arrêter ferme (`CloudApi.Abort`, aussi pour Discuter) et `RuntimeModelPort.stop` libère le moteur aussitôt ; une commande est attendue par petits pas et, à l'annulation, tout son arbre de processus est tué (`ProcessTree` : SIGTERM puis SIGKILL après 0,7 s, tubes fermés, sur un fil à part) ; un `apk` resté seul est tué avant la commande apk suivante (`LinuxEnv.reapStrays`, variable `ALTREX_ENV` donnée aux programmes du Linux) ; filet de sécurité : si le tour n'est pas fini 1,2 s après Arrêter, il est clos dans le journal (`ForgeEngine.forceStop`) et l'écran rendu ; la notification passe par un récepteur (`ForgeStopReceiver`), toujours livré. Tests : commande bloquante annulée en moins de 2 s avec son arbre (`AgentJobsTest`), tour arrêté pendant une commande, pendant une attente de limite, et tour bloqué dans du code non annulable (`ForgeEngineStopTest`).

**2. Délai des commandes.** Plus de délai total fixe : délai d'inactivité (120 s ; 300 s pour apk, npm, pip, git clone, make… ; 20 s pour un serveur) et plafond de 30 min ; au dépassement, la commande n'est pas tuée mais passe en tâche de fond, le modèle reçoit la sortie partielle, le `job_id` et la consigne de suivre avec BashOutput (`CommandWait`, `AgentJobs.await`). La carte montre les 4 dernières lignes en direct. Prompt système : installations et serveurs en arrière-plan (`OUTILS.md` version 1.1).

**3. Aperçu.**
- *a)* `python3 -m http.server` servait `/root` : le script de Forge faisait `cd "$(cat cwd)"` ; sans dossier enregistré c'est `cd ""`, que `sh` accepte sans bouger : la première commande tournait dans `/root`, l'enregistrait, et toutes les suivantes (tâches de fond comprises) partaient de là. Le dossier enregistré n'est plus repris que s'il est dans le projet, sinon on repart de la racine du projet (aussi pour les états déjà abîmés).
- *b)* Fichiers HTML ouverts par une URL `data:` (erreur `net::ERR_HTTP_RESPONSE_CODE_FAILURE`, liens relatifs cassés) : remplacé par un petit serveur HTTP dans l'appli (`forge/preview/PreviewServer.kt`) : 127.0.0.1 seulement, port éphémère, démarré à l'ouverture de l'aperçu et arrêté à sa fermeture, dossier du projet seulement (traversée, `%2e%2e`, liens symboliques sortants et fichiers cachés refusés), types MIME, `index.html`, liste de dossier, `no-store`, jeton exigé (adresse puis cookie HttpOnly) pour que les autres applis du téléphone ne lisent pas le projet. HTML, SVG, CSS et JS passent par lui ; « Serveur local » reste pour les serveurs lancés par le modèle.
- *c)* Assombrissement forcé/algorithmique du WebView désactivé, fond blanc par défaut.
- *d)* Bouton Recharger, et rechargement automatique quand l'agent modifie un fichier (Write, Edit, Bash) pendant que l'aperçu est ouvert.
- *e)* `network_security_config.xml` inchangé (http en clair vers localhost et 127.0.0.1 seulement) ; JavaScript et stockage local actifs ; contenu mixte non nécessaire (aucune page https ne charge le serveur local), laissé refusé ; CORS : même origine pour les pages du serveur de l'aperçu.

**4. Petits correctifs.** Carte d'autorisation : « N lignes de plus · voir tout » ne recouvre plus la première ligne du diff (deux éléments frères dans une `Box`) ; liste de tâches : consigne renforcée dans le prompt et rappel automatique, une fois par tour, si des tâches restent non terminées à la réponse finale ; ligne d'état : libellés raccourcis sur écran étroit (« ctx », nom du modèle sans son fournisseur) et défilement horizontal ; limites de débit (HTTP 429) : nouvel essai automatique (Retry-After ou 2, 4, 8 s, 3 essais), message « Limite du fournisseur …, nouvel essai dans N s » dans le fil, annulable.

**Non vérifié sans téléphone** : temps réel d'arrêt sous PRoot (SIGTERM aux processus tracés, `--kill-on-exit`), lecture de `/proc/<pid>/environ` et `cmdline` des processus du Linux sur Android (repérage des apk orphelins), obtention du pid d'un processus sur Android (réflexion, sinon comparaison des enfants avant/après le lancement), fermeture d'une connexion HTTPS bloquée par `disconnect()` depuis un autre fil, livraison du récepteur de la notification, cookie du jeton accepté par le WebView, rendu clair (assombrissement désactivé) selon la version du WebView, réception réelle d'un 429 de Groq et format de son `Retry-After`.

### 2026-11-11 — après l'essai sur téléphone : barre des modèles distants, calcul automatique

**1. Modèles cloud et Spaces : pas de barre d'état, il fallait écrire un message pour « charger ».**
- *Cause* : la barre « Non chargé · Charger » et la carte « Modèle actif » ne s'affichaient que pour les modèles locaux (`ComputeRuntime.hasLocalCompute`).
- *Correctif* (`runtime/RemoteCheck.kt`, `ui/common/ModelLoadControls.kt`, `ModelPickerChip.kt`) : pour `cloud:…` et `space:…`, une barre cohérente au-dessus du chat et de Forge, et dans le sélecteur de modèle : « Prêt », « Connexion… », « Hors ligne », « Clé manquante », « Clé refusée », « Indisponible », avec **« Vérifier la connexion »**. Cloud : une seule requête légère (la liste des modèles du fournisseur avec la clé, aucun jeton consommé) valide la clé et la présence du modèle ; elle part d'elle-même une fois à l'affichage. Space Hugging Face : lecture de la description de son API ; s'il dort, la barre indique « Space en veille, réveil en cours… » et attend son démarrage (4 min au plus) ; un Space n'est réveillé que sur demande. Pas de « Décharger » : rien n'est en mémoire. La barre du chat disparaît une fois « Prêt » ; la pastille de la puce devient verte.

**2. Choix automatique du calcul (LiteRT-LM) « très long et un peu instable ».**
- *Cause* : au premier chargement manuel, le mode Auto mesurait **avant** de charger : pour chaque calcul (CPU, GPU, NPU) un moteur complet était démarré avec le banc d'essai de LiteRT-LM (64 jetons lus, 32 écrits), sans délai maximum, puis le modèle était chargé une fois de plus. Un pilote lent ou instable bloquait donc le chargement.
- *Correctif* (`compute/AutoBench.kt`, `AutoCompute.kt`, `ComputeRuntime.kt`, `LlmChatModelHelper.kt`) : le modèle se charge **immédiatement** sur un choix sûr, sans mesure : le choix mesuré s'il existe, sinon le dernier calcul qui a démarré correctement, sinon celui que le modèle recommande (LiteRT-LM), sinon le CPU (GGUF : CPU). La mesure se fait **ensuite, en arrière-plan** : seulement après un « Charger » manuel hors conversation (jamais après un envoi de message), batterie et mémoire libres suffisantes ; un calcul à la fois, le calcul chargé d'abord ; banc minuscule (16 jetons lus, 12 écrits) ; **18 s au plus par calcul** (au-delà : « trop lent », plus essayé de lui-même) ; annulable (bouton, début d'une génération, déchargement) ; progression visible (« Auto : test du GPU… ») dans le sélecteur de modèle et le feuillet Génération. **Anti-plantage** : un drapeau « test en cours » est écrit sur disque avant chaque essai ; s'il est encore là au chargement suivant, le calcul est mis en liste noire pour ce modèle, ce téléphone et cette version, et n'est jamais retenté en boucle. Le résultat est **mémorisé et appliqué au chargement suivant** ; si un calcul plus rapide est trouvé, un bouton « Basculer sur GPU » le propose, rien n'est rechargé sans prévenir. Bouton **« Relancer la mesure »** dans le feuillet Génération.
- Tests : `AutoBenchTest` (choix immédiat, ordre, un seul à la fois, délai, liste noire, reprise après plantage simulé), `RemoteCheckTest`.

**Non vérifié sans téléphone** : réponse réelle d'un Space endormi (503, ou page d'attente) et durée de son réveil ; liste des modèles de chaque fournisseur (OpenRouter la donne sans clé : sa clé n'est vérifiée qu'au premier message) ; durée réelle du banc de LiteRT-LM par calcul sur Snapdragon 8 Gen 1 (si le démarrage du GPU dépasse 18 s, il sera noté « trop lent » : « Relancer la mesure » lui redonne une chance, ou choisis-le à la main) ; mémoire pendant la mesure (un second moteur est démarré à côté du modèle chargé ; refusée si la mémoire libre est inférieure à 1,25 fois la taille du modèle + 600 Mo) ; l'appel natif du banc ne peut pas être interrompu, seulement attendu ; « Basculer » (décharger puis recharger).

## 16. Étude d'Acode (MIT) : idées retenues

Étude complète : `docs/forge/recherche/acode.md` (dépôt Acode, commit 6962af1, licence MIT ; idées seulement, aucun code repris).

| Idée | Étape |
|---|---|
| Rapport d'aperçu pour l'agent : erreurs de console, JS, HTTP, plantage du rendu (callbacks natifs de la WebView) | 3 |
| Émulateur de tailles d'écran (téléphone, tablette, bureau) et captures multi-tailles | 3 |
| Garde de navigation de l'aperçu (origine du projet ; liens externes dans le navigateur) | 3 |
| Profil WebView isolé par projet (cookies et stockage séparés) | 3 |
| Servir le texte non enregistré de l'éditeur avant le disque | 3 |
| Fichier ouvert modifié par l'agent : rechargement ou conflit visible, jamais d'écrasement silencieux | 2 |
| Barre de touches rapides configurable (symboles, Ctrl, Tab, flèches) | 2 |
| CodeMirror 6 tactile en WebView (poignées, indentation conservée) | 2 |

À ne pas reprendre : serveur sur toutes les interfaces sans jeton, Eruda injecté partout, réécriture du HTML par regex, plugins avec tous les droits, SFTP/FTP (LGPL), `libaxs.so` (licence non vérifiée). Forge fait déjà mieux sur le serveur d'aperçu (127.0.0.1, jeton, port éphémère) et sur le rechargement automatique.

## 17. Document d'architecture du 8 novembre (`ARCHITECTURE_ALTREX4.md`, sur `main`)

Analyse du 2026-11-10.

- **Déjà fait dans Forge :** double terminal, modes de permission, cartes d'accord, outils déterministes avec diff.
- **Déjà prévu :** compaction du contexte et carte du dépôt (étape 2), client MCP (n8n à tester), skills `SKILL.md`.
- **Laya (Système 1) :** à tester d'abord dans notre llama.cpp (API `/v1/systemone` non vérifiée). Modèles sous Apache 2.0 vérifiés (`ggml-org/Laya-GGUF`, `convaiinnovations/laya-multilingual`) ; préférer le multilingue. **Règle :** son score peut ajouter une demande d'accord, jamais en retirer.
- **Kali :** à ajouter en option téléchargeable, sans remplacer Alpine ; prévoir l'équivalent `apt` des aides `apk`/`altrex-setup`. `force-unsafe-io` et `nmap -sT --unprivileged` sont justes. Les cartes d'accord restent actives même en mode « force ».
- **À corriger dans le document :** retirer la référence à OpenClaude (dérivé du code propriétaire de Claude Code, écarté) ; changer l'`applicationId` une seule fois, avec la vraie clé de signature.
- **Recherche FTS5 + RRF :** bonne idée, complète la recherche par le sens.
- **Licence de `aayushch/laya` :** non vérifiée.

Ordre proposé : chargement manuel et calcul (fait) → étape 2 de Forge → test de Laya → Kali en option → n8n.

## 18. Statut de l'étape 2 « Confort » (2026-11-10)

En trois phases, chacune compilée, testée et enregistrée (commit local) avant la suivante. **Rien n'a encore été essayé sur le téléphone.** Hors périmètre, gardés pour plus tard : éditeur CodeMirror et barre de touches (étape 2b), Blueprint (étape 3), sous-agents (étape 4), connecteurs et skills dans Forge.

**Phase A : Git et fichiers** (`forge/checkpoints`, `forge/web`, `tools/Mentions.kt`, `ui/ForgeChanges.kt`)
- Instantanés automatiques autour des modifications de l'agent : « avant » à la première écriture du tour (Write, Edit, MultiEdit, commande Bash qui n'est pas en lecture seule), « après » en fin de tour avec la liste des fichiers changés (Bash compris). Dépôt Git **fantôme** `/root/projets/.instantanes/<projet>.git` (GIT_DIR à part, sans hooks, identité « Altrex Forge ») : jamais de commit sur la branche de l'utilisateur. **Repli** si git ne répond pas dans le Linux : copies dédupliquées par contenu dans `files/forge/instantanes/<projet>/` (mêmes commandes ; fichiers de plus de 2 Mo non couverts). Les identifiants distinguent les deux sortes, les anciens instantanés restent utilisables.
- `/undo` (confirmation avec la liste des fichiers) : sauvegarde d'abord, remet les fichiers du dernier tour, supprime ceux que l'agent a créés, **laisse tels quels** ceux que l'utilisateur a modifiés depuis, et remet tout comme avant si quelque chose échoue ; l'agent est prévenu au tour suivant. `/diff [session]` en cartes `+N −M` dépliables ; `/checkpoints` (liste, annuler, diff). Aussi dans le menu ⋮.
- `@fichier` : sélecteur à recherche floue dans la saisie ; contenu injecté borné (8 000 caractères par fichier, 20 000 en tout), un fichier donné en entier compte comme lu. 📎 : pièce jointe copiée dans `.altrex/pieces/` puis mentionnée.
- Outils `MultiEdit` (tout ou rien, un seul accord), `WebFetch` (HTTPS public seulement : localhost, IP privées, CGNAT, IPv6 locales et redirections vers elles refusées ; 2 Mo, 20 s ; texte extrait), `WebSearch` (clé Brave des Réglages, sinon message clair). `docs/forge/OUTILS.md` passe en **version 1.2** (compatible : ajouts après les 11 outils).
- Fichier changé hors de Forge : le jeton de version refusait déjà l'écriture ; en plus, le fichier est relu juste avant d'écrire (l'utilisateur a pu le modifier pendant l'attente de l'accord) et un conflit est montré dans le fil, jamais d'écrasement silencieux ; l'aperçu ouvert se recharge quand le fichier change, même hors de Forge.
- « Fichiers produits » : calculés aussi depuis l'instantané (fichiers créés par Bash compris) ; chaque puce ouvre un menu : ouvrir dans l'aperçu, partager, copier le contenu, copier le chemin.

**Non vérifié sans téléphone (phase A)** : git du Linux sur `/root/projets` sous PRoot (droits, durée d'un instantané sur un gros projet), choix git/copies au premier instantané, partage par FileProvider d'un fichier de `files/forge/projets`, sélecteur de pièces jointes, WebFetch et Brave réels (certificats, proxy, réseau mobile), rechargement de l'aperçu par sondage toutes les 1,5 s.

**Phase B : contexte et performance** (2026-11-11 ; `engine/ForgeEngine.kt`, `engine/ConversationReuse.kt`, `workspace/ForgeSettings.kt`, `ui/ForgeSettingsSheet.kt`)
- **Compaction à deux niveaux.** Au-delà de 60 % du budget du modèle, les gros anciens résultats d'outils du tour deviennent des traces récupérables (texte complet dans `.altrex/sorties/`, un Read se relit) ; encore au-delà de 85 %, le modèle résume ce qui précède. Le journal garde tout ; l'écran aussi.
- **Mode léger** pour les modèles locaux lents (automatique pour LiteRT-LM et les petits contextes, réglable : Auto / Toujours / Jamais) : 7 outils décrits en une ligne chacun (Read, Write, Edit, Bash, Glob, Grep, TodoWrite), prompt système de moins de 600 jetons (au lieu d'environ 2 000), résultats d'outils de 1 500 caractères, 15 étapes par tour. Pour **LiteRT-LM**, la conversation du moteur est gardée d'une étape à l'autre au lieu d'être reconstruite (c'était la cause principale de la lenteur mesurée avec Gemma 4 E4B : tout le prompt était relu à chaque étape).
- **Saisie pendant que l'agent travaille** : « Orienter » (le message arrive au modèle à sa prochaine étape) ou « Après » (file d'attente visible, annulable, envoyée à la fin du tour).
- **Réglages de Forge** (menu ⋮, `/reglages`) : mode léger, nombre d'étapes et durée d'un tour, édition de l'**ALTREX.md global** dans l'appli.
- `docs/forge/OUTILS.md` : version 1.3 (§ 9).

**Non vérifié sans téléphone (phase B)** : gain réel de vitesse de LiteRT-LM avec la conversation gardée (et l'égalité entre ce que le moteur garde et l'historique de Forge quand le modèle écrit un raisonnement) ; comportement de Gemma 4 E4B avec le prompt léger (appels d'outils bien formés ?) ; qualité des résumés d'un petit modèle local ; seuils 60 % / 85 % sur de vraies sessions ; feuillet des réglages et boutons « Orienter » / « Après » sur écran étroit.

**Phase C : modes Build et Apprentissage** (2026-11-11 ; `engine/BuildMode.kt`, `engine/ForgePrompts.kt`, `ui/ForgeSheets.kt`)
- **Build est un vrai mode.** La carte « Build » de l'accueil (et l'interrupteur de la feuille « Mode de travail », ou `/build`) l'ouvre : tu décris ton idée ; Forge cadre (quelques questions si un choix change l'architecture) et propose l'architecture dans un plan à valider avec le bouton (ExitPlanMode), qui doit se terminer par une **recette** : objectif, fichiers attendus, commandes de vérification, page d'aperçu. Un plan sans preuve vérifiable est renvoyé au modèle avant même de t'être montré.
- **Construction autonome, bornée.** Plan validé, Forge construit seul (modifications de fichiers sans question ; les commandes restent demandées, sauf « Toujours » ou « Tout autoriser »). À la fin de chaque tour, le **juge d'achèvement** de l'appli contrôle la recette sans demander son avis au modèle : fichiers présents et non vides, commandes de test qui réussissent dans le Linux du projet, page d'aperçu présente avec tous ses fichiers liés (ou serveur local qui répond). Une preuve qui échoue relance un tour de correction avec le rapport ; limites réglables : 8 tours, 3 essais de correction d'un même échec, puis « bloqué » avec le rapport (ton message relance). Arrêter coupe toute la boucle.
- **Propositions une seule fois, à la fin.** Quand tout passe, un dernier tour en lecture seule propose améliorations et meilleures technologies (liste classée, gain et effort) ; rien n'est appliqué (toute écriture y est refusée).
- **Apprentissage** (interrupteur, `/apprendre`) : l'agent annonce chaque outil et pourquoi, explique le code, guide pas à pas sans faire à ta place sauf demande, termine par un petit exercice et vérifie ta réponse au tour suivant. Se combine avec tous les modes d'autorisation.
- Les deux modes sont dans le journal (reprise après fermeture), visibles sur la puce de mode ; `docs/forge/OUTILS.md` passe en **version 1.4** (§ 10).
- Par rapport au § 5 : la recherche de 2 ou 3 architectures est réduite à « une retenue, une ou deux écartées en une ligne » dans le plan ; la « passe d'amélioration » automatique (revue, scan) et les cases à cocher sur les propositions restent à faire (étape 3).

**Non vérifié sans téléphone (phase C)** : qu'un vrai modèle écrive bien la section « ## Recette » au format attendu (le format toléré est large, mais un petit modèle local peut l'oublier : il reçoit alors l'erreur et doit recommencer) ; durée et fiabilité des commandes de vérification sous PRoot ; le contrôle de l'aperçu est **statique** (fichiers liés présents), il n'exécute pas la page : les erreurs JavaScript ne sont pas vues avant le rapport d'aperçu de l'étape 3 ; rendu de la feuille « Mode de travail » (deux interrupteurs) et de la puce de mode allongée sur écran étroit ; comportement pédagogique réel du mode Apprentissage selon le modèle.

**Tests de l'étape 2** : `Step2FilesTest` (instantanés, /undo, @fichier, MultiEdit, web), `Step2ContextTest` (compaction, mode léger, réutilisation de conversation, orientation, réglages), `Step2ModesTest` (recette, juge d'achèvement, limites de tours et d'essais, propositions une seule fois, Apprentissage, journal).

## 19. Version PC (2026-11-11)

Décision et étude : `docs/pc/PLAN.md` ; compilation, usage et limites : `docs/pc/README.md`.

- **Architecture** : un noyau en Rust (`pc/crates/altrex-core`) qui réécrit le moteur de Forge sans interface, et une application de bureau (`pc/crates/altrex-pc`, egui). Aucun fichier de l'appli Android n'a été modifié. Raisons : exécutable de quelques Mo au lieu d'une JVM embarquée, `.exe` Windows produit depuis Linux, aucun risque de régression Android. Prix : le moteur est réécrit, pas réutilisé.
- **Contrat partagé** (`docs/forge/contrat/`) : les schémas des 14 outils (§ 2 de `OUTILS.md`, vérifiés des deux côtés), le format du journal (version 1), et des vecteurs de test lus par les tests Kotlin (`ContratPartageTest`) et Rust (`tests/contrat.rs`) : lecture des commandes, autorisations, appels d'outils, journal.
- **Ce qui change sur PC** : pas de PRoot ni d'Alpine, donc un shell natif (PowerShell sous Windows, `sh` ailleurs) et **pas de bac à sable** ; le prompt système et la description de l'outil Bash décrivent la machine et son shell, les noms et paramètres des outils ne changent pas. Les modèles locaux passent par `llama-server` (protocole texte de `OUTILS.md`), les modèles cloud par leurs API. Un projet peut être un dossier existant du disque.
- **État** : squelette qui tourne (projets, session de bout en bout, 12 outils sur 14, autorisations, mode Plan, journal, aperçu local, clés chiffrées). Restent à porter : compaction, `/undo` et `/diff`, WebFetch et WebSearch, commandes `/`, mode léger, Build et Apprentissage (liste complète dans `docs/pc/README.md`).
- **Pour le dataset Argora** : rien ne change. Le texte vu par un modèle local sur PC est celui de `OUTILS.md` (même bloc d'outils, même format d'appel et de résultat) ; seules deux descriptions d'outils et le texte d'environnement du prompt système nomment l'ordinateur au lieu du téléphone.
