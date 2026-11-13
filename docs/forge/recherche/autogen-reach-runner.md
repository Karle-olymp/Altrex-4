# Forge : ce qu'on retient d'AutoGen, Agent-Reach et local-ai-runner

Lecture seule, rien n'a été exécuté. Chemins relatifs à
`/tmp/claude-0/-home-user/f2187807-956a-51ca-a64a-3a197336c840/scratchpad/repos/`.
Abréviations : `AC` = `autogen/autogen-main/python/packages/autogen-agentchat/src/autogen_agentchat`,
`EXT` = `autogen/autogen-main/python/packages/autogen-ext/src/autogen_ext`,
`AGS` = `autogen/autogen-main/python/packages/autogen-studio`,
`AR` = `agent-reach/Agent-Reach-main`. Étapes Forge (ALTREX_CODE.md §7) : 1 fondations, 2 confort, 3 aperçu/scan, 4 sous-agents/hooks/touche perso.

On ne reprend que des idées. Aucun code n'est copié.

---

## 1. Microsoft AutoGen

**Ce que c'est.** Un framework Python et .NET pour faire travailler plusieurs agents ensemble (équipes, handoffs, graphes, Magentic-One), plus AutoGen Studio (une interface web pour composer des équipes). **Le projet est en mode maintenance** (`autogen-main/README.md` l.14-25) : plus aucune nouvelle fonction, le successeur est Microsoft Agent Framework. On s'en sert donc comme source de patrons, pas comme dépendance.

**Licences** (`README.md` l.222-225) :
- `LICENSE` = CC-BY-4.0, pour la documentation et les « autres contenus ».
- `LICENSE-CODE` = MIT, pour tout le code (y compris `AGS/LICENSE-CODE`).
- Le texte des prompts dans `_prompts.py` fait partie d'un fichier `.py`, donc il est couvert par MIT. On le reformule quand même au lieu de le copier.
- Aucun droit sur les noms et logos Microsoft (l.229).

### Patrons utiles

