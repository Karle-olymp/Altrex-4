"""Tour 6 : nouvelle direction (ni triskel ni A). N1 étoile d'accolades, N2 galet numérique, N3 signal intérieur,
N4 signature Lissajous, N5 bloc-âme.
Règle de famille : Argora = le même symbole tourné à 45° (les carrés deviennent des losanges)."""
import math
import os
import sys

from gen_concepts import INK, f, lockup, svg

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
C = 128


def turn45(body):
    return f'<g transform="rotate(45 {C} {C})">{body}</g>'


# N1 — Étoile d'accolades : quatre accolades, pointe vers l'extérieur, autour d'un point.
SW1 = 17


def brace(W=40, h=16, p=34, dist=44):
    """Accolade horizontale au-dessus du centre, pointe vers le haut."""
    y = C - dist
    d = (f"M{C - W} {y + h} Q{C - W} {y} {C - W + h} {y} L{C - h} {y} Q{C} {y} {C} {y - p} "
         f"Q{C} {y} {C + h} {y} L{C + W - h} {y} Q{C + W} {y} {C + W} {y + h}")
    return (f'<path d="{d}" fill="none" stroke="{INK}" stroke-width="{SW1}" '
            f'stroke-linecap="round" stroke-linejoin="round"/>')


def n1(argora):
    b = brace()
    body = "".join(f'<g transform="rotate({90 * k} {C} {C})">{b}</g>' for k in range(4))
    core = (f'<rect x="{C - 13}" y="{C - 13}" width="26" height="26"/>' if argora
            else f'<circle cx="{C}" cy="{C}" r="15"/>')
    g = f'<g fill="{INK}">{body}{core}</g>'
    return turn45(g) if argora else g


# N2 — Galet numérique : demi-disque lisse à gauche, grille de pixels à droite qui se raréfie vers le bord.
R2, PITCH, PX = 96, 24, 19


def n2(argora):
    top, bot = C - R2, C + R2
    parts = [f'<path d="M{C} {top} A{R2} {R2} 0 0 0 {C} {bot} Z"/>']
    rows = int(2 * R2 / PITCH)
    for i in range(rows):
        cy = top + PITCH * (i + 0.5)
        for j in range(5):
            cx = C + PITCH * (j + 0.5) - 2
            dist = math.hypot(cx - C, cy - C)
            if dist > R2 - 4:
                continue
            # densité qui baisse vers le bord : on saute quelques pixels extérieurs (motif fixe)
            if dist > R2 * 0.72 and (i + j) % 2 == 1:
                continue
            s_ = PX if dist < R2 * 0.6 else PX * 0.78
            parts.append(f'<rect x="{f(cx - s_ / 2)}" y="{f(cy - s_ / 2)}" width="{f(s_)}" height="{f(s_)}"/>')
    g = f'<g fill="{INK}">{"".join(parts)}</g>'
    return turn45(g) if argora else g


# N3 — Signal intérieur : deux paires d'ondes tournées vers un point central.
def wave(r, w, side):
    """Arc de 100° centré sur l'axe horizontal ; side=-1 à gauche (concavité vers le centre)."""
    a0, a1 = (-50, 50) if side > 0 else (130, 230)
    pts = []
    for ang, rr in [(a0, r + w / 2), (a1, r + w / 2), (a1, r - w / 2), (a0, r - w / 2)]:
        pts.append((C + rr * math.cos(math.radians(ang)), C - rr * math.sin(math.radians(ang))))
    (x1, y1), (x2, y2), (x3, y3), (x4, y4) = pts
    ro, ri = r + w / 2, r - w / 2
    sweep_o = 0
    return (f'<path d="M{f(x1)} {f(y1)} A{f(ro)} {f(ro)} 0 0 {sweep_o} {f(x2)} {f(y2)} '
            f'A{f(w / 2)} {f(w / 2)} 0 0 {sweep_o} {f(x3)} {f(y3)} A{f(ri)} {f(ri)} 0 0 {1 - sweep_o} {f(x4)} {f(y4)} '
            f'A{f(w / 2)} {f(w / 2)} 0 0 {sweep_o} {f(x1)} {f(y1)} Z"/>')


def n3(argora):
    waves = "".join(wave(r, w, side) for r, w in [(54, 18), (92, 20)] for side in (-1, 1))
    core = (f'<rect x="{C - 16}" y="{C - 16}" width="32" height="32"/>' if argora
            else f'<circle cx="{C}" cy="{C}" r="19"/>')
    g = f'<g fill="{INK}">{waves}{core}</g>'
    return turn45(g) if argora else g


# N4 — Signature Lissajous : figure d'oscilloscope 3:2, une seule ligne continue, nœud au centre.
def n4(argora, n=480):
    pts = []
    for k in range(n + 1):
        t = 2 * math.pi * k / n
        pts.append((C + 90 * math.sin(3 * t + math.pi / 2), C + 90 * math.sin(2 * t)))
    d = "M" + " L".join(f"{f(x)} {f(y)}" for x, y in pts) + " Z"
    line = (f'<path d="{d}" fill="none" stroke="{INK}" stroke-width="15" stroke-linejoin="round"/>')
    core = (f'<rect x="{C - 15}" y="{C - 15}" width="30" height="30"/>' if argora
            else f'<circle cx="{C}" cy="{C}" r="0"/>')
    g = f'<g fill="{INK}">{line}{core}</g>'
    return turn45(g) if argora else g


# N5 — Bloc-âme : un pixel géant (la machine) percé d'un trou rond décentré qui abrite un noyau.
def n5(argora):
    hole_x, hole_y, hr, cr = 146, 110, 50, 21
    d = (f"M58 32 H198 A26 26 0 0 1 224 58 V198 A26 26 0 0 1 198 224 H58 A26 26 0 0 1 32 198 V58 "
         f"A26 26 0 0 1 58 32 Z M{hole_x - hr} {hole_y} A{hr} {hr} 0 1 0 {hole_x + hr} {hole_y} "
         f"A{hr} {hr} 0 1 0 {hole_x - hr} {hole_y} Z")
    g = (f'<g fill="{INK}"><path fill-rule="evenodd" d="{d}"/>'
         f'<circle cx="{hole_x}" cy="{hole_y}" r="{cr}"/></g>')
    if argora:
        return f'<g transform="translate({C} {C}) scale(0.8) translate(-{C} -{C})">{turn45(g)}</g>'
    return g


for k, fn in [("n1", n1), ("n2", n2), ("n3", n3), ("n4", n4), ("n5", n5)]:
    alt, arg = fn(False), fn(True)
    open(f"{OUT}/{k}-altrex.svg", "w").write(svg(alt))
    open(f"{OUT}/{k}-argora.svg", "w").write(svg(arg))
    open(f"{OUT}/{k}-altrex-lockup.svg", "w").write(lockup(alt, "ALTREX"))
    open(f"{OUT}/{k}-argora-lockup.svg", "w").write(lockup(arg, "ARGORA"))
print("ok")
