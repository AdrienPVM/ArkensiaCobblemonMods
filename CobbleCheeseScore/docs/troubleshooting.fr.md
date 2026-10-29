[← CobbleCheeseScore](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](troubleshooting.md)

# Dépannage

## Le mod ne charge pas du tout

**Cause.** Une des dépendances strictes manque, ou deux copies du jar sont dans `mods/`.
Fabric refuse de démarrer plutôt que de charger un mod dont le bloc `depends` n'est pas
satisfait : `fabricloader >=0.16.0`, `minecraft ~1.21.1`, `java >=21` et `cheesedash *`.

**Remède.** Lis l'erreur du loader lui-même, qui nomme la contrainte non satisfaite. Puis
vérifie :

```bash
ls /chemin/vers/serveur/mods/ | grep -E 'cobblecheesescore|cheesedash'
java -version
```

Exactement un `cobblecheesescore-*.jar` et un `cheesedash-*.jar`, et Java 21 ou plus.
Supprimer un jar plus ancien est le remède habituel après une mise à jour.

## Rien ne se passe en solo

**Cause.** Le seul entrypoint est `server`, qui implémente
`DedicatedServerModInitializer`. Celui-ci n'est pas appelé sur un serveur intégré : ouvrir
un monde depuis ton propre client ne démarre donc jamais le mod — aucun fichier de
configuration n'est écrit, aucun module n'est enregistré, aucune ligne de log n'apparaît.

**Remède.** Teste sur un serveur dédié. `"environment": "server"` signifie aussi que le jar
ne fait rien d'utile dans le dossier `mods/` d'un client : il n'y a donc aucune installation
côté client à rater.

## Le dashboard n'a pas de section CobbleCheeseScore

**Cause.** CheeseDash découvre les modules via
`FabricLoader.getEntrypoints("cheesedash", …)` une seule fois, au démarrage. Si le mod a
chargé mais que l'entrypoint n'a pas été récupéré, ou si CheeseDash a démarré d'une façon
qui l'a manqué, le préfixe `scores` n'est jamais enregistré et chaque requête
`/api/v1/scores/*` reçoit le `404` propre à CheeseDash.

**Remède.** Cherche la ligne qui prouve l'enregistrement :

```text
[CheeseDash] Public context registered: /scores/
```

Si elle est absente alors que `CobbleCheeseScore initialized successfully!` est présente, le
mod a chargé et l'entrypoint non. Vérifie que le jar dans `mods/` est bien celui que tu as
construit — un vieux jar antérieur au module dashboard s'initialisera très bien et
n'enregistrera rien. Confirme avec :

```bash
unzip -p /chemin/vers/serveur/mods/cobblecheesescore-*.jar fabric.mod.json | grep -A2 cheesedash
```

Tu veux
`"cheesedash": ["com.cobblecheesescore.dashboard.CobbleCheeseScoreDashboardModule"]`. Puis
redémarre le serveur — rien n'enregistre un module après le démarrage.

## Tous les classements sont vides et le log dit `Pokedex folder not found`

**Cause.** `DataCollector` cherche au mauvais endroit, ou le monde n'a réellement aucune
donnée Cobblemon. Le chemin du monde est déduit de
`server.getSaveProperties().getLevelName()` résolu contre le répertoire d'exécution, ce qui
est faux quand le dossier sur le disque porte un autre nom que le niveau, ou quand
l'hébergeur lance le jar depuis un dossier parent.

**Remède.** Le log imprime le chemin qu'il a retenu :

```text
[CobbleCheeseScore] Auto-detected world path: /home/container/world
[CobbleCheeseScore] Pokedex folder not found: /home/container/world/pokedex
```

Regarde où se trouve réellement `pokedex/` :

```bash
find /chemin/vers/serveur -maxdepth 3 -type d -name pokedex
```

Si le chemin diffère, fixe-le dans `config/cobblecheesescore.json` :

