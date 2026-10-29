[← ScheduleRestart](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](architecture.md)

# Architecture

Un fichier source, 171 lignes, une classe, aucun mixin, aucun paquet réseau, aucune
persistance. Cette page existe moins pour expliquer la structure que pour être honnête sur
ce qu'elle n'a pas.

## Arborescence

```text
ScheduleRestart/
├── build.gradle                  Loom 1.8, Java 21, aucune dépendance vers un mod frère
├── gradle.properties             mod_version=1.0.0+1.21.1 · archives_base_name (inutilisé)
├── settings.gradle               dépôt du plugin Fabric uniquement ; pas de rootProject.name
├── gradle/wrapper/               wrapper Gradle 8.10
└── src/main/
    ├── java/com/schedulerestart/
    │   └── ScheduledRestart.java  tout
    └── resources/
        └── fabric.mod.json        id scheduledrestart · environment server
```

Il n'y a pas de répertoire `assets/`, pas de `data/`, pas de `*.mixins.json`, pas de
package `client/` et pas de package `dashboard/`. Rien n'est enregistré dans aucun registre
Minecraft : aucun item, aucun bloc, aucune entité, aucun son, aucune particule, aucun
critère de scoreboard.

Le projet se compile seul. `build.gradle` n'a aucun glob `fileTree` pointant vers le
`build/libs/` d'un autre mod : l'ordre de compilation du dépôt n'a donc aucune importance
pour celui-ci — même si `fabric.mod.json` exige CheeseDash à l'exécution.

## La classe unique

`com.schedulerestart.ScheduledRestart implements ModInitializer`.

| Membre | Ce qu'il possède |
|---|---|
| `static Timer currentTimer` | la planification armée, unique. `null` jusqu'à la première commande |
| `onInitialize()` | enregistre l'arbre Brigadier et rend la main. Rien d'autre ne se passe au démarrage |
| `executeRestart(ctx, isStop)` | lit les arguments, annule la minuterie précédente, met toutes les tâches en file, confirme à l'auteur |
| `broadcastColored(server, msg, color, bold)` | construit un `MutableText`, le formate, et confie la diffusion au thread serveur |
| `restartServer()` | construit une ligne de commande `java … -jar … nogui` à partir du processus courant et la démarre avec `ProcessBuilder` |

Que `currentTimer` soit `static` sur un `ModInitializer` est redondant — Fabric instancie
l'initialiseur exactement une fois — mais inoffensif, et cela rend le champ accessible
depuis les corps anonymes des `TimerTask` sans capture.

## État et disque

Le mod a une seule pièce d'état, le `Timer` dans `currentTimer`, et elle ne vit qu'en
mémoire. **Rien n'est jamais écrit sur le disque.** Pas de configuration, pas de fichier de
planification, pas de marqueur, pas de verrou. L'unique conséquence est qu'une
planification ne survit pas à la JVM : si le serveur crashe ou est redémarré par autre
chose pendant qu'une planification est armée, elle disparaît et personne n'est averti.

C'est un choix défendable pour un outil dont l'horizon maximum est de soixante minutes. Il
cesse de l'être dès que quelqu'un élargit la borne `integer(1, 60)`.

## Threading

Trois threads comptent, et les passations entre eux sont le seul détail mécanique
intéressant du mod.

| Thread | Ce qui s'y exécute |
|---|---|
| Thread principal du serveur | `onInitialize`, l'enregistrement de la commande, `executeRestart` (qui tourne dans le dispatch de commande), chaque diffusion, et `server.stop(false)` |
| Thread `java.util.Timer` | tous les corps de `TimerTask` : le *déclenchement* de chaque annonce, la pause d'une seconde, et `restartServer()` |
| Le processus enfant | une JVM entièrement neuve, pas un thread, lancée par `ProcessBuilder.start()` |

Deux passations, toutes deux correctes :

```java
server.execute(() -> server.getPlayerManager().broadcast(finalText, false));
```

et

```java
server.execute(() -> server.stop(false));
```

Chaque contact avec l'état Minecraft passe par `server.execute`, qui met le travail en file
sur le thread principal. Le thread de la minuterie ne fait jamais que construire des objets
`Text` — immuables — et appeler `ProcessBuilder`. Il n'y a aucune synchronisation sur
`currentTimer`, et aucune n'est nécessaire en pratique puisque le champ n'est affecté que
depuis le dispatch de commande, qui est toujours le thread serveur.

