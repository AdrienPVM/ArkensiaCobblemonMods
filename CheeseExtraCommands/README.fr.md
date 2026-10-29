# CheeseExtraCommands

> 🇬🇧 [Read in English](README.md)

> Quatre commandes de vérification qui permettent à un arbre de dialogue de NPC
> d'inspecter l'équipe Cobblemon d'un joueur et d'écrire la réponse dans un scoreboard.

Un système de quêtes bâti sur des dialogues de NPC a un angle mort : il sait distribuer
des items et lire des scoreboards, mais il ne sait pas regarder dans l'équipe d'un
joueur. `/cheesecheck` comble ce trou. Il répond à quatre questions (un Pokémon de cette
équipe porte-t-il assez d'EVs, assez d'IVs élevés, telle espèce est-elle présente, ces
Pokémon ont-ils été capturés dans assez de types de Balls différents) et, en cas de
réussite, il écrit une valeur dans l'objectif de scoreboard que tu lui désignes. La
branche de dialogue suivante du NPC lit cet objectif et avance.

Le choix de conception qui compte : en cas de réussite la commande écrit le score et
renvoie `1` ; en cas d'échec elle n'écrit rien et renvoie `0`. La progression de la quête
reste donc là où elle était, et le NPC ressort sa réplique « tu y travailles encore » à
la prochaine interaction. Aucune logique d'annulation, aucune seconde commande.

Le mod embarque aussi le traqueur du **tour de l'île d'Arkensia** : une surveillance de
scoreboards côté serveur qui pousse un HUD en bas à droite listant les habitants que le
joueur n'a pas encore rencontrés. Cette moitié est câblée sur une île précise et ses 26
NPCs. Elle est inerte ailleurs, et c'est documenté franchement comme tel.

**Minecraft 1.21.1 · Fabric · Java 21 · 7 fichiers source, ~840 lignes**

⚠️ Tous les messages destinés aux joueurs sont écrits en dur **en français**, via
`Text.literal`. Il n'existe aucun fichier de langue : il n'y a donc rien à traduire, et
aucun moyen de passer en anglais sans modifier `CheeseCheckCommand.java`.

---

## Prérequis

| | |
|---|---|
| **Obligatoire** | Fabric Loader ≥ 0.15.0 · Fabric API · Minecraft 1.21.x · Java 21 |
| **Optionnel, mais indispensable en pratique** | [Cobblemon](https://modrinth.com/mod/cobblemon) 1.6.1 |

Cobblemon est atteint uniquement par réflexion (`CobblemonReflect`) : il n'y a donc
aucune dépendance à la compilation ni au chargement. Sans Cobblemon installé, le mod se
charge, la commande s'enregistre, et chaque vérification annonce au joueur
`[CheeseCheck] Cobblemon non disponible.` avant de renvoyer `0`.

Aucune dépendance à CheeseDash. Ce mod n'enregistre ni module de dashboard ni route
REST.

## Installation

```bash
cd CheeseExtraCommands && ./gradlew build
```

Dépose `build/libs/cheeseextra-1.0.0+1.21.1.jar` dans le dossier `mods/` du serveur.
Aucun fichier de configuration n'est à créer. Le log du serveur affiche :

```text
[CheeseExtra] Initializing CheeseExtraCommands...
[CheeseExtra] Commands + Arkensia island-tour tracker registered.
[CheeseExtra] /cheesecheck command registered (ev, iv, pokemon, ballvariety)
```

Procédure complète : [installation](docs/installation.fr.md).

## Documentation

| Page | Contenu |
|---|---|
| [Installation](docs/installation.fr.md) | Prérequis, côté sur lequel installer le mod, lignes de log qui prouvent le chargement, désinstallation propre |
| [Premiers pas](docs/getting-started.fr.md) | Une chaîne complète : un NPC pose une condition, une commande la vérifie, une récompense en dépend |
| [Commandes](docs/commands.fr.md) | Les quatre sous-commandes argument par argument, chaque valeur de retour, chaque message d'échec, les schémas d'intégration |
| [Configuration](docs/configuration.fr.md) | Il n'y a aucun fichier de configuration. Ce qui en tient lieu : les objectifs de scoreboard, et les constantes compilées dans le jar |
| [Traqueur du tour de l'île](docs/island-tour.fr.md) | Le sous-système HUD d'Arkensia : la machine à états en scoreboards, le payload S2C, les 26 NPCs écrits en dur |
| [Architecture](docs/architecture.fr.md) | Arborescence des packages, la couche de réflexion et pourquoi elle scanne par type, le threading, les faiblesses connues |
| [Dépannage](docs/troubleshooting.fr.md) | Symptôme, cause, remède, dont les trois raisons pour lesquelles une vérification renvoie zéro sans rien dire |

## Les commandes en un coup d'œil

Une commande, `/cheesecheck`, quatre sous-commandes, **niveau de permission 2** sur tout
l'arbre. Chaque sous-commande se termine par un couple `<scoreboard> <value>` :
l'objectif à écrire, et la valeur à y inscrire quand la vérification passe.

| Commande | Passe quand |
|---|---|
| `/cheesecheck ev <player> <minEV> <scoreboard> <value> [statCount] [teamCount]` | au moins `teamCount` Pokémon de l'équipe ont chacun `statCount` stats à `minEV` EVs ou plus |
| `/cheesecheck iv <player> <count> <minIV> <scoreboard> <value> [teamCount]` | au moins `teamCount` Pokémon de l'équipe ont chacun `count` IVs à `minIV` ou plus |
| `/cheesecheck pokemon <player> <species> <scoreboard> <value>` | l'équipe contient cette espèce |
| `/cheesecheck ballvariety <player> <count> <scoreboard> <value>` | l'équipe compte au moins `count` types de Balls de capture distincts |

Deux pièges reviennent souvent. La sous-commande `ev` n'a **aucun sélecteur de stat** :
elle compte combien de stats franchissent le seuil, elle ne permet pas d'exiger la
Vitesse en particulier. Et les quatre lisent **l'équipe seule**, jamais les boîtes du PC.

## Avertissements francs

- Le retour part vers le **joueur ciblé**, pas vers l'appelant de la commande. Un
  administrateur qui lance une vérification sur quelqu'un d'autre ne voit rien dans son
  propre chat.
- Les scores sont écrits sur le **pseudo** du joueur, pas sur son UUID. Un joueur qui
  change de pseudo perd sa progression de quête.
- Un objectif de scoreboard absent est créé automatiquement au lieu d'être refusé : une
  faute de frappe dans son nom produit donc un objectif silencieux que personne ne lira
  jamais.
- Le traqueur du tour de l'île est propre à Arkensia et n'affichera rien sur un autre
  serveur, sauf si tu recrées ses objectifs de scoreboard.

## Licence

[PolyForm Noncommercial 1.0.0](../LICENSE), avec une permission additionnelle pour les
opérateurs de serveur. Tu peux faire tourner ce mod sur un serveur qui reçoit des dons
ou vend des rangs ; tu ne peux pas vendre le mod lui-même.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Discord Arkensia](https://discord.gg/fYmPwMBjdB)
