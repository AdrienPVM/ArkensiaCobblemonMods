[← CheeseMusic](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](dashboard.md)

# API dashboard

CheeseMusic enregistre un module [CheeseDash](../../CheeseDash/) : il n'a donc aucune
commande et aucun éditeur en jeu. L'API REST ci-dessous, ou l'édition manuelle de
[`config/cheesemusic.json`](configuration.fr.md) suivie d'un redémarrage, sont les seules
façons de gérer les assignations.

Le module est déclaré dans `fabric.mod.json` sous l'entrypoint custom `cheesedash` :

```json
"entrypoints": { "cheesedash": ["com.cheesemusic.dashboard.CheeseMusicDashboardModule"] }
```

CheeseDash le découvre au démarrage. CheeseDash absent — il est dans `suggests`, pas dans
`depends` — l'entrypoint n'est jamais invoqué, le module ne se charge jamais, et le mod
démarre normalement. La musique de zone continue de fonctionner avec ce qui est déjà dans
le fichier de configuration ; tu n'as simplement aucun moyen d'y changer quoi que ce soit
sans redémarrer.

| | |
|---|---|
| **Préfixe** | `music` |
| **Chemin de base** | `/api/v1/music` |
| **Libellé dans la barre latérale** | CheeseMusic |
| **Icône** | 🎵 |
| **Description** | `Musique de zones` — français codé en dur dans `CheeseMusicDashboardModule`, hors i18n |
| **Canaux WebSocket** | **aucun** — voir [Pas de WebSocket](#pas-de-websocket) |

## Authentification

Chaque route exige un token de session, comme partout dans CheeseDash. Le champ de la
réponse de connexion est `sessionId`, et c'est cette valeur qui va dans l'en-tête
`Authorization` :

```bash
TOKEN=$(curl -s -X POST http://localhost:44311/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"token":"<ton master token>"}' | jq -r .sessionId)

curl -H "Authorization: Bearer $TOKEN" http://localhost:44311/api/v1/music/assignments
```

## Rôles

`MusicApi` appelle `WebDashboard.requireWrite(exchange)` sur ses six routes mutantes, et
rien du tout sur ses cinq routes de lecture. `requireWrite` vaut
`requireRole(exchange, OPERATOR)`, qui répond `403` et renvoie `false` quand le rôle de la
session est inférieur à `OPERATOR`.

Donc : **les lectures sont ouvertes à n'importe quelle session authentifiée, `READ_ONLY`
comprise ; les écritures exigent `OPERATOR` ou `SUPER_ADMIN`.** Aucune route de ce mod
n'exige `SUPER_ADMIN` ; `requireAdmin` n'est jamais appelée.

Ces appels ne sont cependant pas la frontière. Le répartiteur de modules de CheeseDash,
dans `DashboardApi`, garde déjà tous les modules :

```java
DashboardModule module = DashboardRegistry.getModule(prefix);
if (module != null) {
    if (!method.equals("GET") && !WebDashboard.requireWrite(exchange)) {
        return;
    }
    module.handleRequest(exchange, subRoute);
```

Toute requête non-`GET` vers `/api/v1/music/` exige donc `OPERATOR` avant même d'atteindre
`MusicApi`. Les six appels de `MusicApi` sont de la défense en profondeur : utiles, et
redondants dans cette configuration. Trois conséquences de l'endroit où se trouve la vraie
frontière :

- **Aucune route music ne peut exiger `SUPER_ADMIN` par le seul répartiteur.** Le cœur ne
  connaît que `requireWrite` : `OPERATOR` est donc le plafond, à moins qu'un handler
  n'appelle `requireAdmin` lui-même. Aucun ne le fait ici.
- **Un `GET` qui modifierait quelque chose ne serait gardé que par le module.** Il n'en
  existe aucun aujourd'hui — les cinq routes de lecture ne font bien que lire — mais le
  cœur ne l'attraperait pas.
- **Une modification du répartiteur ne désarmerait pas ce mod.** Contrairement aux modules
  qui n'appellent aucun garde, les six routes d'écriture de `MusicApi` continueraient de
  vérifier le rôle elles-mêmes.

> Les gardes au niveau du module ont été réparés le 29 octobre 2026. Ils étaient
> auparavant écrits `WebDashboard.requireWrite(exchange);` en instruction nue, la valeur de
> retour jetée — ce qui envoyait le `403` puis appliquait quand même la modification.
> C'était un vrai défaut du module, mais pas un trou exploitable à travers CheeseDash : le
> répartiteur ci-dessus avait déjà rejeté toute requête non-`GET` venant d'une session
> inférieure à `OPERATOR`, donc un compte `READ_ONLY` n'atteignait jamais ces handlers. La
> forme actuelle est `if (!WebDashboard.requireWrite(exchange)) return;` sur les six routes
> d'écriture : le module applique désormais le rôle de lui-même aussi. Si tu lis une
> version plus ancienne de ce mod, audite ce jar plutôt que de te fier à cette page.

## Disponibilité du module

`CheeseMusicDashboardModule.handleRequest` répond `503` avec
`{"error":"CheeseMusic not ready"}` avant d'atteindre `MusicApi` quand la référence au
serveur, l'instance du mod ou la configuration sont encore nulles. C'est la fenêtre entre
le moment où CheeseDash accepte les connexions HTTP et celui où le gestionnaire
`SERVER_STARTED` de CheeseMusic s'exécute. Elle est courte, et c'est aussi ce que tu
obtiens si le démarrage de CheeseMusic a levé une exception.

---

## Assignations

### `GET /api/v1/music/assignments`

Toutes les assignations, en tableau JSON d'objets d'assignation. Rôle : n'importe lequel.

```json
[
  {
    "zoneId": "volcano_cave",
    "zoneName": "Volcano Cave",
    "tracks": ["volcano_theme"],
    "mode": "SEQUENTIAL",
    "volume": 0.5,
    "fadeInTicks": 40,
    "fadeOutTicks": 80
  }
]
```

Note que la réponse est construite depuis les **valeurs** de la table : la clé de recherche
n'est donc pas dans la sortie. Une entrée créée par `POST` a une clé égale à son `zoneId`
(une assignation par tag porte `"zoneId": "tag:cave"`) : la réponse suffit alors. Seule
une entrée dont le `zoneId` a été modifié par un `PUT` ou à la main affiche une valeur qui
n'est pas sa clé. `[]` quand rien n'est assigné.

### `POST /api/v1/music/assignments`

Créer ou remplacer une assignation. Rôle : **OPERATOR**.

Corps : un objet d'assignation, analysé par `MusicAssignment.fromJson`. Seul `zoneId` est
obligatoire ; tous les autres champs retombent sur les valeurs littérales listées dans
[configuration](configuration.fr.md#objets-dassignation).

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"zoneId":"spawn_plaza","zoneName":"Spawn Plaza","tracks":["spawn_theme"],"mode":"RANDOM","volume":0.7,"fadeInTicks":60,"fadeOutTicks":60}' \
  http://localhost:44311/api/v1/music/assignments
```

La réponse est l'assignation stockée, renvoyée par `toJson()`. La clé de la table est prise
sur le `zoneId` du corps, et `config.save()` réécrit tout le fichier de configuration avant
l'envoi de la réponse. Une entrée existante sous la même clé est écrasée sans avertissement
— il n'y a pas d'erreur « existe déjà ».

| Statut | Corps | Quand |
|---|---|---|
| `200` | l'assignation | stockée et sauvegardée |
| `400` | `{"error":"Missing zoneId"}` | `zoneId` est présent mais vide ou composé d'espaces |
| `403` | celui de CheeseDash | rôle inférieur à `OPERATOR` |
| `500` | `{"error":"Internal error"}` | `zoneId` **absent**, JSON malformé, ou corps qui n'est pas un objet |

Cette dernière ligne est une aspérité. Un corps sans aucun `zoneId` fait lever une `NPE` à
`obj.get("zoneId").getAsString()`, que le `catch` externe du gestionnaire convertit en
`500`. Seul un `zoneId` présent mais vide atteint le `400` prévu. La cause est dans le log
dans les deux cas :

```text
[CheeseMusic] API error: null
```

### `PUT /api/v1/music/assignments/{clé}`

Remplacer une assignation existante. Rôle : **OPERATOR**.

| Paramètre | Où | Notes |
|---|---|---|
| `clé` | chemin | la clé de recherche. Une clé `tag:` fonctionne telle quelle (`tag:cave`) ou encodée (`tag%3Acave`) : le chemin est décodé par `getPath()` puis une seconde fois par `URLDecoder`, si bien qu'un `+` littéral dans une clé devient une espace |

Corps : un objet d'assignation complet. C'est un remplacement, pas un patch — les champs
omis retombent sur leurs valeurs par défaut au lieu de conserver les anciennes.

```bash
curl -X PUT -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"zoneId":"volcano_cave","zoneName":"Volcano Cave","tracks":["volcano_theme","volcano_calm"],"mode":"SHUFFLE","volume":0.45,"fadeInTicks":40,"fadeOutTicks":120}' \
  http://localhost:44311/api/v1/music/assignments/volcano_cave
