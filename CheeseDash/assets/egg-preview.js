/**
 * EggPreview — 3D procedural egg preview using Three.js
 * Ports the Java ProceduralTextureGenerator (13-step pipeline) + CollectibleModelRenderer voxels.
 */

// ═══════════════════════════════════════════════════════════════════════════════
//  CONSTANTS
// ═══════════════════════════════════════════════════════════════════════════════

const EGG_SIZE = 64; // texture resolution
const EGG_Y_MAX = 1.0;

// 472 surface voxels — compact int format: [x1*16,y1*16,z1*16,x2*16,y2*16,z2*16,faceMask]
// Face mask bits: PX=1, MX=2, PY=4, MY=8, PZ=16, MZ=32
const EGG_VOXELS = [
1,4,7,2,5,8,42,1,4,8,2,5,9,26,1,5,6,2,6,7,42,1,5,7,2,6,8,2,1,5,8,2,6,9,2,1,5,9,2,6,10,26,1,6,5,2,7,6,46,1,6,6,2,7,7,2,1,6,7,2,7,8,2,1,6,8,2,7,9,2,1,6,9,2,7,10,2,1,6,10,2,7,11,30,1,7,6,2,8,7,34,1,7,7,2,8,8,2,1,7,8,2,8,9,2,1,7,9,2,8,10,18,1,8,6,2,9,7,38,1,8,7,2,9,8,6,1,8,8,2,9,9,6,1,8,9,2,9,10,22,2,3,5,3,4,6,42,2,3,6,3,4,7,10,2,3,7,3,4,8,10,2,3,8,3,4,9,10,2,3,9,3,4,10,10,2,3,10,3,4,11,26,2,4,4,3,5,5,42,2,4,5,3,5,6,2,2,4,6,3,5,7,2,2,4,9,3,5,10,2,2,4,10,3,5,11,2,2,4,11,3,5,12,26,2,5,4,3,6,5,34,2,5,5,3,6,6,2,2,5,10,3,6,11,2,2,5,11,3,6,12,18,2,6,4,3,7,5,34,2,6,11,3,7,12,18,2,7,4,3,8,5,34,2,7,5,3,8,6,2,2,7,10,3,8,11,2,2,7,11,3,8,12,18,2,8,4,3,9,5,38,2,8,5,3,9,6,2,2,8,10,3,9,11,2,2,8,11,3,9,12,22,2,9,5,3,10,6,38,2,9,6,3,10,7,2,2,9,7,3,10,8,2,2,9,8,3,10,9,2,2,9,9,3,10,10,2,2,9,10,3,10,11,22,2,10,6,3,11,7,38,2,10,7,3,11,8,6,2,10,8,3,11,9,6,2,10,9,3,11,10,22,3,2,5,4,3,6,42,3,2,6,4,3,7,10,3,2,7,4,3,8,10,3,2,8,4,3,9,10,3,2,9,4,3,10,10,3,2,10,4,3,11,26,3,3,4,4,4,5,42,3,3,11,4,4,12,26,3,4,3,4,5,4,42,3,4,12,4,5,13,26,3,5,3,4,6,4,34,3,5,12,4,6,13,18,3,6,3,4,7,4,34,3,6,12,4,7,13,18,3,7,3,4,8,4,34,3,7,12,4,8,13,18,3,8,3,4,9,4,34,3,8,12,4,9,13,18,3,9,3,4,10,4,38,3,9,4,4,10,5,2,3,9,11,4,10,12,2,3,9,12,4,10,13,22,3,10,4,4,11,5,38,3,10,5,4,11,6,2,3,10,10,4,11,11,2,3,10,11,4,11,12,22,3,11,5,4,12,6,38,3,11,6,4,12,7,2,3,11,7,4,12,8,2,3,11,8,4,12,9,2,3,11,9,4,12,10,2,3,11,10,4,12,11,22,3,12,6,4,13,7,38,3,12,7,4,13,8,6,3,12,8,4,13,9,6,3,12,9,4,13,10,22,4,1,6,5,2,7,42,4,1,7,5,2,8,10,4,1,8,5,2,9,10,4,1,9,5,2,10,26,4,2,4,5,3,5,42,4,2,5,5,3,6,8,4,2,10,5,3,11,8,4,2,11,5,3,12,26,4,3,3,5,4,4,42,4,3,12,5,4,13,26,4,4,2,5,5,3,42,4,4,13,5,5,14,26,4,5,2,5,6,3,34,4,5,13,5,6,14,18,4,6,2,5,7,3,34,4,6,13,5,7,14,18,4,7,2,5,8,3,34,4,7,13,5,8,14,18,4,8,2,5,9,3,38,4,8,13,5,9,14,22,4,9,3,5,10,4,32,4,9,12,5,10,13,16,4,10,3,5,11,4,38,4,10,12,5,11,13,22,4,11,4,5,12,5,38,4,11,11,5,12,12,22,4,12,5,5,13,6,38,4,12,10,5,13,11,22,4,13,6,5,14,7,38,4,13,7,5,14,8,6,4,13,8,5,14,9,6,4,13,9,5,14,10,22,5,1,5,6,2,6,42,5,1,6,6,2,7,8,5,1,7,6,2,8,8,5,1,8,6,2,9,8,5,1,9,6,2,10,8,5,1,10,6,2,11,26,5,2,3,6,3,4,42,5,2,4,6,3,5,8,5,2,11,6,3,12,8,5,2,12,6,3,13,26,5,3,2,6,4,3,42,5,3,13,6,4,14,26,5,4,2,6,5,3,32,5,4,13,6,5,14,16,5,5,2,6,6,3,32,5,5,13,6,6,14,16,5,6,1,6,7,2,46,5,6,14,6,7,15,30,5,7,2,6,8,3,32,5,7,13,6,8,14,16,5,8,2,6,9,3,32,5,8,13,6,9,14,16,5,9,2,6,10,3,38,5,9,13,6,10,14,22,5,10,3,6,11,4,32,5,10,12,6,11,13,16,5,11,3,6,12,4,38,5,11,12,6,12,13,22,5,12,4,6,13,5,38,5,12,11,6,13,12,22,5,13,5,6,14,6,38,5,13,6,6,14,7,4,5,13,9,6,14,10,4,5,13,10,6,14,11,22,5,14,7,6,15,8,38,5,14,8,6,15,9,22,6,0,7,7,1,8,42,6,0,8,7,1,9,26,6,1,4,7,2,5,42,6,1,5,7,2,6,8,6,1,6,7,2,7,8,6,1,9,7,2,10,8,6,1,10,7,2,11,8,6,1,11,7,2,12,26,6,2,3,7,3,4,40,6,2,12,7,3,13,24,6,3,2,7,4,3,40,6,3,13,7,4,14,24,6,4,2,7,5,3,32,6,4,13,7,5,14,16,6,5,1,7,6,2,42,6,5,14,7,6,15,26,6,6,1,7,7,2,32,6,6,14,7,7,15,16,6,7,1,7,8,2,34,6,7,14,7,8,15,18,6,8,1,7,9,2,38,6,8,14,7,9,15,22,6,9,2,7,10,3,32,6,9,13,7,10,14,16,6,10,2,7,11,3,38,6,10,13,7,11,14,22,6,11,3,7,12,4,32,6,11,12,7,12,13,16,6,12,3,7,13,4,38,6,12,12,7,13,13,22,6,13,4,7,14,5,38,6,13,5,7,14,6,4,6,13,10,7,14,11,4,6,13,11,7,14,12,22,6,14,6,7,15,7,38,6,14,7,7,15,8,4,6,14,8,7,15,9,4,6,14,9,7,15,10,22,7,0,6,8,1,7,42,7,0,7,8,1,8,8,7,0,8,8,1,9,8,7,0,9,8,1,10,26,7,1,4,8,2,5,40,7,1,5,8,2,6,8,7,1,10,8,2,11,8,7,1,11,8,2,12,24,7,2,3,8,3,4,40,7,2,12,8,3,13,24,7,3,2,8,4,3,40,7,3,13,8,4,14,24,7,4,1,8,5,2,42,7,4,14,8,5,15,26,7,5,1,8,6,2,32,7,5,14,8,6,15,16,7,6,1,8,7,2,32,7,6,14,8,7,15,16,7,7,1,8,8,2,32,7,7,14,8,8,15,16,7,8,1,8,9,2,36,7,8,14,8,9,15,20,7,9,2,8,10,3,32,7,9,13,8,10,14,16,7,10,2,8,11,3,36,7,10,13,8,11,14,20,7,11,3,8,12,4,32,7,11,12,8,12,13,16,7,12,3,8,13,4,36,7,12,12,8,13,13,20,7,13,4,8,14,5,36,7,13,11,8,14,12,20,7,14,5,8,15,6,38,7,14,6,8,15,7,4,7,14,9,8,15,10,4,7,14,10,8,15,11,22,7,15,7,8,16,8,38,7,15,8,8,16,9,22,8,0,6,9,1,7,41,8,0,7,9,1,8,8,8,0,8,9,1,9,8,8,0,9,9,1,10,25,8,1,4,9,2,5,40,8,1,5,9,2,6,8,8,1,10,9,2,11,8,8,1,11,9,2,12,24,8,2,3,9,3,4,40,8,2,12,9,3,13,24,8,3,2,9,4,3,40,8,3,13,9,4,14,24,8,4,1,9,5,2,41,8,4,14,9,5,15,25,8,5,1,9,6,2,32,8,5,14,9,6,15,16,8,6,1,9,7,2,32,8,6,14,9,7,15,16,8,7,1,9,8,2,32,8,7,14,9,8,15,16,8,8,1,9,9,2,36,8,8,14,9,9,15,20,8,9,2,9,10,3,32,8,9,13,9,10,14,16,8,10,2,9,11,3,36,8,10,13,9,11,14,20,8,11,3,9,12,4,32,8,11,12,9,12,13,16,8,12,3,9,13,4,36,8,12,12,9,13,13,20,8,13,4,9,14,5,36,8,13,11,9,14,12,20,8,14,5,9,15,6,37,8,14,6,9,15,7,4,8,14,9,9,15,10,4,8,14,10,9,15,11,21,8,15,7,9,16,8,37,8,15,8,9,16,9,21,9,0,7,10,1,8,41,9,0,8,10,1,9,25,9,1,4,10,2,5,41,9,1,5,10,2,6,8,9,1,6,10,2,7,8,9,1,9,10,2,10,8,9,1,10,10,2,11,8,9,1,11,10,2,12,25,9,2,3,10,3,4,40,9,2,12,10,3,13,24,9,3,2,10,4,3,40,9,3,13,10,4,14,24,9,4,2,10,5,3,32,9,4,13,10,5,14,16,9,5,1,10,6,2,41,9,5,14,10,6,15,25,9,6,1,10,7,2,32,9,6,14,10,7,15,16,9,7,1,10,8,2,33,9,7,14,10,8,15,17,9,8,1,10,9,2,37,9,8,14,10,9,15,21,9,9,2,10,10,3,32,9,9,13,10,10,14,16,9,10,2,10,11,3,37,9,10,13,10,11,14,21,9,11,3,10,12,4,32,9,11,12,10,12,13,16,9,12,3,10,13,4,37,9,12,12,10,13,13,21,9,13,4,10,14,5,37,9,13,5,10,14,6,4,9,13,10,10,14,11,4,9,13,11,10,14,12,21,9,14,6,10,15,7,37,9,14,7,10,15,8,4,9,14,8,10,15,9,4,9,14,9,10,15,10,21,10,1,5,11,2,6,41,10,1,6,11,2,7,8,10,1,7,11,2,8,8,10,1,8,11,2,9,8,10,1,9,11,2,10,8,10,1,10,11,2,11,25,10,2,3,11,3,4,41,10,2,4,11,3,5,8,10,2,11,11,3,12,8,10,2,12,11,3,13,25,10,3,2,11,4,3,41,10,3,13,11,4,14,25,10,4,2,11,5,3,32,10,4,13,11,5,14,16,10,5,2,11,6,3,32,10,5,13,11,6,14,16,10,6,1,11,7,2,45,10,6,14,11,7,15,29,10,7,2,11,8,3,32,10,7,13,11,8,14,16,10,8,2,11,9,3,32,10,8,13,11,9,14,16,10,9,2,11,10,3,37,10,9,13,11,10,14,21,10,10,3,11,11,4,32,10,10,12,11,11,13,16,10,11,3,11,12,4,37,10,11,12,11,12,13,21,10,12,4,11,13,5,37,10,12,11,11,13,12,21,10,13,5,11,14,6,37,10,13,6,11,14,7,4,10,13,9,11,14,10,4,10,13,10,11,14,11,21,10,14,7,11,15,8,37,10,14,8,11,15,9,21,11,1,6,12,2,7,41,11,1,7,12,2,8,9,11,1,8,12,2,9,9,11,1,9,12,2,10,25,11,2,4,12,3,5,41,11,2,5,12,3,6,8,11,2,10,12,3,11,8,11,2,11,12,3,12,25,11,3,3,12,4,4,41,11,3,12,12,4,13,25,11,4,2,12,5,3,41,11,4,13,12,5,14,25,11,5,2,12,6,3,33,11,5,13,12,6,14,17,11,6,2,12,7,3,33,11,6,13,12,7,14,17,11,7,2,12,8,3,33,11,7,13,12,8,14,17,11,8,2,12,9,3,37,11,8,13,12,9,14,21,11,9,3,12,10,4,32,11,9,12,12,10,13,16,11,10,3,12,11,4,37,11,10,12,12,11,13,21,11,11,4,12,12,5,37,11,11,11,12,12,12,21,11,12,5,12,13,6,37,11,12,10,12,13,11,21,11,13,6,12,14,7,37,11,13,7,12,14,8,5,11,13,8,12,14,9,5,11,13,9,12,14,10,21,12,2,5,13,3,6,41,12,2,6,13,3,7,9,12,2,7,13,3,8,9,12,2,8,13,3,9,9,12,2,9,13,3,10,9,12,2,10,13,3,11,25,12,3,4,13,4,5,41,12,3,11,13,4,12,25,12,4,3,13,5,4,41,12,4,12,13,5,13,25,12,5,3,13,6,4,33,12,5,12,13,6,13,17,12,6,3,13,7,4,33,12,6,12,13,7,13,17,12,7,3,13,8,4,33,12,7,12,13,8,13,17,12,8,3,13,9,4,33,12,8,12,13,9,13,17,12,9,3,13,10,4,37,12,9,4,13,10,5,1,12,9,11,13,10,12,1,12,9,12,13,10,13,21,12,10,4,13,11,5,37,12,10,5,13,11,6,1,12,10,10,13,11,11,1,12,10,11,13,11,12,21,12,11,5,13,12,6,37,12,11,6,13,12,7,1,12,11,7,13,12,8,1,12,11,8,13,12,9,1,12,11,9,13,12,10,1,12,11,10,13,12,11,21,12,12,6,13,13,7,37,12,12,7,13,13,8,5,12,12,8,13,13,9,5,12,12,9,13,13,10,21,13,3,5,14,4,6,41,13,3,6,14,4,7,9,13,3,7,14,4,8,9,13,3,8,14,4,9,9,13,3,9,14,4,10,9,13,3,10,14,4,11,25,13,4,4,14,5,5,41,13,4,5,14,5,6,1,13,4,6,14,5,7,1,13,4,9,14,5,10,1,13,4,10,14,5,11,1,13,4,11,14,5,12,25,13,5,4,14,6,5,33,13,5,5,14,6,6,1,13,5,10,14,6,11,1,13,5,11,14,6,12,17,13,6,4,14,7,5,33,13,6,11,14,7,12,17,13,7,4,14,8,5,33,13,7,5,14,8,6,1,13,7,10,14,8,11,1,13,7,11,14,8,12,17,13,8,4,14,9,5,37,13,8,5,14,9,6,1,13,8,10,14,9,11,1,13,8,11,14,9,12,21,13,9,5,14,10,6,37,13,9,6,14,10,7,1,13,9,7,14,10,8,1,13,9,8,14,10,9,1,13,9,9,14,10,10,1,13,9,10,14,10,11,21,13,10,6,14,11,7,37,13,10,7,14,11,8,5,13,10,8,14,11,9,5,13,10,9,14,11,10,21,14,4,7,15,5,8,41,14,4,8,15,5,9,25,14,5,6,15,6,7,41,14,5,7,15,6,8,1,14,5,8,15,6,9,1,14,5,9,15,6,10,25,14,6,5,15,7,6,45,14,6,6,15,7,7,1,14,6,7,15,7,8,1,14,6,8,15,7,9,1,14,6,9,15,7,10,1,14,6,10,15,7,11,29,14,7,6,15,8,7,33,14,7,7,15,8,8,1,14,7,8,15,8,9,1,14,7,9,15,8,10,17,14,8,6,15,9,7,37,14,8,7,15,9,8,5,14,8,8,15,9,9,5,14,8,9,15,9,10,21
];

