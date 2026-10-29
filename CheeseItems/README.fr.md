# CheeseItems

> 🇬🇧 [Read in English](README.md)

> Des items de quête écrits depuis un dashboard web, plus le catalogue de transformations Morpho.

Un item CheeseItems est un item vanilla qui porte un tag `cheeseitems` dans son composant
`CUSTOM_DATA`. Tout le mod tient dans cette astuce : rien n'est jamais enregistré dans le
registre des items, donc une définition peut être créée, renommée, recolorée, dotée de
nouveaux effets et d'une nouvelle icône pendant que le serveur tourne, et chaque copie
déjà dans l'inventaire d'un joueur se met à jour au tick suivant. Une chaîne de quêtes se
construit en tapant dans le dashboard, pas en redémarrant.

Le clic droit sur un item personnalisé lance les commandes que tu lui as attachées,
applique les effets que tu as listés, consomme une utilisation, démarre un compte à
rebours dans la barre d'action — ce que dit la définition. Les conditions filtrent
l'utilisation par dimension, niveau d'expérience, item en main secondaire ou nœud de
permission. Les PNG que tu envoies sont compilés dans un resource pack serveur poussé
automatiquement aux clients.

La seconde moitié du mod ne partage que le code : le **catalogue de Morphos**, une liste de
formes de Pokémon qu'un joueur peut débloquer puis porter. Une morpho est stockée par
joueur dans un Codex, choisie dans une interface en jeu (`J`), activée avec `H`, et
appliquée en pilotant la commande `cshift` de [CobbleShift](../CobbleShift/). Chaque
transformation vide une réserve d'endurance et s'annule d'elle-même quand la réserve est
vide.

**Minecraft 1.21.1 · Fabric · Java 21 · 28 fichiers source, ~4 980 lignes**

---

## Prérequis

| | |
|---|---|
| **Requis** | [CheeseDash](../CheeseDash/) · Fabric Loader ≥ 0.16.0 · Fabric API · Java 21 |
| **Nécessaire pour les morphos** | [CobbleShift](../CobbleShift/) — les transformations sont des commandes `cshift` |
| **Optionnel** | [CheeseStore](../CheeseStore/) — affiche un solde de monnaie dans l'interface du Codex |

`cheesedash` est un `depends` strict : le serveur refuse de démarrer sans lui. CobbleShift
n'est pas déclaré du tout : le système de morphos appelle `cshift transform` via le
dispatcher de commandes, donc sans CobbleShift la commande échoue et seul un avertissement
atteint le log.

`"environment": "*"` — le jar se charge des deux côtés. La moitié cliente ajoute
l'interface du Codex et les deux raccourcis clavier. Un joueur sans le jar peut quand même
recevoir des items et reçoit quand même le resource pack.

## Installation

```bash
cd CheeseDash && ./gradlew build     # CheeseItems compile contre son jar
cd ../CheeseItems && ./gradlew build
```

Dépose `build/libs/cheeseitems-1.0.0+1.21.1.jar` et le jar de CheeseDash dans le dossier
`mods/` du serveur. Le premier démarrage écrit `config/cheeseitems.json` et
`config/cheeseitems_morphos.json`, et affiche :

```text
[CheeseItems] Initializing...
[CheeseItems] Default config created
[CheeseItems] Initialized successfully!
[CheeseItems] Server started — 0 items loaded
```

Règle ensuite `resourcePackHost` dans `config/cheeseitems.json` sur une adresse que tes
joueurs peuvent réellement atteindre. Parcours complet :
[installation](docs/installation.fr.md).

## Documentation

| Page | Ce qu'elle couvre |
|---|---|
| [Installation](docs/installation.fr.md) | Prérequis, ordre de build, ce que crée le premier démarrage, comment vérifier le chargement, désinstallation propre |
| [Premiers pas](docs/getting-started.fr.md) | Un tutoriel : d'un serveur vierge à une icône personnalisée dans la main d'un joueur et une morpho qu'il peut porter |
| [Commandes](docs/commands.fr.md) | `/cheeseitem` et `/morpho`, argument par argument, avec les niveaux de permission, la complétion, les sorties et tous les messages d'échec |
| [Configuration](docs/configuration.fr.md) | Les trois fichiers JSON et les dossiers d'assets, clé par clé, avec un exemple complet annoté |
| [Items personnalisés](docs/items.fr.md) | Le modèle d'un item : identité, options, utilisations, cooldowns, effets, actions de commande, conditions, endurance par item, et les options qui ne font rien |
| [Resource pack](docs/resource-pack.fr.md) | Comment les PNG envoyés deviennent un pack, l'attribution des CustomModelData, la police de la barre d'endurance, ce que le pack écrase |
| [Morphos](docs/morphos.fr.md) | Le catalogue, le Codex par joueur, l'interface, l'activation, la réserve d'endurance, comment CobbleShift est piloté |
| [API dashboard](docs/dashboard.fr.md) | Les deux modules, chaque route REST, les formes de requête et de réponse, les deux canaux WebSocket, et ce que les gardes de rôle couvrent ou non |
| [Architecture](docs/architecture.fr.md) | Arborescence des packages, responsabilités des classes, moments d'écriture disque, threading, le mixin du totem, points d'extension, faiblesses connues |
| [Dépannage](docs/troubleshooting.fr.md) | Symptôme, cause, remède — dont les trois façons qu'a un item de ne rien faire en silence |

