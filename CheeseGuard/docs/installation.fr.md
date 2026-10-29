[← CheeseGuard](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](installation.md)

# Installation

## Prérequis

| | Version | Requis sur |
|---|---|---|
| Minecraft | 1.21.1 exactement — `~1.21.1` dans `fabric.mod.json` | les deux |
| Fabric Loader | ≥ 0.15.11 | les deux |
| [Fabric API](https://modrinth.com/mod/fabric-api) | n'importe laquelle — compilé contre 0.100.4+1.21 | les deux |
| `fabric-resource-loader-v0` | n'importe laquelle — fourni avec Fabric API | les deux |
| Java | 21 ou plus | les deux |
| [CheeseDash](../../CheeseDash/) | une version correspondante | serveur |

CheeseGuard déclare `"environment": "*"` et enregistre un entrypoint `client` : **le jar
va sur le serveur et devrait aussi aller sur le client**. La répartition :

- **Côté serveur** — tout. Zones, flags, événements, dimensions, prison, commandes, API
  dashboard. Le mod est pleinement fonctionnel avec le jar sur le serveur seulement.
- **Côté client** — quatre choses, et rien d'autre. Les flags `nametag-hide` et
  `minimap-hide` (appliqués par des mixins de rendu côté client, ils ne peuvent pas
  fonctionner autrement), les HUD de vote et de prop hunt, et le menu de propriétaire de
  zone lié à une touche. Un joueur sans le mod voit les nametags et sa minimap dans une
  zone censée les masquer, et n'a pas de menu. Rien ne casse ; ces fonctions sont
  simplement absentes pour lui.

⚠️ **CheeseDash est une dépendance obligatoire.** Il est dans `depends`, pas dans
`suggests`. CheeseGuard refuse de se charger sans lui, même si tu n'ouvres jamais le
dashboard.

Optionnels, chacun activé seulement en présence du mod concerné :

| Mod | Ce qu'il ajoute |
|---|---|
| [Waystones](https://modrinth.com/mod/waystones) | supprimer une dimension retire aussi ses waystones (par réflexion, `WaystonesHook`) |
| [Cobblemon](https://modrinth.com/mod/cobblemon) | les quatre flags `cobblemon-*`, les limites de pâturages, le minijeu de pêche |
| [CobbleShift](../../CobbleShift/) | quatre types d'effet d'événement qui transforment les participants |
| [CheeseKart](../../CheeseKart/) | deux types d'effet d'événement qui mettent les participants en kart |
| [CobblemonDungeons](../../CobblemonDungeons/) | trois types d'effet d'événement pour piloter les donjons |
| [CheeseStore](../../CheeseStore/) | un portefeuille pour les achats de zone |
| Numismatic Overhaul | une monnaie pour les achats de zone |
| Jade, JourneyMap | le flag `minimap-hide` masque leurs surcouches (mixins client) |
| FTB Quests | détecté au chargement ; helpers d'intégration de quêtes |

Les intégrations Jade, JourneyMap, Cobblemon et Cobblemon Spawn Alerts sont des **mixins
dans les classes de ces mods, par leur nom**, et non de la réflexion. Elles peuvent casser
quand ces mods se mettent à jour. Le risque par groupe est dans
[l'architecture](architecture.fr.md#les-44-mixins).

## Construction

CheeseGuard compile contre le jar de CheeseDash, résolu par un glob sur
`../CheeseDash/build/libs/`. **CheeseDash doit donc être construit d'abord**, sinon la
compilation échoue sur des symboles manquants.

```bash
cd CheeseDash && ./gradlew build
cd ../CheeseGuard && ./gradlew build
```

`tools/build-all.sh` parcourt tout le dépôt dans l'ordre des dépendances et reste la voie
la plus simple si tu construis plus d'un mod.

Le résultat est `build/libs/cheeseguard-1.0.0+1.21.1.jar`. La version vient de
`mod_version` dans `gradle.properties`. Deux autres jars apparaissent à côté :
`cheeseguard-1.0.0+1.21.1-sources.jar` et le jar de développement. **Ne copie ni l'un ni
l'autre** — un jar de sources dans `mods/` se charge comme un mod sans code et produit
l'échec déroutant où le mod « est installé » mais n'a aucune commande.

## Installation

Dépose le jar de CheeseDash et `cheeseguard-1.0.0+1.21.1.jar` dans le dossier `mods/` du
serveur. Supprime d'abord tout ancien jar de CheeseGuard : deux versions dans `mods/` est
une erreur de chargement, pas une mise à jour silencieuse.

Pour les fonctions client, place le même jar dans le `mods/` du client, aux côtés de
Fabric API. CheeseDash n'est pas nécessaire sur le client.

## Ce que crée le premier démarrage

CheeseGuard crée `config/cheeseguard/` immédiatement — ses classes de stockage le font
dans leurs constructeurs — mais n'y écrit rien avant qu'une sauvegarde ait lieu. À
`SERVER_STARTED`, quelques éléments de plus sont écrits d'office : un premier démarrage
sans zone te laisse donc avec :

```text
config/
├── cheeseguard/                      (vide)
├── cheeseguard_assets/prophunt/      (vide)
├── cheeseguard_states/               (vide)
├── cheeseguard_dimensions.json       les mondes chargés, enregistrés d'office comme dimensions intégrées
├── cheeseguard_fish_pokemon.json     la table de pêche par défaut
└── cheeseguard_pastures.json         les deux plafonds de pâturages par défaut
```

Tout le reste apparaît à l'usage : `regions.json` à la première zone,
`cheeseguard_events.json` au premier événement, et ainsi de suite.
`cheeseguard_dimensions.json` est écrit à chaque démarrage, parce que
`DimensionManager.init` enregistre chaque monde chargé qui n'a pas encore de configuration
(l'Overworld, le Nether, l'End et toute dimension moddée) puis sauvegarde.

**`config/cheeseguard_webhook.json` n'est jamais créé pour toi.** Si tu veux des
notifications Discord, tu l'écris toi-même, avec la structure donnée dans
[la configuration](configuration.fr.md#cheeseguard_webhookjson).

L'inventaire complet des fichiers et dossiers, et lesquels sont éditables à la main, est
dans [la configuration](configuration.fr.md).

## Vérifier qu'il est chargé

Une ligne dans le log du serveur, émise par le logger `CheeseGuard`, à la fin de
`onInitialize` :

```text
[CheeseGuard] CheeseGuard loaded!
```

Avec FTB Quests également installé, elle devient :

```text
[CheeseGuard] CheeseGuard loaded! (FTB Quests detected)
```

Cette ligne signifie que le mod s'est initialisé. Elle ne signifie **pas** que les
gestionnaires dépendant du serveur sont prêts : `EventManager`, `DimensionManager` et
`ZoneStateManager` ne s'initialisent qu'à `SERVER_STARTED`, plus tard dans le démarrage.
Les autres lignes du même logger portent un préfixe `[CG]` dans le message, ce qui permet
de repérer l'activité à l'exécution :

```text
[CheeseGuard] [CG] Fish Pokemon table loaded: 39 entries
[CheeseGuard] [CG] EventManager: 0 events, EC=false, SB=false
```

(La ligne de la table de pêche n'apparaît qu'une fois le fichier créé, donc à partir du
deuxième démarrage ; 39 est la taille de la table par défaut.)

En jeu, en tant qu'opérateur :

```mcfunction
/cg
```

```text
[CheeseGuard] Administration disponible sur le dashboard web CheeseDash.
```

Si tu obtiens *Unknown or incomplete command*, le mod n'est pas sur le serveur. Souviens-toi
que la commande racine est `/cg` — il n'y a pas de `/cheeseguard`.

Vérifie ensuite le côté dashboard :

```bash
curl -H "Authorization: Bearer EXAMPLE-TOKEN-do-not-use" \
     http://localhost:44311/api/v1/cheeseguard/status
```

Un `503 {"error":"CheeseGuard not ready"}` signifie que le module est enregistré mais que
le serveur n'a pas fini de démarrer — attends et réessaie. Un 404 signifie que CheeseDash
n'a pas récupéré l'entrypoint, cas traité dans
[le dépannage](troubleshooting.fr.md#la-section-cheeseguard-manque-dans-le-dashboard).

## Désinstallation

Retire le jar de `mods/` sur le serveur et sur chaque client, puis redémarre.

Ce qui s'arrête aussitôt : plus aucun flag n'est appliqué, une zone protégée redevient donc
un morceau de monde ordinaire. Les prisonniers sont libérés dans le sens où plus rien ne les
confine, mais leurs peines restent sur le disque. Les événements en cours sont abandonnés en
plein état.

⚠️ **Ne désinstalle pas avec des joueurs en pleine partie.** Un événement qui a vidé un
inventaire le restitue quand le joueur part. Retirer le mod retire le code qui ferait cette
restitution, et les inventaires restent dans `config/cheeseguard_pending_inv/` sous forme de
NBT que personne ne lit. Arrête tous les événements d'abord, ou réinstalle le mod et lance
`/cg event leave` pour chaque joueur concerné avant de le retirer définitivement.

Ce qui reste derrière, rien de nuisible :

- `config/cheeseguard/` — zones, groupes, sets de chunks, suggestions, tags, prison
- `config/cheeseguard_*.json` — dimensions, événements, scores, politique de lancement,
  pâturages, webhook, table de pêche, icônes de props, retours en attente
- `config/cheeseguard_templates/`, `config/cheeseguard_states/`,
  `config/cheeseguard_snapshots/`, `config/snapshots/`,
  `config/cheeseguard_racetracks/`, `config/cheeseguard_inv_archive/`,
  `config/cheeseguard_pending_inv/`, `config/cheeseguard_clearinv_snapshots/`,
  `config/cheeseguard_assets/`

**Les dimensions créées par le mod ne sont pas dans `config/`.** Ce sont de vrais dossiers
de monde à l'intérieur de ta sauvegarde, et ils y restent après la désinstallation.
Minecraft ne les chargera pas sans le mod qui les a enregistrées, ce qui est inoffensif mais
occupe de l'espace disque. Supprime-les à la main si tu veux récupérer la place, et garde
une sauvegarde d'abord.

Garde tout si tu envisages de réinstaller : le format des données est stable et le mod
reprend là où il s'était arrêté.

---

← [CheeseGuard](../README.fr.md) · [Premiers pas](getting-started.fr.md) →