| # | Patron | Preuve | Pour Forge |
|---|---|---|---|
| A1 | **Registre de tâche + registre de progression** (Magentic-One). Boucle externe : un tableau des faits (donnés / à chercher / à déduire / suppositions), puis un plan court. Boucle interne, à chaque tour : un JSON `{is_request_satisfied, is_in_loop, is_progress_being_made, next_speaker, instruction_or_question}`, chaque champ avec `reason` et `answer`. | `AC/teams/_group_chat/_magentic_one/_prompts.py` (FACTS, PLAN, PROGRESS_LEDGER, classe `LedgerEntry`) | Étape 4 pour les sous-agents. Une version légère dès l'étape 1 : un contrôle de progression tous les N appels d'outils, relié à `todo_write`. |
| A2 | **Compteur de blocages et nouveau plan.** `n_stalls += 1` si l'agent n'avance pas ou tourne en rond, sinon `-1` (jamais sous 0). Quand le compteur atteint `max_stalls` (3 par défaut) : mise à jour des faits, puis nouveau plan qui doit expliquer la cause de l'échec. `max_turns` vaut 20 par défaut. Le JSON est réessayé `max_json_retries` fois. | `AC/.../_magentic_one_orchestrator.py` l.300-405 ; prompt `PLAN_UPDATE` dans `_prompts.py` ; valeurs par défaut dans `_magentic_one_group_chat.py` l.52-53 | Étapes 1-2 : détection de boucle, puis « je replanifie » affiché à l'utilisateur. C'est précieux pour les petits modèles locaux (risque n°3 de la spec). |
| A3 | **Conditions d'arrêt composables** avec `&` et `\|` : MaxMessage, TextMention, TokenUsage, Timeout, Handoff, **External** (déclenchée de l'extérieur, comme un bouton Stop), SourceMatch, FunctionCall. | `AC/conditions/_terminations.py` (classes l.24-565) ; `AC/base/_termination.py` l.79-144 | Étape 1 : budget par demande (étapes, jetons, temps), plus le bouton Arrêter (External). À afficher dans la ligne d'état. |
| A4 | **Approbation avec motif renvoyé au modèle.** `ApprovalRequest{code, context}` donne `ApprovalResponse{approved, reason}`. En cas de refus, le modèle reçoit « not approved. Reason: … ». La fonction d'approbation peut être humaine OU un modèle relecteur. | `AC/agents/_code_executor_agent.py` l.69-86, 140-142, 345-366, 711-714 | Étape 1 : un refus dans le mode « Demander » doit comporter un motif (champ facultatif) que l'agent lit. Étape 4 : un relecteur automatique en mode « Tout autoriser » sur les commandes risquées. |
| A5 | **Un agent utilisé comme outil** (`AgentTool`). Le sous-agent tourne, puis renvoie soit son dernier message, soit tout le fil. Avertissement : désactiver les appels parallèles, car les agents ont un état. | `AC/tools/_agent.py`, `AC/tools/_task_runner_tool.py` (`return_value_as_last_message`) | Étape 4 : le patron le plus simple pour l'outil `task` des sous-agents. Il ne renvoie qu'un résumé, ce qui économise le contexte. |
| A6 | **Society of Mind** : une équipe interne, puis un `response_prompt` qui la résume en une seule réponse. | `AC/agents/_society_of_mind_agent.py` l.34, 64 | Étape 4 : un sous-agent « explorateur » qui remonte une synthèse. |
| A7 | **Handoff = outil `transfer_to_<cible>`** avec une description et un message. Si l'agent en déclenche plusieurs, seul le premier compte. | `AC/base/_handoff.py` l.12-43 ; `AC/agents/_assistant_agent.py` l.171 | Étape 4 : passer de Plan à Build, ou de Build au relecteur, comme un handoff explicite. |
| A8 | **Plafond d'itérations d'outils** et choix « reflect_on_tool_use » contre « résumé brut ». | `AC/agents/_assistant_agent.py` l.85, 139-146 | Étape 1 : limite d'étapes par demande, réglable. |
| A9 | **GraphFlow** : un graphe orienté d'agents. Chaque arête a une condition (texte contenu, ou fonction). Les nœuds attendent `all` ou `any` de leurs arêtes entrantes. Les cycles sont gérés par `activation_group`. | `AC/teams/_group_chat/_graph/_digraph_group_chat.py` l.25-115 | Étape 4, ou la zone « conception de systèmes » : un format JSON simple de workflow (nœuds, arêtes, conditions). |
| A10 | **Sélecteur** : `selector_func` (déterministe), puis `candidate_func`, `allow_repeated_speaker` et `max_selector_attempts`. | `AC/teams/_group_chat/_selector_group_chat.py` l.68-95 | Étape 4 : laisser des règles fixes passer avant le modèle quand c'est possible. |
| A11 | **Pause, reprise et sauvegarde d'état** d'une équipe, y compris les compteurs de l'orchestrateur. | `AC/teams/_group_chat/_base_group_chat.py` l.657-798 ; orchestrateur l.225-245 | Étape 2 : la reprise de session doit aussi restaurer la todo, le plan et les compteurs, pas seulement les messages. |
| A12 | **MCP Workbench** : `tool_overrides` (renommer ou redécrire un outil MCP), plus prompts, ressources et sampling. | `EXT/tools/mcp/_workbench.py` l.37-47, 83-90, 250-294, 372-432 | Étape 2 : renommer les outils MCP en français ou les raccourcir pour les petits modèles. |
| A13 | **TextCanvas** : des fichiers en mémoire avec révisions, `get_diff` et `apply_patch`. | `EXT/memory/canvas/_canvas.py`, `_text_canvas.py`, `_canvas_writer.py` | Idée seulement. Git couvre déjà ce besoin (étape 2). |
| A14 | **Avertissements de sécurité** : conteneurs, journaux surveillés, injection de prompt depuis le web. | `EXT/teams/magentic_one.py` l.42-52 | Étapes 1-2 : marquer le contenu `web_fetch` comme non fiable. |

