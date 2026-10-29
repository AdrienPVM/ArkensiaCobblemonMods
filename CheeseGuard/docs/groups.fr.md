[← CheeseGuard](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](groups.md)

# Groupes

Un groupe est un paquet nommé de drapeaux et de conditions d'entrée que partage un
ensemble de zones. Il existe pour que vingt parcelles autour du spawn puissent toutes
refuser `pvp` sans que tu édites vingt zones, et pour que changer d'avis signifie éditer un
seul objet.

La classe est `com.cheeseguard.data.RegionGroup`. Elle vit dans le même fichier que les
zones, `config/cheeseguard/regions.json`, sous une clé `groups`.

---

## Ce qu'un groupe porte, et ce qu'il ne porte pas

| Champ | Type | Rôle |
|---|---|---|
| `id` | chaîne | Identifiant, dérivé du nom. La clé partout |
| `name` | chaîne | Libellé d'affichage, texte libre |
| `regionIds` | liste de chaînes | Les zones qui appartiennent au groupe |
| `flags` | carte à trois états | Valeurs de drapeaux héritées par les zones membres |
| `requiredAdvancementsEntry` / `Exit` | liste de chaînes | Conditions d'advancement, fusionnées avec celles de chaque zone |
| `requiredQuestsEntry` / `Exit` | liste de chaînes | Conditions FTB Quests, même fusion |

C'est tout l'objet. Un groupe n'a **aucune** limite géométrique, pas de membres, pas de
propriétaire, pas de priorité, pas de messages, pas de tags, pas de configuration de vente
et pas de déclencheurs de commandes. Ce n'est pas une zone et il ne couvre aucun espace :
un groupe ne fait jamais qu'apporter des valeurs aux zones qui lui appartiennent.

Trois conséquences qui méritent d'être dites franchement :

- Un groupe ne peut pas accorder d'appartenance. Ajouter quelqu'un à un groupe « staff »
  est impossible — les membres appartiennent aux zones, une liste par zone.
- Un groupe ne peut pas porter de message d'entrée ou de sortie. Ce sont des chaînes
  propres à chaque zone.
- Un groupe n'a pas de priorité, donc deux groupes ne se concurrencent jamais. Une zone
  appartient à **au plus un** groupe : `groupId` est une seule chaîne, pas une liste.

---

## Créer un groupe et le remplir

### Depuis les commandes

```text
/cg group create <nom>
/cg group add <zoneId> <groupId>
/cg group delete <groupId>
```

`create` réduit le nom en identifiant avec la même règle que les zones —
`name.toLowerCase().replaceAll("[^a-z0-9_-]", "_")` — donc `Zone PvE` devient `zone_pve`.
Elle refuse un identifiant qui existe déjà. `add` pose le `groupId` de la zone et ajoute
l'identifiant de la zone à la liste du groupe, en une seule opération ; elle signale
`Groupe ou zone introuvable` s'il manque l'un des deux côtés.

