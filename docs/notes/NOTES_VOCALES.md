# Notes vocales

Elles s'ouvrent depuis le menu → **Notes vocales**. On parle, l'appli écoute et transcrit, la note est gardée. Les notes s'affichent en **liste** ou en **graphe** (comme le graphe d'Obsidian).

Code : `app/src/main/java/com/google/ai/edge/gallery/notes/`.

## Enregistrer

Fichiers : `VoiceRecorder.kt`, `NotesRecordScreen.kt`.

- Reconnaissance d'Android (`SpeechRecognizer`), **sur l'appareil d'abord** (`createOnDeviceSpeechRecognizer`), dans la langue du téléphone (français si aucune).
- Android 13 et plus : l'appli demande quelles langues sont installées (`checkRecognitionSupport`).
  - Langue installée : l'enregistrement démarre.
  - Langue téléchargeable : bouton « Télécharger le pack de langue » (`triggerModelDownload`).
  - Sinon, l'écran le dit clairement. Le service de reconnaissance du système (qui peut envoyer l'audio à son fournisseur) n'est utilisé que si l'utilisateur appuie sur « Utiliser le service du système ». L'écran affiche alors « Service système » au lieu de « Sur l'appareil ».
- **L'écoute continue pendant les pauses** :
  - Android 13 et plus : une session segmentée (un résultat par phrase).
  - Sinon, ou quand une session s'arrête : relance automatique, avec un délai qui grandit après des erreurs à la suite (jamais de boucle folle). Après 8 erreurs d'affilée, l'écoute se met en pause et le texte est gardé.
- Le gros bouton met en pause ou reprend. « Terminer » enregistre la note. « Abandonner » demande confirmation.
- L'écran reste allumé pendant l'enregistrement. Quand l'appli passe en arrière-plan, l'enregistrement se met en pause.
- **L'audio n'est jamais gardé**, seulement le texte.
- Permission micro (`RECORD_AUDIO`, déjà dans le manifeste) : demandée au moment d'appuyer sur « Enregistrer ».

## Ne jamais perdre une note

- Pendant l'enregistrement, le texte est écrit dans un brouillon (`notes/draft.recording`) à chaque phrase reconnue, et toutes les 2 s pour les mots en cours.
- À l'appui sur « Terminer », la note est créée tout de suite avec la transcription brute. Le brouillon n'est effacé qu'après.
- Si l'appli a été tuée ou a planté, un bandeau propose au retour : **Reprendre**, **Garder comme note** ou **Supprimer**.

## Stockage

Fichier : `NoteStore.kt`.

- Un fichier JSON par note dans `filesDir/notes/<id>.json`. C'est le stockage privé de l'appli, sans sauvegarde cloud (`allowBackup=false`).
- **Écriture atomique** : écriture dans `<id>.json.tmp`, `fsync`, puis renommage. Une note sur le disque est donc toujours entière.
- Un fichier illisible est ignoré (et laissé en place) : il ne bloque pas les autres notes.
- Index en mémoire, notes triées de la plus récente à la plus ancienne.

Champs d'une note :

| Champ | Contenu |
|---|---|
| `id` | UUID |
| `createdAt`, `updatedAt` | millisecondes epoch |
| `title` | titre |
| `text` | texte nettoyé ou modifié par l'utilisateur |
| `rawTranscript` | ce que la reconnaissance a entendu, tel quel |
| `summary` | 1 ou 2 phrases |
| `tags` | 3 à 6 mots-clés en minuscules |
| `structuredBy` | nom du modèle, `"heuristique"`, ou `null` |

Aucun contenu de note n'est écrit dans les journaux (logs).

## Structurer avec le modèle

Fichiers : `NoteStructuring.kt` (fonctions pures), `NotesRepository.kt`.

- Le modèle reçoit la transcription. Il doit renvoyer en JSON :
  - `titre` ;
  - `texte` : corrigé (ponctuation, mots mal reconnus), sans rien inventer ni résumer ;
  - `resume` ;
  - `tags`.
