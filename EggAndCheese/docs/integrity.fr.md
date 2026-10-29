[← EggAndCheese](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](integrity.md)

# Intégrité : doublons et orphelins

En juin 2026, une carte de production s'est retrouvée avec deux blocs physiques
revendiquant `egg_026` et un bloc portant un id qu'aucune entrée du registre n'utilisait.
Les deux défauts sont invisibles en jeu : un œuf ressemble à un œuf. Toute la machinerie
de cette page existe pour qu'un admin puisse les trouver et les corriger sans ouvrir un
fichier de région.

Si ta carte a dérivé — œufs copiés à la WorldEdit, schématique collée deux fois,
sauvegarde restaurée, fichier de registre remplacé à la main — c'est la page qu'il te
faut.

## Les deux défauts

**Un doublon**, c'est un `registryId` revendiqué par plus d'un bloc. L'entrée du registre
pointe vers une position ; les autres blocs sont des leurres. Un joueur qui découvre un
leurre reçoit la récompense et la quête, et l'admin qui se téléporte ensuite à la position
du registre trouve un œuf déjà trouvé. Pire : un `PUT` du dashboard qui déplace l'œuf ne
déplace que le bloc canonique et laisse le leurre en place définitivement.

**Un orphelin**, c'est un bloc au `registryId` non vide qui ne correspond à aucune entrée
du registre. Le clic droit n'y fait absolument rien : `handlePlayerDiscovery` sort à sa
première vérification. Il s'affiche, il est indestructible pour les non-admins, et il ne
sert à rien.

## `LiveAdminEggIndex`

`src/main/java/com/roicheese/eggandcheese/data/LiveAdminEggIndex.java` — une map statique
du `registryId` vers un ensemble d'`EggLoc(String dim, BlockPos pos)`.

Son invariant, énoncé dans son propre Javadoc : il contient exactement les positions de
chaque `AdminCollectibleBlockEntity` **actuellement chargé** dont le `registryId` n'est
pas vide. Il est maintenu depuis trois endroits :

| Événement | Action |
|---|---|
| `ServerBlockEntityEvents.BLOCK_ENTITY_LOAD` | `add(id, dim, pos)` |
| `ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD` | `remove(id, dim, pos)` |
| `AdminCollectibleBlockEntity.setRegistryId` (côté serveur) | retire l'ancien id, ajoute le nouveau |

L'invariant est tout l'intérêt de la chose. Comme l'index ne contient que des blocs
chargés, toute position qu'il rapporte est dans un chunk chargé : un appelant peut donc
faire `getBlockEntity()` dessus sans jamais déclencher de chargement de chunk. Tous les
chemins de réparation de ce sous-système en dépendent, et casser l'invariant
réintroduirait le plantage décrit en fin de page.

Tous les accès ont lieu sur le thread serveur — événements de block entity, plus le
dashboard via `WebDashboard.executeOnServerThread`. Les méthodes sont tout de même
`synchronized`, par précaution contre une lecture égarée hors thread.

`duplicateIds()` renvoie tout id ayant plus d'une position chargée. C'est un vrai doublon
parmi les chunks chargés ; rien ne peut être affirmé sur les chunks déchargés.

## Réconciliation au chargement

