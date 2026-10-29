[← CheesePatrol](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](troubleshooting.md)

# Dépannage

Symptôme, cause, remède. Les cinq premières sections traitent des raisons pour lesquelles un
NPC qui devrait marcher ne marche pas, ce qui représente l'écrasante majorité des signalements.

Avant tout, active la journalisation verbeuse : la plupart de ces diagnostics tiennent à une
ligne de log. Arrête le serveur, mets `"debug": true` dans
`config/cheesepatrol_data.json`, redémarre. Instructions complètes dans
[configuration](configuration.fr.md#debug).

---

## La patrouille affiche ACTIVE et le NPC ne bouge pas du tout

**Cause.** Cinq choses distinctes produisent ce symptôme identique. Descends la liste.

1. **Moins de deux points de passage.** `tickPatrol` saute toute patrouille avec
   `waypoints.size() < 2` sans rien journaliser. `/npcpatrol info` te le dit.
2. **L'entité n'est pas un `PathAwareEntity`.** Si tu l'as sélectionnée avec
   `/npcpatrol select <uuid>` plutôt qu'au clic droit, rien ne l'a validée. La boucle de tick
   abandonne au test de type, en silence, à jamais. Sélectionne le NPC au clic droit avec la
   baguette : ce chemin refuse une mauvaise entité à voix haute.
3. **L'entité n'est pas chargée.** Cherche `NPC … not found after 30s - respawn attempt`
   dans le log sous `debug`. Généralement un chunk qui ne se charge pas ; voir
   [le NPC disparaît](#un-npc-disparaît-et-revient-ailleurs) plus bas.
4. **La réflexion EasyNPC est cassée.** Si EasyNPC a été mis à jour et a renommé
   `getEntityGoalSelector`, la recherche échoue en silence et les goals d'inactivité propres
   au NPC le maintiennent sur place. Il n'existe aucune ligne de log pour ce cas. Diagnostique
   par élimination : attache une patrouille à un mob vanilla ordinaire — un zombie, par
   exemple — et vois s'il marche. Si le zombie patrouille et l'EasyNPC non, c'est ta cause.
   Voir [la dépendance EasyNPC](architecture.fr.md#la-dépendance-easynpc-tient-en-un-appel-par-réflexion).
5. **Un joueur se tient dans le rayon de détection.** Le contrôle « regarder un joueur » gèle
   la patrouille avec une priorité absolue et sans délai d'expiration : un NPC avec quelqu'un
   garé à côté de lui ne bouge jamais. Recule à plus de `radius` blocs et regarde à nouveau.

**Remède.** Pour 1, ajoute des points de passage. Pour 2, `/npcpatrol clear` puis
resélectionne au clic droit. Pour 3, voir la section dédiée. Pour 4, fige EasyNPC sur une
version qui fonctionne et ouvre une issue. Pour 5, baisse le rayon ou éloigne le trajet des
endroits où les joueurs traînent.

## start annonce que le NPC est introuvable

**Symptôme.**

```text
[CheesePatrol] Patrol started at waypoint #1/4 (NPC not found — will start when loaded)
```

**Cause.** `kickStartPatrol` n'a pas trouvé l'entité par UUID. Il a cherché d'abord dans la
dimension enregistrée par la patrouille puis dans tous les mondes chargés : l'entité n'est
donc réellement pas en mémoire. Trois raisons :

- son chunk n'est pas chargé (le cas le plus fréquent, et généralement passager)
- elle est dans une dimension non chargée sur ce serveur
- elle a réellement disparu

**Remède.** En général aucun : la patrouille est active et le mod force-loadera le dernier
chunk connu à chaque tick jusqu'à ce que le NPC apparaisse, puis la relancera
automatiquement. Guette :

```text
[CheesePatrol] NPC a1b2c3d4 found after 412 ticks missing - kick-starting patrol
```

S'il n'apparaît jamais, l'échelle de réapparition prend le relais au bout de 30 secondes. Si
elle échoue aussi dix fois, tu obtiens une erreur `failed to respawn 10 times` et la
patrouille passe en pause système, que le contrôle d'intégrité réessaie toutes les cinq
minutes. À ce stade, vérifie que le `Dim:` affiché par `/npcpatrol info` existe encore sur ce
serveur — une patrouille fixée sur une dimension CheeseGuard supprimée ne pourra jamais être
récupérée, et le log le dit :

```text
[CheesePatrol] Cannot respawn NPC a1b2c3d4 - dimension 'cheeseguard:arena_01' not found
```

Dans ce cas, passe `/npcpatrol clear` sur la patrouille et reconstruis-la sur un NPC qui
existe.

## Le NPC marche dans un mur et y reste

**Cause.** Conforme à la construction. Le déplacement est un écrasement de vélocité par tick,
sans pathfinding et sans test d'obstacle : un point de passage que le NPC ne peut pas
atteindre en ligne droite est un point de passage qu'il ne peut pas atteindre.

**Remède.** Repense le segment. Lance `/npcpatrol show` et suis chaque ligne du regard :
toute ligne qui traverse de la géométrie est un segment cassé. Ajoute des points de passage
intermédiaires pour que chaque paire consécutive ait une ligne de vue dégagée. Évite les
barrières, les portillons, les tapis sur les escaliers et les marches plus hautes que la
hauteur de pas du mob.

Le chien de garde anti-blocage finira par le téléporter sur le point de passage au bout de 60
secondes : une patrouille mal tracée a donc l'air de fonctionner, de loin. Ce n'est pas un
remède, c'est le symptôme qui se cache lui-même.

## Le NPC se téléporte au lieu de parcourir une longue ligne droite

**Symptôme.** Avec `debug: true` :

```text
[CheesePatrol] NPC a1b2c3d4 stuck for 60s at dist 14.2 - teleporting to waypoint 3
```

et le NPC saute la dernière portion à chaque tour.

**Cause.** Le chien de garde anti-blocage mesure la *distance au point de passage*, pas la
progression. Toute période continue de 60 secondes passée à plus de 2 blocs de la cible le
déclenche, y compris une marche parfaitement saine. À la vitesse par défaut `0.5`, la limite
est d'environ **70 blocs de segment**.

**Remède.** Soit coupe la longue branche avec un point de passage intermédiaire, soit monte la
vitesse. À `speed 1.0` la limite double à peu près, à 144 blocs. L'arithmétique est dans
[circuits de patrouille](patrol-circuits.fr.md#le-chien-de-garde-de-blocage--60-secondes-téléportation).

## Le NPC s'arrête pour un joueur qui n'est même pas visible

**Cause.** Le contrôle « regarder un joueur » est une simple requête de distance sphérique,
**sans test de ligne de vue**. Un joueur derrière un mur, sous un plancher ou de l'autre côté
d'une porte fermée compte.

**Remède.** Baisse `/npcpatrol radius`, ou déplace le trajet. Il n'existe aucun réglage pour
la ligne de vue ; en ajouter un signifie ajouter un appel `raycast` à côté de l'appel à
`getClosestPlayer` dans `tickPatrol`.

Noter l'effet de ricochet : une attente sur un point de passage ne se décompte pas pendant
que le NPC regarde un joueur, donc un NPC avec un voisin permanent ne termine jamais sa pause
non plus.

---

## Le mod ne se charge pas du tout

**Symptôme.** Aucune ligne `[CheesePatrol]` dans le log, et `/npcpatrol` n'existe pas.

**Cause et remède**, par ordre de probabilité :

| Cause | Remède |
|---|---|
| CheeseDash est absent | `"cheesedash": "*"` est dans `depends`. Le loader refuse de démarrer — cherche un écran d'erreur de dépendance Fabric nommant `cheesepatrol`, pas un échec silencieux |
| Mauvaise version de Minecraft ou du loader | `~1.21.1` et un loader `≥ 0.16.0` sont exigés |
| Java inférieur à 21 | le jar est compilé en release 21 et ne chargera pas en 17 |
| Tu as déployé `cheesepatrol-1.0.0+1.21.1-sources.jar` | déploie le jar simple |
| Deux copies du jar dans `mods/` | supprime l'ancienne |

**Symptôme.** Le serveur plante au démarrage avec une erreur de mixin mentionnant
`EntityRemoveMixin`.

**Cause.** `cheesepatrol.mixins.json` fixe `"required": true` et `"defaultRequire": 1` : une
injection ratée dans `Entity.remove` est donc fatale plutôt que sautée. C'est délibéré —
perdre silencieusement la protection contre la suppression serait pire. En pratique, cela
signifie une version de Minecraft contre laquelle ce mod n'a pas été construit.

**Remède.** Utilise la 1.21.1, ou recompile le mod pour ta version. Ne contourne pas en
assouplissant l'exigence du mixin : un mixin de protection de NPC qui ne fait rien en silence
est un piège.

## La vue Patrols manque dans la barre latérale du dashboard

**Cause.** CheeseDash construit sa barre latérale à partir des entrypoints `cheesedash`
découverts au démarrage. Une entrée manquante signifie l'une de ces choses :

- CheesePatrol ne s'est pas chargé du tout — vérifie d'abord ses lignes de log
- le jar de CheeseDash dans `mods/` est plus ancien que l'interface `DashboardModule` contre
  laquelle le mod a été compilé
- tu regardes une page en cache

**Remède.** Vérifie que `[CheesePatrol] Ready.` est dans le log, force le rechargement du
dashboard, et interroge directement la liste des modules :

```bash
curl http://localhost:44311/api/v1/modules -H 'Authorization: Bearer ses_…'
```

`patrol` doit apparaître dans la réponse. Si c'est le cas et que la barre latérale reste
vide, le problème est du côté de CheeseDash, pas ici.

## Le dashboard répond 503 CheesePatrol not ready

**Cause.** `CheesePatrolDashboardModule` ne conserve sa référence au `MinecraftServer`
qu'entre `onServerStart` et `onServerStop`. Un 503 signifie que le serveur Minecraft ne
tourne pas — tu es soit dans la fenêtre avant son démarrage, soit après un `/stop` avec le
serveur HTTP encore debout.

**Remède.** Attends le serveur, ou démarre-le.

## Éditer cheesepatrol_data.json n'a aucun effet

**Cause.** Le fichier est lu exactement une fois, à `SERVER_STARTING`. Il n'y a aucune
commande de rechargement. Pire, il est réécrit en entier depuis la mémoire par
`PatrolManager.save()` à chaque modification : une édition faite serveur allumé n'est donc
pas seulement ignorée, elle est écrasée dès que quelqu'un ajoute un point de passage ou
démarre une patrouille, et au plus tard à l'arrêt du serveur.

**Remède.** Arrête le serveur, édite, démarre. C'est la seule séquence prise en charge, et
c'est ainsi qu'on change `debug`.

## Une commande est refusée

| Ce que tu vois | Cause | Remède |
|---|---|---|
| *Unknown or incomplete command* vanilla | tu es sous le niveau de permission 2 | passe-toi `/op`, ou monte le niveau de permission |
| `Player only` | tu l'as lancée depuis la console, RCON ou un bloc de commande. Seule `list` y fonctionne | lance-la en jeu |
| `[CheesePatrol] No NPC selected. …` | la Patrol Wand n'est pas dans ta **main principale**, ou c'est une baguette sans sélection enregistrée | `/npcpatrol wand`, puis clic droit sur le NPC |
| `[CheesePatrol] Hold a Patrol Wand first!` | `select` a besoin de la baguette pour y écrire l'UUID | prends une baguette |
| `[CheesePatrol] No entity found with UUID …` | l'entité n'est pas chargée, ou pas dans **ta** dimension courante — `select` ne cherche que dans ton propre monde | va dans sa dimension, ou clique dessus |
| `[CheesePatrol] Invalid waypoint index.` | `remove` ou `wait` reçoit un index au-delà de la liste. Les index partent de 1 | `/npcpatrol info` pour la vraie numérotation |
| `[CheesePatrol] Need at least 2 waypoints …` | exactement cela | ajoute des points de passage |
| `[CheesePatrol] Unknown particle type: …` | le nom n'est pas l'un des 46 | l'erreur les liste tous ; la complétion par tabulation est plus simple |
| *Incomplete command* vanilla après `/npcpatrol add 5000` | un nombre seul au-delà de 3600 sort de la branche « attente » et est analysé comme un `x` | utilise `/npcpatrol wait` pour les longues pauses, ou donne les trois coordonnées |

## `/kill` ne fait rien à un NPC de patrouille

**Cause.** Le mixin de suppression bloque `KILLED` et `DISCARDED` pour toute entité ayant une
fiche de patrouille **ou** un fichier de sauvegarde résiduel dans
`config/cheesepatrol_entities/`. Tu verras :

```text
[CheesePatrol] Blocked removal (KILLED) of registered NPC a1b2c3d4 (patrol active)
```

**Remède.** Passe d'abord `/npcpatrol clear` sur ce NPC — cela retire la fiche *et* supprime
le fichier de sauvegarde, levant les deux couches de protection. Tue-le ensuite.

Si tu as déjà retiré le mod et que tu te retrouves avec un NPC intuable, plus rien ne te
bloque : le mixin est parti avec le jar.

## Le log est rempli de lignes Blocked removal

**Cause.** Quelque chose essaie sans cesse de supprimer un NPC protégé. Le mixin journalise
chaque tentative en `INFO`, sans limitation de débit et **sans** être supprimé par
`debug: false`.

**Remède.** Trouve ce qui supprime — un plugin de nettoyage d'entités, un bloc de commande
répétitif avec un sélecteur `@e` trop large, un script de mob-cap. Exclus-en les NPC de
patrouille — par UUID, ou avec un tag à toi : CheesePatrol ne pose le tag `cheesepatrol` que
sur ses porte-armures marqueurs, jamais sur les NPC — ou resserre le sélecteur. Les lignes de log sont un symptôme, et les
faire taire est impossible sans modifier le mod.

## Un NPC disparaît et revient ailleurs

**Cause.** C'est l'échelle de réapparition qui fait son travail. L'entité a été absente
30 secondes, le mod l'a reconstruite depuis son instantané NBT, et l'a placée **sur son point
de passage courant** plutôt que là où elle avait été perdue. Sous `debug` :

```text
[CheesePatrol] NPC a1b2c3d4 not found after 30s - respawn attempt 1/10
[CheesePatrol] Respawned NPC a1b2c3d4 at waypoint 128, 65, -340
```

**Remède.** Généralement aucun. Mais un cycle *qui se répète* signifie que l'entité continue
de disparaître, et cela vaut la peine d'être traqué : l'instantané NBT peut avoir une minute
de retard, donc chaque aller-retour peut perdre des changements d'état EasyNPC. Cherche un
chunk qui refuse de rester chargé, ou un autre mod qui supprime l'entité d'une façon que la
liste des raisons autorisées du mixin laisse passer.

⚠️ Si tu vois le même NPC exister en double, c'est le risque connu de doublon d'UUID : le
contrôle de conflit ne voit que les mondes chargés, donc une réapparition pendant que
l'original est dans un chunk déchargé crée un second exemplaire. Passe `/npcpatrol clear`,
supprime les deux entités, et reconstruis la patrouille.

## Les marqueurs de points de passage refusent de disparaître

**Cause.** `/npcpatrol markers` ne fait que supprimer puis reposer. Il n'y a pas
d'interrupteur d'arrêt, et `/npcpatrol clear` ne retire les marqueurs **que dans le monde où
tu te tiens**.

**Remède.**

```mcfunction
/kill @e[type=armor_stand,tag=cheesepatrol]
```

dans chaque monde concerné. Un arrêt propre du serveur retire aussi tous les porte-armures
tagués qui se trouvent dans un chunk chargé, dans tous les mondes : un redémarrage en efface
donc la plupart, mais pas un crash, ni un arrêt pour ceux des chunks non chargés.

## Mes `/forceload` se font retirer sans arrêt

**Cause.** Un bug connu. Quand une patrouille a besoin d'un chunk déjà forcé, le mod
s'abstient de le forcer mais l'enregistre quand même comme le sien, et le relâche plus tard
quand le NPC s'éloigne — annulant ton forceload.

**Remède.** Garde les chunks forcés à la main à l'écart des trajets de patrouille, ou
réajoute-les. Détails dans
[garder les NPC en vie](resilience.fr.md#le-suivi-des-chunks-dune-patrouille-peut-annuler-ton-propre-forceload).

## Des chunks restent forcés après la désinstallation

**Cause.** Les chunks forcés ne sont délibérément pas relâchés à l'arrêt, et retirer le jar
ne laisse rien pour les relâcher.

**Remède.**

```mcfunction
/forceload query
/forceload remove <x> <z>
```

Évite-le la prochaine fois en passant `/npcpatrol clear` sur chaque patrouille **avant** de
retirer le mod. La procédure complète est dans
[installation](installation.fr.md#désinstaller-proprement).

## Les particules de traînée n'apparaissent pas

Vérifie dans cet ordre :

1. **La patrouille est-elle active ?** Les particules de traînée sont émises depuis
   `tickPatrol`, qui ne tourne que pour les patrouilles actives ayant au moins deux points de
   passage. Une patrouille arrêtée n'a pas de traînée.
2. **Le NPC est-il chargé et trouvé ?** Pas d'entité, pas de particules.
3. **Es-tu dans `particlerange` ?** La valeur par défaut est 16 blocs.
   `/npcpatrol particlerange 48`.
4. **L'effet est-il `none` ?** `/npcpatrol info` n'affiche pas l'effet de particules ; lis
   `cheesepatrol_data.json`, où `particleEffect` est entièrement omis quand il vaut `none`.
5. **Le décalage les pousse-t-il dans le modèle ou dans un mur ?** Remets à zéro avec
   `/npcpatrol particleheight 0` et `/npcpatrol particledepth 0`.

Les particules sont envoyées avec `force = true` : le réglage de particules du client n'est
donc pas le problème.

## L'aperçu du trajet n'affiche rien

**Cause.** `/npcpatrol show` est supprimé, sans message, quand tu es dans une autre dimension
que la patrouille. `/npcpatrol info` indique laquelle sous `Dim:`.

**Remède.** Va dans cette dimension. Noter aussi qu'une seule patrouille peut être
prévisualisée par joueur à la fois — en afficher une seconde remplace silencieusement la
première.

---

## Produire un rapport de bug utile

Ouvre-le sur
[GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues) avec :

**1. Le log, avec `debug: true` activé.** `logs/latest.log`. Cherches-y `[CheesePatrol]` et
inclus toutes les occurrences, dans l'ordre, du démarrage du serveur jusqu'à la panne. Sans
le drapeau debug, la plupart des diagnostics de ce mod n'existent pas.

**2. La patrouille elle-même.** L'objet concerné de `config/cheesepatrol_data.json`, ou la
sortie de `/npcpatrol info` avec le NPC sélectionné. Les coordonnées des points de passage
comptent : la plupart des problèmes de déplacement sont de la géométrie.

**3. Les versions.** Minecraft, Fabric Loader, Fabric API, CheeseDash, **EasyNPC** et le nom
du jar CheesePatrol. La version d'EasyNPC est celle qu'on oublie et la plus susceptible
d'être la cause, puisque toute l'intégration tient à un unique appel par réflexion sur un nom
de méthode.

**4. Ce que fait le NPC, en une phrase.** « Fait deux coins puis s'arrête », « se téléporte
sur la dernière portion de chaque tour », « ne quitte jamais sa place ». Combiné aux
coordonnées des points de passage, c'est généralement suffisant pour identifier laquelle des
cinq causes de la première section s'applique.

**5. Si un mob vanilla patrouille correctement.** Attache une courte patrouille à un zombie
et dis s'il marche. Ce seul test sépare « le mod est cassé » de « l'intégration EasyNPC est
cassée », et c'est la chose la plus utile que tu puisses inclure.

Pour un crash, inclus la stack trace complète, pas la ligne de résumé. Pour tout ce qui
touche au dashboard, inclus le statut HTTP et le corps de la réponse.

---

← [Architecture](architecture.fr.md) · [CheesePatrol](../README.fr.md) →
