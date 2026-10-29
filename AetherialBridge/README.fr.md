# AetherialBridge

> 🇬🇧 [Read in English](README.md)

> Expose la génération d'îles flottantes d'Aetherial Islands comme un type de
> génération supplémentaire dans le créateur de dimensions de CheeseGuard.

CheeseGuard sait créer des dimensions serveur allumé : tu choisis un type de
génération dans le dashboard, tu donnes un nom à la dimension, et un nouveau
`ServerWorld` apparaît quelques instants plus tard. Par défaut cette liste contient
cinq entrées intégrées (`VOID`, `FLAT`, `NORMAL`, `NETHER`, `END`). AetherialBridge en
ajoute une sixième, étiquetée **Aetherial Islands**, qui construit son chunk generator
à partir des noise settings d'Aetherial Islands au lieu d'un preset vanilla. Tout le
reste de la dimension (point d'apparition, PvP, keep-inventory, snapshots, reset, mode
par joueur) reste du ressort de CheeseGuard.

Ce qu'il n'est pas : ce n'est pas un mod de génération de monde, et il n'embarque aucune
donnée de worldgen. Le terrain vient du datapack Aetherial Islands, que tu installes
séparément. Ce mod est l'adaptateur qui permet à CheeseGuard d'aller chercher ces
données pour une dimension à la fois, au lieu de laisser le datapack réécrire
`minecraft:overworld` pour tout le serveur.

La seconde moitié du mod est moins évidente à la lecture du nom. Le terrain d'îles
flottantes se trouve très en dessous de y=0, avec un fond océanique proche du bas du
monde, et plusieurs features que le vanilla et d'autres mods accrochent à tous les
biomes tagués overworld s'y comportent mal : les sources de lave creusent des tunnels
dans la colonne d'eau, et le balayage de placement d'un mod de minerais ne converge
jamais et déclenche le watchdog du serveur. Quatre mixins suppriment ces features dans
toute dimension dont le chunk generator annonce un niveau de la mer inférieur à -25, et
l'un d'eux écrit lui-même le fond marin, avec sable, gravier, argile, prismarine,
coraux, varech et herbes aquatiques. [La page génération du monde](docs/worldgen.fr.md)
détaille l'ensemble.

**Minecraft 1.21.1 · Fabric · Java 21 · 7 fichiers, ~485 lignes**

---

## ⚠️ À lire avant d'installer

**C'est un patch tiers, publié comme exemple de référence plus que comme produit
fini.**

- Ses cinq mixins s'injectent tous dans des classes qui ne lui appartiennent pas : trois
  dans du vanilla (`NoiseChunkGenerator`, `SpringFeature`, `LakeFeature`), et deux dans
  des classes appartenant à d'autres mods (`cobblemonraiddens` et `minerally`). Il a été écrit pour un modpack
  précis, et n'importe quelle mise à jour de ces mods peut le casser sans prévenir.
