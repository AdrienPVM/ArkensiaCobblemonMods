[← CheeseStore](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](configuration.md)

# Configuration

```text
config/cheesestore.json
```

Écrit au premier démarrage s'il n'existe pas, par `CheeseStoreConfig.save()`. Chaque
lecture est tolérante : une clé absente conserve sa valeur par défaut Java, donc un
ancien fichier continue de fonctionner après une mise à jour, et une clé que tu
supprimes revient au défaut plutôt que de provoquer une erreur.

> ⚠️ **Ce fichier contient des secrets.** `stripeSecretKey`,
> `stripeWebhookSecret`, `googleClientSecret` et `oauthJwtSecret` sont tous des
> identifiants vivants. Ne le commite jamais, ne le mets jamais dans un pack serveur
> que tu distribues, ne le laisse jamais dans une sauvegarde publique. Le `.gitignore`
> du dépôt exclut `config/`, mais un pack que tu assembles à la main l'emportera sans
> broncher. Si une clé fuit, révoque-la depuis le dashboard Stripe immédiatement — une
> clé `sk_live_` qui a fuité, c'est le contrôle de ton compte Stripe.

**Aucune clé Stripe n'est codée en dur où que ce soit dans le mod.** Les trois champs
s'initialisent à la chaîne vide dans `CheeseStoreConfig`, le seul autre endroit où une
clé apparaît est le paramètre du constructeur de `StripeClient`, et le seul hôte d'API
présent dans les sources est `https://api.stripe.com/v1`. Avec un `stripeSecretKey`
vide, le mod ne construit jamais de `StripeClient` et le checkout répond
`503 Stripe not configured`. Rien n'appelle à l'extérieur, et rien n'est prérempli avec
le compte de quelqu'un d'autre.

Cinq autres fichiers vivent à côté de celui-là. Ce sont des états, pas de la
configuration, et tu ne les édites que pour réparer quelque chose :

| Fichier | Contenu |
|---|---|
| `cheesestore_deliveries.json` | toutes les commandes d'achat, plafonnées à 5 000 (les plus anciennes sont jetées) |
| `cheesestore_wallets.json` | le solde de crédits par UUID |
| `cheesestore_playcosmetics.json` | le catalogue de cosmétiques jouables |
| `cheesestore_cosmetics.json` | le catalogue de cosmétiques web historique |
| `cheesestore_ownership.json` | qui possède quel cosmétique |
| `cheesestore_equipped.json` | ce que chaque joueur porte |
| `cheesestore_assets/` | les PNG envoyés pour les produits, la galerie et les cosmétiques |

---

## Stripe

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `stripePublishableKey` | chaîne | `""` | `pk_…`, transmise au navigateur par `GET /store/api/config` |
| `stripeSecretKey` | chaîne | `""` | `sk_…`, authentifie chaque appel à l'API Stripe |
| `stripeWebhookSecret` | chaîne | `""` | `whsec_…`, vérifie qu'un webhook vient bien de Stripe |

Les trois viennent de ton dashboard Stripe. Commence avec les clés de **test**
(`sk_test_…`) et le Stripe CLI, et ne passe aux clés de production qu'une fois qu'un
achat de test a été livré de bout en bout.

`stripePublishableKey` est conçue pour être publique ; la publier n'est pas une fuite.
Vérifie seulement que tu n'as pas collé une clé secrète dans ce champ, parce que
`GET /store/api/config` la sert à quiconque la demande.

`stripeSecretKey` peut changer sans redémarrage : `PUT /api/v1/store/config` appelle
`refreshStripeClient()`, qui reconstruit le `StripeClient`, et le checkout suivant
utilise la nouvelle clé. La vider met le client à `null` et bloque le checkout. Le
dashboard affiche cette clé masquée en `<8 premiers>***<4 derniers>`, et une valeur
contenant `***` est refusée à l'écriture pour que la forme masquée ne puisse pas
écraser la vraie clé.

