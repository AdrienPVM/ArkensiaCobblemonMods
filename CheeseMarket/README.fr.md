# CheeseMarket

> 🇬🇧 [Read in English](README.md)

> Des boutiques pour les joueurs, avec une interface plein écran en jeu, conçue depuis le
> dashboard jusqu'au pixel.

Une boutique, ici, n'est pas un coffre rempli d'items. Un joueur ouvre un panneau de
350 × 250 avec son propre fond, sa propre grille de cartes, une fenêtre de confirmation
dotée d'un curseur de quantité de 1 à 64, et de vraies infobulles vanilla sur chaque item —
le serveur les pré-calcule et les envoie avec les données de boutique, donc un livre
enchanté affiche ses enchantements exactement comme dans un inventaire.

Les administrateurs ne touchent jamais à un fichier de configuration. Les boutiques, les
sets, les items, les prix, les limites de stock et l'agencement visuel sont tous conçus
dans CheeseDash, et l'onglet de mise en page est un véritable éditeur : fais glisser des
éléments sur un canevas à la taille de la texture de boutique, accroche-les à une grille,
annule trente niveaux, téléverse un PNG de fond, enregistre l'agencement comme template
réutilisable.

Il n'y a aucun champ de prix nulle part, parce que la monnaie *est* l'identifiant d'item
que tu mets dans la liste des coûts. Des émeraudes, un item de quête personnalisé, des
pièces Numismatic Overhaul prélevées sur la bourse du joueur, ou des crédits CheeseStore —
même champ, identifiant différent. Un item peut aussi exécuter des commandes serveur à
l'achat, et c'est ainsi qu'une boutique vend un rang ou une permission plutôt qu'une pile.

**Minecraft 1.21.1 · Fabric · Java 21 · 23 fichiers source, ~6 900 lignes**

---

## Prérequis

| | |
|---|---|
| **Requis** | [CheeseDash](../CheeseDash/) · Fabric Loader ≥ 0.15.11 · Fabric API · Java 21 |
| **Requis des deux côtés** | le même jar sur le serveur **et** sur chaque client — l'interface est un écran client |
| **Facultatifs** | [CheeseStore](../CheeseStore/) pour des prix en crédits · [CheeseItems](../CheeseItems/) pour les icônes d'items personnalisés et le catalogue morpho · [Numismatic Overhaul](https://modrinth.com/mod/numismatic-overhaul) pour des prix en pièces payés depuis la bourse |

Les trois intégrations facultatives sont atteintes par réflexion : le jar se charge donc
sans elles. Chacune journalise une ligne disant si elle a trouvé sa cible, et la
fonctionnalité correspondante s'éteint discrètement sinon.

## Installation

```bash
cd CheeseDash      && ./gradlew build     # CheeseMarket compile contre son jar
cd ../CheeseMarket && ./gradlew build
```

Dépose `build/libs/cheesemarket-1.0.0+1.21.1.jar` et le jar de CheeseDash dans le `mods/`
du serveur, et le jar CheeseMarket dans celui du client. Il n'y a aucun fichier de
configuration : le premier démarrage crée `<world>/data/cheesemarket/shops/` et journalise

```text
[CheeseMarket] Initialized.
[Market] Loading shops from .../world/data/cheesemarket/shops
```

Marche à suivre complète : [installation](docs/installation.fr.md).

## Documentation

| Page | Ce qu'elle couvre |
|---|---|
| [Installation](docs/installation.fr.md) | Prérequis, ordre de construction, pourquoi les deux côtés ont besoin du jar, ce que le premier démarrage crée, comment vérifier, retrait propre |
| [Premiers pas](docs/getting-started.fr.md) | Un tutoriel : d'un serveur vide à un joueur qui paie cinq émeraudes une pioche, avec la vente visible dans le dashboard |
| [Commandes](docs/commands.fr.md) | `/cmadmin` et les cinq sous-commandes `/shop`, argument par argument, avec les niveaux de permission, les sorties et les messages d'échec |
| [Configuration](docs/configuration.fr.md) | Il n'y a pas de fichier de configuration — ce qui existe à la place, où, ce qu'on peut éditer à chaud, et un fichier de boutique entièrement annoté |
| [Le modèle de boutique](docs/shop-model.fr.md) | Boutique, set et item : chaque champ, comment la rotation décide qu'un set est ouvert, comment les limites et les remises à zéro fonctionnent vraiment, comment un prix se paie |
| [Interface joueur](docs/player-interface.fr.md) | Ce qu'un acheteur voit et fait, écrit de son point de vue — et la liste honnête de ce que l'interface ne fait pas |
| [Éditeur de mise en page](docs/layout-editor.fr.md) | Les cinq types d'éléments, le gabarit de carte, la fenêtre de confirmation, les images téléversées, les templates, et ce qui est validé |
| [Achats](docs/purchases.fr.md) | Le pipeline d'achat étape par étape, ce qui est enregistré et où, et les trois façons dont un achat finit sur un joueur débité et sans marchandise |
| [API dashboard](docs/dashboard.fr.md) | Chaque route sous `/api/v1/market`, les formes de requête et de réponse, le rôle exigé par chacune, et le message WebSocket `market_purchase` |
| [Architecture](docs/architecture.fr.md) | Organisation des packages, responsabilités des classes, threads, le protocole à découpage de chaînes, faiblesses connues |
| [Dépannage](docs/troubleshooting.fr.md) | Symptôme, cause, remède — y compris les lignes de log qui signifient qu'un joueur a été débité pour rien |

