# Altrex sur PC : étude et décision (phase 0)

Version du 2026-11-11. Ce document tranche l'architecture de la version PC d'Altrex (cible principale : Windows `.exe` ; Linux et macOS quand c'est peu coûteux). La version PC sert d'abord **Forge** (le mode de programmation, `docs/forge/FORGE.md`) avec de plus gros modèles locaux (Argora 9B, llama.cpp avec GPU) et des modèles cloud.

## 1. Décision

**Option D : un noyau en Rust, dans le dossier `pc/`, sans toucher au code Android.**

- `pc/crates/altrex-core` : le moteur de Forge réécrit en Rust, sans interface (outils, autorisations, mode Plan, journal JSONL, mémoire ALTREX.md, clients cloud, pilotage de `llama-server`, serveur d'aperçu, coffre des clés). Tout y est testé par `cargo test`.
- `pc/crates/altrex-pc` : l'application de bureau. Au départ une interface **egui** (Rust pur, un seul exécutable) ; le noyau ne dépend pas de l'interface, qui peut être remplacée (Tauri 2, § 4) sans réécrire le moteur.
- Android et PC restent alignés par un **contrat partagé** (§ 5) : les schémas figés de `docs/forge/OUTILS.md`, le format du journal, et des vecteurs de test communs exécutés par les tests Kotlin **et** Rust.

Pourquoi, en bref : l'utilisateur préfère Rust (poids fort, « à valeur égale, Rust ») ; l'exécutable est léger (quelques dizaines de Mo au plus, contre une JVM embarquée + Compose) ; un `.exe` Windows se produit **depuis ce conteneur Linux** par compilation croisée (impossible avec `jpackage`) ; le risque de régression Android est nul puisqu'aucun fichier Android ne change. Le prix est réel et chiffré au § 3 : le moteur Kotlin n'est pas réutilisable tel quel, il est réécrit.

## 2. Options étudiées

| | A. Kotlin Multiplatform + Compose Desktop | B. Tauri/Electron + noyau Kotlin en service local | C. Module JVM partagé + Compose Desktop (variante légère de A) | **D. Rust** |
|---|---|---|---|---|
| Réutilisation du code Forge | Maximale (moteur, outils, tests) | Bonne (noyau), interface à refaire | Maximale, sans plugin KMP sur l'appli Android | **Aucune** : réécriture, alignée par contrat |
| Poids de la distribution | 80 à 130 Mo (JVM embarquée, Skia) | 100 à 200 Mo (JVM + WebView ou Chromium) | 80 à 130 Mo | **10 à 25 Mo** (egui) ; 10 à 20 Mo (Tauri, WebView2 du système) |
| Démarrage, mémoire | JVM : 1 à 2 s, 200 à 400 Mo | deux processus, plus lourd | comme A | natif : instantané, quelques dizaines de Mo |
| Risque de régression Android | Moyen à fort (plugin KMP, AGP 9, R8) | Faible à moyen | Moyen (déplacement de ~6 000 lignes, visibilité `internal`, R8) | **Nul** |
| `.exe` depuis Linux | Non (`jpackage` ne croise pas) | Electron oui, JVM non | Non | **Oui** (mingw ou cargo-xwin), vérifié ici |
| Licences | Apache-2.0 | MIT/Apache-2.0 (Electron : MIT) | Apache-2.0 | MIT/Apache-2.0 (détail § 9) |
| Mises à jour | à écrire (pas de mécanisme intégré) | Tauri/Electron : updater intégré | à écrire | Tauri : updater ; egui : à écrire (§ 10) |
| Effort pour un squelette | Moyen | Fort (deux piles + IPC) | Faible à moyen | Fort (réécriture), mais sans dette côté Android |
| Divergence Android/PC | Faible (même code) | Faible | Faible | **À tenir par le contrat partagé** |

**A et C (Kotlin).** C'est le chemin le plus court vers un moteur identique : le cœur de Forge est déjà du Kotlin presque pur (`forge/engine`, `forge/tools`, `forge/journal`, `forge/preview`, `runtime/CloudApi.kt`, `runtime/CloudTools.kt`, `agent/ToolCalling.kt` n'importent rien d'Android ; seuls `RuntimeModelPort`, `LinuxShellPort`, `ProjectSnapshots`, `ForgeDoctor`, `ProjectImporter`, `forge/service` et `forge/ui` en dépendent). Un essai d'extraction a été commencé puis **annulé** quand la préférence pour Rust est arrivée : il demandait de scinder `AgentShell` et `CloudApi`, de déplacer des tests, et de revalider R8. Contre : une JVM embarquée (taille, démarrage), pas de `.exe` depuis Linux, et un risque non nul pour l'appli Android à chaque déplacement.

**B (Tauri/Electron + noyau Kotlin).** Cumule les défauts : une JVM **et** une WebView, deux processus, un protocole local à maintenir. Écartée.

**D (Rust).** Retenue. Les points vérifiés :
- chaîne Rust présente dans le conteneur (cargo 1.97) ; cible `x86_64-pc-windows-gnu` et `gcc-mingw-w64` installables : la compilation croisée vers Windows est possible ici (résultat noté dans `docs/pc/README.md`) ;
- llama.cpp : pilotage de `llama-server` (§ 6), pas de liaison native ;
- licences des crates : MIT ou Apache-2.0 (§ 9), aucune GPL.

## 3. Ce que coûte Rust, honnêtement

Rien du Kotlin de Forge n'est repris tel quel. À réécrire (volumes Kotlin actuels, hors interface) :

| Bloc | Kotlin (lignes) | État dans le squelette PC |
|---|---|---|
| Moteur (boucle, limites, rappels, Stop) | `ForgeEngine` 973, `LoopGuard` 126, `RateLimit` 69 | boucle, limites, rappels, Stop ; anti-boucle simple |
| Autorisations et modes | `PermissionPolicy` 191, `ForgeMode` 65, `AgentShell` (lecture seule) ~80 | port fidèle + dialecte PowerShell |
| Outils fichiers et recherche | `FileTools` 305, `EditMatcher` 120, `SearchTools` 252, `ProjectPaths` 68 | Read, Write, Edit, MultiEdit, Glob, Grep |
| Shell et tâches de fond | `LinuxEnv` 647, `AgentJobs` 252, `CommandWait` 64, `ProcessTree` 188 | shell natif (PowerShell, sh), tâches de fond |
| Journal et état | `ForgeEvent` 251, `SessionJournal` 83, `SessionState` 315 | même format, relecture et réparation |
| Espace de travail, mémoire | `ForgeWorkspace` 233, `MemoryChain` 98, `ProjectContext` 74 | mêmes dossiers, chaîne ALTREX.md |
| Clients cloud | `CloudApi` 614, `CloudTools` 535 | Anthropic et format OpenAI, appels natifs |
| Appels d'outils (texte) | `ToolCalling` 332 | même protocole |
| Aperçu | `PreviewServer` 331 | serveur local statique |
| Compaction, instantanés `/undo`, Build, Apprentissage, WebFetch/WebSearch | ~1 500 | **reportés** (§ 11) |

Soit environ 6 000 lignes de Kotlin et 2 500 lignes de tests dont l'équivalent Rust est à écrire et à maintenir. Le risque principal n'est pas l'effort initial mais la **divergence** : deux moteurs qui évoluent séparément. Le contrat du § 5 est la réponse.

## 4. Interface : egui maintenant, Tauri 2 possible ensuite

| | egui/eframe | Tauri 2 | iced | Slint |
|---|---|---|---|---|
| Nature | Rust pur, mode immédiat | noyau Rust + interface web (WebView2 sous Windows) | Rust pur, style Elm | Rust + langage de balisage |
| Licence | MIT/Apache-2.0 | MIT/Apache-2.0 | MIT | **GPL-3.0 ou licence commerciale ou « royalty-free » avec conditions** : écartée |
| Exécutable | un seul fichier, 10 à 25 Mo | 10 à 20 Mo + installateur NSIS | comparable à egui | — |
| Aperçu des projets web | navigateur du système | **WebView intégrée** | navigateur | — |
| Rendu riche (Markdown, code, diff) | à la main | natif (HTML/CSS) | à la main | — |
| Compilation croisée Linux → Windows | simple (mingw) | possible (cargo-xwin), plus lourde ; sous Linux il faut webkit2gtk | simple | — |
| Mises à jour | à écrire | plugin updater | à écrire | — |

Choix pour le squelette : **egui**. Raisons : tout en Rust, un seul exécutable sans dépendance (pas de WebView2 à vérifier), compilation croisée vérifiable ici, et les jetons de la maquette B (`docs/forge/maquettes/B-technique/tokens.md` : fond `#0C151B`, accent `#72D6DB`, Manrope, JetBrains Mono) s'appliquent directement. Limites assumées : Markdown et coloration à écrire, aperçu dans le navigateur du système.

**La question reste ouverte pour l'interface finale.** Si le rendu du fil (Markdown, diff, code) ou l'aperçu intégré deviennent prioritaires, Tauri 2 est le bon candidat : `altrex-core` ne connaît pas l'interface, le passage ne coûte que la couche d'écran. Tauri n'a pas été construit ici (webkit2gtk et SDK Windows trop lourds pour le disque du conteneur).

## 5. Contrat partagé Android ↔ PC

Trois pièces, toutes dans le dépôt :

1. **Schémas d'outils figés** : `docs/forge/OUTILS.md` § 2 (14 outils, ordre, paramètres). Côté Rust, `pc/crates/altrex-core/src/outils/schemas.jsonl` en est la copie exacte ; un test Rust relit `OUTILS.md` et échoue à la moindre différence (comme le test Kotlin existant). Le PC n'adapte que des **descriptions** (Bash : shell natif au lieu d'Alpine ; chemin absolu « dans le dossier du projet »), jamais un nom ni un paramètre.
2. **Format du journal** : `forge/journal/ForgeEvent.kt`, version 1 (une ligne JSON par événement, clés `t`, `ts`, puis les champs). Le Rust écrit les mêmes lignes et **garde sans perte** les événements qu'il ne produit pas encore (`checkpoint`, `undo`, `compaction`, `summary`, `learning`, `build`). Une session du téléphone s'ouvre sur PC et inversement.
3. **Vecteurs de test communs** : `docs/forge/contrat/*.json`, des cas « entrée → résultat attendu » lus par les tests des deux côtés :
   - `commandes.json` : lecture seule, simple, programme, dangereuse ;
   - `permissions.json` : mode + accords + action → autoriser, demander (titre, risque, « Toujours »), refuser ;
   - `appels.json` : texte du modèle → appels d'outils lus, texte affiché ;
   - `journal.json` : lignes du journal relues puis réécrites à l'identique, et état replié (mode, accords, tâches).

   Règle : **un changement de comportement commence par un vecteur**. Le test Kotlin (`ContratPartageTest`) et le test Rust (`tests/contrat.rs`) lisent les mêmes fichiers ; si l'un des deux moteurs s'écarte, sa suite échoue.

Même arborescence de travail des deux côtés : `projets/<nom>/`, `meta/<nom>.json`, `sessions/<projet>/<id>.jsonl`, `ALTREX.md` global ; dans un projet, `ALTREX.md`, `blueprint/`, `.altrex/plans/`, `.altrex/sorties/`.

## 6. Moteur d'inférence local

**`llama-server` officiel piloté en HTTP**, pas de bibliothèque liée.

- Pourquoi pas la crate `llama-cpp-2` (liaison) : elle compile llama.cpp avec CMake pour chaque backend (CUDA, Vulkan), ce qui casse la compilation croisée simple, alourdit la CI et lie l'appli à une version de llama.cpp. Un serveur séparé se met à jour sans recompiler Altrex (une architecture nouvelle comme Qwen3.5 demande juste un binaire plus récent), et un plantage du moteur ne tue pas l'appli.
- Binaire : les versions officielles de llama.cpp (licence MIT) publient pour Windows des archives CPU, **CUDA** (NVIDIA) et **Vulkan** (AMD, Intel, NVIDIA). L'appli lance un binaire **fourni par l'utilisateur** (chemin dans les Réglages, ou `llama-server` trouvé dans le `PATH` ou à côté de l'exécutable) ; le téléchargement à la demande est documenté (`docs/pc/README.md`) et son automatisation est une étape suivante (choix du backend selon le GPU, vérification d'empreinte).
- Lancement : `llama-server -m <modèle.gguf> --host 127.0.0.1 --port <port> -c <contexte> -ngl <couches GPU>`, attente de `/health`, arrêt à la fermeture. Un serveur déjà lancé (adresse dans les Réglages) est utilisable tel quel.
- Protocole : `/v1/chat/completions` en flux, avec le **protocole texte** de Forge (`<tool_call>`), c'est-à-dire exactement le texte de `OUTILS.md` sur lequel Argora est entraîné. Le gabarit de conversation est celui du GGUF, appliqué par le serveur.
- Modèles : Argora GGUF (9B sur PC, `Q5_K_M` ou plus), tout GGUF compatible.

## 7. Environnement d'exécution des outils (sans PRoot)

- **Shell natif**. Windows : PowerShell (`pwsh` s'il est installé, sinon `powershell.exe`) ; Git Bash ou WSL2 en option plus tard (réglage du shell). Linux et macOS : `sh`.
- Le modèle doit savoir où il est : le prompt système du PC décrit le système, le shell et ses commandes (« PowerShell : `Get-ChildItem`, `Select-String`… pas de `ls -la`, `grep`, `&&` »), et la description de l'outil Bash nomme le shell. Les noms d'outils ne changent pas (`Bash` reste `Bash`).
- Lecture seule et commandes à risque : les règles POSIX du téléphone sont reprises telles quelles (vecteurs communs) ; un **dialecte PowerShell** s'y ajoute (cmdlets de lecture, `Remove-Item -Recurse` sur une racine, `iwr | iex`, `format`, `Start-Process -Verb RunAs`…). Sous PowerShell, parenthèses et accolades évaluent du code : une commande qui en contient n'est jamais « lecture seule ».
- Différence de fond avec le téléphone : il n'y a **aucun bac à sable**. Une commande approuvée agit sur l'ordinateur réel de l'utilisateur. La politique est donc la même que sur téléphone (toute commande qui n'est pas de lecture est demandée, sauf « Tout autoriser »), et l'écran le dit.
- Dossier courant gardé d'une commande à l'autre ; commande silencieuse trop longtemps : passée en arrière-plan (comme sur téléphone) ; Stop tue l'arbre de processus (`taskkill /T /F` sous Windows, groupe de processus ailleurs).

## 8. Autres décisions

- **Clés d'API** : chiffrées au repos. Windows : **DPAPI** (`CryptProtectData`, liée au compte Windows). Linux, macOS : AES-256-GCM avec une clé locale en fichier `0600` (protège d'une copie du fichier de réglages, pas d'un accès au compte) ; trousseau du système (Secret Service, Keychain) plus tard. Les clés ne sont jamais écrites dans le journal ni montrées au modèle.
- **Aperçu** : serveur statique local (`127.0.0.1`, port libre, chemins confinés au projet) + navigateur du système. WebView intégrée seulement avec Tauri (§ 4).
- **Terminal** : pas de terminal intégré au départ ; le terminal du système suffit (bouton « Ouvrir un terminal dans le projet » plus tard).
- **MCP stdio** : possible nativement sur PC (lancement d'un processus, JSON-RPC sur stdin/stdout), bien plus simple que sur Android. Reporté après le squelette ; la crate officielle `rmcp` (MIT/Apache-2.0) ou un client minimal maison.
- **Notes vocales, Discuter, Argora « second cerveau »** : reportés. La version PC commence par Forge.
- **Format des projets et sessions** : celui du téléphone (§ 5). Dossier de travail par défaut : `~/Altrex` (réglable).

## 9. Licences

Dépendances directes du squelette : `serde_json`, `regex`, `base64`, `similar` (Apache-2.0), `ureq`, `aes-gcm`, `getrandom`, `windows-sys`, `eframe`/`egui` : toutes MIT ou Apache-2.0 (ou les deux). TLS : `rustls` (Apache-2.0/ISC/MIT) avec `ring` (ISC et dérivés d'OpenSSL, permissive). Polices Manrope et JetBrains Mono : SIL OFL 1.1 (déjà dans l'appli Android). **Aucune GPL ni LGPL.** Slint (GPL-3.0 hors licence commerciale) est écartée pour cette raison. `llama-server` est un programme séparé sous MIT. Un contrôle automatique (`cargo deny`) est à ajouter à la CI.

## 10. Empaquetage, signature, mises à jour

- **Windows** : `altrex-pc.exe` autonome. Compilation croisée depuis Linux (`x86_64-pc-windows-gnu`, mingw) pour un essai rapide ; la **version publiée** se construit sur un runner Windows (`.github/workflows/build-pc.yml`, cible MSVC), qui lance aussi les tests sous Windows. Installateur (`.msi` avec WiX ou NSIS) : étape suivante.
- **Signature de code : non faite.** Sans certificat, Windows SmartScreen avertit au premier lancement. À prévoir : certificat (OV, ou Azure Trusted Signing) et signature dans la CI.
- **Mises à jour** : rien d'automatique au départ (téléchargement de la nouvelle version). Ensuite : vérification d'une version publiée (GitHub Releases) avec empreinte signée, ou l'updater de Tauri si l'interface y passe.
- Linux : binaire simple (`.tar.gz`) ; macOS : non construit ici (il faut un runner macOS, et une notarisation Apple).

## 11. Ce qui est propre à Android (remplacé ou ignoré sur PC)

| Android | PC |
|---|---|
| Hilt, `ViewModel`, navigation Compose | rien : objets Rust simples |
| `ForgeService` (service au premier plan), notifications | fil d'exécution du moteur ; notifications du système plus tard |
| PRoot + Alpine (`LinuxEnv`, `AgentShell`), Termux | shell natif (§ 7) |
| WebView (aperçu, `run_js`) | navigateur du système (§ 8) |
| LiteRT-LM, AICore (ML Kit), llama.cpp en JNI | `llama-server` (§ 6) |
| Keystore (`SecretCipher`), `SharedPreferences`, DataStore | DPAPI / fichier chiffré, `reglages.json` |
| Espaces Hugging Face distants, téléchargement de modèles dans l'appli | reportés |
| SAF (`ProjectImporter`), `DocumentFile` | le projet **est** un dossier du disque |
| Instantanés par le git du Linux (`/undo`) | à refaire avec le git du système ou des copies (reporté) |
| Notes vocales, caméra, PDF, intents, connecteurs OAuth | reportés ou sans objet |

## 12. Phases

1. **Phase 1 (ce lot)** : squelette qui tourne. Fenêtre, écran des projets, session Forge de bout en bout avec un modèle cloud (clé chiffrée) et avec `llama-server`, outils Read/Write/Edit/MultiEdit/Glob/Grep/Bash/BashOutput/KillShell/TodoWrite/AskUserQuestion/ExitPlanMode, autorisations et mode Plan, journal, aperçu local. Vecteurs communs. Workflow Windows rédigé.
2. **Phase 2 : parité du moteur.** Compaction à deux niveaux, instantanés et `/undo` `/diff`, WebFetch/WebSearch (avec `UrlGuard`), anti-boucle complet, commandes `/`, `@fichier`, mode léger, saisie pendant le travail, Build et Apprentissage. Chaque bloc arrive avec ses vecteurs.
3. **Phase 3 : confort PC.** Téléchargement de `llama-server` et des GGUF, détection du GPU, MCP stdio, rendu Markdown et diff soigné (ou passage à Tauri), terminal, installateur, signature, mises à jour.
4. **Phase 4** : synchronisation téléphone ↔ PC des projets et sessions (le format est déjà commun).

## 13. Risques

- **Divergence des deux moteurs** : tenue par le contrat (§ 5), à condition de l'étendre à chaque changement. C'est le risque n° 1.
- **Windows non vérifié ici** : pas de machine Windows dans le conteneur. PowerShell, DPAPI, `taskkill`, les chemins `C:\…` et l'affichage ne sont que compilés (compilation croisée), pas exécutés. La CI Windows lance les tests ; l'essai réel reste à faire sur une machine.
- **Pas de bac à sable sur PC** : une commande approuvée agit sur la vraie machine. Les règles « à risque » de PowerShell sont nouvelles et moins éprouvées que celles du téléphone.
- **egui** : rendu du texte riche limité ; OpenGL requis (pilotes anciens ou bureau à distance : prévoir le moteur `wgpu`).
- **Protocole texte avec `llama-server`** : dépend du gabarit du GGUF ; les gabarits qui exigent une alternance stricte des rôles sont gérés en fusionnant les messages consécutifs.
