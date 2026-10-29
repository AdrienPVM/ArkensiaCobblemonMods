[← CheeseGuard](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](timeline.md)

# Chronologie

La chronologie, c'est ce qui donne un scénario à un événement. Au lieu de « cet événement
dure dix minutes et applique Vitesse », tu places des blocs sur des pistes : un titre à la
seconde 0, une bordure qui rétrécit à partir de la seconde 30, un spawn de Pokémon toutes les
45 secondes, un vote à la seconde 300. `TimelineExecutor` (`logic/TimelineExecutor.java`,
~1300 lignes) relit cette disposition une fois par seconde et déclenche ce qui est dû.

L'éditeur, c'est la vue Événements du dashboard, onglet chronologie. Il n'existe pas
d'éditeur en jeu.

## Actes, pistes, blocs

Trois niveaux emboîtés.

**Un acte** (`data/EventAct.java`) est un chapitre. Un événement est une liste d'actes joués
dans l'ordre, chacun avec sa propre chronologie. Un acte possède un titre et un son d'entrée,
une règle de transition, et éventuellement un comportement de mort qui remplace celui de
l'événement pour sa durée.

**Une piste** est une ligne horizontale dans l'éditeur. Les pistes n'ont aucun comportement
propre — elles regroupent les blocs visuellement et donnent à chacun une couleur et une
icône. Il y en a 22, déclarées en constantes dans `data/TimelineBlock.java` :

```text
messages · potions · zones · spawns · commands · weather · sounds · score
flags · roles · votes · players · state_flags · random · objectives
death · dimension · inventory · visibility · redstone · stats · logic
```

La valeur `track` d'un bloc est cosmétique. Déplacer un bloc `POTION` sur la ligne `weather`
ne change rien à ce qu'il fait. Le seul endroit où la piste compte fonctionnellement est
`EventAct.fromLegacyPhase()`, qui choisit une piste par type d'effet lors de la migration
d'un ancien événement à phases.

**Un bloc** (`data/TimelineBlock.java`) est un effet, placé dans le temps :

| Champ | Clé JSON | Signification |
|---|---|---|
| `id` | `id` | 8 caractères hexadécimaux, générés à la création |
| `track` | `track` | la ligne sur laquelle il se trouve |
| `lane` | `lane` | sous-ligne dans la piste, pour les blocs qui se chevauchent (omise si 0) |
| `effectType` | `effectType` | un des types listés plus bas |
| `startSeconds` | `start` | secondes depuis le début de l'**acte**, pas de l'événement |
| `durationSeconds` | `duration` | `0` signifie instantané, un seul coup |
| `repeatIntervalSeconds` | `repeat` | `0` = pas de répétition ; sinon, redéclenchement toutes les N secondes |
| `conditionType` | `condType` | une garde évaluée avant chaque déclenchement |
| `conditionParam` | `condParam` | l'argument textuel de la condition |
| `conditionValue` | `condValue` | l'argument numérique de la condition |
| `properties` | `props` | les paramètres propres à l'effet, objet JSON libre |
| `label` | `label` | remplace la légende générée automatiquement |
| `colorOverride` | `color` | couleur hexadécimale imposée au bloc |

Un bloc instantané minimal, tel qu'il est stocké dans le tableau `blocks` d'un acte :

```json
{
  "id": "a1b2c3d4",
  "track": "messages",
  "effectType": "TITLE",
  "start": 0,
  "duration": 0,
  "props": {
    "titleText": "Round 1",
    "subtitleText": "Bonne chance",
    "titleColor": "gold",
    "fadeIn": 10,
    "stay": 60,
    "fadeOut": 10
  }
}
```

Un bloc à durée, avec répétition et condition :

```json
{
  "id": "9f8e7d6c",
  "track": "potions",
  "effectType": "POTION",
  "start": 30,
  "duration": 120,
  "repeat": 10,
  "condType": "PLAYER_COUNT_ABOVE",
  "condValue": 4,
  "props": { "potionId": "minecraft:speed", "amplifier": 1, "potionDuration": 240 }
}
```

### Comment un bloc se déclenche

