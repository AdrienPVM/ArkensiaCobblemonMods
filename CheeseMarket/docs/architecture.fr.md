[← CheeseMarket](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](architecture.md)

# Architecture

Le jar contient 23 fichiers source Java, environ 6 900 lignes, tous sous
`com.cheesemarket`. Le système d'événements n'est pas ici : il vit dans
[CheeseGuard](../../CheeseGuard/).

---

## Organisation des packages

```text
com.cheesemarket
├── CheeseMarket.java            ModInitializer — payloads, receiver, commandes, cycle de vie
├── CheeseMarketClient.java      ClientModInitializer — les deux receivers S2C
├── command/
│   └── ShopCommands.java        /cmadmin et l'arbre /shop
├── data/
│   ├── ShopData.java            boutique : couleurs, mode d'affichage, mise en page, sets
│   ├── ShopSet.java             set : nom, rotation, items, sortOrder
│   ├── ShopItem.java            item : icône, sorties, coûts, commandes, stock
│   ├── ItemEntry.java           { itemId, count, components? }
│   ├── CommandEntry.java        { command, count }
│   ├── StockConfig.java         mode de stock, limites, cadence de remise à zéro
│   ├── RotationConfig.java      ALWAYS / SCHEDULE / MANUAL
│   └── ShopLayout.java          éléments, gabarit de carte, style de la fenêtre de confirmation
├── manager/
│   └── ShopManager.java         chargement/écriture, compteurs, validation d'achat, journal
├── network/
│   └── ShopPayloads.java        trois records CustomPayload
├── server/
│   ├── ShopGuiHandler.java      toute action venue d'un client ; le pipeline d'achat
│   ├── MorphoShopRenderer.java  synthétise une boutique depuis le catalogue CheeseItems
│   ├── CheeseStoreHook.java     portefeuille de fromage, par réflexion
│   ├── NumismaticHook.java      bourse de pièces, par réflexion
│   └── CheeseItemsHook.java     icônes, noms et piles d'items personnalisés, par réflexion
├── client/
│   ├── ShopViewScreen.java      l'interface joueur
│   └── CheeseMarketScreen.java  l'éditeur d'administration en jeu
└── dashboard/
    ├── CheeseMarketDashboardModule.java   préfixe « market », métadonnées de navigation
    ├── MarketApi.java                     le dispatch REST
    └── AssetManager.java                  assets PNG et templates de mise en page

```

---

## Qui possède quoi

**`ShopManager`** est la seule autorité mutable. Un singleton paresseux, remplacé en bloc
par `init(worldDataDir)` au `SERVER_STARTED`. Il possède la table des boutiques, les trois
tables de stock et la liste des transactions ; c'est la seule classe qui écrit le JSON des
boutiques, le fichier de stock ou le fichier de transactions. Rien d'autre ne garde une
référence à un `ShopData` d'un tick sur l'autre.

Ses collections sont hétérogènes : `shops` est un simple `LinkedHashMap`, modifié
seulement sur le thread serveur mais aussi parcouru, sans synchronisation, par les
handlers `GET` du dashboard sur le thread HTTP ; les tables de stock sont des
`ConcurrentHashMap` (aucune route HTTP ne les lit) ; la liste de transactions est une
`CopyOnWriteArrayList`, ce qui permet de servir `GET /transactions` depuis le thread HTTP
sans risque.

**`ShopGuiHandler`** est sans état et statique. Il prend une chaîne d'action plus un
joueur et fait tout, du CRUD d'administration au pipeline d'achat. Avec ses 1 300 lignes,
c'est la plus grosse classe du mod et l'endroit où la logique métier vit réellement.

**`ShopViewScreen`** détient tout l'état de rendu côté joueur : le JSON de boutique
analysé, la mise en page, les décalages de défilement, la quantité de la fenêtre de
confirmation, et une table de textures enregistrées dynamiquement pour les icônes d'items
personnalisés. Il détruit ces textures dans `removed()`.

**`AssetManager`** possède les dossiers de PNG et de templates, et constitue la seule
couche de validation des fichiers téléversés.

