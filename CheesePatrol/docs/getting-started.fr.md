[← CheesePatrol](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](getting-started.md)

# Premiers pas

À la fin de cette page, un EasyNPC parcourra un circuit fermé de quatre points autour
d'une place de village, s'arrêtera cinq secondes à un coin, laissera une traînée de
poussière cyan, et s'immobilisera pour se tourner vers toi dès que tu passeras à moins de
six blocs. Compte une dizaine de minutes, et chaque étape dit ce que tu dois voir avant de
passer à la suivante.

Il te faut un serveur avec CheesePatrol, CheeseDash et EasyNPC installés (voir
[installation](installation.fr.md)), et le niveau de permission 2 — opérateur sur un
serveur vanilla. Toutes les sous-commandes `/npcpatrol` sont soumises à ce niveau.

---

## 1. Poser un NPC dans le monde

CheesePatrol ne crée pas de NPC. Il pilote un NPC qui existe déjà : fais-en apparaître un
avec les outils propres d'EasyNPC, à l'endroit où tu veux que le circuit commence.

Choisis ensuite ton terrain. La seule chose qui décide si tout cela marchera, c'est la
**ligne de vue entre deux points de passage consécutifs**. Le déplacement est un
écrasement de vélocité, pas du pathfinding : le NPC va droit sur son point de passage
suivant et ne contourne ni barrière, ni pot de fleurs, ni marche d'un bloc. Une place
plate et dégagée est le bon endroit pour un premier circuit ; un escalier, non.

Tu dois voir : un NPC immobile, occupé à ce que sa configuration EasyNPC lui fait faire.

## 2. Récupérer la baguette

```mcfunction
/npcpatrol wand
```

```text
[CheesePatrol] Patrol Wand given. Right-click an EasyNPC to select it, then use /npcpatrol add.
```

Une **Patrol Wand** dorée et scintillante tombe dans ton inventaire. C'est une tige de
Blaze portant un tag de données personnalisées `cheesepatrol_wand`, et ce tag est tout
l'intérêt de l'objet : la baguette n'est pas un indice, c'est là que ta sélection de NPC
est stockée. Garde-la dans ta main principale pour tout le reste de ce guide. Si tu la
laisses tomber, la passes en main secondaire ou la ranges dans un coffre, toutes les
sous-commandes ci-dessous répondront `No NPC selected`.

## 3. Sélectionner le NPC

Baguette en main, fais un clic droit sur le NPC.

```text
[CheesePatrol] Selected: Village Guard (a1b2c3d4...)
```

Ce message arrive dans le chat. L'UUID du NPC est désormais écrit dans la baguette, une
fiche de patrouille est créée pour lui, et la dimension de la patrouille est fixée sur le
monde où tu te trouves.

Si tu reçois à la place un message rouge sur ta barre d'action :

```text
[CheesePatrol] Not a valid NPC (not PathAwareEntity).
```

l'entité cliquée n'hérite pas de `PathAwareEntity` et n'a donc aucune navigation à
piloter. Porte-armures, cadres, bateaux et tout ce qui n'est pas un mob échouent ici. Les
entités EasyNPC passent.

Le clic droit sert aussi de vérification d'état par la suite : clique sur un NPC qui a
déjà un circuit et le message gagne un suffixe, `[6 waypoints, inactive]` ou un
`[PATROL ACTIVE — /npcpatrol clear before deleting!]` en rouge.

## 4. Tracer le circuit

Il y a deux façons de poser un point de passage, et tu voudras les deux.

**À la baguette**, clic droit sur le bloc sur lequel tu veux que le NPC se tienne. Le point
de passage est enregistré **un bloc au-dessus** du bloc cliqué : clique le sol, pas l'air
au-dessus.

```text
[CheesePatrol] Waypoint #1 added at 112 65 -340
```

Celui-ci s'affiche sur la barre d'action, pas dans le chat. Marche jusqu'à chaque coin de
la place et clique le sol. Tant que tu tiens la baguette, le clic droit est consommé par
CheesePatrol : tu n'ouvriras donc pas une porte ni ne basculeras un levier par accident.

**À la commande**, debout là où tu veux le point de passage :

```mcfunction
/npcpatrol add
```

