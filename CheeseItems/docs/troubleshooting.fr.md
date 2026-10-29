[← CheeseItems](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](troubleshooting.md)

# Dépannage

## Le serveur ne démarre pas

**Cause.** Le plus souvent, CheeseDash manque. `cheesedash` est un `depends` strict, et le
loader s'arrête sur une erreur de dépendance le nommant avant qu'une ligne de code Minecraft
ne tourne. Deuxième cause la plus fréquente : deux copies de `cheeseitems-*.jar` dans
`mods/`, restes d'une mise à jour.

**Remède.** Mets le jar de CheeseDash dans `mods/`, construit depuis le même clone du dépôt.
Garde un seul jar CheeseItems. Les deux jars doivent être ceux sans `-sources`.

## <a id="la-commande-cheeseitem-nexiste-pas"></a>La commande `/cheeseitem` n'existe pas

**Cause.** Soit le mod n'est pas chargé, soit tu es sous le niveau de permission 2. Brigadier
masque les commandes que tu ne peux pas lancer, donc un joueur sans permission reçoit
exactement le même *Unknown or incomplete command* qu'un joueur sur un serveur sans le mod.

**Remède.** Cherche `[CheeseItems] Initialized successfully!` dans le log. Si la ligne est
là, le mod est chargé et le problème est la permission — `/op <name>`, ou lance la commande
depuis la console ou un bloc de commande. `/morpho` est le test de contrôle : elle n'exige
aucune permission, donc si `/morpho` marche et `/cheeseitem` non, c'est la permission.

## `/cheeseitem list` dit qu'il n'y a aucun item alors que le fichier en contient

**Cause.** La configuration n'a pas pu être analysée. Une seule virgule en trop, un
guillemet typographique collé depuis un document, ou un commentaire `//` suffit — le fichier
est lu avec l'analyseur strict de Gson.

**Remède.** Cherche la ligne juste au-dessus de la bannière de démarrage :

```text
[CheeseItems] Failed to load config: …
```

⚠️ Agis avant de toucher au dashboard. Le mod garde une liste d'items vide après un échec
d'analyse, et la prochaine écriture — n'importe quelle création, modification ou suppression
— enregistre cette liste vide **par-dessus ton fichier**. Copie `config/cheeseitems.json`
quelque part, corrige le JSON, puis `/cheeseitem reload`.

## `give` rapporte un succès mais le joueur ne reçoit rien

**Cause.** `baseItem` n'est pas un identifiant d'item enregistré. `Registries.ITEM.get`
renvoie `minecraft:air` pour un identifiant inconnu, le mod construit une pile d'air, et la
commande rapporte un succès puisque rien n'a levé d'exception.

**Remède.** Vérifie l'identifiant dans `/cheeseitem list` — l'item de base est affiché entre
parenthèses. Fautes à chercher : un espace de noms `minecraft:` manquant, un pluriel
(`minecraft:papers`), ou un item venant d'un mod qui n'est pas installé. Le mod ne valide
jamais ce champ.

Une seconde cause, plus rare : l'inventaire du joueur était plein, et la pile a été jetée à
ses pieds plutôt que perdue.

## Le clic droit sur un item personnalisé ne fait rien

Quatre causes, dans l'ordre à vérifier :

| Cause | Comment le voir |
|---|---|
| `enabled: false` | `/cheeseitem list` affiche `[OFF]` |
| L'item n'a ni `useActions` ni `onUseEffects` | rien n'est configuré pour se produire |
| Une condition a échoué | le joueur voit `Conditions non remplies.` dans la barre d'action |
| Un cooldown est en cours | le joueur voit `Cooldown : 7.3s` |

**Remède pour le cas des conditions.** Le message ne dit jamais *laquelle* a échoué, ce qui
est voulu pour un item de quête et ne t'aide pas. Passe-les en revue : le joueur doit être
dans `dimension`, au moins au **niveau d'expérience** `minLevel`, tenir `requiredHeldItem` en
**main secondaire**, et passer `permission`.

