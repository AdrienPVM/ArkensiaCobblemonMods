[← CheeseFixQuests](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](troubleshooting.md)

# Dépannage

---

## Le serveur crashe au démarrage avec une erreur de mixin

**Cause.** Un mixin n'a pas pu s'appliquer, parce que la classe, la méthode ou l'instruction
FTB qu'il vise a changé. `cheesefixquests.mixins.json` est en `required: true` avec
`defaultRequire: 1` : une redirection qui ne trouve rien à rediriger est un échec dur, pas un
avertissement. La trace nomme le mixin :

```text
Mixin apply failed cheesefixquests.mixins.json:BaseQuestFileMixin -> …
org.spongepowered.asm.mixin.injection.throwables.InjectionError:
  Redirector forcePlayerTeamInGetTeamData could not find any targets
```

**Remède.** Vérifie tes versions FTB. Ce mod est vérifié contre **FTB Quests 2101.1.13**, FTB
Teams 2101.1.2 et FTB Library 2101.1.18. Si tu as mis l'un d'eux à jour, reviens à ces
versions, ou ouvre une issue avec le rapport de crash et les versions que tu utilises — le
mixin doit être réécrit contre le nouveau bytecode, et aucune configuration ne contourne cela.

Si le mixin nommé est `CobblemonQuestsHandlerMixin` ou `ExtraQuestsTimerMixin`, retirer ce mod
optionnel est aussi un contournement valable : les deux sont en `suggests`, et le mixin ne
s'applique pas quand le mod est absent.

---

## Le mod n'apparaît pas du tout dans le journal

**Cause.** Le jar n'est pas chargé. En général FTB Quests ou FTB Teams manque : tous deux sont
déclarés dans `depends`, donc Fabric refuse de charger CheeseFixQuests sans eux et le dit :

```text
Mod 'CheeseFixQuests' (cheesefixquests) 1.0.1+1.21.1 requires any version of mod 'ftbquests', which is missing!
```

**Remède.** Installe FTB Quests et FTB Teams (et FTB Library, dont ils ont besoin). Vérifie
aussi que le jar est bien dans le `mods/` du serveur et pas dans celui d'une instance client
par erreur, que Fabric Loader est au moins en 0.16.0 et que Java est en 21.

---

## La progression des quêtes est toujours partagée dans un groupe

**Cause la plus probable.** `forceIndividualProgress` vaut `false`. Vérifie d'abord :

```mcfunction
/cheesefixquests status
```

```text
§e[CheeseFixQuests] forceIndividual=true migrationDone=true debug=false
```