`stripeWebhookSecret` est la seule qui n'a pas de défaut sûr. Vide signifie que
`POST /store/api/webhook` répond `503` et refuse d'agir — délibérément, puisque
[sans elle n'importe qui peut forger une commande payée](payments.fr.md#pourquoi-la-vérification-de-signature-nest-pas-optionnelle).
Copie-la depuis l'endpoint que tu crées dans Stripe, et non depuis la page des clés
d'API du compte ; c'est une valeur propre à chaque endpoint et elle change si tu
recrées l'endpoint.

---

## Boutique

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `storeName` | chaîne | `"Arkensia"` | titre de la boutique |
| `storeSubtitle` | chaîne | `"Boutique Premium"` | sous-titre |
| `storeBaseUrl` | chaîne | `""` | origine publique de la boutique, sans slash final |
| `storeEnabled` | booléen | `true` | interrupteur général du catalogue public |
| `currencyName` | chaîne | `"crédit"` | comment les crédits s'appellent sur le site |

### `storeBaseUrl`

La seule clé qui doit être juste avant d'encaisser un paiement. C'est l'origine
publique que tes acheteurs atteignent, sans slash final :

```json
"storeBaseUrl": "https://store.example.com"
```

Le checkout construit l'URL de retour de Stripe comme `<storeBaseUrl>/store/`, et
`/premium open` construit son lien de chat de la même façon. Une mauvaise valeur casse
les deux, de façons différentes : un lien `/premium open` cassé est un désagrément,
alors qu'une mauvaise URL de retour envoie un client qui a payé vers un endroit
incapable de confirmer son achat.

Deux valeurs sont rejetées d'emblée. Vide, ou contenant `localhost`, fait répondre
`POST /store/api/checkout` :

```text
503 {"error":"Store base URL is not configured. Set storeBaseUrl in config or CheeseDash admin."}
```

La même condition est vérifiée au démarrage et journalisée :

```text
[CheeseStore] ⚠ storeBaseUrl is not configured! Checkout will be blocked.
```

C'est intentionnel. Une URL de retour en `localhost` signifie que Stripe redirige
l'acheteur vers sa propre machine, ce qui échoue silencieusement de son point de vue :
il a payé et n'a atterri nulle part. Bloquer le checkout est le meilleur échec. Le
contrôle est un test de sous-chaîne, donc un hôte légitime qui contiendrait ce mot
serait rejeté lui aussi.

### `storeEnabled`

`false` fait répondre `503 {"error":"Store is disabled"}` aux routes publiques du
catalogue — produits, galerie, cosmétiques, playcosmetics, liste morpho. Sers-t'en
pendant que tu règles les prix.

Trois choses qu'elle ne coupe **pas**, et c'est important : `/checkout`,
`/session-status` et `/webhook` continuent tous de fonctionner. Une session de checkout
créée juste avant que tu ne bascules l'interrupteur se termine quand même et livre
quand même. C'est presque certainement le bon choix — tu ne veux pas qu'une boutique
désactivée avale un paiement déjà en vol — mais cela veut dire que
`storeEnabled: false` est un interrupteur de catalogue, pas un coupe-circuit. Pour
arrêter réellement d'encaisser, vide `stripeSecretKey`.

### `currencyName`

Une étiquette, rien de plus. Elle atteint le site par `GET /store/api/config` et n'est
utilisée nulle part dans la logique Java. Les messages en jeu viennent des fichiers de
langue, qui disent *credits* en anglais et *crédits* en français quoi que tu règles
ici : un nom de monnaie personnalisé n'apparaîtra donc pas dans le chat.

---

## Clés de session web

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `googleClientId` | chaîne | `""` | rien — voir ci-dessous |
| `googleClientSecret` | chaîne | `""` | rien |
| `googleRedirectUri` | chaîne | `""` | rien |
| `oauthJwtSecret` | chaîne | `""` → généré | réservé ; s'autogénère |

> ⚠️ **Les trois clés `google*` sont de la configuration morte.** Elles sont chargées,
> sauvegardées et exposées par des accesseurs, et rien ne les lit. La classe qui les
> utiliserait, `auth/GoogleOAuth.java`, a vu son flux Google retiré : `/oauth/start` et
> `/oauth/callback` sont étiquetées *legacy* dans les sources et ne font plus qu'un
> `302` vers `/store/`. Laisse les trois vides. Elles sont conservées, pour autant que
> le code le montre, pour qu'un fichier de configuration existant continue de se
> parser ; l'intention n'est pas documentée dans les sources et je ne vais pas la
> deviner.

Ce qui identifie réellement un visiteur web est plus simple, et le nom de la classe le
masque. `GET /store/api/oauth/me` pose un cookie `store_session` contenant un
identifiant aléatoire — `HttpOnly`, `Path=/`, `SameSite=Lax`, durée de vie 14 jours —
sans aucune forme d'identification. Cette session anonyme ne devient une identité que
lorsque le visiteur demande un code d'appariement
(`GET /store/api/oauth/link-code`, limité à 12 par minute et par IP) et le valide en
jeu avec `/store link <code>`. Le code vit 5 minutes, et un joueur a droit à 5
tentatives de validation par minute.

La chaîne de confiance de chaque achat en crédits est donc : *celui qui détenait cette
session de navigateur avait aussi accès à un client Minecraft connecté sur ton
serveur*. C'est une base raisonnable pour dépenser des crédits. Ce n'est pas une
connexion authentifiée, et le cookie ne porte pas d'attribut `Secure` — mets le site
derrière HTTPS et l'attribut reste absent, donc le cookie circulera en HTTP clair si
quoi que ce soit atteint le site par cette voie.

`GET /store/api/config` rapporte `oauthEnabled: true` sans condition — un littéral codé
en dur, pas une valeur dérivée d'une clé. L'élément d'identification de la boutique est
donc toujours proposé, ce qui est cohérent avec le fait qu'il n'a besoin d'aucun
identifiant.

`oauthJwtSecret` se gère lui-même et n'est actuellement utilisé par aucun chemin de
code qui signerait quoi que ce soit. La première fois que `getOauthJwtSecret()` est
appelé avec une valeur de moins de 32 caractères, il génère 48 octets depuis
`SecureRandom`, les encode en base64 URL-safe et sauvegarde le fichier immédiatement.
Laisse-le vide.

Aucune de ces quatre clés n'est atteignable depuis le dashboard :
`GET /api/v1/store/config` les omet et `PUT` les ignore. Édite-les dans le fichier,
puis redémarre.

---

## Produits et galerie

`products` et `gallery` sont des tableaux à l'intérieur du même fichier, et les deux se
gèrent normalement depuis le dashboard plutôt qu'à la main — le mod réécrit le fichier
entier à chaque changement, ce qui supprimera tes commentaires et reformatera ton
indentation.

Un produit :

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `id` | chaîne | 8 caractères hexa aléatoires | immuable ; la clé que tout le reste référence |
| `name` | chaîne | `null` | affiché sur la carte, et envoyé à Stripe comme nom de ligne |
| `description` | chaîne | `null` | corps de la carte |
| `priceCents` | entier | `0` | le montant facturé, dans la plus petite unité de la devise |
| `credits` | entier | `0` | **un chiffre de badge uniquement** — voir plus bas |
| `currency` | chaîne | `"eur"` | code ISO, en minuscules, transmis tel quel à Stripe |
| `category` | chaîne | `null` | `"pack"` ou `"gems"` dans la boutique livrée |
| `commands` | tableau | `[]` | ce que l'acheteur reçoit réellement |
| `enabled` | booléen | `true` | masqué du catalogue public quand faux |
| `sortOrder` | entier | `0` | croissant |
| `featured` | booléen | `false` | ajoute le badge « populaire » et la bordure en dégradé |
| `hasImage` | booléen | `false` | positionné par l'envoi d'image, pas à la main |

**`priceCents` est en centimes.** `499` fait 4,99 €. Un produit tarifé `4.99` par
quelqu'un qui pense en euros facture quatre centimes.

**`credits` n'octroie rien.** C'est le nombre sur la carte. Les crédits qu'un acheteur
reçoit viennent de `commands`, donc un pack de 500 crédits a besoin des deux :

```json
"credits": 500,
"commands": ["premium give {player} 500"]
```

Règle l'un et oublie l'autre, et tu obtiens une carte qui promet 500 crédits et n'en
livre aucun, ou qui livre des crédits sans badge. Rien ne les recoupe.

**Les `commands` s'exécutent depuis la console serveur**, avec `{player}` remplacé par
le pseudo Minecraft de l'acheteur, via `executeWithPrefix` avec la source de commande
du serveur — tous les droits, pas besoin du préfixe `/`. Elles sont résolues et
stockées à la création de la commande d'achat, donc modifier un produit ne change pas
une commande déjà en file. Teste chaque commande à la main avant d'activer le produit :
une commande qui lève une exception marque la commande d'achat `FAILED`, et une
commande qui ne fait rien en silence la marque `DELIVERED`.

Une entrée de galerie porte `id`, `title`, `description`, `isNew`, `sortOrder`,
`hasImage` et `createdAt`. Il n'y a ni prix ni checkout — c'est une vitrine de
récompenses à obtenir ailleurs. `isNew: true` fait remonter une entrée en haut, les
plus récentes d'abord.

---

## Un exemple complet annoté

JSON n'a pas de commentaires ; les annotations ci-dessous servent à la lecture et
doivent disparaître avant que tu enregistres le fichier.

```json
{
  "stripePublishableKey": "pk_test_51Abc...",
  "stripeSecretKey": "sk_test_51Abc...",
  "stripeWebhookSecret": "whsec_7f2a...",

  "storeName": "Arkensia",
  "storeSubtitle": "Boutique Premium",
  "storeBaseUrl": "https://store.example.com",
  "storeEnabled": true,
  "currencyName": "crédit",

  "googleClientId": "",
  "googleClientSecret": "",
  "googleRedirectUri": "",
  "oauthJwtSecret": "",

  "products": [
    {
      "id": "pack500",
      "name": "500 crédits",
      "description": "De quoi s'offrir deux traînées.",
      "priceCents": 499,
      "credits": 500,
      "currency": "eur",
      "category": "gems",
      "enabled": true,
      "featured": true,
      "sortOrder": 0,
      "hasImage": true,
      "commands": ["premium give {player} 500"]
    },
    {
      "id": "rankvip",
      "name": "Rang VIP",
      "description": "Accès permanent au rang VIP.",
      "priceCents": 1499,
      "credits": 0,
      "currency": "eur",
      "category": "pack",
      "enabled": false,
      "featured": false,
      "sortOrder": 1,
      "hasImage": false,
      "commands": [
        "lp user {player} parent add vip",
        "tellraw {player} {\"text\":\"Merci !\",\"color\":\"gold\"}"
      ]
    }
  ],

  "gallery": []
}
```

Notes de lecture sur cet exemple. `pack500` règle `credits` et la commande
`premium give` correspondante — la paire qui doit s'accorder. `rankvip` est livré avec
`enabled: false`, ce qui est la façon de préparer un produit : l'écrire, tester ses
commandes par une livraison manuelle, puis l'activer. Les quatre clés de session web
sont laissées vides : trois sont mortes et la quatrième se remplit toute seule au
premier usage.

---

## Ce qui prend effet quand

| Changement | Nécessite |
|---|---|
| clés Stripe, nom/sous-titre/URL de base, `storeEnabled`, `currencyName` | `PUT /api/v1/store/config` s'applique à chaud ; une édition du fichier exige un redémarrage |
| produits, galerie | à chaud par le dashboard ; une édition du fichier exige un redémarrage |
| les quatre clés de session web | redémarrage, toujours — et trois d'entre elles ne changent rien |
| portefeuilles, possession, équipement | à chaud, chaque écriture est immédiate |
| catalogue de cosmétiques jouables | à chaud par le dashboard ; une édition du fichier exige un redémarrage |

Il n'existe aucune commande de rechargement. La configuration est lue une fois, à
`onInitialize`, et ensuite la copie en mémoire fait foi — c'est aussi elle qui est
réécrite, donc **un fichier édité à la main pendant que le serveur tourne sera
écrasé** par le prochain changement venu du dashboard. Arrête le serveur avant
d'éditer le fichier.

---

← [Commandes](commands.fr.md) · [Le flux de paiement](payments.fr.md) →
