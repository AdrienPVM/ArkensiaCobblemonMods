[← CheeseStore](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](cosmetics.md)

# Cosmétiques et portefeuilles

Tout ce qu'un joueur peut acheter sans carte bancaire passe par ce sous-système : un
solde de crédits, un catalogue tarifé en crédits, une liste de possession, et un
emplacement équipé par catégorie. Le côté Stripe ne fait que recharger le solde — et il
le fait en exécutant les commandes d'un produit, ce qui pour un pack de monnaie veut
dire `premium give {player} <n>`.

---

## La monnaie

Un solde par joueur, un `long`, stocké par `WalletManager` dans
`config/cheesestore_wallets.json`, indexé par UUID :

```json
{
  "069a79f4-44e9-4726-a5be-fca90e38aaf5": {
    "playerName": "Steve", "balance": 1250, "lastUpdated": 1730000000000
  }
}
```

Son nom d'affichage vient de `currencyName` dans la configuration, par défaut
`crédit`. Le code et les lignes de log l'appellent *fromage* par endroits, parce que
c'est le nom qu'elle portait sur le serveur pour lequel il a été écrit ; les fichiers de
langue l'appellent *crédits*. Le même nombre partout, trois noms.

`WalletManager` garde les soldes dans une `ConcurrentHashMap` et écrit le fichier de
façon **asynchrone** via `CompletableFuture.runAsync` après chaque changement. Cela
tient un changement de solde hors du thread serveur, et cela a une conséquence : un
crash brutal dans les quelques millisecondes qui suivent un octroi de crédits peut
perdre cet octroi. Pour quelques crédits c'est acceptable ; cela vaut la peine d'y
penser avant d'accorder 100 000 crédits à quelqu'un puis de faire `/stop`
immédiatement.

Quatre opérations existent sur un portefeuille : `getBalance`, `add`, `deduct` (qui
refuse et renvoie `false` plutôt que de passer en négatif) et `setBalance` (qui
contraint à zéro). L'éditeur de portefeuilles du dashboard et `/premium set` utilisent
tous deux `setBalance`, donc tous deux **écrasent** au lieu d'ajuster.

---

## Deux catalogues qui portent le même nom

C'est la première chose à bien saisir, parce que la nomenclature est activement
trompeuse. CheeseStore contient **deux** systèmes de cosmétiques sans rapport, aux noms
voisins, et ils coexistent volontairement.

| | Historique | Jouable |
|---|---|---|
| Classe | `com.cheesestore.CosmeticItem` | `com.cheesestore.cosmetic.CosmeticEntry` |
| Gestionnaire | `com.cheesestore.CosmeticManager` | `com.cheesestore.cosmetic.CosmeticManager` |
| Fichier | `config/cheesestore_cosmetics.json` | `config/cheesestore_playcosmetics.json` |
| Routes admin | `/api/v1/store/cosmetics` | `/api/v1/store/playcosmetics` |
| Routes publiques | `/store/api/cosmetics` | `/store/api/playcosmetics` |
| Effet d'un achat | exécute une liste d'`applyCommands` | enregistre la possession |
| Répétable ? | oui — acheter deux fois débite deux fois | non — `409 already_owned` |
| Surface en jeu | `/premium cosmetics` les liste | tout l'arbre `/cosmetics` |
| Images | `config/cheesestore_assets/cosmetics/<id>.png` | aucune ; un identifiant d'item fait l'icône |

Les deux classes de gestionnaire ont **le même nom simple** dans des packages
différents, et c'est pourquoi les sources désignent l'une par
`com.cheesestore.cosmetic.CosmeticManager` en entier partout où elles touchent aux
deux.

Le modèle historique est venu d'abord et c'est un crochet générique « vends n'importe
quoi, exécute des commandes » : son énumération `Type` vaut `HAT`, `TRAIL`, `PARTICLE`,
`ITEM`, `EFFECT`, utilisée seulement comme puces de filtre sur le site, et le vrai
comportement est ce que font les `applyCommands`. Le modèle jouable est venu ensuite,
implémente ses effets lui-même, et c'est celui que le menu en jeu connaît.

Si tu montes une nouvelle boutique, utilise le système jouable et laisse le catalogue
historique vide. Les routes historiques sont conservées parce que le JavaScript de la
boutique en production les consomme encore.

> ⚠️ Le commentaire de classe de `CosmeticEntry` dit que son catalogue vit dans
> `cheesestore_cosmetics.json`. C'est faux — `CosmeticManager` résout
> `cheesestore_playcosmetics.json`. Le commentaire est périmé ; le code a raison.

---

## Le modèle jouable

Trois fichiers, trois préoccupations, et cette séparation est la chose à comprendre :

