[← AetherialBridge](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](troubleshooting.md)

# Dépannage

La plupart des problèmes avec ce mod relèvent de deux causes : l'espace de noms du
datapack est faux, ou le niveau de la mer est au-dessus de -25. Les symptômes ci-dessous
sont classés à peu près par fréquence.

---

## Le serveur ne démarre pas

**Cause.** Une entrée de `depends` manque. `fabric.mod.json` en déclare cinq, dont deux
sont d'autres mods :

```json
"depends": {
  "fabricloader": ">=0.15.0",
  "fabric-api": "*",
  "minecraft": "~1.21",
  "cheeseguard": "*",
  "mr_aetherial_islands": "*"
}
```

Fabric Loader résout les dépendances avant que le moindre mod s'initialise : le
lancement s'interrompt donc sur une erreur de résolution qui nomme ce qu'il n'a pas pu
satisfaire. Le log s'arrête là, sans aucune ligne `[AetherialBridge]`.

**Remède.** Mets le jar nommé dans `mods/`. Pour `cheeseguard`, souviens-toi que
CheeseGuard dépend lui-même de CheeseDash : il t'en faut donc deux. Pour
`mr_aetherial_islands`, c'est l'identifiant du datapack Aetherial Islands empaqueté en
mod ; le jar contre lequel ce mod a été écrit est
`aetherial-islands-2.0.2-dimscope.jar`.

Retirer le jar de ce mod est aussi un remède légitime si tu ne veux pas de dimensions
flottantes : rien d'autre dans le dépôt n'en dépend.

## Le type de génération n'apparaît pas dans le dashboard

**Cause.** L'entrypoint `cheeseguard-gentype` n'a pas été découvert. CheeseGuard balaie
une seule fois, pendant son propre `onInitialize`, et gèle le registre ensuite. Le log te
dit quel côté a échoué :

```text
[CG] GenTypeRegistry discovered 0 provider(s): []
```

Ça veut dire que CheeseGuard a fait son balayage et n'a rien trouvé. Soit ce mod ne s'est
pas chargé du tout (aucune ligne `[AetherialBridge] Initialized` plus haut dans le log),
soit une ancienne version du jar est installée, dont le `fabric.mod.json` n'a pas
l'entrypoint.

Un identifiant en doublon produit une autre ligne :

```text
[CG] Duplicate GenTypeProvider id 'aetherial-islands:floating' — ignoring
```

Deux jars avec le même identifiant de provider sont installés, très probablement deux
versions de ce mod.

**Remède.** Vérifie qu'il y a exactement un `aetherialbridge-*.jar` dans `mods/`. Puis
redémarre : le registre est construit une seule fois à l'init, donc rafraîchir le
dashboard ne le remplira jamais. Confirme par l'API plutôt que par le navigateur,
puisque la liste déroulante met en cache :

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:44311/api/v1/cheeseguard/dimensions/gen-types
```

## La création de dimension échoue sur « Unknown customGenTypeId »

**Cause.** Le dashboard a envoyé `customGenTypeId: "aetherial-islands:floating"` et
`GenTypeRegistry.get` a renvoyé null, donc `DimensionsApi` a répondu 400. C'est la même
cause racine que le symptôme précédent, prise par l'autre bout : une page de dashboard
chargée quand le provider était enregistré, puis un serveur redémarré sans le jar.

**Remède.** Recharge la page du dashboard, ce qui refait l'appel à `gen-types`. Si
l'entrée a vraiment disparu, voir ci-dessus.

## La dimension est générée vide

**Cause.** Le provider a levé une exception, et CheeseGuard est retombé sur un générateur
plat de vide au lieu de faire échouer la création. Il y a deux messages, et il te faut le
premier pour savoir lequel :

```text
[CG] Plugin gen-type 'aetherial-islands:floating' failed: ChunkGeneratorSettings
'aetherial_islands:floating' not loaded — is the aetherial-islands-2.0.2-dimscope.jar
present in mods/? — falling back to VOID
```

C'est le problème d'espace de noms :
`data/aetherial_islands/worldgen/noise_settings/floating.json` n'est dans aucun datapack
chargé. Le téléchargement Modrinth standard d'Aetherial Islands ne le contient pas,
puisqu'en amont le datapack écrase `minecraft:overworld` à la place. Voir
[l'espace de noms du datapack](installation.fr.md#lespace-de-noms-du-datapack).

```text
[CG] Unknown customGenTypeId 'aetherial-islands:floating' — falling back to VOID
```

C'est le problème de registre : la dimension a été sauvegardée par un serveur qui avait
ce mod, et le serveur actuel ne l'a pas.

**Remède.** Installe le datapack correctement préfixé, ou remets le jar du mod, puis
**supprime et recrée la dimension**. Les chunks déjà générés en vide restent vides : le
générateur ne décide que de l'allure des nouveaux chunks. Si la dimension n'a que
quelques minutes, la supprimer depuis le dashboard et en créer une neuve est le chemin le
plus rapide. Si elle contient déjà des constructions, les outils de snapshot et de reset
de CheeseGuard sont l'alternative, au prix de perdre le terrain généré de toute façon.

## Au fond du monde il y a un puits sec, ou de la lave, au lieu d'un océan

**Cause.** Le niveau de la mer de tes noise settings est à -25 ou au-dessus, donc les
quatre mixins ne se sont jamais déclenchés. Chacun commence par le même test, et le
datapack DimScope est à -30 précisément pour passer dessous.

Sans les mixins, tu obtiens le comportement vanilla dans un terrain qui n'a pas été fait
pour : pas de fond marin forcé, et l'échantillonneur de niveau de fluide vanilla qui met
de la lave partout sous y=-54.

**Remède.** Vérifie `sea_level` dans
`data/aetherial_islands/worldgen/noise_settings/floating.json`. Mets-le sous -25. Changer
le seuil à la place suppose d'éditer le littéral dans quatre fichiers de mixin et de
reconstruire, ce que décrit
[génération du monde](worldgen.fr.md#le-seuil-de-niveau-de-la-mer).

## Des tunnels de lave traversent l'eau

**Cause.** `SpringFeatureMixin` n'a pas annulé les sources de lave. Soit le niveau de la
mer est au-dessus de -25, ce qui est le symptôme précédent, soit le mixin ne s'est pas
appliqué, ce qui aurait plutôt crashé le jeu au lancement. En pratique, c'est toujours le
niveau de la mer.

Une troisième possibilité est un mod qui ajoute sa propre feature plaçant de la lave aux
biomes tagués overworld, via une classe autre que `SpringFeature` ou `LakeFeature`. Ces
deux-là sont les seules supprimées : tout le reste passe.

**Remède.** Confirme d'abord le niveau de la mer. S'il est sous -25 et que tu as encore
de la lave, cherche quelle feature l'a placée : `/locate` ne t'aidera pas, mais regarder
ce que ta liste de mods ajoute aux biomes `#minecraft:is_overworld`, si. Supprimer une
troisième feature demande un nouveau mixin, et `MinerallyFeatureMixin` est le patron à
copier.

