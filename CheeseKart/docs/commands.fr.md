[← CheeseKart](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](commands.md)

# Commandes

CheeseKart expose une seule commande, `/cheesekart`, et cinq sous-commandes. L'arbre
entier exige le **niveau de permission 2** — celui qu'un serveur vanilla accorde à un
opérateur. Un joueur sous ce niveau ne voit pas la commande dans la complétion par
tabulation et reçoit l'erreur vanilla *Unknown or incomplete command* s'il la tape
quand même.

Aucune commande n'est destinée aux joueurs. Les pilotes sont mis en kart par un
administrateur ou par un événement CheeseGuard ; ils ne tapent jamais rien eux-mêmes.

---

## `/cheesekart start`

```mcfunction
/cheesekart start <player> <preset> [species]
```

**Le joueur d'abord, le preset ensuite.** C'est un piège classique, parce que l'ancienne
documentation du mod donnait l'ordre inverse.

| Argument | Type | Requis | Valeurs acceptées | Défaut |
|---|---|---|---|---|
| `player` | mot unique | oui | le nom d'un joueur **connecté** ; se complète depuis la liste des joueurs | — |
| `preset` | mot unique | oui | un identifiant de preset de `config/cheesekart.json` ; se complète depuis les presets chargés | — |
| `species` | chaîne gourmande | non | un nom d'espèce Cobblemon, transmis tel quel à CobbleShift | la `species` du preset lui-même, sinon aucune |

`species` est un argument *gourmand* : il avale la fin de la ligne, donc une valeur en
plusieurs mots n'a pas besoin de guillemets. Il n'a de sens que si
[CobbleShift](../../CobbleShift/) est installé ; sans lui, l'argument est accepté et
ignoré en silence.

### Ce que fait la commande

1. Si le joueur a déjà un kart, cette session est arrêtée d'abord — `start` sert donc
   aussi à changer le preset de quelqu'un en pleine course.
2. Fait apparaître un bateau à la position exacte du joueur, marqué invisible,
   invulnérable et silencieux.
3. Force le joueur à monter dessus. Si la montée échoue, le bateau est jeté et la
   commande signale l'échec.
4. Pousse les douze valeurs de physique du preset vers le client, sous forme de paquets
   OpenBoatUtils.
5. Résout l'espèce (l'argument explicite d'abord, puis le champ du preset) et demande à
   CobbleShift de transformer le joueur, s'il est disponible.
6. Enregistre la session et annonce à tous les clients que ce joueur est désormais en
   kart.

### Exemples

```mcfunction
/cheesekart start Steve drift
```

```text
Kart started for Steve (preset=drift)
```

```mcfunction
/cheesekart start Steve rally pikachu
```

Même résultat, et Steve est en plus transformé en Pikachu pour la durée de la session si
CobbleShift est installé.

### Échecs

| Message | Cause |
|---|---|
| `Player not found: <name>` | le nom ne correspond à aucun joueur connecté — un joueur hors ligne ne peut pas être mis en kart |
| `Preset not found: <id>` | aucun identifiant de ce nom dans `config/cheesekart.json` ; vérifie avec `/cheesekart presets` |
| `Failed to start kart for <name>` | la montée forcée a été refusée. C'est rare : un véhicule déjà occupé n'en est *pas* la cause, car une montée forcée en fait d'abord descendre le joueur — voir [dépannage](troubleshooting.fr.md#une-commande-start-répond-failed-to-start-kart) |

Les deux premiers sont des refus : rien ne s'est passé. Le troisième signifie qu'un
bateau a été créé puis nettoyé, donc aucune entité ne traîne derrière.

---

## `/cheesekart stop`

```mcfunction
/cheesekart stop <player>
```

| Argument | Type | Requis | Valeurs acceptées |
|---|---|---|---|
| `player` | mot unique | **oui** | le nom d'un joueur connecté ; se complète depuis la liste des joueurs |

L'argument joueur est obligatoire. Il n'existe aucune forme de `stop` qui viserait
l'auteur de la commande.

