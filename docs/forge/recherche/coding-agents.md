# Forge : ce qu'on retient de 8 agents de code open source

Étude en lecture seule (git clone --depth 1, puis ls/grep/sed/cat ; rien exécuté, rien installé, rien copié).
Chaque clone a été supprimé après sa note. Les chemins cités sont relatifs à la racine de chaque dépôt.
Complète `docs/forge/recherche/deepseek-harness.md` et `autogen-reach-runner.md` (pas de redite :
deux axes sandbox/approbation, fail-closed, lecture avant écriture, journal append-only, compaction 2 niveaux,
spill, ALTREX.md en chaîne y sont déjà).

Étapes Forge : 0 look, 1 fondations, 2 confort, 3 aperçu/scan/Blueprint, 4 sous-agents/hooks/modes perso.

---

## 1. Aider (Aider-AI/aider)

- **Licence** : Apache-2.0 (`LICENSE.txt`, seul fichier de licence du dépôt). Les requêtes tree-sitter
  `aider/queries/tree-sitter-languages/*.scm` sont des versions modifiées des `tags.scm` des grammaires
  (MIT pour la plupart, liste dans `aider/queries/tree-sitter-languages/README.md`) ; celles de
  `aider/queries/tree-sitter-language-pack/` renvoient aux licences de chaque grammaire
  (`aider/queries/tree-sitter-language-pack/README.md`). On ne les copie pas ; si un jour on en a besoin,
  les prendre à la source (dépôts tree-sitter officiels, MIT) avec leur mention.
- **Ce que c'est** : agent de code en ligne de commande (Python), « pair programming » dans le terminal,
  centré sur git (un commit par modification) et sur une carte du dépôt envoyée au modèle.

### Idées à prendre

**Carte du dépôt (repo map)** — `aider/repomap.py`
- Extraction : pour chaque fichier, tree-sitter parse le source et une requête `.scm` par langage
  capture des noms `name.definition.*` (def) et `name.reference.*` (ref) (`get_tags_raw`, l.279-363).
  Si un langage ne donne que des définitions, les références sont complétées par le lexer Pygments
  (même fonction, l.338-363).
- Cache : par fichier, clé = chemin, invalidé par `mtime`, stocké en SQLite via `diskcache`
  (`get_tags`, l.233-264 ; `TAGS_CACHE_DIR`, l.43) avec repli en mémoire si SQLite casse
  (`tags_cache_error`, l.177-215).
- Classement : graphe fichier→fichier (référenceur → définisseur) pondéré, puis PageRank `networkx`
  personnalisé (`get_ranked_tags`, l.365-560). Pondérations lues : ×10 si l'identifiant est cité par
  l'utilisateur, ×10 si identifiant long en snake/camel (≥ 8 car.), ×0,1 si commence par `_`,
  ×0,1 si défini dans > 5 fichiers, ×50 si le référenceur est un fichier « dans la conversation »,
  racine carrée du nombre de références (l.488-516). Personnalisation : fichiers de la conversation
  et fichiers/chemins mentionnés (l.374-445).
- Budget : recherche dichotomique sur le nombre de définitions pour tenir dans `map_tokens`
  (défaut 1024, l.49 ; boucle l.667-703), budget agrandi quand aucun fichier n'est ouvert
  (`map_mul_no_files`, l.120-132).
- Rendu : extraits « arbre » (lignes de définition avec leur portée parente) via `grep_ast.TreeContext`
  (`to_tree`, l.748+).
- **Pour Forge** : excellent pour un petit contexte (Argora 2B/4B, 2048-4096 jetons). Version Forge
  réaliste sur téléphone : **sans tree-sitter au départ** — liste des fichiers + symboles extraits par
  `ctags`-like regex ou par `rg` sur motifs de définition par langage, classement simple (fichiers
  ouverts, fichiers cités, nombre de références `rg -c`), budget en jetons par dichotomie. Tree-sitter
  ensuite, côté Kotlin/JNI ou dans le Linux. → étape 2 (version simple), étape 4 (graphe + PageRank).
- **Ce qu'il faut sous Alpine arm64** : Aider s'appuie sur les paquets Python `tree-sitter`,
  `tree-sitter-language-pack`, `grep-ast`, `networkx` (+ scipy indiqué en commentaire),
  `diskcache`, `pygments` (`requirements/requirements.in` l.9, l.34-40 ; `requirements.txt` l.78,
  120, 217, 413). Tree-sitter = bibliothèque C + une grammaire compilée par langage : sur musl/arm64
  il faut des roues musllinux-aarch64 ou une compilation (gcc, python3-dev) — lourd sous PRoot.
  Alternative : bibliothèque tree-sitter compilée dans l'APK (NDK) avec quelques grammaires.
  ripgrep : binaire Rust statique, paquet `ripgrep` dans Alpine (aarch64) ; aucune dépendance
  d'exécution. (Constat sur les besoins, rien n'a été lancé.)

**Git : un commit par modification, `/undo` sûr** — `aider/repo.py`, `aider/commands.py`, `aider/coders/base_coder.py`
- Avant d'éditer un fichier déjà modifié par l'utilisateur, Aider commite d'abord **les changements de
  l'utilisateur** séparément (« dirty commit ») pour que l'annulation ne mange pas son travail
  (`check_for_dirty_commit`, `dirty_commit`, base_coder.py l.2174-2190 et 2415+).
- Chaque série d'éditions du modèle = un commit ; le hash est mémorisé dans `aider_commit_hashes`
  (`show_auto_commit_outcome`, base_coder.py l.2397-2403) ; le message de commit est généré par un
  modèle faible (repo.py, `commit`).
- `/undo` est **gardé** : refuse si le dernier commit n'est pas un commit de l'agent de cette session,
  si c'est un merge, si un fichier touché a des changements non commités, si un fichier n'existait pas
  avant, ou si le commit est déjà poussé sur `origin` ; restaure fichier par fichier puis
  `reset --soft HEAD~1` (commands.py `cmd_undo`, l.~555-650).
- `/diff` = diff depuis le HEAD d'avant le dernier message utilisateur (`commit_before_message`,
  base_coder.py l.874 ; commands.py `raw_cmd_diff` l.657-692).
- Attribution configurable (auteur « (aider) », committer, ou trailer Co-authored-by ;
  repo.py l.62-260).
- **Pour Forge** : reprendre les 5 garde-fous de `/undo` et le « commit des changements de
  l'utilisateur avant » (vous partagez le dossier avec le Terminal). Mais Forge ne doit pas polluer
  l'historique de l'utilisateur à chaque édition : préférer des instantanés hors branche (voir Cline,
  dépôt git fantôme) et un commit réel seulement sur demande. → étape 2.

**Boucle lint/test automatique** — base_coder.py l.1596-1622, `aider/linter.py`
- Après édition : lint des fichiers modifiés ; si erreurs, on demande « corriger ? » et la sortie
  devient le message suivant (« reflection ») ; idem pour la commande de test ; plafond
  `max_reflections = 3` (base_coder.py l.101, l.936-944).
- Lint de base **sans outil externe** : tree-sitter détecte les nœuds `ERROR`/manquants
  (`basic_lint`, `traverse_tree`, linter.py l.201-268) ; le résultat est présenté avec le contexte
  syntaxique et les lignes marquées (`tree_context`, l.234-257) ; commande de lint par langage
  configurable (`set_linter`, l.31).
- **Pour Forge** : boucle « vérifier après écrire » avec plafond ; le mode Build en a besoin pour
  sa « preuve ». Afficher la sortie du linter avec les lignes concernées marquées. → étape 2 (Build v1).

**Architecte / éditeur** — `aider/coders/architect_coder.py`
- Un modèle « architecte » décrit les changements en prose ; après confirmation « Edit the files? »
  (sauf auto-accept), un second agent « éditeur », avec un autre modèle, sans carte du dépôt et sans
  commandes shell, applique la description ; seul le résultat revient (l.11-48).
- **Pour Forge** : modèle cloud fort pour planifier + Argora local pour éditer, ou l'inverse. C'est
  déjà le mode Plan → exécution ; la nouveauté est le **changement de modèle** entre les deux et
  l'éditeur sans outils superflus. → étape 4 (sous-agent éditeur).

**Robustesse des éditions pour petits modèles** — `aider/coders/editblock_coder.py`
- Remplacement tolérant : exact, puis en ignorant l'indentation de tête, puis gestion des `...`
  élidés, puis similarité (`replace_most_similar_chunk` l.157-188, `replace_part_with_missing_leading_whitespace`
  l.243, `replace_closest_edit_distance` l.296).
- En cas d'échec, le message renvoyé au modèle donne **les lignes réelles les plus proches**
  (« Did you mean… », `find_similar_lines` l.602, usage l.52-70).
- **Pour Forge** : `Edit` refuse un remplacement non exact (sécurité), mais l'erreur doit renvoyer les
  lignes proches : gros gain pour Argora. Tolérance d'indentation possible avec confirmation. → étape 1.

**Divers**
- Fichiers « lecture seule » dans le contexte distincts des fichiers éditables (`cmd_read_only`,
  commands.py l.1328). → étape 2 (`@fichier` en lecture seule).
- Mode « surveillance » : commentaires `AI!` / `AI?` dans un fichier édité ailleurs déclenchent
  l'agent (`aider/watch.py` l.69, l.205). Idée amusante pour l'éditeur de Forge, pas prioritaire.

### À éviter
- Formats d'édition multiples (`aider/coders/` : editblock, udiff, whole, patch… ~35 fichiers) :
  Forge a des outils natifs `Edit`/`Write` ; inutile.
- Dépendances Python lourdes (scipy/networkx/tree-sitter) dans PRoot : trop coûteux à installer.
- Commit automatique dans la branche de l'utilisateur à chaque édition (bruit dans son historique).

---

## 2. Cline (cline/cline)

