[← CheeseFixQuests](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](installation.md)

# Installation

---

## Prérequis

| | |
|---|---|
| **Requis** | Minecraft 1.21.1 · Fabric Loader ≥ 0.16.0 · Fabric API · Java 21 |
| **Mods requis** | [FTB Quests](https://www.curseforge.com/minecraft/mc-mods/ftb-quests-fabric) · [FTB Teams](https://www.curseforge.com/minecraft/mc-mods/ftb-teams-fabric) (et FTB Library, que tous deux entraînent) |
| **Mods optionnels** | [Cobblemon Quests](https://modrinth.com/mod/cobblemon-quests) · [ExtraQuests](https://modrinth.com/mod/extraquests) |

`fabric.mod.json` déclare `ftbquests` et `ftbteams` dans `depends` sans contrainte de
version, et `cobblemon_quests` et `extraquests` dans `suggests`. Sans FTB Quests ou FTB
Teams, le jar ne se charge pas du tout. Les deux optionnels ont chacun leur mixin de
compatibilité, qui ne s'applique simplement pas quand le mod est absent.

Les versions contre lesquelles ce mod est vérifié sont celles figées dans `build.gradle` :
**FTB Quests 2101.1.13**, FTB Teams 2101.1.2, FTB Library 2101.1.18, Cobblemon Quests
1.1.14, ExtraQuests 1.5.3. D'autres versions peuvent fonctionner ; voir l'avertissement plus
bas.

### De quel côté

`environment` vaut `"*"` — **installe-le côté serveur et côté client.**

L'essentiel du mod est côté serveur : les redirections de stockage, la migration, le
balayage de finalisation. Une installation serveur seule fonctionne et donne correctement à
chaque joueur une progression individuelle. Mais `ClientQuestFileMixin` est un correctif
côté client, et sans lui le mode édition du carnet de quêtes est définitivement désactivé
pour les opérateurs, parce que le client compare l'identifiant de groupe qu'on lui a annoncé
à l'identifiant d'équipe de joueur que portent réellement ses données de quêtes. Si personne
n'édite les quêtes depuis le jeu, serveur seul est un choix défendable. Si quelqu'un le
fait, envoie aussi le jar aux clients.

Le mod n'a pas d'interface de configuration ni de réglage côté client. Une installation
client ne demande aucun paramétrage.

---

## Obtenir le jar

### Pré-compilé

Prends `cheesefixquests-1.0.1+1.21.1.jar` dans la version que tu installes. Rien d'autre de
ce dépôt n'est nécessaire — CheeseFixQuests ne dépend ni de CheeseDash ni d'aucun mod frère,
ce qui en fait le seul mod d'ici que tu peux déposer dans un modpack sans rapport.

### Compiler depuis les sources

> ⚠️ **Ce mod ne peut pas être compilé depuis un clone vierge.** Il exige trois jars que tu
> dois télécharger à la main. C'est la seule raison pour laquelle le build n'est pas
> entièrement automatique.

FTB Quests, FTB Teams et FTB Library sont **exclusifs à CurseForge** : publiés ni sur
Modrinth ni sur aucun dépôt Maven public, donc Gradle n'a aucun moyen de les récupérer. Ce
mod mixe directement dans leurs classes, et le processeur d'annotations de Mixin résout
chaque cible à la compilation : les jars doivent donc être sur le classpath de compilation.

Télécharge le fichier **Fabric 1.21.1** de chacun depuis CurseForge et dépose-les dans le
dossier [`localdeps/`](../../localdeps/README.md) du dépôt — à la racine du dépôt, pas dans
le dossier du mod — sous exactement ces noms :

```text
localdeps/ftb-quests-fabric-2101.1.13.jar
localdeps/ftb-teams-fabric-2101.1.2.jar
localdeps/ftb-library-fabric-2101.1.18.jar
```

Les noms doivent correspondre exactement. `build.gradle` les liste littéralement dans sa
définition `ftbJars` et vérifie leur présence avant quoi que ce soit d'autre. Une version
FTB plus récente compilera probablement, mais tu devras alors modifier ces trois noms de
fichier dans `CheeseFixQuests/build.gradle` en plus de renommer les fichiers.

`localdeps/` contient aussi `academy-mc1.21.1-v1.4.0+build.467-fabric.jar`. Celui-là est
pour CheeseCompatModsFix et **n'est pas nécessaire ici** — CheeseFixQuests exige exactement
les trois jars FTB.

`.gitignore` exclut les `*.jar` dans `localdeps/`, donc rien de ce que tu y déposes ne peut
finir dans un commit. Ces mods appartiennent à leurs auteurs et ne sont pas redistribués par
ce dépôt.

S'il manque un jar, le build s'arrête immédiatement et le nomme :

```text
FAILURE: Build failed with an exception.

* Where: Build file 'CheeseFixQuests/build.gradle'

* What went wrong:
A problem occurred evaluating project ':'.
>
  CheeseFixQuests cannot be built without the FTB jars.

  Missing from localdeps/:
    - ftb-teams-fabric-2101.1.2.jar

  These three mods are CurseForge-exclusive — they are on neither Modrinth nor any
  public Maven repository, so Gradle cannot download them for you. CheeseFixQuests
  mixes directly into their classes, so they are required to compile.

  Grab them from CurseForge (search the mod name, pick the Fabric 1.21.1 file) and
  drop them in localdeps/ with exactly those filenames. Details: localdeps/README.md
```

Cette vérification existe pour que tu obtiennes ce message au lieu de plusieurs centaines de
lignes de `cannot find symbol`.

Tout le reste se résout tout seul, depuis le Maven de Modrinth : Architectury API 13.0.8,
Cobblemon Quests 1.1.14 et ExtraQuests 1.5.3. Ensuite :

```bash
cd CheeseFixQuests && ./gradlew build
```

Le jar arrive dans `build/libs/cheesefixquests-1.0.1+1.21.1.jar`. Il n'y a **aucun ordre de
compilation à respecter** : aucun mod frère de ce dépôt n'entre en jeu.

---

## Installer

1. Arrête le serveur.
2. **Sauvegarde `world/ftbquests/` et `config/ftbquests/`.** Ce n'est pas une formule de
   politesse : le mod exécute une migration unique qui réécrit la progression des quêtes à
   la première connexion d'un joueur. [La migration](migration.fr.md) explique exactement ce
   qu'elle fait, et cette sauvegarde est ton seul chemin de retour si le résultat ne te
   convient pas.
3. Dépose `cheesefixquests-1.0.1+1.21.1.jar` dans le `mods/` du serveur, à côté de FTB
   Quests et FTB Teams.
4. Envoie le même jar aux clients si tu veux que l'édition des quêtes en jeu continue de
   fonctionner.
5. Démarre le serveur.

---

## Ce que crée le premier démarrage

Un fichier, `config/cheesefixquests.json`, avec les neuf valeurs par défaut. Référence
complète dans [configuration](configuration.fr.md).

Rien d'autre, pour l'instant. `config/cheesefixquests_audit.log` n'apparaît que si tu actives
`auditQuestEvents`.

La migration ne s'exécute **pas** au démarrage. Elle s'exécute à la première connexion d'un
joueur.

---

## Vérifier le chargement

Trois ou quatre lignes, dans le journal du serveur, au démarrage :

```text
[CheeseFixQuests] Individual quest progress mode ENABLED
[CheeseFixQuests] Migration pending — will run on first player login
[CheeseFixQuests] Boot complete — finalizer=true claim-lock=true audit=false
```

La deuxième ligne n'apparaît que tant que `migrationDone` vaut `false`. Si tu lis
`Individual quest progress mode DISABLED`, c'est que `forceIndividualProgress` vaut `false`
dans la configuration et que le mod ne fait rien.

Une quatrième ligne vient de l'initialiseur statique de `TeamDataClaimRewardSyncMixin` :
elle apparaît donc au premier chargement de la classe `TeamData` de FTB Quests, et non à un
moment fixe du démarrage :

```text
[CheeseFixQuests] TeamDataClaimRewardSyncMixin loaded — claimReward is now thread-atomic.
```

Puis, une dizaine de secondes après que le serveur a commencé à tourner — le premier
balayage s'exécute, et arme ses phases, qu'il y ait quelqu'un en ligne ou non :

```text
[CheeseFixQuests] Phase A (detect) armed — sweep every 200 ticks.
[CheeseFixQuests] Phase D (Cobblemon Pokédex resync) armed.
```

La ligne de la phase D n'apparaît que si Cobblemon Quests est installé.

### Vérifier en jeu

La ligne de migration prouve que les données ont bougé. Pour prouver que la redirection
fonctionne, il te faut deux comptes :

1. Fais rejoindre un groupe FTB Teams à deux joueurs : `/ftbteams party create`, puis
   invitation et acceptation.
2. Fais terminer n'importe quelle quête à l'un des deux — ramasser un item requis est le plus
   rapide.
3. Ouvre le carnet de quêtes sur l'autre compte. **La quête doit toujours être inachevée.**
   Avec FTB Quests seul, elle serait terminée pour les deux.

Si elle est terminée pour les deux, commence par
[la progression est toujours partagée dans un groupe](troubleshooting.fr.md#la-progression-des-quêtes-est-toujours-partagée-dans-un-groupe).

---

## Désinstaller

Arrête le serveur, supprime le jar de `mods/` (et des clients). FTB Quests revient
immédiatement à la progression par groupe.

Ce qui reste derrière :

- `config/cheesefixquests.json`, et `config/cheesefixquests_audit.log` si tu l'as activé un
  jour. Les deux sont inertes ; supprime-les à volonté.
- **Les enregistrements de progression individuelle créés par la migration.** Ils restent
  dans les données propres à FTB Quests, indexés par l'UUID de l'équipe de joueur. FTB Quests
  les ignorera pour quiconque est dans un groupe et lira l'ancien enregistrement du groupe —
  qui n'a plus été mis à jour depuis le jour de l'installation. Les joueurs en groupe auront
  l'impression d'avoir perdu tout ce qu'ils ont fait entre-temps.
- Les enregistrements de groupe que ce mod a cessé d'écrire. Intacts, et périmés.

La désinstallation n'est donc pas symétrique de l'installation. Réinstaller reprend
directement les enregistrements individuels, mais tout ce qui s'est joué sans le mod reste
dans l'enregistrement du groupe, et un `remigrate` ultérieur lui appliquera les règles de
filtrage de [la migration](migration.fr.md). Restaure la sauvegarde de l'étape 2 si tu veux
un retour en arrière propre.

---

← [CheeseFixQuests](../README.fr.md) · [Premiers pas](getting-started.fr.md) →
