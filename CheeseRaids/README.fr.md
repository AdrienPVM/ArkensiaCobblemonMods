# CheeseRaids

> 🇬🇧 [Read in English](README.md)

> ## ⚠️ Expérimental — jamais joué avec des joueurs
>
> Ce framework compile et a été déployé sur des instances de test. Il n'a **jamais tourné
> sur un serveur de production, et aucun raid n'a jamais été joué.** Quatre coordonnées de
> sa configuration sont des bouchons à relever en jeu avant que quoi que ce soit
> fonctionne, cinq fonctionnalités prévues sont incomplètes, et quatre de ses sept
> dépendances passent par de la réflexion sans être déclarées. Installe-le en t'attendant
> à trouver des problèmes que personne n'a encore rencontrés.

Un framework de raids serveur, planifiés et minutés. Un raid, c'est un fichier JSON qui
décrit une annonce, une vague de Pokémon dans l'overworld, un NPC héraut, une arène de
parkour dans sa propre dimension, un boss et une table de récompenses. Le mod exécute le
cycle de vie ; le fichier décide de ce qui se passe.

Le premier et seul raid livré est l'**Invasion de Hoopa**. À l'heure dite, le ciel se
couvre d'un orage de minuit au-dessus d'un point choisi et une vague de Pokémon
incapturables en sort, menée par un alpha surdimensionné et luisant. Tue l'alpha et un
héraut Ho-Oh se matérialise au terme de cinq secondes d'éclairs qui s'accumulent. Par lui,
les joueurs entrent dans une faille vide où chacun reçoit sa propre branche de parkour de
75 blocs, générée depuis une graine dérivée de son UUID et animée bloc par bloc devant lui.
Les branches convergent vers un disque central, où un Hoopa Déchaîné géant est tiré à
travers un portail dès que tout le monde est arrivé. Battez-le et la faille s'effondre, en
renvoyant tout le monde dans l'overworld avec des portails et des feux d'artifice.

Voilà pour l'intention. [Ce que le code fait réellement](docs/hoopa-invasion.fr.md) s'en
approche, avec des manques nommés — le héraut n'a aucun dialogue tant que tu ne l'as pas
câblé toi-même, et le combat de boss est un combat Cobblemon ordinaire plutôt qu'une
mécanique de raid coopératif.

**Minecraft 1.21.1 · Fabric · Java 21 · 21 fichiers source, ~3 340 lignes**

---

## Prérequis

