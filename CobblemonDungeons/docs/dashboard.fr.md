[← CobblemonDungeons](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](dashboard.md)

# Intégration dashboard

CobblemonDungeons enregistre un module CheeseDash via l'entrypoint `cheesedash` de son
`fabric.mod.json` :

```json
"cheesedash": ["com.sebastian.cobblemondungeons.dashboard.CobblemonDungeonsDashboardModule"]
```

Le module déclare le préfixe `dungeons` : toutes les routes ci-dessous vivent sous
`/api/v1/dungeons/`. CheeseDash sert le HTTP sur le port **44311** et le WebSocket sur
**44312**.

L'authentification est celle de CheeseDash : `POST /api/v1/auth/login` renvoie un token de
session à passer en `Authorization: Bearer <token>` sur chaque autre requête. Le module
répond `503` avec `{"error":"CobblemonDungeons not ready"}` à toute requête arrivée avant que
`onServerStart` ne lui ait donné le `MinecraftServer`, ou après `onServerStop`.

---

## ⚠️ Toutes les routes sont accessibles à tous les rôles authentifiés

`DungeonsApi` n'appelle jamais `WebDashboard.requireWrite` ni `WebDashboard.requireAdmin`.
Pas une seule fois, nulle part dans le fichier. CheeseDash vérifie qu'une session existe
avant de router vers un module, mais la vérification du rôle est la responsabilité de chaque
module, et ce module ne l'effectue pas.

L'effet pratique : un compte `READ_ONLY` peut créer, modifier et supprimer des
configurations de donjons, réécrire des fichiers de dresseurs, changer les modèles de
tickets, déclencher un rechargement, faire échouer de force une partie en cours et expulser
un joueur d'un donjon. Huit routes mutantes, aucun contrôle de rôle :

| Route | Ce qu'une session READ_ONLY peut en faire |
|---|---|
| `POST /configs` | Créer un donjon |
| `PUT /configs/:id` | Écraser toute la configuration d'un donjon |
| `DELETE /configs/:id` | Supprimer un donjon et son fichier |
| `POST /configs/reload` | Recharger toutes les configurations depuis le disque |
| `PUT /tickets` | Réécrire les modèles de tickets |
| `PUT /trainers/:id` | Écrire n'importe quel fichier JSON de dresseur |
| `POST /active/fail` | Faire échouer de force un donjon en cours |
| `POST /active/leave` | Retirer un joueur d'un donjon en cours |

Le correctif, pour qui s'en chargera, tient en une ligne au début de chaque gestionnaire
mutant :

```java
if (!WebDashboard.requireWrite(exchange)) return;
```

`requireWrite` admet `OPERATOR` et au-dessus et répond lui-même à la requête quand il
refuse : le retour immédiat est donc correct. En attendant que cela soit fait, traite tout
compte ayant accès au dashboard comme un opérateur pour ce module, et ne distribue pas de
session `READ_ONLY` en croyant qu'elle est en lecture seule.

La colonne « rôle » des tableaux ci-dessous consigne ce que le code impose aujourd'hui, pas
ce que chaque route devrait exiger.

## Les configurations de donjons

### `GET /configs`

Résumés de chaque donjon enregistré. Rôle : toute session authentifiée.

```json
[
  {
    "id": "generic_dungeon",
    "name": "Generic Dungeon",
    "icon": "minecraft:paper",
    "tier": 1,
    "teleportMode": "CROSS_SERVER",
    "activeInstances": 0,
    "activePlayers": 0,
    "slotsTotal": 2,
    "timeLimitSeconds": 1800,
    "partyLimit": 5
  }
]
```

`slotsTotal` vaut `1 + additionalLocations.length`. `activeInstances` et `activePlayers`
sont comptés en direct depuis les tables d'instances.

### `GET /configs/:id`

Le `DungeonConfig` complet du donjon, sérialisé par Gson exactement comme le serait le
fichier sur le disque. Rôle : toute session authentifiée. `404` avec
`{"error":"Dungeon not found"}` si l'identifiant est inconnu.

