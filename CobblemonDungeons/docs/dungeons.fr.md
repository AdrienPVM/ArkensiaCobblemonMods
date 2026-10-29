[← CobblemonDungeons](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](dungeons.md)

# La définition d'un donjon

Un donjon est un fichier JSON dans `config/cobblemon_dungeons/dungeons/`. Le nom du fichier
n'importe pas pour l'identification — c'est le champ `general.id` à l'intérieur qui compte —
mais le dashboard réécrit un donjon dans `<id>.json` (et `DELETE` ne supprime que
`<id>.json`), donc garder le fichier nommé d'après son identifiant évite les fichiers en
double.

Chaque champ ci-dessous est un champ de `DungeonConfig` (ou de l'une de ses classes
imbriquées) dans
`src/main/java/com/sebastian/cobblemondungeons/config/DungeonConfig.java`. La valeur par
défaut indiquée est celle que porte un `DungeonConfig` fraîchement construit, c'est-à-dire
aussi ce que tu obtiens pour toute clé omise du fichier : Gson remplit les clés absentes
avec l'initialiseur du champ, donc un fichier partiel est valide et le reste prend ses
valeurs par défaut.

---

## Niveau racine

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `general` | objet | voir plus bas | Identité et présentation dans l'interface |
| `networking` | objet | voir plus bas | Quel serveur fait tourner le donjon, et comment les joueurs y vont |
| `locations` | objet | voir plus bas | Le premier emplacement de localisation |
| `additionalLocations` | tableau d'objets de localisation | `[]` | Emplacements supplémentaires, pour faire tourner le donjon plusieurs fois en parallèle |
| `requirements` | objet | voir plus bas | Conditions d'entrée, objectifs, limite de temps, vies, cooldown |
| `scaling` | objet | voir plus bas | Tier par défaut et table tier → niveau |
| `spawning` | objet | voir plus bas | Placement des Alphas, Gardiens, Pieux, portails et coffres |
| `boss` | objet | voir plus bas | Pool de boss et position d'apparition du boss |
| `rewardCap` | int | `0` | Nombre maximum de récompenses de complétion qu'un joueur peut recevoir. `0` signifie sans limite |
| `lootPools` | tableau | `[]` | Contenu des coffres, indexé par identifiant de coffre et par tier |
| `completionRewards` | tableau | `[]` | Items tirés pour chaque joueur à la mort du boss |

`rewardCap` ne compte que les récompenses effectivement remises. Un tirage dont
l'identifiant d'item est inconnu (et ne correspond à aucun nom de ticket) produit une pile
vide : il est journalisé sous `Unknown loot item ID or ticket name: <item>` et ne consomme
pas d'emplacement, au lieu de grignoter silencieusement le quota de quelqu'un.

## `general`

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `name` | string | `"Generic Dungeon"` | Nom affiché, utilisé dans les messages de chat, le HUD et l'interface de sélection |
| `id` | string | `"generic_dungeon"` | Clé de registre. Les tickets, le dashboard et `DUNGEON_OPEN` la référencent tous |
| `guiIcon` | string | `"minecraft:paper"` | Identifiant d'item dessiné pour ce donjon dans l'interface de sélection |

`id` est la seule identité qui compte. Deux fichiers déclarant le même `id` aboutissent à ce
que le dernier chargé gagne, parce que `DungeonManager.registerDungeon` écrit dans une table
indexée par identifiant et que l'ordre de `File.listFiles` n'est pas spécifié.

## `networking`

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `targetServer` | string | `"dungeonsraids"` | Nom du serveur proxy vers lequel le joueur est envoyé, en mode `CROSS_SERVER` |
| `mainServer` | string | `"main"` | Nom du serveur proxy vers lequel il revient à la sortie |
| `teleportMode` | string | `"CROSS_SERVER"` | `"SAME_SERVER"` ou `"CROSS_SERVER"` (comparé sans tenir compte de la casse) |
| `syncPlayerData` | boolean | `true` | Si l'inventaire, l'équipe et le PC sont sérialisés et transportés |

Les deux modes sont des chemins de code réellement différents, et le mode décide de quelle
moitié du mod tu te sers :

