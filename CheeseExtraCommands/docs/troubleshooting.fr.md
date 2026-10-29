[← CheeseExtraCommands](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](troubleshooting.md)

# Dépannage

Symptôme, cause, remède. Le mod échoue silencieusement par conception : l'essentiel de
cette page consiste donc à distinguer trois silences qui se ressemblent.

---

## Le mod ne se charge pas du tout

**Cause.** Une des dépendances obligatoires manque ou n'est pas à la bonne version. Les
exigences déclarées sont Fabric Loader ≥ 0.15.0, Fabric API et Minecraft `~1.21`. Une
seconde copie du jar dans `mods/` produit le même résultat : Fabric refuse de démarrer
quand deux fichiers déclarent l'identifiant de mod `cheeseextra`.

**Remède.** Lis le premier écran de `logs/latest.log`. Fabric nomme la dépendance fautive
sans détour, avec la version voulue et la version trouvée. Pour un doublon,
`ls mods/ | grep cheeseextra` doit renvoyer exactement une ligne.

Le mod n'a besoin d'aucun mod frère de ce dépôt : un CheeseDash ou un CheeseGuard absent
n'est jamais la cause ici.

## Fabric avertit qu'une icône manque

**Cause.** `fabric.mod.json` déclare `"icon": "assets/cheeseextra/icon.png"` et ce fichier
n'existe pas dans le jar. Il n'y a pas du tout de dossier `assets/` dans
`src/main/resources/`.

**Remède.** Purement cosmétique. Le mod se charge et fonctionne ; Mod Menu affiche une
icône de remplacement. Ajoute un PNG à
`src/main/resources/assets/cheeseextra/icon.png` et reconstruis, ou supprime la ligne
`icon` du `fabric.mod.json`.

## La commande /cheesecheck n'existe pas

**Cause.** Soit le mod ne s'est pas chargé, soit tu es sous le niveau de permission 2.
Brigadier masque les commandes dont le prédicat `requires` échoue : un joueur sans
privilège reçoit donc l'erreur vanilla *Unknown or incomplete command* plutôt qu'un
message de permission.

**Remède.** Cherche la troisième ligne de démarrage :

```text
[CheeseExtra] /cheesecheck command registered (ev, iv, pokemon, ballvariety)
```

