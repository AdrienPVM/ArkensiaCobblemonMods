[← CobbleCap](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](client.md)

# Le côté client

Le `fabric.mod.json` de CobbleCap déclare `"environment": "*"`, donc le même jar se charge
des deux côtés. La moitié client fait trois choses : elle dessine le badge de limite, elle
masque les Pokémon de pâturage sur demande, et elle fait en sorte que les statues de joueur
ressemblent à des statues plutôt qu'à des joueurs au repos.

Le mod est **jouable sans elle**. Un client vanilla sur un serveur CobbleCap n'a pas de
badge, pas de `/pasturehide`, et voit les statues respirer et se balancer, mais chaque règle
de limite est imposée côté serveur et rien ne se désynchronise.

## Le badge du HUD

`CapHudRenderer` s'enregistre sur `HudRenderCallback` et dessine une texture de 24×20,
`cobblecap:textures/gui/level_cap.png`, avec le numéro de limite centré dessus en blanc avec
une ombre.

| Réglage | Source |
|---|---|
| visibilité | `hudVisible` sur `PlayerCapData`, posé par `/cobblecap hud on|off` |
| coin | `hudPosition` : `0` bas-droite, `1` bas-gauche, `2` haut-droite, `3` haut-gauche |
| marge | 6 pixels, codée en dur |

Il ne dessine rien du tout quand le joueur est nul, quand F1 a masqué le HUD
(`client.options.hudHidden`), avant l'arrivée de toute donnée, quand le système de limite est
désactivé côté serveur, ou quand le joueur l'a coupé.

### Ce que transporte le paquet

`CapSyncPayload` est le seul paquet S2C utilisé par le HUD, sous l'identifiant
`cobblecap:cap_sync` :

| Champ | Type |
|---|---|
| `capLevel` | int |
| `nextChampion` | chaîne, écrite comme `""` pour null et relue comme null |
| `hudVisible` | booléen |
| `hudPosition` | int |
| `systemEnabled` | booléen |

`CobbleCapMod.syncToClient` l'envoie à la connexion, à chaque changement de limite, à une
bascule du HUD, sur `/cobblecap reload`, et sur `enable`/`disable`. Il retourne tôt quand le
joueur n'a pas de `networkHandler` et quand il n'a aucune entrée stockée : un joueur dont les
données n'ont pas pu être chargées n'a donc discrètement aucun HUD plutôt qu'un HUD faux.

⚠️ **`nextChampion` est envoyé mais jamais dessiné.** Le renderer le garde dans un champ
statique et ne l'utilise pas. Les fichiers de langue portent `cobblecap.hud.next` →
`Suivant : %s`, et le badge n'affiche que le nombre. Soit la ligne du prochain champion a été
abandonnée, soit elle n'a jamais été écrite ; la donnée est là pour qui voudra l'ajouter.

## `/pasturehide`

Une bascule purement client qui arrête le rendu des Pokémon Cobblemon attachés à un bloc de
pâturage. Les entités existent toujours et continuent de tourner ; elles ne sont simplement
pas dessinées, pour ce joueur-là.