// 18 palettes: [primary, secondary, highlight, shadow, accent, glow] as ARGB ints
const PALETTES = {
  WARM:      [0xFFE07030,0xFFFF9050,0xFFFFD080,0xFFBB4010,0xFFFFB040,0xFFFF6010],
  COOL:      [0xFF3060E0,0xFF50A0FF,0xFF80D0FF,0xFF1030A0,0xFF00FFDD,0xFF2080FF],
  NATURE:    [0xFF40A030,0xFF70C050,0xFFA0D060,0xFF105010,0xFFD4E830,0xFF00FF80],
  DARK:      [0xFF301040,0xFF602080,0xFF9050D0,0xFF100020,0xFFCC00FF,0xFF8800CC],
  PASTEL:    [0xFFFFB8C8,0xFFB8D8FF,0xFFB8FFD0,0xFFFFE8B8,0xFFE8B8FF,0xFFFF88BB],
  FIRE:      [0xFFFF2000,0xFFFF8000,0xFFFFD000,0xFFCC1000,0xFFFF5500,0xFFFFFF00],
  ICE:       [0xFFA0E8FF,0xFFD0F8FF,0xFF80C0FF,0xFF4090D0,0xFFFFFFFF,0xFF00DDFF],
  MYSTIC:    [0xFF8000FF,0xFFFF00C0,0xFFCC60FF,0xFF4000AA,0xFFFF80FF,0xFFBB00FF],
  GOLD:      [0xFFFFD700,0xFFFFA500,0xFFFFEC8B,0xFFA07000,0xFFFFFF80,0xFFFFCC00],
  CANDY:     [0xFFFF69B4,0xFFFF1493,0xFFFFB6C1,0xFFCC1070,0xFFFF88FF,0xFFFF00AA],
  NEBULA:    [0xFF0A0A2E,0xFF3B006E,0xFF7B2FBE,0xFF050510,0xFFFF6B9D,0xFF00FFCC],
  LAVA:      [0xFF1A0000,0xFFCC2200,0xFFFF8800,0xFF0D0000,0xFFFFDD00,0xFFFF4400],
  FOREST:    [0xFF1B3A1B,0xFF2D6A2D,0xFF8BC34A,0xFF0A1A0A,0xFFCDDC39,0xFF76FF03],
  ARCTIC:    [0xFFF0F8FF,0xFFB0C8E0,0xFFFFFFFF,0xFF607890,0xFF88CCFF,0xFF00AAFF],
  CORRUPTED: [0xFF1A001A,0xFF660066,0xFFCC00CC,0xFF000000,0xFFFF00FF,0xFFAA00AA],
  CELESTIAL: [0xFF000820,0xFF001050,0xFF4488FF,0xFF000010,0xFFFFEE88,0xFF88CCFF],
  NEON:      [0xFF0D0D0D,0xFF00FFFF,0xFFFF00FF,0xFF000000,0xFF00FF00,0xFFFF00FF],
  RAINBOW:   [0xFFFF0000,0xFF00CC00,0xFF0000FF,0xFF800000,0xFFFFFF00,0xFFFF8800],
  OCEAN:     [0xFF005577,0xFF0088AA,0xFF00CCDD,0xFF003344,0xFF44EEFF,0xFF00AACC],
  SUNSET:    [0xFFFF4500,0xFFFF6347,0xFFFFD700,0xFFCC3300,0xFFFF8C00,0xFFFF1493],
  AUTUMN:    [0xFF8B4513,0xFFCD853F,0xFFDAA520,0xFF5C2E00,0xFFFF6600,0xFFFFA500],
  SPRING:    [0xFF88CC44,0xFFAADD66,0xFFFFFFAA,0xFF558833,0xFFFF88CC,0xFF66FF66],
  CYBERPUNK: [0xFF0D0D0D,0xFFFF0055,0xFF00FFCC,0xFF000000,0xFFFF00AA,0xFF00CCFF],
  STEAMPUNK: [0xFF8B6914,0xFFCD9B1D,0xFFEEC900,0xFF5C4500,0xFFB87333,0xFFC5A000],
  DESERT:    [0xFFDEB887,0xFFF5DEB3,0xFFFFE4C4,0xFFA0825A,0xFFFF9933,0xFFFFCC66],
  VOLCANIC:  [0xFF2A0000,0xFF880000,0xFFFF4400,0xFF110000,0xFFFFAA00,0xFFFF2200],
  AURORA:    [0xFF004466,0xFF00CC88,0xFF88FF44,0xFF002233,0xFFFF44AA,0xFF00FFAA],
  AMETHYST:  [0xFF6A0DAD,0xFF9B30FF,0xFFDA70D6,0xFF3B0066,0xFFE040FB,0xFFBB44FF],
  JADE:      [0xFF00A86B,0xFF50C878,0xFF98FB98,0xFF005533,0xFF00FF7F,0xFF66FFAA],
  RUST:      [0xFFB7410E,0xFFCC5500,0xFFE07020,0xFF802A00,0xFFFF7733,0xFFDD4400],
  BLUEBERRY: [0xFF4169E1,0xFF6495ED,0xFF87CEEB,0xFF1C3D8F,0xFF88BBFF,0xFF3366FF],
  PEACH:     [0xFFFFDAB9,0xFFFFCBA4,0xFFFFE5CC,0xFFCC9977,0xFFFF9999,0xFFFFBB88],
  TOXIC:     [0xFF003300,0xFF00CC00,0xFF88FF00,0xFF001100,0xFFCCFF00,0xFF00FF44],
};


const FLAG_LIGHTING = 0x01, FLAG_SPECULAR = 0x02, FLAG_EMBOSS = 0x04;
const FLAG_VARNISH = 0x08, FLAG_RIM_GLOW = 0x10;

// ═══════════════════════════════════════════════════════════════════════════════
//  SEEDED RANDOM (splitmix32, similar distribution to Java Random)
// ═══════════════════════════════════════════════════════════════════════════════

