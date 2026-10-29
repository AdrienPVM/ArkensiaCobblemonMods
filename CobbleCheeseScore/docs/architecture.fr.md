[← CobbleCheeseScore](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](architecture.md)

# Architecture

16 fichiers Java, environ 2 940 lignes, aucun mixin, aucun item, bloc ou entité enregistré,
aucune commande. Le mod est un lecteur de disque, un trieur, un rendu `awt` et un
gestionnaire REST boulonnés ensemble par une classe statique qui tient les références.

---

## Arborescence des packages

```text
com.cobblecheesescore
├── CobbleCheeseScore.java      entrypoint, références statiques, cycle de vie, planificateur
├── cache/
│   └── CacheManager.java       map PlayerData en mémoire, map d'images, lastUpdate
├── config/
│   ├── Config.java             configuration globale, load/save, semis Conseil 4
│   └── ScoreboardConfig.java   la définition d'un classement
├── dashboard/
│   ├── CobbleCheeseScoreDashboardModule.java   l'entrypoint `cheesedash`
│   └── ScoresApi.java          toutes les routes REST, 728 lignes
├── data/
│   ├── DataCollector.java      détection du chemin du monde, découverte des fichiers par joueur
│   ├── PlayerData.java         les trois sources d'un joueur + les métriques dérivées
│   ├── PokedexNBTParser.java   speciesRecords → SpeciesRecord/FormRecord
│   └── CobblemonDataParser.java  les deux sources JSON
├── image/
│   └── ImageGenerator.java     rendu Graphics2D, 254 lignes
├── scoreboard/
│   ├── ScoreboardManager.java  calcul, le switch identifiant → mesure, Conseil 4
│   ├── PlayerScore.java        une ligne classée, Comparable, porte son sens de tri
│   ├── MiniGameManager.java    registre des mini-jeux + règle de points
│   └── MiniGameLegends.java    archive permanente des mini-jeux
└── utils/
    └── NBTReader.java          parseur NBT écrit à la main
```

## Qui possède quoi

**`CobbleCheeseScore`** est l'entrypoint et le localisateur de services. Chaque composant
est un champ statique privé avec un getter statique public, et `ScoresApi` les atteint de
cette façon plutôt qu'en recevant quoi que ce soit. Il n'y a aucune injection de dépendances
et aucune interface entre l'API et les gestionnaires. `onInitializeServer` construit
`Config`, `CacheManager` et `MiniGameManager` ; `SERVER_STARTED` construit `DataCollector`
et `ScoreboardManager`, lance un rafraîchissement et démarre le planificateur.

**`Config`** est tout le fichier de configuration sous forme d'objet mappé par Gson, plus
`load()`, `save(Path)` et le semeur Conseil 4. Il est mutable et partagé : `ScoresApi` mute
les objets `ScoreboardConfig` sur place puis appelle `save`. Il n'y a aucune copie et aucune
couche de validation.

**`DataCollector`** résout le chemin du monde une fois, dans son constructeur, puis
parcourt `<world>/pokedex` à chaque passe de collecte pour découvrir les joueurs. Il ne met
rien en cache ; les parseurs écrivent directement dans un `PlayerData` neuf par joueur et
par passe.

**`PlayerData`** détient les trois sources brutes sous forme de maps publiques mutables et
expose un getter par métrique. La centaine d'identifiants d'espèces légendaires vit ici dans
un `static final Set<String>`, ce qui est la seule connaissance du jeu codée en dur du mod.
Le total de 1 025 espèces utilisé pour le pourcentage du Pokédex vit dans
`ScoreboardManager.formatScore` à la place — deux constantes de générations Pokémon dans
deux fichiers différents.

**`ScoreboardManager`** possède la map des classements et le `switch` des mesures. Il
possède aussi `dynamicTitles`, la seule `ConcurrentHashMap` du mod, peuplée depuis les
exports Conseil 4.

