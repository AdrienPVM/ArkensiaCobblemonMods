[← CheeseMusic](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](sound-packs.md)

# Packs de sons

**CheeseMusic ne contient aucun audio.** Fouille le jar et tu y trouveras seize fichiers de
classes, deux petits fichiers de langue, une configuration de mixin et `fabric.mod.json` —
aucun `.ogg`, aucun `sounds.json`, aucun répertoire `assets/cheesemusic/sounds/`. Chaque
piste que tu entends dans une zone est un fichier que tu as fourni.

C'est ce qui fait de cette page celle qu'on ne peut pas sauter. Le mod te donne la moitié
de la chaîne de distribution : il gère un resource pack **sur le serveur**, maintient son
`sounds.json` honnête, et assainit tes noms de fichiers. L'autre moitié — amener ce pack
sur le client de chaque joueur — il ne la fait pas, et ne peut pas la faire, parce que les
resource packs sont un mécanisme vanilla que le mod ne touche jamais.

---

## Pourquoi un resource pack, et pas un datapack

Les fichiers de sons sont des ressources client. Le client est la machine avec les
haut-parleurs, et il ne jouera qu'un événement sonore qu'il peut résoudre depuis ses
propres resource packs chargés. Un datapack vit sur le serveur et ne contient aucun audio.
Donc :

- **Resource pack** — oui. C'est la seule voie.
- **Datapack** — non. CheeseMusic ne lit rien depuis `data/` et n'enregistre aucun
  chargeur de datapack.
- **Événements sonores vanilla** — non, et cela mérite d'être dit clairement.
  `ZoneMusicTracker.sendPlay` construit l'identifiant du son comme `"cheesemusic:" +
  track`, donc chaque identifiant que reçoit le client vit dans le namespace
  `cheesemusic`. Aucun moyen de pointer une assignation vers
  `minecraft:music.nether.basalt_deltas`, ni vers l'événement sonore d'un autre mod. Si tu
  veux de la musique vanilla dans une zone, copie le `.ogg` depuis les ressources vanilla
  vers ton propre pack, sous le nom de ton choix.

---

## Le pack que le serveur te construit

`SoundScanner` tourne une fois, depuis `CheeseMusicMod.onServerStarted`, juste après le
chargement de la configuration. Il travaille sur deux chemins, tous deux relatifs au
répertoire d'exécution du serveur :

```text
<racine serveur>/resourcepacks/CheeseMusic/                              racine du pack
<racine serveur>/resourcepacks/CheeseMusic/pack.mcmeta                    écrit si absent
<racine serveur>/resourcepacks/CheeseMusic/assets/cheesemusic/sounds.json régénéré à chaque scan
<racine serveur>/resourcepacks/CheeseMusic/assets/cheesemusic/sounds/     tes fichiers .ogg
```

Si le répertoire `sounds/` n'existe pas, le scanner le crée, écrit `pack.mcmeta` s'il n'y en
a pas, et consigne où il a tout mis :

```text
[CheeseMusic] Created resource pack at: .../resourcepacks/CheeseMusic
[CheeseMusic] Place .ogg files in: .../resourcepacks/CheeseMusic/assets/cheesemusic/sounds
```

Le `pack.mcmeta` généré est exactement ceci :

```json
{"pack":{"pack_format":34,"description":"CheeseMusic — Custom zone music"}}
```

`34` est le format de resource pack de la 1.21.1. Le fichier n'est écrit **que s'il
n'existe pas déjà** : tu peux donc le remplacer par ta propre description, ajouter un
`pack.png`, ou élargir la plage `supported_formats`, et le mod laissera ta version
tranquille.

⚠️ Le répertoire s'appelle `resourcepacks/`, qui sur un serveur dédié n'est pas un
répertoire que vanilla lit pour quoi que ce soit. Rien sur le serveur ne charge ce pack.
C'est une zone de préparation dont le seul but est d'être remise aux clients.

### Ce que fait un scan

À chaque scan — au démarrage du serveur, puis après chaque téléversement ou suppression de
piste depuis le dashboard — `SoundScanner.scan()` :

1. Vide sa liste de pistes en mémoire.
2. Parcourt `sounds/` à la recherche des fichiers `*.ogg` uniquement. Les sous-répertoires
   ne sont pas visités ; le glob est plat.
3. Assainit chaque nom de base (section suivante). Si le nom assaini diffère du vrai nom,
   **le fichier est renommé sur le disque**, et le renommage est consigné :
   ```text
   [CheeseMusic] Renamed 'Volcano Theme (Final Mix).ogg' -> 'volcano_theme_final_mix.ogg'
   ```
4. Trie les noms par ordre alphabétique.
5. Réécrit `sounds.json` de zéro.
6. Consigne le total : `[CheeseMusic] Found 7 tracks in resource pack`.

L'étape 3 modifie tes fichiers. Si tu as besoin de conserver les noms d'origine, garde tes
masters ailleurs et copie des duplicatas assainis dans `sounds/`.

### L'assainissement des noms, exactement

`SoundScanner.sanitize` existe parce que le chemin d'un `Identifier` Minecraft n'accepte que
`[a-z0-9/._-]`, et qu'un identifiant invalide lève une exception côté client au lieu
d'échouer proprement. La transformation, dans l'ordre :

| Étape | Effet |
|---|---|
| Minuscules | `Volcano` → `volcano`, avec `Locale.ROOT` pour qu'un serveur en locale turque se comporte comme les autres |
| Espaces | ` ` → `_` |
| Tirets | `—` (cadratin) et `–` (demi-cadratin) → `-` |
| Virgules | `,` → `_` |
| Filtre | tout caractère restant hors de `[a-z0-9/._-]` est **supprimé**, pas remplacé |
| Compactage | les suites de `_` deviennent un seul `_` |
| Rognage | les `_` de début et de fin sont retirés |
| Résultat vide | devient le littéral `track` |

Deux conséquences à garder en tête. Les caractères accentués sont purement supprimés, donc
`forêt.ogg` devient `fort.ogg` — renomme ce genre de fichier toi-même avant le
téléversement si cela te gêne. Et comme le filtre supprime au lieu de substituer,
`Theme #1.ogg` et `Theme 1.ogg` s'assainissent tous deux en `theme_1` ; le second scanné
reçoit un `_2` en suffixe (`theme_1_2`) pour ne pas écraser le premier.

