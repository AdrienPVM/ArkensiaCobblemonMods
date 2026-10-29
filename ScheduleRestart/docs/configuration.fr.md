[← ScheduleRestart](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](configuration.md)

# Configuration

**Il n'existe aucun fichier de configuration.** Rien n'apparaît dans `config/` au premier
démarrage, rien n'apparaît plus tard, et le mod ne lit ni n'écrit jamais de fichier.
`FabricLoader.getInstance().getConfigDir()` n'apparaît pas dans le code. Il n'y a pas de
classe `*Config`, pas de Gson, pas de commande de rechargement, pas de page dashboard.

Tout ce qui pourrait être configurable est un littéral du fichier source unique,
`src/main/java/com/schedulerestart/ScheduledRestart.java`. En changer quoi que ce soit
implique de modifier ce fichier et de recompiler le jar. Cette page est donc une carte
indiquant quelle ligne contrôle quoi, pour que tu n'aies pas à lire toute la classe pour
changer une couleur.

La seule chose qu'un opérateur peut faire varier à l'exécution, ce sont les arguments de la
commande : le délai en minutes, et le fait que le second argument soit le mot `stop` ou
non. Ils sont documentés dans [commandes](commands.fr.md).

## Ce qui est écrit en dur, et où

Les numéros de ligne correspondent au fichier tel qu'il est en version `1.0.0+1.21.1`,
171 lignes.

| Comportement | Valeur actuelle | Ligne | Code |
|---|---|---|---|
| Nom de la commande | `schedulerestart` | 28 | `CommandManager.literal("schedulerestart")` |
| Niveau de permission | `2` | 29 | `source.hasPermissionLevel(2)` |
| Délai minimum | `1` minute | 30 | `IntegerArgumentType.integer(1, 60)` |
| Délai maximum | `60` minutes | 30 | même appel |
| Mot-clé d'arrêt | `stop`, insensible à la casse | 35 | `mode.equalsIgnoreCase("stop")` |
| Noms d'action | `Arrêt` / `Redémarrage` | 52 | `String actionCap = isStop ? "Arrêt" : "Redémarrage";` |
| Couleurs d'avertissement | or pour l'arrêt, aqua pour le redémarrage | 54 | `isStop ? Formatting.GOLD : Formatting.AQUA` |
| Message d'ouverture | `⚠ … du serveur dans N minute(s) !` | 56 | `broadcastColored(...)` |
| Intervalle des rappels | toutes les `60 000` ms | 65 | `i * 60 * 1000L` |
| Texte des rappels | `⚠ … dans N minute(s) !` | 63 | dans le premier `TimerTask` |
| Longueur du décompte en secondes | `10` dernières secondes | 68 | `for (int i = 10; i > 0; i--)` |
| Seuil rouge/jaune | `5` secondes | 75 | `seconds <= 5 ? Formatting.RED : Formatting.YELLOW` |
| Texte du décompte en secondes | `⏱ … dans N seconde(s)...` | 76 | dans le second `TimerTask` |
| Annonce finale | `🔄 … en cours...` en rouge | 84 | `broadcastColored(..., Formatting.RED, true)` |
| Délai de grâce avant l'arrêt | `1000` ms | 86 | `Thread.sleep(1000)` |
| Comment le serveur s'arrête | `server.stop(false)` | 99 | l'appel d'arrêt vanilla |
| Gras sur les diffusions | toujours actif | 112 | `if (bold) text = text.formatted(Formatting.BOLD)` |
| Confirmation à l'auteur | `✅ … planifié dans N minute(s)`, vert | 104 | `Text.literal(...).formatted(Formatting.GREEN)` |
| Motif du jar lanceur Fabric | `fabric-server-launch*.jar` | 143 | `name.startsWith("fabric-server-launch")` |
| Motif du jar de repli | `server*.jar` | 147 | `name.startsWith("server")` |
| Argument passé à la JVM enfant | `nogui` | 162 | `command.add("nogui")` |

