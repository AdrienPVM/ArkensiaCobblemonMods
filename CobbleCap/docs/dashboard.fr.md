[← CobbleCap](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](dashboard.md)

# API dashboard

CobbleCap enregistre un module CheeseDash via l'entrypoint `cheesedash` déclaré dans
`fabric.mod.json` :

```json
"entrypoints": {
  "cheesedash": ["com.cobblecap.dashboard.CobbleCapDashboardModule"]
}
```

Le préfixe du module est `cobblecap`, donc chaque route ci-dessous pend sous
`/api/v1/cobblecap`. Dans la barre latérale il s'affiche comme **CobbleCap**, icône 🏅,
décrit comme *Level cap progression*.

CheeseDash sert le HTTP sur le port **44311**. L'authentification est par session :
`POST /api/v1/auth/login` rend un token à passer en `Authorization: Bearer <token>` sur
toutes les autres requêtes. Une requête sans session valide n'atteint jamais le gestionnaire
de CobbleCap.

## Disponibilité du module

`CobbleCapDashboardModule.handleRequest` refuse de dispatcher tant que la référence
`MinecraftServer` (posée dans `onServerStart`) et `CobbleCapMod.INSTANCE` ne sont pas toutes
deux non nulles :

```json
{"error":"CobbleCap not ready"}
```

C'est un **503**. Cela signifie que le dashboard est en marche mais que le serveur Minecraft
n'a pas fini de démarrer, ou que l'initialiseur de CobbleCap n'a pas tourné.

## Rôles

Les comptes CheeseDash portent l'un de trois rôles : `READ_ONLY`, `OPERATOR`,
`SUPER_ADMIN`. CobbleCap n'appelle jamais que `WebDashboard.requireWrite(exchange)`, jamais
`requireAdmin`, donc le plafond pour ce module est OPERATOR. Les routes qui n'appellent
aucun garde sont lisibles par toute session authentifiée, `READ_ONLY` comprise.

⚠️ Les quatre gardes d'écriture étaient cassés jusqu'au **29 octobre 2026**. Ils étaient
écrits `WebDashboard.requireWrite(exchange);` en instruction nue, en jetant la valeur
booléenne retournée, ce qui signifiait que le 403 était envoyé *et que le gestionnaire
continuait quand même* — un compte `READ_ONLY` pouvait modifier les limites. Ils sont
désormais `if (!WebDashboard.requireWrite(exchange)) return;` dans `handleSetCap`,
`handleToggleExpLock`, `handleChampions` (la branche `PUT`) et `handleConfig` (la branche
`PUT`). Le cinquième site d'appel, `/fireworks/test`, a toujours été écrit comme un
`else if` gardé dans le dispatcher et n'a jamais été affecté. Si tu fais tourner une build
antérieure à cette date, traite chaque route d'écriture comme non protégée par rôle et ne
donne de compte `READ_ONLY` à personne.

---

## `GET /api/v1/cobblecap/players`

Tous les joueurs que CobbleCap a jamais enregistrés, en tableau plat. Rôle : n'importe
lequel.

```json
[
  {
    "uuid": "f84c6a79-0a4e-45e0-aa79-5d0ba2f4a2b1",
    "playerName": "Steve",
    "capLevel": 45,
    "championsBeatenCount": 4,
    "lastCapChange": 1761700000000,
    "expLocked": false
  }
]
```

`lastCapChange` est en millisecondes epoch. `championsBeatenCount` est un compte, pas la
liste — interroge la route de détail pour les identifiants.

## `GET /api/v1/cobblecap/players/{uuid}`

Un joueur, avec les champs que la route de liste omet. Rôle : n'importe lequel.

| Paramètre | Où | Notes |
|---|---|---|
| `uuid` | chemin | UUID canonique avec tirets ; tout ce que `UUID.fromString` rejette donne un 400 |

```json
{
  "uuid": "f84c6a79-0a4e-45e0-aa79-5d0ba2f4a2b1",
  "playerName": "Steve",
  "capLevel": 45,
  "championsBeaten": ["gym_leader_pierre_0395", "gym_leader_ondine_0396"],
  "lastCapChange": 1761700000000,
  "hudVisible": true,
  "hudPosition": 0,
  "expLocked": false
}
```

| Statut | Corps |
|---|---|
| 200 | l'objet ci-dessus |
| 400 | `{"error":"Invalid UUID"}` |
| 404 | `{"error":"Player not found"}` |

Noter que `challengeBeaten` et `lastChallengeTimes` sont stockés sur le disque mais **pas**
exposés par cette route. Lis `config/cobblecap/players.json` si tu en as besoin.

## `POST /api/v1/cobblecap/players/{uuid}/cap`

Fixe la limite d'un joueur sans détour. Rôle : **OPERATOR**.

```json
{"capLevel": 60}
```

| Champ | Type | Obligatoire | Accepté |
|---|---|---|---|
| `capLevel` | entier | oui | 1 à 100 inclus |

