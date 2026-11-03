# Terminal du modèle : `run_command`, `read_file`, `write_file`, `edit_file`, `check_job` (format exact, pour le dataset Argora)

Avec cet outil, le modèle dispose de son propre Alpine Linux, distinct du terminal de l'utilisateur. Il n'est proposé que lorsque « Skills et outils » est activé.

## Spec (bloc `# Tools`)

`run_command` est la 6e entrée de `LlamaCppToolBridge.specs`, après `webSearch`. L'ordre des outils est donc : `load_skill`, `run_js`, `run_intent`, `runMcpTool`, `webSearch`, `run_command`. L'encodage est le même que pour les autres specs (org.json, `/` non échappé).

```json
{"type":"function","function":{"name":"run_command","description":"Run a shell command in your own Alpine Linux environment (apk add, python3, git, curl…). The user sees and approves every command. Returns the exit code and the output.","parameters":{"type":"object","properties":{"command":{"type":"string","description":"The shell command to run (sh syntax), from /root."}},"required":["command"]}}}
```

Noms d'argument acceptés : `command`, ainsi que les alias `cmd` et `shell`.

## Résultat (encodé par Gson)

Les clés apparaissent dans cet ordre :
- `exit_code` : une chaîne de caractères ;
- `note` : présente seulement si le délai est dépassé ;
- `output` ;
- `status` : `succeeded` si le code de sortie vaut 0 et que le délai n'est pas dépassé, `failed` sinon.

```json
{"exit_code":"0","output":"Python 3.14.10","status":"succeeded"}
```

- Délai dépassé : `"note":"stopped: took longer than 120 s"`.
- Sortie vide : `"output":"(no output)"`.
- Sortie de plus de 8 000 caractères : seule la fin est gardée, précédée de `[… N caractères du début omis …]\n`.
- Les secrets sont masqués (`SecretRedactor`).
- Le résultat est encadré comme un contenu non fiable, avec la source « résultat de l'outil run_command », dans le même cadre `DONNÉES-{nonce}` que les résultats web, MCP et JS.

## Refus et erreurs

| Cas | Réponse |
|---|---|
| L'utilisateur refuse | `{"result":"refused: the user did not allow this command. Do not run it again; tell the user.","status":"failed"}` |
| Aucun écran pour demander l'accord | `{"result":"refused: the user could not be asked to allow this command. Do not try again; tell the user.","status":"failed"}` |
| Commande vide | `{"error":"The run_command tool requires a non-empty \"command\" argument.","status":"failed"}` |
| Linux indisponible | `{"error":"Linux is not available on this device.","status":"failed"}` |
| Installation ratée | `{"error":"Linux could not be installed: …","status":"failed"}` |

## Environnement (pour écrire des exemples réalistes)

