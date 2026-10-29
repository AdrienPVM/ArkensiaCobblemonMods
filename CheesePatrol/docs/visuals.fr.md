[← CheesePatrol](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](visuals.md)

# Rendu visuel

Trois systèmes d'affichage sans rapport entre eux vivent dans `PatrolMarker`, et il vaut la
peine de savoir lequel est lequel, parce qu'ils n'ont ni le même public ni le même coût.

| Système | Qui le voit | Durée de vie | Piloté par |
|---|---|---|---|
| **Aperçu du trajet** | un seul administrateur | jusqu'à extinction | `/npcpatrol show` |
| **Marqueurs de points de passage** | tout le monde | jusqu'au retrait manuel ou à l'arrêt du serveur | `/npcpatrol markers` |
| **Effet de traînée** | tout le monde à portée | permanent, fait partie de la patrouille | `/npcpatrol particles` |

---

## L'aperçu du trajet

`/npcpatrol show` est l'outil de conception. Il dessine le circuit entier pour **toi seul**,
redessiné toutes les 10 ticks, et c'est la façon la plus rapide de voir qu'un segment
traverse un mur.

**Ce qui est dessiné :**

- Sur chaque point de passage, 8 particules de poussière à `x + 0.5`, `y + 1.5`,
  `z + 0.5` — un bloc et demi plus haut, pour que le nuage soit à hauteur de tête et non
  dans le plancher. Dispersion de 0,15 sur chaque axe, pour que cela se lise comme une
  petite bouffée et pas comme un point.
- Le long de chaque segment, une ligne de poussière à `y + 1.0`, une particule tous les
  0,5 bloc, en ambre (`1.0, 0.67, 0.0`) à l'échelle 0,8. Les segments sont tracés plus bas
  que les nuages des points de passage, ce qui rend les jonctions lisibles.

**Les couleurs codent la position dans la liste :**

| Couleur | Point de passage |
|---|---|
| Vert | le premier, `#1` |
| Rouge | le dernier |
| Bleu | tous ceux du milieu |

Sur une patrouille à deux points de passage, tu obtiens un vert et un rouge, pas de bleu.
Sur une patrouille à un seul point — qui ne peut pas démarrer, mais peut être prévisualisée
— le point unique est vert, parce que le test « premier point » passe avant le test
« dernier point ».

**Le segment de fermeture est dessiné.** La ligne boucle du dernier point de passage vers
le premier, parce que le circuit le fait. C'est le segment dont on oublie l'existence, et le
voir tracé en travers d'une place de village est généralement le moment où un trajet se
fait repenser.

**Portée et durée de vie :**

- Un seul NPC à la fois par joueur. Appeler `show` sur une seconde patrouille remplace la
  première, puisque l'association va d'un UUID de joueur vers un UUID de NPC.
- L'affichage est entièrement supprimé pendant que tu es dans une autre dimension que la
  patrouille — pas d'erreur, rien de dessiné. Reviens dans le bon monde.
- L'entrée est abandonnée automatiquement si tu te déconnectes, ou si la patrouille est
  effacée ou perd tous ses points de passage.
- Les particules sont envoyées avec `force = true` : elles s'affichent donc à longue
  distance et sur un client réglé en particules minimales.

⚠️ **Le coût dépend de la longueur du trajet, pas du nombre de points de passage.** Un
circuit de 12 points et 300 blocs de longueur totale représente environ 100 particules de
points de passage plus 600 particules de ligne, renvoyées toutes les 10 ticks. C'est
acceptable pour un administrateur qui conçoit un trajet, et ce n'est pas à laisser allumé.
Éteins quand tu as fini.

## Les marqueurs de points de passage

`/npcpatrol markers` plante une vraie entité sur chaque point de passage : un porte-armures,
invisible, sans gravité, invulnérable, avec un nom personnalisé visible `WP #1`, `WP #2` et
ainsi de suite, à `x + 0.5`, `y`, `z + 0.5`.

Chacun porte trois tags de commande, ce qui les rend gérables :

| Tag | Valeur |
|---|---|
| `cheesepatrol` | sur tous les marqueurs créés par le mod |
| `npc:<uuid>` | l'UUID complet du NPC auquel ce marqueur appartient |
| `wp:<index>` | l'index du point de passage **compté depuis 0** — `wp:0` est le porte-armures étiqueté `WP #1` |

Tu peux donc viser les marqueurs d'une seule patrouille avec précision :

```mcfunction
/kill @e[type=armor_stand,tag=cheesepatrol,tag=npc:a1b2c3d4-e5f6-7890-abcd-ef1234567890]
```

Les marqueurs battent l'aperçu du trajet pour éditer un long circuit, parce qu'ils
persistent pendant que tu te déplaces et qu'un second administrateur à tes côtés les voit.
Ils sont moins bons pour vérifier les segments, parce qu'ils montrent les points et pas les
lignes entre eux. Utilise les deux.

### Ce à quoi faire attention

