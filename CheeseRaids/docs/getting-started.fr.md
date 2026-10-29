[← CheeseRaids](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](getting-started.md)

# Prise en main

L'objectif de ce parcours : partir d'un serveur où le jar vient d'être installé et arriver à
**une Invasion de Hoopa complète** — une vague combattue dans l'overworld, un héraut
révélé, une faille franchie, un parkour traversé, un boss tué et des portails ouverts. À la
dernière étape, tu auras vu chacune des phases du mod.

> ⚠️ Personne ne l'a encore fait. Le mod n'a été déployé que sur des instances de test et
> aucun raid n'a été joué : attends-toi à des problèmes que cette page ne couvre pas.
> Fais-le sur un monde de test, avec un compte dont tu peux perdre l'inventaire, avant de le
> faire avec des joueurs.

Compte une heure. L'essentiel part en relevé de coordonnées et en placement du NPC ; le raid
lui-même prend une dizaine de minutes.

---

## Avant de commencer

Il te faut un serveur avec CheeseDash, CheeseGuard, CheeseRaids, Cobblemon et EasyNPC
installés, et le journal de démarrage qui montre :

```text
[CheeseRaids] initialized — modular raid framework ready.
[CheeseRaids] Loaded 1 raid definition(s), 0 active instance(s)
```