```

| Statut | Corps | Quand |
|---|---|---|
| `200` | l'assignation mise à jour | remplacée et sauvegardée |
| `403` | celui de CheeseDash | rôle inférieur à `OPERATOR` |
| `404` | `{"error":"Assignment not found"}` | aucune entrée sous cette clé |
| `500` | `{"error":"Internal error"}` | corps sans `zoneId`, ou illisible |

⚠️ La clé stockée est celle du **chemin**, tandis que le `zoneId` de l'objet stocké vient
du **corps**. Envoyer un corps dont le `zoneId` contredit le chemin laisse les deux
durablement désaccordés. Les recherches utilisent la clé, donc la musique continue de
fonctionner ; le dashboard et la réponse du `GET` montrent la valeur du corps. Garde-les
identiques.

### `DELETE /api/v1/music/assignments/{clé}`

Supprimer une assignation. Rôle : **OPERATOR**.

```bash
curl -X DELETE -H "Authorization: Bearer $TOKEN" \
  http://localhost:44311/api/v1/music/assignments/volcano_cave
```

```json
{"ok":true}
```

`404` avec `{"error":"Assignment not found"}` si la clé est inconnue. Le fichier de
configuration est réécrit immédiatement. Les joueurs présents dans la région ne reçoivent
pas de `stop` ici — le tracker s'en aperçoit à sa vérification suivante et envoie un
`stop` portant `defaultFadeOutTicks` : la piste est coupée et ReactiveMusic revient sur
cette durée.

---

## Pistes

Ces routes agissent sur les fichiers `.ogg` du répertoire de resource pack du serveur. Lis
d'abord [packs de sons](sound-packs.fr.md) : un fichier sur le serveur n'atteint aucun
client tout seul.

### `GET /api/v1/music/tracks`

Les noms assainis que `SoundScanner` a trouvés à son dernier scan. Rôle : n'importe lequel.

```json
["spawn_theme", "volcano_calm", "volcano_theme"]
```

Triés par ordre alphabétique. `[]` si le scanner est nul (impossible dès lors que le module
répond) ou si le répertoire est vide. Ce sont exactement les chaînes que les entrées
`tracks` d'une assignation doivent utiliser.

### `POST /api/v1/music/tracks/upload?name={nom de fichier}`

Téléverser un `.ogg`. Rôle : **OPERATOR**.

| Paramètre | Où | Notes |
|---|---|---|
| `name` | requête | le nom de fichier d'origine, encodé URL, **doit finir par `.ogg`** |
| — | corps | les octets bruts du fichier. Ni multipart, ni base64 |

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  --data-binary "@Volcano Theme.ogg" \
  "http://localhost:44311/api/v1/music/tracks/upload?name=Volcano%20Theme.ogg"
```

