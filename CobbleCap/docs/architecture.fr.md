[← CobbleCap](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](architecture.md)

# Architecture

52 fichiers source Java, environ 11 740 lignes. Deux de ces fichiers dépassent 800 lignes et
deux autres dépassent 1 000, et c'est la première chose à savoir : la logique est concentrée
dans une poignée de gros managers plutôt que diluée.

## Arborescence des packages

```text
com.cobblecap
├── CobbleCapMod.java            point d'entrée : charge le stockage, enregistre les événements, possède chaque manager
├── client/                      client uniquement — HUD, /pasturehide, aides aux statues
│   ├── CobbleCapClient          ClientModInitializer : récepteurs de payload, HUD, commande
│   ├── CapHudRenderer           le badge de limite
│   ├── PastureHideClient        la bascule, son fichier .properties, le cache des UUID attachés
│   ├── PastureHideCommand       l'arbre Brigadier côté client
│   ├── StatueStyleClient        UUID de doublure → StatueStyle → Identifier de texture
│   └── StatueFreezeGuard        ThreadLocal brisant la récursion entre deux mixins de modèle
├── commands/                    arbres Brigadier
│   ├── CobbleCapCommand         racine /cobblecap, sous-commandes limite/joueur/config (843 L)
│   ├── StatueCommand            sous-arbres statue, chrono et champion (822 L)
│   └── ExpLockCommand           /explock
├── dashboard/                   intégration CheeseDash
│   ├── CobbleCapDashboardModule préfixe, métadonnées de navigation, garde de disponibilité
│   └── CobbleCapApi             le dispatcher REST (376 L)
├── data/                        porteurs d'état et leur persistance
│   ├── CapConfig                config/cobblecap/config.json
│   ├── CapStorage               config/cobblecap/players.json, écriture différée
│   ├── PlayerCapData            l'entrée d'un joueur
│   ├── ChampionConfig           config/cobblecap/champions.json, liste ordonnée
│   ├── ChampionEntry            un champion — record immuable
│   ├── LegendsStorage           config/cobblecap_legends.json
│   ├── LegendEntry              une ligne de Panthéon + TeamMember + Mode
│   ├── TenureStorage            config/cobblecap_tenures.json
│   ├── Tenure                   un règne compétitif + FailRecord
│   ├── ChampionNpcState         la liaison du repli par NPC
│   └── StatueStyle              l'énumération de 13 styles
├── logic/                       tout ce qui agit
│   ├── CapManager               gestion des événements d'XP, le message de blocage, aides de réflexion Cobblemon
│   ├── CapListener              cycle de vie connexion/arrêt, UseEntityCallback sur les PNJ rctmod
│   ├── CobblemonHook            abonnement réflexif aux événements + le gestionnaire BATTLE_STARTED_PRE
│   ├── ChampionManager          conditions, victoire, promotion, instantanés d'équipe (1 147 L)
│   ├── ChronoManager            les chronos
│   ├── Conseil4Boards           4 scoreboards + 4 exports JSON
│   ├── StatueManager            dioramas, doublures, ancres, tick de style (1 422 L)
│   ├── ChampionDoppelganger     une doublure de faux joueur
│   ├── RegisDoppelgangerManager doublure de skin en arène pour le dresseur final
│   ├── MojangProfileFetcher     résolution de GameProfile à 4 niveaux
│   ├── RctTrainerMorpher        la réécriture du datapack + la file
│   ├── RctReloadHook            réflexion vers le TrainerManager.loadTrainers() de rctmod
│   ├── RegisModeManager         basculement classique/compétitif du JSON actif
│   ├── ChampionNpcManager       le morph de repli par NPC Cobblemon
│   ├── Conseil4TrainerCloner    clones de dresseur par dimension — DÉSACTIVÉ (548 L)
│   ├── FireworksManager         la célébration, CheeseGuard par réflexion
│   ├── CobbleCapWebhook         publications Discord
│   ├── PastureTracker           autorité serveur des pâturages, parcours toutes les 2 s
│   └── SyntheticUuidRegistry    l'ensemble des UUID de doublure, partagé avec les mixins client
├── mixin/
│   ├── PokemonSetLevelMixin     le filet de sécurité
│   ├── PokemonAddExperienceMixin la couche de rognage
│   └── client/                  5 mixins client — masquage de pâturage, 3 gels de pose, 1 échange de texture
└── network/                     3 payloads S2C : CapSyncPayload, PastureSyncPayload, StatueStyleSyncPayload
```

