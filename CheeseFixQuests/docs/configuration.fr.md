[← CheeseFixQuests](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](configuration.md)

# Configuration

Un seul fichier : **`config/cheesefixquests.json`**, relatif au répertoire de travail du
serveur. Il est créé avec toutes les valeurs par défaut au premier démarrage s'il n'existe
pas.

Neuf clés, toutes booléennes. Il n'y a rien à régler : chacune active ou coupe un morceau du
mod. Sur un serveur en bonne santé tu n'y touches jamais ; elles existent pour qu'une
régression puisse être isolée à un mécanisme sans recompiler le jar.

> ⚠️ Rien ici ne se recharge. `CheeseFixQuestsConfig.load()` s'exécute une fois dans
> `onInitialize` et n'a aucun autre appelant. Modifie le fichier serveur arrêté, ou
> redémarre après modification.

---

## L'interrupteur principal

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `forceIndividualProgress` | booléen | `true` | le mod. À `false`, toutes les redirections retombent sur le comportement d'origine de FTB Quests |

### `forceIndividualProgress`

Testée en tête des six `@Redirect` de résolution d'équipe, des deux surcharges
d'`isPlayerOnTeam` (serveur et client), des deux blocages de `mergeData`, de la résolution d'équipe du balayage de finalisation et de la migration
automatique. À `false`, le jar devient inerte : FTB Quests revient à la progression par
groupe, `mergeData` refonctionne, et le balayage de finalisation résout l'équipe *effective*
plutôt que l'équipe de joueur — il continue donc de tourner, mais contre les données du
groupe.

Une chose qu'elle ne désactive **pas** : `TeamDataClaimRewardSyncMixin`. Le verrou de
réclamation des récompenses a son propre drapeau, `enableClaimRewardLock`, et reste actif
quoi qu'il arrive.

