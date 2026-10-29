[← EggAndCheese](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](commands.md)

# Commandes

Le mod enregistre deux commandes racines distinctes, depuis deux classes :

| Commande | Classe | Qui peut l'exécuter |
|---|---|---|
| `/eac` | `util/AdminCommand.java` | les joueurs qui passent `PermissionUtil.isAdmin` |
| `/pedestal` | `util/PedestalCommand.java` | **tout le monde**, y compris un joueur sans op |

L'administration des collectibles eux-mêmes se fait dans le dashboard CheeseDash. La
surface de commandes est volontairement mince : un moyen de récupérer un item admin, un
moyen de lire la taille du registre, et quatre réglages d'affichage pour les stèles.

Il n'y a aucun alias. Rien n'est enregistré sous `/eggandcheese`, `/egg`, ni aucune autre
orthographe.

## `/eac`

```mcfunction
/eac
/eac give <id>
/eac reload
```

### Permission

Tout l'arbre `/eac` porte une seule exigence :

```java
.requires(src -> src.isExecutedByPlayer() && PermissionUtil.isAdmin(src.getPlayer()))
```

Deux conséquences.

**Ce doit être un joueur.** `isExecutedByPlayer()` est faux pour la console du serveur,
pour un bloc de commande, et pour `/execute as`. Lancer `eac give cave_nord` depuis la
console donne l'erreur de commande inconnue standard de Minecraft, parce que pour la
console cette commande n'existe pas du tout.

**Admin, c'est `eggandcheese.admin`, avec repli sur op niveau 2.**
`PermissionUtil.isAdmin` tente par réflexion
`me.lucko.fabric.api.permissions.v0.Permissions.check(player, "eggandcheese.admin", 2)` ;
quand fabric-permissions-api n'est pas dans le classpath, il retombe sur
`hasPermissionLevel(2)`.

Un joueur sans la permission ne reçoit pas de message de refus. Brigadier masque une
commande dont le `requires` échoue : `/eac` n'apparaît donc pas dans sa liste de
complétion, et la taper produit :

```text
Unknown or incomplete command, see below for error
```

C'est le texte de Minecraft, pas celui de ce mod.

### `/eac`

Sans sous-commande, elle affiche une indication et rien d'autre :

```text
Utilisez le dashboard CheeseDash pour gérer les oeufs.
```

En anglais :

```text
Use the CheeseDash dashboard to manage eggs.
```

Affichée en jaune, comme retour de commande envoyé avec `broadcastToOps` désactivé : elle
n'atteint que toi — ni le log du serveur, ni les autres ops —, et la règle de jeu
`sendCommandFeedback` la masque entièrement quand elle vaut faux. Il en va de même pour la
ligne de `/eac reload`. Aucun état n'est modifié.

### `/eac give <id>`

Met dans ton inventaire l'item de placement admin correspondant à une entrée existante du
registre.

| Argument | Type | Obligatoire | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `id` | `StringArgumentType.string()` | oui | un mot unique, ou une chaîne entre guillemets pour un id contenant des espaces | — |

⚠️ **Aucune complétion.** L'argument n'a pas de `SuggestionProvider` : appuyer sur Tab ne
propose rien. Il faut connaître l'id, ou le lire dans la liste Collectibles du dashboard.
C'est de loin la première cause des erreurs `ID inconnu`.

