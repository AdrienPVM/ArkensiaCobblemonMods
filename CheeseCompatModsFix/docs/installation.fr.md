[← CheeseCompatModsFix](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](installation.md)

# Installation

> ⚠️ Lis d'abord le [README](../README.fr.md). Ce mod corrige les mods d'autres auteurs
> contre des versions précises, il est publié comme référence plutôt que comme quelque chose
> à installer, et aucun support n'est offert pour lui.

---

## Prérequis

### Obligatoires

| | Version |
|---|---|
| Minecraft | 1.21.1 |
| Fabric Loader | ≥ 0.16.0 |
| Java | ≥ 21 |
| Fabric API | le module `fabric-command-api-v2` est une dépendance dure |

C'est tout le bloc `depends` de `fabric.mod.json`. **Chaque mod que ce jar corrige est
optionnel au runtime.** Il n'y a ni `cobblemon`, ni `cobblesafari`, ni
`cobblemon_battle_tower` dans le bloc de dépendances, exprès :
`CheeseCompatMixinPlugin` interroge `FabricLoader.isModLoaded(...)` pour chaque cible et
éteint les mixins concernés quand le mod est absent, et les correctifs sans mixin sont
derrière la même vérification dans `CheeseCompatMod.onInitialize()`. L'installer ne t'oblige
pas à installer les quinze mods qu'il connaît.

### Optionnels — les mods qu'il corrige

Chacun de ceux-ci active un groupe de correctifs s'il est présent et est ignoré sinon. La
colonne version est celle contre laquelle le code a été écrit.