```text
cheesestore_playcosmetics.json   le catalogue — ce qui existe, ce que ça coûte
cheesestore_ownership.json       uuid → l'ensemble des identifiants que le joueur possède
cheesestore_equipped.json        uuid → catégorie → l'unique identifiant porté là
```

Posséder n'est pas porter. Un joueur peut posséder douze traînées et n'en porter
aucune. Supprimer une entrée du catalogue ne nettoie pas les lignes de possession qui la
référencent — elles deviennent des identifiants orphelins qui ne résolvent plus rien,
inoffensifs mais jamais ramassés. Changer la `category` d'une entrée alors que
quelqu'un la porte le laisse la porter sous l'ancienne clé de catégorie jusqu'à ce
qu'il la retire.

Un `CosmeticEntry` :

```json
{
  "id": "trail_flame",
  "title": "Sillage de Flammes",
  "description": "Une traînée de feu derrière vous.",
  "category": "TRAIL",
  "icon": "minecraft:blaze_powder",
  "price": 400,
  "isNew": false,
  "sortOrder": 0,
  "enabled": true,
  "params": { "particle": "minecraft:flame", "shape": "trail", "density": "3" }
}
```

`id` est à toi de choisir et c'est la clé de possession, donc ne réutilise jamais le
même pour un cosmétique différent. `price` et `sortOrder` sont de simples entiers ;
`price` est contraint à zéro ou plus à la construction comme à la fusion. `icon` doit
être un identifiant d'item vanilla — un identifiant irrésoluble retombe sur
`minecraft:paper` dans l'interface, et un identifiant absent sur
`minecraft:barrier`.

Tout ce qui est propre à une catégorie vit dans `params`, une table plate de chaînes
vers chaînes. C'est ce qui permet à un seul POJO de servir huit comportements, au prix
d'une absence totale de vérification de type : une `density` valant `"banane"` est
acceptée à l'écriture et relue comme la valeur par défaut par `paramInt`.

### Les huit catégories et leurs params

| Catégorie | Params | Notes |
|---|---|---|
| `TRAIL` | `particle`, `shape`, `density` | `shape` ∈ `trail` `halo` `spiral` `orbit` ; `density` un entier |
| `KILL_EFFECT` | `effect`, `sound` (opt.), `particle` (opt.) | `effect` ∈ `firework` `lightning` `explosion` `souls` `totem` `blood` |
| `DEATH_EFFECT` | `effect`, `particle` (opt.), `sound` (opt.) | même vocabulaire pour `effect` |
| `CHAT_TAG` | `tag`, `color` | `tag` est une chaîne de préfixe, par ex. `✦ VIP` |
| `NAME_COLOR` | `color` | une des 16 couleurs de chat vanilla, en minuscules |
| `PET` | `mob`, `petName` (opt.), `baby` (opt.) | `mob` un identifiant d'entité vanilla |
| `MOUNT` | `mob`, `saddle`, `variant` (opt.), `baby` (opt.) | `mob` doit être chevauchable |
| `CAPE` | `texture` | l'identifiant de cape ; voir [les capes](#les-capes-exigent-un-mod-client) |

`GET /api/v1/store/playcosmetics/enums` renvoie des listes d'identifiants
sélectionnées pour chacune de ces catégories, pour que le dashboard puisse proposer des
menus déroulants. Ces listes sont codées en dur dans `StoreApi`, et non lues depuis les
registres, donc une particule ou une créature moddée n'est pas proposée — mais rien ne
t'empêche d'en mettre une dans `params` à la main, et les gestionnaires essaieront de
la résoudre.

### Le catalogue par défaut

Au premier démarrage, sans `cheesestore_playcosmetics.json` présent,
`CosmeticManager.load()` écrit un catalogue semé depuis `DefaultCatalog.build()` :

```text
[CheeseStore] No cosmetic catalogue found — writing default catalogue
[CheeseStore] Default cosmetic catalogue written (99 entries)
```

La composition, telle que `build()` l'écrit réellement : 10 traînées, 8 effets de kill,
8 effets de mort, 16 couleurs de pseudo (une par couleur de chat), 6 tags de chat, 8
familiers, 11 montures et 32 capes (8 capes thématiques plus un bloc « collection » de
24). Le commentaire de classe annonce encore « ~63 entrées », 7 montures et 8 capes ; il
n'est plus à jour. Tous les titres et descriptions y sont **en français** —
ce sont des chaînes littérales dans `DefaultCatalog.java`, et non des clés de
traduction, donc un serveur anglophone doit les réécrire via le dashboard ou éditer le
fichier.

Un tableau JSON vide mais valide dans ce fichier est traité comme une erreur et
repeuplé avec les valeurs par défaut au démarrage suivant, avec un avertissement. Pour
tourner sans aucun cosmétique, désactive les entrées plutôt que de vider le fichier.

---

## Comment chaque catégorie s'applique

