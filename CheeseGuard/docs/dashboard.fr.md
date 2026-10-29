[← CheeseGuard](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](dashboard.md)

# API dashboard

CheeseGuard s'enregistre auprès de CheeseDash via l'entrypoint Fabric `cheesedash`, sous le
préfixe `cheeseguard`. Toutes les routes ci-dessous se trouvent à :

```text
http://<server>:44311/api/v1/cheeseguard/<route>
```

Dans la barre latérale du dashboard, il apparaît comme **CheeseGuard** 🛡️ avec la description
*Zones, dimensions et événements*.

## Authentification

Toutes les routes exigent une session CheeseDash :

```http
Authorization: Bearer <session-token>
```

Obtiens-en une auprès de `POST /api/v1/auth/login`.

## Ce que le cœur applique réellement

> ⚠️ **Six des huit API de CheeseGuard ne contiennent aucun contrôle de rôle.** `ZonesApi`,
> `DimensionsApi`, `EventsApi`, `GroupsApi`, `PlayersApi` et `ChunksApi` n'appellent jamais
> `WebDashboard.requireWrite` ni `WebDashboard.requireAdmin` — pas une fois, sur 65 routes qui
> modifient quelque chose. Seules `PrisonApi` et `RacesApi` se gardent elles-mêmes.
>
> Ce qui tient lieu de ces contrôles, c'est le répartiteur de modules de CheeseDash, dans
> `DashboardApi` :
>
> ```java
> DashboardModule module = DashboardRegistry.getModule(prefix);
> if (module != null) {
>     if (!method.equals("GET") && !WebDashboard.requireWrite(exchange)) {
>         return;
>     }
>     module.handleRequest(exchange, subRoute);
>     ...
> }
> ```
>
> C'est la forme correcte — elle teste la valeur de retour et rend la main — donc dans cette
> version, toute requête non-`GET` vers n'importe quelle route sous `/api/v1/cheeseguard/`
> exige bien `OPERATOR` ou au-dessus, et les requêtes `GET` acceptent tous les rôles,
> `READ_ONLY` compris. La frontière tient.
>
> Ce qui ne tient pas, c'est la raison pour laquelle elle tient. La protection est une
> propriété du répartiteur du cœur, fondée uniquement sur la méthode HTTP, et le module n'y
> contribue en rien. Trois conséquences :
>
> - **Aucune route de CheeseGuard ne peut exiger `SUPER_ADMIN`.** Réinitialiser une dimension
>   depuis un snapshot, supprimer des fichiers de région de la sauvegarde du monde et
>   supprimer une zone sont toutes des opérations de niveau `OPERATOR`, parce qu'`OPERATOR`
>   est la seule barre que le répartiteur connaisse.
> - **Un `GET` qui modifierait quelque chose serait totalement sans garde.** Il n'en existe
>   aucun aujourd'hui dans ces six API, mais rien dans le module n'empêcherait d'en ajouter un,
>   et rien ne le signalerait.
> - **Une modification du répartiteur supprimerait tous les contrôles d'un coup.** Les six API
>   n'ont pas de seconde ligne de défense.
>
> C'est documenté plutôt que corrigé, délibérément. La colonne « rôle » des tableaux qui
> suivent indique le rôle **réellement** exigé dans cette version, et marque par (cœur) les
> cas où la garantie vient du répartiteur de CheeseDash et non du module.

Pour mémoire, `requireWrite` signifie `OPERATOR` ou au-dessus et `requireAdmin` signifie
`SUPER_ADMIN` ; les deux envoient leur propre `403` et rendent le verdict sous forme de
booléen :

```json
{"error":"Insufficient permissions — requires OPERATOR"}
```

Toute requête non-`GET` vers un module est aussi écrite dans le journal d'audit de CheeseDash
sous la forme `cheeseguard.<méthode>` avec le chemin complet, quelle que soit l'API qui l'a
traitée.

## Routage

`CheeseGuardDashboardModule.handleRequest()` répond `503` si le mod ou le serveur ne sont pas
prêts :

```json
{"error":"CheeseGuard not ready"}
```

Sinon, il passe la main à `CheeseGuardApiRouter.handle()`, qui répartit sur un préfixe de la
sous-route, dans cet ordre :

| La sous-route commence par | Gestionnaire |
|---|---|
| `/` ou `/status` (GET) | le gestionnaire de statut du routeur |
| `/zones` | `ZonesApi` |
| `/groups` | `GroupsApi` |
| `/dimensions` | `DimensionsApi` |
| `/events` | `EventsApi` |
| `/players` | `PlayersApi` |
| `/chunks` | `ChunksApi` |
| `/races` | `RacesApi` |
| `/prison` | `PrisonApi` |

