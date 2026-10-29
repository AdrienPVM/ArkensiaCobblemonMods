[← CheeseGuard](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](architecture.md)

# Architecture

CheeseGuard est le plus gros mod de ce dépôt : 140 fichiers source et environ 35 000
lignes. Il a commencé comme un mod de protection de régions et a accumulé un moteur
d'événements, un gestionnaire de dimensions, un cadre de minijeux, une prison, un
chronomètre de course et un système de vente de terrains. Rien n'a jamais été extrait. Lis
cette page avant de modifier quoi que ce soit dedans.

**Minecraft 1.21.1 · Fabric · Java 21 · 140 fichiers source, ~35 100 lignes**

## Organisation des packages

```text
com.cheeseguard
├── CheeseGuardMod.java        441 lignes — le ModInitializer ; possède chaque gestionnaire
├── api/                         3 fichiers,    225 lignes — le seul point d'extension public
├── client/                      4 fichiers,    739 lignes — entrypoint client, HUD, écran de propriétaire de zone
├── commands/                    2 fichiers,  1 445 lignes — les arbres Brigadier /cg et /prison
├── dashboard/                  10 fichiers,  4 227 lignes — le module CheeseDash et huit API REST
├── data/                       25 fichiers,  4 965 lignes — le modèle : zones, flags, événements, configurations, stockage
├── logic/                      43 fichiers, 21 093 lignes — tous les gestionnaires, listeners et hooks tiers
├── mixin/                      46 fichiers,  1 810 lignes — 44 mixins enregistrés, 2 fichiers morts
└── network/                     6 fichiers,    178 lignes — payloads personnalisés pour les HUD et le menu client
```

`logic/` contient 60 % du code et c'est là que vit la complexité. Ses quatre plus gros
fichiers méritent d'être connus par leur nom :

| Fichier | Lignes | Ce qu'il possède |
|---|---|---|
| `MiniGameManager` | 5 244 | tous les modes de minijeu — prop hunt, spleef, pêche, score du hot potato, gestion des équipes |
| `EventManager` | 2 892 | le cycle de vie d'un événement, participants, scores, tâches planifiées, limites de lancement |
| `DimensionManager` | 2 570 | création de dimension à chaud, mondes par joueur, snapshots, bordures, règles d'entrée |
| `TimelineExecutor` | 1 312 | le langage d'actes et d'effets dans lequel les événements s'écrivent |

Une classe de 5 000 lignes portant une douzaine de responsabilités n'est pas une
conception, c'est une accumulation. Dis-le franchement quand tu y touches.

## Classes clés

### `CheeseGuardMod`

L'unique entrypoint et le localisateur de services. `CheeseGuardMod.INSTANCE` est un champ
statique public, et toutes les autres classes atteignent leurs gestionnaires via
`CheeseGuardMod.getInstance().getXxx()`. Il n'y a ni injection de dépendances ni interface
entre un gestionnaire et ses appelants.

Dans `onInitialize`, il construit : `RegionStorage`, `RegionManager`, `SelectionManager`,
`VisualizationManager`, `ChunkSetStorage`, `ChunkSuggestionStorage`, `TagConfigStorage`,
`ServerManager`, `EventManager`, `DimensionManager`, `ZoneStateManager`,
`PropHuntIconStorage`, `PastureLimitsManager`, `PrisonManager` et
`PrisonDoppelgangerManager`. Il enregistre les deux arbres de commandes, trois listeners
(`ProtectionListener`, `WandListener`, `PrisonListener`), deux handlers supplémentaires
(`HotPotatoHandler`, `EventLeaveHandler`), six types de payload réseau et neuf callbacks de
cycle de vie Fabric.

Les gestionnaires créés comme champs sont disponibles immédiatement ; ceux qui ont besoin
du serveur (`EventManager.init`, `DimensionManager.init`, `ZoneStateManager.init`) ne
montent qu'à `SERVER_STARTED`. C'est cet écart qui explique que plusieurs handlers
commencent par un test de nullité sur leur gestionnaire et repartent sans bruit — une
requête arrivant pendant le démarrage est jetée, pas mise en file.

### `RegionStorage` et `Region`

