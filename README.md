# Altrex

Altrex est une application Android d'IA embarquée ("on-device") qui permet de télécharger,
exécuter et explorer des modèles de langage **localement sur le téléphone**, sans dépendre
d'un serveur. Elle s'appuie sur **LiteRT-LM**, **llama.cpp (GGUF)** et **AICore/Gemma**, et
propose plusieurs espaces : discussion, code, agents, sécurité et benchmark.

> Altrex est un fork de [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery),
> distribué sous licence Apache 2.0 (voir [LICENSE](LICENSE)).

## Fonctionnalités

- **Chat LLM** — discussion en streaming avec un modèle local (markdown, historique, dictée).
- **Prompt Lab** ("Code") — expérimentation de prompts et de configurations de modèle.
- **Agent Chat** — agents avec **Skills** (compétences) et **outils MCP**, gestion de secrets
  chiffrés.
- **Assistant de code** — ouverture d'un dossier de projet, explorateur de fichiers, lecture et
  édition de code assistées par le modèle (diff avant/après).
- **Sécurité (SecurCode)** — agent en **lecture seule** qui analyse un projet (secrets,
  dépendances vulnérables, failles).
- **Model Manager** — import de modèles depuis un fichier local ou Hugging Face, gestion du
  stockage et des téléchargements.
- **Benchmark** — mesure des performances des modèles on-device.
- **Notifications** — rappels planifiés.

## Stack technique

- **Langage :** Kotlin, **Jetpack Compose** (Material 3)
- **Build :** Gradle (Kotlin DSL), Android Gradle Plugin
- **Inférence :** LiteRT-LM, TFLite (GPU/support), llama.cpp (JNI), MediaPipe
- **Injection de dépendances :** Hilt ; **sérialisation :** Moshi, kotlinx.serialization, Protobuf
- **Divers :** Firebase (Analytics, Messaging), Hugging Face (OAuth), Ktor, MCP

## Prérequis

- **JDK 17** ou plus récent
- **Android SDK** avec `compileSdk 36`
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
  customtasks/      Agent Chat, Skills et serveurs MCP
  data/             Modèles, DataStore, configurations
  runtime/          Aides des moteurs (LiteRT-LM, llama.cpp, AICore)
  skills/           Chargement des Skills (SKILL.md + scripts)
  tools/            Outils exposés au modèle (fichiers, JS, MCP, intents)
  ui/               Écrans Compose (chat, code, sécurité, modèles, benchmark…)
app/src/main/assets/skills/    Skills d'exemple
app/src/main/cpp/              Sources natives (llama.cpp / ggml)
app/src/main/res/              Ressources (thème, chaînes, icônes)
```

## Licence

Distribué sous **Apache License 2.0**. Voir le fichier [LICENSE](LICENSE).

Ce projet étant dérivé de Google AI Edge Gallery, les en-têtes de copyright d'origine
(« Copyright 2025 Google LLC ») sont conservés dans les fichiers concernés.
