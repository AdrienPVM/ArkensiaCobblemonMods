# CheeseCompatModsFix

> 🇬🇧 [Read in English](README.md)

> Quarante rustines dans les mods de quinze autres auteurs, écrites pour un modpack précis.

> ## ⚠️ Ce sont des rustines, pas un mod
>
> Chaque correctif a été écrit pour **un modpack précis**, contre des **versions
> précises** d'une douzaine de mods tiers, pour résoudre des problèmes survenus sur un
> seul serveur. Les mixins visent les entrailles du code d'autres auteurs, par nom, par
> index de lambda, par constante de bytecode. **La moindre mise à jour de l'un de ces
> mods peut tout casser en silence** : la config mixin pose `defaultRequire: 0`, donc un
> correctif dont la cible a bougé est ignoré sans la moindre erreur.
>
> Il est **fourni tel quel**, et **aucun support n'est garanti**. Si tu l'installes et
> que quelque chose casse, la réponse sera « enlève-le ».

Ce n'est pas une fonctionnalité. C'est un recueil de mixins de compatibilité et de
rustines runtime — corrections de crash, corrections de comportement entre mods, culls de
performance, et quelques franches bidouilles — réunis parce qu'ils partagent un
initialiseur, un fichier de configuration et un plugin de filtrage des mixins.

Il est publié parce que les correctifs sont instructifs pris un par un : un débordement
d'entier qui faisait couvrir le monde entier à un rayon de protection, un `copyFrom` qui
écrivait les IV dans le mauvais sens, une clé de cache assez grossière pour geler un
évènement pour la durée de vie d'un monde, un `goto` que le vérificateur refuse là où une
branche conditionnelle passe. Quelques-uns sont réellement réutilisables. Le lire, c'est
le but ; l'installer, non.

Ce n'est pas non plus **du code jetable**. La version `1.2.53+1.21.1` tourne en
production sur le serveur de son auteur, et ce depuis une cinquantaine de versions.
Chaque correctif est lié à une version précise d'un mod tiers, ce qui en fait à la fois
une pièce porteuse et une pièce fragile.

**Minecraft 1.21.1 · Fabric · Java 21 · 64 fichiers, ~7 456 lignes**

---

## Prérequis

| | |
|---|---|
| **Obligatoire** | Fabric Loader ≥ 0.16.0 · Fabric API (`fabric-command-api-v2`) · Java 21 |
| **Optionnel** | les quinze mods qu'il corrige — chacun active un groupe de correctifs et est ignoré s'il est absent |

Tout ce qu'il corrige est optionnel au runtime. `CheeseCompatMixinPlugin` interroge
`FabricLoader.isModLoaded(...)` pour chaque cible et éteint les mixins concernés quand le
mod est absent : l'installer n'oblige donc pas à installer toute la pile qu'il connaît.

Fonctionne **côté serveur et côté client**. Le serveur reçoit les 35 mixins serveur et
tous les correctifs à base d'évènements ; le client reçoit quatre mixins cosmétiques ou
de performance, plus un entrypoint `client` qui active les packs de ressources Arkensia au
premier lancement.

### ⚠️ Le build exige deux jars que tu dois fournir

**FTB Quests** et **StarAcademy** sont exclusifs à CurseForge et Gradle ne peut pas les
résoudre. Télécharge le fichier **Fabric 1.21.1** de chacun dans
[`localdeps/`](../localdeps/README.md) à la racine du dépôt, avec exactement ces noms :

```text
ftb-quests-fabric-2101.1.13.jar
academy-mc1.21.1-v1.4.0+build.467-fabric.jar
```

Le build s'arrête dès la phase de configuration avec un message nommant ce qui manque,
plutôt qu'un mur de `cannot find symbol`. Un mixin qui désigne sa cible par une chaîne a
quand même besoin du jar : le processeur d'annotations Mixin valide chaque cible à la
compilation.

Tout le reste — Cobblemon, Battle Tower, CobbleSafari, Trials Edition, Raid Dens,
Legendary Monuments, Minerally, Enhanced Celestials, Data Anchor, Daycare+, Fight or
Flight, ExtraQuests, Numismatic Overhaul, JourneyMap, sgui — est récupéré automatiquement
depuis Maven.

## Installation

```bash
cd CheeseCompatModsFix && ./gradlew build
```

