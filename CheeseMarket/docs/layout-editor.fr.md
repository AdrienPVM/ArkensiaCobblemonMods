[← CheeseMarket](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](layout-editor.md)

# L'éditeur de mise en page

L'agencement visuel d'une boutique est une donnée, pas du code. Le dashboard porte un
éditeur par glisser-déposer sur un canevas de 350 × 250 — exactement la taille en pixels
de la texture de boutique — et ce que tu y déposes est stocké sur la boutique sous forme
d'objet `layout`, puis rejoué par le client quand un joueur ouvre la boutique.

Une boutique sans `layout` retombe sur un agencement codé en dur. Ce défaut est aussi ce
que produit `ShopLayout.buildDefault()`, donc « réinitialiser » et « jamais touché »
donnent le même résultat.

---

## Le canevas

L'éditeur vit derrière l'onglet **Layout** de la page de détail d'une boutique, dans le
dashboard (module `market`). Il te donne :

- un canevas de 350 × 250, du zoom 1× au zoom 4×, avec le fond de la boutique dessous ;
- une grille d'accrochage de 1, 2, 5, 10, 15 ou 20 pixels (5 par défaut) ;
- un panneau de calques avec des flèches d'ordre Z par élément ;
- un panneau de propriétés pour ce qui est sélectionné ;
- annuler et refaire, sur 30 niveaux, liés à Ctrl+Z et Ctrl+Y ;
- Suppr pour retirer la sélection, Ctrl+D pour la dupliquer, les flèches pour déplacer
  d'un pixel et Maj+flèches pour déplacer du pas d'accrochage ;
- un enregistrement automatique après 2 secondes d'inactivité : tu édites et ça persiste,
  sans bouton d'enregistrement.

Les coordonnées sont en pixels de texture, l'origine en haut à gauche du panneau de
350 × 250. Le client ajoute l'origine à l'écran du panneau, donc tes coordonnées sont
indépendantes de la résolution.

---

## Ce que tu peux placer

Cinq types d'éléments, issus de `ShopLayout.ElementType` :

| Type | Ce que c'est | Dessiné par le client ? |
|---|---|---|
| `BACKGROUND` | le fond du panneau, soit la texture intégrée, soit un PNG téléversé | oui |
| `TEXT` | une étiquette de texte, avec des variables | oui |
| `BUTTON` | un rectangle plein avec un libellé et une action | oui |
| `ITEM_GRID` | la grille de cartes — colonnes, lignes et marges | oui |
| `IMAGE` | une image indépendante | **non** |

⚠️ `IMAGE` est une impasse. Tu peux en ajouter un, le positionner et l'enregistrer, et
`ShopViewScreen` le saute explicitement en même temps que `BACKGROUND` et `ITEM_GRID`
dans sa boucle d'éléments personnalisés — mais contrairement à ces deux-là, il n'a de
renderer dédié nulle part. Il n'apparaîtra jamais en jeu. Utilise plutôt le PNG téléversé
de l'élément `BACKGROUND` pour la décoration.

Chaque élément partage le même cadre : `id`, `type`, `x`, `y`, `width`, `height`,
`zOrder`, et un objet `props` libre dont les clés dépendent du type. Les éléments sont
dessinés par `zOrder` croissant.

### TEXT

| Propriété | Signification |
|---|---|
| `text` | la chaîne ; `{shopName}` et `{shopDesc}` sont substitués au rendu |
| `color` | hexadécimal `RRGGBB`, un nom de couleur vanilla, ou le littéral `{titleColor}` pour hériter de la couleur de titre de la boutique |
| `align` | `left`, `center` (défaut) ou `right`, dans la `width` de l'élément |
| `fontSize` | présent dans la mise en page par défaut ; le renderer tire son échelle de `scale` |
| `scale` | facteur d'échelle du texte |
| `bold` | booléen |

La mise en page par défaut utilise deux éléments TEXT dont `text` vaut `{shopName}` et
`{shopDesc}`, et le client les retrouve *par cette chaîne de variable exacte* pour y
appliquer le style du titre et de la description. Remplace `{shopName}` par autre chose et
le titre cesse d'être traité comme un titre.

### BUTTON

