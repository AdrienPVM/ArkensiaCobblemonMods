[← CheeseExtraCommands](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](installation.md)

# Installation

CheeseExtraCommands ne dépend d'aucun mod frère : il se construit et s'installe seul.
C'est l'installation la plus courte du dépôt.

---

## Prérequis

| | Version | Pourquoi |
|---|---|---|
| Minecraft | 1.21.x | `fabric.mod.json` déclare `"minecraft": "~1.21"` ; construit et utilisé sur 1.21.1 |
| Fabric Loader | ≥ 0.15.0 | déclaré dans `depends` ; le build utilise 0.15.11 |
| Fabric API | n'importe laquelle | déclarée `"fabric-api": "*"` ; le mod utilise les modules commandes, cycle de vie, réseau et rendu du HUD |
| Java | 21 | `options.release = 21` dans `build.gradle` |
| [Cobblemon](https://modrinth.com/mod/cobblemon) | 1.6.1 | optionnel au chargement, indispensable pour que les commandes servent à quelque chose |

Cobblemon est dans `suggests`, pas dans `depends`. Le build le récupère depuis le Maven
Modrinth sous la forme `compileOnly "maven.modrinth:cobblemon:v77SHSXW"`, qui est la
version Fabric de 1.6.1, et ne s'y lie jamais à l'exécution. Tout passe par
`CobblemonReflect`, qui résout les classes par leur nom et les méthodes par leur
signature. Voir [la couche de réflexion](architecture.fr.md#la-couche-de-réflexion).

## Sur quel côté l'installer

**Les deux, avec des conséquences différentes.** `fabric.mod.json` déclare
`"environment": "*"` et deux entrypoints : `main` (`CheeseExtraMod`) et `client`
(`CheeseExtraClient`).

| Emplacement | Ce que tu obtiens |
|---|---|
| **Serveur seul** | Les quatre sous-commandes `/cheesecheck` fonctionnent. Le HUD du tour de l'île n'apparaît jamais, aucun client ne sachant décoder le paquet |
| **Serveur et client** | Les commandes fonctionnent, et un joueur dont le client a aussi le jar voit le HUD du tour de l'île |
| **Client seul** | Rien. Ni commande, ni HUD, puisque le HUD est entièrement piloté par un paquet serveur |

Le serveur tolère un client dépourvu du mod : `IslandTourTracker.update` enveloppe son
`ServerPlayNetworking.send` dans un `try/catch` et écrit une seule ligne DEBUG si l'envoi
échoue. Personne n'est déconnecté, rien n'est spammé.

## Construire

```bash
cd CheeseExtraCommands
./gradlew build
```

Le jar arrive dans `build/libs/` :

```text
build/libs/cheeseextra-1.0.0+1.21.1.jar
build/libs/cheeseextra-1.0.0+1.21.1-sources.jar
```

La version vient de `mod_version=1.0.0+1.21.1` dans `gradle.properties` et le nom de
base de `archives_base_name=cheeseextra`. Déploie le premier fichier, pas celui en
`-sources`.

Le premier build a besoin du réseau : Gradle résout Cobblemon depuis
`https://api.modrinth.com/maven` et la bibliothèque standard Kotlin depuis Maven
Central. Aucun jar du dossier `localdeps/` du dépôt n'intervient, et
`tools/build-all.sh` place ce mod en tier 3 uniquement parce que c'est là qu'atterrit
tout ce qui n'a pas de dépendance fraternelle.

## Installer

1. Arrête le serveur.
2. Copie `cheeseextra-1.0.0+1.21.1.jar` dans `mods/`.
3. Supprime tout ancien `cheeseextra-*.jar` de `mods/`. Deux versions du même identifiant
   de mod empêchent Fabric de démarrer, tout court.
4. Démarre le serveur.

Distribue le même jar aux clients seulement si tu veux le HUD du tour de l'île. Il n'est
pas nécessaire aux commandes.

## Ce que crée le premier démarrage

Rien. Pas de fichier de configuration, pas de dossier de données, aucun item, bloc ou
entité enregistré. Toute l'empreinte persistante du mod vit dans le scoreboard vanilla
du monde, et seulement après qu'un appel à `/cheesecheck` y a écrit. Voir
[configuration](configuration.fr.md).

## Vérifier qu'il est chargé

Trois lignes, dans cet ordre :

```text
[CheeseExtra] Initializing CheeseExtraCommands...
[CheeseExtra] Commands + Arkensia island-tour tracker registered.
[CheeseExtra] /cheesecheck command registered (ev, iv, pokemon, ballvariety)
```

L'ordre mérite d'être connu. Les deux premières viennent de `onInitialize`, qui ne fait
qu'*enregistrer un callback* pour l'arbre de commandes. La troisième est écrite par
`CheeseCheckCommand.register`, que Fabric appelle plus tard, au moment où le serveur
construit son dispatcher de commandes. Si tu vois les deux premières et jamais la
troisième, le serveur n'est jamais allé jusqu'à l'enregistrement des commandes, et le
problème est ailleurs dans le démarrage.

Les trois sont écrites sous le nom de logger `cheeseextra`, et non sous un nom de classe :
chercher `CheeseExtra` dans le log attrape donc tout ce que ce mod imprime.

## Vérifier en jeu

Serveur lancé, en tant qu'opérateur :

```mcfunction
/cheesecheck pokemon TonPseudo pikachu quest_test 1
```

Trois issues possibles, chacune parlante :

| Ce que voit le joueur ciblé | Signification |
|---|---|
| `Vérification réussie ! Tu as bien un pikachu dans ton équipe.` | le mod et Cobblemon fonctionnent tous les deux |
| `Tu n'as pas de pikachu dans ton équipe. Capture-en un et reviens !` | fonctionne aussi ; l'équipe n'a réellement pas de Pikachu |
| `[CheeseCheck] Cobblemon non disponible.` | le mod s'est chargé, Cobblemon non |

Si la complétion ne propose pas `cheesecheck` du tout, tu es sous le niveau de
permission 2.

Souviens-toi que le message part vers le **joueur nommé dans la commande** : cible-toi
toi-même pour ce test.

## Désinstaller

Retire le jar de `mods/` et redémarre. Rien ne reste du stockage propre au mod, puisqu'il
n'en a aucun.

Ce qui survit, c'est tout ce qu'il a écrit dans le monde :

- chaque objectif de scoreboard qu'un appel à `/cheesecheck` a créé ou mis à jour, avec
  ses scores
- les objectifs que le traqueur du tour de l'île *lit* (`jenny_intro`, `spawn_tour`,
  `<key>_met`), qu'il ne crée jamais et ne supprime jamais

Ce sont des objectifs vanilla ordinaires. Nettoie-les avec
`/scoreboard objectives remove <name>` si tu veux les voir disparaître. Un dialogue de
NPC ou une tâche de quête qui s'appuie encore dessus continue de fonctionner, mais
n'avancera plus jamais.

---

← [CheeseExtraCommands](../README.fr.md) · [Premiers pas](getting-started.fr.md) →