`tickBlocks()` tourne une fois par seconde avec `elapsed` = secondes depuis le début de
l'acte.

- **Bloc instantané** (`duration == 0`) : se déclenche une fois, au premier tick où
  `elapsed >= start`. Avec `repeat > 0`, il se redéclenche ensuite toutes les `repeat`
  secondes, tant que l'acte dure.
- **Bloc à durée** : au premier tick dans `[start, start+duration)`, il exécute son
  gestionnaire de *début* ; puis son gestionnaire de *tick* chaque seconde, ou toutes les
  `repeat` secondes si `repeat > 0` ; puis son gestionnaire de *fin* au premier tick à partir
  de `start+duration`.

Trois conséquences à connaître. Un bloc dont le `start` dépasse la durée de l'acte ne se
déclenche jamais. Le gestionnaire de fin d'un bloc à durée est ce qui défait un `MULTIPLIER`
ou un `FLAG_OVERRIDE` : un bloc encore actif à la fin de l'acte n'obtient donc jamais sa fin
— le nettoyage au niveau de l'événement dégèle les joueurs et vide les flags, mais un
multiplicateur posé par un bloc inachevé reste sur l'objet événement jusqu'à
`resetRuntime()`. Enfin, `repeat` sur un bloc à durée change la cadence du *tick*, pas un
déclenchement supplémentaire : un bloc `POTION` avec `repeat: 10` réapplique la potion toutes
les 10 secondes au lieu de chaque seconde.

### Conditions de bloc

Évaluées avant chaque déclenchement. `NONE` signifie toujours.

| `condType` | Utilise | Vrai quand |
|---|---|---|
| `PLAYER_COUNT_BELOW` | `condValue` | moins de N participants (spectateurs exclus) |
| `PLAYER_COUNT_ABOVE` | `condValue` | plus de N participants |
| `SCORE_ABOVE` | `condValue` | le score d'au moins un participant dépasse N |
| `SCORE_BELOW` | `condValue` | le score d'au moins un participant est sous N |
| `TEAM_SCORE_ABOVE` | `condValue` | déclaré, retombe sur `true` — non implémenté |
| `TIME_OF_DAY_DAY` | — | déclaré, retombe sur `true` — non implémenté |
| `TIME_OF_DAY_NIGHT` | — | déclaré, retombe sur `true` — non implémenté |
| `PLAYER_FLAG_TRUE` | `condParam` | au moins un participant a ce flag d'état à `true` |
| `PLAYER_FLAG_FALSE` | `condParam` | aucun participant ne l'a à `true` |
| `RANDOM_CHANCE` | `condValue` | un tirage frais sous N pour cent |

Les trois conditions non implémentées tombent dans la branche `default -> true`, c'est-à-dire
qu'un bloc gardé par elles se déclenche sans condition. C'est le mauvais sens de défaillance
pour une garde : ne les utilise pas.

`SCORE_ABOVE` et `SCORE_BELOW` signifient « au moins un participant », pas « tous les
participants ». Avec un peloton hétérogène, les deux peuvent être vraies en même temps.

## Transitions d'acte

`checkActTransition()` tourne après les blocs, chaque seconde.

| `transitionType` | Avance quand |
|---|---|
| `DURATION` | `actElapsedSeconds >= durationSeconds` |
| `CONDITION` | la `transitionCondition` configurée est satisfaite |
| `MANUAL` | jamais automatiquement — un administrateur doit le faire avancer |

Quand le **dernier** acte se termine, l'exécuteur pose `signalEnd` sur l'événement et
`EventManager` l'emmène en `ENDING` au tick suivant. Un événement composé uniquement d'actes
`MANUAL` tourne donc jusqu'à ce que quelque chose l'arrête.

Conditions de transition :

| `transitionCondition` | Utilise | Satisfaite quand |
|---|---|---|
| `PLAYERS_ALIVE_BELOW` | `transitionCondValue` | il reste moins de N participants |
| `SCORE_REACHED` | `transitionCondValue` | le score d'un participant atteint N |
| `ALL_TASKS_COMPLETE` | `transitionCondValue` | le flag `tasks_done` de chaque participant est ≥ N |
| `VOTE_COMPLETE` | — | aucun vote n'est actif pour cet événement |
| `CUSTOM_FLAG` | `transitionCondParam`, `transitionCondValue` | le flag nommé d'un participant vaut la valeur |
| `TIME_OF_DAY` | — | déclaré, renvoie `false` — non implémenté |

