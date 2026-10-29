[← CheeseMarket](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](player-interface.md)

# L'interface joueur

Cette page est écrite pour celui qui achète. La boutique n'est pas un coffre rempli
d'items : c'est un panneau plein écran dessiné à partir d'une texture de 350 × 250, avec
ses propres cartes, sa propre fenêtre de confirmation et un curseur de quantité. Tu ne
déplaces jamais un item et tu ne fermes jamais un coffre.

---

## Ouvrir une boutique

Tu ne tapes rien. Une boutique s'ouvre quand le serveur décide de l'ouvrir pour toi, ce
qui veut dire en pratique l'une de ces deux choses :

- un administrateur lance `/shop open <id>` et c'est lui qui la lance, ou il te cible
  autrement ;
- un NPC, un script de quête ou un bloc de commande t'envoie dans une boutique, en lançant
  `/shop open <id>` en ton nom (`/execute as <toi> run …`) avec assez de permission.

Rien sur le serveur ne peut envoyer l'action `open_shop_player:` à ta place : c'est un
paquet que ton propre client envoie, et seul le bouton d'aperçu de l'écran
d'administration le fait.

Il n'existe aucune commande `/shop` pour les joueurs. Tout l'arbre `/shop` exige le
niveau de permission 2, donc si tu la tapes tu reçois l'erreur vanilla *Unknown or
incomplete command*. Voir [les commandes](commands.fr.md) si tu es l'administrateur.

À l'ouverture, ton monde continue de tourner derrière le panneau — la boutique ne met
**pas** le jeu en pause (`shouldPause()` renvoie false). Tu peux te faire tirer dessus
pendant que tu fais tes courses.

---

## Ce que tu vois

```text
┌──────────────────────────────────────────────┐
│                                         [ X ]│  ← bouton de fermeture, en haut à droite
│              Nom de la boutique              │  ← titre, coloré par la boutique
│                 description                  │
│                                              │
│   ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐  │
│   │icon│ │icon│ │icon│ │icon│ │icon│ │icon│  │  ← 6 cartes par ligne, 3 lignes visibles
│   │ 5⛁ │ │ 12⛁│ │ 3🧀│ │ 1⛁ │ │ 40⛁│ │ 2⛁ │  │  ← prix sous chaque icône
│   └────┘ └────┘ └────┘ └────┘ └────┘ └────┘  │
│   ┌────┐ ┌────┐                            ▌ │  ← barre de défilement s'il y a plus
│   └────┘ └────┘                              │
└──────────────────────────────────────────────┘
```

Les positions exactes, la taille des cartes, les couleurs, l'image de fond et le libellé
de prix sont tous configurables par boutique : ce que tu vois sur un serveur ne correspond
donc pas forcément au croquis. Les éléments toujours présents : un fond, un titre, un
bouton de fermeture et une grille de cartes.

Chaque carte montre l'icône de l'item et son prix, jusqu'à trois lignes de coût (la
quatrième devient `…`). Survoler une carte te donne la véritable infobulle vanilla de
l'item — le serveur la pré-calcule et l'envoie avec les données de boutique, donc un livre
enchanté affiche ses enchantements et un item de quête personnalisé affiche son lore
exactement comme dans ton inventaire.

Tu ne vois pas combien il en reste. Le serveur calcule une ligne de stock pour chaque item
et l'envoie, mais le client ne la dessine jamais ; le seul signe d'une limite est la carte
qui se grise quand tu ne peux plus l'acheter.

**Une carte grisée est une carte que tu ne peux pas acheter maintenant** — stock épuisé,
quota personnel atteint, ou déjà possédée. Elle est dessinée sous un voile sombre, ne
s'éclaire pas au survol, et cliquer dessus ne fait rien.

---

## Acheter

1. **Clique une carte.** Une fenêtre de confirmation s'ouvre par-dessus la grille. Il n'y
   a pas de bouton d'achat sur les cartes elles-mêmes.
2. La fenêtre montre deux colonnes : à gauche ce que tu paies, à droite ce que tu
   reçois, avec de grandes icônes. L'une ou l'autre colonne défile à la molette si la
   liste est longue. Survoler une ligne donne son infobulle plus une ligne
   `Quantité: n`.
3. **Règle la quantité.** Fais glisser le curseur. Il va de 1 à 64 par pas entiers, et les
   deux colonnes se mettent à jour en direct — les prix se multiplient pendant que tu
   glisses.
4. **Clique `Confirmer`.** Ou `Fermer`, ou appuie sur Échap, ou clique n'importe où en
   dehors de la fenêtre : ces trois gestes annulent et remettent la quantité à 1.

