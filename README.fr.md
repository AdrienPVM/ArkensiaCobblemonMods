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

La plupart de ces mods ne fonctionnent pas seuls. **[CheeseDash](CheeseDash/)** est le
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
                                          └── … 14 autres modules
```

---

## Les mods

### Cœur

| Mod | Rôle | Nécessite |
|-----|------|-----------|
| **[CheeseDash](CheeseDash/)** | Dashboard web d'administration auto-hébergé (HTTP + WebSocket), authentification par session avec rôles, journal d'audit, webhooks Discord. Expose l'API de modules à laquelle tous les autres mods s'enregistrent. | — |

### Monde et joueurs

| Mod | Rôle | Nécessite |
|-----|------|-----------|
| **[CheeseGuard](CheeseGuard/)** | Protection de régions avec flags par zone, dimensions personnalisées avec snapshots et resets, système d'événements chronométrés complet, gestion des joueurs, outillage de chunks. Le plus gros mod du dépôt. | CheeseDash |
| **[CheeseMusic](CheeseMusic/)** | Musique d'ambiance par zone, avec fondu au franchissement des frontières de région. | — |
| **[CheesePatrol](CheesePatrol/)** | Patrouilles par waypoints pour les entités EasyNPC : les PNJ parcourent une boucle et s'arrêtent pour regarder les joueurs proches. | CheeseDash |
| **[AetherialBridge](AetherialBridge/)** | Enregistre la génération d'îles flottantes d'Aetherial Islands comme type de génération de dimension CheeseGuard. L'overworld vanilla reste intact. | CheeseGuard, Aetherial Islands |

### Économie

| Mod | Rôle | Nécessite |
|-----|------|-----------|
| **[CheeseMarket](CheeseMarket/)** | Boutiques côté joueur avec interface plein écran personnalisée et éditeur de mise en page dans le dashboard. | CheeseDash |
| **[CheeseStore](CheeseStore/)** | Boutique premium web avec paiement Stripe, gestion des commandes, livraison manuelle et portefeuilles. ⚠️ Traite de vrais paiements — lis son [README](CheeseStore/README.md) et [SECURITY.md](SECURITY.md) avant toute chose. | CheeseDash, CheeseItems |
| **[CheesePurse](CheesePurse/)** | Dépose automatiquement dans la bourse du joueur les pièces Numismatic Overhaul ramassées. | Numismatic Overhaul |

### Gameplay Cobblemon

| Mod | Rôle | Nécessite |
|-----|------|-----------|
| **[CobbleCap](CobbleCap/)** | Progression du niveau maximum des Pokémon par badges, détection des champions, Hall of Fame, chronos et dioramas de statues décoratifs. | Cobblemon |
| **[CobbleShift](CobbleShift/)** | Transforme les joueurs en Pokémon avec capacités passives, plus les mini-jeux CobbleHunt et Hot Potato. Dialogue avec Cobblemon par réflexion, donc se dégrade proprement en son absence. | Cobblemon (optionnel) |
| **[CobbleCheeseScore](CobbleCheeseScore/)** | Classements multiples configurables, avec export PNG pour affichage sur carte in-game. | CheeseDash |
| **[CobblemonDungeons](CobblemonDungeons/)** | Système de donjons configurable : instances, tickets, dresseurs, classements et historique des runs. | Cobblemon, RCTApi, CheeseDash |
| **[CheeseExtraCommands](CheeseExtraCommands/)** | Commandes de vérification utilisées par les chaînes de quêtes PNJ : contrôle des EV, IV, espèces et variété de balls. | Cobblemon (optionnel) |

### Événements et contenu

| Mod | Rôle | Nécessite |
|-----|------|-----------|
| **[EggAndCheese](EggAndCheese/)** | Chasse aux œufs collectionnables générés procéduralement, avec génération par ADN immuable et aperçu 3D dans le dashboard. | CheeseDash |
| **[CheeseRaids](CheeseRaids/)** | Framework de raids multijoueurs chronométrés : arènes procédurales, boss coopératifs et récompenses. ⚠️ Expérimental — voir son README. | CheeseDash, CheeseGuard |
| **[CheeseKart](CheeseKart/)** | Mode course de karts construit sur des bateaux invisibles et la physique OpenBoatUtils, avec presets réglables. | CheeseDash |
| **[CheeseItems](CheeseItems/)** | Objets de quête personnalisés avec NBT, effets et rechargement à chaud, créés intégralement depuis le dashboard. | CheeseDash |
| **[CheeseVanity](CheeseVanity/)** | Permet aux joueurs de masquer leurs trinkets et leur armure sur leur propre modèle. | Trinkets (optionnel) |

### Utilitaires et correctifs

| Mod | Rôle | Nécessite |
|-----|------|-----------|
| **[ScheduleRestart](ScheduleRestart/)** | Redémarrages et arrêts planifiés du serveur avec compte à rebours pour les joueurs. | CheeseDash |
| **[CheeseFixQuests](CheeseFixQuests/)** | Force la progression individuelle dans FTB Quests même quand les joueurs partagent une équipe FTB Teams. Utile bien au-delà de ce serveur. | FTB Quests, FTB Teams |
| **[CheeseCompatModsFix](CheeseCompatModsFix/)** | ⚠️ Correctifs de compatibilité dont les mixins ciblent des mods tiers. Conçus pour un modpack précis et susceptibles de casser à chaque mise à jour de ces mods. Fournis tels quels, sans support. | — |

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

Une erreur mentionnant un `cheesedash-1.0.0.jar` introuvable signifie que la première
étape a été sautée.

### Deux mods exigent des jars que tu dois fournir

**CheeseFixQuests** et **CheeseCompatModsFix** se greffent sur FTB Quests, FTB Teams,
FTB Library et StarAcademy. Ces quatre mods sont exclusifs à CurseForge — publiés ni
sur Modrinth ni sur aucun Maven public — donc Gradle ne peut pas les télécharger, et
ce dépôt ne les redistribue pas.

Télécharge-les toi-même dans [`localdeps/`](localdeps/README.md). Les deux builds
s'arrêtent immédiatement en nommant précisément ce qui manque, tu ne resteras donc
pas dans le flou. **Les 19 autres mods n'ont besoin de rien de tout ça.**

L'ordre d'installation sur un serveur suit la même logique : CheeseDash d'abord, puis
tout ce qui en dépend.

---

## Licence

Publié sous **[PolyForm Noncommercial License 1.0.0](LICENSE)**, avec une permission
additionnelle pour les opérateurs de serveur.

**Tu peux**

- lire, étudier et apprendre de ce code
- le modifier, le forker et continuer à le maintenir
- le redistribuer, modifications incluses
- le faire tourner sur ton serveur Minecraft — **même un serveur qui reçoit des dons,
  vend des rangs ou tient une boutique**

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
