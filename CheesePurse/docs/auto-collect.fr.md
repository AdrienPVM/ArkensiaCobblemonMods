[← CheesePurse](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](auto-collect.md)

# Dépôt automatique

Tout le mod tient dans une boucle et un ensemble. Cette page décrit ce que la boucle
fait réellement, ce qui est à la fois plus étroit et plus large que son nom le laisse
croire : plus étroit parce qu'elle n'est pas branchée au ramassage d'objets, plus large
parce qu'elle ne se limite pas à ce qui vient d'être ramassé.

---

## Le modèle

Trois éléments d'état, et il vaut mieux les distinguer parce que deux d'entre eux
peuvent se contredire.

| État | Vit où | Persisté ? |
|---|---|---|
| `AUTO_COLLECT_PLAYERS`, un `Set<UUID>` | mémoire serveur, `NumismaticCheesePurse` | non |
| `autoCollectEnabled`, un `boolean` | mémoire client, `NumismaticCheesePurseClient` | non |
| Le solde de la bourse | le `CurrencyComponent` de Numismatic | oui, par Cardinal Components |

Le booléen client décide de la texture affichée par le bouton. L'ensemble serveur décide
si les pièces bougent vraiment. Ils ne sont synchronisés que dans un sens — du client
vers le serveur, au clic. Rien n'informe jamais un client de ce que croit le serveur.
Quand ils divergent, le joueur voit une chose et en obtient une autre ; c'est le
comportement déroutant le plus fréquent de ce mod, et il a
[sa propre entrée de dépannage](troubleshooting.fr.md#le-bouton-est-gris-mais-mes-pièces-disparaissent-quand-même).

## La boucle

`NumismaticCheesePurse.onInitialize` enregistre un gestionnaire sur
`ServerTickEvents.END_SERVER_TICK`. Le gestionnaire incrémente un compteur et sort tout
de suite tant que le compteur n'a pas atteint `SCAN_INTERVAL_TICKS`, qui vaut `10` :

```java
private static final int SCAN_INTERVAL_TICKS = 10;
```

Dix ticks, c'est une demi-seconde à 20 TPS en bonne santé. Sur un serveur qui rame,
c'est plus long en temps réel, parce que la boucle compte des ticks et non des
millisecondes. Sur un serveur à 10 TPS, le balayage a lieu une fois par seconde.

Quand le compteur se déclenche, il se remet à zéro et parcourt
`server.getPlayerManager().getPlayerList()` — tous les joueurs connectés, en une passe.
Les joueurs absents de `AUTO_COLLECT_PLAYERS` sont écartés par un `Set.contains` sur
leur UUID. Pour les autres, `collectCoinsForPlayer` s'exécute.

Il y a un seul compteur pour tout le serveur, pas un par joueur. Tous les joueurs
inscrits sont balayés au même tick.

### Ce que fait `collectCoinsForPlayer`

Deux appels, tous deux vers Numismatic Overhaul :

```java
long totalValue = CurrencyHelper.getMoneyInInventory(player, true);
if (totalValue <= 0) return;
ModComponents.CURRENCY.get(player).modify(totalValue);
```

Le commentaire du code affirme que cela reproduit le bouton « tout déposer » de
Numismatic, et c'est exact : `RequestPurseActionC2SPacket` traite son action `STORE_ALL`
avec les deux mêmes appels dans le même ordre. CheesePurse, c'est ce bouton sur une
minuterie.

Le retour anticipé sur `totalValue <= 0` est une petite économie plutôt qu'une protection
visible. Sans lui, chaque joueur inscrit sans pièces recevrait un appel `modify(0)` deux
fois par seconde ; `modify(0)` réécrit tout de même la valeur du composant, mais n'envoie
aucun message — Numismatic s'arrête avant la notification quand le delta ne correspond à
aucune pièce. Le garde-fou épargne cette écriture, pas l'écran du joueur.

---

## Réponses aux cinq questions que tout le monde pose

### Le dépôt est-il immédiat ou différé ?

**Différé, de dix ticks serveur au maximum.** Il n'y a aucun hook de ramassage dans ce
mod. Rien n'observe `ItemEntity`, rien ne se greffe sur `PlayerEntity.sendPickup`, et le
mot « ramassage » n'apparaît que dans le texte de l'infobulle. La boucle est une
minuterie qui scrute les inventaires : une pièce reste donc dans l'inventaire du joueur
entre un et dix ticks avant de bouger, selon l'endroit du cycle où elle est tombée.

Un joueur qui garde son inventaire ouvert peut le voir. Avec l'œil assez vif, ou un
enregistrement d'écran, la pile est visiblement là, puis plus là.

### Que se passe-t-il quand l'inventaire est plein ?

**Le mod ne voit jamais ces pièces, parce qu'elles n'atteignent jamais l'inventaire.**

Le ramassage est vanilla. Un joueur dont les 36 emplacements principaux sont pleins sans
possibilité de fusion ne peut pas ramasser une pile de pièces du tout — elle reste au
sol et disparaît selon le calendrier vanilla. CheesePurse ne lit que ce qui est déjà
dans l'inventaire : une pièce qui n'a pas pu être ramassée ne fait pas partie du
problème qu'il résout.

Il faut le dire franchement, parce que le nom de la fonctionnalité invite à supposer
l'inverse. CheesePurse ne **sauve pas** les pièces d'un inventaire plein. Il rend tout
au plus l'inventaire plein moins probable, en libérant les emplacements que les pièces
occuperaient — mais seulement après qu'elles sont entrées.

Le sens du dépôt, lui, n'échoue jamais pour une question de place. Une bourse est un
`long` dans un composant, pas un conteneur : il n'y a pas de capacité à saturer. Le
plafond théorique est `Long.MAX_VALUE` unités de bronze, et rien dans l'un ou l'autre
mod ne protège d'un dépassement ; l'atteindre demande environ 9,2 × 10¹⁸ bronze,
qu'aucune économie légitime ne produit.

### Toutes les dénominations sont-elles concernées ?

**Oui, et plus que tu ne l'attends peut-être.**

`getMoneyInInventory` teste chaque pile avec `stack.getItem() instanceof CurrencyItem`.
Dans Numismatic Overhaul 0.3.4, les classes qui implémentent `CurrencyItem` sont
`CoinItem` et `MoneyBagItem`, et les instances enregistrées sont :

| Item | Classe | Valeur |
|---|---|---|
| `numismatic-overhaul:bronze_coin` | `CoinItem` | 1 unité de bronze pièce |
| `numismatic-overhaul:silver_coin` | `CoinItem` | 100 unités de bronze pièce |
| `numismatic-overhaul:gold_coin` | `CoinItem` | 10 000 unités de bronze pièce |
| `numismatic-overhaul:money_bag` | `MoneyBagItem` | ce que contient son composant |

Donc les trois pièces, et les bourses d'argent aussi. Une bourse d'argent qu'un joueur
transporte comme réserve de valeur compacte se dissout dans sa bourse personnelle au
balayage suivant, comme le reste. Si ton serveur utilise les bourses d'argent comme un
item à part entière — une récompense de quête destinée à être remise à quelqu'un, par
exemple — préviens les joueurs de désactiver l'interrupteur avant d'en porter une.

Le scan couvre `player.getInventory().size()`, c'est-à-dire en 1.21.1 les 36
emplacements principaux, les 4 emplacements d'armure et la main secondaire. Une pièce en
main secondaire est prise. Les coffres de l'Ender, les boîtes de Shulker et tout autre
conteneur ne sont pas scrutés : les pièces qui y sont rangées ne risquent rien.

⚠️ La liste ci-dessus est lue dans le code source de Numismatic Overhaul 0.3.4, pas dans
une spécification publiée. Un autre mod qui implémenterait `CurrencyItem` sur son propre
item serait balayé lui aussi, et une version future de Numismatic pourrait ajouter ou
modifier des items. Prends-la pour l'observation d'une version donnée.

### Est-ce par joueur ou global ?

**Par joueur, indexé par UUID, et réglé par le joueur.**

Il existe exactement une entrée dans `AUTO_COLLECT_PLAYERS` : un
`ToggleAutoCollectPayload` venu d'un client, dont le gestionnaire lit `context.player()`
et utilise l'UUID de ce joueur-là. Un joueur ne peut basculer que lui-même. Pas de
commande, pas de vérification de permission, pas d'API, pas de chemin administrateur —
le serveur ne peut ni activer ni désactiver la fonctionnalité pour quelqu'un d'autre, et
il ne peut pas l'activer pour tout le monde.

L'ensemble n'est consulté que par la boucle, et il n'est jamais écrit ailleurs que dans
le gestionnaire de paquet.

### Comment désactiver ?

Quatre moyens, par ordre décroissant d'envie que tu en auras :

1. **Recliquer sur le bouton.** Le client inverse son booléen et envoie
   `ToggleAutoCollectPayload(false)` ; le serveur retire l'UUID.
2. **Redémarrer le serveur.** `AUTO_COLLECT_PLAYERS` est un initialiseur de champ sur un
   `HashSet` statique, reconstruit vide à chaque démarrage. Tous les joueurs sont
   désactivés après un redémarrage, quoi que dessine leur client.
3. **Retirer le jar** du serveur. Rien n'est persisté, il n'y a donc rien à nettoyer.
4. Il n'y a pas de quatrième moyen. Pas de clé de configuration, pas de commande, pas de
   gamerule, pas d'interrupteur dans un dashboard.

Attention : **se déconnecter ne désactive rien**. Le gestionnaire ne retire jamais un
UUID à la déconnexion. Un joueur qui active, quitte et revient est toujours actif côté
serveur, alors que son client, s'il a redémarré entre-temps, affiche le gris. C'est
cette asymétrie qui produit la désynchronisation décrite plus bas.

---

## Le piège du retrait de bourse

La boucle ne sait pas d'où vient une pièce. Elle lit l'inventaire, et tout ce qui s'y
trouve et qui est un `CurrencyItem` part dans la bourse.

Cela inclut les pièces que le joueur vient d'en **sortir**. L'écran de bourse de
Numismatic possède des contrôles de retrait ; s'en servir avec le dépôt automatique
activé donne ceci :

```text
le joueur clique « retirer 500 »
 → Numismatic crédite 5 pièces d'argent à l'inventaire, débite 500 de la bourse
 → jusqu'à 0,5 s s'écoule
 → CheesePurse balaie les 5 pièces d'argent, recrédite 500 à la bourse
 → le joueur voit  - [5 Silver]  puis  + [5 Silver]  dans sa barre d'action
```

Effet net : rien, deux fois. L'argent n'est pas perdu et rien n'est dupliqué — les deux
opérations conservent la valeur — mais un joueur qui cherche à obtenir des pièces
physiques pour les remettre à un autre joueur, ou pour les mettre dans un coffre, n'y
arrivera pas sans désactiver l'interrupteur d'abord.

Il en va de même pour toute autre source de pièces : un coffre, un échange avec un
villageois, un `/give`, un butin, un autre mod qui paie en monnaie Numismatic. Le
balayage les traite toutes de la même façon.

Ce n'est pas un bug au sens d'une erreur de code ; c'est la conséquence directe du choix
de scruter les inventaires sur une minuterie au lieu de se brancher sur le ramassage. Un
hook de ramassage serait le remède, et c'est la première chose à changer si ce
comportement est inacceptable sur ton serveur. Voir
[les points d'extension](architecture.fr.md#points-dextension).

## Ce que voit le joueur, et qui en décide

CheesePurse n'envoie lui-même aucun message au joueur. Chaque notification vient du
`CurrencyComponent.modify` de Numismatic, qui met en forme la variation sous forme de
liste de noms de pièces et respecte l'option `moneyMessageLocation` **du client**,
synchronisée vers le serveur par owo-lib :

| `moneyMessageLocation` | Résultat de chaque balayage |
|---|---|
| `ACTIONBAR` (défaut) | `+ [3 Silver, 12 Bronze]` au-dessus de la barre d'action rapide |
| `CHAT` | `numismatic > + [3 Silver, 12 Bronze]` dans le chat |
| `DISABLED` | rien |

Un joueur que la notification agace la règle sur `DISABLED` dans les options de mod de
Numismatic ; les commentaires de sa configuration précisent que ce changement demande de
se reconnecter pour prendre effet. Rien dans CheesePurse ne peut le faire à sa place, et
CheesePurse appelle délibérément `modify` plutôt que `silentModify` — ce retour est la
seule confirmation qu'un joueur reçoit que le dépôt a eu lieu.

Avec le réglage `CHAT` et un revenu régulier en pièces, cela fait une ligne de chat
toutes les demi-secondes. C'est à prendre en compte si l'économie de ton serveur
distille la monnaie en continu.

## Performances

Chaque balayage coûte un `Set.contains` par joueur connecté, plus, pour chaque joueur
inscrit, un parcours de 41 emplacements d'inventaire avec un test `instanceof` sur
chacun. Cela tourne sur le thread serveur, de façon synchrone, deux fois par seconde.

Avec cinquante joueurs inscrits, cela fait environ 4 100 vérifications d'emplacement par
seconde. Ce n'est pas rien, mais cela reste bien dans le bruit d'un tick normal, et il
n'y a aucune entrée-sortie : l'écriture dans la bourse est une affectation de `long`
suivie d'un paquet de synchronisation de composant, et il n'y a aucune écriture de
fichier sur tout le chemin.

La partie coûteuse, s'il y en a une, c'est `modify` qui construit et envoie un message
texte par dépôt et par joueur. Sur un serveur où beaucoup de joueurs utilisent
l'interrupteur et gagnent des pièces en permanence, cela fait un paquet de chat chacun à
chaque balayage.

---

← [Premiers pas](getting-started.fr.md) · [Configuration](configuration.fr.md) →
