[← CobblemonDungeons](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](architecture.md)

# Architecture

45 fichiers Java sous `src/main/java`, environ 12 000 lignes, plus un fichier
supplémentaire dans le sous-projet `velocity-party-forwarder/`. Deux fichiers portent plus
de 45 % de ce total, ce qui t'apprend l'essentiel de ce qu'il faut savoir sur l'endroit où
siège la complexité.

---

## Le nom du package

Le package racine est `com.sebastian.cobblemondungeons`, le groupe Gradle est
`com.sebastian`, et l'identifiant du mod est `cobblemon_dungeons`. Ce nommage est hérité et
il n'a **délibérément pas été renommé**. Trois choses casseraient d'un coup :

- **Les mixins.** `cobblemon_dungeons.mixins.json` déclare
  `"package": "com.sebastian.cobblemondungeons.mixin"`, et chaque classe de mixin est
  résolue relativement à lui.
- **Les identifiants de registre et de ressources.** `MOD_ID` vaut `cobblemon_dungeons`, et
  chaque bloc, item, fluide et screen handler est enregistré sous ce namespace. Les chemins
  de ressources et de datapack — `assets/cobblemon_dungeons/…` et les fichiers de
  blockstate, de modèle et d'item qu'ils contiennent — sont indexés par lui, comme le sont
  les identifiants des blocs déjà posés dans les mondes en service.
- **L'entrypoint CheeseDash.** `fabric.mod.json` nomme
  `com.sebastian.cobblemondungeons.dashboard.CobblemonDungeonsDashboardModule` comme classe
  pleinement qualifiée, découverte par réflexion à l'exécution. Le
  `CobblemonDungeonsHook` de CheeseGuard fait de même, en cherchant
  `com.sebastian.cobblemondungeons.dungeon.DungeonManager`,
  `…item.DungeonTicketItem` et `…config.DungeonTicketConfig$TicketConfig` par
  `Class.forName`.

Un renommage n'apporte rien de fonctionnel et coûte une migration de monde plus une
modification coordonnée dans un autre mod. Laisse-le tel quel.

Une vraie incohérence existe malgré tout : les fichiers de langue vivent dans
`assets/cobblemondungeons/lang/` — sans tiret bas — alors que toutes les autres ressources
sont sous `assets/cobblemon_dungeons/`. Cela fonctionne, parce que Minecraft fusionne les
fichiers de langue de tous les namespaces et que les clés elles-mêmes sont
`cobblemondungeons.*`, mais c'est un piège quand tu les cherches.

## Disposition des packages

```text
com.sebastian.cobblemondungeons
├── CobblemonDungeons.java          entrypoint principal — registres, ticks, événements, commandes (2439 L)
├── CobblemonDungeonsClient.java    entrypoint client — écrans, HUD, rendu, rejeu du payload
├── block/                          7 classes : spawners, pieux, portail, coffre de donjon + son block entity
├── config/                         4 classes : ConfigLoader, DungeonConfig, MainConfig, DungeonTicketConfig
├── dashboard/                      module CheeseDash + DungeonsApi
├── dungeon/                        DungeonManager, DungeonInstance, et les trois gestionnaires d'événements Cobblemon
├── fluid/                          PoisonWaterFluid (immobile + courant)
├── gui/                            screen handler, écran, HUD, et les deux ouvreurs d'interface
├── item/                           DungeonTicketItem
├── mixin/                          3 mixins
├── networking/                     7 records CustomPayload
├── party/                          DungeonsParty, son gestionnaire, ses commandes
└── util/                           DatabaseManager, InteractionHandler, TrainerLoader, les deux gestionnaires de sync
```

## Les classes clés