class SeededRandom {
  constructor(seed) {
    this.state = seed | 0;
  }
  _next() {
    this.state = (this.state + 0x9e3779b9) | 0;
    let z = this.state;
    z = Math.imul(z ^ (z >>> 16), 0x85ebca6b);
    z = Math.imul(z ^ (z >>> 13), 0xc2b2ae35);
    return (z ^ (z >>> 16)) >>> 0;
  }
  nextInt(bound) { return this._next() % bound; }
  nextFloat() { return this._next() / 4294967296; }
  nextBoolean() { return (this._next() & 1) === 1; }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  NOISE FUNCTIONS
// ═══════════════════════════════════════════════════════════════════════════════

function _hash(x, y, seed) {
  // 32-bit hash approximation of the Java 64-bit version
  let h = seed ^ Math.imul(x, 1234567891) ^ Math.imul(y, 987654321);
  h = Math.imul(h ^ (h >>> 16), 0x85ebca6b);
  h = Math.imul(h ^ (h >>> 13), 0xc2b2ae35);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
}

function valueNoise(x, y, seed) {
  const xi = Math.floor(x), yi = Math.floor(y);
  let fx = x - xi, fy = y - yi;
  fx = fx * fx * (3 - 2 * fx);
  fy = fy * fy * (3 - 2 * fy);
  const s = seed | 0;
  const h00 = _hash(xi, yi, s), h10 = _hash(xi + 1, yi, s);
  const h01 = _hash(xi, yi + 1, s), h11 = _hash(xi + 1, yi + 1, s);
  return h00 + (h10 - h00) * fx + (h01 - h00) * fy + (h00 - h10 - h01 + h11) * fx * fy;
}

function turbulence(x, y, seed, oct) {
  let s = 0, a = 1, f = 1, m = 0;
  for (let i = 0; i < oct; i++) {
    s += Math.abs(valueNoise(x * f, y * f, (seed + i * 7919) | 0) * 2 - 1) * a;
    m += a; a *= 0.5; f *= 2;
  }
  return s / m;
}

function veinNoise(x, y, seed) {
  return (1 - Math.abs(valueNoise(x, y, seed) * 2 - 1)) * 0.6
    + (1 - Math.abs(valueNoise(x * 2, y * 2, (seed + 13) | 0) * 2 - 1)) * 0.25
    + (1 - Math.abs(valueNoise(x * 4, y * 4, (seed + 31) | 0) * 2 - 1)) * 0.15;
}

function hexGrid(x, y) {
  const s3 = 1.732, hx = (x - y / s3) / 2, hz = y * s3 / 2, hy = -hx - hz;
  const rx = Math.round(hx), ry = Math.round(hy), rz = Math.round(hz);
  return 1 - Math.hypot(hx - rx, hz - rz) * 2;
}

// ═══════════════════════════════════════════════════════════════════════════════
//  COLOR UTILITIES (all ARGB int format)
// ═══════════════════════════════════════════════════════════════════════════════

function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

function lerpColor(c1, c2, t) {
  t = clamp(t, 0, 1);
  const r = ((c1 >> 16) & 0xFF) + (((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * t;
  const g = ((c1 >> 8) & 0xFF) + (((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * t;
  const b = (c1 & 0xFF) + ((c2 & 0xFF) - (c1 & 0xFF)) * t;
  return 0xFF000000 | (r << 16) | (g << 8) | b;
}

function brighten(c, a) {
  return 0xFF000000
    | (Math.min(255, ((c >> 16) & 0xFF) + 255 * a) << 16)
    | (Math.min(255, ((c >> 8) & 0xFF) + 255 * a) << 8)
    | Math.min(255, (c & 0xFF) + 255 * a);
}

function colorBrightness(rgb) {
  return (((rgb >> 16) & 0xFF) * 0.299 + ((rgb >> 8) & 0xFF) * 0.587 + (rgb & 0xFF) * 0.114) / 255;
}

// ═══════════════════════════════════════════════════════════════════════════════
//  CANVAS PIXEL ACCESS (ImageData RGBA ↔ ARGB int)
// ═══════════════════════════════════════════════════════════════════════════════

function getPixel(data, x, y) {
  const i = (y * EGG_SIZE + x) * 4;
  return 0xFF000000 | (data[i] << 16) | (data[i + 1] << 8) | data[i + 2];
}

function setPixel(data, x, y, argb) {
  const i = (y * EGG_SIZE + x) * 4;
  data[i] = (argb >> 16) & 0xFF;
  data[i + 1] = (argb >> 8) & 0xFF;
  data[i + 2] = argb & 0xFF;
  data[i + 3] = 255;
}

// ═══════════════════════════════════════════════════════════════════════════════
//  DRAWING PRIMITIVES
// ═══════════════════════════════════════════════════════════════════════════════

function drawEllipse(d, cx, cy, rx, ry, color, alpha) {
  for (let y = cy - ry; y <= cy + ry; y++) for (let x = cx - rx; x <= cx + rx; x++) {
    if (x < 0 || y < 0 || x >= EGG_SIZE || y >= EGG_SIZE) continue;
    const dx = (x - cx) / rx, dy = (y - cy) / ry, dist = dx * dx + dy * dy;
    if (dist <= 1) setPixel(d, x, y, lerpColor(getPixel(d, x, y), color, alpha * (1 - dist * 0.4)));
  }
}

function drawLine(d, x1, y1, x2, y2, color, alpha) {
  let dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1);
  let sx = x1 < x2 ? 1 : -1, sy = y1 < y2 ? 1 : -1, err = dx - dy;
  let x = x1, y = y1;
  while (true) {
    if (x >= 0 && y >= 0 && x < EGG_SIZE && y < EGG_SIZE)
      setPixel(d, x, y, lerpColor(getPixel(d, x, y), color, alpha));
    if (x === x2 && y === y2) break;
    const e2 = 2 * err;
    if (e2 > -dy) { err -= dy; x += sx; }
    if (e2 < dx) { err += dx; y += sy; }
  }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  SEAMLESS WRAPPING HELPERS (horizontal tiling for cylindrical UV mapping)
// ═══════════════════════════════════════════════════════════════════════════════

// Seamless 2D value noise via torus mapping — x wraps at EGG_SIZE, y is linear.
// Uses two noise samples (cos/sin of angle) combined with y to preserve 2D richness.
function seamlessValueNoise(x, y, xScale, yScale, seed) {
  const angle = (x / EGG_SIZE) * Math.PI * 2;
  const r = EGG_SIZE * xScale / (Math.PI * 2);
  const cx = Math.cos(angle) * r, sx = Math.sin(angle) * r;
  const ny = y * yScale;
  return (valueNoise(cx + 50, ny + 10, seed) + valueNoise(sx + 50, ny + 30, (seed + 7919) | 0)) * 0.5;
}

function seamlessTurbulence(x, y, xScale, yScale, seed, oct) {
  let s = 0, a = 1, f = 1, m = 0;
  for (let i = 0; i < oct; i++) {
    s += Math.abs(seamlessValueNoise(x, y, xScale * f, yScale * f, (seed + i * 7919) | 0) * 2 - 1) * a;
    m += a; a *= 0.5; f *= 2;
  }
  return s / m;
}

function seamlessVeinNoise(x, y, xScale, yScale, seed) {
  return (1 - Math.abs(seamlessValueNoise(x, y, xScale, yScale, seed) * 2 - 1)) * 0.6
    + (1 - Math.abs(seamlessValueNoise(x, y, xScale * 2, yScale * 2, (seed + 13) | 0) * 2 - 1)) * 0.25
    + (1 - Math.abs(seamlessValueNoise(x, y, xScale * 4, yScale * 4, (seed + 31) | 0) * 2 - 1)) * 0.15;
}

// Drawing with horizontal wrapping — draws at cx ± EGG_SIZE so shapes near edges appear on both sides
function drawEllipseWrapped(d, cx, cy, rx, ry, col, alpha) {
  drawEllipse(d, cx, cy, rx, ry, col, alpha);
  if (cx - rx < 0) drawEllipse(d, cx + EGG_SIZE, cy, rx, ry, col, alpha);
  if (cx + rx >= EGG_SIZE) drawEllipse(d, cx - EGG_SIZE, cy, rx, ry, col, alpha);
}

function setPixelWrapped(d, x, y, argb) {
  if (y < 0 || y >= EGG_SIZE) return;
  setPixel(d, ((x % EGG_SIZE) + EGG_SIZE) % EGG_SIZE, y, argb);
}

function drawLineWrapped(d, x1, y1, x2, y2, col, alpha) {
  let dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1);
  let sx = x1 < x2 ? 1 : -1, sy = y1 < y2 ? 1 : -1, err = dx - dy;
  let x = x1, y = y1;
  while (true) {
    const wx = ((x % EGG_SIZE) + EGG_SIZE) % EGG_SIZE;
    if (y >= 0 && y < EGG_SIZE)
      setPixel(d, wx, y, lerpColor(getPixel(d, wx, y), col, alpha));
    if (x === x2 && y === y2) break;
    const e2 = 2 * err;
    if (e2 > -dy) { err -= dy; x += sx; }
    if (e2 < dx) { err += dx; y += sy; }
  }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  STAMPS
// ═══════════════════════════════════════════════════════════════════════════════

function applyStamp(d, dna, rng, highlight, accent, glow, secondary) {
  if (!dna.stamp || dna.stamp === 'NONE') return;
  const r = 6, cols = 4, rows = 4;
  const stepX = EGG_SIZE / cols, stepY = EGG_SIZE / rows;
  const col1 = highlight, col2 = secondary;
  const alpha = 0.70;
  for (let row = 0; row < rows; row++) {
    const offsetX = (row % 2 === 0) ? 0 : stepX / 2;
    for (let col = 0; col < cols; col++) {
      let cx = (offsetX + col * stepX + stepX / 2) | 0;
      let cy = (row * stepY + stepY / 2) | 0;
      cx = ((cx % EGG_SIZE) + EGG_SIZE) % EGG_SIZE;
      cy = clamp(cy, r + 2, EGG_SIZE - r - 3);
      switch (dna.stamp) {
        case 'STAR':     drawStar(d, cx, cy, r, r / 2, 5, col1, col2, alpha); break;
        case 'MOON':     drawMoon(d, cx, cy, r, col1, alpha); break;
        case 'HEART':    drawHeart(d, cx, cy, r, col1, col2, alpha); break;
        case 'SPIRAL':   drawSpiral(d, cx, cy, r, col1, alpha); break;
        case 'DIAMOND':  drawDiamond(d, cx, cy, r, col1, col2, alpha); break;
        case 'RUNE_TRIPLE': drawRuneTriple(d, cx, cy, r, col1, alpha); break;
        case 'ARROW':    drawArrow(d, cx, cy, r, col1, alpha); break;
        case 'CROWN':    drawCrown(d, cx, cy, r, col1, col2, alpha); break;
        case 'INFINITY': drawInfinity(d, cx, cy, r, col1, alpha); break;
      }
    }
  }
}

function drawStar(d, cx, cy, outerR, innerR, points, col1, col2, alpha) {
  for (let y = cy - outerR - 2; y <= cy + outerR + 2; y++) {
    if (y < 0 || y >= EGG_SIZE) continue;
    for (let dx = -outerR - 2; dx <= outerR + 2; dx++) {
      const x = ((cx + dx) % EGG_SIZE + EGG_SIZE) % EGG_SIZE;
      const angle = Math.atan2(y - cy, dx) + Math.PI / 2;
      const dist = Math.hypot(dx, y - cy);
      const section = angle / (Math.PI * 2 / points);
      const frac = section - Math.floor(section);
      const starR = innerR + (outerR - innerR) * (1 - Math.abs(frac - 0.5) * 2);
      if (dist <= starR + 0.5) {
        const t = dist / starR;
        const col = t < 0.5 ? col1 : col2;
        const a = alpha * (1 - Math.max(0, dist - starR));
        setPixel(d, x, y, lerpColor(getPixel(d, x, y), col, a));
      }
    }
  }
}

function drawMoon(d, cx, cy, r, col, alpha) {
  const off = (r / 3) | 0;
  for (let y = cy - r; y <= cy + r; y++) {
    if (y < 0 || y >= EGG_SIZE) continue;
    for (let ddx = -r; ddx <= r; ddx++) {
      const x = ((cx + ddx) % EGG_SIZE + EGG_SIZE) % EGG_SIZE;
      const d1 = Math.hypot(ddx, y - cy), d2 = Math.hypot(ddx - off, y - cy);
      if (d1 <= r && d2 > r * 0.75)
        setPixel(d, x, y, lerpColor(getPixel(d, x, y), col, alpha * (1 - d1 / r * 0.3)));
    }
  }
}

function drawHeart(d, cx, cy, r, col1, col2, alpha) {
  for (let dy = -r; dy <= r; dy++) for (let dx = -r; dx <= r; dx++) {
    const x = ((cx + dx) % EGG_SIZE + EGG_SIZE) % EGG_SIZE, y = cy - dy;
    if (y < 0 || y >= EGG_SIZE) continue;
    const nx = dx / r, ny = dy / r;
    const v = nx * nx + ny * ny - 1;
    const heart = v * v * v - nx * nx * ny * ny * ny;
    if (heart <= 0.08) {
      const t = Math.max(0, heart / 0.08);
      setPixel(d, x, y, lerpColor(getPixel(d, x, y), lerpColor(col1, col2, t), alpha * (1 - t * 0.4)));
    }
  }
}

function drawSpiral(d, cx, cy, r, col, alpha) {
  const turns = 3;
  for (let t = 0; t < turns * Math.PI * 2; t += 0.05) {
    const sr = (t / (turns * Math.PI * 2)) * r;
    const x = ((cx + (Math.cos(t) * sr) | 0) % EGG_SIZE + EGG_SIZE) % EGG_SIZE;
    const y = (cy + Math.sin(t) * sr) | 0;
    if (y >= 0 && y < EGG_SIZE)
      setPixel(d, x, y, lerpColor(getPixel(d, x, y), col, alpha));
  }
}

function drawDiamond(d, cx, cy, r, col1, col2, alpha) {
  for (let dy = -r; dy <= r; dy++) for (let dx = -r; dx <= r; dx++) {
    const x = ((cx + dx) % EGG_SIZE + EGG_SIZE) % EGG_SIZE, y = cy + dy;
    if (y < 0 || y >= EGG_SIZE) continue;
    if (Math.abs(dx) + Math.abs(dy) <= r) {
      const t = (Math.abs(dx) + Math.abs(dy)) / r;
      setPixel(d, x, y, lerpColor(getPixel(d, x, y), lerpColor(col1, col2, t), alpha));
    }
  }
}

function drawRuneTriple(d, cx, cy, r, col, alpha) {
  for (let i = 0; i < 3; i++) {
    const angle = i * Math.PI * 2 / 3;
    drawLineWrapped(d, cx, cy, cx + (Math.cos(angle) * r) | 0, cy + (Math.sin(angle) * r) | 0, col, alpha);
    drawLineWrapped(d, cx - (Math.cos(angle) * r / 3) | 0, cy - (Math.sin(angle) * r / 3) | 0,
      cx + (Math.cos(angle + Math.PI / 2) * r / 2) | 0, cy + (Math.sin(angle + Math.PI / 2) * r / 2) | 0,
      col, alpha * 0.7);
  }
  drawEllipseWrapped(d, cx, cy, 2, 2, col, alpha);
}

function drawArrow(d, cx, cy, r, col, alpha) {
  drawLineWrapped(d, cx, cy - r, cx, cy + (r / 2) | 0, col, alpha);
  drawLineWrapped(d, cx, cy - r, cx - (r / 2) | 0, cy - (r / 3) | 0, col, alpha);
  drawLineWrapped(d, cx, cy - r, cx + (r / 2) | 0, cy - (r / 3) | 0, col, alpha);
}

function drawCrown(d, cx, cy, r, col1, col2, alpha) {
  const by = cy + (r / 2) | 0;
  if (by >= 0 && by < EGG_SIZE)
    for (let dx = -r; dx <= r; dx++) {
      const wx = ((cx + dx) % EGG_SIZE + EGG_SIZE) % EGG_SIZE;
      setPixel(d, wx, by, lerpColor(getPixel(d, wx, by), col1, alpha));
    }
  const spikeX = [cx - r, cx - (r / 2) | 0, cx, cx + (r / 2) | 0, cx + r];
  const spikeH = [(r / 2) | 0, r, (r * 3 / 2) | 0, r, (r / 2) | 0];
  for (let i = 0; i < 5; i++) {
    drawLineWrapped(d, spikeX[i], by, spikeX[i], by - spikeH[i], col1, alpha);
    drawEllipseWrapped(d, spikeX[i], by - spikeH[i], 2, 2, col2, alpha);
  }
  for (let dx = -r; dx <= r; dx++) for (let y = by; y <= by + (r / 3) | 0; y++) {
    if (y < 0 || y >= EGG_SIZE) continue;
    const wx = ((cx + dx) % EGG_SIZE + EGG_SIZE) % EGG_SIZE;
    setPixel(d, wx, y, lerpColor(getPixel(d, wx, y), col1, alpha * 0.7));
  }
}

function drawInfinity(d, cx, cy, r, col, alpha) {
  const off = (r / 2) | 0;
  for (let t = 0; t < Math.PI * 2; t += 0.04) {
    const x1 = ((cx - off + (Math.cos(t) * off) | 0) % EGG_SIZE + EGG_SIZE) % EGG_SIZE;
    const y1 = (cy + Math.sin(t) * off / 2) | 0;
    const x2 = ((cx + off + (Math.cos(t + Math.PI) * off) | 0) % EGG_SIZE + EGG_SIZE) % EGG_SIZE;
    const y2 = (cy + Math.sin(t + Math.PI) * off / 2) | 0;
    if (y1 >= 0 && y1 < EGG_SIZE)
      setPixel(d, x1, y1, lerpColor(getPixel(d, x1, y1), col, alpha));
    if (y2 >= 0 && y2 < EGG_SIZE)
      setPixel(d, x2, y2, lerpColor(getPixel(d, x2, y2), col, alpha));
  }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  PATTERNS (18 types)
// ═══════════════════════════════════════════════════════════════════════════════

function applyPatternType(d, type, dna, rng, primary, secondary, highlight, shadow, accent, alpha) {
  const sc = dna.patternScale || 1;
  const S = EGG_SIZE;
  const seed = dna.seed | 0;

  switch (type) {
    case 'SPOTS': {
      const count = 8 + rng.nextInt(14);
      for (let i = 0; i < count; i++) {
        const col = rng.nextInt(3) === 0 ? highlight : (rng.nextBoolean() ? secondary : accent);
        drawEllipseWrapped(d, rng.nextInt(S), rng.nextInt(S),
          (2 + rng.nextInt(5)) * sc, ((2 + rng.nextInt(5)) * sc * (0.5 + rng.nextFloat() * 0.9)) | 0,
          col, alpha * (0.4 + rng.nextFloat() * 0.4));
      }
      break;
    }
    case 'STRIPES': {
      const freq = 2 + rng.nextInt(3);
      const slope = (0.3 + rng.nextFloat() * 0.6) * (rng.nextBoolean() ? 1 : -1);
      const sw = (2 + rng.nextInt(3)) * sc;
      for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
        const v = Math.sin(x / S * Math.PI * 2 * freq + y * slope) * 0.5 + 0.5;
        const band = (v * 3) | 0;
        const col = band === 0 ? secondary : band === 1 ? accent : primary;
        setPixel(d, x, y, lerpColor(getPixel(d, x, y), col, alpha * 0.55));
      }
      break;
    }
    case 'MARBLE': {
      for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
        const n = seamlessTurbulence(x, y, 0.08 / sc, 0.08 / sc, (seed + 1) | 0, 5);
        const m = (Math.sin(x / S * Math.PI * 2 + n * 4.8) + 1) * 0.5;
        const m2 = (Math.sin((y + n * 25) * 0.18) + 1) * 0.5;
        const col = m > 0.6 ? lerpColor(secondary, highlight, m2) : lerpColor(primary, accent, m);
        setPixel(d, x, y, lerpColor(getPixel(d, x, y), col, alpha * 0.65));
      }
      break;
    }
    case 'GEOMETRIC': {
      const cells = (3 + rng.nextInt(4)) * sc;
      for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
        const cx2 = ((x * cells / S) | 0) % cells, cy2 = (y * cells / S) | 0;
        let col = ((cx2 + cy2) % 3 === 0) ? secondary : ((cx2 + cy2) % 3 === 1) ? accent : primary;
        const hv = seamlessValueNoise(x, y, 0.12 / sc, 0.10 / sc, (seed + 55) | 0);
        if (hv > 0.85) col = highlight;
        setPixel(d, x, y, lerpColor(getPixel(d, x, y), col, alpha * 0.55));
      }
      break;
    }
    case 'VEINS': {
      for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
        const v = seamlessVeinNoise(x, y, 0.10 / sc, 0.10 / sc, seed);
        if (v > 0.65) {
          const s2 = (v - 0.65) / 0.35;
          setPixel(d, x, y, lerpColor(getPixel(d, x, y), s2 > 0.7 ? highlight : secondary, alpha * s2 * 0.85));
        }
      }
      break;
    }
    case 'CONSTELLATION': {
      for (let y = 0; y < S; y++) for (let x = 0; x < S; x++)
        setPixel(d, x, y, lerpColor(getPixel(d, x, y), shadow, alpha * 0.7));
      const stars = 12 + rng.nextInt(10);
      const pts = [];
      for (let i = 0; i < stars; i++) {
        pts.push([rng.nextInt(S), rng.nextInt(S)]);
        drawEllipseWrapped(d, pts[i][0], pts[i][1], 1, 1, highlight, alpha * 0.9);
      }
      for (let i = 0; i < stars - 1; i += 2)
        drawLineWrapped(d, pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1], secondary, alpha * 0.3);
      break;
    }
    case 'CRYSTALS': {
      for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
        const scl = 1 / (4 * sc);
        const n = seamlessValueNoise(x, y, scl, scl, seed);
        const n2 = seamlessValueNoise(x, y, scl, scl, (seed + 13) | 0);
        const col = n > 0.5 ? (n2 > 0.5 ? highlight : secondary) : (n2 > 0.5 ? accent : primary);
        setPixel(d, x, y, lerpColor(getPixel(d, x, y), col, alpha * 0.6));
      }
      break;
    }
    case 'BUBBLES': {
      for (let i = 0; i < 15 + rng.nextInt(10); i++) {
        const bx = rng.nextInt(S), by = rng.nextInt(S), br = ((3 + rng.nextInt(6)) * sc / 2) | 0;
        for (let dy = -br; dy <= br; dy++) for (let dx = -br; dx <= br; dx++) {
          const ny = by + dy;
          if (ny < 0 || ny >= S) continue;
          const wx = ((bx + dx) % S + S) % S;
          const dd = Math.hypot(dx, dy);
          if (dd <= br) {
            const rim = Math.abs(dd - br * 0.85) / (br * 0.15 + 1);
            const col = dd < br * 0.6 ? lerpColor(primary, secondary, 0.3) : rim < 0.4 ? highlight : secondary;
            setPixel(d, wx, ny, lerpColor(getPixel(d, wx, ny), col, alpha * (1 - dd / br) * 0.7));
          }
        }
      }
      break;
    }
    case 'WAVES': {
      const waveCycles = Math.max(1, Math.round(S * 0.28 / sc / (Math.PI * 2)));
      for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
        const n = seamlessValueNoise(x, y, 0.04, 0.04, seed);
        const w1 = (Math.sin(x / S * Math.PI * 2 * waveCycles + n * 6) + 1) * 0.5;
        const w2 = (Math.cos(y * 0.22 / sc + n * 5) + 1) * 0.5;
        const c2 = (w1 + w2) * 0.5;
        const col = c2 > 0.75 ? highlight : c2 > 0.5 ? secondary : c2 > 0.25 ? accent : primary;
        setPixel(d, x, y, lerpColor(getPixel(d, x, y), col, alpha * 0.6));
      }
      break;
    }
    case 'MOSAIC': {
      const ts = Math.max(2, (4 * sc / 2) | 0);
      for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
        const tileX = (((x / ts) | 0) % Math.ceil(S / ts) + Math.ceil(S / ts)) % Math.ceil(S / ts);
        const tileY = (y / ts) | 0;
        const tr = new SeededRandom((seed + tileX * 1000 + tileY) | 0);
        const tv = tr.nextFloat();
        let col = tv > 0.7 ? secondary : tv > 0.45 ? accent : tv > 0.2 ? primary : highlight;
        const border = x % ts === 0 || y % ts === 0;
        setPixel(d, x, y, lerpColor(getPixel(d, x, y), border ? lerpColor(col, shadow, 0.6) : col, alpha * 0.6));
      }
      break;
    }
    case 'SCALES': {
      const scaleSize = 6 * sc;
      for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
        const row = Math.floor(y / scaleSize);
        const ox = (row % 2 === 0) ? 0 : scaleSize * 0.5;
        const lx = (((x + ox) % scaleSize) + scaleSize) % scaleSize - scaleSize * 0.5;
        const ly = (y % scaleSize) - scaleSize * 0.5;
        const dist = Math.sqrt(lx * lx * 0.6 + ly * ly);
        const edge = dist / (scaleSize * 0.55);
        let col = edge < 0.6 ? secondary : edge < 0.85 ? accent : shadow;
        if (edge > 0.92) col = highlight;
        setPixel(d, x, y, lerpColor(getPixel(d, x, y), col, alpha * 0.7));
      }
      break;
    }
    case 'CRACKS': {
      for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
        const v = seamlessVeinNoise(x, y, 0.18 / sc, 0.18 / sc, (seed + 333) | 0);
        const v2 = seamlessVeinNoise(x, y, 0.28 / sc, 0.28 / sc, (seed + 444) | 0);
        const thin = Math.min(v, v2);
        if (thin < 0.10) {
          const depth = thin / 0.10;
          const crackCol = depth < 0.5 ? lerpColor(shadow, secondary, depth * 0.6) : lerpColor(shadow, accent, (depth - 0.5) * 0.8);
          setPixel(d, x, y, lerpColor(getPixel(d, x, y), crackCol, alpha * (1 - depth) * 0.85));
        }
      }
      break;
    }
    case 'GALAXY': {
      for (let y = 0; y < S; y++) for (let x = 0; x < S; x++)
        setPixel(d, x, y, lerpColor(getPixel(d, x, y), shadow, alpha * 0.8));
      for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
        const n = seamlessTurbulence(x, y, 0.06, 0.06, seed, 4);
        if (n > 0.4) {
          const col = n > 0.7 ? highlight : n > 0.55 ? secondary : accent;
          setPixel(d, x, y, lerpColor(getPixel(d, x, y), col, alpha * (n - 0.4) * 1.2));
        }
      }
      // Nebula clouds using secondary
      for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
        const nb = seamlessValueNoise(x, y, 0.09, 0.09, (seed + 789) | 0);
        if (nb > 0.6 && nb < 0.75) setPixel(d, x, y, lerpColor(getPixel(d, x, y), secondary, alpha * (nb - 0.6) * 4));
      }
      for (let i = 0; i < 40 + rng.nextInt(30); i++) {
        const sx = rng.nextInt(S), sy = rng.nextInt(S);
        setPixel(d, sx, sy, lerpColor(getPixel(d, sx, sy), 0xFFFFFFFF, alpha * 0.9));
      }
      break;
    }
    case 'RUNES': {
      const count2 = 5 + rng.nextInt(8);
      for (let i = 0; i < count2; i++) {
        const rx = rng.nextInt(S), ry = 4 + rng.nextInt(S - 8);
        const r2 = (2 + rng.nextInt(3)) * sc;
        const col = rng.nextInt(3) === 0 ? secondary : (rng.nextBoolean() ? highlight : accent);
        drawLineWrapped(d, rx - r2, ry, rx + r2, ry, col, alpha * 0.85);
        drawLineWrapped(d, rx, ry - r2, rx, ry + r2, col, alpha * 0.85);
        if (rng.nextBoolean()) drawLineWrapped(d, rx - r2, ry - r2, rx + r2, ry + r2, col, alpha * 0.6);
        if (rng.nextBoolean()) drawLineWrapped(d, rx - r2, ry + r2, rx + r2, ry - r2, col, alpha * 0.6);
      }
      break;
    }
    case 'SPLATTER': {
      for (let i = 0; i < 20 + rng.nextInt(20); i++) {
        const sx = rng.nextInt(S), sy = rng.nextInt(S);
        const sr = (1 + rng.nextInt(4) * sc / 2) | 0;
        const col = rng.nextInt(4) === 0 ? highlight : rng.nextBoolean() ? secondary : accent;
        drawEllipseWrapped(d, sx, sy, sr, (sr * (0.4 + rng.nextFloat() * 0.8)) | 0, col, alpha * (0.5 + rng.nextFloat() * 0.4));
        drawLineWrapped(d, sx, sy, sx + rng.nextInt(11) - 5, sy + rng.nextInt(11) - 5, col, alpha * 0.35);
      }
      break;
    }
    case 'CIRCUITS': {
      const grid = 8 * sc;
      for (let y = 0; y < S; y += grid) for (let x = 0; x < S; x++)
        setPixel(d, x, Math.min(y, S - 1), lerpColor(getPixel(d, x, Math.min(y, S - 1)), secondary, alpha * 0.5));
      for (let x = 0; x < S; x += grid) for (let y = 0; y < S; y++)
        setPixel(d, x % S, y, lerpColor(getPixel(d, x % S, y), secondary, alpha * 0.5));
      for (let y = 0; y < S; y += grid) for (let x = 0; x < S; x += grid)
        if (rng.nextBoolean()) drawEllipseWrapped(d, x % S, Math.min(y, S - 1), 2, 2, highlight, alpha * 0.9);
      break;
    }
    case 'TIEDYE': {
      const cx3 = rng.nextInt(S / 2) + S / 4, cy3 = rng.nextInt(S / 2) + S / 4;
      const cols = [primary, secondary, highlight, accent, shadow];
      for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
        const rawDx = x - cx3;
        const dx3 = Math.abs(rawDx) < S / 2 ? rawDx : (rawDx > 0 ? rawDx - S : rawDx + S);
        const dy3 = y - cy3;
        const angle2 = Math.atan2(dy3, dx3);
        const dist2 = Math.hypot(dx3, dy3);
        const swirl = angle2 + dist2 * 0.1 / sc;
        const band2 = Math.abs((swirl / 0.4) | 0) % cols.length;
        setPixel(d, x, y, lerpColor(getPixel(d, x, y), cols[band2], alpha * 0.65));
      }
      break;
    }
    case 'GRADIENT': {
      const stops = [primary, secondary, accent, highlight, shadow];
      for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
        const t = (Math.sin(x / S * Math.PI * 2) * 0.5 + 0.5 + y / S) / 2;
        const scaled = t * (stops.length - 1);
        const i1 = Math.min(scaled | 0, stops.length - 2), i2 = i1 + 1;
        setPixel(d, x, y, lerpColor(getPixel(d, x, y), lerpColor(stops[i1], stops[i2], scaled - i1), alpha * 0.7));
      }
      break;
    }

    case 'HONEYCOMB': {
      const hexH = Math.max(3, (8 * sc) | 0);
      const hexW = (hexH * 1.732) | 0;
      for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
        const row = (y / hexH) | 0;
        const offX = (row % 2 === 0) ? 0 : hexW / 2;
        const wx = ((x + offX) % hexW + hexW) % hexW;
        const wy = y % hexH;
        const cx = wx - hexW / 2, cy = wy - hexH / 2;
        const edge = Math.max(Math.abs(cx) / (hexW / 2), Math.abs(cy) / (hexH / 2), Math.abs(cx / (hexW / 2) + cy / (hexH / 2)) * 0.67);
        if (edge > 0.75) {
          setPixel(d, x, y, lerpColor(getPixel(d, x, y), shadow, alpha * Math.min(1, (edge - 0.75) * 6)));
        } else {
          const cellIdx = (row + (((x + offX) / hexW) | 0)) % 3;
          const col = cellIdx === 0 ? highlight : cellIdx === 1 ? secondary : primary;
          setPixel(d, x, y, lerpColor(getPixel(d, x, y), col, alpha * 0.35));
        }
      }
      break;
    }

    case 'FEATHERS': {
      const arcH = Math.max(4, (10 * sc) | 0);
      for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
        const row = (y / arcH) | 0;
        const offX = (row % 2 === 0) ? 0 : S / 6;
        const period = S / 3;
        const wx = ((x + offX) % period + period) % period;
        const localY = y % arcH;
        const cx = wx / period - 0.5;
        const arc = 1 - 4 * cx * cx;
        const t = localY / arcH;
        if (t < arc * 0.9) {
          const barb = Math.sin(t * Math.PI * 4 + cx * 8) * 0.5 + 0.5;
          const col = barb > 0.5 ? highlight : secondary;
          setPixel(d, x, y, lerpColor(getPixel(d, x, y), col, alpha * 0.5 * arc));
        }
      }
      break;
    }

    case 'FLORA': {
      for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
        const n1 = seamlessTurbulence(x, y, 0.15 * sc, 0.15 * sc, seed, 3);
        const n2 = seamlessValueNoise(x, y, 0.3 * sc, 0.3 * sc, seed + 100);
        const leaf = n1 > 0.52 && n1 < 0.68;
        const vein = Math.abs(n2 - 0.5) < 0.04 && leaf;
        if (vein) setPixel(d, x, y, lerpColor(getPixel(d, x, y), shadow, alpha * 0.8));
        else if (leaf) setPixel(d, x, y, lerpColor(getPixel(d, x, y), primary, alpha * 0.6));
        else if (n1 > 0.45) setPixel(d, x, y, lerpColor(getPixel(d, x, y), secondary, alpha * 0.25));
      }
      break;
    }

    case 'ZIGZAG': {
      const freq = Math.max(2, (4 * sc) | 0);
      const bandH = Math.max(3, (6 * sc) | 0);
      for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
        const angle = (x / S) * Math.PI * 2;
        const wave = (Math.asin(Math.sin(angle * freq + y * 0.3)) / (Math.PI / 2) + 1) / 2;
        const band = ((y + wave * bandH) / bandH) | 0;
        const col = band % 2 === 0 ? primary : secondary;
        const edge = Math.abs(wave - 0.5) * 2;
        setPixel(d, x, y, lerpColor(getPixel(d, x, y), col, alpha * (0.4 + edge * 0.3)));
      }
      break;
    }

    case 'CROSSHATCH': {
      const spacing = Math.max(3, (6 * sc) | 0);
      for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
        const a1 = (x / S) * Math.PI * 2;
        const line1 = Math.abs(Math.sin(a1 * (S / spacing) + y * 0.5));
        const line2 = Math.abs(Math.sin(a1 * (S / spacing) - y * 0.5 + 1.0));
        if (line1 < 0.12) setPixel(d, x, y, lerpColor(getPixel(d, x, y), primary, alpha * 0.7));
        else if (line2 < 0.12) setPixel(d, x, y, lerpColor(getPixel(d, x, y), secondary, alpha * 0.6));
      }
      break;
    }

    case 'SMOKE': {
      for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
        const n1 = seamlessTurbulence(x, y, 0.08 * sc, 0.08 * sc, seed, 4);
        const n2 = seamlessTurbulence(x, y, 0.16 * sc, 0.16 * sc, seed + 77, 3);
        const blend = n1 * 0.6 + n2 * 0.4;
        const col = blend > 0.55 ? highlight : (blend > 0.4 ? primary : secondary);
        setPixel(d, x, y, lerpColor(getPixel(d, x, y), col, alpha * blend * 0.8));
      }
      break;
    }

    case 'TRIBAL': {
      for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
        const n = seamlessValueNoise(x, y, 0.2 * sc, 0.2 * sc, seed);
        const angle = (x / S) * Math.PI * 2;
        const band = Math.sin(angle * 3 + n * 4) * Math.sin(y / S * Math.PI * 2 + n * 3);
        if (Math.abs(band) < 0.15) {
          setPixel(d, x, y, lerpColor(getPixel(d, x, y), primary, alpha * 0.8));
        } else if (Math.abs(band) < 0.25) {
          setPixel(d, x, y, lerpColor(getPixel(d, x, y), accent, alpha * 0.4));
        }
      }
      break;
    }

    case 'FRACTAL': {
      for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
        let val = 0, amp = 1, freq = 0.15 * sc;
        for (let o = 0; o < 5; o++) {
          val += seamlessValueNoise(x, y, freq, freq, seed + o * 31) * amp;
          amp *= 0.5; freq *= 2;
        }
        val /= 1.9375;
        const sharp = val > 0.55 ? 1 : (val > 0.45 ? (val - 0.45) * 10 : 0);
        const col = sharp > 0.5 ? highlight : primary;
        setPixel(d, x, y, lerpColor(getPixel(d, x, y), col, alpha * (0.3 + sharp * 0.5)));
      }
      break;
    }
  }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  MICRO-TEXTURE (7 types)
