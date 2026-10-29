[← CobbleCheeseScore](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](leaderboards.md)

# Classements

Un classement est une entrée du tableau `scoreboards` de
`config/cobblecheesescore.json`. L'entrée décrit à quoi le classement ressemble et combien
de joueurs il contient. Ce qu'il **mesure** est décidé par son `id`, dans un `switch` à
l'intérieur de `ScoreboardManager.calculateScore`. Cette séparation est la chose la plus
importante à comprendre de ce mod : la configuration ne peut pas créer un nouveau type de
mesure, seulement une nouvelle présentation d'une mesure existante.

---

## Le modèle d'objets

```text
Config                                  config/cobblecheesescore.json
 ├── réglages globaux                    intervalle, zone horaire, pied d'image…
 ├── defaultColors : Map<String,String>  palette de repli
 └── scoreboards   : List<ScoreboardConfig>
                       └── id, enabled, title, description,
                           sortOrder, maxPlayers, rowsPerColumn,
                           colors : Map<String,String>

ScoreboardManager
 ├── scoreboards : Map<id, List<PlayerScore>>   les classements calculés
 └── dynamicTitles : Map<id, String>            titres remplacés depuis un JSON externe

PlayerScore   uuid · playerName · score (double) · formattedScore · ascending
```

`PlayerScore` porte son propre sens de tri et implémente `Comparable` :
`Collections.sort` sur une liste de ces objets ordonne donc le classement. Trier une liste
dont les entrées ne s'accordent pas sur le sens est indéfini ; en pratique, toutes les
entrées d'un même classement partagent la même valeur.

## Tous les champs d'un classement

| Champ | Type | Défaut | Effet |
|---|---|---|---|
| `id` | chaîne | aucun | sélectionne la mesure, nomme la route API, sert de clé au cache d'images |
| `enabled` | booléen | `true` | `false` ignore le classement à chaque passe — plus de calcul, plus de nouvelle image. Ce qui était déjà en cache reste servi jusqu'au redémarrage |
| `filename` | chaîne | aucun | **champ mort.** Aucun code ne le lit. Voir la note ci-dessous |
| `title` | chaîne | aucun | la grande ligne de l'en-tête de l'image |
| `description` | chaîne | aucun | la petite ligne juste en dessous |
| `sortOrder` | chaîne | `"desc"` | `"asc"` classe le plus petit score en premier ; toute autre valeur, faute de frappe incluse, signifie décroissant |
| `maxPlayers` | entier | `30` | le classement est tronqué à cette longueur après le tri |
| `rowsPerColumn` | entier | `10` | pilote le nombre de colonnes de l'image rendue |
| `colors` | objet | `{}` | surcharges par classement, par-dessus `defaultColors` |

`filename` survit à la version du mod qui écrivait des fichiers PNG sur le disque et les
servait depuis son propre serveur web sur le port 44310. Ce serveur n'existe plus. Le champ
est toujours écrit, et le semeur Conseil 4 le remplit toujours, mais aucune URL et aucun
chemin de fichier n'en dérive. C'est l'identifiant qui adresse un classement aujourd'hui.

Les champs manquants retombent sur les initialiseurs de champ de `ScoreboardConfig.java`,
et non sur `defaultMaxPlayers` / `defaultRowsPerColumn` de la configuration globale — ces
deux clés globales sont rapportées par `GET /config` et sinon inutilisées. Un classement
sans `maxPlayers` obtient 30 parce que `ScoreboardConfig` déclare
`private int maxPlayers = 30`, ce qui se trouve être le même nombre.

### Résolution des couleurs

`getColorOrDefault(key, defaults)` essaie la map `colors` du classement, puis
`defaultColors`, puis `#FFFFFF`. Cinq clés changent effectivement l'image :

| Clé | Effet |
|---|---|
| `background` | début du dégradé du fond |
| `titleBackground` | début du dégradé de l'en-tête, **et** la couleur de chaque valeur de score |
| `titleText` | texte du titre et de la description ; omets-la et le rendu calcule noir ou blanc depuis la luminosité de l'en-tête |
| `text` | noms des joueurs sous le rang 1 |
| `footerText` | la ligne d'horodatage |

