[← CheeseMarket](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](shop-model.md)

# Le modèle de boutique

Trois objets s'emboîtent : une **boutique** contient des **sets**, un set contient des
**items**. Un fichier JSON par boutique sur le disque, et ce fichier est toute la
définition — il n'y a pas de catalogue global et aucun partage entre boutiques. Copie un
item dans deux boutiques et tu obtiens deux items indépendants qui se ressemblent.

Cette page décrit ce que chaque niveau stocke, comment un set décide s'il est ouvert,
comment la limite de stock d'un item fonctionne, et comment un prix se paie réellement.

---

## Où tout cela vit

```text
<world>/data/cheesemarket/
├── shops/
│   ├── general.json              un fichier par boutique, nommé d'après son id
│   └── metamorph.json
├── cheesemarket_stock.json       compteurs d'exécution, hors définitions
├── cheesemarket_transactions.json
├── assets/<shopId>/*.png         images téléversées pour la mise en page
└── templates/*.json              mises en page enregistrées
```

Le dossier est résolu au `SERVER_STARTED` depuis
`server.getSavePath(WorldSavePath.ROOT).resolve("data")` : il suit donc ton `level-name`
au lieu d'être figé. Chaque enregistrement réécrit tout le fichier de boutique avec le
formatage lisible de Gson, donc les fichiers restent consultables et comparables.

---

## Boutique

Une boutique est un `ShopData`. L'identifiant vient de l'argument de `/shop create` ou du
dashboard et devient le nom du fichier ; il n'est jamais modifiable ensuite.

