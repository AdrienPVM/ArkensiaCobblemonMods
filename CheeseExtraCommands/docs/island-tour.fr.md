[← CheeseExtraCommands](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](island-tour.md)

# Traqueur du tour de l'île

La seconde moitié de ce mod n'a rien à voir avec `/cheesecheck`. C'est un HUD : un
panneau dans le coin inférieur droit de l'écran qui liste, par leur nom, les habitants
d'Arkensia que le joueur n'a pas encore rencontrés, avec un compteur. Il existe parce
qu'une chasse au trésor à 26 NPCs est injouable si le joueur doit se souvenir de ceux à
qui il a déjà parlé.

⚠️ Il est câblé sur une île unique. Les 26 noms vivent dans une map statique
d'`IslandTourTracker`, et le titre du HUD est une chaîne littérale française. Sur
n'importe quel autre serveur, le HUD n'apparaîtra jamais ou listera des noms qui ne
veulent rien dire. Le réorienter implique de modifier le code source et de reconstruire.
Rien ne casse si tu le laisses tranquille : les objectifs absents, le traqueur conclut
« caché » et n'envoie rien.

---

## Le modèle

Trois pièces, chacune dans son fichier.

| Pièce | Fichier | Tourne sur |
|---|---|---|
| Lecteur d'état | `com.cheeseextra.arkensia.IslandTourTracker` | thread serveur |
| Format de transport | `com.cheeseextra.network.IslandTourPayload` | les deux, S2C |
| Rendu | `com.cheeseextra.client.IslandTourHud` | thread de rendu client |

Aucun nouvel état persistant. Le traqueur lit le scoreboard vanilla, que les dialogues de
NPC écrivaient déjà, et en déduit tout. C'est la décision de conception qu'il faut
comprendre : le mod est une *vue* sur un état possédé par le système de quêtes, pas une
seconde source de vérité. Rien ne peut se désynchroniser, puisque rien n'est dupliqué.

## La machine à états en scoreboards

Trois formes d'objectif la pilotent. Le traqueur n'en crée jamais aucune, et un objectif
absent est lu comme `0`.

| Objectif | Écrit par | Valeurs |
|---|---|---|
| `jenny_intro` | le dialogue de Jenny, la donneuse de quête | `0` ne lui a jamais parlé, `1` tour accepté et en cours, `2` tour terminé et récompensé |
| `spawn_tour` | le dialogue de première rencontre de chaque habitant, incrémenté | `0`–`26` |
| `<key>_met` | le dialogue de première rencontre de l'habitant correspondant | `0` pas rencontré, `1` rencontré |

Le HUD s'affiche quand, et seulement quand :

```java
boolean show = introVal >= 1 && introVal < 2 && tourVal < TOTAL;
```

Pour des entiers, `>= 1 && < 2` signifie exactement `1`. Le panneau est donc visible
pendant que `jenny_intro == 1` et `spawn_tour < 26`. Prendre la quête le fait apparaître,
la terminer le fait disparaître, et un joueur qui n'a jamais parlé à Jenny ne le voit
jamais.

`spawn_tour` et les objectifs `<key>_met` sont redondants volontairement. `spawn_tour`
est le verrou pas cher, lu une fois par joueur et par seconde. Les 26 objectifs
`<key>_met` ne sont lus que si ce verrou est déjà franchi, et ce sont eux qui produisent
la liste réelle des noms. Si les deux divergent un jour (un dialogue qui incrémente
`spawn_tour` sans écrire `<key>_met`, par exemple), le compteur du HUD suit les objectifs
`_met`, pas `spawn_tour`, car `met` est calculé comme `TOTAL - remaining.size()`.

## Les 26 habitants

L'ordre ci-dessous est celui d'`IslandTourTracker.NPCS`, une `LinkedHashMap`, et c'est
l'ordre dans lequel le HUD les liste. Il est alphabétique par nom affiché, avec une
exception : `alfred_main` passe en tête sous le nom affiché `Alfred`.

