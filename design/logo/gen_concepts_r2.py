"""Tour 2 des concepts Altrex / Argora : Convergence, Abri, Strates (symboles noirs + lockups)."""
import math
import os
import sys

from gen_concepts import INK, f, lockup, svg

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)


# Concept D — Convergence : >•< (Altrex) / <•> (Argora). Bras à 45°, épaisseur horizontale 34.
def chevron_right(x_tip, half=50, h=34, cy=128):
    """'>' : pointes à x_tip, sommet à x_tip + half + h."""
    return (f"M{f(x_tip)} {f(cy - half)} L{f(x_tip + h)} {f(cy - half)} L{f(x_tip + h + half)} {f(cy)} "
            f"L{f(x_tip + h)} {f(cy + half)} L{f(x_tip)} {f(cy + half)} L{f(x_tip + half)} {f(cy)} Z")


def mirror(d):
    """Symétrie horizontale autour de x = 128."""
    out, toks = [], d.split()
    for t in toks:
        if t[0] in "ML":
            out.append(t[0] + f(256 - float(t[1:])))
        else:
            out.append(t)
    return " ".join(out)


right_in = chevron_right(12)                      # '>' à gauche, sommet à x = 96
D_core = '<circle cx="128" cy="128" r="17"/>'
D_alt = f'<g fill="{INK}"><path d="{right_in}"/><path d="{mirror(right_in)}"/>{D_core}</g>'
left_out = mirror(chevron_right(160))              # '<' à gauche, sommet à x = 12
D_arg = (f'<g fill="{INK}"><path d="{left_out}"/><path d="{mirror(left_out)}"/>'
         f'<path d="M128 106 L150 128 L128 150 L106 128 Z"/></g>')


# Concept E2 — Abri : deux arches emboîtées au-dessus d'un noyau (point Altrex / losange Argora).
def arch_band(ro, ri, cx=128, cy=150, leg=50):
    return (f"M{cx - ro} {cy} A{ro} {ro} 0 0 1 {cx + ro} {cy} V{cy + leg} H{cx + ri} V{cy} "
            f"A{ri} {ri} 0 0 0 {cx - ri} {cy} V{cy + leg} H{cx - ro} Z")


arches = f'<path d="{arch_band(104, 78)}"/><path d="{arch_band(62, 40)}"/>'
E_alt = f'<g fill="{INK}">{arches}<circle cx="128" cy="180" r="18"/></g>'
E_arg = f'<g fill="{INK}">{arches}<path d="M128 158 L150 180 L128 202 L106 180 Z"/></g>'


# Concept C2 — Strates : le Λ du concept C découpé en trois couches horizontales.
APEX, BASE, LEG = 26, 228, 34
IN_APEX = APEX + LEG / math.sin(math.atan(0.5))


def xo(y):
    return (y - APEX) * 0.5


def xi(y):
    return (y - IN_APEX) * 0.5


def band(y1, y2):
    """Tranche horizontale du Λ entre y1 et y2."""
    if y2 <= IN_APEX:
        return (f"M{f(128 - xo(y1))} {y1} L{f(128 + xo(y1))} {y1} L{f(128 + xo(y2))} {y2} "
                f"L{f(128 - xo(y2))} {y2} Z")
    if y1 >= IN_APEX:
        left = (f"M{f(128 - xo(y1))} {y1} L{f(128 - xi(y1))} {y1} L{f(128 - xi(y2))} {y2} "
                f"L{f(128 - xo(y2))} {y2} Z")
        right = (f"M{f(128 + xi(y1))} {y1} L{f(128 + xo(y1))} {y1} L{f(128 + xo(y2))} {y2} "
                 f"L{f(128 + xi(y2))} {y2} Z")
        return left + " " + right
    return (f"M{f(128 - xo(y1))} {y1} L{f(128 + xo(y1))} {y1} L{f(128 + xo(y2))} {y2} "
            f"L{f(128 + xi(y2))} {y2} L128 {f(IN_APEX)} L{f(128 - xi(y2))} {y2} L{f(128 - xo(y2))} {y2} Z")


strata = "".join(f'<path d="{band(a, b)}"/>' for a, b in [(26, 76), (90, 146), (160, 228)])
C2_alt = f'<g fill="{INK}">{strata}</g>'
C2_arg = f'<g fill="{INK}">{strata}<path d="M128 170 L146 188 L128 206 L110 188 Z"/></g>'

for k, alt, arg in [("d", D_alt, D_arg), ("e", E_alt, E_arg), ("c2", C2_alt, C2_arg)]:
    open(f"{OUT}/{k}-altrex.svg", "w").write(svg(alt))
    open(f"{OUT}/{k}-argora.svg", "w").write(svg(arg))
    open(f"{OUT}/{k}-altrex-lockup.svg", "w").write(lockup(alt, "ALTREX"))
    open(f"{OUT}/{k}-argora-lockup.svg", "w").write(lockup(arg, "ARGORA"))
print("ok")
