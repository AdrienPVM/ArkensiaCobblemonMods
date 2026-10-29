[← CheeseItems](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](commands.md)

# Commandes

CheeseItems enregistre deux arbres de commandes. `/cheeseitem` est l'arbre administrateur
et exige le **niveau de permission 2** — celui qu'un serveur vanilla accorde à un
opérateur. `/morpho` n'exige aucune permission : n'importe quel joueur peut la lancer.

Un joueur sous le niveau 2 ne voit pas `/cheeseitem` dans la complétion par tabulation et
reçoit l'erreur vanilla *Unknown or incomplete command* s'il la tape quand même. Il n'y a
aucune vérification par sous-commande : l'appel `requires(...)` est posé sur le littéral
racine, donc l'arbre entier est verrouillé d'un coup.

---

## `/cheeseitem give`

```mcfunction
/cheeseitem give <player> <item_id> [quantity]
```

**Le joueur vient d'abord, l'identifiant de l'item ensuite.**

| Argument | Type | Requis | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `player` | mot unique | oui | le nom d'un joueur **connecté** ; se complète depuis la liste des joueurs du serveur | — |
| `item_id` | mot unique | oui | un identifiant de `config/cheeseitems.json` ; se complète depuis les définitions chargées | — |
| `quantity` | entier | non | `1`–`64`, imposé par Brigadier | `1` |

`quantity` est borné par le type d'argument lui-même : `0` ou `65` est rejeté par le
parseur de commande avant que le mod ne voie quoi que ce soit, et tu obtiens l'erreur de
plage de Brigadier, pas un message du mod.

Les deux fournisseurs de suggestions lisent l'état vivant. Un item créé depuis le
dashboard il y a une seconde se complète immédiatement, parce que le fournisseur parcourt
la liste de définitions en mémoire au lieu de relire le fichier.

### Ce que fait la commande

1. Cherche la définition par identifiant. Une définition absente interrompt la commande
   **avant** la vérification du nom du joueur : une faute dans l'identifiant masque donc
   une faute dans le nom.
2. Cherche le joueur dans le gestionnaire de joueurs. Les joueurs hors ligne ne peuvent
   pas être visés.
3. Construit un `ItemStack` neuf depuis la définition : item de base, tag d'identité
   `cheeseitems` dans `CUSTOM_DATA`, `usesLeft` quand la définition a un `maxUses`
   supérieur à zéro, puis le nom affiché, le lore, la taille de pile, l'effet de
   brillance, l'option incassable, la couleur de teinture et le custom model data. Tout
   est décrit dans [le format d'un item](items.fr.md).
4. Insère la pile dans l'inventaire du joueur. **Si l'inventaire est plein, la pile est
   jetée au sol à ses pieds** plutôt que perdue.
5. Rapporte le succès à l'appelant et au log du serveur (le retour est diffusé aux autres
   opérateurs, parce que `sendFeedback` est appelé avec `true`).

