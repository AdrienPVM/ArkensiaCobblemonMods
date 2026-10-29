[← CobbleShift](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](transforms.md)

# Transformations

Une transformation change un joueur en Pokémon sans le remplacer par une entité. Le
joueur garde son propre corps, son propre inventaire et sa propre position sur le
serveur ; il est rendu invisible, reçoit quelques modificateurs d'attribut, et chaque
client reçoit l'ordre de dessiner un modèle de Pokémon à ses coordonnées à la place. Le
serveur ne fait jamais apparaître de Pokémon.

C'est ce choix qui rend le sous-système peu coûteux, et c'est lui aussi qui le rend
fragile. Peu coûteux, parce que rien n'a besoin d'être monté, synchronisé ou nettoyé.
Fragile, parce qu'un client vanilla reçoit l'invisibilité sans le modèle, et ne voit
rien du tout.

---

## Ce que gagne le joueur

Tout ce qui suit est appliqué par `TransformManager.transform()`, dans cet ordre.

| Effet | Source | Clé de config |
|---|---|---|
| Invisibilité, durée `Integer.MAX_VALUE`, ambiante, sans particules | toujours | — |
| modificateur `GENERIC_MOVEMENT_SPEED` `cobbleshift:transform_speed` | `mapSpeed(baseSpeed) − 0.1` | `transformSpeedEnabled` |
| modificateur `GENERIC_MAX_HEALTH` `cobbleshift:transform_health` | `mapMaxHealth(baseHP) − 20` | `transformHealthEnabled` |
| modificateur `GENERIC_SCALE` `cobbleshift:transform_scale` | `mapScale(actualHeight) − 1.0` | `transformScaleEnabled` |
| le modèle du Pokémon, sur chaque client | `TransformSyncPayload` | — |
| particules de barre de l'End et de villageois content, côté serveur | toujours | — |
| le cri de l'espèce, à moins de 32 blocs, dans la même dimension | client, à partir du payload | — |
| une attaque à projectile de type liée au clic gauche | `AttackInputMixin` | — |
| caméra forcée en troisième personne, zoom à la molette, pose de sommeil, raccourci de cri | mixins client | — |

