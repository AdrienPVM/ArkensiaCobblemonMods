[← CheeseItems](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](architecture.md)

# Architecture

28 fichiers source, environ 4 980 lignes, un mixin, deux modules dashboard, quatre payloads
réseau. Le mod a deux moitiés qui ne partagent que le jar et le logger : les items
personnalisés, et le catalogue de morphos.

## Arborescence des packages

```text
com.cheeseitems
├── CheeseItemsMod.java          point d'entrée : câblage, cycle de vie, envoi du resource pack
├── CheeseItemsConfig.java       persistance des items, des CustomModelData, des fichiers d'assets
├── CustomItemDefinition.java    le modèle d'un item + ses quatre classes internes
├── ItemManager.java             CRUD, construction d'ItemStack, rafraîchissement des inventaires
├── UseHandler.java              clic droit, conditions, cooldowns, effets, actions, endurance par item
├── CheeseItemsCommand.java      /cheeseitem
├── MorphoCommand.java           /morpho
├── ResourcePackGenerator.java   construit le ZIP du pack en mémoire
├── client/                      côté client seulement : écran du codex, cache d'images, raccourcis
├── dashboard/                   deux implémentations de DashboardModule + leurs API
├── integration/                 CheeseStoreHook — réflexion vers CheeseStore
├── mixin/                       TotemMixin
├── morpho/                      catalogue, codex, manager, tracker d'endurance, miniatures
└── network/                     quatre records CustomPayload
```

### Ce que possède chaque classe

| Classe | Possède |
|---|---|
| `CheeseItemsMod` | le singleton `INSTANCE`, le graphe d'objets, toutes les inscriptions d'événements, l'envoi du resource pack |
| `CheeseItemsConfig` | la liste d'items en mémoire, la table `customModelData`, `resourcePackHost`, et toutes les entrées/sorties sur les fichiers d'assets |
| `CustomItemDefinition` | les champs d'un item plus `PotionEffectEntry`, `CommandAction`, `UseConditions`, `EnduranceConfig` |
| `ItemManager` | le seul code qui construit ou répare un `ItemStack`, et les helpers NBT statiques |
| `UseHandler` | tout le comportement en jeu des items, les tables de cooldowns, la liste des tâches différées, les réserves d'endurance par item, la réserve soulbound |
| `MorphoCatalog` | le catalogue, un singleton paresseux, sa propre sauvegarde différée, et les valeurs d'endurance globales |
| `MorphoCodex` | la possession par joueur et l'identifiant actif, avec sa propre sauvegarde différée |
| `MorphoManager` | le pont vers `cshift`, la table des joueurs transformés, le payload de synchronisation |
| `MorphoEnduranceTracker` | un singleton eager tenant une réserve par joueur, avancé à chaque tick |
| `MorphoThumbnail` | le cache disque des miniatures des images de catalogue |
| `CheeseItemsClient` | les raccourcis, l'instantané du codex côté client, les deux récepteurs |

Deux singletons sont accessibles sans passer par `CheeseItemsMod` :
`MorphoCatalog.getInstance()` (paresseux, se charge au premier accès, raison pour laquelle
`onInitialize` le touche délibérément) et `MorphoEnduranceTracker.getInstance()` (champ
statique eager). Tout le reste pend sous `CheeseItemsMod.INSTANCE`.

## L'état et le moment où il atteint le disque

| État | Où | Écrit |
|---|---|---|
| Définitions d'items | `config/cheeseitems.json` | de façon synchrone à chaque création, modification et suppression, et après l'attribution d'un `customModelData` |
| `customModelData`, `resourcePackHost` | même fichier | avec les définitions |
| Icônes d'items | `config/cheeseitems_assets/<id>.png` | immédiatement à l'envoi |
| Catalogue de morphos | `config/cheeseitems_morphos.json` | différé de 5 s, forcé à `SERVER_STOPPING` |
| Codex des joueurs | `config/cheeseitems_morpho.json` | différé de 5 s, forcé à `SERVER_STOPPING` |
| Images du catalogue | `config/cheeseitems_assets/morphos/<id>.png` | immédiatement à l'envoi |
| Miniatures | à côté des originaux | à la première demande |
| ZIP du resource pack | nulle part — mémoire seulement | reconstruit à la demande |
| Cooldowns, tâches différées, réserve soulbound, les deux réserves d'endurance, la table des transformés | mémoire | jamais |

Le différé est le point faible. `markDirty()` n'enregistre que si plus de cinq secondes se
sont écoulées depuis la dernière sauvegarde ; sinon il lève un drapeau et compte sur une
**modification ultérieure** pour déclencher l'écriture. Il n'y a pas de minuterie. Un crash
entre la dernière modification et `SERVER_STOPPING` perd ce qui s'est passé dans cette
fenêtre.

