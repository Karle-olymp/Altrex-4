# Forge : ce qu'on retient d'Acode (éditeur de code Android)

Étude en lecture seule (`git clone --depth 1` dans un dossier neuf du scratchpad, puis ls/grep/sed/cat ; rien
exécuté, rien installé, rien copié). Dépôt `Acode-Foundation/Acode`, commit `6962af1` (29/10/2026), version
`1.13.5` (`package.json`). Chemins relatifs à la racine du dépôt. Le sous-module `codemirror-lsp-client` n'a pas été
récupéré (dossier vide) : non lu.

Étapes Forge : 0 look, 1 fondations, 2 confort, 3 aperçu/scan/Blueprint, 4 sous-agents/hooks/modes perso.
Valeur : haute / moyenne / basse. Effort : S (jours), M (1-2 semaines), L (plus).

À rapprocher de l'état actuel de Forge (FORGE.md §15, 2026-11-05) : `PreviewServer.kt` existe déjà (127.0.0.1,
port éphémère, jeton, `no-store`, traversée refusée, rechargement quand l'agent écrit). Plusieurs points d'Acode
sont donc **en retrait** de Forge (voir « À éviter ») ; on ne retient que ce qui manque.

---

## 1. Fiche

- **Licence** : MIT, « Copyright 2020 Foxdebug (Ajit Kumar) » (`license.txt` à la racine ; `package.json`
  `"license": "MIT"`). Réutilisation **permise** juridiquement (avec la mention de copyright), mais la règle Forge
  reste : idées seulement, aucun code ni texte repris.
- **Ce que c'est** : éditeur de code pour Android (Play Store + F-Droid), appli **Cordova** (`cordova-android`
  15.1, `config.xml` : minSdk 26, targetSdk 36) : toute l'interface est en JS/HTML dans la WebView, les fonctions
  natives sont des **plugins Cordova en Java** dans `src/plugins/` (serveur HTTP, navigateur intégré, WebView
  avancée, SAF, FTP, SFTP, terminal, PRoot, auth, achats, pubs AdMob).
- **Éditeur** : a **migré d'Ace vers CodeMirror 6** (CHANGELOG l.227 « Acode has migrated from Ace to CodeMirror » ;
  il reste une couche de compatibilité `aceModes` pour les plugins, `src/lib/acode.js` l.418). ~25 paquets
  `@codemirror/lang-*`, `@codemirror/lsp-client`, Emmet. Moteur de rendu **canvas WebGL2** optionnel et
  expérimental (`@acode.app/canvas-view` 0.0.3, MIT ; `src/cm/createEditorView.ts`).
- **Aperçu** : NanoHTTPD dans un plugin Java (`src/plugins/server/`), réponses fabriquées **côté JS**
  (`src/lib/run.js`, 584 l.) ; navigateur intégré natif (`src/plugins/browser/`, émulateur d'appareils) ; console
  maison ou **eruda** (téléchargé à la demande).
- **Terminal** : xterm.js 6 + Alpine minirootfs 3.21 sous **PRoot** (`src/plugins/proot/`, `src/plugins/terminal/`),
  pont processus ↔ WebSocket (`ProcessServer.java`, binaire `libaxs.so` = « acodex_server »).
- **Poids** : non mesuré (aucun APK construit). Indices : `src/plugins/proot` = 21 Mo dans le dépôt (par ABI : rootfs
  Alpine ~3,8 Mo + `libaxs.so` ~3,5 Mo + PRoot/talloc ~0,3 Mo, × 3 ABI) ; TypeScript et Prettier (+ 8 greffons)
  embarqués dans le bundle JS.

---

## 2. Aperçu (le cœur de la demande)

### Comment ça marche
- `run()` (`src/lib/run.js`) : un fichier `.md` ouvre la page d'aperçu Markdown (l.40-45) ; un `.js` ou la console
  ouvre `console.html` sur un port à part (`CONSOLE_PORT` 8159) ; sinon serveur sur `SERVER_PORT` 8158
  (`src/lib/config.js` l.11-13). Si le port est pris : `++port` et on recommence (l.171-185).
- Le serveur Java ne sert rien lui-même : pour **chaque requête** il envoie un objet `{requestId, path, …}` au JS,
  puis **attend en boucle** (`Thread.sleep(1)`, `NanoHTTPDWebserver.java` l.257-264) que le JS réponde par
  `{status, body}` ou `{path}` (fichier servi par Java, avec `Range`/`ETag`, l.96-210).
- Le JS (`handleRequest`, run.js l.195-390) choisit la source : **tampon de l'éditeur s'il est ouvert et non
  enregistré** (l.315, 340, 369), sinon le fichier ; fichiers SFTP/FTP/`content:` copiés dans le cache d'abord
  (`sendFile`, l.472-530).
- Point d'entrée : si le fichier actif n'est pas `index.html`, on cherche `index.html` à la racine du dossier ouvert
  (l.111-131) ; appui long sur ▶ = lancer **le fichier courant** (texte d'aide « preview info », `en-us.json` l.219 ;
  réglage `useCurrentFileForPreview`).
- URL sans extension : essai exact → `chemin.html` → `chemin/index.html` (l.302-333).
- HTML : injection après `<head>` d'un bloc commenté « Injected code » (viewport, script console, init eruda), qui
  **se retire du DOM** juste après exécution (`sendHTML`, l.422-466).
- **Pas de rechargement à chaud** : aucun livereload dans `src/` (grep `livereload|reload`) ; l'utilisateur relance
  l'aperçu ou recharge le navigateur. Forge est déjà en avance (rechargement quand l'agent écrit).
