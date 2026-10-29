[← CobbleCheeseScore](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](configuration.md)

# Configuration

```text
config/cobblecheesescore.json
```

Écrit au premier démarrage depuis la ressource embarquée `cobblecheesescore-default.json`,
et réécrit chaque fois que `Config.load()` sème un classement Conseil 4 manquant ou qu'une
route du dashboard enregistre. Deux autres fichiers vivent à côté et ne sont pas de la
configuration — ce sont des données :

| Fichier | Écrit par | Contenu |
|---|---|---|
| `config/cobblecheesescore_minigames.json` | `MiniGameManager` | les sessions de mini-jeux, 500 glissantes par jeu |
| `config/cobblecheesescore_legends.json` | `MiniGameLegends` | le panthéon permanent des mini-jeux |
| `config/cobblecheesescore_external_conseil4_*.json` | **CobbleCap**, pas ce mod | les exports classés des quatre classements Conseil 4 |

Le fichier est analysé par Gson directement sur la classe `Config` : une clé que la classe
ne déclare pas est donc silencieusement ignorée, et une clé que tu supprimes retombe sur
l'initialiseur du champ. Les commentaires ne sont pas du JSON légal et le défaut livré n'en
contient aucun ; l'exemple annoté en fin de page est fait pour être lu, pas pour être
collé.

⚠️ **Le mod réécrit ce fichier.** `Config.save()` sérialise l'objet en mémoire avec une
mise en forme automatique : l'ordre des clés ou les espaces que tu préfères sont perdus dès
qu'un classement Conseil 4 est semé ou qu'un `PUT` du dashboard arrive. Garde ta propre
copie si tu le règles à la main.

---

