[← CheeseMusic](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](zone-music.md)

# Musique de zone

Le côté serveur du mod, en une phrase : tous les quelques ticks, déterminer dans quelle
région se trouve chaque joueur, et quand cette réponse change, dire à son client de
démarrer ou d'arrêter une playlist. Cette page couvre le modèle, les règles de résolution,
le cycle de vie et le paquet. Ce que le client fait ensuite du paquet, c'est
[lecture](playback.fr.md).

---

## Le modèle

Trois objets, aucune base de données.

**Une zone** est une région CheeseGuard. CheeseMusic ne définit, ne stocke et n'édite
aucune zone — il les lit par réflexion, sous la forme
`CheeseGuardHook.ZoneInfo(id, name, priority, tags)`. Crée et façonne tes régions avec les
outils de CheeseGuard ; CheeseMusic ne demande jamais que « quelles régions contiennent ce
point ».

**Une assignation** (`MusicAssignment`) lie une clé de recherche à une playlist. Ses champs
sont l'identifiant de zone, un nom d'affichage, la liste de pistes, le mode de lecture, un
volume et deux durées de fondu.
[Configuration](configuration.fr.md#objets-dassignation) les liste avec types et valeurs
par défaut. Les assignations vivent dans `config/cheesemusic.json` et nulle part ailleurs.

**Un état de joueur** (`ZoneMusicTracker.PlayerMusicState`) n'existe qu'en mémoire : quelle
clé le joueur entend actuellement, et s'il est en combat Cobblemon. Il est créé au premier
tick, supprimé à la connexion comme à la déconnexion, et jamais écrit sur le disque. Un
redémarrage signifie que chaque joueur connecté est réévalué de zéro, ce qui est sans
conséquence : il reçoit un nouveau paquet `play` dans la demi-seconde qui suit le retour du
serveur.

Une clé de recherche n'est pas toujours un identifiant de zone. C'est soit :

- un **identifiant de zone**, qui correspond exactement à une région, soit
- `tag:<tag>`, qui correspond à n'importe quelle région portant ce tag CheeseGuard.

Les deux formes cohabitent dans la même table `assignments`, et c'est pourquoi la clé et le
champ interne `zoneId` peuvent différer : pour une assignation par tag, la clé est
`tag:cave` tandis que `zoneId` contient ce que le dashboard y a écrit.

---

## Ordre de résolution

`ZoneMusicTracker.resolveZoneMusic(worldId, x, y, z)` transforme une position en clé de
recherche, ou en `null` pour le silence :

1. Si `CheeseGuardHook.isAvailable()` est faux, renvoyer `null` immédiatement. Pas de
   CheeseGuard, pas de musique, jamais, pour personne.
2. Demander à CheeseGuard toutes les régions contenant le point :
   `getRegionsAt(worldId, x, y, z)`. `worldId` est l'identifiant complet de la dimension,
   `minecraft:overworld` et consorts.
3. Parcourir cette liste **dans l'ordre où CheeseGuard l'a renvoyée**. Pour chaque région :
   - si `assignments` a une entrée sous l'identifiant de la région, renvoyer cet
     identifiant ;
   - sinon, pour chacun des tags de la région dans l'ordre, si `assignments` a une entrée
     sous `tag:<tag>`, renvoyer `tag:<tag>`.
4. Rien n'a correspondu : renvoyer `null`.

Une assignation directe de zone bat donc une assignation par tag **sur la même région**,
mais une assignation par tag sur une région plus tôt dans la liste bat une assignation
directe sur une région plus tard. L'ordre des régions est celui que produit le
`getRegionsAt` de CheeseGuard.

### La priorité est lue mais jamais utilisée

`ZoneInfo` porte la `priority` de CheeseGuard, et `GET /api/v1/music/zones` la rapporte,
mais `resolveZoneMusic` ne trie jamais dessus. Des régions qui se chevauchent se résolvent
donc selon l'ordre d'itération de CheeseGuard, et non selon la priorité que tu as réglée
dans CheeseGuard. Savoir si cet ordre d'itération reflète déjà la priorité dépend du
`RegionManager.getRegionsAt` de CheeseGuard lui-même, que ce mod appelle par réflexion sans
l'inspecter. Considère le comportement de régions assignées qui se chevauchent comme
indéfini, et évite de donner à deux régions superposées des musiques contradictoires. C'est
un vrai trou, pas une subtilité — il est repris dans
[architecture](architecture.fr.md#faiblesses-connues).

---

## La boucle de tick

`CheeseMusicMod.onTick` est enregistré sur `ServerTickEvents.END_SERVER_TICK` et ne fait
rien à moins que le tracker existe **et** que `config.isEnabled()` soit vrai. Ensuite,
`ZoneMusicTracker.tick` :

```java
tickCounter++;
if (tickCounter % config.getCheckIntervalTicks() != 0) return;
```

Un tick sur `checkIntervalTicks`, il parcourt toute la liste des joueurs connectés et
appelle `tickPlayer` sur chacun, avec un `try`/`catch` par joueur qui journalise au niveau
`debug`. Un joueur dont la recherche lève une exception n'arrête pas les autres.

`tickPlayer`, dans l'ordre :

1. Demander `CobblemonBattleHook.isInBattle(player)`, dans son propre `try`/`catch`.
2. Si le joueur est en combat : s'il a actuellement de la musique et n'était pas déjà
   marqué comme combattant, envoyer un `stop` avec un **fondu codé en dur de 40 ticks**,
   poser le drapeau, et sortir. `currentZoneId` est délibérément laissé intact.
3. Si le drapeau était posé et que le joueur n'est plus en combat, effacer le drapeau et
   mettre `currentZoneId = null`. Cela force la comparaison suivante à voir un changement,
   donc la musique de zone reprend après le combat.
4. Résoudre la clé depuis la dimension et la position en blocs courantes du joueur.
5. Si la clé diffère de `currentZoneId` :
   - s'il y avait une clé précédente, envoyer un `stop`, avec le fondu de sortie pris sur
     l'ancienne assignation, ou sur `defaultFadeOutTicks` quand il n'y avait pas
     d'assignation ;
   - si la nouvelle clé est non-`null` et possède une assignation, envoyer un `play` ;
   - enregistrer la nouvelle clé.

L'étape 5 est le seul endroit où un paquet part pendant un déplacement normal. Un joueur
immobile, ou qui bouge à l'intérieur d'une même région, ne génère aucun trafic réseau.

### Connexion et déconnexion

`ServerPlayConnectionEvents.JOIN` supprime l'état du joueur, et `DISCONNECT` fait de même.
La suppression à la **connexion** compte : le client d'un joueur qui se reconnecte a remis
son propre `ZoneMusicPlayer` à `IDLE`, donc le serveur doit oublier ce qu'il croyait que le
joueur entendait, sinon il comparerait la nouvelle position à une clé périmée et
n'enverrait rien.

`SERVER_STOPPING` vide tous les états. Aucun paquet n'est envoyé, parce que les clients sont
sur le point d'être déconnectés de toute façon et se remettront à zéro eux-mêmes.

---

## Étouffement en combat

Avec Cobblemon installé, `CobblemonBattleHook` localise par réflexion la méthode de
recherche à un seul `UUID` de `BattleRegistry` et traite un retour non-`null` comme « en
combat ». Tant que c'est vrai, la musique de zone est arrêtée et aucune nouvelle zone n'est
résolue, ce qui laisse la musique de combat de Cobblemon seule en scène.

Deux choses à savoir sur ce hook. Il choisit sa méthode en balayant
`BattleRegistry.getDeclaredMethods()` à la recherche d'une méthode prenant un seul `UUID`
et renvoyant autre chose que `void`, en préférant un nom contenant à la fois `battle` et
`player`, et en retombant sur *n'importe quelle* méthode à un seul `UUID` renvoyant un type
référence. Ce repli est une devinette : si Cobblemon se dote un jour d'une seconde méthode
de ce genre déclarée plus tôt, le hook s'attache silencieusement à la mauvaise. La ligne de
log nomme la méthode choisie, et c'est le seul moyen de le savoir :

```text
[CheeseMusic] Cobblemon detected — battle detection via getBattleByParticipatingPlayerId()
```

Et le fondu du `stop` de combat est le littéral `40` de `sendStop(player, 40)`. Il n'est pas
configurable et ignore les `fadeOutTicks` de l'assignation. Deux secondes ont été choisies
pour laisser la place à l'introduction de combat de Cobblemon ; si tu veux une autre valeur,
change cette ligne.

Sans Cobblemon, `isInBattle` renvoie toujours `false` et toute cette branche est du poids
mort.

---

## Le paquet

Un seul type de payload, `cheesemusic:zone_music`, enregistré uniquement du serveur vers le
client (`PayloadTypeRegistry.playS2C()`). Quatre champs, écrits dans cet ordre :

| Champ | Type | Signification |
|---|---|---|
| `action` | chaîne | `play`, `stop` ou `fade_to` |
| `tracksJson` | chaîne | un objet JSON pour `play` ; chaîne vide pour les autres, que le lecteur retransforme en `null` |
| `volume` | flottant | le volume de l'assignation ; `0` sur un `stop` |
| `fadeTicks` | entier | `fadeInTicks` sur un `play`, le fondu de sortie résolu sur un `stop` |

`tracksJson` pour un `play` est construit par `sendPlay` et ressemble à ceci :

```json
{"tracks":["cheesemusic:spawn_theme","cheesemusic:spawn_theme_night"],"mode":"RANDOM"}
```

Le préfixe `cheesemusic:` est ajouté ici, entrée par entrée, et c'est pour cela qu'une
assignation stocke des noms nus. Envoyer du JSON sous forme de chaîne dans un payload
binaire n'est pas élégant, mais cela limite le codec à quatre écritures primitives et
laisse la playlist grandir sans changement de protocole.

`fade_to` est traité côté client — `ZoneMusicPlayer.fadeTo` règle le volume cible et rien
d'autre, parce qu'une instance de son streamée ne peut pas être regagnée en douceur.
**Aucun code serveur ne l'envoie jamais.** Il est mort-né, conservé parce qu'une future
rampe de volume par zone l'utiliserait.

Un client sans CheeseMusic installé n'a aucun récepteur enregistré pour l'identifiant de
payload et jette le paquet. Le serveur ne le sait pas et s'en moque, donc un serveur à
clients mixtes fonctionne — les joueurs sans le jar entendent simplement la musique
vanilla.

---

## Cas limites à connaître

**Deux joueurs, une frontière.** L'état est strictement par joueur. Deux joueurs de part et
d'autre d'un bord de région reçoivent des paquets différents au même tick et entendent des
pistes différentes. C'est tout l'intérêt de procéder par joueur plutôt que par un son de
niveau monde.

**Changement de dimension.** L'identifiant de dimension fait partie des données de
résolution, donc une téléportation vers le Nether provoque une nouvelle résolution à la
vérification suivante. Si la destination n'a aucune région assignée, le joueur reçoit un
`stop` ; le fondu de sortie vient de la région qu'il a quittée.

**Une assignation supprimée pendant que quelqu'un écoute.** La vérification suivante résout
une autre clé (ou `null`), et le fondu du `stop` retombe sur `defaultFadeOutTicks`, parce
que `config.getAssignment(oldKey)` renvoie maintenant `null`. La musique s'éteint en fondu
au lieu d'être coupée net, ce qui est le bon comportement, obtenu par accident.

**Une liste `tracks` vide.** Le serveur envoie quand même un `play`. Le client analyse une
playlist vide, ne trouve aucune piste à choisir, et ramène les couches vanilla et
ReactiveMusic. Le résultat net est du silence, atteint par une transition — pas une erreur.

**`lastPos` et `lastSendTime`.** Les deux sont écrits à chaque tick et lus nulle part. Ils
ressemblent au début d'un anti-rebond basé sur le déplacement, jamais terminé.

---

← [Packs de sons](sound-packs.fr.md) · [Lecture](playback.fr.md) →
