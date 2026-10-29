[← ScheduleRestart](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](installation.md)

# Installation

## Prérequis

| Prérequis | Version | Pourquoi |
|---|---|---|
| Minecraft | **1.21.1** exactement | `fabric.mod.json` fixe `"minecraft": "1.21.1"`, pas un intervalle |
| Fabric Loader | **≥ 0.16.0** | déclaré dans `depends`. Compilé contre 0.16.9 |
| Java | **21 ou plus** | déclaré dans `depends` ; `build.gradle` compile avec `release = 21` |
| Fabric API | n'importe quelle build 1.21.1 | utilisé pour `CommandRegistrationCallback`. Compilé contre `0.107.0+1.21.1` |
| [CheeseDash](../../CheeseDash/) | n'importe quelle version | exigé par `depends`, et utilisé pour rien. Voir plus bas |

Fabric API n'est **pas** déclaré dans le `fabric.mod.json` de ce mod, mais tu ne peux pas
te retrouver sans lui : CheeseDash, que ce mod exige, le déclare. Sans Fabric API, le loader
refuse de démarrer et signale que CheeseDash a besoin de `fabric-api`. Installe-le et le
message disparaît. Ce trou ne compterait que si la dépendance `cheesedash` était un jour
retirée — [détails](architecture.fr.md).

