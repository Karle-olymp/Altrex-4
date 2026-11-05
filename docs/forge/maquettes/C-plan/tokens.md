# Forge — Direction C « Plan d'architecte »

L'écran est une feuille de plan : un papier bleu nuit quadrillé, des traits pâles, des cadres tracés avec repères d'angle, des cartouches (feuille, échelle, date), des numéros de repère (01, N1…), des cotes. Blueprint y est chez lui.
Le lien avec Altrex : bouclier officiel, cyan de marque #72D6DB comme « crayon » principal (traits actifs, repères, action), vert #5FD4A0 pour la réussite. Un second crayon jaune sert aux annotations et aux alertes.

## Couleurs
| Jeton | Hex | Usage |
|---|---|---|
| bg | #0A1C2E | papier bleu nuit |
| grille fine / forte | rgba(150,195,235,.05) tous les 12 px / .12 tous les 60 px | quadrillage |
| surface | #0C2136 (92 %) | cadres |
| line | #2F5373 | traits secondaires, pointillés |
| line2 | #6F98BB | traits de cadre |
| text | #EAF3FA | texte (≈ 15:1) |
| muted | #A3BFD6 | texte secondaire (≈ 9:1) |
| faint | #5F84A4 | numéros de repère inactifs |
| accent | #72D6DB | crayon cyan : actif, repères d'angle, action principale |
| on-accent | #04202A | texte sur cyan |
| pencil | #F2D27C | crayon jaune : annotations, « en cours », alertes |
| ok / add | #5FD4A0 / #7FE0B4 | réussite, ajouts |
| del | #FF9C8F | suppressions |

## Typo
- Manrope 400 à 700 : titres 21 px, corps 14 px.
- JetBrains Mono en capitales espacées (+0,08 à +0,16 em, 9 à 11,5 px) pour tout ce qui est annotation : verbes d'outils (LU, MODIFIÉ, COMMANDE), onglets, cartouche, numéros « 01 — ».

## Formes, espacement, élévation
- Rayons : 0 à 2 px (sauf pastilles rondes : repères numérotés, icônes, Envoyer).
- Cadres : trait 1 px + repères d'angle cyan (haut-gauche, bas-droite) qui débordent de 5 px.
- Pointillés pour ce qui est provisoire : sélection, « en cours », proposition, Refuser/Ignorer.
- Pas d'ombre ; la profondeur vient de l'opacité du papier.
- Grille de 4 px ; marges de 14 à 16 px ; zones tactiles de 44 à 48 px.

## Animation (appli réelle)
- **Tracé** : les liens et les cadres se dessinent (stroke-dashoffset, 400 à 600 ms) quand le modèle propose un plan ou un schéma : on voit le plan « se tracer ».
- Les liens actifs (requête en cours dans Blueprint, vue en direct) font défiler leurs pointillés (1 s par motif).
- Valider un plan : les pointillés des étapes deviennent pleins, l'une après l'autre (80 ms d'écart).
- Repères d'angle : ils se resserrent de 4 px quand un cadre reçoit le focus (150 ms).
- Mouvement réduit : apparition directe, sans tracé.