La commande et ses sorties sont dans [commandes](commands.fr.md#pasturehide). La préférence
vit dans `config/cobblecap_client.properties` :

```properties
#CobbleCap client preferences
pasture_pokemon_hidden=true
```

Chargée par `PastureHideClient.load()` à l'initialisation du client et réécrite à chaque
bascule. C'est un simple fichier `java.util.Properties` avec une seule clé, distinct des
configs JSON côté serveur parce qu'il appartient au joueur, pas au monde.

### Pourquoi le serveur doit aider

`Pokemon.tetheringId` n'est **jamais** synchronisé par DataTracker. Le client n'a aucun moyen
de savoir qu'un Pokémon est attaché à un pâturage, donc aucune quantité de réflexion côté
client ne peut répondre à la question. La première implémentation a tenté exactement cela et
ne pouvait pas fonctionner.

`PastureTracker`, côté serveur, comble le trou :

1. Toutes les **40 ticks** (2 secondes), il parcourt toutes les entités de tous les mondes
   dont le nom de classe se termine par `.PokemonEntity` et demande, par réflexion, si
   `getTetheringId()` ou `getTethering()` est non nul — sur l'entité, puis sur son objet
   `Pokemon`.
2. Il compare cet ensemble au précédent et ne diffuse que les changements.
3. À la connexion, il envoie l'ensemble complet.

`PastureSyncPayload` a donc trois modes — `SNAPSHOT`, `ADD`, `REMOVE` — et le client
maintient un ensemble adossé à une `ConcurrentHashMap`. `PastureHideClient.isPastured` est
alors un test à deux étapes bon marché : le nom de classe de l'entité se termine par
`.PokemonEntity`, et son UUID est dans cet ensemble.

Les deltas sont envoyés un paquet par UUID et par joueur, ce qui est gaspilleur en rafale
mais sans conséquence au rythme auquel les pâturages changent réellement.

### L'annulation du rendu

`EntityRenderDispatcherMixin` s'injecte en tête de
`EntityRenderDispatcher.render(Entity, DDD, F, F, MatrixStack, VertexConsumerProvider, I)`
et annule quand la bascule est active et que l'entité est attachée. Il retourne
immédiatement quand la bascule est coupée, donc le coût est une lecture de booléen par
entité et par image.

Il tient aussi une télémétrie et journalise une fois par seconde tant que la bascule est
active :

```text
[CobbleCap-Client] /pasturehide last 1s: checked 42 Pokémon entities, hid 7
```

Cette ligne est le diagnostic. Le mixin ne l'affiche que s'il a vérifié au moins un Pokémon
dans la dernière seconde, donc tu ne verras jamais `checked 0` : **aucune ligne du tout**
alors que la bascule est active et que des Pokémon sont en vue signifie que le mixin ne voit
aucune entité Pokémon. `checked 42, hid 0` signifie que le mixin fonctionne et que le client n'a aucune
donnée de pâturage — le serveur ne l'envoie pas, ce qui veut dire d'ordinaire que le serveur
n'a pas CobbleCap, ou que `PastureTracker` n'a pas encore fait son premier parcours. Noter
que le log est en niveau `info` et se déclenche une fois par seconde aussi longtemps que la
bascule est active et qu'un Pokémon est rendu, ce qui remplira un log de client sur une longue session.

`PastureHideClient` contient aussi une méthode `debugLog` et un champ `lastDebugLog`
inutilisés — du code mort issu de l'ancienne approche par réflexion.

## Le rendu des statues

Quatre mixins client existent pour une seule raison : faire en sorte que les faux joueurs
créés par [le sous-système de statues](statues.fr.md) aient l'air sculptés plutôt que
vivants.

Tous s'appuient sur `SyntheticUuidRegistry`, un ensemble d'UUID appartenant à des doublures.
Le registre vit dans le source set **commun** pour qu'un mixin client puisse le lire sans
singleton client séparé. Le serveur le remplit quand il crée ou retire une doublure ; le
client le reflète depuis `StatueStyleSyncPayload`, au motif que tout UUID mentionné dans ce
payload est par définition une doublure. Le mode `REMOVE` est le désenregistrement
autoritaire — un `SNAPSHOT` ne vide volontairement **pas** le registre, puisqu'une doublure
dont le style n'a jamais changé reste synthétique.

| Mixin | Cible | Pourquoi |
|---|---|---|
| `BipedAnimateModelMixin` | `BipedEntityModel.animateModel(LivingEntity,FFF)` | annule la passe de pré-pose — inclinaison, angles de nage et de rampement, accroupissement de monture. Sans lui, une statue près d'un joueur qui nage reprend cette pose |
| `BipedEntityModelMixin` | `BipedEntityModel.setAngles(LivingEntity,FFFFF)` | rappelle `setAngles` avec tous les arguments de mouvement à zéro, puis annule l'original — pas de respiration au repos, pas de balancement des membres |
| `PlayerEntityModelMixin` | `PlayerEntityModel.setAngles(LivingEntity,FFFFF)` | la surcharge joueur ajoute son propre balancement de manches et un suivi des oreilles, appliqués avant l'appel à `super`. Un mixin sur le parent seul arriverait trop tard |
| `PlayerEntityRendererMixin` | `PlayerEntityRenderer.getTexture(Entity)` | renvoie `cobblecap:textures/statue/<style>.png` au lieu de la skin du joueur, pour tout style autre que `natural` |

`BipedEntityModelMixin` et `PlayerEntityModelMixin` entreraient en récursion mutuelle —
`PlayerEntityModel extends BipedEntityModel`, donc l'appel réentrant de l'un atterrit dans
l'injection de l'autre. `StatueFreezeGuard`, un `ThreadLocal<Boolean>` partagé, brise le
cycle : tous deux vérifient d'abord `active()` et le posent autour de leur propre
réinvocation.

### Les textures stylisées

`StatueStyleClient` associe un UUID de doublure à un `StatueStyle`, alimenté par
`StatueStyleSyncPayload` en modes `SNAPSHOT`, `UPSERT` et `REMOVE`. `natural` est stocké
comme *absent* — `put(uuid, NATURAL)` retire l'entrée — de sorte qu'une statue `natural`
retombe sur la résolution de skin vanilla et affiche la vraie skin du champion.

Tous les autres styles se résolvent vers un PNG précalculé livré dans le jar :

```text
assets/cobblecap/textures/statue/{stone,gold,emerald,diamond,amethyst,copper,
                                  netherite,obsidian,crystal,glass,frost,soul}.png
```

Douze fichiers, un par style non `natural`. C'est ce qui donne à une statue dorée un corps
doré plutôt qu'un contour doré ; la couleur de lueur par équipe de scoreboard et l'aura de
particules sont côté serveur et visibles d'un client vanilla, la texture du corps non.

⚠️ Un client vanilla voit donc une statue stylisée comme la skin ordinaire du champion avec
une lueur colorée. C'est une différence purement cosmétique, mais cela signifie que les
captures d'écran ne correspondront pas entre joueurs avec et sans le mod.

## Ordre d'initialisation du client

`CobbleCapClient.onInitializeClient` :

1. attacher le récepteur `CapSyncPayload` — les trois codecs sont enregistrés par
   l'initialiseur **commun**, qui tourne des deux côtés, donc les réenregistrer ici lèverait
   une erreur *already registered*
2. attacher le récepteur `PastureSyncPayload`
3. attacher le récepteur `StatueStyleSyncPayload`
4. `CapHudRenderer.register()`
5. `PastureHideClient.load()`
6. `PastureHideCommand.register()`

```text
[CobbleCap] Client initializing...
[CobbleCap] Client initialized — HUD registered, /pasturehide enabled (hidden=false)
```

Le corps de chaque récepteur est enveloppé dans `context.client().execute(...)`, donc le
traitement des paquets quitte le thread netty pour le thread client avant de toucher à quoi
que ce soit.

---

← [Statues et dioramas](statues.fr.md) · [Configuration](configuration.fr.md) →
