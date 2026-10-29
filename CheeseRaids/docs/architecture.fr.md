[← CheeseRaids](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](architecture.md)

# Architecture

21 fichiers source, environ 3 340 lignes, aucun mixin, aucun code client. Écrit pour qui
reprendra la maintenance — partie franche du bas incluse.

---

## Découpage des packages

```text
com.cheeseraids
├── CheeseRaidsMod.java          entrypoint : crochets de cycle de vie, de tick, enregistrement des commandes
├── commands/
│   └── CheeseRaidsCommand.java  tout l'arbre Brigadier (102 lignes)
├── data/                        donnée pure, champs publics compatibles Gson, aucun comportement
│   ├── RaidDefinition.java      configuration statique d'un raid + la définition Hoopa par défaut
│   ├── RaidInstance.java        état d'exécution d'un raid en cours
│   ├── PlayerRaidState.java     état par joueur dans une instance
│   ├── RaidPhase.java           l'énumération de cycle de vie à 8 valeurs
│   └── Pos.java                 position qualifiée par un monde
├── logic/                       tout ce qui se passe
│   ├── RaidManager.java         orchestrateur : I/O, planificateur, machine à états, effets (740 lignes)
│   ├── StarArenaGenerator.java  création de la faille, arène du boss, parkour procédural (844 lignes)
│   ├── WaveManager.java         la vague overworld
│   ├── CheckpointTracker.java   supervision du parkour, à chaque tick
│   ├── BossProvider.java        interface de stratégie de boss
│   ├── CustomGiantBossProvider.java  la seule implémentation
│   └── RewardManager.java       portails, feux d'artifice, monnaie, commandes de butin
├── hooks/                       passerelles de réflexion vers les mods tiers
│   ├── CobblemonSpawnHook.java  PokemonProperties → entité
│   ├── CobblemonBattleHook.java BattleRegistry → le combat est-il terminé
│   ├── EasyNpcHook.java         l'attribut d'affichage VISIBLE du héraut
│   └── CobbleSafariHook.java    portails Hoopa Ring
├── dashboard/
│   ├── CheeseRaidsDashboardModule.java  entrypoint CheeseDash, câblage WebSocket
│   └── RaidsApi.java            les six routes REST
└── util/
    └── Mc.java                  exécution de commandes, résolution de monde/bloc/particule/son
```

`data` ne dépend de rien d'autre que du `BlockPos` de Minecraft. `hooks` dépend de peu de
choses dans le mod : le logger, plus `RaidDefinition` et `Mc` pour `EasyNpcHook`. `logic` est là où vit le couplage, et l'essentiel tient à `RaidManager`
qui va chercher dans les six autres classes.

## Qui possède quoi

**`CheeseRaidsMod`** possède l'instance de `RaidManager` et trois enregistrements Fabric :
`SERVER_STARTED` → `onServerStart`, `SERVER_STOPPING` → `onServerStop`,
`END_SERVER_TICK` → `tick`. Il détient aussi le consommateur statique `DASHBOARD_BROADCAST`,
qui est la façon dont le mod parle au dashboard sans compiler contre lui à cet endroit.

**`RaidManager`** possède :

- `definitions` — `LinkedHashMap<String, RaidDefinition>`, modifiée au chargement et au
  rechargement ;
- `active` — `ConcurrentHashMap<String, RaidInstance>`, une entrée par raid en cours.
  Concurrente parce que le thread HTTP la lit ;
- `lastRunDates` — les horodatages anti-rejeu du planificateur ;
- les quatre collaborateurs de logique (générateur, vagues, suivi, récompenses — le
  fournisseur de boss est résolu à neuf à chaque appel), et la file d'effets `fx` ;
- `tickCounter`, incrémenté à chaque tick et utilisé par toutes les cadences par modulo.

