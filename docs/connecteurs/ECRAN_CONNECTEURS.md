# Écran Connecteurs (étape 3)

Il s'ouvre depuis le menu → **Connecteurs**. Il a deux parties :

- **Connectés** : les serveurs MCP enregistrés, avec leur état, le nombre d'outils, un interrupteur, la politique d'autorisation, la liste des outils, et les boutons « Reconnecter » et « Déconnecter ».
- **Catalogue** : un appui suffit pour ajouter un connecteur. On peut aussi en ajouter un par son adresse.

## Catalogue

Fichier : `connectors/ConnectorCatalog.kt`.

Chaque entrée OAuth a été vérifiée le 28/10/2026 :
- son serveur publie les métadonnées RFC 9728 et RFC 8414 ;
- il accepte l'enregistrement dynamique (RFC 7591) ;
- il gère PKCE S256.

| Type | Connecteurs |
|---|---|
| OAuth | Hugging Face, Sentry, Supabase, Linear, Notion, Atlassian, monday.com, Intercom, Zapier, Canva, Webflow, Wix, Stripe, PayPal |
| Clé (`Authorization: Bearer`) | GitHub (jeton d'accès personnel ; le serveur GitHub n'accepte pas l'enregistrement dynamique) |
| Public | DeepWiki, Context7, Cloudflare Docs |

Ne sont pas inclus :
- Asana et Box, qui n'ont pas d'enregistrement dynamique ;
- Figma et Vercel, qui réservent leur serveur à des clients approuvés.

Pas de logos (marques déposées) : une initiale en couleur à la place.

## Autorisations (lecture / action)

Chaque outil garde les indications que le serveur donne dans `annotations` :
- `readOnlyHint` devient `McpTool.read_only` ;
- `destructiveHint` devient `McpTool.destructive`.

La politique se règle par connecteur (`McpServer.permission_policy`) :

| Politique | Lecture | Action | Suppression |
|---|---|---|---|
| Lire librement, demander pour les actions (défaut) | libre | demandée | demandée |
| Toujours demander | demandée | demandée | demandée |
| Tout autoriser (sauf suppressions) | libre | libre | demandée |

« Toujours autoriser » sur un outil précis l'emporte sur la politique du connecteur (règle : `RunMcpTool.needsApproval`).

Ces indications viennent du serveur : un serveur malveillant pourrait déclarer « lecture » un outil qui écrit. C'est pour cette raison que le catalogue ne contient que des serveurs officiels.

## Reconnexion

Reconnecter ou ajouter de nouveau un connecteur conserve ses réglages :
- outils activés ;
- autorisations « toujours » ;
- politique ;
- id du catalogue.

Au retour dans Discuter, `refreshFromStore()` reprend les changements faits dans l'écran Connecteurs :
- elle reconnecte seulement si des serveurs ont été ajoutés ou retirés ;
- sinon, elle recopie juste les réglages.

## Connecteurs locaux (stdio)

Un connecteur local est un serveur MCP lancé comme un programme (`npx`, `uvx`, `python`…) dans le Linux du modèle. Il communique par l'entrée et la sortie standard, comme ceux de Claude Code ou de Claude Desktop.

**Enregistrement.** Il est enregistré comme un connecteur distant, avec pour url `stdio:` suivie de la commande.

**Préparation.** Au premier ajout, `LocalMcp.prepare` installe le Linux si besoin, puis le lanceur de la commande :
- `nodejs` et `npm` pour `npx` ;
- `uv` et `python3` pour `uvx`.

**Exécution.**
- Le processus est lancé depuis `/root`, avec stderr à part : la sortie standard porte le protocole.
- Il est arrêté avec son client.
- Délai de connexion : 90 s, au lieu de 10 s pour un connecteur distant.

**Catalogue :**
- les serveurs de référence MCP : fichiers, mémoire, réflexion pas à pas, pages web (`uvx`), heure (`uvx`) ;
- REA (`npx -y rea-agents@latest mcp`, avec des modules natifs : non vérifié sous Alpine) ;
- toute autre commande saisie à la main.

**Autorisations.** Les mêmes que pour les connecteurs distants (lecture libre, actions demandées). Le serveur ne voit que le Linux du modèle.

**Test.** `StdioMcpLiveTest` (avec `MCP_STDIO_TEST=1`) branche un vrai `server-memory` sur le même transport : les 9 outils sont listés, et les indications `readOnlyHint` sont lues.