Dépose `build/libs/cheesecompatmodsfix-1.2.53+1.21.1.jar` dans le `mods/` du serveur.
Aucun mod frère n'est requis. Au premier démarrage, il écrit
`config/cheesecompatmodsfix.json` et journalise :

```text
[CheeseCompat] Loaded (v1.2.53). Arc Phone heal=enabled, PC=enabled, Sanctuary fix=ON
```

Cette ligne apparaît qu'un correctif se soit activé ou non. Pour savoir lesquels se sont
activés, cherche `CheeseCompat` dans le log : une ligne d'activation absente signifie un
correctif absent. Parcours complet : [installation](docs/installation.fr.md).

## Documentation

| Page | Contenu |
|---|---|
| [Installation](docs/installation.fr.md) | Prérequis, les deux jars CurseForge, le build, ce que crée le premier démarrage, les lignes de log qui prouvent le chargement, la désinstallation propre et ce que le monde conserve |
| [Premiers pas](docs/getting-started.fr.md) | Un tutoriel : construire le mod, découvrir quels correctifs se sont activés sur ta pile, puis en désactiver un et voir l'effet en jeu |
| [Commandes](docs/commands.fr.md) | `/stopbattle`, et comment `/stopbattle <player>` est retiré aux non-opérateurs par réflexion dans Brigadier |
| [Configuration](docs/configuration.fr.md) | Les trois booléens, dont un ne fait rien, plus tous les nombres qui ressemblent à de la config et sont codés en dur |
| [Architecture](docs/architecture.fr.md) | Arborescence des packages, filtrage des mixins, motifs de réflexion, threading, et une liste franche des faiblesses connues |
| [Dépannage](docs/troubleshooting.fr.md) | Symptôme, cause, remède — construit autour du fait que le mode d'échec normal ici est le silence |

### Le catalogue des correctifs

La partie importante. Une entrée par correctif : le mod et la version visés, le symptôme
du point de vue d'un joueur ou d'un administrateur, le moyen employé, ce qui se passe
quand le mod visé est absent, et si le correctif est encore actif.

| Page | Contenu |
|---|---|
| [Battle Tower](docs/fixes-battle-tower.fr.md) | Douze correctifs : sauvegarde et restauration d'équipe à travers une déconnexion, corruption des IV/EV, géométrie de l'arène, Pokémon PNJ invisible, exploit de débordement dans la boutique BP |
| [Cobblemon et les combats](docs/fixes-cobblemon.fr.md) | Formes Mega-Z, méga-gemmes custom, Trials Edition sur 1.6.1, alphas incapturables, raid dens, Rad Gyms — et les deux correctifs **désactivés ou refusés** après avoir cassé la production |
| [Safari, Academy et boss](docs/fixes-safari-and-bosses.fr.md) | CobbleSafari 0.1.5 rétroporté sur Cobblemon 1.6.1, les blocs de portail du safari, et les patches bytecode pour Cobblewarden Boss |
| [Daycare+](docs/fixes-daycare.fr.md) | Neuf mixins qui réécrivent les règles de reproduction, dont un écrit et jamais activé |
| [Tout le reste](docs/fixes-misc.fr.md) | Legendary Monuments, Minerally, Enhanced Celestials, ExtraQuests, JourneyMap, Numismatic Overhaul |

## Les commandes en un coup d'œil

| Commande | Permission | Effet |
|---|---|---|
| `/stopbattle` | n'importe quel joueur | met fin de force au combat Cobblemon de l'appelant |
| `/stopbattle <player>` | niveau 2 | commande de Battle Tower, restreinte aux opérateurs par ce mod |

La forme ciblée n'est pas ajoutée ici, seulement verrouillée. Détails dans
[commandes](docs/commands.fr.md).

## Ce qu'il corrige

