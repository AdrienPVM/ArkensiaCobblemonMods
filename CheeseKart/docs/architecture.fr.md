[← CheeseKart](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](architecture.md)

# Architecture

Quatorze fichiers source, environ 1 380 lignes. Assez petit pour que cette page couvre
tout, ce qui est précisément l'intérêt de l'écrire.

---

## Découpage en packages

```text
com.cheesekart
├── CheeseKartMod.java          entrypoint: wires every event, owns the two singletons
├── CheeseKartConfig.java       loads/saves config/cheesekart.json, holds the presets
├── CheeseKartCommand.java      the /cheesekart Brigadier tree
├── KartPreset.java             a preset: 16 fields, JSON in/out, merge, stock factories
├── KartSession.java            immutable record describing one running kart
├── KartManager.java            all the behaviour — start, stop, per-tick enforcement
├── CobbleShiftProxy.java       soft dependency on CobbleShift, by reflection
├── network
│   ├── KartSyncPayload.java    S2C: "this player is/is not in a kart"
│   └── OpenBoatUtilsProxy.java builds and sends the OBU physics packets
├── client
│   ├── CheeseKartClient.java   client entrypoint: tracks kart players and boat ids
│   └── mixin
│       ├── BoatRenderMixin.java  cancels rendering of kart boats
│       └── DismountMixin.java    blocks Shift-dismount in a locked kart
└── dashboard
    ├── CheeseKartDashboardModule.java  registers the "kart" prefix with CheeseDash
    └── KartApi.java                    the nine REST routes
```

## Qui possède quoi

**`CheeseKartMod`** est le seul singleton, exposé par un `INSTANCE` public statique. Tout
le reste atteint la configuration et le gestionnaire à travers lui, y compris la couche
dashboard et le hook par réflexion de CheeseGuard. Il enregistre sept choses à
l'initialisation : le chargement de la configuration, le gestionnaire, les deux types de
payload, l'arbre de commandes, et les gestionnaires de cycle de vie, de tick, de
déconnexion et de connexion.

**`CheeseKartConfig`** est une `ConcurrentHashMap` de presets, plus le chargement et la
sauvegarde JSON. Elle est concurrente parce que le dashboard modifie les presets depuis
des threads HTTP pendant que le thread serveur les lit. Elle sauvegarde sans attendre :
`putPreset` et `removePreset` écrivent tout le fichier immédiatement, si bien qu'il
n'existe aucun état de « modifications non enregistrées » à perdre.

**`KartManager`** détient le comportement et la table des sessions, elle aussi une
`ConcurrentHashMap`, indexée par UUID de joueur. Une session par joueur, garantie par le
fait que `startKart` arrête d'abord celle qui existe.

**`KartPreset`** est un bean mutable avec trois méthodes JSON qu'il vaut la peine de
distinguer : `fromJson` construit un nouveau preset et laisse les champs absents à leur
valeur par défaut ; `mergeFrom` n'applique que les champs présents sur une instance
existante, ce qui est ce qui fait du `PUT` une fusion ; `toJson` écrit toujours les seize
champs. `id` est délibérément absent de `mergeFrom`, afin qu'une mise à jour ne puisse pas
renommer un preset.

**`KartSession`** est un record, il ne peut donc pas dériver. Il photographie `locked` au
lancement — modifier le champ `locked` d'un preset ne change pas une session déjà en
cours.

## Gestion des threads

Trois sortes de threads touchent ce mod, et les frontières sont là où vivent les bugs.

| Thread | Ce qui y tourne |
|---|---|
| **Serveur (principal)** | l'arbre de commandes, `KartManager.tick()`, toutes les apparitions, montées, descentes et suppressions |
| **HTTP** (CheeseDash) | le traitement des routes de `KartApi`, la lecture et l'écriture des presets |
| **IO Netty** | l'événement de déconnexion d'un joueur |

Les règles que suit le code :

- Tout ce qui touche au monde ou à une entité passe par
  `WebDashboard.executeOnServerThread(server, …)`. Chaque route d'API qui modifie quelque
  chose le fait — `/start`, `/stop`, `/stopall`, et la réapplication à chaud à l'intérieur
  de `PUT /presets/{id}`.
