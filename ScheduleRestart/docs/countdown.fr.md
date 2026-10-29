[← ScheduleRestart](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](countdown.md)

# Le compte à rebours

Tout ce qu'un joueur voit vient de ce seul sous-système. C'est un `java.util.Timer` avec
`minutes + 10` tâches en file, toutes armées en une seule passe au moment où la commande
s'exécute, et dont aucune ne fait autre chose que diffuser une ligne de chat. Rien ici ne
relit l'horloge, rien ne revérifie la liste des joueurs, et rien ne peut être arrêté
autrement qu'en annulant la minuterie entière.

Cette page donne la chronologie exacte, parce qu'un administrateur qui se trompe dessus
annonce un redémarrage au mauvais moment à un serveur plein de monde.

## Il n'y a aucune heure murale

`/schedulerestart` prend un **délai en minutes à partir de l'instant où tu appuies sur
Entrée**. Il ne prend pas une heure de la journée, pas une expression cron, pas une date.
Il n'existe aucun format `03:00` à mal écrire, aucun fuseau horaire serveur à mal lire,
aucun `TimeZone` ni `ZoneId` dans le code, et aucune planification quotidienne ou
récurrente.

La borne supérieure est 60 : le plus loin que tu puisses armer quoi que ce soit est donc
une heure. La planification ne vit qu'en mémoire — si la JVM s'arrête pour une autre
raison, l'arrêt en attente disparaît.

Si tu veux un redémarrage à 04:00 heure locale, quelque chose d'extérieur à ce mod doit
décider que 04:00 est arrivé puis envoyer `/schedulerestart 5` par RCON ou par la console.

## L'horloge que la minuterie utilise réellement

`Timer.schedule(task, delay)` convertit le délai en instant absolu avec
`System.currentTimeMillis()` : le compte à rebours suit donc **l'horloge murale du
système**, pas le compteur de ticks du serveur. Deux conséquences en découlent, et elles
jouent en sens inverse.

- **Le lag du serveur n'étire pas le compte à rebours.** Un serveur qui tourne à 8 TPS
  s'arrête quand même exactement dix minutes après la commande. Les avertissements
  conservent leur espacement, parce qu'ils sont diffusés depuis le thread de la minuterie
  et que seule la passation finale touche au thread serveur.
- **Un saut d'horloge déplace le compte à rebours.** Si NTP corrige l'horloge de l'hôte de
  deux minutes vers l'avant pendant la planification, toutes les tâches restantes
  deviennent en retard et `Timer` les déclenche à la chaîne : les joueurs reçoivent une
  rafale d'avertissements, puis l'arrêt. Un saut vers l'arrière retarde tout d'autant.
  C'est une propriété de `java.util.Timer` plutôt qu'un choix, et le mod ne fait rien pour
  s'en protéger.

## La chronologie complète

Prenons `/schedulerestart 10` comme exemple, avec `T` pour l'instant où la tâche finale
s'exécute, dix minutes après la commande :

| Déclenchement | Message | Couleur |
|---|---|---|
| immédiat | `⚠ Redémarrage du serveur dans 10 minutes !` | aqua gras |
| T − 9 min | `⚠ Redémarrage dans 9 minutes !` | aqua gras |
| T − 8 min | `⚠ Redémarrage dans 8 minutes !` | aqua gras |
| … | un par minute, même texte | aqua gras |
| T − 1 min | `⚠ Redémarrage dans 1 minute !` | aqua gras |
| T − 10 s | `⏱ Redémarrage dans 10 secondes...` | jaune gras |
| T − 9 s … T − 6 s | `⏱ Redémarrage dans 9 secondes...` et ainsi de suite | jaune gras |
| T − 5 s | `⏱ Redémarrage dans 5 secondes...` | **rouge gras** |
| T − 4 s … T − 2 s | même texte, décompte | rouge gras |
| T − 1 s | `⏱ Redémarrage dans 1 seconde...` | rouge gras |
| T | `🔄 Redémarrage en cours...` | rouge gras |
| T + 1 s | rien n'est diffusé ; le serveur commence à s'arrêter | — |

Deux détails de ce tableau passent facilement inaperçus. **Le message d'ouverture est le
seul à dire `du serveur`** : tous les rappels suivants l'abandonnent, ce qui est une
incohérence cosmétique du code et non le signe de quoi que ce soit. Et **la couleur change
à cinq secondes**, du jaune au rouge, qui est le dernier indice visuel offert au joueur.

