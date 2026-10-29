[← CheeseRaids](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](integrations.md)

# Intégrations externes

CheeseRaids dialogue avec six autres mods. Deux sont des dépendances dures contre lesquelles
il compile ; quatre sont atteints par **réflexion** et se dégradent en leur absence. Ce
partage détermine ce qu'une mise à jour de dépendance peut casser, et avec quel bruit.

> ⚠️ Tout ce qui figure sur cette page est lu dans les sources de CheeseRaids. Le
> comportement des mods tiers eux-mêmes est déduit des noms de classes et de méthodes que les
> hooks recherchent, pas de leur code — considère ces détails comme des observations.

---

## La carte des dépendances

| Mod | Déclaré dans `fabric.mod.json` | Comment il est atteint | S'il est absent |
|---|---|---|---|
| Fabric API | `depends` | API normale | le mod ne charge pas |
| [CheeseDash](../../CheeseDash/) | `depends` | compilation, `modCompileOnly` | le mod ne charge pas |
| [CheeseGuard](../../CheeseGuard/) | `depends` | compilation, `modCompileOnly` | le mod ne charge pas |
| Cobblemon | non déclaré | réflexion | pas de vague, pas de boss — le raid tourne à vide |
| EasyNPC | non déclaré | réflexion | pas de héraut ; le secours par commande est annoncé |
| CobbleSafari | non déclaré | réflexion | pas de portails de victoire |
| [CheeseStore](../../CheeseStore/) | non déclaré | réflexion | `reward.fromage` est sans effet, en silence |

Quatre de ces sept ne sont déclarés nulle part : Fabric ne peut donc pas t'avertir s'il en
manque un, et une construction de modpack ne peut pas l'attraper. Cobblemon en particulier
n'est **pas facultatif en pratique** : sans lui, le raid s'annonce, ne fait rien apparaître,
arrive en timeout et se nettoie. La seule raison pour laquelle il n'est pas une dépendance
déclarée, c'est que tous les accès passent par de la réflexion.

## La règle de réflexion que ce mod suit

Sur l'ensemble de ces mods, il existe une règle permanente : ne jamais résoudre une méthode
Minecraft par son nom Yarn, parce qu'à l'exécution ces noms sont intermédiaires. CheeseRaids
applique la même discipline aux mods tiers, ce qui explique l'allure étrange des hooks. Au lieu
de `getDeclaredMethod("setDisplayAttribute", …)`, ils parcourent `getMethods()` et comparent la
**forme** — types de paramètres, nombre de paramètres, type de retour.

Le bénéfice : un renommage de méthode côté tiers ne casse pas le hook. Le coût : un changement
de *signature* le casse en silence, et ces correspondances sont assez souples pour être
fragiles autrement. `CobblemonBattleHook.getBattleId` accepte n'importe quelle méthode sans
argument renvoyant un `UUID` dont le nom contient `battle` — si une mise à jour de Cobblemon
ajoute une seconde méthode de ce genre, le hook peut choisir la mauvaise.

## Commandes contre API

Documenter ce mod a commencé sur une hypothèse qu'il faut corriger : que le raid ferait
apparaître ses entités en lançant des commandes textuelles. **Ce n'est pas le cas pour ce qui
compte.** Le fichier de configuration porte encore les modèles de commande d'une conception
antérieure, mais ils sont morts :

| Clé de configuration | À quoi elle ressemble | En réalité |
|---|---|---|
| `wave.spawnCommand` | `execute in {dim} positioned {x} {y} {z} run pokespawn {species} {aspects} level={level}` | inutilisée. La vague passe par `CobblemonSpawnHook` |
| `boss.spawnCommand` | le même modèle | inutilisée. Le boss passe par le même hook |
| `boss.scaleCommand` | un modèle prenant `{uuid} {scale}` | inutilisée. La mise à l'échelle passe par `setScaleModifier` de Cobblemon |

