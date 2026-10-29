[← CheeseRaids](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](dashboard.md)

# API dashboard

CheeseRaids enregistre un module [CheeseDash](../../CheeseDash/) sous le préfixe **`raids`** :
toutes les routes vivent donc sous `/api/v1/raids/*`. Le module est découvert via l'entrypoint
custom Fabric déclaré dans `fabric.mod.json` :

```json
"entrypoints": { "cheesedash": ["com.cheeseraids.dashboard.CheeseRaidsDashboardModule"] }
```

Dans la barre latérale, il apparaît sous le nom **CheeseRaids**, avec la description *Raids
serveur (invasions)*.

CheeseDash sert le HTTP sur le port **44311** et le WebSocket sur **44312**. Chaque route
ci-dessous exige un token de session dans l'en-tête `Authorization` ; tu l'obtiens par
`POST /api/v1/auth/login`, le seul endpoint non authentifié de tout le dashboard.

```bash
TOKEN=$(curl -s -X POST http://localhost:44311/api/v1/auth/login \
  -H 'Content-Type: application/json' -d '{"token":"<master token>"}' | jq -r .sessionId)

curl -s http://localhost:44311/api/v1/raids/ -H "Authorization: Bearer $TOKEN"
```

---

## Rôles

Six routes, deux niveaux de rôle. Les routes de lecture n'appellent aucune garde : n'importe
quelle session authentifiée peut les utiliser, y compris **READ_ONLY**. Les trois routes
mutantes appellent `WebDashboard.requireWrite`, ce qui veut dire **OPERATOR ou SUPER_ADMIN**.
Aucune route n'appelle `requireAdmin` : rien ici n'est réservé à SUPER_ADMIN.

| Méthode | Chemin | Rôle | Effet |
|---|---|---|---|
| `GET` | `/api/v1/raids/` | n'importe lequel | vue d'ensemble combinée : résumé des définitions + instances actives |
| `GET` | `/api/v1/raids/overview` | n'importe lequel | identique à la précédente |
| `GET` | `/api/v1/raids/definitions` | n'importe lequel | chaque définition, sérialisée en entier |
| `GET` | `/api/v1/raids/instances` | n'importe lequel | instances actives seulement |
| `POST` | `/api/v1/raids/start` | OPERATOR | démarre un raid |
| `POST` | `/api/v1/raids/stop` | OPERATOR | arrête un raid et nettoie |
| `POST` | `/api/v1/raids/reload` | OPERATOR | relit les fichiers de définition |

Ces appels à `requireWrite` ne sont pas la seule chose qui applique cette colonne. Le
répartiteur de modules de CheeseDash, dans `DashboardApi`, garde tous les modules avant
même d'atteindre `RaidsApi` :

```java
DashboardModule module = DashboardRegistry.getModule(prefix);
if (module != null) {
    if (!method.equals("GET") && !WebDashboard.requireWrite(exchange)) {
        return;
    }
    module.handleRequest(exchange, subRoute);
```

Les trois appels propres à `RaidsApi` sont donc de la défense en profondeur : utiles, et
redondants dans cette configuration. Trois conséquences du fait que la vraie frontière
siège dans le cœur :

- **Aucune route raids ne peut exiger `SUPER_ADMIN`.** Le répartiteur ne connaît que
  `requireWrite` : `OPERATOR` est donc le plafond, à moins qu'un handler n'appelle
  `requireAdmin` lui-même, et aucun ne le fait. Démarrer un raid, en arrêter un et relire
  tous les fichiers de définition depuis le disque sont toutes des opérations de niveau
  `OPERATOR`.
- **Un `GET` qui modifierait quelque chose ne serait attrapé que par le module.** Les
  quatre routes de lecture ne font bien que lire, mais le répartiteur ne regarde que la
  méthode HTTP et laisserait passer telle quelle une route de ce genre.
- **Une modification du répartiteur ne désarmerait pas ces trois routes**, puisqu'elles
  vérifient le rôle elles-mêmes — à la différence des modules qui ne gardent rien.

Tout le reste sous ce préfixe renvoie `404 {"error":"Not found"}`. Une requête qui arrive avant
la fin du démarrage du serveur renvoie `503 {"error":"CheeseRaids not ready"}`. Une exception
non rattrapée renvoie `500 {"error":"Internal server error"}` et journalise la cause côté
serveur.

---

## `GET /api/v1/raids/` · `GET /api/v1/raids/overview`

Aucun paramètre. C'est la route que la vue du dashboard appelle réellement.