`Region` est la zone : une boîte, un identifiant de monde, une priorité, un identifiant de
groupe, une table de flags, une liste de membres, et une `SaleConfig` facultative. Un flag
absent de la table signifie *hérité*, ce qui est distinct de `false` ; `Flag` ne porte
aucune valeur par défaut, seulement un bit `memberExempt` qui décide si les propriétaires
le contournent.

`RegionStorage` garde toutes les zones et tous les groupes dans deux tables et persiste les
deux dans `config/cheeseguard/regions.json`. Chaque mutation appelle `save()` de façon
synchrone : ajouter un membre réécrit tout le fichier sur le thread serveur.

### `ProtectionListener`

La moitié de la protection tournée vers les joueurs : les callbacks Fabric (casse,
utilisation, attaque, usage d'item) et les vérifications toutes les 10 ticks (entrée/sortie,
vol, montures, transformation, nametag, minimap), 795 lignes. La résolution elle-même —
surcharges d'événement → flag explicite de zone → flag de groupe → drapeaux refusés de la
dimension — vit dans `RegionManager.isAllowed()` / `isFlagAllowed()`, et c'est elle
qu'appelle chaque mixin qui applique un flag, pas cette classe.

### `EventManager` et `TimelineExecutor`

Un événement est un `ServerEvent` dont l'état est pris parmi `IDLE`, `ANNOUNCED`, `QUEUE`,
`COUNTDOWN`, `ACTIVE`, `ENDING`, `CLEANUP`. Il n'existe **aucun état `PAUSED`**, et aucune
pause ni reprise nulle part dans le mod. N'en ajoute pas sans avoir lu comment
`EventManager.tick` calcule le temps restant depuis des horodatages d'horloge murale : une
pause exigerait de rebaser chacun d'eux, et plusieurs sont persistés.

`TimelineExecutor` exécute les actes : chaque acte contient des blocs de chronologie, chacun
avec un décalage de départ et un type d'`EventEffect`, dont certains appellent d'autres mods (transformations CobbleShift,
démarrages CheeseKart, ouvertures de donjon).

### `DimensionManager`

Crée et supprime des dimensions à chaud, ce qui est la chose la plus dangereuse que fait ce
mod. Les mondes par joueur reçoivent l'identifiant `<masterDimId>_<uuid8>` — l'identifiant
de configuration de la maîtresse, un tiret bas, et les huit premiers caractères
hexadécimaux de l'UUID du propriétaire. Supprimer l'un d'eux décharge le monde et retire
son dossier ; sous Windows les handles de fichier ne sont pas relâchés immédiatement, le
code attend donc avant de supprimer et peut échouer quand même.

C'est aussi le seul utilisateur de l'entrée `getListeners()` de l'access widener (l'autre
entrée du widener rend `NoiseChunkGenerator` extensible, ce que rien dans CheeseGuard
lui-même n'étend) : le vanilla chaîne la bordure de
l'Overworld au Nether et à l'End via un listener `WorldBorderSyncer`, ce qui rend
impossibles des bordures indépendantes par dimension. Le widener ouvre
`WorldBorder.getListeners()` pour que cette chaîne puisse être démontée au démarrage.

## Threads

Trois threads touchent l'état de ce mod, et les passages de relais sont explicites.

**Le thread serveur** exécute tout ce qui est dans `logic/` : les handlers de tick, les
listeners, les exécuteurs de commande, chaque mutation de bloc et d'entité. Les commandes y
sont déjà, leurs handlers mutent donc directement.

**Les threads HTTP de CheeseDash** exécutent `dashboard/*Api.java`. L'état de Minecraft ne
doit pas y être touché : chaque API passe donc le relais avec
`WebDashboard.executeOnServerThread(server, () -> …)`. Les comptes actuels sont de 18
passages dans `ZonesApi`, 13 dans `DimensionsApi` comme dans `EventsApi`, 7 dans
`ChunksApi`, 5 dans `GroupsApi`, 4 dans `PlayersApi`, 2 dans `PrisonApi` et **aucun dans
`RacesApi`**. `RacesApi` confie lui-même son scan et sa carte de hauteur au thread serveur,
avec `server.execute` et un `CompletableFuture` de 30 secondes ; ses routes de circuit lisent
et écrivent leurs propres fichiers JSON sur le thread HTTP, tout comme la sauvegarde
automatique après un scan, si bien qu'une écriture de configuration de circuit peut entrer
en concurrence avec une course qui la lit sur le thread serveur.

