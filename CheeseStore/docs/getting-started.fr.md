[← CheeseStore](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](getting-started.md)

# Premiers pas

À la fin de cette procédure, tu auras effectué un vrai paiement Stripe — en mode test,
avec une carte de test — observé l'arrivée du webhook, et vu les crédits atterrir dans
ton propre portefeuille en jeu. C'est toute la boucle, et c'est la seule chose qui
mérite d'être vérifiée avant de pointer une vraie carte dessus.

Tout ce qui suit se passe en mode **test** Stripe. Aucun argent ne bouge. Ne passe pas
aux clés de production avant que la dernière étape ait fonctionné au moins une fois.

Compte 30 à 45 minutes, surtout du temps passé sur le dashboard Stripe.

---

## Étape 1 — le faire tourner

Suis [installation](installation.fr.md) jusqu'à la vérification des logs : trois jars
dans `mods/`, serveur démarré, `[CheeseStore] Initialized successfully!` dans le log.

Vérifie que le module est atteignable :

```bash
curl http://localhost:44311/store/api/config
```

```json
{"storeName":"Arkensia","storeSubtitle":"Boutique Premium","stripePublishableKey":"","storeEnabled":true,"currencyName":"crédit","oauthEnabled":true}
```

Un `404` ici veut dire que CheeseDash ne charge pas le module ; arrête-toi et corrige
cela d'abord ([pourquoi](troubleshooting.fr.md#le-module-store-est-absent-du-dashboard)).

Tu verras aussi cet avertissement dans le log, et il est juste :

```text
[CheeseStore] ⚠ storeBaseUrl is not configured! Checkout will be blocked.
```

## Étape 2 — se connecter au dashboard

Ouvre `http://localhost:44311/`, identifie-toi avec le token maître de
`config/cheesedash.json`, et trouve **CheeseStore** dans la barre latérale — l'icône
diamant, intitulée *Boutique premium et paiements*.