## Qui possède quoi

`CobbleCapMod.INSTANCE` est un singleton statique public posé dans `onInitialize`, et chaque
manager y pend en champ `public final` avec un getter. Il n'y a ni injection de dépendances
ni localisateur de services — `CobbleCapMod.INSTANCE.getCapStorage()` est l'idiome partout,
y compris depuis des méthodes statiques et depuis les mixins.

| Propriétaire | Possède |
|---|---|
| `CapConfig` | les réglages, et se sauvegarde à chaque setter |
| `CapStorage` | la table `UUID → PlayerCapData` ; le seul auteur de `players.json` |
| `ChampionConfig` | la liste ordonnée des champions et `nextUnbeaten` |
| `LegendsStorage` | le Panthéon, entrées de test incluses |
| `TenureStorage` | les règnes compétitifs ; au plus un ouvert |
| `ChronoManager` | les chronos en cours |
| `StatueManager` | les entités de diorama, les doublures, les ancres, le tick de style |
| `RctTrainerMorpher` | le morph en attente et les fichiers du datapack |
| `ChampionNpcState` | la liaison du NPC de repli |

`CapManager`, `ChampionManager`, `Conseil4Boards`, `FireworksManager`, `PastureTracker`,
`RctReloadHook`, `RegisModeManager` et `SyntheticUuidRegistry` sont tous **statiques** —
aides sans état, ou état statique mutable sans instance.

## Persistance

| Fichier | Écrit quand | Atomique |
|---|---|---|
| `cobblecap/config.json` | chaque setter, et le premier démarrage | non |
| `cobblecap/champions.json` | `setChampions` (`PUT` du dashboard) | non |
| `cobblecap/players.json` | différé de 500 ms après un changement, plus `SERVER_STOPPING` | **oui** |
| `cobblecap_legends.json` | chaque ajout, remplacement ou suppression | **oui** |
| `cobblecap_tenures.json` | chaque changement de règne et chaque échec | non |
| `cobblecap_chronos.json` | chaque démarrage, arrêt, annulation | **oui** |
| `cobblecap_dioramas.json` | chaque changement d'ancre | **oui** |
| `cobblecheesescore_external_conseil4_*.json` | chaque reconstruction de tableau | **oui** |
| `cobblecap_client.properties` | chaque changement de `/pasturehide` | non |

« Atomique » signifie un voisin `.tmp` et un `Files.move`, en privilégiant `ATOMIC_MOVE` et
en se rabattant sur un remplacement simple. Quatre fichiers ne le font pas, et
`cobblecap_tenures.json` est celui qui compte : il contient le champion courant et il est
écrit directement en place, donc un crash en pleine écriture peut le tronquer.

`players.json` est le seul écrivain différé. `CapStorage.saveAsync` bascule un
`AtomicBoolean` et planifie une sauvegarde complète 500 ms plus tard sur un thread démon
nommé `CobbleCap-Save`, condensant une rafale en une écriture. La fenêtre pendant laquelle un
crash brutal perd des données est donc d'une demi-seconde — une hausse de limite,
réalistement.

## Threading

| Thread | Ce qui y tourne |
|---|---|
| **thread serveur** | chaque commande, chaque gestionnaire d'événement Cobblemon, les deux corps de tick, tout le travail d'entités, `RctReloadHook` |
| **thread HTTP** (CheeseDash) | les gestionnaires de `CobbleCapApi` — lecture directe, les mutations sautent vers le thread serveur |
| **thread netty** (client) | la réception des payloads, immédiatement différée avec `context.client().execute(...)` |
| **thread de rendu client** | `CapHudRenderer.render`, les quatre mixins client |
| démon `CobbleCap-Save` | les écritures différées de `CapStorage` |
| `cobblecap-tenures-save` | un `new Thread` par appel à `TenureStorage.saveAsync` |
| le thread des feux d'artifice | un `new Thread` par spectacle, qui dort entre les fusées |

