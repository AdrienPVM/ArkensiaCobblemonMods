[← CheeseCompatModsFix](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](fixes-daycare.md)

# Catalogue des correctifs — Daycare+

> ⚠️ Cette famille ne corrige pas des bugs. Elle réécrit les règles de reproduction
> compétitive de Daycare+ en d'autres règles, parce que le serveur voulait d'autres règles.
> Si tu installes ce jar en attendant que Daycare+ se comporte comme son auteur l'a
> documenté, ce ne sera pas le cas.

**Mod visé :** Daycare+ (`daycareplus`).
**Version visée à la compilation :** 1.1.0 (Fabric), id de version Modrinth `HqAFvLN2`.
**Aussi nécessaire à la compilation :** sgui 1.6.1+1.21.1 (Patbox), pour le mixin de GUI.
Il est livré en jar-in-jar dans Daycare+ ; le build le prend sur le Maven Nucleoid à la
place.
**Inerte en l'absence du mod :** oui. `CheeseCompatMixinPlugin` renvoie
`DAYCAREPLUS_LOADED` pour tout mixin dont le nom de classe contient `Daycare` ou
`Fertility`, et `DaycareSimpleModeStore` n'est relié aux évènements de cycle de vie que
derrière un `isModLoaded("daycareplus")` dans `CheeseCompatMod`.

Lis cette page en entier avant de changer un seul mixin. Huit d'entre eux touchent deux
méthodes — `PotentialPokemonProperties.setIVs` et `PastureExtension.produceEgg` — et le
résultat est le produit de tous, pas leur somme.

---

## Les règles d'IV, réécrites

Le mode « compétitif » de Daycare+ remplace l'héritage d'IV des jeux principaux par un
système de moyennes à lui, dans lequel les objets tenus cessent de compter. Le serveur
voulait l'inverse : le comportement des objets des jeux principaux **plus** la moyenne
comme repli au lieu de l'aléatoire. Quatre mixins y arrivent.

### 1. Le mode compétitif cesse de surcharger l'héritage

**Moyen.** `DaycareIVFixMixin` sur
`com.provismet.cobblemon.daycareplus.breeding.PotentialPokemonProperties`, deux `@Redirect`
sur des appels à `DaycarePlusOptions.doCompetitiveBreeding()` — un dans `setIVs`, un dans
`setNature` — tous deux forcés à renvoyer `false`.

Effet : le mode compétitif suit toujours la fertilité et consomme toujours les objets
tenus, mais le Destiny Knot, les objets Power et l'Everstone se comportent exactement comme
en reproduction standard.

**État.** Actif.

### 2. Idem, pour `fromParents`

**Moyen.** `DaycareFromParentsMixin` sur `PotentialPokemonProperties$PotentialIV`, un
`@Redirect` sur `doCompetitiveBreeding()` dans `fromParents`, forcé à `false`.

`fromParents` avait sa propre copie de la logique de moyennes, donc le correctif 1 seul la
laissait en place. Avec les deux, le chemin d'héritage standard est préservé : les objets
Power imposent leur statistique, `WILDCARD` pour le reste.

**État.** Actif.

### 3. Repli aléatoire remplacé par la moyenne des parents

**Moyen.** `DaycareIVAverageMixin` sur `PotentialPokemonProperties`, deux changements dans
`setIVs` :

- `@ModifyConstant` sur la constante `3`, qui renvoie `0`. Cette constante est le nombre
  par défaut de `forcedIVs`. Laissée à 3, la seconde boucle choisit toujours trois
  statistiques au hasard et copie la valeur exacte d'un parent — le résultat « min/max »
  dont le serveur ne voulait pas. La branche Destiny Knot pose `forcedIVs` à 5 avec
  `iconst_5`, une constante différente, donc porter un Destiny Knot hérite toujours de
  cinq statistiques.
- `@Redirect` sur le troisième appel à `IVs.set(Stat, int)` (`ordinal = 2`), qui est le
  repli aléatoire, et qui écrit `(primary + secondary) / 2` pour cette statistique à la
  place. Les deux parents sont lus via `@Shadow @Final private Pokemon primary` /
  `secondary`.

Le tableau combiné :

| Objets tenus | Résultat |
|---|---|
| aucun | 0 forcé, les 6 IV sont la moyenne |
| Destiny Knot | 5 hérités d'un parent au hasard, 1 en moyenne |
| objet Power | cette statistique forcée depuis le porteur, les autres en moyenne |
| objet Power + Destiny Knot | statistique forcée + 4 hérités + 1 en moyenne |