```json
"autoDetectDataFolder": false,
"manualDataFolderPath": "/home/container/world"
```

Redémarre. Le chemin est résolu une seule fois dans le constructeur de `DataCollector` :
une édition sans redémarrage ne change rien. Un `manualDataFolderPath` qui n'existe pas
journalise `Manual path does not exist: <path>` et retombe silencieusement sur la détection
automatique.

Si `find` ne trouve rien, Cobblemon n'a jamais écrit de fichier de Pokédex — aucun joueur
n'a encore rencontré de Pokémon, ou Cobblemon n'est pas installé. Le mod n'est pas cassé ; il
n'a rien à classer.

## Les classements montrent moins de joueurs qu'il n'y en a sur le serveur

**Cause.** Deux filtres, tous deux délibérés.

Un joueur sans fichier `pokedex/<uuid>.nbt` n'existe pas pour ce mod. La liste des joueurs
est construite en parcourant ce dossier : un joueur avec 200 heures et aucune rencontre de
Pokémon est donc invisible sur tous les classements, `play_time` compris.

Un joueur dont le score calculé vaut exactement 0 est écarté avant le tri — sur tous les
classements sauf `survivor`. Sur un serveur jeune, c'est la raison pour laquelle la plupart
des classements de type sont presque vides.

**Remède.** Rien à corriger. Vérifie la distinction avec :

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:44311/api/v1/scores/stats
```

`totalPlayersTracked` est le nombre de joueurs que le collecteur a trouvés ; le
`playerCount` d'un classement est le nombre d'entre eux qui y ont un score non nul. Un grand
écart entre les deux est normal.

## Un joueur précis manque sur un classement et apparaît sur les autres

**Cause.** La mesure de ce classement lit un fichier source que le joueur n'a pas, ou un
compteur que Cobblemon n'a pas écrit pour lui. Tous les classements dérivés de Cobblemon
lisent `advancementData.<compteur>` dans `cobblemonplayerdata/<uuid>.json`, et une clé
absente donne 0, qui est ensuite filtré.

**Remède.** Cherche les avertissements par joueur, au niveau DEBUG/WARN :

```text
[CobbleCheeseScore] Cobblemon data file not found for 5e1b2c3d-…
[CobbleCheeseScore] Minecraft stats file not found for 5e1b2c3d-…
```

Puis lis le fichier à la main et vérifie si le compteur existe :

```bash
python3 -c "import json;d=json.load(open('world/cobblemonplayerdata/4a/5e1b2c3d-….json'));print(d.get('advancementData',{}).keys())"
```

Si le compteur est absent, Cobblemon n'a pas enregistré cette activité pour ce joueur et le
mod n'y peut rien.

## Un changement de configuration n'a aucun effet

**Cause.** Presque rien n'est rechargeable à chaud dans ce mod, et l'objet `Config` en
mémoire du serveur en cours d'exécution fait autorité sur le fichier.

**Remède.** Compare ton changement à la table de rechargement dans
[configuration](configuration.fr.md#comportement-au-rechargement). En résumé :

- le `title`, `description`, `enabled`, `maxPlayers`, `rowsPerColumn` ou `colors` d'un
  classement appliqué via `PUT /api/v1/scores/scoreboards/:id` prend effet immédiatement ;
- tout le reste exige un redémarrage du serveur ;
- `sortOrder`, ajouter un classement et retirer un classement ne peuvent être faits **que**
  dans le fichier, suivi d'un redémarrage.

⚠️ Éditer `config/cobblecheesescore.json` pendant que le serveur tourne est franchement
dangereux : le prochain `PUT` du dashboard sérialise la configuration en mémoire par-dessus
ton édition, et elle est perdue. Arrête le serveur, édite, redémarre.

## `PUT /config` a répondu `{"saved":true}` et n'a rien changé

**Cause.** C'est ce que fait la route. `handleUpdateConfig` n'analyse aucune clé globale ;
sa seule branche, pour `scoreboardId` plus `enabled`, retrouve le classement et
**journalise** la demande sans l'appliquer. Puis elle écrit la configuration en mémoire,
inchangée, sur le disque et rapporte un succès. Le `{"saved":true}` est véridique sur
l'écriture du fichier et trompeur sur ta demande.

**Remède.** Utilise la route par classement pour les changements de classement :

```bash
curl -X PUT http://localhost:44311/api/v1/scores/scoreboards/survivor \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"enabled":false}'
```

Pour les clés globales — `updateIntervalMinutes`, `timeZone`, `showLastUpdate`,
`lastUpdateText`, `defaultColors` — édite le fichier avec le serveur arrêté.

## Les images ne se rafraîchissent jamais toutes seules

**Cause.** `updateIntervalMinutes` est lue une seule fois, à `SERVER_STARTED`, et passée à
`scheduleAtFixedRate`. Rien ne la replanifie, et une valeur de `0` ou négative fait lever
l'exécuteur au démarrage, te laissant avec le rendu initial et aucun planificateur.

**Remède.** Cherche la ligne qui confirme le démarrage du planificateur, et l'intervalle
avec lequel il a démarré :

```text
[cobblecheesescore] Scoreboard update scheduler started (interval: 10 minutes)
```

Si cette ligne manque, cherche juste au-dessus une `IllegalArgumentException` et corrige
`updateIntervalMinutes` pour en faire un entier positif. Si la ligne est présente avec
l'ancienne valeur, tu as changé la configuration sans redémarrer. En attendant, force une
passe à la main :

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:44311/api/v1/scores/refresh
```

