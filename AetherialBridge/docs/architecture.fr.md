[← AetherialBridge](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](architecture.md)

# Architecture

Sept fichiers Java, 485 lignes, dont plus de la moitié dans un seul mixin. Assez petit
pour que cette page nomme chaque ligne qui compte, et c'est bien l'objectif : quand les
mods que celui-ci rapièce changent, il faut savoir quoi ouvrir en premier.

---

## Organisation des packages

```text
com.aetherialbridge
├── AetherialBridgeMod.java        ModInitializer. Écrit une ligne de log. Aucun état.
├── FloatingIslandsProvider.java   le GenTypeProvider que CheeseGuard découvre
└── mixin
    ├── NoiseChunkGeneratorMixin.java  264 lignes : fluide forcé à l'eau + fond marin forcé
    ├── SpringFeatureMixin.java        annule les sources de lave sous un niveau de mer de -25
    ├── LakeFeatureMixin.java          annule tous les lacs sous -25
    ├── MinerallyFeatureMixin.java     @Pseudo : annule les features Minerally sous -25
    └── RaidDenFeatureMixin.java       @Pseudo : annule les raid dens là où CheeseGuard le dit
```

Deux ressources :

```text
src/main/resources/fabric.mod.json              2 entrypoints, 5 entrées depends
src/main/resources/aetherialbridge.mixins.json  5 mixins, required: true, defaultRequire: 1
```

## Qui possède quoi

**`AetherialBridgeMod`** est l'entrypoint `main` et ne contient rien d'autre qu'une
constante `MOD_ID` et un `LOGGER` public statique. Son `onInitialize` écrit une ligne et
rend la main. D'autres mods de ce dépôt gardent un singleton `INSTANCE` pour la
réflexion inter-mods ; celui-ci n'a rien qui vaille d'être atteint, donc il s'en passe.

**`FloatingIslandsProvider`** constitue toute la surface publique. La classe est `final`,
sans état, et toutes ses méthodes sauf une renvoient une constante. Fabric l'instancie
quand CheeseGuard demande l'entrypoint `cheeseguard-gentype`, et CheeseGuard garde cette
instance dans `GenTypeRegistry` pour la durée du processus. `createChunkGenerator` est la
seule méthode qui travaille, et elle alloue un nouveau `NoiseChunkGenerator` à chaque
appel : une fois par création de dimension, une fois par dimension sauvegardée à chaque
démarrage du serveur.

**Les cinq mixins** n'ont pas d'état non plus. Quatre d'entre eux sont de purs gardes qui
ressortent tôt ou annulent un appel. `NoiseChunkGeneratorMixin` fait exception : il
shadow le champ privé `settings` du vanilla pour lire le niveau de la mer, et ses deux
méthodes auxiliaires (`aetherialbridge$decorate`, `aetherialbridge$placeCoralCluster`)
reçoivent tout ce dont elles ont besoin en paramètres, y compris l'objet de travail
`BlockPos.Mutable` : rien n'est conservé d'un chunk à l'autre.

## État et disque

Il n'y en a pas. Aucun fichier de configuration, aucune donnée persistée, aucun cache,
aucun champ statique mutable nulle part dans le mod. Redémarrer le serveur ne remet rien
à zéro, parce qu'il n'y avait rien à remettre à zéro.

L'état dont dépend le comportement vit à deux autres endroits. Les noise settings
viennent d'un datapack et sont lues par le registry manager du serveur à chaque appel de
`createChunkGenerator` : un `/reload` qui recharge les datapacks est donc pris en compte
à la création de dimension suivante, sans mise en cache. Le drapeau `raidDensEnabled`
vit dans le `config/cheeseguard_dimensions.json` de CheeseGuard et est lu à chaque
tentative de placement de raid den, ce qui fait que l'interrupteur du dashboard prend
effet sur les chunks nouvellement générés sans redémarrage.

## Threading

Tout dans ce mod tourne sur un chemin de worldgen, et rien de cela n'est un choix de ce
mod.

- `createChunkGenerator` est appelé par le `DimensionManager` de CheeseGuard, que le
  dashboard atteint via `WebDashboard.executeOnServerThread`. C'est donc toujours sur le
  **thread serveur**, et le javadoc de `GenTypeProvider` en fait un contrat.
- Les deux injections dans `NoiseChunkGenerator` tournent là où Minecraft fait tourner la
  génération de chunks : sur le **pool de workers de worldgen**, plusieurs chunks en
  parallèle. Elles ne touchent que leur propre objet `Chunk` et des variables locales, ce
  qui est précisément ce qui rend ça sûr. Ajouter un champ mutable partagé à ce mixin
  créerait une course de données.
