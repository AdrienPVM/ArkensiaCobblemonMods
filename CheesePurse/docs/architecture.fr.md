[← CheesePurse](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](architecture.md)

# Architecture

Cinq fichiers source, 257 lignes. Le mod est assez petit pour que cette page couvre
chaque classe et garde encore de la place pour ce qui ne va pas.

---

## Découpage en packages

```text
com.roicheese.numismaticheesepurse
├── NumismaticCheesePurse.java        entrypoint main : récepteur de paquet + boucle de tick
├── NumismaticCheesePurseClient.java  entrypoint client : détient un booléen, rien d'autre
├── network
│   └── ToggleAutoCollectPayload.java record C2S portant un unique booléen
├── client
│   └── AutoCollectButton.java        le ButtonWidget de 12×12 et son infobulle
└── mixin
    └── InventoryScreenMixin.java     ajoute le bouton dans InventoryScreen.init
```

Les deux entrypoints sont déclarés dans `fabric.mod.json`, `main` et `client`. Il n'y a
pas d'entrypoint `server` ni de package dashboard — CheesePurse n'enregistre rien auprès
de CheeseDash et ne publie aucun `DashboardModule`.

## Qui possède quoi

**`NumismaticCheesePurse`** constitue tout le côté serveur. Il détient l'unique élément
d'état serveur, `public static final Set<UUID> AUTO_COLLECT_PLAYERS`, un simple
`HashSet`. `onInitialize` fait trois choses : enregistrer `ToggleAutoCollectPayload` sur
`PayloadTypeRegistry.playC2S()`, enregistrer le récepteur global qui modifie l'ensemble,
et enregistrer le gestionnaire `END_SERVER_TICK` qui pilote le balayage. Il détient
aussi `tickCounter`, un champ d'instance, et la constante `SCAN_INTERVAL_TICKS`.

`collectCoinsForPlayer` est `public static` : un autre mod peut donc l'appeler pour
forcer un dépôt sur un joueur. C'est la seule surface d'extension exploitable qu'expose
le mod, et elle est fortuite plutôt que conçue.

**`NumismaticCheesePurseClient`** détient `public static boolean autoCollectEnabled` et
rien d'autre. Son `onInitializeClient` est vide, avec un commentaire qui explique
pourquoi : le type de payload C2S est enregistré par l'entrypoint `main`, qui tourne des
deux côtés physiques, et l'enregistrer deux fois fait planter le client au démarrage. Ce
commentaire porte du sens — quelqu'un finira par regarder cette méthode vide et vouloir
la remplir.

**`ToggleAutoCollectPayload`** est un record d'un seul booléen, avec un `PacketCodec`
écrit à la main qui écrit et lit un unique octet. Son identifiant est
`numismatic-cheesepurse:toggle_auto_collect`. Il n'existe aucun équivalent S2C, ce qui
est à la racine de la désynchronisation décrite plus bas.

**`AutoCollectButton`** étend `ButtonWidget`. L'action de pression inverse
`NumismaticCheesePurseClient.autoCollectEnabled`, rafraîchit l'infobulle, puis exécute le
`Runnable` que lui passe le mixin — c'est lui qui envoie le paquet. Note l'ordre :
l'état local change d'abord, le paquet part ensuite ; l'apparence du bouton n'attend
donc jamais le serveur et n'est jamais corrigée par lui.

La classe passe un fournisseur d'infobulle à `super(...)` **et** appelle `setTooltip`
dans `refreshTooltipOn`. La forme fournisseur est évaluée par le chemin de narration
vanilla, l'infobulle explicite est ce que voit le joueur au survol ; avoir les deux est
redondant mais sans conséquence.

**`InventoryScreenMixin`** s'injecte au `TAIL` de `InventoryScreen.init`, calcule la
position du bouton à partir de la taille vanilla de l'interface, et appelle
`addDrawableChild`. Comme il tourne dans `init`, le bouton est reconstruit à chaque
ouverture d'écran et à chaque redimensionnement de fenêtre ; l'état qu'il affiche étant
le booléen statique, rien n'est perdu d'une reconstruction à l'autre.