### AutoGen Studio (interface visuelle)
- Constructeur visuel d'équipes sur `@xyflow/react` (`AGS/frontend/package.json`), avec une bibliothèque de composants à glisser (`builder/library.tsx`), un **historique annuler/rétablir** (`builder/store.tsx` l.61-184) et une **bascule Visuel ⇄ JSON** (`builder/builder.tsx` l.75, 169-179, 356-384).
- **Validation avec suggestion de correction** (`AGS/autogenstudio/validation/validation_service.py` l.13-22 : `error` + `suggestion`) et **bouton « tester ce composant »** (`component_test_service.py` : test_agent, test_model, test_tool…).
- **Visualisation en direct d'une exécution** : graphe des messages entre agents, disposé avec Dagre, avec les états active, awaiting_input, complete, error et stopped (`AGS/frontend/src/components/views/playground/chat/agentflow/agentflow.tsx` l.20, 111, 156-208, 418-443).
- Une **galerie** de composants prêts à l'emploi (`AGS/autogenstudio/gallery/builder.py`).
- L'outil s'annonce lui-même comme un **prototype non destiné à la production**, sans sécurité (`AGS/README.md` l.11-24).

**Pour Forge** : (étape 4 ou zone Blueprints) un éditeur simple de **blueprints** stocké en JSON dans le projet (par ex. `.altrex/blueprints/*.json`), avec un mode texte/JSON, annuler/rétablir, une validation `error + suggestion` et un rendu en lecture seule du graphe d'une exécution (qui a fait quoi). Sur téléphone, mieux vaut rendre un graphe et l'éditer par formulaires que proposer un canevas libre au doigt.

### À ne pas reprendre
- Le runtime à acteurs, les topics pub/sub et la distribution gRPC (`autogen-core`, `protos/`) : beaucoup trop lourds pour une boucle Kotlin sur téléphone.
- Les équipes en discussion de groupe où chaque tour coûte un appel LLM de sélection (Selector, Magentic-One). C'est cher et lent avec un modèle cloud, et irréaliste en local. Un agent principal avec des sous-agents en outils (A5) suffit.
- Le canevas libre façon Studio, tel quel sur 6,8 pouces. Ses dépendances web (Gatsby, React Flow) ne vont pas dans Compose.
- Le framework lui-même comme dépendance (Python, et en maintenance).

---

## 2. Agent-Reach (MIT, © 2025 Agent Eyes, `AR/LICENSE`)

**Ce que c'est.** Une CLI Python qui **installe, contrôle (doctor) et aiguille** vers des outils CLI d'autres projets (yt-dlp, gh, twitter-cli, Jina Reader, mcporter…) pour environ 15 plateformes. Ce n'est pas une surcouche : une fois l'outil installé, l'agent appelle directement le programme d'origine (`AR/llms.txt`, `AR/docs/install.md` « Goal »). La documentation est surtout en chinois.

### Patrons utiles

