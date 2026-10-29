[← CobbleShift](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](architecture.md)

# Architecture

Cette page s'adresse à la personne qui maintiendra CobbleShift ensuite. Elle décrit
l'organisation du code, qui possède quel état, quel thread touche à quoi, et où la
conception est fragile. Les nombres de lignes viennent d'un `wc -l` sur `src/main/` au
moment de la rédaction.

---

## Organisation des packages

```text
com.cobbleshift                 côté serveur : entrypoint, config, commande, gestionnaires, correspondance des espèces, projectile
com.cobbleshift.client          entrypoint client, rendu des modèles fantômes, HUD
com.cobbleshift.client.mixin    les dix mixins client
com.cobbleshift.network         les quatre payloads personnalisés
com.cobbleshift.dashboard       le module CheeseDash et son gestionnaire REST
```

Il n'y a aucun mixin côté serveur. Tous les mixins de `cobbleshift.mixins.json` sont
déclarés sous `"client"` : un serveur dédié n'en applique aucun.

## Classes clés

| Classe | Lignes | Possède |
|---|---|---|
| `CobbleShiftMod` | 123 | l'entrypoint `main` : enregistrement des payloads, chargement de la config, les trois gestionnaires, tous les événements serveur, la répartition du tick |
| `CobbleShiftConfig` | 155 | les seize champs de config, `load()`, `save()`, les setters qui bornent |
| `CobbleShiftCommand` | 559 | l'arbre Brigadier de `/cshift`, l'analyse des jetons d'aspect, le lancer de rayon de 5 blocs |
| `TransformManager` | 585 | `activeTransforms`, les horodatages d'annulation pour le cooldown, les modificateurs d'attributs, la diffusion de la synchronisation et de l'état d'animation, le nettoyage des modificateurs orphelins à la connexion |
| `TransformData` | 114 | une transformation de joueur : espèce, types, BST, shiny, forme, taille et vitesse personnalisées, cooldown d'attaque, horodatages |
| `AbilityManager` | 347 | `activeAbilities`, `disabledPlayers`, la boucle de rafraîchissement, la lecture par réflexion du Pokémon de tête |
| `AbilityType` | 99 | les dix-huit types, leurs noms affichés en français et leurs effets de statut |
| `AttackHandler` | 181 | le côté serveur des attaques au clic gauche : dégâts, cooldown, file des tirs différés, dispersion des projectiles |
| `TypeProjectileEntity` | 159 | l'entité `cobbleshift:type_projectile` et ses effets à l'impact |
| `SpeciesMapper` | 462 | toutes les lectures par réflexion du registre d'espèces de Cobblemon, et les cinq formules de conversion des statistiques |
| `NpcTransformManager` | 399 | la map des transformations de PNJ, son fichier de sauvegarde, le modificateur de vitesse des PNJ |
| `client.CobbleShiftClient` | 193 | l'entrypoint `client` : raccourcis clavier, récepteurs de payloads, verrou de la troisième personne, réticule, rendu des projectiles |
| `client.TransformRenderer` | 1595 | tout l'état client des transformations, la création par réflexion du `PokemonEntity` fantôme, la synchronisation de pose, d'eau et d'animation, les cris |
| `client.AbilityHudRenderer` | 219 | le HUD des capacités et la barre de cooldown d'attaque côté client |
| `dashboard.CobbleShiftDashboardModule` | 42 | le préfixe `shift`, la garde 503 tant que le serveur n'a pas démarré |
| `dashboard.ShiftApi` | 618 | les quinze routes REST |

`TransformRenderer` représente à lui seul un quart du code, et l'essentiel en est de la
réflexion dans les classes client de Cobblemon. C'est le fichier qui a le plus de chances
de casser à une mise à jour de Cobblemon.

## État et disque

| État | Où | Persisté | Écrit quand |
|---|---|---|---|
| config | champs de `CobbleShiftConfig` | `config/cobbleshift.json` | premier démarrage si absent, `/cshift ability global`, `POST /ability/global`, `PUT /config` |
| transformations de joueurs | `TransformManager.activeTransforms` | **non** | — |
| modificateurs d'attributs de transformation | les données d'attributs du joueur | oui, par Minecraft, dans le fichier du joueur | à la transformation, retirés à l'annulation |
| horodatages d'annulation (cooldown) | `TransformManager.lastRevertTime` | non | — |
| capacités actives | `AbilityManager.activeAbilities` | non | — |
| exclusion d'un joueur des capacités | `AbilityManager.disabledPlayers` | non | — |
| transformations de PNJ | `NpcTransformManager.npcTransforms` | `config/cobbleshift_npc_transforms.json` | à chaque modification d'un PNJ, et sans condition à `SERVER_STOPPING` |
| modificateur de vitesse des PNJ | les données d'attributs du PNJ | oui, par Minecraft, avec l'entité | à `speednpc`, retiré à l'annulation |
| tirs différés en attente | `AttackHandler.pendingAttacks` | non | — |
| tout l'état client | maps statiques de `TransformRenderer` | non | — |

