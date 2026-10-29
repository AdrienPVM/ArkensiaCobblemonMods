[← ScheduleRestart](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](commands.md)

# Commandes

ScheduleRestart enregistre exactement une commande, `/schedulerestart`. Aucune
sous-commande, aucun alias, aucune forme d'annulation. L'arbre entier exige le **niveau
de permission 2** — celui qu'un serveur vanilla accorde à un opérateur — déclaré sur le
littéral racine :

```java
.requires(source -> source.hasPermissionLevel(2))
```

Un joueur sous le niveau 2 ne voit pas `/schedulerestart` dans la complétion par
tabulation, et s'il la tape quand même il reçoit le message vanilla *Unknown or
incomplete command, see below for error* avec le caret rouge sous le premier caractère.
La commande n'affiche jamais de message de permission qui lui serait propre, parce que
Brigadier élague le nœud avant que le mod ne soit consulté.

La commande fonctionne depuis n'importe quelle source de niveau 2 : un joueur, la console
du serveur, un bloc de commande via `/execute`, ou une session RCON. Elle n'exige aucun
état particulier du serveur.

---

## `/schedulerestart`

```mcfunction
/schedulerestart <minutes> [mode]
```

| Argument | Type | Requis | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `minutes` | entier | oui | **1 à 60** inclus | — |
| `mode` | mot unique | non | le mot littéral `stop`, dans n'importe quelle casse ; tout autre mot est accepté et signifie *redémarrage* | absent, ce qui signifie *redémarrage* |

