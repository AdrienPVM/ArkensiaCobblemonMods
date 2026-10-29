[← CheeseCompatModsFix](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](fixes-safari-and-bosses.md)

# Catalogue des correctifs — Safari, Academy et boss

> ⚠️ La famille CobbleSafari existe parce que CobbleSafari 0.1.5 a été bâti pour Cobblemon
> 1.7.1 et que le serveur tourne en 1.6.1. Ce ne sont pas des corrections de bugs, c'est un
> rétroportage, tenu par de la réflexion et un `@Overwrite`. Rien ici ne survit à une mise
> à jour de CobbleSafari.

**Mods visés sur cette page**

| Mod | Id du mod | Version visée à la compilation |
|---|---|---|
| CobbleSafari | `cobblesafari` | 0.1.5 (Fabric), id Modrinth `XANyMPaV` |
| StarAcademy | `academy` | `academy-mc1.21.1-v1.4.0+build.467-fabric.jar`, depuis `localdeps/` |
| Cobblewarden Boss | `cobblewarden_boss` | `cobblewarden_boss-fabric-1.0.0.jar`, patché à part |

---

## CobbleSafari sur le mauvais Cobblemon

CobbleSafari 0.1.5 vise Cobblemon 1.7.1. Deux de ses APIs n'existent pas en 1.6.1 :

- `SpawnablePosition`, employée par `RepelInfluence` et `BucketBoostInfluence`, est une
  classe 1.7.
- `BattleStartedEvent.Pre`, employée par `DimensionalBanEventHandler`, s'appelait
  `BattleStartedPreEvent` en 1.6.1.

Laissé tel quel, `CobbleSafari.initLogic()` lève une exception en traversant ces
enregistrements et le mod entier échoue à s'initialiser — plus de zones safari, plus de
mécaniques de capture, plus d'incubateur, plus de donjons. Les trois correctifs ci-dessous
retirent les deux enregistrements cassés et remettent des remplaçants au format 1.6.1.

### 1. Sauter les enregistrements cassés

**Moyen.** `CobbleSafariInitMixin` — `@Overwrite` sur `CobbleSafari.initLogic()`. Il
reproduit la méthode d'origine moins l'enregistrement des `SpawningInfluence` et moins
l'enregistrement des évènements de ban dimensionnel. Tout le reste de ce que CobbleSafari
fait dans `initLogic` se produit encore.

**Inerte en l'absence du mod.** Oui — conditionné à `cobblesafari` dans
`CheeseCompatMixinPlugin`.

**État.** Actif.

⚠️ Un `@Overwrite` sur l'initialiseur d'un mod tiers est la chose la plus fragile de ce
dépôt. Tout changement que CobbleSafari apportera à `initLogic` — un nouvel
enregistrement, un réordonnancement, un appel renommé — est jeté en silence par ce mixin,
et le symptôme sera « une fonctionnalité de CobbleSafari a cessé d'exister », sans rien
dans le log.

### 2. Influences de spawn, refaites pour 1.6.1

**Moyen.** `SafariSpawnInfluenceFix` enregistre deux influences de remplacement via
`PlayerSpawnerFactory`, en utilisant `java.lang.reflect.Proxy` pour implémenter l'interface
`SpawningInfluence` de Cobblemon. Le Proxy est là pour éviter de compiler contre des noms de
classes Minecraft intermédiaires (`class_3218`, `class_2338`, `class_1297`) que le montage
Cobblemon en `compileOnly` ne peut pas résoudre.

| Influence | Comportement |
|---|---|
| BucketBoost | multiplie les poids des seaux de spawn peu commun / rare / ultra-rare quand un effet PC de CobbleSafari est actif |
| Repel | bloque les apparitions de Pokémon dans un rayon de 80 blocs quand l'effet `REPEL` est actif |

Les ids d'effets viennent de `maxigregrze.cobblesafari.init.ModEffects` et les multiplicateurs
de `maxigregrze.cobblesafari.config.SpawnBoostConfig`, tous deux résolus par nom.

**État.** Actif.

### 3. Bans dimensionnels, refaits pour 1.6.1

**Symptôme sans le correctif.** Dans une dimension safari, les joueurs peuvent lancer des
combats, envoyer des Pokémon et pêcher, trois choses que les règles du safari interdisent.

**Moyen.** `SafariBanFix` s'abonne à trois évènements Cobblemon en priorité `HIGHEST`,
entièrement par réflexion :

