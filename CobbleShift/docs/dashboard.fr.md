[← CobbleShift](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](dashboard.md)

# API du dashboard

CobbleShift enregistre un module CheeseDash via l'entrypoint `cheesedash` déclaré dans
`fabric.mod.json`. `CobbleShiftDashboardModule.getPrefix()` renvoie `shift` : chaque route
ci-dessous est donc accessible à `/api/v1/shift<subRoute>` sur le port HTTP de CheeseDash,
**44311**.

L'entrée du module dans la barre latérale s'appelle `CobbleShift`, avec la description
`Transformations et mini-jeux`, codée en dur en français dans `getNavDescription()` et qui
ne passe pas par l'i18n.

L'authentification est celle de CheeseDash, pas de ce mod. `POST /api/v1/auth/login`
renvoie un jeton de session ; chaque appel ci-dessous exige
`Authorization: Bearer <token>`.

---

## Rôles requis

CheeseDash a trois rôles : `READ_ONLY`, `OPERATOR`, `SUPER_ADMIN`. Un handler déclare ce
dont il a besoin en appelant une garde **et en sortant en cas d'échec** :

```java
if (!WebDashboard.requireWrite(exchange)) return;
```

Cette forme compte. `requireWrite` et `requireAdmin` renvoient un booléen et envoient
elles-mêmes la 403 ; elles ne lèvent pas d'exception. Un handler qui appelle
`WebDashboard.requireWrite(exchange)` en ignorant le résultat effectue l'écriture quand
même, et le client reçoit deux réponses. Chaque handler gardé de `ShiftApi` utilise la
bonne forme, avec retour : les neuf ont été vérifiés ligne par ligne pour cette page.

| Garde présente | Rôle nécessaire |
|---|---|
| aucune | toute session authentifiée, `READ_ONLY` compris |
| `requireWrite` | `OPERATOR` ou `SUPER_ADMIN` |
| `requireAdmin` | `SUPER_ADMIN` uniquement |

Appliqué aux quinze routes :

| Méthode | Chemin | Garde dans le code | Rôle |
|---|---|---|---|
| `GET` | `/transforms` | aucune | tous |
| `POST` | `/transform` | `requireWrite` | OPERATOR |
| `POST` | `/revert` | `requireWrite` | OPERATOR |
| `POST` | `/revertall` | `requireWrite` | OPERATOR |
| `GET` | `/abilities` | aucune | tous |
| `POST` | `/ability` | `requireWrite` | OPERATOR |
| `POST` | `/ability/global` | `requireAdmin` | **SUPER_ADMIN** |
| `POST` | `/ability/toggle` | `requireWrite` | OPERATOR |
| `GET` | `/ability/disabled` | aucune | tous |
| `GET` | `/species` | aucune | tous |
| `GET` | `/species/:name` | aucune | tous |
| `GET` | `/config` | aucune | tous |
| `PUT` | `/config` | `requireAdmin` | **SUPER_ADMIN** |
| `POST` | `/scale` | `requireWrite` | OPERATOR |
| `POST` | `/speed` | `requireWrite` | OPERATOR |

Deux routes sont réservées à SUPER_ADMIN : l'interrupteur global des capacités et la mise
à jour de la config. Toutes deux écrivent `config/cobbleshift.json` sur le disque, et c'est
là que le mod trace la limite entre une action d'opérateur et une action
d'administrateur. Remarque l'asymétrie avec les commandes : en jeu, `/cshift ability
global` n'exige que le niveau de permission 2, comme toutes les autres sous-commandes.

Toute route non reconnue renvoie `404 {"error":"Route not found"}`. Une exception, où
qu'elle survienne pendant l'aiguillage, est interceptée et renvoyée en `500` avec le
message de l'exception dans un champ `detail` ; ce message est échappé à la main, pas par
Gson, alors traite-le comme une sortie de diagnostic et non comme quelque chose à
analyser.

