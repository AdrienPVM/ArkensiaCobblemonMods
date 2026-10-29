[← CheeseCompatModsFix](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](fixes-battle-tower.md)

# Catalogue des correctifs — Battle Tower

> ⚠️ Chaque correctif de cette page entre dans le code d'un autre auteur, par mixin ou
> par réflexion, contre **une seule** version de ce code. Lis la ligne de version avant
> de supposer que quoi que ce soit ici s'applique à ton serveur.

**Mod visé :** Cobblemon Battle Tower (`cobblemon_battle_tower`).
**Version visée à la compilation :** `1.7.11+1.6.1` (Fabric), id de version Modrinth
`NRBIX8Ty` — la coordonnée est dans `build.gradle`.
**Inerte en l'absence du mod :** oui, pour tous. `CheeseCompatMixinPlugin` renvoie
`BT_LOADED` pour tout mixin dont le nom de classe contient `BattleTower`, `TeamSnapshot`,
`NetworkHelper`, `TeamFullRestore` ou `BattleArenaPosition`, et
`BattleTowerDisconnectFix.register()` est derrière un
`isModLoaded("cobblemon_battle_tower")` dans `CheeseCompatMod`. Sans le mod, aucune de
ces lignes n'est atteinte.

C'est la plus grosse famille du mod — huit correctifs — parce que la Battle Tower
confisque l'équipe entière d'un joueur pour la durée d'une série. Elle copie l'équipe,
force chaque Pokémon à un niveau fixe, puis restaure l'originale. Tout ce qui interrompt
ce cycle (une déconnexion, un crash, un abandon, un deuxième combat lancé trop vite)
laisse un joueur avec la mauvaise équipe, et « mauvaise équipe » sur un serveur en
production, ça veut dire un ticket de support sur des Pokémon perdus.

---

## 1. Présence d'Academy forcée à vrai

**Symptôme.** Les dresseurs PNJ de la tour combattent à leurs propres niveaux au lieu du
niveau que l'étage est censé imposer. Un étage prévu pour être un combat à niveau 50 plat
ne l'est pas.

**Cause.** `BattleTowerManager.isAcademyPresent()` conditionne le chemin de forçage des
niveaux PNJ. Elle renvoie faux sur un serveur où l'intégration StarAcademy qu'elle attend
n'est pas détectée comme elle l'attend.

**Moyen.** `BattleTowerManagerMixin` — `@Inject` en `HEAD` de `isAcademyPresent`,
`cancellable`, `cir.setReturnValue(true)`. La méthode n'exécute jamais sa propre logique.

**État.** Actif.

C'est la rustine la plus brutale du mod : un retour forcé d'une ligne. Elle est listée en
premier parce que c'est aussi celle qui a le plus de chances d'être fausse sur un autre
modpack. Si ton serveur ne veut pas de niveaux PNJ forcés, ce correctif à lui seul fait
diverger la tour de son comportement amont.

## 2. Lancer un combat de tour alors qu'on est déjà en combat

**Symptôme.** Un joueur en combat sauvage ou de dresseur s'approche de la tour, lance un
combat, et se retrouve dans deux combats à la fois. De son côté l'écran ne répond plus ;
du côté serveur, Showdown a deux sessions qui croient toutes deux posséder la même équipe.

**Moyen.** `@Inject` en `HEAD` de `BattleTowerManager.startBattle`, `cancellable`. Le
gestionnaire lit le `BattleRegistry` de Cobblemon par réflexion (la classe est résolue par
nom et mise en cache, avec un drapeau `reflectionFailed` pour qu'un échec de résolution
dégrade vers « pas de vérification » plutôt que vers une exception par appel). Si le
joueur a un combat actif, l'injection pose la valeur de retour à `null` et envoie
`cheesecompat.bt.already_in_battle` :

```text
Vous ne pouvez pas entrer dans la Tour de Combat pendant un combat !
```

**État.** Actif.

Cette même injection en `HEAD` gère aussi les deux gardes qui suivent.

## 3. Lancer un combat pendant qu'une restauration disque tourne encore

**Symptôme.** Un joueur se reconnecte après une déconnexion en pleine partie, retourne
droit à la tour, et l'équipe verrouillée est celle qui porte encore les niveaux forcés de
la tour. Les niveaux d'origine sont alors perdus définitivement.

