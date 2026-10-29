[← CheeseVanity](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](configuration.md)

# Configuration

```text
config/cheesevanity.json
```

Le chemin est construit dans `VanityConfig` par `Path.of("config", "cheesevanity.json")`,
un chemin **relatif**. Il se résout par rapport au répertoire de travail du processus, qui
pour un lancement de serveur normal est le dossier du serveur, à côté de `world/` et de
`mods/`. En pratique c'est le même endroit que celui désigné par
`FabricLoader.getConfigDir()`, mais ce n'est pas le même code : démarre le serveur avec un
répertoire de travail différent de sa racine et le fichier sera créé ailleurs. Un client
écrit aussi ce fichier, sous `.minecraft/config/`, où il n'a aucun effet — la configuration
n'est jamais lue que par le côté serveur.

Le fichier est écrit avec un objet aux valeurs par défaut la première fois que le mod
démarre sans lui, donc tu n'as jamais à le créer à la main.

---

## Clés

Trois clés, toutes au niveau racine. Il n'y a pas de sections.

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `enableVanillaArmor` | booléen | `false` | autorise la bascule des quatre slots d'armure vanilla (`helmet`, `chestplate`, `leggings`, `boots`) |
| `enableTrinkets` | booléen | `true` | autorise la bascule de tous les slots Trinkets |
| `disabledSlots` | tableau de chaînes | `[]` | slot keys qui ne peuvent jamais être basculées, quoi que disent les deux booléens |

Les trois sont lues par une seule méthode, `VanityConfig.isSlotToggleable`, dans cet
ordre :

```java
if (disabledSlots.contains(slotKey)) return false;
if (isVanillaArmorSlot(slotKey)) return enableVanillaArmor;
return enableTrinkets;
```

`disabledSlots` l'emporte donc sur tout, les booléens d'armure se départagent selon que la
clé est l'un des quatre mots vanilla, et `enableTrinkets` sert de repli pour toutes les
autres clés.

### `enableVanillaArmor`

**Il vaut `false` par défaut, et ce défaut surprend.** À l'installation, appuyer sur `=`
sur un casque ne fait rien et ne dit rien. Le commentaire de `VanityConfig.java` explique
pourquoi : sur le serveur Arkensia, un autre mod gère déjà la visibilité des armures, et
CheeseVanity s'en écarte volontairement. Sur n'importe quel autre serveur ce n'est pas
vrai, et c'est la première clé que tu voudras inverser :

```json
"enableVanillaArmor": true
```

L'activer ne change rien de façon rétroactive. Cela ne change que ce que le serveur
accepte à partir de ce moment. Un joueur qui a masqué un casque pendant que la clé valait
`true` garde ce casque masqué après que tu l'aies remise à `false` — l'ensemble stocké
n'est jamais filtré au chargement, seules les écritures sont vérifiées. Pour réafficher
réellement ces slots, il faut retirer les clés de `config/cheesevanity_data.json` à la
main.

### `enableTrinkets`

Couvre tous les slots Trinkets d'un coup. Il n'y a pas d'interrupteur par groupe ; si tu
veux autoriser `chest/*` mais pas `head/*`, tu le fais en listant les clés `head` dans
`disabledSlots`.

La mettre à `false` alors que Trinkets est installé, en laissant `enableVanillaArmor` à
`false`, transforme le mod en coquille vide : plus rien n'est basculable nulle part. Rien
ne te prévient de cette combinaison.

### `disabledSlots`

Une simple liste de slot keys, comparées par égalité de chaînes exacte — pas de joker, pas
de préfixe, pas de `chest/*`. Récupère les clés dans le log client comme décrit dans
[la page des contrôles](commands.fr.md#lire-la-slot-key-dun-slot).

Le cas d'usage qui justifie cette clé, ce sont les slots fonctionnels qui ne devraient pas
devenir invisibles. Un sac à dos dans `chest/back` est l'exemple type : un sac à dos
masqué, c'est un joueur qui a l'air désarmé et qui transporte quarante stacks, et sur un
serveur PvP c'est une information à laquelle les autres joueurs ont droit. Même
raisonnement pour un équivalent d'élytres ou pour un trinket dont le palier doit se voir.

```json
"disabledSlots": ["chest/back", "head/hat"]
```

Les clés de cette liste restent d'apparence basculable dans l'interface — il n'y a pas
d'état grisé et pas de message. Un joueur qui appuie sur `=` sur un slot désactivé obtient
le silence. Dis à tes joueurs quels slots tu as bloqués, parce que le mod ne le fera pas.

---

## Rechargement

**Aucun. La configuration est lue exactement une fois**, dans
`CheeseVanityMod.onInitialize`, dans un champ statique. Aucune commande ne la recharge,
aucune surveillance de fichier ne la relit, et `/reload` n'y touche pas. Modifier le
fichier sur un serveur en marche ne change rien jusqu'au prochain redémarrage.

La même chose vaut pour `cheesevanity_data.json` : lu une fois au démarrage, écrit à chaque
bascule. L'éditer pendant que le serveur tourne est pire qu'inutile, parce que la bascule
suivante de n'importe quel joueur réécrit tout le fichier depuis la mémoire et jette ta
modification. Arrête le serveur d'abord.

---

## Exemple complet annoté

JSON n'accepte pas les commentaires, donc les annotations ci-dessous doivent disparaître
avant usage. Copie plutôt le bloc qui suit.

```json
{
  "enableVanillaArmor": true,      // laisse masquer helmet/chestplate/leggings/boots
  "enableTrinkets": true,          // laisse masquer tous les slots Trinkets
  "disabledSlots": [               // sauf ceux-ci, qui restent visibles pour tous
    "chest/back",                  // sacs à dos — masquer du stockage est déloyal en PvP
    "offhand/shield"               // exemple : un slot que ton pack déclare pour les boucliers
  ]
}
```

La version utilisable :

```json
{
  "enableVanillaArmor": true,
  "enableTrinkets": true,
  "disabledSlots": [
    "chest/back"
  ]
}
```

Et le fichier que le mod écrit pour lui-même au premier lancement, qui est aussi le
« défaut Arkensia », là où un autre mod gère les armures :

```json
{
  "enableVanillaArmor": false,
  "enableTrinkets": true,
  "disabledSlots": []
}
```

Gson désérialise le fichier directement sur les champs de `VanityConfig`. Une clé que tu
omets garde son défaut Java, une clé mal orthographiée est ignorée en silence, et un
fichier mal formé est attrapé, journalisé en `[CheeseVanity] Failed to load config`, puis
remplacé par les valeurs par défaut — le fichier cassé est alors **écrasé**, donc garde une
copie avant d'expérimenter.

---

← [Contrôles](commands.fr.md) · [Qui voit quoi](visibility.fr.md) →
