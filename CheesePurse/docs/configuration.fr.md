[← CheesePurse](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](configuration.md)

# Configuration

**CheesePurse n'a pas de fichier de configuration.** Rien n'est écrit dans `config/`,
rien n'y est lu, et `FabricLoader.getInstance().getConfigDir()` n'apparaît nulle part
dans le code source. Chercher `config/cheesepurse.json` ou
`config/numismatic-cheesepurse.json` est peine perdue : aucun des deux n'a jamais
existé.

Chaque nombre qui gouverne le comportement du mod est une constante de compilation.
Cette page les liste, indique quel fichier et quelle ligne modifier, puis traite des
réglages qui, eux, changent ce que tu vois — et qui appartiennent à Numismatic Overhaul,
pas à ce mod.

---

## Les valeurs codées en dur

| Valeur | Où | Défaut | Effet |
|---|---|---|---|
| `SCAN_INTERVAL_TICKS` | `NumismaticCheesePurse.java` | `10` | Ticks serveur entre deux balayages. 10 ticks font 0,5 s à 20 TPS. |
| `AutoCollectButton.SIZE` | `AutoCollectButton.java` | `12` | Largeur et hauteur du bouton en pixels, et aussi la taille du rectangle source utilisé pour dessiner la texture. |
| X du bouton | `InventoryScreenMixin.java` | `guiLeft + 27` | Calculé par `guiLeft + 32 - SIZE / 2 + 1`. |
| Y du bouton | `InventoryScreenMixin.java` | `guiTop + 9` | Calculé par `guiTop + 76 - SIZE - 55 - 27 + 27`. |
| État client initial | `NumismaticCheesePurseClient.java` | `false` | `autoCollectEnabled`, le booléen à partir duquel le bouton se dessine. |

### Changer l'intervalle de balayage

```java
// NumismaticCheesePurse.java
private static final int SCAN_INTERVAL_TICKS = 10;
```

L'augmenter rend les dépôts plus paresseux et la boucle moins coûteuse ; le descendre à
`1` balaie à chaque tick, soit vingt parcours d'inventaire par seconde et par joueur
inscrit, et vingt messages de barre d'action possibles. `1` est un réglage légitime sur
un petit serveur si tu veux que le dépôt paraisse instantané, et une mauvaise idée avec
cinquante joueurs.

Le mettre à `0` ou à un nombre négatif fait que le garde-fou
`if (tickCounter < SCAN_INTERVAL_TICKS) return;` ne déclenche jamais de sortie
anticipée : le balayage tourne à chaque tick, comme avec `1`, mais par accident plutôt
que par intention. Il n'y a aucune validation ; le champ est `private static final` et
ce que tu compiles est ce qui tourne.

Le compteur est un champ d'instance unique partagé par tout le serveur : l'intervalle
est donc global. Aucun moyen de donner à un joueur un balayage plus rapide qu'à un
autre.

### Changer la position du bouton

```java
// InventoryScreenMixin.java
int btnX = guiLeft + 32 - s / 2 + 1;
int btnY = guiTop  + 76 - s - 55 - 27 + 27;
```

L'expression en Y est un reliquat arithmétique de plusieurs séries d'ajustements :
`76 - 12 - 55 - 27 + 27` vaut `9`. Elle n'est pas fausse, elle consigne juste un
historique dont personne n'a besoin. Si tu déplaces le bouton, remplacer toute
l'expression par un décalage simple est plus clair que d'ajouter un terme de plus à la
chaîne.

Les coordonnées sont relatives au coin supérieur gauche de l'interface d'inventaire, que
le mixin recalcule à partir de la taille vanilla codée en dur de 176×166. Un pack de
ressources qui change les dimensions de la texture d'inventaire placera le bouton au
mauvais endroit, et aucun hook ne permet d'en informer le mod.

### Changer les textures

Deux PNG de 12×12, à des chemins que le code fixe en dur :

```text
src/main/resources/assets/numismatic-cheesepurse/textures/gui/
├── auto_collect_on.png     dessiné quand l'interrupteur est actif
└── auto_collect_off.png    dessiné quand il est inactif
```

`AutoCollectButton.renderWidget` les dessine avec
`context.drawTexture(tex, getX(), getY(), 0, 0, SIZE, SIZE, SIZE, SIZE)`, où les deux
derniers arguments sont la largeur et la hauteur totales du fichier de texture.
Remplacer les PNG par des images d'une autre taille sans changer `SIZE` donne un bouton
étiré ou rogné, parce que le rectangle source, le rectangle de destination et les
dimensions déclarées du fichier sont tous la même constante.

