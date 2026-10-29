[← EggAndCheese](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](dna.md)

# L'ADN d'un collectible

Chaque collectible d'EggAndCheese possède un seul état qui décide de son apparence : un
`CollectibleDNA`. C'est un `record` Java — quatorze composantes, aucun setter, aucun
champ mutable. Deux collectibles au même ADN sont visuellement identiques sur tous les
clients, parce que la texture est calculée depuis l'ADN au lieu d'être stockée comme
image.

Ce choix a une conséquence qu'il faut assimiler avant de toucher à l'API : on ne modifie
jamais un ADN. On en construit un nouveau et on remplace l'ancienne référence.

## Les quatorze composantes

Déclarées dans `src/main/java/com/roicheese/eggandcheese/dna/CollectibleDNA.java` :

| Composante | Type | Plage / valeurs | Effet sur l'œuf |
|---|---|---|---|
| `seed` | `long` | n'importe quelle valeur 64 bits | Pilote chaque décision aléatoire de la génération de texture. Même graine et mêmes autres champs donnent une texture identique au bit près. |
| `palette` | `PaletteType` | 34 palettes nommées | Fournit les six couleurs avec lesquelles toute la texture est dessinée. |
| `pattern` | `PatternType` | 26 motifs nommés | Le motif principal. |
| `patternScale` | `int` | 1–4 (borné) | Taille des détails du motif principal. 1 donne du fin et chargé, 4 du grossier et massif. |
| `secondaryPattern` | `PatternType` | 26 motifs nommés | Un second motif dessiné sous ou sur le premier. |
| `patternBlend` | `int` | 0–100 (borné) | Part du motif secondaire qui ressort. 0 le masque complètement. |
| `emissive` | `EmissiveType` | 16 valeurs, `NONE` incluse | La couche lumineuse. `NONE` signifie qu'aucune texture émissive n'est générée. |
| `emissiveDensity` | `int` | 0–100 (borné) | Part de la surface couverte par la lueur. 0 supprime la lueur même si `emissive` est renseigné. |
| `rarity` | `RarityType` | 6 paliers | Couleur du libellé, nombre de couches de motif, bordure, et animation ou non de la lueur. |
| `stamp` | `StampType` | 10 valeurs, `NONE` incluse | Une icône reconnaissable (étoile, lune, couronne…) dessinée en troisième couche. |
| `microTexture` | `MicroTextureType` | 8 valeurs, `NONE` incluse | Grain de surface haute fréquence posé par-dessus tout le reste. |
| `visualFlags` | `int` | masque de 5 bits | Cinq effets de post-traitement indépendants — voir plus bas. |
| `varnishLevel` | `int` | 0–100 (borné) | Intensité du vernis. Lu uniquement si le flag de vernis est actif. |
| `customPalette` | `String` | `""` ou six valeurs `RRGGBB` séparées par des virgules | Remplace `palette` quand la chaîne n'est pas vide. |

### Les palettes

Un `PaletteType`, ce sont six couleurs ARGB dans un ordre fixe : primaire, secondaire,
rehaut, ombre, accent, lueur. Chaque générateur de motif les désigne par leur rôle, et
c'est pour cette raison que changer de palette rhabille un œuf sans toucher à sa forme.

Les 34 valeurs : `WARM`, `COOL`, `NATURE`, `DARK`, `PASTEL`, `FIRE`, `ICE`, `MYSTIC`,
`GOLD`, `CANDY`, `NEBULA`, `LAVA`, `FOREST`, `ARCTIC`, `CORRUPTED`, `CELESTIAL`,
`NEON`, `RAINBOW`, `OCEAN`, `SUNSET`, `AUTUMN`, `SPRING`, `CYBERPUNK`, `STEAMPUNK`,
`DESERT`, `VOLCANIC`, `AURORA`, `AMETHYST`, `JADE`, `RUST`, `BLUEBERRY`, `PEACH`,
`TOXIC`, `CUSTOM`.

