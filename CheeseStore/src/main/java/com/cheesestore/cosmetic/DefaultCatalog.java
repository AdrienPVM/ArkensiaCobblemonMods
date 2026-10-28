package com.cheesestore.cosmetic;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the default, fully-populated cosmetic catalogue written on first launch.
 *
 * <p>All particle / effect / mob / colour ids below are valid vanilla Minecraft 1.21.1.
 * Composition (~63 entries):
 * <ul>
 *   <li>10 × TRAIL</li>
 *   <li> 8 × KILL_EFFECT</li>
 *   <li> 8 × DEATH_EFFECT</li>
 *   <li>16 × NAME_COLOR (one per Minecraft chat colour)</li>
 *   <li> 6 × CHAT_TAG</li>
 *   <li> 8 × PET</li>
 *   <li> 7 × MOUNT (cosmetic rideable mobs)</li>
 *   <li> 8 × CAPE (client-rendered back capes; texture param = the capeId)</li>
 * </ul>
 */
final class DefaultCatalog {

    private DefaultCatalog() {}

    static List<CosmeticEntry> build() {
        List<CosmeticEntry> list = new ArrayList<>();
        int order = 0;

        // ── TRAIL (10) — particle / shape (trail|halo|spiral|orbit) / density ──────
        list.add(trail("trail_flame",     "Sillage de Flammes",   "Une traînée de feu derrière vous.",        "minecraft:blaze_powder",      400,  "minecraft:flame",            "trail",  3, order++));
        list.add(trail("trail_heart",     "Cœurs Flottants",      "Des cœurs s'élèvent dans votre sillage.",  "minecraft:poppy",             350,  "minecraft:heart",            "spiral", 2, order++));
        list.add(trail("trail_soul",      "Feux d'Âme",           "Des flammes bleues spectrales.",           "minecraft:soul_torch",        500,  "minecraft:soul_fire_flame",  "trail",  3, order++));
        list.add(trail("trail_note",      "Notes Musicales",      "Des notes colorées vous accompagnent.",    "minecraft:note_block",        300,  "minecraft:note",             "orbit",  2, order++));
        list.add(trail("trail_portal",    "Vortex de l'End",      "Des particules de portail tourbillonnent.", "minecraft:ender_pearl",      600,  "minecraft:portal",           "spiral", 4, order++));
        list.add(trail("trail_happy",     "Aura Émeraude",        "Un halo de particules vertes.",            "minecraft:emerald",           350,  "minecraft:happy_villager",   "halo",   3, order++));
        list.add(trail("trail_crit",      "Étincelles Critiques", "Des étincelles d'attaque critique.",       "minecraft:diamond_sword",     450,  "minecraft:crit",             "trail",  3, order++));
        list.add(trail("trail_snow",      "Flocons d'Hiver",      "Une douce chute de neige vous suit.",      "minecraft:snowball",          300,  "minecraft:snowflake",        "trail",  4, order++));
        list.add(trail("trail_dragon",    "Souffle du Dragon",    "L'haleine destructrice du dragon.",        "minecraft:dragon_breath",     800,  "minecraft:dragon_breath",    "halo",   3, order++));
        list.add(trail("trail_enchant",   "Runes Enchantées",     "Des glyphes magiques en orbite.",          "minecraft:enchanted_book",    550,  "minecraft:enchant",          "orbit",  3, order++));

        // ── KILL_EFFECT (8) — effect / sound (opt) / particle (opt) ────────────────
        list.add(killEffect("kill_firework",  "Feu d'Artifice",      "Un feu d'artifice explose sur votre victime.",   "minecraft:firework_rocket", 500,  "firework",   "minecraft:entity.firework_rocket.blast", null, order++));
        list.add(killEffect("kill_lightning", "Foudre Vengeresse",   "La foudre s'abat sur votre adversaire.",         "minecraft:lightning_rod",   700,  "lightning",  "minecraft:entity.lightning_bolt.thunder", null, order++));
        list.add(killEffect("kill_explosion", "Détonation",          "Une explosion marque votre kill.",               "minecraft:tnt",             550,  "explosion",  "minecraft:entity.generic.explode", "minecraft:explosion_emitter", order++));
        list.add(killEffect("kill_souls",     "Récolte d'Âmes",      "Des âmes s'échappent de votre victime.",         "minecraft:soul_lantern",    650,  "souls",      "minecraft:particle.soul_escape.use", "minecraft:soul", order++));
        list.add(killEffect("kill_totem",     "Triomphe du Totem",   "L'aura dorée du totem d'immortalité.",           "minecraft:totem_of_undying",750,  "totem",      "minecraft:item.totem.use", "minecraft:totem_of_undying", order++));
        list.add(killEffect("kill_blood",     "Effusion de Sang",    "Une gerbe rouge brutale.",                        "minecraft:redstone",        450,  "blood",      "minecraft:entity.player.attack.crit", "minecraft:dust", order++));
        list.add(killEffect("kill_ender",     "Téléfrag de l'End",   "Une implosion de particules de l'End.",          "minecraft:ender_eye",       600,  "explosion",  "minecraft:entity.enderman.teleport", "minecraft:portal", order++));
        list.add(killEffect("kill_anvil",     "Enclume Écrasante",   "Le fracas d'une enclume qui tombe.",             "minecraft:anvil",           500,  "explosion",  "minecraft:block.anvil.land", "minecraft:cloud", order++));

        // ── DEATH_EFFECT (8) — effect / particle (opt) / sound (opt) ───────────────
        list.add(deathEffect("death_explosion", "Sortie Explosive",   "Vous explosez en mourant.",                  "minecraft:tnt",            450,  "explosion", "minecraft:explosion", "minecraft:entity.generic.explode", order++));
        list.add(deathEffect("death_souls",     "Envol des Âmes",     "Votre âme s'élève vers le ciel.",            "minecraft:soul_lantern",   500,  "souls",     "minecraft:soul", "minecraft:particle.soul_escape.use", order++));
        list.add(deathEffect("death_smoke",     "Disparition Fumée",  "Un nuage de fumée masque votre chute.",      "minecraft:campfire",       350,  "explosion", "minecraft:campfire_cosy_smoke", "minecraft:block.fire.extinguish", order++));
        list.add(deathEffect("death_cloud",     "Nuage Empoisonné",   "Un nuage toxique se dissipe.",               "minecraft:fermented_spider_eye",400,"explosion","minecraft:effect", "minecraft:entity.splash_potion.break", order++));
        list.add(deathEffect("death_totem",     "Lueur Finale",       "Un dernier éclat doré du totem.",            "minecraft:totem_of_undying",550, "totem",     "minecraft:totem_of_undying", "minecraft:item.totem.use", order++));
        list.add(deathEffect("death_firework",  "Bouquet Final",      "Un feu d'artifice d'adieu.",                 "minecraft:firework_rocket",500,  "firework",  null, "minecraft:entity.firework_rocket.blast", order++));
        list.add(deathEffect("death_dragon",    "Désintégration",     "Vous vous désintégrez comme le dragon.",     "minecraft:dragon_head",    800,  "explosion", "minecraft:dragon_breath", "minecraft:entity.ender_dragon.death", order++));
        list.add(deathEffect("death_lightning", "Cendres Foudroyées", "La foudre réduit votre corps en cendres.",   "minecraft:lightning_rod",  650,  "lightning", "minecraft:large_smoke", "minecraft:entity.lightning_bolt.impact", order++));

        // ── NAME_COLOR (16) — one per Minecraft chat colour ────────────────────────
        list.add(nameColor("name_black",        "Nom Noir",          "minecraft:black_dye",      200, "black", order++));
        list.add(nameColor("name_dark_blue",    "Nom Bleu Foncé",    "minecraft:lapis_lazuli",   200, "dark_blue", order++));
        list.add(nameColor("name_dark_green",   "Nom Vert Foncé",    "minecraft:green_dye",      200, "dark_green", order++));
        list.add(nameColor("name_dark_aqua",    "Nom Cyan Foncé",    "minecraft:prismarine_crystals",200, "dark_aqua", order++));
        list.add(nameColor("name_dark_red",     "Nom Rouge Foncé",   "minecraft:redstone",       200, "dark_red", order++));
        list.add(nameColor("name_dark_purple",  "Nom Violet Foncé",  "minecraft:purple_dye",     200, "dark_purple", order++));
        list.add(nameColor("name_gold",         "Nom Or",            "minecraft:gold_ingot",     300, "gold", order++));
        list.add(nameColor("name_gray",         "Nom Gris",          "minecraft:gray_dye",       200, "gray", order++));
        list.add(nameColor("name_dark_gray",    "Nom Gris Foncé",    "minecraft:light_gray_dye", 200, "dark_gray", order++));
        list.add(nameColor("name_blue",         "Nom Bleu",          "minecraft:blue_dye",       250, "blue", order++));
        list.add(nameColor("name_green",        "Nom Vert",          "minecraft:lime_dye",       250, "green", order++));
        list.add(nameColor("name_aqua",         "Nom Aqua",          "minecraft:diamond",        250, "aqua", order++));
        list.add(nameColor("name_red",          "Nom Rouge",         "minecraft:red_dye",        250, "red", order++));
        list.add(nameColor("name_light_purple", "Nom Rose",          "minecraft:pink_dye",       250, "light_purple", order++));
        list.add(nameColor("name_yellow",       "Nom Jaune",         "minecraft:yellow_dye",     250, "yellow", order++));
        list.add(nameColor("name_white",        "Nom Blanc",         "minecraft:white_dye",      200, "white", order++));

        // ── CHAT_TAG (6) — tag prefix / color ──────────────────────────────────────
        list.add(chatTag("tag_vip",    "Tag VIP",       "Affiche ✦ VIP devant votre nom.",        "minecraft:gold_ingot",   600, "✦ VIP",    "gold", order++));
        list.add(chatTag("tag_mvp",    "Tag MVP",       "Affiche ❖ MVP devant votre nom.",        "minecraft:diamond",      800, "❖ MVP",    "aqua", order++));
        list.add(chatTag("tag_pro",    "Tag PRO",       "Affiche ⚔ PRO devant votre nom.",        "minecraft:iron_sword",   500, "⚔ PRO",    "red", order++));
        list.add(chatTag("tag_legend", "Tag Légende",   "Affiche ☀ LÉGENDE devant votre nom.",    "minecraft:nether_star",  1000,"☀ LÉGENDE", "yellow", order++));
        list.add(chatTag("tag_ghost",  "Tag Fantôme",   "Affiche ☠ devant votre nom.",            "minecraft:skeleton_skull",450,"☠",        "dark_gray", order++));
        list.add(chatTag("tag_heart",  "Tag Cœur",      "Affiche ❤ devant votre nom.",            "minecraft:poppy",        400, "❤",        "light_purple", order++));

        // ── PET (8) — mob entity id / petName (opt) / baby (true|false) ─────────────
        list.add(pet("pet_allay",     "Familier Allay",     "Un allay vous suit fidèlement.",        "minecraft:allay",          700, "minecraft:allay",     null,      false, order++));
        list.add(pet("pet_cat",       "Chaton",             "Un petit chat à vos côtés.",            "minecraft:cat_spawn_egg",  500, "minecraft:cat",       "Minou",   true,  order++));
        list.add(pet("pet_wolf",      "Louveteau",          "Un loyal louveteau.",                   "minecraft:bone",           500, "minecraft:wolf",      "Croc",    true,  order++));
        list.add(pet("pet_fox",       "Renardeau",          "Un renard espiègle.",                   "minecraft:sweet_berries",  600, "minecraft:fox",       null,      true,  order++));
        list.add(pet("pet_parrot",    "Perroquet",          "Un perroquet coloré sur votre route.",  "minecraft:feather",        600, "minecraft:parrot",    "Coco",    false, order++));
        list.add(pet("pet_bee",       "Abeille",            "Une abeille bourdonnante.",             "minecraft:honeycomb",      450, "minecraft:bee",       null,      false, order++));
        list.add(pet("pet_axolotl",   "Axolotl",            "Un axolotl porte-bonheur.",             "minecraft:tropical_fish",  650, "minecraft:axolotl",   null,      true,  order++));
        list.add(pet("pet_chicken",   "Poussin",            "Un poussin qui ne vous lâche pas.",     "minecraft:egg",            300, "minecraft:chicken",   "Nugget",  true,  order++));

        // ── MOUNT (7) — rideable cosmetic mobs. mob / saddle (true|false) / variant (opt) ──
        // Cosmetic only (vanilla speed, no stats); auto-recalled in combat. Summon with /mount.
        list.add(mount("mount_horse_white", "Étalon Blanc",       "Un fier étalon blanc à monter.",        "minecraft:saddle",                       1200, "minecraft:horse",          true,  "white", order++));
        list.add(mount("mount_horse_black", "Coursier Noir",      "Un coursier noir de jais.",             "minecraft:black_dye",                    1200, "minecraft:horse",          true,  "black", order++));
        list.add(mount("mount_donkey",      "Âne Têtu",           "Un âne robuste et têtu.",               "minecraft:hay_block",                     800, "minecraft:donkey",         true,  null,    order++));
        list.add(mount("mount_mule",        "Mule de Bât",        "Une solide mule de bât.",               "minecraft:chest",                         900, "minecraft:mule",           true,  null,    order++));
        list.add(mount("mount_pig",         "Cochon Sellé",       "Un cochon sellé prêt à galoper.",       "minecraft:carrot_on_a_stick",             600, "minecraft:pig",            true,  null,    order++));
        list.add(mount("mount_strider",     "Strider du Nether",  "Un strider pour traverser la lave.",    "minecraft:warped_fungus_on_a_stick",     1500, "minecraft:strider",        true,  null,    order++));
        list.add(mount("mount_camel",       "Chameau du Désert",  "Un chameau du grand désert.",           "minecraft:cactus",                       1800, "minecraft:camel",          true,  null,    order++));
        list.add(mount("mount_skeleton_horse", "Cheval Squelette", "Une monture spectrale d'outre-tombe.",  "minecraft:bone",                         1600, "minecraft:skeleton_horse", true,  null,    order++));
        list.add(mount("mount_zombie_horse",   "Cheval Zombi",     "Un destrier réanimé des morts.",        "minecraft:rotten_flesh",                 1600, "minecraft:zombie_horse",   true,  null,    order++));
        list.add(mount("mount_llama",          "Lama de Caravane", "Un robuste lama de caravane.",          "minecraft:lead",                         1000, "minecraft:llama",          true,  null,    order++));
        list.add(mount("mount_ravager",        "Ravageur Dompté",  "Une imposante monture de raid.",        "minecraft:saddle",                       2500, "minecraft:ravager",        true,  null,    order++));

        // ── CAPE (32) — client-rendered back capes. texture = the capeId ───
        // The cape PNG (64x32 vanilla cape format) ships in the factionwarhud jar at
        // assets/factionwarhud/textures/entity/cape/<capeId>.png and is rendered client-side.
        // Themed set: 4 faction-flavoured + 4 premium (gold/black/victory/dragon).
        list.add(cape("cape_phenix",  "Cape du Phénix",   "Une cape rouge ardente, ailes de braise.",     "minecraft:fire_charge",     1000, order++));
        list.add(cape("cape_golem",   "Cape du Golem",    "Une lourde cape de bronze et de pierre.",       "minecraft:copper_block",    1000, order++));
        list.add(cape("cape_spectre", "Cape du Spectre",  "Une cape bleue spectrale et fantomatique.",     "minecraft:phantom_membrane",1000, order++));
        list.add(cape("cape_sylvain", "Cape Sylvaine",    "Une cape verte tissée de feuilles.",            "minecraft:oak_leaves",      1000, order++));
        list.add(cape("cape_general", "Cape du Général",  "Une majestueuse cape dorée de commandement.",   "minecraft:gold_block",      2500, order++));
        list.add(cape("cape_officer", "Cape d'Officier",  "Une élégante cape noire d'officier.",           "minecraft:black_wool",      2000, order++));
        list.add(cape("cape_laurel",  "Cape du Laurier",  "Une cape blanc et or de la victoire.",          "minecraft:totem_of_undying",3000, order++));
        list.add(cape("cape_dragon",  "Cape du Dragon",   "Une cape pourpre et noire d'écailles.",         "minecraft:dragon_head",     3500, order++));

        // ── CAPE — collection skinmc.net (24) : MINECON, Mojang historiques & designs custom ───
        list.add(cape("cape_minecon2011",        "Cape MINECON 2011",        "La légendaire cape creeper de la MINECON 2011.", "minecraft:creeper_head",       5000, order++));
        list.add(cape("cape_minecon2012",        "Cape MINECON 2012",        "Cape officielle de la MINECON 2012.",            "minecraft:nether_star",        5000, order++));
        list.add(cape("cape_minecon2013",        "Cape MINECON 2013",        "Cape losange verte de la MINECON 2013.",         "minecraft:diamond",            5000, order++));
        list.add(cape("cape_minecon2015",        "Cape MINECON 2015",        "Cape planète cerclée de la MINECON 2015.",       "minecraft:ender_eye",          5000, order++));
        list.add(cape("cape_minecon2016",        "Cape MINECON 2016",        "Cape creeper chromée de la MINECON 2016.",       "minecraft:creeper_head",       5000, order++));
        list.add(cape("cape_15th_anniversary",   "Cape 15e Anniversaire",    "Cape du 15e anniversaire de Minecraft.",         "minecraft:cake",               4000, order++));
        list.add(cape("cape_mojang",             "Cape Mojang",              "La cape rouge au logo Mojang.",                  "minecraft:red_banner",         4500, order++));
        list.add(cape("cape_millionth_customer", "Cape du Millionième",      "Cape dorée rarissime des origines.",             "minecraft:gold_ingot",         6000, order++));
        list.add(cape("cape_cobalt",             "Cape Cobalt",              "Cape bleu profond des studios Mojang.",          "minecraft:lapis_lazuli",       3500, order++));
        list.add(cape("cape_scrolls_champion",   "Cape Champion Scrolls",    "Cape de champion du jeu Scrolls.",               "minecraft:writable_book",      3500, order++));
        list.add(cape("cape_mrmessiah",          "Cape MrMessiah",           "Cape de développeur historique.",                "minecraft:emerald",            4000, order++));
        list.add(cape("cape_birthday",           "Cape d'Anniversaire",      "Une cape festive de gâteau.",                    "minecraft:cake",               1500, order++));
        list.add(cape("cape_valentine",          "Cape de la Saint-Valentin","Cape rouge au cœur passionné.",                  "minecraft:poppy",              1500, order++));
        list.add(cape("cape_turtle",             "Cape de la Tortue",        "Cape verte d'écailles de tortue.",               "minecraft:turtle_helmet",      2000, order++));
        list.add(cape("cape_prismarine",         "Cape de Prismarine",       "Cape aquatique turquoise.",                      "minecraft:prismarine_crystals",2200, order++));
        list.add(cape("cape_cherry_blossom",     "Cape des Cerisiers",       "Cape florale rose et blanche.",                  "minecraft:cherry_sapling",     2200, order++));
        list.add(cape("cape_devil_wings",        "Cape des Ailes du Diable", "Cape noire aux ailes de braise.",                "minecraft:fire_charge",        3000, order++));
        list.add(cape("cape_celestial_crystals", "Cape des Cristaux Célestes","Cape sombre aux cristaux violets.",             "minecraft:amethyst_cluster",   3000, order++));
        list.add(cape("cape_fallen_knight",      "Cape du Chevalier Déchu",  "Cape de charbon d'un chevalier déchu.",          "minecraft:netherite_scrap",    3200, order++));
        list.add(cape("cape_crimson_voyager",    "Cape du Voyageur Cramoisi","Cape rouge sang du voyageur.",                   "minecraft:redstone",           2500, order++));
        list.add(cape("cape_eye_blossom",        "Cape de l'Œil Fleuri",     "Cape sombre aux accents d'œil-fleur.",           "minecraft:ender_eye",          2800, order++));
        list.add(cape("cape_beach_sunset",       "Cape du Coucher de Soleil","Cape dégradé arc-en-ciel.",                      "minecraft:orange_dye",         2400, order++));
        list.add(cape("cape_forest",             "Cape de la Forêt",         "Cape verte des grands bois.",                    "minecraft:spruce_sapling",     2000, order++));
        list.add(cape("cape_black_rose",         "Cape de la Rose Noire",    "Cape noire à la rose écarlate.",                 "minecraft:wither_rose",        2600, order++));

        return list;
    }

