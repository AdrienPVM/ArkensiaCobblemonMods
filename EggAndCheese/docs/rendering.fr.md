[← EggAndCheese](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](rendering.md)

# Génération de textures et aperçus

Aucune texture d'œuf n'est livrée dans le jar. Les seules images de
`assets/eggandcheese/textures/` sont trois icônes d'item et une particule. Tout ce qu'un
joueur voit sur un collectible est calculé depuis son [ADN](dna.fr.md) à l'exécution, côté
client, la première fois que cet ADN apparaît à l'écran.

Deux implémentations indépendantes du même pipeline existent, et c'est le point le plus
important à retenir sur ce sous-système.

| Où | Fichier | Lignes | Sortie |
|---|---|---|---|
| En jeu, côté client | `src/client/java/com/roicheese/eggandcheese/texture/ProceduralTextureGenerator.java` | 1 245 | Trois `NativeImage` 64×64 — couleur, émissif image 0, émissif image 1 |
| Aperçu du dashboard | `CheeseDash/assets/egg-preview.js` | 1 420 | Textures canvas appliquées à un mesh voxel Three.js |

Le fichier JavaScript est un portage du fichier Java. Son en-tête le dit : *« Ports the
Java ProceduralTextureGenerator (13-step pipeline) + CollectibleModelRenderer voxels. »*
Ils sont maintenus en phase à la main. Modifie un motif en Java et l'aperçu du dashboard
mentira jusqu'à ce que tu le modifies aussi en JavaScript, et réciproquement.

⚠️ **L'aperçu 3D ne vit pas dans ce mod.** `egg-preview.js` est un asset de CheeseDash,
servi depuis le jar de CheeseDash. Si l'aperçu est cassé, cherche dans le dossier
`CheeseDash/`, pas ici. EggAndCheese ne fournit aucun fichier frontend.

## Le pipeline, dans l'ordre

`ProceduralTextureGenerator.generate(CollectibleDNA)` renvoie les trois images. L'image
de couleur est construite dans cet ordre, et c'est l'ordre qui fait que les couches se
lisent correctement :

1. **Remplissage de base** depuis la palette active — le primaire avec un dégradé
   vertical vers l'ombre, pour que l'œuf se lise déjà comme une forme ronde avant tout
   éclairage.
2. **Fond de rareté** — teinte supplémentaire pour les paliers élevés.
3. **Motif principal**, dessiné `rarity.getPatternLayers()` fois avec des paramètres
   perturbés. C'est de là que vient la densité d'un œuf `MYTHIC`.
4. **Motif secondaire**, mélangé en alpha à `patternBlend / 100`.
5. **Tampon**, s'il n'est pas `NONE`.
6. **Micro-texture**, une modulation de luminosité pixel par pixel.
7. **Relief** (`FLAG_EMBOSS`) — une passe de Sobel sur les bords du motif.
8. **Éclairage directionnel** (`FLAG_LIGHTING`).
9. **Tache spéculaire** (`FLAG_SPECULAR`) au quart de la largeur et au quart de la
   hauteur, avec une décroissance gaussienne de `SIZE * 0.18`.
10. **Vernis** (`FLAG_VARNISH`) à `varnishLevel / 100`.
11. **Halo de bord** (`FLAG_RIM_GLOW`).
12. **Effets de rareté** — bordure à partir d'`EPIC`.
13. **Cartes émissives**, générées séparément dans les deux images de frame.

La taille de texture est 64×64, fixe, dans `ProceduralTextureGenerator.SIZE`.

### Continuité de la texture

Chaque fonction de bruit a une variante sans raccord : `seamlessValueNoise`,
`seamlessTurbulence`, `seamlessVeinNoise`, et les fonctions de dessin existent en version
enroulée (`drawEllipseWrapped`, `drawLineWrapped`, `setPixelWrapped`). La texture fait le
tour de la circonférence de l'œuf : tout ce qui est dessiné près du bord gauche doit
reparaître à droite. Un motif ajouté sans passer par les fonctions enroulées laisse une
couture verticale visible sur le flanc de chaque œuf qui l'utilise.

### Deux images émissives

`generate` produit `emissF0` et `emissF1` à partir de deux graines de frame différentes.
Seuls les ADN `LEGENDARY` et `MYTHIC` alternent réellement entre les deux —
`RarityType.isAnimated()` en décide. Les deux images sont générées quelle que soit la
rareté, ce qui coûte un peu de travail inutile sur les œufs communs mais garde une forme
de cache uniforme.

## Les motifs émissifs

Les motifs de lueur réellement implémentés sont les suivants. Le générateur Java et
`egg-preview.js` traitent tous les deux les 15 valeurs non-`NONE` d'`EmissiveType`,
chacune dans sa propre branche :

| Valeur | Ce qui est dessiné |
|---|---|
| `SPOTS` | Points lumineux sur une grille de cellules perturbée. |
| `LINES` | Segments de ligne lumineux. |
| `RUNES` | Glyphes construits à partir de sept gabarits de traits — vertical, horizontal, croix, deux diagonales, un X et une forme fermée — choisis par cellule. |
| `STARS` | Éclats à plusieurs branches avec un cœur brillant. |
| `AURORA` | Une bande de lumière horizontale qui ondule. |
| `VEINS` | Bruit de veines seuillé en fissures lumineuses. |
| `SPARKS` | Petits points avec de courtes traînées. |
| `CRACKS` | Fines lignes de fracture, plus rares et plus nettes que `VEINS`. |
| `PULSE` | Anneaux de luminosité concentriques. |
| `CIRCUIT` | Pistes orthogonales avec des nœuds de jonction. |
| `RIPPLE` | Anneaux d'interférence depuis des centres décalés. |
| `FLAME` | Langues verticales pilotées par turbulence. |
| `NEBULA` | Nuages doux multi-octaves. |
| `MATRIX` | Colonnes verticales de cellules brillantes qui tombent. |
| `DIAMONDS` | Un treillis de contours en losange. |

