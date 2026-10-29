[← AetherialBridge](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](getting-started.md)

# Premiers pas

À la fin de cette page, tu te tiendras debout sur une île flottante, dans une dimension
nommée `cheeseguard:islands`, avec l'overworld vanilla exactement dans l'état où tu
l'as laissé. L'ensemble prend environ cinq minutes sur un serveur qui fait déjà tourner
CheeseDash et CheeseGuard, et aucune étape ne demande d'éditer un fichier de
configuration à la main.

Rien ici n'est une commande AetherialBridge, puisque le mod n'en a aucune. Ce
qu'AetherialBridge apporte, c'est une carte supplémentaire dans un écran du dashboard
qui appartient déjà à CheeseGuard. Le travail est celui de CheeseGuard ; le terrain est
celui du datapack.

---

## 1. Démarre le serveur et lis trois lignes de log

Installe d'abord les quatre jars ([installation](installation.fr.md#installation)), puis
démarre le serveur et cherche ces lignes, dans cet ordre :

```text
[AetherialBridge] Initialized — registers 'aetherial-islands:floating' GenTypeProvider with CheeseGuard.
[CG] GenTypeRegistry discovered 1 provider(s): [aetherial-islands:floating]
[CheeseDash] ✅ Started on port 44311 — http://localhost:44311/
```

