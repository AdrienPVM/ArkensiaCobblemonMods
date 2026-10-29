[← CheeseGuard](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](commands.md)

# Commandes — zones, flags, groupes et états

CheeseGuard enregistre trois commandes racines : `/cg`, `/prison` et `/chunksuggest`.
Il n'existe aucun alias `/cheeseguard` — le littéral racine dans `CheeseGuardCommand` est
`cg`, et rien n'enregistre un nom plus long.

Cette page couvre le côté zones de l'arbre `/cg` : sélection, CRUD de zone, flags,
membres, groupes, états de zone sauvegardés et la commande d'achat destinée aux joueurs.
Le reste de l'arbre — événements, prison, mondes par joueur, minijeux, restauration
d'inventaire et les autres sous-commandes d'exploitation — se trouve sur
[la page exploitation](commands-operations.fr.md).

## Niveaux de permission

Brigadier protège un nœud avec `requires(src -> src.hasPermissionLevel(2))` — le niveau
qu'un serveur vanilla accorde à un opérateur. Toutes les sous-commandes mutantes de cette
page portent cette garde ; quelques commandes de lecture ou destinées aux joueurs ne
l'ont délibérément pas.

| Sous-commande | Garde dans le code | Accès réel |
|---|---|---|
| `/cg` (nu), `gui` | la racine est ouverte ; `openGui` vérifie lui-même le niveau 2 | tout le monde — les non-ops reçoivent une aide, les ops un avis |
| `wand`, `pos1`, `pos2`, `create`, `delete`, `list`, `info` | niveau 2 | opérateurs |
| `flag`, `addmember`, `removemember` | niveau 2 | opérateurs |
| `group create`, `group delete`, `group add` | niveau 2 | opérateurs |
| `state capture/list/delete/apply` | niveau 2 | opérateurs |
| `tp`, `toggle` | aucune | tout le monde |
| `buy` | aucune (délibéré — c'est l'achat destiné aux joueurs) | tout le monde |
| `/chunksuggest` | aucune (délibéré) | tout le monde |

⚠️ `flag`, `addmember`, `removemember` et tout le sous-arbre `group` étaient **sans garde
jusqu'au 29 octobre 2026** : n'importe quel joueur pouvait désarmer la protection d'une
zone dont il devinait l'identifiant, et les identifiants de zone dérivent du nom de la
zone — donc ils se devinent. Ils sont protégés désormais. Si tu fais tourner un jar plus
ancien, protège ces nœuds avec un mod de permissions ou garde le serveur en liste blanche.

Que `tp` et `toggle` restent ouvertes est délibéré et beaucoup plus bénin, mais bon à
savoir : n'importe quel joueur peut se téléporter aux coordonnées du centre d'une zone, ce
qui révèle l'emplacement de ce que tu as construit derrière la protection.

Un joueur sous le niveau requis ne voit pas la sous-commande protégée dans la complétion
par tabulation et reçoit l'erreur vanilla *Unknown or incomplete command* s'il la tape
quand même.

Toutes les sous-commandes de cette page sauf `state`, `playerworld` et l'arbre `/prison`
doivent être lancées **par un joueur**, pas depuis la console : le handler appelle
`ctx.getSource().getPlayerOrThrow()` et retourne silencieusement `0` quand il n'y a pas de
joueur. Un appel console a donc l'air de n'avoir rien fait, sans message d'erreur. C'est un
défaut, pas un choix de conception.

---

## `/cg`

```mcfunction
/cg
/cg gui
```

Les deux formes appellent le même handler. Pour un opérateur, il n'affiche qu'une ligne :

```text
[CheeseGuard] Administration disponible sur le dashboard web CheeseDash.
```

Il n'y a plus d'interface d'administration en jeu. Elle a été supprimée
(`CheeseGuardScreen`, `EventEditorScreen`) quand l'administration est passée sur
CheeseDash, et le littéral `gui` est tout ce qu'il en reste.

Pour un joueur sous le niveau 2, la même commande affiche l'aide destinée aux joueurs :

```text
═══ CheeseGuard ═══
/cg event join — Rejoindre un événement
/cg event leave — Quitter un événement
/cg event spectate — Observer un événement
/cg event list — Voir les événements en cours
```

Cette liste est le résumé honnête de ce qu'un joueur normal est *censé* pouvoir faire.

---

## Sélectionner un volume

Une zone est une boîte. Tu la définis en marquant deux coins opposés, puis en la nommant.

### `/cg wand`

```mcfunction
/cg wand
```