Couper cette clé ne défait pas une migration. Les enregistrements individuels restent sur le
disque, invisibles, jusqu'à ce que tu la réactives. Voir
[migrer en sens inverse](migration.fr.md#migrer-en-sens-inverse).

---

## État de la migration

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `migrationDone` | booléen | `false` | indique si la migration unique de la progression a eu lieu |

C'est un état, pas un réglage, et il partage un fichier avec les réglages. `false` signifie
que la migration s'exécutera automatiquement à la prochaine connexion d'un joueur. Le mod la
passe à `true` et enregistre le fichier immédiatement après, et `/cheesefixquests migrate` et
`remigrate` font de même.

La remettre à `false` à la main réarme la migration automatique pour la prochaine connexion.
Lis [la migration](migration.fr.md) d'abord : un second passage est additif et n'abîmera
rien, mais il peut recopier de la progression que tu avais volontairement retirée.

---

## Journalisation

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `debugLogging` | booléen | `false` | une ligne INFO par redirection |
| `auditQuestEvents` | booléen | `false` | consigne chaque finalisation, réclamation et resynchronisation dans `config/cheesefixquests_audit.log` |

### `debugLogging`

Bavard. Chaque redirection de résolution d'équipe écrit une ligne, et l'écouteur
d'inventaire se déclenche à chaque mutation d'inventaire de chaque joueur. Sur un serveur
peuplé cela produit des milliers de lignes par minute. Active-la pour confirmer qu'une
redirection précise est atteinte, lis les quelques lignes dont tu as besoin, désactive-la.

Même désactivée, le mod journalise une fois par joueur la première fois que
`FTBQuestsInventoryListenerMixin` redirige pour lui :

```text
[CheeseFixQuests] InventoryListener.detect: redirected to PlayerTeam for Steve (first redirect for this player; total redirects since startup: 1423)
```

Cette ligne à cadence limitée est en général la seule preuve dont tu as besoin que le mixin
est vivant.

### `auditQuestEvents`

La piste d'investigation, documentée en détail dans
[le journal d'audit](finalizer.fr.md#le-journal-daudit). Désactivée par défaut parce que le
balayage écrit une ligne `PHASE_C_SWEEP` par joueur connecté toutes les 10 secondes, qu'il
se soit passé quelque chose ou non. Active-la pour enquêter sur une récompense dupliquée ou
une quête qui refuse de se terminer, et coupe-la ensuite.

---

## Les quatre phases du finaliseur

Chaque phase du [balayage de finalisation](finalizer.fr.md) a son propre interrupteur
d'arrêt. Elles sont indépendantes : en couper une laisse les autres tourner.

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `enableInventoryRescan` | booléen | `true` | phase A — relancer `FTBQuestsInventoryListener.detect` pour chaque joueur connecté |
| `enableTaskSubmitSweep` | booléen | `true` | phase B — appeler `submitTask` sur chaque tâche dont `checkOnLogin()` est vrai |
| `enableQuestFinalization` | booléen | `true` | phase C — scanner les tâches d'item, réparer la progression de tâche orpheline, finaliser les quêtes terminées mais non marquées |
| `enableCobblemonPokedexResync` | booléen | `true` | phase D — relancer `triggerPokeDexUpdate` de Cobblemon Quests ; inerte sans ce mod |

La phase C est celle qui répare de vraies casses ; les trois autres sont des ceintures de
sécurité. S'il faut un jour alléger le balayage pour le temps de tick, coupe-les dans l'ordre
D, B, A et garde C.

La phase D se désactive en plus définitivement d'elle-même, avec un seul WARN, si la forme
des classes de Cobblemon Quests ne correspond plus. Tu n'as pas besoin de toucher au drapeau
dans ce cas.

---

## Le verrou de réclamation

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `enableClaimRewardLock` | booléen | `true` | rend atomique le test-puis-écriture à l'intérieur de `TeamData.claimReward` |

> ⚠️ Garde-le à `true`. `TeamData.claimedRewards` est une map fastutil sans aucune sûreté
> vis-à-vis des threads, et dans le bytecode d'origine de FTB le test « déjà réclamée ? » et
> l'écriture qui enregistre la réclamation sont deux étapes distinctes. Deux threads qui
> atteignent la même clé de récompense entre ces deux étapes passent tous les deux le test
> et exécutent tous les deux la remise. Mettre `false` restaure exactement cette fenêtre.

Le drapeau n'existe que pour pouvoir couper ce mixin sans recompiler si une régression lui
est imputée. L'incident pour lequel il a été écrit est consigné dans le code : une récompense
de badge donnée deux fois et la commande qui y était attachée exécutée deux fois, à deux
secondes d'écart, avec une seule entrée dans les données persistées parce que l'écriture d'un
thread avait écrasé celle de l'autre.

Contrairement aux autres drapeaux, celui-ci est honoré même quand `forceIndividualProgress`
vaut `false`. La course qu'il ferme est dans FTB Quests, pas dans ce mod.

---

## Exemple complet

Toutes les clés, à leur valeur par défaut. C'est octet pour octet ce que le mod écrit au
premier démarrage, annotations en plus.

```json
{
  "forceIndividualProgress": true,    // le mod lui-même ; false rend le jar inerte
  "debugLogging": false,              // une ligne par redirection — très bavard
  "migrationDone": false,             // passe à true après la migration
  "enableInventoryRescan": true,      // phase A
  "enableTaskSubmitSweep": true,      // phase B
  "enableQuestFinalization": true,    // phase C — celle qui répare les quêtes bloquées
  "enableCobblemonPokedexResync": true, // phase D — exige Cobblemon Quests
  "enableClaimRewardLock": true,      // verrou anti-duplication ; à garder à true
  "auditQuestEvents": false           // journal d'investigation, pour les enquêtes seulement
}
```

JSON n'accepte pas les commentaires. Copie la structure, pas les lignes `//`.

Une clé absente retombe sur la valeur par défaut déclarée dans `ConfigData`, qui est celle
affichée ci-dessus. Un fichier illisible est journalisé en ERROR, les valeurs par défaut sont
utilisées, et le fichier est réécrit avec elles :

```text
[CheeseFixQuests] Failed to load config, using defaults
```

---

← [Commandes](commands.fr.md) · [La migration](migration.fr.md) →
