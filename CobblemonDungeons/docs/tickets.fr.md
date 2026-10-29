[← CobblemonDungeons](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](tickets.md)

# Les tickets

Un ticket de donjon est le droit d'entrée. Un seul item,
`cobblemon_dungeons:dungeon_ticket`, portant deux données personnalisées qui décident de ce
qu'il ouvre :

| Clé NBT | Type | Signification |
|---|---|---|
| `DungeonId` | string | Le `general.id` du donjon auquel ce ticket donne accès |
| `Tier` | int | Le tier qu'il vaut |

Les deux vivent sous `DataComponentTypes.CUSTOM_DATA`. Le nom affiché vient de
`DataComponentTypes.CUSTOM_NAME`, et le tier est aussi écrit dans
`CUSTOM_MODEL_DATA`, ce qui donne aux tiers 1 à 5 leurs propres textures
(`dungeon_ticket_1.json` à `dungeon_ticket_5.json`).

L'infobulle affiche les deux valeurs, relues depuis le NBT :

```text
§6Copper Dungeon Ticket
Donjon : generic_dungeon
Tier : 1
```

---

## Où se définissent les modèles de tickets

`config/cobblemon_dungeons/dungeon_tickets.json`. Un seul tableau, `tickets`, de modèles :

```json
{
  "tickets": [
    {
      "dungeonId": "generic_dungeon",
      "itemName": "§6Copper Dungeon Ticket",
      "tier": 1
    }
  ]
}
```

| Clé | Type | Défaut | Effet |
|---|---|---|---|
| `dungeonId` | string | `"generic_dungeon"` | Écrit dans le `DungeonId` du ticket |
| `itemName` | string | `"§6Copper Dungeon Ticket"` | Nom affiché, et poignée par laquelle les tables de butin désignent ce ticket |
| `tier` | int | `1` | Écrit dans `Tier` et dans `CUSTOM_MODEL_DATA` |

Le fichier est créé au premier démarrage avec exactement cette unique entrée, et le même
repli s'applique chaque fois que la liste serait enregistrée vide.

`itemName` n'est pas cosmétique. C'est l'identifiant par lequel un pool de butin distribue
un ticket : le changer casse toutes les entrées de butin qui référençaient l'ancien nom.

## Comment fonctionne le guichet

`consumeDungeonTicket` parcourt tout l'inventaire du chef à la recherche d'une pile de
`dungeon_ticket` dont le `DungeonId` égale l'identifiant du donjon **et** dont le `Tier` est
supérieur ou égal au tier demandé. La première correspondance est décrémentée de un.

Deux conséquences du `tier >= requiredTier` :

- Un ticket Tier 5 ouvre une partie Tier 1. Les tiers sont un plancher, pas une égalité.
- C'est la première pile correspondante dans l'ordre de l'inventaire qui est dépensée, ce
  qui peut être ton ticket Tier 5 alors qu'un Tier 1 aurait suffi. Il n'y a aucune logique
  du « moins cher d'abord ».

Pas de correspondance, pas d'entrée : *Vous avez besoin d'un Ticket de Donjon valide pour
<nom> (Tier N) pour entrer.*

Seul le **chef** de groupe est débité. Tous les autres entrent sur son ticket.

## Obtenir des tickets

Trois voies existent dans le code.

### 1. Le butin des coffres de donjon

Une entrée de pool de butin peut nommer un ticket dans son champ `item`. Le résolveur essaie
d'abord la chaîne comme identifiant d'item ; quand cela donne `minecraft:air`, il retombe
sur une comparaison de la chaîne avec chaque `itemName` configuré, en testant la forme brute
et la forme où `§` est remplacé par `&`. Une correspondance construit un vrai ticket à
partir de ce modèle.

```json
{ "item": "§6Copper Dungeon Ticket", "min": 1, "max": 1, "weight": 5 }
```

`§6Copper Dungeon Ticket` comme `&6Copper Dungeon Ticket` fonctionnent. Une chaîne qui ne
correspond ni à un identifiant d'item ni à un nom de ticket journalise
`Unknown loot item ID or ticket name: …` et ne produit rien.

### 2. L'interface d'échange de tickets

L'écran de donjon possède une vue **Échange de tickets** avec quatre conversions fixes :

| Échange | Coût |
|---|---|
| Tier 1 → Tier 2 | 5 × Tier 1 |
| Tier 2 → Tier 3 | 3 × Tier 2 |
| Tier 3 → Tier 4 | 3 × Tier 3 |
| Tier 4 → Tier 5 | 3 × Tier 4 |

Les taux sont codés en dur dans `DungeonScreenHandler` et ne sont pas configurables.

Un échange compte les tickets **par tier seulement**, en ignorant `DungeonId` : cinq
tickets Tier 1 pour cinq donjons différents constituent un paiement valide. Le ticket
obtenu est construit à partir du premier modèle de `dungeon_tickets.json` dont le `tier`
correspond à la cible, donc le donjon vers lequel pointe le nouveau ticket est celui du
modèle qui se trouve en premier dans le fichier.

Échecs :

| Message | Cause |
|---|---|
| *Pas assez de tickets Tier N ! Il en faut M.* | Moins que le nombre requis de tickets du tier source dans l'inventaire |
| *Aucun modèle de ticket Tier N trouvé dans la config !* | Aucun modèle de `dungeon_tickets.json` ne porte le tier cible |

En cas de succès : *Échange de Nx tickets Tier A contre 1x ticket Tier B !*

### 3. L'effet `DUNGEON_TICKET_GIVE` de CheeseGuard

Pendant un événement CheeseGuard, l'effet de timeline `DUNGEON_TICKET_GIVE` distribue des
tickets à chaque participant. Il ne lit pas `dungeon_tickets.json` : il construit un modèle
à la volée avec le `dungeonId` et le `tier` de l'effet et le nom
`§6Dungeon Ticket [<dungeonId> T<tier>]`. Les tickets créés ainsi ont donc une autre
apparence que ceux que tu as configurés, même lorsqu'ils ouvrent le même donjon. Détails
dans [la page dashboard](dashboard.fr.md#effets-dévénement-cheeseguard).

Il n'existe aucune commande de type `/give` pour les tickets. Un admin qui en a besoin lance
un effet d'événement CheeseGuard, ou distribue l'item avec le `/give` vanilla accompagné des
bonnes données personnalisées.

## Synchronisation côté client

La configuration des tickets est poussée à chaque client à la connexion sous forme de
`DungeonTicketSyncPayload` contenant tout le JSON, et le client écrase sa copie locale. Le
client en a besoin parce que l'interface d'échange affiche les noms et les tiers localement.

Cette synchronisation n'a lieu **qu'à la connexion**. Modifier les tickets depuis le
dashboard pendant que des joueurs sont en ligne met à jour le serveur et le fichier, mais
les clients connectés gardent l'ancienne liste jusqu'à leur reconnexion.

---

← [Les instances et leur cycle de vie](instances.fr.md) · [Les dresseurs et RCTApi](trainers.fr.md) →