```text
[CheesePatrol] Added waypoint #2 at 128 65 -340
```

Celle-ci utilise le bloc qu'occupent tes pieds, soit, pour un joueur debout sur le sol, le
bloc d'air dans lequel il se tient — exactement là où vont les pieds d'un NPC. Il existe
aussi `/npcpatrol add <x> <y> <z>` si tu préfères taper des coordonnées, et les deux formes
acceptent une attente finale en secondes.

Pose **quatre** points de passage, un par coin. Deux est le minimum avec lequel une
patrouille démarre ; trois ou plus font lire la boucle comme un trajet plutôt que comme
un va-et-vient.

## 5. Vérifier ce que tu as construit

```mcfunction
/npcpatrol info
```

```text
=== Patrol Info ===
NPC: a1b2c3d4-e5f6-7890-abcd-ef1234567890
Dim: minecraft:overworld
Status: INACTIVE
Speed: 0.5
Radius: 5.0
Waypoints (4):
  #1: 112 65 -340 <-- current
  #2: 128 65 -340
  #3: 128 65 -324
  #4: 112 65 -324
```

Lis la liste comme l'ordre de parcours. Le NPC fait #1 → #2 → #3 → #4 puis **revient au
#1** : le circuit est une boucle fermée, pas un aller-retour. Si tes numéros zigzaguent en
travers de la place au lieu d'en faire le tour, l'ordre est faux ;
`/npcpatrol remove <index>` puis réajoute dans la bonne séquence. Supprimer un point de
passage renumérote tous ceux qui le suivent.

## 6. Voir le trajet avant de le lancer

```mcfunction
/npcpatrol show
```

```text
[CheesePatrol] Particles ON. Run again to hide.
```

Le circuit entier est dessiné pour toi seul, rafraîchi toutes les demi-secondes : un nuage
vert sur le point de passage #1, un nuage rouge sur le dernier, bleu sur ceux du milieu, et
une ligne dorée le long de chaque segment — y compris le segment de fermeture, du dernier
point vers le premier, celui dont on oublie l'existence.

Suis chaque ligne du regard. Si l'une traverse un bâtiment, ce segment ne fonctionnera pas.
Corrige maintenant, avant que le NPC passe une minute à marcher dans un mur.

Relance la commande pour éteindre l'affichage.

## 7. Régler la marche

Trois réglages, chacun sur le NPC sélectionné :

```mcfunction
/npcpatrol speed 1.0
```

```text
[CheesePatrol] Speed set to 1.0
```

