[← CobbleCheeseScore](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](dashboard.md)

# API dashboard

Le mod enregistre un module CheeseDash sous le préfixe **`scores`**, via l'entrypoint
`cheesedash` de `fabric.mod.json` :

```json
"entrypoints": {
  "cheesedash": ["com.cobblecheesescore.dashboard.CobbleCheeseScoreDashboardModule"]
}
```

Les routes authentifiées vivent sous `/api/v1/scores/`. Le module déclare aussi un préfixe
public, `scores` également, ce qui lui donne une route non authentifiée sous
`/scores/api/`.

| | |
|---|---|
| Nom affiché | CobbleCheeseScore |
| Icône de navigation | 🏆 |
| Description de navigation | Classements et scoreboards |
| Port HTTP | 44311 (CheeseDash) |

---

## Rôle requis

**`ScoresApi` ne contient aucun contrôle de rôle qui lui soit propre.** Cherche-y
`WebDashboard.requireWrite` ou `WebDashboard.requireAdmin` et tu ne trouveras rien — pas une
fois, sur ses huit routes qui modifient quelque chose.

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
version, toute requête non-`GET` vers n'importe quelle route sous `/api/v1/scores/` exige
bien `OPERATOR` ou au-dessus, et les requêtes `GET` acceptent tous les rôles, `READ_ONLY`
compris. La frontière tient.

Ce qui ne tient pas, c'est la raison pour laquelle elle tient. La protection est une
propriété du répartiteur du cœur, fondée uniquement sur la méthode HTTP, et le module n'y
contribue en rien. Trois conséquences :

- **Aucune route de ce module ne peut exiger `SUPER_ADMIN`.** Supprimer un mini-jeu avec
  toutes ses sessions, réécrire le titre et les couleurs d'un classement et forcer un
  rafraîchissement complet sont toutes des opérations de niveau `OPERATOR`, parce
  qu'`OPERATOR` est la seule barre que le répartiteur connaisse.
- **Un `GET` qui modifierait quelque chose serait totalement sans garde.** Il n'en existe
  aucun aujourd'hui — toutes les écritures ici sont des `POST`, `PUT` ou `DELETE` — mais rien
  dans le module n'empêcherait d'en ajouter un, et rien ne le signalerait.
- **Une modification du répartiteur supprimerait tous les contrôles d'un coup.** `ScoresApi`
  n'a pas de seconde ligne de défense.

C'est documenté plutôt que corrigé, délibérément. L'indication de rôle sur chaque route
ci-dessous donne le rôle **réellement** exigé dans cette version, et marque par (cœur) les
cas où la garantie vient du répartiteur de CheeseDash et non du module.

Pour mémoire, `requireWrite` signifie `OPERATOR` ou au-dessus et `requireAdmin` signifie
`SUPER_ADMIN` ; les deux envoient leur propre `403` et rendent le verdict sous forme de
booléen, si bien que la forme locale correcte est :

```java
if (!WebDashboard.requireWrite(exchange)) return;
```

Ignorer sa valeur de retour envoie le 403 **puis continue d'exécuter le handler**, ce qui est
pire que de ne pas l'appeler. Un module qui écrit cette ligne correctement ajoute de la
défense en profondeur ; il ne déplace pas la frontière, que le répartiteur tient déjà.

| Rôle | Ce qu'il peut faire ici |
|---|---|
| READ_ONLY | toutes les routes `GET` — classements, rangs, images, config, stats, mini-jeux |
| OPERATOR | tout ce qui précède, plus toutes les routes non-`GET` (appliqué par le cœur) |
| SUPER_ADMIN | la même chose qu'OPERATOR — aucune route ici n'en demande davantage |

Toute requête non-`GET` est aussi écrite dans le journal d'audit de CheeseDash sous la forme
`scores.<méthode>` avec le chemin complet.

---

## Routes des classements

### `GET /api/v1/scores/scoreboards`

Tous les classements configurés, activés ou non, dans l'ordre de la configuration. Rôle :
n'importe lequel.

