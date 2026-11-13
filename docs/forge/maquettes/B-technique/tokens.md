# Forge — Direction B « Technique sobre »

Un instrument professionnel : le fond nuit d'Altrex, des filets d'un pixel, un seul accent (le cyan de marque #72D6DB), une typo très lisible et beaucoup de mono pour tout ce qui est donnée.
Le lien avec Altrex est direct : même fond #0C151B, bouclier officiel, cyan de marque comme unique couleur d'action. Le vert #5FD4A0 ne sert qu'au sens (réussite, ajouts).

## Couleurs
| Jeton | Hex | Usage |
|---|---|---|
| bg | #0C151B | fond (nuit Altrex) |
| surface | #101B22 | panneaux (tâches, saisie, feuilles) |
| surface2 | #15232B | bloc du message, segment actif |
| line | #1F2F38 | filets |
| line2 | #2B3F4A | contours des champs, boutons |
| text | #E3EBEF | texte (≈ 15:1) |
| muted | #90A4AF | texte secondaire (≈ 7:1) |
| faint | #5C707B | décor seulement |
| accent | #72D6DB | action, sélection, progression |
| on-accent | #05191B | texte sur cyan (≈ 11:1) |
| ok / add | #5FD4A0 | réussite, lignes ajoutées |
| del | #F08F8F | lignes supprimées |
| warn | #E6C46A | indices, interrompu, Tout autoriser |

## Typo
- Manrope 500 à 700 (pas de 800) : titres 17 à 20 px, corps 14 px.
- JetBrains Mono : en-têtes de section en capitales espacées (11 px, +0,12 em), états, ligne d'état, diff, étiquettes de champs.

## Formes, espacement, élévation
- Rayons : 8 / 6 / 4. Aucune ombre : la hiérarchie vient des filets et de deux niveaux de surface.
- Grille de 4 px ; marges de 16 px ; zones tactiles de 44 à 48 px.
- Progression des tâches en 5 segments (une case par tâche).
- Sélection : barre de 2 px à gauche + teinte cyan à 7 %.

## Animation (appli réelle)
- Sobre et rapide : 120 à 180 ms, ease-out, aucun rebond.
- Le segment de la tâche en cours se remplit de gauche à droite pendant l'étape ; le carré « en cours » clignote doucement (opacité 1 → 0,5, 1,6 s).
- Cartes d'outils : dépliage par hauteur (160 ms), sans glissement.
- Nouvelle ligne du flux : fondu de 120 ms seulement, pour garder le calme pendant les longues sessions.
- Changement de mode : la barre cyan glisse d'une option à l'autre (180 ms).
