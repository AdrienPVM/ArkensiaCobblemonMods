[← EggAndCheese](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](rendering.fr.md)

# Texture generation and previews

No egg texture ships in the jar. The only images in `assets/eggandcheese/textures/` are
three item icons and one particle. Everything a player sees on a collectible is computed
from its [DNA](dna.md) at runtime, on the client, the first time that DNA appears on
screen.

Two independent implementations of the same pipeline exist, and that is the single most
important thing to know about this subsystem.

| Where | File | Lines | Output |
|---|---|---|---|
| In game, client side | `src/client/java/com/roicheese/eggandcheese/texture/ProceduralTextureGenerator.java` | 1 245 | Three 64×64 `NativeImage`s — colour, emissive frame 0, emissive frame 1 |
| Dashboard preview | `CheeseDash/assets/egg-preview.js` | 1 420 | Canvas textures fed to a Three.js voxel mesh |

The JavaScript file is a port of the Java one. Its header says so: *"Ports the Java
ProceduralTextureGenerator (13-step pipeline) + CollectibleModelRenderer voxels."* They
are kept in step by hand. Change a pattern in Java and the dashboard preview will lie
until you change it in JavaScript too, and the other way round.

⚠️ **The 3D preview does not live in this mod.** `egg-preview.js` is a CheeseDash asset,
served from CheeseDash's jar. If the preview is broken, look in the `CheeseDash/`
directory, not here. EggAndCheese contributes no frontend file at all.

## The pipeline, in order

`ProceduralTextureGenerator.generate(CollectibleDNA)` returns the three images. The
colour image is built in this order, and the order is what makes the layers read
correctly:

1. **Base fill** from the active palette — primary with a vertical gradient toward
   shadow, so the egg reads as rounded before any lighting is applied.
2. **Rarity background** — extra tinting for the higher tiers.
3. **Primary pattern**, drawn `rarity.getPatternLayers()` times with jittered
   parameters. This is where a `MYTHIC` egg gets its density.
4. **Secondary pattern**, alpha-blended at `patternBlend / 100`.
5. **Stamp**, if not `NONE`.
6. **Micro-texture**, a per-pixel brightness modulation.
7. **Emboss** (`FLAG_EMBOSS`) — a Sobel pass over the pattern edges.
8. **Directional lighting** (`FLAG_LIGHTING`).
9. **Specular blob** (`FLAG_SPECULAR`) at one quarter width, one quarter height, with a
   Gaussian falloff of `SIZE * 0.18`.
10. **Varnish** (`FLAG_VARNISH`) at `varnishLevel / 100`.
11. **Rim glow** (`FLAG_RIM_GLOW`).
12. **Rarity effects** — border for `EPIC` and above.
13. **Emissive maps**, generated separately into the two frame images.

Texture size is 64×64, fixed, in `ProceduralTextureGenerator.SIZE`.

### Seamlessness

Every noise helper has a seamless variant: `seamlessValueNoise`,
`seamlessTurbulence`, `seamlessVeinNoise`, and drawing helpers come in wrapped flavours
(`drawEllipseWrapped`, `drawLineWrapped`, `setPixelWrapped`). The texture wraps around
the egg's circumference, so anything drawn near the left edge has to reappear on the
right. A pattern added without using the wrapped helpers shows a visible vertical seam
down one side of every egg using it.

### Two emissive frames

`generate` produces `emissF0` and `emissF1` from two different frame seeds. Only
`LEGENDARY` and `MYTHIC` DNA actually alternate between them — `RarityType.isAnimated()`
decides. Both frames are generated regardless of rarity, which costs a little work for
nothing on common eggs but keeps the cache shape uniform.

## Emissive patterns

These are the glow patterns actually implemented. The Java generator and
`egg-preview.js` both handle all 15 non-`NONE` values of `EmissiveType`, each as its own
branch:

| Value | What it draws |
|---|---|
| `SPOTS` | Glowing dots on a jittered cell grid. |
| `LINES` | Bright line segments. |
| `RUNES` | Glyphs built from seven stroke templates — vertical, horizontal, cross, two diagonals, an X, and a box form — chosen per cell. |
| `STARS` | Multi-pointed bursts with a bright core. |
| `AURORA` | A flowing horizontal band of light. |
| `VEINS` | Vein noise thresholded into glowing cracks. |
| `SPARKS` | Small points with short trailing streaks. |
| `CRACKS` | Thin fracture lines, sparser and sharper than `VEINS`. |
| `PULSE` | Concentric brightness rings. |
| `CIRCUIT` | Orthogonal traces with junction nodes. |
| `RIPPLE` | Interference rings from offset centres. |
| `FLAME` | Turbulence-driven tongues rising vertically. |
| `NEBULA` | Soft multi-octave clouds. |
| `MATRIX` | Vertical falling columns of bright cells. |
| `DIAMONDS` | A lattice of diamond outlines. |

