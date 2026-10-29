# CheeseFixQuests

> 🇬🇧 [Read in English](README.md)

> Rend la progression des quêtes FTB Quests individuelle, même quand les joueurs partagent
> une équipe FTB Teams.

**C'est le mod de ce dépôt qui a le plus de chances de te servir, quel que soit ton
serveur.** Il n'a rien à voir avec Cobblemon ni avec le serveur Arkensia : il corrige un
point de friction bien connu entre deux mods très répandus.

FTB Quests stocke la progression par *équipe*. FTB Teams donne à chaque joueur une équipe
personnelle permanente et lui permet en plus de rejoindre un groupe (*party*) ; dès qu'un
joueur est dans un groupe, FTB Quests lit et écrit la progression *du groupe*. Pour un
modpack coopératif où le groupe est l'unité de jeu, c'est le bon choix. Pour un serveur où
les groupes servent aux protections de terrain, au chargement de chunks ou au chat de
guilde, non : ces joueurs n'ont jamais demandé à partager un carnet de quêtes, et la
première fois que l'un d'eux tue un boss la quête disparaît de celui de tous les autres.
CheeseFixQuests découple les deux. Les quêtes deviennent individuelles, et les équipes
continuent de faire tout le reste.

Le mécanisme : neuf mixins dans les entrailles de FTB, dont la plupart transforment la question
« sur quelle équipe ce joueur est-il effectivement ? » posée à FTB Teams en « quelle est
l'équipe propre à ce joueur ? ». Il n'y a aucune logique par type de tâche : la redirection
se place à la couche qui résout le stockage, donc toutes les quêtes et tous les types de
tâches sont couverts. Une migration unique recopie la progression d'équipe existante vers
la progression individuelle à la première connexion, et un balayage de réparation toutes
les 10 secondes corrige une autre famille de bugs FTB, où une tâche atteint son objectif
sans que la quête s'en aperçoive.

**Minecraft 1.21.1 · Fabric · Java 21 · 15 fichiers source, ~1 920 lignes**

---

## ⚠️ Avertissements honnêtes

**Les mixins visent les entrailles d'un autre mod : champs privés, descripteurs de
méthodes, instructions bytecode précises. Rien là-dedans n'est une API publique.** Une mise
à jour de FTB Quests, FTB Teams, Cobblemon Quests ou ExtraQuests peut casser ce mod, et
comme la configuration des mixins est en `required: true`, le symptôme sera un crash au
démarrage et non une dégradation silencieuse. C'est le moins mauvais des deux scénarios,
mais cela veut dire que tu figes tes versions FTB et que tu testes avant de les changer.

Vérifié contre **FTB Quests 2101.1.13**, FTB Teams 2101.1.2, FTB Library 2101.1.18, et en
option Cobblemon Quests 1.1.14 et ExtraQuests 1.5.3. Ce sont les versions inscrites dans
`build.gradle`. Tout le reste n'est pas testé.

**La migration réécrit la progression des quêtes à la première connexion d'un joueur,
automatiquement, sans rien demander.** Sauvegarde `world/ftbquests/` avant. Pour les équipes
qui ne sont plus des groupes actifs, la copie est volontairement incomplète — seules les
récompenses réclamées indiquent quel joueur a fait quoi — donc une quête terminée sans que
sa récompense soit réclamée ne survit pas. [La migration](docs/migration.fr.md) détaille
exactement ce qui est copié, et pourquoi.

Ce mod tourne en production sur un serveur en service. Il n'a été testé contre aucune
version de FTB autre que celles listées ci-dessus.

---

## Prérequis

