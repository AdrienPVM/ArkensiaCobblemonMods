[← CheesePatrol](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](architecture.md)

# Architecture

Dix fichiers source, 2 313 lignes, dont 881 dans une seule classe. Assez petit pour que
cette page couvre tout honnêtement, y compris ce qui est mauvais.

---

## Découpage des packages

```text
com.cheesepatrol
├── CheesePatrolMod.java            entrypoint : quatre hooks de cycle de vie, le tick, la commande, les handlers
├── PatrolManager.java              881 lignes — tout le comportement et tout l'état. Le mod, en fait
├── PatrolData.java                 une patrouille : points de passage, réglages, drapeaux transitoires, JSON
├── PatrolCommand.java              l'arbre Brigadier /npcpatrol, 18 sous-commandes
├── PatrolWand.java                 la tige de Blaze et ses tags de données personnalisées
├── PatrolInteractionHandler.java   clic droit entité → sélection, clic droit bloc → ajout de point de passage
├── PatrolMarker.java               marqueurs porte-armures, aperçu du trajet, 46 effets de traînée
├── mixin
│   └── EntityRemoveMixin.java      annule Entity.remove pour les NPC protégés
└── dashboard
    ├── CheesePatrolDashboardModule.java   enregistre le préfixe « patrol » auprès de CheeseDash
    └── CheesePatrolApi.java               les deux routes REST
```

Rien n'est enregistré dans un registre Minecraft. Aucun item, aucun bloc, aucun type
d'entité, aucun son, aucun composant de données propre. La baguette est un `BLAZE_ROD`
vanilla portant un composant `minecraft:custom_data`, et les marqueurs sont des
porte-armures vanilla avec des tags de commande. C'est pourquoi le mod n'a ni resource
pack, ni textures, ni fichiers de modèle.

## Qui possède quoi

**`CheesePatrolMod`** ne fait que câbler. `onInitialize` enregistre, dans l'ordre :

| Hook | Effet |
|---|---|
| `SERVER_STARTING` | `PatrolManager.load()` — délibérément tôt, pour que le mixin ait des données avant qu'un chunk ne tourne |
| `SERVER_STARTED` | `forceloadActivePatrolChunks` |
| `SERVER_STOPPING` | sauvegarder toutes les entités, écrire le fichier, retirer tous les marqueurs. Ne relâche **pas** les chunks forcés |
| `START_SERVER_TICK` | `PatrolManager.tick(server)` |
| `CommandRegistrationCallback` | l'arbre de commandes |
| — | `PatrolInteractionHandler.register()` |

Il n'y a pas d'`INSTANCE` et pas d'objet singleton. Tout dans `PatrolManager` est
`static`, ce qui est la décision structurelle centrale du mod et la source de plusieurs
des faiblesses listées plus bas.

**`PatrolManager`** possède le comportement et tout l'état :

```java
private static final Map<UUID, PatrolData> patrols            = new ConcurrentHashMap<>();
private static final Map<UUID, UUID>       showingParticles   = new ConcurrentHashMap<>();
private static final Map<UUID, Set<Long>>  patrolForcedChunks = new ConcurrentHashMap<>();
private static boolean debug = false;
private static volatile boolean loaded = false;
private static int backupTickCounter, recoveryTickCounter, particleTickCounter, startupDelayTicks;
```

Les tables sont concurrentes parce que le thread HTTP lit `patrols`. Les `Set<Long>` à
l'intérieur de `patrolForcedChunks` sont de simples `HashSet`, ce qui est sûr puisque seul
le thread serveur y touche. Les compteurs de ticks et `debug` sont des statiques ordinaires,
réservés au thread serveur.

`tick(server)` constitue tout le runtime : il parcourt chaque patrouille et appelle
`tickPatrol` pour celles qui sont actives avec au moins deux points de passage, puis fait
tourner quatre compteurs : l'intégrité de démarrage à 100 ticks, les sauvegardes toutes les
1200, le contrôle d'intégrité toutes les 6000, les particules toutes les 10.

**`PatrolData`** est un bean mutable, un par patrouille, avec ses champs persistés et un
bloc de champs `transient` qui ne sont jamais écrits :