**`StarArenaGenerator`** possède la file de révélation et deux ensembles à portée de session :
`nightLocked` (failles dont le verrou de minuit a été persisté cette session) et
`builtCenters` (failles dont l'arène du boss a été construite pour ce raid). Les deux sont en
mémoire seulement ; un redémarrage en pleine raid les oublie, ce qui signifie que l'arène est
reconstruite et le verrou ré-enregistré. Sans danger, mais bon à savoir.

**`CustomGiantBossProvider`** ne possède rien. Il est sans état par conception : tout vit sur la
`RaidInstance`, donc `BossProvider.forDefinition()` peut renvoyer une instance neuve à chaque
appel et deux raids concurrents ne partagent jamais d'état. Le commentaire dans `RaidManager`
le dit explicitement.

## État et disque

Trois fichiers, tous sous `config/cheeseraids/` :

| Chemin | Écrit quand | Lu quand |
|---|---|---|
| `raids/<id>.json` | seulement quand le défaut est écrit au premier démarrage — rien d'autre n'écrit jamais une définition | au démarrage du serveur, et à chaque `reload` |
| `instances/<raidId>.json` | à `start`, à chaque transition de phase, à chaque `enter`, toutes les 100 ticks, et à l'arrêt du serveur | au démarrage du serveur, pour la récupération après crash |
| `state.json` | chaque fois que le planificateur déclenche, et à l'arrêt du serveur | au démarrage du serveur |

Chaque écriture est un `Files.writeString` synchrone sur le **thread serveur**. Pour les
fichiers d'instance, cela représente quelques centaines d'octets toutes les cinq secondes par
raid actif, ce qui va ; cela reste des entrées-sorties bloquantes dans la boucle de tick, et un
raid à douze joueurs écrit un objet plus gros.

La récupération après crash est volontaire et précise. Au démarrage, chaque fichier d'instance
dont la phase n'est pas `IDLE` est rechargé dans `active` : la machine à états reprend donc là
où elle s'était arrêtée. C'est l'état par joueur qui rend l'opération sûre : `lastCheckpoint` et
`seed` survivent, et `enter` remet un joueur de retour à son checkpoint au lieu de régénérer sa
branche. Les commentaires du code relient cela à deux incidents dans des mods frères — une
duplication d'inventaire et une boucle de crash — et la leçon est énoncée comme une contrainte
dure : **ne jamais régénérer, toujours restaurer.**

Trois trous dans cette récupération. L'instance rechargée conserve des échéances absolues en
millisecondes epoch : un serveur arrêté une heure revient avec tous ses timeouts déjà expirés
et le raid court jusqu'au nettoyage. `builtCenters` étant en mémoire, l'arène est
reconstruite sous des joueurs qui s'y trouvent déjà. Et la file d'effets n'est pas
persistée : un redémarrage pendant le crescendo du héraut le laisse masqué pour le reste du
raid, et un redémarrage pendant l'apparition du boss (`COMBAT` avec `bossSpawned` encore à
false) fait que le boss n'apparaît jamais et que le raid attend la fin de
`boss.combatTimeoutSeconds`.

## Threading

Presque tout est sur le thread serveur. C'est le principe.

| Tourne sur | Quoi |
|---|---|
| thread serveur | `tick()`, et donc toute la machine à états, la file d'effets, la file de révélation, les deux hooks d'apparition, la pose de blocs, les téléportations, chaque commande, chaque écriture disque |
| thread HTTP (CheeseDash) | `RaidsApi.handle` — parsing, sérialisation, réponse |
| thread WebSocket (CheeseDash) | la diffusion `raid_update`, depuis `DashboardRegistry.broadcast` |

Le passage de relais est explicite. `start`, `stop` et `reload` enveloppent toutes leur
mutation dans `WebDashboard.executeOnServerThread(server, …)` et capturent le résultat dans un
tableau à un élément, pour que la réponse HTTP puisse le rapporter. Les lectures ne changent pas
de thread : `definitions()` et `activeInstances()` renvoient des **copies**
(`new ArrayList<>(…)`) précisément pour qu'un thread HTTP puisse itérer pendant que le thread
serveur modifie.

Ces copies sont superficielles. Un `GET /instances` qui itère sur une liste copiée déréférence
tout de même des objets `RaidInstance` vivants, dont la table `players` peut être en cours
d'écriture par le thread serveur. En pratique, itérer une `LinkedHashMap` pendant un `put`
concurrent peut lever, et le `try/catch` externe de l'API transforme cela en 500. Ça ne s'est
jamais vu, mais c'est le genre de course qui n'apparaît qu'avec douze joueurs entrant en même
temps.

`EasyNpcHook` et `CobblemonSpawnHook` résolvent leur réflexion paresseusement, dans des
initialiseurs `synchronized` protégés par un `volatile boolean init`. Les deux ne sont jamais
appelés que depuis le thread serveur : la synchronisation est donc une ceinture par-dessus les
bretelles, pas une nécessité.

## Mixins

