[← CheeseRaids](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](configuration.md)

# Configuration

CheeseRaids n'a pas de fichier de configuration global. Toute la configuration vit dans **un
fichier par raid** :

```text
config/cheeseraids/raids/<id>.json
```

Au premier démarrage du serveur, le mod crée le dossier et, s'il est vide, y écrit
`hoopa.json` depuis les valeurs Java par défaut de `RaidDefinition.defaultHoopa()`. Deux
chemins voisins relèvent de l'état d'exécution plutôt que de la configuration, et ne
doivent pas être édités à la main :

```text
config/cheeseraids/instances/<raidId>.json   état d'un raid en cours
config/cheeseraids/state.json               raidId → date du dernier déclenchement planifié
```

Une définition se recharge avec `/cheeseraids reload` ou `POST /api/v1/raids/reload`. Le
rechargement relit chaque `*.json` de `raids/` et remplace la table en mémoire. Un raid
**déjà en cours** ne conserve la définition de départ que dans la mesure où le gestionnaire
appelle `definitions.get(id)` à chaque tick — en pratique, un rechargement en pleine raid
change le comportement sous l'instance active. Recharge entre deux raids.

> ⚠️ Plusieurs clés du fichier ne sont **lues par aucun code**. Elles sont signalées
> ci-dessous comme *inutilisées*, avec ce à quoi elles semblaient destinées. Elles ne
> constituent pas une surface de configuration ; les modifier ne change rien.

---

## Niveau racine

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `id` | chaîne | `"hoopa"` | l'identifiant du raid. Le nom du fichier est ignoré — c'est ce champ que le mod utilise comme clé, et l'argument que prend chaque commande |
| `displayName` | chaîne | `"Invasion Hoopa"` | sert à nommer la dimension de faille (`<displayName> — Faille`) et s'affiche sur le dashboard |
| `icon` | chaîne | `"👻"` | emoji affiché dans la liste du dashboard. Aucun effet en jeu |
| `enabled` | booléen | `true` | à `false`, `start` refuse et le planificateur ignore le raid |
| `exitPos` | position | overworld `0.5 / 65 / 0.5` | où les joueurs sont téléportés à la fin du raid. **Un bouchon — voir [calibration](calibration.fr.md)** |

Un objet de position s'écrit `{ "world": "<namespace:id>", "x": 0.0, "y": 0.0, "z": 0.0, "yaw": 0.0, "pitch": 0.0 }`.
`world` est un identifiant de monde complet ; `yaw` et `pitch` ne comptent que là où le
joueur est téléporté.

C'est le champ `id`, et non le nom de fichier, qui fait foi. Nommer un fichier
`invasion.json` avec `"id": "hoopa"` te donne un raid appelé `hoopa`. Le mod ne réécrit
jamais une définition (seul le défaut du premier démarrage est enregistré), mais deux
fichiers portant le même `id` sont tous deux lus et le dernier lu remplace l'autre sans rien
dire, dans l'ordre du dossier. Garde-les alignés.

## `schedule`

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `autoTrigger` | booléen | `false` | active le déclenchement à heure fixe |
| `hour` | entier | `20` | heure locale du serveur, 0–23 |
| `minute` | entier | `0` | minute, 0–59 |
| `everyNDays` | entier | `1` | nombre minimal de jours entre deux déclenchements automatiques ; `1` = quotidien |

Le planificateur tourne une fois par seconde et déclenche quand l'heure **et** la minute
locales du serveur correspondent toutes les deux. Ce n'est pas un cron : il n'y a pas de
jour de la semaine, et un serveur arrêté pendant toute cette minute rate purement et
simplement le déclenchement. L'anti-rejeu est un horodatage par identifiant de raid dans
`state.json`, comparé avec `ChronoUnit.DAYS.between` : redémarrer le serveur pendant la
minute de déclenchement ne lance donc pas le raid deux fois.

`everyNDays` est ramené à 1 au minimum. Un raid déjà actif est ignoré, de même qu'un raid
avec `enabled: false`.

## `announce`

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `title` | chaîne | `"Invasion Hoopa"` | grand titre à l'écran, pour tous les joueurs |
| `subtitle` | chaîne | `"Une faille dimensionnelle s'ouvre..."` | sous-titre ; ignoré si vide |
| `chat` | chaîne | `"Une invasion Hoopa commence ! …"` | ligne de chat, préfixée `§d` ; ignorée si vide |
| `discord` | booléen | `true` | voir ci-dessous |
| `discordMessage` | chaîne | `"👻 Une **Invasion Hoopa** vient de commencer !"` | le texte qui serait envoyé |