Les points de passage qui comptent :

- **HTTP → serveur.** `CobbleCapApi` enveloppe chaque mutation dans
  `WebDashboard.executeOnServerThread(server, …)` : `handleSetCap`, `handleToggleExpLock` et
  `handleFireworksTest`. Les `PUT` de config et de champions ne sautent **pas**, parce qu'ils
  ne touchent que de l'état Java simple et écrivent un fichier — défendable, même si
  `ChampionConfig.setChampions` remplace une liste que le thread serveur lit à chaque combat,
  sans synchronisation.
- **thread des feux → serveur.** Chaque fusée est un `server.execute(...)` ; seul le `sleep`
  est hors thread.
- **netty → client.** Chaque corps de récepteur est à l'intérieur de
  `context.client().execute(...)`.

Primitives de concurrence utilisées : `ConcurrentHashMap` pour la table des joueurs, les
limiteurs de messages, la table des doublures et les ensembles de pâturage ;
`CopyOnWriteArrayList` pour les légendes et les règnes ; `AtomicReference` pour le morph en
attente ; `AtomicBoolean` pour le drapeau de sauvegarde et le garde des feux d'artifice ;
`ReentrantLock` pour le basculement de mode de Régis ; `synchronized` sur `save()` dans
plusieurs stockages ; et trois gardes `ThreadLocal<Boolean>` — `LEVEL_FORCE_ACTIVE`,
`cobblecap$capping` et `StatueFreezeGuard`.

### Les deux corps de tick

`END_SERVER_TICK` lance trois choses, chacune avec sa propre limitation interne :

| Appelé | Limitation | Coût |
|---|---|---|
| `StatueManager.tick()` | toutes les 10 ticks | parcourt **toutes les entités de tous les mondes chargés** ; auto-réparation des doublures toutes les 60 ticks |
| `RctTrainerMorpher.tick(server)` | toutes les 20 ticks | une seule lecture d'`AtomicReference`, presque toujours nulle |
| `PastureTracker.tick(server)` | toutes les 40 ticks | parcourt **toutes les entités de tous les mondes chargés**, avec de la réflexion par Pokémon |

Deux parcours complets d'entités sur un tick partagé sont le coût permanent principal du
mod. Le test d'étiquette dans `StatueManager` et le test de nom de classe dans
`PastureTracker` viennent tous deux en premier, donc le coût par entité est faible, mais le
parcours lui-même n'est pas gratuit sur un gros monde.

## La stratégie de réflexion

Rien dans `logic/` n'importe Cobblemon. Tout passe par `Class.forName` et un parcours de
méthodes, pour une raison explicitement donnée : à l'exécution, un nom de méthode Yarn est un
nom *intermédiaire*, donc `getDeclaredMethod("getOwnerPlayer")` ne correspondrait pas.

Trois motifs reviennent :

1. **Candidats de noms.** `CapManager.invokeGetter(target, "getLevel", "level")` essaie
   chaque nom contre `getMethods()` et `getDeclaredMethods()`.
2. **Parcours par signature.** `PokemonSetLevelMixin` trouve le joueur propriétaire en
   cherchant toute méthode sans argument dont le type de retour est assignable à
   `ServerPlayerEntity`. `RctTrainerMorpher.countActiveBattles` trouve la table des combats
   en prenant le premier champ déclaré de type `Map`.
3. **Un proxy Kotlin `Function1`.** `CobblemonHook.hookEvent` construit un
   `java.lang.reflect.Proxy` implémentant `kotlin.jvm.functions.Function1`, gère lui-même
   `invoke`/`toString`/`hashCode`/`equals`, et le passe au `subscribe` de l'observable — en
   essayant d'abord la surcharge `(Priority, Function1)` puis en se rabattant sur
   `(Function1)`.

Les deux mixins sont l'exception : ils font `@Mixin(Pokemon.class, remap = false)` avec une
dépendance Cobblemon `compileOnly` à la compilation, parce qu'un mixin a besoin d'une vraie
classe cible.

### Échec ouvert et échec fermé

