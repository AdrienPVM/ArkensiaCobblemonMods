[← CheesePatrol](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](commands.md)

# Commandes

CheesePatrol enregistre une seule commande, `/npcpatrol`, avec 18 sous-commandes. L'arbre
entier est soumis à `hasPermissionLevel(2)` — le niveau qu'un serveur vanilla accorde à un
opérateur. Un joueur en dessous ne voit pas la commande dans la complétion par tabulation
et reçoit l'erreur vanilla *Unknown or incomplete command* s'il la tape.

Aucune commande n'est destinée aux joueurs, et il n'y a aucun alias. `/npcpatrol` est le
seul point d'entrée.

---

## La baguette porte ta sélection

C'est la seule chose à comprendre avant de lire le reste de cette page.

Quinze des 18 sous-commandes agissent sur « le NPC sélectionné », et la sélection n'est
**pas** une variable par joueur côté serveur. Elle vit dans le NBT de la Patrol Wand, sous
la clé `cheesepatrol_npc`, et chacune de ces sous-commandes la relit dans
`player.getMainHandStack()` au moment où tu la lances.

Les conséquences méritent d'être explicitées :

- La baguette doit être **dans ta main principale**, pas la main secondaire, pas la barre
  d'accès rapide.
- Deux administrateurs peuvent tenir deux baguettes pointant sur deux NPC différents sans
  se gêner.
- Lâcher, ranger ou perdre la baguette perd la sélection. Reprends-en une et resélectionne.
- Dupliquer la baguette (clic molette en créatif) duplique la sélection avec elle.
- `wand`, `list` et le point d'entrée `select` sont les seules sous-commandes qui n'exigent
  pas une sélection existante.

Les trois façons de sélectionner : clic droit sur un NPC baguette en main,
`/npcpatrol select <uuid>`, ou hériter de ce que la baguette pointait déjà.

Quand une sous-commande a besoin d'une sélection et n'en trouve pas :

```text
[CheesePatrol] No NPC selected. Hold a Patrol Wand and /npcpatrol select <uuid>, or right-click an EasyNPC.
```

## Depuis une console, RCON ou un bloc de commande

Possible pour exactement une sous-commande : `list`. Toutes les autres ont besoin soit d'un
joueur directement, soit d'une baguette, et les deux chemins abandonnent avec :

```text
Player only
```

