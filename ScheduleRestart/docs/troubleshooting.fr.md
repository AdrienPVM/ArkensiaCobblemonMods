[← ScheduleRestart](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](troubleshooting.md)

# Dépannage

## Le jar produit est minuscule et le mod ne charge pas

`build/libs/ScheduleRestart-1.0.0+1.21.1.jar` pèse quelques centaines d'octets — 261 est le
chiffre classique —, la compilation a annoncé un succès, et le serveur démarre sans jamais
mentionner `scheduledrestart`.

**Cause.** Fabric Loom 1.8 ne fonctionne pas sous Gradle 9. Sa tâche `remapJar` se termine
sans erreur et écrit un jar qui ne contient aucune classe et aucun `fabric.mod.json`.
Jusqu'au 29 octobre 2026, le `gradlew` de ce projet était un bouchon qui déléguait au
`gradle` présent sur ton `PATH` au lieu de télécharger la distribution fixée : quiconque
avait Gradle 9 installé globalement compilait un jar vide sans le moindre avertissement. Un
clone antérieur à cette date contient encore ce bouchon.

**Remède.** Vérifie quel Gradle s'exécute réellement :

```bash
cd ScheduleRestart
./gradlew --version
```

Il doit afficher `Gradle 8.10`, la version fixée dans
`gradle/wrapper/gradle-wrapper.properties`. S'il affiche 9.x ou autre chose, ton wrapper est
l'ancien bouchon. Récupère la version actuelle du dépôt, ou restaure un vrai wrapper :

```bash
gradle wrapper --gradle-version 8.10
./gradlew --version          # confirme 8.10
./gradlew clean build
ls -l build/libs/
```

Un jar correct pèse quelques kilooctets et contient `fabric.mod.json` ainsi que
`com/schedulerestart/ScheduledRestart.class` :

```bash
unzip -l build/libs/ScheduleRestart-1.0.0+1.21.1.jar
```

Si ce listing montre ces deux entrées plus un manifeste, tu as un vrai jar.

---

## Le serveur refuse de démarrer : `requires any version of mod 'cheesedash'`

```text
Mod 'Scheduled Restart' (scheduledrestart) 1.0.0+1.21.1 requires any version of mod
'cheesedash', which is missing!
```

**Cause.** `fabric.mod.json` liste `cheesedash` dans `depends`. La dépendance est bien
réelle pour le loader, même si le mod n'utilise pas une seule classe CheeseDash.