## Tous les classements échouent d'un coup avec `Failed to update scoreboard`

**Cause.** Le coupable le plus probable est `timeZone`. Elle est passée à `ZoneId.of(...)`
dans le rendu, sans garde : une faute de frappe lève donc une `DateTimeException` pour
chaque classement l'un après l'autre, chacune attrapée et journalisée individuellement.

**Remède.**

```text
[CobbleCheeseScore] Failed to update scoreboard: pokedex_completed
java.time.zone.ZoneRulesException: Unknown time-zone ID: Europe/Pari
```

Mets `timeZone` à un identifiant IANA valide (`Europe/Paris`, `America/New_York`, `UTC`) et
redémarre. Un échec de résolution de police sur une JVM mal configurée produit le même motif
par classement avec une autre exception — lis la trace de pile plutôt que de deviner.

## Une route d'image renvoie du JSON

**Cause.** Les deux routes d'image renvoient `image/png` en cas de succès et
`application/json` en cas d'échec. Un corps de `404` vaut
`{"error":"No image for scoreboard: <id>"}`, un corps de `503` vaut
`{"error":"Cache not initialized"}`.

**Remède.** Lis le code de statut. Un `404` signifie une de ces trois choses :

- l'identifiant du classement est faux — regarde `GET /api/v1/scores/scoreboards` pour les
  identifiants exacts et leur indicateur `hasImage` ;
- le classement est désactivé, donc aucune image n'a jamais été rendue ;
- aucun rafraîchissement n'est encore allé au bout, ce qui sur un serveur à froid signifie
  que le cache est vide.

Force un rafraîchissement et réessaie. Un `503` signifie que le mod n'a pas fini son
initialisation — attends `All scoreboards updated successfully` dans le log.

## L'image est une bande haute et étroite

**Cause.** Le nombre de colonnes est `ceil(joueurs / rowsPerColumn)` **borné à 3**.
Au-delà de 3 × `rowsPerColumn` joueurs classés, les colonnes s'allongent au lieu de se
multiplier. Avec `maxPlayers: 100` et `rowsPerColumn: 10`, tu obtiens trois colonnes de 34
lignes, soit 2 320 pixels de haut pour 1 880 de large.

