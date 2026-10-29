[← CheeseMusic](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](playback.md)

# Lecture

Tout ce qui est sur cette page tourne sur le client. Le travail du serveur s'arrête quand
le paquet part ([musique de zone](zone-music.fr.md#le-paquet)) ; à partir de là, une
unique instance de `ZoneMusicPlayer` par client décide de ce qui est audible.

La contrainte de conception qui façonne tout le reste : **trois sources musicales ne
doivent jamais se superposer**. Le `MusicTracker` vanilla, ReactiveMusic et la piste propre
à CheeseMusic veulent le même canal. Plutôt que de les mélanger, le lecteur fait tourner
une machine à états qui fait taire les deux autres, attend la confirmation qu'elles sont
muettes, et ne lance sa propre piste qu'ensuite.

---

## La machine à états

Quatre états, dans `ZoneMusicPlayer` :

```text
IDLE ──play──> FADING_RM_OUT ──RM muet──> PLAYING
  ^                  │                      │
  │                  └──────stop──────┐     │stop
  │                                   v     v
  └────RM rétabli──── FADING_RM_IN <────────┘
```

| État | Ce qui est audible | Ce qu'il attend |
|---|---|---|
| `IDLE` | la musique vanilla, ou ReactiveMusic | un paquet `play` |
| `FADING_RM_OUT` | ReactiveMusic, en train de descendre à zéro | `ReactiveMusicHook.isFadeOutDone()` |
| `PLAYING` | une piste de zone | la fin de la piste, ou un paquet |
| `FADING_RM_IN` | ReactiveMusic, qui remonte depuis zéro | `ReactiveMusicHook.isFadeInDone()` |

`isActive()` vaut `state != IDLE`, et ce booléen unique est ce que lit le
[mixin](#le-mixin-de-musique-vanilla). La musique vanilla est donc étouffée pendant toute
la transition, et pas seulement pendant qu'une piste joue.

### Entrer dans une région

Sur un `play` en `IDLE` :

1. Analyser la playlist. Une liste vide annule tout et laisse l'état à `IDLE`.
2. Mémoriser le volume.
3. `ReactiveMusicHook.startFadeOut(fadeTicks)`.
4. `MinecraftClient.getInstance().getMusicTracker().stop()`, dans un `try` qui avale tout.
5. Passer à `FADING_RM_OUT`.

Ensuite, à chaque tick client, dès qu'`isFadeOutDone()` est vrai, choisir une piste, la
jouer, armer un délai de garde et passer à `PLAYING`. Si la playlist s'avère n'avoir aucun
index jouable, remonter ReactiveMusic à la place et passer à `FADING_RM_IN`.

Un `play` qui arrive alors qu'on est déjà en `FADING_RM_OUT` ou en `PLAYING` ne relance pas
la transition. Il remplace la playlist et le volume, de sorte que la **prochaine** piste
obéit à la nouvelle assignation pendant que la piste en cours se termine. C'est ce qui fait
qu'un trajet entre deux régions assignées sonne comme un changement de playlist plutôt que
comme une coupure, et c'est aussi pour cela qu'éditer une assignation depuis le dashboard
n'interrompt personne.

Un `play` qui arrive en `FADING_RM_IN` — un joueur qui est sorti d'une région et y revient
aussitôt — est mis de côté dans `pendingTracksJson` et rejoué une fois le fondu d'entrée
terminé. La transition se termine complètement avant que la nouvelle commence. À l'oreille,
cela veut dire un bref retour de ReactiveMusic. Pas joli, mais cela garde le raisonnement
de la machine à états en un seul fil.

### Sortir d'une région

Sur un `stop` :

- depuis `PLAYING` : couper la piste de zone, `startFadeIn(max(fadeTicks, 20))`, passer à
  `FADING_RM_IN` ;
- depuis `FADING_RM_OUT` : la piste de zone n'a jamais démarré, donc simple marche
  arrière — remonter ReactiveMusic et passer à `FADING_RM_IN` ;
- depuis `FADING_RM_IN` ou `IDLE` : rien.

La piste de zone est **coupée, pas estompée**. `stopZoneMusic()` appelle
`SoundManager.stop` sur l'instance et l'audio s'arrête à cet instant.

### Les fondus sont ceux de ReactiveMusic, pas les tiens

C'est la partie du mod la plus mal comprise, donc autant être direct.

`fadeInTicks` et `fadeOutTicks` n'estompent jamais une piste CheeseMusic. Ils règlent le
temps que met ReactiveMusic à s'écarter, et celui qu'il met à revenir. La piste de zone,
elle, démarre brutalement au `volume` plein et s'arrête brutalement.

Trois détails supplémentaires découlent du code :

- Les deux ont un plancher de 20 ticks : `Math.max(fadeTicks, 20)`. Une valeur de `5` se
  comporte comme `20`.
- `play` range son propre `fadeTicks` dans le champ nommé `fadeOutTicks`, avec le
  commentaire *« We'll use the same value for both directions as approximation »*. Ce champ
  n'est ensuite utilisé que sur un chemin d'échec. La vraie durée du fondu de sortie est
  celle que le serveur envoie sur le `stop`, et elle vient bien des `fadeOutTicks` de
  l'assignation. Le nommage à l'intérieur de la classe est trompeur ; le comportement de
  bout en bout est correct.
- `ZoneMusicSoundInstance` possède une méthode `setMusicVolume` conçue pour des changements
  de gain en douceur. Personne ne l'appelle. Le volume est figé pour la durée de vie d'une
  piste.

Donc si tu veux qu'une piste monte en fondu, fais le fondu dans le fichier `.ogg`. Le mod
ne le fera pas pour toi.

---

## Avancement de la playlist

En `PLAYING`, le lecteur surveille `SoundManager.isPlaying(currentSound)` et avance quand
cela devient faux. `pickNextIndex` implémente les trois modes, comme décrit dans
[configuration](configuration.fr.md#mode). `SHUFFLE` remélange en plus la liste quand elle
arrive au bout, de sorte qu'un sac n'est jamais rejoué dans le même ordre.

### Le délai de garde de 100 ticks

Un son streamé fraîchement démarré n'est pas signalé comme en lecture immédiatement, et un
rechargement de resource pack peut le tuer net. `trackStartCooldown = COOLDOWN_TICKS` (100
ticks, cinq secondes) couvre cette fenêtre :

- pendant le délai, `isPlaying` n'est pas consulté du tout ;
- au tick où le délai tombe à zéro, si le son n'est **pas** en lecture, la même piste est
  relancée et le délai réarmé.

Cette protection a une conséquence. Une piste de moins de cinq secondes est déjà terminée
quand le délai expire : elle est donc relancée — indéfiniment. Garde les pistes de zone
bien au-delà de cinq secondes. Un jingle court a sa place dans un `/playsound`, pas dans
une assignation.

Aucun délai n'est appliqué entre les pistes d'une playlist, seulement au premier démarrage
après une transition, ce qui veut dire qu'un son mort en milieu de playlist n'est pas
récupéré.

---

## Coopération avec ReactiveMusic

`ReactiveMusicHook` est de la réflexion sur les internes de ReactiveMusic :

| Membre | Classe | Usage |
|---|---|---|
| `thread` (champ statique) | `circuitlord.reactivemusic.ReactiveMusic` | l'objet du thread de lecture |
| `gainPercentage` (champ) | la classe de ce thread | le volume que CheeseMusic fait varier |
| `resetPlayer()` (méthode) | la classe de ce thread | abandonner le morceau courant de ReactiveMusic |
| `additionalSilence` (champ statique) | `ReactiveMusic` | mis à `999999` pour l'empêcher de choisir un nouveau morceau |

Le fondu de sortie sauvegarde le gain courant et l'`additionalSilence` courant, met
`additionalSilence` à `999999`, puis fait descendre le gain de `savedGain / ticks` par tick.
Arrivé à zéro, il appelle `resetPlayer()` pour tuer le morceau net.

Le fondu d'entrée restaure `additionalSilence`, appelle `resetPlayer()` pour qu'un nouveau
morceau soit choisi, puis fait remonter le gain jusqu'à la valeur sauvegardée.

Entre les fondus, tant que `suppressed` est encore vrai, chaque tick réaffirme
`additionalSilence = 999999` et `gainPercentage = 0`. ReactiveMusic remet son propre gain
quand il choisit un morceau : sans cette réaffirmation, il récupérerait son volume.
`forceReset()`, à la déconnexion, restaure les deux champs d'un coup, sans rampe.

Chaque accès par réflexion est enveloppé, de sorte qu'un échec consigne au pire un
avertissement. `isAvailable()` retente l'initialisation à chaque appel jusqu'à réussir,
parce que le champ `thread` de ReactiveMusic est nul jusqu'à ce que son propre lecteur
démarre — une initialisation unique au lancement du client le raterait.

### Ce qui se passe sans ReactiveMusic

La réponse honnête : **rien ne joue, et la musique vanilla reste éteinte.**

Suis le fil. `ReactiveMusicHook.startFadeOut` commence par `if (!isAvailable()) return;` :
ReactiveMusic absent, elle ne fait donc rien, et en particulier ne pose jamais
`suppressed`. `ZoneMusicPlayer` est déjà passé en `FADING_RM_OUT` et attend maintenant
ceci :

```java
public static boolean isFadeOutDone() {
    return suppressed && !fading && currentGain <= 0.001f;
}
```

`suppressed` est faux et rien d'autre ne peut le poser. L'attente ne finit jamais. Le
lecteur reste en `FADING_RM_OUT`, donc `isActive()` reste vrai, donc le mixin continue
d'annuler `MusicTracker.tick`. Le résultat pour un joueur :

- entrer dans une région assignée ne joue aucune piste ;
- la musique vanilla ne revient pas, parce que le mixin l'étouffe aussi longtemps que le
  joueur est coincé ;
- un paquet `stop` (ressortir) le sauve bien — un `stop` en `FADING_RM_OUT` passe à
  `FADING_RM_IN`, et `isFadeInDone()` vaut `!suppressed && !fading`, immédiatement vrai,
  donc l'état revient à `IDLE` et la musique vanilla reprend.

Le symptôme n'est donc pas un silence permanent dans tous les cas — c'est du silence dans
chaque région assignée, et du silence aussi longtemps qu'on y reste. Une déconnexion le
dissipe également, via `reset()`.

⚠️ Installe ReactiveMusic sur chaque client, ou attends-toi à ça. Cela n'a pas été vérifié
en jeu ; c'est une lecture des deux classes nommées plus haut. Mais aucune branche ne permet
d'y échapper. Faire fonctionner le mod sans ReactiveMusic veut dire faire renvoyer vrai à
`isFadeOutDone()` quand le hook est indisponible — une modification d'une ligne que
personne n'a faite, et qui mériterait d'être testée plutôt que supposée.

---

## Le mixin de musique vanilla

`MusicTrackerMixin` est listé sous `client` dans `cheesemusic.mixins.json` : il n'est donc
jamais appliqué sur un serveur dédié.

```java
@Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
```

En tête de `MusicTracker.tick`, si `ZoneMusicPlayer.isActive()`, il arrête le son que le
tracker détient et annule le tick. Vanilla ne poursuit donc pas sa musique courante et n'en
démarre pas de nouvelle tant que la musique de zone est active ou en transition.

`require = 0` — repris dans la configuration de mixin sous `"defaultRequire": 0` — signifie
que le jeu ne plante pas si le point d'injection est introuvable. Sur une version de
Minecraft où `MusicTracker.tick` a changé de forme, le mixin ne fait silencieusement rien
et la musique vanilla joue par-dessus la musique de zone. L'échec discret plutôt que le
plantage est le bon choix pour une fonctionnalité cosmétique, et c'est aussi pourquoi il
faut guetter la double musique après chaque montée de version.

---

← [Musique de zone](zone-music.fr.md) · [Configuration](configuration.fr.md) →
