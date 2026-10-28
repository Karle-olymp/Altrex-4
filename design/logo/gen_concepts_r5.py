"""Tour 5 : nouvelles variantes du triskel orientées informatique.
R1 triskel taillé (facettes, noyau hexagonal), R2 triskel-git (branches + commits), R3 triskel-radar (arcs).
Altrex : formes rondes / hexagone ; Argora : losanges."""
import math
import os
import sys

from gen_concepts import INK, f, lockup, svg

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
C = 128
ROTS = (90, 210, 330)


def pol(r, deg):
    a = math.radians(deg)
    return C + r * math.cos(a), C - r * math.sin(a)


def spiral(rot, t, r0=40, r1=92, turn=110):
    return r0 + (r1 - r0) * t, rot + turn * t


def poly(pts):
    return "M" + " L".join(f"{f(x)} {f(y)}" for x, y in pts) + " Z"


def ngon(x, y, r, n, start=90):
    return poly([(x + r * math.cos(math.radians(start + k * 360 / n)),
                  y - r * math.sin(math.radians(start + k * 360 / n))) for k in range(n)])


def diamond(x, y, r):
    return ngon(x, y, r, 4, 90)


# R1 — Triskel taillé : spirale échantillonnée en 5 facettes, tête hexagonale (Altrex) / losange (Argora).
def r1_arm(rot, argora, w1=30.0, steps=4):
    left, right = [], []
    for k in range(steps + 1):
        t = k / steps
        r, a = spiral(rot, t)
        w = 3 + (w1 - 3) * t ** 1.3
        left.append(pol(r - w / 2, a))
        right.append(pol(r + w / 2, a))
    hx, hy = pol(*spiral(rot, 1.0))
    head = diamond(hx, hy, 19) if argora else ngon(hx, hy, 17, 6, 0)
    return f'<path d="{poly(left + right[::-1])}"/><path d="{head}"/>'


def r1(argora):
    arms = "".join(r1_arm(r, argora) for r in ROTS)
    core = diamond(C, C, 22) if argora else ngon(C, C, 21, 6, 0)
    return f'<g fill="{INK}">{arms}<path d="{core}"/></g>'


# R2 — Triskel-git : branches courbes depuis un nœud central, deux commits par branche.
LW = 14


def r2_arm(rot, argora, n=40):
    pts = [pol(*spiral(rot, k / n, r0=30, r1=88, turn=85)) for k in range(n + 1)]
    line = (f'<path d="M' + " L".join(f"{f(x)} {f(y)}" for x, y in pts) + f'" fill="none" stroke="{INK}" '
            f'stroke-width="{LW}" stroke-linecap="round" stroke-linejoin="round"/>')
    nodes = []
    for t, r in [(0.6, 10), (1.0, 17)]:
        x, y = pol(*spiral(rot, t, r0=30, r1=88, turn=85))
        nodes.append(f'<path d="{diamond(x, y, r * 1.25)}"/>' if argora
                     else f'<circle cx="{f(x)}" cy="{f(y)}" r="{r}"/>')
    return line + "".join(nodes)


def r2(argora):
    arms = "".join(r2_arm(r, argora) for r in ROTS)
    core = f'<path d="{diamond(C, C, 25)}"/>' if argora else f'<circle cx="{C}" cy="{C}" r="21"/>'
    return f'<g fill="{INK}">{arms}{core}</g>'


# R3 — Triskel-radar : trois arcs concentriques décalés de 120°, chacun terminé par un point.
def arc_band(r, w, a0, a1):
    ro, ri = r + w / 2, r - w / 2
    p1, p2, p3, p4 = pol(ro, a0), pol(ro, a1), pol(ri, a1), pol(ri, a0)
    large = 1 if abs(a1 - a0) > 180 else 0
    return (f"M{f(p1[0])} {f(p1[1])} A{f(ro)} {f(ro)} 0 {large} 0 {f(p2[0])} {f(p2[1])} "
            f"L{f(p3[0])} {f(p3[1])} A{f(ri)} {f(ri)} 0 {large} 1 {f(p4[0])} {f(p4[1])} Z")


def r3(argora):
    parts = []
    for rot, r, w in zip(ROTS, (44, 70, 96), (14, 16, 18)):
        a0, a1 = rot, rot + 140
        parts.append(f'<path d="{arc_band(r, w, a0, a1)}"/>')
        hx, hy = pol(r, a1)
        parts.append(f'<path d="{diamond(hx, hy, w * 1.0)}"/>' if argora
                     else f'<circle cx="{f(hx)}" cy="{f(hy)}" r="{f(w * 0.8)}"/>')
    core = f'<path d="{diamond(C, C, 20)}"/>' if argora else f'<circle cx="{C}" cy="{C}" r="16"/>'
    return f'<g fill="{INK}">{"".join(parts)}{core}</g>'


for k, fn in [("r1", r1), ("r2", r2), ("r3", r3)]:
    alt, arg = fn(False), fn(True)
    open(f"{OUT}/{k}-altrex.svg", "w").write(svg(alt))
    open(f"{OUT}/{k}-argora.svg", "w").write(svg(arg))
    open(f"{OUT}/{k}-altrex-lockup.svg", "w").write(lockup(alt, "ALTREX"))
    open(f"{OUT}/{k}-argora-lockup.svg", "w").write(lockup(arg, "ARGORA"))
print("ok")
