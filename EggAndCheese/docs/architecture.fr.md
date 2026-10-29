[← EggAndCheese](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](architecture.md)

# Architecture

49 fichiers source, environ 7 100 lignes de Java. Le mod utilise
`loom.splitEnvironmentSourceSets()` : la séparation entre code serveur et code client est
donc imposée par le build et non par convention — ce qui est dans `src/client/java` ne peut
pas être référencé depuis `src/main/java`.

## Découpage en packages

```text
src/main/java/com/roicheese/eggandcheese/
├── EggAndCheese.java          ModInitializer — registres, critère, paquets, events, commandes
├── advancement/               le critère egg_found
├── block/                     les trois blocs + le handler de réconciliation au chargement
├── blockentity/               l'état des œufs admin, des œufs déco et des stèles
├── dashboard/                 module CheeseDash + dispatcher /api/v1/eggs/*
├── data/                      registre persistant, entrées, et index des blocs chargés
├── dna/                       le record d'ADN et ses sept enums
├── ftb/                       le hook de réflexion FTB Quests
├── item/                      item admin, item déco, item de stèle
├── network/                   tuyauterie des payloads et des paquets
├── registry/                  enregistrement des blocs, items et block entities
└── util/                      commandes, contrôle de permission, helpers NBT

src/client/java/com/roicheese/eggandcheese/
├── EggAndCheeseClient.java    ClientModInitializer — renderers + récepteur de sync
├── JourneyMapIntegration.java hook de waypoint optionnel (voir Code mort)
├── renderer/                  trois BESR, le renderer d'item, la config de rendu
└── texture/                   le générateur procédural, son cache et un RNG à graine
```

| Package | Ce qu'il possède |
|---|---|
| `advancement` | `EggFoundCriterion` — le déclencheur `eggandcheese:egg_found` et le codec de sa condition `egg_id` |
| `block` | `AdminCollectibleBlock` (indestructible, la cible de la chasse), `CollectibleDecoBlock` (la récompense, cassable), `PedestalBlock` + `PedestalExtensionBlock` (mobilier), `PedestalVariant`, et `AdminEggPlacedEvent` qui n'est pas un bloc du tout mais le handler d'événement de réconciliation |
| `blockentity` | `AdminCollectibleBlockEntity` (ADN, registryId, échelle, la logique de découverte), `CollectibleDecoBlockEntity` (ADN seulement), `PedestalBlockEntity` (stack exposé, échelle, hauteur, pilier, rotation, formes de collision) |
| `dashboard` | `EggAndCheeseDashboardModule` (préfixe, métadonnées de navigation, référence au serveur) et `EggsApi` (924 lignes, toutes les routes) |
| `data` | `EggRegistryData` (le `PersistentState`), `CollectibleEntry` (un œuf), `LiveAdminEggIndex` (blocs chargés par id) |
| `dna` | `CollectibleDNA` plus `PaletteType`, `PatternType`, `EmissiveType`, `StampType`, `MicroTextureType`, `RarityType`, `CollectibleType` |
| `ftb` | `FtbQuestsHook` — 733 lignes de réflexion, le plus gros fichier |
| `item` | `AdminCollectibleItem` (placement et la branche remplacer/créer), `CollectibleItem` (infobulle, placement, le correctif de vitrine), `PedestalItem` |
| `network` | `ModPayloads` (un payload S2C), `ModPackets` (sérialisation et émetteur), `PacketExtras` (un stub no-op) |
| `registry` | `ModBlocks` (2 blocs collectibles + 23 stèles + 1 extension), `ModItems` (2 items collectibles + 23 items de stèle), `ModBlockEntities` (3 types) |
| `util` | `AdminCommand`, `PedestalCommand`, `PermissionUtil`, `NbtUtils` |
| `renderer` (client) | `AdminCollectibleBESR`, `CollectibleDecoBESR`, `PedestalBESR`, `EggItemRenderer`, `CollectibleModelRenderer` (le mesh de 472 voxels), `EggRenderConfig` |
| `texture` (client) | `ProceduralTextureGenerator` (1 245 lignes), `CollectibleTextureCache`, `SeededRandom` |

## Points d'entrée

`fabric.mod.json` en déclare trois :