// ═══════════════════════════════════════════════════════════════════════════════

function applyMicroTexture(d, dna) {
  if (!dna.microTexture || dna.microTexture === 'NONE') return;
  const ms = (dna.seed + 424242) | 0;
  const S = EGG_SIZE;
  for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
    const cur = getPixel(d, x, y);
    let factor = 0;
    switch (dna.microTexture) {
      case 'GRANULAR': factor = seamlessValueNoise(x, y, 0.8, 0.8, ms) * 0.18 - 0.09; break;
      case 'METALLIC': { const n = seamlessValueNoise(x, y, 0.4, 0.4, ms); const a = (x / S) * Math.PI * 2; factor = Math.sin(n * 20 + Math.sin(a) * S * 0.3 / (Math.PI * 2)) * 0.12; break; }
      case 'ICY':      { const n = seamlessTurbulence(x, y, 0.5, 0.5, ms, 2); factor = n > 0.6 ? (n - 0.6) * 0.4 : -0.05; break; }
      case 'SILK':     { const n = seamlessValueNoise(x, y, 0.1, 0.1, ms); const a = (x / S) * Math.PI * 2; factor = (Math.sin(Math.sin(a) * S * 0.25 / (Math.PI * 2) + n * 8) + Math.sin(y * 0.25) * 0.5) * 0.07; break; }
      case 'STONE':    factor = (seamlessValueNoise(x, y, 1.2, 1.2, ms) - 0.5) * 0.22; break;
      case 'SAND':     { const n = seamlessValueNoise(x, y, 1.5, 1.5, ms); factor = n > 0.7 ? 0.12 : (n < 0.3 ? -0.08 : 0); break; }
      case 'CARBON':   { const dark = (((x >> 1) + (y >> 1)) % 2 === 0); const n = seamlessValueNoise(x, y, 0.9, 0.9, ms); factor = (dark ? -0.08 : 0.04) + n * 0.06; break; }
    }
    if (factor !== 0) {
      const r = clamp(((cur >> 16) & 0xFF) + (factor * 255) | 0, 0, 255);
      const g = clamp(((cur >> 8) & 0xFF) + (factor * 255) | 0, 0, 255);
      const b = clamp((cur & 0xFF) + (factor * 255) | 0, 0, 255);
      setPixel(d, x, y, 0xFF000000 | (r << 16) | (g << 8) | b);
    }
  }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  TEXTURE GENERATION (13-step pipeline)