## Le serveur se figge ou le watchdog le tue pendant la génération de chunks

**Cause.** Très probablement le balayage de placement de Minerally. Ses features sont
accrochées à chaque biome tagué `#minecraft:is_overworld`, la dimension flottante utilise
la source de biomes overworld vanilla, donc elles suivent, et sur cette géométrie
`PositionChecker.isValidPositionDown` ne converge jamais. Le signe est un log rempli de :

```text
Empty height range: [-55 absolute--60 absolute]
```

suivi d'un message du watchdog sur un tick qui prend trop de temps.

`MinerallyFeatureMixin` existe pour empêcher exactement ça. Si ça se produit quand même,
le mixin ne s'est pas lié, et cet échec est **silencieux** : il est déclaré `@Pseudo`
avec `require = 0`, donc une classe Minerally renommée ou déplacée ne produit aucune
erreur. Les quatre noms de classes qu'il vise sont dans
`MinerallyFeatureMixin.java:26-31`.

**Remède.** Compare ces quatre noms au jar Minerally que tu as installé. S'ils diffèrent,
mets à jour la liste `targets` et reconstruis. Retirer Minerally fonctionne aussi, et se
teste plus vite : si le figement s'arrête, le diagnostic est confirmé.

Ne confonds pas ça avec la lenteur ordinaire d'une première visite. Générer une dimension
flottante est réellement plus lent que générer des chunks d'overworld, parce que le fond
marin forcé écrit jusqu'à une trentaine de blocs par colonne en plus de la passe de bruit
normale. Quelques secondes de saccade à la première entrée sont normales ; une mise à
mort par le watchdog, non.

## Les raid dens n'apparaissent plus dans l'overworld

**Cause.** Quelqu'un a décoché la case 🐲 **Raid dens** sur `minecraft:overworld` dans la
vue des dimensions de CheeseDash. `RaidDenFeatureMixin` lit
`CheeseGuardWorldgenApi.isRaidDensEnabled(worldId)` pour **toutes** les dimensions, pas
seulement les flottantes, et CheeseGuard garde aussi une entrée de config pour les mondes
vanilla. Son javadoc affirme qu'il ne concerne que les dimensions personnalisées ; le
code ne le restreint pas.

**Remède.** Recoche la case. Les nouveaux chunks regénèrent des dens ; les chunks déjà
générés sans en restent là, puisque le placement des features a lieu une fois par chunk.

Le cas inverse a la même explication : si des dens apparaissent dans une dimension où tu
n'en voulais pas, le drapeau est activé par défaut, et rien n'annule le placement avant
que tu le désactives.

## Modifier un fichier de configuration ne change rien

**Cause.** Il n'y a pas de fichier de configuration AetherialBridge. Le mod n'en lit
aucun et n'en écrit aucun. Chercher `config/aetherialbridge.json` ne donnera rien,
puisqu'il n'existe pas.