| # | Patron | Preuve | Pour Forge |
|---|---|---|---|
| R1 | **Un registre de « canaux »** : nom, description, `backends` ordonnés (préféré d'abord, puis repli), `tier` (0 = sans config, 1 = clé gratuite, 2 = installation), `active_backend`, `check()` qui renvoie ok, warn, off ou error. L'utilisateur peut forcer un backend, et une valeur forcée inconnue est ignorée. | `AR/agent_reach/channels/base.py` | Étapes 2-3 : un registre des outils Linux de Forge (git, ripgrep, node, python, gh…) et des connecteurs MCP, sur le même modèle. |
| R2 | **Une sonde réelle, pas `which()`** : on lance `cmd --version` avec un délai maximum, et le résultat est classé missing / broken (codes 126/127, interpréteur absent) / timeout / error / ok, avec une **consigne de réparation**. Les nouvelles tentatives ne servent qu'aux erreurs passagères. Les sondes doivent être sans effet de bord. | `AR/agent_reach/probe.py` | Étape 1 (`altrex-setup`) : `/doctor` dans Forge. C'est très pertinent sous PRoot, où les binaires et les shims cassent. |
| R3 | **Doctor avec sortie JSON pour l'agent** et rapport lisible pour l'humain, groupé par niveau, avec un compteur « x/y disponibles ». Une erreur dans un canal se dégrade en « error » sans faire planter le reste. | `AR/agent_reach/doctor.py` l.16-106 ; `llms.txt` (`doctor --json`) | Étape 1 : `/doctor` affiche des cartes, et l'agent lit le JSON avant d'utiliser un outil. |
| R4 | **Installation en lecture seule par défaut** : `install` ne fait que vérifier, `--system` exige un accord explicite et `--dry-run` montre ce qui serait fait. | `AR/agent_reach/cli.py` l.83-105, 271-428 ; `docs/install.md` l.11-15 | Étape 1 : l'agent propose `apk add …` sous forme de plan, et l'utilisateur valide (mode Demander). |
| R5 | **Une notice d'installation écrite pour un agent** : objectif, **limites** (pas de sudo sans accord, rien hors `~/.agent-reach/`, pas de paquets hors liste, ne pas désactiver les protections), **règles de dossiers** (ne jamais polluer l'espace de travail). | `AR/docs/install.md` l.20-48 | Étape 1 : une notice `altrex-setup` lisible par l'agent, et une règle « outils hors de `/root/projets` ». |
| R6 | **`llms.txt`** : un index court qui renvoie l'agent vers l'installation, les commandes principales et le dépannage. | `AR/llms.txt` | Étape 2 : chaque skill ou outil Forge a un résumé court, et le détail est chargé à la demande. |
| R7 | **Skill de routage** : une description « MUST USE when… / NOT for… », des règles valables toute la session (contrôle avant d'agir, annoncer le backend utilisé, suivre les chaînes de repli de `references/` au lieu d'inventer des commandes), une table de routage vers des fichiers de référence. | `AR/agent_reach/skill/SKILL_en.md` l.1-60 ; `references/web.md` | Étape 2 : le format des skills Forge. La règle « annonce ce que tu utilises » rejoint le mode apprentissage. |
| R8 | **Mises à jour sans harceler** : `check-update` à la fin d'une tâche, une ligne dans le résumé, ne jamais interrompre, ne jamais répéter la même version. | `SKILL_en.md` règle 5 ; `cli.py` l.180-183 (`check-update`, `watch`) | Étape 4 (touche perso / mode build) : proposer les mises à jour et les technos récentes à la fin, une seule fois. |
| R9 | **Hygiène des secrets** : fichier de config en 0600, URL nettoyées de leurs identifiants, tests sur les limites des identifiants. | `AR/agent_reach/config.py` l.61-79 ; `utils/text.py` (`scrub_url_credentials`) ; `tests/test_doctor_credential_boundaries.py` | Étapes 1-2 : secrets MCP et clés dans le Linux en 0600, masqués dans les cartes d'outils. |
| R10 | **Lecteur web de repli** `r.jina.ai/URL`, avec détection des captchas et de Cloudflare. | `AR/agent_reach/channels/web.py` l.19-53 | Étape 2 (`web_fetch`) : idée de repli seulement. C'est un service tiers, donc à signaler pour la vie privée. |

### À ne pas reprendre
- **Extraire les cookies des navigateurs** (`AR/agent_reach/cookie_extract.py`) et se connecter aux réseaux sociaux par cookies, ou passer par des proxys pour contourner les blocages (`llms.txt`). C'est risqué (conditions d'utilisation, sécurité) et sans objet sur Android.
- `_detect_environment` (`cli.py` l.1343-1380) repose sur des heuristiques serveur ou bureau (SSH, docker, DISPLAY) qui seraient fausses sous PRoot. Forge connaît déjà son environnement.
- La CLI de 2 400 lignes avec ses installateurs plateforme par plateforme : on garde le patron, pas le volume.
- Les canaux chinois, la publicité de sponsors, les specs `.openteams/`.

---

## 3. local-ai-runner (« LocalForge — Mobile AI IDE »)

**Ce que c'est.** Une **maquette statique** Next.js 16 / React 19 / shadcn / Tailwind, en un seul composant (`local-ai-runner/components/localforge-app.tsx`, 103 lignes), très probablement générée par v0 (`app/layout.tsx` : `generator`, `@vercel/analytics`). **Elle ne contient aucune inférence** : les modèles sont codés en dur (l.29-33), la réponse est simulée (`sendPrompt`, l.52-56) et l'import de fichier ne fait que basculer un booléen (`Models`, `accept=".gguf,.litertlm"`).

