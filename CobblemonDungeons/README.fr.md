# CobblemonDungeons

> 🇬🇧 [Read in English](README.md)

> Des donjons instanciés à l'entrée payante pour Cobblemon : objectifs, dresseurs Gardiens,
> boss géant, butin et classements, le tout défini en JSON.

Un groupe dépense un ticket, patiente cinq secondes de compte à rebours, et atterrit dans un
hub pendant que le serveur dispose le contenu du donjon autour de lui. Puis il est téléporté
à l'intérieur. Des Pokémon Alpha — surdimensionnés, agressifs, incapturables, qu'on combat à
l'épée et non avec une équipe — gardent la zone. Les dresseurs Gardiens attendent à leurs
spawners un vrai combat Cobblemon. Les gemmes se cachent dans les coffres, les pieux dans le
sol. Un compteur sur le HUD suit tout cela face aux exigences du donjon, à côté d'un stock
de vies partagées et d'un chronomètre.

Remplis tous les objectifs et un portail, invisible jusque-là, s'allume. Entre dedans et le
groupe se retrouve dans la salle du boss face à un Charizard trois fois plus grand que la
normale. Tue-le et chacun obtient son butin, le boss lui-même dans son PC, et un temps au
classement de ce donjon à ce tier. Échoue — plus de vies, plus de temps, ou une sortie
volontaire — et sur un donjon cross-serveur rien de ce que tu as ramassé à l'intérieur ne
rentre avec toi.

Les admins ne touchent aucune commande pour tout cela. Les donjons sont des fichiers JSON,
rechargeables à chaud, éditables depuis le dashboard CheeseDash ; les tiers 1 à 5 permettent
à un même donjon de servir un groupe de niveau 20 et un groupe de niveau 115 depuis la même
géométrie.

**Minecraft 1.21.1 · Fabric · Java 21 · 45 fichiers source, ~12 000 lignes**

---

## Prérequis