    // ── Builders ────────────────────────────────────────────────────────────────

    private static CosmeticEntry trail(String id, String title, String desc, String icon, int price,
                                       String particle, String shape, int density, int order) {
        CosmeticEntry c = new CosmeticEntry(id, title, desc, "TRAIL", icon, price);
        c.setSortOrder(order);
        c.put("particle", particle).put("shape", shape).put("density", Integer.toString(density));
        return c;
    }

    private static CosmeticEntry killEffect(String id, String title, String desc, String icon, int price,
                                            String effect, String sound, String particle, int order) {
        CosmeticEntry c = new CosmeticEntry(id, title, desc, "KILL_EFFECT", icon, price);
        c.setSortOrder(order);
        c.put("effect", effect);
        if (sound != null) c.put("sound", sound);
        if (particle != null) c.put("particle", particle);
        return c;
    }

    private static CosmeticEntry deathEffect(String id, String title, String desc, String icon, int price,
                                             String effect, String particle, String sound, int order) {
        CosmeticEntry c = new CosmeticEntry(id, title, desc, "DEATH_EFFECT", icon, price);
        c.setSortOrder(order);
        c.put("effect", effect);
        if (particle != null) c.put("particle", particle);
        if (sound != null) c.put("sound", sound);
        return c;
    }

