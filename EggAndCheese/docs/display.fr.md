[← EggAndCheese](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](display.md)

# Œufs décoratifs et stèles

Une découverte ne vaut quelque chose que si le joueur peut l'exposer. La moitié de ce mod
est la chasse ; l'autre moitié est du mobilier.

## L'œuf décoratif

`eggandcheese:collectible_egg` est ce qu'un joueur reçoit quand il trouve un œuf. C'est un
`BlockItem` en `maxCount(1)` — chaque exemplaire est unique parce que chacun porte son
propre ADN — et c'est `CollectibleItem.create(type, dna)` qui en construit un.

Son `CUSTOM_DATA` contient le compound `dna` et, quand l'item vient d'un œuf enregistré,
une chaîne `registryId`. L'id ne sert qu'à l'infobulle ; rien dans l'item décoratif n'est
lié au registre.

### Infobulle

`CollectibleItem.appendTooltip` ajoute jusqu'à trois lignes :

```text
Motif : Cristaux
Palette : Glace
ID : cave_nord
```

En anglais :

```text
Pattern: Cristaux
Palette: Glace
ID: cave_nord
```

Les valeurs de motif et de palette viennent du `getDisplayName()` des enums, qui sont des
**chaînes françaises dans le code source Java** quelle que soit la langue du client — un
client anglais lit donc `Pattern: Cristaux`. La ligne d'ID est en gris foncé et n'apparaît
que si un `registryId` est présent.

### Le poser

Un clic droit sur une face de bloc pose un bloc `eggandcheese:egg_deco`. `postPlacement`
recopie l'ADN de l'item sur le nouveau `CollectibleDecoBlockEntity` : l'œuf posé est donc
exactement l'item.

Le bloc :

- a `noCollision()` et `nonOpaque()` — les joueurs le traversent,
- ne porte qu'une propriété `FACING`, réglée à l'opposé de l'orientation du joueur qui
  pose,
- a `strength(0.5f)`, et se casse donc à peu près aussi vite qu'un bloc de laine,
- n'est **pas** waterloggable, contrairement au bloc admin,
- se rend en `ENTITYBLOCK_ANIMATED` avec un ticker purement client, comme l'œuf admin.

Le casser rend l'œuf. `onBreak` reconstruit l'item depuis l'ADN du block entity et le
dépose au sol avec `dropStack` : un œuf décoratif survit donc à un déménagement de
construction. Noter qu'il est lâché comme item au sol au lieu d'être remis au joueur,
c'est-à-dire l'inverse de ce que fait le bloc admin.

### Dans d'autres conteneurs

`CollectibleItem` étend `BlockItem`, et cela a causé un vrai bug qui mérite d'être
connu : un clic droit sur *n'importe quel* bloc tentait de poser le bloc déco, ce qui
rendait impossible de mettre un œuf dans la vitrine (`display_case`) de Cobblemon. La
classe redéfinit le chemin d'utilisation de l'item pour renvoyer `PASS` dans ce cas,
laissant le handler du bloc cible s'exécuter et insérer l'item normalement. Le commentaire
du code nomme explicitement `display_case`.

C'est une observation sur le comportement d'un mod tiers, pas une spécification : le code
a été écrit contre le Cobblemon de l'époque, et une version future pourrait interagir
autrement.

## Les stèles

Une stèle est un bloc qui expose un item, mis à l'échelle, surélevé et éventuellement en
rotation, au sommet d'un pilier réglable. Ce sont des blocs craftables ordinaires, pas des
outils d'admin.

### Les 23 variantes

Chaque variante est son propre bloc et son propre item, toutes partageant un seul type de
block entity `eggandcheese:pedestal` :

`pedestal_stone`, `pedestal_oak`, `pedestal_quartz`, `pedestal_iron`, `pedestal_gold`,
`pedestal_diamond`, `pedestal_netherite`, `pedestal_copper`, `pedestal_amethyst`,
`pedestal_emerald`, `pedestal_dark_oak`, `pedestal_cherry`, `pedestal_birch`,
`pedestal_deepslate`, `pedestal_blackstone`, `pedestal_prismarine`, `pedestal_spruce`,
`pedestal_jungle`, `pedestal_acacia`, `pedestal_mangrove`, `pedestal_bamboo`,
`pedestal_crimson`, `pedestal_warped`.

Les réglages de bloc de chacune sont copiés du matériau dont elle porte le nom —
`Settings.copy(Blocks.STONE_BRICKS)` pour la stèle en pierre, `Blocks.GOLD_BLOCK` pour
celle en or — de sorte que dureté, outil requis et sons correspondent à ce matériau. Le
seul ajout est `nonOpaque()`.

### Fabrication

Les 23 partagent une seule recette façonnée : une colonne de deux, sur une base de trois,
du matériau évident.

```text
 X
 X
XXX
```

| Stèle | `X` |
|---|---|
| `pedestal_stone` | `minecraft:stone_bricks` |
| `pedestal_gold` | `minecraft:gold_ingot` |
| … | le bloc ou le lingot correspondant à chaque variante |

