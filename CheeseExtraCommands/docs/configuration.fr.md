[← CheeseExtraCommands](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](configuration.md)

# Configuration

**Ce mod n'a aucun fichier de configuration.** Pas de `config/cheeseextra.json`, pas de
fichier de propriétés, aucun réglage piloté par datapack.
`FabricLoader.getInstance().getConfigDir()` n'est appelé nulle part dans le code source,
et le mod n'écrit rien sur le disque de lui-même.

C'est une conséquence assumée de sa conception : chaque paramètre qu'un auteur de quêtes
voudrait régler est passé en argument de commande. Le seuil, le compte, l'objectif à
écrire et la valeur à y inscrire sont choisis à chaque appel. Un fichier de configuration
ne ferait que les dupliquer.

Ce qui suit est ce qui tient lieu de fichier de configuration : les objectifs de
scoreboard qui portent l'état, et la poignée de constantes compilées dans le jar.

---

## La configuration par argument de commande

Les arguments qu'un fichier de configuration porterait autrement :

| Ce que tu voudrais configurer | Où cela vit à la place |
|---|---|
| seuil d'EVs | `minEV` sur `/cheesecheck ev`, `1`–`252` |
| combien de stats doivent le franchir | `statCount`, `1`–`6`, défaut `1` |
| combien de Pokémon doivent qualifier | `teamCount`, `1`–`6`, défaut `1` |
| seuil d'IVs | `minIV` sur `/cheesecheck iv`, `1`–`31` |
| combien d'IVs doivent le franchir | `count`, `1`–`6` |
| quelle espèce | `species` sur `/cheesecheck pokemon` |
| combien de types de Balls | `count` sur `/cheesecheck ballvariety`, `1`–`20` |
| quel objectif reçoit le résultat | `scoreboard`, sur les quatre |
| quelle valeur est écrite | `value`, sur les quatre |

Tous prennent effet au prochain appel. Il n'y a rien à recharger et rien à redémarrer.

## L'état vit dans le scoreboard vanilla

Le seul stockage persistant du mod est le scoreboard du monde, enregistré par Minecraft
dans `world/data/scoreboard.dat`. Deux groupes d'objectifs comptent.

### Les objectifs que `/cheesecheck` écrit

N'importe quel objectif que tu nommes dans un argument `scoreboard`. Le mod ne réserve
aucun nom, ne les préfixe pas, et n'en tient aucune liste.

| Propriété | Valeur |
|---|---|
| Critère | `dummy` |
| Nom d'affichage | le nom de l'objectif lui-même, en texte brut |
| Type de rendu | `INTEGER` |
| Porteur du score | le **pseudo** du joueur, via `ScoreHolder.fromName(...)` |

