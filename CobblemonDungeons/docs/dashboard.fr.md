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

## Ce que le cœur applique réellement

`DungeonsApi` ne contient aucun contrôle de rôle qui lui soit propre. Il n'appelle jamais
`WebDashboard.requireWrite` ni `WebDashboard.requireAdmin` — pas une seule fois, nulle part
dans le fichier, sur ses huit routes mutantes.

Ce qui tient lieu de ces contrôles, c'est le répartiteur de modules de CheeseDash, dans
`DashboardApi` :

```java
DashboardModule module = DashboardRegistry.getModule(prefix);
if (module != null) {
    if (!method.equals("GET") && !WebDashboard.requireWrite(exchange)) {
        return;
    }
    module.handleRequest(exchange, subRoute);
    ...
}
```

C'est la forme correcte — elle teste la valeur de retour et rend la main — donc dans cette
version, toute requête non-`GET` vers n'importe quelle route sous `/api/v1/dungeons/` exige
bien `OPERATOR` ou au-dessus, et les requêtes `GET` acceptent tous les rôles, `READ_ONLY`
compris. La frontière tient.

Ce qui ne tient pas, c'est la raison pour laquelle elle tient. La protection est une
propriété du répartiteur du cœur, fondée uniquement sur la méthode HTTP, et le module n'y
contribue en rien. Trois conséquences :

- **Aucune route de CobblemonDungeons ne peut exiger `SUPER_ADMIN`.** Supprimer un donjon et
  son fichier, écraser n'importe quel JSON de dresseur et faire échouer de force une partie
  en cours sont toutes des opérations de niveau `OPERATOR`, parce qu'`OPERATOR` est la seule
  barre que le répartiteur connaisse.
- **Un `GET` qui modifierait quelque chose serait totalement sans garde.** Il n'en existe
  aucun aujourd'hui, mais rien dans le module n'empêcherait d'en ajouter un, et rien ne le
  signalerait.
- **Une modification du répartiteur supprimerait tous les contrôles d'un coup.** Cette API
  n'a pas de seconde ligne de défense.

Les huit routes mutantes, toutes `OPERATOR` ou au-dessus par le répartiteur du cœur :

| Route | Ce qu'elle fait |
|---|---|
| `POST /configs` | Créer un donjon |
| `PUT /configs/:id` | Écraser toute la configuration d'un donjon |
| `DELETE /configs/:id` | Supprimer un donjon et son fichier |
| `POST /configs/reload` | Recharger toutes les configurations depuis le disque |
| `PUT /tickets` | Réécrire les modèles de tickets |
| `PUT /trainers/:id` | Écrire n'importe quel fichier JSON de dresseur |
| `POST /active/fail` | Faire échouer de force un donjon en cours |
| `POST /active/leave` | Retirer un joueur d'un donjon en cours |

Un `if (!WebDashboard.requireWrite(exchange)) return;` local au début de chacun de ces
gestionnaires relèverait de la défense en profondeur — utile, mais redondant avec le
répartiteur, et ce n'est pas la frontière elle-même.

Pour mémoire, `requireWrite` signifie `OPERATOR` ou au-dessus et `requireAdmin` signifie
`SUPER_ADMIN` ; les deux envoient leur propre `403` et rendent le verdict sous forme de
booléen. La colonne « rôle » des tableaux qui suivent indique le rôle **réellement** exigé
dans cette version, et marque par (cœur) les cas où la garantie vient du répartiteur de
CheeseDash et non du module.

## Les configurations de donjons

### `GET /configs`

Résumés de chaque donjon enregistré. Rôle : tous les rôles, `READ_ONLY` compris.

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
fichier sur le disque. Rôle : tous les rôles, `READ_ONLY` compris. `404` avec
`{"error":"Dungeon not found"}` si l'identifiant est inconnu.