- Alpine 3.24.3 aarch64 via PRoot, gestionnaire de paquets `apk`.
- Shell `/bin/sh` (busybox ash), lancé avec `sh -lc` depuis `/root`, en root simulé.
- `apk add python3` installe Python 3.14.
- `altrex-setup` (terminal de l'utilisateur, mais aussi utilisable par le modèle) installe d'un coup bash, git, curl, python/pip, node/npm, gh, nano, vim… ; `--dev` ajoute gcc, make, cmake, go et rust. Les raccourcis `pkg` (comme dans Termux), `apt` et `apt-get` traduisent vers `apk` (`pkg install x` = `apk add x`).
- Accès réseau identique à celui de l'appli.
- Délai de 120 s par commande.
- Pas de pseudo-terminal pour l'outil : les commandes interactives (`vi`, `top`, `python` sans `-c`) ne marchent pas.
- Comportement attendu du modèle :
  - une seule commande courte et précise à la fois ;
  - installer un outil avant de s'en servir ;
  - lire le code de sortie ;
  - après un refus, ne pas relancer la commande mais expliquer la situation à l'utilisateur.

## Prompt système de l'agent

Quand « Skills et outils » est activé, le prompt (`AgentChatTaskModule.kt`) liste tous les chemins possibles, dans cet ordre :
1. répondre directement, sans outil (question, explication, code) ;
2. `run_command` (commande, paquet, exécution de code) ;
3. `webSearch` (information récente) ;
4. `load_skill` (skill de la liste) ;
5. `runMcpTool` (seulement si un outil MCP est actif).

L'ancien prompt ne connaissait que les skills et le MCP : les modèles inventaient des noms de skill (« yum ») au lieu d'utiliser le terminal.

Le prompt contient aussi une règle (« fais-le toi-même avec `run_command`, ne dis pas à l'utilisateur de taper la commande, n'invente pas de résultat ») et trois exemples :
- « install python 3 » → `apk add python3` ;
- « quelle version de Linux ? » → `uname -a` ;
- « clone … » → `apk add git && git clone …`.

Le texte exact se trouve dans `AGENT_PROMPT_HEAD` (`AgentChatTaskModule.kt`).


## Mise à jour 2026-11 : terminal façon Claude Code

### Specs (bloc `# Tools`, après `webSearch`, dans cet ordre)

Les outils du terminal se suivent dans cet ordre : `run_command`, `read_file`, `write_file`, `edit_file`, `check_job`. Ils ne sont proposés que si le Linux est disponible.

Paramètres de `run_command` :
- `command` (string, obligatoire) : le répertoire courant et les variables exportées sont conservés d'une commande à l'autre.
- `timeout` (integer, facultatif) : jusqu'à 600 s, 120 par défaut.
- `background` (boolean, facultatif) : renvoie tout de suite un `job_id`.

Autres outils :

| Outil | Paramètres |
|---|---|
| `read_file` | `path` ; `offset` et `limit` facultatifs (lignes, 300 par défaut) |
| `write_file` | `path`, `content` |
| `edit_file` | `path`, `old_string`, `new_string` ; `replace_all` facultatif. `old_string` doit être unique, sauf avec `replace_all`. |
| `check_job` | `job_id` ; `stop` facultatif |

Les descriptions exactes sont dans `LlamaCppToolBridge.specs`.

### Résultats

`run_command` :
```json
{"exit_code":"0","output":"…","cwd":"/root/projet","status":"succeeded"}
```
- Délai dépassé : `note` = « stopped: took longer than N s. For long work, use background=true and check_job. ».
- Lancé en arrière-plan :
```json
{"job_id":"job1","note":"Started in the background. Read its output with check_job(\"job1\"); stop it with check_job(\"job1\", stop=true).","status":"succeeded"}
```

`read_file` (lignes numérotées au format `%6d\t`, coupées à 500 caractères) :
```json
{"path":"a.py","total_lines":"42","content":"     1\timport os\n…","note":"Lines 301 to 420 not shown: call read_file again with offset.","status":"succeeded"}
```

`write_file` :
```json
{"path":"…","bytes":"123","status":"succeeded"}
```

`edit_file` :
```json
{"path":"…","replacements":"1","status":"succeeded"}
```

Erreurs de `edit_file`, au format `{"error":…,"status":"failed"}` :
- « old_string was not found in the file. Read the file again and copy the text exactly. »
- « old_string was found N times. Add surrounding lines to make it unique, or set replace_all. »

`check_job` :
```json
{"job_id":"job1","command":"…","state":"running","new_output":"…","status":"succeeded"}
```
- `state` vaut `running`, `finished` ou `stopped`.
- `exit_code` est présent quand la tâche est terminée.

Tous ces résultats sont encadrés comme contenu non fiable (`DONNÉES-{nonce}`).

### Refus (les mêmes textes que `run_intent` et MCP)

- `{"result":"refused: the user did not allow this action. Do not try again; tell the user.","status":"failed"}`
- `{"result":"refused: the user could not be asked to allow this action. Do not try again; tell the user.","status":"failed"}`

### Autorisations (côté appli, invisibles pour le modèle)

**Commandes**, selon le mode réglé :
- **Par défaut :** les commandes en lecture seule passent librement (`ls`, `cat`, `grep`, `git status`…, sans `;`, `&`, `>` ni `$(`) ; les autres sont demandées.
- **Toujours me demander.**
- **Tout autoriser.**

Le bouton « Toujours » d'une demande s'applique au programme concerné, par exemple « npm ». Il ne couvre jamais une commande enchaînée (avec `;` ou `&`).

**Fichiers :** `write_file` et `edit_file` sont demandés, sauf si l'utilisateur a activé « Modifier ses fichiers sans demander ».

**Comportement attendu du modèle :**
- lire un fichier avant de le modifier ;
- utiliser `edit_file` pour les petites modifications et `write_file` pour un nouveau fichier ;
- lancer en arrière-plan les serveurs et les tâches longues, puis les suivre avec `check_job`.