```json
"entrypoints": {
  "main":      ["com.roicheese.eggandcheese.EggAndCheese"],
  "client":    ["com.roicheese.eggandcheese.EggAndCheeseClient"],
  "cheesedash":["com.roicheese.eggandcheese.dashboard.EggAndCheeseDashboardModule"]
}
```

`"mixins": []` — le mod n'a aucun mixin, et pas de `eggandcheese.mixins.json`. Rien n'est
injecté dans Minecraft ni dans un autre mod. C'est inhabituel dans cette collection, et
c'est une force : il n'y a aucun bytecode dépendant d'une version à casser lors d'une mise
à jour de Minecraft.

`EggAndCheese.onInitialize` exécute, dans l'ordre : `ModBlocks.init()`,
`ModItems.init()`, `ModBlockEntities.init()`, l'enregistrement du critère,
`ModPayloads.registerAll()`, `ModPackets.registerServerHandlers()`,
`PacketExtras.register()`, `AdminEggPlacedEvent.register()`, puis les deux enregistrements
de commandes. L'enregistrement des payloads doit précéder celui des handlers, et le code
source le dit en commentaire.

```text
[EggAndCheese] Initializing...
[EggAndCheese] Ready!
```

## Où vit l'état

| État | Stockage | Écrit quand |
|---|---|---|
| Entrées de collectibles, les quatre réglages | `EggRegistryData`, un `PersistentState` sous la clé `eggandcheese_registry` sur l'**overworld** | au cycle de sauvegarde de Minecraft, après un `markDirty()` |
| ADN, id, échelle, surcharge de rendu d'un bloc | NBT d'`AdminCollectibleBlockEntity` dans son chunk | à la sauvegarde du chunk, après `markDirty()` |
| ADN d'un bloc déco | NBT de `CollectibleDecoBlockEntity` | à la sauvegarde du chunk |
| Item exposé et réglages d'affichage d'une stèle | NBT de `PedestalBlockEntity` | à la sauvegarde du chunk |
| ADN, id, échelle d'un item | `DataComponentTypes.CUSTOM_DATA` sur le stack | avec l'inventaire |
| Blocs admin chargés, par id | `LiveAdminEggIndex`, statique, en mémoire | jamais persisté — reconstruit depuis les événements de chargement |
| Distance de rendu client | `EggRenderConfig`, statique | jamais persisté — écrasé par chaque paquet de sync |

Rien dans ce mod n'écrit jamais un fichier de lui-même. Aucun appel à `getConfigDir()`,
aucun JSON sur disque, aucune sauvegarde manuelle. Toute écriture passe par la persistance
de Minecraft : un plantage entre deux autosaves perd les découvertes récentes, et un
rollback du monde ramène le registre des œufs en arrière en même temps que le terrain — ce
qui est généralement ce qu'on veut, puisque les blocs reculent aussi.

Le fait que le registre vive sur l'overworld plutôt que par dimension est volontaire :
l'entrée d'un œuf doit être trouvable depuis le dashboard sans savoir dans quel monde il
se trouve.

## Threads

Trois threads comptent.

**Le thread serveur.** Les interactions de bloc, le chargement et le déchargement des
block entities, la vidange de la file de réconciliation en fin de tick, les commandes, et
toute mutation du registre. Tous les appelants de `LiveAdminEggIndex` sont ici.

**Un thread HTTP de CheeseDash.** `EggsApi.handle` s'y exécute. Chaque handler qui touche
l'état du jeu enveloppe son travail dans
`WebDashboard.executeOnServerThread(server, () -> { ... })`, recueille le résultat dans un
tableau à un élément, et répond depuis le thread HTTP une fois le travail fait sur le
thread serveur. `GET /enums` est le seul handler à sauter ce saut, parce qu'il ne lit que
des enums Java.

L'idiome du tableau à un élément est la façon dont un `Runnable` renvoie une valeur. Cela
paraît étrange et c'est correct : le tableau est écrit sur le thread serveur et lu sur le
thread HTTP après le retour d'`executeOnServerThread`, qui est le point de
synchronisation.

**Le thread de rendu client.** `ProceduralTextureGenerator` et
`CollectibleTextureCache` y vivent. La génération de texture est synchrone à la première
apparition d'un ADN, d'où le bref à-coup quand un joueur entre dans un champ d'œufs tous
différents.

