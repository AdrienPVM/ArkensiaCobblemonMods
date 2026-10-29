[← CheeseExtraCommands](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](getting-started.md)

# Premiers pas

À la fin de cette page, un NPC de ton serveur demandera à un joueur un Pokémon à cinq
IVs parfaits, refusera d'avancer tant que ce Pokémon n'existe pas, et remettra une
récompense dès qu'il existera. Toute la chaîne tient en trois objectifs de scoreboard,
deux fonctions de datapack et un appel à `/cheesecheck`.

La donneuse de quête s'appelle ici Mira, l'objectif qui porte sa progression est
`mira_progress`, et la récompense est une Master Ball. Renomme les trois librement ;
c'est la structure qui compte.

Tout ce qui suit n'utilise que des commandes et un datapack vanilla : tu peux donc
suivre cette page sur un serveur nu, avec ce mod et Cobblemon seulement.
L'[étape 8](#8-piloter-la-chaîne-depuis-un-vrai-npc) remplace le bloc de commande de
test par un véritable NPC.

---

## 1. Confirmer que le mod tourne

Démarre le serveur et cherche les trois lignes de démarrage :

```text
[CheeseExtra] Initializing CheeseExtraCommands...
[CheeseExtra] Commands + Arkensia island-tour tracker registered.
[CheeseExtra] /cheesecheck command registered (ev, iv, pokemon, ballvariety)
```

