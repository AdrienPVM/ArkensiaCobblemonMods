[← CheeseDash](../README.fr.md) · [Tous les mods](../../README.fr.md) · 🇬🇧 [English](web-ui.md)

# Interface web

Le dashboard, c'est un fichier HTML, une feuille de style et une quarantaine de fichiers
JavaScript servis directement depuis le jar. Pas d'étape de build, pas de bundler, pas de
npm, pas de framework au-delà d'Alpine.js chargé depuis un CDN. C'est un choix assumé :
l'outil a un utilisateur par serveur, il doit rester modifiable depuis une session SSH,
et une chaîne de build qui pourrit en deux ans survivrait à son utilité.

**~36 000 lignes** de HTML, JavaScript et CSS, contre ~4 650 lignes de Java.

---

## Où vivent les fichiers

```text
CheeseDash/
├── index.html                  coquille de l'app, écran de login, ordre de chargement
├── preview.html                démo autonome avec données factices
└── assets/
    ├── app.js                  store Alpine, routeur, helpers partagés
    ├── api.js                  le client REST — tout appel réseau passe par lui
    ├── style.css               le design system, 2 170 lignes
    ├── i18n.js                 moteur de traduction
    ├── lang-fr.js              chaînes françaises
    ├── lang-en.js              chaînes anglaises
    ├── guides.js               documentation intégrée de chaque module
    ├── view-*.js               26 fichiers, un par vue
    ├── egg-preview.js          rendu voxel Three.js pour EggAndCheese
    ├── playcosmetics-viewer.js visualiseur de modèles pour les cosmétiques CheeseStore
    ├── three.min.js            vendorisé
    └── OrbitControls.js        vendorisé
```

### ⚠️ Modifie les copies à la racine du mod, jamais celles sous `src/main/resources`

`processResources`, dans `build.gradle`, les recopie au moment du build :

```groovy
from("${projectDir}/index.html")   { into "dashboard" }
from("${projectDir}/preview.html") { into "dashboard" }
from("${projectDir}/assets")       { into "dashboard/assets" }
```

`src/main/resources/dashboard/` est donc un produit généré. Tout ce que tu y écris est
écrasé au prochain `./gradlew build`, en silence, sans fusion et sans avertissement. Si
ta modification a disparu, c'est pour cette raison.

À l'exécution, `WebDashboard.StaticHandler` lit depuis le classpath, sous
`/dashboard<chemin>`. `/` renvoie `/index.html` ; un chemin contenant `..` donne un
`403` ; un chemin inconnu retombe sur `index.html`, pour que le routage côté client
survive à un rechargement sur un lien profond. Les types de contenu viennent de
l'extension, dans `WebDashboard.detectContentType` ; le HTML est servi sans aucun
en-tête `Cache-Control`, et
**tout le reste reçoit `Cache-Control: public, max-age=3600`**. Cette heure de cache est
la deuxième raison pour laquelle une modification semble sans effet : recharge la page de
force, ou redémarre le serveur, après avoir touché un `.js` ou un `.css`.

Les réponses compressibles sont gzippées de façon transparente quand le client l'annonce ;
les formats déjà compressés comme le PNG ou le woff2 passent intacts.

---

## Ordre de chargement des scripts

L'ordre compte, parce que rien ici n'est un module et que tout est une variable globale.
`index.html` se termine par cette séquence :

```text
three.min.js → OrbitControls.js → egg-preview.js
i18n.js → lang-fr.js → lang-en.js
api.js → guides.js → app.js
view-overview.js … view-wizard.js   (26 fichiers)
```

Alpine.js est chargé en `defer` depuis un CDN en haut du document, donc il s'initialise
après l'exécution de tous les scripts ci-dessus. C'est ce qui permet à `NAV_SECTIONS`,
dans `app.js`, d'appeler `t()` au premier niveau : les packs de langue se sont déjà
enregistrés.

Trois conséquences :

- **Un nouveau `view-*.js` doit être ajouté à `index.html`,** entre `app.js` et la fin de
  la liste. Un fichier de vue qui n'y figure pas ne se charge jamais, et l'appel du
  routeur à son constructeur lève une `ReferenceError` (`buildKart is not defined`),
  affichée à la place de la vue.
- **`app.js` doit venir avant les fichiers de vue,** puisqu'ils s'appuient sur ses
  helpers partagés.
- **Ni `import`, ni `export`.** Les fonctions sont globales par déclaration. `const` et
  `let` uniquement, jamais `var`.

