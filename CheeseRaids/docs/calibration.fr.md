[← CheeseRaids](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](calibration.md)

# Calibrer un raid

> ⚠️ Rien de cette page n'a été validé par un raid réellement joué. La procédure
> ci-dessous est déduite du code, pas de l'expérience.

Un `config/cheeseraids/raids/hoopa.json` neuf contient quatre positions qui valent toutes
`minecraft:overworld` en `0.5 / 65 / 0.5` par défaut. C'est l'origine du monde, très
probablement sous la terre, et rien du raid ne fonctionne avant que tu les remplaces par de
vraies coordonnées de ta carte. C'est la seule étape de mise en place que le mod ne peut pas
faire à ta place.

Les quatre positions, et à quoi chacune sert :

| Chemin JSON | Ce qui s'y trouve | Conséquence si c'est faux |
|---|---|---|
| `wave.arena` | l'endroit de l'overworld où la vague de Pokémon apparaît | la vague apparaît à l'origine, ou pas du tout |
| `npc.pos` | là où se tient le héraut Ho-Oh (EasyNPC) | le héraut n'est jamais trouvé, et les joueurs sont renvoyés vers une commande |
| `reward.portalAnchor` | là où les portails de victoire s'ouvrent | les portails s'ouvrent à l'origine, ou sont ignorés |
| `exitPos` | là où les joueurs sont téléportés à la fin du raid | les joueurs sont éjectés à l'origine, éventuellement dans des blocs |

`arena.center` est une cinquième position, mais elle se trouve dans la faille générée et son
défaut (`cheeseguard:hoopa_rift` en `0.5 / 100 / 0.5`) est déjà correct. N'y touche pas, sauf
si tu changes aussi `arena.riftDimId` — les deux doivent s'accorder, parce que le code
construit l'identifiant de monde comme `cheeseguard:` + `riftDimId` et lit le centre de la
plateforme dans le champ `arena.center`.

---

## Étape 1 — relève les coordonnées en jeu

Vole jusqu'à chaque emplacement et lis ta position. Vanilla te donne trois moyens ; prends
celui que tu préfères.

```mcfunction
/data get entity @s Pos
```

```text
RoiCheese has the following entity data: [112.4d, 71.0d, -2043.7d]
```

F3 affiche aussi **XYZ** et, sous **Block**, les coordonnées entières du bloc. Le mod arrondit
tes coordonnées au bloc inférieur (`BlockPos.ofFloored`) : les décimales ne comptent donc que
pour `exitPos`, où le joueur est téléporté au double exact.

Il te faut aussi l'identifiant de dimension, que F3 affiche à droite sous le nom du monde.
Pour une dimension CheeseGuard il ressemble à `cheeseguard:<id>`, pour le vanilla à
`minecraft:overworld`.

### Ce qu'il faut regarder à chaque endroit

**`wave.arena`** — choisis un sol dégagé et plat, avec au moins `wave.spread` blocs de marge
dans chaque direction horizontale (6 par défaut, soit une zone de 13×13), et de la hauteur.
Les mobs apparaissent en `arena.y + 1`, dispersés au hasard dans ±`spread` en X et Z. L'alpha
apparaît à l'échelle 1,8 : il a besoin de place en hauteur. Les joueurs combattent la vague
ici, donc ce doit être un endroit accessible et un endroit qu'un orage ne va pas gâcher —
`wave.overworldStorm` force `weather thunder` et `time set midnight` dans ce monde pour toute
la durée du raid.

**`npc.pos`** — place-toi exactement là où le héraut doit apparaître. La révélation ne trouve
que les entités EasyNPC situées **dans les 10 blocs** de cette position, mesurés comme une
boîte alignée sur les axes et élargie de 10 dans chaque direction, et seulement dans le monde
désigné par `npc.pos.world`. Pose le NPC d'abord, puis relève tes propres coordonnées en te
tenant dessus.

**`reward.portalAnchor`** — les portails sont placés sur un cercle de rayon `portalSpacing`
(4,0 par défaut) autour de ce point, un par portail gagné, régulièrement espacés. Prévois un
cercle dégagé de 10 blocs. Le chemin de secours de CobbleSafari ne fonctionne que dans
l'overworld : un ancrage dans une autre dimension peut n'ouvrir silencieusement aucun
portail.