## Commandes en un coup d'œil

| Commande | Niveau | Effet |
|---|---|---|
| `/cmadmin` | 2 | ouvre l'éditeur d'administration en jeu |
| `/shop open <shopId>` | 2 | ouvre une boutique **pour toi-même** |
| `/shop list` | 2 | toutes les boutiques chargées avec leur nombre de sets |
| `/shop create <shopId>` | 2 | crée une boutique vide et son fichier JSON |
| `/shop reload` | **4** | relit boutiques, stock et transactions depuis le disque |
| `/shop delete <shopId>` | **4** | supprime la boutique et son fichier, sans confirmation |

Il n'y a aucune commande pour les joueurs. Les joueurs atteignent une boutique parce qu'un
NPC, une quête ou une commande l'a ouverte pour eux. Et `/shop open` n'a aucun argument de
joueur cible : il ouvre la boutique pour celui qui l'a lancée.

Un éditeur d'administration en jeu existe bel et bien, derrière `/cmadmin` — ce README
affirmait le contraire. Le dashboard est le meilleur outil et celui qui est maintenu, mais
l'écran en jeu est là et fonctionne.

## Dashboard

Enregistré sous le préfixe `market` : tout vit donc sur `/api/v1/market/*` — CRUD des
boutiques, des sets et des items, les routes propres à l'éditeur de mise en page, le
téléversement d'assets PNG, les templates de mise en page, le journal des transactions et
les statistiques. `GET` est lisible par toute session authentifiée ; toute autre méthode
exige `OPERATOR` — une règle appliquée par le dispatch de modules de CheeseDash, pas par
ce mod, qui n'appelle jamais `requireWrite` lui-même. Aucune route market ne peut exiger
`SUPER_ADMIN`, parce qu'`OPERATOR` est la seule barre que ce dispatch connaisse :
supprimer une boutique est une action d'opérateur. Voir
[la page dashboard](docs/dashboard.fr.md#rôles-requis) pour ce que cela garantit et ce
que cela ne garantit pas.

Un message `market_purchase` part sur le canal WebSocket `market` après chaque achat
réussi, et CheeseDash le relaie vers Discord si un webhook est configuré.

## Avertissements honnêtes

⚠️ **Le canal d'actions n'a aucun contrôle de permission.** Toute action client — y compris
`shop_create`, `shop_delete` et `item_save` — atteint le handler sans la moindre
vérification que l'expéditeur est opérateur, et `shop_buy` est accepté pour n'importe quel
identifiant de boutique sans vérifier que la boutique a un jour été ouverte pour ce joueur.
Si ton serveur accueille des joueurs non fiables avec des clients modifiés, lis
[la note d'architecture](docs/architecture.fr.md#le-canal-dactions-nest-pas-authentifié)
avant de déployer ceci.

⚠️ **Le paiement n'est pas atomique avec la livraison.** Les coûts sont prélevés avant la
remise des sorties. Une sortie dont l'identifiant d'item est absent du registre est sautée
avec une ligne de log tandis que l'achat se déclare quand même réussi, et une exception de
livraison laisse le joueur débité avec un message en chat lui disant de trouver un
administrateur. Les deux cas sont remboursables à la main depuis le journal des
transactions.
[Ce que le code fait dans chaque cas](docs/purchases.fr.md#étape-4--la-livraison).

Trois fonctionnalités sont configurables et non implémentées : les modes d'affichage
`TABS`/`FLAT`/`FEATURED`, le message d'accueil, et l'élément de mise en page `IMAGE`.
L'absence de barre d'onglets a une conséquence visible — **seul le premier set actif d'une
boutique est atteignable en jeu** — donc construis un set actif à la fois et laisse la
rotation choisir lequel.

Ce mod a tourné sur un serveur en production avec des centaines de joueurs : le chemin
d'achat est donc éprouvé pour de vrai. L'éditeur de mise en page et le catalogue morpho
sont plus récents et ont moins servi.

## Licence

[PolyForm Noncommercial 1.0.0](../LICENSE), avec une permission additionnelle pour les
opérateurs de serveur. Tu peux faire tourner ceci sur un serveur qui reçoit des dons ou
vend des rangs ; tu ne peux pas vendre le mod lui-même.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Discord Arkensia](https://discord.gg/fYmPwMBjdB)
