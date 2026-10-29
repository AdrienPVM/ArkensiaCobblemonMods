[← CheeseDash](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](scheduler.md)

# Planificateur

`ServerScheduler.java`, 561 lignes, planifie trois choses : un redémarrage, un arrêt, ou
un arrêt pour maintenance. Il avertit les joueurs dans le chat selon un décompte fixe,
sauvegarde les mondes, puis arrête le serveur. Les planifications récurrentes survivent à
un redémarrage ; les actions ponctuelles, non.

Les routes qui le pilotent sont dans
[API REST → Planificateur](rest-api.fr.md#planificateur). Cette page traite de ce qui se
passe après que la route a répondu `200`.

> ⚠️ Lis [la section sur `RESTART`](#restart-nest-pas-sans-danger-sur-tous-les-hébergeurs)
> avant de planifier ton premier redémarrage sur un serveur de production. Sur certaines
> configurations d'hébergement, un `RESTART` arrête le serveur et rien ne le relance.

---

## Les trois types d'action

| Type | Ce qu'il fait | Différence |
|---|---|---|
| `RESTART` | sauvegarde, lance un nouveau processus serveur, arrête l'actuel | le seul type qui tente de revenir |
| `STOP` | sauvegarde, arrête | le serveur reste éteint |
| `MAINTENANCE` | sauvegarde, arrête | identique à `STOP`, plus un événement Discord `MAINTENANCE` |

`MAINTENANCE` mérite d'être énoncé clairement, parce que son nom suggère autre chose : il
ne met **pas** le serveur dans un mode maintenance, ne bloque pas les connexions, et ne
redémarre rien ensuite. `fireAction` le traite exactement comme `STOP` — la sauvegarde
tourne, les joueurs reçoivent le même message d'arrêt, `server.stop(false)` est
appelé — et le seul comportement supplémentaire est la notification Discord, envoyée avec
le `message` de l'action. Si tu veux que les joueurs voient une raison à l'indisponibilité,
c'est dans ce message qu'elle va.

---

## Actions ponctuelles

Une action ponctuelle est créée par `POST /api/v1/server/schedule`, avec un type et un
délai compris entre 5 et 86 400 secondes. Son id est `sa_` suivi d'au plus six caractères
hexadécimaux (un nombre aléatoire inférieur à `0xFFFFFF`, sans zéros de remplissage).

**Une seule peut être en attente.** `scheduleAction` commence par appeler
`cancelAction()`, donc créer une seconde action écarte la première. Ce n'est pas
silencieux : l'annulation s'exécute en entier, les joueurs reçoivent la ligne de chat
« … annulé. » et un message `schedule_cancelled` part (que le mappeur Discord transforme
en `RESTART_CANCELLED`), aussitôt suivi de l'annonce de la nouvelle action. Le dashboard,
lui, ne t'avertit pas avant que cela arrive.

Ce que fait le serveur à l'instant où l'action est créée :

1. Il démarre un ticker de décompte, un tick par seconde, sur le pool de threads du
   planificateur.
2. Il diffuse `schedule_created` sur le canal WebSocket `schedule`, ce qui déclenche à son
   tour l'événement Discord `RESTART_SCHEDULED` pour tout webhook abonné.
3. Il journalise la ligne
   `[CheeseDash] Restart scheduled in 599s by admin: Weekly maintenance` pour un délai
   de 600 secondes. Le chiffre est le temps restant, recalculé et tronqué à la seconde
   quelques instants après la création : il affiche donc en général une seconde de moins.
4. Il envoie immédiatement un avertissement en chat à tous les joueurs connectés, quel
   que soit le délai, construit à partir du même chiffre tronqué
   (`Redémarrage dans 9 minutes 59s...` pour un délai de 600 secondes). Un redémarrage
   planifié dans une heure s'annonce donc tout de suite, puis à chaque seuil inférieur.

**Les actions ponctuelles ne sont jamais écrites sur disque.** Le fichier de
configuration ne contient que les planifications récurrentes. Un serveur qui tombe
pendant un décompte revient sans rien en attente, et ni le dashboard ni le log ne
mentionnent l'action perdue.

### Le décompte

Les avertissements en chat partent à dix seuils, en secondes :

```text
1800  900  600  300  180  120  60  30  10  5
```

Soit 30 min, 15, 10, 5, 3, 2, 1 min, puis 30 s, 10 s et 5 s. Les seuils supérieurs au
délai sont sautés : un arrêt dans 60 secondes s'annonce à la création, puis à 30, 10 et
5 secondes.

Le message est construit depuis les fichiers de langue, donc les joueurs le voient dans
la leur :

```text
[CheeseDash] Redémarrage dans 5 minutes... (Weekly maintenance)
```

```text
[CheeseDash] Restart in 5 minutes... (Weekly maintenance)
```

La parenthèse finale est le `message` de l'action, du texte libre que tu as fourni, omis
s'il est vide. Il n'est pas traduit : c'est ta chaîne, affichée telle que tu l'as tapée.

La durée elle-même n'est **pas** traduite non plus. `ServerScheduler.formatTime` la
construit en Java avec des mots en dur, et ces mots penchent vers le français : sous la
minute elle produit `30 secondes`, donc un client anglais lit
`[CheeseDash] Restart in 30 secondes...`. Au-dessus de la minute, le résultat se lit
correctement dans les deux langues (`5 minutes`), et au-delà d'une heure il est neutre
(`1h30m`). Corriger cela veut dire déplacer les unités dans les fichiers de langue ; ce
n'est pas fait.

> La comparaison est `remaining == threshold`, sur un tick à la seconde calculé depuis
> l'horloge murale. Un tick qui arrive en retard — une longue pause GC, un thread
> principal gelé, un portable qui sort de veille — saute entièrement le seuil et aucun
> avertissement n'est envoyé pour celui-là. L'action se déclenche quand même à l'heure.
> En pratique tu perds un avertissement intermédiaire de temps en temps, jamais le
> redémarrage.

Sur le WebSocket, `schedule_tick` part toutes les cinq secondes, et chaque seconde sur
les dix dernières. Les payloads sont dans
[WebSocket](websocket.fr.md#schedule_tick--canal-schedule).

### Ce qui se passe au déclenchement

Dans l'ordre, tel qu'écrit dans `fireAction` :

1. Le ticker s'arrête et `activeAction` est remis à zéro.
2. `schedule_fired` part sur le canal `schedule`, ce qui correspond à l'événement Discord
   `SCHEDULED_TASK_FIRED`.
3. Sur le thread serveur : les joueurs lisent `Sauvegarde en cours...`, puis
   `server.save(false, true, true)` tourne, puis `Sauvegarde terminée.`. Le thread du
   planificateur attend ce travail sur un verrou pendant **30 secondes au maximum**. Une
   sauvegarde plus longue n'est pas interrompue — le planificateur cesse simplement
   d'attendre et continue, ce qui, sur un très gros monde, signifie que l'arrêt peut
   commencer pendant que la sauvegarde tourne encore.
4. Pour `MAINTENANCE`, l'événement Discord est envoyé.
5. Pour `RESTART`, `restartProcess()` s'exécute — avant l'arrêt du serveur, donc le
   nouveau processus démarre alors que l'ancien s'éteint encore.
6. Sur le thread serveur : `Arrêt du serveur...` dans le chat, puis
   `server.stop(false)`.

Un échec de sauvegarde est journalisé en `Save failed before Restart: …` et n'arrête pas
la séquence. Le serveur s'arrête dans tous les cas.

---

## `RESTART` n'est pas sans danger sur tous les hébergeurs

`restartProcess()` devine comment relancer le serveur. Il prend le binaire Java dans
`ProcessHandle.current()`, les arguments de la JVM dans `ManagementFactory`, puis cherche
le jar :

```java
for (int i = 0; i < processArgs.size() - 1; i++) {
    if (processArgs.get(i).equals("-jar")) { jarPath = processArgs.get(i + 1); break; }
}
if (jarPath == null) {
    // balayage du répertoire courant : fabric-server-launch*.jar, sinon server*.jar
}
if (jarPath == null) throw new Exception("Cannot find server jar for restart");
```

Il lance ensuite `<java> <args jvm> -jar <jar> nogui` depuis le répertoire de travail
courant, avec `inheritIO()`.

Ça marche quand le serveur a été lancé par
`java -Xmx8G -jar fabric-server-launch.jar nogui`. Ça échoue dans plusieurs
configurations courantes :

- **Un script d'enrobage ou un gestionnaire de processus.** Pterodactyl, systemd avec un
  script shell, un point d'entrée Docker, tout panneau qui lance la JVM avec `-cp` et une
  classe principale plutôt qu'avec `-jar`, ou depuis un autre répertoire de travail. Le
  balayage des arguments ne trouve pas de `-jar`, celui du répertoire ne trouve aucun
  fichier correspondant, et la méthode lève une exception.
- **Un jar dont le nom n'est pas reconnu.** Le repli ne reconnaît que
  `fabric-server-launch*.jar` ou `server*.jar`.
- **Un lanceur qui passe les options JVM par un fichier ou une variable
  d'environnement.** Le nouveau processus n'hérite que de ce que rapporte
  `getInputArguments()`, donc un redémarrage peut revenir avec une taille de tas
  différente de celle que tu as configurée.

> ⚠️ **L'échec survient alors que l'arrêt est déjà engagé.** Regarde l'ordre dans
> `fireAction` : l'exception de `restartProcess()` est attrapée, journalisée en
> `Failed to spawn restart process: …`, et l'exécution continue droit vers
> `server.stop(false)`. Le serveur s'éteint et rien ne le rallume. Tes joueurs voient un
> redémarrage planifié et le serveur ne revient jamais.
>
> Si ton hébergeur relance automatiquement le processus à sa sortie, tu obtiens le risque
> inverse : deux serveurs partageant brièvement un même répertoire de monde, parce que le
> processus fils de CheeseDash démarre avant que le père ait relâché ses verrous de
> fichiers.

**Teste-le sans risque, une fois, avant de lui faire confiance.** Sur un serveur où
personne ne joue :

1. `POST /api/v1/server/schedule` avec `{"type":"RESTART","delaySeconds":30}`.
2. Surveille le log. Une configuration qui fonctionne affiche
   `[CheeseDash] Restart process spawned: /usr/bin/java -Xmx8G -jar fabric-server-launch.jar nogui`.
   Lis cette ligne : elle donne la commande exacte, donc tu vois si la taille du tas et
   le jar sont les bons.
3. Si tu lis plutôt `Failed to spawn restart process:` ou
   `Cannot find server jar for restart`, `RESTART` n'est pas utilisable sur cet
   hébergeur.

Quand il ne l'est pas, utilise `STOP` avec le redémarrage automatique de ton hébergeur,
ou mets le redémarrage dans le planificateur de l'hébergeur et sers-toi de CheeseDash
uniquement pour l'annonce du décompte : une action `MAINTENANCE` calée une minute avant
le redémarrage externe donne l'avertissement aux joueurs sans que CheeseDash touche au
processus.

---

## Planifications récurrentes

Une planification récurrente est une heure de la journée plus un ensemble de jours de la
semaine, persistée dans `config/cheesedash_schedules.json`. Son id est `rec_` suivi d'au
plus six caractères hexadécimaux.

```json
{
  "recurring": [
    {
      "id": "rec_9b21ef",
      "type": "RESTART",
      "hour": 5,
      "minute": 0,
      "daysOfWeek": [1, 2, 3, 4, 5, 6, 7],
      "message": "Nightly restart",
      "enabled": true
    }
  ]
}
```

| Champ | Type | Notes |
|---|---|---|
| `id` | chaîne | généré ; régénéré s'il est absent du fichier |
| `type` | chaîne | `RESTART`, `STOP` ou `MAINTENANCE`. Une valeur inconnue rend l'entrée entière illisible |
| `hour` | entier | 0–23, heure locale du serveur |
| `minute` | entier | 0–59 |
| `daysOfWeek` | tableau d'entiers | ISO : 1 = lundi … 7 = dimanche. Les sept jours si la planification est créée sans ce champ |
| `message` | chaîne | affiché aux joueurs dans le décompte, `""` par défaut |
| `enabled` | booléen | `true` quand la clé est absente |

Le fichier est écrit à chaque création, modification, suppression et bascule, sur un
thread démon éphémère. Une entrée qui ne se parse pas est sautée avec
`[CheeseDash] Skipping invalid recurring schedule: …` et les autres se chargent quand
même : une coquille introduite à la main ne te coûte pas le reste du fichier.

### Comment une planification récurrente se déclenche

Un vérificateur tourne **toutes les 30 secondes**, à partir de 30 secondes après
`SERVER_STARTED`, et c'est la seule horloge en jeu. Pour chaque planification activée
dont l'ensemble de jours contient aujourd'hui, il compare l'heure et la minute courantes
aux siennes :

```java
if (currentHour == schedule.hour && currentMinute == schedule.minute) {
    scheduleAction(schedule.type, 300, schedule.message, "scheduler");
    schedule.enabled = false;
    executor.schedule(() -> { schedule.enabled = true; }, 90, TimeUnit.SECONDS);
    break;
}
```

Quatre conséquences à bien comprendre :

**Une planification récurrente ne se déclenche pas à son heure — elle démarre un décompte
de 5 minutes à son heure.** Un `RESTART` réglé sur 05:00 s'annonce à 05:00 et le serveur
tombe réellement à 05:05. Règle l'heure cinq minutes avant l'indisponibilité souhaitée.

**Le `createdBy` de l'action produite vaut `scheduler`,** ce qui permet de distinguer un
redémarrage automatique d'un redémarrage déclenché par un admin en lisant le journal
d'audit ou le salon Discord.

**La planification se désactive elle-même pendant 90 secondes après s'être déclenchée,**
pour que le vérificateur, qui passe deux fois par minute, ne la déclenche pas deux fois
dans la même minute. Si le serveur s'arrête dans cette fenêtre — ce qui, pour un
redémarrage à 5 minutes, n'arrive pas, mais qui est possible avec un montage à délai
court — la planification n'est persistée désactivée que si autre chose sauvegarde le
fichier entre-temps ; la réactivation se fait en mémoire, sur une minuterie. Bon à savoir
si tu trouves un jour une planification à `enabled: false` sans que personne y ait
touché.

**Rien ne se déclenche tant qu'une action ponctuelle est en attente.**
`checkRecurring` sort immédiatement si `activeAction != null`, donc un décompte de
redémarrage manuel qui enjambe 05:00 fait rater sa fenêtre à la planification de 05:00.
Elle ne se rattrape pas : elle attend demain.

Et puisque la comparaison porte sur la seule heure et la seule minute, un serveur éteint
à 05:00 n'exécute jamais le redémarrage du jour.

### Les numéros de jours ne sont pas validés

`POST /api/v1/server/schedule/recurring` accepte n'importe quels entiers dans
`daysOfWeek`. `[0]` et `[9]` sont stockés et ne correspondront jamais à
`DayOfWeek.getValue()` : la planification existe dans le dashboard, s'affiche comme
activée, et ne se déclenche jamais. Même chose pour `hour` et `minute` sur la route
`PUT`, qui — contrairement à la route de création — ne vérifie aucune plage : un `hour` à
`99` est persisté et silencieusement mort. Si une planification récurrente ne se
déclenche jamais, lis le JSON brut avant toute chose.

---

## Threads et arrêt

Tout ce que le planificateur fait de lui-même tourne sur un
`ScheduledExecutorService` à deux threads, démons, nommés `CheeseDash-Scheduler`. Chaque
contact avec le jeu — les messages en chat, la sauvegarde du monde, l'arrêt — est confié
au thread serveur par `server.execute(...)`. Le verrou de 30 secondes sur la sauvegarde
est le seul endroit où le planificateur bloque, et il bloque son propre thread, jamais
celui du jeu.

Les écritures disque partent sur des threads démons éphémères nommés
`CheeseDash-ScheduleSave`. Une JVM qui meurt dans la même milliseconde qu'une sauvegarde
peut perdre cette écriture ; avec des planifications récurrentes qui changent quelques
fois par an, c'est un compromis assumé.

Sur `SERVER_STOPPING`, `shutdown()` annule le décompte et le vérificateur récurrent, puis
appelle `executor.shutdownNow()`. Rien n'est vidé en sortie, ce qui est l'autre raison
pour laquelle une action ponctuelle en attente ne survit pas : il n'existe aucun code
pour la sauvegarder, et aucun pour la restaurer.

---

← [Interface web](web-ui.fr.md) · [Webhooks Discord](discord-webhooks.fr.md) →
