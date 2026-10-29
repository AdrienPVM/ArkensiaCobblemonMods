[← CheeseGuard](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](commands-operations.md)

# Commandes — événements, prison et exploitation

La seconde moitié de la surface en commandes. [La première page](commands.fr.md) couvre
les zones, les flags, les groupes et les états ; celle-ci couvre tout ce qui agit sur les
joueurs et sur le serveur : le sous-arbre `/cg event`, les bascules de minijeu, les mondes
par joueur, les limites de pâturages, la restauration d'inventaire, et la commande
`/prison` séparée.

Les niveaux de permission sont indiqués par sous-commande. Une sous-commande d'ici,
`/cg adv`, n'a aucune garde, et `/cg event` est ouverte par choix, le filtrage se faisant
feuille par feuille.

---

## `/cg event`

Le nœud `event` est déclaré explicitement `requires(src -> true)` : il est donc visible
par tout le monde, et le filtrage se fait feuille par feuille. Le modèle d'événement —
états, phases, effets, participants — est dans [les événements](events.fr.md) ; le langage
de timeline qui pilote les actes d'un événement est dans [la timeline](timeline.fr.md).

Un événement est **en cours** dès que son état n'est pas `IDLE`, et **actif** uniquement
dans l'état `ACTIVE`. Les sept états sont `IDLE`, `ANNOUNCED`, `QUEUE`, `COUNTDOWN`,
`ACTIVE`, `ENDING`, `CLEANUP`. Il n'existe aucun état de pause et aucune commande de pause
ou de reprise : un événement que tu veux arrêter doit l'être depuis le dashboard.

### `/cg event join`

```mcfunction
/cg event join
/cg event join <eventId>
```

Aucune permission requise. Doit être lancée par un joueur.

| Argument | Type | Requis | Valeurs acceptées |
|---|---|---|---|
| `eventId` | mot unique | non | un identifiant d'événement ; aucune complétion |

Sans argument, la commande parcourt la liste des événements et rejoint le **premier**
événement en cours auquel tu ne participes pas déjà. L'ordre est celui de la liste dans
`cheeseguard_events.json`, pas une priorité : avec deux événements en cours, tu ne peux pas
prédire lequel tu obtiens. Passe l'identifiant quand cela compte.

Tu rejoins comme `PARTICIPANT`. L'inscription sauvegarde d'abord ton inventaire et ta
position, afin que le départ puisse te les rendre.

```text
Inscrit à l'événement : Spleef Night
```

| Message | Cause |
|---|---|
| `Aucun événement disponible à rejoindre` | rien n'est en cours, ou tu participes déjà à tous les événements en cours |
| `Événement introuvable` | l'identifiant ne correspond à aucun événement |
| `Impossible de rejoindre cet événement` | `EventManager.joinEvent` a refusé — l'événement a dépassé sa phase de file d'attente, il est plein, ou les conditions d'inscription ne sont pas remplies |

### `/cg event spectate`

```mcfunction
/cg event spectate
/cg event spectate <eventId>
```

Les deux mêmes formes et la même résolution, mais l'inscription se fait avec le rôle
`SPECTATOR`.

```text
Spectateur de : Spleef Night
```

| Message | Cause |
|---|---|
| `Aucun événement disponible à rejoindre` | rien en cours — la forme sans argument réutilise le message de `join` |
| `Événement introuvable` | identifiant inconnu |
| `Impossible d'observer cet événement` | l'événement a refusé l'inscription comme spectateur |

### `/cg event leave`

```mcfunction
/cg event leave
```

Aucune permission requise. Trois replis, essayés dans l'ordre, et c'est ce qui en fait la
commande à utiliser quand un joueur est bloqué :

1. Si tu participes à un événement, le quitter normalement — inventaire et position
   restaurés, score conservé dans le relevé de l'événement.
