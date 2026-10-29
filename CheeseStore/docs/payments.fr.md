[← CheeseStore](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](payments.md)

# Le flux de paiement, de bout en bout

C'est la page à lire avant d'accepter le moindre euro. Elle suit un achat depuis
l'instant où un acheteur clique sur une carte produit jusqu'à l'instant où une commande
s'exécute sur le serveur, et elle dit ce qui se passe quand chaque étape échoue.
L'étape qui compte le plus est la dernière : un paiement peut réussir chez Stripe et
échouer à livrer dans Minecraft, et la conception du mod part du principe que cela
arrivera parfois.

> ⚠️ Rien sur cette page n'est une déclaration de conformité. Le mod dialogue
> correctement avec l'API Stripe, pour autant que le code le montre, mais ni le code
> ni cette documentation ne garantissent qu'une boutique que tu exploites satisfait tes
> obligations en matière de droit de la consommation, de fiscalité ou de comptabilité.
> Lis aussi [SECURITY.md](../../SECURITY.md).

---

## Les objets en jeu

| Objet | Classe | Vit dans |
|---|---|---|
| Produit | `StoreProduct` | `config/cheesestore.json`, sous `products` |
| Session Stripe Checkout | — (un objet Stripe) | côté Stripe ; son `id` est l'identifiant de commande |
| Commande d'achat / livraison | `PendingDelivery` | `config/cheesestore_deliveries.json` |
| Portefeuille | `WalletManager.WalletEntry` | `config/cheesestore_wallets.json` |

Un `StoreProduct` porte un prix en centimes (`priceCents`), un code de devise
(`currency`, par défaut `"eur"`) et une liste de `commands`. Les commandes sont ce que
l'acheteur reçoit réellement : chacune voit `{player}` remplacé par le pseudo
Minecraft de l'acheteur, puis est exécutée depuis la console serveur. Le champ
`credits` est un chiffre de badge affiché sur la carte de la boutique ; il **ne crédite
aucun portefeuille** par lui-même. Si tu vends de la monnaie, les `commands` du produit
doivent contenir la commande qui l'octroie.

Un `PendingDelivery` a trois états, déclarés dans `PendingDelivery.Status` :

| Statut | Signification |
|---|---|
| `PENDING` | payé, commandes résolues, pas encore exécutées |
| `DELIVERED` | toutes les commandes se sont exécutées sans lever d'exception |
| `FAILED` | une commande a levé une exception ; `failReason` contient son message |

---

## La séquence

### 1. L'acheteur choisit un produit

Le JavaScript de la boutique appelle `GET /store/api/products`. Cette route ne renvoie
que les produits activés, triés par `sortOrder`, via `StoreProduct.toPublicJson()` —
qui omet `commands` et `enabled`, de sorte que le catalogue public ne révèle jamais
quelle commande un achat déclenche.

**Si la boutique est coupée** (`storeEnabled: false`), cette route répond `503` avec
`{"error":"Store is disabled"}` et la page n'a rien à afficher.

### 2. Le navigateur demande une session de checkout

```text
POST /store/api/checkout
{ "productId": "...", "playerUUID": "...", "playerName": "..." }
```

Cinq verrous s'exécutent avant tout contact avec Stripe, dans cet ordre, dans
`StoreApi.handleCheckout` :

1. **Vérification de même origine** (`checkSameOriginPost`). L'en-tête `Origin` de la
   requête doit correspondre à `Host`, ou à défaut son `Referer`. Une requête sans
   aucun des deux en-têtes passe, parce que certains navigateurs très stricts sur la
   vie privée les retirent sur un POST de même origine. Refus →
   `403 {"error":"forbidden_origin"}`.
2. **Limitation de débit par IP** : 8 tentatives de checkout par 60 secondes, sous la
   clé `checkout:<ip>`. Au-delà → `429 {"error":"too_many_requests"}`.
3. **Stripe configuré ?** Si `stripeSecretKey` est vide, `CheeseStoreMod` n'a jamais
   construit de `StripeClient` et la route répond
   `503 {"error":"Stripe not configured"}`.
4. **Corps complet ?** `productId`, `playerUUID` ou `playerName` manquant → `400`.
   Produit inconnu ou désactivé → `404 {"error":"Product not found or disabled"}`.
