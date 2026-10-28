"""Logo final Altrex / Argora — « Bouclier d'accolades ».

Le bouclier est tracé comme une ligne centrale (droites + arcs de cercle tangents), puis son contour
est calculé exactement : chaque droite devient deux droites, chaque arc deux arcs concentriques,
avec des extrémités rondes. Aucun trait (stroke) dans les fichiers finaux.

Usage : PYTHONPATH=<dossier fonttools> python3 build_final.py <dossier de sortie>
"""
import math
import os
import sys

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
HERE = os.path.dirname(os.path.abspath(__file__))
FONT = os.path.join(HERE, "../../../app/src/main/res/font/manrope_extrabold.ttf")

W = 256
NIGHT, BLUE, CYAN, GREEN, WHITE = "#0C151B", "#8CCBF5", "#72D6DB", "#5FD4A0", "#E8EEF2"


def f(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


# --- Tortue : ligne centrale en droites et arcs tangents --------------------------------------
class Turtle:
    def __init__(self, x, y, heading):
        self.x, self.y, self.h = x, y, heading
        self.start = (x, y, heading)
        self.prims = []

    def line(self, length):
        x1 = self.x + length * math.cos(math.radians(self.h))
        y1 = self.y + length * math.sin(math.radians(self.h))
        self.prims.append(("L", self.x, self.y, x1, y1, self.h, self.h))
        self.x, self.y = x1, y1

    def line_to_x(self, x_target):
        self.line((x_target - self.x) / math.cos(math.radians(self.h)))

    def arc(self, radius, turn):
        """turn > 0 : virage horaire à l'écran (centre à droite du cap)."""
        side = 1 if turn > 0 else -1
        nx, ny = math.cos(math.radians(self.h + 90 * side)), math.sin(math.radians(self.h + 90 * side))
        cx, cy = self.x + radius * nx, self.y + radius * ny
        phi0 = math.degrees(math.atan2(self.y - cy, self.x - cx))
        phi1 = phi0 + turn
        x1, y1 = cx + radius * math.cos(math.radians(phi1)), cy + radius * math.sin(math.radians(phi1))
        self.prims.append(("A", self.x, self.y, x1, y1, self.h, self.h + turn, cx, cy, turn))
        self.x, self.y, self.h = x1, y1, self.h + turn


def offset(x, y, h, s):
    """Point décalé de s à droite du cap h."""
    return x + s * math.cos(math.radians(h + 90)), y + s * math.sin(math.radians(h + 90))


def outline(t, half, mirror=False):
    """Contour fermé d'une ligne centrale d'épaisseur 2*half, extrémités rondes."""
    mx = (lambda x: W - x) if mirror else (lambda x: x)
    flip = (lambda sw: 1 - sw) if mirror else (lambda sw: sw)

    def P(pt):
        return f"{f(mx(pt[0]))} {f(pt[1])}"

    right, left = [], []                       # segments côté droit (aller) et gauche (retour)
    for p in t.prims:
        if p[0] == "L":
            _, x0, y0, x1, y1, h, _ = p
            right.append(("L", offset(x0, y0, h, half), offset(x1, y1, h, half)))
            left.append(("L", offset(x1, y1, h, -half), offset(x0, y0, h, -half)))
        else:
            _, x0, y0, x1, y1, h0, h1, cx, cy, turn = p
            a, b = offset(x0, y0, h0, half), offset(x1, y1, h1, half)
            c, d = offset(x1, y1, h1, -half), offset(x0, y0, h0, -half)
            large = 1 if abs(turn) > 180 else 0
            sweep = 1 if turn > 0 else 0
            right.append(("A", a, b, math.hypot(a[0] - cx, a[1] - cy), large, sweep))
            left.append(("A", c, d, math.hypot(c[0] - cx, c[1] - cy), large, 1 - sweep))
    d = f"M{P(right[0][1])}"
    for seg in right:
        d += f" L{P(seg[2])}" if seg[0] == "L" else \
            f" A{f(seg[3])} {f(seg[3])} 0 {seg[4]} {flip(seg[5])} {P(seg[2])}"
    end_left = left[-1][1]
    d += f" A{f(half)} {f(half)} 0 0 {flip(0)} {P(end_left)}"     # extrémité ronde (fin)
    for seg in reversed(left):
        d += f" L{P(seg[2])}" if seg[0] == "L" else \
            f" A{f(seg[3])} {f(seg[3])} 0 {seg[4]} {flip(seg[5])} {P(seg[2])}"
    d += f" A{f(half)} {f(half)} 0 0 {flip(0)} {P(right[0][1])} Z"  # extrémité ronde (début)
    return d


# --- Le bouclier : demi-accolade gauche, symétrisée --------------------------------------------
def half_brace(stroke):
    t = Turtle(111, 37, 165)            # haut, près de l'axe, part vers la gauche en descendant
    t.line(40)
    t.arc(16, -75)                      # coin supérieur → cap vers le bas
    t.line(22)
    t.arc(13, 60)                       # la pointe de l'accolade sort vers l'extérieur…
    t.arc(9.6, -120)                    # … tourne autour de la pointe…
    t.arc(13, 60)                       # … et revient à la verticale
    t.line(12)
    t.arc(62, -54)                      # galbe du bas du bouclier
    t.line_to_x(W / 2)                  # jusqu'à la pointe, sur l'axe
    return t


STROKE = 19
CORE_Y = 119


def shield_paths(stroke=STROKE):
    t = half_brace(stroke)
    return outline(t, stroke / 2), outline(t, stroke / 2, mirror=True), t


def core_path(argora, r=23, cx=W / 2, cy=CORE_Y):
    if argora:
        k = r * 1.22
        return f"M{f(cx)} {f(cy - k)} L{f(cx + k)} {f(cy)} L{f(cx)} {f(cy + k)} L{f(cx - k)} {f(cy)} Z"
    return (f"M{f(cx - r)} {f(cy)} A{f(r)} {f(r)} 0 1 0 {f(cx + r)} {f(cy)} "
            f"A{f(r)} {f(r)} 0 1 0 {f(cx - r)} {f(cy)} Z")


def symbol_paths(argora, stroke=STROKE, core_r=23):
    left, right, _ = shield_paths(stroke)
    return [left, right, core_path(argora, core_r)]


# --- Nom en Manrope ExtraBold, vectorisé ------------------------------------------------------
_font = TTFont(FONT)
_glyphs = _font.getGlyphSet()
_cmap = _font.getBestCmap()
UPM = _font["head"].unitsPerEm
CAP = _font["OS/2"].sCapHeight


def word_path(text, x0, baseline, cap_px, tracking_em=0.18):
    """Chemins des lettres (une par lettre), hauteur de capitale = cap_px. Retourne (chemins, largeur)."""
    scale = cap_px / CAP
    paths, x = [], x0
    for i, ch in enumerate(text):
        g = _cmap[ord(ch)]
        pen = SVGPathPen(_glyphs)
        _glyphs[g].draw(TransformPen(pen, (scale, 0, 0, -scale, x, baseline)))
        paths.append(pen.getCommands())
        x += _glyphs[g].width * scale
        if i < len(text) - 1:
            x += tracking_em * UPM * scale
    return paths, x - x0


# --- Assemblage SVG ---------------------------------------------------------------------------
def grad_defs(gid, c0, c1):
    return (f'<defs><linearGradient id="{gid}" x1="0" y1="0" x2="1" y2="1">'
            f'<stop offset="0" stop-color="{c0}"/><stop offset="1" stop-color="{c1}"/></linearGradient></defs>')


def svg_doc(w, h, body, title, bg=None):
    rect = f'<rect width="{f(w)}" height="{f(h)}" fill="{bg}"/>' if bg else ""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {f(w)} {f(h)}" width="{f(w)}" height="{f(h)}">'
            f'<title>{title}</title>{rect}\n{body}\n</svg>\n')


