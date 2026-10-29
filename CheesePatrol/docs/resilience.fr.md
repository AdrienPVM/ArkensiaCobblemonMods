[← CheesePatrol](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](resilience.md)

# Garder les NPC en vie

Plus de la moitié de ce mod ne parle pas de marche. Elle parle de garantir que ce qui
marche existe encore.

Le problème est réel : un NPC est une entité, les entités vivent dans des chunks, les
chunks se déchargent, et une entité déchargée ne tourne pas. Pire, des entités se perdent :
une corruption de chunk, un crash pendant une sauvegarde, un `/kill @e` avec un sélecteur
imprudent, un balayage de mob-cap, un administrateur qui nettoie les « mobs errants ». Un
NPC de patrouille qui s'évapore laisse un trou dans une ville que personne ne remarque
jusqu'à ce qu'un joueur demande où est passé le garde.

CheesePatrol répond par quatre couches, chacune rattrapant ce que les autres manquent.
Chacune a aussi un coût, et cette page nomme les deux.

---

## Couche 1 : le mixin de suppression

`EntityRemoveMixin` s'injecte en tête de `Entity.remove(RemovalReason)` et annule l'appel
pour les entités protégées.

```java
if (reason == RemovalReason.UNLOADED_TO_CHUNK
        || reason == RemovalReason.UNLOADED_WITH_PLAYER
        || reason == RemovalReason.CHANGED_DIMENSION) {
    return;   // autorisé
}
```

Ces trois raisons sont les suppressions légitimes : le chunk se décharge, l'entité est
écrite sur disque, ou elle change de monde. Tout le reste, `KILLED` et `DISCARDED`, est
bloqué.

Une entité est protégée si **l'un** des deux contrôles passe :

1. **Elle a une fiche de patrouille.** Active ou inactive, démarrée ou jamais démarrée :
   dès l'instant où tu sélectionnes un NPC à la baguette, il devient intuable.
2. **Elle a un fichier de sauvegarde.**
   `Files.exists(config/cheesepatrol_entities/<uuid>.dat)`. Ce second contrôle existe pour
   la fenêtre, pendant le chargement du monde, où les chunks tournent alors que
   `PatrolManager.load()` n'a pas encore été exécuté et que la table des patrouilles est
   donc vide.

La conséquence visible : `/kill` sur un NPC de patrouille ne fait rien, et le seul moyen
d'en supprimer un est de passer `/npcpatrol clear` d'abord. Le message d'état du clic droit
de la baguette (`[PATROL ACTIVE — /npcpatrol clear before deleting!]`) est là pour te le
rappeler.

### Ce que bloquer `KILLED` signifie réellement

⚠️ Bloquer la suppression n'est pas empêcher la mort. Un NPC qui prend des dégâts mortels
meurt quand même — sa vie tombe à zéro, `onDeath` s'exécute, l'animation de mort démarre —
puis la suppression qui devrait le nettoyer est annulée. Selon la logique même du code,
l'entité reste dans le monde dans cet état.

Ne compte pas sur ce mixin comme immunité aux dégâts. **Rends les NPC de patrouille
invulnérables dans la configuration propre d'EasyNPC**, et considère le mixin comme une
protection contre la suppression accidentelle, ce en quoi il est bon. Ceci est un
raisonnement sur le chemin de code et non un comportement mesuré ; si tu as un NPC coincé
dans une animation de mort, voilà pourquoi.

### Le mixin journalise chaque blocage

Chaque suppression annulée écrit une ligne, en `INFO`, sans limitation de débit et **sans
garde `debug`** :

```text
[CheesePatrol] Blocked removal (KILLED) of registered NPC a1b2c3d4 (patrol active)
[CheesePatrol] Blocked removal (DISCARDED) of NPC a1b2c3d4 via backup-file protection (patrol data not yet loaded)
```

La seconde est un `WARN`. Une boucle qui essaie sans cesse de supprimer une entité protégée
remplira un fichier de log, et le réglage `debug: false` ne la supprime pas. Si ton log est
plein de ces lignes, quelque chose essaie de supprimer tes NPC à intervalle régulier (un
plugin de nettoyage, très probablement), et cela vaut la peine de le trouver.

### Le coût : une vérification disque à chaque suppression d'entité, partout

C'est le fait de performance le plus important de ce mod, et il n'est pas visible depuis la
liste des fonctionnalités.

