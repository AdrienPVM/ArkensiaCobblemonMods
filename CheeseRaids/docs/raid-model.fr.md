[← CheeseRaids](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](raid-model.md)

# Le modèle de raid

CheeseRaids se présente comme un framework, ce qui soulève une question légitime : quelle
part en est réellement générique, et quelle part n'est que l'Invasion de Hoopa déguisée en
framework ? Cette page y répond honnêtement, parce que la réponse détermine si écrire un
second raid relève de la configuration ou du code.

> ⚠️ Aucun raid n'a jamais été joué. Le modèle décrit ci-dessous est ce que le code
> implémente, pas une conception validée.

---

## Ce qu'est un raid

Un raid, ce sont deux objets.

Une **définition** (`RaidDefinition`) est de la configuration statique : un fichier JSON dans
`config/cheeseraids/raids/`, un objet en mémoire, indexé par son `id`. Elle décrit l'annonce,
la vague, le héraut, l'arène, le boss, les récompenses et la planification. C'est de la
donnée pure avec des défauts Java ; Gson la désérialise directement dans des champs publics.

Une **instance** (`RaidInstance`) est l'état d'exécution d'une session : la phase courante,
son heure de départ, les joueurs présents, les slots qu'ils occupent, les UUID d'entités de
la vague, l'entité et l'identifiant de combat du boss, les échéances. Il existe au plus une
instance par identifiant de raid. Elle est persistée dans
`config/cheeseraids/instances/<raidId>.json` à chaque changement de phase et toutes les 100
ticks, et rechargée au démarrage du serveur : un crash en pleine raid reprend au lieu
d'abandonner une dimension pleine de joueurs.

`RaidManager` possède les deux tables et un unique `tick()` que le serveur appelle à la fin
de chaque tick. Il n'y a ni thread par raid, ni exécuteur planifié : tout se passe sur le
thread serveur.

## Phases

```text
IDLE → ANNOUNCE → OVERWORLD_WAVE → NPC_REVEAL → OPEN → COMBAT → REWARD → CLEANUP → IDLE
```

La progression est strictement linéaire. Il n'y a pas d'état `PAUSED`, et aucun retour en
arrière.

| Phase | Entrée quand | Sortie quand |
|---|---|---|
| `ANNOUNCE` | `start` est appelée, ou le planificateur déclenche | `wave.graceSecondsBeforeWave` s'est écoulé |
| `OVERWORLD_WAVE` | la vague apparaît | l'alpha meurt, toute la vague est nettoyée, zéro mob est apparu, ou `wave.timeoutSeconds` expire |
| `NPC_REVEAL` | juste après la vague | le même tick — cette phase n'attend pas |
| `OPEN` | la faille est ouverte | quelqu'un est entré **et** (tout le monde est arrivé, ou `boss.graceSeconds` sont passées depuis la première arrivée, ou `arena.openTimeoutSeconds` a expiré) |
| `COMBAT` | l'apparition du boss commence | le boss est vaincu, ou `boss.combatTimeoutSeconds` expire |
| `REWARD` | le boss est tombé | 4,5 secondes plus tard |
| `CLEANUP` | toujours | le même tick |

Deux de ces transitions méritent une seconde lecture.

`NPC_REVEAL` passe à `OPEN` dans le tick même où elle est entrée. Elle n'existe que pour
exécuter une chose — compter les hérauts, et lancer soit l'animation de révélation, soit
l'annonce de la commande de secours — puis s'effacer. Le crescendo de cinq secondes du héraut
se déroule pendant `OPEN`, piloté par une file d'effets séparée et non par la machine à
états.

`OPEN` peut arriver en timeout de deux façons distinctes. Si `openDeadline` passe et que
**personne** n'est entré, le raid avorte directement vers `CLEANUP`, sans boss ni récompense.
Si quelqu'un est entré, la même échéance force l'apparition du boss, que quiconque ait
atteint le centre ou non.

