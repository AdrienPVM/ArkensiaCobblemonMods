[← EggAndCheese](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](configuration.md)

# Configuration

**Il n'y a pas de fichier de configuration.** EggAndCheese n'appelle jamais
`FabricLoader.getInstance().getConfigDir()`, et rien n'apparaît dans `config/` au premier
démarrage. Si tu cherchais `config/eggandcheese.json` : arrête, il n'existe pas et le
créer n'a aucun effet.

Quatre réglages serveur existent, et ils vivent dans le même état persistant que les
collectibles eux-mêmes :

```text
world/data/eggandcheese_registry.dat
```

Ce fichier est un bloc NBT écrit par le `PersistentStateManager` de Minecraft, pas quelque
chose qu'on édite à la main. La façon prévue de changer un réglage, c'est le dashboard, ou
le point d'API qui est derrière.

## Les quatre réglages

Déclarés comme champs d'`EggRegistryData`, lus et écrits via
`GET`/`PUT /api/v1/eggs/config` :

| Clé | Type | Défaut | Ce que ça fait |
|---|---|---|---|
| `ftbQuestsEnabled` | booléen | `false` | Interrupteur général de l'intégration FTB Quests : création automatique d'une quête à la création d'un œuf, mise à jour à l'édition, suppression à la suppression, et validation à la découverte |
| `ftbQuestsChapter` | chaîne | `"oeufs"` | Nom du chapitre FTB Quests dans lequel les quêtes d'œufs sont créées |
| `ftbAutoSync` | booléen | `false` | Stocké et exposé, mais **jamais lu par aucune logique** |
| `textureRenderDistance` | entier | `20` | Distance en blocs au-delà de laquelle un client remplace l'œuf voxel complet par une simple boîte. `0` désactive la dégradation |

Les deux compteurs de la même réponse — `totalEntries` et `totalDiscoveries` — sont
dérivés, en lecture seule, et ignorés par le `PUT`.

### `ftbQuestsEnabled`

