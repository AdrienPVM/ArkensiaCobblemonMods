[← CheeseGuard](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](configuration.md)

# Configuration

CheeseGuard n'a pas de fichier de configuration unique. Il a **quatorze** emplacements
qu'il lit et écrit sous le dossier `config/` du serveur, un par sous-système, et presque
tous relèvent de l'état plutôt que du réglage : les zones, les événements, les prisonniers
et les dimensions sont créés depuis le dashboard ou par commande, et ces fichiers sont là
où le mod les garde entre deux démarrages.

Cela détermine la façon de les traiter. Seuls quatre fichiers méritent une édition à la
main (`cheeseguard_webhook.json`, `cheeseguard_event_launch.json`,
`cheeseguard_pastures.json`, `cheeseguard_fish_pokemon.json`). Les autres appartiennent au
serveur en marche, qui les réécrit à la prochaine sauvegarde ; ne les édite qu'avec le
serveur arrêté, et garde une copie.

## Tous les fichiers d'un coup d'œil

| Chemin | Écrit par | Contenu | Éditable à la main |
|---|---|---|---|
| `config/cheeseguard/regions.json` | `RegionStorage` | toutes les zones et tous les groupes, avec flags, membres, priorités, configurations de vente | serveur arrêté seulement |
| `config/cheeseguard/chunks.json` | `ChunkSetStorage` | les sets de chunks et leurs plannings de remise à zéro | serveur arrêté seulement |
| `config/cheeseguard/chunk_suggestions.json` | `ChunkSuggestionStorage` | les suggestions des joueurs via `/chunksuggest` | serveur arrêté seulement |
| `config/cheeseguard/tag_configs.json` | `TagConfigStorage` | les plafonds de possession de zones par tag, utilisés par `/cg buy` | serveur arrêté seulement |
| `config/cheeseguard/prison.json` | `PrisonManager` | identifiant de la zone de prison, point de sortie, message d'accueil, prisonniers actifs, historique | serveur arrêté seulement |
| `config/cheeseguard_dimensions.json` | `DimensionManager` | les configurations de dimension, y compris celles par joueur | serveur arrêté seulement |
| `config/cheeseguard_events.json` | `EventManager` | toutes les définitions d'événement, avec actes, effets et timelines | serveur arrêté seulement |
| `config/cheeseguard_scores.json` | `ScoreManager` | résultats d'événements, meilleurs scores de minijeux, statistiques par joueur | en lecture seule, par conception |
| `config/cheeseguard_event_launch.json` | `EventLaunchConfig` | les limites de débit et la fenêtre horaire des événements lancés par les joueurs | **oui** |
| `config/cheeseguard_pastures.json` | `PastureLimitsManager` | les deux plafonds de pâturages par chunk | **oui** |
| `config/cheeseguard_webhook.json` | toi | l'URL du webhook Discord | **oui** |
| `config/cheeseguard_fish_pokemon.json` | `PokemonFishingTable` | la table pondérée d'espèces du minijeu de pêche | **oui** |
| `config/cheeseguard_prophunt_icons.json` | `PropHuntIconStorage` | la correspondance clé d'icône → `custom_model_data` | non |
| `config/cheeseguard_pending_reverts.json` | `CobbleShiftHook` | les transformations CobbleShift encore dues à un retour après un plantage | non |

Et neuf dossiers :

| Chemin | Contenu |
|---|---|
| `config/cheeseguard_templates/` | les templates d'événement, un JSON par template. **Pas** `config/templates/` |
| `config/cheeseguard_states/<zoneId>/` | les états de zone sauvegardés, avec un `index.json` par zone |
| `config/cheeseguard_snapshots/` | les captures de `/cg event snapshot`, un `.nbt` par identifiant |
| `config/snapshots/<dimId>/` | les snapshots de dimension, utilisés par la remise à zéro d'une dimension. Note le nom : sans préfixe `cheeseguard_` |
| `config/cheeseguard_racetracks/` | les définitions de circuit, un JSON par identifiant d'événement |
| `config/cheeseguard_inv_archive/` | les archives d'inventaire, la source de `/cg restoreinv` |
| `config/cheeseguard_pending_inv/` | les instantanés d'inventaire des joueurs actuellement dans un événement — le chemin de récupération après plantage |
| `config/cheeseguard_clearinv_snapshots/` | les instantanés pris par le vidage d'inventaire à l'entrée d'une dimension |
| `config/cheeseguard_assets/prophunt/` | les icônes de props envoyées, en `.png`, nommées par clé |

