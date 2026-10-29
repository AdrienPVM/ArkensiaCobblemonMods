[← EggAndCheese](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](installation.md)

# Installation

## De quel côté

**Des deux.** `fabric.mod.json` déclare `"environment": "*"`, et le mod le prend au
sérieux :

- le serveur possède le registre, la logique de placement, celle de la découverte et
  l'API du dashboard ;
- le client possède tous les renderers et le générateur de texture procédurale — aucune
  texture d'œuf n'est dans le jar, donc un client qui ne fait pas tourner le mod n'a rien à
  dessiner.

Le mod enregistre en outre 26 blocs et 25 items. Un client qui se connecte sans eux a un
registre incomplet pour les blocs que le serveur lui envoie : considère donc une
installation serveur seule comme non prise en charge plutôt que dégradée.

## Prérequis

| | Version | Où |
|---|---|---|
| Minecraft | exactement 1.21.1 (`~1.21.1`) | |
| Fabric Loader | ≥ 0.16.0 | [fabricmc.net](https://fabricmc.net/use/installer/) |
| Fabric API | n'importe quel build 1.21.1 ; développé contre `0.102.0+1.21.1` | [Modrinth](https://modrinth.com/mod/fabric-api) |
| Java | 21 | |
| **CheeseDash** | le build correspondant de ce dépôt | [CheeseDash](../../CheeseDash/) |

CheeseDash est une dépendance **obligatoire** — `"cheesedash": "*"` figure dans `depends`,
et Fabric Loader refuse de démarrer le serveur sans lui.

### Facultatif

| Mod | Ce que tu y gagnes | Sans lui |
|---|---|---|
| [FTB Quests](https://modrinth.com/mod/ftb-quests-fabric) | Une quête par œuf, créée et validée automatiquement | L'intégration se désactive et le journalise. Utilise à la place un advancement à toi sur le déclencheur `eggandcheese:egg_found` — voir [l'advancement](display.fr.md#ladvancement) |
| fabric-permissions-api, par exemple via [LuckPerms](https://luckperms.net/) | Le nœud de permission `eggandcheese.admin` | Repli sur op niveau 2 |
| [JourneyMap](https://modrinth.com/mod/journeymap) | Rien, à ce jour | `JourneyMapIntegration` existe mais n'est branché à rien — voir [architecture](architecture.fr.md#code-mort) |

Cobblemon n'est pas requis. Un correctif de compatibilité dans `CollectibleItem` cible la
vitrine de Cobblemon, et il est inerte en son absence.

## Ordre de construction

La dépendance à un mod frère se résout par un glob `fileTree` sur
`../CheeseDash/build/libs`, et non par un nom de fichier figé. CheeseDash doit donc être
**construit** avant qu'EggAndCheese ne compile :

```bash
cd CheeseDash   && ./gradlew build
cd ../EggAndCheese && ./gradlew build
```

Le résultat est `EggAndCheese/build/libs/eggandcheese-1.0.0+1.21.1.jar`. La version vient
de `mod_version` dans `gradle.properties`.

`tools/build-all.sh` à la racine du dépôt parcourt tous les mods dans l'ordre des
dépendances, ce qui est plus simple dès que tu en construis plus d'un.

Deux détails de build à connaître :

- `loom.splitEnvironmentSourceSets()` est actif : le code client vit dans
  `src/client/java` et le code serveur dans `src/main/java`, les deux déclarés comme le
  même mod. Un build qui laisserait silencieusement tomber le source set client produirait
  un jar sans renderers.
- `withSourcesJar()` est activé : `build/libs/` contient donc aussi
  `eggandcheese-1.0.0+1.21.1-sources.jar`. **Ne déploie pas celui-là.**

## Installer

1. Arrête le serveur.
2. Copie dans le `mods/` du serveur :
   - `eggandcheese-1.0.0+1.21.1.jar`
   - le jar de CheeseDash
   - Fabric API
3. Copie le même jar EggAndCheese dans le `mods/` de chaque client, avec Fabric API.
4. Démarre le serveur.

Supprime l'ancien jar avant de copier le nouveau. Deux versions du même mod dans `mods/`
est un échec au démarrage, pas une fusion.

## Ce que crée le premier démarrage

**Aucun fichier de configuration.** EggAndCheese n'appelle jamais `getConfigDir()` ; rien
n'apparaît dans `config/`. Si tu attendais `config/eggandcheese.json`, va voir
[configuration](configuration.fr.md).

Ce qu'il crée, paresseusement, c'est une entrée d'état persistant dans le monde :

```text
world/data/eggandcheese_registry.dat
```

Elle est écrite à la première sauvegarde du monde qui suit une modification du registre —
un placement d'œuf, une création ou un enregistrement de configuration depuis le dashboard ;
une simple lecture (un `GET`, `/eac reload`) ne crée pas le fichier. Elle contient à la fois
les collectibles et les quatre réglages. Sur un monde neuf, elle démarre vide, avec `ftbQuestsEnabled` à faux,
`ftbQuestsChapter` à `"oeufs"`, `ftbAutoSync` à faux et `textureRenderDistance` à 20.

Rien n'est écrit dans `config/`, rien dans une base de données, et aucun fichier hors du
dossier du monde.

## Vérifier qu'il est chargé

### Dans le log

```text
[EggAndCheese] Initializing...
[EggAndCheese] Ready!
```

Les deux lignes signifient que l'enregistrement s'est terminé. Côté client, tu obtiens en
plus :

```text
[EggAndCheese] Client initializing...
[EggAndCheese] Client ready!
```

Avec FTB Quests installé, le hook ajoute son propre diagnostic à la première
utilisation :

```text
[EAC-FTB] CustomTask available — egg quests will not be clickable by players
[EAC-FTB] setRawIcon available — quest icons enabled
[EAC-FTB] TranslationManager available — title sync enabled
[EAC-FTB] Network sync available — live client updates enabled (sendToPlayers=true, create=true, editCtor=true)
[EAC-FTB] FTB Quests hook initialized — Java API ready
```

Sans lui :

```text
[EAC-FTB] FTB Quests not present — hook disabled
```

### Dans le dashboard

Connecte-toi sur `http://<serveur>:44311`. Une section **EggAndCheese** avec une entrée 🥚 **Collectibles** dans la
barre latérale signifie que l'entrypoint `cheesedash` a été découvert. Pour une
vérification qui ne passe pas par le frontend :

```bash
curl -H "Authorization: Bearer <token>" http://localhost:44311/api/v1/modules
```

Le préfixe `eggs` doit être dans la liste. `GET /api/v1/eggs/stats` sur une installation
neuve renvoie des zéros, ce qui reste un aller-retour réussi à travers le module.

### En jeu

```mcfunction
/eac reload
```

```text
Registre d'oeufs chargé : 0 entrées.
```

Un joueur op qui obtient cette ligne a confirmé d'un coup l'arbre de commandes, le contrôle
de permission et le registre. Pose ensuite un œuf :

```mcfunction
/give @s eggandcheese:admin_egg
```

Clic droit sur une face de bloc. Tu dois obtenir un œuf à texture procédurale en rotation
lente et, dans le chat :

```text
Oeuf créé avec l'ID automatique 'egg_001'. Modifiable depuis le dashboard CheeseDash.
```

Si le bloc apparaît en cube de modèle manquant ou pas du tout, la moitié client n'est pas
chargée. Si la ligne de chat est absente, c'est la moitié serveur.

## Désinstaller

1. Arrête le serveur.
2. Retire le jar du `mods/` du serveur et de celui de chaque client.
3. Démarre le serveur.

### Ce qui casse

Chaque bloc et chaque item `eggandcheese:` devient une entrée de registre inconnue.
Minecraft retire les blocs inconnus des chunks à leur chargement : **tous les œufs posés,
tous les œufs décoratifs et toutes les stèles disparaissent**, avec ce que les stèles
exposaient. Les items dans les inventaires sont retirés du stack au chargement. C'est le
comportement vanilla pour un mod retiré, et il n'est pas récupérable sans remettre le
mod.

Sauvegarde le monde avant de retirer le mod si l'une de ces conséquences t'importe.

### Ce qui reste derrière

| Reste | Où | Comment l'enlever |
|---|---|---|
| Le registre des collectibles et les quatre réglages | `world/data/eggandcheese_registry.dat` | supprimer le fichier |
| Les quêtes FTB créées par le mod | les données FTB Quests de ton pack | les supprimer dans l'éditeur de quêtes — le mod ne nettoie rien derrière lui une fois retiré |
| Rien dans `config/` | — | — |

Remettre le mod avant de supprimer `eggandcheese_registry.dat` restaure le registre avec
toutes ses entrées et toutes ses découvertes intactes. Les blocs, en revanche, ont disparu
du monde : chaque entrée pointera donc vers des coordonnées où il n'y a rien. Le
[rapport d'intégrité](integrity.fr.md) du dashboard les listera toutes sous
`unloadedEntries`, et tu reposes chaque œuf depuis son item admin.

### Retirer seulement l'intégration FTB

Il est rare qu'il faille désinstaller le mod pour cela. Mets `ftbQuestsEnabled` à `false`
dans `PUT /api/v1/eggs/config` et le hook se taira immédiatement, en laissant les quêtes
existantes en place. Supprime-les dans l'éditeur de quêtes si tu veux les faire
disparaître.

---

← [Dépannage](troubleshooting.fr.md) · [Premiers pas](getting-started.fr.md) →
