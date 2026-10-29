[← CheeseMarket](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](commands.md)

# Commandes

Deux racines de commandes, toutes deux enregistrées dans `ShopCommands.register` :
`/cmadmin` et `/shop`. Les deux exigent le **niveau de permission 2** à la racine, et deux
sous-commandes de `/shop` montent ce niveau à **4**. Rien ici n'est destiné aux joueurs :
un joueur sous le niveau 2 ne voit aucune des deux commandes dans la complétion par
tabulation et reçoit l'erreur vanilla *Unknown or incomplete command* s'il la tape quand
même.

Il n'existe aucun alias. Aucune commande ne dépend d'un état particulier.

---

## `/cmadmin`

```mcfunction
/cmadmin
```

Aucun argument. Niveau de permission 2. Doit être lancée par un joueur : une source
console ou un bloc de commande reçoit `Joueur requis` et une valeur de retour de 0.

Ouvre l'écran d'administration en jeu — un éditeur plein écran pour les boutiques, les
sets et les items. La commande envoie deux paquets : un `ShopOpenPayload(adminMode = true)`
qui dit au client d'ouvrir `CheeseMarketScreen`, puis le résultat de l'action
`open_shop_list` pour que l'écran ait des données à dessiner.

⚠️ L'ancien README du dépôt affirmait qu'il n'existe aucun éditeur d'administration en
jeu, « par choix de conception ». Il en existe un : `CheeseMarketScreen` fait environ mille
lignes et est entièrement câblé derrière cette commande. Le dashboard est le meilleur
éditeur et celui qui est maintenu, mais celui en jeu existe et fonctionne.

```mcfunction
/cmadmin
```

Aucune sortie en chat. L'écran s'ouvre.

| Message | Cause |
|---|---|
| `Joueur requis` | la source n'est pas un joueur — console, bloc de commande, ou `/execute as` sur autre chose qu'un joueur |

---

## `/shop open`

```mcfunction
/shop open <shopId>
```

| Argument | Type | Requis | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `shopId` | mot unique | oui | un identifiant de boutique chargée ; **aucune complétion par tabulation** | — |

Niveau de permission 2. Ouvre la boutique **pour le joueur qui lance la commande**, et
pour lui seul. Il n'y a aucun argument de joueur cible : `/shop open general` lancé depuis
la console ne fait rien d'autre que signaler une erreur, et cette commande n'offre aucun
moyen de pousser une boutique vers quelqu'un d'autre. Pour ouvrir une boutique pour un
autre joueur, il faut envoyer l'action `open_shop_player:<shopId>` depuis ce qui le
pilote — c'est ce que fait l'intégration NPC.

Ce qu'elle fait, dans l'ordre :

1. Résout la boutique depuis `ShopManager`.
2. La passe par `MorphoShopRenderer.materialize`, qui renvoie la boutique inchangée sauf
   si `morphoCatalog` vaut true.
3. Envoie `ShopOpenPayload(adminMode = false)`.
4. Construit le JSON de vue joueur — sets actifs, items activés, chiffres de stock par
   joueur, infobulles résolues, images en base64 — et l'envoie comme payload `SHOP_VIEW`.

Rien n'est écrit sur le disque et aucun état ne change. La complétion d'argument n'est pas
câblée : Brigadier voit un simple `StringArgumentType.word()`, donc il faut connaître
l'identifiant. Récupère-le avec `/shop list`.

```mcfunction
/shop open general
```

Le panneau de boutique s'ouvre. Aucune sortie en chat en cas de succès.

| Message | Cause |
|---|---|
| `Joueur requis` | la source n'est pas un joueur |
| `Shop introuvable : <id>` | aucune boutique chargée sous cet identifiant ; vérifie avec `/shop list` |

---

## `/shop list`

```mcfunction
/shop list
```

Aucun argument. Niveau de permission 2. Affiche à l'appelant toutes les boutiques
chargées, une ligne chacune, en lisant la table en mémoire — donc une boutique créée
depuis le dashboard il y a une seconde apparaît immédiatement.

```text
── Shops CheeseMarket ──
general — Magasin général (3 sets)
metamorph — Métamorph (0 sets)
```

L'en-tête est doré et les lignes jaunes. Le nombre de sets est le **total**, pas le nombre
de sets actuellement actifs. Sans aucune boutique chargée, tu obtiens
`Aucun shop trouvé.` en jaune — un succès, pas une erreur.

---

## `/shop reload`

```mcfunction
/shop reload
```

Aucun argument. **Niveau de permission 4.** Appelle `ShopManager.loadAll()`, qui :

1. vide la table des boutiques en mémoire ;
2. relit chaque `*.json` de `<world>/data/cheesemarket/shops/` ;
3. relit `cheesemarket_stock.json` ;
4. relit `cheesemarket_transactions.json`.

