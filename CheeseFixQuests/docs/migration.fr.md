[← CheeseFixQuests](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](migration.md)

# La migration

Installer CheeseFixQuests change *l'endroit* où FTB Quests va chercher la progression d'un
joueur. Cela ne déplace pas, en soi, la progression qui s'y trouve déjà. Sur un serveur qui
a tourné avec FTB Quests et des groupes, tout cet historique est rangé dans des
enregistrements `TeamData` indexés par groupe, et à l'instant où les redirections entrent en
action chacun de ces joueurs se retrouve devant un carnet vide.

`QuestMigration` existe pour combler cet écart : il recopie les anciens enregistrements
d'équipe vers des enregistrements individuels. Cette page explique ce qu'il copie, ce qu'il
refuse délibérément de copier, et pourquoi la réponse n'est pas simplement « tout ».

Si ton serveur est tout neuf, ou n'a jamais utilisé les groupes FTB Teams, rien de tout cela
ne te concerne : il n'y a rien à migrer et la migration annoncera zéro.

---

## Quand elle s'exécute

Deux points d'entrée, le même code :

- **Automatiquement**, une fois. `ServerQuestFileMixin` s'injecte en tête de
  `ServerQuestFile.playerLoggedIn` ; si `migrationDone` vaut `false` et
  `forceIndividualProgress` vaut `true`, il lance `QuestMigration.migrateAll()`, passe
  `migrationDone` à `true` et enregistre la configuration. Le premier joueur qui se connecte
  après l'installation déclenche tout.
