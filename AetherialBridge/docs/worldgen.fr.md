[← AetherialBridge](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](worldgen.md)

# Génération du monde

Deux choses se passent dans ce mod, et elles méritent d'être séparées. La première est
un enregistrement très court : une classe annonce à CheeseGuard un type de génération
de plus, et la manière de construire son chunk generator. La seconde est
une chaîne de réparation : quatre mixins qui permettent à un monde dont l'océan est à
y=-30 de survivre à des features conçues pour un monde dont l'océan est à y=63.

Tout ce qui suit est lu dans `FloatingIslandsProvider` et dans les cinq classes de
`com.aetherialbridge.mixin`. Quand un nombre vient du datapack et non du code de ce
mod, c'est signalé, parce que tu peux le changer et que le mod suivra.

---

## Le provider de type de génération

`FloatingIslandsProvider` implémente l'interface `GenTypeProvider` de CheeseGuard.
L'interface a six méthodes, dont cinq renvoient une constante :

| Méthode | Renvoie | Pourquoi cette valeur |
|---|---|---|
| `id()` | `"aetherial-islands:floating"` | la clé que le dashboard renvoie en `customGenTypeId`. Avec espace de noms, comme l'interface l'exige. |
| `label()` | `"Aetherial Islands"` | le texte sur la carte du dashboard. |
| `icon()` | `"☁"` (`☁`) | un seul glyphe, affiché au-dessus du label. |
| `defaultSpawnY()` | `100.0` | appliqué à la config de dimension à la création. Au-dessus de la bande d'îles, très au-dessus de la mer à y=-30. |
| `dimensionType()` | `DimensionTypes.OVERWORLD` | donne à la dimension la lumière de l'overworld, son intervalle de hauteur (-64 à 320), son cycle jour/nuit et les `effects` de l'overworld vanilla pour le rendu du ciel. |
| `createChunkGenerator(server, config)` | un `NoiseChunkGenerator` | le vrai travail, ci-dessous. |

Le générateur se construit en six lignes utiles :

```java
var cgsRegistry = server.getRegistryManager().get(RegistryKeys.CHUNK_GENERATOR_SETTINGS);
RegistryEntry<ChunkGeneratorSettings> settingsEntry = cgsRegistry.getEntry(SETTINGS_KEY)
    .orElseThrow(() -> new IllegalStateException("ChunkGeneratorSettings '" + SETTINGS_ID + "' not loaded — ..."));
BiomeSource biomeSource = CheeseGuardWorldgenApi.freshOverworldBiomeSource(server);
return new NoiseChunkGenerator(biomeSource, settingsEntry);
```

Trois décisions y sont condensées.