**`ImageGenerator`** est sans état, à part sa référence `Config`. Il ne sait rien des
classements au-delà du `ScoreboardConfig` qu'on lui tend.

**`CacheManager`** est deux `HashMap` et un `long`. Il possède un `clearAll()` que personne
n'appelle.

**`ScoresApi`** n'est constitué que de méthodes statiques. C'est la seule classe qui importe
`com.cheesedash.*` en dehors du module.

**`NBTReader`** est un parseur NBT écrit de zéro : reniflage du GZIP, puis un lecteur
récursif sur les douze types de tags. Il existe parce que lire le fichier de Pokédex de
Cobblemon via le `NbtIo` de Minecraft impliquerait de toucher l'état du monde depuis un
thread autre que le thread serveur. La contrepartie est 117 lignes de code de format que le
mod maintient lui-même.

## L'état et le moment où il atteint le disque

| État | Vit dans | Écrit sur le disque |
|---|---|---|
| configuration globale et définitions de classements | `Config` | au premier chargement quand le fichier est absent ; quand un classement Conseil 4 est semé ; sur `PUT /scoreboards/:id` ; sur `PUT /config` |
| données des joueurs | `CacheManager.playerDataCache` | jamais |
| images rendues | `CacheManager.imageCache` | jamais — les octets PNG sont produits à chaque requête |
| classements calculés | `ScoreboardManager.scoreboards` | jamais |
| titres dynamiques | `ScoreboardManager.dynamicTitles` | jamais — relus depuis l'export Conseil 4 à chaque passe |
| sessions de mini-jeux | `MiniGameManager.games` | à chaque création, renommage, suppression, ajout et suppression de session |
| archive des mini-jeux | `MiniGameLegends.records` | à chaque session enregistrée |

Rien n'est écrit à l'extinction. `onServerStopping` arrête le planificateur et met la
référence au serveur à null ; tout ce qui est en mémoire est jeté. C'est sans danger
puisque chaque mutation a déjà écrit au passage, mais cela signifie qu'un crash ne perd rien
et qu'un arrêt propre n'enregistre rien non plus.

`MiniGameLegends.save()` écrit un fichier `.tmp` voisin et le déplace avec `ATOMIC_MOVE`,
avec un déplacement simple en repli. `Config.save()` et `MiniGameManager.save()` font un
`Files.writeString` direct sur le fichier vivant — un crash en cours d'écriture les tronque.

## Threading

Quatre sortes de threads touchent à l'état de ce mod.

| Thread | Ce qui y tourne |
|---|---|
| le thread d'initialisation du mod | `onInitializeServer` : chargement de la configuration, `CacheManager`, `MiniGameManager` (qui lit son fichier) |
| le thread serveur | `SERVER_STARTED` et `SERVER_STOPPING`, dont le rafraîchissement complet initial |
| `pool-N-thread-1` | le `ScheduledExecutorService`, un seul thread, qui lance `updateAll` tous les `updateIntervalMinutes` |
| `CheeseDash-HTTP` (il y en a 4) | tous les handlers de `ScoresApi` |

**Il n'y a aucun passage de relais vers le thread serveur, nulle part.** Pas
d'`executeOnServerThread`, pas de `server.execute`. C'est défendable : un rafraîchissement
lit des fichiers sur le disque et ne touche à aucun état du monde, aucune entité et aucun
bloc. Les deux seuls appels Minecraft de tout le chemin de données sont
`server.getPlayerManager().getPlayerList()` et `server.getUserCache().getByUuid(...)` dans
`DataCollector.getPlayerName`, deux recherches en lecture seule qui se trouvent être
raisonnablement tolérantes à un appel venu d'ailleurs — mais aucune des deux n'est
documentée comme thread-safe, et les appeler depuis un thread HTTP est le seul vrai pari de
sûreté des threads du mod.

### La course au rafraîchissement

`updateAll()` est atteignable depuis le thread du planificateur et depuis n'importe lequel
des quatre threads HTTP, et il n'est pas synchronisé.

