# CobbleShift

> 🇬🇧 [Read in English](README.md)

> Transforme joueurs et PNJ en Pokémon, et donne aux joueurs des bonus passifs liés au type de leur équipe.

Un admin tape `/cshift transform Steve charizard`, et chaque client équipé du mod cesse de
dessiner Steve pour dessiner un Dracaufeu à sa place. Steve garde son inventaire et sa
position. Sa vitesse, sa vie maximale et sa hitbox suivent les statistiques de base de
Dracaufeu, sa caméra passe à la troisième personne derrière le Pokémon, et un clic gauche
lance une boule de feu au lieu d'un coup de poing. Le serveur ne fait jamais apparaître de
Pokémon : le joueur est rendu invisible et chaque client dessine un modèle fantôme à ses
coordonnées.

Le même moteur de rendu transforme les personnages EasyNPC en Pokémon. Une transformation
de PNJ est purement visuelle et sauvegardée sur disque : le Ronflex qui bloque une route
est toujours là après un redémarrage, alors que les transformations de joueurs sont faites
pour durer le temps d'un événement et prennent fin à un arrêt propre.

Le troisième volet n'a rien à voir avec la transformation. Une fois les capacités passives
activées, le Pokémon du premier emplacement de l'équipe d'un joueur lui accorde un effet de
statut thématique : un type Feu donne Résistance au feu, un type Eau donne Respiration
aquatique et Grâce du dauphin, et le bonus suit l'équipe quand elle change. Les événements
CheeseGuard et les courses CheeseKart déclenchent aussi des transformations, et un module
CheeseDash expose l'ensemble en REST.

**Minecraft 1.21.1 · Fabric · Java 21 · 34 fichiers, ~6 780 lignes**

---

## Prérequis

