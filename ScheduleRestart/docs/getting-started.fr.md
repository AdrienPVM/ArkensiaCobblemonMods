[← ScheduleRestart](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](getting-started.md)

# Premiers pas

À la fin de ce parcours, tu auras observé un arrêt complet à deux minutes depuis
l'intérieur — chaque avertissement, dans l'ordre, avec les bonnes couleurs — puis mis en
place un superviseur pour que `/schedulerestart` ramène vraiment le serveur au lieu de
simplement l'espérer. Deux minutes est la planification la plus courte qui exerce toutes
les branches du compte à rebours, et c'est pourquoi on l'utilise plutôt qu'une minute.

Fais ça sur un serveur de test. L'étape 5 arrête le serveur volontairement.

## 1. Mettre les trois jars dans `mods/`

```bash
cd ScheduleRestart
./gradlew --version          # doit afficher Gradle 8.10
./gradlew build
cp build/libs/ScheduleRestart-1.0.0+1.21.1.jar /chemin/vers/serveur-test/mods/
```

Le dossier `mods/` a aussi besoin de `fabric-api-*.jar` et de `cheesedash-*.jar`. Le jar
CheeseDash est exigé par `fabric.mod.json` même si ce mod ne l'appelle jamais : le serveur
refuse donc de démarrer sans lui.

