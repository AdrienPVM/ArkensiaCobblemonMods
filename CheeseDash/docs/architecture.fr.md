[← CheeseDash](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](architecture.md)

# Architecture

Seize fichiers Java, 4 656 lignes, aucun mixin. La complexité intéressante n'est pas dans
le Java — elle est à la frontière de threads entre une requête HTTP et un tick Minecraft,
et dans le frontend, sept fois plus gros que le backend.

---

## Arborescence des packages

```text
com.cheesedash
├── CheeseDashMod.java            entrypoint : découvre les modules, câble les événements, possède les six singletons
├── DashboardModule.java          l'interface publique que les autres mods implémentent (45 L)
├── DashboardRegistry.java        map statique préfixe → module, plus broadcast()      (62 L)
├── DashboardApi.java             dispatcher de /api/v1/*, tous les endpoints du cœur (1 242 L)
├── WebDashboard.java             le serveur HTTP, l'auth, la limitation de débit, les en-têtes, les helpers (554 L)
├── WebSocketServer.java          WS sur port+1, poignée de main d'auth, diffusion par canal (193 L)
├── DashboardConfig.java          config/cheesedash.json, tokens, comptes             (285 L)
├── SessionManager.java           sessions en mémoire, 24 h glissantes                (187 L)
├── AuditLogger.java              journal d'audit persistant, anneau de 10 000 entrées (179 L)
├── AdminAccount.java             un compte ; l'enum Role vit ici                      (59 L)
├── ServerScheduler.java          redémarrages ponctuels et récurrents, compte à rebours, relance (561 L)
├── DiscordWebhookManager.java    23 types d'événements, construction d'embeds, POST asynchrones (693 L)
├── network
│   └── QuickMenuPayload.java     C2S : « exécute cette commande pour moi »            (26 L)
└── client
    ├── CheeseDashClient.java     entrypoint client : une touche                       (50 L)
    ├── QuickMenuConfig.java      config/cheesedash_shortcuts.json                     (79 L)
    └── QuickMenuScreen.java      l'écran de raccourcis                                (183 L)
```

Plus le frontend à la racine du dossier du mod : `index.html`, `preview.html` et
`assets/` — environ 36 000 lignes de HTML, JavaScript et CSS, dont 26 fichiers
`view-*.js`, un par page du dashboard. `processResources` recopie tout cela dans
`src/main/resources/dashboard/` au build, ce qui signifie que **tu modifies les fichiers
de la racine et jamais les copies**. Détails dans [interface web](web-ui.fr.md).

---

## Qui possède quoi

**`CheeseDashMod`** est le seul `ModInitializer`, exposé en public static `INSTANCE`. Il
construit et détient la config, le serveur HTTP, le serveur WebSocket, le journal
d'audit, le planificateur et le gestionnaire Discord, et c'est par lui que `DashboardApi`
atteint le planificateur et les webhooks. Il possède aussi la mesure du TPS — un compteur
de ticks qui divise les ticks par les nanosecondes écoulées toutes les 20 ticks et
plafonne le résultat à 20.0.

**`WebDashboard`** possède le `HttpServer` et tout ce qui se passe avant un handler de
route : l'exécuteur à quatre threads, le middleware d'authentification, la limitation de
débit par IP, les en-têtes de sécurité, le handler de fichiers statiques avec son gzip et
son repli SPA, et les contextes publics de modules. Il héberge aussi les helpers statiques
que les modules appellent, ce qui fait de cette classe à la fois une infrastructure et
une API publique.

**`DashboardApi`** est un unique dispatcher statique de 1 242 lignes : une longue chaîne
de `if (route.equals(…) && method.equals(…))` menant à des handlers privés. Pas de
framework, pas d'annotations, pas de réflexion. C'est répétitif et complètement
transparent, et c'est le compromis assumé ici — l'alternative était une abstraction de
routage que personne n'aurait lue.

**`DashboardRegistry`** est de l'état statique : une `LinkedHashMap` préfixe → module,
si bien que `GET /modules` liste les modules dans l'ordre où Fabric a renvoyé les
entrypoints (la barre latérale, elle, garde l'ordre fixe de `NAV_SECTIONS` dans
`app.js`). Il détient aussi les références au serveur WebSocket et au gestionnaire
Discord, ce qui rend `DashboardRegistry.broadcast(channel, json)` appelable depuis
n'importe quel mod sans avoir d'objet CheeseDash sous la main.

**`DashboardConfig`** est le lecteur/écrivain de `cheesedash.json` plus la résolution de
tokens. `resolveToken` vérifie d'abord le master token, puis chaque compte, à temps
constant par comparaison. Les comptes vivent dans une `ConcurrentHashMap` parce que les
threads HTTP les modifient.