Si la deuxième ligne dit `discovered 0 provider(s)`, arrête-toi là : la liste de
l'étape 3 ne contiendra pas l'entrée dont tu as besoin. Va voir
[dépannage](troubleshooting.fr.md#le-type-de-génération-napparaît-pas-dans-le-dashboard).

Si le serveur ne finit jamais de démarrer et que le log s'arrête sur une erreur de
dépendance nommant `cheeseguard` ou `mr_aetherial_islands`, c'est qu'un jar requis
manque. Les deux sont déclarés dans `depends`, donc Fabric interrompt le lancement
plutôt que de démarrer sans eux.

## 2. Ouvre le dashboard et choisis ton joueur admin

Va sur `http://<serveur>:44311`, connecte-toi avec ton token, et sélectionne ton pseudo
en jeu sous **Admin Player** dans l'en-tête. Le dashboard utilise ce nom pour toute
action qui cible un joueur, dont le bouton **TP** que tu presseras à l'étape 5. Si tu
l'oublies, ce bouton affiche un toast d'erreur au lieu de téléporter qui que ce soit.

Ouvre ensuite **Dimensions** dans la barre latérale. Tu dois y voir tes mondes
existants (`minecraft:overworld`, `minecraft:the_nether`, `minecraft:the_end`), chacun
repris automatiquement par CheeseGuard comme une entrée intégrée.

## 3. Crée la dimension

Clique sur **New dimension**. L'assistant comporte quatre étapes.

**Étape 1, le type.** Saisis `islands` dans le champ du nom. En dessous se trouve une
grille de cartes de types de génération : cinq intégrées (`VOID`, `FLAT`, `NORMAL`,
`NETHER`, `END`), puis **Aetherial Islands**, avec un glyphe ☁ et
`aetherial-islands:floating` écrit dessous comme identifiant. `VOID` est sélectionné par
défaut : clique donc sur la carte Aetherial Islands et vérifie que sa bordure passe à
l'ambre.

Les cartes plugin viennent toujours après les intégrées, dans l'ordre où CheeseGuard
les a découvertes. Si la carte est absente, la poignée de main de l'entrypoint a échoué,
et rafraîchir la page n'y changera rien.

**Étape 2, le monde.** Laisse la seed vide, sauf si tu veux un monde reproductible.
CheeseGuard génère une seed aléatoire non nulle quand tu laisses le champ vide, et ça
compte plus qu'il n'y paraît : une seed à `0` ferait silencieusement réutiliser à la
dimension la seed de terrain de l'overworld. Le champ *flat layers* reste masqué,
puisqu'il ne concerne que `FLAT`.

**Étape 3, les options.** Les valeurs par défaut conviennent pour un premier essai :
apparition des monstres activée, raid dens activés, PvP désactivé, keep-inventory
désactivé, structures désactivées. Deux méritent une réflexion ici :

- **Generate structures.** Désactivé est plus sûr pour un premier essai. Les structures
  vanilla sont placées sur du terrain de forme overworld, et un village qui tente de
  s'installer sur une île flottante donne des résultats allant de l'amusant à
  l'inutilisable.
- **Raid dens** (🐲). Laisse la case cochée, sauf si CobblemonRaidDens est installé et
  que tu ne veux pas de dens dans cette dimension. La décocher est ce qui fait annuler
  le placement des dens par [`RaidDenFeatureMixin`](worldgen.fr.md#les-raid-dens-cobblemon)
  pour cet identifiant de monde, et c'est le seul des cinq mixins que tu pilotes
  depuis le dashboard.

**Étape 4, la création.** Relis le résumé et confirme.

## 4. Vérifie que le générateur a bien été construit sur le datapack

Le log du serveur répond en moins d'une seconde :

```text
[AetherialBridge] Built NoiseChunkGenerator for dim 'islands' using aetherial_islands:floating (min_y=-64, height=384)
[CG] Runtime dimension created: cheeseguard:islands
[CG] Created custom dimension: islands (NORMAL)
```

Trois choses à y lire :

- La première ligne est la preuve que l'espace de noms du datapack s'est résolu. Sans
  elle, tu as obtenu une dimension vide à la place, et le log porte une ligne
  `falling back to VOID`.
  [Le dépannage](troubleshooting.fr.md#la-dimension-est-générée-vide) explique comment
  s'en sortir.
- `min_y=-64, height=384` sont lus en direct dans les noise settings chargées : ce sont
  donc les vraies valeurs de ton datapack, pas des constantes de ce mod.
- `(NORMAL)` n'est pas une erreur. Un type de génération plugin stocke `NORMAL` dans le
  champ `genType` et la vraie sélection dans `customGenTypeId` ; `genType` ne survit
  que pour choisir le type de dimension et un spawn Y de repli.

Le dashboard navigue tout seul vers la page de détail de la nouvelle dimension. Son
spawn Y affiche **100**, soit le `defaultSpawnY()` du provider et non le 80 habituel de
CheeseGuard pour une dimension `NORMAL`. 100 se situe au-dessus de la bande d'îles et
bien au-dessus de la mer à y=-30 : tu arrives donc en plein air et tu tombes de peu, au
lieu d'apparaître dans la roche.

## 5. Téléporte-toi

Presse le bouton **TP** sur la ligne de la dimension ou dans l'en-tête de sa page de
détail. Le dashboard envoie le joueur admin sélectionné au point d'apparition de la
dimension.

L'équivalent depuis la console du serveur, si tu préfères éviter le dashboard :

```mcfunction
/execute in cheeseguard:islands run tp TonPseudo 0.5 100 0.5
```

Attends-toi à un court figement à la première entrée. Les chunks autour du spawn sont
générés pour la première fois, et la passe de fond marin forcé tourne par chunk en plus
de la passe de bruit normale.

## 6. Regarde autour, et vérifie les deux choses faciles à manquer

Monte en vol. Tu dois voir des îles à plusieurs hauteurs, empilées en quatre couches de
bruit, avec de l'air libre entre elles et la palette de biomes vanilla posée sur leurs
sommets : forêt sur une île, taïga sur la suivante.

Puis regarde en bas et vérifie ces deux points, parce que c'est à ça que servent les
mixins :

- **À y=-30 il y a une surface d'océan**, et en dessous un fond de sable, gravier,
  argile, terre et prismarine, avec des amas de coraux, du varech, des herbes aquatiques
  et des éponges de temps en temps. La bedrock se trouve à y=-64. Si tu trouves plutôt
  un puits vide jusqu'à la bedrock, le niveau de la mer de ton datapack est au-dessus de
  `-25` et `NoiseChunkGeneratorMixin` ne s'est jamais déclenché.
- **Pas de lave.** Ni lac de lave en surface, ni source de lave traversant l'eau. Si tu
  trouves l'un ou l'autre, le même seuil l'explique.

```mcfunction
/execute in cheeseguard:islands run tp TonPseudo 0.5 -32 0.5
```

Ça te place deux blocs sous la surface de la mer — le dernier bloc d'eau est à y=-30, et
en dessous le mixin réécrit chaque colonne, si bien que y=-32 est de l'eau partout —, le moyen le plus rapide de voir le fond.

## 7. Et ensuite

À partir d'ici, la dimension est une dimension CheeseGuard ordinaire. Sa page de détail
te donne la bordure de monde, les snapshots, le mode par joueur, les verrous de temps et
de météo, et les commandes exécutées à l'entrée et à la sortie. Rien de tout cela ne
vient d'AetherialBridge, et tout est documenté avec
[CheeseGuard](../../CheeseGuard/).

Deux cas où tu reviens vers la documentation de ce mod :

- Tu veux comprendre de quoi le terrain est réellement fait, ou tu dois modifier le fond
  marin. [Génération du monde](worldgen.fr.md).
- Quelque chose a cassé après une mise à jour de mod.
  [Architecture](architecture.fr.md#où-se-situe-le-couplage) nomme les lignes exactes qui
  se lient à du code tiers, dans l'ordre où les vérifier.

---

← [Installation](installation.fr.md) · [Génération du monde](worldgen.fr.md) →
