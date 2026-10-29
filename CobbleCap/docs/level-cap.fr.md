[← CobbleCap](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](level-cap.md)

# La limite de niveau

C'est tout l'intérêt du mod. Un joueur possède un nombre, sa limite, et aucun Pokémon
qu'il possède ne peut la dépasser. Ce nombre ne bouge que lorsqu'il bat un champion. Tout le
reste de CobbleCap ne fait que décorer cette unique règle.

## Le modèle

| Objet | Vit dans | Contient |
|---|---|---|
| `PlayerCapData` | `config/cobblecap/players.json`, indexé par UUID | `capLevel`, `championsBeaten`, `challengeBeaten`, `expLocked`, les préférences de HUD, les horodatages de cooldown des défis |
| `ChampionEntry` | `config/cobblecap/champions.json`, un tableau **ordonné** | un identifiant de dresseur rctmod, un nom affiché, le `capGranted` à la victoire, et les conditions |
| `CapConfig` | `config/cobblecap/config.json` | `startingCap`, l'interrupteur général `enabled`, les espèces interdites, le webhook, les feux d'artifice |

L'entrée d'un joueur est créée à sa première connexion (`CapListener` appelle
`CapStorage.ensureExists`), avec `capLevel` au `startingCap` — **20** par défaut.

## Comment la limite est calculée

Il n'y a pas de formule. La limite est un entier stocké, et il change par exactement cinq
voies :

1. **Une victoire sur un champion.** `ChampionManager` résout le `capGranted` du champion et
   appelle `CapStorage.raiseCapLevel`. Cette méthode ne fait jamais que monter : si la
   limite du joueur est déjà égale ou supérieure à la nouvelle valeur, rien ne se passe et
   elle renvoie `false`. Battre les champions dans le désordre, ou en rebattre un, ne peut
   donc pas abaisser la limite de qui que ce soit.
2. **Une victoire en mode défi,** lorsque le `challengeCapGranted` de ce champion est
   supérieur à zéro — en montée seule là aussi.
   [Le système de champion](champions.fr.md) détaille ce chemin.
3. **Un admin qui la fixe** — `/cobblecap set <player> <level>`, ou
   `POST /api/v1/cobblecap/players/{uuid}/cap`. Les deux écrivent la valeur directement sans
   garde de montée seule, donc un admin peut abaisser une limite.
4. **Une réinitialisation** — `/cobblecap reset <player>` la ramène au `startingCap`.
5. **Une réparation** — `/cobblecap addbeaten <player> <championId>` appelle le même
   `raiseCapLevel` en montée seule avec le `capGranted` de ce champion, sans aucun combat.

L'ordre de `champions.json` **est** la progression. `ChampionConfig.nextUnbeaten` renvoie la
première entrée dont l'`id` n'est pas dans la liste `championsBeaten` du joueur, et cette
entrée est sa barrière actuelle : le champion dont le HUD affiche le nom en *Suivant*, et le
seul qu'il est autorisé à affronter.

## Comment la limite est imposée

Quatre couches indépendantes, parce que chacune attrape ce que les autres laissent passer.
Trois vivent sur le chemin de l'XP, une sur le chemin du combat.

### 1. Le hook d'événement Cobblemon — `CobblemonHook` + `CapManager`

`CobblemonHook.tryInit` s'exécute sur `SERVER_STARTED` et s'abonne au bus d'événements de
Cobblemon **entièrement par réflexion** : `Class.forName` sur
`com.cobblemon.mod.common.api.events.CobblemonEvents`, puis un `java.lang.reflect.Proxy`
implémentant `kotlin.jvm.functions.Function1` passé à la méthode `subscribe` de
l'observable. Aucune dépendance Cobblemon à la compilation n'intervient sur ce chemin.

Il essaie plusieurs noms de champ par événement et retient le premier qui existe :

| Rôle | Noms de champ essayés, dans l'ordre | Gestionnaire |
|---|---|---|
| XP de combat | `EXPERIENCE_GAINED_EVENT_PRE`, `EXP_GAINED`, `EXPERIENCE_GAINED` | `CapManager.handleXpEvent` |
| Bonbon exp | `EXPERIENCE_CANDY_USE_PRE`, `EXPERIENCE_CANDY_PRE` | `CapManager.handleXpEvent` |
| Début de combat | `BATTLE_STARTED_PRE`, `PRE_BATTLE_START`, `BATTLE_START_PRE` | le garde de combat intégré |
| Victoire | `BATTLE_VICTORY`, `BATTLE_WON` | `ChampionManager.handleBattleVictoryEvent` |

Chacun journalise ce à quoi il s'est lié, ou avertit qu'il n'a rien trouvé :

