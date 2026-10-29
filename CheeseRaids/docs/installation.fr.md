[← CheeseRaids](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](installation.md)

# Installation

> ⚠️ **Ce mod n'a été déployé que sur des instances de test.** Il n'a jamais tourné sur un
> serveur de production, aucun raid n'a jamais été joué, et aucun retour de joueurs
> n'existe à ce jour. Installe-le sur un serveur que tu acceptes de casser.

---

## Prérequis

CheeseRaids est **uniquement côté serveur**. Il n'a pas d'entrypoint client ni de code
client ; chaque effet visuel est une particule, un son ou un paquet de titre envoyé par le
serveur. Les joueurs se connectent avec un client vanilla, ou avec le modpack que ton
serveur exige déjà pour Cobblemon.

| | |
|---|---|
| **Obligatoire** | Minecraft 1.21.1 · Fabric Loader ≥ 0.16.0 · Fabric API · Java 21 |
| **Mods frères obligatoires** | [CheeseDash](../../CheeseDash/) · [CheeseGuard](../../CheeseGuard/) |
| **Obligatoire en pratique** | [Cobblemon](https://modrinth.com/mod/cobblemon) — non déclaré, mais le raid ne fait rien apparaître sans lui |
| **Nécessaire au héraut** | [EasyNPC](https://modrinth.com/mod/easy-npc) |
| **Nécessaire aux portails de victoire** | CobbleSafari |
| **Facultatif** | [CheeseStore](../../CheeseStore/) — pour le versement `reward.fromage` |

Les deux mods frères sont déclarés en dépendances dures dans `fabric.mod.json` : Fabric
refuse de démarrer sans l'un ou l'autre. CheeseGuard est utilisé directement, par de vrais
imports, pour créer la dimension de faille ; CheeseDash héberge le module de dashboard.

Les quatre autres passent par de la réflexion et ne sont **déclarés nulle part**. Fabric ne
peut pas t'avertir qu'il en manque un. Cobblemon en particulier : sans lui, le raid
s'annonce, ne fait apparaître ni vague ni boss, arrive en timeout et se nettoie — une suite
de phases vides, avec `Cobblemon API unavailable` dans le journal. Considère-le comme
obligatoire malgré le manifeste. [Les intégrations](integrations.fr.md) donnent la matrice
complète de ce qui casse, et comment.

## Ordre de build

CheeseRaids compile contre CheeseDash et CheeseGuard via un glob `fileTree` sur
`../<Mod>/build/libs/` : les deux doivent donc être **construits** avant lui, dans cet
ordre :

```bash
cd CheeseDash    && ./gradlew build
cd ../CheeseGuard && ./gradlew build
cd ../CheeseRaids && ./gradlew build
```

`tools/build-all.sh`, à la racine du dépôt, parcourt tous les mods dans l'ordre des
dépendances : c'est la voie la plus simple si tu en construis plus d'un.

Le glob retient `cheesedash-*.jar` et `cheeseguard-*.jar`, en excluant `-sources` et
`-dev` : la version exacte des mods frères est donc indifférente. Si l'un des deux
`build/libs/` est vide, la compilation échoue sur un import manquant
`com.cheeseguard.CheeseGuardMod` ou `com.cheesedash.WebDashboard` — c'est le symptôme d'une
étape de build oubliée, pas d'une dépendance cassée.

Le jar arrive ici :

```text
CheeseRaids/build/libs/cheeseraids-1.0.0+1.21.1.jar
```

La version vient de `mod_version` dans `gradle.properties`, qui porte déjà le suffixe
`+1.21.1`.

## Installation

Dépose trois jars dans le dossier `mods/` du serveur, à côté de Fabric API :

```text
mods/
├── fabric-api-0.103.0+1.21.1.jar
├── cheesedash-<version>.jar
├── cheeseguard-<version>.jar
└── cheeseraids-1.0.0+1.21.1.jar
```

Laisse de côté les artefacts `-sources.jar` et `-dev.jar` qui apparaissent aussi dans
`build/libs/`. Supprime tout `cheeseraids-*.jar` plus ancien avant de copier le nouveau :
deux versions du même mod dans `mods/` empêchent le démarrage.

Cobblemon, EasyNPC, CobbleSafari et CheeseStore vont dans le même dossier si tu les veux,
dans la version que ton pack utilise déjà.

## Ce que crée le premier démarrage

Deux dossiers et, sur une installation vierge, un fichier :

```text
config/cheeseraids/
├── raids/
│   └── hoopa.json        la définition par défaut de l'Invasion de Hoopa
├── instances/            vide ; un fichier par raid en cours
└── state.json            écrit seulement après le premier déclenchement planifié
```

`raids/` et `instances/` sont créés systématiquement. `hoopa.json` n'est écrit **que si
`raids/` ne contient aucun fichier `.json`** — et ça compte pour plus tard : un seul fichier
mal formé bloque l'écriture du défaut, et tu peux te retrouver avec un dossier contenant un
fichier cassé et aucune définition.

`state.json` associe un identifiant de raid à la date ISO de son dernier déclenchement
planifié : c'est le registre anti-rejeu du planificateur. Il n'existe pas avant qu'un raid
planifié se déclenche.

Le `hoopa.json` par défaut n'est **pas utilisable en l'état.** Quatre de ses positions
valent `minecraft:overworld` en `0.5 / 65 / 0.5` — l'origine du monde, sous la terre. Rien
ne fonctionne avant que tu les remplaces par de vraies coordonnées de ta carte. C'est le
sujet de la [calibration](calibration.fr.md), et c'est l'étape que tu ne peux pas sauter.

## Vérifier le chargement

Deux lignes de journal, dans cet ordre. La première vient de l'initialiseur du mod, la
seconde une fois le monde en place :

```text
[CheeseRaids] initialized — modular raid framework ready.
[CheeseRaids] Loaded 1 raid definition(s), 0 active instance(s)
```

Si la seconde annonce `Loaded 0 raid definition(s)`, c'est que le dossier de définitions
contenait des fichiers qui ont tous échoué au parsing — voir
[dépannage](troubleshooting.fr.md#le-mod-charge-mais-aucun-raid-napparaît).

En jeu, en tant qu'opérateur :

```mcfunction
/cheeseraids status
```

```text
Raids:
• hoopa [idle]
```

Et sur le dashboard, une entrée **CheeseRaids** dans la barre latérale, décrite comme
*Raids serveur (invasions)*. Ou en HTTP :

```bash
TOKEN=$(curl -s -X POST http://localhost:44311/api/v1/auth/login \
  -H 'Content-Type: application/json' -d '{"token":"<master token>"}' | jq -r .token)

curl -s http://localhost:44311/api/v1/raids/ -H "Authorization: Bearer $TOKEN"
```

Un `503 {"error":"CheeseRaids not ready"}` signifie que le module s'est enregistré mais que
le serveur n'a pas fini de démarrer. Attends et réessaie.

## Désinstallation

Retire `cheeseraids-*.jar` de `mods/` et redémarre. CheeseDash abandonne le module, la
commande `/cheeseraids` disparaît, et rien d'autre dans le pack n'est affecté — le mod n'a
aucun mixin, n'enregistre ni bloc, ni item, ni entité, et ne patche rien.

Ce qui reste derrière, et quoi en faire :

| Reste derrière | Remarques |
|---|---|
| `config/cheeseraids/` | les définitions, les fichiers d'instance et `state.json`. Supprimable sans risque ; garde `raids/` si tu comptes réinstaller, puisque tes coordonnées calibrées y vivent |
| la dimension de faille | une dimension CheeseGuard, en général `cheeseguard:hoopa_rift`, avec ses fichiers de région dans la sauvegarde du monde. CheeseRaids ne la supprime jamais — il la réutilise entre deux raids. Retire-la par la gestion de dimensions de CheeseGuard |
| le héraut EasyNPC | une entité EasyNPC ordinaire que tu as placée toi-même. Elle restera dans l'état de visibilité où elle était en dernier. Supprime-la avec l'outillage d'EasyNPC |
| les mobs de vague et le boss | écartés au nettoyage. Un serveur tué en pleine raid peut laisser des Pokémon persistants et incapturables à l'arène de vague, puisque la persistance est précisément ce qui les empêche de disparaître. Retire-les avec `/kill @e[type=cobblemon:pokemon,distance=..20]` depuis l'arène, ou via le tag de commande `cheeseraids_wave_<id>` / `cheeseraids_boss_<id>` que chacun porte |
| l'heure du jour | `wave.overworldStorm` met le monde de la vague à minuit et ne le restaure jamais. Le nettoyage ne rétablit que la météo |

Désinstalle pendant un raid actif et les joueurs présents dans la faille y restent, dans une
dimension dont `enabled` vaut `false`. Arrête le raid et laisse la séquence de victoire ou
une téléportation manuelle les en sortir d'abord.

---

← [CheeseRaids](../README.fr.md) · [Prise en main](getting-started.fr.md) →
