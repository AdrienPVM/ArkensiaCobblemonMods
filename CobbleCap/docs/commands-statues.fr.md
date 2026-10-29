[← CobbleCap](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](commands-statues.md)

# Commandes statues, chrono et champion

Trois sous-arbres de `/cobblecap`, tous attachés par `StatueCommand.attach` et tous
verrouillés au **niveau de permission 2** à la racine du sous-arbre. Un non-op n'en voit
aucun dans l'autocomplétion.

| Sous-arbre | Ce qu'il pilote |
|---|---|
| `/cobblecap statue …` | [le sous-système de statues et dioramas](statues.fr.md) |
| `/cobblecap chrono …` | le chronomètre du Conseil 4, et la promotion de Maître de la Ligue qu'un `stop` peut déclencher |
| `/cobblecap champion …` | [le morphing du champion](champion-morphing.fr.md) — la réécriture du dresseur RCT et le repli par NPC |

La plupart de ces commandes renvoient un **compte** plutôt qu'un booléen, ce qui les rend
utilisables sous `/execute store result`.

---

## L'argument `slot`

Toute commande de statue qui modifie un diorama existant prend un `slot`, normalisé par
`StatueCommand.normalizeSlot` avant usage :

| Ce que tu tapes | Signification |
|---|---|
| `player` ou `p` | la figure du dresseur |
| `1`–`6` | cet emplacement d'équipe — **converti en base 0 en interne** |
| `all` | toutes les entités de ce diorama (`move`, `rotate`, `scale`, `style` uniquement) |
| autre chose | passé tel quel en minuscules, et ne correspondra à rien |

L'autocomplétion propose `player 1 2 3 4 5 6` pour `remove_slot`, et les mêmes plus `all`
pour `move`, `rotate`, `scale` et `style`. Les suggestions sont des indications : une valeur
non reconnue est acceptée et n'affecte aucune entité, donc une commande qui annonce
`Moved 0 entit(y/ies)` est d'ordinaire une faute de frappe dans `slot`, pas un diorama
manquant.

## L'argument `style`

Treize styles, issus de l'énumération `StatueStyle`. Autocomplétés partout, et
**`StatueStyle.fromKey` retombe sur `natural` pour tout ce qu'il ne reconnaît pas** au lieu
d'échouer — un style mal orthographié donne silencieusement une statue sans teinte.

