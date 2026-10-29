[← CheeseGuard](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](events.md)

# Événements

Un *événement* CheeseGuard est une animation chronométrée à l'échelle du serveur, avec une
liste de participants, une table de scores, un cycle de vie et, si tu veux, une chronologie
scénarisée. C'est le sous-système qui transforme une zone statique en arène de tournoi, en
concours de pêche ou en battle royale.

Les événements vivent dans `config/cheeseguard_events.json`. `EventManager`
(`logic/EventManager.java`) les détient en mémoire, les tique une fois par seconde depuis le
thread serveur, et réécrit le fichier à chaque mutation significative. La vue Événements du
dashboard est le seul éditeur pris en charge — il n'existe aucune interface en jeu pour
construire un événement.

## Le modèle

Chaque événement est un `ServerEvent` (`data/ServerEvent.java`). Les champs se répartissent
en groupes, et tu touches rarement plus de deux ou trois groupes pour un événement donné.

| Groupe | Champs | Ce qu'il décide |
|---|---|---|
| Identité | `id`, `name`, `description`, `displayColor` | ce que les joueurs voient dans les annonces et la bossbar |
| Mode | `mode`, `miniGameType`, `miniGameConfig` | événement serveur simple, ou mini-jeu piloté par `MiniGameManager` |
| Déclencheur | `trigger`, `scheduledDelayMinutes`, `recurringIntervalMinutes`, `lunarEventId`, `triggerZoneId`, `playerLaunchable` | comment l'événement démarre sans administrateur |
| Minutage | `announceDurationSeconds`, `countdownSeconds`, `cooldownMinutes`, `lastRunTimestamp` | la durée de chaque phase de pré-démarrage et l'écart entre deux exécutions |
| Portée | `scopeZoneIds` | les zones auxquelles les effets s'appliquent (vide = tout le serveur) |
| Participation | `minParticipants`, `maxParticipants`, `teamMode`, `teamSize`, `deathBehavior`, `endOnAllDead` | qui peut rejoindre et ce qui arrive à la mort |
| Messages | `announceMessage`, `startMessage`, `endMessage` et leurs couleurs, `bossbarEnabled`, `bossbarColor`, `discordNotify` | le bruit côté joueurs |
| Téléportations | `tpParticipant*`, `tpSpectator*`, `tpParticipantZoneIds`, `tpSpectatorZoneIds`, `returnMode`, `return*` | où les gens vont au départ et où ils reviennent |
| Inventaire | `saveInventory`, `forceEmptyInventory`, `kitEntryCommands`, `kitExitCommands`, `blacklistItems`, `blacklistEnchants`, `blacklistPokemon`, `disablePokemonSummon` | le contrat d'équipement |
| Victoire | `victoryCondition`, `victoryTargetScore`, `scoreTriggers`, `rewards` | comment on gagne des points et qui est payé |
| Structure | `useActSystem`, `acts`, `phases` | le contenu scénarisé — voir [Chronologie](timeline.fr.md) |
| Enchaînement | `chainEventId`, `exclusionGroup`, `exclusionPriority` | ce qui suit, et ce qui ne doit pas tourner en même temps |

`maxParticipants = 0` signifie illimité, pas « personne ». `scopeZoneIds` vide signifie
global, pas « aucune zone ». Les deux valeurs par défaut sont volontaires : un événement neuf
est ouvert et couvre tout le serveur.

### Énumérations

```text
EventMode          SERVER_EVENT | MINIGAME
TriggerType        MANUAL | SCHEDULED | RECURRING | LUNAR | ZONE_ENTRY
TeamMode           SOLO | DUOS | TEAMS | COOP
VictoryCondition   TIMER | SCORE_MAX | FIRST_TO_X | SURVIVAL | COOP_GOAL
DeathBehavior      RESPAWN_EVENT | BECOME_SPECTATOR
```

`LUNAR` ne se déclenche que si Enhanced Celestials est installé ; `EventManager` le cherche
au démarrage et, si le mod est absent, un événement `LUNAR` ne se déclenche jamais tout seul.
Rien n'est consigné à chaque tick à ce sujet : c'est donc la première chose à vérifier quand
un événement « ne part jamais ».