## L'état, et ce qui est écrit sur disque

| État | Écrit sur disque ? |
|---|---|
| `AUTO_COLLECT_PLAYERS` | **non** |
| `autoCollectEnabled` | **non** |
| Solde de la bourse | oui, par le `CurrencyComponent` Cardinal Components de Numismatic |

Le mod n'écrit jamais aucun fichier qui lui soit propre. Il n'a ni configuration, ni
dossier de données, ni couche de persistance. Le seul effet durable qu'il a sur un monde
est l'argent qu'il déplace vers le composant de Numismatic, que Numismatic sauvegarde
dans les données du joueur avec `RespawnCopyStrategy.ALWAYS_COPY` — un solde déposé
survit donc à la mort, aux changements de dimension et aux redémarrages.

⚠️ Un commentaire dans `NumismaticCheesePurse` affirme que « le client conserve son
propre état dans un fichier de configuration, de sorte qu'il sait toujours quoi afficher
sans demander au serveur ». **C'est faux.**
`NumismaticCheesePurseClient.autoCollectEnabled` est un champ statique en mémoire, sans
aucune sauvegarde ni lecture nulle part dans le code source. Le commentaire décrit une
intention jamais implémentée, et l'ancien README la répétait comme un fait. Crois le
champ, pas le commentaire.

## Threading

Deux threads touchent ce mod, et la frontière est plus simple que dans la plupart des
mods de ce dépôt, parce qu'il n'y a aucune couche HTTP.

| Thread | Ce qui y tourne |
|---|---|
| **Serveur (principal)** | le gestionnaire `END_SERVER_TICK`, `collectCoinsForPlayer`, et le récepteur de payload |
| **Rendu client** | `AutoCollectButton.renderWidget` et l'injection `init` du mixin |

Le récepteur de payload mérite une note. Le
`ServerPlayNetworking.registerGlobalReceiver` de Fabric pour les payloads de jeu exécute
le gestionnaire sur le **thread serveur**, pas sur le thread d'E/S Netty : Fabric le
planifie. C'est ce qui rend un simple `HashSet` sûr ici — l'unique écrivain (le
récepteur) et l'unique lecteur (le gestionnaire de tick) sont sur le même thread et ne
tournent jamais en concurrence.

Cette sûreté est fortuite plutôt que garantie. Rien dans la classe ne documente
l'exigence, et le fait que `collectCoinsForPlayer` soit `public static` signifie qu'un
appelant extérieur sur un autre thread pourrait la briser. Un
`ConcurrentHashMap.newKeySet()` ne coûterait rien et supprimerait la question.

Il n'y a aucun passage explicite d'un thread à l'autre, parce qu'il n'y a rien à passer :
le côté client ne touche jamais au monde, et le côté serveur ne touche jamais à une
interface.

## Le mixin

Un seul mixin, déclaré sous `"client"` dans `numismaticheesepurse.mixins.json`. Le
tableau `"mixins"` — celui du côté commun — est vide.

| | |
|---|---|
| Classe | `InventoryScreenMixin` |
| Cible | `net.minecraft.client.gui.screen.ingame.InventoryScreen` |
| Méthode | `init`, injection en `@At("TAIL")` |
| Pourquoi | `InventoryScreen` n'offre aucune API pour ajouter un widget ; la seule façon de poser un bouton dans l'inventaire survie est d'étendre son initialisation. |

Le mixin étend `AbstractInventoryScreen<PlayerScreenHandler>` pour que `this.width`,
`this.height` et `addDrawableChild` se résolvent à la compilation. C'est le motif usuel
pour se greffer sur un écran.

