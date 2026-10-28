"""Génère les concepts de logo Altrex / Argora (symboles, variantes Argora, lockups)."""
import math
import os
import sys

SLOPE = 0.5  # pente 1:2 (26,57° de la verticale)
SIN = math.sin(math.atan(SLOPE))
T30 = SLOPE
INK = "#111111"


def svg(body, w=256, h=256):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}">\n'
            f'{body}\n</svg>\n')


def f(x):
    return f"{x:.1f}".rstrip("0").rstrip(".")


# --- Λ commun : jambes à 30° de la verticale --------------------------------------------
def chevron(apex_y, base_y, leg, cx=128):
    """Λ plein : contour extérieur + creux intérieur parallèle (épaisseur perpendiculaire `leg`)."""
    dx = (base_y - apex_y) * T30
    inner_apex = apex_y + leg / SIN
    idx = (base_y - inner_apex) * T30
    return (f"M{f(cx)} {f(apex_y)} L{f(cx + dx)} {f(base_y)} L{f(cx + idx)} {f(base_y)} "
            f"L{f(cx)} {f(inner_apex)} L{f(cx - idx)} {f(base_y)} L{f(cx - dx)} {f(base_y)} Z"), inner_apex


# Concept A — Noyau : Λ + point (Altrex) / Λ + losange (Argora)
roof, inner = chevron(26, 228, 34)
dot = '<circle cx="128" cy="176" r="19"/>'
diamond = '<path d="M128 152 L152 176 L128 200 L104 176 Z"/>'
A_alt = f'<g fill="{INK}"><path d="{roof}"/>{dot}</g>'
A_arg = f'<g fill="{INK}"><path d="{roof}"/>{diamond}</g>'

# Concept B — Comète : Λ en trait arrondi + barre = comète du loader (tête ronde Altrex / losange Argora)
lw = 32
B_leg = f'<path d="M58 226 L128 34 L198 226" fill="none" stroke="{INK}" stroke-width="{lw}" stroke-linecap="round" stroke-linejoin="round"/>'
B_alt = f'<g fill="{INK}">{B_leg}<path d="M100 182 L134 167 A15 15 0 1 1 134 197 Z"/></g>'
B_arg = f'<g fill="{INK}">{B_leg}<path d="M100 182 L133.5 175.5 L140 169 L153 182 L140 195 L133.5 188.5 Z"/></g>'

# Concept C — Strates : deux Λ emboîtés (Altrex) / Λ qui abrite un triangle plein (Argora)
outer, oin = chevron(26, 228, 30)
gap = 22
in_apex = oin + gap / SIN
inner_c, _ = chevron(in_apex, 228, 26)
tri_apex = in_apex
tdx = (228 - tri_apex) * T30
tri = f"M128 {f(tri_apex)} L{f(128 + tdx)} 228 L{f(128 - tdx)} 228 Z"
C_alt = f'<g fill="{INK}"><path d="{outer}"/><path d="{inner_c}"/></g>'
C_arg = f'<g fill="{INK}"><path d="{outer}"/><path d="{tri}"/></g>'

# --- Wordmark géométrique (exploration : traits, à vectoriser au stade final) ----------
CAP, SW = 60, 11
GLYPHS = {
    "A": (50, "M0 60 L25 0 L50 60 M9.6 40 L40.4 40"),
    "L": (38, "M5.5 0 V54.5 H38"),
    "T": (48, "M0 5.5 H48 M24 5.5 V60"),
    "R": (44, "M5.5 60 V5.5 H25 A13.5 13.5 0 0 1 25 32.5 H5.5 M22 32.5 L44 60"),
    "E": (40, "M40 5.5 H5.5 V54.5 H40 M5.5 30 H34"),
    "X": (50, "M2 0 L48 60 M48 0 L2 60"),
    "G": (58, "M52.5 15 A27 27 0 1 0 55 33 H32"),
    "O": (60, "M30 3 A27 27 0 1 1 29.99 3 Z"),
}


def word(text, x0, y0, scale, track):
    parts, x = [], x0
    for ch in text:
        w, d = GLYPHS[ch]
        parts.append(f'<path transform="translate({f(x)} {f(y0)}) scale({scale})" d="{d}"/>')
        x += (w + track) * scale
    width = x - track * scale - x0
    body = (f'<g fill="none" stroke="{INK}" stroke-width="{SW}" stroke-linejoin="miter" '
            f'stroke-linecap="butt">' + "".join(parts) + "</g>")
    return body, width


def lockup(symbol_body, text):
    scale = 1.25
    cap = CAP * scale
    wm, width = word(text, 300, 128 - cap / 2 + 6, scale, 22)
    total = 300 + width + 8
    return svg(f'<g transform="translate(0 6)">{symbol_body}</g>{wm}', w=round(total))


if __name__ == "__main__":
    OUT = sys.argv[1]
    os.makedirs(OUT, exist_ok=True)
    for key, alt, arg in [("a", A_alt, A_arg), ("b", B_alt, B_arg), ("c", C_alt, C_arg)]:
        open(f"{OUT}/{key}-altrex.svg", "w").write(svg(alt))
        open(f"{OUT}/{key}-argora.svg", "w").write(svg(arg))
        open(f"{OUT}/{key}-altrex-lockup.svg", "w").write(lockup(alt, "ALTREX"))
        open(f"{OUT}/{key}-argora-lockup.svg", "w").write(lockup(arg, "ARGORA"))
    print("ok", sorted(os.listdir(OUT)))