def paths_el(paths, fill):
    return "".join(f'<path fill="{fill}" d="{d}"/>' for d in paths)


BRANDS = {
    "altrex": dict(argora=False, name="ALTREX", c0=BLUE, c1=CYAN, mono=BLUE, title="Altrex"),
    "argora": dict(argora=True, name="ARGORA", c0=CYAN, c1=GREEN, mono=CYAN, title="Argora"),
}
COLORWAYS = {                       # nom : (remplissage du symbole, remplissage du nom, fond)
    "couleur": ("grad", WHITE, None),
    "couleur-sur-nuit": ("grad", WHITE, NIGHT),
    "mono": ("mono", "mono", None),
    "noir": ("#111111", "#111111", None),
    "blanc": ("#FFFFFF", "#FFFFFF", None),
}


def build(slug, b):
    sym = symbol_paths(b["argora"])
    small = symbol_paths(b["argora"], stroke=26, core_r=27)        # coupe pour 16–32 px
    gid = f"g-{slug}"

    def fills(cw):
        s_fill, w_fill, bg = COLORWAYS[cw]
        sf = f"url(#{gid})" if s_fill == "grad" else (b["mono"] if s_fill == "mono" else s_fill)
        wf = b["mono"] if w_fill == "mono" else w_fill
        defs = grad_defs(gid, b["c0"], b["c1"]) if s_fill == "grad" else ""
        return sf, wf, bg, defs

    for cw in COLORWAYS:
        sf, wf, bg, defs = fills(cw)
        title = f'{b["title"]} — symbole'
        open(f"{OUT}/{slug}-symbole-{cw}.svg", "w").write(svg_doc(W, W, defs + paths_el(sym, sf), title, bg))
        open(f"{OUT}/{slug}-symbole-petit-{cw}.svg", "w").write(
            svg_doc(W, W, defs + paths_el(small, sf), title + " (petites tailles)", bg))
        # Lockup horizontal : symbole 256 de haut, nom centré, capitale = 30 % du symbole
        cap = 68
        letters, width = word_path(b["name"], 0, 0, cap)
        gap = 22
        x_text = W + gap
        baseline = W / 2 + cap / 2
        letters, width = word_path(b["name"], x_text, baseline, cap)
        total = x_text + width + 8
        body = defs + paths_el(sym, sf) + paths_el(letters, wf)
        open(f"{OUT}/{slug}-horizontal-{cw}.svg", "w").write(
            svg_doc(total, W, body, f'{b["title"]} — logo horizontal', bg))
        # Lockup empilé : symbole au-dessus, nom centré dessous
        cap_s = 58
        _, w_s = word_path(b["name"], 0, 0, cap_s)
        total_w = max(W, w_s) + 40
        sx = (total_w - W) / 2
        letters_s, _ = word_path(b["name"], (total_w - w_s) / 2, W + 40 + cap_s, cap_s)
        body = (defs + f'<g transform="translate({f(sx)} 0)">{paths_el(sym, sf)}</g>' + paths_el(letters_s, wf))
        open(f"{OUT}/{slug}-empile-{cw}.svg", "w").write(
            svg_doc(total_w, W + 40 + cap_s + 24, body, f'{b["title"]} — logo empilé', bg))


