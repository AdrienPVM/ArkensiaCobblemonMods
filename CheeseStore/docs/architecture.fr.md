[← CheeseStore](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](architecture.md)

# Architecture

32 fichiers Java, environ 8 100 lignes, aucun mixin. Le mod est un `ModInitializer`
Fabric plus un module CheeseDash, et presque tout ce qu'il fait est de l'état dans des
fichiers JSON, gardé par un gestionnaire HTTP et une poignée d'écouteurs d'événements
Fabric.

---

## Packages

```text
com.cheesestore
├── CheeseStoreMod            ModInitializer — câble tout, détient les singletons
├── CheeseStoreConfig         cheesestore.json : clés Stripe, réglages boutique, produits, galerie
├── StripeClient              REST Stripe sur le HttpClient du JDK ; vérification HMAC du webhook
├── StoreProduct              un produit vendable (prix en centimes + commandes de livraison)
├── GalleryItem               une entrée de vitrine non vendable
├── PendingDelivery           une commande d'achat : commandes résolues + PENDING/DELIVERED/FAILED
├── DeliveryManager           la file des commandes, sa persistance, et l'exécution des commandes
├── WalletManager             solde de crédits par UUID, persistance asynchrone
├── CosmeticItem              cosmétique web historique (tarifé en crédits, exécute des commandes)
├── CosmeticManager           catalogue cosmétique historique + son stock d'images
├── PremiumCommand            /premium et /balance
├── StoreCommand              /store link <code>
├── auth
│   ├── AccountLinker         codes d'appariement, liaisons session → UUID Minecraft
│   └── GoogleOAuth           le cookie store_session et les routes /oauth/*
├── cosmetic                  le système de cosmétiques jouables (16 fichiers, ~2 800 lignes)
│   ├── CosmeticsModule       un init() unique qui câble tout le sous-système
│   ├── CosmeticEntry         le modèle partagé : catégorie + une table de params
│   ├── CosmeticManager       catalogue, semé depuis DefaultCatalog au premier lancement
│   ├── DefaultCatalog        les 63 entrées semées
│   ├── OwnershipManager      uuid → identifiants possédés
│   ├── EquipManager          uuid → catégorie → l'unique identifiant équipé
│   ├── TrailManager          traînées de particules, tous les 2 ticks
│   ├── CombatEffectsManager  effets de kill et de mort
│   ├── CosmeticCombatBridge  point d'entrée réflexif pour le mod factionwar
│   ├── NameTagManager        tags de chat et couleurs de pseudo
│   ├── PetManager            créatures qui suivent
│   ├── MountManager          créatures cosmétiques chevauchables, rappel en combat
│   ├── CosmeticsCommands     /cosmetics et son menu sgui
│   └── MountCommand          /mount
├── net
│   ├── CapePayload           le payload personnalisé cheesestore:capes
│   └── CapeNetworking        diffuse la carte uuid → capeId
└── dashboard
    ├── CheeseStoreDashboardModule   le DashboardModule : préfixe « store », préfixe public « store »
    └── StoreApi                     1 791 lignes : toutes les routes admin et publiques
```

`store/` à la racine du mod n'est pas du Java. C'est la boutique — HTML, CSS, JS,
polices et images — copiée dans le jar à `resources/store/` par un bloc
`from(...) { into "store" }` dans `build.gradle`, et servie par CheeseDash sous le
préfixe public du module.

---

## Qui détient quel état

Chaque gestionnaire est un singleton initialisé depuis
`CheeseStoreMod.onInitialize()`, et chacun détient exactement un fichier sous
`config/`. Il n'y a ni base de données ni transaction partagée : une écriture touche un
fichier.

| Classe | Fichier | Déclencheur d'écriture | Écrit sur |
|---|---|---|---|
| `CheeseStoreConfig` | `cheesestore.json` | tout changement de produit, galerie ou réglage | le thread appelant, `synchronized` |
| `DeliveryManager` | `cheesestore_deliveries.json` | commande créée, livrée ou en échec | le thread appelant, `synchronized` |
| `WalletManager` | `cheesestore_wallets.json` | tout changement de solde | **un thread du pool `CompletableFuture`** |
| `CosmeticManager` (historique) | `cheesestore_cosmetics.json` | changement de catalogue | le thread appelant |
| `cosmetic.CosmeticManager` | `cheesestore_playcosmetics.json` | changement de catalogue | le thread appelant |
| `OwnershipManager` | `cheesestore_ownership.json` | octroi ou révocation | le thread appelant |
| `EquipManager` | `cheesestore_equipped.json` | équipement ou retrait | le thread appelant |
| `AccountLinker` | `cheesestore_oauth_links.json` | liaison achevée ou supprimée | le thread appelant |