Les lectures de structures immuables ou concurrentes se font directement depuis le thread
HTTP. `PrisonManager` utilise une `ConcurrentHashMap` et une liste synchronisée exactement
pour cela ; `RegionStorage` utilise des tables ordinaires et ne le fait pas, donc une
lecture de la liste des zones depuis le dashboard pendant qu'un joueur crée une zone est
techniquement une situation de compétition. Elle n'a jamais mordu en pratique parce que la
création de zone est rare.

**Les threads Netty** livrent les payloads personnalisés. Chaque récepteur de
`CheeseGuardMod` enveloppe aussitôt son corps dans `context.server().execute(...)`. Le
handler de déconnexion va plus loin : il capture le `GameProfile` sur le thread Netty et
diffère la création du sosie au thread serveur, parce que construire un
`ServerPlayerEntity` et diffuser des paquets hors thread s'est révélé peu fiable.

## Persistance

Quatorze fichiers et huit dossiers, tous sous `config/`, chacun écrit par son propre
gestionnaire. [La configuration](configuration.fr.md) les liste tous avec leurs clés et
leur propriétaire.

Trois schémas, et les différences comptent :

- **Écriture à la mutation.** `RegionStorage`, `PrisonManager`, `PastureLimitsManager`,
  `ChunkSetStorage` réécrivent tout leur fichier à l'intérieur du setter. Durable, et un
  accroc sur de grosses données.
- **Écriture à la demande.** `EventManager.save()` et `DimensionManager.save()` sont
  appelés par leurs appelants. Oublie un appel et la modification survit jusqu'au prochain
  redémarrage, puis disparaît.
