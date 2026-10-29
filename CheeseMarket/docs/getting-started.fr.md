[← CheeseMarket](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](getting-started.md)

# Premiers pas

À la fin de cette page, un joueur posté sur ton serveur ouvrira un panneau de boutique,
cliquera une carte, fera glisser un curseur de quantité, paiera cinq émeraudes et repartira
avec une pioche en diamant — et tu verras la vente dans la liste des transactions du
dashboard. Compte vingt minutes.

Il te faut un serveur Fabric 1.21.1 avec CheeseDash et CheeseMarket installés côté serveur,
CheeseMarket et CheeseDash installés sur ton propre client, et ton token de dashboard sous
la main. Si ce
n'est pas encore le cas, commence par [l'installation](installation.fr.md).

---

## 1. Créer la boutique

En jeu, en tant qu'opérateur :

```mcfunction
/shop create starter
```

```text
Shop créé : starter
```

Cela a écrit `<world>/data/cheesemarket/shops/starter.json` avec les valeurs par défaut et
une liste de sets vide. Vérifie qu'elle est chargée :

```mcfunction
/shop list
```

```text
── Shops CheeseMarket ──
starter — Nouveau shop (0 sets)
```

Le nom est `Nouveau shop` parce que la commande n'en prend pas. Tu corriges cela à l'étape
suivante.

---

## 2. L'ouvrir dans le dashboard

Va sur `http://<ton-serveur>:44311`, connecte-toi, et clique **CheeseMarket 🛒** dans la
barre latérale. La liste des boutiques affiche `starter` avec 0 set et 0 item. Clique
dessus.

Quatre onglets : **Configuration**, **Layout**, **Sets & Items**, **Transactions**.

Dans **Configuration**, renseigne :

- *Nom* → `Boutique de départ`
- *Description* → `Outils et consommables`

L'édition est un `PUT` sur la boutique et est enregistrée au fil de la frappe. Si tu
préfères passer par l'API :

```bash
curl -X PUT -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"name":"Boutique de départ","description":"Outils et consommables"}' \
  http://localhost:44311/api/v1/market/shops/starter
```

