[← CobbleCap](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](champion-morphing.md)

# Le morphing du champion

L'idée : le boss final de la ligue est celui qui l'a battu en dernier. Un joueur gagne le
Conseil 4 en mode compétitif, et le dresseur RCT `champion_regis_challenge` est réécrit pour
combattre avec l'équipe de ce joueur.

⚠️ **C'est la partie la plus fragile de CobbleCap et celle qui a le plus de chances de
casser.** Elle fonctionne en écrivant des fichiers dans le répertoire de datapack d'un mod
tiers, puis en appelant par réflexion une méthode privée de ce mod. Elle cassera quand
Radical Cobblemon Trainers changera sa disposition de fichiers ou ses noms de classes, et
rien dans CobbleCap ne peut le détecter à l'avance. Si tu adaptes ce mod à un autre serveur,
lis [ce que tu dois changer](#ce-que-tu-dois-changer) avant tout le reste.

## Sur quoi cela repose

| Dépendance | Comment elle est atteinte | Ce qui casse si elle change |
|---|---|---|
| la disposition de fichiers du datapack **rctmod** | un chemin construit depuis trois champs statiques mutables de `RctTrainerMorpher` | la racine du datapack n'est pas trouvée et le morph est abandonné avec un avertissement |
| `com.gitlab.srcmc.rctmod.api.RCTMod` | réflexion : `getInstance().getTrainerManager().loadTrainers()` | le fichier est écrit mais le serveur en cours garde l'ancienne équipe jusqu'au redémarrage |
| `com.gitlab.srcmc.rctmod.world.entities.TrainerMob` | réflexion, pour le basculement vers le défi | le mode défi cesse de basculer |
| le serveur de session Mojang | HTTPS simple, pour la skin — **inutilisé aujourd'hui** | rien ; voir [la skin](#la-skin-nest-pas-écrite) |

Rien de tout cela n'est une dépendance à la compilation. Le `build.gradle` ne contient
aucune entrée rctmod ; chaque appel est un `Class.forName`. Le mod se charge et tourne sans
rctmod — le morph ne trouve simplement jamais rien.

## Le déroulement

```text
victoire compétitive / /cobblecap champion morph_now
        │
        ▼
RctTrainerMorpher.request(player, bestTimeMs)
        │  fige l'équipe dans un PendingMorph (AtomicReference)
        ▼
tick(server)  — tous les 20 ticks serveur
        │  compareAndSet(pending, null)   ← un seul flush par requête, aucun double
        ▼
flushMorph
        1. locateDatapackRoot
        2. ensureBackup                  ← premier morph seulement
        3. réécriture du tableau "team" du JSON de dresseur
        4. RegisModeManager.saveRankedSnapshot
        5. rafraîchissement des clones par dimension et des doublures de skin
        6. RctReloadHook.reloadTrainers
        7. StatueManager.refreshCurrentChampionDioramas
```

### La file d'attente

`request` ne morphe pas. Il fige l'équipe du joueur — via
`ChampionManager.snapshotPlayerTeam`, la même réflexion que celle du Panthéon — et stocke un
`PendingMorph` dans une `AtomicReference`. Une requête ultérieure écrase simplement celle en
attente, donc seul le champion le plus récent est jamais appliqué.

`tick` tourne sur `END_SERVER_TICK` mais n'agit que tous les **20 ticks**, une seconde. Il
prélève la valeur en attente avec `compareAndSet(pm, null)`, ce qui garantit un seul flush
même si deux ticks s'entrelaçaient.

### La vérification de combat est du code mort

La file existe à cause d'un souci de sécurité : ne pas changer l'équipe d'un dresseur
pendant que quelqu'un l'affronte. `RctTrainerMorpher` contient toujours
`countActiveBattles()`, qui réfléchit dans le `BattleRegistry` de Cobblemon, retrouve son
premier champ déclaré de type `Map` et renvoie la taille de cette table — ou **−1 quand le
registre n'est pas introspectable**, avec un contrat, écrit dans sa propre Javadoc, voulant
que les appelants traitent −1 comme « inconnu » et diffèrent le morph. C'est bien la
vérification en échec fermé.

**Plus rien ne l'appelle.** `tick` vide la file à la prochaine frontière d'une seconde,
quoi qu'il arrive. Le commentaire du source explique pourquoi : sur un serveur à trente
joueurs, le nombre de combats actifs n'est pour ainsi dire jamais nul, et le report reportait
donc indéfiniment. Et ce report s'est révélé inutile, parce que rctmod charge l'instantané
d'équipe d'un dresseur au début du combat — réécrire le JSON en pleine bagarre n'affecte pas
un combat en cours, seulement le suivant.

Donc : la méthode est du code vivant jamais invoqué, et la garantie pour laquelle elle a été
écrite est désormais fournie par le cache de rctmod lui-même. C'est un arbitrage
raisonnable, mais c'est une observation sur le comportement de rctmod plutôt qu'un contrat,
et il cesserait d'être vrai si rctmod se mettait à relire un dresseur en pleine bagarre.

### Localiser le datapack

`locateDatapackRoot` essaie cinq candidats dans l'ordre et retient le premier qui est un
répertoire **et** contient le JSON de dresseur au chemin relatif attendu :

1. `server.getSavePath(WorldSavePath.DATAPACKS)/<datapackName>`
2. `<runDir>/<level-name>/datapacks/<datapackName>`
3. `<runDir>/world/datapacks/<datapackName>`
4. `<runDir>/datapacks/<datapackName>`
5. `<runDir>/resourcepacks/<datapackName>`

Le cinquième existe parce que la disposition AutoModpack d'Arkensia duplique les données RCT
sous `resourcepacks/`. En cas de succès il journalise le chemin retenu ; en cas d'échec il
journalise tous les candidats essayés, et c'est la ligne à lire quand un morph ne fait
rien :

```text
[CobbleCap] Datapack root resolved: /srv/mc/world/datapacks/CacademyMons
[CobbleCap] Datapack 'CacademyMons' not found in any of: [...]
```

### La sauvegarde

`ensureBackup` s'exécute avant la première écriture et copie les fichiers en place dans
`config/cobblecap_regis_backup/` :

| Fichier | Contenu |
|---|---|
| `champion_regis_challenge.json` | le dresseur d'origine, intact — la variante **classique** |
| `champion_regis_challenge.png` | la skin d'origine |
| `champion_regis_challenge.MORPHED.json` | le dernier morph — la variante **compétitive**, écrite par `RegisModeManager.saveRankedSnapshot` |

Il n'écrase jamais une sauvegarde existante, de sorte que le premier instantané est toujours
l'état d'origine. Il refuse également de sauvegarder un JSON contenant déjà le marqueur
`__cobblecap_champion`, en journalisant
`Live trainer JSON already morphed — backup skipped (no pristine copy available)`.

⚠️ Si CobbleCap est installé sur un serveur où le JSON de dresseur avait déjà été morphé par
une installation précédente, il n'existe aucune copie d'origine et `restore_original` ne peut
pas la ramener. Lance `/cobblecap champion backup_now` avant le premier morph sur tout
nouveau serveur.

### La réécriture

Le JSON existant est analysé puis **rapiécé**, pas remplacé : seul le tableau `team` est
substitué, de sorte que `name`, `ai`, `battleRules` et tout ce que rctmod définit par
ailleurs survivent. Si le fichier est absent ou illisible, un modèle minimal est construit
avec `name.translatable = trainer.rctmod.champion_regis_challenge`, `ai.type = "rct"` avec
`maxSelectMargin: 0.01`, et `battleRules.maxItemUses: 6`.

Chaque membre d'équipe devient :

```json
{
  "species": "garchomp",
  "level": 78,
  "gender": "MALE",
  "nature": "adamant",
  "shiny": true,
  "aspects": ["mega"]
}
```

`species` retombe sur `"ditto"` quand l'instantané n'en a pas, et tout autre champ est omis
s'il est vide ou nul. Un bloc de provenance est ajouté :

```json
"__cobblecap_champion": {
  "playerUuid": "f84c6a79-0a4e-45e0-aa79-5d0ba2f4a2b1",
  "playerName": "Steve",
  "bestTimeMs": 1122000,
  "morphedAt": 1778000000000
}
```

rctmod ignore cette clé ; CobbleCap l'utilise comme marqueur « déjà morphé », et elle
t'indique d'un coup d'œil de qui est l'équipe que contient actuellement un fichier de
dresseur. L'écriture est atomique via un voisin `.tmp`.

### Le rechargement

Il n'existe pas de commande `rctmod reload`. Le source le dit explicitement, vérifié contre
le bytecode de rctmod-fabric 0.16.11-beta : seuls `rctmod player …` et `rctmod trainer …`
existent. Une version antérieure dispatchait une commande de reload dans une boucle, chaque
tentative levait une exception, chaque exception était avalée, et l'équipe morphée ne prenait
donc effet qu'après un redémarrage complet.

`RctReloadHook` remplace cela par une réflexion sur
`RCTMod.getInstance().getTrainerManager().loadTrainers()`, qui relit les JSON de dresseur
depuis le disque et resynchronise les clients **sans** le rescan de datapack du `/reload`
vanilla — un rescan gèlerait un gros serveur moddé pendant plusieurs secondes. Cela doit
s'exécuter sur le thread serveur, et cela échoue en mode ouvert : tout échec de réflexion
journalise la cause réelle et renvoie `false`.

```text
[CobbleCap] rctmod TrainerManager.loadTrainers() invoked (ok=true)
[CobbleCap] rctmod reload failed after morph -> Steve — JSON on disk updated, restart to apply.
```

La seconde ligne est bénigne en ce sens que rien n'est corrompu : le fichier est correct et
un redémarrage l'applique.

## La skin n'est pas écrite

Le mod ne change **pas** la texture du dresseur.

`downloadMojangSkin` est entièrement implémentée — UUID vers profil de session, propriété
`textures` en base64, URL de skin, octets PNG — et `writeAtomicBytes` existe pour l'écrire.
Ni l'une ni l'autre n'est appelée. `flushMorph` journalise :

```text
[CobbleCap] Skin morph skipped (architectural limitation — see RctTrainerMorpher comment)
```

La raison, d'après le source : l'implémentation précédente écrivait dans
`datapacks/<nom>/assets/…`, et Minecraft ne charge jamais d'assets depuis un datapack — les
textures viennent des packs de ressources. Écrire dans `resourcepacks/` exigerait de
régénérer le bundle AutoModpack et de faire retélécharger chaque client, ce qui est trop
perturbant pour une victoire. Le correctif prévu est une doublure (« doppelgänger ») à
l'exécution, dont des morceaux existent (`RegisDoppelgangerManager`, `ChampionDoppelganger`)
et sont rafraîchis à chaque morph, mais la texture propre au fichier de dresseur est laissée
telle quelle.

Donc : **l'équipe change, le visage non**. Deux méthodes mortes et le champ
`trainerSkinRelPath` subsistent pour qui voudra terminer le travail.

## Le basculement de mode de Régis est global

`RegisModeManager` maintient le fichier de dresseur en place comme une copie de l'une de
deux variantes persistées — le `champion_regis_challenge.json` d'origine pour le classique,
le `…MORPHED.json` pour le compétitif — et `/cobblecap mode` bascule entre les deux.
`applyMode` prend un `ReentrantLock`, court-circuite quand le mode demandé est déjà en
place, copie la source par-dessus le fichier actif, et dispatche le même rechargement par
réflexion.

Demander le compétitif avant que quiconque ait morphé retombe sur le classique et
journalise `No ranked snapshot yet — falling back to classic`. Si aucun des deux fichiers
n'est présent, le basculement est abandonné.

⚠️ Il y a **un seul** fichier actif, et il est global au serveur. Le mode choisi par le
joueur est stocké par joueur sur le scoreboard `cobblecap_mode`, mais le dresseur qu'il
affronte réellement est la variante écrite en dernier. Quand des entrées se chevauchent, un
joueur peut affronter la variante choisie par quelqu'un d'autre, donc une victoire classée
n'a pas forcément été obtenue contre l'équipe classée. Le source l'assume : la conception est l'« Option B », retenue au motif qu'à cinq ou
dix entrées par semaine la course est rare, et que le contrôle de no-op coalesce les
répétitions. C'est un vrai trou de correction, pas un oubli, et le seul correctif robuste
serait des instances de dresseur par joueur — ce que `Conseil4TrainerCloner` devait faire
avant d'être désactivé. D'ici là, ne laisser entrer qu'un challenger à la fois supprime le
problème.

## Le repli : un NPC Cobblemon

`ChampionNpcManager` est un second chemin, indépendant. Au lieu de réécrire un datapack, il
lie une **entité NPC Cobblemon** et morphe cette entité en mémoire. Rien dans le flux normal
ne l'appelle — il est piloté uniquement par les commandes `/cobblecap champion bind_npc*`, et
`morphTo` n'a aucun appelant dans le mod, si bien qu'en pratique lier un NPC enregistre une
liaison et pas grand-chose ne se produit automatiquement.

Ce que `morphTo` ferait, pour qui voudra le brancher :

1. Résoudre l'entité liée par UUID, en privilégiant la dimension enregistrée puis en
   parcourant tous les mondes chargés.
2. Vérifier que c'est un NPC Cobblemon — l'identifiant de type doit commencer par
   `cobblemon:` et soit contenir `npc`, soit avoir pour nom de classe simple `NPCEntity`.
3. Appliquer la skin en parcourant les méthodes publiques de l'entité à la recherche d'une
   méthode à un argument `String` dont le nom en minuscules contient `texture` plus l'un de
   `profile`, `playername` ou `username`. Cobblemon se charge lui-même du téléchargement
   Mojang.
4. Copier l'équipe : essayer une surcharge `setParty` acceptant directement le stockage
   d'équipe du joueur ; sinon vider l'équipe du NPC et, pour chaque Pokémon du joueur, le
   cloner (`clone`/`copy`/`cloneWithoutID`/`createDeepCopy`) et ajouter le clone.

L'étape de clonage est en **échec fermé** : un Pokémon sans méthode de clone utilisable est
ignoré plutôt que partagé par référence, parce qu'un partage ferait muter mutuellement les
Pokémon du joueur et ceux du NPC. Il journalise
`Skipped N Pokémon (no clone method) — NPC team will be incomplete`.

Le résultat est un `MorphOutcome` valant `OK`, `NO_BINDING`, `NPC_NOT_FOUND`,
`NOT_COBBLEMON_NPC`, `SKIN_FAILED`, `PARTY_FAILED` ou `EXCEPTION`. Les échecs de skin et
d'équipe sont indépendants : l'un seul renvoie encore un succès partiel avec l'autre
appliqué.

`ChampionNpcState` persiste la liaison et le nom du dernier champion morphé, c'est ce
qu'affiche `/cobblecap champion status`.

Ce chemin est **moins fragile** que la réécriture de datapack — il ne touche aucun fichier
et survit à une mise à jour de rctmod — mais il morphe un NPC Cobblemon, pas un dresseur
RCT, donc ce NPC ne participe pas à la progression de rctmod. À considérer comme une issue
de secours plutôt qu'un équivalent.

## Ce que tu dois changer

| Constante | Fichier et ligne | Ce que c'est |
|---|---|---|
| `datapackName = "CacademyMons"` | `src/main/java/com/cobblecap/logic/RctTrainerMorpher.java` **ligne 57** | le nom du répertoire de datapack contenant les fichiers de dresseur RCT |
| `trainerJsonRelPath` | même fichier, ligne 55 | `data/rctmod/trainers/champion_regis_challenge.json` |
| `trainerSkinRelPath` | même fichier, ligne 56 | `assets/rctmod/textures/trainers/single/champion_regis_challenge.png` — inutilisé aujourd'hui |
| `TRAINER_ID = "champion_regis_challenge"` | même fichier, ligne 52 | le dresseur qui est morphé |

Les trois chemins sont `public static` et non `final`, donc réassignables à l'exécution
depuis un autre mod — mais **rien dans CobbleCap ne les lit depuis un fichier de
configuration**, malgré le commentaire au-dessus d'eux qui annonce « Configurable from
CapConfig ». Ce commentaire est faux : aucune clé de `CapConfig` ne les touche. Les changer
veut dire éditer le source et reconstruire.

`CacademyMons` est le datapack d'Arkensia. Sur tout autre serveur, `locateDatapackRoot` ne
trouvera aucun répertoire de ce nom, tous les candidats échoueront, et le morph sera
abandonné avec l'avertissement `Datapack 'CacademyMons' not found` à chaque victoire
compétitive et à chaque `morph_now`. Rien d'autre ne casse — pas de crash, pas de corruption
— mais le champion ne change jamais, et les dioramas `place_current` ne se rafraîchissent
jamais non plus, puisque leur rafraîchissement dépend du même flush.

---

← [Le système de champion](champions.fr.md) · [Statues et dioramas](statues.fr.md) →
