[← CheeseDash](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](auth-and-roles.md)

# Authentification et rôles

Tout ce qui suit sort de trois classes : `WebDashboard` (le middleware),
`SessionManager` (les sessions) et `AdminAccount` (les rôles). Lis cette page avant de
donner un token à quelqu'un, et lis la dernière section avant d'ouvrir un port.

---

## Le flux de login

```text
POST /api/v1/auth/login   {"token": "<master token ou token de compte>"}
   │
   ├─ token non reconnu ──→ 401 {"error":"Invalid token"}  + audit "auth.login_failed"
   │
   └─ reconnu ──→ session créée ──→ 200 {sessionId, username, role, expiresIn}
                                        │
                   chaque requête ensuite :  Authorization: Bearer <sessionId>
```

`POST /api/v1/auth/login` est la seule route sous `/api/` que le middleware laisse
passer sans justificatif. Tout le reste exige l'en-tête :

```http
Authorization: Bearer ses_9mJ…
```

Les champs de la réponse sont exactement ceux-ci :

| Champ | Signification |
|---|---|
| `sessionId` | `ses_` suivi de 43 caractères Base64 URL-safe. C'est le justificatif à partir de maintenant |
| `username` | `admin` pour le master token, sinon le nom du compte |
| `role` | `READ_ONLY`, `OPERATOR` ou `SUPER_ADMIN` |
| `expiresIn` | Millisecondes avant expiration — `86400000` sur une session neuve |

### Un token brut fonctionne aussi comme justificatif Bearer

Le middleware essaie d'abord la valeur du Bearer comme identifiant de session ; si ça
échoue, il l'essaie comme master token ou token de compte, et en cas de succès **crée une
session à la volée** puis continue. Donc ceci fonctionne :

```bash
curl -H "Authorization: Bearer EXAMPLE-TOKEN-do-not-use-mWqZ4rKxv2NbSvQ1pX" \
  http://localhost:44311/api/v1/status
```

Pratique pour un script, et c'est la raison pour laquelle tu peux interroger l'API sans
te connecter d'abord. Mais cela a un coût réel : **chaque requête de ce type crée une
session neuve**, puisque le token brut ne correspond jamais à un identifiant de session
existant. Un script qui interroge une fois par seconde fait grossir la carte des sessions
d'une entrée par seconde et écrit une ligne `Session created for 'admin'` chaque fois.
Ces sessions expirent 24 heures après leur dernier usage, donc la carte se vide bien,
mais un collecteur qui tourne longtemps poussera `GET /admin/sessions` à plusieurs
milliers d'entrées. Pour tout ce qui est répété, connecte-toi une fois et réutilise
l'identifiant de session.

---

## Sessions

| Propriété | Valeur |
|---|---|
| Durée de vie | 24 heures |
| Expiration | **Glissante** — chaque validation réussie remet le compteur à zéro |
| Stockage | En mémoire, `ConcurrentHashMap`. Jamais écrit sur disque |
| Survit à un redémarrage | Non, volontairement. Se reconnecter ne coûte rien |
| Nettoyage | Un thread démon balaie les sessions expirées toutes les 5 minutes ; une session expirée est aussi retirée dès qu'elle est utilisée |

Trois routes agissent dessus :

- `GET /api/v1/auth/session` — la session courante, plus `activeSessions`, le décompte
  global.
- `POST /api/v1/auth/logout` — révoque la session courante. N'importe quel rôle.
- `GET /api/v1/admin/sessions` et `DELETE /api/v1/admin/sessions/{id}` — lister et
  révoquer celles des autres, `SUPER_ADMIN` uniquement.

Les sessions sont aussi révoquées en masse quand le token d'un compte est régénéré ou que
le compte est supprimé. Régénérer le master token ne révoque rien, ce qui vaut la peine
d'être su si tu le régénères justement parce que tu le crois fuité : redémarre le serveur
pour en être sûr.

Changer le rôle d'un compte (`PUT /admin/accounts/{username}`) ne révoque rien non plus.
Le rôle est copié dans la session au moment où elle est créée : une session ouverte avant
une rétrogradation garde son ancien rôle jusqu'à son expiration ou sa révocation. Après
avoir rétrogradé quelqu'un, révoque ses sessions depuis `GET /admin/sessions`, ou
régénère son token, ce qui le fait pour toi.

---

## Ce que peut faire chaque rôle

Trois rôles, comparés par ordinal : `READ_ONLY` (0) < `OPERATOR` (1) < `SUPER_ADMIN` (2).
Un contrôle signifie « au moins ce rôle », donc `SUPER_ADMIN` passe toutes les barrières.

