# CheeseVanity

> 🇬🇧 [Read in English](README.md)

> Les joueurs masquent leur propre armure et leurs trinkets sans les retirer.

Un joueur ouvre son inventaire, survole le slot qui contient une cape, appuie sur `=`, et la
cape cesse d'être dessinée sur son personnage. Elle reste équipée et continue de faire ce
qu'elle fait ; elle est simplement devenue invisible. Un deuxième appui sur `=` la fait
revenir. C'est tout le mod.

La préférence est stockée sur le serveur, par UUID de joueur, et diffusée à tous les joueurs
connectés. Le changement n'est donc pas une illusion privée côté client : tu le vois en
troisième personne, et tous les autres joueurs qui font tourner le mod voient le même modèle
que toi. ⚠️ Un joueur qui se connecte **sans** CheeseVanity dans son dossier `mods/` voit
encore tout ton équipement, et rien côté serveur ne peut y changer quoi que ce soit — le
masquage est assuré par des mixins de rendu côté client. Si la cohérence visuelle compte sur
ton serveur, fais-en un mod client obligatoire.

Contrairement au reste de ce dépôt, CheeseVanity est un outil de **joueur** et non
d'administrateur. Il n'y a ni commandes, ni niveaux de permission, ni module dashboard. Le
seul levier d'un opérateur est un fichier de configuration à trois clés qui décide quelles
sortes de slots les joueurs ont le droit de masquer.

**Minecraft 1.21.1 · Fabric · Java 21 · 10 fichiers source, ~630 lignes**

---

## Prérequis

| | |
|---|---|
| **Requis** | Fabric Loader ≥ 0.16.0 · [Fabric API](https://modrinth.com/mod/fabric-api) · Java 21 |
| **Optionnel** | [Trinkets](https://modrinth.com/mod/trinkets) — compilé contre la 3.10.0, non embarqué |

Nécessaire des **deux côtés** : le serveur stocke et diffuse la préférence, le client est la
seule chose capable d'agir dessus.

⚠️ Sans Trinkets, seuls les quatre slots d'armure vanilla peuvent être masqués — et
`enableVanillaArmor` vaut `false` par défaut, donc sur une installation neuve sans Trinkets
le mod ne fait rien du tout jusqu'à ce que tu modifies la configuration. Ce défaut est
délibéré : sur le serveur Arkensia, un autre mod gère la visibilité des armures.

Aucune dépendance CheeseDash et aucune dépendance vers un mod frère. Celui-ci se construit
et tourne tout seul.

## Installation

```bash
cd CheeseVanity && ./gradlew build
```

Dépose `build/libs/cheesevanity-1.0.0+1.21.1.jar` dans le `mods/` du serveur **et** dans le
`mods/` de chaque client. Au premier démarrage, le serveur écrit
`config/cheesevanity.json` et journalise :

```text
[CheeseVanity] Loaded — vanilla armor: false, trinkets: true
```

Procédure complète : [installation](docs/installation.fr.md).

## Documentation

| Page | Contenu |
|---|---|
| [Installation](docs/installation.fr.md) | Prérequis, quel côté a besoin du jar, ce que crée le premier lancement, les quatre vérifications qui prouvent que ça marche, désinstallation propre |
| [Premiers pas](docs/getting-started.fr.md) | Un tutoriel joueur : masquer un trinket, le voir disparaître, le faire confirmer par quelqu'un, tout remettre |
| [Contrôles](docs/commands.fr.md) | La touche `=` documentée entrée par entrée, et tous ses échecs silencieux |
| [Configuration](docs/configuration.fr.md) | `cheesevanity.json` : les trois clés, l'ordre dans lequel elles sont évaluées, et un exemple complet annoté |
| [Qui voit quoi](docs/visibility.fr.md) | Le modèle des slot keys, l'aller-retour client/serveur, la frontière exacte du masquage, et où vit la préférence |
| [Architecture](docs/architecture.fr.md) | Découpage des packages, les deux stratégies de rendu opposées, le threading, chaque mixin, et une liste franche des faiblesses |
| [Dépannage](docs/troubleshooting.fr.md) | Symptôme, cause, remède — les cinq raisons différentes pour lesquelles `=` semble ne rien faire |

Il n'y a pas de `dashboard.fr.md` : le mod n'enregistre aucun module CheeseDash et n'expose
aucune route REST.

## Contrôles en un coup d'œil

Aucune commande n'existe. Une seule touche fait tout, et n'importe quel joueur peut
l'utiliser.

| Entrée | Effet |
|---|---|
| `=` sur un slot Trinkets | bascule la visibilité de ce trinket sur ton modèle |
| `=` sur l'un de tes quatre slots d'armure | la même chose, si `enableVanillaArmor` vaut `true` |

Les slots masqués sont marqués dans l'écran d'inventaire par un remplissage rouge translucide
et une croix rouge. Détails, y compris les raisons d'un refus silencieux, dans
[contrôles](docs/commands.fr.md).

## Avertissements honnêtes

- **La touche `=` est codée en dur.** Ce n'est pas un raccourci Minecraft : elle n'apparaît
  pas dans Options → Commandes et ne peut pas être réassignée en jeu.
- **Rien n'explique à un joueur pourquoi une bascule a été refusée.** Chaque rejet est
  silencieux côté client ; un refus de la configuration est écrit dans le log serveur, les autres n'y laissent aucune trace.
- **Le masquage des trinkets est une astuce.** Le mixin vide les slots de trinkets masqués
  le temps d'un appel de rendu et y remet les piles ensuite, parce que le renderer de
  Trinkets n'offre aucune prise par slot. Il cassera quand Trinkets changera cette méthode,
  et il cassera **en silence** : les injections sont déclarées `require = 0`.
- **Le log de débogage est livré en `INFO`.** Chaque appui sur `=` écrit deux lignes dans le
  log client.
- **La configuration est lue une fois au démarrage.** Pas de rechargement ; redémarre le
  serveur après l'avoir modifiée.
- **Resserrer la configuration ne démasque rien** de ce qui est déjà stocké. Le fichier de
  données doit être édité à la main, serveur arrêté.

## Licence

[PolyForm Noncommercial 1.0.0](../LICENSE), avec une permission additionnelle pour les
opérateurs de serveur. Tu peux le faire tourner sur un serveur qui reçoit des dons ou vend
des rangs ; tu ne peux pas vendre le mod lui-même.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Discord Arkensia](https://discord.gg/fYmPwMBjdB)