**`SessionManager`** détient les sessions dans une `ConcurrentHashMap`, rien de plus. Une
session capture `username`, `role`, `ip` et des horodatages, et le rôle est **capturé à la
création** — c'est pourquoi rétrograder un compte n'affecte pas ses sessions vivantes.

**`AuditLogger`** garde une `ArrayList` synchronisée d'entrées, la tronque à 10 000, et
écrit toute la liste sur disque après chaque entrée.

**`ServerScheduler`** possède un `ScheduledExecutorService` à deux threads : un future qui
fait tourner un compte à rebours chaque seconde, un autre qui vérifie les planifications
récurrentes toutes les 30 secondes.

**`DiscordWebhookManager`** possède un pool de deux threads pour le HTTP sortant, une
limitation de débit par webhook, et les modèles d'embeds des 23 types d'événements.

---

## Les deux serveurs

**HTTP, port 44311.** Le `com.sun.net.httpserver.HttpServer` du JDK, backlog 64, quatre
threads démons nommés `CheeseDash-HTTP`. Trois sortes de contextes sont enregistrées au
démarrage, et **l'ordre compte** :

1. Le préfixe public de chaque module, s'il en a déclaré un — enregistré *avant* `/api/`
   pour qu'il corresponde en premier.
2. `/api/` — le middleware authentifié, qui appelle ensuite `DashboardApi.handle`.
3. `/` — le handler statique, qui sert `dashboard/*` depuis le classpath et retombe sur
   `index.html` pour tout ce qui est inconnu, afin que le routeur côté client prenne le
   relais.

Quatre threads est un plafond délibéré : un dashboard sert un ou deux administrateurs, et
un pool borné garantit qu'un collecteur fou ne crée pas de threads sans limite. Cela
signifie aussi que quatre requêtes lentes bloquent la cinquième, et c'est la raison pour
laquelle un handler ne doit jamais attendre quelque chose de long.

**WebSocket, port 44312**, par `org.java-websocket:Java-WebSocket:1.5.7`, embarqué dans
le jar via le `include` de Gradle pour qu'aucun mod supplémentaire ne soit nécessaire. Le
port est calculé comme `config.getPort() + 1` et n'a pas de réglage propre. Une connexion
doit envoyer `{"type":"auth","token":…}` dans les cinq secondes, sinon un thread de
nettoyage, qui passe toutes les cinq secondes, la ferme avec le code `4001` (donc au bout
de cinq à dix secondes) ; ensuite elle s'abonne à des canaux et reçoit ce
qui y est diffusé. Détail du protocole dans [WebSocket](websocket.fr.md).