| Classe | Lignes | Ce qu'elle possède |
|---|---|---|
| `DungeonManager` | 3012 | Tout ce qui touche à une partie : les tables d'instances, les comptes à rebours, le démarrage et la sortie, les apparitions, les objectifs, les récompenses, l'historique, les cooldowns, le classement, les diffusions WebSocket |
| `CobblemonDungeons` | 2439 | L'enregistrement de chaque bloc, item, fluide et screen handler ; la boucle par tick ; l'IA des Alphas ; les dangers ; les arbres Brigadier |
| `DungeonScreenHandler` | 656 | L'état côté serveur de chaque écran de donjon, dont l'échange de tickets |
| `DungeonScreen` | 646 | Le rendu client de ces écrans |
| `DungeonInstance` | 589 | L'état mutable d'une partie : compteurs, vies, chronomètre, inventaires de coffres, files de scan |
| `DungeonsApi` | 565 | Les 19 routes REST sous `/api/v1/dungeons` |
| `PlayerSyncHandler` | 529 | La sérialisation et la restauration de l'état joueur entre serveurs |
| `PartySyncHandler` | 401 | L'état de l'équipe Cobblemon entre serveurs, plus l'outillage de débogage |
| `InteractionHandler` | 297 | Les interactions d'entités et de blocs : coffres, génération de butin, pieux, clés, gemmes, autel du boss |
| `ConfigLoader` | 250 | La lecture et l'écriture de chaque fichier JSON |
| `TrainerLoader` | 135 | L'enregistrement des dresseurs auprès de RCTApi au démarrage du serveur |

`DungeonManager` est entièrement statique — tables statiques, méthodes statiques, aucune
instance. C'est pourquoi un `MinecraftServer` doit être passé à presque chaque appel, et
pourquoi il n'y a aucune couture naturelle pour des tests.

## L'état, et le moment où il est écrit

| État | Où il vit | Persisté |
|---|---|---|
| Configurations de donjons | `DungeonManager.dungeons`, une `ConcurrentHashMap` indexée par identifiant | `dungeons/<id>.json`, écrit à la création et à la mise à jour depuis le dashboard |
| Instances vivantes | `activeInstances` (par joueur), `partyInstances` (par groupe), `bossToInstance` (par boss) | Jamais. Elles meurent avec le serveur |
| Comptes à rebours, syncs, transferts et démarrages en attente | Quatre autres tables statiques | Jamais |
| Positions de blocs | Registres `Long2IntOpenHashMap` par monde plus un index par chunk, remplis au chargement de chunk | Jamais — reconstruits depuis le monde à chaque chargement |
| Modèles de tickets | `ConfigLoader.ticketConfig` | `dungeon_tickets.json`, à la mise à jour depuis le dashboard |
| Historique des parties | `CopyOnWriteArrayList`, plafonnée à 1000 | `run_history.json`, réécrit intégralement à la fin de chaque partie |
| Cooldowns | `ConcurrentHashMap` imbriquée | `cooldowns.json`, réécrit intégralement à chaque partie réussie |
| Classement | `ConcurrentHashMap` imbriquée | `leaderboard.json`, réécrit intégralement à chaque partie réussie |
| Blobs de transfert joueur | La table `<préfixe>player_sync` | Seulement quand `database.useSql` est activé |

Chaque écriture JSON est une réécriture complète du fichier, pas un ajout, et elle a lieu
sur le thread qui l'a déclenchée. Pour les fichiers d'enregistrement, c'est le thread serveur
en fin de partie : une écriture bloquante de quelques centaines de kilooctets au plus. À un
rythme humain c'est invisible ; cela vaut la peine de le savoir avant que quelqu'un n'ajoute
un chemin de code qui enregistre une partie en boucle.

Il n'y a aucun état dans `world/data` et aucun `PersistentState` personnalisé. Rien de ce que
le mod stocke n'est lié à une sauvegarde de monde, hormis les blocs et les items que les
joueurs détiennent.

## Le threading

**Thread serveur.** Tout ce qui touche au monde. `tickFastScans`, `tickPendingSyncs`,
`tickPendingTransfers` et `tickPendingStarts` tournent à chaque `START_SERVER_TICK` ;
`tickDungeons` — les comptes à rebours, le chronomètre, l'envoi du HUD — tourne une fois
toutes les 20 d'entre eux. La granularité d'une seconde du chronomètre de donjon est une
conséquence de ce diviseur, pas d'une horloge murale.