| | |
|---|---|
| **Obligatoire** | [CheeseDash](../CheeseDash/) · [CheeseGuard](../CheeseGuard/) · Fabric Loader ≥ 0.16.0 · Fabric API · Java 21 |
| **Obligatoire en pratique** | [Cobblemon](https://modrinth.com/mod/cobblemon) — non déclaré, mais le raid ne fait apparaître personne sans lui |
| **Pour le héraut** | [EasyNPC](https://modrinth.com/mod/easy-npc) |
| **Pour les portails de victoire** | CobbleSafari |
| **Facultatif** | [CheeseStore](../CheeseStore/) — le versement de monnaie `reward.fromage` |

Côté serveur uniquement : pas d'entrypoint client, pas de code client, pas de mixin. Chaque
effet est une particule, un son ou un paquet de titre envoyé par le serveur.

CheeseDash et CheeseGuard sont des dépendances dures et Fabric refuse de démarrer sans
elles. Les quatre autres passent par de la réflexion et ne sont déclarées nulle part :
rien ne t'avertit quand il en manque une — [ce qui casse, et comment](docs/integrations.fr.md).

## Installation

```bash
cd CheeseDash    && ./gradlew build     # CheeseRaids compile contre les deux jars
cd ../CheeseGuard && ./gradlew build
cd ../CheeseRaids && ./gradlew build
```

Dépose `build/libs/cheeseraids-1.0.0+1.21.1.jar` et les deux jars frères dans le dossier
`mods/` du serveur. Au premier démarrage, le mod écrit
`config/cheeseraids/raids/hoopa.json` et journalise :

```text
[CheeseRaids] initialized — modular raid framework ready.
[CheeseRaids] Loaded 1 raid definition(s), 0 active instance(s)
```

Ce fichier n'est **pas utilisable en l'état.** Quatre de ses positions valent l'origine du
monde par défaut. La [calibration](docs/calibration.fr.md) est l'étape que tu ne peux pas
sauter.

## Documentation

| Page | Contenu |
|---|---|
| [Installation](docs/installation.fr.md) | Prérequis, ordre de build, ce que crée le premier démarrage, comment vérifier le chargement, désinstallation propre et ce qui reste derrière |
| [Prise en main](docs/getting-started.fr.md) | Un tutoriel : d'une installation neuve à une Invasion de Hoopa complète, toutes les phases vues |
| [Commandes](docs/commands.fr.md) | Les six sous-commandes, argument par argument, avec les niveaux de permission, les sorties et tous les messages d'échec |
| [Configuration](docs/configuration.fr.md) | `hoopa.json` en entier : chaque clé, son défaut et ses interactions, y compris celles qui ne font rien, plus un exemple annoté |
| [Calibration](docs/calibration.fr.md) | Comment relever en jeu les quatre coordonnées bouchons et vérifier chacune d'elles. La page la plus utile de la série |
| [Le modèle de raid](docs/raid-model.fr.md) | Ce qu'est un raid, les huit phases, l'arène en étoile, et un partage honnête entre le générique et le propre à Hoopa |
| [L'Invasion de Hoopa](docs/hoopa-invasion.fr.md) | La séquence réelle du raid livré, tick par tick, avec ses manques nommés |
| [Intégrations externes](docs/integrations.fr.md) | Les quatre hooks par réflexion, pourquoi les commandes ont été abandonnées au profit d'appels directs, et ce qu'une mise à jour de dépendance casse |
| [API dashboard](docs/dashboard.fr.md) | Les six routes REST sous `/api/v1/raids`, les rôles exigés, l'unique type de message WebSocket |
| [Architecture](docs/architecture.fr.md) | Découpage des packages, responsabilités des classes, état et disque, threading, points d'extension, faiblesses connues |
| [Dépannage](docs/troubleshooting.fr.md) | Symptôme, cause, remède — y compris les échecs qui ressemblent à des succès vus du jeu |

## Les commandes en un coup d'œil

| Commande | Effet | Permission |
|---|---|---|
| `/cheeseraids start <raid>` | démarre un raid immédiatement | niveau 2 |
| `/cheeseraids stop <raid>` | l'arrête et nettoie l'arène | niveau 2 |
| `/cheeseraids status` | chaque raid chargé, sa phase et sa planification | niveau 2 |
| `/cheeseraids reload` | relit les fichiers de définition | niveau 2 |
| `/cheeseraids herald <raid> info \| show \| hide` | diagnostique et bascule le NPC héraut | niveau 2 |
| `/cheeseraids testreward <raid>` | ouvre les portails à l'ancrage, sans raid | niveau 2 |
| `/cheeseraids enter <raid>` | entre dans la faille ouverte | aucune |

L'identifiant du raid vient en premier dans chacune d'elles, y compris `herald`, où l'action
le suit. Il n'y a pas de complétion par tabulation pour cet identifiant ;
`/cheeseraids status` les liste. Détail complet dans [commandes](docs/commands.fr.md).

## Dashboard

S'enregistre sous le préfixe `raids` : `/api/v1/raids/*`, avec une vue d'ensemble en
lecture seule des définitions et des instances en cours, et les contrôles démarrer, arrêter
et recharger. Les définitions elles-mêmes sont en lecture seule côté REST — un raid se
modifie comme un fichier sur le disque, puis se recharge.

Un seul canal WebSocket, `raids`, un seul type de message, émis uniquement aux changements
de phase.

## Licence

[PolyForm Noncommercial 1.0.0](../LICENSE), avec une permission additionnelle pour les
opérateurs de serveur. Tu peux faire tourner ce mod sur un serveur qui reçoit des dons ou
vend des rangs ; tu ne peux pas vendre le mod lui-même.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Discord Arkensia](https://discord.gg/fYmPwMBjdB)
