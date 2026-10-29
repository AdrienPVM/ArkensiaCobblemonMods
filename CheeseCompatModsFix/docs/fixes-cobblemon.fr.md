[← CheeseCompatModsFix](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](fixes-cobblemon.md)

# Catalogue des correctifs — Cobblemon, combats et méga-évolution

> ⚠️ Chaque correctif de cette page vise une version précise d'un mod tiers. Deux d'entre
> eux ne sont pas actifs : l'un a été désactivé après avoir cassé la production, l'autre
> n'a jamais été écrit parce que la tentative avait cassé la production. Les deux sont
> documentés ici volontairement.

**Mods visés sur cette page**

| Mod | Id du mod | Version visée à la compilation |
|---|---|---|
| Cobblemon | `cobblemon` | 1.6.1 (Fabric), id Modrinth `v77SHSXW` |
| MegaShowdown | — (réflexion seule) | pas sur le classpath de compilation |
| Cobblemon Trials Edition | `cobblemontrialsedition` | 1.2.2 (Fabric), id `XdMo4HiB` |
| Cobblemon Raid Dens | `cobblemonraiddens` | 0.4.7 (Fabric), id `d8mGjw4X` |
| Cobblemon Fight or Flight Reborn | `fightorflight` | 0.9.0 (Fabric), id `6McAEN1y` |
| CobblemonAlphas | — (réflexion seule) | pas sur le classpath de compilation |
| Rad Gyms | `rad-gyms` | pas sur le classpath de compilation |

---

## ⚠️ 1. Le chien de garde de combat qui a gelé tous les combats (désactivé)

**État : DÉSACTIVÉ. La classe est toujours dans l'arbre source, et son unique site
d'appel est commenté.**

C'est l'avertissement le plus utile que ce mod puisse donner, donc il passe en premier.

### Ce qu'il devait faire

`RctForcedSwitchFix`, ajouté en **1.2.51**, visait un bug réel et reproductible. Quand
plusieurs Pokémon tombent au même tick — Perish Song, un effet Requiem dans un combat Rad
Gyms, un double KO en coop 2v2 à la Battle Tower — le moteur Cobblemon émet une requête de
choix (`|request|`, en pratique un force-switch) pour un acteur, mais le routage de cette
requête vers un acteur IA non joueur ne déclenche jamais
`AIBattleActor.onChoiceRequested()`. L'acteur PNJ reste avec un `request` non nul, une
liste `responses` vide, et `PokemonBattle.checkForInputDispatch()` attend indéfiniment une
réponse. Le combat est figé.