**Remède.** Augmente `rowsPerColumn` au même rythme que `maxPlayers`, ou baisse
`maxPlayers`. Trente joueurs avec `rowsPerColumn: 10` donnent une image de 1 880 × 880, qui
est la forme que la conception supposait. Le calcul est dans
[export PNG](png-export.fr.md#géométrie).

## Les polices ont l'air fausses

**Cause.** Chaque appel à `new Font(...)` demande la famille `"Inter"`. Un serveur sans
affichage l'a rarement installée, et AWT retombe silencieusement sur une police logique par
défaut aux métriques différentes — le centrage dérive donc, et les noms sont tronqués à une
autre largeur.

**Remède.** Installe Inter sur l'hôte et redémarre (`apt install fonts-inter` sur les
distributions de la famille Debian), ou édite les six appels `new Font("Inter", …)` dans
`src/main/java/com/cobblecheesescore/image/ImageGenerator.java` pour une famille que l'hôte
possède vraiment. Il n'y a aucune clé de configuration pour cela.

## Un cadre en jeu n'affiche rien, ou un placeholder

**Cause.** Quatre possibilités, dans un ordre de probabilité approximatif.

1. L'URL utilise `localhost` ou une adresse de réseau local. Le cadre est récupéré par le
   jeu de chaque **joueur**, pas par le serveur.
2. Le port 44311 n'est pas joignable depuis le côté réseau des joueurs.
3. L'identifiant du classement dans l'URL est faux, ou le classement est désactivé : la
   route répond donc `404` avec un corps JSON que le cadre ne peut pas décoder.
4. Le mod de cadre d'image n'est pas installé sur le client de ce joueur.

**Remède.** Teste l'URL exacte depuis une machine du réseau des joueurs, pas depuis le
serveur :

```bash
curl -sI http://ton-serveur:44311/scores/api/images/test/pokedex_completed.png
```

Un `200` avec `Content-Type: image/png` dédouane le côté serveur et désigne le client. Une
connexion refusée est un pare-feu ou un problème de liaison de port. Un `404` est un
problème d'identifiant de classement — liste-les avec `GET /api/v1/scores/scoreboards`.

Rappelle-toi `Cache-Control: public, max-age=60` : un client peut afficher une image
vieille d'une minute après un rafraîchissement forcé, ce qui ressemble à un cadre périmé
sans en être un.

## Une commande est refusée

Il n'y a aucune commande. `grep -r CommandRegistrationCallback src/main/java` ne renvoie
rien, et aucun package `commands/` n'existe. Chaque opération passe par le dashboard ou par
l'API REST. Si une autre documentation t'a donné une commande `/cobblecheesescore …`, elle
n'existe pas dans cette version.

## Un compte READ_ONLY a changé quelque chose

**Cause.** `ScoresApi` ne contient aucun appel à `WebDashboard.requireWrite` ni à
`requireAdmin`. Le seul verrou est le middleware d'authentification de CheeseDash, qui
vérifie que la session est valide et ne vérifie pas son rôle. Toutes les routes du module —
y compris `PUT /scoreboards/:id`, `POST /refresh` et toutes les mutations de mini-jeux —
sont ouvertes à n'importe quelle session authentifiée.

