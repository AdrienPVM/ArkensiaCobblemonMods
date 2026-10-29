[← CobbleCap](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](configuration.md)

# Configuration

CobbleCap tient huit fichiers. Deux sont de la configuration que tu édites, un est une liste
de champions que tu rédiges, et cinq sont de l'état que le mod possède et que tu ne devrais
toucher qu'avec le serveur arrêté.

| Chemin | Nature | Écrit par |
|---|---|---|
| `config/cobblecap/config.json` | **configuration** | `CapConfig` — au premier démarrage, et à chaque setter |
| `config/cobblecap/champions.json` | **données rédigées** | `ChampionConfig` — créé vide, réécrit par le `PUT` du dashboard |
| `config/cobblecap/players.json` | état | `CapStorage` — différé, et à l'arrêt |
| `config/cobblecap_legends.json` | état | `LegendsStorage` — à chaque changement |
| `config/cobblecap_tenures.json` | état | `TenureStorage` — à chaque changement |
| `config/cobblecap_chronos.json` | état | `ChronoManager` — à chaque changement |
| `config/cobblecap_dioramas.json` | état | `StatueManager` — à chaque changement d'ancre |
| `config/cobblecap_client.properties` | configuration **client** | `PastureHideClient` |

Noter l'incohérence de disposition : `config.json`, `champions.json` et `players.json`
vivent dans un sous-répertoire `cobblecap/` ; tout le reste est directement dans `config/`
avec un préfixe `cobblecap_`. C'est historique et le mod s'en moque, mais cela veut dire
qu'un script de sauvegarde doit couvrir les deux formes.