Niveau 2. Insère dans ton inventaire une hache en or nommée *Zone Selection Wand* et
confirme au-dessus de la barre d'action :

```text
Zone wand received!
```

Cette confirmation reste en anglais même avec un client français : la clé
`cheeseguard.cmd.wand_received` n'a pas été traduite dans `fr_fr.json`, pas plus que le nom
de l'objet. C'est un oubli.

Clic gauche sur un bloc pour poser le coin 1, clic droit pour le coin 2 (géré par
`WandListener`). Le wand est une hache en or ordinaire avec un nom personnalisé — ce n'est
pas un item enregistré, donc n'importe qui peut le tenir.

### `/cg pos1` / `/cg pos2`

```mcfunction
/cg pos1
/cg pos2
```

Niveau 2. Marque le coin à **ta propre position de bloc**, dans le monde où tu te trouves,
sans avoir besoin du wand. Pratique depuis une chaîne de blocs de commande, ou quand tu
voles.

```text
Position 1 : 118, 64, -302
```

```text
Position 2 : 142, 90, -276
```

Les deux coins sont stockés par joueur dans `SelectionManager`, indexés par UUID, et
uniquement en mémoire : un redémarrage efface toutes les sélections en cours.

Une sélection n'est utilisable qu'une fois les **deux** coins posés, et dans le même
monde. `pos1` et `pos2` enregistrent l'identifiant du monde avec la position.

### `/cg create`

```mcfunction
/cg create <name>
```

Niveau 2.

| Argument | Type | Requis | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `name` | chaîne gourmande | oui | n'importe quel texte ; avale la fin de la ligne, donc les espaces n'ont pas besoin de guillemets | — |

L'identifiant de la zone dérive du nom : passé en minuscules, puis tout caractère hors de
`[a-z0-9_-]` remplacé par `_`. `Mon Arène #2` devient `mon_ar_ne__2`. Deux noms différents
peuvent produire le même identifiant, et rien ne le vérifie — le second `create` écrase la
première entrée de `regions.json`.

La boîte est normalisée (min/max par axe), l'ordre dans lequel tu as marqué les coins n'a
donc pas d'importance. Le joueur créateur devient le premier membre de la zone.

```mcfunction
/cg create Market Square
```

```text
Région 'Market Square' créée !
```

| Message | Cause |
|---|---|
| `Aucune sélection ! Utilisez le wand.` | aucune sélection, ou un seul coin posé |
| `Sélection incomplète !` | la même condition, signalée depuis `createRegion` |

### `/cg delete`

```mcfunction
/cg delete <id>
```

Niveau 2. `id` est un mot unique — l'**identifiant** de la zone, pas son nom affiché.
`/cg list` montre les deux.

```mcfunction
/cg delete market_square
```

```text
Zone supprimée : market_square
```

| Message | Cause |
|---|---|
| `Zone introuvable : <id>` | aucune zone avec cet identifiant |

La suppression écrit `regions.json` immédiatement. Elle ne retire pas la zone des groupes
qui la référençaient : un groupe peut donc garder un identifiant de zone fantôme — voir
[les groupes](groups.fr.md).

---

## Inspecter les zones

### `/cg list`

```mcfunction
/cg list
```

Niveau 2. Toutes les zones de tous les mondes, avec leur identifiant et un nom de monde
abrégé.

```text
Zones (3)
• Market Square [market_square] – Overworld
• Prison [prison] – Overworld
• Boss Arena [boss_arena] – minecraft:the_nether
```

La colonne monde passe par un helper qui ne reconnaît que les trois mondes vanilla par
sous-chaîne (`overworld`, `nether`, `end`) ; tout autre identifiant de dimension est
affiché brut. Une dimension personnalisée dont l'identifiant contient `end` — par exemple
`arkensia:endless_cave` — est donc étiquetée *End* à tort. Cosmétique, mais déroutant
quand tu cherches une zone.

Aucun filtre, aucune pagination. Avec quelques centaines de zones, cela noie le chat ;
lis-les plutôt depuis le dashboard.

### `/cg info`

```mcfunction
/cg info <id>
```

Niveau 2.

```mcfunction
/cg info market_square
```

```text
Market Square
ID : market_square | Overworld
Min : 118,64,-302
Max : 142,90,-276
Groupe : aucun
Priorité : 0 | Membres : 1
```