**Il n'y en a aucun.** Pas de `cheeseraids.mixins.json`, pas de package de mixins, rien dans
`build.gradle`. Tout ce que le mod fait, il le fait via l'API Fabric, via les classes
serveur publiques de Minecraft, et par réflexion dans d'autres mods. Pour un mod qui fait
apparaître des Pokémon géants dans une dimension générée, c'est une retenue délibérée et
bienvenue : il n'y a aucun comportement vanilla à patcher.

Il n'y a non plus **aucun code client**. `fabric.mod.json` déclare `"environment": "*"` et un
unique entrypoint `main`, sans entrypoint `client`. Chaque effet visuel est une particule, un
son ou un paquet de titre envoyé par le serveur, et c'est pourquoi le raid ne demande aucune
installation côté client.

## La file d'effets

Elle mérite sa propre section, parce que c'est l'unique choix de conception non évident du mod.

`FxTask` est une classe abstraite avec un compteur `age` privé et un `step(age)` que la
sous-classe implémente, renvoyant `true` quand elle est terminée. `tickFx()` parcourt la liste,
appelle chacune, et **retire toute tâche qui lève**, après l'avoir journalisée :

```java
try { done = it.next().tick(); }
catch (Throwable e) { LOGGER.warn("…FX task error: {}", e.getMessage()); done = true; }
```

Le commentaire dit *never let an FX bug stall the raid*, et c'est le bon réflexe — avec une
conséquence qu'il faut comprendre plutôt que corriger à la légère. L'apparition du boss se
trouve *dans* une tâche FX, à l'âge 36 de `bossApparitionFx`. Si cette tâche lève à l'âge 20,
elle est abandonnée, le boss n'apparaît jamais, `bossSpawned` reste `false`, le test de victoire
continue de renvoyer `false`, et le raid reste en `COMBAT` jusqu'à l'expiration de
`boss.combatTimeoutSeconds` — quinze minutes par défaut. Des effets cosmétiques et une
apparition structurante partagent une seule tâche faillible.

Il existe un garde-fou : si le monde de la faille ne peut pas être résolu, la tâche fait
apparaître le boss à l'âge 0 et sort immédiatement, pour qu'un monde manquant ne saute pas
silencieusement l'apparition.

## Points d'extension

**`BossProvider`** est le propre. Cinq méthodes plus deux crochets de tick par défaut, tout
l'état sur l'instance. Écris une classe, implémente-la, puis modifie `forDefinition()` pour
qu'il lise `def.boss.provider` — il ignore ce champ aujourd'hui et renvoie toujours
`CustomGiantBossProvider`.

