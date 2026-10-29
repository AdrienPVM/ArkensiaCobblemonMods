[← CheeseExtraCommands](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](architecture.md)

# Architecture

Sept fichiers source, 840 lignes, deux fonctionnalités sans rapport qui partagent un jar.
Les commandes de vérification et le HUD du tour de l'île n'ont aucun code en commun au-delà
du nom de logger, et l'une pourrait être supprimée sans toucher à l'autre.

---

## Arborescence des packages

```text
com.cheeseextra
├── CheeseExtraMod.java            entrypoint main : enregistre tout, possède le logger
├── CheeseCheckCommand.java        tout l'arbre Brigadier /cheesecheck et ses quatre handlers
├── CobblemonReflect.java          pont de réflexion vers Cobblemon : équipe, EVs, IVs, espèce, Ball
├── arkensia
│   └── IslandTourTracker.java     lit les scoreboards du tour chaque seconde, pousse des deltas
├── network
│   └── IslandTourPayload.java     record S2C : show / met / total / remaining
└── client
    ├── CheeseExtraClient.java     entrypoint client : récepteur de payload et accroche HUD
    └── IslandTourHud.java         dessine le panneau en bas à droite
```

`com.cheeseextra.arkensia` contient le code propre à l'île, ce qui rend le couplage
visible depuis le nom du package : tout ce qui s'y trouve n'est pas réutilisable ailleurs.
Le décompte de lignes par fichier :

| Fichier | Lignes |
|---|---|
| `CheeseCheckCommand.java` | 263 |
| `CobblemonReflect.java` | 243 |
| `IslandTourTracker.java` | 143 |
| `IslandTourHud.java` | 92 |
| `IslandTourPayload.java` | 49 |
| `CheeseExtraMod.java` | 33 |
| `CheeseExtraClient.java` | 17 |

`resources/` ne contient que `fabric.mod.json`. Aucune configuration de mixin, aucun
fichier de langue, aucun datapack, aucune texture.

## Qui possède quoi

**`CheeseExtraMod`** est un simple `ModInitializer` sans état au-delà d'un `LOGGER`
public statique, nommé `cheeseextra` plutôt que d'après une classe. Il enregistre cinq
choses :

| Enregistrement | Pourquoi |
|---|---|
| `CommandRegistrationCallback` → `CheeseCheckCommand.register` | l'arbre de commandes, construit quand le serveur construit son dispatcher |
| `IslandTourPayload.register()` | le codec S2C, qui doit être enregistré pendant l'init du mod des deux côtés |
| `ServerTickEvents.END_SERVER_TICK` → `IslandTourTracker::onServerTick` | l'évaluation du HUD, une fois par seconde |
| `ServerPlayConnectionEvents.JOIN` | force un renvoi de l'état du HUD à un joueur qui se reconnecte |
| `ServerPlayConnectionEvents.DISCONNECT` | jette le snapshot en cache de ce joueur |

Pas de singleton `INSTANCE`, pas d'objet manager, aucune configuration à charger. Toutes
les autres classes sont statiques.

**`CheeseCheckCommand`** est entièrement statique : l'arbre Brigadier dans `register`,
quatre handlers, un helper `setScore`. L'arbre est bâti en imbriquant des appels
`.then(...)` et en accrochant des `.executes(...)` à plusieurs profondeurs : c'est ainsi
que fonctionnent les arguments optionnels de queue. `/cheesecheck ev` a trois nœuds
`executes` (sur `value`, sur `statCount`, sur `teamCount`), chacun passant des valeurs
littérales différentes à la même méthode `checkEv`. C'est pour cela que les défauts valent
`1` et `1` sans être déclarés comme constantes nulle part : ce sont des arguments au point
d'appel.