**Remède.** Trouve le véritable propriétaire du réglage :

| Réglage | Vit dans |
|---|---|
| niveau de la mer, hauteur du monde, forme du terrain | le datapack Aetherial Islands |
| point d'apparition, PvP, difficulté, keep-inventory, verrous de temps et de météo, raid dens, bordure de monde | CheeseGuard, dans `config/cheeseguard_dimensions.json` ou depuis le dashboard |
| port et token du dashboard | CheeseDash, dans `config/cheesedash.json` |
| matériaux du fond marin, taux de décoration, le seuil de -25 | les sources de ce mod. Recompilation |

## Une commande est refusée, ou introuvable

**Cause.** AetherialBridge n'enregistre aucune commande. `/aetherialbridge` et ses
variantes n'existent pas, et n'ont jamais existé.

**Remède.** Les dimensions se créent et se gèrent depuis la vue **Dimensions** de
CheeseDash. Les déplacements sont du vanilla :

```mcfunction
/execute in cheeseguard:islands run tp TonPseudo 0.5 100 0.5
```

L'arbre de commandes `/cg` de CheeseGuard couvre les zones, les events et les mondes de
joueurs, et exige le niveau de permission 2 pour ses branches administratives.

## Le jeu crashe au lancement sur une erreur de mixin

**Cause.** Un des trois mixins visant le vanilla ne s'est pas appliqué.
`aetherialbridge.mixins.json` pose `"required": true` et `defaultRequire: 1` : une méthode
vanilla dont la signature a changé est donc un échec dur et non un saut silencieux. Le
rapport de crash nomme la classe de mixin et la méthode qu'il n'a pas trouvée.

Ça arrive quand le mod tourne sur une version de Minecraft autre que 1.21.1. L'entrée
`depends` dit `~1.21`, ce qui est plus permissif que le mod ne l'est réellement.

**Remède.** Fais-le tourner sur 1.21.1. Porter le mod suppose de mettre à jour les
descripteurs à `NoiseChunkGeneratorMixin.java:45` et `:59`,
`SpringFeatureMixin.java:23` et `LakeFeatureMixin.java:20`, plus le champ `@Shadow` à
`NoiseChunkGeneratorMixin.java:41`, et de reconstruire contre les nouveaux mappings Yarn.

## Les joueurs arrivent dans la roche, ou tombent dans le vide

**Cause.** Le spawn Y n'est pas là où est le terrain. Les nouvelles dimensions de ce type
reçoivent `spawnY = 100` du `defaultSpawnY()` du provider, soit au-dessus de la bande
d'îles : un joueur arrive en plein air et tombe de peu. Si quelqu'un a modifié le point
d'apparition vers une coordonnée à l'intérieur d'une île, ou vers une coordonnée au-dessus
du vide, tu obtiens les deux autres résultats.

**Remède.** Règle le point d'apparition depuis la page de détail de la dimension. Le
bouton **Utiliser ma position** y écrit tes coordonnées actuelles, ce qui est le plus
rapide pour poser le spawn sur une île que tu as réellement vue.

---

## Produire un rapport de bug utile

Ouvre une issue sur
[GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues) avec :

1. **Le `logs/latest.log` complet**, ou au minimum toutes les lignes contenant
   `AetherialBridge`, `[CG]`, `GenTypeRegistry`, `Mixin` et `Empty height range`. Les deux
   lignes qui identifient presque tous les problèmes sont
   `[CG] GenTypeRegistry discovered N provider(s)` au démarrage et
   `[AetherialBridge] Built NoiseChunkGenerator for dim ...` à la création de dimension.
2. **Le jar Aetherial Islands exact que tu utilises**, son nom de fichier et le champ
   `version` de son `fabric.mod.json`. « Celui de Modrinth » est la réponse dans la
   plupart des cas cassés, et c'est la seule configuration qui ne peut pas fonctionner.
3. **Les valeurs de `sea_level`, `noise.min_y` et `noise.height`** de ton
   `floating.json`.
4. **Si Minerally, CobblemonRaidDens, Lithostitched ou TerraBlender sont installés**,
   avec leurs versions. Quatre des cinq mixins existent à cause des deux premiers.
5. **L'entrée de la dimension** dans `config/cheeseguard_dimensions.json`, qui montre
   `customGenTypeId`, `genType`, `seed`, `spawnY` et `raidDensEnabled` au même endroit.

Une capture d'écran du terrain vaut la peine pour tout ce qui est visuel, prise avec F3
ouvert pour que les coordonnées et l'identifiant de dimension soient dans le cadre.

Garde en tête ce qu'est ce mod : un patch contre trois mods qu'il ne possède pas,
construit pour un seul modpack. Un rapport qui dit « ça a cassé après la mise à jour de
Minerally » est une information utile, mais la réponse est un changement de code dans la
liste `targets`, pas un réglage.

---

← [Architecture](architecture.fr.md) · [AetherialBridge](../README.fr.md) →
