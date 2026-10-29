[← CheeseMarket](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](dashboard.md)

# API dashboard

CheeseMarket enregistre un module CheeseDash via l'entrypoint `cheesedash` déclaré dans
`fabric.mod.json` :

```json
"entrypoints": { "cheesedash": ["com.cheesemarket.dashboard.CheeseMarketDashboardModule"] }
```

Le préfixe du module est **`market`**, donc toutes les routes ci-dessous sont joignables
sur `/api/v1/market/...`, sur le port HTTP de CheeseDash, **44311**. Le module fournit
aussi un nom d'affichage (`CheeseMarket`), une icône de navigation (`🛒`) et une
description (`Gestion des boutiques`) pour la barre latérale du dashboard.

L'authentification est celle de CheeseDash, pas celle de ce mod :
`POST /api/v1/auth/login` renvoie un token de session, et chaque appel ci-dessous exige
`Authorization: Bearer <token>`.

---

## Rôles requis

`MarketApi` ne contient **aucun appel à `requireWrite` ni à `requireAdmin`**. Vérifie
toi-même — il n'y en a pas un seul. Le garde qui protège ces routes est le dispatch central
des modules de CheeseDash, dans `DashboardApi` :

```java
DashboardModule module = DashboardRegistry.getModule(prefix);
if (module != null) {
    // Enforce write permissions for non-GET requests on modules
    if (!method.equals("GET") && !WebDashboard.requireWrite(exchange)) {
        return;
    }
    module.handleRequest(exchange, subRoute);
```

Cela compte, parce que dans ce dépôt `WebDashboard.requireWrite(exchange)` **renvoie un
booléen et ne bloque rien par lui-même** : ignorer son résultat laisse passer la requête.
Le dispatch ci-dessus utilise la forme correcte, `if (!…) return;`, donc le garde est
réel.

La règle qui en découle, et c'est une règle globale plutôt que route par route :

| Méthode | Rôle requis |
|---|---|
| `GET` | n'importe quelle session authentifiée — `READ_ONLY` suffit |
| `POST`, `PUT`, `DELETE` | `OPERATOR` ou plus |

**Aucune route market n'exige `SUPER_ADMIN`.** Supprimer une boutique, avec ses items, sa
configuration de ventes et ses images téléversées, est une action `OPERATOR`.

Deux conséquences du fait que le garde vit en amont et non dans `MarketApi` :

- une session `READ_ONLY` peut lire toutes les définitions de boutiques, tous les prix, le
  journal complet des transactions avec noms de joueurs et UUID, et toutes les images
  téléversées ;
- si ce module était un jour monté ailleurs que dans le dispatch de modules de CheeseDash,
  il n'aurait aucune autorisation du tout. Rien dans le mod n'impose un rôle par lui-même.

Chaque requête de module non-GET est aussi inscrite au journal d'audit de CheeseDash par
le même dispatch, sous `market.post`, `market.put` ou `market.delete`, avec le chemin
complet.

---

## Boutiques

### `GET /api/v1/market/shops`

Toutes les boutiques chargées, en résumé. Aucun paramètre.

```json
[
  { "id": "general", "name": "Magasin général", "description": "Consommables",
    "displayMode": "TABS", "setCount": 3, "itemCount": 41 }
]
```

`itemCount` compte tous les items, activés ou non.

### `POST /api/v1/market/shops`

Crée une boutique. Rôle : `OPERATOR`.

```json
{ "id": "farmers", "name": "Marché fermier" }
```

Les deux champs sont facultatifs. Un `id` vide ou absent fait générer au mod un
identifiant de 8 caractères. `name` n'est appliqué et enregistré que s'il est présent. La
création tourne sur le thread serveur via `WebDashboard.executeOnServerThread`.

Renvoie l'objet de détail complet de la boutique. `409` avec
`{"error":"Shop ID already exists: <id>"}` si l'identifiant est pris, `500` avec le message
d'exception si la tâche du thread serveur lève.

### `GET /api/v1/market/shops/:shopId`

