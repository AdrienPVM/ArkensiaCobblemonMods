[← CheeseDash](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](configuration.md)

# Configuration

Les réglages propres à CheeseDash vivent dans un seul fichier :

```text
config/cheesedash.json
```

Il est créé au premier démarrage, avec un master token généré et des défauts
raisonnables. Il est lu **une seule fois**, pendant l'initialisation du mod, et réécrit
dès que quelque chose modifie un compte, le nom du joueur administrateur, les dossiers de
zones ou le master token.

> ⚠️ **Ce fichier contient un secret.** Le champ `token` est le mot de passe maître de
> ton serveur : une session `SUPER_ADMIN`, donc l'exécution de n'importe quelle commande.
> Chaque entrée de `accounts[]` en contient un autre. Ne commite jamais ce fichier, ne le
> colle jamais dans un rapport de bug, ne le mets jamais dans un modpack, et garde ses
> permissions de fichier serrées. Si tu soupçonnes une fuite, appelle
> `POST /api/v1/dashboard/token/regenerate` ou supprime le fichier et redémarre.

> ⚠️ Les modifications à la main sur un serveur **en fonctionnement** sont ignorées, et
> pire, elles sont écrasées : le prochain changement de compte réécrit tout le fichier
> depuis la mémoire. Arrête le serveur avant d'éditer à la main, ou fais le changement
> par l'API.

---