**Un nouveau raid** ne demande aucun code : dépose un fichier JSON dans
`config/cheeseraids/raids/`. Voir
[le modèle de raid](raid-model.fr.md#écrire-un-second-raid) pour ce sur quoi tu vas butter.

**`Mc`** est l'endroit où ajouter des helpers partagés. `Mc.run` en particulier vaut d'être
réutilisée plutôt que de réécrire sa propre exécution de commande : elle construit une source
de niveau 4 dont la sortie est capturée et journalisée, et appelle le dispatcher directement
pour qu'une `CommandSyntaxException` remonte et soit journalisée au lieu d'être avalée. C'était
un vrai bug — la version précédente, avec `withSilent()`, masquait complètement les
`pokespawn` refusés.

**Un nouveau hook** devrait copier `CobblemonSpawnHook` : initialisation paresseuse
synchronisée, un prédicat `available()`, correspondance de méthode par forme, une ligne de
journal distincte par échec, et une valeur de retour que l'appelant vérifie.

**Une nouvelle phase** veut dire modifier l'énumération et le `switch` d'`advance()`. Rien n'est
piloté par table ; la machine à états est un `switch` littéral, ce qui est lisible et
entièrement fermé à l'extension.

## Faiblesses connues et compromis assumés

Énoncées sans détour, parce que le mod est expérimental et que celui qui le reprendra mérite
cette liste.

**Jamais joué.** La plus grande faiblesse, de loin. Il compile, il a été déployé sur des
instances de test, et aucun raid n'a jamais été joué avec des joueurs. Rien de ce qui suit n'a
été confirmé par l'observation.

**Deux classes contiennent 47 % du code.** `RaidManager`, à 740 lignes, mélange cinq
responsabilités : entrées-sorties fichier, planificateur, machine à états, effets cinématiques,
et mise en forme du chat ou des titres. `StarArenaGenerator`, à 844 lignes, mélange gestion de
dimension, construction d'arène, géométrie procédurale et animation de révélation. Les deux se
découperaient proprement le long de ces lignes, et les deux sont les fichiers qu'il faut lire
en entier pour changer quoi que ce soit.

**Seize clés de configuration mortes.** `wave.spawnCommand`, `wave.entityType`,
`wave.detectRadius`, `wave.captureWindowMs`, `boss.spawnCommand`, `boss.scaleCommand`,
`boss.entityType`, `boss.detectRadius`, `boss.captureWindowMs`, plus `boss.provider`,
`arena.model`, `arena.parkourLength`, `arena.gapMin`/`gapMax`, `arena.riseMax` et
`npc.despawnAfterSeconds`. Ce sont les résidus d'une conception où les entités étaient créées
par commande et détectées par comparaison d'instantanés. Elles sont écrites sur le disque au
premier démarrage, ce qui les fait ressembler à une surface de configuration. Elles n'en sont
pas une.

**Un morceau de code mort.** `RaidManager.showHerald()` est privée, complète, et appelée de
nulle part — le chemin de révélation est passé par `heraldRevealFx` et celui-ci est resté en
arrière. C'est le seul orphelin privé. Une poignée d'utilitaires publics ne sont jamais appelés
non plus : `WaveManager.aliveCount`, `Mc.entitiesOfType`, `Mc.worldKey`,
`CobbleSafariHook.available`, `RaidManager.definition` et `RaidInstance.player`.

**Chaque texte est un littéral français.** `assets/cheeseraids/lang/en_us.json` et
`fr_fr.json` contiennent chacun exactement une clé, `cheeseraids.title`, et rien ne la lit.
Chaque message qu'un joueur voit est un `Text.literal` avec des codes couleur `§` en dur, en
français. Convertir le mod vers `Text.translatable` est un changement mécanique mais large,
touchant `RaidManager`, `CheckpointTracker`, `RewardManager`, `EasyNpcHook` et la classe de
commandes.

**Quatre hooks par réflexion, trois d'entre eux silencieux.** L'apparition échoue bruyamment.
Le hook de combat, celui d'échelle et celui de CobbleSafari se dégradent tous vers un repli ou
un avertissement, ce qui maintient le raid en marche et rend une dépendance cassée difficile à
remarquer. CobbleSafari est le pire des trois : il appelle une méthode **privée** d'un autre
mod, qui ne lui doit aucune stabilité. [Les intégrations](integrations.fr.md) donnent la matrice
complète.

**Entrées-sorties bloquantes dans la boucle de tick.** Chaque enregistrement d'instance et
chaque `dm.save()` est une écriture fichier synchrone sur le thread serveur. L'enregistrement
du verrou de nuit est déjà limité à une fois par faille et par session pour exactement cette
raison ; les enregistrements d'instance ne le sont pas.

**Grosses opérations de blocs synchrones.** `buildCenter` écrit un disque de rayon
`centerRadius` ; `clearCenter` vide un pavé de côté `2 × (centerRadius + 17) + 1` sur
29 couches (d'une sous le disque à 27 au-dessus) — soit environ 122 000 mises à jour de blocs
au rayon par défaut (65 × 65 × 29), en un seul tick, avec
`NOTIFY_LISTENERS`. Sur un chunk vide c'est peu coûteux par bloc, mais ça reste le travail d'un
tick dans les deux cas.

**Copies superficielles à la frontière de threads.** Décrit dans la section
[threading](#threading). La copie protège la liste, pas les objets qu'elle contient.

**La file d'effets avale une apparition structurante.** Décrit dans la section
[la file d'effets](#la-file-deffets).

**Les récompenses ne dépendent pas de la participation.** Tout joueur de l'instance connecté à
la mort du boss est récompensé, qu'il ait atteint le disque ou non. Le drapeau `arrived` existe
et est suivi ; le chemin de récompense ne le lit pas.

**`stop` n'éjecte pas.** La commande comme la route REST nettoient l'arène sans téléporter
personne. Le chemin d'éjection existe (`ejectAll`) et est appelé depuis la séquence de victoire
et le timeout de combat, mais pas depuis `stop`.

**Les échéances sont des millisecondes epoch absolues.** Une longue indisponibilité en pleine
raid ressuscite une instance dont tous les timeouts sont déjà passés.

---

← [API dashboard](dashboard.fr.md) · [Dépannage](troubleshooting.fr.md) →