| | READ_ONLY | OPERATOR | SUPER_ADMIN |
|---|---|---|---|
| Lire n'importe quelle route du cœur (`/status`, `/modules`, `/server/stats`, `/server/logs`, `/server/mods`) | oui | oui | oui |
| Lire n'importe quelle route de module (`GET /api/v1/<prefix>/…`) | oui | oui | oui |
| Lire le journal d'audit | oui | oui | oui |
| Lire la liste des webhooks et le planificateur | oui | oui | oui |
| Écrire sur une route de module (`POST`/`PUT`/`DELETE`) | **non** | oui | oui |
| Définir `adminPlayerName`, gérer les dossiers de zones | **non** | oui | oui |
| `POST /server/command` | **non** | **non** | oui |
| Créer, modifier, supprimer des comptes ; régénérer leurs tokens | **non** | **non** | oui |
| Lister et révoquer les sessions des autres | **non** | **non** | oui |
| Créer, modifier, supprimer, tester des webhooks | **non** | **non** | oui |
| Planifier ou annuler un redémarrage, un arrêt, une maintenance | **non** | **non** | oui |
| Régénérer le master token | **non** | **non** | oui |

En dessous, trois helpers font tout le travail, et ce sont ceux qu'appelle l'auteur d'un
module :

| Helper | Rôle minimum | En cas d'échec |
|---|---|---|
| `WebDashboard.requireWrite(exchange)` | `OPERATOR` | `403 {"error":"Insufficient permissions — requires OPERATOR"}` |
| `WebDashboard.requireAdmin(exchange)` | `SUPER_ADMIN` | `403 {"error":"Insufficient permissions — requires SUPER_ADMIN"}` |
| `WebDashboard.requireRole(exchange, role)` | celui que tu passes | même forme, en nommant ce rôle |

Chacun renvoie un booléen et a déjà écrit la réponse quand il renvoie `false` ; le motif
d'appel est donc `if (!requireWrite(exchange)) return;`.

### Où la frontière d'écriture est réellement appliquée

Pour les routes de modules, ce n'est pas le module qui l'applique. `DashboardApi` garde
toute requête non-`GET` avant que le module ne la voie :

```java
DashboardModule module = DashboardRegistry.getModule(prefix);
if (module != null) {
    // Enforce write permissions for non-GET requests on modules
    if (!method.equals("GET") && !WebDashboard.requireWrite(exchange)) {
        return;
    }
    module.handleRequest(exchange, subRoute);
```

Un compte `READ_ONLY` ne peut donc écrire sur aucune route de module, que ce module
appelle ou non un garde de son côté. Un appel à `requireWrite` dans un module est de la
défense en profondeur — utile pour la clarté de l'intention, mais redondant, et ce n'est
pas la frontière elle-même.

Ce qui découle du fait que la frontière vit dans le cœur et non dans le module :

- **Aucune route de module ne peut exiger `SUPER_ADMIN`.** Le garde central ne connaît que
  `requireWrite` : le rôle maximal atteignable sur un module est donc `OPERATOR`. Une route
  qui mériterait `SUPER_ADMIN` — réécrire un fichier de configuration en entier, déplacer
  de l'argent réel — doit appeler `requireAdmin` elle-même.
- **Une route mutante exposée en `GET` ne serait pas gardée du tout.** Le garde central ne
  regarde que la méthode HTTP, rien d'autre.
- **Une seule modification de ce répartiteur ferait tomber tous ces contrôles d'un coup**,
  sans qu'aucun module ne s'en aperçoive.

### Deux failles à connaître

- **`GET /webhooks/{id}` renvoie l'URL Discord en entier, à n'importe quel rôle
  authentifié.** La route de liste masque le milieu de l'URL ; la route d'un webhook
  précis, non. Un compte `READ_ONLY` peut donc lire un secret qui lui permet de poster
  dans ton salon Discord. Considère `READ_ONLY` comme « observateur de confiance », pas
  comme « inoffensif ».
- **`READ_ONLY` peut lire le log du serveur.** `GET /server/logs` n'a aucun contrôle de
  rôle, et un log Minecraft contient couramment des IP de joueurs et des arguments de
  commandes.

---

## Manipulation des tokens

- **La comparaison est à temps constant.** `WebDashboard.constantTimeEquals` fait un XOR
  sur chaque octet et accumule la différence au lieu de sortir dès le premier écart. Elle
  sort bien tôt quand les deux longueurs diffèrent, donc la longueur d'un token n'est pas
  protégée — ce qui est sans conséquence, puisque tous les tokens générés par ce mod font
  43 caractères.