Le résultat est une stèle. Les recettes sont du JSON de datapack ordinaire dans
`data/eggandcheese/recipe/`, un fichier par variante : un pack peut donc en remplacer ou
en retirer n'importe laquelle sans toucher au mod.

### S'en servir

| Action | Résultat |
|---|---|
| Clic droit avec un item, stèle vide | Un item est pris sur ton stack et exposé |
| Clic droit à mains nues, stèle occupée | L'item exposé te revient |
| Clic droit avec un item, stèle occupée | Rien — l'interaction passe outre |
| Clic droit avec un **œuf admin**, quel que soit l'état | Rien — l'interaction passe outre |

`canAccept` n'exige qu'un stack non vide : une stèle expose donc n'importe quoi — un œuf
collectible, un diamant, un gâteau. La seule exclusion explicite est
`AdminCollectibleItem` : donner l'item de placement admin à une stèle renvoie
`PASS_TO_DEFAULT_BLOCK_INTERACTION` au lieu de le consommer, pour qu'on ne puisse pas
perdre par mégarde l'item de placement d'un œuf dans le mobilier.

L'item est pris par `stack.copyWithCount(1)` et le stack source décrémenté de un : un
stack de 64 en perd donc exactement un.

La reprise insère dans ton inventaire, ou lâche à tes pieds si celui-ci est plein.

### Propriétés d'affichage

Quatre valeurs vivent sur `PedestalBlockEntity`, toutes réglables avec
[`/pedestal`](commands.fr.md#pedestal) :

| Propriété | Défaut | Plage via la commande | Effet |
|---|---|---|---|
| `displayScale` | `1.0` | 0,5 – 3,0 | Taille de l'item exposé |
| `displayHeight` | `0.0` | −0,5 – 0,5 | Décalage vertical, en blocs |
| `pillarHeight` | `1.0` | 0,0 – 5,0 | Hauteur du pilier sous la plateforme |
| `spinning` | `true` | bascule | Rotation ou non de l'item |

Le renderer fait aussi flotter l'item doucement ; `bobOffset` et `bobTick` sont de l'état
d'animation côté client, pas de la configuration.

`getDisplayDNA()` lit l'ADN de ce qui se trouve sur la stèle, et c'est ainsi qu'un œuf
posé sur une stèle obtient sa texture procédurale au lieu d'une icône plate.

### Piliers et bloc d'extension

`pillarHeight` n'est pas cosmétique. `setPillarHeight` reconstruit un tableau de
`VoxelShape` mis en cache et appelle `updateExtensions()`, qui pose des blocs
`eggandcheese:pedestal_extension` au-dessus de la stèle pour porter la collision de la
plateforme surélevée. `removeAllExtensions()` les nettoie sur le chemin de retrait
normal.

`PedestalExtensionBlock` est enregistré avec `strength(-1.0f, 3600000.0f)` — les mêmes
réglages que la bedrock. Il ne peut être ni miné ni soufflé. C'est volontaire : une
extension est structurelle, et laisser un joueur casser le milieu d'un pilier laisserait
la plateforme en suspension sans collision.

⚠️ Si une stèle disparaît par une voie qui n'appelle pas `removeAllExtensions` — un
`/setblock`, une opération WorldEdit, un rollback de chunk — ses blocs d'extension restent,
et ils sont incassables. Retire-les avec `/setblock <x> <y> <z> air`. Il n'existe aucun
autre moyen en jeu de s'en débarrasser.

Le cache de formes est indexé sur la `pillarHeight` courante (`cachedShapesPh`) et
reconstruit seulement quand la hauteur change réellement : les recherches de collision sur
une stèle inchangée ne coûtent donc rien.

## L'advancement

`data/eggandcheese/advancements/egg_found.json` est un unique advancement à un critère :

```json
{
  "criteria": {
    "find_egg": {
      "trigger": "eggandcheese:egg_found",
      "conditions": { "egg_id": "*" }
    }
  },
  "rewards": {}
}
```

Le `"*"` est traité par `EggFoundCriterion.Conditions.matches`, qui accepte un `egg_id`
absent, le littéral `*`, ou un id exact. Cet advancement se déclenche donc sur **n'importe
quelle** découverte d'œuf. Il n'a pas de bloc `display`, ce qui le rend invisible dans
l'écran des progrès et en fait un simple point d'accroche — FTB Quests peut le surveiller,
et un datapack peut en dépendre.

Pour construire une tâche liée à un œuf précis, écris ton propre advancement avec l'id
dans les conditions :

```json
{
  "criteria": {
    "find_it": {
      "trigger": "eggandcheese:egg_found",
      "conditions": { "egg_id": "cave_nord" }
    }
  }
}
```

Le critère est enregistré dans `EggAndCheese.onInitialize` sous `Registries.CRITERION` :
le déclencheur `eggandcheese:egg_found` est donc disponible pour n'importe quel datapack
sans configuration supplémentaire.

---

← [Placement et découvertes](placement-and-discovery.fr.md) · [L'intégration FTB Quests](ftb-quests.fr.md) →