Le chien de garde tournait sur `ServerTickEvents.END_SERVER_TICK`, parcourait
`BattleRegistry.battleMap` par réflexion (il n'y a pas de getter public), cherchait un
acteur non joueur avec une requête en attente et aucune réponse, et après un debounce
rejouait `onChoiceRequested()` dessus. Il était écrit en fail-open : toute exception
journalisait un avertissement et ne faisait rien. Il était idempotent par objet requête.
Il tournait uniquement sur le thread serveur.

### Ce qui s'est réellement passé

La détection était fausse, et fausse de la façon la plus coûteuse possible.
`isStuckNpcActor()` marquait comme « figé » **tout** acteur IA non joueur ayant une requête
active et une liste `responses` vide — c'est-à-dire l'état **normal** de chaque tour
pendant que le joueur humain réfléchit à son coup. Le debounce était de 60 ticks, trois
secondes. Tout joueur réfléchissant trois secondes voyait le chien de garde rejouer
`onChoiceRequested()` sous lui, au milieu d'un combat parfaitement sain, sauvage comme
dresseur. Ça désynchronisait Showdown et **gelait tous les combats du serveur**.

La forensique de production a ajouté un second constat : il ne débloquait même pas les
vrais cas de force-switch pour lesquels il avait été bâti. Ceux-là revenaient en
`Can't pass` rejeté. Le résultat mesuré était donc aucun bénéfice et des dégâts à
l'échelle du serveur.

Il a été désactivé en **1.2.52** en commentant l'enregistrement dans
`CheeseCompatMod.onInitialize()`. Le fichier de classe subsiste, avec l'autopsie complète
en commentaire au site d'appel. Le cas Perish Song / double KO reste ouvert, et la note
dans le code dit qu'il faudrait un filtre force-switch strict avant que quiconque réessaie.

### La leçon, dite franchement

Une heuristique « détecter un combat figé » doit distinguer *en attente* de *figé*, et dans
un moteur au tour par tour ces deux états sont indiscernables de l'extérieur. Requête en
attente plus réponses vides n'est pas la preuve d'un blocage ; c'est la définition d'un tour
en cours. Tout chien de garde de ce genre a besoin d'un signal qui n'apparaît que dans le
cas cassé — pour le force-switch précisément, cela veut dire lire
`request.getForceSwitch()` et rien d'autre — et il doit être testé sur un serveur avec du
trafic réel, parce que c'est sur le chemin sain que tu te tromperas.

Si tu es tenté de réactiver cette classe : ne le fais pas, pas telle qu'elle est écrite.

## ⚠️ 2. Le mixin sur les objets tenus, refusé (jamais retenté)

**État : délibérément non implémenté. Il n'y a pas de tel mixin dans l'arbre source, et il
ne devrait pas y en avoir.**

En **1.2.37**, une tentative a été faite de mixiner dans la gestion des objets tenus de
MegaShowdown, pour que les méga-gemmes custom se résolvent dans le simulateur Showdown. Ça
a cassé **tous les échanges d'objets tenus par les Pokémon du serveur** — pas seulement
les méga-gemmes, tous les objets tenus, pour tout le monde. Ça a été annulé, et la décision
prise a été de ne pas réessayer cette approche.

Le comportement de consommation de `rusted_sword` que la tentative poursuivait à l'origine
est sciemment laissé non corrigé. C'est un WONTFIX, pas une tâche ouverte.

Ce qui l'a remplacé est le correctif 4 ci-dessous : Cobblemon expose une API publique
d'enregistrement exactement pour ça, et l'employer ne touche au chemin de code des objets
tenus de personne. Si tu te sens partir vers un mixin sur les objets tenus, c'est cette API
qu'il faut attraper à la place.

## 3. Les formes Mega-Z ne dé-évoluent jamais

**Symptôme.** Un Pokémon qui méga-évolue vers une forme Mega-Z reste dans cette forme pour
toujours — après le combat, après un rechargement, définitivement. Elle ne compte pas non
plus dans le plafond d'une méga par joueur et par combat, donc un joueur peut méga deux
fois.

**Cause.** MegaShowdown code en dur
`mega_aspects = HashSet(Set.of("mega", "mega_x", "mega_y"))` dans l'initialiseur statique
de `com.cobblemon.yajatkaul.mega_showdown.formChangeLogic.FormChangeHelper`. Une forme dont
l'aspect est `mega_z` est invisible pour `hasMega()` (d'où le contournement du plafond) et
pour le `Devolve()` d'après-combat (d'où la permanence).

**Moyen.** `MegaShowdownAspectPatch.apply()`, appelé depuis `onInitialize`. Il résout la
classe par nom, lit le champ `mega_aspects` par réflexion et ajoute `"mega_z"` au `HashSet`
vivant. Le bytecode décompilé confirme qu'il s'agit d'un `HashSet` ordinaire, donc la
mutation est sûre.

**Inerte en l'absence du mod.** Oui — `Class.forName` échoue, le patch journalise et
rend la main. Il n'y a aucun mixin et aucune dépendance de compilation vers MegaShowdown.

**État.** Actif.

## 4. Méga-gemmes custom non reconnues par le simulateur

**Symptôme.** Un joueur tient l'une des 42 méga-gemmes CacademyMons, et le bouton Méga est
grisé, sans message et sans rien dans le log.

**Cause.** Le datapack CacademyMons livre ses méga-gemmes sous forme de simples
`minecraft:brick` (plus une poignée d'autres items vanilla), distingués uniquement par leur
`custom_model_data`. Le `CobblemonHeldItemManager` de Cobblemon construit l'id d'objet tenu
qu'il envoie au simulateur Showdown depuis le chemin de registre de l'item — `"brick"` —
que Showdown ne peut pas résoudre vers un id de gemme comme `lucarionitez`. Du coup
`pokemon.heldItem` arrive vide dans le simulateur, `canMegaEvo()` renvoie null, et le
bouton est désactivé en silence.

**Moyen.** `CacademyMegaStoneRemapPatch.apply()` enregistre une fonction `stackRemap` via
l'**API publique** de Cobblemon, `CobblemonHeldItemManager.registerStackRemap` (résolue par
réflexion, parce que ce mod compile contre Cobblemon en `compileOnly`). La fonction mappe
le couple `(id de registre de l'item, custom_model_data)` vers le bon id de gemme Showdown.
Cobblemon la consulte ensuite à chaque recherche d'objet tenu pendant la mise en place du
combat, et les entrées existantes de `items.js` / `held_items.js` qui déclarent `megaStone`
et `megaEvolves` prennent effet toutes seules.