Les trois classes `*Hook` possèdent chacune un jeu de handles de réflexion mis en cache,
résolu une fois au premier usage derrière un drapeau `checked`, et chacune journalise une
ligne indiquant si elle a trouvé son mod cible.

---

## État et écritures disque

| Quoi | Où | Écrit |
|---|---|---|
| définitions de boutiques | `<world>/data/cheesemarket/shops/<id>.json` | à chaque mutation, fichier entier, formaté lisiblement |
| compteurs de stock | `<world>/data/cheesemarket/cheesemarket_stock.json` | à chaque achat, et au `SERVER_STOPPING` |
| journal de transactions | `<world>/data/cheesemarket/cheesemarket_transactions.json` | à chaque achat |
| images téléversées | `<world>/data/cheesemarket/assets/<shopId>/*.png` | au téléversement |
| templates de mise en page | `<world>/data/cheesemarket/templates/*.json` | à l'enregistrement |

Il n'y a aucun regroupement par drapeau « modifié » et aucune file d'écriture. Un achat
écrit deux fichiers de façon synchrone sur le thread serveur ; une édition d'item réécrit
tout le fichier de boutique. Les deux sont petits, les deux conviennent à l'échelle où
cela a tourné, et les deux sont la première chose à regarder si un fichier de boutique
dépasse un jour quelques centaines de kilooctets.

Les trois fichiers JSON sont lus au `SERVER_STARTED` et sur `/shop reload`, et nulle part
ailleurs ; images et templates sont relus sur le disque à chaque utilisation. Un
fichier de boutique édité à la main pendant que le serveur tourne est écrasé par la
mutation suivante.

---

## Threads

| Thread | Ce qui y tourne |
|---|---|
| thread serveur | tout `ShopGuiHandler`, chaque mutation de `ShopManager`, les commandes, les callbacks de cycle de vie |
| thread HTTP de CheeseDash | le dispatch de `MarketApi`, la validation, la construction du JSON, les I/O fichiers d'`AssetManager` |
| thread de rendu client | `ShopViewScreen`, `CheeseMarketScreen`, l'enregistrement des textures |

Deux passages de relais, tous deux explicites :

- **Client → thread serveur.** Le receiver `cheesemarket:shop_action` enregistré dans
  `CheeseMarket.onInitialize` enveloppe le traitement dans
  `context.server().execute(…)` : aucune action ne tourne donc sur un thread netty.
- **Thread HTTP → thread serveur.** Chaque handler mutant de `MarketApi` enveloppe son
  travail dans `WebDashboard.executeOnServerThread(server, () -> …)` puis relit le résultat
  dans un tableau à un élément. Les handlers GET lisent directement depuis le thread
  HTTP : `transactions`, en copy-on-write, est sans risque ; la table `shops`, elle, ne
  l'est pas et se lit sans verrou.

`AssetManager` fait exception : il lit et écrit les fichiers PNG directement depuis le
thread HTTP. Le thread serveur ne fait que les lire (pour intégrer un fond dans une vue
de boutique), donc le chevauchement est bénin — mais un disque lent bloque une requête
HTTP plutôt qu'un tick.

---

## Protocole réseau

Trois payloads, tous définis dans `ShopPayloads` :

| Id | Direction | Forme |
|---|---|---|
| `cheesemarket:shop_action` | C2S | une chaîne, l'action |
| `cheesemarket:shop_sync` | S2C | `viewMode` plus une chaîne JSON |
| `cheesemarket:shop_open` | S2C | un booléen, `adminMode` |

`shop_sync` utilise un codec de chaîne à grande capacité qui lit et écrit avec
`Integer.MAX_VALUE` comme limite, au lieu des 32 767 caractères de `PacketCodecs.STRING`.
Il n'a pas le choix : une vue de boutique embarque des PNG en base64 pour le fond et pour
chaque icône d'item personnalisé, plus une infobulle vanilla sérialisée par identifiant
d'item, et cela dépasse largement la limite par défaut.

