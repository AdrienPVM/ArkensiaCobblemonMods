[← CobbleShift](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](npc-transforms.md)

# Transformations de PNJ

Une transformation de PNJ donne à un PNJ EasyNPC l'apparence d'un Pokémon. Le PNJ garde tout
ce qui fait de lui un PNJ : ses dialogues, ses actions, sa position, sa hitbox, son UUID.
Les clients qui ont CobbleShift dessinent un modèle de Pokémon à l'endroit où se tient le
PNJ, et c'est tout.

Transformations de joueurs et de PNJ partagent un nom, un paquet et un moteur de rendu, et
diffèrent sur presque tout le reste. Une transformation de joueur est un état de session,
avec des attributs, des attaques et un cooldown, et elle prend fin au redémarrage. Une
transformation de PNJ est purement visuelle, porte au plus un attribut, et elle est
**sauvegardée sur disque** : le Ronflex qui garde un pont est toujours un Ronflex après le
redémarrage du serveur. Tout le sous-système tient dans `NpcTransformManager` côté serveur
et `NpcTransformModelMixin` côté client.

---

## Le modèle

Une seule map, `Map<UUID, NpcTransformData>`, indexée par l'UUID d'entité du PNJ. Chaque
entrée est un record immuable :

| Champ | Type | Signification |
|---|---|---|
| `species` | chaîne | le nom de l'espèce, sans espace de noms (`cobblemon:snorlax` devient `snorlax`) |
| `shiny` | booléen | utiliser la texture shiny |
| `form` | chaîne ou `null` | la chaîne d'aspects, même grammaire que `/cshift transform` |
| `type1` | chaîne | type principal, lu dans Cobblemon au moment de la transformation |
| `type2` | chaîne ou `null` | type secondaire |
| `scale` | float | multiplicateur de taille visuelle, `1.0` par défaut |
| `speed` | float | multiplicateur de vitesse de déplacement, `1.0` par défaut |

`type1` et `type2` sont enregistrés mais inutilisés pour les PNJ : ils voyagent dans le
paquet de synchronisation parce que ce paquet est partagé avec les transformations de
joueurs, pour lesquelles le client s'en sert dans les animations des types Eau. Changer
`scale` ou `speed` construit un nouveau record avec `withScale()` ou `withSpeed()` et le
remet dans la map.

Comme la clé est l'UUID d'entité, la transformation suit le PNJ partout : téléporte-le,
fais-le changer de dimension, il reste transformé. `findEntity()` cherche dans tous les
mondes chargés. Une copie du PNJ est une nouvelle entité avec un nouvel UUID, et elle n'est
pas transformée.

## Ce qui change sur le PNJ, et ce qui ne change pas