- **Écriture sur événement.** Les archives d'inventaire et les instantanés en attente sont
  écrits quand un événement vide un inventaire, et c'est ce qui rend
  [`/cg restoreinv`](commands-operations.fr.md#cg-restoreinv) possible.

Rien n'est écrit de façon atomique. Chaque sauvegarde est un `Files.writeString`
directement sur le chemin final, sans fichier temporaire ni renommage. Un plantage pendant
l'écriture de `regions.json` le tronque, et le démarrage suivant charge zéro zone —
`load()` consigne une ligne `Failed to load CheeseGuard data` et continue sans rien. Sauvegarde `config/cheeseguard/` et
`config/cheeseguard_*.json` avec ton monde.

## Les 44 mixins

`cheeseguard.mixins.json` enregistre 40 mixins côté serveur et 4 côté client. Deux autres
fichiers existent dans `mixin/` et ne sont **pas** enregistrés :
`StructureGenerationMixin`, désormais une classe vide dont le commentaire dit que la
logique a déménagé, et `StructurePieceMixin`, qui est du code vivant que rien ne charge.
Supprime le premier ; décide pour le second.

Groupés par ce qu'ils accrochent, avec le risque porté par chaque groupe :

### Comportement vanilla des blocs et des entités — 21 mixins

`BlockPlaceMixin` (`BlockItem.place`), `ItemDropMixin`, `ItemPickupMixin`,
`EntitySpawnMixin` (`ServerWorld.spawnEntity`), `MobSpawnMixin` (`MobEntity.canSpawn`),
`ExplosionMixin`, `FallDamageMixin`, `HungerMixin`, `FarmlandTrampleMixin`,
`FireSpreadMixin`, `LeavesDecayMixin`, `IceMeltMixin`, `SnowFormMixin`, `VineGrowMixin`,
`CoralDeathMixin` (quatre classes de bloc de corail d'un coup), `RedstoneMixin`,
`EndermanGriefMixin`, `EatGrassGoalMixin`, `CreativeInventoryMixin`,
`CobblemonBlockInteractMixin` et `AbstractFireBlockCustomDimMixin`.

Chacun demande à `RegionManager` (ou, pour les règles de dimension, à `DimensionManager`)
si l'action est permise et annule à `HEAD` sinon.
C'est la couche d'application des flags, et c'est le groupe le plus susceptible d'entrer en
conflit avec un autre mod de protection ou de gestion de monde qui accroche les mêmes
méthodes — deux mods qui annulent tous deux `BlockItem.place`, c'est une loterie d'ordre de
chargement. Neuf mixins portent `require = 0` et échouent silencieusement si leur méthode
cible est introuvable — `FireSpreadMixin`, `EventProjectileHitMixin`, `EventCraftItemMixin`,
`EventFishCatchMixin`, `CSAFuzzyMatchFixMixin` et les quatre mixins client ; les autres font
planter le jeu à la place sur un changement de mappings, ce qui est le meilleur
comportement pour un mod de protection.

### Score d'événement — 8 mixins

`EventBlockBreakMixin`, `EventBlockPlaceScoreMixin`, `EventCraftItemMixin`,
`EventFishCatchMixin`, `EventItemPickupScoreMixin`, `EventMobKillMixin`,
`EventPlayerDeathMixin`, `EventProjectileHitMixin`. Ceux-là attribuent des points d'événement
sans rien annuler — trois à `RETURN` (`EventBlockPlaceScoreMixin`,
`EventItemPickupScoreMixin`, `EventFishCatchMixin`), `EventBlockBreakMixin` à la fois à
`HEAD` et à `RETURN`, les autres à `HEAD`. Deux d'entre eux
(`EventBlockPlaceScoreMixin`, `EventItemPickupScoreMixin`) portent `priority = 1100` pour
passer après le mixin de protection sur la même méthode — le point n'est marqué que pour
une action réellement autorisée. Cet ordre est porteur et n'est documenté nulle part dans
le code lui-même.

### Plomberie des dimensions et du monde — 7 mixins

`NetherPortalReturnMixin` et `EndPortalReturnMixin` (`createTeleportTarget`) renvoient un
joueur dans la dimension d'où il vient au lieu de l'Overworld, et c'est ce qui fait
fonctionner un Nether à l'intérieur d'une dimension personnalisée. `WorldSeedMixin`
(`ServerWorld.getSeed`) renvoie une graine par dimension. `WorldBorderNoSyncerMixin`
(`WorldBorder.addListener`) refuse le syncer vanilla pour que les bordures restent
indépendantes. `StructureStartMixin` et le non enregistré `StructurePieceMixin` filtrent la
génération de structures. `ChunkRegionAccessor` et `MinecraftServerAccessor` sont de
simples accesseurs — `MinecraftServerAccessor` expose la table mutable `worlds`, et c'est
ainsi que les dimensions sont enregistrées à chaud.

Dans ce groupe, tout casse à un changement de version de Minecraft avant quoi que ce soit
d'autre. Les deux mixins de portail et l'accesseur `worlds` en particulier touchent des
internes que Mojang a changés à plusieurs reprises.

### Interception de commandes — 2 mixins

Tous deux visent `CommandManager.execute` à `HEAD`. `HomeCommandMixin` annule toute
commande commençant par `home` pour un non-opérateur se trouvant dans une dimension dont la
configuration a `allowHome` désactivé. `PrisonCommandMixin` annule tout ce qu'un prisonnier
tape, hors d'une petite liste blanche.

Comparer la chaîne brute de la commande est grossier : `/homeland` commence aussi par
`home`. Et deux mixins sur la même méthode, tous deux annulants, est un conflit qui attend
d'être trouvé. Si une commande ne fait mystérieusement rien pour un joueur et fonctionne
pour un autre, regarde ici en premier.

### Mods tiers — 6 mixins

C'est le groupe fragile. Chacun vise une classe d'un autre mod par son nom pleinement
qualifié avec `remap = false`, et `@Pseudo` quand le mod peut être absent.

| Mixin | Mod visé | Ce qu'il fait | Ce qui casse à leur mise à jour |
|---|---|---|---|
| `JadeTooltipHideMixin` | Jade — `snownee.jade.overlay.OverlayRenderer` | masque l'infobulle de bloc dans une zone `minimap-hide` | une classe de rendu renommée ou déplacée de package |
| `JourneyMapMinimapHideMixin` | JourneyMap — `journeymap.client.ui.minimap.MiniMap` | masque la minimap dans une zone `minimap-hide` | note que celui-ci n'a **pas** `remap = false`, contrairement à ses voisins |
| `CobblemonNametagHideMixin` | Cobblemon — `…client.render.pokemon.PokemonRenderer` | masque les nametags de Pokémon dans une zone `nametag-hide` | Cobblemon réorganise souvent ses renderers client |
| `NametagHideMixin` | `EntityRenderer` vanilla | la même chose pour les entités non Pokémon | risque faible |
| `CSAFuzzyMatchFixMixin` | Cobblemon Spawn Alerts — `io.github.stainlessstasis.alert.AlertHandler` | redirige un `String.contains` vers `equalsIgnoreCase` dans `getConfigForPokemon` | un `@Redirect` sur un site d'appel précis : toute retouche de cette méthode arrête la correspondance en silence, ou fait planter |
| `PlayerListEntryNullSafeMixin` | `PlayerListS2CPacket.Entry` vanilla | sûreté contre le null pour un plantage vu avec des entrées de liste de joueurs moddées | risque faible |

`CSAFuzzyMatchFixMixin` mérite un mot, parce qu'il est l'exemple le plus clair du coût de
ce groupe. Ce n'est pas du tout une fonctionnalité de CheeseGuard : il corrige un bug dans
le mod de quelqu'un d'autre, où une comparaison par sous-chaîne faisait déclencher l'alerte
configurée pour *Artikodin* par un Pokémon nommé *Tic* en français. Le correctif est juste
et le commentaire qui le documente est excellent. Il reste un `@Redirect` dans une méthode
tierce, inspectée en version 1.9.2, que personne ne pensera à revérifier quand ce mod se
mettra à jour.

Waystones, à l'inverse, est atteint par **réflexion** dans `WaystonesHook` plutôt que par
un mixin — le bon choix pour une intégration optionnelle, et le schéma à copier s'il t'en
faut une autre. `CobblemonHook`, `CobbleShiftHook`, `CheeseKartHook`, `CheeseStoreHook`,
`CobblemonDungeonsHook`, `NumismaticHook` et `FtbQuestHelper` font tous de même.

## Intégration dashboard

`CheeseGuardDashboardModule` est déclaré comme entrypoint `cheesedash` dans
`fabric.mod.json`, enregistre le préfixe `cheeseguard` et transmet tout à
`CheeseGuardApiRouter`. Le routeur répartit sur un préfixe de chemin vers huit API :
`/zones`, `/groups`, `/dimensions`, `/events`, `/players`, `/chunks`, `/races`, `/prison`.
Un chemin non reconnu reçoit un 404 ; une requête arrivant avant `onServerStart` reçoit un
503.

Un seul canal WebSocket est publié par ce mod : `PrisonListener` appelle
`DashboardRegistry.broadcast("prison", …)`. Tout le reste de ce que le dashboard montre pour
CheeseGuard se consulte par scrutation sur REST. Les routes sont documentées dans
[le dashboard](dashboard.fr.md).

## Points d'extension

- **`api/GenTypeRegistry` et `GenTypeProvider`** — le seul point d'extension conçu comme
  tel. Un autre mod peut enregistrer un type de génération de monde que le créateur de
  dimensions de CheeseGuard proposera. `GenTypeRegistry.discover()` tourne dans
  `onInitialize`.
- **`Flag`** — ajouter un flag suppose d'ajouter une constante d'énumération, un hook de
  mixin ou de listener qui la consulte, et une case dans la grille de flags du dashboard.
  Les chaînes `displayName` de l'énumération sont **françaises, codées en dur**, et ne sont
  pas des clés de langue.
- **`EventEffect`** — les nouveaux types d'effet sont la façon prévue de faire faire
  quelque chose de nouveau à un événement, y compris piloter un autre mod. Les effets
  inter-mods existants (CobbleShift, CheeseKart, donjons) servent de modèles.
- **Une intégration tierce** — écris un `*Hook` dans `logic/` en réflexion, avec
  `FabricLoader.isModLoaded`. N'ajoute pas de mixin à moins que la réflexion ne puisse
  vraiment pas le faire.

## Faiblesses connues

Dites franchement, parce que ce sont elles qui te coûteront un après-midi.

**Les API dashboard délèguent toute leur autorisation au cœur.** CheeseDash fournit
`WebDashboard.requireWrite(exchange)` et `requireAdmin(exchange)`, et `ZonesApi`,
`DimensionsApi`, `EventsApi`, `GroupsApi`, `PlayersApi` et `ChunksApi` **n'appellent jamais
ni l'un ni l'autre** — 65 routes mutantes sans aucun contrôle de rôle à l'intérieur du
module. Seules `PrisonApi` et `RacesApi` se gardent elles-mêmes :

```java
if (!WebDashboard.requireWrite(exchange)) return;
```

La frontière tient pourtant, parce que le répartiteur de modules de CheeseDash, dans
`DashboardApi`, applique `requireWrite` à toute requête non-`GET` avant qu'aucun module ne la
voie. Une session `READ_ONLY` peut lire et rien d'autre, sur toutes les routes de ce mod. Ce
qui ne tient pas, c'est la raison pour laquelle elle tient : la protection est une propriété
du répartiteur du cœur, fondée uniquement sur la méthode HTTP, et le module n'y contribue en
rien. Trois conséquences :

- **Aucune route de CheeseGuard ne peut exiger `SUPER_ADMIN`.** `requireWrite` est la seule
  barre que le répartiteur connaisse : réinitialiser une dimension depuis un snapshot,
  supprimer des fichiers de région de la sauvegarde du monde et modifier un flag sont donc
  tous au niveau `OPERATOR`. Une route qui mériterait une barre plus haute doit appeler
  `requireAdmin` elle-même.
- **Une route mutante exposée en `GET` ne serait gardée par rien du tout.** Il n'en existe
  aucune aujourd'hui dans ces six API, mais rien dans le module n'empêcherait d'en ajouter
  une, et rien ne le signalerait.
- **Une seule modification de ce répartiteur ferait tomber les 65 contrôles d'un coup**, sans
  qu'aucun module s'en aperçoive.

Un appel local à `requireWrite`, comme dans `PrisonApi`, est donc de la défense en profondeur :
utile, mais redondante — ce n'est pas la frontière elle-même. La garde répond elle-même à la
requête par un 403 et renvoie `false` ; ignorer sa valeur de retour — ce que `RacesApi`
faisait jusqu'au 29 octobre 2026 — envoie le 403 puis exécute l'action quand même. Le détail
par route, et la colonne « rôle » qui marque les garanties venant du cœur, appartiennent au
[dashboard](dashboard.fr.md).

**Les gardes de commande ont été fausses longtemps.** `/cg flag`, `/cg addmember`,
`/cg removemember` et tout le sous-arbre `/cg group` n'avaient aucune clause `requires`
jusqu'au 29 octobre 2026 : n'importe quel joueur pouvait les appeler. Elles sont au niveau 2
désormais, comme les dix-neuf autres sous-commandes protégées. La leçon est structurelle
plutôt qu'historique : une clause `requires` est une ligne facile à oublier que rien ne
teste, et quatre manquaient depuis des mois sans que personne le remarque. Le tableau
complet est dans [les commandes](commands.fr.md#niveaux-de-permission).

**Les sauvegardes ne sont ni atomiques ni doublées.** Un plantage pendant l'écriture de
`regions.json` perd toutes les zones, avec une seule ligne d'erreur dans le log, parce que
le chargeur attrape l'erreur d'analyse et démarre à vide.

**`MiniGameManager` fait 5 244 lignes.** Prop hunt, spleef, pêche et la logique d'équipe
partagent une seule classe et un seul jeu de champs. Rien de tout cela n'est testé.

**Deux commandes mentent sur leur réussite.** `/cg removemember` et `/cg group delete`
signalent un succès sans condition.

**La console ne peut pas lancer la plupart de `/cg`.** Les handlers appellent
`getPlayerOrThrow()` et renvoient `0` en cas d'échec, sans message : un appel console
ressemble donc à une absence d'effet.

**Du français codé en dur à des endroits anglophones.** Les noms affichés de l'énumération
`Flag`, le `getNavDescription()` du module dashboard, le message d'accueil par défaut de la
prison, et le littéral `aucun` affiché par `/cg info` quand une zone n'a pas de groupe.

**`logic/` n'a aucune frontière.** Tout atteint tout par le `CheeseGuardMod.INSTANCE`
statique. Il n'existe aucune couture sur laquelle tester, et c'est pourquoi il n'y a aucun
test.

**Il vise quatre mods tiers par nom de classe.** Jade, JourneyMap, Cobblemon, Cobblemon Spawn
Alerts. Chacune de leurs mises à jour peut casser une fonction de CheeseGuard ou faire
planter le jeu au chargement. Si le serveur ne démarre plus après la mise à jour d'un mod,
soupçonne cette liste d'abord et cherche les échecs d'application de mixin dans le log.

---

← [API dashboard](dashboard.fr.md) · [Dépannage](troubleshooting.fr.md) →