2. Sinon, s'il existe une **restauration en attente** pour toi (le serveur a planté
   pendant que tu étais dans un événement, et l'instantané de ton inventaire a survécu
   dans `config/cheeseguard_pending_inv/`), l'appliquer maintenant et retirer l'item de
   sortie.
3. Sinon, retirer l'item de sortie de ton inventaire et annoncer que tu n'étais dans aucun
   événement.

```text
Vous avez quitté l'événement.
```

```text
Inventaire et position restaurés.
```

```text
Vous ne participez à aucun événement
```

Le troisième message correspond à un retour `0`, les deux autres à `1`. Le cas 2 est le
chemin de récupération après un plantage ; si un joueur signale avoir perdu son inventaire
à cause d'un événement, c'est la première chose à essayer.

### `/cg event launch`

```mcfunction
/cg event launch <eventId>
```

Aucune permission requise, mais filtrée à l'exécution. C'est ainsi que les joueurs
démarrent un événement eux-mêmes, sans administrateur présent.

| Argument | Type | Requis | Valeurs acceptées |
|---|---|---|---|
| `eventId` | mot unique | oui | un identifiant d'événement |

Contrôles, dans l'ordre :

1. l'événement existe ;
2. l'événement a `playerLaunchable` ;
3. l'événement n'est pas déjà en cours ;
4. le cooldown propre à l'événement est écoulé, si `cooldownMinutes` dépasse 0 et que
   l'événement a déjà tourné ;
5. sauf si l'appelant a le niveau 2 — **les opérateurs sautent le reste** — la politique
   globale de lancement de `config/cheeseguard_event_launch.json` : fenêtre horaire, puis
   limite par joueur, puis limite à l'échelle du serveur.

Un lancement réussi enregistre l'horodatage sous ton UUID et annonce l'événement, ce qui
le place en `ANNOUNCED` et non directement en `ACTIVE`.

```mcfunction
/cg event launch spleef_night
```

```text
Événement 'Spleef Night' lancé !
```

| Message | Cause |
|---|---|
| `Événement introuvable` | identifiant inconnu |
| `Cet événement ne peut pas être lancé par les joueurs.` | `playerLaunchable` est désactivé |
| `L'événement est déjà en cours` | l'état n'est pas `IDLE` |
| `Cet événement est en cooldown, réessayez plus tard.` | le cooldown propre à l'événement n'est pas écoulé |
| `Les événements ne peuvent être lancés qu'entre les heures autorisées.` | hors de la fenêtre horaire configurée |
| `Vous avez atteint votre limite de lancements pour cette période.` | ta propre limite |
| `La limite globale de lancements a été atteinte pour cette période.` | la limite à l'échelle du serveur |

Les trois derniers viennent de `EventManager.checkLaunchAllowed`, qui renvoie un code court
que la commande transforme en clé `cheeseguard.cmd.launch_<code>`. Ajouter un nouveau code
dans `checkLaunchAllowed` sans ajouter la clé de langue correspondante afficherait la clé
brute aux joueurs — bon à savoir avant d'étendre ce mécanisme.

### `/cg event list`

```mcfunction
/cg event list
```

Aucune permission requise. Uniquement les événements en cours, chacun avec un bouton
cliquable d'inscription.

```text
🎉 Spleef Night — ACTIVE (4m12s) [Participer]
```

Le nom de l'état est affiché brut depuis l'énumération : les joueurs voient donc
`COUNTDOWN` et `CLEANUP` non traduits. Quand rien ne tourne :

```text
Aucun événement en cours
```

### `/cg event announce`

```mcfunction
/cg event announce <eventId>
```

**Niveau 2.** Fait passer un événement au repos en `ANNOUNCED` et le diffuse, ce qui est la
façon normale d'ouvrir les inscriptions côté administration.

```text
Événement 'spleef_night' annoncé !
```

| Message | Cause |
|---|---|
| `Événement introuvable` | identifiant inconnu |
| `L'événement est déjà en cours` | l'événement n'est pas `IDLE` |

### `/cg event score add`

```mcfunction
/cg event score add <target> <amount>
```