Les dépendances CDN sont le CDN de développement de Tailwind, DaisyUI 4.10.1, Alpine.js
3.14.1 et Google Fonts (Geist, Geist Mono). La politique de sécurité de contenu posée par
`WebDashboard.addSecurityHeaders` autorise ces origines (et `https://unpkg.com` pour les
scripts, d'où rien n'est chargé aujourd'hui), plus
`https://mc-heads.net`, `https://minecraft.wiki` et `https://img.pokemondb.net` pour les
images, et `ws:`/`wss:` pour le WebSocket. **Le dashboard a donc besoin d'un accès
Internet depuis le navigateur**, pas depuis le serveur. Sur un réseau isolé, la mise en
page s'effondre en HTML sans style ; vendoriser Tailwind et Alpine à côté de
`three.min.js` est la solution, et personne ne l'a faite.

---

## `app.js` — le store et le routeur

Un composant Alpine, `cgApp()`, porte tout l'état de l'application : `authenticated`,
`currentView`, `viewHtml`, la file de notifications, le WebSocket, la liste de joueurs en
direct, le statut serveur mis en cache. `loginForm()` est un second composant, plus
petit, pour l'écran de connexion.

Une vue est une fonction qui renvoie une chaîne de HTML :

```javascript
// view-zones.js
async function buildZones(app) {
    let data;
    try { data = await API.zones.list(); }
    catch (e) { app.toast(e.message, 'error'); return `<div class="empty-state">…</div>`; }
    return `<div class="max-w-6xl mx-auto slide-in">…</div>`;
}
```

`loadView` est un `switch` sur l'identifiant de vue qui appelle le bon constructeur, et
le résultat est injecté via le `x-html` d'Alpine. Les routes de détail sont le même
identifiant suivi d'un second segment après deux-points (`zones:spawn`), dispatchées par
un deuxième `switch`. Le fragment d'URL porte la vue courante, donc un rafraîchissement te ramène là
où tu étais.

Les helpers partagés vivent dans `app.js`, les helpers spécifiques dans la vue qui les
utilise. La règle est mécanique : **utilisé par deux vues ou plus → `app.js` ; utilisé
par une seule → le fichier de cette vue.** Ceux qu'il faut connaître avant d'écrire une
vue sont `escHtml`, `_nav`, `_toast`, `confirmAction`, `detailBackBtn`, `detailTabBar`,
`switchDetailTab`, `speciesPickerHtml`, `buildFlagsGrid`, `openPlayerPicker`,
`skeletonCard` et `skeletonPage`. L'état mutable d'une vue va dans une globale
nommée — `window._mk` pour le marché, `window._eg` pour les œufs, `window._ed` pour les
événements — pas dans le store Alpine.

Deux règles qui ne relèvent pas du style :

- **N'appelle jamais `fetch()` directement.** Tout passe par `API.*` dans `api.js`, qui
  attache le justificatif de session, transforme les erreurs en exceptions et centralise
  l'URL de base.
- **`escHtml()` sur chaque donnée serveur interpolée dans du HTML**, apostrophes
  comprises. Les vues construisent le HTML sous forme de chaînes, donc c'est la seule
  chose qui sépare un pseudo choisi par un joueur d'une injection de script dans ta
  propre page d'administration.

---

## La barre latérale s'adapte à ce qui est installé

`NAV_SECTIONS`, dans `app.js`, déclare toutes les sections que le frontend sait afficher.
La plupart portent un `prefix` qui nomme le module backend auquel elles appartiennent. Au
login :

```javascript
const mods = await API.get('/modules');
if (Array.isArray(mods)) installed = new Set(mods.map(m => m.prefix));

this.navSections = NAV_SECTIONS.filter(s =>
  (s.key !== 'admin' || API.canAdmin()) &&
  (!s.prefix || installed === null || installed.has(s.prefix))
);
```

Une section n'apparaît donc que si `GET /api/v1/modules` annonce son préfixe : c'est ce
qui fait fonctionner n'importe quelle combinaison de mods sans aucune configuration. Les
sections sans `prefix` — *Général* et *Administration* — sont de l'infrastructure et sont
toujours affichées, et la section *Administration* est en plus masquée à quiconque n'est
pas `SUPER_ADMIN`. Si la requête `/modules` échoue, `installed` reste `null` et **toutes**
les sections sont conservées : mieux vaut une entrée de menu morte qu'un dashboard qui se
cache la moitié de lui-même parce qu'une requête a expiré.

### Des vues pour des mods absents de ce dépôt

`assets/` livre `view-factionwar.js` et `view-wizard.js`, qui appartiennent à un module
`factionwar` inexistant dans ce dépôt. Il en va de même pour des morceaux d'autres
sections : `view-morphos.js` a besoin du module `morphos` (livré dans CheeseItems),
`view-prison.js` de CheeseGuard, `view-playcosmetics.js` de CheeseStore,
`view-patrol.js` de CheesePatrol, `view-music.js` de CheeseMusic.

Sur un serveur qui n'a pas le mod, le filtre ci-dessus fait son travail : **la section
est tout simplement absente de la barre latérale.** Le JavaScript se charge quand
même — quelques dizaines de kilo-octets de code mort servis à chaque chargement de
page — mais aucun chemin de l'interface n'y mène. Taper le fragment d'URL à la main
(`#factionwar`) atteint bien le constructeur, qui appelle alors un endpoint sous un
préfixe que personne n'a enregistré et récolte
`404 {"error":"Route not found: …"}` ; la vue affiche une notification d'erreur et un
état vide. Rien ne casse, rien n'est exposé, et le seul coût est le téléchargement.

`view-raids.js` est l'anomalie inverse : le constructeur existe et est branché au
routeur, CheeseRaids enregistre bien le préfixe `raids`, mais `NAV_SECTIONS` n'a aucune
section pour lui. La vue n'est accessible que par `#raids`.

---

## Internationalisation

`i18n.js` tient en une centaine de lignes. `I18N.register(lang, data)` reçoit un pack de
langue, `I18N.t(key, params)` résout une clé, et deux globales l'enveloppent :

```javascript
t('zones.created', {name: 'Arena'})   // interpole {name}
tip('tip.zones.priority')             // renvoie un <span> de tooltip, ou '' si la clé manque
```

`lang-fr.js` et `lang-en.js` portent chacun environ 2 900 clés. Le français est la langue
par défaut et le repli : une clé inconnue est cherchée en français et, à défaut, c'est la
clé elle-même qui est renvoyée — une traduction manquante est donc visible plutôt que
vide. Le choix est stocké dans `localStorage` sous `cg-lang`, et changer de langue
recharge la page, parce que `NAV_SECTIONS` et les définitions de guides sont évaluées une
seule fois au chargement.

Les conventions, si tu ajoutes une chaîne :

- `module.subkey` pour les chaînes d'un module, `common.*` pour les chaînes partagées,
  `error.*` pour les échecs, `tip.module.field` pour les tooltips de champ,
  `guide.module.section.*` pour le contenu des guides.
- **Chaque nouvelle clé va dans `lang-fr.js` ET dans `lang-en.js`.** Une clé présente
  dans un seul fichier retombe silencieusement sur le français pour les lecteurs
  anglophones.
- Dates et durées via `I18N.fmtDate(iso)` et `I18N.fmtUptime(ms)`, jamais une locale en
  dur.
- Aucune chaîne visible par l'utilisateur écrite en dur dans une vue. Il en reste
  quelques-unes — les entrées de navigation `factionwar` et `playcosmetics` portent des
  libellés français en dur — et ce sont des bugs, pas un modèle à suivre.

Le texte affiché en jeu relève d'un autre mécanisme : `Text.translatable` avec
`src/main/resources/assets/cheesedash/lang/{en_us,fr_fr}.json`.

---

## Guides

`guides.js` contient `GUIDE_DEFS`, une définition par vue — 22 au total, couvrant les
vues de modules et les trois vues d'administration — et les rend à partir de clés i18n de
la forme `guide.<module>.<section>.{title,body,list,note}`. Deux schémas d'intégration,
selon que la vue possède déjà une barre d'onglets :

```javascript
// une vue à onglets : ajouter un onglet Guide
const tabs = detailTabBar('zv', ['info','flags','members'], guideTabEntry());
// …et son panneau dans le corps
${guideTabPanel('zones', 'zv')}

// une vue sans onglets : un bouton qui ouvre le guide dans un modal
${guideButton('shift')}
```

Quand tu changes ce que fait un module, mets à jour ses clés `guide.*` dans les deux
fichiers de langue. Un guide qui décrit le comportement du mois dernier est pire que pas
de guide.

---

## Le design system

`style.css` définit la palette sous forme de variables CSS sur `[data-theme="cg-dark"]`
et `[data-theme="cg-light"]`, et l'attribut de thème est posé sur `<html>`. La préférence
est stockée dans `localStorage` sous `cg-theme`, appliquée par un script inline dans
`index.html` avant que la feuille de style ne peigne, et basculée depuis l'en-tête. Le
thème sombre est celui par défaut, et celui qui reçoit l'attention.

| Variable | Sombre | Rôle |
|---|---|---|
| `--bg` | `#0c0e14` | fond de page |
| `--bg-card` | `#13161e` | cartes et panneaux |
| `--bg-elevated` | `#1a1d28` | inputs, éléments surélevés |
| `--bg-border` | `#252836` | toutes les bordures |
| `--amber` | `#f5a623` | accent primaire, la couleur de marque |
| `--cyan` | `#22d3ee` | accent secondaire |
| `--green` | `#22c55e` | succès, en ligne |
| `--red` | `#ef4444` | danger, erreurs |
| `--muted` | `#6b7280` | texte secondaire |

Utilise les variables, jamais un hex littéral, sinon le thème clair casse.

Les composants sont des classes maison, parce que les valeurs par défaut de DaisyUI ne
collent pas à cette palette : `.btn-cg` avec `.btn-cg-primary` / `-danger` / `-ghost` /
`-success` / `-sm` pour les boutons, `.cg-table` pour les tableaux, `.section-card` et
ses header/title/body pour les panneaux, `.stat-card` pour les chiffres, `.id-badge`
pour les identifiants, `.status-dot` avec `.s-online` / `.s-warn` / `.s-offline` /
`.s-idle`, `.detail-tabs` et `.detail-tab` pour les onglets, `.empty-state` pour le vide,
`.skeleton-line` pour le chargement, `.field-label` et `.field-tip` pour les formulaires.
DaisyUI n'est gardé que là où le thème l'override proprement : `.input`, `.select`,
`.toggle`, `.checkbox`, `.radio`, `.modal` et `.modal-box`.

---

## `preview.html` — une ancienne maquette autonome

`preview.html` est une page séparée et autonome, avec son propre composant Alpine inline
et ses données factices, écrites en dur en français. Elle ne charge aucun des fichiers
`assets/*.js`. Ouvre-la depuis le disque : pas de build, pas de Minecraft, mais le
navigateur a quand même besoin des CDN de Tailwind et d'Alpine.

Elle s'ouvre directement sur le dashboard : `init()` pose `auth = true`, donc le
formulaire de connexion est sauté. (Ce formulaire, si tu le réactives, accepte `demo` ou
toute chaîne de plus de quatre caractères, et plus court affiche
`Token invalide — essayez "demo" pour la démo`.) Seule la vue d'ensemble a des données
factices — TPS, nombre de joueurs, compteurs de zones, de dimensions et d'événements,
une liste d'activité récente. La barre latérale compte sept autres entrées (Zones,
Groupes, Chunks, Dimensions, Événements, Joueurs, Serveur), et chacune affiche un écran
provisoire `Phase 2 — implémentation en cours`.