## Les commandes en un coup d'œil

| Commande | Permission | Ce qu'elle fait |
|---|---|---|
| `/cheeseitem give <player> <item_id> [quantity]` | niveau 2 | construit un item personnalisé et le met dans l'inventaire d'un joueur |
| `/cheeseitem list` | niveau 2 | toutes les définitions, avec leur état actif/inactif et leur item de base |
| `/cheeseitem reload` | niveau 2 | relit `cheeseitems.json` et rafraîchit les inventaires connectés |
| `/cheeseitem morpho grant <player> <morpho_id>` | niveau 2 | ajoute une morpho du catalogue au Codex d'un joueur |
| `/morpho` | aucune | ouvre l'interface du Codex sur le client de l'appelant |

Le littéral de commande est `cheeseitem`, au singulier. Dans `give`, le joueur vient
d'abord et l'identifiant de l'item ensuite. L'ancienne documentation donnait cet ordre à
l'envers et listait une sous-commande `/cheeseitem grant` qui n'existe pas.

## Dashboard

Deux modules, tous deux déclarés dans l'entrypoint `cheesedash` :

- **`items`** → `/api/v1/items/*` — CRUD des items, give, reload, envoi d'icône, la liste
  des effets de potion dont l'éditeur a besoin. Publie `item_update` sur le canal `items`.
- **`morphos`** → `/api/v1/morphos/*` — CRUD du catalogue, envoi d'image, réordonnancement
  par glisser-déposer, les valeurs d'endurance globales, et l'attribution d'une morpho à un
  joueur. Publie `morpho_update` sur le canal `morphos`.

Chacun sert aussi des routes publiques sans authentification :
`/cheeseitems/api/pack.zip` pour le resource pack, et `/morphos/api/image/<id>` plus
`/morphos/api/thumb/<id>` pour les images du catalogue, c'est ainsi que l'interface en jeu
et la boutique web les récupèrent.

> ⚠️ **Sur ce module, un `OPERATOR` équivaut à un accès console.** Un item peut porter des
> `useActions` dont les commandes s'exécutent au niveau de permission 4 : quiconque peut
> écrire un item peut donc tout lancer. Les routes d'écriture exigent `OPERATOR` ou plus —
> appliqué à la fois par le module et par le répartiteur de CheeseDash — et `OPERATOR` est
> aussi la barre maximale qu'une route de module puisse atteindre. Accorde ce rôle en
> conséquence. Détails dans
> [dashboard](docs/dashboard.fr.md#authentification-et-gardes-de-rôle).

## Avertissements honnêtes

- **`soulbound` supprime l'item.** Les items sont retirés de l'inventaire avant la mort et
  mis de côté, mais la méthode qui les rend n'est jamais appelée. Voir
  [items](docs/items.fr.md#les-options-qui-ne-font-rien).
- `droppable`, `tradeable`, `placeable`, `zoneId` et `onPickupEffects` sont stockés, servis
  par l'API et affichés dans l'éditeur, et ne changent rien en jeu.
- Le système de morphos pilote CobbleShift en exécutant une chaîne de commande. Il cassera
  en silence le jour où `cshift` changera de syntaxe.
- Le resource pack généré remplace `assets/minecraft/font/default.json` et le modèle d'item
  de chaque item de base utilisé. Un autre pack touchant les mêmes fichiers entrera en
  conflit.

## Licence

[PolyForm Noncommercial 1.0.0](../LICENSE), avec une permission additionnelle pour les
opérateurs de serveur. Tu peux le faire tourner sur un serveur qui reçoit des dons ou vend
des rangs ; tu ne peux pas vendre le mod lui-même.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Discord Arkensia](https://discord.gg/fYmPwMBjdB)
