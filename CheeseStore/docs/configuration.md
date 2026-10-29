[← CheeseStore](../README.md) · [All mods](../../README.md) · 🇫🇷 [Français](configuration.fr.md)

# Configuration

```text
config/cheesestore.json
```

Written on first launch if it does not exist, by `CheeseStoreConfig.save()`. Every
read is tolerant: a key that is absent keeps its Java default, so an older file keeps
working after an update, and a key you delete reverts rather than erroring.

> ⚠️ **This file contains secrets.** `stripeSecretKey`, `stripeWebhookSecret`,
> `googleClientSecret` and `oauthJwtSecret` are all live credentials. Never commit it,
> never put it in a server pack you distribute, never leave it in a public backup.
> The repository's `.gitignore` excludes `config/`, but a pack you assemble by hand
> will sweep it up without complaint. If a key leaks, rotate it from the Stripe
> dashboard immediately — a leaked `sk_live_` key is control of your Stripe account.

**No Stripe key is hard-coded anywhere in the mod.** The three fields initialise to
the empty string in `CheeseStoreConfig`, the only other place a key appears is
`StripeClient`'s constructor parameter, and the only API host in the source is
`https://api.stripe.com/v1`. With an empty `stripeSecretKey` the mod never constructs
a `StripeClient` at all and checkout answers `503 Stripe not configured`. Nothing
phones home, and nothing is pre-filled with somebody else's account.

Five more files sit beside this one. They are state, not configuration, and you edit
them only to repair something:

| File | Holds |
|---|---|
| `cheesestore_deliveries.json` | every order, capped at 5 000 (oldest dropped) |
| `cheesestore_wallets.json` | credit balance per UUID |
| `cheesestore_playcosmetics.json` | the playable cosmetic catalogue |
| `cheesestore_cosmetics.json` | the legacy website cosmetic catalogue |
| `cheesestore_ownership.json` | who owns which cosmetic |
| `cheesestore_equipped.json` | what each player is wearing |
| `cheesestore_assets/` | uploaded product, gallery and cosmetic PNGs |

---

## Stripe

| Key | Type | Default | What it does |
|---|---|---|---|
| `stripePublishableKey` | string | `""` | `pk_…`, handed to the browser by `GET /store/api/config` |
| `stripeSecretKey` | string | `""` | `sk_…`, authenticates every call to the Stripe API |
| `stripeWebhookSecret` | string | `""` | `whsec_…`, verifies that a webhook really came from Stripe |

All three come from your Stripe dashboard. Start with **test** keys (`sk_test_…`) and
the Stripe CLI, and switch to live keys only once a test purchase has delivered
end to end.

`stripePublishableKey` is designed to be public; publishing it is not a leak. Confirm
you have not pasted a secret key into that field, because `GET /store/api/config`
serves it to anyone who asks.

`stripeSecretKey` can be changed without a restart: `PUT /api/v1/store/config` calls
`refreshStripeClient()`, which rebuilds the `StripeClient`, and the next checkout uses
the new key. Clearing it sets the client to `null` and blocks checkout. The dashboard
shows this key masked as `<first 8>***<last 4>`, and a value containing `***` is
refused on write so the masked form cannot be saved over the real one.

