[← EggAndCheese](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](dashboard.md)

# API dashboard

EggAndCheese enregistre un module CheeseDash via l'entrypoint personnalisé `cheesedash`
de `fabric.mod.json` :

```json
"entrypoints": {
  "cheesedash": ["com.roicheese.eggandcheese.dashboard.EggAndCheeseDashboardModule"]
}
```

| Propriété du module | Valeur |
|---|---|
| préfixe | `eggs` |
| nom affiché | `EggAndCheese` |
| icône de navigation | 🥚 |
| description de navigation | `Collectibles et œufs` |

Toutes les routes ci-dessous vivent donc sous `/api/v1/eggs/`. CheeseDash sert le HTTP sur
le port **44311** et le WebSocket sur **44312**.

`EggAndCheeseDashboardModule` conserve une référence au `MinecraftServer` entre
`onServerStart` et `onServerStop`, et répond `503 {"error":"Server not available"}` à
toute requête qui arrive pendant que cette référence est nulle — au démarrage, ou après
l'arrêt. Sinon il transmet la requête directement à `EggsApi.handle`.

## Authentification et rôles

L'authentification est celle de CheeseDash, pas celle de ce mod.
`POST /api/v1/auth/login` renvoie un token de session, à passer en
`Authorization: Bearer <token>` sur toutes les autres requêtes.

⚠️ **Toutes les routes d'`EggsApi` n'exigent qu'une session valide. Il n'y a aucun
contrôle de rôle dans l'API de ce mod.** `EggsApi.java` ne contient aucun appel à
`WebDashboard.requireWrite` ni à `WebDashboard.requireAdmin` — vérifié par un grep sur
tout l'arbre des sources. Un compte `READ_ONLY` peut donc supprimer un collectible, en
renommer un, effacer des découvertes, donner des items à des joueurs connectés, réécrire
la configuration et lancer la réparation d'intégrité.

C'est un vrai manque, pas un choix de conception, et il vaut la peine de rappeler à quoi
ressemble la forme correcte. Dans ce dépôt, `WebDashboard.requireWrite(exchange)`
**renvoie un booléen** et ne bloque rien par lui-même : ignorer son résultat ne fait rien.
La forme qui impose réellement quelque chose est :

```java
if (!WebDashboard.requireWrite(exchange)) return;
```

Tant que les handlers mutants ci-dessous ne commencent pas par cette ligne, considère que
le « rôle requis » est « n'importe quelle session authentifiée », et restreins en
conséquence l'attribution des comptes `READ_ONLY`.

La colonne des rôles dans les tableaux ci-dessous consigne ce que le code impose
aujourd'hui, pas ce qu'il devrait imposer.

## Conventions

- Les corps de requête et de réponse sont en JSON. Les réponses sont construites avec
  Gson.