- Il attend une version **repackagée** d'Aetherial Islands dont les données de worldgen
  vivent dans l'espace de noms `aetherial_islands`. Le téléchargement Modrinth standard
  ne fournit pas cet espace de noms : tant que tu ne fournis pas un datapack qui le fait,
  le provider lève une erreur explicite et CheeseGuard crée quand même la dimension, avec
  un générateur de vide.
  [Installation](docs/installation.fr.md#lespace-de-noms-du-datapack) explique ce qui
  est attendu, et pourquoi.
- Fourni tel quel. Aucun support n'est assuré, et aucun correctif n'est promis quand
  les mods visés changent.

L'affirmation selon laquelle **l'overworld vanilla reste intact** est vraie, et
[architecture](docs/architecture.fr.md#pourquoi-loverworld-est-à-labri) montre le garde
qui la rend vraie. Ce garde est un test sur le niveau de la mer, pas sur l'identifiant
de dimension : lis cette section avant d'installer à côté un autre datapack à niveau de
la mer très bas.

---

## Prérequis

Les deux mods ci-dessous sont dans `depends`, ce qui veut dire que Fabric Loader refuse
de démarrer le serveur si l'un des deux manque.

| | Obligatoire | Remarques |
|---|---|---|
| [CheeseGuard](../CheeseGuard/) | oui | fournit le point d'extension `cheeseguard-gentype` et le gestionnaire de dimensions. Entraîne [CheeseDash](../CheeseDash/) à son tour. |
| [Aetherial Islands](https://modrinth.com/datapack/aetherial-islands) | oui | déclaré sous `mr_aetherial_islands`. Les données de worldgen elles-mêmes. |
| Fabric Loader | ≥ 0.15.0 | |
| Fabric API | n'importe quelle version 1.21.1 | |

Côté serveur pour tout ce qui compte. `fabric.mod.json` déclare `"environment": "*"`,
donc le jar se charge aussi sur un client, mais rien n'y tourne.

## Installation

CheeseGuard doit être **construit** avant que ce mod compile, parce que la dépendance
se résout sur son jar et non sur ses sources :

```bash
cd CheeseDash         && ./gradlew build     # gradlew.bat sous Windows
cd ../CheeseGuard     && ./gradlew build
cd ../AetherialBridge && ./gradlew build
```

Puis dépose quatre jars dans `mods/` : CheeseDash, CheeseGuard,
`AetherialBridge-1.0.0+1.21.1.jar`, et ton jar Aetherial Islands. Le détail complet,
les lignes de log à vérifier et la réserve sur l'espace de noms sont dans
[installation](docs/installation.fr.md).

## Documentation

| Page | Contenu |
|---|---|
| [Installation](docs/installation.fr.md) | Prérequis exacts, ordre de build, l'espace de noms de datapack dont le provider a besoin, lignes de log qui prouvent le chargement, désinstallation propre. |
| [Premiers pas](docs/getting-started.fr.md) | Parcours numéroté d'un serveur vierge jusqu'à se tenir debout sur une île flottante. |
| [Génération du monde](docs/worldgen.fr.md) | Le provider de type de génération, le seuil de niveau de la mer, le fond marin forcé et ses décorations, et les trois features supprimées. |
| [Architecture](docs/architecture.fr.md) | Classe par classe, les lignes exactes où se situe le couplage au code tiers, le threading, et les faiblesses connues. |
| [Dépannage](docs/troubleshooting.fr.md) | Dimension générée en vide, tunnels de lave, timeouts du watchdog, mod qui refuse de charger, et comment produire un rapport de bug utile. |

Il n'y a pas de `commands.fr.md` ni de `configuration.fr.md` : le mod n'enregistre
aucune commande et ne lit aucun fichier de configuration propre. Tout ce qui se règle
sur une dimension Aetherial Islands est un réglage de dimension CheeseGuard, stocké
dans `config/cheeseguard_dimensions.json` et édité depuis la vue **Dimensions** de
CheeseDash. Il n'y a pas non plus de `dashboard.fr.md`, parce que le mod n'enregistre
aucun module CheeseDash ; la seule surface du dashboard qu'il modifie est la liste
déroulante des types de génération que CheeseGuard sert déjà.
[Premiers pas](docs/getting-started.fr.md) fait le tour de cet écran.

## Commandes en un coup d'œil

Aucune. Le mod n'ajoute ni commande ni GUI en jeu. Les dimensions se créent depuis le
dashboard, et tu te déplaces dans l'une d'elles avec la téléportation vanilla ou le
bouton **TP** du dashboard.

## Licence

Publié sous [PolyForm Noncommercial 1.0.0](../LICENSE), avec une permission
additionnelle pour les opérateurs de serveur : tu peux faire tourner ce mod sur un
serveur qui accepte des dons, vend des rangs ou héberge une boutique. Tu ne peux pas
vendre le mod lui-même, l'inclure dans un modpack payant, ni vendre un service bâti
autour de son hébergement.

Aetherial Islands appartient à ses propres auteurs. Il n'est ni redistribué ici, ni
couvert par cette licence.

## Support

- **Bugs** → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- **Questions** → [Discord Arkensia](https://discord.gg/fYmPwMBjdB)

Garde l'avertissement ci-dessus en tête au moment de signaler quelque chose : une
casse provoquée par une mise à jour d'Aetherial Islands, de CobblemonRaidDens ou de
Minerally est le comportement attendu d'un mod-patch, et le correctif passe par du
code, pas par un réglage.