- `BATTLE_STARTED_PRE`
- `POKEMON_SENT_PRE`
- `POKEROD_CAST_PRE`

Chaque gestionnaire interroge `BannedItemsManager.isBattleAllowed(RegistryKey)` — la table
de règles de CobbleSafari lui-même — et annule si la réponse est non. Les gestionnaires
sont construits comme des proxys Kotlin `Function1` renvoyant `kotlin.Unit.INSTANCE`, et la
méthode `subscribe(Priority, Function1)` est localisée en cherchant une méthode `subscribe`
à deux paramètres plutôt que par correspondance de signature, parce que la signature est
générée par Kotlin.

**État.** Actif. L'échec est bruyant ici : tout le `register()` est entouré d'un try/catch
qui journalise en `ERROR`. Les bans ne s'appliquent alors simplement pas, ce qui est une
régression de gameplay et non un crash.

### 4. Le téléporteur ne marchait que depuis l'Overworld

**Symptôme.** Marcher sur un `cobblesafari:safari_teleporter` hors de l'Overworld — dans
`cheeseguard:arkensia` par exemple — ne fait rien.

**Moyen.** `SafariTeleporterMixin` — `@Inject` en `HEAD` de
`SafariTeleporterBlock.onEntityCollision`, `cancellable`, qui autorise la téléportation
depuis toute dimension qui n'est pas elle-même une dimension safari.

**État.** Actif.

### 5. Les portails de donjon n'apparaissaient que dans l'Overworld

**Moyen.** `DungeonPortalSpawnMixin` sur `PortalSpawnManager`, deux injections en `HEAD`,
toutes deux `cancellable` avec `require = 0` :

- `trySpawnRandomPortal` — tire un joueur au hasard parmi tous les joueurs en ligne plutôt
  que parmi ceux de l'Overworld uniquement.
- `spawnPortalNearPlayer(Lnet/minecraft/class_3222;Z)Z` — la signature est écrite en forme
  intermédiaire parce que le mixin tourne avec `remap = false`.

Les portails sont autorisés dans toutes les dimensions **sauf** les `cobblesafari:*`, pour
qu'un portail de donjon ne puisse pas apparaître dans un donjon.

**État.** Actif.

### 6. Une seconde dimension de donjon — non enregistrée

`DungeonDimensionsMixin` est une **classe de mixin vide**. Elle est déclarée dans
`cheesecompatmodsfix.mixins.json`, elle cible `DungeonDimensions`, et elle ne contient
qu'un commentaire : `dungeon_jump` n'est délibérément pas enregistré, parce que CobbleSafari
0.1.5 a les fichiers de dimension mais aucune structure de worldgen pour elle — pas de
template pools, pas de pièce de départ. L'enregistrer créerait une dimension vide.

**État.** Inerte par construction. C'est du code mort qui documente une décision. Charger
un mixin vide coûte une transformation de classe et rien d'autre, mais c'est le genre de
chose à supprimer à la prochaine passe.

### 7. Blocs Cobblemon 1.7 manquants

**Symptôme.** La dimension souterraine de CobbleSafari échoue à se générer, parce que son
worldgen référence `cobblemon:tatami_mat` et `cobblemon:tatami_block` — des blocs ajoutés
en Cobblemon 1.7.x et absents en 1.6.1.

**Moyen.** `CheeseCompatMod.registerCobblemonMissingBlocks()` enregistre les deux sous
l'espace de noms `cobblemon` : `tatami_mat` comme `CarpetBlock` sans collision,
`tatami_block` comme bloc plein, tous deux en `MapColor.PALE_YELLOW`, dureté `0.1f`,
`BlockSoundGroup.WOOL`, chacun avec son `BlockItem`. Modèles, blockstates, textures et
tables de butin des deux sont livrés dans
`src/main/resources/assets/cobblemon/` et `src/main/resources/data/cobblemon/`.

Deux gardes : la méthode entière rend la main tôt si CobbleSafari est absent, et encore si
`cobblemon:tatami_mat` est déjà dans le registre des blocs — tourner sur Cobblemon 1.7, où
les blocs existent en amont, est donc un no-op.

**État.** Actif. Enregistrer des blocs dans **l'espace de noms d'un autre mod** n'est pas
quelque chose à copier à la légère. Ça marche parce que les registres de Minecraft ne se
soucient pas de qui enregistre un id, et ça entrera en conflit le jour où Cobblemon
enregistrera les siens selon l'ordre de chargement de ce serveur.

