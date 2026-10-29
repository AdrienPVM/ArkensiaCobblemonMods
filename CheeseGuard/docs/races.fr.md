[← CheeseGuard](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](races.md)

# Courses

Une course est un événement mini-jeu de type `RACE` dont le circuit a été construit dans le
monde puis *scanné* pour devenir une configuration. Tu places des blocs colorés pour la ligne
de départ, les points de contrôle et les bonus, tu dis à CheeseGuard quel bloc signifie quoi,
et il remplit la zone par propagation pour transformer ces blocs en régions avec boîtes
englobantes et centres. À partir de là, franchir un point de contrôle est un test géométrique
contre une région scannée, pas une recherche de zone.

Trois pièces : `RacesApi` (`dashboard/RacesApi.java`) expose le scan et la configuration du
circuit, `RaceTrackScanner` (`logic/RaceTrackScanner.java`) fait le scan, et `RaceTrackConfig`
(`data/RaceTrackConfig.java`) est le résultat stocké. La course elle-même tourne dans
`MiniGameManager`.

Les configurations de circuit vivent dans un fichier par événement :

```text
config/cheeseguard_racetracks/<eventId>.json
```

## Construire un circuit

1. Crée une zone qui englobe tout le circuit — voir [Zones et flags](zones-and-flags.fr.md).
   Le scan est borné par cette zone : tout ce qui est à l'extérieur est invisible pour la
   course.
2. Place les blocs repères. N'importe quel type de bloc convient ; ce qui compte, c'est que
   chaque rôle ait son propre bloc. Un ensemble qui marche : laine bleue pour les points de
   contrôle, bloc d'or pour la ligne de départ, un bloc coloré par type de bonus.
3. Scanne, avec `POST /races/scan`, en passant l'identifiant de zone et la correspondance
   bloc → repère.
4. Ordonne les points de contrôle, avec `PUT /races/:eventId/track` et un tableau
   `checkpointOrders`.
5. Crée un événement `MINIGAME` avec `miniGameType: "RACE"` et le même identifiant que la
   configuration de circuit, puis lance-le comme n'importe quel événement
   ([Événements](events.fr.md)).

### La correspondance

```json
{
  "zoneId": "circuit_arkensia",
  "eventId": "evt_41200",
  "blockMappings": {
    "minecraft:blue_wool": "checkpoint",
    "minecraft:gold_block": "start",
    "minecraft:lime_wool": "boost",
    "minecraft:redstone_block": "trap"
  }
}
```

Deux noms de repère sont réservés : `checkpoint` et `start`. **Toute autre valeur est traitée
comme une clé de bonus**, sans aucune validation — une faute de frappe comme
`"checkpointt"` devient silencieusement un type de bonus nommé `checkpointt`. Le scan refuse
une correspondance sans entrée `checkpoint` :

```json
{"error":"blockMappings must contain at least one 'checkpoint' mapping"}
```

Un identifiant de bloc que le registre ne connaît pas est ignoré avec une ligne de log et ne
fait pas échouer le scan :

```text
[CG] Race scan: unknown block in mappings: minecraft:blue_wolo
```

Passer `eventId` fait enregistrer le scan directement dans la configuration de circuit de cet
événement. L'omettre donne un essai à blanc : le résultat revient en JSON et rien n'est écrit.

### Ce que fait le scan

`RaceTrackScanner.scan()` tourne sur le thread serveur, puisqu'il lit des états de blocs.
`RacesApi` le planifie et attend le résultat jusqu'à 30 secondes ; au-delà, la requête échoue
en `500 Scan failed: ...` même si le scan finit par aboutir.

L'algorithme :

1. Résoudre une fois chaque identifiant de bloc en objet `Block`, pour une comparaison
   d'identité rapide.
2. Refuser la zone si son volume dépasse 50 millions de blocs :
   `Zone too large for scanning: <n> blocks (max 50M). Reduce zone bounds.`