⚠️ Une incohérence à connaître. Les cinq fichiers sous `config/cheeseguard/` résolvent leur
chemin avec `Paths.get("config", "cheeseguard")` — **relativement au dossier de travail du
processus**, pas au dossier de configuration de Fabric. Tout le reste part de
`server.getRunDirectory()` ou de `FabricLoader.getConfigDir()`. Sur un lancement normal, où
le dossier de travail est la racine du serveur, les deux coïncident. Lance le serveur avec
un autre dossier de travail et CheeseGuard écrira tes zones dans un `config/` auquel tu ne
t'attendais pas, et démarrera sans aucune d'elles. Si des zones disparaissent après un
changement d'hébergement, c'est la raison.

---

## `cheeseguard_webhook.json`

Le seul fichier que tu dois créer toi-même. Le mod le lit une fois, à `SERVER_STARTED`.

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `webhook_url` | chaîne | `""` | le webhook Discord vers lequel le mod poste. Vide ou absent désactive toute publication Discord |

```json
{
  "webhook_url": "https://discord.com/api/webhooks/000000000000000000/EXAMPLE-WEBHOOK-do-not-use"
}
```

Lu au démarrage uniquement. Une modification exige un redémarrage. Un fichier absent n'est
ni une erreur ni un avertissement : la publication Discord est simplement coupée, ce qui
rend une faute dans le nom du fichier difficile à remarquer. Cherche
`Failed to load webhook config` dans le log si tu attendais des publications et n'en as
aucune — et note qu'un fichier *absent* ne produit aucune ligne du tout.

L'URL est un secret. Quiconque la détient peut publier dans ton salon au nom du serveur.

---

## `cheeseguard_event_launch.json`

La politique de `/cg event launch`, la commande qui laisse les joueurs démarrer des
événements eux-mêmes. Chargée quand `EventManager.init` tourne au démarrage du serveur, et
réécrite dès que le dashboard la modifie. Un fichier absent laisse chaque valeur ci-dessous
à son défaut, et le fichier n'est créé que lorsque quelque chose le sauvegarde.

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `playerLimitCount` | entier | `0` | lancements qu'un joueur peut faire par période. `0` signifie illimité |
| `playerLimitPeriod` | chaîne | `"DAY"` | `HOUR`, `DAY` ou `WEEK`. Toute autre valeur est ramenée à `DAY` |
| `globalLimitCount` | entier | `0` | lancements que tout le serveur peut faire par période. `0` signifie illimité |
| `globalLimitPeriod` | chaîne | `"DAY"` | les trois mêmes valeurs |
| `timeWindowEnabled` | booléen | `false` | quand vrai, les lancements sont refusés hors de la fenêtre |
| `timeWindowStartHour` | entier | `8` | première heure de la fenêtre, 0–23, bornée |
| `timeWindowEndHour` | entier | `22` | première heure **hors** de la fenêtre, 0–23, bornée |

La fenêtre est semi-ouverte : `8`–`22` autorise un lancement à 21 h 59 et refuse celui de
22 h 00. Un début supérieur à la fin passe par minuit : `22`–`6` est donc une fenêtre de
nuit. Mettre les deux à la même heure ne ferme **pas** la fenêtre : des heures égales
passent par la branche « passage de minuit » (`hour >= start || hour < end`), vraie à
toute heure, donc chaque lancement est autorisé. Il n'existe aucun moyen de fermer
complètement la fenêtre, sauf à refuser le lancement par les joueurs sur les événements
eux-mêmes (`playerLaunchable`).

