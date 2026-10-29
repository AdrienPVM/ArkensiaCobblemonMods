[← CheeseItems](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](resource-pack.md)

# Resource pack

Un item personnalisé est un item vanilla, donc par défaut il y ressemble : une définition
construite sur `minecraft:paper` s'affiche comme du papier. Lui donner sa propre icône
suppose de faire parvenir une texture au client, et le mod s'y prend en construisant un ZIP
de resource pack en mémoire, puis en disant aux clients de le télécharger depuis le port
HTTP de CheeseDash.

Rien n'est écrit sur le disque. Le pack existe comme un `byte[]` dans
`ResourcePackGenerator`, reconstruit quand ses entrées changent.

## Ce qui déclenche une génération

| Événement | Effet |
|---|---|
| `SERVER_STARTED` | première génération, à partir des icônes présentes sur le disque |
| `POST /api/v1/items/:id/image` | régénération, puis envoi aux joueurs connectés si le pack a changé |
| `DELETE /api/v1/items/:id/image` | idem |
| `DELETE /api/v1/items/:id` | idem, après suppression de l'icône et de l'entrée `customModelData` |
| `CheeseItemsMod.regenerateAndPushPack()` appelé par un autre mod | idem — les icônes PropHunt de CheeseGuard le font, voir [entrées venant d'autres mods](#entrées-venant-dautres-mods) |

`/cheeseitem reload` ne régénère **pas** le pack. Déposer un PNG dans
`config/cheeseitems_assets/` à la main ne fait donc rien tant qu'un envoi d'icône ou un
redémarrage n'a pas eu lieu.

`generate()` indique si le SHA-1 a changé, et seul un pack modifié est envoyé. Cela compte
plus qu'il n'y paraît : chaque envoi force le client à retélécharger et réappliquer le pack,
ce qui réinitialise son ordre de resource packs dans `options.txt`. Le ZIP est écrit avec un
horodatage d'entrée fixe (`2025-01-01T00:00:00Z`) précisément pour qu'un ensemble d'icônes
inchangé produise un ZIP identique octet pour octet, et donc la même empreinte d'un
redémarrage à l'autre.

## Ce que contient le ZIP

```text
pack.mcmeta
assets/<ns>/models/item/<item de base>.json        un par baseItem distinct utilisé
assets/cheeseitems/models/item/<id>.json           un par item ayant une icône
assets/cheeseitems/textures/item/<id>.png          le PNG envoyé, tel quel
assets/cheeseitems/textures/font/bar_0..10.png     seulement si les 11 images existent
assets/minecraft/font/default.json                 seulement si les 11 images existent
```

`pack.mcmeta` déclare `pack_format: 34`, la valeur de la 1.21.1, et la description
*CheeseItems Custom Icons*.

### Comment une icône est choisie

Chaque item ayant une icône reçoit un numéro de `customModelData`, attribué une fois et
stocké dans `config/cheeseitems.json`. `ItemManager` écrit ce numéro sur la pile comme
composant `CUSTOM_MODEL_DATA` — mais **seulement si l'item a aussi un fichier d'icône sur le
disque**. Si l'un des deux manque, le composant est retiré, de sorte qu'une pile ne pointe
jamais vers un modèle absent du pack.

Le pack remplace ensuite le modèle de l'*item de base* par une liste de prédicats :

```json
{
  "parent": "minecraft:item/generated",
  "textures": { "layer0": "minecraft:item/paper" },
  "overrides": [
    {"predicate": {"custom_model_data": 1}, "model": "cheeseitems:item/quest_key"},
    {"predicate": {"custom_model_data": 4}, "model": "cheeseitems:item/lantern_of_arkensia"}
  ]
}
```

Un fichier de ce type par `baseItem` distinct, avec les overrides triés par numéro. Les
items qui partagent un item de base partagent ce fichier.

⚠️ **Ce fichier remplace intégralement le modèle de l'item de base.** Chaque item utilisé
comme `baseItem` perd ce qu'un autre resource pack lui faisait — un pack de textures qui
restyle le papier et une définition CheeseItems construite sur du papier ne peuvent pas
gagner tous les deux, et c'est l'ordre de chargement qui tranche. Choisis des items de base
que ton pack de textures ne touche pas, ou accepte que la version de CheeseItems soit celle
que les joueurs voient. L'inverse est vrai aussi : un pack chargé au-dessus de celui de
CheeseItems tue les icônes personnalisées.

### <a id="la-police-de-la-barre-dendurance"></a>La police de la barre d'endurance

La barre d'endurance peut être dessinée avec tes propres images au lieu des caractères
`◆`/`◇`. Place onze PNG dans `config/cheeseitems_assets/bar/0.png` jusqu'à `10.png`,
représentant 0 % à 100 % par pas de 10.

Les onze doivent exister. `hasBarImages()` les vérifie tous et renvoie faux au premier
manquant : dix images ne donnent rien, et sans avertissement.

Quand ils sont tous là, le pack les associe aux points de code d'usage privé `U+E000` à
`U+E00A`, et la barre d'action envoie le seul caractère correspondant au niveau arrondi. Les
glyphes sont déclarés avec `ascent: -3` et `height: 16`.

⚠️ La police est injectée comme `assets/minecraft/font/default.json`, c'est-à-dire **toute
la définition de la police par défaut**. Minecraft fusionne les fournisseurs de police entre
les packs, donc cela cohabite d'habitude avec les polices d'autres packs — mais un pack qui
livre aussi un `default.json` complet et se charge après remplacera celui-ci, et la barre
devient une suite de carrés « glyphe manquant ». Dans ce cas, le repli `◆`/`◇` n'est pas
utilisé : le mod envoie toujours le glyphe, parce qu'il ne vérifie que la présence des PNG
côté serveur.

## <a id="le-pack-est-servi-en-http"></a>Le pack est servi en HTTP

`GET http://<resourcePackHost>/cheeseitems/api/pack.zip` — sans authentification, via le
mécanisme de route publique de CheeseDash. `resourcePackHost` est un simple `hôte:port` et
retombe sur `localhost:44311`, ce qui marche sur une machine de test et échoue en silence
pour chaque joueur distant. [Règle-le](configuration.fr.md#resourcepackhost).

L'envoi a lieu à la connexion, en deux paquets :

1. `ResourcePackRemoveS2CPacket` pour l'UUID fixe du pack, dérivé de la chaîne
   `cheeseitems-pack`, afin qu'un client détenant une version antérieure avec une autre
   empreinte l'abandonne d'abord. Sans ce retrait, le client peut se retrouver avec une
   incohérence UUID/SHA-1, qui est ce qui corrompt `options.txt`.
2. `ResourcePackSendS2CPacket` avec l'URL, le SHA-1, `required = false` et l'invite
   *Custom item icons for Cacademy*.

`required = false` signifie qu'un joueur peut refuser le pack. Il voit alors les icônes
vanilla et la barre textuelle, et rien d'autre ne casse.

Si le pack est vide — aucune icône et aucune image de barre — rien n'est envoyé du tout, et
le log le dit :

```text
[CheeseItems] Resource pack cleared (no custom icons, no bar images)
```

Si `resourcePackHost` ne résout rien d'exploitable, l'envoi est ignoré pour chaque joueur
avec un avertissement :

```text
[CheeseItems] resourcePackHost not configured in cheeseitems.json — cannot send pack to Steve
```

## Entrées venant d'autres mods

`ResourcePackGenerator` expose deux méthodes qu'un mod voisin peut appeler pour glisser ses
propres icônes dans le même pack :

```java
generator.addExternalEntry(id, namespace, baseItem, customModelData, pngBytes);
generator.removeExternalEntries(idPrefix);
```

Les entrées externes sont ajoutées aux entrées d'items à chaque génération et ne sont
**pas** persistées — l'appelant doit les réenregistrer après chaque redémarrage. Elles ne
reçoivent pas non plus de `customModelData` depuis `CheeseItemsConfig`, donc l'appelant est
responsable de choisir des numéros qui n'entrent pas en collision avec ceux de
`config/cheeseitems.json`. Le seul appelant de ce dépôt est `PropHuntIconStorage` de
[CheeseGuard](../../CheeseGuard/), qui atteint les deux méthodes par réflexion, enregistre ses
icônes sous le préfixe `ph_` avec des numéros à partir de 900, puis appelle
`CheeseItemsMod.regenerateAndPushPack()`.

## Vérifier ce que les joueurs ont vraiment reçu

Le log du serveur donne la taille et l'empreinte de chaque génération :

```text
[CheeseItems] Endurance bar font added to resource pack (11 levels)
[CheeseItems] Resource pack generated: 3 items, 18422 bytes, SHA1=4f1c…
[CheeseItems] Pack changed — pushing to 7 online players
```

et tu peux télécharger le pack toi-même avec la même URL que les clients, ce qui est la
façon la plus rapide de confirmer qu'une texture est bien dedans :

```bash
curl -O http://play.example.net:44311/cheeseitems/api/pack.zip
unzip -l pack.zip      # curl -O nomme le fichier d'après l'URL, pas d'après Content-Disposition
```

---

← [Items personnalisés](items.fr.md) · [Morphos](morphos.fr.md) →