```json
{
  "definitions": [
    {
      "id": "hoopa",
      "displayName": "Invasion Hoopa",
      "icon": "👻",
      "enabled": true,
      "autoTrigger": false,
      "hour": 20,
      "minute": 0,
      "everyNDays": 1,
      "active": true,
      "phase": "OPEN"
    }
  ],
  "instances": [ /* voir plus bas */ ]
}
```

`definitions[]` est ici un **résumé**, pas la définition entière : dix champs, de quoi
afficher une carte. `active` indique si une instance existe, et `phase` n'est présent que
lorsqu'elle existe.

## `GET /api/v1/raids/definitions`

Aucun paramètre. Les objets `RaidDefinition` complets, sérialisés par Gson à l'identique —
chaque bloc imbriqué, y compris les clés que la [configuration](configuration.fr.md) signale
comme inutilisées. C'est la seule route qui te montre exactement ce que le serveur a en
mémoire, ce qui en fait le bon moyen de confirmer qu'un `reload` a bien pris ton édition.

```json
[
  {
    "id": "hoopa",
    "displayName": "Invasion Hoopa",
    "icon": "👻",
    "enabled": true,
    "schedule": { "autoTrigger": false, "hour": 20, "minute": 0, "everyNDays": 1 },
    "announce": { "title": "Invasion Hoopa", "subtitle": "…", "chat": "…", "discord": true, "discordMessage": "…" },
    "wave":  { "arena": { "world": "minecraft:overworld", "x": 0.5, "y": 65.0, "z": 0.5, "yaw": 0.0, "pitch": 0.0 }, "spread": 6.0, "spawns": [ … ], "…": "…" },
    "npc":   { "easyNpcName": "hooh-raid", "entityType": "easy_npc:humanoid_slim", "pos": { … }, "…": "…" },
    "arena": { "model": "SHARED", "riftDimId": "hoopa_rift", "center": { … }, "…": "…" },
    "boss":  { "provider": "CUSTOM_GIANT", "species": "hoopa", "aspects": "unbound", "…": "…" },
    "reward":{ "portalDivisor": 3, "minPortals": 1, "portalAnchor": { … }, "…": "…" },
    "exitPos": { "world": "minecraft:overworld", "x": 0.5, "y": 65.0, "z": 0.5, "yaw": 0.0, "pitch": 0.0 }
  }
]
```

Il n'existe **aucune contrepartie en écriture.** Les définitions sont en lecture seule côté
REST : pas de `PUT`, pas de `POST /definitions`, pas de `DELETE`. Modifier un raid veut dire
éditer le fichier JSON sur le disque et appeler `reload`.

## `GET /api/v1/raids/instances`

Aucun paramètre. Un objet par instance active ; un tableau vide quand rien ne tourne.

```json
[
  {
    "raidId": "hoopa",
    "phase": "COMBAT",
    "participants": 4,
    "waveTotal": 5,
    "bossSpawned": true,
    "players": [
      { "name": "RoiCheese", "slot": 0, "entered": true, "arrived": true,  "finished": false },
      { "name": "Steve",     "slot": 1, "entered": true, "arrived": false, "finished": false }
    ]
  }
]
```

| Champ | Signification |
|---|---|
| `phase` | le nom de l'énumération `RaidPhase` |
| `participants` | `players.length` — tous ceux qui sont entrés, connectés ou non |
| `waveTotal` | combien de mobs de vague sont réellement apparus. Reste à sa valeur finale après la vague |
| `bossSpawned` | `false` jusqu'au tick 36 de l'apparition du boss : une instance en `COMBAT` peut donc légitimement afficher `false` |
| `players[].slot` | le slot angulaire de la branche, à partir de 0 |
| `players[].entered` | téléporté dans la faille au moins une fois |
| `players[].arrived` | a atteint le disque du boss |
| `players[].finished` | éjecté à la fin du raid |

Ce qui n'est **pas** exposé, bien que l'instance le détienne : les UUID d'entité et de combat
du boss, la liste des entités de la vague, toutes les échéances, le dernier checkpoint de
chaque joueur, les graines de slot et le compteur de réapparitions. Si tu débogues un raid
bloqué, l'API REST ne te dira pas pourquoi — lis
`config/cheeseraids/instances/<raidId>.json`, qui contient l'objet entier.

## `POST /api/v1/raids/start`

Exige **OPERATOR**. Corps :

```json
{ "raid": "hoopa" }
```

Un corps absent ou illisible dégénère en `raid = ""`, qui n'est jamais un identifiant valide :
l'appel échoue donc plutôt que de démarrer quelque chose d'inattendu.

Le démarrage s'exécute sur le thread serveur via `WebDashboard.executeOnServerThread`, et le
thread HTTP attend le résultat — le code de statut reflète donc l'issue réelle.

```json
{ "started": true, "raid": "hoopa" }
```