Comme la comparaison est un `startsWith` et non une égalité de segment, une sous-route comme
`/zonesfoo` est routée vers `ZonesApi`, qui ne reconnaît alors aucun de ses propres motifs et
répond son `404 {"error":"Route not found"}` générique. Le résultat est correct ; le chemin
emprunté n'est pas celui qu'on devinerait en lisant le routeur.

Un préfixe non reconnu répond :

```json
{"error":"CheeseGuard route not found: /whatever"}
```

Toute exception qui s'échappe d'un gestionnaire est rattrapée par le routeur et transformée en
`500` :

```json
{"error":"Internal server error","detail":"<message dont les guillemets doubles sont remplacés par des apostrophes>"}
```

Les API attrapent d'abord leurs propres exceptions, généralement avec la cause du thread
serveur dépliée : la plupart des erreurs arrivent donc en `500` portant le vrai message
plutôt que cette enveloppe.

### `GET /` et `GET /status`

Rôle : n'importe lequel. Aucun paramètre. Un petit résumé utilisé par la vue d'ensemble du
dashboard :

```json
{ "zoneCount": 42, "dimensionCount": 7, "activeEvents": 1, "eventCount": 12 }
```

Chaque champ n'est présent que si le gestionnaire correspondant existe : une requête précoce
pendant le démarrage peut donc renvoyer un objet partiel, voire `{}`.

---

## Zones

`ZonesApi`, 1056 lignes, la plus grosse des huit. Le sens des champs est dans
[Zones et flags](zones-and-flags.fr.md) ; cette page couvre la surface des routes.

| Méthode | Chemin | Corps / paramètres | Réponse | Statuts | Rôle |
|---|---|---|---|---|---|
| `GET` | `/zones` | `?world=<id>` filtre par monde | tableau de résumés de zones | `200` | n'importe lequel |
| `POST` | `/zones` | `{name, playerName?, id?, ...}` | la zone créée | `200` `400` `404` `409` | `OPERATOR` (cœur) |
| `GET` | `/zones/:id` | — | détail de la zone | `200` `400` `404` | n'importe lequel |
| `PUT` | `/zones/:id` | champs partiels de zone | détail de la zone | `200` `400` `404` `500` | `OPERATOR` (cœur) |
| `DELETE` | `/zones/:id` | — | `{"deleted":true}` | `200` `404` `409` | `OPERATOR` (cœur) |
| `GET` | `/zones/tags` | — | les tags utilisés | `200` | n'importe lequel |
| `GET` | `/zones/tag-configs` | — | toutes les configs de tags | `200` `503` | n'importe lequel |
| `GET` | `/zones/tag-configs/:tagId` | — | une config de tag | `200` `400` `404` | n'importe lequel |
| `PUT` | `/zones/tag-configs/:tagId` | champs de config de tag | la config | `200` `400` `500` | `OPERATOR` (cœur) |
| `DELETE` | `/zones/tag-configs/:tagId` | — | `{"deleted":true}` | `200` `400` `500` | `OPERATOR` (cœur) |
| `GET` | `/zones/:id/states` | — | états de zone sauvegardés | `200` | n'importe lequel |
| `POST` | `/zones/:id/states` | `{name}` | l'état capturé | `200` `500` | `OPERATOR` (cœur) |
| `POST` | `/zones/:id/states/cancel` | — | `{...}` | `200` | `OPERATOR` (cœur) |
| `PUT` | `/zones/:id/states/:stateId` | `{name}` | l'état | `200` `400` `404` | `OPERATOR` (cœur) |
| `DELETE` | `/zones/:id/states/:stateId` | — | `{"deleted":true}` | `200` `404` | `OPERATOR` (cœur) |
| `POST` | `/zones/:id/states/:stateId/apply` | — | résultat | `200` `400` `500` | `OPERATOR` (cœur) |
| `PUT` | `/zones/:id/sale` | config de vente | la config de vente | `200` `404` `500` | `OPERATOR` (cœur) |
| `DELETE` | `/zones/:id/sale` | — | `{...}` | `200` `404` `500` | `OPERATOR` (cœur) |
| `POST` | `/zones/:id/sale/hologram` | — | `{...}` | `200` `400` `404` `500` | `OPERATOR` (cœur) |
| `DELETE` | `/zones/:id/sale/hologram` | — | `{...}` | `200` `404` `500` | `OPERATOR` (cœur) |
| `POST` | `/zones/:id/sale/sign` | `?player=<nom>` | `{...}` | `200` `400` `404` | `OPERATOR` (cœur) |
| `POST` | `/zones/:id/sale/relist` | — | `{...}` | `200` `400` `404` `500` | `OPERATOR` (cœur) |
| `GET` | `/zones/:id/members` | — | tableau de membres | `200` `404` | n'importe lequel |
| `POST` | `/zones/:id/members` | `{playerName, role?}` | `{...}` | `200` `400` `404` `409` `500` | `OPERATOR` (cœur) |
| `DELETE` | `/zones/:id/members/:uuid` | — | `{...}` | `200` `400` `404` `500` | `OPERATOR` (cœur) |
| `PUT` | `/zones/:id/owner` | `{playerName}` | `{...}` | `200` `400` `404` `500` | `OPERATOR` (cœur) |
| `DELETE` | `/zones/:id/owner` | — | `{...}` | `200` `400` `404` `500` | `OPERATOR` (cœur) |
| `POST` | `/zones/:id/replace-selection` | `{playerName?}` | la zone | `200` `400` `404` `500` | `OPERATOR` (cœur) |