// ═══════════════════════════════════════════════════════════════════════════════

function generateEggTexture(dna) {
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = EGG_SIZE;
  const ctx = canvas.getContext('2d');
  const imgData = ctx.createImageData(EGG_SIZE, EGG_SIZE);
  const d = imgData.data;
  const S = EGG_SIZE;

  const palColors = dna.customPalette ? parseCustomPalette(dna.customPalette)
    : (PALETTES[dna.palette] || PALETTES.WARM);
  const [primary, secondary, highlight, shadow, accent, glow] = palColors;
  const seed = dna.seed | 0;
  const rng = new SeededRandom(seed);

  // Step 1: Rarity background (seamless noise)
  for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
    const n = seamlessValueNoise(x, y, 0.1, 0.1, (seed + 55555) | 0);
    setPixel(d, x, y, lerpColor(primary, shadow, n * 0.35));
  }

  // Step 2: Base gradient (vertical only — avoids radial seam at x edges)
  for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
    const dy = (y - S * 0.5) / (S * 0.5);
    const dist = Math.min(Math.abs(dy), 1);
    const noise = seamlessValueNoise(x, y, 0.15, 0.15, seed) * 0.3;
    const base = lerpColor(primary, shadow, Math.min(1, dist + noise) * 0.65);
    setPixel(d, x, y, lerpColor(getPixel(d, x, y), base, 0.7));
  }

  // Step 3: Primary pattern
  applyPatternType(d, dna.pattern, dna, rng, primary, secondary, highlight, shadow, accent, 1.0);

  // Step 4: Secondary pattern
  if (dna.patternBlend > 0) {
    const blendAlpha = dna.patternBlend / 100 * 0.85;
    const rng2 = new SeededRandom((seed + 77777) | 0);
    applyPatternType(d, dna.secondaryPattern, dna, rng2, accent, highlight, glow, shadow, primary, blendAlpha);
  }

  // Step 5: Emboss (FLAG_EMBOSS) — x-wrapped neighbor reads
  if (dna.visualFlags & FLAG_EMBOSS) {
    const snap = new Int32Array(S * S);
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) snap[y * S + x] = getPixel(d, x, y);
    for (let y = 1; y < S - 1; y++) for (let x = 0; x < S; x++) {
      const xR = (x + 1) % S, xL = (x - 1 + S) % S;
      const cR = snap[y * S + xR], cL = snap[y * S + xL];
      const cD = snap[(y + 1) * S + x], cU = snap[(y - 1) * S + x];
      const ddx = colorBrightness(cR) - colorBrightness(cL);
      const ddy = colorBrightness(cD) - colorBrightness(cU);
      const emboss = (ddx - ddy) * 0.5;
      if (Math.abs(emboss) > 0.04) {
        const base = getPixel(d, x, y);
        const result = emboss > 0
          ? lerpColor(base, 0xFFFFFFFF, Math.max(0, emboss) * 0.55)
          : lerpColor(base, shadow, Math.max(0, -emboss) * 0.65);
        setPixel(d, x, y, result);
      }
    }
  }

  // Step 6: Shimmer & sparkle effects — seamless noise + vertical edges
  for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
    const n = seamlessValueNoise(x, y, 0.25, 0.25, (seed + 9999) | 0);
    if (n > 0.84) setPixel(d, x, y, lerpColor(getPixel(d, x, y), highlight, (n - 0.84) / 0.16 * 0.4));
  }
  for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
    const dy = (y - S * 0.5) / (S * 0.5);
    const dist = Math.abs(dy);
    if (dist > 0.78 && dist < 1.0) {
      const rim = (dist - 0.78) / 0.22;
      const pulse = (Math.sin(x / S * Math.PI * 2 * 3 + y * 0.7 + seed) + 1) * 0.5;
      setPixel(d, x, y, lerpColor(getPixel(d, x, y), lerpColor(glow, highlight, pulse * 0.4), rim * 0.5));
    }
  }
  const sparkRng = new SeededRandom((seed + 0xABCDEF01) | 0);
  const sparkCount = 6 + sparkRng.nextInt(8);
  for (let i = 0; i < sparkCount; i++) {
    const sx = sparkRng.nextInt(S), sy = sparkRng.nextInt(S);
    setPixelWrapped(d, sx, sy, 0xFFFFFFFF);
    const sxL = (sx - 1 + S) % S, sxR = (sx + 1) % S;
    setPixelWrapped(d, sxL, sy, lerpColor(getPixel(d, sxL, sy), highlight, 0.5));
    setPixelWrapped(d, sxR, sy, lerpColor(getPixel(d, sxR, sy), highlight, 0.5));
    if (sy > 0) setPixel(d, sx, sy - 1, lerpColor(getPixel(d, sx, sy - 1), highlight, 0.5));
    if (sy < S - 1) setPixel(d, sx, sy + 1, lerpColor(getPixel(d, sx, sy + 1), highlight, 0.5));
  }

  // Step 7: Stamps
  applyStamp(d, dna, rng, highlight, accent, glow, secondary);

  // Step 8: Micro-texture
  applyMicroTexture(d, dna);

  // Step 9: Directional lighting — cylindrical normals (seamless on x)
  if (dna.visualFlags & FLAG_LIGHTING) {
    let Lx = -0.6, Ly = -0.7, Lz = 0.4;
    const Llen = Math.sqrt(Lx * Lx + Ly * Ly + Lz * Lz);
    Lx /= Llen; Ly /= Llen; Lz /= Llen;
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const angle = (x / S) * Math.PI * 2;
      const ny = (y - S * 0.5) / (S * 0.5);
      let nx = Math.cos(angle), nz = Math.sin(angle);
      const nLen = Math.sqrt(nx * nx + ny * ny * 0.25 + nz * nz);
      nx /= nLen; const nny = ny * 0.5 / nLen; nz /= nLen;
      const lambert = Math.max(0, -(nx * Lx + nny * Ly + nz * Lz));
      const light = 0.45 + lambert * 0.55;
      const cur = getPixel(d, x, y);
      const r = clamp(((cur >> 16) & 0xFF) * light, 0, 255) | 0;
      const g = clamp(((cur >> 8) & 0xFF) * light, 0, 255) | 0;
      const b = clamp((cur & 0xFF) * light, 0, 255) | 0;
      setPixel(d, x, y, 0xFF000000 | (r << 16) | (g << 8) | b);
    }
  }

  // Step 10: Specular — cylindrical distance for x wrap
  if (dna.visualFlags & FLAG_SPECULAR) {
    const hotX = S / 4, hotY = S / 4, sigma = S * 0.18;
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const rawDx = Math.abs(x - hotX);
      const dx = Math.min(rawDx, S - rawDx);
      const dy = y - hotY;
      const gauss = Math.exp(-(dx * dx + dy * dy) / (2 * sigma * sigma));
      if (gauss < 0.01) continue;
      const specCol = lerpColor(0xFFFFFFFF, highlight, 0.25);
      setPixel(d, x, y, lerpColor(getPixel(d, x, y), specCol, gauss * 0.65));
    }
  }

  // Step 11: Edge glow — top/bottom rims only (no horizontal edges on cylinder)
  if (dna.visualFlags & FLAG_RIM_GLOW) {
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const v = y / S;
      const rimV_top = Math.max(0, 1 - v / 0.18);
      const rimV_bot = Math.max(0, 1 - (1 - v) / 0.18);
      let rim = Math.max(rimV_top, rimV_bot);
      rim = rim * rim;
      if (rim > 0.01) {
        const glowBright = lerpColor(glow, 0xFFFFFFFF, rim * 0.35);
        setPixel(d, x, y, lerpColor(getPixel(d, x, y), glowBright, rim * 0.80));
      }
    }
  }

  // Step 12: Varnish — periodic on x (seamless cylinder wrap)
  if (dna.visualFlags & FLAG_VARNISH) {
    const strength = (dna.varnishLevel || 0) / 100 * 0.35;
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const band = Math.sin(x / S * Math.PI * 2) * 0.5 + 0.5 + (y / S) * 0.3;
      const sheen = Math.pow(Math.max(0, Math.sin(band * Math.PI * 1.5)), 3);
      if (sheen > 0.05)
        setPixel(d, x, y, lerpColor(getPixel(d, x, y), lerpColor(highlight, 0xFFFFFFFF, 0.5), sheen * strength));
    }
  }

  // Step 13: Highlight vignette — y-only gradient (seamless on x)
  for (let y = 0; y < S; y++) {
    const vy = 1 - y / S;
    const hl = Math.max(0, vy - 0.25) / 0.75;
    if (hl <= 0.04) continue;
    const hlAlpha = hl * hl * 0.18;
    for (let x = 0; x < S; x++) {
      setPixel(d, x, y, lerpColor(getPixel(d, x, y), highlight, hlAlpha));
    }
  }

  ctx.putImageData(imgData, 0, 0);

  // Generate emissive map if emissive type is set
  let emissiveCanvas = null;
  if (dna.emissive && dna.emissive !== 'NONE' && (dna.emissiveDensity || 0) > 0) {
    emissiveCanvas = generateEmissiveMap(dna, glow, seed);
  }

  return { texture: canvas, emissive: emissiveCanvas };
}