Les trois modificateurs sont **persistants** : ils sont écrits dans les données
d'attribut sauvegardées du joueur. C'est voulu (c'est ainsi qu'une transformation survit
à une déconnexion), et c'est aussi pourquoi un crash les laisse derrière lui. Voir
[déconnexions, redémarrages et crashs](#déconnexions-redémarrages-et-crashs).

La santé est remontée au nouveau maximum juste après l'application du modificateur : une
transformation en espèce à forts PV équivaut à un soin complet. L'annulation ramène la
santé au maximum réel si le joueur le dépasse désormais, ce qui ferme l'exploit dans
l'autre sens.

## Ce que perd le joueur

| Perdu | Pourquoi |
|---|---|
| la vue à la première personne | le client force `THIRD_PERSON_BACK` à la transformation et le reforce à chaque tick, si bien que F5 ne fait rien |
| le réticule vanilla | la troisième personne le masque ; le client dessine sa propre croix à la place |
| l'item tenu et les bras à l'écran | `TransformArmMixin` annule `renderRightArm` et `renderLeftArm` |
| le nom au-dessus de la tête | `PlayerNametagMixin` annule `renderLabelIfPresent` pour les joueurs et PNJ transformés |
| les bruits de pas | `TransformStepSoundMixin` annule `playStepSound` |
| la variation de FOV au sprint | `TransformFovMixin` fige le multiplicateur de FOV |
| les attaques normales au clic gauche | `AttackInputMixin` intercepte `doAttack` et lance un projectile à la place |
| son propre skin, aux yeux de tous | le modèle est remplacé sur chaque client qui a le mod |

Rien ne change côté blocs, inventaire ou interactions. Un joueur transformé peut toujours
miner, poser des blocs, ouvrir des coffres et écrire dans le chat. La transformation, c'est
du cosmétique, plus trois attributs, plus une nouvelle attaque.

## Des statistiques de base aux valeurs Minecraft

Cinq formules dans `SpeciesMapper`, toutes linéaires et toutes basées sur les
statistiques de base de Cobblemon.

| Formule | Code | Plage |
|---|---|---|
| vitesse de déplacement | `0.1 + (baseSpeed − 80) / 800` | environ 0,006 (base 5) à 0,25 (base 200) |
| santé maximale | `10 + baseHP / 15` | environ 10 à 27 |
| échelle | `clamp(effectiveHitboxHeight / 1.8, 0.15, 2.5)` | 0,15 à 2,5 |
| dégâts d'attaque | `min(12, 2 + max(atk, spAtk) / 25)` | 2 à 12 |
| cooldown d'attaque | `max(25, 40 − speed / 12)` ticks | 25 à 40 |

On retranche 0,1 à `mapSpeed` avant d'en faire un modificateur parce que la base vanilla
est 0,1 et que le modificateur est en `ADD_VALUE` ; la même astuce s'applique à la santé
face à 20 et à l'échelle face à 1,0. Une espèce de vitesse de base 80 obtient donc un
modificateur d'exactement zéro et se déplace au rythme d'un joueur normal.

L'échelle est la seule qui demande un vrai travail. Le mod veut que la boîte de collision
du joueur corresponde à la hitbox grise F3+B qu'aurait un vrai Pokémon de cette espèce :
`resolveActualHeight` construit donc par réflexion un `PokemonEntity` jetable, lit
`getHeight()`, puis le jette sans jamais l'ajouter au monde. En cas d'échec, il se rabat
sur `hitbox.height × baseScale` lu sur l'objet espèce. Les deux chemins divisent ensuite
par 1,8, la hauteur d'un joueur vanilla. Le plafond à 2,5 rend Wailord et Steelix grands
sans être absurdes ; il explique aussi que le champ `mappedScale` du dashboard, qui
transmet à la place la taille du Pokédex en décimètres, affiche 2,5 pour presque tout.
C'est un bug de l'API, pas de la transformation.

Lance `/cshift species <name>` pour voir toutes les valeurs dérivées d'une espèce avant
de t'en servir.

## Attaques

Un clic gauche pendant la transformation envoie un `AttackAbilityPayload` au lieu de
donner un coup. Le client y joint un bit : son modèle fantôme a-t-il une animation
d'attaque prête.

`AttackHandler.handleAttack()` procède alors ainsi :

1. Sort sans rien dire si le joueur n'est pas transformé, si `attacksEnabled` est faux,
   ou si le cooldown propre à la transformation court encore.
2. Calcule les dégâts et le cooldown d'après l'espèce, modulés par
   `attackDamageMultiplier` et `attackCooldownMultiplier`. Le cooldown a un plancher de
   5 ticks, quel que soit le multiplicateur.
3. Pose le cooldown **au moment du clic**, avant qu'aucun projectile n'existe, pour que
   le délai d'animation ne modifie jamais les dégâts par seconde.
4. Avec une animation, met le tir en file pour 26 ticks (1,3 seconde) et lit la
   direction de visée au moment de l'apparition, ce qui laisse le joueur réajuster sa
   visée pendant la préparation. Sans animation, il tire tout de suite.

Le nombre de projectiles dépend du total des statistiques de base :

| BST | Tirs | Dégâts par tir |
|---|---|---|
| moins de 350 | 1 | complets |
| de 350 à 449 | 1 | ×1,3 |
| 450 et plus | 3, dans un cône de 15° | ×0,7 au centre, ×0,5 sur chaque flanc |

⚠️ `mapProjectileCount()` ne renvoie que 1 ou 3 et sert à l'affichage dans
`/cshift species` et dans l'API, tandis que `spawnProjectiles()` porte la logique à trois
paliers ci-dessus. Le palier renforcé 350-449 est donc invisible dans les deux
affichages : une espèce à 400 de BST affiche `1 projectile(s)` sans mentionner le
multiplicateur de 1,3.

`TypeProjectileEntity` est un `ThrownEntity` de gravité 0,01, lancé à la vitesse 1,5 : il
vole presque à plat. Il est enregistré sous `cobbleshift:type_projectile` et rendu comme
un item volant choisi selon le type : une boule de feu pour Feu, une plume pour Vol, une
pierre taillée pour Roche, et ainsi de suite. Il ignore son propriétaire, inflige ses
dégâts comme des dégâts de projectile lancé, applique un effet de statut propre au type,
et disparaît à la moindre collision.

| Type | Effet à l'impact |
|---|---|
| `fire` | 3 secondes en feu |
| `ice` | Lenteur II, 3 s |
| `ground` | Lenteur II, 3 s |
| `grass`, `water` | Lenteur I, 2 s |
| `poison` | Poison I, 5 s |
| `electric`, `fairy` | Surbrillance, 5 s / 3 s |
| `psychic` | Lévitation, 1,5 s |
| `flying` | Lévitation, 1 s |
| `ghost` | Cécité, 3 s |
| `dark` | Obscurité, 3 s |
| `dragon` | Faiblesse, 5 s |
| `fighting` | Faiblesse, 3 s |
| `rock` | Fatigue de minage, 3 s |
| `steel` | Fatigue de minage, 2 s |
| `bug` | Faim, 4 s |
| `normal` | aucun |

Seul le type **primaire** est utilisé, à la fois pour le projectile et pour son effet.
La moitié Vol d'un Dracaufeu n'apparaît jamais dans ses attaques.

On désactive les attaques d'une transformation avec le mot `noattacks` sur
`/cshift transform`. Impossible de les réactiver sur une transformation en cours, à part
en transformant à nouveau.

## Ce que le joueur peut faire une fois transformé

Deux raccourcis clavier client, tous deux **non assignés par défaut** : ils apparaissent
dans l'écran Commandes, sous la catégorie `CobbleShift`, et ne font rien tant que le
joueur ne leur attribue pas de touche.

| Raccourci | Clé de traduction | Effet |
|---|---|---|
| Dormir / Se réveiller | `key.cobbleshift.sleep` | met le modèle fantôme dans son animation de sommeil ; affiche `Zzz...` dans la barre d'action |
| Jouer le cri | `key.cobbleshift.cry` | joue le cri de l'espèce |

La molette de la souris règle la distance de la caméra via `TransformScrollMixin`. Au
zoom 0, le modèle du joueur local est masqué : c'est ce qui se rapproche le plus d'une
vue à la première personne.

Un joueur ne peut pas annuler sa propre transformation. `TransformData` porte un drapeau
`locked`, avec un accesseur, un mutateur et un champ JSON, et **rien dans le mod ne le
positionne jamais** : il n'existe pas d'auto-annulation à verrouiller. Ce champ est un
poids mort, reste d'une conception qui n'a pas été construite.

## Durée, cooldown, et ce qui met fin à une transformation

| Met fin à une transformation | Condition |
|---|---|
| `/cshift revert <player>` | à tout moment |
| `/cshift revertall` | à tout moment |
| `POST /api/v1/shift/revert` ou `/revertall` | rôle OPERATOR |
| l'effet `TRANSFORM_REVERT_ALL` de CheeseGuard | pendant un événement |
| fin de session CheeseKart | si le pilote a été transformé par le kart |
| expiration de la durée maximale | `transformMaxDurationEnabled` est actif et `transformMaxDurationSeconds` s'est écoulé |
| arrêt du serveur | `SERVER_STOPPING` appelle `revertAll()` |
| une nouvelle transformation | l'ancienne est d'abord annulée, sans le son d'annulation |

La durée maximale est **désactivée** par défaut. Quand elle est active,
`TransformManager.tick()` compare à chaque tick serveur le temps écoulé à
`transformMaxDurationSeconds × 1000` et annule la transformation du joueur avec
`⏰ Durée max de transformation atteinte !`. La vérification porte sur les millisecondes
de l'horloge murale, pas sur les ticks : un serveur qui rame fait quand même expirer les
transformations à l'heure.

Le cooldown est lui aussi désactivé par défaut. Quand il est actif, un joueur dont la
transformation a été annulée ne peut pas être transformé à nouveau pendant
`transformCooldownSeconds`. Deux choses à son sujet passent facilement inaperçues :

- Il se mesure à partir de l'**annulation**, pas de la transformation, et il est stocké
  par UUID en mémoire uniquement.
- Changer d'espèce en étant déjà transformé le contourne complètement : la vérification
  est sautée quand le joueur a une transformation active. Le cooldown limite donc la
  fréquence à laquelle un joueur peut *revenir* sous forme de Pokémon, pas la fréquence
  à laquelle il peut changer d'espèce.

Quand le cooldown bloque une transformation, le message de refus va au **joueur ciblé**,
dans sa barre d'action. L'administrateur qui a tapé la commande reçoit l'erreur générique
`Échec : espèce '<name>' introuvable ou Cobblemon non disponible`, et un appelant de l'API
le `400` équivalent.

⚠️ `autoRevertOnDeath` vaut `true` par défaut et **ne fait rien**.
`TransformManager.onPlayerDeath()` l'implémente correctement, mais aucun appelant
n'existe nulle part dans le mod : pas de mixin de mort, pas d'enregistrement de
`ServerLivingEntityEvents.AFTER_DEATH`, rien. Un joueur transformé qui meurt reste
transformé après sa réapparition. Pour le brancher, il faut enregistrer cet événement
dans `CobbleShiftMod.onInitialize()`.

## Déconnexions, redémarrages et crashs

Une déconnexion est le cas facile. `onPlayerDisconnect` ne fait volontairement rien :
l'entrée reste dans `activeTransforms`, et `onPlayerJoin` réapplique l'Invisibilité puis
rediffuse chaque transformation active à chaque client, avec `silent=true` pour que
personne n'entende de cri à la reconnexion. Les modificateurs d'attribut étaient
persistants et n'ont jamais été retirés : ils sont déjà en place. Un joueur qui se
reconnecte est toujours transformé.

Un redémarrage propre est géré lui aussi : `SERVER_STOPPING` annule toutes les
transformations, ce qui retire les modificateurs avant leur sauvegarde.

Le **crash** est le cas problématique. `activeTransforms` n'existe qu'en mémoire et n'est
jamais écrit sur le disque : le serveur revient sans aucune trace de qui était transformé,
alors que les trois modificateurs persistants et l'Invisibilité infinie sont dans les
fichiers des joueurs. Le joueur se connecterait invisible, à la mauvaise échelle, et sans
modèle.

`cleanupStaleTransform()` intercepte précisément ce cas. À toute connexion sans
transformation active, il retire tout modificateur `cobbleshift:transform_*` qu'il trouve,
et retire l'Invisibilité **seulement si cette instance est ambiante et sans particules**,
ce qui est la signature propre au mod. Une invisibilité donnée par une balise ou une
potion ne correspond pas assez à cette signature pour poser un vrai problème en pratique,
même si un effet ambiant accordé par une balise est le seul cas où le test pourrait se
tromper. La méthode journalise `Cleaned up stale transform modifiers for <name>` quand
elle agit : c'est la ligne à chercher après un crash.

La conséquence pratique : un crash met fin en silence à toutes les transformations. Les
transformations de joueur ne sont pas persistantes, par conception. Celles des PNJ le
sont, et cette différence est toute la raison d'être des
[transformations de PNJ](npc-transforms.fr.md).

## Qui d'autre transforme les joueurs

Trois appelants en dehors de l'arbre de commandes, tous documentés dans
[l'architecture](architecture.fr.md#lapi-externe-de-fait) :

- **CheeseDash** : `POST /api/v1/shift/transform`, espèce seule, sans chromatique ni
  forme.
- **CheeseGuard** : les effets d'événement `TRANSFORM_ALL`, `TRANSFORM_RANDOM`,
  `TRANSFORM_BY_TEAM` et `TRANSFORM_REVERT_ALL`.
- **CheeseKart** : transforme un pilote au début de sa session et annule sa
  transformation à la fin.

Ces deux derniers passent par réflexion sur `getTransformManager().transform(player, species)`
et `revert(player)`. Ces deux signatures sont une API publique, qu'elles aient été
pensées comme telle ou non.

---

← [Configuration](configuration.fr.md) · [Capacités passives](abilities.fr.md) →