Le commentaire du code explique pourquoi les commandes ont été abandonnées, et c'est la phrase
la plus utile du mod : `pokespawn` lancée depuis une source console silencieuse **avalait ses
propres échecs**, et l'échec réellement rencontré — `ServerWorld.addEntity` renvoyant `false`
parce que le chunk n'était pas chargé — ne produisait ni apparition, ni erreur. L'appel direct
renvoie un booléen vérifiable et une poignée sur l'entité : un refus est journalisé, et il n'y
a plus à devenir quelle entité a été créée.

Trois modèles de commande **sont** vivants :

| Clé | Quand elle s'exécute | Marqueurs |
|---|---|---|
| `npc.showCommand` | à la révélation du héraut, en plus de la bascule Java | `{world} {type} {x} {y} {z} {npc}` |
| `npc.hideCommand` | quand le héraut est masqué | les mêmes |
| `reward.portalCommand` | seulement si CobbleSafari n'a ouvert aucun portail | `{dim} {x} {y} {z} {index}` |

Les trois sont vides par défaut : le mod n'exécute donc aucune commande modélisée tel qu'il est
livré. Quand l'une tourne, elle passe par une source console de niveau 4 dont la sortie est
**capturée et journalisée** plutôt que jetée, et une `CommandSyntaxException` est journalisée
avec le texte de la commande. C'est l'inverse du problème `pokespawn`, et ça vaut d'être copié
si tu ajoutes ton propre modèle.

Deux commandes vanilla sont aussi lancées directement, sans condition, construites dans le
code : `weather thunder 1200` et `time set midnight` (et `weather clear` au nettoyage),
enveloppées dans `execute in <world> run`, plus `title @a …` pour l'annonce. Ce sont des
syntaxes vanilla stables ; elles ne constituent pas un risque côté tiers.

---

## CheeseGuard

La seule dépendance dure que le mod utilise effectivement à l'exécution, et il l'utilise
directement — vrais imports, pas de réflexion :

```java
com.cheeseguard.CheeseGuardMod.INSTANCE.getDimensionManager()
```

Trois appels :

- **`createDimension(id, name, GenType.VOID, cfg -> …)`** — crée la faille la première fois
  qu'un joueur entre, comme un monde vide nommé `<displayName> — Faille`, avec le spawn à
  `arena.center` + 1, `keepInventory` depuis la configuration, PvP désactivé, apparition de
  mobs désactivée, antres de raid désactivés, heure verrouillée à 18000, météo verrouillée, et
  `enabled = false` pour que personne n'y entre hors d'un raid. `createDimension` sort
  immédiatement si la dimension existe déjà : c'est pourquoi changer `arena.keepInventory`
  après le premier raid n'a aucun effet ;
- **`getConfig(id)` + `applyTimeLock(cfg)` + `save()`** — ré-épingle minuit. Une faille créée
  par une version plus ancienne du mod garde son ancienne configuration : le verrou est donc
  forcé ici. Le `save()` bloquant tourne au plus une fois par faille et par session serveur ;
  l'entrée d'un joueur ne ré-épingle que l'affichage, sans écriture disque ;
- rien d'autre. Les zones, les événements et l'outillage de chunks de CheeseGuard ne sont pas
  utilisés.

L'ancien README affirmait que les raids « s'accrochent à ses événements ». C'est faux : il n'y
a aucun type d'`EventEffect` CheeseGuard pour les raids dans ce mod, dans aucun sens. Compare
avec [CheeseKart](../../CheeseKart/), qui enregistre bien `KART_START_ALL` et
`KART_STOP_ALL`.

Comme la dépendance est à la compilation et déclarée dans `depends`, un changement d'API de
CheeseGuard casse la **construction**, bruyamment, ce qui est le bon mode d'échec. Il n'y a
pas de dégradation en douceur : si `CheeseGuardMod.INSTANCE` est nul, la création de la faille
lève, est attrapée, journalisée comme `Failed to create rift dimension`, et chaque `enter`
répond « La faille n'a pas pu s'ouvrir. »

## Cobblemon

Deux hooks, tous deux par réflexion, tous deux essentiels.

### Apparition — `CobblemonSpawnHook`

Résolu une fois, paresseusement :