Le démontage suit un ordre délibéré : les clients apprennent que le kart est terminé
*avant* la descente, la physique est réinitialisée *après*, et le joueur est finalement
re-téléporté à sa propre position pour purger la vélocité résiduelle.
[Pourquoi cet ordre](physics.fr.md#arrêter-un-kart-est-un-démontage-ordonné) compte, si
tu touches un jour à ce code.

```mcfunction
/cheesekart stop Steve
```

```text
Kart stopped for Steve
```

| Message | Cause |
|---|---|
| `Player not found: <name>` | pas connecté |
| `<name> is not in kart` | le joueur est connecté mais n'a aucune session. Sans conséquence |

---

## `/cheesekart stopall`

```mcfunction
/cheesekart stopall
```

Aucun argument. Arrête toutes les sessions actives, chacune passant par le même
démontage ordonné qu'un `stop` individuel. Les sessions dont le joueur s'est déconnecté
entre-temps sont retirées de la table plutôt que démontées.

```text
All karts stopped (4)
```

Le résultat numérique de la commande est le nombre de sessions qui étaient actives, ce
qui la rend utilisable dans une chaîne de blocs de commande ou dans un
`/execute store`.

Elle s'exécute aussi automatiquement à l'arrêt du serveur : un redémarrage ne laisse
donc jamais traîner un bateau invisible.

---

## `/cheesekart list`

```mcfunction
/cheesekart list
```

Les sessions actives, une ligne chacune. La sortie n'est envoyée qu'à l'appelant.

```text
--- Active karts (2) ---
  Steve | preset=drift | species=pikachu | 94s [locked]
  Alex | preset=rally | 12s
```

| Champ | Signification |
|---|---|
| nom | le joueur |
| `preset=` | le preset avec lequel la session a été lancée |
| `species=` | entièrement omis quand aucune transformation n'est active |
| `…s` | secondes écoulées depuis le début de la session |
| `[locked]` | le pilote ne peut pas descendre — voir [le mode verrouillé](physics.fr.md#le-mode-verrouillé) |

Quand rien ne tourne, tu obtiens `No active karts`.

---

## `/cheesekart presets`

```mcfunction
/cheesekart presets
```

Liste les presets configurés avec les trois valeurs que l'on règle le plus souvent, ce
qui permet de les comparer sans ouvrir le fichier de configuration.

```text
--- Presets (3) ---
  parkour — Parkour | slip=0.98 step=2.0 fwd=0.04 [locked]
  rally — Rally | slip=0.98 step=1.25 fwd=0.04 [locked]
  drift — Drift | slip=1.1 step=1.25 fwd=0.06 [locked]
```

`slip` est la glissance, `step` la hauteur de marche, `fwd` l'accélération avant. Un
preset qui porte un champ `species` l'affiche aussi. Tous les champs sont documentés
dans [configuration](configuration.fr.md).

L'ordre est celui dans lequel la `ConcurrentHashMap` sous-jacente itère, pas l'ordre de
création — d'où l'ordre `parkour`, `rally`, `drift` des trois presets fournis. Il en va de
même pour `/cheesekart list` et pour l'ordre des presets dans le fichier sauvegardé.

Cette commande lit les presets vivants, en mémoire : un preset créé depuis le dashboard
il y a une seconde apparaît immédiatement.

---

## Piloter le mod depuis ailleurs

Tout ce qui précède est aussi accessible sans commande :

- **Dashboard** — le module `kart`, ou directement les endpoints REST. Voir
  [l'API dashboard](dashboard.fr.md).
- **Événements CheeseGuard** — les types d'effet `KART_START_ALL` et `KART_STOP_ALL`
  mettent tous les participants d'un événement chronométré en kart d'un seul coup, ce
  qui est la façon de démarrer proprement une course pour tout le monde au même tick.

---

← [Premiers pas](getting-started.fr.md) · [Configuration](configuration.fr.md) →
