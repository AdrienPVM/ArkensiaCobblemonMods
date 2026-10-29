[← CobbleCap](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](commands.md)

# Commandes

CobbleCap enregistre trois racines de commande :

| Racine | Côté | Enregistrée dans |
|---|---|---|
| `/cobblecap` | serveur | `CobbleCapCommand.register` |
| `/explock` | serveur | `ExpLockCommand.register` |
| `/pasturehide` | **client** | `PastureHideCommand.register` |

`/pasturehide` est une commande *client* Fabric. Elle n'atteint jamais le serveur, donc elle
fonctionne sur n'importe quel serveur du moment que le joueur a CobbleCap installé, et la
taper dans une console ne fait rien puisqu'une console n'a pas de client.

L'arbre `/cobblecap` est majoritairement en niveau 2 (opérateur), avec quelques exceptions
que tout joueur peut lancer : la racine nue, `status` sans cible, `hud …`, `legends …` et
`mode classic|ranked`. Brigadier masque une sous-commande qu'un joueur ne peut pas utiliser,
donc un non-op qui tape `/cobblecap ` ne voit que celles-là ; taper une sous-commande
verrouillée malgré tout donne l'erreur vanilla *Unknown or incomplete command*.

Cette page couvre les sous-commandes de limite, de joueur et de configuration. Les
sous-arbres statue, chrono et champion sont assez gros pour avoir leur propre page :
[commandes statues, chrono et champion](commands-statues.fr.md).

Il n'y a aucun alias. Rien n'est abrégé.

---

## `/cobblecap`

```mcfunction
/cobblecap
```

Aucun argument, aucune permission, **doit être lancé par un joueur**. Affiche sa propre
limite, le champion suivant, et les conditions non remplies pour ce champion.

```text
Votre limite de niveau : 45
Prochain champion : Erika
  - Pokédex : 61/80 capturés
```

