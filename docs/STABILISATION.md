# Altrex : audit avant stabilisation

Audit en lecture seule du 4 novembre 2026 (branche `ccr-bd2eca9a-mw717h`, versionCode 41 / versionName 1.0.19).
Rien n'a été modifié dans le code. Chaque constat donne le fichier et la ligne pour qu'un développeur puisse le vérifier.
Les chemins `…/gallery/` désignent `app/src/main/java/com/google/ai/edge/gallery/`.

---

## Résumé en une page

**L'état général est bon.** Les ajouts récents sont soignés : clés chiffrées avec le Keystore, OAuth avec PKCE, notes écrites de façon atomique, sauvegardes Android coupées et 26 suites de tests qui passent toutes. Le travail restant consiste surtout à **enlever ce qui reste de l'application Google d'origine** et à **corriger une dizaine de points précis**.

**Les 7 constats les plus importants**

| # | Constat | Gravité | Effort |
|---|---|---|---|
| 1 | **Écran vide pour toujours** au premier lancement si la liste des modèles (téléchargée depuis le dépôt GitHub *de Google*) ne se charge pas : pas de réseau, ou version d'Altrex absente chez Google (par ex. `1_1_0.json` répond 404 aujourd'hui). Le message d'erreur n'existe que sur un écran qui n'est jamais affiché. | Élevée | S–M |
| 2 | **Une page web de skill peut lire les fichiers privés d'Altrex** : historique des discussions, index de la mémoire locale, fichiers des deux Linux. Le serveur interne de la WebView expose tout le dossier `files/`. On peut aussi y arriver avec un nom de script contenant `../`, que le modèle choisit lui-même. | Élevée | S |
| 3 | **Deux commandes contournent l'accord** dans le mode par défaut du Linux du modèle (« lectures libres ») : `rg --pre <programme>` et `sort --compress-program=<programme>` exécutent n'importe quel programme sans rien demander. | Moyenne | S |
| 4 | **Firebase (Analytics et Messaging) est toujours embarqué** alors qu'il est inactif (aucune configuration). Il ajoute au manifeste les permissions publicitaires `AD_ID` et `ACCESS_ADSERVICES_*`. TFLite et MediaPipe sont aussi embarqués sans être utilisés (≈ 15 Mo de bibliothèques natives). TFLite ajoute en plus `READ_PHONE_STATE` et `WRITE_EXTERNAL_STORAGE`. | Moyenne (vie privée et taille) | M |
| 5 | **Le texte « Confidentialité » et le sous-titre du menu (« IA locale · hors ligne ») sont faux** depuis l'arrivée des modèles cloud, des Spaces et des connecteurs : avec un modèle cloud, toute la conversation part chez le fournisseur. | Moyenne (confiance) | S |
| 6 | **L'APK fait 167 Mo**, dont 115 Mo de code non optimisé : c'est un build *debug* sans R8. Aucun build *release* n'existe (ni signature, ni règles R8). | Moyenne | M–L |
| 7 | **Mettre à jour la version d'Alpine effacera le Linux de l'utilisateur** (ses paquets et son dossier `/root`), parce que le marqueur d'installation porte le numéro de version. | Moyenne (perte de données future) | S |

**Menus.** Il y a 5 espaces, 2 outils hérités de Google (Ask Image, Audio Scribe), plus 2 tâches encore enregistrées mais retirées du menu (Agent Skills, Sécurité) et 3 écrans qui ne sont plus atteignables (accueil Google, test llama.cpp, notifications). Je propose un menu à **4 espaces + 3 boutons en bas** (section 1.4). La suppression d'Ask Image et d'Audio Scribe est une **décision de l'utilisateur**.

**Plan.** La section 8 donne la liste ordonnée. Les priorités de **cette semaine**, toutes de petite taille et sans risque fonctionnel, sont les constats 1, 2, 3, 5, 7, la gestion d'erreur des Spaces et l'arrêt des journaux qui contiennent des saisies.

**Ce que je n'ai pas pu vérifier** : les avertissements du compilateur Kotlin (Gradle interdit pendant l'audit), le comportement réel sur le téléphone et le gain exact de R8.

---

## 1. Menus, écrans et modes

### 1.1 Ce que l'utilisateur peut atteindre aujourd'hui

**Menu latéral** (`…/ui/home/AltrexDrawer.kt`)

| Entrée | Section | Ce que c'est | Fichier |
|---|---|---|---|
| Discuter | Espaces | Discussion principale. Elle utilise l'écran « agent » (skills, MCP, actions du téléphone, recherche web, Linux du modèle) avec des outils optionnels. Pièces jointes possibles : photo, caméra, audio, fichier, PDF (`MessageInputText.kt:1007-1078`). | `ui/llmchat/LlmChatTaskModule.kt:144-155` → `customtasks/agentchat/AgentChatScreen.kt` |
| Code | Espaces | Ancien « Prompt Lab » de Google, transformé en éditeur de projet avec agent, aperçu, recherche web et scan SecurCode. **Il a sa propre boucle d'agent** (`ui/llmsingleturn/AgentRunner.kt`, `ToolCalling.kt`), distincte de celle de Discuter. | `ui/llmsingleturn/` |
| Terminal | Espaces | Alpine Linux (PRoot) de l'utilisateur. | `terminal/TerminalScreen.kt` |
| Connecteurs | Espaces | Serveurs MCP distants (OAuth) et locaux. | `connectors/ConnectorsScreen.kt` |
| Notes vocales | Espaces | Dictée, liste, graphe, export Obsidian. | `notes/` |
| Ask Image | Outils | Écran Google d'origine (question sur une image). | `ui/llmchat/LlmChatTaskModule.kt:233-300` |
| Audio Scribe | Outils | Écran Google d'origine (transcription). | `ui/llmchat/LlmChatTaskModule.kt:327-` |
| Carte « Modèle chargé » / bouton Modèles | Bas | Gestionnaire de modèles (+ benchmark pour les modèles LiteRT, `ui/common/modelitem/DownloadModelPanel.kt:85`). | `ui/modelmanager/GlobalModelManager.kt` |
| Réglages | Bas | Fenêtre plein écran (pas une vraie page de navigation). | `ui/home/SettingsDialog.kt` |
| « Altrex comme assistant » | Bas | Guide pour devenir l'assistant du téléphone. La carte disparaît une fois Altrex configuré. | `ui/home/AssistantGuide.kt` |

**Hors menu** : le panneau « Demander à Altrex » (sélection de texte, partage, bouton assistant), dans `AskAltrexActivity.kt`. Dans la discussion, le menu « ⋮ » propose « Exporter en PDF » et « Exporter le journal llama.cpp » (`ui/common/ModelPageAppBar.kt:204-214`). Il existe aussi un interrupteur « Mode autonome » (`AgentChatScreen.kt:738`).

### 1.2 Doublons, écrans morts et confusions

