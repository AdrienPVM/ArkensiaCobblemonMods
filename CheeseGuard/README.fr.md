# CheeseGuard

> 🇬🇧 [Read in English](README.md)

> Protection de régions, dimensions créées à chaud et moteur d'événements chronométrés,
> administrés depuis CheeseDash.

Le plus gros mod de ce dépôt, et celui auquel la plupart des autres se raccrochent. Il a
commencé comme un mod de protection : une zone est une boîte à laquelle sont attachés 31
flags décidant si un joueur peut y casser des blocs, s'y battre, y sortir un Pokémon, y
subir des dégâts de chute, ou même y entrer. Les membres et les groupes font ce que tu
attends — le propriétaire d'une zone peut construire sur sa propre parcelle, et un groupe
de zones partage un seul jeu de règles.

Puis il a grandi. La même notion de « région avec des règles » s'est révélée être ce dont
un événement a besoin, d'où un moteur d'événements : participants, équipes, scores sur les
blocs cassés, les mobs tués et les poissons pêchés, phases pilotées par une timeline, et
effets qui vont jusque dans CobbleShift ou CheeseKart pour transformer tout le monde ou
mettre tous les joueurs en kart au même tick. Les dimensions en avaient besoin aussi : le
mod crée et supprime des mondes à chaud, donne à chaque joueur une copie privée de l'un
d'eux, en prend des snapshots et les remet à zéro. Une prison, un système de vente de
terrains avec panneaux cliquables, des sets de chunks qui régénèrent une partie de la carte
selon un planning, le prop hunt, le spleef et la pêche compétitive se sont empilés par
dessus.

En jeu, il est invisible quand il fonctionne. Un joueur entre dans le marché et ne peut pas
le saccager, clique sur un panneau *à vendre* et devient propriétaire d'une parcelle, tape
`/cg event join` et se retrouve téléporté dans un minijeu avec son inventaire mis de côté,
finit dernier et le récupère. L'administrateur n'ouvre jamais une fenêtre de chat : tout
sauf les quelques commandes ci-dessous vit dans le dashboard.

**Minecraft 1.21.1 · Fabric · Java 21 · 140 fichiers source, ~35 100 lignes**

---

## ⚠️ À lire avant un serveur public