### 8. Un bloc de portail pour le safari

Pas un correctif — un ajout, groupé ici parce qu'il ne se charge qu'avec CobbleSafari.

`CobbleSafariBlocks` enregistre deux blocs dans l'espace de noms propre au mod,
`cheesecompat` : `cheesecompat:cobblesafari_portal_frame` (avec un item) et
`cheesecompat:cobblesafari_portal`. Un `UseBlockCallback` allume un cadre. La détection de
cadre dans `CobbleSafariPortalHelper` suit le motif du portail du Nether vanilla : largeur
de `MIN_WIDTH` 2 à `MAX_WIDTH` 21, hauteur de `MIN_HEIGHT` 3 à `MAX_HEIGHT` 21. Entrer dans
le portail exécute la même logique de téléportation que marcher sur
`cobblesafari:safari_teleporter`, avec un délai de message par joueur pour qu'un joueur
immobile dans le portail ne soit pas spammé.

Les noms de blocs viennent des fichiers de langue :

```text
block.cheesecompat.cobblesafari_portal_frame  →  Cadre de Portail CobbleSafari
block.cheesecompat.cobblesafari_portal        →  Portail CobbleSafari
```

**État.** Actif.

⚠️ **Bruit de log connu et inoffensif.** Les blocs sont enregistrés conditionnellement mais
leur table de butin est livrée inconditionnellement, donc sur un serveur sans CobbleSafari
le log porte :

```text
Couldn't parse element minecraft:loot_table/cheesecompat:blocks/cobblesafari_portal_frame
  - Unknown registry key ...: cheesecompat:cobblesafari_portal_frame
```

Le serveur démarre normalement. C'est une table de butin orpheline, pas un échec.

### 9. Pas de capture dans le donjon

**Symptôme visé.** Les joueurs capturaient les Pokémon du spawner dans le donjon
souterrain au lieu de les combattre.

**Moyen.** `DungeonCaptureFix` enregistre un `UseItemCallback` qui, dans
`cobblesafari:dungeon_underground` uniquement, fait échouer tout item dont l'id commence par
`cobblemon:` et contient `ball`, avec `cheesecompat.dungeon.capture_blocked` :

```text
Vous ne pouvez pas capturer de Pokémon dans le donjon !
```

**État.** Actif. La correspondance d'item est un test de sous-chaîne, donc un futur item
Cobblemon avec `ball` dans son nom et aucun comportement de jet serait bloqué aussi.

---

## StarAcademy

### 10. Balles safari bloquées dans les mauvaises dimensions

**Symptôme.** Une balle safari ne fait rien dans une dimension CobbleSafari — c'est-à-dire
exactement là où elle devrait marcher.

**Cause.** StarAcademy restreint les balles safari à `academy:safari` via un mixin sur
`Item.use()`.

**Moyen.** `SafariBallFix` enregistre un `UseItemCallback`, qui se déclenche **avant**
`Item.use()` et contourne donc entièrement le mixin d'Academy. Dans une dimension dont l'id
commence par `cobblesafari:`, avec en main un item dont l'id contient `safari_ball`, il
invoque `PokeBallItem.throwPokeBall(World, ServerPlayerEntity)` par réflexion (la méthode
est trouvée en balayant les méthodes déclarées par nom et nombre de paramètres égal à 2),
décrémente le stack sauf si le joueur est en créatif, et renvoie un succès.

**État.** Actif. Contourner la restriction d'un autre mod en passant devant lui avec un
évènement plus précoce mérite un nom : ça ne combat pas son mixin, ça le rend inatteignable.
Ça veut dire aussi que la règle d'Academy est toujours là et s'applique toujours partout
ailleurs.

### 11. Lance-pierre dans le safari

**Moyen.** `SlingshotBlockFix`, également un `UseItemCallback` : dans toute dimension
`cobblesafari:`, un item dont l'id est exactement `academy:slingshot` échoue, avec un
message en barre d'action `cheesecompat.slingshot.blocked` :

```text
Le lance-pierre ne peut pas être utilisé ici !
```

**État.** Actif.

### 12. Lance-pierre tuant des boss incapturables

**Symptôme.** Un boss Pokémon apparu comme incapturable est supprimé par un tir de
lance-pierre.