La mutation passe par `WebDashboard.executeOnServerThread`, et si le joueur est en ligne un
`CapSyncPayload` est poussé à son client juste après, pour que le HUD se mette à jour sans
reconnexion.

```json
{"updated": true, "capLevel": 60}
```

| Statut | Corps | Cause |
|---|---|---|
| 400 | `{"error":"Missing capLevel"}` | corps absent, illisible, ou sans la clé |
| 400 | `{"error":"capLevel must be between 1 and 100"}` | hors plage |
| 400 | `{"error":"Invalid UUID"}` | UUID de chemin mal formé |
| 403 | le refus de rôle propre à CheeseDash | session `READ_ONLY` |
| 500 | `{"error":"Failed to set cap level"}` | le dispatch sur le thread serveur a levé une exception |

Cela écrit via `CapStorage.setCapLevel`, qui persiste. Cela ne touche **pas**
`championsBeaten`, donc la prochaine victoire sur un champion recalcule la limite depuis la
liste des champions et écrasera ce que tu as posé ici. Pour un changement durable, enregistre
aussi la progression avec `/cobblecap addbeaten <player> "<championId>"`.

## `POST /api/v1/cobblecap/players/{uuid}/explock`

Bascule le blocage d'XP du joueur. Rôle : **OPERATOR**. Aucun corps de requête — la route
inverse la valeur courante.

```json
{"uuid": "f84c6a79-0a4e-45e0-aa79-5d0ba2f4a2b1", "expLocked": true}
```

| Statut | Corps |
|---|---|
| 200 | l'objet ci-dessus |
| 400 | `{"error":"Invalid UUID"}` |
| 404 | `{"error":"Player not found"}` |
| 500 | `{"error":"Failed to toggle explock"}` |

