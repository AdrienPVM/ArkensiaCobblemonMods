[← CobbleCheeseScore](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](getting-started.md)

# Premiers pas

À la fin de cette marche à suivre, tu auras un classement de chasseurs de shinies rendu en
PNG, recoloré comme tu le veux, servi sur une URL qui n'exige aucune authentification, et
accroché à un mur de ta ville de spawn où il se rafraîchit tout seul toutes les dix
minutes.

Elle suppose un serveur Fabric 1.21.1 dédié avec Cobblemon installé et au moins un joueur
qui a capturé quelque chose. Un monde sans aucune donnée Cobblemon traversera toutes les
étapes et rendra des classements vides, ce qui est un essai à blanc utile mais pas un
résultat visible.

---

## 1. Construire et installer les deux jars

Le mod compile contre le jar de CheeseDash : CheeseDash passe donc d'abord.

```bash
cd CheeseDash && ./gradlew build
cd ../CobbleCheeseScore && ./gradlew build
cp ../CheeseDash/build/libs/cheesedash-*.jar /chemin/vers/serveur/mods/
cp build/libs/cobblecheesescore-1.0.0+1.21.1.jar /chemin/vers/serveur/mods/
```

Tous les détails, y compris quoi faire quand le jar CheeseDash n'est pas trouvé, dans
[installation](installation.fr.md).

## 2. Démarrer le serveur et lire trois lignes de log

```bash
cd /chemin/vers/serveur && java -Xmx8G -jar fabric-server-launch.jar nogui
```

Trois lignes prouvent que la chaîne fonctionne. Attends-les dans cet ordre :

```text
[cobblecheesescore] CobbleCheeseScore initialized successfully!
[CheeseDash] Public context registered: /scores/
[cobblecheesescore] Scoreboard update scheduler started (interval: 10 minutes)
```