S'il n'y est pas, le mod s'est chargé mais CheeseDash ne l'a pas enregistré. De nouveau,
[dépannage](troubleshooting.fr.md#le-module-store-est-absent-du-dashboard).

## Étape 3 — régler `storeBaseUrl`

Pour cette procédure, il te faut une URL vers laquelle Stripe peut rediriger un
navigateur. Localhost ne fera pas l'affaire — le mod la rejette explicitement, parce
qu'un acheteur redirigé vers `localhost` arrive sur sa propre machine.

Si tu as un domaine pointé sur le serveur, utilise-le. Sinon, utilise un tunnel :

```bash
# une option parmi beaucoup ; n'importe quel tunnel HTTPS vers le port 44311 convient
ngrok http 44311
```

Prends l'URL HTTPS qu'il te donne et règle-la dans les paramètres boutique du
dashboard, ou directement dans `config/cheesestore.json` avec le serveur arrêté :

```json
"storeBaseUrl": "https://a1b2c3d4.ngrok-free.app"
```

Pas de slash final. L'URL de retour du checkout est construite comme
`<storeBaseUrl>/store/`.

Redémarre si tu as édité le fichier à la main ; un changement depuis le dashboard prend
effet immédiatement. L'avertissement doit cesser d'apparaître au démarrage suivant.

## Étape 4 — coller tes clés Stripe de test

Dans le dashboard Stripe, avec l'interrupteur **Mode test** activé, ouvre
*Developers → API keys* et copie :

- la clé publiable, `pk_test_…`
- la clé secrète, `sk_test_…`

Colle les deux dans le panneau de configuration de CheeseStore et enregistre. La réponse
est `{"ok":true}` et `refreshStripeClient()` reconstruit le client Stripe — sans
redémarrage.

Réouvre le panneau et la clé secrète affiche maintenant `sk_test_***1a2b` (les 8 premiers caractères, `***`, les 4 derniers). C'est le
masque ; ce n'est pas ce qui a été enregistré. Une valeur contenant `***` est refusée à
l'écriture précisément pour que le masque ne puisse pas écraser la vraie clé.

Vérifie que la clé a bien été prise :

```bash
curl http://localhost:44311/store/api/config
```

`stripePublishableKey` doit maintenant être renseignée. La clé secrète n'apparaît jamais
sur cette route.

## Étape 5 — enregistrer le webhook

C'est l'étape que les gens sautent, et c'est celle qui fait fonctionner l'ensemble.

Dans Stripe, *Developers → Webhooks → Add endpoint* :

- **URL** : `<storeBaseUrl>/store/api/webhook`
- **Événements** : `checkout.session.completed` — celui-là, rien d'autre ne donne lieu
  à une action

Stripe affiche alors un **secret de signature**, `whsec_…`. Copie-le dans
`stripeWebhookSecret` dans le dashboard et enregistre.

Ne saute pas ce champ. S'il est vide, `POST /store/api/webhook` répond `503` et refuse
de traiter quoi que ce soit, délibérément — et
[sans la vérification de signature, n'importe qui peut forger une commande payée](payments.fr.md#pourquoi-la-vérification-de-signature-nest-pas-optionnelle).

Envoyer un événement de test depuis Stripe est un contrôle de bon sens rapide. Un
`checkout.session.completed` de test ne porte aucune des métadonnées du mod : une
configuration correcte journalise donc `[CheeseStore] Missing metadata in session <id>`,
ne crée rien et renvoie `200` ; tout autre type d'événement est ignoré en silence avec
le même `200`. Un `400 Invalid signature` veut dire que tu as copié le mauvais secret ;
un `503` veut dire que le champ est encore vide.

## Étape 6 — créer un produit

Dans la vue produits du dashboard, ajoutes-en un :

| Champ | Valeur |
|---|---|
| Nom | `500 crédits` |
| Description | `De quoi s'offrir deux traînées.` |
| Prix (centimes) | `499` |
| Credits | `500` |
| Devise | `eur` |
| Catégorie | `gems` |
| Activé | oui |
| Commands | `premium give {player} 500` |

Deux choses à ne pas rater ici.

**Le prix est en centimes.** `499` fait 4,99 €. Taper `4.99` facture quatre centimes.

**`credits` n'octroie rien.** C'est le nombre imprimé sur la carte. Ce que l'acheteur
reçoit réellement, c'est la liste `commands` : le `500` du badge et le `500` de
`premium give {player} 500` doivent donc rester cohérents par tes soins — rien ne le
vérifie.

Vérifie que le produit est public :

```bash
curl http://localhost:44311/store/api/products
```

```json
[{"id":"a3f91c02","name":"500 crédits","description":"De quoi s'offrir deux traînées.","priceCents":499,"credits":500,"currency":"eur","category":"gems","featured":false,"hasImage":false}]
```

Note ce qui n'est **pas** dans cette réponse : `commands` et `enabled`. La forme
publique les omet, donc tes commandes de livraison ne sont jamais exposées aux
acheteurs.

## Étape 7 — tester la commande avant que quiconque la paie

Exécute la commande de livraison à la main, comme la console l'exécutera :

```mcfunction
/premium give <tonpseudo> 500
```

```text
+500 crédits donnés à <tonpseudo>. Nouveau solde : 500
```

Si cela échoue, chaque achat de ce produit échouera aussi — et il échouera *après* que
l'argent a été prélevé, en silence : la commande d'achat est quand même enregistrée
`DELIVERED`, parce que le gestionnaire de commandes de Minecraft avale l'erreur
([détails](payments.fr.md#léchec-qui-te-coûte-réellement-de-largent)). Corrige
maintenant.

Puis remets-toi à zéro pour que le vrai test soit sans ambiguïté :

```mcfunction
/premium set <tonpseudo> 0
```

## Étape 8 — lier ton compte

Ouvre `<storeBaseUrl>/store/` dans un navigateur. La boutique se charge, la carte de
ton produit y est.

Demande un code d'appariement au site :

```bash
curl -c cookies.txt http://localhost:44311/store/api/oauth/link-code
```

```json
{"code":"A1B2C3D4","expiresAt":1730000300000}
```

Dans le navigateur, c'est l'élément d'identification ; le code vit 5 minutes. Valide-le
en jeu :

```mcfunction
/store link A1B2C3D4
```

```text
Session boutique liée à <tonpseudo>. Tu peux désormais voir ton solde et acheter des morphos depuis le site.
```

Le site sait maintenant quel compte Minecraft possède cette session, ce qu'exigent les
routes qui dépensent des crédits. Note que la session liée ici est celle que `curl` a
stockée dans `cookies.txt`, pas celle de ton navigateur : chaque client reçoit son
propre cookie `store_session`. Pour voir ton solde sur la page, demande un code via le
bouton d'identification de la boutique elle-même et valide-le aussi. Le checkout par
carte de l'étape suivante n'a **pas** besoin de liaison — il prend l'UUID dans le corps
de la requête — mais l'étape 11 utilise la session de `cookies.txt`.

## Étape 9 — acheter ton propre produit avec une carte de test

Sur la boutique, choisis ton pseudo et va jusqu'au checkout. La page poste vers
`/store/api/checkout`, reçoit une `checkoutUrl` en retour, et t'envoie sur la page de
paiement hébergée par Stripe.

Paie avec la carte de test de Stripe :

```text
4242 4242 4242 4242    n'importe quelle date future    n'importe quel CVC    n'importe quel code postal
```

Stripe te redirige vers `<storeBaseUrl>/store/?session_id=cs_test_…`.

Si le checkout renvoie `503` à la place, l'une de deux choses est fausse :
`storeBaseUrl` est vide ou contient `localhost`, ou `stripeSecretKey` est vide. Le corps
de la réponse dit laquelle. Un produit désactivé ou inconnu répond
`404 Product not found or disabled`.

## Étape 10 — observer la livraison

Deux choses doivent s'être produites à ce stade, et elles se font volontairement la
course.

Dans le log serveur :

```text
[CheeseStore] Delivery created: orderId=cs_test_a1b2..., player=<tonpseudo>, product=500 crédits
[CheeseStore] Delivered orderId=cs_test_a1b2... to player=<tonpseudo>
```

Et en jeu :

```text
🧀 +500 crédits reçus ! Nouveau solde : 500
```

Confirme :

```mcfunction
/premium
```

```text
🧀 Crédits : 500
```

La commande d'achat est maintenant dans la vue des commandes du dashboard, en
`DELIVERED`, et `GET /api/v1/store/stats` rapporte `deliveredOrders: 1`,
`failedOrders: 0`.

Quel chemin a livré ? Probablement la redirection : la page appelle
`/store/api/session-status`, qui récupère la session chez Stripe et la traite
immédiatement. Le webhook arrive un instant plus tard, trouve qu'une commande avec cet
identifiant existe déjà, journalise `Duplicate delivery attempt` et ne fait rien. Les
deux chemins sont actifs, l'identifiant de session Stripe est la clé d'idempotence, et
le premier arrivé gagne. C'est la conception voulue, et voir cette ligne de doublon
confirme que cela fonctionne.

**Teste maintenant le chemin qui compte davantage.** Ferme l'onglet du navigateur à
l'instant où Stripe redirige, avant que la page puisse appeler `session-status`, et
achète de nouveau. Le webhook seul doit livrer. Si ce n'est pas le cas, ton endpoint de
webhook est mauvais, et tu te reposais entièrement sur le fait que les acheteurs ne
ferment pas leur onglet.

Enfin, teste une livraison hors ligne : fais acheter un second compte alors qu'il est
déconnecté. La commande reste `PENDING` jusqu'à sa prochaine connexion, et là :

```text
[CheeseStore] Delivered 1 pending purchase(s) to <nom>
```

## Étape 11 — dépenser les crédits

L'autre moitié de la boutique. Choisis un cosmétique dans le catalogue semé :

```bash
curl http://localhost:44311/store/api/playcosmetics | head -c 300
```

`trail_heart` coûte 350. Achète-le depuis la page cosmétiques de la boutique, ou
directement avec le cookie de session de l'étape 8 :

```bash
curl -b cookies.txt -X POST \
  -H "Origin: http://localhost:44311" \
  http://localhost:44311/store/api/playcosmetics/trail_heart/buy
```

```json
{"success":true,"newBalance":150,"cosmeticId":"trail_heart"}
```

Évite `trail_flame` pour ce test : à cause d'un bug de cache dans `TrailManager`, une
traînée dont la particule est `minecraft:flame` s'affiche une fois, puis plus rien
jusqu'au redémarrage suivant (voir
[le dépannage](troubleshooting.fr.md#une-traînée-ou-un-effet-de-kill-ne-fait-rien)).

L'en-tête `Origin` compte : la route rejette un POST d'origine différente avec
`403 forbidden_origin`, parce qu'elle dépense de l'argent. `401 not_authenticated` veut
dire soit qu'il n'y a pas de cookie, soit que sa session n'a jamais été liée — l'étape 8
n'a pas abouti.

Puis, en jeu :

```mcfunction
/cosmetics
```

Le bouton *Traînées* affiche maintenant `Possédés : 1 / 10`. Clique dessus, clique
*Cœurs Flottants*, et des cœurs tournent en spirale autour de toi.

```mcfunction
/premium
```

```text
🧀 Crédits : 150
```

500 entrés, 350 sortis. La boucle est fermée : un paiement par carte est devenu des
crédits, les crédits sont devenus un cosmétique, et le cosmétique est sur un joueur.

---

## Avant de passer aux clés de production

Que la boucle de test fonctionne est nécessaire, pas suffisant. Passe par cette liste,
dans l'ordre :

1. **Passe en revue le catalogue semé.** 99 cosmétiques sont activés et tarifés en ce
   moment, avec des titres en français, y compris 32 capes invisibles sans le mod client
   `factionwarhud`. Désactive ou réécris ce que tu ne vends pas réellement.
2. **Sers la boutique en HTTPS** derrière un reverse proxy. Stripe ne livre pas de
   webhooks vers du HTTP clair sur un compte de production, et le cookie de session n'a
   pas d'attribut `Secure`.
3. **Réécris `store/cgv.html`.** Les conditions générales de vente livrées là ont été
   écrites pour un serveur précis, dans une juridiction précise. Les publier comme les
   tiennes est pire que de n'en avoir aucune.
4. **Lis [SECURITY.md](../../SECURITY.md).**
5. **Traite chaque compte `OPERATOR` comme un exploitant de boutique à part entière.**
   Les écritures exigent bien `OPERATOR` — une session `READ_ONLY` est réellement en
   lecture seule — mais aucune route d'ici ne peut exiger davantage, donc `OPERATOR`
   suffit pour fixer des soldes de portefeuille et écraser tes clés Stripe.
   [Pourquoi](dashboard.fr.md#ce-que-le-cœur-applique-réellement).
6. **Décide comment tu remarqueras une commande d'achat non livrée.** Rien ne t'alerte,
   et le compteur `failedOrders` de `GET /api/v1/store/stats` rate le cas courant — une
   commande qui échoue dans la console est quand même enregistrée `DELIVERED`. Lis la
   console après chaque vente tant que tu n'as pas confiance dans tes commandes.
7. **Échange les clés de test contre les clés de production, et crée un nouvel endpoint
   de webhook.** Le secret de signature est propre à chaque endpoint : un endpoint de
   production a un `whsec_…` différent de celui de test, et coller l'ancien signifie que
   chaque paiement réel échoue la vérification de signature et que rien n'est livré.

C'est ce dernier point qui piège. Vérifie-le avec un produit à 0,50 € et ta propre carte
avant d'annoncer la boutique.

---

← [Installation](installation.fr.md) · [Commandes](commands.fr.md) →
