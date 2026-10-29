[← CheeseItems](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](items.md)

# Items personnalisés

Un item personnalisé n'est pas un item enregistré. C'est un `ItemStack` vanilla dont le
composant `CUSTOM_DATA` porte une seule chaîne : `cheeseitems: "<id>"`. Tout le reste — le
nom, le lore, la couleur, les effets, les commandes, les conditions — est lu depuis
`config/cheeseitems.json` au moment où l'item est utilisé, ou réappliqué à la pile quand la
définition change.

Ce choix de conception décide de l'essentiel du comportement du mod. Le registre des items
n'est jamais touché, donc les définitions peuvent apparaître et disparaître pendant que le
serveur tourne. Une pile retrouvée dans un coffre dans six mois se comportera selon ce que
dit la définition *à ce moment-là*, pas selon ce qu'elle disait quand la pile a été créée.

## L'objet de définition

Une entrée du tableau `items` de `config/cheeseitems.json`. Chaque champ est optionnel : un
champ que le chargeur ne trouve pas garde la valeur par défaut du constructeur, c'est-à-dire
celle du tableau ci-dessous. Il n'y a **aucune validation** au-delà de ces défauts — un
`baseItem` inconnu, un `nameColor` mal formé ou un `maxUses` négatif sont tous acceptés et
produisent le comportement décrit dans la prose.

### Identité

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `id` | chaîne | un fragment d'UUID de 8 caractères | l'identifiant utilisé par `give`, l'API et le tag NBT `cheeseitems` |
| `displayName` | chaîne | `"Custom Item"` | le `CUSTOM_NAME` de la pile, italique retirée |
| `nameColor` | chaîne | `"#FFFFFF"` | couleur hexadécimale appliquée au nom affiché |
| `baseItem` | chaîne | `"minecraft:paper"` | l'item vanilla dont la pile est faite |

`id` est le seul champ qui compte pour une pile déjà dans le monde, parce que c'est le seul
qui est stocké sur la pile. Renommer un identifiant orpheline chaque copie existante. Il
n'y a pas d'endpoint de renommage pour cette raison — crée une nouvelle définition et
redistribue-la.

`nameColor` et `loreColor` passent par `parseHexColor`, qui retire un `#` initial et lit le
reste en hexadécimal. **Tout ce qu'il n'arrive pas à lire devient blanc en silence**
(`0xFFFFFF`). Une écriture courte à trois chiffres comme `#f5a` est lue comme le nombre
`0xF5A`, un bleu foncé, et non comme la couleur voulue.

