[← CheeseCompatModsFix](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](getting-started.md)

# Premiers pas

À la fin de ce parcours, tu auras le mod construit et en marche sur un serveur vierge, tu
sauras **lesquelles** de ses familles de correctifs se sont réellement activées sur ta pile
de mods, et tu en auras désactivé un et vu l'effet arriver en jeu.

Cette dernière partie compte plus qu'elle n'en a l'air. Presque tout ce que fait ce mod est
invisible quand ça marche — un crash qui ne se produit pas, une équipe qui revient correcte.
La bascule de l'Arc Phone est l'un des très rares correctifs à produire un résultat visible,
immédiat et réversible, ce qui en fait la bonne chose à tester en premier : si tu arrives à
faire apparaître et disparaître ce message, tu sais que la couche mixin est vivante.

Compte une vingtaine de minutes, dont l'essentiel en démarrage de serveur.

---

## 1. Vérifie ton Java

```bash
java -version
```

```text
openjdk version "21.0.5" 2024-10-15
```

Tout ce qui est sous 21 ne compilera pas ce mod et ne le fera pas tourner.

## 2. Mets les deux jars CurseForge en place

Le build refuse de démarrer sans eux. Depuis CurseForge, télécharge le fichier **Fabric
1.21.1** de FTB Quests et de StarAcademy, et dépose les deux dans `localdeps/` à la racine
du dépôt, avec exactement ces noms :

```text
localdeps/ftb-quests-fabric-2101.1.13.jar
localdeps/academy-mc1.21.1-v1.4.0+build.467-fabric.jar
```

Vérifie :

```bash
ls localdeps/
```

```text
README.md
academy-mc1.21.1-v1.4.0+build.467-fabric.jar
ftb-quests-fabric-2101.1.13.jar
```