La ligne `Groupe :` affiche la chaîne littérale `aucun` quand la zone n'appartient à aucun
groupe, dans les deux langues — ce repli est codé en dur en Java au lieu de venir du
fichier de langue. Les flags ne sont pas affichés : `info` ne couvre que la géométrie, le
groupe, la priorité et le nombre de membres.

| Message | Cause |
|---|---|
| `Zone introuvable.` | aucune zone avec cet identifiant |

### `/cg tp`

```mcfunction
/cg tp <id>
```

**Aucune garde de permission.** Te téléporte au centre horizontal de la zone, dans le
monde où tu te trouves — pas dans le monde de la zone. Te téléporter vers une zone du
Nether depuis l'Overworld te dépose à ces coordonnées dans l'Overworld.

La coordonnée Y est choisie en balayant depuis le `maxY` de la zone vers le bas, jusqu'au
premier niveau avec un bloc non vide dessous et deux blocs non solides au-dessus. Si aucun
niveau ne convient, le Y central de la zone est utilisé tel quel, ce qui peut te placer
dans la pierre.

```text
TP vers : Market Square
```

| Message | Cause |
|---|---|
| `Zone introuvable.` | aucune zone avec cet identifiant |

### `/cg toggle`

```mcfunction
/cg toggle
```

**Aucune garde de permission.** Active ou coupe le contour en particules des zones
proches, pour toi seul, dessiné par `VisualizationManager`.

```text
Visualisation : Activée
```

```text
Visualisation : Désactivée
```

L'état est par joueur et en mémoire uniquement.

---

## Flags

### `/cg flag`

```mcfunction
/cg flag <region> <flag> <value>
```

Niveau 2.

| Argument | Type | Requis | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `region` | mot unique | oui | un identifiant de zone | — |
| `flag` | mot unique | oui | un identifiant de flag du tableau ci-dessous | — |
| `value` | mot unique | oui | `true`, `false`, `inherit`, `null` | — |

`inherit` et `null` (sans distinction de casse) effacent le flag : la zone retombe alors
sur son groupe, puis sur le défaut du flag lui-même. Toute autre valeur passe par
`Boolean.parseBoolean`, ce qui veut dire que chaque valeur non reconnue — `yes`, `1`,
`allow`, une faute de frappe — est lue comme `false`. Aucune validation, aucun
avertissement. C'est de loin la façon la plus facile d'interdire quelque chose par
accident.

Aucune complétion n'est câblée sur les arguments `flag` et `value` : ce sont de simples
arguments mot, il faut donc connaître l'orthographe.

Les 31 identifiants de flag :

| Identifiant | Ce qu'il contrôle | Membres exemptés |
|---|---|---|
| `block-break` | casser des blocs | oui |
| `block-place` | placer des blocs | oui |
| `interact` | coffres, portes, interactions PNJ | oui |
| `entity-damage` | attaquer entités et mobs | oui |
| `pvp` | attaquer des joueurs | oui |
| `use-items` | utiliser des items (seau, briquet…) | oui |
| `explosions` | TNT, creepers | oui |
| `fire-spread` | propagation du feu | non |
| `mob-spawning` | spawn naturel de mobs | non |
| `mob-grief` | griefing de mobs (enderman, herbe broutée) | non |
| `trample` | piétiner les cultures | non |
| `item-drop` | jeter des items | oui |
| `item-pickup` | ramasser des items | oui |
| `ender-pearl` | téléportation à l'ender pearl | oui |
| `fly` | vol (elytra, créatif) | non |
| `cobblemon-fly` | montures volantes Cobblemon | non |
| `cobblemon-mount` | montures terrestres Cobblemon | non |
| `cobblemon-summon` | sortir des Pokémon | non |
| `fall-damage` | dégâts de chute | non |
| `hunger` | perte de faim | non |
| `entry` | entrer dans la zone | oui |
| `exit` | quitter la zone | oui |
| `leaf-decay` | dégradation des feuilles | non |
| `ice-melt` | fonte de la glace | non |
| `snow-form` | formation de neige | non |
| `vine-grow` | pousse des vignes et lianes | non |
| `redstone` | mécanismes redstone | non |
| `nametag-hide` | masque les nametags (mixin client) | non |
| `minimap-hide` | masque la minimap (mixins Jade / JourneyMap) | non |
| `coral-death` | mort du corail hors de l'eau | non |
| `cobbleshift-transform` | transformation CobbleShift | non |

*Membres exemptés* correspond au champ `memberExempt` de l'énumération. Pour un flag
`oui`, les membres et le propriétaire de la zone contournent la restriction — le
comportement classique d'une protection, où tu peux casser ta propre construction. Pour un
flag `non`, la restriction s'applique à tout le monde y compris le propriétaire, parce
qu'elle décrit l'environnement plutôt qu'une permission.