```text
[CobbleCap] Cobblemon XP hook registered on field: EXPERIENCE_GAINED_EVENT_PRE
[CobbleCap] Cobblemon candy XP hook registered on field: EXPERIENCE_CANDY_USE_PRE
[CobbleCap] Battle pre-check hook registered on field: BATTLE_STARTED_PRE
[CobbleCap] Battle victory hook registered on field: BATTLE_VICTORY
```

Un avertissement `Could not find … field` signifie que Cobblemon a renommé quelque chose et
que cette couche est hors service. C'est la ligne à chercher après chaque mise à jour de
Cobblemon.

`CapManager.handleXpEvent` fait ensuite sa propre réflexion sur l'objet d'événement :
extraire le Pokémon, résoudre le joueur propriétaire (depuis l'événement, sinon depuis le
Pokémon), lire le niveau, lire l'UUID du Pokémon pour la limitation de débit. Si le niveau
est **égal ou supérieur** à la limite — attention à la frontière, `currentLevel < cap` est
le seul cas passant — il annule. L'annulation elle-même suit deux stratégies : appeler
`cancel()`/`setCanceled(true)` sur l'événement et, à défaut d'une telle méthode, mettre la
quantité d'XP à `0` via `setExperience`/`setExpToAdd`/`setExp`/`setAmount`.

Chaque étape est encapsulée de telle sorte qu'un échec de réflexion journalise en debug et
**laisse passer l'XP**. Le mod échoue en mode ouvert, par conception : un renommage dans
Cobblemon dégrade la limite, il ne fait pas planter le serveur.

### 2. `PokemonAddExperienceMixin` — la couche de rognage

Celle-ci s'injecte dans `com.cobblemon.mod.common.pokemon.Pokemon` (à la compilation,
`remap = false`) en tête de `addExperienceWithPlayer` et `addExperience`. Son rôle n'est pas
de bloquer mais de **rogner**, pour qu'un joueur sous la limite ne voie jamais un message de
montée de niveau aussitôt annulé.

```java
int xpForCap  = pokemon.getExperienceGroup().getExperience(cap);
int headroom  = xpForCap - currentXp - 1;   // -1 garde le Pokémon à un point du niveau plafond
```

- À la limite ou au-delà, ou `headroom <= 0` : l'appel renvoie un
  `AddExperienceResult(lvl, lvl, Set.of(), 0)` synthétique. Zéro XP, aucun changement de
  niveau, aucun message.
- Sous la limite mais avec dépassement : la quantité est réduite à `headroom` et la vraie
  méthode est rappelée avec le nombre plus petit. Un garde de réentrance en `ThreadLocal`
  (`cobblecap$capping`) empêche ce second appel d'être rogné une nouvelle fois.
- Sinon elle ne fait rien et la quantité d'origine passe.

Le `-1` est la raison pour laquelle un Pokémon plafonné reste à *limite − 1* avec une barre
d'XP presque pleine, au lieu d'être pile à la limite. C'est volontaire : atterrir sur le
niveau plafond lui-même déclencherait le chemin de montée de niveau de Cobblemon.

### 3. `PokemonSetLevelMixin` — le filet de sécurité

Injection en tête de `Pokemon.setLevel(int)`, annulable. Elle attrape tout ce qui fixe un
niveau sans passer par l'XP du tout — le commentaire du mod cite le MasterCandyItem de
Cobblemon Utility+ comme le cas qui l'a motivée.

Les règles, dans l'ordre :

1. Si `CobbleCapMod.LEVEL_FORCE_ACTIVE` est vrai sur ce thread, retour immédiat. Ce
   `ThreadLocal<Boolean>` est une API publique pour les autres mods ; CheeseCompat le
   bascule pour que Battle Tower puisse forcer des niveaux au-delà de la limite.
2. `newLevel <= currentLevel` : retour. Les baisses sont toujours autorisées, pour
   l'administration et le débogage.
3. Le joueur propriétaire est résolu en **parcourant les méthodes par type de retour** :
   chaque méthode sans argument de la classe Pokémon dont le type de retour est assignable à
   `ServerPlayerEntity` est invoquée jusqu'à ce que l'une renvoie un joueur. Les noms ne sont
   pas utilisés, parce qu'à l'exécution un nom de méthode Yarn est un nom intermédiaire et ne
   correspondrait pas.
4. Blocage d'XP actif : l'XP est ramenée au niveau courant, on annule.
5. Système de limite activé et `newLevel > cap` : l'XP est ramenée à la limite, on annule.

`clampExperience` abaisse l'XP brute jusqu'à
`getExperienceGroup().getExperience(targetLevel)` via `setExperience$common`, par réflexion.
Sans cela, le total d'XP interne continue de grimper derrière un `setLevel` bloqué, et le
déclenchement d'XP suivant produit des messages de montée de niveau répétés et une barre
visuellement fausse.

### 4. `BATTLE_STARTED_PRE` — le contrôle d'équipe

Avant qu'un combat commence, le hook parcourt les acteurs et, pour tout acteur joueur :

