# EggAndCheese

> 🇬🇧 [Read in English](README.md)

> Une chasse aux œufs à collectionner où l'apparence de chaque œuf est calculée depuis un
> record d'ADN immuable, au lieu d'être stockée comme texture.

Un admin compose un œuf dans le dashboard CheeseDash — palette, deux couches de motifs,
une couche lumineuse, un tampon, une finition de surface, cinq flags de post-traitement —
le regarde tourner dans un aperçu 3D, puis cache le bloc quelque part dans le monde. Le
joueur qui le trouve fait un clic droit : un son de ramassage, une ligne en barre
d'action, et un œuf décoratif dans son inventaire, porteur de cette texture exacte. Il peut
le poser, le mettre en cadre, ou l'installer sur une stèle en rotation.

Rien de cette texture n'est livré dans le jar. Les seules images du mod sont trois icônes
d'item et une particule. Une carte de couleur 64×64 et deux images émissives sont générées
sur le client la première fois que chaque ADN distinct apparaît à l'écran, par un pipeline
en 13 étapes qui existe deux fois : une fois en Java pour le jeu, une fois en JavaScript
pour l'aperçu du dashboard.

Les œufs ne peuvent pas être minés par les non-admins en survie (le mode créatif et les
explosions les retirent tout de même), sont traversables, waterloggables, et
enregistrés une fois par joueur. Avec FTB Quests installé, chaque œuf peut porter sa propre
quête, créée automatiquement avec l'œuf lui-même pour icône.

**Minecraft 1.21.1 · Fabric · Java 21 · 49 fichiers source, ~7 100 lignes**

---

## Prérequis

