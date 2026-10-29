# CheeseMusic

> 🇬🇧 [Read in English](README.md)

> Musique d'ambiance liée aux régions CheeseGuard, poussée vers le client de chaque
> joueur au moment où il entre et où il sort.

Le serveur surveille où se trouve chaque joueur. Quand quelqu'un pénètre dans une région
qui porte une assignation musicale, le serveur lui envoie un paquet nommant la playlist,
et son propre client la joue sur le canal Musique. Il sort, le serveur envoie un stop.
Personne ne tape de commande, et deux joueurs postés de part et d'autre d'une frontière
entendent des choses différentes.

Une assignation, c'est un identifiant de région, une liste de pistes, un mode de lecture
(`RANDOM`, `SEQUENTIAL` ou `SHUFFLE`), un volume et deux durées de fondu. Tu les écris
depuis le dashboard CheeseDash, qui sert aussi à téléverser les fichiers `.ogg` et
maintient le `sounds.json` du resource pack en accord avec ce qui est sur le disque. La
musique de combat Cobblemon reprend la main quand un combat démarre : le mod arrête la
musique de zone le temps du combat, puis la relance.

Aucun fichier audio n'est livré avec ce mod, et acheminer ton audio jusqu'aux joueurs est
la partie qui demande du vrai travail. Le serveur te construit un squelette de resource
pack, mais il ne peut pas le distribuer lui-même.
[Packs de sons](docs/sound-packs.fr.md) traite la chaîne complète, de bout en bout.

**Minecraft 1.21.1 · Fabric · Java 21 · 16 fichiers source, ~1 710 lignes**

---

## Prérequis

