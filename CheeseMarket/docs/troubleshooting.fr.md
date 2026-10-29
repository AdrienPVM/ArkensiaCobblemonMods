[← CheeseMarket](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](troubleshooting.md)

# Dépannage

## Le serveur refuse de démarrer et se plaint de `cheesedash`

**Cause.** CheeseDash est une dépendance dure déclarée dans `depends`. Fabric Loader ne
démarrera pas sans lui.

**Remède.** Mets `cheesedash-*.jar` dans le même dossier `mods/`. Si tu l'as construit
toi-même, construis CheeseDash **avant** CheeseMarket — la dépendance Gradle est un glob
sur `../CheeseDash/build/libs/cheesedash-*.jar`, et un glob vide te donne un jar dont les
imports `com.cheesedash` n'ont jamais été résolus.

---

## `/shop open` n'affiche rien et aucun panneau n'apparaît

**Cause.** Le mod manque côté client. Le serveur a envoyé un payload
`cheesemarket:shop_open` et le client n'a aucun receiver pour lui : il est donc jeté en
silence. Le panneau est un `Screen` côté client ; le serveur ne peut pas le dessiner.

**Remède.** Installe le même `cheesemarket-*.jar` dans le `mods/` du client, plus Fabric
API. Le client n'a pas besoin de CheeseDash. Cherche
`[CheeseMarket] Client initialized.` dans le log du client pour confirmer.

---

## Le panneau s'ouvre mais la grille est vide

`Aucun article.` à l'endroit où les cartes devraient être.

**Cause.** L'une de ces quatre, par ordre de probabilité :

1. La boutique n'a aucun set.
2. La rotation du set dit qu'il n'est pas actif maintenant.
3. Tous les items du set ont `enabled: false`.
4. Les items sont dans un **deuxième** set, et seul le premier set actif est atteignable.

**Remède.** Demande à l'API ce que le serveur en pense :

```bash
curl -H "Authorization: Bearer <token>" \
  http://localhost:44311/api/v1/market/shops/<shopId>
```

Chaque set porte un booléen `active` et un `rotationLabel`. Si `active` est faux sur un set
`SCHEDULE`, compare la fenêtre à l'horloge et au fuseau **de la machine serveur** — pas aux
tiens et pas à UTC. Si `active` est faux sur un set `MANUAL`, bascule `manualActive`.