`CUSTOM` porte les mêmes couleurs que `WARM`. Elle existe comme marqueur pour les
sélecteurs de couleur du dashboard ; ce qui remplace réellement les couleurs, c'est une
chaîne `customPalette` non vide, et cela fonctionne avec n'importe quelle valeur de
palette. `getActiveColors()` analyse la chaîne, exige exactement six valeurs
hexadécimales séparées par des virgules, force l'alpha à `FF`, et retombe silencieusement
sur les couleurs de l'enum quand la chaîne est mal formée. Une faute de frappe dans une
couleur se manifeste donc par « mes couleurs ont été ignorées », jamais par une erreur.

### Les motifs

26 valeurs : `SPOTS`, `STRIPES`, `MARBLE`, `GEOMETRIC`, `VEINS`, `CONSTELLATION`,
`CRYSTALS`, `BUBBLES`, `WAVES`, `MOSAIC`, `SCALES`, `CRACKS`, `GALAXY`, `RUNES`,
`SPLATTER`, `CIRCUITS`, `TIEDYE`, `GRADIENT`, `HONEYCOMB`, `FEATHERS`, `FLORA`,
`ZIGZAG`, `CROSSHATCH`, `SMOKE`, `TRIBAL`, `FRACTAL`.

`pattern` et `secondaryPattern` puisent dans le même enum : rien n'interdit de mettre
`SPOTS` aux deux. Le résultat est un semis de taches plus dense, pas une erreur.

### La rareté

`RarityType` est le seul enum qui change autre chose qu'une couleur. Chaque palier porte
quatre propriétés supplémentaires, lues directement dans les arguments du constructeur :

| Rareté | Couleur du libellé | Couches de motif | Bordure | Lueur animée |
|---|---|---|---|---|
| `COMMON` | `#AAAAAA` | 1 | non | non |
| `UNCOMMON` | `#55FF55` | 1 | non | non |
| `RARE` | `#5599FF` | 2 | non | non |
| `EPIC` | `#AA00FF` | 2 | oui | non |
| `LEGENDARY` | `#FFAA00` | 3 | oui | oui |
| `MYTHIC` | `#FF55FF` | 3 | oui | oui |

`getPatternLayers()` explique pourquoi un œuf `MYTHIC` paraît plus chargé qu'un `COMMON`
au même motif, et `isAnimated()` pourquoi seules les lueurs `LEGENDARY` et `MYTHIC`
défilent. Dans ce mod, la rareté est un budget visuel, pas un taux de drop : rien dans le
code ne tire au sort contre la rareté pour décider si un joueur trouve un œuf.

### La couche émissive

16 valeurs : `NONE`, `SPOTS`, `LINES`, `RUNES`, `STARS`, `AURORA`, `VEINS`, `SPARKS`,
`CRACKS`, `PULSE`, `CIRCUIT`, `RIPPLE`, `FLAME`, `NEBULA`, `MATRIX`, `DIAMONDS`.

La lueur est une texture séparée, générée seulement si `emissive != NONE` **et**
`emissiveDensity > 0`. Les deux conditions comptent : un œuf en `emissive = FLAME` avec
`emissiveDensity = 0` n'a aucune carte de lueur. La façon dont chacune est réellement
dessinée est traitée dans [génération de textures et aperçus](rendering.fr.md).

### Tampons et micro-textures

Les tampons relèvent de l'iconographie plutôt que du motif : `NONE`, `STAR`, `MOON`,
`HEART`, `SPIRAL`, `DIAMOND`, `RUNE_TRIPLE`, `ARROW`, `CROWN`, `INFINITY`. Ils sont
dessinés en troisième couche, ce qui fait qu'un tampon survit à un motif chargé en
dessous.

Les micro-textures simulent une matière : `NONE`, `GRANULAR`, `METALLIC`, `ICY`, `SILK`,
`STONE`, `SAND`, `CARBON`. Elles modulent la luminosité à haute fréquence sur toute la
surface, et se lisent donc comme une finition plutôt que comme un dessin.

### Les flags visuels

`visualFlags` est un masque de bits. Cinq bits sont définis :

