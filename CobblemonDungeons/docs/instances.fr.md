[← CobblemonDungeons](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](instances.md)

# Les instances et leur cycle de vie

Un `DungeonConfig` est un modèle. Un `DungeonInstance` est la partie d'un groupe à travers
ce modèle : son propre chronomètre, ses propres vies partagées, ses propres compteurs
d'objectifs, son propre boss, ses propres inventaires de coffres. Rien d'une instance n'est
écrit sur le disque pendant qu'elle tourne — elle vit dans les tables de `DungeonManager` et
meurt avec le serveur.

Trois tables portent cet état :

- `activeInstances`, indexée par UUID de **joueur**. Chaque membre d'un groupe pointe vers
  le même objet d'instance.
- `partyInstances`, indexée par l'UUID du chef de groupe, pour permettre à un membre qui se
  reconnecte de retrouver la partie de son groupe.
- `bossToInstance`, indexée par l'UUID de l'entité boss, pour qu'une victoire puisse être
  rattachée à la partie qui a fait apparaître ce boss.

---

## Lancer une partie

`DungeonManager.startDungeon(leader, dungeonId[, tier])` est le point d'entrée unique.
L'interface l'appelle, `/dungeons` y mène à travers l'écran de sélection, et l'effet
`DUNGEON_OPEN` de CheeseGuard l'appelle par réflexion. Aucune commande ne lance un donjon
directement.

En mode `CROSS_SERVER`, la première chose qui se produit est un contrôle de disponibilité. Le
chef reçoit un `BungeePayload.playerCount(targetServer)`, son client le relaie au proxy, et
la réponse revient sous forme de message de plugin. Pendant ce temps un `PendingStart`
attend dans une table avec un budget de 60 ticks. Le chef voit *Vérification du statut du
serveur de donjon...*. Une seconde tentative pendant qu'une première est en cours réaffiche
ce message et est ignorée pour le reste. Si aucune réponse n'arrive dans les 60 ticks,
`tickPendingStarts` abandonne la tentative et avertit le chef *Le serveur de donjon est hors
ligne. Veuillez réessayer plus tard.* — le même message qu'une réponse « hors ligne »
explicite.

Une fois que le serveur cible a répondu (ou immédiatement, en mode `SAME_SERVER`), les
vérifications s'enchaînent dans cet ordre, et le premier échec annule tout avec un message :

1. **Direction du groupe** — si le chef est dans un groupe sans en être le chef : *Seul le
   chef de groupe peut lancer un donjon !*
2. **Équipe présente** — chaque membre en ligne doit avoir au moins un Pokémon : *Vous avez
   besoin d'un ou plusieurs pokémon dans votre équipe pour entrer dans les donjons*
3. **Taille du groupe** — plus de membres que `requirements.dungeonsPartySizeLimit` :
   *Votre groupe est trop grand pour ce donjon (Max : N)*
4. **Cooldown** — un membre encore en cooldown : *<joueur> est encore en cooldown pour ce
   donjon (Xm Ys restantes)*
5. **Emplacement libre**, `SAME_SERVER` uniquement — aucun emplacement de localisation
   disponible : *Les donjons sont pleins ! Veuillez réessayer plus tard.*
6. **Ticket** — le chef doit détenir un ticket correspondant, et il est consommé ici : *Vous
   avez besoin d'un Ticket de Donjon valide pour <nom> (Tier N) pour entrer.*

Seul le chef paie un ticket. Les membres du groupe sont transportés gratuitement.

Un `CountdownData` est alors placé dans `activeCountdowns` pour chaque membre, à 5 secondes,
et tout le monde voit *Le donjon démarre dans 5 secondes... Préparez votre équipe !* Le
compte à rebours avance d'un cran à chaque appel de `tickDungeons` — toutes les 20 ticks
serveur — et affiche *Démarrage dans N...* dans la barre d'action.

Quand il atteint zéro, le `DungeonInstance` est construit. C'est l'instant où la taille du
groupe est gelée : les vies viennent du nombre de membres capturé dans le compte à rebours,
pas de qui est encore en ligne plus tard.

