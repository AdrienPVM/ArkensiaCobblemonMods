[← CheeseDash](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](rest-api.md)

# API REST

Tout est du JSON sur HTTP, port 44311. Cette page couvre les routes du **cœur**, celles
que `DashboardApi` traite lui-même. Les routes apportées par d'autres mods vivent sous
`/api/v1/<prefix>/` et sont documentées par ces mods ; la façon dont elles sont
dispatchées est [en bas de page](#routes-de-modules).

```text
http://<serveur>:44311/api/v1/<route>
```

Chaque route exige `Authorization: Bearer <identifiant de session ou token>`, sauf
`POST /auth/login`. Les rôles sont expliqués dans
[authentification et rôles](auth-and-roles.fr.md#ce-que-peut-faire-chaque-rôle) ; dans
les tableaux ci-dessous, **lecture** signifie n'importe quel rôle authentifié,
**écriture** signifie `OPERATOR` ou plus, et **admin** signifie `SUPER_ADMIN`.

---

## Conventions

**Le préfixe de version est optionnel.** Le dispatcher retire `^/api/v1` puis `^/api` du
chemin, donc `/api/v1/ping`, `/api/ping` et même `/api/v1/api/ping` atteignent le même
handler. Utilise `/api/v1/` : c'est ce que le frontend envoie, et ce qui continuera de
fonctionner si une `/v2` apparaît un jour.

**Les réponses sont en `application/json; charset=utf-8`**, toujours, erreurs comprises.
Une erreur vaut `{"error":"<message>"}` ; un `500` ajoute
`{"detail":"<message de l'exception>"}`.

**Codes de statut utilisés par les routes du cœur :**

| Code | Quand |
|---|---|
| `200` | Succès |
| `204` | Préflight `OPTIONS` |
| `400` | Champ manquant ou invalide dans le corps, ou valeur hors plage |
| `401` | En-tête `Authorization` absent, illisible, ou justificatif inconnu |
| `403` | Authentifié mais rôle insuffisant |
| `404` | Route inconnue, ou ressource nommée inexistante |
| `409` | Un compte porte déjà ce nom d'utilisateur |
| `429` | Limitation de débit : plus de 20 authentifications échouées en 60 s depuis cette IP |
| `500` | Exception non gérée — y compris, sur plusieurs routes, un JSON malformé |
| `503` | La référence au serveur Minecraft n'est pas encore définie |

> ⚠️ Plusieurs handlers appellent `JsonParser.parseString(body).getAsJsonObject()` sans
> `try` : **un JSON invalide produit donc un `500`, pas un `400`**, sur
> `POST /admin/accounts`, `PUT /admin/accounts/{username}`, `POST /server/command` et
> `PUT /dashboard/admin-player`. Les routes de login, de planification et de webhooks,
> elles, l'attrapent et répondent `400 {"error":"Invalid JSON"}`. Traite un `500` dont le
> `detail` contient une erreur de parsing comme ton bug, pas celui du serveur.

**Le `503` est un état réel, pas une panne.** La référence au serveur Minecraft est
posée sur `SERVER_STARTED`, au moment même où le serveur HTTP démarre. Toute route qui a
besoin du jeu — `/status`, `/server/stats`, `/server/logs`, `/server/command` — répond
`503 {"error":"Server not ready"}` jusque-là. `/ping` ne le fait volontairement pas,
ce qui en fait un test de santé utilisable pendant le démarrage.

---

## Santé et statut

### `GET /ping`

Aucun contrôle de rôle au-delà de l'authentification. La sonde de vie la moins coûteuse
possible : elle ne touche pas au jeu.

```json
{"status":"ok","mod":"cheesedash"}
```

### `GET /status`

**lecture.** La source principale de la page de vue d'ensemble. `503` avant que le
serveur soit prêt.

```json
{
  "online": true,
  "playerCount": 7,
  "maxPlayers": 60,
  "motd": "Arkensia",
  "tps": 19.8,
  "uptime": 1772131331004,
  "version": "1.21.1",
  "moduleCount": 11,
  "wsPort": 44312,
  "username": "admin",
  "role": "SUPER_ADMIN"
}
```

| Champ | Remarques |
|---|---|
| `tps` | Mesuré par CheeseDash lui-même toutes les 20 ticks, plafonné à 20.0, arrondi à une décimale |
| `uptime` | ⚠️ **C'est l'heure courante en millisecondes epoch**, pas un uptime. Pour un vrai uptime, utilise `GET /server/stats` |
| `wsPort` | Toujours `port + 1`. Le frontend le lit ici plutôt que de le supposer |
| `username`, `role` | La session de l'appelant, omis s'il est d'une façon ou d'une autre absent |

### `GET /modules`

**lecture.** Ce qui est installé. C'est le tableau à partir duquel la barre latérale est
construite, et c'est pourquoi le dashboard s'adapte à n'importe quelle combinaison de
mods sans configuration.

```json
[
  {"prefix":"cheeseguard","displayName":"CheeseGuard","icon":"🛡","description":"Zones et protections"},
  {"prefix":"kart","displayName":"CheeseKart","icon":"🏎","description":"Mode kart et courses"}
]
```

Les quatre champs viennent directement de l'implémentation `DashboardModule` du module.
Un tableau vide signifie que CheeseDash tourne seul.

---

## Serveur

### `GET /server/stats`

**lecture.** `503` avant que le serveur soit prêt. Les chiffres du heap viennent de
`Runtime` : ils décrivent toute la JVM, pas seulement Minecraft.

```json
{
  "heapUsedMb": 4210,
  "heapMaxMb": 8192,
  "heapTotalMb": 6144,
  "tps": 19.8,
  "playerCount": 7,
  "maxPlayers": 60,
  "uptime": 7384112,
  "version": "1.21.1"
}
```

Ici, `uptime` **est** une durée en millisecondes, mesurée depuis l'initialisation du
mod — contrairement au champ du même nom dans `/status`.

### `GET /server/logs`

**lecture.** Lit `logs/latest.log` dans le répertoire d'exécution du serveur et renvoie
la fin du fichier.

| Paramètre | Type | Défaut | Plage |
|---|---|---|---|
| `n` | int | `100` | ramené dans 1–500 ; une valeur non numérique est ignorée et le défaut utilisé |

```json
{"lines":["[18:42:11] [Server thread/INFO]: …"],"totalLines":84120,"returned":100}
```

Un fichier de log absent n'est pas une erreur : tu obtiens un tableau `lines` vide avec
les deux compteurs à zéro. Un échec de lecture est un `500`.

> ⚠️ Cette route lit le fichier **entier** en mémoire pour en prendre les `n` dernières
> lignes. Sur un serveur debout depuis une semaine avec un gros `latest.log`, chaque
> appel alloue tout le fichier. Acceptable de temps en temps, mauvais dans une boucle de
> collecte. Et remarque qu'il n'y a aucun contrôle de rôle : un compte `READ_ONLY` peut
> lire ton log, qui contient couramment des IP de joueurs.

### `GET /server/mods`

**lecture.** Tous les mods chargés par Fabric, pas seulement les modules CheeseDash.

```json
[{"id":"cheesedash","name":"CheeseDash","version":"1.0.0+1.21.1"}]
```

### `POST /server/command`

**admin.** Exécute une commande Minecraft en tant que console du serveur.

```json
{"command": "say hello"}
```

```json
{"executed":true,"command":"say hello"}
```

| Échec | Code |
|---|---|
| Serveur pas prêt | `503` |
| Clé `command` absente | `400 {"error":"command is required"}` |
| `command` vide ou composée d'espaces | `400 {"error":"command cannot be empty"}` |
| La commande a levé une exception, ou le thread serveur n'a pas répondu en 5 secondes | `500` avec le message d'exception |

Trois choses se passent avant l'exécution : l'appel est journalisé avec l'IP du client,
une entrée d'audit `server.command` est écrite avec la chaîne complète de la commande, et
l'exécution est confiée au thread de tick du serveur via `executeOnServerThread`, qui
l'attend jusqu'à cinq secondes.

`{"executed":true}` signifie que le thread serveur a exécuté la commande sans lever
d'exception. Cela ne signifie **pas** que la commande a réussi : un `/give` vers un
joueur hors ligne renvoie `200` ici et une erreur dans le log du jeu. La sortie de la
commande n'est ni capturée ni renvoyée.

---

## Authentification

### `POST /auth/login`

**Aucune authentification.** La seule route dans ce cas.

```json
{"token": "<master token ou token de compte>"}
```

```json
{"sessionId":"ses_9mJ…","username":"admin","role":"SUPER_ADMIN","expiresIn":86400000}
```

| Échec | Code |
|---|---|
| Corps non valide en JSON | `400 {"error":"Invalid JSON"}` |
| Clé `token` absente | `400 {"error":"token is required"}` |
| Token non reconnu | `401 {"error":"Invalid token"}`, audité en `auth.login_failed` |

Le token est débarrassé de ses espaces avant comparaison, donc un copier-coller
approximatif est toléré. Pas de limitation de débit — voir
[limitation de débit](auth-and-roles.fr.md#limitation-de-débit).

### `POST /auth/logout`

**lecture.** Révoque la session appelante. Toujours `200 {"ok":true}`, même s'il n'y
avait rien à révoquer.

### `GET /auth/session`

**lecture.** La session appelante, plus le décompte global des sessions actives.

```json
{
  "sessionId": "ses_9mJ…",
  "username": "admin",
  "role": "SUPER_ADMIN",
  "ip": "127.0.0.1",
  "createdAt": 1772131331004,
  "lastActivity": 1772131901551,
  "expiresIn": 85976553,
  "activeSessions": 2
}
```

`401 {"error":"No active session"}` est inatteignable en pratique : le middleware aurait
rejeté la requête avant.

---

## Administration

Tout ce qui suit exige `SUPER_ADMIN`, sauf le journal d'audit.

### `GET /admin/accounts`

**admin.** Tous les comptes additionnels, sans jamais leur token. Le compte maître
`admin` n'est pas une entrée de `accounts[]` et n'apparaît pas ici.

```json
[{"username":"moderator","role":"OPERATOR","createdAt":1772131331004}]
```

### `POST /admin/accounts`

**admin.** Crée un compte et génère son token.

```json
{"username": "moderator", "role": "OPERATOR"}
```

```json
{"username":"moderator","role":"OPERATOR","createdAt":1772131331004,"token":"Jq7x…-WkR"}
```

⚠️ Le champ `token` n'apparaît **que** dans cette réponse et dans celle de la
régénération. Rien ne permet de le retrouver plus tard ; le seul recours est de le
régénérer, ce qui invalide l'ancien.

| Échec | Code |
|---|---|
| `username` ou `role` absent | `400 {"error":"username and role are required"}` |
| Nom vide, au-delà de 32 caractères, ou hors de `[a-zA-Z0-9_-]` | `400` avec la règle dans le message |
| Nom égal à `admin` | `400 {"error":"'admin' is reserved for the master token"}` |
| Nom déjà pris | `409 {"error":"Account already exists: …"}` |
| `role` hors des trois valeurs | `400 {"error":"Invalid role. Must be READ_ONLY, OPERATOR, or SUPER_ADMIN"}` |

`role` est mis en majuscules avant d'être parsé, donc `"operator"` est accepté. Le compte
est écrit dans `config/cheesedash.json` immédiatement, et l'action est auditée en
`admin.account_create`.

### `PUT /admin/accounts/{username}`

**admin.** Change le rôle, et seulement le rôle. Il n'existe aucun moyen de renommer un
compte ni de fixer son token à une valeur choisie.

```json
{"role": "READ_ONLY"}
```

```json
{"updated":true,"username":"moderator","role":"READ_ONLY"}
```

`400` si `role` manque ou est invalide, `404 {"error":"Account not found: …"}` sinon.
Audité en `admin.account_update`.

> Les sessions **existantes** du compte gardent leur ancien rôle, parce qu'une session
> capture le rôle à sa création. Rétrograder quelqu'un ne prend effet qu'à l'expiration
> de sa session, ou quand tu la révoques via `DELETE /admin/sessions/{id}`.

### `POST /admin/accounts/{username}/regenerate`

**admin.** Émet un nouveau token et le renvoie une seule fois.

```json
{"username":"moderator","token":"Zp2v…-9tQ"}
```

Toutes les sessions de ce nom d'utilisateur sont révoquées. `404` s'il n'existe pas de
tel compte. Audité en `admin.account_regen_token`.

### `DELETE /admin/accounts/{username}`

**admin.** `200 {"deleted":true}` ou `404`. Toutes les sessions du compte sont révoquées.
Audité en `admin.account_delete`.

### `GET /admin/sessions`

**admin.** Toutes les sessions actives et non expirées, de la même forme que
`GET /auth/session` sans `activeSessions`. Utile surtout pour repérer une session que tu
ne reconnais pas, ou l'accumulation de sessions décrite dans
[la note sur les tokens bruts](auth-and-roles.fr.md#un-token-brut-fonctionne-aussi-comme-justificatif-bearer).

### `DELETE /admin/sessions/{id}`

**admin.** `200 {"revoked":true}` si elle existait, `200 {"revoked":false}` sinon.
Remarque que ce n'est pas un `404` : le code est `200` dans les deux cas. Non audité.

### `GET /admin/audit`

**lecture** — volontairement, pour que chaque administrateur puisse voir ce que les
autres ont fait.

| Paramètre | Type | Défaut | Plage |
|---|---|---|---|
| `n` | int | `100` | ramené dans 1–500 |
| `offset` | int | `0` | ramené à ≥ 0 ; compte à rebours depuis l'entrée la plus récente |

```json
{
  "entries": [
    {"timestamp":"2026-03-09T18:42:11.004Z","username":"admin","role":"SUPER_ADMIN",
     "action":"server.command","detail":"say hello","ip":"127.0.0.1"}
  ],
  "total": 412,
  "offset": 0,
  "limit": 100
}
```

Du plus récent au plus ancien. `total` est tout l'historique stocké, plafonné à 10 000
entrées. Sémantique complète dans [journal d'audit](audit.fr.md).

---

## Préférences du dashboard

### `POST /dashboard/token/regenerate`

**admin.** Remplace le master token.

```json
{"confirm": true}
```

```json
{"token":"<le nouveau master token>"}
```

Sans `confirm:true` dans le corps : `400 {"error":"Missing confirm:true in body"}`. Le
contrôle est une recherche de sous-chaîne dans le texte brut du corps — `{"confirm":true}`
et `{"confirm": true}` passent tous les deux, et aucun autre espacement ne passe. Audité
en `admin.token_regenerate` et journalisé en `WARN` avec l'IP de l'appelant.

⚠️ Les sessions existantes survivent à cette opération. Si tu régénères parce que le
token a fuité, redémarre le serveur ensuite.

### `GET /dashboard/admin-player` · `PUT /dashboard/admin-player`

**lecture** / **écriture.** Le nom de joueur que le dashboard considère comme « toi ».

```json
{"name": "RoiCheese"}
```

Le `PUT` renvoie le nom débarrassé de ses espaces et sauvegarde `config/cheesedash.json`
immédiatement. `400 {"error":"name is required"}` si la clé est absente. Aucune
vérification que le joueur existe. Non audité.

### `GET /dashboard/admin-player/position`

**lecture.** Où se trouve ce joueur à l'instant.

```json
{"online":true,"x":128.5,"y":64.0,"z":-310.2,"world":"minecraft:overworld"}
```

`{"online":false}` quand le serveur n'est pas prêt, que le nom est vide, ou que le joueur
est hors ligne. Jamais une erreur, ce qui la rend sûre à interroger en boucle.

### Dossiers de zones

**lecture** pour le `GET`, **écriture** pour le reste. Regroupement cosmétique des zones
CheeseGuard, stocké dans la config propre à CheeseDash ; voir
[configuration](configuration.fr.md#le-tableau-zonefolders).

| Méthode | Chemin | Corps | Réponse |
|---|---|---|---|
| GET | `/dashboard/zone-folders` | — | le tableau des objets dossier |
| POST | `/dashboard/zone-folders` | `{"name":"Spawn area"}` | le dossier créé, avec un `id` généré |
| PUT | `/dashboard/zone-folders` | le tableau **entier** | `{"ok":true}` |
| DELETE | `/dashboard/zone-folders/{id}` | — | `{"ok":true}` |

Le `PUT` remplace toute la liste : lis-modifie-réécris, car envoyer un tableau partiel
supprime les dossiers que tu as omis. Le `POST` répond
`400 {"error":"name is required"}` sur un nom vide. Le `DELETE` valide l'id contre `..`
et `/` et répond `400` sur un id suspect, `404` si aucun dossier ne correspond. Aucune de
ces routes n'est auditée.

---

## Planificateur

La lecture est ouverte à tous les rôles ; tout ce qui change une planification exige
`SUPER_ADMIN`. Le comportement, les annonces de compte à rebours et ce que `RESTART` fait
au processus sont couverts dans [planificateur](scheduler.fr.md).

### `GET /server/schedule`

**lecture.**

```json
{
  "active": {
    "id": "sa_3f8a1c",
    "type": "RESTART",
    "message": "Weekly maintenance",
    "scheduledAt": 1772131931004,
    "createdAt": 1772131331004,
    "createdBy": "admin",
    "remainingSeconds": 582
  },
  "recurring": [
    {"id":"rec_9b21ef","type":"RESTART","hour":5,"minute":0,
     "daysOfWeek":[1,2,3,4,5,6,7],"message":"Nightly restart","enabled":true}
  ]
}
```

`active` vaut `null` quand rien n'est en attente. `daysOfWeek` suit l'ISO : 1 est lundi,
7 est dimanche.

### `POST /server/schedule`

**admin.** Planifie une action ponctuelle. Une seule peut être en attente ; en créer une
seconde annule silencieusement la première.

```json
{"type": "RESTART", "delaySeconds": 600, "message": "Weekly maintenance"}
```

| Champ | Requis | Remarques |
|---|---|---|
| `type` | oui | `RESTART`, `STOP` ou `MAINTENANCE`, mis en majuscules avant parsing |
| `delaySeconds` | oui | de 5 à 86 400 inclus |
| `message` | non | Texte libre montré aux joueurs, `""` si absent |

Renvoie l'action créée dans la forme montrée plus haut. `400` sur un JSON invalide, un
champ manquant, un `type` inconnu, ou un délai hors plage. Audité en `schedule.create`,
et un événement Discord `RESTART_SCHEDULED` est déclenché.

Les actions ponctuelles ne sont **pas persistées**. Un redémarrage du serveur pendant un
compte à rebours l'oublie.

### `DELETE /server/schedule/active`

**admin.** `200 {"cancelled":true}`, ou `404 {"error":"No active scheduled action"}`.
Audité en `schedule.cancel`, déclenche `RESTART_CANCELLED` sur Discord.

### `POST /server/schedule/recurring`

**admin.** Ajoute une planification quotidienne ou hebdomadaire, persistée dans
`config/cheesedash_schedules.json`.

```json
{"type":"RESTART","hour":5,"minute":0,"daysOfWeek":[1,3,5],"message":"Restart"}
```

| Champ | Requis | Remarques |
|---|---|---|
| `type` | oui | comme ci-dessus |
| `hour` | oui | 0–23 |
| `minute` | oui | 0–59 |
| `daysOfWeek` | non | numéros de jours ISO ; **les sept jours** si omis |
| `message` | non | `""` si absent |

`400` sur un JSON invalide, un champ manquant, un `type` inconnu, ou une heure/minute
hors plage. Audité en `schedule.recurring_add`. Remarque que les jours ne sont pas
validés : un `daysOfWeek` de `[0]` ou `[9]` est accepté et ne correspondra jamais.

### `PUT /server/schedule/recurring/{id}`

**admin.** Mise à jour partielle. Seules les clés présentes sont appliquées : `hour`,
`minute`, `message`, `enabled`, `type`, `daysOfWeek` (qui remplace tout l'ensemble).
Renvoie la planification mise à jour, ou `404` si l'id est inconnu. Audité en
`schedule.recurring_update`.

⚠️ Contrairement à la route de création, celle-ci ne vérifie **pas** les plages de
`hour`, `minute` ni la validité de `type`. Un `type` inconnu lève une exception qui
remonte en `500`, et une `hour` à `99` est enregistrée et ne se déclenche jamais.

### `DELETE /server/schedule/recurring/{id}`

**admin.** `200 {"deleted":true}` ou `404`. Audité en `schedule.recurring_delete`.

---

## Webhooks Discord

La lecture est ouverte à tous les rôles ; créer, modifier, supprimer et tester exigent
`SUPER_ADMIN`. Le formatage des messages et le catalogue d'événements sont dans
[webhooks Discord](discord-webhooks.fr.md).

| Méthode | Chemin | Rôle | Effet |
|---|---|---|---|
| GET | `/webhooks/event-types` | lecture | Les 23 types d'événements avec `id`, `label`, `description`, `color` et `category` |
| GET | `/webhooks` | lecture | Tous les webhooks, le milieu de leur URL masqué |
| GET | `/webhooks/{id}` | lecture | Un webhook, **URL non masquée** |
| POST | `/webhooks` | admin | En crée un |
| PUT | `/webhooks/{id}` | admin | Mise à jour partielle |
| DELETE | `/webhooks/{id}` | admin | Le supprime |
| POST | `/webhooks/{id}/test` | admin | Poste un message de test et rapporte le résultat |

`POST /webhooks` prend
`{"name":"Staff alerts","url":"https://discord.com/api/webhooks/…"}`. `name` doit faire
de 1 à 64 caractères, et l'URL doit commencer par `https://discord.com/api/webhooks/` ou
`https://discordapp.com/api/webhooks/` — toute autre valeur donne
`400 {"error":"URL must be a valid Discord webhook URL"}`. Le même contrôle d'URL
s'applique au `PUT` quand une clé `url` est présente. Un nouveau webhook démarre
`enabled` avec un ensemble d'événements vide : il n'est donc abonné à rien jusqu'à ce que
tu lui envoies un tableau `events` par `PUT`.

`POST /webhooks/{id}/test` fait exception : il répond `200` que le test ait réussi ou
non, avec `{"success":true}` ou `{"success":false,"error":"…"}`. Un dépassement de délai
au bout de 10 secondes donne un `500` de la même forme `success:false`. Cette route n'est
pas auditée, contrairement à la création, la modification et la suppression.

> ⚠️ `GET /webhooks/{id}` renvoie l'URL Discord complète à n'importe quel rôle
> authentifié, `READ_ONLY` compris. Cette URL est un justificatif.

---

## Routes de modules

Tout ce que le cœur ne reconnaît pas est traité comme un préfixe de module :

```text
/api/v1/kart/presets/rally
         └┬─┘ └──────┬────┘
      préfixe   subRoute, passé au module sous la forme "/presets/rally"
```

Le dispatcher prend le premier segment du chemin, le cherche dans `DashboardRegistry`, et
appelle `module.handleRequest(exchange, subRoute)`. Un préfixe sans rien après lui donne
un subRoute égal à `"/"`.

Trois choses se passent autour de cet appel, et c'est pourquoi l'auteur d'un module a
moins à écrire qu'il ne le croit :

1. **L'authentification est déjà faite.** La session est attachée à l'exchange avant le
   dispatch, et se récupère avec `WebDashboard.getSession(exchange)`.
2. **Les requêtes non-`GET` passent par `requireWrite`** avant que le module ne les
   voie, donc aucun module ne peut laisser un compte `READ_ONLY` écrire par accident. Un
   module qui exige `SUPER_ADMIN` sur une route précise doit toujours appeler
   `requireAdmin` lui-même.
3. **Les requêtes non-`GET` sont auditées** ensuite, sous la forme `<prefix>.<method>`
   avec le détail `POST /api/v1/kart/start`. Un module qui veut une entrée plus parlante
   écrit sa propre ligne d'audit en plus de celle-là.

Un préfixe inconnu donne `404 {"error":"Route not found: /nope/whatever"}`. Toute
exception sortant d'un handler de module devient un `500` avec le message d'exception
dans `detail`, et est journalisée avec sa stack trace.

> ⚠️ **Ces préfixes sont masqués par les routes du cœur** et ne doivent pas être
> utilisés par un module : `ping`, `status`, `modules`, `auth`, `admin`, `dashboard`,
> `server`, `webhooks`. Les contrôles du cœur passent d'abord, donc un module qui
> enregistrerait `server` ne serait jamais atteint — et rien ne t'en avertit. Les
> collisions entre deux modules, *elles*, sont signalées au démarrage :
> `Duplicate module prefix 'x' — skipping …`.

Un module peut aussi servir un **contexte public non authentifié** en dehors de `/api/`,
ce qui est la façon dont CheeseStore publie sa vitrine. Voir
[écrire un module](writing-a-module.fr.md#contextes-publics-non-authentifiés).

---

← [Authentification et rôles](auth-and-roles.fr.md) · [WebSocket](websocket.fr.md) →