- Réglages d'aperçu (`src/settings/previewSettings.js`) : port du serveur, **port d'aperçu** (≠ serveur ⇒ ouvre
  directement `http://host:port`, pour un serveur de dev lancé dans le terminal, run.js l.49-60), mode **in-app /
  navigateur externe**, hôte, désactiver le cache, bouton console. Avertissement si **mode économie d'énergie** avant
  d'ouvrir le navigateur externe (l.157-168).

### Navigateur intégré et émulateur — `src/plugins/browser/android/com/foxdebug/browser/`
- Menu : Appareils, Désactiver le cache (`LOAD_NO_CACHE`), Console, Définir par défaut, Ouvrir dans le navigateur,
  Quitter (`Browser.java` l.233-335).
- Émulateur : liste de 17 gabarits (iPhone SE 320×568 … Laptop XL, 4K) + « Personnalisé » avec curseurs largeur /
  hauteur / **échelle** (`Emulator.java` l.45-81, l.117-140) ; la WebView est redimensionnée et la balise
  `meta viewport` réécrite (`Browser.java` l.502-533) ; **mode bureau** = changement d'user-agent + largeur
  (l.436-473).

### WebView avancée — `src/lib/webview.js`, `src/plugins/webview/`
- API d'instances avec **événements** : `pageFinished`, `progressChanged`, `httpError`, `resourceError`,
  `sslError`, `consoleMessage`, `jsAlert/Confirm/Prompt`, `permissionRequest`, `renderProcessGone`,
  `navigationBlocked`, `downloadRequested`… (webview.js l.9-47) ; réglages validés par listes fermées (cache, contenu
  mixte, user-agent, assombrissement algorithmique, l.54-108).
- **Garde de navigation** : `allowNavigation=false` bloque tout ; sinon http(s) seulement, refus annoncé avec motif
  (`WebViewInstance.java` l.973-1001).
- **Profils isolés** (androidx.webkit `MULTI_PROFILE`) : cookies/stockage séparés par profil nommé ; « incognito »
  = profil éphémère supprimé à la fermeture et **purgé au démarrage suivant** s'il en reste ; repli honnête « pas
  isolé » si le fournisseur WebView ne sait pas faire (`ProfileManager.java` l.20-60, 113-200).
- Plantage du moteur de rendu traité (`onRenderProcessGone`, l.1094) ; console relayée (`onConsoleMessage`, l.1133).

### Console
- Console maison (`src/lib/console.js`) : les commandes tapées tournent dans un **Worker** (`consoleRuntime.js`) :
  une seule à la fois, **délai 30 s** puis `terminate()` (l.1, 113) ; le Worker plafonne à **100 messages/s**, et
  les objets sont « photographiés » avec bornes (profondeur 4, 100 propriétés, 500 valeurs ; `consoleWorker.js`
  l.1-5, 89). Capture `window.error` (console.js l.186), thème synchronisé avec l'appli.