`VOTE_COMPLETE` est satisfaite quand *aucun* vote ne tourne, ce qui inclut le cas où aucun
vote n'a jamais été lancé. Associe-la à un bloc `START_VOTE` plus tôt dans le même acte, sans
quoi elle avance au premier tick.

Le démarrage d'un acte efface tout l'état d'exécution par bloc — marqueurs de déclenchement,
ensemble des blocs actifs, derniers horodatages. Un bloc de l'acte 2 est évalué depuis une
ardoise propre même si un bloc identique a tourné dans l'acte 1.

## Types d'effets

61 types sont déclarés dans `data/EventEffect.Type`. Ils ne sont pas tous implémentés dans
l'exécuteur de chronologie ; le tableau le signale pour chacun. Un type non implémenté reste
sélectionnable dans l'éditeur et sauvegardé sur le disque — il se contente d'une ligne de log
en niveau debug et ne fait rien :

```text
[CG-TL] Unhandled instant block type: APPLY_KNOCKBACK
```

### Messages et ATH

| Type | Mode | Propriétés | Comportement |
|---|---|---|---|
| `BROADCAST` | instantané / début de durée | `message`, `color`, `channel` (`chat`/`actionbar`/`title`), `subtitle`, `global` | envoie aux participants ; `global: true` envoie à tout le serveur |
| `TITLE` | instantané / début de durée | `titleText`, `subtitleText`, `titleColor`, `fadeIn` (10), `stay` (60), `fadeOut` (10) | écran de titre, durées en ticks |
| `ACTIONBAR_PERSISTENT` | tick de durée | `message`, `color` | réécrit à chaque intervalle de tick, donc il reste affiché |
| `OBJECTIVE_HUD_UPDATE` | — | — | **non implémenté** |
| `BROADCAST_SCORE_BOARD` | — | — | **non implémenté** |
| `STATS_BROADCAST` | — | — | **non implémenté** |

### Potions, sons, météo, temps

| Type | Mode | Propriétés | Comportement |
|---|---|---|---|
| `POTION` | tick de durée | `potionId` (`minecraft:speed`), `amplifier` (0), `potionDuration` (1200 ticks), `target` | réapplique à chaque intervalle de tick |
| `SOUND` | instantané | `soundId`, `volume` (1.0), `pitch` (1.0), `target` | joué par joueur |
| `WEATHER` | instantané / début de durée | `weatherType` (`clear`) | règle la météo du monde de l'événement |
| `TIME` | instantané / début / tick | `timeValue` (6000), `freeze` | avec `freeze: true`, l'heure est réimposée à chaque tick, ce qui la fige |
| `PARTICLE` | — | — | **non implémenté** dans la chronologie (uniquement sur le chemin des phases historiques) |

`potionDuration` doit confortablement dépasser l'intervalle de tick, sinon les joueurs
clignotent entre avec et sans effet. La valeur par défaut de 1200 ticks (60 s) face à un tick
de 1 s est volontairement généreuse.

### Commandes et aléatoire

| Type | Mode | Propriétés | Comportement |
|---|---|---|---|
| `COMMAND` | instantané / début + tick de durée | `command`, `target` (`all`/`zone`/`random`/`team`) | exécutée depuis la console au niveau 4 |
| `COMMAND_PLAYER` | instantané | `command`, `target` | exécutée en tant que chaque joueur visé |
| `WEIGHTED_RANDOM_COMMAND` | instantané | `cmd0`, `weight0`, `cmd1`, `weight1`, … , `target` | tire une commande selon les poids |
| `CONDITIONAL_BRANCH` | — | — | **non implémenté** |

Le bloc aléatoire pondéré lit `cmd0`, `cmd1`, … jusqu'à la première clé manquante, avec
`weightN` à 1 par défaut. Un trou dans la numérotation tronque silencieusement la liste.