### Le différé qui garde le serveur en vie

`AdminEggPlacedEvent` ne fait jamais de travail dans son propre handler d'événement.
`BLOCK_ENTITY_LOAD` empile sur une `ConcurrentLinkedQueue` ;
`ServerTickEvents.END_SERVER_TICK` la vide, jusqu'à 8192 tâches par tick.

**Ne jamais forcer le chargement d'un chunk depuis un handler `BLOCK_ENTITY_LOAD` ou
`CHUNK_LOAD`.** C'est la règle la plus chèrement acquise du mod. Un correctif de juin 2026
appelait `getBlockEntity` sur une position possiblement déchargée depuis l'intérieur du
handler de chargement. `BLOCK_ENTITY_LOAD` se déclenche à l'intérieur du pipeline de
montée en niveau des chunks de c2me : cet appel réentrait donc dans le système de chunks
depuis lui-même et bloquait le thread serveur sur un travail qui ne pouvait pas avancer. Le
watchdog a tiré, la production a planté deux fois dans la même nuit, puis est entrée en
boucle de plantage parce que le même chunk rechargeait à chaque démarrage.

Deux choses dans le code empêchent la récidive, et un contributeur doit préserver les
deux :

1. **La file vidée en fin de tick**, pas `server.execute`. `ThreadExecutor.execute`
   exécute un runnable en ligne quand l'appelant est déjà sur le thread serveur — ce qui,
   dans un handler de chargement, est toujours le cas. `server.execute` réintroduirait
   exactement le blocage. Le code source porte un commentaire qui le dit ; ne le
   « simplifie » pas.
2. **`isChunkLoaded` avant toute lecture inter-chunks.** `unbindAt` interroge
   `world.getChunkManager().isChunkLoaded(cx, cz)` — une requête d'état non bloquante,
   sans `getChunkBlocking` en dessous — et abandonne quand la réponse est non. Le bloc est
   réconcilié plus tard, quand son propre événement de chargement se déclenche.

Le récit complet est dans
[intégrité](integrity.fr.md#ne-jamais-forcer-le-chargement-dun-chunk-depuis-un-handler-de-chargement-de-block-entity).

## Réseau

Un payload, du serveur vers le client :

```java
// ModPayloads
record SyncRegistryPayload(byte[] data) implements CustomPayload
// id : eggandcheese:sync_registry
```

Il transporte un buffer écrit à la main : le nombre d'entrées, puis chaque entrée (id,
type, ADN en NBT, position, dimension, échelle, nombre de découvertes, positionLost,
titre, sous-titre), puis `ftbQuestsEnabled`, `ftbQuestsChapter` et
`textureRenderDistance`.

Le client ne lit que le dernier champ. `EggAndCheeseClient.registerClientPackets` jette la
liste des entrées et affecte `data[3]` à `EggRenderConfig.textureRenderDistance`. Tout le
registre est sérialisé et envoyé pour livrer un seul entier.

`ModPackets.registerServerHandlers()` n'enregistre rien — il n'y a aucun paquet du client
vers le serveur. Toute action d'administration arrive par HTTP.

## Points d'extension

Si tu ajoutes quelque chose à ce mod, les coutures sont les suivantes.

**Une nouvelle composante d'ADN.** Ajoute le champ au record `CollectibleDNA`, puis mets à
jour, dans cet ordre : `toNbt`/`fromNbt`, `toDnaCode`/`fromDnaCode`, `cacheKey()`,
`randomDNA`, `EggsApi.applyEdits`, `EggsApi.buildDetail`, et les deux générateurs de
texture. Oublier `cacheKey()` fait rendre deux œufs différents à l'identique ; oublier
`applyEdits` rend le champ non modifiable par l'API. La liste des champs du record est de
fait dupliquée à sept endroits, et c'est le coût principal de cette conception.

**Un nouveau motif, type émissif, tampon, micro-texture ou palette.** Ajoute la constante
d'enum, puis implémente sa branche dans `ProceduralTextureGenerator` **et** dans
`CheeseDash/assets/egg-preview.js`. `GET /enums` reprend la nouvelle valeur sans travail
supplémentaire : la liste déroulante du dashboard se remplit toute seule. Utilise les
fonctions de bruit sans raccord et les fonctions de dessin enroulées, sinon ton motif
laissera une couture verticale.

