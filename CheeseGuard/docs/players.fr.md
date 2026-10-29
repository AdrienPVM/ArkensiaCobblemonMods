[← CheeseGuard](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](players.md)

# Joueurs

Le sous-système joueurs est la fenêtre du dashboard sur qui est connecté, plus un petit jeu
d'actions de modération : expulser, téléporter, changer le mode de jeu, envoyer un message. Il
ne détient aucun état et aucun fichier de configuration — `PlayersApi`
(`dashboard/PlayersApi.java`, ~300 lignes) lit le `PlayerManager` vivant à chaque requête et
n'écrit rien sur le disque.

Tout ce qui suit passe par le dashboard. Il n'existe pas de commande `/cg player`.

## La liste des connectés

`GET /api/v1/cheeseguard/players` renvoie un objet par joueur connecté, construit directement
depuis l'entité :

```json
[
  {
    "uuid": "f84c6a79-0a4e-45e0-879b-cd49ebd4c4e2",
    "name": "Steve",
    "world": "minecraft:overworld",
    "x": 142,
    "y": 71,
    "z": -308,
    "health": 18.5,
    "maxHealth": 20.0,
    "gameMode": "survival",
    "ping": 42,
    "online": true
  }
]
```

Les coordonnées sont tronquées en entiers ; les points de vie sont arrondis à une décimale.
`ping` est la latence rapportée par le gestionnaire réseau, c'est-à-dire une moyenne glissante
et non une mesure fraîche. `gameMode` est le nom vanilla en minuscules — `survival`,
`creative`, `adventure`, `spectator` — alors que la route d'*écriture* du mode de jeu attend
des majuscules. Ne renvoie pas telle quelle la valeur que tu as lue sans la passer en
majuscules.

La liste est reconstruite à chaque requête : elle est donc toujours à jour, au prix d'un
parcours de la liste des joueurs. Le dashboard l'interroge en boucle ; il n'existe pas de
canal joueurs sur le WebSocket propre à CheeseGuard (CheeseDash publie ses propres messages
`player_join` / `player_leave` / `player_list` sur son canal `players`, qui est un mécanisme
différent).

## La liste des déconnectés

`GET /api/v1/cheeseguard/players/offline?n=20` renvoie les joueurs vus récemment qui ne sont
**pas** connectés.

La source est le `usercache.json` de vanilla, dans le dossier du serveur, lu et analysé à
chaque requête. Le gestionnaire parcourt le fichier à partir de sa **dernière** entrée, à
rebours, et s'arrête après `n` entrées, en sautant quiconque correspond à un joueur connecté
sans tenir compte de la casse. Vanilla écrit ce fichier du plus récemment utilisé au plus
ancien, donc le parcourir à rebours renvoie d'abord les joueurs vus **il y a le plus
longtemps** — l'inverse de ce que vise la route (son commentaire de code suppose l'ordre
inverse). Avec plus de joueurs en cache que `n`, les plus récents sont coupés. `n` est borné entre 1 et 100 et vaut 20 par
défaut ; un `n` illisible retombe sur la valeur par défaut au lieu d'échouer.

```json
[ { "name": "Alex", "uuid": "...", "online": false } ]
```

Trois limites découlent de la source de données :

- Un joueur qui ne s'est jamais connecté n'est pas dans le cache et n'apparaîtra jamais.
- Le cache appartient à vanilla, avec ses propres règles d'éviction : « vu récemment » est donc
  approximatif, et les entrées finissent par disparaître.
- Un `usercache.json` absent, vide ou malformé est avalé silencieusement et la route renvoie
  un tableau vide. Une liste vide signifie donc soit « personne », soit « le fichier n'a pas
  pu être lu », et la réponse ne permet pas de distinguer les deux.

Il n'y a ni pagination ni recherche : `n` ne fait que limiter la tête de liste.

## Détail d'un joueur

`GET /api/v1/cheeseguard/players/:name` prend un **nom**, pas un UUID, décodé depuis l'URL
avant la recherche.

Pour un joueur connecté, la réponse ajoute au résumé :

| Champ | Source |
|---|---|
| `food` | niveau de faim, 0–20 |
| `xpLevel` | niveau d'expérience |
| `fly` | si le vol est autorisé |
| `advDone` / `advTotal` | progression des succès |

Le comptage des succès parcourt tous les succès chargés, en sautant les identifiants
contenant `recipes/`, et demande au traqueur du joueur si chacun est accompli. Sur un serveur
lourdement moddé, cela représente des milliers de succès par requête, sur le thread HTTP.
C'est de loin la lecture la plus coûteuse de cette API — n'interroge pas cette route en
boucle.

Pour un joueur déconnecté, la réponse est minimale et reste un `200` :

```json
{ "name": "Alex", "online": false, "uuid": "..." }
```

`uuid` n'est présent que si le cache utilisateur connaît le nom. Un nom que personne n'a
jamais porté renvoie `{"name":"Nimporte","online":false}` — cette route **n'a pas de 404**.
Teste le champ `online` et la présence de `uuid` plutôt que de te fier au code de statut.

## Actions

Les quatre routes d'action exigent que le joueur soit **connecté** et répondent sinon
`404 {"error":"Player not online"}`. Toutes les quatre passent leur mutation par
`WebDashboard.executeOnServerThread` : un `200` signifie donc « l'action a été planifiée et la
tâche s'est terminée sans lever d'exception », et tout échec interne ressort en `500` portant
le message de l'exception.

### Expulsion

