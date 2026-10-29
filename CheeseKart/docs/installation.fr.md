[← CheeseKart](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](installation.md)

# Installation

CheeseKart tourne sur le serveur et sur le client, et les pièces qui comptent dépendent
du côté où tu l'installes. Son `fabric.mod.json` déclare `"environment": "*"`, donc
Fabric le charge des deux côtés.

---

## Ce qu'il te faut

### Serveur

| | Version | Pourquoi |
|---|---|---|
| Minecraft | **1.21.1** exactement | déclaré comme `~1.21.1` |
| Fabric Loader | ≥ 0.16.0 | |
| Fabric API | n'importe quel build 1.21.1 | commandes, réseau, événements de cycle de vie |
| Java | 21 ou plus récent | |
| [CheeseDash](../../CheeseDash/) | n'importe laquelle | **dépendance stricte** — le serveur refuse de démarrer sans elle |

### Client, pour chaque pilote

| | Requis ? | Sans lui |
|---|---|---|
| CheeseKart | non, mais recommandé | les bateaux restent visibles, et Shift fait réellement descendre d'un kart verrouillé — le serveur remet le joueur en selle un tick plus tard, ce qui donne un à-coup plutôt qu'une touche sans effet |
| [OpenBoatUtils](https://modrinth.com/mod/openboatutils) | **oui, en pratique** | le kart se conduit exactement comme un bateau vanilla — aucune adhérence, aucune accélération, aucun changement de direction |

Un pilote sans OpenBoatUtils ne produit aucune erreur visible par qui que ce soit. Il
conduit un bateau normal pendant que tout le monde conduit un kart, et rien n'est écrit
dans le log. Si la course est une vraie composante de ton serveur, mets OpenBoatUtils
dans le pack client et considère-le comme obligatoire.

### Optionnel

- [CobbleShift](../../CobbleShift/) — active le champ et l'argument `species`, qui
  transforment les pilotes en Pokémon le temps d'une session. Atteint par réflexion, il
  peut donc être ajouté ou retiré à tout moment.

---

## Compilation

CheeseKart compile contre le jar compilé de CheeseDash : **CheeseDash doit donc être
compilé en premier**.

```bash
cd CheeseDash  && ./gradlew build     # gradlew.bat sous Windows
cd ../CheeseKart && ./gradlew build
```

Depuis la racine du dépôt, `tools/build-all.sh` enchaîne tout ça dans le bon ordre pour
tous les mods d'un coup.

Le jar que tu veux est celui-ci :

```text
CheeseKart/build/libs/cheesekart-1.0.0+1.21.1.jar
```

Ignore `cheesekart-1.0.0+1.21.1-sources.jar` — c'est l'archive des sources, pas un mod.

Une erreur de compilation qui nomme des classes `com.cheesedash` absentes signifie que
CheeseDash n'a pas été compilé en premier.

## Installation

1. Arrête le serveur.
2. Copie le jar CheeseDash et le jar CheeseKart dans `mods/`.
3. Vérifie que Fabric API est présent aussi.
4. Démarre le serveur.

Donne à chaque client de pilote le jar CheeseKart et OpenBoatUtils.

---

## Ce que crée le premier démarrage

Un seul fichier :

```text
config/cheesekart.json
```

Il est écrit avec trois presets — `rally`, `drift` et `parkour` — parce que le mod sème
des valeurs par défaut dès qu'il ne trouve aucun preset. Référence complète des champs
dans [configuration](configuration.fr.md).

Rien d'autre n'est créé. Pas de base de données, pas de données de monde, pas de
fichiers joueur. Les sessions vivent en mémoire et ne sont volontairement pas
persistées.

## Vérifier le chargement

Trois lignes dans le log du serveur, dans cet ordre :

```text
[CheeseKart] Initializing...
[CheeseKart] Initialized
[CheeseKart] Server started — 3 presets loaded
```

Les deux premières viennent de l'initialisation du mod, la troisième de l'événement de
démarrage du serveur — si tu vois les deux premières sans la troisième, c'est que le
serveur n'a pas terminé son démarrage, pour une raison étrangère au mod.

Attends-toi à une ligne de plus, selon qu'OpenBoatUtils est installé **sur le serveur**
ou non :

```text
[CheeseKart] OBU detected — using OBU BytePayload via reflection
[CheeseKart] OBU not loaded — registered fallback payload channel
```

Les deux sont normales.
[Pourquoi il existe deux chemins](physics.fr.md#pourquoi-le-payload-est-envoyé-de-deux-manières-différentes).

Et une autre à propos de CobbleShift, s'il est présent :

```text
[CheeseKart] CobbleShift integration active
[CheeseKart] CobbleShift not installed — transform features disabled
```

Confirme ensuite en jeu, en tant qu'opérateur :

```mcfunction
/cheesekart presets
```

```text
--- Presets (3) ---
  rally — Rally | slip=0.98 step=1.25 fwd=0.04 [locked]
  drift — Drift | slip=1.1 step=1.25 fwd=0.06 [locked]
  parkour — Parkour | slip=0.98 step=2.0 fwd=0.04 [locked]
```

Si la commande n'existe pas, le mod n'a pas chargé. Si elle existe mais répond que tu
n'as pas la permission, tu es en dessous du niveau de permission 2.

Enfin, le dashboard : `kart` doit apparaître dans `GET /api/v1/modules`, et la barre
latérale doit afficher une entrée 🏎.

---

## Désinstallation

1. Lance `/cheesekart stopall` **avant d'arrêter le serveur.** C'est important : cette
   commande fait descendre tous les pilotes, supprime les bateaux invisibles et
   réinitialise la physique des clients. Retire le jar avec des sessions en cours et tu
   laisses dans le monde des bateaux invisibles et invulnérables. L'arrêt normal du
   serveur exécute aussi `stopAll`, donc un arrêt propre suffit — c'est un arrêt sale
   qui doit t'inquiéter.
2. Arrête le serveur.
3. Retire le jar de `mods/`.
4. Éventuellement, supprime `config/cheesekart.json`. Le garder ne coûte rien et
   préserve tes presets en cas de réinstallation.

Rien ne reste dans les données du monde ni dans celles des joueurs. Si tu as malgré tout
perdu un bateau quelque part, c'est une entité bateau vanilla ordinaire avec l'attribut
d'invisibilité — repérable avec `/kill @e[type=boat]` dans la zone, ce qui emportera
aussi les bateaux légitimes : vise juste.

---

← [CheeseKart](../README.fr.md) · [Premiers pas](getting-started.fr.md) →