Les deux limites sont des fenêtres glissantes sur les horodatages de lancement enregistrés,
pas des compartiments de calendrier : avec `playerLimitCount: 2` et la période `DAY`, un
joueur qui a lancé deux fois à 20 h 00 peut relancer à 20 h 01 le lendemain, pas à minuit.
Les horodatages sont gardés en mémoire et purgés au bout d'une semaine, donc **un
redémarrage efface tous les lancements enregistrés** et les limites repartent de zéro.
C'est un vrai manque si tu comptes dessus pour cadencer un événement.

Les opérateurs de niveau 2 contournent la fenêtre et les deux limites. Le
`cooldownMinutes` propre à chaque événement n'est pas dans ce fichier — il vit sur
l'événement, dans `cheeseguard_events.json`, et il s'applique aussi aux opérateurs.

```json
{
  "playerLimitCount": 2,
  "playerLimitPeriod": "DAY",
  "globalLimitCount": 10,
  "globalLimitPeriod": "DAY",
  "timeWindowEnabled": true,
  "timeWindowStartHour": 14,
  "timeWindowEndHour": 23
}
```

---

## `cheeseguard_pastures.json`

Des plafonds sur les blocs de pâturage Cobblemon, appliqués au moment de la pose.

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `maxPerChunk` | entier | `1` | pâturages autorisés dans un chunk, tous propriétaires confondus |
| `maxPerChunkPerPlayer` | entier | `1` | pâturages qu'un joueur peut posséder dans un chunk |

Les deux sont bornés à 0 et n'ont pas de maximum. **`0` désactive ce plafond** — il veut
dire « illimité », pas « interdit » (`PastureLimitsManager` ne vérifie qu'un plafond
supérieur à 0). Avec les deux à `0`, CheeseGuard ne limite plus du tout les pâturages.

Les deux sont vérifiés indépendamment : le plafond effectif pour un joueur seul est donc le
plus petit des deux. `maxPerChunk: 4` avec `maxPerChunkPerPlayer: 1` veut dire que quatre
joueurs peuvent en poser un chacun.

Les modifications faites avec `/cg pasture chunk` ou `/cg pasture player` réécrivent le
fichier aussitôt et prennent effet à la prochaine pose. Une édition à la main exige un
redémarrage, puisque le fichier n'est lu qu'au démarrage du serveur. Un fichier absent est
créé avec les valeurs par défaut lors de ce premier chargement.

```json
{
  "maxPerChunk": 4,
  "maxPerChunkPerPlayer": 2
}
```

---

## `cheeseguard_fish_pokemon.json`

La table de butin du minijeu de pêche, dans ses modes REALISTIC et ARCADE. Contrairement
aux autres fichiers, celui-ci **est créé avec des valeurs par défaut utilisables** la
première fois que le mod le charge et le trouve absent : la table livrée est donc dans le
fichier, pas seulement dans le code.

La structure est une liste de paliers, chacun avec une valeur en points, un poids et une
liste d'espèces :

```json
{
  "tiers": [
    {
      "name": "common",
      "points": 1,
      "weight": 100,
      "pokemon": [
        { "id": "magikarp", "name": "Magicarpe" },
        { "id": "tentacool", "name": "Tentacool" }
      ]
    },
    {
      "name": "legendary",
      "points": 50,
      "weight": 1,
      "pokemon": [
        { "id": "lugia", "name": "Lugia" }
      ]
    }
  ]
}
```

| Clé | Type | Effet |
|---|---|---|
| `tiers[].name` | chaîne | l'étiquette du palier montrée aux joueurs |
| `tiers[].points` | entier | points accordés pour n'importe quelle espèce de ce palier |
| `tiers[].weight` | entier | poids relatif **par espèce**, pas par palier |
| `tiers[].pokemon[].id` | chaîne | l'identifiant d'espèce Cobblemon, en minuscules |
| `tiers[].pokemon[].name` | chaîne | nom affiché ; retombe sur `id` en son absence |

