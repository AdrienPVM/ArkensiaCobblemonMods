# CheeseKart

> 🇬🇧 [Read in English](README.md)

> Course de karts bâtie sur des bateaux invisibles et la physique OpenBoatUtils.

Un joueur s'assoit dans un bateau qu'il ne voit pas, et ce bateau ne se conduit en rien
comme un bateau. Adhérence, accélération, vitesse de braquage, force de saut, gravité et
contrôle en vol sont tous poussés vers le client sous forme de surcharges de physique :
la même piste peut donner la sensation d'une spéciale de rallye ou d'une patinoire,
selon le preset avec lequel tu as lancé le pilote.

Les presets s'écrivent depuis le dashboard CheeseDash et s'appliquent à chaud : modifie
l'adhérence d'un preset pendant que trois personnes sont en pleine course, et leurs
karts changent sous eux au paquet suivant. C'est tout l'objet du mod : une piste se
règle en la conduisant, pas en redémarrant le serveur.

Les administrateurs mettent les joueurs en kart avec une commande ou un bouton du
dashboard. Aucune commande n'est destinée aux joueurs : les pilotes ne tapent jamais
rien.

**Minecraft 1.21.1 · Fabric · Java 21 · 14 fichiers source, ~1 380 lignes**

---

## Prérequis

| | |
|---|---|
| **Requis** | [CheeseDash](../CheeseDash/) · Fabric Loader ≥ 0.16.0 · Fabric API · Java 21 |
| **Fortement recommandé** | [OpenBoatUtils](https://modrinth.com/mod/openboatutils), sur le client |
| **Optionnel** | [CobbleShift](../CobbleShift/) — permet de transformer les pilotes en Pokémon |

C'est OpenBoatUtils qui modifie réellement la physique, et il doit se trouver **sur le
client du pilote**. Sans lui, un kart n'est qu'un bateau invisible avec le comportement
d'origine d'un bateau : ça marche toujours, mais ce n'est pas un kart. Le mod détecte ce
cas et reste silencieux plutôt que d'échouer — voir
[comment la physique est transmise](docs/physics.fr.md).

## Installation

```bash
cd CheeseDash && ./gradlew build     # CheeseKart compile contre son jar
cd ../CheeseKart && ./gradlew build
```

Dépose `build/libs/cheesekart-1.0.0+1.21.1.jar` et le jar CheeseDash dans le `mods/` du
serveur. Au premier démarrage, le mod écrit `config/cheesekart.json` avec trois presets
(`rally`, `drift`, `parkour`) et affiche :

```text
[CheeseKart] Created default presets (rally, drift, parkour)
[CheeseKart] Server started — 3 presets loaded
```

Procédure complète : [installation](docs/installation.fr.md).

## Documentation

| Page | Contenu |
|---|---|
| [Installation](docs/installation.fr.md) | Prérequis, ordre de compilation, ce que crée le premier démarrage, comment vérifier le chargement, désinstallation propre |
| [Premiers pas](docs/getting-started.fr.md) | Un tutoriel : d'un serveur neuf à un pilote qui conduit un kart que tu as réglé toi-même |
| [Commandes](docs/commands.fr.md) | Chaque sous-commande, argument par argument, avec les niveaux de permission, les sorties et les messages d'échec |
| [Configuration](docs/configuration.fr.md) | `cheesekart.json` en entier : les 16 champs d'un preset, leurs valeurs par défaut, leurs plages et leurs interactions, plus les trois presets fournis |
| [Physique et sessions](docs/physics.fr.md) | Comment un kart est réellement construit, le protocole de paquets OpenBoatUtils, le mode verrouillé, les transformations CobbleShift, le cycle de vie d'une session |
| [API dashboard](docs/dashboard.fr.md) | Les neuf endpoints REST sous `/api/v1/kart`, les formes de requête et de réponse, les rôles exigés |
| [Architecture](docs/architecture.fr.md) | Découpage en packages, responsabilités des classes, threads, les deux mixins client, points d'extension, faiblesses connues |
| [Dépannage](docs/troubleshooting.fr.md) | Symptôme, cause, correctif — dont les trois modes de défaillance qui, vus de l'extérieur, sont identiques |

## Les commandes en un coup d'œil

Toutes exigent le niveau de permission 2.

| Commande | Effet |
|---|---|
| `/cheesekart start <player> <preset> [species]` | met un joueur en kart |
| `/cheesekart stop <player>` | met fin à la session de ce joueur |
| `/cheesekart stopall` | met fin à toutes les sessions |
| `/cheesekart list` | les sessions actives, avec leur preset et leur durée |
| `/cheesekart presets` | les presets configurés, avec leurs principales valeurs de physique |

L'ordre des arguments s'inverse facilement : **le joueur d'abord**, le preset ensuite.
Détails dans [commandes](docs/commands.fr.md).

## Dashboard

S'enregistre sous le préfixe `kart` : tout se trouve donc sous `/api/v1/kart/*` — CRUD
des presets, liste des sessions actives, contrôles start/stop/stopall. Un `PUT` sur un
preset le réapplique à toutes les sessions qui l'utilisent.

CheeseGuard peut aussi piloter le mod pendant un événement chronométré, via deux types
d'effet, `KART_START_ALL` et `KART_STOP_ALL`.

## Licence

[PolyForm Noncommercial 1.0.0](../LICENSE), avec une permission additionnelle pour les
opérateurs de serveur. Tu peux le faire tourner sur un serveur qui reçoit des dons ou
vend des rangs ; tu ne peux pas vendre le mod lui-même.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Discord Arkensia](https://discord.gg/fYmPwMBjdB)