- **Les tokens sont générés avec `SecureRandom`**, 32 octets, Base64 URL-safe sans
  padding. Le master token et les tokens de compte utilisent le même générateur, et les
  identifiants de session le même encore, avec un préfixe `ses_`.
- **Un token de compte n'est affiché qu'une seule fois**, dans la réponse à
  `POST /admin/accounts` ou `POST /admin/accounts/{username}/regenerate`. Toute autre
  représentation d'un compte l'omet. Il reste stocké en clair dans
  `config/cheesedash.json` : c'est le fichier qui est le secret, pas un hash du token.
- **La case « Mémoriser sur cet appareil » du formulaire de connexion stocke le token
  brut dans le navigateur.** Cochée, la page garde le token lui-même (et pas seulement
  l'identifiant de session) dans le `localStorage`, sous `cg_remember_token`, et s'en
  sert pour se reconnecter en silence à la visite suivante, même après un redémarrage du
  serveur. Quiconque accède à ce profil de navigateur a ton token ; pour le master token,
  laisse la case décochée sur toute machine partagée. Se déconnecter l'efface.

---

## Limitation de débit

Le middleware tient un compteur par IP : **20 authentifications échouées par fenêtre de
60 secondes**, après quoi cette IP reçoit `429 {"error":"Too many requests"}` jusqu'au
renouvellement de la fenêtre. La fenêtre n'est pas glissante : elle repart du premier
échec qui suit une pause. Le contrôle passe avant l'authentification : tant qu'une IP est
bloquée, même un identifiant de session valide venant de cette IP reçoit le `429`.

> ⚠️ **Ce mécanisme ne couvre pas la route de login.** `/auth/login` est reconnue et
> dispatchée *avant* le contrôle de débit, et un login échoué n'incrémente jamais le
> compteur. Ce qui est réellement limité, c'est l'envoi d'un mauvais en-tête
> `Authorization` sur une autre route. Une attaque par force brute contre
> `POST /auth/login` n'est donc pas bridée ; les échecs sont audités en
> `auth.login_failed` avec l'IP, donc tu le verras dans le journal d'audit, mais rien ne
> l'arrête. Les 256 bits d'entropie du token sont la vraie défense — et c'est
> précisément pourquoi rien de tout ça n'a sa place sur un port public.

---

## En-têtes de sécurité

Chaque réponse de `/api/` et du handler de fichiers statiques porte :

| En-tête | Valeur |
|---|---|
| `X-Frame-Options` | `DENY` |
| `X-Content-Type-Options` | `nosniff` |
| `X-XSS-Protection` | `1; mode=block` |
| `Referrer-Policy` | `no-referrer` |
| `Access-Control-Allow-Origin` | `""` (vide) |
| `Content-Security-Policy` | `default-src 'self'`, avec des autorisations de scripts pour les CDN Tailwind, jsDelivr et unpkg, de styles pour Tailwind, jsDelivr et `fonts.googleapis.com`, les polices depuis `fonts.gstatic.com`, les images depuis `data:`, `blob:`, `mc-heads.net`, `minecraft.wiki` et `img.pokemondb.net`, et `connect-src 'self' ws: wss:` pour le WebSocket |

La CSP inclut `'unsafe-inline'` et `'unsafe-eval'` parce qu'Alpine.js évalue des
expressions tirées d'attributs HTML. C'est un véritable affaiblissement de la politique,
accepté en échange de l'absence d'étape de build. Conséquence pratique : la protection
contre le XSS dans le dashboard repose sur un échappement correct des données par le
frontend, pas sur la CSP.

Un **contexte public de module** — le mécanisme qu'utilise CheeseStore pour sa vitrine —
reçoit un jeu d'en-têtes différent et plus strict, avec HSTS, `Permissions-Policy` et
`frame-ancestors 'none'`, plus des autorisations spécifiques à Stripe. Ces pages ne sont
pas authentifiées par conception ; voir
[écrire un module](writing-a-module.fr.md#contextes-publics-non-authentifiés).

### CORS

Fermé. `Access-Control-Allow-Origin` vaut la chaîne vide sur chaque réponse d'API, ce
qu'aucun navigateur n'accepte comme correspondance : une page d'une autre origine ne peut
donc pas lire l'API, même avec un token volé en JavaScript. Le préflight `OPTIONS`
répond `204` avec `Allow-Methods` et `Allow-Headers` mais toujours sans origine
exploitable, donc la vraie requête n'est jamais envoyée.

Rien n'arrête un client qui n'est pas un navigateur, et c'est le but : `curl` et tes
scripts fonctionnent. CORS protège le navigateur de l'administrateur, pas le serveur.

### Ce qui est servi sans aucune authentification

- `GET /` et tous les fichiers statiques en dessous : `index.html`, `preview.html`,
  `assets/*`. La page de login doit être joignable avant que tu aies un justificatif.
  Aucune donnée du serveur ne se trouve dans ces fichiers.
- Tout chemin qui ne correspond pas à un fichier retombe sur `index.html`, pour que le
  routeur en une seule page le traite.
- Toutes les routes sous le **préfixe public** d'un module, s'il en déclare un. Dans ce
  dépôt, quatre modules le font : CheeseStore (`/store/`), CobbleCheeseScore
  (`/scores/`), CheeseItems (`/cheeseitems/`) et son module Morphos (`/morphos/`). Ils
  vivent sur le même port que le dashboard : un reverse proxy censé n'exposer que l'un
  d'eux doit filtrer par chemin, pas par port.

---

## Exposition réseau

> ⚠️ **Le port 44311 ne doit pas être joignable depuis Internet.** Ce n'est pas une
> suggestion de durcissement, c'est la ligne la plus importante de cette page.

Les raisons concrètes, dans l'ordre de gravité :

1. **Il n'y a pas de TLS.** Le serveur HTTP est un simple `HttpServer` sur une socket
   TCP. Ton master token circule en clair sur la requête de login, et ton identifiant de
   session circule en clair sur toutes les requêtes suivantes. Quiconque se trouve entre
   toi et le serveur — un réseau de café, un routeur compromis, le switch de ton
   hébergeur — lit les deux et peut les rejouer.
2. **Une session `SUPER_ADMIN` est un shell distant sur le jeu.**
   `POST /server/command` exécute n'importe quelle commande Minecraft avec tous les
   droits du serveur. Cela comprend `op`, `ban`, `whitelist off`, et toutes les commandes
   d'administration que tes autres mods enregistrent.
3. **La force brute sur la route de login n'est pas bridée**, comme décrit plus haut.
   C'est l'entropie du token qui te sauve, et seulement tant que ce token est un token
   généré — si tu le remplaces un jour à la main par quelque chose de mémorisable, tu as
   supprimé la seule défense.
4. **`READ_ONLY` n'est pas en lecture seule au sens qui compte.** Il peut lire le log de
   ton serveur et tes URL de webhooks Discord non masquées.
5. **Le port WebSocket n'accepte que le master token** (voir ci-dessous) : quoi que tu
   fasses pour le 44311, le 44312 transporte le secret de plus grande valeur que tu
   possèdes.

Choisis l'une de ces approches, par ordre de préférence décroissant :

| Approche | Quoi faire |
|---|---|
| **Tunnel SSH** | `ssh -L 44311:localhost:44311 -L 44312:localhost:44312 user@serveur`, puis navigue sur `http://localhost:44311`. Rien n'est exposé, et le trafic est chiffré par SSH. Transfère les deux ports, sinon les vues en direct restent vides |
| **VPN** | Place le serveur et ton poste sur un réseau WireGuard ou Tailscale, et fais écouter le dashboard sur cette interface uniquement |
| **Reverse proxy avec TLS** | nginx ou Caddy termine le HTTPS sur 443 et relaie vers `127.0.0.1:44311`, en faisant passer l'upgrade WebSocket vers 44312. Ajoute une authentification HTTP basique devant comme second facteur |
| **Liste blanche pare-feu** | `iptables`/`ufw` n'autorisant que ta propre IP. Jouable, mais ton token traverse quand même Internet en clair |

Et quel que soit ton choix : ne mets pas le dashboard derrière la fonction « ouvrir un
port » d'un hébergeur mutualisé en considérant le travail fait.

### Le WebSocket n'accepte que le master token

`WebSocketServer.onMessage` compare le token du message `auth` à `config.getToken()` et à
rien d'autre. Les identifiants de session ne fonctionnent pas, les tokens de compte non
plus. Une connexion qui échoue est fermée aussitôt avec le code `4001` ; une connexion
qui ne s'authentifie pas est fermée avec le même code par un balayage qui passe toutes les
5 secondes, donc au bout de 5 à 10 secondes.

Un compte `OPERATOR` ou `READ_ONLY` ne possède donc aucun justificatif que le WebSocket
accepte, et seul un client muni du master token reçoit les données en direct. Le dashboard
livré n'y parvient même pas : il envoie son identifiant de session, jamais le token brut,
si bien que sa socket est refusée pour tous les comptes, master compris, et que les pages
retombent sur du sondage. Les comptes additionnels fonctionnent bien pour l'API REST et
les pages construites dessus. Les détails du protocole sont dans
[WebSocket](websocket.fr.md#seul-le-master-token-est-accepté).

---

← [Configuration](configuration.fr.md) · [API REST](rest-api.fr.md) →
