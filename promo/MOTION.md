# MOTION.md — Altrex

Pas d'images de référence fournies (dossier `examples` vide) : ce fichier part de l'app elle-même
(intro, loader comète, bulles, tiroir). Là où l'app ne tranche pas : ASK ME.

## 1. Couleurs
| Hex | Rôle |
|---|---|
| #0C151B | Fond de toutes les scènes. Jamais de noir pur. |
| #8CCBF5 | Accent principal : logo, mots-clés, curseur, traits lumineux. |
| #72D6DB | Second accent : dégradés avec #8CCBF5, ondes audio. |
| #5FD4A0 | Succès, « modèle chargé », point de statut. |
| #F2C38B | Une seule mise en avant par vidéo au plus (ex. un chiffre). |
| #E8EEF2 | Texte principal (blanc cassé). |
| #8A9AA6 | Texte secondaire. |

## 2. Typo
- Manrope 700/800 pour les titres, 500 pour le corps. Titres 96–140 px (16:9) / 110–160 px (9:16).
- JetBrains Mono pour le code, tokens/s, CPU/GPU/NPU.
- Un message par plan, 7 mots max à l'écran.

## 3. Timing
- Personnalité : **Premium**. Entrées 450–600 ms, sorties 300–400 ms (entrées 30–50 % plus longues).
- Tenue minimum d'un texte : 1,5 s + 0,3 s par mot.
- Stagger total < 500 ms. Transitions de scène 500–700 ms.

## 4. Mouvement
- 30 fps. Easing standard `cubic-bezier(0.4, 0, 0.2, 1)` ; entrées ease-out `cubic-bezier(0, 0, 0.2, 1)`, sorties ease-in `cubic-bezier(0.4, 0, 1, 1)`. Pas d'overshoot.
- Trois couches : primaire (le message), secondaire (UI, bulles), ambiante (halo lent, particules, 6–10 s par cycle).
- Déplacements courts (≤ 1/3 de l'écran), jamais linéaires ; jamais opacité seule pour un changement important (toujours opacité + translation ou échelle).
- Signature : le trait lumineux du « A » qui se dessine, et la comète du loader.

## 5. Texture et finition
Halo radial bleu très doux en fond, léger grain (2–3 %), vignette. Coins arrondis 20–28 px, bordures 1 px à 12 % d'opacité de l'accent. Pas d'ombres portées dures.

## 6. Ne jamais
1. Rebond, élasticité ou overshoot.
2. Couleurs hors palette, dégradés arc-en-ciel.
3. Plus de 3 éléments en mouvement en même temps.
4. Texte qui défile trop vite pour être lu.
5. Visuels de cloud ou de serveurs « qui reçoivent » les données.

## 7. Exemple réussi (l'intro de l'app)
1. Fond nuit, halo qui respire.
2. Le contour du « A » se trace en 900 ms, trait #8CCBF5.
3. Le « A » se remplit en dégradé bleu → cyan, 500 ms ease-out.
4. Un reflet balaie le logo.
5. « ALTREX » monte de 12 px en fondu, lettres espacées, 600 ms.
6. Tenue, puis sortie en 350 ms ease-in.
