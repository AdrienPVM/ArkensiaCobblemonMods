[← CheeseMusic](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](configuration.md)

# Configuration

CheeseMusic garde tout dans un seul fichier, côté serveur :

```text
<racine du serveur>/config/cheesemusic.json
```

Le chemin vient de `srv.getRunDirectory().resolve("config")` dans
`CheeseMusicMod.onServerStarted` : il suit donc le répertoire d'exécution du serveur, et
non l'assistant de répertoire de configuration de Fabric. Sur une installation
client-seul (solo), le répertoire d'exécution est le dossier `.minecraft`, et le fichier
atterrit dans `.minecraft/config/cheesemusic.json` pour le serveur intégré.

Si le fichier n'existe pas au démarrage du serveur, `CheeseMusicConfig.load` en écrit un
neuf avec les valeurs par défaut ci-dessous et un objet `assignments` vide. Le fichier est
lu une seule fois, à `SERVER_STARTED`. L'éditer à la main pendant que le serveur tourne
n'a aucun effet, et la prochaine écriture du dashboard écrasera tes modifications — voir
[Rechargement](#rechargement-et-réécriture).

---

## Clés de premier niveau

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `enabled` | booléen | `true` | Interrupteur général. À `false`, `CheeseMusicMod.onTick` saute entièrement le tracker : aucune zone n'est évaluée et aucun paquet n'est envoyé. Les clients continuent de jouer ce qu'on leur a dit en dernier jusqu'à ce qu'ils quittent le monde. |
| `defaultFadeInTicks` | entier | `60` | Stockée et exposée par le dashboard, mais **jamais lue par le chemin de lecture**. Voir [Les clés qui ne font rien](#les-clés-qui-ne-font-rien). |
| `defaultFadeOutTicks` | entier | `60` | Durée du fondu de sortie, en ticks, utilisée quand l'assignation de la zone qu'un joueur écoutait a été supprimée avant qu'il en sorte : il ne reste alors rien où lire une valeur. 60 ticks = 3 secondes. |
| `checkIntervalTicks` | entier | `10` | Fréquence à laquelle le serveur réévalue dans quelle zone se trouve chaque joueur connecté. 10 ticks = deux fois par seconde. |
| `defaultVolume` | flottant | `0.7` | Stockée et exposée par le dashboard, mais **jamais lue par le chemin de lecture**. Voir [Les clés qui ne font rien](#les-clés-qui-ne-font-rien). |
| `assignments` | objet | `{}` | Table associant une clé de recherche à un objet d'assignation. Une clé est soit un identifiant de zone CheeseGuard, soit `tag:<tag>` pour viser n'importe quelle zone portant ce tag. Voir [musique de zone](zone-music.fr.md#ordre-de-résolution). |

### `checkIntervalTicks`

`ZoneMusicTracker.tick` incrémente un compteur à chaque tick serveur et sort
immédiatement sauf si `tickCounter % checkIntervalTicks == 0`. La valeur fixe donc le
délai maximal entre le franchissement d'une frontière de zone par un joueur et le
changement de musique : au défaut de `10`, jusqu'à une demi-seconde.

Descends-la à `1` et la recherche de zone tourne pour chaque joueur connecté à chaque
tick. Chaque recherche est un appel par réflexion vers CheeseGuard (`CheeseGuardHook`),
donc le coût croît avec le nombre de joueurs. Monte-la au-delà de `40` et les
franchissements de frontière se détachent de la musique, parce qu'un joueur peut avancer
de plusieurs blocs dans la nouvelle zone avant que la piste change.

`CheeseMusicConfig.setCheckIntervalTicks` borne la valeur entre 1 et 100. Cette borne ne
s'applique que sur le chemin d'écriture du dashboard. `load()` affecte le champ
directement, donc un fichier édité à la main peut contenir `0` — ce qui fait lever une
`ArithmeticException` à `tickCounter % 0` dès le premier tick. N'écris jamais `0` dans
cette clé à la main.

### `defaultFadeOutTicks`

Utilisée à un seul endroit, `ZoneMusicTracker.tick`, quand la zone d'un joueur change et
que la clé qu'il écoutait n'a plus d'assignation d'où lire un fondu de sortie :

```java
int fadeOut = oldAssignment != null ? oldAssignment.getFadeOutTicks() : config.getDefaultFadeOutTicks();
```

Le tracker ne retient que des clés qui avaient une assignation au moment où il les a
résolues : un joueur dans une région non assignée n'écoute rien et ne reçoit aucun `stop`
en la quittant. Le repli ne joue que dans un cas : l'assignation a été supprimée depuis le
dashboard pendant que le joueur était à l'intérieur. Tout autre `stop` lit les
`fadeOutTicks` de l'assignation que le joueur quitte. Ce que le client fait d'une durée de
fondu est décrit dans
[lecture](playback.fr.md#les-fondus-sont-ceux-de-reactivemusic-pas-les-tiens), et ce n'est
pas ce que le nom des clés suggère.

### Les clés qui ne font rien

`defaultFadeInTicks` et `defaultVolume` sont chargées, sauvegardées, servies par
`GET /api/v1/music/config` et acceptées par `PUT /api/v1/music/config`, puis plus jamais
lues. Rien dans `ZoneMusicTracker`, `MusicAssignment` ni le package client ne les
consulte. Une nouvelle assignation créée depuis le dashboard porte ses propres
`fadeInTicks` et `volume`, fournis par l'appelant, et `MusicAssignment.fromJson` retombe
sur les littéraux codés en dur `60` et `0.7f` plutôt que sur ces clés de configuration.

Les modifier est sans danger et ne change rien d'audible. Elles sont documentées ici parce
qu'elles apparaissent dans le fichier et dans l'API, et qu'un lecteur qui met
`defaultVolume` à `0.2` sans entendre de différence mérite de savoir pourquoi.

---

## Objets d'assignation

Chaque valeur sous `assignments` est sérialisée par `MusicAssignment.toJson` et relue par
`MusicAssignment.fromJson`.

| Champ | Type | Défaut si absent | Effet |
|---|---|---|---|
| `zoneId` | chaîne | **obligatoire** | L'identifiant de zone CheeseGuard auquel cette assignation se lie. `fromJson` appelle `obj.get("zoneId").getAsString()` sans vérifier la présence du champ : une entrée qui l'omet lève une exception et arrête le chargement à cet endroit ; les clés et assignations lues avant elle sont gardées, celles d'après sont perdues, et la prochaine écriture du dashboard enregistre cette table tronquée par-dessus le fichier. |
| `zoneName` | chaîne | retombe sur `zoneId` | Libellé d'affichage seulement. Montré dans le dashboard ; jamais utilisé pour la correspondance. |
| `tracks` | tableau de chaînes | `[]` | Noms de pistes nus — le nom du fichier `.ogg` sans son extension, après assainissement. **Aucun préfixe de namespace.** `ZoneMusicTracker.sendPlay` ajoute `cheesemusic:` lui-même. Avec une liste vide, la zone se résout quand même et un `play` part quand même, mais le client l'ignore et reste `IDLE` : la musique en cours continue. |
| `mode` | chaîne | `RANDOM` | Au choix `RANDOM`, `SEQUENTIAL`, `SHUFFLE`. Une valeur inconnue est avalée par le bloc `catch (IllegalArgumentException ignored)` et laisse le mode à `RANDOM`. |
| `volume` | flottant | `0.7` | Transmis au client puis au volume du `SoundInstance`. Le curseur de musique vanilla s'applique encore par-dessus. |
| `fadeInTicks` | entier | `60` | Le temps que le client passe à éteindre ReactiveMusic **avant** que la piste de cette zone démarre. La piste démarre ensuite au `volume` plein, sans fondu propre. Plancher de 20 ticks côté client. |
| `fadeOutTicks` | entier | `60` | Le temps que le client passe à rallumer ReactiveMusic **après** que la piste de cette zone a été coupée. Plancher de 20 également. |

La clé de la table et le `zoneId` interne sont écrits à l'identique par le dashboard
(`config.putAssignment(zoneId, assignment)` avec la même chaîne), mais rien n'impose
qu'ils concordent. Les recherches passent par la clé de la table
(`config.getAssignment(zoneId)`) : si tu édites le fichier à la main et désynchronises les
deux, la clé gagne et `zoneId` devient un champ purement cosmétique.

### `mode`

La chaîne de mode voyage jusqu'au client dans le paquet, et **c'est le client qui décide
de l'ordre**. Le code qui compte est `ZoneMusicPlayer.pickNextIndex` :

- Une seule piste dans la liste, ou zéro : le mode n'a pas d'importance. Zéro piste ne
  joue rien.
- `SEQUENTIAL` — la liste dans l'ordre du fichier de configuration, en boucle.
- `SHUFFLE` — la liste est mélangée une fois à l'arrivée du paquet, jouée dans cet ordre,
  puis remélangée chaque fois qu'elle arrive au bout. Un vrai sac mélangé.
- `RANDOM` — un tirage uniforme qui rejette l'index qui vient d'être joué, donc une même
  piste ne se répète jamais deux fois de suite. Avec exactement deux pistes, cela alterne,
  puisque rejeter l'index précédent ne laisse qu'un seul choix.

`MusicAssignment.pickTrackIndex`, côté serveur, implémente les trois mêmes modes et n'est
appelée par personne. Sa branche `SHUFFLE` est une copie de `SEQUENTIAL`, ce qui n'est
inoffensif que parce que la méthode est morte. Ne raisonne pas à partir d'elle.

### Les noms de pistes ne sont pas validés à l'écriture

`MusicApi` accepte n'importe quelles chaînes dans `tracks`. Rien ne vérifie qu'un `.ogg`
correspondant existe. `SoundScanner.getAvailableTracks()` détient la liste des noms
trouvés sur le disque et `GET /api/v1/music/tracks` la sert pour que le dashboard propose
un sélecteur, mais le chemin d'écriture des assignations ne la consulte jamais. Un nom
inexistant produit du silence sur le client, un `warn` d'une ligne dans le log client, et
absolument rien côté serveur.

Écrire ici un identifiant avec namespace est un piège précis. `sendPlay` construit
`"cheesemusic:" + track`, donc `arkensia:volcano` devient `cheesemusic:arkensia:volcano`,
qu'`Identifier.of` rejette côté client. [Packs de sons](sound-packs.fr.md) explique
comment vérifier qu'un nom existe vraiment.

---

## Rechargement et réécriture

| Action | Effet |
|---|---|
| Démarrage du serveur | Fichier lu une fois, dans `SERVER_STARTED`. |
| Édition à la main serveur allumé | Ignorée. La copie en mémoire fait autorité. |
| Écriture dashboard (`POST`/`PUT`/`DELETE` sur les assignations, `PUT` sur la config) | Modifie la copie en mémoire, puis appelle `config.save()`, qui réécrit tout le fichier depuis la mémoire. **Toute édition manuelle faite depuis le démarrage est perdue à cet instant.** |
| Arrêt du serveur | Aucune sauvegarde. `onServerStopping` ne fait que vider le tracker. |

Il n'y a aucune commande de rechargement et aucun branchement sur `/reload`. Pour
appliquer une édition manuelle, redémarre le serveur. Si tu comptes malgré tout éditer le
fichier d'un serveur en marche, arrête d'abord d'utiliser le dashboard : sa prochaine
écriture balaiera ton travail.

---

## Un fichier complet annoté

```json
{
  "enabled": true,
  "defaultFadeInTicks": 60,
  "defaultFadeOutTicks": 90,
  "checkIntervalTicks": 10,
  "defaultVolume": 0.7,
  "assignments": {
    "spawn_plaza": {
      "zoneId": "spawn_plaza",
      "zoneName": "Spawn Plaza",
      "tracks": [
        "spawn_theme",
        "spawn_theme_night"
      ],
      "mode": "RANDOM",
      "volume": 0.7,
      "fadeInTicks": 60,
      "fadeOutTicks": 60
    },
    "volcano_cave": {
      "zoneId": "volcano_cave",
      "zoneName": "Volcano Cave",
      "tracks": [
        "volcano"
      ],
      "mode": "SEQUENTIAL",
      "volume": 0.45,
      "fadeInTicks": 20,
      "fadeOutTicks": 120
    }
  }
}
```

Ce fichier se lit de haut en bas :

- `defaultFadeOutTicks` vaut `90`, ce qui ne compte que si l'une de ces assignations est
  supprimée pendant qu'un joueur s'y trouve : ReactiveMusic a alors 4,5 secondes pour
  remonter. Sortir normalement de `volcano_cave` lui en laisse 6, grâce aux `fadeOutTicks`
  de `120` propres à cette région.
- `spawn_plaza` a deux pistes en mode `RANDOM`, ce qui, avec deux pistes, alterne : un
  échange A/B à chaque fin de piste. Chaque entrée sur la place commence par
  `spawn_theme`, parce que le client remet son index à `-1` à chaque nouvelle liste et que
  la règle des deux pistes choisit alors l'index `0`.
- `volcano_cave` laisse une seconde (`20` ticks) à ReactiveMusic pour s'effacer, puis
  démarre aussitôt sa piste à volume faible. Une seule piste en
  mode `SEQUENTIAL` la boucle indéfiniment tant que le joueur reste à l'intérieur.
- Les trois noms renvoient à `spawn_theme.ogg`, `spawn_theme_night.ogg` et `volcano.ogg`
  posés dans `resourcepacks/CheeseMusic/assets/cheesemusic/sounds/` sur le serveur, et au
  même pack installé sur chaque client. CheeseMusic ne livre aucun audio.

`defaultFadeInTicks` et `defaultVolume` sont présentes parce que `save()` les écrit
toujours. Elles n'ont aucun effet.

---

← [Lecture](playback.fr.md) · [API dashboard](dashboard.fr.md) →