**Threads HTTP.** Les gestionnaires de `DungeonsApi` tournent sur l'exécuteur `HttpServer`
de CheeseDash. Les lectures attaquent directement les tables concurrentes ; chaque mutation
est enveloppée dans `WebDashboard.executeOnServerThread(server, …)`. C'est cohérent dans
tout le fichier : les gestionnaires de création, de mise à jour, de suppression, de
rechargement, d'échec forcé et de sortie forcée délèguent tous. Les écritures de tickets et
de dresseurs non, parce qu'elles ne touchent que des fichiers et l'objet de configuration,
jamais le monde.

Une conséquence de cette délégation à connaître en lisant le code :
`handleCreateConfig` planifie l'enregistrement puis relit immédiatement le donjon dans le
registre pour construire sa réponse, laquelle dépend donc de l'achèvement de la tâche
planifiée. `executeOnServerThread` est synchrone, ce qui rend la chose correcte — et fragile
si cela change un jour.

**Threads d'événements Cobblemon.** `BattleHandler`, `DeathHandler` et `CaptureHandler`
s'abonnent au bus d'événements Kotlin de Cobblemon et tournent là où il les dispatche, en
pratique le thread serveur. Ils atteignent le serveur par
`FabricLoader.getInstance().getGameInstance()` au lieu de se le faire passer, ce qui est une
odeur de code mais fonctionne sur un serveur dédié.

**Threads Netty.** Les récepteurs de payload sont enregistrés avec `ServerPlayNetworking`,
qui dispatche sur le thread serveur : rien de particulier n'est nécessaire là.

## Les mixins

Trois, tous dans `cobblemon_dungeons.mixins.json` avec `"required": true` et
`defaultRequire: 1` — une injection ratée est un crash, pas un avertissement.

| Mixin | Cible | Pourquoi |
|---|---|---|
| `PokemonEntityMixin` | `net.minecraft.entity.Entity#calculateDimensions`, en `TAIL` | Force le modificateur d'échelle de Cobblemon à la valeur persistante `CustomScale` — ou à l'aspect `scale:` en repli — et fixe `GENERIC_SCALE` à 1.0 pour que les deux ne se multiplient pas. C'est ce qui rend un Alpha ou un boss visiblement plus grand sans que sa boîte de collision dérive |
| `EntityAccessor` | `net.minecraft.entity.Entity` | Accesseur et invoker pour le champ privé `dimensions` et `calculateDimensions()`, utilisés par le code d'échelle |
| `HandledScreenMixin` | `HandledScreen#render`, en enveloppant `drawSlotHighlight` | Client uniquement. Supprime la surbrillance de slot vanilla sur un `DungeonScreen`, un écran dessiné à la main où la surbrillance tombe mal |

Note le nom de la classe : `PokemonEntityMixin` cible `Entity`, pas `PokemonEntity`, et
vérifie le type à l'exécution. Elle a visiblement été renommée dans l'intention et pas dans
le nom.

Les trois ciblent des classes vanilla, et c'est volontaire : aucun mixin de ce mod ne touche
au bytecode de Cobblemon ni de RCTApi. Le couplage à ces mods passe par leurs API publiques
et, dans `BattleHandler`, par des chaînes d'aspect et des tags de commande.

## Les payloads réseau

Sept records `CustomPayload` sous `networking/`, six dans le namespace
`cobblemon_dungeons` — `BungeePayload` utilise le canal standard `bungeecord:main` :

| Payload | Sens | Contenu |
|---|---|---|
| `DungeonJoinPayload` | les deux sens | Identifiant de donjon, de groupe, taille du groupe, un blob NBT d'état joueur, tier. Le client le conserve pendant un changement de serveur et le rejoue à la connexion |
| `DungeonHudPayload` | S2C | Les quatre compteurs d'objectifs avec leurs cibles, le temps restant, les vies, un drapeau de visibilité |
| `DungeonTicketSyncPayload` | S2C | Toute la configuration des tickets en JSON, envoyée à la connexion |
| `BungeePayload` | les deux sens | Messages de plugin au format BungeeCord, dont la requête de nombre de joueurs |
| `ProxyForwardPayload` | S2C | Le format de transfert personnalisé que comprend le plugin Velocity |
| `PartySyncPayload` | les deux sens | État sérialisé de l'équipe Cobblemon |
| `PartyDebugPayload` | les deux sens | L'aller-retour de `partydebug ping` |

