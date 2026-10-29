[← CobbleCheeseScore](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](png-export.md)

# Export PNG

Chaque classement activé est rendu dans un `BufferedImage` en mémoire et servi en HTTP sous
forme de PNG. C'est la partie du mod qui justifie de l'installer : un classement que tu peux
accrocher à un mur de ta ville de spawn et y laisser, et qui se rafraîchit tout seul.

Rien n'est écrit sur le disque. L'image vit dans la map `imageCache` de `CacheManager` et
est encodée en octets PNG à chaque requête par `ImageIO.write(image, "PNG", baos)`.
Redémarre le serveur et toutes les images ont disparu jusqu'au premier rafraîchissement qui
repeuple le cache, lequel a lieu pendant `SERVER_STARTED`.

---

## Qui fait le rendu, et quand

`ScoreboardManager.updateAll()` est le seul point d'entrée. Pour chaque classement activé,
il calcule le classement, appelle `ImageGenerator.generateScoreboard(boardConfig, scores,
dynamicTitle)`, et stocke le résultat sous l'identifiant du classement. Trois choses le
déclenchent :

| Déclencheur | Thread |
|---|---|
| le rafraîchissement initial de `SERVER_STARTED` | le thread de démarrage du serveur |
| le planificateur, tous les `updateIntervalMinutes` | un `ScheduledExecutorService` à un seul thread dédié |
| `POST /api/v1/scores/refresh`, ou n'importe quel `PUT /api/v1/scores/scoreboards/:id` | un thread HTTP de CheeseDash |

Les trois lancent la passe complète sur tous les classements, pas sur un seul. Un `PUT` sur
un seul classement refait le rendu de la trentaine d'images, ce qui prend une seconde ou
deux.

## Géométrie

L'image n'a pas de taille fixe. Elle grandit avec le nombre de joueurs classés, depuis des
constantes en tête d'`ImageGenerator` :

| Constante | Valeur |
|---|---|
| `COLUMN_WIDTH` | 580 |
| `ROW_HEIGHT` | 60 |
| `HEADER_HEIGHT` | 140 |
| `FOOTER_HEIGHT` | 60 |
| `PADDING` | 40 |
| `COLUMN_GAP` | 30 |

Le nombre de colonnes vient de `ceil(joueurs / rowsPerColumn)`, borné à l'intervalle 1–3.
Une fois ce nombre fixé, les lignes sont redistribuées également :
`actualRowsPerColumn = ceil(joueurs / colonnes)`. Donc `rowsPerColumn` décide **du nombre
de colonnes que tu obtiens**, et non du nombre de lignes dans chaque colonne.

```text
largeur = 580 × colonnes + 30 × (colonnes − 1) + 80
hauteur = 280 + actualRowsPerColumn × 60
```

Quatre cas travaillés, tous avec `rowsPerColumn: 10` :

| Joueurs classés | Colonnes | Lignes par colonne | Taille de l'image |
|---|---|---|---|
| 10 | 1 | 10 | 660 × 880 |
| 25 | 3 | 9 | 1 880 × 820 |
| 30 | 3 | 10 | 1 880 × 880 |
| 45 (`maxPlayers: 45`) | 3 | 15 | 1 880 × 1 180 |

⚠️ Au-delà de 3 × `rowsPerColumn` joueurs, la borne mord et les colonnes s'allongent au
lieu de se multiplier : l'image devient une bande haute. Avec `maxPlayers: 100` et
`rowsPerColumn: 10`, tu obtiens trois colonnes de 34 lignes — une image de 2 320 pixels de
haut. Augmente `rowsPerColumn` au même rythme que `maxPlayers` si tu veux une image plus
carrée.

Le format de pixels est `TYPE_INT_RGB` — aucun canal alpha. Les fonds de ligne translucides
fonctionnent quand même, puisqu'ils sont fondus sur le dégradé déjà peint en dessous.

## Ce que le rendu dessine

Dans l'ordre :

1. Un `GradientPaint` diagonal sur toute la toile, de `background` vers la même couleur à
   90 % de luminosité.
2. Un rectangle d'en-tête arrondi, de 140 px de haut, rempli d'un dégradé de
   `titleBackground` vers 85 % de celle-ci.
3. Le titre, centré, en **Inter Bold 48**. Le titre dynamique l'emporte sur
   `boardConfig.title` quand il y en a un — seul le classement des échecs du Conseil 4 en
   définit un.
4. La description, centrée, en **Inter Plain 20**, de la même couleur que le titre.
5. Une ligne par joueur : un rectangle arrondi, une barre gauche de 4 px pour les trois
   premiers, le numéro de rang (**Inter Bold 32** pour le podium, 28 en dessous), le nom du
   joueur (**Inter Bold 22** pour le rang 1, 18 en dessous), et le score formaté aligné à
   droite en **Inter Bold 22**, coloré avec `titleBackground`.
