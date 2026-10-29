# CobbleCheeseScore

> 🇬🇧 [Read in English](README.md)

> Trente-quatre classements Cobblemon, chacun rendu en PNG que tu peux accrocher à un mur
> en jeu et y laisser.

Le mod lit les données des joueurs directement sur le disque — le NBT du Pokédex de
Cobblemon, son JSON de données joueur, et les statistiques vanilla — classe tout le monde
sur les classements que tu as activés, et dessine chaque classement dans une image avec
`Graphics2D`. Rien n'est réécrit dans le dossier du monde, et rien n'est écrit sur le
disque du tout : les images vivent en mémoire et sont encodées en octets PNG à chaque
requête.

Ce qui justifie de l'installer, c'est le dernier maillon de cette chaîne. Chaque
classement est servi sur une URL stable qui ne demande aucune authentification, ce qui
permet à un mod de cadre d'image installé sur les clients de pointer dessus : le classement
devient alors un élément du monde. Un joueur passe devant le panneau d'affichage du spawn
et voit qui est en tête du compteur de shinies, avec un horodatage de rendu en pied
d'image qui prouve que c'est à jour. Le rafraîchissement se fait sur minuterie — dix
minutes par défaut — sans commande à lancer et sans fichier à copier.

Il n'y a aucune commande. Les administrateurs pilotent tout depuis le dashboard CheeseDash
ou son API REST : recolorer un classement, le renommer, l'éteindre, forcer un
rafraîchissement. Un registre de mini-jeux séparé enregistre les résultats d'événements que
rien dans le jeu ne compte automatiquement, notés selon une règle de points de podium.

**Minecraft 1.21.1 · Fabric · Java 21 · 16 fichiers source, ~2 940 lignes · côté serveur uniquement**

---

## Prérequis

