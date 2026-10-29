[← CheesePatrol](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](dashboard.md)

# API dashboard

CheesePatrol enregistre un module CheeseDash sous le préfixe **`patrol`** : tout vit donc
sous `/api/v1/patrol/*` sur le serveur HTTP de CheeseDash, port **44311**.

C'est la plus petite surface dashboard du dépôt : **deux routes**. Le dashboard est une
fenêtre en lecture seule sur les patrouilles, plus une action de confort. Créer, éditer,
démarrer et arrêter un circuit passe uniquement par les commandes — par omission plutôt que
par choix, puisqu'aucun endpoint ne le permet.

---

## Enregistrement

`fabric.mod.json` déclare l'entrypoint :

```json
"entrypoints": {
  "cheesedash": ["com.cheesepatrol.dashboard.CheesePatrolDashboardModule"]
}
```

CheeseDash le découvre au démarrage via
`FabricLoader.getEntrypoints("cheesedash", DashboardModule.class)`. Ce que le module
annonce pour la barre latérale :

| Méthode | Valeur |
|---|---|
| `getPrefix()` | `patrol` |
| `getDisplayName()` | `CheesePatrol` |
| `getNavIcon()` | 🚶 |
| `getNavDescription()` | `Patrouilles NPC` |

La description est en français écrit en dur, contrairement aux chaînes propres du dashboard
qui passent par l'i18n. C'est un fil qui pend, pas un choix délibéré.

Le module conserve la référence au `MinecraftServer` entre `onServerStart` et
`onServerStop`. Tant que cette référence est nulle — avant le démarrage du serveur, après
son arrêt — les deux routes répondent :

```json
{"error":"CheesePatrol not ready"}
```

avec le statut **503**.

## Authentification

Authentification par session CheeseDash, standard. Connecte-toi une fois :

```bash
curl -X POST http://localhost:44311/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"token":"<master-token-or-account-token>"}'
```

et passe le token de session renvoyé à chaque appel suivant :

```bash
curl http://localhost:44311/api/v1/patrol/list \
  -H 'Authorization: Bearer ses_…'
```

Les sessions durent 24 heures avec une expiration glissante. Tout ce qui est sous
`/api/v1/`, sauf la route de connexion, exige l'en-tête.

---

## `GET /list`

```text
GET /api/v1/patrol/list
```

Aucun paramètre de chemin ni de requête. Renvoie toutes les patrouilles, actives ou non,
sous forme de tableau JSON.

**Rôle requis : n'importe quelle session authentifiée.** Il n'y a ni appel à `requireWrite`
ni à `requireAdmin` sur cette route : un compte `READ_ONLY` peut donc la lire, ce qui est
correct pour un listage.

```json
[
  {
    "npcUuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "npcName": "Village Guard",
    "dimension": "minecraft:overworld",
    "active": true,
    "waypointCount": 4,
    "currentWaypointIndex": 2,
    "speed": 1.0,
    "pausedBySystem": false
  }
]
```

| Champ | Type | Remarques |
|---|---|---|
| `npcUuid` | chaîne | L'UUID complet. C'est ce qu'attend `POST /tp`, et ce qu'attend `/npcpatrol select` |
| `npcName` | chaîne | Le nom affiché de l'entité si elle est chargée et trouvable. Sinon les 8 premiers caractères de l'UUID suivis de `...` — un nom qui ressemble à `a1b2c3d4...` signifie donc que l'entité est introuvable |
| `dimension` | chaîne | La dimension sur laquelle la patrouille est fixée |
| `active` | booléen | Si le circuit tourne |
| `waypointCount` | entier | Longueur de la liste des points de passage |
| `currentWaypointIndex` | entier | **Compté depuis 0.** Les commandes en jeu affichent les points de passage à partir de 1 : l'index `2` ici est donc le point que `/npcpatrol info` appelle `#3` |
| `speed` | nombre | Vitesse stockée |
| `pausedBySystem` | booléen | `true` signifie que le mod a désactivé cette patrouille lui-même après dix réapparitions ratées. C'est le seul élément d'état que le dashboard expose et que `/npcpatrol list` n'expose pas |

| Statut | Quand |
|---|---|
| `200` | toujours, en cas de succès. Tableau vide s'il n'y a aucune patrouille |
| `401` | session absente ou expirée |
| `503` | la référence au serveur est nulle |
| `500` | exception imprévue ; le corps porte son message |

Deux choses à savoir sur cette route :