`NONE` court-circuite tout : aucune image émissive n'est écrite et le renderer saute
entièrement la passe émissive.

La part de surface qui s'allume est `emissiveDensity / 100`. La couleur vient de
l'emplacement « lueur » de la palette, et c'est pourquoi la même `FLAME` se lit orange sur
`FIRE` et cyan sur `ICE`.

## Cache côté client

`CollectibleTextureCache` indexe chaque texture générée sur
`CollectibleDNA.cacheKey()` et l'enregistre auprès du gestionnaire de textures de
Minecraft sous un `Identifier` généré. Trois points d'entrée :

- `getColorTexture(dna)`
- `getEmissiveTexture(dna, frame)` et la surcharge à un argument pour la frame courante
- `invalidate(dna)` / `invalidateAll()`

La génération a lieu sur le thread de rendu la première fois qu'un œuf entre dans le
champ. Un joueur qui survole un champ de cinquante œufs distincts verra un bref
à-coup ; ensuite les textures restent en mémoire. Comme la clé est l'ADN et non l'id de
l'œuf, cinquante œufs au même ADN coûtent une seule texture.

## Distance de rendu et niveau de détail

`EggRenderConfig.textureRenderDistance` est la distance en blocs au-delà de laquelle le
modèle voxel complet est remplacé par une simple boîte. `0` signifie « ne jamais
dégrader ».

La valeur arrive du serveur dans `SyncRegistryPayload`, envoyé par
`ModPackets.sendRegistrySync`. Un `renderDistanceOverride` par œuf sur `CollectibleEntry`
a la priorité quand il est `>= 0` ; `-1` signifie « utiliser la valeur globale ».

⚠️ Trois valeurs par défaut différentes pour le même réglage coexistent dans le code, et
elles ne s'accordent pas :

- `EggRegistryData.textureRenderDistance` démarre à `20` — c'est la vérité du serveur.
- `EggRenderConfig.textureRenderDistance` démarre à `20`, avec un commentaire Javadoc qui
  annonce encore `Default = 10`.
- `ModPackets.readRegistryFromBufFull` retombe sur `10` quand le paquet est trop court
  pour contenir le champ.

Plus important : `sendRegistrySync` n'est appelé que depuis
`PUT /api/v1/eggs/config`. Un client qui se connecte et ne voit jamais de changement de
configuration garde son `20` compilé en dur au lieu de la valeur configurée côté serveur.
Enregistre la configuration une fois depuis le dashboard après un redémarrage si tu
dépends d'une distance non standard. C'est un vrai manque, pas une subtilité — voir
[architecture](architecture.fr.md#faiblesses-connues).

## Le modèle voxel

L'œuf n'est pas une sphère. C'est un ensemble figé de 472 voxels de surface, et la même
donnée existe deux fois :

- en jeu, construite par `CollectibleModelRenderer` (source set client),
- dans le dashboard, sous forme du tableau `EGG_VOXELS` en tête d'`egg-preview.js`,
  encodé en sept entiers par voxel : `[x1*16, y1*16, z1*16, x2*16, y2*16, z2*16,
  faceMask]`.

Seules les faces nommées dans `faceMask` sont émises : les faces internes ne coûtent
rien. Le matériau Three.js est construit avec `side: THREE.FrontSide`, parce que les
normales générées pointent vers l'extérieur et que le double côté ramenait du Z-fighting
entre faces de voxels coplanaires. Les faces du dessus et du dessous sont en plus
légèrement décalées le long de leur normale, pour la même raison.

La scène d'aperçu est volontairement sobre : une caméra en perspective à 35°, une lumière
ambiante à 0.5, une lumière directionnelle à 0.8, `OrbitControls` quand CheeseDash l'a
chargé. Le fond est codé en dur à `0x0c0e14`, la couleur de fond de page du dashboard.

## Renderers d'item et de bloc

Trois renderers de block entity et un renderer d'item sont enregistrés dans
`EggAndCheeseClient` :

| Renderer | Cible |
|---|---|
| `AdminCollectibleBESR` | le bloc collectible admin — l'œuf caché |
| `CollectibleDecoBESR` | le bloc œuf décoratif qu'un joueur pose |
| `PedestalBESR` | les stèles, y compris ce qu'elles exposent |
| `EggItemRenderer` | `admin_egg` et `collectible_egg` en main et en interface |

`EggItemRenderer` passe par `BuiltinItemRendererRegistry` : c'est ce qui fait qu'un œuf
dans la barre d'action affiche le vrai modèle 3D avec sa vraie texture, et non une icône
plate.

`AdminCollectibleBlock.getRenderType` renvoie `ENTITYBLOCK_ANIMATED`, et son ticker est
enregistré **uniquement côté client** — `getTicker` renvoie `null` sur le serveur. Ce
ticker ne fait rien d'autre qu'avancer `yRotation` de 0,5° par tick, ce qui donne la
rotation lente visible sur un œuf posé. Aucun tick serveur n'y est consacré.

---

← [L'ADN d'un collectible](dna.fr.md) · [Placement et découvertes](placement-and-discovery.fr.md) →