Pour le cas 4 : c'est une vraie limitation, pas une erreur de configuration.
`ShopViewScreen` dessine le set à l'index 0 et rien dans l'interface ne change cet index :
mets donc tout ce qu'un joueur doit voir dans un seul set et laisse la rotation décider
lequel. Contexte dans [le modèle de boutique](shop-model.fr.md#boutique).

---

## Une carte affiche la mauvaise icône

**Cause.** `getEffectiveDisplayItem()` traite `minecraft:paper` comme « non défini » — pas
seulement une chaîne vide, le littéral papier aussi. Un item dont le `displayItem` est du
papier retombe sur l'icône de sa première sortie.

**Remède.** Mets `displayItem` à autre chose que du papier. Si tu veux réellement une
icône de papier pour un item qui produit autre chose, il n'y a aucun moyen de l'exprimer :
choisis un autre item qui y ressemble, ou fais du papier la première sortie.

---

## Une carte affiche un carré blanc vide

**Cause.** Une icône `cheeseitems:*` dont le PNG n'est pas arrivé. Le serveur le
journalise pendant la construction de la vue :

```text
[Market]   my_item → NO image found
```

**Remède.** Soit CheeseItems n'est pas installé — cherche
`[Market] CheeseItems detected` à la première ouverture de boutique — soit cette définition
d'item n'a aucune image téléversée dans CheeseItems. Donne-lui-en une, ou fais pointer le
`displayItem` de l'item de boutique vers un item Minecraft ordinaire et garde
l'identifiant CheeseItems uniquement dans `outputs`.

---

## Un joueur a été débité et n'a rien reçu

⚠️ C'est le mode de défaillance qui mérite une alerte sur les logs.

**Cause.** Le paiement est pris avant la livraison, et deux choses peuvent casser entre les
deux.

Cherche dans le log du serveur :

```text
[Market] Item not in registry, skipping delivery: mymod:thing for player Steve
[Market] CRITICAL: giveOutputs failed for player Steve buying a1b2c3d4 x1 — payment was already taken!
```

La première ligne signifie qu'une boutique vend un identifiant d'item qui n'existe pas sur
ce serveur, d'habitude après le retrait d'un mod. L'achat se termine « avec succès » : le
joueur est débité, reçoit `Achat réussi`, et la transaction est enregistrée. La seconde
signifie que la livraison a levé une exception ; le joueur reçoit au moins
`[Market] Erreur de livraison ! Contactez un admin pour un remboursement.` en chat.

**Remède.** Rembourse à la main — l'entrée de transaction dans
`cheesemarket_transactions.json` porte la liste `costs` à l'unité et la `quantity`, donc
multiplie et rends. Puis corrige la cause : vérifie chaque identifiant de sortie contre le
registre du serveur après avoir retiré un mod. Un balayage rapide :

```bash
grep -o '"itemId":"[^"]*"' <world>/data/cheesemarket/shops/*.json | sort -u
```

Tout espace de noms de cette liste dont le mod n'est plus installé est un piège qui attend
un client.

---

## Des items ont fini au sol au lieu de l'inventaire

**Cause.** Le contrôle de place simule les 36 emplacements d'inventaire principaux
seulement, et traite les sorties `cheeseitems:*` comme empilant par 64. Quand la réalité
n'est pas d'accord, `insertStack` échoue et le surplus est lâché aux pieds du joueur plutôt
que supprimé.

**Remède.** Rien à corriger côté serveur — les items ne sont pas perdus. Dis aux joueurs de
faire de la place avant d'acheter en gros. La clé de langue qui les aurait avertis
(`cheesemarket.purchase.items_dropped`) existe et n'est pas câblée ; la ressusciter est un
correctif de trois lignes dans `giveOutputs`.

---

## Un stock global de 1 a vendu 64 unités

**Cause.** `canPurchase` ne prend aucun argument de quantité. Il vérifie `remaining > 0`,
passe, puis `recordPurchase` borne le compteur à zéro. C'est pareil pour les quotas par
joueur.

**Remède.** Considère le stock comme un plafond souple. Si un item doit être strictement
limité, fixe-lui un prix qui rend 64 unités inabordables, ou combine le quota par joueur
*et* un coût qui s'épuise. Le raisonnement est dans
[les achats](purchases.fr.md#la-quantité-ne-fait-pas-partie-du-contrôle-de-stock).

---

## Un joueur ne peut pas payer un item alors qu'il a visiblement l'argent

**Cause.** La monnaie du coût n'est pas disponible. Chaque monnaie a sa forme de
défaillance :

| Identifiant de coût | Si son mod manque |
|---|---|
| `cheesestore:fromage` | le solde lit 0, donc rien n'est jamais abordable |
| `numismatic-overhaul:*_coin` | les items de pièces n'existent pas, donc le compte d'inventaire vaut 0 |
| tout `cheeseitems:*` | reconnu par un tag `custom_data`, donc un item ressemblant sans le tag ne compte pas |

**Remède.** Vérifie les verdicts des hooks dans le log, journalisés une fois au premier
usage :

```text
[Market] CheeseStore not found — fromage support disabled
[Market] Numismatic Overhaul not found — purse support disabled
```

Si le mod *est* installé et que le hook dit quand même qu'il n'a rien trouvé, lis l'entrée
suivante.

---

## Une intégration a cessé de fonctionner après une mise à jour de CheeseStore, CheeseItems ou Numismatic Overhaul

**Cause.** Les trois intégrations résolvent classes et méthodes par leur nom, par
réflexion. Un renommage de l'autre côté éteint la fonctionnalité avec un unique
avertissement et plus aucune plainte.

```text
[Market] Failed to initialize CheeseStore hook: <message>
[Market] Failed to initialize Numismatic Overhaul hook: <message>
[Market] Failed to initialize CheeseItems hook: <message>
[Market] Morpho catalog reflection unavailable: <message>
```

**Remède.** Le message nomme la classe ou la méthode qui a bougé. Mets à jour la classe
`*Hook` correspondante dans `com.cheesemarket.server`. Les signatures attendues sont
listées dans [l'architecture](architecture.fr.md#dépendances-optionnelles).

---

## Les images de fond téléversées n'apparaissent jamais en jeu

**Cause.** Presque toujours le décalage de `level-name`.
`AssetManager.init(worldDataDir)` n'est jamais appelé, donc la classe retombe sur
`<dossier config>/../world/data` tandis que `ShopManager` utilise le vrai chemin de
sauvegarde. Avec un `level-name` autre que `world`, les images sont écrites et lues sous un
dossier `world/` qui ne contient rien d'autre.

**Remède.** Mets `level-name=world`, ou déplace le dossier `assets/` là où
`AssetManager` regarde, ou modifie `CheeseMarket.onInitialize` pour appeler
`AssetManager.init` avec le même chemin que celui passé à `ShopManager.init`. Détails dans
[l'éditeur de mise en page](layout-editor.fr.md#les-images-téléversées).

Une seconde cause, plus bénigne : `GET /market/assets/:shopId/:name` est servi avec
`Cache-Control: public, max-age=3600`, donc une image remplacée peut sembler inchangée dans
le dashboard pendant une heure. C'est un cache de navigateur, pas le jeu ; le jeu reçoit
les octets en ligne et n'est jamais périmé.

---

## `reset-stock` dit que ça a marché et rien n'a changé

**Cause.** `POST …/items/:itemId/reset-stock` répond `{"reset":true}` et n'a aucune
implémentation derrière.

**Remède.** Arrête le serveur, édite
`<world>/data/cheesemarket/cheesemarket_stock.json`, redémarre. Ou édite-le puis lance
`/shop reload` avant que quiconque n'achète quoi que ce soit — l'achat suivant réécrit le
fichier depuis la mémoire et ton édition serait perdue. La forme du fichier est dans
[la configuration](configuration.fr.md#le-fichier-de-stock).

---

## Une édition d'un fichier JSON de boutique n'a eu aucun effet

**Cause.** Les boutiques sont lues au `SERVER_STARTED` et sur `/shop reload`, et réécrites
depuis la mémoire à chaque mutation. Une édition à chaud est écrasée.

**Remède.** `/shop reload` — niveau de permission 4. Si le fichier ne prend toujours pas,
c'est qu'il a échoué à l'analyse et que la boutique a été retirée de la table ; la sortie
d'erreur standard porte

```text
[CheeseMarket] Failed to load shop: .../starter.json → <message>
```

Valide le JSON. Note que l'exemple annoté de
[la configuration](configuration.fr.md#un-fichier-de-boutique-annoté) contient des
commentaires destinés à la lecture, et que Gson les refuse.

---

## Une commande est refusée

| Ce que tu vois | Cause |
|---|---|
| *Unknown or incomplete command* vanilla | ton niveau de permission est sous 2 ; tout l'arbre `/cmadmin` et `/shop` exige le niveau 2 |
| la même chose, sur `reload` ou `delete` seulement | ces deux sous-commandes exigent le niveau **4** |
| `Joueur requis` | tu as lancé `/cmadmin` ou `/shop open` depuis la console ou un bloc de commande ; les deux exigent une source joueur |
| `Shop introuvable : <id>` | mauvais identifiant — il n'y a aucune complétion sur `shopId`, donc vérifie avec `/shop list` |

---

## Le module `market` manque dans le dashboard

**Cause.** CheeseDash découvre les modules via l'entrypoint `cheesedash` au démarrage. Si
CheeseMarket a échoué à se charger, s'est chargé après que CheeseDash a abandonné, ou a été
construit contre un CheeseDash trop ancien pour avoir `DashboardModule`, le module est
absent.

**Remède.** Vérifie que les deux jars se sont chargés, puis appelle l'API directement :

```bash
curl -H "Authorization: Bearer <token>" http://localhost:44311/api/v1/modules
```

`market` doit figurer dans la liste avec le nom d'affichage `CheeseMarket`. Un `404` sur
`/api/v1/market/stats` avec `market` absent de `/modules` signifie que l'entrypoint n'a
jamais tourné — cherche une exception CheeseMarket pendant le démarrage. Un `503`
`{"error":"Server not available"}` signifie au contraire que le module s'est chargé mais
que `onServerStart` n'a pas encore été déclenché ; attends la fin du chargement du monde.

---

## Un compte `READ_ONLY` du dashboard voit les noms de joueurs et les prix

**Cause.** Ce n'est pas un bug, mais c'est bon à savoir : le dispatch de modules de
CheeseDash autorise toute session authentifiée à faire un `GET` sur une route de module, et
réserve `OPERATOR` à tout le reste. Chaque `GET` de market — définitions de boutiques,
prix, images téléversées, journal complet des transactions avec noms et UUID — est donc
lisible par `READ_ONLY`.

**Remède.** Rien dans ce mod. Ne donne pas `READ_ONLY` à quelqu'un qui ne doit pas voir
les données de ventes. Les rôles par route sont dans
[la page dashboard](dashboard.fr.md#rôles-requis).

---

## Un joueur a acheté quelque chose sans pouvoir ouvrir la boutique

**Cause.** ⚠️ Le receiver `cheesemarket:shop_action` n'a aucun contrôle de permission et
aucun contrôle de distance. Un client modifié peut envoyer
`shop_buy:<shopId>:<setId>:<itemId>:<qty>` pour n'importe quelle boutique dont il connaît
les identifiants, et ces identifiants sont visibles dans le payload de toute boutique qu'on
lui a un jour montrée. Le même canal accepte aussi les actions d'administration : un paquet
forgé peut donc créer, modifier ou supprimer des boutiques.

**Remède.** Il n'existe aucun interrupteur de configuration pour cela. L'atténuation
disponible aujourd'hui consiste à ne pas traiter les identifiants de boutiques comme des
secrets et à surveiller le journal des transactions pour y repérer des ventes depuis des
boutiques qu'un joueur n'aurait pas dû atteindre. Le changement de code — un garde
`hasPermissionLevel(2)` sur chaque action d'administration et un relevé par joueur de la
dernière boutique ouverte — est décrit dans
[l'architecture](architecture.fr.md#le-canal-dactions-nest-pas-authentifié).

---

## Un client en anglais voit du texte français dans la boutique

**Cause.** Plusieurs chaînes sont du français codé en dur en Java plutôt que des clés de
langue : les boutons de confirmation `Confirmer` et `Fermer`, `Aucun article.`, les
libellés de stock (`Illimité`, `Quotidien`, `Par joueur`…), le message d'échec de livraison
et les messages morpho. Les fichiers de langue eux-mêmes sont complets pour les clés
*réellement* utilisées.

**Remède.** Non configurable. Les clés sont listées dans
[l'architecture](architecture.fr.md#clés-de-langue-inutilisées) si tu veux les déplacer
vers les fichiers de langue.

---

## Signaler un bug

Ouvre un ticket sur
[GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues) avec :

1. **De quel côté est le problème.** Le panneau a-t-il refusé de s'ouvrir (client), ou un
   achat a-t-il mal tourné (serveur) ?
2. **Le log du serveur**, `logs/latest.log`, filtré sur le mod :
   ```bash
   grep -E "CheeseMarket|\[Market\]" logs/latest.log
   ```
   Inclus le bloc de démarrage et tout ce qui entoure le moment de la panne. Les lignes
   `[Market] CRITICAL` et `skipping delivery` sont les importantes pour un problème
   d'achat.
3. **Le log du client** pour un problème d'interface, `.minecraft/logs/latest.log`, filtré
   de la même façon.
4. **La définition de la boutique** — `<world>/data/cheesemarket/shops/<shopId>.json`. Il
   est petit, lisible, et contient d'habitude la réponse.
5. **L'entrée de transaction**, si un achat est en cause : l'objet correspondant de
   `cheesemarket_transactions.json`, qui porte les coûts et les sorties exacts.
6. **Quels mods facultatifs sont installés** — CheeseStore, CheeseItems, Numismatic
   Overhaul — et leurs versions, plus les trois lignes de verdict des hooks dans le log.

Les questions, plutôt que les bugs, vont sur le
[Discord Arkensia](https://discord.gg/fYmPwMBjdB).

---

← [Architecture](architecture.fr.md) · [CheeseMarket](../README.fr.md) →