La définition complète, enrichie des données d'exécution. Les sets gagnent `active` (le
verdict de rotation en direct) et `rotationLabel` (un libellé français comme
`Horaire (08:00→20:00)`) ; les items sont triés par `sortOrder` et gagnent
`outputsSummary`, `costsSummary`, `commandsSummary` et `effectiveName`.

`400` `Invalid shop ID` si l'identifiant contient `..`, `/`, `\`, `%` ou fait 128
caractères ou plus. `404` `Shop not found` sinon.

### `PUT /api/v1/market/shops/:shopId`

Mise à jour partielle. Rôle : `OPERATOR`. Seules les clés présentes dans le corps sont
appliquées : `name`, `description`, `displayMode`, `showWelcomeMessage`,
`welcomeMessage`, `titleColor`, `bgColor`, `borderColor`, `accentColor`, `btnBuyColor`,
`btnBuyTextColor`, `btnCloseColor`, `btnCloseTextColor`, `morphoCatalog`, `layout`.

Un `displayMode` illisible est ignoré plutôt que rejeté. Un `layout` à `null` revient à la
mise en page par défaut ; un objet `layout` la remplace en entier.

Renvoie le nouveau détail de boutique. `404` si la boutique a disparu, `500` si la tâche
lève.

### `DELETE /api/v1/market/shops/:shopId`

Rôle : `OPERATOR`. Supprime la boutique de la mémoire et son fichier JSON, **et** son
dossier d'assets — contrairement à `/shop delete` en jeu, qui laisse les images derrière.

```json
{ "deleted": true }
```

---

## Mise en page

### `GET /api/v1/market/shops/:shopId/layout`

La mise en page de la boutique. Une boutique sans mise en page reçoit
`ShopLayout.buildDefault()` plutôt qu'un objet vide, pour que l'éditeur ait toujours
quelque chose à ouvrir.

### `PUT /api/v1/market/shops/:shopId/layout`

Rôle : `OPERATOR`. Le corps est un objet de mise en page : `elements`, `itemCard`,
`confirmLayout`.

- Un corps portant `elements` ou `itemCard` est analysé et stocké.
- Un corps n'en portant aucun des deux réinitialise la mise en page à null — le défaut.
- Plus de 20 éléments donne `500` `{"error":"Maximum 20 elements"}`, parce que le contrôle
  lève dans la tâche du thread serveur et que le catch en fait un 500 plutôt qu'un 400.

Renvoie la mise en page stockée, ou le défaut si elle a été réinitialisée.

---

## Assets

### `GET /api/v1/market/shops/:shopId/assets`

Un tableau de noms d'assets, sans le `.png`.

### `POST /api/v1/market/shops/:shopId/assets`

Rôle : `OPERATOR`. Téléversement en base64.

```json
{ "name": "shop-bg", "data": "data:image/png;base64,iVBORw0KGgo…" }
```

Un préfixe d'URL `data:` est retiré à la première virgule, donc les deux formes marchent.

```json
{ "uploaded": true, "name": "shop-bg" }
```

`400` pour un nom manquant, des données manquantes, un base64 indécodable, ou tout refus
d'`AssetManager` : `Invalid asset name`, `Empty data`, `File too large (max 2 MB)`,
`Not a valid PNG file`, `Maximum 10 assets per shop`, `Cannot create directory`,
`Failed to write: <message>`.

### `DELETE /api/v1/market/shops/:shopId/assets/:name`

Rôle : `OPERATOR`. `{"deleted":true}`, ou `404` `Asset not found`.

### `GET /api/v1/market/assets/:shopId/:name`

Sert les octets du PNG. `Content-Type: image/png` et
`Cache-Control: public, max-age=3600` — une image remplacée peut donc mettre une heure à
apparaître dans un navigateur qui l'a mise en cache ; c'est pour cette raison que
l'éditeur du dashboard ajoute une chaîne de requête anti-cache. `400` `Invalid path` s'il
n'y a pas de barre oblique après l'identifiant de boutique, `404` `Asset not found` sinon.