## Les modifications que l'on veut vraiment faire

### Relever le plafond d'une heure

`IntegerArgumentType.integer(1, 60)`, ligne 30, plafonne le délai à 60 minutes. L'élargir
à `integer(1, 1440)` est arithmétiquement sûr : le plus long délai devient
`1440 × 60 × 1000 = 86 400 000` ms, très loin des limites d'un `long`, et le
`i * 60 * 1000L` de la boucle des rappels est déjà promu en `long` par le littéral
`1000L`.

Ce qui n'est **pas** sûr, c'est la conséquence : une planification de 24 heures met 1440
tâches en file et suppose que la JVM vivra une journée sans être redémarrée par autre
chose. Le mod n'a aucune persistance : un crash à la vingtième heure oublie tout
silencieusement.

### Abaisser le niveau de permission

Ligne 29. Passer à `hasPermissionLevel(0)` laisserait n'importe quel joueur arrêter le
serveur, et c'est pourquoi la valeur est 2. Si tu veux qu'un non-op de confiance s'en
serve, garde le niveau et accorde la permission via un mod de permissions plutôt qu'en
modifiant cette ligne.

### Traduire les messages

Il n'y a aucun fichier de langue à modifier. Les lignes 52, 56, 63, 76, 84 et 104
contiennent les seules chaînes vues par les joueurs, sous forme de littéraux français. Un
jeu anglais donnerait :

```java
String actionCap = isStop ? "Shutdown" : "Restart";
```

avec les cinq gabarits de message réécrits en conséquence, y compris leur mise au pluriel,
actuellement `(n > 1 ? "s" : "")` — correcte en français comme en anglais.

