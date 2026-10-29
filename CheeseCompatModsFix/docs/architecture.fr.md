[← CheeseCompatModsFix](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](architecture.md)

# Architecture

64 fichiers source, environ 7 456 lignes. Il n'y a pas de modèle de domaine ici, et pas de
sous-système au sens habituel : le mod est une collection plate de rustines indépendantes
qui partagent un initialiseur, un fichier de configuration et une config mixin. Lis cette
page pour la forme du contenant ; lis les [pages de catalogue](../README.fr.md#le-catalogue-des-correctifs)
pour ce qu'il y a dedans.

---

## Arborescence des packages

```text
com.cheesecompat
├── CheeseCompatMod.java              l'unique entrypoint ; relie chaque correctif, possède la config
├── CheeseCompatConfig.java           trois booléens, config/cheesecompatmodsfix.json
├── CheeseCompatMixinPlugin.java      décide quels mixins s'appliquent, depuis des isModLoaded
├── CheeseCompatClient.java           activateur de packs de ressources client — NON relié
│
├── BattleTowerDisconnectFix.java     sauvegarde/restauration d'équipe à la déconnexion (512 lignes)
├── RadGymsFix.java                   quatre correctifs Rad Gyms, tous par réflexion (413 lignes)
├── RaidDenFix.java                   cristaux de raid qui ne se referment jamais (377 lignes)
├── RctForcedSwitchFix.java           chien de garde de combat — DÉSACTIVÉ, voir plus bas (276 lignes)
├── CacademyMegaStoneRemapPatch.java  42 méga-gemmes custom → ids Showdown (241 lignes)
├── SafariSpawnInfluenceFix.java      influences de spawn 1.6.1, via java.lang.reflect.Proxy
├── SafariBanFix.java                 ré-enregistre trois évènements Cobblemon pour 1.6.1
├── SafariBallFix.java                balles safari dans les dimensions CobbleSafari
├── SlingshotBlockFix.java            bloque academy:slingshot dans les dimensions CobbleSafari
├── DungeonCaptureFix.java            bloque les Poké Balls dans le donjon du safari
├── EnhancedCelestialsWarmup.java     amorce la donnée lunaire paresseuse d'EC par niveau
├── MegaShowdownAspectPatch.java      ajoute « mega_z » à l'ensemble d'aspects de MegaShowdown
│
├── block                             les blocs de portail CobbleSafari ajoutés par ce mod
│   ├── CobbleSafariBlocks.java
│   ├── CobbleSafariPortalBlock.java
│   └── CobbleSafariPortalHelper.java
├── cobbleboss                        comportements autour des boss Cobblewarden
│   ├── ShriekerBossState.java        constantes + persistance des délais
│   ├── ShriekerBossAggroKeeper.java  re-cible les boss toutes les 2 s
│   └── BossDarknessAura.java         DARKNESS dans un rayon de 50 blocs d'un boss
├── daycare
│   └── DaycareSimpleModeStore.java   état de reproduction ponctuelle, indexé par dim + BlockPos
└── mixin                             35 mixins serveur + 4 mixins client

com.ace.cobbleboss
└── BossSpawner.java                  le package d'un autre mod ; compilé ici, injecté là-bas
```

`com.ace.cobbleboss` n'est pas une faute de frappe. C'est le package propre à Cobblewarden
Boss, et `BossSpawner` est écrit ici pour être extrait de ce jar et injecté dans le leur.
Rien dans ce mod ne l'appelle. Voir
[le catalogue des boss](fixes-safari-and-bosses.fr.md#13-bossspawner--dans-cet-arbre-source-pas-dans-ce-jar).

## Qui possède quoi

**`CheeseCompatMod`** est l'intégralité du câblage. `onInitialize()` exécute, dans l'ordre :
charger la config, enregistrer les deux blocs Cobblemon manquants, enregistrer
`/stopbattle`, planifier la restriction de `/stopbattle <player>`, puis sept blocs
`isModLoaded`, puis l'amorçage Enhanced Celestials et les deux patches MegaShowdown
inconditionnellement, puis une ligne de log. Il n'y a pas de registre, pas de gestionnaire
et pas de répartiteur — chaque correctif enregistre ses propres gestionnaires d'évènements
et se débrouille ensuite.

**`CheeseCompatConfig`** porte trois booléens et rien d'autre. Il charge depuis
`config/cheesecompatmodsfix.json` à l'init et **réécrit immédiatement après avoir chargé**,
pour qu'un champ ajouté dans une nouvelle version apparaisse dans le fichier au premier
démarrage avec cette version. Il est lu via un `CheeseCompatMod.getConfig()` statique,
jamais rechargé, donc modifier le fichier sur un serveur en marche ne fait rien. Détails
dans [configuration](configuration.fr.md).

**`CheeseCompatMixinPlugin`** est la seule pièce de machinerie partagée qui mérite d'être
comprise, et elle a sa propre section ci-dessous.

**Tout le reste est une feuille.** Aucun correctif ne dépend d'un autre, à trois exceptions
près, toutes dans la famille Battle Tower : `BattleTowerManagerMixin` interroge
`BattleTowerDisconnectFix.isRestorePending(...)`, `TeamFullRestoreMixin` écrit sa
sauvegarde via `BattleTowerDisconnectFix.writeBackupFromSnapshot(...)`, et
`SculkShriekerBossSpawnMixin` lit et écrit `ShriekerBossState`. Si tu supprimes un
correctif, cherche son nom de classe avant de supposer que rien d'autre ne l'utilisait.

## Comment les mixins sont éteints

`cheesecompatmodsfix.mixins.json` liste 35 mixins serveur et 4 mixins client, et désigne
`com.cheesecompat.CheeseCompatMixinPlugin` comme plugin. Ce plugin est le mécanisme qui rend
un mod rempli de mixins vers des mods tiers installable sans danger sur un serveur qui n'a
aucun de ces mods.

Il évalue quatorze vérifications `FabricLoader.isModLoaded(...)` **une seule fois**, dans
des booléens `static final`, puis `shouldApplyMixin(targetClassName, mixinClassName)` filtre
sur des **sous-chaînes du nom de la classe de mixin** et renvoie le booléen correspondant :

| Le nom contient | Conditionné à |
|---|---|
| `BattleTower`, `TeamSnapshot`, `NetworkHelper`, `TeamFullRestore`, `BattleArenaPosition`, `BattleTowerChunkPreload` | `cobblemon_battle_tower` |
| `ArcPhone`, `Sanctuary`, `EntrepreneurProfessionLock` | `legendarymonuments` |
| `OrePile` | `minerally` |
| `CobbleSafari`, `SafariTeleporter`, `DungeonPortalSpawn`, `DungeonDimensions` | `cobblesafari` |
| `TrialsNature` | `cobblemontrialsedition` |
| `RaidTera`, `RaidBoss`, `RaidFire`, `RaidEntity` | `cobblemonraiddens` |
| `Daycare`, `Fertility` | `daycareplus` |
| `AcademySlingshot` | `academy` |
| `SculkShriekerBossSpawn` | `cobblewarden_boss` |
| `EnhancedCelestialsFactoryFallback` | `enhancedcelestials` |
| `FoFOwnedGiratinaNoAggro` | `fightorflight` |
| `ExtraQuestsTimerResetGuard` | `extraquests` |
| `JourneyMapSeavlianIcon`, `IconDataAccessor` | `journeymap` |
| `NumismaticShopCull` | `numismatic-overhaul` |

La branche par défaut renvoie `true`. C'est important : un mixin dont le nom ne correspond à
aucun de ces motifs est **toujours appliqué**. Exactement un mixin tombe dedans —
`AlphaScaleHitboxFixMixin` — et il compte sur `require = 0` et une cible par chaîne pour
être inoffensif sans Cobblemon. Trois autres visent délibérément des classes vanilla
(`RaidFireSpreadMixin` sur `FireBlock`, `EntrepreneurProfessionLockMixin` sur
`VillagerEntity`, `SculkShriekerBossSpawnMixin` sur `SculkShriekerBlockEntity`) et sont de
toute façon conditionnés par le tableau ci-dessus.

⚠️ **Le filtrage par sous-chaîne est le point faible.** Nomme un nouveau mixin
`BattleTowerFooFix` et il est silencieusement conditionné à Battle Tower ; nomme-le
`FooBattleTowerFix` et c'est pareil. Nomme-le de façon à ne correspondre à aucun motif et il
s'applique inconditionnellement. Il n'y a aucune erreur pour l'une ou l'autre faute. Quand
tu ajoutes un mixin, ajoute sa condition dans `shouldApplyMixin` dans le même commit, et
choisis un nom qui contient le mot-clé de la famille.

La config pose aussi `"injectors": { "defaultRequire": 0 }`, donc **une injection échouée
est un mixin ignoré, pas un crash**, pour tout mixin qui ne surcharge pas ce réglage. C'est
le bon défaut pour un mod comme celui-ci : un mixin qui ne correspond plus doit dégrader
vers « le correctif a cessé de marcher », pas vers « le serveur ne démarre pas ». Le prix,
c'est qu'un correctif cassé est silencieux. `JourneyMapSeavlianIconMixin` est la seule
exception, avec `require = 1`.

## État et disque

Rien dans ce mod n'est une sauvegarde gérée ; chaque fichier est écrit par le correctif qui
le possède.

| Chemin | Propriétaire | Écrit quand | Format |
|---|---|---|---|
| `config/cheesecompatmodsfix.json` | `CheeseCompatConfig` | au chargement à l'init, et à la création | Gson indenté |
| `config/cheesecompat_bt_backups/<uuid>.dat` | `BattleTowerDisconnectFix` | au verrouillage d'équipe Battle Tower | NBT compressé, renommage atomique depuis `.dat.tmp` |
| `config/cheesecompat_daycare.json` | `DaycareSimpleModeStore` | sur `SERVER_STOPPING` et au fil des changements d'état | JSON |
| `config/cheesecompatmodsfix_shrieker_cooldowns.json` | `ShriekerBossState` | au fil de la pose des délais | JSON |

Deux détails à connaître. `CheeseCompatConfig` résout son chemin en
`Path.of("config", "cheesecompatmodsfix.json")` — relatif au répertoire de travail, et
**non** via `FabricLoader.getConfigDir()`, que les trois autres emploient. Au démarrage
normal d'un serveur, c'est le même dossier. Avec un launcher qui démarre le serveur dans un
autre répertoire de travail, non, et la config atterrit ailleurs pendant que les trois
autres fichiers tombent au bon endroit.

Et les sauvegardes Battle Tower sont les seuls fichiers ici à contenir une donnée non
régénérable — c'est l'équipe d'un joueur. Le balayage à 30 jours sur `SERVER_STARTED` est la
seule chose qui les supprime, à part une restauration réussie.

## Threading

| Thread | Ce qui y tourne |
|---|---|
| Serveur (principal) | chaque injection de mixin, chaque gestionnaire d'évènement, chaque boucle de tick, `/stopbattle` |
| Rendu client | les quatre mixins client |
| Celui qu'emploie sgui | le gestionnaire de clic de la GUI Daycare+, qui écrit `DaycareSimpleModeStore` |

Il n'y a pas de thread HTTP et pas de pool de workers : ce mod n'enregistre aucun module
CheeseDash et ne démarre aucun thread. Les mixins tournent sur le thread qui a appelé la
méthode où ils sont injectés, c'est-à-dire ici toujours le thread serveur.

La concurrence qui existe est défensive plutôt qu'architecturale. Plusieurs correctifs
emploient une `ConcurrentHashMap` là où une map ordinaire suffirait — les maps « en attente »
et « tentatives » de `BattleTowerDisconnectFix`, les compteurs d'étage et de combats de
`BattleTowerManagerMixin`, la table de `DaycareSimpleModeStore`,
`ShriekerBossState.COOLDOWN_UNTIL`. L'une de ces maps est porteuse : `RadGymsFix`
**remplace** la `LinkedHashMap` `PLAYER_GYMS` de Rad Gyms par une `ConcurrentHashMap` par
réflexion, parce qu'un accès non synchronisé à une `HashMap` gelait le serveur dans `get()`.

Le seul vrai risque inter-threads est la GUI Daycare+ qui écrit le store de mode ponctuel
pendant que le tick du pâturage le lit. La map est concurrente et les valeurs sont deux
booléens, donc une lecture déchirée est impossible, mais il n'y a aucune garantie d'ordre
entre un clic et le tick suivant. En pratique, un clic qui arrive au milieu d'un tick prend
effet au suivant.

## La réflexion : le motif dominant

L'essentiel de ce mod parle aux autres mods par réflexion, pas par imports. Il y a deux
raisons distinctes, et les confondre mène au mauvais correctif.

**Raison une : le mod n'est pas du tout sur le classpath de compilation.** MegaShowdown,
CobblemonAlphas et Rad Gyms ne sont jamais déclarés dans `build.gradle`. Tout ce qui les
concerne passe par `Class.forName` sur un nom codé en dur.

**Raison deux : le mod *est* sur le classpath, mais en `compileOnly` et non en
`modCompileOnly`.** `compileOnly` ne remappe pas, donc une classe Cobblemon dont la
signature mentionne un type Minecraft arrive avec ce type sous forme **intermédiaire**
(`class_3218`, `class_2338`, `class_1297`), que javac ne peut pas résoudre contre les
mappings Yarn. Toucher une telle classe directement ne compile pas. Le code contourne ça de
trois façons, selon la forme du problème :

- **Réflexion sur la méthode**, quand il faut un appel — `SafariBallFix` qui cherche
  `throwPokeBall` par nom et nombre de paramètres, `BossSpawner` qui appelle
  `PokemonProperties.Companion.parse`.
- **`java.lang.reflect.Proxy`**, quand une interface doit être *implémentée* —
  `SafariSpawnInfluenceFix` qui construit des instances de `SpawningInfluence`,
  `SafariBanFix` qui construit des gestionnaires Kotlin `Function1`.
- **`Object` dans la signature d'un gestionnaire de mixin**, quand c'est le type d'un
  paramètre qui pose problème — `DaycareGUIToggleMixin` et `DaycareSimpleModeTickMixin` font
  tous deux ça.

`build.gradle` porte un commentaire sur presque chaque dépendance expliquant de laquelle des
deux il s'agit et pourquoi. Ces commentaires sont la documentation la plus utile du fichier
de build ; garde-les si tu y touches.

### Ne cherche jamais un nom Yarn par réflexion

C'est la règle la plus facile à enfreindre. Les noms de méthodes dans un mod Fabric compilé
sont **intermédiaires** au runtime, pas Yarn. `getDeclaredMethod("tick")` sur une classe
Minecraft échoue ; le nom est `method_5773`. Tu verras ce nom intermédiaire écrit
littéralement dans plusieurs mixins — `AlphaScaleHitboxFixMixin` vise `method_5773`
(`Entity.tick`), `FoFOwnedGiratinaNoAggroMixin` vise `method_6264` (`Goal.canUse`),
`AcademySlingshotProtectionMixin` vise `method_7454` (`ProjectileEntity.onEntityHit`), et
`DungeonPortalSpawnMixin` écrit un descripteur complet
`spawnPortalNearPlayer(Lnet/minecraft/class_3222;Z)Z`. Les quatre tournent avec
`remap = false`, raison pour laquelle Mixin ne traduit pas le nom Yarn à leur place.

Là où le nom d'une méthode ne peut vraiment pas être supposé, le code balaie au lieu de
deviner — `SafariBanFix.findSubscribeMethod` cherche une méthode à deux paramètres appelée
`subscribe` plutôt que de faire correspondre une signature générée par Kotlin.

## Inventaire des mixins

Groupés par mod visé, avec le détail de chacun dans le catalogue :

| Cible | Mixins | Catalogue |
|---|---|---|
| Battle Tower | `BattleTowerManagerMixin`, `TeamSnapshotMixin`, `TeamFullRestoreMixin`, `BattleTowerShutdownMixin`, `BattleArenaPositionMixin`, `BattleTowerChunkPreloadMixin`, `NetworkHelperImplMixin`, `client.BattleTowerScreenAspectMixin` | [Battle Tower](fixes-battle-tower.fr.md) |
| Cobblemon | `AlphaScaleHitboxFixMixin`, `RaidTeraBlockMixin` | [Cobblemon](fixes-cobblemon.fr.md) |
| Trials Edition | `TrialsNatureMixin` | [Cobblemon](fixes-cobblemon.fr.md) |
| Raid Dens | `RaidBossFacingMixin`, `RaidEntityClearMixin`, `RaidFireSpreadMixin` | [Cobblemon](fixes-cobblemon.fr.md) |
| Fight or Flight | `FoFOwnedGiratinaNoAggroMixin` | [Cobblemon](fixes-cobblemon.fr.md) |
| CobbleSafari | `CobbleSafariInitMixin`, `SafariTeleporterMixin`, `DungeonPortalSpawnMixin`, `DungeonDimensionsMixin` | [Safari](fixes-safari-and-bosses.fr.md) |
| StarAcademy | `AcademySlingshotProtectionMixin` | [Safari](fixes-safari-and-bosses.fr.md) |
| Cobblewarden Boss | `SculkShriekerBossSpawnMixin` | [Safari](fixes-safari-and-bosses.fr.md) |
| Daycare+ | `DaycareIVFixMixin`, `DaycareIVAverageMixin`, `DaycareFromParentsMixin`, `DaycareEggFertilityAvgMixin`, `DaycareStrictFertilityMixin`, `DaycareSimpleModeTickMixin`, `DaycareGUIToggleMixin`, `FertilityClampMixin` | [Daycare+](fixes-daycare.fr.md) |
| Legendary Monuments | `ArcPhoneHealMixin`, `ArcPhonePCMixin`, `SanctuaryDistanceMixin`, `EntrepreneurProfessionLockMixin` | [Tout le reste](fixes-misc.fr.md) |
| Minerally | `OrePileMixin` | [Tout le reste](fixes-misc.fr.md) |
| Enhanced Celestials | `EnhancedCelestialsFactoryFallbackMixin` | [Tout le reste](fixes-misc.fr.md) |
| ExtraQuests | `ExtraQuestsTimerResetGuardMixin` | [Tout le reste](fixes-misc.fr.md) |
| JourneyMap | `client.JourneyMapSeavlianIconMixin`, `client.IconDataAccessor` | [Tout le reste](fixes-misc.fr.md) |
| Numismatic Overhaul | `client.NumismaticShopCullMixin` | [Tout le reste](fixes-misc.fr.md) |

Une classe de mixin existe dans l'arbre source et **n'est pas** dans `mixins.json` :
`DaycareDestinyKnotConsumeMixin`. Mixin n'applique que ce que la config nomme, donc elle
n'est jamais chargée. Voir
[le catalogue Daycare+](fixes-daycare.fr.md#4-consommation-du-destiny-knot--écrite-jamais-activée).

## ⚠️ Le chien de garde : la cicatrice à lire

`RctForcedSwitchFix` est la seule pièce de ce mod à avoir fait des dégâts mesurables en
production, et elle est conservée dans l'arbre exprès, pour que le suivant ne la réécrive
pas.

C'était un chien de garde de combat, ajouté en **1.2.51**, bâti pour débloquer un bug réel :
quand plusieurs Pokémon tombent au même tick, le moteur Cobblemon émet une requête de choix
pour un acteur IA et ne la délivre jamais, donc `checkForInputDispatch()` attend
indéfiniment. Le chien de garde tournait sur `END_SERVER_TICK`, parcourait
`BattleRegistry.battleMap` par réflexion, et rejouait `AIBattleActor.onChoiceRequested()`
sur tout acteur qu'il jugeait figé.

Le jugement était faux. `isStuckNpcActor()` marquait **tout** acteur IA non joueur ayant une
requête en attente et une liste `responses` vide, après un debounce de 60 ticks — et c'est
l'état normal de chaque tour pendant que le joueur humain décide. Trois secondes de
réflexion suffisaient à le déclencher. Il rejouait `onChoiceRequested()` dans des combats
sains, sauvages comme dresseurs, désynchronisait Showdown et **gelait tous les combats du
serveur**. La forensique a ensuite montré qu'il ne débloquait même pas les cas de
force-switch pour lesquels il avait été bâti ; ceux-là revenaient en `Can't pass` rejeté.
Aucun bénéfice, des dégâts à l'échelle du serveur.

Il a été désactivé en **1.2.52** en commentant l'unique appel qui l'enregistrait, dans
`CheeseCompatMod.onInitialize()`. L'autopsie est dans ce commentaire. La classe compile
toujours et n'est atteignable de nulle part.

Ce qui mérite une section dans un document d'architecture plutôt qu'une ligne de changelog :
la classe était *bien faite* et pourtant catastrophique. Elle était fail-open, idempotente
par objet requête, cantonnée au thread serveur, et son propre javadoc signalait honnêtement
la seule chose dont elle n'était pas sûre (« l'efficacité réelle dépend de l'hypothèse… à
valider en jeu »). Rien de tout cela n'a aidé, parce que l'erreur était dans le **prédicat
de détection**, et un prédicat faux sur un chemin parcouru à chaque tour par chaque joueur ne
se dégrade pas gracieusement. L'ingénierie de sûreté autour d'une boucle chaude ne compense
pas le fait de ne pas savoir à quoi « cassé » ressemble.

Si tu écris un chien de garde de ce genre, trouve un signal qui n'apparaît **que** dans
l'état cassé — pour le force-switch, cela veut dire lire `request.getForceSwitch()` et rien
d'autre — et teste-le contre du trafic réel, parce que c'est sur le chemin sain que tu te
tromperas.

Sa cicatrice jumelle est dans
[le catalogue Cobblemon](fixes-cobblemon.fr.md#-2-le-mixin-sur-les-objets-tenus-refusé-jamais-retenté) :
un mixin sur la gestion des objets tenus de MegaShowdown, tenté en **1.2.37**, qui a cassé
tous les échanges d'objets tenus du serveur et qui n'est délibérément pas retenté.

## Points d'extension

- **Ajouter un correctif pour un mod déjà dans la liste.** Écris le mixin, ajoute-le à
  `cheesecompatmodsfix.mixins.json`, et vérifie que le nom de classe correspond à un motif
  existant de `shouldApplyMixin`. Rien d'autre n'est nécessaire.
- **Ajouter un correctif pour un nouveau mod.** Ajoute la constante `isModLoaded` et une
  branche dans `shouldApplyMixin` **d'abord**, puis le mixin. Ajoute la dépendance à
  `build.gradle` épinglée à un **id de version** Modrinth, pas à un numéro de version — le
  raisonnement est dans [`localdeps/README.md`](../../localdeps/README.md), et résoudre par
  numéro peut te refiler silencieusement un jar NeoForge.
- **Un correctif sans mixin.** Suis `SafariBallFix` : un `register()` statique, appelé
  depuis un bloc `isModLoaded` dans `onInitialize`, qui enregistre un callback d'évènement
  Fabric. Un évènement qui se déclenche avant le hook de l'autre mod est souvent un levier
  plus propre qu'un mixin qui le combat.
- **Un nouveau drapeau de configuration.** Ajoute le champ à `CheeseCompatConfig` avec son
  défaut. Le comportement charger-puis-sauver le fait apparaître automatiquement dans les
  fichiers existants. Lis-le via `CheeseCompatMod.getConfig()`.

## Faiblesses connues

Dites franchement, parce que les découvrir par surprise est pire.

- **`fabric.mod.json` déclare `"version": "1.1.0"`.** `gradle.properties` dit
  `mod_version=1.2.53+1.21.1`. `processResources` déclare bien une propriété `version` et
  étend `fabric.mod.json`, mais le fichier n'a pas de placeholder `${version}`, donc
  l'extension ne fait rien. Le jar est nommé depuis la vraie version ; la version que Fabric
  rapporte et affiche dans la liste des mods est `1.1.0`. Tout autre mod qui écrirait une
  contrainte de version contre `cheesecompatmodsfix` lirait le mauvais numéro.
- **`CheeseCompatMod` journalise sa version comme une chaîne codée en dur**, `"(v1.2.53)"`,
  dans la ligne de démarrage. Elle doit être éditée à la main à chaque version.
- **`CheeseCompatClient` est du code mort.** Aucun entrypoint `client` n'est déclaré.
  L'activateur de packs de ressources ne tourne jamais.
- **`DaycareDestinyKnotConsumeMixin` est du code mort.** Absent de `mixins.json`.
- **`DungeonDimensionsMixin` est une classe vide** qui n'existe que pour porter un
  commentaire, et qui est tout de même chargée et appliquée.
- **`CheeseCompatConfig` emploie un chemin relatif** là où tous les autres fichiers du mod
  passent par `FabricLoader.getConfigDir()`.
- **Pas de rechargement de la config.** Elle est lue une fois à l'init. Modifier le fichier
  sur un serveur en marche ne fait rien, et le prochain redémarrage le réécrira.
- **`defaultRequire: 0` rend chaque correctif cassé silencieux.** Une mise à jour de mod qui
  déplace une méthode cible ne produit pas d'erreur ; elle produit un correctif qui a cessé
  de marcher. Le seul moyen de s'en apercevoir est le retour du symptôme.
- **Cibles d'injection nommées comme des lambdas.** `ArcPhoneHealMixin` et
  `ArcPhonePCMixin` injectent dans `lambda$registerServerReceiver$1`. Les noms de lambdas
  sont attribués par le compilateur dans l'ordre du source et ne sont pas une API.
- **Géométrie de carte codée en dur.** L'offset PNJ de `18` blocs dans
  `BattleArenaPositionMixin` est l'écartement des plateformes d'une arène précise.
- **Un `@Overwrite` sur l'initialiseur d'un mod tiers.** `CobbleSafariInitMixin` remplace
  `CobbleSafari.initLogic()` en entier, et jettera en silence tout ce que CobbleSafari y
  ajoutera dans une version future.
- **Des blocs enregistrés dans l'espace de noms d'un autre mod.** `cobblemon:tatami_mat` et
  `cobblemon:tatami_block` sont enregistrés par ce mod, pour le worldgen de CobbleSafari.
- **Une table de butin orpheline** journalise une erreur de parsing au démarrage quand
  CobbleSafari est absent. Inoffensive, mais c'est du bruit, et ça ressemble à un vrai échec
  pour quiconque lit le log pour la première fois.
- **Aucun test, d'aucune sorte.** Chaque affirmation de cette documentation sur ce que fait
  un correctif vient de la lecture du code et de l'expérience de production d'un seul
  serveur.

---

← [Catalogue — tout le reste](fixes-misc.fr.md) · [Dépannage](troubleshooting.fr.md) →
