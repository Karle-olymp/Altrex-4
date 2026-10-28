"""Tour 7 : direction cybersécurité (défendre, repousser les attaques).
S1 déflecteur, S2 bouclier d'accolades, S3 bouclier pixel, S4 sentinelle (A en négatif).
Altrex : noyau rond / pixels carrés ; Argora : losanges."""
import math
import os
import sys

from gen_concepts import INK, f, lockup, svg

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
C = 128

# Silhouette de bouclier commune (haut légèrement bombé, pointe en bas).
SHIELD = ("M128 28 C156 40 186 44 214 42 L214 118 C214 172 178 208 128 230 "
          "C78 208 42 172 42 118 L42 42 C70 44 100 40 128 28 Z")


def pol(r, deg, cx=C, cy=C):
    a = math.radians(deg)
    return cx + r * math.cos(a), cy - r * math.sin(a)


def bit(x, y, half, argora):
    """Pixel (carré, Altrex) ou losange (Argora)."""
    if argora:
        return f'<path d="M{f(x)} {f(y - half * 1.3)} L{f(x + half * 1.3)} {f(y)} L{f(x)} {f(y + half * 1.3)} L{f(x - half * 1.3)} {f(y)} Z"/>'
    return f'<rect x="{f(x - half)}" y="{f(y - half)}" width="{f(2 * half)}" height="{f(2 * half)}" rx="{f(half * 0.2)}"/>'


def core(argora, r=20, cx=C, cy=C):
    if argora:
        return f'<path d="M{cx} {cy - r * 1.25} L{cx + r * 1.25} {cy} L{cx} {cy + r * 1.25} L{cx - r * 1.25} {cy} Z"/>'
    return f'<circle cx="{cx}" cy="{cy}" r="{r}"/>'


# S1 — Déflecteur : arc épais devant le noyau, fragments qui rebondissent.
def s1(argora):
    cx = 104
    ro, ri, a0, a1 = 82, 58, -68, 68
    p1, p2, p3, p4 = pol(ro, a1, cx), pol(ro, a0, cx), pol(ri, a0, cx), pol(ri, a1, cx)
    arc = (f'<path d="M{f(p1[0])} {f(p1[1])} A{ro} {ro} 0 0 1 {f(p2[0])} {f(p2[1])} '
           f'A12 12 0 0 1 {f(p3[0])} {f(p3[1])} A{ri} {ri} 0 0 0 {f(p4[0])} {f(p4[1])} '
           f'A12 12 0 0 1 {f(p1[0])} {f(p1[1])} Z"/>')
    frags = "".join(bit(x, y, h, argora) for x, y, h in
                    [(212, 128, 11), (222, 82, 8), (230, 170, 7), (198, 50, 5.5), (204, 206, 5)])
    return f'<g fill="{INK}">{arc}{core(argora, 24, cx, 128)}{frags}</g>'


# S2 — Bouclier d'accolades : deux accolades qui dessinent un bouclier, ouvert en haut.
def s2(argora):
    sw = 18
    left = ("M114 36 C92 44 72 46 52 46 L52 98 Q52 122 34 128 Q52 134 52 158 "
            "C56 190 88 212 128 228")
    right = ("M142 36 C164 44 184 46 204 46 L204 98 Q204 122 222 128 Q204 134 204 158 "
             "C200 190 168 212 128 228")
    paths = "".join(f'<path d="{d}" fill="none" stroke="{INK}" stroke-width="{sw}" '
                    f'stroke-linecap="round" stroke-linejoin="round"/>' for d in (left, right))
    return f'<g fill="{INK}">{paths}{core(argora, 22, 128, 124)}</g>'


# S3 — Bouclier pixel : bouclier plein, des pixels éjectés sur le bord droit (attaque repoussée).
def s3(argora):
    hole = core(argora, 0)
    bits = "".join(bit(x, y, h, argora) for x, y, h in
                   [(234, 70, 8), (240, 112, 6.5), (230, 152, 9), (214, 194, 6), (246, 186, 4.5)])
    notch = ("M214 60 L196 60 L196 78 L214 78 Z M214 132 L200 132 L200 146 L214 146 Z "
             "M206 172 L192 172 L192 186 L206 186 Z")
    body = f'<path fill-rule="evenodd" d="{SHIELD} {notch}"/>'
    inner = (f'<g fill="#fff">{core(argora, 22, 120, 120)}</g>')
    g = f'<g transform="translate(-10 0)"><g fill="{INK}">{body}{bits}</g>{inner}</g>'
    return g + hole


# S4 — Sentinelle : bouclier avec un A (Λ) en négatif et le noyau dessous.
def s4(argora):
    lam = "M128 70 L174 168 L152 168 L128 116 L104 168 L82 168 Z"
    body = f'<path fill-rule="evenodd" d="{SHIELD} {lam}"/>'
    inner = f'<g fill="#fff">{core(argora, 13, 128, 186)}</g>'
    return f'<g fill="{INK}">{body}</g>{inner}'


for k, fn in [("s1", s1), ("s2", s2), ("s3", s3), ("s4", s4)]:
    alt, arg = fn(False), fn(True)
    open(f"{OUT}/{k}-altrex.svg", "w").write(svg(alt))
    open(f"{OUT}/{k}-argora.svg", "w").write(svg(arg))
    open(f"{OUT}/{k}-altrex-lockup.svg", "w").write(lockup(alt, "ALTREX"))
    open(f"{OUT}/{k}-argora-lockup.svg", "w").write(lockup(arg, "ARGORA"))
print("ok")
