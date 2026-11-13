# DeepSeek Harness (dsh) : ce qui sert à Forge

Étude en lecture seule (grep/cat/ls uniquement, rien exécuté). Racine notée `R/` =
`/tmp/claude-0/-home-user/f2187807-956a-51ca-a64a-3a197336c840/scratchpad/repos/deepseek-harness/deepseek-harness-master/`.
Référence Forge : `/home/user/Altrex-4/docs/code/ALTREX_CODE.md` (étapes 1 fondations, 2 confort, 3 aperçu/scan, 4 sous-agents/hooks/touche perso).

## 1. Ce que c'est

- Harnais d'agent open source de DeepSeek (CLI `dsh`, Web UI sur 127.0.0.1:3080, SDK Python), en « developer preview » avec ruptures annoncées (`R/README.md`).
- Tout est plugin sur le framework Cordis (vendored, MIT) ; ~50 groupes de paquets : sessions, outils fs/bash, sandbox, approbation, plan, todo, compaction, skills, MCP, sous-agents, workflows, goal, hooks (`R/packages/README.md`).
- Documentation très abondante, bilingue en/zh, avec « Agent Notes » de décision (`R/.agents/notes/README.md`).

## 2. Licences et provenance (à lire avant tout)

- Projet : MIT, © 2026 DeepSeek (`R/LICENSE`). Lanceur Landlock natif : BSD-3 (`R/native/landlock-run/LICENSE`). Vendored Cordis/cosmokit/schemastery : MIT (`R/THIRD_PARTY_NOTICES.md`, section « Vendored source »).
- **« Official Claude Code platform payload »** (`R/THIRD_PARTY_NOTICES.md`, section du même nom) : le paquet optionnel `packages/subagent/subagent-claude-code` dépend de `@anthropic-ai/claude-agent-sdk@0.3.220` (licence « SEE LICENSE IN README.md », donc pas MIT) dont les `optionalDependencies` installent le **binaire officiel Claude Code 2.1.220** (logiciel propriétaire d'Anthropic, licence « SEE LICENSE IN LICENSE.md »). Le texte précise lui-même que cette autorisation « ne classe pas leurs termes comme permissifs ». Conséquence pour nous : la licence MIT du dépôt **ne couvre pas** ce SDK ni ce binaire ; Altrex ne doit ni les embarquer ni les redistribuer, ni s'en inspirer au niveau du code. Le dépôt téléchargé ne contient pas ces binaires (aucun `node_modules`), seulement le wrapper MIT (`R/packages/subagent/subagent-claude-code/README.md`). Recommandation : ne pas creuser ce paquet ni ses tests (`tests/real-product.spec.ts`).
- Éléments calqués sur Claude Code / Codex (réimplémentation d'interfaces, pas du code copié d'après ce que j'ai lu) : pont de hooks qui exécute le `hooks.json` de Claude Code et compte « 23 of Claude Code's current 30 » événements non gérés (`R/packages/hooks/hooks-claude-code/README.md`, `R/packages/hooks/hook-protocol/README.md`) ; constantes « matches Claude Code's GlobTool limit / GrepTool head_limit / ripgrep raw buffer » (`R/packages/fs/tool-fs-search/README.md` l.28-31) ; vocabulaire « Claude Code dynamic-workflows meta block » (`R/docs/subsystems/workflow.md` l.41) ; prompt du mode plan qui « emprunte » à Codex (`R/.agents/notes/archived/feature/2026-07-07-plan-mode.md` l.154). À ne pas reprendre tel quel : ces constantes et ce vocabulaire semblent venir de l'observation du produit propriétaire ; Forge définit les siens.
- Dépendances non permissives, outillage de dev seulement : `eslint-plugin-sonarjs` (LGPL-3.0), `lightningcss` (MPL-2.0) (`R/THIRD_PARTY_NOTICES.md`). Sans effet pour nous (on ne copie rien).
- Marque : « DeepSeek Harness » est une marque déposée ; ne pas l'utiliser dans un nom ni utiliser ses visuels officiels (`R/BRAND_GUIDELINES.md`, `R/packages/client/ui-brand-official/README.md`).
- Politique retenue : on prend **des idées et des schémas de conception**, on ne copie aucun code, aucun prompt mot pour mot.

## 3. Idées utiles, par sujet

### Autorisations et sandbox
- **Deux axes séparés plutôt qu'un seul « mode »** : sandbox (`read-only` / `workspace-write` / `danger-full-access`) et politique d'approbation (`ask` / `never`), combinés en « presets » affichés comme un seul sélecteur ; si la combinaison ne correspond à aucun preset, l'état affiché est `custom` (`R/docs/subsystems/permission-presets.md`). La note de décision rejette explicitement le `permissionMode` unique « façon Claude Code » (`R/.agents/notes/archived/feature/2026-07-07-plan-mode.md` l.160). Pour Forge : garder les 4 puces (Demander / Éditions auto / Plan / Tout autoriser) comme presets, mais les implémenter en interne sur 2 ou 3 axes (écriture fichiers, commandes, approbation). → étape 1.
- **Approbation fermée et « fail-closed »** : résultats `allowed-once | rejected | cancelled | unavailable` ; pas de réponse, réponse invalide ou UI absente = refus. La demande ne recopie pas les arguments, elle pointe le `callId` de la carte d'outil déjà affichée (`R/docs/subsystems/approval.md`). Pour Forge : si l'appli passe en arrière-plan pendant une demande, on refuse ou on met en attente, on n'autorise jamais par défaut. → étape 1.
- **Escalade ponctuelle** : une commande refusée par la sandbox renvoie des faits séparés (`denied`, `runnerFailed`, `enforcement`) ; le modèle peut demander un nouvel essai plus large avec une `justification`, que l'humain doit valider pour cet appel précis (`R/docs/subsystems/shell.md` l.140-165). → étape 1 (carte « Autoriser une fois »).
- **Enforcement déclaré et sondé** : la sandbox dit si elle protège `full` ou `partial`, et refuse de lancer si elle ne peut rien garantir (`R/docs/subsystems/sandbox.md`, `R/native/landlock-run/README.md`). Pour Forge : PRoot n'est pas une frontière de sécurité ; l'écran doit le dire honnêtement (« le modèle voit `/root/projets` » est une convention de montage, pas un cloisonnement noyau). Landlock exige Linux 5.13+ (`R/native/landlock-run/README.md`) : à vérifier sur le noyau du téléphone, ne pas compter dessus.
- **Lecture avant écriture avec jeton de version** : `edit`/`write` refusent si le fichier n'a pas été lu (`FS_NOT_OBSERVED`) ou a changé depuis (`FS_STALE_VERSION`), écriture atomique (`R/docs/subsystems/filesystem.md` l.116-151, `R/packages/fs/tool-fs/README.md` l.15). Capital pour Forge car toi et le modèle partagez le dossier via le Terminal. → étape 1.

### Mode plan
- Plan = **consigne douce** (section de prompt), la sécurité vient des autorisations, pas du plan ; la **liste d'outils ne change pas** entre modes pour garder le cache de prompt ; sortie par un outil `exit_plan_mode` qui exige un plan Markdown complet commençant par `#`, présenté pour validation ; un « oui » en conversation n'approuve rien (`R/docs/subsystems/plan.md`, `R/apps/cli/config/agent-presets/code/agent.cordis.yml` l.112-140). Pour Forge : la spec veut un Plan en lecture seule stricte ; combiner les deux (consigne + refus des outils d'écriture côté `AgentShell`), garder le catalogue d'outils stable, carte « Plan » avec Valider / Continuer à planifier. → étape 1.

### Sessions, reprise, mémoire
- **Session = journal append-only d'événements** ; l'historique envoyé au modèle, la todo, le mode plan, la politique d'approbation sont tous des « folds » purs du journal ; reprise et fork = rejouer (`R/docs/subsystems/session.md` l.1-40, l.536-548). Fork seulement à une frontière de tour. → étape 2 (sessions/reprise) ; idée de structure dès l'étape 1.
- **Récupération après crash** : un tour ouvert sans fin est fermé par un `turn/end { interrupted }` synthétique au lieu d'être tronqué (`R/docs/subsystems/persistence.md`). Exactement le cas d'Android qui tue l'appli. → étape 1 (avec le service de premier plan).
- **Mémoire projet (`AGENTS.md`)** : fichier global puis chaîne de fichiers de la racine au dossier courant, injectés dans un cadre `<system-reminder>` ; fichiers imbriqués découverts quand le modèle touche un sous-dossier ; mise à jour/suppression signalées ; dédoublonnage par empreinte SHA-1 ; **le texte `</system-reminder>` dans le fichier est échappé** pour qu'un dépôt ne puisse pas fermer le cadre (`R/packages/context/agent-instructions/README.md`). Pour `ALTREX.md`. → étape 1 (racine + global), étape 2 (imbriqués).
- **Mentions `@`** : `@chemin` reconnu seulement en début ou après un espace (pas dans un e-mail) ; sélectionner un fichier n'en injecte pas le contenu, le modèle doit le lire (`R/packages/context/file-reference/README.md`). → étape 2.
- **Compaction en deux niveaux** : élagage bon marché des vieux résultats d'outils (seuil 8192 caractères, garder 4096 au début et 1024 à la fin) avant le résumé par LLM (`R/apps/cli/config/agent-presets/code/agent.cordis.yml` l.150-170) ; le résumé est encadré par un verrou `start/end` pour détecter un crash au milieu (`R/docs/subsystems/compaction.md`). Très adapté aux petits contextes (Argora). → étape 2.
- **Spill** : une sortie d'outil trop grosse est écrite dans un fichier et le modèle reçoit un chemin + une consigne pour la relire (`R/docs/subsystems/spill.md`). → étape 1 (sorties `npm install`, logs).
- Ce que dsh **n'a pas** : points de retour de fichiers / git / rewind (aucune mention trouvée, `grep rewind|checkpoint|worktree`). Les checkpoints Git de Forge sont à concevoir nous-mêmes.

### Boucle d'agent et outils
- **Pipeline d'outil explicite** : `tool/call` journalisé avant exécution → carte « en attente » → pré-exécution (hooks, autorisation) → approbation → exécution (timeout) → post-exécution (bloquer, remplacer, ajouter du contexte) → `tool/result` → carte « terminée » (`R/docs/tool-execution-pipeline.md`, diagramme mermaid). Bon modèle pour les cartes repliables et pour placer les hooks plus tard. → étape 1 (structure), étape 4 (hooks).
- **Anti-boucle** : compte les appels identiques consécutifs (même outil, mêmes arguments normalisés) et injecte un rappel gradué aux seuils 3/5/8 ; `todo_write` est transparent ; les appels refusés comptent (`R/packages/guard/repeat-tool-reminder/README.md`). Indispensable pour un petit modèle local. → étape 1.
- **`todo_write` liste entière à chaque appel**, statuts `pending|in_progress|completed`, rejet des doublons et champs inconnus, option « une seule tâche en cours » ; la liste vit dans le journal (dernier gagne) et l'UI la lit de là (`R/packages/todo/tool-todo/README.md`). → étape 1.
- **Questions à l'utilisateur avec options** (label + description), « intent » de présentation optionnelle (`R/docs/subsystems/user-questions.md`). Idéal pour l'écran tactile. → étape 1.
- **MCP** : outils nommés `mcp__<serveur>__<outil>` (`R/packages/mcp/mcp-client/README.md`). → étape 2.
- **web_fetch** : ne suit que les redirections de même origine, sinon nouvel appel obligatoire (`R/packages/web/web-fetch-http/README.md` l.22). → étape 2.
- **Presets d'agent par session** (dossier = prompt + outils), plusieurs agents différemment composés dans le même processus (`R/packages/preset/README.md`, `R/apps/cli/config/agent-presets/`). Pour Forge : « mode apprentissage » et « mode construction » = presets (persona + sections de prompt + outils), pas des branches de code. → étape 4 (touche perso), conception dès l'étape 1.

### Longues tâches, mode construction, sous-agents
- **Goal** : objectif durable dans la session, rounds automatiques plafonnés (`maxGoalRounds`), le modèle doit prouver avant de marquer `complete`, peut déclarer `blocked` avec raison ; les messages humains passent toujours avant (`R/packages/goal/goal-round-driver/README.md`, `R/packages/goal/tool-goal/README.md`). → base du « mode construction » (étape 4, ou 2 si simple).
- **Ralph** : une suite d'agents neufs sur un objectif fixe ; **le dossier de travail est la mémoire long terme** ; chaque round rend un rapport structuré `continue|complete|blocked` + résumé + preuves + prochaines étapes, validé et borné ; le résultat dit que c'est l'avis du worker, pas une certification (`R/packages/workflow/tool-ralph/README.md`). Très bon pour un téléphone : contexte court par round, reprise facile après coupure. → étape 4.
- Sous-agents `spawn` (sans contexte parent) et `fork` (avec), avec mode arrière-plan contrôlé par jobs (`R/apps/cli/config/agent-presets/code/agent.cordis.yml` l.176-235). → étape 4.

### UX
- **Rangée « fichiers produits »** en fin de tour : puces des fichiers créés/modifiés, calculée depuis les outils de modification et non depuis le texte du modèle ; dans la réponse, un nom de fichier en code inline devient cliquable seulement s'il est non ambigu (`R/packages/client/ui-deliverables/README.md`). Parfait pour l'écran étroit. → étape 2.
- **Vue « trajectoire »** : registre tour/étape/outil avec inspecteur (jetons, durée, entrée/sortie) (`R/packages/client/ui-trajectory/README.md`). Utile pour déboguer et pour le mode apprentissage. → étape 4.
- Le contexte d'exécution (URL, port, mode du serveur) doit être visible du modèle : post-mortem où l'agent a cru qu'un HTTP 200 voulait dire « ça marche » alors que la page était blanche (`R/docs/postmortem/0003-web-agent-gui-feedback-loop.md`). Pour l'Aperçu : donner au modèle le port réel et une vraie vérification (rendu de la WebView, console), pas un simple 200. → étape 3.

### Évaluation et tests
- « Benchmark » très mince : seulement « utiliser le SDK Python, un dossier et une session par tâche » (`R/BENCHMARK.md`). Rien à reprendre comme suite de tests.
- Politique de tests précieuse (`R/docs/testing.md`) : **vérifier le monde, pas ce que l'agent dit** (relancer la commande, relire le fichier, vérifier que les fichiers non concernés sont identiques à l'octet) ; tests de fumée avec un vrai modèle ; scénarios rejoués depuis des journaux de session enregistrés (snapshot). Pour Forge : une petite batterie de tâches (« ajoute un formulaire », « corrige ce test ») vérifiées par des scripts dans le Linux, rejouables sur chaque modèle (cloud vs Argora). → étape 1 (2-3 tâches), étoffer ensuite.
- `R/docs/defensive-patterns.md` : rapporter les faits indépendants séparément (`timedOut`, `signal`, `exitCode`), sinon un timeout ressemble à un succès. → étape 1 (`run_command`, `check_job`).

### Docs et marque
- Chaque README de paquet a une section **« Model Experience »** (ce que le modèle voit exactement, coût en jetons, effet sur le cache) et **« Known Limitations and Deferred Work »**, vérifiées par scripts (`R/docs/cookbook/adding-a-package.md` l.18-85). Bonne pratique pour documenter chaque outil de Forge.
- Agent Notes : décisions rangées par cycle de vie (`proposed/implemented/rejected/archived`) et classe (`R/.agents/notes/README.md`). Post-mortems numérotés (`R/docs/postmortem/`).
- Paires de traduction suivies par empreinte git (`R/README.i18n.yaml`) : utile si Altrex documente en fr + en un jour.
- Marque : règles courtes « décrire la relation, ne pas utiliser le nom dans un nom de projet » (`R/BRAND_GUIDELINES.md`) ; modèle possible pour la marque Altrex/Forge.

## 4. À ne pas copier

- Tout le paquet `subagent-claude-code` et le SDK/binaire Claude Code (propriétaire, voir §2). Idem pour imiter le format `hooks.json` de Claude Code : définir un format de hooks Forge.
- Les constantes « comme Claude Code » (`R/packages/fs/tool-fs-search/README.md`) : choisir nos propres limites selon la RAM du téléphone.
- `extensions` / `tool-cordis` : le modèle écrit et monte des plugins dans le processus hôte ; leur doc dit « not a security boundary » (`R/packages/extensions/tool-cordis/README.md` l.23, l.102). Dangereux sur un téléphone avec données perso.
- Code Mode / `run_code` (le modèle écrit un programme TypeScript qui appelle les outils ; isolation par worker thread, « not a security claim ») (`R/packages/code-runtime/code-runtime/README.md` l.15, l.37). Trop lourd et peu sûr, et inutile pour un petit modèle.
- Équipes d'agents expérimentales (même checkout partagé, pas de verrou) (`R/packages/experimental/agent-team/README.md` l.72).
- La complexité générale : framework Cordis, HMR, Typert, couverture 100 % par fichier, tests multi-OS, Windows ACL/Seatbelt. Hors de proportion pour une appli Kotlin ; garder les concepts, pas l'architecture plugin.
- Toute marque ou visuel DeepSeek.

## 5. Correspondance avec les étapes de Forge

| Étape | Idées |
|---|---|
| 1 Fondations | presets sur axes séparés ; approbation fail-closed + escalade une fois ; lecture-avant-écriture avec version ; mode plan (catalogue stable + `exit_plan_mode`) ; `todo_write` liste entière ; questions à options ; anti-boucle 3/5/8 ; spill des grosses sorties ; journal append-only + tour « interrupted » ; `ALTREX.md` global + racine avec échappement ; faits séparés pour commandes ; 2-3 tâches d'éval « vérifier le monde » |
| 2 Confort | sessions/reprise/fork par rejeu ; compaction en 2 niveaux ; `ALTREX.md` imbriqués ; `@` sans injection ; MCP `mcp__x__y` ; web_fetch même origine ; rangée « fichiers produits » ; (checkpoints Git : à concevoir, dsh n'a rien) |
| 3 Aperçu/scan | port et mode réels visibles du modèle ; vérifier le rendu, pas le 200 (post-mortem 0003) |
| 4 Sous-agents, hooks, touche perso | pipeline d'outil comme points de hooks ; sous-agents spawn/fork ; goal + rounds Ralph pour le mode construction ; presets pour apprentissage/construction ; vue trajectoire |