| Clé | Nom affiché | Objectif lu |
|---|---|---|
| `alfred_main` | Alfred | `alfred_main_met` |
| `bjorn` | Bjorn | `bjorn_met` |
| `brick` | Brick | `brick_met` |
| `chen` | Chen | `chen_met` |
| `dante` | Dante | `dante_met` |
| `dino` | Dino | `dino_met` |
| `ectoplasma` | Ectoplasma | `ectoplasma_met` |
| `finn` | Finn | `finn_met` |
| `flint` | Flint | `flint_met` |
| `joelle` | Joelle | `joelle_met` |
| `kai` | Kai | `kai_met` |
| `lucius` | Lucius | `lucius_met` |
| `max` | Max | `max_met` |
| `metamorph` | Metamorph | `metamorph_met` |
| `mira` | Mira | `mira_met` |
| `nemo` | Nemo | `nemo_met` |
| `nina` | Nina | `nina_met` |
| `primo` | Primo | `primo_met` |
| `rosalia` | Rosalia | `rosalia_met` |
| `selena` | Selena | `selena_met` |
| `stella` | Stella | `stella_met` |
| `sven` | Sven | `sven_met` |
| `suzy` | Suzy | `suzy_met` |
| `violette` | Violette | `violette_met` |
| `woody` | Woody | `woody_met` |
| `zora` | Zora | `zora_met` |

`TOTAL` vaut `NPCS.size()` : il vaut donc `26` par construction, et non par une constante
séparée. Ajoute une entrée à la map et le seuil suit. Les noms affichés ne portent aucun
accent, ce qui explique que `Joelle` et `Metamorph` apparaissent sans accent en jeu.

La map est enveloppée dans `Collections.unmodifiableMap` : rien ne peut la muter à
l'exécution.

## Le cycle de vie

### Chaque seconde

`ServerTickEvents.END_SERVER_TICK` appelle `IslandTourTracker.onServerTick`. Un compteur
statique saute 19 ticks sur 20, le corps tourne donc une fois par seconde.

Pour chaque joueur connecté :

1. Lire `jenny_intro` et `spawn_tour`.
2. Décider de `show`.
3. Si `show`, lire les 26 objectifs `<key>_met` et construire la liste des noms qui
   manquent encore. Sinon, utiliser une liste vide et fixer `met` à
   `min(spawn_tour, 26)`.
4. Comparer le résultat au dernier snapshot envoyé à ce joueur.
5. S'il diffère, envoyer un `IslandTourPayload` et mémoriser le nouveau snapshot.

L'étape 4 est la raison pour laquelle cela ne coûte rien. Le snapshot est un record
`(show, met, remaining)`, et un joueur immobile produit un snapshot identique chaque
seconde : aucun paquet ne sort. Un paquet n'est envoyé que quand l'état du tour a
réellement changé, ce qui arrive au plus quelques dizaines de fois sur une partie
entière.

Les deux premiers objectifs sont cherchés une seule fois par tick pour tout le serveur,
hors de la boucle sur les joueurs. Les 26 recherches `_met`, elles, se font par joueur,
mais seulement pour ceux dont le verrou est franchi.

### À la connexion

`ServerPlayConnectionEvents.JOIN` appelle `onPlayerJoin`, qui retire le snapshot en cache
du joueur puis lance le même `update`. Vider le cache d'abord est précisément ce qui force
un envoi : un joueur qui se reconnecte n'a aucun état de HUD côté client, le serveur doit
donc renvoyer même si rien n'a changé côté serveur.

### À la déconnexion

`ServerPlayConnectionEvents.DISCONNECT` retire le snapshot. La map est une
`ConcurrentHashMap` indexée par UUID et c'est la seule chose qui la purge : un serveur qui
ne verrait jamais d'événement de déconnexion pour un joueur fuirait donc un petit record.
En pratique, Fabric le déclenche toujours.

## Le payload

`IslandTourPayload` est un record et un `CustomPayload` Fabric, enregistré en S2C
uniquement :

```java
PayloadTypeRegistry.playS2C().register(ID, CODEC);
```

| Champ | Type sur le fil | Signification |
|---|---|---|
| `show` | booléen | si le HUD doit être visible du tout |
| `met` | VarInt | habitants rencontrés, pour le compteur |
| `total` | VarInt | toujours `26` en l'état du code, mais envoyé plutôt que supposé |
| `remaining` | VarInt de longueur, puis autant de chaînes plafonnées à 64 caractères | noms affichés restant à rencontrer |

