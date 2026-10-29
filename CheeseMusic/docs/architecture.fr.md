[← CheeseMusic](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](architecture.md)

# Architecture

Pour qui reprendra ce mod. Seize fichiers source, environ 1 710 lignes, aucune couche de
persistance au-delà d'un fichier JSON, et trois hooks par réflexion vers des mods qui ne
sont pas des dépendances de compilation.

---

## Disposition des packages

```text
com.cheesemusic
├── CheeseMusicMod.java         ModInitializer. Câblage du cycle de vie, détient les singletons
├── CheeseMusicConfig.java      le modèle de configuration, son chargement et sa sauvegarde
├── MusicAssignment.java        la playlist d'une zone, avec son JSON en entrée et en sortie
├── PlayMode.java               RANDOM | SEQUENTIAL | SHUFFLE
├── network
│   └── MusicPayload.java       l'unique paquet S2C : action + JSON + volume + ticks
├── logic                       côté serveur
│   ├── ZoneMusicTracker.java   état par joueur, boucle de tick, envoi des paquets
│   ├── SoundScanner.java       le resource pack du serveur : scan, assainissement, sounds.json
│   ├── CheeseGuardHook.java    réflexion vers le RegionManager de CheeseGuard
│   └── CobblemonBattleHook.java réflexion vers le BattleRegistry de Cobblemon
├── client                      côté client
│   ├── CheeseMusicClient.java  ClientModInitializer, récepteur de paquets, hook de tick
│   ├── ZoneMusicPlayer.java    la machine de lecture à quatre états (la plus grosse classe)
│   ├── ZoneMusicSoundInstance.java  un AbstractSoundInstance sur le canal MUSIC
│   └── ReactiveMusicHook.java  réflexion vers ReactiveMusic, rampe de gain
├── mixin
│   └── MusicTrackerMixin.java  client seulement, étouffe la musique vanilla
└── dashboard
    ├── CheeseMusicDashboardModule.java  l'entrypoint CheeseDash, préfixe « music »
    └── MusicApi.java           le dispatch REST
```

La séparation est strictement par côté. Rien sous `logic` n'est touché par le client, rien
sous `client` par le serveur, et `network` plus les quatre classes de modèle à la racine
sont le seul code partagé. `dashboard` est également serveur seulement, et c'est le seul
package qui importe `com.cheesedash`.

## Ce que possède chaque classe

**`CheeseMusicMod`** détient les trois singletons — configuration, tracker, scanner — plus
la référence au `MinecraftServer`, et est lui-même un singleton statique via `INSTANCE`.
L'enregistrement du type de payload a lieu dans `onInitialize`, parce qu'un payload doit
être enregistré avant qu'aucune connexion n'existe. Tout le reste est différé à
`SERVER_STARTED`, où le répertoire d'exécution est connu.