    private static CosmeticEntry nameColor(String id, String title, String icon, int price,
                                           String color, int order) {
        CosmeticEntry c = new CosmeticEntry(id, title, "Colore votre pseudo en " + color + ".", "NAME_COLOR", icon, price);
        c.setSortOrder(order);
        c.put("color", color);
        return c;
    }

    private static CosmeticEntry chatTag(String id, String title, String desc, String icon, int price,
                                         String tag, String color, int order) {
        CosmeticEntry c = new CosmeticEntry(id, title, desc, "CHAT_TAG", icon, price);
        c.setSortOrder(order);
        c.put("tag", tag).put("color", color);
        return c;
    }

    private static CosmeticEntry pet(String id, String title, String desc, String icon, int price,
                                     String mob, String petName, boolean baby, int order) {
        CosmeticEntry c = new CosmeticEntry(id, title, desc, "PET", icon, price);
        c.setSortOrder(order);
        c.put("mob", mob).put("baby", Boolean.toString(baby));
        if (petName != null) c.put("petName", petName);
        return c;
    }

    private static CosmeticEntry mount(String id, String title, String desc, String icon, int price,
                                       String mob, boolean saddle, String variant, int order) {
        CosmeticEntry c = new CosmeticEntry(id, title, desc, "MOUNT", icon, price);
        c.setSortOrder(order);
        c.put("mob", mob).put("saddle", Boolean.toString(saddle));
        if (variant != null) c.put("variant", variant);
        return c;
    }

    /**
     * A CAPE entry. The {@code texture} param carries the capeId itself (== the entry id), which the
     * client (factionwarhud) resolves to {@code factionwarhud:textures/entity/cape/<capeId>.png}.
     */
    private static CosmeticEntry cape(String id, String title, String desc, String icon, int price, int order) {
        CosmeticEntry c = new CosmeticEntry(id, title, desc, "CAPE", icon, price);
        c.setSortOrder(order);
        c.put("texture", id); // capeId == entry id; the client maps it to the cape PNG.
        return c;
    }
}