**Les noise settings sont cherchées par identifiant, pas construites.**
`aetherial_islands:floating` doit exister dans le registre `ChunkGeneratorSettings` du
serveur, donc être présent dans un datapack chargé. Ce mod n'embarque aucune donnée de
worldgen ; voir [l'espace de noms du datapack](installation.fr.md#lespace-de-noms-du-datapack)
pour savoir à quoi doit ressembler le fichier, et où il va. Quand la recherche échoue,
le provider lève une exception, CheeseGuard l'attrape et retombe sur un générateur de
vide.

**La source de biomes est une source overworld neuve, pas celle de l'overworld en
cours.** `CheeseGuardWorldgenApi.freshOverworldBiomeSource` construit un nouveau
`MultiNoiseBiomeSource` à partir de la liste de paramètres `OVERWORLD`. Le commentaire
de CheeseGuard en donne la raison : réutiliser la source de biomes de l'overworld en
cours figerait le placement des biomes sur les coordonnées de l'overworld et ignorerait
la seed de la nouvelle dimension, si bien que deux dimensions « aléatoires » auraient
des biomes identiques. La conséquence visible ici, c'est que les îles portent la palette
de biomes vanilla, y compris les biomes que d'autres mods injectent via TerraBlender,
que CheeseGuard initialise pour les dimensions créées à chaud.

**Pas de fusion de surface rule.** `CheeseGuardWorldgenApi` propose aussi
`withOverworldSurfaceRule`, qui greffe la surface rule de l'overworld en cours sur un
objet de settings. Ce provider ne l'appelle pas : la surface vient donc de la
`surface_rule` du datapack. C'est le bon choix pour un terrain dont la forme n'a rien à
voir avec celle de l'overworld, mais c'est bon à savoir si tu débogues un biome moddé
dont les blocs de surface sont faux dans cette dimension et corrects dans l'overworld.

### Comment CheeseGuard y accède

La poignée de main se résume à un entrypoint custom Fabric, sans aucun appel d'API de
ce côté :

```json
"entrypoints": {
  "main": ["com.aetherialbridge.AetherialBridgeMod"],
  "cheeseguard-gentype": ["com.aetherialbridge.FloatingIslandsProvider"]
}
```

`GenTypeRegistry.discover()` tourne une fois pendant `CheeseGuardMod.onInitialize()`,
rassemble tous les entrypoints `cheeseguard-gentype`, rejette un provider dont `id()`
est nul ou déjà pris, puis gèle la map. Rien ne peut s'enregistrer plus tard, et rien ne
se désenregistre. `AetherialBridgeMod.onInitialize()` ne fait lui-même qu'une chose :
écrire une ligne de log. Le provider est instancié par Fabric, pas par ce mod.

Ensuite, CheeseGuard appelle `createChunkGenerator` une fois par création de dimension,
et une fois de plus à chaque démarrage du serveur pour chaque dimension sauvegardée
qu'il recrée. Les deux appels se font sur le thread serveur.

---

## Le seuil de niveau de la mer

Quatre mixins changent le comportement, et tous les quatre décident d'agir par le même
test :

```java
if (settings.seaLevel() >= -25) return;
```

ou son équivalent, `ctx.getGenerator().getSeaLevel() >= -25`. Au-dessus de -25, chaque
mixin ressort immédiatement et le vanilla tourne intact. En dessous, la chaîne de
réparation s'enclenche.

Le datapack DimScope fixe `sea_level: -30`, donc la dimension se situe 5 sous le seuil.
L'overworld vanilla est à 63, le nether à 32, l'end à 0 : aucun n'en approche. C'est tout
le mécanisme par lequel ce mod laisse les autres dimensions tranquilles, et il vaut
mieux être clair sur sa forme : **le test porte sur le niveau de la mer du chunk
generator, pas sur l'identifiant de la dimension.** N'importe quelle dimension, venant
de n'importe quel mod ou datapack, dont les noise settings placent la mer sous -25,
récolte le fond marin forcé et la suppression des features. Il n'y a pas de liste
d'autorisation.

Deux conséquences :

- Si tu modifies le datapack et remontes `sea_level` au-dessus de -25, les mixins ne se
  déclenchent plus et tu obtiens un comportement vanilla dans un monde taillé pour autre
  chose : un puits sec jusqu'à la bedrock là où l'océan devrait être, de la lave de -64
  à -54, des sources de lave dans la bande d'îles, et le balayage de placement de
  Minerally qui tourne en boucle.
- Si tu installes un second datapack à niveau de la mer très bas, il hérite de tout ça,
  qu'il le veuille ou non.

Le javadoc de `SpringFeatureMixin` et de `LakeFeatureMixin` annonce « sea_level &lt; -50 »
et celui de `SpringFeatureMixin` mentionne -55 ; celui de `MinerallyFeatureMixin` dit déjà
-25 (et que la dimension est à -30). Le code des quatre dit -25. Les deux premiers
commentaires sont périmés ; le nombre qui compte est -25.

---

## Le fond marin forcé

`NoiseChunkGeneratorMixin` contient deux injections. Les deux visent le
`NoiseChunkGenerator` vanilla, qu'utilise toute dimension de type overworld du serveur,
et les deux sont gardées par le test de niveau de la mer ci-dessus.

### Forcer l'eau à la place de la lave

```java
@Inject(method = "createFluidLevelSampler(...)", at = @At("HEAD"), cancellable = true)
```

L'échantillonneur de niveau de fluide vanilla répond à « quel fluide va à cette
position » par deux bandes : de la lave sous y=-54, et le fluide configuré jusqu'au
niveau de la mer. Dans un monde dont le `min_y` est -64 et dont la mer est à -30, cette
bande de lave fait dix couches de lave sur tout le fond du monde. Le mixin remplace
l'échantillonneur par un échantillonneur constant : le fluide par défaut des settings
(l'eau, dans ce datapack), à `seaLevel + 1`.

Le datapack désactive les aquifères, ce qui limite les cas où l'échantillonneur est
consulté, mais il reste la source du niveau de fluide par défaut : l'écrasement compte
donc.

### Écrire le fond

```java
@Inject(method = "carve(...)", at = @At("RETURN"))
```

La seconde injection tourne à la fin de `carve`, et uniquement pour
`GenerationStep.Carver.AIR`. C'est l'ordre des statuts de chunk qui rend ce placement
valable : Minecraft exécute `NOISE`, puis `SURFACE`, puis `CARVERS`, puis `FEATURES`.
Injecter à la fin du creusement signifie que le fond est écrit après que les surface
rules du datapack ont déjà peint : c'est donc lui qui gagne. Les features tournent
ensuite, et c'est précisément pour ça que les sources de lave ont demandé un correctif
à part.

Pour chacune des 256 colonnes du chunk, le mixin calcule une hauteur de fond à partir de
quatre termes sinusoïdaux, écrit de la bedrock à `min_y`, remplit le matériau de fond
jusqu'à cette hauteur, puis remplit d'eau de là jusqu'au niveau de la mer :

| Grandeur | Formule | Avec ce datapack |
|---|---|---|
| bedrock | `min_y` | y=-64 |
| base du fond | `min_y + 1` | y=-63 |
| plafond du fond | `seaLevel - 3` | y=-33 |
| sommet du fond | `clamp(base + round(n + 14))` | entre -63 et -33 |
| colonne d'eau | de `sommetDuFond + 1` à `seaLevel` | jusqu'à y=-30 inclus |

`n` est la somme de quatre termes sinus/cosinus de longueurs d'onde d'environ 140, 30, 10
et 90 blocs, d'amplitudes 8, 4, 1.5 et 3. Trois décalages de phase sont dérivés de trois
tranches de 16 bits différentes de la seed du monde, donc deux dimensions de seeds
différentes obtiennent des fonds différents. C'est une fonction de bruit écrite à la
main plutôt qu'une `DensityFunction` Minecraft : peu coûteuse, périodique, et largement
suffisante pour une surface qu'un joueur voit de près.

Le matériau du fond vient d'une seconde fonction de taches, indépendante, évaluée par
colonne :

| Valeur de la tache | Bloc |
|---|---|
| > 1.1 | `minecraft:prismarine` |
| > 0.55 | `minecraft:gravel` |
| < -1.05 | `minecraft:clay` |
| < -0.6 | `minecraft:dirt` |
| sinon | `minecraft:sand` |

Le sable est le cas courant, et les autres apparaissent en taches lentes, de l'ordre de
80 blocs de large.

### Décoration

Une passe privée `aetherialbridge$decorate` parcourt ensuite les mêmes 256 colonnes avec
un `java.util.Random` initialisé depuis la seed du monde et les coordonnées du chunk : le
résultat est donc reproductible par chunk et indépendant de l'aléa des features vanilla.
Les colonnes qui ont moins de 2 blocs d'eau au-dessus du fond sont ignorées entièrement.

Un seul tirage de `nextDouble()` par colonne décide de tout, lu dans cet ordre :

| Condition | Résultat |
|---|---|
| fond en prismarine et tirage < 0.04 | amas de coraux |
| tirage < 0.005 | amas de coraux, sur n'importe quel fond |
| fond en sable ou gravier et tirage < 0.06 | colonne de varech, 3 à 14 de haut, plafonnée à `profondeur - 1` |
| tirage < 0.10 et profondeur ≥ 3 | herbe aquatique haute, les deux moitiés |
| tirage < 0.22 | herbe aquatique |
| tirage < 0.235 | cornichon de mer, 1 à 4 unités, waterlogged |
| tirage < 0.238 | éponge ou éponge humide, 50/50 |
| sinon | fond nu |

À peu près un quart du fond porte quelque chose, les taches de prismarine sont les
récifs de corail, et les étendues de sable sont les forêts de varech. Un amas de coraux
choisit une des cinq familles et place 4 à 9 blocs sur une emprise de 3×3, chacun de 1 ou
2 de haut, avec une chance sur trois chacun d'un éventail ou d'une plante de corail au
sommet, les deux waterlogged, jamais au-dessus de `seaLevel - 1`.

Une bizarrerie, puisqu'elle est visible si on la cherche : la branche du varech ressort
par `continue` quand l'eau est trop peu profonde pour une colonne de 3 blocs (moins de 4
de profondeur), et comme les branches forment une seule chaîne de `else if`, un tirage
tombé sur le varech ne retombe jamais sur l'herbe aquatique : la colonne reste nue. Les
fonds sableux peu profonds sont donc un peu plus nus que le tableau ne le suggère.

---

## Les features supprimées

Trois features viennent d'ailleurs et se comportent mal sur ce terrain. Chacune a son
mixin, et aucune n'est configurable.

### Les sources de lave

`SpringFeatureMixin` s'injecte dans `SpringFeature.generate` vanilla et annule le
placement quand le fluide configuré est de la lave et que le niveau de la mer du
générateur est sous -25. Les sources d'eau sont laissées tranquilles : le test porte donc
sur `ctx.getConfig().state.getFluid()` et non sur l'identifiant de la feature.

Les biomes overworld vanilla programment une vingtaine de tentatives de source de lave
par chunk, biaisées vers le bas du monde. Dans un monde dont le fond est un océan rempli,
chaque réussite devient un tunnel de lave à travers l'eau, visible depuis la surface et
mortel pour qui nage dedans.

### Les lacs

`LakeFeatureMixin` annule tous les appels à `LakeFeature.generate` sous le seuil, sans
inspecter la configuration. Le raisonnement de son javadoc est que la génération des
biomes overworld vanilla ne programme que `lake_lava_surface` et
`lake_lava_underground` : il n'y a donc pas de lac d'eau à préserver. C'est vrai pour le
vanilla. Un mod ou un datapack qui ajouterait une feature de lac d'eau à un biome tagué
overworld la perdrait dans cette dimension, et la perte serait silencieuse.

### Les minerais Minerally

`MinerallyFeatureMixin` est un mixin `@Pseudo` qui vise quatre classes de
`theone_ss.minerally.generation.feature` : `OverWorldMineralFeature`,
`DeepslateMineralFeature`, `MineralRockFeature` et `CrystalFeature`. Il les annule toutes
sous le seuil.

Minerally accroche ses features via
`BiomeModifications.addFeatureInOverWorldBiome`, ce qui veut dire que chaque biome tagué
`#minecraft:is_overworld` les porte ; et comme la dimension flottante utilise la source
de biomes overworld vanilla, elle hérite du lot. Sur cette géométrie, le
`PositionChecker.isValidPositionDown` de Minerally ne converge jamais : le serveur écrit
des avertissements `Empty height range: [-55 absolute--60 absolute]` puis le watchdog se
déclenche. Ce mixin existe pour éviter un crash, pas pour ranger la distribution des
minerais.

### Les raid dens Cobblemon

`RaidDenFeatureMixin` est le mouton noir, et le seul dont un administrateur pilote le
comportement. C'est un mixin `@Pseudo` sur
`com.necro.raid.dens.common.worldgen.RaidDenFeature`, et au lieu de tester le niveau de
la mer, il interroge CheeseGuard :

```java
var world = ctx.getWorld().toServerWorld();
String worldId = world.getRegistryKey().getValue().toString();
if (!CheeseGuardWorldgenApi.isRaidDensEnabled(worldId)) {
    cir.setReturnValue(false);
}
```

`isRaidDensEnabled` cherche l'identifiant de monde dans les configs de dimension de
CheeseGuard et renvoie le drapeau `raidDensEnabled`, avec `true` par défaut pour toute
dimension qu'il ne connaît pas. Le placement des dens n'est donc annulé que là où un
administrateur a décoché la case 🐲 **Raid dens**, proposée à la fois dans l'assistant de
création et sur la page de détail de la dimension.

Deux choses en découlent, que le javadoc de la classe ne dit pas. D'abord, ça **n'a rien
à voir avec les îles flottantes** : ça s'applique à toute dimension pour laquelle
CheeseGuard a une config, et CheeseGuard enregistre aussi des configs pour les mondes
vanilla, donc décocher la case sur `minecraft:overworld` désactive les raid dens naturels
dans ton monde principal. C'est une fonctionnalité si tu le voulais, et une surprise
sinon. Ensuite, tout le corps est entouré d'un `try { ... } catch (Throwable ignored) {}`,
délibérément, pour qu'une recherche en échec laisse la worldgen continuer au lieu de
bloquer un chunk.

La raison pour laquelle ce code vit dans ce mod plutôt que dans CheeseGuard est
mécanique : CobblemonRaidDens n'a pas de liste noire par dimension, et CheeseGuard ne
s'injecte pas dans des mods tiers. AetherialBridge est déjà un mod-patch, donc le hook a
été mis ici.

---

## Ce que tu peux changer, et où

| Ce que tu veux changer | Où |
|---|---|
| forme des îles, hauteur, couches, climat des biomes | le datapack Aetherial Islands |
| niveau de la mer, hauteur du monde, bloc et fluide par défaut | `noise_settings/floating.json` dans le datapack |
| les matériaux ou la décoration du fond marin | `NoiseChunkGeneratorMixin`, recompilation |
| le seuil de -25 | les quatre mixins, recompilation. C'est un littéral dans chacun |
| le spawn Y des nouvelles dimensions de ce type | `defaultSpawnY()` dans `FloatingIslandsProvider`, ou par dimension depuis le dashboard |
| les raid dens activés ou non, par dimension | le dashboard, sans reconstruction |
| tout le reste au sujet de la dimension | les réglages de dimension de CheeseGuard |

---

← [Premiers pas](getting-started.fr.md) · [Architecture](architecture.fr.md) →