La politique n'est pas uniforme, et les asymétries sont délibérées :

| Chemin | En cas d'échec | Conséquence |
|---|---|---|
| `CapManager.handleXpEvent` | **ouvert** — XP autorisée | un renommage Cobblemon dégrade la limite au lieu de faire planter |
| les deux mixins | **ouvert** | idem |
| `ChampionManager.handlePreBattle` sans entrée joueur | **ouvert** | le combat se poursuit |
| `isFtbQuestCompleted` | **ouvert** — renvoie `true` | l'absence de FTB Quests ne bloque jamais un combat |
| `playerPartyViolatesCap` | **ouvert** — renvoie `false` | le combat n'est pas annulé |
| `findBannedSpeciesInParty` | **ouvert** — liste vide | une réflexion d'équipe cassée désactive silencieusement la liste d'interdiction |
| `getPokedexCount` | **fermé** — renvoie `0` | une condition de Pokédex devient infranchissable |
| `countActiveBattles` | **fermé** — renvoie `−1` | documenté, mais plus appelé |
| le clonage d'équipe pour le NPC | **fermé** — emplacement ignoré | ne jamais partager une référence vivante de Pokémon |

De tous ceux-là, `getPokedexCount` est celui à retenir : c'est le seul échec de réflexion qui
*bloque* les joueurs au lieu de les laisser passer.

## Mixins

```json
"mixins": ["PokemonSetLevelMixin", "PokemonAddExperienceMixin"],
"client": ["client.EntityRenderDispatcherMixin", "client.BipedEntityModelMixin",
           "client.PlayerEntityModelMixin", "client.BipedAnimateModelMixin",
           "client.PlayerEntityRendererMixin"]
```

| Mixin | Cible | Pourquoi |
|---|---|---|
| `PokemonSetLevelMixin` | `Pokemon.setLevel(int)` HEAD, annulable | attrape les poses de niveau qui contournent entièrement l'XP — le cas motivant était le MasterCandyItem de Cobblemon Utility+ |
| `PokemonAddExperienceMixin` | `Pokemon.addExperienceWithPlayer` et `addExperience` HEAD, annulables | rogne l'XP jusqu'à la marge sous la limite pour qu'aucun message de montée de niveau ne se déclenche puis soit annulé |
| `EntityRenderDispatcherMixin` | `EntityRenderDispatcher.render(...)` HEAD | `/pasturehide` — annule le dessin d'un Pokémon attaché |
| `BipedAnimateModelMixin` | `BipedEntityModel.animateModel(LivingEntity,FFF)` HEAD | annule la passe de pré-pose, pour qu'une statue ne reprenne pas la pose d'un nageur voisin |
| `BipedEntityModelMixin` | `BipedEntityModel.setAngles(LivingEntity,FFFFF)` HEAD | réinvoque avec les mouvements à zéro — pas de respiration, pas de balancement |
| `PlayerEntityModelMixin` | `PlayerEntityModel.setAngles(...)` HEAD | la surcharge joueur anime manches et oreilles avant d'appeler `super`, donc le mixin parent arrive trop tard |
| `PlayerEntityRendererMixin` | `PlayerEntityRenderer.getTexture(Entity)` HEAD | renvoie le PNG de statue précalculé pour les styles autres que `natural` |

`injectors.defaultRequire` vaut `1`, donc une signature qui cesse de correspondre est un
**échec de démarrage dur**, pas un saut silencieux. C'est le bon réglage par défaut pour les
deux mixins de limite et une responsabilité pour les cinq cosmétiques : un changement
Minecraft ou Cobblemon côté client casse le jeu au lieu de perdre un effet décoratif.

## Points d'extension

Ce qu'un contributeur peut utiliser sans toucher aux entrailles :

- **`CobbleCapMod.LEVEL_FORCE_ACTIVE`** — un `public static ThreadLocal<Boolean>`. Pose-le à
  vrai sur ton thread et `PokemonSetLevelMixin` ainsi que le contrôle d'équipe de
  `BATTLE_STARTED_PRE` se mettent en retrait. C'est ainsi que CheeseCompat permet à Battle
  Tower de forcer des niveaux au-delà de la limite. Remets-le dans un `finally`.
