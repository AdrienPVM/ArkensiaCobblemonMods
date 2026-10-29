[← CheeseRaids](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](hoopa-invasion.md)

# L'Invasion de Hoopa

Le premier et pour l'instant unique raid livré avec le mod. Cette page décrit la séquence
**telle que le code l'exécute**, étape par étape, avec les manques signalés.

> ⚠️ Elle n'a jamais été jouée. Chaque durée, message et effet ci-dessous a été lu dans les
> sources, pas observé en jeu.

---

## La séquence, telle qu'elle est codée

### 1. L'annonce (`ANNOUNCE`)

`/cheeseraids start hoopa`, ou le planificateur, crée l'instance et immédiatement :

- diffuse `announce.chat` dans le chat, en mauve clair ;
- envoie `title @a title` avec `announce.title` en `light_purple`, et `title @a subtitle`
  avec `announce.subtitle` en gris ;
- écrit la ligne Discord dans le **journal du serveur** — il n'y a pas de webhook (voir la
  [configuration](configuration.fr.md#announce)) ;
- masque le héraut, pour qu'un joueur posté près du NPC ne le voie pas avant la révélation ;
- force `weather thunder 1200` et `time set midnight` dans le monde de la vague, si
  `wave.overworldStorm` est actif. L'orage est réappliqué toutes les 600 ticks pendant tout le
  raid, parce que la commande vanilla ne dure qu'une soixantaine de secondes.

Puis il attend `wave.graceSecondsBeforeWave` secondes, cinq par défaut.

### 2. La vague (`OVERWORLD_WAVE`)

Chaque entrée de `wave.spawns` apparaît `count` fois, en `wave.arena.y + 1`, dispersée au
hasard dans ±`wave.spread` en X et Z. Chacune est créée comme une entité Cobblemon
**incapturable** et persistante au sens vanilla. La persistance compte : sans elle, le
désapparitionneur de Cobblemon ferait vieillir la vague en plein combat.

La vague livrée, c'est un Hoopa Déchaîné niveau 80 et quatre Ténéfix niveau 60.

La première entrée marquée `leader: true` devient l'**alpha**. Il apparaît à l'échelle 1,8,
reçoit un contour lumineux permanent, et est suivi par son propre UUID. Toutes les cinq ticks,
la vague dessine des particules de repérage : un anneau de portail qui tourne, des flammes
d'âme et une couronne de barres de l'End autour de l'alpha, des particules de sorcière et des
barres de l'End autour des autres, pour que les joueurs les retrouvent dans l'orage.

La phase se termine au premier de ces événements :

- **l'alpha meurt.** C'est la fin prévue. Un titre à l'échelle du serveur part — « L'Alpha
  Hoopa s'enfuit ! » / « Il s'enfouit dans sa dimension... la faille s'ouvre ! » — et l'effet
  d'ouverture de faille se joue à l'arène. Les sbires encore vivants sont écartés ;
- **tout est mort**, testé toutes les `wave.checkIntervalTicks` ticks ;
- **aucun mob n'est apparu.** Le raid journalise un avertissement et continue plutôt que de
  rester bloqué ;
- **`wave.timeoutSeconds` expire**, 180 par défaut. La vague est écartée et le raid continue.

Tuer l'alpha est donc un raccourci, pas une obligation, et une vague que personne ne combat
avance quand même au bout de trois minutes. Le seul cas où le raid se bloque ici, c'est si le
monde désigné par `wave.arena.world` n'existe pas : l'apparition est alors sautée entièrement
et la phase tombe dans son timeout.

### 3. Le héraut (`NPC_REVEAL` → `OPEN`)

La dimension de faille est créée si elle n'existe pas. Puis le mod compte les entités EasyNPC
situées dans les 10 blocs de `npc.pos`, sans les révéler.

**Si au moins une est trouvée**, un crescendo d'environ 102 ticks est mis en file et la phase
avance immédiatement. Le crescendo se déroule pendant `OPEN` :

| Tick | Ce qui se passe |
|---|---|
| 0 | l'effet de portail d'ouverture de faille à l'arène de vague, plus un cor de chèvre grave |
| 0–59 | trois secondes de montée : ambiance de caverne toutes les 12 ticks, particules de portail qui s'accumulent |
| 60–97 | particules de totem et de barre de l'End en spirale rentrante autour du NPC encore invisible, hauteur du son de balise croissante |
| 80, 90 | deux éclairs cosmétiques avec tonnerre |
| 100 | **le point culminant.** L'EasyNPC devient visible, un troisième éclair tombe, un flash et 120 particules de totem éclatent, trois sons partent ensemble, et le chat reçoit « Le héraut Ho-Oh surgit dans un éclair ! Parlez-lui pour entrer dans la faille. » |
| 100–112 | des flammes s'attardent autour du NPC |

Les trois secondes d'amorce sont volontaires : elles évitent que le héraut arrive par-dessus
l'effet d'ouverture de la faille.

**Si aucune n'est trouvée**, le raid ne s'arrête pas net. Il journalise un avertissement, joue
quand même l'effet d'ouverture, et diffuse le chemin de secours par commande :

```text
La faille de Hoopa s'ouvre ! Tapez /cheeseraids enter hoopa pour entrer.
```

Cette dégradation en douceur est le bon choix, mais note ce qu'elle implique : un héraut mal
configuré n'a pas l'air cassé vu de la place d'un joueur. Vérifie la console, ou lance
`/cheeseraids herald hoopa info` avant le raid.

**Ce que le héraut ne fait pas.** Le chat dit *parlez-lui*. CheeseRaids ne contient aucune
gestion de dialogue, aucun écouteur d'interaction et aucune liaison d'action EasyNPC. Le mod
rend le NPC visible et luisant ; câbler son dialogue pour qu'il lance
`/cheeseraids enter hoopa` est quelque chose que tu configures **dans EasyNPC**, à la main,
sur ce NPC. Rien dans ce dépôt ne le fait pour toi, et rien ne vérifie que tu l'as fait.
C'est le plus grand écart entre ce que le raid promet aux joueurs et ce que le code livre.

### 4. La faille (`OPEN`)

Les joueurs entrent — par le dialogue du héraut si tu l'as configuré, sinon avec
`/cheeseraids enter hoopa`. Chacun réclame un slot et obtient sa propre branche de parkour,
générée depuis une graine dérivée de son UUID.
[Le modèle de raid](raid-model.fr.md#larène-en-étoile) couvre la disposition ; ce qu'un joueur
vit, c'est :

1. il arrive sur une plateforme de purpur 3×3 sous un anneau de Hoopa flottant or et magenta,
   tourné vers l'intérieur, à 75 blocs du centre ;
2. pendant trois secondes, une colonne de portail se resserre autour de lui sur un son de
   balise de plus en plus aigu ;
3. puis le chemin se matérialise bloc par bloc devant lui, huit blocs par tick, avec des
   particules à chacun, un faisceau qui court jusqu'au front de construction, et une gamme
   musicale qui monte de 0,9 à 1,9 sur la longueur du chemin ;
4. quand le dernier bloc se pose, un bouquet final à l'autre extrémité raccorde le chemin au
   disque du boss.

Il saute vers l'intérieur à travers cinq zones thématiques — cavernes d'amétriste, pont de
glace, cavernes de mousse et de toiles, gorge de chute, aiguilles de cristal. Tomber sous
`center.y - arena.fallBelow` le remet sur son dernier checkpoint. Les checkpoints arrivent
toutes les `arena.checkpointEvery` plateformes, six par défaut, et s'annoncent dans la barre
d'action : « ✔ Checkpoint atteint ! »

Atteindre le disque donne « Vous avez atteint le cœur de la faille ! » et marque le joueur
`arrived`.

La phase se termine quand au moins un joueur est entré et que l'une de ces conditions est
vraie : tous ceux qui sont entrés sont arrivés ; `boss.graceSeconds` sont passées depuis la
**première** arrivée ; ou `arena.openTimeoutSeconds` sont passées depuis le début de la phase.
Si le timeout d'ouverture passe et que personne n'est entré du tout, le raid avorte vers le
nettoyage.

### 5. Le boss (`COMBAT`)

Le chat reçoit « Quelque chose d'immense s'éveille au cœur de la faille... » et une apparition
de 44 ticks démarre au centre du disque :

| Tick | Ce qui se passe |
|---|---|
| 0 | ambiances de portail de l'End et de Wither |
| 0–33 | un anneau de particules de portail inversé qui s'élargit, des flammes d'âme qui montent, un grondement de dragon de plus en plus aigu |
| 18, 28 | éclairs cosmétiques avec tonnerre |
| 36 | **le boss apparaît.** Une seconde salve, un éclair, un rugissement de Wither, dans le chat « ⚡ Le gardien de la faille SURGIT ! ⚡ », et un titre envoyé aux seuls joueurs réellement dans la faille |

Avant l'apparition, le fournisseur creuse une poche d'air au-dessus du disque, dimensionnée
depuis `boss.baseScale` — demi-largeur `ceil(2 + échelle × 2)`, hauteur
`ceil(6 × échelle) + 3` — et force le chargement du chunk central. Les deux existent à cause
d'échecs réels : un Hoopa Déchaîné à 1,5× mesure une dizaine de blocs et s'asphyxiait
instantanément dans une poche fixe de 5×5×6, et le chunk central de la faille se décharge
pendant que tous les joueurs sont à 75 blocs sur le parkour, ce qui faisait refuser
`spawnEntity` tout net.

Le boss est un unique Pokémon incapturable et persistant, au niveau
`boss.baseLevel + (participants - 1) × boss.levelPerExtraPlayer`, mis à l'échelle par
`boss.baseScale` appliquée *avant* le paquet d'apparition, pour que chaque client voie la
taille immédiatement. Une aura tourbillonnante de feu d'âme est dessinée autour de lui toutes
les trois ticks.

**Il se combat comme un combat Cobblemon ordinaire.** Il n'y a pas de mécanique de raid, pas
de barre de vie partagée, pas de coordination de tours entre joueurs. « Coopératif » veut dire
que plusieurs joueurs se tiennent sur le même disque ; le premier à engager récupère le
combat. C'est le deuxième grand écart entre l'intention et le code.

La détection de la victoire est volontairement prudente, parce que le test évident est faux.
Gagner un combat Cobblemon retire le Pokémon sauvage environ deux secondes **après** la fin du
combat, et téléporter les joueurs pendant cette fenêtre était visiblement cassé. Le contrat est
donc : l'entité a disparu, **et** soit un identifiant de combat a été capturé et ce combat
n'est plus enregistré, soit aucun combat n'a été capturé mais le boss a survécu plus de cinq
secondes. Un boss qui disparaît en moins de cinq secondes sans combat est traité comme un échec
d'apparition, pas comme une victoire, et il réapparaît — jusqu'à deux fois, dans les 200 ticks
suivant l'apparition.

La phase se termine à la victoire, ou sur `boss.combatTimeoutSeconds` (900 par défaut), ce qui
éjecte tout le monde et nettoie **sans récompense**.

### 6. Les récompenses (`REWARD`)

Les joueurs encore dans la faille reçoivent la luminescence pendant dix secondes, la
régénération II pendant cinq, la chute lente pendant dix, un titre doré « 🏆 VICTOIRE ! » et
« La faille s'effondre — l'invasion est repoussée ! », avec des particules de totem et deux
sons. Les étincelles continuent toutes les quatre ticks.

4,5 secondes plus tard :

1. tout le monde est téléporté dehors, vers son propre `returnPos` s'il est renseigné, sinon
   vers `exitPos` ;
2. `floor(participants / reward.portalDivisor)` portails, au moins `reward.minPortals`,
   s'ouvrent sur un cercle de rayon `reward.portalSpacing` autour de `reward.portalAnchor`,
   avec deux feux d'artifice à chacun. Ce sont des portails de donjon « Hoopa Ring » de
   CobbleSafari, atteints par réflexion — voir
   [les intégrations](integrations.fr.md#cobblesafari) ;
3. trois feux d'artifice et un son de lancement au-dessus de chaque gagnant, où qu'il se
   trouve désormais ;
4. `reward.fromage` est ajouté au portefeuille CheeseStore de chaque gagnant, si le montant
   est non nul et si CheeseStore est installé ;
5. chaque entrée de `reward.commands` s'exécute une fois par gagnant, avec `{player}`
   substitué.

« Gagnant » désigne ici tout joueur de l'instance connecté à cet instant. Il n'y a aucune
vérification qu'il soit arrivé, encore moins qu'il ait combattu. Un joueur qui est entré dans
la faille, est tombé cinquante fois du parkour et n'a jamais atteint le disque est récompensé
exactement comme celui qui a battu le boss.

### 7. Le nettoyage (`CLEANUP`)

Météo dégagée au-dessus de l'arène de vague ; la branche de chaque joueur recalculée et
remplie d'air ; le volume de l'arène du boss vidé et ses chunks forcés libérés ; mobs de vague
et boss écartés ; héraut masqué ; files d'effets et de révélation vidées ; instance retirée de
la mémoire et son fichier JSON supprimé. La dimension de faille elle-même n'est **pas**
supprimée — elle reste, vide et désactivée, et le prochain raid y reconstruit l'arène.

L'heure du jour n'est pas rétablie. `overworldStorm` l'avait mise à minuit ; le nettoyage ne
dégage que la météo.

---

## Ce qui est incomplet

Rassemblé au même endroit, parce que la séquence ci-dessus se lit mieux que le mod ne se joue.

| Manque | Conséquence |
|---|---|
| Le héraut n'a pas de dialogue | le NPC apparaît et luit, mais rien n'en fait un point d'entrée. Tu dois câbler son dialogue sur `/cheeseraids enter <id>` dans EasyNPC toi-même |
| Le combat de boss n'a pas de mécanique de raid | un combat Cobblemon ordinaire. Plusieurs joueurs sur un disque, un seul combat |
| Les récompenses ne dépendent pas de la participation | tout joueur connecté à la mort du boss est récompensé, arrivé ou non |
| `stop` n'éjecte pas les joueurs | un arrêt manuel laisse les joueurs dans une dimension dont le sol est en train d'être supprimé |
| Les annonces Discord sont une ligne de journal | `announce.discord` n'envoie rien nulle part |
| Neuf clés de configuration ne font rien | listées dans la [configuration](configuration.fr.md) |
| Rien n'est traduit | chaque texte destiné aux joueurs est un littéral français dans le Java |
| Quatre hooks par réflexion | EasyNPC, l'apparition Cobblemon, les combats Cobblemon et CobbleSafari passent tous par de la réflexion. Chacun peut casser à une mise à jour de dépendance, et trois des quatre se dégradent en silence |

Rien de tout cela ne rend le raid injouable sur le papier. Mais tout cela explique pourquoi le
mod est marqué expérimental.

---

← [Le modèle de raid](raid-model.fr.md) · [Intégrations externes](integrations.fr.md) →
