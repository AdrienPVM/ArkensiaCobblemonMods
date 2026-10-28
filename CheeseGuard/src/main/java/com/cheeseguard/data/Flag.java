package com.cheeseguard.data;

/**
 * All protection flags available for a region.
 * Default value is the behavior when NO region covers a location (always allowed).
 */
public enum Flag {

    BLOCK_BREAK      ("block-break",    "Casser des blocs",       "🪓",  true),
    BLOCK_PLACE      ("block-place",    "Placer des blocs",       "🧱",  true),
    INTERACT         ("interact",       "Interactions (coffres, portes, PNJ…)", "🚪", true),
    ENTITY_DAMAGE    ("entity-damage",  "Attaquer entités/mobs",  "⚔",   true),
    PVP              ("pvp",            "PvP (attaquer joueurs)", "🗡",   true),
    USE_ITEMS        ("use-items",      "Utiliser items (seau, briquet…)", "🪣", true),
    EXPLOSIONS       ("explosions",     "Explosions (TNT, creepers…)", "💣", true),
    FIRE_SPREAD      ("fire-spread",    "Propagation du feu",     "🔥",  false),
    MOB_SPAWNING     ("mob-spawning",   "Spawn naturel de mobs",  "👾",  false),
    MOB_GRIEF        ("mob-grief",      "Griefing de mobs (enderman, herbe…)", "🐛", false),
    TRAMPLE          ("trample",        "Piétiner les cultures",  "🌾",  false),
    ITEM_DROP        ("item-drop",      "Dropper des items",      "📦",  true),
    ITEM_PICKUP      ("item-pickup",    "Ramasser des items",     "🤏",  true),
    ENDER_PEARL      ("ender-pearl",    "Téléportation ender pearl", "🔮", true),
    FLY              ("fly",            "Vol (elytra, creative)", "🪽",  false),
    COBBLEMON_FLY    ("cobblemon-fly",  "Montures volantes Cobblemon", "🐉", false),
    COBBLEMON_MOUNT  ("cobblemon-mount","Montures terrestres Cobblemon", "🐎", false),
    COBBLEMON_SUMMON ("cobblemon-summon","Sortir des Pokémon",    "📤",  false),
    FALL_DAMAGE      ("fall-damage",    "Dégâts de chute",        "💀",  false),
    HUNGER           ("hunger",         "Perte de faim",          "🍖",  false),
    ENTRY            ("entry",          "Entrer dans la zone",    "🚶",  true),
    EXIT             ("exit",           "Quitter la zone",        "🏃",  true),
    LEAF_DECAY       ("leaf-decay",     "Dégradation des feuilles", "🍂", false),
    ICE_MELT         ("ice-melt",       "Fonte de la glace",      "🧊",  false),
    SNOW_FORM        ("snow-form",      "Formation de neige",     "❄",   false),
    VINE_GROW        ("vine-grow",      "Pousse des vignes/lianes", "🌿", false),
    REDSTONE         ("redstone",       "Mécanismes redstone",    "⚡",  false),
    NAMETAG_HIDE     ("nametag-hide",   "Masquer les nametags",   "👁",  false),
    MINIMAP_HIDE     ("minimap-hide",   "Masquer la minimap",     "🗺",  false),
    CORAL_DEATH      ("coral-death",    "Mort du corail hors de l'eau", "🪸", false),
    COBBLESHIFT_TRANSFORM ("cobbleshift-transform", "Transformation CobbleShift", "🦊", false);

    private final String id;
    private final String displayName;
    private final String icon;
    /**
     * If true, members/owners of a region are EXEMPT from this flag
     * (classic protection behaviour: owners can break their own stuff).
     * If false, the flag applies to EVERYONE including members
     * (behavioural/environmental restriction: e.g. no Pokémon summon applies to all).
     */
    private final boolean memberExempt;

    Flag(String id, String displayName, String icon, boolean memberExempt) {
        this.id = id;
        this.displayName = displayName;
        this.icon = icon;
        this.memberExempt = memberExempt;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getIcon() { return icon; }
    /** True if members/owners bypass this flag (protection flags). False for behavioural flags. */
    public boolean isMemberExempt() { return memberExempt; }

    public static Flag fromId(String id) {
        for (Flag f : values()) {
            if (f.id.equals(id)) return f;
        }
        return null;
    }
}