Chaque sous-système enregistre ses propres événements Fabric depuis
`CosmeticsModule.init()` et est idempotent. Aucun ne lève d'exception dans le tick
serveur : chaque gestionnaire enveloppe son travail dans un `try`/`catch` sur
`Throwable` et journalise un avertissement, parce qu'un cosmétique qui fait planter la
boucle de tick serait un bug bien plus grave qu'un cosmétique qui cesse silencieusement
de fonctionner.

### Les traînées

`TrailManager` rend sur `END_SERVER_TICK`, mais seulement tous les **2 ticks** — la
constante `TICK_INTERVAL`. Les quantités de particules sont bornées et les positions
mises en cache par joueur. Les particules résolues sont mises en cache par identifiant,
et un identifiant qui ne résout pas est censé être mémorisé comme sentinelle pour que le
gestionnaire ne retente pas la recherche à chaque tick.

> ⚠️ **Cette sentinelle est cassée.** C'est `ParticleTypes.FLAME` lui-même, et un
> identifiant inconnu ou paramétré retombe aussi sur `FLAME` : le cache ne distingue
> donc pas une vraie flamme d'un échec. Le premier rendu d'une telle traînée dessine des
> flammes ; chaque rendu suivant lit la valeur en cache comme « non résolue » et ne
> dessine rien, jusqu'au redémarrage suivant. En pratique : **toute traînée dont la
> `particle` est `minecraft:flame`, absente, inconnue ou paramétrée (`minecraft:dust`…)
> s'affiche une fois puis s'arrête** — `trail_flame` du catalogue par défaut compris.
> Choisis une autre particule pour ces entrées.

### Les effets de kill et de mort

`CombatEffectsManager` expose `handleKill(killer, victim)` et
`handleDeath(victim)`. Pour les morts, il branche lui-même un événement :
`ServerLivingEntityEvents.AFTER_DEATH`, de sorte que le `DEATH_EFFECT` d'un joueur se joue
à n'importe quelle mort, avec un dédoublonnage de 500 ms pour qu'une mort signalée deux
fois ne se joue qu'une fois. Les kills, il ne les branche pas du tout. Ils n'arrivent
que par `CosmeticCombatBridge`, une façade délibérément stable qu'un mod tiers,
`factionwar`, recherche **par son nom et invoque par réflexion** :

```java
com.cheesestore.cosmetic.CosmeticCombatBridge
  public static void onKill(ServerPlayerEntity killer, ServerPlayerEntity victim)
  public static void onDeath(ServerPlayerEntity victim)
```

Renomme cette classe ou change l'une des deux signatures et les effets de kill
s'arrêtent, sans aucune erreur nulle part — l'appelant est de l'autre côté d'une
recherche par réflexion qui échouera simplement. C'est une observation sur la façon
dont les deux mods sont câblés, pas une spécification de `factionwar`, qui ne fait pas
partie de ce dépôt et que je n'ai pas lu. Sans ce mod installé, les effets de kill ne
se déclenchent jamais, à moins qu'autre chose n'appelle la façade ; les effets de mort,
eux, se déclenchent quand même via `AFTER_DEATH`.

### Les tags de chat et les couleurs de pseudo

`NameTagManager.refresh(player)` place le joueur dans une équipe de scoreboard qui lui
est propre, dont la couleur vient de la `NAME_COLOR` équipée et le préfixe du `CHAT_TAG`
équipé ; sans aucun des deux, l'équipe est supprimée. Il est appelé à la connexion, à
l'équipement et au retrait, et quand `revoke` retire un cosmétique équipé.
`cleanup(player)` supprime l'équipe, et s'exécute à la déconnexion.
`/cosmetics grantall` ne le rafraîchit **pas** — accorder n'est pas équiper.

### Les familiers

`PetManager` fait apparaître une créature étiquetée (`cos_pet`, plus
`cos_pet_owner.<uuid>`) qui suit son propriétaire. `refresh(player)` resynchronise un
joueur et `despawnFor` retire un orphelin. Les étiquettes sont le moyen d'identifier un
familier laissé derrière par un crash et de le nettoyer au démarrage suivant.

### Les montures

La plus élaborée des huit, et celle dont les règles se remarquent en jeu.

Équiper une `MOUNT` la sélectionne et rien de plus. La créature n'apparaît qu'avec un
`/mount`, `/mount summon` explicite, ou le bouton d'invocation du menu `/cosmetics`.
Une monture invoquée est invulnérable, silencieuse, persistante et son IA est
désactivée — elle est dirigée par son cavalier et n'a aucune statistique, donc elle ne
peut gagner un combat pour personne.

