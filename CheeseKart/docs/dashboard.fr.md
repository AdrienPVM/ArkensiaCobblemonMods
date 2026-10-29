[← CheeseKart](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](dashboard.md)

# API dashboard

CheeseKart s'enregistre auprès de CheeseDash via l'entrypoint Fabric `cheesedash`, sous
le préfixe `kart`. Tout ce qui suit se trouve donc à :

```text
http://<server>:44311/api/v1/kart/<route>
```

Dans la barre latérale du dashboard, il apparaît comme **CheeseKart** 🏎 avec la
description *Mode kart et courses*.

## Authentification

Toutes les routes exigent une session CheeseDash :

```http
Authorization: Bearer <session-token>
```

Obtiens-en une auprès de `POST /api/v1/auth/login`. Les routes de lecture acceptent tous
les rôles, `READ_ONLY` compris.

> ⚠️ **Le garde d'écriture de ce module n'empêche en réalité rien.** Chaque route qui
> modifie quelque chose appelle `WebDashboard.requireWrite(exchange)` et **jette sa
> valeur de retour**. Cette méthode ne lève pas d'exception : elle envoie le `403`
> elle-même et rend le verdict sous forme de booléen. L'appel correct est
> `if (!WebDashboard.requireWrite(exchange)) return;`, et c'est ce que font les routes
> de CheeseDash lui-même. Ici, une session `READ_ONLY` reçoit un `403` **et** l'action
> est exécutée quand même.
>
> Prends donc les rôles indiqués ci-dessous comme une intention, pas comme une frontière
> appliquée : dans cette version, n'importe quelle session authentifiée, quel que soit
> son rôle, peut démarrer un kart, en arrêter un, ou créer, modifier et supprimer des
> presets. Tant que ce n'est pas corrigé, ne distribue pas de comptes `READ_ONLY` en
> comptant sur le fait qu'ils soient en lecture seule. La même erreur est présente dans
> CheeseMusic, CheesePatrol, CobbleCap et le module de courses de CheeseGuard.

Si CheeseKart n'a pas fini de démarrer — ou si le serveur n'est pas en marche — chaque
route répond `503` avec `{"error":"CheeseKart not ready"}`, plutôt que d'échouer d'une
manière inexploitable.

Une route non reconnue sous ce préfixe répond `404` en renvoyant le chemin :
`{"error":"Unknown kart route: /whatever"}`. Toute exception non rattrapée devient un
`500` qui porte le message de l'exception.

---

## Routes des presets

### `GET /presets`

Tous les presets configurés, en tableau JSON. Aucun paramètre. Rôle : n'importe lequel.

```json
[
  {
    "id": "rally",
    "name": "Rally",
    "slipperiness": 0.98,
    "stepHeight": 1.25,
    "gravity": -0.04,
    "forwardAccel": 0.04,
    "backwardAccel": 0.005,
    "yawAccel": 1.0,
    "turnForwardAccel": 0.005,
    "accelStacking": false,
    "airControl": true,
    "airStepping": true,
    "jumpForce": 0.0,
    "fallDamage": false,
    "locked": true,
    "species": ""
  }
]
```