Attention : `COMMAND` sur un bloc à durée se déclenche au *début* **et** à chaque tick. Un
bloc avec `duration: 60` et sans `repeat` exécute sa commande 61 fois. Utilise `duration: 0`
pour un coup unique.

### Zones

| Type | Mode | Propriétés | Comportement |
|---|---|---|---|
| `ZONE_SHRINK` | tick de durée | `zoneCenterX`/`cx`, `zoneCenterZ`/`cz`, `radiusInitial` (200), `radiusFinal` (50), `curve` (`linear`), `zoneDamage`/`damagePerSec` (1.0), `showBorder`, `orbitEnabled`, `orbitRadius`, `orbitSpeed` | interpole le rayon sur la durée du bloc et blesse les joueurs à l'extérieur |
| `ZONE_HEAL` | tick de durée | `amount` (1.0), `target` | soigne à chaque tick |
| `ZONE_DAMAGE` | tick de durée | `amount` (1.0), `target` | blesse à chaque tick |
| `ZONE_POWERUP` | début/tick/fin de durée | `powerX`, `powerY`, `powerZ`, `powerWorld`, `powerRadius` (3.0), `powerType` (`SPEED`), `powerAmplifier` (1), `powerDurationTicks` (200), `singleUse`, `cooldownSeconds`, `powerLabel`, `visualBlock` (`minecraft:end_rod`) | un bonus au sol : pose un bloc visuel, accorde le pouvoir au contact, retire le bloc à la fin |
| `ZONE_LOOT` | — | — | **non implémenté** |
| `ZONE_MOVE` | — | — | **non implémenté** |
| `FLAG_OVERRIDE` | instantané / début + fin de durée | `flagId`, `targetZone`, `flagValue` | impose un flag de zone pendant la durée du bloc et le restaure à la fin |

`ZONE_SHRINK` lit deux orthographes pour son centre (`zoneCenterX` et `cx`) parce que la
migration historique écrit l'une et l'éditeur l'autre. Les particules de bordure sont dessinées
par une boucle à 3 ticks séparée dans `EventManager`, et non par le tick du bloc : c'est
pourquoi la bordure paraît fluide alors que les dégâts tombent une fois par seconde.

### Spawns

| Type | Mode | Propriétés | Comportement |
|---|---|---|---|
| `ENTITY_SPAWN` | instantané | `command` | exécute une commande de spawn depuis la console |
| `POKEMON_SPAWN` | instantané | `species`, `level` (50), `x`, `y` (64), `z`, `world` | fait apparaître via le hook Cobblemon |
| `SPAWN_ITEM_AT_POS` | instantané | `itemId` (`minecraft:diamond`), `x`, `y` (64), `z` | lâche une entité item |
| `SPAWN_STRUCTURE` | début de durée | `structureName`, `x`, `y` (64), `z` | place une structure |
| `SET_BLOCK_AT_POS` | instantané | `blockId` (`minecraft:air`), `bx`, `by` (64), `bz`, `world` | un `setblock` |
| `COBBLEMON_FORCE_ENCOUNTER` | — | — | **non implémenté** |
| `RESPAWN_ENTITY_AT_POS` | — | — | **non implémenté** |
| `RANDOM_LOOT_AT_POSITIONS` | — | — | **non implémenté** |
| `REDSTONE_ACTIVATE` | — | — | **non implémenté** |

### Joueurs