**Une nouvelle route d'API.** Ajoute une branche dans `EggsApi.handle` ou
`EggsApi.handleCollectibleRoutes`. Suis la forme existante : valider d'abord, envelopper
l'accès à l'état dans `executeOnServerThread`, répondre avec Gson. Et commence tout
handler mutant par `if (!WebDashboard.requireWrite(exchange)) return;` — aucun ne le fait
aujourd'hui, et c'est le manque listé plus bas.

**Une nouvelle variante de stèle.** Une entrée dans `ModBlocks`, une dans `ModItems`, une
dans la liste de blocs du builder `ModBlockEntities.PEDESTAL`, une constante
`PedestalVariant`, un blockstate et un modèle JSON, une recette, et deux clés de langue.
Rien d'autre.

**Surveiller les découvertes depuis un autre mod.** Écoute le critère d'advancement
`eggandcheese:egg_found` plutôt que de toucher aux classes de ce mod. Il est enregistré
dans `Registries.CRITERION` et porte l'id de l'œuf dans ses conditions.

## Faiblesses connues

Énoncées franchement, parce que la prochaine personne à maintenir ce mod les rencontrera.

**Aucun contrôle de rôle dans l'API.** `EggsApi` ne contient aucun appel à `requireWrite`
ni à `requireAdmin`. Toutes les routes sont ouvertes à n'importe quelle session CheeseDash
authentifiée, `READ_ONLY` comprise. La forme correcte dans ce dépôt est
`if (!WebDashboard.requireWrite(exchange)) return;` — la méthode renvoie un booléen et
n'impose rien si son résultat est ignoré. C'est le problème le plus sérieux de cette page.

**Le renommage orpheline le bloc du monde.** `POST /collectibles/:id/rename` change la clé
du registre et laisse le block entity porter l'ancien `registryId`. Le résultat est un
bloc orphelin et une entrée sans bloc, à chaque fois. Le renommage devrait réécrire aussi
l'id du block entity, ou au minimum refuser quand le bloc est chargé.

**`CollectibleType.CHEESE` est à moitié implémenté.** L'enum l'a, l'API l'accepte,
`GET /stats` le compte, des blockstates et des modèles JSON existent pour `admin_cheese`
et `cheese_deco` — et aucun bloc ni item de fromage n'est jamais enregistré.
`CollectibleItem.getBlock` ignore son argument `type` et renvoie toujours
`ModBlocks.EGG_DECO`. Créer un collectible avec `"type":"cheese"` donne un œuf portant une
étiquette de fromage. Il faut finir ou retirer.

**Trois défauts contradictoires pour un même réglage.** `textureRenderDistance` vaut `20`
côté serveur, `20` côté client avec un Javadoc qui annonce `10`, et `10` dans le repli de
lecture du paquet.

**La distance de rendu n'atteint jamais un client qui se connecte.** `sendRegistrySync`
est appelé à un seul endroit : `PUT /config`. Un client qui se connecte et ne voit jamais
de changement de configuration garde son défaut compilé. Le correctif serait un envoi sur
`ServerPlayConnectionEvents.JOIN`.

**Le paquet de sync envoie tout le registre pour livrer un entier.** L'ADN de chaque
entrée est sérialisé dans un tableau d'octets que le client jette immédiatement. Avec
quelques centaines d'œufs, cela fait un paquet mesurable pour rien.

**`ftbAutoSync` est un réglage mort.** Persisté, exposé par l'API, affiché dans le
dashboard, lu par personne.

**`/eac reload` ne recharge rien.** Il affiche le nombre d'entrées. Le nom est un
vestige.

**`/pedestal` n'a aucune exigence de permission.** N'importe quel joueur au niveau 0 peut
rerégler n'importe quelle stèle du serveur, y compris dans une construction protégée.

