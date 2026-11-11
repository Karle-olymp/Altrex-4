# Contrat partagé Android ↔ PC

Forge existe en deux moteurs : Kotlin (téléphone, `app/…/forge`) et Rust (PC, `pc/crates/altrex-core`). Pour qu'ils ne divergent pas, ces fichiers décrivent le comportement attendu sous forme de cas « entrée → résultat ». Les **mêmes fichiers** sont lus par les tests des deux côtés :

- Kotlin : `app/src/test/java/com/google/ai/edge/gallery/forge/ContratPartageTest.kt` ;
- Rust : `pc/crates/altrex-core/tests/contrat.rs`.

| Fichier | Ce qu'il fixe |
|---|---|
| `commandes.json` | lecture d'une commande shell : simple pipeline, lecture seule, programme, à risque (dialecte POSIX) |
| `permissions.json` | mode + accords de la session + action → autoriser, demander (titre, « Toujours », risque, escalade) ou refuser (motif) |
| `appels.json` | texte du modèle → appels d'outils lus et texte affiché (protocole `<tool_call>`) |
| `journal.json` | lignes du journal (format 1) relues puis réécrites à l'identique, et état replié |

S'y ajoutent les schémas des 14 outils, figés dans `docs/forge/OUTILS.md` § 2 et vérifiés des deux côtés.

Règle : un changement de comportement de Forge commence par un cas ajouté ici ; les deux suites doivent rester vertes. Les cas ne couvrent que ce qui est commun (le dialecte PowerShell du PC a ses propres tests Rust).

Écart connu : le téléphone lit le JSON des appels avec `org.json`, tolérant (guillemets simples, clés sans guillemets) ; le PC lit du JSON strict. Les cas d'`appels.json` restent en JSON strict.
