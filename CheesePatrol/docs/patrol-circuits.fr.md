[← CheesePatrol](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](patrol-circuits.md)

# Circuits de patrouille

Tout ce que décrit cette page se passe dans une seule méthode,
`PatrolManager.tickPatrol`, qui tourne une fois par tick serveur pour chaque patrouille
**active et comptant au moins deux points de passage**. Une patrouille qui rate l'une des
deux conditions est entièrement sautée : pas de mouvement, pas de particules de traînée,
pas de détection de blocage, rien.

Lis cette page si tu conçois un trajet, ou si un NPC fait quelque chose que tu ne lui as pas
demandé.

---

## Le modèle

Une patrouille, c'est trois choses : un UUID de NPC, une liste ordonnée de positions de
bloc, et un index dans cette liste.

```text
PatrolData
├── npcUuid                 l'entité pilotée. Aussi la seule identité de la patrouille
├── waypoints[]             positions de bloc ordonnées
├── waypointWaitTicks[]     liste parallèle, une pause par point de passage
├── currentWaypointIndex    le point de passage VERS lequel on marche
├── speed, playerDetectionRadius
└── drapeaux transitoires   waiting, lookingAtPlayer, missingTicks, stuckTicks, stallTicks
```

Les attentes forment une **liste parallèle**, pas un champ d'un objet point de passage.
`add` et `remove` maintiennent les deux listes en phase ; rien d'autre n'y touche. Le seul
cas où elles pourraient se désynchroniser serait un fichier édité à la main, et le
chargeur reconstruit les deux depuis le même tableau JSON : c'est donc impossible.

`currentWaypointIndex` est le point de passage vers lequel le NPC se dirige, pas celui
qu'il vient de quitter. `/npcpatrol info` le marque `<-- current`, ce qui se lit comme « le
prochain arrêt ».

Il n'y a aucune notion de trajet, de chemin ou d'horaire. Il y a une liste et un curseur.

## L'ordre de parcours, et le dernier point de passage

Les points de passage sont visités dans l'ordre de la liste, et `advanceToNextWaypoint`
s'écrit :

```java
currentWaypointIndex = (currentWaypointIndex + 1) % waypoints.size();
```

Le circuit est donc une **boucle fermée**. Après le dernier point de passage, le NPC repart
vers le premier et continue. Il n'y a pas de fin, pas de « patrouille terminée », pas de
demi-tour, pas de mode va-et-vient et aucun moyen d'en configurer un. Le segment du dernier
point vers le premier est un vrai segment que le NPC parcourra, et `/npcpatrol show` le
dessine — c'est la façon la plus simple de remarquer qu'il coupe en diagonale à travers un
bâtiment.

Deux conséquences pour la conception d'un trajet :

- **Deux points de passage font un segment, pas une boucle.** Le NPC fait A → B → A → B.
  C'est le minimum avec lequel une patrouille démarre, et c'est une animation d'inactivité
  tout à fait convenable pour un marchand.
- **Ferme ta boucle toi-même.** Si tu veux que le NPC fasse le tour d'un carré, pose quatre
  points de passage aux coins, pas cinq avec un doublon. Le cinquième ferait arriver le NPC
  au premier coin, le compterait immédiatement comme arrivé au doublon, et le sauterait.

Le réordonnancement n'est pas géré. `add` ajoute à la fin. Pour corriger le milieu d'un
circuit, supprime tous les points de passage après l'erreur et réajoute-les, en te
souvenant que `remove` renumérote.

## L'arrivée, et ce que « assez près » veut dire

À chaque tick, la distance au carré entre le NPC et le centre du point de passage cible
(`x + 0.5`, `y`, `z + 0.5`) est comparée à un seuil :

| Type de segment | Seuil | En blocs |
|---|---|---|
| Au sol | `2.25` | 1,5 |
| En vol | `25.0` | 5,0 |

Sous le seuil, le NPC compte comme arrivé. Le seuil en vol est délibérément large : tenir
une position exacte par écrasement de vélocité ne marche pas, donc un NPC volant qui arrive
à cinq blocs est considéré comme ayant réussi.

À l'arrivée, l'une de deux choses se produit :

- le point de passage a une attente > 0 → la navigation s'arrête, la vélocité est mise à
  zéro, et l'attente commence
- le point de passage n'a pas d'attente → l'index avance et la navigation est poussée vers
  le point suivant dans le même tick, sans aucune pause

