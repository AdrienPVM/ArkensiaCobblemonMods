[← CheeseExtraCommands](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](commands.md)

# Commandes

Une commande, `/cheesecheck`, et quatre sous-commandes : `ev`, `iv`, `pokemon` et
`ballvariety`. Tout l'arbre est enregistré dans `CheeseCheckCommand.register` et gardé
par un unique `requires(src -> src.hasPermissionLevel(2))` posé sur le literal racine.

Tout ce qui figure sur cette page a été lu dans `CheeseCheckCommand.java` et
`CobblemonReflect.java`. L'ancien README décrivait `/cheesecheck ev <player> <stat>
<value>`, qui n'a jamais existé dans ce code ; voir [ce que l'ancien README annonçait de
faux](#ce-que-lancien-readme-annonçait-de-faux).

---

## La forme commune aux quatre

Chaque sous-commande suit le même contrat :

```text
/cheesecheck <type> <player> <critères...> <scoreboard> <value>
```

| Étape | Comportement |
|---|---|
| 1 | Résoudre le joueur ciblé depuis l'argument `player` |
| 2 | Si Cobblemon est absent, prévenir le joueur et renvoyer `0`. Rien d'autre ne se produit |
| 3 | Lire l'**équipe** de la cible par réflexion |
| 4 | Compter combien de membres de l'équipe satisfont les critères |
| 5 | **Réussite** → écrire `value` dans l'objectif `scoreboard`, message vert au joueur, renvoyer `1` |
| 6 | **Échec** → message rouge au joueur, aucune écriture, renvoyer `0` |

Trois conséquences en découlent, et ce sont les trois points sur lesquels on se trompe.

**L'équipe seule.** `CobblemonReflect.getPartyPokemon` résout le
`PokemonStoreManager` de Cobblemon et lui demande un store d'équipe. Les boîtes du PC ne
sont jamais consultées. Un joueur dont le Pokémon parfait dort dans une boîte échoue à
toutes les vérifications.

**Le message part vers la cible, pas vers l'appelant.** Tous les appels à `sendMessage`
de `CheeseCheckCommand` visent le `player` résolu. Un bloc de commande, la console du
serveur ou un administrateur qui vérifie quelqu'un d'autre ne reçoit aucune sortie dans
le chat. Le seul retour de l'appelant est le résultat numérique et le log du serveur.

**L'échec n'est pas une erreur.** Une vérification ratée renvoie `0` sans lever
d'exception : la commande est donc « exécutée mais sans succès ». C'est précisément ce
qui la rend utilisable comme verrou de quête : rien n'est écrit, donc l'état de la quête
ne bouge pas.

## Niveau de permission

Tout l'arbre exige le **niveau de permission 2**, celui qu'un serveur vanilla accorde à
un opérateur.

| Appelant | Niveau | Fonctionne |
|---|---|---|
| Console du serveur | 4 | oui |
| Opérateur (`/op`) avec `op-permission-level=4` par défaut | 4 | oui |
| Bloc de commande | 2 | oui |
| `mcfunction` lancée depuis un bloc de commande ou `/execute` | hérite de 2 | oui |
| Un bouton EasyNPC ou de système de quêtes exécuté comme le serveur | 4 | oui |
| Joueur ordinaire | 0 | non |

Un joueur sous le niveau 2 ne voit pas `cheesecheck` dans la complétion, et la saisir
produit l'erreur vanilla *Unknown or incomplete command* plutôt qu'un message de
permission. C'est le comportement de Brigadier, pas un choix de ce mod.

Ces commandes sont **conçues pour être exécutées par le serveur**, pas tapées par les
joueurs. L'argument `player` existe justement pour qu'un bouton de dialogue, un bloc de
commande ou une tâche de quête puisse vérifier un tiers.

## Complétion

| Argument | Complétion |
|---|---|
| `player` | oui. `EntityArgumentType.player()` propose les pseudos en ligne et accepte un sélecteur à cible unique comme `@p` ou `@s` |
| `scoreboard` | **non**. C'est un simple `StringArgumentType.word()` : les objectifs existants ne sont pas proposés et les fautes de frappe ne sont pas détectées |
| `species` | **non**. Également un `word()` ; aucune liste d'espèces n'est offerte |
| chaque argument entier | non, mais la plage est vérifiée et rappelée dans l'erreur |

L'absence de complétion sur `scoreboard` est de loin la première cause de panne
silencieuse. Voir [le piège de la création
automatique](#le-piège-de-la-création-automatique).

---

## `/cheesecheck ev`

```mcfunction
/cheesecheck ev <player> <minEV> <scoreboard> <value> [statCount] [teamCount]
```

| Argument | Type | Obligatoire | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `player` | sélecteur de joueur | oui | un pseudo en ligne, ou un sélecteur à cible unique | — |
| `minEV` | entier | oui | `1`–`252` | — |
| `scoreboard` | mot | oui | n'importe quel nom d'objectif ; créé s'il manque | — |
| `value` | entier | oui | n'importe quel `int`, négatifs compris | — |
| `statCount` | entier | non | `1`–`6` | `1` |
| `teamCount` | entier | non | `1`–`6` | `1` |

**Il n'y a aucun sélecteur de stat.** `minEV` est un seuil appliqué aux six stats à la
fois, et `statCount` indique *combien d'entre elles* doivent le franchir. Tu peux exiger
« trois stats à 100 EVs ou plus », tu ne peux pas exiger « la Vitesse à 252 ». Si tu as
besoin d'une stat précise, cette commande ne sait pas l'exprimer.

Les stats lues sont les six stats de combat de Cobblemon, dans l'ordre de l'énumération :
HP, ATTACK, DEFENCE, SPECIAL_ATTACK, SPECIAL_DEFENCE, SPEED. `EVASION` et `ACCURACY`
existent dans l'énumération `Stats` de Cobblemon, mais la couche de réflexion ne prend
délibérément que les six premières.

### Comment le comptage fonctionne

Pour chaque Pokémon de l'équipe, la commande compte les stats dont l'EV est `>= minEV`.
Si ce compte est `>= statCount`, le Pokémon correspond. Si le nombre de Pokémon
correspondants est `>= teamCount`, la vérification passe.

Les deux arguments optionnels omis, cela se réduit à : au moins un Pokémon de l'équipe a
au moins une stat à `minEV` EVs ou plus.

### Exemples

Un seul Pokémon entraîné dans une stat :

```mcfunction
/cheesecheck ev Steve 252 quest_ev 2
```

Steve voit, en vert :

```text
Vérification réussie ! Un de tes Pokémon a 1 stats à 252+ EVs.
```

La grammaire est fausse dans le mod lui-même : le cas singulier affiche quand même
`1 stats`. C'est cosmétique, et c'est dans le code source, pas dans cette page.

Une équipe entièrement entraînée, quatre Pokémon à deux stats maximisées chacun :

```mcfunction
/cheesecheck ev Steve 252 quest_ev_team 1 2 4
```

```text
Vérification réussie ! 4/4 Pokémon dans ton équipe remplissent le critère (2 stats à 252+ EVs).
```

### Messages d'échec

| Message | Condition |
|---|---|
| `[CheeseCheck] Cobblemon non disponible.` | la classe `Cobblemon` n'est pas dans le classpath. Renvoie `0` avant toute lecture |
| `Aucun Pokémon dans ton équipe n'a <minEV> EVs dans une stat. Continue l'entraînement !` | `teamCount` et `statCount` tous deux à `1`, et rien ne correspond |
| `Aucun Pokémon dans ton équipe n'a <statCount> stats à <minEV>+ EVs. Continue l'entraînement !` | `teamCount` à `1`, `statCount` au-dessus de `1`, rien ne correspond |
| `<matching>/<teamCount> Pokémon dans ton équipe remplissent le critère (<statCount> stats à <minEV>+ EVs). Continue l'entraînement !` | `teamCount` au-dessus de `1` et pas assez de Pokémon correspondants. Affiche la progression partielle |