- **Licence** : Apache-2.0 (`LICENSE` ; `apps/vscode/LICENSE` aussi Apache-2.0). Palette UI dérivée de
  Radix Colors (MIT, `sdk/packages/ui/README.md` l.187). Aucune mention de dérivation de logiciel propriétaire trouvée.
- **Ce que c'est** : agent de code (extension VS Code historique + CLI + « hub »), réorganisé en SDK
  TypeScript (`sdk/packages/{shared,llms,agents,core,ui}`, `sdk/ARCHITECTURE.md`). Plan/Act, checkpoints,
  règles `.clinerules`, skills, workflows, hooks, MCP, sous-agents.

### Idées à prendre

**Checkpoints sans toucher à la branche de l'utilisateur** — `sdk/packages/core/src/hooks/checkpoint-hooks.ts`, `sdk/packages/core/src/session/checkpoint-restore.ts`
- Un instantané **par tour utilisateur** (`CheckpointEntry { ref, createdAt, runCount, kind: stash|commit }`,
  checkpoint-hooks.ts l.80-90), créé avec `git stash create` (n'altère ni l'arbre de travail ni la liste
  de stash visible) ; les fichiers non suivis (mais non ignorés) sont ajoutés comme **3e parent** grâce à un
  index git privé (`GIT_INDEX_FILE`) gardé dans un dossier de données par session (l.25-56, l.243-337, l.341-430).
- L'objet reste atteignable par une **référence privée** `refs/cline/checkpoints/<session>/<n>` (l.~430-470,
  l.~625-640) ; nettoyage à la suppression de la session et par âge (14 jours, l.19, l.57).
- Repli : arbre propre → entrée « HEAD » ; télémétrie de l'issue (`stash | head_clean | head_fallback | skipped`).
- Restauration en **transaction** : avant de restaurer, sauvegarde de l'état actuel (y compris non suivis)
  sous une ref privée, rollback si échec (`beginWorktreeRestoreTransaction`, checkpoint-restore.ts l.43-150).
- **Refus** si HEAD a bougé depuis l'instantané (commits faits ensuite, rebase, changement de branche) :
  « restaure seulement la conversation, ou remets la branche » ; déplacement de HEAD par compare-and-swap
  `update-ref <new> <old>` (l.403-458). `git clean -fd` seulement si l'instantané contenait les non-suivis,
  et jamais sur les fichiers ignorés (`node_modules`, `.env`) (l.461-470).
- 3 choix de restauration : **Fichiers** / **Conversation seule** / **Fichiers + conversation**, et bouton
  **Comparer** sur chaque point de contrôle (`docs/core-workflows/checkpoints.mdx` l.40-74) ; éditer un ancien
  message propose de restaurer les fichiers à ce point (l.91-93).
- **Pour Forge** : c'est le meilleur modèle pour « instantané automatique avant chaque série de
  modifications » + `/undo` : l'historique git de l'utilisateur reste propre (pas de commit par édition comme
  Aider), le Terminal partagé voit un dépôt normal. Limite : si le projet n'est pas un dépôt git, Cline
  désactive les checkpoints (l.~88-103) ; Forge proposera `git init` une fois, ou utilisera le dépôt fantôme de
  Roo Code (section 3). → étape 2 (le plus rentable de l'étude). Sous PRoot : seulement le binaire `git` d'Alpine.

**Plan / Act** — `apps/cli/src/runtime/interactive/mode.ts`, `sdk/packages/core/src/extensions/tools/presets.ts`, `.../command-guard.ts`
- Le passage en Act par l'outil `switch_to_act_mode` vaut approbation du plan **seulement** si l'outil l'a
  demandé ; un basculement d'interface (`source: "ui"`) ne lance jamais l'exécution (mode.ts l.5-30, l.60-80).
  L'outil termine le tour (`completesRun`), puis une relance automatique « continue avec le plan approuvé »
  est envoyée comme message synthétique masqué (l.24-29, l.48-55).
- Cline **change** le jeu d'outils selon le mode (preset `plan` : pas d'éditeur, presets.ts l.20-60), mais garde
  le shell en Plan avec une **liste noire** de commandes d'écriture + refus des redirections `>` (command-guard.ts
  l.1-20) ; le commentaire admet que les modèles faibles éditent par le shell et que la liste n'est pas étanche.
- Modèle différent pour Plan et pour Act (`docs/core-workflows/plan-and-act.mdx` l.63-80).
- **Pour Forge** : (1) garder notre choix « liste d'outils stable » (meilleur pour le cache), mais **ajouter
  le garde-commande** en Plan : la permission lecture seule doit aussi couvrir `Bash` (refuser `rm`, `mv`,
  `sed -i`, `>`, `tee`, gestionnaires de paquets…) avec motif ; (2) la distinction « approbation par bouton
  vs bascule » rejoint la règle « un oui ne vaut pas validation » ; (3) modèle par mode (cloud pour planifier,
  Argora pour agir). → étape 1 (garde-commande), étape 2 (modèle par mode).

**Approbations** — `apps/vscode/src/shared/AutoApprovalSettings.ts`, `docs/features/auto-approve.mdx`
- Approbation par **catégorie d'action** : lire, éditer, commandes « sûres », toutes commandes, navigateur, MCP
  (AutoApprovalSettings.ts l.13-25) ; c'est le **modèle** qui marque une commande « sûre » ou « à approuver »
  (`docs/features/auto-approve.mdx` l.28, l.49-60). Notification système quand une approbation attend, et quand
  une commande auto-approuvée tourne depuis 30 s (l.29). Mode « YOLO » = tout approuver (l.73-117).
- **Pour Forge** : la notification « une approbation t'attend » est indispensable sur téléphone (service de
  premier plan). Ne pas faire confiance au marquage « sûre » du modèle : l'heuristique doit être côté Forge
  (liste blanche de commandes en lecture). → étape 1.

**Règles conditionnelles par chemin** — `docs/customization/cline-rules.mdx` l.170-240, `sdk/packages/core/src/extensions/config/user-instruction-config-loader.ts`
- Un fichier de règles avec en-tête YAML `paths: [globs]` ne s'active que si un fichier en contexte correspond ;
  sans en-tête = toujours actif ; `paths: []` = désactivé ; YAML invalide = **actif quand même** avec le texte
  brut visible (fail-open pour déboguer) ; notification « règles conditionnelles appliquées : … ».
- Trois types unifiés : `skill`, `rule`, `workflow` (même chargeur, en-tête YAML, `disabled`) (loader l.53-85) ;
  lit aussi `AGENTS.md` à la racine (l.563-581).
- **Pour Forge** : `ALTREX.md` + un dossier `.altrex/regles/*.md` avec `chemins:` ; un « workflow » = une commande
  `/` écrite en Markdown (base des commandes `/` à toi, étape 4). → étape 2 (règles), étape 4 (workflows).

**Ignorer ≠ sécurité** — `docs/customization/clineignore.mdx` l.1-15
- Cline reconnaît que `.clineignore` ne filtrait que le chargement automatique (lecture possible par `@` ou shell)
  et le remplace par un hook `PreToolUse` qui bloque vraiment. **Pour Forge** : un éventuel `.altrexignore` doit
  être appliqué dans les outils (Read/Grep/Glob/Bash), sinon ne pas le présenter comme une protection. → étape 2.

**UX des cartes** — `apps/vscode/webview-ui/src/components/chat/`
- Une carte par type : `DiffEditRow.tsx`, `CommandOutputRow.tsx`, `ThinkingRow.tsx`, `CompactionRow.tsx`,
  `SubagentStatusRow.tsx`, `PlanCompletionOutputRow.tsx`, `HookMessage.tsx`.
- Diff : en-tête fichier + **compteurs `+N · -M`** colorés (DiffEditRow.tsx l.168, l.205-210), dépliable,
  défilement qui **suit** le flux pendant l'écriture tant que l'utilisateur ne remonte pas (l.75-93).
- Sortie de commande : poignée « déplier » seulement au-delà de 5 lignes (CommandOutputRow.tsx l.108-109) ;
  exécution en arrière-plan signalée.
- **Pour Forge** : exactement le flux à cartes repliables de la maquette ; reprendre « suivre le flux sauf si
  l'utilisateur fait défiler », « +N −M », « déplier au-delà de 5 lignes ». → étape 1.

### À éviter
- Le SDK entier (hub, Composio, Langfuse, cron, kanban, cloud handoff : `sdk/ARCHITECTURE.md` sections 1041+,
  1248+) : trop vaste pour un téléphone.
- Liste noire de commandes présentée comme une sécurité (elle-même dit qu'elle ne l'est pas).
- Laisser le modèle déclarer qu'une commande est sûre.
---

## 3. Roo Code (RooCodeInc/Roo-Code)

- **Licence** : Apache-2.0 (`LICENSE`, `apps/docs/LICENSE`). (Roo Code est historiquement un fork de Cline,
  lui aussi Apache-2.0.)
- **Ce que c'est** : extension VS Code d'agent de code, organisée autour des **modes** (Architect, Code, Ask,
  Debug, Orchestrator + modes personnalisés), avec sous-tâches déléguées, checkpoints en dépôt fantôme, i18n
  (dont `package.nls.fr.json`).

### Idées à prendre

**Un mode = rôle + quand l'utiliser + consignes + groupes d'outils (avec restriction de fichiers)** — `packages/types/src/mode.ts`, `packages/types/src/tool.ts`, `src/shared/tools.ts`
- Schéma (`modeConfigSchema`, mode.ts l.97-106) : `slug` (lettres/chiffres/tirets), `name`, `roleDefinition`
  (obligatoire), `whenToUse`, `description`, `customInstructions`, `groups`, `source: global|project`.
- `groups` : liste de groupes parmi `read`, `edit`, `command`, `mcp`, `modes` (tool.ts l.7) ; une entrée peut être
  un couple `["edit", { fileRegex, description }]` qui **limite l'écriture** à certains fichiers (mode.ts l.7-35 ;
  exemple : Architect n'écrit que du `.md`, l.177). Le refus est une erreur explicite `FileRestrictionError` qui
  dit au modèle quels motifs sont permis (`src/shared/modes.ts` l.135 ; `src/core/tools/validateToolUse.ts`
  l.206-225).
