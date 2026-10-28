"""Tour 4 : le triskel-comète tiré vers l'informatique. T1 triskel + noyau-puce, T2 comète de bits, T3 triskel-circuit,
T4 hybride (queue en pixels).
Altrex : pixels carrés / puce carrée ; Argora : losanges."""
import math
import os
import sys

from gen_concepts import INK, f, lockup, svg

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
C = 128


def pol(r, deg):
    a = math.radians(deg)
    return C + r * math.cos(a), C - r * math.sin(a)


def spiral(rot, t, r0=40, r1=92, turn=150):
    return r0 + (r1 - r0) * t, rot + turn * t


def square(x, y, half, angle=0, radius=0):
    """Carré centré (x, y), tourné de `angle` degrés (sens SVG)."""
    return (f'<rect x="{f(x - half)}" y="{f(y - half)}" width="{f(2 * half)}" height="{f(2 * half)}" '
            f'rx="{f(radius)}" transform="rotate({f(angle)} {f(x)} {f(y)})"/>')


def core(diamond, half=17):
    return square(C, C, half, 45 if diamond else 0, 0 if diamond else 5)


# T1 — Triskel d'origine (têtes rondes), noyau = puce carrée (Altrex) / losange (Argora).
def t1_arm(rot, diamond, n=48, w0=2.0, w1=34.0):
    left, right = [], []
    for k in range(n + 1):
        t = k / n
        r, a = spiral(rot, t)
        w = w0 + (w1 - w0) * t ** 1.4
        left.append(pol(r - w / 2, a))
        right.append(pol(r + w / 2, a))
    body = "M" + " L".join(f"{f(x)} {f(y)}" for x, y in left + right[::-1]) + " Z"
    hx, hy = pol(*spiral(rot, 1.0))
    return f'<path d="{body}"/><circle cx="{f(hx)}" cy="{f(hy)}" r="{f(w1 / 2)}"/>'


# T2 — Comète de bits : pixels qui grossissent vers la tête.
def t2_arm(rot, diamond, steps=(0.0, 0.27, 0.52, 0.76, 1.0)):
    out = []
    for i, t in enumerate(steps):
        r, a = spiral(rot, t, r0=44, r1=92, turn=105)
        x, y = pol(r, a)
        half = 4 + 12 * (i / (len(steps) - 1)) ** 1.4
        out.append(square(x, y, half, 45 if diamond else 0, 0 if diamond else half * 0.25))
    return "".join(out)


# T4 — Hybride : comète pleine côté tête, la queue se décompose en pixels (carrés Altrex / losanges Argora).
def t4_arm(rot, diamond, n=40, t_cut=0.42, w1=34.0):
    left, right = [], []
    for k in range(n + 1):
        t = t_cut + (1 - t_cut) * k / n
        r, a = spiral(rot, t)
        w = 6 + (w1 - 6) * ((t - t_cut) / (1 - t_cut)) ** 1.3
        left.append(pol(r - w / 2, a))
        right.append(pol(r + w / 2, a))
    body = "M" + " L".join(f"{f(x)} {f(y)}" for x, y in left + right[::-1]) + " Z"
    hx, hy = pol(*spiral(rot, 1.0))
    bits = []
    for t, half in [(0.29, 5.5), (0.17, 4.5), (0.05, 3.5)]:
        x, y = pol(*spiral(rot, t))
        bits.append(square(x, y, half, 45 if diamond else 0, 0 if diamond else 1.5))
    return f'<path d="{body}"/><circle cx="{f(hx)}" cy="{f(hy)}" r="{f(w1 / 2)}"/>' + "".join(bits)


# T3 — Triskel-circuit : piste coudée à 45° terminée par une pastille (via).
SW = 15


def t3_arm(rot, diamond):
    # bras local « vers le haut », coudé vers la gauche (sens de rotation), puis tourné de rot
    pts = [(0, -36), (0, -58), (-26, -84), (-50, -84)]
    d = "M" + " L".join(f"{f(C + x)} {f(C + y)}" for x, y in pts)
    px, py = C - 50 - 14, C - 84
    pad = (f'<path d="{d} L{f(px + 14)} {f(py)}" fill="none" stroke="{INK}" stroke-width="{SW}" '
           f'stroke-linejoin="miter" stroke-linecap="butt"/>')
    if diamond:
        via = (f'<path fill-rule="evenodd" d="M{f(px)} {f(py - 20)} L{f(px + 20)} {f(py)} L{f(px)} {f(py + 20)} '
               f'L{f(px - 20)} {f(py)} Z M{f(px)} {f(py - 7)} L{f(px + 7)} {f(py)} L{f(px)} {f(py + 7)} L{f(px - 7)} {f(py)} Z"/>')
    else:
        via = (f'<path fill-rule="evenodd" d="M{f(px - 15)} {f(py)} A15 15 0 1 0 {f(px + 15)} {f(py)} '
               f'A15 15 0 1 0 {f(px - 15)} {f(py)} Z M{f(px - 6)} {f(py)} A6 6 0 1 1 {f(px + 6)} {f(py)} '
               f'A6 6 0 1 1 {f(px - 6)} {f(py)} Z"/>')
    return f'<g transform="rotate({rot} {C} {C}) translate({C} {C}) scale(0.95) translate(-{C} -{C})">{pad}{via}</g>'


def t3_core(diamond):
    if diamond:
        return square(C, C, 20, 45)
    return square(C, C, 22, 0, 4)


def build(arm_fn, diamond, core_fn=None, rots=(90, 210, 330)):
    arms = "".join(arm_fn(r, diamond) for r in rots)
    c = core_fn(diamond) if core_fn else core(diamond)
    return f'<g fill="{INK}">{arms}{c}</g>'


variants = {
    "t1": (build(t1_arm, False), build(t1_arm, True)),
    "t2": (build(t2_arm, False), build(t2_arm, True)),
    "t4": (build(t4_arm, False), build(t4_arm, True)),
    "t3": (build(t3_arm, False, t3_core, rots=(0, 120, 240)), build(t3_arm, True, t3_core, rots=(0, 120, 240))),
}
for k, (alt, arg) in variants.items():
    open(f"{OUT}/{k}-altrex.svg", "w").write(svg(alt))
    open(f"{OUT}/{k}-argora.svg", "w").write(svg(arg))
    open(f"{OUT}/{k}-altrex-lockup.svg", "w").write(lockup(alt, "ALTREX"))
    open(f"{OUT}/{k}-argora-lockup.svg", "w").write(lockup(arg, "ARGORA"))
print("ok")