- Les lectures et écritures de presets se font directement sur le thread HTTP, ce qui est
  sûr parce que la table des presets est concurrente et qu'un preset n'est jamais que
  remplacé entièrement, jamais modifié en partie pendant qu'on le lit.
- Le gestionnaire de déconnexion arrive sur un thread réseau : il retire donc la session
  sur place (la table est concurrente) mais planifie le nettoyage du bateau avec
  `server.execute(…)`.

La conséquence pour les appelants de l'API, c'est qu'une route modifiante répond avant
que son effet ait nécessairement eu lieu. `{"started":true}` signifie *planifié*, pas
*réussi* — voir [la remarque sur POST /start](dashboard.fr.md#post-start).

## La boucle par tick

`KartManager.tick()` tourne à chaque tick serveur et constitue la totalité de ce que le
mod impose. Pour chaque session, il tranche entre quatre cas :

| Situation | Action |
|---|---|
| le joueur est hors ligne | retirer la session, jeter le bateau, avertir les clients |
| le joueur ne chevauche rien, la session est `locked` | retrouver le bateau et le remettre en selle ; si le bateau est mort, arrêter la session |
| le joueur ne chevauche rien, la session n'est pas `locked` | arrêter la session — descendre est la façon de terminer un kart non verrouillé |
| le joueur chevauche quelque chose, mais le bateau a disparu ou est mort | arrêter la session |

Il rassemble ce qu'il y a à faire dans deux listes et agit après l'itération, plutôt que
de modifier la table des sessions pendant qu'il la parcourt.

Les bateaux sont recherchés par **identifiant d'entité d'abord, UUID ensuite**.
L'identifiant d'entité est plus rapide et fiable au sein d'une session ; le repli par UUID
couvre le cas où l'entité a été rechargée et a reçu un nouvel identifiant. Le nettoyage
essaie tous les mondes chargés, parce qu'un pilote peut franchir une frontière de
dimension en pleine session.

## Les deux mixins client

Les deux sont réservés au client, déclarés dans `cheesekart.mixins.json` sous `"client"`,
et les deux sont **non fatals** : `require = 0` et `defaultRequire: 0` font qu'une
injection ratée est un mixin ignoré en silence, pas un crash. C'est délibéré : aucun des
deux mixins n'est essentiel, et faire planter un client pour un hook cosmétique serait la
pire des issues.

**`BoatRenderMixin`** annule `BoatEntityRenderer.render` à HEAD quand l'identifiant
d'entité est celui d'un bateau de kart connu. C'est un filet de sécurité : le bateau est
déjà marqué invisible côté serveur. Il existe pour la fenêtre entre le moment où un client
apprend l'existence de l'entité bateau et celui où il apprend l'existence du kart, et pour
les packs de ressources ou les mods qui rendent les bateaux par un autre chemin.

**`DismountMixin`** annule `ClientPlayerEntity.dismountVehicle` à HEAD quand le joueur
local est en kart. C'est ce qui fait que Shift donne l'impression de ne rien faire, plutôt
que celle d'une descente défaite un tick plus tard. Note qu'il vérifie seulement *être en
kart*, pas *être verrouillé* — le client n'est pas informé de l'état verrouillé d'une
session, donc un kart non verrouillé ne peut pas non plus être quitté avec Shift sur un
client qui a CheeseKart installé. Un joueur sur un kart non verrouillé doit être arrêté par
un administrateur, ou descendre d'une autre manière. Bon à savoir si tu comptes sur
`locked: false`.

**`CheeseKartClient`** tient deux collections — l'ensemble des joueurs en kart et une
table joueur → identifiant d'entité du bateau — toutes deux concurrentes, parce que le
gestionnaire de payload et le thread de rendu les lisent tous les deux. Elles ne sont
jamais vidées au changement de monde ; la méthode `clear()` existe mais personne ne
l'appelle, donc des entrées périmées peuvent survivre à un changement de serveur jusqu'à
ce que le prochain paquet de synchronisation les corrige. Sans conséquence en pratique
(une entrée périmée cache un bateau qui n'existe plus), mais c'est un fil qui pend.

## Réseau

Deux canaux, de sens opposés et pour des publics différents.

**`cheesekart:sync`** (S2C) transporte `(playerUuid, active, boatEntityId)` et est diffusé
à **tous** les clients, pas seulement au pilote — les autres joueurs ont besoin de savoir
qu'ils ne doivent pas rendre ce bateau. Il est aussi rejoué pour tout joueur qui se
connecte pendant que des karts tournent, pour qu'un arrivant tardif voie la grille
correctement.

**`openboatutils:settings`** (S2C) transporte les paquets de physique OBU bruts vers le
pilote uniquement. Protocole complet dans
[physique](physics.fr.md#le-protocole-openboatutils). Le canal appartient à OBU quand OBU
est installé côté serveur, et à ce mod quand il ne l'est pas — d'où les deux chemins
d'envoi d'`OpenBoatUtilsProxy`.

Les deux sont protégés par `canSend` : un client qui n'a pas enregistré un canal ne reçoit
jamais un paquet qu'il ne saurait pas décoder.

## Points d'extension

- **Un nouveau réglage de physique.** Ajoute le champ à `KartPreset` (le champ, le
  getter/setter, et les trois méthodes JSON — `toJson`, `fromJson`, `mergeFrom` ; oublier
  `mergeFrom` est l'erreur facile, et rend le champ silencieusement non modifiable par
  `PUT`), puis ajoute son identifiant de paquet OBU et un appel d'envoi dans
  `OpenBoatUtilsProxy.applyPreset`.
- **Une nouvelle route.** Ajoute une branche dans `KartApi.handle` et une méthode de
  traitement. Enveloppe dans `executeOnServerThread` tout ce qui touche au monde, et
  appelle `WebDashboard.requireWrite` en premier si la route modifie quelque chose.
- **Une autre dépendance optionnelle.** `CobbleShiftProxy` est le patron à recopier :
  chercher la classe par son nom, mettre les méthodes en cache, exposer un
  `isAvailable()`, et retenter la recherche si le singleton de l'autre mod n'est pas encore
  prêt.
- **Atteindre ce mod depuis un autre mod.** Fais ce que fait le `CheeseKartHook` de
  CheeseGuard : réfléchir sur `com.cheesekart.CheeseKartMod.INSTANCE`, puis
  `getKartManager()` et `getConfig()`. Les deux sont publics et stables.

## Faiblesses connues

Énoncées sans détour, parce que les découvrir par surprise est pire.

- **La réflexion OBU est le point unique de défaillance.** Le nom de la classe
  `BytePayload` d'OBU est codé en dur. Si OBU la renomme ou la déplace, la recherche
  échoue au démarrage, l'erreur est écrite une fois, et à partir de là les karts se
  conduisent comme des bateaux sans autre avertissement.
- **Les identifiants de paquets ont été obtenus par ingénierie inverse**, pas à partir
  d'une spécification publiée. Un changement de protocole du côté d'OBU dégrade le
  comportement en silence au lieu de produire une erreur.
- **Les presets sont lus sur le disque une seule fois, au démarrage.** Il n'existe aucune
  commande de rechargement. Les modifications à la main de `cheesekart.json` sur un
  serveur en marche sont ignorées, puis écrasées.
- **`DismountMixin` ignore `locked`**, comme décrit plus haut.
- **`CheeseKartClient.clear()` est du code mort** — l'état client n'est jamais remis à
  zéro explicitement.
- **Aucune limitation de débit sur l'écriture des presets.** Chaque `POST`, `PUT` et
  `DELETE` réécrit tout le fichier de configuration de façon synchrone. Très bien à
  vitesse humaine ; un script qui martèle `PUT` pour régler à chaud produit une écriture
  de fichier par requête.
- **Les sessions ne sont pas persistées**, par choix. Un crash avec des karts en cours
  laisse des bateaux invisibles dans le monde, parce que le démontage de l'arrêt n'a
  jamais tourné.

---

← [API dashboard](dashboard.fr.md) · [Dépannage](troubleshooting.fr.md) →