| Persistés | Transitoires |
|---|---|
| `waypoints`, `waypointWaitTicks`, `speed`, `playerDetectionRadius`, `active`, `currentWaypointIndex`, `dimension`, `lastX/Y/Z`, les quatre champs de particules, `pausedBySystem` | `waitingAtWaypoint`, `remainingWaitTicks`, `lookingAtPlayer`, `missingTicks`, `respawnAttempts`, `stuckTicks`, `stallTicks` |

Noter que `toJson` **omet tout champ portant sa valeur par défaut**, et que `fromJson`
laisse les champs absents à leurs valeurs de construction. Cela garde le fichier compact et
signifie qu'ajouter un champ demande de toucher les deux méthodes, sans quoi il ne se
persiste jamais, en silence.

`fromJson` affecte les champs **directement**, en contournant les setters qui bornent. Les
plages documentées dans [configuration](configuration.fr.md) sont imposées par les
commandes, pas par le chargeur.

**`PatrolWand`** est un ensemble de helpers statiques au-dessus du composant `CUSTOM_DATA`.
C'est lui qui porte la sélection, et c'est pourquoi la baguette doit être en main
principale pour presque toutes les sous-commandes. Deux de ses cinq méthodes ne sont jamais
appelées.

**`PatrolMarker`** réunit trois systèmes d'affichage sans rapport dans un même fichier.
Voir [rendu visuel](visuals.fr.md).

## L'état, et quand il atteint le disque

Deux fichiers, tous deux écrits de façon synchrone depuis le thread serveur.

**`config/cheesepatrol_data.json`** est réécrit en entier par `PatrolManager.save()`. Appelé
depuis : chaque sous-commande qui modifie quelque chose, le clic droit de la baguette sur un
bloc, le chemin d'abandon de réapparition, et le contrôle d'intégrité quand quelque chose a
changé. Ajouter un point de passage sur un serveur à 50 patrouilles sérialise et écrit donc
les 50 patrouilles, dans le tick, avant que la commande ne rende la main.

Il est écrit avec `Files.writeString` par-dessus le fichier existant. **Pas de fichier
temporaire, pas de renommage atomique, pas de copie de sauvegarde.** Un crash en pleine
écriture perd tous les trajets.

**`config/cheesepatrol_entities/<uuid>.dat`** est un instantané NBT compressé par NPC de
patrouille, écrit par `backupAllEntities` toutes les 1200 ticks, par `tickPatrol` quand un
NPC manquant réapparaît, et à l'arrêt. La passe périodique écrit *N* fichiers à la suite sur
le thread serveur, ce qui est le plus gros coût en un seul tick du mod à l'échelle.

Les lectures n'ont lieu qu'une fois chacune : `load()` à `SERVER_STARTING`, et un fichier
`.dat` seulement quand une réapparition est tentée.

## Threading

| Thread | Ce qui y tourne |
|---|---|
| **Serveur (principal)** | tout `PatrolManager`, l'arbre de commandes, les deux callbacks d'interaction, toutes les E/S disque |
| **HTTP** (CheeseDash) | `CheesePatrolApi.handle` et ses deux handlers |
| **Netty IO** | rien. Le mod n'enregistre aucun payload ni handler réseau |

Le passage de relais est `WebDashboard.executeOnServerThread(server, …)`, utilisé par
`POST /tp` pour la téléportation. Il bloque le thread HTTP jusqu'à cinq secondes en
attendant le thread serveur : un `200` signifie donc *programmé et accusé*, pas *terminé*.

⚠️ **`GET /list` touche des entités depuis le thread HTTP.** `resolveNpcName` parcourt
`server.getWorlds()` et appelle `world.getEntity(uuid)` sans passer par le thread serveur.
`ServerWorld.getEntity` lit la table de recherche d'entités, que le thread serveur modifie à
chaque tick à mesure que des entités arrivent et partent. Ce n'est pas une lecture
documentée comme thread-safe. En pratique elle renvoie un nom ou null et n'a pas été vue
échouer ; correctement, elle devrait être enveloppée dans `executeOnServerThread`, ou bien
le nom devrait être mis en cache sur le `PatrolData` au dernier passage du thread serveur.
La règle propre au dépôt — lire depuis un thread HTTP ce qui est immuable ou concurrent,
passer la main pour le reste — place ceci du mauvais côté de la ligne.