Les définitions d'items échappent à ce problème : chaque modification appelle directement
`config.save()`, qui réécrit tout le fichier.

## Threading

| Tourne sur | Quoi |
|---|---|
| Thread serveur | `UseItemCallback`, `END_SERVER_TICK` (le tick de `UseHandler` comme celui de l'endurance des morphos), l'exécution des commandes, toute mutation d'inventaire, `ALLOW_DEATH`, `DISCONNECT`, `JOIN` |
| Thread HTTP (le `HttpServer` de CheeseDash) | chaque gestionnaire de `ItemsApi` et `MorphoCatalogApi` |
| Thread réseau Netty | les récepteurs de payloads, qui passent immédiatement la main avec `ctx.server().execute(...)` |
| Thread client | les raccourcis, l'écran du codex ; l'application d'une synchronisation passe par `context.client().execute(...)` |
| Un thread HTTP, avec son propre exécuteur | la mise à l'échelle des images dans `MorphoThumbnail` |

Le passage d'un thread HTTP au thread serveur est
`WebDashboard.executeOnServerThread(server, () -> …)`, et les API l'utilisent là où elles le
doivent : le `PUT` d'un item (il rafraîchit les inventaires), `give`, `reload`, le
rafraîchissement des routes d'icône, le `grant` des morphos et la resynchronisation après
suppression.

Elles ne l'utilisent **pas** pour le reste, et c'est un choix d'apparence délibérée mais non
documentée qu'il vaut mieux connaître :

- La création et la suppression dans `ItemsApi` modifient la liste de définitions et
  écrivent le fichier directement depuis le thread HTTP. Cette liste est un `ArrayList`
  ordinaire, lu sur le thread serveur à chaque clic droit. Une création concurrente d'une
  utilisation est en théorie une course de données ; en pratique la fenêtre est de l'ordre
  de la microseconde et aucun crash n'a été signalé. C'est tout de même incorrect.
- `MorphoCatalog` et `MorphoCodex` utilisent `ConcurrentHashMap` et des méthodes
  `synchronized`, donc les routes du catalogue sont réellement sûres hors du thread serveur.

`MorphoEnduranceTracker.pools` est un `ConcurrentHashMap` mais n'est touché que depuis le
thread serveur.

## Le mixin

Un seul mixin, `cheeseitems.mixins.json`, `required: true`, sans séparation client/serveur.

| Mixin | Cible | Injection | Raison d'être |
|---|---|---|---|
| `TotemMixin` | `LivingEntity#tryUseTotem` | `@Inject(at = HEAD, cancellable = true)` | empêche un item personnalisé construit sur `minecraft:totem_of_undying` de ressusciter son porteur |

La logique examine les deux mains. Si une main tient un totem portant le tag `cheeseitems`
et qu'aucune main ne tient un totem ordinaire, la méthode renvoie `false` et la mort se
poursuit. Un joueur tenant à la fois un totem personnalisé et un vrai garde l'effet du vrai.

La cible est un nom de méthode vanilla, donc ce mixin est la seule pièce du mod qu'une
montée de version de Minecraft peut casser net. C'est aussi la seule raison pour laquelle un
item personnalisé peut utiliser sans danger la texture du totem.

## Points d'extension

- **Items** : ajoute un champ à `CustomItemDefinition`, traite-le dans `toJson`, `fromJson`
  et `mergeFrom` (les trois — oublier `mergeFrom` est la façon dont un champ échoue en
  silence à s'enregistrer depuis le dashboard), puis lis-le dans `UseHandler` ou
  `ItemManager.applyVisuals`. Rien d'autre à toucher ; l'API sert ce que produit `toJson`.
- **Resource pack** : `ResourcePackGenerator.addExternalEntry(...)` permet à un autre mod de
  glisser des textures dans le même pack. Voir
  [le resource pack](resource-pack.fr.md#entrées-venant-dautres-mods).
- **Morphos** : `CheeseItemsMod.INSTANCE.getMorphoManager().addToCodex(player, entry)`
  attribue une morpho depuis Java. `MorphoCatalog.getSortedForWebsite()` et
  `getSortedForNpcShop()` existent pour les surfaces de boutique hors de ce mod.
- **Un nouveau module dashboard** : ajoute la classe au tableau de l'entrypoint
  `cheesedash` dans `fabric.mod.json`. CheeseDash le découvre au démarrage ; rien dans ce
  mod ne l'enregistre.

## <a id="faiblesses-connues"></a>Faiblesses connues

Écrites noir sur blanc parce que la prochaine personne les rencontrera.

1. **Ici, un `OPERATOR` est une console.** Les `useActions` d'un item lancent des commandes
   arbitraires au niveau de permission 4 : quiconque peut écrire un item peut donc tout
   lancer. Toute route mutante est restreinte à `OPERATOR` par un garde posé sur la méthode
   HTTP dans les deux fichiers d'API, et une seconde fois par le répartiteur de modules de
   CheeseDash — qui est aussi le plafond : comme le garde central ne connaît que
   `requireWrite`, aucune route d'ici ne peut exiger `SUPER_ADMIN`. Le chemin d'élévation
   depuis `OPERATOR` subsiste, par conception.
   [Détails](dashboard.fr.md#authentification-et-gardes-de-rôle).
2. **`soulbound` supprime les items.** `UseHandler.onPlayerDeath` met de côté les piles
   soulbound et les retire de l'inventaire ; `onPlayerRespawn`, qui les rendrait, n'a aucun
   appelant. Le gestionnaire `AFTER_DEATH` dans `CheeseItemsMod` est une lambda vide avec un
   commentaire disant que la restauration appartient ailleurs. Corriger demande d'enregistrer
   `ServerPlayerEvents.AFTER_RESPAWN` et d'y appeler `onPlayerRespawn`.
3. **Cinq champs et une liste d'effets sont morts.** `droppable` (sa méthode d'application
   n'a pas d'appelant), `onPickupEffects` (idem), `tradeable`, `placeable`,
   `useConditions.zoneId`, `endurance.displayName`. Tous les six circulent dans l'API et
   l'éditeur. [Tableau](items.fr.md#les-options-qui-ne-font-rien).
4. **Deux systèmes d'endurance.** `UseHandler` en a un, indexé par `tag` + joueur, pour les
   items ; `MorphoEnduranceTracker` en a un autre, indexé par joueur, pour les morphos. Ils
   dupliquent la logique de drain, de régénération, d'affichage dans la barre d'action et
   d'épuisement presque ligne pour ligne, dessin de la barre inclus. Une modification de l'un
   doit être recopiée à la main dans l'autre.
5. **Le trou de la transformation à sec.** Se transformer avec une réserve vide exécute
   `cshift transform` *puis* refuse de démarrer le drain, laissant le joueur transformé sans
   rien pour l'annuler. [Où](morphos.fr.md#la-réserve-dendurance).
6. **La table des transformés n'est pas persistée.** Un redémarrage désynchronise l'idée que
   CheeseItems a de qui est transformé de celle de CobbleShift.
7. **`cshift` est appelé comme une chaîne de commande.** Pas d'API, pas de vérification de
   version, pas de dépendance déclarée. Cela casse en silence quand CobbleShift change.
8. **Pas de rechargement du catalogue.** `MorphoCatalog.load()` ne tourne qu'une fois. Les
   éditions à la main de `cheeseitems_morphos.json` sur un serveur en marche sont écrasées.
9. **`baseItem` n'est jamais validé.** Un identifiant inconnu donne `minecraft:air`, `give`
   rapporte un succès, et le joueur ne reçoit rien.
10. **Une erreur d'analyse dans `cheeseitems.json` vide la liste d'items** au lieu de
    refuser de démarrer, et la sauvegarde suivante écrase le fichier cassé par
    `{"items":[]}`.
11. **Le pack généré remplace des fichiers de modèle et de police vanilla entiers**, donc il
    entre en conflit avec tout autre pack touchant les mêmes items de base ou
    `default.json`.
12. **`CheeseStore` n'est pas déclaré** dans `fabric.mod.json`, pas même en `suggests`, alors
    que la javadoc de `CheeseStoreHook` l'affirme. La réflexion est protégée, donc cela ne
    coûte rien à l'exécution — cela ne trompe que la personne qui lit le manifeste.
13. **`/items/:id/give` contourne la validation d'identifiant** que `GET`, `PUT` et `DELETE`
    appliquent, parce que la branche `/give` est reconnue avant l'appel à `isValidId`.
    L'identifiant ne sert jamais que de clé de table, donc il n'y a pas de traversée de
    chemin, mais cette asymétrie est accidentelle.

## Environnement

`"environment": "*"` avec un entrypoint `client`. Le jar se charge des deux côtés. La moitié
serveur est tout le système d'items et la logique des morphos ; la moitié cliente est l'écran
du Codex, les deux raccourcis et le cache d'images. Un joueur sans le jar peut recevoir des
items, les voit correctement (les données voyagent dans les composants de la pile), reçoit le
resource pack, et n'a aucune interface de Codex ni aucun moyen d'activer une
morpho.

`cheesedash` est un `depends` strict : le mod ne peut pas se charger sans lui — les deux
moitiés atteignent directement des classes de CheeseDash
(`CheeseDashMod.INSTANCE.getConfig().getPort()` entre autres), et il n'existe aucun chemin de
repli.

---

← [API dashboard](dashboard.fr.md) · [Dépannage](troubleshooting.fr.md) →
