# Outil `run_command` : format exact (pour le dataset Argora)

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
- `apk add python3` installe Python 3.14. Les raccourcis `pkg` (comme dans Termux), `apt` et `apt-get` traduisent vers `apk` (`pkg install x` = `apk add x`).
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