3. Parcourir toutes les positions dans les limites de la zone, en sautant l'air, et collecter
   celles dont le bloc figure dans la correspondance.
4. Remplir par propagation (BFS sur les six faces voisines), en regroupant les blocs adjacents
   du **même** type de repère en une `MarkerRegion` avec boîte englobante et centre.
5. Trier les régions : les groupes `checkpoint` dans la liste des points de contrôle, `start`
   dans la ligne de départ, tout le reste dans les bonus.
6. Trier les points de contrôle par X du centre, puis par Z, ce qui donne un ordre par défaut
   déterministe mais géographiquement arbitraire.

L'adjacence diagonale **ne** fusionne **pas** un groupe. Deux blocs de point de contrôle qui ne
se touchent que par un coin sont deux points de contrôle. C'est généralement ce que tu veux
pour une rangée de portiques séparés, et c'est un piège si tu as construit un ruban en
diagonale.

Si plus d'un groupe `start` est trouvé, le premier gagne et les autres sont jetés :

```text
[CG] Race scan: multiple start line groups found, using first one
```

La réponse rend compte du travail effectué :

```json
{
  "checkpoints": [ { "markerType": "checkpoint", "order": -1, "min": [...], "max": [...], "center": [...] } ],
  "powerups": [ ... ],
  "startLine": { ... },
  "totalBlocksScanned": 2450000,
  "markerBlocksFound": 312,
  "durationMs": 1840,
  "zoneBounds": { "minX": 0, "minY": 60, "minZ": 0, "maxX": 200, "maxY": 80, "maxZ": 200, "world": "minecraft:overworld" }
}
```

`totalBlocksScanned` compte toutes les positions visitées, l'air inclus : une zone haute le
fait exploser. Une emprise de 200×200 sur 20 blocs de hauteur fait 800 000 positions ; la même
emprise sur toute la hauteur du monde en fait 24 millions. Garde la plage Y de la zone serrée
autour du circuit — c'est le facteur de loin le plus déterminant pour le temps de scan.

### Rescanner

Rescanner est le flux normal : tu déplaces un portique, tu rescannes. `applyToConfig()` essaie
de préserver l'ordre déjà défini, en appariant chaque nouveau point de contrôle aux anciens sur
la distance horizontale — à moins de 5 blocs (distance au carré sous 25), l'ancien `order` est
repris. Y est ignoré dans cet appariement : deux points de contrôle superposés verticalement et
distants de moins de 5 blocs horizontalement peuvent donc hériter de l'ordre l'un de l'autre.
Revois l'ordre après avoir déplacé un portique en hauteur.

Les points de contrôle qui n'apparient rien gardent `order: -1`, c'est-à-dire non ordonnés.

## La carte de relief

`POST /races/heightmap` rend la zone vue d'en haut pour que le dashboard puisse dessiner une
carte 2D avec les points de contrôle par-dessus.

Pour chaque colonne `(x, z)`, il trouve le bloc non-air le plus haut doté d'une vraie couleur
de carte, en partant du heightmap vanilla `WORLD_SURFACE` comme optimisation, et prend la
couleur de carte de ce bloc. Une seconde passe ombre chaque pixel en comparant sa hauteur à
celle de son voisin au nord — 12 % plus clair s'il est plus haut, 15 % plus sombre s'il est
plus bas — et c'est ce qui donne son relief à la carte. Les pixels sans rien gardent la couleur
de fond du dashboard, `#0c0e14`.

Le plafond porte sur 500 000 colonnes :

```json
{"error":"Heightmap failed: Zone area too large for heightmap: 640000 (max 500K)"}
```

Cela correspond à peu près à une emprise de 700×700. Contrairement au scan, le plafond ne
porte que sur la surface : la hauteur de la zone ne compte pas, puisque chaque colonne s'arrête
au premier bloc solide.

La réponse donne `width`, `depth`, `minX`, `minZ` et les données de pixels, que le dashboard
transforme en image. `minX` et `minZ` sont ce qui permet de placer le centre réel d'un point de
contrôle sur le bon pixel.