- `createFluidLevelSampler` est `static` dans le vanilla, et l'injection est statique
  aussi : elle n'a aucun accès à l'état d'instance et ne peut pas s'en inventer un.
- `RaidDenFeatureMixin` lit la liste de configs vivante de CheeseGuard depuis un thread de
  worldgen. `CheeseGuardWorldgenApi.isRaidDensEnabled` se documente comme appelable
  depuis n'importe quel thread, puisqu'il s'agit d'une lecture sur la liste de configs, et
  tout le corps est entouré d'un `catch (Throwable)` : une modification concurrente
  dégénère en « dens autorisés » plutôt qu'en chunk en échec.

Aucun thread HTTP n'entre jamais dans ce mod. Il n'enregistre aucun module de dashboard
et ne sert aucune route.

## Mixins

| Mixin | Cible | Injection | Remap | Pourquoi |
|---|---|---|---|---|
| `NoiseChunkGeneratorMixin` | `net.minecraft.world.gen.chunk.NoiseChunkGenerator` | `createFluidLevelSampler` en `HEAD`, cancellable | oui | forcer l'eau au lieu de la bande de lave vanilla sous y=-54 |
| | | `carve` en `RETURN` | oui | écrire bedrock, fond marin et colonne d'eau, puis décorer |
| `SpringFeatureMixin` | `net.minecraft.world.gen.feature.SpringFeature` | `generate` en `HEAD`, cancellable | oui | les sources de lave perceraient l'océan |
| `LakeFeatureMixin` | `net.minecraft.world.gen.feature.LakeFeature` | `generate` en `HEAD`, cancellable | oui | les lacs de lave, même raison |
| `MinerallyFeatureMixin` | 4 classes de `theone_ss.minerally.generation.feature` | `generate` en `HEAD`, cancellable, `require = 0` | non | son balayage de placement ne converge jamais et le watchdog tue le serveur |
| `RaidDenFeatureMixin` | `com.necro.raid.dens.common.worldgen.RaidDenFeature` | `generate` en `HEAD`, cancellable, `require = 0` | non | CobblemonRaidDens n'a pas de liste noire par dimension |

Ce que fait chacun en détail, et le terrain qui en sort, est dans
[génération du monde](worldgen.fr.md).

Les deux mixins tiers sont `@Pseudo` avec `remap = false`, et chacun liste deux noms de
méthode pour que l'injecteur se lie dans les deux cas :

```java
@Inject(method = {
    "generate(Lnet/minecraft/world/gen/feature/util/FeatureContext;)Z",
    "method_13151(Lnet/minecraft/class_5821;)Z"
}, at = @At("HEAD"), cancellable = true, require = 0)
```

