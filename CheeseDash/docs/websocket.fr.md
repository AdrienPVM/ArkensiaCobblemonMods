[← CheeseDash](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](websocket.md)

# WebSocket

L'API REST répond à des questions. Le WebSocket, lui, pousse des événements : le TPS une
fois par seconde, les connexions de joueurs, les décomptes avant redémarrage, chaque
entrée d'audit, et tout ce que les modules installés décident de diffuser. Le serveur est
implémenté dans `WebSocketServer.java`, 193 lignes, au-dessus de
`org.java-websocket:Java-WebSocket:1.5.7`, embarqué dans le jar en Jar-in-Jar
(`include implementation(...)` dans `build.gradle`) : il n'y a rien à installer à côté.

```text
ws://<serveur>:44312/
```

Le port vaut le port HTTP plus un, calculé dans `CheeseDashMod` par
`config.getPort() + 1`. Il n'a pas de réglage propre, et `GET /api/v1/status` le renvoie
dans `wsPort` pour qu'un client n'ait jamais à le deviner. Il n'y a pas de TLS : le
serveur écoute en `ws://` pur, donc place-le derrière le même tunnel ou le même VPN que
le port HTTP.

---

## La poignée de main

Trois étapes, dans cet ordre, et l'ordre est imposé.

**1. Se connecter.** Le serveur enregistre la connexion comme *en attente*, avec un
horodatage. Un thread de nettoyage passe toutes les cinq secondes et ferme toute
connexion en attente depuis plus de cinq secondes, avec le code de fermeture `4001` et la
raison `Auth timeout`.

**2. S'authentifier.** Le premier message doit être :

```json
{"type":"auth","token":"<master token>"}
```

En cas de succès le serveur répond et la connexion rejoint l'ensemble des connexions
authentifiées :

```json
{"type":"auth_ok"}
```

En cas d'échec la connexion est fermée immédiatement avec le code `4001` et la raison
`Invalid token`. Pas de message d'erreur, pas de seconde chance sur la même socket :
reconnecte-toi avec un token correct.

Tout message autre que `auth` envoyé avant authentification ferme la connexion avec
`4001 Not authenticated`. Un message qui n'est pas un objet JSON, ou un objet JSON sans
`type`, est ignoré en silence.

**3. S'abonner.** L'authentification seule ne livre rien. Une connexion ne reçoit que les
canaux qu'elle a demandés :

```json
{"type":"subscribe","channels":["tps","players","audit","schedule"]}
```

Il n'y a pas d'accusé de réception. `unsubscribe` prend la même forme et retire les
canaux de l'ensemble. Les noms de canaux ne sont pas validés : s'abonner à `tpz` est
accepté et ne livre jamais rien. S'abonner deux fois est sans effet, c'est un ensemble.

`{"type":"ping"}` répond `{"type":"pong"}`, pratique pour maintenir une connexion
inactive en vie à travers un proxy. Rien d'autre n'est accepté.

### Seul le master token est accepté

⚠️ Le contrôle est `WebDashboard.constantTimeEquals(token, config.getToken())` — une
comparaison en temps constant avec le **master token uniquement**. `SessionManager` n'est
jamais consulté, donc rien de ce qui suit ne fonctionne :

- un identifiant de session issu de `POST /api/v1/auth/login` (`ses_…`),
- le token d'un compte additionnel de `config.accounts[]`, quel que soit son rôle.

Deux conséquences. D'abord, les données temps réel sont de fait une fonctionnalité
`SUPER_ADMIN` : un compte `OPERATOR` ou `READ_ONLY` ne possède aucun justificatif que le
WebSocket acceptera. Ensuite, et c'est plus gênant : **le dashboard livré avec le mod ne
passe pas lui-même la poignée de main.** `app.js` envoie `API.getToken()`, et dans
`api.js` cette fonction vaut `function getToken() { return getSessionId(); }` —
l'identifiant de session créé au login. Le serveur le compare au master token, la
comparaison échoue, et la socket se ferme avec `4001`. `app.js` réessaie alors toutes les
trois secondes tant que l'utilisateur reste connecté, et retombe sur un sondage de
`GET /api/v1/status` toutes les deux secondes dès que la socket est tombée (le
`setInterval` vaut 2000 ms, même si le commentaire au-dessus annonce 10 s) : c'est
pourquoi la page d'ensemble affiche quand même un TPS. Tout
ce qui est véritablement temps réel — le flux d'audit, les notifications d'arrivée et de
départ, le décompte avant redémarrage — n'arrive jamais.