Un neuvième et un dixième ensemble de fichiers sont des **sorties**, pas de la
configuration : `config/cobblecheesescore_external_conseil4_*.json` et
`config/cobblecap_regis_backup/`. Voir
[les classements Conseil 4](champions.fr.md#export-json-pour-cobblecheesescore) et
[le morphing du champion](champion-morphing.fr.md#la-sauvegarde).

---

## `config/cobblecap/config.json`

Écrit avec les valeurs par défaut au premier démarrage s'il n'existe pas. Chaque clé n'est
lue que si elle est présente, donc supprimer une clé restaure sa valeur par défaut au lieu
de casser l'analyse.

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `startingCap` | entier | `20` | la limite donnée à un joueur à sa première connexion |
| `enabled` | booléen | `true` | interrupteur général de l'application XP, des champions, des espèces interdites et du contrôle d'équipe |
| `discordWebhookUrl` | chaîne | `""` | webhook Discord pour les événements de champion et de Maître de la Ligue ; vide désactive |
| `bannedSpecies` | tableau de chaînes | `[]` | espèces qui annulent un combat si elles sont dans l'équipe |
| `fireworksEnabled` | booléen | `false` | lancer un spectacle de fusées quand un champion compétitif est couronné |
| `fireworksZoneId` | chaîne | `""` | la zone CheeseGuard dans laquelle le spectacle a lieu |
| `fireworksCount` | entier | `12` | fusées à lancer |
| `fireworksIntervalMs` | entier | `800` | délai entre deux fusées |
| `fireworksDurationSec` | entier | `12` | plafond strict sur la durée du spectacle |

### `startingCap`

Lu à chaque connexion par `CapStorage.ensureExists`, donc le changer n'affecte que les
**nouveaux** joueurs. Les entrées existantes conservent leur `capLevel` stocké — utilise
`/cobblecap reset <player>` pour ramener un joueur à la nouvelle valeur.

Il n'est **pas réglable via l'API** : `GET /config` le renvoie, `PUT /config` l'ignore.
`/cobblecap reload` le relit bien en mémoire, donc un redémarrage n'est pas nécessaire, mais
rien ne rajuste rétroactivement personne.

### `enabled`

C'est plus qu'un interrupteur d'XP. À `false` :

- les trois couches d'application de l'XP retournent tôt
- le gestionnaire `BATTLE_STARTED_PRE` retourne à sa première ligne, donc **les contrôles
  d'espèces interdites et de limite d'équipe s'arrêtent aussi**
- `UseEntityCallback` cesse d'intercepter les PNJ rctmod, donc les champions se comportent
  comme des dresseurs ordinaires, sans conditions ni refus
- le traitement de `BATTLE_VICTORY` s'arrête, donc les limites cessent de monter et personne
  n'est promu

Ce qui marche encore : les blocages d'XP, les statues, les chronos, les scoreboards Conseil 4
et tout le dashboard.

`/cobblecap enable` et `/cobblecap disable` le basculent et sauvegardent immédiatement ;
`PUT /api/v1/cobblecap/config` aussi. Chaque client en ligne est resynchronisé pour que le
HUD puisse se masquer.

### `discordWebhookUrl`

Poussé dans `CobbleCapWebhook` au démarrage et de nouveau sur `/cobblecap reload` et sur un
`PUT` du dashboard. Une chaîne vide désactive la publication. Le mod poste lors d'une
victoire de champion et lors d'une promotion de Maître de la Ligue ; il n'y a aucun filtre
d'événement et aucune limitation de débit au-delà de ces déclencheurs.

### `bannedSpecies`

Mis en minuscules à la lecture comme à l'écriture, et comparé à l'**identifiant showdown**
de Cobblemon — le nom d'espèce en minuscules, donc `mewtwo`, `tapukoko`, `mrmime`. Une équipe
contenant l'un d'eux annule le combat avant qu'il commence avec :

```text
Combat annulé — Pokémon interdit dans votre équipe : Mewtwo
```

Le message liste les noms affichés ; la configuration contient les identifiants showdown. Le
contrôle tourne sur chaque combat, pas seulement sur les combats de champion, et il n'est
**pas** ignoré quand `CobbleCapMod.LEVEL_FORCE_ACTIVE` est posé — contrairement au contrôle
de limite d'équipe, qui l'est.

`findBannedSpeciesInParty` échoue en mode ouvert : si l'équipe ne peut pas être lue par
réflexion, il renvoie une liste vide et le combat se poursuit. Une mise à jour de Cobblemon
qui casse la réflexion sur l'équipe désactive donc silencieusement la liste d'interdiction.

### Les clés de feux d'artifice

Les cinq alimentent `FireworksManager`, qui lance des fusées dans une zone CheeseGuard quand
un champion **compétitif** est couronné. CheeseGuard est atteint par réflexion, donc en son
absence le spectacle est ignoré avec une ligne de debug et rien d'autre ne se produit.

Les setters plafonnent, et ils plafonnent silencieusement — un `PUT` du dashboard annonce un
succès avec la valeur que tu as envoyée tout en stockant la valeur plafonnée :

| Clé | Plafonnement |
|---|---|
| `fireworksCount` | `max(1, v)` |
| `fireworksIntervalMs` | `max(50, v)` |
| `fireworksDurationSec` | `max(1, v)` |

Rien ne plafonne la borne **supérieure**. `fireworksCount: 10000` avec
`fireworksIntervalMs: 50` tentera de tirer dix mille fusées, borné seulement par
`fireworksDurationSec` — le spectacle vérifie l'échéance avant chaque fusée et s'arrête :

```text
[CobbleCap] Fireworks show hit duration cap at rocket 240/10000
```

`fireworksDurationSec` est donc la véritable soupape de sécurité, et le nombre de fusées
effectif vaut `min(fireworksCount, durationSec × 1000 / intervalMs)`. Garde la durée basse.

Le spectacle tourne sur **son propre thread**, en dormant entre les fusées et en revenant
sur le thread serveur avec `server.execute` pour chaque apparition. Un garde de réentrance
coalesce une seconde requête pendant qu'une est en cours :

```text
[CobbleCap] Fireworks show already running — new request coalesced
```

Un identifiant de zone non résolvable abandonne avec un avertissement :

```text
[CobbleCap] Fireworks zone 'league_arena' not found via CheeseGuard — show aborted
```

Règle le tir avec `POST /api/v1/cobblecap/fireworks/test`, qui ignore `fireworksEnabled`
mais exige quand même un identifiant de zone.

### Rechargement à chaud

`/cobblecap reload` relit ce fichier et `champions.json`. Un `PUT` du dashboard l'écrit. Une
modification à la main pendant que le serveur tourne sera **écrasée** par le prochain appel
de setter, puisque `CapConfig` garde la copie autoritaire en mémoire et que `save()` écrit
l'objet entier. Édite, puis `/cobblecap reload`, dans cet ordre.

### Exemple complet annoté

```json
{
  "startingCap": 20,

  "enabled": true,

  "discordWebhookUrl": "https://discord.com/api/webhooks/0000/xxxx",

  "bannedSpecies": [
    "mewtwo",
    "arceus",
    "rayquaza"
  ],

  "fireworksEnabled": true,
  "fireworksZoneId": "league_arena",
  "fireworksCount": 12,
  "fireworksIntervalMs": 800,
  "fireworksDurationSec": 12
}
```

JSON n'a pas de commentaires. Les annotations de chaque clé sont dans le tableau ci-dessus ;
copie le bloc tel quel.

---

## `config/cobblecap/champions.json`

**Créé vide au premier démarrage.** C'est le fichier qui fait que le mod fait quelque chose,
et le mod ne livre aucun contenu pour lui :

```json
{
  "champions": []
}
```

Avec une liste vide, aucun champion n'est jamais détecté, aucune limite ne monte jamais
d'elle-même, et `/cobblecap` annonce à chaque joueur
`Tous les champions ont été vaincus !` — parce que `nextUnbeaten` sur une liste vide ne
renvoie rien.

Le tableau est **ordonné**, et l'ordre est la progression. Chaque champ et sa valeur par
défaut sont documentés dans [le système de champion](champions.fr.md#le-modèle) ; `id` doit
correspondre exactement à l'identifiant de dresseur rctmod.

### Exemple complet annoté

Trois entrées : une première arène sans condition, une arène plus tardive conditionnée au
Pokédex et à un scoreboard, et le champion final.

```json
{
  "champions": [
    {
      "id": "gym_leader_pierre_0395",
      "displayName": "Pierre",
      "capGranted": 30,
      "requiredPokedex": 0,
      "requiredQuestId": "",
      "victoryQuestId": "1A2B3C4D5E6F7081",
      "challengeId": "gym_leader_pierre_challenge",
      "challengeQuestId": "",
      "challengeCooldownHours": 24,
      "allowRefight": false
    },
    {
      "id": "gym_leader_erika_0399",
      "displayName": "Erika",
      "capGranted": 50,
      "requiredPokedex": 80,
      "requiredScoreboard": "alfred_main_progress",
      "requiredScoreMin": 12,
      "challengeId": "gym_leader_erika_challenge",
      "challengeRequiredScoreMin": 20,
      "challengeCooldownHours": 24,
      "challengeCapGranted": 0,
      "allowRefight": false
    },
    {
      "id": "champion_regis_challenge",
      "displayName": "Régis",
      "capGranted": 100,
      "challengeId": "",
      "allowRefight": true
    }
  ]
}
```

Ce qu'il faut savoir de cet exemple :

- `displayName` est ce que voient les joueurs **et** ce dont la clé d'advancement est
  dérivée : minuscules, espaces en tirets bas. `"Major Bob"` devient
  `cobblecap:champion/major_bob`, qui doit exister comme advancement de datapack sinon
  l'octroi ne fait rien.
- `capGranted: 100` avec un `challengeId` vide marque une entrée **post-game** : elle est
  ignorée par `hasAllNormalChampionsBeaten`, ce qui permet au champion final de figurer dans
  la même liste sans bloquer les déblocages de défi.
- `requiredScoreMin` et `challengeRequiredScoreMin` ne sont lus que quand
  `requiredScoreboard` est non vide. Les deux conditionnent sur le **même** objectif.
- `allowRefight: false` rend le combat unique ; `true` permet de réaffronter un champion
  battu, même si `CapListener` refuse quand même l'interaction une fois le champion battu et
  sans défi débloqué, ce qui restreint l'effet pratique de `true`.
- l'identifiant `champion_regis_challenge` est codé en dur à quatre endroits comme « le
  champion final ». Voir
  [ce que tu dois changer](champions.fr.md#ce-que-tu-dois-changer-pour-ton-propre-serveur).

### Comment il est écrit

`/cobblecap reload` le relit. `PUT /api/v1/cobblecap/champions` **remplace la liste
entière** et réécrit le fichier — il n'y a ni fusion ni retour en arrière, donc fais
toujours un `PUT` du résultat d'un `GET` avec tes modifications appliquées.

Un échec d'analyse laisse la liste en mémoire intacte et journalise :

```text
[CobbleCap] Failed to load champions.json: <message>
```

La réponse de chat à `/cobblecap reload` annonce un compte. Un compte de `0` juste après
avoir édité un fichier rempli signifie que l'analyse a échoué — lis le log.

Noter que `ChampionEntry.toJson` omet les champs conditionnels, donc un `GET` suivi d'un
`PUT` est sans perte mais pas identique octet pour octet à un fichier écrit à la main :
`requiredScoreboard` et ses voisins disparaissent quand ils sont vides,
`challengeRequiresAllBeaten` n'apparaît que s'il vaut `false`, et `challengeCapGranted`
seulement s'il est supérieur à zéro.

---

## Fichiers d'état

N'édite pas ceux-ci à la main pendant que le serveur tourne. Chaque manager garde la copie
autoritaire en mémoire et t'écrasera.

### `config/cobblecap/players.json`

Un objet plat indexé par UUID :

```json
{
  "f84c6a79-0a4e-45e0-aa79-5d0ba2f4a2b1": {
    "uuid": "f84c6a79-0a4e-45e0-aa79-5d0ba2f4a2b1",
    "playerName": "Steve",
    "capLevel": 45,
    "championsBeaten": ["gym_leader_pierre_0395"],
    "challengeBeaten": [],
    "hudVisible": true,
    "hudPosition": 0,
    "expLocked": false,
    "lastCapChange": 1778000000000,
    "lastChallengeTimes": {}
  }
}
```

Les écritures sont **différées** : `saveAsync()` lève un drapeau et planifie une sauvegarde
complète 500 ms plus tard sur un thread démon nommé `CobbleCap-Save`, condensant une rafale
de changements en une seule écriture. L'écriture elle-même est atomique via
`players.json.tmp` et un `Files.move`, en privilégiant `ATOMIC_MOVE` et en se rabattant sur
un remplacement simple. Un `save()` synchrone tourne sur `SERVER_STOPPING`.

⚠️ Le différé de 500 ms signifie qu'un crash brutal peut perdre jusqu'à une demi-seconde de
changements — une hausse de limite, réalistement. Un arrêt propre ne perd rien. Une entrée
qui échoue à l'analyse est ignorée avec une erreur et le reste du fichier se charge quand
même.

### `config/cobblecap_legends.json`

Le Panthéon. Forme et sémantique dans
[le système de champion](champions.fr.md#le-panthéon). En ajout seul en usage normal ; les
seules choses qui retirent des lignes sont `/cobblecap statue test_clear` et
`/cobblecap chrono wipe`.

### `config/cobblecap_tenures.json`

Les règnes de champion compétitif. Au plus une entrée n'a pas d'`endedAt`, et c'est le
champion courant.

```json
{
  "tenures": [
    {
      "id": "7c1e...",
      "championUuid": "f84c6a79-...",
      "championName": "Steve",
      "startedAt": 1778000000000,
      "bestTimeMs": 1122000,
      "failedAttempts": [
        {"uuid": "9b2d...", "playerName": "Alex", "count": 3, "lastFailAt": 1778100000000}
      ]
    }
  ]
}
```

Contrairement aux autres fichiers d'état, celui-ci n'est **pas** écrit de façon atomique —
`TenureStorage.save` écrit directement dans le chemin cible. Un crash en pleine écriture peut
le tronquer. `saveAsync` crée aussi un `new Thread` nu par appel au lieu d'utiliser un pool.

### `config/cobblecap_chronos.json`

Les chronos en cours, UUID vers horodatage de départ :

```json
{
  "f84c6a79-0a4e-45e0-aa79-5d0ba2f4a2b1": 1778000000000
}
```

Persisté pour qu'une course survive à un crash, et elle continue de défiler depuis son
départ d'origine — la pendule n'est **pas** mise en pause pendant que le serveur est arrêté.
Si tu redémarres pendant une course, annule les chronos concernés.

### `config/cobblecap_dioramas.json`

Les ancres de diorama dynamique du champion. Forme dans
[statues et dioramas](statues.fr.md#le-diorama-dynamique-du-champion). Vidé par
`/cobblecap statue wipe_all`.

---

## `config/cobblecap_client.properties`

Côté client, une seule clé, écrite par la commande `/pasturehide` :

```properties
#CobbleCap client preferences
pasture_pokemon_hidden=false
```

Toute valeur autre que `true` est lue comme `false` (`Boolean.parseBoolean`). Un fichier
absent n'est pas une erreur — le défaut est `false`. Détails dans
[le côté client](client.fr.md#pasturehide).

---

## Ce qui n'est pas configurable

À dire clairement, parce que plusieurs de ces éléments ont l'air de devoir l'être :

| Élément | Où il est figé |
|---|---|
| le nom de datapack `CacademyMons` | `RctTrainerMorpher.java` ligne 57 — **la seule modification dont tu auras presque certainement besoin** |
| les chemins et l'identifiant du dresseur morphé | `RctTrainerMorpher.java` lignes 52, 55, 56 |
| l'identifiant du champion final `champion_regis_challenge` | codé en dur dans quatre méthodes |
| les noms d'objectifs Conseil 4 | les constantes de `Conseil4Boards` |
| les noms de fichiers d'export CobbleCheeseScore | `Conseil4Boards.export(...)` |
| le nom d'objectif `cobblecap_mode` | `Conseil4Boards`, `ChampionManager`, `CobbleCapCommand` |
| le cooldown de 30 secondes du message d'XP | `CapManager.XP_MSG_COOLDOWN_MS` |
| le cooldown de 2 secondes des messages d'avant-combat | `ChampionManager.shouldSendMessage` |
| la taille, la marge et la texture du badge de HUD | `CapHudRenderer` |
| la disposition des dioramas (3,5 blocs en arrière, 2 d'écart) | `StatueManager.placeFullDiorama` |

Les trois champs de chemin de `RctTrainerMorpher` sont `public static` et non `final`, et un
commentaire au-dessus d'eux affirme qu'ils sont « Configurable from CapConfig ». **Ce
commentaire est faux** — aucune clé de configuration ne les lit ni ne les écrit. Les changer
veut dire éditer le source et reconstruire.

---

← [Le côté client](client.fr.md) · [Architecture](architecture.fr.md) →