**`CobblemonReflect`** est le seul fichier réellement intéressant. Voir
[la couche de réflexion](#la-couche-de-réflexion).

**`IslandTourTracker`** possède le seul état mutable du mod : une
`ConcurrentHashMap<UUID, Snapshot>` de ce qui a été envoyé en dernier à chaque joueur,
plus un `int tickCounter` statique. `Snapshot` est un record : un snapshot mémorisé ne
peut donc pas dériver.

**`IslandTourPayload`** est un record qui implémente `CustomPayload`, avec un
`PacketCodec.of(write, read)` écrit à la main plutôt qu'un codec composé. L'écrire à la
main est ce qui permet à la longueur de liste d'être un VarInt suivi de chaînes
plafonnées.

**`IslandTourHud`** garde quatre champs statiques `volatile` et dessine à partir d'eux.
`volatile` parce que le thread réseau confie le payload à l'exécuteur client pendant que
le thread de rendu lit les champs ; voir [threading](#threading).

## La couche de réflexion

`CobblemonReflect` atteint Cobblemon sans lien à la compilation ni au chargement.
Cobblemon est `compileOnly` dans `build.gradle` et se trouve dans `suggests`, pas dans
`depends`, du `fabric.mod.json`. `isAvailable()` est un unique `Class.forName` sur
`com.cobblemon.mod.common.Cobblemon`, et chaque commande l'appelle avant toute autre
chose.

La discipline importante ici : **rien n'est cherché par nom de méthode là où un nom
pourrait être remappé ou renommé.** Le code scanne plutôt une méthode par sa *forme*.

```java
for (Method m : cobblemonClass.getMethods()) {
    if (m.getParameterCount() == 0
            && m.getReturnType().getSimpleName().equals("PokemonStoreManager")) {
        storage = m.invoke(instance);
        break;
    }
}
```

Ce motif se répète quatre fois, chaque fois avec un discriminant différent :

| Cible | Discriminant |
|---|---|
| `PokemonStoreManager` | méthode sans argument dont le type de retour s'appelle `PokemonStoreManager`, sur `Cobblemon.INSTANCE` |
| le store d'équipe | méthode à un argument dont le nom du type de retour *contient* `Party` et dont le paramètre accepte un `ServerPlayerEntity`, avec repli sur une surcharge à `UUID` |
| le store `EVs` / `IVs` | méthode sans argument dont le type de retour s'appelle exactement `EVs` ou `IVs`, sur l'objet Pokémon |
| `getOrDefault(Stat)` | méthode à un argument renvoyant un `int` et dont le paramètre implémente `com.cobblemon.mod.common.api.pokemon.stats.Stat` |

Seuls les noms de classes sont écrits en dur : `Cobblemon`, `Stats`, `Stat`, et le champ
`INSTANCE` que Kotlin génère pour un `object`. Ce sont la surface d'API propre à Cobblemon
et les éléments les moins susceptibles de bouger.

Là où un nom *est* employé, il l'est comme un candidat parmi plusieurs. `invokeGetter`
prend une liste variadique et essaie chaque nom contre `getMethod` puis
`getDeclaredMethod` : `getSpeciesId` essaie `getShowdownId`, `showdownId`, `getName`,
`name` dans cet ordre ; `getCaughtBall` essaie `getCaughtBall` puis `getPokeball`, puis
sur la Ball elle-même `getName`, `name`, `toString`.

Deux replis méritent d'être connus, parce qu'ils changent le sens d'un résultat et pas
seulement le chemin pour l'obtenir :

- **Le store d'équipe.** Si l'équipe ne peut pas être obtenue comme `Iterable`, le code
  essaie `toGappyList`, `getAll`, `getPokemon` tour à tour et itère celui qui renvoie un
  `Iterable`. Une liste « à trous » peut contenir des `null` pour les emplacements vides ;
  les `null` sont filtrés à l'insertion.
- **Le store de stats.** Si `getOrDefault(Stat)` est introuvable, le code itère le store
  comme un `Iterable<Map.Entry<Stat, Integer>>` et place chaque valeur à l'**ordinal de
  l'énumération** de la stat. Cela suppose silencieusement que l'ordre de déclaration de
  `Stats` est HP, ATTACK, DEFENCE, SPECIAL_ATTACK, SPECIAL_DEFENCE, SPEED. Si Cobblemon
  réordonne un jour cette énumération, le chemin de repli lit les mauvaises stats et rien
  ne t'avertit.

La liste de candidats de `getCaughtBall` qui finit par `toString` est plus tranchante
qu'il n'y paraît : `getMethod("toString")` réussit toujours sur n'importe quel objet, donc
si `getName` et `name` sont tous deux absents la méthode renvoie le `toString` de la
Ball, en minuscules, quel qu'il soit. Pour compter des types de Balls *distincts*, cela
fonctionne encore, la valeur n'ayant besoin que d'être stable par type de Ball. Cela ne
fonctionnerait pas si la chaîne était un jour comparée à un identifiant connu.

Chaque échec de réflexion est capturé. Les commandes dégradent vers « aucune
correspondance » plutôt que de lever une exception, ce qui signifie qu'une mise à jour de
Cobblemon cassant le pont ressemble exactement à un joueur dont l'équipe ne qualifie pas.
C'est le principal danger de maintenance de cette conception, et les lignes de log en
niveau INFO sont le seul moyen de distinguer les deux cas.

## État et persistance

Le mod n'écrit **aucun fichier**. Toute son empreinte persistante est le scoreboard
vanilla : les objectifs écrits par `setScore`, que Minecraft enregistre dans
`world/data/scoreboard.dat` selon son propre calendrier. Pas de hook de sauvegarde, pas
de vidage, pas de handler d'arrêt.

Le seul état en mémoire est `IslandTourTracker.lastSent`, un cache qui peut être jeté à
tout moment sans autre conséquence qu'un paquet redondant.

## Threading

| Code | Thread | Remarques |
|---|---|---|
| handlers de `CheeseCheckCommand` | thread serveur | Brigadier dispatche sur le thread qui a lu la commande, soit le thread serveur pour un joueur, un bloc de commande ou une fonction |
| `CobblemonReflect` | thread serveur | appelé uniquement depuis les handlers de commande |
| `setScore` | thread serveur | mute directement le scoreboard, ce qui est sûr à cet endroit |
| `IslandTourTracker.onServerTick` | thread serveur | `END_SERVER_TICK` |
| `onPlayerJoin` / `onPlayerDisconnect` | thread serveur | les événements de connexion de Fabric |
| `ServerPlayNetworking.send` | thread serveur | confie le paquet au pipeline netty |
| décodage du payload | thread réseau client | le récepteur Fabric |
| `IslandTourHud.update` | thread principal client | le récepteur l'enveloppe dans `context.client().execute(...)` |
| `IslandTourHud.render` | thread de rendu client | `HudRenderCallback` |

Il n'y a **aucun thread HTTP**, puisqu'il n'y a pas de module de dashboard. Rien dans ce
mod n'a besoin d'`executeOnServerThread`.

Deux passages de thread existent, et tous deux sont explicites. Côté client, le récepteur
de payload ne touche pas lui-même aux champs du HUD : il poste sur l'exécuteur client.
Les champs du HUD sont `volatile` par-dessus, ce qui relève de la double sécurité :
`update` et `render` tournent tous deux sur des threads client dont rien ne garantit
qu'ils soient les mêmes dans toutes les versions de Minecraft.

`lastSent` est une `ConcurrentHashMap` bien que tous les accès se fassent sur le thread
serveur. C'est inoffensif, et c'est une assurance peu coûteuse si un appelant futur
arrive d'ailleurs.

## Mixins

**Aucun.** `fabric.mod.json` déclare `"mixins": []` et il n'existe aucun fichier de
configuration de mixin dans `resources/`. Le mod obtient tout par des événements publics
de l'API Fabric, ce qui explique qu'il survive à un correctif de Minecraft sans
recompilation.

## Points d'extension

Ce mod ne publie ni interface, ni registre, ni événement. Un contributeur l'étend en le
modifiant. Les trois coutures naturelles :

**Ajouter une sous-commande.** Ajoute une branche
`.then(CommandManager.literal("..."))` dans `CheeseCheckCommand.register` et un handler
statique à côté des quatre existants. Renvoie `1` en cas de réussite et `0` en cas
d'échec, et n'appelle `setScore` que sur le chemin de réussite : tout le contrat
d'intégration avec les quêtes tient dans ces deux règles. Suis la convention d'ordre des
arguments existante (`player` d'abord, les critères ensuite, `scoreboard` et `value` en
dernier) plutôt que celle d'`iv`, qui place son compte avant son seuil et se lit mal face
à `ev`.

**Lire quelque chose de nouveau sur un Pokémon.** Ajoute une méthode statique à
`CobblemonReflect`. Préfère scanner `getMethods()` par type de retour ou type de paramètre
plutôt que nommer une méthode. Quand un nom est inévitable, passe plusieurs candidats à
`invokeGetter` et assure-toi que la méthode renvoie une valeur neutre au lieu de lever une
exception.

**Réorienter le HUD.** Traité dans [le traqueur du tour de
l'île](island-tour.fr.md#le-réorienter-vers-un-autre-serveur).

## Faiblesses connues

Écrites noir sur blanc parce qu'elles sont toutes réelles et toutes faciles à rencontrer.

**Une vérification ratée et un pont cassé sont indiscernables.** Les deux renvoient `0`,
les deux envoient un message rouge au joueur. Si une mise à jour de Cobblemon change le
nom de `PokemonStoreManager`, toutes les quêtes du serveur deviennent silencieusement
infaisables et le seul symptôme est
`Could not get PokemonStoreManager from Cobblemon.INSTANCE` enterré dans le log en niveau
WARN. Une sous-commande de diagnostic qui annoncerait l'état du pont réglerait cela, et
elle n'existe pas.

**Le logging est beaucoup trop bavard.** `getPartyPokemon` écrit deux lignes INFO par
appel et `getStatStore` une par Pokémon. Un seul `/cheesecheck iv` sur une équipe pleine
écrit huit lignes INFO, et `setScore` en ajoute une neuvième. Sur un serveur où vingt
dialogues de NPC lancent des vérifications, le log devient illisible. Ces lignes
devraient être en DEBUG ; elles sont en INFO parce qu'elles ont été ajoutées pendant la
mise au point de la réflexion et n'ont jamais été baissées.

**Les scores sont indexés par pseudo.** `ScoreHolder.fromName(player.getName().getString())`
fait perdre sa progression à un joueur qui change de pseudo. Passer à l'UUID n'aiderait
pas davantage, puisque les dialogues de NPC lisent les mêmes objectifs par nom ; corriger
cela proprement implique de changer les deux côtés en même temps.

**Un nom d'objectif mal orthographié est accepté.** `setScore` crée l'objectif au lieu de
refuser. L'avertissement qu'il écrit est le seul signal, et le joueur voit tout de même un
message de réussite. Valider contre les objectifs existants, ou au moins rendre la
création automatique explicite, transformerait un échec silencieux en échec bruyant.

**La sous-commande `ev` ne peut pas cibler une stat.** « EVs de Vitesse au maximum » est
inexprimable. Ajouter un argument de stat demanderait une cinquième profondeur
d'`executes` ou une sous-commande séparée ; la forme actuelle ne s'étend pas proprement,
car `statCount` et un hypothétique `stat` occuperaient la même position.

**`ballvariety` accepte un compte jusqu'à 20** pour un conteneur qui en tient six. Toute
valeur au-dessus de `6` est vouée à l'échec. La plage devrait être `1`–`6`.

**Le message de réussite d'`ev` affiche `1 stats`** dans le cas singulier. C'est
cosmétique, et visible par chaque joueur dès la première étape de quête qu'il valide.

**Le repli du store de stats fait confiance aux ordinaux d'énumération.** Décrit sous [la
couche de réflexion](#la-couche-de-réflexion). C'est le seul chemin qui peut renvoyer de
mauvais nombres plutôt qu'aucun nombre.

**La moitié « tour de l'île » est du poids mort hors d'Arkensia.** Elle représente 284
lignes réparties sur trois fichiers, plus un entrypoint client, et sur n'importe quel
autre serveur elle ne fait rien. La séparer dans son propre mod rendrait les deux moitiés
honnêtes ; cela n'en valait pas la peine tant qu'un unique serveur faisait tourner les
deux.

**Rien n'est testé.** Il n'y a pas de source set de tests, pas de fixture, et aucun moyen
d'exercer la couche de réflexion sans un serveur lancé et Cobblemon installé. Chaque
modification de `CobblemonReflect` doit être vérifiée en jeu.

---

← [Traqueur du tour de l'île](island-tour.fr.md) · [Dépannage](troubleshooting.fr.md) →