**État.** Actif.

⚠️ Un `@ModifyConstant` qui vise le littéral `3` n'est sans ambiguïté que tant que `setIVs`
contient exactement un `3`. Un refactoring en amont qui en introduit un second réécrira
silencieusement la mauvaise constante — et le symptôme sera des distributions d'IV
étranges, pas une erreur.

### 4. Consommation du Destiny Knot : écrite, jamais activée

**État : présent dans l'arbre source et NON enregistré.**

`DaycareDestinyKnotConsumeMixin` existe, est complet, et cible `PastureExtension.produceEgg`
en `TAIL` pour échanger le Destiny Knot de chaque parent contre `ItemStack.EMPTY` après la
production d'un œuf. Son raisonnement est solide : Daycare+ ne tague le Destiny Knot que
dans `noncompetitive_breeding`, donc la boucle de consommation intégrée — qui vérifie
`competitive_breeding` — le laisse intact, alors que le correctif 1 ci-dessus a rétabli le
transfert de cinq IV du Destiny Knot en mode compétitif. Dans les jeux principaux, il
serait consommé.

**Il n'est pas listé dans `cheesecompatmodsfix.mixins.json`.** Mixin n'applique que ce que
le fichier de config nomme, donc cette classe n'est jamais chargée et ne tourne jamais.
Rien dans le code ni dans l'historique ne dit si c'est volontaire ou un oubli ; la réponse
honnête est que l'intention n'est pas claire.

Ce qui **est** livré, c'est le tag de datapack correspondant,
`src/main/resources/data/daycareplus/tags/item/competitive_breeding.json` :

```json
{
  "replace": false,
  "values": [
    "cobblemon:destiny_knot"
  ]
}
```

Il ajoute le Destiny Knot au tag d'items `competitive_breeding` de Daycare+, ce qui fait que
la boucle de consommation **du mod lui-même** le prend en compte. Le comportement pour
lequel le mixin orphelin avait été écrit est donc obtenu par le tag à la place — ce qui
expliquerait pourquoi le mixin a été laissé de côté. Prends ça comme la lecture la plus
probable, pas comme une décision documentée.

---

## Fertilité

### 5. La fertilité peut devenir négative

**Symptôme.** La fertilité d'un Pokémon dérive sous zéro, ce qui casse ensuite toutes les
comparaisons qui supposaient que c'était impossible.

**Cause.** Deux voies : un serveur configuré avec
`allow_breeding_without_fertility = true`, ou une course qui laisse un parent à fertilité
nulle produire un œuf de plus.

**Moyen.** `FertilityClampMixin` sur
`com.provismet.cobblemon.daycareplus.feature.FertilityProperty` — un `@ModifyArg` sur le
second argument de l'appel `new IntSpeciesFeature(KEY, value)` dans `decrement()`, qui
remplace `get - 1` par `max(0, get - 1)`.

**État.** Actif.

### 6. Fertilité nulle ne peut jamais se reproduire, quoi qu'en dise la config

**Moyen.** `DaycareStrictFertilityMixin` sur `BreedingUtils` — `@Inject` en `HEAD` de
`isAllowedToBreed`, `cancellable`. Si la reproduction compétitive est active **et** que
`FertilityProperty.get(pokemon) <= 0`, il renvoie `false`.

Note la condition : elle est conditionnée à `doCompetitiveBreeding()` valant vrai. Sur un
serveur non compétitif, ce mixin ne fait rien, et
`allow_breeding_without_fertility = true` fonctionne toujours comme Daycare+ l'entend.

**État.** Actif.

### 7. L'œuf hérite d'un point de fertilité en moins que la moyenne des parents

**Symptôme visé.** Une lignée de reproduction doit décliner : chaque génération un point
moins fertile que la précédente.

**Cause du comportement d'origine.** `produceEgg` pose la fertilité de l'œuf à
`Math.min(parent1, parent2)`.

**Moyen.** `DaycareEggFertilityAvgMixin` — un `@Redirect` sur l'appel `Math.min(II)I` dans
`produceEgg`, qui renvoie `max(0, (a + b) / 2)`.

L'arithmétique vaut la peine d'être explicitée, parce que la formule dans le code ne
ressemble pas à la règle qu'elle implémente. `Math.min` est appelé *après* que
`FertilityProperty.decrement` a tourné sur les deux parents, donc les valeurs qui arrivent
sont déjà `pre1 - 1` et `pre2 - 1`. La règle voulue est `(pre1 + pre2) / 2 - 1`. En
substituant : `(a + 1 + b + 1) / 2 - 1 = (a + b) / 2`. D'où la simplification. Le plancher
à 0 ne peut se déclencher que si un parent était déjà à 0, ce qu'`isAllowedToBreed` empêche
(correctif 6).