**Remède.** Il n'y a aucune configuration pour cela ; il faut une modification de code, une
clause de garde par handler mutant. En attendant, considère un compte CheeseDash READ_ONLY
comme ayant un accès complet en écriture sur ce module, et restreins les comptes en
conséquence. Tous les détails dans [dashboard](dashboard.fr.md#rôle-requis).

## Le classement d'un mini-jeu est à l'envers

**Cause.** `POST /minigames/:id/sessions` stocke le tableau `rankings` **exactement tel
qu'il a été soumis** et attribue 3, 2 et 1 points aux positions 1, 2 et 3 de ce tableau. Les
valeurs `score` brutes ne servent jamais à l'ordonnancement. Soumets un résultat de course
trié par temps croissant dans un jeu où le plus rapide gagne, et le joueur le plus lent
reçoit les trois points.

**Remède.** Resoumets, le meilleur d'abord. Une session stockée ne peut pas être réordonnée
sur place : supprime-la avec `DELETE /minigames/:id/sessions/:idx` en utilisant l'`index`
donné par `GET /minigames/:id`, puis publie-la à nouveau. Supprimer décale tous les indices
supérieurs d'un cran vers le bas : récupère la liste entre deux suppressions.

## Les classements Conseil 4 sont vides

**Cause.** Ils lisent quatre fichiers JSON que [CobbleCap](../../CobbleCap/) écrit dans
`config/`. Sans CobbleCap, les fichiers n'existent pas et les classements sont rendus comme
un en-tête sans lignes. `Config.load()` sème les quatre entrées dans chaque installation
quoi qu'il arrive, ce qui explique qu'ils apparaissent même quand ils ne peuvent pas
fonctionner.

**Remède.** Soit installe CobbleCap, soit désactive-les :

```bash
for id in conseil4_first_finish conseil4_fastest_time \
          conseil4_ranked_champions conseil4_ranked_failures; do
  curl -s -X PUT "http://localhost:44311/api/v1/scores/scoreboards/$id" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d '{"enabled":false}'
done
```

Le semeur ne rajoute un classement que si son identifiant est **absent** : une entrée
désactivée survit donc aux redémarrages. Avec CobbleCap installé et les classements toujours
vides, vérifie les noms de fichiers au caractère près —
`cobblecheesescore_external_conseil4_first.json`, `…_fastest.json`,
`…_ranked_champions.json`, `…_ranked_failures.json` — et cherche :

```text
[CobbleCheeseScore] Failed to load Conseil 4 board conseil4_fastest_time: <message>
```

Un fichier absent est journalisé en DEBUG et ne dit rien au niveau par défaut : le silence
signifie donc « fichier absent ».

## Déposer un rapport de bug utile

Ouvre-le sur
[GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues) avec :

1. **La version du jar.** `ls mods/ | grep cobblecheesescore`, et le jar CheeseDash qui
   l'accompagne.
2. **Le bloc de démarrage du log.** Tout, de `Initializing CobbleCheeseScore...` jusqu'à
   `Scoreboard update scheduler started`, y compris la ligne `Auto-detected world path` et
   les éventuels avertissements. Ce bloc à lui seul répond à la plupart des questions sur
   l'endroit où le mod cherche et sur ce qu'il a trouvé.
3. **Le log d'un rafraîchissement en échec.** Extrais-le :

   ```bash
   grep -n -i 'cobblecheesescore\|ScoresApi' logs/latest.log | tail -60
   ```

   Si quelque chose a levé, inclus toute la trace de pile, pas seulement la première ligne.
4. **Ta configuration, avec les classements réduits.** Les clés globales plus le seul
   classement qui se comporte mal suffisent. Précise si tu as édité le fichier pendant que le
   serveur tournait.
5. **Pour un problème d'API, la requête exacte et la réponse complète.** `curl -i` plutôt
   que `curl -s`, pour que le code de statut et les en-têtes figurent dans le collage. Le
   code de statut compte plus que le corps sur les routes d'image, où les erreurs reviennent
   en JSON.
6. **Pour un problème d'affichage en jeu, la sortie de `curl -sI <l'URL exacte du cadre>`
   lancée depuis la machine d'un joueur**, et le nom et la version du mod de cadre d'image.
   Cette seule commande sépare un problème de serveur d'un problème de client.

N'ouvre pas d'issue publique pour un problème de sécurité — `SECURITY.md` à la racine du
dépôt explique où l'envoyer. L'absence de contrôles de rôle documentée ci-dessus est déjà
connue.

---

← [Architecture](architecture.fr.md) · [CobbleCheeseScore](../README.fr.md) →