| Type | Mode | Propriétés | Comportement |
|---|---|---|---|
| `FREEZE_PLAYERS` | instantané / début + fin de durée | — | les participants ne peuvent plus bouger ; imposé à chaque tick et relâché à la fin |
| `UNFREEZE_PLAYERS` | instantané | — | relâche tout le monde |
| `GIVE_ITEM_TO_PLAYER` | instantané | `itemId` (`minecraft:diamond`), `count` (1), `target` | |
| `TAKE_ITEM_FROM_PLAYER` | instantané | `itemId`, `count`, `target` | |
| `TELEPORT_PLAYER_TO_PLAYER` | instantané | `targetRole` | téléporte les joueurs sur le porteur d'un rôle |
| `ITEM_DROP` | — | — | **non implémenté** dans la chronologie (phases historiques seulement) |
| `SET_PLAYER_HEALTH` | — | — | **non implémenté** |
| `SET_PLAYER_HUNGER` | — | — | **non implémenté** |
| `APPLY_KNOCKBACK` | — | — | **non implémenté** |
| `LOCK_HOTBAR` / `UNLOCK_HOTBAR` | — | — | **non implémenté** |
| `DIMENSION_TELEPORT_ALL` | — | — | **non implémenté** |
| `INVENTORY_DISTRIBUTE` | — | — | **non implémenté** |
| `SET_PLAYER_VISIBILITY` | — | — | **non implémenté** |

Le gel est détenu par l'exécuteur, pas par le bloc : l'ensemble des joueurs gelés vit par
événement et est vidé dans `end()`. Un bloc instantané `FREEZE_PLAYERS` sans
`UNFREEZE_PLAYERS` correspondant immobilise les joueurs jusqu'à la fin de l'événement.

### Rôles, votes et flags d'état

| Type | Mode | Propriétés | Comportement |
|---|---|---|---|
| `ASSIGN_ROLE_PRIVATE` | instantané | `roleName`, `strategy` (`random`/`first`/`all`), `count` (1), `revealMessage`, `revealColor`, `otherRoleName`, `otherRevealMessage`, `otherRevealColor` | attribue un rôle caché à N joueurs et, au besoin, un second rôle à tous les autres |
| `TRANSFER_ROLE` | instantané | `fromRole`, `toRole` | renomme un rôle chez celui qui le détient |
| `START_VOTE` | instantané | `question`, `voteType` (`OPTION_VOTE`), `duration` (30), `opt0`, `opt1`, … | ouvre un vote via `VoteManager` |
| `PLAYER_FLAG_SET` | instantané | `flagKey`, `flagValue`, `target` | écrit un flag d'état par joueur |
| `CHANGE_TEAM_COLOR` | — | — | **non implémenté** |

Les rôles et les flags d'état passent tous deux par `PlayerStateFlags`, cloisonné par
événement et effacé à la fin. Le rôle caché est rangé sous la clé de flag `role`, qui est
aussi ce que lit le filtre `target: "role"` — c'est ainsi qu'un bloc peut ne s'adresser
qu'aux imposteurs.

Les options de vote, comme les commandes pondérées, sont lues en `opt0`, `opt1`, … jusqu'à la
première clé manquante.

### Score et mort

| Type | Mode | Propriétés | Comportement |
|---|---|---|---|
| `MULTIPLIER` | début + fin de durée | `multType` (`score`), `multValue` (2.0) | pose un multiplicateur pendant l'activité, le retire à la fin |
| `DEATH_BEHAVIOR_OVERRIDE` | instantané | `behavior` (`RESPAWN_EVENT`) | remplace le comportement de mort de l'événement pour le reste de l'exécution |

### Effets inter-mods

Neuf types d'effets vont piocher dans d'autres mods. Tous passent par des hooks de réflexion
dans `logic/` : CheeseGuard compile et tourne sans aucun de ces mods installés. Chaque hook
résout sa classe cible une fois au démarrage du serveur et consigne le résultat :

```text
[CG] CobbleShift detected — transform effects enabled
[CG] CheeseKart not installed — kart effects disabled
[CG] CobblemonDungeons detected — dungeon effects enabled
```