```json
[
  {
    "id": "pokedex_completed",
    "title": "Pokédex Complété",
    "description": "Espèces capturées",
    "enabled": true,
    "maxPlayers": 30,
    "sortOrder": "desc",
    "playerCount": 25,
    "topPlayer": { "name": "RoiCheese", "score": "906/1025 (88%)" },
    "hasImage": true
  }
]
```

`topPlayer` est entièrement omis quand le classement est vide. `playerCount` est la
longueur du classement calculé, qui exclut les joueurs dont le score est nul.

| Statut | Condition |
|---|---|
| `200` | toujours, même avec tous les classements vides |
| `503` | `ScoreboardManager` ou `Config` est null — le mod n'a pas fini de démarrer |

### `GET /api/v1/scores/scoreboards/:id`

Un classement avec son classement complet. Rôle : n'importe lequel.

| Paramètre | Dans | Type | Remarques |
|---|---|---|---|
| `id` | chemin | chaîne | un identifiant de classement de la configuration. Capturé par `[^/]+`, il ne peut donc pas contenir de barre oblique |

```json
{
  "id": "shiny_hunter",
  "title": "Chasseur de Shiny",
  "description": "Shinies capturés",
  "enabled": true,
  "maxPlayers": 30,
  "sortOrder": "desc",
  "rankings": [
    {
      "rank": 1,
      "uuid": "5e1b2c3d-7a8f-4b6c-9d0e-1f2a3b4c5d6e",
      "name": "RoiCheese",
      "score": 69.0,
      "formattedScore": "69"
    }
  ],
  "colors": { "titleBackground": "#FFD700" },
  "hasImage": true,
  "lastUpdate": 1761700000000
}
```

`score` est le double brut, `formattedScore` la chaîne que montre l'image. `lastUpdate` est
l'horodatage en millisecondes du dernier rafraîchissement complet, partagé par tous les
classements ; `0` signifie qu'aucun rafraîchissement n'est encore allé au bout.

| Statut | Condition |
|---|---|
| `200` | trouvé |
| `404` | `{"error":"Scoreboard not found: <id>"}` |
| `503` | non initialisé |

### `PUT /api/v1/scores/scoreboards/:id`