### <a id="enabled"></a>Apparence et options

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `lore` | tableau de chaînes | `[]` | une ligne d'infobulle par entrée, italique retirée |
| `loreColor` | chaîne | `"#AAAAAA"` | couleur hexadécimale appliquée à chaque ligne de lore |
| `glowing` | booléen | `false` | pose `ENCHANTMENT_GLINT_OVERRIDE` ; `false` le retire |
| `dyeColor` | entier | `-1` | RGB compacté pour `DYED_COLOR` ; `-1` retire le composant |
| `enabled` | booléen | `true` | `false` rend le clic droit totalement inerte |
| `maxStackSize` | entier | `64` | borné entre 1 et 64 avant d'être écrit dans `MAX_STACK_SIZE` |
| `unbreakable` | booléen | `false` | pose le composant `UNBREAKABLE` |
| `consumable` | booléen | `false` | retire un exemplaire de la pile après une utilisation réussie |
| `soulbound` | booléen | `false` | ⚠️ voir [les options qui ne font rien](#les-options-qui-ne-font-rien) |
| `disappearOnDeath` | booléen | `false` | la pile est effacée de l'inventaire avant la mort |
| `droppable` | booléen | `true` | ⚠️ non appliqué — voir plus bas |
| `tradeable` | booléen | `true` | ⚠️ jamais lu par aucun code |
| `placeable` | booléen | `false` | ⚠️ jamais lu par aucun code |

`enabled: false` n'empêche pas `give` de distribuer l'item, et n'empêche pas non plus les
effets passifs d'être ignorés — la passe des effets passifs vérifie aussi l'option, donc un
item désactivé est inerte en main mais reste un item vanilla normal. Il s'empile, il se
jette, il occupe une case. Traite-le comme « retiré du service », pas comme « supprimé ».

`dyeColor` n'apparaît que sur les items qui lisent le composant : armure en cuir, armure en
cuir pour cheval, armure de loup. Sur du papier, il ne change rien.

### Utilisations et cooldowns

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `maxUses` | entier | `0` | `0` signifie illimité. Au-dessus de zéro, la pile est marquée d'un `usesLeft` et la dernière utilisation détruit la pile entière |
| `cooldownTicks` | entier | `0` | cooldown par joueur entre deux utilisations de cette définition, en ticks |

`maxUses` est écrit sur la pile à la construction : le modifier ensuite ne change pas le
compteur d'une pile que quelqu'un possède déjà — même un changement de `baseItem` ne le fait
pas, car la reconstruction recopie l'ancien `usesLeft`. Seule une pile qui n'avait aucun
compteur (construite quand `maxUses` valait `0`) prend la nouvelle valeur, et seulement si un
changement de `baseItem` la reconstruit. Quand le compteur atteint zéro, le gestionnaire
appelle `held.decrement(held.getCount())`, ce qui vide **la pile entière**, pas un seul
exemplaire. Distribue les items à `maxUses` un par un, ou attends-toi à voir une pile de
cinq disparaître à la cinquième utilisation du premier.

Un cooldown encore en cours envoie `Cooldown : 12.4s` dans la barre d'action et
l'utilisation est refusée. La comptabilité des cooldowns vit en mémoire seulement : un
redémarrage les efface tous. Les vieilles entrées sont nettoyées une fois par minute, et
tout ce qui dépasse 72 000 ticks (une heure) est supprimé.

### Effets

Trois tableaux de même forme :

| Clé | Quand il s'applique |
|---|---|
| `onUseEffects` | à un clic droit réussi |
| `passiveEffects` | réappliqué toutes les 40 ticks tant que l'item est dans une des deux mains |
| `onPickupEffects` | ⚠️ jamais appliqué — voir [les options qui ne font rien](#les-options-qui-ne-font-rien) |

Chaque entrée :

| Champ | Type | Défaut | Effet |
|---|---|---|---|
| `effectId` | chaîne | `"minecraft:speed"` | identifiant d'effet ; un identifiant inconnu est ignoré en silence |
| `amplifier` | entier | `0` | `0` correspond au niveau I |
| `durationTicks` | entier | `600` | 20 ticks par seconde |

Les effets sont ajoutés avec `ambient = false`, `showParticles = true`, `showIcon = true`.
Pour un effet passif, choisis une durée plus longue que le rafraîchissement de 40 ticks —
100 ticks est un bon plancher, car une durée inférieure à 40 laisse des trous que le joueur
ressentira.

`GET /api/v1/items/enums` renvoie les 37 identifiants d'effet que l'éditeur du dashboard
propose. Le mod ne te limite pas à cette liste : tout ce qui se trouve dans le registre
`STATUS_EFFECT` fonctionne, y compris les effets ajoutés par d'autres mods.

### <a id="useactions"></a>useActions

La raison d'être du mod. Chaque entrée lance une commande serveur quand l'item est utilisé.

| Champ | Type | Défaut | Effet |
|---|---|---|---|
| `command` | chaîne | `""` | la commande à lancer, sans le slash initial |
| `count` | entier | `1` | combien de fois la lancer ; une valeur inférieure à 1 vaut 1 |
| `feedback` | chaîne | `""` | message dans la barre d'action, en vert. Vide signifie aucun message |
| `delayCommand` | chaîne | `""` | une seconde commande, lancée plus tard |
| `delaySeconds` | entier | `0` | le délai. **Ce champ et `delayCommand` doivent tous deux être remplis, sinon rien n'est planifié** |
| `showTimer` | booléen | `false` | affiche un compte à rebours dans la barre d'action jusqu'au déclenchement |
| `timerLabel` | chaîne | `""` | le libellé de ce compte à rebours ; vide retombe sur `Timer` |

`{player}` est remplacé par le nom de l'utilisateur dans `command`, `delayCommand` et
`feedback`. C'est le seul motif de substitution.

⚠️ **Les commandes sont exécutées en tant que console serveur**, via
`server.getCommandManager().executeWithPrefix(server.getCommandSource(), cmd)`. C'est le
niveau de permission 4. Un clic droit par n'importe quel joueur peut donc lancer `op`,
`ban`, `stop` ou n'importe quoi d'autre — la définition de l'item est la frontière de
sécurité, et les routes du dashboard qui écrivent les définitions n'exigent qu'`OPERATOR`
([pourquoi](dashboard.fr.md#authentification-et-gardes-de-rôle)).

La commande différée est lancée depuis une liste que le mod fait avancer lui-même. Elle est
en mémoire : un redémarrage entre l'utilisation et l'échéance abandonne la suite en
silence. Le compte à rebours est poussé une fois par seconde et s'écrit `3m 20s` au-dessus
d'une minute, `45s` en dessous.

Un exemple concret — un parchemin de téléportation qui prévient, puis déplace le joueur :

```json
{
  "command": "effect give {player} minecraft:blindness 60 1",
  "count": 1,
  "feedback": "Canalisation…",
  "delayCommand": "tp {player} 120 70 -340",
  "delaySeconds": 3,
  "showTimer": true,
  "timerLabel": "Téléportation"
}
```

### useConditions

Vérifiées avant tout le reste. Une vérification qui échoue envoie `Conditions non
remplies.` dans la barre d'action et refuse l'utilisation — le joueur n'apprend pas
*laquelle* a échoué, ce qui est voulu pour un item de quête et pénible pour le débogage.

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `dimension` | chaîne ou absente | absente | l'utilisation ne marche que dans cette dimension, comparée à la clé de registre complète (`minecraft:the_nether`) |
| `minLevel` | entier | `0` | **niveau d'expérience** minimum, pas un niveau de Pokémon. `0` désactive la vérification |
| `requiredHeldItem` | chaîne ou absente | absente | identifiant d'item qui doit se trouver en **main secondaire** |
| `permission` | chaîne ou absente | absente | un nœud de permission, vérifié via LuckPerms |
| `globalCooldownTicks` | entier | `0` | un cooldown partagé par tous les joueurs pour cette définition |
| `zoneId` | chaîne ou absente | absente | ⚠️ stocké, servi, jamais vérifié |

La vérification `permission` fonctionne ainsi : les opérateurs de niveau 2 ou plus passent
immédiatement, sans que le nœud soit consulté. Tous les autres passent par une recherche
LuckPerms par réflexion, et **tout échec de cette réflexion — y compris l'absence de
LuckPerms — est traité comme un refus**. Donc sur un serveur sans LuckPerms, une condition
`permission` rend l'item utilisable par les opérateurs seulement. C'est un défaut
raisonnable, mais ce n'est pas ce que le nom du champ suggère.

`globalCooldownTicks` et `cooldownTicks` sont tous deux consultés, et le message affiche
celui auquel il reste le plus de temps.

### endurance

Une réserve de drain par item, distincte de la réserve d'endurance des morphos décrite dans
[morphos](morphos.fr.md). Omets la clé, ou mets-la à `null`, pour n'avoir aucune réserve.

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `tag` | chaîne | `""` | identifiant de réserve. **Un tag vide désactive tout le bloc** |
| `displayName` | chaîne | `"Endurance"` | conservé dans l'état de la réserve ; actuellement affiché nulle part |
| `capacity` | entier | `100` | valeur pleine de la réserve |
| `drainPerSecond` | flottant | `2.0` | points retirés par seconde pendant l'activité |
| `regenPerSecond` | flottant | `1.0` | points rendus par seconde pendant l'inactivité |
| `onDepletedCommands` | tableau d'actions | `[]` | entrées de la forme `useActions`, lancées quand la réserve se vide ou qu'on la coupe |

Avec une réserve configurée, le clic droit devient une bascule. La première pression démarre
le drain et suit le chemin d'utilisation normal. Une seconde pression pendant le drain
l'arrête, lance `onDepletedCommands`, applique le cooldown et **ignore** complètement
`useActions` et `onUseEffects`. Une pression alors que la réserve est à zéro est refusée avec
`Endurance épuisée !`.

Cette seconde pression passe par le même filtre que la première : les conditions, puis le
cooldown. Avec un `cooldownTicks`, un joueur qui a lancé la réserve ne peut pas l'arrêter
avant la fin du cooldown — il reçoit `Cooldown : …` et le drain continue.

La réserve est dessinée dans la barre d'action quatre fois par seconde, sous forme de dix
caractères `◆`/`◇` colorés en vert au-dessus de 50 %, jaune au-dessus de 25 %, rouge en
dessous — ou sous forme d'un unique glyphe de la police générée si
`config/cheeseitems_assets/bar/0.png` … `10.png` existent tous. Voir
[le resource pack](resource-pack.fr.md#la-police-de-la-barre-dendurance).

Les joueurs qui partagent un `tag` ne partagent pas une réserve : la table est indexée par
tag *et* par UUID de joueur. Le tag regroupe les items, donc deux items portant
`tag: "stamina"` consomment la même réserve personnelle. Les réserves vivent en mémoire et
sont abandonnées à la déconnexion, après exécution de `onDepletedCommands` si un drain était
actif.

## Un exemple complet annoté

```json
{
  "items": [
    {
      "id": "lantern_of_arkensia",
      "displayName": "Lantern of Arkensia",
      "nameColor": "#F5A623",
      "baseItem": "minecraft:soul_lantern",

      "lore": [
        "A quest item. It remembers the way.",
        "Right-click near the shrine."
      ],
      "loreColor": "#9CA3AF",
      "glowing": true,
      "dyeColor": -1,

      "enabled": true,
      "maxStackSize": 1,
      "unbreakable": true,
      "consumable": false,
      "soulbound": false,
      "disappearOnDeath": false,
      "droppable": true,
      "tradeable": true,
      "placeable": false,

      "maxUses": 3,
      "cooldownTicks": 200,

      "onUseEffects": [
        { "effectId": "minecraft:night_vision", "amplifier": 0, "durationTicks": 1200 }
      ],
      "passiveEffects": [
        { "effectId": "minecraft:glowing", "amplifier": 0, "durationTicks": 100 }
      ],
      "onPickupEffects": [],

      "useActions": [
        {
          "command": "playsound minecraft:block.beacon.activate master {player}",
          "count": 1,
          "feedback": "La lanterne répond.",
          "delayCommand": "tp {player} 148 64 -512",
          "delaySeconds": 5,
          "showTimer": true,
          "timerLabel": "Sanctuaire"
        }
      ],

      "useConditions": {
        "dimension": "minecraft:overworld",
        "minLevel": 5,
        "requiredHeldItem": "minecraft:amethyst_shard",
        "globalCooldownTicks": 0
      }
    }
  ],
  "customModelData": {
    "lantern_of_arkensia": 1
  },
  "resourcePackHost": "play.example.net:44311"
}
```

Lecture de cette définition en jeu : l'item s'empile à un, brille, et ne peut pas être
utilisé hors de l'Overworld, sous le niveau d'expérience 5, ou sans éclat d'amethyste en
main secondaire. Une utilisation valide joue un son, affiche *La lanterne répond.*, démarre
un compte à rebours `Sanctuaire` de cinq secondes, accorde la vision nocturne pendant une
minute et brûle une charge sur trois. Le garder en main maintient le joueur surligné. La
troisième utilisation le détruit.

`customModelData` et `resourcePackHost` ne font pas partie de l'item — ils sont voisins du
tableau et sont traités dans [configuration](configuration.fr.md).

## <a id="rechargement-à-chaud-et-items-déjà-dans-les-inventaires"></a>Rechargement à chaud et items déjà dans les inventaires

C'est la question qui compte sur un serveur en production, et le comportement exact
tient en quelques règles.

`refreshAllPlayers()` tourne après `/cheeseitem reload`, après un `PUT` sur un item, après
l'envoi d'une icône et après la suppression d'une icône. Il parcourt chaque case de
l'inventaire de chaque joueur **connecté**. Pour chaque pile portant un tag `cheeseitems`
dont l'identifiant correspond encore à une définition :

- si `baseItem` correspond toujours à l'item de la pile, les composants visuels sont
  réappliqués sur place : nom, couleur, lore, taille de pile, brillance, incassable,
  teinture, custom model data ;
- si `baseItem` a changé, la pile est reconstruite de zéro avec le même nombre
  d'exemplaires, et le compteur `usesLeft` est recopié.

Puis `player.currentScreenHandler.sendContentUpdates()` pousse les changements au client.

**Ce qui n'est pas rafraîchi :**

| Cas | Ce qui se passe |
|---|---|
| Le joueur est hors ligne | rien. La pile est mise à jour au prochain rafraîchissement pendant qu'il est connecté |
| La pile est dans un coffre, un coffre de l'Ender, une boîte de Shulker, un cadre, un entonnoir, ou au sol | jamais touchée. Seuls les inventaires de joueurs sont parcourus |
| La définition a été **supprimée** | la pile garde son tag, son ancien nom et son ancien lore pour toujours. Elle ne correspond plus à rien, donc le clic droit ne fait rien de plus que l'item de base, et aucune passe ne la nettoiera jamais |
| L'`id` de la définition a changé | identique à une suppression : les anciennes piles sont orphelines |
| `maxUses` a été augmenté ou réduit | les piles existantes gardent leur `usesLeft` écrit, même lors d'une reconstruction due au `baseItem`, qui le recopie. Seule une pile sans aucun compteur en reçoit un, et seulement lors d'une telle reconstruction |
| `useActions`, `useConditions`, effets, options | pas stockés sur la pile du tout, donc ils prennent effet immédiatement — rien à rafraîchir |

Une pile orpheline est un problème cosmétique, pas dangereux : ses commandes ont disparu
avec sa définition. Pour récupérer après une suppression accidentelle, recrée la définition
avec exactement le même `id` — les anciennes piles se remettent alors à fonctionner, parce
que la seule chose qui les relie est cette chaîne.

Le rechargement *remplace* aussi la liste en mémoire, ce qui veut dire qu'un item créé
depuis le dashboard mais pas encore écrit sur le disque ne peut pas exister : `createItem`,
`updateItem` et `deleteItem` appellent tous `config.save()` avant de rendre la main. Un
rechargement ne perd jamais une modification faite depuis le dashboard.

## <a id="les-options-qui-ne-font-rien"></a>Les options qui ne font rien

Section d'honnêteté. Ces champs sont acceptés par le chargeur, transportés par l'API et
affichés dans l'éditeur du dashboard, et ne changent rien en jeu.

| Champ | Pourquoi |
|---|---|
| `droppable` | `UseHandler.shouldBlockDrop` existe et envoie `Cet item ne peut pas être jeté.`, mais **aucun gestionnaire d'événement ne l'appelle**. N'importe quel item peut être jeté |
| `onPickupEffects` | `UseHandler.onItemPickup` existe et n'est jamais appelé. Ramasser un item n'applique rien |
| `tradeable` | aucun code ne le lit |
| `placeable` | aucun code ne le lit. Un `baseItem` qui est un bloc peut toujours être posé |
| `useConditions.zoneId` | aucun code ne le lit. `checkConditions` ne consulte jamais CheeseGuard |
| `endurance.displayName` | stocké dans l'état de la réserve, jamais affiché |

⚠️ **`soulbound` est pire qu'inerte : il supprime l'item.** À la mort, chaque pile soulbound
est copiée dans une réserve en mémoire et **retirée de l'inventaire**. La méthode qui les
rend, `onPlayerRespawn`, n'est appelée par rien — le gestionnaire `AFTER_DEATH` dans
`CheeseItemsMod` est un bloc vide avec un commentaire disant que la restauration devrait se
faire ailleurs. La réserve est ensuite perdue au redémarrage du serveur.

En attendant un correctif, ne mets pas `soulbound: true`. Utilise `disappearOnDeath: false`
(le défaut) et laisse l'item tomber avec le reste de l'inventaire du joueur, ou garde l'item
hors des zones de combat.

---

← [Configuration](configuration.fr.md) · [Resource pack](resource-pack.fr.md) →
