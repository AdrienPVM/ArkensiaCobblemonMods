[← CheeseFixQuests](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](commands.md)

# Commandes

CheeseFixQuests expose une seule commande, `/cheesefixquests`, et quatre sous-commandes. Ce
sont des outils d'administration autour de la migration des données — la redirection vers
la progression individuelle, elle, n'a besoin d'aucune commande et agit dès que le mod est
chargé.

L'arbre entier est déclaré avec `requires(src -> src.hasPermissionLevel(4))` : il exige donc
le **niveau de permission 4**, celui d'un propriétaire de serveur ou de la console. Les
opérateurs de niveau 2 ne suffisent pas. Un joueur en dessous du niveau 4 ne voit pas
`cheesefixquests` dans la complétion par tabulation et reçoit l'erreur vanilla *Unknown or
incomplete command* s'il la tape quand même.

Les quatre sous-commandes sont déclarées dans `CheeseFixQuestsCommand.register`. Il n'y a
aucun alias et aucune complétion d'argument — le seul argument de l'arbre est un mot brut,
et Brigadier n'a rien à proposer pour lui.

> ⚠️ Tous les messages de ces commandes sont du français codé en dur, avec des codes de
> couleur `§` historiques. Le mod n'a pas de fichier de langue, donc un client anglais voit
> aussi du français. Les blocs de sortie ci-dessous sont ce que le serveur envoie
> réellement.

---

## `/cheesefixquests status`

```mcfunction
/cheesefixquests status
```

Aucun argument. Affiche les trois drapeaux qui déterminent si le mod fait quelque chose,
lus directement dans la configuration en mémoire.

```text
§e[CheeseFixQuests] forceIndividual=true migrationDone=true debug=false
```

| Champ | Clé de config | Signification |
|---|---|---|
| `forceIndividual` | `forceIndividualProgress` | `false` signifie que toutes les redirections sont inertes et que FTB Quests se comporte normalement |
| `migrationDone` | `migrationDone` | `false` signifie que la migration automatique n'a pas encore eu lieu et se déclenchera à la prochaine connexion |
| `debug` | `debugLogging` | journalisation INFO à chaque redirection |

Le retour est envoyé avec `broadcastToOps = false` : seul l'appelant le voit. La commande ne
peut pas échouer et renvoie `1`.

