[← CheeseCompatModsFix](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](fixes-misc.md)

# Catalogue des correctifs — tout le reste

> ⚠️ Même avertissement que sur les autres pages du catalogue : chaque correctif est lié à
> une version d'un mod tiers, et rien ici n'est testé contre une autre.

Huit correctifs répartis sur six mods, plus l'unique fonctionnalité côté client qui ne soit
pas un mixin.

**Mods visés sur cette page**

| Mod | Id du mod | Version visée à la compilation |
|---|---|---|
| Legendary Monuments | `legendarymonuments` | 7.2, id Modrinth `oaBxuxgH` |
| Minerally | `minerally` | 1.21, id `wvK26f7w` |
| Enhanced Celestials | `enhancedcelestials` | 6.0.2.5, id `WU6cJeLI` (+ Data Anchor 2.0.0.12, `CpSbR8Mk`) |
| ExtraQuests | `extraquests` | 1.5.3, id `bbzqP178` |
| FTB Quests | `ftbquests` | `ftb-quests-fabric-2101.1.13.jar`, depuis `localdeps/` |
| JourneyMap | `journeymap` | 1.21.1-6.0.0-beta.47, id `oBV1MaLz` |
| Numismatic Overhaul | `numismatic-overhaul` | 0.3.4, id `DPXG6kTJ` |

---

## Legendary Monuments

Les quatre sont conditionnés à `legendarymonuments` dans `CheeseCompatMixinPlugin` — le
plugin filtre sur les noms de classes contenant `ArcPhone`, `Sanctuary` ou
`EntrepreneurProfessionLock`.

### 1. Protection de sanctuaire couvrant le monde entier

**Symptôme.** Le rayon de protection d'un bloc sanctuaire semble s'étendre à l'infini —
casser un bloc est refusé dans une autre dimension, ou à des dizaines de milliers de blocs.

**Cause.** `SanctuaryBlock.isWithinRadius()` calcule `dx * dx + dz * dz` en arithmétique
`int`. Au-delà d'environ 46 000 blocs, `dx * dx` déborde la plage des entiers signés et
devient négatif, ce qui est toujours `<= radiusSq`. Toute position lointaine se lit comme
étant à l'intérieur.

**Moyen.** `SanctuaryDistanceMixin` — `@Inject` en `HEAD` de `isWithinRadius`,
`cancellable`, `require = 0`, qui refait la comparaison en arithmétique `long` et renvoie la
bonne réponse.

**État.** Actif.