> ⚠️ Aucune des trois ne porte de clause `requires(permissionLevel)`, donc tous les joueurs
> peuvent les lancer, `delete` incluse. Verrouille la commande `cg` dans ton mod de
> permissions si ton serveur accueille des joueurs non fiables. La même faille touche
> `/cg flag` et les commandes de membres — voir
> [zones et drapeaux](zones-and-flags.fr.md#membres-et-rôles).

`/cg group delete` appelle `removeGroup()` sans vérifier que le groupe existe, donc
supprimer un groupe qui n'est pas là annonce un succès et ne fait rien. Sans danger, mais
ne lis pas la confirmation comme une preuve que l'identifiant était le bon.

### Depuis le dashboard

`POST /groups` avec `{"name": "..."}`. L'identifiant vient d'une règle légèrement
différente de celle de la commande : `GroupsApi.slugify()` **supprime** les caractères hors
de `[a-z0-9\s-]` au lieu de les remplacer, puis réduit les suites d'espaces à un tiret
unique. `Zone PvE` devient `zone-pve` depuis le dashboard et `zone_pve` depuis la commande.

Les deux règles se contredisent, et rien ne les réconcilie. Choisis une voie de création
par serveur et tiens-t'y, ou tu finiras avec `zone-pve` et `zone_pve` côte à côte.

L'affectation est `POST /groups/:id/zones/:zoneId`, le retrait est le même chemin en
`DELETE`. Les deux maintiennent les deux côtés de la relation cohérents, ce qui importe
parce que la relation est stockée deux fois.

---

## La relation est stockée des deux côtés

Une zone connaît son groupe (`Region.groupId`) et un groupe connaît ses zones
(`RegionGroup.regionIds`). Rien dans le modèle de données ne force ces deux-là à
s'accorder, donc chaque voie d'écriture met les deux à jour :

| Voie | Ce qu'elle fait |
|---|---|
| `POST /groups/:id/zones/:zoneId` | Retire la zone de son ancien groupe, l'ajoute à celui-ci, pose `groupId` |
| `DELETE /groups/:id/zones/:zoneId` | Retire de la liste, et n'efface `groupId` que s'il pointe encore vers ce groupe |
| `PUT /zones/:id` avec `groupId` | La même mise à jour des deux côtés, depuis la zone. Une chaîne vide vaut `null` |
| `DELETE /zones/:id` | Retire l'identifiant de la zone de la liste de son groupe avant de supprimer la zone |
| `DELETE /groups/:id` | Efface `groupId` sur chaque zone qui pointait vers le groupe |
| `/cg group add` | Pose les deux côtés |

La résolution des drapeaux ne lit qu'**un** des deux côtés : `isAllowed()` part de la zone,
passe par `region.getGroupId()` et cherche le groupe par son identifiant. Une zone listée
dans les `regionIds` d'un groupe mais dont le `groupId` propre est nul ou périmé n'hérite
**de rien** — elle apparaît simplement dans la liste des zones du groupe, qui saute les
identifiants ne correspondant plus à une zone. Si les drapeaux d'un groupe semblent ignorés
par une zone, vérifie d'abord le `groupId` de cette zone.

`/cg group add` et le fichier JSON sont les deux moyens d'arriver à cet état incohérent. La
commande, elle, maintient bien les deux côtés en phase ; une édition à la main, non.

---

## Comment les droits se résolvent quand une zone a les deux

Les drapeaux se résolvent sur toutes les zones qui se recouvrent à une position, en passes.
[L'ordre complet est dans la page des zones](zones-and-flags.fr.md#lordre-de-résolution-complet) ;
la partie qui concerne les groupes est celle-ci :

1. **Passe 1** — chaque zone couvrant la position est examinée pour une valeur
   **explicite** du drapeau, dans l'ordre trié. La première trouvée décide, et la
   résolution s'arrête.
2. **Passe 2** — seulement si aucune zone n'avait de valeur explicite. La même liste est
   reparcourue, et la première zone dont le groupe a une valeur pour ce drapeau décide.
3. **Passe 3** — le jeu de drapeaux refusés de la dimension.

Donc : **un drapeau de zone explicite bat toujours un drapeau de groupe.** Pas seulement
celui de son propre groupe — *n'importe quel* drapeau de groupe de *n'importe quelle* zone
qui se recouvre, quelle que soit la priorité de cette autre zone.

### Les trois cas

**Une zone, avec un groupe.** Son drapeau propre gagne quand il est posé ; le groupe
remplit tout ce qui reste en héritage. C'est le cas normal et il se comporte comme on
l'attend : le groupe est le défaut, la zone est la surcharge.

**Des zones qui se recouvrent, l'une avec un drapeau explicite.** La valeur explicite gagne
même si le groupe de l'autre zone dit le contraire et même si cette autre zone est plus
prioritaire. La passe 1 se termine avant que la passe 2 ne commence.

Concrètement : `plaza` (priorité 0) autorise explicitement `pvp` ; `downtown`
(priorité 50) la recouvre et appartient au groupe `safe`, qui refuse `pvp`. Dans la zone de
recouvrement, le PvP est **autorisé**, parce que `plaza` a une valeur explicite et
`downtown` non. Augmenter la priorité de `downtown` ne change rien. Pour que `safe` gagne
là, pose `pvp` en refus explicite sur `downtown`.

**Des zones qui se recouvrent, toutes en héritage.** La passe 2 décide, en parcourant les
zones dans le même ordre trié — l'enfant avant le parent, puis la priorité décroissante — et
en prenant la première valeur de groupe qu'elle trouve. Là, la priorité fait ce que tu
attends.

### Membres et OP passent avant

L'exemption de membre et le contournement OP sont évalués **avant** toutes ces passes, et
ils sont évalués contre les zones, jamais contre le groupe. Pour un drapeau à exemption (de
protection), être le propriétaire ou un membre de n'importe quelle zone couvrant la position
autorise l'action sans plus de question — le refus d'un groupe ne peut pas restreindre un
membre de l'une de ses propres zones.

Les drapeaux de comportement n'ont aucune exemption, donc un groupe qui refuse
`fall-damage` ou `cobblemon-summon` s'applique aux membres, aux propriétaires et aux
opérateurs sans distinction. Cette asymétrie est de loin la première cause de « mon drapeau
de groupe ne fait rien » : les drapeaux de protection sont levés pour les gens mêmes qui
les testaient.

Quels drapeaux appartiennent à quelle famille est listé dans
[les tableaux de drapeaux](zones-and-flags.fr.md#les-drapeaux).

### Les conditions d'entrée fusionnent au lieu de surcharger

Les conditions d'advancement et de quête se comportent différemment des drapeaux. Pour
chaque zone, `ProtectionListener` construit l'**union** de la liste de la zone et de celle
de son groupe dans un `LinkedHashSet` — les entrées de la zone d'abord, celles du groupe
ensuite, les doublons fondus :

```java
Set<String> required = new LinkedHashSet<>();
required.addAll(entry ? region.getRequiredAdvancementsEntry() : region.getRequiredAdvancementsExit());
if (region.getGroupId() != null) { /* on ajoute aussi la liste du groupe */ }
```

Une zone ne peut pas raccourcir la liste de conditions de son groupe — seulement
l'allonger. Un groupe avec un advancement requis conditionne chacune de ses zones, et une
zone qui en ajoute deux à elle en exige maintenant trois. S'il te faut une zone dispensée,
sors-la du groupe.

Les opérateurs sautent entièrement les deux listes de conditions, au niveau de permission
2, et la liste de quêtes est sautée quand FTB Quests n'est pas chargé.

---

## Poser les drapeaux d'un groupe

Les drapeaux de groupe ont les mêmes trois états que ceux des zones : `ALLOW`, `DENY`,
`INHERIT`. Il n'y a pas de commande `/cg` pour eux — `/cg flag` n'écrit que des drapeaux de
zone. Les groupes s'éditent depuis le dashboard :

```http
PUT /api/v1/cheeseguard/groups/zone-pve
Content-Type: application/json

{
  "name": "Zones PvE",
  "flags": { "pvp": "DENY", "explosions": "DENY", "block-break": "INHERIT" }
}
```

Seules les clés présentes dans le corps sont touchées ; un identifiant de drapeau que
`Flag.fromId()` ne reconnaît pas est sauté en silence, et une valeur qui n'est pas l'un des
trois états est sautée aussi. `INHERIT` retire l'entrée de la carte, et c'est ainsi qu'on
dépose un drapeau de groupe — il n'y a pas de route de suppression de drapeau.

`GET /groups/:id` renvoie toujours les 31 drapeaux avec leur état, donc les entrées
`INHERIT` sont visibles bien qu'absentes du fichier. La liste (`GET /groups`) renvoie un
`flagCount`, qui ne compte que ceux explicitement posés.

---

## Persistance

Dans le même fichier que les zones, lu une seule fois au démarrage et réécrit
intégralement après chaque changement :

```text
config/cheeseguard/regions.json
```

```json
{
  "regions": { },
  "groups": {
    "zone-pve": {
      "name": "Zones PvE",
      "regions": [ "mines_du_nord", "plaine_centrale" ],
      "flags": { "pvp": false, "explosions": false },
      "requiredAdvancementsEntry": [ "minecraft:story/mine_diamond" ]
    }
  }
}
```

L'identifiant du groupe est la **clé**. La liste des zones est écrite sous `regions`, pas
sous `regionIds` — le nom JSON et le nom du champ Java diffèrent. `flags` ne contient que
les entrées explicitement posées, sous forme de booléens. Les quatre listes de conditions
ne sont écrites que lorsqu'elles ne sont pas vides.

Les groupes sont désérialisés **avant** les zones, donc la référence `group` d'une zone
trouve toujours sa cible au chargement. Seul `name` est lu sans garde `has()`, donc un
groupe édité à la main auquel il manque son nom avorte tout le chargement — zones
incluses. Sauvegarde le fichier avant de l'éditer.

`removeGroup()` balaye chaque zone dont le `groupId` correspondait et le met à `null`, puis
sauvegarde. Supprimer un groupe ne supprime jamais une zone, et les zones gardent les
drapeaux explicites qu'elles avaient ; elles ne perdent que ceux qu'elles héritaient.

---

← [Zones et drapeaux](zones-and-flags.fr.md) · [Dimensions](dimensions.fr.md) →