`Thread.sleep(1000)` s'exécute sur le thread de la minuterie : la seconde de grâce avant
l'arrêt ne bloque donc **pas** la boucle de ticks. Le serveur continue de ticker à pleine
vitesse jusqu'à ce que `stop` soit effectivement dépilé.

### Le mode de défaillance de cette conception

Un `Timer` tourne indépendamment de la boucle de ticks, c'est pourquoi le compte à rebours
reste exact sur un serveur qui lague. C'est aussi pourquoi le compte à rebours et l'arrêt
deviennent muets si le thread serveur se bloque : `server.execute` met en file un travail
qu'un thread gelé ne dépile jamais. La planification semble avoir disparu. En mode arrêt,
rien n'est perdu et rien n'est corrompu, mais un administrateur qui regarde le chat voit
un décompte s'arrêter sur `dans 4 secondes...` et un serveur qui reste debout. En mode
redémarrage, le thread de la minuterie atteint quand même `restartServer()` à zéro et lance
la JVM de remplacement à côté de celle qui est figée — laquelle échoue alors sur le port ou
sur le verrou du monde.

## Mixins

Aucun. Il n'y a pas de `scheduledrestart.mixins.json` et pas de clé `mixins` dans
`fabric.mod.json`. Le mod ne touche à aucun interne vanilla et ne cassera donc pas sur un
correctif Minecraft pour cette raison — seuls les appels d'API mappés par Yarn
(`MinecraftServer.stop`, `PlayerManager.broadcast`,
`ServerCommandSource.hasPermissionLevel`) pourraient changer, et ce sont des API publiques
stables.

## La dépendance qui ne sert à rien

`fabric.mod.json` :

```json
"depends": {
  "fabricloader": ">=0.16.0",
  "minecraft": "1.21.1",
  "java": ">=21",
  "cheesedash": "*"
}
```

`cheesedash` est une dépendance dure. Fabric Loader refuse de démarrer un serveur sans le
jar CheeseDash. Et pourtant :

- `ScheduledRestart.java` n'importe rien de `com.cheesedash`.
- Il n'y a aucun entrypoint `"cheesedash"` :
  `FabricLoader.getEntrypoints("cheesedash", …)` ne trouve donc jamais ce mod et le
  registre de CheeseDash n'apprend jamais son existence.
- Pas de `DashboardModule`, pas de classe `*Api`, pas de route REST sous `/api/v1/`, pas de
  canal WebSocket, aucun appel `broadcast(...)` vers `DashboardRegistry`.
- Le mod n'apparaît même pas dans la barre latérale du dashboard, puisque celle-ci est
  construite à partir des modules enregistrés.

La déclaration n'apporte donc rien et coûte un jar. L'histoire probable est que le mod a
été écrit en même temps que CheeseDash et qu'une page dashboard était prévue ; la ligne de
dépendance est entrée d'abord et la page n'a jamais suivi. **L'intention n'est pas
reconstituable depuis le code** : il n'y a ni module commenté, ni classe bouchon, ni
`TODO`.

Les deux directions seraient une amélioration : retirer `cheesedash` de `depends` et le mod
devient vraiment autonome, ou écrire le module et la dépendance devient vraie. En attendant,
traite-la comme un défaut d'empaquetage et garde le jar CheeseDash installé.

L'erreur symétrique est dans le même bloc. Le seul appel à Fabric API du mod est

```java
CommandRegistrationCallback.EVENT.register(...)
```

issu de `net.fabricmc.fabric.api.command.v2`, et `fabric-api` n'est **pas** dans `depends`.
Sans Fabric API, le mod se charge puis meurt sur un `NoClassDefFoundError` pendant son
initialisation, au lieu d'être refusé avec un message lisible *requires fabric-api*. Le
`build.gradle` déclare bien un `modImplementation` sur Fabric API : c'est donc purement un
trou dans les métadonnées d'exécution.

## Points d'extension

Honnêtement : il n'y en a aucun. Aucun événement n'est émis, aucune interface n'est
publiée, aucune méthode statique n'est appelable depuis un autre mod, et `restartServer()`
est privée. Un mod qui voudrait déclencher un compte à rebours devrait passer la commande
par le dispatcher.

Si tu veux étendre ça, les deux changements qui l'ouvriraient sont :