Il n'y a aucun champ de version de protocole nulle part. Un client et un serveur sur des
versions différentes produiront des erreurs de codec plutôt qu'un avertissement lisible.

## Points d'extension

Si tu ajoutes du code à ce mod, les coutures existantes sont les suivantes :

- **Un nouveau champ de configuration.** Ajoute-le à `DungeonConfig` avec un initialiseur.
  Gson remplit les clés absentes depuis l'initialiseur, donc les anciens fichiers continuent
  de fonctionner et le dashboard prend le champ en compte gratuitement —
  `buildConfigDetail` ne fait que sérialiser l'objet.
- **Un nouvel objectif.** Le motif est : une variante de bloc enregistrée dans une famille
  numérotée, un registre par monde, un compteur et une cible sur `DungeonInstance`, un
  `handleXFound` sur `DungeonManager` qui diffuse et appelle `checkPortalActivation`, et un
  terme de plus dans `DungeonInstance.canAccessBoss()`. Suis les Pieux : ce sont les plus
  petits des quatre.
- **Une nouvelle route REST.** Un `if (route.equals(…) && method.equals(…))` de plus en haut
  de `DungeonsApi.handle`. Place-le avant la branche de préfixe `/configs/`, qui est
  gourmande.
- **Un nouveau message WebSocket.** `broadcastDungeonEvent(type, instance, server)` avec une
  nouvelle chaîne de type. La forme du payload est figée pour tous les types.
- **Une nouvelle famille de variantes de blocs.** Suis `registerAlphaSpawner(int, Block,
  Item)` et ses constantes `*_ID_MIN` / `*_ID_MAX`, et n'oublie pas les JSON de blockstate
  et de modèle par variante. Les comptes actuels sont 20 spawners d'Alpha et 10 de chacun
  des spawners de Gardien, de Pieu, de portail et de coffre.
- **Piloter le mod depuis un autre mod.** Copie le `CobblemonDungeonsHook` de CheeseGuard :
  cherche `DungeonManager` par son nom et appelle ses méthodes statiques par réflexion. Les
  points d'entrée utiles sont `startDungeon`, `getDungeonConfig` et
  `DungeonTicketItem.createStack`.

## Faiblesses connues et compromis assumés

**Deux fichiers portent tout.** `DungeonManager` à 3012 lignes et `CobblemonDungeons` à
2439 sont le mod entier en pratique. L'IA des Alphas, les tables de types, les ticks de
danger, l'enregistrement et l'arbre de commandes vivent tous dans la classe d'entrypoint,
et le cycle de vie des instances, les apparitions, les récompenses et trois sous-systèmes de
persistance vivent tous dans le manager. Toute modification de l'un ou de l'autre suppose de
lire beaucoup de code sans rapport d'abord.

**Les instances n'existent qu'en mémoire : un redémarrage perd chaque partie.** Il n'y a
aucune sérialisation de `DungeonInstance`. Un arrêt ou un crash en pleine partie laisse le
groupe là où il se tenait, sans instance, sans HUD et sans sortie autre que
`/dungeons leave`, qui leur dira qu'ils ne sont pas dans un donjon. Sur une installation
cross-serveur, ils peuvent en plus se trouver sur le mauvais serveur avec des inventaires
non sauvegardés. C'est la plus grosse lacune.

**Se déconnecter est un échec non enregistré.** `handleDisconnect` retire le joueur
silencieusement : rien n'est journalisé, aucun cooldown n'est noté, le classement n'est pas
touché, et un joueur solo ne peut pas revenir parce que l'instance est démontée avec lui.

**La détection des Gardiens est en partie basée sur les noms.** `BattleHandler` traite tout
PNJ dont le nom contient « guardian » comme un Gardien de donjon. Le nettoyage des entités
sur un serveur de rôle `main` est la même espèce d'heuristique, cherchant `key`, `gem`,
`stake` et `boss` dans les noms d'entités avec une exception pour « dungeon master ». Les
deux se déclencheront à tort sur une entité sans rapport nommée malencontreusement.

