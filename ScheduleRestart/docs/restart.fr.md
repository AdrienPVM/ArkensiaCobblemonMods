[← ScheduleRestart](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](restart.md)

# Ce qui se passe à l'échéance

La dernière tâche de la minuterie est la seule à faire quelque chose d'irréversible. Lis
cette page avant de brancher le mod sur un serveur auquel quelqu'un tient, parce que la
moitié « arrêt » est solide et la moitié « relance » ne l'est pas.

## La tâche finale, ligne par ligne

```java
currentTimer.schedule(new TimerTask() {
    @Override
    public void run() {
        broadcastColored(server, "🔄 " + actionCap + " en cours...", Formatting.RED, true);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        if (!isStop) {
            try {
                restartServer();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        server.execute(() -> server.stop(false));
    }
}, minutes * 60 * 1000L);
```

Quatre étapes, dans cet ordre :

1. **Annoncer.** `🔄 Redémarrage en cours...` (ou `🔄 Arrêt en cours...`) en rouge gras,
   mis en file sur le thread serveur comme toutes les autres diffusions.
2. **Attendre une seconde** sur le thread de la minuterie. C'est ce qui laisse à
   l'annonce le temps d'atteindre les clients avant la coupure réseau. Le thread serveur
   n'est pas bloqué par cette pause — elle a lieu sur le thread propre de la minuterie —
   donc le serveur continue de ticker normalement pendant cette seconde.
3. **Lancer la JVM de remplacement**, en mode redémarrage uniquement. Cela se produit
   *avant* l'arrêt, pas après. Voir [la relance](#la-relance) pour comprendre pourquoi
   c'est important.
4. **Demander au serveur de s'arrêter**, depuis le thread serveur.

## L'arrêt est l'arrêt vanilla

```java
server.execute(() -> server.stop(false));
```

`MinecraftServer.stop(boolean)` est la méthode même qu'appelle la commande vanilla
`/stop`. Le mod ne tue pas le processus, n'appelle pas `System.exit`, n'envoie pas de
`SIGTERM`, et n'exécute aucune commande. Il appelle l'API ordinaire et laisse Minecraft
faire son propre démontage :

- les joueurs sont déconnectés avec le message standard *Server closed*
- chaque monde chargé est sauvegardé
- les données des joueurs sont écrites
- la session de stockage du niveau est libérée et le `session.lock` relâché

**Le mod ne déclenche aucune sauvegarde explicite de son côté.** Pas de `/save-all`, pas
d'appel à `saveAll(...)`, aucun vidage avant l'arrêt. La sauvegarde a lieu parce que la
séquence d'arrêt vanilla l'effectue, exactement comme si un opérateur tapait `/stop`. Si un
mod corrompt tes données sur un `/stop` normal, ceci n'y change rien ; si `/stop` est sûr
sur ton serveur, ceci l'est aussi.

L'enveloppe `server.execute(...)` compte : `stop` doit s'exécuter sur le thread principal
du serveur, et la tâche finale tourne sur un thread `java.util.Timer`. Appeler `stop`
directement depuis le thread de la minuterie démonterait le serveur sous les pieds de sa
propre boucle de ticks. Le mod fait ça correctement.

En mode arrêt (`stop` en second argument), l'histoire s'arrête là. Le processus se termine
et rien ne tente de le ramener.

## La relance

En mode redémarrage, `restartServer()` s'exécute juste avant que l'arrêt ne soit mis en
file. Elle construit une nouvelle ligne de commande à partir du processus courant et la
démarre avec `ProcessBuilder`.

### Comment le binaire Java est trouvé

```java
String javaBin = ProcessHandle.current().info().command()
        .orElse(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
```

L'exécutable du processus en cours, avec `$JAVA_HOME/bin/java` en repli. Sur un hôte Linux
ou Windows normal, la première branche aboutit et l'enfant tourne sous la même JVM que le
parent.

### Comment les options JVM sont reportées

```java
List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
```

Cet appel rend les arguments **de la JVM** : `-Xmx8G`, `-XX:+UseG1GC`, chaque propriété
système `-D`, chaque `-javaagent`. Il ne rend **pas** les arguments du programme, et
n'inclut ni `-jar` ni le chemin du jar. Les réglages mémoire et le GC survivent donc à un
redémarrage, et tout ce qui venait après le nom du jar, non.

### Comment le jar du serveur est localisé

Deux stratégies, essayées dans cet ordre.

```java
for (int i = 0; i < processArgs.size() - 1; i++) {
    if (processArgs.get(i).equals("-jar")) {
        jarPath = processArgs.get(i + 1);
        break;
    }
}
```

D'abord, le mod parcourt les arguments de son propre processus à la recherche de `-jar` et
prend le token suivant. C'est le chemin fiable, et il ne fonctionne que si le serveur a été
lancé comme `java … -jar quelquechose.jar`.

S'il n'y a pas de `-jar` — un lancement par classpath, un script de démarrage qui passe
directement une classe principale, ou la plupart des panels d'hébergement — le code
retombe sur un balayage du répertoire de travail :

```java
for (File f : workDir.listFiles()) {
    String name = f.getName().toLowerCase();
    if (name.startsWith("fabric-server-launch") && name.endsWith(".jar")) {
        jarPath = f.getName();
        break;
    }
    if (name.startsWith("server") && name.endsWith(".jar")) {
        jarPath = f.getName();
    }
}
```

