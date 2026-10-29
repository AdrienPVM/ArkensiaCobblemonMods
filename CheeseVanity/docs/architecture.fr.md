[← CheeseVanity](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](architecture.md)

# Architecture

Dix fichiers Java, environ 630 lignes, aucun module dashboard, aucune commande, aucune
entrée de registre. Tout le mod tient dans un ensemble de chaînes par UUID de joueur, un
protocole à deux paquets pour le déplacer, et trois mixins client qui agissent dessus. Lire
le code source de bout en bout prend vingt minutes, et cette page existe surtout pour
pointer ce qui n'est pas évident et ce qui est fragile.

---

## Découpage des packages

```text
com.cheesevanity
├── CheeseVanityMod.java              entrypoint principal — côté serveur, 94 lignes
├── CheeseVanityClient.java           entrypoint client — un seul récepteur de paquet, 20 lignes
├── VanityConfig.java                 les trois clés de config + la règle de basculabilité
├── VanityData.java                   stockage serveur, UUID -> ensemble de slot keys
├── VanityClientState.java            cache client de la même forme
├── network
│   ├── ToggleSlotC2SPayload.java     C2S : une chaîne, la slot key
│   └── SyncHiddenSlotsS2CPayload.java S2C : un UUID et son ensemble complet de clés
└── mixin.client
    ├── HandledScreenMixin.java       la touche '=', le résolveur de slot key, la surcouche rouge
    ├── ArmorFeatureRendererMixin.java annule le rendu d'armure pour les slots vanilla masqués
    └── TrinketFeatureRendererMixin.java vide les slots de trinkets masqués autour du rendu
```

Deux configurations de mixins, qui ne listent toutes les deux que des mixins `client` :

| Fichier | `required` | `defaultRequire` | Contenu |
|---|---|---|---|
| `cheesevanity.mixins.json` | `true` | `1` | `HandledScreenMixin`, `ArmorFeatureRendererMixin` |
| `cheesevanity-trinkets.mixins.json` | `false` | `0` | `TrinketFeatureRendererMixin` |

Ce découpage est ce qui fait de Trinkets une véritable dépendance optionnelle. La seconde
configuration n'est pas requise et ses injections ne le sont pas non plus : quand Trinkets
est absent, le mixin échoue à s'appliquer et Loader continue sans un mot.

---

## Ce que possède chaque classe

**`CheeseVanityMod`** possède le serveur. Dans `onInitialize`, il charge la configuration et
le fichier de données, enregistre les deux types de payload, enregistre le récepteur C2S, et
s'accroche à `ServerPlayConnectionEvents.JOIN`. Il détient aussi l'instance statique de
configuration et le `Logger` nommé `CheeseVanity` par lequel toutes les autres classes
journalisent.

**`VanityData`** est l'unique source de vérité. Une `ConcurrentHashMap<UUID, Set<String>>`
dont les ensembles sont des `ConcurrentHashMap.newKeySet()`. `toggle` inverse l'appartenance
et renvoie le nouvel état, `getHidden` peut renvoyer `null` pour un joueur qui n'a rien de
masqué, `getAll` donne une vue non modifiable de la table mais **pas** des ensembles qu'elle
contient.

**`VanityConfig`** détient les trois clés et la procédure de décision. `isSlotToggleable`
vérifie d'abord `disabledSlots`, puis se branche sur `isVanillaArmorSlot`, une comparaison
statique de chaînes contre les quatre mots codés en dur.

**`VanityClientState`** reflète la forme des données côté client. `updatePlayer` retire
complètement l'entrée quand l'ensemble reçu est vide : c'est ainsi qu'un démasquage
s'exprime, le serveur n'envoie jamais d'instruction « effacer », il envoie un ensemble vide.

**`HandledScreenMixin`** fait trois métiers différents, soit un de plus qu'une classe ne
devrait : il lit l'appui de touche, il possède `resolveSlotKey` (le seul endroit où un `Slot`
devient une slot key), et il dessine la surcouche.

**`ArmorFeatureRendererMixin`** et **`TrinketFeatureRendererMixin`** forment le côté rendu, et
ils adoptent des approches opposées. Voir plus bas.

---

## L'état et le moment où il atteint le disque

| État | Vit dans | Écrit quand |
|---|---|---|
| configuration | l'instance statique de `VanityConfig` | au premier lancement seulement, quand le fichier est absent ou illisible |
| slots masqués, serveur | `VanityData.hiddenSlots` | `VanityData.save()` à **chaque** bascule |
| slots masqués, client | `VanityClientState.hiddenSlots` | jamais — mémoire seulement |