## La préparation, et pourquoi il y a deux téléportations

Les joueurs en `SAME_SERVER` sont téléportés immédiatement vers `hubPos` et informés
*Préparation de la zone du donjon... Veuillez patienter.* La préparation tourne ensuite sur
le tick de scan rapide :

- les registres de blocs par monde sont consultés pour les spawners d'Alpha, de Pieu, de
  Gardien et les portails situés dans les rayons configurés
- les chunks sont scannés sur un budget partagé de 32 scans par tick, toutes instances
  confondues
- les Pieux et les dresseurs Gardiens sont placés
- `isReadyForPlayers()` devient vrai quand les Pieux et les Gardiens sont apparus et
  qu'aucune tâche d'apparition de dresseur n'est encore en file

Alors seulement `tickDungeons` téléporte tout le monde vers `spawnPos` avec *Le donjon est
prêt ! Préparez-vous.* Les Alphas sont délibérément exclus de cette condition : ils
apparaissent quand un joueur entre dans `alphaTriggerRadius` d'un spawner, donc les attendre
reviendrait à attendre indéfiniment.

Le HUD est poussé sous forme de `DungeonHudPayload` à chaque passage de `tickDungeons`,
portant les quatre compteurs d'objectifs avec leurs cibles, le temps restant et les vies
restantes.

## Les objectifs et le déverrouillage du boss

`canAccessBoss()` est une conjonction : chaque clé de `requirements.requiredKeyItems`
collectée, et les quatre compteurs à leur exigence ou au-delà. Rien de partiel ne compte.

Quand elle devient vraie pour la première fois, `checkPortalActivation` rend visible chaque
bloc de portail de l'instance et annonce *Le portail a été activé ! Tous les objectifs sont
complétés.* Avant cela, le portail est invisible et un clic droit dessus ne fait rien. Un
portail visible cliqué par quelqu'un qui n'est dans aucune partie répond *Vous devez être
dans un donjon actif pour utiliser ce portail.*, et par quelqu'un dont la propre partie n'a
pas déverrouillé le boss, *Vous devez compléter tous les objectifs du donjon avant de
combattre le boss !* Entrer dans un portail ne fait rien du tout.

Un clic droit sur un portail visible ouvre l'invite de boss. L'accepter invoque le boss à
`bossSpawnPos` — ou à la surcharge de l'emplacement actif — par `spawnBoss`, puis téléporte
chaque membre en ligne vers le `bossRoomPos` du **premier** emplacement et le marque comme
visiteur de la salle du boss, ce qui compte pour les réapparitions.

## Mourir, être KO, et manquer de temps

Trois choses coûtent une vie, et les trois partagent un compteur unique pour tout le groupe :

| Événement | Gestionnaire | Message |
|---|---|---|
| Un joueur meurt | `handlePlayerDeath` | *Un joueur est mort ! N vies restantes pour le groupe.* |
| Toute l'équipe Pokémon d'un joueur est KO | `handlePokemonTeamWipe` | *Toute votre équipe est KO ! N vies restantes pour le groupe.* plus *L'équipe de <joueur> est KO ! N vies restantes.* aux autres |
| Les vies tombent à zéro | `failDungeon` | *Vies du groupe épuisées ! Le donjon a échoué.* |
| Le chronomètre atteint zéro | `failDungeon` | *Le temps est écoulé ! Le donjon a échoué.* |

Une équipe KO alors qu'il reste des vies soigne l'équipe du joueur et — sur un serveur de
rôle `dungeon` uniquement — le téléporte : vers `bossRoomPos` si le boss est déverrouillé
**et** qu'il est déjà entré dans la salle du boss, vers `spawnPos` sinon (tous deux pris dans
le premier emplacement). Sur un serveur de rôle principal, mode `SAME_SERVER` compris, le
joueur reste où il est. Une réapparition après la mort suit la même règle sur tout serveur,
avec l'emplacement actif. La condition
« a déjà visité la salle du boss » est ce qui empêche un arrivant tardif d'être largué dans
un combat de boss où il n'a jamais mis les pieds.