Remarque l'asymétrie : la branche `fabric-server-launch*.jar` **sort** de la boucle, celle
de `server*.jar` non. Un répertoire contenant à la fois `server.jar` (le paquet vanilla que
Fabric télécharge) et `server-1.21.1.jar` garde donc celui qui arrive en dernier dans
l'ordre de `listFiles()`, ordre dépendant du système de fichiers et nullement alphabétique
par spécification. Un répertoire qui contient un `fabric-server-launch.jar` va bien, pour
peu que `listFiles()` l'atteigne — et si un `server*.jar` a été retenu plus tôt dans
l'itération, il est écrasé dès que le fichier Fabric est atteint, ce qui arrivera, puisque
le `break` est inconditionnel une fois la correspondance établie.

La règle pratique : **ça marche sur une installation
`java -jar fabric-server-launch.jar nogui` et c'est un coup de dé sur tout le reste.**

Si aucune des deux stratégies ne trouve de jar :

```java
throw new Exception("Impossible de trouver le jar du serveur pour le redémarrage !");
```

Cette exception est attrapée par le `catch (Exception e)` qui entoure l'appel, affichée
sous forme de trace, puis **jetée. L'arrêt se poursuit malgré tout.** Le serveur s'arrête
et ne revient pas. Aucune annulation, aucune nouvelle tentative, aucun message à ceux qui
sont encore connectés.

### La commande effectivement lancée

```java
command.add(javaBin);
command.addAll(jvmArgs);
command.add("-jar");
command.add(jarPath);
command.add("nogui");
```

donc le processus enfant est

```text
<java> <toutes les options JVM> -jar <jarPath> nogui
```

et le mod le journalise, ce qui est la seule ligne que tu pourras chercher après coup :

```text
[ScheduledRestart] Relancement : /usr/bin/java -Xmx8G -XX:+UseG1GC -jar fabric-server-launch.jar nogui
```

`nogui` est ajouté inconditionnellement. Tout **autre** argument de programme avec lequel
le serveur avait été démarré est perdu : `--universe`, `--world`, `--port`,
`--bonusChest`, un `--serverId` personnalisé. Si ton script de démarrage passe quoi que ce
soit après le nom du jar au-delà de `nogui`, le serveur relancé tourne sans.

```java
ProcessBuilder pb = new ProcessBuilder(command);
pb.directory(new File(System.getProperty("user.dir")));
pb.inheritIO();
pb.start();
```

Le répertoire de travail est hérité explicitement, donc les chemins relatifs se résolvent
de la même façon. `inheritIO()` transmet à l'enfant les entrées, sorties et erreurs
standard du parent.

## Pourquoi un panel d'hébergement casse tout

Quatre raisons indépendantes, chacune suffisante.

**Le lancement n'est généralement pas en `-jar`.** Pterodactyl, Multicraft et la plupart
des panels construisent une commande de démarrage avec un classpath ou un wrapper : le
balayage de `-jar` échoue et le repli par répertoire doit deviner. Souvent il devine le
`server.jar` vanilla, qui démarre un serveur **vanilla** sans aucun mod — le pire des
résultats, parce que le serveur revient bien et ne charge silencieusement aucun mod.

**L'enfant est lancé avant la mort du parent.** `restartServer()` s'exécute, puis l'arrêt
est mis en file. Pendant un instant les deux JVM existent. La nouvelle tente de prendre le
port 25565 alors que l'ancienne le tient encore, et d'acquérir le `session.lock` d'un monde
que l'ancienne n'a pas relâché. Selon le timing, elle échoue sur *Failed to bind to port*
ou sur *world is already in use*, ou elle survit parce que son propre démarrage est plus
lent que l'arrêt de l'ancien serveur. **Le mod parie sur le fait que cette course tourne en
sa faveur.** Il n'a ni attente, ni nouvelle tentative, ni poignée de main.

**Les panels tuent des groupes de processus.** Quand le parent se termine, le superviseur
du panel voit son processus géré disparaître et récolte tout le groupe de processus, ou
marque le serveur comme arrêté et refuse de reconnaître un processus qu'il n'a pas démarré.
L'enfant meurt donc avec son parent, ou tourne de façon invisible, le panel affichant
*hors ligne* alors que le port 25565 répond.

**`inheritIO()` pointe vers des descripteurs sur le point de se fermer.** La console de
l'enfant est celle du parent mourant. Sur un panel, la sortie standard du parent est un
tube que le superviseur ferme en récoltant le processus : la sortie de log de l'enfant ne
va donc nulle part et son entrée standard est morte, ce qui veut dire que tu ne pourras
plus jamais y taper `stop`.

### Ce qu'il faut faire à la place

Utilise le mode arrêt et laisse quelque chose d'extérieur à la JVM redémarrer le serveur.
C'est l'arrangement fiable, et c'est celui que
[premiers pas](getting-started.fr.md) te fait mettre en place :

```mcfunction
/schedulerestart 5 stop
```

- **systemd** : une unité avec `Restart=always`. Le mod arrête le processus, systemd le
  redémarre, et le port comme le verrou du monde sont libres à ce moment-là puisque les
  deux étapes sont séquentielles.
- **Un script enveloppe** : `while true; do java -jar fabric-server-launch.jar nogui; sleep 5; done`.
- **Un panel d'hébergement** : active *redémarrage automatique au crash* ou *redémarrage à
  l'arrêt*, selon le nom que ton panel lui donne. Le panel devient propriétaire du
  redémarrage et le mod n'a plus qu'à s'arrêter proprement.

Dans les trois cas, le compte à rebours, les avertissements et la sauvegarde propre
viennent toujours de ce mod. Seule la relance déménage vers quelque chose qui sait
vraiment la faire.

---

← [Le compte à rebours](countdown.fr.md) · [Configuration](configuration.fr.md) →
