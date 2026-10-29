# CobbleCap

> 🇬🇧 [Read in English](README.md)

> Une limite de niveau Pokémon stricte, qui ne monte qu'en battant un champion d'arène.

Un joueur se connecte, et ses Pokémon s'arrêtent au niveau 20. Pas « montent lentement » :
s'arrêtent. L'expérience au-delà de la limite est refusée et jetée, et le plafond ne bouge
que lorsqu'il bat le champion suivant d'une liste ordonnée. Cette seule règle transforme la
progression d'un serveur Cobblemon : la question n'est plus « combien de temps as-tu », mais
« quel badge détiens-tu ».

Par-dessus se greffe une ligue. Les champions sont des PNJ Radical Cobblemon Trainers
assortis de conditions : un nombre de Pokémon au Pokédex, une quête FTB, un score de
scoreboard, et toujours l'obligation de les battre dans l'ordre. Bats le dernier et tu
entres dans un Panthéon persistant, avec ton équipe figée telle qu'elle était, ton temps
chronométré, et — en mode compétitif — ton équipe écrite dans le boss final : le prochain
challenger t'affrontera.

Les admins disposent d'un diagnostic qui explique exactement pourquoi un joueur est bloqué,
d'un système de statues qui met en scène un champion et ses six Pokémon n'importe où dans le
monde, et d'un panneau CheeseDash pour les limites.

**Minecraft 1.21.1 · Fabric · Java 21 · 52 fichiers source, ~11 740 lignes**

