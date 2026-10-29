[← CheeseItems](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](configuration.md)

# Configuration

CheeseItems n'a pas de fichier de réglages au sens habituel. Il a trois documents JSON qui
sont des *données* — définitions d'items, catalogue de morphos, morphos possédées par les
joueurs — et deux clés de configuration réelle enfouies dans le premier. Tout vit sous le
dossier `config/` du serveur.

| Chemin | Écrit par | Contenu |
|---|---|---|
| `config/cheeseitems.json` | le mod, à chaque opération CRUD | définitions d'items, table `customModelData`, `resourcePackHost` |
| `config/cheeseitems_morphos.json` | le mod, en différé | le catalogue de morphos et les valeurs d'endurance globales |
| `config/cheeseitems_morpho.json` | le mod, en différé | les morphos par joueur. Pas destiné à être édité |
| `config/cheeseitems_assets/<id>.png` | l'envoi d'icône | une icône d'item personnalisée par définition |
| `config/cheeseitems_assets/bar/0.png` … `10.png` | toi, à la main | les images de la police de la barre d'endurance |
| `config/cheeseitems_assets/morphos/<id>.png` | l'envoi d'image de catalogue | une image de morpho par entrée du catalogue |

Les trois fichiers JSON sont créés au premier démarrage s'ils manquent — vides, sans
contenu d'exemple. `config/cheeseitems.json` sort ainsi :

```json
{
  "items": [],
  "customModelData": {},
  "resourcePackHost": ""
}
```

## cheeseitems.json

### Clés de premier niveau

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `items` | tableau de définitions | `[]` | les items personnalisés. Une page leur est consacrée : [items personnalisés](items.fr.md) |
| `customModelData` | objet, id → entier | `{}` | quel numéro de `CustomModelData` utilise l'icône de chaque item |
| `resourcePackHost` | chaîne | `""` | le `hôte:port` depuis lequel le client doit télécharger le resource pack |

`customModelData` est de la comptabilité, pas de la configuration. Un numéro est attribué
la première fois qu'une icône est envoyée pour un item, puis réutilisé indéfiniment, afin
qu'un client ayant le pack en cache ne voie pas toutes les icônes se décaler quand un
nouvel item apparaît. Le prochain numéro à distribuer est déduit au chargement comme
`max(existants) + 1`. Modifier ces numéros à la main casse le lien entre une pile déjà dans
le monde et son icône — n'y touche pas.

`resourcePackHost` est la seule clé que tu dois régler toi-même.

### resourcePackHost

Le mod construit l'URL de téléchargement ainsi :

```text
http://<resourcePackHost>/cheeseitems/api/pack.zip
```

La valeur est donc un hôte et un port, sans schéma et sans chemin :
`play.example.net:44311`. Elle doit être joignable **depuis la machine d'un joueur**, pas
depuis le serveur.

Quand la clé est vide, le mod résout un repli dans cet ordre :

1. Le port configuré de CheeseDash, sous la forme `localhost:<port>` — et il écrit qu'il l'a
   fait :

   ```text
   [CheeseItems] resourcePackHost auto-resolved to localhost:44311 (set resourcePackHost in cheeseitems.json for production)
   ```

2. Si CheeseDash n'est pas lisible, le littéral `localhost:44311`.

⚠️ **`localhost` est un défaut qui marche pour un test en solo et un échec silencieux en
production.** Chaque client distant résout `localhost` vers lui-même, ne trouve rien sur
44311, et renonce au pack. Le joueur voit les icônes vanilla et aucune erreur. Si les
icônes personnalisées font partie de ton projet, règle cette clé dès le premier jour.

Note que le schéma est codé en dur sur `http`. Il n'y a aucun moyen de servir le pack en
HTTPS sans un proxy inverse qui termine le TLS sur un autre nom d'hôte, et le client ne
refusera un pack en `http` que si le launcher a été configuré pour — le vanilla l'accepte.

Changer `resourcePackHost` demande un redémarrage ou un `/cheeseitem reload` : la valeur est
lue à chaque envoi du pack, mais le fichier n'est relu qu'au rechargement.

## cheeseitems_morphos.json

Deux clés de premier niveau :

```json
{
  "entries": [ … ],
  "globalEndurance": {
    "capacity": 100,
    "drainPerSecond": 2.0,
    "regenPerSecond": 1.0
  }
}
```