Les deux contrôles s'enchaînent, et le second ne tourne que si le premier échoue. Le
premier échoue pour **toute entité qui n'est pas un NPC de patrouille**, c'est-à-dire pour
toutes les entités du serveur. Donc chaque fois que quoi que ce soit est supprimé où que ce
soit, le mixin exécute un appel `Files.exists` sur le thread serveur.

Chaque flèche qui disparaît. Chaque objet ramassé. Chaque orbe d'expérience. Chaque Pokémon
qui tombe K.O. Chaque mob dans une ferme. Sur un serveur Cobblemon chargé, cela représente
des milliers de `stat` disque par seconde, de façon synchrone, sur le thread qui fait
tourner le jeu.

Le correctif qu'un contributeur devrait apporter est de garder en mémoire l'ensemble des
UUID du répertoire de sauvegarde au démarrage et de le mettre à jour à chaque écriture ou
suppression, ce qui transforme la vérification disque en consultation d'un ensemble. En
attendant : ce mod a un coût global mesurable, même s'il est généralement supportable.

## Couche 2 : les drapeaux d'entité par tick

Bon marché, et appliqués à chaque tick à chaque NPC de patrouille trouvé :

```java
mob.setPersistent();      // vanilla ne le fera jamais despawner
mob.fallDistance = 0;     // il ne subira jamais de dégâts de chute
```

`setPersistent` est le mécanisme vanilla pour « ce mob n'est pas jetable », le même drapeau
qu'obtient un mob nommé ou tenu en laisse. Il est aussi appliqué par le contrôle d'intégrité
des cinq minutes, donc le NPC d'une patrouille inactive l'obtient également.

