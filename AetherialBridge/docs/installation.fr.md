[← AetherialBridge](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](installation.md)

# Installation

AetherialBridge est un mod côté serveur. `fabric.mod.json` déclare
`"environment": "*"`, donc Fabric charge volontiers le jar sur un client aussi, mais
les seuls entrypoints qu'il enregistre sont un initialiseur `main` et un provider
`cheeseguard-gentype`, qui tournent tous les deux côté serveur. Les joueurs qui se
connectent à un serveur qui l'utilise n'ont rien à installer.

---

## Ce qu'il te faut

### Serveur

| | Version | Pourquoi |
|---|---|---|
| Minecraft | **1.21.1** | déclaré en `~1.21` |
| Fabric Loader | ≥ 0.15.0 | déclaré dans `depends` |
| Fabric API | n'importe quelle version 1.21.1 | déclaré dans `depends` |
| Java | 21 ou plus | `options.release = 21` |
| [CheeseGuard](../../CheeseGuard/) | n'importe laquelle | **dépendance dure**, déclarée en `cheeseguard: "*"` |
| [CheeseDash](../../CheeseDash/) | n'importe laquelle | entraîné par CheeseGuard, qui en dépend |
| Aetherial Islands | voir plus bas | **dépendance dure**, déclarée en `mr_aetherial_islands: "*"` |

`cheeseguard` et `mr_aetherial_islands` sont tous les deux dans `depends`, pas dans
`suggests`. C'est une exigence dure au sens de Fabric : si l'un des deux jars manque,
**Fabric Loader interrompt le lancement** avant que le moindre mod s'initialise, avec
une erreur de résolution de dépendances qui nomme ce qu'il n'a pas trouvé. Il n'y a pas
de mode dégradé, et le mod ne sert à rien sans les deux.

L'intervalle de versions pour Aetherial Islands est `"*"`, donc Loader accepte
n'importe quelle version qu'il trouve. C'est volontairement large, et ce n'est pas une
déclaration de compatibilité : ce qui doit vraiment correspondre, c'est l'**espace de
noms** des données de worldgen, décrit dans la section suivante. Une version qui
satisfait `"*"` peut très bien échouer au moment de créer la dimension.

### Client

Rien. Aucun mod client, aucun pack de ressources. Le terrain est généré côté serveur et
envoyé comme des données de chunk ordinaires, et les biomes utilisés sont des biomes
overworld vanilla : un client sans mods affiche les îles flottantes sans broncher.

### Pas obligatoire, mais utile à savoir

- **TerraBlender**, si d'autres mods de ton serveur s'en servent pour injecter des
  biomes. CheeseGuard initialise TerraBlender pour chaque dimension créée à chaud,
  celle-ci comprise, afin que les biomes moddés s'affichent avec leurs propres features
  au lieu de pierre nue. Rien à configurer.