La subtilité du poids compte. `weight` est stocké sur chaque entrée du palier, et le total
est la somme sur les *entrées* : un palier de poids 100 avec dix espèces a donc dix fois
plus de chances d'être tiré qu'un palier de poids 100 avec une seule espèce. Pour rendre un
palier plus rare, baisse son poids ou réduis sa liste d'espèces.

Le mod n'écrit ce fichier que lorsqu'il est absent (les valeurs par défaut intégrées sont
sauvegardées lors de ce premier chargement) ; il ne réécrit jamais un fichier que tu as
édité.

La table est chargée par `PokemonFishingTable.reload`, que seul `EventManager.init` appelle,
au démarrage du serveur. Rien dans le dashboard ne la recharge : une édition à la main exige
donc un redémarrage. Un fichier mal formé journalise
`Failed to load fish pokemon table` et retombe sur les valeurs par défaut intégrées : une
erreur de syntaxe te rend donc silencieusement la table d'origine.

---

## Les fichiers d'état

Ceux-ci sont écrits par le serveur et documentés là où leur modèle est expliqué. Les
éditer à la main est un dernier recours.

### `config/cheeseguard/regions.json`

Deux objets de premier niveau, `regions` et `groups`, chacun indexé par identifiant :

```json
{
  "regions": {
    "market_square": {
      "name": "Market Square",
      "world": "minecraft:overworld",
      "minX": 118, "minY": 64, "minZ": -302,
      "maxX": 142, "maxY": 90, "maxZ": -276,
      "group": "public_areas",
      "priority": 0,
      "owner": { "uuid": "…", "name": "RoiCheese", "role": "OWNER" },
      "members": [ { "uuid": "…", "name": "Steve", "role": "MEMBER" } ],
      "flags": { "block-break": false, "pvp": false }
    }
  },
  "groups": {
    "public_areas": { "name": "Public Areas", "flags": {}, "regions": ["market_square"] }
  }
}
```

L'exemple est abrégé : chaque zone porte aussi ses messages d'entrée, de sortie et de
protection, sa couleur de particules et, quand ils sont définis, `parent`, ses tags, ses
exigences de progrès et de quêtes, ses commandes d'entrée et de sortie et un `saleConfig`.
La clé `group` est omise quand la zone n'a pas de groupe, et le propriétaire est stocké à
part de `members`.

Un flag absent de l'objet `flags` est *hérité*, ce qui n'est pas la même chose que `false`.
La sémantique complète, la résolution par priorité et les champs de vente sont dans
[zones et flags](zones-and-flags.fr.md) et [les groupes](groups.fr.md).

Chaque mutation — un flag, un membre — réécrit tout le fichier de façon synchrone sur le
thread serveur. Avec quelques milliers de zones, cela fait un accroc mesurable sur un
serveur chargé.

### `config/cheeseguard_dimensions.json`

Une entrée par dimension gérée, y compris les copies par joueur dont l'identifiant a la
forme `<masterDimId>_<uuid8>`. Type de génération, bordure, point d'apparition (ce
qu'écrit `/cg setspawn`), règles d'entrée, conservation de l'inventaire et déclencheur de
snapshot vivent tous ici. Voir [les dimensions](dimensions.fr.md).

### `config/cheeseguard_events.json`

Tous les événements : état, déclencheur, mode d'équipe, phases, actes, effets,
participants, autorisations de lancement et cooldown. Voir
[les événements](events.fr.md) et [la timeline](timeline.fr.md).

### `config/cheeseguard/chunks.json`

Les sets de chunks. Chaque set nomme une dimension et un planning de remise à zéro :

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `dimension` | chaîne | `"minecraft:overworld"` | la dimension à laquelle appartient le set |
| `resetTrigger` | chaîne | `"MANUAL"` | `MANUAL` ou `SCHEDULED` |
| `resetHour` | entier | `6` | heure de la remise à zéro planifiée, 0–23, heure locale du serveur |
| `resetMinute` | entier | `0` | minute, 0–59 |
| `resetDays` | liste de chaînes | `[]` | noms de jours, `MONDAY`…`SUNDAY`. Vide signifie tous les jours |
| `lastAutoReset` | entier long | `0` | millisecondes epoch de la dernière remise à zéro automatique |

