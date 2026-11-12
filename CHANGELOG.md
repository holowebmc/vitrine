# Changelog

## 0.2.0

- Screen pages blur the game behind them with `backdrop-filter: blur()` (Minecraft 26.2+).
- Item icons as images: `<img src="vitrine://item/minecraft:chest">` (26.2+).
- Game-drawn slots: an element with `--vitrine-bind` shows what `Vitrine.bind` draws there. `vitrine:item`, `vitrine:entity` and `vitrine:player` are built in (26.2+).
- World panels raise elements as 3D solids with `--vitrine-depth`.
- Page audio always goes through the game's volume.
- Releases are now built and uploaded by GitHub Actions.
