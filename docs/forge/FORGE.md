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
- **Nom :** « Forge » dans l'appli ; « Altrex Forge » pour toute communication publique. Le nom seul est déjà utilisé ailleurs (Laravel Forge, Atlassian Forge, Minecraft Forge, un agent de code nommé Forge, une maquette « LocalForge »). **Vérification de marque à faire avant toute publication**, comme pour le logo.
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
- **Look propre à Forge**, distinct de Claude Code. **Étape 0 : trois directions en maquettes** (ambiance « atelier » avec braises, ambiance « sobre technique », ambiance « plan d'architecte »), tu en choisis une avant que l'écran soit codé. La marque Altrex (fond nuit, bouclier d'accolades) reste le fil commun.

## 9. Étapes

| Étape | Contenu | Taille |
|---|---|---|
| **0. Le look** | Trois maquettes de l'écran Forge, tu choisis | S |
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

1. **Le look** (étape 0) : je prépare les trois maquettes quand tu me dis go.
2. **Mermaid et Excalidraw** pour Blueprint : on les étudie à l'étape 3 (vérifier leur licence et leur poids dans l'appli).
3. **Le nom** : Forge te convient-il toujours après la vérification de marque ? Sinon : Atelier, Fonderie.