`0.5` est la valeur par défaut, soit une flânerie. `1.0` est une marche soutenue. La valeur
est bornée à `0.1`–`5.0` et n'est pas une vitesse en blocs par seconde — voir
[comment la vitesse devient une vélocité](patrol-circuits.fr.md#la-vitesse-nest-pas-en-blocs-par-seconde).

```mcfunction
/npcpatrol radius 6
```

```text
[CheesePatrol] Player detection radius set to 6.0
```

Six blocs, pour que le NPC te remarque quand tu passes à côté et non seulement quand tu le
bousculeras.

```mcfunction
/npcpatrol wait 3 5
```

```text
[CheesePatrol] Waypoint #3 wait set to 5s.
```

Le NPC s'arrête maintenant cinq secondes au coin #3 à chaque tour. L'index part de 1 et
correspond à ce qu'affiche `/npcpatrol info`. `0` seconde rétablit un virage sans pause.

## 8. Démarrer

```mcfunction
/npcpatrol start
```

```text
[CheesePatrol] Patrol started at waypoint #1/4 (NPC kick-started)
```

`(NPC kick-started)` est la partie importante. Elle signifie que le mod a trouvé l'entité,
effacé ses goals d'IA EasyNPC, coupé la navigation en cours et l'a poussée immédiatement
vers le point de passage #1. Si tu lis `(NPC not found — will start when loaded)` à la
place, la patrouille est marquée active mais rien ne bouge : le NPC est dans un chunk non
chargé ou dans une autre dimension que celle enregistrée par la patrouille. Voir
[dépannage](troubleshooting.fr.md#start-annonce-que-le-npc-est-introuvable).

Tu dois voir : le NPC se tourner et partir vers le premier coin en un tick ou deux.

Si le NPC se tenait déjà sur le coin #1 — à moins de 1,5 bloc de son centre — le
kick-start le saute et le message devient `Patrol started at waypoint #2/4` : `start` ne
commence jamais par attendre sur le point où se tient le NPC.

## 9. L'observer à l'œuvre

Recule et laisse-le faire un tour complet. Trois comportements à guetter :

- **Il prend les virages.** Sur un point de passage sans attente, il change de cible sans
  s'arrêter. Au coin #3 il s'immobilise cinq secondes, puis repart.
- **Il ferme la boucle.** Après le #4 il repart vers le #1 au lieu de faire demi-tour.
- **Il te remarque.** Approche-toi à moins de six blocs. Il doit s'arrêter en pleine
  enjambée et tourner tête et corps vers toi, et rester immobile jusqu'à ce que tu
  ressortes du rayon. Il n'y a ni minuteur sur ce comportement, ni vérification de la ligne
  de vue : tant que tu es dans le rayon, la patrouille est en pause, mur ou pas mur. Les
  minuteurs d'attente ne s'écoulent pas non plus pendant qu'il te regarde.

## 10. Lui donner une traînée

```mcfunction
/npcpatrol particles dust_cyan
```

```text
[CheesePatrol] Particle trail set to: dust_cyan
```

De la poussière cyan suit maintenant le NPC quand il bouge, trois bouffées derrière lui, et
retombe à une seule bouffée à l'arrêt. La complétion par tabulation liste les 46 effets. Si
elle se place à la mauvaise hauteur ou traverse le modèle, décale-la :

```mcfunction
/npcpatrol particleheight 0.4
/npcpatrol particledepth 0.6
```

Une profondeur positive place la traînée derrière le NPC, négative devant. Référence
complète dans [rendu visuel](visuals.fr.md).

## 11. Facultatif : planter des marqueurs durables

```mcfunction
/npcpatrol markers
```

```text
[CheesePatrol] Armor stand markers placed at 4 waypoint(s). Run again to refresh.
```

Un porte-armures invisible et invulnérable étiqueté `WP #1` … `WP #4` flotte maintenant sur
chaque point de passage, visible de tous, ce qui rend l'édition d'un long trajet bien plus
simple que de relancer `show` à chaque fois. Relance la commande après avoir modifié le
circuit pour les reconstruire. Ils sont nettoyés automatiquement à l'arrêt du serveur, et
par `/npcpatrol clear`.

⚠️ Les marqueurs sont des entités permanentes dont l'étiquette est visible par les joueurs.
Démonte-les avant que quelqu'un d'autre se connecte : il n'existe pas de `markers off`,
donc efface le circuit ou tue-les par tag :

```mcfunction
/kill @e[type=armor_stand,tag=cheesepatrol]
```

## 12. Arrêter, et ce qui persiste

```mcfunction
/npcpatrol stop
```

```text
[CheesePatrol] Patrol stopped.
```

Le circuit reste sur disque, le NPC s'arrête où il est, et les chunks que cette patrouille
maintenait chargés sont relâchés. `/npcpatrol start` la reprend au point de passage vers
lequel elle se dirigeait.

Tout ce que tu as fait plus haut a été écrit dans `config/cheesepatrol_data.json` au fur et
à mesure : chaque `add`, chaque `speed`, chaque `wait` réécrit le fichier entier
immédiatement, donc un crash ne perd rien. Redémarre le serveur et la patrouille reprend
d'elle-même, cinq secondes après `SERVER_STARTED`.

Ce que `stop` ne fait **pas**, c'est lever la protection du NPC. Il reste intuable et
sauvegardé toutes les minutes. Pour annuler entièrement la patrouille, protection comprise,
passe `/npcpatrol clear`.

## Pour aller plus loin

- [Circuits de patrouille](patrol-circuits.fr.md) — seuils d'arrivée, segments en vol, et
  pourquoi une ligne droite de 70 blocs finit téléportée
- [Commandes](commands.fr.md) — les sous-commandes restantes, avec tous les messages d'échec
- [Garder les NPC en vie](resilience.fr.md) — ce que fait le mod quand le NPC disparaît
  malgré tout

---

← [Installation](installation.fr.md) · [Commandes](commands.fr.md) →