⚠️ **Les étiquettes sont visibles par les joueurs.** Il n'y a aucun filtre de permission :
un porte-armures invisible marqué `WP #3` flottant au-dessus d'une place de marché est vu de
tous ceux qui se connectent. Les marqueurs sont un outil d'édition, pas du décor.

⚠️ **Il n'existe aucun moyen de les éteindre avec la commande.** `/npcpatrol markers`
supprime puis repose ; il ne supprime jamais seulement. Ils disparaissent quand :

- `/npcpatrol clear` passe — mais seulement dans le monde où tu te tiens
- le serveur s'arrête proprement, ce qui retire tous les porte-armures tagués
  `cheesepatrol` qui se trouvent dans un chunk chargé, dans tous les mondes
- tu les tues toi-même par tag

Un crash les laisse donc en place, et effacer une patrouille depuis la mauvaise dimension
laisse ses marqueurs orphelins, sans NPC auquel les rattacher.

**Ils ne font pas obstacle.** Un porte-armures n'est pas poussable en vanilla et n'a pas de
collision de déplacement : un NPC — comme un joueur — traverse tout droit le marqueur posé
sur son point de passage. Les marqueurs ne peuvent pas dévier une patrouille de sa ligne.

**Poser des marqueurs balaie toutes les entités du monde.** `refreshMarkers` appelle d'abord
`removeMarkers`, qui parcourt `world.iterateEntities()` à la recherche des porte-armures
tagués. Lancer cela sur un monde chargé, c'est une itération complète des entités. Pas un
problème pour une commande d'administration lancée de temps en temps ; à savoir avant de la
scripter.

## Les effets de traînée

La traînée par NPC est le seul des trois systèmes qui fasse partie de l'état sauvegardé de la
patrouille, et le seul que les joueurs sont censés voir. Réglage avec
`/npcpatrol particles <type>`.

### Quand et où elle apparaît

Les particules de traînée sont émises depuis `tickPatrol`, ce qui signifie que **la
patrouille doit être active et son NPC chargé**. Une patrouille arrêtée n'a pas de traînée,
quelle que soit sa configuration. L'émission a lieu tous les 2 ticks serveur, dix fois par
seconde.

Le point d'ancrage est le milieu du corps du NPC, `y + height * 0.5`, décalé par les deux
réglages :

```text
x = npc.x - forward.x * particleZOffset
y = npc.y + height/2 + particleYOffset - forward.y * particleZOffset
z = npc.z - forward.z * particleZOffset
```

`forward` est le vecteur de vélocité normalisé quand le NPC bouge (vitesse > 0,01) et le yaw
du corps quand il est arrêté. Ce second cas compte : quand un NPC s'immobilise pour regarder
un joueur, son axe avant devient son orientation, donc une traînée réglée pour être derrière
lui pivote au fur et à mesure qu'il se tourne.

Comme `particleZOffset` est **soustrait** le long de l'axe avant, une valeur positive place
la traînée *derrière* le NPC et une valeur négative devant. La sortie de la commande le dit,
et le champ ne s'appelle `Z` que parce qu'il a commencé sa vie comme un décalage de
profondeur.

En mouvement, trois bouffées sont émises — à l'ancrage puis 0,5 et 1,0 bloc plus loin en
arrière le long de l'inverse de l'axe avant — ce qui est ce qui la fait lire comme une
traînée. À l'arrêt, une seule.

### Densité et dispersion, par effet

Quatre effets sont traités à part ; tous les autres utilisent les valeurs par défaut.

| Effet | Particules par bouffée | Dispersion (X/Z) | Dispersion (Y) | Vitesse des particules |
|---|---|---|---|---|
| `enchant` | 3 | 0,2 | 0,26 | 0,5 |
| `cherry` | 3 | 0,3 | 0,39 | 0,01 |
| `smoke` | 1 | 0,1 | 0,13 | 0,005 |
| `end_rod` | 1 | 0,15 | 0,195 | 0,01 |
| tous les autres | 2 | 0,15 | 0,195 | 0,01 |

La dispersion verticale vaut toujours 1,3× l'horizontale, ce qui étire la bouffée et masque
les positions discrètes. `enchant` reçoit une vitesse de particules élevée parce que les
glyphes d'enchantement sont censés converger ; les autres sont quasi statiques et restent où
ils apparaissent.

Noter le recouvrement : **`sparkle` et `end_rod` rendent la même particule `END_ROD`**, et
ne diffèrent que par la densité — `sparkle` émet 2 par bouffée, `end_rod` 1. Choisis
`sparkle` pour une traînée visible et `end_rod` pour une traînée discrète.

### Les 46 noms d'effets

```text
none  sparkle  flame  soul  enchant  end_rod  cherry  smoke
dust  dust_cyan  dust_purple  dust_white  dust_red  dust_green  dust_pink  dust_orange
heart  note  portal  snowflake  witch  glow  electric  scrape
wax  spore  ash  crimson_spore  warped_spore  mycelium
falling_nectar  falling_spore  dripping_honey  falling_honey
dripping_obsidian_tear  falling_obsidian_tear
cloud  crit  damage  dragon  firework
lava  rain  smoke_large  totem  trial
```

