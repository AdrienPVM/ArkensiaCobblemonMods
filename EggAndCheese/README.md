# EggAndCheese — Easter Egg Hunt Mod for Minecraft 1.21.1 (Fabric)

## Overview
EggAndCheese is a server-side easter egg hunt system. Admins hide collectibles (eggs or cheese wedges) around the world with unique procedurally-generated textures. Players must find them, triggering FTB Quests task completions and receiving decorative reward items.

## Setup

### Requirements
- Minecraft 1.21.1
- Fabric Loader ≥ 0.16.0
- Fabric API

### Optional
- FTB Quests (for quest integration)
- JourneyMap (for automatic admin waypoints)
- LuckPerms (for permission node `eggandcheese.admin`)

---

## Admin Workflow

### 1. Open the Admin Dashboard
```
/eac
```

### 2. Create a New Collectible
Click **+ New Collectible** in the dashboard.

In the Creator screen:
1. Choose **Type**: Egg or Cheese
2. Cycle through **Palette** (10 options: Warm, Cool, Nature, Dark, Pastel, Fire, Ice, Mystic, Gold, Candy)
3. Cycle through **Pattern** (10 options: Spots, Stripes, Marble, Geometric, Veins, Constellation, Crystals, Bubbles, Waves, Mosaic)
4. Set **Pattern Scale** (1-4)
5. Choose **Emissive** type (None, Spots, Lines, Runes, Stars, Aurora, Veins, Sparks)
6. Set **Emissive Density** (0-100%)
7. Click **↻ Regenerate** to shuffle the random seed while keeping your parameters
8. See the **3D rotating preview** + **2D icon preview** live
9. Type a **unique ID** (e.g. `cave_nord`, `spawn_secret`)
10. Click **✓ Confirm**

You'll receive the admin placement item in your inventory.

### 3. Place the Collectible
Use the admin item (right-click to place) anywhere in the world. The block is **indestructible** for non-admins.

### 4. Adjust Scale
Open the detail page from the dashboard (click the entry). Use the **Scale slider** (0.1× to 4.0×) and click **Apply Scale**.

### 5. Manage Collectibles
From the detail page:
- **⊕ Teleport** — instant TP to the egg's location
- **Give Admin Item** — get another placement copy
- **Give Reward Item** — give yourself the decorative version
- **✗ Delete** — remove from registry + remove world block

---

## FTB Quests Integration

In your FTB Quest book, add a task with criteria type **Advancement**:

```
Advancement ID: eggandcheese:egg_found
```

For a task that requires finding a **specific egg**, use the advancement data tag system or name your advancement after the egg ID. You can also use the criterion trigger directly with FTBQuests' custom task support:

```json
{
  "trigger": "eggandcheese:egg_found",
  "conditions": {
    "egg_id": "your_egg_id_here"
  }
}
```

When a player right-clicks an admin egg, it triggers `eggandcheese:egg_found` with the egg's ID. FTB Quests detects this and completes the matching task, then rewards the player as configured in the quest book.

---

## Player Experience

1. Player finds the hidden egg/cheese in the world
2. **Right-click** the block
3. ✅ Receives a **unique decorative item** with the same procedural texture
4. FTB Quest task is **automatically completed**
5. (Player can only discover each egg **once**)

The decorative item can be:
- **Placed on the ground** as a decorative block
- **Placed in item frames** on walls
- **Displayed on a Display Pedestal** (given by admin via `/eac`)

---

## Emissive Options
| Type | Description |
|------|-------------|
| None | No emissive layer |
| Spots | Glowing dots scattered across the surface |
| Lines | Bright glowing line segments |
| Runes | Angular rune-like glyphs |
| Stars | Multi-pointed star bursts |
| Aurora | Flowing band of light |
| Veins | Glowing crack-like veins |
| Sparks | Small sparks with trailing light |

---

## Commands

| Command | Description |
|---------|-------------|
| `/eac` | Open the admin dashboard |
| `/eac give <id>` | Give yourself the admin placement item for an existing egg |
| `/eac reload` | Print registry count |

---

## Permissions

Node: `eggandcheese.admin` (fallback: OP level 2)

---

## Building

```bash
./gradlew build
```

Output: `build/libs/eggandcheese-1.0.0.jar`