Pour un arrêt (`/schedulerestart 10 stop`), le tableau est identique sauf que
`Redémarrage` devient `Arrêt` et que l'aqua gras des rappels à la minute devient de **l'or
gras**. Le compte à rebours des dix secondes garde son jaune et son rouge dans les deux
modes : la couleur seule ne distingue donc pas un redémarrage d'un arrêt dans les
dernières secondes.

## L'arithmétique derrière

Trois boucles produisent ce tableau, et les lire est plus rapide que de faire confiance à
un résumé.

Le message d'ouverture est inconditionnel, envoyé avant que quoi que ce soit ne soit
planifié :

```java
broadcastColored(server, "⚠ " + actionCap + " du serveur dans " + minutes
    + " minute" + (minutes > 1 ? "s" : "") + " !", actionColor, true);
```

Les rappels à la minute :

```java
for (int i = 1; i < minutes; i++) {
    final int remaining = minutes - i;
    currentTimer.schedule(task, i * 60 * 1000L);
}
```

La boucle s'arrête à `minutes - 1` : le dernier rappel est donc `remaining = 1` à
`T − 60 s`. Il n'y a jamais de rappel annonçant « 0 minutes », et **avec `minutes = 1` le
corps de la boucle ne s'exécute jamais** — une planification d'une minute a un message
d'ouverture puis cinquante secondes de rien.

Le décompte seconde par seconde :

```java
for (int i = 10; i > 0; i--) {
    final int seconds = i;
    long delay = (minutes * 60 - seconds) * 1000L;
    ...
    Formatting countColor = seconds <= 5 ? Formatting.RED : Formatting.YELLOW;
}
```

Dix tâches, de `T − 10 s` à `T − 1 s`. Le délai est calculé depuis la durée *totale* :
elles sont donc toujours ancrées à la fin de la planification, jamais à son début. Le
seuil de couleur est `seconds <= 5`, ce qui place la bascule entre le message à 6 secondes
et celui à 5 secondes.

La mise au pluriel, partout, est `(n > 1 ? "s" : "")`, appliquée aux minutes comme aux
secondes. C'est du français correct : `1 minute`, `2 minutes`. Cela veut aussi dire que
`0` s'écrirait `0 minute`, mais aucun chemin de code ne peut produire 0.

## Qui reçoit les messages

```java
server.execute(() -> server.getPlayerManager().broadcast(finalText, false));
```

Chaque diffusion est enveloppée dans `server.execute(...)`, qui la met en file sur le
thread principal du serveur. Le thread de la minuterie ne touche jamais directement au
gestionnaire de joueurs — c'est la seule discipline de threading du mod, et elle est
correcte.

Les destinataires sont ceux qui sont **connectés à l'instant où la tâche s'exécute**,
résolus à ce moment et non mis en cache. Un joueur qui se connecte à `T − 30 s` ne voit
que le décompte à la seconde restant et n'a aucune idée qu'un redémarrage a été annoncé
neuf minutes plus tôt. Un joueur qui se déconnecte à `T − 5 min` n'apprend jamais que le
serveur est tombé. Il n'y a pas de message à la connexion, pas de barre de boss, pas de
barre d'action, pas d'indicateur dans la liste des joueurs : si un joueur a masqué le
chat, il ne reçoit aucun avertissement.

⚠️ Comme les diffusions sont mises en file sur le thread serveur, un serveur dont le
thread principal est bloqué — interblocage, longue pause de sauvegarde de chunks, gel
détecté par le watchdog — cesse d'afficher les avertissements alors que le thread de la
minuterie continue de compter. Ce même thread bloqué n'exécutera jamais non plus la
passation `server.stop(false)` : l'arrêt n'a donc silencieusement pas lieu. Le compte à
rebours et l'arrêt échouent tous deux du côté sûr, mais vu de l'administrateur la
planification a simplement disparu.

## Ce que tu ne peux pas changer

Chaque nombre de cette page est un littéral de `ScheduledRestart.java`. L'intervalle d'une
minute, le décompte de dix secondes, la bascule de couleur à cinq secondes, les emoji, le
texte, le gras — tout est écrit en dur, sans fichier de configuration et sans
rechargement. Quelle ligne modifier pour chacun est listé dans
[configuration](configuration.fr.md).

---

← [Commandes](commands.fr.md) · [Ce qui se passe à l'échéance](restart.fr.md) →