`defaultRequire` vaut `1` et la configuration est `"required": true` : une injection
ratée est donc un **plantage franc côté client**, et non un bouton silencieusement
absent. C'est une différence délibérée avec les autres mods de ce dépôt, dont plusieurs
utilisent `require = 0` sur les mixins cosmétiques. Ici, le mixin est le seul accès à la
fonctionnalité : échouer bruyamment se défend — mais cela veut dire qu'une mise à jour
de Minecraft qui déplace `InventoryScreen.init` couche le client au lieu de dégrader.

La méthode injectée s'appelle `autocoinpurse$injectToggleButton`. Le préfixe
`autocoinpurse$` est un reliquat de l'ancien nom du mod et ne correspond plus à rien ;
ce n'est qu'un préfixe d'unicité, donc sans conséquence fonctionnelle, mais c'est le
genre de détail qui fait douter un lecteur d'être dans le bon fichier.

## Comment Numismatic Overhaul est atteint

**Par import direct, pas par réflexion.** `NumismaticCheesePurse` importe
`com.glisco.numismaticoverhaul.ModComponents` et
`com.glisco.numismaticoverhaul.currency.CurrencyHelper` et les appelle comme n'importe
quelle autre classe. `build.gradle` les déclare en `modCompileOnly` : Numismatic n'est
donc que sur le classpath de compilation.

Ce choix a des conséquences qu'il vaut mieux énoncer, parce que les autres mods de ce
dépôt font majoritairement l'inverse :

- **Aucune dégradation en douceur.** `fabric.mod.json` liste `numismatic-overhaul` dans
  `depends` : Fabric refuse de se lancer sans, plutôt que de laisser le mod se charger
  et échouer plus tard. C'est le bon appariement — un import dur appelle une dépendance
  dure.
- **La casse survient au chargement de classe.** Si une version future de Numismatic
  renomme `CurrencyHelper`, déplace `ModComponents`, ou change la signature de
  `getMoneyInInventory` ou de `modify`, le résultat est un `NoClassDefFoundError` ou un
  `NoSuchMethodError` dès que `NumismaticCheesePurse` est touché — à l'initialisation du
  serveur, en pratique. Bruyant et précoce, ce qui vaut mieux que silencieux, mais c'est
  un plantage.
- **La version est épinglée sur un seul fichier Modrinth.**
  `maven.modrinth:numismatic-overhaul:DPXG6kTJ` correspond à la 0.3.4. La contrainte
  d'exécution dans `depends` est `"*"` : un serveur peut donc faire tourner la version
  Numismatic qu'il veut face à un jar compilé pour 0.3.4. Cet écart n'est sûr qu'aussi
  longtemps que les deux méthodes ci-dessus gardent leur forme.

Compare avec `CobbleShiftProxy` dans
[CheeseKart](../../CheeseKart/docs/architecture.fr.md), qui atteint un mod optionnel par
réflexion et sait répondre `isAvailable()`. Ce motif est le bon pour une dépendance
optionnelle, et le mauvais ici : le mod ne sert à rien sans Numismatic, il n'y a donc
rien vers quoi dégrader.

## Points d'extension

- **Forcer un dépôt depuis un autre mod.** Appelle
  `NumismaticCheesePurse.collectCoinsForPlayer(serverPlayer)` sur le thread serveur. La
  méthode est publique, statique et indépendante de l'interrupteur — elle dépose que le
  joueur se soit inscrit ou non.
- **Lire ou modifier qui est inscrit.** `AUTO_COLLECT_PLAYERS` est public et mutable.
  Ajouter un UUID de l'extérieur fonctionne et prend effet au balayage suivant, avec la
  réserve que le client du joueur continuera d'afficher le gris, puisque rien ne le
  détrompe.
- **Ajouter une commande.** Il n'existe aucune classe de commande ni enregistrement
  Brigadier. Un `/cheesepurse toggle <player>` ferait une trentaine de lignes, et aurait
  besoin d'un paquet S2C pour garder le bouton honnête.