Un preset porte toujours ses seize champs. Le sens de chaque champ est dans
[configuration](configuration.fr.md#champs-dun-preset).

### `GET /presets/{id}`

Un seul preset, de la même forme qu'un élément du tableau ci-dessus. Rôle : n'importe
lequel.

| Statut | Corps |
|---|---|
| `200` | le preset |
| `404` | `{"error":"Preset not found: <id>"}` |

### `POST /presets`

Crée un preset. Rôle : `OPERATOR` ou au-dessus.

| Champ du corps | Requis | Remarques |
|---|---|---|
| `id` | oui | doit correspondre à `[a-z0-9_-]+` — minuscules, chiffres, tiret, trait de soulignement |
| `name` | oui | texte libre |
| tout autre champ de preset | non | les champs omis prennent les valeurs par défaut de [configuration](configuration.fr.md#champs-dun-preset) |

```bash
curl -X POST http://localhost:44311/api/v1/kart/presets \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"id":"ice-sprint","name":"Ice Sprint","slipperiness":1.12,"forwardAccel":0.07}'
```

| Statut | Corps |
|---|---|
| `201` | le preset créé, ses seize champs résolus |
| `400` | `{"error":"Missing id or name"}` |
| `400` | `{"error":"Invalid ID (lowercase alphanumeric, -, _ only)"}` |
| `409` | `{"error":"Preset already exists: <id>"}` |

Le fichier de configuration est écrit avant l'envoi de la réponse : un `201` signifie
donc que le preset est sur le disque.

### `PUT /presets/{id}`

Modifie un preset, et **le réapplique à toutes les sessions qui l'utilisent**. Rôle :
`OPERATOR` ou au-dessus.

C'est une fusion, pas un remplacement : seuls les champs présents dans le corps sont
changés, tout le reste conserve sa valeur actuelle. Envoyer `{"slipperiness": 1.2}`
change l'adhérence et rien d'autre. L'`id` ne peut pas être changé par cette voie —
supprime et recrée.

```bash
curl -X PUT http://localhost:44311/api/v1/kart/presets/drift \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"slipperiness": 1.15, "yawAccel": 2.4}'
```

| Statut | Corps |
|---|---|
| `200` | le preset complet, à jour |
| `404` | `{"error":"Preset not found: <id>"}` |

La réapplication est planifiée sur le thread serveur : la réponse peut donc revenir un
cheveu avant que les karts des pilotes ne changent réellement. En pratique, cela fait un
tick. Le serveur écrit une ligne par lot :

```text
[CheeseKart] Re-applied preset 'drift' to 3 active session(s)
```

Les sessions qui utilisent un *autre* preset ne sont pas touchées, et le drapeau `locked`
d'une session en cours ne change pas même si tu le modifies ici — il a été copié au
lancement de la session.

Cet endpoint est la raison pour laquelle le mod a cette forme : règle un virage, envoie un
`PUT`, sens la différence au tour suivant sans que personne n'ait quitté son kart.

### `DELETE /presets/{id}`

Supprime un preset. Rôle : `OPERATOR` ou au-dessus.

| Statut | Corps |
|---|---|
| `200` | `{"deleted":true}` |
| `404` | `{"error":"Preset not found: <id>"}` |

Les sessions lancées depuis ce preset continuent de tourner — elles détiennent leur propre
copie des valeurs de physique. Elles ne peuvent plus être réglées, voilà tout, et
l'identifiant de preset renvoyé par `/active` ne pourra plus être résolu.

---

## Routes des sessions

### `GET /active`

Les sessions actives, en tableau. Rôle : n'importe lequel. Tableau vide quand rien ne
tourne.

```json
[
  {
    "playerName": "Steve",
    "playerUuid": "f84c6a79-0a4e-45e0-879b-cd49ebd4c4e2",
    "presetId": "drift",
    "species": "pikachu",
    "locked": true,
    "durationMs": 94213,
    "durationFormatted": "1m 34s"
  }
]
```

| Champ | Remarques |
|---|---|
| `species` | chaîne vide quand aucune transformation n'est active |
| `durationMs` | millisecondes depuis le début de la session, calculées à chaque requête |
| `durationFormatted` | `"45s"` sous la minute, `"1m 34s"` au-dessus |

### `POST /start`

Met un joueur connecté en kart. Rôle : `OPERATOR` ou au-dessus.

| Champ du corps | Requis | Remarques |
|---|---|---|
| `playerName` | oui | doit être connecté |
| `presetId` | oui | doit exister |
| `species` | non | espèce Cobblemon ; prend le dessus sur la `species` du preset |

```bash
curl -X POST http://localhost:44311/api/v1/kart/start \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"playerName":"Steve","presetId":"drift"}'
```

| Statut | Corps |
|---|---|
| `200` | `{"started":true}` |
| `400` | `{"error":"Missing playerName or presetId"}` |
| `404` | `{"error":"Player not found: <name>"}` |
| `404` | `{"error":"Preset not found: <id>"}` |

> ⚠️ `{"started":true}` signifie que le lancement a été **planifié sur le thread
> serveur**, pas qu'il a réussi. La montée forcée peut encore échouer — si un joueur
> chevauche déjà autre chose, par exemple — et cet échec apparaît dans le log du serveur,
> pas dans cette réponse. Confirme avec `GET /active` si cela compte.

### `POST /stop`

Met fin à la session d'un joueur. Rôle : `OPERATOR` ou au-dessus.

| Champ du corps | Requis |
|---|---|
| `playerName` | oui |

| Statut | Corps |
|---|---|
| `200` | `{"stopped":true}` |
| `400` | `{"error":"Missing playerName"}` |
| `404` | `{"error":"Player not found: <name>"}` |

Un joueur connecté mais sans session renvoie `200` — contrairement à la commande, qui te
dit qu'il n'était pas en kart.

### `POST /stopall`

Met fin à toutes les sessions. Rôle : `OPERATOR` ou au-dessus. Aucun corps.

| Statut | Corps |
|---|---|
| `200` | `{"stoppedAll":true}` |

---

## Pas de canal WebSocket

CheeseKart ne publie rien sur le WebSocket de CheeseDash. Les changements d'état de kart
atteignent les clients *du jeu* par le payload `cheesekart:sync` propre au mod, qui est
un mécanisme entièrement différent, auquel un dashboard ne peut pas s'abonner.

Une vue de dashboard qui veut des données de session en direct doit interroger
`GET /active` en boucle. C'est ce que fait la vue intégrée.

---

## Appelé depuis les événements CheeseGuard

Deux types d'effet CheeseGuard atteignent ce mod directement, par un hook Java plutôt que
par HTTP :

| Effet | Paramètres | Comportement |
|---|---|---|
| `KART_START_ALL` | `kartPresetId`, `kartSpeciesOverride` | met tous les participants de l'événement en kart |
| `KART_STOP_ALL` | — | met fin à la session de tous les participants |

`kartSpeciesOverride` joue le même rôle que le troisième argument de la commande : laissé
vide, chaque pilote prend la `species` du preset lui-même. Un `kartPresetId` absent ou
inconnu est consigné par CheeseGuard et l'effet ne fait rien :

```text
[CG] KART_START_ALL: no presetId specified
[CG] KART_START_ALL: preset not found: <id>
```

CheeseGuard atteint CheeseKart par réflexion, de la même façon que CheeseKart atteint
CobbleShift : aucun des deux mods n'exige l'autre à la compilation.

Un événement de course démarre donc pour tout le monde au même tick, ce qui est ce que
l'on veut pour une course chronométrée et ce qu'une série d'appels d'API individuels ne
peut pas donner.

---

← [Physique et sessions](physics.fr.md) · [Architecture](architecture.fr.md) →