| | |
|---|---|
| **Requis** | Fabric Loader ≥ 0.16.0 · [Fabric API](https://modrinth.com/mod/fabric-api) · Java 21 · le mod sur le serveur **et** sur les clients |
| **Requis en pratique** | [Cobblemon](https://modrinth.com/mod/cobblemon). Déclaré optionnel, mais sans lui toute recherche d'espèce échoue |
| **Optionnels** | [CheeseDash](../CheeseDash/) pour le panneau d'administration · [EasyNPC](https://modrinth.com/mod/easy-npc) pour les transformations de PNJ |

CobbleShift n'atteint Cobblemon que par réflexion et ne l'importe jamais : c'est pour cela
que le jar se charge sans Cobblemon. Cela signifie aussi qu'une mise à jour de Cobblemon
peut casser sans bruit la recherche des espèces ou le modèle fantôme ; voir
[l'architecture](docs/architecture.fr.md#la-réflexion-et-pourquoi-les-noms-de-méthode-comptent).

## Installation

```bash
cd CheeseDash && ./gradlew build     # CobbleShift compile contre son jar
cd ../CobbleShift && ./gradlew build
```

Dépose `build/libs/cobbleshift-1.0.0+1.21.1.jar` dans `mods/` sur le serveur **et** sur
chaque client. Un client qui ne l'a pas ne voit tout simplement personne à la place d'un
joueur transformé. Au premier démarrage, le serveur écrit `config/cobbleshift.json` et
journalise :

```text
[CobbleShift] Initializing...
[CobbleShift] Initialized successfully!
```

Procédure complète : [installation](docs/installation.fr.md).

## Documentation

| Page | Contenu |
|---|---|
| [Installation](docs/installation.fr.md) | Prérequis, ordre de compilation, fichiers créés au premier démarrage, preuve du chargement, désinstallation propre |
| [Premiers pas](docs/getting-started.fr.md) | Un tutoriel : d'un serveur vierge à un Dracaufeu transformé, un bonus passif et un PNJ qui survit au redémarrage |
| [Commandes](docs/commands.fr.md) | Les treize sous-commandes de `/cshift`, argument par argument, avec leur sortie et chaque message d'échec |
| [Configuration](docs/configuration.fr.md) | Les seize clés de `cobbleshift.json`, leurs bornes, les trois qui ne font rien, un fichier annoté |
| [Transformations](docs/transforms.fr.md) | Ce qu'une transformation de joueur ajoute et retire, les formules de statistiques, les attaques, la durée, les crashs |
| [Capacités passives](docs/abilities.fr.md) | La boucle de rafraîchissement, l'effet de chaque type, la lecture du Pokémon de tête |
| [Transformations de PNJ](docs/npc-transforms.fr.md) | Le ciblage des EasyNPC, le modèle purement visuel, le modificateur de vitesse, le fichier de sauvegarde et ses pièges |
| [API du dashboard](docs/dashboard.fr.md) | Les quinze routes REST sous `/api/v1/shift`, formes des requêtes et réponses, rôles requis |
| [Architecture](docs/architecture.fr.md) | Packages, état et disque, threading, payloads, les dix mixins, la réflexion, l'API externe de fait, les faiblesses connues |
| [Dépannage](docs/troubleshooting.fr.md) | Symptôme, cause, remède, et ce qu'il faut mettre dans un rapport de bug |

## Les commandes en un coup d'œil

Toutes exigent le niveau de permission 2.

| Commande | Effet |
|---|---|
| `/cshift transform <player> <pokemon [shiny] [noattacks] [form…]>` | transforme un joueur |
| `/cshift revert <player>` · `/cshift revertall` | ramène un joueur, ou tous, à sa forme normale |
| `/cshift scale <player> <value>` · `/cshift speed <player> <value>` | change la taille ou la vitesse d'un joueur transformé |
| `/cshift ability <player> [pokemon]` | force une capacité passive, ou revient au mode automatique |
| `/cshift ability global enable\|disable` | l'interrupteur général des capacités passives, sauvegardé sur disque |
| `/cshift ability toggle <player>` | exclut un joueur des capacités passives, jusqu'au redémarrage |
| `/cshift transformnpc <pokemon…>` · `revertnpc` · `revertallnpc` | la même chose pour le PNJ EasyNPC que tu regardes |
| `/cshift scalenpc <value>` · `/cshift speednpc <value>` | règle le PNJ que tu regardes |
| `/cshift list` | transformations de joueurs et capacités actives |
| `/cshift species <name>` | les statistiques d'une espèce et ce que CobbleShift en tire |

Le joueur vient en premier dans `transform`. Aucune commande ne permet à un joueur de se
transformer ou de reprendre forme lui-même. Détails dans [les commandes](docs/commands.fr.md).

## Avertissements honnêtes

- ⚠️ **La mort n'annule pas une transformation**, alors que la clé de config
  `autoRevertOnDeath` vaut `true` par défaut. Rien n'appelle le code qui se cache derrière.
- **Les clients vanilla voient les joueurs transformés comme invisibles.** Installe le mod
  sur tous les clients.
- **L'ancienne documentation décrit un mini-jeu CobbleHunt et un jeu Hot Potato.** Aucun des
  deux n'existe dans ces sources. Il reste deux clés de config `hotPotato*`, jamais lues.
- **Les transformations de joueurs ne survivent pas à un crash.** La connexion suivante
  nettoie les restes ; voir
  [le modèle de transformation](docs/transforms.fr.md#déconnexions-redémarrages-et-crashs).
- Le mod a tourné sur le serveur Arkensia. Il n'a aucun test automatisé.

## Licence

[PolyForm Noncommercial 1.0.0](../LICENSE), avec une permission additionnelle pour les
opérateurs de serveur. Tu peux le faire tourner sur un serveur qui reçoit des dons ou vend
des rangs ; tu ne peux pas vendre le mod lui-même.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Discord Arkensia](https://discord.gg/fYmPwMBjdB)