Périmètre : seulement les 42 gemmes qui ont un couple `(item_id, cmd)` unique. Les entrées
où `cmd == 0` sur `minecraft:brick` entrent en collision entre elles et sont ignorées —
c'étaient des bouchons dans le datapack.

**Inerte en l'absence du mod.** Oui, deux fois : sans Cobblemon la résolution par
réflexion échoue et le patch rend la main ; sans le datapack CacademyMons la table de remap
ne correspond jamais à rien.

**État.** Actif. ⚠️ Ce correctif est spécifique à un datapack. Les ids d'items et les
numéros de `custom_model_data` sont codés en dur dans `REMAP`. Sur tout autre serveur il ne
fait rien — ce qui est le mode d'échec voulu, mais autant le savoir avant de lire les 241
lignes de table.

## 5. Trials Edition crashe sur Cobblemon 1.6.1

**Symptôme.** `IncompatibleClassChangeError` dès que Cobblemon Trials Edition essaie de
résoudre une nature.

**Cause.** Dans Cobblemon 1.7.x, les méthodes de `Natures` sont `@JvmStatic` ; en 1.6.1 ce
sont des méthodes d'instance sur le singleton Kotlin `Natures.INSTANCE`. Trials Edition a
été compilé contre 1.7.x, donc il émet un `invokestatic` vers une méthode qui n'est pas
statique sur ce serveur.

**Moyen.** `TrialsNatureMixin` — un `@Overwrite` sur `SpawnablePokemonProperties`, qui
remplace l'appel par un appel routé via `Natures.INSTANCE`.

**Inerte en l'absence du mod.** Oui — conditionné à `cobblemontrialsedition` dans le
plugin mixin.

**État.** Actif. Un `@Overwrite` est le plus gros marteau que Mixin propose : il remplace
le corps de la méthode en entier et entre en conflit avec tout autre mod touchant la même
méthode. Il est employé ici parce que le correctif est une instruction de bytecode
différente, pas un comportement ajouté.

## 6. Les alphas sont incapturables, en silence

**Symptôme.** Un Pokémon CobblemonAlphas ne peut pas être capturé. Les Poké Balls
rebondissent, pas d'animation de capture, pas de message, rien. Le joueur n'a aucun moyen
de savoir s'il fait une erreur.

**Cause.** Inconnue, et c'est dit dans le code. Les alphas apparaissent avec un état
interne d'entité corrompu qui empêche `EmptyPokeBallEntity.onEntityHit` de se déclencher.
Tous les chemins du `onEntityHit` de Cobblemon qui *bloquent* une capture envoient un
message en chat, et ce symptôme est muet — donc le jet n'est pas bloqué, il n'arrive pas.
Le diagnostic qui a cerné le problème : exécuter
`/data merge entity … {Pokemon:{ScaleModifier:2.5f}}` avec la **même** valeur d'échelle, ne
changeant rien, rend l'alpha capturable. Le déclencheur est donc un état que `readNbt()`
remet à zéro, pas une incohérence d'échelle ou de hitbox.

**Moyen.** `AlphaScaleHitboxFixMixin`, cible par chaîne
(`com.cobblemon.mod.common.entity.pokemon.PokemonEntity`, `remap = false`), `@Inject` en
`HEAD` de `method_5773` — le nom intermédiaire de `Entity.tick()`, écrit explicitement
parce que les noms Yarn ne sont pas traduits quand `remap = false`. Au premier tick d'une
entité marquée `IS_ALPHA: true` dans son `Pokemon.PersistentData`, il effectue un
aller-retour NBT (`writeNbt` puis `readNbt`), ce que `/data merge` fait en interne.
L'échelle, l'AABB, le drapeau `IS_ALPHA`, les IV, les talents et les butins survivent tous
intacts.

Pour un Pokémon non alpha, c'est une vérification booléenne et une lecture de
`PersistentData` par entité, une seule fois.

**Inerte en l'absence du mod.** Ce mixin n'a pas d'entrée dans `CheeseCompatMixinPlugin`,
donc il tombe dans la branche par défaut qui renvoie `true` et il *est* appliqué dès que la
classe cible existe. Sans Cobblemon la classe cible n'existe pas et `require = 0` fait que
l'injection est ignorée en silence au lieu de crasher. C'est le seul correctif du mod dont
l'inertie repose sur `require = 0` plutôt que sur une vérification de mod explicite.

**État.** Actif. Un correctif pour une cause que personne n'a identifiée, ce qui est dit
tel quel dans son propre javadoc. Ça marche ; personne ne prétend savoir pourquoi l'état
est corrompu au départ.

