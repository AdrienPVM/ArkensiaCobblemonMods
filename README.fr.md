# Arkensia Cobblemon Mods

> 🇬🇧 [Read this README in English](README.md)

L'ensemble des mods Fabric développés pour **Arkensia**, un serveur Minecraft 1.21.1
Cobblemon. 21 mods, allant d'un dashboard web d'administration complet à la protection
de régions, aux boutiques joueurs, aux donjons, aux raids et à un système de niveau
maximum basé sur les badges.

Ces mods ont fait tourner un serveur en production avec des centaines de joueurs. Ils
sont publiés ici pour que la communauté puisse les lire, s'en inspirer, les corriger et
les faire vivre.

**Minecraft 1.21.1 · Fabric · Java 21**

---

## Commence par CheeseDash

La plupart de ces mods ne fonctionnent pas seuls. **[CheeseDash](CheeseDash/README.fr.md)** est le
pivot : un dashboard web auto-hébergé auquel les autres mods se branchent via un
entrypoint Fabric personnalisé. Si tu n'installes qu'un seul mod de ce dépôt, installe
celui-là.

Les administrateurs gèrent tout depuis le dashboard, les joueurs utilisent les
interfaces in-game. L'absence d'interface admin in-game est un choix d'architecture.

```
Navigateur ──HTTP 44311──┐
                         ├── CheeseDash ──┬── CheeseGuard
Navigateur ──WS   44312──┘                ├── CheeseMarket
                                          ├── CheeseStore
                                          └── … 10 autres modules
```

---

## Les mods

### Cœur

| Mod | Rôle | Nécessite |
|-----|------|-----------|
| **[CheeseDash](CheeseDash/README.fr.md)** | Dashboard web d'administration auto-hébergé (HTTP + WebSocket), authentification par session avec rôles, journal d'audit, webhooks Discord. Expose l'API de modules à laquelle les autres mods s'enregistrent. | — |

### Monde et joueurs

| Mod | Rôle | Nécessite |
|-----|------|-----------|
| **[CheeseGuard](CheeseGuard/README.fr.md)** | Protection de régions avec flags par zone, dimensions personnalisées avec snapshots et resets, système d'événements chronométrés complet, gestion des joueurs, outillage de chunks. Le plus gros mod du dépôt. | CheeseDash |
| **[CheeseMusic](CheeseMusic/README.fr.md)** | Musique d'ambiance par zone, avec fondu au franchissement des frontières de région. | CheeseGuard, ReactiveMusic sur les clients (en pratique) |
| **[CheesePatrol](CheesePatrol/README.fr.md)** | Patrouilles par waypoints pour les entités EasyNPC : les PNJ parcourent une boucle et s'arrêtent pour regarder les joueurs proches. | CheeseDash, EasyNPC (en pratique) |
| **[AetherialBridge](AetherialBridge/README.fr.md)** | Enregistre la génération d'îles flottantes d'Aetherial Islands comme type de génération de dimension CheeseGuard. L'overworld vanilla reste intact. | CheeseGuard, Aetherial Islands |

### Économie

| Mod | Rôle | Nécessite |
|-----|------|-----------|
| **[CheeseMarket](CheeseMarket/README.fr.md)** | Boutiques côté joueur avec interface plein écran personnalisée et éditeur de mise en page dans le dashboard. | CheeseDash |
| **[CheeseStore](CheeseStore/README.fr.md)** | Boutique web avec paiement Stripe, livraison en jeu, une monnaie de crédits et un catalogue de cosmétiques jouables. ⚠️ Traite de vrais paiements — lis son [README](CheeseStore/README.fr.md) et [SECURITY.md](SECURITY.md) avant toute chose. | CheeseDash, CheeseItems |
| **[CheesePurse](CheesePurse/README.fr.md)** | Un bouton dans l'écran d'inventaire qui balaie les pièces Numismatic Overhaul du joueur vers sa bourse, deux fois par seconde. | Numismatic Overhaul |

### Gameplay Cobblemon

| Mod | Rôle | Nécessite |
|-----|------|-----------|
| **[CobbleCap](CobbleCap/README.fr.md)** | Progression du niveau maximum des Pokémon par badges, détection des champions, Hall of Fame, chronos et dioramas de statues décoratifs. | Cobblemon (en pratique), Radical Cobblemon Trainers (pour les champions) |
| **[CobbleShift](CobbleShift/README.fr.md)** | Transforme les joueurs et les PNJ EasyNPC en Pokémon, avec des capacités passives selon le type et des attaques à distance. Atteint Cobblemon par réflexion, donc se charge sans lui — mais toute recherche d'espèce échoue alors. | Cobblemon (en pratique) |
| **[CobbleCheeseScore](CobbleCheeseScore/README.fr.md)** | Trente-quatre classements Cobblemon, chacun rendu en PNG servi sur une URL stable pour les cadres d'image en jeu. | CheeseDash, Cobblemon (en pratique) |
| **[CobblemonDungeons](CobblemonDungeons/README.fr.md)** | Système de donjons configurable : instances, tickets, dresseurs, classements et historique des runs. | Cobblemon, RCTApi, CheeseDash |
| **[CheeseExtraCommands](CheeseExtraCommands/README.fr.md)** | Commandes de vérification utilisées par les chaînes de quêtes PNJ : contrôle des EV, IV, espèces et variété de balls, plus le suivi du tour de l'île d'Arkensia. | Cobblemon (en pratique) |