La troisième est celle qui compte : elle signifie que l'arbre de commandes existe. Si
elle manque, arrête-toi ici et lis
[installation](installation.fr.md#vérifier-quil-est-chargé).

## 2. Créer l'objectif de progression à la main

```mcfunction
/scoreboard objectives add mira_progress dummy "Mira"
```

Fais-le même si `/cheesecheck` créerait l'objectif pour toi. Le créer toi-même garantit
que si tu écris mal son nom dans une commande ultérieure, l'avertissement du mod ressorte
dans le log au lieu de se confondre avec une première écriture légitime. Voir [le piège
de la création automatique](commands.fr.md#le-piège-de-la-création-automatique).

Vérifie :

```mcfunction
/scoreboard objectives list
```

```text
There are 1 objective(s): [Mira]
```

## 3. Se donner un état de départ

La convention de ce système de quêtes : **les nombres impairs veulent dire « en
attente », les nombres pairs veulent dire « fait »**. Les états de Mira :

| Score | Signification |
|---|---|
| `0` | n'a jamais parlé à Mira |
| `1` | quête acceptée, le joueur élève |
| `2` | vérification réussie, récompense due |
| `3` | récompense remise, quête close |

Place-toi à l'état `1`, comme si tu avais déjà accepté la quête :

```mcfunction
/scoreboard players set @s mira_progress 1
```

```text
Set [Mira] for Steve to 1
```

## 4. Écrire les deux branches de dialogue

Crée un datapack dans `world/datapacks/arkensia/`, avec `pack.mcmeta` :

```json
{ "pack": { "pack_format": 48, "description": "Arkensia quests" } }
```

Puis `data/arkensia/function/mira/still_breeding.mcfunction` :

```mcfunction
tellraw @s {"text":"Mira: reviens me voir quand tu auras un Pokémon avec cinq IVs parfaits.","color":"gray"}
```

Et `data/arkensia/function/mira/reward.mcfunction` :

```mcfunction
tellraw @s {"text":"Mira: impressionnant. Tiens, tu l'as méritée.","color":"gold"}
give @s cobblemon:master_ball 1
scoreboard players set @s mira_progress 3
```

La fonction de récompense fait elle-même passer le score à `3`. Rien d'autre ne le fait,
et c'est ce qui empêche un joueur d'encaisser deux fois.

Charge le pack :

```mcfunction
/reload
```

```text
Reloading!
```

## 5. Écrire le sélecteur de branche

`data/arkensia/function/mira/talk.mcfunction` est ce que le NPC appellera. Il fait deux
choses : lancer la vérification quand le joueur est en attente, puis brancher sur le
score obtenu.

```mcfunction
execute if score @s mira_progress matches 1 run cheesecheck iv @s 5 31 mira_progress 2
execute if score @s mira_progress matches 1 run function arkensia:mira/still_breeding
execute if score @s mira_progress matches 2 run function arkensia:mira/reward
execute if score @s mira_progress matches 3 run tellraw @s {"text":"Mira: merci encore.","color":"gray"}
```

Lis l'ordre attentivement, car c'est là que se joue toute l'astuce.

La ligne 1 lance la vérification. Si l'équipe du joueur remplit le critère, la commande
écrit `2` dans `mira_progress` séance tenante. La ligne 2 teste à nouveau `1`, trouve
`2`, et saute la réplique « continue l'élevage ». La ligne 3 teste `2`, correspond, et
remet la récompense.

Si la vérification échoue, elle n'écrit rien. `mira_progress` vaut toujours `1`, la ligne
2 se déclenche, et le joueur reçoit le rappel. Une seule fonction, les deux issues, aucune
imbrication de conditions.

`@s` fonctionne ici parce que la fonction s'exécute avec le joueur pour exécutant. Le
bouton de dialogue de Mira doit être configuré ainsi ; voir
l'[étape 8](#8-piloter-la-chaîne-depuis-un-vrai-npc).

## 6. Observer l'échec

Vide ton équipe, ou remplis-la de Pokémon sans aucun IV parfait, puis lance :

```mcfunction
/function arkensia:mira/talk
```

Deux messages, dans cet ordre :

```text
Aucun Pokémon dans ton équipe n'a 5 IVs à 31+. Continue l'élevage !
Mira: reviens me voir quand tu auras un Pokémon avec cinq IVs parfaits.
```

Le premier vient de `/cheesecheck`, en rouge. Le second est ton dialogue. Vérifie que
rien n'a bougé :

```mcfunction
/scoreboard players get @s mira_progress
```

```text
Steve has 1 [Mira]
```

Ce `1` est toute la raison d'être de cette conception. Une vérification ratée ne laisse
aucune trace à annuler.

## 7. Observer la réussite

Mets un Pokémon qualifiant dans l'équipe. Sur un serveur de test, le plus rapide est la
commande de Cobblemon elle-même :

```mcfunction
/pokegive @s magikarp ivs=hp_iv:31,attack_iv:31,defence_iv:31,special_attack_iv:31,speed_iv:31
```

⚠️ `pokegive` et la syntaxe de ses arguments appartiennent à Cobblemon, pas à ce mod. La
forme ci-dessus correspond à Cobblemon 1.6.1 ; vérifie `/pokegive` en jeu si ta version
diffère.

Reparle ensuite à Mira :

```mcfunction
/function arkensia:mira/talk
```

```text
Vérification réussie ! Un de tes Pokémon a 5 IVs à 31+.
Mira: impressionnant. Tiens, tu l'as méritée.
```

Une Master Ball arrive dans ton inventaire, et le score a bougé deux fois en un seul
appel :

```mcfunction
/scoreboard players get @s mira_progress
```

```text
Steve has 3 [Mira]
```

`1` → `2` écrit par `/cheesecheck`, puis `2` → `3` écrit par la fonction de récompense.
Relance la fonction et tu n'obtiens plus que `Mira: merci encore.` La quête est close.

Le log du serveur garde la trace de l'écriture :

```text
[CheeseExtra] IVs values: HP=31 ATK=31 DEF=31 SPA=31 SPD=0 SPE=31
[CheeseExtra] Set mira_progress = 2 for Steve
```

Ces lignes `IVs values` sont écrites en niveau INFO pour chaque Pokémon à chaque
vérification. Sur un serveur fréquenté, c'est bruyant ; voir [les faiblesses
connues](architecture.fr.md#faiblesses-connues).

## 8. Piloter la chaîne depuis un vrai NPC

Remplace `/function arkensia:mira/talk` par ce que ton mod de NPC appelle sur un bouton
de dialogue. Deux exigences, valables pour n'importe quel mod de NPC :

- la commande doit s'exécuter au **niveau de permission 2 ou plus**, ce qu'un NPC agissant
  comme le serveur satisfait
- l'**exécutant doit être le joueur qui clique**, pour que `@s` se résolve sur lui

Si ton mod de NPC lance les commandes comme le serveur sans exécutant, `@s` n'a rien à
quoi se rattacher. Passe alors le joueur explicitement et renonce à `@s` :

```mcfunction
execute as <player> run function arkensia:mira/talk
```

La plupart des mods de dialogue substituent le pseudo du joueur qui clique dans une
variable, précisément pour cette raison. Sur Arkensia, cela passait par les boutons de
dialogue d'EasyNPC, qui exécutent leur commande avec le joueur cliqueur pour exécutant.
C'est une **observation sur EasyNPC**, pas une garantie offerte par ce mod : vérifie-le
avec ton propre mod de NPC avant de bâtir vingt quêtes par-dessus.

## 9. Ajouter un second critère

Les trois autres sous-commandes se glissent dans le même emplacement. Une étape qui
réclame une espèce précise :

```mcfunction
execute if score @s dante_progress matches 1 run cheesecheck pokemon @s gyarados dante_progress 2
```

Une étape qui réclame de la variété de Balls :

```mcfunction
execute if score @s suzy_progress matches 1 run cheesecheck ballvariety @s 6 suzy_progress 2
```

Une étape qui réclame une équipe entraînée en EVs, trois Pokémon à deux stats maximisées
chacun :

```mcfunction
execute if score @s brick_progress matches 1 run cheesecheck ev @s 252 brick_progress 2 2 3
```

Cette dernière est celle dont il faut revérifier l'ordre : `ev` prend d'abord le seuil,
puis `scoreboard`, puis `value`, et seulement ensuite `statCount` et `teamCount`. `iv`
prend d'abord le compte et le seuil en second. Tableaux complets dans
[commandes](commands.fr.md).

## Si quelque chose n'a pas marché

| Ce que tu as vu | Va voir |
|---|---|
| pas de troisième ligne au démarrage | [installation](installation.fr.md#vérifier-quil-est-chargé) |
| `[CheeseCheck] Cobblemon non disponible.` | [dépannage](troubleshooting.fr.md#chaque-vérification-annonce-cobblemon-non-disponible) |
| une vérification qui devrait passer renvoie `0` | [dépannage](troubleshooting.fr.md#une-vérification-qui-devrait-passer-renvoie-zéro) |
| le score a bougé mais le NPC n'a pas réagi | [dépannage](troubleshooting.fr.md#le-score-est-écrit-mais-la-quête-navance-pas) |
| aucun message dans le chat | [dépannage](troubleshooting.fr.md#rien-napparaît-dans-le-chat-quand-je-lance-une-vérification) |

---

← [Installation](installation.fr.md) · [Commandes](commands.fr.md) →