Modifie la présentation d'un classement. Rôle : **OPERATOR** (cœur) — voir
[rôle requis](#rôle-requis). Le corps est un objet partiel ; seules les clés présentes sont
appliquées.

| Clé du corps | Type | Effet |
|---|---|---|
| `title` | chaîne | remplace `title` |
| `description` | chaîne | remplace `description` |
| `enabled` | booléen | active ou désactive le classement |
| `maxPlayers` | entier | remplace `maxPlayers` |
| `rowsPerColumn` | entier | remplace `rowsPerColumn` |
| `colors` | objet | fusionné dans la map existante ; une valeur `null` ou `""` **retire** cette clé |

`sortOrder` et `id` ne peuvent pas être changés par cette route, bien que
`ScoreboardConfig` ait des setters pour les deux. Pour ceux-là, édite le fichier de
configuration et redémarre.

```bash
curl -X PUT http://localhost:44311/api/v1/scores/scoreboards/shiny_hunter \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title":"Shiny Hunters","colors":{"titleBackground":"#FFD700","text":""}}'
```

```json
{"updated":true}
```

Effets de bord, dans l'ordre : toute la configuration est réécrite dans
`config/cobblecheesescore.json`, puis `ScoreboardManager.updateAll()` s'exécute, ce qui
recollecte les données de tous les joueurs et refait le rendu de **tous** les classements.
L'appel ne rend la main qu'à la fin de tout cela : attends-toi à ce qu'il dure aussi
longtemps qu'un rafraîchissement complet.

| Statut | Condition |
|---|---|
| `200` | `{"updated":true}` |
| `404` | aucun classement avec cet identifiant |
| `500` | `{"error":"Failed to save config"}` — inatteignable en pratique pour une erreur disque : `Config.save()` attrape sa propre `IOException` et se contente de journaliser `Failed to save config`, si bien qu'une écriture ratée répond quand même `200`. Les modifications sont en mémoire dans tous les cas et seront perdues au redémarrage si l'écriture a échoué |
| `503` | configuration non chargée |

Un rafraîchissement qui lève après un enregistrement réussi est journalisé en avertissement
et répond quand même `200` : le changement de configuration a tenu, les images non.

### `GET /api/v1/scores/scoreboards/:id/image`

**Cette route renvoie une image, pas du JSON.** Rôle : n'importe lequel.

```text
GET /api/v1/scores/scoreboards/:id/image
GET /api/v1/scores/scoreboards/:id/image/:filename.png
```

Le segment final optionnel est capturé puis jeté ; il n'existe que pour qu'un
téléchargement depuis un navigateur obtienne un nom de fichier utile. Les deux formes
renvoient des octets identiques.

| | |
|---|---|
| `Content-Type` en cas de succès | `image/png` |
| En-têtes de succès | `Cache-Control: public, max-age=60` |
| Corps | le PNG encodé depuis le `BufferedImage` en cache par `ImageIO` |

```bash
curl -H "Authorization: Bearer $TOKEN" \
  -o pokedex.png \
  http://localhost:44311/api/v1/scores/scoreboards/pokedex_completed/image
```

| Statut | Type de contenu | Condition |
|---|---|---|
| `200` | `image/png` | une image est en cache pour cet identifiant |
| `404` | `application/json` | `{"error":"No image for scoreboard: <id>"}` — identifiant inconnu, aucun rafraîchissement encore passé, ou classement désactivé depuis le démarrage (un classement désactivé plus tard garde sa dernière image) |
| `503` | `application/json` | `{"error":"Cache not initialized"}` |

⚠️ Les réponses d'erreur sont en JSON alors que la réponse de succès est un PNG. Regarde le
code de statut, pas le type de contenu, et ne passe jamais le corps directement à un
décodeur d'image.

### `POST /api/v1/scores/refresh`

Force une recollecte et un nouveau rendu complets. Rôle : **OPERATOR** (cœur). Aucun corps.

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:44311/api/v1/scores/refresh
```

```json
{"refreshed":true,"lastUpdate":1761700000000}
```

S'exécute de façon synchrone sur le thread HTTP appelant. Elle lit des fichiers NBT et JSON
et ne touche à aucun état Minecraft : elle ne bascule donc pas sur le thread serveur — mais
cela signifie que la requête HTTP reste ouverte pendant toute la passe, et que deux
rafraîchissements concurrents entrent en course. Voir
[architecture](architecture.fr.md#threading).

| Statut | Condition |
|---|---|
| `200` | terminé |
| `500` | `{"error":"Refresh failed: <message>"}` |
| `503` | `ScoreboardManager` non initialisé |

---

## Routes de configuration

### `GET /api/v1/scores/config`

Rôle : n'importe lequel. Renvoie un sous-ensemble choisi de la configuration globale plus
les réglages de chaque classement.

```json
{
  "updateIntervalMinutes": 10,
  "timeZone": "Europe/Paris",
  "defaultMaxPlayers": 30,
  "defaultRowsPerColumn": 10,
  "showLastUpdate": true,
  "enableHistory": true,
  "historyRetentionDays": 30,
  "defaultColors": { "background": "#0f0c29", "titleBackground": "#ffcb05" },
  "scoreboards": [
    {
      "id": "pokedex_completed",
      "enabled": true,
      "title": "Pokédex Complété",
      "description": "Espèces capturées",
      "maxPlayers": 30,
      "rowsPerColumn": 10,
      "sortOrder": "desc",
      "colors": {}
    }
  ]
}
```

`enableHistory`, `historyRetentionDays`, `defaultMaxPlayers` et `defaultRowsPerColumn` sont
rapportées ici et nulle part ailleurs — aucun code n'agit dessus. `port`,
`enableWebInterface`, `enableJsonApi`, `enableDiscordWebhook`, `discordWebhookUrl`,
`autoDetectDataFolder`, `manualDataFolderPath` et `lastUpdateText` ne sont pas rapportées du
tout, bien que les trois dernières aient bel et bien un effet. Voir
[configuration](configuration.fr.md#les-clés-que-rien-ne-lit).

| Statut | Condition |
|---|---|
| `200` | toujours |
| `503` | configuration non chargée |

### `PUT /api/v1/scores/config`

Rôle : **OPERATOR** (cœur).

⚠️ **Cette route ne change presque rien.** Lis `handleUpdateConfig` : elle fait exactement
deux choses. Si le corps contient à la fois `scoreboardId` et `enabled`, elle retrouve le
classement et **journalise** le changement demandé sans l'appliquer ; puis elle réécrit la
configuration en mémoire sur le disque et répond `{"saved":true}`. Aucune clé globale n'est
analysée. Un commentaire périmé dans le code prétend encore que `ScoreboardConfig` a besoin
d'un setter — `setEnabled` existe depuis un moment, il n'est simplement pas appelé ici.

Donc :

- pour activer ou désactiver un classement, utilise `PUT /scoreboards/:id` avec
  `{"enabled": false}` ;
- pour changer `updateIntervalMinutes`, `timeZone`, `showLastUpdate` ou `defaultColors`,
  édite `config/cobblecheesescore.json` et redémarre le serveur.

```bash
curl -X PUT http://localhost:44311/api/v1/scores/config \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"scoreboardId":"survivor","enabled":false}'
```

```json
{"saved":true}
```

Ce `{"saved":true}` est honnête sur ce qui s'est passé — le fichier a été enregistré — et
trompeur sur ce que tu as demandé. `survivor` est toujours activé.

| Statut | Condition |
|---|---|
| `200` | `{"saved":true}` |
| `404` | `{"error":"Scoreboard not found"}` quand `scoreboardId` ne désigne rien |
| `500` | `{"error":"Failed to save config"}` — jamais en pratique pour une erreur disque, que `Config.save()` avale et journalise ; la route répond alors quand même `200` |
| `503` | configuration non chargée |

### `GET /api/v1/scores/stats`

Rôle : n'importe lequel. Compteurs de synthèse pour une tuile du dashboard.

```json
{
  "totalScoreboards": 34,
  "enabledScoreboards": 34,
  "totalPlayersTracked": 30,
  "lastUpdate": 1761700000000,
  "updateIntervalMinutes": 10
}
```

`totalPlayersTracked` est la taille de la map `PlayerData` en cache, qui n'est remplacée que
lorsqu'une passe de collecte a trouvé au moins un joueur. Après une passe qui n'en a trouvé
aucun, elle continue de rapporter le nombre précédent. Quand la configuration est null, la
route répond `200` avec tous les compteurs à `0` et sans clé `updateIntervalMinutes`.

---

## Routes mini-jeux

Le modèle et la règle de notation sont décrits dans [mini-jeux](minigames.fr.md). Les
lectures acceptent tous les rôles ; les cinq écritures exigent `OPERATOR` ou au-dessus,
appliqué par le répartiteur du cœur.

| Méthode | Chemin | Objet | Rôle |
|---|---|---|---|
| `GET` | `/minigames` | tous les jeux, avec leur nombre de sessions et leur meilleur joueur | tous |
| `POST` | `/minigames` | créer un jeu | `OPERATOR` (cœur) |
| `GET` | `/minigames/global` | classement agrégé sur tous les jeux | tous |
| `GET` | `/minigames/:id` | un jeu, ses sessions de la plus récente à la plus ancienne, et son classement | tous |
| `PUT` | `/minigames/:id` | renommer un jeu | `OPERATOR` (cœur) |
| `DELETE` | `/minigames/:id` | supprimer un jeu et toutes ses sessions | `OPERATOR` (cœur) |
| `POST` | `/minigames/:id/sessions` | enregistrer le résultat d'une session | `OPERATOR` (cœur) |
| `DELETE` | `/minigames/:id/sessions/:idx` | supprimer une session par son indice | `OPERATOR` (cœur) |

`/minigames/global` est capturée avant `/minigames/:id` : `global` est donc un identifiant
réservé, et un jeu créé sous ce nom peut être listé mais jamais récupéré individuellement.

### `GET /minigames`

```json
[
  {
    "id": "chasse_au_tresor",
    "displayName": "Chasse au trésor",
    "sessionCount": 12,
    "playerCount": 8,
    "topPlayer": { "name": "RoiCheese", "score": "21 pts" }
  }
]
```

`topPlayer` est omis quand aucune session n'a jamais été enregistrée. `503` si le
gestionnaire est null.

### `POST /minigames`

| Clé du corps | Type | Requise |
|---|---|---|
| `id` | chaîne | oui, non vide après suppression des espaces |
| `displayName` | chaîne | oui, non vide après suppression des espaces |

```bash
curl -X POST http://localhost:44311/api/v1/scores/minigames \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"id":"chasse_au_tresor","displayName":"Chasse au trésor"}'
```

```json
{"created":true,"id":"chasse_au_tresor"}
```

Écrit `config/cobblecheesescore_minigames.json` immédiatement.

| Statut | Condition |
|---|---|
| `200` | créé |
| `400` | `{"error":"id and displayName required"}` |
| `409` | `{"error":"Game already exists: <id>"}` |
| `503` | gestionnaire null |

Rien ne valide l'identifiant au-delà du fait qu'il ne soit pas vide. Une barre oblique ou un
espace dedans rendront `/minigames/:id` inatteignable, parce que le distributeur capture
`[^/]+` sur un chemin brut.

### `GET /minigames/:id`

```json
{
  "id": "chasse_au_tresor",
  "displayName": "Chasse au trésor",
  "sessions": [
    {
      "index": 11,
      "date": 1761700000000,
      "rankings": [
        { "uuid": "5e1b2c3d-…", "name": "RoiCheese", "score": 420.0 }
      ]
    }
  ],
  "ranking": [
    {
      "rank": 1,
      "uuid": "5e1b2c3d-…",
      "name": "RoiCheese",
      "score": 21.0,
      "formattedScore": "21 pts"
    }
  ]
}
```

Les sessions reviennent de la plus récente à la plus ancienne, et chacune porte son `index`
**dans la liste chronologique stockée** — c'est le nombre qu'attend
`DELETE /minigames/:id/sessions/:idx`. Les indices ne sont pas contigus dans l'ordre de la
réponse : lis-les sur les objets plutôt que de compter les positions.

`404` quand l'identifiant est inconnu, `503` quand le gestionnaire est null.

### `PUT /minigames/:id`

| Clé du corps | Type | Effet |
|---|---|---|
| `displayName` | chaîne | renommé, espaces retirés. Toute autre clé est ignorée |

`200` `{"updated":true}`, ou `404` `{"error":"Game not found"}` — le même `404` est renvoyé
quand le gestionnaire lui-même est null.

### `DELETE /minigames/:id`

`200` `{"deleted":true}`, `404` `{"error":"Game not found: <id>"}`, `503` gestionnaire null.
Les sessions du jeu partent avec lui de `cobblecheesescore_minigames.json`. Son historique
survit dans `cobblecheesescore_legends.json`, dont rien ne supprime et qu'aucune route
n'expose.

### `POST /minigames/:id/sessions`

| Clé du corps | Type | Requise |
|---|---|---|
| `rankings` | tableau | oui, doit être un tableau JSON |

Chaque élément : `uuid` (chaîne, `""` par défaut), `name` (chaîne, `""` par défaut),
`score` (nombre, `0` par défaut). **Le tableau doit déjà être trié du meilleur au moins
bon** — rien ne le trie ni ne le valide, et les points vont aux positions 1, 2 et 3 du
tableau tel qu'il a été soumis.

```bash
curl -X POST http://localhost:44311/api/v1/scores/minigames/chasse_au_tresor/sessions \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"rankings":[
        {"uuid":"5e1b2c3d-7a8f-4b6c-9d0e-1f2a3b4c5d6e","name":"RoiCheese","score":420},
        {"uuid":"0b1c2d3e-…","name":"Alex","score":310}
      ]}'