- **Manuellement**, avec [`/cheesefixquests migrate`](commands.fr.md#cheesefixquests-migrate)
  ou [`/cheesefixquests remigrate`](commands.fr.md#cheesefixquests-remigrate).

Comme le chemin automatique se déclenche sur une connexion, il s'exécute **sur le thread
serveur, à l'intérieur de la séquence de connexion**, avant que FTB Quests n'ait synchronisé
les données de ce joueur vers lui. Sur un monde avec quelques centaines d'enregistrements
`TeamData` et un millier de quêtes, cela fait une pause visible pour ce premier joueur. Cela
n'arrive qu'une fois.

> ⚠️ La migration automatique réécrit la progression des quêtes à la première connexion,
> sans rien demander et sans sauvegarde. Sauvegarde `world/ftbquests/` (et
> `config/ftbquests/`) **avant** le premier démarrage avec ce mod installé.

---

## Le modèle de données sur lequel elle travaille

FTB Quests garde un `TeamData` par équipe. Quatre maps à l'intérieur portent la progression :

| Champ | Clé | Attribution |
|---|---|---|
| `taskProgress` | identifiant de tâche | **par équipe**. Aucune trace du membre qui l'a gagnée |
| `started` | identifiant de quête / chapitre | **par équipe** |
| `completed` | identifiant de quête / chapitre | **par équipe** |
| `claimedRewards` | `QuestKey(uuid, identifiant de récompense)` | **par joueur** — la seule map qui nomme un joueur |

`TeamDataAccessor`, une interface d'accesseurs mixin sur `TeamData`, est ce qui permet à la
migration de lire et d'écrire ces quatre maps directement au lieu de passer par les setters
de FTB. C'est délibéré : les setters déclenchent les cascades de complétion et la remise des
récompenses, et une migration qui distribuerait des récompenses pendant la copie serait
inutilisable.

L'asymétrie de ce tableau est toute la difficulté. Trois des quatre maps n'ont aucune idée de
qui a fait quoi. Seule `claimedRewards` le sait, par l'UUID du joueur contenu dans chaque
`QuestKey`.

---

## Ce qu'elle copie, source par source

`migrateAll()` parcourt chaque `TeamData` du fichier de quêtes. Pour chacun, il construit
l'ensemble des joueurs candidats — chaque UUID non nul trouvé dans `claimedRewards`, plus les
membres actuels si l'équipe est encore un groupe actif — puis choisit l'un de deux modes.

### Groupe actif → copie intégrale

Si l'équipe source existe encore et que `isPartyTeam()` est vrai, chaque membre actuel reçoit
une **copie intégrale** des quatre maps. Le raisonnement : tant que le groupe était vivant,
ses membres partageaient réellement cette progression. La découper après coup serait de la
devinette, et donner à chaque membre le total partagé est le résultat qui correspond à ce que
les joueurs ont vécu.

Un effet de bord à anticiper : deux joueurs qui ont fait la moitié du travail chacun
ressortent tous les deux de la migration avec le chapitre entier terminé.

### Tout le reste → filtrage guidé par les récompenses

Pour une source qui est une équipe de joueur, ou une équipe qui n'existe plus du tout, la
migration **filtre**. Elle ne copie une entrée de `taskProgress` / `started` / `completed`
que lorsque l'identifiant appartient à une quête pour laquelle le joueur a personnellement
réclamé une récompense.

L'ensemble d'identifiants est construit depuis `claimedRewards` : pour chaque récompense
réclamée, résoudre la récompense, puis sa quête, et collecter l'identifiant de la quête,
celui de son chapitre, et ceux de toutes les tâches de cette quête. Tout ce qui est en dehors
de cet ensemble est laissé sur place.

C'est une vraie perte de données, assumée. Un enregistrement d'équipe disparue peut contenir
la progression de plusieurs joueurs partis à des moments différents, et seules les
réclamations de récompense disent qui était qui. Tout copier donnerait à un joueur les
complétions d'un autre. Le commentaire dans le code est franc sur l'arbitrage :
`task_progress/started/completed are per-TEAM, not per-player`.

Conséquence pratique : **une quête qu'un joueur a terminée mais dont il n'a jamais réclamé la
récompense ne survit pas à une migration filtrée.** Il devra la refaire. Avec les données que
FTB Quests stocke, il n'y a pas de contournement.

### `claimedRewards`, dans les deux modes

Les réclamations de récompense sont toujours filtrées par UUID, dans les deux modes : une
entrée n'est copiée vers un joueur que si son `QuestKey.uuid()` est l'UUID de ce joueur, ou
l'UUID nul `00000000-0000-…` que FTB utilise pour les récompenses d'équipe. Personne n'hérite
de la réclamation de quelqu'un d'autre.

---

## Sémantique de fusion

La copie n'écrase jamais, à une exception près :

| Map | Règle |
|---|---|
| `taskProgress` | `max(existant, source)` — le plus grand nombre gagne |
| `started` | écrit uniquement si la clé est absente |
| `completed` | écrit uniquement si la clé est absente |
| `claimedRewards` | écrit uniquement si la clé est absente |

C'est ce qui rend `remigrate` répétable : un second passage sur des données inchangées trouve
chaque clé déjà présente, ne copie rien, et annonce zéro migration.

Cela veut dire aussi que la migration est **purement additive**. Elle ne supprime jamais, ne
diminue jamais un compteur, et ne touche jamais au `TeamData` source. Les anciens
enregistrements de groupe restent sur le disque exactement tels qu'ils étaient. C'est utile
quand il faut relancer la migration, et c'est un piège quand tu as volontairement
réinitialisé quelque chose : un `remigrate` ultérieur peut recopier l'ancienne valeur.
Réinitialise après avoir migré, pas avant.

---

## Lire le journal

`migrateAll()` écrit une ligne par équipe source :

```text
[CheeseFixQuests] Processing team 8f3c1d20-… [PARTY] — 2 reward UUID(s), 3 total player(s), filter=false
```

- le type entre crochets est `PARTY`, `PLAYER` ou `DEFUNCT` (l'identifiant d'équipe n'a rien
  résolu)
- `reward UUID(s)` est le nombre de joueurs distincts ayant laissé des réclamations dans cet
  enregistrement
- `filter=false` signifie copie intégrale ; `filter=true` signifie filtrage guidé par les
  récompenses

Puis une ligne par joueur :

```text
[CheeseFixQuests]   8f3c1d20-… → PersonalTeam 1a2b3c4d-… [FULL (party member)]: tasks=14, started=6, completed=6, rewards=4
[CheeseFixQuests]   7e5f…-… → PersonalTeam 9c8d…-… [FILTERED (23 quest/task/chapter IDs)]: tasks=9, started=4, completed=4, rewards=3
[CheeseFixQuests]   4b2a…-… — 0 new entries [FILTERED (0 quest IDs — only copying claimedRewards)]
```

Les quatre compteurs sont les entrées réellement écrites, pas celles examinées.
`0 new entries` à un second passage est la réponse attendue et saine.

Et une ligne à la fin :

```text
[CheeseFixQuests] Migration saved: 3 player(s) updated
```

ou, quand rien n'a bougé :

```text
[CheeseFixQuests] No new data to migrate
```

Deux avertissements méritent d'être connus :

```text
[CheeseFixQuests]   <uuid> — getPlayerTeamForPlayerID returned EMPTY, trying getTeamByID fallback...
[CheeseFixQuests]   <uuid> — PlayerTeam NOT FOUND (both methods), skipping
```

FTB Teams crée l'équipe d'un joueur à sa première connexion. Un UUID qui apparaît dans une
ancienne réclamation mais n'a pas d'équipe de joueur — un joueur purgé des données FTB Teams,
ou un UUID venu d'un import de monde — ne peut pas être migré, et est ignoré. Si la seconde
ligne nomme un joueur toujours actif, mentionne-le dans un rapport de bug : cela ne devrait
pas arriver.

---

## La distinction `getId()` / `getTeamId()`

Une ligne de `QuestMigration` porte l'essentiel de l'histoire du mod :

```java
UUID personalId = playerTeam.getId();   // NOT getTeamId()
```

Dans FTB Teams, `Team.getTeamId()` sur une équipe de joueur renvoie l'identifiant de l'équipe
**effective** de ce joueur — le groupe, quand il en a un. `Team.getId()` renvoie
l'identifiant permanent propre à l'équipe de joueur. Une version antérieure de cette
migration utilisait `getTeamId()`, le comparait à l'identifiant de l'équipe source, concluait
« même équipe, rien à faire », et sautait absolument tous les membres de groupe. La migration
annonçait un succès et ne copiait rien.

Si tu étends ce code, c'est la distinction à garder en tête. Partout où ce mod veut « le
stockage propre à ce joueur, groupes ou pas », il veut `getId()` ou
`getPlayerTeamForPlayerID`.

---

## Migrer en sens inverse

Il n'y a pas de migration inverse. Si tu retires CheeseFixQuests, FTB Quests revient à lire
les `TeamData` indexés par groupe, qui contiennent toujours ce qu'ils contenaient avant la
migration — ancien, et amputé de tout ce qui s'est passé pendant que le mod était installé.
Les enregistrements individuels restent sur le disque et deviennent invisibles pour quiconque
est dans un groupe.

Réinstaller le mod reprend les enregistrements individuels : l'aller-retour n'est donc pas
destructeur. Mais une période de jeu sans le mod laissera dans l'enregistrement du groupe de
la progression qu'aucune migration ultérieure ne déplacera, puisque `migrationDone` vaut déjà
`true`. Lance `remigrate` dans ce cas, et relis les règles de filtrage ci-dessus avant.

---

← [Configuration](configuration.fr.md) · [Le balayage de finalisation](finalizer.fr.md) →