- Groupes → outils (`TOOL_GROUPS`, src/shared/tools.ts l.296-315) ; outils **toujours disponibles** dans tous les
  modes : question, fin de tâche, changer de mode, nouvelle tâche, todo, commande `/`, skill (l.318-326).
- Fichiers : modes du projet dans `.roomodes` (YAML, JSON accepté en repli) qui **priment** sur les modes globaux
  `custom_modes.yaml` (`src/core/config/CustomModesManager.ts` l.19, l.101, l.157, l.212-301) ; règles propres à un
  mode dans `.roo/rules-<slug>/` (`src/core/prompts/sections/custom-instructions.ts` l.409-427).
- **Export/import d'un mode avec ses règles** en un YAML partageable (`exportModeWithRules`, `importModeWithRules`,
  CustomModesManager.ts l.709-716, l.926).
- **Modèle par mode** (`modeApiConfigs: slug → profil`, `src/core/config/ProviderSettingsManager.ts` l.30).
- **Pour Forge** : c'est le format à prendre pour les **modes personnalisés** et pour décrire nos modes
  **Apprentissage** et **Build** dans le même moule. Proposition Forge : `.altrex/modes/<slug>.md` avec en-tête
  YAML (`nom`, `quand`, `outils: [lire, éditer{chemins}, commandes, mcp]`, `autorisations` = une des 4 puces,
  `modele` facultatif) et le corps = consignes. Attention à notre règle « la liste d'outils ne change pas »
  (cache) : appliquer les groupes **à l'exécution** (refus avec motif comme `FileRestrictionError`) plutôt qu'en
  retirant les outils du prompt. Apprentissage = mode « surcouche » (consignes seulement, pas de restriction).
  → étape 2 (Apprentissage, Build via ce format interne), étape 4 (modes à toi, import/export).

**Orchestrateur et sous-tâches séquentielles** — `src/core/tools/NewTaskTool.ts`, `src/core/tools/AttemptCompletionTool.ts`, mode.ts l.215-230
- `new_task(mode, message, todos?)` : le parent est mis en pause et l'enfant devient **la seule tâche active**
  (`delegateParentAndOpenChild`, NewTaskTool.ts l.112-120) ; `todos` = liste Markdown à cocher, optionnellement
  exigée (l.45-80). L'enfant termine par `attempt_completion` dont le résultat **rouvre le parent** avec ce
  résumé (`reopenParentFromDelegation`, AttemptCompletionTool.ts l.28, l.171).
- Mode Orchestrator : **aucun groupe d'outils** (`groups: []`), il ne peut que déléguer (mode.ts l.224) ; ses
  consignes demandent un périmètre explicite, tout le contexte nécessaire, et un résumé final qui sert de
  « source de vérité » (à reformuler, pas à copier).
- **Pour Forge** : schéma idéal pour téléphone (une seule boucle active à la fois, pas de parallélisme) ; colle à
  « un sous-agent appelé comme un outil, ne renvoie qu'un résumé ». Le mode Build peut être un orchestrateur
  léger : cadrage → architecture → sous-tâches Code → passe d'amélioration. → étape 4 (moteur prêt dès l'étape 1).

**Checkpoints en dépôt git fantôme** — `src/services/checkpoints/ShadowCheckpointService.ts`, `excludes.ts`
- Dépôt git séparé (dans le stockage de l'extension) avec `core.worktree` = dossier du projet : marche **même si
  le projet n'est pas un dépôt git** et ne touche jamais au `.git` de l'utilisateur (l.129-191) ; variables
  `GIT_DIR`/`GIT_WORK_TREE` héritées **retirées** de l'environnement (l.20-71) ; exclusions écrites dans le
  `info/exclude` du fantôme + respect du `.gitignore` (l.209-218) ; liste d'exclusions par défaut : artefacts de
  build, médias, etc. (excludes.ts, ~160 motifs).
- Refus si un dépôt git **imbriqué** est détecté (l.134-145, l.230-280).
- Sauvegarde = `commit` (optionnellement vide) ; restauration = `clean -f -d` + `reset --hard <hash>` et
  suppression des checkpoints postérieurs (l.295-370).
- **Pour Forge** : complémentaire de Cline. Forge peut choisir : dépôt fantôme par projet (simple, marche sans git,
  coût disque) vs refs privées dans le vrai dépôt (Cline, plus économe mais exige un dépôt). Sur téléphone :
  fantôme dans le stockage d'Altrex, exclusions strictes (`node_modules`, `build/`, médias) sinon le disque gonfle.
  → étape 2.

**Petits garde-fous utiles aux petits modèles**
- **Alias de noms d'outils** : `write_file` → `write_to_file`, etc. ; l'alias est résolu à l'exécution
  (`TOOL_ALIASES`, src/shared/tools.ts l.328-341). Pour Argora qui se trompera de nom (`read_file` vs `Read`) :
  table d'alias Forge. → étape 1.
- Détecteur d'appels identiques consécutifs (limite 3 par défaut) (`src/core/tools/ToolRepetitionDetector.ts`
  l.9-55) : confirme notre rappel anti-boucle 3/5/8.

### À éviter
- Indexation sémantique `codebase_search` (embeddings + base vectorielle Qdrant : `src/services/code-index/`) :
  trop lourd pour PRoot ; un repo map à la Aider + `Grep` suffit.