Si l'objectif n'existe pas au moment où une vérification réussit, il est créé avec
exactement ces propriétés et un avertissement est écrit dans le log. Créer tes objectifs
à l'avance avec `/scoreboard objectives add <name> dummy "<display>"` te donne un nom
d'affichage lisible et transforme cet avertissement en véritable signal d'erreur.
[Pourquoi c'est important](commands.fr.md#le-piège-de-la-création-automatique).

L'écriture est inconditionnelle : elle fixe le score, elle ne l'incrémente pas, et elle
ne vérifie pas si la nouvelle valeur est supérieure à l'ancienne. Une vérification qui
passe deux fois écrit deux fois la même valeur, sans dommage. Une vérification pointée
sur un objectif qu'un autre système écrit aussi entrera en conflit avec lui.

### Les objectifs que le traqueur du tour de l'île lit

Le traqueur lit trois formes d'objectif chaque seconde et **n'en crée jamais aucune**. Un
objectif absent est lu comme `0`.

| Objectif | Lu pour | Signification |
|---|---|---|
| `jenny_intro` | chaque joueur connecté | `0` quête non prise, `1` tour en cours, `2` terminé et récompensé |
| `spawn_tour` | chaque joueur connecté | combien d'habitants le joueur a rencontrés, `0`–`26` |
| `<key>_met` | chacun des 26 habitants | `0` pas rencontré, `1` ou plus rencontré |

Crée-les avec `/scoreboard objectives add <name> dummy` avant d'espérer voir le HUD
apparaître. La liste complète des 26 clés, et la condition exacte d'affichage du HUD,
sont dans [le traqueur du tour de l'île](island-tour.fr.md).

## Les constantes compilées dans le jar

Les valeurs ci-dessous sont celles que tu mettrais dans un fichier de configuration s'il
en existait un.
En changer une signifie modifier le code source et reconstruire.

| Constante | Valeur | Fichier | Ce qu'elle contrôle |
|---|---|---|---|
| niveau de permission | `2` | `CheeseCheckCommand.register` | qui peut lancer `/cheesecheck` |
| `TICK_INTERVAL` | `20` | `IslandTourTracker` | ticks entre deux évaluations de l'état du HUD, donc une seconde |
| `TOTAL` | `26` | `IslandTourTracker` | déduit de la taille de la map `NPCS` |
| `NPCS` | 26 entrées | `IslandTourTracker` | clé → nom affiché pour les habitants |
| titre du HUD | `Habitants à rencontrer` | `IslandTourHud` | l'en-tête du HUD, écrit en dur en français |
| `COLUMNS` | `2` | `IslandTourHud` | combien de colonnes de noms le HUD dessine |
| `COL_WIDTH` | `70` | `IslandTourHud` | largeur de colonne en pixels mis à l'échelle ; un nom plus long chevauche la colonne suivante |
| `MARGIN_RIGHT` / `MARGIN_BOTTOM` | `5` / `5` | `IslandTourHud` | décalage du HUD par rapport au coin inférieur droit |
| palette | `BG`, `BORDER`, `TITLE`, `COUNTER`, `NAME` | `IslandTourHud` | valeurs ARGB reprenant la charte de couleurs de CheeseDash |
| nom de classe Cobblemon | `com.cobblemon.mod.common.Cobblemon` | `CobblemonReflect` | la sonde de disponibilité |
| nom de classe de l'énumération `Stats` | `com.cobblemon.mod.common.api.pokemon.stats.Stats` | `CobblemonReflect` | d'où viennent les six stats de combat |

Deux d'entre elles sont des bouchons au sens où elles ne conviendront pas à un autre
serveur : `NPCS` et le titre du HUD. Remplacer les 26 entrées de l'initialiseur statique
d'`IslandTourTracker` par tes propres clés est la seule manière prévue de réorienter le
HUD, et `TOTAL` suit automatiquement puisqu'il vaut `NPCS.size()`.

## Aucun fichier de langue

Il n'existe pas de dossier `assets/cheeseextra/lang/` : rien de tout cela n'est donc
traduisible. Chaque chaîne destinée aux joueurs est un `Text.literal` en français, écrit
en ligne dans `CheeseCheckCommand` et `IslandTourHud`. Les lignes de log, elles, sont en
anglais.

S'il te faut des messages en anglais, les chaînes à changer sont les neuf appels à
`sendMessage` de `CheeseCheckCommand` et la variable locale `title` dans
`IslandTourHud.render`. Les convertir en `Text.translatable` avec de vrais fichiers de
langue serait une contribution bienvenue ; le mod ne le fait pas aujourd'hui.

## Un exemple annoté, par souci de complétude

Puisqu'il n'y a pas de fichier de configuration à montrer, l'équivalent le plus proche
est la mise en place de scoreboards dont un NPC donneur de quête a besoin. Mets ceci dans
une fonction de datapack et lance-la une fois :

```mcfunction
# L'objectif dont dépend le NPC. "dummy" parce que rien dans le vanilla ne devrait
# jamais y toucher ; seuls /cheesecheck et la fonction de récompense l'écrivent.
scoreboard objectives add mira_progress dummy "Mira"

# Traqueur du tour de l'île, à l'échelle du serveur. Le traqueur les lit et ne les
# crée jamais : ils doivent donc exister avant que le HUD puisse apparaître.
scoreboard objectives add jenny_intro dummy "Jenny"
scoreboard objectives add spawn_tour dummy "Tour"

# Un par habitant. Il y en a 26 ; liste complète dans island-tour.fr.md.
scoreboard objectives add mira_met dummy
scoreboard objectives add bjorn_met dummy
# ...
```

Rien ici n'est propre à ce mod au-delà des noms. Ce sont des objectifs vanilla ordinaires,
et `/scoreboard objectives remove` en dispose.

---

← [Commandes](commands.fr.md) · [Traqueur du tour de l'île](island-tour.fr.md) →
