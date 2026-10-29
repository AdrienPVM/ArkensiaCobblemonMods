[← CheeseMusic](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](getting-started.md)

# Premiers pas

À la fin de cette page, tu franchiras une ligne invisible dans ton monde et tu entendras
démarrer une piste que tu as choisie, pendant qu'un joueur resté deux blocs derrière toi
entendra autre chose. Compte une trentaine de minutes, dont l'essentiel passé à convertir
de l'audio et à amener un resource pack sur un client.

Ce qu'il te faut avant de commencer : un serveur Fabric 1.21.1 que tu peux redémarrer et
sur lequel tu peux copier des fichiers, les droits d'opérateur dessus, un fichier musical
que tu as le droit d'utiliser, et un client dans lequel tu peux installer des mods.

---

## 1. Construire et installer quatre mods

CheeseMusic seul ne fait rien de visible. L'ensemble qui fonctionne, c'est CheeseMusic,
CheeseGuard (les régions), CheeseDash (l'éditeur) et ReactiveMusic (côté client, voir
[installation](installation.fr.md#reactivemusic)).

```bash
cd CheeseDash   && ./gradlew build
cd ../CheeseGuard && ./gradlew build
cd ../CheeseMusic && ./gradlew build
```

CheeseDash d'abord — CheeseMusic compile contre son jar. Copie les trois jars sur le
serveur :

```bash
cp CheeseDash/build/libs/cheesedash-*+1.21.1.jar   /srv/mc/mods/
cp CheeseGuard/build/libs/cheeseguard-*+1.21.1.jar /srv/mc/mods/
cp CheeseMusic/build/libs/cheesemusic-*+1.21.1.jar /srv/mc/mods/
```

Laisse de côté tout `-sources.jar`. Sur ton client, installe Fabric API, ReactiveMusic et
le même `cheesemusic-1.0.0+1.21.1.jar`.

**Tu dois voir :** trois jars dans `mods/`, et aucun `-sources` parmi eux.

## 2. Démarrer le serveur et lire le log

```bash
cd /srv/mc && java -Xmx4G -jar fabric-server-launch.jar nogui
```

**Tu dois voir**, parmi les lignes de démarrage :

```text
[CheeseMusic] Initialized successfully!
[CheeseMusic] Created resource pack at: /srv/mc/resourcepacks/CheeseMusic
[CheeseMusic] Place .ogg files in: /srv/mc/resourcepacks/CheeseMusic/assets/cheesemusic/sounds
[CheeseMusic] CheeseGuard detected — zone music enabled
[CheeseMusic] Server started — 0 assignments loaded
```

`CheeseGuard not installed — zone music disabled` à la place de l'avant-dernière ligne
signifie que le jar de CheeseGuard manque ou n'a pas pu se charger. Corrige ça avant de
continuer : rien de ce qui suit ne peut fonctionner sans lui. Les autres variantes sont
listées dans [installation](installation.fr.md#prouver-que-cest-chargé).

Le serveur vient d'écrire `config/cheesemusic.json` et le resource pack vide.

## 3. Obtenir une session dashboard

Trouve ton master token dans `config/cheesedash.json`, puis :

```bash
TOKEN=$(curl -s -X POST http://localhost:44311/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"token":"<ton master token>"}' | jq -r .sessionId)
echo $TOKEN
```

**Tu dois voir :** une chaîne commençant par `ses_`. Toutes les commandes ci-dessous
réutilisent `$TOKEN`. Tu peux aussi faire tout cela depuis l'interface web de CheeseDash
sur `http://localhost:44311` ; la forme `curl` est utilisée ici parce qu'elle montre
exactement ce qui se passe.

## 4. Créer une région

En jeu, en tant qu'opérateur, marque deux coins opposés d'une petite zone — une pièce, une
cour, n'importe quoi dont tu peux entrer et sortir en quelques secondes :

```mcfunction
/cg wand
```

Clic gauche sur un coin, clic droit sur l'autre. Ou laisse tomber la baguette et place-toi
dans chaque coin :

```mcfunction
/cg pos1
/cg pos2
/cg create Volcano Cave
```

`create` prend un nom gourmand : les espaces n'ont pas besoin de guillemets. CheeseGuard
dérive l'identifiant de la région depuis le nom.

```mcfunction
/cg list
```

**Tu dois voir :** ta région listée, avec son identifiant entre crochets. Retiens cet
identifiant — la suite de cette page suppose `volcano_cave`.

Contre-vérifie depuis le dashboard, qui est ce que CheeseMusic voit lui-même :

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:44311/api/v1/music/zones
```

```json
[{"id":"volcano_cave","name":"Volcano Cave","priority":0,"tags":[]}]
```

Un `[]` vide ici alors que la région apparaît dans `/cg list` signifie que le hook
CheeseGuard par réflexion est cassé — voir
[dépannage](troubleshooting.fr.md#get-apiv1musiczones-renvoie-un-tableau-vide-alors-que-cg-list-montre-des-régions).

## 5. Convertir ton audio en Ogg Vorbis

Minecraft joue de l'Ogg Vorbis et rien d'autre.

```bash
ffmpeg -i "Volcano Theme.wav" -c:a libvorbis -q:a 5 -ar 44100 "Volcano Theme.ogg"
```

**Attendu :** un fichier bien en dessous de 50 Mo, et plus long que cinq secondes — une
piste plus courte tombe dans la boucle de redémarrage décrite dans
[lecture](playback.fr.md#le-délai-de-garde-de-100-ticks).

## 6. Téléverser la piste

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  --data-binary "@Volcano Theme.ogg" \
  "http://localhost:44311/api/v1/music/tracks/upload?name=Volcano%20Theme.ogg"
```

```json
{"ok":true,"track":"volcano_theme"}
```

**Tu dois voir :** le nom assaini dans le champ `track`. C'est le seul nom qui fonctionne à
partir d'ici — `Volcano Theme` n'existe pas, du point de vue du jeu. Le téléversement a
aussi réécrit `sounds.json`, sans redémarrage.

Confirme :

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:44311/api/v1/music/tracks
```

```json
["volcano_theme"]
```

## 7. Mettre le resource pack sur ton client

L'étape qui fait trébucher tout le monde, parce que le mod ne peut pas la faire pour toi.
Le pack du serveur est dans `/srv/mc/resourcepacks/CheeseMusic/`. Zippe son **contenu**, de
sorte que `pack.mcmeta` soit à la racine du zip et pas dans un sous-dossier :

```bash
cd /srv/mc/resourcepacks/CheeseMusic && zip -r ~/cheesemusic-pack.zip .
```

Copie ce zip dans le `.minecraft/resourcepacks/` de ton client, puis active-le dans
**Options → Packs de ressources**.

Vérifie maintenant, en jeu :

```mcfunction
/playsound cheesemusic:volcano_theme music @s
```

**Tu dois entendre :** ta piste. C'est le point de contrôle de tout le tutoriel — si ça
échoue ici, aucune assignation ne fonctionnera jamais. La commande annonce un succès que le
son existe ou non : c'est ton oreille qui juge. Un silence, avec
`Unable to play unknown soundEvent: cheesemusic:volcano_theme` dans le log client,
signifie que le client ne résout pas l'événement : le pack n'est pas activé, ou `pack.mcmeta` est
imbriqué un répertoire trop profond dans le zip.
[Packs de sons](sound-packs.fr.md#livraison-aux-clients) couvre les trois vraies voies de
distribution, pour quand tu dépasseras le test sur ton propre client.

Coupe le son avant de continuer :

```mcfunction
/stopsound @s music cheesemusic:volcano_theme
```

## 8. Assigner la piste à la région

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"zoneId":"volcano_cave","zoneName":"Volcano Cave","tracks":["volcano_theme"],"mode":"SEQUENTIAL","volume":0.6,"fadeInTicks":40,"fadeOutTicks":80}' \
  http://localhost:44311/api/v1/music/assignments
```

**Tu dois voir :** l'assignation renvoyée en écho. `config/cheesemusic.json` la contient
désormais, et le changement est actif — pas de redémarrage, pas de commande de
rechargement.

L'entrée de `tracks` est `volcano_theme`, sans préfixe `cheesemusic:`. Le serveur ajoute le
préfixe quand il envoie le paquet ; l'ajouter toi-même produit un identifiant invalide et
du silence.

## 9. Entrer

Place-toi hors de la région. Entre.

**Tu dois entendre :** ReactiveMusic s'éteindre en deux secondes (40 ticks), puis ta piste
démarrer. Le serveur revérifie les positions tous les dix ticks, donc la transition
commence dans la demi-seconde qui suit le franchissement de la frontière.

Confirme que le serveur est d'accord :

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:44311/api/v1/music/status
```

```json
[{"playerUuid":"069a79f4-...","zoneId":"volcano_cave","playerName":"Steve"}]
```

Apparaître dans cette liste signifie que le serveur a envoyé le paquet `play`. Du silence
alors que tu y figures veut dire que le problème est côté client, et presque toujours
ReactiveMusic manquant — commence par
[dépannage](troubleshooting.fr.md#jentre-dans-une-région-assignée-et-je-nentends-rien-du-tout).

Ressors. La piste est coupée et ReactiveMusic remonte en quatre secondes (80 ticks). La
coupure est franche par conception : les durées de fondu déplacent le volume de
ReactiveMusic, jamais celui de la piste CheeseMusic. Si tu veux que la piste s'estompe,
estompe-la dans le `.ogg`. Le raisonnement est dans
[lecture](playback.fr.md#les-fondus-sont-ceux-de-reactivemusic-pas-les-tiens).

## 10. Ajouter une deuxième piste et un mode de lecture

```bash
curl -s -X PUT -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"zoneId":"volcano_cave","zoneName":"Volcano Cave","tracks":["volcano_theme","volcano_calm"],"mode":"SHUFFLE","volume":0.6,"fadeInTicks":40,"fadeOutTicks":80}' \
  http://localhost:44311/api/v1/music/assignments/volcano_cave
```

Téléverse d'abord `volcano_calm`, comme à l'étape 6. `PUT` est un remplacement complet, pas
un patch : chaque champ omis retombe sur son défaut au lieu de garder son ancienne valeur.

**Attendu :** si tu étais déjà dans la région, rien ne change à l'oreille, et rien ne
changera tant que tu y restes : une modification n'envoie aucun paquet, ton client continue
donc de boucler l'ancienne liste d'une seule piste. Sors puis rentre pour recevoir la
nouvelle liste.

## Et ensuite

- Une même piste sur plusieurs régions d'un coup : donne un tag CheeseGuard aux régions et
  assigne `tag:<tag>` au lieu d'un identifiant —
  [musique de zone](zone-music.fr.md#ordre-de-résolution).
- Régler l'intervalle de vérification et les fondus globaux —
  [configuration](configuration.fr.md).
- Amener le pack à trente joueurs plutôt qu'un —
  [packs de sons](sound-packs.fr.md#livraison-aux-clients).

---

← [Installation](installation.fr.md) · [Packs de sons](sound-packs.fr.md) →
