[← CheeseItems](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](getting-started.md)

# Premiers pas

À la fin de ce parcours, un joueur de ton serveur tiendra une `Lantern of Arkensia`
brillante avec sa propre icône PNG, qui joue un son et le téléporte trois secondes après un
clic droit, et possédera une morpho qu'il pourra porter d'une pression sur une touche. Rien
de ce qui suit n'exige de redémarrer le serveur après le premier lancement.

Il te faut un serveur Fabric 1.21.1 avec CheeseDash et CheeseItems dans `mods/`
([installation](installation.fr.md)), les droits d'opérateur en jeu, et le token CheeseDash
pour te connecter au dashboard sur `http://<hôte>:44311`.

## 1. Vérifier que le mod répond

```mcfunction
/cheeseitem list
```

```text
Aucun item défini.
```

Si tu obtiens *Unknown or incomplete command* à la place, arrête-toi ici et consulte
[le dépannage](troubleshooting.fr.md#la-commande-cheeseitem-nexiste-pas).

## 2. Régler l'hôte du resource pack

Ouvre `config/cheeseitems.json` et règle la seule clé qui soit de la vraie configuration :

```json
"resourcePackHost": "play.example.net:44311"
```

Utilise l'adresse et le port CheeseDash que tes **joueurs** atteignent, pas `localhost`.
Puis :

```mcfunction
/cheeseitem reload
```

```text
CheeseItems config rechargée.
```

Sauter cette étape ne casse rien de ce que tu fais aujourd'hui si tu testes depuis la même
machine. Cela casse les icônes personnalisées pour tous les autres.
[Pourquoi](configuration.fr.md#resourcepackhost).

## 3. Créer l'item depuis le dashboard

Connecte-toi au dashboard, ouvre **CheeseItems** 🎁 dans la barre latérale, et crée un item.
L'éditeur écrit le JSON pour toi, mais il vaut la peine de savoir ce qu'il produit, donc
voilà la même chose via l'API :

```bash
curl -s -X POST http://localhost:44311/api/v1/items \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "id": "lantern_of_arkensia",
    "displayName": "Lantern of Arkensia",
    "nameColor": "#F5A623",
    "baseItem": "minecraft:soul_lantern",
    "lore": ["A quest item. It remembers the way."],
    "glowing": true,
    "maxStackSize": 1,
    "cooldownTicks": 200
  }'
```

```json
{"id":"lantern_of_arkensia","displayName":"Lantern of Arkensia", … }
```

La réponse est la définition complète, chaque champ que tu n'as pas envoyé étant rempli
depuis les défauts. Le fichier sur le disque est déjà à jour — les écritures d'items sont
synchrones.

Vérifie depuis le jeu :

```mcfunction
/cheeseitem list
```

```text
--- CheeseItems (1) ---
 [ON] lantern_of_arkensia — Lantern of Arkensia (minecraft:soul_lantern)
```

⚠️ Ne te trompe pas sur `baseItem`. Un identifiant qui n'existe pas donne `minecraft:air`, et
`give` rapportera gaiement un succès en ne donnant rien au joueur.

## 4. Te le donner et le regarder

```mcfunction
/cheeseitem give Steve lantern_of_arkensia
```

```text
Donné 1x Lantern of Arkensia à Steve
```

Tu tiens une lanterne des âmes nommée *Lantern of Arkensia* en ambre, avec une ligne de lore
grise et un effet de brillance. Le clic droit ne fait encore rien : aucune action n'y est
attachée.

## 5. Attacher une action

Retour dans l'éditeur, ajoute une **action d'utilisation**. Via l'API, un `PUT` fusionne dans
la définition existante — seules les clés envoyées sont touchées :

```bash
curl -s -X PUT http://localhost:44311/api/v1/items/lantern_of_arkensia \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "useActions": [{
      "command": "playsound minecraft:block.beacon.activate master {player}",
      "count": 1,
      "feedback": "La lanterne répond.",
      "delayCommand": "tp {player} 148 64 -512",
      "delaySeconds": 3,
      "showTimer": true,
      "timerLabel": "Sanctuaire"
    }]
  }'
```

Fais un clic droit avec la lanterne que tu tiens déjà. Pas de rechargement, pas de nouveau
`give` : les `useActions` ne sont pas stockées sur la pile, donc la modification s'applique à
chaque copie du monde d'un coup.

Ce que tu vois, dans l'ordre : le son de balise, *La lanterne répond.* dans la barre
d'action, puis `Sanctuaire: 3s`, `Sanctuaire: 2s`, `Sanctuaire: 1s`, et tu te retrouves en
148 64 -512.

⚠️ `{player}` est le seul motif de substitution, et les commandes tournent **en tant que
console, au niveau de permission 4**. Quiconque peut cliquer droit sur cet item peut lancer
cette commande. Lis [useActions](items.fr.md#useactions) avant d'y mettre quoi que ce soit de
destructeur.

Refais un clic droit tout de suite et tu obtiens `Cooldown : 9.8s`, parce que l'étape 3 a
posé `cooldownTicks: 200`.

## 6. Lui donner une icône

Dans l'éditeur d'item, envoie un PNG de 16×16 (ou n'importe quel carré). Ou bien :

```bash
curl -s -X POST http://localhost:44311/api/v1/items/lantern_of_arkensia/image \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"imageBase64\":\"$(base64 -w0 lantern.png)\"}"
```

```json
{"uploaded":true}
```

Trois choses se produisent sur le serveur :

```text
[CheeseItems] Resource pack generated: 1 items, 1204 bytes, SHA1=…
[CheeseItems] Pack changed — pushing to 1 online players
[CheeseItems] Image uploaded for item: lantern_of_arkensia
```

Ton client est invité à télécharger *Custom item icons for Cacademy*. Accepte. La lanterne
dans ta main devient ton PNG — le rafraîchissement des inventaires tourne avec l'envoi, donc
inutile de redonner l'item.

Si l'icône n'apparaît pas, la cause habituelle est un `resourcePackHost` pointant vers un
endroit que ton client ne peut pas atteindre.
[Liste de contrôle](troubleshooting.fr.md#les-icônes-personnalisées-napparaissent-pas).

## 7. Créer une morpho

La seconde moitié du mod. Ouvre **Morphos** 🧬 dans la barre latérale, ou :

```bash
curl -s -X POST http://localhost:44311/api/v1/morphos \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title":"Ditto","species":"ditto","isNew":true,"terrestrial":true}'
```

```json
{"id":"7c1f90ab","title":"Ditto","species":"ditto", … }
```

Note l'`id` généré — tu en as besoin à l'étape suivante. `species` est ce qui est transmis à
CobbleShift, donc ce doit être une espèce que `cshift` accepte.

Envoie-lui une image de la même façon que l'icône d'item
(`POST /api/v1/morphos/7c1f90ab/image`) si tu veux la voir dans le Codex avec une
illustration plutôt qu'un placeholder.

## 8. L'attribuer et la porter

```mcfunction
/cheeseitem morpho grant Steve 7c1f90ab
```

```text
Morpho Ditto donnée à Steve.
```

et le joueur voit `🧬 Ditto ajoutée à votre Codex de Morpho !`.

Appuie sur `J`. Le Codex s'ouvre et montre une carte. Comme c'est la première morpho que ce
joueur possède, elle est déjà active. Appuie sur `H` :

```text
Transformation : Ditto
```

Le joueur est un Métamorph, une barre d'endurance apparaît dans la barre d'action, et après
50 secondes au débit par défaut elle se vide et l'annule automatiquement. Appuie sur `H`
avant cela pour annuler à la main.

Si `H` affiche *Transformation : Ditto* mais que rien ne change visiblement, CobbleShift
n'est pas installé ou `cshift` a rejeté l'espèce. Le log a le détail :

```text
[CheeseItems] cshift command failed: cshift transform Steve ditto (…)
```

Voir [morphos](morphos.fr.md#porter-une-morpho) — le mod ne peut pas savoir si la
transformation a réellement fonctionné.

## 9. Distribuer l'item proprement

Pour une quête, tu ne veux presque jamais taper `give` toi-même. Deux approches :

- Un PNJ ou une fonction lance `cheeseitem give <player> <id>`, ce qui exige le niveau de
  permission 2 — lance-la depuis un bloc de commande ou une fonction, qui s'exécutent tous
  deux au niveau 2 ou plus.
- Le bouton de don du dashboard, qui appelle `POST /api/v1/items/:id/give` avec un nom de
  joueur.

Et pour qu'un item attribue une morpho quand on l'utilise, mets l'attribution dans ses
`useActions` :

```json
{"command": "cheeseitem morpho grant {player} 7c1f90ab", "count": 1, "feedback": "Une nouvelle forme."}
```

Voilà la boucle complète : un item écrit depuis le dashboard qui, au clic droit, donne une
morpho écrite depuis le dashboard. Aucun des deux n'a demandé de redémarrage, ni une ligne de
Java.

## Pour aller plus loin

- Tous les champs d'un item, et les six qui ne font rien :
  [items personnalisés](items.fr.md)
- Comment le pack est construit et ce qu'il écrase : [resource pack](resource-pack.fr.md)
- Le catalogue, le Codex et la réserve d'endurance : [morphos](morphos.fr.md)
- ⚠️ Avant de confier des comptes dashboard à quelqu'un d'autre, lis
  [ce que les gardes de rôle couvrent, et ce qu'ils ne couvrent pas](dashboard.fr.md#authentification-et-gardes-de-rôle)

---

← [Installation](installation.fr.md) · [Commandes](commands.fr.md) →