La remise à zéro de `fallDistance` existe à cause des segments en vol : un NPC dont
`NoGravity` se coupe en altitude tombe, et sans cette remise à zéro la chute le tuerait.
Voir [les segments en vol](patrol-circuits.fr.md#les-segments-en-vol).

## Le force-load des chunks

Une entité dans un chunk déchargé ne tourne pas, ne marche pas, et ne peut pas être trouvée
par UUID. CheesePatrol force-load donc des chunks : de vrais chunks forcés vanilla, du même
type que ceux que crée `/forceload` et du même type que ceux qui persistent dans les données
du monde.

**Pendant qu'une patrouille tourne**, chaque tick maintient exactement deux chunks forcés
par NPC :

- le chunk dans lequel se tient le NPC
- le chunk du point de passage vers lequel il marche

À mesure que le NPC avance, les chunks qui sortent de cette paire sont relâchés. Deux chunks
par NPC en patrouille est un coût modeste et borné, et une patrouille qui franchit une
frontière de chunk charge le chunk suivant avant d'y arriver.

**À `SERVER_STARTED`**, avant que quoi que ce soit ait eu l'occasion de marcher, le mod en
force davantage :

| État de la patrouille | Chunks forcés au démarrage |
|---|---|
| Active, ≥2 points de passage | le chunk de la dernière position connue **plus tous les chunks des points de passage** |
| Tout le reste | le chunk de la dernière position connue uniquement |

```text
[CheesePatrol] Pre-loaded 7 chunks for 3 patrol(s)
```

La raison de forcer tous les chunks des points de passage d'une patrouille active est une
course : la patrouille est relancée cinq secondes après le démarrage, et le point de passage
qu'elle vise alors doit être chargeable à cet instant. La raison de forcer le chunk de la
dernière position connue même pour une patrouille inactive est plus simple — c'est là que se
trouve l'entité, et si ce chunk ne se charge jamais, l'entité ne s'enregistre jamais et le
contrôle d'intégrité se met à vouloir faire réapparaître quelque chose qui n'a jamais été
perdu.

**Quand un NPC disparaît**, le mod force-load le chunk de la dernière position connue à
chaque tick, jusqu'à ce qu'il réapparaisse ou que l'échelle de réapparition renonce. C'est
le mécanisme qui récupère habituellement un NPC : il n'était pas perdu, son chunk n'était
pas chargé.

**Les chunks sont relâchés** par `/npcpatrol stop`, par `/npcpatrol clear`, et au fur et à
mesure qu'une patrouille s'en éloigne.

### Ils ne sont délibérément pas relâchés à l'arrêt

`SERVER_STOPPING` sauvegarde les entités et écrit le fichier de données, et ne relâche
explicitement **pas** les chunks forcés. Le commentaire du code dit pourquoi :

> Do NOT release forced chunks — they persist in world data so entities stay loaded
> immediately on next restart (prevents despawn race condition)

C'est un compromis défendable, et il a deux conséquences à connaître :

- Ton monde porte en permanence des chunks forcés pour chaque patrouille. `/forceload query`
  les liste, et ils continuent de tourner (les mobs apparaissent, les cultures poussent,
  les entonnoirs bougent) aussi longtemps que la patrouille existe.
- **Retirer le mod sans effacer les patrouilles d'abord laisse ces chunks forcés à jamais**,
  puisqu'il ne reste rien pour les relâcher. La procédure de désinstallation dans
  [installation](installation.fr.md#désinstaller-proprement) couvre ce point.

`PatrolManager.releaseAllForcedChunks` existe pour le comportement inverse et **rien ne
l'appelle**. C'est du code mort, vestige d'avant cette décision.

### Le suivi des chunks d'une patrouille peut annuler ton propre `/forceload`

Un bug à connaître si tu force-loades des chunks à la main.

Quand une patrouille a besoin d'un chunk déjà forcé, le mod s'abstient de le forcer — à
raison, pour ne pas interférer. Mais il l'enregistre quand même comme l'un des siens. Plus
tard, quand le NPC s'éloigne et que le chunk n'est plus nécessaire, le chemin de libération
ne revérifie pas qui l'avait forcé et le relâche quand même.

Effet net : un `/forceload add` posé par un administrateur sur un chunk qu'un NPC de
patrouille traverse ensuite se fait annuler en silence quand le NPC repart. Si l'un de tes
forceload disparaît sans cesse, vérifie si un trajet de patrouille le croise.

## L'échelle de réapparition

Quatre couches de protection laissent encore un cas : l'entité a réellement disparu. Pour
celui-là, il y a les instantanés NBT.

### Les sauvegardes

`config/cheesepatrol_entities/<npc-uuid>.dat`, un par NPC de patrouille enregistré, sortie
de `entity.saveNbt` compressée en GZip. Écrits :

- toutes les **1200 ticks (60 secondes)**, pour chaque patrouille dont l'entité est
  actuellement trouvée — active ou inactive
- dès qu'un NPC précédemment manquant est retrouvé
- à `SERVER_STOPPING`

Supprimés par `/npcpatrol clear`, et supprimés automatiquement s'ils deviennent illisibles —
ce qui empêche un instantané corrompu d'entretenir une boucle de tentatives sans fin :

```text
[CheesePatrol] Corrupted backup for NPC a1b2c3d4 - deleting to prevent retry loop: <message>
```

Un instantané peut avoir jusqu'à une minute de retard : un NPC réapparu peut donc perdre
jusqu'à une minute de changements d'état EasyNPC. Pour un NPC de patrouille, cet état change
rarement, d'où le jugement qu'une minute suffisait.

### L'échelle elle-même

Pour une patrouille **active**, quand l'entité n'est pas trouvée, `tickPatrol` compte les
ticks :

| Écoulé | Ce qui se passe |
|---|---|
| chaque tick | force-loader le chunk du point de passage cible et celui de la dernière position connue |
| **600 ticks (30 s)** | tenter une réapparition depuis la sauvegarde, incrémenter le compteur de tentatives, réinitialiser le minuteur de 30 secondes |
| toutes les 30 s ensuite | une nouvelle tentative |
| **après 10 tentatives** (11ᵉ échéance de 30 secondes, ≈5 min 30) | renoncer : `active = false`, `pausedBySystem = true`, sauvegarder, journaliser une erreur |

```text
[CheesePatrol] NPC a1b2c3d4 failed to respawn 10 times - pausing patrol (integrity check retries every 5 min)
```

Renoncer n'est pas renoncer définitivement. `pausedBySystem = true` est le drapeau qui dit au
contrôle d'intégrité « ce n'était pas une décision humaine », et le contrôle d'intégrité
continue de réessayer toutes les cinq minutes indéfiniment, réactivant la patrouille dès que
le NPC existe à nouveau. `/npcpatrol stop` met fin à la reprise automatique (il remet
`pausedBySystem` à faux), mais le contrôle d'intégrité continue de faire réapparaître un
NPC manquant depuis sa sauvegarde ; seul `/npcpatrol clear` arrête les tentatives.

Le compteur se remet entièrement à zéro, ticks manquants *et* tentatives, au premier tick
où l'entité est trouvée.

### Ce que fait une réapparition

1. Lire `<uuid>.dat`
2. Résoudre la dimension de la patrouille. Une dimension absente est un échec sec :
   `Cannot respawn NPC a1b2c3d4 - dimension 'cheeseguard:arena_01' not found`
3. `EntityType.loadEntityWithPassengers` pour reconstruire l'entité, puis `world.spawnEntity`
4. La téléporter sur le **point de passage courant**, pour qu'elle rejoigne son circuit
   plutôt que de traverser la carte depuis l'endroit où l'instantané a été pris
5. Réappliquer `NoGravity` si ce point de passage est un point en vol, remettre la distance
   de chute à zéro, la rendre persistante, et relancer la patrouille

Si `spawnEntity` renvoie `false`, la réapparition est abandonnée :

```text
[CheesePatrol] spawnEntity returned false for NPC a1b2c3d4 - UUID conflict or invalid NBT
```

Un conflit d'UUID est l'issue sûre : cela signifie que l'entité existe bien dans un monde
chargé, et faire apparaître un second exemplaire serait pire que de ne rien faire.

⚠️ **Le risque que le code ne couvre pas.** Le contrôle de conflit ne voit que les mondes
chargés. Si le NPC d'origine se trouve dans un chunk *déchargé* et que la réapparition
réussit, deux entités revendiquent désormais le même UUID — une dans le monde, une sur
disque. Quand ce chunk finira par se charger, la gestion vanilla des UUID en doublon en
supprimera une, et laquelle n'est pas quelque chose que ce mod contrôle. Garder le bon chunk
force-loadé est ce qui rend ce cas improbable, et c'est en partie pourquoi le force-load est
si agressif.

## Le contrôle d'intégrité

Le filet sous le filet, parce que l'échelle de réapparition ne tourne que pour les
patrouilles actives. Il s'exécute :

- **une fois, 100 ticks (5 secondes) après le démarrage du serveur** — au même instant,
  chaque patrouille active ayant au moins deux points de passage est relancée, parce que des
  entités chargées avec leurs goals EasyNPC intacts n'avanceront pas tant que ces goals ne
  sont pas effacés
- **toutes les 6000 ticks (5 minutes)** ensuite

Pour chaque patrouille, active ou non :

| État de l'entité | Action |
|---|---|
| trouvée | la rendre persistante ; si la patrouille était en pause système et a ≥2 points de passage, la reprendre et la relancer |
| absente, aucune sauvegarde | journaliser un avertissement (sous `debug`) et passer — rien ne peut être fait |
| absente, sauvegarde présente | force-loader le dernier chunk connu, faire réapparaître, rendre persistante, remettre les compteurs à zéro ; reprendre la patrouille si elle était en pause système |

Le fichier de données entier est sauvegardé si quelque chose a changé.

Cette première exécution est ce qui fait reprendre les patrouilles d'elles-mêmes après un
redémarrage. Avec `debug: true` :

```text
[CheesePatrol] Startup: kick-started 3 active patrols
```

## Lire le système de résilience dans le log

Avec `debug: true`, un cycle complet de récupération ressemble à ceci :

```text
[CheesePatrol] NPC a1b2c3d4 not found after 30s - respawn attempt 1/10
[CheesePatrol] Respawned NPC a1b2c3d4 at waypoint 128, 65, -340
[CheesePatrol] Kick-started patrol for NPC a1b2c3d4 at waypoint 3/4
```

Le kick-start annonce le point de passage *qui suit* celui où le NPC a réapparu : la
réapparition le pose exactement sur le point #2, et un kick-start à moins de 1,5 bloc de sa
cible fait avancer.

Un cycle raté, qui finit en pause système :

```text
[CheesePatrol] NPC a1b2c3d4 not found after 30s - respawn attempt 1/10
[CheesePatrol] No backup found for NPC a1b2c3d4
…
[CheesePatrol] NPC a1b2c3d4 failed to respawn 10 times - pausing patrol (integrity check retries every 5 min)
```

Et une récupération venue du chargement du chunk plutôt que d'une réapparition :

```text
[CheesePatrol] NPC a1b2c3d4 found after 412 ticks missing - kick-starting patrol
```

Cette dernière ligne est la plus fréquente, et c'est la raison d'être du force-load : la
plupart des NPC « perdus » ne l'avaient jamais été.

---

← [Rendu visuel](visuals.fr.md) · [API dashboard](dashboard.fr.md) →
