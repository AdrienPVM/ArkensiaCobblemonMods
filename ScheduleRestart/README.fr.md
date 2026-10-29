# ScheduleRestart

> 🇬🇧 [Read in English](README.md)

> Planifie un redémarrage ou un arrêt du serveur avec un compte à rebours visible par les joueurs.

Une commande, une minuterie, un seul fichier source. Tu tapes `/schedulerestart 10` et
tout le monde reçoit immédiatement un avertissement en gras, puis un rappel chaque minute
jusqu'à la dernière, puis un compte à rebours à la seconde sur les dix dernières
secondes. À l'échéance, le serveur sauvegarde et s'arrête, et le mod tente de se
relancer dans un nouveau processus JVM.

Le mot « tente » porte tout le poids de la phrase. La relance reconstruit une ligne de
commande `java -jar … nogui` à partir du processus en cours et la démarre avec
`ProcessBuilder`. Ça fonctionne sur une installation lancée par un simple
`java -jar fabric-server-launch.jar nogui`, et ça ne fonctionne pas sur la plupart des
panels d'hébergement. Lis [ce qui se passe à l'échéance](docs/restart.fr.md) avant de
brancher ce mod sur un serveur de production, parce que le mode de défaillance est « le
serveur s'arrête et ne revient jamais ».

Tout ce que voient les joueurs est du français écrit en dur dans le code, sans fichier de
langue. C'est une limite, pas un choix de confort : sur un serveur anglophone, tes joueurs
liront quand même « ⚠ Redémarrage du serveur dans 10 minutes ! ». Pour changer ça, il faut
modifier `ScheduledRestart.java` et recompiler.

**Minecraft 1.21.1 · Fabric · Java 21 · 1 fichier source, 171 lignes**

---

## Prérequis

| | |
|---|---|
| **Requis** | Fabric Loader ≥ 0.16.0 · Fabric API · Java 21 · [CheeseDash](../CheeseDash/) |
| **Côté** | Serveur uniquement (`"environment": "server"`) — les clients n'ont besoin de rien |

⚠️ La dépendance à CheeseDash est bien réelle, mais elle ne sert à rien.
`fabric.mod.json` déclare `cheesedash` dans `depends`, donc Fabric refuse de démarrer
sans lui, alors que le mod n'enregistre aucun module CheeseDash et ne touche pas une
seule de ses classes. C'est une dépendance qu'il faut satisfaire sans rien recevoir en
échange. Voir
[la dépendance qui ne sert à rien](docs/architecture.fr.md#la-dépendance-qui-ne-sert-à-rien).

Fabric API, lui, *est* utilisé (`CommandRegistrationCallback`) mais n'est **pas déclaré**
dans `fabric.mod.json`. Sans lui, le mod meurt sur un `NoClassDefFoundError` au lieu d'un
message lisible de dépendance manquante.

## Installation

```bash
cd ScheduleRestart && ./gradlew build      # gradlew.bat sous Windows
```

Dépose `build/libs/ScheduleRestart-1.0.0+1.21.1.jar` dans le `mods/` du serveur, à côté
du jar CheeseDash et de Fabric API. Rien ne va côté client. Aucun fichier de
configuration n'est créé au premier démarrage, et rien n'est jamais écrit sur le disque.

Le jar porte le nom du dossier, pas celui de l'identifiant du mod — détails dans
[installation](docs/installation.fr.md).

## Documentation

| Page | Contenu |
|---|---|
| [Installation](docs/installation.fr.md) | Prérequis, le piège du nom de jar, ce que le premier démarrage crée ou ne crée pas, comment vérifier le chargement, désinstallation propre |
| [Premiers pas](docs/getting-started.fr.md) | Un tutoriel : planifier un arrêt à deux minutes, observer chaque message, puis mettre en place un superviseur pour qu'un redémarrage redémarre vraiment |
| [Commandes](docs/commands.fr.md) | `/schedulerestart` argument par argument, le niveau de permission, le piège du mot-clé `stop`, tous les messages produits |
| [Le compte à rebours](docs/countdown.fr.md) | La chronologie exacte : à quel moment chaque avertissement part, son texte, sa couleur et l'arithmétique derrière |
| [Ce qui se passe à l'échéance](docs/restart.fr.md) | `server.stop(false)`, l'auto-relance, comment le jar est localisé, et pourquoi un panel d'hébergement casse tout |
| [Configuration](docs/configuration.fr.md) | Il n'y a aucun fichier de configuration. Ce qui est écrit en dur à la place, et quelle ligne modifier pour chaque comportement |
| [Architecture](docs/architecture.fr.md) | La classe unique, le modèle `java.util.Timer`, le threading, la dépendance inutilisée, les faiblesses connues |
| [Dépannage](docs/troubleshooting.fr.md) | Symptôme, cause, remède — dont le jar de 261 octets et le serveur qui ne revient jamais |

## Les commandes en un coup d'œil

Niveau de permission 2 requis.

| Commande | Effet |
|---|---|
| `/schedulerestart <minutes>` | annonce, compte à rebours, puis sauvegarde, arrêt et tentative de relance |
| `/schedulerestart <minutes> stop` | annonce, compte à rebours, puis sauvegarde et arrêt, sans relance |

`minutes` accepte **1 à 60** inclus. Le second argument signifie « arrête » uniquement
s'il vaut littéralement le mot `stop`, dans n'importe quelle casse. Tout autre mot est
accepté et veut silencieusement dire « redémarre » : `/schedulerestart 5 shutdown`
relance donc le serveur.

Il n'existe qu'une seule planification à la fois, et une nouvelle commande annule la
précédente. **Il n'y a aucune commande d'annulation.** La seule façon de renoncer à une
planification que tu regrettes est d'en lancer une plus longue, puis d'arrêter le serveur
à la main avant qu'elle ne tombe.

## État réel du mod

Ce mod tourne sur le serveur Arkensia et y fait ce qu'il annonce, dans une configuration
de lancement précise : `java -jar fabric-server-launch.jar nogui`, sans panel. Le chemin
de relance est la partie fragile, et il n'a jamais été testé chez plusieurs hébergeurs.
Les défauts connus sont listés dans
[les faiblesses connues](docs/architecture.fr.md#faiblesses-connues) plutôt que cachés.

## Licence

[PolyForm Noncommercial 1.0.0](../LICENSE), avec une permission additionnelle pour les
opérateurs de serveur. Tu peux le faire tourner sur un serveur qui reçoit des dons ou
vend des rangs ; tu ne peux pas vendre le mod lui-même.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Discord Arkensia](https://discord.gg/fYmPwMBjdB)
