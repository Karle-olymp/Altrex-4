"""Tour 3 des concepts Altrex / Argora, dans l'esprit des symboles d'IA (rayonnants, une touche propre) :
Obturateur, Triskel-comète, Rose de convergence. Exploration : l'obturateur utilise un <mask>, à vectoriser."""
import math
import os
import sys

from gen_concepts import INK, f, lockup, svg

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
C = 128


def pol(r, deg, cx=C, cy=C):
    a = math.radians(deg)
    return cx + r * math.cos(a), cy - r * math.sin(a)


# Concept G — Obturateur : N lames ; l'ouverture est un triangle (Altrex, N=3) ou un losange (Argora, N=4).
def aperture(n, r0, R=108, gap=9, key="ap"):
    verts = [pol(r0, 90 + k * 360 / n) for k in range(n)]
    hole = "M" + " L".join(f"{f(x)} {f(y)}" for x, y in verts) + " Z"
    cuts = []
    for i in range(n):
        (x0, y0), (x1, y1) = verts[i], verts[(i + 1) % n]
        dx, dy = x1 - x0, y1 - y0
        L = math.hypot(dx, dy)
        ux, uy = dx / L, dy / L
        # prolonge le côté i au-delà du sommet i+1 jusqu'au bord du disque (et un peu plus)
        ex, ey = x1 + ux * 2 * R, y1 + uy * 2 * R
        cuts.append(f'<line x1="{f(x1)}" y1="{f(y1)}" x2="{f(ex)}" y2="{f(ey)}" stroke="#000" stroke-width="{gap}"/>')
    return (f'<defs><mask id="{key}"><circle cx="{C}" cy="{C}" r="{R}" fill="#fff"/>'
            f'<path d="{hole}" fill="#000"/>{"".join(cuts)}</mask></defs>'
            f'<circle cx="{C}" cy="{C}" r="{R}" fill="{INK}" mask="url(#{key})"/>')


G_alt = aperture(3, 50, key="ap3")
G_arg = aperture(4, 44, key="ap4")


# Concept H — Triskel-comète : trois comètes en spirale autour d'un noyau.
def comet_arm(rot, r0=40, r1=92, turn=150, w0=2.0, w1=34.0, n=48):
    left, right = [], []
    for k in range(n + 1):
        t = k / n
        r = r0 + (r1 - r0) * t
        a = rot + turn * t
        w = w0 + (w1 - w0) * t ** 1.4
        # normale approchée : direction radiale (spirale lente)
        left.append(pol(r - w / 2, a))
        right.append(pol(r + w / 2, a))
    body = "M" + " L".join(f"{f(x)} {f(y)}" for x, y in left + right[::-1]) + " Z"
    hx, hy = pol(r1, rot + turn)
    return f'<path d="{body}"/><circle cx="{f(hx)}" cy="{f(hy)}" r="{f(w1 / 2)}"/>'


arms = "".join(comet_arm(90 + 120 * k) for k in range(3))
H_alt = f'<g fill="{INK}">{arms}<circle cx="{C}" cy="{C}" r="18"/></g>'
H_arg = f'<g fill="{INK}">{arms}<path d="M128 106 L150 128 L128 150 L106 128 Z"/></g>'


# Concept I — Rose de convergence : quatre chevrons en X vers le noyau (Altrex) / vers l'extérieur (Argora).
def chevron_toward(apex_dist, inward=True, half=34, h=26):
    """Chevron sur l'axe horizontal gauche, pointe vers le centre (inward) ou vers l'extérieur."""
    if inward:
        ax = C - apex_dist                       # sommet extérieur du '>'
        pts = [(ax - h - half, C - half), (ax - half, C - half), (ax, C),
               (ax - half, C + half), (ax - h - half, C + half), (ax - h, C)]
    else:
        ax = C - apex_dist - h - half            # sommet du '<' tourné vers l'extérieur
        pts = [(ax + h + half, C - half), (ax + half, C - half), (ax, C),
               (ax + half, C + half), (ax + h + half, C + half), (ax + h, C)]
    return "M" + " L".join(f"{f(x)} {f(y)}" for x, y in pts) + " Z"


def rose(inward, core):
    d = chevron_toward(30 if inward else 30, inward)
    g = "".join(f'<path d="{d}" transform="rotate({45 + 90 * k} {C} {C})"/>' for k in range(4))
    return f'<g fill="{INK}">{g}{core}</g>'


I_alt = rose(True, f'<circle cx="{C}" cy="{C}" r="17"/>')
I_arg = rose(False, '<path d="M128 108 L148 128 L128 148 L108 128 Z"/>')

for k, alt, arg in [("g", G_alt, G_arg), ("h", H_alt, H_arg), ("i", I_alt, I_arg)]:
    open(f"{OUT}/{k}-altrex.svg", "w").write(svg(alt))
    open(f"{OUT}/{k}-argora.svg", "w").write(svg(arg))
    open(f"{OUT}/{k}-altrex-lockup.svg", "w").write(lockup(alt, "ALTREX"))
    open(f"{OUT}/{k}-argora-lockup.svg", "w").write(lockup(arg, "ARGORA"))
print("ok")