**`CheeseMusicConfig`** est à la fois le modèle et son propre sérialiseur. `save()`
construit le `JsonObject` à la main au lieu de réfléchir sur les champs, et c'est pourquoi
le `transient configPath` ne fuit jamais dans le fichier. Les setters bornent ; `load()`
non. Cette asymétrie est voulue pour le dashboard et devient un piège pour l'édition
manuelle — voir [configuration](configuration.fr.md#checkintervalticks).

**`MusicAssignment`** est un bean mutable dont les setters n'ont aucun appelant : l'API
remplace des objets entiers au lieu de les muter. Ce serait un meilleur `record`, et son
`pickTrackIndex` est du code mort qui duplique le sélecteur du client.

**`ZoneMusicTracker`** détient `Map<UUID, PlayerMusicState>` et est la seule classe qui
envoie des paquets. Son état n'est volontairement pas persisté.

**`SoundScanner`** détient le répertoire `resourcepacks/CheeseMusic/` et le `sounds.json`
généré. C'est la seule classe qui renomme les fichiers de l'utilisateur.

**`ZoneMusicPlayer`** est un singleton statique côté client, qui détient la machine à
états, la playlist, le `SoundInstance` courant et une demande de lecture en attente. Une
instance par processus de jeu, ce qui est correct — un client est dans un monde à la fois.

**`ReactiveMusicHook`** est entièrement statique et conserve le gain et la valeur de
silence sauvegardés de ReactiveMusic d'un bout à l'autre d'un cycle de fondu. Cet état
statique est la raison pour laquelle un `startFadeIn` échoué peut laisser ReactiveMusic
muet jusqu'à la déconnexion suivante.

---

## L'état et le moment où il touche le disque

| État | Où il vit | Écrit quand |
|---|---|---|
| Configuration globale et toutes les assignations | `config/cheesemusic.json` | à chaque écriture dashboard, et une fois au premier démarrage si le fichier est absent. Jamais à l'extinction |
| Fichiers de pistes | `resourcepacks/CheeseMusic/assets/cheesemusic/sounds/*.ogg` | au téléversement ; renommés à chaque scan |
| `sounds.json` | le même pack | régénéré à chaque scan, donc à chaque démarrage et à chaque téléversement ou suppression |
| Zone courante par joueur | mémoire, `ZoneMusicTracker.states` | jamais |
| État de lecture du client | mémoire, `ZoneMusicPlayer` | jamais |

Les écritures de configuration sont des réécritures complètes via `Files.writeString`, sans
fichier temporaire ni déplacement atomique. Un plantage en pleine écriture tronque la
configuration. Les assignations sont peu coûteuses à reconstruire, donc cela n'a pas valu la
peine d'être corrigé, mais il faut le savoir avant de confier à ce mécanisme quelque chose
qui compte.

Il n'y a aucun chemin de rechargement. `load` est appelée une fois, depuis
`SERVER_STARTED`.

---

## Threading

Quatre contextes, et les passages de l'un à l'autre sont la partie à ne pas rater.

**Thread de tick serveur.** `ZoneMusicTracker.tick`, un tick sur `checkIntervalTicks`. Lit
les positions des joueurs, appelle CheeseGuard et Cobblemon par réflexion, et envoie des
paquets via `ServerPlayNetworking.send`. Tout cela est correct sur ce thread, et seulement
sur celui-là.

**Thread HTTP de CheeseDash.** `MusicApi.handle`, une requête à la fois par connexion, sur
l'exécuteur de `com.sun.net.httpserver`. Il mute `CheeseMusicConfig` et appelle
`SoundScanner`, que le thread de tick lit en parallèle. **Il n'y a aucune synchronisation et
aucun passage vers le thread serveur.** Aucun appel à
`WebDashboard.executeOnServerThread` n'existe dans ce mod.

C'est une vraie course de données. En pratique, elle est bénigne : `assignments` est une
`LinkedHashMap` lue par le thread de tick et écrite par le thread HTTP, donc une écriture
pendant une lecture peut en principe lever une exception ou renvoyer une valeur périmée. Le
`try`/`catch` par joueur de la boucle de tick avale ce qui en sort, et la vérification
suivante, une demi-seconde plus tard, voit une table cohérente. `getAllAssignments()`
renvoie une *vue* non modifiable, pas une copie : une itération sur le thread HTTP pendant
une écriture sur ce même thread est le seul cas structurellement sûr.

Toute mutation d'état Minecraft depuis le thread HTTP serait un vrai bug — et il n'y en a
aucune, parce que l'API ne touche que la configuration et le système de fichiers. Si tu
ajoutes un endpoint qui téléporte un joueur ou joue un son, fais-le passer par
`WebDashboard.executeOnServerThread`.

**Thread réseau du client.** Le récepteur de payload, qui fait immédiatement
`context.client().execute(...)` pour sauter sur le thread de rendu. C'est correct :
`ZoneMusicPlayer` et `SoundManager` n'existent que sur le thread de rendu.

**Thread de rendu du client.** `ZoneMusicPlayer.tick` depuis `END_CLIENT_TICK`, le mixin
depuis `MusicTracker.tick`, et le traitement différé des paquets. Mono-thread, aucun verrou
nécessaire.

Un cinquième contexte, à demi visible : `ReactiveMusicHook` écrit `gainPercentage` sur un
champ appartenant au **thread de lecture de ReactiveMusic**, depuis le thread de rendu, sans
`volatile`. Une écriture peut être vue quelques millisecondes en retard. Pour une rampe de
volume cela n'a aucune importance, et la boucle de tick réaffirme la valeur de toute façon.

---

## Le mixin

Un seul mixin, client seulement, déclaré sous `"client"` dans `cheesemusic.mixins.json`
avec `"defaultRequire": 0`.

| Mixin | Cible | Injection | Raison d'être |
|---|---|---|---|
| `MusicTrackerMixin` | `net.minecraft.client.sound.MusicTracker` | `@Inject` en `HEAD` de `tick`, `cancellable`, `require = 0` | Vanilla démarrerait sinon sa propre musique de fond par-dessus une piste de zone. Le mixin arrête le son suivi et annule le tick tant que `ZoneMusicPlayer.isActive()` |

Il `@Shadow` le champ privé `current` et la méthode publique `stop()`. Les deux sont stables
sur toute la 1.21.x, et `require = 0` fait qu'un renommage dégrade en « le mixin ne fait
rien » plutôt qu'en plantage. Le symptôme visible de cette dégradation est deux musiques en
même temps, et c'est aussi la première chose à vérifier après une montée de version de
Minecraft.

Étouffer vanilla par un mixin tout en étouffant ReactiveMusic par réflexion est
incohérent. La raison est que ReactiveMusic a besoin d'une *rampe*, qu'une annulation ne
sait pas exprimer, là où vanilla n'a besoin que d'un tout-ou-rien.

---

## Points d'extension

**Une nouvelle règle de résolution.** `ZoneMusicTracker.resolveZoneMusic` contient toute la
logique « position vers playlist », en quinze lignes. L'heure du jour, la météo ou l'équipe
d'un joueur se brancheraient là, comme une autre forme de clé à côté de `tag:`.

**Une nouvelle action de paquet.** `MusicPayload.action` est une chaîne libre et le client
la répartit dans un `switch`. `fade_to` existe déjà côté client et n'est jamais envoyé ;
le brancher est une modification de deux lignes dans le tracker.

**Une route dashboard.** Ajoute une branche à `MusicApi.handle`. Suis la forme existante :
garde les écritures avec `if (!WebDashboard.requireWrite(exchange)) return;` en première
instruction, valide avant de muter, et appelle `config.save()` après. Ce garde est de la
défense en profondeur plutôt que la frontière — le répartiteur de modules de CheeseDash
exige déjà `OPERATOR` pour toute requête non-`GET` avant d'atteindre `MusicApi`. Deux
choses que le répartiteur ne fera pas à ta place : il ne regarde que la méthode HTTP, donc
un `GET` qui modifierait quelque chose arriverait sans garde ; et il ne monte jamais la
barre au-dessus d'`OPERATOR`, donc une route qui mérite `SUPER_ADMIN` doit appeler
`WebDashboard.requireAdmin` elle-même, dans la même forme `if (!…) return;`.

**Un canal WebSocket.** Rien n'existe aujourd'hui. `ZoneMusicTracker.tickPlayer` est
l'endroit où une diffusion de changement de zone appartient, puisqu'il est déjà le point de
passage unique de chaque transition.

**Une seconde source musicale.** `ReactiveMusicHook` est le modèle : une classe statique, un
`tryInit` qui attrape `ClassNotFoundException` séparément du reste, un `isAvailable()` qui
retente, et chaque accès à un membre enveloppé. Copie cette forme plutôt que d'en inventer
une autre.

---

## Faiblesses connues

Franchement, et à peu près dans l'ordre de la probabilité que chacune te morde.

**ReactiveMusic est de fait obligatoire.** La machine à états du client attend un drapeau
que seul le hook ReactiveMusic peut poser. Sans ce mod, une région assignée ne joue rien et
la musique vanilla reste étouffée jusqu'à ce que le joueur sorte ou se déconnecte. Trace
complète dans [lecture](playback.fr.md#ce-qui-se-passe-sans-reactivemusic). C'est le défaut
le plus sérieux du mod, et le correctif tient à peu près en une ligne.

**Rien ici n'a été éprouvé en jeu**, autant que le dépôt permette d'en juger. Plusieurs
points de cette liste sont du genre qu'une seule soirée d'usage réel ferait remonter.

**Trois hooks par réflexion sur des internes de mods.** Le `thread`, le `gainPercentage`,
l'`additionalSilence` et le `resetPlayer` de ReactiveMusic ; le `BattleRegistry` de
Cobblemon ; le `RegionManager`, le `getStorage` et les accesseurs de `Region` de
CheeseGuard. Chacun cassera quand le mod concerné changera de forme, et chacun échoue en
silence — journalisé en `info`, `warn` ou `debug`, jamais levé. L'échec silencieux est le
bon choix pour une fonctionnalité cosmétique, et cela veut aussi dire qu'une casse peut
passer inaperçue pendant des mois. Les lignes de log du démarrage sont le seul avertissement
précoce ; lis-les après chaque mise à jour de dépendance.

**La méthode de combat de Cobblemon est trouvée à la devinette.** La première passe filtre
sur un nom contenant à la fois `battle` et `player` ; le repli accepte *n'importe quelle*
méthode à un seul `UUID` renvoyant un type référence. Si le nom préféré disparaît, le repli
peut s'attacher à une méthode sans rapport et la détection de combat devient absurde sans la
moindre erreur. Le nom de la méthode choisie est journalisé, et c'est le seul moyen de
vérifier.

**La gestion de la priorité est empruntée à CheeseGuard.** `resolveZoneMusic` prend la
première région assignée dans l'ordre que renvoie `getRegionsAt` et ne consulte jamais la
`priority` qu'il a déjà en main. Cet ordre se trouve être la priorité, la plus haute
d'abord, parce que CheeseGuard le trie ; à priorité égale, c'est l'ordre de stockage. Rien
dans ce mod ne vérifie cette hypothèse.

**Aucune synchronisation entre le thread HTTP et le thread de tick**, comme décrit plus
haut.

**Les écritures de configuration ne sont pas atomiques**, et une édition manuelle est
silencieusement jetée par la prochaine écriture du dashboard.

**`checkIntervalTicks` peut être mis à `0` à la main**, ce qui fait lever une
`ArithmeticException` à `tickCounter % 0` dès le premier tick après le démarrage. La borne
ne vit que dans le setter.

**Une piste de moins de cinq secondes se relance indéfiniment**, parce que le délai de garde
de 100 ticks expire après que le son est déjà terminé, et que le chemin de récupération
interprète cela comme un son mort.

**Aucune validation de l'existence d'un nom de piste** quand une assignation est écrite, et
aucun avertissement quand une piste encore référencée par une assignation est supprimée.

**Code mort.** `MusicAssignment.pickTrackIndex` (c'est le client qui choisit), ses setters,
`ZoneMusicSoundInstance.setMusicVolume` / `isDone` / `markDone`,
`ReactiveMusicHook.isSuppressed`, `CobblemonBattleHook.isAvailable`, l'action `fade_to`, et
`PlayerMusicState.lastPos` / `lastSendTime`. Ce dernier duo ressemble à un anti-rebond de
déplacement abandonné. Rien de tout cela n'est nuisible ; tout cela égare un lecteur, et
c'est pourquoi c'est listé plutôt que laissé à découvrir.

**Deux clés de configuration qui ne font rien.** `defaultFadeInTicks` et `defaultVolume`
sont chargées, sauvegardées et servies, et lues par aucun code de lecture.

**La description de la barre latérale est du français codé en dur.**
`getNavDescription()` renvoie le littéral `"Musique de zones"`, là où le reste du dashboard
passe par des clés i18n.

---

← [API dashboard](dashboard.fr.md) · [Dépannage](troubleshooting.fr.md) →