## Toutes les clés

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `port` | int | `44311` | Port TCP sur lequel le serveur HTTP écoute. Le serveur WebSocket écoute sur `port + 1`, toujours, sans réglage distinct |
| `token` | string | 32 octets aléatoires, Base64 URL-safe | Le master token. Résout vers le nom d'utilisateur `admin` avec le rôle `SUPER_ADMIN` |
| `enabled` | boolean | `true` | À `false`, le serveur HTTP n'est pas démarré du tout. Le serveur WebSocket démarre quand même |
| `adminPlayerName` | string | `""` | Le nom en jeu que le dashboard considère comme « toi ». Pilote les actions ciblant un joueur et l'affichage de position. Omis du fichier tant qu'il est vide |
| `accounts` | array | `[]` | Comptes administrateurs additionnels. Un objet par compte ; voir [plus bas](#le-tableau-accounts) |
| `zoneFolders` | array | `[]` | Regroupement purement visuel des zones CheeseGuard dans la barre latérale. Aucun effet sur la protection |
| `_note` | string | écrit par le mod | Une chaîne de rappel que le mod écrit à chaque sauvegarde et ignore au chargement. La modifier ou la supprimer ne change rien |

### `port`

Le changer exige un redémarrage ; le serveur écoute au démarrage et ne se relie jamais.
Deux points à garder en tête :

- Le port WebSocket suit automatiquement. Mettre `port` à `8080` déplace le WebSocket sur
  `8081`, et le frontend apprend la nouvelle valeur via `GET /status`, qui renvoie
  `wsPort`.
- Les deux ports doivent être libres. Si le bind HTTP échoue, le log le dit et le
  dashboard est simplement absent ; si c'est le bind WebSocket qui échoue, le HTTP
  continue de fonctionner et les mises à jour en direct, non. Voir
  [dépannage](troubleshooting.fr.md#le-dashboard-ne-démarre-jamais-et-le-log-dit-address-already-in-use).

### `token`

Généré avec `SecureRandom`, 32 octets, Base64 URL-safe sans padding — 43 caractères. Il
est affiché dans un encadré la première fois qu'il est généré, et plus jamais ensuite.

Trois façons de le remplacer :

- `POST /api/v1/dashboard/token/regenerate` avec `{"confirm":true}` dans le corps,
  `SUPER_ADMIN` uniquement. Le nouveau token revient dans la réponse. **Les sessions
  existantes continuent de fonctionner** : régénérer le master token ne révoque aucune
  session, donc un attaquant qui détient un identifiant de session vivant n'est pas
  expulsé par cette seule opération. Redémarre le serveur si tu veux en être certain.
- Le mettre à `""` ou le retirer du fichier : le démarrage suivant s'en aperçoit, en
  génère un nouveau, et écrit `TOKEN MISSING — new master token generated`.
- Supprimer tout le fichier, ce qui supprime aussi tous les comptes.

### `enabled`

Le seul interrupteur global. Avec `enabled: false` :

```text
[CheeseDash] Disabled in config, skipping.
```

Pas de serveur HTTP, pas de fichiers statiques, pas d'API — donc aucune route de module
ne fonctionne, même si les modules eux-mêmes se sont chargés normalement. Le serveur
WebSocket démarre malgré tout, ce qui est discutable : le port `port + 1` reste ouvert,
accepte les connexions et diffuse le TPS, les arrivées de joueurs et les entrées d'audit
à tout client qui envoie le master token. Si tu désactives le dashboard pour des raisons
de sécurité, bloque aussi ce port ; voir
[WebSocket](websocket.fr.md#cycle-de-vie-threads-et-modes-de-panne).

### `adminPlayerName`

Texte libre, comparé au nom d'un joueur connecté. Le dashboard s'en sert pour les actions
qui ont besoin d'un joueur cible sans la demander chaque fois, et
`GET /api/v1/dashboard/admin-player/position` renvoie les coordonnées et le monde de ce
joueur, ou `{"online":false}` quand le nom est vide ou le joueur hors ligne. Aucune
validation : un nom mal orthographié donne `online: false` plutôt qu'une erreur.

Modifiable à chaud via `PUT /api/v1/dashboard/admin-player` (`OPERATOR` ou plus), qui
sauvegarde le fichier immédiatement.

---

## Le tableau `accounts`

Chaque entrée est un objet à quatre champs :

| Champ | Type | Requis | Effet |
|---|---|---|---|
| `username` | string | oui | Identifiant unique. L'API impose 1 à 32 caractères parmi `[a-zA-Z0-9_-]` et refuse `admin`, réservé au master token. Un fichier écrit à la main n'est **pas** vérifié contre ces règles |
| `token` | string | oui | Le secret de ce compte. Généré comme le master token |
| `role` | string | non, `READ_ONLY` par défaut | Une valeur parmi `READ_ONLY`, `OPERATOR`, `SUPER_ADMIN` |
| `createdAt` | long | non | Millisecondes epoch, pour l'affichage seulement |

Ce que chaque rôle peut faire est le sujet de
[authentification et rôles](auth-and-roles.fr.md#ce-que-peut-faire-chaque-rôle). En
résumé : `READ_ONLY` peut lire, `OPERATOR` peut modifier l'état du jeu, `SUPER_ADMIN`
peut en plus lancer des commandes et gérer les comptes.

Préfère créer les comptes via `POST /api/v1/admin/accounts` plutôt qu'à la main. La route
valide le nom, refuse les doublons, génère le token pour toi, et le renvoie exactement
une fois.

> ⚠️ **Un seul compte malformé te coûte le master token et des comptes.** Le parsing des
> comptes se fait dans le même bloc `try` que le reste du chargement, et une valeur de
> `role` inconnue (ou un `username` ou un `token` absent) lève une exception. La suite de
> la lecture est alors abandonnée avec `Failed to read config, regenerating`, et le mod
> retombe sur son chemin de premier démarrage : **un nouveau master token est généré et
> le fichier est écrasé**. Ce qui était déjà chargé avant l'exception survit — `port`,
> `enabled`, `adminPlayerName`, `zoneFolders` et les comptes listés *avant* le compte
> fautif — mais le compte fautif et tous ceux qui le suivent disparaissent. Une virgule en
> trop ou n'importe quelle autre erreur de syntaxe JSON est pire : rien n'est chargé, et
> le fichier est réécrit avec les valeurs par défaut et aucun compte. Garde une sauvegarde
> avant d'éditer à la main, et si tu le fais, valide d'abord le JSON.

---

## Le tableau `zoneFolders`

Un confort propre à CheeseDash, pour les serveurs à nombreuses zones CheeseGuard. Chaque
dossier ressemble à ça :

```json
{ "id": "f_18f3c2a1b40", "name": "Spawn area", "zoneIds": [], "order": 0 }
```

`id` est généré sous la forme `f_` suivi de l'horodatage de création en hexadécimal. Rien
ne valide `zoneIds` contre des zones qui existent réellement, et rien dans CheeseGuard ne
lit ces données — supprimer un dossier ne supprime jamais une zone. La gestion passe par
les routes `/api/v1/dashboard/zone-folders` documentées dans
[l'API REST](rest-api.fr.md#dossiers-de-zones).

---

## Les autres fichiers écrits par CheeseDash

Trois voisins dans le même dossier, chacun possédé par un sous-système différent et
chacun créé seulement au premier écrit. Ils ne font pas partie de `cheesedash.json` et ne
sont pas rechargés depuis le disque pendant l'exécution.

| Fichier | Propriétaire | Contenu | Documenté dans |
|---|---|---|---|
| `config/cheesedash_audit.json` | `AuditLogger` | Un tableau JSON plat d'entrées d'audit, la plus récente en dernier, plafonné à 10 000 | [journal d'audit](audit.fr.md) |
| `config/cheesedash_schedules.json` | `ServerScheduler` | `{"recurring":[…]}` — les définitions de redémarrages récurrents. Les actions ponctuelles ne sont jamais persistées | [planificateur](scheduler.fr.md) |
| `config/cheesedash_webhooks.json` | `DiscordWebhookManager` | `{"webhooks":[…]}` — les cibles Discord et leurs abonnements aux événements | [webhooks Discord](discord-webhooks.fr.md) |

La configuration Discord n'est **pas** dans `cheesedash.json`. Une entrée de webhook
ressemble à ceci, et le champ `url` est un secret à part entière : quiconque le détient
peut poster dans ton salon.

```json
{
  "webhooks": [
    {
      "id": "wh_3f8a1c",
      "name": "Staff alerts",
      "url": "https://discord.com/api/webhooks/123456789/AbCdEf…",
      "avatarUrl": "",
      "enabled": true,
      "events": ["SERVER_START", "SERVER_STOP", "ADMIN_COMMAND"],
      "scoreboardFilter": [],
      "createdAt": 1772131331004
    }
  ]
}
```

Une chaîne inconnue dans `events` est ignorée silencieusement, et une entrée qui échoue
au parsing est sautée avec un avertissement plutôt que de faire perdre le fichier —
l'inverse du comportement de `cheesedash.json`. L'API masque le milieu de l'URL dans les
réponses de liste et la renvoie entière sur le `GET` d'un webhook précis.

Sur un **client** où le mod est installé, un fichier de plus apparaît :

```text
config/cheesedash_shortcuts.json
```

Il contient la liste des raccourcis du menu rapide et n'atteint jamais le serveur. Voir
[menu rapide](quick-menu.fr.md).

---

## Un fichier complet annoté

Les commentaires ne sont **pas** du JSON valide. Retire-les avant usage ; ils sont là
pour être lus, pas parsés.

```json
{
  "port": 44311,                       // HTTP ; le WebSocket est implicitement 44312
  "token": "EXAMPLE-TOKEN-do-not-use-mWqZ4rKxv2NbSvQ1pX",
                                       // master token → utilisateur "admin", SUPER_ADMIN.
                                       // SECRET. Ne commite jamais ce fichier.
  "enabled": true,                     // false désactive entièrement le serveur HTTP
  "adminPlayerName": "RoiCheese",      // le joueur en jeu que le dashboard cible

  "zoneFolders": [                     // regroupement cosmétique, zones CheeseGuard
    {
      "id": "f_18f3c2a1b40",           // généré, ne le choisis pas à la main
      "name": "Spawn area",            // texte libre
      "zoneIds": ["spawn", "market"],  // non validé contre les zones réelles
      "order": 0                       // ordre d'affichage
    }
  ],

  "accounts": [
    {
      "username": "moderator",         // 1-32 car., [a-zA-Z0-9_-], jamais "admin"
      "token": "Jq7x…-WkR",            // SECRET, propre à ce compte
      "role": "OPERATOR",              // READ_ONLY | OPERATOR | SUPER_ADMIN
      "createdAt": 1772131331004       // affichage seulement
    },
    {
      "username": "builder",
      "token": "Zp2v…-9tQ",
      "role": "READ_ONLY",             // voit tout, ne change rien
      "createdAt": 1772131400912
    }
  ],

  "_note": "Token is your dashboard password. Delete this file to regenerate."
                                       // écrit par le mod, ignoré au chargement
}
```

---

← [Premiers pas](getting-started.fr.md) · [Authentification et rôles](auth-and-roles.fr.md) →