6. Le pied d'image, centré, en **Inter Plain 16** : `lastUpdateText` suivi de l'heure
   courante au format `dd/MM/yyyy HH:mm:ss` dans le `timeZone` configuré. Entièrement omis
   quand `showLastUpdate` vaut `false`.

L'antialiasing est actif pour les formes comme pour le texte, et `KEY_RENDERING` est réglé
sur `VALUE_RENDER_QUALITY`.

### ⚠️ Inter n'est presque certainement pas installée

Chaque appel à `new Font(...)` demande la famille `"Inter"`. Un serveur de jeu Linux sans
affichage l'a rarement. Quand AWT n'arrive pas à résoudre un nom de famille, il retombe sur
une police logique par défaut : les images sont donc rendues — elles le sont simplement
dans ce que la JVM a choisi, et les métriques diffèrent assez pour que le centrage paraisse
légèrement décalé et que les noms longs soient tronqués à un endroit différent.

Il n'y a aucune clé de configuration pour la police. Si la typographie t'importe, installe
Inter pour tout le système sur l'hôte (`fonts-inter` sur les distributions de la famille
Debian) et redémarre le serveur, ou édite les six appels `new Font("Inter", …)` dans
`src/main/java/com/cobblecheesescore/image/ImageGenerator.java`.

### La couleur du texte du titre est calculée, pas configurée

Si un classement définit `colors.titleText`, cette couleur est utilisée. Sinon, le rendu
choisit le noir (`#141414`) ou le blanc depuis la luminosité perçue de
`titleBackground` :

```text
luminosité = (0,299·R + 0,587·V + 0,114·B) / 255
luminosité > 0,5  →  texte presque noir
sinon             →  texte blanc
```

C'est pour cela que l'en-tête jaune par défaut (`#ffcb05`) reçoit du texte sombre et qu'un
en-tête violet reçoit du blanc, sans aucune configuration.

### Les clés de couleur que le rendu ignore

Quatre clés apparaissent dans la configuration par défaut livrée et dans une documentation
plus ancienne, mais aucun code ne les lit. Les couleurs du podium sont codées en dur en or,
argent et bronze, et le nom du rang 1 est toujours `#FFD700` :

| Clé | État |
|---|---|
| `firstPlaceBackground` | ignorée — la ligne du rang 1 est toujours en or à 40/255 d'alpha |
| `secondPlaceBackground` | ignorée — argent à 30/255 |
| `thirdPlaceBackground` | ignorée — bronze à 30/255 |
| `topPlayerText` | ignorée — le nom du rang 1 est toujours `#FFD700` |

`getRowBackgroundColor(int rank, ScoreboardConfig config)` reçoit la configuration du
classement et ne la consulte jamais. Changer ces quatre clés ne fait rien.

### Les noms longs

Un nom plus large que `COLUMN_WIDTH − 260` (320 px) est raccourci en retirant quatre
caractères et en ajoutant `...`, de façon répétée, jusqu'à ce qu'il rentre ou qu'il tombe à
dix caractères. Un nom très long dans une police logique étroite peut donc malgré tout
dépasser son budget de 320 px.

---

## Récupérer l'image

Deux routes servent exactement les mêmes octets. La différence est l'authentification.

### Authentifiée

```text
GET /api/v1/scores/scoreboards/<id>/image
GET /api/v1/scores/scoreboards/<id>/image/<n_importe_quoi>.png
```

Exige un en-tête CheeseDash `Authorization: Bearer <token>` comme toutes les autres routes
`/api/`. La seconde forme n'existe que pour qu'un *Enregistrer sous* de navigateur donne un
nom de fichier sensé : le segment final est capturé par l'expression régulière du
distributeur puis jeté.

### Publique, sans authentification

```text
GET /scores/api/images/<name>/<id>.png
```

Servie depuis le contexte public de CheeseDash pour le préfixe `scores`, enregistré
**avant** `/api/` et situé hors du middleware d'authentification. `<name>` est un
remplissage libre pour le nom de fichier qu'un client enregistrera ; `<id>` est le véritable
identifiant du classement, décodé depuis l'URL. Les deux viennent de
`CobbleCheeseScoreDashboardModule.handlePublicRequest`.

C'est la route à utiliser pour tout ce qui est en jeu, puisque rien à l'intérieur de
Minecraft ne peut envoyer un token Bearer.

```bash
curl -o pokedex.png \
  http://ton-serveur:44311/scores/api/images/pokedex/pokedex_completed.png
```

| Réponse | Signification |
|---|---|
| `200` avec `Content-Type: image/png` | l'image en cache, plus `Cache-Control: public, max-age=60` |
| `404` avec un corps JSON | aucune image en cache sous cet identifiant — l'identifiant est faux, aucun rafraîchissement n'est encore passé, ou le classement est désactivé depuis le démarrage du serveur (un classement désactivé en cours de route continue de servir sa dernière image) |
| `503` avec un corps JSON | `CacheManager` est null, autrement dit le mod n'a pas fini son initialisation |