Un client que tu écris toi-même fonctionne parfaitement s'il envoie le master token.
Garde cette asymétrie en tête en lisant la suite : tout ce qui suit est ce que le serveur
diffuse, vérifié dans le code, et non ce que le frontend fourni parvient aujourd'hui à
afficher.

Note aussi qu'un canal ne porte aucun filtre de rôle. Une fois authentifiée avec le
master token, une connexion peut s'abonner à n'importe quoi, y compris `audit`, qui
rejoue qui a fait quoi (l'IP elle-même est retirée du message WebSocket, voir
[plus bas](#audit--canal-audit)).

---

## Les canaux réellement diffusés

Un canal existe parce que quelque chose appelle
`DashboardRegistry.broadcast(channel, jsonMessage)` — le point d'entrée statique que
n'importe quel mod peut appeler sans risque, puisqu'il ne fait rien quand le WebSocket de
CheeseDash est absent. Le code de CheeseDash lui-même appelle parfois
`webSocketServer.broadcast(...)` directement, ce qui contourne l'acheminement vers
Discord décrit dans [webhooks Discord](discord-webhooks.fr.md).

| Canal | Publié par | Types de message |
|---|---|---|
| `tps` | cœur CheeseDash | `tps` |
| `players` | cœur CheeseDash | `player_count`, `player_join`, `player_leave`, `player_list` |
| `audit` | cœur CheeseDash | `audit` |
| `schedule` | cœur CheeseDash | `schedule_created`, `schedule_tick`, `schedule_cancelled`, `schedule_fired` |
| `market` | CheeseMarket | `market_purchase` |
| `eggs` | EggAndCheese | `egg_discovery` |
| `items` | CheeseItems | `item_update` |
| `morphos` | CheeseItems (module Morphos) | `morpho_update` |
| `prison` | CheeseGuard | `prison_added`, `prison_released`, `prison_updated` |
| `dungeons` | CobblemonDungeons | `dungeon_start`, `dungeon_complete`, `dungeon_fail`, `dungeon_boss_spawn`, `dungeon_player_death` |
| `store` | CheeseStore | `store_update`, `store_purchase`, `store_gallery_update`, `store_cosmetic_update`, `store_cosmetic_purchase`, `store_playcosmetic_update`, `store_playcosmetic_purchase` |
| `raids` | CheeseRaids | `raid_update` |

### Les canaux auxquels on s'abonne et que personne ne publie

`app.js` s'abonne aussi à `logs`, `events` et `dimensions`, et `handleWsMessage` a des
branches pour `log`, `event_update`, `dim_update` et `snapshot_done`. Rien dans ce dépôt
ne diffuse quoi que ce soit là-dessus : une recherche sur l'ensemble du dépôt pour
`broadcast("logs"`, `broadcast("events"` et `broadcast("dimensions"` ne trouve rien. La
vue console du serveur se remplit donc en sondant `GET /api/v1/server/logs`, pas via un
flux, et les vues événements et dimensions se rafraîchissent quand tu le leur demandes.
Le mappeur Discord porte les mêmes entrées orphelines ; voir
[le tableau de correspondance](discord-webhooks.fr.md#correspondance-canal--événement).

---

## Forme des payloads

Chaque message est un objet JSON portant un `type`. Rien n'est encapsulé, rien n'est
regroupé, et le nom du canal n'est pas répété dans le payload : un client abonné à
plusieurs canaux distingue les messages par leur seul `type`.

### `tps` · canal `tps`

Émis tous les 20 ticks, et seulement si au moins un client est connecté
(`getConnectionCount() > 0`) : un serveur sans spectateur ne travaille pour personne.

```json
{"type":"tps","value":19.8}
```

`value` est le taux de tick mesuré, borné à 20.0 et arrondi à une décimale.

### `player_count` · canal `players`

Envoyé dans le même bloc de 20 ticks que `tps`, ce qui en fait un battement de cœur peu
coûteux.

```json
{"type":"player_count","count":7,"max":60}
```

### `player_join` · canal `players`

```json
{"type":"player_join","name":"RoiCheese","uuid":"ca9e…","world":"minecraft:overworld"}
```

### `player_leave` · canal `players`

Celui-là ne porte pas de `world`.

```json
{"type":"player_leave","name":"RoiCheese","uuid":"ca9e…"}
```

### `player_list` · canal `players`

Envoyé juste après chaque connexion et, pour une déconnexion, au tick serveur suivant :
le joueur qui part est encore dans le gestionnaire de joueurs pendant l'événement
`DISCONNECT`, donc la diffusion est différée via `server.execute(...)` pour obtenir une
liste exacte.

```json
{
  "type": "player_list",
  "players": [
    {"name":"RoiCheese","uuid":"ca9e…","world":"minecraft:overworld"}
  ]
}
```

### `audit` · canal `audit`

Un message par entrée d'audit, émis depuis `AuditLogger.log` avant l'écriture sur
disque. Il porte tous les champs de l'entrée stockée **sauf `ip`**, qui reste dans
`config/cheesedash_audit.json` et dans `GET /api/v1/admin/audit`.

```json
{
  "type": "audit",
  "timestamp": "2026-03-09T18:42:11.004Z",
  "username": "admin",
  "role": "SUPER_ADMIN",
  "action": "server.command",
  "detail": "say hello"
}
```

### `schedule_created` · canal `schedule`

L'objet imbriqué `action` est le résultat de `ScheduledAction.toJson()`, exactement la
forme que `GET /api/v1/server/schedule` renvoie sous `active`.

```json
{
  "type": "schedule_created",
  "action": {
    "id":"sa_3f8a1c","type":"RESTART","message":"Weekly maintenance",
    "scheduledAt":1772131931004,"createdAt":1772131331004,
    "createdBy":"admin","remainingSeconds":600
  }
}
```

### `schedule_tick` · canal `schedule`

Émis par le ticker de décompte **toutes les cinq secondes**, et chaque seconde sur les
dix dernières (`remaining <= 10 || remaining % 5 == 0`).

```json
{"type":"schedule_tick","remaining":582,"actionType":"RESTART","message":"Weekly maintenance"}
```

### `schedule_cancelled` · canal `schedule`

Volontairement nu : ni id, ni type, donc un client qui suit deux choses à la fois ne peut
pas savoir *ce qui* a été annulé sans relire `GET /api/v1/server/schedule`.

```json
{"type":"schedule_cancelled"}
```

### `schedule_fired` · canal `schedule`

Envoyé au moment où l'action se déclenche, avant la sauvegarde des mondes et l'arrêt. Sur
un `RESTART`, c'est la dernière chose que la connexion reçoit.

```json
{"type":"schedule_fired","actionType":"RESTART"}
```

---

## Payloads des modules

Ceux-ci viennent des mods satellites, pas de CheeseDash. Ils sont documentés ici parce
que le canal appartient à CheeseDash, mais chaque mod possède sa propre forme et peut la
changer : considère ce qui suit comme une lecture du code au moment de la rédaction.

### `market_purchase` · canal `market` · CheeseMarket

Émis depuis `ShopGuiHandler` après un achat réussi, et uniquement si `cheesedash` est
chargé.

```json
{
  "type":"market_purchase","playerName":"RoiCheese","shopId":"spawn_general",
  "itemName":"Iron Pickaxe","quantity":2,"timestamp":1772131331004
}
```

### `egg_discovery` · canal `eggs` · EggAndCheese

Depuis `AdminCollectibleBlockEntity` quand un joueur trouve un collectible. `eggId` est
l'identifiant de registre, pas un nom affichable.

```json
{"type":"egg_discovery","playerName":"RoiCheese","eggId":"egg_026","timestamp":1772131331004}
```

### `item_update` · canal `items` · CheeseItems

`action` vaut `created`, `updated` ou `deleted`. Le message de suppression ne porte pas
d'`itemName`.

```json
{"type":"item_update","itemId":"quest_key","itemName":"Rusty Key","action":"updated"}
```

### `morpho_update` · canal `morphos` · CheeseItems

`action` vaut `created`, `updated`, `deleted`, `reordered`, `image_updated` ou
`image_deleted`. Sur `reordered` il n'y a pas de `morphoId` du tout, puisque c'est toute
la liste qui a bougé.

```json
{"type":"morpho_update","morphoId":"morpho_01","action":"image_updated"}
```

### `prison_added` · `prison_released` · `prison_updated` · canal `prison` · CheeseGuard

L'objet imbriqué `entry` est le JSON complet du `PrisonEntry`. Les champs de premier
niveau en sont dupliqués volontairement, pour que le mappeur Discord de CheeseDash puisse
les lire sans parcourir l'objet imbriqué. Les champs nuls dans l'entrée sont simplement
absents.

```json
{
  "type":"prison_added",
  "entry":{ "...": "le PrisonEntry complet" },
  "playerName":"Griefer","reason":"griefing spawn",
  "durationMs":3600000,"duration":"1h","byName":"admin"
}
```

`prison_released` ajoute `releaseReason` (`EXPIRED` quand la peine est purgée, sinon une
grâce) et `releasedByName`.

### `dungeon_*` · canal `dungeons` · CobblemonDungeons

Cinq types, une seule forme. `players` contient des noms affichables, avec repli sur les
huit premiers caractères de l'UUID pour un joueur hors ligne. `bossSpecies` n'apparaît
qu'une fois le boss apparu.

```json
{
  "type":"dungeon_boss_spawn","dungeonId":"frost_keep","dungeonName":"Frost Keep",
  "tier":3,"players":["RoiCheese"],"timeLeft":412,"lives":2,"bossSpecies":"glaceon"
}
```

### `store_*` · canal `store` · CheeseStore

Sept types. Les messages de mise à jour embarquent l'objet modifié sous `product`, `item`
ou `cosmetic`, sérialisé par le `toJson()` de la boutique ; les messages d'achat
décrivent une commande terminée. CheeseStore est le module le plus bavard du bus, ce qui
vaut d'être su avant de s'abonner à `store` sur un serveur chargé.

### `raid_update` · canal `raids` · CheeseRaids

Émis à chaque changement de phase et à chaque progression d'un raid.

```json
{"type":"raid_update","raidId":"hoopa","phase":"BOSS","participants":12}
```

---

## Cycle de vie, threads et modes de panne

Le serveur WebSocket est construit dans `onInitialize` mais démarré dans
`SERVER_STARTED`, juste après le serveur HTTP. C'est un thread démon avec
`setReuseAddr(true)`, pour qu'un redémarrage rapide ne bute pas sur une socket encore en
`TIME_WAIT`.

**⚠️ `enabled: false` ne l'arrête pas.** `WebDashboard.start()` vérifie
`config.isEnabled()` et journalise `Disabled in config, skipping.` — mais
`CheeseDashMod` appelle `webSocketServer.start()` sans condition, hors de tout contrôle
de ce genre. Désactiver le dashboard dans `config/cheesedash.json` ferme donc le port
44311 et laisse **le port 44312 à l'écoute**. Il accepte les connexions, exige le master
token, et diffuse le TPS et les entrées d'audit à quiconque le possède. Si tu désactives
le dashboard pour des raisons de sécurité, bloque les deux ports au pare-feu ; ne compte
pas sur la configuration pour l'avoir fait.

`broadcast` parcourt les connexions authentifiées sur le thread appelant — le thread de
tick serveur pour `tps` et les événements joueurs, un thread HTTP pour une entrée
d'audit, un thread du planificateur pour un tick de décompte — et les échecs de
`conn.send` sont avalés connexion par connexion. Une socket morte ne peut pas casser la
diffusion vers les autres, et une diffusion ne peut pas lever d'exception dans la boucle
de tick.

Les deux tables de connexions sont des `ConcurrentHashMap`, et `onClose` comme `onError`
retirent la socket des deux. Les `IOException` ne sont pas journalisées, parce qu'un
client qui rabat l'écran de son portable en produit une et que le log n'en a pas besoin.

Sur `SERVER_STOPPING`, `shutdown()` appelle `stop(1000)` : une seconde pour vider, puis
les sockets meurent. Les clients voient une fermeture normale, pas un `4001`, ce qui
permet à un client bien élevé de distinguer « le serveur s'arrête » de « mon token est
mauvais ».

---

## Diffuser depuis ton propre module

```java
import com.cheesedash.DashboardRegistry;
import com.google.gson.JsonObject;

JsonObject msg = new JsonObject();
msg.addProperty("type", "kart_race_finished");
msg.addProperty("preset", preset.getId());
msg.addProperty("winner", player.getName().getString());
DashboardRegistry.broadcast("kart", msg.toString());
```

Quatre règles, toutes tirées des mods déjà présents sur le bus :

1. **Appelle `DashboardRegistry.broadcast`, pas le serveur directement.** Il ne fait rien
   quand le WebSocket n'est pas lancé, et il alimente aussi le gestionnaire Discord.
2. **Nomme le canal comme le préfixe de ton module.** Rien ne l'impose, et une collision
   signifie juste que les messages de deux modules arrivent sur le même abonnement.
3. **Mets toujours un `type`,** et préfixe-le du nom de ton module. Les clients font un
   `switch` sur `type` tous canaux confondus, donc `update` est un mauvais nom de type et
   `kart_update` un bon.
4. **Garde un champ `playerName` au premier niveau** si l'événement concerne un joueur.
   Le mappeur Discord de CheeseDash ne lit que les champs de premier niveau ; c'est pour
   ça que le payload prison de CheeseGuard les duplique hors de son `entry` imbriqué.

Le versant enregistrement de tout ceci, et le reste de ce qu'un module obtient
gratuitement, est dans [écrire un module](writing-a-module.fr.md).

---

← [API REST](rest-api.fr.md) · [Écrire un module](writing-a-module.fr.md) →