Les identifiants de zone sont validés par le helper standard — ni `..`, ni `/`, ni `\`, ni
`%`, et moins de 128 caractères — et un identifiant invalide répond
`400 {"error":"Invalid zone ID"}` avant toute recherche.

`POST /zones` crée une zone **à partir d'une sélection à la baguette**, pas de coordonnées.
Sans `playerName`, il prend le premier opérateur connecté ; s'il n'y en a aucun,
`400 No playerName provided and no op player online`. Si le joueur nommé n'a pas de sélection
complète à deux coins, `400 No complete wand selection for player <nom>`. Un identifiant déjà
pris donne `409`.

`PUT /zones/:id` est une modification partielle. Les flags s'envoient en objet associant un
identifiant de flag à `"ALLOW"`, `"DENY"` ou `"INHERIT"`, sans distinction de casse ; un
identifiant de flag inconnu est ignoré sans un mot plutôt que refusé. Définir `groupId`
entretient aussi la liste de membres du groupe des deux côtés, et une chaîne vide est traitée
comme `null`.

```bash
curl -X PUT http://localhost:44311/api/v1/cheeseguard/zones/arena \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"priority":5,"flags":{"pvp":"ALLOW","build":"DENY"}}'
```

`DELETE /zones/:id` répond `409` au lieu de supprimer en cascade lorsque la zone a des
enfants.

`DELETE /zones/:id/members/:uuid` refuse de retirer le propriétaire :
`400 Cannot remove owner via members endpoint. Use DELETE /zones/:id/owner instead.`

Les identifiants de tag sont validés à part, contre les minuscules, les chiffres et le
tiret :
`400 {"error":"Invalid tag ID. Use lowercase letters, digits, and hyphens only."}`

---

## Groupes

`GroupsApi`. Les groupes sont des collections nommées de zones qui partagent des flags — voir
[Groupes](groups.fr.md).

| Méthode | Chemin | Corps | Réponse | Statuts | Rôle |
|---|---|---|---|---|---|
| `GET` | `/groups` | — | tableau de résumés de groupes | `200` | n'importe lequel |
| `POST` | `/groups` | `{name}` | détail du groupe | `200` `400` `409` `500` | `OPERATOR` (cœur) |
| `GET` | `/groups/:id` | — | détail du groupe | `200` `404` | n'importe lequel |
| `PUT` | `/groups/:id` | champs partiels | détail du groupe | `200` `404` `500` | `OPERATOR` (cœur) |
| `DELETE` | `/groups/:id` | — | `{"deleted":true}` | `200` `404` `500` | `OPERATOR` (cœur) |
| `GET` | `/groups/:id/zones` | — | les zones du groupe | `200` `404` | n'importe lequel |
| `POST` | `/groups/:id/zones/:zoneId` | — | `{"assigned":true}` | `200` `404` `500` | `OPERATOR` (cœur) |
| `DELETE` | `/groups/:id/zones/:zoneId` | — | `{"unassigned":true}` | `200` `404` `500` | `OPERATOR` (cœur) |

L'identifiant du groupe est **dérivé** du nom par slugification — tu ne le choisis pas. Un nom
qui se slugifie en chaîne vide donne `400 {"error":"Invalid group name"}`, et une collision
donne `409 {"error":"Group already exists: <id>"}`. Deux noms différents peuvent se slugifier
en un même identifiant, et le second est alors refusé.

---

## Dimensions

`DimensionsApi`, 806 lignes. Le sens des champs est dans [Dimensions](dimensions.fr.md).

| Méthode | Chemin | Corps / paramètres | Réponse | Statuts | Rôle |
|---|---|---|---|---|---|
| `GET` | `/dimensions/gen-types` | — | types de génération disponibles | `200` | n'importe lequel |
| `GET` | `/dimensions` | — | tableau de résumés enrichis | `200` | n'importe lequel |
| `POST` | `/dimensions` | `{name, genType\|customGenTypeId, ...}` | détail de la dimension | `200` `400` `500` | `OPERATOR` (cœur) |
| `GET` | `/dimensions/:id` | — | détail de la dimension | `200` `400` `404` | n'importe lequel |
| `PUT` | `/dimensions/:id` | champs partiels | détail de la dimension | `200` `400` `404` `500` | `OPERATOR` (cœur) |
| `DELETE` | `/dimensions/:id` | — | `{"deleted":true}` | `200` `404` `500` | `OPERATOR` (cœur) |
| `POST` | `/dimensions/:id/snapshot` | — | résultat | `200` `404` `500` | `OPERATOR` (cœur) |
| `POST` | `/dimensions/:id/reset` | — | résultat | `200` `404` `500` | `OPERATOR` (cœur) |
| `POST` | `/dimensions/:id/toggle` | `{key}` | détail de la dimension | `200` `400` `404` `500` | `OPERATOR` (cœur) |
| `PUT` | `/dimensions/:id/snapshot/config` | `{trigger, hour?, minute?}` | config | `200` `400` `404` `500` | `OPERATOR` (cœur) |
| `POST` | `/dimensions/:id/whitelist` | `{playerName}` | `{...}` | `200` `400` `404` `500` | `OPERATOR` (cœur) |
| `DELETE` | `/dimensions/:id/whitelist/:uuid` | — | `{...}` | `200` `400` `404` | `OPERATOR` (cœur) |
| `POST` | `/dimensions/:id/builders` | `{playerName}` | `{...}` | `200` `400` `404` `500` | `OPERATOR` (cœur) |
| `DELETE` | `/dimensions/:id/builders/:uuid` | — | `{...}` | `200` `400` `404` | `OPERATOR` (cœur) |
| `GET` | `/dimensions/:id/players` | — | dimensions par joueur | `200` `404` | n'importe lequel |
| `DELETE` | `/dimensions/:id/players/:uuid8` | — | `{"deleted":true}` | `200` `400` `404` `500` | `OPERATOR` (cœur) |
| `POST` | `/dimensions/:id/players/:uuid8/reset` | — | `{"reset":true}` | `200` `400` `404` `500` | `OPERATOR` (cœur) |

`POST /dimensions` exige `name` et l'un de `genType` ou `customGenTypeId` :
`400 {"error":"name and (genType or customGenTypeId) are required"}`. Un `customGenTypeId`
inconnu donne `400 {"error":"Unknown customGenTypeId: <id>"}` ; un `genType` inconnu donne
`400 {"error":"Invalid genType: <valeur>"}`. Avec un type de génération personnalisé, un
`genType` invalide n'est *pas* une erreur : il retombe sur `NORMAL`, parce que l'énumération ne
sert qu'à choisir le type de dimension et le Y d'apparition par défaut.

L'identifiant est slugifié depuis le nom — minuscules, caractères non alphanumériques
réduits à `_`, `_` de début et de fin retirés, avec un repli sur `dim_<millis % 10000>` s'il ne
reste rien. Tu ne le choisis pas.

Certains champs du corps sont appliqués **avant** la création du monde parce qu'ils affectent
la génération — `seed`, `flatLayers`, `generateStructures` — et le reste après. C'est cette
séparation qui explique pourquoi la création passe par une fonction de configuration plutôt
que par des setters sur l'objet renvoyé.

`POST /dimensions/:id/toggle` bascule un seul booléen, nommé par `key`. Clés acceptées :
`pvp`, `mobSpawning`, `keepInventory`, `clearInventory`, `constructionMode`, `perPlayerMode`,
`commandBlocks`, `enderChest`, `generateStructures`, `cobblemonBlocked`, `enabled`,
`timeLocked`, `weatherLocked`, `allowHome`, `raidDensEnabled`, `borderEnabled`. Une `key`
absente donne `400` ; une clé **non reconnue** traverse le `switch` sans rien changer et répond
pourtant `200` avec le détail inchangé. Vérifie le champ que tu voulais basculer.

Les routes par joueur adressent une sous-dimension par les 8 premiers caractères hexadécimaux
de l'UUID du propriétaire, validés contre `[0-9a-f]{8}`. Un identifiant qui ne se résout pas
donne `404 {"error":"Player dimension not found for uuid8: <uuid8>"}`.

Les dimensions intégrées comme `minecraft:overworld` sont listées et lisibles, mais leurs
identifiants contiennent un deux-points, qui n'est pas un caractère de chemin légal sous
Windows. Le constructeur de résumé saute les recherches de snapshot et de dimensions par
joueur pour elles, plutôt que de fabriquer un chemin impossible. N'attends pas que les routes
de snapshot fonctionnent sur une dimension intégrée.

---

## Événements

`EventsApi`, 696 lignes. Le modèle et le cycle de vie sont dans [Événements](events.fr.md) ;
la chronologie interne d'un événement est dans [Chronologie](timeline.fr.md).

| Méthode | Chemin | Corps | Réponse | Statuts | Rôle |
|---|---|---|---|---|---|
| `GET` | `/events` | — | tableau de résumés | `200` | n'importe lequel |
| `POST` | `/events` | `{name?}` ou `{templateName}` | détail de l'événement | `200` `404` `500` | `OPERATOR` (cœur) |
| `GET` | `/events/:id` | — | détail de l'événement | `200` `400` `404` | n'importe lequel |
| `PUT` | `/events/:id` | champs partiels | détail de l'événement | `200` `400` `404` `500` | `OPERATOR` (cœur) |
| `DELETE` | `/events/:id` | — | `{"deleted":true}` | `200` `400` `404` `500` | `OPERATOR` (cœur) |
| `POST` | `/events/:id/announce` | — | détail de l'événement | `200` `404` `500` | `OPERATOR` (cœur) |
| `POST` | `/events/:id/start` | — | détail de l'événement | `200` `404` `500` | `OPERATOR` (cœur) |
| `POST` | `/events/:id/stop` | — | détail de l'événement | `200` `404` `500` | `OPERATOR` (cœur) |
| `GET` | `/events/:id/participants` | — | tableau, score décroissant | `200` `404` | n'importe lequel |
| `POST` | `/events/:id/participants` | `{playerName, role?}` | `{"added":true}` | `200` `400` `404` `500` | `OPERATOR` (cœur) |
| `DELETE` | `/events/:id/participants/:uuid` | — | `{"removed":true}` | `200` `400` `404` `500` | `OPERATOR` (cœur) |
| `POST` | `/events/:id/score` | `{playerName, amount, operation?}` | `{"updated":true}` | `200` `400` `404` `500` | `OPERATOR` (cœur) |
| `POST` | `/events/:id/save-template` | `{name?}` | `{"saved":true,"name":"..."}` | `200` `500` | `OPERATOR` (cœur) |
| `GET` | `/events/templates` | — | `[{"name":"..."}]` | `200` | n'importe lequel |
| `POST` | `/events/templates/:name/load` | — | détail de l'événement | `200` `400` `404` `500` | `OPERATOR` (cœur) |
| `DELETE` | `/events/templates/:name` | — | `{"deleted":true}` | `200` `400` `500` | `OPERATOR` (cœur) |
| `GET` | `/events/minigame-types` | — | le catalogue de mini-jeux | `200` | n'importe lequel |
| `GET` | `/events/launch-config` | — | config de lancement | `200` | n'importe lequel |
| `PUT` | `/events/launch-config` | champs de config | config de lancement | `200` | `OPERATOR` (cœur) |
| `GET` | `/events/prophunt/icons` | — | `[{"key":"..."}]` | `200` | n'importe lequel |
| `GET` | `/events/prophunt/icons/:key` | — | **octets PNG** | `200` `400` `404` | n'importe lequel |
| `POST` | `/events/prophunt/icons/:key` | `{imageBase64}` | `{"uploaded":true}` | `200` `400` | `OPERATOR` (cœur) |
| `DELETE` | `/events/prophunt/icons/:key` | — | `{"deleted":true}` | `200` `400` | `OPERATOR` (cœur) |

`POST /events` se comporte différemment selon le corps. Avec `templateName`, il résout le
template et renvoie l'événement obtenu **sans l'enregistrer ni le sauvegarder** — un aperçu.
Avec `name` (ou rien, avec `Nouvel evenement` par défaut), il crée, enregistre et sauvegarde un
nouvel événement. Pour charger un template *pour de vrai*, utilise
`POST /events/templates/:name/load`.

`PUT /events/:id` est une modification partielle, sauf pour `kitEntryCommands`,
`kitExitCommands`, `blacklistItems` et `acts`, qui sont vidés puis remplis quand ils sont
présents. Une valeur d'énumération invalide lève une exception dans la tâche du thread
serveur, ressort en `500`, et laisse l'événement partiellement modifié et sauvegardé.

`POST /events/:id/start` refuse un événement déjà en cours
(`500 {"error":"Event already running"}`) ; `stop` refuse un événement qui ne tourne pas
(`500 {"error":"Event not running"}`) ; `announce` refuse un événement déjà en cours. Il n'y a
pas de route de pause, parce que `ServerEvent.State` n'a pas de `PAUSED` — voir
[Événements](events.fr.md#cycle-de-vie).

`POST /events/:id/participants` exige que le joueur soit **connecté** :
`500 {"error":"Player not online: <nom>"}`. Une inscription hors des états `ANNOUNCED`,
`COUNTDOWN` ou `ACTIVE` échoue avec `500 {"error":"Failed to add participant"}`.

`POST /events/:id/score` prend une `operation` valant `add` (par défaut), `subtract` ou `set`.
Une valeur non reconnue se comporte silencieusement comme `add`. La cible est appariée par
nom, sans distinction de casse, parmi les participants existants ; un non-participant donne
`500 {"error":"Player not a participant"}`.

`/events/:id/participants` repose sur une `Map<UUID, EventParticipant>`. En Java,
`EventsApi.getParticipants()` renvoie une **`Map`**, pas une `List` — itère sur `.values()`.

Les clés d'icône PropHunt sont validées contre `[a-z0-9_]+` et doivent faire moins de 128
caractères : `400 {"error":"Invalid key"}`. Le corps d'envoi accepte soit une chaîne base64
nue, soit une URI complète `data:image/png;base64,...` — tout ce qui précède la première
virgule est retiré. Un `GET` sur une icône renvoie du `image/png` avec
`Cache-Control: public, max-age=60`, pas du JSON. Une méthode non prise en charge sur une clé
d'icône répond `405 {"error":"Method not allowed"}`, le seul `405` de tout le module.

---

## Joueurs

`PlayersApi`. Le comportement et les pièges sont dans [Joueurs](players.fr.md).

| Méthode | Chemin | Corps / paramètres | Réponse | Statuts | Rôle |
|---|---|---|---|---|---|
| `GET` | `/players` | — | tableau des joueurs connectés | `200` `503` | n'importe lequel |
| `GET` | `/players/offline` | `?n=1..100`, défaut 20 | tableau des déconnectés | `200` | n'importe lequel |
| `GET` | `/players/:name` | — | détail, ou une ébauche si déconnecté | `200` | n'importe lequel |
| `POST` | `/players/:name/kick` | `{message?}` | `{"kicked":true}` | `200` `404` `500` | `OPERATOR` (cœur) |
| `POST` | `/players/:name/tp` | `{x?,y?,z?,dimension?}` | `{"teleported":true}` | `200` `404` `500` | `OPERATOR` (cœur) |
| `POST` | `/players/:name/gamemode` | `{mode}` | `{"changed":true}` | `200` `400` `404` `500` | `OPERATOR` (cœur) |
| `POST` | `/players/:name/message` | `{text, type?}` | `{"sent":true}` | `200` `400` `404` `500` | `OPERATOR` (cœur) |

`:name` est un **nom** de joueur, décodé depuis l'URL. `GET /players/:name` ne répond jamais
`404` — un nom inconnu renvoie `{"name":"...","online":false}`. Les quatre routes d'action,
elles, répondent bien `404 {"error":"Player not online"}`.

`mode` doit valoir `SURVIVAL`, `CREATIVE`, `ADVENTURE` ou `SPECTATOR` après passage en
majuscules ; tout le reste donne `500 {"error":"Invalid gamemode: <valeur>"}`. Le `type` de la
route message vaut `chat`, `actionbar`, `title` ou `subtitle`, toute valeur inconnue retombant
sur `chat`.

---

## Chunks

`ChunksApi`. La sémantique, le contrat de sauvegarde et la granularité au fichier de région
sont dans [Chunks](chunks.fr.md).

| Méthode | Chemin | Corps | Réponse | Statuts | Rôle |
|---|---|---|---|---|---|
| `GET` | `/chunks/sets` | — | tableau de résumés d'ensembles | `200` `503` | n'importe lequel |
| `POST` | `/chunks/sets` | `{name, dimension?, chunks?}` | `{"created":true,"name":"..."}` | `200` `400` `500` | `OPERATOR` (cœur) |
| `GET` | `/chunks/sets/:name` | — | détail de l'ensemble avec ses entrées | `200` `404` | n'importe lequel |
| `DELETE` | `/chunks/sets/:name` | — | `{"deleted":true}` | `200` `500` | `OPERATOR` (cœur) |
| `DELETE` | `/chunks/sets/:name/entry` | `{x, z, radius}` | `{"removed":true}` | `200` `400` `500` | `OPERATOR` (cœur) |
| `POST` | `/chunks/sets/:name/reset` | — | un `DeletionResult` | `200` `400` `404` `500` | `OPERATOR` (cœur) |
| `PUT` | `/chunks/sets/:name/schedule` | `{trigger, dimension?, hour?, minute?, days?}` | l'horaire | `200` `400` `404` | `OPERATOR` (cœur) |
| `GET` | `/chunks/suggestions` | — | tableau de suggestions | `200` | n'importe lequel |
| `DELETE` | `/chunks/suggestions/:id` | — | `{"dismissed":true}` | `200` `500` | `OPERATOR` (cœur) |
| `POST` | `/chunks/suggestions/:id/accept` | `{setName, radius?}` | `{"accepted":true,"setName":"..."}` | `200` `400` `409` `500` | `OPERATOR` (cœur) |

Les noms d'ensembles sont décodés depuis l'URL et servent directement de clé de stockage, sans
validation d'identifiant — un nom contenant une barre oblique ne peut pas être adressé, parce
que le routeur découpe sur la première `/`.

⚠️ `POST /chunks/sets/:name/reset` **supprime des fichiers de région de la sauvegarde du
monde**. Il est protégé par `OPERATOR`, comme toutes les autres mutations ici, parce que le
répartiteur du cœur ne connaît que cette barre. Une sauvegarde préalable est obligatoire et
vérifiée, et la suppression est abandonnée si la sauvegarde échoue — mais il n'y a aucune
distinction de rôle entre réinitialiser le monde et renommer un ensemble de chunks.

`POST /chunks/suggestions/:id/accept` est la seule route du module qui renvoie `409` au moyen
d'un test sur le message : une incompatibilité de dimension entre la suggestion et l'ensemble
visé devient un `409`, tout le reste un `500`.

`DELETE /chunks/sets/:name/entry` exige les trois valeurs `x`, `z` et `radius`, puisque
l'égalité d'une entrée porte sur ce triplet. Se tromper de rayon donne
`500 {"error":"Entry not found in set"}`.

---

## Courses

`RacesApi`. Le scan, la carte de relief et les réglages du circuit sont dans
[Courses](races.fr.md).

| Méthode | Chemin | Corps | Réponse | Statuts | Rôle |
|---|---|---|---|---|---|
| `POST` | `/races/scan` | `{zoneId, blockMappings, eventId?}` | un `ScanResult` | `200` `400` `500` | `OPERATOR` (`requireWrite`) |
| `POST` | `/races/heightmap` | `{zoneId}` | `{width, depth, minX, minZ, image}` | `200` `400` `500` | `OPERATOR` (cœur) |
| `GET` | `/races/:eventId/track` | — | la config du circuit | `200` `400` | n'importe lequel |
| `PUT` | `/races/:eventId/track` | réglages partiels | la config du circuit | `200` `400` | `OPERATOR` (`requireWrite`) |
| `DELETE` | `/races/:eventId/track` | — | `{"ok":true}` | `200` `400` | `OPERATOR` (`requireWrite`) |

Trois de ces routes se gardent correctement, depuis le 29 octobre 2026 :

```java
if (!WebDashboard.requireWrite(exchange)) return;
```

Avant cette date, les mêmes appels jetaient la valeur de retour, ce qui envoyait un `403` puis
exécutait le travail quand même. `POST /races/heightmap` n'a toujours pas de garde propre et
s'appuie sur le cœur, comme indiqué.

`GET /races/:eventId/track` renvoie une **config par défaut vide** plutôt qu'un `404` quand
aucun circuit n'a été scanné pour cet événement, parce que « pas encore configuré » est l'état
normal. Un `PUT` sur un événement sans config en crée une.

`/races/:eventId/track` est reconnu par l'expression régulière `/races/[^/]+/track`, et
l'identifiant d'événement est ensuite validé par le helper standard. Une méthode autre que
`GET`, `PUT` ou `DELETE` sur ce chemin répond `405`.

---

## Prison

`PrisonApi`. Le comportement est dans [Prison](prison.fr.md).

| Méthode | Chemin | Corps | Réponse | Statuts | Rôle |
|---|---|---|---|---|---|
| `GET` | `/prison` | — | `{active, history, config}` | `200` `503` | n'importe lequel |
| `GET` | `/prison/active` | — | entrées actives avec `remainingMs` | `200` | n'importe lequel |
| `GET` | `/prison/history` | — | entrées libérées, plus récentes d'abord | `200` | n'importe lequel |
| `GET` | `/prison/config` | — | zone, message d'accueil, point de sortie | `200` | n'importe lequel |
| `PUT` | `/prison/config` | `{prisonZoneId?, welcomeMessage?, releasePoint?}` | la config | `200` `400` | `OPERATOR` (`requireWrite`) |
| `POST` | `/prison` | `{uuid\|name, durationMs\|duration, reason?}` | la nouvelle entrée | `200` `400` `404` `500` | `OPERATOR` (`requireWrite`) |
| `PUT` | `/prison/:uuid` | `{endsAt\|durationMs\|duration\|extendMs}` | l'entrée mise à jour | `200` `400` `404` | `OPERATOR` (`requireWrite`) |
| `DELETE` | `/prison/:uuid` | — | `{"released":true}` | `200` `500` | `OPERATOR` (`requireWrite`) |

C'est l'API à copier si tu en ajoutes une neuvième : chaque route qui modifie quelque chose
teste la valeur de retour de `requireWrite`, les lectures sont ouvertes, et les chemins
d'écriture valident avant de toucher à l'état.

`PUT /prison/config` valide `prisonZoneId` contre le stockage des zones et répond
`400 {"error":"Zone not found: <id>"}`. Un `releasePoint` à `null` l'efface ; un objet doit
porter les quatre champs `world`, `x`, `y`, `z`, avec `yaw` et `pitch` facultatifs, et un objet
auquel manque l'un des quatre est **ignoré sans un mot** plutôt que refusé.

`POST /prison` refuse de travailler sans zone de prison configurée
(`400 {"error":"Prison zone not configured"}`), exige une durée positive
(`400 {"error":"Duration is required and must be > 0"}`), et résout le joueur par la liste des
connectés puis par le cache utilisateur (`404 {"error":"Player not found"}`).

`PUT /prison/:uuid` sur quelqu'un qui n'est pas actuellement emprisonné donne
`404 {"error":"Not imprisoned"}`, et un `endsAt` dans le passé donne
`400 {"error":"endsAt must be in the future"}`.

Un corps JSON malformé sur n'importe quelle route prison donne
`400 {"error":"Invalid JSON"}` — un corps **vide**, en revanche, est traité comme un objet
vide : un `PUT` sans corps est donc une opération neutre qui renvoie l'état courant.

---

## WebSocket

CheeseGuard publie sur exactement **un** canal : `prison`. Abonne-toi de la façon habituelle :

```json
{"type":"subscribe","channels":["prison"]}
```

| `type` | Envoyé quand |
|---|---|
| `prison_added` | une peine commence, depuis la commande ou depuis l'API |
| `prison_released` | une peine expire ou est graciée |
| `prison_updated` | `endsAt` est ajusté via `PUT /prison/:uuid` |

Le payload porte la `PrisonEntry` complète sous `entry`, plus `playerName`, `reason`,
`durationMs`, `duration`, `byName`, `releaseReason` et `releasedByName` aplatis au premier
niveau, pour que `DiscordWebhookManager` puisse les récupérer :

```json
{
  "type": "prison_added",
  "entry": { "playerUuid": "...", "playerName": "Steve", "reason": "griefing", "startedAt": 1761700000000, "durationMs": 7200000, "endsAt": 1761707200000, "issuedBy": "dashboard:roicheese", "issuedByName": "roicheese" },
  "playerName": "Steve",
  "reason": "griefing",
  "durationMs": 7200000,
  "duration": "2h",
  "byName": "roicheese"
}
```

**Rien d'autre n'est diffusé.** Il n'y a ni `event_update`, ni `dim_update`, ni `snapshot_done`
en provenance de ce mod : `DashboardRegistry.broadcast` n'apparaît qu'une seule fois dans tout
le code, dans `PrisonListener`. Les noms de canaux sont des chaînes libres dans le serveur
WebSocket de CheeseDash : une vue du dashboard peut donc s'abonner à `events` ou à
`dimensions` sans erreur — elle ne recevra simplement jamais rien. Ces vues interrogent leurs
routes `GET` en boucle, et c'est ce que doit faire tout ce que tu construiras.

---

← [Courses](races.fr.md) · [Architecture](architecture.fr.md) →