**Moyen.** `AcademySlingshotProtectionMixin` —
`@Mixin(targets = "abeshutt.staracademy.entity.SlingshotEntity", remap = false)`,
`@Inject` en `HEAD` de `method_7454` (`ProjectileEntity.onEntityHit`), `cancellable`. Tous
les types de la signature du gestionnaire sont du Minecraft standard (`EntityHitResult`,
`Entity`), et la vérification d'incapturabilité est faite par réflexion, pour que javac
n'ait jamais à résoudre les supertypes intermédiaires de `PokemonEntity` (`class_1471`,
`class_5147`).

**Inerte en l'absence du mod.** Oui — conditionné à `academy` dans le plugin mixin. C'est
aussi le mixin qui fait du jar StarAcademy un prérequis de build : le processeur
d'annotations Mixin valide une cible désignée par chaîne à la compilation, donc la classe
doit être sur le classpath même si elle est nommée et non importée.

**État.** Actif.

---

## Cobblewarden Boss

Ce mod de boss est le seul cas où les correctifs vivent à deux endroits : des mixins dans
ce jar, et des **patches bytecode appliqués au jar de l'autre mod**, conservés dans
`patches/cobblewarden/`.

### ⚠️ `patches/cobblewarden/` n'est pas du code de RoiCheese

`patches/cobblewarden/` porte des patches de compatibilité pour **Cobblewarden Boss de
LiquidSilverAce**, sous licence **CC0-1.0**. C'est la seule partie du dépôt qui soit le
travail de quelqu'un d'autre. Le `fabric.mod.json` patché qui s'y trouve garde
LiquidSilverAce comme auteur, garde `"license": "CC0-1.0"`, et garde les liens vers le
projet d'origine (`github.com/LiquidSilverAce/CobblewardenBoss`). L'attribution reste à
l'auteur ; les patches sont publiés sous les mêmes termes CC0 que le code qu'ils modifient,
pas sous la licence PolyForm de ce dépôt.

`patches/cobblewarden/README.md` documente quatre patches, en français, avec un détail
forensique complet — indices du constant pool, séquences d'octets exactes, et un script
Python pour régénérer chacun. En résumé :

| Patch | Effet |
|---|---|
| 1 | assouplit le bloc `depends` : `fabricloader >=0.17.3→>=0.16.0`, `cobblemon >=1.7.1→>=1.6.1`, `fightorflight >=0.10.2→>=0.9.0`, après un audit du bytecode confirmant que seules deux méthodes Cobblemon sont réellement appelées |
| 2 | transforme `EventResult.interruptFalse()` en `EventResult.pass()` dans `WardenSpawnInterceptor`, pour qu'un second warden dans une région ne soit plus annulé sans rien pour le remplacer |
| 3 | remplace `BossSpawner` entièrement, en appelant l'API Cobblemon directement au lieu d'exécuter `/pokespawnat` — la voie par commande crashait avec une NPE dans `cobblemon_spawn_alerts` |
| 4 | neutralise entièrement la vérification `AncientCityTracker.hasSpawned`, pour que **chaque** warden déclenche un boss quel que soit l'état du cache régional |

Deux notes de ce README méritent d'être extraites, parce que ce sont des leçons générales :

- `AncientCityTracker.hasSpawned()` indexe son cache sur `(x/128)_(y/64)_(z/128)`, ce qui
  marque toute une région de 128×64×128 comme « déjà apparue » pour la durée de vie du
  monde. Un boss tué des semaines plus tôt gelait tous les wardens futurs de cette région.
  Les clés de cache grossières ont l'air inoffensives jusqu'à ce que ce qu'on met en cache
  soit un évènement unique.
- Le patch 4 ne pouvait pas s'écrire avec un `goto`. Remplacer la vérification par
  `goto 51` crashe le serveur au chargement de classe avec
  `VerifyError: Expecting a stack map frame`, parce que `goto` est une branche
  inconditionnelle et que la JVM exige un `StackMapFrame` à l'instruction qui suit — même
  si cette instruction est du code mort. La forme qui marche est `iconst_0` suivi du `ifeq`
  d'origine : même effet au runtime (0 == 0, branche toujours) mais ça reste une branche
  *conditionnelle*, dont le fall-through n'a besoin d'aucune frame.