Le blocage est vérifié avant la limite dans `CapManager.handleXpEvent`, et contrairement à la
limite il s'applique même quand `enabled` vaut `false`. Voir
[la limite de niveau](level-cap.fr.md#le-blocage-dxp-est-un-interrupteur-séparé).

## `GET /api/v1/cobblecap/champions`

La liste ordonnée des champions, exactement comme `ChampionEntry.toJson()` la sérialise.
Rôle : n'importe lequel.

```json
[
  {
    "id": "gym_leader_pierre_0395",
    "displayName": "Pierre",
    "capGranted": 30,
    "requiredPokedex": 0,
    "requiredQuestId": "",
    "victoryQuestId": "1A2B3C4D5E6F7081",
    "challengeId": "gym_leader_pierre_challenge",
    "challengeQuestId": "",
    "challengeCooldownHours": 24,
    "allowRefight": true
  }
]
```

Trois champs sont écrits conditionnellement et sont simplement **absents** quand ils ne sont
pas définis, au lieu d'être présents à zéro :
`requiredScoreboard`/`requiredScoreMin`/`challengeRequiredScoreMin` n'apparaissent que si
`requiredScoreboard` est non vide, `challengeRequiresAllBeaten` seulement quand il vaut
`false`, et `challengeCapGranted` seulement quand il est supérieur à zéro. Un client qui les
lit doit fournir lui-même les valeurs par défaut (`""`, `0`, `true`, `0`).

## `PUT /api/v1/cobblecap/champions`

Remplace la liste **entière**. Rôle : **OPERATOR**. Le corps est un tableau JSON nu
d'objets champion, pas un objet qui en enveloppe un.

```json
[
  {"id": "gym_leader_pierre_0395", "displayName": "Pierre", "capGranted": 30},
  {"id": "gym_leader_ondine_0396", "displayName": "Ondine", "capGranted": 35}
]
```

Tous les champs sauf `id` et `displayName` retombent sur une valeur par défaut dans
`ChampionEntry.fromJson` (`capGranted` 20, `allowRefight` vrai,
`challengeRequiresAllBeaten` vrai, le reste `""` ou `0`), donc un objet court est légal et
silencieusement complété.

```json
{"saved": true, "count": 2}
```

| Statut | Corps |
|---|---|
| 200 | l'objet ci-dessus |
| 400 | `{"error":"Expected JSON array"}` |
| 403 | refus de rôle |

C'est un écrasement destructeur : la route appelle `ChampionConfig.setChampions`, qui
remplace la liste en mémoire et réécrit immédiatement
`config/cobblecap/champions.json`. Il n'y a ni fusion, ni mise à jour partielle, ni retour
en arrière. **Fais un `PUT` du résultat d'un `GET` avec tes modifications appliquées**,
jamais d'un fragment. L'ordre compte — la première entrée qu'un joueur n'a pas battue est sa
barrière actuelle.

## `GET /api/v1/cobblecap/config`

Rôle : n'importe lequel. Tout `CapConfig`, plus un champ calculé.

```json
{
  "enabled": true,
  "startingCap": 20,
  "discordWebhookUrl": "",
  "bannedSpecies": ["mewtwo", "arceus"],
  "fireworksEnabled": false,
  "fireworksZoneId": "",
  "fireworksCount": 12,
  "fireworksIntervalMs": 800,
  "fireworksDurationSec": 12,
  "availableZones": ["league_arena", "spawn"]
}
```

`availableZones` n'est stocké nulle part. Il vient de
`FireworksManager.listAvailableZones()`, qui atteint CheeseGuard par réflexion et renvoie un
tableau vide quand CheeseGuard n'est pas installé. Il existe pour que le dashboard puisse
afficher une liste déroulante de zones.

## `PUT /api/v1/cobblecap/config`

Rôle : **OPERATOR**. Mise à jour partielle — seules les clés présentes dans le corps sont
appliquées, chacune via son setter de `CapConfig`, et chaque setter appelle `save()` : un
`PUT` avec cinq clés réécrit donc `config.json` cinq fois.

| Clé | Type | Plafonnement appliqué par le setter |
|---|---|---|
| `enabled` | booléen | aucun |
| `discordWebhookUrl` | chaîne | `null` devient `""` ; également poussé dans `CobbleCapWebhook` à chaud |
| `bannedSpecies` | tableau de chaînes | mis en minuscules |
| `fireworksEnabled` | booléen | aucun |
| `fireworksZoneId` | chaîne | `null` devient `""` |
| `fireworksCount` | entier | `max(1, v)` |
| `fireworksIntervalMs` | entier | `max(50, v)` |
| `fireworksDurationSec` | entier | `max(1, v)` |

`startingCap` est **en lecture seule via l'API**. Le `GET` le renvoie, le `PUT` l'ignore.
Pour le changer, édite `config/cobblecap/config.json` et redémarre.

```json
{"updated": true}
```

Un corps qui n'est pas un objet JSON donne `400 {"error":"Invalid JSON body"}`. Noter
l'asymétrie avec le plafonnement : envoie `fireworksIntervalMs: 10` et tu obtiens
`200 {"updated":true}` tandis que la valeur stockée devient `50`. Refais un `GET` si tu as
besoin de savoir ce qui a réellement été retenu.

## `POST /api/v1/cobblecap/fireworks/test`

Rôle : **OPERATOR**. Lance le spectacle de feux d'artifice immédiatement, avec ce qui est
actuellement enregistré dans la configuration, et **ignore délibérément
`fireworksEnabled`** pour que tu puisses régler le tir avant de l'activer pour de vrais
couronnements.

Aucun corps. La seule condition préalable est un `fireworksZoneId` non vide.

```json
{
  "started": true,
  "zoneId": "league_arena",
  "count": 12,
  "intervalMs": 800,
  "durationSec": 12
}
```

| Statut | Corps | Cause |
|---|---|---|
| 200 | l'objet ci-dessus | dispatché sur le thread serveur |
| 400 | `{"error":"No zone configured — set fireworksZoneId first"}` | `fireworksZoneId` vide |
| 405 | `{"error":"Method not allowed"}` | autre chose que `POST` |
| 500 | `{"error":"Failed to dispatch on server thread"}` | `executeOnServerThread` a levé une exception |

`started: true` signifie que la tâche a été remise au thread serveur, pas que des fusées
sont parties. Si l'identifiant de zone ne se résout pas dans CheeseGuard, le spectacle n'a
simplement pas lieu et la raison est dans le log serveur, pas dans cette réponse.

## `GET /api/v1/cobblecap/stats`

Rôle : n'importe lequel. Un histogramme des limites sur tous les joueurs enregistrés, trié
par limite croissante (`TreeMap`).

```json
{
  "tiers": [
    {"cap": 20, "count": 31},
    {"cap": 30, "count": 12},
    {"cap": 100, "count": 2}
  ],
  "totalPlayers": 45
}
```

## Routes inconnues

Tout ce que le dispatcher ne reconnaît pas renvoie `404 {"error":"Not found"}`. Toute
exception levée dans un gestionnaire est attrapée en tête de `CobbleCapApi.handle`,
journalisée en `[CobbleCap API] Error handling <route>: <message>`, et répondue par
`500 {"error":"Internal server error"}`.

## WebSocket

**CobbleCap ne publie rien en WebSocket.** Il n'y a aucun appel à
`DashboardRegistry.broadcast(...)` dans tout le mod, donc aucun canal ne transporte les
changements de limite, les victoires de champion ni les événements de chrono. Une vue du
dashboard qui veut des données vivantes doit interroger `/players` et `/stats` en boucle.

Des annonces côté serveur existent bien, mais elles partent dans le chat Minecraft
(`PlayerManager.broadcast`) et vers Discord via `CobbleCapWebhook`, pas vers le socket du
dashboard.

---

← [Commandes statues, chrono et champion](commands-statues.fr.md) · [Architecture](architecture.fr.md) →