Si le jar que tu viens de compiler ne pèse que quelques centaines d'octets, arrête-toi ici
et lis
[le jar produit est minuscule et le mod ne charge pas](troubleshooting.fr.md#le-jar-produit-est-minuscule-et-le-mod-ne-charge-pas).

## 2. Démarrer le serveur et confirmer que le mod est chargé

```bash
java -Xmx4G -jar fabric-server-launch.jar nogui
```

Utilise exactement cette forme de ligne de commande — `java … -jar <lanceur>.jar nogui` —
parce que c'est la seule mise en place que la relance de l'étape 6 sait reconstruire.
Ensuite :

```bash
grep -n "scheduledrestart" logs/latest.log
```

```text
	- scheduledrestart 1.0.0+1.21.1
```

Le mod n'affiche aucune bannière, donc cette ligne de la liste des mods de Fabric est la
preuve. Rien n'apparaît dans `config/` — il n'y a aucun fichier de configuration à créer.

## 3. Confirmer que la commande est là

Depuis la console du serveur, tape :

```mcfunction
schedulerestart
```

```text
Unknown or incomplete command, see below for error
```

Cette erreur est le cas de succès : la commande existe mais `minutes` est obligatoire, donc
une invocation nue est une erreur de syntaxe. Si la console dit plutôt que la commande est
inconnue dès le premier caractère, le mod ne s'est pas enregistré — retourne à l'étape 2.

En jeu, connecte-toi comme opérateur et tape `/schedule`. `schedulerestart` doit apparaître
dans la complétion. Un joueur sous le niveau de permission 2 ne voit rien, ce qui est le
comportement voulu et non un bug.

## 4. Se connecter avec un second compte, ou au moins être en jeu

Tout l'intérêt du mod est ce que voient les joueurs. La console journalise bien chaque
avertissement, mais en texte brut, sans les couleurs ni le gras qui rendent le décompte
lisible, et la confirmation verte ne part que vers l'auteur et les autres opérateurs. Sois
connecté comme joueur pour l'étape suivante, et lance la commande depuis la console ou
depuis ce même joueur.

## 5. Planifier un arrêt à deux minutes et l'observer

```mcfunction
/schedulerestart 2 stop
```

Le mot-clé `stop` compte. Il signifie *arrête et ne relance pas*, ce que tu veux pendant
que tu découvres encore la chronologie. Tape-le exactement : tout autre mot signifie
silencieusement « redémarre », et `/schedulerestart 2 shutdown` relancerait le serveur.

L'auteur voit immédiatement, en vert :

```text
✅ Arrêt planifié dans 2 minutes
```

et dans le chat, tous les joueurs voient, en or gras :

```text
⚠ Arrêt du serveur dans 2 minutes !
```

Regarde maintenant l'horloge. Voilà ce qui doit arriver, et quand :

| Instant | Ce que tu vois |
|---|---|
| T + 1:00 | `⚠ Arrêt dans 1 minute !` — or gras |
| T + 1:50 | `⏱ Arrêt dans 10 secondes...` — jaune gras |
| T + 1:51 → 1:54 | `9`, `8`, `7`, `6` secondes — encore jaune |
| T + 1:55 | `⏱ Arrêt dans 5 secondes...` — passe au **rouge gras** |
| T + 1:56 → 1:59 | `4`, `3`, `2`, `1` seconde — rouge |
| T + 2:00 | `🔄 Arrêt en cours...` — rouge gras |
| T + 2:01 | tu es déconnecté ; le serveur sauvegarde et se termine |

Trois choses à remarquer pendant que ça tourne. Le message d'ouverture est le seul à dire
`du serveur`. La couleur passe du jaune au rouge exactement à cinq secondes. Et les
messages sont en français, sans aucun fichier de langue dans le mod — c'est une limite, pas
un réglage ; [configuration](configuration.fr.md#traduire-les-messages) indique quelles
lignes modifier.

La chronologie complète, avec son arithmétique et ce qui se passe avec d'autres délais, est
dans [le compte à rebours](countdown.fr.md).

## 6. Faire en sorte qu'un redémarrage redémarre vraiment

Le serveur est maintenant éteint. Redémarre-le et essaie l'autre mode :

```mcfunction
/schedulerestart 2
```

Sans second argument, c'est un **redémarrage** : à l'échéance, le mod construit une ligne
de commande `java … -jar … nogui` à partir du processus en cours et la démarre avec
`ProcessBuilder` avant d'arrêter l'ancien serveur. Surveille le log :

```text
[ScheduledRestart] Relancement : /usr/bin/java -Xmx4G -jar fabric-server-launch.jar nogui
```

Si tu as lancé le serveur exactement comme à l'étape 2, ça fonctionne et le serveur revient
de lui-même. Si tu l'as lancé autrement — un panel, un classpath, un wrapper qui passe des
arguments supplémentaires — plusieurs choses peuvent mal tourner, et
[pourquoi un panel d'hébergement casse tout](restart.fr.md#pourquoi-un-panel-dhébergement-casse-tout)
les énumère.

**Sur toute installation autre qu'un `java -jar` nu, arrête de compter sur la relance.**
Utilise un superviseur et n'emploie que le mode `stop`. Une unité systemd est la version
directe :

```ini
[Unit]
Description=Minecraft Fabric server
After=network.target

[Service]
WorkingDirectory=/chemin/vers/serveur
ExecStart=/usr/bin/java -Xmx8G -jar fabric-server-launch.jar nogui
Restart=always
RestartSec=10
User=minecraft

[Install]
WantedBy=multi-user.target
```

Un script enveloppe fait le même travail avec moins de cérémonie :

```bash
while true; do
  java -Xmx8G -jar fabric-server-launch.jar nogui
  echo "serveur terminé, redémarrage dans 5 s"
  sleep 5
done
```

Avec l'un ou l'autre en place, `/schedulerestart 5 stop` te donne les avertissements, le
compte à rebours et une sauvegarde vanilla propre, et le superviseur gère la partie que le
mod fait mal. Le port et le verrou du monde sont libres au moment où le nouveau processus
démarre, ce qui est précisément ce que la relance interne du mod ne peut pas garantir.

## 7. Savoir faire machine arrière

Il n'existe aucune commande d'annulation. Si tu planifies quelque chose que tu regrettes :

```mcfunction
/schedulerestart 60
```

Une nouvelle planification remplace l'ancienne, donc ceci repousse l'événement d'une heure
— et aucun démenti n'est annoncé, donc tes joueurs verront un second avertissement
déroutant. Ensuite, arrête le serveur à la main, ou redémarre-le pour de vrai, et la
minuterie oubliée mourra avec la JVM.

C'est tout le contournement, et c'est pourquoi l'absence de `cancel` est en tête des
[faiblesses connues](architecture.fr.md#faiblesses-connues).

## Quoi lire ensuite

- [Commandes](commands.fr.md) pour le tableau des arguments, le niveau de permission et
  tous les messages d'échec.
- [Ce qui se passe à l'échéance](restart.fr.md) avant d'utiliser le mode redémarrage sur un
  serveur de production.
- [Dépannage](troubleshooting.fr.md) quand une étape ci-dessus n'a pas fait ce que cette
  page annonçait.

---

← [Installation](installation.fr.md) · [Commandes](commands.fr.md) →
