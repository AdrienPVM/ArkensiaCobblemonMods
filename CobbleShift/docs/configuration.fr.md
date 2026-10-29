[← CobbleShift](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](configuration.md)

# Configuration

CobbleShift lit un seul fichier de configuration global :

```text
config/cobbleshift.json
```

Le chemin vient de `FabricLoader.getInstance().getConfigDir().resolve("cobbleshift.json")`
dans `CobbleShiftConfig` : il suit donc un dossier de configuration déplacé. Le fichier est
créé au premier démarrage, toutes les clés à leur valeur par défaut, s'il n'existe pas
encore. Le client ne le lit ni ne l'écrit jamais.

Un second fichier, `config/cobbleshift_npc_transforms.json`, n'est **pas** de la
configuration : c'est l'état sauvegardé des transformations de PNJ, réécrit par le mod à
chaque modification d'un PNJ. Son format est décrit dans
[les transformations de PNJ](npc-transforms.fr.md#le-fichier-de-sauvegarde). Ne le modifie
pas à la main pendant que le serveur tourne.

---

## Lecture et écriture du fichier

La configuration est lue **une seule fois**, dans `CobbleShiftMod.onInitialize()`, avant le
démarrage du serveur. Il n'existe aucune commande de rechargement. Une modification faite à
la main prend effet au redémarrage suivant, pas avant.

Le fichier est réécrit dans trois situations, et chacune sérialise les valeurs **en
mémoire**, pas celles du fichier :

| Écrivain | Clés modifiées |
|---|---|
| premier démarrage, fichier absent | les seize, à leurs valeurs par défaut |
| `/cshift ability global enable\|disable` | `abilityGlobalEnabled` |
| `POST /api/v1/shift/ability/global` | `abilityGlobalEnabled` |
| `PUT /api/v1/shift/config` | n'importe laquelle des quatorze clés qu'il accepte |

⚠️ La conséquence piège facilement : si tu modifies `cobbleshift.json` pendant que le serveur
tourne et que quelqu'un bascule ensuite les capacités par la commande ou par le dashboard,
ta modification est écrasée par les valeurs chargées au démarrage. Modifie le fichier
serveur arrêté, ou passe par `PUT /config`.

Le chargement se fait clé par clé. Une clé absente du fichier garde sa valeur par défaut,
ce qui permet à un ancien fichier de récupérer les clés ajoutées depuis. Une clé présente
avec le mauvais type (une chaîne là où un nombre est attendu) lève une exception, et le
`catch` journalise :

```text
[CobbleShift] Failed to load config, using defaults: <message>
```

Ce message exagère ce qui s'est passé. Les clés lues **avant** la mauvaise gardent la
valeur du fichier ; la mauvaise clé et toutes celles qui suivent gardent leur valeur par
défaut. L'ordre est celui des tableaux ci-dessous. Le fichier cassé n'est pas réécrit à ce
moment-là, mais la sauvegarde suivante, quelle qu'elle soit, le remplace par les valeurs en
mémoire.

L'analyseur est `JsonParser.parseString` de Gson, qui fonctionne en mode tolérant et
accepte les commentaires `//`. C'est ce qui rend l'exemple annoté en bas de page chargeable
tel quel. Les commentaires ne survivent pas à la sauvegarde suivante.

## Les clés

Les seize clés sont au premier niveau d'un unique objet JSON. Les tableaux suivent l'ordre
de `CobbleShiftConfig.load()`.

### Transformations

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `autoRevertOnDeath` | booléen | `true` | **rien**, voir [les clés qui ne font rien](#clés-qui-ne-font-rien) |
| `transformScaleEnabled` | booléen | `true` | applique le modificateur `GENERIC_SCALE` à la transformation, pour que la hitbox du joueur corresponde à l'espèce |
| `transformSpeedEnabled` | booléen | `true` | applique le modificateur `GENERIC_MOVEMENT_SPEED` tiré de la Vitesse de base |
| `transformHealthEnabled` | booléen | `true` | applique le modificateur `GENERIC_MAX_HEALTH` tiré des PV de base, puis soigne jusqu'au nouveau maximum |
| `transformMaxDurationEnabled` | booléen | `false` | annule automatiquement une transformation quand elle atteint `transformMaxDurationSeconds` |
| `transformMaxDurationSeconds` | int | `300` | la durée limite, en secondes de temps réel |
| `transformCooldownEnabled` | booléen | `false` | bloque une nouvelle transformation pendant `transformCooldownSeconds` après une annulation |
| `transformCooldownSeconds` | int | `60` | le cooldown, compté à partir de l'annulation |

Les trois interrupteurs `transform*Enabled` sont lus au moment de la transformation. En
couper un ne retire pas le modificateur d'un joueur déjà transformé ; cela empêche la
transformation suivante de l'appliquer. Une annulation retire les trois modificateurs
quelle que soit la valeur des interrupteurs.

Deux interactions avec les commandes de réglage méritent d'être connues :

- Avec `transformScaleEnabled` à `false`, `/cshift scale` réussit toujours et envoie
  toujours la nouvelle taille aux clients : le **modèle** grandit, mais `setPlayerScale`
  saute l'attribut et la **hitbox** ne bouge pas.
- Avec `transformSpeedEnabled` à `false`, `/cshift speed` affiche son message de succès et
  ne change rien : `setPlayerSpeed` enregistre la valeur et saute le modificateur.

La durée et le cooldown sont tous deux mesurés avec `System.currentTimeMillis()`, pas en
ticks : un serveur qui rame ne les allonge pas. Les deux sont décrits du point de vue du
joueur dans
[le modèle de transformation](transforms.fr.md#durée-cooldown-et-ce-qui-met-fin-à-une-transformation).

### Attaques

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `attackDamageMultiplier` | float | `1.0` | multiplie les dégâts de projectile propres à l'espèce |
| `attackCooldownMultiplier` | float | `1.0` | multiplie le cooldown d'attaque propre à l'espèce, avant le plancher de 5 ticks |

`AttackHandler` lit les deux à chaque clic : un changement par `PUT /config` s'applique dès
l'attaque suivante. Un multiplicateur de cooldown inférieur à `1.0` accélère les attaques
côté serveur ; le plancher de 5 ticks (un quart de seconde) s'applique après la
multiplication, quelle que soit la valeur.

⚠️ Le client ne connaît pas le cooldown du serveur. `AttackInputMixin` refuse tout clic
tant que la barre de cooldown du HUD se remplit, et
`AbilityHudRenderer.triggerAttackCooldown()` remplit toujours cette barre en **30 ticks**
fixes. Deux conséquences :

- Un vrai client ne peut jamais attaquer plus d'une fois toutes les 1,5 seconde. Baisser
  `attackCooldownMultiplier` jusqu'à faire passer le cooldown serveur sous 30 ticks ne
  change rien de perceptible pour le joueur.
- Quand le cooldown serveur dépasse 30 ticks (les cooldowns de base vont déjà de 25 à 40,
  et un multiplicateur supérieur à `1.0` les allonge), la barre affiche « prêt » trop tôt.
  Le clic suivant est envoyé, ignoré par le serveur, et relance la barre de 30 ticks. Le
  joueur voit une attaque perdue.

Aucune clé ne désactive les attaques globalement. Elles se désactivent transformation par
transformation, avec le jeton `noattacks` de `/cshift transform`.

### Capacités passives

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `abilityAutoMode` | booléen | `true` | la capacité suit le premier Pokémon de l'équipe du joueur |
| `abilityGlobalEnabled` | booléen | `false` | interrupteur général de tout le sous-système des capacités |
| `abilityTickInterval` | int | `100` | la fréquence, en ticks serveur, à laquelle les capacités sont réévaluées et les effets réappliqués |
| `abilityEffectDuration` | int | `160` | la durée, en ticks, de chaque effet accordé à un rafraîchissement |

`abilityGlobalEnabled` est désactivé par défaut : une installation neuve n'accorde aucun
effet passif à personne tant qu'un admin ne l'a pas activé.

Avec `abilityAutoMode` à `false`, la boucle de rafraîchissement ne lit plus les équipes.
Seules les capacités forcées par `/cshift ability <player> <pokemon>` continuent d'être
rafraîchies, et quand aucune capacité n'est active la boucle sort aussitôt. Une capacité
automatique déjà active au moment où tu as coupé le mode reste en place et continue d'être
rafraîchie, jusqu'à ce que les capacités soient coupées globalement, que le joueur soit
exclu, qu'un forçage la remplace, ou que le serveur redémarre.

`abilityTickInterval` et `abilityEffectDuration` n'ont de sens qu'ensemble. Chaque
rafraîchissement accorde des effets qui durent `abilityEffectDuration` ticks, et le suivant
arrive `abilityTickInterval` ticks plus tard. La durée doit rester **supérieure** à
l'intervalle, sinon chaque bonus s'interrompt pendant `intervalle − durée` ticks entre deux
rafraîchissements. Les valeurs par défaut gardent une marge de 60 ticks.

Un comportement vanilla à prendre en compte : le client fait clignoter la Vision nocturne
pendant ses 200 derniers ticks. La durée par défaut de 160 ticks tient entièrement dans
cette fenêtre, si bien que la Vision nocturne du type `dark` clignote en permanence. Monte
`abilityEffectDuration` au-dessus de 200, en la gardant supérieure à l'intervalle, si tu
comptes dessus. C'est une observation du rendu vanilla, pas quelque chose que CobbleShift
contrôle.

Chaque effet est décrit dans [les capacités passives](abilities.fr.md).

### Hot Potato

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `hotPotatoEnabled` | booléen | `true` | **rien** |
| `hotPotatoPassRange` | double | `3.0` | **rien** |

Les deux sont expliquées dans [les clés qui ne font rien](#clés-qui-ne-font-rien).

## Plages et bornes

Chaque clé numérique a un setter qui borne sa valeur. **Les bornes ne s'appliquent qu'à
travers les setters**, et le seul appelant des setters numériques est
`PUT /api/v1/shift/config`. `load()` affecte directement la valeur brute du fichier : un
fichier modifié à la main contourne toutes les bornes ci-dessous.

| Clé | Borne du setter | Ce que fait une valeur hors plage dans le fichier |
|---|---|---|
| `abilityTickInterval` | `>= 20` | `0` lève une `ArithmeticException` (`tickCounter % 0`) dans le gestionnaire de tick serveur, à chaque tick ; rien ne l'intercepte. Une valeur négative se comporte comme sa valeur absolue |
| `abilityEffectDuration` | `>= 40` | `0` ou moins accorde des effets qui expirent aussitôt : les capacités semblent ne rien faire |
| `attackDamageMultiplier` | `[0.1, 5.0]` | `0` rend les projectiles inoffensifs ; leur effet de statut s'applique toujours |
| `attackCooldownMultiplier` | `[0.1, 5.0]` | `0` ou moins tombe sur le plancher de 5 ticks |
| `transformMaxDurationSeconds` | `>= 10` | `0` ou moins annule chaque transformation au tick qui suit son début, quand la limite est activée |
| `transformCooldownSeconds` | `>= 5` | `0` ou moins désactive le cooldown en pratique |
| `hotPotatoPassRange` | `>= 1.0` | sans objet, la clé n'est jamais lue |

⚠️ Ne mets jamais `0` dans `abilityTickInterval`. La boucle exécute `tickCounter % interval`
tout en haut de `AbilityManager.tick()`, avant de vérifier l'interrupteur global :
l'exception se produit même capacités désactivées.

Les mêmes bornes s'appliquent quand le dashboard envoie une valeur : `PUT /config` avec
`"abilityTickInterval": 5` enregistre `20`, et la réponse ne le dit pas. Relis la
configuration avec `GET /config` pour voir ce qui a réellement été retenu.

Les valeurs de `/cshift scale`, `/cshift speed`, `scalenpc` et `speednpc` ne sont pas des
clés de configuration. Leur propre plage `[0.1, 10.0]` est imposée par Brigadier puis à
nouveau par les gestionnaires ; voir
[les commandes](commands.fr.md#cshift-scale-et-cshift-speed).

## Clés qui ne font rien

Trois des seize clés sont chargées, sauvegardées et renvoyées par `GET /config`, et ne
changent rien en jeu.

| Clé | Pourquoi elle n'a aucun effet |
|---|---|
| `autoRevertOnDeath` | son seul lecteur est `TransformManager.onPlayerDeath()`, et rien n'appelle cette méthode : aucun événement de mort n'est enregistré, aucun mixin n'intercepte la mort. Un joueur transformé qui meurt reste transformé |
| `hotPotatoEnabled` | aucun code ne la lit. Ce mod ne contient aucun jeu Hot Potato |
| `hotPotatoPassRange` | idem. Son setter existe et n'est jamais appelé |

`autoRevertOnDeath` est la plus trompeuse, parce que son nom et sa valeur par défaut
(`true`) promettent un comportement, et que le dashboard permet de la basculer. La faire
fonctionner demande d'enregistrer dans `CobbleShiftMod.onInitialize()` un événement de mort
qui appelle `onPlayerDeath()` ; en attendant, annule la transformation des joueurs morts à
la main, ou depuis l'événement qui les a tués.

Les deux clés Hot Potato sont des restes d'un mini-jeu décrit dans une ancienne
documentation et absent des sources. `PUT /config` ne les accepte même pas : le
gestionnaire n'a aucune branche pour elles, et les envoyer est ignoré sans un mot. Elles
restent dans le fichier uniquement parce que `toJson()` les écrit encore.

## Exemple de fichier

Le fichier complet, chaque clé à sa valeur par défaut et un commentaire par ligne. Il se
charge tel quel grâce à l'analyseur tolérant, et le serveur le réécrit sans les
commentaires à la sauvegarde suivante.

```json
{
  // Ne fait rien : aucun crochet de mort n'appelle le code qui la lit.
  "autoRevertOnDeath": true,
  // La capacité suit le premier emplacement de l'équipe. Désactivé = seules les capacités forcées se rafraîchissent.
  "abilityAutoMode": true,
  // Interrupteur général des capacités passives. Désactivé sur une installation neuve.
  "abilityGlobalEnabled": false,
  // Ticks entre deux rafraîchissements. Jamais 0. Le dashboard impose 20 au minimum.
  "abilityTickInterval": 100,
  // Durée de chaque effet en ticks. À garder au-dessus de abilityTickInterval, et au-dessus de 200 pour la Vision nocturne.
  "abilityEffectDuration": 160,
  // Ne fait rien : aucun jeu Hot Potato n'existe dans le code.
  "hotPotatoEnabled": true,
  // Ne fait rien, même raison.
  "hotPotatoPassRange": 3.0,
  // Redimensionne la hitbox du joueur à la taille de l'espèce (0,15x à 2,5x).
  "transformScaleEnabled": true,
  // Tire la vitesse de déplacement de la Vitesse de base.
  "transformSpeedEnabled": true,
  // Tire la vie maximale des PV de base.
  "transformHealthEnabled": true,
  // Multiplicateur de dégâts des projectiles. Le dashboard borne à [0.1, 5.0].
  "attackDamageMultiplier": 1.0,
  // Multiplicateur du cooldown d'attaque. Plus bas = plus rapide. Plancher de 5 ticks après multiplication.
  "attackCooldownMultiplier": 1.0,
  // Annule automatiquement les transformations après transformMaxDurationSeconds.
  "transformMaxDurationEnabled": false,
  // Secondes de temps réel. Le dashboard impose 10 au minimum.
  "transformMaxDurationSeconds": 300,
  // Bloque la retransformation un moment après une annulation. Changer d'espèce saute la vérification.
  "transformCooldownEnabled": false,
  // Secondes, comptées à partir de l'annulation. Le dashboard impose 5 au minimum.
  "transformCooldownSeconds": 60
}
```

Un serveur qui organise des événements chronométrés, où une transformation doit se
terminer d'elle-même et où les joueurs ne doivent pas repasser aussitôt en Pokémon,
changerait quatre valeurs :

```json
{
  "transformMaxDurationEnabled": true,
  "transformMaxDurationSeconds": 600,
  "transformCooldownEnabled": true,
  "transformCooldownSeconds": 120
}
```

Envoie cet objet comme corps de `PUT /api/v1/shift/config` avec une session `SUPER_ADMIN` :
il s'applique immédiatement, bornes comprises, et il est sauvegardé sur disque. Écrit à la
main dans le fichier, les clés omises gardent leur valeur par défaut et le changement
attend un redémarrage.

---

← [Commandes](commands.fr.md) · [Transformations](transforms.fr.md) →
