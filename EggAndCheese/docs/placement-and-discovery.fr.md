[← EggAndCheese](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](placement-and-discovery.md)

# Placement et découvertes

Un œuf existe deux fois : comme `CollectibleEntry` dans le registre, et comme
`AdminCollectibleBlock` quelque part dans un monde. Le registre fait autorité sur ce
qu'est l'œuf ; le bloc est l'endroit où un joueur peut le toucher. L'essentiel de la
complexité du mod consiste à maintenir ces deux faces d'accord.

## Les objets

| Objet | Où il vit | Ce qu'il possède |
|---|---|---|
| `CollectibleEntry` | `EggRegistryData`, un `PersistentState` de l'overworld | id, type, ADN, position, dimension, échelle, `positionLost`, titre, sous-titre, surcharge de distance de rendu, id de quête FTB, l'ensemble des joueurs qui l'ont découvert, l'ensemble de ceux qui ont reçu son item décoratif |
| `AdminCollectibleBlockEntity` | le chunk où il se trouve | ADN, type de collectible, `registryId`, échelle, surcharge de distance de rendu |
| item `eggandcheese:admin_egg` | l'inventaire d'un admin | ADN, `registryId`, échelle, dans son composant `CUSTOM_DATA` |

Le block entity détient une *copie* de l'ADN et de l'échelle. C'est volontaire : un client
qui charge le chunk en a besoin sans avoir à demander les données du registre au serveur.
Le registre reste la source de vérité, et le dashboard écrit les deux côtés dès qu'il
modifie l'un.

Le `registryId` d'un block entity est le lien. Un `registryId` vide signifie que le bloc
est décoratif et non lié : il s'affiche, il ne peut pas être découvert, et l'outil
d'intégrité l'ignore.

## Poser un œuf

L'item admin est un `BlockItem` : le placement est un clic droit ordinaire contre une
face. Tout le travail se fait dans `AdminCollectibleItem.postPlacement`, côté serveur.

Il lit trois choses dans le `CUSTOM_DATA` du stack : `dna`, `registryId`, `scale`. S'il
n'y a pas d'ADN dans le stack, un ADN aléatoire est généré : un `/give` d'un
`eggandcheese:admin_egg` nu produit donc quand même un œuf fonctionnel.

Ensuite, l'une des deux branches s'exécute.

### Branche 1 — le stack désigne un id de registre existant

C'est un replacement ou un déplacement. Avant de lier le nouveau bloc, le code vérifie
via `LiveAdminEggIndex` si cet id possède déjà un bloc physique **chargé** ailleurs. Si
oui, il délie ce bloc (`setRegistryId("")`) et le retire, pour ne laisser aucun doublon
derrière. Puis il écrit la nouvelle position et la nouvelle dimension dans l'entrée,
efface `positionLost`, recopie l'ADN et l'échelle de l'item sur l'entrée et sur le nouveau
block entity, et applique la surcharge de distance de rendu de l'entrée.

L'admin lit, dans le chat :

```text
Oeuf 'egg_014' replacé avec succès — position rétablie.
```

Sur un client anglais :

```text
Egg 'egg_014' replaced successfully — position restored.
```

### Branche 2 — le stack n'a pas d'id, ou un id inconnu du registre

Une nouvelle entrée est créée. L'id vient de `EggRegistryData.generateUniqueId()`, qui
compte `egg_001`, `egg_002`, … jusqu'à en trouver un libre. L'entrée est ajoutée, le
block entity y est lié, et si l'intégration FTB Quests est active, une quête est créée
immédiatement.

```text
Oeuf créé avec l'ID automatique 'egg_015'. Modifiable depuis le dashboard CheeseDash.
```

La conséquence mérite d'être relevée : **passer par le dashboard pour poser un œuf n'est
pas obligatoire.** Un admin muni d'un simple œuf admin peut en poser autant qu'il veut et
ranger les ids et les titres ensuite. La route `POST /collectibles` du dashboard crée au
contraire une entrée aux coordonnées que tu fournis, ce qui est le bon choix quand tu sais
déjà où l'œuf doit aller.