```json
{"ok":true,"track":"volcano_theme"}
```

`track` est le nom assaini, et c'est lui qu'il faut mettre dans une assignation. Le
téléversement écrit le fichier, puis déclenche un scan complet, qui réécrit `sounds.json`.
Le serveur consigne :

```text
[CheeseMusic] Track uploaded: Volcano Theme.ogg -> volcano_theme
```

| Statut | Corps | Quand |
|---|---|---|
| `200` | `{"ok":true,"track":"..."}` | écrit et rescanné |
| `400` | `{"error":"Invalid filename. Must be a .ogg file."}` | `name` manquant, ne finissant pas par `.ogg`, ou contenant `..`, `/` ou `\` |
| `400` | `{"error":"Empty file"}` | corps de longueur nulle |
| `400` | `{"error":"File too large (max 50MB)"}` | corps au-delà de 52 428 800 octets |
| `403` | celui de CheeseDash | rôle inférieur à `OPERATOR` |
| `503` | `{"error":"SoundScanner not ready"}` | le scanner est nul |

Le contenu n'est jamais inspecté. Téléverse un JPEG renommé en `.ogg` et le serveur
l'accepte, le liste comme piste, et le client échoue en silence quand on lui demande de le
jouer. Tout le corps est lu en mémoire **avant** le contrôle de taille : un téléversement de
4 Go est donc une allocation de 4 Go sur le thread HTTP — n'expose pas le port 44311 à
Internet.

### `DELETE /api/v1/music/tracks/{nom}`

Supprimer un `.ogg` et rescanner. Rôle : **OPERATOR**.

`nom` est le nom de piste assaini **sans** l'extension `.ogg`, décodé depuis l'URL.

```bash
curl -X DELETE -H "Authorization: Bearer $TOKEN" \
  http://localhost:44311/api/v1/music/tracks/volcano_theme