| | |
|---|---|
| **Requis** | [FTB Quests](https://www.curseforge.com/minecraft/mc-mods/ftb-quests-fabric) · [FTB Teams](https://www.curseforge.com/minecraft/mc-mods/ftb-teams-fabric) · Fabric Loader ≥ 0.16.0 · Fabric API · Java 21 |
| **Optionnel** | [Cobblemon Quests](https://modrinth.com/mod/cobblemon-quests) — son propre gestionnaire d'événements devient lui aussi individuel |
| **Optionnel** | [ExtraQuests](https://modrinth.com/mod/extraquests) — son minuteur quotidien réinitialise enfin les bonnes données |

Aucune dépendance à CheeseDash ni à aucun autre mod de ce dépôt. Il s'installe tel quel
dans un modpack qui n'a rien à voir.

Installe-le **côté serveur et côté client** : c'est le côté serveur qui compte, mais un
mixin client est ce qui maintient le mode édition du carnet de quêtes en état de marche
pour les opérateurs.

## Installation

Dépose `cheesefixquests-1.0.1+1.21.1.jar` dans `mods/`, à côté de FTB Quests et FTB Teams.
Sauvegarde `world/ftbquests/` avant le premier démarrage. Il n'y a rien à configurer.

```text
[CheeseFixQuests] Individual quest progress mode ENABLED
[CheeseFixQuests] Migration pending — will run on first player login
[CheeseFixQuests] Boot complete — finalizer=true claim-lock=true audit=false
```

### ⚠️ Le compiler exige trois jars que tu dois fournir

FTB Quests, FTB Teams et FTB Library sont **exclusifs à CurseForge** : publiés ni sur
Modrinth ni sur aucun dépôt Maven public, donc Gradle ne peut pas les récupérer. Ce mod
mixe directement dans leurs classes, ils sont donc obligatoires à la compilation.

Télécharge le fichier **Fabric 1.21.1** de chacun dans
[`localdeps/`](../localdeps/README.md), à la racine du dépôt, sous exactement ces noms :

```text
ftb-quests-fabric-2101.1.13.jar
ftb-teams-fabric-2101.1.2.jar
ftb-library-fabric-2101.1.18.jar
```

Le build les vérifie avant toute autre chose et s'arrête avec un message nommant ce qui
manque — tu ne te retrouveras pas devant un mur de `cannot find symbol`. Ensuite
`./gradlew build`. Aucun mod frère n'a besoin d'être construit avant.

## Documentation

| Page | Contenu |
|---|---|
| [Installation](docs/installation.fr.md) | Prérequis et versions, de quel côté installer, les trois jars exigés par le build, ce que crée le premier démarrage, les lignes de log qui prouvent le chargement, la désinstallation propre et ce qu'elle laisse derrière |
| [Premiers pas](docs/getting-started.fr.md) | Un tutoriel à deux comptes : constater le partage, installer, puis voir la même quête se terminer pour un joueur et pas pour l'autre |
| [Commandes](docs/commands.fr.md) | Les quatre sous-commandes, leurs arguments, le niveau de permission, les effets de bord et tous les messages d'échec |
| [Configuration](docs/configuration.fr.md) | Les neuf drapeaux de `cheesefixquests.json`, ce que chacun désactive, et un fichier complet annoté |
| [La migration](docs/migration.fr.md) | Le sort de la progression qui existe déjà : quand la copie s'exécute, ses deux modes, ce qu'elle refuse de copier et pourquoi, et comment lire son journal |
| [Le balayage de finalisation](docs/finalizer.fr.md) | La passe de réparation en quatre phases, pourquoi FTB laisse des quêtes bloquées barre pleine, le verrou anti-duplication et le journal d'audit |
| [Architecture](docs/architecture.fr.md) | Ce que fait FTB par défaut, ce qui est couvert et ce qui ne l'est pas, les neuf mixins et leurs cibles, le threading, les points d'extension, les faiblesses connues |
| [Dépannage](docs/troubleshooting.fr.md) | Symptôme, cause, remède — crashs de mixin, progression toujours partagée, récompenses données deux fois, progression perdue après la migration |

## Les commandes en un coup d'œil

Les quatre exigent le **niveau de permission 4**, pas le niveau 2 habituel des opérateurs.
Tous leurs messages sont du français codé en dur : le mod n'a pas de fichier de langue.

| Commande | Effet |
|---|---|
| `/cheesefixquests status` | affiche les trois drapeaux qui décident si le mod fait quelque chose |
| `/cheesefixquests migrate` | lance la migration unique de la progression, sauf si elle a déjà eu lieu |
| `/cheesefixquests remigrate` | la relance quand même |
| `/cheesefixquests resetchapter <chapterHexId>` | dé-termine toutes les quêtes achevées d'un chapitre, pour tous les joueurs |

La migration se lance normalement d'elle-même à la première connexion : `migrate` existe
pour le cas où tu préfères la déclencher depuis la console avant de rouvrir le serveur.

## Licence

[PolyForm Noncommercial 1.0.0](../LICENSE), avec une Permission Additionnelle pour les
opérateurs de serveur. Tu peux l'utiliser sur un serveur qui reçoit des dons ou vend des
grades ; tu ne peux pas vendre le mod lui-même. FTB Quests, FTB Teams et FTB Library
appartiennent à la FTB Team et ne sont pas redistribués ici.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Discord Arkensia](https://discord.gg/fYmPwMBjdB)