Identifiant de canal : `cheeseextra:island_tour`.

Envoyer `total` sur le fil au lieu de l'écrire en dur côté client permet à un serveur dont
la map `NPCS` a été modifiée de fonctionner avec un jar client intact. Cela n'aide que si
les noms viennent aussi du serveur, ce qui est le cas.

L'envoi est enveloppé dans un `try/catch` qui écrit en niveau DEBUG. Un client dépourvu du
mod ne sait pas décoder le canal, et cet échec est avalé délibérément : le HUD est une
décoration optionnelle, pas de quoi déconnecter un joueur.

## Le rendu

`IslandTourHud` garde quatre champs statiques `volatile` mis à jour depuis le payload et
les dessine depuis `HudRenderCallback`. Il sort immédiatement, sans rien dessiner, dès que
l'une de ces conditions est vraie :

- `visible` est faux
- `remaining` est vide, même si `visible` est vrai
- le joueur est nul, le HUD est masqué par F1, ou un écran est ouvert

La deuxième condition compte : une fois que le joueur a rencontré les 26 habitants, le
serveur peut encore envoyer `show = true` pendant un tick ou deux, et le HUD se cache de
lui-même plutôt que de dessiner une boîte vide.

Mise en page, d'après les constantes d'`IslandTourHud` :

- ancré en bas à droite, 5 pixels mis à l'échelle de chaque bord
- titre `Habitants à rencontrer` en ambre (`0xFFF5A623`), compteur `(met/total)` en vert
  (`0xFF22C55E`), aligné à droite sur la même ligne
- noms en gris (`0xFFCCCCCC`), deux colonnes, en colonne d'abord : la première colonne se
  remplit jusqu'en bas avant que la seconde commence
- fond `0xCC1A1D28` et bordure d'un pixel en `0x80F5A623`, reprenant la palette de
  CheeseDash

Le nombre de lignes vaut `ceil(remaining / 2)` : la boîte rétrécit donc à mesure que le
joueur avance dans la liste. La largeur de colonne est un `70` pixels fixe. Un nom affiché
plus large que cela chevauche son voisin, et c'est pourquoi tous les noms de `NPCS` sont
courts.

## Le piloter depuis tes propres quêtes

Rien dans le traqueur n'est appelable. Pas de commande, pas d'API, pas d'entrypoint. La
seule façon de faire apparaître le HUD est de mettre les bonnes valeurs dans les bons
objectifs :

```mcfunction
# une fois, à l'échelle du serveur
scoreboard objectives add jenny_intro dummy
scoreboard objectives add spawn_tour dummy
scoreboard objectives add mira_met dummy
# ... et 25 autres

# dans le dialogue « accepter la quête » de Jenny
scoreboard players set @s jenny_intro 1

# dans le dialogue de première rencontre de chaque habitant, celui de Mira par exemple
execute unless score @s mira_met matches 1.. run scoreboard players add @s spawn_tour 1
scoreboard players set @s mira_met 1

# dans le dialogue « remettre la récompense » de Jenny
scoreboard players set @s jenny_intro 2
```

Le garde-fou `execute unless` sur l'incrément n'est pas optionnel. `spawn_tour` est un
simple compteur que rien n'empêche d'être incrémenté deux fois par le même NPC, et s'il
dépasse `26` le verrou se ferme et le HUD disparaît alors qu'il manque encore des noms.

## Le réorienter vers un autre serveur

Trois modifications, puis une reconstruction :

1. Remplace les entrées du bloc statique d'`IslandTourTracker` par tes propres paires
   `clé → nom affiché`. `TOTAL` suit.
2. Renomme `jenny_intro` et `spawn_tour` dans `onServerTick` si ta donneuse de quête ne
   s'appelle pas Jenny, ou crée des objectifs portant ces noms et laisse le code
   tranquille.
3. Change la chaîne `title` dans `IslandTourHud.render` si le français ne convient pas.

Garde des noms affichés sous une dizaine de caractères, ou élargis `COL_WIDTH` en
conséquence.

---

← [Configuration](configuration.fr.md) · [Architecture](architecture.fr.md) →