- Retirer des outils du prompt selon le mode (casse le cache de prompt ; nous l'avons écarté).
- `restoreCheckpoint` avec `clean -f -d` sans sauvegarde préalable : Cline fait mieux (transaction).
---

## 4. Codex CLI (openai/codex)

- **Licence** : Apache-2.0 (`LICENSE`, `docs/license.md`) ; `NOTICE` : contient du code dérivé de Ratatui (MIT).
  Sous-éléments : `codex-rs/vendor/bubblewrap/LICENSE` = **GNU LGPL v2** (bubblewrap embarqué) ;
  `third_party/wezterm/LICENSE` = MIT ; `third_party/voice/licenses/` (LGPL-2.1, Opus, PCRE2, libffi, zlib…) ;
  skills d'exemple `codex-rs/skills/src/assets/samples/*/LICENSE.txt` = Apache-2.0. Rien n'indique une dérivation
  de logiciel propriétaire. On ne prend que des idées ; ne rien tirer de `vendor/bubblewrap` ni `third_party/voice`.
- **Ce que c'est** : agent de code d'OpenAI en Rust (`codex-rs/`, ~150 crates : tui, core, protocol, execpolicy,
  linux-sandbox, app-server…), avec bac à sable OS, politiques d'approbation, `AGENTS.md`. La doc du dépôt renvoie
  vers le site (`docs/sandbox.md`, `docs/agents_md.md`, `docs/execpolicy.md` = 3 lignes chacun) : notes tirées du code.

### Idées à prendre

**Vocabulaire des politiques** — `codex-rs/protocol/src/protocol.rs` l.1163-1330, `codex-rs/protocol/src/config_types.rs` l.104-115
- Approbation (`AskForApproval`) : `untrusted` (tout demande sauf règle explicite), `on-request` (le modèle demande
  quand il veut sortir du bac à sable ; alias historique `on-failure`), `granular` (interrupteurs par flux :
  escalade de bac à sable, règles, scripts de skills, outil de demande de permissions, questions MCP — un flux à
  `false` est **refusé automatiquement**, pas montré), `never` (les échecs reviennent au modèle, jamais à
  l'utilisateur).
- Bac à sable (`SandboxPolicy`) : `read-only {network_access}`, `workspace-write {writable_roots, network_access,
  exclure TMPDIR, exclure /tmp}`, `danger-full-access`, et **`external-sandbox`** = « je suis déjà dans un bac à
  sable externe ».
- **Sous-chemins protégés même en écriture** : `WritableRoot { root, read_only_subpaths, protected_metadata_names }`
  — `.git` (notamment `.git/hooks`) et le dossier de config de l'agent restent en lecture seule, car les modifier
  permettrait d'escalader (l.1300-1330).
- **Réseau** : un axe à part (`NetworkAccess restricted|enabled`).
- **Pour Forge** : DeepSeek Harness nous a déjà donné les 2 axes ; Codex ajoute (1) l'axe **réseau** séparé,
  (2) la liste de **sous-chemins protégés** (`.git/hooks`, `.altrex/`, `ALTREX.md` du global) même en « Éditions
  auto » — à appliquer dans nos outils `Write`/`Edit` (et à afficher comme limite honnête pour `Bash` sous PRoot),
  (3) le terme `external-sandbox` qui décrit **honnêtement** PRoot : « isolation fournie par l'hôte, non garantie ».
  → étape 1.

**Décisions d'approbation riches, refus par défaut** — protocol.rs l.4466-4515, `codex-rs/tui/src/bottom_pane/approval_overlay.rs` l.860-1060
- `ReviewDecision` : `approved` (une fois), `approved_execpolicy_amendment` (**toujours autoriser les commandes qui
  commencent par ce préfixe**, enregistré dans les règles), `approved_for_session`, `approved_mcp_policy_amendment`,
  `network_policy_amendment` (autoriser/refuser un hôte), `denied { rejection }` (continuer autrement, avec motif),
  `timed_out`, `abort` (arrêter jusqu'au prochain message). **Le défaut est `denied`** (l.4505-4511).
- Libellés de l'écran d'approbation : « Oui », « Oui, et ne plus demander pour les commandes qui commencent par
  `<préfixe>` », « Oui pour cette session », « Non, et dire à l'agent quoi faire autrement » ; pour les fichiers :
  « Oui, et ne plus demander pour ces fichiers » (approval_overlay.rs l.872-939, l.1045-1055).
- **Pour Forge** : carte d'approbation à 4 boutons max sur téléphone : **Oui** · **Toujours pour `npm test …`**
  (préfixe affiché et modifiable) · **Non, plutôt…** (champ texte → motif reçu par le modèle) · **Stop**. → étape 1.

**Règles d'exécution par préfixe** — `codex-rs/execpolicy/README.md`, `execpolicy/src/amend.rs`
- `prefix_rule(pattern, decision = allow|prompt|forbidden, justification, match, not_match)` ; alternatives par
  liste ; la décision la plus stricte gagne (`forbidden > prompt > allow`) ; la `justification` d'un `forbidden`
  propose une alternative (« utilise X au lieu de Y ») et est montrée au modèle ; les exemples `match`/`not_match`
  sont **vérifiés au chargement** comme des tests ; `host_executable` contraint les chemins absolus d'un binaire
  (README l.1-100). L'option « toujours autoriser » **ajoute une règle** au fichier (amend.rs l.63-85).
- **Pour Forge** : remplace la liste noire de Cline par un vrai petit langage, mais en **JSON/YAML** (pas Starlark) :
  `{ prefixe: ["npm","test"], decision: "autoriser", motif: "…" }` dans `.altrex/regles-commandes.json` + global.
  Le refus avec motif et alternative est précieux pour Argora. → étape 1 (règles simples), étape 4 (éditeur).

**Relecteur automatique des approbations (« Guardian »)** — `codex-rs/guardian-context/src/lib.rs`, `codex-rs/prompts/templates/guardian/`, `codex-rs/core/src/tools/approvals.rs` l.1-2, `core/src/tools/orchestrator.rs` l.1-3
- Un modèle relecteur évalue risque intrinsèque (`low`/…/`high`) et **autorisation par l'utilisateur** à partir
  d'un extrait borné de la conversation ; un relecteur asynchrone non bloquant peut déclencher une relecture
  bloquante pour la suite (classifier_instructions.md l.1-36, lu en structure seulement, non copié).
  « Les points de contrôle humains imposés ne peuvent pas être satisfaits par les hooks, la relecture automatique ou
  les approbations réutilisables » (approvals.rs l.2) ; une nouvelle tentative avec plus de droits exige une
  **nouvelle** relecture (orchestrator.rs l.2-3). Menu « Approuver pour moi » (`tui/src/chatwidget/permissions_menu.rs`).
- **Pour Forge** : c'est le « relecteur d'approbations » de l'étape 4. Garder les deux règles d'or : certaines
  demandes restent toujours humaines (installer, hors projet, réseau sortant) ; une escalade = nouvelle décision.

**`AGENTS.md`** — `codex-rs/core/src/agents_md.rs` l.1-150
- Racine du projet trouvée en remontant jusqu'à un **marqueur** (`.git` par défaut, configurable ; liste vide =
  pas de remontée) ; concaténation racine → dossier courant, sans dépasser la racine (l.7-18) ; fichier
  **`AGENTS.override.md`** local prioritaire (l.44-45) ; **noms de repli** configurables (l.3-6) ; **budget en
  octets** avec message « réduis tes consignes » (l.51, l.65-75) ; **projet non approuvé = consignes non chargées**
  (l.65).
- **Pour Forge** : `ALTREX.md` lit aussi `AGENTS.md`/`CLAUDE.md` comme replis (projets importés) ; budget strict
  pour Argora ; un projet cloné depuis Internet = « non approuvé » : on ne charge pas son `ALTREX.md`/règles tant que
  l'utilisateur ne l'a pas approuvé (écran « Faire confiance à ce dossier ? », `tui/src/onboarding/trust_directory.rs`
  l.83-147). → étape 1 (confiance + budget), étape 2 (replis).

**Plan = gabarit de prompt** — `codex-rs/collaboration-mode-templates/templates/{default,plan}.md`
- Le mode plan est un « mode de collaboration » = un gabarit d'instructions, pas un autre jeu d'outils. Confirme
  notre choix (outils stables, consigne + permissions).

**Diffs** — `codex-rs/tui/src/diff_render.rs` l.1-40
- Bloc par fichier (Ajout / Suppression / Modification), numéro de ligne aligné à droite, gouttière `+ - ␠`,
  coloration syntaxique **par hunk entier** (garde l'état du parseur sur les chaînes multi-lignes), couleurs de fond
  atténuées en sombre, retour à la ligne forcé à la largeur disponible. **Pour Forge** : sur téléphone, retour à la
  ligne obligatoire (pas de défilement horizontal), fond teinté plutôt que texte coloré seul. → étape 2.

### À éviter
- Le bac à sable Linux de Codex : bubblewrap + seccomp + Landlock (`codex-rs/linux-sandbox/README.md`) exige des
  **espaces de noms utilisateur** ; Codex refuse même WSL1 faute de ces espaces. Sous Android/PRoot (sans root) ce
  n'est pas disponible : ne pas promettre cette isolation. Bubblewrap embarqué = LGPL v2.
- Starlark pour les règles (interpréteur à embarquer) ; app-server, cloud-tasks, agent-message-board : hors sujet.
---

## 5. Plandex (plandex-ai/plandex)

- **Licence** : MIT, © 2025 PlandexAI Inc. (`LICENSE`, seul fichier de licence). Le service hébergé « Plandex Cloud »
  est en **arrêt progressif depuis le 3/10/2025** (`README.md` l.157) ; le code reste en auto-hébergement.
- **Ce que c'est** : agent de code pour grandes tâches (CLI Go + serveur Go + Postgres) ; les modifications
  s'accumulent dans un **bac à sable versionné côté serveur** et ne touchent les fichiers qu'au moment d'`apply`.

### Idées à prendre

**Revoir avant d'appliquer** — `docs/docs/core-concepts/reviewing-changes.md`, `app/cli/plan_exec/action_menu.go`, `app/cli/lib/apply.go`
- Par défaut, rien n'est écrit dans le projet : les changements sont « en attente » (reviewing-changes.md l.6-9).
  À la fin d'une tâche, **un menu unique** : voir les diffs (format git ou interface navigateur côte-à-côte /
  ligne-à-ligne), **rejeter certains fichiers** (sélection) ou tous, appliquer tout, appliquer + déboguer en
  automatique (action_menu.go l.30-75 ; reviewing-changes.md l.10-62).
- `apply` : confirmation « Appliquer les changements à N fichiers ? » (apply.go l.181), vérification préalable que
  les fichiers en contexte n'ont pas changé depuis (« outdated context », apply.go l.~120-130 ;
  `lib/context_conflict.go` `checkContextConflicts`).
- Les commandes proposées (installer, tester, lancer) sont regroupées dans un script en attente `_apply.sh`, montré
  en bloc et exécuté **après** confirmation « Exécuter maintenant ? » (apply.go l.140, l.254-300, l.332-400).
- **Retour arrière automatique** si ces commandes échouent : `ApplyRollbackPlan { ToRevert: chemin → (contenu,
  mode), ToRemove }` (types/apply.go l.25-38 ; `lib.Rollback` apply.go l.752) ; menu « Déboguer et réessayer une
  fois / Déboguer en automatique / Annuler les changements et quitter », avec un plafond d'essais
  (`plan_exec/apply_exec.go` l.26-165).
- **Pour Forge** : c'est le meilleur patron pour le **mode Build** et pour un futur réglage « Revoir à la fin » :
  l'agent écrit dans une **zone tampon** (copie de travail ou branche fantôme), Forge montre à la fin une
  **liste de fichiers à cocher** (+N −M par fichier, tap = diff plein écran), puis un bouton Appliquer ;
  les commandes de vérification tournent après l'application et un échec propose « corriger / annuler ».
  Très adapté au petit écran : une seule revue au lieu de 20 cartes d'approbation. Coût : il faut que les outils
  `Read`/`Grep` voient la version tampon (superposition) ; sous PRoot le plus simple est un **worktree git** ou une
  copie du projet dans le stockage d'Altrex. → étape 2 (Build v1 avec revue finale), étape 3 (aperçu sur la
  version tampon).

**Autonomie en matrice de réglages** — `docs/docs/core-concepts/autonomy.md` l.17-31, l.78-160
- 5 niveaux nommés (`none`, `basic`, `plus`, `semi`, `full`) + `custom`, chacun = combinaison de drapeaux
  indépendants : auto-continuer, auto-construire, charger le contexte, contexte intelligent, appliquer, pouvoir
  exécuter, exécuter, déboguer, commiter.
- **Pour Forge** : même principe que nos 4 puces (presets) + « Personnalisé ». À retenir : afficher la **matrice**
  dans l'écran de réglages pour expliquer ce que fait chaque puce. → étape 1 (texte d'aide).

**Tout est versionné, y compris la conversation** — `docs/docs/core-concepts/version-control.md` l.6-40, `app/server/db/git.go`
- Chaque événement (contexte ajouté, message, réponse, construction d'un changement, rejet, application, changement
  de modèle) crée une version (dépôt git par plan côté serveur : git.go l.44, `reset --hard` l.158) ; `log` et
  `rewind N | sha` ; branches de plan pour essayer sans perdre l'historique (branches.md) ; avertissement : un
  rewind sans branche efface l'historique (version-control.md l.36-40).
- **Pour Forge** : notre journal append-only couvre la conversation ; ajouter un **« Essayer autrement »** = brancher
  la session à un tour (fork) plutôt qu'effacer. → étape 2.

**Carte de fichiers par tree-sitter côté serveur** — `app/server/syntax/file_map/map.go` l.39-150
- Carte des définitions par fichier (`MapFile`), cas spéciaux Markdown/markup ; même famille d'idée qu'Aider.