- **`SAME_SERVER`** — tout se passe sur ce serveur. Entrer signifie un compte à rebours de
  5 secondes, puis une téléportation vers `hubPos`, puis une téléportation vers `spawnPos`
  une fois le monde préparé. Aucun proxy, aucune sérialisation de données joueur, aucun
  `targetServer`.
- **`CROSS_SERVER`** — la valeur par défaut. Le client du chef est d'abord interrogé sur le
  nombre de joueurs de `targetServer` via le canal de plugin BungeeCord ; si la réponse
  n'arrive pas en 60 ticks, ou si le serveur se déclare hors ligne, la tentative est
  abandonnée et le chef est averti *Le serveur de donjon est hors ligne. Veuillez réessayer
  plus tard.* Alors seulement le compte à rebours démarre. À son terme, chaque membre reçoit un
  `DungeonJoinPayload` portant ses données sérialisées et est transféré vers `targetServer`
  40 ticks plus tard.

Un donjon créé depuis le dashboard est forcé en `SAME_SERVER`, parce que le dashboard n'a
aucun moyen de connaître la topologie de ton proxy.

⚠️ Le mode `CROSS_SERVER` exige le plugin Velocity de `velocity-party-forwarder/` sur le
proxy, et le mod sur le serveur principal comme sur le serveur de donjon avec
`cobblemon.dungeons.serverRole` réglé en conséquence. Voir
[le cycle de vie des instances](instances.fr.md) et [l'installation](installation.fr.md).

## `locations` et `additionalLocations`

Chaque objet de localisation contient cinq positions. Une position s'écrit
`{x, y, z, dimension}`, `dimension` étant un identifiant de dimension complet.

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `spawnPos` | position | `0, 100, 0` dans `minecraft:overworld` | Où les joueurs atterrissent une fois le donjon préparé ; aussi le centre de chaque scan de rayon |
| `hubPos` | position | idem | Zone d'attente que les joueurs voient pendant la préparation |
| `bossRoomPos` | position | idem | Où le portail envoie le groupe, et où réapparaît un joueur KO une fois la salle du boss déverrouillée |
| `returnPos` | position | idem | Où les joueurs `SAME_SERVER` sont téléportés à la sortie |
| `bossSpawnPos` | position | `null` | Remplace `boss.bossSpawnPos` pour cet emplacement |

`additionalLocations` est le moyen pour une seule définition de donjon de servir plusieurs
groupes simultanés : `getAllLocations()` renvoie `locations` suivi de
`additionalLocations`, et en mode `SAME_SERVER` `findAvailableSlot` distribue le premier
index sur lequel aucune instance vivante n'est assise. Sans emplacement supplémentaire il y
en a exactement un, et un deuxième groupe reçoit *Les donjons sont pleins ! Veuillez
réessayer plus tard.*

En mode `CROSS_SERVER`, le serveur principal n'alloue rien (son compte à rebours porte un
`locationIndex` à `-1`) ; l'emplacement est alloué sur le serveur de donjon, par
`findAvailableSlot` dans `handleCrossServerJoin`, à l'arrivée du premier membre d'un groupe.
Un groupe qui trouve tous les emplacements occupés reçoit *Les donjons sont pleins !* et est
renvoyé vers `mainServer`.

⚠️ Trois téléportations ignorent l'emplacement alloué et utilisent toujours le **premier**
bloc `locations` : le passage du hub à `spawnPos` une fois la préparation terminée, la
téléportation de l'invite de boss vers `bossRoomPos`, et celle qui suit une équipe KO. Les
scans, l'apparition du boss, la reconnexion et la réapparition utilisent bien l'emplacement
actif. Avec `additionalLocations`, un groupe sur l'emplacement 2 est donc préparé autour de
son propre `spawnPos` mais livré à celui de l'emplacement 1.

