/**
 * mc-cosmetic-viewer.js — Aperçu 3D des cosmétiques (Arkensia / CheeseStore)
 * ──────────────────────────────────────────────────────────────────────────
 * Léger viewer Three.js (r128, chargé en <script> classique avec SRI, globals
 * `THREE` + `THREE.OrbitControls`).
 *
 * Stratégie de rendu (du plus riche au fallback) :
 *   • ITEM / HAT avec une texture (PNG d'aperçu `/store/api/cosmetics/:id/image`
 *     quand hasImage) → maillage EXTRUDÉ à partir de l'alpha de la texture :
 *     le rendu « item plat en 3D » authentique de Minecraft (faces avant/arrière
 *     texturées + tranches sur les bords). Tourne en continu + OrbitControls.
 *   • Si la texture échoue (chargement/CORS) → plan texturé (billboard) dans la
 *     même scène 3D, toujours tournant.
 *   • Aucune texture exploitable → le composant signale `noTexture` ; l'appelant
 *     retombe sur l'<img> PNG ou l'icône (géré côté Alpine/HTML).
 *   • PARTICLE / TRAIL / EFFECT → PAS de 3D : vignette canvas animée (particules).
 *
 * API publique (window.McCosmeticViewer) :
 *   mount(container, cosmetic)  → démarre un aperçu dans `container` (un <div>).
 *                                 `cosmetic` = { id, name, type, itemId, hasImage,
 *                                                imageUrl?, modelRef? }
 *                                 retourne un handle { dispose() }.
 *
 * Le viewer ne casse rien si Three.js est absent : il bascule alors directement
 * sur la vignette particules (3D désactivée), aucune exception propagée.
 */
