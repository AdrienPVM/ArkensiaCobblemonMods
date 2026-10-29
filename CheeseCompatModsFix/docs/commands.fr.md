[← CheeseCompatModsFix](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](commands.md)

# Commandes

Ce mod ajoute exactement une commande, et modifie une commande qui appartient à un autre
mod. Les deux sont dans `CheeseCompatMod` — il n'y a pas de package `commands`.

---

## `/stopbattle`

Met fin de force au combat Cobblemon de l'appelant.

```mcfunction
/stopbattle
```

### Arguments

Aucun. La forme sans argument est la seule que ce mod enregistre.

| Argument | Type | Obligatoire | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| — | — | — | — | — |

### Permission

**Aucune requise.** La commande est enregistrée sans clause `requires(...)`, donc
n'importe quel joueur peut l'exécuter. C'est délibéré : un joueur dont le combat est figé
doit pouvoir se libérer sans administrateur.

Elle exige en revanche une source **joueur**.
`context.getSource().getPlayerOrThrow()` est la première chose que le gestionnaire appelle,
donc l'exécuter depuis la console du serveur ou depuis un bloc de commande échoue avec
l'erreur standard de Brigadier :

```text
No player was found
```

### Ce qu'elle fait

Le gestionnaire lit le nom de l'appelant puis exécute, en tant que source de commande
**serveur** :

```mcfunction
stopbattle <nom de l'appelant>
```

Cette forme ciblée appartient à Battle Tower, pas à ce mod. Autrement dit, `/stopbattle`
sans argument est un enrobage en libre-service qui appelle le `/stopbattle <player>` de ce
mod à ta place, avec une permission de niveau serveur, pour que la restriction décrite
plus bas ne t'empêche pas d'arrêter ton propre combat.

L'appelant voit alors, en vert :

```text
Battle stop requested.
```

Cette chaîne est un `Text.literal`, pas une clé de traduction, donc elle est en anglais
quelle que soit la langue du client.

### Effets de bord

- Une ligne dans le log du serveur, en `INFO` :

  ```text
  [CheeseCompat] <name> used /stopbattle on themselves
  ```

- Tout ce que fait le `/stopbattle <player>` de Battle Tower, que ce mod ne contrôle pas.
- Rien n'est écrit sur disque, et aucun évènement n'est diffusé.

### Exemple

Un joueur dont le combat est figé tape :

```mcfunction
/stopbattle
```

et voit :

```text
Battle stop requested.
```

Le combat se termine un ou deux ticks plus tard, quand la commande ciblée a tourné. Note la
formulation : *requested*, demandé. Le message est envoyé immédiatement et sans condition,
**avant** que le résultat de la commande interne soit connu. Si le gestionnaire de Battle
Tower ne trouve aucun combat à arrêter, le joueur reçoit quand même « Battle stop
requested. » et rien ne se passe. Il n'y a aucun retour pour ce cas.

### Messages d'échec

| Message | Cause |
|---|---|
| `No player was found` | exécutée depuis la console ou un bloc de commande au lieu d'un joueur |
| `Unknown command...` | Battle Tower n'est pas installé, donc il n'y a pas d'arbre `stopbattle` auquel ce mod puisse accrocher utilement sa forme sans argument — le littéral s'enregistre, mais l'appel ciblé interne n'a rien à exécuter |
| *(silence, le combat continue)* | le `/stopbattle <name>` interne n'a trouvé aucun combat pour ce joueur |

### Complétions

Aucune. Il n'y a pas d'argument à compléter.

---

## `/stopbattle <player>` — restreinte, pas ajoutée

Ce mod n'enregistre **pas** la forme ciblée. Battle Tower le fait. Ce que ce mod fait, c'est
la retirer aux non-opérateurs.

### Pourquoi

Sans la restriction, n'importe quel joueur pourrait mettre fin au combat de n'importe quel
autre, par son nom. Sur un serveur où une série de Battle Tower verrouille une équipe à des
niveaux forcés, ce n'est pas une blague — c'est un moyen de corrompre l'état de l'équipe de
quelqu'un d'autre depuis l'autre bout de la carte.

### Comment

`restrictStopBattleArgs()` tourne sur `ServerLifecycleEvents.SERVER_STARTED`, après que
tous les mods ont enregistré leurs commandes. Elle ne peut pas employer le builder Brigadier
normal, parce que le nœud existe déjà et appartient à un autre mod, donc elle édite l'arbre
de commandes vivant par réflexion :

1. Trouver le nœud `stopbattle` sous la racine du dispatcher. S'il est absent — Battle
   Tower pas installé — rendre la main sans rien faire.
2. Atteindre les maps privées `children` et `arguments` de `CommandNode` par réflexion.
3. Pour chaque enfant qui est un `ArgumentCommandNode`, construire un remplaçant avec
   `RequiredArgumentBuilder`, en reportant le nom, le type d'argument, le gestionnaire
   `executes` et le fournisseur de suggestions personnalisé, et en ajoutant
   `.requires(src -> src.hasPermissionLevel(2))`. Rattacher les enfants du nœud d'origine
   au remplaçant, puis le remettre dans les deux maps.

Seuls les enfants de type argument sont remplacés. Le nœud littéral `stopbattle` lui-même,
et la forme sans argument enregistrée plus haut, ne sont pas touchés.

### Permission après le changement

**Niveau 2** (le niveau « op » habituel pour les commandes) pour `/stopbattle <player>`. Un
joueur sous le niveau 2 ne voit pas du tout la forme avec argument dans son arbre de
commandes : pour lui, la syntaxe ciblée n'existe tout simplement pas. Brigadier le rapporte
comme il rapporte n'importe quelle entrée inconnue :

```text
Incorrect argument for command
```

Il peut toujours exécuter le `/stopbattle` nu.

### En cas d'échec

Toute la méthode est entourée d'un try/catch. Un échec — une version de Brigadier où ces
noms de champs ont changé, un security manager, quoi que ce soit — produit un avertissement
et laisse l'arbre tel quel :

```text
[CheeseCompat] Could not restrict /stopbattle <player>: <message>
```

Le succès produit :

```text
[CheeseCompat] Restricted /stopbattle <player> to operators
```

⚠️ Si tu ne vois aucune des deux lignes, rien n'a tourné : soit le nœud `stopbattle` était
absent (Battle Tower pas installé, ce qui est normal), soit le serveur n'a jamais atteint
`SERVER_STARTED`. Le cas dangereux est l'avertissement, parce qu'il signifie que **la
commande ciblée reste ouverte à tous les joueurs** alors que le reste du mod fonctionne
normalement.

### Une note sur la réflexion dans Brigadier

C'est le seul endroit du mod qui mute les structures de données privées d'une bibliothèque
plutôt que le code d'un autre *mod*, et il vaut la peine d'être explicite sur pourquoi
c'était acceptable : il n'existe aucune API pour greffer après coup une vérification de
permission sur un nœud de commande enregistré par quelqu'un d'autre. Les alternatives
étaient un mixin sur l'enregistrement de commande de Battle Tower (fragile d'une autre
façon, et lié à une version) ou demander à Battle Tower d'ajouter la vérification en amont.
La réflexion est contenue dans une seule méthode, échoue en douceur, et journalise.

---

← [Premiers pas](getting-started.fr.md) · [Configuration](configuration.fr.md) →