Le second cas est ce qui rend un virage fluide fluide. Le NPC ne s'arrête jamais ; il
se met à viser ailleurs 1,5 bloc avant le coin, ce qui arrondit le virage.

## L'attente sur un point de passage

Une attente est stockée par point de passage, en ticks, et réglée en secondes par
`/npcpatrol wait <index> <seconds>` ou l'argument final de `/npcpatrol add`. Pendant
l'attente :

```text
remainingWaitTicks -= 1   à chaque tick
```

Quand il atteint zéro, le drapeau s'efface, l'index avance, et la navigation est poussée
vers le point suivant.

Deux choses surprennent dans ce décompte :

1. **Il ne s'écoule pas quand un joueur est proche.** Le contrôle « regarder un joueur » a
   lieu avant le traitement de l'attente et rend la main : le décrément est donc
   entièrement sauté. Une attente de 5 secondes avec un joueur planté à côté du NPC dure
   aussi longtemps que le joueur, puis prend ses cinq secondes dès qu'il s'éloigne.
2. **Elle ne survit à rien.** `waitingAtWaypoint` et `remainingWaitTicks` sont des champs
   transitoires, non sauvegardés sur disque. Après un redémarrage en pleine attente, la
   boucle par tick trouve le NPC déjà sur le point de passage et recommence l'attente depuis
   le début — mais cinq secondes après le démarrage, le kick-start de démarrage efface le
   drapeau d'attente et, puisque le NPC est toujours à moins de 1,5 bloc, le fait avancer.
   Une longue attente interrompue par un redémarrage est donc ramenée à environ cinq
   secondes.

Une attente de `3600` secondes est acceptée : c'est la façon prévue de garer un NPC
quelque part une heure durant. Un fichier de données édité à la main peut dépasser cela,
parce que le chargeur ne revalide pas le plafond.

## L'arrêt pour regarder un joueur

Le comportement pour lequel ce mod existe, et le morceau de code le plus simple qu'il
contienne :

```java
var nearestPlayer = mob.getWorld().getClosestPlayer(mob, patrol.getPlayerDetectionRadius());
if (nearestPlayer != null) {
    mob.getNavigation().stop();
    mob.setVelocity(Vec3d.ZERO);
    mob.getLookControl().lookAt(nearestPlayer, 30.0f, 30.0f);
    patrol.setLookingAtPlayer(true);
    return;
}
```

| Question | Réponse |
|---|---|
| **Rayon ?** | `playerDetectionRadius`, défaut `5.0`, réglable de `1.0` à `32.0` par patrouille |
| **Forme ?** | Une sphère. Un joueur 10 blocs au-dessus est à 10 blocs et ne déclenche pas un rayon de 5 |
| **Durée ?** | Aucune. Cela dure exactement le temps qu'un joueur reste dans le rayon |
| **Ligne de vue ?** | Non vérifiée. Un NPC se tourne vers un joueur à travers un mur, un plancher ou une porte fermée |
| **Quel joueur ?** | Le plus proche. Les spectateurs sont exclus par le filtre vanilla lui-même |
| **Priorité ?** | Absolue. Le contrôle passe avant l'attente et avant le mouvement, et rend la main |
| **Vitesse de rotation ?** | `lookAt(player, 30.0f, 30.0f)` — jusqu'à 30° de yaw et 30° de pitch par tick, donc la tête pivote en quelques ticks plutôt qu'instantanément |

Ce `return` constitue tout le système de priorité. Tant qu'un joueur est à portée, le NPC
ne bouge pas, n'avance pas son point de passage, ne décompte pas d'attente, et n'accumule
ni ticks de blocage ni ticks de calage. La patrouille est gelée, braquée sur le joueur.

L'absence de vérification de la ligne de vue est la limite que tu remarqueras en premier.
Un NPC à l'intérieur d'un mur de boutique de deux blocs s'arrête chaque fois qu'un client
passe dehors. Le remède est un rayon plus petit, ou un trajet qui éloigne le NPC des murs
que les joueurs longent.

## La vitesse n'est pas en blocs par seconde

`speed` alimente deux systèmes différents à deux échelles différentes, d'où l'impression
que le nombre est arbitraire.

**Pour l'écrasement de vélocité par tick**, qui est ce que tu vois réellement :

