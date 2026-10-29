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

1. **"Rarity" background** — seamless noise between the palette's primary and shadow.
   Despite the name, rarity is not read: the step uses only the seed and the palette.
2. **Base gradient** — primary toward shadow along the vertical axis, plus noise, so the
   egg reads as rounded before any lighting is applied.
3. **Primary pattern**, drawn once.
4. **Secondary pattern**, alpha-blended at `patternBlend / 100 × 0.85`, skipped at `0`.
5. **Emboss** (`FLAG_EMBOSS`) — a Sobel pass over the pattern edges.
6. **Shimmer and sparkles** — noise highlights, a pulsing band near the top and bottom
   edges, and 6 to 13 sparkle pixels. Every egg gets this step, whatever its rarity.
7. **Stamp**, if not `NONE`.
8. **Micro-texture**, a per-pixel brightness modulation, if not `NONE`.
9. **Directional lighting** (`FLAG_LIGHTING`).
10. **Specular blob** (`FLAG_SPECULAR`) at one quarter width, one quarter height, with a
    Gaussian falloff of `SIZE * 0.18`.
11. **Rim glow** (`FLAG_RIM_GLOW`) — a glow-coloured band at the top and bottom rims.
12. **Varnish** (`FLAG_VARNISH`) at `varnishLevel / 100 × 0.35`.
13. **Highlight vignette** — a vertical gradient toward the highlight colour, always on.

The two emissive maps are generated separately, after the colour image.

Texture size is 64×64, fixed, in `ProceduralTextureGenerator.SIZE`.

### Seamlessness

Every noise helper has a seamless variant: `seamlessValueNoise`,
`seamlessTurbulence`, `seamlessVeinNoise`, and drawing helpers come in wrapped flavours
(`drawEllipseWrapped`, `drawLineWrapped`, `setPixelWrapped`). The texture wraps around
the egg's circumference, so anything drawn near the left edge has to reappear on the
right. A pattern added without using the wrapped helpers shows a visible vertical seam
down one side of every egg using it.

### Two emissive frames

`generate` produces `emissF0` and `emissF1` from two different frame seeds, and both are
registered as textures. Only frame 0 is ever drawn: every renderer calls the one-argument
`getEmissiveTexture(dna)`, which always returns frame 0, and `RarityType.isAnimated()` is
never read. Frame 1 is generated and uploaded for nothing, for every egg, whatever its
rarity — no glow alternates frames today.

## Emissive patterns

These are the glow patterns actually implemented. The Java generator and
`egg-preview.js` both handle all 15 non-`NONE` values of `EmissiveType`, each as its own
branch:

| Value | What it draws |
|---|---|
| `SPOTS` | Glowing dots on a jittered cell grid. |
| `LINES` | Four wavy vertical lines around the circumference, bent by noise. |
| `RUNES` | Glyphs built from seven stroke templates — vertical, horizontal, cross, two diagonals, an X, and a ring — chosen per cell. |
| `STARS` | Round points with a bright core and a soft radial halo — no rays. |
| `AURORA` | A flowing horizontal band of light. |
| `VEINS` | Vein noise thresholded into glowing cracks. |
| `SPARKS` | Small, sharp points with a short falloff — smaller and sparser than `STARS`, no trails. |
| `CRACKS` | Thin fracture lines, sparser and sharper than `VEINS`. |
| `PULSE` | Contour lines of a smooth noise field — wavy bands, not concentric rings. |
| `CIRCUIT` | Orthogonal traces with junction nodes. |
| `RIPPLE` | Interference rings from offset centres. |
| `FLAME` | Turbulence-driven tongues rising vertically. |
| `NEBULA` | Soft multi-octave clouds. |
| `MATRIX` | Vertical falling columns of bright cells. |
| `DIAMONDS` | A lattice of diamond outlines. |

`NONE` short-circuits: the emissive images are left fully transparent, and the renderer
skips the emissive pass entirely. A density of `0` does the same.

How much of the surface lights up is `emissiveDensity / 100`. The colour comes from the
palette's glow slot, brightened, which is why the same `FLAME` reads yellow on `FIRE` and
cyan on `ICE`.

## Client-side caching

`CollectibleTextureCache` keys every generated texture on `CollectibleDNA.cacheKey()`
and registers it with Minecraft's texture manager under a generated `Identifier`. Three
entry points:

- `getColorTexture(dna)`
- `getEmissiveTexture(dna, frame)` and the one-argument overload, which always means frame 0
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
value". Only the hidden admin egg honours the override: decorative eggs and eggs on
pedestals always use the global value.

⚠️ Three different defaults for the same setting exist in the code, and they do not
agree:

- `EggRegistryData.textureRenderDistance` starts at `20` — this is the server's truth.
- `EggRenderConfig.textureRenderDistance` starts at `20`, with a Javadoc comment that
  still claims `Default = 10`.
- `ModPackets.readRegistryFromBufFull` falls back to `10` when the packet is too short
  to contain the field.

More importantly, `sendRegistrySync` is only called from `PUT /api/v1/eggs/config`. A
client that joins and never sees a config change keeps its compiled-in `20` rather than
the server's configured value. Only the clients online at the moment of a config save
receive the value; anyone who joins later — or reconnects — is back to `20` until the next
save, so no amount of saving makes a non-default distance stick. This is a real gap, not a subtlety — see
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