Les écritures sont des réécritures du fichier entier via `Files.writeString`, pas des
ajouts et pas des opérations atomiques — aucun fichier temporaire suivi d'un renommage
nulle part. Un crash pendant une écriture peut laisser un fichier tronqué. Chaque
chargeur attrape son propre échec d'analyse et le journalise plutôt que de lever une
exception, donc un fichier corrompu dégrade en *vide* plutôt que d'empêcher le
démarrage : un `cheesestore_wallets.json` tronqué signifie que **tous les soldes se
lisent à zéro**, silencieusement, avec une seule ligne `Failed to load wallets` dans le
log. Sauvegarde ces fichiers.

`DeliveryManager` se plafonne lui-même à `MAX_DELIVERIES = 5000`, en retirant les
entrées les plus anciennes en tête de liste à la sauvegarde. Au-delà, ton historique de
commandes devient lacunaire. Le plafond est une constante de la classe, pas un réglage.

`CheeseStoreConfig` réécrit le fichier **entier** à chaque sauvegarde, produits et
galerie compris. Une édition à la main faite pendant que le serveur tourne est donc
perdue au prochain changement venu du dashboard.

---

## Threading

Trois sortes de threads touchent ce mod, et les points de bascule sont ce qu'il faut
regarder quand quelque chose se comporte bizarrement.

**Le thread serveur** exécute les commandes, les écouteurs d'événements Fabric, et tout
le travail par tick des gestionnaires de cosmétiques. `TrailManager`, `PetManager` et
`MountManager` tournent tous sur `END_SERVER_TICK` ; la traînée est limitée à un tick
sur 2 et la réévaluation équipement/orphelin des montures à un tick sur N, tandis que
le sondage de combat de la monture tourne à chaque tick.

**Les threads HTTP de CheeseDash** exécutent `StoreApi.handle` et
`StoreApi.handlePublic`. Ils lisent la configuration, les catalogues et les
portefeuilles directement — tous en `ConcurrentHashMap` ou `CopyOnWriteArrayList`, donc
une lecture est sûre — et ils confient tout ce qui touche au monde Minecraft avec
`WebDashboard.executeOnServerThread(server, () -> {...})`, qui bloque le thread HTTP
jusqu'à ce que le thread serveur ait exécuté le lambda.

Quatre endroits font cette bascule, et chacun a sa raison :

| Endroit | Pourquoi il doit être sur le thread serveur |
|---|---|
| `POST /orders/:id/deliver` | exécute des commandes |
| `PUT /wallets/:uuid` | envoie un message au joueur |
| `/cosmetics/buy`, `/morpho/buy`, `/playcosmetics/:id/buy` | débit atomique, plus des commandes ou un octroi |
| `processPayment` → `deliverPending` | exécute des commandes — via `server.execute`, sans attendre |

Les routes d'achat sont les plus intéressantes. Chacune vérifie le solde sur le thread
HTTP pour un refus peu coûteux, puis le vérifie **de nouveau** dans le lambda, parce que
deux requêtes concurrentes passent toutes les deux la première vérification. La seconde,
exécutée sur l'unique thread serveur, les sérialise. Les commentaires des sources
appellent cela la fenêtre TOCTOU ; ils ont raison, et cette double vérification est la
seule chose qui sépare une boutique fréquentée d'un double débit.

Note l'asymétrie dans la façon dont `processPayment` dispatche : il utilise
`server.execute(...)`, qui met en file et revient, plutôt que
`executeOnServerThread`, qui attend. C'est le bon choix pour un webhook — Stripe veut un
`200` rapide et réessaiera s'il ne l'obtient pas — mais cela veut dire que la réponse
HTTP ne dit rien de savoir si la livraison a fonctionné.

**Un thread du pool commun `CompletableFuture`** écrit
`cheesestore_wallets.json`. `WalletManager.saveAsync()` est la seule persistance
asynchrone du mod. L'avantage est qu'un changement de solde ne bloque jamais un tick ;
l'inconvénient est une fenêtre de quelques millisecondes où un crédit accordé est en
mémoire mais pas sur le disque.