Le reste de l'API est propre : `patrols` est une `ConcurrentHashMap` et les champs lus sur
un `PatrolData` sont des primitives, donc un listage peut se déchirer à la frontière d'un
tick mais ne peut rien corrompre.

## Le mixin

Un seul mixin, côté serveur, déclaré dans `cheesepatrol.mixins.json` avec
`"required": true` et `"defaultRequire": 1`.

```java
@Mixin(Entity.class)
public abstract class EntityRemoveMixin {
    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void cheesepatrol$preventPatrolRemoval(Entity.RemovalReason reason, CallbackInfo ci)
}
```

| | |
|---|---|
| **Cible** | `net.minecraft.entity.Entity#remove(Entity.RemovalReason)`, en HEAD |
| **Pourquoi** | Un NPC de patrouille tué ou jeté casse une ville en silence. C'est la seule couche qui arrête une suppression volontaire |
| **Portée** | Toutes les entités du jeu. Le filtre est dans la méthode, pas dans l'injection |
| **En cas d'échec** | Le serveur **plante au démarrage**. `defaultRequire: 1` rend une injection manquée fatale |

Être fatal en cas d'échec est le bon choix ici, et l'inverse de ce que fait CheeseKart avec
ses mixins client cosmétiques. Perdre silencieusement la protection contre la suppression
signifierait des NPC qui disparaissent sans indication de cause — bien pire qu'un serveur
qui refuse de démarrer après une mise à jour de Minecraft. Si la signature de la méthode
change, tu le sauras immédiatement.