La complétion par tabulation compare les préfixes sur cette liste : `dust` + Tab te donne
donc les huit couleurs de poussière.

Les huit entrées `dust_*` sont la poussière colorée propre au mod, définie en RGB dans le
code source :

| Nom | RGB | Échelle | Rendu |
|---|---|---|---|
| `dust` | `1.00, 0.84, 0.00` | 1,2 | or |
| `dust_cyan` | `0.13, 0.83, 0.93` | 1,2 | cyan |
| `dust_purple` | `0.60, 0.20, 1.00` | 1,2 | violet |
| `dust_white` | `1.00, 1.00, 1.00` | 1,0 | blanc, un peu plus petit |
| `dust_red` | `1.00, 0.15, 0.15` | 1,2 | rouge |
| `dust_green` | `0.20, 1.00, 0.30` | 1,2 | vert |
| `dust_pink` | `1.00, 0.50, 0.80` | 1,2 | rose |
| `dust_orange` | `1.00, 0.55, 0.00` | 1,2 | orange |

Les poussières sont celles qui valent la peine pour une traînée : couleur unique et
constante, aucune animation, et aucun sens vanilla attaché. Le reste correspond directement
à des types de particules vanilla :

| Nom | Particule vanilla | Nom | Particule vanilla |
|---|---|---|---|
| `sparkle` | `END_ROD` | `wax` | `WAX_ON` |
| `flame` | `FLAME` | `spore` | `SPORE_BLOSSOM_AIR` |
| `soul` | `SOUL_FIRE_FLAME` | `ash` | `WHITE_ASH` |
| `enchant` | `ENCHANT` | `crimson_spore` | `CRIMSON_SPORE` |
| `end_rod` | `END_ROD` | `warped_spore` | `WARPED_SPORE` |
| `cherry` | `CHERRY_LEAVES` | `mycelium` | `MYCELIUM` |
| `smoke` | `CAMPFIRE_COSY_SMOKE` | `falling_nectar` | `FALLING_NECTAR` |
| `heart` | `HEART` | `falling_spore` | `FALLING_SPORE_BLOSSOM` |
| `note` | `NOTE` | `dripping_honey` | `DRIPPING_HONEY` |
| `portal` | `PORTAL` | `falling_honey` | `FALLING_HONEY` |
| `snowflake` | `SNOWFLAKE` | `dripping_obsidian_tear` | `DRIPPING_OBSIDIAN_TEAR` |
| `witch` | `WITCH` | `falling_obsidian_tear` | `FALLING_OBSIDIAN_TEAR` |
| `glow` | `GLOW` | `cloud` | `CLOUD` |
| `electric` | `ELECTRIC_SPARK` | `crit` | `CRIT` |
| `scrape` | `SCRAPE` | `damage` | `DAMAGE_INDICATOR` |
| `dragon` | `DRAGON_BREATH` | `firework` | `FIREWORK` |
| `lava` | `LAVA` | `rain` | `RAIN` |
| `smoke_large` | `LARGE_SMOKE` | `totem` | `TOTEM_OF_UNDYING` |
| `trial` | `TRIAL_SPAWNER_DETECTION` | | |

Trois d'entre eux sont à éviter sur un serveur public, parce que les joueurs les lisent
comme une information et non comme une décoration : `damage` donne l'impression que le NPC
prend des coups, `crit` celle d'un coup critique qui touche, et `totem` est l'explosion du
totem d'immortalité. `heart` se lit comme une reproduction ou un apprivoisement.

### La portée de visibilité

`/npcpatrol particlerange <16–512>` borne qui reçoit les paquets. Chaque position de
particule est envoyée joueur par joueur, à tous ceux qui sont dans cette distance sphérique
de l'ancrage, avec `force = true`.

Le coût est réel et il se multiplie : *(joueurs à portée) × (3 bouffées) × (particules par
bouffée) × (10 émissions par seconde)*. À la valeur par défaut de 16 blocs, cela fait une
poignée de paquets vers deux ou trois personnes. À 512 blocs sur un serveur de 40 joueurs,
chaque joueur de la carte reçoit la traînée de chaque NPC en patrouille, dix fois par
seconde.

Mets 16 pour un garde de village et ne monte que pour quelque chose de délibérément visible
de loin, comme un NPC volant qui tourne autour d'un monument.

La portée ne concerne **que la traînée**. L'aperçu de trajet de `show` va au seul joueur qui
l'a demandé, à n'importe quelle distance jusqu'à 512 blocs dans la dimension, et les marqueurs sont des
entités ordinaires soumises à la distance d'affichage des entités.

---

← [Circuits de patrouille](patrol-circuits.fr.md) · [Garder les NPC en vie](resilience.fr.md) →