### À éviter
- Architecture client/serveur + Postgres + Docker : impossible à embarquer tel quel sur téléphone.
- `app/cli/lib/claude_max.go` : connexion OAuth à un abonnement Claude Max (URLs console.anthropic.com, l.25-26)
  pour piloter les modèles : **ne pas reproduire** (utiliser un abonnement grand public via une appli tierce pose
  des questions de conditions d'utilisation ; Altrex passe par des clés API).
- Projet dont le service est en fin de vie : idées oui, dépendance non.
---

## 6. goose (block/goose → désormais aaif-goose/goose)

- **Dépôt** : cloné depuis `block/goose` (le proxy l'a servi), mais le `README.md` pointe partout vers
  **`aaif-goose/goose`** (badges CI, script d'installation, gouvernance : l.12-46) : le projet a changé
  d'organisation (gouvernance ouverte, `GOVERNANCE.md`). Contenu étudié = celui servi par `block/goose`.
- **Licence** : Apache-2.0 (`LICENSE`). Bibliothèques JS embarquées pour les visualisations MCP avec leurs licences
  dans `crates/goose-mcp/licenses/` (chart.js, d3, d3-sankey, leaflet, leaflet.markercluster, mermaid).
- **Ce que c'est** : agent généraliste open source (Rust ; CLI, appli de bureau, API) bâti sur MCP : tout outil
  est une « extension » ; « recettes » = flux réutilisables ; inférence locale llama.cpp intégrée.

### Idées à prendre

**Recettes = workflows réutilisables et paramétrés** — `crates/goose/src/recipe/mod.rs` l.43-210, `crates/goose/src/agents/types.rs` l.11-62, `crates/goose/src/slash_commands/recipe_slash_command.rs`, `workflow_recipes/release_risk_check/`
- Une recette (YAML, versionnée) : `title`, `description`, `instructions` (consignes au modèle), `prompt` (premier
  message), `extensions` à activer, `settings` (fournisseur, modèle, température, **`max_turns`**), `activities`
  (puces de démarrage proposées), `parameters`, `response.json_schema` (sortie structurée), `sub_recipes`
  (sous-recettes, éventuellement séquentielles), **`retry`** (mod.rs l.43-128).
- Paramètres typés : `string | number | boolean | date | file | select` ; exigence `required | optional |
  user_prompt` (demandé à l'utilisateur) ; **un paramètre `file` ne peut pas avoir de valeur par défaut** « pour ne
  pas importer des fichiers sensibles » (mod.rs l.158-207) ; paramètres non référencés retirés et validation
  (`recipe/validate_recipe.rs` l.438-483).
- `retry` = **objectif vérifiable** : `max_retries`, `checks: [ { shell: "commande" } ]` (succès = code de sortie 0),
  `on_failure` (commande de nettoyage), délais (types.rs l.11-62).
- Une recette peut être exposée comme **commande `/`** (`slash_commands/recipe_slash_command.rs`), comme les skills
  (`skill_slash_command.rs`).
- **Pour Forge** : excellent format pour (1) le **mode Build** (« objectif durable + preuve » = `checks` shell +
  `max_turns` + `max_retries`), (2) les **commandes `/` à toi** (une recette = un fichier dans
  `.altrex/commandes/`), (3) les puces `activities` sur l'écran « Nouveau projet ». Garder la règle « pas de
  fichier par défaut ». → étape 2 (Build avec checks), étape 4 (recettes utilisateur).

**Extensions (MCP) normalisées** — `crates/goose/src/agents/extension.rs` l.159-300
- Types : `stdio` (commande locale, `env_keys`, `timeout`), `builtin`, `platform` (interne), `streamable_http`
  (URI, en-têtes, OAuth avec secret jamais stocké en clair, socket Unix) ; chacun avec **`available_tools`**
  (liste blanche d'outils exposés) et `bundled`.
- **Pour Forge** : le registre d'outils/connecteurs du cahier des charges : liste blanche d'outils par serveur MCP
  (essentiel pour les petits modèles : moins d'outils = meilleurs choix), secrets dans le coffre Android, délai
  par serveur. → étape 2.

**Approbation « intelligente » et annotations d'outils** — `crates/goose-provider-types/src/goose_mode.rs` l.22-32, `crates/goose/src/permission/permission_inspector.rs` l.15-240, `permission/permission_judge.rs` l.40-150
- 4 modes : `auto`, `approve` (avant chaque appel), `smart_approve` (seulement les appels sensibles), `chat`
  (aucun outil). Niveaux mémorisés par outil : `always_allow | ask_before | never_allow` (permission_store.rs).
- `smart_approve` : (1) règle mémorisée, (2) **annotation MCP `read_only_hint`** de l'outil = auto-autorisé,
  (3) sinon un appel LLM classe les requêtes en lecture seule (permission_inspector.rs l.58, l.162-240 ;
  permission_judge.rs). Les tests montrent qu'un ancien « toujours autoriser » mis en cache est **ignoré** en
  smart_approve et en approve (l.323-328) : prudence par défaut.
- **Pour Forge** : utiliser les **annotations MCP** (`readOnlyHint`, `destructiveHint`) pour classer les outils MCP
  sous nos puces (« Éditions auto » autorise les outils annotés lecture seule) ; pas de LLM juge à l'étape 1
  (coût, et Argora n'est pas fiable pour ça). → étape 2 (MCP), étape 4 (juge).

**Détection de commandes dangereuses** — `crates/goose/src/security/patterns.rs` l.5-330, `security/scanner.rs`, `security/egress_inspector.rs`
- Table de motifs avec **niveau de risque** (`Critical/High/Medium/…` → score de confiance) pour l'injection de
  commandes ; inspecteur de sortie réseau (egress). **Pour Forge** : base d'un drapeau « commande risquée » sur la
  carte d'approbation (rouge si `rm -rf`, `curl | sh`, écriture hors projet) ; ne remplace pas la permission.
  → étape 1 (quelques motifs), étape 4.

**Détecteur de répétition** — `crates/goose/src/tool_monitor.rs` l.18-100 : appels identiques au-delà de
`max_repetitions` bloqués. Confirme notre anti-boucle.

**Mémoire de projet avec imports bornés** — `crates/goose/src/hints/load_hints.rs` l.10-17, `hints/import_files.rs` l.14-110
- Lit `.goosehints` **et** `AGENTS.md` (noms configurables, `CONTEXT_FILE_NAMES`) ; une ligne `@fichier.md` importe un
  autre fichier, avec **limites** : profondeur 3, 64 imports, 1 Mo au total, chemins canonicalisés, métadonnées git
  écartées (import_files.rs l.14-17, l.78-110).
- **Pour Forge** : `@` dans `ALTREX.md` pour inclure `docs/architecture.md` ; reprendre les bornes. → étape 2.

**`/doctor` qui répare le fournisseur** — `crates/goose/src/doctor.rs` l.22-260
- Vérifie l'extension « developer » requise, **teste réellement** le fournisseur/modèle, et en cas d'échec essaie
  d'autres modèles puis d'autres fournisseurs configurés, avec un message d'erreur expliqué (`describe_error`).
- **Pour Forge** : `/doctor` teste aussi le modèle (cloud : un appel minuscule ; local : chargement + 1 jeton) en plus
  des outils du Linux. → étape 1.

**Appels d'outils émulés pour modèles locaux** — `crates/goose-local-inference/src/tool_emulation.rs` l.1-6, `src/llamacpp/mod.rs` l.280-296, l.575-595
- Inférence llama.cpp intégrée (`llama-cpp-2`, Cargo.toml l.40). Si le gabarit de chat GGUF a un gabarit
  `tool_use` → appels **natifs** (flux OpenAI-compatibles fusionnés par index, `inference_native_tools.rs`
  l.228-300) ; sinon **émulation** : le modèle écrit `$ commande` sur une ligne ou un bloc de code balisé, que le
  parseur transforme en appel d'outil.
- **Pour Forge/Argora** : très pertinent (Altrex embarque llama.cpp). Prévoir les deux chemins : natif via le
  gabarit Qwen3.5, émulation de secours (format minimal « `$ cmd` ») pour un modèle local sans gabarit d'outils.
  → étape 1 (choix natif/émulé), à transmettre à la session Argora.

### À éviter
- Mode `auto` par défaut (goose_mode.rs l.23-25 : `Auto` est le défaut) : Forge garde « Demander » par défaut.
- Juge LLM pour la lecture seule avec un petit modèle local ; scheduler, gateway, live_voice, goose_apps : hors
  sujet pour l'instant.
- Embarquer mermaid/d3/leaflet via MCP : si Blueprint en a besoin, les prendre à la source avec leur licence
  (mermaid MIT, déjà prévu à l'étape 3).
---

## 7. opencode (sst/opencode → désormais anomalyco/opencode)

- **Dépôt** : cloné depuis `sst/opencode` (servi par le proxy), mais `README.md` pointe vers
  **`anomalyco/opencode`** (badges l.14, Homebrew `anomalyco/tap` l.54, Nix l.59) : le projet a changé d'organisation.
- **Licence** : MIT, © 2025 opencode (`LICENSE`, `packages/ui/LICENSE`, `packages/http-recorder/LICENSE`) ;
  `packages/process-daemon/LICENSE` MIT © 2026 James Long ; `packages/process-daemon/src/ghostty/LICENSE` MIT (Ghostty).
  Tests tiers `packages/codemode/test/LICENSE.wpt`, `LICENSE.test262` (non lus, inutiles).
- **Attention provenance** : `specs/v2/claude-agent-provider.md` décrit un fournisseur optionnel qui **lance le
  programme `claude` (Claude Code, propriétaire) installé par l'utilisateur via le SDK Claude Agent** (l.5-20). Ce n'est
  pas une dérivation du code de Claude Code, mais Forge ne doit **pas** reprendre ce mécanisme (même règle que pour le
  paquet `subagent-claude-code` de DeepSeek Harness). Je n'ai pas lu au-delà de l.40 de ce fichier.
- **Ce que c'est** : agent de code open source (TypeScript/Bun, Effect) avec **séparation moteur / interfaces** : un
  cœur (`packages/core`) + un serveur HTTP à flux d'événements (`packages/server`) + clients TUI, bureau, web, SDK.
  Agents intégrés `build` (accès complet) et `plan` (lecture seule), sous-agent `general` (`README.md` l.85-95).

### Idées à prendre

**Moteur séparé de l'écran, relié par un flux d'événements rejouable** — `specs/v2/event-stream-architecture.md` l.1-66, `specs/v2/session.md` l.106-118
- Le cœur publie des événements ; le serveur encode une fois et distribue à chaque client par une **file bornée**
  (4 096 trames) ; un client trop lent est **déconnecté seul**, sans bloquer le moteur ni les autres
  (event-stream-architecture.md l.47-66).
- Journal durable par session : `log({ after, follow })` rejoue depuis un numéro de séquence, émet un **marqueur de
  synchronisation**, puis continue en direct ; les deltas de texte/raisonnement en cours ne sont **pas** rejoués
  (session.md l.106-112).
- **Pour Forge** : exactement le découpage Android souhaitable : **moteur dans le service de premier plan**, écran
  Compose = client qui s'abonne ; quand Android détruit l'activité, l'écran revient, rejoue depuis la dernière
  séquence vue et reprend en direct. Une future version PC peut être un autre client du même journal. → étape 1
  (architecture du moteur ré-entrant), sans HTTP : un `Flow` Kotlin + journal Room suffit.

**Messages de l'utilisateur pendant que l'agent travaille : « orienter » ou « mettre en file »** — `specs/v2/session.md` l.5-24
- Toute saisie entre d'abord dans une **boîte de réception durable** avant exécution ; deux modes :
  `steer` (par défaut, livré au prochain **point d'arrêt sûr** entre deux étapes) et `queue` (attend que l'agent soit
  au repos). Une nouvelle saisie réinitialise le compteur d'étapes de l'agent. Idempotence par identifiant (pas de
  double envoi si le réseau rejoue).
- **Pour Forge** : sur téléphone on tape souvent pendant que l'agent travaille : bouton d'envoi à deux gestes
  (« Glisser une consigne » vs « Après ») ; la consigne n'interrompt pas un outil en cours. → étape 2.

**Reprise après plantage par « réclamation » écrite d'avance** — `specs/v2/session.md` l.53-80, l.114-118
- Au début d'une période d'exécution, une **réclamation** (write-ahead claim) est écrite ; succès/échec/interruption
  volontaire la libèrent ; une mort brutale la laisse. Au démarrage, les sessions réclamées reprennent avec une
  consigne de continuation et un **nombre d'essais borné**. Chaque appel d'outil est rendu durable **avant** ses effets ;
  à la reprise, les appels restés « en cours » sont marqués **échoués**, jamais rejoués automatiquement
  (session.md l.73-80) — on ne peut pas prouver qu'une commande externe n'a pas déjà agi.
- Réessais réseau étroits et visibles : 1 + 4 essais avec attente exponentielle, seulement pour limites de débit,
  erreurs internes du fournisseur, transport, flux incomplet (l.82-88).
- **Pour Forge** : complète le « tour interrompu » de DeepSeek Harness : (1) la réclamation dit si Android a tué
  l'appli en plein travail ; (2) proposer « Reprendre » plutôt que reprendre seul ; (3) ne jamais relancer une
  commande `Bash` interrompue, l'afficher « interrompue, état inconnu ». → étape 1.

**Changements de consignes (`ALTREX.md`) en deltas datés** — `specs/v2/session.md` l.90-96
- Chaque source de consignes est hachée ; quand une source change en cours de session, le changement est inséré **à sa
  place chronologique** comme message système, au lieu de réécrire le prompt de départ ; la compaction refait la base.
- **Pour Forge** : préserve le cache de prompt quand l'utilisateur modifie `ALTREX.md` depuis le Terminal. → étape 2.

**Mode Plan = permissions + rappels qui survivent à la compaction** — `packages/core/src/plugin/plan.ts` l.14-90
- L'agent Plan reçoit des règles : poser des questions autorisé, `edit` **refusé partout sauf** dans un dossier de
  plans (l.37-40). Un rappel « tu es en mode Plan » est injecté à l'entrée, un rappel « tu n'es **plus** en mode Plan,
  les consignes précédentes ne s'appliquent plus » à la sortie (l.14-25), et le rappel est **réinjecté** si la
  compaction ou une annulation l'a fait disparaître (l.55-90).
- **Pour Forge** : (1) Plan peut écrire dans `blueprint/` ou `.altrex/plans/` seulement — ça relie Plan et Blueprint ;
  (2) le rappel de sortie est indispensable aux petits modèles qui continuent à « planifier » ; (3) vérifier après
  chaque compaction que le rappel du mode courant est présent. → étape 1.

**Règles d'autorisation simples et lisibles** — `packages/core/src/permission.ts` l.20, l.58-110, l.140-345
- Règle = `{ action, resource (motif *), effect: allow | ask | deny }` ; la **dernière règle qui correspond gagne** ;
  sans règle : `ask` ; un agent sans permissions = tout refusé (l.20, l.91-104). Réponses : une fois / **toujours**
  (enregistré par projet) / refuser **avec message** (`CorrectedError`, le texte devient la consigne reçue) (l.66-75,
  l.310-345). Un refus **s'applique à toutes les demandes en attente** de la même session (« le retour vaut pour le
  lot », l.317-335). À la fermeture, toutes les demandes en attente sont refusées (l.140-155).
- **Règles « sans surveillance »** (`Unattended`) : pour un travail que personne ne peut approuver (tâche planifiée),
  un jeu de règles tranche ; ce qu'il n'autorise pas est refusé ; les refus configurés gagnent toujours (l.58-64).
- **Pour Forge** : ce modèle `action/ressource/effet` est le plus simple à exposer à l'utilisateur et à écrire dans
  un mode personnalisé (`edit: { "src/**": allow, "*": ask }`). « Sans surveillance » = la politique appliquée quand
  l'écran est éteint ou l'appli en arrière-plan (Build long) : on règle d'avance ce qui passe, le reste est refusé
  avec motif au lieu de bloquer. → étape 1 (modèle), étape 2 (Build sans surveillance).

**Résultat d'outil à trois faces** — `specs/v2/tools.md` l.5-48, l.148-177
- Un outil renvoie `content` (ce que voit le modèle, stocké), `metadata` (JSON compact **pour l'interface**) et
  éventuellement `output` typé ; chaque appel a une identité durable (session, agent, message, callID). Le registre
  borne le texte envoyé au modèle (début + fin + marqueur au milieu) et **conserve le texte complet** dans un stockage
  géré (l.148-155) ; un appel inconnu ou invalide devient une erreur visible sans rien exécuter.
- **Pour Forge** : les cartes d'outils et la rangée de livrables se dessinent depuis `metadata` (fichiers touchés,
  +N −M, code de sortie), jamais depuis la prose du modèle. → étape 1.

**Instantanés** — `packages/core/src/snapshot.ts` l.74-215
- Dépôt git d'instantanés séparé, initialisé à partir du dépôt source (l.91-105), capture d'arbre avec **plafond de
  2 Mo par fichier non suivi** (l.141), compactage périodique des objets (l.121-131), restauration **par fichiers
  choisis** avec refus d'un chemin qui sort du projet (l.200-215). **Pour Forge** : plafond par fichier + `git gc`
  périodique = indispensable pour le stockage d'un téléphone. → étape 2.

**Outil `Edit` tolérant mais exact** — `packages/core/src/tool/plugin/edit.ts` l.275
- Description lue (non copiée) : correspondance exacte d'abord, puis tolérance aux variantes typographiques
  (guillemets, tirets, espaces) et aux espaces de fin de ligne ; fins de ligne CRLF/LF préservées ; échec si plusieurs
  occurrences sans `replaceAll`. Rejoint Aider : une tolérance **bornée et annoncée**. → étape 1.

**LSP** : dans le cœur v2 actuel, le diagnostic LSP après édition est encore un **TODO**
(`packages/core/src/tool/plugin/edit.ts` l.258, `file-mutation.ts` l.138) ; seule une config v1 existe en test
(`packages/core/test/fixture/v1-config/lsp.ts`). Rien de mûr à reprendre ; pour Forge, un lint/compilateur après
édition (boucle Aider) est plus réaliste sous PRoot qu'un serveur LSP permanent (mémoire).

**ripgrep** — `packages/core/src/ripgrep/binary.ts` l.17-110 : utilise `rg` du PATH, sinon télécharge une version
**épinglée par empreinte** ; pour Linux arm64 la cible est `aarch64-unknown-linux-gnu` (glibc) (l.24-25), qui ne
tournerait pas telle quelle sur Alpine (musl) : sous Alpine, prendre le paquet `ripgrep` d'apk. Idée à garder :
vérification d'empreinte de tout binaire téléchargé.

### À éviter
- Le fournisseur « Claude Agent » (voir provenance).
- Effect/Bun/serveur HTTP : on garde l'architecture, pas la pile.
- Concurrence de plusieurs sessions dans un même processus sur téléphone : limiter à une session active.
---

## 8. OpenHands (All-Hands-AI/OpenHands → OpenHands/OpenHands + OpenHands/software-agent-sdk)

- **Où est le code** : `All-Hands-AI/openhands` (servi par le proxy) ne contient plus que l'**interface « Agent
  Canvas »** (React/Electron) ; son `README.md` (l.14, l.137, l.153-177) indique l'organisation **`OpenHands`** et
  répartit : `OpenHands/OpenHands` = interface ; **`OpenHands/software-agent-sdk`** = SDK Python, serveur d'agent,
  outils, conversations, espaces de travail, événements ; `OpenHands/typescript-client` ; `OpenHands/automation`.
  J'ai donc étudié l'interface puis le SDK. Le dossier `enterprise/` n'existe plus dans aucun des deux clones ; par
  précaution les deux ont été clonés en **sparse-checkout excluant `/enterprise/`** : rien n'en a été lu.
- **Licences** : interface MIT © 2025 OpenHands contributors (`LICENSE`) ; SDK MIT © 2026 OpenHands contributors
  (`software-agent-sdk/LICENSE`), client TS MIT (`clients/typescript/LICENSE`). Le SDK dit s'être « adapté
  d'OpenHands » (son propre ancien code, `openhands-sdk/openhands/sdk/security/analyzer.py` l.35).
- **Ce que c'est** : plateforme d'agents de code ; le SDK fournit boucle d'agent, journal d'événements append-only,
  condenseur, détecteur de blocage, analyseurs de risque, skills (ex-« microagents »), espaces de travail
  local/Docker/distant, hooks, `/goal`.

### Idées à prendre

**Confirmation selon le niveau de risque** — `openhands-sdk/openhands/sdk/security/confirmation_policy.py` l.9-61, `security/risk.py` l.8-38, `security/llm_analyzer.py` l.11-29, `security/ensemble.py` l.1-7, `security/defense_in_depth/policy_rails.py` l.1-14
- Politiques : `AlwaysConfirm`, `NeverConfirm`, `ConfirmRisky(threshold = HIGH, confirm_unknown = true)` ; risque
  `LOW < MEDIUM < HIGH`, `UNKNOWN` hors échelle et **confirmé par défaut**.
- Sources de risque combinées par **le pire des avis** (ensemble) : (1) le modèle remplit lui-même un champ
  `security_risk` dans chaque appel d'outil (LLM analyzer), (2) motifs connus, (3) **« rails » structurels
  déterministes** : récupération réseau qui alimente une exécution (`curl … | sh`), opération disque brute,
  suppression catastrophique → HIGH (policy_rails.py l.1-14).
- Interface : badge « risque moyen / élevé » sur la carte de commande (`OpenHands/OpenHands`
  `src/components/features/chat/tool-visualizers/bash/bash.tsx` l.11-34).
- **Pour Forge** : ajouter au schéma de `Bash` un champ facultatif `risque` (bas/moyen/élevé) que le modèle remplit,
  **combiné par le pire** avec nos rails déterministes ; en « Éditions auto », une commande à risque élevé ou inconnu
  repasse en « Demander ». Le champ aide aussi l'utilisateur à lire la carte. → étape 1 (rails + badge), étape 2
  (champ du modèle, à transmettre à la session Argora pour le format d'outils).

**Détecteur de blocage à 5 motifs** — `openhands-sdk/openhands/sdk/conversation/stuck_detector.py` l.21-68, `conversation/types.py` l.145-160
- Sur les 20 derniers événements depuis le dernier message utilisateur : même action+résultat répétés (seuil 4),
  même action+erreur (3), monologue sans action (3), alternance A/B/A/B (6), erreurs de fenêtre de contexte ; une
  seule relance par série figée.
- **Pour Forge** : enrichit notre anti-boucle 3/5/8 : ajouter **monologue** (le modèle parle sans agir — fréquent
  chez les petits modèles) et **alternance** (lit/écrit/lit/écrit). → étape 1.

**Compaction = événement « pierre tombale » dans le journal** — `openhands-sdk/openhands/sdk/context/condenser/README.md`
- Le journal reste append-only ; une condensation ajoute un événement `Condensation` qui dit quels événements oublier
  et quel résumé insérer ; une `View` applique ces marques pour construire le contexte. Stratégie : résumer la
  **première moitié**, garder la seconde intacte ; les résumés sont re-résumés plus tard. Déclencheurs **souples**
  (seuil de taille : on peut reporter si la structure ne le permet pas) vs **durs** (dépassement de fenêtre : reset
  complet résumé).
- **Pour Forge** : c'est la bonne façon d'articuler notre « journal à ajout seul » et la « compaction à deux
  niveaux » : la compaction est un événement du journal, la reprise rejoue tout en appliquant les marques. → étape 2.

**`/goal` : objectif jugé par un second modèle, avec plafond** — `openhands-sdk/openhands/sdk/conversation/goal/__init__.py` l.1-10, `goal/controller.py` l.25-130 ; `critic/base.py` l.17-60
- Envoyer l'objectif, laisser l'agent travailler, faire **juger l'achèvement par un autre modèle**, relancer avec
  une consigne de suite jusqu'à `complete` ou `capped` (défaut 10 itérations) ; statut vivant
  `running | complete | capped | interrupted` « pour une puce de progression » (controller.py l.37-52). Critique
  itérative : seuil de réussite 0,6, 3 itérations max (critic/base.py l.40-60).
- **Pour Forge** : le **mode Build** = `/goal` + preuve déterministe (checks shell de goose) : un juge seul ne suffit
  pas (il croit l'agent), une commande seule ne voit pas la qualité. Puce « Build 3/10 · en cours ». → étape 2.

**Skills déclenchées par mots-clés ou par chemin** — `openhands-sdk/openhands/sdk/skills/trigger.py` l.13-47, `skills/skill.py` l.165-178, l.540-580 ; `context/agent_context.py` l.96-131
- Types de déclencheur : `keyword` (mot entier, `git` ne s'active pas sur `github`), `task` (avec `inputs`), `path`
  (règle injectée **de façon déterministe** quand l'agent touche un fichier correspondant, globs à la gitignore) ;
  sans déclencheur = toujours actif ; un skill « par chemin » n'est pas invocable par le modèle (priorité `paths`).
  Compatibilité avec l'ancien dossier `microagents/` et lecture d'`AGENTS.md`.
- **Pour Forge** : même idée que les règles conditionnelles de Cline ; préférer le **déclenchement déterministe**
  (chemin, mot-clé) au choix par le modèle pour Argora. → étape 2.

**Espaces de travail interchangeables** — `openhands-workspace/openhands/workspace/{docker,apptainer,remote_api,cloud,agent_sandbox}`, `openhands-sdk/openhands/sdk/workspace/base.py` l.28-35
- Une interface commune (exécuter, lire/écrire) avec implémentations locale, Docker, Apptainer, distante.
- **Pour Forge** : définir l'interface « espace de travail » (PRoot local aujourd'hui, PC distant demain) dès l'étape 1
  pour que la future version PC soit un autre backend. Docker/Apptainer : inutilisables sur téléphone.

**Cartes d'outils en deux moitiés** — `OpenHands/OpenHands` `src/components/features/chat/tool-visualizers/define.ts`, `dispatcher.tsx`, `primitives/{diff-view,file-path-chip,output-pane,key-value-grid}.tsx`
- Chaque appel = **carte d'action** + **carte de résultat** ; un registre de « visualiseurs » par type d'outil (bash,
  éditeur, recherche, tâche) produit le corps repliable, sinon repli en Markdown ; primitives réutilisables (puce de
  chemin, diff, volet de sortie). **Pour Forge** : sur téléphone, **fusionner** action et résultat dans une seule carte
  (l'action en titre, le résultat dépliable) pour économiser l'écran ; garder l'idée du registre de rendus par outil
  avec repli générique (utile pour les outils MCP). → étape 1.

**Hooks** — `openhands-sdk/openhands/sdk/hooks/types.py` l.9-17 : événements `PreToolUse`, `PostToolUse`,
`UserPromptSubmit`, `SessionStart`, `Stop` — mêmes noms que Claude Code. Forge a décidé son **propre format** : ne
pas reprendre ces noms tels quels (à l'étape 4).

### À éviter
- Docker/Kubernetes/Helm, serveur d'agents multi-hôtes, automation : hors téléphone.
- Faire confiance au seul `security_risk` déclaré par le modèle (toujours le combiner avec des règles déterministes).
---

## 9. Synthèse transversale

### 9.1 Les sujets demandés, en bref

- **Repo map** : Aider = tree-sitter (requête `.scm` par langage, défs/réfs) → graphe → PageRank personnalisé →
  dichotomie sur un budget de jetons (`aider/repomap.py`) ; Plandex fait une carte tree-sitter côté serveur. Sous
  Alpine arm64 : `ripgrep` = paquet apk, binaire autonome, rien d'autre ; tree-sitter = bibliothèque C + une
  grammaire compilée par langage (en Python : roues musl/aarch64 ou compilation gcc + python3-dev ; Aider ajoute
  networkx, scipy, diskcache, pygments). Recommandation : v1 sans tree-sitter (symboles par motifs `rg` + classement
  simple + budget), tree-sitter plus tard embarqué dans l'APK (NDK) avec quelques grammaires.
- **Modes personnalisés** : modèle Roo Code (rôle, quand l'utiliser, consignes, groupes d'outils avec restriction de
  fichiers, modèle par mode, `.roomodes` projet > global, règles `rules-<mode>/`, export/import) + règles
  `action/ressource/effet` d'opencode + appliquer les restrictions **à l'exécution** (refus motivé) pour garder la
  liste d'outils stable. Apprentissage = mode « consignes seules » ; Build = mode orchestrateur + recette (objectif,
  checks, plafonds).
- **Revoir avant d'appliquer** : Plandex (zone tampon, revue finale par fichier, rejet sélectif, apply, commandes
  après apply, retour arrière si échec) ; Cline/Roo/opencode : checkpoints restaurables par fichiers, conversation,
  ou les deux.
- **Vocabulaire approbation / bac à sable** : Codex (`untrusted / on-request / granular / never` ×
  `read-only / workspace-write / danger-full-access / external-sandbox`, réseau à part, sous-chemins protégés) ;
  goose (`auto / approve / smart_approve / chat`) ; OpenHands (`always / never / confirm-risky(seuil)`) ; Plandex
  (5 niveaux d'autonomie en matrice). Décisions : Codex (`once / préfixe toujours / session / refus+motif / abort`,
  **défaut = refus**), opencode (`once / always / reject+message`, refus propagé au lot, règles « sans surveillance »).
- **Checkpoints** : 3 conceptions : commit réel par édition (Aider, garde-fous `/undo`) ; dépôt fantôme
  `core.worktree` (Roo, marche sans git ; opencode, plafond 2 Mo/fichier + `gc`) ; `git stash create` + refs privées
  dans le vrai dépôt, restauration transactionnelle qui refuse si HEAD a bougé (Cline). Pour Forge : **dépôt fantôme
  dans le stockage d'Altrex** (marche pour tous les projets, ne touche pas au `.git` de l'utilisateur) + les garde-fous
  de Cline (sauvegarde avant restauration, refus si l'historique a bougé, ne jamais effacer les fichiers ignorés) +
  plafond de taille d'opencode.
- **Cartes d'outils et diffs sur petit écran** : une carte par appel (action en titre, résultat dépliable — fusionner
  les deux moitiés d'OpenHands) ; rendu choisi par type d'outil depuis les **métadonnées** (opencode), repli générique
  pour MCP ; en-tête `fichier +N −M` (Cline) ; sortie repliée au-delà de 5 lignes (Cline) ; suivi automatique du flux
  sauf si l'utilisateur remonte (Cline) ; diff ligne-à-ligne avec retour à la ligne forcé, numéros, gouttière `+/−`, fond
  teinté, coloration par hunk (Codex) ; badge de risque (OpenHands) ; carte d'approbation à 4 boutons dont « Non,
  plutôt… » avec champ texte (Codex, opencode) ; en mode revue finale, **liste de fichiers à cocher** puis diff plein
  écran par fichier (Plandex).

### 9.2 Tableau idée → projets → étape → effort

Effort : S (jours), M (1-2 semaines), L (plus).

| # | Idée | Projets (fichiers clés) | Étape | Effort |
|---|---|---|---|---|
| 1 | Décision d'approbation riche, défaut = refus : Oui / Toujours pour ce préfixe / Non plutôt… (motif) / Stop ; refus propagé aux demandes en attente | Codex `protocol.rs` l.4466-4515, `approval_overlay.rs` ; opencode `permission.ts` l.310-345 | 1 | S |
| 2 | Règles `action/ressource/effet` (dernière qui correspond gagne, défaut « demander ») + règles de commandes par préfixe avec motif et alternative | opencode `permission.ts` l.91-104 ; Codex `execpolicy/README.md` | 1 | M |
| 3 | Sous-chemins protégés même en écriture (`.git/hooks`, `.altrex/`) + axe réseau séparé + libellé honnête « isolation externe » pour PRoot | Codex `protocol.rs` l.1250-1330 | 1 | S |
| 4 | Mode Plan : écriture refusée sauf `blueprint/`/`.altrex/plans/`, garde-commande shell, rappel d'entrée **et de sortie**, réinjecté après compaction | opencode `plugin/plan.ts` ; Cline `command-guard.ts`, `mode.ts` | 1 | S |
| 5 | Moteur dans le service de premier plan, écran = client d'un journal rejouable (séquence + marqueur de synchro) ; réclamation écrite d'avance ; appels d'outils interrompus marqués échoués, jamais rejoués | opencode `specs/v2/session.md`, `event-stream-architecture.md` | 1 | M |
| 6 | Détecteur de blocage : répétition, action+erreur, **monologue**, **alternance** | OpenHands SDK `stuck_detector.py` ; Roo `ToolRepetitionDetector.ts` ; goose `tool_monitor.rs` | 1 | S |
| 7 | Erreur d'`Edit` qui montre les lignes réelles les plus proches ; tolérance bornée (indentation, typographie) ; alias de noms d'outils | Aider `editblock_coder.py` l.52-70, l.157-330 ; opencode `edit.ts` l.275 ; Roo `tools.ts` l.328 | 1 | S |
| 8 | Cartes d'outils dessinées depuis les métadonnées ; `+N −M`, repli > 5 lignes, suivi du flux ; badge de risque ; rails déterministes (`curl \| sh`, `rm -rf /`, disque brut) | opencode `specs/v2/tools.md` ; Cline `DiffEditRow.tsx`, `CommandOutputRow.tsx` ; OpenHands `bash.tsx`, `policy_rails.py` ; goose `patterns.rs` | 1 | M |
| 9 | Projet « non approuvé » : `ALTREX.md`/règles non chargés avant accord ; budget en octets ; replis `AGENTS.md`/`CLAUDE.md` ; imports `@` bornés | Codex `agents_md.rs` ; goose `hints/import_files.rs` | 1-2 | S |
| 10 | `/doctor` qui teste aussi le modèle (et propose un autre modèle/fournisseur) | goose `doctor.rs` | 1 | S |
| 11 | Appels d'outils natifs ou **émulés** pour modèle local llama.cpp selon le gabarit GGUF | goose `goose-local-inference/src/tool_emulation.rs`, `llamacpp/mod.rs` | 1 (Argora) | M |
| 12 | Checkpoints : dépôt fantôme + restauration transactionnelle (Fichiers / Conversation / Les deux), refus si HEAD a bougé, plafond par fichier, `gc` | Roo `ShadowCheckpointService.ts` ; Cline `checkpoint-hooks.ts`, `checkpoint-restore.ts`, `checkpoints.mdx` ; opencode `snapshot.ts` | 2 | M |
| 13 | `/undo` gardé (dernier changement de l'agent seulement, pas de fichier modifié depuis, pas déjà poussé) ; commit des changements de l'utilisateur avant édition | Aider `commands.py` `cmd_undo`, `base_coder.py` l.2174-2190 | 2 | S |
| 14 | Mode Build : recette (objectif, `max_turns`, `checks` shell, `max_retries`, `on_failure`) + juge d'achèvement avec plafond + boucle lint/test (3 réflexions max) + revue finale à la Plandex | goose `recipe/mod.rs`, `agents/types.rs` ; OpenHands `goal/controller.py` ; Aider `base_coder.py` l.1596-1622 ; Plandex `reviewing-changes.md`, `apply_exec.go` | 2 | L |
| 15 | Compaction = événement « pierre tombale » dans le journal ; résumer la 1re moitié ; déclencheurs souples/durs | OpenHands SDK `condenser/README.md` | 2 | M |
| 16 | Saisie pendant le travail : « orienter » (au prochain point sûr) ou « après » (file) | opencode `specs/v2/session.md` l.5-24 | 2 | S |
| 17 | Règles/skills déclenchées par chemin ou mot-clé (déterministe) | Cline `cline-rules.mdx` ; OpenHands SDK `skills/trigger.py` | 2 | S |
| 18 | MCP : liste blanche d'outils par serveur, annotations `readOnlyHint` pour classer sous les puces, délai par serveur, secrets au coffre | goose `extension.rs`, `permission_inspector.rs` | 2 | M |
| 19 | Modes personnalisés au format Roo (rôle, quand, consignes, groupes + restriction de fichiers, modèle par mode, projet > global, export/import) | Roo `packages/types/src/mode.ts`, `CustomModesManager.ts`, `ProviderSettingsManager.ts` | 2 (Apprentissage/Build) puis 4 | M |
| 20 | Carte du dépôt (repo map) : v1 motifs `rg` + budget ; v2 tree-sitter + PageRank | Aider `repomap.py` ; Plandex `syntax/file_map/map.go` | 2 puis 4 | M → L |
| 21 | Revue finale « fichiers à cocher » avec zone tampon (worktree) et retour arrière si les commandes échouent | Plandex `apply.go`, `types/apply.go`, `action_menu.go` | 2-3 | L |
| 22 | Orchestrateur séquentiel : `new_task(mode, consigne, todos)`, parent en pause, l'enfant rend un résumé | Roo `NewTaskTool.ts`, `AttemptCompletionTool.ts` | 4 | M |
| 23 | Architecte / éditeur : un modèle planifie, un autre (Argora) applique avec peu d'outils | Aider `architect_coder.py` ; Cline modèle par mode | 4 | M |
| 24 | Relecteur automatique d'approbations (risque + autorisation), certaines demandes restent toujours humaines, toute escalade = nouvelle revue | Codex `guardian-context`, `core/src/tools/approvals.rs` | 4 | L |
| 25 | Recettes utilisateur exposées en commandes `/`, paramètres typés (pas de fichier par défaut) | goose `recipe_slash_command.rs`, `recipe/mod.rs` l.158-207 | 4 | M |

### 9.3 À éviter (tous projets)
- Copier code ou prompts (y compris les prompts d'orchestrateur de Roo, du Guardian de Codex, des descriptions
  d'outils d'opencode) : idées seulement.
- Bacs à sable reposant sur les espaces de noms ou Landlock (Codex bubblewrap/seccomp) et Docker (OpenHands, Plandex) :
  indisponibles sous PRoot sans root.
- Dépendances lourdes dans le Linux de l'appli (Python + tree-sitter + scipy ; base vectorielle Qdrant de Roo ;
  Postgres de Plandex ; serveurs LSP permanents).
- Faire confiance au modèle pour classer une commande « sûre » (Cline) ou « à faible risque » (OpenHands) sans règle
  déterministe ; juge LLM pour la lecture seule avec un petit modèle (goose).
- Commit automatique dans la branche de l'utilisateur à chaque édition (Aider).
- Tout mécanisme qui pilote le binaire Claude Code ou un abonnement grand public : opencode
  `specs/v2/claude-agent-provider.md`, Plandex `app/cli/lib/claude_max.go`.
- Noms d'événements de hooks calqués sur Claude Code (OpenHands SDK `hooks/types.py`) : Forge a son format.

### 9.4 Licences (rappel)
| Projet | Licence | Points d'attention |
|---|---|---|
| Aider | Apache-2.0 | requêtes `.scm` dérivées des grammaires (MIT surtout) |
| Cline | Apache-2.0 | palette Radix (MIT) |
| Roo Code | Apache-2.0 | — |
| Codex | Apache-2.0 + NOTICE (Ratatui MIT) | `vendor/bubblewrap` **LGPL-2.0** ; `third_party/voice` (LGPL-2.1, etc.) |
| Plandex | MIT | service cloud en arrêt depuis 10/2025 ; intégration abonnement Claude Max |
| goose | Apache-2.0 | dépôt déplacé vers `aaif-goose/goose` ; libs JS MCP (d3, leaflet, mermaid, chart.js) sous leurs licences |
| opencode | MIT (+ sous-paquets MIT) | dépôt déplacé vers `anomalyco/opencode` ; fournisseur qui lance Claude Code |
| OpenHands | MIT (interface et SDK) | code d'agent déplacé vers `OpenHands/software-agent-sdk` ; `enterprise/` absent et non lu |