**`exitPos`** — là où tout le monde atterrit après la victoire. Un endroit sûr et au sol,
idéalement près du spawn. Renseigne aussi `yaw` et `pitch` si l'orientation des joueurs
t'importe.

## Étape 2 — place le NPC héraut

Le héraut est une entité [EasyNPC](https://modrinth.com/mod/easy-npc). CheeseRaids ne la crée
pas ; il ne fait que basculer la visibilité de l'EasyNPC qu'il trouve près de `npc.pos`.
Fais-en apparaître un avec l'outillage d'EasyNPC, habille-le en Ho-Oh et laisse-le là en
permanence — le raid le masque entre deux sessions plutôt que de le supprimer.

Le code filtre les candidats sur le préfixe d'identifiant de registre `easy_npc` : n'importe
quel type d'entité EasyNPC convient donc. `npc.entityType` dans la configuration
(`easy_npc:humanoid_slim` par défaut) **ne sert pas** à la recherche : il est seulement
substitué comme `{type}` dans les modèles facultatifs `showCommand` / `hideCommand`.

## Étape 3 — écris les coordonnées dans le fichier

Édite `config/cheeseraids/raids/hoopa.json`. Seuls les cinq objets de position demandent une
intervention ; tout le reste a un défaut fonctionnel.

```json
{
  "id": "hoopa",
  "wave": {
    "arena": { "world": "minecraft:overworld", "x": 112.0, "y": 71.0, "z": -2043.0, "yaw": 0.0, "pitch": 0.0 }
  },
  "npc": {
    "pos": { "world": "minecraft:overworld", "x": 118.0, "y": 71.0, "z": -2050.0, "yaw": 180.0, "pitch": 0.0 }
  },
  "reward": {
    "portalAnchor": { "world": "minecraft:overworld", "x": 100.0, "y": 71.0, "z": -2030.0, "yaw": 0.0, "pitch": 0.0 }
  },
  "exitPos": { "world": "minecraft:overworld", "x": 120.5, "y": 71.0, "z": -2060.5, "yaw": 90.0, "pitch": 0.0 }
}
```

Garde le reste du fichier tel que le mod l'a écrit. Chaque champ de `RaidDefinition` a un
défaut côté Java, donc une clé omise est complétée depuis la classe — mais le fichier que le
mod écrit au premier démarrage est complet, et l'éditer sur place expose à moins d'erreurs
que de l'élaguer.

Puis recharge :

```mcfunction
/cheeseraids reload
```

```text
Définitions de raids rechargées.
```

Un rechargement relit chaque `*.json` de `config/cheeseraids/raids/`. Si ton édition a cassé
la syntaxe JSON, le fichier est **laissé intact** et la console le dit :

```text
[CheeseRaids] FAILED to parse raid def .../hoopa.json — fix the JSON syntax and
/cheeseraids reload. Left untouched. (...)
```

C'est volontaire : un fichier mal formé n'est jamais écrasé par les valeurs par défaut, tu ne
perds donc pas ton travail. Mais l'ancienne définition reste chargée, ce qui veut dire qu'un
rechargement peut sembler réussir alors que ton changement n'a rien fait. Lis la console après
chaque rechargement.

## Étape 4 — vérifie chaque position

### Le héraut

```mcfunction
/cheeseraids herald hoopa info
```

```text
Héraut attendu: 118, 71, -2050 dans minecraft:overworld
EasyNPC dans 10 blocs: 1
  • easy_npc:humanoid_slim @ 118, 71, -2050 (dist 0.5, invisible=true)
```

`EasyNPC dans 10 blocs: 0` signifie que la révélation ne trouvera rien. Le diagnostic élargit
aussi sa recherche à 128 blocs et liste ce qu'il y trouve, sous l'étiquette *mauvaise
position ?* — c'est ainsi que tu attrapes une faute de frappe dans les coordonnées.
`⚠ AUCUN EasyNPC chargé à proximité` signifie soit que le NPC est ailleurs, soit que le chunk
n'est pas chargé.

Le chargement des chunks est un vrai piège ici. Le héraut vit dans l'overworld, à un endroit
où aucun joueur ne se trouve peut-être, et un chunk non chargé n'a aucune entité à trouver. Si
`info` ne rapporte rien alors que tu es certain que le NPC est là, place-toi à côté de lui et
relance la commande.

Vérifie ensuite que la bascule fonctionne réellement :

```mcfunction
/cheeseraids herald hoopa show
```

```text
1 EasyNPC rendu(s) visible(s).
```

Le NPC doit apparaître avec un contour lumineux. `0 EasyNPC rendu(s) visible(s).` avec un
`info` correct désigne l'API de visibilité d'EasyNPC plutôt que tes coordonnées — voir
[les intégrations](integrations.fr.md#easynpc).

### Les portails de récompense

```mcfunction
/cheeseraids testreward hoopa
```

Cette commande ouvre `max(1, reward.minPortals)` portails autour de l'ancrage et tire des
feux d'artifice, sans lancer de raid. La réponse donne le compte et la position :

```text
Test récompense: 1/1 portail(s) Hoopa à 100, 71, -2030 (minecraft:overworld)
```

`0/1` signifie que CobbleSafari a refusé ou qu'il est absent. `Monde d'ancrage introuvable:
<id>` signifie que `reward.portalAnchor.world` ne désigne aucun monde chargé — vérifie la
faute de frappe, ou que la dimension CheeseGuard que tu as visée existe bien.

### L'arène de vague et la sortie

Aucune des deux n'a de commande de test à blanc. Vole jusqu'aux deux et confirme à l'œil : de
la marge à l'arène de vague, du sol dur à la sortie. La première fois que tu lances le raid
pour de vrai, surveille la console — un mauvais monde de vague est signalé bruyamment, avec la
liste des mondes qui existent :

```text
[CheeseRaids] wave arena world 'minecraft:overwrld' NOT FOUND — fix wave.arena.world.
Available: minecraft:overworld minecraft:the_nether minecraft:the_end cheeseguard:hoopa_rift
```

## Étape 5 — une répétition générale

La calibration n'est pas terminée avant qu'un raid ait tourné de bout en bout. Les quatre
positions renseignées et le héraut vérifié :

```mcfunction
/cheeseraids start hoopa
```

Suis ensuite [la séquence Hoopa](hoopa-invasion.fr.md) et surveille les cinq choses qui
peuvent encore être fausses alors que les coordonnées sont bonnes :

1. **La vague n'apparaît pas.** L'appel d'apparition de Cobblemon a été refusé. La console
   porte la raison (`spawnEntity refused`, `Cobblemon API unavailable`).
2. **Le héraut n'apparaît jamais.** Traité plus haut ; le raid ne s'arrête pas net, il
   retombe sur une annonce de `/cheeseraids enter hoopa` dans le chat.
3. **La faille ne s'ouvre pas.** CheeseGuard n'a pas pu créer `cheeseguard:hoopa_rift`.
   `Failed to create rift dimension` est journalisé avec la cause.
4. **Le parkour est inatteignable ou infaisable.** `arena.starRadius` (75 par défaut) est la
   distance du centre à la plateforme de départ d'une branche ; la géométrie en découle. La
   faisabilité des sauts a été vérifiée par simulation, jamais par un joueur.
5. **Le boss n'apparaît pas, ou disparaît aussitôt.** Le fournisseur dégage une poche d'air
   dimensionnée depuis `boss.baseScale` avant l'apparition, et refait apparaître le boss
   jusqu'à deux fois s'il disparaît avant le début d'un combat. Si ça échoue encore,
   `Boss spawn FAILED` est journalisé.

## Changer l'identifiant de la dimension de faille

`arena.riftDimId` vaut `hoopa_rift` par défaut, ce qui donne l'identifiant de monde
`cheeseguard:hoopa_rift`. Si tu le changes, change aussi `arena.center.world` en
conséquence : le générateur résout la faille par concaténation et le centre par le champ
`center` — l'un ne découle pas de l'autre. Une incohérence produit une faille qui s'ouvre
mais une plateforme de boss construite et cherchée dans un monde qui n'existe pas.

La dimension est créée à la demande, la première fois qu'un joueur entre, avec
`enabled = false` pour que personne ne puisse y pénétrer hors d'un raid, `pvp = false`,
`mobSpawning = false`, l'heure verrouillée à 18000 (minuit) et la météo verrouillée. Ces
valeurs sont fixées dans le code, pas dans la définition du raid : le fichier de configuration
ne te donne donc aucune prise sur elles. Modifie-les ensuite par la configuration de
dimensions propre à CheeseGuard si tu veux autre chose.

---

← [Configuration](configuration.fr.md) · [Le modèle de raid](raid-model.fr.md) →
