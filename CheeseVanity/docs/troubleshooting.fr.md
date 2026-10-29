[← CheeseVanity](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](troubleshooting.md)

# Dépannage

Presque tous les rapports sur ce mod disent « appuyer sur `=` ne fait rien », et ce seul
symptôme a cinq causes différentes. Le mod ne donne au joueur aucun message d'erreur pour
aucune d'entre elles, donc le diagnostic est toujours le même : déterminer jusqu'où la
bascule est allée dans la chaîne.

La chaîne est : appui de touche → slot résolu → paquet envoyé → serveur accepte → serveur
diffuse → client rend. Chaque symptôme ci-dessous est l'un de ces maillons qui casse.

---

## Le mod ne se charge pas du tout

**Cause.** Fabric Loader a refusé le jar. Les raisons habituelles : Fabric API absent,
loader antérieur à 0.16.0, Minecraft qui n'est pas en 1.21.1, deux copies de
`cheesevanity-*.jar` dans `mods/`, ou un Java antérieur à 21.

**Remède.** Lis l'erreur du loader en haut de `logs/latest.log` ; elle nomme la dépendance
non satisfaite. L'ensemble requis est dans
[installation.fr.md](installation.fr.md#prérequis). Supprime tout
`cheesevanity-*.jar` plus ancien avant de copier le nouveau : le même identifiant de mod
deux fois est un arrêt net.

---

## Appuyer sur `=` ne fait rien et le log client reste muet

**Cause.** `HandledScreenMixin` ne s'est jamais exécuté. Soit le mod n'est pas dans le
`mods/` du **client**, soit `cheesevanity.mixins.json` n'a pas pu s'appliquer, soit un autre
mod a consommé l'appui de touche avant d'atteindre le `keyPressed` vanilla.

**Remède.** Vérifie que `[CheeseVanity] Client initialized` est bien dans le log client. Si
la ligne manque, le jar n'est installé que côté serveur — installe-le aussi côté client. Si
elle est là et que les lignes de slot n'apparaissent toujours pas, cherche
`Mixin apply failed cheesevanity.mixins.json` dans le log client : c'est un conflit de mods
sur `HandledScreen`, et il faut le nom du mod fautif dans la trace de pile pour aller plus
loin.

---

## Le log affiche `Resolved slotKey: null`

**Cause.** Le slot survolé n'est pas d'une des deux sortes que le mod comprend. Seules les
instances de `dev.emi.trinkets.TrinketSlot` et les quatre index d'armure d'un
`PlayerInventory` (36 à 39) se résolvent en clé. Un slot de coffre, un slot de barre
d'items, un slot de craft ou un slot d'accessoire fourni par une autre API renvoie `null`.

**Remède.** Il n'y a rien à configurer ici : un slot `null` ne peut pas être rendu
basculable sans modifier `resolveSlotKey`. Survole plutôt un slot que le mod prend en
charge. Si le slot que tu veux est bien un slot Trinkets et renvoie quand même `null`,
vérifie que Trinkets est installé côté **client** : sans lui, la branche par réflexion est
sautée entièrement et seules les armures se résolvent.

---

## La slot key est résolue mais aucun message n'apparaît

**Cause.** Le serveur a rejeté la bascule, ou ne l'a jamais reçue. Trois possibilités, par
ordre de vraisemblance décroissante :

1. La configuration interdit ce genre de slot. `enableVanillaArmor` vaut `false` par
   défaut, donc tous les slots d'armure sont refusés sur une installation neuve.
2. La slot key figure dans `disabledSlots`.
3. Le serveur n'a pas le mod, donc le canal `cheesevanity:toggle_slot` est inconnu et le
   payload est jeté.

**Remède.** Cherche le refus dans le log **serveur**, où il est écrit en entier :

```text
[CheeseVanity] Slot 'helmet' not toggleable (enableVanillaArmor=false, enableTrinkets=true, isVanillaArmor=true)
```

Cette ligne te dit dans lequel des deux premiers cas tu te trouves. Aucune ligne du tout, et
aucune ligne `[CheeseVanity] Loaded` non plus, signifie le troisième. Modifie
`config/cheesevanity.json` et **redémarre** — voir le symptôme suivant.

---

## J'ai modifié la configuration et rien n'a changé

**Cause.** La configuration est lue exactement une fois, dans
`CheeseVanityMod.onInitialize`. Il n'y a aucune commande de rechargement, aucune
surveillance de fichier, et `/reload` n'y touche pas.

**Remède.** Redémarre le serveur, puis confirme les valeurs depuis la ligne de démarrage :

```text
[CheeseVanity] Loaded — vanilla armor: true, trinkets: true
```

Si ces valeurs ne correspondent toujours pas à ton fichier, tu as modifié un autre fichier.
Le chemin est relatif au répertoire de travail du serveur, et un client écrit sa propre copie
inutile sous `.minecraft/config/`. [configuration.fr.md](configuration.fr.md) donne le
détail.

---

## J'ai désactivé un réglage et des slots restent masqués

**Cause.** `isSlotToggleable` ne filtre que les **écritures**, pas les lectures. Ce qui est
déjà dans `config/cheesevanity_data.json` continue d'être diffusé et rendu, quoi que dise
maintenant la configuration. Ajouter une clé à `disabledSlots` empêche de même toute bascule
ultérieure de cette clé et ne démasque rien.

**Remède.** Arrête le serveur, édite `config/cheesevanity_data.json`, retire les clés
fautives des tableaux des joueurs (ou supprime entièrement l'entrée d'un joueur), redémarre.
Ne l'édite pas serveur allumé : la bascule suivante de n'importe qui réécrit tout le fichier
depuis la mémoire et ta modification disparaît.

---

## Le message dit `hidden` mais l'item est toujours rendu

**Cause.** Le côté serveur a fonctionné et le côté rendu non. Pour une armure, cela signifie
que `ArmorFeatureRendererMixin` ne s'est pas appliqué, ce qui aurait aussi produit une erreur
de mixin dans le log client. Pour les trinkets, c'est plus probable et plus discret :
`TrinketFeatureRendererMixin` injecte avec `require = 0`, donc si
`TrinketFeatureRenderer.render` ne correspond plus à la signature attendue, le mixin
s'applique avec **zéro** injection et ne dit rien.

**Remède.** Vérifie la version de Trinkets. Le mod est compilé contre
`dev.emi:trinkets:3.10.0` et l'injection vise cette forme de méthode ; une mise à jour de
Trinkets peut la casser sans rien casser d'autre. Le symptôme est spécifique et
reconnaissable : le message en barre d'action et la croix rouge fonctionnent tous les deux,
seul le modèle ne change pas. Il n'existe pas de contournement, il faut reconstruire le mixin
contre la nouvelle version de Trinkets.

---

## Les autres joueurs voient encore mon équipement

**Cause.** Ces joueurs n'ont pas CheeseVanity dans le `mods/` de leur client. Le masquage est
assuré par des mixins de rendu côté client ; un client sans le mod n'a rien pour masquer. Le
serveur ne déséquipe ni ne retire jamais rien, donc il n'existe aucun repli côté serveur.

**Remède.** Rends CheeseVanity obligatoire côté client sur ton serveur. Si ton pack est
distribué par un lanceur ou par un système de synchronisation automatique, ajoute-le là.
Aucune configuration ne rendra coopératif un client non moddé. C'est une limite de
conception, documentée sur [la page de visibilité](visibility.fr.md#la-frontière-exacte).

---

## Quelqu'un paraît partiellement invisible sur un serveur qui n'a pas le mod

**Cause.** Un cache client périmé. `VanityClientState` n'est jamais vidé — la méthode
`clear()` existe mais rien ne l'appelle, et il n'y a aucun hook de déconnexion. Rejoins le
serveur A où ton équipement est masqué, déconnecte-toi, rejoins le serveur B dans la même
session de jeu, et le client de B détient toujours les entrées de A.

**Remède.** Redémarre le client. C'est la seule remise à zéro fiable. Si tu maintiens le mod,
le correctif est un gestionnaire `ClientPlayConnectionEvents.DISCONNECT` qui appelle
`VanityClientState.clear()` ; il est listé dans les
[faiblesses connues](architecture.fr.md#faiblesses-connues).

---

## Mes préférences ont disparu

**Cause.** Soit `config/cheesevanity_data.json` a été perdu ou remplacé, soit tu es sur un
autre serveur. Le fichier appartient à une installation de serveur et est indexé par UUID de
joueur ; rien n'est partagé entre serveurs, et un réseau avec un proxy et plusieurs serveurs
d'arrière-plan garde des fichiers distincts, sauf si ces serveurs partagent un dossier
`config/`.

**Remède.** Restaure le fichier depuis une sauvegarde, serveur arrêté. Cherche dans le log
`[CheeseVanity] Failed to load data`, qui signifie que le fichier existait mais n'a pas pu
être parsé — dans ce cas la table en mémoire démarre vide, et la première bascule qui suit
écrase le fichier cassé : récupère-le avant de laisser qui que ce soit jouer.

---

## Le dashboard n'a pas de module CheeseVanity

**Cause.** Il n'y a pas de module. CheeseVanity ne déclare pas d'entrypoint `cheesedash`
dans son `fabric.mod.json` et n'expose aucune route REST. Son absence de la barre latérale
est le comportement correct, pas une panne.

**Remède.** Aucun. L'administration se fait par le fichier de configuration et le fichier de
données, tous deux édités sur le disque serveur arrêté.

---

## Signaler un bug

Le rapport qui se corrige vite contient quatre choses.

1. **Le log client**, `.minecraft/logs/latest.log`. Joins le fichier entier si tu peux. Les
   lignes qui comptent sont `[CheeseVanity] Client initialized`, toutes les lignes
   `[CheeseVanity] Key '='` et `Resolved slotKey` autour de l'échec, et n'importe quelle
   ligne `Mixin apply failed` où que ce soit dans le fichier.
2. **Le log serveur**, `logs/latest.log`. La ligne
   `[CheeseVanity] Loaded — vanilla armor: ..., trinkets: ...` prouve quelle configuration
   était active, et toute ligne `not toggleable` explique un refus silencieux.
3. **`config/cheesevanity.json`**, tel quel. Trois clés, donc colle le fichier entier.
4. **Quel maillon de la chaîne a cassé** — appui de touche, slot résolu, message reçu, modèle
   changé, autres joueurs d'accord. Dire « appuyer sur `=` résout `chest/back`, je reçois le
   message rouge, le modèle ne change pas » fait passer de cinq causes candidates à une.

Précise aussi si Trinkets est installé et dans quelle version, et si le joueur qui ne voit
pas le changement a le mod lui-même. Ces deux questions expliquent la majorité des rapports.

Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues) ·
Questions → [Discord Arkensia](https://discord.gg/fYmPwMBjdB)

---

← [Architecture](architecture.fr.md) · [CheeseVanity](../README.fr.md) →