Tous renvoient `0`.

Un nombre hors plage est rejeté par Brigadier avant l'exécution du handler, avec le
message vanilla *Integer must not be less than 1, found 0* ou *Integer must not be more
than 252, found 300*. Un pseudo hors ligne ou inconnu produit le vanilla *No player was
found*. Ce sont des erreurs d'analyse : elles lèvent une exception, donc la commande
n'est pas seulement « sans succès », elle échoue franchement, et un
`/execute store result` laisse sa cible intacte.

---

## `/cheesecheck iv`

```mcfunction
/cheesecheck iv <player> <count> <minIV> <scoreboard> <value> [teamCount]
```

| Argument | Type | Obligatoire | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `player` | sélecteur de joueur | oui | un pseudo en ligne, ou un sélecteur à cible unique | — |
| `count` | entier | oui | `1`–`6` | — |
| `minIV` | entier | oui | `1`–`31` | — |
| `scoreboard` | mot | oui | n'importe quel nom d'objectif ; créé s'il manque | — |
| `value` | entier | oui | n'importe quel `int` | — |
| `teamCount` | entier | non | `1`–`6` | `1` |

Observe l'ordre des arguments face à `ev` : ici le **compte précède le seuil** (`count`
puis `minIV`), alors que `ev` prend le seuil d'abord et le compte en optionnel final. Les
deux sous-commandes ne sont pas symétriques. C'est l'erreur d'ordre à surveiller quand on
rédige des fichiers de dialogue.