| Aspect | Transformation de PNJ | Transformation de joueur, pour comparer |
|---|---|---|
| modèle sur les clients qui ont le mod | le Pokémon | le Pokémon |
| modèle sur les clients vanilla | **le PNJ, inchangé** | rien, le joueur est invisible |
| effet Invisibilité | aucun | permanent |
| nametag | masqué par `PlayerNametagMixin` | masqué |
| hitbox | celle du PNJ, intacte | mise à l'échelle de l'espèce |
| `scale` | modèle côté client uniquement | modèle et attribut `GENERIC_SCALE` |
| vitesse de déplacement | seulement si tu la règles, voir [vitesse](#vitesse) | tirée de la Vitesse de base |
| vie maximale | intacte | tirée des PV de base |
| cri et particules | jamais | dans un rayon de 32 blocs |
| attaques, capacités passives | aucune | oui |

L'absence d'Invisibilité est voulue, et la raison figure dans les commentaires de
`NpcTransformManager` comme du mixin. Le `WorldRenderer` de Minecraft saute les entités
invisibles avant même d'appeler `EntityRenderDispatcher.render()`. Le mixin des PNJ
s'accroche à cette méthode : un PNJ invisible ne l'atteindrait jamais et aucun Pokémon ne
serait dessiné. Les joueurs passent par un autre mixin, sur `PlayerEntityRenderer`, ce qui
explique qu'eux puissent être invisibles et pas les PNJ.

Un effet de bord bienvenu : un joueur sans CobbleShift voit le PNJ ordinaire au lieu d'un
emplacement vide. Une transformation de PNJ se dégrade mieux qu'une transformation de
joueur.

La hitbox est l'autre face du même choix. `scalenpc 3.0` donne un Ronflex immense dont la
zone cliquable a toujours la taille d'un villageois. Les joueurs visent le PNJ, pas le
modèle, quand ils veulent lui parler.

## Choisir la cible

Les quatre sous-commandes visées (`transformnpc`, `revertnpc`, `scalenpc`, `speednpc`)
n'ont pas d'argument d'entité. C'est `getLookedAtEntity()` qui choisit la cible :

1. Elle trace un segment de 5 blocs depuis les yeux de l'appelant, dans la direction de
   son regard.
2. Elle teste chaque entité présente dans la boîte de l'appelant élargie de 5 blocs,
   contre la boîte de l'entité élargie de 0,3.
3. Elle garde l'impact le **plus proche**.

Trois conséquences découlent de cette implémentation :

- **Les blocs n'arrêtent pas le rayon.** Seules les boîtes d'entités sont testées : tu peux
  viser un PNJ à travers un mur ou une fenêtre.
- **Toute entité placée devant l'emporte.** Un Pokémon, un porte-armure ou un autre joueur
  entre toi et le PNJ devient la cible, et la commande répond
  `Cette entité n'est pas un PNJ EasyNPC`.
- **L'appelant doit être un joueur.** `getPlayerOrThrow()` rejette la console et les blocs
  de commande avec l'erreur vanilla.

`isEasyNpc()` vérifie ensuite l'espace de noms du type d'entité dans le registre, et rien
d'autre : tout ce qui est enregistré sous `easy_npc` passe. EasyNPC n'est pas déclaré dans
`fabric.mod.json` et aucune classe d'EasyNPC n'est référencée. Si EasyNPC change d'espace
de noms, toutes les sous-commandes PNJ se mettront à refuser avec ce même message.

`scalenpc` et `speednpc` sautent la vérification EasyNPC et se contentent de demander si
la cible a une transformation enregistrée. En pratique elles ne peuvent pas atteindre une
entité qui n'est pas un PNJ, puisque `transformnpc` est le seul moyen d'en créer une.

## Cycle de vie

### Transformation

`/cshift transformnpc <pokemon [shiny] [form…]>`, en visant. L'espèce doit se résoudre via
`SpeciesMapper`, ce qui suppose Cobblemon présent.

Si le PNJ est déjà transformé, l'ancienne transformation est **annulée d'abord**, puis un
nouveau record est créé avec `scale` et `speed` revenus à `1.0`. Changer l'espèce d'un PNJ
réinitialise donc sa taille et retire son modificateur de vitesse. Réapplique-les ensuite.

Le gestionnaire diffuse alors la synchronisation à tous les clients connectés et
sauvegarde le fichier. En jeu, l'appelant voit :

```text
✦ PNJ transformé en snorlax
```

et le journal serveur :

```text
[CobbleShift] NPC 1a2b3c4d transformed → snorlax (shiny=false, form=null)
```

L'UUID du journal est tronqué à ses huit premiers caractères.

### Annulation

`/cshift revertnpc`, en visant. Supprime l'entrée, retire le modificateur de vitesse de
l'entité, diffuse une espèce vide pour que les clients abandonnent le modèle, et
sauvegarde.

`/cshift revertallnpc` fait de même pour toutes les entrées, sans viser. Pour un PNJ dont
le chunk n'est pas chargé, `findEntity()` ne renvoie rien : l'entrée quitte la map et la
synchronisation vide est diffusée, mais l'entité elle-même est hors d'atteinte, et **son
modificateur de vitesse reste sur elle**. Ce modificateur est persistant et vit dans les
données sauvegardées de l'entité. Le PNJ garde sa vitesse personnalisée sans plus aucune
transformation pour l'expliquer. Annule les PNJ qui ont une vitesse personnalisée pendant
que leur chunk est chargé, ou remets d'abord leur vitesse à `1.0`.

### Taille

`/cshift scalenpc <value>`, ou `POST /api/v1/shift/scale` avec l'UUID du PNJ. Bornée à
`[0.1, 10.0]`. La valeur est enregistrée, diffusée et sauvegardée. Côté client,
`NpcTransformModelMixin` multiplie le modèle par cette valeur avec `matrices.scale()`. Rien
ne change sur le serveur : ni attribut, ni hitbox.

### Vitesse

`/cshift speednpc <value>`, ou `POST /api/v1/shift/speed`. Bornée à `[0.1, 10.0]`.

C'est le seul effet côté serveur d'une transformation de PNJ. `applySpeedModifier()` pose
un modificateur `GENERIC_MOVEMENT_SPEED` sur l'entité :

| Propriété | Valeur |
|---|---|
| id | `cobbleshift:npc_speed` |
| opération | `ADD_MULTIPLIED_BASE` |
| montant | `speed − 1.0` |
| persistant | oui |

`2.0` double la vitesse de base, `0.5` la divise par deux, et `1.0` retire complètement le
modificateur. Que cela change quelque chose de visible dépend du fait que le PNJ bouge ou
non. Un PNJ EasyNPC statique reste statique ; un PNJ qui suit une patrouille la parcourt
plus vite. La manière dont EasyNPC exploite l'attribut est une observation, pas quelque
chose que ce mod contrôle.

Tous les 100 ticks, `tick()` parcourt les entrées qui ont une vitesse personnalisée et
remet le modificateur sur tout PNJ chargé qui ne l'a plus. Le commentaire du code invoque le
chargement des chunks. Comme le modificateur est persistant, c'est surtout un filet de
sécurité contre autre chose qui le retirerait.

## Persistance

### Quand le fichier est lu et écrit

| Moment | Ce qui se passe |
|---|---|
| `SERVER_STARTED` | `load()` lit le fichier, s'il existe, dans la map |
| premier tick serveur après un chargement non vide | journalise que les transformations sont prêtes, rien de plus |
| chaque transformation, annulation, changement de taille ou de vitesse | `save()` réécrit tout le fichier |
| `revertallnpc` | un `save()` par PNJ annulé dont l'entité est chargée, puis un de plus |
| `SERVER_STOPPING` | `save()`, **sans condition**, même sans rien à sauvegarder |

La dernière ligne signifie qu'un arrêt propre laisse toujours un fichier derrière lui,
contenant `[]` si aucun PNJ n'a jamais été transformé. Les PNJ sont sauvegardés à l'arrêt
et jamais annulés ; c'est l'inverse des transformations de joueurs, que `SERVER_STOPPING`
annule.

Les deux lignes de journal après un redémarrage avec des transformations sauvegardées :

```text
[CobbleShift] Loaded 3 NPC transform(s) from config/cobbleshift_npc_transforms.json
[CobbleShift] Loaded 3 NPC transform(s), will re-apply on first tick
```

suivies, au premier tick, de :

```text
[CobbleShift] 3 NPC transform(s) ready for client sync
```

Malgré la formulation, rien n'est « réappliqué » à ce moment-là. Les clients apprennent
l'existence des transformations de PNJ en se connectant : le gestionnaire `JOIN` appelle
`broadcastAllNpcTransforms()`, qui envoie une synchronisation silencieuse par entrée au
joueur qui arrive. Comme personne n'est connecté au démarrage du serveur, cela suffit.

### Le fichier de sauvegarde

```text
config/cobbleshift_npc_transforms.json
```

⚠️ Ce chemin est un **chemin relatif littéral**, `Path.of("config/cobbleshift_npc_transforms.json")`,
résolu par rapport au dossier de travail du serveur. Il ne passe pas par
`FabricLoader.getConfigDir()` comme le fait `cobbleshift.json`. Sur une installation
standard, les deux fichiers aboutissent dans le même dossier `config/`. Si ton hébergeur
lance le serveur depuis un autre dossier, ou déplace le dossier de configuration, le
fichier des PNJ atterrit ailleurs.

Un tableau JSON, un objet par PNJ :

```json
[
  {
    "npcUuid": "1a2b3c4d-0000-4000-8000-000000000001",
    "species": "snorlax",
    "shiny": false,
    "type1": "normal"
  },
  {
    "npcUuid": "1a2b3c4d-0000-4000-8000-000000000002",
    "species": "lycanroc",
    "shiny": true,
    "form": "midnight",
    "type1": "rock",
    "scale": 1.5,
    "speed": 2.0
  }
]
```

`scale` et `speed` ne sont écrits que s'ils diffèrent de `1.0`, et relus comme `1.0` en
leur absence. `form`, `type1` et `type2` sont **omis** quand ils valent `null` : l'instance
Gson qui écrit le fichier ne sérialise pas les `null`, donc une clé à `null` disparaît.
`load()` relit une clé absente comme `null`.

Modifier le fichier à la main ne fonctionne que serveur arrêté : la modification suivante
en jeu l'écrase depuis la mémoire. La valeur `species` n'est pas revalidée au chargement :
une faute de frappe survit jusqu'à ce qu'un client tente de construire le modèle et échoue
sans bruit.

⚠️ Une entrée mal formée est dangereuse. `load()` vide la map, puis ajoute les entrées une
à une ; une exception en cours de route (un UUID invalide, un `species` manquant) arrête la
boucle et journalise `Failed to load NPC transforms: <message>`, en ne gardant que les
entrées lues avant elle. Le `save()` suivant, qui a lieu à la prochaine modification ou au
prochain arrêt, réécrit cette map partielle et **les entrées restantes sont perdues**. Garde
une copie du fichier avant de le modifier.

## Comment le client le dessine

Le serveur envoie un `TransformSyncPayload` avec `fromNpc = true`. Le champ UUID du paquet
s'appelle `playerUuid` pour des raisons historiques ; pour un PNJ, il porte l'UUID du PNJ.
Le récepteur client vérifie `fromNpc` et ne joue jamais de cri ni de particules pour lui,
quoi que dise le drapeau `silent`. Une espèce vide signifie une annulation.

`TransformRenderer` range la transformation dans les mêmes maps que les joueurs, indexées
par UUID. Ensuite `NpcTransformModelMixin`, injecté en tête de
`EntityRenderDispatcher.render()`, s'exécute pour chaque entité rendue :

1. Il rend la main aussitôt pour toute `PlayerEntity`, dont s'occupe le mixin des joueurs.
2. Il rend la main si l'UUID de l'entité n'a pas de transformation.
3. Il récupère ou construit le fantôme : un `PokemonEntity` côté client, créé par
   réflexion, jamais ajouté au monde.
4. Il copie sur le fantôme la position, la rotation, la vélocité, l'orientation du corps
   et de la tête et la vitesse des membres du PNJ, et fait avancer le délégué d'animation
   Cobblemon du fantôme une fois par tick de jeu.
5. Il dessine le fantôme à la position du PNJ, mis à l'échelle par la `scale` enregistrée.
6. Il annule le rendu propre du PNJ.

Toute exception sur ce chemin est avalée. Si le moteur de rendu de Cobblemon lève une
exception en plein milieu, le mixin dépile la pile de matrices et l'image continue. Le
symptôme visible d'un échec est un PNJ rendu tel quel, ou pas rendu du tout, sans rien dans
le journal.

Le fantôme n'a ni pose de sommeil, ni animation d'attaque, ni logique de caméra :
`syncGhostToEntity()` est la version allégée de la synchronisation des joueurs.

`ShinyChimeMuteMixin`, qui fait taire le carillon shiny de Cobblemon autour des joueurs
transformés en shiny, ne regarde que les **joueurs** transformés (`getPlayerByUuid`). Un
PNJ shiny n'est pas couvert. Que son fantôme émette réellement le carillon dépend du code
client de Cobblemon et n'a pas été vérifié.

## Piloter en administrateur

| Action | Commande | Dashboard |
|---|---|---|
| transformer un PNJ | `/cshift transformnpc <pokemon…>` | — |
| en annuler un | `/cshift revertnpc` | — |
| tout annuler | `/cshift revertallnpc` | — |
| redimensionner le modèle | `/cshift scalenpc <value>` | `POST /api/v1/shift/scale` |
| changer la vitesse de marche | `/cshift speednpc <value>` | `POST /api/v1/shift/speed` |
| les lister | — | `GET /api/v1/shift/transforms`, dans le tableau `npcs` |

Aucune route REST ne transforme ni n'annule un PNJ : le dashboard ne peut que lister et
régler ce que tu as créé en jeu. `GET /transforms` est d'ailleurs le seul endroit qui
montre les transformations de PNJ, avec leurs UUID ; `/cshift list` ne montre que celles
des joueurs. Le détail des routes est dans [l'API du dashboard](dashboard.fr.md).

⚠️ Les deux routes de réglage du dashboard appellent `setNpcScale()` et `setNpcSpeed()`
directement sur le thread HTTP, sans `executeOnServerThread`. `setNpcSpeed()` cherche
l'entité et modifie ses attributs depuis ce thread, et toutes deux écrivent le fichier de
sauvegarde en concurrence avec le thread serveur. Sur un serveur calme, ça fonctionne en
pratique ; cela reste une situation de concurrence non protégée.
[L'architecture](architecture.fr.md#threading) la range avec les autres appels hors du
thread serveur.

## Cas limites

**Le PNJ est supprimé.** Rien n'écoute la suppression des entités. L'entrée reste dans la
map et dans le fichier, est envoyée à chaque joueur qui se connecte, et coûte un peu de
bande passante pour toujours. Seul `/cshift revertallnpc` l'efface, et il efface aussi tous
les PNJ encore vivants.

**Le PNJ est dans un chunk non chargé.** La transformation n'en souffre pas. Les clients
connaissent déjà l'UUID et dessinent le modèle quand l'entité se charge. Les changements de
vitesse faits depuis le dashboard pendant que le PNJ est déchargé sont enregistrés, puis
appliqués par la vérification des 100 ticks une fois qu'il se charge.

**Un client se connecte après un redémarrage.** Il reçoit toutes les transformations de
PNJ à la connexion, en silence. Aucun cri ne se joue.

**Un client change de serveur.** Le client ne vide jamais ses maps de transformation à la
déconnexion (`TransformRenderer.clear()` n'a aucun appelant). Un UUID transformé sur un
serveur reste enregistré côté client jusqu'à la fin de la session de jeu. Pour les PNJ, le
risque est faible, les UUID d'entités se recoupant rarement d'un serveur à l'autre.

---

← [Capacités passives](abilities.fr.md) · [API du dashboard](dashboard.fr.md) →