**Cause.** `BattleTowerDisconnectFix` restaure une équipe depuis une sauvegarde disque
sur plusieurs ticks (il attend le chargeur d'équipe asynchrone de Cobblemon). Un nouveau
`startBattle` pendant cette fenêtre capture une équipe en cours de reconstruction.

**Moyen.** La même injection `HEAD` sur `startBattle`. Elle appelle
`BattleTowerDisconnectFix.isRestorePending(uuid)` en premier, avant toute autre
vérification, et annule avec `cheesecompat.bt.restore_pending` si une restauration est en
cours :

```text
Restauration de l'équipe de la Tour de Combat en cours — patientez quelques secondes avant de lancer un nouveau combat.
```

**État.** Actif.

## 4. Verrou d'équipe périmé après un abandon

**Symptôme.** Un joueur abandonne ou se déconnecte, revient, et la tour refuse de lancer
un combat parce qu'elle croit que l'équipe a changé — un verrou fantôme qu'aucune action
en jeu ne libère.

**Moyen.** L'injection `HEAD` sur `startBattle` compare les UUID des Pokémon de l'équipe
courante à ceux du `TeamSnapshot` stocké (`cheesecompat$validateTeam`). Si le snapshot est
périmé, elle libère le verrou et laisse le combat se lancer. Le mixin tient aussi deux
maps `@Unique` — un numéro d'étage et un compteur de combats par joueur, tous deux en
mémoire et perdus au redémarrage — et en dérive un délai adaptatif : 1500 ms jusqu'à
l'étage 5, 2000 ms jusqu'au 10, 2500 ms jusqu'au 15, 3000 ms au-delà. Ce délai donne à
Showdown plus de temps pour démonter son état à mesure qu'une série l'accumule.

**État.** Actif.

Ces chiffres sont empiriques. Ils ont été augmentés jusqu'à ce que le symptôme cesse sur
un serveur. Rien ne permet de croire qu'ils sont justes ailleurs.

## 5. Corruption des IV et des EV dans le snapshot d'équipe

**Symptôme.** Une équipe revient d'une série de tour avec des IV retirés au hasard et des
EV remis à zéro. C'est le pire bug de la famille, parce qu'il est silencieux et définitif.

**Cause.** Dans Cobblemon 1.6.1, `Pokemon.copyFrom()` a des lambdas qui écrivent les IV et
les EV dans le mauvais sens : au lieu de copier la source vers la cible, elles écrasent la
*source* avec les IV fraîchement tirés et les EV à zéro de la cible. Battle Tower appelle
`copyFrom` quand il capture une équipe.

**Moyen.** `TeamSnapshotMixin` sur `battle.tower.data.TeamSnapshot`, quatre injections :
`@Inject` en `HEAD` et en `RETURN` de `captureTeam`, et la même paire sur `getTeam`. Le
gestionnaire `HEAD` sauve les vrais IV et EV ; celui de `RETURN` les réécrit par-dessus ce
que `copyFrom` a produit. `getTeam` a besoin du même traitement parce que l'interface
client l'appelle à chaque rafraîchissement : sans ça, la corruption serait réappliquée en
continu.

**État.** Actif.

C'est un correctif pour un bug de Cobblemon, appliqué à un site d'appel de Battle Tower.
Si tu tournes sur Cobblemon 1.7.x, où la lambda est corrigée, ce mixin travaille pour
rien — sans nuire, mais pour rien.

## 6. Restauration complète de l'équipe, pas seulement des niveaux

**Symptôme.** Une équipe revient d'une série avec les bons niveaux et rien d'autre :
objets tenus disparus, PP justes ou faux, amitié remise à zéro, attaques revenues en
arrière, statut effacé.

**Cause.** `BattleTowerManager.restoreForcedLevelsSnapshot()` ne restaure que les niveaux
et appelle `heal()`. Tout le reste du snapshot est ignoré.

**Moyen.** `TeamFullRestoreMixin`, deux sites d'injection sur `BattleTowerManager` :

- `@Inject` en `RETURN` de `lockInTeam` — sérialise le `TeamSnapshot` sur disque via
  `BattleTowerDisconnectFix.writeBackupFromSnapshot`, pour que l'état complet de l'équipe
  survive à un crash en pleine partie. Le fichier n'est supprimé qu'après une restauration
  prouvée réussie.
- `@Inject` en `HEAD` de `restoreForcedLevelsSnapshot` — reconstruit chaque Pokémon de
  l'équipe sur place via `Pokemon.loadFromNBT`, le même chemin de code que Cobblemon
  utilise au chargement depuis le disque. Une injection en `RETURN` journalise le résultat.

Les joueurs hors ligne sont volontairement ignorés : le chemin de reconnexion appartient à
`BattleTowerDisconnectFix`, qui lit le fichier de sauvegarde intact à la place.

**État.** Actif.

## 7. Déconnexion et reconnexion en pleine partie

**Symptôme.** Un joueur se déconnecte pendant un combat de tour et revient avec les
niveaux forcés de la tour comme vrais niveaux. Ou avec une équipe vide. Ou avec l'équipe
qu'il avait trois séries plus tôt.

**Moyen.** `BattleTowerDisconnectFix` — une classe de gestionnaires d'évènements, pas un
mixin :

| Hook | Effet |
|---|---|
| `ServerLifecycleEvents.SERVER_STARTED` | balaie les sauvegardes de plus de 30 jours (`BACKUP_MAX_AGE_MS`) |
| `ServerPlayConnectionEvents.DISCONNECT` | marque le joueur comme ayant besoin d'une restauration |
| `ServerPlayConnectionEvents.JOIN` | met la restauration en file |
| `ServerTickEvents.END_SERVER_TICK` | fait tourner la boucle de restauration |

Les sauvegardes vivent dans `config/cheesecompat_bt_backups/<uuid>.dat`, écrites avec
`NbtIo.writeCompressed` dans un `.dat.tmp` puis renommées atomiquement, donc un crash en
cours d'écriture ne peut pas laisser un demi-fichier. La boucle réessaie toutes les
`WAIT_INTERVAL_TICKS` (20 ticks, une seconde) jusqu'à `MAX_ATTEMPTS` (60, soit une minute)
et ne déclenche que lorsque **tous** les UUID de la sauvegarde sont présents dans l'équipe
vivante. Cette condition existe parce que Cobblemon charge une équipe de façon asynchrone :
restaurer trop tôt écrit dans une liste encore en cours de remplissage. La sauvegarde n'est
supprimée qu'après que chaque UUID a été retrouvé et rechargé.

Le code distingue une clé `team` absente d'une liste d'équipe vide — un changement de
schéma en amont qui supprimerait la clé ne doit pas se lire comme « ce joueur n'avait
aucun Pokémon ».

**État.** Actif.

La classe entre aussi dans `com.cobblecap.CobbleCapMod` par réflexion quand
[CobbleCap](../../CobbleCap/) est installé, pour suspendre l'application du plafond de
niveau pendant que la tour force les niveaux. Cette interaction est dans ce dépôt, pas
chez un tiers, mais c'est toujours de la réflexion contre un nom.

## 8. Offsets de téléportation de l'arène

**Symptôme.** Joueurs et dresseurs PNJ apparaissent au mauvais endroit dans une arène
custom — le joueur cinq blocs derrière l'ancre de l'arène, le PNJ cinq devant, tous les
deux au milieu de rien.

**Cause.** `BattleArenaService.buildPositions()` traite l'ancre configurée comme le
*centre* de l'arène : le joueur est placé à `anchor.offset(facing, -5)` et le PNJ à
`anchor.offset(facing, 5)`.

**Moyen.** `BattleArenaPositionMixin` — deux `@ModifyConstant` sur `buildPositions`, qui
changent la constante `-5` en `0` (le joueur apparaît sur l'ancre) et `5` en `18` (le PNJ
apparaît sur la plateforme d'en face, 18 blocs plus loin). Les offsets coop, ±2 blocs
latéralement, ne sont pas touchés.

**État.** Actif.

⚠️ `18`, c'est la distance entre les plateformes d'**une construction d'arène précise**.
Sur toute autre arène, ce correctif met le PNJ dans un mur. C'est un nombre codé en dur
pour une seule carte, documenté comme tel.

## 9. Pokémon PNJ invisible

**Symptôme.** Le combat démarre, la barre de vie du Pokémon du PNJ apparaît, et le Pokémon
lui-même jamais. Le combat est alors bloqué : on ne peut pas viser ce qui n'a jamais été
affiché.

**Cause.** Conséquence directe du correctif 8. Déplacer le PNJ à ancre + 18 peut le mettre
dans un chunk différent de celui de l'ancre, et `BattleTowerManager.ensureChunkLoaded` ne
charge que le chunk de l'ancre. Le `ClientboundAddEntityPacket` du Pokémon apparu est alors
jeté par le traqueur d'entités, parce que le chunk est hors du rayon de suivi du joueur.

**Moyen.** `BattleTowerChunkPreloadMixin` — `@Inject` en `HEAD` de `createTrainer`, qui
force-load un carré de 3×3 chunks autour du milieu entre le joueur et le PNJ. Le coût est
d'un force-load par création de combat ; le commentaire dans le code chiffre le pic TPS à
50-100 ms et le juge acceptable. Les échecs sont attrapés et journalisés en `ERROR` sans
casser le combat.

**État.** Actif.

## 10. Formes alternatives mal affichées dans l'écran d'équipe

**Symptôme.** Dans l'écran de sélection d'équipe de la tour, une variante régionale, une
méga ou une forme originelle s'affiche comme l'espèce de base. Le chromatique passe ; tout
le reste est perdu.

**Cause.** `BattleTowerScreen.renderPokemonInSlot` ne passe que `Set.of("shiny")` (ou un
ensemble vide) à `FloatingState.setCurrentAspects()`. Les vrais aspects n'ont jamais
traversé le réseau : `BattleTowerNetwork$PartyEntry` ne porte que `speciesId` et `shiny`.

**Moyen.** `client.BattleTowerScreenAspectMixin`, côté client uniquement. Un `@Inject` en
`HEAD` de `renderPokemonInSlot` pour savoir quel emplacement est en train d'être dessiné,
un `@ModifyArg` sur l'appel à `setCurrentAspects` pour y fusionner les vrais aspects lus
dans le miroir d'équipe client de Cobblemon, et un `@Inject` en `RETURN` pour nettoyer. En
cas d'échec, l'ensemble d'origine (cassé) est renvoyé tel quel : le pire cas est le
comportement existant.

Purement visuel. Aucun objet Pokémon n'est muté.

**État.** Actif.

## 11. Niveaux forcés non restaurés à l'arrêt

**Symptôme.** Arrête le serveur pendant que quelqu'un est en combat de tour ; son équipe
garde les niveaux forcés pour toujours.

**Cause.** `BattleTower.onServerStopping` vide ses maps mais n'appelle jamais
`restoreForcedLevels`.

**Moyen.** `BattleTowerShutdownMixin` — `@Inject` en `HEAD` de `onServerStopping`, pour
que la restauration tourne pendant que les joueurs sont encore en ligne et leurs équipes
encore inscriptibles.

**État.** Actif.

## 12. Boutique BP : débordement de quantité et BP perdus en silence

**Symptôme.** Deux bugs distincts de même forme — des BP disparaissent et rien n'arrive.

Le premier est un exploit : un paquet forgé avec `quantity = Integer.MAX_VALUE` fait
déborder `bpCost * quantity` vers un nombre négatif, donc l'achat *crédite* des BP au lieu
d'en débiter, et peut geler le serveur sur la boucle d'insertion.

Le second a été observé en jeu : une entrée de boutique dont l'id d'item Minecraft n'est
pas enregistré sur ce serveur. `handlePurchase` débite les BP **avant** de résoudre
l'item, et `Registries.ITEM.get(id)` sur un id non enregistré renvoie `minecraft:air` et
non `null` dans une `DefaultedRegistry`. Le stack vide s'insère « avec succès », aucune
exception n'est levée, aucun rollback n'a lieu. BP partis, rien livré, aucun message.

**Moyen.** `NetworkHelperImplMixin` — `@Inject` en `HEAD` de `handlePurchase`,
`cancellable`. Il borne la quantité à `[1, 64]` et valide l'item avant tout débit ;
annuler en `HEAD` signifie que la méthode entière, débit inclus, ne tourne jamais.

La subtilité qui mérite d'être répétée, parce que la première tentative l'avait prise à
l'envers : le paramètre `itemId` de `handlePurchase` est le **slug du `ShopItem`**
(`ShopItem.id()`), pas un id d'item Minecraft. Le vrai id est `ShopItem.itemId()`. Valider
le paramètre directement aurait refusé tous les achats légitimes. Le correctif résout donc
le `ShopItem` par son slug, puis valide *son* `itemId()`. Les « command items », dont la
récompense est une commande et pas un item, sont exemptés de la vérification de registre.

**État.** Actif.

---

## Ce que cette famille t'apprend

Les correctifs Battle Tower sont la meilleure illustration du motif que tout ce mod
répète : un mod tiers détient un état joueur mutable de part et d'autre d'une frontière
asynchrone, et chaque correctif ici est soit une garde à l'entrée, soit un snapshot pris
plus tôt, soit une restauration faite plus tard. Trois d'entre eux (3, 6, 7) ne
fonctionnent que parce qu'ils s'accordent sur un fichier de sauvegarde et un drapeau
« restauration en cours ». Si tu en retires un, vérifie ce que les deux autres supposaient.

---

← [Configuration](configuration.fr.md) · [Catalogue — Cobblemon et les combats](fixes-cobblemon.fr.md) →