Si elle est là, l'arbre existe et le problème est ton niveau de permission. `/op <pseudo>`,
ou lance la commande depuis la console ou un bloc de commande, qui portent respectivement
les niveaux 4 et 2. [Plus de détails sur qui peut
l'appeler](commands.fr.md#niveau-de-permission).

Si seules les deux premières lignes apparaissent, le serveur n'a jamais atteint
l'enregistrement des commandes. La cause est ailleurs dans la séquence de démarrage :
cherche une exception entre les deux.

## Chaque vérification annonce Cobblemon non disponible

**Cause.** `Class.forName("com.cobblemon.mod.common.Cobblemon")` a échoué : Cobblemon n'est
pas dans le classpath. C'est une dépendance `suggests`, donc Fabric ne se plaint jamais de
son absence ; le mod se charge très bien sans lui.

**Remède.** Installe Cobblemon pour Fabric 1.21.1. Le mod a été construit contre la
version 1.6.1. La vérification n'est qu'une sonde sur un nom de classe : si un futur
Cobblemon déplaçait ou renommait cette classe, la sonde échouerait même avec Cobblemon
installé, et le symptôme serait identique. Si Cobblemon est manifestement présent dans
`mods/` et chargé dans le log, c'est ce renommage qu'il faut soupçonner, et
`CobblemonReflect.isAvailable` est la seule ligne à changer.

## Une vérification qui devrait passer renvoie zéro

Trois causes distinctes produisent le même message rouge et le même `0`.

**Cause 1 : le Pokémon est dans une boîte.** `getPartyPokemon` ne lit que le store
d'équipe. Un Pokémon dans le PC est invisible pour chaque sous-commande.

**Remède.** Fais-le déplacer dans l'équipe par le joueur.

**Cause 2 : les critères ne sont pas ceux que tu crois.** `ev` n'a aucun sélecteur de
stat : `minEV 252 statCount 1` passe sur *n'importe quelle* stat maximisée, pas sur la
Vitesse. `iv` prend son compte avant son seuil, à l'inverse d'`ev`. Pour `pokemon`,
l'espèce doit s'écrire comme un identifiant Showdown, en minuscules et sans ponctuation,
et un identifiant inexistant échoue exactement comme un Pokémon absent. Relis [les
tableaux d'arguments](commands.fr.md#cheesecheck-ev).

**Remède.** Lance la vérification à la main avec `/execute store result` et le vrai
joueur, puis ajuste les nombres jusqu'à obtenir le comportement voulu :

```mcfunction
/scoreboard objectives add check_dbg dummy
/execute store result score Steve check_dbg run cheesecheck iv Steve 5 31 quest_iv 4
/scoreboard players get Steve check_dbg
```

**Cause 3 : le pont de réflexion a cassé.** Une mise à jour de Cobblemon a renommé
quelque chose que `CobblemonReflect` cherche. Les commandes dégradent vers « aucune
correspondance » au lieu de lever une exception : cela ressemble donc à un échec
légitime.

**Remède.** C'est le seul cas que le log tranche. Une vérification qui fonctionne affiche
la taille de l'équipe et les valeurs de stats :

```text
[CheeseExtra] Got party object: PlayerPartyStore
[CheeseExtra] Party for Steve: 6 pokemon found
[CheeseExtra] IVs values: HP=31 ATK=31 DEF=31 SPA=31 SPD=12 SPE=31
```

Un pont cassé affiche un avertissement à la place, et c'est l'avertissement qui dit où ça
a rompu :

| Ligne de log | Ce qui a échoué |
|---|---|
| `Could not get PokemonStoreManager from Cobblemon.INSTANCE` | la recherche du storage. La surface d'API de Cobblemon a changé |
| `Could not get party for player <name>` | le storage a été trouvé, l'accesseur d'équipe non |
| `Party for <name>: 0 pokemon found` alors que l'équipe n'est pas vide | l'objet équipe a été trouvé mais n'a pas pu être itéré |
| `Could not get EVs from pokemon object (class: ...)` | l'accesseur du store de stats a changé |
| `Stats enum has N constants, expected >= 6` | l'énumération `Stats` a bougé ou rétréci |

Chacune correspond à un scan de [la couche de
réflexion](architecture.fr.md#la-couche-de-réflexion). La corriger implique d'ajuster le
discriminant de ce scan, puis de reconstruire.

## Rien n'apparaît dans le chat quand je lance une vérification

**Cause.** Comportement attendu. Chaque message part vers le **joueur nommé dans la
commande**, pas vers l'appelant. Un administrateur qui vérifie quelqu'un d'autre, un bloc
de commande ou la console ne voient rien.

**Remède.** Cible-toi toi-même pour tester, ou lis le log du serveur, qui enregistre
chaque écriture réussie :

```text
[CheeseExtra] Set mira_progress = 2 for Steve
```

## Le score est écrit mais la quête n'avance pas

**Cause.** La vérification a écrit dans un autre objectif que celui que ton NPC lit. Comme
`setScore` crée un objectif absent au lieu de refuser, une simple faute de frappe produit
une commande qui marche, un message de réussite vert, et un objectif que personne ne lit.
L'argument `scoreboard` n'a aucune complétion pour attraper la faute.

**Remède.** Cherche dans le log :

```bash
grep "not found — creating it" logs/latest.log
```

Chaque ligne nomme un objectif qui n'existait pas quand une vérification y a écrit. Sur un
serveur dont tous les objectifs sont créés à l'avance, cette ligne ne devrait jamais
apparaître. Compare chaque nom à `/scoreboard objectives list`.

Seconde possibilité : l'objectif est bon mais la **valeur** n'est pas celle sur laquelle
le dialogue branche. `/cheesecheck` fixe le score, il ne l'incrémente pas : un dialogue
qui attend `progression + 1` ne verra rien. Compare l'argument `value` au nombre que
l'état de dialogue suivant teste.

## Un joueur a perdu toute sa progression de quête

**Cause.** Il a changé de pseudo Minecraft. Les scores sont écrits sur
`ScoreHolder.fromName(player.getName().getString())` : un changement de pseudo repart donc
d'un score vide sur chaque objectif que ce mod a écrit.

**Remède.** Restaure-le à la main :

```mcfunction
/scoreboard players set NouveauPseudo mira_progress 3
```

Il n'existe aucun chemin de migration.
[Pourquoi](architecture.fr.md#faiblesses-connues).

## Une étape de quête est impossible à valider

**Cause.** Le plus souvent, un identifiant d'espèce qui n'existe pas. `/cheesecheck
pokemon` ne valide rien : un identifiant inconnu ne correspond jamais, et le joueur
s'entend dire qu'il ne possède pas le Pokémon. Rien n'est écrit dans le log.

Ensuite, un `count` de `ballvariety` au-dessus de `6`. La plage accepte jusqu'à `20`, une
équipe tient six Pokémon : `7` et au-delà ne peuvent jamais passer.

**Remède.** Teste l'étape toi-même avec une équipe dont tu sais qu'elle qualifie. Si
`pokemon` échoue encore, l'identifiant est faux : vérifie l'orthographe Showdown
(`mrmime`, `nidoranf`, `tapukoko`) plutôt que le nom affiché.

## Le HUD du tour de l'île n'apparaît jamais

**Cause.** L'une de cinq, par ordre de probabilité.

1. Le client n'a pas le mod. Le HUD est dessiné côté client ; le serveur ne peut pas le
   forcer.
2. `jenny_intro` ne vaut pas exactement `1`. La condition est `>= 1 && < 2` : `0` et `2`
   masquent tous deux le panneau.
3. `spawn_tour` vaut `26` ou plus. Le verrou se ferme à `TOTAL`, et un compteur
   doublement incrémenté dépasse.
4. Les 26 objectifs `<key>_met` valent tous `1` : la liste restante est vide et le rendu
   sort immédiatement, même avec `show = true`.
5. Les objectifs n'existent pas. Le traqueur ne les crée jamais, et un objectif absent est
   lu comme `0`.

**Remède.** Inspecte l'état directement :

```mcfunction
/scoreboard players get Steve jenny_intro
/scoreboard players get Steve spawn_tour
```

Puis place le joueur dans l'état visible à la main :

```mcfunction
/scoreboard players set Steve jenny_intro 1
/scoreboard players set Steve spawn_tour 0
```

Le panneau doit apparaître dans la seconde, le traqueur évaluant tous les 20 ticks. Si
ce n'est pas le cas et que le client a bien le jar, active le niveau DEBUG sur le serveur
et cherche `Failed to send IslandTourPayload`. Modèle complet dans [le traqueur du tour de
l'île](island-tour.fr.md#la-machine-à-états-en-scoreboards).

## Le HUD affiche des noms qui se chevauchent

**Cause.** `COL_WIDTH` vaut un `70` pixels fixe et le rendu ne mesure pas les noms. Un nom
affiché plus large empiète sur la colonne suivante.

**Remède.** Raccourcis les noms affichés dans `IslandTourTracker.NPCS`, ou augmente
`COL_WIDTH` dans `IslandTourHud`, puis reconstruis. Les deux sont des constantes de
compilation ; [le tableau des
constantes](configuration.fr.md#les-constantes-compilées-dans-le-jar) les recense.

## Le log est noyé sous les lignes CheeseExtra

**Cause.** `CobblemonReflect` journalise en INFO, pas en DEBUG. Un seul `/cheesecheck iv`
sur une équipe pleine écrit deux lignes d'équipe, six lignes de stats et une ligne de
score.

**Remède.** Aucun interrupteur à l'exécution. Soit tu fais taire le logger dans ta
configuration log4j :

```xml
<Logger name="cheeseextra" level="WARN"/>
```

soit tu passes les appels `LOGGER.info` de `CobblemonReflect.getPartyPokemon` et
`getStatStore` en `LOGGER.debug`, puis tu reconstruis. La seconde est la bonne correction,
et le mod devrait être livré ainsi.

Attention : faire taire le logger masque aussi les diagnostics dont tu as besoin pour [un
pont cassé](#une-vérification-qui-devrait-passer-renvoie-zéro). `WARN` conserve les
avertissements et supprime le bruit, c'est le bon compromis.

## Le module n'apparaît pas dans le dashboard CheeseDash

**Cause.** Il n'apparaîtra jamais. Ce mod n'enregistre aucun entrypoint `cheesedash`,
aucun `DashboardModule` et aucune route REST. `fabric.mod.json` ne déclare que les
entrypoints `main` et `client`.

**Remède.** Rien à corriger. Il n'y a pas de page de dashboard pour ce mod, par
conception, et pas de `dashboard.md` dans cette documentation pour la même raison.

## Rédiger un rapport de bug utile

Ouvre une issue sur
[GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues) avec :

1. **Les trois lignes de démarrage**, ou celles d'entre elles qui sont apparues. Elles
   établissent que le mod s'est chargé et que l'arbre de commandes a été construit.
2. **La commande exacte**, copiée telle quelle, avec le vrai pseudo et le vrai nom
   d'objectif. L'ordre des arguments est de loin la faute la plus fréquente, et seule la
   ligne littérale la révèle.
3. **Toutes les lignes `[CheeseExtra]` de la tentative.** Extrais-les :

   ```bash
   grep "CheeseExtra" logs/latest.log | tail -40
   ```

   Les lignes `Got party object`, `Party for`, `values:` et `Set` montrent ensemble
   jusqu'où la vérification est allée.
4. **Le message vu par le joueur ciblé**, en français, copié tel quel. Il identifie quelle
   branche de quel handler s'est exécutée.
5. **Ta version de Cobblemon.** La couche de réflexion n'est épinglée à rien, mais elle a
   été écrite contre la 1.6.1, et chaque scan est une hypothèse sur les noms de classes de
   cette version.
6. **Si le client a le mod**, pour tout rapport concernant le HUD.

Une éventuelle stack trace vient d'un `LOGGER.error` de `CobblemonReflect` et nomme
l'appel réflexif qui a échoué. Inclus-la en entier.

---

← [Architecture](architecture.fr.md) · [CheeseExtraCommands](../README.fr.md) →