| Type | Mod visé | Propriétés | Si le mod est absent |
|---|---|---|---|
| `TRANSFORM_ALL` | CobbleShift | `transformSpecies` (`pikachu`) | `[CG-TL] TRANSFORM_ALL: CobbleShift not available` en **warn**, le bloc ne fait rien |
| `TRANSFORM_RANDOM` | CobbleShift | `transformSpeciesList` (`pikachu,charizard,mewtwo`), `allowDuplicates` (true) | idem, avec le nom du type dans le message |
| `TRANSFORM_BY_TEAM` | CobbleShift | `transformTeamMap` (un objet JSON sous forme de chaîne) | idem |
| `TRANSFORM_REVERT_ALL` | CobbleShift | — | `CobbleShiftHook.revertAll()` attrape l'erreur et la consigne en debug ; rien ne se passe |
| `KART_START_ALL` | CheeseKart | `kartPresetId` (`rally`), `kartSpeciesOverride` (vide) | `[CG] Cannot start karts — CheeseKart not available` en warn |
| `KART_STOP_ALL` | CheeseKart | — | retourne silencieusement |
| `DUNGEON_OPEN` | CobblemonDungeons | `dungeonId`, `dungeonTier` (1) | `[CG-TL] DUNGEON_OPEN: CobblemonDungeons not available` en warn |
| `DUNGEON_TICKET_GIVE` | CobblemonDungeons | `dungeonId`, `dungeonTier` (1), `ticketCount` (1) | idem |
| `DUNGEON_MODIFIER` | CobblemonDungeons | `dungeonId`, `modifierType` (`DOUBLE_LOOT`), `modifierValue` (2.0) | s'exécute quand même — voir plus bas |

Le type s'écrit **`TRANSFORM_REVERT_ALL`**, et non `REVERT_ALL`. Certaines notes anciennes
emploient le nom court ; la constante de l'énumération et la valeur stockée dans `effectType`
sont la forme longue.

`TRANSFORM_BY_TEAM` ne lit les équipes d'aucun système d'équipes. Il découpe la liste des
participants en blocs de `event.teamSize` selon leur position dans la collection, et associe
les indices de bloc 0, 1, 2 … aux clés de `transformTeamMap`. L'ordre des participants vient
des valeurs d'une `HashMap` : il n'est donc pas stable d'une exécution à l'autre. Prends
l'attribution d'équipe ici pour « arbitraire mais équilibrée », pas pour « l'équipe rouge
devient Dracaufeu ».

`DUNGEON_OPEN` sort de sa boucle après le premier participant : il lance une seule instance
de donjon avec le premier joueur comme chef, qui que ce soit. Il vérifie aussi
`dungeonExists()` d'abord et consigne `dungeon '<id>' not found` au lieu de lever une
exception.

`DUNGEON_MODIFIER` **n'est pas branché sur CobblemonDungeons du tout**. Il ne vérifie pas
`isAvailable()`, n'appelle pas le hook et ne modifie rien. Il consigne `(broadcast only)` et
envoie à chaque joueur connecté une ligne de chat traduite. Le commentaire du code dit que
l'intégration reste à faire. Si tu poses ce bloc en espérant du butin doublé, tu obtiens un
message et rien d'autre.

## Interaction avec les mini-jeux

Un événement en mode `MINIGAME` joue sa chronologie de la même façon, mais `MiniGameManager`
pilote en plus sa propre logique par type sur des ticks plus rapides (tous les 4 ticks pour
les atterrissages Thimble, chaque tick pour les points de contrôle de course). Les deux ne se
coordonnent pas : un bloc de chronologie qui téléporte les joueurs pendant une course se
battra avec la logique de course. Sur un événement mini-jeu, limite la chronologie aux
messages et au décor, à moins d'avoir lu le code du mini-jeu.

## Phases historiques

Avant les actes, un événement avait des `phases`, chacune portant une liste plate d'objets
`EventEffect`. `useActSystem` choisit la structure utilisée ; les événements chargés depuis
une ancienne configuration sont migrés par `EventAct.fromLegacyPhase()`, qui crée un acte par
phase et transforme chaque effet en bloc avec `start: 0` et `duration` égale à la longueur de
la phase.

La migration est mécanique. Tout ce qui se trouvait dans une phase démarre simultanément en
tête d'acte, ce qui n'est presque jamais le minutage réel de la phase. Ouvre tout événement
migré dans l'éditeur et réespace les blocs avant de le lancer.

`PARTICLE` et `ITEM_DROP` sont les deux effets qui fonctionnent encore sur le chemin des
phases historiques mais n'ont aucun gestionnaire dans `TimelineExecutor` — migrer un événement
qui les utilisait les perd silencieusement.

---

← [Événements](events.fr.md) · [Joueurs](players.fr.md) →