Ne touche pas au *mode d'affichage*. Il est stocké et le client l'ignore — voir
[le modèle de boutique](shop-model.fr.md#boutique).

---

## 3. Ajouter un set

Une boutique sans set n'affiche rien, parce que les items vivent dans des sets et que seul
un set actif est dessiné.

Dans **Sets & Items**, ajoute un set et nomme-le `Outils`. Laisse la rotation sur
`ALWAYS` — le set est ouvert en permanence, ce qui est ce que tu veux pour un premier test.

```bash
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"name":"Outils"}' \
  http://localhost:44311/api/v1/market/shops/starter/sets
```

La réponse porte l'identifiant généré du set — huit caractères hexadécimaux, quelque chose
comme `a1b2c3d4`. Note-le ; les routes d'items en ont besoin.

⚠️ Ajoute exactement **un** set pour l'instant. Une boutique peut en contenir plusieurs, et
seul le premier actif est atteignable dans l'interface joueur. Un deuxième set serait
invisible, ce qui est une façon déroutante de commencer.

---

## 4. Ajouter un item

Toujours dans **Sets & Items**, ajoute un item dans `Outils` et renseigne :

- *Item d'affichage* → `minecraft:diamond_pickaxe`
- *Nom d'affichage* → `Pioche de mineur`
- *Sortie* → `minecraft:diamond_pickaxe` × 1
- *Coût* → `minecraft:emerald` × 5
- *Stock* → `PER_PLAYER`, limite 2

Ou en un seul appel, en substituant l'identifiant de ton set :

```bash
curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{
        "displayItem": "minecraft:diamond_pickaxe",
        "displayName": "Pioche de mineur",
        "outputs": [{"itemId":"minecraft:diamond_pickaxe","count":1}],
        "costs":   [{"itemId":"minecraft:emerald","count":5}],
        "stock":   {"mode":"PER_PLAYER","perPlayerLimit":2},
        "enabled": true
      }' \
  http://localhost:44311/api/v1/market/shops/starter/sets/a1b2c3d4/items
```

Deux choses à comprendre tout de suite, parce qu'elles façonnent chaque item que tu
construiras :

- **La monnaie est l'identifiant d'item.** Il n'y a pas de champ de prix. `costs` est une
  liste d'identifiants et de quantités, et ce que tu y mets est ce que le joueur paie. Des
  émeraudes ici ; ce pourrait être `cheesestore:fromage` pour des crédits ou
  `numismatic-overhaul:silver_coin` pour des pièces. Voir
  [comment un prix se paie](shop-model.fr.md#comment-un-prix-se-paie).
- **Le `displayItem` est utilisé tel quel, papier compris.** Seul un `displayItem` vide
  retombe sur la première sortie — et l'éditeur du dashboard remplit un champ vide avec la
  première sortie à ta place. Un item créé par l'API sans `displayItem` garde la valeur
  par défaut, `minecraft:paper`, et affiche une feuille de papier.

---

## 5. Le regarder en jeu

```mcfunction
/shop open starter
```

Le monde s'assombrit et le panneau apparaît : le nom de ta boutique en doré, la description
en dessous, un bouton de fermeture en haut à droite, et une seule carte au milieu de la
grille montrant une pioche en diamant avec `5` et une icône d'émeraude en dessous. Survoler
la carte te donne la vraie infobulle de la pioche. La carte n'affiche aucun chiffre de
stock : le serveur en calcule un (`2/2 (vous)`) et l'envoie dans `stockDisplay`, mais le
client ne le dessine jamais.

Si rien ne se passe du tout, ton client n'a pas le mod. Si le panneau s'ouvre vide, le set
n'est pas actif ou l'item est désactivé — vérifie le drapeau `active` dans
`GET /api/v1/market/shops/starter`, et consulte
[le dépannage](troubleshooting.fr.md#le-panneau-souvre-mais-la-grille-est-vide).

---

## 6. Acheter quelque chose

Donne-toi d'abord l'argent — la boutique prend les émeraudes dans ton inventaire, pas dans
le vide :

```mcfunction
/give @s minecraft:emerald 10
```

Puis, dans le panneau :

1. **Clique la carte.** La fenêtre de confirmation s'ouvre : ce que tu paies à gauche, ce
   que tu reçois à droite, un curseur, et `Fermer` / `Confirmer`.
2. **Fais glisser le curseur jusqu'à 2.** La colonne de coût passe à 10 émeraudes.
3. **Clique `Confirmer`.**

```text
Achat réussi : Pioche de mineur x2
```

La fenêtre de confirmation se ferme, tes émeraudes ont disparu, deux pioches sont dans ton
inventaire, et la carte est désormais grisée. Tu es à ta limite ; cliquer dessus ne fait
rien.

Ce grisement, c'est le quota par joueur qui fait son travail. Le compteur est écrit dans
`<world>/data/cheesemarket/cheesemarket_stock.json` immédiatement, donc il survit à un
redémarrage.

---

## 7. Voir la vente

De retour dans le dashboard, ouvre l'onglet **Transactions** de la boutique. Une ligne :
ton nom, l'item, quantité 2, la liste des coûts, un horodatage.

```bash
curl -H "Authorization: Bearer <token>" \
  "http://localhost:44311/api/v1/market/transactions?shopId=starter&n=10"
```

```json
{"transactions":[{"timestamp":"2026-10-29T14:03:11.284Z",
  "playerUUID":"00000000-0000-0000-0000-000000000000","playerName":"Steve",
  "shopId":"starter","shopName":"Boutique de départ","setId":"a1b2c3d4","setName":"Outils",
  "itemId":"e5f6a7b8","itemName":"Pioche de mineur","quantity":2,"costs":[{"itemId":"minecraft:emerald","count":5}],
  "outputs":[{"itemId":"minecraft:diamond_pickaxe","count":1}]}],
 "total":1,"returned":1}
```

Les listes `costs` et `outputs` sont **à l'unité** — multiplie par `quantity` pour savoir ce
qui a réellement changé de mains. Le journal garde les 500 dernières ventes puis commence à
jeter les plus anciennes, donc copie le fichier si tu veux un historique.

Si tu as un onglet de dashboard ouvert pendant qu'une personne achète, la vente arrive
aussi en direct sur le canal WebSocket `market`, sous forme de message
`market_purchase`.

---

## 8. Lui donner ton allure

La boutique fonctionne. Passons à ce qui l'empêchera de ressembler à celle de tout le
monde.

Ouvre l'onglet **Layout**. Tu obtiens le canevas de 350 × 250 avec l'agencement par défaut
déjà posé dessus : un fond, le titre, la description, le bouton de fermeture et la grille
d'items.

Essaie trois choses, dans cet ordre :

1. **Déplace la grille.** Sélectionne l'élément `ITEM_GRID` et fais-le glisser, ou
   déplace-le aux flèches. Mets son `cols` à 4 dans le panneau de propriétés. Rouvre la
   boutique en jeu : quatre cartes par ligne, centrées.
2. **Téléverse un fond.** Clique *Upload image* et choisis un PNG de 350 × 250 sous 2 Mo.
   L'asset prend le nom du fichier, et l'éditeur règle lui-même le `src` de l'élément
   `BACKGROUND` sur ce nom. L'image est envoyée au client en ligne la prochaine fois qu'un
   joueur ouvre la boutique.
3. **Enregistre-le comme template.** Clique *Templates*, enregistre sous un nom, et
   l'agencement devient réutilisable sur ta prochaine boutique.

L'enregistrement automatique part deux secondes après que tu as cessé d'éditer, Ctrl+Z
annule jusqu'à quarante-neuf niveaux (l'historique garde cinquante états), et `Reset` remet l'agencement par défaut. La référence complète des
éléments et des propriétés est dans
[l'éditeur de mise en page](layout-editor.fr.md).

---

## 9. La mettre derrière un NPC

`/shop open` exige le niveau de permission 2 : un joueur ne peut donc pas la taper
lui-même. Les joueurs arrivent dans une boutique parce que quelque chose d'autre la lance
pour eux, en leur nom : un dialogue de NPC, une récompense de quête ou un bloc de commande
qui exécute `/execute as <joueur> run shop open starter` au niveau 2 ou plus. Le serveur
n'a pas d'autre moyen — l'action `open_shop_player:` ne peut venir que du client du joueur
lui-même.

C'est aussi le moment où la boutique cesse d'être un test. Deux points à régler avant de
laisser entrer qui que ce soit d'autre :

- **La rotation.** Un set en `SCHEDULE` s'ouvre et se ferme sur l'horloge de la machine
  serveur : c'est ainsi qu'on tient un marché de nuit. Un set en `MANUAL` est un
  interrupteur que tu bascules depuis le dashboard.
- **Les limites.** `UNLIMITED` convient à un magasin général, `GLOBAL` à une série
  limitée, `PER_PLAYER_RESET` à une allocation quotidienne. Garde en tête qu'un achat
  unique n'est pas borné par la limite — un item `GLOBAL` avec une unité restante en vendra
  quand même 64 d'un coup.
  [Pourquoi](purchases.fr.md#la-quantité-ne-fait-pas-partie-du-contrôle-de-stock).

---

← [Installation](installation.fr.md) · [Commandes](commands.fr.md) →