```

```json
{"added":true,"sessionCount":13}
```

Effets de bord : ajoute la session, rogne le jeu à ses 500 sessions les plus récentes, écrit
`cobblecheesescore_minigames.json`, puis ajoute un enregistrement permanent à
`cobblecheesescore_legends.json`. Un échec de cette dernière étape est journalisé et avalé —
la réponse reste `200`.

| Statut | Condition |
|---|---|
| `200` | enregistré |
| `400` | `{"error":"rankings array required"}` |
| `404` | `{"error":"Game not found: <id>"}` |
| `503` | gestionnaire null |

Un tableau `rankings` vide est accepté : la session est stockée sans entrée, et l'archive
des légendes l'ignore.

### `DELETE /minigames/:id/sessions/:idx`

`idx` doit correspondre à `\d+`, sinon la route retombe sur le `404` du module. Un indice
hors bornes et un jeu inconnu répondent tous deux `404` `{"error":"Session not found"}`.

⚠️ Supprimer une session renumérote tous les indices au-dessus d'elle, puisque la liste est
une `ArrayList` et que l'indice en est la position. Récupère `GET /minigames/:id` entre deux
suppressions, sinon tu supprimeras la mauvaise.

### `GET /minigames/global`

```json
{
  "ranking": [
    { "rank": 1, "uuid": "5e1b2c3d-…", "name": "RoiCheese",
      "score": 48.0, "formattedScore": "48 pts" }
  ],
  "totalGames": 3
}
```

---

## Route publique, sans authentification

```text
GET /scores/api/images/<name>/<id>.png
```

Enregistrée parce que `getPublicPrefix()` renvoie `scores`, servie hors du middleware
d'authentification `/api/`, avec les en-têtes de sécurité publics de CheeseDash. `<name>`
est un remplissage pour le nom du fichier téléchargé ; `<id>` est l'identifiant du
classement, décodé depuis l'URL. Mêmes octets, mêmes codes de statut et mêmes corps
d'erreur JSON que la route d'image authentifiée.

C'est la route sur laquelle pointer un cadre d'image en jeu. Marche à suivre complète dans
[export PNG](png-export.fr.md#lafficher-sur-une-carte-en-jeu).

Tout le reste sous `/scores/api/` répond `404` `{"error":"Not found"}`. Le préfixe public
fait aussi servir par CheeseDash des ressources du classpath depuis `/scores/`, mais le mod
n'en livre aucune : `http://host:44311/scores/` est donc un `404`.

---

## WebSocket

**Ce module ne publie rien en WebSocket.** Il n'y a aucun appel à
`DashboardRegistry.broadcast(...)` dans tout le mod. Une vue du dashboard qui affiche des
données de classement doit interroger `GET /scoreboards` ou `GET /stats` périodiquement ;
rien ne lui poussera une notification de rafraîchissement.

---

## Routes non gérées et erreurs

Tout ce que le distributeur ne reconnaît pas répond `404` `{"error":"Route not found"}`.
Toute exception qui s'échappe d'un handler est attrapée en tête de `ScoresApi.handle`,
journalisée en `[ScoresApi] Error handling <subRoute>: <message>`, et renvoyée ainsi :

```json
{"error":"Internal server error","detail":"<message de l'exception>"}
```

Un corps JSON malformé atteint ce filet : une faute de frappe dans le corps revient donc en
`500` plutôt qu'en `400`.

---

← [Mini-jeux](minigames.fr.md) · [Architecture](architecture.fr.md) →