### Événements et contenu

| Mod | Rôle | Nécessite |
|-----|------|-----------|
| **[EggAndCheese](EggAndCheese/README.fr.md)** | Chasse aux œufs collectionnables générés procéduralement, avec génération par ADN immuable et aperçu 3D dans le dashboard. | CheeseDash |
| **[CheeseRaids](CheeseRaids/README.fr.md)** | Framework de raids multijoueurs chronométrés : arènes procédurales, boss coopératifs et récompenses. ⚠️ Expérimental — voir son README. | CheeseDash, CheeseGuard, Cobblemon (en pratique) |
| **[CheeseKart](CheeseKart/README.fr.md)** | Mode course de karts construit sur des bateaux invisibles et la physique OpenBoatUtils, avec presets réglables. | CheeseDash, OpenBoatUtils sur les clients (fortement recommandé) |
| **[CheeseItems](CheeseItems/README.fr.md)** | Objets de quête personnalisés avec NBT, effets et rechargement à chaud, créés intégralement depuis le dashboard, plus le catalogue de transformations Morpho. | CheeseDash |
| **[CheeseVanity](CheeseVanity/README.fr.md)** | Permet aux joueurs de masquer leurs trinkets et leur armure sur leur propre modèle. | Trinkets (optionnel) |

### Utilitaires et correctifs

| Mod | Rôle | Nécessite |
|-----|------|-----------|
| **[ScheduleRestart](ScheduleRestart/README.fr.md)** | Redémarrages et arrêts planifiés du serveur avec compte à rebours pour les joueurs. | CheeseDash |
| **[CheeseFixQuests](CheeseFixQuests/README.fr.md)** | Force la progression individuelle dans FTB Quests même quand les joueurs partagent une équipe FTB Teams. Utile bien au-delà de ce serveur. | FTB Quests, FTB Teams |
| **[CheeseCompatModsFix](CheeseCompatModsFix/README.fr.md)** | ⚠️ Correctifs de compatibilité dont les mixins ciblent des mods tiers. Conçus pour un modpack précis et susceptibles de casser à chaque mise à jour de ces mods. Fournis tels quels, sans support. | — |

---

## Documentation

Chaque mod a un manuel, pas seulement un readme. Son `README.md` donne la vue d'ensemble
— ce qu'il fait, ce qu'il exige, comment l'installer — et son dossier `docs/` contient le
détail :

| Page | Ce qu'on y trouve |
|---|---|
| `installation.md` | prérequis exacts, ordre de compilation, ce que crée le premier démarrage, la ligne de log qui prouve le chargement, désinstallation propre |
| `getting-started.md` | un tutoriel, d'un serveur vierge jusqu'à un résultat qui marche |
| `commands.md` | chaque commande, argument par argument, avec les niveaux de permission, des exemples complets et tous les messages d'échec |
| `configuration.md` | chaque clé de configuration avec son type, son défaut et son effet, plus un fichier complet annoté |
| une page par sous-système | l'explication de fond : zones et drapeaux, dimensions, événements, ADN d'un collectible, protocole OpenBoatUtils, selon ce que le mod a vraiment |
| `dashboard.md` | chaque route REST et chaque message WebSocket, avec le rôle exigé |
| `architecture.md` | arborescence des packages, threading, mixins, points d'extension, et une liste franche des faiblesses connues |
| `troubleshooting.md` | symptôme, cause, remède |

`installation`, `getting-started`, `architecture` et `troubleshooting` existent pour
chaque mod ; `commands`, `configuration` et `dashboard` seulement quand le mod a des
commandes, un fichier de configuration ou un module de dashboard.

**Tout existe aussi en anglais**, en `*.md` à côté de chaque page française. Chaque page
porte un sélecteur de langue sur sa première ligne.

Cette documentation a été écrite en relisant le code source, et non en toilettant les
anciens readmes — qui décrivaient des commandes inexistantes, des arguments dans le
mauvais ordre et des fonctionnalités jamais branchées. Quand un mod est fragile,
expérimental, ou qu'il a une valeur par défaut à changer avant toute mise en service, ses
pages le disent, plutôt que de te le laisser découvrir en production.

---

## Téléchargement