| | Multiplicateur | À `speed 0.5` | À `speed 1.0` |
|---|---|---|---|
| Au sol | `speed × 0.12` | 0,06 bloc/tick ≈ **1,2 bloc/s** | ≈ 2,4 blocs/s |
| En vol | `speed × 0.15` | 0,075 bloc/tick ≈ **1,5 bloc/s** | ≈ 3,0 blocs/s |

Un joueur vanilla marche à environ 4,3 blocs par seconde : `speed 0.5` est donc une vraie
flânerie et `speed 2.0`, à environ 4,8 blocs par seconde, dépasse déjà un joueur qui
marche.

**Pour la navigation vanilla**, `moveToCurrentWaypoint` passe le même nombre directement à
`mob.getNavigation().startMovingTo(x, y, z, speed)`, où il devient un multiplicateur sur
l'attribut de vitesse de déplacement de l'entité — une échelle complètement différente, sur
laquelle `1.0` signifie « la vitesse normale du mob ».

Les deux sont en jeu sur un segment au sol : la navigation est lancée au changement de
point de passage, puis l'écrasement de vélocité est appliqué à chaque tick. À l'observation
des NPC, c'est l'écrasement qui détermine la vitesse apparente. Que le même champ pilote les
deux échelles est une verrue, pas un choix de conception.

## Le déplacement au sol

À chaque tick, sur un segment au sol, si la distance horizontale au point de passage
dépasse 0,3 bloc :

- la vélocité horizontale est fixée au vecteur unitaire vers le point de passage multiplié
  par `speed × 0.12`
- **la vélocité verticale est laissée intacte**, donc la gravité, les dalles et les
  escaliers fonctionnent toujours
- `velocityModified` est levé, pour que le changement soit envoyé aux clients
- yaw, yaw de tête et yaw de corps sont tous interpolés de 15 % vers la direction cible à
  chaque tick, ce qui fait ressembler le virage à un virage plutôt qu'à un claquement

Noter ce qui manque : pas de saut, pas de chemin, aucun test d'obstacle. Le NPC marche dans
ce qui se trouve devant lui. Une marche d'un bloc n'est franchie que si la hauteur de pas
propre au mob le permet ; une barrière est un mur. C'est le point le plus important à
anticiper dans la conception.

À chaque tick, le mod appelle aussi `mob.setPersistent()` et met `fallDistance = 0`. Le
premier empêche le despawn vanilla ; le second signifie qu'un NPC de patrouille ne subit
jamais de dégâts de chute, ce qui compte à cause de la section suivante.

## Les segments en vol

Un point de passage est un point de passage *en vol* s'il n'y a aucun bloc solide à sa
position ni dans les trois blocs en dessous :

```java
for (int dy = 0; dy >= -3; dy--) {
    if (world.getBlockState(target.add(0, dy, 0)).isSolidBlock(...)) return false;
}
return true;   // rien de solide dans les 3 blocs du dessous — on vole
```

Ceci est évalué **par point de passage cible, à chaque tick**, et non par segment. Les
conséquences :

- Dès que le NPC vise un point de passage en vol, `NoGravity` s'active et le NPC quitte le
  sol sur lequel il se tenait. Il ne marche pas jusqu'au bord d'abord.
- Dès qu'il vise à nouveau un point au sol, `NoGravity` se coupe et il tombe. Le
  `fallDistance = 0` appliqué à chaque tick est ce qui l'empêche de mourir.
- Un point de passage placé un bloc au-dessus d'un plancher est un point **au sol**, parce
  que le plancher est dans les trois blocs. Il faut être réellement au-dessus du vide, ou
  quatre blocs plus haut, pour obtenir du vol.

Le déplacement en vol est de la pure vélocité, avec une subtilité. À moins de 8 blocs de la
cible, la direction se mélange à celle du point de passage *suivant* selon une courbe
cubique, jusqu'à 70 % :

```java
double t = 1.0 - (dist / 8.0);
double blend = t * t * t * 0.7;
```

Cela produit un arc à travers le point de passage au lieu d'un arrêt-demi-tour, et c'est
pourquoi un circuit en vol se lit comme un oiseau qui tourne plutôt qu'un drone qui pointe
des repères. Le yaw est interpolé à 8 % par tick, plus doux que les 15 % au sol, pour la
même raison.

Il n'y a aucun contrôle du pitch et aucune gestion des collisions en vol. Un NPC volant
rentrera dans une falaise et y restera jusqu'à ce que le chien de garde anti-blocage le
téléporte.

## Quand un NPC se bloque

