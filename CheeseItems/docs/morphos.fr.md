[← CheeseItems](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](morphos.md)

# Morphos

Une morpho est une apparence de Pokémon qu'un joueur peut débloquer puis porter. Le
sous-système partage le jar avec les items personnalisés et presque rien d'autre : fichiers
de configuration différents, module dashboard différent, chemin de code différent. Le seul
lien est le champ `useActions` d'un item, par lequel un item de quête ou un achat en
boutique finit par attribuer une morpho.

Trois objets, trois emplacements de stockage :

| Objet | Possède | Fichier |
|---|---|---|
| Entrée de catalogue (`MorphoEntry`) | ce qu'*est* une morpho : espèce, forme, shiny, titre, prix, disponibilité, commandes | `config/cheeseitems_morphos.json` |
| Entrée de codex (`MorphoCodexEntry`) | le fait qu'un joueur possède une morpho, et depuis quand | `config/cheeseitems_morpho.json` |
| Réserve d'endurance (`MorphoEnduranceTracker.State`) | combien de temps de transformation il reste à un joueur | mémoire seulement |

Le catalogue est la source de vérité unique. Une entrée de codex conserve une copie de
l'espèce, de la forme et de l'option shiny au moment de l'attribution, mais l'interface, la
transformation et l'endurance lisent toutes le catalogue : modifier une entrée dans le
dashboard la change donc pour tous ceux qui la possèdent, sans redémarrage.

## <a id="lentrée-de-catalogue"></a>L'entrée de catalogue

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `id` | chaîne | 8 caractères hexadécimaux, générés | l'identité. Référencée par les entrées de codex et par `/cheeseitem morpho grant` |
| `title` | chaîne | `""` | le nom affiché dans l'interface et la boutique. Vide retombe sur `species` |
| `description` | chaîne | `""` | affichée par le site de la boutique |
| `species` | chaîne | `""` | passée à `cshift transform`. **Vide rend la morpho inutilisable** |
| `form` | chaîne | `""` | argument de forme optionnel pour `cshift` |
| `shiny` | booléen | `false` | ajoute `shiny` à la commande `cshift` |
| `hasImage` | booléen | `false` | maintenu par le mod à l'envoi ou à la suppression d'une image. Ne le règle pas à la main |
| `priceCredits` | entier | `0` | prix sur le site, en crédits CheeseStore. Borné à ≥ 0 |
| `priceGold` | entier | `0` | prix dans la boutique PNJ. Borné à ≥ 0 |
| `availableOnWebsite` | booléen | `false` | inclut l'entrée dans `getSortedForWebsite()` |
| `availableInNpcShop` | booléen | `false` | inclut l'entrée dans `getSortedForNpcShop()` |
| `isNew` | booléen | `false` | épingle l'entrée en haut de chaque liste triée |
| `sortOrder` | entier | `0` → ajoutée à la fin | position parmi les entrées non récentes, par ordre croissant |
| `createdAt` | millisecondes epoch | maintenant | départage, et sert de clé de tri parmi les entrées `isNew` |
| `terrestrial`, `aquatic`, `aerial` | booléen | `false` | badges d'habitat. Purement cosmétiques |
| `legendary` | booléen | `false` | un badge étoile. Purement cosmétique |
| `commandsOnTransform` | tableau de chaînes | `[]` | commandes supplémentaires lancées après la transformation |
| `commandsOnRevert` | tableau de chaînes | `[]` | commandes supplémentaires lancées après l'annulation |
| `useCustomEndurance` | booléen | `false` | si vrai, les trois clés `custom*` remplacent les valeurs globales |
| `customCapacity` | entier | `100` | borné à ≥ 1 |
| `customDrainPerSecond` | flottant | `2.0` | borné à ≥ 0 |
| `customRegenPerSecond` | flottant | `1.0` | borné à ≥ 0 |

`availableOnWebsite` et `availableInNpcShop` sont des filtres que le mod calcule mais ne
consomme jamais lui-même. Les deux accesseurs triés existent pour le site de la boutique et
pour le PNJ Métamorph, qui sont hors de ce mod. Les activer ne change rien au Codex en jeu :
un joueur voit toutes les morphos qu'il possède, disponibles ou non.