Les deux chemins d'échec enregistrent la partie comme non réussie, diffusent `dungeon_fail`
au dashboard, nettoient les entités du donjon, puis appellent `leaveDungeon` pour chaque
membre encore en ligne. Les joueurs hors ligne à cet instant sont simplement retirés
d'`activeInstances`.

## Terminer une partie

`handleBossDefeated` ne s'exécute qu'une fois par instance, protégé par le drapeau
`bossDefeated` :

1. le boss est retiré de `bossToInstance` et l'instance est marquée terminée
2. la partie est ajoutée à l'historique et `dungeon_complete` part sur le WebSocket
3. un cooldown est enregistré pour **chaque** membre, et le classement est mis à jour
4. chaque membre tire son `rewardChance` pour recevoir le Pokémon boss lui-même, livré dans
   son PC (*<customName du boss> a été transféré dans votre PC !* — le Pokémon porte ce
   surnom), et son équipe est soignée
5. *Félicitations ! Le boss du donjon a été vaincu !* et *Le groupe a gagné ! Vous serez
   téléportés dans 10 secondes.*
6. les récompenses de complétion sont distribuées
7. `finishDelayTicks` est fixé à 10

Ce délai est compté en passages de `tickDungeons`, donc dix d'entre eux — dix secondes à
cadence normale, ce que promet le message de chat. À son expiration, chaque membre passe par
`leaveDungeon`.

## Quitter

`leaveDungeon` est appelé par `/dungeons leave`, par le délai de fin, par `failDungeon`, et
par la sortie forcée du dashboard. Il masque toujours le HUD, puis bifurque selon
`teleportMode` :

- `SAME_SERVER` — téléportation vers le `returnPos` de l'emplacement actif.
- `CROSS_SERVER` — envoi du transfert de retour (`DungeonJoinPayload`), puis transfert vers
  `mainServer` 40 ticks plus tard.

Ce qui est rapatrié dépend de la mort du boss. En cas de succès et avec `syncPlayerData`
activé, l'état complet du joueur — inventaire, équipe Cobblemon, PC — est sérialisé et,
quand SQL est activé, écrit en base. En cas d'échec, seule la position de retour voyage, et
le joueur est averti sans détour : *Vous avez quitté ou échoué le donjon. Vos objets et votre
progression n'ont PAS été sauvegardés.*

Ce message ne dépend que de `syncPlayerData`, pas du mode. En mode `SAME_SERVER`, rien n'est
sérialisé ni annulé — le joueur est simplement téléporté avec tout ce qu'il porte — et
pourtant, avec `syncPlayerData: true` (la valeur par défaut), il lit que ses objets n'ont pas
été sauvegardés. Avec `syncPlayerData: false`, le message n'apparaît jamais.

⚠️ C'est la conception, et elle est sévère : tout ce qu'un joueur a ramassé dans un donjon
`CROSS_SERVER` est perdu s'il échoue ou s'il en sort. Dis-le sur ton serveur avant que
quelqu'un ne l'apprenne à ses frais.

Le joueur est retiré d'`activeInstances`. Quand le dernier membre part,
`clearDungeonEntities` s'exécute et l'association de groupe est supprimée.

Un joueur qui n'était dans aucune partie reçoit *Vous n'êtes pas actuellement dans une
instance de donjon.*

## Se déconnecter en pleine partie

`handleDisconnect` est branché sur `ServerPlayConnectionEvents.DISCONNECT`. Il annule tout
compte à rebours en cours, retire le joueur d'`activeInstances` et de l'ensemble des joueurs
de l'instance, et — s'il s'agissait du dernier membre — nettoie les entités du donjon et
l'association de groupe.

Rien n'est sauvegardé. La partie n'est pas enregistrée, aucun cooldown n'est noté, le
classement n'est pas touché. Pour le joueur qui se déconnecte, c'est un échec silencieux.