**Six des huit API dashboard ne vérifient jamais le rôle du compte elles-mêmes.** La
frontière des rôles tient quand même, mais elle est tenue par CheeseDash et non par
CheeseGuard : le répartiteur de modules du cœur exige `OPERATOR` ou au-dessus pour toute
requête non-`GET` vers un module, donc une session `READ_ONLY` peut lire et rien de plus.
Ce que coûte l'absence de contrôles dans le module, c'est la précision — aucune route de
CheeseGuard ne peut exiger `SUPER_ADMIN`, si bien que supprimer une zone ou effacer une
dimension est à la même barre `OPERATOR` que modifier un flag, et soixante-cinq routes
mutantes n'ont aucune seconde ligne de défense si ce répartiteur change un jour.
[Pourquoi, et ce que cela implique](docs/architecture.fr.md#faiblesses-connues).

**Sur un jar construit avant le 29 octobre 2026, quatre commandes mutantes n'avaient
aucune garde de permission** — `/cg flag`, `/cg addmember`, `/cg removemember` et tout le
sous-arbre `/cg group` étaient appelables par n'importe quel joueur. Corrigé ; mets le
jar à jour et vérifie lequel tu fais tourner.
[Le tableau des gardes actuelles](docs/commands.fr.md#niveaux-de-permission).

---

## Prérequis

| | |
|---|---|
| **Requis** | [CheeseDash](../CheeseDash/) · Fabric Loader ≥ 0.15.11 · Fabric API · Java 21 |
| **Suggéré** | [Waystones](https://modrinth.com/mod/waystones) — supprimer une dimension retire aussi ses waystones |
| **Optionnels** | [Cobblemon](https://modrinth.com/mod/cobblemon) · [CobbleShift](../CobbleShift/) · [CheeseKart](../CheeseKart/) · [CobblemonDungeons](../CobblemonDungeons/) · [CheeseStore](../CheeseStore/) · Numismatic Overhaul · Jade · JourneyMap · FTB Quests |

CheeseDash est une dépendance **obligatoire** : le mod ne se chargera pas sans lui.

Le jar va sur le serveur et sur le client. Tout fonctionne côté serveur seul ; le client
ajoute les flags `nametag-hide` et `minimap-hide`, deux HUD et le menu de propriétaire de
zone.

## Installation

```bash
cd CheeseDash && ./gradlew build     # CheeseGuard compile contre son jar
cd ../CheeseGuard && ./gradlew build
```

Dépose `build/libs/cheeseguard-1.0.0+1.21.1.jar` et le jar de CheeseDash dans le `mods/` du
serveur. Au premier démarrage, le mod crée un `config/cheeseguard/` vide et journalise :

```text
[CheeseGuard] CheeseGuard loaded!
```

Marche à suivre complète, côté client et désinstallation propre comprises :
[installation](docs/installation.fr.md).

## Documentation

| Page | Contenu |
|---|---|
| [Installation](docs/installation.fr.md) | Prérequis, ordre de build, serveur contre client, ce que crée le premier démarrage, comment vérifier le chargement, désinstallation propre |
| [Premiers pas](docs/getting-started.fr.md) | Un tutoriel : d'un serveur vierge à une zone protégée, un groupe, une arène restaurable et une prison qui marche |
| [Commandes](docs/commands.fr.md) | Zones, sélection, flags, membres, groupes, états sauvegardés, `/cg buy`, `/chunksuggest` — avec les niveaux de permission |
| [Événements, prison et exploitation](docs/commands-operations.fr.md) | Le sous-arbre `/cg event`, le hot potato, les mondes par joueur, les limites de pâturages, la restauration d'inventaire, l'arbre `/prison` |
| [Configuration](docs/configuration.fr.md) | Les quatorze fichiers de configuration et neuf dossiers : contenu de chacun, les quatre éditables à la main, toutes les clés |
| [Zones et flags](docs/zones-and-flags.fr.md) | Le modèle de zone : géométrie, les 31 flags, héritage, priorité, recouvrement, membres, ventes de terrain |
| [Groupes](docs/groups.fr.md) | Grouper des zones pour qu'elles partagent des flags par défaut, et comment la résolution retombe |
| [Dimensions](docs/dimensions.fr.md) | Création de dimension à chaud, mondes par joueur, snapshots, remises à zéro, bordures, règles d'entrée |
| [Événements](docs/events.fr.md) | Le cycle de vie d'un événement, participants, équipes, scores, templates, les modes de minijeu |
| [Timeline](docs/timeline.fr.md) | Le langage d'actes et d'effets dans lequel s'écrit le programme d'un événement |
| [Joueurs](docs/players.fr.md) | La gestion des joueurs depuis le dashboard : kick, téléportation, mode de jeu, messages, indicateurs d'état |
| [Chunks](docs/chunks.fr.md) | Sets de chunks, régénération planifiée de la carte, suggestions des joueurs, le chemin de suppression et de sauvegarde |
| [Prison](docs/prison.fr.md) | Confinement, actions bloquées, peines et historique, le sosie hors ligne |
| [Courses](docs/races.fr.md) | Définition des circuits, scan et chronométrage |
| [API dashboard](docs/dashboard.fr.md) | Chaque route REST sous `/api/v1/cheeseguard`, avec les rôles requis et le canal WebSocket `prison` |
| [Architecture](docs/architecture.fr.md) | Carte des packages, classes clés, threads, persistance, les 44 mixins groupés par cible, faiblesses connues |
| [Dépannage](docs/troubleshooting.fr.md) | Symptôme, cause, remède — dont les trois façons qu'ont les zones de disparaître en silence |

## Les commandes d'un coup d'œil

Trois commandes racines. **`/cg`, pas `/cheeseguard`** — il n'y a pas d'alias long.

| Commande | Rôle | Niveau |
|---|---|---|
| `/cg wand` · `/cg pos1` · `/cg pos2` | sélectionner un volume | 2 |
| `/cg create <name>` · `/cg delete <id>` | créer ou retirer une zone | 2 |
| `/cg list` · `/cg info <id>` | inspecter les zones | 2 |
| `/cg tp <id>` · `/cg toggle` | se téléporter vers une zone, afficher les contours | aucun |
| `/cg flag <zone> <flag> <value>` | poser un flag | 2 |
| `/cg addmember` · `/cg removemember` | appartenance à une zone | 2 |
| `/cg group create\|delete\|add` | groupes | 2 |
| `/cg state capture\|list\|delete\|apply` | états de zone sauvegardés | 2 |
| `/cg buy <zone> [confirm]` | acheter une zone en vente | aucun |
| `/cg event join\|leave\|spectate\|list\|launch` | commandes d'événement pour les joueurs | aucun |
| `/cg event announce\|score\|snapshot\|template` | administration des événements | 2 |
| `/cg hotpotato on\|off\|bomb\|explode` | le jeu du hot potato | 2 |
| `/cg playerworld list\|delete` | dimensions par joueur | 2 |
| `/cg setspawn` · `/cg pasture` · `/cg restoreinv` | spawn de dimension, plafonds de pâturages, récupération d'inventaire | 2 |
| `/cg adv check\|list` | dump des advancements | aucun |
| `/prison set\|release\|list\|info\|setzone\|setout\|clearout` | la prison | 2 |
| `/chunksuggest` | suggérer ta position pour une régénération de carte | aucun |

Les lignes marquées *aucun* sont ouvertes à tous les joueurs : deux d'entre elles par choix
(`/cg buy`, `/chunksuggest`), les autres parce que personne n'a pris le temps de les
protéger. Détail complet dans [les commandes](docs/commands.fr.md) et
[l'exploitation](docs/commands-operations.fr.md).

## Dashboard

Enregistré sous le préfixe `cheeseguard` : tout vit donc sous `/api/v1/cheeseguard/*`,
réparti sur huit API — zones, groupes, dimensions, événements, joueurs, chunks, courses,
prison. Un seul canal WebSocket est publié, `prison` ; tout le reste se consulte par
scrutation. Les flags de groupe, la création de dimension, l'éditeur de timeline, les sets
de chunks, les circuits, les webhooks et la configuration de vente d'une zone n'existent
**que** là, sans équivalent en commande.

## Ce qu'il faut savoir avant de mettre les mains dedans

- **Les états d'événement sont `IDLE → ANNOUNCED → QUEUE → COUNTDOWN → ACTIVE → ENDING →
  CLEANUP`.** Il n'y a pas de `PAUSED`, il n'existe aucune pause ni reprise dans le mod, et
  en ajouter une est plus dur qu'il n'y paraît : le temps restant est calculé depuis des
  horodatages d'horloge murale, dont plusieurs sont persistés.
- **Les identifiants de dimension par joueur sont
  `<masterDimId>_<8 premiers caractères hex de l'UUID du joueur>`.**
- **Les identifiants de dimension natifs contiennent un deux-points**
  (`minecraft:overworld`), ce qui casse une analyse de chemin naïve. Les recherches de
  snapshot et de dimension par joueur sont délibérément sautées pour eux.
- **Les templates d'événement vivent dans `config/cheeseguard_templates/`**, pas dans
  `config/templates/`.
- **Les participants d'un événement reviennent sous forme de `Map`, pas de `List`** —
  itère sur `.values()`.
- **La suppression d'une dimension attend 400 à 500 ms avant d'effacer les fichiers sous
  Windows**, parce que les verrous de fichier survivent au déchargement, et elle peut
  échouer quand même.
- **Les sauvegardes ne sont pas atomiques.** Un plantage pendant l'écriture de
  `regions.json` perd toutes les zones, silencieusement, parce que le chargeur avale les
  erreurs d'analyse. Sauvegarde `config/cheeseguard/` avec ton monde.

## Aspérités connues

Ce mod a grandi sous la pression des délais, sur un serveur en production. Il fonctionne,
il tourne depuis des mois avec des centaines de joueurs, et des pans entiers ne sont pas
jolis : `MiniGameManager` fait à lui seul 5 244 lignes couvrant quatre jeux sans rapport,
chaque classe atteint toutes les autres par un `CheeseGuardMod.INSTANCE` statique, il n'y a
aucun test, et quatre mixins visent des mods tiers par leur nom de classe et casseront quand
ces mods changeront. La liste franche est la dernière section de
[l'architecture](docs/architecture.fr.md#faiblesses-connues).

Certains libellés par défaut — noms affichés des flags, message d'accueil de la prison,
description dans la barre latérale du dashboard — sont des chaînes **françaises** codées en
dur plutôt que des clés de langue. C'est cosmétique ; les vrais fichiers de langue sont
sous `src/main/resources/assets/cheeseguard/lang/`.

## Licence

[PolyForm Noncommercial 1.0.0](../LICENSE), avec une permission additionnelle pour les
opérateurs de serveur. Tu peux faire tourner ce mod sur un serveur qui accepte des dons ou
vend des rangs ; tu ne peux pas vendre le mod lui-même.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Discord Arkensia](https://discord.gg/fYmPwMBjdB)