| | |
|---|---|
| **Requis** | [Cobblemon](https://modrinth.com/mod/cobblemon) ≥ 1.6.1 · [RCTApi](https://www.curseforge.com/projects/1152792) ≥ 0.13.7-beta · [CheeseDash](../CheeseDash/) · Fabric Loader ≥ 0.16.10 · Fabric API · Java 21 |
| **Optionnels** | [Flan](https://modrinth.com/mod/flan) · Radical Cobblemon Trainers |
| **Serveur et client** | Les deux. Le HUD, les écrans et le transfert cross-serveur vivent tous sur le client |

Cobblemon se résout depuis le Maven Modrinth et RCTApi depuis CurseMaven : un clone propre
se construit sans aucun téléchargement manuel, et rien de tiers n'est redistribué ici.

## Installation

```bash
cd CheeseDash && ./gradlew build        # CobblemonDungeons compile contre son jar
cd ../CobblemonDungeons && ./gradlew build
```

Attention au Kotlin DSL : le script de build de ce mod est `build.gradle.kts`, et sa version
y vit plutôt que dans `gradle.properties`. Dépose
`build/libs/cobblemon-dungeons-1.1.147+1.21.1.jar` et le jar de CheeseDash dans le `mods/`
du serveur, et le même jar de donjon dans le `mods/` de chaque client. Le premier démarrage
écrit `config/cobblemon_dungeons/` avec un donjon d'exemple complet, un modèle de ticket et
un dresseur d'exemple, et journalise :

```text
Initializing Cobblemon Dungeons...
Registered dungeon from file: example_dungeon.json
Cobblemon Dungeons server role: main
Registered custom trainer from file: example_trainer.json
```

⚠️ `velocity-party-forwarder/` n'est **pas** un mod. C'est un plugin de proxy Velocity à
part, nécessaire uniquement pour les donjons cross-serveur, et il va dans le `plugins/` du
proxy — jamais dans `mods/`. Il ne fait pas partie de la construction Gradle principale.
[L'installation](docs/installation.fr.md) donne les détails.

Parcours complet : [l'installation](docs/installation.fr.md).

## Documentation

| Page | Contenu |
|---|---|
| [Installation](docs/installation.fr.md) | Prérequis, ordre de construction, le plugin Velocity et où il va, ce que crée le premier démarrage, comment vérifier, désinstallation propre |
| [Premiers pas](docs/getting-started.fr.md) | Un tutoriel : d'un serveur nu à un donjon que tu as construit, parcouru, terminé et retrouvé au classement |
| [Commandes](docs/commands.fr.md) | Les deux arbres de commandes, argument par argument, avec niveaux de permission, sorties et tous les messages d'échec |
| [Configuration](docs/configuration.fr.md) | `config.json`, le bloc base de données, la propriété de rôle serveur, et exactement ce qui se recharge et ce qui exige un redémarrage |
| [La définition d'un donjon](docs/dungeons.fr.md) | Chaque champ d'un fichier de donjon : type, défaut, effet, et comment les tiers sélectionnent le contenu |
| [Les instances et leur cycle de vie](docs/instances.fr.md) | Comment une partie est créée, préparée, gagnée et perdue ; déconnexions, retours, nettoyage et transferts cross-serveur |
| [Les tickets](docs/tickets.fr.md) | Ce qu'est un ticket, les trois façons d'en obtenir, les taux d'échange, et comment le guichet le lit |
| [Les dresseurs et RCTApi](docs/trainers.fr.md) | Écrire un Gardien, le placer, le combattre, et comment une victoire devient un objectif |
| [Classements et historique des parties](docs/records.fr.md) | Ce qui est enregistré, où, quand, et les endroits où les chiffres sont approximatifs |
| [Intégration dashboard](docs/dashboard.fr.md) | Les 16 routes REST sous `/api/v1/dungeons`, les cinq messages WebSocket, les trois effets CheeseGuard, et un avertissement sur les rôles |
| [Architecture](docs/architecture.fr.md) | Disposition des packages, les deux fichiers qui portent tout, état et écritures disque, threading, les trois mixins, points d'extension, faiblesses connues |
| [Dépannage](docs/troubleshooting.fr.md) | Symptôme, cause, remède — dont les quatre raisons différentes qui bloquent un groupe dans le hub |

## Les commandes en un coup d'œil

| Commande | Permission | Effet |
|---|---|---|
| `/dungeons` · `/dungeon` | tout joueur | Ouvre l'interface de sélection des donjons |
| `/dungeons leave` | tout joueur | Quitte le donjon en cours |
| `/dungeons sql test` | niveau 2 | Rapporte la connectivité à la base de données |
| `/dungeons partydebug <on\|off\|dump\|status\|ping>` | niveau 2 | Diagnostics de sync de groupe cross-serveur |
| `/DungeonsParty [create\|invite\|join\|leave]` | tout joueur | Gestion des groupes, serveur principal uniquement |

Rien ici ne lance un donjon : cela passe par l'interface, qui appelle
`DungeonManager.startDungeon` pour toi. `/DungeonsParty` est sensible à la casse et doit être
tapé avec ses majuscules. Détails dans [les commandes](docs/commands.fr.md).

## Dashboard

S'enregistre sous le préfixe `dungeons` : tout vit donc à `/api/v1/dungeons/*` — CRUD des
configurations de donjons, rechargement à chaud, modèles de tickets, fichiers de dresseurs,
instances en cours avec échec forcé et sortie forcée, statistiques, historique des parties
et classement. Cinq messages `dungeon_*` sont poussés sur le WebSocket CheeseDash, canal
`dungeons`.

⚠️ `DungeonsApi` n'appelle jamais `WebDashboard.requireWrite` ni `requireAdmin`. Huit routes
mutantes — dont la suppression d'un donjon et l'échec forcé d'une partie en cours — sont donc
ouvertes à **toute** session authentifiée, `READ_ONLY` comprise. Traite tout compte de
dashboard comme un opérateur pour ce module en attendant que cela change ; la liste complète
et le correctif d'une ligne sont dans [la page dashboard](docs/dashboard.fr.md).

CheeseGuard peut aussi piloter le mod pendant un événement minuté via trois types d'effet,
`DUNGEON_OPEN`, `DUNGEON_TICKET_GIVE` et `DUNGEON_MODIFIER` — le dernier ne faisant,
honnêtement, que journaliser et annoncer.

## Avertissements honnêtes

⚠️ `config.json` est livré avec `database.password` réglé sur la chaîne littérale
`"password"`. C'est un bouchon écrit par `MainConfig.DatabaseConfig`, et il doit être
remplacé avant d'activer SQL. Voir [la configuration](docs/configuration.fr.md).

Au-delà de cela, trois choses méritent d'être connues avant de construire une saison autour
de ce mod :

- **Les parties ne survivent pas à un redémarrage.** Les instances n'existent qu'en mémoire.
  Un arrêt ou un crash en pleine partie laisse le groupe sans instance et sans sortie, et
  éventuellement sur le mauvais serveur en installation cross-serveur.
- **Se déconnecter en pleine partie est un échec silencieux.** Rien n'est journalisé, aucun
  cooldown n'est enregistré, et un joueur solo ne peut pas revenir.
- **Les systèmes d'Alphas, de boss et de Gardiens s'accrochent aux internes de Cobblemon et
  de RCT** par des chaînes d'aspect, des tags de commande et des appels d'API. Ils
  demanderont de la maintenance à chaque mise à jour majeure de Cobblemon ou de RCT, et le
  symptôme sera des Gardiens qui ne comptent pas ou des boss de taille normale plutôt qu'un
  crash.

Le package est `com.sebastian.cobblemondungeons` et n'est délibérément pas renommé : cela
casserait la configuration des mixins, les identifiants de ressources et de datapack, et
l'entrypoint CheeseDash, sans aucun gain fonctionnel.
[L'architecture](docs/architecture.fr.md) l'explique.

## Licence

[PolyForm Noncommercial 1.0.0](../LICENSE), avec une permission additionnelle pour les
opérateurs de serveur. Tu peux faire tourner ce mod sur un serveur qui accepte des dons ou
vend des rangs ; tu ne peux pas vendre le mod lui-même. Cobblemon et RCT API appartiennent à
leurs propres auteurs et ne sont pas redistribués ici.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Discord Arkensia](https://discord.gg/fYmPwMBjdB)