### Les propriétés du bloc

`AdminCollectibleBlock` est construit avec `noCollision()` et `nonOpaque()`, implémente
`Waterloggable` et porte deux propriétés d'état : `FACING`
(`Properties.HORIZONTAL_FACING`, réglé à l'opposé de l'orientation du joueur qui pose,
pour que l'œuf te regarde) et `WATERLOGGED` (vrai quand il est posé dans l'eau).

L'absence de collision fait qu'un joueur traverse un œuf. C'est ce qui permet de le poser
dans une embrasure ou dans des herbes hautes sans rien bloquer.

## Indestructibilité

Trois redéfinitions protègent un œuf posé :

- `canReplace` renvoie `false` : aucun placement de bloc ne peut l'écraser.
- `calcBlockBreakingDelta` renvoie `0.0f` pour quiconque n'est pas admin. Une vitesse de
  minage nulle signifie que le bloc ne cède jamais, quelle que soit la durée du clic.
- `onBreak` renvoie l'état inchangé pour un non-admin, ce qui annule la casse.

Un admin qui casse bien le bloc récupère l'œuf en item, porteur de son ADN, de son échelle
et de son `registryId`, inséré directement dans l'inventaire (ou lâché à ses pieds si
l'inventaire est plein — jamais déposé comme drop autrement). L'entrée du registre passe
alors en `positionLost = true`, sa position revient à `BlockPos.ORIGIN`, et l'admin reçoit
un avertissement en doré :

```text
L'oeuf 'egg_014' n'a plus de position dans le monde. Replacez-le ou supprimez-le via le dashboard CheeseDash.
```

`positionLost` compte partout dans l'API : le dashboard refuse de déplacer, de
synchroniser ou de supprimer un bloc du monde pour une entrée dans cet état, puisqu'il n'y
a pas de bloc sur lequel agir. Replacer l'item efface le drapeau.

La permission vient de `PermissionUtil.isAdmin`, qui tente par réflexion
`me.lucko.fabric.api.permissions.v0.Permissions.check(entity, "eggandcheese.admin", 2)`
et retombe sur `hasPermissionLevel(2)` quand aucun mod de permissions n'est installé. Il
n'existe pas de nœud « peut casser » distinct — admin est admin.

## La découverte

Un non-admin fait un clic droit sur un œuf. `AdminCollectibleBlock.onUse` s'exécute sur le
serveur et appelle `AdminCollectibleBlockEntity.handlePlayerDiscovery`, qui :

1. Sort immédiatement si `registryId` est vide ou ne désigne aucune entrée du registre. Un
   œuf non lié est un décor.
2. Si le joueur est déjà dans `discoveredBy`, affiche `Vous avez déjà trouvé celui-ci !`
   dans la barre d'action et s'arrête.
3. Ajoute le joueur à `discoveredBy` **et** à `decoReceivedBy`, puis marque le registre
   comme modifié.
4. Construit la récompense — un `collectible_egg` porteur du même ADN — et l'insère dans
   l'inventaire du joueur, en la lâchant à ses pieds si l'inventaire est plein.