`POST /players/:name/kick`, corps `{"message": "..."}`. Le message vaut `Kicked by admin` par
défaut et est envoyé avec `Text.literal` : les codes couleur `§` fonctionnent et rien n'est
traduit — le joueur voit exactement la chaîne que tu as tapée, quelle que soit sa langue.

```bash
curl -X POST http://localhost:44311/api/v1/cheeseguard/players/Steve/kick \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"Redemarrage du serveur dans 10s"}'
```

Il n'y a pas de bannissement ici, et pas d'historique d'expulsions. Un joueur expulsé peut se
reconnecter immédiatement. Pour quelque chose de durable, utilise [Prison](prison.fr.md) ou la
liste de bannissement vanilla via `POST /api/v1/server/command`.

### Téléportation

`POST /players/:name/tp`. La forme du corps décide laquelle des trois conduites tu obtiens :

| Corps | Comportement |
|---|---|
| `{"x":..,"y":..,"z":..}` | téléporte dans le monde courant du joueur |
| `{"dimension":"..","x":..,"y":..,"z":..}` | téléporte à ces coordonnées dans cette dimension |
| `{"dimension":".."}` | téléporte au point d'apparition de la dimension, via `DimensionManager` |

Avec des coordonnées et sans dimension, tout axe omis garde la valeur actuelle du joueur :
`{"y": 200}` le soulève donc à la verticale. Avec une dimension **et** des coordonnées, les
axes omis valent `0`, `64`, `0`, parce qu'il n'existe pas de « valeur actuelle » sensée dans
un monde où le joueur n'est pas encore. Cette asymétrie surprend : le même corps partiel
signifie deux choses différentes selon la présence de `dimension`.

Le lacet et le tangage sont toujours conservés. Un identifiant de dimension inconnu donne
`500 {"error":"java.lang.RuntimeException: Dimension not found: <id>"}` : l'exception est
levée dans la tâche du thread serveur, et le gestionnaire renvoie le message de
l'`ExecutionException` qui l'enveloppe, sans le déballer.

La forme « point d'apparition » délègue à `DimensionManager.tpToDimension()`, qui connaît les
dimensions personnalisées de CheeseGuard et leurs points d'apparition configurés — voir
[Dimensions](dimensions.fr.md). Si aucun `DimensionManager` n'est disponible, l'appel est sauté
silencieusement et la route répond quand même `200 {"teleported":true}`.

### Mode de jeu

`POST /players/:name/gamemode`, corps `{"mode": "SURVIVAL"}`. Valeurs acceptées, en
majuscules : `SURVIVAL`, `CREATIVE`, `ADVENTURE`, `SPECTATOR`. L'entrée est passée en
majuscules avant comparaison, donc `survival` marche ; tout le reste donne
`500 {"error":"Invalid gamemode: <valeur>"}`. Un `mode` absent donne
`400 {"error":"mode is required"}`.

C'est un appel direct à `changeGameMode`. Il ne se coordonne avec rien d'autre qui gère les
modes de jeu — un événement qui a mis un joueur en spectateur, par exemple, garde son propre
souvenir du mode qu'il compte restaurer. Changer le mode de jeu d'un participant depuis ici
pendant un événement sera écrasé au nettoyage de l'événement, ou écrasera ce que le nettoyage
attendait. Préfère les rôles de participant de l'événement ([Événements](events.fr.md)).

### Message

`POST /players/:name/message`, corps `{"text": "...", "type": "chat"}`. Quatre types :

| `type` | Livraison |
|---|---|
| `chat` (par défaut, et toute valeur inconnue) | une ligne de chat |
| `actionbar` | au-dessus de la barre d'action |
| `title` | le grand texte de titre |
| `subtitle` | la seconde ligne du titre |

Un `text` absent donne `400 {"error":"text is required"}`.

`chat` et `actionbar` passent par `Text.literal` : les codes `§` fonctionnent. `title` et
`subtitle` sont implémentés autrement : ils construisent une commande vanilla `/title` avec le
texte inséré dans un composant JSON, et l'exécutent depuis la source de commandes du serveur.
Les guillemets doubles sont échappés, mais rien d'autre — une barre oblique inverse, un retour
à la ligne ou une accolade seule dans le texte produisent du JSON malformé, et la commande
échoue. Cet échec est une erreur de commande, pas une exception : la route répond quand même
`200 {"sent":true}` et rien n'apparaît à l'écran.

Garde les titres en texte brut. Si tu as besoin de mise en forme, envoie un `chat` avec des
codes `§`.

## Autorisation

`PlayersApi` ne contient **aucun** appel à `WebDashboard.requireWrite` ni à `requireAdmin`.
Rien dans ce fichier ne vérifie un rôle.

Ce qui protège les quatre routes d'action, c'est le répartiteur de modules de CheeseDash, qui
garde toute requête non-`GET` vers un module avec
`if (!method.equals("GET") && !requireWrite(...))`. Comme les quatre actions sont des `POST`,
elles exigent de fait `OPERATOR` ou au-dessus, et les deux routes de lecture acceptent tous
les rôles. C'est le bon résultat ici — mais c'est le cœur qui le produit, pas ce module, et la
distinction compte dès que quelqu'un ajoute un `GET` qui modifie quelque chose. Le tableau
complet est dans [API dashboard](dashboard.fr.md#ce-que-le-cœur-applique-réellement).

Toute requête non-`GET` vers un module est aussi écrite dans le journal d'audit de CheeseDash,
sous la forme `cheeseguard.post` avec le chemin complet : les expulsions et les téléportations
sont donc rattachables à une session.

---

← [Chronologie](timeline.fr.md) · [Chunks](chunks.fr.md) →