```text
Shops rechargés depuis le disque.
```

Diffusé aux autres opérateurs, parce que le retour est envoyé avec `true` pour l'indicateur
`broadcastToOps`.

Deux conséquences à anticiper :

- Le rechargement **jette l'état non enregistré en mémoire**. En pratique, tous les
  chemins de mutation enregistrent immédiatement, donc il n'y a rien à perdre — mais un
  fichier de boutique devenu illisible depuis le dernier chargement disparaît de la table,
  avec pour seule trace `[CheeseMarket] Failed to load shop: <fichier> → <message>` sur la
  sortie d'erreur standard.
- Les fichiers de stock et de transactions sont relus depuis le disque : éditer
  `cheesemarket_stock.json` à la main puis recharger est donc la façon prise en charge de
  réinitialiser un compteur. Fais-le quand personne n'achète : l'achat suivant réécrit tout
  le fichier depuis la mémoire.

Un joueur qui tient un panneau de boutique ouvert n'est pas rafraîchi. Il continue de
regarder les anciennes données jusqu'à fermer et rouvrir le panneau.

---

## `/shop create`

```mcfunction
/shop create <shopId>
```

| Argument | Type | Requis | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `shopId` | mot unique | oui | n'importe quel mot unique non déjà utilisé | — |

Niveau de permission 2 (déclaré explicitement sur la sous-commande en plus d'être
hérité). Crée une boutique avec les valeurs par défaut — nom `Nouveau shop`, mode
d'affichage `TABS`, le jeu de couleurs d'origine, aucun set — et **écrit immédiatement
`<shopId>.json`**.

L'identifiant devient le nom du fichier. Il est vérifié contre les collisions, et rien
d'autre : aucune validation de caractères n'a lieu sur ce chemin, contrairement à
l'`isValidId` du dashboard. Un identifiant de boutique contenant une barre oblique ou un
`..` sera passé directement à `Path.resolve`. Utilise des mots simples en minuscules.

```mcfunction
/shop create farmers
```

```text
Shop créé : farmers
```

Diffusé aux opérateurs. L'étape suivante est le dashboard : la commande te donne une
boutique vide et aucun moyen d'y mettre quoi que ce soit.

| Message | Cause |
|---|---|
| `Un shop avec l'ID '<id>' existe déjà.` | l'identifiant est pris ; un identifiant n'est pas réutilisable sans suppression préalable |

---

## `/shop delete`

```mcfunction
/shop delete <shopId>
```

| Argument | Type | Requis | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `shopId` | mot unique | oui | un identifiant de boutique chargée ; aucune complétion | — |

**Niveau de permission 4.** Retire la boutique de la table et supprime son fichier JSON.
Il n'y a aucune confirmation et aucun retour en arrière.

```mcfunction
/shop delete farmers
```

```text
Shop supprimé : farmers
```

En rouge, diffusé aux opérateurs.

Ce qu'elle laisse derrière :

- `<world>/data/cheesemarket/assets/<shopId>/*.png` — les images téléversées. Seul le
  `DELETE /shops/:id` du dashboard les nettoie ; la commande, non.
- les compteurs de stock indexés sur cet identifiant de boutique dans
  `cheesemarket_stock.json`, qui restent dans le fichier et sont réécrits au prochain achat
  de n'importe quel item ;
- chaque entrée de transaction nommant cette boutique, ce qui est voulu — l'historique des
  ventes doit survivre à la boutique.

Recréer une boutique avec le même identifiant ressuscite ses anciens compteurs de stock,
parce que les clés correspondent de nouveau. Si ce n'est pas ce que tu veux, vide le
fichier de stock entre la suppression et la création.

| Message | Cause |
|---|---|
| `Shop introuvable : <id>` | aucune boutique chargée sous cet identifiant |

---

## Piloter le mod depuis ailleurs

Tout ce qui précède est accessible sans commande, et le dashboard est la voie prévue :

- **Dashboard** — le module `market`. CRUD boutiques, sets et items, l'éditeur de mise en
  page, les assets, les templates, les transactions et les statistiques. Voir
  [l'API dashboard](dashboard.fr.md).
- **Le canal d'actions** — `cheesemarket:shop_action` porte à la fois les actions joueur et
  un jeu complet d'actions d'administration, sans contrôle de permission propre. C'est
  ainsi qu'un NPC ouvre une boutique pour un joueur, et c'est aussi un trou : voir
  [l'architecture](architecture.fr.md#le-canal-dactions-nest-pas-authentifié).

---

← [Premiers pas](getting-started.fr.md) · [Configuration](configuration.fr.md) →