| | |
|---|---|
| **Obligatoire** | [CheeseDash](../CheeseDash/) · Fabric Loader ≥ 0.16.0 · Fabric API · Java 21 |
| **Facultatif** | [FTB Quests](https://modrinth.com/mod/ftb-quests-fabric) — une quête par œuf |
| **Facultatif** | fabric-permissions-api / [LuckPerms](https://luckperms.net/) — le nœud `eggandcheese.admin`, sinon op niveau 2 |

CheeseDash est une dépendance obligatoire : les collectibles se composent dans le dashboard
web et il n'y a aucune interface d'administration en jeu. Installe le mod sur le serveur
**et** sur les clients — il enregistre 26 blocs et 25 items, et tout le rendu est côté
client.

## Installation

```bash
cd CheeseDash && ./gradlew build      # EggAndCheese compile contre son jar
cd ../EggAndCheese && ./gradlew build
```

Dépose `build/libs/eggandcheese-1.0.0+1.21.1.jar` et le jar de CheeseDash dans le `mods/`
du serveur, et le jar EggAndCheese dans le `mods/` de chaque client. Il n'y a **aucun
fichier de configuration** ; le mod stocke son état dans
`world/data/eggandcheese_registry.dat`. Au démarrage, il journalise :

```text
[EggAndCheese] Initializing...
[EggAndCheese] Ready!
```

Marche à suivre complète : [installation](docs/installation.fr.md).

## Documentation

| Page | Ce qu'elle couvre |
|---|---|
| [Installation](docs/installation.fr.md) | Prérequis, de quel côté installer, ordre de construction, ce que crée le premier démarrage, comment vérifier le chargement, retrait propre et ce qu'il détruit |
| [Premiers pas](docs/getting-started.fr.md) | Un tutoriel : d'un dashboard vide à un second joueur qui découvre un œuf dont tu as conçu la texture |
| [Commandes](docs/commands.fr.md) | `/eac` et `/pedestal`, argument par argument, avec les permissions, tous les messages et tous les échecs |
| [Configuration](docs/configuration.fr.md) | Les quatre réglages, où ils vivent puisqu'il n'y a pas de fichier, les champs par œuf, et un exemple complet annoté |
| [L'ADN d'un collectible](docs/dna.fr.md) | Le record à 14 composantes, chaque enum et chaque plage, et ce que l'immuabilité implique pour un appelant qui fait un `PUT` partiel |
| [Génération de textures et aperçus](docs/rendering.fr.md) | Le pipeline en 13 étapes, les 15 motifs émissifs, le cache de textures client, le niveau de détail, et pourquoi l'aperçu 3D vit dans CheeseDash |
| [Placement et découvertes](docs/placement-and-discovery.fr.md) | Comment un œuf est posé, pourquoi il ne peut pas être cassé, ce qu'une découverte enregistre, et ce que voit le joueur |
| [Œufs décoratifs et stèles](docs/display.fr.md) | L'item de récompense, les 23 variantes de stèle et leurs recettes, les réglages d'affichage, et l'advancement `egg_found` |
| [L'intégration FTB Quests](docs/ftb-quests.fr.md) | Le hook par réflexion, ce qu'il résout, création et validation des quêtes, synchronisation complète, et le repli quand il casse |
| [Intégrité : doublons et orphelins](docs/integrity.fr.md) | L'index des blocs chargés, la réconciliation au chargement, le bouton Intégrité, et la règle de chargement de chunks apprise au prix de deux plantages en production |
| [API dashboard](docs/dashboard.fr.md) | Toutes les routes sous `/api/v1/eggs`, formes de requête et de réponse, le message WebSocket `egg_discovery`, et la situation des rôles |
| [Architecture](docs/architecture.fr.md) | Découpage en packages, état et threads, points d'extension, code mort, et une liste franche des faiblesses connues |
| [Dépannage](docs/troubleshooting.fr.md) | Symptôme, cause, remède — dont les trois façons dont un clic droit ne fait silencieusement rien |

## Les commandes en un coup d'œil

| Commande | Permission | Ce que ça fait |
|---|---|---|
| `/eac` | `eggandcheese.admin`, sinon op 2 | affiche un renvoi vers le dashboard |
| `/eac give <id>` | `eggandcheese.admin`, sinon op 2 | te donne l'item de placement admin d'un œuf existant |
| `/eac reload` | `eggandcheese.admin`, sinon op 2 | affiche le nombre d'entrées du registre — ne recharge rien |
| `/pedestal scale <0.5–3.0>` | **aucune** | taille de l'item sur la stèle que tu regardes |
| `/pedestal height <−0.5–0.5>` | **aucune** | décalage vertical de cet item |
| `/pedestal pillar <0.0–5.0>` | **aucune** | hauteur du pilier sous la plateforme |
| `/pedestal spin` | **aucune** | active ou coupe la rotation |

Tout le reste — créer, modifier, renommer, supprimer, se téléporter, les statistiques,
l'intégrité — passe uniquement par le dashboard. Détails dans
[les commandes](docs/commands.fr.md).

## Dashboard

S'enregistre sous le préfixe `eggs` : tout vit donc sous `/api/v1/eggs/*` — CRUD des
collectibles, mise à jour fusionnée de l'ADN, découvertes, don d'items, configuration,
enums, statistiques, ainsi que le rapport et la réparation d'intégrité. Un message
`egg_discovery` est poussé sur le canal WebSocket `eggs` à chaque fois qu'un joueur en
trouve un.

## Avertissements francs

⚠️ **La frontière des rôles du dashboard est tenue par le cœur, pas par ce mod.** `EggsApi`
ne contient aucun appel à `WebDashboard.requireWrite` ni à `requireAdmin`. Les écritures
restent malgré tout réservées à `OPERATOR`, parce que le répartiteur de modules de
CheeseDash rejette toute requête non-`GET` qui échoue à `requireWrite` avant même que le
module la voie — un compte `READ_ONLY` est donc bien en lecture seule ici. Le prix à payer :
aucune route de ce mod ne peut exiger `SUPER_ADMIN`, un `GET` qui modifierait quelque chose
serait sans garde, et une seule modification de ce répartiteur ferait tomber tous les
contrôles d'un coup. [Le détail](docs/dashboard.fr.md#ce-que-le-cœur-applique-réellement).

⚠️ **`/pedestal` n'a aucune exigence de permission.** N'importe quel joueur du serveur peut
rerégler n'importe quelle stèle devant laquelle il peut se placer.

⚠️ **Renommer un œuf posé orpheline son bloc.** `POST /collectibles/:id/rename` change la
clé du registre et laisse le bloc porter l'ancien id. Choisis les ids avant de poser, ou
lance la réparation d'intégrité ensuite.

⚠️ **L'intégration FTB Quests est de la pure réflexion** sur les internes de
`dev.ftb.mods.ftbquests.*` — 733 lignes. Elle se dégrade en silence et journalise ce
qu'elle a résolu, mais elle cassera quand FTB Quests se restructurera. Un advancement à toi
sur le déclencheur de critère `eggandcheese:egg_found` est l'alternative stable —
l'advancement `egg_found` du mod se trouve dans le dossier `advancements/` d'avant la 1.21
et n'est jamais chargé ([détails](docs/display.fr.md#ladvancement)).

Le type de collectible `CHEESE` est à moitié implémenté : l'enum, l'API et les statistiques
le connaissent, et aucun bloc ni item de fromage n'est jamais enregistré. Créer un
collectible avec `"type":"cheese"` donne un œuf portant une étiquette de fromage.

## Pour les contributeurs

`CollectibleDNA` est un record Java immuable. Pour changer un champ, on reconstruit tout le
record en fusionnant les champs modifiés sur les anciens — ce que fait exactement le `PUT`
du dashboard : un objet `dna` partiel conserve donc les champs que tu omets.

**Ne jamais forcer le chargement d'un chunk depuis un handler `BLOCK_ENTITY_LOAD` ou
`CHUNK_LOAD`.** Un correctif de juin 2026 l'a fait, a bloqué le thread serveur dans le
pipeline de chunks de c2me, et a fait planter la production deux fois dans la même nuit. La
réconciliation est désormais différée sur une file vidée en fin de tick — pas sur un
`server.execute`, qui s'exécute en ligne quand on est déjà sur le thread serveur — et les
lectures de chunk vérifient d'abord `isChunkLoaded`. Les deux défenses doivent rester. Le
récit est dans
[intégrité](docs/integrity.fr.md#ne-jamais-forcer-le-chargement-dun-chunk-depuis-un-handler-de-chargement-de-block-entity).

Le package Java a été renommé de `com.roicheese.eggandcheese` en
`com.roicheese.eggandcheese` lors de la publication du mod. Les identifiants de ressources
ne sont pas concernés : l'espace de noms est `eggandcheese`, dérivé de l'id du mod, donc
`eggandcheese:egg_found` et tous les ids de recette sont inchangés.

## Licence

[PolyForm Noncommercial 1.0.0](../LICENSE), avec une permission additionnelle pour les
opérateurs de serveur. Tu peux faire tourner ce mod sur un serveur qui reçoit des dons ou
vend des rangs ; tu ne peux pas vendre le mod lui-même.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Discord Arkensia](https://discord.gg/fYmPwMBjdB)