L'asymétrie entre joueurs et PNJ est la décision de conception centrale, et elle est
voulue. Une transformation de joueur doit durer le temps d'un événement : elle vit en
mémoire et un arrêt propre l'annule. Une transformation de PNJ est un élément de décor :
elle est sauvegardée et un arrêt propre la conserve.

Le point faible est la transformation de joueur à moitié persistée : la map est perdue en
cas de crash alors que les modificateurs et l'Invisibilité infinie survivent dans le
fichier du joueur. `TransformManager.cleanupStaleTransform()` répare cela à la connexion
suivante. L'histoire complète est dans
[le modèle de transformation](transforms.fr.md#déconnexions-redémarrages-et-crashs).

Les deux fichiers ne sont pas résolus de la même manière. `CobbleShiftConfig` passe par
`FabricLoader.getConfigDir()`. `NpcTransformManager` utilise le chemin relatif littéral
`config/cobbleshift_npc_transforms.json`, résolu par rapport au dossier de travail. Chez un
hébergeur qui lance le serveur depuis un autre dossier, les deux fichiers se retrouvent à
des endroits différents. La correction consiste à résoudre `CONFIG_FILE` via
`getConfigDir()`, comme le fait la config.

## Threading

### Thread serveur

Tout ce qui modifie le jeu s'exécute ici quand la demande vient du jeu lui-même :

- les quatre gestionnaires de tick, répartis depuis un seul enregistrement
  `END_SERVER_TICK` : `TransformManager.tick()`, `AbilityManager.tick()`,
  `AttackHandler.tick()`, `NpcTransformManager.tick()`
- toutes les sous-commandes `/cshift`
- le gestionnaire `JOIN` (resynchronisation des transformations de joueurs et de PNJ,
  nettoyage des orphelins) et le gestionnaire `DISCONNECT`, qui ne fait rien
- `SERVER_STARTED` et `SERVER_STOPPING`

Le récepteur C2S des attaques lit l'unique booléen du payload et confie le travail au
thread serveur avec `context.server().execute(...)`, quel que soit le thread sur lequel
Fabric l'invoque. `AttackHandler.pendingAttacks` est une simple `ArrayList`, sûre
uniquement parce que tous les accès passent par ce relais et par le tick.

### Thread HTTP

`ShiftApi` s'exécute sur le thread HTTP de CheeseDash. Le relais est
`WebDashboard.executeOnServerThread(server, task)`, qui place la tâche dans la file du
serveur et **bloque le thread HTTP jusqu'à cinq secondes** en l'attendant. En cas de
dépassement, le gestionnaire répond par une erreur, mais la tâche reste en file et
s'exécute quand même plus tard.

| Route | S'exécute sur |
|---|---|
| `POST /transform`, `/revert`, `/revertall` | thread serveur, via `executeOnServerThread` |
| `POST /ability` (forçage et retrait) | thread serveur, via `executeOnServerThread` |
| `POST /scale` pour un **joueur** | thread serveur, via `executeOnServerThread` |
| `POST /ability/global` | écriture de la config sur le thread HTTP ; le nettoyage est mis en file avec `server.execute`, sans attente |
| `POST /ability/toggle` | **thread HTTP** : `togglePlayer()` modifie l'ensemble et envoie un paquet depuis ce thread |
| `PUT /config` | **thread HTTP** : setters et `save()` |
| `POST /scale` pour un **PNJ** | **thread HTTP** : `setNpcScale()` diffuse et sauvegarde depuis ce thread |
| `POST /speed` | **thread HTTP** : `setNpcSpeed()` cherche l'entité, modifie son attribut et sauvegarde |
| toutes les routes `GET` | thread HTTP, en lisant des `ConcurrentHashMap` et en appelant Cobblemon par réflexion |

Les lignes en gras enfreignent la règle de CheeseDash selon laquelle toute modification de
Minecraft passe par `executeOnServerThread`. Les collections concernées sont concurrentes,
donc rien ne plante sur un serveur calme, mais `setNpcSpeed()` qui touche aux attributs
d'une entité depuis un thread étranger, et deux threads qui réécrivent en même temps le
fichier des PNJ, sont de vraies situations de concurrence. La correction consiste à
envelopper ces quatre gestionnaires dans `executeOnServerThread`.

Les champs de la config sont des champs ordinaires, non `volatile`, écrits par le thread
HTTP et lus à chaque tick par le thread serveur. En pratique les nouvelles valeurs
apparaissent ; le modèle mémoire de Java ne garantit pas quand.

### Thread client

Les trois récepteurs S2C de `CobbleShiftClient` passent sur le thread client avec
`context.client().execute(...)` avant de toucher à `TransformRenderer`. Les maps du moteur
de rendu sont de toute façon des `ConcurrentHashMap`, et tous les mixins les lisent depuis
le thread de rendu.

## Payloads réseau

Les quatre payloads circulent sur la connexion de jeu Minecraft, enregistrés via le
`PayloadTypeRegistry` de Fabric. Aucun n'utilise le WebSocket de CheeseDash, et le mod ne
publie rien sur ce WebSocket.

| Payload | Id | Sens | Champs | Envoyé quand |
|---|---|---|---|---|
| `TransformSyncPayload` | `cobbleshift:transform_sync` | S2C | `playerUuid`, `species`, `type1`, `type2`, `scale`, `silent`, `fromNpc` | transformation, annulation, changement de taille, chaque connexion ; pour les joueurs comme pour les PNJ |
| `AnimationStateSyncPayload` | `cobbleshift:anim_state_sync` | S2C | `playerUuid`, `envState` (octet), `isMoving` | tous les 5 ticks, seulement en cas de changement, aux joueurs à moins de 64 blocs dans la même dimension, jamais au joueur concerné |
| `AbilitySyncPayload` | `cobbleshift:ability_sync` | S2C | `species`, `type1`, `type2` | chaque changement de capacité, pour ce seul joueur |
| `AttackAbilityPayload` | `cobbleshift:attack_ability` | C2S | `hasAnimation` | clic gauche en étant transformé |

Conventions communes aux payloads S2C :

- Une **`species` vide** signifie « rien » : annulation pour une transformation, HUD masqué
  pour une capacité.
- La `species` de `TransformSyncPayload` est **encodée** : `snorlax`, `snorlax|shiny`,
  `lycanroc|shiny|midnight`. Le client découpe sur `|`.
- Les chaînes de type nulles sont écrites `""` et relues comme `null`.
- Les codes `envState` sont `0` au sol, `1` en surface de l'eau, `2` immergé, `3` en l'air,
  `8` endormi.

Pour une transformation, le serveur envoie deux variantes du même payload : `silent = false`
aux joueurs à moins de 32 blocs dans la même dimension, qui entendent le cri et voient les
particules, et `silent = true` à tous les autres, qui ne reçoivent que le modèle.
`fromNpc = true` supprime le cri et les particules sans condition.

Chaque envoi est protégé par `ServerPlayNetworking.canSend()`. Un client vanilla ne reçoit
jamais ces paquets et n'est jamais déconnecté faute de les connaître ; il voit simplement
un joueur invisible.

`AttackAbilityPayload.hasAnimation` est pris tel quel. Un client modifié qui envoie toujours
`false` saute la préparation de 1,3 seconde et tire aussitôt. Le cooldown est imposé par le
serveur : le gain porte sur le moment du tir, pas sur les dégâts par seconde.

## Mixins

Dix mixins client, tous avec `require = 0` et `"defaultRequire": 0`. Ce réglage signifie
qu'un mixin dont la cible est introuvable est **ignoré sans bruit** au lieu de faire planter
le jeu. Le mod tolère ainsi les autres mods qui touchent aux mêmes méthodes, mais une
injection cassée ne se voit que par une fonctionnalité manquante, sans aucune erreur dans
le journal.

| Mixin | Cible | Injection | Raison d'être |
|---|---|---|---|
| `TransformModelMixin` | `PlayerEntityRenderer.render` | `HEAD`, annulable | dessine le Pokémon fantôme à la place d'un joueur transformé ; masque le modèle local au zoom 0 |
| `NpcTransformModelMixin` | `EntityRenderDispatcher.render` | `HEAD`, annulable | même chose pour les PNJ transformés ; voir [les transformations de PNJ](npc-transforms.fr.md#comment-le-client-le-dessine) |
| `PlayerNametagMixin` | `EntityRenderer.renderLabelIfPresent` | `HEAD`, annulable | masque le nametag de tout UUID transformé, joueur ou PNJ |
| `TransformArmMixin` | `PlayerEntityRenderer.renderRightArm` / `renderLeftArm` | `HEAD`, annulable | pas de bras à la première personne sur un Pokémon |
| `TransformCameraMixin` | `Camera.update` | `TAIL` | recentre l'orbite de la troisième personne sur le corps du Pokémon (55 % de sa hauteur plus 0,55) et applique le zoom à la molette |
| `TransformScrollMixin` | `Mouse.onMouseScroll` | `HEAD`, annulable | transforme la molette en zoom de caméra quand le joueur est transformé |
| `AttackInputMixin` | `MinecraftClient.doAttack` | `HEAD`, annulable | transforme le clic gauche en `AttackAbilityPayload` |
| `TransformStepSoundMixin` | `Entity.playStepSound` | `HEAD`, annulable | pas de bruits de pas de joueur sous un Pokémon |
| `TransformFovMixin` | `AbstractClientPlayerEntity.getFovMultiplier` | `HEAD`, annulable | supprime la variation de champ de vision au sprint |
| `ShinyChimeMuteMixin` | `SoundManager.play(SoundInstance)` | `HEAD`, annulable | coupe le carillon shiny de Cobblemon à moins de 4 blocs d'un joueur transformé en shiny |

Toutes les cibles sont des méthodes de Minecraft vanilla, nommées en Yarn et remappées par
Loom à la compilation. Aucune ne vise une classe de Cobblemon, ce qui rend les mixins
indépendants de sa version. Le couplage avec Cobblemon passe par la réflexion.

`ShinyChimeMuteMixin` reconnaît le son par son identifiant (espace de noms `cobblemon`,
chemin contenant `shiny` et `chime`) et par la distance. Un vrai Pokémon shiny à moins de
4 blocs d'un joueur transformé en shiny est coupé lui aussi ; le commentaire de la classe
assume ce compromis.

## La réflexion et pourquoi les noms de méthode comptent

CobbleShift n'importe jamais une classe de Cobblemon. `build.gradle` n'a aucune dépendance
vers Cobblemon et `fabric.mod.json` se contente de le suggérer (`suggests`). Tout accès
passe par `Class.forName(...)` et `getMethod(...)`, dans `SpeciesMapper`, `AbilityManager`
et `TransformRenderer`. C'est pour cela que le jar se charge sur un serveur sans Cobblemon,
et que chaque recherche d'espèce y échoue au lieu de planter.

Ces recherches contiennent deux sortes de noms, qui se comportent très différemment dans
un jar de production.

**Les noms propres à Cobblemon peuvent être écrits en dur.** `PokemonSpecies.INSTANCE`,
`getByName`, `getBaseStats`, `getPrimaryType`, `getHitbox`, `getBaseScale`,
`PokemonEntity.getDelegate`, `PokemonProperties.Companion` : ce sont les noms du code
source Kotlin de Cobblemon. Cobblemon les livre non obfusqués et Fabric ne remappe pas les
classes d'un autre mod : la chaîne du code correspond à la méthode à l'exécution. Ce qui les
casse, c'est une mise à jour de Cobblemon qui renomme ou supprime une méthode. Le code s'y
attend : la plupart des recherches essaient plusieurs candidats à la suite (`getSpecies`,
puis `getAll`, puis `species` ; `getParty(ServerPlayerEntity)`, puis `getParty(UUID)`), et
chaque échec passe au suivant.

**Les noms de Minecraft ne le peuvent pas.** À l'exécution sur un serveur Fabric, les
membres de Minecraft portent des noms intermediary (`field_5957`, `method_5674`), pas des
noms Yarn. Une chaîne Yarn passée à `getDeclaredField` fonctionne en environnement de
développement et échoue en production. `TransformRenderer` traite ce problème à deux
endroits, avec deux niveaux de soin :

- `notifyTrackedDataSet()` essaie `onTrackedDataSet`, `method_5674` et `m_7350_` par leur
  nom, puis se rabat sur une **recherche par signature** : la première méthode déclarée
  d'`Entity` qui prend un seul paramètre `TrackedData` et renvoie `void`. Ce repli est le
  bon modèle.
- `syncWaterState()` essaie les noms Yarn et intermediary de `touchingWater`,
  `submergedInWater` et `submergedFluidTag`, puis se rabat sur **l'ordre de déclaration** :
  les deux premiers champs `boolean` protégés et non statiques d'`Entity` sont supposés être
  les deux indicateurs d'eau, et le premier `Set` privé et non statique est supposé être
  l'ensemble des tags de fluide. Le code lit la valeur de chaque champ sur le joueur puis
  l'ignore : rien ne vérifie la supposition. Une mise à jour de Minecraft qui ajoute un
  booléen protégé avant ces champs fait lire le mauvais indicateur au fantôme, et les
  animations aquatiques se dérèglent sans bruit. Les champs retenus sont journalisés une
  fois, sous la forme `[CobbleShift] Water sync: touchingWater=…`, la ligne à vérifier après
  une mise à jour de Minecraft.

La règle pour un contributeur : un nom de Cobblemon peut s'écrire sous forme de chaîne, un
nom de Minecraft jamais. Utilise directement le symbole Yarn dans le code ordinaire, que
Loom remappe, et quand la réflexion est inévitable, reconnais la méthode par ses types de
paramètres et de retour.

Un cache demande de l'attention. `SpeciesMapper.getAllSpeciesNames()` remplit
`cachedSpeciesNames` au premier appel réussi et ne le rafraîchit jamais. Si ce premier appel
a lieu avant que Cobblemon ait chargé ses espèces, la liste d'autocomplétion reste vide, ou
incomplète, jusqu'à un redémarrage. Les espèces ajoutées par un rechargement de datapack
manquent aussi à la complétion, même si elles se résolvent quand on les tape.

## L'API externe de fait

Deux mods frères pilotent CobbleShift par réflexion, sans dépendance à la compilation :

| Appelant | Membres atteints par réflexion |
|---|---|
| CheeseGuard `logic/CobbleShiftHook` | `CobbleShiftMod.INSTANCE`, `getTransformManager()`, `transform(ServerPlayerEntity, String)`, `transform(ServerPlayerEntity, String, boolean)`, `revert(ServerPlayerEntity)`, `revertAll()` |
| CheeseKart `CobbleShiftProxy` | `CobbleShiftMod.INSTANCE`, `getTransformManager()`, `transform(ServerPlayerEntity, String)`, `revert(ServerPlayerEntity)` |

Ces six membres forment une API publique, qu'ils aient été conçus comme tels ou non.
Renommer `getTransformManager`, changer un type de paramètre ou remplacer `INSTANCE` par un
getter compile sans erreur ici et désactive sans bruit les quatre effets d'événement de
transformation de CheeseGuard, son drapeau de zone `cobbleshift-transform`, et les pilotes
transformés de CheeseKart. Les deux appelants journalisent une ligne `not installed` au
niveau info quand la recherche échoue : c'est la seule trace, dans leurs journaux de
démarrage.

La surcharge booléenne est celle par laquelle CheeseGuard transmet `noattacks`. La surcharge
shiny-et-forme, `transform(ServerPlayerEntity, String, boolean, String)`, n'est atteinte par
personne ; son `boolean` est le drapeau shiny, pas celui des attaques, une confusion facile
pour un appelant.

`CobbleShiftHook` contient aussi `getActiveTransformSpecies()`, qui cherche
`getTransformData(UUID)` sur le gestionnaire. Cette méthode n'existe pas (l'accesseur
s'appelle `getTransform(UUID)`) : la recherche échouerait toujours. Personne n'appelle cette
méthode, elle n'a donc aucun effet aujourd'hui.

Le gestionnaire de drapeau de CheeseGuard indique en commentaire qu'une transformation laisse
derrière elle un vol accordé « pour les espèces volantes », et retire les effets et le vol
après l'annulation. CobbleShift
n'accorde le vol nulle part : ce nettoyage est du code défensif dans CheeseGuard.

Côté dashboard, c'est l'inverse : CobbleShift compile contre CheeseDash (`modImplementation`
sur le jar de CheeseDash) et implémente son interface `DashboardModule`. Ce code ne se
charge qu'à travers l'entrypoint `cheesedash` : le jar fonctionne donc sans CheeseDash.

## Points d'extension

- **Une nouvelle source de transformation.** Appelle `TransformManager.transform(...)` et
  `revert(...)` sur le thread serveur. Toutes les surcharges aboutissent à celle à quatre
  arguments.
- **Un nouvel effet de type.** Ajoute ou modifie une entrée d'`AbilityType`, et un cas dans
  `TypeProjectileEntity` pour l'effet à l'impact. Les deux s'appuient sur l'identifiant de
  type en minuscules.
- **Une nouvelle clé de config.** Ajoute le champ, une ligne dans `load()`, une ligne dans
  `toJson()`, un setter avec sa borne, et une branche dans `ShiftApi.handleUpdateConfig`.
  C'est en oubliant la dernière que les clés Hot Potato sont devenues lisibles et non
  modifiables.
- **Une nouvelle route REST.** Ajoute une branche dans `ShiftApi.handle()`. Protège les
  écritures avec `requireWrite` ou `requireAdmin` sous la forme avec retour, et enveloppe
  les modifications du jeu dans `executeOnServerThread`.
- **Brancher `autoRevertOnDeath`.** Enregistre `ServerLivingEntityEvents.AFTER_DEATH` dans
  `onInitialize()` et appelle `transformManager.onPlayerDeath(player)` pour les joueurs.

## Faiblesses connues

Sans détour, à peu près dans l'ordre de ce qu'elles coûtent en jeu.

- **La mort n'annule pas la transformation.** `autoRevertOnDeath` vaut `true` par défaut et
  n'a aucun appelant. Voir [la configuration](configuration.fr.md#clés-qui-ne-font-rien).
- **Les clients vanilla ne voient rien.** Un joueur transformé est invisible pour qui n'a
  pas le mod. Il n'existe aucun rendu de repli.
- **La barre d'attaque client dure toujours 30 ticks**, sans rapport avec le vrai cooldown du
  serveur : le multiplicateur de cooldown n'a guère d'effet sous 1.0 et provoque des clics
  perdus au-dessus. Voir [la configuration](configuration.fr.md#attaques).
- **Les valeurs du fichier de config contournent toutes les bornes**, et
  `abilityTickInterval: 0` fait planter le serveur dès son premier tick. Voir
  [plages et bornes](configuration.fr.md#plages-et-bornes).
- **Des modifications hors du thread serveur** dans quatre gestionnaires REST, listées dans
  [threading](#threading).
- **Le fichier de sauvegarde des PNJ peut être tronqué** par une seule entrée mal formée, et
  garde à jamais les entrées des PNJ supprimés. Voir
  [les transformations de PNJ](npc-transforms.fr.md#le-fichier-de-sauvegarde).
- **Le client ne vide jamais son état.** `TransformRenderer.clear()` existe et n'a aucun
  appelant ; rien n'est enregistré sur la déconnexion du client. Un joueur qui quitte le
  serveur en étant transformé garde son propre UUID dans la map du client, et s'il rejoint
  un autre serveur ou un monde solo dans la même session de jeu, sa caméra est forcée à la
  troisième personne et son modèle remplacé, jusqu'au redémarrage du jeu. Constaté à la
  lecture du code, pas reproduit.
- **Une ligne de journal trompeuse.** Sans Cobblemon, `SpeciesMapper` journalise
  `Cobblemon not found — using fallback stats`. Il n'y a aucune statistique de repli :
  chaque recherche ne renvoie rien.
- **Des champs morts.** `TransformData.locked` n'est jamais positionné. `hotPotatoEnabled`
  et `hotPotatoPassRange` ne sont jamais lus. `NpcTransformManager.setServer()` n'est jamais
  appelé.
- **`mapProjectileCount()` contredit `spawnProjectiles()`** sur le palier de BST 350-449 :
  `/cshift species` et l'API sous-estiment ce palier. Voir
  [le modèle de transformation](transforms.fr.md#attaques).
- **Du français codé en dur** à deux endroits non traduits : les noms affichés
  d'`AbilityType` et la description de la barre latérale du dashboard,
  `Transformations et mini-jeux`.
- **Une icône manquante.** `fabric.mod.json` pointe vers `assets/cobbleshift/icon.png`, qui
  n'existe pas ; les menus de mods affichent une icône par défaut.
- **Un dossier de données égaré.** `CobbleShift/data/cobblemon/species/` contient deux
  fichiers d'espèces Cobblemon (Dracaufeu, Lougaroc) en dehors de `src/main/`. Ils ne sont
  pas embarqués dans le jar et rien n'y fait référence. Ils ressemblent à des copies de
  référence oubliées pendant le développement.

Le mod a tourné sur le serveur Arkensia ; aucun des points ci-dessus ne vient d'un test
automatisé, car le projet n'en a aucun.

---

← [API du dashboard](dashboard.fr.md) · [Dépannage](troubleshooting.fr.md) →