Aucun fond, aucune bordure et aucun état de survol ne sont dessinés : `renderWidget` est
redéfini pour ne tracer que la texture, si bien que le cadre de bouton vanilla
n'apparaît jamais. Une texture aux bords transparents se lit comme une icône flottante,
ce que font les textures fournies.

## Le fichier de langue

Deux clés, dans `src/main/resources/assets/numismatic-cheesepurse/lang/` :

| Clé | `fr_fr.json` | `en_us.json` |
|---|---|---|
| `numismaticheesepurse.button.tooltip.enabled` | `Cheesepurse : ACTIVÉE\nLes pièces ramassées sont automatiquement déposées dans votre bourse.` | `Cheesepurse: ON\nCoins picked up are automatically deposited into your purse.` |
| `numismaticheesepurse.button.tooltip.disabled` | `Cheesepurse : DÉSACTIVÉE\nCliquez pour déposer automatiquement les pièces dans votre bourse.` | `Cheesepurse: OFF\nClick to automatically deposit picked-up coins into your purse.` |

Ces deux chaînes constituent la totalité du texte du mod destiné au joueur. Tout le
reste de ce qu'il lit vient de Numismatic Overhaul.

Seuls `en_us` et `fr_fr` sont fournis. Toute autre locale retombe sur `en_us`, ce qui
est le comportement vanilla pour une traduction manquante. Ajouter une langue consiste à
poser un `<locale>.json` à côté de ces deux-là avec les mêmes deux clés ; aucun
changement de code n'est nécessaire.

⚠️ Les deux chaînes décrivent le comportement comme s'appliquant aux pièces
« ramassées ». Comme l'explique
[dépôt automatique](auto-collect.fr.md#le-piège-du-retrait-de-bourse), le balayage prend
toutes les pièces de l'inventaire, quelle qu'en soit l'origine. Les infobulles
sous-estiment ce que fait la fonctionnalité, et un serveur qui s'est fait piéger par le
retrait de bourse voudra peut-être les reformuler.

---

## Les réglages qui, eux, changent ce que tu vois

Ceux-ci appartiennent à Numismatic Overhaul et se configurent dans ses propres options
de mod, par client, via Mod Menu ou `config/numismatic-overhaul.json5`.

| Clé Numismatic | Défaut | Effet sur CheesePurse |
|---|---|---|
| `moneyMessageLocation` | `ACTIONBAR` | Où apparaît la notification `+ [3 Silver]` de chaque balayage : `ACTIONBAR`, `CHAT`, ou `DISABLED` pour aucune. Réglée par client et synchronisée vers le serveur. Le commentaire de Numismatic précise qu'il faut se reconnecter pour l'appliquer. |
| `generateCurrencyInChests` | `true` | Si les coffres de butin contiennent des pièces, ce qui constitue l'essentiel de ce qu'il y a à collecter sur un serveur survie. Redémarrage requis. |
| `enableVillagerTrading` | `true` | Si les villageois échangent en monnaie Numismatic, l'autre source principale de pièces. Redémarrage requis. |
| `lootOptions.*` | variable selon la structure | Combien d'argent rapporte chaque type de structure. Change la fréquence à laquelle un balayage a quelque chose à faire. |

Rien dans CheesePurse ne lit l'une de ces valeurs. Elles sont listées parce que, quand
un propriétaire de serveur demande comment arrêter le spam, la réponse est
`moneyMessageLocation`, et elle n'est pas dans ce mod.

## Un exemple annoté, pour le fichier de configuration qui n'existe pas

Si tu veux en ajouter un, la forme ci-dessous est le minimum qui vaudrait la peine.
Ce n'est **pas** un fichier que le mod lit — l'écrire ne change rien — et il n'est montré
que pour souligner que chaque valeur ci-dessous est aujourd'hui une constante en Java.

```json5
{
  // Ticks serveur entre deux balayages d'inventaire. Aujourd'hui SCAN_INTERVAL_TICKS
  // dans NumismaticCheesePurse.java. 20 donnerait un dépôt perçu comme une fois par seconde.
  "scanIntervalTicks": 10,

  // Si les joueurs conservent leur interrupteur à travers un redémarrage. Aujourd'hui
  // impossible : AUTO_COLLECT_PLAYERS est un simple HashSet en mémoire, sans sauvegarde.
  "persistToggle": false,

  // Si les bourses d'argent sont balayées avec les pièces en vrac. Aujourd'hui toujours vrai,
  // parce que MoneyBagItem implémente CurrencyItem et que le scan teste l'interface.
  "collectMoneyBags": true
}
```

Implémenter la première clé est un changement de deux lignes. La deuxième est le vrai
travail — voir [les faiblesses connues](architecture.fr.md#faiblesses-connues) pour les
deux approches qui le feraient correctement.

---

← [Dépôt automatique](auto-collect.fr.md) · [Architecture](architecture.fr.md) →