- Alternative eruda (MIT) téléchargée depuis jsDelivr à l'activation (`appSettings.js` l.421-440,
  `config.js` l.23).

---

## 3. Éditeur (CodeMirror 6 sur mobile)

- **Sélection tactile** (`src/cm/touchSelectionMenu.js`, 1289 l.) : classement des tapes simple/double/triple avec
  tolérances (500 ms, 20 px, ou même ligne à ±2 colonnes, l.17-60), poignées « goutte », menu placé hors des
  poignées et du curseur (marges 10-28 px), défilement automatique au bord (40 px), menu en grille au-delà de 10
  actions ; vibration au tap (réglage `vibrateOnTap`).
- **Barre de touches rapides** (`src/components/quickTools/items.js`, `src/handlers/quickToolsInit.js`,
  `src/cm/quickToolsModifierKeys.ts`) : ~50 boutons (Ctrl, Tab, Shift, Échap, flèches, `{}[]()<>;'"&|=/!~`#$%^-`,
  déplacer/copier ligne, annuler/refaire, rechercher, enregistrer, coller, tout sélectionner, palette de commandes) ;
  **2 rangées** défilables au doigt, ordre choisi par l'utilisateur (`quicktoolsItems`, `settings.js` l.198), page
  de réglage par glisser ; **modificateurs collants** (Ctrl/Shift/Alt/Meta restent armés jusqu'à la touche
  suivante, état visible sur la barre) ; **répétition à l'appui long** des flèches/Suppr ; Shift + symbole mappé.
- **Tap sur un numéro de ligne** = sélectionner la ligne, Shift = étendre (`src/cm/lineNumberSelection.ts`).
- Retour à la ligne qui **garde l'indentation** (`indentedLineWrapping.ts`), points de coupure sur la ponctuation
  (`punctuationWrapping.ts`), guides d'indentation, crochets arc-en-ciel, pastilles de couleur + sélecteur
  (`colorView.ts`, `colorChip.ts`), renommage auto de la balise fermante (`tagAutoRename.ts`), Emmet,
  `scrollPastEnd`, complétion par mots locaux.