## `requirements`

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `minLevel` | int | `1` | Déclaré mais lu par aucun chemin de code |
| `dungeonsPartySizeLimit` | int | `5` | Plafond strict de la taille du groupe à l'entrée |
| `timeLimitSeconds` | int | `1800` | Chronomètre de toute la partie, en secondes |
| `requiredKeyItems` | tableau de strings | `[]` | Noms de clés à toutes collecter avant que le boss ne se déverrouille |
| `requiredAlphas` | int | `0` | Pokémon Alpha à vaincre |
| `requiredGems` | int | `0` | Gemmes à trouver dans les coffres |
| `requiredGuardians` | int | `0` | Dresseurs Gardiens à battre |
| `requiredStakes` | int | `0` | Pieux à trouver |
| `soloLives` | int | `3` | Vies partagées pour un groupe de 1 |
| `smallPartyLives` | int | `6` | Vies partagées pour un groupe de 2 ou 3 |
| `largePartyLives` | int | `10` | Vies partagées pour un groupe de 4 ou plus |
| `cooldownSeconds` | int | `0` | Cooldown par joueur après une réussite. `0` le désactive |

`minLevel` existe dans la classe de données et dans aucune logique — aucun gestionnaire ne
le lit. Considère-le comme une note pour toi-même, pas comme une barrière appliquée.

Les quatre compteurs `required*` sont à la fois des objectifs et des cibles d'apparition.
L'instance règle `alphaSpawnTarget`, `guardianSpawnTarget` et `stakeSpawnTarget` dessus à sa
construction, donc `requiredStakes: 4` signifie « fais apparaître quatre Pieux et exiges-en
quatre ». Mettre un compteur à `0` marque cet objectif comme accompli avant même le début :
le constructeur met `stakesSpawned` et `areGuardiansSpawned` à `true` quand leur exigence
est nulle, ce qui est aussi ce qui rend immédiatement prêt pour les joueurs un donjon sans
Gardien.

Les vies sont partagées par le groupe, pas individuelles. Une mort ou une équipe
entièrement KO coûte une vie ; à zéro, la partie échoue pour tout le monde. Les seuils sont
évalués contre la taille de groupe capturée à la construction de l'instance, donc un membre
qui se déconnecte avant la fin du compte à rebours ne donne pas plus de vies aux autres.

`timeLimitSeconds` est décrémenté une fois par `tickDungeons`, qui tourne toutes les 20
ticks serveur — une vraie seconde sur un serveur en bonne santé, davantage sur un serveur
qui lague. Le chronomètre n'est « réel » qu'au sens où il suit la cadence des ticks.

`cooldownSeconds` est enregistré **en cas de succès uniquement**, pour chaque membre du
groupe, indexé par identifiant de donjon et UUID de joueur dans
`config/cobblemon_dungeons/cooldowns.json`. Une partie échouée ne coûte rien. La
vérification à l'entrée rejette tout le groupe si un seul membre est encore en cooldown, et
le nomme : *<joueur> est encore en cooldown pour ce donjon (Xm Ys restantes)*.

## `scaling`

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `tier` | int | `1` | Tier utilisé quand une partie démarre sans tier explicite |
| `tierLevels` | tableau de `{tier, level}` | `[]` | Niveau auquel chaque tier cale le contenu |

Le tier est le seul bouton que tournent les joueurs. L'interface propose les Tiers 1 à 5 sous
forme de cinq boutons, et `startDungeon(player, id)` sans tier retombe sur `scaling.tier`.
Le tier sélectionne ensuite les pools de butin, les entrées de boss, les entrées d'Alpha et
les entrées de Gardien qui s'appliquent — chacune portant son propre champ `tier`, où `0`
signifie « n'importe quel tier ».

`tierLevels` associe un tier au niveau auquel le contenu apparaît. Un tier sans entrée dans
la table retombe sur une table intégrée dans `resolveTierLevel` : tier 1 → 15, 2 → 30,
3 → 60, 4 → 85, 5 → 115 ; tout autre tier reçoit `0`, c'est-à-dire aucune surcharge. Les
tiers 1 à 5 ont donc toujours un niveau, même avec `tierLevels: []`.

