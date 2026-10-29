[← CheeseDash](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](discord-webhooks.md)

# Webhooks Discord

`DiscordWebhookManager.java`, 693 lignes, publie des embeds riches dans des salons
Discord quand quelque chose arrive sur le serveur. Tu enregistres une URL de webhook, tu
coches les événements voulus, et ça part à partir de là. La partie astucieuse, c'est que
la plupart des événements ne viennent pas de code dédié : ils sont captés sur le bus
WebSocket, donc un module qui diffuse un événement obtient ses notifications Discord sans
même savoir que Discord existe.

Les routes CRUD sont dans [API REST → Webhooks Discord](rest-api.fr.md#webhooks-discord).
Cette page couvre les événements, la forme des messages et les modes de panne.

> ⚠️ Une URL de webhook Discord est un secret : qui la détient peut publier dans ce salon
> au nom de ton serveur. `GET /api/v1/webhooks/{id}` la rend **non masquée** à n'importe
> quel rôle authentifié, `READ_ONLY` compris. Voir
> [la note en bas de page](#lurl-est-lisible-par-tous-les-rôles).

---

## Enregistrer un webhook

Dans Discord : paramètres du salon → Intégrations → Webhooks → Nouveau webhook → copier
l'URL. Puis :

```bash
curl -X POST http://localhost:44311/api/v1/webhooks \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"Staff alerts","url":"https://discord.com/api/webhooks/123…/abc…"}'
```

`name` doit faire de 1 à 64 caractères. L'URL doit commencer par
`https://discord.com/api/webhooks/` ou `https://discordapp.com/api/webhooks/` ; toute
autre forme donne `400 {"error":"URL must be a valid Discord webhook URL"}`. Ce contrôle
est un test de préfixe, pas un test d'accessibilité : une coquille dans la partie token
de l'URL passe ici et ne se manifeste que par un HTTP 401 de Discord au premier
événement.

**Un webhook neuf n'est abonné à rien.** Il est créé `enabled` avec un ensemble
d'événements vide, donc il reste muet jusqu'à ce que tu envoies un tableau `events` :

```bash
curl -X PUT http://localhost:44311/api/v1/webhooks/wh_3f8a1c \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"events":["SERVER_START","SERVER_STOP","PRISON_IMPRISONED"]}'
```

La clé `events` remplace l'ensemble complet : lis, modifie, réécris. Les noms
d'événements inconnus sont **écartés en silence** — les échecs de `DiscordEventType.valueOf` sont
attrapés et ignorés — donc une coquille te coûte cet abonnement sans aucune erreur.
Compare ce que tu as envoyé avec ce que `GET /api/v1/webhooks/{id}` te renvoie, et
vérifie `eventCount`.

`POST /api/v1/webhooks/{id}/test` publie un embed vert `✅ Test CheeseDash` qui nomme le
webhook et son nombre d'événements. Il répond `200` dans les deux cas, avec
`{"success":true}` ou `{"success":false,"error":"…"}` : lis le corps, pas le code de
statut.

Tout est stocké dans `config/cheesedash_webhooks.json`, écrit à chaque modification sur
un thread démon nommé `CheeseDash-WebhookSave` :

```json
{
  "webhooks": [
    {
      "id": "wh_3f8a1c",
      "name": "Staff alerts",
      "url": "https://discord.com/api/webhooks/123…/abc…",
      "avatarUrl": "",
      "enabled": true,
      "events": ["SERVER_START", "PRISON_IMPRISONED"],
      "scoreboardFilter": [],
      "createdAt": 1772131331004,
      "eventCount": 2
    }
  ]
}
```

L'URL est stockée en clair — il faut bien, pour qu'elle serve. `eventCount` est écrit
aussi et ignoré au chargement, ce qui en fait un piège si tu édites à la main : change
`events` dans le fichier et `eventCount` mentira jusqu'à la prochaine écriture.
`avatarUrl`, s'il est renseigné, remplace l'avatar que Discord affiche pour le message ;
le pseudo est toujours `CheeseDash`.

### `scoreboardFilter`

Consulté uniquement pour `SCORE_LEADER_CHANGE`. Quand le tableau n'est pas vide,
l'événement n'est livré que si son `scoreboardId` figure dans la liste, ce qui permet de
dédier un salon à un seul classement. Un tableau vide signifie tous les scoreboards. Une
bizarrerie à connaître : un événement qui arrive **sans** `scoreboardId` du tout passe le
filtre, parce que le contrôle est sauté quand l'identifiant est nul.

---

## Les 23 types d'événement

`GET /api/v1/webhooks/event-types` renvoie ce catalogue avec, pour chaque entrée, son
`id`, son `label`, sa `description`, sa `color` et sa `category`. Les libellés et
descriptions sont en français dans l'énumération : c'est ce que le dashboard affiche dans
l'éditeur de webhooks, Discord n'en voit rien.

| Événement | Catégorie | Déclenché par |
|---|---|---|
| `SERVER_START` | Serveur | CheeseDash, sur `SERVER_STARTED` |
| `SERVER_STOP` | Serveur | CheeseDash, sur `SERVER_STOPPING` |
| `RESTART_SCHEDULED` | Serveur | `POST /server/schedule`, et la diffusion `schedule_created` |
| `RESTART_CANCELLED` | Serveur | `DELETE /server/schedule/active`, et la diffusion `schedule_cancelled` |
| `MAINTENANCE` | Serveur | une action `MAINTENANCE` qui se déclenche |
| `SCHEDULED_TASK_FIRED` | Serveur | toute action qui se déclenche ; aussi l'auto-reset de chunks de CheeseGuard |
| `PLAYER_JOIN` | Joueurs | CheeseDash, à la connexion d'un joueur |
| `PLAYER_LEAVE` | Joueurs | CheeseDash, à la déconnexion |
| `EVENT_ANNOUNCE` | Evénements | le canal `events` — **personne ne le diffuse** |
| `EVENT_START` | Evénements | le canal `events` — **personne ne le diffuse** |
| `EVENT_END` | Evénements | le canal `events` — **personne ne le diffuse** |
| `SCORE_LEADER_CHANGE` | Scores | rien dans ce dépôt |
| `MARKET_PURCHASE` | Marché | CheeseMarket, via le canal `market` |
| `EGG_DISCOVERY` | Collectibles | EggAndCheese, via le canal `eggs` |
| `ADMIN_COMMAND` | Administration | rien dans ce dépôt |
| `DIMENSION_CREATED` | Dimensions | le canal `dimensions` — **personne ne le diffuse** |
| `DIMENSION_DELETED` | Dimensions | le canal `dimensions` — **personne ne le diffuse** |
| `SHIFT_TRANSFORM` | CobbleShift | le canal `shift` — **personne ne le diffuse** |
| `PRISON_IMPRISONED` | Prison | CheeseGuard, via le canal `prison` |
| `PRISON_RELEASED` | Prison | CheeseGuard, via le canal `prison` |
| `LEAGUE_MASTER` | CobbleCap | CobbleCap, sous forme d'embed pré-construit |
| `CHAMPION_VICTORY` | CobbleCap | CobbleCap, sous forme d'embed pré-construit |
| `CUSTOM` | Autre | rien — un emplacement pour un mod qui voudrait un message générique |

Six d'entre eux peuvent être cochés et ne se déclencheront jamais avec les mods de ce
dépôt : `EVENT_ANNOUNCE`, `EVENT_START`, `EVENT_END`, `DIMENSION_CREATED`,
`DIMENSION_DELETED` et `SHIFT_TRANSFORM` dépendent tous de canaux WebSocket que personne
ne publie — voir
[WebSocket](websocket.fr.md#les-canaux-auxquels-on-sabonne-et-que-personne-ne-publie).
`SCORE_LEADER_CHANGE`, `ADMIN_COMMAND` et `CUSTOM` n'ont pas d'émetteur non plus. Ils sont
câblés de bout en bout du côté Discord et attendent une diffusion qui n'existe pas
encore ; s'y abonner est inoffensif, et silencieux.

`ADMIN_COMMAND` est celui dont l'absence surprend : une commande lancée depuis
`POST /api/v1/server/command` est écrite dans le journal d'audit et dans le log du
serveur, mais aucun événement Discord n'est envoyé pour elle.

---

## Correspondance canal → événement

`onBroadcast(channel, jsonMessage)` est appelé par `DashboardRegistry.broadcast` pour
chaque message WebSocket, et `mapBroadcastToEvent` décide s'il devient un événement
Discord :

| Canal | `type` du message | Événement Discord |
|---|---|---|
| `players` | `player_join` | `PLAYER_JOIN` |
| `players` | `player_leave` | `PLAYER_LEAVE` |
| `market` | `market_purchase` | `MARKET_PURCHASE` |
| `eggs` | `egg_discovery` | `EGG_DISCOVERY` |
| `schedule` | `schedule_created` | `RESTART_SCHEDULED` |
| `schedule` | `schedule_cancelled` | `RESTART_CANCELLED` |
| `schedule` | `schedule_fired` | `SCHEDULED_TASK_FIRED` |
| `prison` | `prison_added` | `PRISON_IMPRISONED` |
| `prison` | `prison_released` | `PRISON_RELEASED` |
| `events` | type contenant `ANNOUNCED` | `EVENT_ANNOUNCE` |
| `events` | type contenant `ACTIVE` ou `COUNTDOWN` | `EVENT_START` |
| `events` | type contenant `CLEANUP` ou `IDLE` | `EVENT_END` |
| `dimensions` | `dim_created` / `dim_deleted` | `DIMENSION_CREATED` / `DIMENSION_DELETED` |
| `shift` | `transform` | `SHIFT_TRANSFORM` |

Tout le reste renvoie `null` et est abandonné. Remarque que `schedule_created`
correspond à `RESTART_SCHEDULED` quel que soit le type d'action : un `STOP` planifié
arrive donc dans Discord sous le titre *Redémarrage planifié*. Et les trois types
`prison_*` incluent `prison_updated`, qui ne correspond à rien : modifier la durée d'une
peine n'est pas annoncé.

Ensuite `extractBroadcastData` aplatit le message en une table de chaînes, en lisant ces
clés et seulement elles, **au premier niveau** : `name`, `playerName`, `eventName`,
`itemName`, `eggName`, `dimName`, `species`, `message`, `scoreboardId`,
`scoreboardName`, `reason`, `duration`, `byName`, `releaseReason`, `releasedByName`. Un
objet `action` imbriqué est lu aussi, pour `message` et `remainingSeconds` (mis en forme
dans `delay`). C'est tout le contrat entre un module et Discord : mets ces champs au
sommet de ton payload, ou l'embed retombera sur sa formulation générique. C'est
exactement pour cela que le payload prison de CheeseGuard duplique `playerName`,
`reason`, `duration` et `byName` hors de son `entry` imbriqué.

À noter aussi : c'est `DashboardRegistry.broadcast` qui déclenche tout ceci, et les
diffusions `tps` et `players` de CheeseDash le contournent en partie en appelant
`webSocketServer.broadcast(...)` directement. Les connexions et déconnexions atteignent
quand même Discord, parce que `CheeseDashMod` appelle `sendEvent` explicitement pour
elles dans le même handler.

---

## À quoi ressemble le message

Chaque message est un embed dans une enveloppe standard :

```json
{
  "username": "CheeseDash",
  "embeds": [{
    "title": "⛓ Joueur emprisonné",
    "description": "**Griefer** a été emprisonné par **admin**\n**Durée:** 1h\n**Raison:** griefing spawn",
    "color": 15680580,
    "timestamp": "2026-03-09T18:42:11.004Z",
    "footer": {"text": "CheeseDash • Arkensia"}
  }]
}
```

`avatar_url` est ajouté quand le webhook en possède un. La couleur vient du type
d'événement — ambre `#f5a623` pour la planification, vert `#22c55e` pour un succès, rouge
`#ef4444` pour les arrêts et les sanctions, or `#ffd700` pour les jalons de classement —
en cohérence avec la palette du dashboard.

**Les titres et descriptions sont du français en dur**, emoji compris, dans
`buildEmbed`. Il n'existe aucun réglage de langue pour la sortie Discord, et le pied de
message indique `CheeseDash • Arkensia`. Les deux sont cosmétiques et les deux sont dans
`DiscordWebhookManager.java` si tu les veux dans une autre langue ou sans le nom du
serveur — le README dit la même chose des autres mentions d'Arkensia.

Deux champs supplémentaires sont ajoutés quand les données de l'événement les portent :
*Joueurs en ligne* depuis `playerCount` et *TPS* depuis `tps`, côte à côte. En pratique
seul `SERVER_START` les fournit, et son `tps` vaut la chaîne littérale `20.0` plutôt
qu'une mesure — le serveur vient de démarrer, rien n'a encore été mesuré.

Quelques événements lisent leur description dans le payload. `RESTART_SCHEDULED` utilise
`delay` et ajoute `message` en bloc de citation ; `MAINTENANCE` ajoute `message` de la
même façon ; `SCHEDULED_TASK_FIRED` utilise `message` comme description entière, ce qui
permet à l'auto-reset de chunks de CheeseGuard d'annoncer combien de fichiers de région
il a supprimés ; `ADMIN_COMMAND` encadrerait `command` de backticks et nommerait
l'utilisateur, si quelque chose le déclenchait.

### Embeds pré-construits

`sendRawEmbed(eventType, embed)` publie un embed construit par l'appelant, en contournant
entièrement `buildEmbed`. CobbleCap s'en sert pour `CHAMPION_VICTORY` et
`LEAGUE_MASTER`, où il veut des sprites de Pokémon et un détail d'équipe que le gabarit
ne sait pas exprimer. Il atteint la méthode par réflexion, ce qui permet à CobbleCap de
compiler et de tourner sans CheeseDash.

Si tu écris un module, préfère diffuser sur le WebSocket et laisser le mappeur travailler.
Ne sors `sendRawEmbed` que quand l'embed lui-même est le sujet.

---

## Livraison, limitation de débit et erreurs

L'envoi est asynchrone, sur un pool de deux threads nommés `CheeseDash-Discord`, donc un
Discord lent ne bloque jamais le thread du jeu. Chaque POST a un délai de connexion de 5
secondes et un délai de lecture de 5 secondes.

**CheeseDash se limite lui-même à un message toutes les deux secondes par webhook**, et
l'implémentation compte : un événement qui arrive dans cette fenêtre est **abandonné**,
pas mis en file.

```java
Long lastSend = rateLimitTracker.get(webhook.id);
if (lastSend != null && System.currentTimeMillis() - lastSend < RATE_LIMIT_INTERVAL_MS) {
    continue;
}
```

Un webhook abonné à `PLAYER_JOIN`, sur un serveur où dix personnes se reconnectent après
un redémarrage, en rapportera donc peut-être deux. Rien n'est journalisé quand un message
est abandonné. Si tu veux une trace complète d'un flux d'événements, sers-toi du journal
d'audit ou du WebSocket ; Discord est ici pour notifier, pas pour tenir des comptes. Le
compteur est par webhook, donc répartir des événements bavards sur deux URL de webhook du
même salon double ton débit.

Les échecs sont journalisés en `WARN` et jamais réessayés :

| Résultat | Journalisé | Renvoyé par la route de test |
|---|---|---|
| HTTP 200 ou 204 | rien | `{"success":true}` |
| HTTP 429 | `Discord rate limited for webhook 'Staff alerts'` | `Rate limited (429)` |
| Tout autre code | `Discord webhook 'X' returned 401: {…}` avec le corps renvoyé par Discord | `HTTP 401: …` |
| Timeout, échec DNS, URL invalide | `Discord webhook 'X' failed: …` | le message de l'exception |

Un 429 venant de Discord signifie que tu as atteint *leur* limite, ce que
l'auto-limitation à 2 secondes évite normalement ; le voir pointe vers plusieurs
instances de CheeseDash partageant une même URL de webhook, ou vers un autre bot dans le
même salon. Un 401 ou un 404 signifie que le webhook a été supprimé côté Discord :
l'URL est morte et aucun nombre de tentatives ne la ressuscitera.

Sur `SERVER_STOPPING`, le gestionnaire est arrêté par `shutdownNow()` : c'est pourquoi
l'événement `SERVER_STOP` est envoyé **avant** la séquence d'arrêt dans `CheeseDashMod`.
Un message encore en vol à cet instant est perdu.

---

## L'URL est lisible par tous les rôles

`GET /api/v1/webhooks` masque le milieu de l'URL. `GET /api/v1/webhooks/{id}` ne le fait
pas : il appelle `toJson(false)` et renvoie l'URL complète, et la route n'a aucun contrôle
de rôle au-delà de l'authentification.

> ⚠️ **Un compte `READ_ONLY` peut lire toutes les URL de webhook Discord que tu as
> enregistrées.** Cette URL n'exige ni token ni compte : quiconque la détient peut
> publier des messages arbitraires, avec des embeds arbitraires, dans ce salon, au nom de
> ton serveur. Distribuer un compte `READ_ONLY` équivaut donc à distribuer toutes les URL
> de webhook du dashboard. Si ce n'est pas acceptable, ne crée pas le compte : le
> masquage sur la route de liste n'est pas un contrôle, seulement un confort.

Faire tourner une URL compromise, c'est supprimer le webhook dans Discord, en créer un
nouveau, et envoyer la nouvelle URL avec `PUT /api/v1/webhooks/{id}`. Les abonnements aux
événements, eux, survivent.

---

← [Planificateur](scheduler.fr.md) · [Journal d'audit](audit.fr.md) →