Elle est rappelée automatiquement quand son propriétaire entre en combat. Deux
détecteurs tournent : l'événement `AFTER_DAMAGE` sur l'attaquant comme sur la victime,
et un sondage de `hurtTime` à chaque tick en filet de sécurité. Après un rappel de
combat, le propriétaire ne peut pas réinvoquer pendant `COOLDOWN_TICKS` = **200 ticks,
environ 10 secondes**, et une tentative dans cette fenêtre répond avec les secondes
restantes.

Les montures sont aussi retirées à la mort, à la déconnexion, au changement de monde et
au retrait du cosmétique — et dès que le propriétaire cesse de la chevaucher (descendre
avec Maj la renvoie ; sans délai de recharge). Ces contrôles, et le sondage de combat,
tournent à chaque tick pour chaque joueur qui a une monture active ; le balayage des
montures laissées par des propriétaires hors ligne tourne tous les 20 ticks
(`RESYNC_INTERVAL`).

### Les capes exigent un mod client

Une `CAPE` est rendue entièrement côté client. Le serveur fait une seule chose :
diffuser la carte complète `uuid → capeId` sur un payload personnalisé
`cheesestore:capes` dès que quelque chose change. La texture elle-même est attendue à
`factionwarhud:textures/entity/cape/<capeId>.png`, au format de cape vanilla 64×32, à
l'intérieur d'un mod client compagnon nommé `factionwarhud`.

Donc : **les capes ne font rien sur un client vanilla.** Les 32 entrées de cape du
catalogue par défaut se vendront, s'équiperont, s'afficheront comme équipées dans le
menu, et personne ne les verra. Si tu ne distribues pas ce mod compagnon, désactive la
catégorie cape avant d'ouvrir la boutique. `factionwarhud` n'est pas dans ce dépôt, et
le chemin de sa texture est lu dans les commentaires de ce mod-ci, non vérifié contre
lui.

---

## Acheter avec des crédits

Trois routes dépensent des crédits, toutes sur la surface publique, toutes avec la même
chaîne de gardes — le détail est dans
[l'API dashboard](dashboard.fr.md#les-achats-en-crédits). Ce qui importe ici, c'est ce
qu'elles font une fois qu'elles ont décidé de te laisser passer :

| Route | Achète | Effet |
|---|---|---|
| `POST /store/api/playcosmetics/:id/buy` | un `CosmeticEntry` | débit, puis `OwnershipManager.grant` |
| `POST /store/api/cosmetics/buy` | un `CosmeticItem` | débit, puis exécution des `applyCommands` |
| `POST /store/api/morpho/buy` | une entrée du catalogue CheeseItems | débit, puis ajout au codex de ce joueur |

Les trois exigent une session web **et** un `/store link` achevé, pour que le mod sache
quel UUID Minecraft dépense. Les trois vérifient le solde deux fois : une fois sur le
thread HTTP pour un refus rapide, puis de nouveau à l'intérieur de
`executeOnServerThread` avant le débit. C'est la seconde vérification qui compte. Deux
requêtes simultanées passent toutes les deux la première ; une seule passe la seconde.

Deux des trois revérifient aussi la possession dans le bloc exécuté sur le thread
serveur, donc une requête doublée ne peut pas octroyer deux fois. `/cosmetics/buy` ne le
fait pas, par conception — les cosmétiques historiques sont des achats répétables. Si
l'un des tiens doit être unique, impose-le dans ses commandes.

L'achat entier tourne sur le thread serveur, ce qui veut dire que les `applyCommands`
y tournent aussi, depuis la console, avec tous les droits. Une commande lente ou
bloquante dans cette liste bloque le tick. Garde-les courtes.

### Les octrois d'administration

`POST /api/v1/store/playcosmetics/grant` et `/cosmetics grant <player> <id>` accordent
tous deux la possession **sans débit et sans contrôle de prix**. C'est ainsi qu'on teste
un cosmétique qu'on vient de créer, et c'est aussi quelque chose que n'importe quelle
session `OPERATOR` peut faire : la route n'est protégée que par
[le garde d'écriture du cœur](dashboard.fr.md#ce-que-le-cœur-applique-réellement), rien
de plus strict.

`/cosmetics grantall <player>` accorde chaque entrée activée. Il n'existe aucun
`revokeall` ; défaire cela veut dire un `revoke` par identifiant.

---

## Ce qui n'existe pas ici

Aucune expiration : la possession est permanente une fois accordée. Aucun échange ni
cadeau entre joueurs. Aucun historique d'achat par cosmétique — une fois accordé, rien
n'enregistre quand ni à quel prix, donc la seule trace d'un achat en crédits est
l'événement WebSocket du dashboard, qui n'est persisté nulle part (et que `/morpho/buy`
n'envoie même pas ; un achat de morpho ne laisse que son entrée de codex CheeseItems,
horodatée). S'il te faut une
piste d'audit des dépenses en crédits, tu devras l'ajouter.

---

← [Le flux de paiement](payments.fr.md) · [API dashboard](dashboard.fr.md) →