## `spawning`

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `alphaPool` | tableau | `[]` | Pokémon Alpha candidats, pondérés |
| `alphaSpawnRadius` | int | `150` | Rayon de recherche des blocs spawner d'Alpha autour de `spawnPos` |
| `alphaTriggerRadius` | double | `5.0` | Distance à laquelle un joueur déclenche un spawner d'Alpha |
| `guardianSpawnRadius` | int | `150` | Rayon de recherche des blocs spawner de Gardien |
| `stakeSpawnRadius` | int | `150` | Rayon de recherche des blocs spawner de Pieu |
| `portalSpawnRadius` | int | `150` | Rayon de recherche des blocs portail |
| `chestSpawnRadius` | int | `150` | Rayon de recherche des coffres de donjon |
| `alphaSpawnerIds` | tableau d'int | `[]` | Variantes numérotées de spawner d'Alpha utilisées par ce donjon |
| `guardianSpawnerIds` | tableau d'int | `[]` | Variantes numérotées de spawner de Gardien |
| `stakeSpawnerIds` | tableau d'int | `[]` | Variantes numérotées de spawner de Pieu |
| `portalIds` | tableau d'int | `[]` | Variantes numérotées de portail |
| `chestIds` | tableau d'int | `[]` | Variantes numérotées de coffre — aussi l'identifiant du pool de butin |
| `spawnerScanVerticalRange` | int | `64` | Demi-portée verticale du scan de blocs |
| `guardianPool` | tableau | `[]` | Dresseurs Gardiens candidats, pondérés |
| `guardianTrainerIds` | tableau de strings | `[]` | **Noms** de dresseurs supplémentaires qui comptent comme Gardien quand un joueur les bat. Rien n'est invoqué depuis cette liste |
| `spawnProtectionRadius` | double | `100.0` | Déclaré mais lu par aucun chemin de code |

Les listes d'identifiants numérotés sont ce qui permet à deux donjons de partager un monde
sans se voler leur mobilier. Le mod enregistre 20 variantes de bloc spawner d'Alpha
(`alpha_spawner`, `alpha_spawner_2` … `alpha_spawner_20`) et 10 de chacun des spawners de
Gardien, de Pieu, des portails et des coffres de donjon. Un donjon ne réagit qu'aux
variantes listées dans ses propres tableaux `*Ids`, donc `alphaSpawnerIds: [1]` rend un
donjon aveugle à `alpha_spawner_2`. Un tableau vide — la valeur par défaut — signifie aucun
filtre : toutes les variantes sont utilisées.

⚠️ Deux familles de variantes sont découvertes mais jamais activées.
`checkPortalActivation` n'allume que les blocs qui sont le bloc `portal` de base, donc
`portal_2` … `portal_10` ne deviennent jamais visibles et ne peuvent pas servir ; et
`spawnStakes` ne pose un Pieu que sur le `stake_spawner` de base, donc `stake_spawner_2` …
`stake_spawner_10` n'en portent jamais — un donjon qui compte sur eux ne peut jamais
atteindre `requiredStakes`. Utilise la variante 1 des deux (`portal`, `stake_spawner`).

`chestIds` joue un double rôle : le numéro de variante du coffre est aussi l'`id` que doit
porter un pool de butin pour être utilisé par ce coffre.

Les positions ne sont pas stockées dans la configuration. Elles sont découvertes : les poses
de blocs entrent dans un registre par monde au chargement de chunk, et chaque instance
vivante scanne les chunks de son rayon sur un budget de 32 scans de chunk par tick, toutes
instances confondues. De grands rayons coûtent donc du temps de préparation plutôt que du
TPS.

### Entrées de `alphaPool`

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `species` | string | `"ghastly"` | Nom d'espèce Cobblemon |
| `tier` | int | `0` | Tier auquel appartient cette entrée. `0` signifie n'importe quel tier |
| `minLevel` | int | `10` | Borne inférieure du niveau tiré — seulement quand le niveau du tier vaut `0` |
| `maxLevel` | int | `20` | Borne supérieure — même condition |
| `weight` | int | `10` | Poids relatif de sélection |

