# 🛡️ Altrex-4 — Architecture Cible, Feuille de Route & Intégrations IA

Ce document résume l'analyse architecturale d'**Altrex-4** et la stratégie d'évolution vers une plateforme mobile autonome de développement et de cybersécurité (Système 1 + Système 2 + Sandbox Kali Linux + Workflows MCP).

---

## 1. Architecture Globale : "Système 1 + Système 2 + Bras Externe"

Pour optimiser la RAM, la batterie et la réactivité sur Android tout en ayant une puissance maximale en cybersécurité et en développement, l'architecture repose sur 4 piliers complémentaires :

1. **Cerveau Rapide / Système 1 (`ggml-org/Laya-GGUF` en local) :**
   * **Rôle :** Prise de décision ultra-rapide (15 à 150 ms), classification, routage d'outils et sécurité (Gatekeeper).
   * **Modèle :** `Laya-Q8_0.gguf` (449 Mo, architecture ModernBERT 0.4B) via l'API `/v1/systemone` de `llama.cpp`.
2. **Expert Technique / Système 2 (Modèle Cyber LLM en local) :**
   * **Rôle :** Raisonnement complexe, génération et correction de code (Mode type Claude Code), audit de sécurité (`SecurCode`) et pilotage du terminal Kali Linux.
3. **Environnement d'Exécution Isolé (Double Terminal `proot` Kali Linux) :**
   * **Rôle :** Un terminal pour l'utilisateur et un terminal sandboxé dédié au modèle IA pour exécuter les outils de pentest/audit et les scripts sans risquer de corrompre la session utilisateur.
4. **Bras Armé Externe (`n8n` connecté via MCP) :**
   * **Rôle :** Exécution des workflows lourds ou externes (API Shodan, VirusTotal, alertes Slack/Telegram/Discord, création de tickets GitHub/Jira, génération de rapports) via le protocole MCP sans consommer la RAM du téléphone.

---

## 2. Choix et Configuration du Modèle de Décision Laya

### Quel fichier télécharger ?
* **Modèle principal recommandé :** **`Laya-Q8_0.gguf` (449 Mo)** depuis le dépôt officiel `ggml-org/Laya-GGUF`.
  * *Pourquoi `Q8_0` (8 bits) plutôt que `BF16` (844 Mo) ?* Conserve 99,9 % de la précision du 16 bits sur une architecture BERT, mais divise par deux la RAM consommée et double la vitesse sur mobile.
  * *Attention :* Bien prendre le modèle de `ggml-org/Laya-GGUF` (compatible nativement avec `llama.cpp` `/v1/systemone`) et **pas** celui de `mys/laya-GGUF` (qui nécessite `ggmlc`).
* **Alternative Multilingue (si les prompts internes sont en français) :**
  * `convaiinnovations/laya-multilingual` (`mmBERT-base`, ~345 Mo en Q8_0, contexte 1024) ou sa version `.tflite` publiée par `litert-community` pour Android.

### Cas d'usage de Laya-GGUF dans Altrex-4
Laya évalue un état (`state`) et répond instantanément à des questions typées sans générer de texte inutile :
* **`noul` (Oui / Non / Incertain) :** Détection de *Prompt Injection* avant d'envoyer un fichier suspect au LLM principal.
* **`choice` (Choix multiple) :** Routage intelligent de la requête utilisateur vers le bon module (*Simple Chat*, *Mode Claude Code*, *Audit SecurCode*, *Terminal Kali*, ou *Appel MCP n8n*).
* **`score` (Évaluation de risque : Faible / Moyen / Critique) :** Analyse chaque commande bash générée par le modèle Cyber **avant** son exécution dans Kali Linux. Si le risque est élevé, déclenche une **Action Card** demandant la validation de l'utilisateur.

---

## 3. Migration du Terminal : d'Alpine Linux vers Kali Linux (`proot`)

Passer d'Alpine (`musl libc`) à Kali Linux (`glibc`) permet une compatibilité native avec 100 % des outils de cybersécurité (`nmap`, `sqlmap`, `metasploit`, `nikto`, `gobuster`, `ffuf`, `nuclei`, etc.).

### Bonnes pratiques d'implémentation sur Android (No-Root) :
1. **Image RootFS légère :**
   * Utiliser **Kali NetHunter Rootless Minimal (arm64)** (~150-200 Mo compressé) ou une base **Debian-Slim arm64** avec les dépôts `kali-rolling` dans `/etc/apt/sources.list`.
   * Télécharger le RootFS au premier lancement de l'application (ne pas l'inclure en dur dans l'APK).
2. **Accélération de `dpkg` / `apt` sous `proot` :**
   * Ajouter l'option `force-unsafe-io` dans `/etc/dpkg/dpkg.cfg.d/force-unsafe-io` à l'intérieur du conteneur Kali pour éviter les lenteurs d'écriture disque (`fsync`) sous `proot`.