- `ScoreboardManager.scoreboards` est une `HashMap` ordinaire, mutée par `put` pendant une
  passe et lue par `getScoreboard` sur les threads HTTP.
- `CacheManager.imageCache` et `playerDataCache` sont des `HashMap` ordinaires, et
  `setPlayerData` **remplace** la référence de la map en pleine passe.
- Deux passes concurrentes entrelacent librement leurs appels à `put`.

En pratique, la fenêtre fait une seconde ou deux toutes les dix minutes, et la conséquence
d'un accident est une image périmée ou à moitié mise à jour. Le mode de défaillance qui mord
vraiment est une `ConcurrentModificationException` ou une `HashMap` corrompue sous une
boucle serrée d'appels `POST /refresh`. Ne scripte pas cet endpoint sur un intervalle court.

Un seul `synchronized` sur `updateAll` plus des `ConcurrentHashMap` dans `CacheManager`
fermeraient cette brèche. Ça n'a pas été fait.

### Rendu hors du thread serveur

`Graphics2D`, `BufferedImage` et `ImageIO` tournent tous sur le thread qui les appelle, sans
qu'aucune boucle d'événements AWT soit impliquée. Rien ne définit
`-Djava.awt.headless=true` ; cela n'a pas été nécessaire, parce qu'aucune des classes
utilisées n'exige un affichage. Une JVM avec un chemin de polices cassé lèvera dans
`new Font(...)`, attrapé par classement par `updateAll`.

## Intégration dashboard

`fabric.mod.json` déclare deux entrypoints :

```json
"entrypoints": {
  "server":     ["com.cobblecheesescore.CobbleCheeseScore"],
  "cheesedash": ["com.cobblecheesescore.dashboard.CobbleCheeseScoreDashboardModule"]
}
```

CheeseDash appelle `FabricLoader.getEntrypoints("cheesedash", DashboardModule.class)` au
démarrage et enregistre ce qu'il trouve. `getPrefix()` renvoie `scores`, ce qui mappe le
module sur `/api/v1/scores/*` derrière le middleware d'authentification.
`getPublicPrefix()` renvoie aussi `scores`, ce qui fait créer par CheeseDash un contexte
**non authentifié** sur `/scores/` — enregistré avant `/api/`, délibérément hors du contrôle
d'authentification, pour qu'un cadre d'image en jeu puisse récupérer un PNG sans token.

Le module lui-même ne détient que la référence au `MinecraftServer` et répond `503` tant
qu'elle est null. Tout le reste est délégué à `ScoresApi` par un appel statique.

### Aucun contrôle de rôle qui lui soit propre

`ScoresApi` n'appelle jamais `WebDashboard.requireWrite` ni `requireAdmin`. La frontière des
rôles est pourtant appliquée — par le cœur, pas par ce module. Le répartiteur de modules de
CheeseDash, dans `DashboardApi`, teste `requireWrite` sur toute requête non-`GET` avant de
rendre la main :

```java
if (!method.equals("GET") && !WebDashboard.requireWrite(exchange)) {
    return;
}
module.handleRequest(exchange, subRoute);
```