| Mod visé | Correctifs |
|---|---|
| **Battle Tower** | niveaux PNJ forcés, garde contre le double combat, verrous d'équipe périmés, corruption IV/EV, restauration complète de l'équipe, sauvegarde à la déconnexion, offsets d'arène, préchargement de chunks, aspects dans l'écran d'équipe, restauration à l'arrêt, débordement dans la boutique BP |
| **Cobblemon** | alphas silencieusement incapturables, Téracristallisation bloquée en raid |
| **MegaShowdown** | `mega_z` ajouté à l'ensemble des aspects pour que les formes Mega-Z dé-évoluent ; 42 méga-gemmes custom mappées vers les ids Showdown |
| **CobbleSafari** | rétroportage vers Cobblemon 1.6.1 : influences de spawn, bans dimensionnels, téléporteur et portails de donjon hors Overworld, deux blocs Cobblemon manquants, un bloc de portail |
| **StarAcademy** | balles safari débloquées dans les dimensions safari, lance-pierre bloqué là-bas, boss incapturables protégés des tirs de lance-pierre |
| **Daycare+** | héritage des IV réécrit, fertilité bornée et décroissante, mode ponctuel avec bascule dans la GUI |
| **Raid Dens** | orientation du boss, crash au nettoyage des entités, propagation du feu, cristaux qui ne se referment jamais |
| **Rad Gyms** | joueurs bloqués après un crash, gel par concurrence, mauvais type de badge, NPE à l'interaction avec un dresseur |
| **Legendary Monuments** | débordement d'entier sur le rayon des sanctuaires, bascules de soin et de PC pour l'Arc Phone, verrou de profession entrepreneur |
| **Minerally** | crash NPE quand JustHammers casse un `OrePile` en zone |
| **Enhanced Celestials** | les dimensions `cheeseguard:*` héritent des réglages lunaires ; la donnée par niveau, paresseuse, est forcée à se matérialiser |
| **ExtraQuests** | une course au démarrage qui supprimait des timers de quêtes quotidiennes sans les reset |
| **Fight or Flight** | un Giratina capturé n'attaque plus son dresseur |
| **JourneyMap** | chemin d'icône de minimap pour les fakemons hors de l'espace de noms `cobblemon` |
| **Numismatic Overhaul** | cull par distance sur le rendu des blocs boutique (FPS client) |
| **Cobblewarden Boss** | apparition immédiate du boss sur un cri de sculk, agressivité permanente, aura de ténèbres — plus quatre patches bytecode appliqués au jar de ce mod |

## Deux cicatrices à lire avant de toucher à ça