⚠️ CheeseDash est exigé et inutilisé. Fabric Loader refuse de démarrer sans le jar
CheeseDash, alors que ce mod n'enregistre aucun module dashboard et ne référence aucune
classe CheeseDash. Tu dois satisfaire la dépendance et tu ne reçois rien en retour —
[l'histoire complète](architecture.fr.md#la-dépendance-qui-ne-sert-à-rien).

## De quel côté

**Serveur uniquement.** `fabric.mod.json` fixe `"environment": "server"`, et le mod ne
contient aucun code client, aucun paquet réseau, aucun rendu et aucun raccourci clavier.
Les joueurs se connectent avec un client non modifié et n'ont rien à installer. Mettre le
jar dans le `mods/` d'un client ne sert à rien ; le loader l'ignore purement et simplement
en environnement client.

## Compilation

Le mod n'a aucune dépendance vers un mod frère à la compilation — `build.gradle` ne
contient aucun glob `fileTree` pointant vers le `build/libs/` d'un autre mod — donc tu peux
le compiler seul, dans n'importe quel ordre par rapport au reste du dépôt.

```bash
cd ScheduleRestart
./gradlew build          # gradlew.bat sous Windows
```

Le jar arrive dans `build/libs/`. `tools/build-all.sh`, à la racine du dépôt, compile tous
les mods dans l'ordre des dépendances et inclut celui-ci.

⚠️ **Vérifie ta version de Gradle avant toute chose.**

```bash
./gradlew --version
```

Elle doit afficher `Gradle 8.10`, la version fixée dans
`gradle/wrapper/gradle-wrapper.properties`. Loom 1.8 ne fonctionne pas sous Gradle 9 : il
produit un `remapJar` qui réussit et écrit un jar sans aucun contenu. Si tu as cloné ce
dépôt avant le 29 octobre 2026, le script `gradlew` était un bouchon qui déléguait au
Gradle installé sur ton `PATH`, ce qui rendait la panne facile à rencontrer. Symptôme et
remède dans
[le jar produit est minuscule et le mod ne charge pas](troubleshooting.fr.md#le-jar-produit-est-minuscule-et-le-mod-ne-charge-pas).

## Le jar ne porte pas le nom du mod

```text
build/libs/ScheduleRestart-1.0.0+1.21.1.jar
```

L'identifiant du mod est `scheduledrestart` — un `d` de plus que le dossier — et
`gradle.properties` déclare même `archives_base_name=scheduledrestart`. `build.gradle` ne
l'applique jamais : il n'y a pas de bloc `base { archivesName = ... }`, donc Gradle retombe
sur le nom du projet, et `settings.gradle` ne fixe aucun `rootProject.name`, donc le nom du
projet est celui du dossier, `ScheduleRestart`.

Le résultat est un jar nommé `ScheduleRestart-…` qui contient un mod nommé
`scheduledrestart`. Rien ne casse, parce que le nom de fichier d'un jar dans `mods/` n'a
aucun sens pour Fabric. Mais ne cherche pas `scheduledrestart` dans ton répertoire `mods/`,
ne trouve rien et n'en conclus pas que le mod n'est pas installé. L'incohérence est laissée
telle quelle délibérément, pour que le nom de jar donné dans cette documentation
corresponde à ce qu'une compilation produit vraiment.

## Déploiement

```bash
cp build/libs/ScheduleRestart-1.0.0+1.21.1.jar /chemin/vers/serveur/mods/
```

À côté de lui, le `mods/` du serveur doit contenir :

- `fabric-api-*.jar`
- `cheesedash-*.jar`

Redémarre le serveur. Il n'y a rien à configurer.

## Ce que le premier démarrage crée

**Rien.** Aucun fichier dans `config/`, aucun répertoire, aucun marqueur, aucune base. Le
mod n'écrit rien sur le disque, ni à son premier démarrage ni plus tard, et il ne lit jamais
de fichier. Le seul état qu'il garde est un `java.util.Timer` en mémoire, et c'est pourquoi
[configuration](configuration.fr.md) est une page sur des lignes de code et non sur un
fichier.

## Vérifier qu'il est chargé

Le mod n'affiche aucune bannière de démarrage — `onInitialize()` enregistre la commande et
rend la main. Il y a deux vérifications fiables.

**Dans le log.** Fabric liste tous les mods chargés pendant le démarrage :

```text
[main/INFO] (FabricLoader) Loading 42 mods:
	...
	- scheduledrestart 1.0.0+1.21.1
	...
```

Cherche l'identifiant, pas le nom du jar :

```bash
grep -n "scheduledrestart" logs/latest.log
```

**En jeu.** Connecte-toi comme opérateur et tape `/schedule`. Si `schedulerestart`
apparaît dans la complétion par tabulation, l'arbre de commandes est enregistré. Si tu n'es
pas niveau 2, il n'apparaîtra pas même quand le mod est chargé : fais donc ce test depuis
la console du serveur ou en tant qu'opérateur complet.

Une troisième vérification, plus lente, consiste à lancer `/schedulerestart 1` sur un
serveur de test et à regarder arriver les avertissements. Ne fais pas ça sur un serveur de
production pour confirmer une installation : il s'arrête une minute plus tard et tente de
se relancer.

La seule ligne de log que le mod écrit lui-même apparaît lors d'un redémarrage, juste avant
l'arrêt :

```text
[ScheduledRestart] Relancement : /usr/bin/java -Xmx8G -jar fabric-server-launch.jar nogui
```

Son absence après un compte à rebours en mode redémarrage signifie que la relance n'est
même pas allée jusqu'à construire une ligne de commande.
[Ce qui se passe à l'échéance](restart.fr.md) détaille les branches.

## Désinstallation

Arrête le serveur, supprime le jar, redémarre.

```bash
rm /chemin/vers/serveur/mods/ScheduleRestart-1.0.0+1.21.1.jar
```

**Rien ne reste derrière.** Aucun fichier de configuration à nettoyer, aucune donnée de
monde, aucun objectif de scoreboard, aucun NBT persistant, aucune entité, aucun item
enregistré qui se transformerait en `minecraft:air` au chargement suivant. Retirer ce mod
ne peut pas corrompre un monde, puisqu'il n'y a jamais rien écrit.

Une réserve opérationnelle : si tu retires le mod alors qu'une planification est armée,
celle-ci meurt avec la JVM que tu viens d'arrêter, donc rien ne se déclenchera plus tard.
Et si tu comptais sur ce mod pour ton redémarrage nocturne, souviens-toi qu'il n'a jamais
été ce qui effectuait le redémarrage sur une installation supervisée — c'était ton unité
systemd ou ton panel. Retirer le mod retire le compte à rebours et l'arrêt propre, pas le
superviseur.

Si tu retires **CheeseDash** en laissant ce mod installé, le serveur ne démarrera pas :

```text
Mod 'Scheduled Restart' (scheduledrestart) 1.0.0+1.21.1 requires any version of mod
'cheesedash', which is missing!
```

C'est la dépendance inutile qui mord. Retire les deux jars, ou garde les deux.

---

← [ScheduleRestart](../README.fr.md) · [Premiers pas](getting-started.fr.md) →