function generateEmissiveMap(dna, glowColor, seed) {
  const S = EGG_SIZE;
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = S;
  const ctx = canvas.getContext('2d');
  const imgData = ctx.createImageData(S, S);
  const d = imgData.data;
  const density = (dna.emissiveDensity || 0) / 100;
  const es = (seed + 787878) | 0;
  const r = ((glowColor >> 16) & 0xFF), g = ((glowColor >> 8) & 0xFF), b = (glowColor & 0xFF);

  // Helper: cell-based point check with horizontal wrapping
  function _emCellDot(x, y, cellSz, seedOff, threshold, maxRad, S, density, es) {
    let maxI = 0;
    for (let oy = -1; oy <= 1; oy++) for (let ox = -1; ox <= 1; ox++) {
      const cx = Math.floor(x / cellSz) + ox;
      const cy = Math.floor(y / cellSz) + oy;
      const wcx = ((cx % 20) + 20) % 20;
      const rng = new SeededRandom(es + wcx * seedOff + cy * 131);
      if (rng.nextFloat() > threshold) continue;
      const dotX = (cx + 0.25 + rng.nextFloat() * 0.5) * cellSz;
      const dotY = (cy + 0.25 + rng.nextFloat() * 0.5) * cellSz;
      const rad = maxRad * (0.5 + rng.nextFloat() * 0.5);
      let dx = x - dotX;
      if (dx > S / 2) dx -= S; else if (dx < -S / 2) dx += S;
      const dy = y - dotY;
      const dist = Math.sqrt(dx * dx + dy * dy);
      if (dist < rad) maxI = Math.max(maxI, (1 - dist / rad) * density);
    }
    return maxI;
  }

  for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
    let intensity = 0;
    switch (dna.emissive) {
      case 'SPOTS': {
        // Cell-based round dots scattered across the texture
        intensity = _emCellDot(x, y, 5, 37, density * 0.6, 2.2, S, density, es);
        break;
      }
      case 'LINES': {
        const angle = (x / S) * Math.PI * 2;
        const wave = Math.sin(angle * 4 + seamlessValueNoise(x, y, 0.15, 0.15, es) * 3);
        intensity = Math.abs(wave) < 0.1 + density * 0.15 ? (1 - Math.abs(wave) / (0.1 + density * 0.15)) * density : 0;
        break;
      }
      case 'RUNES': {
        // Grid-based geometric rune symbols per cell
        const cellSz = 10;
        const cxC = Math.floor(x / cellSz);
        const cyC = Math.floor(y / cellSz);
        const lx = ((x % cellSz) + cellSz) % cellSz / cellSz;
        const ly = (y % cellSz) / cellSz;
        const rng = new SeededRandom(es + ((cxC % 8 + 8) % 8) * 47 + cyC * 83);
        if (rng.nextFloat() < density * 0.5) {
          const pat = rng.nextInt(7);
          const lw = 0.13;
          let on = false;
          switch (pat) {
            case 0: on = Math.abs(lx - 0.5) < lw; break;
            case 1: on = Math.abs(ly - 0.5) < lw; break;
            case 2: on = Math.abs(lx - 0.5) < lw || Math.abs(ly - 0.5) < lw; break;
            case 3: on = Math.abs(lx - ly) < lw * 1.41; break;
            case 4: on = Math.abs(lx + ly - 1) < lw * 1.41; break;
            case 5: on = Math.abs(lx - ly) < lw * 1.41 || Math.abs(lx + ly - 1) < lw * 1.41; break;
            case 6: { const cdist = Math.sqrt((lx-0.5)*(lx-0.5) + (ly-0.5)*(ly-0.5)); on = Math.abs(cdist - 0.35) < lw; break; }
          }
          const margin = Math.min(lx, 1 - lx, ly, 1 - ly);
          intensity = on ? density * Math.min(1, margin / 0.08) : 0;
        }
        break;
      }
      case 'STARS': {
        // Bright individual star points with soft halo
        const cellSz = 8;
        let maxI = 0;
        for (let oy = -1; oy <= 1; oy++) for (let ox = -1; ox <= 1; ox++) {
          const cx = Math.floor(x / cellSz) + ox;
          const cy = Math.floor(y / cellSz) + oy;
          const rng = new SeededRandom(es + ((cx % 12 + 12) % 12) * 53 + cy * 97);
          if (rng.nextFloat() > density * 0.4) continue;
          const sx = (cx + 0.2 + rng.nextFloat() * 0.6) * cellSz;
          const sy = (cy + 0.2 + rng.nextFloat() * 0.6) * cellSz;
          let dx = x - sx;
          if (dx > S / 2) dx -= S; else if (dx < -S / 2) dx += S;
          const dy = y - sy;
          const dist = Math.sqrt(dx * dx + dy * dy);
          if (dist < 0.7) maxI = Math.max(maxI, density);
          else if (dist < 2.8) maxI = Math.max(maxI, density * Math.pow(1 - (dist - 0.7) / 2.1, 2));
        }
        intensity = maxI;
        break;
      }
      case 'AURORA': {
        const n = seamlessTurbulence(x, y, 0.1, 0.1, es, 3);
        const wave = Math.sin(y / S * Math.PI * 3 + n * 4);
        intensity = Math.max(0, wave * 0.5 + 0.5) * density * n;
        break;
      }
      case 'VEINS': {
        // Thicker branching veins with 4 octaves
        const n = seamlessTurbulence(x, y, 0.15, 0.15, es, 4);
        const n2 = seamlessValueNoise(x, y, 0.3, 0.3, es + 55);
        const thickness = 0.06 + n2 * 0.03;
        const diff = Math.abs(n - 0.5);
        intensity = diff < thickness ? density * Math.pow(1 - diff / thickness, 0.6) : 0;
        break;
      }
      case 'SPARKS': {
        // Sparse bright sparkle points
        const cellSz = 10;
        let maxI = 0;
        for (let oy = -1; oy <= 1; oy++) for (let ox = -1; ox <= 1; ox++) {
          const cx = Math.floor(x / cellSz) + ox;
          const cy = Math.floor(y / cellSz) + oy;
          const rng = new SeededRandom(es + ((cx % 10 + 10) % 10) * 71 + cy * 199);
          if (rng.nextFloat() > density * 0.25) continue;
          const spX = (cx + 0.1 + rng.nextFloat() * 0.8) * cellSz;
          const spY = (cy + 0.1 + rng.nextFloat() * 0.8) * cellSz;
          let dx = x - spX;
          if (dx > S / 2) dx -= S; else if (dx < -S / 2) dx += S;
          const dy = y - spY;
          const dist = Math.sqrt(dx * dx + dy * dy);
          if (dist < 0.5) maxI = Math.max(maxI, density);
          else if (dist < 2.2) maxI = Math.max(maxI, density * 0.6 * (1 - (dist - 0.5) / 1.7));
        }
        intensity = maxI;
        break;
      }
      case 'CRACKS': {
        const n1 = seamlessTurbulence(x, y, 0.15, 0.15, es, 2);
        const n2 = seamlessValueNoise(x, y, 0.4, 0.4, es + 99);
        const crack = Math.abs(n1 - n2) < 0.04;
        intensity = crack ? density * (1 - Math.abs(n1 - n2) / 0.04) : 0;
        break;
      }
      case 'PULSE': {
        const n = seamlessValueNoise(x, y, 0.08, 0.08, es);
        const ring = Math.sin(n * Math.PI * 8);
        intensity = Math.abs(ring) > 0.85 ? density * (Math.abs(ring) - 0.85) / 0.15 : 0;
        break;
      }
      case 'CIRCUIT': {
        const cellSize = 8;
        const cx = ((x % cellSize) + cellSize) % cellSize;
        const cy = y % cellSize;
        const onGrid = cx === 0 || cy === 0;
        const n = seamlessValueNoise(x, y, 0.3, 0.3, es);
        intensity = (onGrid && n > 0.4) ? density * 0.8 : (n > 0.85 ? density : 0);
        break;
      }
      // ── New patterns ──
      case 'RIPPLE': {
        // Concentric rings from multiple random centers
        let maxI = 0;
        for (let ci = 0; ci < 4; ci++) {
          const rng = new SeededRandom(es + ci * 317);
          const rcx = rng.nextFloat() * S;
          const rcy = 0.15 * S + rng.nextFloat() * 0.7 * S;
          let dx = x - rcx;
          if (dx > S / 2) dx -= S; else if (dx < -S / 2) dx += S;
          const dy = y - rcy;
          const dist = Math.sqrt(dx * dx + dy * dy);
          const ring = Math.sin(dist * 0.7);
          if (Math.abs(ring) > 0.8) maxI = Math.max(maxI, density * (Math.abs(ring) - 0.8) / 0.2 * Math.max(0, 1 - dist / (S * 0.6)));
        }
        intensity = maxI;
        break;
      }
      case 'FLAME': {
        // Upward fire tendrils using noise
        const n1 = seamlessTurbulence(x, y, 0.12, 0.08, es, 3);
        const n2 = seamlessValueNoise(x, y, 0.2, 0.06, es + 33);
        const rise = 1 - y / S; // brighter at bottom (y=0), fading up
        const streak = Math.abs(n1 - 0.5) < 0.05 + n2 * 0.04;
        intensity = streak ? density * rise * (1 - Math.abs(n1 - 0.5) / (0.05 + n2 * 0.04)) : 0;
        break;
      }
      case 'NEBULA': {
        // Soft glowing cloud patches
        const n1 = seamlessTurbulence(x, y, 0.08, 0.08, es, 4);
        const n2 = seamlessValueNoise(x, y, 0.12, 0.12, es + 77);
        const combined = n1 * 0.6 + n2 * 0.4;
        intensity = combined > 0.45 ? density * Math.pow((combined - 0.45) / 0.55, 0.8) : 0;
        break;
      }
      case 'MATRIX': {
        // Digital rain: vertical columns with bright segments
        const colW = 4;
        const col = Math.floor(x / colW);
        const rng = new SeededRandom(es + ((col % 20 + 20) % 20) * 59);
        if (rng.nextFloat() < density * 0.5) {
          const speed = 0.3 + rng.nextFloat() * 0.4;
          const offset = rng.nextFloat() * S;
          const t = ((y + offset) * speed) % S;
          const bright = t < S * 0.25;
          const cx = ((x % colW) + colW) % colW;
          const centered = cx >= 1 && cx < colW - 1;
          intensity = (bright && centered) ? density * (1 - t / (S * 0.25)) : 0;
        }
        break;
      }
      case 'DIAMONDS': {
        // Diamond/rhombus grid pattern
        const sz = 7;
        const lx = ((x % sz) + sz) % sz / sz - 0.5;
        const ly = (y % sz) / sz - 0.5;
        const diamond = Math.abs(lx) + Math.abs(ly);
        const onEdge = Math.abs(diamond - 0.4) < 0.06;
        const n = seamlessValueNoise(x, y, 0.3, 0.3, es);
        intensity = onEdge && n > (0.5 - density * 0.3) ? density * (1 - Math.abs(diamond - 0.4) / 0.06) : 0;
        break;
      }
    }
    intensity = Math.min(1, Math.max(0, intensity));
    if (intensity > 0) {
      const idx = (y * S + x) * 4;
      d[idx] = (r * intensity) | 0;
      d[idx + 1] = (g * intensity) | 0;
      d[idx + 2] = (b * intensity) | 0;
      d[idx + 3] = 255;
    }
  }

  ctx.putImageData(imgData, 0, 0);
  return canvas;
}