```

```json
{"ok":true}
```

| Statut | Corps | Quand |
|---|---|---|
| `200` | `{"ok":true}` | supprimé, ou fichier déjà absent — `deleteTrack` vérifie l'existence et reste muet |
| `400` | `{"error":"Invalid track name"}` | vide, littéralement `upload`, ou contenant `..`, `/` ou `\` |
| `403` | celui de CheeseDash | rôle inférieur à `OPERATOR` |
| `503` | `{"error":"SoundScanner not ready"}` | le scanner est nul |

Le rejet de `upload` existe parce que le dispatch teste `path.startsWith("/tracks/")` avant
tout le reste : sans lui, `DELETE /api/v1/music/tracks/upload` tenterait de supprimer un
fichier nommé `upload.ogg`.

Supprimer une piste qu'une assignation référence encore laisse une référence pendante. Rien
ne t'avertit, et le client qui la reçoit consigne un `warn` et ne joue rien. Vérifie
`GET /assignments` avant de supprimer.

---

## Zones

### `GET /api/v1/music/zones`

Toutes les régions CheeseGuard, pour remplir un sélecteur. Rôle : n'importe lequel.

```json
[
  {"id": "volcano_cave", "name": "Volcano Cave", "priority": 10, "tags": ["cave", "danger"]}
]
```

Lues via `CheeseGuardHook.getAllZones()`, qui enchaîne `CheeseGuardMod.INSTANCE` →
`getRegionManager()` → `getStorage()` → `getRegions()` par réflexion. CheeseGuard absent, ou
hook cassé, la réponse est `[]` et le statut reste `200`. Un tableau vide signifie donc soit
« aucune région », soit « pas de CheeseGuard » — c'est le log du démarrage qui te dit
laquelle :

```text
[CheeseMusic] CheeseGuard detected — zone music enabled
[CheeseMusic] CheeseGuard not installed — zone music disabled
```

`priority` et `tags` viennent directement de la région. `tags` est ce qu'une clé
d'assignation `tag:<tag>` met en correspondance.

---

## Configuration

### `GET /api/v1/music/config`

Les cinq clés globales. Rôle : n'importe lequel. Les assignations **ne** sont **pas**
incluses.

```json
{
  "enabled": true,
  "defaultFadeInTicks": 60,
  "defaultFadeOutTicks": 60,
  "checkIntervalTicks": 10,
  "defaultVolume": 0.7
}
```

### `PUT /api/v1/music/config`

Patcher n'importe quel sous-ensemble de ces cinq clés. Rôle : **OPERATOR**.

```bash
curl -X PUT -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"enabled":true,"checkIntervalTicks":5}' \
  http://localhost:44311/api/v1/music/config
