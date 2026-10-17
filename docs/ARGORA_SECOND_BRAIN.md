# Argora et le skill « second-brain » : ce que le modèle doit savoir appeler

Destinataire : le projet d'entraînement Argora (`karle-olymp/argora`).
Le skill est dans `app/src/main/assets/skills/second-brain/` (instructions : `SKILL.md`, mémoire : `scripts/index.html`).

## Format d'appel dans Altrex (modèles GGUF)

Altrex décrit les outils dans le prompt système au format Hermes (comme Qwen). Le modèle répond avec un bloc
`<tool_call>{json}</tool_call>`, et Altrex renvoie le résultat dans un bloc `<tool_response>`.
Seuls quatre noms d'outils existent : `load_skill`, `run_js`, `run_intent`, `webSearch`.
Les « outils » du second cerveau (`save_to_para`, `send_sms`…) passent **par** `run_js` ou `run_intent`.

Séquence attendue :
1. `load_skill` avec `{"skillName": "second-brain"}` pour lire les instructions ;
2. puis un ou plusieurs `run_js` / `run_intent` ;
3. puis une réponse courte en français qui dit ce qui a été fait.

### Mémoire (run_js)
```
<tool_call>{"name": "run_js", "arguments": {"skillName": "second-brain", "scriptName": "index.html", "data": "{\"action\":\"save_to_para\",\"category\":\"projets\",\"title\":\"Audit nginx\",\"content\":\"...\",\"tags\":[\"nginx\"],\"deadline\":\"2026-10-23\"}"}}</tool_call>
```
`data` est une **chaîne** JSON (guillemets échappés), pas un objet.

| action | champs |
|---|---|
| `save_to_para` | `category` (projets, domaines, ressources, archives), `content`, `title`, `tags`, `deadline` |
| `retrieve_memory` | `query`, `category_filter` |
| `list_notes` | `category` |
| `update_note` | `id` + champs à changer |
| `archive_note`, `delete_note` | `id` |
| `export_memory` | — |
| `evolve_code` | `target` (`rule` ou `action`), `name`, `new_code`, `reason` |
| `list_rules`, `undo_evolution` | — / `name` |

### Téléphone (run_intent)
```
<tool_call>{"name": "run_intent", "arguments": {"intent": "set_alarm", "parameters": "{\"hour\":7,\"minute\":30,\"message\":\"Sauvegarde NAS\"}"}}</tool_call>
```

| intent | parameters |
|---|---|
| `get_current_date_and_time` | `{}` |
| `create_calendar_event` | `title`, `description`, `begin_time`, `end_time` (AAAA-MM-JJTHH:MM:SS) |
| `create_contact` | `name`, `phone`, `email` |
| `set_alarm` | `hour`, `minute`, `message` |
| `send_sms` | `phone`, `message` |
| `device_control` | `action` : `toggle_music` ou `toggle_flashlight` |

Les intents ouvrent l'appli système concernée, pré-remplie : l'utilisateur confirme toujours.

## Ce qu'il faudrait dans le dataset

Quelques dizaines d'exemples multi-tours (écrits ou relus à la main, cf. règle de provenance du CLAUDE.md d'Argora) :
- ranger une note dans la bonne catégorie PARA, en **résumant** (pas de copie mot à mot) ;
- chercher avec `retrieve_memory` **avant** de répondre à « qu'est-ce que je sais sur… » ;
- projet avec date → `save_to_para` puis `get_current_date_and_time` puis `create_calendar_event` ;
- rappel dans les 24 h → `set_alarm` ;
- lire un `<tool_response>` en erreur et corriger l'appel (catégorie inconnue, champ manquant) ;
- ne **pas** appeler d'outil quand la question n'en a pas besoin.