Deux chiens de garde indépendants, avec des déclencheurs et des remèdes différents. Les
deux sont sautés pendant que le NPC attend sur un point de passage ou regarde un joueur —
ces drapeaux étant lus depuis le tick précédent, il existe une fenêtre d'un tick où un
compteur peut s'incrémenter à l'arrivée d'un joueur. Sans conséquence.

### Le chien de garde de calage : 5 secondes, renavigation

À chaque tick, si la vélocité horizontale au carré est sous `0.001` (environ 0,03
bloc/tick), un compteur tourne. À **100 ticks — 5 secondes — de vélocité quasi nulle** :

1. effacer le goal selector d'EasyNPC
2. couper la navigation
3. si le NPC est déjà à moins de 1,5 bloc de son point de passage, passer au suivant
4. repousser la navigation vers le point de passage

C'est le remède doux, et celui qui se déclenche le plus souvent. Il rattrape la panne
courante où les goals d'inactivité propres à EasyNPC reprennent le dessus et épinglent le
NPC sur place, ainsi que le cas où le NPC est arrivé sur un point de passage d'une façon que
le test d'arrivée a manquée.

Le commentaire de code au-dessus de ce bloc annonce « 2s » ; la constante vaut 100 ticks,
soit 5 secondes. Fais confiance à la constante.

### Le chien de garde de blocage : 60 secondes, téléportation

À chaque tick, si le NPC est à plus de 2 blocs de son point de passage cible, un second
compteur tourne. À **1200 ticks — 60 secondes — à plus de 2 blocs** :

```java
mob.refreshPositionAndAngles(target.x + 0.5, target.y, target.z + 0.5, yaw, pitch);
```

Le NPC est téléporté sur le point de passage et la patrouille est relancée. Repasser sous
2 blocs à n'importe quel moment remet le compteur à zéro.

⚠️ **Ce n'est pas un contrôle de progression, malgré le commentaire qui le présente comme
tel.** Il mesure une distance, pas un mouvement. Un NPC qui marche impeccablement le long
d'une longue ligne droite accumule des ticks de blocage sur tout le trajet, et se fait
téléporter si la marche dure plus d'une minute. À la vitesse par défaut `0.5`, cela fait
environ **70 blocs** :

| Vitesse | Blocs parcourus en 1200 ticks |
|---|---|
| `0.5` | ≈ 72 |
| `1.0` | ≈ 144 |
| `0.25` | ≈ 36 |

La règle pratique est donc : **garde des segments nettement sous 70 blocs à la vitesse par
défaut**, ou monte la vitesse, ou coupe la longue branche avec un point de passage
intermédiaire. Un trajet qui téléporte le NPC à chaque tour ressemble exactement à un
trajet qui fonctionne, jusqu'à ce que quelqu'un soit là pour le voir.

## Où se prennent les décisions, dans l'ordre

Pour référence quand tu lis le code ou que tu débogues une patrouille qui fait deux choses
à la fois :

```text
1.  trouver l'entité              absente → force-load, compteur, échelle de réapparition, FIN
2.  pas un PathAwareEntity ?      FIN — définitivement, pour cette patrouille
3.  absente puis retrouvée ?      la sauvegarder et relancer
4.  setPersistent, fallDistance=0
5.  tous les 100 ticks serveur    effacer le goal selector d'EasyNPC
6.  noter la dernière position connue
7.  chien de garde de blocage     peut téléporter, puis relancer
8.  mettre à jour les chunks forcés   chunk du NPC + chunk du point de passage cible
9.  tous les 2 ticks serveur      émettre les particules de traînée
10. chien de garde de calage      peut renaviguer
11. joueur dans le rayon ?        s'arrêter, le regarder, FIN
12. en attente sur un point ?     décompter, peut-être avancer, FIN
13. en vol ou au sol ?            régler `NoGravity` en conséquence
14. sous le seuil d'arrivée ?     démarrer une attente, ou avancer, FIN
15. se déplacer                   écrasement de vélocité + interpolation du yaw
```

L'étape 2 mérite l'attention : si l'UUID appartient à quelque chose qui n'est pas un
`PathAwareEntity` — ce que `/npcpatrol select` te laissera volontiers faire — chaque tick
atteint l'étape 2 et renonce, à jamais, sans ligne de log ni erreur. La patrouille affiche
`ACTIVE` et rien ne bouge.

---

← [Configuration](configuration.fr.md) · [Rendu visuel](visuals.fr.md) →