La sémantique de chaque flag, l'héritage et la priorité sont dans
[zones et flags](zones-and-flags.fr.md).

```mcfunction
/cg flag market_square block-break false
```

```text
Flag block-break = false
```

```mcfunction
/cg flag market_square pvp inherit
```

```text
Flag pvp = inherit
```

| Message | Cause |
|---|---|
| `Zone introuvable.` | aucune zone avec cet identifiant |
| `Flag inconnu : <id>` | l'identifiant n'est pas dans le tableau ci-dessus — vérifie les tirets |

Une pose comme un effacement sauvegardent `regions.json` de façon synchrone, sur le thread
serveur.

---

## Membres

### `/cg addmember`

```mcfunction
/cg addmember <region> <player>
```

Niveau 2.

| Argument | Type | Requis | Valeurs acceptées |
|---|---|---|---|
| `region` | mot unique | oui | un identifiant de zone |
| `player` | mot unique | oui | le nom d'un joueur **connecté** |

La cible doit être connectée, parce que le handler la résout via
`PlayerManager.getPlayer(name)` pour récupérer son UUID. Il n'y a pas de chemin hors ligne
et pas de repli sur le cache des profils — contrairement à `/prison`, qui en a un.

```mcfunction
/cg addmember market_square Steve
```

```text
Steve ajouté.
```

| Message | Cause |
|---|---|
| `Zone introuvable.` | aucune zone avec cet identifiant |
| `Joueur introuvable.` | le joueur n'est pas connecté |

Ajouter un membre qui l'est déjà n'est pas refusé.

### `/cg removemember`

```mcfunction
/cg removemember <region> <player>
```

Niveau 2. Cherche les membres par nom affiché, sans distinction de casse, et retire la
première correspondance.

```mcfunction
/cg removemember market_square steve
```

```text
Steve retiré.
```

Le message de succès est envoyé **que quelque chose ait été retiré ou non** : le handler
signale la réussite sans condition après la recherche. Lance `/cg info <id>` et compare le
nombre de membres si tu as besoin d'en être sûr. Seul `Zone introuvable.` est un vrai
échec ici.

Les membres sont stockés par UUID *et* par nom. Un joueur qui change de pseudo Minecraft
conserve son appartenance (les contrôles de protection comparent les UUID) mais ne peut
plus être retiré avec son nouveau nom, puisque cette commande compare le nom stocké.

---

## Groupes

Un groupe est un paquet nommé de zones qui partagent des flags par défaut. Une zone résout
un flag qu'elle ne définit pas elle-même depuis son groupe. Le modèle est décrit dans
[les groupes](groups.fr.md) ; ici, seule la surface en commandes.

Tout le sous-arbre `group` exige le **niveau 2**.

### `/cg group create`

```mcfunction
/cg group create <name>
```

| Argument | Type | Requis | Valeurs acceptées |
|---|---|---|---|
| `name` | chaîne gourmande | oui | n'importe quel texte |

L'identifiant est fabriqué à partir du nom exactement comme pour `/cg create`.

```mcfunction
/cg group create Public Areas
```

```text
Groupe créé : Public Areas
```

| Message | Cause |
|---|---|
| `Groupe déjà existant.` | un groupe avec cet identifiant est déjà stocké |

### `/cg group delete`

```mcfunction
/cg group delete <id>
```

```text
Groupe supprimé : public_areas
```

La confirmation est inconditionnelle — supprimer un groupe qui n'a jamais existé signale
une réussite. Les zones qui pointaient vers le groupe gardent leur `groupId` et finissent
par référencer un groupe disparu. Réaffecte-les avant de supprimer.

### `/cg group add`

```mcfunction
/cg group add <region> <group>
```

Affecte une zone à un groupe en écrivant les deux côtés du lien : le `groupId` de la zone
et la liste de zones du groupe.

```mcfunction
/cg group add market_square public_areas
```

```text
Market Square → groupe Public Areas
```

| Message | Cause |
|---|---|
| `Zone ou groupe introuvable.` | l'un des deux identifiants est inconnu — le message ne dit pas lequel |

Il n'existe pas de `group remove`. Pour détacher une zone, réaffecte-la depuis le
dashboard.

---

## Les états de zone sauvegardés

