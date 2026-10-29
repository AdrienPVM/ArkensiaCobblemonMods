[← CheesePatrol](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](configuration.md)

# Configuration

**CheesePatrol n'a pas de fichier de réglages.** Tous les mods voisins de ce dépôt écrivent
un `config/<modid>.json` au premier démarrage, avec des paramètres dedans ; celui-ci non. Il
n'y a pas de classe `CheesePatrolConfig`, aucune valeur par défaut à surcharger, et aucune
commande de rechargement.

Ce qui existe à la place est un **fichier d'état** — les patrouilles elles-mêmes, écrites
par le mod et relues au démarrage — plus exactement un drapeau global, qui se trouve vivre
dans le même fichier.

```text
config/cheesepatrol_data.json
```

Le chemin est écrit en dur sous la forme `Path.of("config", "cheesepatrol_data.json")`,
relatif au répertoire de travail du serveur. Il ne passe pas par
`FabricLoader.getInstance().getConfigDir()` : sur un lancement inhabituel où le répertoire
de travail n'est pas la racine du serveur, le fichier atterrit donc ailleurs. À savoir si tu
ne le trouves pas.

Tout ce que contient ce fichier est produit par les sous-commandes `/npcpatrol`. L'éditer à
la main est possible et documenté plus bas, avec de vraies réserves.

---

## Forme générale

```json
{
  "debug": false,
  "patrols": [ … ]
}
```

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `debug` | booléen | `false` | Active la journalisation verbeuse par patrouille dans la console serveur |
| `patrols` | tableau | `[]` | Un objet par patrouille. Écrit depuis la mémoire à chaque changement |

Les deux clés sont écrites à chaque sauvegarde. Un `debug` absent est lu comme `false` ; un
`patrols` absent comme une liste vide. Un fichier corrompu ou illisible est journalisé une
fois et traité comme vide :

```text
[CheesePatrol] Failed to load data
```

⚠️ Le mod ne sauvegarde jamais ce fichier avant de le réécrire. Une écriture tronquée
pendant un crash perd toutes les patrouilles — récupérables seulement depuis tes propres
sauvegardes de monde. Les entités NPC survivent ; leurs trajets, non.

### `debug`

La seule clé ici qui soit véritablement un réglage et non un état, et la seule que tu dois
éditer à la main parce qu'**aucune commande ni endpoint ne la bascule**.

Avec `debug: false`, la console ne voit que les erreurs : fichier de données corrompu,
sauvegarde d'entité échouée, patrouille qui abandonne après dix tentatives de réapparition,
réapparition franchement ratée.

Avec `debug: true`, tu obtiens en plus, par patrouille et par événement :

```text
[CheesePatrol] Kick-started patrol for NPC a1b2c3d4 at waypoint 2/4
[CheesePatrol] NPC a1b2c3d4 not found after 30s - respawn attempt 1/10
[CheesePatrol] NPC a1b2c3d4 found after 812 ticks missing - kick-starting patrol
[CheesePatrol] NPC a1b2c3d4 stuck for 60s at dist 14.2 - teleporting to waypoint 3
[CheesePatrol] Integrity check: NPC a1b2c3d4 missing (patrol active) - respawning from backup
[CheesePatrol] Auto-resuming patrol a1b2c3d4 - entity found, was system-paused
[CheesePatrol] Startup: kick-started 3 active patrols
```

C'est la sortie que tu veux quand un NPC se comporte mal et que tu n'arrives pas à savoir
s'il se bloque, s'il se fait téléporter, ou s'il meurt discrètement et réapparaît. Coupe-la
ensuite : ces lignes sont par événement, pas par tick, mais une douzaine de patrouilles
capricieuses rempliront un log.

**Comment la changer, précisément :**

1. Arrête le serveur. Ce n'est pas facultatif, et la raison est le chemin de sauvegarde :
   toute action `/npcpatrol` réécrit le fichier entier depuis l'état en mémoire, donc une
   édition faite serveur allumé est écrasée dès que quelqu'un ajoute un point de passage.
2. Remplace `"debug": false` par `"debug": true`.
3. Démarre le serveur. `PatrolManager.load()` tourne à `SERVER_STARTING` et lit le drapeau.

Le drapeau est conservé d'une sauvegarde à l'autre — le mod réécrit ce qu'il a chargé — donc
tu n'as à le faire qu'une fois.