| Statut | Quand |
|---|---|
| `200` | le raid a démarré |
| `409` | refus : identifiant inconnu, `enabled: false`, ou déjà actif. Le corps vaut `{"started":false,…}` |

```bash
curl -s -X POST http://localhost:44311/api/v1/raids/start \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"raid":"hoopa"}'
```

## `POST /api/v1/raids/stop`

Exige **OPERATOR**. Même forme de corps.

```json
{ "stopped": true }
```

| Statut | Quand |
|---|---|
| `200` | l'instance a été trouvée et nettoyée |
| `404` | rien d'actif sous cet identifiant |

La même réserve que pour la commande s'applique : **ceci ne téléporte pas les joueurs.**
Arrêter un raid dont les joueurs sont en plein parkour supprime les blocs sous leurs pieds. La
vue du dashboard demande une confirmation (*Stopper le raid « … » et nettoyer l'arène ?*) mais
cette confirmation n'avertit pas de ce point.

## `POST /api/v1/raids/reload`

Exige **OPERATOR**. Pas de corps.

```json
{ "reloaded": true }
```

Toujours `200`, toujours `true`, même quand tous les fichiers ont échoué au parsing. Comme pour
la commande en jeu, le seul signal est le journal du serveur. Si tu automatises une poussée de
configuration, lis `GET /definitions` ensuite et vérifie que ton changement y est bien.

---

## WebSocket

CheeseRaids publie sur **un seul** canal, `raids`, avec **un seul** type de message.

Abonne-toi comme d'habitude, après avoir authentifié la socket :

```json
{"type":"auth","token":"<token de session>"}
{"type":"subscribe","channels":["raids"]}
```

### `raid_update`

```json
{
  "type": "raid_update",
  "raidId": "hoopa",
  "phase": "COMBAT",
  "participants": 4
}
```

Émis **uniquement aux transitions de phase** — à chaque appel de `transition()`, ce qui couvre
`ANNOUNCE` jusqu'à `CLEANUP`. Rien d'autre ne diffuse : aucun message quand un joueur entre,
aucun quand un checkpoint est franchi, aucun quand le boss apparaît ou meurt, aucun quand le
raid est nettoyé et retiré.

Ça fait du canal une notification grossière plutôt qu'un flux en direct — et la vue livrée
avec le dashboard ne le consomme même pas. `buildRaids` n'appelle `app.subscribe(['raids'])`
que si cette fonction existe, et le cœur de CheeseDash n'en définit aucune ; la liste
d'abonnements du cœur n'inclut pas `raids`, et son gestionnaire de messages n'a aucun cas
`raid_update`. Les cartes ne sont redessinées que si tu appuies sur *Rafraîchir* ou utilises
l'un des boutons de la vue. Un changement de phase ou un joueur qui rejoint en plein `OPEN`
n'apparaîtra pas avant.

La diffusion passe par un `Consumer<RaidInstance>` statique sur `CheeseRaidsMod`, renseigné par
le module de dashboard au démarrage du serveur et vidé à l'arrêt. Avec CheeseDash présent mais
le module pas encore démarré, le consommateur est nul et la diffusion est ignorée en silence.

---

## La vue du dashboard

Le module `raids` affiche une carte par définition, dans une grille adaptative.

Chaque carte montre l'icône et le nom d'affichage, l'identifiant en badge, un point d'état
(vert si actif, gris si au repos), la phase courante en monospace, et la planification — soit
`auto HH:MM · tous les Nj`, soit `déclenchement manuel`.

Un raid actif se déploie en une table de participants : numéro de slot, nom du joueur, et une
étiquette de progression dérivée des trois booléens — *En attente* (`entered` faux), *Parkour*
(entré, pas arrivé), *Au centre* (arrivé), *Terminé* (`finished`). `· boss actif` apparaît à
côté du nombre de participants dès que `bossSpawned` vaut `true`.

Les boutons sont **Démarrer** (désactivé quand `enabled` vaut `false`), **Stopper** (avec une
confirmation), plus **Rafraîchir** et **Recharger les définitions** dans la barre d'outils
au-dessus de la grille. Sans définition chargée, la vue explique qu'un `hoopa.json` par défaut
est créé dans `config/cheeseraids/raids/`.

Il n'y a pas d'éditeur. Tout ce que le fichier de configuration contrôle — positions,
apparitions, boss, récompenses — s'édite sur le disque, pas dans le navigateur. Pour un mod
dont le principal problème de mise en place est quatre coordonnées bouchons, c'est le manque
qu'il serait le plus utile de combler.

---

← [Intégrations externes](integrations.fr.md) · [Architecture](architecture.fr.md) →