for slug, b in BRANDS.items():
    build(slug, b)
print("ok", len(os.listdir(OUT)), "fichiers")


# --- Icônes : Android (adaptive icon), tuiles d'appli, favicon ---------------------------------
def tile_svg(slug, b, size=512, radius=0, small=False):
    """Icône carrée : fond nuit, symbole en dégradé, dans la zone sûre."""
    sym = symbol_paths(b["argora"], stroke=26, core_r=27) if small else symbol_paths(b["argora"])
    gid = f"t-{slug}"
    s = size * (0.62 if small else 0.56) / W
    off = (size - W * s) / 2
    rect = f'<rect width="{size}" height="{size}" rx="{radius}" fill="{NIGHT}"/>'
    body = (grad_defs(gid, b["c0"], b["c1"]) + rect +
            f'<g transform="translate({f(off)} {f(off)}) scale({f(s)})">{paths_el(sym, f"url(#{gid})")}</g>')
    return svg_doc(size, size, body, f'{b["title"]} — icône')


def android_foreground(b, monochrome=False):
    """Vector drawable 108×108 ; le symbole tient dans la zone sûre (cercle de 66 dp)."""
    s = 0.285
    off = (108 - W * s) / 2
    if monochrome:
        fill = 'android:fillColor="#FFFFFFFF"'
        paths = "".join(f'\n        <path {fill} android:pathData="{d}" />' for d in symbol_paths(b["argora"]))
        head = '<vector xmlns:android="http://schemas.android.com/apk/res/android"'
    else:
        grad = (f'\n            <aapt:attr name="android:fillColor">\n'
                f'                <gradient android:type="linear" android:startX="0" android:startY="0" '
                f'android:endX="256" android:endY="256" android:startColor="#FF{b["c0"][1:]}" '
                f'android:endColor="#FF{b["c1"][1:]}" />\n            </aapt:attr>')
        paths = "".join(f'\n        <path android:pathData="{d}">{grad}\n        </path>'
                        for d in symbol_paths(b["argora"]))
        head = ('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
                '    xmlns:aapt="http://schemas.android.com/aapt"')
    return (f'<?xml version="1.0" encoding="utf-8"?>\n{head}\n    android:width="108dp"\n'
            f'    android:height="108dp"\n    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
            f'    <group android:translateX="{f(off)}" android:translateY="{f(off)}" '
            f'android:scaleX="{s}" android:scaleY="{s}">{paths}\n    </group>\n</vector>\n')


ICONS = os.path.join(OUT, "..", "icones")
os.makedirs(ICONS, exist_ok=True)
ANDROID = os.path.join(OUT, "..", "android")
os.makedirs(ANDROID, exist_ok=True)
for slug, b in BRANDS.items():
    open(f"{ICONS}/{slug}-icone-512.svg", "w").write(tile_svg(slug, b, 512))
    open(f"{ICONS}/{slug}-icone-arrondie-512.svg", "w").write(tile_svg(slug, b, 512, radius=112))
    open(f"{ICONS}/{slug}-icone-petite.svg", "w").write(tile_svg(slug, b, 64, radius=14, small=True))
    open(f"{ANDROID}/{slug}_ic_launcher_foreground.xml", "w").write(android_foreground(b))
    open(f"{ANDROID}/{slug}_ic_launcher_monochrome.xml", "w").write(android_foreground(b, monochrome=True))
open(f"{ANDROID}/ic_launcher_background.xml", "w").write(
    '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
    f'    <color name="ic_launcher_background">{NIGHT}</color>\n</resources>\n')
print("icônes ok")