1. Donner à la commande une sous-commande `cancel` et exposer la planification en attente
   sous forme de petit record immuable. Cela seul rend le mod utilisable depuis un
   dashboard.
2. Remplacer les trois boucles `Timer` improvisées par une liste de paires
   `(offsetMillis, clé de message)` construite une fois. La chronologie devient alors une
   donnée, ce qui la rend configurable et testable, et rend un `cancel` correct par
   construction.

## Faiblesses connues

Listées approximativement par ordre de probabilité de te faire mal.

**La relance lance l'enfant avant la mort du parent.** `restartServer()` s'exécute, puis
l'arrêt est mis en file. Les deux JVM existent en même temps et se disputent le port 25565
et le `session.lock` du monde. Ça marche parce que le démarrage du nouveau serveur est plus
lent que l'arrêt de l'ancien. C'est une course, pas une conception, et il n'y a aucune
nouvelle tentative si l'enfant la perd. Détail complet dans
[pourquoi un panel d'hébergement casse tout](restart.fr.md#pourquoi-un-panel-dhébergement-casse-tout).

**Une relance échouée arrête quand même le serveur.** Le `catch (Exception e)` autour de
`restartServer()` affiche la trace et poursuit vers `server.stop(false)`. Il n'y a aucune
branche « je n'ai pas trouvé de jar, donc je ne m'arrête pas ». Une relance mal configurée
met le serveur hors ligne indéfiniment.

**Le repli de recherche de jar peut démarrer un serveur vanilla.** Si le balayage du
répertoire tombe sur `server.jar` — le paquet vanilla que Fabric garde à côté de son
lanceur — la JVM de remplacement fait tourner du Minecraft vanilla sans aucun mod. Le
serveur revient et rien ne fonctionne, ce qui est plus difficile à diagnostiquer qu'un
serveur resté éteint.

**Il n'y a aucune commande d'annulation.** Une seule minuterie statique, remplacée
silencieusement à chaque nouvelle commande, sans aucun moyen de la désarmer. Le
contournement est dans
[commandes](commands.fr.md#relancer-la-commande-annule-la-planification-précédente) et
c'est un contournement.

**Annuler n'avertit personne.** Les joueurs prévenus d'un arrêt reçoivent un nouvel
avertissement d'ouverture avec d'autres chiffres et aucun démenti de l'ancien.

**Tout mot différent de `stop` signifie redémarrage.** `/schedulerestart 5 shutdown`
relance le serveur. `equalsIgnoreCase("stop")` sans validation de l'alternative est à une
faute de frappe de l'inverse de ton intention, et l'argument n'offre aucune complétion pour
te protéger.

**Tout le texte joueur est du français écrit en dur.** Aucun fichier de langue, aucun
`Text.translatable`. Un serveur anglophone affiche des avertissements français et la seule
correction est une modification du code.

**Le `Timer` n'est pas démon et il est retenu par un champ statique.** `new Timer()` crée
un thread non démon, et `currentTimer` en garde une référence forte pour toujours : le
thread n'est donc jamais collecté et ne se termine jamais de lui-même. Sur un serveur
dédié, on n'a pas observé que cela empêche le processus de se terminer, et le chemin
d'arrêt n'en dépend pas — mais un thread non démon qui traîne a exactement la forme d'une
JVM qui refuse de mourir, et `new Timer(true)` supprimerait le risque sans rien coûter.

**Aucune persistance.** Une planification armée disparaît si la JVM tombe pour n'importe
quelle autre raison.

**Aucun test.** Il n'y a pas de source set de test et aucun moyen d'exercer la chronologie
sans faire tourner un serveur et attendre une minute.

## Ce qui est délibéré

Deux choses qui ressemblent à des oublis et n'en sont pas.

Le mod ne sauvegarde **pas** le monde lui-même avant de s'arrêter. Il appelle
`server.stop(false)`, et l'arrêt vanilla sauvegarde tout. Ajouter un `saveAll` explicite
avant l'arrêt ne ferait que sauvegarder deux fois.

Le compte à rebours n'est **pas** piloté par les ticks du serveur. Un décompte piloté par
les ticks dérive sur un serveur qui lague, et le lag est la condition normale du serveur
pour lequel ce mod a été écrit. Utiliser l'horloge murale fait que le redémarrage a lieu au
moment annoncé, ce qui est ce qu'on a promis aux joueurs.

---

← [Configuration](configuration.fr.md) · [Dépannage](troubleshooting.fr.md) →
