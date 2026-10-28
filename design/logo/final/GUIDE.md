# Altrex · Argora — guide du logo

## L'idée

Deux accolades de code `{ }` dessinent un bouclier autour d'un noyau : **le code qui protège ce qui tourne
sur ton appareil**. Altrex et Argora partagent le même bouclier ; seul le noyau change.

| Marque | Noyau | Dégradé | Couleur unie |
|---|---|---|---|
| **Altrex** (l'appli, la plateforme) | rond | `#8CCBF5` → `#72D6DB` | `#8CCBF5` |
| **Argora** (le modèle cybersécurité) | losange | `#72D6DB` → `#5FD4A0` | `#72D6DB` |

Le losange est la signature d'Argora : on peut le réutiliser seul (puces, états « analyse en cours »,
animations).

## Couleurs

| Rôle | HEX | RGB |
|---|---|---|
| Nuit (fond) | `#0C151B` | 12, 21, 27 |
| Bleu Altrex | `#8CCBF5` | 140, 203, 245 |
| Cyan | `#72D6DB` | 114, 214, 219 |
| Vert Argora | `#5FD4A0` | 95, 212, 160 |
| Blanc cassé (texte du nom sur fond sombre) | `#E8EEF2` | 232, 238, 242 |

Contraste sur fond nuit : bleu 10,5:1, cyan 10,9:1, vert 10,0:1, blanc cassé 15,8:1 (AA et AAA).

## Typographie

Le nom est en **Manrope ExtraBold**, capitales, approche +18 %, vectorisé dans les fichiers (aucune police à
installer). Manrope est sous licence SIL Open Font License, qui autorise l'usage dans un logo.
Textes d'accompagnement : Manrope ; code et chiffres techniques : JetBrains Mono.

## Fichiers

| Dossier | Contenu |
|---|---|
| `svg/` | Masters vectoriels. Pour chaque marque : `symbole`, `symbole-petit`, `horizontal`, `empile`, chacun en `couleur` (nom blanc cassé, pour fond sombre), `couleur-sur-nuit` (avec fond), `mono`, `noir`, `blanc` |
| `png/` | Exports prêts à l'emploi : icônes 16 → 512 px (carrées et arrondies), logos horizontaux et empilés |
| `web/` | `favicon.ico` (16/32/48), `apple-touch-icon.png` (180), icônes 192 et 512 |
| `android/` | Icône adaptative Android : premier plan en dégradé, version monochrome (Android 13+), couleur de fond |
| `icones/` | Sources SVG des tuiles d'icône |
| `presentation/` | Planche de présentation (8 images) ; source : `presentation.html` |
| `build_final.py` | Génère tous les SVG et les fichiers Android à partir de la géométrie |

## Règles d'usage

**Zone de protection** : autour du logo, laisser au moins **x**, où x = le diamètre du noyau.

**Tailles minimales**
- Symbole : 24 px. En dessous (16–24 px : favicon, barre d'état), utiliser **`symbole-petit`**, au trait
  épaissi.
- Logo horizontal : 120 px de large.
- Logo empilé : 64 px de large.

**Fonds autorisés** : nuit `#0C151B` (version couleur, à privilégier), blanc ou clair (version noire),
photo sombre et unie (version blanche). Sur fond clair, le dégradé est trop pâle : utiliser `noir`.

**À ne pas faire**
- Changer les couleurs, ou mettre le dégradé d'Altrex sur Argora (et inversement).
- Échanger les noyaux (le losange est réservé à Argora).
- Déformer, faire pivoter, ajouter une ombre, un contour ou un effet lumineux.
- Refermer le haut du bouclier : l'ouverture entre les deux accolades fait partie du dessin.
- Recomposer le nom dans une autre police ou changer l'espacement.

## Intégration Android

Les fichiers de `android/` remplacent ceux de `app/src/main/res/` :
- `<marque>_ic_launcher_foreground.xml` → `drawable/ic_launcher_foreground.xml`
- `<marque>_ic_launcher_monochrome.xml` → `drawable/ic_launcher_monochrome.xml` (et pointer
  `<monochrome>` de `mipmap-anydpi-v26/ic_launcher.xml` dessus)
- `ic_launcher_background.xml` → `values/ic_launcher_background.xml` (fond nuit)

Le symbole occupe la zone sûre de l'icône adaptative (cercle de 66 dp sur 108) : il n'est jamais rogné,
quel que soit le masque du lanceur.

## Mouvement (proposition)

Intro : les deux accolades arrivent de part et d'autre et se referment autour du noyau (450–600 ms,
ease-out, sans rebond), puis le noyau s'allume. Pour Argora en analyse : le losange pulse doucement
(cycle de 1,2 s).

## Avant usage public

Aucune recherche d'antériorité de marque n'a été faite. Avant de déposer ou de publier le logo, faire
vérifier les bases de marques (INPI, EUIPO) et lancer une recherche d'image inversée.