(pas de préfixe `[CheesePatrol]` sur celui-là : il vient d'un autre chemin de code). Un bloc
de commande a le niveau de permission 2 et passe le contrôle `requires`, puis échoue au
contrôle « joueur ». Il n'existe aucun moyen de piloter les patrouilles depuis un datapack.

---

## `/npcpatrol wand`

```mcfunction
/npcpatrol wand
```

Aucun argument. Insère une Patrol Wand dans ton inventaire : une tige de Blaze avec le nom
personnalisé doré et gras `Patrol Wand`, un scintillement d'enchantement forcé, et un
booléen `cheesepatrol_wand` dans son composant `minecraft:custom_data`. C'est ce composant
qui en fait une baguette — une tige de Blaze renommée à l'enclume n'en est pas une.

```text
[CheesePatrol] Patrol Wand given. Right-click an EasyNPC to select it, then use /npcpatrol add.
```

L'objet est inséré selon les règles normales d'inventaire : inventaire plein, il ne va
nulle part et le message de succès s'affiche quand même. Relance la commande autant de fois
que tu veux, rien ne dédoublonne les baguettes.

| Message | Cause |
|---|---|
| `Player only` | lancée depuis la console, RCON ou un bloc de commande |

## `/npcpatrol select <uuid>`

```mcfunction
/npcpatrol select <uuid>
```

| Argument | Type | Requis | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `uuid` | UUID | oui | un UUID d'entité complet, forme avec tirets ; aucune complétion proposée | — |

Écrit `uuid` dans la baguette que tu tiens et crée une fiche de patrouille s'il n'y en a
pas, en fixant la dimension de la patrouille sur **le monde où tu te trouves**.
L'alternative au clic droit, utile quand le NPC est loin ou noyé dans une foule d'entités.

```mcfunction
/npcpatrol select a1b2c3d4-e5f6-7890-abcd-ef1234567890
```

```text
[CheesePatrol] Selected NPC: a1b2c3d4... (Village Guard)
```

Deux choses qu'elle ne fait **pas**, et les deux piquent :

- Elle cherche l'entité avec `player.getServerWorld().getEntity(uuid)` : le NPC doit donc
  être dans **ton** monde courant et chargé. Un NPC dans une autre dimension ne peut pas
  être sélectionné de cette façon ; va sur place, ou clique dessus.
- Contrairement au clic droit, elle ne vérifie **pas** que l'entité est un
  `PathAwareEntity`. Tu peux sélectionner un porte-armures ou un objet au sol par son UUID,
  lui construire un circuit complet, le démarrer, et n'obtenir ni erreur ni mouvement : la
  boucle par tick abandonne silencieusement pour tout ce qui n'est pas path-aware.

| Message | Cause |
|---|---|
| `Player only` | pas lancée par un joueur |
| `[CheesePatrol] Hold a Patrol Wand first! (/npcpatrol wand)` | ta main principale n'est pas une baguette ; l'UUID est jeté |
| `[CheesePatrol] No entity found with UUID <uuid>` | pas chargée, ou pas dans ta dimension courante |

## Sélectionner au clic droit

Pas une sous-commande, mais la même opération, et celle que tu utiliseras. Baguette en main,
clic droit sur n'importe quelle entité :

```text
[CheesePatrol] Selected: Village Guard (a1b2c3d4...)
```

dans le chat, avec un suffixe d'état quand le NPC a déjà un circuit :
`[6 waypoints, inactive]`, ou en rouge
`[PATROL ACTIVE — /npcpatrol clear before deleting!]`.

Ce chemin, lui, vérifie `PathAwareEntity` et refuse sinon, sur ta barre d'action :

```text
[CheesePatrol] Not a valid NPC (not PathAwareEntity).
```

Les deux gestionnaires de clic droit, entité et bloc, exigent le niveau de permission 2 et
la main principale. Sans le niveau, le clic retombe sur le comportement vanilla comme si
CheesePatrol n'était pas installé.

## `/npcpatrol add`

Quatre formes, toutes **ajoutant un point de passage à la fin de la liste**. Il n'y a pas
d'insertion.

```mcfunction
/npcpatrol add
/npcpatrol add <waitSeconds>
/npcpatrol add <x> <y> <z>
/npcpatrol add <x> <y> <z> <waitSecondsPos>
```

| Argument | Type | Requis | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `waitSeconds` | entier | non | `0`–`3600` | `0` |
| `x` `y` `z` | entiers | les trois, ou aucun | tout entier ; **coordonnées de bloc absolues uniquement** — `~` et `^` ne sont pas gérés | tes pieds |
| `waitSecondsPos` | entier | non | `0`–`3600` | `0` |

Les arguments de coordonnées sont de simples entiers, pas le type « position » de
Minecraft : les coordonnées relatives et locales ne fonctionnent donc pas. Tape les nombres.

Les formes « aux pieds » utilisent `player.getBlockPos()` — le bloc qu'occupent tes pieds,
soit, debout sur le sol, le bloc d'air dans lequel tu te tiens. Le clic droit de la baguette
sur un bloc utilise au contraire le bloc cliqué **plus un en Y** : les deux posent donc le
point de passage là où vont les pieds d'un NPC.

```mcfunction
/npcpatrol add 5
```

```text
[CheesePatrol] Added waypoint #3 at 128 65 -340 (wait 5s)
```

```mcfunction
/npcpatrol add 112 65 -324
```

```text
[CheesePatrol] Added waypoint #4 at 112 65 -324
```

Les deux formes sauvegardent `cheesepatrol_data.json` immédiatement.

⚠️ Les formes à un nombre et à trois nombres occupent la même position dans l'arbre
Brigadier : un nombre seul est donc lu comme un temps d'attente, et trois nombres comme des
coordonnées. Un nombre seul **supérieur à 3600** sort de la branche « attente », est lu
comme un `x`, et la commande devient incomplète — tu reçois l'erreur vanilla
*Incomplete command* plutôt qu'un message sur les bornes.

| Message | Cause |
|---|---|
| `Player only` | pas lancée par un joueur, y compris pour les formes à coordonnées |
| `[CheesePatrol] No NPC selected. …` | pas de baguette, ou baguette sans sélection |

## `/npcpatrol remove <index>`

```mcfunction
/npcpatrol remove <index>
```

| Argument | Type | Requis | Valeurs acceptées |
|---|---|---|---|
| `index` | entier | oui | `≥ 1` ; la numérotation affichée par `/npcpatrol info` |

Supprime un point de passage et son temps d'attente. **Tout ce qui suit est renuméroté** :
retirer le #2 d'un circuit de cinq points laisse #1…#4, l'ancien #3 s'appelant désormais #2.
Supprime en partant de la fin si tu en retires plusieurs.

```mcfunction
/npcpatrol remove 3
```

```text
[CheesePatrol] Removed waypoint #3. 3 remaining.
```

Si le NPC visait un index qui n'existe plus, l'index retombe à 0 et le NPC se tourne vers le
point de passage #1 au tick suivant. Une patrouille en cours qui descend sous deux points de
passage cesse d'avancer mais reste marquée active : la boucle par tick la saute. Supprimer
le point de passage où le NPC se tient n'interrompt pas une attente en cours.

| Message | Cause |
|---|---|
| `[CheesePatrol] Invalid waypoint index.` | aucune patrouille pour ce NPC, ou `index` au-delà de la liste |
| `[CheesePatrol] No NPC selected. …` | aucune sélection |

## `/npcpatrol start`

```mcfunction
/npcpatrol start
```

Aucun argument. Marque la patrouille active, efface le drapeau de pause système,
réinitialise le compteur d'entité manquante, et remet la cible sur le point de passage #1 si
l'index stocké dépasse la fin de la liste. Puis elle **relance** (*kick-start*), ce qui est
la partie intéressante :

1. trouver l'entité par UUID dans la dimension de la patrouille, avec repli sur tous les
   mondes chargés
2. effacer entièrement le goal selector d'EasyNPC (`clear(g -> true)`)
3. couper la navigation, mettre la vélocité à zéro
4. effacer les drapeaux « regarde un joueur » et « en attente »
5. appliquer `NoGravity` si le point de passage courant est un point en vol
6. si le NPC est déjà à moins de 1,5 bloc de son point de passage courant, passer au
   suivant — `start` n'attend donc jamais sur le point où il se tient
7. pousser la navigation vers le point de passage courant
8. force-loader le chunk de la dernière position connue et **tous les chunks des points de
   passage**

```text
[CheesePatrol] Patrol started at waypoint #1/4 (NPC kick-started)
```

Le suffixe dit si l'étape 1 a trouvé quelque chose :

| Suffixe | Signification |
|---|---|
| `(NPC kick-started)` | l'entité a été trouvée et poussée ; elle avance maintenant |
| `(NPC not found — will start when loaded)` | la patrouille est active mais rien n'a bougé. Le NPC est déchargé, mort, ou dans une autre dimension que celle enregistrée |

Le second cas n'est pas un échec signalé comme une erreur : la patrouille est bien active, et
la boucle par tick va commencer à force-loader des chunks puis, au bout de 30 secondes, à
tenter de faire réapparaître le NPC depuis sa sauvegarde NBT. Voir
[garder les NPC en vie](resilience.fr.md#léchelle-de-réapparition).

| Message | Cause |
|---|---|
| `[CheesePatrol] Need at least 2 waypoints to start a patrol.` | moins de deux points de passage, ou aucune fiche de patrouille |
| `[CheesePatrol] No NPC selected. …` | aucune sélection |

## `/npcpatrol stop`

```mcfunction
/npcpatrol stop
```

Aucun argument. Marque la patrouille inactive, efface le drapeau de pause système pour que
le contrôle d'intégrité ne la relance pas tout seul, relâche les chunks force-loadés de
cette patrouille, et sauvegarde.

```text
[CheesePatrol] Patrol stopped.
```

Le NPC s'arrête là où il est — en pleine enjambée, avec la vélocité que lui a donnée le
dernier tick, donc il peut dériver d'un bloc avant que la friction l'emporte. L'index du
point de passage est conservé : `start` reprend le tour plutôt que de le recommencer.

Ce que `stop` ne fait **pas** : il ne lève pas la protection contre la suppression,
n'arrête pas les sauvegardes NBT minute par minute, et n'empêche pas le contrôle
d'intégrité, qui passe toutes les cinq minutes, de faire réapparaître le NPC s'il
disparaît. Une patrouille inactive reste un NPC protégé. Seul `clear` défait cela.

Distinguer un arrêt manuel d'un arrêt automatique compte : `stop` met
`pausedBySystem = false`, ce qui dit au contrôle d'intégrité « un humain a fait ça, n'y
touche pas ». Une patrouille désactivée par dix réapparitions ratées met ce drapeau à `true`
et se voit relancée automatiquement quand son NPC revient.

| Message | Cause |
|---|---|
| `[CheesePatrol] No patrol data for this NPC.` | la baguette pointe sur un UUID sans fiche de patrouille |
| `[CheesePatrol] No NPC selected. …` | aucune sélection |

## `/npcpatrol clear`

```mcfunction
/npcpatrol clear
```

Aucun argument, aucune confirmation, irréversible. Dans l'ordre : relâcher les chunks
force-loadés, vider la liste des points de passage, supprimer les marqueurs porte-armures de
ce NPC **dans ton monde courant uniquement**, retirer la patrouille du registre, supprimer
`config/cheesepatrol_entities/<uuid>.dat`, et sauvegarder.

```text
[CheesePatrol] Patrol cleared and all waypoints removed.
```

C'est aussi le seul moyen de rendre un NPC de patrouille à nouveau tuable. Tant qu'une fiche
de patrouille ou un fichier de sauvegarde existe pour une entité, le mixin de suppression
bloque la suppression qui suit un `/kill`, un coup mortel ou un despawn (il ne bloque pas
les dégâts eux-mêmes) — l'ordre pour supprimer un NPC est donc toujours
`clear` d'abord, kill ensuite. Le suffixe d'état au clic droit est là pour te le rappeler.

Le nettoyage des marqueurs est limité à la dimension où tu te tiens : effacer une patrouille
depuis le mauvais monde laisse donc ses porte-armures sur place. Tue-les par tag :

```mcfunction
/kill @e[type=armor_stand,tag=cheesepatrol]
```

| Message | Cause |
|---|---|
| `[CheesePatrol] No NPC selected. …` | aucune sélection |

Lancer `clear` sur un UUID sans fiche de patrouille réussit et annonce un succès. La
commande est idempotente.

## `/npcpatrol list`

```mcfunction
/npcpatrol list
```

Aucun argument, aucune sélection nécessaire, fonctionne depuis la console. Toutes les
patrouilles du serveur, actives ou non, dans un ordre non défini (elle parcourt une
`ConcurrentHashMap`).

```text
Patrols (2):
[ACTIVE] a1b2c3d4... | 4 WP | speed=1.0 | radius=6.0
[INACTIVE] 9f8e7d6c... | 6 WP | speed=0.5 | radius=5.0
```

`[ACTIVE]` est vert, `[INACTIVE]` gris. L'UUID est tronqué à huit caractères — assez pour
identifier un NPC, pas assez pour le coller dans `/npcpatrol select`. Pour l'UUID complet,
utilise `info`, ou le dashboard, qui le renvoie en entier.

Noter que `[INACTIVE]` couvre à la fois « un administrateur l'a arrêtée » et « le système
l'a mise en pause après dix réapparitions ratées ». `list` ne les distingue pas ; le
dashboard le fait, via le champ `pausedBySystem`.

Sans rien de configuré :

```text
[CheesePatrol] No patrols configured.
```

## `/npcpatrol info`

```mcfunction
/npcpatrol info
```

Aucun argument. Détail complet de la patrouille sélectionnée, envoyé à toi seul.

```text
=== Patrol Info ===
NPC: a1b2c3d4-e5f6-7890-abcd-ef1234567890
Dim: minecraft:overworld
Status: ACTIVE
Speed: 1.0
Radius: 6.0
Waypoints (4):
  #1: 112 65 -340
  #2: 128 65 -340 [wait 5s]
  #3: 128 65 -324 <-- current
  #4: 112 65 -324
```

| Champ | Signification |
|---|---|
| `NPC` | l'UUID complet, celui à coller dans `select` ou dans le dashboard |
| `Dim` | la dimension sur laquelle la patrouille est fixée, définie au moment de la sélection |
| `Status` | `ACTIVE` en vert ou `INACTIVE` en rouge. Ne distingue pas une pause système |
| `Speed` | la valeur stockée, après bornage |
| `Radius` | rayon de détection des joueurs en blocs, après bornage |
| `[wait Ns]` | pause propre au point de passage, omise quand elle vaut zéro |
| `<-- current` | le point de passage vers lequel le NPC marche, pas celui qu'il a quitté |

`info` lit la fiche vivante, en mémoire : un changement fait au tick précédent apparaît
immédiatement. La commande ne dit rien de l'entité : un NPC disparu depuis une heure affiche
toujours `ACTIVE` avec un point de passage courant. Pour l'état de l'entité, lis le log
serveur ou le dashboard.

| Message | Cause |
|---|---|
| `[CheesePatrol] No patrol data for this NPC.` | l'UUID de la baguette n'a pas de fiche de patrouille |
| `[CheesePatrol] No NPC selected. …` | aucune sélection |

## `/npcpatrol speed <value>`

```mcfunction
/npcpatrol speed <value>
```

| Argument | Type | Requis | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `value` | double | oui | `0.1`–`5.0`, imposé par Brigadier puis borné à nouveau à l'écriture | `0.5` |

```text
[CheesePatrol] Speed set to 1.0
```

Prend effet au tick suivant ; pas de redémarrage, pas besoin de relancer `start`. Ce n'est
pas une valeur en blocs par seconde : le code multiplie par `0.12` au sol et par `0.15` en
vol pour obtenir une vélocité. `0.5` donne environ 1,2 bloc par seconde au sol. Détails dans
[circuits de patrouille](patrol-circuits.fr.md#la-vitesse-nest-pas-en-blocs-par-seconde).

Crée une fiche de patrouille s'il n'y en a pas : tu peux donc régler une vitesse avant de
poser le moindre point de passage.

## `/npcpatrol radius <value>`

```mcfunction
/npcpatrol radius <value>
```

| Argument | Type | Requis | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `value` | double | oui | `1.0`–`32.0` | `5.0` |

```text
[CheesePatrol] Player detection radius set to 6.0
```

La sphère à l'intérieur de laquelle le NPC s'arrête et se tourne vers le joueur le plus
proche. Sphérique, pas cylindrique : un joueur 10 blocs au-dessus compte comme à 10 blocs.
Les spectateurs sont exclus ; il n'y a ni vérification de la ligne de vue, ni temps de
recharge. Comportement complet :
[l'arrêt « regarder un joueur »](patrol-circuits.fr.md#larrêt-pour-regarder-un-joueur).

Les grandes valeurs coûtent cher. À `32.0` sur un spawn fréquenté, un NPC ne bougera
pratiquement jamais, parce qu'il y a toujours quelqu'un dans le rayon.

## `/npcpatrol wait <index> <seconds>`

```mcfunction
/npcpatrol wait <index> <seconds>
```

| Argument | Type | Requis | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `index` | entier | oui | `≥ 1`, un numéro de point de passage existant | — |
| `seconds` | entier | oui | `0`–`3600` | — |

⚠️ **L'index d'abord, les secondes ensuite.** L'ancien README de ce mod décrivait `wait`
comme prenant un unique nombre de ticks ; la commande prend deux arguments et le second est
en secondes, converti en ticks en interne (`seconds * 20`).

```mcfunction
/npcpatrol wait 2 5
```

```text
[CheesePatrol] Waypoint #2 wait set to 5s.
```

```mcfunction
/npcpatrol wait 2 0
```

```text
[CheesePatrol] Waypoint #2 set to smooth (no wait).
```

La pause démarre quand le NPC arrive à moins de 1,5 bloc du point de passage (5 blocs sur un
segment en vol), et ce n'est **pas** une garantie de durée : le décompte est entièrement
suspendu tant qu'un joueur est dans le rayon de détection, parce que le contrôle
« regarder un joueur » rend la main avant le traitement de l'attente. Une attente de 5
secondes avec un joueur planté à côté du NPC dure aussi longtemps que le joueur.

Les deux formes de `add` peuvent régler une attente à la création, ce qui demande en général
moins de frappe.

| Message | Cause |
|---|---|
| `[CheesePatrol] Invalid waypoint index.` | aucune patrouille, ou `index` au-delà de la liste |
| `[CheesePatrol] No NPC selected. …` | aucune sélection |

## `/npcpatrol show`

```mcfunction
/npcpatrol show
```

Aucun argument. Bascule un aperçu privé du trajet pour le NPC sélectionné : de la poussière
colorée sur chaque point de passage, une ligne dorée le long de chaque segment y compris
celui de fermeture, redessinée toutes les 10 ticks.

```text
[CheesePatrol] Particles ON. Run again to hide.
```

```text
[CheesePatrol] Particles OFF.
```

Un seul NPC à la fois par joueur — afficher une seconde patrouille remplace la première.
L'affichage est supprimé pendant que tu es dans une autre dimension que la patrouille, et
abandonné automatiquement si tu te déconnectes ou si la patrouille est effacée. Couleurs et
géométrie : [rendu visuel](visuals.fr.md#laperçu-du-trajet).

Fonctionne sur une patrouille inactive, et c'est tout l'intérêt : vérifier le trajet avant
de la démarrer.

| Message | Cause |
|---|---|
| `Player only` | pas lancée par un joueur |
| `[CheesePatrol] No waypoints to show.` | aucune fiche de patrouille, ou liste de points de passage vide |
| `[CheesePatrol] No NPC selected. …` | aucune sélection |

## `/npcpatrol markers`

```mcfunction
/npcpatrol markers
```

Aucun argument. Place un porte-armures invisible, sans gravité et invulnérable, avec une
étiquette visible `WP #1`, `WP #2` … sur chaque point de passage de la patrouille
sélectionnée, dans **ton monde courant**.

```text
[CheesePatrol] Armor stand markers placed at 4 waypoint(s). Run again to refresh.
```

⚠️ Le message dit *refresh*, et rafraîchir est tout ce que la commande fait : chaque appel
supprime les marqueurs existants de ce NPC et en pose une nouvelle série. **Il n'existe aucun
moyen d'éteindre les marqueurs avec cette commande.** Ils sont retirés par
`/npcpatrol clear`, par un arrêt propre du serveur (qui supprime tous les porte-armures
tagués `cheesepatrol` qui se trouvent dans un chunk chargé, dans tous les mondes), ou à la
main :

```mcfunction
/kill @e[type=armor_stand,tag=cheesepatrol]
```

Le texte du guide intégré au dashboard décrit cette sous-commande comme une bascule. Le code
ne bascule pas.

Chaque porte-armures porte trois tags de commande : `cheesepatrol`, `npc:<uuid-complet>` et
`wp:<index-depuis-0>`, ce qui permet de viser les marqueurs d'un seul NPC avec un sélecteur.
Les étiquettes sont visibles par tous les joueurs : c'est donc un outil d'édition, pas une
décoration.

| Message | Cause |
|---|---|
| `Player only` | pas lancée par un joueur |
| `[CheesePatrol] No waypoints to mark.` | aucune fiche de patrouille, ou liste vide |
| `[CheesePatrol] No NPC selected. …` | aucune sélection |

## `/npcpatrol particles <type>`

```mcfunction
/npcpatrol particles <type>
```

| Argument | Type | Requis | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `type` | mot unique | oui | l'un de 46 noms, complétés par tabulation ; mis en minuscules avant comparaison | `none` |

Définit l'effet de traînée qui suit le NPC pendant que sa patrouille est active. Émis toutes
les 2 ticks, trois bouffées en mouvement et une à l'arrêt.

```mcfunction
/npcpatrol particles dust_cyan
```

```text
[CheesePatrol] Particle trail set to: dust_cyan
```

```mcfunction
/npcpatrol particles none
```

```text
[CheesePatrol] Particle trail disabled.
```

La liste complète, avec ce que chacun rend, est dans
[rendu visuel](visuals.fr.md#les-effets-de-traînée). La complétion par tabulation compare
les préfixes sur cette même liste : taper `dust` puis Tab te donne les huit couleurs de
poussière.

| Message | Cause |
|---|---|
| `[CheesePatrol] Unknown particle type: <type>. Available: none, sparkle, flame, …` | le nom n'est pas dans la liste. L'erreur affiche les 46 |
| `[CheesePatrol] No NPC selected. …` | aucune sélection |

## `/npcpatrol particleheight <offset>`

```mcfunction
/npcpatrol particleheight <offset>
```

| Argument | Type | Requis | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `offset` | double | oui | `-5.0`–`5.0` | `0.0` |

```text
[CheesePatrol] Particle Y offset set to 0.4
```

Décalage vertical par rapport à l'ancrage naturel de la traînée, le milieu du corps du NPC
(`y + height * 0.5`). Positif vers le haut. Prend effet au tick de particules suivant.

## `/npcpatrol particledepth <depth>`

```mcfunction
/npcpatrol particledepth <depth>
```

| Argument | Type | Requis | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `depth` | double | oui | `-5.0`–`5.0` | `0.0` |

```text
[CheesePatrol] Particle depth offset set to 0.6 (negative=ahead, positive=behind)
```

Décalage le long de l'axe avant du NPC. **Positif place la traînée derrière le NPC, négatif
devant.** L'avant est pris sur le vecteur de vélocité en mouvement et sur le yaw du corps à
l'arrêt : la traînée pivote donc quand le NPC se tourne vers un joueur.

## `/npcpatrol particlerange <range>`

```mcfunction
/npcpatrol particlerange <range>
```

| Argument | Type | Requis | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `range` | entier | oui | `16`–`512` blocs | `16` |

```text
[CheesePatrol] Particle visibility range set to 48 blocks
```

La traînée est envoyée joueur par joueur, et uniquement à ceux situés dans cette distance
sphérique du point d'ancrage. Cela borne la bande passante, pas la luminosité : un paquet
par joueur éligible, par position et par tick de particules, donc une portée de 512 blocs
sur un serveur chargé signifie que chaque joueur proche reçoit la traînée de chaque NPC.
Monte-la pour un NPC emblématique, laisse-la à 16 pour un garde de village.

La portée ne concerne que la traînée. L'aperçu de trajet de `show` est toujours envoyé au
seul joueur qui l'a demandé, à n'importe quelle distance jusqu'à 512 blocs dans la
dimension — le plafond vanilla des particules envoyées avec `force = true`.

---

## Ce qui n'a pas de commande

À savoir, pour ne pas le chercher :

- **Pas de rechargement.** `cheesepatrol_data.json` est lu une seule fois, à
  `SERVER_STARTING`. Les modifications à la main sur un serveur en marche sont ignorées puis
  écrasées par la sauvegarde suivante.
- **Pas de bascule debug.** Le drapeau `debug` qui contrôle la journalisation verbeuse des
  patrouilles n'existe que dans le fichier. Voir
  [configuration](configuration.fr.md#debug).
- **Pas d'insertion ni de réordonnancement.** `add` ajoute à la fin ; corriger le milieu
  d'un circuit, c'est supprimer et réajouter la queue.
- **Pas de `markers off`**, comme décrit plus haut.
- **Pas de nom de patrouille.** Les patrouilles sont identifiées par l'UUID du NPC partout.
- **Pas de création, d'édition ni de suppression depuis le dashboard.** Il lit et téléporte,
  rien d'autre. Voir [dashboard](dashboard.fr.md).

---

← [Premiers pas](getting-started.fr.md) · [Configuration](configuration.fr.md) →