`stripeWebhookSecret` is the one with no safe default. Empty means
`POST /store/api/webhook` answers `503` and refuses to act — deliberately, since
[without it anyone can forge a paid order](payments.md#why-the-signature-check-is-not-optional).
Copy it from the endpoint you create in Stripe, not from the account's API keys page;
it is a per-endpoint value and it changes if you recreate the endpoint.

---

## Store

| Key | Type | Default | What it does |
|---|---|---|---|
| `storeName` | string | `"Arkensia"` | heading on the storefront |
| `storeSubtitle` | string | `"Boutique Premium"` | sub-heading |
| `storeBaseUrl` | string | `""` | public origin of the storefront, no trailing slash |
| `storeEnabled` | bool | `true` | master switch for the public catalogue |
| `currencyName` | string | `"crédit"` | what credits are called on the website |

### `storeBaseUrl`

The one key that must be right before you take a payment. It is the public origin
your buyers reach, without a trailing slash:

```json
"storeBaseUrl": "https://store.example.com"
```

Checkout builds Stripe's return URL as `<storeBaseUrl>/store/`, and `/premium open`
builds its chat link the same way. Both are broken by a wrong value, in different
ways: a bad `/premium open` link is a nuisance, while a bad return URL sends a paying
customer somewhere that cannot confirm their purchase.

Two values are rejected outright. Empty, or containing `localhost`, makes
`POST /store/api/checkout` answer:

```text
503 {"error":"Store base URL is not configured. Set storeBaseUrl in config or CheeseDash admin."}
```

The same condition is checked at startup and logged:

```text
[CheeseStore] ⚠ storeBaseUrl is not configured! Checkout will be blocked.
```

That is intentional. A `localhost` return URL means Stripe redirects the buyer to
their own machine, which fails silently from the buyer's point of view — they paid and
landed nowhere. Blocking checkout is the better failure. The check is a substring
test, so a legitimate host that happens to contain the word would also be rejected.

### `storeEnabled`

`false` makes the public catalogue routes — products, gallery, cosmetics,
playcosmetics, morpho list — answer `503 {"error":"Store is disabled"}`. Use it while
you are setting prices.

Three things it does **not** switch off, and this matters: `/checkout`,
`/session-status` and `/webhook` all keep working. A checkout session created just
before you flipped the switch still completes and still delivers. That is almost
certainly the right call — you do not want a disabled store to swallow a payment
already in flight — but it means `storeEnabled: false` is a catalogue switch, not a
kill switch. To actually stop taking money, clear `stripeSecretKey`.

### `currencyName`

A label only. It reaches the website through `GET /store/api/config` and is used
nowhere in the Java logic. In-game messages come from the lang files, which say
*credits* in English and *crédits* in French regardless of what you set here, so a
custom currency name will not appear in chat.

---

## Web session keys

| Key | Type | Default | What it does |
|---|---|---|---|
| `googleClientId` | string | `""` | nothing — see below |
| `googleClientSecret` | string | `""` | nothing |
| `googleRedirectUri` | string | `""` | nothing |
| `oauthJwtSecret` | string | `""` → generated | reserved; self-generating |

> ⚠️ **The three `google*` keys are dead configuration.** They are loaded, saved and
> exposed by getters, and nothing reads them. The class that would use them,
> `auth/GoogleOAuth.java`, has had its Google flow removed: `/oauth/start` and
> `/oauth/callback` are labelled *legacy* in the source and now do nothing but a
> `302` back to `/store/`. Leave all three empty. They are kept, as far as the code
> shows, so an existing config file still parses; the intent is not documented in
> the source and I am not going to guess at it.

What actually identifies a web visitor is simpler, and the name of the class hides
it. `GET /store/api/oauth/me` sets a `store_session` cookie holding a random id —
`HttpOnly`, `Path=/`, `SameSite=Lax`, 14-day lifetime — with no sign-in of any kind.
That anonymous session becomes an identity only when the visitor asks for a pairing
code (`GET /store/api/oauth/link-code`, rate-limited to 12 per minute per IP) and
redeems it in-game with `/store link <code>`. The code lives 5 minutes, and a player
gets 5 redemption attempts per minute.

So the trust chain for every credit purchase is: *whoever held this browser session
also had access to a logged-in Minecraft client on your server*. That is a reasonable
basis for spending credits. It is not an authenticated login, and the cookie carries
no `Secure` flag — put the site behind HTTPS and the flag is still absent, so the
cookie will travel over plain HTTP if anything ever reaches the site that way.

`GET /store/api/config` reports `oauthEnabled: true` unconditionally — a hard-coded
literal, not derived from any key. The storefront's sign-in affordance is therefore
always offered, which is consistent with the fact that it needs no credentials.

`oauthJwtSecret` manages itself and is currently unused by any code path that signs
anything. The first time `getOauthJwtSecret()` is called with a value shorter than 32
characters, it generates 48 bytes from `SecureRandom`, encodes them URL-safe base64
and saves the file immediately. Leave it empty.

None of these four is reachable from the dashboard: `GET /api/v1/store/config` omits
them and `PUT` ignores them. Edit them in the file, then restart.

---

## Products and gallery

`products` and `gallery` are arrays inside the same file, and both are normally
managed from the dashboard rather than by hand — the mod rewrites the whole file on
every change, which will strip your comments and reformat your indentation.

A product:

| Key | Type | Default | What it does |
|---|---|---|---|
| `id` | string | random 8 hex chars | immutable; the key everything else references |
| `name` | string | `null` | shown on the card, and sent to Stripe as the line-item name |
| `description` | string | `null` | card body |
| `priceCents` | int | `0` | the amount charged, in the currency's smallest unit |
| `credits` | int | `0` | a **badge number only** — see below |
| `currency` | string | `"eur"` | ISO code, lower-case, passed straight to Stripe |
| `category` | string | `null` | `"pack"` or `"gems"` in the shipped storefront |
| `commands` | array | `[]` | what the buyer actually receives |
| `enabled` | bool | `true` | hidden from the public catalogue when false |
| `sortOrder` | int | `0` | ascending |
| `featured` | bool | `false` | adds the "popular" badge and gradient border |
| `hasImage` | bool | `false` | set by the image upload, not by hand |

**`priceCents` is in cents.** `499` is €4.99. A product priced `4.99` by someone
thinking in euros charges four cents.

**`credits` grants nothing.** It is the number on the card. The credits a buyer
receives come from `commands`, so a 500-credit pack needs both:

```json
"credits": 500,
"commands": ["premium give {player} 500"]
```

Set one and forget the other and you have a card that promises 500 credits and
delivers none, or delivers credits with no badge. Nothing cross-checks them.

**`commands` run from the server console**, with `{player}` replaced by the buyer's
Minecraft name, through `executeWithPrefix` with the server command source — full
permissions, no `/` prefix needed. They are resolved and stored at order creation, so
editing a product does not change an order already queued. Test every command by hand
before enabling the product: a command that throws marks the order `FAILED`, and a
command that silently does nothing marks it `DELIVERED`.

A gallery item carries `id`, `title`, `description`, `isNew`, `sortOrder`, `hasImage`
and `createdAt`. There is no price and no checkout — it is a showcase of rewards
obtainable elsewhere. `isNew: true` floats an item to the top, newest first.

---

## A complete annotated example

JSON has no comments; the annotations below are for reading, and must come out before
you save the file.

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

Reading notes on that example. `pack500` sets `credits` and the matching
`premium give` command — the pair that has to agree. `rankvip` ships
`enabled: false`, which is how to stage a product: author it, test its commands with
a manual delivery, then enable it. The four web-session keys are left empty: three
are dead and the fourth fills itself on first use.

---

## What takes effect when

| Change | Needs |
|---|---|
| Stripe keys, store name/subtitle/base URL, `storeEnabled`, `currencyName` | `PUT /api/v1/store/config` applies live; editing the file needs a restart |
| Products, gallery | live through the dashboard; editing the file needs a restart |
| the four web-session keys | restart, always — and three of them change nothing |
| Wallets, ownership, equipped | live, every write is immediate |
| Playable cosmetic catalogue | live through the dashboard; editing the file needs a restart |

There is no reload command. The config is read once, at `onInitialize`, and after that
the in-memory copy is authoritative — it is also what gets written back, so **a file
edited by hand while the server runs will be overwritten** by the next dashboard
change. Stop the server before editing the file.

---

← [Commands](commands.md) · [The payment flow](payments.md) →