- **`RctTrainerMorpher.trainerJsonRelPath` / `trainerSkinRelPath` / `datapackName`** —
  `public static`, non `final`, réassignables à l'exécution.
- **Les exports CobbleCheeseScore** — quatre fichiers JSON à la forme stable, écrits à chaque
  reconstruction de tableau. Lis-les plutôt que d'analyser des scoreboards.
- **Le scoreboard `cobblecap_mode`** — `1` classique, `2` compétitif. N'importe quel système
  peut l'écrire ; `/cobblecap mode` n'est qu'un producteur parmi d'autres.
- **Les advancements `cobblecap:`** — accordés à la victoire, et FTB Quests est censé les
  remarquer par scrutation. Tout système de quête qui observe les advancements peut
  s'accrocher de la même façon.
- **`SyntheticUuidRegistry`** — vit dans le source set commun précisément pour que du code
  client puisse le lire.

Il n'y a **aucune** API Java publique, aucun événement publié pour d'autres mods, et aucun
canal WebSocket. L'intégration dashboard est à sens unique : CheeseDash appelle, CobbleCap
ne pousse jamais.

## Code désactivé et dormant

Dit clairement, parce que lire le source sans cette liste est déroutant :

- **`Conseil4TrainerCloner` (548 lignes) est désactivé.** `CobbleCapMod.onInitialize`
  appelle `registerCleanupOnly()`, pas l'enregistrement complet. Il devait cloner chaque
  dresseur du Conseil 4 en une variante propre à la dimension pour que des combats simultanés
  n'entrent pas en collision sur le singleton `TrainerNPC` partagé de rctmod. Le déploiement
  en production du 17/05/2026 a produit *Invalid trainer id* sur les cinq dresseurs, parce que
  le rechargement de rctmod ne rescannait pas les fichiers de datapack depuis le disque. Ce
  qui tourne encore est un nettoyage unique qui rétablit les identifiants par dimension
  périmés et supprime les JSON de clone orphelins. `ChampionManager.stripPerDimSuffix` existe
  pour normaliser les identifiants que ce déploiement a laissés derrière.