Aucun des deux arguments ne propose de complétion. `minutes` est un simple
`IntegerArgumentType.integer(1, 60)` et `mode` un simple `StringArgumentType.word()`,
donc le client ne suggère rien du tout. Le mot `stop` doit être tapé de mémoire — c'est
l'erreur la plus fréquente avec cette commande, et elle échoue silencieusement dans le
mauvais sens. Voir [le piège du mot-clé `stop`](#le-piège-du-mot-clé-stop) plus bas.

Le littéral racine n'a pas de `executes` propre : `/schedulerestart` sans argument est
donc une erreur de syntaxe, pas un message d'aide.

### Ce que fait la commande

La commande ne redémarre rien. Elle arme un `java.util.Timer` et rend la main
immédiatement.

1. Si une planification est déjà armée, elle est annulée — voir
   [relancer la commande annule la planification précédente](#relancer-la-commande-annule-la-planification-précédente).
2. Un nouveau `Timer` est créé. À partir de là, le compte à rebours vit sur son propre
   thread, indépendamment de la boucle de ticks du serveur.
3. Un avertissement part **immédiatement** vers tous les joueurs connectés.
4. Un avertissement est mis en file pour chaque minute restante, puis un pour chacune des
   dix dernières secondes.
5. Une tâche finale est mise en file à `minutes × 60 000` ms. Cette tâche annonce
   l'arrêt, attend une seconde, lance une JVM de remplacement (uniquement en mode
   redémarrage), puis demande au serveur de s'arrêter.
6. L'auteur de la commande reçoit une ligne de confirmation verte.

Tous les messages sont listés dans [le compte à rebours](countdown.fr.md), avec leur
texte exact, leur couleur et leur instant de déclenchement. Ce que la tâche finale fait
réellement à ton serveur — y compris la façon dont la relance échoue — est dans
[ce qui se passe à l'échéance](restart.fr.md).

Rien n'est écrit sur le disque. Aucun fichier n'est créé, aucun état n'est persisté. Une
planification n'existe que dans la mémoire de la JVM en cours : un crash ou un
redémarrage étranger l'oublie.

### Niveau de permission et effets de bord

La confirmation est envoyée avec `sendFeedback(..., true)`. Le `true` est
`broadcastToOps` : la ligne est donc répercutée vers tous les autres opérateurs sous la
forme vanilla `[Auteur: message]`, sous réserve de la règle de jeu `sendCommandFeedback`.
Si tu planifies un arrêt depuis la console, les autres opérateurs voient qui l'a fait.

Les avertissements joueurs passent par `server.getPlayerManager().broadcast(text, false)`,
le même chemin de chat qu'utilise `/say`. Le `false` signifie *chat* et non barre
d'action : les messages restent donc dans l'historique et un joueur qui était en alt-tab
peut remonter les lire.

### Exemples

Un redémarrage à dix minutes, le cas normal :

```mcfunction
/schedulerestart 10
```

L'auteur voit, en vert :

```text
✅ Redémarrage planifié dans 10 minutes
```

et tous les joueurs voient immédiatement, en aqua gras :

```text
⚠ Redémarrage du serveur dans 10 minutes !
```

Un arrêt à deux minutes, sans relance :

```mcfunction
/schedulerestart 2 stop
```

```text
✅ Arrêt planifié dans 2 minutes
```

```text
⚠ Arrêt du serveur dans 2 minutes !
```

Les avertissements passent en or au lieu d'aqua pour un arrêt : un administrateur qui
jette un œil au chat distingue les deux cas sans relire le texte.

La planification la plus courte possible :

```mcfunction
/schedulerestart 1
```

Avec `minutes` à 1, il n'y a aucun rappel à la minute : la boucle des rappels va de 1 à
`minutes - 1`, donc elle est vide. Les joueurs reçoivent l'avertissement d'ouverture, puis
cinquante secondes de silence, puis le compte à rebours des dix secondes. Une minute
suffit pour que le message soit vu et ne suffit à personne pour réagir.

⚠️ Tout le texte vu par les joueurs est écrit en dur, en français, dans
`ScheduledRestart.java`. Il n'existe aucun répertoire `assets/scheduledrestart/lang/`,
aucun `Text.translatable`, et donc aucun moyen de traduire la sortie sans modifier le
code. Un serveur anglophone affiche quand même
`⚠ Redémarrage du serveur dans 10 minutes !` à ses joueurs, quelle que soit la langue de
leur client.

### Messages d'échec

Le mod ne produit lui-même aucun message d'erreur. Tous les refus viennent de Brigadier
ou du dispatcher de commandes vanilla, donc le texte dépend de ta version de Minecraft et
non de ce mod.

| Ce que tu as tapé | Ce que tu obtiens | Cause |
|---|---|---|
| `/schedulerestart` | `Unknown or incomplete command, see below for error` | `minutes` est obligatoire ; le littéral racine n'est pas exécutable |
| `/schedulerestart 0` | `Integer must not be less than 1, found 0` | sous la borne inférieure de `integer(1, 60)` |
| `/schedulerestart 61` | `Integer must not be more than 60, found 61` | au-dessus de la borne supérieure. Impossible de planifier à plus d'une heure |
| `/schedulerestart abc` | `Expected integer` | `minutes` est analysé avant tout le reste |
| `/schedulerestart 10 stop now` | `Incorrect argument for command` | `mode` est un mot unique et n'a aucun nœud enfant : un troisième token est du texte résiduel |
| `/schedulerestart 10` sans être op | `Unknown or incomplete command, see below for error` | niveau de permission inférieur à 2 ; le nœud est invisible, pas refusé |

Un échec survient *après* le succès de la commande, et ne peut donc pas être signalé à
son auteur : si la relance ne trouve aucun jar de serveur, la tâche finale lève

```text
Impossible de trouver le jar du serveur pour le redémarrage !
```

Cette exception est attrapée, affichée sous forme de trace dans le log du serveur, puis
**ignorée — le serveur s'arrête quand même**. La commande a rendu `1` dix minutes plus tôt
et il n'y a plus personne à avertir. C'est le mode de défaillance derrière
[le serveur s'arrête et ne revient jamais](troubleshooting.fr.md#le-serveur-sarrête-et-ne-revient-jamais).

---

## Le piège du mot-clé `stop`

Le second argument est testé exactement une fois :

```java
String mode = StringArgumentType.getString(context, "mode");
return executeRestart(context, mode.equalsIgnoreCase("stop"));
```

`equalsIgnoreCase` fait que `stop`, `STOP` et `Stop` fonctionnent tous. Tout le reste est
une valeur de `mode` valide qui retombe en mode redémarrage. Aucune validation, aucun
avertissement :

| Commande | Comportement réel |
|---|---|
| `/schedulerestart 5 stop` | arrêt, aucune relance |
| `/schedulerestart 5 STOP` | arrêt, aucune relance |
| `/schedulerestart 5 shutdown` | **redémarrage**, relance tentée |
| `/schedulerestart 5 halt` | **redémarrage**, relance tentée |
| `/schedulerestart 5 restart` | redémarrage, relance tentée |
| `/schedulerestart 5 x` | redémarrage, relance tentée |

Un administrateur qui voulait laisser le serveur éteint et a tapé `shutdown` obtient un
serveur qui revient — ou, plus souvent sur une installation hébergée, un serveur qui
s'arrête et dont le panel signale ensuite un processus qu'il n'a pas démarré. Lis la ligne
de confirmation avant de partir : elle dit `Arrêt planifié` pour un arrêt et
`Redémarrage planifié` pour un redémarrage, et c'est le seul retour qui t'indique quelle
branche tu as réellement prise.

---

## Relancer la commande annule la planification précédente

La minuterie est tenue par un unique champ statique :

```java
private static Timer currentTimer = null;
```

Chaque appel annule cette minuterie avant de la remplacer. Les conséquences méritent
d'être écrites noir sur blanc :

- **Il ne peut exister qu'une seule planification.** Impossible d'empiler un
  avertissement à 30 minutes et un autre à 5 minutes.
- **L'annulation est silencieuse.** Les joueurs à qui on a dit
  `⚠ Arrêt du serveur dans 10 minutes !` ne reçoivent aucun démenti. Ils reçoivent
  simplement un nouvel avertissement d'ouverture avec un autre chiffre, et peut-être un
  autre mot, et doivent deviner lequel compte.
- **`Timer.cancel()` n'interrompt pas une tâche déjà en cours.** Si tu relances la
  commande pendant la seconde de pause de la tâche finale, l'ancien arrêt se poursuit
  malgré tout.
- **Il n'y a aucune commande d'annulation.** `/schedulerestart 60` est ce qui s'en
  approche le plus : elle remplace la planification en attente par une planification à une
  heure, ce qui te laisse le temps d'arrêter le serveur à la main, ou de le redémarrer
  pour de vrai et de laisser la minuterie oubliée mourir avec l'ancienne JVM.

Ce dernier point est le contournement pratique, et c'est un contournement, pas une
fonctionnalité. La sous-commande d'annulation manquante est listée dans
[les faiblesses connues](architecture.fr.md#faiblesses-connues).

---

## Aucun autre point d'entrée

Le mod enregistre une commande et rien d'autre. Il n'a pas de module dashboard, pas de
route REST, pas de canal WebSocket, pas de fichier de configuration pour le piloter, et
pas de hook qu'un autre mod pourrait appeler. Bien que `fabric.mod.json` liste
`cheesedash` dans `depends`, il n'y a aucun entrypoint `cheesedash` et aucune classe
CheeseDash n'est référencée dans le code — voir
[la dépendance qui ne sert à rien](architecture.fr.md#la-dépendance-qui-ne-sert-à-rien).

Si tu veux un redémarrage nocturne automatique, ce mod ne te le donnera pas : il planifie
un délai à partir de maintenant, jamais une heure murale, et il oublie tout quand la JVM
se termine. Utilise le cron de ton hébergeur ou le planificateur de ton panel pour envoyer
la commande par RCON, ou le planificateur intégré à [CheeseDash](../../CheeseDash/)
lui-même.

---

← [Premiers pas](getting-started.fr.md) · [Le compte à rebours](countdown.fr.md) →