Dès que le tier de la partie se résout en un niveau supérieur à zéro — toujours le cas pour
les tiers 1 à 5, voir [`scaling`](#scaling) — l'Alpha apparaît exactement à ce niveau et
`minLevel` / `maxLevel` sont ignorés.

La valeur par défaut livrée, `"ghastly"`, est une faute d'orthographe de Gastly jamais
corrigée dans la classe de données. Une espèce inconnue est journalisée une fois puis
ignorée, donc un pool uniquement composé de fautes produit un donjon sans Alpha et un
objectif que personne ne peut remplir.

### Entrées de `guardianPool`

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `trainerId` | string | `"generic_trainer"` | Identifiant d'un dresseur enregistré auprès de RCTApi |
| `tier` | int | `0` | Tier auquel appartient cette entrée. `0` signifie n'importe quel tier |
| `weight` | int | `10` | Poids relatif de sélection |
| `level` | int | `0` | Remplace le niveau du tier quand il est supérieur à zéro |

Voir [les dresseurs et RCTApi](trainers.fr.md) pour l'origine de `trainerId`.

## `boss`

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `pool` | tableau d'entrées de boss | `[]` | Candidats pondérés |
| `bossSpawnPos` | position | `10, 100, 10` dans `minecraft:overworld` | Où le boss apparaît, sauf surcharge par l'emplacement actif |
| `species` | string | `"pikachu"` | Champ historique de boss unique |
| `level` | int | `50` | Historique |
| `customName` | string | `"Dungeon Boss"` | Historique |
| `isShiny` | boolean | `false` | Historique |

Les quatre champs historiques sont l'ancien format d'avant les pools et ils sont toujours
honorés : si `pool` est vide, ou si aucune entrée ne correspond au tier de la partie et
qu'aucune n'a `tier: 0`, le mod fabrique un boss ponctuel depuis `species`, `level`,
`customName` et `isShiny`. Utilise `pool` pour tout contenu nouveau.

### Entrées du pool de boss

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `species` | string | `"pikachu"` | Nom d'espèce Cobblemon |
| `tier` | int | `0` | Tier auquel appartient cette entrée. `0` signifie n'importe quel tier |
| `level` | int | `50` | Niveau du boss. `0` ou moins utilise le niveau du tier |
| `customName` | string | `"Dungeon Boss"` | Nom affiché au-dessus de l'entité |
| `isShiny` | boolean | `false` | Le faire apparaître shiny |
| `weight` | int | `10` | Poids relatif de sélection parmi les entrées du tier correspondant |
| `rewardChance` | double | `100.0` | Pourcentage de chance que chaque membre reçoive le boss lui-même dans son PC |
| `scale` | double | `4.0` | Échelle visuelle, appliquée par l'aspect `scale:` et le NBT persistant `CustomScale` |
| `customRewards` | tableau de récompenses de complétion | `[]` | Récompenses supplémentaires ajoutées au pool pour ce boss seulement |

La sélection privilégie les entrées dont le `tier` égale celui de la partie ; ce n'est que si
cette liste filtrée est vide qu'elle retombe sur les entrées de `tier: 0`. Un nom d'espèce
que Cobblemon ne connaît pas donne une erreur dans le log et aucun boss, ce qui rend la
partie impossible à terminer.

## `lootPools`

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `id` | int | `1` | Variante de coffre que ce pool remplit |
| `tier` | int | `0` | Tier que ce pool sert. `0` est le repli |
| `rolls` | int | `1` | Nombre de tirages pondérés par coffre |
| `items` | tableau d'items de butin | `[]` | Candidats |

La résolution du pool d'un coffre est : correspondance exacte sur `id` **et** sur le tier de
la partie d'abord, puis correspondance sur `id` avec `tier: 0`. Aucune correspondance donne
un coffre vide.

Chaque tirage choisit un item par poids dans toute la liste `items`, indépendamment — le même
item peut sortir deux fois dans un coffre. Si la somme des poids est nulle, rien n'est
généré.

### Entrées d'items de butin

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `item` | string | `"minecraft:diamond"` | Identifiant d'item, ou l'`itemName` exact d'un ticket de donjon configuré |
| `customName` | string | `""` | Remplace le nom affiché |
| `min` | int | `1` | Borne inférieure de la taille de la pile |
| `max` | int | `1` | Borne supérieure |
| `weight` | int | `10` | Poids relatif |
| `nbt` | string | `""` | Chaîne SNBT, analysée et stockée comme composant `custom_data` de la pile |

Placer un ticket dans un coffre se fait en le nommant, pas par identifiant d'item : le
fichier d'exemple généré utilise `"§6Copper Dungeon Ticket"` comme valeur d'`item`, ce qui
retrouve un ticket par son `itemName` configuré (sans tenir compte de la casse, et `&` peut
remplacer `§`). Voir [les tickets](tickets.fr.md).

## `completionRewards`

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `item` | string | `"minecraft:diamond"` | Identifiant d'item |
| `customName` | string | `""` | Remplace le nom affiché |
| `min` | int | `1` | Borne inférieure de la taille de la pile |
| `max` | int | `1` | Borne supérieure |
| `chance` | double | `100.0` | Pourcentage de chance que cette récompense soit tirée |
| `tier` | int | `0` | Tier que cette récompense sert. `0` signifie n'importe quel tier |
| `nbt` | string | `""` | Chaîne SNBT, stockée comme composant `custom_data` de la pile |

Les récompenses de complétion sont tirées par joueur à la mort du boss. Les récompenses
éligibles sont celles dont le `tier` vaut `0` ou égale celui de la partie, plus les
`customRewards` de l'entrée de boss. La liste est mélangée pour chaque joueur avant le
tirage, de sorte que lorsque `rewardCap` mord, les récompenses obtenues sont aléatoires
plutôt que les premières du fichier. Une récompense qui ne tient pas dans l'inventaire est
lâchée aux pieds du joueur.

## Hors du fichier : dangers et entités nommées

Deux éléments d'un donjon se construisent dans le monde au lieu d'être déclarés dans le JSON.

### Dangers

Les quatre items sont dans l'onglet créatif **Cobblemon Dungeons**. Les dangers fonctionnent
partout dans le monde, en partie ou non.

| Item | Effet |
|---|---|
| `poison_water_bucket` | Pose de l'eau empoisonnée. S'y tenir (pieds ou yeux) donne Poison II pendant 5 secondes, renouvelé tant qu'on y reste |
| `poison_gas` | Un bloc sans collision. S'y tenir donne Poison I pendant 3 secondes |
| `gas_mask` | Casque. Porté, ou tenu dans l'une ou l'autre main, il immunise son porteur et retire le Poison |
| `gas_detector` | Tenu dans l'une ou l'autre main, affiche *⚠ Gaz empoisonné détecté à proximité ! ⚠* dans la barre d'action, une fois par seconde, quand un bloc de gaz se trouve à 5 blocs ou moins à l'horizontale (2 en dessous, 5 au-dessus) |

Un Pokémon de type Poison, ou qui tient un masque à gaz comme objet Cobblemon, est immunisé
lui aussi.

### Entités nommées

`InteractionHandler` réagit au clic droit sur toute entité dont le nom personnalisé contient
l'un de ces mots, sans tenir compte de la casse, testés dans cet ordre :

| Le nom contient | Effet |
|---|---|
| `dungeon master` | Ouvre l'interface de sélection des donjons, pour tout le monde |
| `key` | Pour un joueur en partie : collecte une clé nommée exactement comme l'entité — la chaîne que doit lister `requirements.requiredKeyItems` — et retire l'entité |
| `gem` | Pour un joueur en partie : compte une Gemme et retire l'entité |
| `stake` | Pour un joueur en partie : compte un Pieu et retire l'entité |
| `boss altar` | Pour un joueur en partie : invoque le boss si tous les objectifs sont remplis, sinon *Vous devez compléter tous les objectifs pour invoquer le boss !* ; ensuite *Le boss est déjà apparu !* / *Le boss a déjà été vaincu !* |

Le premier mot qui correspond l'emporte : une entité nommée « Boss Altar Key » est une clé.
Des porte-armures nommés sont le support habituel.

---

← [Configuration](configuration.fr.md) · [Les instances et leur cycle de vie](instances.fr.md) →