Un débordement d'entier d'école, et une bonne illustration de la raison pour laquelle il est
passé inaperçu : le bug n'apparaît qu'à une distance où personne ne teste, et quand il
apparaît le symptôme (« la protection s'applique là où elle ne devrait pas ») ne ressemble
pas à un problème d'arithmétique.

### 2. Soin via l'Arc Phone, désactivable

**Symptôme visé.** L'Arc Phone permet à n'importe quel joueur de soigner son équipe
entière n'importe où, ce qui retire tout intérêt aux Centres Pokémon.

**Moyen.** `ArcPhoneHealMixin` sur `HealPacket` — `@Inject` en `HEAD` de
`lambda$registerServerReceiver$1`, `cancellable`, `require = 0`. Quand
`arcPhoneHealEnabled` vaut `false` dans la config, le gestionnaire annule et envoie
`cheesecompat.arcphone.heal_disabled` :

```text
Le soin via l'Arc Phone est désactivé sur ce serveur.
```

**État.** Actif, et **activé** par défaut (`arcPhoneHealEnabled = true`). Voir
[configuration](configuration.fr.md).

### 3. Accès au PC via l'Arc Phone, désactivable

**Moyen.** `ArcPhonePCMixin` sur `PCOpenPacket`, structurellement identique au correctif 2 :
`@Inject` en `HEAD` de `lambda$registerServerReceiver$1`, `cancellable`, `require = 0`,
conditionné à `arcPhonePCEnabled`. Message `cheesecompat.arcphone.pc_disabled` :

```text
L'accès au PC via l'Arc Phone est désactivé sur ce serveur.
```

**État.** Actif, activé par défaut.

Ces deux correctifs injectent dans une **lambda**, nommée
`lambda$registerServerReceiver$1`. Les noms de lambdas sont attribués par le compilateur
dans l'ordre du source et ne font partie d'aucune API. Un réordonnancement des lambdas dans
cette méthode — même sans changement de comportement — renomme la cible et l'injection cesse
de correspondre. `require = 0` fait que cet échec est silencieux : la bascule de l'Arc Phone
cesse discrètement de fonctionner. Si tu poses une bascule à `false` et que rien ne change,
c'est la première chose à soupçonner.

### 4. Villageois entrepreneurs qui changent de métier

**Symptôme.** Le PNJ entrepreneur perd son métier et en reprend un autre — un villageois
qui était commerçant hier est fermier aujourd'hui.

**Cause.** L'entrepreneur est un `minecraft:villager` vanilla portant la profession custom
`legendarymonuments:entrepreneur`. Son poste de travail
(`legendarymonuments:suitcase_block`, POI `entrepreneur_poi`) est ajouté au tag vanilla
`acquirable_job_site`. Le comportement vanilla s'applique alors : un villageois jamais
verrouillé par le commerce, et qui ne voit plus son poste réclamé à portée, abandonne sa
profession et en prend n'importe quelle autre à proximité.

**Moyen.** `EntrepreneurProfessionLockMixin` sur la classe **vanilla** `VillagerEntity` —
`@Inject` en `HEAD` de `setVillagerData`, `cancellable`, qui annule tout appel qui ferait
*quitter* la profession entrepreneur à un villageois qui l'a déjà. L'acquérir fonctionne
toujours (`NONE` → entrepreneur passe), l'IA et le commerce ne sont pas touchés, et les
villageois non entrepreneurs ne sont pas affectés.

**État.** Actif. Il cible une classe vanilla, donc ce mixin s'applique à tous les
villageois du serveur dès que Legendary Monuments est installé ; la vérification de
profession est la première chose qu'il fait.

---

## Minerally

### 5. Crash quand un marteau casse un amas de minerai

**Symptôme.** Crash serveur — NPE — quand JustHammers casse un `OrePile` de Minerally dans
le cadre de son minage en zone.

**Cause.** JustHammers appelle `onBroken()` pour les blocs en zone sans appeler `onBreak()`
d'abord, donc `OrePile._player` est encore null quand `onBroken` le déréférence.

**Moyen.** `OrePileMixin`, `@Mixin(targets = "theone_ss.minerally.block.OrePile")` —
`@Inject` en `HEAD` de `onBroken`, `cancellable`, qui annule quand le champ joueur est null.
L'amas ne produit pas son butin dépendant du joueur dans ce cas ; il ne met pas non plus le
serveur à terre.

**Inerte en l'absence du mod.** Oui — conditionné à `minerally`.

**État.** Actif.

C'est le mixin cité dans [`localdeps/README.md`](../../localdeps/README.md) comme raison
pour laquelle un mixin désigné par chaîne a quand même besoin de son jar à la compilation :
le processeur d'annotations Mixin résout et valide chaque `targets` pendant la compilation et
échoue avec `Mixin target ... could not be found` si la classe est absente. Minerally est
donc sur le classpath de compilation même si aucune ligne de code ne l'importe.

---

## Enhanced Celestials

Deux correctifs, même problème de fond : les dimensions créées au runtime par
[CheeseGuard](../../CheeseGuard/) n'ont jamais de donnée lunaire.

### 6. Réglages lunaires hérités par les dimensions `cheeseguard:*`

**Symptôme.** Une dimension CheeseGuard n'a jamais d'évènement lunaire. Le bloc Lunar
Forecast Hologram de StarAcademy qui s'y trouve n'affiche rien.

**Cause.** `EnhancedCelestialsLunarForecastWorldData.factory()` cherche la dimension dans le
registre `enhancedcelestials:lunar/dimension_settings`. Une dimension créée au runtime n'y a
pas d'entrée, la recherche renvoie `Optional.empty()`, la factory renvoie null.

**Moyen.** `EnhancedCelestialsFactoryFallbackMixin` sur
`dev.corgitaco.enhancedcelestials.lunarevent.EnhancedCelestialsLunarForecastWorldData` — un
`@Redirect` sur l'unique appel à `Registry.getEntry(RegistryKey)` dans `factory()`. Si
l'espace de noms de la dimension est `cheeseguard` et qu'elle n'a pas d'entrée propre, la
redirection renvoie les réglages de `cheeseguard:arkensia` à la place. Chaque dimension
CheeseGuard suit alors le même cycle d'évènements cosmiques que la principale, ce que
voulait l'administrateur.

**Inerte en l'absence du mod.** Oui — conditionné à `enhancedcelestials`.

**État.** Actif.

Data Anchor est une dépendance de compilation et rien d'autre : il fournit
`SyncedLevelTrackedData`, la superclasse de la cible du mixin. Il n'est pas corrigé.

### 7. Forcer la donnée lunaire à se matérialiser

**Symptôme.** Même avec le correctif 6, une dimension rarement visitée n'a toujours pas de
donnée lunaire.

**Cause.** Enhanced Celestials matérialise sa donnée par niveau de façon **paresseuse**. La
factory ne tourne qu'au premier appel à `EnhancedCelestials.lunarForecastWorldData(level)`,
et cet appel ne vient que de `LivingEntity#tick`, du spawner naturel de mobs et de quelques
mixins de bloc et de slime. Tant qu'un joueur n'a pas visité la dimension *ou* qu'un mob n'y
a pas tické, la dimension n'existe tout simplement pas du point de vue d'Enhanced
Celestials.

**Moyen.** `EnhancedCelestialsWarmup` — pas un mixin. Il résout
`EnhancedCelestials.lunarForecastWorldData(World)` par réflexion une fois à l'init, puis
l'appelle sur `ServerWorldEvents.LOAD` pour **chaque** niveau que le serveur charge. La
valeur renvoyée est jetée ; l'appel n'existe que pour amorcer la factory.

**Inerte en l'absence du mod.** Oui, deux fois : `register()` rend la main immédiatement si
`enhancedcelestials` n'est pas chargé, et une signature qui ne correspond pas lors de la
résolution par réflexion journalise un avertissement et désactive l'amorçage au lieu de
lever une exception. Un échec par dimension n'est journalisé qu'en `DEBUG`, parce qu'une
dimension sans `dimension_settings` renvoie légitimement null.

Lignes de log :

```text
[CheeseCompat] EnhancedCelestials lunar warmup registered
[CheeseCompat] Warmed lunar data for cheeseguard:arkensia
```

**État.** Actif. Note qu'il est enregistré **inconditionnellement** dans `onInitialize` —
la vérification du mod vit à l'intérieur de `register()`, pas autour de l'appel.

---

## ExtraQuests et FTB Quests

### 8. Des quêtes quotidiennes qui ne reset jamais

**Symptôme.** Les quêtes quotidiennes à 8 h de certains joueurs cessent de se reset. La
quête reste verrouillée en réclamation jusqu'à une intervention manuelle d'administrateur.
Les autres joueurs du même serveur vont bien.

**Cause.** Une course au démarrage, et une course destructrice. `ExtraQuests.loadStorage`
et `ServerQuestFile.INSTANCE` sont tous deux initialisés sur
`ServerLifecycleEvents.SERVER_STARTED`, et Fabric ne garantit pas l'ordre des listeners. Si
ExtraQuests passe en premier, `ServerQuestFile.INSTANCE` est encore null.
`Utils.questReset` déréférence alors null ou obtient null pour la quête, journalise une
erreur — **et retire l'entrée de `TimerStorage.json` sans avoir reset la quête**. Le timer
a disparu, donc rien ne réessaiera jamais.

**Moyen.** `ExtraQuestsTimerResetGuardMixin` sur `Utils` — `@Inject` en `HEAD` de
`questReset`, `cancellable`. Si `ServerQuestFile.INSTANCE` est null, il renvoie `false`
**sans** toucher au stockage. Au prochain tick d'ExtraQuests, ou au pire au prochain
redémarrage, FTB est prêt et le reset réussit.

Une méthode statique, un retour anticipé. Tout le correctif est « échouer sans détruire les
preuves ».

La journalisation, ajoutée en 1.2.39, est construite pour le diagnostic : un `WARN` à chaque
blocage, limité aux cinq premiers puis un sur cinquante, plus un `INFO` la première fois
qu'il bloque et un la première fois que FTB est de nouveau prêt. Ça te dit si la course se
produit réellement sur ton serveur, et à quelle fréquence.

**Inerte en l'absence du mod.** Oui — conditionné à `extraquests`. FTB Quests lui-même
n'est qu'une dépendance de compilation, nécessaire parce que le mixin lit
`ServerQuestFile`.

**État.** Actif.

---

## JourneyMap

### 9. Icônes de minimap de fakemons rendues depuis la mauvaise texture

**Symptôme.** Un fakemon du pack Seavl's Bizarremons — Vaporipotent, Clockage, Dragash et
les autres — apparaît sur la minimap JourneyMap comme une icône floue et manifestement
fausse. Les espèces de Cobblemon elles-mêmes sont correctes.

**Cause.** `EntityHelper.getIconData(Entity)` résout l'icône d'un Pokémon depuis
`RenderFacade.getEntityTexture(...)`, qui pour Cobblemon renvoie la texture de variation
d'espèce — par exemple
`seavlbizarremon:textures/pokemon/vaporipotent/vaporipotent.png`. JourneyMap transforme
ensuite ce chemin en chemin d'icône, mais la transformation est **codée en dur pour
l'espace de noms `cobblemon`** : le bytecode décompilé d'`EntityHelper`, offsets 613-649,
montre une garde `.equals("cobblemon")` avant l'appel
`.replace("pokemon", "entity_icon")`. Pour tout autre espace de noms, la texture de modèle
en pleine résolution est utilisée telle quelle comme icône de minimap.

**Moyen.** Deux mixins côté client uniquement :

- `client.IconDataAccessor` — un accesseur pour le champ package-privé
  `EntityHelper.IconData.icon`.
- `client.JourneyMapSeavlianIconMixin` — `@Inject` en `RETURN` de `getIconData`,
  `require = 1`. Il lit l'`IconData.icon` renvoyé ; si l'espace de noms est dans
  `FAKEMON_NAMESPACES` (actuellement `seavlbizarremon`) et que le chemin contient
  `/pokemon/`, il réécrit l'icône en `<namespace>:textures/entity_icon/<reste>.png`.

Après le `RETURN` plutôt qu'en ligne, volontairement : `getIconData` pose aussi
`IconData.type` et `.texture` et exécute d'autres logiques qu'on veut garder, donc
post-traiter le résultat survit mieux aux refactorings internes de JourneyMap que remplacer
la méthode.

Les icônes doivent réellement exister à
`assets/<namespace>/textures/entity_icon/...`. Sur ce serveur elles viennent du resourcepack
dCIM. Ajouter un autre pack de fakemons implique d'ajouter son espace de noms à
`FAKEMON_NAMESPACES` **et** de fournir les icônes.

**Inerte en l'absence du mod.** Oui — les deux sont conditionnés à `journeymap` dans le
plugin mixin.

**État.** Actif.

C'est le seul mixin du mod avec `require = 1`, c'est-à-dire qu'une injection échouée est une
erreur et non un saut silencieux. Sur un client, cela veut dire un échec dur au démarrage
plutôt qu'une icône fausse.

⚠️ JourneyMap est épinglé en `6.0.0-beta.47` et non en version `6.0.0`, pour une raison qui
n'a rien à voir avec le correctif : le jar de la release a été construit avec Loom 1.14.10,
que le Loom 1.10.5 de ce projet refuse de lire.

---

## Numismatic Overhaul

### 10. Le rendu des blocs boutique coûte des images par seconde

**Symptôme.** Chute du framerate dans une zone de marché dense où beaucoup de blocs
boutique Numismatic sont en vue.

**Cause.** `ShopBlockEntityRender.render()` émet un `ItemRenderer.renderItem` complet — un
modèle 3D en rotation — pour chaque boutique visible, à chaque image, sans cull de distance
personnalisé. La coupure vanilla par défaut est de 64 blocs, donc ça fait O(N) appels à
`renderItem` par image à travers un marché.

**Moyen.** `client.NumismaticShopCullMixin` — `@Inject` en `HEAD` de `render`,
`cancellable`, qui annule au-delà d'une distance fixe (environ 24 blocs) de la caméra. Le
modèle statique du bloc boutique est toujours dessiné par le mesh du chunk ; seul l'item
flottant du block entity renderer est sauté. Toute exception retombe sur le rendu d'origine,
donc le pire cas est le comportement actuel.

**Inerte en l'absence du mod.** Oui — conditionné à `numismatic-overhaul`.

**État.** Actif. Côté client uniquement, purement visuel.

---

## L'activateur de packs de ressources client

Pas un correctif pour un mod tiers, mais la seule pièce de comportement côté client de ce
jar qui ne soit pas un mixin : sa place est donc ici.

**Problème.** CurseForge n'applique pas l'`options.txt` des overrides de modpack, donc un
nouveau joueur démarre avec tous les packs de ressources Arkensia désactivés, et le modpack
ne ressemble en rien à ce qu'il devrait être.

**Moyen.** `CheeseCompatClient` implémente `ClientModInitializer` et est enregistré comme
entrypoint `client` dans `src/main/resources/fabric.mod.json` :

```json
"entrypoints": {
  "main": ["com.cheesecompat.CheeseCompatMod"],
  "client": ["com.cheesecompat.CheeseCompatClient"]
}
```

Sur `CLIENT_STARTED`, il vérifie si `file/CacademyMons` est déjà dans la liste des packs
activés, et rend la main immédiatement si c'est le cas — cette unique vérification est ce
qui en fait une mise en place unique plutôt que quelque chose qui combat les choix du joueur
à chaque lancement. Sinon il lit la liste ordonnée des packs depuis
`config/resourcepackoverrides.json` (synchronisé depuis le serveur par AutoModpack, ce qui
garde le serveur comme source unique de vérité), retombe sur une liste codée en dur d'une
cinquantaine de packs si ce fichier est absent, rescanne le dossier des packs, les active via
le `ResourcePackManager`, resynchronise l'état du manager dans les options, les écrit, et
recharge les ressources.

Il journalise ce qu'il a fait et — plus utile — ce qu'il n'a pas pu faire :

```text
[CheeseCompat] Arkensia resource packs not configured — activating...
[CheeseCompat] Loaded 55 packs from resourcepackoverrides.json
[CheeseCompat] N packs not found: [...]
[CheeseCompat] 54 resource packs activated (of 55 requested).
```

**État.** Actif, côté client uniquement.

⚠️ C'est le seul code du mod qui réécrit le fichier de réglages d'un joueur et qui force un
rechargement des ressources au démarrage. Il est gardé par un unique nom de pack sentinelle.
Si ce pack est un jour renommé dans le modpack, la garde cesse de correspondre et chaque
client rejoue toute l'activation à chaque lancement, en écrasant tout choix de pack que le
joueur aurait fait depuis. La sentinelle est `file/CacademyMons`, codée en dur dans
`CheeseCompatClient`.

La liste de repli codée en dur est elle aussi spécifique à Arkensia jusqu'aux noms de
fichiers zip individuels, et ne sert à rien sur un autre modpack.

---

← [Catalogue — Daycare+](fixes-daycare.fr.md) · [Architecture](architecture.fr.md) →