5. **`storeBaseUrl` cohérente ?** Vide, ou contenant `localhost`, bloque le checkout
   avec un `503` qui nomme la clé. Le mod journalise aussi cette condition en
   avertissement au démarrage, pour qu'une boutique mal configurée se voie avant que
   quiconque essaie d'acheter.

Alors seulement `StripeClient.createCheckoutSession` fait un POST vers
`https://api.stripe.com/v1/checkout/sessions`, avec un corps en form-encoded,
authentifié par la clé secrète en HTTP Basic (`<clé>:` encodé en base64). Il n'y a
aucun SDK Stripe ; le client tient en quelques dizaines de lignes sur le `HttpClient`
du JDK.

La session est créée en `mode=payment` avec une seule ligne de commande construite en
`price_data` depuis le nom, la devise et le `priceCents` du produit. Trois champs de
métadonnées voyagent avec elle :

```text
metadata[player_uuid]   metadata[player_name]   metadata[product_id]
```

Ces trois champs sont le seul lien entre un paiement Stripe et un compte Minecraft. Le
webhook les relit ; rien d'autre n'identifie l'acheteur.

`success_url` vaut `<storeBaseUrl>/store/?session_id={CHECKOUT_SESSION_ID}` et
`cancel_url` la même page sans le paramètre. Stripe héberge lui-même la page de
paiement, et c'est pourquoi le serveur HTTP du mod n'a pas besoin de TLS pour le
formulaire de carte — mais vois
[l'avertissement sur HTTPS](#ce-dont-ce-flux-ne-te-protège-pas).

Une erreur Stripe (HTTP ≥ 400) revient en `502` avec le message de Stripe lui-même. Un
échec réseau est attrapé et renvoyé comme objet d'erreur aussi. Dans les deux cas, rien
n'a été créé d'aucun côté.

### 3. L'acheteur paie sur la page de Stripe

La réponse à l'étape 2 est `{"checkoutUrl":"https://checkout.stripe.com/..."}` et le
navigateur y est envoyé. À partir de là et jusqu'à la redirection de retour, le mod
n'intervient pas. Les données de carte ne touchent jamais ton serveur.

### 4. Le retour, et l'appel de vérification

Stripe ramène l'acheteur sur `success_url` avec `session_id` dans la chaîne de requête.
La boutique appelle alors :

```text
GET /store/api/session-status?session_id=cs_live_...
```

`handleSessionStatus` récupère la session directement chez Stripe
(`GET /v1/checkout/sessions/<id>`) au lieu de faire confiance à ce que dit le
navigateur. Si le `status` de la session récupérée vaut `"complete"`, il appelle
`processPayment` — exactement ce qu'appelle le webhook — puis répond
`{"status":"<status>"}`.

Ce chemin existe parce qu'il est rapide : l'acheteur voit son achat arriver alors que
la page est encore ouverte. Ce n'est **pas** le chemin de référence. Un acheteur qui
ferme l'onglet en pleine redirection ne le déclenche jamais.

**Échecs ici :** pas de `session_id` → `400` ; Stripe injoignable ou récupération en
échec → `502 {"error":"Failed to retrieve session"}` ; Stripe non configuré → `503`.
Aucun de ces cas ne perd le paiement, parce que le webhook arrive quand même.

### 5. Le webhook — le chemin de référence

```text
POST /store/api/webhook
Stripe-Signature: t=...,v1=...
```

Configure cette URL comme endpoint dans ton dashboard Stripe, abonné à
`checkout.session.completed`. `handleWebhook` fait deux choses avant de parser le corps
en JSON :

1. **Il refuse d'agir sans secret.** Si `stripeWebhookSecret` est vide, la requête
   reçoit `503 {"error":"Webhook secret not configured"}` et une erreur est
   journalisée. Lis [pourquoi cela compte](#pourquoi-la-vérification-de-signature-nest-pas-optionnelle).
2. **Il vérifie la signature.** `StripeClient.verifyWebhookSignature` analyse
   l'en-tête `Stripe-Signature` pour en extraire `t` et `v1`, rejette un horodatage
   écarté de plus de 300 secondes de l'instant présent, recalcule
   `HMAC-SHA256(secret, "<t>.<corps brut>")` en hexadécimal minuscule, et le compare à
   `v1` en temps constant. Échec → `400 {"error":"Invalid signature"}` et un
   avertissement dans le log.

Seul `checkout.session.completed` donne lieu à une action. Tout autre type
d'événement est accepté et ignoré, donc un endpoint abonné largement est sans danger —
l'endpoint répond toujours `200 {"received":true}` une fois la signature validée, ce
dont Stripe a besoin pour arrêter de réessayer.

### 6. `processPayment` crée la commande

`processPayment` lit les trois champs de métadonnées et l'`id` de la session :

- un champ de métadonnées manquant → journalisé en erreur, rien n'est créé
- `product_id` absent de la configuration → journalisé en erreur, rien n'est créé

Puis `DeliveryManager.createDelivery` s'exécute. **C'est la frontière
d'idempotence.** L'identifiant de session Stripe est l'identifiant de commande, et
`createDelivery` renvoie `false` si une livraison avec cet identifiant existe déjà, en
journalisant `Duplicate delivery attempt for orderId=...`. C'est ce qui empêche le
chemin de vérification (étape 4) et le webhook (étape 5) de livrer deux fois le même
achat — le premier arrivé gagne, le second ne fait rien.

Une livraison créée stocke les commandes **résolues** : `{player}` est remplacé au
moment de la création, depuis le pseudo présent dans les métadonnées Stripe. Modifier
ensuite les commandes du produit ne change pas une livraison déjà en file.

L'enregistrement est ajouté à `config/cheesestore_deliveries.json` immédiatement, et un
message `store_purchase` part sur le canal WebSocket `store`.

### 7. La livraison

Si le serveur tourne, `processPayment` planifie `deliverPending` sur le thread serveur
tout de suite. `deliverPending` rassemble les livraisons `PENDING` de ce joueur et,
**si le joueur est connecté**, exécute chaque commande via
`server.getCommandManager().executeWithPrefix(server.getCommandSource(), cmd)` —
c'est-à-dire depuis la console, avec tous les droits.

| Résultat | Ce qui est enregistré |
|---|---|
| toutes les commandes ont tourné | `DELIVERED`, `deliveredAt` horodaté |
| une commande a levé une exception | `FAILED`, `failReason` = le message de l'exception |
| joueur hors ligne | rien ; la livraison reste `PENDING` |

Une livraison `PENDING` est retentée à chaque connexion suivante :
`CheeseStoreMod` branche `ServerPlayConnectionEvents.JOIN` et appelle `deliverPending`
pour le joueur qui arrive. Un acheteur qui a payé hors ligne reçoit son achat à sa
prochaine connexion, et le serveur journalise
`Delivered N pending purchase(s) to <name>`.

**`FAILED` n'est jamais retenté automatiquement.** La commande reste dans la liste
jusqu'à ce qu'un administrateur appuie sur le bouton de livraison, ce qui appelle
`POST /api/v1/store/orders/<orderId>/deliver` et réexécute les commandes stockées
([livraison manuelle](dashboard.fr.md#post-ordersiddeliver)). Cet endpoint refuse une
commande déjà `DELIVERED` : un double clic ne peut donc pas octroyer deux fois.

---

## L'échec qui te coûte réellement de l'argent

Une commande payée dont les commandes échouent est le seul état où l'acheteur a payé et
n'a rien. Cela arrive pour des raisons ordinaires : une faute de frappe dans la
commande d'un produit, un mod qui possédait cette commande et qui n'est plus installé,
un plugin de rangs retiré. L'enregistrement est conservé, avec le message d'exception,
précisément pour que tu puisses corriger la commande et relivrer plutôt que rembourser.

Surveille cela de deux façons :

- `GET /api/v1/store/stats` rapporte `failedOrders`. Une valeur non nulle signifie que
  quelqu'un a payé et n'a rien reçu.
- Le log serveur porte
  `[CheeseStore] Failed to deliver orderId=<id>: <message>`.

Il n'y a aucune alerte. Si tu encaisses des paiements sans surveiller, vérifie
`failedOrders` dans ta routine.

Un second échec, plus discret : les commandes de livraison tournent depuis la console,
donc une commande qui *silencieusement* ne fait rien (mauvaise casse du pseudo, plugin
qui refuse sans lever d'exception) est enregistrée comme `DELIVERED`. Le mod sait
seulement si la commande a levé une exception, pas si elle a fonctionné. Teste les
commandes de chaque produit à blanc avant de l'activer.

---

## Pourquoi la vérification de signature n'est pas optionnelle

L'URL du webhook est, par nécessité, atteignable par n'importe qui sur Internet — les
serveurs de Stripe doivent y faire des POST, et ils le font depuis des adresses que tu
ne peux pas énumérer à l'avance. La signature est la seule chose qui distingue Stripe
d'un attaquant.

Sans elle, le corps de la requête est simplement cru. Un attaquant qui sait que ta
boutique existe fabriquerait :

```json
{
  "type": "checkout.session.completed",
  "data": { "object": {
    "id": "forged-1",
    "metadata": { "player_uuid": "<son uuid>", "player_name": "<son pseudo>",
                  "product_id": "<ton produit le plus cher>" }
  }}
}
```

Il le poste, et `processPayment` crée une livraison et exécute les commandes du
produit. Une requête, n'importe quel produit, aucun paiement. À répéter avec un `id`
différent à chaque fois, puisque le seul contrôle de doublon porte sur l'identifiant de
commande.

C'est pourquoi `handleWebhook` traite un `stripeWebhookSecret` vide comme un échec dur
plutôt que comme un avertissement. Il n'existe aucun « mode non sécurisé » à activer.
Note aussi ce que la vérification t'apporte au-delà de l'authenticité : la fenêtre
d'horodatage de 300 secondes signifie qu'un vrai payload Stripe capturé sur le réseau ne
peut pas être rejoué des heures plus tard.

---

## Ce dont ce flux ne te protège pas

Lis cette partie comme la liste de tes responsabilités, pas de celles du mod.

**Le `playerUUID` est fourni par le navigateur.** L'étape 2 prend `playerUUID` et
`playerName` dans le corps de la requête et ne vérifie pas que la personne qui paie
possède ce compte. La boutique résout les pseudos via `GET /store/api/players` (qui lit
`usercache.json` plus la liste des joueurs connectés), mais rien n'empêche un POST
fabriqué à la main de nommer l'UUID de quelqu'un d'autre. En pratique, cela veut dire
qu'un acheteur peut offrir un achat à un compte arbitraire — généralement sans
conséquence, mais cela veut dire aussi qu'un enregistrement de livraison ne prouve pas
qui a payé. Les routes d'achat en crédits (`/cosmetics/buy`, `/morpho/buy`,
`/playcosmetics/{id}/buy`) sont différentes : celles-là exigent une session OAuth **et**
un compte Minecraft lié, donc elles savent qui dépense.

**Aucun TLS propre.** Le listener de CheeseDash parle du HTTP en clair. Stripe héberge
la page de carte, donc les données bancaires ne risquent rien, mais le trafic de ta
boutique — cookies de session compris — est en clair tant que tu ne places pas devant
un reverse proxy avec un certificat. Stripe refusera d'ailleurs d'envoyer des webhooks
vers un endpoint en HTTP simple sur un compte en production.

**`X-Forwarded-For` n'est cru que derrière un proxy connu.** `publicClientIp` n'honore
cet en-tête que si le pair immédiat est en loopback ou dans une plage RFC1918
(`10.`, `192.168.`, `172.16`–`172.31`). Si ton proxy est sur une adresse publique,
toutes les requêtes seront comptées contre l'unique IP du proxy au lieu de celle du
vrai client. Ajuste `isTrustedProxy` dans `StoreApi.java` si c'est ta topologie.

**Aucun remboursement, aucun grand livre, aucune facture.** Le mod enregistre ce qu'il
a livré. Remboursements, rétrofacturations, TVA et facturation vivent tous dans ton
dashboard Stripe et dans ta comptabilité, et rien dans le mod ne réconcilie avec eux.
Un paiement remboursé laisse sa livraison en `DELIVERED` ici.

**Les conditions de vente livrées dans `store/cgv.html` ne sont pas les tiennes.**
Elles ont été écrites pour un serveur précis, dans une juridiction précise, avec un
hébergement précis. Remplace-les.

---

← [Configuration](configuration.fr.md) · [Cosmétiques et portefeuilles](cosmetics.fr.md) →