`COMBAT` est entrée *avant* que le boss existe. La transition met en file une séquence
d'apparition de 44 ticks, et le boss apparaît réellement au tick 36 de celle-ci.
`inst.bossSpawned` reste `false` jusque-là, et le test de victoire renvoie `false` tant qu'il
l'est : la montée en puissance ne peut donc pas être prise pour une victoire instantanée.

### Qui pilote quoi

Trois choses avancent en parallèle pendant un raid, et c'est leur séparation qui empêche un
bug cosmétique de bloquer la machine à états :

- **la machine à états** — `advance()`, un unique `switch` sur la phase, à chaque tick ;
- **la file d'effets** — une liste de `FxTask`, chacune une séquence cinématique multi-tick
  avec son propre compteur d'âge. Une exception dans l'une est attrapée, journalisée, et la
  tâche abandonnée. C'est là que vivent les apparitions, et c'est pourquoi l'apparition du
  boss se trouve au cœur d'une séquence cosmétique ;
- **la file de révélation** — `StarArenaGenerator.tick()`, qui place les blocs d'une branche
  quelques-uns par tick, avec particules et hauteur de son croissante.

## Ce qui est générique et ce qui est Hoopa

La frontière ne passe pas là où les noms de classes le suggèrent.

**Réellement générique**, entièrement piloté par le fichier de définition :

- la machine à états et ses timeouts ;
- le planificateur, l'horodatage anti-rejeu, la persistance des instances et la récupération
  après crash ;
- la vague : espèces, aspects, niveaux, nombres, quelle entrée est l'alpha, dispersion,
  orage ;
- les dimensions de l'arène en étoile : rayon, nombre de slots, cadence des checkpoints,
  palette de blocs, vitesse de révélation, seuil de chute ;
- l'espèce du boss, ses aspects, la progression de niveau, sa taille et ses timeouts ;
- le nombre de récompenses, la liste de commandes par gagnant et le montant de monnaie ;
- les cinq positions.

**Codé en dur, et teinté Hoopa quoi que tu mettes dans le fichier :**

- **chaque texte destiné aux joueurs.** Les annonces de phase, les titres et les sous-titres
  sont des littéraux français dans `RaidManager` : « L'Alpha Hoopa s'enfuit ! », « Le héraut
  Ho-Oh surgit dans un éclair ! », « La faille de Hoopa s'ouvre ! », « Le gardien de la
  faille SURGIT ! », « VICTOIRE ! ». Le bloc `announce` de la définition ne contrôle que
  l'annonce d'ouverture. Un second raid s'annoncerait comme une faille de Hoopa ;
- **l'identité visuelle.** Particules de portail et de portail inversé, couronnes de barres
  de l'End, sons de téléportation d'enderman et de portail de l'End, palette de feux
  d'artifice or et magenta, anneau de Hoopa flottant construit au-dessus de chaque plateforme
  de départ ;
- **la conception du parkour.** Treize styles de saut, cinq mécaniques et cinq zones nommées
  (`amethyst_caverns`, `frostbridge`, `mosscaverns`, `plunge_gorge`, `crystal_spires`),
  chacune avec sa palette de blocs et sa pondération de styles, sont des tables statiques
  dans `StarArenaGenerator` ;
- **la stratégie de boss.** `BossProvider` est une interface avec une seule implémentation, et
  `forDefinition()` ignore totalement le champ `boss.provider` ;
- **la forme de l'arène.** `arena.model` propose `SHARED` et `PER_PLAYER` ; rien ne lit cette
  clé. Chaque raid est une faille partagée unique avec une branche par joueur.

Donc : un second raid avec d'autres Pokémon, d'autres niveaux, d'autres positions, une autre
longueur de parkour et d'autres récompenses est un fichier de configuration. Un second raid
qui ne ressemble ni ne sonne comme une invasion de Hoopa est un changement de code.
L'abstraction de framework est réelle au niveau du cycle de vie et cosmétique au niveau de la
présentation.