Une chose que `debug` ne supprime **pas** : le mixin qui bloque les suppressions journalise
chaque blocage en `INFO`, sans condition. Voir
[garder les NPC en vie](resilience.fr.md#le-mixin-journalise-chaque-blocage).

## Un objet patrouille

Un par entrée de `patrols`. Les clés sont omises quand elles portent leur valeur par défaut,
d'où une patrouille fraîchement créée longue de trois lignes.

| Clé | Type | Défaut | Écrite quand | Effet |
|---|---|---|---|---|
| `npcUuid` | chaîne | — | toujours | L'UUID de l'entité pilotée. C'est l'identité de la patrouille ; il n'y a pas d'autre identifiant |
| `dimension` | chaîne | `"minecraft:overworld"` | toujours | Identifiant de dimension, sous la forme `namespace:path`. Fixé à la sélection du NPC, jamais modifié ensuite |
| `active` | booléen | `false` | toujours | Si le circuit tourne. Restauré au redémarrage, d'où les patrouilles qui reprennent seules |
| `speed` | nombre | `0.5` | toujours | Vitesse de marche. Bornée à `0.1`–`5.0` |
| `playerDetectionRadius` | nombre | `5.0` | toujours | Rayon « regarder un joueur » en blocs. Borné à `1.0`–`32.0` |
| `currentWaypointIndex` | entier | `0` | toujours | Index, à partir de 0, du point de passage vers lequel le NPC marche |
| `waypoints` | tableau | `[]` | toujours | Le circuit, dans l'ordre de parcours |
| `lastX` `lastY` `lastZ` | entiers | absents | dès que le NPC a été vu | Dernière position de bloc connue, mise à jour à chaque tick où l'entité est trouvée. Sert à force-loader le bon chunk au démarrage |
| `particleEffect` | chaîne | `"none"` | si différent de `"none"` | Nom de l'effet de traînée |
| `particleYOffset` | nombre | `0.0` | si différent de `0` | Décalage vertical de la traînée |
| `particleZOffset` | nombre | `0.0` | si différent de `0` | Décalage avant/arrière de la traînée. Positif = derrière |
| `particleRange` | entier | `16` | si différent de `16` | Portée de visibilité de la traînée, en blocs |
| `pausedBySystem` | booléen | `false` | si `true` | `true` signifie que le mod a désactivé cette patrouille lui-même après dix réapparitions ratées, et la réactivera quand le NPC reviendra. `/npcpatrol stop` la met à `false` |

Un point de passage :

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `x` `y` `z` | entiers | — | Position de bloc. Le NPC visera le centre du bloc, `x + 0.5` / `y` / `z + 0.5` |
| `wait` | entier | absent | Pause en **ticks**, omise quand elle vaut zéro. `/npcpatrol wait` multiplie tes secondes par 20 |

### Les clés dont l'effet n'est pas évident

**`active` et `pausedBySystem` ensemble** décident de ce qui se passe au redémarrage et
pendant le contrôle d'intégrité des cinq minutes. `active: true` signifie que la patrouille
est relancée cinq secondes après `SERVER_STARTED`. `active: false, pausedBySystem: true`
signifie « le mod a renoncé à ce NPC » et le contrôle d'intégrité la repassera en actif dès
que l'entité sera retrouvée. `active: false, pausedBySystem: false` signifie qu'un humain
l'a arrêtée, et rien ne la relancera qu'un humain. Si tu édites l'une à la main, pense à
l'autre.

**`currentWaypointIndex` est lu modulo le nombre de points de passage** à chaque usage : une
valeur hors bornes ne plante donc pas, elle boucle. `/npcpatrol start` la remet à 0 quand
elle dépasse la fin. La régler à la main est une façon légitime de garer un NPC sur un point
précis de son circuit avant un redémarrage.

**`dimension` n'est fixée qu'une fois**, à la sélection du NPC, et rien ne la met à jour
ensuite. Déplace un NPC dans une autre dimension et la patrouille garde l'ancien
identifiant. La recherche d'entité a un repli qui balaie tous les mondes chargés, donc la
patrouille continue généralement de fonctionner, mais le force-load des chunks au démarrage
et l'affichage de `show` utilisent tous deux la valeur stockée et viseront le mauvais monde.
Efface et recrée la patrouille plutôt que d'éditer le champ, pour que la comptabilité des
chunks forcés reste cohérente.

**`lastX` / `lastY` / `lastZ` sont écrites à chaque tick où l'entité est trouvée** mais ne
sont persistées qu'à la sauvegarde. Après un crash, elles peuvent avoir quelques minutes de
retard, ce qui ne compte que pour le force-load du démarrage : une position périmée
force-load le mauvais chunk, l'entité n'est pas trouvée, et l'échelle de réapparition prend
le relais.

**`wait` est en ticks dans le fichier et en secondes dans la commande.** Un fichier
affichant `"wait": 100` fait cinq secondes. Les valeurs sont bornées à `≥ 0` à l'écriture,
mais le plafond de `0`–`3600` secondes de la commande n'est pas appliqué au chargement :
un `"wait": 999999` écrit à la main est donc accepté et le NPC reste planté là une
quatorzaine d'heures.

**Vitesse et rayon sont bornés à l'écriture, pas au chargement.**
`PatrolData.fromJson` affecte les champs directement, sans bornage : un `"speed": 50` édité
à la main survit donc au chargement et le NPC se déplace à six blocs par tick. Les bornes
sont dans les setters, que seules les commandes appellent. Vois-y un moyen de dépasser les
plages documentées à tes risques, pas une fonctionnalité.

## Les sauvegardes d'entités

Un second répertoire, écrit par le même sous-système mais non configurable :

```text
config/cheesepatrol_entities/<npc-uuid>.dat
```

Un instantané NBT compressé en GZip par NPC de patrouille enregistré, produit par
`entity.saveNbt`. Écrit 60 secondes après le démarrage puis toutes les 60 secondes, chaque
fois qu'un NPC manquant réapparaît, et à `SERVER_STOPPING`. Supprimé par `/npcpatrol clear`,
et supprimé automatiquement s'il devient illisible, pour casser une boucle de tentatives de
réapparition.

Ces fichiers sont ce qui rend un NPC récupérable, et leur seule présence suffit à rendre une
entité intuable : le mixin de suppression interroge le répertoire pour toute entité qu'il ne
reconnaît pas. Supprimer l'un d'eux à la main sur un serveur en marche a donc deux effets :
le NPC perd son filet de sécurité, et il perd sa protection contre la suppression jusqu'à ce
que la passe de sauvegarde suivante réécrive le fichier. Comportement complet dans
[garder les NPC en vie](resilience.fr.md).

Il n'y a ni politique de rétention ni plafond de taille. Un fichier par NPC de patrouille,
réécrit sur place, de quelques kilooctets chacun.

## Un exemple complet annoté

Trois patrouilles : une en cours, une arrêtée par un administrateur, une à laquelle le mod a
renoncé. JSON n'autorise pas les commentaires, donc les annotations suivent le bloc.

```json
{
  "debug": false,
  "patrols": [
    {
      "npcUuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "speed": 1.0,
      "playerDetectionRadius": 6.0,
      "active": true,
      "currentWaypointIndex": 2,
      "dimension": "minecraft:overworld",
      "lastX": 126,
      "lastY": 65,
      "lastZ": -331,
      "particleEffect": "dust_cyan",
      "particleZOffset": 0.6,
      "particleRange": 48,
      "waypoints": [
        { "x": 112, "y": 65, "z": -340 },
        { "x": 128, "y": 65, "z": -340, "wait": 100 },
        { "x": 128, "y": 65, "z": -324 },
        { "x": 112, "y": 65, "z": -324 }
      ]
    },
    {
      "npcUuid": "9f8e7d6c-5b4a-3928-1716-0f0e0d0c0b0a",
      "speed": 0.5,
      "playerDetectionRadius": 5.0,
      "active": false,
      "currentWaypointIndex": 0,
      "dimension": "minecraft:the_nether",
      "lastX": 48,
      "lastY": 72,
      "lastZ": 19,
      "waypoints": [
        { "x": 44, "y": 72, "z": 16 },
        { "x": 52, "y": 72, "z": 16 }
      ]
    },
    {
      "npcUuid": "11112222-3333-4444-5555-666677778888",
      "speed": 0.8,
      "playerDetectionRadius": 5.0,
      "active": false,
      "currentWaypointIndex": 1,
      "dimension": "cheeseguard:arena_01",
      "pausedBySystem": true,
      "waypoints": [
        { "x": 0, "y": 80, "z": 0 },
        { "x": 0, "y": 92, "z": 0 },
        { "x": 20, "y": 92, "z": 0 }
      ]
    }
  ]
}
```

- **La patrouille 1** est le résultat du guide [premiers pas](getting-started.fr.md) : un
  carré de quatre coins, pause de 5 secondes au coin #2 (`"wait": 100` ticks), traînée cyan
  poussée 0,6 bloc derrière le NPC et visible jusqu'à 48 blocs. Elle marche actuellement
  vers le point de passage #3, et a été vue pour la dernière fois à `126 65 -331`, entre le
  #2 et le #3.
- **La patrouille 2** a été arrêtée par un humain : `active: false` sans `pausedBySystem`.
  Rien ne la relancera tout seul. Deux points de passage, c'est le minimum ; elle fera des
  allers-retours entre les deux une fois démarrée, puisqu'une boucle de deux points est un
  segment.
- **La patrouille 3** a été désactivée par le mod lui-même. `pausedBySystem: true` signifie
  que l'entité a disparu et que dix tentatives de réapparition ont échoué — très
  probablement parce que `cheeseguard:arena_01` est une dimension qui n'existe plus, cas
  dans lequel la réapparition journalise
  `Cannot respawn NPC 11112222 - dimension 'cheeseguard:arena_01' not found`. Le contrôle
  d'intégrité des cinq minutes continue de réessayer. Noter les points de passage à y 80 et
  y 92 au-dessus du vide : cela fait des segments du milieu des segments **en vol**, et le
  mod appliquera `NoGravity` et déplacera le NPC par vélocité directe. Voir
  [les segments en vol](patrol-circuits.fr.md#les-segments-en-vol).

Aucune entrée `debug` n'est à ajouter : elle est déjà là, écrite à chaque sauvegarde.

---

← [Commandes](commands.fr.md) · [Circuits de patrouille](patrol-circuits.fr.md) →