## Clés globales

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `updateIntervalMinutes` | entier | `10` | minutes entre deux rafraîchissements automatiques, et délai avant le premier planifié |
| `timeZone` | chaîne | `"Europe/Paris"` | zone utilisée pour l'horodatage en pied de chaque image |
| `autoDetectDataFolder` | booléen | `true` | `true` déduit le chemin du monde depuis le serveur ; `false` utilise `manualDataFolderPath` |
| `manualDataFolderPath` | chaîne | `""` | chemin absolu ou relatif vers le dossier du monde. Consulté seulement quand `autoDetectDataFolder` vaut `false` |
| `showLastUpdate` | booléen | `true` | dessine la ligne d'horodatage en pied. `false` l'omet, et les 60 px de pied restent vides |
| `lastUpdateText` | chaîne | `"Dernière mise à jour :"` | le libellé qui précède l'horodatage |
| `defaultColors` | objet | voir plus bas | palette sur laquelle chaque classement retombe |
| `scoreboards` | tableau | 30 entrées, plus 4 semées | les définitions de classements. Chaque champ est documenté dans [classements](leaderboards.fr.md#tous-les-champs-dun-classement) |

### `updateIntervalMinutes`

Lue exactement une fois, au déclenchement de `SERVER_STARTED`, et passée à
`scheduleAtFixedRate(task, interval, interval, MINUTES)`. **La changer exige un
redémarrage du serveur** : rien ne replanifie l'exécuteur, et `PUT /config` ne lit même pas
la clé. Il n'y a pas de borne inférieure : `1` est accepté, et le serveur relira les
fichiers de chaque joueur chaque minute, ce qui est du gaspillage plutôt qu'un danger
puisque le travail se fait hors du thread serveur. `0` ou une valeur négative est rejetée
par `ScheduledExecutorService` avec une `IllegalArgumentException`, levée dans le handler
de `SERVER_STARTED`. Rien dans le mod ne l'attrape : elle remonte dans la séquence de
démarrage du serveur, qui va donc avorter avec un rapport de crash plutôt que de continuer
sans planificateur.

Le rafraîchissement initial inconditionnel pendant `SERVER_STARTED` n'est pas affecté par
cette clé.

### `autoDetectDataFolder` et `manualDataFolderPath`

La détection automatique résout `server.getSaveProperties().getLevelName()` contre le
répertoire d'exécution. C'est correct pour un serveur Fabric standard lancé depuis son
propre dossier. C'est faux quand le nom du niveau sur le disque diffère de celui de
`level.dat`, ou quand un hébergeur lance le jar depuis un dossier parent. Le log te dit
quel chemin a été retenu :

```text
[CobbleCheeseScore] Auto-detected world path: /home/container/world
```

Si ce chemin n'est pas celui où vit réellement `pokedex/`, mets `autoDetectDataFolder` à
`false` et donne le vrai chemin :

```json
"autoDetectDataFolder": false,
"manualDataFolderPath": "/home/container/world"
```

Le chemin manuel n'est utilisé que s'il existe ; un chemin qui ne résout pas journalise
`Manual path does not exist: <path>` et retombe sur la détection automatique. Si celle-ci
lève alors une exception, le chemin devient le littéral relatif `world`.

⚠️ Le chemin du monde est résolu **une seule fois**, dans le constructeur de
`DataCollector`, pendant `SERVER_STARTED`. Modifier l'une ou l'autre clé en cours de
session ne change rien avant redémarrage.

### `timeZone`

N'importe quel identifiant de zone IANA. Il est passé à `ZoneId.of(...)` sans garde — et
seulement quand `showLastUpdate` vaut `true`, puisque le pied d'image est son seul lecteur :
une faute de frappe lève donc une `DateTimeException` dans `generateScoreboard`. Le `catch` par
classement dans `updateAll` transforme cela en `Failed to update scoreboard: <id>` pour
chaque classement l'un après l'autre, et tu te retrouves sans aucune image. Si tous les
classements échouent d'un coup, vérifie cette clé en premier.

### `defaultColors`

Défaut livré :

| Clé | Défaut | Honorée |
|---|---|---|
| `background` | `#0f0c29` | oui |
| `titleBackground` | `#ffcb05` | oui — dégradé de l'en-tête et chaque valeur de score |
| `titleText` | `#FFFFFF` | oui, mais seulement quand elle est définie par classement |
| `text` | `#FFFFFF` | oui |
| `footerText` | `#ffcb05` | oui |
| `firstPlaceBackground` | `#ff715b` | **non** |
| `secondPlaceBackground` | `#ff5b42` | **non** |
| `thirdPlaceBackground` | `#ff2f0f` | **non** |
| `topPlayerText` | `#FFD700` | **non** |

Les valeurs sont analysées comme `#RRGGBB` ; le `#` est optionnel et tout ce qui échoue à
l'analyse devient blanc. La forme courte (`#fff`) et le canal alpha (`#RRGGBBAA`) ne sont
pas gérés — `#fff` traverse les appels à `substring` et ressort blanc.

`titleText` se comporte différemment des autres : elle est lue dans la map `colors` **du
classement lui-même** uniquement, jamais dans `defaultColors`. Mets-la dans
`defaultColors` et elle est ignorée, le rendu calculant alors du noir ou du blanc depuis la
luminosité de l'en-tête. Ce contraste automatique est généralement ce que tu veux ;
impose-le par classement quand il devine mal.

Les quatre clés non honorées sont expliquées dans
[export PNG](png-export.fr.md#les-clés-de-couleur-que-le-rendu-ignore).

---

## Les clés que rien ne lit

Celles-ci sont sérialisées dans le fichier, rapportées par `GET /config` pour certaines, et
n'ont absolument aucun effet. Elles sont laissées en place plutôt que retirées pour qu'un
fichier de configuration existant continue d'être analysable.

| Clé | Défaut | Pourquoi elle est morte |
|---|---|---|
| `port` | `44310` | le mod faisait tourner son propre serveur HTTP sur ce port avant de passer à CheeseDash. Aucun code ne la lit ; le dashboard sert tout sur 44311 |
| `enableWebInterface` | `true` | l'interface HTML qu'elle activait a été supprimée avec ce serveur |
| `enableJsonApi` | `true` | de même — l'API JSON est maintenant celle de CheeseDash |
| `enableDiscordWebhook` | `false` | **aucun code de webhook n'existe dans ce mod.** La mettre à `true` ne publie rien nulle part |
| `discordWebhookUrl` | `""` | idem |
| `enableHistory` | `true` | rapportée par `GET /config`, exploitée nulle part. Aucun historique n'est conservé |
| `historyRetentionDays` | `30` | idem |
| `defaultMaxPlayers` | `30` | rapportée par `GET /config`. Les classements retombent sur le `30` de `ScoreboardConfig`, pas sur celle-ci |
| `defaultRowsPerColumn` | `10` | idem, face au `10` de `ScoreboardConfig` |

Par classement, `filename` est morte pour la même raison que `port` : elle nommait le
fichier PNG que l'ancien serveur écrivait sur le disque. Plus rien n'écrit de PNG sur le
disque aujourd'hui.

⚠️ Si tu lis une documentation qui prétend que ce mod publie une notification Discord quand
la première place d'un classement change de main, cette documentation est fausse. Cherche
`discord` dans `src/main/java` et tu trouveras deux getters et aucun appelant.

---

## Comportement au rechargement

Rien n'est rechargeable à chaud. Il n'y a aucune commande de rechargement, aucun
surveillant de fichier et aucune route de rechargement.

| Changement | Prend effet |
|---|---|
| le `title`, `description`, `enabled`, `maxPlayers`, `rowsPerColumn` ou `colors` d'un classement | immédiatement, **si** le changement passe par `PUT /api/v1/scores/scoreboards/:id` — cette route applique, enregistre et refait le rendu |
| les mêmes champs édités à la main dans le fichier | au prochain redémarrage du serveur |
| le `sortOrder` ou l'`id` d'un classement | au prochain redémarrage seulement. La route `PUT` ne les accepte pas |
| ajouter ou retirer un classement | au prochain redémarrage |
| `updateIntervalMinutes`, `timeZone`, `showLastUpdate`, `lastUpdateText`, `defaultColors` | au prochain redémarrage |
| `autoDetectDataFolder`, `manualDataFolderPath` | au prochain redémarrage |

⚠️ Éditer le fichier à la main **pendant que le serveur tourne** est un piège : l'objet
`Config` en mémoire fait autorité, et le prochain `PUT` sur n'importe quel classement le
réécrit par-dessus ton édition. Arrête le serveur, édite, redémarre.

---

## Un exemple complet annoté

Une configuration réduite à trois classements, les quatre entrées Conseil 4 laissées de
côté pour la longueur. Les annotations `//` expliquent les clés et **ne sont pas du JSON
valide** — retire-les avant usage.

```json
{
  // Minutes entre deux rafraîchissements automatiques. Lue une fois au démarrage ;
  // il faut redémarrer pour la changer.
  "updateIntervalMinutes": 10,

  // Zone IANA pour l'horodatage en pied. Une faute ici casse tous les classements
  // d'un coup.
  "timeZone": "Europe/Paris",

  // Laisse true à moins que le log n'indique le mauvais chemin de monde.
  "autoDetectDataFolder": true,
  "manualDataFolderPath": "",

  // Rapportées par GET /config, utilisées par rien. Les classements retombent sur
  // 30 / 10 par eux-mêmes.
  "defaultMaxPlayers": 30,
  "defaultRowsPerColumn": 10,

  // Ligne de pied d'image. Mets showLastUpdate à false pour la retirer de toutes
  // les images.
  "showLastUpdate": true,
  "lastUpdateText": "Dernière mise à jour :",

  // Clés mortes, reliquats du serveur web autonome supprimé, sur le port 44310.
  "port": 44310,
  "enableWebInterface": true,
  "enableJsonApi": true,

  // Clés mortes. Aucun code de webhook n'existe dans ce mod.
  "enableDiscordWebhook": false,
  "discordWebhookUrl": "",

  // Clés mortes. Aucun historique n'est enregistré.
  "enableHistory": true,
  "historyRetentionDays": 30,

  // Palette de repli. Les quatre dernières clés sont ignorées par le rendu.
  "defaultColors": {
    "background": "#0f0c29",
    "titleBackground": "#ffcb05",
    "titleText": "#FFFFFF",
    "text": "#FFFFFF",
    "footerText": "#ffcb05",
    "firstPlaceBackground": "#ff715b",
    "secondPlaceBackground": "#ff5b42",
    "thirdPlaceBackground": "#ff2f0f",
    "topPlayerText": "#FFD700"
  },

  "scoreboards": [
    {
      // L'identifiant sélectionne la mesure. Il doit correspondre à un case de
      // ScoreboardManager.calculateScore, sinon le classement est rendu vide.
      "id": "pokedex_completed",
      "enabled": true,
      // Champ mort — aucune URL ni aucun chemin de fichier n'en dérive.
      "filename": "pokedex.png",
      "title": "Pokédex Complété",
      "description": "Espèces capturées",
      // "desc" place le score le plus élevé en premier. Modifiable uniquement en
      // éditant ce fichier.
      "sortOrder": "desc",
      // Le classement est tronqué à ce nombre de joueurs après le tri.
      "maxPlayers": 30,
      // Pilote le nombre de colonnes : ceil(joueurs / rowsPerColumn), borné à 1-3.
      "rowsPerColumn": 10,
      // Vide signifie « hériter de defaultColors pour tout ».
      "colors": {}
    },
    {
      "id": "shiny_hunter",
      "enabled": true,
      "filename": "shiny.png",
      "title": "Chasseur de Shiny",
      "description": "Shinies capturés",
      "sortOrder": "desc",
      "maxPlayers": 30,
      "rowsPerColumn": 10,
      // En-tête doré. Les scores dans les lignes prennent aussi cette couleur.
      "colors": {
        "titleBackground": "#FFD700"
      }
    },
    {
      // Classe le PLUS GRAND nombre de morts en premier, malgré son identifiant.
      // C'est un mur de la honte. C'est aussi le seul classement qui garde les
      // joueurs dont le score est nul.
      "id": "survivor",
      "enabled": true,
      "filename": "deaths.png",
      "title": "Roi des Morts",
      "description": "Nombre de morts",
      "sortOrder": "desc",
      "maxPlayers": 30,
      "rowsPerColumn": 10,
      "colors": {
        "titleBackground": "#8B0000",
        // Ces trois clés sont écrites par la configuration par défaut et ignorées
        // par le rendu. Conservées ici pour coller à ce que le mod livre vraiment.
        "firstPlaceBackground": "#FF0000",
        "secondPlaceBackground": "#CC0000",
        "thirdPlaceBackground": "#990000"
      }
    }
  ]
}
```

Le défaut livré complet — 30 classements, chaque classement de type avec sa couleur
officielle — est `src/main/resources/cobblecheesescore-default.json`. Supprime
`config/cobblecheesescore.json` et redémarre pour le retrouver.

---

← [Premiers pas](getting-started.fr.md) · [Classements](leaderboards.fr.md) →