| Constante | Bit | Hex | Ce que ça ajoute |
|---|---|---|---|
| `FLAG_LIGHTING` | 0 | `0x01` | Éclairage directionnel — faux ombrage sphérique. |
| `FLAG_SPECULAR` | 1 | `0x02` | Une tache de brillance en haut à gauche de la texture. |
| `FLAG_EMBOSS` | 2 | `0x04` | Relief de type Sobel sur les bords du motif, un rendu ciselé. |
| `FLAG_VARNISH` | 3 | `0x08` | Un lustre diagonal dont l'intensité est `varnishLevel`. |
| `FLAG_RIM_GLOW` | 4 | `0x10` | Un halo coloré émissif sur le bord de la silhouette. |

Deux alias existent pour rester compatible avec les anciens noms de ces flags :
`FLAG_OUTLINE` vaut `FLAG_EMBOSS` et `FLAG_EDGE_GLOW` vaut `FLAG_RIM_GLOW`. Ce sont les
mêmes bits, pas des bits en plus.

`hasFlag(int)` est le seul accesseur de lecture. Le dashboard reçoit aussi chaque bit
décodé en booléen (`flagLighting`, `flagSpecular`, `flagEmboss`, `flagVarnish`,
`flagRimGlow`) pour qu'un formulaire puisse lier des cases à cocher sans faire
d'arithmétique binaire en JavaScript — mais une écriture repart toujours sous la forme de
l'entier `visualFlags`.

## Immuabilité, et ce qu'un `PUT` doit faire

`CollectibleDNA` est un `record`. Il n'y a pas de `withPalette(...)`, pas de setter, pas
de builder. La seule façon de produire un ADN qui diffère d'un champ est d'appeler le
constructeur canonique avec les quatorze arguments :

```java
CollectibleDNA updated = new CollectibleDNA(
    old.seed(), newPalette, old.pattern(), old.patternScale(),
    old.secondaryPattern(), old.patternBlend(), old.emissive(),
    old.emissiveDensity(), old.rarity(), old.stamp(), old.microTexture(),
    old.visualFlags(), old.varnishLevel(), old.customPalette()
);
entry.setDna(updated);
```

`EggsApi.applyEdits` fait exactement cela. Pour un `PUT /api/v1/eggs/collectibles/:id`
avec un objet `dna` dans le corps, il lit l'ADN courant de l'entrée dans `old`, puis,
pour chacune des quatorze composantes, prend la valeur soumise **si la clé est présente**
et celle de `old` sinon, et construit enfin un seul record neuf.

### Ce que cela implique pour un appelant de l'API

- **Un objet `dna` partiel est sans danger.** Envoie `{"dna":{"palette":"ICE"}}` et les
  treize autres composantes restent intactes. Le serveur les reprend de l'ADN stocké, pas
  de valeurs par défaut.
- **Omettre, c'est « conserver », pas « effacer ».** Aucun moyen de remettre une
  composante à sa valeur par défaut en la laissant de côté. Pour vider une palette
  personnalisée, il faut envoyer explicitement `"customPalette": ""` ; pour retirer un
  tampon, `"stamp": "NONE"`.
- **Un nom d'enum invalide est avalé.** `safeEnum` retombe sur l'ancienne valeur au lieu
  d'échouer : `{"dna":{"pattern":"SPOT"}}` — remarque le S manquant — renvoie `200` avec
  le motif inchangé. Compare toujours le bloc `dna` de la réponse à ce que tu as envoyé.
- **Les nombres sont bornés, pas rejetés.** `patternScale: 99` devient `4` ;
  `emissiveDensity: -5` devient `0`. Là encore : `200`, avec une valeur que tu n'as pas
  demandée.
- **`visualFlags` est tout ou rien.** C'est un seul entier. L'envoyer remplace tout le
  masque : pour activer un bit, il faut envoyer la valeur complète souhaitée, en général
  `anciensFlags | 0x08`.
- **Omettre `dna` entièrement laisse l'ADN tranquille.** Les champs qui l'entourent
  (`title`, `scale`, coordonnées…) sont appliqués indépendamment.

Conclusion : un client qui fait l'aller-retour `GET` → édition → `PUT` est toujours
correct, et un client qui n'envoie que les champs qu'il modifie l'est aussi. Ce qui est
faux, c'est de croire qu'un champ omis réinitialise quoi que ce soit.

## Sérialisation

L'ADN s'écrit sous trois formes, toutes dans `CollectibleDNA`.