Les identifiants dans le chemin sont validés : un identifiant contenant `..`, `/`, `\` ou
`%`, ou long de 128 caractères ou plus, est rejeté avec `400` et
`{"error":"Invalid config ID"}`.

### `POST /configs`

Crée un donjon. Rôle : **toute session authentifiée** (voir l'avertissement ci-dessus).

```json
{ "id": "ruins_of_arkensia", "name": "Ruins of Arkensia" }
```

| Champ | Obligatoire | Défaut |
|---|---|---|
| `id` | oui | — |
| `name` | non | retombe sur `id` |

Le reste de la configuration reçoit les valeurs par défaut de `DungeonConfig`, avec une
surcharge : `networking.teleportMode` est forcé à `SAME_SERVER`, parce que le dashboard ne
peut pas connaître la topologie de ton proxy. Le donjon est enregistré et écrit dans
`config/cobblemon_dungeons/dungeons/<id>.json` sur le thread serveur.

Réponses : `200` avec la configuration complète, `400` `{"error":"id is required"}` pour un
identifiant absent ou vide, `409` `{"error":"Dungeon ID already exists: <id>"}`, `500` avec
le message de l'exception.

### `PUT /configs/:id`

Remplace toute la configuration. Rôle : **toute session authentifiée**. Le corps est un
document `DungeonConfig` complet ; tout ce que tu omets revient à la valeur par défaut du
champ, donc il s'agit d'un remplacement et non d'une fusion. Le `general.id` du corps est
ignoré — l'identifiant du chemin gagne toujours, donc un donjon ne peut pas être renommé par
cette route.

La nouvelle configuration est enregistrée et sauvegardée sur le thread serveur. Les
instances en cours conservent l'objet `DungeonConfig` avec lequel elles ont été construites,
donc une modification ne perturbe pas une partie en cours.

Réponses : `200` avec la configuration stockée, `404` si inconnue, `400`
`{"error":"Invalid config JSON"}` si le corps ne se désérialise pas, `500` sur exception.

### `DELETE /configs/:id`

Supprime le fichier, puis exécute un `ConfigLoader.load()` complet pour reconstruire le
registre. Rôle : **toute session authentifiée**. Répond `200 {"deleted":true}`, ou `404` si
inconnu.

Une partie en cours du donjon supprimé continue : son instance détient son propre objet de
configuration.

### `POST /configs/reload`

`ConfigLoader.load()` sur le thread serveur : vide le registre, relit `config.json`,
`dungeon_tickets.json`, chaque `dungeons/*.json`, puis l'historique des parties, les
cooldowns et le classement. Rôle : **toute session authentifiée**. Répond
`200 {"reloaded":true}`.

Les dresseurs ne sont **pas** rechargés — `TrainerLoader.load` ne tourne qu'au démarrage du
serveur.

## Les tickets

### `GET /tickets`

Le contenu brut de `dungeon_tickets.json`. Rôle : toute session authentifiée.

```json
{ "tickets": [ { "dungeonId": "generic_dungeon", "itemName": "§6Copper Dungeon Ticket", "tier": 1 } ] }
```

### `PUT /tickets`

Remplace la configuration des tickets et l'enregistre sur le disque. Rôle : **toute session
authentifiée**. Renvoie la configuration stockée.

Un corps qui n'est pas du JSON valide est avalé : `ConfigLoader.updateTicketConfig` capture
l'exception, journalise `Failed to update dungeon tickets config from server`, et la route
répond quand même `200` avec la configuration **inchangée**. Compare la réponse à ce que tu
as envoyé s'il te faut une certitude.

Les clients connectés ne sont pas resynchronisés ; ils prendront le changement en compte à
leur prochaine connexion.

## Les dresseurs

### `GET /trainers`

Liste les fichiers de `config/cobblemon_dungeons/trainers/`. Rôle : toute session
authentifiée.

```json
[ { "id": "example_trainer", "filename": "example_trainer.json" } ]
```

Un dossier absent donne un tableau vide.

### `GET /trainers/:id`

Le contenu brut du fichier `<id>.json`, servi tel quel. Rôle : toute session authentifiée.
`404` `{"error":"Trainer file not found"}` si le fichier n'existe pas.

### `PUT /trainers/:id`

Valide que le corps s'analyse comme du JSON, crée le dossier si besoin, et écrit le corps
tel quel dans `<id>.json`. Rôle : **toute session authentifiée**. Renvoie le corps en cas de
succès, `400` `{"error":"Invalid JSON: …"}` sinon.

Seule la syntaxe JSON est vérifiée — pas le fait que RCTApi puisse en faire un dresseur. Un
fichier syntaxiquement valide au schéma absurde est accepté ici et échoue au prochain
démarrage du serveur. Et comme les dresseurs ne se chargent qu'au démarrage, rien d'écrit
par cette route ne prend effet avant un redémarrage.

Les deux routes de dresseurs valident l'identifiant comme le font les routes de
configuration, donc le chemin ne peut pas sortir du dossier des dresseurs.

## Les parties en cours

### `GET /active`

Chaque instance vivante, dédoublonnée — la table d'instances étant indexée par joueur, un
groupe de quatre apparaîtrait sinon quatre fois. Rôle : toute session authentifiée.

```json
{
  "instances": [
    {
      "dungeonId": "generic_dungeon",
      "dungeonName": "Generic Dungeon",
      "tier": 2,
      "timeLeft": 1420,
      "lives": 5,
      "alphasDefeated": 1, "alphasRequired": 2,
      "gemsFound": 0, "gemsRequired": 3,
      "guardiansDefeated": 2, "guardiansRequired": 2,
      "stakesFound": 4, "stakesRequired": 4,
      "bossSpawned": false,
      "bossDefeated": false,
      "bossSpecies": "charizard",
      "startTime": 1761000000,
      "players": [
        { "uuid": "…", "name": "RoiCheese", "online": true }
      ]
    }
  ],
  "countdownCount": 0
}
```

`bossSpecies` n'est présent qu'une fois un boss apparu. `startTime` est en secondes Unix.
`countdownCount` est le nombre de joueurs dans le compte à rebours de 5 secondes avant
démarrage, pas un nombre d'instances.

### `POST /active/fail`

Fait échouer de force la partie dans laquelle se trouve le joueur donné. Rôle : **toute
session authentifiée**.

```json
{ "playerUuid": "0b1c…" }
```

Résout l'instance du joueur, puis exécute le chemin d'échec normal sur le thread serveur :
entrée d'historique avec `success: false`, diffusion `dungeon_fail`, nettoyage des entités,
et chaque membre averti *Donjon terminé de force par un administrateur.* avant d'être
évacué.

Réponses : `200 {"failed":true}`, `400 {"error":"playerUuid is required"}`, `500` avec le
message de l'exception. Un UUID bien formé mais ne correspondant à personne répond quand
même `200 {"failed":true}` — le travail est planifié et ne fait rien, silencieusement.

### `POST /active/leave`

Retire un seul joueur de sa partie, en y laissant le reste du groupe. Rôle : **toute session
authentifiée**. Même forme de corps. Le joueur voit *Vous avez été retiré du donjon par un
administrateur.* et passe par le chemin de sortie normal, ce qui, sur une partie non
terminée, signifie que sa progression dans le donjon n'est pas sauvegardée.

Réponses : `200 {"left":true}`, `400 {"error":"playerUuid is required"}`, `500` sur
exception. Exige que le joueur soit en ligne ; un UUID hors ligne est une opération sans
effet qui rapporte malgré tout un succès.

## Statistiques, historique, classement

### `GET /stats`

Agrégats calculés à la volée depuis l'historique des parties en mémoire. Rôle : toute
session authentifiée. Les définitions exactes sont dans
[classements et historique des parties](records.fr.md#les-statistiques).

```json
{
  "totalRuns": 42, "successCount": 17, "successRate": 40,
  "avgDurationSeconds": 643,
  "configCount": 3, "activeInstances": 1,
  "runsByDungeon": { "generic_dungeon": { "total": 42, "success": 17 } },
  "runsByTier": { "2": { "total": 42, "success": 17 } }
}
```

### `GET /stats/history?n=50`

Les parties les plus récentes, les plus récentes d'abord. Rôle : toute session
authentifiée.

| Paramètre | Type | Défaut | Plage |
|---|---|---|---|
| `n` | int | `50` | borné à 1–1000 ; une valeur illisible retombe sur 50 |

```json
{ "history": [ { "dungeonId": "…", "players": ["RoiCheese"], "durationSeconds": 612, "success": true, "…": "…" } ],
  "total": 42, "returned": 42 }
```

`total` est la taille de tout l'historique, `returned` le nombre d'entrées de cette réponse.

### `GET /leaderboard` et `GET /leaderboard/:dungeonId`

Rôle : toute session authentifiée. Les deux renvoient la même forme imbriquée —
identifiant de donjon, puis tier en clé de type chaîne, puis un tableau d'entrées triées du
plus rapide au plus lent :

```json
{
  "generic_dungeon": {
    "2": [ { "playerName": "RoiCheese", "playerUuid": "…", "durationSeconds": 612, "date": 1761000000 } ]
  }
}
```

La forme par donjon est un objet à une seule clé, pas un tableau nu. Un donjon sans temps
enregistré renvoie `{"<dungeonId>": {}}`.

## La configuration principale

### `GET /config`

Le contenu de `config.json`, avec `database.password` remplacé par `"***"`. Rôle : toute
session authentifiée.

```json
{ "database": { "useSql": false, "host": "localhost", "port": 3306,
                "database": "minecraft", "username": "root",
                "password": "***", "tablePrefix": "cobblemon_dungeons_" } }
```

En lecture seule — il n'y a pas de `PUT /config`. Les réglages de base de données s'éditent
dans le fichier et s'appliquent au redémarrage du serveur. Le nom d'utilisateur, l'hôte et
le préfixe de table ne sont **pas** masqués.

## Les routes inconnues

Tout ce qui ne correspond à rien de ce qui précède reçoit `404` avec
`{"error":"Unknown dungeons route: <route>"}`. Une méthode inadaptée pour un chemin avec
identifiant reçoit `405 {"error":"Method not allowed"}`. `GET /configs/reload` répond
spécifiquement `404 {"error":"Use POST /configs/reload"}`.

## Les messages WebSocket

Le mod diffuse sur le canal CheeseDash **`dungeons`**. Abonne-toi avec :

```json
{ "type": "subscribe", "channels": ["dungeons"] }
```

Cinq types de message existent, tous produits par le même constructeur et portant donc tous
le même payload :

| `type` | Envoyé quand |
|---|---|
| `dungeon_start` | Le compte à rebours est terminé et la partie d'un membre commence |
| `dungeon_player_death` | Un joueur est mort et une vie a été consommée |
| `dungeon_boss_spawn` | Le boss vient d'apparaître |
| `dungeon_complete` | Le boss a été vaincu |
| `dungeon_fail` | La partie a échoué — chronomètre, vies, ou échec forcé depuis le dashboard |

```json
{
  "type": "dungeon_complete",
  "dungeonId": "generic_dungeon",
  "dungeonName": "Generic Dungeon",
  "tier": 2,
  "players": ["RoiCheese", "Alex"],
  "timeLeft": 1105,
  "lives": 4,
  "bossSpecies": "charizard"
}
```

`bossSpecies` est omis tant qu'aucun boss n'est apparu. Il n'y a pas d'horodatage dans le
payload : utilise l'heure de réception.

Deux réserves tirées du code. `dungeon_start` est diffusé une fois **par membre** du groupe,
parce qu'il part depuis la routine de démarrage par joueur : un groupe de quatre produit
donc quatre messages identiques. Et la diffusion est enveloppée dans un
`catch (Exception) {}` nu, après une garde `FabricLoader.isModLoaded("cheesedash")` : une
diffusion qui échoue est silencieuse, donc si les messages cessent d'arriver, rien
n'apparaîtra dans le log.

## Effets d'événement CheeseGuard

CheeseGuard pilote ce mod pendant ses événements minutés via trois types d'effet de
timeline, tous routés par `CobblemonDungeonsHook` dans
`CheeseGuard/src/main/java/com/cheeseguard/logic/CobblemonDungeonsHook.java`. Le hook passe
par la réflexion — CheeseGuard n'a aucune dépendance de compilation vers ce mod — et il est
initialisé une fois depuis `EventManager.init()`. En cas de succès il journalise
`[CG] CobblemonDungeons detected — dungeon effects enabled` ; sans le mod,
`[CG] CobblemonDungeons not installed — dungeon effects disabled`, et les trois effets
deviennent inertes.

| Effet | Propriétés | Ce qu'il fait |
|---|---|---|
| `DUNGEON_OPEN` | `dungeonId`, `dungeonTier` (défaut `1`) | Appelle `DungeonManager.startDungeon(leader, id, tier)` pour le **premier** participant seulement, en tant que chef ; les autres suivent comme son groupe |
| `DUNGEON_TICKET_GIVE` | `dungeonId`, `dungeonTier` (défaut `1`), `ticketCount` (défaut `1`) | Construit un ticket par participant via `DungeonTicketItem.createStack`, inséré dans l'inventaire ou lâché à ses pieds |
| `DUNGEON_MODIFIER` | `dungeonId`, `modifierType` (défaut `DOUBLE_LOOT`), `modifierValue` (défaut `2.0`) | Journalise et annonce, et rien d'autre |

`DUNGEON_OPEN` vérifie d'abord que le donjon existe et avertit
`[CG-TL] DUNGEON_OPEN: dungeon '<id>' not found` sinon. Les vérifications d'entrée normales
s'appliquent toujours, ticket compris — l'effet ne les contourne pas, donc un participant
sans ticket n'entre simplement pas.

⚠️ `DUNGEON_MODIFIER` ne modifie rien. Son propre commentaire de code le dit : l'effet stocke
des métadonnées et diffuse une notification, et l'intégration avec `DungeonManager` est
listée comme travail futur. Il apparaît dans la liste d'effets CheeseGuard et dans l'éditeur
de timeline, et il ne doublera pas ton butin.

Les tickets créés par `DUNGEON_TICKET_GIVE` sont nommés
`§6Dungeon Ticket [<dungeonId> T<tier>]`, construits à partir d'un modèle fabriqué dans le
hook plutôt que lu dans `dungeon_tickets.json`.

---

← [Classements et historique des parties](records.fr.md) · [Architecture](architecture.fr.md) →