`entries` est le catalogue, documenté champ par champ dans
[morphos](morphos.fr.md#lentrée-de-catalogue).

### globalEndurance

| Clé | Type | Défaut | Borne | Effet |
|---|---|---|---|---|
| `capacity` | entier | `100` | ≥ 1 | taille de la réserve pour chaque morpho qui ne la redéfinit pas |
| `drainPerSecond` | flottant | `2.0` | ≥ 0 | points dépensés par seconde pendant la transformation |
| `regenPerSecond` | flottant | `1.0` | ≥ 0 | points récupérés par seconde hors transformation |

Les nombres sont des points abstraits ; seul leur rapport compte. Avec les défauts, une
transformation dure `100 / 2 = 50` secondes et se recharge en `100 / 1 = 100` secondes.

`drainPerSecond: 0` donne un temps de transformation illimité — la réserve n'atteint jamais
zéro, donc rien ne s'annule automatiquement et la barre n'est plus dessinée une fois la
réserve pleine. C'est la façon prévue de désactiver l'endurance. `regenPerSecond: 0` est le
piège inverse : dès qu'un joueur a vidé la réserve, elle ne se remplit plus pendant sa
session, et le seul reset est une reconnexion, puisque l'état n'existe qu'en mémoire.

Une entrée de catalogue avec `useCustomEndurance: true` ignore ces trois clés au profit de
ses propres `customCapacity`, `customDrainPerSecond` et `customRegenPerSecond`.

Ces valeurs sont résolues au moment où un joueur se transforme. Les modifier depuis le
dashboard (`PUT /api/v1/morphos/global`) prend effet à la transformation suivante, pas sur
une transformation déjà en cours.

## cheeseitems_morpho.json

La possession par joueur. La forme est décrite dans
[morphos](morphos.fr.md#le-codex-par-joueur). L'éditer à la main est possible mais
malcommode : le fichier est indexé par UUID, les écritures sont différées, et le serveur en
marche écrasera ta modification depuis sa mémoire à sa prochaine sauvegarde. Attribue les
morphos avec la commande ou l'API, et si tu dois vraiment éditer le fichier, arrête le
serveur d'abord.

## Sémantique du rechargement

| Quoi | Pris en compte par | Redémarrage nécessaire ? |
|---|---|---|
| `items` dans `cheeseitems.json` | `/cheeseitem reload`, `POST /api/v1/items/reload` | non |
| `customModelData` | le même rechargement | non, mais ne l'édite pas à la main |
| `resourcePackHost` | le même rechargement | non |
| `cheeseitems_morphos.json` édité à la main | rien — le catalogue ne se charge qu'une fois | **oui** |
| `globalEndurance` modifié via l'API | immédiatement, pour la transformation suivante | non |
| `cheeseitems_morpho.json` édité à la main | rien | **oui**, et arrête le serveur d'abord |
| PNG de la barre ajoutés ou modifiés | la prochaine régénération du pack, donc un envoi d'icône ou un redémarrage | oui, en pratique |

⚠️ Il n'existe aucun rechargement pour le catalogue de morphos. `MorphoCatalog.load()` est
appelé une seule fois, quand le singleton est touché pour la première fois à
l'initialisation du mod, et rien ne le rappelle. Éditer `cheeseitems_morphos.json` à la main
sur un serveur en marche sera **écrasé** par la prochaine sauvegarde différée. Édite le
catalogue depuis le dashboard, ou arrête le serveur.

## Un exemple complet annoté

```json
{
  // Définitions d'items. Chacune est décrite dans docs/items.fr.md.
  "items": [
    {
      "id": "quest_key",
      "displayName": "Rusty Quest Key",
      "nameColor": "#F5A623",
      "baseItem": "minecraft:tripwire_hook",
      "lore": ["Opens something, somewhere."],
      "loreColor": "#9CA3AF",
      "glowing": true,
      "dyeColor": -1,
      "enabled": true,
      "maxStackSize": 1,
      "droppable": true,
      "tradeable": true,
      "placeable": false,
      "consumable": true,
      "soulbound": false,
      "disappearOnDeath": false,
      "unbreakable": true,
      "maxUses": 0,
      "cooldownTicks": 100,
      "onUseEffects": [],
      "passiveEffects": [],
      "onPickupEffects": [],
      "useActions": [
        {
          "command": "function arkensia:open_vault",
          "count": 1,
          "feedback": "La serrure cède.",
          "showTimer": false
        }
      ],
      "useConditions": {
        "dimension": "minecraft:overworld",
        "minLevel": 0,
        "globalCooldownTicks": 0
      }
    }
  ],

  // Attribué par le mod à l'envoi d'une icône. Ne pas éditer.
  "customModelData": {
    "quest_key": 1
  },

  // hôte:port qu'un client de joueur peut atteindre. Sans schéma, sans chemin.
  "resourcePackHost": "play.example.net:44311"
}
```

⚠️ Les commentaires ci-dessus sont là pour la lecture seulement. Le fichier est analysé par
Gson en mode strict via `JsonParser.parseString`, qui rejette les commentaires `//` — une
config commentée ne se charge pas, écrit
`[CheeseItems] Failed to load config: …` et laisse le mod avec **zéro item**, en silence,
comme si tu les avais tous supprimés. Copie l'exemple sans les lignes de commentaire.

Ce mode d'échec mérite un second regard : une erreur d'analyse est attrapée, écrite au
niveau `ERROR`, puis ignorée. La liste d'items reste vide et la sauvegarde suivante écrit
cette liste vide par-dessus ton fichier. Garde une copie de `cheeseitems.json` avant de
l'éditer à la main, et relis le log après un rechargement.

---

← [Commandes](commands.fr.md) · [Items personnalisés](items.fr.md) →