Celle du milieu est la plus importante : elle signifie que CheeseDash a trouvé le module
via l'entrypoint `cheesedash`. Sans elle, rien du reste de cette page ne fonctionne — va
voir
[dépannage](troubleshooting.fr.md#le-dashboard-na-pas-de-section-cobblecheesescore).

Juste au-dessus de la ligne du planificateur, tu devrais aussi voir combien de joueurs ont
été trouvés :

```text
[CobbleCheeseScore] Collected data for 30 players
```

Un `Pokedex folder not found` à la place signifie que le mod cherche au mauvais endroit, ou
que ton monde n'a réellement aucune donnée Cobblemon pour l'instant. Voir
[dépannage](troubleshooting.fr.md#tous-les-classements-sont-vides-et-le-log-dit-pokedex-folder-not-found).

## 3. Obtenir un token du dashboard

Tout ce qui est sous `/api/` exige un token de session. Connecte-toi avec le master token
de `config/cheesedash.json` :

```bash
curl -s -X POST http://localhost:44311/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"token":"<master token de config/cheesedash.json>"}'
```

```json
{"token":"ses_8f2a…","role":"SUPER_ADMIN","username":"admin"}
```

Garde cette valeur `ses_…` ; le reste de cette page l'appelle `$TOKEN`.

```bash
export TOKEN=ses_8f2a…
```

## 4. Voir les classements dont tu disposes

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:44311/api/v1/scores/scoreboards
```

Trente-quatre objets reviennent. Trouve `shiny_hunter` et regarde deux champs :

```json
{
  "id": "shiny_hunter",
  "title": "Chasseur de Shiny",
  "playerCount": 12,
  "topPlayer": { "name": "RoiCheese", "score": "69" },
  "hasImage": true
}
```

`hasImage: true` signifie qu'une image est déjà en cache et attend. `playerCount` est le
nombre de joueurs dont le score n'est pas nul, pas le nombre de joueurs du serveur : ceux
qui n'ont aucun shiny sont écartés avant le classement.

Si `playerCount` vaut `0` sur tous les classements alors que
`Collected data for N players` disait autre chose, tes données Cobblemon ont une forme que
le parseur ne reconnaît pas ; interroge un classement à la fois avec
`GET /scoreboards/shiny_hunter` pour voir le tableau `rankings` vide de tes propres yeux.

## 5. Regarder l'image rendue

La route authentifiée te donne le PNG :

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  -o shiny.png \
  http://localhost:44311/api/v1/scores/scoreboards/shiny_hunter/image
file shiny.png
```

```text
shiny.png: PNG image data, 660 x 880, 8-bit/color RGB, non-interlaced
```

Ouvre-la. Tu devrais voir un en-tête doré portant *Chasseur de Shiny*, une ligne par joueur
classé avec des accents or, argent et bronze sur le podium, et un pied d'image avec
l'horodatage du rendu.

La taille n'est pas fixe : 660 × 880 est ce que produisent douze joueurs avec
`rowsPerColumn: 10`. Le calcul est dans [export PNG](png-export.fr.md#géométrie).

Si `file` annonce du JSON plutôt qu'un PNG, tu as reçu un corps de `404` ou de `503` : les
réponses d'erreur de cette route sont en JSON alors que la réponse de succès est une image.
Lis le fichier.

## 6. Le recolorer, et voir l'image changer

La route du dashboard applique, enregistre et refait le rendu en un seul appel. Donne au
classement un titre anglais et un en-tête cyan :

```bash
curl -s -X PUT http://localhost:44311/api/v1/scores/scoreboards/shiny_hunter \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title":"Shiny Hunters","description":"Shinies caught",
       "colors":{"titleBackground":"#22d3ee"}}'
```

```json
{"updated":true}
```

Cet appel prend une seconde ou deux, parce qu'il refait le rendu de tous les classements,
pas seulement celui-là. Récupère l'image à nouveau : l'en-tête est cyan avec du texte
**sombre**. Tu n'as pas choisi la couleur du texte, le rendu l'a calculée depuis la
luminosité de l'en-tête. Impose-la avec `"titleText":"#FFFFFF"` dans le même objet
`colors` si tu n'es pas d'accord.

Vérifie aussi le fichier sur le disque :

```bash
grep -A2 '"id": "shiny_hunter"' /chemin/vers/serveur/config/cobblecheesescore.json
```

Le changement est déjà persisté. C'est le seul chemin où une modification survit à un
redémarrage sans que tu touches au fichier — et la raison pour laquelle tu ne dois **pas**
éditer `config/cobblecheesescore.json` à la main pendant que le serveur tourne, puisque le
prochain `PUT` réécrira la configuration en mémoire par-dessus ton édition.

## 7. Confirmer que l'URL publique fonctionne

Rien à l'intérieur de Minecraft ne peut envoyer un token Bearer : l'affichage en jeu passe
donc par la route non authentifiée. Pas d'en-tête cette fois :

```bash
curl -sI http://localhost:44311/scores/api/images/shiny/shiny_hunter.png | head -3
```

```text
HTTP/1.1 200 OK
Content-Type: image/png
Cache-Control: public, max-age=60
```

Le segment `shiny` est libre : il ne décide que du nom de fichier qu'un navigateur
enregistrerait. `shiny_hunter` est le véritable identifiant du classement.

Maintenant, teste-la **depuis le réseau d'un joueur**, pas depuis le serveur :

```bash
curl -sI http://adresse-de-ton-serveur:44311/scores/api/images/shiny/shiny_hunter.png
```

L'URL est résolue par le jeu de chaque joueur, donc `localhost` échouera pour tout le monde
sauf toi. Un refus de connexion ici signifie que le port 44311 n'est pas joignable de
l'extérieur, et aucune configuration en jeu n'y changera rien.

⚠️ Cette route est ouverte à quiconque atteint le port 44311. Elle expose des noms de
joueurs et des statistiques sans authentification. C'est le compromis délibéré qui rend
l'affichage en jeu possible ; s'il ne te convient pas, arrête-toi ici et fais plutôt passer
la route authentifiée par un proxy de ton cru.

## 8. L'accrocher à un mur

Minecraft vanilla ne peut pas charger une image depuis une URL. Cette étape exige un mod de
cadre d'image sur les clients — WaterFrames est celui contre lequel tout cela a été
construit.

1. Note le rapport d'aspect de l'image depuis l'étape 5. 660 × 880 est plus haut que large ;
   un classement à trois colonnes en 1 880 × 880 est à peu près en 2:1. Se tromper là-dessus
   est la raison pour laquelle les classements finissent par avoir l'air étirés.
2. Installe le mod de cadre d'image sur les clients, et sur le serveur s'il a une moitié
   côté serveur.
3. Place un cadre, ouvre son écran, et règle son URL sur la route publique de l'étape 7, en
   utilisant la vraie adresse de ton serveur.
4. Dimensionne le cadre au rapport de l'étape 1.
5. Règle l'intervalle de rafraîchissement du cadre un peu plus long que
   `updateIntervalMinutes` — douze minutes pour un rendu de dix minutes. Rafraîchir plus
   vite n'apporte rien : le serveur envoie `max-age=60` et ne refait de toute façon pas le
   rendu entre deux passes planifiées.

> Le comportement du mod de cadre est une observation de la façon dont il a été utilisé
> ici, et non quelque chose que ce mod spécifie. Consulte sa documentation pour la manière
> actuelle de définir une URL.

Place-toi devant le cadre et lis l'horodatage en pied d'image. Marche à suivre complète et
modes de défaillance dans [export PNG](png-export.fr.md#lafficher-sur-une-carte-en-jeu).

## 9. Forcer un rafraîchissement et voir l'horodatage bouger

Plutôt que d'attendre dix minutes :

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:44311/api/v1/scores/refresh
```

```json
{"refreshed":true,"lastUpdate":1761700120000}
```

Le log encadre la passe :

```text
[CobbleCheeseScore] Collecting player data...
[CobbleCheeseScore] Collected data for 30 players
[CobbleCheeseScore] Updated scoreboard: shiny_hunter (12 players)
[CobbleCheeseScore] All scoreboards updated successfully
```

Chronomètre l'écart entre la première et la dernière ligne. C'est ce qui se produit tous
les `updateIntervalMinutes`, et c'est le chiffre à regarder avant d'augmenter `maxPlayers`
ou de raccourcir l'intervalle.

Attends une minute que le cache du cadre expire, regarde-le à nouveau : l'horodatage du
pied d'image a avancé. Toute la boucle fonctionne alors : disque → classement → PNG → HTTP
→ le mur.

## 10. Éteindre ce dont tu ne veux pas

Une installation neuve livre 34 classements, dont quatre classements Conseil 4 qui n'ont de
sens qu'avec [CobbleCap](../../CobbleCap/) installé. Sans lui, ils sont rendus comme des
en-têtes vides.

```bash
for id in conseil4_first_finish conseil4_fastest_time \
          conseil4_ranked_champions conseil4_ranked_failures; do
  curl -s -X PUT "http://localhost:44311/api/v1/scores/scoreboards/$id" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d '{"enabled":false}'
done
```

Les classements désactivés sont entièrement ignorés : pas de calcul, pas d'image, et un
`404` sur leur route d'image. Le semeur ne rajoute un classement Conseil 4 que lorsque son
identifiant est **absent** de la configuration : une entrée désactivée reste donc
désactivée d'un redémarrage à l'autre.

Pour cela, utilise `{"enabled": false}` sur `PUT /scoreboards/:id`, pas `PUT /config` —
cette seconde route journalise la demande et ne l'applique pas. La raison est dans
[dashboard](dashboard.fr.md#put-apiv1scoresconfig).

---

## Où aller ensuite

- [Classements](leaderboards.fr.md) — chaque champ d'un classement, ce que mesurent les 30
  identifiants, et comment ajouter une mesure de ton cru
- [Configuration](configuration.fr.md) — le fichier de configuration complet, y compris les
  neuf clés qui ne font rien
- [Mini-jeux](minigames.fr.md) — le registre séparé pour les résultats d'événements que tu
  saisis à la main
- [API dashboard](dashboard.fr.md) — chaque route, avec les rôles qu'elle exige réellement

---

← [Installation](installation.fr.md) · [Configuration](configuration.fr.md) →