- Saisie : option « textarea pour l'IME » (`settings.js` l.196) ; **EditContext de CM6 désactivé par défaut**
  (CHANGELOG, PR #2258) — point d'attention pour CM6 dans une WebView Android.
- Commandes et raccourcis centralisés : table `nom / description / touche / readOnly` (`src/lib/keyBindings.js`
  l.26+), fichier de raccourcis utilisateur (`commandRegistry.js` l.1690), **détection de conflits** (l.855) ;
  raccourcis partagés avec le terminal.
- 20 thèmes CM6 (`src/cm/themes/`) + éditeur de thème de l'appli (`src/pages/customTheme`).
- Formatage : Prettier standalone embarqué (babel, estree, html, postcss, markdown, yaml, typescript, graphql) qui lit
  les `.prettierrc*` du projet (`src/lib/prettierFormatter.js` l.7-27, 237).
- LSP : **HTML, CSS, JSON, TypeScript dans des Web Workers** (`src/cm/lsp/workers/`, services vscode-*-languageservice
  et `typescript` embarqués) ; autres langages (Python, Rust/Go/C…, Tailwind, Luau) lancés **dans l'Alpine** et
  reliés par un pont stdio ↔ WebSocket (`runtimes/axsBridge.ts`, fichiers de port `~/.axs/lsp_ports/`,
  `serverLauncher.ts` l.130) ; installation proposée avec accord, barre d'état LSP, page « Problèmes ».
- Pas de vue diff/fusion (`@codemirror/merge` absent).

## 4. Fichiers, projets, recherche

- Systèmes de fichiers : interne, externe (SAF via plugin `sdcard` : lecture/écriture/renommer/copier/déplacer,
  **surveillance de fichier**, index de travail natif `WorkspaceIndex.java` ~1950 l.), FTP (commons-net), SFTP
  (maverick-synergy, profils). Plus d'import GitHub intégré.
- **Conflits disque** (`src/lib/checkFiles.js` l.46-130) : à chaque retour dans l'appli, comparaison `mtime` puis
  contenu ; fichier propre modifié ailleurs ⇒ « recharger ? » ; fichier **non enregistré** modifié ailleurs ⇒ marqué
  `hasDiskConflict` et alerte « enregistrer écraserait les changements externes » ; fichier supprimé ⇒ marqué non
  enregistré.
- Restauration de session (`src/lib/saveState.js`) : onglets, curseur/sélections, défilement, **plis**, encodage,
  tampons non enregistrés, conflits ; dossiers ouverts avec l'état de l'arbre.
- Exclusions d'opérations par globs (`node_modules`… ; `src/utils/fileOperationExclusions.js`).
- Recherche dans les fichiers (`src/sidebarApps/searchInFiles/`) : Worker + cache de contenu IndexedDB (fichiers
  ≤ 512 Ko, marqués « sales » à l'édition ; `indexWorker.js` l.1-40, 109), casse, mot entier, inclure/exclure en
  globs, **tout remplacer**, résultats affichés dans une vue CodeMirror ; résultats en flux (CHANGELOG 1.13.5).
- Modèles de projet : un seul intégré (HTML : `index.html` + `css/` + `js/`, `<%name%>` remplacé), extensible par
  les plugins (`src/lib/projects.js`).
- Terminal : xterm.js + addons (fit, webgl, search, web-links, image, unicode11), sélection tactile dédiée
  (`terminalTouchSelection.js`), serveur WebSocket par processus **lié à 127.0.0.1** (`ProcessServer.java` l.31-32),
  page « Processus en cours », **DocumentsProvider** qui expose l'Alpine dans le sélecteur de fichiers Android
  (`AlpineDocumentProvider.java`), wrapper `rm` pour les liens sous PRoot.

## 5. Plugins

- Paquet zip avec `plugin.json` (`main`, `icon`, `readme`, dépendances, prix) installé depuis le magasin acode.app ou
  un fichier (`src/lib/installPlugin.js`) ; **chemins du zip assainis** (séparateurs, `..`, lettres de lecteur,
  chemins absolus ignorés ; l.331-390).
- Chargement = balise `<script>` dans **le document de l'appli** (`src/lib/loadPlugin.js`) ; le cadre **démonte
  l'ancienne version avant** de charger la nouvelle (commentaire l.20-28 : sinon le `destroy` de l'ancienne est
  perdu) ; le pont natif est « durci » (propriétés `cordova.exec` gelées) et une session de confiance ouverte
  **avant** le script du plugin (l.10-15).
- Registre de modules `acode.define/require` qui expose **les instances CM6 de l'appli** (`@codemirror/state`,
  `view`, `language`…) et ~70 services (fs, terminal, webview, lsp, settings, palette…) (`acode.js` l.385-459).
- Jeton par plugin + coffre de secrets + permissions déclarées (`pluginContext.js`, `Tee.java` l.199-282), mais les
  permissions ne couvrent que ces appels natifs : le plugin a, de fait, **tous les droits du JS de l'appli**.

## 6. i18n

- 34 langues en JSON plat (`src/lang/*.json`, 913 clés) ; `fr-fr.json` : **544 clés sur 913 identiques à l'anglais**
  (non traduites). Outil `utils/lang.js` (ajout/recherche/mise à jour) — non lancé.

---

## 7. Idées numérotées

