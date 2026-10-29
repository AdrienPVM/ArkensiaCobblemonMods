[← CobbleShift](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](installation.md)

# Installation

CobbleShift est déclaré `"environment": "*"` dans son `fabric.mod.json` : le même jar
va **des deux côtés**. Ce n'est pas une formalité. Le serveur détient l'état des
transformations, les attributs et les commandes, tandis que les dix mixins sont côté
client et que chaque remplacement de modèle a lieu dans le rendu client. Un serveur
équipé du mod avec des clients qui ne l'ont pas fonctionne, mais personne ne verra de
Pokémon : le joueur transformé est invisible, sans plus. Installe-le des deux côtés.

---

## Prérequis

| Prérequis | Version | Pourquoi |
|---|---|---|
| Minecraft | `1.21.1` | `"minecraft": "~1.21.1"` dans `fabric.mod.json` |
| Fabric Loader | `>= 0.16.0` | dépendance obligatoire |
| Fabric API | toute version | dépendance obligatoire : enregistrement des commandes, événements de cycle de vie, réseau |
| Java | `>= 21` | `options.release = 21` |
| Cobblemon | toute version | **optionnel** (`suggests`). Sans lui, toute recherche d'espèce échoue |
| CheeseDash | toute version | **optionnel** (`suggests`). Sans lui, le panneau d'administration est absent |
| EasyNPC | toute version | **optionnel**, et pas déclaré du tout. Nécessaire uniquement pour `/cshift transformnpc` |

Cobblemon est optionnel dans le graphe de dépendances et indispensable en pratique. Le
mod n'importe jamais de classe Cobblemon ; il atteint l'équipe, le registre des espèces
et les statistiques de base par réflexion, si bien qu'il se charge proprement sur un
serveur sans Cobblemon. Ce qu'il y fait, en revanche, c'est refuser chaque transformation
avec `Échec : espèce '<name>' introuvable ou Cobblemon non disponible`. Considère
Cobblemon comme obligatoire, sauf si tu veux seulement que le jar soit présent en vue
d'une installation future.

EasyNPC est le cas à part. `NpcTransformManager.isEasyNpc()` teste l'entité ciblée à
l'exécution : le mod n'a donc aucune dépendance envers lui ni à la compilation ni au
chargement, et `fabric.mod.json` ne le mentionne pas. Les sous-commandes PNJ existent
dans tous les cas et refusent avec `Cette entité n'est pas un PNJ EasyNPC` quand le mod
est absent.

## Obtenir le jar

### Depuis une release

Dépose `cobbleshift-1.0.0+1.21.1.jar` dans `mods/` sur le serveur et dans `mods/` sur
chaque client. La chaîne de version vient de `mod_version` dans `gradle.properties` ;
lis-la là plutôt que de te fier à cette page après une mise à jour.

### Depuis les sources

```bash
cd ArkensiaCobblemonMods/CheeseDash
./gradlew build
cd ../CobbleShift
./gradlew build
```

L'ordre compte. `CobbleShift/build.gradle` résout CheeseDash au moyen d'un glob sur une
arborescence de fichiers :

```groovy
modImplementation files(fileTree(dir: "../CheeseDash/build/libs",
    includes: ["cheesedash-*.jar"], excludes: ["*-sources.jar", "*-dev.jar"]))
```

Ce glob ne trouve rien tant que CheeseDash n'a pas été compilé au moins une fois, et la
compilation de `ShiftApi` et de `CobbleShiftDashboardModule` échoue alors sur des
symboles `com.cheesedash.*` manquants. `tools/build-all.sh` parcourt le dépôt dans un
ordre qui évite le problème.

La compilation écrit dans `CobbleShift/build/libs/`. Prends le jar nu ; ignore
`-sources.jar`.

## Ce que crée le premier démarrage

Deux fichiers, tous deux écrits à la demande.

| Chemin | Écrit quand | Contenu |
|---|---|---|
| `config/cobbleshift.json` | au premier démarrage, s'il est absent | les seize clés de config à leurs valeurs par défaut |
| `config/cobbleshift_npc_transforms.json` | à chaque modification d'une transformation de PNJ, et à chaque `SERVER_STOPPING` — même sans aucune, il écrit alors `[]` | les transformations de PNJ persistées |

