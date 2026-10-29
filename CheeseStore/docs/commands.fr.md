[← CheeseStore](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](commands.md)

# Commandes

CheeseStore enregistre cinq racines de commande : `/premium`, `/balance`, `/store`,
`/cosmetics` et `/mount`. Deux d'entre elles portent des branches d'administration
protégées par le **niveau de permission 2** — celui qu'un serveur vanilla accorde à un
opérateur. Tout le reste est ouvert à n'importe quel joueur.

Rien du côté paiement ne se pilote par commande. Les produits, les commandes d'achat et
la livraison manuelle vivent dans [le dashboard](dashboard.fr.md) ; les commandes
décrites ici couvrent le portefeuille de crédits, la liaison de compte, et les
cosmétiques qu'un joueur a déjà achetés.

Les retours destinés aux joueurs sont traduits : le mod livre
`assets/cheesestore/lang/en_us.json` et `fr_fr.json`. Les exemples ci-dessous donnent
les chaînes françaises. Une exception, qu'il vaut mieux connaître : les arbres
`/cosmetics` et `/mount` envoient leurs messages via `Text.literal` avec du **français
codé en dur** au lieu de clés de traduction, donc ces sorties restent en français sur
un client anglais. C'est un défaut de code, noté dans
[architecture](architecture.fr.md#faiblesses-connues).

---

## `/premium`

Le portefeuille de crédits. Les soldes vivent dans
`config/cheesestore_wallets.json`, indexés par UUID de joueur, et sont gérés par
`WalletManager`.

### `/premium` (sans argument)

```mcfunction
/premium
```

Affiche le solde de l'appelant. Réservée aux joueurs — la console reçoit
`Cette commande doit être exécutée par un joueur.`

```text
🧀 Crédits : 1250
```

`/balance` est une seconde racine enregistrée comme alias de commodité pour exactement
cette forme. Elle ne prend aucun argument ; un administrateur qui veut consulter
quelqu'un d'autre passe toujours par `/premium balance <player>`.

### `/premium open`

```mcfunction
/premium open
```

Envoie à l'appelant une ligne de chat cliquable pointant vers
`<storeBaseUrl>/store/`. Réservée aux joueurs.

```text
🛒 Cliquez ici pour ouvrir la boutique
```

Si `storeBaseUrl` n'est pas renseignée, le lien est construit quand même et résout vers
`/store/` sans hôte, ce que le client refusera d'ouvrir. Renseigne d'abord la clé —
voir [configuration](configuration.fr.md#storebaseurl).

### `/premium top`

```mcfunction
/premium top
```

Les dix plus gros soldes, par ordre décroissant. Aucune exigence de permission : c'est
donc un classement public. Si tu ne veux pas que les soldes soient visibles de tous, tu
n'as aucun interrupteur à ta disposition, à part modifier la commande.

```text
=== Top Crédits ===
1. Steve — 4200
2. Alex — 1250
```

Renvoie le nombre d'entrées affichées, ou `0` avec `Aucun joueur n'a de crédits.` quand
aucun portefeuille n'existe encore.

### `/premium cosmetics`

```mcfunction
/premium cosmetics
```

Liste les entrées activées du catalogue `CosmeticItem` **historique** — la liste
achetable depuis le site, gérée par `com.cheesestore.CosmeticManager`, et non les
cosmétiques jouables de `/cosmetics`. Les deux catalogues sont distincts ; voir
[cosmétiques et portefeuilles](cosmetics.fr.md#deux-catalogues-qui-portent-le-même-nom).

```text
=== Cosmétiques (crédits) ===
✨ Traînée Dorée [TRAIL] — 500 crédits
```

Catalogue vide → `Aucun cosmétique disponible pour le moment.`

### `/premium give <player> <amount>`

```mcfunction
/premium give <player> <amount>
```

| Argument | Type | Requis | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `player` | mot unique | oui | un pseudo ; se complète depuis les joueurs **connectés**, mais un pseudo hors ligne est résolu via le cache utilisateur du serveur | — |
| `amount` | long | oui | `1` ou plus (Brigadier refuse `0` et les négatifs) | — |

**Niveau de permission 2.** Un joueur en dessous ne voit pas la branche `give` dans la
complétion par tabulation et reçoit l'erreur vanilla *Unknown or incomplete command*.

Ajoute `amount` au solde de la cible et écrit le fichier de portefeuilles de façon
asynchrone. Le retour est diffusé aux autres opérateurs
(`sendFeedback(..., true)`) et journalisé :

```text
+500 crédits donnés à Alex. Nouveau solde : 1750
```

```text
[CheeseStore] Server gave 500 fromage to Alex (new balance: 1750)
```

Si la cible est connectée — et n'est pas l'administrateur qui lance la commande — elle
est notifiée directement :

```text
🧀 +500 crédits reçus ! Nouveau solde : 1750
```

| Message | Cause |
|---|---|
| `Joueur introuvable : <name>` | ni un joueur connecté ni une entrée du cache utilisateur ne correspond ; rien n'a changé |

### `/premium take <player> <amount>`

Mêmes arguments et même niveau de permission que `give`, avec `amount` de nouveau
contraint à `1` ou plus par Brigadier.

Le solde s'arrête à zéro plutôt que de passer en négatif. Retirer 500 à un joueur qui
détient 200 retire 200, et le retour annonce **200**, pas 500 — la commande calcule
`min(amount, soldeActuel)` pour le message tout en fixant le solde à
`max(0, actuel - amount)`.

```text
-200 crédits retirés à Alex. Nouveau solde : 0
```

### `/premium set <player> <amount>`

Comme ci-dessus, sauf que `amount` accepte `0` (`longArg(0)`), parce que ramener un
solde à zéro est une opération légitime. `WalletManager.setBalance` contraint les
négatifs à zéro en seconde ligne de défense.

```text
Crédits de Alex défini à : 1000
```

La cible, si elle est connectée, voit une notification de réception ou de retrait selon
le sens du changement. Un solde inchangé n'envoie rien.

### `/premium balance <player>`

```mcfunction
/premium balance <player>
```

Lit le solde d'un autre joueur. **Niveau de permission 2** — un simple joueur ne peut
voir que le sien. Fonctionne sur les joueurs hors ligne via le cache utilisateur.

```text
🧀 Crédits de Alex : 1250
```

---

## `/store link <code>`

```mcfunction
/store link <code>
```

| Argument | Type | Requis | Valeurs acceptées |
|---|---|---|---|
| `code` | mot unique | oui | le code affiché sur le site après identification |

Aucune exigence de permission, et réservée aux joueurs : le code lie *ton* UUID à la
session web qui l'a généré. C'est la seconde moitié du flux de liaison de compte — le
site émet un code, tu le colles en jeu, et à partir de là la boutique sait quel compte
Minecraft possède ta session de navigateur. Les routes qui dépensent des crédits
(`/cosmetics/buy`, `/morpho/buy`, `/playcosmetics/{id}/buy`) refusent toutes sans
liaison achevée.

`/store` n'a aucune autre sous-commande. Il n'existe pas de `/store list`, ni de
`/store buy`.

```text
Session boutique liée à Steve. Tu peux désormais voir ton solde et acheter des morphos depuis le site.
```

| Message | Cause |
|---|---|
| `Code de liaison invalide ou expiré. Génère-en un nouveau depuis la boutique.` | le code n'a jamais existé, a déjà été utilisé, ou a expiré |
| `Trop de tentatives de liaison. Attends une minute avant de réessayer.` | la limitation de débit d'`AccountLinker` s'est déclenchée — le garde qui empêche de forcer des codes courts par force brute |
| `Cette commande doit être exécutée par un joueur.` | lancée depuis la console |

---

## `/cosmetics`

Les cosmétiques jouables : traînées, effets de kill et de mort, tags de chat, couleurs
de pseudo, familiers, montures, capes. Alias : `/cosmetic`, enregistré comme une
redirection Brigadier vers le même nœud, donc chaque sous-commande fonctionne sous les
deux noms.

Un joueur ne peut équiper que ce qu'il possède. La possession vient d'un achat web,
d'un `grant` d'administrateur, ou de nulle part du tout — le fait qu'un cosmétique soit
visible au catalogue ne veut pas dire qu'il est atteignable. Le modèle est décrit dans
[cosmétiques et portefeuilles](cosmetics.fr.md).

### `/cosmetics` (sans argument)

Ouvre un menu en coffre construit sur `sgui` (embarqué dans le jar). Le premier niveau
est un coffre de 3 rangées avec un bouton par catégorie, chacun affichant le compte
possédés/total et ce qui est équipé ; le solde du portefeuille occupe l'emplacement
central du bas. Cliquer une catégorie ouvre un écran de 6 rangées qui ne liste que les
cosmétiques que tu possèdes dans celle-ci, avec une bascule au clic sur chacun, un
bouton **Tout retirer** à l'emplacement 49 et une flèche de retour à l'emplacement 53.
La catégorie `MOUNT` reçoit en plus un bouton d'invocation/renvoi à l'emplacement 48.

Si `sgui` lève une exception pour une raison quelconque, la commande retombe sur la
liste texte de `/cosmetics list` plutôt que d'échouer. Réservée aux joueurs.

### `/cosmetics list [category]`

```mcfunction
/cosmetics list [category]
```

| Argument | Type | Requis | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `category` | mot unique | non | `TRAIL` `KILL_EFFECT` `DEATH_EFFECT` `NAME_COLOR` `CHAT_TAG` `PET` `MOUNT` `CAPE` — se complète | toutes les catégories |

Affiche les cosmétiques que tu possèdes, groupés par catégorie, avec un `[équiper]`
cliquable sur chaque entrée non équipée et un lien de retrait cliquable sur celle qui
l'est. Les catégories où tu ne possèdes rien sont entièrement sautées.

### `/cosmetics equip <id>`

```mcfunction
/cosmetics equip <id>
```

| Argument | Type | Requis | Valeurs acceptées |
|---|---|---|---|
| `id` | mot unique | oui | un identifiant de cosmétique ; se complète **depuis les identifiants que tu possèdes** |

Équiper remplace ce qui était équipé dans la catégorie propre à ce cosmétique — un
emplacement par catégorie, imposé par `EquipManager`. Le gestionnaire visuel concerné
est rafraîchi immédiatement : `NAME_COLOR` et `CHAT_TAG` passent par
`NameTagManager`, `PET` par `PetManager`, `MOUNT` par `MountManager`, `CAPE`
rediffuse la carte des capes à tous les clients. `TRAIL`, `KILL_EFFECT` et
`DEATH_EFFECT` sont lus en direct et n'ont besoin d'aucun rafraîchissement.

Équiper une `MOUNT` la sélectionne ; cela ne l'invoque pas. Utilise `/mount`.

| Message | Cause |
|---|---|
| `Cosmétique inconnu : <id>` | aucun identifiant de ce nom au catalogue |
| `Ce cosmétique est désactivé.` | l'entrée existe mais `enabled` est faux |
| `Tu ne possèdes pas ce cosmétique.` | absent de ta liste de possession |
| `Impossible d'équiper ce cosmétique.` | `EquipManager.equip` a renvoyé null — l'entrée a disparu entre la recherche et l'équipement |

### `/cosmetics unequip <category>`

```mcfunction
/cosmetics unequip <category>
```

Vide l'emplacement de cette catégorie et rafraîchit le gestionnaire visuel
correspondant. Un nom de catégorie invalide est rejeté par
`CosmeticEntry.Category.isValid` avec `Catégorie inconnue : <CATÉGORIE>` ; un
emplacement vide répond `Rien d'équipé dans <libellé>.` — le libellé d'affichage
français, par exemple `Rien d'équipé dans Traînées.` pour `TRAIL` — et renvoie `0`.

### `/cosmetics grant <player> <id>` — niveau 2

```mcfunction
/cosmetics grant <player> <id>
```

| Argument | Type | Requis | Valeurs acceptées |
|---|---|---|---|
| `player` | sélecteur d'entité (joueur) | oui | un sélecteur ou un pseudo désignant **un joueur connecté** |
| `id` | mot unique | oui | n'importe quel identifiant du catalogue ; se complète depuis le catalogue entier, activé ou non |

Accorde la possession sans rien facturer. `player` est un `EntityArgumentType`, et non
un simple mot, donc il accepte `@p`, `@a[limit=1]` et compagnie — mais il ne résout que
parmi les joueurs connectés. La cible est informée de ce qu'elle a reçu et renvoyée
vers `/cosmetics`.

C'est la façon prévue de tester un cosmétique que tu viens de créer dans le dashboard.

| Message | Cause |
|---|---|
| `Joueur introuvable.` | le sélecteur n'a rien désigné, ou a désigné autre chose qu'un joueur |
| `Cosmétique inconnu : <id>` | aucun identifiant de ce nom |
| `Système de cosmétiques indisponible.` | les gestionnaires ne sont pas initialisés — le mod n'a pas démarré proprement |

### `/cosmetics grantall <player>` — niveau 2

Accorde d'un coup chaque entrée **activée** du catalogue et annonce le compte. Les
entrées désactivées sont sautées. Il n'existe aucun `revokeall` en contrepartie ;
défaire cela veut dire un `revoke` par identifiant.

```text
Accordé 24 cosmétiques à Alex
```

### `/cosmetics revoke <player> <id>` — niveau 2

Retire la possession. Si la cible avait ce cosmétique équipé, il est aussi déséquipé et
le visuel rafraîchi, pour que personne ne continue à porter ce qu'il ne possède plus.
Révoquer un identifiant que le joueur n'a jamais possédé est accepté en silence et
annonce malgré tout un succès.

---

## `/mount`

```mcfunction
/mount
/mount summon
/mount dismiss
```

Alias : `/monture`. Aucun argument, aucune exigence de permission, réservée aux
joueurs.

`/mount` nu fait une bascule : il invoque la `MOUNT` équipée si aucune n'est sortie, et
la renvoie sinon. `summon` et `dismiss` forcent un sens, ce qui est utile dans un bloc
de commande ou une macro où l'état courant est inconnu.

Une monture est invoquée à la demande et jamais automatiquement. L'équiper dans
`/cosmetics` sélectionne seulement quelle créature apparaît. `MountManager` rappelle la
monture quand son propriétaire entre en combat — comportement décrit dans
[cosmétiques et portefeuilles](cosmetics.fr.md#les-montures).

Tous les retours de cette commande viennent de `MountManager` et sont du texte littéral
exclusivement français.

---

## Les commandes que ce mod n'a pas

Cela vaut la peine d'être dit, parce que la forme du mod invite à le supposer :

- aucune commande ne crée, modifie ou tarife un produit
- aucune commande ne liste ni ne relivre une commande d'achat
- aucune commande ne lit ni n'écrit les clés Stripe

Les trois passent exclusivement par le dashboard. Si CheeseDash ne tourne pas, la
boutique n'a aucune surface d'administration.

---

← [Premiers pas](getting-started.fr.md) · [Configuration](configuration.fr.md) →
