[← CobblemonDungeons](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](installation.md)

# Installation

CobblemonDungeons est un mod **serveur et client**. Son `fabric.mod.json` déclare
`"environment": "*"` et son entrypoint client fait un vrai travail : le HUD des objectifs,
les écrans de donjon, le rendu de l'eau et du gaz empoisonnés, le modèle du masque à gaz,
et le `DungeonJoinPayload` que le client conserve pendant un changement de serveur. Un
joueur sans le mod ne voit pas le HUD, ne peut pas ouvrir l'interface de sélection, et ne
peut pas être transféré d'un serveur à l'autre.

---

## Prérequis

| | |
|---|---|
| **Minecraft** | 1.21.1 |
| **Fabric Loader** | ≥ 0.16.10 |
| **Fabric API** | n'importe quelle version pour 1.21.1 |
| **Java** | 21 |
| **Cobblemon** | ≥ 1.6.1 — compilé contre [1.7.1 Fabric](https://modrinth.com/mod/cobblemon) |
| **RCTApi** | ≥ 0.13.7-beta — [Radical Cobblemon Trainers API](https://www.curseforge.com/projects/1152792) |
| **CheeseDash** | [mod frère](../../CheeseDash/), requis |

Les quatre dépendances dures sont déclarées dans `depends` : Fabric refuse de démarrer si
l'une manque. `suggests` liste `flan` et `radicaltrainers`, dont aucun n'est nécessaire.

Radical Trainers lui-même vaut le détour si tu veux écrire tes dresseurs avec ses outils,
mais ce mod n'a besoin que de RCT**Api** : il enregistre ses propres dresseurs dans son
propre namespace. Voir [les dresseurs et RCTApi](trainers.fr.md).

## Ordre de construction

CobblemonDungeons compile contre le jar de CheeseDash, résolu par un glob sur
`../CheeseDash/build/libs/`. CheeseDash doit être construit d'abord, sinon la construction
du donjon échoue dès la résolution des dépendances.

```bash
cd CheeseDash && ./gradlew build
cd ../CobblemonDungeons && ./gradlew build
```

`tools/build-all.sh` à la racine du dépôt parcourt tous les mods dans le bon ordre si tu
veux les construire tous.

Deux choses distinguent ce mod de ses frères dans le dépôt :

- son script de build est en **Kotlin DSL** — `build.gradle.kts`, pas `build.gradle`. Les
  commandes Gradle sont identiques ; seul le fichier que tu édites change.
- sa version n'est **pas** `1.0.0`. `build.gradle.kts` déclare
  `version = "1.1.147+1.21.1"` et `base.archivesName.set("cobblemon-dungeons")`, donc
  l'artefact est `build/libs/cobblemon-dungeons-1.1.147+1.21.1.jar`. Il n'y a pas de
  `mod_version` dans le `gradle.properties` de ce mod — la version vit dans le script de
  build.

Cobblemon vient du Maven Modrinth, épinglé par identifiant de version plutôt que par
numéro, parce que `1.7.1` désigne à la fois la build Fabric et la build NeoForge ; RCTApi
vient de CurseMaven. Un clone propre se construit sans aucun téléchargement manuel, et ce
dépôt ne redistribue rien de tiers.

## Installation sur le serveur

Dépose deux jars dans le `mods/` du serveur :

```text
mods/
├── cobblemon-dungeons-1.1.147+1.21.1.jar
└── cheesedash-1.0.0+1.21.1.jar
```

plus Cobblemon, RCTApi et Fabric API, que tu allais installer de toute façon.

## Installation sur le client

Le même `cobblemon-dungeons-1.1.147+1.21.1.jar` va dans le `mods/` du client, aux côtés de
Cobblemon, de Fabric API, de RCTApi **et du jar de CheeseDash**. CheeseDash ne fait rien sur
un client, mais `fabric.mod.json` le liste — comme RCTApi — dans `depends`, sans restriction
d'environnement : Fabric refuse donc de lancer un client qui a le jar de donjon sans eux.

Garde le jar client à la même version que le serveur. Les payloads réseau ne négocient
aucune version : un codec qui ne correspond pas produit une déconnexion, pas un
avertissement lisible.

## ⚠️ Le plugin Velocity n'est pas un mod

`velocity-party-forwarder/` est un **plugin de proxy Velocity à part**, et il ne va pas
dans `mods/`. Il va dans le dossier `plugins/` de ton proxy Velocity. Rien dans le serveur
Minecraft ne le chargera, et rien dans le proxy ne chargera le jar du mod.

Tu n'en as besoin que pour les donjons `CROSS_SERVER`. Une installation sur un seul serveur
peut ignorer cette section entièrement.

Ce qu'il fait : Velocity ne transmet pas les messages de plugin BungeeCord `Forward` entre
les serveurs backend, et ce mod s'appuie sur eux pour porter l'état du groupe du serveur
principal vers le serveur de donjon. Le plugin enregistre les canaux `bungeecord:main` et
`cobblemon_dungeons:proxy_forward`, analyse le payload transmis, extrait l'UUID du joueur
cible de ses 16 premiers octets, et délivre le message au backend sur lequel ce joueur se
trouve — en le mettant en file d'attente si le joueur est encore en transit, et en vidant
la file sur `ServerPostConnectEvent`. Sans lui, un groupe arrive vide de l'autre côté.

Il ne fait **pas partie de la construction principale** : le `settings.gradle.kts` racine ne
l'inclut pas, donc `./gradlew build` ne le construit pas et ne casse pas dessus. Le
construire est une étape séparée et manuelle, et il faut un jar de l'API Velocity que tu
fournis toi-même :

```bash
cd CobblemonDungeons/velocity-party-forwarder
mkdir -p libs
# place velocity-proxy.jar (l'API Velocity) dans libs/
./gradlew build          # produit build/libs/velocity-party-forwarder-1.0.5.jar
```

Copie ensuite ce jar dans le `plugins/` du proxy et redémarre le proxy. Au démarrage il
journalise :

```text
[CobblemonPartyForwarder] Registered channel bungeecord:main
[CobblemonPartyForwarder] Registered channel cobblemon_dungeons:proxy_forward
[CobblemonPartyForwarder] Plugin jar path=/path/to/plugins/velocity-party-forwarder-1.0.5.jar
```

Le plugin déclare la version `1.0.5`, indépendamment de celle du mod. Il cible le bytecode
Java 17, donc il tourne sur n'importe quelle build de Velocity sous Java 17 ou plus. Sa
journalisation est volontairement verbeuse — chaque message transmis est journalisé en INFO
— ce qui est utile pendant le câblage d'un réseau et bruyant une fois qu'il fonctionne.

## Mise en place cross-serveur, dans l'ordre

Pour un donjon `CROSS_SERVER` il te faut tout ceci :

1. Le mod sur le serveur principal, sans `serverRole` défini.
2. Le mod sur le serveur de donjon, démarré avec
   `-Dcobblemon.dungeons.serverRole=dungeon`.
3. Le plugin Velocity sur le proxy.
4. Le `networking.targetServer` du donjon correspondant au nom que le proxy donne au
   serveur de donjon, et `networking.mainServer` au nom qu'il donne au principal.
5. Éventuellement `database.useSql: true` sur les deux serveurs Minecraft, pointant vers le
   même schéma, si tes joueurs ont de gros PC.

Si quelque chose manque, le symptôme habituel est un chef de groupe bloqué sur *Vérification
du statut du serveur de donjon...*. [Le dépannage](troubleshooting.fr.md) liste les
variantes.

## Premier démarrage

Démarrer le serveur crée `config/cobblemon_dungeons/` et journalise ceci, dans cet ordre :

```text
Initializing Cobblemon Dungeons...
Creating default main config at …/config/cobblemon_dungeons/config.json
Creating default dungeon tickets at …/config/cobblemon_dungeons/dungeon_tickets.json
Registered dungeon from file: example_dungeon.json
Cobblemon Dungeons server role: main
Registered custom trainer from file: example_trainer.json
```

Ce que tu obtiens :

- `config.json` avec le bloc base de données, SQL désactivé, et le mot de passe
  bouchon — voir [la configuration](configuration.fr.md)
- `dungeon_tickets.json` avec un seul modèle de Tier 1, `§6Copper Dungeon Ticket`
- `dungeons/example_dungeon.json`, un exemple complet : deux espèces d'Alpha, cinq niveaux
  de tier, deux entrées de pool de Gardiens, deux pools de butin, trois récompenses de
  complétion, un boss Charizard avec sa propre récompense, et un deuxième emplacement de
  localisation
- `trainers/example_trainer.json`, un Gardien à un seul Pokémon portant la peau de Steve

Le donjon d'exemple est enregistré et apparaîtra dans l'interface et dans le dashboard. Ses
coordonnées pointent vers `100, 64, 100` dans l'overworld et il est en mode
`CROSS_SERVER` : c'est un modèle à copier plutôt que quelque chose à lancer tel quel.

## Vérifier qu'il est chargé

Trois contrôles, du plus faible au plus fort.

**Le log.** `Initializing Cobblemon Dungeons...` près du début, puis une ligne
`Registered dungeon from file:` par fichier présent dans `dungeons/`.

**En jeu.** `/dungeons` avec au moins un Pokémon dans ton équipe ouvre l'écran de
sélection. Si la commande n'existe pas, le mod n'est pas sur le serveur ; si l'écran ne
s'ouvre pas mais que tu reçois un message, lis le message — c'est l'un des deux refus
documentés dans [les commandes](commands.fr.md).

**Le dashboard.** `GET /api/v1/dungeons/configs` renvoie un tableau contenant
`generic_dungeon` — le `general.id` qui se trouve dans `example_dungeon.json` — et le module apparaît dans la barre latérale de CheeseDash avec l'icône
de château sous le nom `CobblemonDungeons`. Un `503` avec `CobblemonDungeons not ready`
signifie que le module s'est enregistré mais que le serveur n'avait pas encore démarré :
réessaie.

## Désinstallation

Retire le jar du `mods/` du serveur et de celui de chaque client, et le plugin Velocity du
`plugins/` si tu l'avais installé.

Ce qui reste derrière :

- `config/cobblemon_dungeons/` en entier. Supprime-le pour perdre les définitions de
  donjons, les modèles de tickets, les dresseurs, l'historique des parties, le classement
  et les cooldowns.
- Tout bloc enregistré par le mod que tu as posé dans le monde : spawners, portails,
  coffres, pieux, eau empoisonnée. Sans le mod ils deviennent des blocs inconnus, que le
  jeu vanilla rendra comme de l'air et laissera tomber à la prochaine sauvegarde de chunk ;
  sauvegarde ton monde d'abord si cela compte pour toi.
- Les tickets, gemmes, masques à gaz et détecteurs de gaz dans les inventaires des joueurs,
  qui disparaissent de la même manière.
- La table `cobblemon_dungeons_player_sync`, si tu utilisais SQL. La supprimer est sans
  risque dès qu'aucun serveur ne fait tourner le mod.

Rien dans la sauvegarde du monde n'est spécifique au mod au-delà des blocs et des items :
les instances n'existent qu'en mémoire et meurent avec le serveur.

---

← [CobblemonDungeons](../README.fr.md) · [Premiers pas](getting-started.fr.md) →