### Le `sounds.json` généré

Une entrée par piste, écrite par `regenerateSoundsJson()` :

```json
{
  "spawn_theme": {
    "sounds": [{
      "name": "cheesemusic:spawn_theme",
      "stream": true
    }]
  },
  "volcano": {
    "sounds": [{
      "name": "cheesemusic:volcano",
      "stream": true
    }]
  }
}
```

L'identifiant de l'**événement** sonore est donc `cheesemusic:spawn_theme`, et le champ
`name` pointe le client vers `assets/cheesemusic/sounds/spawn_theme.ogg`. Identifiant
d'événement et nom de fichier coïncident, et c'est pour cette raison qu'une assignation
stocke le nom nu, et rien d'autre.

`"stream": true` sur chaque entrée est volontaire : un son streamé est décodé au fil de la
lecture au lieu d'être chargé entièrement en mémoire, ce qui est ce que tu veux pour une
piste de quatre minutes. Cela veut aussi dire que le son ne peut pas être positionnel, ce
qui n'a aucune importance ici — `ZoneMusicSoundInstance` met `relative = true` et joue à la
position de l'auditeur.

Le fichier est écrasé à chaque scan. Les éditions manuelles sont perdues au prochain
démarrage du serveur. Si tu as besoin d'un sous-titre, d'une courbe de volume ou de
plusieurs variantes `.ogg` sous un même événement, il te faut un second pack à toi —
celui-ci est généré par la machine.

---

## De bout en bout : un `.ogg` jusqu'à une zone qui le joue

L'exemple complet. Un serveur avec CheeseGuard et CheeseDash en marche, une région appelée
`volcano_cave`, et un fichier `Volcano Theme.ogg` sur ton portable.

### 1. Convertir l'audio en Ogg Vorbis

Minecraft joue de l'Ogg Vorbis. Un conteneur `.ogg` contenant de l'Opus ne jouera pas. Avec
ffmpeg :

```bash
ffmpeg -i "Volcano Theme.wav" -c:a libvorbis -q:a 5 -ar 44100 "Volcano Theme.ogg"
```

`-q:a 5` tombe autour de 160 kbps, largement suffisant pour de la musique de fond et qui
garde le pack petit. Le mono divise encore la taille par deux si la piste ne dépend pas de
la stéréo (`-ac 1`). Garde chaque fichier sous le plafond de 50 Mo du téléversement
dashboard.

### 2. Amener le fichier dans le pack du serveur

Soit tu le copies toi-même :

```bash
scp "Volcano Theme.ogg" user@server:/srv/mc/resourcepacks/CheeseMusic/assets/cheesemusic/sounds/
```

…puis tu redémarres le serveur pour que `scan()` le prenne en compte et le renomme. Soit tu
le téléverses depuis le dashboard, qui scanne immédiatement et n'exige aucun redémarrage :

```bash
curl -X POST \
  -H "Authorization: Bearer $TOKEN" \
  --data-binary "@Volcano Theme.ogg" \
  "http://localhost:44311/api/v1/music/tracks/upload?name=Volcano%20Theme.ogg"
```

```json
{"ok":true,"track":"volcano_theme"}
```

Le champ `track` de la réponse est le nom assaini, et c'est le seul nom qui fonctionne à
partir d'ici. Note que le nom de fichier voyage dans le **paramètre de requête** `name`, et
pas dans un corps multipart ; le corps, ce sont les octets bruts. L'endpoint rejette tout
nom qui ne finit pas par `.ogg`, ou qui contient `..`, `/` ou `\`.

### 3. Vérifier que le serveur le voit

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:44311/api/v1/music/tracks
```

```json
["volcano_theme"]
```