Note la forme : cette route est `/market/assets/...`, et non
`/market/shops/:id/assets/...`. Elle exige une session valide comme tout le reste — les
images ne sont pas publiques.

---

## Sets

### `POST /api/v1/market/shops/:shopId/sets`

Rôle : `OPERATOR`. Corps `{"name": "Consommables"}`, facultatif. Le set reçoit un
identifiant aléatoire de 8 caractères et un `sortOrder` égal au nombre de sets courant.
Renvoie le JSON du nouveau set.

### `PUT /api/v1/market/shops/:shopId/sets/:setId`

Rôle : `OPERATOR`. Applique `name`, `sortOrder` et n'importe quel sous-ensemble de
`rotation` :

```json
{ "name": "Soirée", "sortOrder": 1,
  "rotation": { "mode": "SCHEDULE", "scheduleStart": "20:00", "scheduleEnd": "02:00" } }
```

Basculer un set `MANUAL` est un `PUT` avec `rotation.manualActive`. Renvoie le JSON du
set. `404` `Set not found`, `400` `Invalid set ID`.

### `DELETE /api/v1/market/shops/:shopId/sets/:setId`

Rôle : `OPERATOR`. `{"deleted":true}`. Les items du set partent avec lui. Leurs compteurs
de stock restent dans `cheesemarket_stock.json`.

---

## Items

### `POST /api/v1/market/shops/:shopId/sets/:setId/items`

Rôle : `OPERATOR`. Crée un item puis lui applique immédiatement le même corps que le
`PUT` accepte : un item peut donc être créé entièrement formé en un seul appel. Renvoie le
JSON de l'item.

### `PUT /api/v1/market/shops/:shopId/sets/:setId/items/:itemId`

Rôle : `OPERATOR`. Applique n'importe quel sous-ensemble de :

| Clé | Forme |
|---|---|
| `displayItem` | chaîne |
| `displayName` | chaîne |
| `enabled` | booléen |
| `sortOrder` | entier |
| `outputs` | tableau de `{itemId, count, components?}` — **remplace** la liste |
| `costs` | tableau de la même forme — **remplace** la liste |
| `commands` | tableau de `{command, count}` — **remplace** la liste |
| `stock` | objet ; `mode`, `globalStock`, `perPlayerLimit`, `resetType`, `resetIntervalSeconds`, appliqués un par un |

Les trois tableaux sont vidés puis reconstruits : un `PUT` portant `costs` doit donc porter
tout le prix. `stock` est fusionné clé par clé sur la configuration existante. Les valeurs
d'énumération illisibles sont ignorées en silence.

### `DELETE /api/v1/market/shops/:shopId/sets/:setId/items/:itemId`

Rôle : `OPERATOR`. `{"deleted":true}`.

### `POST /api/v1/market/shops/:shopId/sets/:setId/items/:itemId/toggle`

Rôle : `OPERATOR`. Inverse `enabled` et enregistre. Renvoie `{"enabled": false}`.

### `POST /api/v1/market/shops/:shopId/sets/:setId/items/:itemId/reset-stock`

