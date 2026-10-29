[← CheeseGuard](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](zones-and-flags.md)

# Zones et drapeaux

Une zone est une boîte alignée sur les axes, dans un seul monde, avec un nom, un
propriétaire, une liste de membres et un jeu de drapeaux qui disent ce qui peut s'y
produire. Tout le reste de ce que fait CheeseGuard — les groupes, les events, la prison,
la vente de zones — est construit sur cet unique objet.

La classe est `com.cheeseguard.data.Region`. Le code l'appelle une *region* et tout ce que
voit un joueur ou un admin l'appelle une *zone* ; les deux mots désignent la même chose
dans cette documentation.

---

## Le modèle

### Les limites

Une zone stocke six entiers et un identifiant de monde :

| Champ | Signification |
|---|---|
| `world` | L'identifiant de dimension dans lequel vit la boîte, par exemple `minecraft:overworld` |
| `minX` `minY` `minZ` | Le coin bas, inclus |
| `maxX` `maxY` `maxZ` | Le coin haut, inclus |

Le constructeur et `setBounds()` normalisent tous les deux, donc tu ne peux pas créer une
boîte inversée : quel que soit l'ordre dans lequel tu passes les coins, la plus petite
valeur devient le minimum. Les deux coins sont inclus, ce qui veut dire qu'une zone d'un
seul bloc a `min == max` et un volume de un, et que le wand de sélection annonce
`dx×dy×dz` en calculant chaque axe comme `abs(pos2 - pos1) + 1`.

Il n'y a ni cylindre, ni polygone, ni mode « colonne entière » sans Y. Une zone qui doit
couvrir une parcelle de la bedrock au ciel est une boîte avec `minY = -64` et
`maxY = 320` ; ces nombres, c'est toi qui les mets.