| Champ | Type | Défaut | Effet |
|---|---|---|---|
| `id` | chaîne | — | identité et nom de fichier |
| `name` | chaîne | `Nouveau shop` | titre affiché dans l'interface joueur |
| `description` | chaîne | `""` | sous-titre sous le titre |
| `displayMode` | `TABS` \| `FLAT` \| `FEATURED` | `TABS` | **non implémenté** — voir plus bas |
| `showWelcomeMessage` | booléen | `false` | envoyé au client, jamais dessiné |
| `welcomeMessage` | chaîne | `Bienvenue dans le shop !` | envoyé au client, jamais dessiné |
| `titleColor` | chaîne | `gold` | un nom de couleur vanilla ou un triplet hexadécimal, pour le titre |
| `bgColor` | chaîne | `1A1A2E` | hexadécimal `RRGGBB` |
| `borderColor` | chaîne | `2A5A8A` | hexadécimal `RRGGBB` |
| `accentColor` | chaîne | `4AC8EA` | hexadécimal `RRGGBB` |
| `btnBuyColor` | chaîne | `1A5C2A` | fond du bouton de confirmation |
| `btnBuyTextColor` | chaîne | `FFFFFF` | texte du bouton de confirmation |
| `btnCloseColor` | chaîne | `1E3A5F` | fond du bouton d'annulation |
| `btnCloseTextColor` | chaîne | `CCCCCC` | texte du bouton d'annulation |
| `layout` | objet ou absent | absent | positions des éléments — voir [l'éditeur de mise en page](layout-editor.fr.md) |
| `morphoCatalog` | booléen | `false` | remplace les sets de la boutique par le catalogue morpho CheeseItems au moment de l'affichage |
| `sets` | tableau | `[]` | les sets, dans l'ordre du fichier |

`displayMode` est analysé, stocké, exposé dans l'API et envoyé au client, et le client ne
le lit jamais. `ShopViewScreen` conserve un champ `displayMode` qu'aucun code de rendu ne
consulte. En pratique, toute boutique se comporte comme une boutique à set unique :
`ShopViewScreen` affiche le set à l'index `tabIdx`, `tabIdx` part à zéro, et rien dans
l'interface joueur ne le change jamais. Un `BUTTON` de mise en page ne peut pas le changer
non plus — la seule action de bouton personnalisé que le client comprend est `close`.

**Donc, dans l'interface joueur, seul le premier set actuellement actif est
atteignable.** Conçois tes boutiques en conséquence : un set actif à la fois, la rotation
décidant lequel. Le deuxième set actif est chargé, validé, achetable par un payload forgé,
et invisible.

`morphoCatalog` est une autre bête, décrite dans
[les achats](purchases.fr.md#le-parcours-du-catalogue-morpho).

---

## Set

Un set est un `ShopSet` — un groupe nommé d'items avec une règle de rotation. Son
identifiant est un fragment hexadécimal aléatoire de 8 caractères généré à la création.

| Champ | Type | Défaut | Effet |
|---|---|---|---|
| `id` | chaîne | 8 caractères aléatoires | identité au sein de la boutique |
| `name` | chaîne | `Nouveau set` | affiché partout où un set est listé |
| `rotation` | objet | voir plus bas | quand le set est actif |
| `items` | tableau | `[]` | les items |
| `sortOrder` | entier | index à la création | ordre ; `getActiveSets()` trie dessus |

`getActiveSets()` filtre sur `rotation.isCurrentlyActive()` et trie par `sortOrder`, et
`getEnabledItems()` filtre sur `enabled` et trie par `sortOrder`. Ces deux méthodes sont
ce que le joueur voit ; le dashboard voit les listes non filtrées.

### Rotation

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `mode` | `ALWAYS` \| `SCHEDULE` \| `MANUAL` | `ALWAYS` | quelle règle s'applique |
| `scheduleStart` | `HH:mm` | `08:00` | ouverture de la fenêtre, `SCHEDULE` seulement |
| `scheduleEnd` | `HH:mm` | `20:00` | fermeture de la fenêtre, `SCHEDULE` seulement |
| `manualActive` | booléen | `true` | l'interrupteur, `MANUAL` seulement |

`SCHEDULE` compare `LocalTime.now()` — **l'horloge et le fuseau horaire de la machine
serveur**, pas le temps Minecraft et pas UTC. Une fenêtre dont la fin précède le début est
lue comme traversant minuit : `22:00` → `04:00` est actif de vingt-deux heures jusqu'à
quatre heures du matin. Si l'une des heures échoue à l'analyse,
`isInScheduleWindow` attrape l'exception et renvoie `true` : une faute de frappe rend donc
le set ouvert en permanence plutôt que fermé en permanence.

`MANUAL` se bascule depuis le dashboard (un `PUT` sur le set avec
`rotation.manualActive`) ou depuis l'écran d'administration en jeu. Une chaîne `mode`
inconnue retombe sur `ALWAYS`.

Le contrôle tourne à chaque construction de vue et à chaque achat : un set ferme donc
pendant qu'un joueur a sa boutique ouverte. La fenêtre se referme, et l'achat suivant
répond `Ce set n'est pas actif en ce moment.`

---

## Item

Un `ShopItem` est une carte de la grille. Son identifiant est aussi un fragment aléatoire
de 8 caractères.

| Champ | Type | Défaut | Effet |
|---|---|---|---|
| `id` | chaîne | 8 caractères aléatoires | identité au sein du set |
| `displayItem` | chaîne | `minecraft:paper` | l'icône |
| `displayName` | chaîne | `""` | nom de remplacement ; vide retombe sur l'identifiant de l'icône |
| `outputs` | tableau d'`ItemEntry` | `[]` | ce que le joueur reçoit |
| `costs` | tableau d'`ItemEntry` | `[]` | ce que le joueur paie |
| `commands` | tableau de `CommandEntry` | `[]` | commandes exécutées à l'achat |
| `stock` | objet | `UNLIMITED` | la limite — voir plus bas |
| `enabled` | booléen | `true` | false le cache aux joueurs et le garde dans le dashboard |
| `sortOrder` | entier | index à la création | position dans la grille |

`getEffectiveDisplayItem()` a une bizarrerie à connaître : la méthode traite
`minecraft:paper` comme « non défini ». Si `displayItem` est vide **ou littéralement
`minecraft:paper`**, l'icône retombe sur l'identifiant de la première sortie, et s'il n'y
a aucune sortie, sur le papier. Un item dont l'icône est réellement censée être du papier,
avec un diamant en sortie, affiche donc un diamant.

`getEffectiveName()` renvoie `displayName` quand il est renseigné, sinon l'identifiant de
l'icône débarrassé de son espace de noms, les tirets bas devenant des espaces
(`minecraft:diamond_sword` → `diamond sword`). Le client fait mieux : un `displayName`
vide lui fait chercher le nom traduit vanilla de l'icône.

### ItemEntry

Une entrée est `{ itemId, count }` avec une chaîne `components` facultative.

| Champ | Type | Effet |
|---|---|---|
| `itemId` | chaîne | un identifiant de registre, ou l'un des deux identifiants virtuels ci-dessous |
| `count` | entier | forcé à 1 minimum, à la construction comme dans `setCount` |
| `components` | chaîne, facultative | un bloc de composants MC 1.21.1, par exemple `[custom_data={quest:1}]` |

Une entrée portant `components` est livrée via la commande vanilla `give` plutôt que par
insertion directe : c'est ainsi qu'une boutique vend un item avec un NBT qu'elle ne
saurait pas construire autrement. Les composants ne sont honorés que sur les
**sorties** : la reconnaissance de paiement compare des types d'items et ne regarde jamais
`components`, donc un coût porteur de NBT est payé avec n'importe quelle pile simple du
même item.

### CommandEntry

`{ command, count }`. Dans la chaîne, `{player}` est remplacé par le nom de l'acheteur, un
`/` initial est retiré, et la commande tourne `count × quantity` fois depuis la source de
commande console. Détails et gestion des échecs dans
[les achats](purchases.fr.md#étape-5--les-commandes-dachat).

---

## Stock et limites

`StockConfig` vit sur l'item ; les compteurs vivent à part, dans
`cheesemarket_stock.json`, avec des clés en chaîne qui survivent à l'édition d'une
boutique.

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `mode` | `UNLIMITED` \| `GLOBAL` \| `PER_PLAYER` \| `PER_PLAYER_RESET` | `UNLIMITED` | quel compteur s'applique |
| `globalStock` | entier | `100` | total pour tout le serveur, `GLOBAL` seulement ; le setter force ≥ 1 |
| `perPlayerLimit` | entier | `1` | unités par joueur, les deux modes `PER_PLAYER` ; le setter force ≥ 1 |
| `resetType` | `DAILY` \| `WEEKLY` \| `MONTHLY` \| `CUSTOM` | `DAILY` | cadence de remise à zéro, `PER_PLAYER_RESET` seulement |
| `resetIntervalSeconds` | long | `86400` | cadence `CUSTOM` ; le setter force ≥ 60 |

Les compteurs sont indexés par `shopId:setId:itemId` pour le stock global et par
`shopId:setId:itemId:playerUuid` pour les comptes par joueur. Change l'identifiant d'un
item — ce que le dashboard ne fait jamais, mais qu'un fichier édité à la main pourrait
faire — et son historique est orphelin, pas migré.

### Comment la remise à zéro fonctionne vraiment

`DAILY`, `WEEKLY` et `MONTHLY` ne sont **pas** des remises à zéro calendaires, malgré ce
que dit le commentaire de classe de `StockConfig` lui-même (« resets at midnight server
time », « every Monday midnight », « on the 1st of each month »). `processResets` compare
`now - lastReset` à un intervalle fixe :

| `resetType` | Intervalle utilisé |
|---|---|
| `DAILY` | 86 400 s |
| `WEEKLY` | 604 800 s |
| `MONTHLY` | 2 592 000 s (30 jours) |
| `CUSTOM` | `resetIntervalSeconds` |

Un quota acheté pour la première fois à 17 h 40 se réinitialise donc à 17 h 40, et un
quota `MONTHLY` dérive d'un ou deux jours par mois. C'est le code qui dit vrai ici ; le
commentaire relève de l'intention.

La remise à zéro est aussi **paresseuse**. `processResets` ne tourne que lorsque
quelqu'un appelle `canPurchase` ou `stockDisplay` pour cet item précis. Un item que
personne ne regarde ne se réinitialise jamais, et le premier joueur à ouvrir la boutique
après l'intervalle est celui qui déclenche l'effacement pour tout le monde. `lastReset`
part à 0, soit 1970, donc le premier accès réinitialise toujours et pose l'horodatage
réel.

Quand une remise à zéro se déclenche, toutes les clés par joueur portant le préfixe
`shopId:setId:itemId:` sont retirées de la table.

### Réinitialiser à la main

`POST /api/v1/market/shops/:shopId/sets/:setId/items/:itemId/reset-stock` répond
`{"reset":true}` et **ne fait absolument rien** — le handler n'a pas de corps. Pour
réellement vider un compteur : arrête le serveur, édite `cheesemarket_stock.json`,
redémarre. Le fichier est lu une fois au chargement et écrit à chaque achat, donc une
édition à chaud se fait écraser.

### Ce que le joueur voit

`stockDisplay` construit la petite ligne sous la carte :

| Mode | Affiché |
|---|---|
| `UNLIMITED` | `∞` |
| `GLOBAL` | `restant/total`, par exemple `42/100` |
| `PER_PLAYER` | `restant/limite (vous)` |
| `PER_PLAYER_RESET` | `restant/limite (<libellé de reset>)`, par exemple `2/3 (Quotidien)` |

Ces libellés viennent de `StockConfig.resetLabel()` et sont du français codé en dur dans
la source Java, pas dans un fichier de langue.

---

## Comment un prix se paie

Le prix d'un item, c'est sa liste `costs`, chaque entrée multipliée par la quantité
achetée. Il n'y a aucun champ de prix et aucun réglage de monnaie : la monnaie *est*
l'identifiant d'item que tu mets dans la liste des coûts. Il en existe trois sortes, et
chacune se règle différemment.

### Items ordinaires

Tout identifiant de registre qui n'est pas un des cas particuliers ci-dessous est compté
et consommé depuis l'inventaire du joueur par type d'item, sans regarder les composants ni
le NBT. Un coût `cheeseitems:*` est l'exception : il est reconnu en scrutant le
`custom_data` de chaque pile à la recherche d'une chaîne `cheeseitems` égale à
l'identifiant privé de son espace de noms.

### Pièces Numismatic Overhaul

`numismatic-overhaul:bronze_coin`, `silver_coin` et `gold_coin` sont reconnues par
`NumismaticHook`. Quand Numismatic Overhaul est chargé, elles sont payées **d'abord
depuis l'inventaire, puis depuis la bourse** : le hook compte les pièces en vrac dans
l'inventaire, les prend, convertit le reliquat en bronze (bronze 1, argent 100, or
10 000) et le prélève sur le composant de bourse par réflexion.

Quand Numismatic Overhaul n'est **pas** chargé, `isAvailable()` est faux et les
identifiants de pièces retombent au rang d'items d'inventaire ordinaires — ce qui veut
dire qu'on ne peut pas les trouver du tout, puisque les items eux-mêmes n'existent pas, et
l'achat échoue pour fonds insuffisants. Le hook journalise sa décision une fois, au
premier usage :

```text
[Market] Numismatic Overhaul detected — purse support enabled
[Market] Numismatic Overhaul not found — purse support disabled
```

Le hook atteint `com.glisco.numismaticoverhaul.ModComponents.CURRENCY` et
`CurrencyComponent.getValue()` / `modify(long)` par leur nom. C'est une observation du
Numismatic Overhaul actuel, pas un contrat : un renommage de leur côté éteint le support
de la bourse avec une ligne d'avertissement, et les achats exigent alors les pièces en
vrac dans l'inventaire.

### Fromage (crédits CheeseStore)

L'identifiant `cheesestore:fromage` est une monnaie virtuelle, pas un item.
`CheeseStoreHook` réfléchit dans `com.cheesestore.WalletManager` pour
`getBalance(UUID)` et `deduct(UUID, String, long)`. Dans l'interface, il s'affiche avec sa
propre texture de fromage et porte le libellé `Crédit`.

En l'absence de CheeseStore, `getBalance` renvoie 0, donc tout coût en fromage rend l'item
inachetable. Le mod ne cache pas l'item et n'avertit pas l'administrateur ; le joueur, lui,
ne peut tout simplement pas acheter.

```text
[Market] CheeseStore detected — fromage support enabled
[Market] CheeseStore not found — fromage support disabled
```

### Items gratuits

Une liste `costs` vide est légale. `deductCosts` n'a rien à vérifier et rien à prélever,
renvoie true, et le joueur reçoit les sorties pour rien. C'est ainsi qu'on construit un
distributeur de récompenses limité seulement par un quota par joueur.

---

← [Configuration](configuration.fr.md) · [Interface joueur](player-interface.fr.md) →