**Sinon.** La progression atteint le groupe par un chemin de code sans redirection. Chacun des
chemins connus a son mixin — l'écouteur d'inventaire de FTB, la synchro de connexion, le
gestionnaire de Cobblemon Quests, le minuteur d'ExtraQuests — mais tout autre mod qui résout
l'équipe effective lui-même et écrit de la progression de quête continuera d'écrire dans le
groupe. C'est une limite structurelle, expliquée dans
[portée](architecture.fr.md#portée--ce-qui-est-couvert-et-ce-qui-ne-lest-pas).

**Remède.** Réduis le champ par type de tâche. Active `auditQuestEvents`, redémarre et
reproduis. Puis regarde ce qui d'autre pourrait écrire : la tâche partagée est-elle une tâche
d'item (l'écouteur d'inventaire), une capture Cobblemon (Cobblemon Quests), une
réinitialisation quotidienne (ExtraQuests), ou quelque chose qu'un quatrième mod soumet ? Ouvre
une issue en nommant le mod et le type de tâche ; le correctif est une nouvelle redirection,
sur le modèle de la plus petite existante.

Il vaut le coup de confirmer que le mixin est vivant. Sans `debugLogging`, l'écouteur
d'inventaire journalise une fois par joueur :

```text
[CheeseFixQuests] InventoryListener.detect: redirected to PlayerTeam for Steve (first redirect for this player; total redirects since startup: 1423)
```

Si cette ligne n'apparaît jamais pour personne, c'est que
`FTBQuestsInventoryListenerMixin` ne s'applique pas — voir le premier symptôme de cette page.

---

## Les joueurs ont perdu leur progression après l'installation du mod

**Cause.** La migration s'est exécutée et n'a pas tout copié. C'est un comportement attendu
dans un cas précis, qu'il vaut mieux comprendre que traiter comme un bug.

Seule `claimedRewards` enregistre *quel joueur* a fait quoi. `taskProgress`, `started` et
`completed` sont par équipe, sans aucune attribution. Donc pour une équipe source qui n'est pas
un groupe actif — l'ancien enregistrement propre à un joueur, ou une équipe qui n'existe plus —
la migration ne copie la progression que pour les quêtes dont ce joueur a personnellement
réclamé une récompense. **Une quête terminée sans réclamer sa récompense ne peut être attribuée
à personne, et n'est pas copiée.**

**Remède.** Il n'y a aucun moyen de la récupérer depuis les données que FTB Quests stocke :
l'information n'y est tout simplement pas. Tes options sont de restaurer la sauvegarde prise
avant l'installation, ou d'accorder les complétions manquantes à la main depuis le carnet en
mode édition. Lis [la migration](migration.fr.md) avant de tenter un `remigrate` : un second
passage est additif et n'aidera pas ici, puisque le premier a déjà copié tout ce qu'il savait
attribuer.

---

## Une quête reste barre pleine et ne se termine jamais

**Cause.** L'état bloqué classique de FTB Quests. `TeamData.setProgress` sort immédiatement
quand le nouveau compte égale l'ancien, ce qui saute la cascade de complétion : une tâche peut
donc porter `progress == max` alors que la map `completed` n'a aucune entrée pour elle. La quête
est finie et le système ne peut pas le voir. Historiquement le contournement était de se
déconnecter et de se reconnecter.

**Remède.** C'est ce que la phase C du [balayage de finalisation](finalizer.fr.md) répare, et
cela devrait se résoudre tout seul en dix secondes. Si ce n'est pas le cas :

1. Vérifie que `enableQuestFinalization` vaut `true` dans `config/cheesefixquests.json`.
2. Active `auditQuestEvents`, redémarre, et observe `config/cheesefixquests_audit.log`. Une
   ligne `PHASE_C_SWEEP` pour ce joueur toutes les dix secondes prouve que le balayage tourne ;
   `visited=0` signifie qu'il n'atteint aucune quête, et une ligne `FINALIZE_SKIP` nomme
   l'exception rencontrée sur cette quête précise.
3. Si la tâche bloquée est une tâche d'item et que l'item venait d'un coffre, c'est le domaine
   de la phase C0 — cherche `ITEM_REPAIR` ou `ITEM_SET` sur cet identifiant de tâche.

Les identifiants du journal d'audit sont en hexadécimal majuscule, la même forme que celle du
carnet et des fichiers `.snbt` : tu peux les rapprocher directement.

---

## Une récompense a été donnée deux fois

**Cause.** Une course entre threads sur `TeamData.claimedRewards`, une map fastutil sans
aucune sûreté vis-à-vis des threads. Dans le code d'origine de FTB, le test « déjà réclamée ? »
et l'écriture qui enregistre la réclamation sont deux instructions distinctes ; deux threads
qui atteignent la même clé entre les deux passent tous les deux le test et exécutent tous les
deux la remise. Les données persistées ont ensuite l'air propres, parce que l'écriture d'un
thread a écrasé celle de l'autre — c'est ce qui rend ce bug si difficile à croire quand tu as
le fichier de sauvegarde sous les yeux.

**Remède.** `enableClaimRewardLock` ferme la fenêtre, et il vaut `true` par défaut. Vérifie
qu'il n'a pas été coupé :

```text
[CheeseFixQuests] Boot complete — finalizer=true claim-lock=true audit=false
```

Si `claim-lock=false`, remets `enableClaimRewardLock` à `true` et redémarre.

S'il est déjà à `true` et qu'un doublon s'est quand même produit, ça mérite un rapport de bug.
Active `auditQuestEvents`, reproduis, et cherche des couples
`CLAIM_RESERVED` / `CLAIM_BLOCKED` sur la même clé depuis des threads différents. Un
`CLAIM_BLOCKED` depuis un thread autre que `Server thread` est la course prise en flagrant
délit ; deux lignes `CLAIM_RESERVED` sur une même clé signifieraient que le verrou lui-même a
échoué, ce qui devrait être impossible — joins ces lignes.

---

## Une modification de configuration reste sans effet

**Cause.** Rien ne recharge la configuration. `CheeseFixQuestsConfig.load()` s'exécute une fois
dans `onInitialize` et n'a aucun autre appelant. Il n'y a pas de commande de rechargement.

**Remède.** Redémarre le serveur.

Et un piège à connaître : `/cheesefixquests migrate`, `remigrate` et la migration automatique
appellent tous `save()`, qui réécrit tout le fichier depuis les valeurs en mémoire. Une
modification faite à la main serveur allumé est silencieusement écrasée. Modifie serveur
arrêté.

---

## `/cheesefixquests` n'est pas reconnue

**Cause.** Tout l'arbre de commandes exige le **niveau de permission 4**, pas le niveau 2 qu'un
serveur vanilla accorde à un opérateur. En dessous, Brigadier masque entièrement la commande et
la taper donne l'erreur vanilla *Unknown or incomplete command*.

**Remède.** Lance-la depuis la console du serveur, ou monte le compte au niveau 4 — dans
`ops.json`, ou avec `/op` sur un serveur dont `op-permission-level` vaut 4.

---

## Les quêtes quotidiennes ont cessé de se réinitialiser

**Cause.** Le `questReset` d'ExtraQuests résolvait l'équipe effective du joueur, c'est-à-dire le
groupe, et réinitialisait le `TeamData` *de ce groupe* — qui est vide sous ce mod. Le minuteur
expirait, la réinitialisation ne touchait rien, et les quêtes restaient terminées.

**Remède.** `ExtraQuestsTimerMixin` redirige cette résolution : les nouvelles
réinitialisations tombent sur les bonnes données et cela ne devrait plus se reproduire. Les
chapitres bloqués avant l'existence du mixin demandent une réparation manuelle :

```mcfunction
/cheesefixquests resetchapter <chapterHexId>
```

Lis d'abord [la section de la commande](commands.fr.md#cheesefixquests-resetchapter). Elle
dé-réclame les récompenses, donc les joueurs qui avaient déjà pris celle d'une quête
réinitialisée peuvent la reprendre, et elle ne touche que les quêtes marquées terminées.

---

## Le mode édition du carnet ne fait rien

**Cause.** Le client n'a pas le jar. Côté client, FTB Quests compare l'identifiant d'équipe
qu'on lui a annoncé (celui du groupe) à celui que portent ses propres données de quêtes (celui
de l'équipe de joueur) ; l'écart fait renvoyer vide à `getOrCreatePlayerData`, ce qui casse
`setCanEdit` / `getCanEdit`. `ClientQuestFileMixin` est le correctif, et c'est un mixin côté
client.

**Remède.** Installe le même jar sur le client. Voir
[de quel côté](installation.fr.md#de-quel-côté).

---

## La phase D s'est désactivée d'elle-même

**Cause.** Cobblemon Quests a changé la forme de la classe que la phase D atteint par
réflexion — le champ singleton `CobblemonQuests.eventHandler` ou la méthode
`triggerPokeDexUpdate(UUID)`. Le mod journalise un seul WARN et ne retente jamais :

```text
[CheeseFixQuests] Phase D permanently disabled (class shape mismatch): java.lang.NoSuchMethodException: …
```

**Remède.** Rien n'est cassé ; la progression Cobblemon Quests retombe simplement sur ses
propres déclencheurs d'événements, comme sans ce mod. Soit tu reviens à Cobblemon Quests
1.1.14, soit tu le signales — la cible de la réflexion doit être mise à jour.

Une autre ligne, `Phase D init transient failure (will retry)`, n'est pas la même chose.
Celle-là est anodine : l'initialiseur statique de Cobblemon Quests n'avait pas encore rempli le
champ, et le balayage suivant armera la phase.

---

## Il n'y a pas de module dashboard

**Cause.** C'est voulu. CheeseFixQuests n'enregistre aucun entrypoint `cheesedash`, n'a aucun
endpoint REST et ne dépend pas du tout de CheeseDash — c'est le seul mod de ce dépôt
entièrement indépendant du reste.

**Remède.** Rien à réparer. Tout se pilote depuis la console avec
[`/cheesefixquests`](commands.fr.md) et depuis
[`config/cheesefixquests.json`](configuration.fr.md).

---

## Écrire un rapport de bug utile

Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues).

À joindre :

1. **Les versions.** CheeseFixQuests, FTB Quests, FTB Teams, FTB Library, Fabric Loader,
   Minecraft, et Cobblemon Quests / ExtraQuests s'ils sont installés. Les versions FTB sont la
   première chose qu'on te demandera.
2. **`config/cheesefixquests.json`**, en entier. Neuf booléens ; l'un d'eux est généralement
   l'explication.
3. **Le bloc de démarrage.** Toutes les lignes `[CheeseFixQuests]` depuis le lancement du
   serveur jusqu'à la première connexion — `grep CheeseFixQuests logs/latest.log`.
4. **Le rapport de crash complet**, s'il y a eu crash. Le nom du mixin dans la trace est à lui
   seul le diagnostic.
5. **Pour un bug de progression ou de duplication**, le journal d'audit. Mets `auditQuestEvents`
   à `true`, redémarre, reproduis, et joins la fenêtre pertinente de
   `config/cheesefixquests_audit.log` — en incluant les lignes `PHASE_C_SWEEP` autour de
   l'événement, pas seulement l'événement. Donne l'identifiant hexadécimal de la quête si tu
   l'as.
6. **Pour un bug de partage**, ce que les deux joueurs ont fait, dans l'ordre, et quel type de
   tâche était en jeu. Tâche d'item, tâche d'élimination, capture Cobblemon, réinitialisation
   quotidienne : chacune a un chemin de code et un mixin différents.

---

← [Architecture](architecture.fr.md) · [CheeseFixQuests](../README.fr.md) →