L'item construit est un `eggandcheese:admin_egg` dont le `CUSTOM_DATA` porte trois valeurs
copiées depuis l'entrée du registre : `dna`, `registryId` et `scale`. C'est ce qui fait
d'un placement ultérieur un *replacement* plutôt qu'un nouvel œuf — voir
[placement et découvertes](placement-and-discovery.fr.md#branche-1--le-stack-désigne-un-id-de-registre-existant).

Le stack est inséré dans ton inventaire ; s'il est plein, il est lâché à tes pieds plutôt
que perdu.

Exemple réel :

```mcfunction
/eac give cave_nord
```

```text
Oeuf admin donné pour : cave_nord
```

En anglais :

```text
Given admin egg for: cave_nord
```

L'échec, en rouge :

```text
ID inconnu : cave_nrod
```

```text
Unknown ID: cave_nrod
```

L'id est cherché par `EggRegistryData.getEntry`, qui fait une correspondance exacte : les
ids sont sensibles à la casse et ne font l'objet d'aucune recherche approchée. `cave_nord`
et `Cave_Nord` sont deux ids différents.

La commande renvoie `1` dans le cas du succès comme dans celui de l'id inconnu : une
chaîne de blocs de commande ne peut donc pas les distinguer par le résultat. Elle ne
renvoie `0` que si `ctx.getSource().getPlayer()` est nul, ce que `requires` aurait déjà dû
empêcher.

### `/eac reload`

```mcfunction
/eac reload
```

```text
Registre d'oeufs chargé : 56 entrées.
```

```text
Egg registry loaded: 56 entries.
```

⚠️ **Cette commande ne recharge rien.** Malgré son nom, le handler fait exactement une
chose : il lit `EggRegistryData.get(server).getAllEntries().size()` et l'affiche. Il n'y a
rien à recharger — le registre est un `PersistentState` tenu en mémoire et écrit par le
cycle de sauvegarde de Minecraft, et il n'existe aucun fichier de configuration à relire.

Prends-la pour ce qu'elle est : « combien d'œufs le serveur croit-il avoir ». Elle sert à
une seule chose : comparer ce nombre à ce qu'affiche le dashboard, pour confirmer que tu
regardes bien le serveur que tu crois.

## `/pedestal`

```mcfunction
/pedestal scale <value>
/pedestal height <value>
/pedestal pillar <value>
/pedestal spin
```

Règle la stèle que tu regardes. Les stèles sont du mobilier d'exposition — voir
[œufs décoratifs et stèles](display.fr.md).

### Permission

⚠️ **Aucune.** `PedestalCommand` n'enregistre aucun `.requires(...)` : chaque
sous-commande est donc disponible au niveau de permission 0. N'importe quel joueur du
serveur peut rerégler n'importe quelle stèle devant laquelle il peut se placer, y compris
une stèle dans une construction protégée. Chaque sous-commande vérifie bien que l'appelant
est un joueur, et envoie

```text
Cette commande doit être exécutée par un joueur.
```

```text
This command must be executed by a player.
```

via `sendError` dans le cas contraire — la console ne peut donc pas s'en servir, mais tous
les joueurs oui. Si cela compte sur ton serveur, restreins `/pedestal` dans ton mod de
permissions, ou protège la région avec [CheeseGuard](../../CheeseGuard/).

### Visée

Chaque sous-commande résout sa cible de la même façon, dans `lookingAtPedestal` :

```java
player.raycast(5.0, 0, false)
```

Un raycast de 5 blocs depuis les yeux du joueur, en ignorant les fluides. Le bloc touché
doit posséder un `PedestalBlockEntity`. Un tir dans le vide comme un tir sur autre chose
produisent le même message rouge :

```text
Vous devez regarder un piédestal.
```

```text
You must be looking at a pedestal.
```

et renvoient `0`. Autrement dit, « rien ne s'est passé » signifie presque toujours que tu
étais un peu trop loin, ou que tu visais l'extension de pilier au lieu du bloc de stèle
lui-même.

### Arguments

| Sous-commande | Argument | Type | Plage | Effet |
|---|---|---|---|---|
| `scale` | `value` | flottant | 0,5 – 3,0 | Taille de l'item exposé |
| `height` | `value` | flottant | −0,5 – 0,5 | Décalage vertical de l'item exposé, en blocs |
| `pillar` | `value` | flottant | 0,0 – 5,0 | Hauteur du pilier sous la plateforme, en blocs |
| `spin` | — | — | — | Active ou coupe la rotation de l'item exposé |

Les plages sont imposées par Brigadier lui-même
(`FloatArgumentType.floatArg(min, max)`) : une valeur hors plage est donc rejetée avant
que le handler ne s'exécute, avec le message standard de Minecraft :

```text
Float must not be more than 3.0, found 5.0
```

Tab complète les noms des quatre sous-commandes ; l'argument `value` n'affiche que son
espace réservé `<value>`, pas sa plage : le tableau ci-dessus est le seul endroit où les
bornes sont écrites.

### Exemples

```mcfunction
/pedestal scale 2.0
```

```text
Taille d'affichage définie à 2,0.
```

```text
Display scale set to 2.0.
```

```mcfunction
/pedestal pillar 3.0
```

```text
Hauteur du pilier définie à 3,0.
```

```text
Pillar height set to 3.0.
```

Changer la hauteur du pilier a un effet de bord qui mérite d'être connu :
`setPillarHeight` reconstruit le cache de forme de collision de la stèle et appelle
`updateExtensions()`, ce qui pose ou retire des blocs
`eggandcheese:pedestal_extension` au-dessus de la stèle. Ce sont ces blocs d'extension que
tu heurtes réellement sur une stèle haute, et ils sont réglés en
`strength(-1.0f, 3600000.0f)` — incassables et insensibles aux explosions, comme la
bedrock. Le `onStateReplaced` de la stèle appelle `removeAllExtensions()` chaque fois que
son bloc est remplacé — cassé, écrasé par `/setblock`, détruit par une explosion —, si bien
que seuls des outils qui réécrivent les chunks sans mises à jour de blocs laissent des
extensions derrière eux.

```mcfunction
/pedestal spin
```

```text
Rotation activée.
```

```text
Spinning enabled.
```

Relance-la :

```text
Rotation désactivée.
```

```text
Spinning disabled.
```

`spin` lit l'état courant et l'inverse : il n'y a donc aucun moyen de le forcer
explicitement à activé ou coupé, c'est une bascule et rien d'autre. Une stèle démarre avec
`spinning = true`.

Les quatre écrivent par un setter qui appelle `markDirty()` et synchronise le block entity
aux clients proches : le changement est donc visible immédiatement et survit à un
redémarrage. Rien n'est journalisé.

## Ce qui n'a pas de commande

Pour être complet, puisque la forme de ce jeu de commandes surprend :

- Créer, modifier, renommer ou supprimer un collectible — dashboard uniquement.
- Modifier un ADN — dashboard uniquement.
- Lister les collectibles — dashboard uniquement. `/eac reload` donne un compte, pas une
  liste.
- Se téléporter à un œuf — dashboard uniquement.
- Donner l'item décoratif — dashboard uniquement
  (`POST /collectibles/:id/give-deco`), ou par clic droit sur ton propre œuf en admin.
- Lancer la vérification d'intégrité — dashboard uniquement.

Il y avait autrefois une interface d'administration en jeu, ouverte par `/eac`. Elle a été
retirée quand l'administration est passée sur CheeseDash, et la racine `/eac` affiche
désormais le message d'indication cité plus haut. `network/PacketExtras.java` est le
vestige de ce flux : son `register()` est un no-op documenté. Son commentaire dit qu'il est
conservé pour `AdminCommand`, mais le seul appelant restant est
`EggAndCheese.onInitialize`.

---

← [Premiers pas](getting-started.fr.md) · [Configuration](configuration.fr.md) →
