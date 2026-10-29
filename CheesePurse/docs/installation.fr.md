[← CheesePurse](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](installation.md)

# Installation

CheesePurse déclare `"environment": "*"` : Fabric le charge donc sur le serveur et sur
le client. Les deux côtés travaillent réellement et aucun n'est optionnel — le serveur
fait tourner la boucle de dépôt, le client dessine le bouton qui l'active. Un serveur
équipé du mod avec des clients qui ne l'ont pas, c'est une fonctionnalité que personne
ne peut atteindre.

---

## Les trois noms de ce mod

C'est ce qui piège chaque première installation, alors autant commencer par là.

| Où | Nom |
|---|---|
| Dossier du dépôt | `CheesePurse` |
| Identifiant du mod (`fabric.mod.json`) | `numismatic-cheesepurse` |
| Nom affiché | `Numismatic Cheesepurse` |
| Package Java | `com.roicheese.numismaticheesepurse` |
| Fichier de configuration des mixins | `numismaticheesepurse.mixins.json` |
| Clés de langue | `numismaticheesepurse.button.tooltip.*` |
| Jar produit | `numismatic-cheesepurse-1.0.0+1.21.1.jar` |

Le nom du jar vient de `archives_base_name=numismatic-cheesepurse` dans
`gradle.properties`, pas du dossier. Il n'existe aucun `cheesepurse-*.jar`, et il n'en a
jamais existé. Si tu scriptes un déploiement avec un glob, cible
`numismatic-cheesepurse-*+1.21.1.jar` et exclus `-sources.jar`.

L'orthographe du package est elle aussi irrégulière : `numismaticheesepurse`, avec un
seul `c` partagé entre « numismatic » et « cheesepurse ». Elle est écrite ainsi partout
— package Java, configuration des mixins, clés de traduction — ce n'est donc pas une
faute à corriger : la corriger à un seul endroit casserait le chargement du mixin ou les
infobulles. Seul l'espace de noms des assets utilise la forme avec tiret
`numismatic-cheesepurse`, parce qu'un espace de noms d'assets doit correspondre à
l'identifiant du mod.

---

## Ce qu'il te faut

### Serveur