| Constat | Preuve | Classement |
|---|---|---|
| **Ask Image et Audio Scribe font doublon avec Discuter**, qui accepte déjà photos, caméra et audio. Ils utilisent en plus d'anciens écrans (`LlmChatScreen`, `LlmChatViewModel`, ≈ 1 600 lignes). | `MessageInputText.kt:1007-1078` ; `ui/llmchat/` | Décision de l'utilisateur |
| **Tâche « Agent Skills » toujours enregistrée** (Hilt `@IntoSet`), alors que Discuter l'a absorbée. On l'atteint encore par le lien `com.google.ai.edge.gallery://llm_agent_chat` et par la grille de l'accueil Google. | `customtasks/agentchat/AgentChatTaskModule.kt:120-135, 211-220` ; `AltrexDrawer.kt:173` | Retirer après décision (les skills ou MCP de l'utilisateur ne sont pas touchés : le `AgentTools` partagé reste) |
| **Tâche « Sécurité » toujours enregistrée**, alors que le commentaire du menu dit qu'elle est fusionnée dans Code. | `ui/security/SecurityTaskModule.kt:25-80` ; `AltrexDrawer.kt:86` | Retirer après décision |
| **Code et Discuter ont deux agents différents** avec des outils de même nom : `read_file` et `write_file` agissent sur le dossier de projet dans Code, et sur le Linux du modèle dans Discuter. C'est déroutant pour l'utilisateur comme pour le modèle. | `ui/llmsingleturn/AgentRunner.kt` vs `tools/RunCommandTool.kt` | Plus tard, décision de l'utilisateur |
| **L'accueil Google (HomeScreen, ≈ 1 070 lignes)** ne s'affiche que si la discussion n'a aucun modèle. En pratique on ne le voit jamais, sauf dans le cas d'erreur du constat 1, où il n'est justement **pas** affiché. | `ui/navigation/GalleryNavGraph.kt:330-352` | À remplacer par un écran d'accueil ou d'erreur simple |
| **Écran « test llama.cpp » et écran « Notifications » inatteignables** : `HomeScreen` reçoit `onLlamaCppClicked` et `onNotificationsClicked` mais ne les utilise nulle part. | `ui/home/HomeScreen.kt:168,172` (seules occurrences) ; routes `GalleryNavGraph.kt:631-672` | Supprimer sans risque |
| Promo « Gemma 4 » (`PromoScreenGm4`, texte d'introduction Gemma, liens ai.google.dev). | `GalleryNavGraph.kt:356-416` ; `HomeScreen.kt:525-650` | Supprimer avec HomeScreen |
| « Linux (test) » dans les Réglages : c'est une fenêtre de test pour développeur. | `SettingsDialog.kt:355-366` | À masquer |

### 1.3 Textes en anglais, en français ou incohérents

- **Noms des tâches** : ils sont en anglais dans `values/strings.xml:701-705` (« AI Chat », « Code Mode », « Ask Image »…) et en français seulement dans `values-fr` (« Discuter », « Code »). Un téléphone qui n'est pas réglé en français affiche donc « AI Chat » dans la barre du haut et « Discuter » dans le menu (le menu est écrit en dur en français). De plus, `task_label_ask_image`, `task_label_audio_scribe` et `task_label_agent_skills` n'ont pas de traduction française.
- **Anglais visible par l'utilisateur** : le menu affiche « Ask Image » et « Audio Scribe » (`AltrexDrawer.kt:124,131`). Les étiquettes de progression « Loaded / Called / Executed » apparaissent dans `AgentChatScreen.kt:767-784`. La fenêtre « Enter secret » vient de `tools/RunJsTool.kt:93`.
- **Sous-titre du menu « IA locale · hors ligne »** (`AltrexDrawer.kt:274`) : il est faux avec un modèle cloud, un Space ou un connecteur.
- **Le README** annonce encore Firebase, TFLite, MediaPipe, Prompt Lab, Agent Chat et `compileSdk 36` (le projet est en 37) : `README.md:12-36`. Le fichier `metadata.json` décrit encore la « showcase » de Google.

### 1.4 Menu proposé (plus simple)

```
ALTREX                          (modèle chargé ●)
─ Discuter      chat, pièces jointes (image, audio, PDF), outils
─ Code          projet, éditeur, aperçu, scan
─ Notes         dictée, graphe
─ Terminal      Linux
──────────────
[Modèles]  [Connecteurs]  [Réglages]
```

- Les connecteurs passent en bas : c'est une configuration, pas un espace de travail.
- La section « Outils » disparaît **si l'utilisateur accepte** : Ask Image et Audio Scribe sont déjà couverts par Discuter.
- **Réglages regroupés** en 6 blocs, au lieu de 9 sections aujourd'hui (`SettingsDialog.kt:208-400`) :
  1. Apparence ;
  2. Modèles et clés (jeton Hugging Face et clés cloud au même endroit) ;
  3. Services (Brave, SecurCode, GitHub) ;
  4. Mémoire locale ;
  5. Linux (les deux sections « Linux » et « Linux du modèle » fusionnées, sans « test ») ;
  6. Confidentialité et À propos.
- Faire des Réglages une vraie page de navigation, et non une fenêtre (`GalleryNavGraph.kt:677-684`), pour que le bouton retour se comporte comme ailleurs.

---

## 2. Poids mort et restes de Google

Légende : **SÛR** = suppression sans risque · **DÉCISION** = l'utilisateur doit choisir · **GARDER**.
J'ai vérifié chaque élément « SÛR » par recherche de références dans `app/src` (Kotlin, ressources, manifeste, tests). Les entrées de manifeste, les modules Hilt et les routes de navigation n'ont pas été comptés comme morts.

### 2.1 Code jamais appelé

| Élément | Preuve | Classement | Effort / risque |
|---|---|---|---|
| `SquareDrawerItem` (`ui/home/SquareDrawerItem.kt`) | seulement importé dans `GalleryNavGraph.kt:118`, jamais appelé | SÛR | S / nul |
| `LiveCameraView` (219 l.), `GlitteringShapesLoader` (160 l.), `EmptyState` (97 l.), `DeleteModelButton` (81 l.), `MessageBodyImageWithHistory` (91 l.), `SteadinessMonitor` (76 l.), `PromoBannerGm4` (123 l.), `NewReleaseNotification` (155 l.), `LlmChatModeToggle`, `AGWorkInfo`, `LatencyProvider`, `SkillsIndex`, `CLASSIFICATION_BAR_MAX_WIDTH` | une seule occurrence chacun : leur propre déclaration | SÛR | S / nul |
| `ui/common/textandvoiceinput/` (`TextAndVoiceInput`, `VoiceRecognizerOverlay`, `HoldToDictate*`, ≈ 600 l.) | `TextAndVoiceInput` n'est appelé nulle part ; le reste n'est utilisé que par lui | SÛR | S / faible (vérifier la compilation) |
| `LlamaCppTestScreen`, `NotificationsScreen` + leurs routes | inatteignables (section 1.2) | SÛR | S / nul |
| `HomeScreen` + `PromoScreenGm4` + route `homepage` | voir 1.2 et constat 1 | À remplacer par un écran simple | M / faible |
| « Cutouts » (Scrapbook / Tiny Garden de Google) : `CutoutsSerializer.kt`, DataStore `cutouts.pb` (`di/AppModule.kt:110`), méthodes `addCutout`… (`data/DataStoreRepository.kt:304-340`), `BuiltInTaskId.MP_SCRAPBOOK` (`data/Tasks.kt:141`, `ModelManagerViewModel.kt:185`) | aucun appel en dehors de leurs définitions | SÛR (garder les messages `.proto`) | S / nul |
| Tâches Agent Skills et Sécurité | section 1.2 | DÉCISION | S |
| Ask Image et Audio Scribe (`LlmChatScreen`, `LlmChatViewModel`…) | section 1.2 | DÉCISION | M |
| `app/src/main/bundle_config.pb.json` (« Bundle config for the Google AI Edge Gallery app ») | référencé nulle part | SÛR | S |

### 2.2 Ressources

- **102 chaînes sur 517** de `res/values/strings.xml` ne sont référencées nulle part (`R.string.x` ou `@string/x`). Exemples : `settings_dialog_firebase_analytics_*`, `prompt_lab_*`, `prompt_template_label_*`, `flashlight_*`, `prompt_chip_*`. Classement : SÛR. Lint (`UnusedResources`) en confirmera la liste exacte. Le gain en taille est négligeable ; l'intérêt est la lisibilité.
- **Drawables jamais référencés** : `analyze`, `chat_spark`, `icon`, `image_spark`, `lasso`, `live`, `text_spark`, `twirl`. Classement : SÛR, gain négligeable (348 Ko pour tout le dossier).

### 2.3 Dépendances (`app/build.gradle.kts`, `gradle/libs.versions.toml`)

| Dépendance | Constat | Classement |
|---|---|---|
| `firebase-analytics`, `firebase-messaging` (+ BoM, plugin `google-services`) | Aucun `google-services.json`, plugin `apply false` : Firebase ne démarre jamais (`Analytics.kt:26-36` attrape l'échec). Il reste pourtant 15 fichiers qui appellent `firebaseAnalytics?.logEvent`, plus le service `GalleryFcmMessagingService` et `FirebaseApp.initializeApp` au démarrage (`GalleryApplication.kt:50-51`). Le manifeste fusionné reçoit `AD_ID`, `ACCESS_ADSERVICES_AD_ID`, `ACCESS_ADSERVICES_ATTRIBUTION` et `BIND_GET_INSTALL_REFERRER_SERVICE` (rapport `app/build/outputs/logs/manifest-merger-debug-report.txt:1284-1375`). | SÛR. Effort M : retirer les appels dans ≈ 15 fichiers, le service FCM, les entrées du manifeste (`AndroidManifest.xml:186-206, 220-222`) et l'option cachée `disable_firebase_analytics`. Risque faible. |
| `play-services-tflite-java`, `-gpu`, `-support` | Aucun import `org.tensorflow` ni `com.google.android.gms.tflite` dans le code. **Elles font ajouter `READ_PHONE_STATE` et `WRITE_EXTERNAL_STORAGE`** au manifeste (rapport de fusion :1598-1601, « targetSdkVersion < 4 ») et embarquent `libtensorflowlite_jni_gms_client.so` (0,5 Mo). | SÛR. Effort S. À vérifier : que LiteRT-LM ne les demande pas lui-même (sa dépendance resterait alors transitive, sans dommage). |
| `tasks-vision` (MediaPipe) | Aucun import `com.google.mediapipe` dans le code. Embarque `libmediapipe_tasks_jni.so` : **14,4 Mo**. | SÛR. Effort S. |
| `kotlin-reflect` (déclaré directement) | Aucun import direct. LiteRT-LM découvre les outils `@Tool` par réflexion (`tools/ToolDefinition.kt`), donc la dépendance peut être nécessaire. | GARDER, à vérifier avec R8 |
| `pdfbox-android` | Utilisé (`DocumentImport.kt`). Il amène BouncyCastle, dont 8 Mo de fichiers `.properties` de cryptographie post-quantique (`org/bouncycastle/pqc/crypto/sike|picnic/*.properties`) inutiles pour lire des PDF. | GARDER. Exclure `org/bouncycastle/pqc/**` dans `packaging.resources.excludes` (S, risque faible : tester l'ouverture d'un PDF chiffré). |
| `androidx.security:security-crypto` (EncryptedSharedPreferences, **obsolète**) | Utilisé par GitHub, Brave et SecurCode (`ui/github/GitHubSettings.kt:21-27` et 5 autres sites), alors que tout le reste utilise `security/SecretCipher.kt`. | Unifier sur `SecretCipher` (M, avec migration des valeurs) |
| `play-services-oss-licenses` | Utilisé (page des licences). | GARDER |
| `mlkit-genai-prompt` (AICore) | Utilisé (`runtime/aicore/AICoreModelHelper.kt`), utile seulement sur les téléphones Pixel compatibles. | DÉCISION |
| CameraX (4 artefacts) | Utilisé par la caméra de la discussion (`MessageInputText.kt`). | GARDER |
| Moshi + Gson + kotlinx.serialization + org.json | **Quatre bibliothèques JSON** pour le même travail. | Plus tard (pas de bénéfice immédiat) |

### 2.4 Permissions (`AndroidManifest.xml`)

| Permission | Utilisée ? | Classement |
|---|---|---|
| `READ_MEDIA_IMAGES` (l.22), `READ_EXTERNAL_STORAGE` (l.24), `ACCESS_MEDIA_LOCATION` (l.23) | jamais demandées dans le code (le sélecteur de photos n'en a pas besoin) | SÛR |
| `GET_ACCOUNTS` (l.45), `c2dm.permission.RECEIVE` (l.41), `READ_GSERVICES` (l.43) | uniquement pour FCM (Firebase) | SÛR, avec Firebase |
| `AD_ID`, `ACCESS_ADSERVICES_*`, install referrer (ajoutées par Firebase) | voir 2.3 | SÛR, avec Firebase |
| `READ_PHONE_STATE`, `WRITE_EXTERNAL_STORAGE` (ajoutées par TFLite) | voir 2.3 | SÛR, avec TFLite |
| `READ_CALENDAR`, `SET_ALARM` | skills d'actions du téléphone (`intents/IntentHandler.kt`) | GARDER |
| `CAMERA`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_DATA_SYNC`, `RECEIVE_BOOT_COMPLETED` | utilisées | GARDER |
| `com.google.android.apps.aicore.service.BIND_SERVICE` | AICore | DÉCISION (avec ML Kit) |

### 2.5 Autres restes de Google

- **La liste des modèles vient du dépôt de Google** : `https://raw.githubusercontent.com/google-ai-edge/gallery/.../model_allowlists/<version>.json` (`ui/modelmanager/ModelManagerViewModel.kt:98-99, 1686`). Le nom du fichier dépend de la version *d'Altrex*. Google décide donc des modèles proposés, et une version d'Altrex absente chez Google casse le premier lancement (constat 1). → À remplacer par une liste Altrex (fichier embarqué dans l'APK + mise à jour depuis votre propre dépôt).
- **OAuth Hugging Face non configuré** : `common/ProjectConfig.kt:25,32` contient `REPLACE_WITH_YOUR_CLIENT_ID_IN_HUGGINGFACE_APP`. Le bouton de connexion Hugging Face du téléchargement (`ui/common/DownloadAndTryButton.kt:248`) ne peut pas fonctionner ; le jeton collé à la main dans les Réglages, lui, fonctionne. → Masquer ce chemin ou le remplacer par « Coller un jeton ».
- **Liens vers le projet Google** : `docUrl` et `sourceCodeUrl` de chaque tâche (`LlmChatTaskModule.kt:99-101, 240-242, 334-336`, `LlmSingleTurnTaskModule.kt:52-54`, `AgentChatTaskModule.kt:130-132`), le dépôt de skills et les discussions (`data/Consts.kt:91-92`), la doc MCP (`McpManagerBottomSheet.kt:213,433`), les liens Gemma (`HomeScreen.kt:548,565`, `PromoBannerGm4.kt:114`).
- **Schéma de lien profond `com.google.ai.edge.gallery://`** (`AndroidManifest.xml:94`, `GalleryNavGraph.kt:725-772`), plus l'extra `deeplink` des notifications FCM (`MainActivity.kt:75-85, 182-191`). Sans FCM, cet extra ne sert plus. → Retirer l'extra ; garder ou renommer le schéma (DÉCISION, aucun usage connu côté utilisateur).
- **`FLAG_KEEP_SCREEN_ON` en permanence** « pour les démos » (`MainActivity.kt:167-168`) : l'écran ne s'éteint jamais tant qu'Altrex est ouvert, ce qui vide la batterie. → Ne le garder que pendant une génération ou un téléchargement.
- **Journalisation de debug héritée** : tous les extras de l'intent au démarrage (`MainActivity.kt:68-72, 176-180`), l'historique des saisies (`ModelManagerViewModel.kt:1350`), la liste complète (`:1244`), le résultat brut d'un skill (`AgentChatScreen.kt:407`, `RunJsTool.kt:197`).

---

## 3. Taille et performances

### 3.1 Contenu de l'APK (`/home/user/altrex-apk/altrex-debug-notes.apk`, 167 Mo, 1 472 fichiers)

| Composant | Taille dans l'APK | Taille décompressée | Remarque |
|---|---|---|---|
| Code Java/Kotlin (24 fichiers `classes*.dex`) | **114,9 Mo** | 114,9 Mo | non compressé, **aucune optimisation R8** (build debug) |
| Bibliothèques natives `lib/arm64-v8a` | 37,0 Mo | 79,9 Mo | voir le détail ci-dessous |
| `org/bouncycastle/**.properties` | 4,0 Mo | 7,9 Mo | amené par pdfbox, inutile (2.3) |
| `resources.arsc` + `res/` | 2,4 Mo | 3,0 Mo | |
| `assets/com/tom_roush` (polices et cmaps pdfbox) | 1,7 Mo | 4,4 Mo | nécessaire au PDF |
| `assets/skills` | < 0,1 Mo | | |

**Bibliothèques natives (décompressées)** :
- `liblitertlm_jni.so` : 22,2 Mo ;
- **`libmediapipe_tasks_jni.so` : 14,4 Mo (inutilisée)** ;
- 6 variantes de llama.cpp `librnllama*.so` : 6 × 7,7 Mo = 46,4 Mo. La bonne variante est choisie au démarrage selon le processeur (`org/nehuatl/llamacpp/LlamaAndroid.kt:36-45`). Sur le S22 Ultra, seule `rnllama_v8_2_dotprod_i8mm` sert, mais garder les autres protège les téléphones plus anciens. → GARDER, sauf si vous visez un seul téléphone (DÉCISION, `cpp/CMakeLists.txt:146-154`) ;
- PRoot : 0,3 Mo.

**Ce qui bloque R8 et le build release** (`app/build.gradle.kts:80-87`) :
- le type `release` est vide : pas de `isMinifyEnabled`, pas de `isShrinkResources`, pas de signature ;
- **il n'existe aucun fichier `proguard-rules.pro`** ;
- pour activer R8, il faudra des règles de conservation pour :
  - les outils découverts par réflexion (méthodes `@Tool` de LiteRT-LM, 10 dans `tools/`) ;
  - les classes lues par Gson (`data/ModelAllowlist.kt` et autres) ;
  - les méthodes JNI `org.nehuatl.llamacpp` ;
  - la vue Termux.
- **Gain attendu** (estimation, à mesurer) : le code passe d'environ 115 Mo à quelques dizaines de Mo, et l'application démarre plus vite.

**Filtres d'ABI** : `arm64-v8a` seulement (`build.gradle.kts:52-58`). C'est correct, PRoot n'existe qu'en arm64.

**Déjà téléchargé à la demande (correct)** : Alpine (≈ 4 Mo), le modèle d'embedding de 278 Mo (`search/Embedder.kt:35`), les modèles. Il n'y a rien d'autre d'évident à rendre téléchargeable.

### 3.2 Démarrage

- `GalleryApplication.onCreate` (`GalleryApplication.kt:33-52`) lit `settings.pb` deux fois **sur le fil principal** (`readTheme`, `readFirebaseAnalytics`), avec `runBlocking` (`data/DataStoreRepository.kt:161-163`). La lecture de `hasViewedPromo` se fait aussi pendant l'affichage (`GalleryNavGraph.kt:384`). Il appelle aussi `FirebaseApp.initializeApp`, qui ne sert à rien.
- `DataStoreRepository` utilise `runBlocking` dans **toutes** ses méthodes (≈ 30 sites, `:141-389`). C'est un modèle hérité de Google. `readSecret` lit `user_data.pb`, qui contient **tout l'historique des discussions** (3.3), donc un fichier qui grossit avec le temps, parfois depuis le fil principal. → Plus tard (M) : passer en `suspend`/`Flow` au moins pour `user_data.pb`.
- `MainActivity` attend 1 s ou la fin du splash avant d'afficher, puis l'animation d'introduction se joue (`MainActivity.kt:107-140`). C'est voulu.
- `LocalMemory.warmUp` relit son index en arrière-plan (`search/LocalMemory.kt:179-188`). Correct.

### 3.3 Mémoire et ressources

| Risque | Preuve | Gravité |
|---|---|---|
| **Historique des discussions sans limite, stocké dans le même fichier que les secrets** (`user_data.pb`). Chaque sauvegarde réécrit tout le fichier ; tout est chargé en mémoire. | `ui/common/chat/ChatViewModel.kt:540-545` ; `proto/chat_history.proto:66-79` | Moyenne (croît avec l'usage) |
| **Images de l'historique enregistrées dans `cacheDir`** : Android peut vider ce dossier, et les images de vieilles discussions disparaissent. | `ChatViewModel.kt:485-486` (audio : `:512`) | Moyenne (perte de données) |
| Seuls les modèles llama.cpp sont libérés avant d'en charger un autre ; un modèle LiteRT (par ex. dans Ask Image) peut rester chargé à côté. | `ModelManagerViewModel.kt:469-481` | Faible à moyenne |
| Le modèle d'embedding (llama.cpp, `n_ctx` 512, mmap) reste chargé tant que la recherche par le sens est active. Rien ne réagit à `onTrimMemory`. | `search/Embedder.kt:122-167` ; aucun `onTrimMemory` dans le code | Faible |
| Les tâches de fond du modèle (`AgentJobs`) ne sont **jamais retirées** de la table une fois finies (jusqu'à 100 000 caractères de sortie chacune, durée de vie = processus). | `tools/AgentJobs.kt:25, 42` (aucun `remove`) | Faible |
| Un exécuteur caméra est créé à chaque affichage et jamais fermé. `cancelAll` crée aussi un fil à chaque appel. | `MessageInputText.kt:1126` ; `data/DownloadRepository.kt:163` | Faible |
| Les portées `CoroutineScope` globales jamais annulées (`SkillManager.kt:86`, `RemoteSpace.kt:273`, `LocalMemory.kt:70`, `NotesRepository.kt:36`, `NotificationScheduleManager.kt:57`) sont des singletons à durée de vie du processus. C'est acceptable. Seules `McpManagerViewModel.kt:175, 577` créent une portée par appel (fermeture de connexion), ce qui reste volontaire et borné. | — | Pas de problème |

---

## 4. Robustesse (classée par probabilité × impact)

| # | Problème | Où | Probabilité × impact |
|---|---|---|---|
| R1 | **Écran vide définitif** si la liste des modèles ne se charge pas : premier lancement hors ligne, ou fichier `<version>.json` absent chez Google (aujourd'hui `1_1_0.json` répond **404**, `1_0_19`…`1_0_21` répondent 200). La liste des tâches reste vide (`ModelManagerViewModel.kt:1096-1098` sort avant de la remplir) et la route de départ attend une liste non vide sans fin (`GalleryNavGraph.kt:330-352`). La fenêtre d'erreur avec « Réessayer » se trouve dans `HomeScreen.kt:457-480`, qui n'est jamais affiché. Le menu ne s'ouvre pas non plus par glissement (`gesturesEnabled = drawerState.isOpen`, `:321`). | ModelManagerViewModel, GalleryNavGraph | **Élevée × bloquant** |
| R2 | **Requêtes vers un Space HF : même faille que celle déjà corrigée pour le cloud.** Le bloc `catch` n'attrape que `Exception` (pas `OutOfMemoryError`, etc.), et `onError` n'est pas protégé par `runCatching` ; la requête cloud voisine l'est (`:503-518`). Une erreur sur ce fil sans gestionnaire ferme l'application. | `runtime/RemoteSpace.kt:397-406` | Moyenne × plantage |
| R3 | `LinuxEnv.exec` : le fil lecteur n'attrape que `IOException`. Si le rappel `onOutput` lève autre chose, l'application plante. Aujourd'hui les rappels passés (`RunCommandTool.kt:150-161`, `LinuxTestDialog.kt:66`) sont sûrs : c'est un risque futur. | `terminal/LinuxEnv.kt:260-275` | Faible × plantage |
| R4 | `RemoteModelHelper.appContext` est `lateinit`. Une requête lancée avant `initialize` (par ex. après une restauration du processus) lève une erreur, mais dans un fil applicatif, donc rattrapée par le message d'erreur. | `RemoteSpace.kt:278` | Faible |
| R5 | **Connexion OAuth perdue si Android tue Altrex pendant que le navigateur est ouvert** (fréquent avec un gros modèle chargé). L'état `pending` est en `remember`, et `MainActivity` jette volontairement l'état sauvegardé (`onCreate(null)`, `MainActivity.kt:60-63`). L'utilisateur revient sur la discussion, sans message. | `connectors/ConnectorsScreen.kt:101-105` | Moyenne × gênant |
| R6 | Écritures DataStore lancées sans `try` : si le disque est plein, l'application plante en arrière-plan. | `skills/SkillManager.kt:432, 553, 582` ; `NotificationScheduleManager.kt:85-90` | Faible × plantage |
| R7 | `LocalMemory.reindex` vérifie puis crée sa tâche sans verrou (`job` non `@Volatile`), depuis le fil principal et depuis `warmUp`. Deux indexations peuvent se chevaucher. | `search/LocalMemory.kt:191-199` | Faible |
| R8 | **Gestionnaire global de plantage** : il enregistre la trace (masquée par `SecretRedactor`) puis la montre au lancement suivant. Il ignore seulement les `CompletionHandlerException` du fil minuterie des coroutines (correctif MCP déjà fait). C'est bien conçu. | `ui/home/CrashLog.kt:17-49` | — |
| R9 | **Permissions** : la notification de téléchargement est bien demandée (`ui/common/Utils.kt:219-236`). En revanche, les rappels planifiés par le skill « schedule-notification » ne demandent jamais `POST_NOTIFICATIONS` : seul `HomeScreen.kt:273-276`, inatteignable, le fait. Sur Android 13+, un rappel peut donc ne jamais s'afficher, sans message. | `intents/IntentHandler.kt:455-524` | Moyenne × fonctionnalité silencieuse |
| R10 | **Messages d'erreur réseau** : les erreurs cloud, Space, MCP, Alpine et OAuth donnent des messages en français, compréhensibles (« Space injoignable… Vérifie ta connexion », « Serveur injoignable (délai dépassé) »). Pas de constat. | — | — |

Sécurité des fils : `AgentJobs` est entièrement `@Synchronized` ; `NoteStore` aussi ; `SecretCipher.engine` est `@Volatile`. Pas d'autre état partagé mutable repéré.

---

## 5. Sécurité et vie privée des ajouts récents

| # | Sujet | Constat | Gravité |
|---|---|---|---|
| S1 | **WebView des skills** | `BaseGalleryWebViewClient` sert **tout `filesDir`** sous l'origine `https://appassets.androidplatform.net/` (`ui/common/GalleryWebView.kt:69`). Le JavaScript d'un skill importé (code tiers) tourne dans cette origine (`skills/SkillExtensions.kt:40-41`). Il peut donc lire par `fetch` : `/datastore/user_data.pb` (historique des discussions en clair), `/memory/index.jsonl` (texte du dossier indexé), `/linux/user/rootfs/root/...` (par ex. jetons `gh` ou `git` du terminal) et `/linux/agent/rootfs/...`, puis les envoyer sur Internet. **Variante** : `run_js` est autorisé sans demande (`tools/RunJsTool.kt:46`) et construit l'URL `…/scripts/$scriptName` avec un `scriptName` choisi par le modèle, sans contrôle (`SkillExtensions.kt:48`). Avec `../`, une injection de prompt peut faire charger une page HTML écrite par le modèle dans son Linux. C'est une chaîne plausible, que je n'ai pas testée sur appareil. **Correctif** : limiter le gestionnaire au dossier des skills importés, refuser `..` et `/` dans `scriptName`. | **Élevée** |
| S2 | **Accord des commandes du modèle** | Le mode par défaut « lectures libres » considère `rg` et `sort` comme de simples lectures (`tools/AgentShell.kt:143-149, 183`). Or `rg --pre=sh motif fichier` et `sort --compress-program=sh` **exécutent un programme sans accord**. Autre point : le programme est identifié par la fin de son chemin (`substringAfterLast('/')`, `:172, 177, 198`). Si l'utilisateur a coché « Modifier ses fichiers sans demander », le modèle peut écrire `/root/ls` puis lancer `/root/ls` comme une « lecture », ou modifier `/root/.altrex/env` (rechargé à chaque commande, `:23-26`) pour détourner `PATH`. La description de l'outil dit en plus « The user sees and approves every command » (`RunCommandTool.kt:37`), ce qui est faux dans ce mode. Les tests (`test/.../tools/AgentShellTest.kt`) ne couvrent pas ces cas. | **Moyenne** |
| S3 | **Isolation du Linux (PRoot)** | PRoot traduit les chemins mais **n'est pas une frontière de sécurité** : les processus tournent sous l'UID d'Altrex, avec `/proc`, `/sys` et `/dev` montés (`terminal/LinuxEnv.kt:300-306`), et **un accès réseau sans restriction**. La séparation USER / AGENT et le dossier partagé désactivé par défaut pour le modèle (`:131-139, 323-332`) sont de bonnes mesures. Il faut juste éviter de présenter ce Linux comme un « bac à sable ». Le téléchargement d'Alpine est vérifié par SHA-256 et l'extraction refuse les chemins hors du dossier (`:176-183, 436-449`) : pas de constat. | Information |
| S4 | **Clés d'API (cloud)** | Elles sont chiffrées en AES-GCM avec une clé du Keystore (`security/SecretCipher.kt`) dans `shared_prefs/altrex_cloud_keys.xml` (`runtime/CloudApi.kt:91-106`). Si le Keystore échoue, la clé est **gardée en clair** (`SecretCipher.kt:40-45`), avec seulement un journal. Les clés sont envoyées seulement dans l'en-tête du fournisseur (`CloudApi.kt:346-349`). | Faible |
| S5 | **Jeton HF, secrets des skills, en-têtes MCP, jetons OAuth MCP** | Tous chiffrés avec `SecretCipher` (`DataStoreRepository.kt:193-260`, `connectors/McpOAuth.kt:204, 344, 358`). L'OAuth MCP impose https, PKCE S256, le paramètre `resource` et des hôtes publics (`McpOAuth.kt:34-96, 366-370`). Pas de constat. | — |
| S6 | **GitHub, Brave, SecurCode** | Deuxième mécanisme de chiffrement (EncryptedSharedPreferences, **obsolète**). | Faible (dette) |
| S7 | **Notes vocales** | Stockage privé, écriture atomique, aucun contenu journalisé ; la reconnaissance sur l'appareil passe en premier et le service système n'est utilisé que sur choix explicite (`notes/VoiceRecorder.kt:71-82`). L'export va dans `Android/media/…/Linux/Notes`, visible des gestionnaires de fichiers et **du Linux du modèle si le partage est activé**. Il faut le dire à l'utilisateur. | Information |
| S8 | **Journaux (logcat)** | On y trouve : l'historique des saisies (`ModelManagerViewModel.kt:1350`), tous les extras d'intent (`MainActivity.kt:70, 178`), le résultat brut d'un skill (`AgentChatScreen.kt:407`), le contenu chargé d'un skill (`LoadSkillTool.kt:41`) et les messages console des WebView (`GalleryWebView.kt:166-170`). Les autres applications ne lisent pas logcat, mais un rapport de bug ou `adb` oui. | Faible |
| S9 | **Analytics** | Firebase inactif (pas de configuration), donc rien n'est envoyé aujourd'hui. Le code et les permissions publicitaires restent (2.3). | Faible (à supprimer) |
| S10 | **Composants exportés** | `MainActivity` (lanceur + lien profond `com.google.ai.edge.gallery://<tâche>?query=` : le texte pré-remplit la saisie **sans l'envoyer**, `AgentChatScreen.kt:237-244`). `AskAltrexActivity` (ASSIST, PROCESS_TEXT, SEND : jamais d'envoi automatique, contenu encadré comme « non fiable »). Services d'assistant protégés par `BIND_VOICE_INTERACTION`. `AltrexRecognitionService` est exporté sans permission mais ne fait que répondre une erreur (`assistant/AltrexAssistService.kt:90-97`). `BootReceiver` vérifie l'action. Le récepteur OAuth d'AppAuth est exporté (normal). Seul point : l'extra `deeplink` de `MainActivity` permet à n'importe quelle application d'ouvrir une URL http(s) **via** Altrex (`MainActivity.kt:75-80`), ce qui ne sert à rien sans FCM. | Faible |
| S11 | **Sauvegardes** | `allowBackup="false"` et `data_extraction_rules.xml` excluent tout (cloud et transfert entre appareils). Pas de constat. | — |
| S12 | **APK debug distribué** | L'APK publié est `debuggable` : `adb run-as` lit toutes les données privées (discussions, notes) sur un téléphone avec débogage USB autorisé. La clé debug n'est pas dans le dépôt (`.gitignore`), et elle vient d'un secret CI : correct. | Faible à moyenne |
| S13 | **Texte « Confidentialité »** | Il ne mentionne ni les modèles cloud (conversation entière envoyée au fournisseur), ni les Spaces HF, ni les connecteurs MCP, ni le téléchargement de la liste des modèles depuis GitHub (Google) (`SettingsDialog.kt:371-380`). | Moyenne (information trompeuse) |

---

## 6. Mises à jour et versions

### 6.1 Aujourd'hui

- La version est fixée à la main dans `app/build.gradle.kts:41-42` (`versionCode = 41`, `versionName = "1.0.19"`).
- **CI** (`.github/workflows/build-debug-apk.yml`) : uniquement au déclenchement manuel et sur les pull requests (pas sur un push). Il lance les tests, `assembleDebug`, puis publie l'APK sous **un seul tag réécrit, `debug-latest`**. Il n'y a donc ni historique ni retour possible à une version précédente. La clé de signature debug, constante grâce au secret `ALTREX_DEBUG_KEYSTORE_B64`, permet d'installer par-dessus (bon point). `concurrency: build` + `cancel-in-progress` : une PR annule une publication en cours.
- L'utilisateur télécharge l'APK et l'installe à la main. Rien dans l'application ne signale une nouvelle version : le `NewReleaseNotification` de Google existe mais n'est pas utilisé.
- **Le build `release` n'est jamais construit** : ni signature ni R8.

### 6.2 Sécurité des données d'une version à l'autre

| Données | Format | Risque de migration |
|---|---|---|
| `settings.pb`, `user_data.pb`, `skills.pb`, `mcp_servers.pb`, `benchmark_results.pb`, `cutouts.pb`, `scheduled_notifications.pb` | Proto DataStore (`app/src/main/proto/*.proto`) | Faible **si** on garde les règles proto : ne jamais réutiliser un numéro de champ, utiliser `reserved` (déjà fait dans `settings.proto:63, 80`). Les anciens secrets en clair sont rechiffrés à la lecture (`DataStoreRepository.kt:203-205`). |
| Notes (`files/notes/*.json`) | kotlinx.serialization, `ignoreUnknownKeys`, valeurs par défaut | Faible. Les fichiers illisibles sont ignorés, pas supprimés (`notes/NoteStore.kt:40-90`). Il n'y a pas de numéro de version dans le format. |
| Mémoire (`files/memory/index.jsonl`, `vectors.bin`) | Format maison | Faible : un index illisible est simplement reconstruit (`LocalMemory.kt:336-350`). |
| **Linux** (`files/linux/<user|agent>/`) | Marqueur `.installed-<ALPINE_VERSION>` (`terminal/LinuxEnv.kt:160`) | **Élevé** : le jour où `ALPINE_VERSION` change, `isInstalled` devient faux et la réinstallation **efface le rootfs** (`:199`), donc tout ce que l'utilisateur a installé et son dossier `/root`. → Séparer « version installée » et « version proposée », ne jamais effacer `/root`, proposer `apk upgrade` à la place. |
| Préférences partagées (`altrex_*`, 12 fichiers) | Clés texte | Faible. Une ancienne clé (`always_allow`) est déjà migrée en douceur (`tools/AgentShell.kt:114-122`). |
| Liste des modèles | Fichier de Google selon `versionName` | **Élevé** (constat R1) |
| Images des discussions | `cacheDir` | Moyen (3.3) |

### 6.3 Stratégie minimale proposée

1. **Une source de vérité pour la version** : garder `versionCode` et `versionName` dans `build.gradle.kts`, mais **les incrémenter à chaque publication**, et publier aussi sous un tag `v1.0.20` (en plus de `debug-latest`) pour garder l'historique.
2. **Vérification de mise à jour dans l'application** (S–M) : au démarrage, au plus une fois par jour et seulement si le réseau est disponible, interroger `https://api.github.com/repos/<propriétaire>/Altrex-4/releases/latest`. Comparer le `versionCode` (mis dans le nom ou la description de la release) et afficher une bannière « Nouvelle version » qui ouvre la page de téléchargement. Pas d'installation automatique.
3. **Liste des modèles Altrex** : un `model_allowlist.json` embarqué dans `assets/` (utilisé si le réseau échoue), et une URL dans votre dépôt, **indépendante du numéro de version**.
4. **Règles à écrire dans un `docs/MISES_A_JOUR.md`** : ne jamais réutiliser un numéro de champ proto ; ne jamais effacer `files/linux/*/rootfs/root` ; toute nouvelle préférence a une valeur par défaut ; tester l'installation par-dessus la version précédente avant de publier.
5. **Plus tard** : un build `release` signé avec une vraie clé (à garder hors du dépôt, avec une copie de sécurité). Attention : **passer de la clé debug à une clé release oblige à désinstaller une fois** (perte des données). C'est une décision à prendre tôt, avec un export préalable (notes déjà exportables ; discussions et modèles à prévoir).

---

## 7. Tests et hygiène du build

- **État des tests** (`app/build/test-results/`, exécution du 4 novembre 16 h 20) : 26 suites, environ 150 tests, **0 échec**. Un test est sauté volontairement : `StdioMcpLiveTest`, qui a besoin de `MCP_STDIO_TEST=1` et de `npx`. `NotesRenderTest` prend 24 s (Robolectric).
- **Tests qui dépendent de l'environnement** : `PkgScriptTest` lance `/bin/sh` (fonctionne sous Linux et en CI, pas sous Windows) ; `StdioMcpLiveTest` est sauté par défaut. Aucun test instable observé.
- **Trous de couverture sur le code le plus risqué** :
  - chargement de la liste des modèles et écran d'erreur (R1) ;
  - `GalleryWebView` / `getJsSkillUrl` (S1) ;
  - cas `rg --pre`, `sort --compress-program` et chemins `/x/ls` dans `AgentShellTest` (S2) ;
  - `AgentJobs` ;
  - `RunCommandTool` (accords, fichiers) ;
  - `McpManagerViewModel` (connexion, délais) ;
  - liens profonds ;
  - migration du marqueur Linux ;
  - `RemoteModelHelper` (erreurs du Space).
- **Obsolescences repérées dans le code** : `Bundle.get(key)` (`MainActivity.kt:70, 178`) ; `EncryptedSharedPreferences`/`MasterKey` (6 sites) ; `externalMediaDirs` (marqué `@Suppress`, 4 sites).
- **Obsolescence Gradle** : `Configuration.setVisible`, 16 occurrences d'après `build/reports/problems/problems-report.html`. Elle vient du plugin Android (`com.android.internal.application`) et sera supprimée dans Gradle 11 : il faudra mettre à jour AGP, rien à corriger dans le code.
- **Imports inutiles** : nombreux, par exemple `GalleryNavGraph.kt:52-66` (`Shield`, `Flag`, `ModalDrawerSheet`, `SquareDrawerItem`…).
- **Petit bug hérité** : la marge de droite reprend la marge de gauche (`end = innerPadding.calculateStartPadding(...)`, `GalleryNavGraph.kt:887`).
- **Avertissements du compilateur Kotlin : non vérifiés.** Gradle n'a pas été lancé pendant l'audit et aucun journal de compilation n'était disponible. → Lancer une fois `./gradlew :app:compileDebugKotlin --warning-mode all` et `./gradlew lintDebug`, puis traiter d'abord les rapports `UnusedResources`, `ObsoleteSdkInt` et `MissingPermission`.
- **Hygiène du dépôt** : `protoc` est cherché dans `/data/data/com.termux/...` (`build.gradle.kts:162-168`), c'est un reste d'un build fait sur le téléphone. Il est sans effet ailleurs.

---

## 8. Plan de stabilisation priorisé

Effort : S = moins d'une demi-journée, M = 1 à 2 jours, L = plus. « Décision » signifie que l'utilisateur doit choisir d'abord.

### Cette semaine (forte valeur, faible risque)

| # | Quoi | Pourquoi | Fichiers | Effort | Risque | Décision |
|---|---|---|---|---|---|---|
| 1 | Liste des modèles : fichier de secours embarqué, et si tout échoue, écran d'erreur avec « Réessayer » et accès au menu au lieu d'un écran vide | R1 : blocage complet au premier lancement | `ModelManagerViewModel.kt:1054-1100`, `GalleryNavGraph.kt:330-352`, `assets/` | S–M | Faible | Non |
| 2 | Limiter le serveur WebView au dossier des skills ; refuser `..` et `/` dans `scriptName` | S1 : lecture possible des discussions et de la mémoire | `GalleryWebView.kt:66-87`, `SkillExtensions.kt:37-49`, `RunJsTool.kt` | S | Faible (tester les skills intégrés et importés) | Non |
| 3 | Retirer `rg` des lectures libres (ou refuser `--pre`), refuser `--compress-program` pour `sort`, exiger un nom de programme **sans chemin** pour les lectures et pour « Toujours » ; corriger la description de l'outil ; ajouter les tests | S2 : exécution sans accord | `tools/AgentShell.kt:143-200`, `RunCommandTool.kt:37`, `AgentShellTest.kt` | S | Faible | Non |
| 4 | Corriger le texte « Confidentialité » et le sous-titre « IA locale · hors ligne » | S13 : information fausse | `SettingsDialog.kt:371-380`, `AltrexDrawer.kt:274` | S | Nul | Non |
| 5 | Marqueur Linux : ne plus effacer le rootfs quand `ALPINE_VERSION` change | Éviter une perte de données future | `terminal/LinuxEnv.kt:160-202` | S | Faible | Non |
| 6 | Erreurs des Spaces : `catch (Throwable)` + `runCatching` autour de `onError`, comme pour le cloud | R2 : plantage | `runtime/RemoteSpace.kt:397-406` | S | Nul | Non |
| 7 | Supprimer les journaux qui contiennent des saisies ou des extras ; garder l'écran allumé seulement pendant une génération | S8 + batterie | `MainActivity.kt:66-85, 167-191`, `ModelManagerViewModel.kt:1244, 1350`, `AgentChatScreen.kt:407` | S | Nul | Non |

### Ensuite

| # | Quoi | Pourquoi | Fichiers | Effort | Risque | Décision |
|---|---|---|---|---|---|---|
| 8 | Retirer Firebase, TFLite, MediaPipe et les permissions inutiles (2.4) ; exclure `bouncycastle/pqc` | ≈ 15 Mo natifs + 8 Mo de fichiers en moins ; plus de permissions publicitaires | `build.gradle.kts`, `libs.versions.toml`, `AndroidManifest.xml`, `Analytics.kt`, `FcmMessagingService.kt`, ≈ 15 appelants | M | Faible (vérifier que tout compile et que les PDF s'ouvrent) | Non |
| 9 | Supprimer le code mort (2.1) : test llama.cpp, Notifications, SquareDrawerItem, Cutouts, textandvoiceinput, composants isolés, `bundle_config.pb.json`, chaînes et drawables inutilisés | Moins de code à maintenir | voir 2.1 et 2.2 | M | Faible | Non |
| 10 | Nouveau menu (1.4) et Réglages regroupés en vraie page ; noms de tâches cohérents dans `values/` ; textes anglais traduits | UI plus simple et cohérente | `AltrexDrawer.kt`, `SettingsDialog.kt`, `GalleryNavGraph.kt`, `values/strings.xml` | M | Faible | **Oui** (Ask Image, Audio Scribe, place des Connecteurs) |
| 11 | Retirer les tâches Agent Skills et Sécurité ; remplacer HomeScreen par un accueil Altrex simple | Doublons invisibles | `AgentChatTaskModule.kt`, `SecurityTaskModule.kt`, `HomeScreen.kt` | S–M | Faible | **Oui** |
| 12 | Liste des modèles hébergée chez Altrex, indépendante de `versionName` | Ne plus dépendre de Google | `ModelManagerViewModel.kt:98-99, 1686` | S | Faible | Oui (choix du dépôt) |
| 13 | Vérification de mise à jour dans l'application + tags versionnés en CI | Mises à jour visibles (section 6.3) | nouveau fichier, `.github/workflows/build-debug-apk.yml` | S–M | Faible | Non |
| 14 | Images des discussions hors de `cacheDir` (`files/chat_media/`), avec migration des chemins existants | Images perdues | `ChatViewModel.kt:480-520` | M | Moyen (migration) | Non |
| 15 | Garder l'état OAuth en cours en cas de mort du processus (sauvegarde dans les préférences) | R5 | `ConnectorsScreen.kt:101-125` | S | Faible | Non |
| 16 | Demander `POST_NOTIFICATIONS` quand un rappel est planifié | R9 | `intents/IntentHandler.kt:455` | S | Nul | Non |

### Plus tard

| # | Quoi | Pourquoi | Effort | Risque | Décision |
|---|---|---|---|---|---|
| 17 | Build `release` avec R8 et réduction des ressources, `proguard-rules.pro` (outils `@Tool`, Gson, JNI llama, Termux), signature release | Taille (≈ 115 Mo de code) et démarrage ; fin de `debuggable` | L | Moyen (réflexion) | Oui : le changement de clé oblige à réinstaller (6.3) |
| 18 | Historique des discussions dans son propre stockage, séparé des secrets, avec une limite ; `DataStoreRepository` sans `runBlocking` | Lenteur et mémoire qui grandissent avec l'usage | L | Moyen (migration) | Non |
| 19 | Un seul chiffrement des secrets (`SecretCipher`), migration depuis EncryptedSharedPreferences | Dette, bibliothèque obsolète | M | Moyen | Non |
| 20 | Une seule boucle d'agent pour Code et Discuter, avec des noms d'outils distincts | Comportement prévisible | L | Élevé | Oui |
| 21 | `onTrimMemory` : libérer l'embedding, les modèles LiteRT inactifs ; purger `AgentJobs` finis | Mémoire | S–M | Faible | Non |
| 22 | Lint et avertissements Kotlin (section 7), une seule bibliothèque JSON à terme | Hygiène | M | Faible | Non |
| 23 | Réduire les variantes llama.cpp si le seul appareil visé est le S22 Ultra | −38 Mo décompressés | S | Moyen (autres téléphones) | Oui |

---

*Méthode : lecture du code, recherches de références (`grep`) dans `app/src`, manifeste fusionné et rapport de fusion présents dans `app/build/`, contenu de l'APK `altrex-debug-notes.apk` (`unzip -l` et `unzip -v`), résultats de tests présents dans `app/build/test-results/`, et vérification HTTP de l'existence des fichiers de liste de modèles chez Google. Gradle n'a pas été lancé et rien n'a été installé.*