Un *état* est une copie capturée de chaque bloc (et un décompte d'entités) à l'intérieur
d'une zone, sauvegardée sous `config/cheeseguard_states/<zoneId>/`. Réappliquer un état
reconstruit la zone, éventuellement sous forme d'animation. Les remises à zéro d'arène et
les décorations saisonnières reposent dessus.

Tout le sous-arbre `state` exige le **niveau 2** et, contrairement au reste de cette page,
fonctionne depuis la console.

### `/cg state capture`

```mcfunction
/cg state capture <zoneId> <name>
```

| Argument | Type | Requis | Valeurs acceptées |
|---|---|---|---|
| `zoneId` | mot unique | oui | un identifiant de zone |
| `name` | chaîne gourmande | oui | une étiquette pour la capture |

```mcfunction
/cg state capture boss_arena Pristine
```

```text
État 'Pristine' capturé (18432 blocs, 3 entités)
```

La capture parcourt toute la boîte, son coût grandit donc avec le volume. Une zone de
100×100×100 représente un million de lectures de blocs sur le thread serveur — attends-toi
à un pic de tick. Capture de petites zones, ou capture quand personne n'est connecté.

| Message | Cause |
|---|---|
| `Zone introuvable : <id>` | aucune zone avec cet identifiant |

### `/cg state list`

```mcfunction
/cg state list <zoneId>
```

```text
États de la zone 'boss_arena' :
  a1b2c3d4 — Pristine (18432 blocs, 3 entités)
```

La première colonne est l'identifiant d'état que tu passes à `apply` et `delete`.

| Message | Cause |
|---|---|
| `Aucun état pour la zone '<id>'` | la zone n'a aucune capture — c'est aussi ce que tu obtiens pour un identifiant de zone inconnu |

### `/cg state delete`

```mcfunction
/cg state delete <zoneId> <stateId>
```

```text
État 'a1b2c3d4' supprimé de la zone 'boss_arena'
```

| Message | Cause |
|---|---|
| `État introuvable : <id>` | aucun état avec cet identifiant sous cette zone |

### `/cg state apply`

```mcfunction
/cg state apply <zoneId> <stateId>
/cg state apply <zoneId> <stateId> <animated>
/cg state apply <zoneId> <stateId> <animated> <direction>
/cg state apply <zoneId> <stateId> <animated> <direction> <speed>
/cg state apply <zoneId> <stateId> <animated> <direction> <speed> <particle>
/cg state apply <zoneId> <stateId> <animated> <direction> <speed> <particle> <blockbyblock>
```

Les arguments sont positionnels et chacun a un défaut quand il est omis : les six formes
ci-dessus sont donc le même handler avec un niveau de détail croissant.

| Argument | Type | Requis | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `zoneId` | mot unique | oui | un identifiant de zone | — |
| `stateId` | mot unique | oui | un identifiant d'état donné par `state list` | — |
| `animated` | booléen | non | `true`, `false` | `false` |
| `direction` | mot unique | non | l'une des 14 directions ci-dessous | `bottom_up` |
| `speed` | entier | non | 1 à 100, imposé par Brigadier | `2` |
| `particle` | mot unique | non | l'une des 18 particules ci-dessous | `cloud` |
| `blockbyblock` | booléen | non | `true`, `false` | `false` |

Directions : `bottom_up`, `top_down`, `slide_from_bottom`, `slide_from_top`,
`slide_from_north`, `slide_from_south`, `slide_from_east`, `slide_from_west`,
`cascade_from_bottom`, `cascade_from_top`, `cascade_from_north`, `cascade_from_south`,
`cascade_from_east`, `cascade_from_west`.

`bottom_up` et `top_down` révèlent l'état couche par couche, sur place. Les formes
`slide_*` font venir les blocs depuis l'extérieur de la zone le long d'un axe. Les formes
`cascade_*` font de même mais activent les blocs les plus proches d'abord, ce qui donne
l'impression que la construction se déploie.

Particules : `none`, `cloud`, `poof`, `smoke`, `flame`, `soul`, `sparkle`, `enchant`,
`totem`, `heart`, `drip`, `cherry`, `sculk`, `witch`, `snowflake`, `note`, `electric`,
`ash`.

Une `direction` ou une `particle` non reconnue n'est **pas** une erreur : toutes deux
passent par un `fromString` qui retombe sur le défaut (`bottom_up`, `cloud`) pour tout ce
qu'il ne connaît pas. Une faute de frappe te donne silencieusement l'animation par défaut.

Le monde utilisé pour la reconstruction est celui de la **source de la commande**, et c'est
ce qui fait fonctionner `state apply` dans une dimension par joueur : la même définition de
zone s'applique dans la copie du monde où se tient l'appelant.

```mcfunction
/cg state apply boss_arena a1b2c3d4
```

```text
État 'a1b2c3d4' appliqué à la zone 'boss_arena'
```

```mcfunction
/cg state apply boss_arena a1b2c3d4 true cascade_from_bottom 4 sparkle true
```

```text
Animation de l'état 'a1b2c3d4' sur la zone 'boss_arena'...
```

Une application animée rend la main immédiatement et s'exécute sur les ticks suivants : la
ligne de retour signifie « démarré », pas « terminé ».

| Message | Cause |
|---|---|
| `Erreur : <détail>` | le canal d'erreur unique de cette sous-commande — zone inconnue, état inconnu, fichier illisible sur le disque. Le détail vient de `ZoneStateManager.applyState` |

---

## `/cg buy`

```mcfunction
/cg buy <zoneId>
/cg buy <zoneId> confirm
```

La commande d'achat destinée aux joueurs, délibérément sans garde. Sans `confirm`, elle
affiche le prix et une invite cliquable ; avec `confirm`, elle exécute la vente.

| Argument | Type | Requis | Valeurs acceptées |
|---|---|---|---|
| `zoneId` | mot unique | oui | un identifiant de zone dont la vente est activée |
| `confirm` | littéral | non | le mot `confirm` |

```mcfunction
/cg buy plot_14
```

```text
La zone plot_14 est en vente pour 2500.
[Cliquez pour acheter]
```

```mcfunction
/cg buy plot_14 confirm
```

```text
Vous êtes maintenant propriétaire de Plot 14 !
```

| Message | Cause |
|---|---|
| `Zone introuvable.` | aucune zone avec cet identifiant |
| `Cette zone n'est pas en vente.` | la zone n'a pas de configuration de vente, ou elle est désactivée |
| `Cette zone a déjà été vendue.` | la configuration de vente est marquée vendue |
| `Vous ne pouvez pas vous offrir cette zone. Prix : <prix>` | le solde du portefeuille est sous le prix |
| `Vous possédez déjà le nombre maximum de zones avec le tag '<tag>'.` | le plafond de possession par tag de `tag_configs.json` est atteint |

Un achat réussi ajoute l'acheteur comme propriétaire, marque la vente comme conclue et
persiste `regions.json` et la configuration des tags. La monnaie vient du portefeuille que
`SaleManager` trouve — voir [zones et flags](zones-and-flags.fr.md) pour le modèle de vente
et le format du panneau.

Les chaînes d'échec *Vous ne pouvez pas vous offrir* et *nombre maximum de zones* sont
renvoyées par `SaleManager.processPurchase` comme une chaîne déjà formatée et affichées
avec `Text.literal` : elles ne sont donc **pas** traduites, même si les fichiers de langue
contiennent les clés correspondantes. Elles s'affichent dans la langue du serveur, pas
dans celle du client. Tous les autres messages de cette page passent par
`Text.translatable` et respectent la langue du client.

---

## `/chunksuggest`

```mcfunction
/chunksuggest
```

Enregistrée comme sa propre commande racine, pas sous `/cg`, et accessible à **tout le
monde**. Le commentaire du code en donne la raison : la nicher sous une racine protégée
par un niveau d'opérateur serait entré en conflit avec la garde de permission du mod
`chunkstoclear`.

Aucun argument. Elle enregistre ta position et ta dimension actuelles comme suggestion de
régénération de cette région de carte pour l'administrateur.

```text
Suggestion envoyée à l'administrateur ! (x:1184 y:72 z:-3012)
```

Avant d'enregistrer quoi que ce soit, elle vérifie si ta région de carte de 512×512 est
déjà couverte par un set de chunks configuré dans la même dimension, et refuse si c'est
le cas :

```text
Cette région fait déjà partie du set "wild_east" et sera régénérée par l'administrateur. Pas besoin de suggérer !
```

| Message | Cause |
|---|---|
| `Système de suggestions non disponible.` | `ChunkSuggestionStorage` est null — le mod n'a pas fini de s'initialiser |

Les suggestions atterrissent dans `config/cheeseguard/chunk_suggestions.json` et se
relisent depuis le dashboard. Le modèle des sets et le planificateur de remise à zéro sont dans
[les chunks](chunks.fr.md).

---

← [Premiers pas](getting-started.fr.md) · [Événements, prison et exploitation](commands-operations.fr.md) →