`count` est le nombre d'IVs qui doivent atteindre `minIV` sur le *même* Pokémon. « Cinq
IVs parfaits » s'écrit `count=5 minIV=31`.

### Exemples

```mcfunction
/cheesecheck iv Steve 5 31 quest_iv 4
```

```text
Vérification réussie ! Un de tes Pokémon a 5 IVs à 31+.
```

En l'exigeant de trois Pokémon à la fois :

```mcfunction
/cheesecheck iv Steve 4 25 quest_iv_team 4 3
```

```text
Vérification réussie ! 3/3 Pokémon dans ton équipe remplissent le critère (4 IVs à 25+).
```

### Messages d'échec

| Message | Condition |
|---|---|
| `[CheeseCheck] Cobblemon non disponible.` | Cobblemon absent |
| `Aucun Pokémon dans ton équipe n'a <count> IVs à <minIV>+. Continue l'élevage !` | `teamCount` à `1` et rien ne correspond |
| `<matching>/<teamCount> Pokémon dans ton équipe remplissent le critère (<count> IVs à <minIV>+). Continue l'élevage !` | `teamCount` au-dessus de `1`, pas assez de correspondances |

Contrairement à `ev`, `iv` n'a pas de formulation distincte pour le critère unique. Les
deux renvoient `0`.

---

## `/cheesecheck pokemon`

```mcfunction
/cheesecheck pokemon <player> <species> <scoreboard> <value>
```

| Argument | Type | Obligatoire | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `player` | sélecteur de joueur | oui | un pseudo en ligne, ou un sélecteur à cible unique | — |
| `species` | mot | oui | un identifiant Showdown de Cobblemon, mis en minuscules par la commande | — |
| `scoreboard` | mot | oui | n'importe quel nom d'objectif ; créé s'il manque | — |
| `value` | entier | oui | n'importe quel `int` | — |

Aucun argument optionnel, et aucun compte : un seul Pokémon correspondant n'importe où
dans l'équipe suffit.

### Comment la correspondance se fait

La commande met `species` en minuscules, puis pour chaque membre de l'équipe lit
`getSpecies().getShowdownId()` par réflexion (en se rabattant sur `showdownId`, `getName`
puis `name` si le premier est indisponible) et met aussi le résultat en minuscules. La
correspondance est soit une égalité exacte, soit un identifiant qui *se termine par*
`:<species>`, ce qui tolère un identifiant avec espace de noms comme
`cobblemon:gyarados`.

Comme il s'agit de l'identifiant Showdown, écris-le comme Showdown l'écrit : sans espace,
sans tiret, sans accent. `M. Mime` devient `mrmime`, `Nidoran♀` devient `nidoranf`,
`Tapu Koko` devient `tapukoko`. `species` étant un argument `word()`, une valeur
contenant un espace ne peut de toute façon pas être saisie.

⚠️ Il s'agit d'une **observation sur Cobblemon 1.6.1**, pas d'une spécification.
L'ensemble exact des identifiants Showdown, et le fait qu'une forme ou une variante
régionale ait ou non le sien, relève de Cobblemon et peut changer d'une version à
l'autre. L'appel par réflexion continuera de fonctionner ; les identifiants, peut-être
pas.