Le planificateur vérifie tous les 600 ticks, exige l'heure exacte et la minute à une près,
et refuse de se déclencher deux fois en moins de 90 secondes. `lastAutoReset` est sa
mémoire ; l'effacer à la main peut provoquer une seconde remise à zéro immédiate. Les
remises à zéro suppriment des fichiers de région après sauvegarde — voir
[les chunks](chunks.fr.md).

### `config/cheeseguard/prison.json`

L'identifiant de la zone de prison, le point de sortie, le message d'accueil, les
prisonniers actifs et jusqu'à 500 entrées d'historique. Le message d'accueil par défaut est
une chaîne **française** codée en dur dans `PrisonManager`, avec des codes couleur `§` et
les placeholders `{reason}` et `{time}` :

```text
§c⛓ Vous êtes en prison.
§7Raison: §f{reason}
§7Temps restant: §f{time}
```

Il n'est pas traduit, parce que c'est une donnée de configuration et non une clé de langue.
Remplace-le depuis le dashboard si tes joueurs ne sont pas francophones. Le mettre à la
chaîne vide ne l'effacera pas — le setter conserve alors la valeur précédente.

### `config/cheeseguard/tag_configs.json`

Les plafonds de possession par tag consultés par `/cg buy` : combien de zones portant un
tag donné un joueur peut posséder. Écrit quand un tag est créé, modifié ou supprimé depuis
le dashboard ; un achat se contente de le lire.

### `config/cheeseguard_scores.json`

Résultats d'événements, meilleurs scores de minijeux et agrégats par joueur. Le commentaire
de `ScoreManager` dit que le fichier existe pour que des mods de scoreboard externes
puissent le lire, ce que fait CobbleCheeseScore. Traite-le comme une sortie.

### `config/cheeseguard_prophunt_icons.json`

Un compteur `nextCmd` et un objet `mapping` reliant une clé de prop à une valeur
`custom_model_data`, associés aux fichiers `.png` de
`config/cheeseguard_assets/prophunt/`. Le compteur est monotone et jamais réutilisé :
supprimer un prop laisse donc un trou. Géré entièrement depuis le dashboard.

### `config/cheeseguard_pending_reverts.json`

Les transformations CobbleShift appliquées par un événement et pas encore annulées, afin
qu'un plantage en pleine partie ne laisse pas un joueur transformé pour toujours. Écrit par
`CobbleShiftHook`, lu au démarrage du serveur. Ne le supprime que si tu es certain
qu'aucun joueur n'est encore transformé.

---

## Ce qui n'est pas configurable

- **Les niveaux de permission.** Ils sont compilés dans l'arbre Brigadier. Tu ne peux pas
  changer le niveau de `/cg flag` depuis un fichier ; voir
  [le tableau des gardes de commande](commands.fr.md#niveaux-de-permission).
- **Les défauts des flags.** Un flag qu'aucune zone, aucun groupe ni aucune dimension ne
  pose est toujours *autorisé* — il n'existe pas de valeur par défaut propre à chaque flag.
  Le fait que les membres soient exemptés ou non d'un flag est le champ `memberExempt` de
  l'énumération `Flag`. Changer l'un ou l'autre suppose de recompiler. (Ce qui se rapproche
  le plus d'un défaut configurable, ce sont les `deniedFlags` d'une dimension, dans
  `cheeseguard_dimensions.json`.)
- **Les ports.** CheeseGuard ne sert aucun HTTP en propre. Ses routes dashboard tournent
  dans CheeseDash, sur le port de CheeseDash — configuré dans `config/cheesedash.json`.
- **La bascule du hot potato.** `HotPotatoHandler` la garde dans un champ statique. Elle
  est désactivée à chaque démarrage.

---

← [Événements, prison et exploitation](commands-operations.fr.md) · [Zones et flags](zones-and-flags.fr.md) →
