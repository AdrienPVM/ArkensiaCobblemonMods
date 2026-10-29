[← CheeseItems](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](installation.md)

# Installation

## Prérequis

| | Version | Où |
|---|---|---|
| Minecraft | 1.21.1 exactement (`~1.21.1`) | — |
| Fabric Loader | ≥ 0.16.0 | [fabricmc.net](https://fabricmc.net/use/server/) |
| Fabric API | n'importe quelle build 1.21.1 ; développé contre `0.103.0+1.21.1` | [Modrinth](https://modrinth.com/mod/fabric-api) |
| Java | 21 ou plus | — |
| CheeseDash | construit depuis ce dépôt | [CheeseDash](../../CheeseDash/) |

`cheesedash` est un `depends` strict dans `fabric.mod.json`. Sans lui, le loader refuse de
démarrer le serveur, et c'est le bon comportement : le mod appelle des classes de CheeseDash
directement et n'a aucun mode dégradé.

Deux voisins optionnels :

| Mod | Ce qu'il ajoute | Ce qui se passe sans lui |
|---|---|---|
| [CobbleShift](../../CobbleShift/) | la transformation réelle derrière chaque morpho | les morphos peuvent être collectées et sélectionnées, et la transformation écrit un avertissement sans rien faire |
| [CheeseStore](../../CheeseStore/) | une ligne de solde dans l'interface du Codex | la ligne est masquée |

Aucun des deux n'est déclaré dans le manifeste. CobbleShift est atteint en lançant une
commande `cshift`, CheeseStore par réflexion : les deux sont donc optionnels de fait plutôt
que par déclaration.

## Quel côté a besoin du jar

**Les deux, avec des conséquences différentes.** Le jar est `"environment": "*"` et possède
un entrypoint `client`.

- **Serveur : obligatoire.** Tout vit ici — définitions, commandes, API dashboard, resource
  pack, logique des morphos.
- **Client : optionnel mais recommandé.** La moitié cliente ajoute l'écran du Codex de
  Morpho et les raccourcis `J` / `H`. Un joueur sans elle reçoit quand même des items
  personnalisés qui s'affichent et se comportent correctement, reçoit quand même le resource
  pack, et a quand même ses morphos enregistrées dans son codex. Il ne peut pas ouvrir le
  Codex ni activer une transformation.

Si tu distribues des mods à tes joueurs via un launcher ou un mod de synchronisation,
envoie-leur CheeseItems.

## Ordre de build

CheeseItems compile contre le jar de CheeseDash, résolu par un glob sur
`../CheeseDash/build/libs/cheesedash-*.jar`. Ce dossier doit contenir un jar avant que
CheeseItems ne compile, donc CheeseDash passe en premier :

```bash
cd CheeseDash && ./gradlew build
cd ../CheeseItems && ./gradlew build
```

`tools/build-all.sh` à la racine du dépôt parcourt tous les mods dans l'ordre des
dépendances si tu préfères ne pas y penser.

La sortie est `CheeseItems/build/libs/cheeseitems-1.0.0+1.21.1.jar`. La version vient de
`mod_version` dans `gradle.properties`. Ignore `cheeseitems-1.0.0+1.21.1-sources.jar` — il
est produit par `withSourcesJar()` et ne doit pas aller dans `mods/`.

## Installation

```bash
cp CheeseDash/build/libs/cheesedash-*.jar      /chemin/vers/serveur/mods/
cp CheeseItems/build/libs/cheeseitems-1.0.0+1.21.1.jar /chemin/vers/serveur/mods/
```

Supprime l'ancien jar avant d'en copier un nouveau. Deux versions du même mod dans `mods/`
est un crash au démarrage, pas un avertissement.

## Premier démarrage

Le mod crée deux fichiers dans `config/`, vides de contenu ; le troisième attend :

```text
config/cheeseitems.json           {"items":[],"customModelData":{},"resourcePackHost":""}
config/cheeseitems_morphos.json   {"entries":[],"globalEndurance":{…}}
config/cheeseitems_morpho.json    créé à la première attribution, pas au démarrage
```

`cheeseitems_morpho.json` est l'exception : `MorphoCodex.load()` sort immédiatement quand le
fichier manque et n'écrit qu'une fois qu'un joueur possède réellement quelque chose.

Aucun dossier d'assets n'est créé avant le premier envoi.

### Les lignes de log qui prouvent le chargement

```text
[CheeseItems] Initializing...
[CheeseItems] Default config created
[CheeseItems] Initialized successfully!
[CheeseItems] Resource pack cleared (no custom icons, no bar images)
[CheeseItems] Server started — 0 items loaded
```

`MorphoCatalog loaded (N entries)` manque au tout premier démarrage : quand le fichier du
catalogue est absent, `MorphoCatalog.load()` l'écrit et sort avant de journaliser. La ligne
apparaît à partir du deuxième démarrage, tout comme `MorphoCodex loaded (N players)` dès que
le fichier du codex existe.

Sur un client, une ligne de plus apparaît :

```text
[CheeseItems] Client initialized — keybinds H (toggle) / J (codex)
```

Aux démarrages suivants, `Default config created` est remplacé par :

```text
[CheeseItems] Loaded 3 item definitions, 2 custom model data mappings
```

Le nombre d'items de cette ligne et celui de `Server started — N items loaded` doivent
correspondre. Si le second affiche `0` alors que ton fichier n'est pas vide, l'analyse a
échoué — cherche `[CheeseItems] Failed to load config:` juste au-dessus, et lis
[configuration](configuration.fr.md#un-exemple-complet-annoté) pour comprendre pourquoi
c'est dangereux.

### Vérifier en jeu

```mcfunction
/cheeseitem list
```

```text
Aucun item défini.
```

Cette réponse est un succès : l'arbre de commandes est enregistré et tu as la permission. Un
*Unknown or incomplete command* signifie soit que le mod ne s'est pas chargé, soit que tu es
sous le niveau de permission 2.

Vérifie ensuite le dashboard. Connecte-toi sur `http://<hôte>:44311` ; la barre latérale doit
afficher deux nouvelles entrées, **CheeseItems** 🎁 et **Morphos** 🧬. Ou interroge l'API
directement :

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:44311/api/v1/items
```

```json
[]
```

## Configurer l'hôte du resource pack

Une étape qu'on oublie facilement et qui casse en silence les icônes personnalisées pour
tous les joueurs distants. Ouvre `config/cheeseitems.json` et règle :

```json
"resourcePackHost": "play.example.net:44311"
```

Un hôte et un port que la **machine d'un joueur** peut atteindre, sans schéma, sans chemin.
Laissée vide, la clé retombe sur `localhost:44311` et le mod écrit qu'il l'a fait.
Explication complète dans [configuration](configuration.fr.md#resourcepackhost).

Puis `/cheeseitem reload` ou un redémarrage.

## Désinstallation

1. Arrête le serveur.
2. Retire `cheeseitems-*.jar` de `mods/`.
3. Démarre le serveur.

Ce qui casse et ce qui survit :

| Élément | Après le retrait |
|---|---|
| Les piles d'items personnalisés dans les inventaires et les coffres | toujours là, toujours l'item de base, toujours nommées et avec leur lore — les composants restent sur la pile. Leur tag `cheeseitems` devient inerte |
| Les items à `maxUses` | gardent leur tag `usesLeft`. Il ne fait rien |
| Les items construits sur `minecraft:totem_of_undying` | **recommencent à ressusciter leur porteur**, puisque le mixin a disparu |
| Les joueurs transformés | restent transformés. Annule-les avec `cshift revert <name>` avant de retirer le mod |
| Le resource pack | les clients gardent le dernier pack téléchargé jusqu'à ce que le serveur leur dise autre chose, ce qu'il ne fera jamais. Ils peuvent le retirer de la liste des packs serveur dans les options |
| `/cheeseitem`, `/morpho` | disparues |
| Les modules du dashboard | disparus de la barre latérale au prochain démarrage de CheeseDash |

Rien n'est supprimé du disque. Restent derrière :

```text
config/cheeseitems.json
config/cheeseitems_morphos.json
config/cheeseitems_morpho.json
config/cheeseitems_assets/
```

Supprime-les à la main si tu en as fini avec le mod. Garde-les si tu prévois de réinstaller :
remettre le jar restaure chaque définition et le codex de chaque joueur, puisque le seul lien
entre une pile et sa définition est la chaîne d'identifiant.

---

← [CheeseItems](../README.fr.md) · [Premiers pas](getting-started.fr.md) →