`firstPlaceBackground`, `secondPlaceBackground`, `thirdPlaceBackground` et
`topPlayerText` sont ignorées — le style du podium est codé en dur. Détails dans
[export PNG](png-export.fr.md#les-clés-de-couleur-que-le-rendu-ignore).

---

## Sources de données

Tous les classements hors Conseil 4 sont calculés depuis trois fichiers par joueur, lus
directement sur le disque par `DataCollector`. Le mod n'écrit jamais dans le dossier du
monde.

```text
<world>/
├── pokedex/<xx>/<uuid>.nbt                 Pokédex Cobblemon, NBT GZIP
├── cobblemonplayerdata/<xx>/<uuid>.json    compteurs Cobblemon
└── stats/<uuid>.json                        statistiques vanilla
```

`<xx>` correspond aux deux premiers caractères de l'UUID. `DataCollector` essaie d'abord le
chemin découpé, puis le chemin plat (`pokedex/<uuid>.nbt`), pour les deux dossiers
Cobblemon. Les statistiques vanilla ne sont cherchées qu'à plat, puisque c'est là que le
serveur vanilla les met.

Le dossier du monde est trouvé depuis `server.getSaveProperties().getLevelName()` résolu
contre le répertoire d'exécution. Mets `autoDetectDataFolder` à `false` et remplis
`manualDataFolderPath` pour le forcer.

### La liste des joueurs vient du dossier du Pokédex

`collectAllPlayerData()` parcourt `<world>/pokedex` sur deux niveaux, traite chaque nom de
fichier `*.nbt` comme un UUID, et construit un `PlayerData` par fichier. Un joueur sans
fichier de Pokédex n'existe pas du point de vue de ce mod, peu importe ses heures de jeu ou
ses morts dans `stats/`. Cobblemon écrit ce fichier la première fois qu'un joueur rencontre
un Pokémon.

Si le dossier `pokedex` est lui-même absent, `collectAllPlayerData` journalise
`Pokedex folder not found: <path>` et renvoie une map vide. `updateAll` journalise alors
`No player data found — continuing to render external-source boards (Conseil 4)` et
continue : les classements Conseil 4 sont donc tout de même rendus, et tous les autres
sortent vides. Note que dans ce cas le cache `playerData` précédent n'est **pas** vidé :
`/stats` continue de rapporter l'ancien `totalPlayersTracked`.

### Ce que mesure chaque classement

Douze classements lisent des données réelles. Tout ce qui est sous `advancementData` vient
du JSON Cobblemon ; les noms de compteurs sont ceux de Cobblemon, pas de ce mod.

| `id` | Source | Champ ou calcul | `formattedScore` |
|---|---|---|---|
| `pokedex_completed` | NBT du Pokédex | espèces ayant au moins une forme dont `knowledge` vaut `CAUGHT` | `906/1025 (88%)` |
| `shiny_hunter` | Cobblemon | `advancementData.totalShinyCaptureCount` | `69` |
| `play_time` | stats vanilla | `minecraft:play_time` ÷ 20 ÷ 3600 | `141.4h` |
| `battle_elite` | Cobblemon | `advancementData.totalBattleVictoryCount` | `443` |
| `trainer_tamer` | Cobblemon | `advancementData.totalPvWBattleVictoryCount` | `347` |
| `distance_traveled` | stats vanilla | somme de `walk_one_cm`, `sprint_one_cm`, `fly_one_cm`, `swim_one_cm`, ÷ 100 000 | `829.7 km` |
| `evolutionist` | Cobblemon | `advancementData.totalEvolvedCount` | `225` |
| `pokeball_crafter` | stats vanilla | chaque entrée de `minecraft:crafted` dont la clé contient à la fois `ball` et `cobblemon` | `5964` |
| `pvp_champion` | Cobblemon | `advancementData.totalPvPBattleVictoryCount` | `4` |
| `trader` | Cobblemon | `advancementData.totalTradedCount` | `56` |
| `survivor` | stats vanilla | `minecraft:deaths` | `10 morts` |
| `legendary_hunter` | NBT du Pokédex | espèces capturées correspondant à une liste codée en dur d'une centaine de légendaires et de fabuleux | `7` |

Dix-huit classements de type lisent `advancementData.totalTypeCaptureCounts`, indexé par
le nom de type en minuscules : `type_normal`, `type_water`, `type_fire`, `type_grass`,
`type_electric`, `type_ice`, `type_fighting`, `type_poison`, `type_ground`,
`type_flying`, `type_psychic`, `type_bug`, `type_rock`, `type_ghost`, `type_dragon`,
`type_dark`, `type_steel`, `type_fairy`. Chacun est livré avec la couleur standard de son
type comme `titleBackground`.

`pokedex_completed` divise par un total codé en dur de 1 025 espèces. Une installation
Cobblemon avec moins d'espèces implémentées, ou un datapack qui en ajoute, rend ce
pourcentage faux. La constante est dans `ScoreboardManager.formatScore`.

`survivor` est titré *Roi des Morts* et livré avec `sortOrder: "desc"` : il classe donc le
joueur avec le **plus grand** nombre de morts en premier. C'est un mur de la honte, pas un
classement de survie, et c'est le seul classement où un score de zéro est conservé plutôt
qu'écarté.

Un identifiant sans `case` dans le `switch` journalise `Unknown scoreboard ID: <id>` une
fois par joueur et par rafraîchissement, et score 0 — ce qui fait que le classement écarte
tout le monde et est rendu vide.

### Un joueur à zéro est écarté

Tout joueur dont le score calculé vaut exactement 0 est ignoré avant le tri, `survivor`
étant la seule exception. Effet pratique : le `playerCount` d'un classement est le nombre de
joueurs ayant une valeur non nulle, pas le nombre de joueurs du serveur. Sur un serveur
jeune, la plupart des classements de type sont vides.

---

## Périodicité du rafraîchissement

`updateIntervalMinutes` (défaut `10`) est lue **une seule fois**, au déclenchement de
`SERVER_STARTED`, et passée à `scheduler.scheduleAtFixedRate` comme délai initial et comme
période. Donc :

- la première exécution planifiée a lieu `updateIntervalMinutes` après le démarrage, en
  plus du rafraîchissement initial inconditionnel qui tourne pendant `SERVER_STARTED` ;
- changer la valeur ensuite, à la main ou depuis le dashboard, n'a aucun effet avant un
  redémarrage du serveur. Rien ne replanifie l'exécuteur.

Une passe complète lit trois fichiers par joueur et fait le rendu de chaque classement
activé. Sur un monde à 30 joueurs et 34 classements, cela représente environ 90 lectures de
fichiers et 34 images. Le log l'encadre :

```text
[CobbleCheeseScore] Collecting player data...
[CobbleCheeseScore] Collected data for 30 players
[CobbleCheeseScore] Updated scoreboard: pokedex_completed (25 players)
...
[CobbleCheeseScore] All scoreboards updated successfully
```

Chronomètre l'écart entre la première et la dernière ligne sur ta propre machine avant
d'augmenter `maxPlayers` ou de baisser l'intervalle. Aucun benchmark n'est livré avec le
mod et les chiffres de l'ancien README n'ont pas été mesurés.

L'échec d'un seul classement n'interrompt pas la passe : la boucle attrape l'exception par
classement et journalise `Failed to update scoreboard: <id>`, en laissant l'image
précédente de ce classement dans le cache.

---

## Classements alimentés de l'extérieur : Conseil 4

Quatre identifiants contournent entièrement `PlayerData`. `calculateScoreboard`
court-circuite sur le préfixe `conseil4_` et lit un fichier JSON écrit par
[CobbleCap](../../CobbleCap/).

| Identifiant | Fichier lu dans `config/` | Classé par |
|---|---|---|
| `conseil4_first_finish` | `cobblecheesescore_external_conseil4_first.json` | `rank`, croissant |
| `conseil4_fastest_time` | `cobblecheesescore_external_conseil4_fastest.json` | `bestTimeMs`, croissant |
| `conseil4_ranked_champions` | `cobblecheesescore_external_conseil4_ranked_champions.json` | `rank`, croissant |
| `conseil4_ranked_failures` | `cobblecheesescore_external_conseil4_ranked_failures.json` | `failures`, décroissant |

`Config.load()` sème ces quatre entrées dans la configuration si elles sont absentes et
ré-enregistre le fichier : elles apparaissent donc dans chaque installation sans édition.
Elles sont activées par défaut.

La forme du fichier :

```json
{
  "dynamicTitle": "Échecs face à RoiCheese",
  "ranking": [
    {
      "playerUuid": "5e1b2c3d-7a8f-4b6c-9d0e-1f2a3b4c5d6e",
      "playerName": "RoiCheese",
      "rank": 1,
      "bestTimeMs": 184320,
      "bestTimeFormatted": "3:04",
      "startedAt": 1747440000000,
      "isCurrent": true,
      "failures": 3
    }
  ]
}
```

Les champs qui comptent dépendent du classement. `playerUuid` et `playerName` toujours ;
`bestTimeMs` et `bestTimeFormatted` seulement pour le classement des temps ; `startedAt`
(ou `completedAt` en repli) et `isCurrent` seulement pour le classement des champions, qui
formate son score en `👑 dd/MM/yyyy` pour le champion en titre et en date nue pour les
autres ; `failures` seulement pour le classement des échecs, formaté en `3 échecs`. Le
classement des premiers vainqueurs formate son score en `#1`.

`dynamicTitle` est optionnel et remplace le `title` configuré du classement dans l'image
rendue. Seul l'export des échecs de CobbleCap en définit un, pour que l'image porte le nom
du champion en titre. Quand un fichier cesse de fournir `dynamicTitle`, le remplacement est
retiré et le titre configuré revient. Si le fichier disparaît complètement, ou devient
illisible, le dernier titre lu reste en place jusqu'à ce qu'un fichier lisible dise
autre chose.

> Les noms de champs et le tableau `ranking` pré-trié sont le contrat de CobbleCap. Rien ne
> les valide, et rien ici ne les produit. Les deux moitiés doivent être maintenues en phase
> à la main ; si CobbleCap change son export, ces quatre classements deviennent vides ou
> faux sans qu'aucune ligne de log explique pourquoi.

### Quand la source est absente

C'est le cas normal pour quiconque ne fait pas tourner CobbleCap.

| Situation | Comportement |
|---|---|
| le fichier n'existe pas | log `DEBUG` `Conseil 4 export file missing: <path> — empty board`, classement vide, l'image est rendue avec seulement l'en-tête et le pied |
| le fichier existe mais n'a pas de clé `ranking` | classement vide, aucun avertissement |
| le fichier est du JSON invalide | log `WARN` `Failed to load Conseil 4 board <id>: <message>`, classement vide |
| l'identifiant commence par `conseil4_` sans être l'un des quatre | log `WARN` `Unknown Conseil 4 board ID: <id>`, classement vide |

Rien ne lève, rien n'est désactivé automatiquement. Tu obtiens quatre classements vides
dans la liste du dashboard et quatre images presque blanches. Mets `"enabled": false` sur
les quatre dans `config/cobblecheesescore.json` si tu n'en veux pas — le semeur n'ajoute un
classement que lorsque son identifiant est absent, donc une entrée désactivée reste
désactivée d'un redémarrage à l'autre.

---

## Ajouter une mesure

La configuration seule ne peut pas le faire. Ajouter un classement qui compte quelque chose
de nouveau demande une modification de code en deux endroits :

1. un `case` dans `ScoreboardManager.calculateScore(String, PlayerData)` qui renvoie le
   nombre, généralement par l'intermédiaire d'une nouvelle méthode sur `PlayerData` ;
2. éventuellement un `case` dans `formatScore` si un entier nu n'est pas le bon affichage.

Puis ajoute l'entrée de configuration avec cet identifiant. Ajouter l'entrée seule te donne
un classement qui journalise `Unknown scoreboard ID` et qui est rendu vide.

Une mesure qui n'est dans aucun des trois fichiers source demande davantage : soit un
nouveau parseur dans `com.cobblecheesescore.data`, soit le motif Conseil 4 — faire écrire à
un autre mod un fichier JSON classé dans `config/` et le lire comme le fait
`loadConseil4Board`.

---

← [Configuration](configuration.fr.md) · [Export PNG](png-export.fr.md) →
