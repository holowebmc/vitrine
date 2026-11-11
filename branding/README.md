# Holoweb visual identity

Every Holoweb icon is drawn by `logo.py` on the same 32×32 pixel grid, upscaled with nearest-neighbour only (never smoothed). Each icon puts one symbol on the shared tile.

## The system

- **Tile:** a dark square `#16112C` with a 1 px outline `#0A0716`, a top/left bevel `#2A2250` and the two corner pixels cut.
- **Holo palette:** each colour is listed as highlight / base / shadow.

  | colour | highlight | base | shadow |
  |---|---|---|---|
  | cyan | `#8CFFF0` | `#36E2D6` | `#1696A0` |
  | violet | `#C4AAFF` | `#8A5CFF` | `#5432BE` |
  | magenta | `#FF96D6` | `#FF4FB8` | `#BE2882` |

- **Accent:** a single white element `#F4F1FF` is the focal point (the orb's core, the H on the block).
- **Shading:** lit from the top-left. Shades only go on the edges; faces and segments keep their flat base colour.
- **Outline:** a 1 px ink `#0A0716` outline around the symbol.
- **Padding:** the symbol takes about 2/3 of the tile (from x 6 to 25). No text goes on an icon.

## Marks

| mark | symbol | function |
|---|---|---|
| Holoweb | holo block: a pixel-exact 2:1 isometric cube with a cyan top, a violet left face and a magenta right face, and a white H on the right face | `holoweb()` |
| Vitrine | swirl orb: a three-colour ring with a 110° twist around a white core | `mark()` |

## Files

| file | use |
|---|---|
| `branding/holoweb-512.png` | organisation avatar (Modrinth, GitHub) |
| `branding/holoweb-block-512.png` | the bare block (transparent background) |
| `src/main/resources/assets/vitrine/icon.png` | Vitrine's 128 px icon in the jar (`fabric.mod.json`) |
| `branding/vitrine-512.png` | Vitrine's Modrinth project icon |
| `branding/vitrine-orb-512.png` | the bare orb (transparent background) for banners and READMEs |

## New mods

Copy `logo.py`, draw the mod's own symbol in a function that writes pixels, and pass it to `compose(draw)`. Use the holo palette and a white accent, keep to the grid, and keep the padding. `compose` adds the tile and the outline.