- **`RctTrainerMorpher.countActiveBattles`** — la vérification de combat en échec fermé.
  Jamais appelée ; voir
  [le morphing du champion](champion-morphing.fr.md#la-vérification-de-combat-est-du-code-mort).
- **`RctTrainerMorpher.downloadMojangSkin` et `writeAtomicBytes`** — le morph de la skin.
  Jamais appelées ; un datapack ne peut pas servir d'assets, et écrire dans `resourcepacks/`
  forcerait chaque client à retélécharger.
- **`StatueManager.equipStyleArmor`** — jamais appelée. Un reste de la conception à support
  d'armure visible que la doublure a remplacée, et c'est pourquoi l'armure d'un style n'a
  aucun effet visible.
- **`ChampionManager.triggerFtbQuest` et `triggerFtbQuestById`** — plus appelées depuis le
  chemin de victoire. Elles forçaient la complétion de quête, ce qui passait par
  `Quest.forceProgress → Reward.forceProgress → claimReward` et dupliquait parfois une
  récompense de badge. Retirer l'appel a rendu le doublon structurellement impossible.
- **`ChampionNpcManager.morphTo`** — aucun appelant. Seules les commandes `bind_npc*`
  touchent cette classe, donc le chemin de repli enregistre une liaison et rien ne morphe
  automatiquement.
- **`PastureHideClient.debugLog` et `lastDebugLog`** — restes de l'approche par réflexion
  côté client qui ne pouvait pas marcher.
- **`ChampionManager.allChallengesBeaten`** est privée et *est* utilisée, par
  `handleChallengeVictory` ; mentionnée seulement parce qu'elle a l'air morte à côté du
  reste.

## Faiblesses connues

Au-delà du code mort :

- **Le basculement de mode de Régis est global au serveur** alors que le drapeau de mode est
  par joueur. Deux joueurs entrant avec des modes opposés affrontent tous deux celui écrit en
  dernier. Assumé dans le source comme un risque accepté à cinq ou dix entrées par semaine.
  [Détails](champion-morphing.fr.md#le-basculement-de-mode-de-régis-est-global).
- **L'identifiant du champion final est codé en dur à quatre endroits** —
  `ChampionManager` lignes 548, 605 et 792, et `StatueCommand` ligne 541.
- **`datapackName = "CacademyMons"`** est codé en dur à `RctTrainerMorpher` ligne 57, et un
  commentaire au-dessus affirme à tort qu'il est configurable depuis `CapConfig`.
- **Les statues ne sont indexées que par étiquettes de commande,** indexées sur le **nom** de
  joueur en minuscules. Un changement de pseudo orpheline un diorama ; deux noms ne différant
  que par la casse entrent en collision.
- **L'apparition des Pokémon de statue est une heuristique spatiale** — `/spawnpokemonat` ne
  renvoie aucune entité, donc le mod relève les UUID avant et cherche dans un rayon de 4
  blocs après.
- **`refreshCurrentChampionDioramas` a besoin d'un opérateur en ligne** comme mandataire de
  source de commande, parce que la commande de spawn de Cobblemon lève une NPE sans joueur.
  Aucun opérateur en ligne, aucune rangée de Pokémon.
- **Quatre des neuf fichiers d'état ne sont pas écrits atomiquement,** y compris
  `cobblecap_tenures.json`.
- **`ChampionConfig.setChampions` remplace une liste non synchronisée** depuis un thread HTTP
  pendant que le thread serveur la lit à chaque combat.
- **`CapHudRenderer` reçoit `nextChampion` et ne le dessine jamais.** La clé de langue
  `cobblecap.hud.next` existe et n'est pas utilisée.
- **Plusieurs chaînes destinées aux joueurs sont des `Text.literal` français,** pas des clés
  traduisibles : l'avis d'annulation automatique de chrono, les annonces de record battu et
  de course plus lente, les annonces de couronnement compétitif, le refus de `chrono stop`,
  et tous les titres de scoreboard Conseil 4. Un client anglais voit du français.
- **`/cobblecap status <player>` affiche soixante à cent lignes de chat,** dont un vidage
  complet des scoreboards et un parcours des advancements.
- **`extractRctmodTrainerId` se replie sur une heuristique large** — tout getter `String`
  sans argument dont le résultat contient un tiret bas et dépasse trois caractères. Il peut
  renvoyer quelque chose sans rapport.
- **Deux grosses classes portent l'essentiel du risque.** `StatueManager` à 1 422 lignes et
  `ChampionManager` à 1 147 mélangent persistance, réflexion, travail d'entités et messagerie.
  C'est là qu'un changement a le plus de chances d'avoir une conséquence ailleurs.

## Compromis assumés

- **De la réflexion partout plutôt qu'une dépendance Cobblemon dure.** Cela coûte la sûreté
  de typage et fait de chaque échec un échec d'exécution ; cela achète un mod qui se charge
  et ne plante pas quand Cobblemon renomme quelque chose.
- **Échec ouvert sur le chemin de l'XP.** Une limite cassée vaut mieux qu'un serveur planté,
  et la limite est une règle de jeu plutôt qu'une garantie de correction.
- **La marge d'XP de `−1`.** Elle laisse un Pokémon plafonné à un point du niveau plafond, ce
  qui a l'air bizarre mais empêche totalement le chemin de montée de niveau de Cobblemon de
  se déclencher.
- **Pas de complétion forcée des quêtes FTB.** Plus lent et dépendant de la scrutation de
  FTB, mais cela a refermé définitivement un trou de duplication de récompense.
- **Pas de canal WebSocket.** Le dashboard interroge en boucle. Plus simple, au prix des
  mises à jour en direct.
- **Un support d'armure marqueur invisible plus un faux joueur,** plutôt qu'un support
  d'armure visible avec une tête. Beaucoup plus de machinerie — cinq écouteurs et une
  auto-réparation toutes les 3 secondes — mais c'est le seul moyen d'obtenir un corps de
  joueur complet sur un client vanilla.

---

← [Configuration](configuration.fr.md) · [Dépannage](troubleshooting.fr.md) →