- **L'ordre n'est pas défini.** Elle parcourt une `ConcurrentHashMap`. La vue du dashboard
  trie côté client, actives d'abord puis par nom ; un script devrait trier aussi plutôt que
  de compter sur la stabilité de l'ordre renvoyé.
- **Elle résout les noms d'entités depuis le thread HTTP.** `resolveNpcName` parcourt
  `server.getWorlds()` et appelle directement `world.getEntity(uuid)`, sans passer par le
  thread serveur. Voir [la note sur le threading](architecture.fr.md#threading).

## `POST /tp`

```text
POST /api/v1/patrol/tp
```

Téléporte un **joueur** au point de passage situé un cran au-delà de celui vers lequel son
NPC marche actuellement, pour que le joueur arrive devant le NPC et non derrière. La
commande ne déplace pas le NPC.

**Rôle requis : OPERATOR ou plus.** Appliqué deux fois — voir
[la note sur l'endroit où vit le contrôle de rôle](#où-vit-le-contrôle-de-rôle) ci-dessous.

**Corps de la requête :**

```json
{
  "npcUuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "playerName": "RoiCheese"
}
```

| Champ | Type | Requis | Remarques |
|---|---|---|---|
| `npcUuid` | chaîne | oui | UUID complet avec tirets. Pas la forme tronquée de `/npcpatrol list` |
| `playerName` | chaîne | oui | Le nom d'un joueur **connecté**. `PlayerManager.getPlayer` le compare sans tenir compte de la casse |

**Réponse :**

```json
{
  "success": true,
  "waypointIndex": 3,
  "x": 112,
  "y": 65,
  "z": -324,
  "dimension": "minecraft:overworld"
}
```

`waypointIndex` vaut `(currentWaypointIndex + 1) % waypointCount`, compté depuis 0 : une
patrouille qui vise l'index 2 sur 4 te téléporte donc à l'index 3, que `/npcpatrol info`
appelle `#4`. Sur une patrouille arrivée à son dernier point de passage, l'index boucle à 0
et tu atterris sur le point `#1`.

La téléportation traverse les dimensions : elle cible la dimension propre de la patrouille,
pas celle du joueur, et conserve son yaw et son pitch.

| Statut | Corps | Quand |
|---|---|---|
| `200` | l'objet ci-dessus | la téléportation a été exécutée |
| `400` | `{"error":"Missing npcUuid or playerName"}` | l'un des deux champs est absent |
| `400` | `{"error":"Invalid UUID"}` | `npcUuid` n'est pas analysable |
| `400` | `{"error":"Patrol has no waypoints"}` | la patrouille existe mais sa liste est vide |
| `400` | `{"error":"Dimension not found: <id>"}` | la dimension de la patrouille n'est pas chargée sur ce serveur |
| `404` | `{"error":"Patrol not found"}` | aucune patrouille pour cet UUID |
| `404` | `{"error":"Player not found: <name>"}` | pas connecté |
| `403` | celui de CheeseDash | le rôle de la session est inférieur à OPERATOR |
| `500` | `{"error":"<message d'exception>"}` | tout le reste, y compris un JSON malformé |

### Où vit le contrôle de rôle

`CheesePatrolApi` garde cette route lui-même, dans la forme correcte :

```java
} else if (path.equals("/tp") && method.equals("POST")) {
    if (!WebDashboard.requireWrite(exchange)) return;
    handleTp(exchange, server);
```

`requireWrite` envoie le `403` et renvoie `false` quand la session n'a pas OPERATOR, et
c'est le fait de tester la valeur de retour qui rend le garde réel — un
`WebDashboard.requireWrite(exchange);` nu enverrait le `403` puis exécuterait quand même le
handler.

Cet appel local n'est pourtant pas la seule chose qui fasse barrage. Le répartiteur de
modules de CheeseDash, dans `DashboardApi`, garde déjà tous les modules avant que le module
ne voie la requête :

```java
DashboardModule module = DashboardRegistry.getModule(prefix);
if (module != null) {
    if (!method.equals("GET") && !WebDashboard.requireWrite(exchange)) {
        return;
    }
    module.handleRequest(exchange, subRoute);
```

L'appel propre au module est donc de la défense en profondeur : utile, et redondant dans
cette configuration. Trois conséquences du fait que la vraie frontière vit dans le cœur :

- **Aucune route patrol ne peut exiger `SUPER_ADMIN`.** Le répartiteur ne connaît que
  `requireWrite` : `OPERATOR` est donc le plafond, à moins qu'un handler n'appelle
  `requireAdmin` lui-même. Aucun des deux ne le fait.
- **Un `GET` qui modifierait quelque chose ne serait attrapé que par le module.** `/tp` est
  un `POST`, donc le point est théorique ici, mais le répartiteur ne regarde que la méthode
  HTTP et laisserait passer tel quel un `GET` mutant.
- **Une modification du répartiteur ne désarmerait pas `/tp`**, grâce à l'appel local — à la
  différence des modules qui ne se gardent pas eux-mêmes.

**Le 200 signifie « fait ».** La téléportation passe par
`WebDashboard.executeOnServerThread`, qui attend la fin de la tâche jusqu'à 5 secondes : un
`200` n'est donc envoyé qu'une fois que le thread serveur l'a exécutée. Si le thread serveur
est bloqué, l'attente expire et tu reçois un 500 — avec le corps `{"error":"null"}`, car
l'exception de délai n'a pas de message — tandis que la téléportation en file peut encore
s'exécuter plus tard.

**Exemple :**

```bash
curl -X POST http://localhost:44311/api/v1/patrol/tp \
  -H 'Authorization: Bearer ses_…' \
  -H 'Content-Type: application/json' \
  -d '{"npcUuid":"a1b2c3d4-e5f6-7890-abcd-ef1234567890","playerName":"RoiCheese"}'
```

## Routes inconnues

Tout le reste sous le préfixe :

```json
{"error":"Unknown patrol route: /start"}
```

avec le statut **404**. Le chemin est renvoyé tel quel, sans aucun échappement : un chemin
contenant un guillemet double ou une barre oblique inverse produit du JSON invalide — à
savoir si tu analyses l'erreur.

## Canaux WebSocket

**CheesePatrol ne publie rien en WebSocket.** Il n'y a aucun appel à `broadcast(...)` dans
tout le mod et aucun canal enregistré auprès de `DashboardRegistry`.

Conséquence pratique : la vue du dashboard est un instantané, pas un flux en direct. Le
`currentWaypointIndex` d'un NPC dans le tableau est celui du moment où tu as chargé la page,
et la page ne refait pas d'appel. Recharge pour le voir avancer.

Ajouter des mises à jour en direct serait l'amélioration évidente — un `patrol_update` au
changement de point de passage, ou au moins aux transitions actif/inactif — et le point
d'accroche est l'appel à `advanceToNextWaypoint` dans `tickPatrol`, plus le chemin de pause
système. À 20 ticks par seconde, une diffusion par changement de point serait bon marché ;
une diffusion par tick, non.

## La vue du dashboard

Une page, enregistrée dans le front-end de CheeseDash sous le nom `view-patrol.js`, atteinte
depuis la barre latérale sous **CheesePatrol → Patrols**.

Elle affiche un tableau, trié actives d'abord puis par ordre alphabétique du nom de NPC :

| Colonne | Source |
|---|---|
| NPC | `npcName` |
| Dimension | `dimension`, namespace retiré — `minecraft:overworld` s'affiche `overworld` |
| Checkpoint | `currentWaypointIndex + 1` sur `waypointCount`, converti dans la forme comptée depuis 1 qu'utilisent les commandes |
| Status | un point coloré : vert actif, orange `pausedBySystem`, gris inactif |
| Speed | `speed` |
| — | un bouton **TP** par ligne |

L'en-tête compte les actives sur le total. Une liste vide affiche un état vide plutôt qu'un
tableau nu.

Le bouton **TP** appelle `POST /tp` avec l'UUID du NPC et le joueur administrateur
actuellement sélectionné dans l'en-tête du dashboard, lu dans `localStorage` sous
`cg_admin_player`. Sans joueur administrateur sélectionné, il refuse et te demande d'en
choisir un — ce sélecteur est une fonctionnalité de CheeseDash, partagée par tous les mods
qui ont besoin d'agir sur le personnage d'un administrateur.

Ce que la vue ne propose **pas** : créer une patrouille, ajouter ou retirer des points de
passage, changer la vitesse ou le rayon, démarrer ou arrêter un circuit, ou en effacer un.
Tout cela passe par `/npcpatrol` en jeu, et doit y passer, puisque sélectionner un NPC
suppose de le viser.

Il existe aussi un guide intégré, accessible depuis le bouton point d'interrogation dans
l'en-tête de la vue, qui couvre le même terrain que ces pages en version courte.
⚠️ Deux de ses affirmations sont fausses face au code actuel : il décrit `/npcpatrol markers`
comme une bascule (elle ne fait que rafraîchir) et liste un effet de particules `drip` qui
n'existe pas.

---

← [Garder les NPC en vie](resilience.fr.md) · [Architecture](architecture.fr.md) →
