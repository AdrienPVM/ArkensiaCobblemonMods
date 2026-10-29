[← CheeseMarket](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](configuration.md)

# Configuration

**CheeseMarket n'a aucun fichier de configuration.** Rien n'est écrit dans `config/`, il
n'existe pas de `cheesemarket.json`, et aucune classe du mod ne lit
`FabricLoader.getConfigDir()` pour ses propres réglages. Tout ce qui se règle vit sur une
boutique, et les boutiques vivent dans la sauvegarde du monde.

C'est la conséquence délibérée de l'endroit où les boutiques sont stockées : une boutique
est une donnée de monde, pas une configuration de serveur, donc elle voyage avec le monde
et non avec l'installation. Copier un monde copie ses boutiques, ses compteurs de stock et
son historique de ventes.

Cette page documente les quatre choses que tu peux éditer sur le disque, où elles sont, et
ce qu'il est sûr de toucher pendant que le serveur tourne.

---

## Les fichiers

```text
<world>/data/cheesemarket/
├── shops/<shopId>.json              une définition de boutique par fichier
├── cheesemarket_stock.json          stock et compteurs par joueur, en direct
├── cheesemarket_transactions.json   les 500 dernières ventes
├── assets/<shopId>/<nom>.png        images de mise en page téléversées
└── templates/<nom>.json             mises en page enregistrées
```

`<world>` est `server.getSavePath(WorldSavePath.ROOT)` — ton `level-name`, `world` par
défaut. L'arborescence est créée au `SERVER_STARTED` si elle manque.