| Mod | Id du mod | Version |
|---|---|---|
| [Cobblemon](https://modrinth.com/mod/cobblemon) | `cobblemon` | 1.6.1 |
| [Cobblemon Battle Tower](https://modrinth.com/mod/cobblemon-battle-tower-1-6-1) | `cobblemon_battle_tower` | 1.7.11+1.6.1 |
| [CobbleSafari](https://modrinth.com/mod/cobblesafari) | `cobblesafari` | 0.1.5 |
| [Cobblemon Trials Edition](https://modrinth.com/mod/cobblemon-trials-edition) | `cobblemontrialsedition` | 1.2.2 |
| [Cobblemon Raid Dens](https://modrinth.com/mod/cobblemonraiddens) | `cobblemonraiddens` | 0.4.7 |
| [Cobblemon Fight or Flight Reborn](https://modrinth.com/mod/cobblemon-fight-or-flight-reborn) | `fightorflight` | 0.9.0 |
| [Legendary Monuments](https://modrinth.com/mod/legendary-monuments) | `legendarymonuments` | 7.2 |
| [Daycare+](https://modrinth.com/mod/daycareplus) | `daycareplus` | 1.1.0 |
| [Enhanced Celestials](https://modrinth.com/mod/enhanced-celestials) | `enhancedcelestials` | 6.0.2.5 |
| [Minerally](https://modrinth.com/mod/minerally) | `minerally` | 1.21 |
| [ExtraQuests](https://modrinth.com/mod/extraquests) | `extraquests` | 1.5.3 |
| [JourneyMap](https://modrinth.com/mod/journeymap) | `journeymap` | 1.21.1-6.0.0-beta.47 |
| [Numismatic Overhaul](https://modrinth.com/mod/numismatic-overhaul) | `numismatic-overhaul` | 0.3.4 |
| FTB Quests | `ftbquests` | 2101.1.13 (CurseForge) |
| StarAcademy | `academy` | v1.4.0+build.467 (CurseForge) |
| Cobblewarden Boss | `cobblewarden_boss` | 1.0.0 |
| MegaShowdown | — | atteint par réflexion seule, jamais déclaré |
| CobblemonAlphas | — | atteint par réflexion seule, jamais déclaré |
| Rad Gyms | `rad-gyms` | atteint par réflexion seule, jamais déclaré |

Rien dans ce dépôt ne dépend de ce mod, et ce mod ne dépend de rien dans ce dépôt, à une
exception optionnelle près : `BattleTowerDisconnectFix` entre dans
[CobbleCap](../../CobbleCap/) par réflexion, s'il est installé, pour suspendre l'application
du plafond de niveau pendant que la Battle Tower force les niveaux. L'absence de CobbleCap
ne change rien.

### De quel côté le met-on ?

**Des deux.** `fabric.mod.json` déclare `"environment": "*"`.

- Côté **serveur** : tout. Les 35 mixins serveur et tous les correctifs à base
  d'évènements.
- Côté **client** : quatre mixins seulement — le correctif des aspects dans l'écran
  d'équipe de Battle Tower, les deux classes d'icônes JourneyMap, et le cull des boutiques
  Numismatic. Les quatre sont cosmétiques ou de performance.

Une installation serveur seule est parfaitement valide et ne perd que ces quatre correctifs
client. Une installation client seule ne fait presque rien d'utile, mais ne casse rien non
plus.

---

## ⚠️ Le build exige deux jars que tu dois fournir

C'est le seul mod du dépôt (avec [CheeseFixQuests](../../CheeseFixQuests/)) qui ne se
construit **pas** depuis un clone propre. `build.gradle` vérifie la présence de deux
fichiers avant toute autre chose :

```text
localdeps/ftb-quests-fabric-2101.1.13.jar
localdeps/academy-mc1.21.1-v1.4.0+build.467-fabric.jar
```

Ces noms de fichiers sont exacts. Le build les référence par leur nom, pas par un glob.

FTB Quests et StarAcademy sont **exclusifs à CurseForge** : ni l'un ni l'autre n'est sur
Modrinth ni sur aucun dépôt Maven public, donc Gradle ne peut pas les récupérer. Télécharge
le fichier **Fabric 1.21.1** de chacun depuis CurseForge et dépose-le dans
[`localdeps/`](../../localdeps/README.md) à la **racine du dépôt** — pas dans
`CheeseCompatModsFix/`.

Si l'un des deux fichiers manque, le build s'arrête immédiatement et nomme ce qui manque :

```text
FAILURE: Build failed with an exception.

* Where:
Build file 'CheeseCompatModsFix/build.gradle'

* What went wrong:
A problem occurred evaluating project ':CheeseCompatModsFix'.
>
  CheeseCompatModsFix cannot be built without these third-party jars.

  Missing from localdeps/:
    - academy-mc1.21.1-v1.4.0+build.467-fabric.jar

  FTB Quests and StarAcademy are CurseForge-exclusive — neither is on Modrinth nor
  on any public Maven repository, so Gradle cannot download them for you. [...]
```

Il échoue à la phase de configuration, avant la compilation, donc tu obtiens ce message au
lieu d'un mur de « cannot find symbol ».

### Pourquoi un mixin qui désigne sa cible par son nom a besoin du jar

Tu pourrais raisonnablement t'attendre à ce qu'un mixin écrit ainsi n'ait besoin d'aucune
dépendance de compilation :

```java
@Mixin(targets = "theone_ss.minerally.block.OrePile")
```

Il en a besoin. Le processeur d'annotations Mixin résout et valide **chaque** cible à la
compilation et échoue avec `Mixin target ... could not be found` si la classe est absente.
Le jar doit donc être sur le classpath de compilation, que la cible soit importée ou nommée.

`.gitignore` exclut les `*.jar` sous `localdeps/`, donc rien de ce que tu y mets ne peut
finir dans un commit. Ces mods appartiennent à leurs auteurs et ne sont pas redistribués par
ce dépôt.

### Tout le reste est automatique

Les seize autres dépendances se résolvent depuis Maven sans rien faire de ta part : le Maven
Modrinth pour les mods, le Maven Nucleoid pour sgui, et le Maven de Fabric. Elles sont
épinglées à des **ids de version** Modrinth plutôt qu'à des numéros de version —
`maven.modrinth:cobblemon:v77SHSXW` et non `:1.6.1` — parce que les numéros de version ne
sont pas uniques sur Modrinth et que résoudre par numéro peut te refiler silencieusement le
jar NeoForge. Le raisonnement est détaillé dans
[`localdeps/README.md`](../../localdeps/README.md).

---

## Build

Aucun mod frère n'a besoin d'être construit d'abord — ce mod ne fait pas partie de la
chaîne de dépendances de CheeseDash.

```bash
cd CheeseCompatModsFix
./gradlew build
```

Le résultat est :

```text
build/libs/cheesecompatmodsfix-1.2.53+1.21.1.jar
```

Ignore les `-sources.jar` et `-dev.jar` du même dossier ; ils ne sont pas pour un serveur.

La version vient de `mod_version` dans `gradle.properties`.

⚠️ Le **nom** du jar dit `1.2.53+1.21.1`, mais `fabric.mod.json` déclare
`"version": "1.1.0"` en littéral, sans placeholder `${version}` que `processResources`
pourrait étendre. Donc la version que Fabric rapporte, et celle affichée dans la liste des
mods en jeu, est `1.1.0`. N'écris pas de contrainte de version contre
`cheesecompatmodsfix` depuis un autre mod tant que ce n'est pas corrigé.

---

## Installation

1. Arrête le serveur.
2. Supprime tout `cheesecompatmodsfix-*.jar` plus ancien de `mods/`. Deux copies d'un mod
   Fabric dans un même dossier est un échec de chargement, pas un choix de version.
3. Copie `cheesecompatmodsfix-1.2.53+1.21.1.jar` dans `mods/`.
4. Démarre le serveur.

Aucun jar frère n'est nécessaire à ses côtés.

## Ce que crée le premier démarrage

Un fichier :

```text
config/cheesecompatmodsfix.json
```

avec les trois défauts. Référence complète dans [configuration](configuration.fr.md).

Trois autres apparaissent plus tard, quand les correctifs qui les possèdent en ont besoin —
pas au démarrage :

```text
config/cheesecompat_bt_backups/        au premier verrouillage d'équipe Battle Tower
config/cheesecompat_daycare.json       quand le mode d'un pâturage Daycare+ est stocké pour la première fois
config/cheesecompatmodsfix_shrieker_cooldowns.json   au premier boss de cri de sculk
```

## Prouver qu'il est chargé

La ligne qui apparaît toujours, depuis `CheeseCompatMod.onInitialize()` :

```text
[CheeseCompat] Loaded (v1.2.53). Arc Phone heal=enabled, PC=enabled, Sanctuary fix=ON
```

Les trois valeurs reprennent ta config. `heal=DISABLED` ou `PC=DISABLED` en capitales
signifie que tu as mis cette clé à `false`.

Puis une ligne par famille de correctifs réellement activée. Sur un serveur avec la pile
complète, tu devrais voir une sélection de :

```text
[CheeseCompat] Registered cobblemon:tatami_mat and tatami_block for CobbleSafari compatibility
[CheeseCompat] Safari dimensional ban events registered (Cobblemon 1.6.x compat)
[CheeseCompat] Safari ball fix registered for CobbleSafari dimensions
[CheeseCompat] Slingshot block registered for CobbleSafari dimensions
[CheeseCompat] Dungeon capture block registered for dungeon_underground
[CheeseCompat] EnhancedCelestials lunar warmup registered
[CheeseCompat] Warmed lunar data for minecraft:overworld
[CheeseCompat] Restricted /stopbattle <player> to operators
```

**C'est l'absence d'une ligne qui est le signal important.** Si tu as installé CobbleSafari
et que tu ne vois pas les quatre lignes safari, l'id du mod n'a pas correspondu et aucun de
ces correctifs ne tourne. La ligne `Loaded (v1.2.53)` apparaît dans les deux cas, donc elle
ne t'apprend rien sur l'application des correctifs.

En jeu, la vérification de bout en bout la plus rapide est `/stopbattle` :

```mcfunction
/stopbattle
```

```text
Battle stop requested.
```

Voir ce message vert signifie que l'arbre de commandes s'est enregistré, donc que
`onInitialize` est allé jusqu'au bout.

## Une erreur inoffensive que tu verras sans CobbleSafari

```text
Couldn't parse element minecraft:loot_table/cheesecompat:blocks/cobblesafari_portal_frame
  - Unknown registry key ...: cheesecompat:cobblesafari_portal_frame
```

Le bloc cadre de portail est enregistré conditionnellement ; sa table de butin est livrée
inconditionnellement, donc la table est orpheline quand le bloc est absent. Le serveur
démarre normalement. Vérifié sur un serveur de test à 475 mods. C'est du bruit, pas un
échec — mais c'est la première chose que tout le monde signale, donc elle est documentée
plutôt que cachée.

---

## Désinstallation

1. Arrête le serveur.
2. Supprime `cheesecompatmodsfix-*.jar` de `mods/`.
3. Démarre le serveur.

Rien d'autre n'est nécessaire. Aucun mixin ne laisse de trace dans une sauvegarde, et aucun
correctif n'écrit dans les données de sauvegarde d'un autre mod.

### Ce qui reste derrière

| Chemin | Suppression sans risque | Note |
|---|---|---|
| `config/cheesecompatmodsfix.json` | oui | trois booléens |
| `config/cheesecompat_daycare.json` | oui | Daycare+ revient à son propre comportement de reproduction |
| `config/cheesecompatmodsfix_shrieker_cooldowns.json` | oui | les délais n'ont de sens que pour ce mod |
| `config/cheesecompat_bt_backups/*.dat` | ⚠️ **vérifie d'abord** | un snapshot d'équipe pour un joueur dont la restauration n'a jamais abouti |

### Ce que le monde conserve

Trois choses survivent dans la sauvegarde et demandent une décision.

**Les blocs `cheesecompat:cobblesafari_portal` et
`cheesecompat:cobblesafari_portal_frame`.** Ceux qui sont posés deviennent des blocs
inconnus. Casse-les ou remplace-les avant de retirer le jar si tu te soucies de l'aspect du
monde.

**Les blocs `cobblemon:tatami_mat` et `cobblemon:tatami_block`.** Ils sont enregistrés par
*ce* mod dans l'espace de noms de *Cobblemon*, pour le worldgen de CobbleSafari. Retire le
jar sur Cobblemon 1.6.1 et chaque bloc tatami posé dans le monde — y compris ceux générés
par CobbleSafari — devient un bloc inconnu. Sur Cobblemon 1.7.x, où ces blocs existent en
amont, rien n'est perdu.

**Une équipe en cours de restauration.** Si un joueur s'est déconnecté pendant un combat de
Battle Tower et que tu retires le jar avant son retour, la sauvegarde `.dat` est la seule
copie restante de sa vraie équipe et plus rien ne la lira jamais. Vérifie que
`config/cheesecompat_bt_backups/` est vide avant de retirer le jar, ou garde le dossier.

### Retirer les patches Cobblewarden

Si tu as aussi appliqué les patches de `patches/cobblewarden/` à ton
`cobblewarden_boss-fabric-1.0.0.jar`, ce jar est modifié et retirer ce mod ne le défait pas.
Remplace-le par le téléchargement d'origine. La procédure, et le diff original/patché, sont
dans `patches/cobblewarden/README.md` — qui note aussi que ces patches sont **du code de
LiquidSilverAce sous CC0-1.0**, pas de RoiCheese, et gardent leur propre attribution.

---

← [CheeseCompatModsFix](../README.fr.md) · [Premiers pas](getting-started.fr.md) →