- Les requêtes passent par `QuietCompletion` (GGUF local, LiteRT, Space ou API cloud), avec **le modèle déjà chargé** dans Discuter. Aucun modèle n'est chargé exprès pour une note.
- La réponse est lue avec prudence. Sont acceptés :
  - un bloc entre ``` ;
  - une réflexion `<think>` avant le JSON ;
  - du texte autour ;
  - un JSON coupé (refermé automatiquement) ou mal formé (lu champ par champ).

  Un « texte » beaucoup plus court que la transcription est un résumé : il est refusé et la transcription est gardée.
- Si un champ manque, ou si la réponse est illisible, **mise en forme simple** (heuristiques) :
  - titre : la première phrase, ou les premiers mots ;
  - tags : les mots les plus fréquents, sans les mots vides.
- Au-delà de 6 000 caractères, le modèle ne réécrit pas le texte : il donne seulement le titre, le résumé et les tags.
- **Vie privée** :
  - Structuration automatique après « Terminer » : seulement si le modèle chargé est **local**. Le réglage est dans le menu ⋮, activé par défaut.
  - Modèle hors du téléphone (Space ou API cloud) : uniquement par le bouton « Structurer avec le modèle », après une confirmation qui dit où le texte part.
  - La fiche de la note indique quel modèle l'a structurée.

## Liens entre notes (arêtes du graphe)

Fichier : `NoteLinks.kt` (fonction pure, testée).

1. **Tags communs**. Un tag rare compte plus qu'un tag présent sur beaucoup de notes (poids 1/√(nombre de notes qui l'ont)). La casse et les accents sont ignorés.
2. **Wikiliens `[[Titre]]`** dans le texte, dans un sens ou dans l'autre. `[[Titre|alias]]` et `[[Titre#section]]` sont acceptés.
3. **Sens proche** (facultatif) : vecteurs d'EmbeddingGemma, cosinus ≥ 0,62. Seulement si l'utilisateur a déjà activé la recherche par le sens de la mémoire locale (même modèle, même verrou, calculé en arrière-plan, jamais pendant une réponse du chat, mis en cache dans `notes/cache/vectors.bin`).

Les trois signaux se combinent : poids = 1 − (1 − 0,6·tags)(1 − 0,9·lien)(1 − 0,55·sens).

Pour éviter une pelote illisible :
- un lien de poids < 0,12 est ignoré ;
- chaque note garde au plus 8 liens « tags seulement » et 3 liens « sens seulement », les plus forts. À poids égal, les notes les plus proches dans le temps passent en premier.

## Graphe

Fichiers : `GraphLayout.kt` (disposition, pure et testée), `NotesGraphView.kt` (dessin).

Disposition :
- Disposition par forces, écrite pour l'appli (aucune dépendance ajoutée) :
  - les liens tirent leurs notes vers une longueur de repos ;
  - les notes se repoussent (quadtree **Barnes–Hut**, O(n log n)) ;
  - une gravité faible, plus forte à l'horizontale, adapte la forme à un téléphone tenu droit.
- **Déterministe** : positions de départ en spirale de tournesol, dans l'ordre des notes, sans hasard.
- Calculée hors du fil principal. Elle **s'arrête** quand elle est stable : environ 300 pas, rien ne tourne au repos.
- Quand les notes changent, elle repart des positions connues avec une faible « température » : mouvement doux.

Dessin :
- Canvas Compose, sans allocation par image dans le dessin :
  - liens regroupés en 3 chemins ;
  - halo pré-rendu une fois par couleur ;
  - textes mesurés une fois par graphe ;
  - zones d'étiquettes dans un tableau réutilisé.
- Fond nuit #0C151B (ou clair en thème jour).
- Les deux groupes les plus fréquents sont en cyan #72D6DB et vert #5FD4A0, puis viennent quatre teintes discrètes. Une légende est affichée en bas.
- Taille d'une note selon son nombre de liens. Liens fins, peu opaques, légèrement courbés.
- Étiquettes :
  - elles apparaissent en zoomant, les notes les plus liées d'abord ;
  - à la vue d'ensemble, environ le quart le plus lié ;
  - elles ne se chevauchent jamais, grâce à un placement glouton par ordre d'importance.
- Gestes :
  - déplacer et pincer, avec inertie ;
  - double appui : recadrer.
- Appui sur une note : elle et ses voisines s'allument, le reste s'estompe, la vue se rapproche. Une carte s'ouvre en bas : titre, date, nombre de liens, résumé, tags, « Ouvrir ».
- Recherche : les notes qui correspondent sont entourées, les autres s'estompent.
- **Mouvement réduit** (animations désactivées dans Android) : disposition calculée d'un coup, pas d'animation, pas d'inertie.
- Écrans vides soignés : constellation et bouton « Enregistrer une note ». Avec une seule note, un message explique d'où viennent les liens.

## Export

Fichier : `NotesExport.kt`.

- Fichiers Markdown compatibles Obsidian :
  - front matter YAML : titre, dates, tags, modèle ;
  - `# Titre`, résumé en citation, texte ;
  - section « Notes liées » en `[[wikiliens]]` ;
  - transcription brute repliée.
- Destination : `Android/media/<appli>/Linux/Notes/`. C'est le dossier déjà partagé avec le terminal (`/root/partage/Notes`), sans permission de stockage. Obsidian peut ouvrir ce dossier.
- Un fichier par note, nommé d'après le titre. Deux notes de même titre donnent « Titre » et « Titre (2) ».
- Menu ⋮ → « Exporter toutes les notes », ou une seule note depuis sa fiche ou sa carte.
- « Partager » ouvre la feuille de partage d'Android avec la note en Markdown (texte). L'utilisateur choisit l'appli.

## Limites

- La qualité de la transcription dépend du service de reconnaissance du téléphone et du pack de langue installé.
- Sur Android 12 (API 31-32), l'appli ne peut pas savoir à l'avance si la langue est installée : une erreur de langue est signalée au moment de l'enregistrement.
- La session segmentée (Android 13 et plus) dépend du service. S'il ne la gère pas, l'appli relance la reconnaissance à chaque fin de session. Certains services jouent alors un bip à chaque relance.
- La structuration utilise seulement le modèle déjà chargé. Sans modèle chargé : mise en forme simple, puis bouton pour structurer plus tard.
- Les liens par le sens ne sont calculés que si la recherche par le sens de la mémoire locale est activée. Leur seuil (0,62) n'a pas été calibré sur de vraies notes.
- Pas d'audio, pas de conversation en temps réel avec le modèle (prévu en V2).

## Ce qui a été vérifié

- Tests JVM (`app/src/test/.../notes/`) :
  - stockage : aller-retour, écriture atomique, fichier corrompu ignoré, suppression, brouillon ;
  - heuristiques de titre, résumé et tags ;
  - lecture de la réponse du modèle : bonne, entre ```, coupée, cassée, vide ;
  - wikiliens et calcul des liens ;
  - disposition : déterministe, converge, pas de NaN, 0/1/2/300 notes, 300 notes en temps borné.
- Tests de rendu Robolectric (`NotesRenderTest`), image par image :
  - graphe vide, 1 note, 40 notes (sombre et clair), note sélectionnée, 300 notes ;
  - enregistrement (écoute, reconnaissance indisponible) ;
  - liste vide et pleine, fiche de note.
- Pour enregistrer les images en PNG : `NOTES_PREVIEW_DIR=/un/dossier ./gradlew :app:testDebugUnitTest --tests '*NotesRenderTest*'`.
- Non vérifié sans téléphone :
  - la reconnaissance vocale réelle : relances, segments, packs de langue, bips ;
  - la fluidité réelle à 300 notes ;
  - la structuration par un vrai modèle.