⚠️ À une exception près. `AssetManager` ne se voit jamais appeler `init(worldDataDir)` : il
retombe donc sur `<dossier config>/../world/data` — un `world` codé en dur. Si ton
`level-name` n'est pas `world`, les images téléversées sont écrites et lues sous un dossier
`world/` qui ne contient rien d'autre, tandis que les définitions de boutiques vont dans la
vraie sauvegarde. Garde `level-name=world` à moins de modifier le mod. Note complète dans
[l'éditeur de mise en page](layout-editor.fr.md#les-images-téléversées).

| Fichier | Écrit quand | Lu quand | Éditable à chaud ? |
|---|---|---|---|
| `shops/*.json` | à chaque mutation de boutique, set ou item | démarrage, `/shop reload` | non — la mutation suivante réécrit le fichier depuis la mémoire |
| `cheesemarket_stock.json` | à chaque achat, et au `SERVER_STOPPING` | démarrage, `/shop reload` | non — même raison |
| `cheesemarket_transactions.json` | à chaque achat | démarrage, `/shop reload` | copie-le, ne l'édite pas |
| `assets/*/*.png` | au téléversement depuis le dashboard | à chaque construction de `SHOP_VIEW` | oui, il est relu sur le disque chaque fois |
| `templates/*.json` | à l'enregistrement d'un template | à l'application d'un template | oui |

Le principe : tout ce qui est tenu en mémoire est réécrit depuis la mémoire, et ton
édition est perdue. Pour changer un compteur de stock : arrête le serveur, édite,
redémarre — ou édite puis lance `/shop reload` **avant** que quiconque n'achète quoi que
ce soit.

---

## Un fichier de boutique, annoté

Tout ce qui suit est un champ réel, lu par `ShopData.fromJson`. Chaque clé est facultative ;
une clé absente prend la valeur par défaut du constructeur de `ShopData`.

```json
{
  "id": "general",
  "name": "Magasin général",
  "description": "Consommables et outils",

  // Analysé, stocké, servi par l'API — et ignoré par le client.
  // TABS | FLAT | FEATURED. Une valeur inconnue retombe sur TABS.
  "displayMode": "TABS",

  // Les deux sont transmis au client et jamais dessinés.
  "showWelcomeMessage": false,
  "welcomeMessage": "Bienvenue dans le shop !",

  // Un nom de couleur vanilla ou un triplet hexadécimal.
  "titleColor": "gold",
  // Hexadécimal RRGGBB, sans '#' devant. Ces trois couleurs et les quatre
  // couleurs de boutons ci-dessous sont envoyées au client et jamais dessinées.
  "bgColor": "1A1A2E",
  "borderColor": "2A5A8A",
  "accentColor": "4AC8EA",
  // Prévues pour les boutons de la fenêtre de confirmation ; inutilisées.
  "btnBuyColor": "1A5C2A",
  "btnBuyTextColor": "FFFFFF",
  "btnCloseColor": "1E3A5F",
  "btnCloseTextColor": "CCCCCC",

  // Omets entièrement la clé pour l'agencement intégré.
  // Écrit par l'éditeur du dashboard ; voir docs/layout-editor.fr.md.
  "layout": {
    "elements": [
      {
        "id": "a1b2c3d4",
        "type": "BACKGROUND",
        "x": 0, "y": 0, "width": 350, "height": 250,
        "zOrder": 0,
        "props": { "src": "default" }
      },
      {
        "id": "e5f6a7b8",
        "type": "TEXT",
        "x": 50, "y": 38, "width": 250, "height": 20,
        "zOrder": 10,
        // Le client retrouve le titre par cette chaîne de variable exacte.
        "props": { "text": "{shopName}", "color": "{titleColor}",
                   "align": "center", "fontSize": 9, "bold": false }
      },
      {
        "id": "c9d0e1f2",
        "type": "ITEM_GRID",
        "x": 32, "y": 95, "width": 286, "height": 121,
        "zOrder": 5,
        "props": { "cols": 6, "rows": 3, "padX": 4, "padY": 3 }
      },
      {
        "id": "b3a4c5d6",
        "type": "BUTTON",
        "x": 312, "y": 38, "width": 20, "height": 20,
        "zOrder": 20,
        // "close" est la seule action que le client comprend.
        "props": { "action": "close", "src": "default" }
      }
    ],
    "itemCard": {
      "cardWidth": 41, "cardHeight": 36,
      "iconOffsetX": 13, "iconOffsetY": 7, "iconSize": 15,
      "costOffsetX": 0, "costOffsetY": 26,
      "costTextScale": 0.85, "costIconSize": 9,
      "costGap": 2, "costTextIconGap": -1, "costIconOffsetY": 0,
      "costTextColor": "FFCC44"
    }
  },

  // true remplace les sets ci-dessous par le catalogue morpho CheeseItems à l'affichage.
  "morphoCatalog": false,

  "sets": [
    {
      "id": "d7e8f9a0",
      "name": "Consommables",
      // Dessiné avant les valeurs plus hautes. Seul le premier set ACTIF est visible en jeu.
      "sortOrder": 0,
      "rotation": {
        // ALWAYS | SCHEDULE | MANUAL. Les valeurs inconnues retombent sur ALWAYS.
        "mode": "SCHEDULE",
        // Horloge de la machine serveur, HH:mm. Une fin avant le début traverse minuit.
        // Une heure illisible rend le set ACTIF en permanence, et non fermé.
        "scheduleStart": "08:00",
        "scheduleEnd": "20:00",
        // Consulté seulement en mode MANUAL.
        "manualActive": true
      },
      "items": [
        {
          "id": "b1c2d3e4",
          // L'icône, utilisée telle quelle (papier compris). Vide, elle retombe sur
          // la première sortie ; une première sortie avec "components" la remplace.
          "displayItem": "minecraft:potion",
          // Vide signifie que le client affiche le nom vanilla traduit de l'icône.
          "displayName": "Potion de soin",

          // Ce que le joueur reçoit. count est forcé à 1 minimum.
          "outputs": [
            { "itemId": "minecraft:potion", "count": 1,
              // Bloc de composants MC 1.21.1 facultatif. Livré via /give.
              "components": "[potion_contents={potion:\"minecraft:healing\"}]" }
          ],

          // Ce que le joueur paie, à l'unité, multiplié par la quantité achetée.
          // La monnaie EST l'identifiant d'item. components est ignoré sur les coûts.
          "costs": [
            { "itemId": "numismatic-overhaul:silver_coin", "count": 5 }
          ],

          // Exécutées à l'achat, count × quantité fois, depuis la source console.
          // {player} devient le nom de l'acheteur. Un / initial est retiré.
          "commands": [
            { "command": "title {player} actionbar {\"text\":\"Merci !\"}", "count": 1 }
          ],

          "stock": {
            // UNLIMITED | GLOBAL | PER_PLAYER | PER_PLAYER_RESET
            "mode": "PER_PLAYER_RESET",
            // GLOBAL seulement. Forcé à 1 minimum.
            "globalStock": 100,
            // Les deux modes PER_PLAYER. Forcé à 1 minimum.
            "perPlayerLimit": 3,
            // PER_PLAYER_RESET seulement. DAILY | WEEKLY | MONTHLY | CUSTOM.
            // Ce sont des intervalles fixes depuis la dernière remise à zéro, PAS des dates.
            "resetType": "DAILY",
            // CUSTOM seulement. Forcé à 60 minimum.
            "resetIntervalSeconds": 86400
          },

          // false cache l'item aux joueurs et le garde dans le dashboard.
          "enabled": true,
          "sortOrder": 0
        }
      ]
    }
  ]
}
```

Le sens de chaque champ, avec le raisonnement derrière les plus étranges, se trouve dans
[le modèle de boutique](shop-model.fr.md). Les commentaires JSON ci-dessus sont là pour la
lecture seule. Le mod lit les fichiers de boutique avec le `JsonParser` permissif de Gson,
qui tolère les commentaires `//`, mais l'enregistrement suivant réécrit le fichier depuis la
mémoire sans eux — retire-les donc plutôt que de compter dessus.

---

## Le fichier de stock

```json
{
  "global": {
    "general:d7e8f9a0:b1c2d3e4": 87
  },
  "player": {
    "general:d7e8f9a0:b1c2d3e4:9d4f3c2b-1a0e-4f5d-8c7b-6a5e4d3c2b1a": 2
  },
  "lastReset": {
    "general:d7e8f9a0:b1c2d3e4": 1761739200
  }
}
```

Trois tables plates, clés en chaîne, valeurs entières ou en secondes epoch.

- `global` contient les unités **restantes**, pas les unités vendues. Une clé absente de
  la table signifie « personne n'en a encore acheté », et le `globalStock` de l'item sert
  alors de valeur de départ.
- `player` contient les unités **achetées** par ce joueur, comptées depuis zéro. Supprime
  une clé serveur arrêté, ou mets-la à `0` puis `/shop reload`, pour rendre son quota à ce
  seul joueur — une clé supprimée du fichier puis rechargée survit en mémoire, parce que le
  rechargement fusionne dans les compteurs existants sans les vider.
- `lastReset` est la seconde epoch à laquelle un item `PER_PLAYER_RESET` a vu ses clés par
  joueur effacées pour la dernière fois. Zéro ou absent signifie que le prochain accès
  réinitialise.

Les clés sont `shopId:setId:itemId` et `shopId:setId:itemId:playerUuid`. Rien ne purge les
clés des boutiques, sets ou items qui n'existent plus, donc le fichier grossit lentement et
indéfiniment. Supprimer une clé orpheline est sans danger.

---

## Le fichier de transactions

Un tableau JSON, l'entrée la plus récente en premier, plafonné à 500. La forme d'une entrée
et la mise en garde sur les coûts à l'unité sont dans
[les achats](purchases.fr.md#ce-que-contient-une-entrée-de-transaction). La 501e entrée
chasse définitivement la plus ancienne du fichier : prévois une copie si l'historique des
ventes compte pour toi.

---

## Rechargement

| Changement | Pris en compte par |
|---|---|
| tout ce qui est édité depuis le dashboard | immédiatement, en mémoire et sur le disque |
| tout ce qui est édité dans l'écran d'administration en jeu | immédiatement |
| un fichier de boutique édité à la main | `/shop reload`, ou un redémarrage |
| un fichier de stock édité à la main | `/shop reload`, ou un redémarrage |
| un PNG téléversé | la prochaine fois qu'un joueur ouvre cette boutique |
| un template | quand tu l'appliques à une boutique |

`/shop reload` exige le niveau de permission 4 et relit tout depuis le disque. Un joueur
qui a déjà un panneau de boutique ouvert n'est pas rafraîchi.

---

← [Commandes](commands.fr.md) · [Le modèle de boutique](shop-model.fr.md) →
