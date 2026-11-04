# Altrex Code : cahier des charges

Version 0 du 2026-11-05. Document de travail : tu relis, tu corriges, puis on construit par étapes.

## 1. Le principe

Altrex a deux modes qui n'ont plus rien à voir :

| | **Discuter** | **Altrex Code** |
|---|---|---|
| Pour quoi | Le quotidien : questions, notes, documents, recherche, rappels, connecteurs | Programmer : créer une appli, un site, un système, corriger, tester, déployer |
| Comparable à | Claude (l'appli de discussion) | Claude Code |
| Façon de travailler | Conversation, réponses courtes | Un agent qui lit, écrit, lance et vérifie, étape par étape, dans un projet |
| Espace de travail | Aucun | Un projet, dans le Linux d'Altrex |
| Outils | Skills, connecteurs, recherche web, commandes ponctuelles | Fichiers, commandes, recherche dans le code, liste de tâches, plan, Git |

**Décisions déjà prises (2026-11-05) :**
- Les projets vivent dans le Linux d'Altrex, dans un dossier commun au Terminal et au modèle.
- L'ancien écran Code est remplacé. On garde son aperçu des pages web et son scan de sécurité, qui deviennent des fonctions du nouveau Code.

## 2. Ce qu'on reprend de Claude Code

| Claude Code | Altrex Code | État aujourd'hui |
|---|---|---|
| Lire, écrire, modifier un fichier (Read, Write, Edit) | `read_file`, `write_file`, `edit_file` | Existent déjà (Linux du modèle) |
| Modification multiple (MultiEdit) | Plusieurs remplacements dans un fichier en un appel | À faire (étape 2) |
| Chercher des fichiers et du texte (Glob, Grep) | `glob`, `grep` (ripgrep dans Alpine, sinon `grep -r`) | À faire (étape 1) |
| Commandes et tâches de fond (Bash) | `run_command`, `check_job` | Existent déjà |
| Liste de tâches (TodoWrite) | `todo_write` : la liste s'affiche en haut de l'écran et se coche en direct | À faire (étape 1) |
| Recherche et lecture web (WebSearch, WebFetch) | `webSearch` existe ; `web_fetch` (lire une page en texte) à ajouter | Partiel |
| Mode plan | Le modèle lit seulement, propose un plan, tu le valides avant toute modification | À faire (étape 1) |
| Modes d'autorisation | **Demander** / **Éditions auto** / **Plan** / **Tout autoriser** | Les réglages existent (`AgentShell.Mode`), à exposer dans l'écran |
| Mémoire du projet (`CLAUDE.md`) | `ALTREX.md` dans le projet (et un global), créé par `/init` | À faire (étape 1) |
| Commandes `/` | `/init` `/clear` `/compact` `/plan` `/model` `/permissions` `/mcp` `/memory` `/diff` `/undo` `/scan` `/preview` `/help` | À faire (étapes 1 à 3) |
| Mentionner un fichier (`@`) | `@` ouvre un sélecteur de fichiers du projet | À faire (étape 2) |
| Points de retour (checkpoints, rewind) | Instantané Git avant chaque série de modifications ; `/undo` revient en arrière | À faire (étape 2) |
| Sessions et reprise | Historique des sessions par projet, reprise d'une session | À faire (étape 2) |
| MCP | Les connecteurs d'Altrex sont utilisables dans Code | Existent, à brancher |
| Skills | Les skills d'Altrex sont utilisables dans Code | Existent, à brancher |
| Compaction du contexte | `/compact` résume l'historique ; automatique près de la limite | À faire (étape 2) |
| Sous-agents (Task) | Des agents spécialisés lancés par l'agent principal | Étape 3 |
| Hooks, commandes personnalisées | Scripts lancés avant ou après un outil, commandes `/` à toi | Étape 3 |
| Ligne d'état | modèle · projet · branche Git · contexte utilisé | À faire (étape 1) |

## 3. Adaptations au téléphone

- **Pas de clavier physique.** Un modèle ne peut pas « appuyer sur Maj+Tab » : le changement de mode se fait par une puce touchable. Les commandes `/` s'ouvrent comme un menu quand on tape `/`.
- **Écran étroit.** Le flux de conversation montre des cartes repliables : une carte par outil (commande, fichier lu, fichier modifié avec son diff). La liste de tâches reste épinglée en haut, repliable.
- **Longues tâches.** Un agent de code travaille des minutes, parfois plus. Si Android ferme l'appli, la tâche s'arrête. Il faut un service de premier plan avec une notification « Altrex Code travaille… » et une notification à la fin. À faire dès l'étape 1.
- **Batterie et chaleur.** Indicateur discret quand une tâche tourne, bouton Arrêter toujours visible.

## 4. Architecture

**Espace de travail commun.** Un dossier de l'appli, `workspace/`, monté dans les deux Linux (celui du Terminal et celui du modèle) sous `/root/projets`. Chaque projet est un sous-dossier. Toi dans le Terminal et le modèle voyez les mêmes fichiers. Le modèle ne voit que ce dossier et son propre Linux, jamais tes autres fichiers. Export et import avec le dossier partagé du téléphone (`~/partage`).

**Moteur.** On réutilise la boucle d'agent de Discuter, avec les appels d'outils natifs pour les modèles cloud, mais avec une configuration propre à Code :
- un prompt système dédié, dans l'esprit de Claude Code (concis, agit plutôt que bavarde, lit avant de modifier, vérifie son travail, lance les tests) ;
- sa liste d'outils (section 2), qui ne contient ni les skills ni les actions du téléphone, mais les connecteurs et les skills activés ;
- des limites plus larges : beaucoup plus d'étapes par demande, contexte géré par compaction ;
- les règles d'autorisation de `AgentShell` (lectures libres, accord pour le reste), avec le mode Plan en lecture seule stricte (aucun outil qui écrit ou qui lance, sauf lectures).

**Aperçu.** PRoot partage le réseau d'Android : un serveur de développement lancé dans le Linux écoute sur `127.0.0.1` et la WebView d'Altrex peut l'afficher. **À vérifier sur un téléphone** avant de s'engager. La carte « Aperçu » ouvre alors le port du projet.

**Scan de sécurité (SecurCode).** Devient une commande `/scan` qui envoie à SecurCode les fichiers du projet, après ton accord, comme aujourd'hui. À adapter : lire les fichiers depuis le Linux et non plus depuis un dossier Android.

**Git.** Installé par `altrex-setup`. Instantané automatique avant chaque série de modifications dans un dépôt local du projet ; `/undo` et `/diff` s'appuient dessus. Si le projet n'est pas un dépôt Git, Altrex en crée un et te le dit.

## 5. L'écran

```
┌──────────────────────────────────────┐
│ ☰  monsite · main        ● claude-…  │  ligne d'état : projet, branche, modèle
│ ▸ Tâches 2/5                         │  liste épinglée, repliable
│──────────────────────────────────────│
│  toi : ajoute un formulaire de…      │
│  ▸ Lu  src/app.js                    │  cartes d'outils repliables
│  ▸ Modifié  src/app.js  +12 −3       │
│  ▸ Commande  npm test  ✓             │
│  réponse du modèle…                  │
│──────────────────────────────────────│
│ [Demander ▾]  /  @  📎  ┃ message… ➤ │  mode, commandes, fichiers, envoi
└──────────────────────────────────────┘
```
- **Accueil du mode Code :** la liste de tes projets (créer, ouvrir, importer depuis le téléphone), les sessions récentes.
- **Panneau latéral :** arbre des fichiers, aperçu, terminal intégré du projet.
- **Cartes de modification :** diff coloré, boutons Accepter / Refuser selon le mode d'autorisation.

## 6. Quels modèles

Code marche avec tous les modèles d'Altrex, mais un vrai agent de code demande un modèle capable : les modèles cloud (Claude, GPT, Gemini…) sont recommandés. Un petit modèle local fera de petites tâches. Argora sera entraîné sur les outils de Code dans un second temps : le format exact des outils sera transmis à la session Argora avant d'être figé.

## 7. Étapes

| Étape | Contenu | Taille |
|---|---|---|
| **1. Fondations** | Dossier de travail commun, écran Code (liste des projets, flux avec cartes d'outils, ligne d'état), prompt et outils (`glob`, `grep`, `todo_write`), modes d'autorisation et mode Plan, `ALTREX.md` et `/init`, commandes `/` de base, service de premier plan et notifications | L |
| **2. Confort de travail** | Diff et points de retour Git (`/undo`, `/diff`), `@fichier`, sessions et reprise, `/compact`, `web_fetch`, multi-modifications, connecteurs et skills dans Code | L |
| **3. Aperçu et scan** | Aperçu des pages web (après vérification du réseau), `/scan`, terminal intégré au projet | M |
| **4. Pour aller plus loin** | Sous-agents, hooks, commandes `/` personnalisées, ta touche personnelle | L |

Chaque étape se vérifie par les tests et la compilation, puis tu l'essaies sur ton téléphone avant la suivante.

## 8. Risques

- **Tâches longues interrompues** par Android si le service de premier plan est mal géré.
- **L'aperçu local** dépend du réseau partagé de PRoot : à vérifier.
- **Qualité du modèle** : un modèle faible se perd dans une longue tâche, ce n'est pas un défaut du mode Code.
- **Taille du chantier** : c'est le plus gros de l'appli après le terminal. D'où les étapes.
- **Migration de l'ancien Code** : ses projets (dossiers du téléphone) devront être importés vers l'espace commun.

## 9. À décider

1. **Les noms des outils** : on garde `read_file` / `write_file` / `edit_file` / `run_command`, ou on adopte ceux de Claude Code (`Read`, `Write`, `Edit`, `Bash`…) ? Les modèles cloud les connaissent mieux, mais Argora est déjà entraîné sur les premiers.
2. **Les sous-agents** : étape 4 comme prévu, ou plus tôt ?
3. **Ta touche personnelle** : qu'est-ce que tu veux ajouter ou changer par rapport à Claude Code ? (look, fonctions, façon de parler, mode « apprentissage » où le modèle explique ce qu'il fait, etc.)