| Propriété | Signification |
|---|---|
| `action` | ce que le clic déclenche |
| `text` | libellé, centré |
| `color` | remplissage du fond, hexadécimal `RRGGBB` |
| `textColor` | couleur du libellé |
| `src` | utilisé par le bouton de fermeture par défaut pour choisir la texture intégrée |

**Une seule action fonctionne : `close`.** Le client construit un widget cliquable pour
toute action non vide et la route via `_custom:<action>`, où il existe exactement une
branche, pour `close`. Toute autre chaîne d'action te donne un bouton qui avale le clic et
ne fait rien. Il n'y a pas de « changer de set », pas de « page suivante », pas de
« exécuter une commande ».

Le bouton de fermeture de la mise en page par défaut est retrouvé en cherchant
`action: "close"`, et reçoit la texture intégrée de 20 × 20 plutôt qu'un remplissage de
couleur.

### ITEM_GRID

| Propriété | Défaut | Signification |
|---|---|---|
| `cols` | 6 | cartes par ligne |
| `rows` | 3 | lignes dans la mise en page par défaut ; le client déduit les lignes visibles de la `height` de l'élément |
| `padX` | 4 | écart horizontal en pixels |
| `padY` | 3 | écart vertical en pixels |

La grille se centre horizontalement dans l'élément. Quand tout tient sans défilement, elle
se centre aussi verticalement, et une dernière ligne incomplète est centrée elle aussi —
donc une boutique de quatre items dans une grille à six colonnes les affiche en une ligne
centrée bien nette, et non alignée à gauche avec deux trous. Au-delà d'une page, une barre
de défilement apparaît à droite et le centrage cesse.

### BACKGROUND

Une seule propriété, `src` : soit `default` pour le `shop/custom/shop.png` intégré, soit
le nom d'un asset téléversé. Le client reçoit le PNG en base64 dans le premier payload
`SHOP_VIEW` et l'enregistre comme texture dynamique, puis il n'est pas réexpédié lors du
rafraîchissement de stock qui suit un achat.

---

## Le gabarit de carte

Indépendamment des éléments, `layout.itemCard` décrit une carte. Édite-le dans le
sous-éditeur de carte, qui zoome de 2× à 8× parce que tu y positionnes des choses à
l'échelle de quelques pixels.

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `cardWidth` | entier | 41 | largeur de la carte, celle de l'`item.png` d'origine |
| `cardHeight` | entier | 36 | hauteur de la carte |
| `iconOffsetX` | entier | 13 | position de l'icône dans la carte |
| `iconOffsetY` | entier | 7 | |
| `iconSize` | entier | 15 | taille de rendu de l'icône |
| `costOffsetX` | entier | 0 | décalage horizontal du bloc de prix par rapport au centre |
| `costOffsetY` | entier | 26 | décalage vertical du bloc de prix |
| `costTextScale` | flottant | 0.85 | échelle du texte de prix |
| `costIconSize` | entier | 9 | taille de la petite icône de pièce ou d'item à côté d'un prix |
| `costGap` | entier | 2 | écart entre deux paires de prix |
| `costTextIconGap` | entier | -1 | écart entre un nombre de prix et son icône |
| `costIconOffsetY` | entier | 0 | décalage vertical de l'icône de prix par rapport à son texte |
| `costTextColor` | chaîne | `FFCC44` | couleur du texte de prix |

Les écarts négatifs sont volontaires et légaux : `costTextIconGap` vaut `-1` par défaut
pour coller l'icône au nombre.

---

## La fenêtre de confirmation

`layout.confirmLayout` met en forme la fenêtre d'achat. Elle est absente jusqu'à ce que tu
l'édites, et absente signifie que les constantes propres au client s'appliquent. Elle
porte une clé de géométrie et quinze couleurs :

| Clé | Défaut | Ce qu'elle colore |
|---|---|---|
| `width` | 340 | largeur de la fenêtre |
| `bgImage` | `""` | nom d'asset pour une image de fond ; vide utilise le `confirm.png` intégré |
| `bgColor` | `1A1A2E` | fond de la fenêtre |
| `borderColor` | `2A5A8A` | bordure |
| `accentColor` | `4AC8EA` | accents |
| `headerBgColor` | `0D2137` | bandeau d'en-tête |
| `titleColor` | `4AC8EA` | nom de l'item |
| `labelColor` | `7A8A9A` | étiquettes de colonnes |
| `costColor` | `FFCC44` | prix |
| `arrowColor` | `4AC8EA` | la flèche entre les deux colonnes |
| `sliderBgColor` | `1A2A4A` | rail du curseur |
| `sliderFillColor` | `357EA3` | partie remplie du rail |
| `sliderThumbColor` | `4AC8EA` | poignée du curseur |
| `sepColor` | `1E3050` | séparateurs |
| `btnConfirmTextColor` | `55FF55` | libellé `Confirmer` |
| `btnCancelTextColor` | `FF6666` | libellé `Fermer` |
| `columnBgColor` | `18FFFFFF` | fond des colonnes, `AARRGGBB` |