Si l'une des deux lignes manque, arrête-toi ici et reprends
[l'installation](installation.fr.md#vérifier-le-chargement). Il te faut aussi les droits
d'opérateur (niveau de permission 2) et, pour l'étape 8, une équipe Pokémon capable de
battre un Hoopa Déchaîné niveau 80.

## 1. Choisis tes deux emplacements dans l'overworld

Vole jusqu'à un endroit dégagé. Il te faut deux points, à 10 ou 30 blocs l'un de l'autre :

- **l'arène de vague** — où les Pokémon de l'invasion apparaissent et sont combattus. Les
  apparitions par défaut exigent une zone dégagée d'au moins 13×13 avec de la hauteur ;
  l'alpha fait 1,8 fois la taille normale ;
- **le perchoir du héraut** — où le NPC Ho-Oh apparaît une fois la vague tombée.

Choisis-les loin de tout ce qui te tient à cœur. Le raid force un orage et met le monde
entier à minuit pour sa durée, et ne restaure jamais l'heure.

Relève les coordonnées à chaque point :

```mcfunction
/data get entity @s Pos
```

```text
RoiCheese has the following entity data: [112.4d, 71.0d, -2043.7d]
```

Note les deux, et deux autres tant que tu es sur place : un **ancrage de portails** avec 10
blocs de rayon dégagé, et un **point de sortie** sur sol dur où les joueurs seront déposés à
la fin. Quatre positions en tout.

## 2. Place le NPC héraut

Fais apparaître un EasyNPC là où tu l'as choisi, avec l'outillage d'EasyNPC, et donne-lui
une allure de Ho-Oh si tu veux l'ambiance. CheeseRaids ne le crée pas — il ne fait que
révéler et masquer l'EasyNPC qu'il trouve à proximité : le NPC est à toi de placer, et à toi
de garder.

Monte dessus et relève à nouveau ta position. C'est cette coordonnée exacte qui va dans la
configuration ; le rayon de recherche est de 10 blocs, donc être proche suffit, mais être
exact supprime une variable.

## 3. Écris les quatre positions dans la configuration

Ouvre `config/cheeseraids/raids/hoopa.json`. Trouve les cinq objets de position et
renseignes-en quatre — laisse `arena.center` tranquille, il est à l'intérieur de la faille
générée et son défaut est correct.

```json
{
  "wave":   { "arena":        { "world": "minecraft:overworld", "x": 112.0, "y": 71.0, "z": -2043.0, "yaw": 0.0, "pitch": 0.0 } },
  "npc":    { "pos":          { "world": "minecraft:overworld", "x": 118.0, "y": 71.0, "z": -2050.0, "yaw": 180.0, "pitch": 0.0 } },
  "reward": { "portalAnchor": { "world": "minecraft:overworld", "x": 100.0, "y": 71.0, "z": -2030.0, "yaw": 0.0, "pitch": 0.0 } },
  "exitPos":                  { "world": "minecraft:overworld", "x": 120.5, "y": 71.0, "z": -2060.5, "yaw": 90.0, "pitch": 0.0 }
}
```

Garde le reste du fichier tel quel — ci-dessus, seules les clés que tu modifies, pas le
fichier entier. La [configuration](configuration.fr.md) documente chaque autre clé, et la
[calibration](calibration.fr.md) détaille ce que chaque position doit satisfaire.

Puis recharge :

```mcfunction
/cheeseraids reload
```

```text
Définitions de raids rechargées.
```

**Lis la console tout de suite.** Cette réponse est inconditionnelle : elle dit la même
chose que ton JSON ait été accepté ou non. Une ligne `FAILED to parse raid def` signifie que
ton édition a été rejetée et que l'ancienne définition est toujours chargée.

## 4. Vérifie le héraut

```mcfunction
/cheeseraids herald hoopa info
```

```text
Héraut attendu: 118, 71, -2050 dans minecraft:overworld
EasyNPC dans 10 blocs: 1
  • easy_npc:humanoid_slim @ 118, 71, -2050 (dist 0.5, invisible=true)
```

`EasyNPC dans 10 blocs: 1` ou plus, c'est ce que tu veux. Zéro signifie que la révélation ne
trouvera rien — le [dépannage](troubleshooting.fr.md#le-héraut-napparaît-jamais) distingue
les quatre raisons possibles.

Confirme que la bascule fonctionne :

```mcfunction
/cheeseraids herald hoopa show
```

```text
1 EasyNPC rendu(s) visible(s).
```

Le NPC doit apparaître avec un contour lumineux. Masque-le ensuite, pour que le raid puisse
le révéler de façon spectaculaire :

```mcfunction
/cheeseraids herald hoopa hide
```

## 5. Câble le dialogue du héraut

C'est l'étape facile à oublier, et celle qui casse le raid pour les joueurs.

CheeseRaids ne donne **aucun dialogue** au NPC. Quand le raid dit aux joueurs *parlez-lui*,
rien ne se passera si tu n'as pas configuré, dans EasyNPC, une action sur ce NPC qui lance :

```mcfunction
/cheeseraids enter hoopa
```

Fais-le maintenant, dans l'éditeur de dialogues et d'actions d'EasyNPC. Rien dans
CheeseRaids ne le fait pour toi, et rien ne vérifie que tu l'as fait.

Tu peux sauter cette étape pour ce premier essai : `enter` n'exige aucune permission, donc
les joueurs peuvent taper la commande eux-mêmes. Sache seulement que le raid leur dira de
parler à un NPC qui ne répond pas.

## 6. Vérifie l'ancrage des portails

```mcfunction
/cheeseraids testreward hoopa
```

```text
Test récompense: 1/1 portail(s) Hoopa à 100, 71, -2030 (minecraft:overworld)
```

Un portail et des feux d'artifice à l'ancrage. `0/1` signifie que CobbleSafari est absent ou
a refusé — le raid se termine quand même, simplement sans portails.
`Monde d'ancrage introuvable` signale une faute dans l'identifiant de monde.

La calibration est faite. Tout ce qui suit, c'est le raid lui-même.

## 7. Lance le raid et combats la vague

Reste près de l'arène de vague — le chunk doit être chargé pour que l'apparition
réussisse — puis :

```mcfunction
/cheeseraids start hoopa
```

```text
Raid 'hoopa' démarré.
```

En moins d'une seconde : une ligne de chat mauve clair, un grand titre violet, et le ciel
qui bascule dans un orage de minuit. Cinq secondes plus tard la vague apparaît, et la
console le confirme :

```text
[CheeseRaids] Wave for hoopa: spawned 5/5 mob(s) directly.
```

`spawned 0/5` signifie que l'apparition a été refusée ; la ligne de journal précédente dit
pourquoi, et le [dépannage](troubleshooting.fr.md#la-vague-napparaît-jamais) couvre les trois
causes.

Tu dois voir un Hoopa Déchaîné luisant et surdimensionné, ceint de particules de portail en
spirale, et quatre Ténéfix marqués de particules de sorcière. Ils sont incapturables :
lancer une ball ne fait rien.

Tue l'alpha — celui qui brille. C'est le raccourci : à l'instant où il meurt, toute la vague
se termine et les Ténéfix restants sont retirés.

```text
L'Alpha Hoopa s'enfuit !
Il s'enfouit dans sa dimension... la faille s'ouvre !
```

Tout tuer fonctionne aussi, et la vague avance de force après `wave.timeoutSeconds` (180 par
défaut) si tu te contentes de partir.

## 8. Entre dans la faille

Pendant les cinq secondes suivantes, la révélation du héraut monte en puissance : ambiance
de caverne, particules qui s'accumulent, deux éclairs, puis un flash et le NPC apparaît.

```text
Le héraut Ho-Oh surgit dans un éclair ! Parlez-lui pour entrer dans la faille.
```

Parle-lui si tu as câblé son dialogue à l'étape 5. Sinon :

```mcfunction
/cheeseraids enter hoopa
```

Tu arrives à 75 blocs du centre de la faille, sur une plateforme de purpur 3×3 sous un
anneau flottant or et magenta, tourné vers l'intérieur et vers le noir. Pendant une seconde
environ, une colonne de portail se resserre autour de toi. Puis le parkour se matérialise
devant toi, huit blocs par tick, sur une gamme qui monte, jusqu'à ce qu'un bouquet final à
l'autre extrémité le raccorde au disque du boss.

Saute vers l'intérieur. Cinq zones à la suite : cavernes d'amétriste, pont de glace, mousse
et toiles d'araignée, gorge de chute, aiguilles de cristal. Tombe et tu es remis sur ton
dernier checkpoint — ce sont les dalles de lanterne aquatique, toutes les six plateformes, et
chacune s'annonce dans la barre d'action :

```text
✔ Checkpoint atteint !
```

À l'arrivée sur le disque :

```text
Vous avez atteint le cœur de la faille !
```

Si tu es vraiment bloqué, `/cheeseraids enter hoopa` à nouveau te téléporte à ton dernier
checkpoint sans rien régénérer.

## 9. Combats le boss

Trente secondes après la première arrivée — `boss.graceSeconds` — ou dès que tout le monde
est arrivé :

```text
Quelque chose d'immense s'éveille au cœur de la faille...
```

Puis un crescendo de 44 ticks : un anneau de portail qui s'élargit, un grondement de dragon
qui monte en hauteur, deux éclairs, et au tick 36 le boss est tiré à travers.

```text
⚡ Le gardien de la faille SURGIT ! ⚡
```

Un Hoopa Déchaîné à 1,5× niveau 80, plus trois niveaux par participant supplémentaire. C'est
un combat Cobblemon ordinaire : engage-le et bats-toi. Il n'y a pas de mécanique de raid ni
de barre de vie partagée — avec plusieurs joueurs sur le disque, le premier à engager
récupère le combat.

Gagne et le reste se fait tout seul. Si tu perds ou si le combat dépasse
`boss.combatTimeoutSeconds` (15 minutes), le raid éjecte tout le monde et nettoie sans
récompense.

## 10. La récompense

```text
🏆 VICTOIRE !
La faille s'effondre — l'invasion est repoussée !
```

Luminescence, régénération et chute lente, particules de totem, 4,5 secondes de
célébration. Puis tu es téléporté vers `exitPos`, des portails s'ouvrent autour de
`reward.portalAnchor` avec des feux d'artifice à chacun, et trois autres partent au-dessus
de ta tête.

La console boucle la séquence :

```text
[CheeseRaids] Rewards for hoopa: 1/1 portal(s) opened, 1 winner(s)
[CheeseRaids] Raid 'hoopa' ended and cleaned up.
```

```mcfunction
/cheeseraids status
```

```text
Raids:
• hoopa [idle]
```

Voilà un raid complet. La dimension de faille reste derrière, vide et désactivée, prête pour
la prochaine fois.

---

## Et ensuite

**Planifie-le.** Mets `schedule.autoTrigger` à `true` avec une heure et une minute et le
raid se lance tout seul. Ce n'est pas un cron : un serveur arrêté pendant cette minute rate
le déclenchement. [Détails](configuration.fr.md#schedule).

**Règle la difficulté.** `arena.starRadius` allonge le parkour, `arena.checkpointEvery` rend
les chutes plus ou moins punitives, `boss.baseLevel` et `levelPerExtraPlayer` fixent le
combat, `wave.spawns` fixe l'ouverture. [Toutes les clés](configuration.fr.md).

**Surveille-le depuis le dashboard.** Le module `raids` affiche la phase et une table de
participants en direct, et peut démarrer, arrêter et recharger.
[API dashboard](dashboard.fr.md).

**Lis les manques avant de le lancer pour des joueurs.** Cinq comptent : le héraut a besoin
d'un dialogue que tu câbles toi-même, le combat de boss n'a pas de mécanique de raid, les
récompenses ne sont pas conditionnées à la participation, `stop` ne téléporte personne, et
l'annonce Discord n'écrit que dans le journal.
[Tous, en un seul endroit](hoopa-invasion.fr.md#ce-qui-est-incomplet).

---

← [Installation](installation.fr.md) · [Commandes](commands.fr.md) →