`NONE` short-circuits: no emissive image is written, and the renderer skips the emissive
pass entirely.

How much of the surface lights up is `emissiveDensity / 100`. The colour comes from the
palette's glow slot, which is why the same `FLAME` reads orange on `FIRE` and cyan on
`ICE`.

## Client-side caching

`CollectibleTextureCache` keys every generated texture on `CollectibleDNA.cacheKey()`
and registers it with Minecraft's texture manager under a generated `Identifier`. Three
entry points:

- `getColorTexture(dna)`
- `getEmissiveTexture(dna, frame)` and the one-argument overload for the current frame
- `invalidate(dna)` / `invalidateAll()`

Generation happens on the render thread the first time an egg comes into view. A player
flying into a field of fifty distinct eggs will see a brief hitch; after that the
textures are resident. Because the key is the DNA and not the egg id, fifty eggs sharing
one DNA cost one texture.

## Render distance and level of detail

`EggRenderConfig.textureRenderDistance` is the distance in blocks beyond which the full
voxel model is replaced by a simple box. `0` means never degrade.

The value arrives from the server in `SyncRegistryPayload`, sent by
`ModPackets.sendRegistrySync`. A per-egg `renderDistanceOverride` on
`CollectibleEntry` takes precedence when it is `>= 0`; `-1` means "use the global
value".

⚠️ Three different defaults for the same setting exist in the code, and they do not
agree:

- `EggRegistryData.textureRenderDistance` starts at `20` — this is the server's truth.
- `EggRenderConfig.textureRenderDistance` starts at `20`, with a Javadoc comment that
  still claims `Default = 10`.
- `ModPackets.readRegistryFromBufFull` falls back to `10` when the packet is too short
  to contain the field.

More importantly, `sendRegistrySync` is only called from `PUT /api/v1/eggs/config`. A
client that joins and never sees a config change keeps its compiled-in `20` rather than
the server's configured value. Save the config once from the dashboard after a restart
if you rely on a non-default distance. This is a real gap, not a subtlety — see
[architecture](architecture.md#known-weaknesses).

## The voxel model

The egg is not a sphere. It is a fixed set of 472 surface voxels, and the same data
exists twice:

- in game, built by `CollectibleModelRenderer` (client source set),
- in the dashboard, as the `EGG_VOXELS` array at the top of `egg-preview.js`, packed as
  seven integers per voxel: `[x1*16, y1*16, z1*16, x2*16, y2*16, z2*16, faceMask]`.

Only the faces named in `faceMask` are emitted, so interior faces cost nothing. The
Three.js material is built with `side: THREE.FrontSide`, because the generated normals
face outward and double-siding reintroduced Z-fighting between coplanar voxel faces.
Top and bottom faces are additionally nudged along their normal for the same reason.

The preview scene is deliberately plain: a perspective camera at 35°, ambient light at
0.5, one directional light at 0.8, `OrbitControls` when CheeseDash has loaded it. The
background is hard-coded to `0x0c0e14`, the dashboard's own page background.

## Item and block renderers

Three block entity renderers and one item renderer are registered in
`EggAndCheeseClient`:

| Renderer | Target |
|---|---|
| `AdminCollectibleBESR` | the admin collectible block — the hidden egg |
| `CollectibleDecoBESR` | the decorative egg block a player places |
| `PedestalBESR` | pedestals, including whatever they display |
| `EggItemRenderer` | `admin_egg` and `collectible_egg` as held and GUI items |

`EggItemRenderer` is wired through `BuiltinItemRendererRegistry`, which is what makes an
egg in a hotbar show the real 3D model with its real texture rather than a flat icon.

`AdminCollectibleBlock.getRenderType` returns `ENTITYBLOCK_ANIMATED`, and its ticker is
registered **client side only** — `getTicker` returns `null` on the server. The ticker
does nothing but advance `yRotation` by 0.5° per tick, which is the slow spin you see on
a placed egg. No server tick is spent on it.

---

← [Collectible DNA](dna.md) · [Placement and discovery](placement-and-discovery.md) →