Les prix en pièces sont reconvertis en dénominations pendant que tu glisses : 150 pièces
de bronze s'affichent comme 1 argent 50 bronze plutôt qu'en tas de 150. C'est de
l'affichage seulement — le serveur débite les montants configurés sur l'item.

### Ce qui se passe ensuite

La fenêtre de confirmation se ferme dès que tu cliques `Confirmer`, avant même que le
serveur ait répondu : il n'y a donc pas de fenêtre pour un double-clic.

| Résultat | Ce que tu vois |
|---|---|
| Acheté | `Achat réussi : <item>` en vert dans le chat (suivi de ` x<n>` au-delà d'un), et la grille se rafraîchit — une carte que tu ne peux plus acheter se grise |
| Pas assez d'argent ou d'items | `Pas assez de ressources pour acheter x<n>.` en rouge |
| Pas de place | `Ton inventaire est plein ! Libère de la place avant d'acheter.` en rouge — rien n'a été débité |
| Stock épuisé | `Stock épuisé pour cet item.` en rouge |
| Quota atteint | `Vous avez atteint votre limite d'achat pour cet item.` en rouge |
| Item désactivé | `Cet item n'est pas disponible.` en rouge |
| Set fermé pendant que tu regardais | `Ce set n'est pas actif en ce moment.` en rouge |
| Tout le reste | `Achat impossible.` en rouge |

Si tu as payé en crédits, tu reçois aussi une ligne dorée :
`🧀 -<n> Crédits (solde : <reste>)`.

⚠️ Un message signifie que quelque chose s'est mal passé du côté du serveur, pas du tien :

```text
[Market] Erreur de livraison ! Contactez un admin pour un remboursement.
```

Tu as été débité et la livraison a échoué. Note l'heure et le nom de l'item et dis-le à un
administrateur ; le log du serveur contient la ligne correspondante et le journal des
transactions contient le prix que tu as payé.

### Des items à tes pieds

Si ton inventaire se remplit *pendant* la remise des items — parce que le contrôle de
place regarde tes 36 emplacements principaux et qu'un achat peut quand même déborder dans
des cas tordus — le reste tombe au sol, à tes pieds, au lieu de disparaître. Tu n'en es pas
averti : ramasse avant de partir.

---

## Le défilement et le reste des commandes

| Entrée | Effet |
|---|---|
| molette sur la grille | fait défiler la grille |
| glisser la barre de défilement à droite | pareil, plus vite |
| molette sur une colonne de confirmation | fait défiler cette colonne |
| clic gauche sur une carte | ouvre la fenêtre de confirmation |
| glisser le curseur | règle la quantité, 1–64 |
| Échap | ferme la fenêtre de confirmation, ou la boutique si aucune fenêtre n'est ouverte |
| clic sur le `X` | ferme la boutique |
| clic en dehors de la fenêtre de confirmation | ferme seulement la fenêtre |

Les clics droits et les clics molette ne font rien. Le jar embarque des chaînes de langue
pour `Middle-click to buy full stack` et `Left-click to purchase`, restes d'une conception
antérieure ; le curseur a remplacé les deux et ces chaînes ne servent plus.

---

## Ce que l'interface ne fait pas

Liste honnête, pour que tu ne les cherches pas :

- **Pas d'onglets.** Une boutique peut contenir plusieurs sets, et seul le premier set
  actif est affiché. Rien dans l'interface ne change de set.
- **Pas de chiffre de stock.** Le stock restant et ton quota personnel ne sont jamais
  affichés.
- **Pas de message d'accueil.** La boutique peut en porter un, et le client ne le dessine
  jamais.
- **Pas de modes d'affichage.** `TABS`, `FLAT` et `FEATURED` sont configurables et le
  client les ignore tous les trois.
- **Pas de revente.** Il n'existe aucun écran ni aucune action de vente, malgré des
  textures qui en ont l'air dans le jar.
- **Pas de recherche et pas de catégories.** La grille, c'est toute la boutique.
- **Pas d'affichage de solde.** Ta bourse ou ton solde de crédits n'apparaît nulle part
  dans le panneau ; tu l'apprends quand un achat échoue.

---

## Si quelque chose semble anormal

Une carte avec un carré blanc vide à la place de l'icône signifie un item personnalisé
dont l'image d'icône n'est pas arrivée — le serveur journalise
`[Market]   <id> → NO image found` dans ce cas. Une carte avec la mauvaise icône est
d'habitude un item dont le `displayItem` est resté à sa valeur par défaut,
`minecraft:paper`, ou dont la première sortie porte des `components` — cette sortie
remplace alors le `displayItem`.

Plus de cas dans [le dépannage](troubleshooting.fr.md).

---

← [Le modèle de boutique](shop-model.fr.md) · [Éditeur de mise en page](layout-editor.fr.md) →
