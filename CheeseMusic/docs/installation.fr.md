[← CheeseMusic](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](installation.md)

# Installation

## Quel côté a besoin du jar

**Les deux.** Ce n'est pas un choix, et c'est la première chose à comprendre.

`fabric.mod.json` déclare `"environment": "*"` et trois entrypoints :

| Entrypoint | Classe | Tourne sur |
|---|---|---|
| `main` | `com.cheesemusic.CheeseMusicMod` | le serveur, et le serveur intégré en solo |
| `client` | `com.cheesemusic.client.CheeseMusicClient` | le client seulement |
| `cheesedash` | `com.cheesemusic.dashboard.CheeseMusicDashboardModule` | le serveur, et seulement si CheeseDash est installé |

Le serveur décide *ce qui* doit jouer et envoie un paquet. Le client est ce qui le joue
réellement. Un serveur avec le jar et un client sans : le client n'a aucun récepteur
enregistré pour `cheesemusic:zone_music`, il jette le paquet et entend la musique vanilla
comme d'habitude. Un client avec le jar sur un serveur sans : personne n'envoie jamais de
paquet, donc le mod est inerte.

Le jar va donc dans le `mods/` du serveur **et** dans celui de chaque joueur. Un serveur
mixte fonctionne : les joueurs qui n'ont pas le jar ne sont pas cassés, ils n'entendent
simplement rien de particulier.

## Prérequis

| | Version | Nécessaire pour |
|---|---|---|
| Minecraft | exactement 1.21.1 (`~1.21.1`) | — |
| Fabric Loader | ≥ 0.16.0 | — |
| Fabric API | n'importe laquelle | événements de cycle de vie, réseau, ticks client |
| Java | ≥ 21 | compilé avec `options.release = 21` |

Ces quatre-là sont dans `depends` — le loader refuse de démarrer sans eux.