Les deux vrais problèmes de ce mixin — une vérification disque à chaque suppression
d'entité sur tout le serveur, et une journalisation `INFO` inconditionnelle — sont dans
[garder les NPC en vie](resilience.fr.md#couche-1--le-mixin-de-suppression).

## La dépendance EasyNPC tient en un appel par réflexion

C'est le seul contact du mod avec EasyNPC, et il vaut la peine de le lire attentivement
parce que c'est ce qui a le plus de chances de casser.

**Où :** `PatrolManager.getEasyNPCGoalSelector(Entity)`, vers la ligne 655 de
`PatrolManager.java`.

```java
public static GoalSelector getEasyNPCGoalSelector(Entity entity) {
    try {
        Method method = entity.getClass().getMethod("getEntityGoalSelector");
        Object result = method.invoke(entity);
        if (result instanceof GoalSelector gs) return gs;
    } catch (Exception ignored) {
        // Not an EasyNPC entity or method not found
    }
    return null;
}
```

**Appelé depuis** trois endroits, qui tous effacent entièrement le selector renvoyé avec
`gs.clear(g -> true)` :

- toutes les 100 ticks serveur dans `tickPatrol`, en entretien
- `kickStartPatrol`, pour que `/npcpatrol start` prenne effet immédiatement
- le chien de garde de calage, après 5 secondes de vélocité quasi nulle

**Pourquoi cela existe :** EasyNPC installe ses propres goals d'IA — regarder autour,
flâner, ce que le NPC est configuré pour faire. Ces goals se disputent la navigation avec la
patrouille, et les laisser en place empêche le NPC d'avancer. Les effacer est le remède
brutal. Cela signifie aussi qu'un NPC en patrouille perd tout son comportement EasyNPC,
définitivement, aussi longtemps que la patrouille existe.

⚠️ **Le nom de la méthode est écrit en dur et cassera quand EasyNPC changera.**
`getEntityGoalSelector` est une méthode de l'API propre d'EasyNPC, pas une méthode de
Minecraft : elle n'est donc pas remappée à l'exécution et une recherche par
`getMethod(name)` est légitime — ce n'est pas le piège des mappings intermédiaires contre
lequel les conventions du dépôt mettent en garde. La fragilité est purement une question de
stabilité d'API : EasyNPC la renomme, la déplace dans une autre interface, ou change son
type de retour, et la recherche commence à renvoyer `null`.

**Et l'échec est silencieux.** Le `catch` avale tout avec un commentaire, il n'y a aucune
ligne de log même sous `debug`, et les appelants traitent `null` comme « ce n'est pas un
EasyNPC, rien à effacer ». Le symptôme d'une version d'EasyNPC incompatible n'est donc pas
une erreur — ce sont des NPC qui cessent de marcher sans raison affichée, et c'est la panne
la plus difficile à diagnostiquer dans ce mod. La première amélioration qu'un contributeur
devrait apporter est un avertissement unique la première fois que la recherche échoue sur
une entité dont le nom de classe contient `easy_npc`.

**`isAirWaypoint`, `PathAwareEntity` et tout le reste** sont vanilla. Le mod pilotera
n'importe quel `PathAwareEntity` de n'importe quel mod ; ce qu'il ne peut pas faire pour une
entité non EasyNPC, c'est effacer les goals de ce mod — donc qu'elle avance ou non dépend
de si ces goals lui résistent.

## La curiosité `compileOnly` dans `build.gradle`

CheesePatrol est le **seul** mod de ce dépôt qui déclare CheeseDash avec un `compileOnly`
simple plutôt qu'un `modCompileOnly` :

```groovy
compileOnly files(fileTree(dir: "../CheeseDash/build/libs", includes: ["cheesedash-*.jar"], …))
```

Tous les mods voisins utilisent `modCompileOnly` ou `modImplementation`. La différence
compte : les configurations `mod*` de Loom remappent les références aux types Minecraft du
jar depuis les noms de production (intermédiaires) vers les noms de l'environnement de
développement (Yarn) avant de le mettre sur le classpath de compilation. Un `compileOnly`
simple met le jar de production sur le classpath sans y toucher.

Cela compile quand même, par chance plus que par conception. Toutes les API CheeseDash que
CheesePatrol utilise ne manipulent que des types dont le nom est identique dans les deux
mappings :

- `HttpExchange`, `IOException`, `String`, `int` : JDK et primitives
- `MinecraftServer` : l'une des rares classes Minecraft non obfusquées
- `DashboardModule`, `WebDashboard` : les classes propres de CheeseDash

La première fois que quelqu'un appellera une méthode CheeseDash prenant un `ServerWorld`,
un `ServerPlayerEntity` ou un `Text`, cette compilation échouera sur un
`net.minecraft.class_NNNN` introuvable. Si tu étends la couche dashboard et que tu obtiens
cette erreur, change la ligne en `modCompileOnly` plutôt que de la contourner.

La ligne `compileOnly "maven.modrinth:easy-npc:PG3ZdKaC"` est elle aussi vestigiale : aucune
classe d'EasyNPC n'est importée nulle part dans le mod, donc rien n'en a besoin à la
compilation.

## Points d'extension

**Une nouvelle sous-commande.** Ajoute un `literal` sous la racine dans
`PatrolCommand.register`, et un handler qui commence par
`UUID npcUuid = getSelectedNpc(ctx); if (npcUuid == null) return 0;` — ce helper fait le
contrôle « joueur », le contrôle de la baguette et le message d'erreur. Appelle
`PatrolManager.save()` si tu modifies quelque chose.

**Un nouveau champ de patrouille persisté.** Quatre endroits dans `PatrolData` : le champ,
un getter et un setter qui borne, une ligne dans `toJson` (conditionnée au non-défaut si tu
veux que le fichier reste compact), et une ligne dans `fromJson`. Oublier `fromJson` est
l'erreur facile, et fait que le champ se réinitialise en silence à chaque redémarrage.

**Un nouvel effet de particules.** Deux endroits dans `PatrolMarker` : un `case` dans
`resolveParticle` et le nom dans la liste `PARTICLE_EFFECTS`. Seule la liste alimente la
complétion par tabulation et la validation de la commande, et seul le switch alimente le
rendu : manquer l'un des deux donne un nom impossible à régler, ou un nom qui se règle mais
n'affiche rien.

**Une nouvelle route REST.** Ajoute une branche dans `CheesePatrolApi.handle`. Écris le
garde de rôle sous la forme `if (!WebDashboard.requireWrite(exchange)) return;` — et non
sous la forme utilisée par la route `/tp` existante. Enveloppe tout ce qui touche au monde
dans `WebDashboard.executeOnServerThread`.

**Des mises à jour WebSocket.** Rien n'existe encore. `DashboardRegistry.broadcast(...)`
depuis le thread serveur est le mécanisme ; `advanceToNextWaypoint` et les transitions
actif/inactif sont les points d'accroche utiles. Ne diffuse pas à chaque tick.

**Prendre en charge un autre mod de NPC.** Généralise `getEasyNPCGoalSelector` pour
parcourir `entity.getClass().getMethods()` à la recherche d'une méthode sans argument dont
le type de retour est `GoalSelector`, au lieu de comparer un nom. Cela couvre n'importe quel
mod exposant son selector sous n'importe quel nom, et c'est la technique que le dépôt impose
déjà pour réfléchir dans les classes Minecraft.

**Atteindre ce mod depuis un autre mod.** Tout dans `PatrolManager` est public et statique :
`PatrolManager.get(uuid)`, `getAll()`, `kickStartPatrol(server, patrol)` et `save()` sont
donc appelables directement. Pratique, et la raison pour laquelle il n'y a aucune instance à
faire circuler.

## Faiblesses connues

Dites franchement, parce que les découvrir par surprise est pire.

### Comportement

- **Le déplacement est un écrasement de vélocité par tick, pas du pathfinding.** Les NPC
  vont droit sur le point de passage suivant et ne contournent pas les obstacles. Sur un
  segment au sol, le mod lance *aussi* la navigation vanilla vers le point de passage : deux
  systèmes pilotent donc le même mob, et c'est l'écrasement que tu vois. C'est une verrue,
  pas un choix de conception.
- **Le chien de garde de blocage mesure une distance, pas une progression**, malgré son
  propre commentaire. Une marche légitime de plus d'environ 70 blocs à la vitesse par défaut
  se fait téléporter à chaque tour. Voir
  [circuits de patrouille](patrol-circuits.fr.md#le-chien-de-garde-de-blocage--60-secondes-téléportation).
- **Le contrôle « regarder un joueur » n'a ni ligne de vue ni temps de recharge** : un NPC
  se fige pour un joueur derrière un mur et reste figé tant qu'il y est — y compris en
  suspendant le décompte d'une attente sur un point de passage.
- **Bloquer `RemovalReason.KILLED` n'empêche pas la mort.** D'après le chemin de code, un
  NPC de patrouille qui prend des dégâts mortels meurt puis n'est pas supprimé. Rends les
  NPC invulnérables dans EasyNPC plutôt que de compter sur le mixin.
- **Une réapparition peut créer un UUID en doublon.** Le contrôle de conflit ne voit que les
  mondes chargés ; si le NPC d'origine est dans un chunk déchargé, deux entités finissent par
  revendiquer le même UUID et vanilla tranche arbitrairement au chargement de ce chunk.
- **`/npcpatrol select <uuid>` ne vérifie pas `PathAwareEntity`**, contrairement au clic
  droit. Sélectionner la mauvaise entité construit une patrouille qui affiche `ACTIVE` et ne
  bouge jamais, sans erreur nulle part.
- **Les attentes ne survivent pas à un redémarrage.** `waitingAtWaypoint` est transitoire :
  un NPC en pleine pause recommence sa pause après un redémarrage.

### Performance

- ⚠️ **Le mixin fait un `Files.exists` à chaque suppression d'entité du jeu**, sur le thread
  serveur, pour chaque entité qui n'est pas un NPC de patrouille, c'est-à-dire toutes. Des
  milliers de `stat` disque par seconde sur un serveur Cobblemon chargé. Le correctif est un
  ensemble d'UUID en mémoire.
- **`save()` réécrit tout le fichier de données de façon synchrone sur le thread serveur** à
  chaque modification, y compris chaque point de passage ajouté par clic droit sur un bloc.
- **`backupAllEntities` écrit un fichier NBT compressé par patrouille, à la suite, sur le
  thread serveur**, toutes les 60 secondes.
- **`removeMarkers` parcourt toutes les entités du monde** pour trouver les porte-armures
  tagués.
- **`findEntity` boucle sur tous les mondes chargés** par patrouille et par tick, avec une
  seconde boucle complète en repli quand la dimension ne correspond pas.
- **La boucle de tick tourne même sans joueur connecté**, et une portée de particules de 512
  blocs multiplie les paquets par le nombre de joueurs à portée.

### Justesse et hygiène

- **Aucune écriture atomique et aucune sauvegarde de `cheesepatrol_data.json`.** Un crash en
  pleine écriture perd tous les trajets.
- **`fromJson` ne borne pas.** Des valeurs éditées à la main hors des plages documentées sont
  acceptées et utilisées.
- **`POST /tp` ignore la valeur de retour de `requireWrite`** : une session READ_ONLY reçoit
  un 403 et la téléportation. Défaut partagé avec `KartApi` et `RacesApi` ; à corriger d'une
  passe sur tout le dépôt.
- **`GET /list` lit des entités depuis le thread HTTP** sans passer par le thread serveur.
- **La comptabilité des chunks d'une patrouille peut annuler un `/forceload`
  d'administrateur** sur un chunk qu'une patrouille traverse. Voir
  [garder les NPC en vie](resilience.fr.md#le-suivi-des-chunks-dune-patrouille-peut-annuler-ton-propre-forceload).
- **Les chunks forcés ne sont jamais relâchés à l'arrêt**, par conception, et
  `releaseAllForcedChunks`, la méthode qui le ferait, n'est jamais appelée. Retirer le mod
  sans effacer les patrouilles d'abord les laisse forcés définitivement.
- **Le mixin journalise en `INFO` sans garde `debug`** et sans limitation de débit.
- **Pas de `reload`.** Le fichier de données est lu une fois à `SERVER_STARTING` et les
  éditions à la main sur un serveur en marche sont écrasées.
- **Aucune i18n.** Chaque chaîne destinée aux joueurs est un `Text.literal` anglais, et il
  n'existe aucun répertoire `assets/cheesepatrol/lang/`. La convention du dépôt est
  `Text.translatable` avec `en_us.json` et `fr_fr.json` ; ce mod la précède ou l'ignore, et
  c'est aussi pourquoi la documentation française cite les sorties de commande en anglais
  telles quelles.
- **`getNavDescription()` renvoie du français écrit en dur** (`"Patrouilles NPC"`) alors que
  le reste du dashboard est traduit.
- **Deux commentaires de code contredisent leurs constantes.** Celui du chien de garde de
  calage dit « 2s » là où la constante vaut 100 ticks (5 s) ; celui de `particleYOffset` dit
  « -1.0 to 1.0 » là où le bornage est de ±5.
- **Tout l'état est `static`.** Convenable pour un serveur dédié. Sur le serveur intégré
  d'un client, les tables sont globales d'un chargement de monde à l'autre, et seule la
  remise à zéro par `load()` sauve la situation.
- **Code mort :** `PatrolWand.clearSelectedNpc`, `PatrolWand.sendWandStatus`,
  `PatrolData.getWaypointWaitTicks`, `PatrolData.getLastY`, `PatrolManager.isLoaded` et
  `PatrolManager.releaseAllForcedChunks` ne sont référencés nulle part.
- **Un fichier égaré dans le répertoire du mod :**
  `data/cobblemon/species/generation1/charizard.json`, 322 lignes, une surcharge d'espèce
  Cobblemon posée hors de `src/main/resources` et donc absente du jar. Il n'a rien à voir
  avec les patrouilles et rien ne le lit.
- ⚠️ **Les déclarations de licence se contredisent.** `fabric.mod.json` déclare
  `PolyForm-Noncommercial-1.0.0`, conforme au dépôt, mais `CheesePatrol/LICENSE` est une
  licence MIT attribuée à « CacademyTeam », et `build.gradle` embarque ce fichier dans le
  jar. Le [LICENSE](../../LICENSE) du dépôt est celui qui fait foi ; le fichier propre au mod
  est un vestige à supprimer.

---

← [API dashboard](dashboard.fr.md) · [Dépannage](troubleshooting.fr.md) →