- **CobblemonRaidDens** et **Minerally**. Ni l'un ni l'autre n'est requis. Deux des
  mixins de ce mod n'existent que pour supprimer leurs features dans les dimensions à
  niveau de la mer très bas, et les deux sont déclarés `@Pseudo` avec `require = 0` :
  ils se lient si la classe est présente, et sont ignorés sans bruit sinon.
  [Génération du monde](worldgen.fr.md#les-features-supprimées) explique ce que chacun
  empêche.

---

## L'espace de noms du datapack

C'est le point sur lequel on se casse les dents, donc il passe avant les instructions
de build.

`FloatingIslandsProvider` consulte exactement une entrée de registre quand CheeseGuard
lui demande un chunk generator :

```java
private static final Identifier SETTINGS_ID = Identifier.of("aetherial_islands", "floating");
```

Il s'agit d'un objet `ChunkGeneratorSettings` qui doit exister à
`data/aetherial_islands/worldgen/noise_settings/floating.json` dans un datapack chargé.
Si la recherche échoue, le provider lève une exception, et le message est explicite :

```text
ChunkGeneratorSettings 'aetherial_islands:floating' not loaded — is the
aetherial-islands-2.0.2-dimscope.jar present in mods/?
```

**Le téléchargement public d'Aetherial Islands ne contient pas ce fichier.** En amont,
le datapack fonctionne en *écrasant*
`data/minecraft/worldgen/noise_settings/overworld.json`, ce qui est la manière normale
pour un datapack de worldgen de remplacer le terrain d'un monde entier. Cette approche
est exactement le contraire de ce que fait ce mod : elle transformerait ton overworld
existant en îles flottantes pour tous les joueurs.

AetherialBridge attend donc une version **repackagée**, dans laquelle :

- les noise settings ont été déplacées de
  `data/minecraft/worldgen/noise_settings/overworld.json` vers
  `data/aetherial_islands/worldgen/noise_settings/floating.json`, avec chaque density
  function et chaque noise qu'elles référencent, qui passent aussi dans l'espace de
  noms `aetherial_islands` ;
- les écrasements de carvers, placed features et structure sets vanilla du datapack ont
  été retirés, puisque ceux-là sont globaux par nature et ne peuvent pas être limités à
  une seule dimension ;
- il ne reste absolument rien sous `data/minecraft/`.

La version contre laquelle ce mod a été écrit s'appelle
`aetherial-islands-2.0.2-dimscope.jar` et s'identifie comme `mr_aetherial_islands`
version `2.0.2-dimscope`. Ses noise settings contiennent :

| Champ | Valeur |
|---|---|
| `sea_level` | `-30` |
| `noise.min_y` | `-64` |
| `noise.height` | `384` |
| `default_block` | `minecraft:stone` |
| `default_fluid` | `minecraft:water` |
| `aquifers_enabled` | `false` |
| `ore_veins_enabled` | `false` |

`sea_level: -30` est le nombre dont dépend tout le reste du mod : il est sous le seuil
de `-25` que testent les quatre mixins, et c'est ce qui enclenche le fond marin forcé
et la suppression des features.
[Génération du monde](worldgen.fr.md#le-seuil-de-niveau-de-la-mer) explique pourquoi ce
seuil, et ce qui casse si ton datapack passe au-dessus.

> ⚠️ Si tu repackages le datapack toi-même, regarde ce qu'il transporte d'autre. La
> version DimScope embarque encore quatre fichiers `worldgen_modifier` Lithostitched,
> et ceux-là ne sont **pas** limités à une dimension : l'un ajoute une surface rule à
> `minecraft:overworld`, et deux autres réécrivent le climat et les couleurs de
> feuillage de `minecraft:taiga` et `minecraft:stony_shore` globalement. Ils sont
> inertes tant que Lithostitched n'est pas installé. Installe Lithostitched et ils
> s'appliquent à tout le serveur, ce qui casse la propriété d'overworld intact que le
> code d'AetherialBridge, lui, respecte.

---

## Build

AetherialBridge compile contre le **jar construit** de CheeseGuard, via un glob
`fileTree` sur `../CheeseGuard/build/libs`, et non contre ses sources. CheeseGuard, de
son côté, compile contre CheeseDash. Trois builds, dans cet ordre :

```bash
cd CheeseDash         && ./gradlew build     # gradlew.bat sous Windows
cd ../CheeseGuard     && ./gradlew build
cd ../AetherialBridge && ./gradlew build
```

Depuis la racine du dépôt, `tools/build-all.sh` parcourt tous les mods dans le bon
ordre.

Le jar que tu veux :

```text
AetherialBridge/build/libs/aetherialbridge-1.0.0+1.21.1.jar
```

Ignore `aetherialbridge-1.0.0+1.21.1-sources.jar`. La version vient de `mod_version`
dans `gradle.properties`.

Une erreur de compilation qui nomme des classes `com.cheeseguard.api` ou
`com.cheeseguard.data` manquantes signifie que CheeseGuard n'a pas été construit en
premier. La dépendance est déclarée `modCompileOnly` : rien de CheeseGuard n'est
embarqué dans ce jar, les classes doivent être présentes à l'exécution, ce qui est le
cas puisque CheeseGuard est une dépendance dure.

## Installation

1. Arrête le serveur.
2. Copie dans `mods/` :
   - `cheesedash-1.0.0+1.21.1.jar`
   - `cheeseguard-1.0.0+1.21.1.jar`
   - `aetherialbridge-1.0.0+1.21.1.jar`
   - ton jar Aetherial Islands, repackagé comme décrit plus haut
3. Vérifie que Fabric API est aussi dans `mods/`.
4. Démarre le serveur.

L'ordre dans le dossier n'a pas d'importance ; Fabric résout les dépendances lui-même.

## Ce que crée le premier démarrage

Rien. AetherialBridge n'écrit aucun fichier, ne crée aucun dossier et ne lit aucune
configuration. Il n'a pas d'état propre : le provider est un objet sans état, avec cinq
méthodes qui renvoient des constantes et une méthode de fabrication.

Des fichiers apparaissent bien, mais ils appartiennent à CheeseGuard :

```text
config/cheeseguard_dimensions.json     une entrée par dimension, écrite à la première sauvegarde
```

La première fois que tu crées une dimension Aetherial Islands, CheeseGuard y ajoute une
entrée avec `"customGenTypeId": "aetherial-islands:floating"`, et Minecraft commence à
écrire des fichiers de région sous :

```text
<nom-du-monde>/dimensions/cheeseguard/<id-de-dimension>/
```

## Vérifier que c'est chargé

Une ligne vient de ce mod, pendant l'init :

```text
[AetherialBridge] Initialized — registers 'aetherial-islands:floating' GenTypeProvider with CheeseGuard.
```

Cette ligne prouve seulement que le jar s'est chargé. Celle qui prouve que la poignée
de main a fonctionné vient de CheeseGuard, qui balaie l'entrypoint
`cheeseguard-gentype` une fois à l'init :

```text
[CG] GenTypeRegistry discovered 1 provider(s): [aetherial-islands:floating]
```

Si tu lis `discovered 0 provider(s): []`, CheeseGuard s'est chargé et AetherialBridge
ne s'est pas enregistré. Voir
[dépannage](troubleshooting.fr.md#le-type-de-génération-napparaît-pas-dans-le-dashboard).

Vérifie ensuite l'API, qui est ce que lit la liste déroulante du dashboard :

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:44311/api/v1/cheeseguard/dimensions/gen-types
```

L'entrée plugin se trouve après les cinq entrées intégrées :

```json
{
  "id": "aetherial-islands:floating",
  "label": "Aetherial Islands",
  "icon": "☁",
  "defaultSpawnY": 100.0,
  "kind": "plugin"
}
```

`kind` indique au dashboard quel champ renvoyer à la création d'une dimension :
`builtin` repart en `genType`, `plugin` en `customGenTypeId`.

La dernière confirmation n'arrive qu'au moment où tu crées effectivement une dimension,
parce que c'est là que le chunk generator est construit :

```text
[AetherialBridge] Built NoiseChunkGenerator for dim 'islands' using aetherial_islands:floating (min_y=-64, height=384)
```

Voir cette ligne signifie que l'espace de noms du datapack est bon.
[Premiers pas](getting-started.fr.md) prend la suite.

---

## Désinstallation

La dimension survit au mod, et c'est le seul point auquel faire attention.

1. **Supprime d'abord chaque dimension Aetherial Islands**, depuis la vue
   **Dimensions** de CheeseDash, pendant que le mod est encore installé. Supprimer une
   dimension décharge son monde, retire l'entrée de
   `config/cheeseguard_dimensions.json`, et efface
   `<nom-du-monde>/dimensions/cheeseguard/<id>/` du disque.
2. Arrête le serveur.
3. Retire `aetherialbridge-1.0.0+1.21.1.jar` de `mods/`. Retire aussi le jar Aetherial
   Islands, sauf si autre chose s'en sert.
4. Démarre le serveur.

Saute l'étape 1 et l'entrée de dimension reste dans `cheeseguard_dimensions.json` avec
un `customGenTypeId` que plus rien ne peut résoudre. CheeseGuard recrée les dimensions
sauvegardées à chaque démarrage, ne trouve aucun provider pour cet identifiant, le
signale, et retombe sur un générateur de vide :

```text
[CG] Unknown customGenTypeId 'aetherial-islands:floating' — falling back to VOID
```

Le monde se charge alors avec ses fichiers de région intacts, donc le terrain déjà
généré est toujours là ; les chunks générés à partir de ce moment sont vides. C'est
rattrapable (remets le jar), mais un joueur qui se trouve dans une zone fraîchement
générée tombera dans le vide.

Rien d'autre ne reste. Aucun fichier de configuration propre à ce mod n'existe à
supprimer, et retirer le jar retire aussi ses cinq mixins : la worldgen vanilla et les
deux features tierces reviennent immédiatement à leur comportement d'origine.

---

← [AetherialBridge](../README.fr.md) · [Premiers pas](getting-started.fr.md) →
