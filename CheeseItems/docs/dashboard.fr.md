[← CheeseItems](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](dashboard.md)

# API dashboard

CheeseItems déclare **deux** modules CheeseDash dans l'entrypoint `cheesedash` de son
`fabric.mod.json` :

| Classe | Préfixe | Base authentifiée | Base publique | Libellé de navigation |
|---|---|---|---|---|
| `CheeseItemsDashboardModule` | `items` | `/api/v1/items` | `/cheeseitems/api` | CheeseItems 🎁 |
| `MorphoCatalogDashboardModule` | `morphos` | `/api/v1/morphos` | `/morphos/api` | Morphos 🧬 |

Les deux apparaissent dans la barre latérale du dashboard dès que CheeseDash les découvre au
démarrage. CheeseDash sert le HTTP sur le port **44311** et le WebSocket sur **44312**. Les
routes authentifiées exigent une session : `POST /api/v1/auth/login` renvoie un token à
passer en `Authorization: Bearer <token>`.

## <a id="authentification-et-gardes-de-rôle"></a>Authentification et gardes de rôle

Les deux dispatchers, `ItemsApi` et `MorphoCatalogApi`, s'ouvrent sur un garde unique :

```java
if (!method.equals("GET") && !WebDashboard.requireWrite(exchange)) return;
```

**Toute route qui modifie quelque chose exige donc `OPERATOR` ou plus**, et les routes de
lecture acceptent tous les rôles, `READ_ONLY` comprise. Le garde porte sur la méthode
HTTP plutôt que sur chaque branche, et c'est volontaire : une nouvelle route mutante ne
peut pas être ajoutée sans en hériter, là où un garde par branche est à un `return` près
d'être oublié.

Cet appel est aussi de la forme correcte : `requireWrite` renvoie un booléen et répond
lui-même à l'échange quand il refuse, donc son résultat **doit** être exploité ;
l'appeler en jetant sa valeur de retour répond `403` puis exécute le travail quand même.

### Ce que le cœur applique réellement

La même frontière est appliquée une seconde fois, avant même que CheeseItems ne voie la
requête, par le répartiteur de modules de CheeseDash dans `DashboardApi` :

```java
DashboardModule module = DashboardRegistry.getModule(prefix);
if (module != null) {
    if (!method.equals("GET") && !WebDashboard.requireWrite(exchange)) {
        return;
    }
    module.handleRequest(exchange, subRoute);
    ...
}
```

Toute requête non-`GET` vers n'importe quel module — pas seulement celui-ci — y est tenue
à `OPERATOR`. Le garde local du module est donc de la défense en profondeur : utile, et
redondante. Trois conséquences découlent de l'endroit où se trouve la vraie frontière :

- **Aucune route de CheeseItems ne peut exiger `SUPER_ADMIN`.** Le garde central ne connaît
  que `requireWrite` : `OPERATOR` est donc la barre maximale atteignable par son biais. Une
  route qui mériterait `SUPER_ADMIN` devrait appeler `requireAdmin` elle-même ; aucune ne le
  fait.
- **Une route mutante exposée en `GET` ne serait gardée par aucune des deux couches** — le
  garde central ne regarde que la méthode HTTP. Il n'en existe aucune ici aujourd'hui.
- **Une seule modification de ce répartiteur ferait tomber le contrôle central partout d'un
  coup**, sans qu'aucun module s'en aperçoive. Sur ce module, le garde local tiendrait
  encore ; sur les modules qui n'en ont pas, plus rien ne tiendrait.

La colonne « rôle » des tableaux qui suivent indique le rôle réellement exigé dans cette
version.