`AdminEggPlacedEvent.register()` installe les handlers. Sur `BLOCK_ENTITY_LOAD`, pour un
collectible admin à l'id non vide, la position est ajoutée à l'index et une réconciliation
est mise en file. Elle n'est **pas** exécutée sur place — voir
[la règle de différé](#ne-jamais-forcer-le-chargement-dun-chunk-depuis-un-handler-de-chargement-de-block-entity).

`reconcile(server, id, justLoaded)` impose un seul bloc physique chargé par id :

1. Prendre un instantané de l'entrée d'index pour `id`. Vide signifie rien à faire.
2. Chercher l'id dans le registre. **Aucune entrée signifie orphelin, et reconcile
   s'arrête** sans rien toucher. Les orphelins sont volontairement laissés à l'outil du
   dashboard, parce que délier un bloc automatiquement pourrait détruire un travail
   qu'un admin est en train de faire.
3. Décider quelle position garder :
   - si la position propre de l'entrée figure dans l'instantané, garder celle-là — le
     registre gagne ;
   - si l'entrée n'est pas revendiquée (`positionLost`, ou position à `BlockPos.ORIGIN`),
     adopter le bloc qui vient de charger et soigner le registre : effacer
     `positionLost`, écrire la position et la dimension adoptées, marquer modifié ;
   - sinon l'entrée revendique une position qui n'est pas chargée. Garder un bloc de façon
     déterministe et ne pas toucher à l'entrée.
4. Délier toutes les autres positions de l'instantané.

« De façon déterministe » renvoie à `pickDeterministic` : plus petit id de dimension par
comparaison de chaînes, puis plus petit `BlockPos.asLong()`. Aucun hasard : deux serveurs
aux mêmes données réconcilient vers le même bloc, et relancer l'opération ne fait rien.

Délier, c'est `unbindAt`, et c'est l'inverse d'une destruction : le `registryId` du bloc
est mis à `""`. Le bloc reste, garde son ADN, continue de s'afficher, et devient un œuf
décoratif non lié. Rien de ce qu'un bâtisseur a posé n'est supprimé. La ligne de log
est :

```text
[EggAndCheese] Unbound duplicate egg 'egg_026' at BlockPos{x=812, y=71, z=-304}
```

### Replacer un œuf retire l'ancien bloc

Le troisième volet de la protection anti-doublon vit dans
`AdminCollectibleItem.postPlacement`. Quand un admin replace un œuf dont l'id possède déjà
un bloc chargé ailleurs, ce bloc est délié puis **retiré** avant que le nouveau ne soit
lié. Cela reproduit ce que fait le déplacement par `PUT` du dashboard et ferme le chemin
qui a créé le doublon `egg_026` d'origine : casser l'œuf, s'en aller, le poser ailleurs,
et retrouver le premier toujours debout.

## Les points d'API du dashboard

### `GET /api/v1/eggs/integrity`

Un rapport, aucune mutation.

```json
{
  "loadedBlocks": 54,
  "duplicateCount": 1,
  "orphanCount": 2,
  "duplicates": [
    {
      "id": "egg_026",
      "positions": [
        { "dimension": "minecraft:overworld", "x": 812, "y": 71, "z": -304 },
        { "dimension": "minecraft:overworld", "x": 940, "y": 64, "z": -190 }
      ]
    }
  ],
  "orphans": [
    { "dimension": "minecraft:overworld", "x": 101, "y": 70, "z": 55, "id": "egg_099" },
    { "dimension": "minecraft:overworld", "x": 133, "y": 68, "z": 61, "id": "egg_099" }
  ],
  "unloadedEntries": ["egg_031", "egg_044"]
}
```

| Champ | Signification |
|---|---|
| `loadedBlocks` | nombre total de blocs d'œufs admin chargés avec un id lié |
| `duplicates` | un objet par id ayant plus d'une position chargée |
| `orphans` | un objet par bloc chargé dont l'id est absent du registre |
| `unloadedEntries` | entrées du registre, non `positionLost`, dont le bloc canonique n'est pas chargé. **Purement informatif** — le serveur ne peut pas vérifier un bloc dans un chunk déchargé sans le charger, et il ne le fera pas. |

`unloadedEntries` est le champ que l'on interprète de travers. Un id qui y figure n'est
pas cassé. Sur un serveur où peu de joueurs sont connectés, la plupart de tes œufs y
seront listés, parce que la plupart de tes chunks sont déchargés.

### `POST /api/v1/eggs/integrity/reconcile`

La réparation. Pour chaque id de l'instantané d'index :

- aucune entrée de registre → `unbindAt` sur chaque position qui le revendique, chaque
  succès comptant dans `orphansCleared` ;
- plus d'une position chargée → `reconcile(server, id, null)`, avec
  `positions - 1` ajouté à `duplicatesResolved`.

```json
{ "duplicatesResolved": 1, "orphansCleared": 2 }
```

Les deux nombres comptent des opérations de *déliaison*, jamais des suppressions. Lancer
deux fois ne pose pas de problème : la seconde passe ne trouve plus rien à délier.

### Le bouton du dashboard

Le bouton **Intégrité** de la vue Collectibles (`CheeseDash/assets/view-eggs.js`, fonction
`checkIntegrity`) appelle le `GET`, et :

- sur un rapport propre, affiche un toast de succès — « Intégrité OK — 54 blocs d'œufs
  chargés, aucun doublon ni orphelin. »
- sinon, liste chaque doublon et chaque orphelin avec ses coordonnées et demande
  confirmation — « 1 ID(s) en double et 2 bloc(s) orphelin(s) trouvés (chunks chargés
  uniquement). Corriger maintenant ? » — puis appelle le `POST` sur réponse positive et
  rapporte « Intégrité corrigée : 1 doublon(s) délié(s), 2 orphelin(s) nettoyé(s). »

Rien n'est réparé sans confirmation.

## Comment s'en servir vraiment sur une carte qui a dérivé

La limite à anticiper, c'est que l'outil ne voit que les **chunks chargés**. Un doublon
dans un chunk que personne n'a visité depuis le redémarrage n'est pas dans l'index et ne
peut pas être trouvé.

Une procédure qui marche :

1. Fais une sauvegarde. La réparation délie au lieu de supprimer, mais une édition de
   registre erronée n'est pas récupérable.
2. Ouvre la vue Collectibles du dashboard et note le nombre d'entrées.
3. Charge la zone suspecte — traverse-la en téléportation, ou utilise un outil de
   chargement de chunks si ton pack en a un. La réconciliation tourne aussi d'elle-même au
   chargement de chaque bloc : parcourir la zone suffit à corriger les doublons dont la
   position canonique du registre fait partie des blocs chargés.
4. Appuie sur **Intégrité**. Lis le rapport avant de confirmer. Il liste toutes les
   positions mais ne dit pas laquelle sera gardée : la règle est la position du registre
   quand elle en fait partie, sinon le choix déterministe. Si ce n'est pas le bloc que tu
   veux garder, sache qu'un `PUT` sur les coordonnées de l'œuf fait plus que déplacer
   l'entrée — il retire le bloc à l'ancienne position et pose un bloc lié neuf à la
   nouvelle. Visé sur le leurre que tu préfères, il écrase ce leurre et supprime l'ancien
   bloc canonique ; réconcilie ensuite.
5. Recommence région par région. Le rapport porte sur un instantané, il n'est pas
   cumulatif.

Pour un bloc délié par erreur, relier se fait par un placement : donne-toi l'item admin
de cet id (`/eac give <id>` ou le bouton give-admin du dashboard) et pose-le où tu veux.
L'ancien bloc délié peut être cassé en admin sans toucher au registre, puisqu'il n'a plus
d'id.

## Ne jamais forcer le chargement d'un chunk depuis un handler de chargement de block entity

C'est la règle autour de laquelle ce sous-système a été reconstruit, et elle a été apprise
au prix fort.

Un correctif livré le 18 juin 2026 réconciliait les œufs directement dans le handler
`BLOCK_ENTITY_LOAD`, et cette réconciliation appelait `getBlockEntity` sur une position
qui pouvait se trouver dans un chunk déchargé. `BLOCK_ENTITY_LOAD` se déclenche **à
l'intérieur** du pipeline de montée en niveau des chunks de c2me. Un `getBlockEntity` qui
doit charger un chunk réentre dans le système de chunks depuis l'intérieur et bloque le
thread serveur en attente d'un travail qui ne peut pas avancer. Le thread serveur s'est
bloqué, le watchdog a tiré, et la production a planté deux fois dans la même nuit — 00h38
et 01h11 le 19 juin — puis est entrée en boucle de plantage, parce que le plantage se
reproduisait dès que le même chunk rechargeait.

Deux défenses sont en place dans le code, et les deux doivent y rester.

**La réconciliation est différée, pas immédiate.** `BLOCK_ENTITY_LOAD` ne fait
qu'empiler sur une `ConcurrentLinkedQueue`, et `ServerTickEvents.END_SERVER_TICK` la
vide, jusqu'à 8192 tâches par tick, chacune dans son propre try/catch. Une tâche en échec
journalise un avertissement et la vidange continue. (Quand plus de 8192 tâches attendent,
la boucle retire la 8193ᵉ avant de tester son compteur : celle-là est perdue sans
s'exécuter ; le prochain chargement de ce bloc la remet en file.)

```text
[EggAndCheese] deferred reconcile failed: <message>
```

⚠️ Le différé est une **file vidée en fin de tick**, pas un `server.execute(...)`. Le
commentaire d'`AdminEggPlacedEvent` est explicite sur la raison, et il vaut la peine de la
répéter parce que `server.execute` est la réponse réflexe : `ThreadExecutor.execute`
exécute le runnable **en ligne** quand l'appelant est déjà sur le thread serveur, ce qui
est exactement la situation ici. `server.execute` aurait exécuté la réconciliation de
façon synchrone dans le callback de chargement et aurait bloqué de la même manière. Si
jamais tu « simplifies » la file en un `server.execute`, tu réintroduis la panne.

**Les lectures de chunk ne bloquent pas.** `unbindAt` interroge d'abord
`world.getChunkManager().isChunkLoaded(cx, cz)` — une simple requête d'état, sans
`getChunkBlocking` en dessous, et donc sûre sous c2me. Ce n'est que si le chunk est déjà
chargé qu'il touche `getBlockEntity`, qui répond alors depuis le cache. Si le chunk du
doublon n'est pas chargé, `unbindAt` renvoie `false` et abandonne ; ce bloc sera
réconcilié quand son propre `BLOCK_ENTITY_LOAD` se déclenchera.

La règle générale, valable bien au-delà de ce mod : **un handler `BLOCK_ENTITY_LOAD` ou
`CHUNK_LOAD` ne doit pas forcer le chargement d'un chunk, ni directement ni via une API
qui pourrait le faire.** Mets le travail en file pour un tick ultérieur et vérifie
`isChunkLoaded` avant de toucher quoi que ce soit hors du chunk qu'on t'a donné.

---

← [L'intégration FTB Quests](ftb-quests.fr.md) · [API dashboard](dashboard.fr.md) →