Si le module est chargé mais que la référence au serveur est nulle (au démarrage, ou
après l'arrêt), le module répond `503 {"error":"CobbleShift not ready"}` avant même que
`ShiftApi` ne voie la requête.

---

## Transformations

### `GET /api/v1/shift/transforms`

Les joueurs et les PNJ dans une seule réponse.

```json
{
  "players": [
    {
      "playerUuid": "…",
      "playerName": "Steve",
      "species": "charizard",
      "type1": "fire",
      "type2": "flying",
      "bst": 534,
      "shiny": false,
      "transformedAt": 1730200000000,
      "attacksEnabled": true,
      "scale": 1.0
    }
  ],
  "npcs": [
    {
      "npcUuid": "…",
      "species": "snorlax",
      "shiny": false,
      "form": "…",
      "type1": "normal",
      "scale": 1.0,
      "speed": 1.0
    }
  ]
}
```

Les objets joueur viennent de `TransformData.toJson()` ; `form` et `type2` sont omis
quand ils sont nuls au lieu d'être envoyés à `null`. Toujours `200`.

### `POST /api/v1/shift/transform`

```json
{ "playerName": "Steve", "species": "charizard" }
```

Les deux champs sont requis. L'appel exécute `transform(player, species)`, la surcharge à
deux arguments : **le chromatique et la forme ne peuvent pas être demandés par l'API**.
Seule la grammaire de la commande les expose. La mutation est enveloppée dans
`WebDashboard.executeOnServerThread`.

| Statut | Corps |
|---|---|
| `200` | `{"transformed":true,"player":"Steve","species":"charizard"}` |
| `400` | `{"error":"playerName and species required"}` |
| `400` | `{"error":"Transform failed — species not found or Cobblemon unavailable"}` |
| `404` | `{"error":"Player not online: Steve"}` |
| `500` | `{"error":"Server thread error: …"}` |

Le `400` d'échec couvre aussi le cooldown de transformation, qui fait renvoyer `false` à
`transform()` sans signal distinct. Le joueur voit le message de cooldown en jeu ;
l'appelant de l'API voit « species not found ».

### `POST /api/v1/shift/revert`

```json
{ "playerName": "Steve" }
```

`200` → `{"reverted":true,"player":"Steve"}`. `400` quand le joueur n'a pas de
transformation active, `404` quand il est déconnecté.

### `POST /api/v1/shift/revertall`

Sans corps. `200` → `{"reverted": 4}`, le nombre de transformations actives avant le
balayage. Les transformations de PNJ ne sont pas touchées.

---

## Capacités

### `GET /api/v1/shift/abilities`

Un tableau, un objet par capacité active. Chacun est un `AbilityData.toJson()` complété de
trois champs calculés.

```json
[
  {
    "playerUuid": "…",
    "species": "gengar",
    "type1": "ghost",
    "type2": "poison",
    "forced": true,
    "activatedAt": 1730200000000,
    "playerName": "Steve",
    "abilityName": "Ombre Furtive",
    "disabled": false
  }
]
```

`playerName` vaut `"offline"` quand l'UUID ne correspond à aucun joueur connecté.
`abilityName` est le nom affiché en français tiré de l'enum `AbilityType`. Cet endpoint
est le seul endroit où l'API rattache un UUID à un nom de joueur, ce que `/cshift list` ne
fait pas.

### `POST /api/v1/shift/ability`

```json
{ "playerName": "Steve", "species": "gengar" }
```

Avec une `species` non vide, force la capacité. Omets `species` ou envoie-la vide pour
lever le forçage.

| Statut | Corps |
|---|---|
| `200` | `{"forced":true,"player":"Steve","species":"gengar"}` |
| `200` | `{"cleared":true,"player":"Steve"}` |
| `400` | `{"error":"playerName required"}` |
| `404` | `{"error":"Player not online: Steve"}` |

L'espèce n'est pas validée. Un nom inconnu est stocké et se résout en type `normal`, qui
n'accorde rien.

### `POST /api/v1/shift/ability/global` (SUPER_ADMIN)

```json
{ "enabled": true }
```

Un champ `enabled` absent compte comme `false` : le test est `data.has("enabled") &&
data.get("enabled").getAsBoolean()`, donc `POST {}` **désactive** le sous-système.
Écrit la config et, à la désactivation, planifie `deactivateAllAndSync()` sur le thread
serveur.

`200` → `{"abilityGlobalEnabled": true}`.

### `POST /api/v1/shift/ability/toggle`

```json
{ "playerName": "Steve" }
```

`200` → `{"player":"Steve","disabled":true}`, où `disabled` est l'état **après**
l'inversion. `400` sans `playerName`, `404` quand le joueur est déconnecté.

Ce handler modifie `disabledPlayers` directement depuis le thread HTTP au lieu de passer
par `executeOnServerThread`. L'ensemble est un `ConcurrentHashMap.newKeySet()`, ce qui
est sans danger, mais `togglePlayer` envoie aussi un paquet depuis ce thread. Signalé
dans [l'architecture](architecture.fr.md#threading).

### `GET /api/v1/shift/ability/disabled`

```json
[ { "uuid": "…", "name": "Steve" } ]
```

`name` vaut `"offline"` pour un UUID sans joueur connecté.

---

## Espèces

### `GET /api/v1/shift/species`

Pas une liste, malgré le chemin.

```json
{
  "cobblemonAvailable": true,
  "note": "Use /species/:name to query individual species. Full list requires Cobblemon registry access."
}
```

`SpeciesMapper.getAllSpeciesNames()` existe pourtant bel et bien et renvoie la liste
complète triée : la complétion par tabulation de la commande s'en sert. Cet endpoint est
antérieur à cette méthode et n'a jamais été mis à jour. Traite-le comme une sonde de
présence de Cobblemon.

### `GET /api/v1/shift/species/:name`

Reconnu par `subRoute.matches("/species/[^/]+")` : le nom ne peut donc pas contenir de
barre oblique. Aucune autre validation : le nom part directement dans la recherche par
réflexion.

```json
{
  "name": "charizard",
  "type1": "fire",
  "type2": "flying",
  "hp": 78, "atk": 84, "def": 78, "spAtk": 109, "spDef": 85, "speed": 100,
  "height": 17.0,
  "mappedSpeed": 0.125,
  "mappedHealth": 15.2,
  "mappedScale": 2.5,
  "bst": 534,
  "attackDamage": 6.36,
  "attackCooldown": 32,
  "projectileCount": 3,
  "ability1Name": "Flamme Intérieure",
  "ability2Name": "Plume Légère"
}
```

⚠️ Ici, `mappedScale` est faux. Le handler appelle `mapScale(stats.height())` en passant
la taille du Pokédex en **décimètres**, alors qu'une transformation appelle
`mapScale(effectiveHitboxHeight)` en blocs. Comme `mapScale` plafonne à 2,5, presque toutes
les espèces renvoient `2.5` depuis cet endpoint. La sortie en jeu de `/cshift species` n'a
pas ce bug. Ne te sers pas de la valeur de l'API pour prévoir la taille d'une
transformation.

`404` → `{"error":"Species not found: <name>"}`, ce qui, sans Cobblemon, vaut pour tous les
noms.

---

## Config

### `GET /api/v1/shift/config`

L'objet de config complet, exactement `CobbleShiftConfig.toJson()` : les seize clés,
documentées dans [la configuration](configuration.fr.md). Aucune garde : une session
`READ_ONLY` peut la lire.

### `PUT /api/v1/shift/config` (SUPER_ADMIN)

Un objet partiel. Chaque clé n'est appliquée que si elle est présente, via son mutateur :
les bornes décrites dans [la configuration](configuration.fr.md#plages-et-bornes)
s'appliquent donc. Le fichier entier est ensuite sauvegardé.

```json
{ "abilityGlobalEnabled": true, "abilityTickInterval": 60, "abilityEffectDuration": 120 }
```

`200` → `{"saved":true}`.

⚠️ Deux clés ne sont **pas** acceptées ici : `hotPotatoEnabled` et `hotPotatoPassRange`.
Elles existent dans la classe de config et dans le fichier, et `GET /config` les renvoie,
mais le handler du `PUT` n'a pas de branche pour elles. Les envoyer est ignoré en silence.
Ce sont des vestiges ; [la configuration](configuration.fr.md#clés-qui-ne-font-rien)
explique pourquoi.

Les changements s'appliquent au tick de rafraîchissement suivant. Rien n'exige de
redémarrage.

---

## Échelle et vitesse

### `POST /api/v1/shift/scale`

```json
{ "uuid": "…", "scale": 1.5 }
```

Indexé par UUID et non par nom, et aiguillé selon ce qu'est cet UUID : d'abord un joueur
transformé, puis un PNJ transformé. `scale` est borné à `[0.1, 10.0]` et vaut `1.0` par
défaut quand il est absent.

```json
{ "type": "player", "uuid": "…", "scale": 1.5, "success": true }
```

`type` vaut `"player"` ou `"npc"`. `400` pour un UUID absent ou mal formé, `404` pour un
UUID qui n'est ni l'un ni l'autre.

### `POST /api/v1/shift/speed`

```json
{ "uuid": "…", "speed": 2.0 }
```

**PNJ uniquement.** Malgré la symétrie avec `/scale`, cette route ne touche jamais un
joueur : elle vérifie `npcMgr.isNpcTransformed(uuid)` et renvoie sinon
`404 {"error":"UUID is not a transformed NPC"}`. La vitesse d'un joueur ne se règle que
par `/cshift speed`.

Borné à `[0.1, 10.0]`. `200` → `{"uuid":"…","speed":2.0,"success":true}`.

---

## WebSocket

**CobbleShift ne publie rien en WebSocket.** Il n'y a aucun appel à
`DashboardRegistry.broadcast` dans le mod, aucun canal enregistré et aucun type de
message `shift`. Une vue du dashboard qui affiche les transformations doit interroger
`GET /transforms` à intervalles réguliers.

Le mod envoie bien ses propres paquets Minecraft (`TransformSyncPayload`,
`AbilitySyncPayload` et `AnimationStateSyncPayload` vers les clients,
`AttackAbilityPayload` depuis eux), mais ils passent par la connexion de jeu, pas par le
port 44312 de CheeseDash. Ils sont décrits dans
[l'architecture](architecture.fr.md#payloads-réseau).

---

← [Transformations de PNJ](npc-transforms.fr.md) · [Architecture](architecture.fr.md) →