`columnBgColor` prend huit chiffres hexadécimaux parce qu'il lui faut un canal alpha ; les
autres en prennent six.

Les deux libellés de boutons eux-mêmes, `Confirmer` et `Fermer`, sont des chaînes
françaises codées en dur dans `ShopViewScreen`. Tu peux les recolorer, pas les renommer ni
les traduire.

---

## Les images téléversées

Les images sont propres à une boutique, téléversées en base64 depuis le dashboard et
stockées comme de vrais fichiers :

```text
<world>/data/cheesemarket/assets/<shopId>/<nom>.png
```

`AssetManager` impose :

- les octets doivent commencer par la signature PNG de huit octets — l'extension n'est pas
  crue sur parole ;
- 2 Mo maximum par fichier ;
- 10 assets par boutique, comptés seulement à la création d'un nouveau nom, donc écraser
  un asset existant marche toujours ;
- des noms correspondant à `[a-zA-Z0-9_-]+`, de moins de 128 caractères, et sans `..`.

Supprimer une boutique depuis le dashboard supprime aussi son dossier d'assets. La
supprimer avec `/shop delete` ne le fait **pas** — ce chemin ne retire que le fichier
JSON, donc les PNG restent derrière, orphelins.

⚠️ `AssetManager` résout lui-même sa racine de stockage, et rien n'appelle jamais
`AssetManager.init(worldDataDir)`. Il retombe sur
`<dossier config>/../world/data`, ce qui n'est correct que si ton dossier de monde
s'appelle littéralement `world`. `ShopManager`, à l'inverse, obtient le vrai chemin depuis
`server.getSavePath(…)`. Sur un serveur avec `level-name=autre-chose`, les définitions de
boutiques et les images téléversées finissent à deux endroits différents, et les images
sont lues dans un dossier où personne n'écrit. Mets `level-name=world`, ou appelle
`AssetManager.init` depuis `CheeseMarket.onInitialize` si tu modifies le mod.

---

## Les templates

Une mise en page peut être enregistrée sous un nom et appliquée à une autre boutique :

```text
<world>/data/cheesemarket/templates/<nom>.json
```

Cinquante templates maximum, mêmes règles de nommage que les assets, et écraser un nom
existant est toujours permis. Un template stocke le tableau `elements`, le bloc `itemCard`
et le bloc `confirmLayout` — la mise en page et rien d'autre. Il ne porte aucune couleur de
boutique, aucun set, aucun item, et aucune référence aux assets que son élément
`BACKGROUND` nomme. Applique un template pointant vers `shop-bg` à une boutique qui n'a
pas d'asset de ce nom et la boutique retombe sur la texture intégrée.

Les fichiers de template sont rangés en dehors de toute boutique : ils survivent donc à la
suppression de toutes les boutiques.

---

## Ce qui est validé

Pas grand-chose, et il vaut mieux savoir où sont les bords :

- `PUT /shops/:id/layout` refuse plus de 20 éléments avec un 500 et le message
  `Maximum 20 elements`. C'est le seul contrôle sur la mise en page dans son ensemble.
- Les coordonnées ne sont **pas** bornées. Rien n'empêche un élément à `x = 9000` ; il est
  enregistré, puis dessiné hors de l'écran.
- Un type d'élément qui échoue à l'analyse devient `TEXT`.
- Un `PUT` dont le corps n'a ni `elements` ni `itemCard` est lu comme « réinitialiser au
  défaut » et met la mise en page à null.
- `props` est transmis tel quel, en JSON brut, sans schéma. Une propriété inconnue est
  stockée et ignorée ; une propriété mal orthographiée perd son effet en silence.

---

← [Interface joueur](player-interface.fr.md) · [Achats](purchases.fr.md) →
