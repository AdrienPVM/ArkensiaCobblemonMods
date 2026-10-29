[← CheeseFixQuests](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](getting-started.md)

# Premiers pas

À la fin de ce parcours, deux joueurs seront dans le même groupe FTB Teams, l'un d'eux aura
terminé une quête, et le carnet de l'autre affichera toujours cette quête comme inachevée.
C'est tout l'objet du mod, et c'est une chose que tu peux voir se produire en une dizaine de
minutes.

Il te faut un serveur Fabric 1.21.1 avec FTB Quests et FTB Teams, et deux comptes — l'effet
est invisible à un seul joueur, puisqu'un joueur seul est toujours seul sur son équipe. Une
seconde instance Minecraft ou un ami font aussi bien l'affaire.

---

## 1. Constater d'abord le comportement par défaut

Fais-le avant d'installer quoi que ce soit. Ça prend deux minutes et c'est ce qui rend la
suite convaincante.

Démarre le serveur sans CheeseFixQuests. Connecte les deux comptes et mets-les en groupe :

```mcfunction
/ftbteams party create TestParty
/ftbteams party invite <SecondJoueur>
```

puis sur le second compte :

```mcfunction
/ftbteams party join TestParty
```

Fais maintenant terminer au premier joueur n'importe quelle quête avec une tâche d'item —
ramasse l'item demandé. Ouvre le carnet de quêtes sur le **second** compte.

La quête y est terminée elle aussi, et sa récompense attend d'être réclamée. Personne sur le
second compte n'a rien fait. C'est FTB Quests qui fonctionne exactement comme prévu, et
c'est le comportement que tu vas changer.

Quitte le groupe sur les deux comptes (`/ftbteams party leave`) et arrête le serveur.

---

## 2. Sauvegarder les données de quêtes

```text
world/ftbquests/
config/ftbquests/
```

Copie les deux quelque part de sûr. Le mod exécute une migration unique qui réécrit la
progression des quêtes à la première connexion d'un joueur, et cette sauvegarde est le seul
chemin de retour. Sur un monde tout neuf il n'y a rien à perdre, mais prends l'habitude ici
plutôt que sur ton serveur en service.

---

## 3. Installer le jar