Le protocole est un protocole à découpage de chaînes. Les actions ressemblent à
`item_save:<shopId>:<setId>:<itemId>|<json>`, découpées sur `:` avec une limite et sur `|`
pour la charge. Deux séparateurs sont en usage parce que les identifiants d'items
contiennent des deux-points : les actions de sortie et de coût utilisent `|` partout
exactement pour cette raison. Ça marche, et c'est la partie du mod qu'un mainteneur a le
plus de chances de casser en ajoutant un champ.

Valeurs de `viewMode` : `SHOP_LIST`, `SHOP_EDIT`, `SET_LIST`, `SET_EDIT`, `ITEM_LIST`,
`ITEM_EDIT`, `ITEM_PICKER`, `ERROR`, `SHOP_VIEW`, `PURCHASE_RESULT`, `CLOSE`. Le client
route `SHOP_VIEW` et `PURCHASE_RESULT` vers l'écran joueur, `CLOSE` vers la fermeture de
tout, et tout le reste vers l'écran d'administration.

Les actions qu'un joueur ordinaire peut envoyer, et celles qui exigent le niveau de
permission 2, sont décrites sous [le canal d'actions](#le-canal-dactions).

---

## Mixins

**Aucun.** `fabric.mod.json` n'a aucune clé `mixins`, et CheeseMarket n'en a pas besoin :
il s'accroche au jeu par les événements de la Fabric API, le callback d'enregistrement des
commandes et ses propres payloads.

---

## Dépendances optionnelles

Trois mods facultatifs, chacun atteint par réflexion pour que le jar se charge sans eux.
Les trois sont en `suggests` ou absents de `fabric.mod.json` — seuls `cheesedash` et
`fabric-api` sont des dépendances dures.

| Hook | Cible | Utilisé pour |
|---|---|---|
| `CheeseStoreHook` | `com.cheesestore.WalletManager` | solde et prélèvement de fromage |
| `NumismaticHook` | `com.glisco.numismaticoverhaul.ModComponents` / `CurrencyComponent` | solde et prélèvement de la bourse de pièces |
| `CheeseItemsHook` | `com.cheeseitems.CheeseItemsMod`, `ItemManager`, `CheeseItemsConfig`, `CustomItemDefinition`, `morpho.MorphoCatalog` | PNG d'icônes, noms d'affichage, piles construites, catalogue morpho |

`MorphoShopRenderer` et `ShopGuiHandler.MorphoCatalogReflect` ajoutent une quatrième et
une cinquième surface de réflexion sur les classes morpho de CheeseItems.

Tout cela résout classes et méthodes **par leur nom**. C'est un compromis délibéré — les
mods restent compilables indépendamment — et une fragilité permanente : renomme
`getPriceCredits`, `hasInCodex` ou `buildItemStack` dans CheeseItems, ou
`CurrencyComponent.modify` dans Numismatic Overhaul, et la fonctionnalité correspondante
s'éteint toute seule avec une ligne `WARN` à la première utilisation du hook et plus
aucune plainte ensuite.
Cherche `[Market] Failed to initialize` dans le log après avoir mis à jour l'un de ces
mods.

`fabric.mod.json` liste `cheesestore` et `cheeseitems` sous `suggests`. Numismatic Overhaul
n'est pas listé du tout, pas même en suggestion, alors que le mod réfléchit dedans par son
identifiant de mod.

---

## L'ancienne copie de `com.cheeseguard`

Les builds antérieurs au 29 octobre 2026 embarquaient dans ce jar une seconde copie,
plus ancienne, du code de CheeseGuard : 86 classes sous `com.cheeseguard`, un
`cheeseguard.mixins.json` et `assets/cheeseguard/lang/`. Rien dans CheeseMarket ne s'en
servait et aucun entrypoint n'y menait, mais elle n'était pas inoffensive. Avec les deux
mods installés, les mêmes noms de classes existaient dans deux jars, et aucun des deux
mods ne décide de la copie que le jeu charge ; les fichiers de langue dupliqués pouvaient
de même écraser les traductions du vrai CheeseGuard. La copie datait en outre d'avant les
correctifs de permissions de CheeseGuard.

Tout a été supprimé. Si un ancien jar `cheesemarket` traîne encore dans un dossier
`mods/`, remplace-le.

---

## Faiblesses connues

### Le canal d'actions

`CheeseMarket.onInitialize` enregistre un unique receiver global pour
`cheesemarket:shop_action` et confie chaque payload à `ShopGuiHandler.handle`. N'importe
quel client peut placer un payload sur ce canal — le client d'origine à travers ses
écrans, un client modifié avec la chaîne de son choix. Le contrôle doit donc se trouver
dans le gestionnaire, et c'est le cas : la première chose que fait `handle` est de ranger
l'action dans l'un de deux groupes.

| Groupe | Actions | Qui peut les envoyer |
|---|---|---|
| Actions joueur | `open_shop_player:<shopId>`, `shop_buy:<shopId>:<setId>:<itemId>[:<qté>]` | tout le monde — ce sont celles qu'envoie `ShopViewScreen` quand un joueur parcourt une boutique et achète |
| Actions d'administration | tout le reste : les lectures `open_*`, `shop_*`, `set_*`, `item_*`, `picker_*` | niveau de permission 2 et plus (`player.hasPermissionLevel(2)`) |

Le niveau 2 est la même barre que `/cmadmin`, la commande qui ouvre l'éditeur d'où
partent ces actions : un opérateur ne remarque jamais ce garde. Une action
d'administration envoyée par quelqu'un en dessous est écartée avant toute branche — rien
n'est lu, écrit ni renvoyé — et une ligne part dans le journal du serveur :

```text
[CheeseMarket] Refused admin action from non-op <nom>: <action>
```

L'action est coupée à 64 caractères suivis de `…`, pour qu'un long payload `item_save`
n'inonde pas le journal. Le client ne reçoit aucune réponse : un éditeur resté ouvert
après que son joueur a perdu le statut d'opérateur cesse de répondre, sans aucun message
à l'écran.

**Historique.** Avant le 29 octobre 2026, ce chemin ne comportait aucun contrôle : ni le
receiver ni aucune branche de `handle` ne regardait l'expéditeur. Un joueur muni d'un
client modifié pouvait envoyer toutes les actions d'administration et, comme les
commandes d'achat s'exécutent depuis la source console, cela suffisait à faire exécuter
des commandes arbitraires comme la console du serveur. Le garde ci-dessus ferme cette
faille. Si un jar `cheesemarket` compilé avant cette date se trouve dans un dossier
`mods/`, remplace-le.

#### Ce que le garde ne couvre pas

Aucun des points ci-dessous ne permet à un joueur d'exécuter une commande ni de modifier
une boutique. Tous sont antérieurs au correctif, et tous viennent de ce que les deux
actions joueur font confiance à leurs arguments.

- **N'importe quelle boutique, depuis n'importe où.** `open_shop_player` et `shop_buy`
  acceptent n'importe quel identifiant de boutique. Rien ne vérifie que la boutique a
  été ouverte pour ce joueur par un NPC, une quête ou `/shop open`, et rien ne teste la
  distance. Un client modifié peut donc consulter toutes les boutiques et y acheter, y
  compris celles qui ne devraient être accessibles qu'après une quête. Ne compte pas sur
  la manière dont une boutique s'ouvre pour restreindre qui y achète ; restreins-le par
  les prix, le stock et la rotation.
- **Le stock et les quotas par joueur peuvent être dépassés.** `canPurchase` contrôle le
  stock et le quota par joueur sans tenir compte de la quantité, qui peut monter à 64 :
  un seul achat peut dépasser l'un comme l'autre. Traité dans
  [Les contrôles de stock ignorent la quantité](#les-contrôles-de-stock-ignorent-la-quantité).
- **Les commandes d'achat sont visibles des joueurs.** Le payload `SHOP_VIEW` est
  construit à partir de `ShopItem.toJson()`, qui inclut le champ `commands` de chaque
  item. Ce qu'un administrateur met dans une commande d'achat — une récompense cachée,
  une coordonnée, un nom de tag — est lisible par un client modifié. N'y mets rien que tu
  ne montrerais pas à l'acheteur.
- **Les boutiques morpho vendent tout le catalogue.** Dans une boutique avec
  `morphoCatalog: true`, `handlePurchase` passe la main à `handleMorphoPurchase` avant
  que `canPurchase` ne s'exécute. Ce chemin vérifie uniquement que l'identifiant est de
  la forme `morpho_<id>`, que le catalogue connaît `<id>`, que le joueur ne le possède
  pas encore, et le solde. N'importe quelle entrée du catalogue est donc achetable à son
  prix catalogue, y compris celles que la vue de la boutique écarte parce qu'elles ne
  sont pas marquées disponibles auprès des NPC. Voir
  [le parcours du catalogue morpho](purchases.fr.md#le-parcours-du-catalogue-morpho).
- **Les identifiants de boutique ne sont pas validés sur disque.** `shop_create:` et
  `shop_delete:` transmettent l'identifiant tel quel à `Path.resolve(id + ".json")` :
  `..` et `/` ne sont pas refusés. Seuls les opérateurs peuvent encore l'atteindre, et
  ils peuvent déjà faire bien davantage ; ce chemin mérite tout de même le contrôle de
  type `isValidId` que `MarketApi` applique côté dashboard.

### Le paiement et la livraison ne sont pas atomiques

Décrit étape par étape dans [les achats](purchases.fr.md). En version courte : les coûts
sont prélevés avant la livraison des sorties, et trois cas laissent un joueur débité sans
rien en échange — un identifiant d'item de sortie absent du registre, une exception pendant
la livraison, et un `CheeseStoreHook.deduct` qui renvoie false après que le contrôle de
solde est passé. Les deux premiers journalisent bruyamment ; le troisième non.

### `AssetManager` résout le mauvais dossier

`AssetManager.init(worldDataDir)` existe et rien ne l'appelle : la classe retombe donc sur
un nom de dossier `world` codé en dur, tandis que `ShopManager` utilise le vrai chemin de
sauvegarde. Sur un serveur dont le `level-name` n'est pas `world`, les images téléversées
et les définitions de boutiques vivent dans deux arborescences différentes. Une ligne dans
`CheeseMarket.onInitialize` suffirait à régler cela.

### `reset-stock` ne fait rien

`POST …/items/:itemId/reset-stock` répond `{"reset":true}` et n'a pas de corps. On croirait
que ça a marché.

### Trois fonctionnalités configurables ne sont pas implémentées

`displayMode` (`TABS`/`FLAT`/`FEATURED`), `showWelcomeMessage`/`welcomeMessage`, et
l'élément de mise en page `IMAGE` sont tous éditables, stockés, servis et ignorés.
L'absence de barre d'onglets implique aussi que **seul le premier set actif d'une boutique
est atteignable en jeu**, et c'est le point à retenir : un administrateur peut construire
un deuxième set actif et le regarder ne jamais apparaître.

### Clés de langue inutilisées

Dix-huit des 39 clés de `assets/cheesemarket/lang/en_us.json` ne sont référencées par
aucun code Java : tout le bloc `cheesemarket.command.*` (remplacé par
`cheesemarket.cmd.*`), tout le bloc `cheesemarket.purchase.*` (remplacé par
`cheesemarket.gui.*`), et tout le bloc `cheesemarket.tooltip.*` (les infobulles sont
désormais construites à partir des vraies infobulles vanilla).
`cheesemarket.purchase.items_dropped` est celle qui vaudrait la peine d'être ressuscitée :
des items *sont* lâchés aux pieds d'un joueur et personne ne le lui dit.

Dans l'autre sens, plusieurs chaînes vues par les joueurs sont du français codé en dur en
Java plutôt que des clés de langue : `Confirmer`, `Fermer`, `Aucun article.`, le message
d'échec de livraison, les messages morpho, et les libellés `Gratuit`, `Coût:` et
`Quantité:` des infobulles. Un client en
anglais y voit du français. Les libellés de mode et de rotation — `Illimité`,
`Par joueur`, `Quotidien`, `Toujours actif` et compagnie — sont eux aussi du français en
dur, mais seuls l'écran d'administration et le dashboard les affichent.

### Code mort

- `ShopManager.logTransaction(…, String type, …)` — la surcharge publique `BUY`/`SELL` —
  n'est jamais appelée. Il n'existe aucun parcours de revente dans le mod, malgré
  `shop_sell.png` et les dossiers de textures `*_sell/` dans le jar.
- `ShopManager.recordPurchase(shopId, setId, itemId, uuid)` — la surcharge à quatre
  arguments — n'est jamais appelée.
- `MorphoShopRenderer` écrit `rotation.scheduleType` dans le set synthétique, et
  `RotationConfig.fromJson` lit `mode`. La clé est ignorée ; c'est le défaut `ALWAYS` qui
  fait fonctionner le set synthétique malgré tout.
- Le commentaire de documentation de `ShopPayloads` liste une action `picker_search` et un
  `set_manual_toggle` ; les deux existent, mais il liste aussi des préfixes réservés au
  client, `_scroll` et `_setmode`, que `ShopViewScreen` ne traite plus.

### Les contrôles de stock ignorent la quantité

`canPurchase` ne prend pas de quantité : un achat unique peut donc dépasser un stock
global ou un quota par joueur. Traité dans
[les achats](purchases.fr.md#la-quantité-ne-fait-pas-partie-du-contrôle-de-stock).

---

## Points d'extension

Si tu ajoutes à ce mod plutôt que de le lire :

- **Un nouveau champ au niveau boutique.** Ajoute-le à `ShopData` avec une valeur par
  défaut dans le constructeur, à `toJson`/`fromJson`, à `MarketApi.applyShopEdits`, et à
  `ShopGuiHandler.buildPlayerShopViewJson` si le client en a besoin. Les deux méthodes
  `applyShopEdits` — l'une dans `MarketApi`, l'autre dans `ShopGuiHandler` — sont des
  copies distinctes qui divergent facilement ; celle de `ShopGuiHandler` ignore déjà les
  couleurs de boutons, `morphoCatalog` et `layout`.
- **Une nouvelle monnaie.** Suis `CheeseStoreHook` : un hook de réflexion, un prédicat
  d'identifiant, et quatre points de contact dans `ShopGuiHandler.deductCosts` (phase de
  vérification, phase de prélèvement, `countInInventory`, `removeFromInventory`), plus une
  branche dans `ShopViewScreen` pour l'icône.
- **Un nouveau type d'élément de mise en page.** Ajoute-le à `ShopLayout.ElementType`,
  apprends à la boucle d'éléments personnalisés de `ShopViewScreen` à le dessiner, et
  ajoute-le à `MK_ELEMENT_TYPES` dans le `view-market.js` du dashboard. Note que la boucle
  fait aujourd'hui un `continue` sur trois types, dont deux sont dessinés ailleurs et un
  n'est pas dessiné du tout.
- **Une nouvelle route REST.** Ajoute-la à `MarketApi.handle` avant le 404 final, valide
  les identifiants avec `isValidId` en premier, et enveloppe toute mutation dans
  `WebDashboard.executeOnServerThread`. Le rôle vient gratuitement du dispatch de
  CheeseDash : `GET` est lisible par tout le monde, tout le reste exige `OPERATOR`. Deux
  limites en découlent. Une route qui modifie quelque chose ne doit pas être exposée en
  `GET`, parce que le dispatch ne regarde que la méthode HTTP et ne la garderait pas. Et
  si la route mérite `SUPER_ADMIN`, le handler doit appeler `WebDashboard.requireAdmin`
  lui-même — le dispatch ne monte jamais la barre plus haut qu'`OPERATOR`.
- **Un nouveau message WebSocket.** Copie la diffusion protégée à la fin de
  `handlePurchase` : vérifie `isModLoaded("cheesedash")`, attrape `NoClassDefFoundError`,
  et publie sur le canal `market`.

---

← [API dashboard](dashboard.fr.md) · [Dépannage](troubleshooting.fr.md) →