## Réglages du circuit

`PUT /races/:eventId/track` fusionne les clés présentes dans le corps ; les clés absentes
gardent leur valeur. Ces réglages sont lus par `MiniGameManager` au démarrage de la course :
les changer en pleine course n'a aucun effet.

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `zoneId` | chaîne | — | la zone qui contient le circuit |
| `orderedCheckpoints` | booléen | `true` | les points de contrôle doivent être pris dans l'ordre |
| `laps` | entier | `1` | combien de fois la séquence doit être bouclée |
| `countdownSeconds` | entier | `5` | décompte avant le départ |
| `freezeDuringCountdown` | booléen | `true` | immobiliser les pilotes jusqu'à la fin du décompte |
| `endTimerSeconds` | entier | `30` | délai de grâce pour les retardataires après le premier arrivé |
| `checkpointMargin` | double | `1.5` | blocs de marge ajoutés autour de la boîte englobante d'un point de contrôle |
| `movementMode` | chaîne | `any` | `any`, `foot`, `shift` ou `kart` |
| `nextCheckpointParticle` | chaîne | `HAPPY_VILLAGER` | particule marquant le prochain portique |
| `validatedCheckpointParticle` | chaîne | `FLAME` | particule sur les portiques déjà pris |
| `hideValidatedCheckpoints` | booléen | `false` | ne plus dessiner les portiques déjà pris |
| `transformCommands` | chaîne[] | vide | commandes de transformation des pilotes au départ |
| `transformMode` | chaîne | `command` | `command` ou `species` |
| `revertTransformsOnEnd` | booléen | `true` | défaire la transformation à la fin de la course |
| `pointsPerCheckpoint` | entier | `10` | score d'événement par portique |
| `finishBonusPoints` | entier | `100` | score d'événement à l'arrivée |
| `positionBonusPoints` | entier | `50` | score supplémentaire pondéré par la place |
| `checkpointReplacementBlock` | chaîne | `minecraft:air` | ce que deviennent les blocs d'un portique pris |
| `startLineReplacementBlock` | chaîne | `minecraft:air` | ce que devient la ligne de départ au lancement |
| `teleportOnOutOfBounds` | booléen | `true` | ramener un pilote sorti de la zone à son dernier portique |
| `powerupTypes` | objet | vide | configuration par bonus, indexée par nom de repère |
| `checkpointOrders` | entier[] | — | en écriture seule : attribue `order` aux points de contrôle **par index** |

`checkpointOrders` est positionnel : l'élément *i* fixe l'ordre du point de contrôle *i* dans
la liste stockée, et la boucle s'arrête au plus court des deux tableaux. L'ordre de la liste
stockée est l'ordre du scan (X puis Z), pas ton ordre de course — le tableau que tu envoies
dit donc « pour chaque point de contrôle scanné, quelle est sa place dans le tour ». Rescanner
peut changer l'ordre stocké, et c'est pourquoi `applyToConfig` tente de reporter les valeurs.

`movementMode` n'est qu'en partie implémenté. Seul `foot` est appliqué, et l'application
consiste à démonter quiconque chevauche quoi que ce soit, une fois par tick, avec
`⚠ À pied uniquement ! Vous avez été démonté.` `shift` et `kart` sont acceptés, stockés, et
sans effet dans cette version.

### Types de bonus

Chaque nom de repère de bonus a une entrée dans `powerupTypes` :

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `effectId` | chaîne | `minecraft:speed` | l'effet accordé |
| `amplifier` | entier | `0` | amplificateur de l'effet |
| `durationTicks` | entier | `100` | durée de l'effet, 5 secondes |
| `targetSelf` | booléen | `true` | `false` l'applique aux **autres** pilotes dans le rayon |
| `targetRadius` | entier | `5` | rayon utilisé quand `targetSelf` est faux |
| `cooldownSeconds` | entier | `10` | délai par joueur avant que le même bonus redéclenche |
| `singleUse` | booléen | `false` | le bonus est consommé par le premier pilote qui le prend |
| `replacementBlock` | chaîne | `minecraft:air` | ce que deviennent ses blocs après usage |
| `sound` | chaîne | `block.beacon.activate` | joué au ramassage |