Le faire proprement veut dire `Text.translatable` avec des clés dans
`src/main/resources/assets/scheduledrestart/lang/en_us.json` et `fr_fr.json`. Ce
répertoire n'existe pas. Le créer est la bonne correction et c'est listé dans
[les faiblesses connues](architecture.fr.md#faiblesses-connues).

⚠️ Les clés de traduction `cheesedash.scheduler.*` que tu pourrais trouver en cherchant
dans le dépôt appartiennent au **planificateur propre à CheeseDash**, pas à ce mod. Elles
vivent dans `CheeseDash/src/main/resources/assets/cheesedash/lang/`. Ce mod n'en utilise
aucune et n'a pas de fichier de langue : chercher du texte traduisible dans son jar ne
donne rien.

### Changer la forme du compte à rebours

Trois pièces indépendantes :

- Les **rappels à la minute** viennent de la boucle des lignes 58 à 66. Remplacer
  `i * 60 * 1000L` par `i * 300 * 1000L` ne donnerait pas des rappels toutes les cinq
  minutes, parce que la boucle calcule aussi `remaining = minutes - i` et annoncerait donc
  le mauvais chiffre. Si tu veux des rappels à 30, 15, 5 et 1 minute, remplace la boucle
  par des appels `schedule(...)` explicites plutôt que d'ajuster le multiplicateur.
- Le **décompte en secondes** vient de la boucle des lignes 68 à 79 et est ancré à la fin
  de la planification par `(minutes * 60 - seconds) * 1000L`. Passer `i = 10` à `i = 30`
  donne un décompte de trente secondes et fonctionne tel quel, du moment que la durée
  totale est plus longue que le décompte — ce que le minimum d'une minute imposé par
  `integer(1, 60)` garantit jusqu'à 59 secondes.
- Le **seuil de couleur** est la ligne 75, isolée, et peut être changé librement.

### Faire en sorte que la relance trouve ton jar

Les lignes 139 à 151 sont le repli qui s'exécute quand aucun `-jar` n'apparaît dans les
arguments du processus. Si ton serveur est lancé par classpath et que le repli choisit le
mauvais fichier, la correction honnête est d'écrire le tien en dur :

```java
if (jarPath == null) {
    jarPath = "fabric-server-launch.jar";
}
```

Remplacer une devinette par une valeur connue est ici une amélioration, pas une
régression. Pourquoi la devinette existe et comment elle se trompe est expliqué dans
[ce qui se passe à l'échéance](restart.fr.md#comment-le-jar-du-serveur-est-localisé).

## Valeurs fixées à la compilation

Deux fichiers contiennent des valeurs qui ressemblent à de la configuration et qui ne sont
pas rechargeables du tout.

`gradle.properties` :

```properties
mod_version=1.0.0+1.21.1
maven_group=com.schedulerestart
archives_base_name=scheduledrestart
minecraft_version=1.21.1
yarn_mappings=1.21.1+build.3
loader_version=0.16.9
fabric_version=0.107.0+1.21.1
```

⚠️ `archives_base_name=scheduledrestart` est **déclaré et jamais utilisé**. `build.gradle`
n'a aucun bloc `base { archivesName = ... }` : Gradle nomme donc le jar d'après le projet,
c'est-à-dire d'après le nom du dossier. Le jar s'appelle
`ScheduleRestart-1.0.0+1.21.1.jar` alors que l'identifiant du mod est `scheduledrestart`.
Les deux noms sont corrects dans leur monde respectif et aucun ne correspond à l'autre.
Rien ne casse — le nom de fichier d'un jar dans `mods/` est purement cosmétique — mais ne
cherche pas `scheduledrestart` dans ton répertoire `mods/` pour en conclure que le mod est
absent. L'incohérence est documentée plutôt que corrigée, pour que le nom de jar donné
dans cette documentation continue de correspondre à ce qu'une compilation produit
réellement.

`fabric.mod.json` déclare les dépendances, et se trompe dans les deux sens :

```json
"depends": {
  "fabricloader": ">=0.16.0",
  "minecraft": "1.21.1",
  "java": ">=21",
  "cheesedash": "*"
}
```

`cheesedash` est exigé et inutilisé. `fabric-api` est utilisé — le seul appel à Fabric API
du mod est `CommandRegistrationCallback.EVENT.register` — et non déclaré. Les deux cas
sont expliqués dans
[architecture](architecture.fr.md#la-dépendance-qui-ne-sert-à-rien).

## Un exemple de configuration complet

Il n'y en a aucun à donner, puisqu'il n'y a pas de fichier. L'équivalent le plus proche est
le patch que tu appliquerais pour faire tourner le mod en anglais avec un décompte de
trente secondes et un plafond de deux heures, annoté pour que tu puisses n'en prendre que
les morceaux qui t'intéressent :

```java
// ligne 30 — élargit le délai accepté à deux heures
CommandManager.argument("minutes", IntegerArgumentType.integer(1, 120))

// ligne 52 — noms anglais ; tout le reste réutilise cette chaîne
String actionCap = isStop ? "Shutdown" : "Restart";

// ligne 56 — l'annonce d'ouverture
broadcastColored(server, "⚠ Server " + actionCap.toLowerCase() + " in " + minutes
        + " minute" + (minutes > 1 ? "s" : "") + "!", actionColor, true);

// ligne 68 — trente secondes de décompte à la seconde au lieu de dix
for (int i = 30; i > 0; i--) {

// ligne 75 — garde le rouge pour les cinq dernières secondes
Formatting countColor = seconds <= 5 ? Formatting.RED : Formatting.YELLOW;

// ligne 140 — arrête de deviner ; nomme le lanceur que tu utilises vraiment
jarPath = "fabric-server-launch.jar";
```

Recompile avec `./gradlew build` et redéploie le jar. Il n'existe aucun chemin de
rechargement : l'arbre de commandes et tous les messages sont figés au chargement de la
classe, donc un serveur en cours d'exécution garde l'ancien comportement jusqu'à son
redémarrage.

---

← [Ce qui se passe à l'échéance](restart.fr.md) · [Architecture](architecture.fr.md) →