| | Version | Pourquoi |
|---|---|---|
| Minecraft | **1.21.1** exactement | déclaré `~1.21.1` |
| Fabric Loader | ≥ 0.17.2 | déclaré dans `depends` |
| Fabric API | n'importe quel build 1.21.1 | registre réseau et `ServerTickEvents` |
| Java | 21 ou plus | `compatibilityLevel: JAVA_21` |
| [Numismatic Overhaul](https://modrinth.com/mod/numismatic-overhaul) | n'importe laquelle (compilé contre 0.3.4) | **dépendance stricte** — le serveur refuse de démarrer sans |

Numismatic Overhaul traîne à son tour ses propres dépendances, en particulier Cardinal
Components et owo-lib. Installe-le comme sa page l'indique ; CheesePurse n'ajoute aucune
exigence au-delà de la présence de Numismatic.

### Client, pour chaque joueur

| | Obligatoire ? | Sans lui |
|---|---|---|
| CheesePurse | **oui** | aucun bouton dans l'écran d'inventaire, aucun moyen d'activer la fonctionnalité, et le serveur n'entend jamais parler de ce joueur |
| Numismatic Overhaul | **oui** | le client refuse de se lancer — l'entrée `depends` s'applique des deux côtés |
| Fabric API | oui | le paquet de bascule passe par `ClientPlayNetworking` |

Le bouton vient d'un mixin client sur `InventoryScreen`, listé sous `"client"` dans
`numismaticheesepurse.mixins.json`. Un client vanilla, ou un client Fabric sans ce jar,
voit un écran d'inventaire standard et ne pourra jamais activer la fonctionnalité pour
lui-même.

Il n'existe aucun contournement côté administration. Rien, sur le serveur, ne peut
activer le dépôt automatique pour un joueur qui ne peut pas cliquer sur le bouton.

---

## Construction

CheesePurse ne dépend d'aucun mod frère de ce dépôt : il se construit seul, sans
contrainte d'ordre.

```bash
cd CheesePurse
./gradlew build          # gradlew.bat sous Windows
```

Numismatic Overhaul est résolu à la compilation depuis le dépôt Maven de Modrinth, que
`build.gradle` déclare :

```groovy
modCompileOnly "maven.modrinth:numismatic-overhaul:DPXG6kTJ"  // 0.3.4
```

`DPXG6kTJ` est un identifiant de version Modrinth, pas un numéro de version. Il épingle
exactement un fichier. La dépendance est `modCompileOnly` : Numismatic n'est que sur le
classpath de compilation et n'est jamais embarqué dans le jar — ce sont les joueurs qui
l'installent.

La première construction a besoin d'un accès réseau à `api.modrinth.com`. Sur une
machine hors ligne, elle échoue sur une dépendance `maven.modrinth:numismatic-overhaul`
non résolue.

Le jar que tu veux :

```text
CheesePurse/build/libs/numismatic-cheesepurse-1.0.0+1.21.1.jar
```

Ignore `numismatic-cheesepurse-1.0.0+1.21.1-sources.jar`, et ignore tout ce qui se
trouve sous `build/devlibs/` : ce sont des artefacts de développement aux noms non
remappés.

Depuis la racine du dépôt, `tools/build-all.sh` construit tous les mods dans l'ordre des
dépendances et inclut celui-ci.

## Installation

1. Arrête le serveur.
2. Copie `numismatic-cheesepurse-1.0.0+1.21.1.jar` dans `mods/`.
3. Vérifie que Fabric API et Numismatic Overhaul sont dans le même dossier.
4. Démarre le serveur.
5. Distribue le même jar à chaque joueur, dans son `mods/` client.

---

## Ce que crée le premier démarrage

Rien.

Pas de fichier de configuration, pas de dossier de données, pas de données de monde, pas
de données de joueur qui lui soient propres. Le seul état que le mod possède est un
`Set<UUID>` en mémoire, et le seul état qu'il écrit est le composant de monnaie de
Numismatic Overhaul, que Numismatic persiste dans les données du joueur.

Cela vaut la peine d'être répété, parce que cela fixe les attentes : il n'y a aucun
fichier à modifier pour changer le comportement du mod, et aucun fichier à supprimer
pour le réinitialiser. Voir [configuration](configuration.fr.md).

## Vérifier qu'il est chargé

Deux lignes dans le log du serveur, émises par `NumismaticCheesePurse.onInitialize` :

```text
[NumismaticCheesePurse] Initializing…
[NumismaticCheesePurse] Ready.
```

Elles sont produites par un logger nommé d'après l'identifiant du mod : la plupart des
formats de log les affichent donc aussi avec un préfixe `numismatic-cheesepurse` en plus
de celui entre crochets dans le message. Les deux lignes se suivent — tout ce qui se
passe entre elles est de l'enregistrement de paquets et d'événements, qui ne produit
aucune sortie. Si tu vois la première sans la seconde, quelque chose a levé une
exception pendant l'initialisation : la pile d'appels se trouve juste après.

Tout ce que le mod fait ensuite est journalisé en `DEBUG` :

```text
[NumismaticCheesePurse] Auto-collect ENABLED for Steve
[NumismaticCheesePurse] Collected 1 240 bronze units for Steve
```

Un niveau de log de serveur par défaut masque les deux. Monte le niveau pour
`com.roicheese.numismaticheesepurse` ou pour le logger `numismatic-cheesepurse` si tu
veux regarder la boucle travailler.

Confirme ensuite en jeu :

1. Rejoins le serveur et appuie sur E.
2. Une petite icône de pièce se trouve près du coin supérieur gauche de la fenêtre
   d'inventaire, juste au-dessus du modèle du personnage.
3. Survole-la. L'infobulle affiche `Cheesepurse : DÉSACTIVÉE`, avec une seconde ligne
   qui explique le clic.

Pas d'icône : le jar client manque, ou le mixin ne s'est pas appliqué — voir
[dépannage](troubleshooting.fr.md#il-ny-a-aucun-bouton-dans-mon-écran-dinventaire).

## Désinstallation

1. Demande aux joueurs de désactiver l'interrupteur, ou ne fais rien et accepte que les
   balayages en cours s'arrêtent avec le jar. Rien ne reste à moitié fait : chaque
   balayage est un appel synchrone unique, il n'y a donc aucun état partiel à vider.
2. Arrête le serveur.
3. Retire le jar de `mods/`, sur le serveur et sur chaque client.

Ce qui reste derrière :

- **L'argent déjà déposé reste dans la bourse.** C'est le composant de Numismatic, pas
  celui de ce mod, et retirer CheesePurse ne l'affecte pas.
- Rien dans `config/`, puisque rien n'y a été écrit.
- Rien dans les données de monde ni dans les données de joueur appartenant à ce mod.

Retirer le jar du serveur en le laissant sur les clients laisse le bouton visible et
cliquable. Le clic envoie un payload sur le canal
`numismatic-cheesepurse:toggle_auto_collect` dont le serveur n'a plus de récepteur ;
Fabric ignore les payloads de jeu inconnus et journalise un avertissement plutôt que de
déconnecter le client. Le symptôme est donc un bouton qui change de couleur et ne fait
rien. Retire-le des deux côtés en même temps.

---

← [CheesePurse](../README.fr.md) · [Premiers pas](getting-started.fr.md) →