## L'arène en étoile

La faille est une dimension vide (*void*) CheeseGuard, créée à la demande la première fois
qu'un joueur entre, avec `enabled = false` pour que personne n'y pénètre hors d'un raid. En
son centre se trouve un disque plat de rayon `arena.centerRadius` — l'arène du boss,
construite une fois par raid.

Chaque joueur reçoit une **branche** : un parcours de parkour qui part d'une plateforme de
départ située à `arena.starRadius` blocs et rentre vers le disque. Les branches reçoivent des
slots angulaires, espacés de `2π / arena.maxSlots` : douze joueurs forment donc une étoile à
douze branches.

La géométrie est une **fonction pure de (définition, slot, graine)**. La graine vient de
l'UUID du joueur, mélangé à l'identifiant du raid et au slot. Rien de la disposition n'est
stocké : le nettoyage, le suivi des checkpoints et la réapparition la recalculent tous. C'est
ce qui rend une reconnexion sûre — recalculer donne le chemin identique, donc le joueur est
remis sur les blocs qu'il avait quittés.

Les plateformes sont de deux sortes. La plateforme de départ, les checkpoints et la plateforme
finale sont des dalles 3×3 avec une jupe en dessous et un marqueur lumineux. Tout ce qui se
trouve entre elles est une unique dalle 1×1 posée sur une structure thématique strictement
sous le plan d'atterrissage : rien ne peut donc empiéter sur un couloir de saut.

Les sauts sont choisis dans une table de styles pondérée, biaisée par celle des cinq zones où
tombe l'étape, puis bridés : chaque mécanique déclare un plafond de portée pour le changement
de hauteur obtenu, et un candidat qui le dépasse est ramené en arrière. Les montées sont
limitées à +1 bloc. Les commentaires du code indiquent que le résultat a été vérifié par une
simulation externe (`temp/sim_parkour_v3.py`, absente de ce dépôt) rapportant zéro saut
impossible. **Aucun joueur ne l'a sauté.**

La progression est supervisée à chaque tick par `CheckpointTracker`, pour chaque joueur entré
mais pas encore arrivé :

- sous `center.y - arena.fallBelow` → téléporté au dernier checkpoint, vitesse et distance de
  chute remises à zéro ;
- à moins de 2,2 blocs horizontalement et 2 verticalement d'un checkpoint → ce checkpoint est
  enregistré, et un message de barre d'action part une fois par nouveau checkpoint ;
- à moins de `centerRadius - 0.5` horizontalement et 1,5 verticalement de `center.y + 1` →
  `arrived`. Le test est volontairement strict : se tenir sur la dernière plateforme n'est pas
  arriver, il faut être sur le disque.

## Écrire un second raid

Copie `hoopa.json`, change l'`id`, change le nom du fichier en conséquence, et édite. Il n'y a
aucune étape d'enregistrement : `loadDefinitions()` lit chaque `*.json` du dossier. `start` et
le planificateur le traitent ensuite exactement comme le premier.

Ce que tu peux changer librement : espèces et niveaux, positions, planification, rayon du
parkour et nombre de slots, palette de blocs, taille et progression du boss, récompenses.

Ce sur quoi tu vas butter : les textes et l'identité des particules, listés plus haut. S'il te
faut un raid qui ne soit pas une faille dimensionnelle déchirée par Hoopa, les littéraux de
`RaidManager` sont ton point de départ.

Un point d'extension existe et n'est pas utilisé. `BossProvider` est une interface propre à
cinq méthodes — apparition, capture en cours, tick de capture, vaincu, nettoyage, plus deux
crochets de tick avec implémentation par défaut. Écrire un second fournisseur est
simple ; le brancher demande de modifier `BossProvider.forDefinition()` pour qu'il lise
réellement `def.boss.provider`, ce qu'il ne fait pas aujourd'hui.

---

← [Calibration](calibration.fr.md) · [L'Invasion de Hoopa](hoopa-invasion.fr.md) →