```text
com.cobblemon.mod.common.api.pokemon.PokemonProperties
  → champ public static Companion
  → Companion.parse(String)
  → PokemonProperties.createEntity(World)   [trouvé par nom + un paramètre assignable depuis World]
```

Puis `refreshPositionAndAngles`, un tag de commande facultatif, le modificateur d'échelle, la
persistance vanilla, un `getChunk` synchrone pour forcer le chunk cible, et
`world.spawnEntity`.

La chaîne de propriétés est assemblée par le mod, pas configurée :

```text
<species> level=<n> aspect=<a> aspect=<b> … uncatchable=true
```

`aspects` est une liste à virgules dans la configuration et devient un jeton `aspect=` par
entrée. Tout ce que le mod fait apparaître est `uncatchable=true` — mobs de vague comme boss.
Il n'y a aucune option pour rendre un Pokémon de raid capturable.

Les échecs sont journalisés, chacun avec sa ligne propre : `Cobblemon API init failed`,
`Cobblemon API unavailable; cannot spawn '<props>'`, `createEntity returned non-Entity`,
`spawnEntity refused '<props>' at x,y,z (chunk not loaded / build limit?)`. Si tu débogues un
raid vide, ce sont ces quatre chaînes à chercher.

Le chemin de mise à l'échelle est distinct et plus souple : il cherche sur l'entité une méthode
`getPokemon()` sans argument, puis sur l'objet renvoyé un mutateur à un `float` dont le nom
contient `scalemodifier`. Un échec journalise `scale <n> unsupported` et le mob apparaît à
taille normale. Le combat fonctionne quand même ; le boss n'est simplement pas géant.

### Combats — `CobblemonBattleHook`

```text
com.cobblemon.mod.common.battles.BattleRegistry
  → champ public static INSTANCE
  → getBattle(UUID)
```

Ne sert qu'à répondre à une question : le combat du boss est-il terminé ? L'identifiant de
combat de l'entité est lu par forme (zéro paramètre, renvoie un `UUID`, nom contenant
`battle`) à chaque tick de combat et mémorisé ; la victoire attend ensuite que `getBattle(id)`
renvoie `null`.

Si ce hook échoue à se résoudre, le mod retombe sur un test d'âge : entité disparue et plus de
cinq secondes depuis l'apparition comptent pour un K.O. C'est ce repli qui rend un échec de
hook survivable ici, et aussi pourquoi les joueurs peuvent être téléportés une ou deux
secondes trop tôt.

### Ce qui casse à une mise à jour de Cobblemon

Un renommage de `PokemonProperties`, `Companion`, `parse`, `BattleRegistry` ou `INSTANCE`
casse le hook — ce sont des correspondances par nom exact. Un changement des types de
paramètres de `createEntity`, ou de la signature de `getBattle(UUID)`, le casse aussi. Le mod
a été écrit contre Cobblemon 1.6.1, version que désigne le commentaire sur
`setScaleModifier`.

L'apparition échoue bruyamment : le journal est explicite et le raid ne fait visiblement rien
apparaître. Le hook de combat échoue silencieusement, avec repli. Le hook d'échelle échoue
silencieusement, avec un avertissement.

## EasyNPC

```text
de.markusbordihn.easynpc.data.display.DisplayAttributeType  → constante d'énumération VISIBLE
de.markusbordihn.easynpc.data.type.ValueType                → constante d'énumération BOOLEAN
<l'entité NPC>.<une méthode>(DisplayAttributeType, ValueType, Boolean)
```

La troisième ligne est trouvée uniquement par forme de paramètres : le nom de la méthode peut
donc changer librement. Les deux classes d'énumération et leurs constantes sont trouvées par
nom : **elles** ne peuvent pas.

Pourquoi ceci plutôt que l'invisibilité vanilla : les entités EasyNPC surchargent
`isInvisible()` pour lire leur propre attribut d'affichage `VISIBLE`, donc `setInvisible(true)`
est ignoré. L'attribut d'affichage est le vrai levier, et il écrit des données suivies à
synchronisation automatique : les clients se mettent à jour tout seuls.