Le premier est le nom Yarn, qui ne correspond que dans un environnement de développement
en mappings Yarn ; le second est le nom intermediary, celui que porte à l'exécution, en
production, tout jar de mod — Minerally et CobblemonRaidDens compris —, et celui qui se
lie réellement sur un serveur. Lister les deux évite d'avoir besoin d'un refmap pour une classe contre
laquelle ce mod ne peut pas compiler. `require = 0` signifie qu'un mixin qui ne se lie à
rien n'est pas une erreur, et c'est ce qui permet au mod de rester chargeable quand
Minerally ou CobblemonRaidDens est absent. C'est aussi un vrai danger, traité dans
[faiblesses connues](#faiblesses-connues).

## Pourquoi l'overworld est à l'abri

Le README de ce mod et celui de la racine affirment tous les deux que l'overworld vanilla
reste intact. C'est vrai, et c'est vrai pour trois raisons distinctes, que tu peux
vérifier toi-même.

**1. Aucune donnée de worldgen ne vise `minecraft:`.** Ce mod n'embarque aucun dossier
`data/`, et le datapack repackagé qu'il attend a vu son arborescence `data/minecraft/`
supprimée ; les noise settings vivent à
`data/aetherial_islands/worldgen/noise_settings/floating.json` à la place. Rien
n'écrase les noise settings, carvers, features ou structure sets de
`minecraft:overworld`. C'est la partie qui, sinon, réécrirait tout ton serveur, et c'est
la raison d'être du repackaging.

**2. Quatre des cinq mixins sont gardés par un niveau de mer que l'overworld ne peut pas
atteindre.** `NoiseChunkGeneratorMixin`, `SpringFeatureMixin`, `LakeFeatureMixin` et
`MinerallyFeatureMixin` ressortent immédiatement quand le niveau de la mer est à -25 ou
au-dessus. Celui de l'overworld vanilla est à 63. Les injections sont bien présentes dans
le chemin de code de l'overworld, et ça coûte une seule comparaison d'`int` par appel.

**3. Le mixin des raid dens autorise par défaut.** `isRaidDensEnabled` renvoie `true`
pour tout monde dont il n'a pas de config, et pour toute config dont le drapeau est posé,
ce qui est le cas par défaut. Il n'annule donc rien, nulle part, jusqu'à ce qu'un
administrateur décoche une case.

Trois réserves honnêtes là-dessus :

- Le garde est un **test sur le niveau de la mer, pas sur l'identifiant de dimension**.
  Installe un autre datapack qui place sa mer sous -25 et il reçoit le même traitement, y
  compris un fond marin forcé qu'il n'a jamais demandé.
- `RaidDenFeatureMixin` **n'est pas limité aux dimensions personnalisées**, contrairement
  à ce que dit son javadoc. CheeseGuard tient aussi des configs pour les mondes vanilla,
  donc décocher **Raid dens** sur `minecraft:overworld` supprime bien les dens naturels
  dans l'overworld. C'est une action d'administrateur, pas quelque chose que ce mod fait
  de lui-même, mais c'est un effet global atteint via un mod dont le README dit qu'il
  laisse l'overworld tranquille.
- Le datapack peut encore transporter des choses globales. La version DimScope embarque
  quatre fichiers `worldgen_modifier` Lithostitched, dont un ajoute une surface rule à
  `minecraft:overworld` et les trois autres réécrivent le climat et les couleurs de
  quatre biomes vanilla pour tout le serveur. Ils ne font rien sans Lithostitched installé. Avec lui, ils
  s'appliquent, et c'est l'affaire du datapack plutôt que celle de ce mod.

## Où se situe le couplage

Quatre paris distincts sur du code que ce mod ne possède pas. Dans l'ordre où les
vérifier quand quelque chose casse après une mise à jour :

**1. L'identifiant du datapack.** `FloatingIslandsProvider.java:31`

```java
private static final Identifier SETTINGS_ID = Identifier.of("aetherial_islands", "floating");
```

Un renommage côté datapack casse la création de dimension, et bruyamment : le
`orElseThrow` de la ligne 45 produit un message qui nomme l'identifiant attendu, et
CheeseGuard retombe sur un générateur de vide. C'est le mode d'échec qu'on veut, et c'est
le seul des quatre qui s'annonce.

**2. Les descripteurs de méthodes vanilla.** `NoiseChunkGeneratorMixin.java:45` et `:59`,
`SpringFeatureMixin.java:23`, `LakeFeatureMixin.java:20`. Descripteurs complets,
remappés via le refmap, avec `defaultRequire: 1`. Sur une version de Minecraft où l'une
de ces signatures a changé, le mixin ne s'applique pas et le jeu **crashe au lancement**
avec une erreur d'application de mixin qui nomme la méthode. Bruyant, et c'est le bon
comportement : ce mod n'a pas de mode dégradé sensé pour un fond marin manquant.

**3. Le shadow de `settings`.** `NoiseChunkGeneratorMixin.java:41`

```java
@Shadow @Final
private RegistryEntry<ChunkGeneratorSettings> settings;
```

Un champ privé du vanilla, lu pour obtenir le niveau de la mer dans l'injection sur
`carve`. Même classe d'échec que les descripteurs, même franchise.

**4. Les deux noms de classes tierces.** `RaidDenFeatureMixin.java:22` et
`MinerallyFeatureMixin.java:26-31`. Cinq noms de classes pleinement qualifiés
appartenant à deux mods, avec `require = 0`. **Ceux-là échouent en silence.** Si
Minerally renomme `CrystalFeature` ou déplace son package, rien n'est écrit dans le log,
rien ne crashe, et le seul symptôme est le retour de ce que le mixin empêchait : dans le
cas de Minerally, des timeouts du watchdog pendant la génération de chunks dans une
dimension flottante. Vérifie-les en dernier, mais vérifie-les avec un profileur ou un log
rempli d'avertissements `Empty height range`, pas en cherchant une erreur.

## Points d'extension

**Écrire ton propre type de génération.** Ce mod existe en partie comme implémentation de
référence, et la recette est courte. Implémente `com.cheeseguard.api.GenTypeProvider`,
déclare la classe sous l'entrypoint `cheeseguard-gentype` dans ton `fabric.mod.json`, et
dépends de `cheeseguard`. Renvoie un `id()` avec espace de noms, puisque le dashboard de
CheeseGuard distingue les types plugin des types intégrés en cherchant un `:` dans la
valeur. Sers-toi de `CheeseGuardWorldgenApi` pour les parties difficiles :
`freshOverworldBiomeSource` si tu veux des biomes overworld avec ta propre seed,
`withOverworldSurfaceRule` si tu veux que les surface rules moddées s'appliquent,
`ensureTerraBlenderReady` si tu enregistres un monde toi-même au lieu de laisser faire
CheeseGuard. Déclare la dépendance en `modCompileOnly` sur un glob `fileTree` de
`../CheeseGuard/build/libs`, comme le fait le `build.gradle` de ce mod, pour que rien de
CheeseGuard ne soit embarqué dans ton jar.

**Changer le fond marin.** Tout est dans `aetherialbridge$forceSeafloor` et
`aetherialbridge$decorate`. Les deux reçoivent leurs entrées en paramètres et n'écrivent
que par `chunk.setBlockState` : une réécriture reste donc autonome. Garde le garde de
niveau de la mer en tête de méthode, garde les écritures dans l'emprise 16×16 du chunk,
et n'introduis pas de champ.

**Supprimer une autre feature qui se tient mal.** `MinerallyFeatureMixin` est le patron :
`@Pseudo`, `remap = false`, les deux noms de méthode listés, `require = 0`, tout le corps
dans un `try/catch (Throwable)`, et la décision prise sur
`ctx.getGenerator().getSeaLevel()`. Copie-le, change les cibles, et ajoute la classe à
`aetherialbridge.mixins.json`.

## Faiblesses connues

Dites franchement, parce que les découvrir par surprise est pire.

- **Les deux pseudo-mixins échouent en silence.** `require = 0` est ce qui rend le mod
  chargeable sans Minerally ni CobblemonRaidDens, et c'est aussi ce qui rend un
  renommage de classe indétectable. Aucune ligne de log au démarrage ne dit si l'un ou
  l'autre s'est lié.
- **Le seuil de -25 est un littéral, répété dans quatre fichiers.** Rien ne le partage,
  rien ne le journalise, et rien ne vérifie que le niveau de la mer du datapack est bien
  en dessous. Une modification du datapack qui remonte le niveau de la mer désactive
  silencieusement toute la chaîne de réparation.
- **Le garde porte sur le niveau de la mer, pas sur la dimension.** Toute autre dimension
  à niveau de mer bas du serveur hérite du fond marin forcé, sans moyen de s'y soustraire
  autrement qu'en recompilant.
- **Deux commentaires javadoc portent de mauvais nombres.** `SpringFeatureMixin` et
  `LakeFeatureMixin` disent que le seuil est -50, et `SpringFeatureMixin` ajoute que la
  dimension est à -55. Le code dit -25 et le datapack dit -30 — c'est le javadoc de
  `MinerallyFeatureMixin` (« < -25 », « à -30 ») qui est juste. Crois le code.
- **Une recherche de settings en échec dégénère en monde vide par-dessus de vrais
  fichiers de région.** CheeseGuard attrape l'exception du provider et retombe sur un
  générateur plat de vide. Les chunks déjà sur disque se chargent avec leur terrain ; les
  nouveaux sortent vides. La seule trace est deux lignes de log, et rien dans le
  dashboard ne dit que la dimension génère de travers.
- **Le fond marin ignore le biome.** Le même mélange sable, gravier, argile et prismarine
  apparaît sous une île de désert et sous une taïga enneigée. Aucune variation par biome,
  par choix, mais ça fait étrange dans un biome froid.
- **La passe de décoration n'a pas été profilée.** Elle parcourt 256 colonnes avec
  exactement 35 appels à `setBlockState` chacune pour la bedrock, le fond et l'eau (chaque
  y de `min_y` au niveau de la mer, avec ce datapack), plus la
  décoration, sur chaque chunk de la dimension. C'est de l'arithmétique peu coûteuse et
  bornée, mais personne n'a mesuré le coût par chunk, et la génération de chunks dans une
  dimension flottante est nettement plus lente que dans l'overworld.
- **La dimension survit au mod.** Retire le jar et CheeseGuard garde la dimension
  sauvegardée, en la résolvant vers un générateur de vide à chaque démarrage. Voir
  [désinstallation](installation.fr.md#désinstallation).
- **Testé dans exactement un modpack.** Il a été écrit pour la liste de mods d'un seul
  serveur, avec un datapack repackagé précis, contre une version de Minerally et une de
  CobblemonRaidDens. Rien n'a été testé contre le datapack Modrinth standard, parce que
  cette configuration ne peut pas fonctionner par construction.

---

← [Génération du monde](worldgen.fr.md) · [Dépannage](troubleshooting.fr.md) →
