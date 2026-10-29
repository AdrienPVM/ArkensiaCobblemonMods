# CheesePurse

> 🇬🇧 [Read in English](README.md)

> Un interrupteur dans l'écran d'inventaire qui vide les pièces Numismatic Overhaul de
> l'inventaire d'un joueur vers sa bourse, deux fois par seconde.

Numismatic Overhaul possède déjà un bouton « tout déposer » dans l'écran de sa bourse.
Un joueur qui mine pendant une heure se retrouve avec quatre piles de pièces de bronze
et doit penser à ouvrir sa bourse pour cliquer. CheesePurse supprime le « penser à » :
un bouton pièce de 12×12 se place près du coin supérieur gauche de l'écran
d'inventaire, et tant qu'il affiche la pièce dorée, une boucle côté serveur vide les
pièces de ce joueur dans sa bourse toutes les dix ticks.

Le chemin de dépôt est celui qu'utilise Numismatic pour son propre bouton :
`CurrencyHelper.getMoneyInInventory(player, true)` prend les pièces, puis
`ModComponents.CURRENCY.get(player).modify(value)` crédite la bourse. Les pièces
atterrissent dans le composant Cardinal Components que Numismatic conserve dans les
données du joueur, et le joueur reçoit la notification maison de Numismatic
`+ [3 Silver, 12 Bronze]` dans sa barre d'action. Rien ne change dans l'économie ; seuls
les clics disparaissent.

Le mod est volontairement minuscule, et cela se voit. Pas de fichier de configuration,
pas de commande, pas de module dashboard, et l'état de l'interrupteur vit dans un
`HashSet` en mémoire qui se vide au redémarrage du serveur. Lis
[les faiblesses connues](docs/architecture.fr.md#faiblesses-connues) avant de le poser
sur un serveur dont l'économie compte vraiment.

**Minecraft 1.21.1 · Fabric · Java 21 · 5 fichiers source, ~260 lignes**

---

## Prérequis

| | |
|---|---|
| **Obligatoire** | [Numismatic Overhaul](https://modrinth.com/mod/numismatic-overhaul) (compilé contre 0.3.4) · Fabric Loader ≥ 0.17.2 · Fabric API · Java 21 |
| **Optionnel** | rien |

Numismatic Overhaul est une dépendance stricte, déclarée `"numismatic-overhaul": "*"`
dans `fabric.mod.json`. Ni le serveur ni le client ne démarrent sans elle. Ses classes
sont importées directement plutôt qu'atteintes par réflexion : il n'existe donc aucun
mode dégradé de repli.

CheesePurse ne dépend **pas** de CheeseDash. Il n'enregistre aucun module dashboard et
n'expose aucune route REST.

## Installation

```bash
cd CheesePurse && ./gradlew build
```

Dépose `build/libs/numismatic-cheesepurse-1.0.0+1.21.1.jar` dans le `mods/` du serveur
et dans le `mods/` de chaque joueur, à côté de Numismatic Overhaul et de Fabric API. Le
log du serveur affiche alors :

```text
[NumismaticCheesePurse] Initializing…
[NumismaticCheesePurse] Ready.
```

Rien n'est écrit dans `config/`. Marche à suivre complète :
[installation](docs/installation.fr.md).

⚠️ **Le jar ne porte pas le nom du dossier.** Le dossier s'appelle `CheesePurse`,
l'identifiant du mod est `numismatic-cheesepurse`, et le jar est
`numismatic-cheesepurse-<version>+1.21.1.jar`. Aucun fichier nommé `cheesepurse-*.jar`
n'existe dans la sortie de build.
[Pourquoi](docs/installation.fr.md#les-trois-noms-de-ce-mod).

## Documentation

| Page | Contenu |
|---|---|
| [Installation](docs/installation.fr.md) | Prérequis, les trois noms de ce mod, ce dont chaque côté a besoin, vérifier le chargement, désinstallation propre |
| [Premiers pas](docs/getting-started.fr.md) | Un tutoriel : d'un serveur vierge jusqu'à voir les pièces quitter ta barre d'action rapide pour ta bourse |
| [Dépôt automatique](docs/auto-collect.fr.md) | La boucle en détail : ce qui compte comme pièce, le délai d'une demi-seconde, l'inventaire plein, le piège du retrait de bourse, la portée par joueur |
| [Configuration](docs/configuration.fr.md) | Il n'y a pas de fichier de configuration. Ce qui est codé en dur, quelle ligne modifier, et quelles options Numismatic changent ce que tu vois |
| [Architecture](docs/architecture.fr.md) | Cinq classes, un mixin client, le threading, l'état non persisté, et les résidus de l'ancien nom de projet |
| [Dépannage](docs/troubleshooting.fr.md) | Symptôme, cause, remède — dont la désynchronisation qui fait disparaître les pièces alors que le bouton est gris |

## Les commandes en un coup d'œil

Aucune. CheesePurse n'enregistre aucune commande et n'offre aucun contrôle côté
opérateur. Tout passe par le bouton d'inventaire, que chaque joueur pilote pour
lui-même.

## Avertissements honnêtes

- **L'état de l'interrupteur ne survit pas à un redémarrage.** Le serveur le garde en
  mémoire. Après un redémarrage, chaque joueur est désactivé côté serveur alors que son
  client peut encore dessiner la pièce dorée. Voir
  [le symptôme de désynchronisation](docs/troubleshooting.fr.md#le-bouton-est-gris-mais-mes-pièces-disparaissent-quand-même).
- **La boucle balaie tout l'inventaire, pas seulement ce qui vient d'être ramassé.** Les
  pièces que tu retires de ta propre bourse y retournent en moins d'une demi-seconde
  tant que l'interrupteur est actif. Cela surprend.
  [Détails](docs/auto-collect.fr.md#le-piège-du-retrait-de-bourse).
- **Il compile contre Numismatic Overhaul 0.3.4 avec des imports directs.** Un
  renommage de `CurrencyHelper`, `ModComponents` ou `CurrencyComponent.modify` dans une
  version future de Numismatic casse le mod au chargement de classe, sans dégradation.
- Le chemin nominal a tourné sur le serveur Arkensia. Les modes de défaillance listés
  dans [dépannage](docs/troubleshooting.fr.md) ont été lus dans le code ; ils n'ont pas
  tous été reproduits volontairement.

## Licence

[PolyForm Noncommercial 1.0.0](../LICENSE), avec une permission additionnelle pour les
opérateurs de serveur. Tu peux le faire tourner sur un serveur qui reçoit des dons ou
vend des rangs ; tu ne peux pas vendre le mod lui-même.

Le `fabric.mod.json` de ce mod déclare `PolyForm-Noncommercial-1.0.0` et il n'existe
aucun fichier `LICENSE` distinct dans `CheesePurse/` : il relève donc des mêmes termes
que le reste du dépôt. Numismatic Overhaul, dont il dépend et contre lequel il compile,
est une œuvre distincte sous licence MIT, par glisco, Pois1x et Noaaan. Rien ici ne la
relicencie, et aucun code de Numismatic n'est copié dans ce mod.

## Support

- Bugs → [GitHub Issues](https://github.com/AdrienPVM/ArkensiaCobblemonMods/issues)
- Questions → [Discord Arkensia](https://discord.gg/fYmPwMBjdB)
