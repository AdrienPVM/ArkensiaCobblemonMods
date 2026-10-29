[← CheeseStore](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](dashboard.md)

# API dashboard

CheeseStore enregistre un module CheeseDash sous le préfixe **`store`** par
l'entrypoint `cheesedash` de `fabric.mod.json`. `CheeseStoreDashboardModule` déclare
deux surfaces, et la distinction entre elles compte ici plus que dans n'importe quel
autre mod du dépôt :

| Surface | Servie à | Qui l'atteint | Gestionnaire |
|---|---|---|---|
| **Admin** | `http://<hôte>:44311/api/v1/store/*` | une session CheeseDash authentifiée | `StoreApi.handle` |
| **Publique** | `http://<hôte>:44311/store/api/*` | n'importe qui sur Internet | `StoreApi.handlePublic` |

La surface publique existe parce qu'elle doit exister : le navigateur d'un acheteur et
les serveurs de webhook de Stripe doivent tous les deux l'atteindre, et ni l'un ni les
autres ne peuvent détenir un token de session CheeseDash. Elle est donc non
authentifiée **par conception**, et elle se défend autrement — limitation de débit par
IP, vérification de même origine sur tout ce qui dépense de l'argent, une session OAuth
plus un compte Minecraft lié sur chaque débit de crédits, et une signature HMAC sur le
webhook. Ces gardes sont décrits route par route plus bas, et le parcours de paiement
est retracé dans [le flux de paiement](payments.fr.md).

Les deux surfaces partagent l'unique canal WebSocket du mod, `store`, sur le port
44312.