> ⚠️ Le garde trace la frontière des rôles ; il ne change pas ce qu'un `OPERATOR` peut
> faire. Un opérateur peut toujours écrire un item dont les `useActions` lancent des
> commandes arbitraires en tant que console serveur au niveau de permission 4
> ([pourquoi](items.fr.md#useactions)) — par exemple un item dont l'utilisation lance
> `op <nom>`, donné à un joueur. Sur ce module, `OPERATOR` équivaut à un accès console :
> accorde ce rôle en conséquence.

Les routes publiques, non authentifiées, sont en dehors de tout cela : elles passent par
`handlePublicImage`, `handlePublicThumb` et `handlePublicRequest`, jamais par les
dispatchers `handle` ci-dessus.

Les deux modules répondent `503 {"error":"CheeseItems not ready"}` (ou
`"Server not ready"`) avant tout gestionnaire si la référence au serveur Minecraft est nulle
— pendant le démarrage, ou après `SERVER_STOPPING`.

Tout chemin ou méthode non reconnu retombe sur `404 {"error":"Not found"}`, et toute
exception levée dans un gestionnaire est journalisée et répondue en
`500 {"error":"Internal server error"}`.

## Routes des items

Base : `/api/v1/items`. Rôles : `GET` accepte tous les rôles, `READ_ONLY` comprise ;
toute autre méthode exige `OPERATOR` ou plus (voir ci-dessus).

| Méthode | Chemin | Corps | Succès | Erreurs |
|---|---|---|---|---|
| `GET` | `/` | — | `200` tableau de résumés | — |
| `POST` | `/` | objet de définition | `201` définition complète | `409` identifiant déjà pris |
| `GET` | `/:id` | — | `200` définition complète + `hasCustomIcon` | `400` identifiant invalide · `404` introuvable |
| `PUT` | `/:id` | définition partielle | `200` définition fusionnée | `400` · `404` · `500` échec de mise à jour |
| `DELETE` | `/:id` | — | `200 {"deleted":true}` | `400` · `404` · `500` |
| `POST` | `/:id/give` | `{"playerName":"…","quantity":1}` | `200 {"given":true,"quantity":N}` | `404` item · `400` playerName requis / joueur introuvable |
| `POST` | `/:id/image` | `{"imageBase64":"…"}` | `200 {"uploaded":true}` | `400` · `404` |
| `GET` | `/:id/image` | — | `200` `image/png` | `400` · `404` image introuvable |
| `DELETE` | `/:id/image` | — | `200 {"deleted":true}` | `400` |
| `POST` | `/reload` | — | `200 {"reloaded":true}` | `500` échec du rechargement |
| `GET` | `/enums` | — | `200 {"potionEffects":[…]}` | — |

### Formes

`GET /` renvoie un résumé par item, pas la définition complète — la vue liste du dashboard
n'a pas besoin des tableaux :

```json
[
  {
    "id": "quest_key",
    "displayName": "Rusty Quest Key",
    "nameColor": "#F5A623",
    "baseItem": "minecraft:tripwire_hook",
    "enabled": true,
    "glowing": false,
    "soulbound": false,
    "consumable": false,
    "droppable": true,
    "unbreakable": false,
    "disappearOnDeath": false,
    "maxUses": 0,
    "cooldownTicks": 0,
    "actionCount": 2,
    "effectCount": 1,
    "hasCustomIcon": true
  }
]
```

`GET /:id` renvoie la définition exactement telle qu'elle est écrite sur le disque, plus
`hasCustomIcon`. Chaque champ est documenté dans [items personnalisés](items.fr.md).

`POST /` accepte la même forme. Un `id` absent ou vide est remplacé par
`item_<5 caractères hexadécimaux>`. Tout champ omis prend son défaut de constructeur, donc
poster `{}` crée un papier blanc nommé *Custom Item*.

`PUT /:id` est une **fusion** : seules les clés présentes dans le corps sont touchées.
`endurance` est la seule clé où `null` a un sens — envoyer `"endurance": null` retire la
réserve, tandis qu'omettre la clé la conserve. La fusion et le rafraîchissement des
inventaires tournent sur le thread serveur via `WebDashboard.executeOnServerThread`, parce
qu'ils touchent aux inventaires des joueurs.

`DELETE /:id` supprime aussi l'icône envoyée, libère le numéro `customModelData` de l'item,
enregistre la configuration et régénère le resource pack. Il ne retire **pas** les copies
déjà présentes dans les inventaires ; voir
[le rechargement à chaud](items.fr.md#rechargement-à-chaud-et-items-déjà-dans-les-inventaires).

`POST /:id/give` cible un joueur connecté par son nom. Le `quantity` du corps vaut `1` par
défaut et, contrairement à la commande, **n'est pas borné** — une quantité supérieure à la
taille de pile produit ce que `insertStack` en fait, et une valeur non entière lève une
exception rendue en `500`. Un joueur hors ligne revient en `400`, pas en `404`, parce que
l'échec est levé depuis la tâche exécutée sur le thread serveur.

`POST /:id/image` prend un PNG en base64, avec ou sans préfixe `data:image/png;base64,`.
L'envoi attribue un numéro `customModelData` si l'item n'en a pas encore, reconstruit le
resource pack, le pousse aux joueurs connectés si son SHA-1 a changé, et rafraîchit les
inventaires pour que la nouvelle icône apparaisse sans reconnexion. Il n'y a aucune limite
de taille et aucune vérification que les octets sont bien un PNG.

`GET /enums` ne renvoie que `potionEffects` — 37 identifiants d'effets vanilla. La javadoc
de la route affirme qu'elle renvoie aussi `categories` et `rarities` ; c'est faux, et ces
champs n'existent nulle part dans le mod.

### Route publique des items

`GET /cheeseitems/api/pack.zip` — sans authentification. Sert le resource pack généré en
`application/zip` avec `Cache-Control: no-cache`, ou
`404 {"error":"No resource pack available"}` quand aucun pack n'a été construit. C'est
l'URL que le mod transmet aux clients : elle doit donc être joignable depuis l'extérieur,
voir [le resource pack](resource-pack.fr.md#le-pack-est-servi-en-http).

## Routes du catalogue de morphos

Base : `/api/v1/morphos`. Rôles : `GET` accepte tous les rôles, `READ_ONLY` comprise ;
toute autre méthode exige `OPERATOR` ou plus (voir ci-dessus).

| Méthode | Chemin | Corps | Succès | Erreurs |
|---|---|---|---|---|
| `GET` | `/` | — | `200` tableau d'entrées, trié | — |
| `POST` | `/` | objet d'entrée (peut être vide) | `201` entrée créée | — |
| `GET` | `/:id` | — | `200` entrée | `400` identifiant invalide · `404` |
| `PUT` | `/:id` | entrée partielle | `200` entrée fusionnée | `400` · `404` |
| `DELETE` | `/:id` | — | `200 {"deleted":true}` | `400` · `404` |
| `POST` | `/:id/image` | `{"imageBase64":"…"}` | `200 {"uploaded":true}` | `400` · `404` |
| `GET` | `/:id/image` | — | `200` `image/png`, revalidé | `400` |
| `DELETE` | `/:id/image` | — | `200 {"deleted":true}` | `400` |
| `POST` | `/reorder` | `{"ids":["a","b",…]}` | `200 {"ok":true}` | `400` ids requis |
| `POST` | `/grant` | `{"playerUuid"\|"playerName", "morphoId"}` | `200 {"granted":true}` | `400` · `404` · `409` déjà dans le codex · `500` |
| `GET` | `/global` | — | `200` endurance globale | — |
| `PUT` | `/global` | endurance partielle | `200` endurance mise à jour | — |

Une entrée ressemble à ceci ; chaque champ et son défaut sont documentés dans
[morphos](morphos.fr.md#lentrée-de-catalogue) :

```json
{
  "id": "a3f91c2e",
  "title": "Ditto",
  "description": "It copies anything.",
  "species": "ditto",
  "form": "",
  "shiny": false,
  "hasImage": true,
  "priceCredits": 250,
  "priceGold": 0,
  "availableOnWebsite": true,
  "availableInNpcShop": true,
  "isNew": true,
  "sortOrder": 4,
  "createdAt": 1761700000000,
  "terrestrial": true,
  "aquatic": false,
  "aerial": false,
  "legendary": false,
  "commandsOnTransform": [],
  "commandsOnRevert": [],
  "useCustomEndurance": false,
  "customCapacity": 100,
  "customDrainPerSecond": 2.0,
  "customRegenPerSecond": 1.0
}
```

`POST /` génère un identifiant hexadécimal de 8 caractères, inscrit `createdAt` et place
l'entrée en fin d'ordre de tri. Le corps passe par le même code de fusion que `PUT`, donc un
doublon créé en postant la copie d'une entrée existante conserve ses commandes et ses
réglages d'endurance. `id`, `hasImage` et `createdAt` sont **ignorés** dans le corps : la
fusion ne les lit pas.

`PUT /global` ne modifie que les clés envoyées. Les valeurs sont bornées : `capacity` à 1 au
minimum, les deux débits à 0 au minimum.

`POST /grant` résout le joueur depuis `playerUuid` s'il est présent, sinon par nom parmi les
joueurs connectés, sinon via le cache utilisateurs du serveur — un joueur hors ligne qui
s'est déjà connecté peut donc recevoir une morpho. Un nom qui ne résout nulle part donne un
`404`.

`DELETE /:id` retire l'entrée, supprime son image et sa miniature en cache, puis
resynchronise le codex de chaque joueur connecté afin que l'entrée orpheline disparaisse
immédiatement de son interface. Ce nettoyage est définitif : un joueur qui possédait cette
morpho la perd. Recréer l'entrée sous un nouvel identifiant ne la lui rend pas.

### <a id="routes-publiques-des-morphos"></a>Routes publiques des morphos

Les deux sans authentification, les deux en `GET` uniquement, les deux répondues avec des
en-têtes de revalidation PNG afin qu'un client détenant déjà le fichier obtienne un `304` :

| Chemin | Renvoie |
|---|---|
| `/morphos/api/image/<id>` ou `/morphos/api/image/<id>.png` | le PNG envoyé en pleine résolution |
| `/morphos/api/thumb/<id>` ou `/morphos/api/thumb/<id>.png` | une réduction sans perte à 480 px maximum, générée et mise en cache à la première demande |

L'interface du Codex en jeu interroge `/thumb/`, comme le tableau d'administration ; le site
de la boutique utilise `/image/` pour sa visionneuse. La javadoc de `MorphoCatalogApi`
indique que le chemin public est `/cheeseitems/api/morpho-image/<id>.png` — c'est obsolète
et faux ; le chemin vient de `MorphoCatalogDashboardModule.getPublicPrefix()`, qui renvoie
`morphos`.

## WebSocket

Deux canaux, tous deux publiés avec `DashboardRegistry.broadcast(...)`. Abonne-toi de la
façon habituelle :

```json
{"type":"subscribe","channels":["items","morphos"]}
```

### Canal `items`

Un seul type de message, `item_update` :

| Champ | Présent | Valeur |
|---|---|---|
| `type` | toujours | `"item_update"` |
| `itemId` | toujours | l'identifiant de la définition |
| `itemName` | sur `created` et `updated` seulement | `displayName` |
| `action` | toujours | `"created"`, `"updated"` ou `"deleted"` |

```json
{"type":"item_update","itemId":"quest_key","itemName":"Rusty Quest Key","action":"updated"}
```

Rien n'est diffusé pour `give`, `reload` ni les routes d'image.

### Canal `morphos`

Un seul type de message, `morpho_update` :

| Champ | Présent | Valeur |
|---|---|---|
| `type` | toujours | `"morpho_update"` |
| `morphoId` | sauf sur `reordered` | l'identifiant de l'entrée |
| `action` | toujours | `"created"`, `"updated"`, `"deleted"`, `"image_updated"`, `"image_deleted"` ou `"reordered"` |

`reordered` ne porte aucun identifiant, puisque c'est toute la liste qui a bougé. Rien n'est
diffusé pour `grant` ni pour les routes `/global` : un second onglet du dashboard ne verra
donc pas une modification d'endurance avant son rechargement.

## Ce que les vues du dashboard permettent

La vue `items` liste les définitions avec leurs options, ouvre un éditeur pour chaque champ
décrit dans [items personnalisés](items.fr.md), envoie et recadre une icône, et propose un
bouton de don qui demande un nom de joueur. La vue `morphos` liste les entrées du catalogue
avec leurs miniatures, les modifie, les réordonne par glisser-déposer (ce qui appelle
`/reorder`), envoie des images, modifie les valeurs d'endurance globales et attribue une
entrée à un joueur.

Les deux vues font partie du frontend de CheeseDash, pas de ce mod — le mod ne sert que du
JSON.

---

← [Morphos](morphos.fr.md) · [Architecture](architecture.fr.md) →