| Optionnel selon `fabric.mod.json` | En réalité |
|---|---|
| [CheeseGuard](../../CheeseGuard/) | `suggests`. Sans lui il n'y a aucune région, `resolveZoneMusic` renvoie `null` pour toute position, et aucun joueur ne reçoit jamais de piste. Le mod se charge et ne fait rien |
| [CheeseDash](../../CheeseDash/) | `suggests`. Sans lui il n'y a pas d'éditeur. Les assignations déjà présentes dans `config/cheesemusic.json` jouent toujours ; tu ne peux pas les changer sans éditer le fichier et redémarrer |
| [ReactiveMusic](https://modrinth.com/mod/reactive-music) | pas déclaré du tout, et **obligatoire en pratique sur chaque client**. Voir l'avertissement ci-dessous |
| [Cobblemon](https://modrinth.com/mod/cobblemon) | pas déclaré. Active seulement l'arrêt de la musique de zone pendant un combat |

### ReactiveMusic

Le lecteur côté client attend que ReactiveMusic confirme s'être éteint avant de lancer une
piste de zone, et cette confirmation ne peut venir que du hook ReactiveMusic. Le mod
absent, entrer dans une région assignée ne joue rien et laisse la musique vanilla étouffée
jusqu'à ce que le joueur ressorte ou se reconnecte. La trace exacte est dans
[lecture](playback.fr.md#ce-qui-se-passe-sans-reactivemusic).

⚠️ Installe ReactiveMusic sur chaque client, ou attends-toi au silence dans les régions
assignées.

## Ordre de build

CheeseMusic compile contre le jar de CheeseDash, résolu par un glob `fileTree` dans
`build.gradle` :

```groovy
modImplementation files(fileTree(dir: "../CheeseDash/build/libs",
    includes: ["cheesedash-*.jar"], excludes: ["*-sources.jar", "*-dev.jar"]))
```

Un glob vide n'est pas une erreur pour Gradle : il se résout silencieusement en rien, et le
build échoue ensuite sur `import com.cheesedash.WebDashboard` avec *package
com.cheesedash does not exist*. CheeseDash doit donc être construit d'abord :

```bash
cd CheeseDash   && ./gradlew build
cd ../CheeseMusic && ./gradlew build
```

`tools/build-all.sh`, à la racine du dépôt, parcourt chaque mod dans l'ordre des
dépendances et reste l'option sûre dès que tu en construis plusieurs.

CheeseGuard n'est **pas** une dépendance de compilation. `CheeseGuardHook` l'atteint
entièrement par réflexion, donc tu peux construire CheeseMusic sans que le jar de
CheeseGuard existe nulle part sur le disque. Idem pour Cobblemon et ReactiveMusic.

La sortie est :

```text
CheeseMusic/build/libs/cheesemusic-1.0.0+1.21.1.jar
```

La version vient de `mod_version=1.0.0+1.21.1` dans `gradle.properties`. Ignore
`cheesemusic-1.0.0+1.21.1-sources.jar`, produit par `withSourcesJar()` et qui n'a rien à
faire dans un dossier `mods/`.

## Installer

```bash
cp CheeseMusic/build/libs/cheesemusic-1.0.0+1.21.1.jar /srv/mc/mods/
```

Puis le même jar dans le `mods/` de chaque client, aux côtés de Fabric API et de
ReactiveMusic. Supprime d'abord tout `cheesemusic-*.jar` plus ancien : deux versions dans
un même dossier, c'est une erreur de chargement, pas une mise à jour.

## Ce que crée le premier démarrage

Deux choses, toutes deux sous le répertoire d'exécution du serveur.

**Le fichier de configuration**, écrit par `CheeseMusicConfig.load` quand il ne trouve
rien :

```text
config/cheesemusic.json
```

```json
{
  "enabled": true,
  "defaultFadeInTicks": 60,
  "defaultFadeOutTicks": 60,
  "checkIntervalTicks": 10,
  "defaultVolume": 0.7,
  "assignments": {}
}
```

Chaque clé est documentée dans [configuration](configuration.fr.md).

**Le squelette de resource pack**, créé par `SoundScanner.scan` quand le répertoire de sons
est absent :

```text
resourcepacks/CheeseMusic/pack.mcmeta
resourcepacks/CheeseMusic/assets/cheesemusic/sounds.json      (objet vide)
resourcepacks/CheeseMusic/assets/cheesemusic/sounds/          (répertoire vide)
```

Ce pack est une zone de préparation. Le serveur ne le distribue à personne ; c'est à toi de
le faire. [Packs de sons](sound-packs.fr.md) est la page pour ça.

## Prouver que c'est chargé

Côté **serveur**, à `SERVER_STARTED`, dans cet ordre :

```text
[CheeseMusic] Initializing...
[CheeseMusic] Initialized successfully!
[CheeseMusic] Loaded config with 0 assignments
[CheeseMusic] Created resource pack at: /srv/mc/resourcepacks/CheeseMusic
[CheeseMusic] Place .ogg files in: /srv/mc/resourcepacks/CheeseMusic/assets/cheesemusic/sounds
[CheeseMusic] CheeseGuard detected — zone music enabled
[CheeseMusic] Cobblemon detected — battle detection via getBattleByParticipatingPlayerId()
[CheeseMusic] Server started — 0 assignments loaded
```

La ligne `Loaded config with N assignments` est absente au tout premier démarrage, parce
qu'un fichier fraîchement écrit prend le retour anticipé de `load`. Les deux lignes de
resource pack n'apparaissent que si le répertoire a dû être créé ; cette branche rend la
main sans compter les pistes. À chaque démarrage suivant, elles sont remplacées par
`[CheeseMusic] Found <n> tracks in resource pack`.

Trois de ces lignes sont des diagnostics à lire chaque fois :

| Ligne | Signification |
|---|---|
| `CheeseGuard not installed — zone music disabled` | la réflexion n'a trouvé aucun `com.cheeseguard.CheeseGuardMod`. Rien ne jouera jamais |
| `CheeseGuard hook failed: <message>` | CheeseGuard est présent mais a changé de forme. Rien ne jouera, et c'est une casse au niveau du code |
| `Cobblemon BattleRegistry found but no suitable method` | l'étouffement en combat est désactivé ; la musique de zone jouera par-dessus la musique de combat |

Côté **client** :

```text
[CheeseMusic] Client initializing...
[CheeseMusic] ReactiveMusic detected — cooperative control enabled
[CheeseMusic] Client initialized
```

`ReactiveMusic not installed` à la place de la ligne du milieu, c'est l'avertissement
ci-dessus, et c'est la ligne la plus utile du log client. Elle peut aussi être
légitimement absente au démarrage : `ReactiveMusicHook.isAvailable()` réessaie à chaque
appel, parce que le champ `thread` de ReactiveMusic est nul jusqu'à ce que son propre
lecteur démarre. La ligne apparaît alors plus tard, au premier fondu. Ce nouvel essai a un
effet de bord quand ReactiveMusic est vraiment absent : `ReactiveMusic not installed` est
journalisé de nouveau à chaque `play` et à chaque `stop` reçus par le client, si bien qu'un
log qui en est rempli signifie simplement que le joueur a franchi beaucoup de frontières de
région.

## Vérifier en jeu

Avec CheeseDash en marche, la vérification la plus rapide ne demande aucun audio :

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:44311/api/v1/music/zones
```

Un tableau non vide prouve que le jar est chargé, que le module dashboard s'est enregistré
et que le hook CheeseGuard fonctionne. Un tableau vide avec `CheeseGuard detected` dans le
log signifie que tu n'as pas encore de région.

Pour le trajet complet, de rien à de la musique audible, suis
[premiers pas](getting-started.fr.md).

## Désinstaller

Supprime le jar du `mods/` du serveur et de celui de chaque client. Puis décide quoi faire
des restes, dont le mod n'enlève aucun :

| Ce qui reste | Supprimer ? |
|---|---|
| `config/cheesemusic.json` | sans risque. Garde-le si tu peux réinstaller — c'est la seule copie de tes assignations |
| `resourcepacks/CheeseMusic/` | tes fichiers `.ogg` vivent là. Sauvegarde avant de supprimer |
| Le pack sur chaque client | un resource pack ordinaire. Désactive-le dans **Options → Packs de ressources**, ou supprime le zip |
| `resource-pack=` dans `server.properties` | si tu as utilisé cette voie de distribution, vide la ligne, sinon les clients continuent de télécharger un pack dont plus rien ne demande les sons |

Rien n'est écrit dans la sauvegarde du monde, aucune entité n'est générée et aucun bloc
n'est posé : un monde qui a fait tourner CheeseMusic se charge proprement sans lui. Un
client qui garde le pack activé après la disparition du mod ne risque rien — les
événements sonores existent et plus rien ne les déclenche.

Une curiosité à connaître en partant : `src/main/resources/assets/cheesemusic/lang/`
contient `en_us.json` et `fr_fr.json` avec deux clés, `cheesemusic.zone.enter` et
`cheesemusic.zone.exit`. Aucun code ne référence ni l'une ni l'autre. Le mod n'envoie
aucun message de chat à aucun moment, donc il n'y a rien à traduire et rien à configurer
de ce côté.

---

← [CheeseMusic](../README.fr.md) · [Premiers pas](getting-started.fr.md) →