Les identifiants dans le chemin sont validés : un identifiant contenant `..`, `/`, `\` ou
`%`, ou long de 128 caractères ou plus, est rejeté avec `400` et
`{"error":"Invalid config ID"}`.

### `POST /configs`

Crée un donjon. Rôle : `OPERATOR` ou au-dessus (cœur).

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

Remplace toute la configuration. Rôle : `OPERATOR` ou au-dessus (cœur). Le corps est un
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
registre. Rôle : `OPERATOR` ou au-dessus (cœur). Répond `200 {"deleted":true}`, ou `404` si
inconnu.

Le fichier supprimé est toujours `dungeons/<id>.json`. Un donjon dont le fichier porte un
autre nom n'est pas retiré : le rechargement le réenregistre depuis son propre fichier, et
la route répond pourtant `200 {"deleted":true}`.

Une partie en cours du donjon supprimé continue : son instance détient son propre objet de
configuration.

### `POST /configs/reload`

`ConfigLoader.load()` sur le thread serveur : vide le registre, relit `config.json`,
`dungeon_tickets.json`, chaque `dungeons/*.json`, puis l'historique des parties, les
cooldowns et le classement. Rôle : `OPERATOR` ou au-dessus (cœur). Répond
`200 {"reloaded":true}`.

Les dresseurs ne sont **pas** rechargés — `TrainerLoader.load` ne tourne qu'au démarrage du
serveur.

## Les tickets

### `GET /tickets`

Le contenu brut de `dungeon_tickets.json`. Rôle : tous les rôles, `READ_ONLY` compris.

```json
{ "tickets": [ { "dungeonId": "generic_dungeon", "itemName": "§6Copper Dungeon Ticket", "tier": 1 } ] }
```

### `PUT /tickets`

Remplace la configuration des tickets et l'enregistre sur le disque. Rôle : `OPERATOR` ou
au-dessus (cœur). Renvoie la configuration stockée.

Un corps qui n'est pas du JSON valide est avalé : `ConfigLoader.updateTicketConfig` capture
l'exception, journalise `Failed to update dungeon tickets config from server`, et la route
répond quand même `200` avec la configuration **inchangée**. Compare la réponse à ce que tu
as envoyé s'il te faut une certitude.

Les clients connectés ne sont pas resynchronisés ; ils prendront le changement en compte à
leur prochaine connexion.

## Les dresseurs

### `GET /trainers`

Liste les fichiers de `config/cobblemon_dungeons/trainers/`. Rôle : tous les rôles,
`READ_ONLY` compris.

```json
[ { "id": "example_trainer", "filename": "example_trainer.json" } ]
```

Un dossier absent donne un tableau vide.

### `GET /trainers/:id`

Le contenu brut du fichier `<id>.json`, servi tel quel. Rôle : tous les rôles, `READ_ONLY`
compris.
`404` `{"error":"Trainer file not found"}` si le fichier n'existe pas.

### `PUT /trainers/:id`

Valide que le corps s'analyse comme du JSON, crée le dossier si besoin, et écrit le corps
tel quel dans `<id>.json`. Rôle : `OPERATOR` ou au-dessus (cœur). Renvoie le corps en cas de
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
groupe de quatre apparaîtrait sinon quatre fois. Rôle : tous les rôles, `READ_ONLY` compris.

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

Fait échouer de force la partie dans laquelle se trouve le joueur donné. Rôle : `OPERATOR`
ou au-dessus (cœur).

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

Retire un seul joueur de sa partie, en y laissant le reste du groupe. Rôle : `OPERATOR` ou
au-dessus (cœur). Même forme de corps. Le joueur voit *Vous avez été retiré du donjon par un
administrateur.* et passe par le chemin de sortie normal, ce qui, sur une partie non
terminée, signifie que sa progression dans le donjon n'est pas sauvegardée.

Réponses : `200 {"left":true}`, `400 {"error":"playerUuid is required"}`, `500` sur
exception. Exige que le joueur soit en ligne ; un UUID hors ligne est une opération sans
effet qui rapporte malgré tout un succès.

## Statistiques, historique, classement

### `GET /stats`

Agrégats calculés à la volée depuis l'historique des parties en mémoire. Rôle : tous les
rôles, `READ_ONLY` compris. Les définitions exactes sont dans
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

Les parties les plus récentes, les plus récentes d'abord. Rôle : tous les rôles,
`READ_ONLY` compris.

| Paramètre | Type | Défaut | Plage |
|---|---|---|---|
| `n` | int | `50` | borné à 1–1000 ; une valeur illisible retombe sur 50 |

```json
{ "history": [ { "dungeonId": "…", "players": ["RoiCheese"], "durationSeconds": 612, "success": true, "…": "…" } ],
  "total": 42, "returned": 42 }
```

`total` est la taille de tout l'historique, `returned` le nombre d'entrées de cette réponse.

### `GET /leaderboard` et `GET /leaderboard/:dungeonId`

Rôle : tous les rôles, `READ_ONLY` compris. Les deux renvoient la même forme imbriquée —
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

Le contenu de `config.json`, avec `database.password` remplacé par `"***"`. Rôle : tous
les rôles, `READ_ONLY` compris.

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
| `DUNGEON_OPEN` | `dungeonId`, `dungeonTier` (défaut `1`) | Appelle `DungeonManager.startDungeon(leader, id, tier)` pour le **premier** participant seulement, en tant que chef. Les autres participants ne suivent que s'ils sont membres de son `/DungeonsParty` ; l'effet ne constitue aucun groupe lui-même |
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