`CobbleShiftConfig.load()` résout son chemin via `FabricLoader.getConfigDir()` et suit
donc un dossier de config déplacé. `NpcTransformManager`, lui, ne le fait pas : son
`CONFIG_FILE` est la chaîne littérale `"config/cobbleshift_npc_transforms.json"`,
résolue par rapport au dossier de travail du serveur. Sur une arborescence de serveur
normale, les deux atterrissent au même endroit. Sur une arborescence inhabituelle, non :
voir [l'architecture](architecture.fr.md#état-et-disque).

Le client ne crée jamais le fichier des PNJ. Il crée en revanche son propre
`config/cobbleshift.json` : `CobbleShiftMod` est un point d'entrée `main`, il tourne donc
aussi sur le client au lancement et y écrit la configuration par défaut. Cette copie ne
compte qu'en solo.

## Vérifier le chargement

Ces lignes apparaissent sur le serveur pendant l'initialisation, dans cet ordre :

```text
[CobbleShift] Initializing...
[CobbleShift] TypeProjectileEntity registered
[CobbleShift] Default config created
[CobbleShift] Initialized successfully!
```

`Default config created` n'apparaît qu'au tout premier démarrage ; les suivants
journalisent `Config loaded` à la place. La ligne à surveiller est
`TypeProjectileEntity registered` : le mod force le chargement de cette classe avec
`Class.forName` dans le seul but de déclencher l'enregistrement statique de son type
d'entité, et un `Failed to load TypeProjectileEntity!` de niveau `ERROR` à cet endroit
signifie que le type d'entité ne s'est jamais enregistré et que toutes les attaques
échoueront plus tard.

Une fois le monde lancé :

```text
[CobbleShift] Server started
```

Si CheeseDash est installé, il journalise lui-même la découverte du module ; le préfixe
`shift` répond ensuite sur le port **44311**.

## Vérifier en jeu

```mcfunction
/cshift list
```

Un opérateur doit obtenir :

```text
═══ CobbleShift Status ═══
Transforms actives : 0
Abilities actives : 0
Abilities globales : OFF
```

Si `/cshift` n'apparaît pas dans la complétion par tabulation, soit le mod ne s'est pas
chargé, soit ton niveau de permission est inférieur à 2 : tout l'arbre de commandes est
verrouillé au niveau 2.

Confirme ensuite que Cobblemon est réellement joignable :

```mcfunction
/cshift species pikachu
```

De vraies statistiques de base signifient que la réflexion a trouvé Cobblemon. Un
`Espèce 'pikachu' introuvable` alors que Cobblemon est installé signifie que la
recherche par réflexion est cassée, ce qui est un autre problème, traité dans
[le dépannage](troubleshooting.fr.md).

Enfin, transforme-toi et vérifie qu'un client équipé du mod dessine bien le modèle :

```mcfunction
/cshift transform <yourname> pikachu
/cshift revert <yourname>
```

## Désinstaller

Annule toutes les transformations **avant** de retirer le jar :

```mcfunction
/cshift revertall
/cshift revertallnpc
```

Un arrêt propre ferait la moitié joueurs à ta place (`SERVER_STOPPING` appelle
`revertAll()`), mais il ne rétablit volontairement **pas** les PNJ : il les sauvegarde.
Lance `revertallnpc` toi-même, sinon les PNJ restent inscrits dans un fichier que plus
rien ne lira.

Arrête ensuite le serveur, supprime le jar du serveur et de chaque client, et décide du
sort des restes :

| Reste | Sans danger ? | Comment le supprimer |
|---|---|---|
| `config/cobbleshift.json` | oui | supprime-le |
| `config/cobbleshift_npc_transforms.json` | oui | supprime-le |
| modificateurs d'attribut `cobbleshift:transform_speed` / `_health` / `_scale` | **non** | ils sont persistants et vivent dans les données du joueur |
| Invisibilité ambiante infinie | **non** | idem |
| un projectile de type en vol | oui | il est supprimé comme entité inconnue au chargement |

Les deux lignes marquées **non** sont la raison pour laquelle on annule d'abord. Si
tu as retiré le jar alors qu'un joueur était transformé, ce joueur reste invisible et à
la mauvaise échelle sans plus aucun mod pour le remettre en ordre ; le correctif est
`/attribute <player> minecraft:generic.scale modifier remove
cobbleshift:transform_scale` pour chacun des trois, plus `/effect clear <player>
minecraft:invisibility`. Remettre le jar est plus facile : `/cshift revertall` ne
trouverait rien, puisque la liste des transformations n'a pas survécu au redémarrage, mais
une connexion avec le jar présent déclenche `cleanupStaleTransform()`, qui fait exactement
ce nettoyage automatiquement.

---

← [CobbleShift](../README.fr.md) · [Premiers pas](getting-started.fr.md) →