`save()` sérialise toute la table et réécrit `config/cheesevanity_data.json` en entier, en
JSON indenté, sur le thread serveur, de façon synchrone, à l'intérieur du gestionnaire de
bascule. Avec une poignée de joueurs cela fait quelques centaines d'octets et personne ne
s'en aperçoit. Avec mille joueurs stockés et quelqu'un qui martèle `=`, c'est une réécriture
complète de fichier par appui, sur le thread principal. Il n'y a ni anti-rebond, ni drapeau
« modifié », ni sauvegarde à l'arrêt — ce qui est cohérent, puisque chaque mutation est déjà
persistée au moment où elle se produit.

Les joueurs dont l'ensemble est vide sont sautés à la sérialisation, donc le fichier se
nettoie tout seul.

---

## Threading

Trois threads comptent.

**Le thread de rendu** lit `VanityClientState` depuis les deux mixins de rendu et depuis
`drawSlot`. Lectures seules, sans verrou, adossées à une `ConcurrentHashMap`.

**Le thread réseau client (netty)** reçoit le payload S2C et passe aussitôt la main avec
`context.client().execute(...)`, donc le cache est muté sur le thread principal client plutôt
que sous le renderer.

**Le thread réseau serveur** reçoit le payload C2S. Il valide la slot key sur place — nulle,
vide, plus longue que 64 caractères, non basculable — et seulement ensuite passe la main avec
`context.server().execute(...)`. Tout ce qui touche `VanityData`, le message au joueur et la
diffusion s'exécute sur le thread serveur. Valider hors du thread serveur est sûr parce que
cela ne lit que de la configuration immuable.

La seule bizarrerie de threading est dans le mixin trinkets, juste après.

---

## Les deux stratégies de rendu

`ArmorFeatureRendererMixin` injecte à la tête de `ArmorFeatureRenderer.renderArmor`, fait
correspondre l'`EquipmentSlot` à l'une des quatre clés d'armure, et appelle `ci.cancel()` si
cette clé est masquée pour `entity.getUuid()`. Propre, en lecture seule, peu coûteux : une
recherche dans une table par couche d'armure, par entité, par image.

`TrinketFeatureRendererMixin` ne peut pas faire pareil, parce que
`TrinketFeatureRenderer.render` itère lui-même les inventaires du composant et qu'il n'existe
aucune méthode par slot à annuler. Il fait donc autre chose : au `HEAD`, il parcourt le
`TrinketComponent`, sauvegarde chaque pile des slots masqués dans un
`ThreadLocal<Map<String, ItemStack>>` indexé `groupe/nom#index`, la remplace par
`ItemStack.EMPTY`, et au `TAIL` les remet toutes en place.

Ça marche, et la classe est honnête sur le fait que c'est une astuce dans son propre
commentaire. Le prix :

- il **mute des objets d'inventaire vivants** pendant le rendu. `TrinketInventory.setStack`
  n'est pas une opération de vue, et tout ce qui observe l'inventaire pendant un rendu en
  cours voit des slots vides.
- si quoi que ce soit entre `HEAD` et `TAIL` lève une exception, le `TAIL` ne s'exécute
  jamais et les trinkets du joueur restent absents jusqu'au prochain rendu réussi de la même
  entité — ou pas, puisque la table sauvegardée est perdue elle aussi.
- le `ThreadLocal` est `static`, donc un rendu imbriqué ou réentrant de deux entités sur le
  même thread ferait effacer par le second `HEAD` la table sauvegardée du premier. En
  pratique le rendu d'entités est séquentiel sur le thread de rendu, donc le cas n'a pas été
  observé, mais rien dans le code ne l'empêche.
- les deux injections portent `require = 0`. Si la signature de la méthode cible cesse un
  jour de correspondre, le mixin s'applique avec zéro injection et le masquage des trinkets
  s'arrête **en silence**. Aucune ligne de log, aucun crash. C'est un compromis délibéré pour
  ne pas crasher à la prochaine mise à jour de Trinkets, et c'est la raison la plus probable
  d'un futur rapport « ça ne marche plus ».

---

## Faiblesses connues

Écrites noir sur blanc parce que la prochaine personne qui touchera à ce code mérite la
liste.