### Exemple

```mcfunction
/cheesecheck pokemon Steve gyarados quest_dante 6
```

```text
Vérification réussie ! Tu as bien un gyarados dans ton équipe.
```

Le nom d'espèce est renvoyé tel que saisi, en minuscules. Le message dira `gyarados`,
jamais `Gyarados`.

### Messages d'échec

| Message | Condition |
|---|---|
| `[CheeseCheck] Cobblemon non disponible.` | Cobblemon absent |
| `Tu n'as pas de <species> dans ton équipe. Capture-en un et reviens !` | aucun membre de l'équipe ne correspond |

Un identifiant d'espèce qui n'existe pas du tout est indiscernable d'un Pokémon absent :
les deux produisent le second message et renvoient `0`. Rien ne valide l'identifiant,
donc une faute de frappe rend une étape de quête définitivement infaisable, sans le
moindre indice dans le log.

---

## `/cheesecheck ballvariety`

```mcfunction
/cheesecheck ballvariety <player> <count> <scoreboard> <value>
```

| Argument | Type | Obligatoire | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `player` | sélecteur de joueur | oui | un pseudo en ligne, ou un sélecteur à cible unique | — |
| `count` | entier | oui | `1`–`20` | — |
| `scoreboard` | mot | oui | n'importe quel nom d'objectif ; créé s'il manque | — |
| `value` | entier | oui | n'importe quel `int` | — |

Lit `getCaughtBall` (en se rabattant sur `getPokeball`) sur chaque membre de l'équipe,
puis le nom de la Ball elle-même, mis en minuscules, dans un `HashSet`. Les chaînes vides
sont écartées. La vérification passe quand la taille de l'ensemble est `>= count`.

`count` monte jusqu'à `20` alors qu'une équipe contient au plus six Pokémon : toute
valeur au-dessus de `6` ne peut donc jamais passer. Brigadier l'accepte ; la
vérification, non. La plage est plus large que ce que la mécanique autorise.

Un Pokémon éclos d'un œuf ou donné par commande peut n'avoir aucune Ball de capture, ou
une Ball dont le nom se résout en chaîne vide via la réflexion. Ceux-là sont ignorés
plutôt que comptés comme un type distinct.

### Exemple

```mcfunction
/cheesecheck ballvariety Steve 6 quest_suzy 8
```

```text
Vérification réussie ! 6 types de Balls différentes dans ton équipe.
```

### Messages d'échec

| Message | Condition |
|---|---|
| `[CheeseCheck] Cobblemon non disponible.` | Cobblemon absent |
| `Tu as <n> types de Balls différentes, il en faut <count>. Capture avec des Balls variées !` | l'ensemble est plus petit que `count`. `<n>` est le compte réel, le joueur voit donc où il en est |

---

## Les valeurs de retour, en un tableau

C'est la partie qu'un système de quêtes consomme réellement.

| Situation | Résultat | Lève | Ce que l'appelant observe |
|---|---|---|---|
| Vérification réussie | `1` | non | score écrit, `store result` → `1`, `store success` → `1`, un bloc de commande conditionnel s'enchaîne |
| Vérification échouée | `0` | non | rien d'écrit, `store result` → `0`, `store success` → `0`, la chaîne s'arrête |
| Cobblemon absent | `0` | non | identique à une vérification échouée. **Rien ne permet de les distinguer au seul résultat** |
| Joueur hors ligne | — | oui | erreur d'analyse ; les cibles de `store result` et `store success` restent telles quelles |
| Entier hors plage | — | oui | erreur d'analyse, idem |
| Sous-commande inconnue ou argument manquant | — | oui | vanilla *Unknown or incomplete command* |

Le fait de renvoyer `0` aussi bien pour un échec légitime que pour un Cobblemon absent
est le point faible de cette interface. Les distinguer depuis un bloc de commande est
impossible ; regarde plutôt le chat du joueur ou le log du serveur.

## Schémas d'intégration

### Lire le résultat directement