Une asymétrie à connaître : le WebSocket compare le token au **master token uniquement**,
ni aux tokens de compte ni aux identifiants de session. Voir
[authentification et rôles](auth-and-roles.fr.md#le-websocket-naccepte-que-le-master-token).

---

## Threads

| Thread | Nom | Ce qui s'y exécute |
|---|---|---|
| Serveur (principal) | `Server thread` | Minecraft lui-même ; le compteur de TPS ; tout ce qui est dans `executeOnServerThread` ; les sauvegardes de monde avant un redémarrage planifié |
| HTTP | `CheeseDash-HTTP` ×4 | Tous les handlers de routes, tous les handlers de modules, les écritures de config et d'audit déclenchées par une requête |
| WebSocket | ceux de Java-WebSocket | La poignée de main d'auth, la tenue des abonnements, les envois sortants |
| Nettoyage des sessions | `CheeseDash-Session-Cleanup` | Balaie les sessions expirées toutes les 5 minutes |
| Nettoyage WS | `CheeseDash-WS-Cleanup` | Ferme les sockets non authentifiées toutes les 5 secondes |
| Planificateur | `CheeseDash-Scheduler` ×2 | Le tick du compte à rebours et la vérification des récurrences |
| Discord | `CheeseDash-Discord` ×2 | Les POST HTTPS sortants vers Discord |
| Écriture d'audit | `CheeseDash-AuditWrite` | **Un nouveau thread par entrée d'audit**, écrivant tout le fichier |
| Écriture planification / webhook | `CheeseDash-ScheduleSave`, `CheeseDash-WebhookSave` | Un nouveau thread par sauvegarde |

Tous sont des threads démons : aucun ne maintient la JVM en vie après l'arrêt de
Minecraft.

**Le passage de frontière est `WebDashboard.executeOnServerThread(server, task)`**, et
c'est la seule traversée sanctionnée de HTTP vers le jeu. Elle soumet à
`server.execute(...)` et bloque sur un `CompletableFuture` pendant cinq secondes au plus,
donc la réponse HTTP n'est écrite qu'après que le jeu a réellement fait le travail — ou
après le dépassement du délai. La règle que tous les modules suivent : **les mutations
traversent, les lectures de structures immuables ou concurrentes, non.**

Le sens inverse n'exige aucune cérémonie : le code de jeu appelle
`DashboardRegistry.broadcast(...)` directement depuis le thread serveur, et la
bibliothèque WebSocket se charge des envois réels ailleurs.

---

## Persistance

Rien ne vit dans les données du monde ni dans celles des joueurs. Quatre fichiers JSON
dans `config/`, chacun possédé par une classe, chacun réécrit intégralement à chaque
changement — pas d'écriture incrémentale, pas de base de données, pas de migrations.

| Fichier | Écrit par | Quand |
|---|---|---|
| `cheesedash.json` | `DashboardConfig.save()` | De façon synchrone, sur le thread appelant, dès qu'un compte, le nom du joueur administrateur, un dossier de zones ou le master token change. Et une fois au premier démarrage |
| `cheesedash_audit.json` | `AuditLogger` | Après **chaque** entrée d'audit, sur un thread créé pour l'occasion |
| `cheesedash_schedules.json` | `ServerScheduler` | À tout changement de la liste des récurrences, sur un thread créé pour l'occasion |
| `cheesedash_webhooks.json` | `DiscordWebhookManager` | À tout changement de webhook, sur un thread créé pour l'occasion |

Relus uniquement au démarrage. Modifier l'un d'eux sur un serveur en fonctionnement n'a
aucun effet et sera écrasé.

Les sessions sont l'exception délibérée : en mémoire seulement, perdues au redémarrage.

---

## Découverte des modules

Un seul appel, pendant `onInitialize` :

```java
FabricLoader.getInstance()
    .getEntrypoints("cheesedash", DashboardModule.class)
    .forEach(module -> { DashboardRegistry.register(module); … });
```

`cheesedash` est un nom d'entrypoint personnalisé, pas quelque chose que Fabric connaît
lui-même. Tout mod qui le déclare est instancié et enregistré, indexé par son préfixe ;
un préfixe en doublon est refusé avec un avertissement et le second module est écarté.
L'ordre de chargement est celui que Fabric renvoie, et il détermine l'ordre de
`GET /modules`.

Les modules sont ensuite notifiés deux fois dans la vie du serveur, sur `SERVER_STARTED`
et `SERVER_STOPPING`, moments où ils récupèrent et relâchent la référence au
`MinecraftServer`. Les deux notifications s'exécutent sur le thread serveur.
`onServerStart` passe avant le démarrage du serveur HTTP : il ne peut donc pas recevoir
une requête qui le court-circuiterait. `onServerStop` passe *avant* l'arrêt du serveur
HTTP, et non après : pendant ce bref intervalle, une requête peut encore atteindre un
module qui a déjà relâché sa référence au serveur, et c'est à cela que sert sa propre
branche `503`.

Le contrat complet est dans [écrire un module](writing-a-module.fr.md).

---

## Faiblesses connues

Dites franchement, parce que les découvrir par surprise est pire.

**Sécurité**

- **Pas de TLS, et il n'y en aura jamais dans le processus.** La conception suppose un
  pare-feu, un tunnel SSH ou un reverse proxy. Les tokens et les identifiants de session
  passent en clair sur le réseau.
- **La route de login n'est pas soumise à la limitation de débit.** Le limiteur se trouve
  derrière le court-circuit de `/auth/login`, donc il ne bride que les mauvais en-têtes
  `Authorization` sur les autres routes. Une attaque par force brute contre l'endpoint de
  login n'est pas bridée — seulement auditée. Garde les tokens générés et place les
  ports derrière un pare-feu ou un tunnel.
- **Le WebSocket n'accepte que le master token**, ce qui force quiconque a besoin des vues
  en direct à détenir le justificatif de plus grande valeur. Le système de rôles n'atteint
  pas du tout le WebSocket, et le frontend fourni, qui envoie son identifiant de session,
  ne passe jamais la poignée de main.
- **`GET /webhooks/{id}` renvoie l'URL Discord non masquée à n'importe quel rôle
  authentifié**, `READ_ONLY` compris. Pareil pour `GET /server/logs` et tout ce qui se
  trouve dans le log.
- **Régénérer le master token ne révoque aucune session.** La récupération après
  compromission exige un redémarrage.
- **La CSP porte `'unsafe-inline'` et `'unsafe-eval'`**, parce qu'Alpine.js évalue des
  expressions d'attributs. La défense contre le XSS repose entièrement sur l'échappement
  des données par le frontend.
- **Les tokens sont stockés en clair**, pas hachés. Le fichier de config est le secret.

**Robustesse**

- **Un `cheesedash.json` malformé est remplacé silencieusement.** Le parsing des comptes
  partage le `try` extérieur : une seule mauvaise valeur de `role` abandonne la suite de
  la lecture et retombe sur le chemin de premier démarrage — nouveau master token, et le
  compte fautif ainsi que tous ceux qui le suivent perdus. Une virgule en trop est pire :
  rien n'est chargé, et tous les comptes disparaissent. Les chargeurs de webhooks et de planifications
  font l'inverse et ne sautent que la mauvaise entrée ; le chargeur de config aurait dû
  être écrit ainsi.
- **`AuditLogger` crée un thread et réécrit tout le fichier par entrée.** À 10 000
  entrées, cela fait une écriture de plusieurs mégaoctets pour chaque action journalisée,
  et des écrivains concurrents peuvent s'entrelacer. Sous une rafale de mutations, c'est
  la première chose qui fera mal.
- **`GET /server/logs` lit tout le fichier de log** en mémoire pour en renvoyer la fin.
- **`executeOnServerThread` a un délai dur de 5 secondes** et rapporte un dépassement en
  `500`, même si la tâche se termine généralement un instant plus tard. Toute opération
  réellement lente a besoin d'un autre motif.
- **Quatre threads HTTP**, sans limite de file au-delà du backlog de 64. Quatre handlers
  lents bloquent tout.
- **`enabled: false` démarre quand même le serveur WebSocket**, qui occupe `port + 1` et
  sert toujours tout client qui envoie le master token.

**Cohérence de l'API**

- **`/status` renvoie `uptime` comme l'heure epoch courante**, pas comme une durée ;
  `/server/stats` renvoie une vraie durée sous le même nom.
- **Un JSON malformé donne un `500` sur quatre routes** et un `400` sur les autres, selon
  que le handler a pris la peine d'attraper l'erreur de parsing.
- **`PUT /server/schedule/recurring/{id}` ne valide rien** de ce que son équivalent
  `POST` valide : heure, minute et type passent sans contrôle.
- **`DELETE /admin/sessions/{id}` répond `200 {"revoked":false}`** pour une session
  inexistante, là où tout le reste de l'espace de noms admin répondrait `404`.
- **Les préfixes du cœur masquent silencieusement les préfixes de modules.** Un module
  qui s'appellerait `server` ne recevrait jamais les chemins que traite le cœur
  (`/server/logs`, `/server/schedule`…), et un module qui s'appellerait `admin` ne
  recevrait rien du tout.

**Montée en charge**

- **Utiliser un token brut comme justificatif Bearer crée une session par requête.** Un
  script de collecte fait grossir la carte des sessions et le log d'une entrée par appel.
- **Le journal d'audit est plafonné à 10 000 entrées** et tronqué par l'avant. Sur un
  serveur chargé, cela fait des jours, pas des mois. Fais tourner le fichier toi-même si
  tu veux un historique plus long.
- **`ServerScheduler.restartProcess` devine le jar du serveur** en cherchant `-jar` dans
  les arguments du processus courant, puis en cherchant `fabric-server-launch*.jar` ou
  `server*.jar` dans le répertoire de travail. Sur un hôte qui démarre via un script
  d'enrobage ou avec un nom de jar inhabituel, le redémarrage échoue sur
  `Cannot find server jar for restart` après que le monde a déjà été sauvegardé ;
  l'exception est seulement journalisée et l'arrêt a lieu quand même — laissant le serveur
  éteint plutôt que relancé. Teste
  un `RESTART` une fois sur un nouvel hôte avant de lui confier une planification
  récurrente.

---

## Points d'extension

- **Une nouvelle route du cœur.** Ajoute une branche dans `DashboardApi.handle` à côté de
  celles qui s'y rapportent, puis une méthode privée `handleX`. Protège-la d'abord avec
  `requireWrite` ou `requireAdmin`, et ajoute un appel
  `auditLogger.log(session, "domaine.action", detail)` si elle mute quelque chose.
- **Un nouveau module.** Rien à changer dans CheeseDash, et c'est tout l'intérêt. Voir
  [écrire un module](writing-a-module.fr.md).
- **Un nouveau canal WebSocket.** Diffuse simplement vers un nouveau nom ; les canaux sont
  des chaînes et l'abonnement est libre. Apprends au frontend à s'y abonner.
- **Un nouveau type d'événement Discord.** Ajoute une constante à
  `DiscordWebhookManager.DiscordEventType` avec un label, une description et une couleur,
  puis traite-la dans le constructeur d'embeds. Elle apparaît gratuitement dans
  `GET /webhooks/event-types`.
- **Une nouvelle page de dashboard.** Ajoute `assets/view-yours.js` avec une fonction
  `async buildYours(app)` renvoyant du HTML, enregistre le script dans `index.html`, et
  ajoute l'entrée de navigation. Couvert dans [interface web](web-ui.fr.md).

---

← [Menu rapide](quick-menu.fr.md) · [Dépannage](troubleshooting.fr.md) →