- **Se brancher sur le ramassage plutôt que scruter.** Le vrai remède au
  [piège du retrait de bourse](auto-collect.fr.md#le-piège-du-retrait-de-bourse). Se
  greffer sur `ItemEntity.onPlayerCollision`, ou sur `PlayerInventory.insertStack` avec
  un filtre sur `CurrencyItem`, rendrait le dépôt réellement piloté par le ramassage et
  supprimerait la minuterie.
- **Persister l'interrupteur.** Voir ci-dessous.

## Faiblesses connues

Énoncées franchement, parce que les découvrir par surprise est pire.

- **L'interrupteur n'est pas persisté, et les deux côtés peuvent diverger.**
  `AUTO_COLLECT_PLAYERS` est reconstruit vide à chaque démarrage de serveur.
  `autoCollectEnabled` est reconstruit à `false` à chaque démarrage de client. Comme il
  n'y a aucune synchronisation S2C et que le client ne renvoie jamais son état à la
  connexion, tout redémarrage d'un côté sans l'autre produit un décalage. Le pire cas
  est un redémarrage client alors que le serveur a gardé son état : le bouton est gris et
  les pièces continuent de disparaître. L'ancien README affirmait que le client « se
  resynchronise à la reconnexion » ; aucun code ne le fait.
- **Les joueurs ne sont jamais retirés de l'ensemble à la déconnexion.**
  `AUTO_COLLECT_PLAYERS` ne fait que croître pendant la durée de vie du serveur, borné
  par le nombre de joueurs distincts ayant activé l'interrupteur. Cela fait quelques
  dizaines d'octets par joueur et c'est effacé par le redémarrage dont ce mod a de toute
  façon besoin : une fuite de principe plus que de fait. Le balayage ignore d'ailleurs
  les joueurs hors ligne, puisqu'il n'itère que sur la liste des connectés — ces entrées
  ne travaillent donc pas.
- **L'ensemble est un `HashSet` non concurrent.** Sûr aujourd'hui uniquement parce que
  Fabric exécute le récepteur de payload sur le thread serveur. Rien ne l'impose ni ne le
  documente.
- **Un commentaire et l'ancien README décrivent tous deux une persistance qui n'existe
  pas.** Voir l'encart dans [l'état](#létat-et-ce-qui-est-écrit-sur-disque).
- **Aucun paquet S2C, du tout.** Le serveur ne peut rien dire à un client, ce qui bloque
  les corrections évidentes de chacun des points ci-dessus.
- **Le mixin est en `required: true`.** Une mise à jour de Minecraft qui change
  `InventoryScreen.init` fait planter le client au lieu de masquer le bouton.
- **Des résidus de nommage de l'ancien projet.** La méthode injectée s'appelle
  `autocoinpurse$injectToggleButton`, et le commentaire dans
  `NumismaticCheesePurseClient` parle de « AutoCoinPurse (entrypoint main) », une classe
  qui n'existe plus. Le package mort `com.example.autocoinpurse` que mentionnait l'ancien
  README a **disparu** : il n'est pas dans l'arborescence source et rien ne le compile.
  Seules ces deux traces de nommage subsistent.
- **Un import inutilisé.** `NumismaticCheesePurse` importe `net.minecraft.util.Identifier`
  sans jamais s'en servir ; l'identifiant est construit dans `ToggleAutoCollectPayload`.
  Sans conséquence, et signe que personne n'a passé de coup de balai.
- **Deux commentaires en français dans une base de code par ailleurs anglaise**, dans
  `NumismaticCheesePurseClient` et `AutoCollectButton`. À normaliser si tu touches à ces
  fichiers.
- **La position du bouton suppose une interface d'inventaire de taille vanilla**,
  176×166, codée en dur dans le mixin. Un pack de ressources qui la redimensionne place
  le bouton de travers.
- **Il n'existe aucun moyen de désactiver la fonctionnalité à l'échelle du serveur**
  autrement qu'en retirant le jar. Sur un serveur qui décide en milieu de saison que le
  dépôt automatique casse son économie, cela signifie un redémarrage avec un dossier
  `mods/` modifié.

---

← [Configuration](configuration.fr.md) · [Dépannage](troubleshooting.fr.md) →