| Clé | Couleur de lueur | Aura de particules |
|---|---|---|
| `natural` | — | aucune (textures d'origine, pas de lueur) |
| `stone` | `0xAAAAAA` | `white_ash` |
| `gold` | `0xFFD700` | `end_rod` |
| `emerald` | `0x50C878` | `happy_villager` |
| `diamond` | `0x5DECF5` | `enchanted_hit` |
| `amethyst` | `0xB180EB` | `dragon_breath` |
| `copper` | `0xC87533` | `wax_on` |
| `netherite` | `0x4D4544` | `sculk_soul` |
| `obsidian` | `0x42325B` | `falling_obsidian_tear` |
| `crystal` | `0xF8E7FF` | `glow` |
| `glass` | `0xCCEEFF` | `bubble_pop` |
| `frost` | `0x88CCFF` | `snowflake` |
| `soul` | `0x29D3DB` | `soul_fire_flame` |

`/cobblecap statue styles` affiche la liste vivante, qui est la réponse autoritaire si ce
tableau finit par s'écarter de l'énumération.

---

## `/cobblecap statue place <playerName> [style] [scale]`

```mcfunction
/cobblecap statue place <playerName> [style] [scale]
```

Place un **diorama complet** — le dresseur et ses six Pokémon enregistrés — à la position de
l'invocateur. Doit être lancée par un joueur : la position et l'orientation de l'invocateur
servent d'ancre.

| Argument | Type | Obligatoire | Accepté | Défaut |
|---|---|---|---|---|
| `playerName` | mot unique | oui | un nom présent dans `cobblecap_legends.json` | — |
| `style` | mot unique | non | une clé de style, autocomplétée | `natural` |
| `scale` | double | non | 0.2–5.0 | `1.0` |

La recherche est `findLatestForPlayerName(playerName, includeTests = false)`, donc un joueur
qui n'a que des entrées de test est traité comme n'en ayant aucune. L'équipe mise en scène
est celle **figée au moment où il est devenu Maître de la Ligue**, pas son équipe actuelle.

```mcfunction
/cobblecap statue place Steve gold 1.5
```

La réponse est le `StatueManager.PlaceResult.message()`, en vert en cas de succès et en
rouge en cas d'échec, et le résultat numérique de la commande est le nombre d'entités
créées.

| Message | Cause |
|---|---|
| `Must be run by a player` | console ou bloc de commande |
| `No legend found for player: <nom>` | aucune entrée non-test sous ce nom exact ; les noms sont comparés sans tenir compte de la casse par `findLatestForPlayerName` mais doivent être exacts par ailleurs |

## `/cobblecap statue place_player <playerName> [style] [scale]`

Mêmes arguments et mêmes échecs, mais place **seulement la figure du dresseur**. Renvoie `1`
en cas de succès.

## `/cobblecap statue place_pokemon <playerName> <slot> [style] [scale]`

```mcfunction
/cobblecap statue place_pokemon <playerName> <slot> [style] [scale]
```

Place **un** Pokémon de l'équipe enregistrée.

| Argument | Type | Obligatoire | Accepté |
|---|---|---|---|
| `playerName` | mot unique | oui | un nom de légende non-test |
| `slot` | entier | oui | **1–6**, converti en base 0 avant l'appel |
| `style` | mot unique | non | clé de style |
| `scale` | double | non | 0.2–5.0 |

Ici `slot` est un vrai argument entier, donc Brigadier rejette `0`, `7` et `player`. C'est
différent du `slot` de `remove_slot`/`move`/`rotate`/`scale`/`style`, qui est un mot.

## `/cobblecap statue place_current [style] [scale]`

```mcfunction
/cobblecap statue place_current [style] [scale]
```

Place un diorama **dynamique** lié à l'identité du champion courant plutôt qu'à un joueur
nommé. Quand le dresseur RCT morphe vers un nouveau vainqueur, ce diorama est démonté et
recréé avec la skin et l'équipe du nouveau champion.

```text
[CURRENT] <message> — will auto-refresh when the champion morphs
```

Ne prend pas de `playerName`. L'ancre est persistée dans `config/cobblecap_dioramas.json`
pour survivre à un redémarrage. Détails dans
[le sous-système de statues](statues.fr.md#le-diorama-dynamique-du-champion).

## `/cobblecap statue place_current_player [style] [scale]`

La même chose sans la rangée de Pokémon — et l'omission persiste à travers les
rafraîchissements, donc un changement de champion ne met à jour que la skin du dresseur.

```text
[CURRENT player-only] <message> — auto-refreshes player skin only when champion morphs (no Pokémon)
```

## `/cobblecap statue test [style] [scale]`

```mcfunction
/cobblecap statue test [style] [scale]
```

Fige l'**équipe actuelle de l'invocateur**, écrit une entrée de légende marquée
`isTest = true`, et met en scène un diorama complet à partir d'elle. C'est ainsi qu'on
prévisualise un style ou une échelle sans toucher au vrai Panthéon.

```text
[TEST] Captured team (6 Pokémon) — <message> — clear with /cobblecap statue test_clear
```

Une légende de test n'apparaît jamais dans `/cobblecap legends`, n'est jamais annoncée, et
est ignorée par toutes les recherches `place*`. Elle n'existe que pour alimenter cette
prévisualisation.

## `/cobblecap statue test_clear`

```mcfunction
/cobblecap statue test_clear
```

Doit être lancée par un joueur, et ne touche jamais que **les données de test de
l'invocateur** : les entités de statue marquées test, et les entrées de légende de test pour
l'UUID de l'invocateur.

```text
Cleared 1 test legend(s) and 7 test statue entit(y/ies)
```

Les vraies entrées de Maître de la Ligue et les vraies statues du même joueur sont laissées
tranquilles.

## `/cobblecap statue remove <playerName>`

Retire toutes les entités de statue appartenant au diorama de cette légende. Renvoie le
compte.

```text
Removed 7 statue entit(y/ies) for Steve
```

Un compte de `0` signifie que rien n'a correspondu — soit le nom est faux, soit le diorama
avait déjà disparu. Il n'y a pas de message d'erreur pour un diorama manquant.

## `/cobblecap statue remove_slot <playerName> <slot>`

Retire une figure. `slot` est un mot : `player`, `p`, ou `1`–`6`. `all` n'est **pas**
accepté ici — utilise `remove` pour cela.

```text
Removed 1 entit(y/ies)
```

## `/cobblecap statue move <playerName> <slot> <dx> <dy> <dz>`

```mcfunction
/cobblecap statue move <playerName> <slot> <dx> <dy> <dz>
```

| Argument | Type | Plage |
|---|---|---|
| `slot` | mot | `player`, `1`–`6`, `all` |
| `dx` `dy` `dz` | double | −100 à 100 chacun |

Un décalage **relatif** en blocs, pas une position absolue. `all` passe `null` comme
sélecteur, ce que `StatueManager` interprète comme « toutes les entités de ce diorama ».

```mcfunction
/cobblecap statue move Steve all 0 0.5 0
```

```text
Moved 3 entit(y/ies)
```

## `/cobblecap statue rotate <playerName> <slot> <yaw>`

`yaw` est un flottant de −360 à 360, et c'est un lacet **absolu**, pas un delta.

```text
Rotated 7 entit(y/ies)
```

## `/cobblecap statue scale <playerName> <slot> <factor>`

`factor` est un double de **0.1 à 5.0** — noter que la borne inférieure diffère de
l'argument `scale` des commandes `place*`, qui démarre à 0.2. Absolu, pas multiplicatif.

```text
Scaled 7 entit(y/ies)
```

## `/cobblecap statue style <playerName> <slot> <style>`

Restyle les figures existantes sur place, couleur de lueur et aura de particules ensemble.

```text
Restyled 7 entit(y/ies) -> netherite
```

## `/cobblecap statue list`

```text
─── Placed Dioramas (2) ───
  Steve — minecraft:overworld @ (128.5, 71.0, -340.5) [7 entit(y/ies)]
  Alex — minecraft:overworld @ (134.5, 71.0, -340.5) [7 entit(y/ies)]
```

`No diorama currently placed.` quand rien n'est en scène, et la commande renvoie `0`.

## `/cobblecap statue tp <playerName>`

Téléporte l'invocateur à l'ancre du diorama, un bloc plus haut, en conservant son lacet et
son tangage actuels. La correspondance ignore la casse par rapport aux noms de
`statue list`.

```text
TP'd to diorama of Steve
```

| Message | Cause |
|---|---|
| `Must be run by a player` | source non joueur |
| `No diorama for <nom>` | aucune entrée dans `statue list` sous ce nom |
| `World not loaded: <id>` | la dimension enregistrée du diorama ne se résout pas — le plus souvent une dimension CheeseGuard par joueur qui n'existe plus |

## `/cobblecap statue stats`

Compte les dioramas, le total d'entités et les entités par style. Le décompte par style est
construit en parcourant **toutes les entités de tous les mondes chargés** à la recherche de
l'étiquette maîtresse de CobbleCap, ce qui est un vrai parcours : évite-la sur un serveur
chargé en heure de pointe.

```text
─── Statue Stats ───
Total dioramas: 2 · total entities: 14
  · gold: 7
  · netherite: 7
```

## `/cobblecap statue styles`

Affiche l'énumération vivante.

```text
Available styles: natural stone gold emerald diamond amethyst copper netherite obsidian crystal glass frost soul
```

## `/cobblecap statue wipe_all`

```mcfunction
/cobblecap statue wipe_all
```

⚠️ Destructrice et sans retour en arrière. Un parcours basé sur les étiquettes qui retire
**toutes** les entités de statue CobbleCap de tous les mondes — y compris les supports
d'armure invisibles orphelins dont la doublure ne s'est jamais rattachée après un
redémarrage — puis vide la liste d'ancres du champion dynamique, de sorte que
`place_current` et `place_current_player` repartent de zéro.

```text
Wiped 23 statue entit(y/ies) and cleared all anchors
```

Le retour est diffusé aux opérateurs. Utilise-la quand l'état des statues est visiblement
corrompu ; utilise `remove` pour tout ce qui est ciblé.

---

## `/cobblecap chrono start <player>`

```mcfunction
/cobblecap chrono start <player>
```

Démarre le chronomètre du Conseil 4, avec une sémantique de **redémarrage forcé** : un
chrono déjà en cours est toujours écarté et remplacé. C'est volontaire, pour que câbler la
commande sur un déclencheur d'entrée de dimension fasse redémarrer la course à la rentrée au
lieu de poursuivre silencieusement une ancienne.

```text
Chrono started for Steve
```

ou, si un chrono tournait déjà :

```text
Chrono RESTARTED for Steve (was at 7m12s)
```

Le joueur lui-même reçoit `⏱ Le chronomètre du Conseil 4 est lancé. Bonne chance !`.

Deux événements annulent un chrono sans aucune commande, tous deux dans
`CobbleCapMod.onInitialize` :

- quitter une dimension `…:conseil4*` vers une dimension qui n'est **pas** liée au
  conseil4. Le joueur reçoit `⏱ Chrono Conseil 4 annulé — tu as quitté la dimension`. Cette
  chaîne est un `Text.literal` français dans le source, donc un client anglais voit du
  français ici.
- se déconnecter. Une course partielle est jetée pour que la tentative suivante reparte
  proprement.

## `/cobblecap chrono start_keep <player>`

L'ancienne sémantique : ne démarrer **que** si rien ne tourne.

```text
Chrono already running for Steve (elapsed: 7m12s)
```

Renvoie `1` quand elle en a démarré un et `0` quand elle a décliné. Utilise celle-ci plutôt
que `start` si tu veux qu'une rentrée en dimension préserve le chrono.

## `/cobblecap chrono stop <player>`

```mcfunction
/cobblecap chrono stop <player>
```

Arrête la pendule et lance la finalisation de Maître de la Ligue. C'est la commande qui peut
couronner quelqu'un, donc elle a une condition stricte.

**Si le joueur n'a encore aucune légende**, il n'est promu que si
`champion_regis_challenge` figure dans son `championsBeaten` ou son `challengeBeaten`.
Sinon :

```text
⚠ Le chrono a été arrêté mais tu n'as pas (encore) battu Régis Challenger. Aucun statut de Maître de la Ligue accordé.
```

La ligne de log est `[CobbleCap] Chrono stop for <nom> REFUSED — champion_regis_challenge
not beaten this run; bestTime ignored, no legend created`, les scoreboards Conseil 4 sont
reconstruits quand même pour qu'un admin voie que le chrono a été traité, et la commande
renvoie `0`. Ce message est aussi un `Text.literal` français.

⚠️ `champion_regis_challenge` est **codé en dur** dans
`StatueCommand.finalizeChrono`. Sur un serveur dont le champion final porte un autre
identifiant, `chrono stop` ne peut jamais promouvoir personne.

**Si le joueur a déjà une légende**, la condition ne s'applique pas et seul le meilleur
temps est touché :

- plus rapide que le `bestTimeMs` enregistré, ou aucun temps enregistré : l'entrée est
  remplacée par le nouveau temps et l'annonce complète part
- plus lent : l'ancien record est conservé et tout le monde voit
  `⏱ Steve a fini en 24m05s (record perso : 18m42s)` — encore un littéral français — et la
  commande renvoie `1` sans webhook ni morph

En cas de promotion ou de record réussi, dans l'ordre : l'annonce à tout le serveur
(`cobblecap.msg.league_master_chrono`, ou `…_first_chrono` pour le tout premier Maître), le
webhook Discord via `CobbleCapWebhook.sendLeagueMaster`, un morph de dresseur RCT **mis en
file** via `rctMorpher.request(player, elapsed)`, et un `Conseil4Boards.rebuild`.

```text
🏆 Steve a terminé le Conseil 4 en 18m42s et devient Maître de la Ligue Pokémon !
```

`No active chrono for <nom>` en erreur quand rien ne tournait.

## `/cobblecap chrono cancel <player>`

Jette la course. Aucune mise à jour de légende, aucune annonce, aucun webhook.

```text
Chrono cancelled for Steve
No active chrono for Steve
```

## `/cobblecap chrono wipe <player>`

⚠️ Annule tout chrono actif **et supprime toutes les entrées de légende de ce joueur, de
test comme réelles**. Le commentaire du code annonce son but : nettoyer après un
`chrono stop` qui a accidentellement couronné quelqu'un pendant les tests.

```text
Wiped Steve — cancelled chrono: yes, removed legend entries: 2
```

Diffusée aux opérateurs. Il n'y a pas de retour en arrière ; `cobblecap_legends.json` est
réécrit.

## `/cobblecap chrono list`

Les chronos actifs avec leur temps écoulé. Elle ne parcourt **que les joueurs en ligne**,
donc un chrono détenu par quelqu'un qui s'est déconnecté depuis n'est pas listé — même si en
pratique une déconnexion l'annule de toute façon.

```text
─── Active chronos (2) ───
  · Steve — 7m12s
  · Alex — 1m48s
```

`No active chronos` quand il n'y en a aucun, en renvoyant `0`.

## `/cobblecap chrono status [player]`

Sans argument, seulement le compte :

```text
2 active chrono(s)
```

Avec un joueur :

```text
Steve — 7m12s
No active chrono
```

---

## `/cobblecap champion morph_now <player>`

```mcfunction
/cobblecap champion morph_now <player>
```

Met en file un morph du dresseur RCT `champion_regis_challenge` vers ce joueur, avec un
temps enregistré de `0`. C'est une **requête**, pas une action immédiate :
`RctTrainerMorpher` l'applique à un tick ultérieur.

```text
Queued morph of champion_regis_challenge to Steve — applied when no battle is active
```

Diffusée aux opérateurs. Il n'y a aucun retour quand le morph finit par aboutir — surveille
le log serveur. Le mécanisme, et notamment pourquoi il attend, est décrit dans
[le morphing du champion](champion-morphing.fr.md).

## `/cobblecap champion bind_npc <uuid>`

```mcfunction
/cobblecap champion bind_npc <uuid>
```

Lie une entité NPC Cobblemon par UUID comme avatar du champion. C'est le **chemin de
repli**, utilisé quand la réécriture du datapack n'est pas viable.

```text
[NPC] <détail>
```

Vert quand `ChampionNpcManager.MorphResult.OK`, rouge sinon, et la chaîne de détail est ce
que rapporte le manager. `Invalid UUID` si le mot n'est pas un UUID.

## `/cobblecap champion bind_npc_closest`

Lie le NPC Cobblemon le plus proche de l'invocateur. Doit être lancée par un joueur.

```text
Must be run by a player
```

## `/cobblecap champion unbind_npc`

Vide la liaison dans `ChampionNpcState`. Réussit toujours.

```text
Champion NPC unbound
```

## `/cobblecap champion status`

```text
Champion NPC: 4f0c…-…-… in minecraft:overworld
Last morphed champion: Steve
```

`(none)` à l'une ou l'autre position quand ce n'est pas défini. C'est la vérification la plus
rapide pour savoir si le sous-système de morph a déjà fonctionné une fois.

## `/cobblecap champion refresh_dioramas`

Recrée chaque diorama `place_current` et `place_current_player` depuis le champion courant.
Normalement automatique lors d'un morph ; lance-la à la main quand un diorama est
visiblement périmé.

```text
Current-champion dioramas refreshed
```

## `/cobblecap champion backup_now`

Sauvegarde le JSON de dresseur Régis et sa skin PNG dans
`config/cobblecap_regis_backup/`. Sans effet si une sauvegarde est déjà là, donc elle
n'écrase jamais une bonne copie par une version morphée.

```text
Régis backup ensured at config/cobblecap_regis_backup/ — see log for details
```

Diffusée aux opérateurs. **Lance-la avant le premier morph sur un nouveau serveur.** Sans
sauvegarde il n'existe aucun retour vers le dresseur d'origine.

## `/cobblecap champion restore_original`

Restaure à la fois le JSON de dresseur et la skin PNG depuis cette sauvegarde, puis
déclenche un rechargement rctmod.

```text
Régis restore: <statut>
```

La chaîne de statut vient directement de `RctTrainerMorpher.restoreOriginal` et rapporte ce
qu'il a réellement réussi à faire, ce qui peut être partiel. Lis-la plutôt que de présumer
du succès.

---

← [Commandes](commands.fr.md) · [API dashboard](dashboard.fr.md) →