**Le hook FTB est de la pure réflexion.** 733 lignes contre les internes de
`dev.ftb.mods.ftbquests.*`. Il se dégrade proprement et journalise ce qu'il a résolu, mais
il **cassera** quand FTB Quests se restructurera, et le mode de défaillance est le silence.
Détails et repli dans [l'intégration FTB Quests](ftb-quests.fr.md).

**Deux pipelines de texture maintenus en phase à la main.** Le générateur Java et
`egg-preview.js` implémentent les mêmes 13 étapes deux fois, dans deux langages, dans des
fichiers relevant de deux dépôts. Ils divergent dès que quelqu'un modifie l'un sans
l'autre, et rien ne le détecte.

**`PedestalExtensionBlock` peut rester orphelin.** Retirer une stèle par `/setblock` ou
WorldEdit laisse derrière des blocs d'extension incassables, retirables seulement par
`/setblock`.

**Le droit à la déco est partagé entre admins et joueurs.** Un admin qui prend la copie
décorative de son propre œuf est inscrit dans `decoReceivedBy` et ne pourra plus jamais y
enregistrer de découverte. Voir
[placement et découvertes](placement-and-discovery.fr.md#le-clic-droit-dun-admin-nest-pas-une-découverte).

**Les messages de découverte affichent l'id brut.** `title` existe et sert à la quête FTB,
mais la ligne de barre d'action destinée au joueur passe `registryId`.

**Les noms d'affichage des enums sont français dans le code Java.** `getDisplayName()`
renvoie `"Cristaux"`, `"Glace"`, `"Épique"` et ainsi de suite, ce que lit un client anglais
dans une infobulle d'item et ce que `GET /enums` renvoie en `displayName`. Il n'y a aucune
couche de traduction pour eux.

**La disposition des quêtes est une longue rangée.** `x = index * 2.5`, `y = 0`. Cinquante
œufs font une rangée de 125 unités dans le livre de quêtes.

## Code mort

**`JourneyMapIntegration`** n'est référencé par rien. Son `addWaypoint` ne fait que
journaliser `[EggAndCheese] Would add JourneyMap waypoint for: <id>`, et
`addClientWaypoint` construit un waypoint par réflexion mais n'est jamais appelé. Il
déclare en outre `package com.roicheese.eggandcheese.integration` tout en se trouvant dans
`src/client/java/com/roicheese/eggandcheese/` — le dossier et le package ne correspondent
pas. `javac` le tolère, donc cela compile, mais aucun outil ne trouvera le fichier là où
le package prétend qu'il est. Il faut le brancher, le déplacer ou le supprimer.

**`PacketExtras.register()`** est un no-op intentionnel, conservé pour qu'`EggAndCheese` et
`AdminCommand` compilent encore après le retrait de l'interface d'administration en jeu.

**`NbtUtils.getOrCreate`** est écrit mais inutilisé dans le code actuel. `setSubNbt` l'est
tout autant.

**Les alias de flags historiques** `FLAG_OUTLINE` et `FLAG_EDGE_GLOW` sont conservés comme
alias de `FLAG_EMBOSS` et `FLAG_RIM_GLOW`.

**L'en-tête de section d'`egg-preview.js`** annonce `PATTERNS (18 types)` au-dessus de 26
branches. Commentaire périmé, sans conséquence, mais il raconte comment le fichier a
grossi.

## Renommage du package

Le package Java était `com.roicheese.eggandcheese` avant l'ouverture du code ; il est
désormais `com.roicheese.eggandcheese`. Un jar ou une branche antérieurs au renommage
portent l'ancienne racine, et un addon compilé contre eux ne chargera pas contre
celui-ci.

Rien n'a changé dans les ressources. L'espace de noms des assets et des données est
`eggandcheese`, dérivé de l'id du mod et non du package : `eggandcheese:egg_found`,
`eggandcheese:admin_egg` et tous les ids de recette sont inchangés. Un datapack écrit pour
l'ancienne version fonctionne encore.

Aucune trace de l'ancien nom de package ne subsiste dans `src/`, `build.gradle` ou
`fabric.mod.json` — `maven_group` vaut `com.roicheese`. Si tu en trouves une dans une
branche, signale-la plutôt que de la corriger sur place : le correctif doit être cohérent
sur toute la collection.

---

← [API dashboard](dashboard.fr.md) · [Dépannage](troubleshooting.fr.md) →