Les NPC candidats sont trouvés par préfixe d'identifiant de registre `easy_npc`, dans une boîte
élargie de 10 blocs autour de `npc.pos`, dans le monde désigné par `npc.pos.world`.
`npc.entityType`, dans la configuration, **ne fait pas partie** de la recherche — il n'alimente
que `{type}` dans les modèles de commande.

Trois modes d'échec distincts, que `/cheeseraids herald <id> info` sépare :

| Symptôme | Signification |
|---|---|
| `EasyNPC visibility API not found` à l'initialisation | EasyNPC est absent, ou les deux classes d'énumération ont bougé |
| `no EasyNPC found within 10 blocks of <pos>` | mauvaises coordonnées, mauvais monde, ou chunk non chargé |
| `N EasyNPC(s) near <pos>, 0 toggled via display-attr` | EasyNPC est là et a été trouvé, mais le mutateur à trois paramètres n'existe plus. Journalise aussi `setDisplayAttribute(VISIBLE,...) not found` |

Un raid dont le héraut ne peut pas être révélé ne se bloque pas : il annonce
`/cheeseraids enter <id>` dans le chat à la place.

**Le dialogue est à toi de configurer.** CheeseRaids bascule la visibilité et la luminescence.
Il ne crée pas le NPC, ne lui donne pas de skin, et ne lui lie aucune action. Faire du héraut
un véritable point d'entrée veut dire configurer son dialogue dans EasyNPC pour lancer
`/cheeseraids enter <id>`. Rien ici ne le fait, et rien ne vérifie que c'est fait — c'est
pourquoi un raid non calibré peut sembler fonctionner jusqu'au moment précis où les joueurs ne
peuvent pas entrer.

## CobbleSafari

```text
maxigregrze.cobblesafari.dungeon.PortalSpawnManager
  → spawnPortal(ServerWorld, BlockPos, ServerPlayerEntity)   [privée ; setAccessible]
  → spawnPortalNearPlayer(ServerPlayerEntity, boolean)        [secours]
```

Les deux sont trouvées par nom **et** par forme, et toutes deux invoquées statiquement
(premier argument `null`). La méthode principale est privée, ce qui constitue une incursion
délibérée dans les entrailles d'un autre mod : c'est le seul moyen de placer un portail à une
position choisie plutôt que près d'un joueur. Le secours est public mais limité à l'overworld,
et ignore totalement la position.

L'appel exige un participant connecté comme propriétaire. Le commentaire indique que
CobbleSafari peut lever sur un propriétaire nul : le chemin de récompense en exige donc un, et
passe sinon directement au secours par commande.

Trois paliers de dégradation, dans l'ordre : la méthode privée, puis la méthode publique près
du joueur, puis `reward.portalCommand` s'il est renseigné, puis un avertissement et aucun
portail. Le raid se termine proprement dans tous les cas.

C'est le hook le plus fragile du mod, et celui qui risque le plus de casser sans que personne
ne le remarque. Une méthode privée n'est pas une API ; CobbleSafari ne lui doit aucune
stabilité.

## CheeseStore

```text
com.cheesestore.WalletManager
  → getInstance()
  → add(UUID, String, long)
```

Trouvé entièrement par nom. Appelé une fois par gagnant quand `reward.fromage > 0`.
`ClassNotFoundException` est attrapée et ignorée — CheeseStore non installé est une condition
normale, pas une erreur. Toute autre exception journalise `fromage reward failed`.

Comme `reward.fromage` vaut 0 par défaut, le hook est inerte tant que tu ne l'actives pas.

---

## Si tu ajoutes une intégration

Le motif à copier est `CobblemonSpawnHook` : un `private static volatile boolean init`, un
résolveur paresseux `synchronized`, un prédicat `available()`, une correspondance de méthode
par forme, une ligne de journal distincte à chaque point d'échec, et une valeur de retour que
l'appelant peut vérifier. Le motif à éviter est un hook qui renvoie `void` et ne journalise
rien — `CobbleSafariHook` s'en approche, et c'est celui sur lequel tu passeras une soirée
quand il cessera de fonctionner.

---

← [L'Invasion de Hoopa](hoopa-invasion.fr.md) · [API dashboard](dashboard.fr.md) →