**NBT** — `toNbt()` / `fromNbt(NbtCompound)`. Utilisé pour le block entity, pour le
composant `CUSTOM_DATA` de l'item, et dans l'état persistant du registre. `customPalette`
n'est écrit que s'il est non vide, et `fromNbt` sur un compound sans clé `seed` renvoie un
ADN aléatoire tout neuf au lieu de lever une exception. Les noms d'enum qui n'existent
plus retombent sur un défaut documenté (`WARM`, `SPOTS`, `MARBLE`, `NONE`, `COMMON`) :
renommer une constante dégrade les anciens œufs au lieu de casser la sauvegarde.

**Code ADN** — `toDnaCode()` / `fromDnaCode(String)`. Une chaîne unique, transportable à
la main :

```text
EAC-{graine sur 16 chiffres hex}-{PALETTE}.{PATTERN}.{SCALE}.{SECONDARY}.{BLEND}.{EMISSIVE}.{DENSITY}.{RARITY}.{STAMP}.{MICRO}.{FLAGS sur 2 chiffres hex}.{VARNISH}[;{customPalette}]
```

Un cas réel :

```text
EAC-5F2A91C30B4D77E8-ICE.CRYSTALS.2.MARBLE.35.STARS.60.EPIC.MOON.ICY.0F.45
```

`fromDnaCode` renvoie `null` sur tout ce qu'il ne sait pas analyser — préfixe `EAC-`
absent, moins de douze champs séparés par des points, échelle non numérique. Il ne lève
jamais d'exception. Le dashboard expose le code en `dna.dnaCode` sur chaque réponse de
détail, ce qui transforme « envoie-moi cet œuf » en copier-coller plutôt qu'en formulaire
à resaisir.

**Clé de cache** — `cacheKey()` concatène les quatorze composantes. Le cache de textures
du client s'en sert pour que deux œufs au même ADN partagent une seule texture en
mémoire. Si tu ajoutes une composante au record et oublies de l'ajouter à `cacheKey()`,
deux œufs visuellement différents entreront en collision dans le cache et se rendront à
l'identique. C'est le seul endroit où la liste des champs du record est dupliquée à la
main.

## Génération aléatoire

`randomDNA(long seed)` initialise un `java.util.Random` et en tire chaque décision : la
même graine donne donc toujours le même ADN. `generate()` est `randomDNA` avec une graine
aléatoire.

La distribution n'est volontairement pas uniforme :

- `palette`, `pattern`, `secondaryPattern` et `emissive` sont tirés uniformément dans
  tout leur enum, `NONE` incluse pour l'émissif.
- `stamp` et `microTexture` valent `NONE` deux fois sur trois. Sinon, la valeur est tirée
  uniformément parmi les entrées autres que `NONE`.
- `patternScale` est uniforme sur 1–4. `patternBlend` vaut 15–69. `emissiveDensity`
  vaut 20–79. `varnishLevel` vaut 30–79.
- `FLAG_LIGHTING` et `FLAG_SPECULAR` sont toujours actifs. `FLAG_EMBOSS` est à pile ou
  face, `FLAG_VARNISH` une fois sur trois, `FLAG_RIM_GLOW` une fois sur quatre.
- la rareté est pondérée : `COMMON` 40 %, `UNCOMMON` 25 %, `RARE` 18 %, `EPIC` 10 %,
  `LEGENDARY` 5 %, `MYTHIC` 2 %.
- `customPalette` vaut toujours `""`. La génération aléatoire n'invente jamais de
  couleurs.

C'est pourquoi le bouton **Régénérer** du dashboard, qui appelle
`POST /collectibles/:id/randomize-dna`, jette tout réglage fait à la main : il ne rebat
pas la graine en gardant tes champs, il construit un ADN aléatoire complet depuis une
nouvelle graine. Si tu as choisi une palette à la main, sauvegarde-la en code ADN avant
d'appuyer.

⚠️ `randomize-dna` sans `seed` dans le corps utilise `new Random().nextLong()`, qui n'est
pas reproductible. Passe `{"seed": 1234}` quand tu veux un résultat que tu pourras
retrouver.

---

← [Configuration](configuration.fr.md) · [Génération de textures et aperçus](rendering.fr.md) →