⚠️ Un `404` et un `503` reviennent en **JSON**, pas en image. Un client qui fait aveuglément
confiance au type de contenu essaiera de décoder `{"error":"…"}` comme un PNG et n'affichera
rien.

⚠️ La route publique expose les noms de joueurs et les statistiques à quiconque atteint le
port 44311. C'est délibéré — c'est ce qui rend l'affichage en jeu possible — mais cela
signifie que tu ne peux pas considérer tout le port du dashboard comme privé si tu
l'utilises. Place-le derrière un reverse proxy si tu as besoin de le restreindre.

`Cache-Control: public, max-age=60` est l'autre chose à savoir : un client peut
légitimement afficher une image vieille d'une minute après que tu as forcé un
rafraîchissement.

---

## L'afficher sur une carte en jeu

Minecraft vanilla ne peut pas charger une image depuis une URL, et ce mod ne livre aucun
code client — `fabric.mod.json` déclare `"environment": "server"`. Afficher le classement
dans le monde demande un mod tiers de cadre d'image sur les clients. Le commentaire sur la
route publique dans `CobbleCheeseScoreDashboardModule` nomme WaterFrames, qui est ce
qu'Arkensia utilisait.

> Les étapes ci-dessous décrivent le fonctionnement de cette intégration vu du côté du mod.
> Les spécificités d'un mod de cadre d'image sont une observation de la façon dont il a été
> utilisé, et non une spécification de ce mod — consulte sa documentation pour la syntaxe
> actuelle de ses commandes.

1. **Rends CheeseDash joignable depuis les clients.** L'URL que tu places dans le monde est
   résolue par le jeu de chaque joueur, pas par le serveur : `localhost` ne fera donc pas
   l'affaire. Utilise l'adresse publique ou le nom DNS de l'hôte, et assure-toi que le port
   44311 est ouvert aux joueurs.

   ```bash
   # depuis une machine située du côté réseau des joueurs
   curl -s -o /dev/null -w '%{http_code} %{content_type}\n' http://ton-serveur:44311/scores/api/images/test/pokedex_completed.png
   ```

   `200 image/png` signifie que tu en as fini avec le côté serveur. Ne teste pas avec
   `curl -I` : la route ne répond qu'à `GET`, et une requête `HEAD` reçoit un `404`.
   Une connexion refusée signifie que le port est fermé ; un `404` signifie qu'aucune image
   n'est encore en cache — force-en une avec `POST /api/v1/scores/refresh`.

2. **Choisis l'identifiant de ton classement.** `GET /api/v1/scores/scoreboards` les liste
   tous, avec un indicateur `hasImage`. L'identifiant est le champ `id`, pas le champ
   `filename` — `filename` est un poids mort dans la configuration et ne joue aucun rôle
   dans aucune URL.

3. **Note le rapport d'aspect.** Déduis la taille de l'image de la table de géométrie
   ci-dessus, ou lis la taille en pixels du fichier récupéré. Une image de 1 880 × 880
   demande un cadre à peu près en 2:1. Se tromper là-dessus est la plainte la plus fréquente
   : le texte a l'air étiré.

4. **Installe le mod de cadre d'image** sur les clients (et sur le serveur, s'il a besoin
   d'une moitié côté serveur). WaterFrames est un mod client et serveur ; AutoModPack ou ton
   pack de lanceur est la voie habituelle pour l'amener sur chaque client.

5. **Place le cadre et pointe-le sur l'URL publique.** Avec WaterFrames, tu places le bloc
   de cadre, puis tu règles son URL dans l'écran du cadre ou avec sa commande. L'URL est la
   route publique :

   ```text
   http://ton-serveur:44311/scores/api/images/pokedex/pokedex_completed.png
   ```

6. **Dimensionne le cadre** au rapport d'aspect de l'étape 3 et règle son intervalle de
   rafraîchissement un peu plus long que `updateIntervalMinutes`. Rafraîchir plus vite que
   le serveur ne refait le rendu ne coûte que de la bande passante, et `max-age=60` fait de
   toute façon qu'un client ne verra pas un changement avant une minute.

7. **Vérifie en jeu.** Place-toi devant le cadre et lis la ligne de pied : elle porte
   l'horodatage du rendu dans le `timeZone` configuré du serveur. Si cet horodatage avance
   tout seul tous les `updateIntervalMinutes`, toute la chaîne fonctionne.

Un classement dont l'image manque affiche ce que le mod de cadre fait d'un chargement
échoué, généralement un vide ou un placeholder. Voir
[dépannage](troubleshooting.fr.md#un-cadre-en-jeu-naffiche-rien-ou-un-placeholder).

---

← [Classements](leaderboards.fr.md) · [Mini-jeux](minigames.fr.md) →