**Niveau 2.**

| Argument | Type | Requis | Valeurs acceptées |
|---|---|---|---|
| `target` | mot unique | oui | le nom d'un joueur connecté |
| `amount` | entier | oui | 1 au minimum, imposé par Brigadier |

Trouve le premier événement **actif** auquel la cible participe et y ajoute les points.
Seul `ACTIVE` compte : un joueur dans un événement encore en `COUNTDOWN` ne peut pas être
noté.

Le minimum de 1 signifie qu'aucune commande ne permet de retirer des points. Passe par le
dashboard pour les corrections.

```mcfunction
/cg event score add Steve 50
```

```text
+50 pts pour Steve
```

| Message | Cause |
|---|---|
| `Joueur introuvable` | pas connecté |
| `Ce joueur ne participe à aucun événement actif` | aucune participation, ou l'événement n'est pas en `ACTIVE` |

### `/cg event snapshot`

```mcfunction
/cg event snapshot save <snapId> <regionId>
/cg event snapshot restore <snapId>
```

**Niveau 2.** Un mécanisme plus léger que
[les états de zone sauvegardés](commands.fr.md#les-états-de-zone-sauvegardés) : un fichier
NBT par snapshot dans `config/cheeseguard_snapshots/<snapId>.nbt`, les blocs seulement,
sans animation et sans index par zone.

```mcfunction
/cg event snapshot save arena_clean boss_arena
```

```text
Snapshot 'arena_clean' : 18432 blocs
```

```mcfunction
/cg event snapshot restore arena_clean
```

```text
18432 blocs restaurés
```

| Message | Cause |
|---|---|
| `Aucun bloc dans la zone <id>` | la capture n'a rien trouvé — généralement un mauvais identifiant de zone, puisqu'une boîte vide capture quand même de l'air |
| `Snapshot introuvable : <id>` | aucun fichier avec cet identifiant |

`restore` ne prend pas d'argument de zone : le snapshot se souvient de la région d'où il
vient. Rien n'empêche deux snapshots de partager un nom de fichier, et un `save` sur un
identifiant existant l'écrase sans demander.

### `/cg event template`

```mcfunction
/cg event template save <eventId> <tplName>
/cg event template load <tplName>
/cg event template list
```

**Niveau 2.** Les templates sont stockés dans **`config/cheeseguard_templates/`** — pas
dans `config/templates/`. Un fichier JSON par template, nommé d'après le nom du template
avec tout caractère hors de `[a-zA-Z0-9_-]` remplacé par `_`, en minuscules.

| Argument | Type | Requis | Valeurs acceptées |
|---|---|---|---|
| `eventId` | mot unique | oui (pour `save`) | un identifiant d'événement existant |
| `tplName` | chaîne gourmande | oui (pour `save` et `load`) | n'importe quel texte |

`load` crée un **nouvel événement** à partir du template et l'ajoute à la liste, puis
sauvegarde `cheeseguard_events.json`. Il ne remplace pas un événement existant : charger
deux fois le même template te donne deux événements.

```mcfunction
/cg event template save spleef_night Spleef Standard
```

```text
Template sauvegardé : Spleef Standard
```

```mcfunction
/cg event template list
```

```text
  📄 spleef_standard
```

| Message | Cause |
|---|---|
| `Événement introuvable` | identifiant d'événement inconnu sur `save` |
| `Erreur sauvegarde template` | l'écriture a échoué — cherche l'erreur d'IO dans le log serveur |
| `Template introuvable : <nom>` | aucun fichier ne correspond, après transformation du nom |
| `Aucun template` | le dossier est vide ou absent |

`load` essaie d'abord le nom de fichier transformé, puis le nom brut : un template
sauvegardé sous `Spleef Standard` se charge donc avec l'une ou l'autre orthographe.

---

## `/cg hotpotato`

```mcfunction
/cg hotpotato on
/cg hotpotato off
/cg hotpotato bomb
/cg hotpotato explode
```

**Niveau 2** sur tout le sous-arbre. Un jeu de fête autonome : un joueur porte une bombe
et la passe en faisant un clic droit sur un autre joueur.

| Sous-commande | Effet |
|---|---|
| `on` | arme le handler — le clic droit sur un joueur passe la bombe |
| `off` | le désarme **et** retire l'item bombe de tous les joueurs connectés |
| `bomb` | donne la bombe à un joueur connecté au hasard |
| `explode` | fait exploser celui qui la porte |

```text
Hot Potato activé !
```

```text
Bombe donnée à Steve
```

```text
Steve éliminé !
```

```text
Hot Potato désactivé — bombes retirées
```

| Message | Cause |
|---|---|
| `Aucun joueur en ligne` | `bomb` sur un serveur vide |
| `Aucun joueur ne porte la bombe` | `explode` sans porteur — personne n'en a reçu, ou le porteur s'est déconnecté |

`HotPotatoHandler` garde son indicateur d'activité dans un champ statique, pas dans un
fichier de configuration : le jeu est donc toujours désactivé après un redémarrage, même si
tu l'avais laissé actif.

---

## `/cg playerworld`

```mcfunction
/cg playerworld list <masterDimId>
/cg playerworld delete <masterDimId> <player>
```

**Niveau 2.** Les copies par joueur d'une dimension maîtresse. L'identifiant de chaque
copie est `<masterDimId>_<uuid8>` — l'identifiant de la maîtresse, un tiret bas, et les
huit premiers caractères hexadécimaux de l'UUID du propriétaire. Le modèle est dans
[les dimensions](dimensions.fr.md).

| Argument | Type | Requis | Valeurs acceptées |
|---|---|---|---|
| `masterDimId` | mot unique | oui | l'identifiant de configuration de la dimension **maîtresse**, pas celui d'une copie |
| `player` | mot unique | oui (pour `delete`) | un nom de joueur |

```mcfunction
/cg playerworld list personal_island
```

```text
Dims personnelles de 'personal_island' (2)
  • Steve's island [personal_island_f81d4fae]
  • Alex's island [personal_island_7d4448d9]
```

```text
Aucune dimension personnelle pour 'personal_island'
```

`delete` résout le joueur en deux étapes : un joueur connecté fournit son UUID
directement ; sinon la commande parcourt les dimensions par joueur de la maîtresse à la
recherche d'une dont le nom affiché *contient* le nom donné, puis prend le premier UUID de
la liste blanche de cette dimension. Cette correspondance par sous-chaîne est lâche — `Al`
correspond à `Alex` — et le repli sur la liste blanche suppose que le premier UUID est
celui du propriétaire. Préfère supprimer pendant que le joueur est connecté, ou le faire
depuis le dashboard, où la correspondance est explicite.

```text
Dimension personnelle de 'Steve' supprimée.
```

| Message | Cause |
|---|---|
| `DimensionManager non disponible` | le gestionnaire est null — le serveur n'a pas fini de démarrer |
| `Dimension personnelle introuvable pour '<joueur>' dans '<maîtresse>'` | aucune correspondance en ligne et aucune correspondance de nom parmi les dimensions stockées |
| `Échec de la suppression.` | `deletePlayerDimension` a renvoyé false. Sous Windows, c'est d'ordinaire un verrou de fichier : le dossier du monde n'a pas pu être supprimé parce que la dimension venait d'être déchargée |

La suppression efface les fichiers du monde. Il n'y a ni confirmation ni retour arrière.

---

## `/cg adv`

```mcfunction
/cg adv check <player>
/cg adv list
```

**Aucune garde de permission** — n'importe quel joueur peut lancer les deux. `check`
affiche tous les advancements complétés par la cible, `list` affiche tous les advancements
que le serveur connaît. Les advancements de recette (`recipes/…`) sont filtrés des deux.

```mcfunction
/cg adv check Steve
```

```text
Advancements de Steve
  ✅ Stone Age [minecraft:story/mine_stone]
  ✅ Getting an Upgrade [minecraft:story/upgrade_tools]
147/832 advancements complétés
```

| Message | Cause |
|---|---|
| `Joueur introuvable.` | la cible n'est pas connectée |

Les deux formes envoient une ligne de chat par advancement. Sur un serveur moddé avec
quelques centaines d'advancements, cela fait quelques centaines de messages d'un coup, que
le client va étrangler et qui peuvent déconnecter un joueur sur une connexion lente. C'est
un outil de débogage qui n'a jamais été protégé ni paginé ; traite-le comme tel.

---

## `/cg setspawn`

```mcfunction
/cg setspawn
```

**Niveau 2.** Doit être lancée par un joueur. Écrit ta position actuelle **et ton
orientation** comme point d'apparition de la configuration de dimension CheeseGuard
correspondant au monde où tu te trouves, puis sauvegarde
`config/cheeseguard_dimensions.json`.

```text
Spawn défini à 118.5, 64.0, -302.5 dans Boss Arena
```

| Message | Cause |
|---|---|
| `Dimension introuvable dans la config : <worldId>` | le monde où tu es n'est pas géré par CheeseGuard. Les mondes vanilla ne le sont pas, sauf si tu les as ajoutés |

Cela définit le point d'apparition *CheeseGuard* de la dimension, utilisé quand le mod y
téléporte un joueur. Cela ne touche pas au spawn vanilla du monde.

---

## `/cg pasture`

```mcfunction
/cg pasture limits
/cg pasture chunk <max>
/cg pasture player <max>
```

**Niveau 2.** Plafonds sur les blocs de pâturage Cobblemon par chunk, pour éviter qu'une
poignée de joueurs rende un chunk impossible à charger.

| Argument | Type | Requis | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `max` | entier | oui | 0 ou plus | les deux limites valent `1` par défaut |

`limits` affiche le couple actuel. `chunk` fixe le total par chunk ; `player` fixe le total
par chunk **et par joueur**. `0` signifie qu'aucun pâturage ne peut être placé, pas
« illimité ».

```text
Pâturages — max par chunk : 1 | max par chunk/joueur : 1
```

```mcfunction
/cg pasture chunk 4
```

```text
Limite par chunk définie à 4 pâturage(s).
```

Chaque modification sauvegarde `config/cheeseguard_pastures.json` immédiatement et prend
effet à la prochaine tentative de pose. Sans redémarrage.

---

## `/cg restoreinv`

```mcfunction
/cg restoreinv list <player>
/cg restoreinv apply <player>
/cg restoreinv apply <player> <filename>
```

**Niveau 2.** Restaure l'inventaire d'un joueur depuis l'archive que CheeseGuard écrit
dans `config/cheeseguard_inv_archive/` quand un événement vide un inventaire. C'est
l'outil de récupération pour la pire catégorie de bug que ce mod peut provoquer, d'où sa
verbosité délibérée.

| Argument | Type | Requis | Valeurs acceptées |
|---|---|---|---|
| `player` | mot unique | oui | un nom de joueur — résolu depuis la liste des connectés, puis le cache de profils du serveur, puis les noms de fichiers d'archive |
| `filename` | chaîne gourmande | non | un nom de fichier exact donné par `list` ; omis, l'archive la plus récente pour cet UUID est utilisée |

`list` affiche les archives, la plus récente d'abord. Chaque ligne est cliquable et
remplit la commande `apply` correspondante dans ta zone de chat, ce qui est la façon prévue
de s'en servir — les noms de fichiers sont longs.

```mcfunction
/cg restoreinv list Steve
```

```text
Archives for Steve (UUID 00000000-0000-0000-0000-000000000000) — most recent first:
  2026-10-28T19:04:11  inv_f81d4fae_2026-10-28T19-04-11.nbt
  2026-10-27T21:40:02  inv_f81d4fae_2026-10-27T21-40-02.nbt
```

Toute la sortie de cette sous-commande est en anglais : ses messages passent par
`Text.literal` et non par le fichier de langue, contrairement au reste du mod.

`apply` affiche l'UUID résolu *avant* toute action destructrice, pour que tu puisses le
vérifier :

```text
Resolved Steve → UUID 00000000-0000-0000-0000-000000000000
Inventory restored online for Steve from inv_f81d4fae_2026-10-28T19-04-11.nbt
```

La cible est prévenue aussi :

```text
Your inventory has been restored from a CheeseGuard archive (2026-10-28T19:04:11).
```

Deux chemins, choisis automatiquement :

- **En ligne** — le NBT de l'archive est lu dans le joueur vivant, puis synchronisé.
- **Hors ligne** — le fichier `playerdata` du joueur est remplacé, après l'écriture d'une
  copie de sécurité à côté. Le retour nomme cette copie.

```text
Offline inventory swap done for Steve from inv_f81d4fae_2026-10-28T19-04-11.nbt (safety copy: 00000000-....dat.bak)
```

Si un `filename` explicite appartient à un UUID différent de celui du joueur nommé, la
commande avertit mais **l'applique quand même** :

```text
WARNING: archive UUID prefix 7d4448d9 does not match Steve (f81d4fae)
```

| Message | Cause |
|---|---|
| `Player not found: <name> (not online, not in user cache, no matching archive)` | les trois chemins de résolution ont échoué |
| `No archives found for <name>` | l'UUID a été résolu mais n'a aucune archive |
| `Archive file not found: <filename>` | le nom de fichier explicite n'existe pas |
| `Failed to read archive: <détail>` | erreur d'IO à la lecture du NBT |
| `Archive unreadable: missing playerData/Inventory or unsupported format. See server log.` | le fichier a été lu mais ne contient aucun inventaire |
| `Restore failed — see server log` | l'écriture en ligne a échoué |
| `Offline restore failed: <détail>` | le remplacement du fichier `playerdata` a échoué |

Chaque application est journalisée en INFO avec l'auteur, la cible, l'archive et le mode :

```text
[CG] /cg restoreinv: actor=RoiCheese target=Steve (00000000-...) archive=inv_f81d4fae_2026-10-28T19-04-11.nbt mode=online
```

---

## `/prison`

```mcfunction
/prison
```

Une commande racine séparée, enregistrée par `PrisonCommand`, avec le **niveau 2 sur le
nœud racine** — tout l'arbre est donc réservé aux opérateurs, `list` et `info` compris.
`/prison` nu affiche l'aide :

```text
— Commandes /prison —
/prison set <player> <duration> <reason>
/prison release <player>
/prison list
/prison info <player>
/prison setzone <zoneId>
/prison setout
/prison clearout
```

Le mécanisme de confinement, le sosie qui remplace un prisonnier déconnecté et la liste des
actions bloquées sont dans [la prison](prison.fr.md).

### `/prison setzone`

```mcfunction
/prison setzone <zoneId>
```

Se complète depuis tous les identifiants de zone. Rien d'autre dans l'arbre ne fonctionne
avant que ce soit fait.

```text
Zone de prison définie : prison
```

| Message | Cause |
|---|---|
| `Zone introuvable : <id>` | aucune zone avec cet identifiant |

### `/prison set`

```mcfunction
/prison set <player> <duration> <reason>
```

| Argument | Type | Requis | Valeurs acceptées |
|---|---|---|---|
| `player` | mot unique | oui | se complète depuis les joueurs connectés, mais un joueur hors ligne trouvé dans le cache de profils fonctionne aussi |
| `duration` | mot unique | oui | une durée composée, voir ci-dessous |
| `reason` | chaîne gourmande | oui | n'importe quel texte ; stocké et montré au prisonnier |

Les durées sont analysées par `PrisonManager.parseDuration` : une suite de chiffres suivis
chacun d'une unité, `s`, `m`, `h` ou `d`, concaténées. `90s`, `30m`, `2h`, `7d` et `1h30m`
sont tous valides, et ils s'additionnent. **Un nombre nu sans unité est lu comme des
minutes** : `30` vaut donc trente minutes. Tout le reste — un signe négatif, une unité sans
chiffre, `2w` — renvoie `-1` et est rejeté. Le total doit être strictement positif.

```mcfunction
/prison set Steve 2h griefing du marché
```

```text
Steve a été envoyé en prison pour 2h.
```

Le prisonnier, s'il est connecté, reçoit :

```text
⛓ Vous êtes en prison pour 2h. Raison : griefing du marché
```

| Message | Cause |
|---|---|
| `Aucune zone de prison n'est configurée. Utilisez /prison setzone <zoneId>.` | lance `setzone` d'abord |
| `Zone introuvable : <id>` | l'identifiant de zone de prison configuré n'existe plus dans `regions.json` |
| `Durée invalide : <valeur> (utilisez par exemple 30m, 2h, 7d).` | la durée n'a pas pu être analysée |
| `Joueur introuvable : <nom>` | ni connecté, ni dans le cache de profils |

Une cible connectée **n'est pas téléportée par cette commande**. Le handler envoie le
message et laisse le confinement par tick de `PrisonListener` la ramener dans la zone à un
tick ultérieur. Si la zone de prison est loin, attends-toi à un délai visible et à un bref
moment où le joueur peut encore agir.

### `/prison release`

```mcfunction
/prison release <player>
```

Se complète depuis la liste des prisonniers actifs. Libère, téléporte au point de sortie
configuré (ou au spawn du monde), et diffuse l'information.

```text
Steve a été libéré de prison.
```

```text
✨ Steve a été libéré de prison (grâce).
```

| Message | Cause |
|---|---|
| `Joueur introuvable : <nom>` | nom impossible à résoudre |
| `<nom> n'est pas en prison.` | aucune peine active. Signalé seulement pour une cible **hors ligne** — pour une cible connectée, le helper de libération en ligne s'exécute d'abord et la commande signale une réussite quoi qu'il arrive |

### `/prison list`

```mcfunction
/prison list
```

```text
Prisonniers actifs (2)
• Steve — 1h47m — griefing du marché
• Alex — 12m — spam dans le chat
```

Une prison vide affiche l'en-tête avec `(0)` et rien d'autre.

### `/prison info`

```mcfunction
/prison info <player>
```

```text
Prison — Steve
Raison : griefing du marché
Temps restant : 1h47m
Emprisonné par : RoiCheese
```

| Message | Cause |
|---|---|
| `Joueur introuvable : <nom>` | nom impossible à résoudre |
| `<nom> n'est pas en prison.` | aucune peine active |

### `/prison setout` / `/prison clearout`

```mcfunction
/prison setout
/prison clearout
```

`setout` enregistre ta position, ton orientation et ta dimension comme point de sortie de
toutes les libérations futures. `clearout` l'oublie, et les libérations retombent alors sur
le spawn du monde.

```text
Point de sortie de prison défini : 118.5, 64.0, -302.5 (minecraft:overworld).
```

```text
Point de sortie réinitialisé (retour au spawn par défaut).
```

| Message | Cause |
|---|---|
| `Cette commande doit être exécutée par un joueur.` | `setout` depuis la console |

Les deux écrivent `config/cheeseguard/prison.json` immédiatement.

---

## Ce qui n'a aucune commande

Plusieurs sous-systèmes ne sont accessibles que depuis le dashboard, par choix. Il n'existe
aucune commande pour les timelines, les circuits de course, les sets de chunks, les
webhooks Discord, la configuration de vente d'une zone, l'édition des flags d'un groupe ou
la création de dimension. Les propriétaires de zone disposent aussi d'un menu en jeu lié à
une touche côté client, géré par `ZoneOwnerMenuHandler`, qui n'est pas une commande non
plus.

---

← [Commandes](commands.fr.md) · [Configuration](configuration.fr.md) →