⚠️ `patches/cobblewarden/README.md` référence
`patches/cobblewarden/classes/com/ace/cobbleboss/WardenSpawnInterceptor.class` et un
`BossSpawner.class` patché. **Ces fichiers `.class` ne sont pas dans le dépôt** — seuls le
README et le `fabric.mod.json` patché y sont. Il faudrait les régénérer depuis les scripts
Python de ce README.

### 13. `BossSpawner` — dans cet arbre source, pas dans ce jar

`src/main/java/com/ace/cobbleboss/BossSpawner.java` est dans le package
`com.ace.cobbleboss`, qui est **le package propre à Cobblewarden Boss**, pas celui de ce
mod. Il existe pour être compilé ici puis *extrait* et injecté dans le jar de l'autre mod
(patch 3). Le jar `cheesecompatmodsfix` porte la classe mais ne l'appelle jamais.

Il fait apparaître le boss via l'API Cobblemon par réflexion —
`PokemonProperties.Companion.parse`, `props.createEntity(level)`,
`serverLevel.spawnEntity(entity)` — au lieu de passer par `/pokespawnat`, puis cherche le
joueur le plus proche dans un rayon de 64 blocs et appelle `mob.setTarget(player)`. Cette
dernière étape existe parce que `fightorflight.do_pokemon_attack_unprovoked=false` supprime
l'agressivité spontanée, mais un `setTarget` dirigé n'est pas « non provoqué », donc l'IA
bascule en combat dès l'apparition.

**État.** Compilé, inutilisé par ce mod, utilisé par le jar patché.

### 14. Apparition immédiate du boss sur un cri de sculk

**Moyen.** `SculkShriekerBossSpawnMixin` — `@Inject` en `HEAD` de
`SculkShriekerBlockEntity.shriek`, `cancellable`. La cible est un block entity **vanilla**.
Il ignore les joueurs en créatif et en spectateur, vérifie `SculkShriekerBlock.CAN_SUMMON`
sur l'état en cache, puis lance la séquence du boss — via
`com.ace.cobbleboss.PortalAnimator.startPortalCreation` quand cette classe est présente, ou
une apparition directe en repli.

L'état vit dans `ShriekerBossState`, persisté dans
`config/cheesecompatmodsfix_shrieker_cooldowns.json` :

| Constante | Valeur | Signification |
|---|---|---|
| `COOLDOWN_MILLIS` | 10 minutes | délai par centre entre deux apparitions de boss |
| `COOLDOWN_RADIUS` | 200 blocs | rayon couvert par un centre de délai |
| `AGGRO_SCAN_RADIUS` | 96 blocs | distance de recherche des boss à re-cibler |
| `AGGRO_TARGET_RADIUS` | 256 blocs | distance à laquelle un boss accepte encore une cible |
| `TELEPORT_DIST_THRESHOLD` | 24 blocs | distance au-delà de laquelle un boss est téléporté vers sa cible |
| `TELEPORT_MAX_DIST` | 256 blocs | au-delà, aucune téléportation |
| `TELEPORT_COOLDOWN_MS` | 5000 ms | écart minimum entre deux téléportations d'un boss |

**Inerte en l'absence du mod.** Oui — conditionné à `cobblewarden_boss`.

**État.** Actif.

### 15. Des boss qui perdent leur agressivité

**Moyen.** `ShriekerBossAggroKeeper`, sur `END_SERVER_TICK`, toutes les 2 secondes : pour
chaque centre de délai actif, trouver les boss Pokémon incapturables apparus à portée et
re-cibler de force le joueur vivant le plus proche. Ça garde un boss invoqué par un cri
hostile après la mort ou la réapparition de sa cible d'origine.

**État.** Actif.

### 16. Aura de ténèbres autour d'un boss

**Moyen.** `BossDarknessAura`, sur `END_SERVER_TICK` : toutes les `APPLY_INTERVAL_TICKS`
(20, une seconde), chaque joueur non spectateur et non créatif dans un `RADIUS` de 50 blocs
d'une entité taguée `cheesecompat_boss` reçoit `DARKNESS` pour `EFFECT_DURATION_TICKS` (60,
trois secondes). La durée est plus longue que l'intervalle, donc l'effet est continu tant
qu'on reste à portée et s'estompe tout seul quelques secondes après être sorti — pas de
durée infinie, pas de comptabilité de nettoyage.

**État.** Actif.

---

← [Catalogue — Cobblemon et les combats](fixes-cobblemon.fr.md) · [Catalogue — Daycare+](fixes-daycare.fr.md) →