**État.** Actif.

⚠️ Ce correctif dépend de l'*ordre* des opérations dans `produceEgg` — décrément d'abord,
`Math.min` ensuite. Si Daycare+ réordonne ces deux étapes un jour, la formule devient
silencieusement `(pre1 + pre2) / 2`, un point trop généreux, sans erreur nulle part.

---

## Reproduction ponctuelle

Les pâturages Daycare+ se reproduisent en continu jusqu'à épuisement de la fertilité. Le
serveur voulait un pâturage qui produit un œuf puis s'arrête, pour qu'un administrateur
puisse en distribuer un sans qu'il tourne sans fin.

### 8. Une bascule dans la GUI du pâturage

**Moyen.** `DaycareGUIToggleMixin` sur `com.provismet.cobblemon.daycareplus.gui.DaycareGUI`
— `@Inject` en `TAIL` de `create`, qui ajoute une horloge dans l'emplacement 5. Les
paramètres sont typés en `Object` plutôt qu'avec leurs vrais types Daycare+ et Cobblemon,
pour éviter de faire résoudre à javac des supertypes Minecraft intermédiaires
(`IMixinPastureBlockEntity` → `Inventory`) qui ne sont pas sur le classpath de compilation.

Cycle de clic, avec les libellés lus dans les fichiers de langue :

| État | Libellé | Le clic mène à |
|---|---|---|
| `AUTO` | Reproduction automatique | `SIMPLE_ARMED` |
| `SIMPLE_ARMED` | Reproduction ponctuelle (armée) | `AUTO` |
| `SIMPLE_DISARMED` | Reproduction ponctuelle (arrêtée) | `SIMPLE_ARMED` |

Infobulles :

```text
Reproduit en continu jusqu'à épuisement de la fertilité. Cliquer pour passer en mode ponctuel.
Produira un seul œuf puis s'arrêtera. Cliquer pour passer en mode automatique.
Œuf produit. Cliquer pour ré-armer et obtenir un nouvel œuf.
```

**État.** Actif.

### 9. Faire respecter la bascule

**Moyen.** `DaycareSimpleModeTickMixin` sur `PastureExtension`, deux injections :

- `@Redirect` sur l'appel `predictEgg()` dans `tick()`. Quand le pâturage est en mode
  ponctuel et non armé, il renvoie `Optional.empty()` au lieu d'appeler la méthode, donc
  aucun œuf n'est prédit et aucun n'est produit.
- `@Inject` en `TAIL` de `produceEgg`. Si le pâturage est en mode ponctuel, il le désarme
  et appelle `markDirty()`.

Les deux lisent le champ privé `blockEntity` par réflexion, pour la même raison que le
correctif 8 : importer `PokemonPastureBlockEntity` obligerait javac à résoudre `class_2586`.

### Où vit l'état de la bascule

`DaycareSimpleModeStore` le garde dans une table annexe, pas sur le block entity, indexé par
`(id de dimension, BlockPos en long)` et persisté dans
`config/cheesecompat_daycare.json`. Il est initialisé sur
`ServerLifecycleEvents.SERVER_STARTED` et vidé sur `SERVER_STOPPING`.

Les défauts sont `simpleMode = true`, `simpleArmed = true`, donc **un nouveau pâturage
produit un œuf puis s'arrête**. C'est l'inverse du défaut de Daycare+, et c'est la raison la
plus probable d'un signalement « mon pâturage a cessé de se reproduire » sur un serveur qui
a installé ce jar sans lire cette page.

Les méthodes du store prennent des parties de clé primitives (`String dimId`,
`long posLong`) plutôt que des types Minecraft, délibérément, pour qu'un appelant n'ait pas
à importer des classes `net.minecraft.*` que le montage Cobblemon en `compileOnly` ne peut
pas résoudre.

**Threading.** La map sous-jacente est une `ConcurrentHashMap`. Les lectures viennent du
thread serveur (le tick du pâturage) et du thread sur lequel sgui exécute le gestionnaire de
clic de la GUI.

---

← [Catalogue — Safari, Academy et boss](fixes-safari-and-bosses.fr.md) · [Catalogue — tout le reste](fixes-misc.fr.md) →