⚠️ Fixe un prix sur chaque morpho. Les deux prix valent `0` par défaut, et les drapeaux de
disponibilité ne font que masquer une entrée d'une liste ; ils n'en empêchent pas l'achat.

Le tri, partout où une liste triée est produite : les entrées `isNew` d'abord, la plus
récente `createdAt` en tête parmi elles ; puis le reste par `sortOrder` croissant, avec
`createdAt` croissant pour départager. `POST /morphos/reorder` renumérote `sortOrder` à
partir de 1 dans l'ordre des identifiants que tu envoies — ceux qu'il ne reconnaît pas sont
ignorés sans erreur.

Le fichier sur le disque est écrit dans un autre ordre (par `sortOrder`, puis `createdAt`),
uniquement pour rester lisible dans un éditeur de texte.

### Images

Un PNG envoyé atterrit dans `config/cheeseitems_assets/morphos/<id>.png` et met `hasImage`
à vrai sur l'entrée. Une miniature de 480 px maximum est générée à la première demande et
mise en cache ; envoyer ou supprimer l'original l'invalide. Les deux sont servies sans
authentification — [les deux routes publiques](dashboard.fr.md#routes-publiques-des-morphos).

## <a id="le-codex-par-joueur"></a>Le Codex par joueur

`config/cheeseitems_morpho.json` associe un UUID de joueur à la liste des morphos qu'il
possède, plus l'identifiant de celle qui est active :

```json
{
  "players": {
    "f8a2b1c4-0000-4000-8000-000000000001": {
      "entries": [
        { "itemId": "a3f91c2e", "species": "ditto", "form": "", "shiny": false, "acquiredAt": 1761700000000 }
      ],
      "active": "a3f91c2e"
    }
  }
}
```

Le champ s'appelle `itemId` plutôt que `morphoId` par compatibilité avec la première version
du sous-système, où une morpho était un item personnalisé. Plus rien ne le lit comme un
identifiant d'item.

La première morpho qu'un joueur reçoit devient automatiquement sa morpho active
(`putIfAbsent`). Ensuite, la morpho active ne change que lorsque le joueur en clique une
dans l'interface. Définir comme active une morpho que le joueur ne possède pas est ignoré en
silence.

Les écritures sont différées : une modification marque le codex comme sale et enregistre
immédiatement si la dernière sauvegarde a plus de cinq secondes, sinon la sauvegarde attend
la modification suivante. `SERVER_STOPPING` force l'écriture. ⚠️ Un crash dans les cinq
secondes suivant une attribution peut perdre cette attribution, car rien ne réenregistre sur
minuterie — le différé ne se déclenche qu'à la *prochaine* écriture.

### Le nettoyage des orphelins

Chaque synchronisation retire les entrées de codex dont l'identifiant de catalogue n'existe
plus, et écrit une ligne par retrait :

```text
[CheeseItems] Pruned orphan morpho 'a3f91c2e' from Steve's codex (no catalog entry)
```

Supprimer une entrée du catalogue depuis le dashboard la retire donc à tous les joueurs qui
la possédaient, immédiatement pour les joueurs connectés et à la prochaine connexion pour
les autres. Il n'y a pas d'annulation : recréer l'entrée produit un nouvel identifiant.

## Attribuer une morpho

Quatre chemins, tous aboutissant à `MorphoCodex.addToCodex` :

| Chemin | Qui l'utilise |
|---|---|
| `/cheeseitem morpho grant <player> <morpho_id>` | un opérateur, en jeu. Joueurs connectés seulement |
| `POST /api/v1/morphos/grant` | le dashboard, et la livraison de la boutique. Fonctionne hors ligne via le cache utilisateurs |
| Le champ `useActions` d'un item lançant la commande ci-dessus | une récompense de quête ou un jeton échangeable |
| `MorphoManager.addToCodex(player, entry)` | n'importe quel mod voisin détenant l'instance |

Une attribution en doublon est refusée : la commande répond
`<name> possède déjà cette morpho.` et l'API répond `409`.

## Porter une morpho

La transformation n'est pas implémentée dans ce mod. `MorphoManager` construit une chaîne de
commande et la confie au dispatcher du serveur :

```text
cshift transform <player> <species> [form] [shiny]
```

puis lance chaque entrée de `commandsOnTransform`, avec `{player}` remplacé par le nom du
joueur, puis démarre la réserve d'endurance. L'annulation lance `cshift revert <player>`,
puis `commandsOnRevert`, puis arrête le drain.

⚠️ Ceci est une observation de la façon dont les deux mods s'emboîtent actuellement, pas un
contrat. [CobbleShift](../../CobbleShift/) n'est pas déclaré comme dépendance et n'est pas
appelé via une API — si `cshift` change l'ordre de ses arguments ou son nom, toutes les
morphos cessent de transformer et la seule trace est l'erreur du dispatcher lui-même, que la
source console écrit dans le log du serveur — pour une commande qui n'existe pas :

```text
Unknown or incomplete command, see below for error
```

suivi de la commande recopiée jusqu'à un repère `<--[HERE]`.

`MorphoManager` a aussi un avertissement `[CheeseItems] cshift command failed: …`, mais il
ne se déclenche que si `executeWithPrefix` lève une exception, et le dispatcher vanilla
attrape lui-même les erreurs de syntaxe et d'exécution pour les renvoyer à la source de la
commande.

Un `cshift` en échec n'arrête pas la suite : les commandes supplémentaires sont lancées, la
réserve d'endurance se vide quand même, et le joueur reçoit quand même *Transformation :
Ditto*. Le mod n'a aucun moyen de savoir si la transformation a réellement eu lieu.

### La bascule

`MorphoManager` tient une table de qui est actuellement transformé, et c'est ce qui fait de
`H` une bascule : la première pression transforme, la seconde annule. Cette table est **en
mémoire seulement**. Un redémarrage pendant qu'un joueur est transformé le laisse transformé
du point de vue de CobbleShift, alors que CheeseItems croit qu'il ne l'est pas — sa
prochaine pression sur `H` le transforme de nouveau au lieu de l'annuler. `cshift revert` à
la main est la sortie de secours.

Presser `H` sans morpho active envoie *Aucune morpho active. Ouvrez le Codex avec [J].* Une
morpho active dont l'entrée de catalogue a une `species` vide envoie *La morpho active est
invalide (item supprimé ?).*

## La réserve d'endurance

Une réserve par joueur, pas par morpho. La capacité et les débits sont résolus au moment de
la transformation depuis l'entrée : ses valeurs `custom*` quand `useCustomEndurance` est
vrai, le bloc `globalEndurance` du catalogue sinon.

```json
"globalEndurance": { "capacity": 100, "drainPerSecond": 2.0, "regenPerSecond": 1.0 }
```

Avec ces défauts, un joueur dispose de 50 secondes de transformation et a besoin de 100
secondes sous forme humaine pour se recharger. La réserve avance à chaque tick serveur :
`drainPerSecond / 20` retiré par tick pendant la transformation, `regenPerSecond / 20`
ajouté par tick sinon.

À zéro, la réserve arrête de se vider et `forceRevert` exécute le chemin d'annulation
complet, `commandsOnRevert` incluses. La régénération commence dès le tick suivant, et
`start()` ne refuse que lorsque la réserve vaut exactement zéro — donc presser `H` de nouveau
un instant plus tard est accepté, et la transformation ne dure que le temps des quelques
points déjà régénérés avant de s'annuler d'elle-même. Il n'y a aucun blocage jusqu'à ce que
la réserve soit pleine.

Le refus, *Endurance épuisée !*, ne peut toucher qu'une pression traitée pendant l'unique
tick entre l'épuisement et la première étape de régénération. Vois ce que fait le code dans
ce cas : la commande `cshift transform` **a déjà été exécutée** au moment où le tracker
refuse, parce que le refus a lieu dans `start()`, après que `doTransform` a lancé la
commande, et `doTransform` inscrit quand même le joueur comme transformé et envoie
*Transformation : …*. Le joueur se retrouve transformé avec `draining = false` : rien ne
l'annule automatiquement et la transformation n'a plus de limite de temps jusqu'à sa
prochaine pression sur `H`. C'est un vrai bug ; l'autre issue est `/cshift revert <player>`.

Réduire `capacity` pendant que quelqu'un est transformé ramène sa valeur courante à la
nouvelle borne à la transformation suivante, pas immédiatement. L'augmenter prend effet à la
transformation suivante aussi.

La réserve est dessinée dans la barre d'action quatre fois par seconde tant qu'elle se vide
ou se remplit, et cesse de l'être une fois pleine. L'état est alors retiré de la mémoire, et
l'est aussi à la déconnexion. Rien de l'endurance n'est persisté : une reconnexion rend au
joueur une réserve pleine.

## L'interface du Codex en jeu

La moitié cliente du jar ajoute l'écran et deux raccourcis, tous deux dans la catégorie
`CheeseItems` et tous deux réassignables :

| Raccourci | Défaut | Action |
|---|---|---|
| Ouvrir le Codex de Morpho | `J` | ouvre l'écran |
| Activer/désactiver morpho | `H` | envoie le paquet de bascule au serveur |

`/morpho` ouvre le même écran en demandant au client de le faire, ce qui est pratique pour
un dialogue de PNJ. L'écran ne s'ouvre que si aucun autre écran n'est ouvert.

L'écran dessine ce que la dernière synchronisation a envoyé — il n'interroge jamais le
serveur de lui-même. Cliquer une entrée envoie `MorphoSetActiveC2SPayload`, qui définit la
morpho active et déclenche une nouvelle synchronisation. Les images sont récupérées en HTTP
depuis `/morphos/api/thumb/<id>` sur l'hôte auquel le joueur s'est connecté, en utilisant le
port CheeseDash que la synchronisation a communiqué au client, et mises en cache comme
textures jusqu'à la prochaine synchronisation.

Un joueur dont le client n'a pas le jar ne voit rien de tout cela et ne peut rien activer,
puisque la bascule est un paquet envoyé par le client. Il peut toujours posséder des
morphos, et un opérateur peut toujours le transformer directement avec `cshift`.

### Ce que contient une synchronisation

Envoyée à la connexion (un tick après l'événement de connexion), après chaque attribution,
après chaque `setActive`, et à tous les joueurs connectés après la suppression d'une entrée du
catalogue :

| Champ | Source |
|---|---|
| `active` | le codex, `""` quand il n'y en a pas |
| `cheesedashPort` | la configuration de CheeseDash, pour que le client sache où chercher les images |
| `balance` | le portefeuille de CheeseStore par réflexion, `-1` quand CheeseStore est absent |
| `currencyName` | la configuration de CheeseStore, omis quand inconnue |
| `entries[]` | un objet par morpho possédée : `itemId`, `species`, `form`, `shiny`, `displayName`, `hasImage`, `legendary`, `terrestrial`, `aquatic`, `aerial` |

`displayName` vient du titre du **catalogue**, avec repli sur l'espèce. C'est pourquoi un
renommage dans le dashboard apparaît dans l'interface de chaque joueur sans redémarrage.

CheeseStore est atteint par `Class.forName("com.cheesestore.WalletManager")` et compagnie.
Il n'est pas déclaré du tout dans `fabric.mod.json` — pas même en `suggests`, malgré le
commentaire du hook qui affirme le contraire. Quand il manque, la ligne de solde est masquée
et rien n'est journalisé : le hook vérifie d'abord `isModLoaded("cheesestore")` et sort sans
bruit. Ses deux lignes de log n'apparaissent que si CheeseStore *est* chargé —
`CheeseStore detected — codex balance display enabled` en cas de succès, ou celle-ci si la
réflexion échoue :

```text
[CheeseItems] CheeseStore not available: com.cheesestore.WalletManager
```

---

← [Resource pack](resource-pack.fr.md) · [API dashboard](dashboard.fr.md) →