```mcfunction
/scoreboard objectives add check_ok dummy
/execute store result score Steve check_ok run cheesecheck iv Steve 5 31 quest_iv 4
/execute if score Steve check_ok matches 1 run say Steve a réussi
```

`store result` capture le retour `1`/`0`. Utilise ce schéma quand tu veux l'issue sans te
soucier de l'objectif que la commande écrit elle-même, par exemple pendant que tu règles
un seuil.

### Le schéma prévu : laisser la commande écrire la progression

La commande écrit déjà un scoreboard en cas de réussite : une étape de quête n'a donc
aucun besoin de `store result`. Pointe `scoreboard` sur l'objectif dont ton NPC dépend,
et `value` sur le numéro de progression de l'état de dialogue *suivant* :

```mcfunction
/cheesecheck iv @s 5 31 alfred_progress 12
```

Le dialogue lit ensuite `alfred_progress` :

```mcfunction
/execute if score @s alfred_progress matches 11 run function arkensia:alfred/still_breeding
/execute if score @s alfred_progress matches 12 run function arkensia:alfred/reward
```

Sur une vérification ratée, `alfred_progress` reste à `11` : le joueur reçoit donc à
nouveau la réplique « continue l'élevage ». C'est pour cela que la commande n'écrit rien
en cas d'échec.

### FTB Quests

Une tâche FTB Quests ne peut pas lancer de commande, mais elle peut observer un score.
Donne à la quête une tâche **Observation** sur l'objectif que la vérification écrit, et
déclenche la vérification depuis le bouton de dialogue du NPC sur lequel le joueur clique.
La quête se valide à l'instant où la vérification passe, puisque c'est l'instant où le
score change.

### Une chaîne de blocs de commande

Place la vérification dans un bloc **Impulsion, Inconditionnel**, et la récompense dans un
bloc **Chaîne, Conditionnel** derrière lui. Le bloc chaîné ne se déclenche que si la
vérification a renvoyé une valeur non nulle, le drapeau de succès d'un bloc de commande
étant dérivé du résultat.

## Le piège de la création automatique

`setScore` cherche l'objectif avec `getNullableObjective`. S'il n'existe pas, l'objectif
est **créé** au lieu d'être signalé comme une erreur :

```java
objective = scoreboard.addObjective(objectiveName, ScoreboardCriterion.DUMMY,
        Text.literal(objectiveName), RenderType.INTEGER, true, null);
```

Un objectif `dummy`, dont le nom d'affichage égale son propre identifiant, en rendu
entier. Le serveur écrit un avertissement :

```text
[CheeseExtra] Scoreboard objective 'alfred_progres' not found — creating it
```

puis une confirmation de l'écriture :

```text
[CheeseExtra] Set alfred_progres = 12 for Steve
```

Une faute de frappe produit donc une commande qui marche, un message de réussite vert
pour le joueur, et un objectif que personne ne lit. Cherche `not found — creating it`
dans le log après avoir rédigé une nouvelle étape de quête : sur un serveur dont tous les
objectifs sont créés à l'avance, cette ligne ne devrait plus jamais apparaître.

Un dernier détail de la même méthode : le score est écrit sur
`ScoreHolder.fromName(player.getName().getString())`, donc sur le **pseudo** du joueur.
Un joueur qui change de pseudo Minecraft repart d'un score vide et perd sa progression de
quête.

---

## Ce que l'ancien README annonçait de faux

Pour mémoire, puisque tout ce qui en serait recopié ne fonctionnera pas :

| L'ancien README annonçait | Le code a réellement |
|---|---|
| `/cheesecheck ev <player> <stat> <value>` | `ev <player> <minEV> <scoreboard> <value> [statCount] [teamCount]`. Aucun sélecteur de stat n'existe, et l'argument obligatoire `scoreboard` manquait dans la syntaxe documentée |
| `/cheesecheck iv <player> <count>` | `iv <player> <count> <minIV> <scoreboard> <value> [teamCount]` |
| `/cheesecheck pokemon <player> <species>` | `pokemon <player> <species> <scoreboard> <value>` |
| la variété de Balls comptée « sur l'équipe ou la boîte » | l'équipe seule. `getPartyPokemon` ne touche jamais au PC |

---

← [Premiers pas](getting-started.fr.md) · [Configuration](configuration.fr.md) →