Si le nom est absent, le fichier n'est pas dans `sounds/`, n'est pas un `.ogg`, ou se trouve
dans un sous-répertoire.

### 4. Distribuer le pack aux clients

C'est l'étape que le mod ne fait pas. Copie tout le répertoire
`resourcepacks/CheeseMusic/` hors du serveur et amène-le sur chaque client, par l'une des
trois voies de [Livraison](#livraison-aux-clients) plus bas. Choisis-en une maintenant,
parce qu'aucune des étapes suivantes ne produit de son sans elle.

La voie la plus rapide pour un test : zippe le contenu de `resourcepacks/CheeseMusic/` de
sorte que `pack.mcmeta` soit à la racine du zip, et dépose le zip dans le
`.minecraft/resourcepacks/` de ton propre client. Puis active-le dans **Options → Packs de
ressources**.

### 5. Vérifier que le client résout le son

En jeu, pack activé :

```mcfunction
/playsound cheesemusic:volcano_theme music @s
```

Entendre la piste veut dire que le pack est chargé et que l'identifiant d'événement est
bon. L'erreur vanilla *No sound with that name* veut dire que le client ne le résout pas —
le pack n'est pas activé, ou `sounds.json` ne liste pas ce nom, ou le `.ogg` manque dans le
zip.

Corrige ça avant de toucher aux assignations. Tout ce qui suit suppose que le client sait
résoudre l'identifiant.

### 6. Créer l'assignation

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"zoneId":"volcano_cave","zoneName":"Volcano Cave","tracks":["volcano_theme"],"mode":"SEQUENTIAL","volume":0.5,"fadeInTicks":40,"fadeOutTicks":80}' \
  http://localhost:44311/api/v1/music/assignments
```

La réponse renvoie en écho l'assignation stockée, et `config/cheesemusic.json` est réécrit
sur-le-champ. Note que `tracks` contient `volcano_theme` — le nom nu, sans préfixe
`cheesemusic:`. Le serveur ajoute le préfixe quand il envoie le paquet.

### 7. Entrer dans la région

Place-toi hors de `volcano_cave`, puis entre. Dans l'intervalle de `checkIntervalTicks` (une
demi-seconde par défaut), ReactiveMusic s'éteint et la piste démarre.
`GET /api/v1/music/status` te liste désormais :

```json
[{"playerUuid":"...","zoneId":"volcano_cave","playerName":"Steve"}]
```

Du silence à cette étape, avec tout ce qui précède vérifié, c'est presque toujours
ReactiveMusic absent du client — voir l'avertissement dans le [README](../README.fr.md) et
[lecture](playback.fr.md#ce-qui-se-passe-sans-reactivemusic).

---

## Livraison aux clients

Trois voies. Aucune n'est du code CheeseMusic ; les trois sont des mécanismes Minecraft
standards ou tiers, décrits ici parce que le mod est inutile sans l'une d'elles.

### Resource pack serveur (`server.properties`)

Héberge le zip sur n'importe quel serveur HTTP et pointe le jeu dessus :

```properties
resource-pack=https://files.example.com/cheesemusic-pack-1.zip
resource-pack-sha1=<sha1 du zip>
require-resource-pack=true
```

Les clients le téléchargent à la connexion et l'appliquent automatiquement. Le SHA-1 doit
correspondre, sinon les clients retéléchargent à chaque fois ; change le **nom de fichier**
chaque fois que le contenu change, parce que les clients mettent agressivement en cache par
URL.

C'est le moins de travail et le plus fragile : un endpoint HTTP à maintenir en vie, et une
limite vanilla de taille de téléchargement qu'un pack musical conséquent peut dépasser.

### Overrides de modpack

Si tes joueurs installent via un pack CurseForge ou Modrinth, mets le zip dans le
`overrides/resourcepacks/` du pack et il est livré avec le mod. Les joueurs l'obtiennent à
l'installation et à chaque mise à jour du pack. C'est ce qui monte le mieux en charge, et
c'est ainsi qu'un serveur chargé en musique procède normalement.

### Un outil de synchronisation de mods

Les outils qui synchronisent des fichiers du serveur vers le client à la connexion
(AutoModpack et ses semblables) peuvent pousser le resource pack en même temps que les jars
de mods. Le comportement dépend entièrement de cet outil, pas de CheeseMusic — considère
tout ce qui est écrit ici à son sujet comme une observation sur la façon dont ces outils
sont généralement configurés, et non comme une spécification.

---

## La licence de l'audio

Le dépôt ne livre aucun audio, et la licence du code ne dit rien de ce que tu diffuses à
travers lui. La plupart des bandes originales de jeux et de films ne sont pas sous licence
permettant leur redistribution dans un resource pack, aussi privé que ton serveur puisse
paraître. De la musique commandée, du domaine public ou explicitement licenciée pour les
packs ferme la question. Elle se règle entre toi et les ayants droit, pas entre toi et ce
mod.

---

← [Premiers pas](getting-started.fr.md) · [Musique de zone](zone-music.fr.md) →