```

```json
{"ok":true}
```

Chaque clé n'est appliquée que si elle est présente : c'est donc un vrai patch. Deux setters
bornent : `checkIntervalTicks` entre 1 et 100, et `defaultVolume` entre 0.0 et 1.0. Les clés
de fondu ne sont pas bornées et acceptent des négatifs, que le client plafonne de toute
façon à un plancher de 20 ticks.

Les cinq prennent effet au tick serveur suivant — il n'y a rien à recharger.
`defaultFadeInTicks` et `defaultVolume` sont acceptées, sauvegardées, puis
[jamais lues par quoi que ce soit](configuration.fr.md#les-clés-qui-ne-font-rien).

Un `500` ici signifie que le corps n'était pas un objet JSON, ou qu'un champ portait le
mauvais type (`"enabled":"yes"` lève une exception dans le `getAsBoolean` de Gson).

---

## Statut

### `GET /api/v1/music/status`

Qui écoute quoi, en ce moment. Rôle : n'importe lequel.

```json
[
  {"playerUuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5", "zoneId": "volcano_cave", "playerName": "Notch"}
]
```

Construit depuis `ZoneMusicTracker.getActiveZoneMusic()`, qui n'inclut un joueur que si son
`currentZoneId` est non nul **et** qu'il n'est pas marqué comme en combat Cobblemon. Un
joueur dont la musique a été étouffée par un combat disparaît de la liste et y revient à la
fin du combat, ce qui en fait, par effet de bord, un indicateur de combat utilisable.

`playerName` vaut `?` quand l'UUID ne correspond plus à un joueur connecté. C'est une course
avec la déconnexion, pas un bug à poursuivre.

Cet endpoint reflète **ce que le serveur a dit au client de jouer**, pas ce que le client
émet réellement. Un client coincé en `FADING_RM_OUT` faute de ReactiveMusic y apparaît comme
écoutant tranquillement. Quand un joueur signale du silence, cet endpoint confirme que le
serveur a fait sa moitié et t'oriente vers
[lecture](playback.fr.md#ce-qui-se-passe-sans-reactivemusic).

---

## Routes inconnues

Tout ce que le dispatch ne reconnaît pas tombe sur un `404` avec
`{"error":"Not found"}` — y compris une méthode que le chemin ne gère pas, comme
`POST /api/v1/music/config`. Il n'y a pas de `405`.

Toute exception non rattrapée ailleurs dans `MusicApi.handle` devient un `500`
`{"error":"Internal error"}`, avec le vrai message et une trace complète dans le log du
serveur, sous `[CheeseMusic] API error:`.

## Pas de WebSocket

CheeseMusic ne publie **rien** sur aucun canal WebSocket. Il n'y a aucun appel à
`DashboardRegistry.broadcast` ni à aucune autre méthode de diffusion dans tout le mod, donc
rien n'arrive de lui sur le port 44312. Une vue dashboard qui veut de l'état en direct doit
interroger `GET /api/v1/music/status` en boucle.

Deux choses qu'il serait naturel de diffuser et qui ne le sont pas : un joueur entrant dans
une région qui a de la musique, et la fin d'un téléversement de piste. Les deux seraient de
petits ajouts, respectivement dans `ZoneMusicTracker.tickPlayer` et dans `MusicApi`.

---

← [Configuration](configuration.fr.md) · [Architecture](architecture.fr.md) →