3. **Contournement de la limite Android `CAP_NET_RAW` (No-Root) :**
   * Sous `proot` sans root réel, Android bloque les *raw sockets* (donc `ping` standard ou `nmap -sS` SYN scan échouent).
   * **Solution :** Configurer des alias dans le `.bashrc` du terminal de l'IA et l'indiquer dans le System Prompt :
     ```bash
     alias nmap='nmap -sT --unprivileged'
     ```
   * Privilégier les scans TCP Connect (`-sT`) et les outils applicatifs/web (`curl`, `sqlmap`, `ffuf`, `nikto`, scripts Python).

---

## 4. Inspirations Architecturales des Dépôts Analysés

### A. Inspiration de `Twigpine/openclaude` (Mode "Claude Code")
Plutôt que d'exécuter tout Node.js en permanence (gourmand pour les petits modèles locaux), reprendre en Kotlin ses mécaniques clés :
* **Compaction du contexte (*Transcript Compaction*) :** Résumer automatiquement l'historique de conversation et les sorties du terminal lorsque la fenêtre de contexte du modèle local approche de sa limite.
* **Repo Map :** Générer une carte légère de l'arborescence et des symboles du projet pour que l'agent comprenne la codebase sans lire tous les fichiers en entier.
* **Outils déterministes :** Structurer strictement les outils `bash`, `grep`, `glob`, `view_file` et `edit_file` (avec prévisualisation des diffs).

### B. Inspiration de `aayushch/laya` (Pipeline d'Agents, Sécurité & RAG Local)
Architecture sous licence **Apache 2.0** (compatible avec Altrex-4), dont 4 briques sont à reprendre en Kotlin :
1. **Pipeline séquentiel strict :**
   `Ingest` ➔ `Rules` ➔ `Router (Laya-GGUF)` ➔ `Workers (Personas)` ➔ `Stager` ➔ `Emit (Action Card)`.
2. **3 Modes de permission pour les Agents (`engine/laya/agents/`) :**
   * `plan` : Lecture seule (idéal pour **SecurCode**).
   * `acceptEdits` : Autorise les modifications de fichiers avec diff, mais **bloque toute exécution shell/terminal**.
   * `force` : Accès complet au terminal Kali (pour l'agent offensif/pentest en sandbox).
3. **Système d'Action Cards (Human-in-the-loop) :**
   * Interface Jetpack Compose présentant les actions critiques préparées par l'IA sous forme de cartes avec boutons **[Approuver]**, **[Modifier]** ou **[Refuser]** avant exécution.
4. **Recherche Hybride Locale (SQLite FTS5 + Vecteurs) :**
   * Utiliser **SQLite FTS5** (natif sur Android) pour la recherche par mots-clés exacts (BM25) combinée aux embeddings vectoriels via l'algorithme **RRF (Reciprocal Rank Fusion)** pour fouiller instantanément dans de gros projets de code.
5. **Prompts Modulaires par Persona :**
   * Séparer les instructions en fichiers Markdown distincts : `router.md`, `auditor.md` (SecurCode), `pentester.md` (Kali), `engineer.md` (Code/Diffs).

### C. Intégration de `n8n-io/n8n` (Serveur MCP & Automatisation SOAR)
* **Ne pas l'installer en local sur le téléphone** (trop lourd en RAM).
* **L'utiliser comme Serveur MCP distant :**
  * Dans `n8n`, chaque workflow doté d'un nœud **MCP Trigger** est exposé via HTTP (`/mcp-server/http`).
  * Altrex-4 s'y connecte via son client MCP en utilisant un **Bearer Token** fort.
  * **Structure des Skills (`.opencode/skills/setup-mcps/SKILL.md`) :** Conserver le format `SKILL.md` avec en-tête YAML (`name`, `description`, `triggers`) pour permettre à l'agent de découvrir et d'utiliser dynamiquement les workflows `n8n` (ex: *Scan Shodan*, *Envoi de rapport de vulnérabilité*, *Création d'issue GitHub*).

---

## 5. Checklist des Prochaines Étapes (TODO)

- [ ] **Namespace :** Prévoir la migration progressive du package `com.google.ai.edge.gallery` vers un `applicationId` propre à Altrex-4.
- [ ] **Intégration Système 1 :** Intégrer `Laya-Q8_0.gguf` (449 Mo) via `llama.cpp` pour le routage des requêtes et le filtrage de sécurité des commandes bash.
- [ ] **Terminal Kali :** Remplacer le rootfs Alpine par un rootfs Kali NetHunter Minimal (arm64) téléchargeable à la demande + configurer `force-unsafe-io` et les alias `--unprivileged`.
- [ ] **UI Action Cards :** Créer le composant Jetpack Compose "Action Card" pour valider les commandes Kali risquées (détectées par Laya) et les patchs de code.
- [ ] **Recherche Codebase :** Implémenter l'indexation locale SQLite FTS5 pour accélérer `SecurCode` et le `Code Assistant`.
- [ ] **Connecteur MCP n8n :** Tester un premier workflow `n8n` déclenché depuis Altrex-4 via MCP (ex: génération et envoi d'un rapport d'audit de sécurité).