- `:id` est décodé depuis l'URL, puis validé par `isValidId` : non vide, sans `..`, sans
  `/`, sans `\`, sans `%`, et de moins de 128 caractères. Un échec donne
  `400 {"error":"Invalid collectible ID"}`.
- Chaque handler qui touche l'état du jeu enveloppe son travail dans
  `WebDashboard.executeOnServerThread` : une mutation est donc appliquée sur le thread
  Minecraft même si c'est le thread HTTP qui a renvoyé la réponse.
- Un collectible absent donne `404 {"error":"Collectible not found"}`. Les échecs internes
  donnent `500` avec le message d'exception dans `error`.
- Un chemin non reconnu donne `404 {"error":"Unknown eggs route: <route>"}`, ou
  `404 {"error":"Unknown collectible route"}` sous `/collectibles/:id`.
- Un corps JSON mal formé ne provoque **pas** d'erreur : `parseJson` renvoie un objet
  vide, et la requête se comporte comme si tu avais envoyé `{}`. Un `PUT` au JSON cassé
  est un succès qui ne fait rien.

## Collectibles

| Méthode | Chemin | Rôle imposé | Ce que ça fait |
|---|---|---|---|
| `GET` | `/collectibles` | n'importe quelle session | Toutes les entrées, triées par id, en résumé |
| `POST` | `/collectibles` | n'importe quelle session | Créer une entrée |
| `GET` | `/collectibles/:id` | n'importe quelle session | Une entrée, détail complet avec l'ADN |
| `PUT` | `/collectibles/:id` | n'importe quelle session | Modifier des champs et/ou l'ADN, déplacer le bloc |
| `DELETE` | `/collectibles/:id` | n'importe quelle session | Supprimer l'entrée, son bloc et sa quête FTB |
| `POST` | `/collectibles/:id/rename` | n'importe quelle session | Changer l'id |
| `POST` | `/collectibles/:id/randomize-dna` | n'importe quelle session | Remplacer l'ADN par un nouvel ADN aléatoire |
| `GET` | `/collectibles/:id/discoveries` | n'importe quelle session | Qui l'a trouvé, avec les pseudos |
| `POST` | `/collectibles/:id/discoveries` | n'importe quelle session | Enregistrer une découverte par pseudo |
| `DELETE` | `/collectibles/:id/discoveries` | n'importe quelle session | Effacer toutes les découvertes |
| `DELETE` | `/collectibles/:id/discoveries/:uuid` | n'importe quelle session | Retirer une découverte |
| `POST` | `/collectibles/:id/give-admin` | n'importe quelle session | Donner l'item de placement admin à un joueur connecté |
| `POST` | `/collectibles/:id/give-deco` | n'importe quelle session | Donner l'item décoratif à un joueur connecté |

### Forme résumée

Renvoyée par `GET /collectibles` pour chaque entrée, et imbriquée dans chaque réponse de
détail :

```json
{
  "id": "cave_nord",
  "type": "egg",
  "title": "La grotte du nord",
  "subtitle": "Derrière la cascade",
  "dimension": "minecraft:overworld",
  "x": 812, "y": 71, "z": -304,
  "scale": 1.4,
  "renderDistanceOverride": -1,
  "positionLost": false,
  "discoveryCount": 7,
  "decoCount": 7,
  "rarity": "EPIC",
  "rarityDisplay": "Épique",
  "rarityColor": "#AA00FF",
  "palette": "ICE",
  "paletteDisplay": "Glace"
}
```

`rarityDisplay` et `paletteDisplay` viennent du `getDisplayName()` des enums, qui est
**en français dans le code source**. Ce sont des chaînes d'affichage de l'enum Java, pas
des valeurs traduites par la couche i18n du dashboard. Préfère `rarity` et `palette` si tu
veux les traduire toi-même.

### Forme détaillée

`GET /collectibles/:id` renvoie le résumé plus deux champs :

```json
{
  "...": "tous les champs du résumé",
  "dna": {
    "seed": 6857411294431479272,
    "palette": "ICE",
    "pattern": "CRYSTALS",
    "patternScale": 2,
    "secondaryPattern": "MARBLE",
    "patternBlend": 35,
    "emissive": "STARS",
    "emissiveDensity": 60,
    "rarity": "EPIC",
    "stamp": "MOON",
    "microTexture": "ICY",
    "visualFlags": 15,
    "varnishLevel": 45,
    "customPalette": "",
    "dnaCode": "EAC-5F2A91C30B4D77E8-ICE.CRYSTALS.2.MARBLE.35.STARS.60.EPIC.MOON.ICY.0F.45",
    "flagLighting": true,
    "flagSpecular": true,
    "flagEmboss": true,
    "flagVarnish": true,
    "flagRimGlow": false
  },
  "discoveredByUuids": ["c3f1…", "9a02…"]
}
```

Les cinq booléens `flag*` sont décodés depuis `visualFlags` pour simplifier la vie du
formulaire. Ils sont en lecture seule : une écriture doit envoyer l'entier.

### `POST /collectibles`

Tous les champs du corps sont facultatifs.

| Champ | Type | Défaut | Remarques |
|---|---|---|---|
| `id` | chaîne | `egg_NNN` automatique | Vide ou absent déclenche `generateUniqueId()` |
| `type` | chaîne | `egg` | `CollectibleType.fromId` : toute valeur inconnue devient `egg` |
| `dimension` | chaîne | `minecraft:overworld` | Non vérifié contre les mondes chargés |
| `x`, `y`, `z` | entier | `0`, `64`, `0` | |
| `seed` | long | aléatoire | Si présent, l'ADN est `randomDNA(seed)` ; sinon totalement aléatoire |
| `scale` | flottant | `1.0` | Borné à 0,1–4,0 par le setter |
| `title`, `subtitle` | chaîne | `""` | Servent de titre et de description à la quête FTB |

Réponses : `200` avec la forme détaillée, `409 {"error":"ID already exists: <id>"}`, ou
`500`.

⚠️ `POST` crée **seulement une entrée de registre**. Aucun bloc n'est posé dans le monde.
L'entrée n'est pas non plus marquée `positionLost` : elle revendique donc des coordonnées
où rien ne se trouve, jusqu'à ce qu'un admin y pose l'item. Crée depuis le dashboard, puis
pose en jeu.

Il n'y a aucun moyen de fournir un ADN complet à la création : tu obtiens un ADN
aléatoire, éventuellement depuis ta graine. Pour composer une apparence exacte, crée
l'entrée puis envoie l'ADN par `PUT`.

### `PUT /collectibles/:id`

Accepte n'importe quel sous-ensemble de `title`, `subtitle`, `scale`, `positionLost`,
`renderDistanceOverride`, `dimension`, `x`, `y`, `z`, `dna`. La sémantique de fusion de
l'ADN est décrite dans
[L'ADN d'un collectible](dna.fr.md#immuabilité-et-ce-quun-put-doit-faire) — les champs
d'ADN omis gardent leur valeur courante.

Deux effets de bord en plus de l'écriture dans le registre :

- **Déplacement.** Si le corps contient l'un de `x`, `y`, `z`, `dimension` et que l'entrée
  n'est pas `positionLost`, l'ancien bloc est retiré et un nouveau bloc `admin_egg` est
  posé à la nouvelle position, configuré avec l'id, l'ADN, l'échelle et la surcharge de
  distance de rendu de l'entrée. Les deux mondes doivent être chargés pour que cela
  s'exécute ; si l'une des deux recherches échoue, le registre bouge et le bloc non.
- **Synchronisation sur place.** Si la position n'a *pas* changé mais que le corps contient
  `renderDistanceOverride`, `scale` ou `dna`, le block entity existant est mis à jour sur
  place, ce qui pousse le changement aux clients immédiatement.

Le titre et la description de la quête FTB liée sont mis à jour quand l'intégration FTB
est active et que l'entrée a un id de quête.

### `DELETE /collectibles/:id`

Supprime la quête FTB s'il y en a une, retire le bloc du monde sauf si l'entrée est
`positionLost`, puis retire l'entrée du registre. `200 {"deleted":true}`.

### `POST /collectibles/:id/rename`

Corps `{"newId":"cave_nord"}`. Le nouvel id passe par `isValidId`, puis par
`EggRegistryData.renameEntry`, qui refuse si l'id cible appartient à une autre entrée.
Renommer vers le même id est un succès sans effet. La réponse est la forme détaillée, ou
`400` — soit `{"error":"Invalid new ID"}`, soit
`{"error":"Rename failed (ID conflict or not found)"}`.

⚠️ Le renommage ne touche **pas** au bloc du monde. Le bloc garde l'ancien `registryId`,
ce qui en fait un orphelin dès que l'entrée est renommée, tandis que l'entrée renommée se
retrouve sans bloc. Lance [l'outil d'intégrité](integrity.fr.md) ensuite, ou repose l'œuf.
C'est un vrai bug, pas une réserve d'usage.

### `POST /collectibles/:id/randomize-dna`

Corps `{"seed": 1234}` ou vide. Construit un ADN aléatoire entièrement neuf — ta palette
et ton motif réglés à la main sont perdus — l'écrit dans l'entrée, et synchronise le block
entity quand l'entrée n'est pas `positionLost`. Renvoie la forme détaillée.

### Découvertes

`GET` renvoie un tableau de `{"uuid": "...", "name": "..."}`. Le pseudo vient du cache
utilisateurs du serveur ; quand l'UUID n'y est pas, `name` vaut les huit premiers
caractères de l'UUID.

`POST` prend `{"playerName":"Lia"}` et le résout par
`server.getUserCache().findByName` : le joueur n'a donc pas besoin d'être connecté, mais
il doit s'être déjà connecté une fois. `400 {"error":"playerName required"}` quand le
champ est vide, `400 {"error":"Player not found: Lia"}` quand le cache ne l'a jamais vu.
Le succès est `200 {"added":true}`.

`DELETE` sur la collection efface toutes les découvertes : `200 {"cleared":true}`.
`DELETE` avec un UUID en retire une : `200 {"removed":true}`, ou
`400 {"error":"Invalid UUID"}` sur un UUID illisible. Ni l'un ni l'autre ne touche
`decoReceivedBy`.

### Donner un item

Les deux routes de don prennent `{"playerName":"Lia"}` et exigent que le joueur soit
**connecté** — elles passent par `server.getPlayerManager().getPlayer(name)` et répondent
`400 {"error":"Player not online: Lia"}` sinon. L'item est inséré dans l'inventaire, ou
lâché aux pieds du joueur si celui-ci est plein.

| Route | Item | Réponse |
|---|---|---|
| `give-admin` | `eggandcheese:admin_egg` avec `dna`, `registryId` et `scale` dans `CUSTOM_DATA` | `{"given":true,"type":"admin"}` |
| `give-deco` | `eggandcheese:collectible_egg` avec le même ADN et le `registryId` | `{"given":true,"type":"deco"}` |

`give-deco` n'enregistre rien dans `decoReceivedBy` : la route peut donc être utilisée
plusieurs fois sur le même joueur.

## Configuration

| Méthode | Chemin | Rôle imposé | Ce que ça fait |
|---|---|---|---|
| `GET` | `/config` | n'importe quelle session | Lire les quatre réglages plus deux compteurs |
| `PUT` | `/config` | n'importe quelle session | Écrire n'importe quel sous-ensemble, puis pousser un paquet de sync à tous les joueurs connectés |
| `POST` | `/config/sync-ftb` | n'importe quelle session | Lancer une synchronisation FTB Quests complète |

```json
{
  "ftbQuestsEnabled": false,
  "ftbQuestsChapter": "oeufs",
  "ftbAutoSync": false,
  "textureRenderDistance": 20,
  "totalEntries": 56,
  "totalDiscoveries": 318
}
```

`totalEntries` et `totalDiscoveries` sont en lecture seule ; le `PUT` les ignore. Le `PUT`
relit et renvoie la même forme : la réponse est donc le nouvel état.

Le `PUT` est aussi le seul chemin de code qui appelle `ModPackets.sendRegistrySync` —
voir [la distance de rendu](rendering.fr.md#distance-de-rendu-et-niveau-de-détail) pour
comprendre pourquoi cela compte.

`POST /config/sync-ftb` répond `200 {"synced":true}` que FTB Quests soit installé ou non ;
`FtbQuestsHook.syncAll` sort immédiatement quand le hook est indisponible.

## Statistiques

`GET /stats` :

```json
{
  "totalEntries": 56,
  "totalDiscoveries": 318,
  "eggCount": 56,
  "cheeseCount": 0,
  "byRarity": { "COMMON": 21, "UNCOMMON": 14, "RARE": 10, "EPIC": 7, "LEGENDARY": 3, "MYTHIC": 1 }
}
```

`cheeseCount` compte les entrées dont le type est `CHEESE`. Aucun bloc ni item de fromage
n'est enregistré dans cette version : sur une installation normale, la valeur est donc
`0` — voir [architecture](architecture.fr.md#faiblesses-connues).

## Enums

`GET /enums` renvoie en une seule réponse toutes les listes déroulantes dont l'éditeur du
dashboard a besoin. Aucun état serveur n'est touché : c'est le seul handler qui ne passe
pas par le thread serveur.

| Clé | Entrées | Champs par entrée |
|---|---|---|
| `palettes` | 34 | `id`, `displayName`, `colors` (six chaînes `RRGGBB`) |
| `patterns` | 26 | `id`, `displayName` |
| `emissives` | 16 | `id`, `displayName` |
| `rarities` | 6 | `id`, `displayName`, `color` (`#RRGGBB`) |
| `stamps` | 10 | `id`, `displayName` |
| `microTextures` | 8 | `id`, `displayName` |
| `types` | 2 | `id` (`egg` / `cheese`), `name` (`EGG` / `CHEESE`) |