Tout ce qui est décrit dans [l'intégration FTB Quests](ftb-quests.fr.md) est conditionné à
ce drapeau *et* à `FtbQuestsHook.isAvailable()`. Drapeau coupé, le mod ne touche jamais au
fichier de quêtes même si FTB Quests est installé ; drapeau actif et FTB Quests absent,
chaque appel sort immédiatement.

L'activer ne crée pas rétroactivement les quêtes des œufs déjà existants. C'est le rôle de
`POST /api/v1/eggs/config/sync-ftb`. Il existe un second filet de sécurité : quand un
joueur découvre un œuf dont le `ftbQuestId` vaut encore `0`, le handler de découverte
appelle `ensureQuestExists` et crée la quête sur le moment.

### `ftbQuestsChapter`

Un **nom** de chapitre, pas un id — mais la recherche ne compare pas les titres.
`FtbQuestsHook` dérive un nom de fichier du réglage (en minuscules, chaque caractère hors
`a-z`, `0-9` et `_` remplacé par `_`, si bien que `Œufs de Pâques` devient
`_ufs_de_p_ques`) et cherche un chapitre dont le nom de fichier FTB lui correspond, sans
tenir compte de la casse. Quand aucun ne correspond, il en crée un avec ce nom de fichier,
intitulé avec le texte brut du réglage. Si plusieurs chapitres correspondent — ce qui est
déjà arrivé, et que le hook journalise — les doublons sont fusionnés en un seul :

```text
[EAC-FTB] Merged 2 duplicate chapters into one 'oeufs'
```

Le setter normaliserait `null` en `"oeufs"`, mais l'API n'arrive jamais jusque-là : envoyer
`{"ftbQuestsChapter": null}` fait lever une exception à `getAsString()` sur le null JSON,
et la requête échoue en `500`. Une chaîne vide, en revanche, est acceptée telle quelle et
fera chercher au hook un chapitre dont le nom de fichier est `""`.

Le défaut est français parce que le serveur pour lequel le mod a été écrit l'est. Renommer
le titre du chapitre dans l'éditeur de quêtes est sans conséquence — son nom de fichier ne
change pas. C'est modifier *ce réglage* vers un texte qui donne un autre nom de fichier qui
fait créer silencieusement au mod un second chapitre à côté du tien.

### `ftbAutoSync`

⚠️ **Réglage mort.** `isFtbAutoSync()` est appelé à un seul endroit —
`EggsApi.handleGetConfig`, pour le renvoyer. Aucun comportement ne le lit. Il persiste, il
fait l'aller-retour par l'API, et il ne fait rien. Il devait sans doute déclencher
`syncAll` automatiquement ; aujourd'hui, il faut appeler `POST /config/sync-ftb` soi-même.
Le laisser à `false` ou à `true` revient au même.

### `textureRenderDistance`

Un seuil de niveau de détail côté client, décidé par le serveur et poussé aux clients. Le
mécanisme, la surcharge par œuf et le ⚠️ sur le fait qu'il n'atteint pas les clients à la
connexion sont dans
[génération de textures et aperçus](rendering.fr.md#distance-de-rendu-et-niveau-de-détail).

Le setter borne à `>= 0` ; il n'y a pas de borne haute. Une valeur au-delà de la distance
de rendu de ton pack équivaut à `0`, puisqu'un bloc plus loin que cela n'est de toute
façon pas rendu.

⚠️ L'onglet **Configuration** du dashboard ne peut pas enregistrer `0`. Son formulaire
envoie `parseInt(valeur) || 10` : `0` — ou un champ vide — part donc en `10`, et un `0`
stocké s'affiche lui aussi `10`. L'onglet s'enregistre en outre tout seul 1,5 seconde après
chaque modification, en envoyant les quatre réglages d'un coup. Pour régler `0`, passe par
l'API.

Les changements prennent effet immédiatement sur les clients connectés, parce que
`PUT /config` envoie un `SyncRegistryPayload` à chaque joueur connecté en dernière étape.
Pas de redémarrage, pas de reconnexion.

## Réglages par collectible

Quatre champs de `CollectibleEntry` relèvent de la configuration plutôt que du contenu.
Tous les quatre s'écrivent avec `PUT /api/v1/eggs/collectibles/:id`.

| Champ | Type | Défaut | Plage | Effet |
|---|---|---|---|---|
| `scale` | flottant | `1.0` | 0,1 – 4,0, borné dans le setter | Taille visuelle de l'œuf dans le monde |
| `renderDistanceOverride` | entier | `-1` | `-1`, ou tout `>= 0` | `-1` signifie « utiliser `textureRenderDistance` » ; toute autre valeur est le seuil propre à cet œuf, en blocs |
| `title` | chaîne | `""` | — | Le titre de la quête FTB. **Pas** affiché dans le message de découverte |
| `subtitle` | chaîne | `""` | — | La description de la quête FTB |

`scale` est borné en silence : `PUT {"scale": 10}` renvoie `200` avec `scale: 4.0`. Relis
toujours la valeur.

Un `renderDistanceOverride` à `0` n'est pas la même chose que `-1`. `0` signifie « ne
jamais dégrader cet œuf », ce que tu veux pour un œuf-vedette visible d'un bout à l'autre
d'une place. `-1` signifie « suivre le réglage global ».

`title` et `subtitle` n'ont aucun effet quand l'intégration FTB Quests est coupée. Ils sont
stockés, renvoyés par l'API, et inertes par ailleurs. En particulier, renseigner un titre
ne change pas ce qu'un joueur lit en trouvant l'œuf — ce message utilise l'id. Voir
[placement et découvertes](placement-and-discovery.fr.md#la-découverte).

## Côté client

Une seule valeur client existe, et elle n'est pas réglable par le joueur :

```java
// src/client/java/com/roicheese/eggandcheese/renderer/EggRenderConfig.java
public class EggRenderConfig {
    public static int textureRenderDistance = 20;
}
```

Elle est écrasée à chaque arrivée d'un `SyncRegistryPayload`. Il n'y a pas de fichier de
configuration client, pas d'écran mod-menu, pas de raccourci. Un joueur pour qui les œufs
coûtent cher à rendre ne peut pas les baisser ; c'est à l'opérateur du serveur de le
faire.

## Exemple complet

Il n'y a pas de fichier à copier : l'équivalent d'un exemple de configuration annoté est
donc la requête qui en écrit un. État complet, chaque clé renseignée explicitement :

```bash
# Remplace <token> par le token de session renvoyé par POST /api/v1/auth/login
curl -X PUT http://localhost:44311/api/v1/eggs/config \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "ftbQuestsEnabled": true,
    "ftbQuestsChapter": "oeufs",
    "ftbAutoSync": false,
    "textureRenderDistance": 32
  }'
```

Annoté, clé par clé :

```json
{
  "ftbQuestsEnabled": true,
  "ftbQuestsChapter": "oeufs",
  "ftbAutoSync": false,
  "textureRenderDistance": 32
}
```

- `ftbQuestsEnabled: true` — les quêtes sont créées, mises à jour, supprimées et validées
  en même temps que les œufs. Exige que FTB Quests soit installé ; sans effet s'il ne
  l'est pas.
- `ftbQuestsChapter: "oeufs"` — désigne le chapitre dont le nom de fichier est `oeufs`.
  Fais-lui produire le nom de fichier de ton chapitre existant, sinon un nouveau chapitre
  sera créé.
- `ftbAutoSync: false` — sans effet dans un sens comme dans l'autre. Laissé dans l'exemple
  parce que l'API l'accepte et que le dashboard l'affiche.
- `textureRenderDistance: 32` — les œufs gardent leur modèle voxel complet jusqu'à 32
  blocs, puis dégradent en boîte. Augmente-le pour une zone de présentation, baisse-le si
  des clients signalent des chutes de framerate dans un champ d'œufs dense.

La réponse reprend la forme de la configuration plus les deux compteurs, ce qui permet de
confirmer ce qui a réellement été stocké :

```json
{
  "ftbQuestsEnabled": true,
  "ftbQuestsChapter": "oeufs",
  "ftbAutoSync": false,
  "textureRenderDistance": 32,
  "totalEntries": 56,
  "totalDiscoveries": 318
}
```

## Comportement au rechargement

Aucun réglage n'exige un redémarrage. Aucun n'exige non plus une commande de rechargement
— `/eac reload`, malgré son nom, ne recharge rien, comme
[les commandes](commands.fr.md#eac-reload) l'expliquent.

Un `PUT` est appliqué sur le thread serveur, marque l'état comme modifié, et prend effet à
la prochaine action qui le lit. La seule valeur propagée aux clients est
`textureRenderDistance`, et cela se produit dans la même requête.

L'état est écrit sur disque au cycle de sauvegarde de Minecraft. Un `PUT` suivi
immédiatement d'un arrêt brutal du processus perd la modification.

---

← [Commandes](commands.fr.md) · [L'ADN d'un collectible](dna.fr.md) →