`targetSelf: false` avec un effet négatif, c'est la recette du piège : le pilote qui roule
dessus l'inflige à tous ceux qui l'entourent.

## Dérouler la course

La course tourne dans `MiniGameManager`, et le test des points de contrôle est la partie à
connaître. La détection n'échantillonne pas la position du joueur une fois par tick — elle
interpole le segment entre la position du tick précédent et celle du tick courant, si bien
qu'un pilote assez rapide pour traverser un portique d'un bloc d'épaisseur entre deux ticks le
déclenche quand même. C'est pour cela que `tickRaceCheckpoints` tourne à chaque tick, alors que
tout le reste de la course se met à jour à 1 Hz.

Avec `orderedCheckpoints: true`, chaque pilote possède un indice de prochain portique. Seul
celui-là compte ; se tenir dans un autre point de contrôle scanné affiche
`⚠ Mauvais checkpoint ! Il vous faut le checkpoint n°N.` au-dessus de la barre d'action.
Prendre le dernier portique boucle un tour : sous `laps`, l'indice repasse à 0 et le pilote
voit `🔄 Tour 2/3 terminé !` ; au dernier tour, c'est une arrivée.

Avec `orderedCheckpoints: false`, le pilote accumule un ensemble de portiques validés dans
n'importe quel ordre, et termine quand l'ensemble est complet.

La gestion de la sortie de circuit, quand `teleportOnOutOfBounds` est actif, téléporte le
pilote au centre de son dernier portique pris, ou à la ligne de départ s'il n'en a pris aucun,
avec `⚠ Vous avez quitté la piste ! Téléporté au dernier checkpoint.`

Un événement dont la configuration de circuit n'a aucun point de contrôle est annulé
d'emblée : `⚠ Course annulée — aucun checkpoint configuré.`

Les scores alimentent la table de scores normale de l'événement, pour que le classement de la
course et le scoreboard de l'événement concordent : les portiques donnent
`pointsPerCheckpoint`, l'arrivée donne `finishBonusPoints`, et `positionBonusPoints` est
pondéré par la place à l'arrivée.

### Mode historique

Un événement `RACE` **sans** fichier de configuration de circuit tourne quand même, dans ce
que le code appelle le mode historique : les points de contrôle sont de simples zones
CheeseGuard listées dans le `miniGameConfig` de l'événement, et les clés de score
(`pointsPerCheckpoint`, `finishBonusPoints`, `positionBonusPoints`, `checkpointSound`,
`finishSound`) sont lues dans cet objet plutôt que dans le fichier de circuit. Il n'y a ni
détection interpolée ni bonus en mode historique. Les nouveaux circuits doivent utiliser le
scanner.

## Autorisation

Depuis le 29 octobre 2026, les gardes d'écriture de ce module sont corrects :

```java
if (!WebDashboard.requireWrite(exchange)) return;
```

Avant ce correctif, les mêmes appels jetaient le booléen de retour, ce qui envoyait un `403`
puis exécutait l'action quand même. Si tu lis une version plus ancienne, prends les rôles
indiqués dans [API dashboard](dashboard.fr.md#courses) comme une intention et non comme une
frontière appliquée.

`POST /races/heightmap` n'a pas de garde propre. Il reste accessible aux seuls `OPERATOR` et
au-dessus, parce que le répartiteur de CheeseDash garde toute requête non-`GET` vers un module
— voir la note dans
[API dashboard](dashboard.fr.md#ce-que-le-cœur-applique-réellement). Il lit des données du
monde et ne change rien, donc le risque pratique est un scan gaspillé, mais une lecture exposée
en `POST` mérite d'être signalée.

---

← [Prison](prison.fr.md) · [API dashboard](dashboard.fr.md) →
