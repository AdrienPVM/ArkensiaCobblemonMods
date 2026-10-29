[← EggAndCheese](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](getting-started.md)

# Premiers pas

À la fin de ce parcours, un second joueur fera un clic droit sur un œuf que tu as caché,
entendra le son de ramassage, lira sa ligne de découverte et repartira avec un œuf
décoratif dont tu as conçu la texture toi-même. Le dashboard affichera une découverte pour
un collectible.

Quinze minutes sur un serveur neuf. Il te faut un serveur avec
[EggAndCheese installé](installation.fr.md), un compte op, et un second compte qui n'est
**pas** op — un clic droit d'admin n'est pas une découverte, tu ne peux donc pas tester
l'expérience joueur avec ton propre compte op.

## 1. Connecte-toi au dashboard

Ouvre `http://<serveur>:44311` et connecte-toi. Clique sur **Collectibles** dans la barre
latérale — l'entrée 🥚 de la section **EggAndCheese**.

Une liste vide et une barre d'outils. S'il n'y a aucune section EggAndCheese, arrête-toi là et
déroule [le dépannage](troubleshooting.fr.md#le-dashboard-na-pas-de-section-œufs) : rien de
ce qui suit ne fonctionnera.

## 2. Crée le collectible, avec l'id que tu veux vraiment

Fais-le **avant** de poser quoi que ce soit. Les ids sont visibles par les joueurs — le
message de découverte affiche l'id, pas le titre — et renommer un œuf après l'avoir posé
laisse un bloc orphelin derrière, ce qui est un vrai bug du code actuel.

Dans la vue Collectibles, appuie sur **+ Nouveau**. Le formulaire demande un titre, une
dimension et des coordonnées — pas d'id —, l'entrée en reçoit donc un automatique
(`egg_001` sur un serveur neuf). Les coordonnées n'importent pas encore ; les valeurs par
défaut du formulaire sont `0, 64, 0` dans `minecraft:overworld`. Appuie sur **Créer** et le
dashboard ouvre la page de détail de la nouvelle entrée.

Donne-lui maintenant l'id que tu veux : ouvre l'onglet **Configuration**, remplace l'id par
`cave_nord` et appuie sur **Renommer**. Renommer est sans danger à ce stade, puisqu'aucun
bloc ne porte encore l'id.

Ce que tu as, c'est une entrée de registre avec un ADN aléatoire et **aucun bloc dans le
monde**. `POST /collectibles` ne pose jamais rien. L'entrée est désormais listée, avec sa
palette.

⚠️ Ne touche pas aux coordonnées de l'onglet **Localisation** tant que l'œuf n'est pas posé.
Les modifier — à la main ou avec **Utiliser ma position** — fait supprimer par le serveur
le bloc qui se trouve aux anciennes coordonnées (`0, 64, 0` ici, donc le terrain) et poser
l'œuf aux nouvelles ; voir [l'API du dashboard](dashboard.fr.md#put-collectiblesid).

## 3. Compose son apparence

La page de détail, désormais sous `cave_nord`, montre l'aperçu 3D d'un côté et les champs
d'ADN de l'autre. Fais tourner l'aperçu à la souris.

Change quelques champs et regarde l'aperçu suivre :

- mets la **palette** sur `ICE`
- mets le **motif** sur `CRYSTALS` et l'**échelle de motif** sur `2`
- mets l'**émissif** sur `STARS` avec une densité autour de `60`
- mets le **tampon** sur `MOON`

Le formulaire n'a pas de champ de rareté : la rareté vient avec l'ADN aléatoire, et seule
une régénération ou un `PUT` d'API portant `dna.rarity` la change.

Il n'y a pas de bouton Enregistrer. Le formulaire s'enregistre tout seul 1,5 seconde après
ta dernière modification, par un `PUT` dont l'objet `dna` porte tous les champs du
formulaire — douze des quatorze composantes. Les deux qu'il n'affiche pas, `seed` et
`rarity`, sont conservées par la fusion. Les règles complètes de fusion, et les quelques
pièges qu'elles contiennent, sont dans
[l'ADN d'un collectible](dna.fr.md#immuabilité-et-ce-quun-put-doit-faire).

⚠️ N'appuie **pas** sur le bouton **Rafraîchir** sous le code ADN à ce stade. Malgré son
libellé, il appelle `randomize-dna` : il remplace tout l'ADN, graine et rareté comprises,
par un ADN aléatoire neuf et jette tout ce que tu viens de régler. Si tu veux brasser avant
de t'engager sur un style, brasse d'abord, règle ensuite.

Pendant que tu y es, ouvre l'onglet **Configuration** et renseigne un **titre** —
`La grotte du nord` — et un **sous-titre**.
Ils servent à la quête FTB si tu l'actives plus tard, et sont inertes sinon. Ils ne
changent **pas** ce qu'un joueur lit à la découverte.

Copie le **code ADN** de la page de détail quelque part en sûreté. Il ressemble à ça :

```text
EAC-5F2A91C30B4D77E8-ICE.CRYSTALS.2.MARBLE.35.STARS.60.EPIC.MOON.ICY.0F.45
```

Cette unique chaîne consigne toute l'apparence, graine comprise. Rien dans le dashboard ne
la relit — il n'existe aucun champ où la coller —, c'est donc une trace, pas un import :
pour retrouver un style après un **Rafraîchir** malencontreux, ressaisis ses champs à la
main, ou envoie-les, `seed` et `rarity` compris, dans l'objet `dna` d'un
`PUT /collectibles/:id`.

## 4. Récupère l'item de placement

En jeu, avec ton compte op :

```mcfunction
/eac give cave_nord
```

```text
Oeuf admin donné pour : cave_nord
```

L'item dans ton inventaire est un **Oeuf Admin** porteur de l'ADN, de l'id et de l'échelle
de cette entrée. En main, il s'affiche comme le véritable œuf 3D avec ta texture — ta
première confirmation que le client est d'accord avec l'aperçu du dashboard.

Si tu obtiens `ID inconnu : cave_nord`, vérifie l'orthographe : la recherche est exacte,
sensible à la casse, et l'argument n'a pas de complétion.

## 5. Cache-le

Marche jusqu'à l'endroit où l'œuf doit être et fais un clic droit sur une face de bloc.

```text
Oeuf 'cave_nord' replacé avec succès — position rétablie.
```

C'est le message que tu veux. « Replacé » est la bonne branche : l'item désignait un id
existant, le mod a donc lié ce bloc à ton entrée et écrit les vraies coordonnées dans le
registre. Si tu vois plutôt

```text
Oeuf créé avec l'ID automatique 'egg_001'. Modifiable depuis le dashboard CheeseDash.
```

c'est que l'item n'avait pas d'id — tu as probablement utilisé
`/give eggandcheese:admin_egg` au lieu de `/eac give cave_nord`. Casse cet œuf, supprime
l'entrée parasite (`egg_001`, ou le numéro qu'elle a reçu) depuis le dashboard, et reviens
à l'étape 4.

L'œuf est désormais :

- en rotation lente, sans collision — les joueurs le traversent,
- incassable en survie pour quiconque n'est pas admin — le mode créatif et les explosions
  le retirent tout de même, voir [placement et découverte](placement-and-discovery.fr.md#indestructibilité),
- waterloggable, il peut donc se tenir dans un ruisseau.

Rafraîchis le dashboard. `cave_nord` affiche maintenant les coordonnées où tu l'as posé, et
`positionLost` est à faux.

## 6. Mets-le à la taille de sa cachette

L'échelle se trouve dans l'onglet **Localisation**. Mets-la à `1.4` ; l'onglet s'enregistre
tout seul.

⚠️ Le registre prend la nouvelle valeur, mais l'œuf dans le monde ne change **pas**.
L'onglet Localisation envoie toujours les coordonnées avec l'échelle, et l'API ne
synchronise l'échelle sur le bloc en place que si le `PUT` ne porte ni `x`, ni `y`, ni `z`,
ni `dimension` — un bug du code actuel. Pour redimensionner l'œuf posé, envoie l'échelle
seule :

```bash
curl -X PUT -H "Authorization: Bearer <token>" -d '{"scale":1.4}' \
  http://localhost:44311/api/v1/eggs/collectibles/cave_nord
```

ou pose l'œuf à nouveau depuis un `/eac give cave_nord` frais, dont l'item porte l'échelle
du registre.

L'échelle est bornée à 0,1–4,0. Un `PUT` avec `10` renvoie `200` avec `4.0`, en silence :
relis la valeur.

Si cet œuf trône sur une place et doit rester net de loin, mets **Distance de rendu**,
dans l'onglet **Configuration**, à `0`, c'est-à-dire « ne jamais dégrader celui-ci ». `-1`
signifie « suivre la distance globale du serveur ». Ce champ-là atteint immédiatement le
bloc posé.

## 7. Découvre-le, en joueur

Connecte-toi avec le compte **sans op** et fais un clic droit sur l'œuf.

```text
Vous avez découvert : cave_nord !
```

en barre d'action, le son de ramassage d'orbe d'expérience, et un **Oeuf Décoratif** dans
l'inventaire, avec ta texture et une infobulle qui annonce son motif et sa palette.

Clic droit à nouveau :

```text
Vous avez déjà trouvé celui-ci !
```

Chaque œuf ne compte qu'une fois par joueur.

Regarde maintenant le dashboard : `cave_nord` affiche une découverte. Ouvre sa liste de
découvertes et tu verras le pseudo du joueur, résolu depuis le cache utilisateurs du
serveur.

Si rien ne s'est passé du tout, les causes probables, par fréquence : tu as testé avec un
compte op (les admins prennent une branche entièrement différente), le bloc n'est pas lié à
l'entrée, ou tu regardes un autre œuf. Voir
[le dépannage](troubleshooting.fr.md#un-joueur-fait-un-clic-droit-sur-un-œuf-et-rien-ne-se-passe).

## 8. Expose la récompense

En joueur, fais un clic droit sur une face de bloc avec l'œuf décoratif. Il se pose comme
un bloc décoratif cassable porteur du même ADN ; le casser rend l'item.

Pour quelque chose de plus beau, fabrique une stèle — une colonne de deux plus une base de
trois, dans l'un des 23 matériaux :

```text
 X
 X
XXX
```

Pose-la, fais un clic droit dessus avec l'œuf décoratif, et l'œuf se place au sommet, en
rotation. Règle-la ensuite en la regardant, à 5 blocs au maximum :

```mcfunction
/pedestal scale 2.0
/pedestal pillar 2.0
/pedestal spin
```

```text
Taille d'affichage définie à 2,0.
Hauteur du pilier définie à 2,0.
Rotation désactivée.
```

⚠️ `/pedestal` n'a **aucune exigence de permission**. N'importe quel joueur peut rerégler
n'importe quelle stèle devant laquelle il peut se placer. Si c'est un problème sur ton
serveur, restreins la commande dans ton mod de permissions.

## 9. Vérifie l'intégrité avant de construire plus grand

Tu n'as qu'un œuf. Appuie quand même sur **Intégrité** dans la vue Collectibles, pour savoir à quoi
ressemble un rapport propre :

```text
Intégrité OK — 1 blocs d'œufs chargés, aucun doublon ni orphelin.
```

Reviens à ce bouton chaque fois que des œufs se comportent bizarrement — deux œufs
identiques, un bloc qui ne fait rien au clic, une entrée du dashboard dont les coordonnées
sont vides. Il trouve doublons et orphelins dans les **chunks chargés** et les répare en
déliant, jamais en supprimant. [Intégrité](integrity.fr.md) contient la procédure
complète, y compris la réserve sur le fait de parcourir la zone.

## 10. Passe à une vraie chasse

La boucle, à partir du deuxième œuf, ce sont les étapes 2 à 5 : créer l'entrée, la renommer
avec son id définitif, composer l'ADN, `/eac give <id>`, poser. Quinze œufs, c'est environ une heure.

Deux raccourcis une fois que tu maîtrises :

- **Poser d'abord, ranger ensuite.** Un clic droit avec un `eggandcheese:admin_egg` nu crée
  sur place une entrée à l'id auto-généré (`egg_001`, `egg_002`, …) avec un ADN aléatoire.
  Plus rapide pour une grande chasse, au prix d'ids qu'un joueur lira `egg_014`.
- **Réutiliser un style.** Le code ADN de l'étape 3 énumère une apparence entière, champ par
  champ. Recopie ces valeurs dans le formulaire d'ADN d'un autre œuf pour donner un air de
  famille à un ensemble — à la main, puisque le dashboard n'importe pas le code lui-même.

### Le brancher à FTB Quests

Si ton pack a FTB Quests, coche **FTB Quests** dans l'onglet **Configuration** de la vue
Collectibles (c'est `ftbQuestsEnabled`) et choisis un nom de chapitre. Dès lors, créer un œuf crée une quête avec l'œuf pour
icône, et une découverte la valide.

Pour les œufs créés avant l'activation, lance une synchronisation une fois — le bouton
**Recharger** de ce panneau — mais lis
d'abord [la page FTB](ftb-quests.fr.md#synchronisation-complète), parce qu'une
synchronisation complète supprime et recrée chaque quête d'œuf, et que les joueurs y
perdent leurs coches.

Si tu préfères que le mod n'écrive pas dans ton fichier de quêtes, laisse le réglage coupé
et pointe une tâche de type Advancement vers un advancement à toi construit sur le
déclencheur `eggandcheese:egg_found` — celui que livre le mod n'est jamais chargé, voir
[l'advancement](display.fr.md#ladvancement). Cette voie survit
aux mises à jour de FTB Quests ; le hook par réflexion, non.

---

← [Installation](installation.fr.md) · [Commandes](commands.fr.md) →