- `ChampionManager.findBannedSpeciesInParty` — si quelque chose de `bannedSpecies` est dans
  l'équipe, le combat est annulé avec `cobblecap.msg.battle_cancelled_banned`.
- `ChampionManager.playerPartyViolatesCap` — si un membre de l'équipe dépasse la limite du
  joueur, le combat est annulé avec `cobblecap.msg.battle_cancelled_cap`.

La partie limite est ignorée quand `LEVEL_FORCE_ACTIVE` est posé. La partie espèces
interdites, non.

C'est ce même gestionnaire qui impose l'ordre des champions et le basculement vers le défi ;
voir [le système de champion](champions.fr.md#la-barrière-davant-combat).

## Ce que voit un joueur plafonné

Un message dans la barre d'action, limité à un par **30 secondes** et par couple (joueur,
Pokémon) grâce à `CapManager.XP_MSG_COOLDOWN_MS` — mais en pratique **uniquement quand un
bonbon exp est utilisé** sur un Pokémon déjà à la limite ou au-dessus. Cobblemon déclenche
`EXPERIENCE_GAINED_EVENT_PRE` depuis l'intérieur de `Pokemon.addExperience`, et la couche 2 a
déjà renvoyé zéro XP en tête de cette méthode pour un Pokémon plafonné : l'XP de combat est
donc refusée sans aucun message. L'événement des bonbons part de l'objet bonbon, avant tout
ajout d'XP, et atteint donc bien `CapManager`. Deux variantes, depuis
`assets/cobblecap/lang/fr_fr.json` :

```text
Pikachu ne peut pas gagner d'XP — limite de niveau : 45. Prochain champion : Erika
```

```text
Pikachu ne peut pas gagner d'XP — limite de niveau : 45. Tous les champions ont été vaincus !
```

La seconde apparaît quand `nextUnbeaten` ne renvoie rien. Le nom du Pokémon vient de
`getDisplayName()`, avec un repli sur le nom d'espèce dont la première lettre est mise en
majuscule, et enfin sur la chaîne littérale `your Pokemon` si les deux tentatives de
réflexion échouent. Voir `your Pokemon cannot gain XP` dans le chat est un signe fiable que
les getters de Cobblemon ont bougé.

En parallèle, [le HUD client](client.fr.md) affiche `Cap : 45` en permanence.

## Le blocage d'XP est un interrupteur séparé

`expLocked` sur `PlayerCapData` bloque **toute** l'XP de ce joueur, à n'importe quel niveau,
et il est vérifié *avant* le drapeau `enabled` dans les trois couches d'application.
Désactiver le système de limite avec `/cobblecap disable` ne libère pas un blocage d'XP.

Il est imposé de trois façons : `CapManager` annule l'événement et envoie
`cobblecap.msg.xp_locked`, `PokemonAddExperienceMixin` renvoie une limite effective de `0`
qui court-circuite vers un blocage total, et `PokemonSetLevelMixin` annule toute hausse.

Les joueurs le basculent eux-mêmes avec `/explock` ; les admins le posent pour quelqu'un
d'autre avec `/explock <player>` ou la route du dashboard. Voir
[commandes](commands.fr.md#explock).

## L'XP excédentaire est jetée, pas mise de côté

Il n'y a aucun réservoir. L'XP au-delà de la limite n'est stockée nulle part pour plus tard
— la couche de rognage réduit la quantité à `headroom` et le surplus n'est jamais ajouté, et
la couche de bridage réécrit activement le total d'XP du Pokémon *vers le bas*, jusqu'au
seuil. Quand une victoire relève la limite, les Pokémon reprennent là où ils s'étaient
arrêtés. Ils ne sautent pas plusieurs niveaux grâce à une expérience accumulée.

C'est une décision de conception, et elle mérite d'être annoncée aux joueurs, parce que
l'attente habituelle avec les autres mods de limite est l'inverse.

## Ce que `enabled: false` coupe

`/cobblecap disable` (et `/cobblecap enable`) bascule `CapConfig.enabled` et sauvegarde. Désactivé :

- l'application de la limite d'XP s'arrête sur les trois couches
- le gestionnaire `BATTLE_STARTED_PRE` retourne à sa première ligne, donc les contrôles
  d'espèces interdites et de limite d'équipe s'arrêtent aussi
- `UseEntityCallback` cesse d'intercepter les PNJ rctmod, donc les champions deviennent des
  dresseurs ordinaires
- la détection de victoire s'arrête, donc les limites cessent de monter

Ce qui continue de marcher : les blocages d'XP, [le sous-système de statues](statues.fr.md),
les chronos, les scoreboards et le dashboard. Ce drapeau est un interrupteur
XP-et-champions, pas un interrupteur de mod.

---

← [Prise en main](getting-started.fr.md) · [Le système de champion](champions.fr.md) →