⚠️ **C'est le mod le plus spécifique à un serveur de tout ce dépôt.** Il a été écrit pour
une ligue précise sur un serveur précis, et cela se voit. Lis
[l'avertissement ci-dessous](#-à-lire-avant-dinstaller) avant de le déployer ailleurs : il y
a une modification du code source que tu devras presque certainement faire, et une seconde
chose à lancer avant que quiconque puisse gagner.

---

## Prérequis

| | |
|---|---|
| **Obligatoire** | Fabric Loader ≥ 0.15.11 · Fabric API · Java 21 |
| **Obligatoire en pratique** | [Cobblemon](https://modrinth.com/mod/cobblemon) 1.6.1 — déclaré seulement en `suggests`, mais le mod ne fait rien sans lui |
| **Obligatoire pour les champions** | [Radical Cobblemon Trainers](https://modrinth.com/mod/rctmod) 0.16.11-beta — **pas déclaré du tout**, et le système de champions ne peut pas fonctionner sans lui |
| **Facultatif** | [CheeseDash](../CheeseDash/) pour le panneau d'administration · FTB Quests pour les conditions de quête · [CheeseGuard](../CheeseGuard/) pour la zone de feux d'artifice · [CobbleCheeseScore](../CobbleCheeseScore/) pour afficher les classements |

Cobblemon et rctmod sont atteints entièrement par réflexion, sans dépendance à la
compilation : le mod se charge proprement sans eux et en fait d'autant moins. CheeseDash est
l'exception — c'est une dépendance **à la compilation**, donc il doit être construit en
premier.

Le côté serveur est obligatoire, le côté client est facultatif. Un client vanilla subit
toutes les règles de limite, mais sans badge, sans `/pasturehide`, et avec des statues qui
respirent.

## Installation

```bash
cd CheeseDash   && ./gradlew build     # CobbleCap compile contre ce jar
cd ../CobbleCap && ./gradlew build
```

Dépose `build/libs/cobblecap-1.0.0+1.21.1.jar` dans le `mods/` du serveur. Au premier
démarrage, il écrit `config/cobblecap/config.json` avec les valeurs par défaut, un
`champions.json` **vide**, et journalise :

```text
[CobbleCap] Initialized — startingCap=20, enabled=true, champions=0
[CobbleCap] Cobblemon XP hook registered on field: EXPERIENCE_GAINED_EVENT_PRE
[CobbleCap] Battle victory hook registered on field: BATTLE_VICTORY
```

`champions=0` est normal — la liste des champions est livrée vide et rien ne verrouille rien
tant que tu ne l'as pas rédigée. Ces deux lignes de hook sont le véritable test de santé.

Procédure complète : [installation](docs/installation.fr.md).

## Documentation

| Page | Contenu |
|---|---|
| [Installation](docs/installation.fr.md) | Prérequis exacts, ordre de build, ce que crée le premier démarrage, les lignes de log qui prouvent le chargement, la désinstallation propre et ce qu'elle laisse derrière |
| [Prise en main](docs/getting-started.fr.md) | Un tutoriel : d'un serveur vierge jusqu'à un champion qui relève vraiment une limite, vérifié en jeu |
| [La limite de niveau](docs/level-cap.fr.md) | Comment la limite est calculée et imposée sur quatre couches indépendantes, ce que voit un joueur plafonné, et pourquoi l'XP excédentaire est jetée au lieu d'être mise de côté |
| [Le système de champion](docs/champions.fr.md) | Détection de victoire, les conditions, le mode défi, classique et compétitif, le Panthéon, les chronos, les classements Conseil 4 et leur export JSON |
| [Le morphing du champion](docs/champion-morphing.fr.md) | Comment le dresseur final reprend l'équipe du dernier vainqueur en réécrivant un datapack, la file d'attente, la vérification de combat devenue morte, le repli par NPC — et pourquoi c'est la partie fragile |
| [Statues et dioramas](docs/statues.fr.md) | Trackers invisibles et doublures de faux joueur, les treize styles, le diorama dynamique du champion, le mode test |
| [Commandes](docs/commands.fr.md) | Les sous-commandes de limite, de joueur et de configuration de `/cobblecap`, plus `/explock` et `/pasturehide` — arguments, permissions, sorties et tous les messages d'échec |
| [Commandes statues, chrono et champion](docs/commands-statues.fr.md) | Les trois grands sous-arbres, argument par argument |
| [Configuration](docs/configuration.fr.md) | Les neuf fichiers, chaque clé avec sa valeur par défaut et son plafonnement, deux exemples complets annotés, et la liste de ce qui n'est **pas** configurable |
| [Le côté client](docs/client.fr.md) | Le badge de limite, `/pasturehide` et pourquoi le serveur doit aider, les quatre mixins de statue |
| [API dashboard](docs/dashboard.fr.md) | Les dix routes sous `/api/v1/cobblecap`, forme des requêtes et des réponses, rôle exigé par chacune |
| [Architecture](docs/architecture.fr.md) | Arborescence des packages, qui possède quoi, threading et points de passage, chaque mixin, points d'extension, code mort et faiblesses connues |
| [Dépannage](docs/troubleshooting.fr.md) | Symptôme, cause, remède — une trentaine, plus comment produire un rapport exploitable |

## Les commandes en un coup d'œil

| Commande | Niveau | Effet |
|---|---|---|
| `/cobblecap` | tout joueur | ta limite, ton prochain champion, tes conditions non remplies |
| `/cobblecap hud on\|off\|position <corner>` | tout joueur | déplacer ou masquer le badge |
| `/cobblecap legends [by_time]` | tout joueur | le Panthéon, par date ou par meilleur temps |
| `/cobblecap mode classic\|ranked` | tout joueur | choisir la variante du Conseil 4 |
| `/cobblecap status <player>` | 2 | le diagnostic complet — commence par là quand quelqu'un est bloqué |
| `/cobblecap get\|set\|reset <player>` | 2 | lire, écrire ou réinitialiser une limite |
| `/cobblecap addbeaten\|removebeaten <player> <id>` | 2 | réparer la progression des champions |
| `/cobblecap reload` · `enable` · `disable` | 2 | relire les configs, basculer l'interrupteur général |
| `/cobblecap statue …` | 2 | 18 sous-commandes pour les dioramas |
| `/cobblecap chrono …` | 2 | 7 sous-commandes pour les chronos |
| `/cobblecap champion …` | 2 | 8 sous-commandes pour le morphing et le repli par NPC |
| `/explock [player]` | tout joueur / 2 | basculer le blocage d'XP pour soi, ou pour quelqu'un d'autre |
| `/pasturehide [on\|off\|toggle\|status]` | côté client | masquer les Pokémon attachés à un pâturage, pour le framerate |

`/explock` et `/pasturehide` sont des racines distinctes, pas des sous-commandes de
`/cobblecap`. `/pasturehide` n'atteint jamais le serveur. Tout le détail dans
[commandes](docs/commands.fr.md) et
[commandes statues, chrono et champion](docs/commands-statues.fr.md).

## Dashboard

S'enregistre sous le préfixe `cobblecap`, donc tout vit sous `/api/v1/cobblecap/*` : la
liste des joueurs avec leurs limites, l'écriture de la limite et du blocage d'XP par joueur,
la liste des champions en `GET`/`PUT` intégral, la configuration, un histogramme des limites
et un déclencheur de test des feux d'artifice. Les lectures acceptent n'importe quel rôle
authentifié ; les écritures exigent OPERATOR.

CobbleCap ne publie **rien** en WebSocket — une vue du dashboard interroge en boucle. En
revanche il poste sur un webhook Discord lors des victoires de champion et des promotions
de Maître de la Ligue, configuré par `discordWebhookUrl`.

⚠️ Les quatre gardes d'écriture étaient cassés jusqu'au **29 octobre 2026** : ils ignoraient
la valeur de retour de `requireWrite`, si bien qu'un compte `READ_ONLY` pouvait modifier les
limites. Corrigé. Sur une build antérieure, ne donne de compte `READ_ONLY` à personne.
[Détails](docs/dashboard.fr.md#rôles).

## ⚠️ À lire avant d'installer

**1. Une constante doit changer, sinon le morphing du champion ne peut pas fonctionner.**

```java
// src/main/java/com/cobblecap/logic/RctTrainerMorpher.java, ligne 57
public static String datapackName = "CacademyMons";
```

C'est le nom du répertoire de datapack du monde qui contient les fichiers de dresseur
rctmod. `CacademyMons` est celui d'Arkensia. Ce n'est **pas une clé de configuration** — un
commentaire juste au-dessus affirme qu'elle est « Configurable from CapConfig », et ce
commentaire est faux : aucune clé de config ne la lit ni ne l'écrit. La changer veut dire
éditer le source et reconstruire.

Laisse-la telle quelle sur un autre serveur et `locateDatapackRoot` essaie cinq chemins,
n'en trouve aucun, et abandonne chaque morph avec :

```text
[CobbleCap] Datapack 'CacademyMons' not found in any of: [...]
```

Rien ne plante et rien ne se corrompt. Mais le champion final ne reprend jamais l'équipe du
dernier vainqueur, et les dioramas dynamiques `place_current` ne se rafraîchissent jamais
non plus, puisque leur rafraîchissement dépend du même chemin de code. Le mode compétitif
devient décoratif.

**2. Lance `/cobblecap champion backup_now` avant le premier morph.** Il copie le JSON de
dresseur et la skin d'origine dans `config/cobblecap_regis_backup/`. Sans cette copie il n'y
a aucun retour possible — `ensureBackup` refuse de sauvegarder un fichier déjà morphé, et un
dresseur morphé reste morphé même après la désinstallation du mod.

**3. L'identifiant du champion final est codé en dur** sous la forme
`champion_regis_challenge`, à quatre endroits : `ChampionManager` lignes 548, 605 et 792, et
`StatueCommand` ligne 541. Une ligue dont le dernier boss porte un autre identifiant ne
promeut jamais de Maître de la Ligue et ne compte jamais de défaite compétitive.

**4. Autres éléments figés plutôt que configurés :** les noms des scoreboards Conseil 4, les
noms de fichiers d'export CobbleCheeseScore, la disposition des dioramas, et plusieurs
chaînes destinées aux joueurs qui sont des `Text.literal` en français au lieu de clés
traduisibles — un client anglais voit donc du français sur les messages de chrono et les
annonces de couronnement compétitif. Et le `champions.json` qui pilote tout est livré
**vide**.

**5. Le morph de la skin n'est pas implémenté.** L'équipe change ; le visage non. Un
datapack ne peut pas servir de textures.
[Pourquoi](docs/champion-morphing.fr.md#la-skin-nest-pas-écrite).

Rien de tout cela n'est difficile à changer. Rien de tout cela n'est configurable non plus.
Considère ce mod comme un bon point de départ plutôt qu'un produit prêt à poser, et lis
[le système de champion](docs/champions.fr.md#ce-que-tu-dois-changer-pour-ton-propre-serveur)
pour la liste complète avec fichiers et numéros de ligne.

## Licence

[PolyForm Noncommercial 1.0.0](../LICENSE), avec une Permission Additionnelle pour les
opérateurs de serveur. Tu peux faire tourner ce mod sur un serveur qui accepte des dons ou
vend des rangs ; tu ne peux pas vendre le mod lui-même.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Discord Arkensia](https://discord.gg/fYmPwMBjdB)