## 7. Un Giratina capturé attaque son dresseur

**Symptôme.** Un joueur qui capture un Giratina obtient un Pokémon qui se comporte comme un
Warden et attaque.

**Cause.** L'IA agressive vient du `PokemonAttackGoal` de Fight or Flight, pas du mod de
boss que tout le monde a soupçonné. Le goal ne vérifie pas la propriété.

**Moyen.** `FoFOwnedGiratinaNoAggroMixin` — `@Inject` en `HEAD` de `method_6264`
(`Goal.canUse()`), `cancellable`, `require = 0`. Un boss apparu via ce mod porte le tag de
commande `cheesecompat_boss` et passe ; un Giratina avec un propriétaire Cobblemon voit
`canUse()` forcé à faux.

À lire comme une leçon de mixin : **la 1.2.39 utilisait
`@Shadow protected Entity pokemonEntity;` et crashait le serveur au démarrage** avec
`InvalidMixinException: @Shadow field pokemonEntity was not located in the target class`.
Le vrai type du champ est `PokemonEntity`, et `@Shadow` exige une correspondance de type
exacte. La 1.2.40 a abandonné `@Shadow` complètement et lit le champ par réflexion, en
mettant le `Field` en cache au premier appel — ce qui supprime aussi toute dépendance de
compilation vers la hiérarchie d'entités de Cobblemon.

**Inerte en l'absence du mod.** Oui — conditionné à `fightorflight`.

**État.** Actif.

## 8. Raid dens : quatre correctifs

Tous les quatre sont conditionnés à `cobblemonraiddens` dans le plugin mixin.

### 8a. Téracristallisation pendant un raid

**Symptôme.** Les joueurs téracristallisent dans un raid, ce que le design du raid ne
prévoit pas.

**Moyen.** `RaidTeraBlockMixin` — `@Inject` en `TAIL` de
`ShowdownActionRequest.sanitize()`, qui retire l'option tera quand le combat se déroule
dans une dimension de l'espace de noms `cobblemonraiddens`. Note que la classe cible
appartient à **Cobblemon**, pas à Raid Dens ; le conditionnement porte sur Raid Dens parce
que c'est la seule raison de l'appliquer.

### 8b. Le boss regarde du mauvais côté

**Symptôme.** Le boss du raid apparaît dos au cristal et aux joueurs.

**Moyen.** `RaidBossFacingMixin` — `@Inject` en `TAIL` de
`RaidCrystalBlockEntity.spawnRaidBoss`, qui tourne le boss vers le cristal. Le cristal est
toujours en `(0, 0, 0)` dans une dimension de raid ; la position du boss vient de
`raid_pois.boss_x/y/z` dans le NBT de la structure.

### 8c. Crash au nettoyage de la dimension de raid

**Symptôme.** `ArrayIndexOutOfBoundsException` avec l'index -1 pendant le démarrage d'un
raid, sur des structures contenant beaucoup de blocs interactifs (redstone, ampoules de
cuivre, entonnoirs).

**Cause.** `spawnRaidBoss` appelle
`getDimension().iterateEntities().forEach(entity -> entity.discard())`. Ça supprime des
entités de l'`Int2ObjectLinkedOpenHashMap` de la dimension *pendant* l'itération, ce qui
corrompt les pointeurs de la liste chaînée.

**Moyen.** `RaidEntityClearMixin` — `@Redirect` sur l'appel à `Iterable.forEach`, qui prend
d'abord un instantané dans une `ArrayList` et applique le consumer de suppression sur la
copie.

### 8d. Le feu se propage dans les dimensions de raid

**Moyen.** `RaidFireSpreadMixin` — `@Inject` en `HEAD` de `FireBlock.scheduledTick`,
`cancellable`, qui annule dans toute dimension dont la clé de registre est dans l'espace de
noms `cobblemonraiddens`. La cible est une classe **vanilla**, donc ce mixin s'applique à
chaque tick de feu du serveur ; la vérification d'espace de noms est la première chose
qu'il fait.

### 8e. Des cristaux qui ne se referment jamais

**Symptôme.** Un cristal de raid reste ouvert après que tout le monde est parti, mort ou
déconnecté, donc personne ne peut relancer un raid là.

**Cause.** Raid Dens suivait l'état par id de dimension, et les ids de dimension sont
réutilisés par joueur. Deux raids rapprochés du même joueur écrasaient mutuellement leur
entrée dans la map, et le premier cristal était oublié.