Rien ici ne tient un verrou à travers une frontière de thread, et rien ne rappelle
depuis le thread serveur vers un thread HTTP : il n'existe donc aucun chemin
d'interblocage que je puisse trouver.

---

## Routage des requêtes

CheeseDash découvre le module par l'entrypoint `cheesedash` et lit deux préfixes
dessus :

```text
getPrefix()       = "store"  →  /api/v1/store/*   → handleRequest  → StoreApi.handle
getPublicPrefix() = "store"  →  /store/api/*      → handlePublicRequest → StoreApi.handlePublic
                                /store/           → la boutique depuis resources/store/
```

CheeseDash authentifie avant d'appeler `handleRequest` et n'authentifie pas avant
`handlePublicRequest`. Cette seule différence constitue toute la frontière de sécurité
de ce mod. Les deux gestionnaires répondent `503 CheeseStore not ready` si le module
n'a pas encore de référence au serveur, ce que tu obtiens pendant le démarrage.

À l'intérieur des deux gestionnaires, le dispatch est une chaîne plate de
`if (subRoute.equals(...) && method.equals(...))` avec un `404` en bas et un seul
`try`/`catch (Exception)` qui enveloppe tout, renvoyant
`500 Internal server error` et journalisant la cause. Il n'y a pas de routeur, pas
d'annotation, pas d'extraction de paramètre de chemin au-delà de l'arithmétique sur
`substring`. Pour une trentaine de routes, c'est lisible ; c'est aussi pourquoi une
route ajoutée au mauvais endroit peut être masquée par une correspondance de préfixe
antérieure.