> ⚠️ **La frontière des rôles de la surface d'administration est tenue par CheeseDash,
> pas par CheeseStore.** Elle tient bien — les écritures exigent `OPERATOR` — mais lis
> [ce que le cœur applique réellement](#ce-que-le-cœur-applique-réellement) pour savoir
> ce que cela te garantit et ce que cela ne te garantit pas. La surface publique, elle,
> n'a aucune frontière de rôle, par conception ; une de ses routes en divulgue plus que
> tu ne l'attends peut-être — voir [`GET /players`](#catalogue-en-lecture-seule).

---

## Authentification

Les routes admin héritent de l'authentification par session de CheeseDash :
`POST /api/v1/auth/login` avec le token maître renvoie un token de session, que chaque
requête suivante envoie en `Authorization: Bearer <token>`. CheeseDash rejette une
requête non authentifiée avant que `StoreApi.handle` ne soit jamais appelé.

Les routes publiques n'envoient aucun en-tête Authorization. Les trois routes qui
dépensent des crédits utilisent un mécanisme entièrement différent : un cookie de
session émis par le flux de liaison de compte du site, résolu par
`GoogleOAuth.currentSession`, puis rapproché d'un UUID Minecraft par
`AccountLinker`. Pas de cookie → `401 not_authenticated`. Cookie mais aucun
`/store link` achevé → `403 not_linked`.

---

## Ce que le cœur applique réellement

CheeseDash donne à un module trois aides pour protéger une route par rôle :
`WebDashboard.requireWrite(exchange)` pour OPERATOR ou plus,
`WebDashboard.requireAdmin(exchange)` pour SUPER_ADMIN, et `getSession(exchange)` pour
inspecter l'appelant. La forme correcte est une ligne en tête du gestionnaire :

```java
if (!WebDashboard.requireWrite(exchange)) return;
```

**`StoreApi.handle` n'en appelle aucune.** Pas une seule fois, nulle part dans le
fichier — les chaînes `requireWrite`, `requireAdmin` et `getSession` n'apparaissent pas
du tout dans les sources de CheeseStore. Ce qui tient lieu de ces contrôles, c'est le
répartiteur de modules de CheeseDash, dans `DashboardApi` :

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

C'est la forme correcte — elle teste la valeur de retour et rend la main — donc dans
cette version, toute requête non-`GET` sous `/api/v1/store/` exige bien `OPERATOR` ou
au-dessus, et les requêtes `GET` acceptent tous les rôles, `READ_ONLY` compris. Une
session `READ_ONLY` ne peut pas changer le prix d'un produit, fixer un solde de
portefeuille, relivrer une commande d'achat ni écraser les clés Stripe. La frontière
tient.

Ce qui ne tient pas, c'est la raison pour laquelle elle tient. La protection est une
propriété du répartiteur du cœur, fondée uniquement sur la méthode HTTP, et CheeseStore
n'y contribue en rien. Trois conséquences :

- **Aucune route de CheeseStore ne peut exiger `SUPER_ADMIN`.** `PUT /config`, qui écrit
  la clé secrète Stripe et le secret du webhook, est une opération de niveau `OPERATOR`,
  parce qu'`OPERATOR` est la seule barre que le répartiteur connaisse. C'est le point le
  plus tranchant : écrire un nouveau `stripeWebhookSecret` invalide immédiatement les
  signatures du vrai endpoint Stripe, ce qui arrête silencieusement toutes les
  livraisons, et écrire une nouvelle `stripeSecretKey` pointe le checkout vers un autre
  compte Stripe. Fixer des soldes de portefeuille arbitraires (`PUT /wallets/:uuid`) se
  situe au même niveau.
- **Un `GET` qui modifierait quelque chose serait totalement sans garde.** Il n'en existe
  aucun aujourd'hui dans `StoreApi.handle`, mais rien dans le module n'empêcherait d'en
  ajouter un, et rien ne le signalerait.
- **Une modification du répartiteur supprimerait tous les contrôles d'un coup.** La
  surface d'administration de CheeseStore n'a pas de seconde ligne de défense.

Un `OPERATOR` sur ce serveur est donc un exploitant de boutique à part entière — c'est
la conception voulue, pas un accident, mais cela vaut d'être dit clairement avant de
distribuer le rôle. S'il te faut un observateur réellement en lecture seule,
`READ_ONLY` est le rôle à donner, et il se comporte conformément à son nom.

Les routes publiques sont en dehors de tout cela. Elles sont non authentifiées par
conception et ne se sont jamais reposées sur des contrôles de rôle ; elles ont leurs
propres gardes — limitation de débit, même origine, OAuth plus un compte lié, HMAC — et
ces gardes sont bien là. L'une d'elles, `GET /players`, renvoie à qui la demande la
liste complète des pseudos et UUID ; voir
[les routes de catalogue](#catalogue-en-lecture-seule).

---

## Routes d'administration

Tous les chemins ci-dessous sont relatifs à `/api/v1/store`. Le rôle indiqué est le
rôle réellement exigé dans cette version : tout `GET` accepte tous les rôles,
`READ_ONLY` compris, et tout non-`GET` exige `OPERATOR` ou au-dessus. La marque
`(cœur)` rappelle que la garantie vient du répartiteur de CheeseDash et non de
CheeseStore — voir
[ce que le cœur applique réellement](#ce-que-le-cœur-applique-réellement). La règle est
uniforme sur tous les tableaux de cette page, donc seul le premier porte la colonne.

### Produits

| Méthode | Chemin | Rôle | Corps | Réponse |
|---|---|---|---|---|
| `GET` | `/products` | tous les rôles | — | `200` tableau de produits complets |
| `POST` | `/products` | `OPERATOR` (cœur) | objet produit, `imageBase64` facultatif | `201` le produit créé |
| `PUT` | `/products/:id` | `OPERATOR` (cœur) | produit partiel, `imageBase64` facultatif | `200` le produit fusionné |
| `DELETE` | `/products/:id` | `OPERATOR` (cœur) | — | `200 {"deleted":true}` |
| `POST` | `/products/:id/image` | `OPERATOR` (cœur) | `{"imageBase64":"..."}` | `200 {"ok":true}` |
| `GET` | `/products/:id/image` | tous les rôles | — | `200 image/png`, ou `404` |

Un objet produit, tel que le renvoie `StoreProduct.toJson()` :

```json
{
  "id": "a3f91c02",
  "name": "Starter pack",
  "description": "500 crédits et un rang",
  "priceCents": 499,
  "credits": 500,
  "currency": "eur",
  "category": "pack",
  "enabled": true,
  "featured": false,
  "sortOrder": 0,
  "hasImage": true,
  "commands": ["premium give {player} 500", "lp user {player} parent add vip"]
}
```

`POST` accepte la même forme. Omettre `id` fait générer le sien au constructeur de
`StoreProduct` : les 8 premiers caractères d'un UUID aléatoire. Omettre `enabled` le
fixe à `true`, donc un produit créé sans ce champ est immédiatement en vente.

`PUT` est une **fusion**, par `StoreProduct.mergeFrom` — les champs absents du corps
conservent leur valeur actuelle. `id` n'est volontairement pas fusionnable : on ne peut
pas renommer l'identifiant d'un produit, seulement le supprimer et le recréer. Note que
`hasImage` n'est pas fusionnable non plus, sauf comme effet de bord d'un envoi d'image.

`imageBase64` accepte soit un payload base64 nu, soit une URI `data:` complète ; le
préfixe jusqu'à la première virgule est retiré. Les octets sont écrits dans
`config/cheesestore_assets/<id>.png` **sans aucune vérification qu'il s'agit d'un PNG**
et sans limite de taille. Supprimer le produit supprime le fichier.

| Statut | Quand |
|---|---|
| `400 {"error":"Invalid ID"}` | l'identifiant contient `..`, `/`, `\`, `%`, ou fait 128 caractères ou plus |
| `404 {"error":"Product not found"}` | aucun produit avec cet identifiant |
| `500 {"error":"Internal server error"}` | n'importe quelle exception, y compris un JSON malformé et un base64 invalide |

Chaque création et chaque mise à jour réussies diffusent `store_update` sur le canal
`store`.

### Galerie

Une vitrine de récompenses en lecture seule : les entrées apparaissent sur la boutique
mais ne peuvent pas être achetées. Mêmes formes que les produits, gérées par
`GalleryItem`.

| Méthode | Chemin | Corps | Réponse |
|---|---|---|---|
| `GET` | `/gallery` | — | `200` tableau, trié `isNew` d'abord puis par `sortOrder` |
| `POST` | `/gallery` | entrée de galerie, `imageBase64` facultatif | `201` l'entrée créée |
| `POST` | `/gallery/reorder` | `{"ids":["a","b",...]}` | `200 {"ok":true}` |
| `PUT` | `/gallery/:id` | entrée partielle | `200` l'entrée fusionnée |
| `DELETE` | `/gallery/:id` | — | `200 {"deleted":true}` |
| `POST` | `/gallery/:id/image` | `{"imageBase64":"..."}` | `200 {"ok":true}` |
| `GET` | `/gallery/:id/image` | — | `200 image/png` |

`POST /gallery` attribue `sortOrder = max(existant) + 1`, donc une nouvelle entrée
arrive à la fin. `/gallery/reorder` réécrit `sortOrder` depuis l'indice dans le tableau,
en ignorant les identifiants qu'il ne trouve pas plutôt qu'en levant une erreur —
transmets la liste complète, pas une liste partielle. `ids` manquant →
`400 {"error":"Missing ids"}`.

Les images de galerie vivent sous
`config/cheesestore_assets/gallery/<id>.png`.

### Commandes d'achat

| Méthode | Chemin | Corps | Réponse |
|---|---|---|---|
| `GET` | `/orders` | — | `200` tableau de livraisons, **les plus récentes d'abord** |
| `POST` | `/orders/:id/deliver` | — | `200 {"delivered":true}` ou `400` |

#### `POST /orders/:id/deliver`

Réexécute les commandes stockées d'une livraison sur le thread serveur, via
`WebDashboard.executeOnServerThread`. Le `:id` est l'identifiant de session Stripe
Checkout.

```text
400 {"error":"Delivery failed or already delivered"}
```

Ce seul message couvre trois situations différentes, ce qui est gênant quand on
débogue : l'identifiant de commande n'existe pas, la commande est déjà `DELIVERED`, ou
une commande a levé une exception. Consulte le log serveur pour les distinguer — une
exception est journalisée par `DeliveryManager` et renseigne le `failReason` de la
commande, que `GET /orders` affiche ensuite.

Un `500` avec le message d'exception signifie que le passage au thread serveur
lui-même a échoué, et non les commandes.

Un objet commande d'achat :

```json
{
  "orderId": "cs_live_a1...",
  "playerUUID": "...", "playerName": "Steve",
  "productId": "a3f91c02", "productName": "Starter pack",
  "priceCents": 499,
  "commands": ["premium give Steve 500"],
  "status": "PENDING",
  "createdAt": 1730000000000,
  "deliveredAt": 0,
  "failReason": null
}
```

`status` vaut `PENDING`, `DELIVERED` ou `FAILED`. Les `commands` sont déjà résolues —
`{player}` a été remplacé à la création de la commande d'achat, donc une relivraison
exécute les commandes telles qu'elles étaient tarifées, et non telles que le produit se
lit aujourd'hui.

### Portefeuilles

| Méthode | Chemin | Corps | Réponse |
|---|---|---|---|
| `GET` | `/wallets` | — | `200` tableau de `{uuid, playerName, balance, lastUpdated}` |
| `GET` | `/wallets/:uuid` | — | `200` un portefeuille, ou `404` |
| `PUT` | `/wallets/:uuid` | `{"balance":1000,"playerName":"Steve"}` | `200` le nouvel état |

`PUT` **fixe** un solde absolu, il n'ajoute pas. Un champ `balance` manquant est lu
comme `0`, donc un corps vide efface le portefeuille — il n'y a aucun garde du genre
« voulais-tu vraiment ». Les négatifs sont contraints à zéro par
`WalletManager.setBalance`.

`playerName` est facultatif : omis ou vide, le nom du portefeuille existant est
conservé, et pour un portefeuille qui n'existe pas encore, les 8 premiers caractères de
l'UUID servent de nom bouchon. Transmets le vrai pseudo quand tu crées un portefeuille
de zéro.

Si le solde a changé et que le joueur est connecté, il reçoit un message sur le thread
serveur, via `cheesestore.notify.received` ou `cheesestore.notify.deducted` — les mêmes
chaînes qu'utilise `/premium give`, donc un ajustement depuis le dashboard est
indiscernable dans le chat d'un ajustement par commande.

| Statut | Quand |
|---|---|
| `400 {"error":"Invalid UUID"}` | échoue le contrôle d'identifiant générique |
| `400 {"error":"Invalid UUID format"}` | non analysable par `UUID.fromString` |
| `404 {"error":"Wallet not found"}` | `GET` sur un UUID sans portefeuille. `PUT` en crée un à la place |

### Statistiques

`GET /stats` → `200` :

```json
{
  "totalProducts": 12, "enabledProducts": 9,
  "totalOrders": 340, "pendingOrders": 2,
  "deliveredOrders": 337, "failedOrders": 1,
  "totalRevenueCents": 128400,
  "totalFromage": 54210, "walletCount": 188
}
```

`totalRevenueCents` ne somme que les commandes `DELIVERED`, donc c'est le chiffre
d'affaires que tu as effectivement honoré, et non celui que Stripe a encaissé. Il ne
soustrait pas les remboursements — rien ici n'en a connaissance. `failedOrders` est le
nombre à surveiller ; voir
[l'échec qui coûte de l'argent](payments.fr.md#léchec-qui-te-coûte-réellement-de-largent).

### Configuration

| Méthode | Chemin | Corps | Réponse |
|---|---|---|---|
| `GET` | `/config` | — | `200` configuration avec les secrets masqués |
| `PUT` | `/config` | n'importe quel sous-ensemble des clés modifiables | `200 {"ok":true}` |

`GET` renvoie `stripePublishableKey` en entier — elle est faite pour être publique — et
masque `stripeSecretKey` et `stripeWebhookSecret` en
`<8 premiers>***<4 derniers>`, ou carrément `***` pour toute valeur de moins de 12
caractères. Les clés Google OAuth ne sont **pas** exposées par cette route, dans aucun
sens : `GET /config` les omet et `PUT /config` les ignore. Elles ne sont modifiables
qu'à la main dans le fichier.

`PUT` accepte `stripePublishableKey`, `stripeSecretKey`, `stripeWebhookSecret`,
`storeName`, `storeSubtitle`, `storeBaseUrl`, `storeEnabled` et `currencyName`. Un
secret dont la valeur contient `***` est ignoré au lieu d'être sauvegardé, ce qui est ce
qui permet au dashboard de renvoyer la forme masquée qu'il a reçue sans détruire la
vraie clé. L'implication vaut d'être connue : une vraie clé contenant `***` ne peut pas
être posée par cette route.

Après sauvegarde, `refreshStripeClient()` reconstruit le `StripeClient` — une nouvelle
clé secrète prend effet au checkout suivant, sans redémarrage. Vider la clé secrète met
le client à `null` et le checkout commence à répondre `503`.

### Cosmétiques historiques (`CosmeticItem`)

| Méthode | Chemin | Corps | Réponse |
|---|---|---|---|
| `GET` | `/cosmetics` | — | `200` tableau |
| `POST` | `/cosmetics` | objet cosmétique | `200` l'objet inséré ou mis à jour (**pas** `201`) |
| `PUT` | `/cosmetics/:id` | partiel | `200` fusionné |
| `DELETE` | `/cosmetics/:id` | — | `200 {"deleted":true}` |
| `POST` | `/cosmetics/:id/image` | `{"imageBase64":"..."}` | `200 {"ok":true}` |
| `GET` | `/cosmetics/:id/image` | — | `200 image/png` |

`POST` est un *upsert* indexé sur `id` : poster un identifiant existant l'écrase en
silence au lieu de refuser. Ce sont les entrées achetables depuis le site, tarifées en
crédits, dont l'effet est une liste d'`applyCommands`. C'est un catalogue différent de
celui des cosmétiques jouables ci-dessous —
[pourquoi il y en a deux](cosmetics.fr.md#deux-catalogues-qui-portent-le-même-nom).

### Cosmétiques jouables (`CosmeticEntry`)

Servis sous `/playcosmetics` précisément pour éviter la collision avec les routes
`/cosmetics` ci-dessus.

| Méthode | Chemin | Corps | Réponse |
|---|---|---|---|
| `GET` | `/playcosmetics` | — | `200 {"cosmetics":[...]}` |
| `POST` | `/playcosmetics` | entrée avec `id` et `category` | `201` l'entrée |
| `PUT` | `/playcosmetics/:id` | partiel | `200` fusionné |
| `DELETE` | `/playcosmetics/:id` | — | `200 {"deleted":true}` |
| `POST` | `/playcosmetics/reorder` | `{"ids":[...]}` | `200 {"ok":true}` |
| `POST` | `/playcosmetics/grant` | `{"uuid":"..."}` ou `{"player":"Nom"}` plus `"id"` | `200 {ok, uuid, id}` |
| `GET` | `/playcosmetics/enums` | — | `200` listes sources pour les menus déroulants |

C'est le seul groupe admin qui renvoie sa liste enveloppée dans un objet
(`{"cosmetics":[...]}`) plutôt qu'en tableau nu. `POST` valide trois choses d'entrée :
`id` présent, `id` passant le contrôle de sûreté de chemin, et `category` valide contre
`CosmeticEntry.Category` — chaque échec étant un `400` distinct. Comme la route
historique, il remplace une entrée de même identifiant au lieu de refuser.

`/playcosmetics/grant` résout sa cible UUID d'abord : un `uuid` explicite gagne, puis un
nom dans `player` est rapproché des joueurs connectés, puis de `usercache.json` sans
tenir compte de la casse. Rien de résoluble → `400` nommant les deux formes acceptées.
Identifiant de cosmétique inconnu → `404`.

`/playcosmetics/enums` renvoie des listes d'identifiants vanilla sélectionnées pour
qu'un administrateur n'ait jamais à taper un identifiant Minecraft brut :
`categories` (8), `particles` (~42), `effects` (6 types), `mobs` (~29, familiers et
montures chevauchables), `colors` (les 16 couleurs de chat), `iconItems` (~65),
`shapes` (`trail`, `halo`, `spiral`, `orbit`) et `sounds` (~15). Elles sont codées en
dur dans `buildEnumsJson()`, et non lues depuis les registres — une particule ou une
créature moddée n'apparaîtra pas, et il faut éditer le Java pour en ajouter une.

Si les gestionnaires de cosmétiques ne sont pas initialisés, chaque route de ce groupe
répond `503 {"error":"Cosmetic system not ready"}`.

---

## Routes publiques

Tous les chemins ci-dessous sont relatifs à `/store/api`. Aucun en-tête Authorization.
La page de boutique elle-même est servie depuis `/store/`, extraite du
`resources/store/` du jar.

### Catalogue, en lecture seule

| Méthode | Chemin | Gardes | Réponse |
|---|---|---|---|
| `GET` | `/products` | `storeEnabled` | produits activés, `toPublicJson()` |
| `GET` | `/products/:id/image` | validation d'identifiant | `image/png` avec en-têtes de revalidation |
| `GET` | `/gallery` | `storeEnabled` | galerie triée, forme publique |
| `GET` | `/gallery/:id/image` | validation d'identifiant | `image/png` |
| `GET` | `/cosmetics` | `storeEnabled` | cosmétiques historiques activés |
| `GET` | `/cosmetics/image?id=...` | validation d'identifiant | `image/png` |
| `GET` | `/cosmetics/:id/image` | validation d'identifiant | la même image, forme en chemin |
| `GET` | `/playcosmetics` | `storeEnabled` | entrées jouables activées |
| `GET` | `/morpho/list` | `storeEnabled` | entrées du catalogue CheeseItems marquées pour le site |
| `GET` | `/morpho/list/:id/image` | validation d'identifiant | PNG pleine résolution |
| `GET` | `/morpho/list/:id/thumb` | validation d'identifiant | une vignette ≤ 480 px, générée et mise en cache à la première requête |
| `GET` | `/config` | — | `storeName`, `storeSubtitle`, `stripePublishableKey`, `storeEnabled`, `currencyName`, `oauthEnabled` |
| `GET` | `/players` | — | **tous** les pseudos et UUID connus |

`storeEnabled: false` fait répondre `503 {"error":"Store is disabled"}` aux routes
protégées. C'est l'interrupteur à utiliser pendant que tu règles les prix.

Deux de ces routes méritent un second regard. `GET /config` publie la clé publiable, ce
qui est correct — cette clé est conçue pour les navigateurs — mais vérifie que tu n'as
pas collé une clé secrète dans ce champ, parce que cette route la remettrait à
quiconque. `GET /players` lit `usercache.json` et la liste des connectés et renvoie le
tout, sans authentification et sans pagination : le registre complet des pseudos et
UUID de tous ceux qui ont un jour rejoint. La boutique en a besoin pour remplir son
sélecteur de joueur. Si tu considères ce registre comme privé, cette route est la raison
de garder le port derrière un proxy capable de la restreindre.

### Checkout et paiement

| Méthode | Chemin | Gardes | Réponse |
|---|---|---|---|
| `POST` | `/checkout` | même origine, **8 par 60 s par IP**, Stripe configuré, `storeBaseUrl` renseignée | `{"checkoutUrl":"..."}` |
| `GET` | `/session-status?session_id=...` | aucun | `{"status":"complete"\|...}` |
| `POST` | `/webhook` | secret de webhook renseigné, signature HMAC-SHA256, fenêtre d'horodatage de 300 s | `{"received":true}` |

Séquence complète, échec par échec, dans
[le flux de paiement](payments.fr.md#la-séquence).

Note que `/session-status` ne porte **aucune** limitation de débit et aucune
vérification d'origine. C'est une lecture de l'état de Stripe suivie d'une livraison
idempotente, donc le pire qu'un flot de requêtes obtienne est un appel sortant vers
l'API Stripe par requête — ce qui compte quand même contre tes quotas Stripe et mérite
une limitation au niveau du proxy si ta boutique est fréquentée.

### Les achats en crédits

| Méthode | Chemin | Gardes | Dépense |
|---|---|---|---|
| `POST` | `/cosmetics/buy` | même origine, **10 par 60 s par IP**, session OAuth, compte lié | crédits → `applyCommands` |
| `POST` | `/morpho/buy` | idem, **10 par 60 s** dans le même seau | crédits → entrée de codex CheeseItems |
| `POST` | `/playcosmetics/:id/buy` | idem, même seau | crédits → octroi de possession |

Les trois partagent un seul seau de limitation de débit, sous la clé `buy:<ip>` — dix
achats par minute toutes catégories confondues, et non dix chacune.

Chacune exécute les cinq mêmes étapes : vérification de même origine, limitation de
débit, session OAuth, compte lié, recherche au catalogue. Puis le solde est vérifié sur
le thread HTTP et vérifié **de nouveau** à l'intérieur de `executeOnServerThread`, parce
que deux requêtes simultanées passeraient toutes les deux la première vérification.
Cette seconde vérification est ce qui empêche un double débit ; les commentaires des
sources appellent cela la fenêtre TOCTOU et ils ont raison.

| Statut | Erreur | Signification |
|---|---|---|
| `403` | `forbidden_origin` | `Origin`/`Referer` ne correspondait pas à `Host` |
| `429` | `too_many_requests` | le seau par IP est plein |
| `401` | `not_authenticated` | aucun cookie de session OAuth |
| `403` | `not_linked` | identifié sur le web, mais aucun `/store link` achevé |
| `400` | `cosmeticId required` / `morphoId required` | identifiant manquant ou dangereux pour un chemin |
| `404` | `cosmetic_not_found` / `morpho_not_found` | inconnu, ou désactivé, ou non marqué pour le site |
| `409` | `already_owned` | morphos et cosmétiques jouables uniquement ; **aucun débit n'a eu lieu** |
| `402` | `insufficient_credits` | le corps contient `currentBalance` et `required` |
| `503` | `server_not_ready` | le serveur Minecraft n'est pas démarré ; rien n'a été débité |
| `500` | `buy_failed` | le travail sur le thread serveur a levé une exception pour une autre raison |

`/cosmetics/buy` n'a **aucun** garde `already_owned` : les cosmétiques historiques sont
des achats répétables, et en acheter un deux fois débite deux fois et exécute
`applyCommands` deux fois. Si un cosmétique doit être unique par joueur, il faut
l'imposer dans ses commandes.

Le succès est `200 {"success":true,"newBalance":N,"<type>Id":"..."}`, et chaque type
diffuse en plus son propre événement d'achat vers le dashboard.

### Sessions web et liaison de compte

Les chemins sous `/oauth/*` vont à `GoogleOAuth.dispatch`, qui malgré le nom de la
classe ne parle plus du tout à Google.

| Chemin | Effet |
|---|---|
| `/oauth/me` | émet ou lit le cookie `store_session` ; renvoie `sessionId`, puis `linkedUuid`, `playerName` et `credits` une fois la liaison faite |
| `/oauth/link-code` | émet un code d'appariement, valable 5 minutes. **12 par 60 s par IP** |
| `/oauth/logout` | supprime la liaison de cette session et efface le cookie |
| `/oauth/start`, `/oauth/callback` | historiques. `302` vers `/store/`, rien d'autre |

`/oauth/link-code` répond `{"alreadyLinked":true,"playerName":"..."}` au lieu d'un
nouveau code quand la session est déjà liée. Le mécanisme, et ce qu'il prouve ou non sur
la personne qui dépense des crédits, est dans
[architecture](architecture.fr.md#la-liaison-de-compte).

---

## WebSocket

Un seul canal, `store`, sur le port 44312. Abonne-toi avec
`{"type":"subscribe","channels":["store"]}` après la poignée de main habituelle
`{"type":"auth","token":"..."}`.

| Type | Déclenché par | Payload |
|---|---|---|
| `store_update` | création / mise à jour de produit | `action`, `product` (objet complet) |
| `store_update` | suppression de produit | `action: "deleted"`, `productId` |
| `store_gallery_update` | galerie : création / mise à jour / réordonnancement / suppression | `action`, `item` ou `itemId` ; `reordered` n'envoie ni l'un ni l'autre |
| `store_cosmetic_update` | cosmétique historique : upsert / mise à jour / suppression | `action`, `cosmetic` ou `cosmeticId` |
| `store_playcosmetic_update` | cosmétique jouable : création / mise à jour / réordonnancement / suppression | `action`, `cosmetic` ou `cosmeticId` |
| `store_purchase` | un paiement Stripe est devenu une commande d'achat | `playerName`, `productName`, `priceCents` |
| `store_cosmetic_purchase` | `/cosmetics/buy` a réussi | `playerName`, `cosmeticName`, `priceCredits` |
| `store_playcosmetic_purchase` | `/playcosmetics/:id/buy` a réussi | `playerName`, `cosmeticName`, `priceCredits` |

Deux manques à connaître. `store_purchase` ne part que si `createDelivery` a renvoyé
`true`, donc le webhook qui arrive en second pour une commande que le chemin de
vérification avait déjà créée est silencieux — comportement correct, mais cela veut dire
que le flux est une suite de commandes d'achat distinctes, et non d'événements Stripe.
Et **rien ne diffuse en cas d'échec de livraison** : une commande `FAILED` apparaît dans
`GET /orders` et dans le log, mais aucun message WebSocket ne l'annonce. Un dashboard
qui ne regarde que ce canal ne la verra pas.

Les changements de portefeuille ne diffusent rien non plus, par quelque chemin que ce
soit.

---

← [Cosmétiques et portefeuilles](cosmetics.fr.md) · [Architecture](architecture.fr.md) →