Donc dans cette version, toute écriture sous `/api/v1/scores/` exige `OPERATOR` ou au-dessus,
et tout `GET` est ouvert à tous les rôles, `READ_ONLY` compris. Ce que coûte l'absence
d'appels locaux est ailleurs : aucune route de ce module ne peut exiger `SUPER_ADMIN`, parce
qu'`OPERATOR` est la seule barre que le répartiteur connaisse ; une route mutante exposée en
`GET` ne serait pas gardée du tout, le répartiteur ne regardant que la méthode HTTP ; et une
seule modification de ce répartiteur ferait tomber tous les contrôles d'un coup, sans seconde
ligne de défense ici pour la rattraper. Documenté avec ses conséquences dans
[dashboard](dashboard.fr.md#rôle-requis).

## Mixins

Aucun. `"mixins": []` dans `fabric.mod.json`, et aucun fichier de configuration de mixin
n'existe. Le mod lit des fichiers et sert du HTTP ; il n'a jamais besoin de changer le
comportement de Minecraft. C'est la raison principale pour laquelle il est inhabituellement
robuste face aux mises à jour de Cobblemon par rapport aux autres mods de ce dépôt : il
dépend du **format sur disque** de Cobblemon, pas de ses classes. Il n'y a aucune dépendance
à la compilation envers Cobblemon.

## Points d'extension pour un contributeur

**Une nouvelle mesure.** Ajoute un `case` à `ScoreboardManager.calculateScore`, en général
appuyé sur un nouveau getter de `PlayerData`, et éventuellement un `case` dans
`formatScore`. Puis ajoute une entrée de configuration avec cet identifiant. La
configuration seule ne fait rien — un identifiant inconnu journalise
`Unknown scoreboard ID` et score 0.

**Une nouvelle source de données.** Ajoute un parseur dans `com.cobblecheesescore.data` et
appelle-le depuis `DataCollector.collectPlayerData`, à côté des trois appels existants.
`PlayerData` a déjà la forme qu'il faut pour des maps brutes plus des getters dérivés.

**Un classement alimenté de l'extérieur.** Suis le motif Conseil 4 : fais écrire par un
autre mod un fichier JSON classé dans `config/`, ajoute le préfixe d'identifiant au
court-circuit de `calculateScoreboard`, et un chargeur calqué sur `loadConseil4Board`. C'est
la façon de classer quelque chose que ce mod ne peut pas voir, et c'est ainsi que CobbleCap
alimente les classements de ligue.

**Une autre apparence.** `ImageGenerator` est autonome et reçoit
`(ScoreboardConfig, List<PlayerScore>, String dynamicTitle)`. Le remplacer entièrement ne
casse rien d'autre.

**La poussée WebSocket.** Rien ici ne diffuse. Un `DashboardRegistry.broadcast(...)` à la
fin d'`updateAll` donnerait au dashboard des notifications de rafraîchissement en direct.

---

## Faiblesses connues et compromis assumés

Lis cette section avant de changer quoi que ce soit.

**Aucun contrôle de rôle propre sur l'API REST.** Les écritures sont barrées à `OPERATOR`
par le répartiteur de CheeseDash, donc la frontière tient aujourd'hui. Le coût est
structurel : aucune route ici ne peut demander `SUPER_ADMIN`, un `GET` mutant serait sans
garde, et tout le module perd sa protection si ce seul contrôle du répartiteur change.

**La course au rafraîchissement.** Décrite plus haut. Des `HashMap` ordinaires mutées depuis
deux sortes de threads sans aucune synchronisation.

**Neuf clés de configuration mortes.** `port`, `enableWebInterface`, `enableJsonApi`,
`enableDiscordWebhook`, `discordWebhookUrl`, `enableHistory`, `historyRetentionDays`,
`defaultMaxPlayers`, `defaultRowsPerColumn`, plus `filename` sur chaque classement. Les
trois premières sont des reliquats d'un serveur web autonome sur le port 44310, supprimé
quand le mod est passé à CheeseDash. Le duo Discord n'a jamais eu d'implémentation — il n'y
a aucun code de webhook dans ce mod et il n'y en a jamais eu dans cette version de
l'arborescence. `enableHistory` et `historyRetentionDays` décrivent de la même façon une
fonctionnalité d'historique qui n'existe pas. Elles sont conservées pour que les fichiers de
configuration existants restent analysables, ce qui est aussi la raison pour laquelle elles
continuent d'induire les gens en erreur. Les retirer serait une bonté.

**Quatre clés de couleur mortes.** `firstPlaceBackground`, `secondPlaceBackground`,
`thirdPlaceBackground` et `topPlayerText` sont dans la configuration par défaut livrée et
sont ignorées par le rendu ; `getRowBackgroundColor(int, ScoreboardConfig)` reçoit la
configuration du classement et ne la lit jamais. Les couleurs du podium sont codées en dur
en or, argent et bronze.

**`PUT /config` ne fait rien.** Sa seule branche conditionnelle journalise le changement
demandé et ne l'applique pas, derrière un commentaire périmé qui prétend que
`ScoreboardConfig` n'a pas de setter. `setEnabled` existe. La route répond quand même
`{"saved":true}`.

**`ImageGenerator.java.backup` est versionné.** Une copie morte d'un rendu plus ancien,
posée à côté du fichier vivant dans `src/main/java/com/cobblecheesescore/image/`. Elle n'est
pas compilée — l'extension la préserve — mais elle va dériver et semer la confusion.
Supprime-la.

**`fabric.mod.json` pointe sur une icône absente.**
`"icon": "assets/cobblecheesescore/icon.png"`, alors que
`src/main/resources/assets/cobblecheesescore/` ne contient que `lang/`. Mod Menu affiche un
placeholder.

**La police est un souhait, pas une dépendance.** Chaque appel `new Font("Inter", …)`
retombe silencieusement sur une police logique sur un hôte qui n'a pas Inter installée, ce
qui est le cas de la plupart. Les images sont rendues ; la typographie n'est pas celle qui
avait été conçue.

**De la connaissance du jeu codée en dur en deux endroits.** L'ensemble des espèces
légendaires dans `PlayerData` et le total de 1 025 espèces du Pokédex dans
`ScoreboardManager`. Les deux vieillissent à chaque génération Pokémon, et ni l'un ni
l'autre n'est configurable. Une installation Cobblemon avec un datapack d'espèces rend le
pourcentage du Pokédex faux.

**`survivor` est mal nommé.** Il est livré avec `sortOrder: "desc"` sur un compteur de
morts : il classe donc le *plus grand* nombre de morts en premier. Le titre *Roi des Morts*
est honnête là-dessus ; l'identifiant ne l'est pas.

**Des identifiants non validés.** Les identifiants de mini-jeux sont acceptés tels quels.
Un identifiant contenant `/` rend ses propres routes inatteignables ; un identifiant valant
`global` est masqué par la route du classement global. Les identifiants de classements
viennent d'un fichier de configuration écrit par un administrateur : ils sont donc moins
exposés.

**`getPlayerName` retombe sur huit caractères d'UUID** quand un joueur n'est ni connecté ni
dans le cache utilisateurs. Ces entrées ressemblent à du charabia tronqué dans une image
rendue, et rien ne permet de les distinguer d'un vrai nom de cette longueur.

**Un parseur NBT écrit à la main.** 117 lignes qui réimplémentent un format que Minecraft
analyse déjà. Cela évite un problème de sûreté des threads et ça fonctionne, mais un
changement de format ou un type de tag inattendu lève `Unknown tag type: <n>`, attrapé par
`PokedexNBTParser` et journalisé comme un échec pour ce seul joueur.

**Du code mort.** `CacheManager.clearAll`, `PlayerScore.isAscending`,
`PlayerData.getPokedexSeen`, `PlayerData.getTotalCaptureCount`,
`MiniGameManager.getLegends`, `MiniGameLegends.getAll` et `getByGameId`,
`ScoreboardManager.getAllScoreboards` et `getDynamicTitle`,
`ScoreboardConfig.getFilename`, et la surcharge à deux arguments de
`ImageGenerator.generateScoreboard` n'ont tous aucun appelant. Les deux concernant les
légendes sont les plus notables : l'archive est écrite et jamais relue par aucune route, de
sorte que le panthéon des mini-jeux n'est lisible qu'en ouvrant le fichier JSON.

---

← [API dashboard](dashboard.fr.md) · [Dépannage](troubleshooting.fr.md) →