⚠️ C'est la vérification de permission qui surprend. Les opérateurs de niveau 2 passent sans
que le nœud soit lu. Tous les autres passent par une recherche LuckPerms par réflexion, et
**tout échec de cette recherche, y compris l'absence de LuckPerms, compte comme un refus**.
Sur un serveur sans LuckPerms, une condition `permission` rend l'item réservé aux opérateurs.
[Détails](items.fr.md#useconditions).

## L'item est utilisé en main secondaire et rien ne se passe

**Cause.** C'est voulu. `UseItemCallback` est filtré sur `Hand.MAIN_HAND` ; une utilisation
depuis la main secondaire passe sans être traitée.

**Remède.** Aucun — mets l'item en main principale. Note que les `passiveEffects`, eux,
fonctionnent depuis les deux mains.

## Une pile entière a disparu après une seule utilisation

**Cause.** `maxUses` avec une pile de plus d'un exemplaire. Quand le compteur atteint zéro,
le gestionnaire vide la pile entière, pas un seul exemplaire.

**Remède.** Distribue les items à `maxUses` avec `maxStackSize: 1` pour qu'ils ne puissent
pas s'empiler. [Pourquoi](items.fr.md#utilisations-et-cooldowns).

## Modifier un item n'a pas mis à jour les copies des joueurs

**Cause.** Cela dépend de ce que tu as changé. Le comportement — actions, conditions, effets,
options — est lu en direct et n'a besoin d'aucun rafraîchissement. L'apparence est inscrite
sur la pile et n'est mise à jour que par une passe de rafraîchissement, qui parcourt
**uniquement les inventaires des joueurs connectés**.

**Remède.** `/cheeseitem reload`, avec le joueur connecté. Les piles dans les coffres, les
coffres de l'Ender, les boîtes de Shulker, les cadres et au sol ne sont jamais rafraîchies.
Si tu as renommé l'`id` ou supprimé la définition, ces piles sont orphelines pour de bon ;
recréer la définition avec exactement le même `id` les ramène.
[Le tableau complet](items.fr.md#rechargement-à-chaud-et-items-déjà-dans-les-inventaires).

## Un item soulbound a disparu à la mort

**Cause.** C'est un bug du mod, pas une erreur de configuration. Les piles soulbound sont
retirées de l'inventaire avant la mort et mises de côté en mémoire, et la méthode qui les
restaure après la réapparition n'est jamais appelée.

**Remède.** N'utilise pas `soulbound: true`. Il n'y a aucune récupération possible pour un
item déjà perdu — la réserve n'existe qu'en mémoire et disparaît au prochain redémarrage.
Redonne l'item avec `/cheeseitem give`.
[Détails](items.fr.md#les-options-qui-ne-font-rien).

## <a id="les-icônes-personnalisées-napparaissent-pas"></a>Les icônes personnalisées n'apparaissent pas

Descends cette liste :

1. **`resourcePackHost` pointe-t-il vers une adresse que le client peut atteindre ?** Le
   défaut résout vers `localhost`, que chaque client distant résout vers lui-même. Le log
   écrit `resourcePackHost auto-resolved to localhost:44311` quand tu ne l'as pas réglé.
2. **Le joueur a-t-il accepté le pack ?** Il est envoyé avec `required = false`, donc un
   refus est silencieux.
3. **Le pack est-il non vide ?** `[CheeseItems] Resource pack cleared (no custom icons, no
   bar images)` signifie qu'il n'y a rien à envoyer.
4. **Peux-tu le télécharger toi-même ?**
   `curl -I http://<resourcePackHost>/cheeseitems/api/pack.zip` doit répondre `200`.
5. **Un autre resource pack écrase-t-il le modèle de l'item de base ?** Le pack généré
   remplace `assets/<ns>/models/item/<item de base>.json` intégralement, et un pack chargé
   au-dessus gagne. [Pourquoi](resource-pack.fr.md#comment-une-icône-est-choisie).
6. **L'icône a-t-elle été envoyée après la création de l'item ?** La route d'envoi répond
   `404` si la définition n'existe pas encore.

Un symptôme côté client qu'il vaut mieux connaître : si le SHA-1 du pack change alors qu'un
client détient une copie antérieure sous le même UUID, le client peut corrompre sa liste de
packs dans `options.txt`. Le mod envoie un paquet de retrait avant chaque envoi pour l'éviter
— si tu vois le problème, vérifie que ce retrait a bien lieu : il n'est écrit qu'au niveau
`DEBUG`.

## La barre d'endurance est une rangée de carrés

**Cause.** La police de la barre est dans le pack mais les glyphes n'atteignent pas le
client, en général parce qu'un autre pack livre un `assets/minecraft/font/default.json`
complet et se charge après.

**Remède.** Réordonne les packs côté client, ou retire les PNG de la barre de
`config/cheeseitems_assets/bar/` pour retomber sur la barre textuelle `◆`/`◇`. Ce repli est
choisi selon la présence des onze PNG **sur le serveur**, et non selon ce que le client
arrive à afficher. [Détails](resource-pack.fr.md#la-police-de-la-barre-dendurance).

Une illustration à moitié installée te donne la barre textuelle sans avertissement :
`hasBarImages()` exige les onze fichiers, de `0.png` à `10.png`.

## Appuyer sur H affiche *Aucune morpho active*

**Cause.** Le joueur possède des morphos mais aucune n'est marquée active, ce qui arrive
après que la morpho active a été supprimée du catalogue.

**Remède.** Ouvre le Codex avec `J` et clique une carte. La première morpho qu'un joueur
reçoit devient active automatiquement ; après une suppression, rien n'en choisit une autre.

## Appuyer sur H ne transforme rien

**Cause.** CobbleShift est absent, ou `cshift` a rejeté l'espèce. Le mod lance
`cshift transform <player> <species> [form] [shiny]` via le dispatcher de commandes et n'a
aucun moyen de vérifier le résultat, donc il annonce *Transformation : Ditto* dans les deux
cas.

**Remède.** Cherche :

```text
[CheeseItems] cshift command failed: cshift transform Steve ditto (…)
```

Puis lance la même commande à la main en tant qu'opérateur pour voir la vraie erreur. Les
causes habituelles sont un nom d'espèce que CobbleShift ne connaît pas, ou une valeur de
`form` invalide pour cette espèce.
[Pourquoi c'est fragile](morphos.fr.md#porter-une-morpho).

## Un joueur est coincé en transformation

**Cause.** L'une des deux. Soit le serveur a redémarré pendant qu'il était transformé — la
table des transformés n'existe qu'en mémoire, donc CheeseItems ne le sait plus et sa
prochaine pression sur `H` le transforme de nouveau au lieu de l'annuler. Soit il a appuyé
sur `H` avec une réserve d'endurance vide, ce qui exécute la transformation *puis* refuse de
démarrer le drain, sans rien pour l'annuler.

**Remède.** `/cshift revert <name>`. Les deux sont des bugs connus, listés dans
[architecture](architecture.fr.md#faiblesses-connues).

## Une morpho a disparu du Codex d'un joueur

**Cause.** Son entrée de catalogue a été supprimée. Chaque synchronisation du codex retire
les entrées dont l'identifiant de catalogue n'existe plus, et écrit une ligne par retrait :

```text
[CheeseItems] Pruned orphan morpho 'a3f91c2e' from Steve's codex (no catalog entry)
```

**Remède.** Il n'y en a aucun qui la rende automatiquement : une entrée recréée reçoit un
nouvel identifiant. Attribue la nouvelle entrée aux joueurs concernés, que tu trouveras dans
`config/cheeseitems_morpho.json` — cherche l'ancien identifiant pour obtenir la liste des
UUID.

## Une édition manuelle du catalogue de morphos a été écrasée

**Cause.** `MorphoCatalog.load()` ne tourne qu'une fois, au démarrage. Le serveur en marche
garde le catalogue en mémoire et réécrit tout le fichier à sa prochaine sauvegarde différée,
jetant ta modification. Il n'existe aucun rechargement pour lui, contrairement à la
configuration des items.

**Remède.** Édite le catalogue depuis le dashboard, ou arrête le serveur, édite, et
redémarre.

## Les modules du dashboard sont absents de la barre latérale

**Cause.** Soit le mod ne s'est pas chargé, soit CheeseDash a démarré avant lui. Les modules
sont découverts via l'entrypoint `cheesedash` au démarrage de CheeseDash ; rien ne les
enregistre plus tard.

**Remède.** Confirme que `[CheeseItems] Initialized successfully!` est dans le log, puis
redémarre le serveur pour que CheeseDash refasse sa découverte. Si le module apparaît mais
que chaque requête répond `503 {"error":"CheeseItems not ready"}`, la référence au serveur
est encore nulle — c'est normal pendant le démarrage et cela signifie que quelque chose
retarde `SERVER_STARTED`.

## Ma route ne peut pas exiger `SUPER_ADMIN`

**Cause.** L'application des rôles pour les routes de module se trouve dans le répartiteur
de CheeseDash, et il ne connaît que `requireWrite` : `OPERATOR` ou au-dessus pour toute
requête non-`GET`, n'importe quel rôle pour un `GET`. `OPERATOR` est donc la barre maximale
qu'une route de module atteint par défaut, quelle que soit la sensibilité de l'opération.

**Remède.** Appelle `WebDashboard.requireAdmin(exchange)` en tête de ce gestionnaire et
exploite sa valeur de retour, comme les gardes existants exploitent celle de
`requireWrite` :

```java
if (!WebDashboard.requireAdmin(exchange)) return;
```

Aucune route de CheeseItems ne le fait aujourd'hui. Note aussi qu'une route mutante exposée
en `GET` ne serait gardée par aucune des deux couches, puisque le contrôle central ne
regarde que la méthode HTTP.

Par ailleurs, sur ce module un `OPERATOR` équivaut à un accès console, par conception : les
`useActions` d'un item tournent en tant que console au niveau de permission 4, donc
quiconque peut écrire une définition d'item peut tout lancer. Accorde ce rôle en
conséquence. [Détails](dashboard.fr.md#authentification-et-gardes-de-rôle).

## Rédiger un rapport de bug utile

Signale sur [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues) avec :

1. **La version du mod** depuis `gradle.properties` (`mod_version`), et celle de CheeseDash à
   côté.
2. **Toutes les lignes `[CheeseItems]`** de `logs/latest.log`, du démarrage jusqu'à l'échec.
   Le bloc de démarrage à lui seul répond à la moitié des questions — il dit combien de
   définitions se sont chargées et si l'analyse a réussi.
3. **La définition ou l'entrée de catalogue concernée**, en JSON. `GET /api/v1/items/<id>` te
   la donne telle quelle, et c'est plus fiable qu'une description de ce que tu crois avoir
   réglé.
4. **Une stack trace** s'il y en a une. Cherche `[CheeseItems API]` dans le log pour les
   échecs côté HTTP, et `cheeseitems` pour les échecs de mixin et de loader.
5. **Pour un problème de resource pack**, la sortie de
   `curl -I http://<resourcePackHost>/cheeseitems/api/pack.zip` lancée depuis le réseau d'un
   joueur, pas depuis le serveur.
6. **Pour un problème de morpho**, si CobbleShift est installé et ce que fait
   `cshift transform` quand tu le lances à la main.

Les questions, plutôt que les bugs, vont sur le
[Discord Arkensia](https://discord.gg/fYmPwMBjdB).

---

← [Architecture](architecture.fr.md) · [CheeseItems](../README.fr.md) →