| # | Idée | Où dans Acode | Valeur | Effort | Étape | Licence / risque |
|---|---|---|---|---|---|---|
| 1 | **Servir le tampon non enregistré** : `PreviewServer` consulte d'abord les fichiers ouverts dans l'éditeur Forge (contenu en mémoire), puis le disque ; l'aperçu reflète ce que l'utilisateur tape sans enregistrer | `run.js` l.315, 340, 369 | haute | S | 3 | idée seule ; aucun |
| 2 | **Rapport d'aperçu pour l'agent** : capter `consoleMessage`, `window.error`, `resourceError`, `httpError`, `renderProcessGone` de la WebView et les rendre au modèle (bornés : N messages/s, profondeur, taille) pour « vérifier le rendu, pas seulement le 200 » | `webview.js` l.9-47 ; `consoleWorker.js` l.1-5, 89 ; `WebViewInstance.java` l.1094, 1133 | haute | M | 3 | idée ; côté Kotlin `WebChromeClient`/`WebViewClient` natifs, pas d'injection nécessaire |
| 3 | **Émulateur de tailles** : puces Téléphone / Tablette / Bureau + personnalisé (largeur, hauteur, échelle), réécriture du viewport, user-agent bureau ; l'agent peut demander une capture à 2-3 tailles | `Emulator.java` l.45-140 ; `Browser.java` l.436-533 | haute | M | 3 | idée ; aucun |
| 4 | **Profil WebView isolé par projet** (androidx.webkit MULTI_PROFILE) : cookies/localStorage d'un projet séparés de l'appli et des autres projets ; profil éphémère « propre » + purge au démarrage ; repli affiché honnêtement si non pris en charge | `ProfileManager.java` ; `WebViewInstance.java` l.230-261 | haute | M | 3 | API Apache-2.0 (androidx) ; dépend de la version du WebView du téléphone (à vérifier sur S22) |
| 5 | **Garde de navigation de l'aperçu** : rester sur l'origine de l'aperçu, liens externes → navigateur système avec confirmation, schémas non http refusés avec motif | `WebViewInstance.java` l.973-1001 | haute | S | 3 | idée ; aucun |
| 6 | **Point d'entrée intelligent** : fichier actif sinon `index.html` du projet ; appui long sur ▶ = ce fichier seulement ; URL propres (`/a` → `a.html` → `a/index.html`) | `run.js` l.111-131, 302-333 | moyenne | S | 3 | idée ; aucun |
| 7 | **Menu de l'aperçu** : Recharger, Sans cache, Console, Taille, Ouvrir dans le navigateur ; avertissement mode économie d'énergie pour un serveur du Linux | `Browser.java` l.233-335 ; `run.js` l.157-168 | moyenne | S | 3 | idée |
| 8 | **Console de l'aperçu** : commandes exécutées hors de la page (Worker) avec délai (30 s) et une à la fois ; objets résumés avec bornes — même bornes pour ce qu'on renvoie au modèle (budget de jetons d'Argora) | `consoleRuntime.js` ; `consoleWorker.js` | moyenne | M | 3 | eruda (MIT) possible mais à **embarquer avec version épinglée** si on le prend |
| 9 | **Détection de modification externe** dans l'éditeur Forge : l'agent (Write/Edit/Bash) modifie un fichier ouvert ⇒ recharger si propre, **conflit visible** si non enregistré (jamais écraser en silence) | `checkFiles.js` l.46-130 ; `saveState.js` (`hasDiskConflict`, `diskMtime`) | haute | S | 2 | idée ; essentiel pour Forge (agent + humain sur les mêmes fichiers) |
| 10 | **Barre de touches rapides** configurable : 2 rangées défilables, modificateurs collants visibles, répétition à l'appui long, symboles de code, bouton palette de commandes ; ordre réglable | `quickTools/items.js` ; `quickToolsInit.js` ; `quickToolsModifierKeys.ts` | haute | M | 2 | idée ; à dessiner au look B+C |
| 11 | **Sélection tactile CM6** : classement des tapes avec tolérances, poignées, menu hors des poignées, défilement au bord, vibration ; tap sur numéro de ligne = ligne | `touchSelectionMenu.js` l.17-60 ; `lineNumberSelection.ts` | haute | M | 2 | idée ; CM6 (MIT) n'apporte pas ça seul |
| 12 | **Pièges CM6 en WebView Android** : EditContext désactivé par défaut, option textarea pour l'IME, retour à la ligne indenté | CHANGELOG PR #2258 ; `settings.js` l.196 ; `indentedLineWrapping.ts` | haute | S | 2 | idée ; à tester sur S22 (clavier Samsung, Gboard) |
| 13 | **Restauration de session** : onglets, curseurs, défilement, plis, tampons non enregistrés | `saveState.js` | moyenne | S | 2 | idée |
| 14 | **LSP web sans Linux** : diagnostics/complétion HTML, CSS, JSON (et TS) dans des Workers de la WebView de l'éditeur ; diagnostics aussi transmis à l'agent ; autres langages plus tard via le Linux (stdio ↔ WebSocket, port en fichier) | `src/cm/lsp/workers/` ; `runtimes/axsBridge.ts` ; `serverLauncher.ts` | moyenne | L | 3-4 | vscode-*-languageservice **MIT**, typescript **Apache-2.0** (poids : TS ≈ plusieurs Mo) |
| 15 | **Recherche dans le projet** : casse / mot entier / inclure-exclure en globs / tout remplacer, résultats en flux affichés comme du code ; index incrémental (fichiers « sales ») avec plafond de taille | `searchInFiles/` ; `indexWorker.js` l.1-40, 109 | moyenne | M | 2 | idée ; côté Forge `rg` dans le Linux ou Kotlin |
| 16 | **Formatage Prettier** embarqué dans la WebView de l'éditeur, qui lit le `.prettierrc` du projet | `prettierFormatter.js` | moyenne | S-M | 2 | Prettier **MIT** ; poids à mesurer |
| 17 | **Raccourcis clavier physiques** (clavier BT/DeX) : table nom/description/touche, fichier utilisateur, détection de conflits | `keyBindings.js` l.26+, 855 ; `commandRegistry.js` l.1690 | moyenne | S | 2 | idée |
| 18 | **Extensions CM6 « confort »** : pastilles + sélecteur de couleur, renommage de balise, Emmet, guides d'indentation, crochets arc-en-ciel | `src/cm/colorView.ts`, `tagAutoRename.ts`, etc. | moyenne | S chacune | 2 | Emmet CM6 : licence **non déclarée** dans `package-lock.json` (MIT en amont, à vérifier avant usage) |
| 19 | **Fichiers du projet visibles dans l'appli Fichiers d'Android** via un `DocumentsProvider` exposant le dossier projets (sous PRoot) | `AlpineDocumentProvider.java` | moyenne | M | 3 | idée ; attention à n'exposer que le dossier projets |
| 20 | **Modèles de projet** (HTML de base, extensibles) pour « Nouveau projet » | `src/lib/projects.js` | moyenne | S | 2-3 | idée |
| 21 | **Port d'aperçu « serveur de dev »** : ouvrir directement `localhost:PORT` d'un serveur lancé dans le Linux | `run.js` l.49-60 | basse | S | 3 | Forge l'a déjà (« Serveur local ») |
| 22 | **Plugins (plus tard)** : registre de modules qui expose **les instances CM6 de l'hôte** (évite deux `@codemirror/state`), démontage par le cadre avant mise à jour, chemins de zip assainis, secrets par jeton | `acode.js` l.385-459 ; `loadPlugin.js` l.10-35 ; `installPlugin.js` l.331-390 | basse | L | 4 | idée ; **ne pas** reprendre le modèle de privilèges (voir À éviter) |
| 23 | **Clés i18n plates + contrôle des clés non traduites** (le français d'Acode en a 60 %) | `src/lang/` ; `utils/lang.js` | basse | S | 2 | idée |
| 24 | Rendu canvas WebGL2 de CM6 (à surveiller seulement) | `src/cm/createEditorView.ts` ; `@acode.app/canvas-view` | basse | L | 4 | MIT, 0.0.3 « expérimental » : pas d'adoption |

---

## 8. À éviter

- **Serveur d'aperçu ouvert au réseau** : `NanoHTTPD(port)` sans nom d'hôte (`NanoHTTPDWebserver.java` l.31) écoute
  sur toutes les interfaces, sans jeton : n'importe quel appareil du même Wi-Fi peut lire le projet. Forge
  (127.0.0.1 + jeton + port éphémère) est meilleur : garder notre conception.
- **Attente active** d'une réponse JS par requête (`Thread.sleep(1)` en boucle, l.257-264) et ports fixes 8158/8159
  avec `++port` : servir directement depuis Kotlin.
- **Injection d'eruda dans toute page externe** (`Browser.java` l.783-830) avec **repli CDN jsDelivr sans version ni
  intégrité**, et eruda téléchargé à l'exécution (`config.js` l.23) : code distant non épinglé dans la WebView.
- **Réécriture du HTML de l'utilisateur** (ajout de `crossorigin="anonymous"` à tous les scripts par regex,
  `run.js` l.454) : préférer les rappels natifs de la WebView (`onConsoleMessage`, erreurs de ressources) à
  l'injection ; si injection, uniquement sur l'origine de l'aperçu et retirée ensuite.
- **Plugins avec tous les droits** : script dans le document principal, accès fs/terminal/réglages/WebView ; les
  « permissions » ne couvrent que le coffre. Pour Forge (étape 4) : extensions déclaratives (thèmes, extraits,
  modèles, langages) ou code isolé (iframe/Worker sans pont natif).
- **Embarquer PRoot + rootfs Alpine + pont `libaxs.so` par ABI** : Altrex a déjà son Linux ; inutile de doubler.
- Pubs AdMob, achats intégrés, compte acode.app, magasin de plugins payants : hors sujet.
- Pas de diff/fusion dans Acode : rien à prendre (voir Codex/Cline dans `coding-agents.md`).

---

## 9. Licences

| Composant | Licence | Réutilisation | Remarque |
|---|---|---|---|
| Acode (dépôt) | **MIT** © 2020 Foxdebug (Ajit Kumar) (`license.txt`) | permise avec la mention | règle Forge : idées seulement |
| CodeMirror 6 (`codemirror`, `@codemirror/*` 6.x), Lezer | MIT | permise | dépendance déjà envisagée par Forge ; à prendre **à la source** |
| `@codemirror/lsp-client` | MIT en amont (sous-module non lu ici) | permise | fork Acode-Foundation non vérifié |
| `@acode.app/canvas-view` 0.0.3 | MIT (`package-lock.json`) | permise | expérimental |
| `@emmetio/codemirror6-plugin` 0.4.1 | **non déclarée** dans le lock | à vérifier | Emmet est MIT en amont |
| xterm.js 6 + addons | MIT | permise | — |
| vscode-html/css/json-languageservice | MIT | permise | — |
| TypeScript | Apache-2.0 | permise (NOTICE) | lourd |
| Prettier (+ plugins) | MIT ; `prettier-plugin-java` Apache-2.0 | permise | — |
| markdown-it (+ plugins), mustache, KaTeX, acorn | MIT | permise | — |
| DOMPurify | MPL-2.0 OU Apache-2.0 | permise (choisir Apache-2.0) | — |
| JSZip | MIT OU GPL-3.0 | permise (choisir MIT) | — |
| esprima | BSD-2-Clause ; `filesize` BSD-3-Clause ; vanilla-picker ISC | permise | — |
| NanoHTTPD (`org.nanohttpd:nanohttpd:+`, version non épinglée) | BSD-3-Clause | permise | inutile : Forge a son serveur Kotlin |
| eruda | MIT | permise | téléchargé à l'exécution, non épinglé |
| androidx.webkit (profils), androidx.documentfile | Apache-2.0 | permise | — |
| Cordova (`cordova-android`, `cordova-plugin-file`) | Apache-2.0 | sans objet | Forge est en Kotlin/Compose |
| commons-net (FTP), commons-io | Apache-2.0 | permise | — |
| maverick-synergy-client 3.1.4 (SFTP) | LGPL-3.0 (édition libre ; double licence commerciale) | possible en lib dynamique, contraintes LGPL | préférer une autre lib si besoin |
| BouncyCastle 1.83 | licence type MIT | permise | — |
| Java-WebSocket 1.6 | MIT | permise | — |
| Plugins `ftp`/`sftp` (`LICENSE.md`) | Apache-2.0 pour le code de l'auteur ; **ftp4j LGPL-2.1** ; GoldRaccoon (licence de l'auteur) | prudence | ne rien prendre |
| PRoot (`libproot*.so`) / talloc | **GPL-2.0** / LGPL-3.0 (licences amont ; aucun fichier de licence dans `src/plugins/proot/`) | binaires seuls : obligations GPL (source) | Altrex a déjà son propre PRoot : vérifier ses mentions de notre côté |
| `libaxs.so` (« acodex_server », téléchargé depuis les releases GitHub de bajrangCoder, `Terminal.js` l.225-300) | **non vérifiée** | ne pas utiliser | binaire opaque |
| Rootfs Alpine | paquets sous leurs licences (busybox GPL-2.0, musl MIT…) | sans objet | — |