**Remède.** Installe [CheeseDash](../../CheeseDash/) à côté, ou retire ce mod. Il n'y a pas
de troisième option, sauf à modifier `fabric.mod.json` et recompiler — ce qui est une chose
légitime à faire si tu veux le compte à rebours sans le dashboard, et qui est discuté dans
[la dépendance qui ne sert à rien](architecture.fr.md#la-dépendance-qui-ne-sert-à-rien).

---

## Le serveur crashe au démarrage sur `NoClassDefFoundError: …CommandRegistrationCallback`

```text
java.lang.NoClassDefFoundError: net/fabricmc/fabric/api/command/v2/CommandRegistrationCallback
	at com.schedulerestart.ScheduledRestart.onInitialize(ScheduledRestart.java:27)
```

**Cause.** Fabric API est absent. Le mod l'utilise et ne le déclare pas dans
`fabric.mod.json` : le loader laisse donc le mod démarrer et la classe ne manque qu'au
moment où `onInitialize` s'exécute.

**Remède.** Dépose un `fabric-api-*.jar` compilé pour 1.21.1 dans `mods/`. Le mod a été
compilé contre `0.107.0+1.21.1` ; n'importe quelle build 1.21.1 fonctionne.

---

## `/schedulerestart` n'apparaît pas dans la complétion

**Cause.** Presque toujours une question de permission, pas d'installation. L'arbre entier
est gardé par `hasPermissionLevel(2)`, et Brigadier masque les nœuds qu'une source ne peut
pas utiliser : un non-opérateur ne voit donc rien du tout — ni commande, ni erreur de
permission.

**Remède.** Vérifie dans cet ordre :

1. Lance la même commande depuis la **console du serveur**, qui est toujours niveau 4. Si
   elle fonctionne là, le mod va bien et c'est ton compte qui pose problème.
2. Confirme que tu es op : `/op <nom>` depuis la console, puis reconnecte-toi ou lance une
   commande — les changements d'op prennent effet à la prochaine synchronisation de l'arbre
   de commandes, qui est automatique.
3. Seulement si la console ignore aussi la commande, vérifie que le mod est chargé :
   `grep scheduledrestart logs/latest.log`.

Un mod de permissions qui accorde un équivalent du niveau 2 via son propre système de nœuds
ne satisfait **pas** automatiquement `hasPermissionLevel(2)`, sauf s'il patche la source de
commande. Si ton mod de permissions annonce une émulation des niveaux vanilla, active-la.

---

## `Unknown or incomplete command` quand je tape `/schedulerestart` seul

**Cause.** Ce n'est pas une erreur du mod. Le littéral racine n'a pas de branche
`executes` : `minutes` est donc obligatoire et une invocation nue ne peut pas s'exécuter.

**Remède.** Donne-lui un délai : `/schedulerestart 10`. Les valeurs acceptées vont de 1 à
60 inclus ; `0` et `61` sont refusés par Brigadier avec
`Integer must not be less than 1` et `Integer must not be more than 60`.

---

## J'ai demandé un arrêt et le serveur est revenu

**Cause.** Le second argument n'était pas littéralement le mot `stop`. Le test est
`mode.equalsIgnoreCase("stop")` sans aucune validation de l'alternative :
`/schedulerestart 5 shutdown`, `… halt`, `… off` et tout autre mot sont acceptés et
signifient *redémarrage*. Aucun des arguments ne propose de complétion, donc rien ne te
prévient.

**Remède.** Tape `stop` et rien d'autre. Lis la confirmation verte avant de partir : elle
dit `Arrêt planifié` pour un arrêt et `Redémarrage planifié` pour un redémarrage, et c'est
le seul retour qui distingue les deux. Le tableau complet de ce que fait chaque mot est
dans [le piège du mot-clé `stop`](commands.fr.md#le-piège-du-mot-clé-stop).

---

## Le serveur s'arrête et ne revient jamais

Le compte à rebours s'est déroulé, `🔄 Redémarrage en cours...` est apparu, le serveur
s'est arrêté, et rien n'a démarré.

**Cause.** L'une de quatre, et le log te dit laquelle.

Cherche la seule ligne de log du mod :

```bash
grep -n "ScheduledRestart" logs/latest.log
```

**Aucune ligne `Relancement :`, et une trace terminée par
`Impossible de trouver le jar du serveur pour le redémarrage !`** — la relance n'a pas pu
identifier de jar de serveur. Le serveur n'a pas été lancé avec `-jar`, et le repli par
répertoire de travail n'a trouvé ni `fabric-server-launch*.jar` ni `server*.jar`.
L'exception est attrapée puis jetée, donc l'arrêt a eu lieu quand même.

**Une ligne `Relancement :` qui nomme le mauvais jar** — le repli a deviné. S'il nomme
`server.jar`, c'est le paquet vanilla et le processus de remplacement est un serveur
vanilla.

**Une ligne `Relancement :` qui a l'air correcte, mais l'enfant est mort** — très
probablement la nouvelle JVM a perdu la course contre l'ancienne et a échoué sur
*Failed to bind to port* ou sur le `session.lock` du monde. L'enfant est lancé *avant*
l'arrêt de l'ancien serveur, donc les deux existent un instant. Sa propre sortie allait
vers la sortie standard du parent via `inheritIO()` : sur un panel, elle a donc pu partir
nulle part et ne te laisser aucune trace.

**Tu es sur un panel d'hébergement** — le panel a récolté le groupe de processus quand son
processus géré s'est terminé, ou il marque le serveur hors ligne et refuse d'adopter un
processus qu'il n'a pas démarré.

**Remède.** Cesse d'utiliser la relance interne et place un superviseur devant. Emploie
`/schedulerestart <minutes> stop` pour que le mod ne fasse plus que l'arrêt propre, et
laisse systemd (`Restart=always`), un script `while true`, ou l'option de redémarrage
automatique de ton panel ramener le serveur. Les unités et scripts exacts sont dans
[premiers pas, étape 6](getting-started.fr.md#6-faire-en-sorte-quun-redémarrage-redémarre-vraiment),
et la mécanique de chaque échec est dans
[pourquoi un panel d'hébergement casse tout](restart.fr.md#pourquoi-un-panel-dhébergement-casse-tout).

---

## Le serveur est revenu sans aucun mod

**Cause.** Le repli de la relance a trouvé `server.jar` — le paquet du serveur vanilla que
Fabric garde à côté de son lanceur — au lieu de `fabric-server-launch.jar`, et a démarré un
serveur vanilla nu. Les deux branches du balayage de répertoire ne sont pas symétriques :
celle de `fabric-server-launch*` sort de la boucle, celle de `server*` non, donc un
répertoire contenant plusieurs fichiers `server*.jar` garde celui qui arrive en dernier dans
l'ordre de `listFiles()`.

**Remède.** Lance le serveur comme `java … -jar fabric-server-launch.jar nogui` pour que le
balayage de `-jar` trouve le bon jar et que le repli par répertoire ne soit jamais atteint.
Si tu ne peux pas changer la commande de lancement, écris le nom du jar en dur — le patch
est dans
[faire en sorte que la relance trouve ton jar](configuration.fr.md#faire-en-sorte-que-la-relance-trouve-ton-jar).

⚠️ Avant de redémarrer à nouveau, vérifie le monde. Un serveur vanilla qui charge un monde
moddé supprime les blocs et items qu'il ne connaît pas. Restaure une sauvegarde plutôt que
de le laisser enregistrer.

---

## J'ai changé une valeur et rien ne s'est passé

**Cause.** Il n'y a aucun fichier de configuration. Rien dans `config/` n'appartient à ce
mod : il n'y a donc rien à modifier, rien à recharger, et aucun chemin de `/reload` qui
prendrait un changement en compte. Chaque minuterie, couleur et message est un littéral de
`ScheduledRestart.java`.

**Remède.** Modifie le code, recompile, remplace le jar, redémarre le serveur.
[Configuration](configuration.fr.md) associe chaque comportement à son numéro de ligne.
L'arbre de commandes et tous les messages sont figés au chargement de la classe : un serveur
en cours d'exécution garde l'ancien comportement quoi que tu fasses aux fichiers sur le
disque.

---

## Le dashboard n'a pas de page ScheduleRestart

**Cause.** Le mod n'enregistre aucun module CheeseDash. Il ne déclare aucun entrypoint
`"cheesedash"` dans `fabric.mod.json`, donc `FabricLoader.getEntrypoints("cheesedash", …)`
ne le trouve jamais, et CheeseDash ne construit sa barre latérale qu'à partir des modules
enregistrés. Il n'y a pas non plus de route REST sous `/api/v1/` ni de canal WebSocket pour
lui.

**Remède.** Aucun. Ce n'est pas une erreur de configuration : la page n'existe pas.
L'entrée `cheesedash` dans `depends` est la raison pour laquelle on en attend une — voir
[la dépendance qui ne sert à rien](architecture.fr.md#la-dépendance-qui-ne-sert-à-rien).
Pilote le mod avec la commande, ou utilise le planificateur propre à CheeseDash.

---

## Des joueurs disent n'avoir vu aucun avertissement

**Cause.** Trois possibilités, toutes comportementales plutôt que cassées.

Les destinataires sont résolus **au moment où chaque tâche se déclenche**, pas au moment de
la commande. Un joueur connecté à `T − 30 s` n'a vu que les dernières secondes du décompte.
Un joueur parti avant la fin a vu ce qui avait été envoyé jusque-là et rien après.

Tout est envoyé dans le **chat** — `broadcast(text, false)`. Il n'y a pas de barre de boss,
pas de barre d'action, pas de titre. Un joueur qui a masqué le chat ne reçoit absolument
aucun avertissement.

Avec `/schedulerestart 1`, il n'y a **aucun rappel à la minute** : la boucle va de 1 à
`minutes - 1`, donc elle est vide, et il y a le message d'ouverture, puis cinquante secondes
de silence, puis le décompte des dix secondes.

**Remède.** Utilise un délai plus long pour que les rappels à la minute aient le temps de se
répéter, et prévient les joueurs par un autre canal pour tout ce qui est perturbant. La
chronologie exacte des messages est dans
[le compte à rebours](countdown.fr.md#la-chronologie-complète).

---

## Le compte à rebours s'est arrêté en route et le serveur est resté debout

Le chat s'est tu sur, disons, `⏱ Redémarrage dans 4 secondes...` et l'arrêt n'a jamais eu
lieu.

**Cause.** Le thread principal du serveur s'est bloqué. Chaque diffusion et le
`server.stop(false)` final sont mis en file avec `server.execute(...)` : ils ne s'exécutent
donc que lorsque le thread serveur dépile sa file. Un interblocage, un gel détecté par le
watchdog ou une très longue pause de sauvegarde de chunks fait que la minuterie continue de
compter sur son propre thread alors que rien de ce qu'elle met en file ne s'exécute.

**Remède.** C'est le symptôme d'un problème de thread serveur, pas de ce mod. Cherche dans
`logs/latest.log` les lignes *Can't keep up* ou *A single server tick took* du watchdog
Minecraft, ou un vidage de threads du watchdog, et attaque-toi à ça. Dès que le thread
serveur récupère, il dépile toute la file d'un coup : attends-toi donc à une rafale de
messages de décompte en retard, immédiatement suivie de l'arrêt.

---

## Deux comptes à rebours différents tournent en même temps

Des joueurs signalent voir à la fois un avertissement à 10 minutes et un à 30 minutes.

**Cause.** Ils ne tournent pas tous les deux. Le mod tient un seul `static Timer`, et chaque
commande l'annule avant de le remplacer : seule la planification la plus récente est vivante.
Ce que les joueurs ont vu, c'est l'ancienne annonce encore présente dans leur historique de
chat plus la nouvelle, **sans aucun démenti de l'ancienne** — l'annulation est totalement
silencieuse.

**Remède.** Annonce la correction toi-même avec `/say`. Le comportement est documenté dans
[relancer la commande annule la planification précédente](commands.fr.md#relancer-la-commande-annule-la-planification-précédente) ;
l'absence d'avis d'annulation est dans
[les faiblesses connues](architecture.fr.md#faiblesses-connues).

Un vrai cas limite : `Timer.cancel()` n'interrompt pas une tâche déjà démarrée. Si tu
relances la commande pendant la seconde de pause de la tâche finale, l'ancien arrêt se
termine quand même.

---

## Mes joueurs lisent du français et je n'en veux pas

**Cause.** Toutes les chaînes vues par les joueurs sont des littéraux français écrits en
dur. Il n'y a pas de répertoire `assets/scheduledrestart/lang/`, aucun appel à
`Text.translatable`, et donc aucun pack de ressources ni réglage de langue client qui
puisse changer la sortie.

**Remède.** Modifie les littéraux et recompile. Les six lignes concernées et un
remplacement anglais sont dans
[traduire les messages](configuration.fr.md#traduire-les-messages).

⚠️ Les clés `cheesedash.scheduler.*` du dépôt appartiennent au planificateur propre à
CheeseDash, pas à ce mod. Les traduire ne change rien ici.

---

## Rédiger un rapport de bug utile

Ouvre-le sur
[GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues), et inclus :

1. **La commande exacte que tu as tapée**, second argument compris s'il y en avait un. La
   différence entre `stop` et `shutdown` est la différence entre deux bugs.
2. **Comment le serveur est lancé**, mot pour mot — la ligne de commande `java` complète,
   ou la commande de démarrage du panel, ou le `ExecStart` systemd. Presque tous les
   rapports de relance se ramènent à cette ligne.
3. **`logs/latest.log`**, et plus précisément :
   - la liste des mods Fabric en haut, pour prouver la version qui a été chargée
   - toutes les lignes contenant `ScheduledRestart`, c'est-à-dire la ligne `Relancement :`
     et toute trace d'exception de la relance
   - les 200 dernières lignes avant l'arrêt, qui contiennent la séquence d'arrêt vanilla
4. **S'il s'agissait d'un redémarrage ou d'un arrêt**, et si le serveur est revenu.
5. **La taille du jar**, si la plainte est que le mod ne s'est pas chargé. Un jar sous 1 ko
   est le bug du `remapJar` vide et non un bug du mod.
6. **Ta version de Gradle** (`./gradlew --version`) si tu l'as compilé toi-même.

Les questions qui ne sont pas des bugs vont sur le
[Discord Arkensia](https://discord.gg/fYmPwMBjdB).

---

← [Architecture](architecture.fr.md) · [ScheduleRestart](../README.fr.md) →
