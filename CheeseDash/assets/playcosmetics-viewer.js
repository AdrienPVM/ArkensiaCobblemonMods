/**
 * playcosmetics-viewer.js — Aperçu 3D d'un item vanilla (CheeseDash / Cosmétiques jouables)
 * ──────────────────────────────────────────────────────────────────────────
 * Petit viewer Three.js dérivé de CheeseStore/mc-cosmetic-viewer.js, mais adapté
 * au dashboard : l'item à afficher est un id Minecraft vanilla (ex
 * "minecraft:blaze_powder"). On récupère sa texture PNG via mkItemIconUrl()
 * (déjà fournie par app.js — sprites minecraft.wiki), puis on l'EXTRUDE pour
 * obtenir le rendu « item plat en 3D » authentique de Minecraft.
 *
 * Three.js + OrbitControls sont déjà chargés globalement par index.html
 * (assets/three.min.js + assets/OrbitControls.js) → pas de chargement paresseux.
 *
 * Stratégie (du plus riche au fallback) :
 *   • Texture chargée → maillage extrudé (faces avant/arrière + tranches).
 *   • Géométrie vide / texture transparente → plan texturé double-face tournant.
 *   • Texture introuvable (CORS/404) ou WebGL absent → on signale noTexture ;
 *     l'appelant retombe sur l'<img> 2D (mkItemImg).
 *
 * API : window.PlayCosmeticViewer.mount(container, itemId) → { dispose(), noTexture? }
 */
(function () {
  'use strict';

  function hasThree() {
    return typeof window.THREE !== 'undefined' && typeof window.THREE.WebGLRenderer === 'function';
  }

  function prefersReducedMotion() {
    return window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  }

  // Résout l'URL de texture d'un item vanilla. Réutilise le helper global du
  // dashboard quand il existe (cohérence avec le reste de l'UI), sinon fallback.
  function iconUrlFor(itemId) {
    if (!itemId) return null;
    if (typeof window.mkItemIconUrl === 'function') {
      const u = window.mkItemIconUrl(itemId);
      if (u) return u;
    }
    const clean = String(itemId).replace('minecraft:', '').replace(/[^a-z0-9_]/g, '');
    if (!clean) return null;
    const title = clean.split('_').map(w => w.charAt(0).toUpperCase() + w.slice(1)).join('_');
    return 'https://minecraft.wiki/images/Invicon_' + title + '.png';
  }

  // ── Chargement texture → ImageData (pour l'extrusion alpha) ─────────────────
  function loadImageData(url, cb) {
    var img = new Image();
    img.crossOrigin = 'anonymous';
    img.onload = function () {
      try {
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
  function buildExtrudedGeometry(THREE, imageData) {
    var w = imageData.width, h = imageData.height;
    var px = imageData.data;
    var alphaAt = function (x, y) {
      if (x < 0 || y < 0 || x >= w || y >= h) return 0;
      return px[(y * w + x) * 4 + 3];
    };
    var ALPHA_CUT = 32;
    var depth = Math.max(0.5, Math.min(w, h) * 0.06);
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

        pushQuad(
          [x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1],
          [0, 0, 1],
          [c.u0, c.v1], [c.u1, c.v1], [c.u1, c.v0], [c.u0, c.v0]
        );
        pushQuad(
          [x1, y0, z0], [x0, y0, z0], [x0, y1, z0], [x1, y1, z0],
          [0, 0, -1],
          [c.u1, c.v1], [c.u0, c.v1], [c.u0, c.v0], [c.u1, c.v0]
        );
        if (alphaAt(x - 1, y) < ALPHA_CUT) {
          pushQuad([x0, y0, z0], [x0, y0, z1], [x0, y1, z1], [x0, y1, z0],
            [-1, 0, 0], [c.u0, c.v1], [c.u0, c.v1], [c.u0, c.v0], [c.u0, c.v0]);
        }
        if (alphaAt(x + 1, y) < ALPHA_CUT) {
          pushQuad([x1, y0, z1], [x1, y0, z0], [x1, y1, z0], [x1, y1, z1],
            [1, 0, 0], [c.u1, c.v1], [c.u1, c.v1], [c.u1, c.v0], [c.u1, c.v0]);
        }
        if (alphaAt(x, y - 1) < ALPHA_CUT) {
          pushQuad([x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1],
            [0, 1, 0], [c.u0, c.v0], [c.u1, c.v0], [c.u1, c.v0], [c.u0, c.v0]);
        }
        if (alphaAt(x, y + 1) < ALPHA_CUT) {
          pushQuad([x0, y1, z1], [x1, y1, z1], [x1, y1, z0], [x0, y1, z0],
            [0, -1, 0], [c.u0, c.v1], [c.u1, c.v1], [c.u1, c.v1], [c.u0, c.v1]);
        }
      }
    }

    if (vCount === 0) return null;

    var geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
    geo.setAttribute('normal', new THREE.Float32BufferAttribute(normals, 3));
    geo.setAttribute('uv', new THREE.Float32BufferAttribute(uvs, 2));
    geo.setIndex(indices);
    return geo;
  }

  function makeScene(THREE, container) {
    var width = container.clientWidth || 220;
    var height = container.clientHeight || 220;

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

    scene.add(new THREE.AmbientLight(0xffffff, 0.9));
    var key = new THREE.DirectionalLight(0xffe6b0, 0.8); key.position.set(2, 3, 4); scene.add(key);
    var rim = new THREE.DirectionalLight(0xff7a3c, 0.5); rim.position.set(-3, -1, -2); scene.add(rim);

    return { renderer: renderer, scene: scene, camera: camera, width: width, height: height };
  }

  function mount3DItem(container, itemId) {
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

    var url = iconUrlFor(itemId);

    function buildPlaneFallback(tex) {
      geometry = new THREE.PlaneGeometry(2.2, 2.2);
      material = new THREE.MeshBasicMaterial({ map: tex, transparent: true, side: THREE.DoubleSide, alphaTest: 0.1 });
      mesh = new THREE.Mesh(geometry, material);
      ctx.scene.add(mesh);
      addControls();
      animate();
    }

    if (!url) {
      cleanup();
      return { noTexture: true, dispose: function () {} };
    }

    loadImageData(url, function (err, imageData, img) {
      if (disposed) return;
      if (err) {
        cleanup();
        if (typeof container.__onNoTexture === 'function') container.__onNoTexture();
        return;
      }
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

  // ── Point d'entrée ──────────────────────────────────────────────────────────
  function mount(container, itemId) {
    if (!container) return { dispose: function () {} };
    container.innerHTML = '';
    if (hasThree() && iconUrlFor(itemId)) {
      try {
        return mount3DItem(container, itemId);
      } catch (e) {
        if (typeof container.__onNoTexture === 'function') container.__onNoTexture();
        return { noTexture: true, dispose: function () {} };
      }
    }
    return { noTexture: true, dispose: function () {} };
  }

  window.PlayCosmeticViewer = { mount: mount };
})();