Deux conditions seulement sont listées ici, et seulement quand elles échouent : le compte au
Pokédex (`requiredPokedex`) et la quête FTB (`requiredQuestId`). La condition de scoreboard
(`requiredScoreboard`) est bien imposée au PNJ mais **n'est pas affichée** par cette
commande — un joueur bloqué par une condition de scoreboard ne voit aucune raison. Utilise
[`/cobblecap status <player>`](#cobblecap-status) en tant qu'admin pour la voir.

| Message | Cause |
|---|---|
| `This command must be run by a player.` | lancée depuis la console ou un bloc de commande |
| `Joueur %s introuvable dans les données CobbleCap.` | aucune entrée dans `players.json` ; normalement impossible, puisque se connecter en crée une |

Quand tous les champions sont battus, la seconde ligne devient
`Tous les champions ont été vaincus !`.

## `/cobblecap status`

```mcfunction
/cobblecap status
/cobblecap status <player>
```

| Argument | Type | Obligatoire | Permission |
|---|---|---|---|
| *(aucun)* | — | — | tout joueur — identique à `/cobblecap` nu |
| `player` | sélecteur d'entité résolvant un joueur | oui pour cette forme | **niveau 2** |

La forme ciblée est un vidage de diagnostic, et c'est la première chose à lancer quand
quelqu'un dit qu'il ne peut pas affronter un champion. Elle affiche, dans l'ordre : UUID,
système activé, mode de jeu, niveau de permission, dimension, position ; niveau de limite,
blocage d'XP, état du HUD, dernier changement de limite ; chaque champion avec `✓`/`✗` et
`✦` pour un défi battu ; l'analyse complète des conditions du champion suivant avec
`OK`/`FAIL` par condition ; les horodatages des derniers défis ; **chaque objectif de
scoreboard** sur lequel ce joueur a un score ; les étiquettes de commande ; l'équipe de
scoreboard ; et chaque advancement `cobblecap:`/`cobblemon:` accordé.

```text
═════ CobbleCap status — Steve ═════
UUID: f84c6a79-0a4e-45e0-aa79-5d0ba2f4a2b1
System enabled: true
…
─── Champions (4/13 beaten) ───
  ✓ gym_leader_pierre_0395 (Pierre, cap=30)
  ✗ gym_leader_erika_0399 (Erika, cap=50)
─── Next champion gating ───
Next: gym_leader_erika_0399 (Erika)
  cap granted: 50 | allowRefight: true
  victoryQuestId: 0123456789ABCDEF
  challengeId: ''
  scoreboard arkensia_tour >= 12 (current=9) FAIL
  ftb quest: (none)
  pokedex: 61/80 FAIL
─── Last challenge times (0) ───
  (none)
…
═════ end status ═════
```

C'est long — compte soixante à cent lignes de chat sur un serveur développé. Lance-la
depuis la console ou le terminal du dashboard plutôt que dans le chat si tu peux.

`Joueur %s introuvable dans les données CobbleCap.` en erreur signifie que la cible n'a
aucune entrée stockée.

## `/cobblecap hud`

```mcfunction
/cobblecap hud on
/cobblecap hud off
/cobblecap hud position <corner>
```

Aucune permission, doit être un joueur. La préférence est stockée par joueur dans
`players.json` et poussée immédiatement au client via `CapSyncPayload` : le changement est
visible sans reconnexion.

| Argument | Type | Valeurs acceptées | Stocké comme |
|---|---|---|---|
| `corner` | mot unique, autocomplété | `bottom-right`, `bottom-left`, `top-right`, `top-left` | `0`, `1`, `2`, `3` |

```mcfunction
/cobblecap hud position top-left
```

```text
HUD déplacé vers top-left.
```

| Message | Cause |
|---|---|
| `Le HUD est déjà visible.` / `Le HUD est déjà masqué.` | la bascule était déjà dans cet état ; rien n'est écrit |
| `Unknown position: <x>. Use: bottom-right, bottom-left, top-right, top-left` | le mot ne correspond pas ; l'autocomplétion suggère les quatre mais ne les impose pas |
| `This command must be run by a player.` | source non joueur |

Le HUD lui-même est décrit dans [le côté client](client.fr.md).

## `/cobblecap set <player> <level>`

```mcfunction
/cobblecap set <player> <level>
```

**Niveau 2.**

| Argument | Type | Obligatoire | Plage |
|---|---|---|---|
| `player` | sélecteur d'entité résolvant un joueur | oui | doit être **en ligne** |
| `level` | entier | oui | 1–100, imposé par Brigadier |

Crée l'entrée du joueur si elle manque, écrit la limite, journalise
`[CobbleCap] <source> set cap for <nom> to <niveau>`, et synchronise le client.

```mcfunction
/cobblecap set Steve 60
```

```text
Limite de Steve fixée à 60.
```

Le retour est diffusé aux autres opérateurs (`sendFeedback(…, true)`), comme pour chaque
sous-commande `/cobblecap` qui utilise le helper interne `reply`.

⚠️ Cela ne fixe que le nombre. `championsBeaten` n'est pas touché, donc la condition d'ordre
croit toujours que le joueur est à son ancien point de progression : relever une limite à la
main laisse ses Pokémon monter mais ne l'autorise **pas** à affronter le champion suivant.
C'est exactement la désynchronisation que `addbeaten` sert à réparer, et `CobblemonHook`
journalise un avertissement nommant le correctif quand il la détecte.

Les valeurs hors plage sont rejetées par Brigadier avant l'exécution du gestionnaire, avec
l'erreur vanilla *Integer must be between 1 and 100*.

## `/cobblecap get <player>`

```mcfunction
/cobblecap get <player>
```

**Niveau 2.** Lit la limite d'un joueur.

```text
Limite actuelle de Steve : 60.
```

`Joueur Steve introuvable dans les données CobbleCap.` si l'entrée n'existe pas.

## `/cobblecap reset <player>`

```mcfunction
/cobblecap reset <player>
```

**Niveau 2.** Un effacement complet de la progression d'un joueur, pas seulement de sa
limite. Dans l'ordre :

1. limite ramenée au `startingCap` de `config.json`
2. `championsBeaten` vidé
3. `challengeBeaten` vidé
4. `lastChallengeTimes` vidé
5. `expLocked` remis à `false`
6. pour **chaque** champion de `champions.json`, quatre commandes vanilla dispatchées
   silencieusement via la source de commande du serveur :
   - `advancement revoke <joueur> only cobblecap:champion/<clé>`
   - `advancement revoke <joueur> only cobblecap:challenge/<clé>`
   - `ftbquests change_progress <joueur> reset <victoryQuestId>` si défini
   - `ftbquests change_progress <joueur> reset <challengeQuestId>` si défini

```mcfunction
/cobblecap reset Steve
```

```text
Limite de Steve réinitialisée à 20 (valeur de départ).
```

La `<clé>` d'advancement est dérivée du **nom affiché** du champion, en minuscules avec les
espaces convertis en tirets bas — pas de son `id`. Donc `displayName: "Major Bob"` donne
`cobblecap:champion/major_bob`. Si tu renommes un champion dans `champions.json`, la
révocation cesse silencieusement de correspondre et des advancements périmés survivent à la
réinitialisation, ce qui maintient le dialogue du PNJ rctmod en mode post-victoire. Chaque
dispatch est enveloppé dans un `catch (Exception ignored)` nu, donc un advancement manquant
ou une absence de FTB Quests échoue sans bruit et la commande annonce quand même un succès.

Il n'y a aucune étape de confirmation et aucun retour en arrière.

## `/cobblecap addbeaten <player> <championId>`

```mcfunction
/cobblecap addbeaten <player> <championId>
```

**Niveau 2.** Outil de réparation. Marque un champion comme battu et relève la limite au
`capGranted` de ce champion si la limite actuelle est inférieure.

| Argument | Type | Obligatoire | Notes |
|---|---|---|---|
| `player` | sélecteur d'entité résolvant un joueur | oui | en ligne |
| `championId` | chaîne **entre guillemets** | oui | autocomplétée depuis les identifiants chargés ; doit exister |

L'argument est un `StringArgumentType.string()`, la variante entre guillemets.
L'autocomplétion insère l'identifiant pour toi ; si tu le tapes à la main, mets-le entre
guillemets s'il contient quoi que ce soit que Brigadier n'accepterait pas nu.

```mcfunction
/cobblecap addbeaten Steve "gym_leader_pierre_0395"
```

```text
Added 'gym_leader_pierre_0395' to Steve's beaten list — cap raised to 30.
```

La proposition finale varie : ` (already present)` quand l'identifiant était déjà dans la
liste, ` — cap raised to N` quand `raiseCapLevel` a renvoyé vrai, et rien du tout quand
l'identifiant était nouveau mais la limite déjà égale ou supérieure à `capGranted`.

| Message | Cause |
|---|---|
| `Unknown champion id: <id>` | absent de `champions.json` — renvoie 0, rien n'est écrit |

Utilise-la après une paire `reset` + `set`, ou quand un `BATTLE_VICTORY` n'a pas été
persisté. L'avertissement qui te dit de la lancer ressemble à ceci dans le log serveur :

```text
[CobbleCap] Order-check rejection may be a desync: player=Steve cap=50 tried=gym_leader_erika_0399 expectedNext=gym_leader_ondine_0396 championsBeaten=[…]. If the player legitimately beat prior champions, repair with: /cobblecap addbeaten Steve gym_leader_ondine_0396
```

## `/cobblecap removebeaten <player> <championId>`

```mcfunction
/cobblecap removebeaten <player> <championId>
```

**Niveau 2.** L'inverse, avec une asymétrie à connaître : il retire l'identifiant de
**`championsBeaten` et de `challengeBeaten`**, et il ne valide **pas** l'identifiant contre
`champions.json`. Un identifiant qui n'existe dans aucune des deux listes est accepté et ne
fait rien. La limite n'est pas abaissée.

```text
Removed 'gym_leader_pierre_0395' from Steve's beaten list.
```

Les advancements et les quêtes FTB ne sont pas révoqués par cette commande — seul `reset` le
fait. Après un `removebeaten`, le PNJ peut donc encore saluer le joueur en mode
post-victoire.

## `/cobblecap reload`

```mcfunction
/cobblecap reload
```

**Niveau 2.** Relit `config/cobblecap/config.json` et `config/cobblecap/champions.json`
depuis le disque, pousse l'URL de webhook dans `CobbleCapWebhook`, puis synchronise chaque
client en ligne pour que la ligne *Suivant* du HUD reflète une liste de champions modifiée.

```text
Config champions rechargée (13 champions chargés).
```

Ce que `reload` ne reprend **pas** : `startingCap` est bien relu dans `CapConfig`, donc les
nouveaux arrivants reçoivent la nouvelle valeur, mais les entrées existantes conservent la
leur. Les données joueur (`players.json`) ne sont pas relues — la mémoire est l'autorité, et
recharger perdrait les modifications non sauvegardées.

Un fichier JSON mal formé ne produit **pas** la ligne
`[CobbleCap] Failed to load champions.json: …` : celle-ci n'est journalisée que pour une
erreur d'E/S. Une erreur de syntaxe lève une `JsonSyntaxException` que ni `CapConfig.load`
ni `ChampionConfig.load` n'interceptent ; elle remonte jusqu'à Brigadier, le chat affiche
l'erreur vanilla *An unexpected error occurred trying to execute that command*, aucun compte
n'est annoncé et aucun client n'est resynchronisé. Si c'est `champions.json` qui est cassé,
la liste en mémoire reste celle d'avant. Vérifie le log serveur après chaque modification.

## `/cobblecap enable` · `/cobblecap disable`

```mcfunction
/cobblecap enable
/cobblecap disable
```

**Niveau 2.** Basculent `CapConfig.enabled` et sauvegardent `config.json` immédiatement.
Chaque client en ligne est resynchronisé pour que le HUD puisse se masquer.

```text
Système de limite de niveau activé.
Système de limite de niveau désactivé.
```

Déjà dans cet état, tu obtiens
`Le système de limite de niveau est déjà activé.` / `… est déjà désactivé.` et rien n'est
écrit.

Ce que la désactivation arrête réellement est listé dans
[la limite de niveau](level-cap.fr.md#ce-que-enabled-false-coupe) — notamment, elle
désactive aussi les contrôles d'espèces interdites et la détection de victoire, pas
seulement la limite d'XP.

## `/cobblecap legends`

```mcfunction
/cobblecap legends
/cobblecap legends by_time
```

Aucune permission. Liste le Panthéon depuis `config/cobblecap_legends.json`. Les entrées de
test sont exclues.

`legends` les liste dans l'ordre de couronnement, avec une date `jj/MM/aaaa` :

```text
─── Maîtres de la Ligue Pokémon (3) ───
🥇 Steve — 14/05/2026 (premier Maître)
2. Alex — 02/06/2026
3. Herobrine — 19/06/2026
```

`legends by_time` écarte au contraire ceux qui n'ont aucun temps enregistré
(`bestTimeMs <= 0`) et trie par meilleur temps de Conseil 4 croissant :

```text
─── Maîtres de la Ligue par temps (2) ───
1. §6Alex§r — 18m42s
2. §6Steve§r — 24m05s
```

Si personne n'est couronné :
`Aucun Maître de la Ligue Pokémon n'a encore été couronné. À vous de jouer !`

Le Panthéon et les chronos sont décrits dans
[le système de champion](champions.fr.md#le-panthéon).

## `/cobblecap mode`

```mcfunction
/cobblecap mode classic
/cobblecap mode ranked
/cobblecap mode set <player> classic
/cobblecap mode set <player> ranked
```

Les deux premières formes sont auto-ciblées et n'exigent **aucune permission** — elles
existent pour être câblées aux boutons de dialogue d'Alfred dans le hall du Conseil 4. La
forme `set` prend une cible et exige le **niveau 2**.

Chaque invocation fait deux choses :

1. Écrit `1` (classique) ou `2` (compétitif) dans le score du joueur sur l'objectif de
   scoreboard vanilla `cobblecap_mode`, en créant d'abord l'objectif avec
   `scoreboard objectives add cobblecap_mode dummy` s'il manque pour une raison quelconque.
2. Appelle `RegisModeManager.applyMode`, qui bascule le **JSON de dresseur Régis actif**
   vers la variante correspondante et dispatche un rechargement rctmod.

```text
Conseil 4 mode → ranked for Steve (JSON swapped + reload dispatched)
```

ou, quand la variante demandée était déjà celle en place :

```text
Conseil 4 mode → classic for Steve (already live)
```

`(already live)` s'affiche dès que `applyMode` renvoie `false`, ce qui couvre aussi les
échecs : aucun instantané dans `config/cobblecap_regis_backup/`, aucune racine de datapack
trouvée, ou une copie qui échoue. Si la ligne dit `already live` et que le combat n'a pas
changé, cherche dans le log `[CobbleCap] … swap aborted` ou `Régis JSON copy failed`. Une
demande `ranked` sans instantané compétitif retombe sans bruit sur le fichier classique.

⚠️ Le basculement de JSON est **global au serveur**, alors que le score de scoreboard est
par joueur. Deux joueurs choisissant des modes différents au même moment affrontent la
variante écrite en dernier. C'est une vraie limite de la conception, et non un bug à
contourner côté commande ; voir
[le morphing du champion](champion-morphing.fr.md#le-basculement-de-mode-de-régis-est-global).

`This command must target a player` en erreur signifie que la forme auto-ciblée a été
lancée depuis une source non joueur.

L'échec de l'écriture du scoreboard n'est pas fatal : il journalise
`[CobbleCap] cobblecap_mode score set failed: …` et le basculement de JSON a quand même
lieu.

---

## `/explock`

```mcfunction
/explock
/explock <player>
```

| Forme | Permission | Effet |
|---|---|---|
| `/explock` | aucune, doit être un joueur | bascule le blocage d'XP de l'appelant |
| `/explock <player>` | **niveau 2** | bascule le blocage d'XP de ce joueur |

C'est une bascule, pas un setter — il n'y a pas de forme `on`/`off`, donc la seule façon de
connaître l'état résultant est le message, ou `/cobblecap status <player>`.

Le joueur dont le blocage a changé reçoit toujours le message, même quand c'est un admin qui
l'a basculé pour lui :

```text
🔒 Blocage d'XP activé — vos Pokémon ne gagnent plus d'expérience.
🔓 Blocage d'XP désactivé — vos Pokémon peuvent de nouveau gagner de l'expérience.
```

Et quand un admin **joueur** a ciblé quelqu'un d'autre, l'admin voit en plus, diffusé aux
opérateurs (lancée depuis la console ou un bloc de commande, `/explock <player>` n'affiche
aucune confirmation — seule la cible est prévenue) :

```text
Blocage d'XP pour Steve : ON
```

| Message | Cause |
|---|---|
| `This command must be run by a player.` | forme auto-ciblée depuis une source non joueur |
| `Player data not found.` | `ensureExists` a tourné mais l'entrée manque toujours — une anomalie interne, signale-la |

Un blocage d'XP est honoré indépendamment de `enabled`, et il prime sur la limite dans les
trois couches d'application. Voir
[le blocage d'XP](level-cap.fr.md#le-blocage-dxp-est-un-interrupteur-séparé).

---

## `/pasturehide`

```mcfunction
/pasturehide
/pasturehide on
/pasturehide off
/pasturehide toggle
/pasturehide status
```

**Commande client.** Aucune permission d'aucune sorte — c'est le moteur de rendu du joueur.
La forme nue et `toggle` sont identiques.

Le réglage masque les Pokémon Cobblemon attachés à un bloc de pâturage, pour ce client
seulement, et il est persisté dans `config/cobblecap_client.properties` :

```properties
#CobbleCap client preferences
pasture_pokemon_hidden=true
```

```text
🙈 Pasture Pokémon hidden (client-only)
👁 Pasture Pokémon visible again
Pasture Pokémon hidden: ON
```

Aucun message d'échec — rien de ce qu'elle fait ne peut échouer au niveau de la commande. Si
la bascule semble ne rien faire, la cause est en amont de la commande : le client n'a aucun
instantané de pâturage venant du serveur. [Le côté client](client.fr.md#pasturehide)
explique pourquoi le serveur doit envoyer cette liste et ce qu'il faut vérifier.

---

## Piloter le mod sans commandes

La limite, la liste des champions, la configuration et le test des feux d'artifice sont tous
joignables en REST sous `/api/v1/cobblecap`. Voir [l'API dashboard](dashboard.fr.md). Les
sous-systèmes statue, chrono, morph et scoreboard sont **uniquement en commande** — le
dashboard ne les expose pas.

---

← [Le système de champion](champions.fr.md) · [Commandes statues, chrono et champion](commands-statues.fr.md) →