Les identifiants de chemin passent par un seul garde, `isValidId`, qui rejette `..`,
`/`, `\`, `%` et tout ce qui fait 128 caractères ou plus. C'est ce qui empêche
`GET /products/<id>/image` de lire un fichier arbitraire, puisque l'identifiant est
concaténé dans un chemin.

---

## La liaison de compte

`GoogleOAuth` porte mal son nom : le flux Google a été retiré, et `/oauth/start` et
`/oauth/callback` sont des bouchons qui redirigent vers `/store/`. Ce qui reste est un
schéma d'appariement.

1. Le navigateur atteint `/store/api/oauth/me`. S'il n'a pas de cookie
   `store_session`, un cookie est frappé depuis `AccountLinker.randomSessionId()` et
   posé avec `HttpOnly; Path=/; SameSite=Lax; Max-Age=14 jours`.
2. Le navigateur demande `/store/api/oauth/link-code`.
   `AccountLinker.issueCode` renvoie un code lié à cet identifiant de session, valable
   `PENDING_TTL_MS` = **5 minutes**. La route est limitée à 12 requêtes par minute et
   par IP.
3. Le joueur lance `/store link <code>` en jeu. `completeLink` apparie le code, écrit un
   `Link` de `{sessionId, minecraftUuid, minecraftName, createdAt}` dans
   `cheesestore_oauth_links.json`, et autorise **5 tentatives par 60 secondes** par
   joueur avant de répondre `RATE_LIMITED`.
4. À partir de là, `currentSession(exchange)` lit le cookie, trouve le `Link`, et
   chaque route de crédits sait quel UUID dépense.

Ce que cela prouve est étroit et mérite d'être dit clairement : *le détenteur de cette
session de navigateur avait aussi un client Minecraft connecté sur ce serveur*. Ce
n'est pas une identité authentifiée. Le cookie n'a pas d'attribut `Secure`, il n'est
donc pas marqué comme réservé à HTTPS, même derrière un proxy TLS. Il n'existe aucune
déconnexion globale, aucune liste de sessions, et aucune expiration sur le `Link`
lui-même — une fois lié, une session reste liée jusqu'à `/oauth/logout`.

`Session.email` porte un nom trompeur : il contient l'identifiant de session. Le champ
est conservé sous son ancien nom, comme l'indique son propre commentaire, pour
compatibilité avec le chemin d'achat des morphos.

---

## Couplage inter-mods

**CheeseDash** — une dépendance dure, déclarée dans `fabric.mod.json` et résolue à la
compilation par un glob `fileTree` sur
`../CheeseDash/build/libs/cheesedash-*.jar`. CheeseStore n'a aucun serveur HTTP propre,
donc sans CheeseDash il n'y a ni boutique, ni endpoint de webhook, ni surface
d'administration. Il emprunte aussi les aides de CheeseDash :
`WebDashboard.respond`, `readBody`, `executeOnServerThread`,
`servePngWithRevalidation`, et `DashboardRegistry.broadcast`.

**CheeseItems** — également une dépendance dure, et le couplage est plus serré qu'il
n'y paraît. `StoreApi` importe `com.cheeseitems.morpho.MorphoCatalog`, `MorphoEntry`,
`MorphoCodex`, `MorphoCodexEntry`, `MorphoThumbnail` et `MorphoManager`, et appelle
`CheeseItemsMod.INSTANCE.getMorphoCodex()` directement — sans réflexion, sans interface
intermédiaire. Un changement de signature sur l'un d'eux casse la compilation de
CheeseStore.

**sgui** (Patbox) — embarqué dans le jar avec `include`, donc aucune installation
séparée n'est nécessaire. Il fait tourner le menu coffre de `/cosmetics`, et chaque
appel vers lui est enveloppé pour qu'un échec retombe sur la liste texte au lieu de
provoquer une erreur.

**factionwar** — pas une dépendance déclarée et absent de ce dépôt. Il recherche
`com.cheesestore.cosmetic.CosmeticCombatBridge` **par son nom** et invoque `onKill` et
`onDeath` par réflexion. Renomme cette classe ou change une signature et les effets de
kill et de mort s'arrêtent sans aucune erreur d'aucun côté. C'est une observation sur la
façon dont les deux sont câblés, tirée des commentaires de ce mod ; je n'ai pas lu
l'autre mod.

**factionwarhud** — absent d'ici également. Les capes sont rendues côté client depuis
`factionwarhud:textures/entity/cape/<capeId>.png` ; le serveur ne fait que diffuser la
carte `uuid → capeId` sur le payload `cheesestore:capes`. Sans ce mod client, les capes
se vendent, s'équipent, et personne ne les voit.

---

## Mixins

Il n'y en a aucun. Pas de `cheesestore.mixins.json`, pas de clé `mixins` dans
`fabric.mod.json`. Tout passe par les événements Fabric et des API publiques, et c'est
pourquoi ce mod survit aux correctifs de Minecraft mieux que la plupart de ses frères.

`fabric.mod.json` déclare `"environment": "*"` et un seul entrypoint `main` — aucun
entrypoint `client`, même si `net/CapeNetworking` enregistre un payload S2C.
Enregistrer le type de payload depuis l'initialiseur commun fonctionne côté serveur ;
le client qui le rend est `factionwarhud`, pas ce mod.

---

## Points d'extension

**Un nouveau comportement de produit** ne demande aucun code. Les produits livrent par
des commandes de console, donc tout ce que tu peux exprimer en commande est déjà
vendable.

**Une nouvelle catégorie de cosmétique** est l'endroit où la conception invite à
l'extension. Ajoute une constante à `CosmeticEntry.Category`, un gestionnaire avec un
`init()` statique idempotent qui enregistre ses propres événements Fabric, une ligne
dans `CosmeticsModule.init()`, une branche dans
`CosmeticsCommands.applyVisuals` s'il a besoin d'un rafraîchissement à l'équipement, et
des entrées dans `prettyCategory` et `categoryIcon` pour l'interface. Puis ajoute la
catégorie au tableau `categories` de `StoreApi.buildEnumsJson()` pour que le dashboard
la propose. Six points de contact, tous petits, aucun caché.

**Une nouvelle route publique** va dans `StoreApi.handlePublic`. Si elle dépense quoi
que ce soit, recopie la chaîne de gardes de `handlePlayCosmeticBuy` à l'identique —
même origine, limitation de débit, session, liaison, double vérification du solde —
plutôt que d'en écrire une nouvelle.

**Une nouvelle route d'administration** va dans `StoreApi.handle`, et devrait commencer
par `if (!WebDashboard.requireWrite(exchange)) return;` même si aucune route existante
ne le fait. Voir ci-dessous.

---

## Faiblesses connues

Énoncées sans détour, parce que celui qui maintiendra ce mod ensuite les rencontrera
toutes.

**Aucun contrôle de rôle sur la surface d'administration.** `StoreApi.handle`
n'appelle jamais `requireWrite` ni `requireAdmin` — les chaînes n'apparaissent pas du
tout dans le mod — donc chaque route admin mutante accepte n'importe quelle session
CheeseDash authentifiée, `READ_ONLY` comprise. Cela inclut fixer des soldes de
portefeuille, réécrire des commandes de livraison, et écraser le secret Stripe et le
secret du webhook. Le correctif fait une ligne par gestionnaire ; il n'a pas été
appliqué ici parce qu'il change le comportement pour quiconque fait déjà tourner des
comptes non SUPER_ADMIN. Décrit en entier dans
[l'API dashboard](dashboard.fr.md#les-gardes-de-rôle-manquants).

**L'UUID d'un acheteur par carte n'est pas vérifié.**
`POST /store/api/checkout` accepte `playerUUID` et `playerName` depuis le corps de la
requête et les prend pour argent comptant. Les routes de crédits n'ont pas ce problème —
elles résolvent l'UUID depuis une session liée — mais le parcours carte, si.

**Aucune validation PNG, aucune limite de taille sur les envois.**
`saveBase64Image` décode le corps en base64 et l'écrit dans `<id>.png` sans vérifier
qu'il s'agit d'une image et sans limite de longueur. Un gros corps est un pic mémoire et
une écriture disque ; un non-image est servi aux navigateurs avec un type de contenu
PNG. N'importe quelle session authentifiée peut le faire.

**Écritures du fichier entier, non atomiques.** Aucun fichier temporaire suivi d'un
renommage. Un crash en pleine écriture peut tronquer un fichier, et chaque chargeur
dégrade un fichier corrompu en vide plutôt que d'échouer bruyamment. Le pire cas est
`cheesestore_wallets.json` : tous les soldes deviennent silencieusement zéro.

**L'historique des commandes est plafonné et lacunaire.**
`MAX_DELIVERIES = 5000`, les plus anciennes jetées. Pour une boutique qui a encaissé de
vrais paiements, le registre de référence est celui de Stripe, et ce fichier devrait
être traité comme un cache de commodité plutôt que comme de la comptabilité.

**Les commandes `FAILED` sont invisibles si tu ne les cherches pas.** Aucun événement
WebSocket, aucun crochet Discord, aucun réessai. Une commande payée dont les commandes
ont échoué attend dans `GET /orders` que quelqu'un la remarque. Le compteur est dans
`GET /stats`, sous `failedOrders`.

**Le succès d'une livraison veut dire « la commande n'a pas levé d'exception ».** Une
commande qui ne fait rien en silence est enregistrée `DELIVERED`.

**Deux systèmes de cosmétiques dont les classes ont le même nom simple.**
`CosmeticItem` / `CosmeticManager` dans le package racine, `CosmeticEntry` /
`cosmetic.CosmeticManager` dans le sous-package. Celui de la racine est historique,
conservé parce que le JavaScript de la boutique en production consomme encore
`/store/api/cosmetics`. Chaque endroit qui touche aux deux doit en écrire un en entier.
C'est une dette de nommage, pas un bug, et les fusionner casserait la boutique.

**Commentaire périmé sur `CosmeticEntry`.** Son javadoc nomme
`config/cheesestore_cosmetics.json` comme fichier de catalogue. Le gestionnaire résout
`cheesestore_playcosmetics.json`. Fais confiance au code.

**Configuration morte.** `googleClientId`, `googleClientSecret` et
`googleRedirectUri` sont chargées, sauvegardées, et lues par rien. `oauthJwtSecret`
s'autogénère et ne signe rien. `GET /store/api/config` rapporte `oauthEnabled: true`
comme un littéral codé en dur.

**Français codé en dur dans le sous-système cosmétique.** `CosmeticsCommands`,
`MountCommand`, `MountManager` et tout `DefaultCatalog` utilisent `Text.literal` avec
des chaînes françaises plutôt que des clés de traduction, alors que le reste du mod
utilise `Text.translatable` avec de vrais fichiers `en_us` et `fr_fr`. Un serveur
anglophone reçoit des messages cosmétiques en français et 63 titres de catalogue en
français.

**`GET /store/api/players` est un déversement de registre non authentifié.** Tous les
pseudos et UUID de `usercache.json` plus la liste des connectés, sans pagination. Le
sélecteur de joueur de la boutique en a besoin. Rien d'autre.

**La page de conditions de la boutique est celle d'un seul serveur.**
`store/cgv.html` a été écrit pour une juridiction et un hébergement précis. Il est livré
comme exemple de forme, pas comme quelque chose à publier.

---

← [API dashboard](dashboard.fr.md) · [Dépannage](troubleshooting.fr.md) →