| | |
|---|---|
| **Requis** | [CheeseDash](../CheeseDash/) · Fabric Loader ≥ 0.16.0 · Fabric API · Java 21 |
| **Requis en pratique** | [Cobblemon](https://modrinth.com/mod/cobblemon) — déclaré dans `suggests`, donc le mod charge sans lui, mais 30 des 34 classements n'ont alors rien à classer |
| **Optionnel** | [CobbleCap](../CobbleCap/) — alimente les quatre classements de ligue Conseil 4. Sans lui, ils sont rendus vides |
| **Pour l'affichage en jeu** | un mod de cadre d'image sur les **clients**, comme WaterFrames. Ce n'est pas une dépendance et ce mod ne l'installe pas |

`"environment": "server"`, et le seul entrypoint est `DedicatedServerModInitializer`. Le
jar ne fait rien dans le dossier `mods/` d'un client, et rien ne démarre en solo.

## Installation

CobbleCheeseScore compile contre le jar de CheeseDash via un glob `fileTree` : CheeseDash
doit donc être **construit** en premier.

```bash
cd CheeseDash && ./gradlew build
cd ../CobbleCheeseScore && ./gradlew build
```

Dépose `build/libs/cobblecheesescore-1.0.0+1.21.1.jar` et le jar CheeseDash dans le
dossier `mods/` du serveur. Au premier démarrage, le mod écrit
`config/cobblecheesescore.json` avec 30 classements, en sème quatre de plus pour le
Conseil 4, et journalise :

```text
[cobblecheesescore] CobbleCheeseScore initialized successfully!
[CheeseDash] Public context registered: /scores/
[cobblecheesescore] Scoreboard update scheduler started (interval: 10 minutes)
```

Marche à suivre complète : [installation](docs/installation.fr.md).

## Documentation

| Page | Contenu |
|---|---|
| [Installation](docs/installation.fr.md) | Prérequis, ordre de build, ce que crée le premier démarrage, les lignes de log qui prouvent le chargement, désinstallation propre |
| [Premiers pas](docs/getting-started.fr.md) | Un tutoriel : d'un serveur neuf à un classement recoloré accroché à un mur et qui se rafraîchit tout seul |
| [Configuration](docs/configuration.fr.md) | `cobblecheesescore.json` en entier, ce qui se recharge et ce qui exige un redémarrage, les neuf clés qui ne font rien, et un exemple complet annoté |
| [Classements](docs/leaderboards.fr.md) | Chaque champ d'un classement avec son type et son défaut, ce que mesurent les 34 identifiants, l'origine des données, la périodicité, et les classements Conseil 4 alimentés de l'extérieur |
| [Export PNG](docs/png-export.fr.md) | Comment l'image est dessinée, quelle taille elle fait, quelles polices elle demande, les deux routes qui la servent, et la marche à suivre complète pour l'afficher sur une carte en jeu |
| [Mini-jeux](docs/minigames.fr.md) | Le registre de résultats d'événements, sa règle de points de podium, le plafond de 500 sessions et le panthéon permanent |
| [API dashboard](docs/dashboard.fr.md) | Chaque route sous `/api/v1/scores`, forme des requêtes et des réponses, codes de statut, et le rôle que chacune exige réellement |
| [Architecture](docs/architecture.fr.md) | Arborescence des packages, responsabilités des classes, threading et course au rafraîchissement, points d'extension, et une liste franche des faiblesses |
| [Dépannage](docs/troubleshooting.fr.md) | Symptôme, cause, remède — dont les quatre raisons pour lesquelles un cadre en jeu n'affiche rien |

## Les commandes en un coup d'œil

**Aucune.** Le mod n'enregistre aucune commande : il n'y a pas de package `commands/` et
aucun appel à `CommandRegistrationCallback` dans tout le code. Chaque opération passe par
CheeseDash.

| Ce que tu veux faire | Comment |
|---|---|
| voir tous les classements et leurs meilleurs joueurs | `GET /api/v1/scores/scoreboards` |
| récupérer le PNG rendu d'un classement | `GET /api/v1/scores/scoreboards/<id>/image` |
| le récupérer sans authentification, pour l'usage en jeu | `GET /scores/api/images/<name>/<id>.png` |
| renommer, recolorer, redimensionner ou désactiver un classement | `PUT /api/v1/scores/scoreboards/<id>` |
| forcer un rafraîchissement complet tout de suite | `POST /api/v1/scores/refresh` |
| enregistrer un résultat de mini-jeu | `POST /api/v1/scores/minigames/<id>/sessions` |

## Dashboard

Le mod s'enregistre sous le préfixe `scores`, ce qui donne `/api/v1/scores/*`, plus une
route publique sans authentification sur `/scores/api/images/…` pour que quelque chose à
l'intérieur de Minecraft puisse récupérer un PNG sans token. Aucun canal WebSocket : le
module ne diffuse rien, donc une vue du dashboard doit interroger périodiquement.

⚠️ **Aucune route de ce module ne vérifie le rôle de l'appelant.** `ScoresApi` ne contient
aucun appel à `WebDashboard.requireWrite` ni à `requireAdmin` : un compte CheeseDash
**READ_ONLY** peut donc réécrire les titres et les couleurs des classements, créer et
supprimer des mini-jeux, supprimer des sessions enregistrées et forcer des
rafraîchissements. Distribue les comptes en conséquence. Détails dans
[dashboard](docs/dashboard.fr.md#rôle-requis).

## Avertissements honnêtes

- **La route d'image publique est ouverte à quiconque atteint le port 44311.** Elle sert
  des noms de joueurs et des statistiques sans authentification. C'est le compromis
  délibéré qui rend l'affichage en jeu possible ; place le port derrière un proxy si cela
  ne te convient pas.
- **Neuf clés de configuration ne font rien.** `port`, `enableWebInterface` et
  `enableJsonApi` sont des reliquats d'un serveur web autonome sur le port 44310, supprimé
  quand le mod est passé à CheeseDash. `enableDiscordWebhook` et `discordWebhookUrl`
  décrivent une fonctionnalité qui n'a aucune implémentation — **ce mod n'envoie rien à
  Discord**, quoi qu'en dise une documentation plus ancienne. `enableHistory`,
  `historyRetentionDays`, `defaultMaxPlayers` et `defaultRowsPerColumn` sont rapportées par
  l'API et exploitées nulle part. Liste complète dans
  [configuration](docs/configuration.fr.md#les-clés-que-rien-ne-lit).
- **`PUT /api/v1/scores/config` répond `{"saved":true}` et n'applique rien.** Utilise
  `PUT /scoreboards/:id` pour les changements de classement, et édite le fichier pour les
  clés globales.
- **Les images demandent la police Inter**, qu'un serveur sans affichage n'a presque jamais
  installée. Elles sont rendues dans une police de repli aux métriques différentes.
- **`survivor` classe le plus grand nombre de morts en premier**, pas le plus petit. Son
  titre, *Roi des Morts*, est exact ; son identifiant ne l'est pas.
- **Deux constantes Pokémon sont codées en dur** et vieillissent à chaque génération : la
  liste des espèces légendaires dans `PlayerData` et le total de 1 025 espèces utilisé pour
  le pourcentage du Pokédex.

## Licence

[PolyForm Noncommercial 1.0.0](../LICENSE), avec une permission additionnelle pour les
opérateurs de serveur. Tu peux faire tourner ce mod sur un serveur qui accepte des dons ou
vend des rangs ; tu ne peux pas vendre le mod lui-même.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Discord Arkensia](https://discord.gg/fYmPwMBjdB)