Une définition désactivée (`"enabled": false`) est quand même distribuée. L'option filtre
le comportement à l'utilisation, pas la remise de l'item — voir
[le format d'un item](items.fr.md#enabled).

### Exemple

```mcfunction
/cheeseitem give Steve quest_key 3
```

```text
Donné 3x Rusty Quest Key à Steve
```

En anglais (`en_us.json`) : `Gave 3x Rusty Quest Key to Steve`. Le nom affiché n'est jamais
traduit : il vient de la définition.

### Échecs

| Message | Cause |
|---|---|
| `Item introuvable : <id>` | aucune définition avec cet identifiant. Vérifie avec `/cheeseitem list` |
| `Joueur introuvable : <name>` | le nom ne correspond à aucun joueur **connecté** |

Les deux sont des refus : rien n'est donné, rien n'est écrit sur le disque.

⚠️ Si le `baseItem` de la définition n'est pas un identifiant d'item enregistré, la pile
est construite à partir de ce que renvoie `Registries.ITEM.get(...)` pour un identifiant
inconnu, c'est-à-dire `minecraft:air`. La commande rapporte quand même un succès et le
joueur ne reçoit rien de visible. C'est l'erreur de saisie la plus courante — le mod ne
valide pas `baseItem`.

---

## `/cheeseitem list`

```mcfunction
/cheeseitem list
```

Aucun argument. Affiche chaque définition chargée, à l'appelant uniquement
(`sendFeedback` avec `false`), une ligne par définition.

```text
--- CheeseItems (3) ---
 [ON] quest_key — Rusty Quest Key (minecraft:tripwire_hook)
 [ON] morpho_ditto — Ditto Morpho (minecraft:slime_ball)
 [OFF] old_token — Legacy Token (minecraft:paper)
```

| Champ | Signification |
|---|---|
| `[ON]` / `[OFF]` | l'option `enabled` de la définition, en vert ou en rouge |
| identifiant | le `item_id` que tu passes à `give` |
| après le tiret | `displayName`, sans couleur |
| entre parenthèses | `baseItem` |

Le marqueur `[ON]`/`[OFF]` et l'item de base entre parenthèses sont construits avec
`Text.literal`, donc ils restent en anglais quelle que soit la langue du client. Seule la
ligne d'en-tête est traduisible.

Sans aucune définition tu obtiens `Aucun item défini.` et la commande renvoie `0`. Sinon le
résultat numérique est le nombre de définitions, ce qui la rend utilisable dans un
`/execute store`.

---

## `/cheeseitem reload`

```mcfunction
/cheeseitem reload
```

Aucun argument. Relit `config/cheeseitems.json` depuis le disque, puis parcourt
l'inventaire de chaque joueur connecté pour remettre les items déjà distribués en accord
avec les nouvelles définitions.

```text
CheeseItems config rechargée.
```

Ce que fait réellement cette passe sur les inventaires, et ce qu'elle ne peut pas faire,
c'est la partie à lire avant de compter dessus :
[rechargement à chaud et items existants](items.fr.md#rechargement-à-chaud-et-items-déjà-dans-les-inventaires).
En résumé — les propriétés visuelles et l'item de base sont réparés sur place, mais une
définition que tu **supprimes** laisse chaque copie déjà dans l'inventaire d'un joueur
derrière elle, comme une pile inerte qui ne correspond plus à rien.

C'est aussi la commande à lancer après avoir édité le JSON à la main. Les écritures du
dashboard passent par le même code, donc inutile de recharger après une modification
faite depuis le dashboard.

---

## `/cheeseitem morpho grant`

```mcfunction
/cheeseitem morpho grant <player> <morpho_id>
```

| Argument | Type | Requis | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `player` | mot unique | oui | le nom d'un joueur connecté ; se complète depuis la liste des joueurs | — |
| `morpho_id` | mot unique | oui | un identifiant d'entrée du catalogue de Morphos ; se complète depuis le catalogue chargé | — |

Ajoute une entrée du catalogue au Codex de Morpho d'un joueur, sans qu'il ait à utiliser un
item. L'entrée est enregistrée avec l'espèce, la forme et l'option shiny du catalogue, plus
l'horodatage courant ; le codex du joueur est poussé vers son client, et l'appelant comme
le joueur sont prévenus.

```mcfunction
/cheeseitem morpho grant Steve ditto
```

```text
Morpho Ditto donnée à Steve.
```

et le joueur voit `🧬 Ditto ajoutée à votre Codex de Morpho !`.

| Message | Cause |
|---|---|
| `Joueur introuvable : <name>` | pas connecté. Vérifié en premier |
| `Morpho introuvable dans le catalogue : <id>` | aucune entrée de ce nom dans `config/cheeseitems_morphos.json` |
| `<name> possède déjà cette morpho.` | le codex contient déjà cet identifiant. Rien n'est modifié |

Tout le sous-système est décrit dans [le système de Morphos](morphos.fr.md).

---

## `/morpho`

```mcfunction
/morpho
```

Aucun argument, **aucun niveau de permission**, et elle ne fonctionne que pour un joueur —
une source console ou bloc de commande obtient
`This command can only be used by a player.`

La commande envoie un unique paquet S2C qui demande au client de l'appelant d'ouvrir
l'écran du Codex de Morpho. Rien ne se passe côté serveur au-delà de ce paquet, donc la
lancer pour un joueur dont le client n'a pas CheeseItems ne fait absolument rien : le
payload n'a aucun gestionnaire là-bas et il est ignoré.

Si l'envoi lève une exception, le joueur voit `Failed to open Morpho Codex: <message>` en
rouge et la commande renvoie `0`. Ce message et celui de la source console sont tous deux
des `Text.literal`, donc ils restent en anglais même sur un client français.

Le même écran s'ouvre avec la touche `J`, et la morpho active s'active avec `H`, les deux
étant réassignables dans les commandes du client, catégorie `CheeseItems`. La commande
existe pour les joueurs qui ont effacé leurs raccourcis et pour les dialogues de PNJ qui
lancent une commande.

---

## Piloter le mod depuis ailleurs

Rien de ce qui précède n'est la seule porte d'entrée :

- **Dashboard** — le module `items` pour le CRUD des définitions et le `give`, le module
  `morphos` pour le catalogue. Voir [l'API dashboard](dashboard.fr.md).
- **Actions de commande** — le champ `useActions` d'une définition lance des commandes
  serveur arbitraires quand l'item est utilisé, c'est ainsi qu'un item finit par donner une
  morpho, téléporter un joueur ou démarrer une quête sans écrire une ligne de Java. Voir
  [useActions](items.fr.md#useactions).

---

← [Premiers pas](getting-started.fr.md) · [Configuration](configuration.fr.md) →