`contains()` est une simple comparaison sur six bornes plus un test d'égalité de
l'identifiant de monde. Une zone ne correspond jamais à une position dans une autre
dimension — avec une exception volontaire, traitée dans
[les dimensions par joueur](#les-dimensions-par-joueur-voient-deux-fois-les-mêmes-zones).

### L'identité

L'identifiant est dérivé du nom, il n'est pas choisi :

```java
String id = name.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
```

`Arène du Nord` devient donc `ar_ne_du_nord`. Les lettres accentuées et les espaces sont
chacune remplacées par un underscore. L'identifiant est la clé partout : dans
`regions.json`, dans les commandes, dans l'API REST, sur les panneaux de vente.

> ⚠️ `/cg create` ne vérifie **pas** si cet identifiant existe déjà.
> `RegionStorage.addRegion()` est un `Map.put`, donc créer une seconde zone dont le nom se
> réduit à un identifiant existant **remplace silencieusement la première zone**, avec ses
> membres, ses drapeaux et sa configuration de vente. La route du dashboard refuse le même
> cas avec un HTTP 409. Jusqu'à correction, traite `/cg create` comme destructive et
> vérifie `/cg list` d'abord.

### Priorité et parenté

De quoi ordonner, sur deux champs :

- `priority` — un `int`, `0` par défaut, le plus grand gagne.
- `parentId` — l'identifiant d'une autre zone, `null` par défaut.

Les deux ne comptent que lorsque des zones se recouvrent. Voir
[les recouvrements](#les-zones-qui-se-recouvrent).

### Tout ce qu'une zone porte d'autre

| Champ | Défaut | Rôle |
|---|---|---|
| `groupId` | `null` | Le groupe dont cette zone hérite ses drapeaux. Voir [les groupes](groups.fr.md) |
| `owner` | le créateur | Un unique `Member`, ou `null` pour une zone sans propriétaire |
| `members` | vide | Les autres entrées `Member` |
| `flags` | vide | Carte à trois états, voir [les drapeaux](#les-drapeaux) |
| `priority` | `0` | Arbitrage des recouvrements |
| `entryMessageEnabled` | `false` | Faut-il envoyer une ligne en entrant |
| `entryMessage` | `"Vous entrez dans : <nom>"` | Cette ligne |
| `exitMessageEnabled` | `false` | Faut-il envoyer une ligne en sortant |
| `exitMessage` | `"Vous quittez : <nom>"` | Cette ligne |
| `protectionMessage` | `"⛔ Cette zone est protégée !"` | Texte d'action bar quand un drapeau bloque quelque chose |
| `particleColor` | `"green"` | Couleur du contour pour la visualisation `/cg toggle`. Au choix : red, green, blue, yellow, purple, cyan, white, orange |
| `requiredAdvancementsEntry` / `Exit` | vide | Conditions d'advancement, voir [les conditions d'entrée et de sortie](#les-conditions-dentrée-et-de-sortie) |
| `advancementDeniedMessage` | `"⛔ Vous n'avez pas les advancements requis !"` | Affiché quand une condition refuse |
| `requiredQuestsEntry` / `Exit` | vide | Conditions FTB Quests, identifiants de quête en hexadécimal |
| `commandsOnEnter` / `commandsOnExit` / `commandsOnBattleLoss` | vide | Déclencheurs de commandes, voir [les déclencheurs de commandes](#les-déclencheurs-de-commandes) |
| `tags` | vide | Étiquettes en texte libre, voir [les tags](#tags-et-limites-de-possession) |
| `saleConfig` | `null` | Mise en vente, voir [vendre une zone](#vendre-une-zone) |

Ces trois messages par défaut sont des chaînes françaises écrites en dur dans
`Region.java` et `RegionManager.java`, pas des clés de traduction. Un serveur qui tourne en
anglais affichera du français jusqu'à ce que tu remplaces le message zone par zone. C'est
une aspérité connue, pas un réglage.

---

## Créer une zone

Le wand de sélection est une **hache en or** nommée `Zone Selection Wand`. `/cg wand` t'en
donne une ; elle n'est pas craftable et rien d'autre dans le mod n'utilise cet item.

1. `/cg wand` — niveau de permission 2.
2. **Clic gauche** sur un bloc : position 1. Le clic est consommé, le bloc n'est donc pas
   cassé.
3. **Clic droit** sur un bloc : position 2. Le chat affiche alors la taille de la
   sélection et le nombre total de blocs.
4. `/cg create <nom>` — crée la zone dans le monde où tu te trouves, avec toi comme
   propriétaire.

Les deux clics du wand exigent le niveau de permission 2, et tous deux vérifient l'item
dans la main qui a cliqué. Une sélection vit en mémoire, par joueur
(`SelectionManager`), et est perdue au redémarrage.

La route `POST /zones` du dashboard consomme la **même** sélection : elle va chercher la
sélection stockée du joueur que tu nommes, ce qui veut dire qu'un admin sélectionne en jeu
et qu'un admin crée depuis le navigateur. Sans `playerName` dans le corps, elle se rabat
sur le premier joueur en ligne ayant le niveau de permission 2.

> Le propriétaire créé par `/cg create` et par le dashboard est un `Member` construit avec
> le constructeur à deux arguments, qui attribue le rôle `MEMBER` plutôt que `OWNER`. Il
> est tout de même stocké dans l'emplacement `owner` et traité partout comme le
> propriétaire, donc le comportement est juste, mais le rôle écrit dans `regions.json`
> induit en erreur. Une propriété transférée plus tard par `transferOwnership()` — ce que
> font l'achat et le menu de zone — écrit correctement `OWNER`.

---

## Les zones qui se recouvrent

Les zones peuvent se recouvrir librement. `RegionManager.getRegionsAt()` renvoie
**toutes** les zones qui contiennent la position, et la résolution parcourt cette liste
dans l'ordre. L'ordre est construit en deux étapes.

D'abord un tri par `priority` décroissante. Puis un second tri avec un comparateur qui
place un enfant avant son parent :

```java
regions.sort((a, b) -> {
    if (a.getId().equals(b.getParentId())) return 1;  // b est l'enfant de a → b d'abord
    if (b.getId().equals(a.getParentId())) return -1; // a est l'enfant de b → a d'abord
    return Integer.compare(b.getPriority(), a.getPriority());
});
```

Une paire parent/enfant directe se résout donc toujours en faveur de l'enfant, quoi que
disent les priorités, et tout le reste se résout par priorité. Deux zones de même priorité
sans lien de parenté gardent l'ordre dans lequel `RegionStorage` les a chargées, c'est-à-
dire l'ordre d'insertion de `regions.json` — stable, mais pas quelque chose sur quoi
construire. Donne des priorités différentes, ou un lien de parenté, à toute paire de zones
qui se recouvrent et dont l'arbitrage t'importe.

`parentId` est une indication sur un seul niveau, utilisée par ce comparateur. Ce n'est pas
une hiérarchie : un petit-enfant ne bat pas son grand-parent par la chaîne, seulement par
la priorité.

### L'ordre de résolution complet

`RegionManager.isAllowed(player, world, x, y, z, flag)` répond à toutes les questions de
protection du mod. Dans l'ordre :

1. **Contournement OP.** Le niveau de permission 2 ou plus renvoie *autorisé*
   immédiatement — mais uniquement pour les drapeaux à exemption de membre (voir plus
   bas), et uniquement si le joueur n'est pas en mode test.
2. **Exemption de membre.** Pour un drapeau à exemption, être le propriétaire ou un membre
   de *n'importe quelle* zone couvrant la position renvoie *autorisé*.
3. **Aucune zone ici** → on passe au jeu de drapeaux refusés de la dimension.
4. **Surcharges d'event.** Pour chaque event qui n'est pas à l'état `IDLE`, une surcharge
   runtime de clé `<zoneId>:<flagId>` gagne, puis une surcharge persistée venant de la
   configuration de l'event. Elles battent tout ce qui suit. Prop Hunt et les effets de
   timeline les posent.
5. **Passe 1 — drapeaux de zone explicites.** La première zone de la liste triée qui a une
   valeur `true`/`false` explicite pour ce drapeau décide.
6. **Passe 2 — drapeaux de groupe.** Aucune zone n'avait de valeur explicite ; on
   reparcourt la même liste et on prend le premier drapeau de groupe qui est posé.
7. **Passe 3 — repli dimension.** Toujours rien ; l'action n'est refusée que si la
   configuration de la dimension liste ce drapeau dans ses `deniedFlags`.
8. Sinon **autorisé**.

Le fait que les passes 1 et 2 soient séparées est tout l'intérêt. Si la zone A autorise
explicitement `pvp` et que la zone B, qui la recouvre, n'a pas de drapeau `pvp` propre mais
appartient à un groupe qui refuse `pvp`, le PvP est **autorisé** — un drapeau explicite sur
n'importe quelle zone bat un drapeau de groupe sur n'importe quelle zone, même plus
prioritaire. Si tu veux que la règle du groupe de B gagne, pose le drapeau explicitement
sur B.

`isFlagAllowed(world, x, y, z, flag)` est la variante sans joueur, utilisée pour les
vérifications environnementales (propagation du feu, griefing de mobs). Elle saute les
étapes 1, 2 et 4 et exécute directement les trois passes.

> `RegionManager` porte aussi un mode test (`toggleTestMode`, `isTestMode`) qui suspend le
> contournement OP et l'exemption de membre pour qu'un admin ressente la zone comme un
> joueur ordinaire. Rien ne l'appelle — aucune commande, aucune route d'API. L'ensemble est
> toujours vide à l'exécution. C'est du code mort de forme utile ; branche-le avant de
> compter dessus.

### Les dimensions par joueur voient deux fois les mêmes zones

L'identifiant d'une dimension par joueur a la forme `cheeseguard:<masterId>_<8 hex>`. Quand
on résout une position à l'intérieur de l'une d'elles, `getRegionsAt()` fait aussi
correspondre les zones dont le `world` est la dimension **maîtresse**
`cheeseguard:<masterId>`. Un seul jeu de zones couvre donc chaque copie d'un monde
instancié par joueur, ce qui est exactement ce qu'on veut : tu dessines l'arène une fois.

Le test du suffixe est strict — un underscore suivi d'exactement huit caractères
hexadécimaux minuscules — donc une dimension maîtresse dont l'identifiant finirait ainsi
serait mal interprétée. Évite d'appeler une maîtresse `quelque_chose_deadbeef`.

---

## Les drapeaux

Trente et un drapeaux, définis une seule fois dans `com.cheeseguard.data.Flag`. Chacun est
à trois états sur une zone et sur un groupe :

- **autoriser** — l'action est permise ; la résolution s'arrête là.
- **refuser** — l'action est refusée ; la résolution s'arrête là.
- **hériter** — aucune valeur stockée sur cet objet ; la résolution continue vers le
  groupe, puis vers la dimension. C'est l'état de chaque drapeau tant que tu n'y touches
  pas.

L'API REST écrit ces trois états `ALLOW`, `DENY` et `INHERIT`. `/cg flag` accepte `true`,
`false`, ou `inherit` / `null`. Un identifiant de drapeau que `Flag.fromId()` ne reconnaît
pas est ignoré au chargement et refusé par la commande.

> Il n'y a **aucune valeur par défaut par drapeau**. Le quatrième argument de chaque
> constante de l'énumération est `memberExempt`, pas un défaut — une erreur facile à faire
> en lisant le fichier. Sans rien de posé nulle part, toute action est autorisée ; un
> drapeau ne peut que restreindre.

### Drapeaux à exemption de membre et drapeaux de comportement

`memberExempt` sépare les drapeaux en deux familles, et change à qui le drapeau
s'applique :

- **Drapeaux à exemption (de protection)** — propriétaires, membres et OP les
  contournent. Ce sont les drapeaux classiques « que personne ne casse mes affaires ».
- **Drapeaux de comportement** — ils s'appliquent à **tout le monde**, membres et OP
  compris, parce qu'ils décrivent la façon dont le monde se comporte et non la question de
  savoir à qui il appartient. Refuser `fall-damage` dans une arène doit s'appliquer aussi
  à l'admin qui s'y tient, sinon l'arène ne fonctionne pas.

### Drapeaux de protection — membres et OP contournent

| Identifiant | Ce qu'il contrôle |
|---|---|
| `block-break` | Casser un bloc. Vérifié dans `PlayerBlockBreakEvents.BEFORE` |
| `block-place` | Placer un bloc |
| `interact` | Clic droit sur un bloc ou une entité : coffres, portes, PNJ |
| `entity-damage` | Attaquer toute entité qui n'est pas un joueur |
| `pvp` | Attaquer un joueur |
| `use-items` | Utiliser un seau, un briquet, une boule de feu, de la poudre d'os ou de la TNT. Le bloc cliqué **et** le bloc sur lequel le clic atterrit sont tous deux vérifiés |
| `explosions` | Explosions de TNT et de creepers |
| `item-drop` | Jeter des items |
| `item-pickup` | Ramasser des items |
| `ender-pearl` | Lancer une ender pearl |
| `entry` | Entrer dans la zone. Une entrée refusée téléporte le joueur sur le bloc d'où il venait |
| `exit` | Sortir de la zone. Même renvoi en arrière |

### Drapeaux de comportement — personne n'est exempt

| Identifiant | Ce qu'il contrôle |
|---|---|
| `fire-spread` | Le feu qui se propage de bloc en bloc |
| `mob-spawning` | Les spawns naturels de mobs |
| `mob-grief` | Les dégâts causés par les mobs : endermen qui soulèvent des blocs, mobs qui piétinent l'herbe |
| `trample` | Les cultures piétinées |
| `fly` | Vol à l'élytre et vol créatif. En refus, le vol plané est interrompu et la capacité de vol retirée, toutes les 10 ticks |
| `cobblemon-fly` | Chevaucher une monture Cobblemon en l'air. En refus, le joueur est éjecté dès que la monture quitte le sol |
| `cobblemon-mount` | Chevaucher une monture Cobblemon, tout court |
| `cobblemon-summon` | Avoir des Pokémon dehors. La sortie est bloquée par le hook Cobblemon ; arriver avec un Pokémon déjà dehors provoque un rappel de l'équipe |
| `fall-damage` | Les dégâts à l'atterrissage |
| `hunger` | La perte de faim |
| `leaf-decay` | Les feuilles qui se dégradent quand la bûche disparaît |
| `ice-melt` | La glace qui fond |
| `snow-form` | Les couches de neige qui se forment |
| `vine-grow` | Les vignes et plantes similaires qui s'étendent |
| `redstone` | Les mécanismes de redstone |
| `nametag-hide` | **Refuser ce drapeau masque les nametags.** Voir la note d'inversion plus bas |
| `minimap-hide` | **Refuser ce drapeau masque la minimap.** Même inversion |
| `coral-death` | Le corail qui meurt hors de l'eau |
| `cobbleshift-transform` | Être transformé par CobbleShift. En refus, le joueur est rétabli, ses effets sont nettoyés et le vol accordé est retiré |

> ⚠️ `nametag-hide` et `minimap-hide` se lisent à l'envers. Le code calcule
> `shouldHide = !isAllowed(..., flag)`, donc **refuser** signifie *masquer* et l'état par
> défaut (hériter, rien de posé) signifie *afficher*. Les noms de ces drapeaux décrivent
> l'effet de leur refus, pas celui de leur autorisation. Les deux sont poussés au client
> sur le canal `GuiSyncPayload` sous les noms `NAMETAG_ZONE` / `MINIMAP_ZONE` avec un corps
> `{"hide":true}`, et uniquement quand l'état change — un joueur sans le mod client ne voit
> donc rien se produire.

Les drapeaux qui dépendent de Cobblemon (`cobblemon-fly`, `cobblemon-mount`,
`cobblemon-summon`) sont entièrement sautés quand Cobblemon n'est pas chargé ; la
vérification sort tout de suite. `cobbleshift-transform` fait de même quand CobbleShift est
absent. Les conditions de quête sont sautées quand FTB Quests est absent.

### Ce que voit un joueur quand un drapeau le bloque

La plupart des refus affichent le `protectionMessage` de la zone sur l'**action bar** — la
ligne juste au-dessus de la barre d'items, pas dans le chat. Le défaut est
`⛔ Cette zone est protégée !`, pris sur la zone la plus prioritaire à la position ; sans
aucune zone, `RegionManager` se rabat sur la même chaîne littérale.

Une poignée de vérifications ont leur propre message traduit, depuis
`assets/cheeseguard/lang/` :

| Situation | Français | Anglais |
|---|---|---|
| `fly` refusé | `Vol interdit dans cette zone !` | `Flying is not allowed in this zone!` |
| `cobblemon-fly` refusé | `Monture volante interdite dans cette zone !` | `Flying mounts are not allowed in this zone!` |
| `cobblemon-mount` refusé | `Monture interdite dans cette zone !` | `Mounts are not allowed in this zone!` |
| `cobblemon-summon` refusé | `Pokémon interdits dans cette zone ! Rappel automatique.` | `Pokemon are not allowed in this zone! Auto-recall.` |
| `cobbleshift-transform` refusé | `Transformations CobbleShift interdites ici ! Annulée.` | `CobbleShift transformations are not allowed here! Reverted.` |
| `entry` refusé | `Vous ne pouvez pas entrer dans cette zone !` | `You cannot enter this zone!` |
| `exit` refusé | `Vous ne pouvez pas quitter cette zone !` | `You cannot leave this zone!` |

Les cinq vérifications périodiques — vol, montures Cobblemon, transformation, nametag,
minimap — tournent sur un handler de tick toutes les 10 ticks, soit environ deux fois par
seconde. Un joueur qui entre dans une zone sans vol continue de voler jusqu'à une
demi-seconde avant d'être lâché. C'est voulu : vérifier 31 drapeaux pour chaque joueur à
chaque tick coûterait plus que ça ne rapporte.

---

## Membres et rôles

`Member` est un UUID, un nom d'affichage mis en cache, et un rôle issu de `Member.Role` :
`OWNER` ou `MEMBER`. Il n'y a que ces deux-là.

Une zone détient un emplacement `owner` et une liste `members`. `isMember(uuid)` renvoie
vrai pour l'un comme pour l'autre, donc le propriétaire n'a jamais besoin de figurer aussi
dans la liste.

| Opération | Effet |
|---|---|
| `addMember` | Ajoute, sauf si `isMember` est déjà vrai |
| `removeMember` | Retire par UUID de la liste. Ne touche pas au propriétaire |
| `transferOwnership` | Retire le nouveau propriétaire de la liste des membres, rétrograde l'ancien en `MEMBER` et l'ajoute à la liste, puis installe le nouveau avec le rôle `OWNER` |
| `removeOwner` | Vide l'emplacement du propriétaire. La zone devient sans propriétaire ; ses membres gardent leur exemption |

Le nom en cache est celui que tu vois dans les listes et celui que `/cg removemember`
compare, sans tenir compte de la casse. Il est rafraîchi quand le joueur est résolu à
nouveau, donc un compte renommé peut afficher un nom périmé jusqu'à ce que quelqu'un touche
à l'appartenance.

### En jeu

Le propriétaire d'une zone qui s'y tient reçoit un rappel sur l'action bar toutes les deux
secondes — `Vous possédez <zone> — appuyez sur <touche> pour gérer` — avec la vraie touche
assignée à `key.cheeseguard.zone_menu`, donc une touche réassignée s'affiche
correctement. La touche ouvre un menu côté client qui peut ajouter un membre, en retirer
un, transférer la propriété, renommer la zone et l'abandonner. Le menu exige le mod client
CheeseGuard ; sans lui le serveur répond
`Ce menu nécessite le mod client CheeseGuard.`

`/cg addmember <zone> <joueur>` et `/cg removemember <zone> <joueur>` font la même chose
depuis la console ou le chat. La cible doit être **en ligne** pour `addmember`, parce que
l'UUID vient de `PlayerManager.getPlayer(name)`.

> ⚠️ `/cg flag`, `/cg addmember`, `/cg removemember`, `/cg group …` et `/cg buy` ne portent
> **aucune** clause `requires(permissionLevel)`. Contrairement à `/cg create`,
> `/cg delete` et `/cg wand`, elles sont accessibles à tous les joueurs, qui peuvent donc
> poser n'importe quel drapeau sur n'importe quelle zone par son identifiant. Si ton
> serveur accueille des joueurs non fiables, verrouille la commande `cg` dans ton mod de
> permissions jusqu'à correction.

---

## Les conditions d'entrée et de sortie

L'entrée et la sortie sont détectées en comparant la position de bloc occupée par le joueur
lors de la vérification précédente avec celle qu'il occupe maintenant, toutes les 10 ticks.
Pour chaque zone dans laquelle il vient d'entrer, dans l'ordre :

1. Le drapeau `entry`. Refusé → téléportation sur la position précédente et affichage de
   `Vous ne pouvez pas entrer dans cette zone !`
2. Les advancements requis, sauf si le joueur a le niveau de permission 2. Un seul
   manquant → téléportation en arrière, affichage de l'`advancementDeniedMessage` de la
   zone sur l'action bar, et liste des advancements manquants dans le chat par leur titre
   affiché.
3. Les quêtes FTB requises, aux mêmes conditions, quand FTB Quests est chargé.
4. Le message d'entrée, s'il est activé.
5. La liste `commandsOnEnter`.

La sortie exécute le miroir de tout ça avec `exit`, les listes de conditions de sortie,
`exitMessage` et `commandsOnExit`. Les deux listes de conditions sont l'**union** de la
liste propre à la zone et de celle de son groupe, ce qui permet à un groupe de conditionner
douze zones d'un coup.

Les identifiants de quête sont des chaînes hexadécimales — le format d'identifiant de FTB,
lu avec `Long.parseUnsignedLong(id, 16)`. Une quête qui ne peut pas être résolue compte
comme manquante, ce qui échoue du côté fermé : une faute de frappe verrouille la zone au
lieu de l'ouvrir. La recherche passe par réflexion sur
`dev.ftb.mods.ftbquests.quest.ServerQuestFile`, en balayant les méthodes par type de
paramètre plutôt que par nom afin de survivre au remappage. C'est une observation du
comportement actuel de FTB Quests, pas un contrat — une mise à jour de FTB peut la casser,
et quand ce sera le cas, toutes les quêtes requises seront lues comme manquantes.

La téléportation de renvoi utilise `teleport()` plutôt que `requestTeleport()` pour que le
joueur ne puisse pas être renvoyé dans le mauvais monde, et elle réécrit la position
mémorisée pour que la vérification suivante ne se redéclenche pas aussitôt.

---

## Les déclencheurs de commandes

Trois listes de commandes serveur brutes :

| Liste | Se déclenche quand |
|---|---|
| `commandsOnEnter` | Le joueur entre dans la zone |
| `commandsOnExit` | Le joueur quitte la zone |
| `commandsOnBattleLoss` | Le joueur perd un combat Cobblemon dans la zone |

Chaque entrée est une chaîne de commande. Un `/` initial est retiré s'il est présent, donc
les deux écritures fonctionnent. `{player}` est remplacé par le nom du joueur. Les lignes
vides sont ignorées.

La commande tourne avec la source de la **console serveur** — niveau opérateur — mais avec
le monde et la position du joueur injectés, ce qui compte pour les dimensions par joueur :
un `tp ~ ~ ~` relatif se résout dans la copie personnelle du joueur, pas dans la maîtresse.
Une commande qui lève une exception est rattrapée et signalée dans le log serveur sous la
forme `[CheeseGuard] Erreur commande zone "<cmd>": <message>` ; les commandes suivantes
s'exécutent quand même.

```json
"commandsOnEnter": [
  "effect give {player} minecraft:night_vision 20 0 true",
  "title {player} actionbar {\"text\":\"Mines du Nord\"}"
]
```

Donner à un joueur un déclencheur de commande de niveau opérateur est aussi puissant que ça
en a l'air. Une zone dans laquelle n'importe qui peut entrer, avec `op {player}` dans sa
liste d'entrée, offre ton serveur. Relis ces listes comme tu relirais une fonction de
datapack.

---

## Tags et limites de possession

`tags` est une liste de texte libre sur une zone. Rien n'impose de vocabulaire ;
`GET /zones/tags` collecte tous les tags distincts en usage pour l'autocomplétion.

Un tag prend du sens lorsqu'il a une configuration dans
`config/cheeseguard/tag_configs.json` :

```json
{
  "plot": {
    "label": "Parcelle résidentielle",
    "color": "#22d3ee",
    "ownershipLimit": 2
  }
}
```

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `label` | chaîne | l'identifiant du tag | Nom d'affichage. Omis du fichier quand il est égal à l'identifiant |
| `color` | chaîne | absent | Couleur d'affichage, utilisée par le dashboard uniquement |
| `ownershipLimit` | entier | `0` | Nombre maximum de zones portant ce tag qu'un joueur peut posséder. `0` signifie illimité |

Un identifiant de tag doit correspondre à `^[a-z0-9-]+$` et faire au plus 64 caractères.

La limite est vérifiée quand un joueur **achète** une zone, en comptant les zones dont il
est propriétaire et dont les tags contiennent ce tag. Un tag sans configuration, ou une
limite à zéro, ne bloque jamais. Rien ne revérifie la limite quand un admin attribue une
zone à la main ou quand la propriété est transférée depuis le menu de zone, donc un admin
peut faire dépasser un joueur de sa propre limite.

---

## Persistance

Les zones et les groupes partagent un seul fichier :

```text
config/cheeseguard/regions.json
```

Il est lu une seule fois, dans le constructeur de `RegionStorage`, à l'initialisation du
mod, et réécrit intégralement chaque fois qu'une zone ou un groupe est créé, supprimé ou
modifié — `addRegion`, `removeRegion`, `addGroup`, `removeGroup` et chaque écriture d'API
appellent `save()`. Les groupes sont désérialisés avant les zones, donc la référence
`group` d'une zone se résout toujours.

Écrit indenté, pour rester lisible. L'éditer à la main pendant que le serveur tourne ne
sert à rien : la copie en mémoire fait autorité et la prochaine écriture écrasera tes
modifications. Arrête le serveur d'abord.

### Forme

```json
{
  "regions": {
    "mines_du_nord": {
      "name": "Mines du Nord",
      "world": "minecraft:overworld",
      "minX": 120, "minY": -20, "minZ": -340,
      "maxX": 190, "maxY": 60,  "maxZ": -260,
      "group": "pve",
      "parent": "continent_nord",
      "priority": 10,
      "owner":   { "uuid": "…", "name": "RoiCheese", "role": "OWNER" },
      "members": [ { "uuid": "…", "name": "Alex", "role": "MEMBER" } ],
      "flags": { "block-break": false, "pvp": false, "fall-damage": false },
      "entryMessageEnabled": true,
      "entryMessage": "Vous entrez dans : Mines du Nord",
      "exitMessageEnabled": false,
      "exitMessage": "Vous quittez : Mines du Nord",
      "protectionMessage": "⛔ Cette zone est protégée !",
      "particleColor": "green",
      "tags": [ "plot" ],
      "commandsOnEnter": [ "effect give {player} minecraft:night_vision 20 0 true" ]
    }
  },
  "groups": { }
}
```

L'identifiant de la zone est la **clé**, pas un champ. Seuls les drapeaux explicitement
posés apparaissent dans `flags` ; un drapeau absent est en héritage. Plusieurs blocs ne
sont écrits que lorsqu'ils ne sont pas vides — `tags`, les trois listes de commandes, les
quatre listes de conditions, `saleConfig`, et `advancementDeniedMessage` quand il diffère
du défaut — ce qui garde le fichier court pour les zones simples.

`name`, `world` et les six bornes sont lus sans garde `has()`. Une zone éditée à la main
à laquelle il manque l'un de ces champs lève une exception, et tout le chargement avorte
dans le bloc catch : tu perds toutes les zones du fichier, pas seulement celle qui est
cassée, avec une seule ligne `Failed to load CheeseGuard data` dans le log. Sauvegarde le
fichier avant de l'éditer.

### Ce qui ne survit pas à un aller-retour

`SaleConfig` porte `signLine1`, `signLine2` et `signType`, et le sérialiseur n'en écrit
aucun. Définis les lignes personnalisées d'un panneau ou son type de bois depuis le
dashboard, et ça fonctionne jusqu'au prochain redémarrage ; après quoi les panneaux
retombent sur le nom de la zone, une deuxième ligne vide et le chêne. Vérifié en lisant
`RegionStorage.serializeRegion()` en regard de `SaleConfig` — les champs existent sur
l'API comme sur l'objet, seule l'étape de persistance manque.

---

## Vendre une zone

Une zone peut être mise en vente auprès des joueurs, payée dans l'une des trois monnaies,
et réclamée par un clic droit sur un panneau ou un hologramme. Toute la fonctionnalité
pend au `saleConfig` de la zone ; une zone dont le `saleConfig` est `null` n'est pas en
vente et rien chez elle ne change.

### La configuration de vente

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `enabled` | booléen | `false` | Interrupteur maître. Une config désactivée répond `Cette zone n'est pas en vente.` |
| `price` | long | `0` | Ramené à `>= 0` par le setter |
| `currency` | chaîne | `"NUMISMATIC"` | Au choix : `NUMISMATIC`, `FROMAGE`, `ITEMS`. Comparé sans tenir compte de la casse |
| `itemId` | chaîne | `null` | Pour `ITEMS` : l'identifiant de l'item, par exemple `minecraft:diamond` |
| `itemCount` | entier | `0` | Pour `ITEMS` : combien. Ramené à `>= 1` par le setter |
| `signText` | liste de chaînes | vide | Remplace tout le texte de l'hologramme, une entrée par ligne |
| `signLine1` | chaîne | nom de la zone | Ligne 2 du panneau physique. **Non persistée** |
| `signLine2` | chaîne | vide | Ligne 3 du panneau physique. **Non persistée** |
| `signType` | chaîne | `"oak"` | Type de bois de l'item panneau. **Non persisté** |
| `sold` | booléen | `false` | Posé par un achat réussi |
| `buyerUuid` / `buyerName` | chaîne | `null` | Écrits par un achat réussi |
| `hologramUuid` | chaîne | `null` | UUID du text display créé, géré par le mod |

`signType` accepte `oak`, `spruce`, `birch`, `jungle`, `acacia`, `dark_oak`, `mangrove`,
`cherry`, `bamboo`, `crimson` et `warped`. Toute autre valeur retombe sur le chêne.

### Les trois monnaies

**`NUMISMATIC`** passe par `NumismaticHook` jusqu'au mod Numismatic Overhaul. Le prix est
un unique nombre de bronze, et il est affiché décomposé : 10 000 bronze pour un or, 100
pour un argent. Un prix de `10250` s'affiche `1 gold 2 silver 50 bronze`. Un prix nul
s'affiche `0 bronze`.

**`FROMAGE`** passe par `CheeseStoreHook` jusqu'au portefeuille CheeseStore, indexé par
UUID de joueur. Affiché sous la forme `<prix> fromage`.

**`ITEMS`** compte les piles correspondantes dans tout l'inventaire de l'acheteur et les
prélève sur les premiers emplacements trouvés. Affiché sous la forme `<nombre>x <itemId>`.

Une monnaie inconnue consigne `Unknown sale currency: <valeur>` et fait renvoyer faux à
`canAfford`, donc la zone ne peut tout bonnement pas être achetée. Les deux hooks sont des
ponts vers des mods optionnels : si le mod de destination est absent, la vérification de
solvabilité est ce que le hook en décide, ce qui est une observation sur ces deux hooks et
non une garantie de cette page.

### Mettre une zone sur le marché

Depuis le dashboard, `PUT /zones/:id/sale` avec les champs ci-dessus, puis l'une ou les
deux routes suivantes :

- `POST /zones/:id/sale/hologram` — crée un `TextDisplayEntity` au centre horizontal de la
  zone, 1,5 bloc au-dessus de `maxY`, orienté pour faire toujours face au spectateur.
  L'UUID de l'entité est stocké dans `hologramUuid`. Les deux routes refusent avec un 400
  si la configuration de vente n'existe pas ou n'est pas activée.
- `POST /zones/:id/sale/sign?player=<nom>` — fabrique un item panneau ciré et le met dans
  l'inventaire de ce joueur, qui doit être **en ligne**, ou le fait tomber à ses pieds.
  Place-le là où la parcelle doit être annoncée.

Le text display est configuré par réflexion, parce que ses setters sont privés dans les
mappings Yarn ; les méthodes sont repérées par type de paramètre et non par nom, de sorte
que le remappage ne les casse pas. Si la réflexion échoue, l'hologramme apparaît tout de
même — vide. La ligne de log est
`Failed to configure TextDisplayEntity via reflection`.

Le panneau porte l'identifiant de la zone sur la **ligne 0 de son verso**, et c'est ainsi
qu'un clic est reconnu. Le recto fait quatre lignes : un en-tête traduit `[À VENDRE]` /
`[FOR SALE]`, `signLine1` ou le nom de la zone, `signLine2` ou rien, et le prix formaté. Le
panneau est ciré pour que les joueurs ne puissent pas l'éditer, et il porte aussi
`CheeseGuardSale: <zoneId>` dans ses données personnalisées.

Texte d'hologramme par défaut, quand `signText` est vide :

```text
FOR SALE
<nom de la zone>
<prix formaté>
[RIGHT-CLICK TO BUY]
```

et une fois vendue :

```text
<nom de la zone>
SOLD
Owner: <nom de l'acheteur>
```

Ces chaînes sont des littéraux dans `SaleManager.buildHologramText()` — elles ne sont pas
traduites et restent en anglais quelle que soit la langue du serveur. Utilise `signText`
s'il te les faut dans une autre langue.

### Acheter

Un clic droit sur le panneau ou sur l'hologramme, ou la commande `/cg buy <zoneId>`,
envoie une invitation :

```text
La zone Mines du Nord est en vente pour 1 gold 2 silver 50 bronze.
 [Cliquez pour acheter]
```

Le bouton exécute `/cg buy <zoneId> confirm`. Un clic sur un panneau est mémorisé pendant
**60 secondes**, indexé par UUID de joueur, pour que la confirmation sache quel panneau
casser. Cliquer sur un hologramme efface explicitement tout clic de panneau mémorisé, pour
qu'un clic périmé sur une autre parcelle ne contamine pas cet achat.

`SaleManager.processPurchase()` s'exécute alors, sur le thread serveur, dans cet ordre :

1. La configuration de vente existe et est activée, sinon `This zone is not for sale`.
2. Pas déjà vendue, sinon `This zone has already been sold`.
3. Les limites de possession par tag, sinon
   `You have reached the ownership limit for tag: <tag>`.
4. La solvabilité, sinon `You cannot afford this zone (<prix>)`.
5. **Prélèvement du paiement.** Un prélèvement numismatique ou en items qui échoue renvoie
   `Failed to deduct items from inventory` ou `Failed to deduct fromage payment`.
6. `transferOwnership()` — l'acheteur devient `OWNER`, l'ancien propriétaire est rétrogradé
   en `MEMBER` et conservé dans la liste des membres.
7. `sold = true`, `buyerUuid` et `buyerName` enregistrés.
8. `storage.save()`.
9. Casse du panneau cliqué, si l'un a été mémorisé dans le délai, sans le faire tomber.
10. Rafraîchissement du texte de l'hologramme s'il y en a un.

En cas de succès, l'acheteur reçoit `Vous êtes maintenant propriétaire de %s !` /
`You are now the owner of %s!` et le serveur consigne
`Zone '<nom>' purchased by <joueur> for <prix>`.

Les messages d'échec de l'étape 5 ne sont **pas** traduits — ils sont renvoyés tels quels,
en anglais, par `processPurchase` et affichés en rouge sans transformation. Les étapes 1 à
4 sont revérifiées ici même si la commande en avait déjà contrôlé une partie, parce que le
dashboard et le panneau aboutissent tous deux dans cette unique méthode.

> ⚠️ L'ancien propriétaire garde son statut de membre, et le statut de membre donne
> l'exemption de tous les drapeaux de protection. Vendre une parcelle n'expulse donc
> **pas** les droits de construction de son ancien propriétaire — il reste membre de la
> zone qu'il vient de vendre. Si une vente doit être une passation nette, vide la liste des
> membres après.

> `SaleManager.sendPurchasePrompt()` utilise la clé de traduction
> `cheeseguard.sale.already_sold` quand la zone est déjà vendue. Cette clé n'existe dans
> aucun des deux fichiers de langue — seule `cheeseguard.sale.alreadySold` existe. Un
> joueur qui clique sur un panneau vendu par cette voie voit la clé brute. Le chemin de la
> commande utilise la bonne clé, donc le défaut n'apparaît que sur les routes du panneau et
> de l'hologramme.

### Remettre en vente, ou retirer du marché

`POST /zones/:id/sale/relist` efface `sold`, `buyerUuid` et `buyerName`, force `enabled`
à `true`, et rafraîchit l'hologramme. Cela ne restaure **pas** l'ancien propriétaire et ne
retire pas l'acheteur — la propriété reste à celui qui l'a achetée, donc remettre en vente
une parcelle vendue revient à la revendre dans le dos de son propriétaire. Change le
propriétaire d'abord si ce n'est pas ce que tu veux.

`DELETE /zones/:id/sale` retire l'hologramme et efface entièrement la configuration de
vente. Le panneau physique, s'il en avait été posé un, reste dans le monde ; un clic
dessus tombe alors sur une zone sans configuration de vente et ne fait absolument rien,
parce que le gestionnaire de panneau n'intercepte le clic que lorsque `saleConfig != null`.

---

← [Configuration](configuration.fr.md) · [Groupes](groups.fr.md) →