**Moyen.** `RaidDenFix`, un gestionnaire piloté au tick et indexé par **position du
cristal** plutôt que par id de dimension. Quand un joueur sort d'un raid, il planifie une
vérification sur ce cristal dans `FAST_CHECK_DELAY` (20 ticks, 1 s), en force-loadant le
chunk du cristal si nécessaire, et sinon il scanne toutes les `SCAN_INTERVAL` (100 ticks,
5 s). Échelle d'escalade : `GRACE_PERIOD` 100 ticks avant toute action,
`MEDIUM_THRESHOLD` 200 ticks, `NUCLEAR_THRESHOLD` 300 ticks, et `GIVE_UP_THRESHOLD` 1200
ticks (60 s) après quoi il cesse de suivre ce cristal. L'abandon est voulu : un cristal que
ce mod n'arrive pas à fermer vaut mieux être laissé tranquille que réessayé indéfiniment.

**État.** Les cinq sont actifs.

## 9. Rad Gyms : quatre correctifs

**Cible :** `rad-gyms`. Pas sur le classpath de compilation — tous les accès passent par
réflexion sur `lol.gito.radgyms.*`. Conditionné à `isModLoaded("rad-gyms")` dans
`CheeseCompatMod`, donc la classe entière est inerte sans le mod.

### 9a. Joueurs bloqués dans une dimension de gym

**Symptôme.** Après un crash ou un redémarrage, un joueur est dans
`rad-gyms:rad-gyms_dim` sans aucun moyen de sortir.

**Cause.** Rad Gyms garde ses instances de gym dans une `LinkedHashMap` `PLAYER_GYMS`
uniquement en mémoire. Un crash perd la map, et avec elle toute voie de sortie.

**Moyen.** À la connexion, les joueurs trouvés dans la dimension de gym sans instance sont
téléportés dehors et informés, via `cheesecompat.radgyms.rescued` :

```text
Vous avez été rescapé d'une dimension de gym après un redémarrage serveur. Votre progression de gym a été perdue.
```

### 9b. Gel du serveur sur un accès concurrent

**Moyen.** La `LinkedHashMap` `PLAYER_GYMS` est remplacée par réflexion par une
`ConcurrentHashMap`. Une `HashMap` non synchronisée sous accès concurrent peut tourner
indéfiniment dans `get()`, ce qui se présente comme un serveur gelé et non comme une
exception.

### 9c. Mauvais badge et mauvais butin pour le type de gym

**Symptôme.** Deux joueurs entrent dans des types de gym différents en même temps et l'un
reçoit le badge de l'autre.

**Cause.** `GymTemplate` est un singleton à état statique mutable. Deux gyms simultanés se
battent dessus.

**Moyen.** La clé du type de gym d'origine est suivie par joueur, et le bon `GymTemplate`
est restauré avant la distribution du butin. Le gestionnaire s'abonne à l'évènement
`BATTLE_VICTORY` de Cobblemon en priorité `HIGHEST` — Rad Gyms s'abonne en `NORMAL`, donc
celui-ci passe d'abord. L'abonnement est construit par réflexion : la valeur de l'énum
`Priority` est résolue par nom et la méthode `subscribe(Priority, Function1)` est trouvée
en cherchant un `subscribe` à deux paramètres.

### 9d. NPE à l'interaction avec un dresseur de gym

**Symptôme.** Plus de 60 occurrences dans un seul log d'une NPE venant de
`Trainer.interactMob()`, typiquement après une déconnexion puis reconnexion dans une
dimension de gym.

**Cause.** `interactMob()` n'a aucune gestion d'erreur et déréférence une `GymInstance`
qui peut ne pas exister.

**Moyen.** Un `UseEntityCallback` pré-valide l'état et bloque l'interaction avec un message
au lieu de la laisser lever une exception — `cheesecompat.radgyms.no_gym` ou
`cheesecompat.radgyms.wrong_trainer` :

```text
Vous n'avez pas de session de gym active. Quittez la dimension et réessayez.
Ce dresseur n'appartient pas à votre session de gym.
```

**État.** Les quatre sont actifs.

---

## Non livré : la surcharge d'espèce Mewtwo

`data/cobblemon/species/generation1/mewtwo.json` est à la **racine du mod**, en dehors de
`src/main/resources/`, donc Gradle ne le met pas dans le jar. C'est un fichier de datapack
conservé à côté du code pour référence, pas quelque chose que ce mod applique. Si tu
attendais un correctif de resolver pour un Mewtwo shiny shadow en installant le jar, il
n'est pas dedans.

---

← [Catalogue — Battle Tower](fixes-battle-tower.fr.md) · [Catalogue — Safari, Academy et boss](fixes-safari-and-bosses.fr.md) →