(function () {
  'use strict';

  var STORE_API = '/store/api';
  var TYPES_3D = { ITEM: true, HAT: true };          // types qui méritent un rendu 3D d'item
  var TYPES_FX = { PARTICLE: true, TRAIL: true, EFFECT: true };

  function hasThree() {
    return typeof window.THREE !== 'undefined' && typeof window.THREE.WebGLRenderer === 'function';
  }

  function imageUrlFor(cosmetic) {
    if (cosmetic.imageUrl) return cosmetic.imageUrl;
    if (cosmetic.hasImage && cosmetic.id) return STORE_API + '/cosmetics/' + encodeURIComponent(cosmetic.id) + '/image';
    return null;
  }

  function prefersReducedMotion() {
    return window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  }

  // ── Chargement texture → ImageData (pour l'extrusion alpha) ─────────────────
  function loadImageData(url, cb) {
    var img = new Image();
    img.crossOrigin = 'anonymous';
    img.onload = function () {
      try {
        // Plafond raisonnable : les textures d'items MC font 16×16/32×32, mais un
        // PNG d'aperçu uploadé peut être plus gros. On downscale pour garder
        // l'extrusion légère (au plus ~64 colonnes de voxels).
        var MAX = 64;
        var w = img.naturalWidth || img.width;
        var h = img.naturalHeight || img.height;
        var scale = Math.min(1, MAX / Math.max(w, h));
        var cw = Math.max(1, Math.round(w * scale));
        var ch = Math.max(1, Math.round(h * scale));
        var c = document.createElement('canvas');
        c.width = cw; c.height = ch;
        var ctx = c.getContext('2d', { willReadFrequently: true });
        ctx.imageSmoothingEnabled = false;
        ctx.drawImage(img, 0, 0, cw, ch);
        var data = ctx.getImageData(0, 0, cw, ch);
        cb(null, data, img);
      } catch (e) {
        cb(e);
      }
    };
    img.onerror = function () { cb(new Error('image load failed')); };
    img.src = url;
  }

  // ── Géométrie : extrusion de la texture (pixels opaques → voxels plats) ─────
  // On émule le rendu d'item « flat » de Minecraft : un quad avant + un quad
  // arrière texturés, et des tranches sur les bords où l'alpha change. On fusionne
  // les quads en une seule BufferGeometry pour rester performant.
  function buildExtrudedGeometry(THREE, imageData) {
    var w = imageData.width, h = imageData.height;
    var px = imageData.data;
    var alphaAt = function (x, y) {
      if (x < 0 || y < 0 || x >= w || y >= h) return 0;
      return px[(y * w + x) * 4 + 3];
    };
    var ALPHA_CUT = 32;
    var depth = Math.max(0.5, Math.min(w, h) * 0.06); // épaisseur ~ "1 pixel" d'item
    // Centrage : le maillage est centré sur l'origine, normalisé à ~2 unités de large.
    var unit = 2 / Math.max(w, h);
    var ox = -w / 2, oy = -h / 2, oz = -depth / 2;

    var positions = [];
    var uvs = [];
    var normals = [];
    var indices = [];
    var vCount = 0;

    function pushQuad(p0, p1, p2, p3, n, uv0, uv1, uv2, uv3) {
      var base = vCount;
      [p0, p1, p2, p3].forEach(function (p) {
        positions.push((p[0] + ox) * unit, -(p[1] + oy) * unit, (p[2] + oz) * unit);
        normals.push(n[0], n[1], n[2]);
      });
      uvs.push(uv0[0], uv0[1], uv1[0], uv1[1], uv2[0], uv2[1], uv3[0], uv3[1]);
      indices.push(base, base + 1, base + 2, base, base + 2, base + 3);
      vCount += 4;
    }

    // UV : la texture est échantillonnée pixel par pixel ; on mappe chaque pixel
    // sur sa cellule (u,v) avec V inversé (origine image en haut, UV en bas).
    function uvCell(x, y) {
      return {
        u0: x / w, u1: (x + 1) / w,
        v0: 1 - (y + 1) / h, v1: 1 - y / h
      };
    }

    for (var y = 0; y < h; y++) {
      for (var x = 0; x < w; x++) {
        if (alphaAt(x, y) < ALPHA_CUT) continue;
        var c = uvCell(x, y);
        var x0 = x, x1 = x + 1, y0 = y, y1 = y + 1, z0 = 0, z1 = depth;

        // Face avant (+Z)
        pushQuad(
          [x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1],
          [0, 0, 1],
          [c.u0, c.v1], [c.u1, c.v1], [c.u1, c.v0], [c.u0, c.v0]
        );
        // Face arrière (-Z) — mêmes UV (texture visible des deux côtés)
        pushQuad(
          [x1, y0, z0], [x0, y0, z0], [x0, y1, z0], [x1, y1, z0],
          [0, 0, -1],
          [c.u1, c.v1], [c.u0, c.v1], [c.u0, c.v0], [c.u1, c.v0]
        );
        // Tranches uniquement sur les bords (voisin transparent) → silhouette nette
        if (alphaAt(x - 1, y) < ALPHA_CUT) { // gauche (-X)
          pushQuad([x0, y0, z0], [x0, y0, z1], [x0, y1, z1], [x0, y1, z0],
            [-1, 0, 0], [c.u0, c.v1], [c.u0, c.v1], [c.u0, c.v0], [c.u0, c.v0]);
        }
        if (alphaAt(x + 1, y) < ALPHA_CUT) { // droite (+X)
          pushQuad([x1, y0, z1], [x1, y0, z0], [x1, y1, z0], [x1, y1, z1],
            [1, 0, 0], [c.u1, c.v1], [c.u1, c.v1], [c.u1, c.v0], [c.u1, c.v0]);
        }
        if (alphaAt(x, y - 1) < ALPHA_CUT) { // haut (-Y image → +Y monde)
          pushQuad([x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1],
            [0, 1, 0], [c.u0, c.v0], [c.u1, c.v0], [c.u1, c.v0], [c.u0, c.v0]);
        }
        if (alphaAt(x, y + 1) < ALPHA_CUT) { // bas
          pushQuad([x0, y1, z1], [x1, y1, z1], [x1, y1, z0], [x0, y1, z0],
            [0, -1, 0], [c.u0, c.v1], [c.u1, c.v1], [c.u1, c.v1], [c.u0, c.v1]);
        }
      }
    }

    if (vCount === 0) return null; // texture entièrement transparente

    var geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
    geo.setAttribute('normal', new THREE.Float32BufferAttribute(normals, 3));
    geo.setAttribute('uv', new THREE.Float32BufferAttribute(uvs, 2));
    geo.setIndex(indices);
    return geo;
  }

  // ── Scène Three.js commune ──────────────────────────────────────────────────
  function makeScene(THREE, container) {
    var width = container.clientWidth || 320;
    var height = container.clientHeight || 320;

    var renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'low-power' });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    renderer.setSize(width, height);
    renderer.domElement.style.display = 'block';
    renderer.domElement.style.width = '100%';
    renderer.domElement.style.height = '100%';
    container.appendChild(renderer.domElement);

    var scene = new THREE.Scene();
    var camera = new THREE.PerspectiveCamera(40, width / height, 0.1, 100);
    camera.position.set(0, 0, 5);

    // Lumières chaudes cohérentes avec le thème phénix.
    scene.add(new THREE.AmbientLight(0xffffff, 0.85));
    var key = new THREE.DirectionalLight(0xffe6b0, 0.8); key.position.set(2, 3, 4); scene.add(key);
    var rim = new THREE.DirectionalLight(0xff7a3c, 0.5); rim.position.set(-3, -1, -2); scene.add(rim);

    return { renderer: renderer, scene: scene, camera: camera, width: width, height: height };
  }

  // ── Aperçu 3D d'un item (extrusion ou plan texturé en fallback) ─────────────
  function mount3DItem(container, cosmetic) {
    var THREE = window.THREE;
    var ctx = makeScene(THREE, container);
    var disposed = false;
    var raf = 0;
    var controls = null;
    var mesh = null;
    var texture = null;
    var geometry = null;
    var material = null;

    var reduce = prefersReducedMotion();

    function addControls() {
      if (typeof THREE.OrbitControls !== 'function') return;
      controls = new THREE.OrbitControls(ctx.camera, ctx.renderer.domElement);
      controls.enablePan = false;
      controls.enableZoom = true;
      controls.minDistance = 2.5;
      controls.maxDistance = 9;
      controls.enableDamping = true;
      controls.dampingFactor = 0.08;
      controls.autoRotate = !reduce;
      controls.autoRotateSpeed = 2.2;
    }

    function animate() {
      if (disposed) return;
      raf = requestAnimationFrame(animate);
      if (controls) controls.update();
      else if (mesh && !reduce) mesh.rotation.y += 0.01;
      ctx.renderer.render(ctx.scene, ctx.camera);
    }

    function onResize() {
      if (disposed) return;
      var w = container.clientWidth || ctx.width;
      var h = container.clientHeight || ctx.height;
      ctx.camera.aspect = w / h;
      ctx.camera.updateProjectionMatrix();
      ctx.renderer.setSize(w, h);
    }
    window.addEventListener('resize', onResize);

    var url = imageUrlFor(cosmetic);

    function buildPlaneFallback(tex) {
      // Plan texturé double-face : item « plat » dans la scène 3D, toujours tournant.
      geometry = new THREE.PlaneGeometry(2.2, 2.2);
      material = new THREE.MeshBasicMaterial({ map: tex, transparent: true, side: THREE.DoubleSide, alphaTest: 0.1 });
      mesh = new THREE.Mesh(geometry, material);
      ctx.scene.add(mesh);
      addControls();
      animate();
    }

    if (!url) {
      // Pas de texture exploitable → on le signale, l'appelant fera le fallback <img>.
      cleanup();
      return { noTexture: true, dispose: function () {} };
    }

    loadImageData(url, function (err, imageData, img) {
      if (disposed) return;
      if (err) {
        // Échec de chargement (CORS/404) → fallback <img> côté appelant.
        cleanup();
        if (typeof container.__onNoTexture === 'function') container.__onNoTexture();
        return;
      }
      // Texture pixel-art : NearestFilter pour garder les pixels nets.
      texture = new THREE.Texture(img);
      texture.magFilter = THREE.NearestFilter;
      texture.minFilter = THREE.NearestFilter;
      texture.generateMipmaps = false;
      texture.needsUpdate = true;

      geometry = buildExtrudedGeometry(THREE, imageData);
      if (!geometry) { buildPlaneFallback(texture); return; }

      material = new THREE.MeshLambertMaterial({
        map: texture, transparent: true, alphaTest: 0.5, side: THREE.DoubleSide
      });
      mesh = new THREE.Mesh(geometry, material);
      // Légère inclinaison initiale pour un rendu « présentation ».
      mesh.rotation.x = -0.15;
      ctx.scene.add(mesh);
      addControls();
      animate();
    });

    function cleanup() {
      if (raf) cancelAnimationFrame(raf);
      window.removeEventListener('resize', onResize);
      if (controls && controls.dispose) controls.dispose();
      if (geometry) geometry.dispose();
      if (material) material.dispose();
      if (texture) texture.dispose();
      if (ctx.renderer) {
        ctx.renderer.dispose();
        if (ctx.renderer.domElement && ctx.renderer.domElement.parentNode) {
          ctx.renderer.domElement.parentNode.removeChild(ctx.renderer.domElement);
        }
      }
    }

    return {
      dispose: function () { if (disposed) return; disposed = true; cleanup(); }
    };
  }

  // ── Vignette particules animée (PARTICLE / TRAIL / EFFECT) ───────────────────
  // Pas de 3D : un petit canvas 2D avec des particules ascendantes (style braise),
  // teinté selon le type, sans dépendance externe.
  function mountFxVignette(container, cosmetic) {
    var canvas = document.createElement('canvas');
    canvas.style.display = 'block';
    canvas.style.width = '100%';
    canvas.style.height = '100%';
    container.appendChild(canvas);
    var ctx = canvas.getContext('2d');
    var disposed = false;
    var raf = 0;
    var reduce = prefersReducedMotion();

    var palettes = {
      PARTICLE: ['#ffd86b', '#ff7a3c', '#fff0c8'],
      TRAIL: ['#ff5530', '#ffd86b', '#ff9a5c'],
      EFFECT: ['#9a6bff', '#c044ff', '#e2c3ff']
    };
    var colors = palettes[cosmetic.type] || palettes.PARTICLE;

    var particles = [];
    function resize() {
      var w = container.clientWidth || 320;
      var h = container.clientHeight || 320;
      var dpr = Math.min(window.devicePixelRatio || 1, 2);
      canvas.width = Math.round(w * dpr);
      canvas.height = Math.round(h * dpr);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      canvas._w = w; canvas._h = h;
    }
    resize();
    window.addEventListener('resize', resize);

    function spawn() {
      var w = canvas._w, h = canvas._h;
      return {
        x: w * (0.2 + Math.random() * 0.6),
        y: h + 8,
        r: 1.5 + Math.random() * 3.5,
        vy: 0.4 + Math.random() * 1.1,
        vx: (Math.random() - 0.5) * 0.6,
        life: 0,
        ttl: 120 + Math.random() * 120,
        c: colors[(Math.random() * colors.length) | 0]
      };
    }
    var COUNT = reduce ? 0 : 34;
    for (var i = 0; i < COUNT; i++) { var p = spawn(); p.y = Math.random() * (canvas._h || 320); particles.push(p); }

    function frame() {
      if (disposed) return;
      raf = requestAnimationFrame(frame);
      var w = canvas._w, h = canvas._h;
      ctx.clearRect(0, 0, w, h);
      // Halo central doux
      var g = ctx.createRadialGradient(w / 2, h / 2, 4, w / 2, h / 2, Math.max(w, h) * 0.55);
      g.addColorStop(0, hexA(colors[0], 0.20));
      g.addColorStop(1, 'rgba(0,0,0,0)');
      ctx.fillStyle = g;
      ctx.fillRect(0, 0, w, h);

      ctx.globalCompositeOperation = 'lighter';
      for (var i = 0; i < particles.length; i++) {
        var p = particles[i];
        p.life++;
        p.y -= p.vy;
        p.x += p.vx;
        if (p.life > p.ttl || p.y < -10) { particles[i] = spawn(); continue; }
        var a = Math.max(0, 1 - p.life / p.ttl) * 0.9;
        ctx.beginPath();
        ctx.fillStyle = hexA(p.c, a);
        ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2);
        ctx.fill();
      }
      ctx.globalCompositeOperation = 'source-over';

      if (reduce) { // image statique : un seul rendu suffit
        cancelAnimationFrame(raf); raf = 0;
      }
    }
    frame();

    function hexA(hex, a) {
      var h = hex.replace('#', '');
      var n = parseInt(h.length === 3 ? h.replace(/(.)/g, '$1$1') : h, 16);
      return 'rgba(' + ((n >> 16) & 255) + ',' + ((n >> 8) & 255) + ',' + (n & 255) + ',' + a.toFixed(3) + ')';
    }

    return {
      dispose: function () {
        if (disposed) return;
        disposed = true;
        if (raf) cancelAnimationFrame(raf);
        window.removeEventListener('resize', resize);
        if (canvas.parentNode) canvas.parentNode.removeChild(canvas);
      }
    };
  }

  // ── Point d'entrée ───────────────────────────────────────────────────────────
  function mount(container, cosmetic) {
    if (!container || !cosmetic) return { dispose: function () {} };
    container.innerHTML = '';
    var type = (cosmetic.type || 'ITEM').toUpperCase();

    // Types FX → vignette particules, jamais de 3D.
    if (TYPES_FX[type]) {
      return mountFxVignette(container, cosmetic);
    }

    // ITEM / HAT → 3D si Three.js dispo ET texture présente.
    if (TYPES_3D[type] && hasThree() && imageUrlFor(cosmetic)) {
      try {
        return mount3DItem(container, cosmetic);
      } catch (e) {
        // WebGL indisponible / contexte perdu → fallback signalé à l'appelant.
        if (typeof container.__onNoTexture === 'function') container.__onNoTexture();
        return { noTexture: true, dispose: function () {} };
      }
    }

    // Sinon : pas de 3D possible → l'appelant affiche l'<img> ou l'icône.
    return { noTexture: true, dispose: function () {} };
  }

  window.McCosmeticViewer = { mount: mount };
})();
