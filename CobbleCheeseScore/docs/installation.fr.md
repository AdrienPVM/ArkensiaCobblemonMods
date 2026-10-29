[← CobbleCheeseScore](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](installation.md)

# Installation

## Prérequis

| | Version | Remarques |
|---|---|---|
| Minecraft | 1.21.1 | `"minecraft": "~1.21.1"` dans `fabric.mod.json` |
| Fabric Loader | ≥ 0.16.0 | dépendance stricte |
| Java | 21 | `"java": ">=21"`, et le build cible la release 21 |
| Fabric API | 0.103.0+1.21.1 | le build compile contre elle ; le mod utilise `ServerLifecycleEvents` |
| [CheeseDash](../../CheeseDash/) | n'importe laquelle | **dépendance stricte** : `"cheesedash": "*"` |
| [Cobblemon](https://modrinth.com/mod/cobblemon) | n'importe laquelle | déclaré dans `suggests`, donc le mod charge sans lui. Sans lui il n'y a aucune donnée de Pokédex et la plupart des classements restent vides |
| [CobbleCap](../../CobbleCap/) | n'importe laquelle | optionnel. Nécessaire seulement pour les quatre classements Conseil 4 |

### Côté serveur uniquement

```json
"environment": "server"
```

Le seul entrypoint est `server`, qui implémente `DedicatedServerModInitializer`. Rien ici
ne tourne sur un client, et il n'y a rien à installer sur la machine d'un joueur. Deux
conséquences :

- mettre le jar dans le `mods/` d'un client ne fait rien, et ne casse rien ;
- `DedicatedServerModInitializer` n'est pas appelé sur un **serveur intégré en solo**.
  Ouvrir un monde depuis ton propre client ne démarrera pas le mod, même avec CheeseDash
  présent. Teste sur un vrai serveur dédié.

Si tu veux que les images de classement soient visibles dans le monde, le client de chaque
joueur a besoin d'un mod tiers de cadre d'image. Ce n'est pas une dépendance de ce mod et
il ne l'installe pas — voir
[export PNG](png-export.fr.md#lafficher-sur-une-carte-en-jeu).

## Ordre de build

Le mod compile contre le jar de CheeseDash, résolu par un glob et non par un nom de
fichier figé :

```groovy
modImplementation files(fileTree(dir: "../CheeseDash/build/libs",
    includes: ["cheesedash-*.jar"], excludes: ["*-sources.jar", "*-dev.jar"]))
```

CheeseDash doit donc être **construit** d'abord, et pas seulement présent. Un
`CheeseDash/build/libs` vide donne des erreurs de compilation sur
`com.cheesedash.WebDashboard` et `com.cheesedash.DashboardModule`.

```bash
cd CheeseDash && ./gradlew build
cd ../CobbleCheeseScore && ./gradlew build
```

`tools/build-all.sh` à la racine du dépôt parcourt tous les mods dans l'ordre des
dépendances, et c'est la voie la plus simple si tu en construis plusieurs.

L'artefact est `build/libs/cobblecheesescore-1.0.0+1.21.1.jar`, d'après `mod_version` dans
`gradle.properties`. Un jar de sources est produit à côté ; ne déploie pas celui-là.

> Jusqu'au 29 octobre 2026, `fabric.mod.json` portait une version figée au lieu de
> `${version}` : le nom du jar et le mod chargé n'étaient donc pas d'accord sur la version.
> C'est corrigé : `version` vaut `${version}`, `processResources` l'étend, et la version
> rapportée correspond au nom de fichier.

## Installation

```bash
cp CheeseDash/build/libs/cheesedash-*.jar        /chemin/vers/serveur/mods/
cp CobbleCheeseScore/build/libs/cobblecheesescore-1.0.0+1.21.1.jar /chemin/vers/serveur/mods/
```

Retire d'abord tout `cobblecheesescore-*.jar` plus ancien de `mods/`. Fabric refusera de
démarrer avec deux copies du même identifiant de mod.

## Ce que crée le premier démarrage

Dans le dossier `config/` du serveur :

| Chemin | Quand | Contenu |
|---|---|---|
| `config/cobblecheesescore.json` | à `onInitializeServer`, s'il est absent | les 30 classements livrés plus les 4 classements Conseil 4 semés, et toutes les clés globales |
| `config/cobblecheesescore_minigames.json` | à la première mutation de mini-jeu | `{"games":{}}` et au-delà |
| `config/cobblecheesescore_legends.json` | à la première session enregistrée | l'archive permanente des mini-jeux |

Rien n'est créé dans le dossier du monde, et rien dans le dossier du monde n'est écrit. Le
mod ne lit que `pokedex/`, `cobblemonplayerdata/` et `stats/`.

Note que la configuration est **ré-enregistrée au premier démarrage même si elle existe
déjà**, dès que l'un des quatre classements Conseil 4 lui manque. C'est ainsi qu'une
installation antérieure à mai 2026 les obtient.

## Vérifier qu'il a chargé

Trois groupes de lignes de log, dans cet ordre. Le logger s'appelle `cobblecheesescore`
pour les lignes de la classe principale et `CobbleCheeseScore` pour tout le reste : ton
formateur de log peut donc afficher le préfixe de deux façons.

Pendant l'initialisation du mod :

```text
[cobblecheesescore] Initializing CobbleCheeseScore...
[CobbleCheeseScore] Creating default configuration...
[CobbleCheeseScore] Seeded scoreboard: conseil4_first_finish
[CobbleCheeseScore] Seeded scoreboard: conseil4_fastest_time
[CobbleCheeseScore] Seeded scoreboard: conseil4_ranked_champions
[CobbleCheeseScore] Seeded scoreboard: conseil4_ranked_failures
[CobbleCheeseScore] Configuration saved to config/cobblecheesescore.json
[cobblecheesescore] CobbleCheeseScore initialized successfully!
```

Sur une installation existante, `Creating default configuration...` est remplacé par
`Configuration loaded from config/cobblecheesescore.json` et les lignes `Seeded` sont
absentes.

Depuis CheeseDash, pour prouver que le module a été découvert via l'entrypoint
`cheesedash` :

```text
[CheeseDash] Public context registered: /scores/
```

Quand le monde est en place :

```text
[cobblecheesescore] Server started - Initializing scoreboards...
[CobbleCheeseScore] Auto-detected world path: /chemin/vers/world
[cobblecheesescore] Running initial scoreboard update...
[CobbleCheeseScore] Collecting player data...
[CobbleCheeseScore] Collected data for 30 players
[CobbleCheeseScore] Updated scoreboard: pokedex_completed (25 players)
[CobbleCheeseScore] All scoreboards updated successfully
[cobblecheesescore] Initial update complete
[cobblecheesescore] Scoreboard update scheduler started (interval: 10 minutes)
```

Sur un monde tout neuf sans données Cobblemon, tu obtiens un avertissement à la place, et
le démarrage réussit quand même :

```text
[CobbleCheeseScore] Pokedex folder not found: /chemin/vers/world/pokedex
[CobbleCheeseScore] No player data found — continuing to render external-source boards (Conseil 4)
```

### Vérifier depuis l'extérieur

Il n'y a aucune commande en jeu. La vérification se fait en HTTP.

```bash
TOKEN=$(curl -s -X POST http://localhost:44311/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"token":"<ton master token>"}' | sed 's/.*"sessionId":"\([^"]*\)".*/\1/')

curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:44311/api/v1/scores/stats
```

```json
{"totalScoreboards":34,"enabledScoreboards":34,"totalPlayersTracked":30,
 "lastUpdate":1761700000000,"updateIntervalMinutes":10}
```

`totalScoreboards: 34` et un `lastUpdate` non nul signifient que le module est enregistré
et qu'un rafraîchissement est passé. La route d'image publique n'a besoin d'aucun token,
ce qui en fait le test de fumée le plus rapide. Utilise un simple `GET` — la route ne
répond qu'à `GET`, donc un `curl -I` (une requête `HEAD`) reçoit un `404` même quand
l'image existe :

```bash
curl -s -o /dev/null -w '%{http_code} %{content_type}\n' http://localhost:44311/scores/api/images/test/pokedex_completed.png
```

```text
200 image/png
```

Dans le dashboard lui-même, la barre latérale gagne une section **CobbleCheeseScore**
contenant une entrée 🏆 (libellée **Classements** en français, **Leaderboards** en
anglais). Si
l'entrée manque, voir
[dépannage](troubleshooting.fr.md#le-dashboard-na-pas-de-section-cobblecheesescore).

## Désinstallation

1. Arrête le serveur.
2. Supprime `mods/cobblecheesescore-*.jar`.
3. Démarre le serveur. Rien d'autre ne référence le mod : CheeseDash découvre les modules
   via la liste d'entrypoints au démarrage, donc le préfixe `scores` et le contexte public
   `/scores/` cessent simplement d'être enregistrés, et la barre latérale du dashboard perd
   son entrée.

Ce qui reste derrière, entièrement dans `config/` et entièrement supprimable :

| Fichier | Pourquoi tu voudrais le garder |
|---|---|
| `cobblecheesescore.json` | tes définitions de classements et ta palette |
| `cobblecheesescore_minigames.json` | les 500 dernières sessions de chaque mini-jeu |
| `cobblecheesescore_legends.json` | le panthéon permanent des mini-jeux. Rien d'autre ne détient ces données |
| `cobblecheesescore_external_conseil4_*.json` | écrits par CobbleCap, pas par ce mod. Retirer ce mod n'empêche pas CobbleCap de les écrire |

Rien ne reste dans le dossier du monde, puisque rien n'y a jamais été écrit. Les images
rendues n'ont jamais existé qu'en mémoire : il n'y a aucun PNG périmé à nettoyer.

⚠️ Tout cadre d'image en jeu pointé sur `http://host:44311/scores/api/images/…` échouera à
charger dès que le mod aura disparu. Retire ou repointe ces cadres avant de désinstaller,
sinon les joueurs regarderont des cadres vides.

---

← [CobbleCheeseScore](../README.fr.md) · [Premiers pas](getting-started.fr.md) →