Rôle : `OPERATOR`. ⚠️ **Ne fait rien.** Le corps du handler est un unique
`respond(200, "{\"reset\":true}")` sans aucune logique derrière. Les compteurs ne sont pas
vidés, aucun fichier n'est écrit, et la réponse est indiscernable d'un succès. Pour
réellement réinitialiser un compteur, édite `cheesemarket_stock.json` puis lance
`/shop reload`, comme décrit dans
[la configuration](configuration.fr.md#le-fichier-de-stock).

---

## Transactions et statistiques

### `GET /api/v1/market/transactions`

| Paramètre de requête | Type | Défaut | Effet |
|---|---|---|---|
| `shopId` | chaîne | aucun | ne renvoie que les entrées de cette boutique |
| `n` | entier | 50 | combien en renvoyer ; borné entre 1 et 500 |

```json
{ "transactions": [ … ], "total": 312, "returned": 50 }
```

`total` est la taille du journal complet en mémoire, avant filtrage ; `returned` est ce
qui est revenu. Le filtre est appliqué pendant le décompte vers `n` : filtrer par boutique
avec `n=50` donne donc jusqu'à 50 entrées *de cette boutique*.

Le journal contient au plus 500 entrées au total, la plus récente en premier. Les achats
du catalogue morpho ne sont pas enregistrés du tout et n'apparaissent jamais ici.

### `GET /api/v1/market/stats`

```json
{ "shopCount": 4, "totalSets": 11, "totalItems": 143,
  "enabledItems": 139, "transactionCount": 312 }
```

Compté en direct en parcourant les boutiques. `transactionCount` est plafonné par le
journal de 500 entrées et n'est pas un total cumulé.

---

## Templates

### `GET /api/v1/market/templates`

Un tableau de noms de templates, sans le `.json`.

### `POST /api/v1/market/templates`

Rôle : `OPERATOR`.

```json
{ "name": "wide-grid", "layout": { "elements": [ … ], "itemCard": { … } } }
```

`{"saved":true}`. `400` `Name required`, `400` `Layout required` si `layout` est absent ou
n'est pas un objet, `400` `Invalid template name` ou `Maximum 50 templates` venant
d'`AssetManager`.

### `GET /api/v1/market/templates/:name`

L'objet de mise en page, ou `404` `Template not found`.

### `DELETE /api/v1/market/templates/:name`

Rôle : `OPERATOR`. `{"deleted":true}` ou `404` `Template not found`.

---

## Formes d'erreur

| Statut | Quand |
|---|---|
| `200` | succès ; le corps est la ressource, ou un petit objet d'accusé de réception |
| `400` | un identifiant a échoué à `isValidId`, ou un asset/template a été refusé |
| `404` | boutique, set, item, asset ou template introuvable ; ou route inconnue, avec la route renvoyée en écho |
| `409` | identifiant de boutique déjà utilisé, à la création |
| `500` | une tâche du thread serveur a levé ; le corps porte le message d'exception |
| `503` | `{"error":"Server not available"}` avant le démarrage du serveur, ou `{"error":"ShopManager unavailable"}` |

La validation passe avant tout le reste dans chaque handler, et chaque mutation passe par
`WebDashboard.executeOnServerThread` : un thread HTTP ne touche donc jamais directement
l'état des boutiques.

---

## WebSocket

Un seul type de message, sur le canal **`market`**. Abonne-toi de la façon habituelle de
CheeseDash :

```json
{ "type": "subscribe", "channels": ["market"] }
```

### `market_purchase`

Diffusé par `ShopGuiHandler` après un achat réussi, via
`DashboardRegistry.broadcast("market", …)` :

```json
{
  "type": "market_purchase",
  "playerName": "Steve",
  "shopId": "general",
  "itemName": "Potion de soin",
  "quantity": 3,
  "timestamp": 1761739391284
}
```

`timestamp` est un `System.currentTimeMillis()`, pas la chaîne ISO que le journal des
transactions utilise. `itemName` est le nom d'affichage effectif de l'item, et il n'y a ni
`setId` ni `itemId` — le message sert un flux en direct, pas un rapprochement comptable.
Utilise `GET /transactions` pour cela.

Le `DiscordWebhookManager` de CheeseDash reconnaît ce type et le mappe à son événement
Discord `MARKET_PURCHASE` : un achat peut donc atteindre un canal Discord sans une ligne
de code dans ce mod.

La diffusion est sautée quand CheeseDash n'est pas chargé, protégée à la fois par
`isModLoaded("cheesedash")` et par un catch de `NoClassDefFoundError`.

**Aucun autre message WebSocket n'est publié par ce mod.** Les éditions de boutiques, de
sets et d'items, les téléversements d'assets, les changements de templates et les remises à
zéro de stock ne sont pas diffusés — une deuxième session de dashboard ne les voit pas tant
qu'elle ne recharge pas.

---

← [Achats](purchases.fr.md) · [Architecture](architecture.fr.md) →