C'est la première chose à lancer quand le mod semble inactif. Si `forceIndividual=false`,
rien du reste de cette documentation ne s'applique — voir
[configuration](configuration.fr.md#forceindividualprogress).

---

## `/cheesefixquests migrate`

```mcfunction
/cheesefixquests migrate
```

Aucun argument. Recopie la progression de quêtes existante depuis le stockage par équipe
vers le stockage propre à chaque joueur, puis marque la migration comme faite.

**Dans la plupart des cas tu n'auras jamais à taper cette commande.**
`ServerQuestFileMixin` lance déjà la même migration automatiquement, en tête de
`ServerQuestFile.playerLoggedIn`, la première fois que quelqu'un se connecte alors que
`migrationDone` vaut `false`. Le premier joueur qui arrive après l'installation la
déclenche. La commande existe pour le cas où tu veux la lancer depuis la console avant de
rouvrir le serveur, et pour que la migration ait un point d'entrée visible.

### Ce que fait la commande

1. Si `migrationDone` vaut déjà `true`, elle affiche un refus et s'arrête. Rien n'est
   touché.
2. Sinon elle appelle `QuestMigration.migrateAll()`, qui parcourt chaque `TeamData` connu du
   fichier de quêtes et recopie les entrées vers les `TeamData` individuels. Les règles de
   copie ne sont pas uniformes, et c'est important : lis [la migration](migration.fr.md)
   avant de lancer ça sur un monde auquel tu tiens.
3. Met `migrationDone = true` et écrit `config/cheesefixquests.json`.
4. Si quelque chose a été copié, marque le fichier de quêtes comme modifié et appelle
   `saveNow()` — la nouvelle progression est sur le disque avant le retour de la commande.

### Effets de bord

- réécrit `config/cheesefixquests.json` depuis les valeurs en mémoire : toute modification
  manuelle faite dans ce fichier serveur allumé est perdue
- écrit le fichier de quêtes sur le disque, de façon synchrone
- journalise une ligne par équipe source et une par joueur migré, en INFO, sous
  `[CheeseFixQuests]`

La valeur de retour est le nombre de migrations de joueur effectuées : c'est ce que verront
`execute if` et les blocs de commande.

### Exemple

```mcfunction
/cheesefixquests migrate
```

```text
§a[CheeseFixQuests] Migration terminée: 3 joueur(s) migrés.
```

Ce retour est envoyé avec `broadcastToOps = true` : tous les opérateurs connectés le voient.
Le détail est dans le journal du serveur :

```text
[CheeseFixQuests] Processing team 8f3c…-… [PARTY] — 2 reward UUID(s), 3 total player(s), filter=false
[CheeseFixQuests]   8f3c…-… → PersonalTeam 1a2b…-… [FULL (party member)]: tasks=14, started=6, completed=6, rewards=4
[CheeseFixQuests] Migration saved: 3 player(s) updated
```

### Échecs

| Message | Cause |
|---|---|
| `§e[CheeseFixQuests] Migration déjà effectuée. Utilisez /cheesefixquests remigrate pour forcer.` | `migrationDone` vaut `true`. Utilise `remigrate` si tu veux vraiment la relancer |

Il n'y a pas de message pour « FTB Quests n'est pas chargé ». Dans ce cas `QuestMigration`
journalise `ServerQuestFile not loaded, cannot migrate` ou `FTB Teams manager not available`
en WARN, renvoie `0`, et la commande annonce quand même une migration réussie de zéro
joueur — en ayant mis `migrationDone = true`. C'est une verrue : lancer `migrate` trop tôt
dans la séquence de démarrage marque la migration comme faite sans rien migrer, et il faut
ensuite `remigrate` pour s'en sortir.

---

## `/cheesefixquests remigrate`

```mcfunction
/cheesefixquests remigrate
```

Aucun argument. La même migration, sans le garde-fou `migrationDone`.

`QuestMigration` est écrit pour être idempotent : la progression des tâches fusionne en
`max(existant, source)`, et `started` / `completed` / `claimedRewards` ne sont écrits que
lorsque la clé est absente. Un second passage sur des données inchangées ne copie rien et
annonce zéro. `remigrate` est donc sans danger à répéter, au sens étroit où il n'abîmera pas
ce qui est déjà là.

Il ne l'est pas au sens large : si un joueur a depuis *perdu* de la progression
volontairement — parce que tu as réinitialisé un chapitre, par exemple — une remigration
peut la recopier depuis l'ancien enregistrement d'équipe, qui existe toujours. La migration
ne supprime jamais sa source.

```mcfunction
/cheesefixquests remigrate
```

```text
§a[CheeseFixQuests] Re-migration terminée: 0 joueur(s) migrés.
```

Zéro est la réponse attendue sur un monde déjà migré et inchangé. Mêmes effets de bord que
`migrate` : configuration réécrite, fichier de quêtes sauvegardé, mêmes lignes de journal.
Renvoie le nombre de migrations de joueur effectuées.

---

## `/cheesefixquests resetchapter`

```mcfunction
/cheesefixquests resetchapter <chapterHexId>
```

Efface toutes les quêtes **terminées** d'un chapitre, pour tous les joueurs, et les rend à
nouveau disponibles depuis le début.

| Argument | Type | Requis | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `chapterHexId` | mot unique (`StringArgumentType.word()`) | oui | l'identifiant FTB Quests d'un chapitre, sous la forme hexadécimale utilisée par le carnet et les fichiers `.snbt`, p. ex. `3C1A4E7B9D2F6058`. Aucune complétion | — |

L'identifiant est celui du chapitre, pas celui d'une quête. Tu peux le lire dans le nom de
fichier du chapitre sous `config/ftbquests/quests/chapters/`, ou dans le carnet en mode
édition.

### Ce que fait la commande

Pour chaque `TeamData` du fichier de quêtes :

1. Résoudre l'équipe propriétaire. Si ce n'est pas une équipe de joueur — un groupe, ou une
   équipe qui n'existe plus — **tout le `TeamData` est ignoré**. La progression détenue par
   un groupe n'est pas réinitialisée.
2. Pour chaque quête du chapitre, et seulement si cette équipe l'a marquée terminée :
   - la progression de chaque tâche est remise à `0`
   - `started` et `completed` sont effacés pour la quête
   - chaque récompense est dé-réclamée via `resetReward(playerUUID, reward)`
3. Si quelque chose a changé, le `TeamData` est marqué modifié et une ligne INFO est écrite.

Ensuite le fichier de quêtes est marqué modifié et `saveNow()` est appelé.

Deux conséquences à énoncer clairement. Les quêtes **en cours** mais non terminées sont
laissées intactes, progression partielle incluse. Et comme les récompenses sont
dé-réclamées, les joueurs qui avaient déjà pris la récompense d'une quête réinitialisée
peuvent la reprendre — c'est exactement l'effet voulu quand tu répares un chapitre
quotidien cassé, et un bug de duplication dans tous les autres cas.

### Pourquoi cette commande existe

Avant l'écriture d'`ExtraQuestsTimerMixin`, le minuteur quotidien d'ExtraQuests résolvait
l'équipe de groupe et réinitialisait *son* `TeamData`, qui sous ce mod est vide. Les
minuteurs expiraient, la réinitialisation ne touchait rien, et les quêtes quotidiennes
restaient terminées pour toujours. `resetchapter` est la réparation manuelle des chapitres
bloqués de cette façon. Le mixin en place, les nouvelles réinitialisations tombent sur les
bonnes données et tu ne devrais plus avoir besoin de cette commande.

### Exemple

```mcfunction
/cheesefixquests resetchapter 3C1A4E7B9D2F6058
```

```text
§e[CheeseFixQuests] Resetting 12 quests in chapter '3C1A4E7B9D2F6058' for all players...
§a[CheeseFixQuests] Reset terminé: 7 quest(s) réinitialisées au total.
```

Les deux lignes sont diffusées aux opérateurs. Le total est cumulé sur les joueurs : 7
signifie sept couples (joueur, quête) réinitialisés, pas sept quêtes distinctes. C'est aussi
la valeur de retour.

### Échecs

| Message | Cause |
|---|---|
| `§c[CheeseFixQuests] ServerQuestFile not loaded` | `ServerQuestFile.INSTANCE` est nul — FTB Quests n'a pas fini de charger, ou est absent |
| `§c[CheeseFixQuests] Invalid hex ID: <id>` | `ServerQuestFile.getID(String)` a levé une exception sur l'argument |
| `§c[CheeseFixQuests] ID <id> is not a chapter` | l'identifiant a été résolu, mais vers autre chose qu'un `Chapter` — une quête, une tâche, ou rien du tout |

Les deux derniers se recouvrent en pratique. `getID(String)` appartient à FTB Quests et ce
mod ne le contrôle pas ; d'après le comportement observé, une chaîne hexadécimale bien
formée qui ne correspond à aucun objet ne lève pas d'exception et tombe donc dans le message
*is not a chapter* plutôt que dans *Invalid hex ID*. Lis ce message comme « je n'ai pas
trouvé de chapitre avec cet identifiant », quelle qu'en soit la raison.

Les trois échecs renvoient `0` et ne changent rien.

---

## Aucune commande pour les redirections

Il n'y a pas de commande pour activer ou couper la progression individuelle à chaud, ni de
commande de rechargement. Chaque drapeau de [`cheesefixquests.json`](configuration.fr.md)
est lu une fois dans `onInitialize` vers un champ statique ; modifier le fichier exige un
redémarrage du serveur. Les drapeaux sont relus à chaque redirection, donc un code qui les
muterait fonctionnerait — aucun ne le fait.

---

← [Premiers pas](getting-started.fr.md) · [Configuration](configuration.fr.md) →