**Les interactions sont pilotées par des noms d'entités.** Les clés, les gemmes, les pieux,
l'autel du boss et le PNJ Dungeon Master sont tous identifiés par correspondance de
sous-chaîne sur un nom personnalisé dans `InteractionHandler`. C'est ce qui rend le système
si facile à écrire avec un datapack et quelques porte-armures nommés, et si facile à casser
en renommant quelque chose.

**Du code mort dans le chemin de victoire sur le boss.** `BattleHandler` compare le surnom
d'un perdant de combat à `boss.customName` pour détecter la mort du boss, mais un boss porte
l'aspect `alpha` et `BattleHandler` annule tout combat contenant un Alpha : cette branche ne
peut pas s'exécuter. Le chemin vivant est le tag de commande `DungeonBoss` de
`DeathHandler`.

**`minLevel` n'est pas implémenté.** `requirements.minLevel` existe dans la classe de
données et n'est lu par rien.

**`DUNGEON_MODIFIER` n'est pas implémenté.** Le troisième effet de donjon de CheeseGuard
journalise et annonce, et son propre commentaire dit que l'intégration avec `DungeonManager`
est un travail futur.

**La frontière des rôles du dashboard vit dans le cœur, pas ici.** `DungeonsApi` n'appelle
jamais `requireWrite` ni `requireAdmin`. La frontière tient quand même — le répartiteur de
modules de CheeseDash exige `OPERATOR` ou au-dessus pour toute requête non-`GET` vers
n'importe quel module, donc les huit routes mutantes sont de niveau `OPERATOR` et les `GET`
acceptent tous les rôles. Mais la garantie est une propriété du répartiteur du cœur, fondée
uniquement sur la méthode HTTP : aucune route ici ne peut exiger `SUPER_ADMIN`, un `GET` qui
modifierait quelque chose serait sans garde, et une seule modification de ce répartiteur
ferait tomber tous les contrôles d'un coup, sans seconde ligne de défense dans cette API.
Documenté en détail dans [la page dashboard](dashboard.fr.md).

**Le `deathCount` de l'historique est dérivé et faux pour les groupes ayant perdu des
membres**, parce que le total de vies de départ est recalculé depuis le nombre de joueurs au
moment de l'enregistrement plutôt que depuis celui capturé à la construction.

**La gestion d'erreurs avale.** `consumeDungeonTicket` et `hasDungeonTicket` enveloppent
tout leur corps dans un `catch (Exception) {}` ; `broadcastDungeonEvent` fait de même ; un
`PUT` de ticket au JSON malformé est journalisé et répondu `200`. Une défaillance dans l'un
de ces chemins est invisible de l'extérieur.

**Couplage aux internes de mods tiers.** `BattleHandler` se base sur la chaîne d'aspect
`alpha` et sur les API d'acteurs et de Pokémon de combat de Cobblemon ; `TrainerLoader`
pilote les convertisseurs de modèles de RCTApi et les `NPCClasses`, `NPCEntity` et
`NPCPlayerModelType` de Cobblemon ; `PokemonEntityMixin` appelle
`PokemonEntity.getSCALE_MODIFIER()`. Tout cela est de l'API publique, et tout cela est
sensible aux versions : ces sous-systèmes casseront à une mise à jour majeure de Cobblemon
ou de RCT, et la casse ressemblera à des Gardiens qui ne comptent pas ou à des boss de taille
normale plutôt qu'à un crash. `BattleHandler.init` est déjà enveloppé dans un
`catch (NoClassDefFoundError | Exception)` qui journalise
`BattleHandler failed to init (Cobblemon API version mismatch?)` et laisse le mod continuer
sans intégration des combats : une dégradation raisonnable, et un avertissement qu'il ne
faut jamais ignorer.

**Le plugin Velocity journalise en INFO, bruyamment.** Chaque message transmis produit
plusieurs lignes de log. C'est volontaire pour la mise en service d'un réseau et mérite
d'être baissé ensuite, ce qui suppose aujourd'hui d'éditer le source.

---

← [Intégration dashboard](dashboard.fr.md) · [Dépannage](troubleshooting.fr.md) →
