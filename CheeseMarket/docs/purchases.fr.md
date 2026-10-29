[← CheeseMarket](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](purchases.md)

# Achats et transactions

L'achat est la seule chose, dans ce mod, qui déplace de la vraie valeur : c'est donc la
partie à lire ligne par ligne. Tout se passe dans une seule méthode,
`ShopGuiHandler.handlePurchase`, sur le thread serveur, dans un ordre fixe : valider,
vérifier la place en inventaire, prendre le paiement, livrer, exécuter les commandes,
enregistrer. Aucune transaction de base de données n'enveloppe l'ensemble. Si une étape
postérieure au paiement échoue, le paiement reste pris — le mod le journalise bruyamment
et dit au joueur d'aller voir un admin.

Cette page documente ce que le code fait réellement à chaque étape, y compris les trois
façons dont un achat peut mal finir.

---

## Comment une demande d'achat arrive au serveur

Le joueur clique sur une carte, un panneau de confirmation s'ouvre, il règle une
quantité et clique sur **Confirmer**. Le client envoie alors un seul payload :

```text
cheesemarket:shop_action   →   "shop_buy:<shopId>:<setId>:<itemId>:<quantity>"
```

Le receiver enregistré dans `CheeseMarket.onInitialize` enveloppe le traitement dans
`context.server().execute(...)` : l'achot complet tourne donc sur le thread serveur et ne
court jamais contre un tick.

