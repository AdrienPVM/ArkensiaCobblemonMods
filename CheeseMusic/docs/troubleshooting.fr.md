[← CheeseMusic](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](troubleshooting.md)

# Dépannage

La musique de zone échoue en silence à peu près partout. Pas d'erreur dans le chat, pas de
texte rouge, pas de plantage — une région ne joue simplement rien, et six causes
différentes produisent ce même symptôme. Le plus rapide est de déterminer quelle moitié de
la chaîne est cassée, donc commence ici :

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:44311/api/v1/music/status
```

Si ton nom apparaît avec le `zoneId` attendu, le serveur a fait son travail et la faute est
côté client. S'il n'apparaît pas, la faute est côté serveur. Tout ce qui suit est classé
selon cette coupure.

---

## Le mod ne se charge pas du tout

**Cause.** Une dépendance de `depends` n'est pas satisfaite — Fabric Loader sous 0.16.0, pas
de Fabric API, Java sous 21, ou une version de Minecraft qui n'est pas la 1.21.1. Ou bien
deux copies de `cheesemusic-*.jar` dans `mods/`.

**Remède.** Lis l'erreur du loader, qui nomme la contrainte non satisfaite. Puis vérifie que
`mods/` contient exactement un `cheesemusic-*.jar` et aucun `-sources.jar`. La première
ligne de log du mod est `[CheeseMusic] Initializing...` ; son absence signifie que le loader
n'a jamais atteint l'entrypoint.

## `[CheeseMusic] CheeseGuard not installed — zone music disabled`

**Cause.** `Class.forName("com.cheeseguard.CheeseGuardMod")` a levé
`ClassNotFoundException`. Le jar de CheeseGuard n'est pas chargé.

**Remède.** Ajoute-le au `mods/` du serveur. CheeseGuard est dans `suggests`, pas dans
`depends`, donc le loader ne t'avertira pas — le mod démarre tranquillement et résout chaque
position en « pas de musique ».

## `[CheeseMusic] CheeseGuard detected but not yet initialized`

(ou sa jumelle, `CheeseGuard RegionManager not yet initialized`)

**Cause.** `CheeseGuardMod.INSTANCE` ou son `RegionManager` était encore nul quand
CheeseMusic a exécuté son gestionnaire `SERVER_STARTED`. Ce n'est pas une course d'ordre de
chargement : CheeseGuard fixe les deux tout en haut de son propre `onInitialize()`, qui se
termine toujours avant le démarrage du serveur. Voir cette ligne signifie que
l'initialisation de CheeseGuard a échoué, et sa propre erreur figure plus haut dans le log.

**Remède.** Corrige ce qui a cassé le démarrage de CheeseGuard, puis redémarre le serveur.
Contrairement au hook ReactiveMusic, `CheeseGuardHook` **ne retente pas** — `initialized`
est mis à `true` avant la tentative, donc une initialisation échouée reste échouée pour
toute la session serveur. Si cela se reproduit à chaque démarrage, le problème est du côté
de CheeseGuard, pas ici.

## `[CheeseMusic] CheeseGuard hook failed: <message>`

**Cause.** CheeseGuard est présent mais n'a plus la forme que le hook attend :
`getRegionManager()`, `getStorage()`, `getRegions()` ou
`getRegionsAt(String,int,int,int)` a été renommée ou resignée.

**Remède.** C'est une casse au niveau du code, pas un problème de configuration. Réaligne
`CheeseGuardHook` sur l'API actuelle de CheeseGuard. Le message d'exception nomme le membre
manquant.

## `GET /api/v1/music/zones` renvoie un tableau vide alors que `/cg list` montre des régions

**Cause.** Soit le hook est indisponible (les trois lignes de log ci-dessus), soit
`getAllZones()` a levé une exception. Ce second cas est journalisé au niveau `debug`, donc
tu ne vois rien avec les réglages de log par défaut.

**Remède.** Passe le logger `cheesemusic` en `DEBUG` et refais la requête. La ligne
`[CheeseMusic] getAllZones failed: ...` te dit alors quel appel par réflexion est cassé. Un
`200` avec `[]` ne distingue jamais à lui seul « aucune région » de « pas de
CheeseGuard » — seul le log du démarrage le fait.

## J'entre dans une région assignée et je n'entends rien du tout

Six causes, dans l'ordre où il vaut la peine de les vérifier.

**1. ReactiveMusic n'est pas installé sur le client.** De loin la plus fréquente. La machine
à états du client attend une confirmation que seul le hook ReactiveMusic peut donner : elle
cale donc en pleine transition et ne joue rien, tout en maintenant la musique vanilla
étouffée. Cherche `[CheeseMusic] ReactiveMusic not installed` dans le log **client**.
*Remède :* installe ReactiveMusic. Trace complète :
[lecture](playback.fr.md#ce-qui-se-passe-sans-reactivemusic).

**2. Le client n'a pas le resource pack.** Teste-le directement :
`/playsound cheesemusic:<piste> music @s`. La commande annonce un succès dans tous les
cas ; un silence, avec `Unable to play unknown soundEvent` dans le log client, signifie
que le pack manque ou n'est pas activé. *Remède :*
[packs de sons](sound-packs.fr.md#livraison-aux-clients).

**3. Le nom de piste de l'assignation n'existe pas.** Compare
`GET /api/v1/music/assignments` et `GET /api/v1/music/tracks`. Rien ne valide les noms à
l'écriture. *Remède :* corrige l'assignation, avec le nom assaini exact.

**4. Le nom a été écrit avec un namespace.** `arkensia:volcano` devient
`cheesemusic:arkensia:volcano`, que le client rejette comme identifiant invalide.
*Remède :* stocke le nom nu.

**5. Le client n'a pas CheeseMusic installé.** Il jette le paquet sans le moindre message.
*Remède :* installe aussi le jar côté client.

**6. `enabled` vaut `false` dans la configuration.** La boucle de tick est entièrement
sautée, donc personne n'est jamais évalué. *Remède :* `PUT /api/v1/music/config` avec
`{"enabled":true}`.

Les causes 1, 2, 3 et 4 te montrent toutes comme écoutant tranquillement dans
`GET /api/v1/music/status`, puisque le serveur a bien envoyé le paquet. La cause 5 donne la
même chose. Seule la cause 6 laisse la liste vide.

## La piste redémarre toutes les cinq secondes

**Cause.** La piste est plus courte que le délai de garde de 100 ticks. À l'expiration du
délai, `SoundManager.isPlaying` est faux parce que le son est déjà terminé, ce que le chemin
de récupération lit comme « le son est mort » et relance.

**Remède.** Utilise des pistes de plus de cinq secondes. Allonge une boucle courte, ou
concatène-la dans un fichier plus long.

## Deux musiques en même temps

**Cause.** `MusicTrackerMixin` ne s'est pas appliqué. Il est déclaré avec `require = 0`,
donc une cible d'injection modifiée dégrade en « ne rien faire » plutôt qu'en plantage.

**Remède.** Cherche dans le log client un avertissement nommant `MusicTrackerMixin`. Si la
version de Minecraft n'est pas la 1.21.1, c'est la raison. Le mixin cible
`MusicTracker.tick`.

Si la seconde source est ReactiveMusic plutôt que vanilla, la cause est différente : le hook
par réflexion a échoué, et le log client dit
`[CheeseMusic] ReactiveMusic hook failed: ...`. ReactiveMusic a changé ses membres `thread`,
`gainPercentage` ou `additionalSilence`.

## ReactiveMusic reste muet après ma sortie d'une région

**Cause.** `ReactiveMusicHook` garde le gain et la valeur de silence sauvegardés dans des
champs statiques, d'un bout à l'autre d'un cycle de fondu. Si `startFadeIn` a levé une
exception en cours de route, `additionalSilence` peut rester à `999999` et le gain à zéro.

**Remède.** Déconnecte-toi et reconnecte-toi. `ZoneMusicPlayer.reset()` s'exécute à la
déconnexion et appelle `forceReset()`, qui restaure les deux champs immédiatement. Un
redémarrage du client marche aussi.

## La musique continue pendant un combat Cobblemon

**Cause.** La détection de combat est désactivée, ou attachée à la mauvaise méthode. Vérifie
la ligne de démarrage du serveur :

```text
[CheeseMusic] Cobblemon detected — battle detection via <nomDeMéthode>()
```

`Cobblemon not installed` signifie que le hook est désactivé, ce qui est correct si tu n'as
pas Cobblemon. `Cobblemon BattleRegistry found but no suitable method` signifie que la
détection est hors service malgré la présence de Cobblemon. Un nom de méthode qui n'a rien à
voir avec les combats signifie que le balayage de repli s'est attaché à la mauvaise.

**Remède.** Les deux premiers n'ont pas besoin de correctif si la musique de combat
t'indiffère. Le troisième demande de resserrer le filtre de noms de
`CobblemonBattleHook` sur le `BattleRegistry` actuel de Cobblemon.

## Une commande est refusée

CheeseMusic n'enregistre **aucune commande**. Il n'y a aucun arbre Brigadier dans les
sources, donc `/cheesemusic` et tout ce qui y ressemble produisent l'erreur vanilla *Unknown
or incomplete command*, quel que soit ton niveau de permission. Les commandes de région
(`/cg …`) appartiennent à CheeseGuard et exigent le niveau de permission 2. Tout ce qui est
propre à CheeseMusic passe par [le dashboard](dashboard.fr.md).

## Le dashboard n'a pas de section CheeseMusic

**Cause.** L'une de trois. CheeseDash n'est pas installé, donc l'entrypoint `cheesedash`
n'est jamais invoqué. Ou le jar de CheeseMusic est absent côté serveur. Ou le démarrage de
CheeseMusic a levé une exception avant la construction de la configuration, auquel cas le
module se charge mais chaque requête répond `503 {"error":"CheeseMusic not ready"}`.

**Remède.** Cherche `[CheeseMusic] Server started — N assignments loaded` dans le log du
serveur. Présente, le mod est en bonne santé et le problème vient de la découverte par
CheeseDash ; absente, le démarrage a échoué et la trace juste au-dessus est la réponse.

## Je veux qu'une route music exige `SUPER_ADMIN` et elle ne demande jamais qu'`OPERATOR`

**Cause.** Le répartiteur de modules de CheeseDash monte la barre à `OPERATOR` pour toute
requête non-`GET` et s'arrête là — `requireWrite` est le seul contrôle qu'il connaisse. Une
route de module ne peut donc jamais être plus restrictive qu'`OPERATOR` par simple
héritage, et rien dans `MusicApi` n'appelle `requireAdmin`.

**Remède.** Ajoute `if (!WebDashboard.requireAdmin(exchange)) return;` en première
instruction du handler à restreindre. Attention à la forme `if (!…) return;` :
`requireAdmin` envoie son propre `403` et renvoie un booléen, donc un appel nu enverrait le
`403` puis laisserait le handler continuer. Les versions de ce mod antérieures au
29 octobre 2026 contenaient exactement cette erreur sur les six routes d'écriture — un vrai
défaut, mais pas exploitable, puisque le répartiteur du cœur avait déjà écarté toute
session inférieure à `OPERATOR`.

## Mon édition manuelle de `config/cheesemusic.json` a disparu

**Cause.** Le fichier est lu une fois, à `SERVER_STARTED`, et la copie en mémoire fait
autorité à partir de là. Toute écriture du dashboard réécrit tout le fichier depuis la
mémoire, en jetant ton édition.

**Remède.** Édite serveur éteint, ou fais le changement par l'API. Il n'y a aucune commande
de rechargement.

## Le serveur a levé une `ArithmeticException` juste après le démarrage

**Cause.** `checkIntervalTicks` vaut `0` dans le fichier de configuration.
`ZoneMusicTracker.tick` calcule `tickCounter % checkIntervalTicks`. La borne 1–100 n'existe
que dans le setter utilisé par le dashboard ; `load()` affecte le champ directement.

**Remède.** Mets-la à `10` à la main, ou à n'importe quelle valeur de 1 à 100.

## Mes noms de fichiers `.ogg` ont changé tout seuls

**Cause.** Comportement attendu. `SoundScanner.scan()` renomme tout fichier dont le nom de
base n'est pas un chemin d'identifiant Minecraft valide, et cela à chaque scan. Chaque
renommage est consigné :

```text
[CheeseMusic] Renamed 'Volcano Theme (Final Mix).ogg' -> 'volcano_theme_final_mix.ogg'
```

**Remède.** Aucun n'est nécessaire, mais garde tes masters hors de `sounds/` si les noms
d'origine comptent. Les accents sont supprimés plutôt que translittérés, donc `forêt.ogg`
devient `fort.ogg` — renomme ce genre de fichier toi-même d'abord. Les règles :
[packs de sons](sound-packs.fr.md#lassainissement-des-noms-exactement).

## Des régions superposées jouent la mauvaise musique

**Cause.** `resolveZoneMusic` renvoie la première région ayant une assignation, dans
l'ordre que renvoie le `getRegionsAt` de CheeseGuard, c'est-à-dire par `priority`, la plus
haute d'abord. Deux régions de même priorité sortent dans l'ordre de stockage, et une
assignation `tag:` sur une région plus prioritaire bat une assignation directe sur une
région moins prioritaire.

**Remède.** Donne à la région dont la musique doit gagner une priorité CheeseGuard plus
haute que celle de la région qui l'entoure. Ou assigne seulement la région extérieure, ou
fais de l'intérieure une région séparée qui ne se superpose pas. Ce trou est consigné dans
[architecture](architecture.fr.md#faiblesses-connues).

---

## Signaler un bug

Ouvre un ticket sur
[GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues) avec :

1. **Quelle moitié.** La sortie de `GET /api/v1/music/status` pendant que le problème se
   produit. Cette seule ligne décide si quelqu'un a besoin de regarder le code client.
2. **Le log du serveur**, `logs/latest.log`, filtré sur le mod :
   ```bash
   grep CheeseMusic logs/latest.log
   ```
   Inclus chaque ligne `[CheeseMusic]` depuis le démarrage, pas seulement l'erreur. Les
   lignes de détection de CheeseGuard et de Cobblemon sont en général la réponse.
3. **Le log du client**, `.minecraft/logs/latest.log`, filtré de la même façon. La ligne de
   détection de ReactiveMusic est la plus importante.
4. **L'assignation**, depuis `GET /api/v1/music/assignments`, et la liste de pistes depuis
   `GET /api/v1/music/tracks`. La plupart des rapports « pas de musique » sont un
   désaccord entre ces deux-là.
5. **Les versions** : Minecraft, Fabric Loader, et le nom exact du jar
   (`cheesemusic-1.0.0+1.21.1.jar`). Pour tout ce qui touche aux fondus ou à
   l'étouffement, la version de ReactiveMusic aussi.

Si le log ne contient absolument rien de `[CheeseMusic]`, dis-le explicitement. C'est en
soi le constat le plus utile, et il désigne le loader plutôt que le mod.

---

← [Architecture](architecture.fr.md) · [CheeseMusic](../README.fr.md) →