S'il en manque un, l'étape 3 s'arrête avec un message qui le nomme. C'est le comportement
voulu, pas un bug — voir
[pourquoi ces deux-là ne peuvent pas être téléchargés pour toi](installation.fr.md#-le-build-exige-deux-jars-que-tu-dois-fournir).

## 3. Construis

```bash
cd CheeseCompatModsFix
./gradlew build
```

Le premier passage télécharge Minecraft, les mappings Yarn et seize jars de mods : compte
quelques minutes. Tu cherches :

```text
BUILD SUCCESSFUL in 2m 14s
```

puis :

```bash
ls build/libs/
```

```text
cheesecompatmodsfix-1.2.53+1.21.1.jar
cheesecompatmodsfix-1.2.53+1.21.1-sources.jar
```

Prends le premier. Ignore le jar de sources.

Si le build a échoué, il a presque certainement échoué à la phase de configuration sur
l'étape 2, et le message dit quel fichier il voulait.

## 4. Dépose-le dans un serveur

Ce mod seul n'a besoin de rien d'autre que Fabric API. L'étape 8, en revanche, a besoin de
Legendary Monuments, et Legendary Monuments 7.2 ne se charge pas seul : son
`fabric.mod.json` exige Fabric Loader 0.16.10 ou plus, Cobblemon, MegaShowdown, Trinkets,
Chipped et CobbleFurnies. La plus petite pile qui fait tourner ce tutoriel est donc :

```text
mods/
├── fabric-api-<version>+1.21.1.jar
├── cobblemon-<version>.jar
├── mega_showdown-<version>.jar
├── trinkets-<version>.jar
├── chipped-<version>.jar          (et ce que Chipped exige lui-même)
├── cobblefurnies-<version>.jar
├── LegendaryMonuments-7.2.jar
└── cheesecompatmodsfix-1.2.53+1.21.1.jar
```

Si tu en oublies un, Fabric refuse de démarrer et nomme celui qui manque.

Démarre-le :

```bash
java -Xmx4G -jar fabric-server-launch.jar nogui
```

## 5. Trouve la ligne qui prouve le chargement

Dans la console, ou dans `logs/latest.log` :

```text
[CheeseCompat] Loaded (v1.2.53). Arc Phone heal=enabled, PC=enabled, Sanctuary fix=ON
```

C'est `onInitialize()` qui atteint sa dernière instruction. Si elle est absente, le mod ne
s'est pas chargé du tout — saute à
[le mod ne se charge pas](troubleshooting.fr.md#symptôme--le-mod-ne-se-charge-pas).

Note ce que cette ligne ne te dit **pas** : si un correctif s'est appliqué. Elle s'affiche
sans condition.

## 6. Découvre quels correctifs se sont réellement activés

C'est l'étape que les gens sautent, avant de passer une heure à se demander pourquoi un
correctif ne marche pas.

```bash
grep CheeseCompat logs/latest.log
```

Sur la pile de l'étape 4, les lignes qui comptent sont ces trois-là, dans cet ordre :

```text
[CheeseCompat][MegaPatch] Added 1 aspect(s) to mega_aspects (now 4 entries: [...])
[CheeseCompat][MegaStoneRemap] Registered 70 CacademyMons stone remaps (includes lucarionitez=261023, ...)
[CheeseCompat] Loaded (v1.2.53). Arc Phone heal=enabled, PC=enabled, Sanctuary fix=ON
```

Les deux premières sont les rustines MegaShowdown et Cobblemon, qui tournent juste avant la
ligne `Loaded`. Il n'y a pas de ligne `Restricted /stopbattle <player> to operators` : elle
n'apparaît que si Battle Tower a enregistré une commande `stopbattle` avec des nœuds
d'argument à restreindre. (`grep` peut renvoyer quelques lignes de plus, parce que certaines
classes journalisent sous un logger nommé `CheeseCompatModsFix`.)

Ajoute CobbleSafari et redémarre, et des lignes comme celles-ci apparaissent :

```text
[CheeseCompat] Registered cobblemon:tatami_mat and tatami_block for CobbleSafari compatibility
[CheeseCompat] Safari dimensional ban events registered (Cobblemon 1.6.x compat)
[CheeseCompat] Safari ball fix registered for CobbleSafari dimensions
[CheeseCompat] Slingshot block registered for CobbleSafari dimensions
[CheeseCompat] Dungeon capture block registered for dungeon_underground
[CheeseCompat] Registered CobbleSafari portal frame and portal blocks
[CheeseCompat] Registered CobbleSafari spawn influences (1.6.1 compat)
```

**La règle à retenir : une ligne manquante signifie un correctif manquant.** Le plugin mixin
conditionne tout à `FabricLoader.isModLoaded(<id>)`, et un mod dont l'id ne correspond pas à
la chaîne que le code attend est, du point de vue de ce mod, non installé. Les ids qu'il
interroge sont listés dans
[installation](installation.fr.md#optionnels--les-mods-quil-corrige).

## 7. Confirme l'enregistrement de l'arbre de commandes

Rejoins le serveur et exécute :

```mcfunction
/stopbattle
```

```text
Battle stop requested.
```

Texte vert, immédiatement. Tu n'es pas en combat, donc rien ne se passe au-delà du message —
la commande annonce *requested*, demandé, pas *arrêté*, et ne vérifie rien. Le voir signifie
qu'`onInitialize()` a enregistré sa commande et que le module de l'API de commandes de
Fabric est présent. Détails dans [commandes](commands.fr.md#stopbattle).

## 8. Désactive un correctif et regarde l'effet arriver

Maintenant la partie à résultat visible. L'Arc Phone, de Legendary Monuments, soigne
l'équipe entière d'un joueur n'importe où, gratuitement. Beaucoup de serveurs n'en veulent
pas.

Confirme d'abord le comportement actuel. En jeu, ouvre l'Arc Phone et soigne ton équipe. Ça
marche, en silence, comme le mod l'entend.

Arrête le serveur. Ouvre la config :

```text
config/cheesecompatmodsfix.json
```

```json
{
  "arcPhoneHealEnabled": true,
  "arcPhonePCEnabled": true,
  "sanctuaryOverflowFix": true
}
```

Passe la première valeur à `false` :

```json
{
  "arcPhoneHealEnabled": false,
  "arcPhonePCEnabled": true,
  "sanctuaryOverflowFix": true
}
```

Démarre le serveur. La ligne de démarrage dit maintenant :

```text
[CheeseCompat] Loaded (v1.2.53). Arc Phone heal=DISABLED, PC=enabled, Sanctuary fix=ON
```

Rejoins, ouvre l'Arc Phone, appuie sur soigner. Au lieu d'un soin, tu obtiens, en rouge :

```text
Le soin via l'Arc Phone est désactivé sur ce serveur.
```

```text
Arc Phone healing is disabled on this server.
```

(Le second est ce que voit un client anglais — le message est une clé de traduction, donc
chaque joueur l'obtient dans sa propre langue.)

Le bouton de soin est toujours là et toujours pressable : seul le gestionnaire de paquet
**côté serveur** est bloqué. Cette asymétrie est normale pour une rustine côté serveur et
vaut la peine d'être intériorisée — ce mod ne change jamais une interface client pour
laquelle il n'a pas de mixin client.

**Le serveur doit être redémarré.** Il n'y a pas de rechargement pour cette config ; elle
est lue une fois à l'init. Si tu as modifié le fichier pendant que le serveur tournait, rien
ne s'est encore passé : la modification reste dans le fichier et prend effet au démarrage
suivant. L'éditer serveur arrêté, puis démarrer, est la façon propre de faire.

Si le message n'apparaît pas après un redémarrage, voir
[une clé de configuration n'a aucun effet](troubleshooting.fr.md#symptôme--une-clé-de-configuration-na-aucun-effet) —
la cause probable est que le point d'injection nommé comme une lambda ne correspond plus à
ton build de Legendary Monuments, et que `defaultRequire: 0` a rendu cet échec silencieux.

## 9. Remets la bascule

```json
{
  "arcPhoneHealEnabled": true,
  "arcPhonePCEnabled": true,
  "sanctuaryOverflowFix": true
}
```

Redémarre. La ligne de démarrage dit de nouveau `heal=enabled` et l'Arc Phone soigne.

Tu viens d'exercer toute la chaîne : build, chargement, filtrage, configuration, mixin,
message visible par le joueur.

---

## Où aller ensuite

Tu as vu la seule partie de ce mod qui se comporte comme une fonctionnalité. Tout le reste
est une rustine, et la lecture utile est le catalogue — une entrée par correctif, avec le mod
et la version visés, le symptôme qu'il traite, le moyen employé, et s'il est encore actif :

- [Battle Tower](fixes-battle-tower.fr.md) — douze correctifs autour de la sauvegarde, de
  la restauration d'équipe et de la géométrie de l'arène. La plus grosse famille.
- [Cobblemon et les combats](fixes-cobblemon.fr.md) — méga-évolution, raids, alphas, Rad
  Gyms, et les deux correctifs **désactivés ou refusés** après avoir cassé la production. À
  lire avant d'écrire quoi que ce soit de similaire.
- [Safari, Academy et boss](fixes-safari-and-bosses.fr.md) — CobbleSafari 0.1.5 rétroporté
  sur Cobblemon 1.6.1, et les patches bytecode pour Cobblewarden Boss.
- [Daycare+](fixes-daycare.fr.md) — neuf mixins qui réécrivent les règles de reproduction.
  ⚠️ Un nouveau pâturage produit un œuf puis s'arrête, l'inverse du défaut de Daycare+.
- [Tout le reste](fixes-misc.fr.md) — six autres mods, dix correctifs à eux tous.

Puis [architecture](architecture.fr.md) pour comprendre le filtrage et ce qui est connu
comme fragile, et [dépannage](troubleshooting.fr.md) quand quelque chose ne se produit pas
en silence — ce qui, avec `defaultRequire: 0`, est le mode d'échec normal ici.

---

← [Installation](installation.fr.md) · [Commandes](commands.fr.md) →
