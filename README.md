# Altrex

Altrex est un assistant Android en français. Il fait tourner des modèles de langage **sur le
téléphone** (LiteRT-LM, llama.cpp/GGUF, AICore), et, si tu le choisis, des modèles cloud avec ta
propre clé ou des Spaces Hugging Face.

> Altrex est un fork de [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery),
> distribué sous licence Apache 2.0 (voir [LICENSE](LICENSE)).

## Ce que fait l'application

Le menu propose quatre espaces et trois boutons de configuration.

- **Discuter** : la discussion principale. Pièces jointes (photo, caméra, audio, fichier, PDF) et,
  en option, des outils : skills, connecteurs MCP, actions du téléphone, recherche web et un Linux
  propre au modèle.
- **Code** : un dossier de projet avec éditeur, aperçu, agent de code, recherche web et scan de
  sécurité SecurCode.
- **Notes vocales** : dictée, liste, graphe des notes, export Obsidian.
- **Terminal** : un Linux Alpine (PRoot) pour toi : paquets, Python, git…
- **Modèles** : télécharger, importer (fichier, Hugging Face), ajouter un modèle cloud ou un
  Space, benchmark des modèles LiteRT.
- **Connecteurs** : serveurs MCP distants (OAuth) et locaux.
- **Réglages** : apparence, modèles et clés, Linux du modèle, mémoire locale (un dossier où le
  modèle peut piocher), confidentialité, application.

Hors du menu : le panneau « Demander à Altrex » (texte sélectionné, partage, bouton assistant du
téléphone).

Sans modèle, l'application ouvre un écran de bienvenue qui propose d'en télécharger, d'en
importer ou d'ajouter un modèle cloud.

## Stack technique

- **Langage :** Kotlin, **Jetpack Compose** (Material 3)
- **Build :** Gradle (Kotlin DSL), Android Gradle Plugin
- **Inférence :** LiteRT-LM, llama.cpp (JNI), AICore ; API cloud des fournisseurs choisis
- **Injection de dépendances :** Hilt ; **sérialisation :** Moshi, kotlinx.serialization, Protobuf
- **Divers :** MCP, Hugging Face, PDFBox, PRoot (Alpine Linux)

## Prérequis

- **JDK 17** ou plus récent
- **Android SDK** avec `compileSdk 37`
- **NDK** et **CMake 3.22.1** (compilation de la partie native `llama.cpp`)
- Un appareil **arm64-v8a** (ABI supportée : `arm64-v8a` uniquement), **Android 12 (API 31)** minimum

## Compilation

```bash
# Récupérer le projet
git clone https://github.com/Karle-olymp/Altrex-4.git
cd Altrex-4

# Compiler l'APK de debug
./gradlew assembleDebug

# L'APK est généré dans :
# app/build/outputs/apk/debug/app-debug.apk
```

Pour installer directement sur un appareil connecté :

```bash
./gradlew installDebug
```

> La compilation native (`externalNativeBuild` / CMake) nécessite le NDK et ne produit que
> l'ABI `arm64-v8a`.

## Intégration continue

Le workflow GitHub Actions `.github/workflows/build-debug-apk.yml` compile l'APK de debug
(déclenchement manuel `workflow_dispatch`) et publie l'artefact dans une release GitHub
`debug-latest`.

## Structure du dépôt

```
app/src/main/java/com/google/ai/edge/gallery/
  agent/            Runtime d'agent (boucle ReAct, streaming d'événements)
  customtasks/      Écran agent de Discuter, skills et serveurs MCP
  data/             Modèles, DataStore, configurations
  runtime/          Aides des moteurs (LiteRT-LM, llama.cpp, AICore)
  skills/           Chargement des Skills (SKILL.md + scripts)
  tools/            Outils exposés au modèle (fichiers, JS, MCP, intents)
  notes/            Notes vocales
  terminal/         Linux Alpine (PRoot)
  connectors/       Connecteurs MCP
  ui/               Écrans Compose (menu, chat, code, modèles, réglages, benchmark…)
app/src/main/assets/skills/    Skills d'exemple
app/src/main/cpp/              Sources natives (llama.cpp / ggml)
app/src/main/res/              Ressources (thème, chaînes, icônes)
```

## Licence

Distribué sous **Apache License 2.0**. Voir le fichier [LICENSE](LICENSE).

Ce projet étant dérivé de Google AI Edge Gallery, les en-têtes de copyright d'origine
(« Copyright 2025 Google LLC ») sont conservés dans les fichiers concernés.