## Cycle de vie

`ServerEvent.State` vaut :

```text
IDLE | ANNOUNCED | QUEUE | COUNTDOWN | ACTIVE | ENDING | CLEANUP
```

Il n'existe **aucun état `PAUSED`**, et il n'y a nulle part de pause ni de reprise dans le
mod — ni dans l'API, ni dans les commandes, ni dans le gestionnaire. Un événement lancé va
jusqu'au bout ou se fait arrêter net. Si tu as besoin d'une respiration au milieu d'une
exécution, c'est à ça que sert un acte en transition `MANUAL`
([Chronologie](timeline.fr.md#transitions-dacte)).

Les états que le code attribue réellement sont `IDLE`, `ANNOUNCED`, `COUNTDOWN`, `ACTIVE` et
`ENDING`. `QUEUE` et `CLEANUP` sont déclarés dans l'énumération mais aucun chemin de code ne
les affecte : la file d'attente est suivie à part par `MiniGameManager`, et le nettoyage
s'exécute de façon synchrone dans `cleanupEvent()` en laissant l'événement en `IDLE`.
Considère ces deux constantes comme réservées.

Les transitions, telles que `EventManager.tickEvent()` les pilote à 1 Hz :

| De | Vers | Quand |
|---|---|---|
| `IDLE` | `ANNOUNCED` | `announceEvent()` — API, commande, ou déclencheur automatique satisfait |
| `IDLE` | `ACTIVE` | `startEventDirect()` — saute entièrement l'annonce et le décompte |
| `ANNOUNCED` | `COUNTDOWN` | après `announceDurationSeconds`, si `participantCount >= minParticipants` |
| `ANNOUNCED` | `IDLE` | après `announceDurationSeconds` avec trop peu de participants — annonce et annulation |
| `COUNTDOWN` | `ACTIVE` | le compteur par seconde atteint zéro |
| `ACTIVE` | `ENDING` | `endEvent()`, ou une condition de victoire remplie |
| `ENDING` | `IDLE` | 5 secondes après l'entrée en `ENDING`, via `cleanupEvent()` |

Le décompte est décrémenté une fois par tick à 1 Hz plutôt que comparé à l'horloge : un
serveur qui rame étire le décompte au lieu de sauter des chiffres. Les titres sont envoyés
aux participants à 30, à 10, puis chaque seconde à partir de 5.

`isRunning()` vaut `state != IDLE`. `isActive()` vaut `state == ACTIVE`. Le résumé exposé par
le dashboard donne les deux, parce que « running » couvre les phases de pré-démarrage et
« active » non.

### Déclencheurs automatiques

Toutes les 5 secondes, `tickAutoTriggers()` parcourt les événements en `IDLE` et décide s'il
faut les annoncer. Avant de regarder le déclencheur, deux filtres s'appliquent :

1. **Cooldown** — si `cooldownMinutes > 0` et que la dernière exécution est plus récente que
   cela, on passe.
2. **Groupe d'exclusion** — si `exclusionGroup` n'est pas vide et qu'un *autre* événement du
   même groupe tourne, on passe.

Ensuite, selon le type : `RECURRING` annonce quand `recurringIntervalMinutes` s'est écoulé
depuis la dernière exécution (ou tout de suite s'il n'a jamais tourné) ; `SCHEDULED` annonce
une fois `scheduledDelayMinutes` écoulé depuis la dernière exécution, ou depuis le démarrage
du serveur à défaut ; `LUNAR` annonce pendant que l'événement Enhanced Celestials nommé est
actif ; `ZONE_ENTRY` annonce dès qu'un joueur connecté se trouve dans `triggerZoneId`.
`MANUAL` ne fait rien ici, par construction.

Un événement en mode `MINIGAME` dont la file d'attente n'est pas vide est également annoncé
quand `MiniGameManager.checkQueueReady()` estime qu'il y a assez de joueurs.

### Nettoyage

`cleanupEvent()` est la partie à lire avant de modifier quoi que ce soit, parce que l'ordre
compte et qu'il résulte de corrections successives :

1. Arrêter l'exécuteur de chronologie.
2. Tuer les mobs survivants de la foudre de fin, si `lightningMobsOnEnd` était actif.
3. Quitter le groupe de chat vocal, si `voiceChatGroup` était actif.
4. Exécuter `kitExitCommands` **pendant que les joueurs sont encore dans le monde de
   l'événement**, pour qu'une commande comme `effect clear @s` s'applique au bon endroit.
5. Restaurer les inventaires sauvegardés ; pour les joueurs partis en cours d'événement,
   écrire une restauration en attente sur le disque.
6. Distribuer les récompenses — **après** la restauration d'inventaire, pour que les prix ne
   soient pas effacés par elle.
7. Téléporter les participants selon `returnMode`.
8. Retirer la bossbar et le scoreboard.
9. Horodater `lastRunTimestamp`, annoncer l'événement chaîné si `chainEventId` est renseigné,
   réinitialiser les champs d'exécution, sauvegarder.

### Résistance aux crashs

Quand `saveInventory` est actif, l'inventaire de chaque participant est écrit sur le disque
en NBT, dans `config/cheeseguard_pending_inv/<uuid>.nbt`, à son entrée, et pas seulement gardé en mémoire. Si le serveur meurt en cours
d'événement, la restauration est rejouée à la prochaine connexion du joueur :
`tickPendingRestores()` réessaie toutes les 3 secondes tant qu'il reste quelque chose en
attente. Les spectateurs victimes d'un crash reçoivent un enregistrement allégé qui ne
restaure que leur mode de jeu, et un retour arrière CobbleShift est mis en file pour eux afin
qu'ils ne revienne pas sous forme de Pokémon.

## Participants

Un participant est un `EventParticipant` (`data/EventParticipant.java`), indexé par UUID. La
collection est une `Map<UUID, EventParticipant>` — `evt.getParticipants()` renvoie la map, et
l'idiome d'itération partout dans le code est donc `getParticipants().values()`. C'est vrai
aussi du helper du dashboard : `EventsApi.getParticipants()` renvoie une **`Map`**, et non une
`List`. Du code qui suppose une liste ne compile pas contre le bon type et casse au premier
`for`.

Les rôles sont `PARTICIPANT` et `SPECTATOR`. L'inscription n'est acceptée que pendant
`ANNOUNCED`, `COUNTDOWN` ou `ACTIVE` — `joinEvent()` renvoie `false` en dehors de ces trois
états (ou si le joueur est déjà inscrit), et l'API traduit cela en `500` avec
`java.lang.RuntimeException: Failed to add participant`. L'API exige aussi que le joueur soit
connecté. Une inscription `PARTICIPANT` devient sans prévenir une inscription `SPECTATOR`
quand `maxParticipants` est atteint, et quand un événement mini-jeu est déjà `ACTIVE` (les
rôles sont attribués au départ) ; le joueur en est averti dans le chat.

Les spectateurs sont protégés de deux petites façons faciles à rater : tout ce qui chevauche
un spectateur est démonté une fois par seconde, et le mode de jeu d'origine d'un spectateur
fait partie de l'enregistrement de reprise après crash.

## Scores

Les points viennent de trois directions.

**Les déclencheurs de score.** `ServerEvent.ScoreTrigger` associe un type de déclencheur, un
filtre et une valeur en points. Les types :

```text
KILL_MOB | KILL_POKEMON | CATCH_POKEMON | FISH_CATCH | FISH_POKEMON | BLOCK_BREAK
BLOCK_PLACE | ITEM_PICKUP | PROJECTILE_HIT | ENTER_ZONE | PLAYER_KILL
STAY_IN_ZONE | REACH_ZONE | CRAFT_ITEM | WIN_BATTLE | CUSTOM_COMMAND
```

`filter` restreint le déclencheur à une entité, un item ou une zone précis ; laissé vide, il
accepte n'importe quoi de cette catégorie. La détection se fait par des mixins —
`EventMobKillMixin`, `EventBlockBreakMixin`, `EventFishCatchMixin`, `EventCraftItemMixin` et
les autres — c'est pourquoi le score fonctionne sur les actions vanilla sans aucune
plomberie de commandes.

**Les multiplicateurs.** `EventManager.addScore()` multiplie les points par les effets
`MULTIPLIER` de type `score` de la **phase historique** en cours. Un bloc `MULTIPLIER` de la
chronologie ne fait que stocker sa valeur sur l'événement (`setActiveMultiplier`), et rien ne
la relit : dans le système d'actes, il n'a donc aucun effet sur les scores. Voir
[Chronologie](timeline.fr.md).

**L'ajustement direct.** `POST /events/:id/score` avec `playerName`, `amount` et une
`operation` à `add` (par défaut), `subtract` ou `set`. C'est la main de l'administrateur : il
écrit directement le score du participant, donc aucun multiplicateur ne s'applique, et il
fonctionne dans n'importe quel état. `EventScoreHook` est autre chose — l'utilitaire
qu'appellent les mixins de détection pour appliquer les déclencheurs de score
correspondants de chaque événement `ACTIVE`.

`victoryCondition` décide à quoi sert le score :

| Condition | Termine l'événement quand |
|---|---|
| `TIMER` | la durée configurée s'écoule ; le meilleur score gagne |
| `SCORE_MAX` | la durée s'écoule ; comme `TIMER`, mais le scoreboard est le propos |
| `FIRST_TO_X` | un joueur atteint `victoryTargetScore` |
| `SURVIVAL` | il ne reste qu'un participant vivant |
| `COOP_GOAL` | le score *cumulé* atteint `victoryTargetScore` |

Les récompenses sont des `EventReward` : un `rank` valant `"1"`, `"2"`, `"3"`, `"top10"`,
`"all"` ou `"lottery"`, une liste de commandes avec le marqueur `{player}`, et `lotteryCount`
pour le rang loterie. Elles s'appliquent au classement figé à la fin de l'événement, pas à la
table en direct.

## Annonces

`announceEvent()` fait passer l'événement en `ANNOUNCED` et diffuse `announceMessage` dans la
couleur `announceColor`. De là jusqu'à la fin de l'événement, les joueurs peuvent rejoindre. La
bossbar, si `bossbarEnabled` est actif, est mise à jour chaque seconde pour tout événement
qui n'est pas en `IDLE`, et `discordNotify` envoie aussi l'annonce via
`logic/DiscordWebhook.java`.

Les trois messages — `announceMessage`, `startMessage`, `endMessage` — sont stockés sur
l'événement comme des chaînes littérales avec leurs propres noms de couleur, et diffusés avec
`Text.literal`. Ils ne sont donc pas traduits : ce que tu tapes dans le dashboard est ce que
voit chaque joueur, dans toutes les langues.

## Templates

Un template est une copie figée de la configuration d'un événement, enregistrée en JSON pour
reconstruire le même tournoi la semaine suivante sans ressaisir quarante champs.

Les templates vivent dans :

```text
config/cheeseguard_templates/
```

Pas `config/templates/`. Le dossier est créé à la première sauvegarde.

`EventTemplate.save()` sérialise l'événement, retire `lastRun`, et ajoute `templateName`,
`templateCreated` et `templateSource`. Le nom de fichier est le nom du template en minuscules
avec tout caractère hors de `[a-zA-Z0-9_-]` remplacé par `_` : `Tournoi d'été #2` devient donc
`tournoi_d__t___2.json`, et deux noms différents peuvent se retrouver sur le même fichier.
Garde des noms de template simples.

`EventTemplate.load()` lit le fichier, attribue un **nouvel** identifiant de la forme
`evt_<millis % 100000>`, remet `lastRun` à zéro et renvoie un `ServerEvent` neuf. Le modulo
limite l'espace d'identifiants à cinq chiffres : créer deux événements dans la même
milliseconde d'un cycle de 100 secondes peut produire le même id. En pratique cela n'a jamais
mordu, mais ce n'est pas un UUID et il ne faut pas le traiter comme tel.

Le chemin de chargement est aussi là où les deux routes diffèrent. `POST /events` avec un
`templateName` construit l'événement et le renvoie **sans l'ajouter au gestionnaire** — rien
n'est persisté. `POST /events/templates/:name/load` le construit, l'ajoute à la liste et
sauvegarde. La seconde est presque toujours celle qu'on veut ; la première ne sert qu'à
prévisualiser les champs résolus d'un template.

```text
[CG] Template saved: Tournoi Hiver
[CG] Template loaded: Tournoi Hiver -> event evt_48213
```

## Routes REST

La référence complète, avec les corps et les codes de statut, est dans
[API dashboard](dashboard.fr.md#événements). En résumé :

| Méthode | Chemin | Rôle |
|---|---|---|
| `GET` | `/events` | résumés de tous les événements |
| `POST` | `/events` | créer de zéro, ou résoudre un template |
| `GET` `PUT` `DELETE` | `/events/:id` | lire, modifier, supprimer un événement |
| `POST` | `/events/:id/announce` `start` `stop` | piloter le cycle de vie |
| `GET` `POST` | `/events/:id/participants` | lister, ajouter |
| `DELETE` | `/events/:id/participants/:uuid` | retirer |
| `POST` | `/events/:id/score` | ajuster le score d'un participant |
| `POST` | `/events/:id/save-template` | figer cet événement en template |
| `GET` | `/events/templates` | noms des templates |
| `POST` `DELETE` | `/events/templates/:name/load`, `/events/templates/:name` | charger, supprimer |
| `GET` | `/events/minigame-types` | le catalogue de mini-jeux |
| `GET` `PUT` | `/events/launch-config` | réglages de lancement par les joueurs |
| `GET` `POST` `DELETE` | `/events/prophunt/icons[/:key]` | stock d'icônes PropHunt |

`PUT /events/:id` est une modification partielle, pas un remplacement : seules les clés
présentes dans le corps sont appliquées. Envoyer `{"countdownSeconds": 5}` change le décompte
et rien d'autre. Les champs de liste font exception — `scopeZoneIds`,
`tpParticipantZoneIds`, `tpSpectatorZoneIds`, `kitEntryCommands`, `kitExitCommands`,
`blacklistItems` et `acts` sont *remplacés* quand ils sont présents, si bien qu'envoyer un
tableau vide supprime leur contenu.

La route ne lit qu'un ensemble fixe de clés. Plusieurs champs du modèle n'y ont aucun
setter — `scheduledDelayMinutes`, `recurringIntervalMinutes`, `lunarEventId`,
`triggerZoneId`, `scoreTriggers`, `rewards`, `chainEventId`, `exclusionPriority`,
`endOnAllDead`, `blacklistEnchants`, `blacklistPokemon`, les trois couleurs de message — et
le dashboard ne peut donc pas les modifier. Ils viennent du fichier JSON (édité serveur
arrêté) ou d'un template.

Une valeur d'énumération invalide dans un corps de `PUT` — `"mode": "MINIGAMES"`, par exemple
— lève une exception dans la tâche du thread serveur et ressort en `500` portant le message
enveloppé, `java.lang.IllegalArgumentException: No enum constant …`. L'événement reste
partiellement modifié en mémoire, car les setters précédant celui qui a échoué ont déjà
tourné ; le `save()` de fin de tâche est sauté, mais l'état à moitié modifié est écrit à la
sauvegarde suivante. Valide les énumérations côté client.

## Limites connues

- Les identifiants d'événements issus de templates ne sont pas à l'épreuve des collisions
  (voir plus haut).
- Un `PUT` qui échoue en milieu de course laisse l'événement à moitié modifié en mémoire, et
  la sauvegarde suivante le rend persistant.
- `QUEUE` et `CLEANUP` existent dans l'énumération d'états mais ne sont jamais affectés.
- `phases` est la structure historique, remplacée par `acts`. Rien ne migre les anciens
  événements : `ServerEvent.migrateToActSystem()` (bâtie sur `EventAct.fromLegacyPhase()`)
  existe mais n'a aucun appelant. Un événement dont la liste `acts` est vide exécute tout
  simplement ses `phases` par le chemin historique — et `POST /events` crée chaque nouvel
  événement avec une phase historique de 300 secondes, `Phase 1`, tant qu'aucun acte n'est
  ajouté.

---

← [Dimensions](dimensions.fr.md) · [Chronologie](timeline.fr.md) →