**Licence** : aucun fichier LICENSE ni README, et `package.json` porte `"name": "my-project"`, `"private": true`. Tous droits réservés par défaut : **on n'en copie rien**, on peut seulement s'en inspirer.

**Idées (faibles mais réelles) :**
- Une jauge de **mémoire de l'appareil** avec la part utilisée par le modèle actif (barre latérale, l.62-68), et des stats **tokens/s** (`Stat label="Tokens / sec"`). Pour Forge : ajouter mémoire et tokens/s à la ligne d'état quand le modèle est local.
- Des cartes de modèles indiquant format, quantification, taille et statut READY/DOWNLOAD, et des réglages expliqués en une ligne chacun (`SettingRow` avec label + description : température, fenêtre de contexte, performance).
- Une pastille « Local runtime active » et un message système « aucune donnée ne quitte cet appareil » (l.36) : un signal de confidentialité visible.

**Attention au nom** : « LocalForge » ressemble beaucoup à « Forge », le nom de travail. C'est un argument de plus pour vérifier le nom avant de le figer.

À ne pas reprendre : la pile web elle-même (Altrex est en Compose), Vercel Analytics (pistage), le code.

---

## 4. Synthèse classée par rapport valeur / effort

| Rang | Idée | Fichier | Étape |
|---|---|---|---|
| 1 | Budgets et arrêts composables (étapes, jetons, temps, bouton Stop « externe ») | `AC/conditions/_terminations.py`, `AC/base/_termination.py` | 1 |
| 2 | Sonde réelle des outils et `/doctor` (missing/broken/timeout avec consigne de réparation, JSON pour l'agent) | `AR/agent_reach/probe.py`, `AR/agent_reach/doctor.py` | 1 |
| 3 | Refus motivé renvoyé au modèle (Demander), relecteur automatique en option | `AC/agents/_code_executor_agent.py` | 1 (relecteur : 4) |
| 4 | Installation en vérification seule par défaut, `--system` sur accord, limites et règles de dossiers écrites pour l'agent | `AR/agent_reach/cli.py`, `AR/docs/install.md` | 1 |
| 5 | Détection de boucle et de blocage, puis replanification motivée | `AC/.../_magentic_one_orchestrator.py`, `_prompts.py` | 1-2 |
| 6 | Registre de progression JSON (fini ? boucle ? progrès ? suite ?) relié à la todo | `AC/.../_magentic_one/_prompts.py` | 2 (puis 4) |
| 7 | Reprise de session avec l'état complet (todo, plan, compteurs) | `AC/teams/_group_chat/_base_group_chat.py` | 2 |
| 8 | Registre outils/connecteurs : backends ordonnés, niveau, forçage par l'utilisateur ; renommage des outils MCP | `AR/agent_reach/channels/base.py`, `EXT/tools/mcp/_workbench.py` | 2-3 |
| 9 | Sous-agent = outil, qui renvoie un résumé (AgentTool / Society of Mind), handoffs explicites | `AC/tools/_agent.py`, `AC/agents/_society_of_mind_agent.py`, `AC/base/_handoff.py` | 4 |
| 10 | Blueprints : graphe JSON (conditions d'arêtes all/any), mode Visuel ⇄ JSON, validation `error+suggestion`, vue en direct de l'exécution | `AC/.../_graph/_digraph_group_chat.py`, `AGS/.../teambuilder/builder/*`, `AGS/autogenstudio/validation/validation_service.py`, `AGS/.../agentflow/agentflow.tsx` | 4 |

Extras : la skill de routage « annonce ce que tu utilises » et les mises à jour proposées une seule fois en fin de tâche (`AR/agent_reach/skill/SKILL_en.md`) pour le mode apprentissage et le mode build (étapes 2 et 4) ; mémoire et tokens/s dans la ligne d'état (local-ai-runner, idée seulement, étape 1).