**Un chien de garde de combat a gelé tous les combats du serveur.** La version 1.2.51
livrait `RctForcedSwitchFix`, censé débloquer les combats où plusieurs Pokémon tombent au
même tick. Son prédicat marquait tout acteur IA ayant une requête en attente et aucune
réponse — c'est-à-dire l'état normal de chaque tour pendant que le joueur humain
réfléchit. Trois secondes de réflexion suffisaient à le déclencher. Il rejouait le
gestionnaire de choix au milieu de combats sains, désynchronisait Showdown et figeait
tout ; la forensique a ensuite montré qu'il ne débloquait même pas les cas pour lesquels
il avait été écrit. **Désactivé en 1.2.52**, et la classe est conservée dans l'arbre avec
l'autopsie à l'endroit de son appel commenté. Il était bien conçu — fail-open, idempotent,
cantonné au thread serveur — et pourtant catastrophique, parce que l'erreur était dans le
**prédicat de détection**, et un prédicat faux sur un chemin parcouru à chaque tour ne se
dégrade pas gracieusement. Le récit complet est dans
[architecture](docs/architecture.fr.md#-le-chien-de-garde--la-cicatrice-à-lire).

**Un mixin sur les objets tenus a cassé tous les échanges d'objets tenus par les Pokémon,
à l'échelle du serveur.** Tenté en 1.2.37 contre MegaShowdown, annulé, et
**délibérément pas retenté**. Le comportement de consommation de `rusted_sword` qu'il
poursuivait est un WONTFIX assumé. L'approche qui fonctionne est l'API publique
`registerStackRemap` de Cobblemon, c'est elle que `CacademyMegaStoneRemapPatch` emploie à
la place.

## Une erreur non fatale connue au démarrage

Sans CobbleSafari installé, le log du serveur porte :

```text
Couldn't parse element minecraft:loot_table/cheesecompat:blocks/cobblesafari_portal_frame
  - Unknown registry key ...: cheesecompat:cobblesafari_portal_frame
```

Le bloc cadre de portail est enregistré conditionnellement, mais sa table de butin est
livrée inconditionnellement : la table est donc orpheline quand le bloc est absent. Le
serveur démarre normalement. Vérifié sur un serveur de test à 475 mods. C'est du bruit
dans le log, rien de plus.

## Autres avertissements honnêtes

- **Il y a un `fabric.mod.json` orphelin à la racine du mod.** Celui qui est empaqueté est
  `src/main/resources/fabric.mod.json`, et il est correct. La copie de 27 lignes à
  `CheeseCompatModsFix/fabric.mod.json` n'est lue par rien, n'est pas empaquetée, déclare
  une version périmée `"version": "1.1.0"` et une description qui s'arrête à « Battle
  Tower, Legendary Monuments, Arc Phone toggles ». C'est un résidu d'une ancienne structure
  de projet et il devrait être supprimé. Lis celui sous `src/main/resources/`.
- **Deux classes sont du code mort** : `DaycareDestinyKnotConsumeMixin` (absent de la
  config mixin) et `DungeonDimensionsMixin` (une classe vide qui ne porte qu'un
  commentaire).
- **`sanctuaryOverflowFix` est une clé de configuration vestigiale.** Elle est lue,
  journalisée, et ne commute rien ; le correctif qu'elle nomme est inconditionnel.
- **Un nouveau pâturage Daycare+ produit un œuf puis s'arrête.** C'est le défaut de ce
  mod, et l'inverse de celui de Daycare+.
- **`BattleArenaPositionMixin` code en dur une arène de 18 blocs.** Avec toute autre
  géométrie d'arène, le PNJ atterrit dans un mur.
- **`CobbleSafariInitMixin` est un `@Overwrite` sur l'initialiseur d'un mod tiers** et
  jettera en silence tout ce que CobbleSafari ajoutera à cette méthode dans une version
  future.
- **Il n'y a aucun test.** Tout ce qui est documenté ici vient de la lecture du code et
  de l'expérience de production d'un seul serveur.

## Spécificités Arkensia

`CacademyMegaStoneRemapPatch` mappe 42 méga-gemmes custom du datapack `CacademyMons` —
livrées sous forme de `minecraft:brick` et d'une poignée d'autres items vanilla,
distingués par leur `custom_model_data` — vers les ids du simulateur Showdown de
MegaShowdown. C'est entièrement spécifique à ce datapack et ça ne fait rien sans lui.

`EnhancedCelestialsFactoryFallbackMixin` est spécifique aux dimensions créées au runtime
par [CheeseGuard](../CheeseGuard/) : toute dimension `cheeseguard:*` sans réglages
lunaires propres hérite de ceux de `cheeseguard:arkensia`.

## ⚠️ `patches/cobblewarden/` est le code de quelqu'un d'autre

`patches/cobblewarden/` contient des patches de compatibilité pour **Cobblewarden Boss de
LiquidSilverAce**, utilisés sous sa licence **CC0-1.0**. C'est la seule partie de ce dépôt
qui ne soit pas de RoiCheese, et elle garde sa propre attribution : le `fabric.mod.json`
patché qui s'y trouve nomme LiquidSilverAce comme auteur, conserve
`"license": "CC0-1.0"` et conserve les liens vers le projet d'origine.

Quatre patches sont documentés dans `patches/cobblewarden/README.md`, en français,
jusqu'aux indices du constant pool et à un script Python pour régénérer chacun : un bloc
`depends` assoupli, deux éditions de bytecode dans `WardenSpawnInterceptor` (une clé de
cache grossière gelait tous les wardens futurs d'une région de 128×64×128), et un
remplacement complet de `BossSpawner`. Note que les fichiers `.class` patchés eux-mêmes
**ne sont pas dans ce dépôt** — seuls le README et le `fabric.mod.json` patché y sont.

Résumé et leçons générales de ces patches :
[Safari, Academy et boss](docs/fixes-safari-and-bosses.fr.md#cobblewarden-boss).

## Licence

[PolyForm Noncommercial 1.0.0](../LICENSE), avec une permission additionnelle pour les
opérateurs de serveur. Tu peux le lire, le modifier, le forker, le redistribuer avec tes
changements, et le faire tourner sur un serveur qui accepte des dons ou vend des rangs ;
tu ne peux pas vendre le mod lui-même.

Chaque mod corrigé ici appartient à ses propres auteurs et aucun n'est redistribué. Le
contenu de `patches/cobblewarden/` est celui de LiquidSilverAce, sous CC0-1.0.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Discord Arkensia](https://discord.gg/fYmPwMBjdB)

⚠️ Pour ce mod en particulier : **aucun support n'est garanti**. Un correctif qui casse
après la mise à jour du mod qu'il vise est attendu, pas un bug. Les rapports signalant
qu'un correctif est faux *contre la version qu'il prétend viser* valent la peine d'être
ouverts.