function parseCustomPalette(str) {
  if (!str) return null;
  const parts = str.split(',').map(s => parseInt(s.trim(), 16));
  if (parts.length < 6 || parts.some(isNaN)) return null;
  return parts.map(c => 0xFF000000 | c);
}

// ═══════════════════════════════════════════════════════════════════════════════
//  THREE.JS EGG PREVIEW
// ═══════════════════════════════════════════════════════════════════════════════

class EggPreview {
  constructor(containerId) {
    this.container = typeof containerId === 'string'
      ? document.getElementById(containerId) : containerId;
    if (!this.container || typeof THREE === 'undefined') return;

    this.scene = new THREE.Scene();
    this.scene.background = new THREE.Color(0x0c0e14);

    const w = this.container.clientWidth || 400, h = this.container.clientHeight || 400;
    this.camera = new THREE.PerspectiveCamera(35, w / h, 0.5, 15);
    this.camera.position.set(2.0, 1.2, 2.0);

    this.renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
    this.renderer.setSize(w, h);
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    this.container.appendChild(this.renderer.domElement);

    // Orbit controls
    if (THREE.OrbitControls) {
      this.controls = new THREE.OrbitControls(this.camera, this.renderer.domElement);
      this.controls.target.set(0.5, 0.5, 0.5);
      this.controls.enableDamping = true;
      this.controls.dampingFactor = 0.08;
      this.controls.autoRotate = true;
      this.controls.autoRotateSpeed = 2;
      this.controls.update();
    }

    // Lights
    this.scene.add(new THREE.AmbientLight(0xffffff, 0.5));
    const dir = new THREE.DirectionalLight(0xffffff, 0.8);
    dir.position.set(-2, 3, 2);
    this.scene.add(dir);

    // Build geometry
    this.eggMesh = null;
    this._buildGeometry();

    // Animation
    this._animId = null;
    this._animate();
  }

