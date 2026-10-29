[← CheeseGuard](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](dimensions.md)

# Dimensions

CheeseGuard crée des dimensions Minecraft à l'exécution, sans datapack et sans
redémarrage. Un `ServerWorld` est construit à la main, inséré dans la table des mondes du
serveur, et enregistré sur disque pour revenir au prochain démarrage. Par-dessus se posent
les règles par dimension, les snapshots des fichiers de monde qui permettent de ramener une
arène à un état connu, et le mode par joueur qui donne à chacun sa copie privée d'un monde.

Le gestionnaire est `com.cheeseguard.logic.DimensionManager`, 2 570 lignes et le troisième
plus gros fichier du mod, après `MiniGameManager` et `EventManager`. L'objet de configuration est
`com.cheeseguard.data.DimensionConfig`.

> ⚠️ Ce sous-système écrit et supprime des fichiers de monde sur disque, en dehors de tout
> ce que Minecraft considère comme son affaire. Une restauration de snapshot décharge un
> monde en service, échange ses fichiers de région et le recharge. Sauvegarde ta map avant
> de l'utiliser sur un serveur auquel tu tiens, et lis
> [la section snapshots](#les-snapshots) avant d'activer un déclencheur de reset
> automatique.

---

## Deux sortes d'entrées dans une seule liste

`DimensionManager` tient une liste unique, `customDimensions`, qui contient les deux :

**Les dimensions personnalisées** — créées par CheeseGuard, `builtIn = false`. Leur
identifiant de monde est `cheeseguard:<id>` et leurs fichiers vivent dans
`<map>/dimensions/cheeseguard/<id>/`. Elles peuvent être supprimées, snapshotées et
réinitialisées.

**Les talons natifs** — `builtIn = true`, enregistrés automatiquement au démarrage pour
chaque monde que le serveur possède déjà et qui n'a pas encore de configuration :
`minecraft:overworld`, `minecraft:the_nether`, les dimensions moddées comme
`cobblemon:safari`. Leur `id` **est** l'identifiant de monde complet, deux-points inclus,
et `getWorldId()` le renvoie tel quel. Ils existent pour qu'un admin puisse poser des
règles et des drapeaux refusés sur les mondes vanilla et moddés depuis la même interface.
Ils ne peuvent être ni supprimés, ni snapshotés, ni réinitialisés.

`getAllDimensions()` rebalaye les mondes du serveur à chaque appel et enregistre un talon
pour toute nouveauté, donc une dimension ajoutée par un autre mod apparaît sans
redémarrage.

Deux configurations peuvent pointer vers le même `worldId` — un talon découvert au
démarrage, et une vraie configuration créée plus tard. `getConfigByWorldId()` préfère
délibérément celle qui n'est **pas** native, parce que c'est elle qui porte les vrais
réglages. Supprimer une dimension personnalisée purge aussi tout talon résiduel pointant
vers le même monde, pour que la paire ne puisse pas subsister dans le fichier de
configuration.

---

## Créer une dimension

### Les types de génération

Cinq types intégrés, depuis `DimensionConfig.GenType` :

| `genType` | Type de dimension | Générateur de chunks | Y de spawn par défaut | Structures |
|---|---|---|---|---|
| `VOID` | overworld | Plat **sans aucune couche**, biome `the_void` | `65` | Jamais |
| `FLAT` | overworld | Plat selon `flatLayers`, biome `plains` | `5` | Selon `generateStructures` |
| `NORMAL` | overworld | Une copie du générateur de l'overworld | `80` | Comme l'overworld |
| `NETHER` | the_nether | Une copie du générateur du Nether | `64` | Comme le Nether |
| `END` | the_end | Une copie du générateur de l'End | `64` | Comme l'End |

`NORMAL`, `NETHER` et `END` prennent l'objet générateur en service dans le monde vanilla
correspondant, et héritent donc de tout ce que les mods de biomes lui ont fait. Si le monde
source est pour une raison quelconque absent, le générateur retombe sur le vide.

`flatLayers` est une spécification de couches séparées par des virgules, du bas vers le
haut :

```text
bedrock,3*stone,grass_block
```

`<nombre>*<bloc>` répète une couche. Un identifiant de bloc sans namespace est supposé être
`minecraft:`. Un bloc introuvable devient de la pierre plutôt que de faire échouer la
création. La spécification par défaut est `bedrock,3*stone,grass_block` ; `VOID` la vide.

Une sixième voie existe : les **types de génération par plugin**. Un autre mod enregistre
un `GenTypeProvider` via l'entrypoint Fabric `cheeseguard-gentype`, et une dimension dont
le `customGenTypeId` le nomme utilise le type de dimension, le générateur de chunks et le Y
de spawn par défaut de ce provider au lieu du tableau ci-dessus. `genType` reste posé —
d'ordinaire `NORMAL` — parce qu'il pilote encore le type de dimension quand le provider ne
le surcharge pas. Un `customGenTypeId` qui ne se résout plus consigne
`Unknown customGenTypeId '<id>' — falling back to VOID` et la dimension se génère vide. Un
provider qui lève une exception pendant la création du générateur obtient le même repli,
avec l'exception consignée.

### L'identifiant, et la graine

```java
id = id.toLowerCase().replaceAll("[^a-z0-9_]", "_");
```

Les tirets sont remplacés eux aussi, contrairement aux identifiants de zones. La route du
dashboard réduit en plus les suites d'underscores et retire ceux de début et de fin. Un
résultat vide devient `dim_<4 chiffres>` d'après l'horloge courante. L'identifiant de monde
est alors `cheeseguard:<id>`, et la création renvoie `null` — HTTP 500
`java.lang.RuntimeException: Dimension already exists or creation failed` — si cet
identifiant est déjà pris.

Si tu ne donnes pas de graine, une graine aléatoire est générée, et c'est volontaire :

```java
if (config.getSeed() == 0L) { do { random = new Random().nextLong(); } while (random == 0L); }
```

`0` signifie « utiliser la graine de l'overworld » partout ailleurs dans le code, donc sans
cette étape, deux dimensions créées sans graine nommée partageraient le terrain de
l'overworld. La boucle exclut `0` pour que la graine aléatoire ne soit jamais prise pour un
« non défini ».

La graine par monde est poussée dans `SeedRegistry` **avant** que le `ServerWorld` ne soit
construit, parce que le gestionnaire de chargement de chunks bâti dans ce constructeur
appelle `world.getSeed()` pour établir sa configuration de bruit. Un mixin lit le registre
et renvoie la bonne valeur. Inverse cet ordre, et la dimension se génère avec le terrain de
l'overworld quoi que dise sa configuration.

### Le cycle de vie d'une dimension

1. **Création.** `POST /dimensions` avec au minimum `name` et soit `genType`, soit
   `customGenTypeId`. Les champs qui affectent la génération — `seed`, `flatLayers`,
   `generateStructures` — sont appliqués par un callback de pré-création, *avant* que le
   monde ne soit bâti. Tout le reste (`spawnX/Y/Z`, `pvp`, `mobSpawning`, `keepInventory`,
   `difficulty`, les verrous, `clearInventory`, `constructionMode`, `perPlayerMode`,
   `raidDensEnabled`) est appliqué après.
2. **Enregistrement à l'exécution.** `createWorldRuntime()` construit les
   `DimensionOptions`, initialise TerraBlender pour le nouveau monde s'il est présent,
   dérive les propriétés de niveau depuis l'overworld, construit le `ServerWorld` et le
   place dans la table des mondes du serveur via un accesseur mixin. Il déclenche ensuite à
   la main l'event de chargement de monde de Fabric, parce que contourner
   `MinecraftServer.createWorlds` signifie que rien d'autre ne le déclenche — sans cet
   appel, les mods qui s'accrochent au chargement de monde manquent entièrement la
   dimension.
3. **Persistance.** La configuration est ajoutée à `customDimensions` et
   `config/cheeseguard_dimensions.json` est réécrit.
4. **À chaque démarrage.** `init()` parcourt les configurations sauvegardées et appelle
   `createWorldRuntime()` pour chacune qui n'est pas native, puis applique les verrous de
   temps et les bordures de monde, puis attache un écouteur de bordure à chaque monde.
5. **Suppression.** `DELETE /dimensions/:id`, refusée pour les dimensions natives et
   refusée une seconde fois, en dur, pour `minecraft:overworld` et `minecraft:the_nether`
   à l'intérieur de `deleteDimension()`. Dans l'ordre : chaque enfant par joueur est
   supprimé d'abord, les waystones enregistrées dans la dimension sont retirées via le hook
   Waystones, les joueurs présents sont téléportés au spawn de l'overworld avec
   `La dimension %s a été supprimée.`, le monde est sauvegardé puis sorti de la table du
   serveur, le dossier du monde est supprimé, le dossier de snapshot est supprimé, chaque
   carte en mémoire indexée par l'identifiant de configuration est vidée, et la
   configuration est écartée.

TerraBlender et Waystones sont des ponts vers des mods optionnels. Les deux sont des
observations du comportement actuel de ces mods, pas des contrats : un changement cassant
de leur côté casse le routage des biomes ou le nettoyage des waystones, et la dimension
elle-même continue de fonctionner.

### Les règles par dimension

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `displayName` | chaîne | l'identifiant | Affiché dans les listes et les messages |
| `spawnX` `spawnY` `spawnZ` | double | `0.5`, selon le type, `0.5` | Où `tpToDimension` dépose un joueur |
| `spawnYaw` `spawnPitch` | float | `0` | L'orientation à l'arrivée |
| `timeLocked` | booléen | `false` | Figer l'heure du jour |
| `lockedTime` | entier | `6000` | L'heure figée. `6000` est midi |
| `weatherLocked` | booléen | `false` | Figer la météo |
| `lockedWeather` | chaîne | `"clear"` | La météo figée |
| `pvp` | booléen | `true` | PvP à l'échelle de la dimension. Vérifié **après** le drapeau de zone et le surclasse : une zone qui autorise le PvP dans une dimension à `pvp: false` refuse quand même |
| `mobSpawning` | booléen | `true` | Les spawns naturels de mobs |
| `keepInventory` | booléen | `false` | L'inventaire survit à la mort dans cette dimension |
| `difficulty` | chaîne | `"default"` | Voir la note plus bas |
| `enabled` | booléen | `true` | À `false`, les joueurs non whitelistés présents sont téléportés au spawn de l'overworld |
| `enabledMessage` | chaîne | `"Cette dimension est fermee pour le moment."` | Affiché, préfixé d'un ⛔, quand cela se produit |
| `whitelist` | ensemble d'UUID | vide | Les joueurs autorisés à entrer dans une dimension fermée |
| `clearInventory` | booléen | `false` | Sauvegarder et vider l'inventaire à l'entrée, le restaurer à la sortie |
| `constructionMode` | booléen | `false` | Créatif pour les builders listés, plus des restrictions d'items |
| `constructionBuilders` | ensemble d'UUID | vide | Qui passe en créatif en mode construction |
| `commandBlocks` | booléen | `false` | Utiliser (clic droit) des blocs de commande et de structure. La pose n'est pas vérifiée |
| `enderChest` | booléen | `true` | Placer ou utiliser des coffres de l'Ender |
| `generateStructures` | booléen | `true` | La génération de structures. Toujours désactivée pour `VOID` |
| `cobblemonBlocked` | booléen | `false` | Bloque tout spawn Cobblemon, et purge ce qui est déjà là |
| `allowHome` | booléen | `true` | Si `/home` fonctionne ici |
| `raidDensEnabled` | booléen | `true` | Les antres de raid Cobblemon naturels |
| `commandsOnBattleLoss` | liste de chaînes | vide | Commandes lancées quand un joueur perd un combat Pokémon ici |
| `commandsOnLeave` | liste de chaînes | vide | Commandes lancées quand un joueur quitte cette dimension |
| `deniedFlags` | ensemble de chaînes | vide | Identifiants de drapeaux de zone refusés dans toute la dimension |
| `borderEnabled` … `borderWarningTime` | mixte | voir plus bas | Bordure de monde par dimension |
| `perPlayerMode` | booléen | `false` | Voir [les dimensions par joueur](#les-dimensions-par-joueur) |
| `builtIn` | booléen | `false` | Posé par le mod, pas par toi |

> `difficulty` est honnête sur lui-même dans sa propre Javadoc, et cette page l'est
> aussi : la difficulté de Minecraft est globale au serveur, donc `easy`, `normal` et
> `hard` sont des **libellés cosmétiques sans aucun effet**. Seul `peaceful` fait quelque
> chose — l'agressivité des mobs hostiles est nettoyée sur un handler de tick, ce qui
> simule le mode paisible dans cette seule dimension. `default` signifie aucune surcharge.

`deniedFlags` est le repli de niveau dimension dans la résolution des drapeaux : un
identifiant de drapeau listé ici est refusé dans toute la dimension *sauf* si une zone ou
un groupe a une valeur explicite pour lui. C'est la dernière passe, donc n'importe quel
drapeau de zone le bat. L'ordre complet est dans
[zones et drapeaux](zones-and-flags.fr.md#lordre-de-résolution-complet). Les dimensions
par joueur héritent de l'ensemble refusé de leur maîtresse, et cet héritage est consulté
même quand l'instance a sa propre configuration — celle d'une instance n'est d'ordinaire
qu'un talon vide, et l'ancienne logique « ne se rabattre que faute de configuration »
faisait que les drapeaux de la maîtresse n'étaient jamais consultés.

La bordure de monde est propre à chaque dimension, et ça n'a pas été gratuit. Vanilla
attache un `WorldBorderSyncer` depuis l'overworld vers tous les autres mondes, de sorte que
poser une bordure sur l'overworld réécrit celles du Nether et de l'End pour qu'elles
s'alignent. `init()` détache ces syncers, ce qui rend la bordure de chaque monde
véritablement indépendante. Un écouteur sur chaque bordure capte tout changement — venu de
`/worldborder`, du dashboard, d'un autre mod — et le sauvegarde dans la configuration, avec
un délai d'une seconde, pour qu'il survive à un redémarrage. Un chien de garde revérifie
toutes les 200 ticks que chaque bordure gérée correspond encore à sa configuration, et un
paquet de bordure est renvoyé dès qu'un joueur change de monde, parce que vanilla ne
synchronise automatiquement que celle de l'overworld.

### Basculer une règle

`POST /dimensions/:id/toggle` avec `{"key": "<nom>"}` inverse un booléen. Clés acceptées :
`pvp`, `mobSpawning`, `keepInventory`, `clearInventory`, `constructionMode`,
`perPlayerMode`, `commandBlocks`, `enderChest`, `generateStructures`, `cobblemonBlocked`,
`enabled`, `timeLocked`, `weatherLocked`, `allowHome`, `raidDensEnabled`, `borderEnabled`,
plus `flag.<flagId>` pour basculer un drapeau refusé au niveau de la dimension. Toute autre
clé répond 500 `java.lang.RuntimeException: Unknown toggle key: <key>`. Une clé
`flag.<flagId>` n'est pas validée : un identifiant de drapeau inconnu est stocké dans
`deniedFlags` et ne correspond simplement jamais à rien.

Cinq d'entre elles font plus que poser un champ. `constructionMode` activé active aussi
`clearInventory`, parce que le mode construction n'a pas de gestion d'inventaire propre.
`cobblemonBlocked` activé purge immédiatement les entités Cobblemon du monde.
`timeLocked`, `weatherLocked` et `borderEnabled` réappliquent leur effet tout de suite au
lieu d'attendre la prochaine passe périodique.

---

## Les snapshots

Un snapshot est une **copie au niveau fichier du dossier de régions de la dimension**,
rien de plus malin. Il capture tout ce que Minecraft a écrit sur disque pour cette
dimension : terrain, block entities, entités, structures, tout. Il ne capture **pas** les
données de joueur — inventaires, positions et advancements vivent ailleurs et un reset n'y
touche pas.

```text
config/snapshots/<dimId>/          ← le snapshot
<map>/dimensions/cheeseguard/<dimId>/   ← le monde en service
```

Le chemin du snapshot est résolu relativement au parent du fichier de configuration des
dimensions, il se place donc à côté de `cheeseguard_dimensions.json` dans `config/`. C'est
aussi pour cela que les dimensions natives sont exclues : leur identifiant contient un
deux-points, qui n'est pas un caractère de chemin légal sous Windows.

### En prendre un

`POST /dimensions/:id/snapshot`, refusé avec un 400 pour une dimension native. La
séquence :

1. Sauvegarder le monde en service, s'il est chargé.
2. Supprimer l'ancien dossier de snapshot, s'il y en a un.
3. Parcourir le dossier du monde et copier chaque fichier.
4. Poser `snapshotEnabled = true` et sauvegarder la configuration.

Une dimension dont les chunks n'ont jamais été générés n'a pas de dossier sur disque ; le
snapshot est alors créé **vide** et tout de même marqué activé, consigné sous
`Snapshot created (empty) for dim '<id>'`. Un reset depuis un snapshot vide te donne un
monde vide, ce qui est une chose parfaitement légitime à vouloir pour une arène dans le
vide, et une surprise si tu attendais du terrain. Le log de succès rapporte sinon la
taille : `Snapshot created for dim '<id>' (~<n>MB)`.

`GET /dimensions/:id` rapporte `snapshotSizeKb`, calculé en parcourant le dossier, et
`snapshotEnabled`.

### Réinitialiser

`POST /dimensions/:id/reset` renvoie l'une de quatre chaînes dans `{"status": …}` :

| Statut | Signification |
|---|---|
| `ok` | La dimension était vide ; le reset a démarré |
| `pending` | Des joueurs étaient à l'intérieur. Ils ont été téléportés au spawn de l'overworld et avertis par `Cette dimension est en cours de réinitialisation.`, et le reset est mis en file |
| `no_snapshot` | Ni cette dimension ni, pour une instance, sa maîtresse n'a de snapshot |
| `error` | Pas de configuration à cet identifiant, ou dimension native |

Un reset en file est repris par `checkAutoResets()`, qui tourne une fois par seconde, dès
que la dimension est vide.

Le reset lui-même, dans `executeReset()` :

1. Sauvegarder le monde en service et **le retirer de la table des mondes du serveur** via
   l'accesseur mixin. La dimension est désormais déchargée.
2. De façon asynchrone, hors du thread serveur : attendre 500 ms, supprimer le dossier du
   monde, le recréer, copier chaque fichier depuis le snapshot.
3. De retour sur le thread serveur : `createWorldRuntime()` pour recharger la dimension,
   `lastResetAt` horodaté en millisecondes epoch, configuration sauvegardée, et chaque
   opérateur en ligne averti par `Dimension '%s' réinitialisée.`

Cette pause de 500 ms, et la pause de 400 ms équivalente avant la suppression du dossier
dans `deletePlayerDimension()` (et avant celle de chaque enfant par joueur dans
`deleteDimension()` — le dossier propre de la dimension supprimée part aussitôt, sans
pause), sont là pour les **verrous de fichiers Windows**. Retirer un `ServerWorld` de la table du serveur ne relâche pas
immédiatement les descripteurs que son stockage de chunks détient, et sous Windows un
descripteur ouvert fait échouer `Files.delete` purement et simplement, au lieu de différer
la suppression comme le fait Linux. Le délai laisse aux descripteurs le temps de se
fermer. C'est une heuristique, pas une garantie : sur un serveur chargé la copie peut
encore échouer, et si elle échoue le code consigne
`Failed to copy snapshot files for '<id>'` et appelle immédiatement
`createWorldRuntime()` pour que la dimension revienne — avec l'état que la copie
interrompue a laissé sur disque. Regarde le log après tout reset qui ne s'annonce pas
terminé.

Il n'y a aucune raison de faire tourner ça sous Linux, où le délai n'est que de la
latence, et aucun test de plateforme ne le protège. Les deux chemins le paient.

### Les trois déclencheurs

`PUT /dimensions/:id/snapshot/config` avec `{"trigger": "...", "hour": 4}` :

| Déclencheur | Quand il se déclenche |
|---|---|
| `MANUAL` | Jamais de lui-même. Seul `POST /reset` réinitialise la dimension |
| `ON_EMPTY` | Quand le dernier joueur part |
| `SCHEDULED` | Une fois par jour, à `snapshotResetHour`, à l'**heure locale du serveur** |

`hour` est ramené à l'intervalle 0–23 et vaut `4` par défaut. Un nom de déclencheur
invalide répond 400 `Invalid trigger: <nom>`. Une dimension dont `snapshotEnabled` est
`false` est sautée par le vérificateur quoi que dise son déclencheur, donc poser `ON_EMPTY`
sans avoir pris de snapshot d'abord ne fait absolument rien.

`ON_EMPTY` se déclenche sur la **transition**, pas sur l'état. Le vérificateur mémorise
dans un ensemble `onEmptyOccupied` les dimensions qui contiennent actuellement des joueurs ;
le reset n'est mis en file que lorsqu'une dimension présente dans cet ensemble devient
vide. Une dimension vide depuis le démarrage, ou qui vient d'être réinitialisée, ne se
réinitialise pas à nouveau — sinon une arène jamais visitée se réinitialiserait une fois
par seconde indéfiniment.

`SCHEDULED` compare `LocalTime.now().getHour()` à l'heure configurée et enregistre le jour
du dernier déclenchement, sous la forme `dayOfYear * 10000 + year`, pour ne pas pouvoir se
déclencher deux fois le même jour. Cet enregistrement ne vit qu'en mémoire : un serveur
redémarré pendant l'heure correspondante relance le reset. La granularité d'une heure signifie que le reset a lieu à
un moment quelconque de l'heure correspondante, dès que la prochaine vérification à la
seconde tombe — pas au début de l'heure. Si le serveur est arrêté pendant toute cette
heure, le reset est simplement sauté ; il ne se rattrape pas au démarrage suivant.

Un reset en file supprime les deux déclencheurs jusqu'à son exécution, pour qu'un
déclencheur ne puisse pas s'empiler sur un reset déjà en attente.

---

## Les dimensions par joueur

Une dimension dont `perPlayerMode` vaut `true` est une **maîtresse** : un modèle dans
lequel personne ne joue. Quand un joueur non opérateur s'y retrouve, il est déplacé en
silence dans sa propre copie privée, créée sur place si elle n'existe pas. Les opérateurs ne
sont jamais redirigés, pour qu'un admin puisse se tenir dans la maîtresse et l'éditer.

### L'identifiant

```text
<masterDimId>_<uuid8>
```

où `uuid8` est les huit premiers **caractères hexadécimaux** de l'UUID du joueur, tirets
retirés — `player.getUuid().toString().replace("-","").substring(0,8)`. Donc la maîtresse
`conseil4` plus un joueur dont l'UUID commence par `abc12345-…` donne l'identifiant de
configuration `conseil4_abc12345` et l'identifiant de monde
`cheeseguard:conseil4_abc12345`.

Huit caractères hexadécimaux font 32 bits. Sur un serveur de quelques milliers de joueurs
une collision est improbable sans être impossible, et rien ne la vérifie : deux joueurs en
collision partageraient un monde. C'est la forme qu'a ce format, pas une forme que cette
page défend.

Le sens inverse — reconnaître une instance à son identifiant — est un test de motif strict
partout où il a lieu : le dernier underscore doit être suivi d'exactement huit caractères
correspondant à `[0-9a-f]{8}`. `RegionManager`, `DimensionManager` et `DimensionsApi`
implémentent chacun ce test séparément, avec la même règle. Une dimension maîtresse dont
l'identifiant finirait par un underscore et huit caractères hexadécimaux serait lue comme
une instance ; n'en nomme pas une `arena_deadbeef`.

### Création et héritage

`createPlayerDimension()` clone les réglages de la maîtresse — type de génération, graine,
couches plates, spawn, verrous de temps et de météo, `pvp`, `mobSpawning`,
`keepInventory`, `difficulty`, `commandBlocks`, `enderChest`, `generateStructures`,
`cobblemonBlocked`, `clearInventory`, `allowHome`, `snapshotEnabled`,
`snapshotResetTrigger` et `commandsOnBattleLoss` — et force quatre valeurs :

- `constructionMode = false`
- `perPlayerMode = false` (une instance n'est jamais elle-même une maîtresse)
- `enabled = true`
- `builtIn = false`

Les fichiers de monde sont copiés depuis le **snapshot de la maîtresse** s'il en existe un,
et depuis le **monde maître en service** sinon. Si ni l'un ni l'autre n'existe, rien n'est
copié et l'instance se génère à neuf depuis son générateur.

L'UUID du propriétaire est ajouté à la whitelist de l'instance, et c'est ainsi que
l'instance reste privée. Le joueur est ensuite téléporté aux coordonnées exactes qu'il avait
atteintes dans la maîtresse, de sorte qu'un portail ou une commande qui le dépose à un
endroit précis continue de fonctionner, et il reçoit
`[CheeseGuard] Bienvenue dans votre dimension personnelle !` sur l'action bar.

Si la création échoue, il reçoit `[CheeseGuard] Erreur lors de la création de votre
dimension personnelle. Contactez un admin.` et est envoyé au spawn de l'overworld.

La vérification de redirection tourne une fois par seconde, depuis le même bloc de 20 ticks
que le vérificateur de reset. Un joueur passe donc jusqu'à une seconde debout dans la
maîtresse avant d'être déplacé. Elle remarque aussi une configuration dont le dossier de
monde ou le sous-dossier `region/` a disparu et reconstruit l'instance depuis la maîtresse,
en consignant `Personal dim '<id>' config exists but world folder missing — recreating
from snapshot`.

### Les zones couvrent toutes les instances d'un coup

Une zone dessinée dans le monde maître s'applique dans chaque instance. `getRegionsAt()`
extrait l'identifiant du monde maître d'un identifiant d'instance et fait correspondre les
zones appartenant à l'un comme à l'autre. Dessine les zones de ton arène une seule fois,
dans la maîtresse, et elles protègent chaque copie.

### Gérer les instances

| Route | Effet |
|---|---|
| `GET /dimensions/:id/players` | Liste les instances de cette maîtresse |
| `DELETE /dimensions/:id/players/:uuid8` | Supprime une instance |
| `POST /dimensions/:id/players/:uuid8/reset` | Reconstruit une instance depuis la maîtresse |

et depuis la console, au niveau de permission 2 :

```text
/cg playerworld list <masterDimId>
/cg playerworld delete <masterDimId> <joueur>
```

Supprimer une instance retire ses waystones, téléporte quiconque s'y trouve au spawn de
l'overworld avec
`[CheeseGuard] Votre dimension personnelle a été supprimée par un administrateur.`,
décharge le monde, supprime le dossier après la pause de 400 ms, supprime le snapshot
propre à l'instance s'il en a un, et écarte la configuration. Réinitialiser fait la même
chose puis reconstruit depuis le snapshot de la maîtresse — ou depuis la maîtresse en
service — en annonçant `[CheeseGuard] Votre dimension a été réinitialisée depuis le
snapshot maître.`

`GET /dimensions` masque les instances : elles n'apparaissent que sous la sous-ressource
`players` de leur maîtresse, pour que la liste des dimensions reste lisible sur un serveur
qui en compte cent.

Retrouver un UUID complet à partir de huit caractères hexadécimaux est la partie ingrate de
cette conception. L'API ne peut pas le faire depuis le seul identifiant, donc elle lit la
whitelist de la configuration de l'instance et prend l'entrée dont l'UUID commence par ces
huit caractères. Une instance dont la whitelist a été vidée à la main ne peut plus être
supprimée ni réinitialisée depuis le dashboard. `/cg playerworld delete` fonctionne encore
pour elle seulement quand son propriétaire est connecté : la commande prend l'UUID du joueur
connecté de ce nom, et pour un joueur déconnecté se rabat sur la même whitelist.

Les recherches de snapshot pour une instance se rabattent sur la maîtresse :
`getEffectiveSnapshotFolder()` renvoie le dossier propre à l'instance s'il existe, puis
celui de la maîtresse, puis le chemin inexistant de l'instance pour que l'appelant le
rejette. Donc `POST /reset` sur une instance restaure le snapshot de la maîtresse, ce qui
est exactement ce que veut une arène instanciée.

---

## Les dimensions natives et le deux-points

L'identifiant d'une configuration native est l'identifiant de monde entier —
`minecraft:overworld`, `cobblemon:safari` — deux-points inclus. Ce seul caractère est la
raison pour laquelle deux recherches sont sautées pour ces dimensions, et cette raison
mérite d'être dite parce qu'elle ne saute pas aux yeux à la lecture du code.

**Le problème de chemin.** Les dossiers de snapshot et de monde sont bâtis en résolvant
l'identifiant contre un répertoire. Un deux-points n'est pas un caractère légal dans un
chemin Windows, et sur n'importe quelle plateforme `minecraft:overworld` n'est pas un
dossier que le mod possède. Un snapshot d'une dimension native n'a donc aucun sens, et
`buildDimSummary()` rapporte `snapshotSizeKb: 0` et `playerDimCount: 0` pour les
configurations `builtIn` au lieu de parcourir un chemin qui ne peut pas exister.
`hasPendingReset` est rapporté `false` de la même façon. Les routes de création, de reset et
de suppression refusent toutes catégoriquement les dimensions natives avec un 400.

**Le problème de route.** L'API des dimensions découpe son chemin au premier `/` après
`/dimensions/`, et `isValidId()` rejette `..`, `/`, `\` et `%` mais autorise `:` — donc
`GET /api/v1/cheeseguard/dimensions/minecraft:overworld` atteint bien la bonne
configuration, et la lecture comme les bascules fonctionnent normalement dessus. Ce qu'un
deux-points casse, c'est tout ce qui traiterait l'identifiant comme un segment de chemin à
part entière, c'est-à-dire exactement ce que faisaient les recherches sautées.

Une dimension native est donc entièrement configurable — règles, drapeaux refusés, bordure
de monde, état fermé, commandes de sortie — et n'est jamais snapshotée, réinitialisée,
supprimée, ni dotée d'instances par joueur. Ce n'est pas une limitation en attente d'être
levée ; ces deux fonctionnalités ont besoin d'un dossier que le mod possède, et une
dimension vanilla n'en est pas un.

---

## Persistance

```text
config/cheeseguard_dimensions.json
```

Note où se trouve celui-ci : directement sous `config/`, et **non** dans
`config/cheeseguard/` où vivent les zones. Les snapshots se placent alors dans
`config/snapshots/<dimId>/`, résolu comme un frère du fichier de configuration.

Écrit chaque fois qu'une dimension est créée, supprimée, basculée ou éditée, chaque fois
qu'une bordure de monde change (avec un délai d'une seconde), et une fois à la fin de
`init()` pour persister les talons natifs découverts à ce démarrage.

```json
{
  "spawnRedirect": "cheeseguard:lobby",
  "dimensions": [
    {
      "id": "arena_pvp",
      "displayName": "Arène PvP",
      "genType": "FLAT",
      "seed": -4218904411238871040,
      "flatLayers": "bedrock,3*stone,grass_block",
      "spawnX": 0.5, "spawnY": 5.0, "spawnZ": 0.5,
      "timeLocked": true, "lockedTime": 6000,
      "weatherLocked": true, "lockedWeather": "clear",
      "pvp": true, "mobSpawning": false, "keepInventory": true,
      "difficulty": "peaceful",
      "builtIn": false, "enabled": true,
      "enabledMessage": "Cette dimension est fermee pour le moment.",
      "whitelist": [],
      "clearInventory": true,
      "constructionMode": false,
      "commandBlocks": false, "enderChest": false,
      "generateStructures": false,
      "cobblemonBlocked": false, "allowHome": false, "raidDensEnabled": false,
      "snapshotEnabled": true,
      "snapshotResetTrigger": "ON_EMPTY",
      "snapshotResetHour": 4,
      "lastResetAt": 1761700000000,
      "perPlayerMode": false,
      "constructionBuilders": [],
      "borderEnabled": true,
      "borderCenterX": 0.0, "borderCenterZ": 0.0,
      "borderSize": 300.0, "borderDamage": 0.2,
      "borderWarningBlocks": 5, "borderWarningTime": 15,
      "deniedFlags": [ "fall-damage", "cobblemon-summon" ]
    }
  ]
}
```

Un tableau, pas une carte — l'identifiant est ici un champ, contrairement à
`regions.json`. `spawnRedirect` est une chaîne de premier niveau nommant une dimension vers
laquelle les spawns de l'overworld sont redirigés ; elle est absente quand elle n'est pas
définie.

Le chargement est plus indulgent que celui du fichier des zones : chaque entrée est
analysée dans son propre `try`, et une entrée cassée consigne
`Skipping invalid dim config: <message>` et est écartée tandis que les autres se chargent.
Seul `id` est lu sans garde, donc une entrée à laquelle il manque son identifiant est celle
qui se fait écarter. Tous les autres champs retombent sur leur défaut quand ils sont
absents, ce qui rend le fichier sûr à élaguer à la main.

L'éditer pendant que le serveur tourne ne sert toujours à rien — la liste en mémoire fait
autorité et la prochaine écriture écrase le fichier.

### Ce qu'une dimension écrit par ailleurs

Les dimensions en `clearInventory` persistent leurs snapshots d'inventaire séparément, pour
qu'un crash pendant qu'un joueur est à l'intérieur ne mange pas ses items :

```text
config/cheeseguard_clearinv_snapshots/<uuid>.nbt
```

Écrit quand un joueur entre, supprimé quand il sort avec son inventaire restauré, et
balayé au démarrage pour qu'un snapshot ayant survécu à un crash puisse être restauré à la
prochaine connexion du joueur. Les fichiers de l'ancien dossier
`config/cheeseguard_construction_snapshots/` sont migrés dans le nouveau au démarrage et
l'ancien dossier est supprimé.

Un snapshot récupéré après crash est restauré en mode **restauration seule** : l'inventaire
détenu au moment de la récupération est jeté plutôt que fusionné. Le chemin de fusion a
besoin de la liste des items que le joueur détenait à l'entrée, laquelle ne vit qu'en
mémoire, et sans elle une fusion dupliquerait chaque item présent dans les deux ensembles.
Consigné sous `Clear inventory mode exited for <joueur> (crash-recovered snapshot) —
restore-only, current inventory discarded to prevent duplication`. Perdre ce qu'un joueur a
ramassé pendant la session interrompue est le choix délibéré, plutôt que de lui donner deux
exemplaires de tout ce avec quoi il est entré.

---

← [Groupes](groups.fr.md) · [Events](events.fr.md) →