| | |
|---|---|
| **Obligatoire** | Fabric Loader ≥ 0.16.0 · Fabric API · Java 21 |
| **Obligatoire en pratique, sur chaque client** | [ReactiveMusic](https://modrinth.com/mod/reactive-music) — voir l'avertissement plus bas |
| **Obligatoire en pratique, sur le serveur** | [CheeseGuard](../CheeseGuard/) — fournit les régions |
| **Optionnel** | [CheeseDash](../CheeseDash/) — le seul éditeur d'assignations · [Cobblemon](https://modrinth.com/mod/cobblemon) — détection des combats |

`fabric.mod.json` place CheeseGuard et CheeseDash dans `suggests`, pas dans `depends`, et
le mod se charge et tourne effectivement sans l'un ni l'autre. Il ne sert simplement à
rien : sans CheeseGuard, la recherche de région ne renvoie jamais rien et aucun joueur ne
reçoit jamais de piste.

## ⚠️ ReactiveMusic n'est pas optionnel, dans l'état où est le code

Le lecteur côté client est une machine à états qui attend la confirmation que
ReactiveMusic s'est bien éteint avant de lancer une piste de zone. Quand ReactiveMusic est
absent, cette confirmation n'arrive jamais, parce que le drapeau dont elle dépend n'est
posé que dans le hook ReactiveMusic. Le résultat, lu directement dans
[`ZoneMusicPlayer`](src/main/java/com/cheesemusic/client/ZoneMusicPlayer.java) et
[`ReactiveMusicHook`](src/main/java/com/cheesemusic/client/ReactiveMusicHook.java) :

- entrer dans une région assignée ne joue absolument rien, et
- le lecteur de musique vanilla reste étouffé par le mixin tant que le joueur y reste —
  la région est donc muette. Ressortir, ou se reconnecter, rétablit la musique vanilla.

Installe ReactiveMusic sur chaque client, ou attends-toi au silence. Le raisonnement
complet, lignes à l'appui, est dans
[lecture](docs/playback.fr.md#ce-qui-se-passe-sans-reactivemusic). Cela n'a pas été
vérifié en jeu — c'est une lecture du code — mais le chemin ne laisse pas de place au
doute.

## Installation

```bash
cd CheeseDash  && ./gradlew build     # CheeseMusic compile contre son jar
cd ../CheeseMusic && ./gradlew build
```

Dépose `build/libs/cheesemusic-1.0.0+1.21.1.jar` dans le `mods/` du serveur **et** dans
celui de chaque joueur. La lecture se fait côté client ; un client sans le jar ignore le
paquet et n'entend rien.

Au premier démarrage, le mod écrit `config/cheesemusic.json` et crée un resource pack vide
sous `resourcepacks/CheeseMusic/` :

```text
[CheeseMusic] Created resource pack at: .../resourcepacks/CheeseMusic
[CheeseMusic] Place .ogg files in: .../resourcepacks/CheeseMusic/assets/cheesemusic/sounds
[CheeseMusic] Server started — 0 assignments loaded
```

Procédure complète : [installation](docs/installation.fr.md).

## Documentation

| Page | Contenu |
|---|---|
| [Installation](docs/installation.fr.md) | Prérequis, quel côté a besoin de quoi, ordre de build, ce que crée le premier démarrage, les lignes de log qui prouvent le chargement, désinstallation propre |
| [Premiers pas](docs/getting-started.fr.md) | Un tutoriel : d'un serveur vierge jusqu'à entendre ta propre piste en entrant dans une région |
| [Packs de sons](docs/sound-packs.fr.md) | Où vont les `.ogg`, comment `sounds.json` est généré, l'assainissement des noms de fichiers, et les trois façons réelles d'amener le pack sur les clients |
| [Musique de zone](docs/zone-music.fr.md) | Le modèle d'assignation, comment une position devient une piste, les assignations `tag:`, la priorité, l'étouffement en combat, le paquet |
| [Lecture](docs/playback.fr.md) | La machine à états du client, ce que font réellement les durées de fondu, la coopération avec ReactiveMusic, le mixin de musique vanilla |
| [Configuration](docs/configuration.fr.md) | `config/cheesemusic.json` clé par clé, lesquelles sont lues et lesquelles ne le sont pas, et un fichier complet annoté |
| [API dashboard](docs/dashboard.fr.md) | Les onze routes REST sous `/api/v1/music`, forme des requêtes et des réponses, rôles exigés, et les codes de statut que chacune peut produire |
| [Architecture](docs/architecture.fr.md) | Packages, responsabilités des classes, threading, le mixin, points d'extension, code mort et faiblesses connues |
| [Dépannage](docs/troubleshooting.fr.md) | Symptôme, cause, remède — en commençant par les plusieurs raisons distinctes pour lesquelles une région ne joue rien |

## Les commandes en un coup d'œil

CheeseMusic n'enregistre **aucune commande**. Il n'y a aucun arbre Brigadier dans les
sources. Tout passe par le dashboard, ou par l'édition de `config/cheesemusic.json` suivie
d'un redémarrage du serveur.

## Dashboard

Enregistré sous le préfixe `music`, donc les routes vivent sous `/api/v1/music/*` : CRUD
des assignations, téléversement et suppression de pistes, liste des régions CheeseGuard,
configuration globale, et qui écoute quoi en ce moment. Dans la barre latérale, il
apparaît comme **CheeseMusic** 🎵 avec la description *Musique de zones*. Il ne publie rien
sur aucun canal WebSocket.

Les lectures sont ouvertes à n'importe quelle session authentifiée ; les six routes
d'écriture exigent `OPERATOR` ou plus. Ces gardes ont été réparés le 29 octobre 2026 — une
version antérieure envoyait le `403` puis appliquait quand même la modification, de sorte
qu'un compte `READ_ONLY` pouvait éditer les assignations. Si tu fais tourner un jar plus
ancien, lis [dashboard](docs/dashboard.fr.md#rôles) avant de te fier à ses rôles.

## État réel du mod

Rien dans le dépôt ne suggère que ce mod ait été éprouvé en jeu. La dépendance à
ReactiveMusic décrite plus haut, et la clé de configuration qui peut faire sauter la
boucle de tick du serveur
([`checkIntervalTicks`](docs/configuration.fr.md#checkintervalticks)), sont exactement le
genre de choses qu'une soirée d'usage réel aurait attrapées.

Les deux hooks tiers sont de la réflexion sur des internes — le `thread`, le
`gainPercentage` et l'`additionalSilence` de ReactiveMusic, le `BattleRegistry` de
Cobblemon — donc les deux cesseront de fonctionner quand ces mods changeront de forme. Ils
échouent en silence plutôt qu'en plantant, ce qui est le bon compromis et aussi la raison
pour laquelle une casse passe facilement inaperçue.

## Licence

[PolyForm Noncommercial 1.0.0](../LICENSE), avec une permission additionnelle pour les
opérateurs de serveur. Tu peux faire tourner ça sur un serveur qui prend des dons ou vend
des grades ; tu ne peux pas vendre le mod lui-même.

La musique que tu diffuses à travers lui est une autre affaire : aucun audio n'est
distribué avec ce dépôt, et la plupart des bandes originales de jeux et de films ne sont
pas sous licence permettant leur redistribution dans un modpack. Cette partie se règle
entre toi et les ayants droit.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Discord Arkensia](https://discord.gg/fYmPwMBjdB)