  _buildGeometry() {
    const positions = [], normals = [], uvs = [], indices = [];
    let vtxIdx = 0;
    const S = 16; // voxel coordinate scale

    // Face normals: PX=0, MX=1, PY=2, MY=3, PZ=4, MZ=5
    const faceNormals = [[1,0,0],[-1,0,0],[0,1,0],[0,-1,0],[0,0,1],[0,0,-1]];
    const faceBits = [1, 2, 4, 8, 16, 32];

    // Face vertex offsets (relative to voxel min, for each face — 4 vertices per quad)
    // Each face: [v0, v1, v2, v3] as [dx,dy,dz] from min corner, using (sx,sy,sz) for max-min
    const faceVerts = [
      // PX: x=max
      (sx,sy,sz) => [[sx,0,0],[sx,sy,0],[sx,sy,sz],[sx,0,sz]],
      // MX: x=min
      (sx,sy,sz) => [[0,0,sz],[0,sy,sz],[0,sy,0],[0,0,0]],
      // PY: y=max (winding CCW when viewed from +Y)
      (sx,sy,sz) => [[0,sy,sz],[sx,sy,sz],[sx,sy,0],[0,sy,0]],
      // MY: y=min (winding CCW when viewed from -Y)
      (sx,sy,sz) => [[0,0,0],[sx,0,0],[sx,0,sz],[0,0,sz]],
      // PZ: z=max
      (sx,sy,sz) => [[sx,0,sz],[sx,sy,sz],[0,sy,sz],[0,0,sz]],
      // MZ: z=min
      (sx,sy,sz) => [[0,0,0],[0,sy,0],[sx,sy,0],[sx,0,0]],
    ];

    for (let vi = 0; vi < EGG_VOXELS.length; vi += 7) {
      const x1 = EGG_VOXELS[vi] / S, y1 = EGG_VOXELS[vi+1] / S, z1 = EGG_VOXELS[vi+2] / S;
      const x2 = EGG_VOXELS[vi+3] / S, y2 = EGG_VOXELS[vi+4] / S, z2 = EGG_VOXELS[vi+5] / S;
      const mask = EGG_VOXELS[vi+6];
      const sx = x2 - x1, sy = y2 - y1, sz = z2 - z1;

      for (let fi = 0; fi < 6; fi++) {
        if (!(mask & faceBits[fi])) continue;
        const verts = faceVerts[fi](sx, sy, sz);
        const n = faceNormals[fi];

        for (const [dx, dy, dz] of verts) {
          const px = x1 + dx, pz = z1 + dz;
          // Nudge PY/MY faces along normal to prevent Z-fighting between coplanar voxel faces
          let py = y1 + dy;
          if (fi === 2) py += 0.0001;       // PY: push outward
          else if (fi === 3) py -= 0.0001;  // MY: push outward
          positions.push(px, py, pz);
          normals.push(n[0], n[1], n[2]);
          // Cylindrical UV
          const u = (Math.atan2(pz - 0.5, px - 0.5) + Math.PI) / (Math.PI * 2);
          const v = 1 - py / EGG_Y_MAX;
          uvs.push(u, v);
        }

        // Two triangles per quad
        indices.push(vtxIdx, vtxIdx + 1, vtxIdx + 2, vtxIdx, vtxIdx + 2, vtxIdx + 3);
        vtxIdx += 4;
      }
    }

    // Fix UV seams: for each quad (4 vertices), check if UVs wrap around
    for (let qi = 0; qi < uvs.length / 2; qi += 4) {
      const u0 = uvs[qi * 2], u1 = uvs[(qi + 1) * 2], u2 = uvs[(qi + 2) * 2], u3 = uvs[(qi + 3) * 2];
      const uArr = [u0, u1, u2, u3];
      const uMax = Math.max(...uArr), uMin = Math.min(...uArr);
      if (uMax - uMin > 0.5) {
        for (let j = 0; j < 4; j++) {
          if (uvs[(qi + j) * 2] < 0.5) uvs[(qi + j) * 2] += 1.0;
        }
      }
    }

    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
    geo.setAttribute('normal', new THREE.Float32BufferAttribute(normals, 3));
    geo.setAttribute('uv', new THREE.Float32BufferAttribute(uvs, 2));
    geo.setIndex(indices);

    // Default material (will be updated by setDNA)
    const mat = new THREE.MeshStandardMaterial({
      color: 0x888888, roughness: 0.6, metalness: 0.1, side: THREE.FrontSide,
    });

    this.eggMesh = new THREE.Mesh(geo, mat);
    this.scene.add(this.eggMesh);
  }

  setDNA(dna) {
    if (!this.eggMesh || !dna) return;
    const result = generateEggTexture(dna);
    const texCanvas = result.texture;
    const tex = new THREE.CanvasTexture(texCanvas);
    tex.magFilter = THREE.NearestFilter;
    tex.minFilter = THREE.NearestFilter;
    tex.wrapS = THREE.RepeatWrapping;
    tex.wrapT = THREE.ClampToEdgeWrapping;
    tex.needsUpdate = true;
    if (this.eggMesh.material.map) this.eggMesh.material.map.dispose();
    this.eggMesh.material.map = tex;
    this.eggMesh.material.color.set(0xffffff);

    // Emissive map
    if (this.eggMesh.material.emissiveMap) {
      this.eggMesh.material.emissiveMap.dispose();
      this.eggMesh.material.emissiveMap = null;
    }
    if (result.emissive) {
      const eTex = new THREE.CanvasTexture(result.emissive);
      eTex.magFilter = THREE.NearestFilter;
      eTex.minFilter = THREE.NearestFilter;
      eTex.wrapS = THREE.RepeatWrapping;
      eTex.wrapT = THREE.ClampToEdgeWrapping;
      eTex.needsUpdate = true;
      this.eggMesh.material.emissiveMap = eTex;
      this.eggMesh.material.emissive = new THREE.Color(0xffffff);
      this.eggMesh.material.emissiveIntensity = 1.5;
    } else {
      this.eggMesh.material.emissive = new THREE.Color(0x000000);
      this.eggMesh.material.emissiveIntensity = 0;
    }

    this.eggMesh.material.needsUpdate = true;
  }

  _animate() {
    this._animId = requestAnimationFrame(() => this._animate());
    if (this.controls) this.controls.update();
    this.renderer.render(this.scene, this.camera);
  }

  resize() {
    if (!this.container) return;
    const w = this.container.clientWidth, h = this.container.clientHeight;
    if (w === 0 || h === 0) return;
    this.camera.aspect = w / h;
    this.camera.updateProjectionMatrix();
    this.renderer.setSize(w, h);
  }

  startAutoSpin() {
    if (this.controls) this.controls.autoRotate = true;
  }

  stopAutoSpin() {
    if (this.controls) this.controls.autoRotate = false;
  }

  resetCamera() {
    if (this.camera) this.camera.position.set(2.0, 1.2, 2.0);
    if (this.controls) { this.controls.target.set(0.5, 0.5, 0.5); this.controls.update(); }
  }

  dispose() {
    if (this._animId) cancelAnimationFrame(this._animId);
    if (this.eggMesh) {
      if (this.eggMesh.material.map) this.eggMesh.material.map.dispose();
      if (this.eggMesh.material.emissiveMap) this.eggMesh.material.emissiveMap.dispose();
      this.eggMesh.material.dispose();
      this.eggMesh.geometry.dispose();
    }
    if (this.renderer) {
      this.renderer.dispose();
      if (this.renderer.domElement && this.renderer.domElement.parentNode)
        this.renderer.domElement.parentNode.removeChild(this.renderer.domElement);
    }
    this.scene = null;
    this.camera = null;
    this.renderer = null;
    this.controls = null;
  }
}

// Global reference for app.js integration
window.EggPreview = EggPreview;
window.generateEggTexture = generateEggTexture;