## Intégrité

| Méthode | Chemin | Rôle imposé | Ce que ça fait |
|---|---|---|---|
| `GET` | `/integrity` | n'importe quelle session | Rapport de doublons et d'orphelins sur les chunks chargés |
| `POST` | `/integrity/reconcile` | n'importe quelle session | Délier les doublons et les orphelins |

Les deux sont documentés en entier, payloads et raisonnement compris, dans
[intégrité](integrity.fr.md#les-points-dapi-du-dashboard).

## WebSocket

Un seul type de message est publié, sur le canal **`eggs`**. On s'y abonne de la façon
habituelle :

```json
{"type":"subscribe","channels":["eggs"]}
```

### `egg_discovery`

Envoyé depuis `AdminCollectibleBlockEntity.handlePlayerDiscovery` quand la découverte d'un
joueur est enregistrée — pas sur un clic droit d'admin, et pas quand le dashboard ajoute
une découverte à la main.

```json
{
  "type": "egg_discovery",
  "playerName": "Lia",
  "eggId": "cave_nord",
  "timestamp": 1750300800000
}
```

`timestamp` est `System.currentTimeMillis()`. La diffusion est protégée par
`FabricLoader.isModLoaded("cheesedash")` et enveloppée dans un `catch` sur
`NoClassDefFoundError` : un build sans CheeseDash dans le classpath se dégrade en silence
au lieu de lever une exception au milieu de l'interaction.

**Aucun autre message WebSocket n'est publié par ce mod.** Créations, modifications,
suppressions, renommages, changements de configuration et réparations d'intégrité sont
tous silencieux : une seconde session du dashboard ne les verra pas avant de recharger la
vue.

---

← [Intégrité : doublons et orphelins](integrity.fr.md) · [Architecture](architecture.fr.md) →
