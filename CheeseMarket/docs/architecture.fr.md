[← CheeseMarket](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](architecture.md)

# Architecture

Le jar contient 109 fichiers source Java. **23 d'entre eux sont CheeseMarket.** Les 86
autres, environ 18 900 lignes, sont une copie dupliquée du package `com.cheeseguard`,
compilée, livrée, et jamais exécutée. Lis la section qui lui est consacrée avant de
chercher le système d'événements ici.

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

com.cheeseguard                  86 fichiers, mort — voir plus bas
```

---

## Qui possède quoi

**`ShopManager`** est la seule autorité mutable. Un singleton paresseux, remplacé en bloc
par `init(worldDataDir)` au `SERVER_STARTED`. Il possède la table des boutiques, les trois
tables de stock et la liste des transactions ; c'est la seule classe qui écrit le JSON des
boutiques, le fichier de stock ou le fichier de transactions. Rien d'autre ne garde une
référence à un `ShopData` d'un tick sur l'autre.

Ses collections sont volontairement hétérogènes : `shops` est un simple `LinkedHashMap`
parce que seul le thread serveur y touche, tandis que les tables de stock sont des
`ConcurrentHashMap` et la liste de transactions une `CopyOnWriteArrayList`, parce que des
threads HTTP les lisent pendant la construction des réponses d'API.

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

Les lectures ont lieu au `SERVER_STARTED` et sur `/shop reload`, et nulle part ailleurs. Un
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
  dans un tableau à un élément. Les handlers GET lisent directement les collections
  thread-safe depuis le thread HTTP, et c'est pour cela que `transactions` est en
  copy-on-write.

`AssetManager` fait exception : il lit et écrit les fichiers PNG directement depuis le
thread HTTP. C'est sans danger — ces fichiers ne sont jamais touchés par le thread
serveur — mais cela veut dire qu'un disque lent bloque une requête HTTP plutôt qu'un tick.

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

---

## Mixins

**Aucun.** Le mod livre un `cheeseguard.mixins.json` listant 30 classes de mixins, et
`fabric.mod.json` n'a **aucune clé `mixins`** : ce fichier n'est donc jamais chargé et pas
un seul de ces mixins n'est appliqué. Voir plus bas.

CheeseMarket lui-même n'a besoin d'aucun mixin : il s'accroche au jeu par les événements de
la Fabric API, le callback d'enregistrement des commandes et ses propres payloads.

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
s'éteint toute seule avec une ligne `WARN` au démarrage et plus aucune plainte ensuite.
Cherche `[Market] Failed to initialize` dans le log après avoir mis à jour l'un de ces
mods.

`fabric.mod.json` liste `cheesestore` et `cheeseitems` sous `suggests`. Numismatic Overhaul
n'est pas listé du tout, pas même en suggestion, alors que le mod réfléchit dedans par son
identifiant de mod.

---

## Le package `com.cheeseguard` dupliqué

86 fichiers Java, environ 18 900 lignes : `CheeseGuardMod`, tout l'arbre `data/` et
`logic/` (régions, dimensions, événements, timelines, mini-jeux, votes, jeux de chunks),
onze classes de GUI en coffre, 30 mixins, et un `GuiSyncPayload`. C'est une copie de
CheeseGuard datant de la séparation des deux mods.

**Ce qui est compilé :** tout. C'est sous `src/main/java`, donc `javac` construit chaque
classe et les 86 finissent dans le jar.

**Ce qui s'exécute :** rien.

- `fabric.mod.json` déclare trois entrypoints — `com.cheesemarket.CheeseMarket`,
  `com.cheesemarket.CheeseMarketClient` et le module dashboard.
  `com.cheeseguard.CheeseGuardMod` n'en fait pas partie, donc son `onInitialize` n'est
  jamais appelé et aucun de ses listeners, commandes ou managers ne s'enregistre jamais.
- `fabric.mod.json` n'a aucune clé `mixins`, donc `cheeseguard.mixins.json` n'est jamais
  lu et aucun des 30 mixins n'est appliqué. Le fichier dort dans le jar comme du JSON
  inerte.
- Rien dans `com.cheesemarket` n'importe ni ne référence `com.cheeseguard`, et rien dans
  `com.cheeseguard` ne référence `com.cheesemarket`. Cherche dans les deux sens : zéro
  occurrence. Les deux packages partagent un jar et rien d'autre.

Le package est donc du poids mort : il gonfle le jar, il refusera de compiler le jour où
un mapping Yarn qu'il utilise changera, et il trompe quiconque lit l'arborescence. Il ne
change **pas** le comportement, et il n'entre pas non plus en conflit avec le vrai mod
CheeseGuard à l'exécution, puisque les noms de classes sont identiques mais ne sont jamais
chargés ni comme cibles de mixins ni comme entrypoints.

L'ancien README affirmait que la duplication portait « spécifiquement sur
`logic/TimelineExecutor.java` et `gui/MainGui.java` » et qu'elle se trouvait « sur le
chemin d'exécution des événements partagé avec CheeseGuard ». Les deux affirmations sont
fausses : c'est le package entier, et il n'est sur aucun chemin d'exécution. Le plan de
publication a décidé de le conserver tel quel plutôt que de risquer un nettoyage que
personne ne pouvait tester : il reste donc là, documenté plutôt que retiré.

Si tu le retires, supprime `src/main/java/com/cheeseguard/` et
`src/main/resources/cheeseguard.mixins.json` ensemble, et n'oublie pas
`src/main/resources/assets/cheeseguard/lang/`, encore livré lui aussi.

---

## Faiblesses connues

### Le canal d'actions n'est pas authentifié

`CheeseMarket.onInitialize` enregistre un unique receiver global pour
`cheesemarket:shop_action` et confie chaque payload à `ShopGuiHandler.handle` sans aucun
contrôle de permission. `ShopGuiHandler` dispatche ensuite sur la chaîne d'action, et cet
espace de chaînes inclut les actions d'administration : `shop_create`, `shop_delete`,
`set_delete`, `item_save`, `item_delete`, `item_toggle` et les autres.

Un client modifié peut donc supprimer une boutique, réécrire le prix d'un item ou activer
un set, sans avoir jamais été opérateur. Le contrôle de permission de `/cmadmin` protège la
*commande* qui ouvre l'écran d'administration, pas les actions que cet écran envoie.

La même chose vaut pour `shop_buy` : n'importe quel client peut acheter dans n'importe
quelle boutique dont il connaît l'identifiant, sans vérification que la boutique lui a été
ouverte un jour et sans test de distance.

C'est le problème le plus grave du mod. Il n'est pas corrigé ici parce que le corriger
suppose de décider ce qu'une action d'administration a le droit d'être — le correctif
évident est un garde `player.hasPermissionLevel(2)` sur toute action qui n'est pas
`shop_buy`, `shop_tab` ou `open_shop_player`, plus un relevé par joueur de la dernière
boutique ouverte pour valider `shop_buy`.

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

Dix-huit des 37 clés de `assets/cheesemarket/lang/en_us.json` ne sont référencées par
aucun code Java : tout le bloc `cheesemarket.command.*` (remplacé par
`cheesemarket.cmd.*`), tout le bloc `cheesemarket.purchase.*` (remplacé par
`cheesemarket.gui.*`), et tout le bloc `cheesemarket.tooltip.*` (les infobulles sont
désormais construites à partir des vraies infobulles vanilla).
`cheesemarket.purchase.items_dropped` est celle qui vaudrait la peine d'être ressuscitée :
des items *sont* lâchés aux pieds d'un joueur et personne ne le lui dit.

Dans l'autre sens, plusieurs chaînes vues par les joueurs sont du français codé en dur en
Java plutôt que des clés de langue : `Confirmer`, `Fermer`, `Aucun article.`, le message
d'échec de livraison, les messages morpho, et les libellés de stock `Illimité`,
`Par joueur`, `Quotidien`, `Toujours actif` et compagnie. Un client en anglais y voit du
français.

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
  CheeseDash : `GET` est lisible par tout le monde, tout le reste exige `OPERATOR`.
- **Un nouveau message WebSocket.** Copie la diffusion protégée à la fin de
  `handlePurchase` : vérifie `isModLoaded("cheesedash")`, attrape `NoClassDefFoundError`,
  et publie sur le canal `market`.

---

← [API dashboard](dashboard.fr.md) · [Dépannage](troubleshooting.fr.md) →