**Le cache client n'est jamais vidé.** `VanityClientState.clear()` existe et n'est appelé de
nulle part. Il n'y a aucun hook de déconnexion. Quitte un serveur, rejoins-en un autre dans
la même session de jeu, et les anciennes entrées sont encore dans la table. Elles ne gênent
que si un UUID masqué sur le serveur A est aussi présent sur le serveur B, auquel cas le
joueur de B paraît partiellement invisible jusqu'à ce qu'il bascule quelque chose.
Enregistrer un `ClientPlayConnectionEvents.DISCONNECT` qui appelle `clear()` est un
correctif de deux lignes.

**Le log de débogage est en `INFO`.** `HandledScreenMixin.onKeyPressed` journalise la classe
du slot, son index, son id, la classe de son inventaire et `hasStack` à chaque appui sur `=`,
puis journalise la clé résolue, et le serveur journalise chaque bascule refusée par la configuration. Le
commentaire dit « INFO level for troubleshooting », donc c'était volontaire, mais c'est du
bruit de débogage volontaire livré aux utilisateurs.

**La touche est codée en dur.** `GLFW_KEY_EQUAL`, comparé littéralement. Pas de
`KeyBinding`, donc aucune entrée dans Options → Commandes, aucun moyen pour un joueur de la
changer et aucun moyen pour le mod d'éviter un conflit avec un autre mod qui voudrait `=`
dans les écrans de conteneur.

**Aucune autorité serveur sur ce qui est déjà stocké.** `isSlotToggleable` ne filtre que les
écritures. Les clés stockées pendant qu'un réglage était permissif restent actives après que
tu l'as resserré, et les clés ajoutées à `disabledSlots` ne démasquent rien. Il n'y a pas de
filtre au chargement et aucune commande d'administration pour corriger : le seul remède est
d'éditer le fichier de données serveur arrêté.

**`getAll()` laisse fuir des ensembles mutables.** La table est enveloppée en non modifiable,
les ensembles ne le sont pas. Le gestionnaire de connexion les recopie dans un `HashSet`
avant envoi, donc aucun appelant actuel n'en abuse, mais l'API y invite.

**Le chemin de configuration est relatif.** `Path.of("config", ...)` au lieu de
`FabricLoader.getInstance().getConfigDir()`. Correct pour un lancement normal, faux pour tout
lanceur qui fixe un autre répertoire de travail, et incohérent avec le reste du dépôt.

**La synchronisation à la connexion est en O(joueurs stockés).** À chaque connexion, le
serveur envoie un paquet par joueur ayant déjà masqué quelque chose, y compris des joueurs
hors ligne et dans une autre dimension. Avec un fichier de données ancien, cela fait quelques
centaines de petits paquets à la connexion. Ce n'est pas encore un problème ; ce n'est pas
borné non plus.

**Aucune localisation des messages joueur.** Il n'existe pas de dossier
`assets/cheesevanity/lang/`. Les deux messages de retour sont construits avec `Text.literal`
et de la concaténation de chaînes : ils sont donc en anglais seulement et ne peuvent pas être
traduits sans modifier le code — contrairement aux autres mods du dépôt, convertis à
`Text.translatable`.

---

## Points d'extension

Il n'y a aucune interface à implémenter et aucun événement auquel s'abonner ; ce mod n'a pas
été conçu pour être étendu. Les trois endroits qu'un contributeur toucherait réellement :

- **`VanityConfig.isSlotToggleable`** — toute la politique. Des règles par groupe, des
  niveaux de permission ou une autorisation par joueur ont leur place ici.
- **`HandledScreenMixin.resolveSlotKey`** — la seule traduction d'un `Slot` vers une clé. Une
  quatrième sorte de slot (un autre mod d'accessoires, une API de type curios) s'ajoute ici,
  et rien d'autre ne change, parce que tout l'aval est une chaîne.
- **un nouveau mixin de rendu** — le modèle à copier est `ArmorFeatureRendererMixin`, pas
  celui des trinkets. Annule à la tête d'une méthode de rendu par slot quand tu peux ; ne te
  rabats sur l'astuce « sauvegarde et restauration » que si la cible ne te laisse aucune
  prise.

Si tu ajoutes un vrai `KeyBinding`, enregistre-le dans `CheeseVanityClient` et lis-le depuis
le mixin au lieu de comparer `keyCode` — c'est le seul changement qui améliorerait le mod
pour tous les utilisateurs à la fois.

---

← [Qui voit quoi](visibility.fr.md) · [Dépannage](troubleshooting.fr.md) →
