"""Holoweb pixel logo: a three-colour swirl orb on a dark tile, drawn on a 32x32 grid.

python branding/logo.py   -> writes the Vitrine icons (mod jar + Modrinth). Needs Pillow.
Other Holoweb mods reuse mark() with their own twist/palette; see branding/README.md.
"""
import math
from pathlib import Path
from PIL import Image

N = 32
INK = (10, 7, 22)        # outlines, ring gap
BG = (22, 17, 44)        # tile
BG_HI = (42, 34, 82)     # tile top/left bevel
CORE = (244, 241, 255)
HOLO = [  # (highlight, base, shadow): cyan, violet, magenta
    ((140, 255, 240), (54, 226, 214), (22, 150, 160)),
    ((196, 170, 255), (138, 92, 255), (84, 50, 190)),
    ((255, 150, 214), (255, 79, 184), (190, 40, 130)),
]


def orb(px, cx, cy, R, r_core, r_gap, twist, seg):
    for y in range(N):
        for x in range(N):
            dx, dy = x + .5 - cx, y + .5 - cy
            d = math.hypot(dx, dy)
            if d > R:
                continue
            if d <= r_core:
                px[x, y] = (255, 255, 255) if dx + dy < -r_core * .6 else CORE
            elif d <= r_gap:
                px[x, y] = INK
            else:
                a = (math.degrees(math.atan2(dy, dx)) + 90 + twist * (d - r_gap) / (R - r_gap)) % 360
                s = seg[int(a // 120)]
                light = -(dx + dy) / d                       # lit from top-left, shaded on the rim only
                rim = d > R - 1.4
                px[x, y] = s[0] if rim and light > .5 else s[2] if rim and light < -.5 else s[1]


def outline(px):
    solid = {(x, y) for y in range(N) for x in range(N) if px[x, y][:3] != BG}
    for y in range(N):
        for x in range(N):
            if (x, y) not in solid and any((x + i, y + j) in solid for i, j in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                px[x, y] = INK


def tile(px):
    for y in range(N):
        for x in range(N):
            if min(x, N - 1 - x) + min(y, N - 1 - y) < 2:
                continue                                     # cut corners
            edge = x in (0, N - 1) or y in (0, N - 1)
            px[x, y] = INK if edge else BG_HI if 1 in (x, y) else BG


def cube(px, top=HOLO[0], left=HOLO[1], right=HOLO[2]):
    """Pixel-exact 2:1 isometric block, faces 10 columns wide, with a white H on the right face."""
    for i in range(10):
        xl, xr = 6 + i, 25 - i
        for y in range(10 - i // 2, 10 + i // 2 + 1):
            px[xl, y] = px[xr, y] = top[0] if y == 10 - i // 2 else top[1]
        for x, s, u in ((xl, left, i), (xr, right, 9 - i)):        # u: column on the face, left to right
            for v in range(12):
                c = s[2] if v == 11 or (s is right and u == 9) else s[0] if s is left and u == 0 else s[1]
                if s is right and (u in (2, 3, 6, 7) and 2 <= v <= 9 or u in (4, 5) and v in (5, 6)):
                    c = CORE
                px[x, 11 + i // 2 + v] = c


def compose(draw, bg=True):
    """Draws on a scratch layer, outlines it in ink and lays it on the tile."""
    im = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    if bg:
        tile(im.load())
    sub = Image.new("RGBA", (N, N), BG)
    sp = sub.load()
    draw(sp)
    outline(sp)
    px = im.load()
    for y in range(N):
        for x in range(N):
            if sp[x, y][:3] != BG:
                px[x, y] = sp[x, y]
    return im


def mark(twist=110, seg=HOLO, bg=True):
    """Vitrine: the swirl orb."""
    R, core, gap = (10.3, 3.3, 4.8) if bg else (14.3, 4.6, 6.4)
    return compose(lambda px: orb(px, 16, 16, R, core, gap, twist, seg), bg)


def holoweb(bg=True):
    """The organisation: the holo block with the H."""
    return compose(cube, bg)


def up(im, s):
    return im.resize((N * s, N * s), Image.NEAREST)


if __name__ == "__main__":
    root = Path(__file__).resolve().parent.parent
    icon = mark()
    up(icon, 4).save(root / "src/main/resources/assets/vitrine/icon.png")   # 128px, in the jar
    up(icon, 16).save(root / "branding/vitrine-512.png")                    # Modrinth project icon
    up(mark(bg=False), 16).save(root / "branding/vitrine-orb-512.png")       # loose orb for banners/README
    up(holoweb(), 16).save(root / "branding/holoweb-512.png")                # organisation avatar (Modrinth, GitHub)
    up(holoweb(bg=False), 16).save(root / "branding/holoweb-block-512.png")  # loose block