`discord: true` n'envoie **rien** à Discord. Il écrit une ligne dans le journal du serveur :

```text
[CheeseRaids][Discord] 👻 Une **Invasion Hoopa** vient de commencer !
```

Il n'y a aucun code de webhook dans ce mod. Considère le couple comme le bouchon d'une
intégration qui n'a jamais été câblée.

Le titre et le sous-titre sont injectés dans une commande vanilla `title @a` sous forme de
JSON, avec `"` et `\` échappés. Les couleurs sont fixées dans le code (`light_purple` pour
le titre, `gray` pour le sous-titre) ; la configuration ne peut pas les changer.

## `wave`

La phase overworld : les Pokémon apparaissent, les joueurs les tuent.

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `arena` | position | overworld `0.5 / 65 / 0.5` | centre d'apparition. **Un bouchon** |
| `spread` | double | `6.0` | chaque mob est placé à un décalage aléatoire dans ±`spread` en X et Z |
| `spawns` | liste | meneur + sbires, ci-dessous | ce qui apparaît |
| `graceSecondsBeforeWave` | entier | `5` | délai entre l'annonce et l'apparition |
| `overworldStorm` | booléen | `true` | force `weather thunder 1200` et `time set midnight` dans le monde de la vague, réappliqué toutes les 600 ticks, puis `weather clear` au nettoyage |
| `checkIntervalTicks` | entier | `20` | cadence du test « vague nettoyée » |
| `timeoutSeconds` | entier | `180` | avance de force à la phase suivante si la vague ne se vide jamais. Ramené à 5 au minimum |
| `spawnCommand` | chaîne | un modèle `pokespawn` | **inutilisée** |
| `entityType` | chaîne | `"cobblemon:pokemon"` | **inutilisée** |
| `detectRadius` | double | `18.0` | **inutilisée** |
| `captureWindowMs` | entier | `3000` | **inutilisée** |

Les quatre clés inutilisées sont les vestiges d'une conception antérieure, où les mobs
étaient créés par une commande console puis retrouvés en comparant les entités autour de
l'arène. Le code actuel appelle Cobblemon directement par réflexion et garde la poignée sur
l'entité : il n'y a donc ni commande, ni rayon de détection, ni fenêtre de capture. Le champ
`RaidInstance.waveCaptureUntil` est toujours renseigné, à 0, et `isCapturing()` renvoie donc
toujours `false`.

`overworldStorm` mérite d'être compris avant d'être activé sur une carte vivante : il change
la météo et l'heure du jour du **monde entier** désigné par `wave.arena.world`, pas d'une
zone, et le nettoyage rétablit `weather clear` mais jamais l'heure.

### `wave.spawns[]`

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `species` | chaîne | `"hoopa"` | nom d'espèce Cobblemon |
| `aspects` | chaîne | `""` | noms d'aspects séparés par des virgules, émis chacun comme `aspect=<nom>` |
| `level` | entier | `70` | niveau du Pokémon ; omis de la chaîne de propriétés s'il est ≤ 0 |
| `count` | entier | `1` | combien en faire apparaître. Ramené à 1 au minimum |
| `leader` | booléen | `false` | désigne l'alpha |

Chaque mob de vague apparaît avec `uncatchable=true` et est marqué persistant au sens
vanilla, pour que le désapparitionneur de Cobblemon le laisse tranquille. La **première**
entrée avec `leader: true` devient l'alpha : il apparaît à l'échelle 1,8, reçoit un contour
lumineux et est suivi séparément. Le tuer termine la vague sur-le-champ, quoi qu'il reste de
vivant. Tout autre mob issu d'une entrée `leader` apparaît lui aussi à l'échelle 1.8, mais
sans contour lumineux et sans suivi.

Le `hoopa.json` livré contient deux entrées :

```json
[
  { "species": "hoopa",   "aspects": "unbound", "level": 80, "count": 1, "leader": true  },
  { "species": "sableye", "aspects": "",        "level": 60, "count": 4, "leader": false }
]
```

## `npc`

Le héraut Ho-Oh auquel les joueurs parlent pour entrer.

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `easyNpcName` | chaîne | `"hooh-raid"` | une étiquette. Substituée dans les modèles de commande comme `{npc}` ; inutilisée par ailleurs |
| `entityType` | chaîne | `"easy_npc:humanoid_slim"` | substituée comme `{type}`. **Ne sert pas** à trouver le NPC |
| `pos` | position | overworld `0.5 / 65 / 0.5` | où se tient le héraut. **Un bouchon** |
| `showCommand` | chaîne | `""` | commande console facultative, lancée en plus de la révélation Java |
| `hideCommand` | chaîne | `""` | idem, pour le masquage |
| `despawnAfterSeconds` | entier | `600` | **inutilisée** |

La révélation elle-même est en Java, pas en commande : le hook cherche les entités dont
l'identifiant de registre commence par `easy_npc` dans une boîte de 10 blocs autour de `pos`
et bascule leur attribut d'affichage `VISIBLE`. Les modèles de commande existent pour tout
ce qu'il faudrait faire en plus — `{world} {type} {x} {y} {z} {npc}` y sont substitués, et le
résultat s'exécute comme une source console de niveau 4.

`despawnAfterSeconds` suggère que le héraut devrait se masquer tout seul si le raid ne
démarre jamais. Aucun code ne lit cette clé. Le héraut est masqué à `ANNOUNCE`, de nouveau
au début de `NPC_REVEAL` (le comptage fait avant le crescendo le masque aussi), et au
nettoyage — nulle part ailleurs.

## `arena`

La dimension de faille et le parkour en étoile procédural.

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `riftDimId` | chaîne | `"hoopa_rift"` | l'identifiant de dimension CheeseGuard ; l'identifiant de monde est `cheeseguard:` + ceci |
| `center` | position | `cheeseguard:hoopa_rift` `0.5 / 100 / 0.5` | centre du disque du boss. Seuls `x`/`y`/`z` sont lus : `world` est ignoré, la faille est toujours `cheeseguard:` + `riftDimId` |
| `starRadius` | double | `75.0` | distance du centre à la plateforme de départ d'une branche. Plus long = plus de sauts |
| `maxSlots` | entier | `12` | branches simultanées, donc nombre maximal de joueurs dans un raid |
| `checkpointEvery` | entier | `6` | un checkpoint toutes les N plateformes. Plus petit = plus facile. Ramené à 1 au minimum |
| `revealBlocksPerTick` | entier | `8` | vitesse d'animation du chemin. Ramené à 1 au minimum |
| `revealDelayTicks` | entier | `60` | ticks de montée en puissance sur la plateforme de départ avant que le chemin commence à apparaître |
| `revealParticle` | chaîne | `"ENCHANT"` | particule de la révélation ; un des noms de `Mc.particle`, tout autre retombe sur `ENCHANT` |
| `revealSound` | chaîne | `"minecraft:block.amethyst_block.chime"` | son joué quand le nombre de blocs posés en fin de tick est un multiple de 6 — tous les 24 blocs (3 ticks) à la vitesse par défaut de 8 |
| `platformBlock` | chaîne | `"minecraft:purpur_block"` | les dalles. Un identifiant inconnu mais bien formé donne de l'**air** (la valeur par défaut du registre des blocs), et les dalles disparaissent ; seul un identifiant mal formé retombe sur le purpur |
| `checkpointBlock` | chaîne | `"minecraft:sea_lantern"` | dalles de checkpoint |
| `centerBlock` | chaîne | `"minecraft:crying_obsidian"` | bloc de base du disque du boss |
| `centerRadius` | entier | `15` | rayon du disque du boss. Ramené à 6 au minimum |
| `fallBelow` | double | `12.0` | blocs sous `center.y` avant qu'un joueur réapparaisse à son dernier checkpoint |
| `openTimeoutSeconds` | entier | `180` | durée pendant laquelle la faille reste ouverte. Ramené à 5 au minimum |
| `keepInventory` | booléen | `true` | transmis à la configuration CheeseGuard de la dimension, à sa création |
| `model` | chaîne | `"SHARED"` | **inutilisée** |
| `parkourLength` | entier | `24` | **inutilisée** — le nombre de plateformes découle de la géométrie |
| `gapMin` / `gapMax` | double | `2.8` / `3.6` | **inutilisées** — les distances de saut viennent de la table de styles en dur |
| `riseMax` | double | `1.0` | **inutilisée** |

`keepInventory` ne prend effet qu'à la **création** de la dimension. Le générateur sort
immédiatement si le monde existe déjà : changer cette clé après le premier raid ne fait donc
rien — change-la dans CheeseGuard.

`centerRadius` est structurant à deux endroits inattendus. Il définit où l'arrivée au centre
est détectée (être à moins de `centerRadius - 0.5` horizontalement et à moins de 1,5 bloc
verticalement de `center.y + 1`), et il fixe le volume que le nettoyage vide : un cube de
rayon `centerRadius + 17` et de `9 + max(5, centerRadius/3) + 13` blocs de haut. Un grand
rayon rend le nettoyage coûteux.

`fallBelow` est relatif au Y du centre, pas à la plateforme du joueur. Une branche descend
sous le centre dans la zone *plunge gorge* : une valeur nettement inférieure au défaut
téléportera des joueurs qui sont encore sur le chemin.

La géométrie du parkour, elle, n'est pas configurable. Treize styles de saut répartis sur
cinq mécaniques (plat, glace, funambule, toile, miel) et cinq zones thématiques sont codés en
dur dans `StarArenaGenerator` ; `starRadius`, `maxSlots` et `checkpointEvery` sont les seuls
leviers que la configuration offre. [Le modèle de raid](raid-model.fr.md#larène-en-étoile)
explique la disposition.

## `boss`

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `species` | chaîne | `"hoopa"` | espèce Cobblemon |
| `aspects` | chaîne | `"unbound"` | liste à virgules, émise chacune comme `aspect=<nom>` |
| `baseScale` | double | `1.5` | multiplicateur de taille, appliqué avant le paquet d'apparition, et utilisé pour dimensionner la poche d'air dégagée au-dessus du disque |
| `baseLevel` | entier | `80` | niveau pour un seul participant |
| `levelPerExtraPlayer` | entier | `3` | ajouté par participant au-delà du premier |
| `graceSeconds` | double | `30` | après l'arrivée du premier joueur au centre, le temps laissé aux autres |
| `combatTimeoutSeconds` | entier | `900` | abandonne et nettoie sans récompense. Ramené à 10 au minimum |
| `provider` | chaîne | `"CUSTOM_GIANT"` | **inutilisée** — `BossProvider.forDefinition` renvoie toujours le fournisseur géant |
| `spawnCommand` | chaîne | un modèle `pokespawn` | **inutilisée** |
| `scaleCommand` | chaîne | `""` | **inutilisée** |
| `entityType` | chaîne | `"cobblemon:pokemon"` | **inutilisée** |
| `detectRadius` | double | `8.0` | **inutilisée** |
| `captureWindowMs` | entier | `3000` | **inutilisée** |

Le niveau final vaut `baseLevel + (participants - 1) × levelPerExtraPlayer`, calculé une
seule fois à l'apparition du boss. Le nombre de participants est celui des joueurs entrés,
pas de ceux encore vivants ni encore présents dans la faille.

`provider` nomme une interface de stratégie qui n'a qu'une seule implémentation. La valeur
`RAID_DEN` citée dans le commentaire du champ renvoie à un fournisseur adossé à
CobblemonRaidDens qui n'existe pas ; quoi que tu écrives ici, tu obtiens `CUSTOM_GIANT`.

`baseScale` passe par le `setScaleModifier` de Cobblemon, trouvé par réflexion. Si la
version de Cobblemon installée ne l'a pas, le boss apparaît à taille normale et un
avertissement est journalisé — le combat fonctionne quand même.

## `reward`

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `portalDivisor` | entier | `3` | `floor(participants / divisor)` portails. Ramené à 1 au minimum |
| `minPortals` | entier | `1` | plancher du calcul ci-dessus |
| `portalAnchor` | position | overworld `0.5 / 65 / 0.5` | centre du cercle de portails. **Un bouchon** |
| `portalSpacing` | double | `4.0` | rayon de ce cercle |
| `portalCommand` | chaîne | `""` | modèle de commande de secours, exécuté une fois par portail si CobbleSafari n'en a ouvert aucun |
| `fromage` | long | `0` | monnaie ajoutée au portefeuille CheeseStore de chaque gagnant. `0` l'ignore |
| `commands` | liste de chaînes | vide | commandes console exécutées une fois par gagnant, `{player}` substitué |

Les portails viennent de CobbleSafari, par réflexion sur son `PortalSpawnManager` privé.
L'appel exige un **participant connecté** comme propriétaire : si tout le monde s'est
déconnecté avant la récompense, aucun portail ne s'ouvre et le secours par commande prend le
relais. Sans l'un ni l'autre, tu obtiens un avertissement et aucun portail, mais le raid se
termine proprement.

Les marqueurs de `portalCommand` sont `{dim} {x} {y} {z} {index}`. À noter : le secours
dispose les portails en ligne (`x + index × spacing`), pas sur le cercle que le chemin
principal utilise.

`commands` est le crochet de récompense généraliste. Une entrée par commande, `{player}`
remplacé par le nom du gagnant, chacune exécutée comme une source console de niveau 4 :

```json
"commands": [
  "give {player} minecraft:diamond 3",
  "cheeseitems give {player} hoopa_token 1"
]
```

---

## Un exemple complet annoté

Le JSON n'accepte pas de commentaires : les annotations ci-dessous sont du texte, pas quelque
chose à coller. Ce qui suit est le `hoopa.json` livré, avec les quatre positions bouchons renseignées.

```json
{
  "id": "hoopa",
  "displayName": "Invasion Hoopa",
  "icon": "👻",
  "enabled": true,

  "schedule": {
    "autoTrigger": true,
    "hour": 21,
    "minute": 30,
    "everyNDays": 2
  },

  "announce": {
    "title": "Invasion Hoopa",
    "subtitle": "Une faille dimensionnelle s'ouvre...",
    "chat": "Une invasion Hoopa commence ! Rejoignez l'arene pour repousser l'attaque.",
    "discord": true,
    "discordMessage": "👻 Une **Invasion Hoopa** vient de commencer !"
  },

  "wave": {
    "arena": { "world": "minecraft:overworld", "x": 112.0, "y": 71.0, "z": -2043.0, "yaw": 0.0, "pitch": 0.0 },
    "spread": 8.0,
    "spawns": [
      { "species": "hoopa",   "aspects": "unbound", "level": 80, "count": 1, "leader": true  },
      { "species": "sableye", "aspects": "",        "level": 60, "count": 6, "leader": false }
    ],
    "graceSecondsBeforeWave": 10,
    "overworldStorm": true,
    "checkIntervalTicks": 20,
    "timeoutSeconds": 240
  },

  "npc": {
    "easyNpcName": "hooh-raid",
    "entityType": "easy_npc:humanoid_slim",
    "pos": { "world": "minecraft:overworld", "x": 118.0, "y": 71.0, "z": -2050.0, "yaw": 180.0, "pitch": 0.0 },
    "showCommand": "",
    "hideCommand": ""
  },

  "arena": {
    "riftDimId": "hoopa_rift",
    "center": { "world": "cheeseguard:hoopa_rift", "x": 0.5, "y": 100.0, "z": 0.5, "yaw": 0.0, "pitch": 0.0 },
    "starRadius": 75.0,
    "maxSlots": 12,
    "checkpointEvery": 5,
    "revealBlocksPerTick": 8,
    "revealDelayTicks": 60,
    "revealParticle": "ENCHANT",
    "revealSound": "minecraft:block.amethyst_block.chime",
    "platformBlock": "minecraft:purpur_block",
    "checkpointBlock": "minecraft:sea_lantern",
    "centerBlock": "minecraft:crying_obsidian",
    "centerRadius": 15,
    "fallBelow": 12.0,
    "openTimeoutSeconds": 240,
    "keepInventory": true
  },

  "boss": {
    "species": "hoopa",
    "aspects": "unbound",
    "baseScale": 1.5,
    "baseLevel": 80,
    "levelPerExtraPlayer": 3,
    "graceSeconds": 45.0,
    "combatTimeoutSeconds": 900
  },

  "reward": {
    "portalDivisor": 3,
    "minPortals": 1,
    "portalAnchor": { "world": "minecraft:overworld", "x": 100.0, "y": 71.0, "z": -2030.0, "yaw": 0.0, "pitch": 0.0 },
    "portalSpacing": 4.0,
    "portalCommand": "",
    "fromage": 500,
    "commands": [
      "give {player} minecraft:diamond 3"
    ]
  },

  "exitPos": { "world": "minecraft:overworld", "x": 120.5, "y": 71.0, "z": -2060.5, "yaw": 90.0, "pitch": 0.0 }
}
```

Les choix de ce fichier valent une explication. `schedule` est actif, à 21h30 tous les deux
jours — un serveur qui redémarre à 21h30 ratera le déclenchement plutôt que de le rattraper.
`spread` vaut 8 au lieu de 6 et le nombre de sbires 6 au lieu de 4, ce qui demande une arène
dégagée de 17×17. `checkpointEvery` vaut 5 plutôt que 6, ce qui rend le parkour un peu plus
indulgent. `graceSeconds` vaut 45 plutôt que 30, parce que le parkour est long et que les
retardataires sont la norme. `fromage` est non nul, ce qui exige CheeseStore — sans lui, le
versement est une opération silencieusement sans effet.

Les clés inutilisées sont volontairement absentes de l'exemple. Le mod les écrit au premier
démarrage, et les laisser dans le fichier est sans conséquence.

---

← [Commandes](commands.fr.md) · [Calibration](calibration.fr.md) →