Elle montre donc l'apparence et la palette, pas la vraie interface, et elle a beaucoup
dérivé par rapport à `index.html` : aucune des 26 vues n'y figure. Pour travailler sur
une vraie vue, il te faut un serveur qui tourne.

---

## Ajouter une vue

De bout en bout, pour un module de préfixe `kart` :

1. Ajoute les endpoints dans `api.js`, sous un objet `endpoints.kart`. Pas de `fetch`
   ailleurs.
2. Écris `assets/view-kart.js` avec `async function buildKart(app)` renvoyant du HTML.
   Entoure chaque appel API d'un `try/catch` et signale les échecs avec
   `app.toast(e.message, 'error')`.
3. Ajoute `case 'kart': return await buildKart(this);` aux routes de liste de `loadView`
   dans `app.js`, et un cas de détail si la vue en a un.
4. Ajoute la section à `NAV_SECTIONS` avec `prefix: 'kart'`, pour qu'elle se masque
   quand le mod est absent.
5. Ajoute `<script src="assets/view-kart.js"></script>` dans `index.html`, après
   `app.js`.
6. Ajoute chaque chaîne dans `lang-fr.js` et `lang-en.js`. Debounce les champs de
   recherche à 300 ms.
7. Ajoute une entrée `GUIDE_DEFS.kart` et ses clés `guide.kart.*`, et place
   `guideButton('kart')` dans l'en-tête de la vue.

Puis `./gradlew build`, redémarre, et recharge le navigateur de force pour passer outre
cette heure de `Cache-Control`.

---

← [Écrire un module](writing-a-module.fr.md) · [Planificateur](scheduler.fr.md) →