Les jars compilés sont sur les [GitHub Releases](https://github.com/AdrienPVM/ArkensiaCobblemonMods/releases) : une release par mod, avec le
tag `<nom du jar>-v<version>+1.21.1`. Tous les mods sont en version 1.0.0, sauf
CheeseFixQuests (1.0.1), CheeseCompatModsFix (1.2.53) et CobblemonDungeons (1.1.147).
La section d'installation du README de chaque mod précise si son jar va sur le serveur,
sur les clients ou des deux côtés.

---

## Compilation

Il te faut **JDK 21**. Gradle est fourni avec le wrapper, et toutes les dépendances
vers des mods tiers sont récupérées automatiquement depuis Maven — **19 des 21 mods
compilent sur un clone vierge, sans aucun téléchargement manuel.**

Chaque mod est un projet Gradle indépendant :

```bash
cd CheeseDash
./gradlew build          # gradlew.bat sous Windows
```

Le jar est produit dans `build/libs/`. Ignore les variantes `-sources.jar` et
`-dev.jar` au déploiement.

### L'ordre de compilation n'est pas facultatif

Les mods qui dépendent d'un autre mod du dépôt compilent contre son **jar compilé**,
pas contre ses sources. Compile les trois piliers d'abord, dans cet ordre :

```bash
cd CheeseDash     && ./gradlew build   # presque tout en dépend
cd ../CheeseGuard && ./gradlew build   # requis par CheeseRaids, AetherialBridge
cd ../CheeseItems && ./gradlew build   # requis par CheeseStore
# puis n'importe quel autre mod, dans n'importe quel ordre
```

Une erreur de compilation sur des classes `com.cheesedash` absentes signifie que la
première étape a été sautée. Un script parcourt aussi les trois paliers pour toi :

```bash
tools/build-all.sh            # tout construire, dans l'ordre
tools/build-all.sh --clean    # vider build/libs d'abord — à faire avant une release
```

Les dépendances entre mods se résolvent par un glob sur `../<Mod>/build/libs/` :
changer la version d'un mod ne casse donc jamais ceux qui en dépendent. Les jars sont
nommés `<nom>-<version>+1.21.1.jar`, où `+1.21.1` est la version de Minecraft visée et
`<nom>` l'identifiant du mod pour la plupart d'entre eux. Six font exception :
`AetherialBridge`, `CheeseExtraCommands`, `CheesePatrol`, `CobbleCap` et
`ScheduleRestart` portent le nom de leur dossier, et CobblemonDungeons produit
`cobblemon-dungeons` (son identifiant est `cobblemon_dungeons`). Le jar de CheesePurse
s'appelle `numismatic-cheesepurse`, son identifiant.

### Deux mods exigent des jars que tu dois fournir

**CheeseFixQuests** et **CheeseCompatModsFix** se greffent sur FTB Quests, FTB Teams,
FTB Library et StarAcademy. Ces quatre mods sont exclusifs à CurseForge — publiés ni
sur Modrinth ni sur aucun Maven public — donc Gradle ne peut pas les télécharger, et
ce dépôt ne les redistribue pas.

Télécharge-les toi-même dans [`localdeps/`](localdeps/README.md). Les deux builds
s'arrêtent immédiatement en nommant précisément ce qui manque, tu ne resteras donc
pas dans le flou. **Les 19 autres mods n'ont besoin de rien de tout ça.**

Sur un serveur, l'ordre n'a pas d'importance — Fabric charge tous les jars de `mods/`
d'un coup — mais les dépendances de chaque mod doivent être présentes à côté de lui,
CheeseDash en tête.

---

## Licence

Publié sous **[PolyForm Noncommercial License 1.0.0](LICENSE)**, avec une permission
additionnelle pour les opérateurs de serveur.

**Tu peux**

- lire, étudier et apprendre de ce code
- le modifier, le forker et continuer à le maintenir
- le redistribuer, modifications incluses
- le faire tourner sur ton serveur Minecraft — **même un serveur qui reçoit des dons,
  vend des rangs, tient une boutique ou est exploité comme une entreprise**

**Tu ne peux pas**

- vendre les mods eux-mêmes, sous quelque forme que ce soit
- les inclure dans un modpack ou server pack payant
- vendre un service d'installation ou d'hébergement construit autour
- retirer l'attribution

Il s'agit d'une licence *source-available*, pas d'une licence open source approuvée
par l'OSI : la restriction non commerciale est volontaire. Les termes complets, et
notamment où passe exactement la limite, sont dans [LICENSE](LICENSE).

---

## Support et contributions

- **Bugs et demandes de fonctionnalités** → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- **Questions et discussions** → [Discord Arkensia](https://discord.gg/fYmPwMBjdB)
- **Failles de sécurité** → voir [SECURITY.md](SECURITY.md). Merci de ne pas ouvrir
  d'issue publique pour une vulnérabilité.

Les pull requests sont bienvenues. [CONTRIBUTING.md](CONTRIBUTING.md) détaille la mise
en place de l'environnement, les conventions suivies par ces mods et ce qui rend une
contribution facile à intégrer.

---

Développé pour [Arkensia](https://discord.gg/fYmPwMBjdB) par **RoiCheese**.