La quantité est analysée côté serveur puis bornée : `Math.max(1, Math.min(64, …))`. Un
quatrième segment absent vaut 1, et un segment illisible vaut aussi 1 (`parseInt` renvoie
1 en cas d'échec). Un paquet forgé ne peut donc pas réclamer 10 000 unités.

⚠️ Il n'y a **aucun contrôle de permission et aucun contrôle de distance** sur ce
payload. Tout client capable d'envoyer `cheesemarket:shop_action` peut acheter dans
n'importe quelle boutique dont il connaît l'identifiant, sans qu'on lui ait jamais
montré cette boutique. Le même receiver accepte aussi les actions d'administration —
voir [l'architecture](architecture.fr.md#le-canal-dactions-nest-pas-authentifié).

---

## Étape 1 — l'éligibilité

`ShopManager.canPurchase(shopId, setId, itemId, playerUuid)` renvoie un
`PurchaseResult` :

| Résultat | Quand |
|---|---|
| `SHOP_NOT_FOUND` | aucune boutique chargée sous cet identifiant |
| `SET_INACTIVE` | le set n'existe pas, **ou** sa rotation dit qu'il n'est pas actif maintenant |
| `ITEM_DISABLED` | l'item n'existe pas, **ou** `enabled` vaut false |
| `OUT_OF_GLOBAL_STOCK` | mode `GLOBAL` et le compteur restant est à zéro ou en dessous |
| `PLAYER_LIMIT_REACHED` | mode `PER_PLAYER` ou `PER_PLAYER_RESET` et ce joueur a déjà épuisé son quota |
| `OK` | rien de ce qui précède |

`INSUFFICIENT_FUNDS` existe dans l'énumération mais `canPurchase` ne le renvoie jamais.
L'argent est vérifié plus tard, à l'étape 3.

Tout résultat autre que `OK` envoie une ligne rouge en chat plus un payload
`PURCHASE_RESULT` portant le nom de l'énumération, puis s'arrête. Rien n'a été touché.

| Résultat | Message en chat (fr_fr) |
|---|---|
| `OUT_OF_GLOBAL_STOCK` | `Stock épuisé pour cet item.` |
| `PLAYER_LIMIT_REACHED` | `Vous avez atteint votre limite d'achat pour cet item.` |
| `ITEM_DISABLED` | `Cet item n'est pas disponible.` |
| `SET_INACTIVE` | `Ce set n'est pas actif en ce moment.` |
| `SHOP_NOT_FOUND` et tout le reste | `Achat impossible.` |

### La quantité ne fait pas partie du contrôle de stock

`canPurchase` ne prend aucun argument de quantité. Un item en mode `GLOBAL` avec une
seule unité restante accepte un achat de 64 : le contrôle voit `remaining > 0` et passe,
puis `recordPurchase` applique `Math.max(0, remaining - quantity)` et borne le compteur à
zéro. Le joueur obtient 64 unités pour 64 fois le prix. C'est pareil pour les limites par
joueur : une limite de 1 n'empêche pas un achat unique de 64.

Si cela compte sur ton serveur, donne à ces items un coût que le joueur ne peut pas payer
64 fois, ou considère la limite comme une limite par transaction plutôt que comme un
plafond dur. C'est un vrai manque, pas une subtilité de lecture.

---

## Étape 2 — la place en inventaire

`canFitOutputs` simule l'insertion avant tout paiement, ce qui est le bon ordre et la
raison pour laquelle un achat refusé pour inventaire plein ne coûte rien.

La simulation copie les 36 emplacements de `PlayerInventory.main`, remplit d'abord les
piles compatibles existantes, puis les emplacements vides, et renvoie false s'il reste
quelque chose. Sur un false, le joueur reçoit
`Ton inventaire est plein ! Libère de la place avant d'acheter.` et un
`PURCHASE_RESULT` valant `INVENTORY_FULL`.

Trois limites de cette simulation méritent d'être connues :

- Seuls les 36 emplacements principaux comptent. La main secondaire et les emplacements
  d'armure sont ignorés, à la fois comme destination et comme piles existantes.
- Une sortie `cheeseitems:*` est supposée empiler jusqu'à 64. Si l'item de base empile par
  1 ou par 16, la simulation est optimiste.
- Un identifiant d'item inconnu est sauté par un `continue` : il ne bloque donc jamais
  l'achat — ce qui est précisément le cas où le joueur se fait débiter pour rien à
  l'étape 4.

---

## Étape 3 — le paiement

`deductCosts` procède en quatre phases : tout est vérifié avant que quoi que ce soit ne
soit prélevé. Un false renvoyé pendant les phases de vérification signifie qu'aucune
valeur n'a bougé, et le joueur reçoit
`Pas assez de ressources pour acheter x<quantité>.` avec un `PURCHASE_RESULT` valant
`INSUFFICIENT_FUNDS`.

| Phase | Ce qu'elle fait |
|---|---|
| 0 | Pour chaque coût dont l'`itemId` est `cheesestore:fromage`, compare `count × quantity` au solde CheeseStore |
| 1 | Pour chaque coût en item ordinaire, compte les piles correspondantes dans l'inventaire et compare à `count × quantity` |
| 2 | Pour les coûts en pièces Numismatic Overhaul, additionne les pièces de l'inventaire et la bourse |
| 3a | Prélève le fromage, puis annonce au joueur `🧀 -<n> Crédits (solde : <solde>)` |
| 3b | Retire les coûts en items ordinaires de l'inventaire |
| 4 | Retire les pièces de l'inventaire d'abord, puis prend le reliquat dans la bourse, en bronze |

La façon dont chaque type de coût est compté et consommé est détaillée dans
[la section monnaie du modèle de boutique](shop-model.fr.md#comment-un-prix-se-paie).

Deux choses contre lesquelles les phases ne protègent pas :

- `CheeseStoreHook.deduct` renvoie un booléen, et la phase 3a l'ignore. Si le
  portefeuille refuse le prélèvement après que la phase 0 a jugé le solde suffisant,
  l'achat continue comme s'il avait été payé.
- La phase 1 compte les coûts `cheeseitems:*` en scrutant le `custom_data` à la recherche
  du tag `cheeseitems`. Deux définitions CheeseItems partageant un tag seraient
  interchangeables comme moyen de paiement.

---

## Étape 4 — la livraison

`giveOutputs` parcourt la liste `outputs` et choisit une voie de livraison par entrée :

1. **Entrée portant une chaîne `components`** — livrée via la commande vanilla
   `give <joueur> <itemId><components> <n>`, exécutée depuis une source de commande
   silencieuse, par lots de 64. Une commande en échec est journalisée et la boucle de
   cette entrée s'arrête.
2. **Entrée `cheeseitems:*`** — la pile est construite via le hook de réflexion
   CheeseItems, par lots de 64, puis insérée avec `insertStack`. Si le hook renvoie autre
   chose qu'un `ItemStack`, le mod journalise
   `Failed to build CheeseItems stack for output: <id>` et s'arrête.
3. **Identifiant d'item simple** — `insertStack` par lots de la taille de pile maximale de
   l'item.

Dans les voies 2 et 3, un `insertStack` en échec retombe sur
`player.dropItem(stack, false)` : les items tombent aux pieds du joueur plutôt que de
disparaître. Le joueur n'en est **pas** averti. Une clé de langue existe pour ce message
(`cheesemarket.purchase.items_dropped`) et n'est jamais utilisée — voir
[les clés inutilisées](architecture.fr.md#clés-de-langue-inutilisées).

### Identifiant d'item inconnu

```text
[Market] Item not in registry, skipping delivery: mymod:thing for player Steve
```

Cette ligne signifie que le coût a déjà été prélevé à l'étape 3 et que la sortie
n'existait silencieusement pas. L'achat se poursuit malgré tout jusqu'au bout : les
commandes s'exécutent, le stock est décrémenté, une transaction est enregistrée et le
joueur reçoit `Achat réussi : <nom>`. Rien, dans le code, ne le rembourse.

C'est le mode de défaillance à surveiller après avoir retiré un mod dont une boutique
vend encore les items. Cherche `skipping delivery` dans le log du serveur avant que
quelqu'un ne se plaigne.

### Exception pendant la livraison

Toute l'étape 4 est enveloppée dans un try/catch. Sur exception, le serveur journalise

```text
[Market] CRITICAL: giveOutputs failed for player Steve buying a1b2c3d4 x1 — payment was already taken! Error: …
```

et le joueur reçoit en chat, sous forme de texte littéral et non de message traduisible :

```text
[Market] Erreur de livraison ! Contactez un admin pour un remboursement.
```

Le remboursement est manuel. La ligne de log porte le nom du joueur, l'identifiant de
l'item de boutique et la quantité, ce qui suffit à reconstituer ce qu'il faut rendre ;
l'entrée de transaction correspondante porte la liste complète des coûts.

### Resynchronisation forcée de l'inventaire

Juste après la livraison, le mod pousse chaque emplacement d'inventaire vers le client
sous forme de `ScreenHandlerSlotUpdateS2CPacket` avec l'identifiant de fenêtre `-2`. Cela
contourne le screen handler ouvert, et c'est ce qui rend un achat visible pendant que le
joueur est dans un écran de dialogue EasyNPC plutôt que dans son propre inventaire. Toute
exception y est avalée.

---

## Étape 5 — les commandes d'achat

`executeCommands` exécute chaque `CommandEntry` de l'item :

- `{player}` est remplacé par le nom de l'acheteur.
- Un `/` initial est retiré.
- La commande tourne `cmd.getCount() × quantity` fois.
- La source est `server.getCommandSource().withSilent()` — la source console, donc la
  commande a tous les droits, quel que soit le niveau de l'acheteur.
- Une commande qui lève une exception est journalisée en
  `Command failed for <joueur> : <commande>` et la boucle passe à la suivante.

Une commande d'achat s'exécute après le paiement et après la livraison. Si elle échoue,
le joueur garde les items et perd ce que la commande devait lui accorder.

---

## Étape 6 — l'enregistrement

`ShopManager.recordPurchase(shopId, setId, itemId, uuid, playerName, quantity)` fait
trois choses, dans cet ordre :

1. Met à jour les compteurs de stock — `GLOBAL` soustrait la quantité et borne à zéro,
   `PER_PLAYER` et `PER_PLAYER_RESET` ajoutent la quantité au compteur de ce joueur,
   `UNLIMITED` ne touche à rien.
2. Écrit `cheesemarket_stock.json`.
3. Ajoute une entrée de transaction à l'index 0 et écrit
   `cheesemarket_transactions.json`.

Les deux écritures sont synchrones, sur le thread serveur, à chaque achat. Deux petits
fichiers, formatés lisiblement : le coût est de quelques centaines de microsecondes — mais
ce sont bien deux écritures disque par achat, et c'est la raison du plafonnement du
journal de transactions.

### Ce que contient une entrée de transaction

```json
{
  "timestamp": "2026-10-29T14:03:11.284Z",
  "playerUUID": "9d4f…",
  "playerName": "Steve",
  "shopId": "general",
  "shopName": "Magasin général",
  "setId": "a1b2c3d4",
  "setName": "Consommables",
  "itemId": "e5f6a7b8",
  "itemName": "Potion de soin",
  "quantity": 3,
  "costs":   [ { "itemId": "numismatic-overhaul:silver_coin", "count": 5 } ],
  "outputs": [ { "itemId": "minecraft:potion", "count": 1 } ]
}
```

`costs` et `outputs` sont les listes **à l'unité** copiées depuis la définition de
l'item, non multipliées par `quantity`. Pour savoir ce qui a réellement été payé,
multiplie toi-même par `quantity`. `itemId` est l'identifiant interne de l'item de
boutique, `itemName` son nom d'affichage effectif au moment de l'achat.

La liste est tenue du plus récent au plus ancien et plafonnée à 500 entrées
(`MAX_TRANSACTIONS`) ; la 501e chasse définitivement la plus ancienne hors du fichier. Il
n'y a aucune archive. S'il te faut un historique de ventes au long terme, copie
`cheesemarket_transactions.json` périodiquement.

`ShopManager` expose aussi une surcharge publique `logTransaction(…, String type, …)` qui
écrit une entrée plus courte de forme `BUY`/`SELL`. Rien ne l'appelle. Il n'existe aucun
parcours de revente dans le mod, malgré les textures `shop_sell.png` et les dossiers
`*_sell/` livrés dans le jar.

---

## Étape 7 — les notifications

Après l'enregistrement, dans cet ordre :

1. Chat : `Achat réussi : <itemName>` (suivi de ` x<n>` quand la quantité dépasse 1), en
   vert.
2. Un payload `PURCHASE_RESULT` portant `purchaseResult: "OK"`, envoyé avant tout le reste
   pour que le client ferme le panneau de confirmation sans attendre.
3. Si CheeseDash est chargé, un message `market_purchase` sur le canal WebSocket
   `market` — payload documenté dans [la page dashboard](dashboard.fr.md#websocket).
   L'appel est protégé par `isModLoaded("cheesedash")` et enveloppé contre
   `NoClassDefFoundError` : une installation sans CheeseDash ne plante donc pas ici.
4. Un nouveau payload `SHOP_VIEW` avec `skipImages = true`, pour que la grille affiche les
   nouveaux chiffres de stock sans réexpédier le fond en base64 que le client possède
   déjà.

---

## Le parcours du catalogue morpho

Une boutique avec `morphoCatalog: true` n'atteint rien de ce qui précède.
`handlePurchase` détecte l'option sur la boutique brute et passe la main à
`handleMorphoPurchase`, car les items d'une telle boutique sont synthétisés au moment de
l'affichage et n'existent pas sur le disque : `ShopManager.canPurchase` ne trouverait rien
à valider.

Ce parcours, dans l'ordre :

1. L'identifiant d'item doit commencer par `morpho_`, sinon `INVALID_ITEM`.
2. L'entrée de catalogue est cherchée par réflexion dans
   `com.cheeseitems.morpho.MorphoCatalog`. Entrée absente → `ITEM_DISABLED`.
3. Si le joueur a déjà la morpho dans son codex, il reçoit
   `Vous possédez déjà cette morpho dans votre codex.` et `PLAYER_LIMIT_REACHED`.
4. Le solde de fromage est comparé au `priceCredits` de l'entrée ; trop faible donne
   `Solde insuffisant : <solde> / <prix> crédits.` et `INSUFFICIENT_FUNDS`.
5. `CheeseStoreHook.deduct` est appelé et **son résultat est vérifié** ici — un false met
   fin à l'achat avec `INSUFFICIENT_FUNDS`.
6. La morpho est accordée en exécutant `cheeseitem morpho grant <joueur> <morphoId>`
   depuis la source de commande du serveur. Une exception est journalisée en
   `Failed to grant morpho <id>: <message>` — et le fromage est déjà parti.
7. Un son est joué, `PURCHASE_RESULT: OK` est envoyé, et la vue matérialisée est
   réexpédiée pour que l'entrée apparaisse comme déjà possédée.

Ce parcours n'écrit **aucune entrée de transaction et aucune entrée de stock**. Les ventes
de morphos n'apparaissent pas dans la liste des transactions du dashboard et ne sont pas
comptées dans les diffusions `market_purchase`. L'argument de quantité est entièrement
ignoré : un achat accorde une morpho.

Tout cela dépend de noms de classes et de méthodes CheeseItems résolus par réflexion.
C'est une observation de la façon dont les deux mods s'emboîtent aujourd'hui, pas un
contrat : renomme `getPriceCredits` ou `hasInCodex` dans CheeseItems et ce parcours
s'arrête, en silence, avec une unique ligne d'avertissement au démarrage.

---

← [Éditeur de mise en page](layout-editor.fr.md) · [API dashboard](dashboard.fr.md) →