Le retour est géré par `handleRejoin` à la connexion : si le joueur est dans un groupe dont
le chef a encore une instance vivante dans `partyInstances`, il y est rajouté et téléporté —
vers la salle du boss si le boss est déverrouillé et qu'il l'avait visitée, vers `spawnPos`
si le groupe a déjà été téléporté à l'intérieur, vers `hubPos` sinon — et le HUD est
renvoyé.

Deux conséquences à connaître :

- Un joueur **solo** ne peut pas revenir. Sans groupe il n'y a pas d'entrée dans
  `partyInstances`, et l'instance a déjà été démontée puisqu'il était le dernier membre.
- Un joueur en groupe qui se reconnecte **après** le départ du dernier autre membre ne peut
  pas revenir non plus, pour la même raison.

## Le nettoyage

`clearDungeonEntities` remet les spawners de Pieu à « pas de pieu présent », rend chaque
bloc de portail invisible à nouveau, puis balaie les entités dans une boîte autour de
`spawnPos` élargie de `alphaSpawnRadius + 50`. Ce qu'il supprime dépend du rôle du serveur :

- Sur un serveur de rôle **dungeon**, chaque entité non joueur de la boîte est supprimée.
- Sur un serveur de rôle **main**, le balayage est sélectif : les Pokémon Alpha et sans
  propriétaire, les objets au sol, et les entités dont le nom contient `key`, `gem`, `stake`
  ou `boss` — à l'exception de tout ce qui s'appelle *dungeon master*.

La branche sélective est une heuristique par correspondance de chaînes, et elle emportera la
mauvaise entité si tu as nommé quelque chose malencontreusement. Faire tourner les donjons
sur un serveur dédié de rôle `dungeon` évite entièrement la question.

## Les parties cross-serveur

Le mode `CROSS_SERVER` repose sur trois pièces mobiles :

1. **Le rôle du serveur.** `CobblemonDungeons.isDungeonServer` lit la propriété système
   `cobblemon.dungeons.serverRole`, avec repli sur la variable d'environnement
   `COBBLEMON_DUNGEONS_SERVER_ROLE`. La valeur `dungeon` (sans distinction de casse) en fait
   un serveur de donjon ; toute autre valeur, absence comprise, en fait un serveur
   principal. Le résultat est mis en cache au premier appel et journalisé sous
   `Cobblemon Dungeons server role: dungeon` ou `… : main`. Le changer exige un redémarrage.
2. **Le payload de connexion.** `DungeonJoinPayload` porte le transfert lui-même. Le client
   le relaie pendant le changement de serveur, ce qui rend un transfert atomique sans base
   de données.
3. **Le plugin du proxy.** L'état du groupe est transmis entre serveurs par le mécanisme
   BungeeCord `Forward` et par le canal personnalisé `cobblemon_dungeons:proxy_forward`.
   Velocity ne les transmet pas de lui-même, d'où `velocity-party-forwarder/`. Voir
   [l'installation](installation.fr.md).

Avec SQL activé, le PC est lu en base au lieu de voyager avec le transfert, parce qu'un PC
complet n'a rien à faire dans un message de plugin.

⚠️ Les payloads client→serveur du mode `CROSS_SERVER` ne sont pas validés par le serveur, et
un client modifié peut en abuser. N'utilise pas ce mode sur un serveur public tant que ce
n'est pas corrigé.

### Fragilité connue

Le contrôle de disponibilité est un message de plugin BungeeCord `PlayerCount` envoyé par
la connexion du chef : il n'obtient de réponse que si le proxy gère ce canal et connaît le
serveur nommé. Faute de réponse, le symptôme est *Vérification du statut du serveur de
donjon...*, puis, trois secondes plus tard, *Le serveur de donjon est hors ligne. Veuillez
réessayer plus tard.* même si le serveur est en réalité démarré.

---

← [La définition d'un donjon](dungeons.fr.md) · [Les tickets](tickets.fr.md) →