Dépose `cheesefixquests-1.0.1+1.21.1.jar` dans le `mods/` du serveur, à côté de FTB Quests
et FTB Teams. Copie le même jar dans le `mods/` des deux clients : le mixin côté client est
ce qui maintient en état de marche le mode édition du carnet pour les opérateurs, et la
[page d'installation](installation.fr.md#de-quel-côté) explique pourquoi.

Démarre le serveur.

---

## 4. Vérifier le journal

Dans les premières secondes du démarrage :

```text
[CheeseFixQuests] Individual quest progress mode ENABLED
[CheeseFixQuests] Migration pending — will run on first player login
[CheeseFixQuests] Boot complete — finalizer=true claim-lock=true audit=false
```

`ENABLED` est la ligne qui compte. Si elle dit `DISABLED`, arrête-toi là : quelque chose a
écrit `"forceIndividualProgress": false` dans `config/cheesefixquests.json` et la suite de ce
parcours ne fera rien. Voir
[configuration](configuration.fr.md#forceindividualprogress).

Si tu ne vois aucune ligne `[CheeseFixQuests]`, va voir
[le mod n'apparaît pas du tout dans le journal](troubleshooting.fr.md#le-mod-napparaît-pas-du-tout-dans-le-journal).

---

## 5. Se connecter, et regarder la migration s'exécuter

Connecte le premier compte. La migration se déclenche à l'intérieur de cette connexion,
avant que les données du carnet ne soient envoyées :

```text
[CheeseFixQuests] No new data to migrate
[CheeseFixQuests] Auto-migration complete: 0 player(s) migrated
```

Sur le monde de test que tu viens de faire, zéro est la bonne réponse — il n'y avait rien à
déplacer. Aucune ligne `Processing team` n'apparaît non plus : la récompense de l'étape 1 n'a
jamais été réclamée et le groupe n'existe plus, donc aucune équipe n'a de joueur à qui la
migration pourrait attribuer quoi que ce soit, et chacune est ignorée sans un mot. Sur un serveur qui a de l'historique tu verras de vrais compteurs, et il faut lire
[la migration](migration.fr.md) pour comprendre ce qu'elle a décidé de copier et ce qu'elle a
laissé derrière.

Le drapeau est maintenant posé, donc cela ne se reproduira pas :

```mcfunction
/cheesefixquests status
```

```text
§e[CheeseFixQuests] forceIndividual=true migrationDone=true debug=false
```

Cette commande exige le **niveau de permission 4**, et non le niveau 2 habituel des
opérateurs. Depuis la console du serveur elle marche toujours.

---

## 6. Reformer le groupe

Les deux comptes, comme à l'étape 1 :

```mcfunction
/ftbteams party create TestParty
/ftbteams party invite <SecondJoueur>
```

```mcfunction
/ftbteams party join TestParty
```

Rien ne change visiblement dans le carnet. En coulisses, si : rejoindre un groupe fait
normalement fusionner par FTB Quests la progression de l'arrivant dans celle du groupe, et
`TeamDataMixin` vient d'annuler cette fusion. Avec `debugLogging` activé tu verrais :

```text
[CheeseFixQuests] Blocked mergeData — individual progress mode active
```

---

## 7. Terminer une quête sur un seul compte

Choisis la tâche d'item la plus simple que tu as et fais-la satisfaire par le **premier**
joueur, en ramassant l'item ou en le fabriquant : l'écouteur d'inventaire de FTB,
redirigé par ce mod, écrit aussitôt cette progression dans l'enregistrement propre au joueur,
et la quête se termine avec sa récompense réclamable.

N'utilise pas un clic-maj depuis un coffre pour ce test. L'écouteur de FTB ne voit jamais ce
chemin, et le balayage de finalisation qui le rattraperait normalement travaille, pour un
joueur en groupe, sur l'enregistrement du groupe plutôt que sur le sien (voir
[les faiblesses connues](architecture.fr.md#faiblesses-connues-et-compromis-assumés)).

Le journal affichera déjà cette ligne pour chaque joueur, dans les dix secondes suivant sa
connexion — la phase A du balayage appelle l'écouteur pour chaque joueur connecté, donc la
première redirection a lieu sans qu'il touche à rien. Elle est imprimée une fois par joueur :

```text
[CheeseFixQuests] InventoryListener.detect: redirected to PlayerTeam for <PremierJoueur> (first redirect for this player; total redirects since startup: 1)
```

Cette ligne est la preuve que la progression des tâches d'item est écrite dans le stockage
propre au joueur et non dans celui du groupe.

---

## 8. Ouvrir le carnet sur l'autre compte

**La quête est toujours inachevée, barre de progression vide.** Aucune récompense en
attente.

C'est le résultat. Les deux comptes sont dans un même groupe, partagent protections,
chargement de chunks et tout ce à quoi tu te sers des groupes, et leurs carnets de quêtes
n'ont plus rien à voir l'un avec l'autre.

Fais terminer la même quête au second joueur pour confirmer que ça marche indépendamment
pour lui : sa barre se remplit, sa récompense apparaît, et le carnet du premier joueur n'en
est pas affecté.

---

## 9. Quitter le groupe, et vérifier que rien n'est perdu

```mcfunction
/ftbteams party leave
```

Les deux carnets restent exactement comme ils étaient. FTB fusionne normalement les
récompenses réclamées en sortie de groupe, et `TeamDataMixin` annule cela aussi : aucun des
deux joueurs n'hérite des réclamations de l'autre.

---

## Et ensuite

- Un joueur signale une quête bloquée barre pleine, ou une récompense donnée deux fois :
  [le balayage de finalisation](finalizer.fr.md) est le sous-système qui traite les deux, et
  `auditQuestEvents` est la façon d'obtenir des preuves.
- Un chapitre de quêtes à minuteur quotidien qui a cessé de se réinitialiser avant
  l'installation du mod a besoin de
  [`/cheesefixquests resetchapter`](commands.fr.md#cheesefixquests-resetchapter).
- Installation sur un serveur avec des années d'historique de groupes : lis
  [la migration](migration.fr.md) en entier avant la première connexion, pas après.
- Quelque chose se comporte autrement que décrit ci-dessus :
  [dépannage](troubleshooting.fr.md).

---

← [Installation](installation.fr.md) · [Commandes](commands.fr.md) →