5. Déclenche le critère d'advancement `eggandcheese:egg_found` avec l'id de l'œuf.
6. Joue `ENTITY_EXPERIENCE_ORB_PICKUP` sur le bloc, hauteur 1.2.
7. Affiche `Vous avez découvert : egg_014 !` dans la barre d'action.
8. Valide la quête FTB liée, en la créant d'abord si l'œuf est antérieur à l'intégration
   — voir [l'intégration FTB Quests](ftb-quests.fr.md).
9. Diffuse un message `egg_discovery` sur le canal WebSocket `eggs` de CheeseDash, protégé
   par `FabricLoader.isModLoaded("cheesedash")` et par un `catch` sur
   `NoClassDefFoundError`.

Ce que voit le joueur, dans l'ordre : le son de ramassage, la ligne en barre d'action, un
nouvel œuf décoratif dans son inventaire, et une quête qui se valide si elle était
configurée.

⚠️ **La ligne en barre d'action affiche l'id brut, pas le titre.**
`Text.translatable("eggandcheese.found", registryId)` passe `registryId` : un joueur lit
donc `Vous avez découvert : egg_014 !` même quand l'entrée a un beau `title`. Le titre
sert à la quête FTB, pas à ce message. Considère tes ids comme visibles par les joueurs et
nomme-les en conséquence — `cave_nord` se lit mieux que `egg_014`.

### Le clic droit d'un admin n'est pas une découverte

Si `PermissionUtil.isAdmin` est vrai, `onUse` prend une branche entièrement différente :
l'admin reçoit une copie de l'œuf décoratif et la méthode se termine. Aucune découverte
n'est enregistrée, aucune quête ne se valide, aucun message WebSocket n'est envoyé. C'est
un outil de prévisualisation — clic droit sur ton propre œuf pour vérifier à quoi
ressemble la récompense.

L'effet de bord mérite d'être connu : cette branche consulte et renseigne le **même**
ensemble `decoReceivedBy` que les découvertes. Un admin qui a pris la copie décorative y
est inscrit et verra, au clic droit suivant,
`Tu as déjà reçu la version déco de cet oeuf.` Si cet admin rejoue plus tard en joueur
normal et perd son op, il ne pourra plus jamais enregistrer de découverte sur cet œuf : le
test sur la déco court-circuite avant qu'on atteigne `handlePlayerDiscovery`. Vide la
liste des découvertes depuis le dashboard, ou teste avec un second compte.

## État par joueur

Deux ensembles indépendants vivent sur chaque entrée :

| Ensemble | Écrit par | Signification |
|---|---|---|
| `discoveredBy` | la découverte joueur, `POST /collectibles/:id/discoveries` | ce joueur a trouvé cet œuf |
| `decoReceivedBy` | la découverte joueur, le clic droit admin | ce joueur a eu l'item décoratif |

Les deux persistent dans le NBT de l'entrée sous forme de listes de chaînes UUID, et les
deux sont relus de façon défensive : un UUID illisible est ignoré plutôt que de faire
échouer le chargement.

Le dashboard peut ajouter une découverte par nom de joueur (`POST`), en retirer une par
UUID (`DELETE /collectibles/:id/discoveries/:uuid`) ou toutes les effacer
(`DELETE /collectibles/:id/discoveries`). Aucune de ces routes ne touche
`decoReceivedBy` : un joueur dont tu as effacé la découverte retrouvera l'œuf et recevra
une seconde copie décorative seulement s'il n'a jamais été dans `decoReceivedBy` — ce qui,
pour une découverte normale, n'arrive pas. En pratique, effacer les découvertes permet à
un joueur de redéclencher la quête, pas de reprendre l'item.

## Persistance

`EggRegistryData` étend `PersistentState` et s'obtient par
`server.getWorld(World.OVERWORLD).getPersistentStateManager().getOrCreate(TYPE,
"eggandcheese_registry")`. L'état est rangé dans le dossier de données de l'overworld,
sous `world/data/eggandcheese_registry.dat`, et Minecraft l'écrit sur son propre cycle de
sauvegarde — à chaque autosave et à l'arrêt. Rien dans ce mod ne force une sauvegarde.

Chaque chemin mutant appelle `markDirty()`, et c'est ce qui programme l'écriture. Un
serveur tué par `kill -9` entre deux autosaves perd les découvertes faites depuis la
dernière.

Comme l'état est rattaché à l'**overworld**, les œufs posés dans d'autres dimensions sont
tout de même enregistrés dans le fichier de données de l'overworld. Supprimer ce fichier
supprime toutes les entrées d'œufs de toutes les dimensions en laissant les blocs debout
— ce qui produit exactement la situation d'orphelins que
[l'outil d'intégrité](integrity.fr.md) est fait pour nettoyer.

---

← [Génération de textures et aperçus](rendering.fr.md) · [Œufs décoratifs et stèles](display.fr.md) →
