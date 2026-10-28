package com.cobbleshift;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * /cshift command tree:
 *   /cshift transform <player> <pokemon>     — transform a player
 *   /cshift revert <player>                  — revert a player
 *   /cshift revertall                        — revert all
 *   /cshift ability <player> [pokemon]       — force ability / clear force
 *   /cshift ability global enable|disable    — toggle global abilities
 *   /cshift ability toggle <player>          — toggle per-player ability
 *   /cshift list                             — list active transforms
 *   /cshift species <name>                   — info about a species
 *   /cshift speednpc <value>                 — set NPC movement speed multiplier
 */
public class CobbleShiftCommand {

    private static final List<String> KNOWN_FORMS = List.of(
        "alolan", "galarian", "hisuian", "paldean", "mega", "gmax"
    );

    private static final SuggestionProvider<ServerCommandSource> SPECIES_SUGGESTIONS = (ctx, builder) -> {
        Collection<String> names = SpeciesMapper.getAllSpeciesNames();
        return CommandSource.suggestMatching(names, builder);
    };

    /**
     * Suggestion provider for the greedy transform arg: "species [shiny] [form]"
     * Suggests species names first, then "shiny" and known Cobblemon form names.
     */
    private static final SuggestionProvider<ServerCommandSource> TRANSFORM_SUGGESTIONS = (ctx, builder) -> {
        String remaining = builder.getRemaining();
        String[] parts = remaining.split("\\s+", -1);

        if (parts.length <= 1) {
            // Typing species name — suggest matching species
            return CommandSource.suggestMatching(SpeciesMapper.getAllSpeciesNames(), builder);
        } else {
            // Already typed species, suggest options
            String lastWord = parts[parts.length - 1];
            List<String> options = new ArrayList<>();
            boolean hasShiny = false;
            boolean hasNoAttacks = false;
            for (int i = 1; i < parts.length - 1; i++) {
                if ("shiny".equalsIgnoreCase(parts[i])) hasShiny = true;
                if ("noattacks".equalsIgnoreCase(parts[i])) hasNoAttacks = true;
            }
            if (!hasShiny) options.add("shiny");
            if (!hasNoAttacks) options.add("noattacks");
            options.addAll(KNOWN_FORMS);

            String prefix = remaining.substring(0, remaining.length() - lastWord.length());
            SuggestionsBuilder offsetBuilder = builder.createOffset(builder.getStart() + prefix.length());
            return CommandSource.suggestMatching(options, offsetBuilder);
        }
    };

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("cshift")
            .requires(src -> src.hasPermissionLevel(2))

            // /cshift transform <player> <pokemon> [shiny] [form]
            .then(literal("transform")
                .then(argument("player", EntityArgumentType.player())
                    .then(argument("pokemon", StringArgumentType.greedyString())
                        .suggests(TRANSFORM_SUGGESTIONS)
                        .executes(CobbleShiftCommand::doTransform)
                    )
                )
            )

            // /cshift revert <player>
            .then(literal("revert")
                .then(argument("player", EntityArgumentType.player())
                    .executes(CobbleShiftCommand::doRevert)
                )
            )

            // /cshift revertall
            .then(literal("revertall")
                .executes(CobbleShiftCommand::doRevertAll)
            )

            // /cshift ability — subcommands
            .then(literal("ability")
                // /cshift ability global enable|disable
                .then(literal("global")
                    .then(literal("enable").executes(ctx -> doAbilityGlobal(ctx, true)))
                    .then(literal("disable").executes(ctx -> doAbilityGlobal(ctx, false)))
                )
                // /cshift ability toggle <player>
                .then(literal("toggle")
                    .then(argument("player", EntityArgumentType.player())
                        .executes(CobbleShiftCommand::doAbilityToggle)
                    )
                )
                // /cshift ability <player> [pokemon]  (force/clear)
                .then(argument("player", EntityArgumentType.player())
                    .executes(CobbleShiftCommand::doClearAbility)
                    .then(argument("pokemon", StringArgumentType.word())
                        .suggests(SPECIES_SUGGESTIONS)
                        .executes(CobbleShiftCommand::doForceAbility)
                    )
                )
            )

            // /cshift list
            .then(literal("list")
                .executes(CobbleShiftCommand::doList)
            )

            // /cshift species <name>
            .then(literal("species")
                .then(argument("name", StringArgumentType.word())
                    .suggests(SPECIES_SUGGESTIONS)
                    .executes(CobbleShiftCommand::doSpeciesInfo)
                )
            )

            // /cshift transformnpc <pokemon> [shiny] [form]
            .then(literal("transformnpc")
                .then(argument("pokemon", StringArgumentType.greedyString())
                    .suggests(TRANSFORM_SUGGESTIONS)
                    .executes(CobbleShiftCommand::doTransformNpc)
                )
            )

            // /cshift revertnpc
            .then(literal("revertnpc")
                .executes(CobbleShiftCommand::doRevertNpc)
            )

            // /cshift revertallnpc
            .then(literal("revertallnpc")
                .executes(CobbleShiftCommand::doRevertAllNpc)
            )

            // /cshift scale <player> <value>
            .then(literal("scale")
                .then(argument("player", EntityArgumentType.player())
                    .then(argument("value", FloatArgumentType.floatArg(0.1f, 10.0f))
                        .executes(CobbleShiftCommand::doScale)
                    )
                )
            )

            // /cshift speed <player> <value>
            .then(literal("speed")
                .then(argument("player", EntityArgumentType.player())
                    .then(argument("value", FloatArgumentType.floatArg(0.1f, 10.0f))
                        .executes(CobbleShiftCommand::doSpeed)
                    )
                )
            )

            // /cshift scalenpc <value>
            .then(literal("scalenpc")
                .then(argument("value", FloatArgumentType.floatArg(0.1f, 10.0f))
                    .executes(CobbleShiftCommand::doScaleNpc)
                )
            )

            // /cshift speednpc <value>
            .then(literal("speednpc")
                .then(argument("value", FloatArgumentType.floatArg(0.1f, 10.0f))
                    .executes(CobbleShiftCommand::doSpeedNpc)
                )
            )
        );
    }

    /**
     * Parse greedy string: "species [shiny] [noattacks] [form/aspects...]"
     * Returns [species, shiny("true"/"false"), form(or null), noAttacks("true"/"false")]
     *
     * All non-shiny tokens after the species are collected as the form/aspects string.
     * This supports both simple forms ("alolan") and Cobblemon aspect strings ("magikarp_jump=black-mask").
     * Multiple aspect tokens are joined with spaces.
     */
    private static String[] parseTransformArgs(String raw) {
        String[] parts = raw.trim().split("\\s+");
        String species = parts[0];
        boolean shiny = false;
        boolean noAttacks = false;
        List<String> formParts = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            if ("shiny".equalsIgnoreCase(parts[i])) {
                shiny = true;
            } else if ("noattacks".equalsIgnoreCase(parts[i])) {
                noAttacks = true;
            } else {
                String token = parts[i];
                if (token.contains("=")) {
                    // Species feature key=value → Cobblemon aspect format
                    // "magikarp_jump=black-mask" → "magikarp-jump-black-mask"
                    String[] kv = token.split("=", 2);
                    token = kv[0].replace('_', '-') + "-" + kv[1];
                } else {
                    // Form token: normalize underscores to hyphens to match Cobblemon conventions
                    // "mega_x" → "mega-x", "mega_y" → "mega-y"
                    token = token.replace('_', '-');
                }
                formParts.add(token);
            }
        }
        // Join tokens with spaces — each token is a distinct aspect (e.g. "mega midnight"
        // = two aspects). Cobblemon's PokemonProperties.parse() uses space as the aspect
        // separator. Hyphens are reserved for INSIDE a single aspect name (e.g. "mega-x").
        String form = formParts.isEmpty() ? null : String.join(" ", formParts);
        return new String[] { species, String.valueOf(shiny), form, String.valueOf(noAttacks) };
    }

    private static int doTransform(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(ctx, "player");
        String raw = StringArgumentType.getString(ctx, "pokemon");
        String[] parsed = parseTransformArgs(raw);
        String species = parsed[0];
        boolean shiny = "true".equals(parsed[1]);
        String form = parsed[2];
        boolean noAttacks = "true".equals(parsed[3]);

        TransformManager mgr = CobbleShiftMod.INSTANCE.getTransformManager();
        boolean ok = mgr.transform(player, species, shiny, form);
        if (ok && noAttacks) {
            TransformData data = mgr.getTransform(player.getUuid());
            if (data != null) data.setAttacksEnabled(false);
        }
        if (ok) {
            String desc = species + (shiny ? " shiny" : "") + (form != null ? " " + form : "") + (noAttacks ? " [no attacks]" : "");
            ctx.getSource().sendFeedback(() -> Text.translatable("cobbleshift.transform.success", player.getName().getString(), desc).formatted(Formatting.GREEN), true);
        } else {
            ctx.getSource().sendError(Text.translatable("cobbleshift.transform.failed", species).formatted(Formatting.RED));
        }
        return ok ? 1 : 0;
    }

    private static int doRevert(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(ctx, "player");
        TransformManager mgr = CobbleShiftMod.INSTANCE.getTransformManager();

        boolean ok = mgr.revert(player);
        if (ok) {
            ctx.getSource().sendFeedback(() -> Text.translatable("cobbleshift.revert.success", player.getName().getString()).formatted(Formatting.GRAY), true);
        } else {
            ctx.getSource().sendError(Text.translatable("cobbleshift.revert.not_transformed", player.getName().getString()).formatted(Formatting.RED));
        }
        return ok ? 1 : 0;
    }

    private static int doRevertAll(CommandContext<ServerCommandSource> ctx) {
        TransformManager mgr = CobbleShiftMod.INSTANCE.getTransformManager();
        int count = mgr.getAllTransforms().size();
        mgr.revertAll();
        ctx.getSource().sendFeedback(() -> Text.translatable("cobbleshift.revert_all.success", count).formatted(Formatting.GRAY), true);
        return count;
    }

    private static int doForceAbility(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(ctx, "player");
        String pokemon = StringArgumentType.getString(ctx, "pokemon");
        AbilityManager mgr = CobbleShiftMod.INSTANCE.getAbilityManager();

        mgr.forceAbility(player, pokemon);
        ctx.getSource().sendFeedback(() -> Text.translatable("cobbleshift.ability.forced", player.getName().getString(), pokemon).formatted(Formatting.GREEN), true);
        return 1;
    }

    private static int doClearAbility(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(ctx, "player");
        AbilityManager mgr = CobbleShiftMod.INSTANCE.getAbilityManager();

        mgr.clearForce(player);
        ctx.getSource().sendFeedback(() -> Text.translatable("cobbleshift.ability.cleared", player.getName().getString()).formatted(Formatting.GRAY), true);
        return 1;
    }

    private static int doAbilityGlobal(CommandContext<ServerCommandSource> ctx, boolean enabled) {
        CobbleShiftConfig cfg = CobbleShiftMod.INSTANCE.getConfig();
        cfg.setAbilityGlobalEnabled(enabled);
        cfg.save();
        // When disabling, clear all active abilities and sync HUD on all clients
        if (!enabled) {
            CobbleShiftMod.INSTANCE.getAbilityManager().deactivateAllAndSync();
        }
        Text stateText = enabled
            ? Text.translatable("cobbleshift.ability.global_enabled").formatted(Formatting.GREEN)
            : Text.translatable("cobbleshift.ability.global_disabled").formatted(Formatting.RED);
        ctx.getSource().sendFeedback(() -> Text.translatable("cobbleshift.ability.global_toggle", stateText).formatted(Formatting.YELLOW), true);
        return 1;
    }

    private static int doAbilityToggle(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(ctx, "player");
        AbilityManager mgr = CobbleShiftMod.INSTANCE.getAbilityManager();

        boolean nowDisabled = mgr.togglePlayer(player.getUuid());
        Text stateText = nowDisabled
            ? Text.translatable("cobbleshift.ability.player_disabled").formatted(Formatting.RED)
            : Text.translatable("cobbleshift.ability.player_enabled").formatted(Formatting.GREEN);
        ctx.getSource().sendFeedback(() -> Text.translatable("cobbleshift.ability.player_toggle", stateText, player.getName().getString()).formatted(Formatting.YELLOW), true);
        return 1;
    }

    private static int doList(CommandContext<ServerCommandSource> ctx) {
        TransformManager tmgr = CobbleShiftMod.INSTANCE.getTransformManager();
        AbilityManager amgr = CobbleShiftMod.INSTANCE.getAbilityManager();
        ServerCommandSource src = ctx.getSource();

        Collection<TransformData> transforms = tmgr.getAllTransforms();
        Collection<AbilityManager.AbilityData> abilities = amgr.getAllAbilities();

        src.sendFeedback(() -> Text.literal("\u00a7e\u00a7l\u2550\u2550\u2550 ").append(Text.translatable("cobbleshift.status.header").formatted(Formatting.YELLOW, Formatting.BOLD)).append(Text.literal(" \u2550\u2550\u2550").formatted(Formatting.YELLOW, Formatting.BOLD)), false);
        src.sendFeedback(() -> Text.translatable("cobbleshift.status.transforms_active", transforms.size()).formatted(Formatting.GOLD), false);
        for (TransformData td : transforms) {
            long mins = (System.currentTimeMillis() - td.getTransformedAt()) / 60000;
            src.sendFeedback(() -> Text.literal("  \u00a77\u2022 \u00a7f" + td.getPlayerName() + " \u00a77\u2192 \u00a7e" + td.getSpeciesName() + " \u00a77(BST=" + td.getBst() + ", " + mins + "min)"), false);
        }

        src.sendFeedback(() -> Text.translatable("cobbleshift.status.abilities_active", abilities.size()).formatted(Formatting.GOLD), false);
        for (AbilityManager.AbilityData ad : abilities) {
            String forced = ad.forced() ? " \u00a7c[FORCED]" : "";
            src.sendFeedback(() -> Text.literal("  \u00a77\u2022 \u00a7f" + ad.speciesName() + " \u00a77(" + ad.type1() + ")" + forced), false);
        }

        CobbleShiftConfig cfg = CobbleShiftMod.INSTANCE.getConfig();
        String onOff = cfg.isAbilityGlobalEnabled() ? "\u00a7aON" : "\u00a7cOFF";
        src.sendFeedback(() -> Text.translatable("cobbleshift.status.global_abilities", onOff).formatted(Formatting.GOLD), false);

        return 1;
    }

    private static int doSpeciesInfo(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        SpeciesMapper.SpeciesStats stats = SpeciesMapper.resolve(name);

        if (stats == null) {
            ctx.getSource().sendError(Text.translatable("cobbleshift.species.not_found", name).formatted(Formatting.RED));
            return 0;
        }

        ServerCommandSource src = ctx.getSource();
        int bst = SpeciesMapper.computeBST(stats.hp(), stats.atk(), stats.def(), stats.spAtk(), stats.spDef(), stats.speed());
        float atkDmg = SpeciesMapper.mapAttackDamage(stats.atk(), stats.spAtk());
        int atkCd = SpeciesMapper.mapAttackCooldown(stats.speed());
        int projCount = SpeciesMapper.mapProjectileCount(bst);

        src.sendFeedback(() -> Text.literal("\u00a7e\u00a7l\u2550\u2550\u2550 ").append(Text.translatable("cobbleshift.species.header", stats.name()).formatted(Formatting.YELLOW, Formatting.BOLD)).append(Text.literal(" \u2550\u2550\u2550").formatted(Formatting.YELLOW, Formatting.BOLD)), false);
        src.sendFeedback(() -> Text.literal("\u00a76Types: \u00a7f" + stats.type1() + (stats.type2() != null ? " / " + stats.type2() : "")), false);
        src.sendFeedback(() -> Text.literal("\u00a76Stats: \u00a7fHP=" + stats.hp() + " Atk=" + stats.atk() + " Def=" + stats.def() +
            " SpAtk=" + stats.spAtk() + " SpDef=" + stats.spDef() + " Spd=" + stats.speed()), false);
        src.sendFeedback(() -> Text.literal("\u00a76BST: \u00a7f" + bst), false);
        src.sendFeedback(() -> Text.literal("\u00a76Mapped Speed: \u00a7f" + String.format("%.3f", SpeciesMapper.mapSpeed(stats.speed()))), false);
        src.sendFeedback(() -> Text.literal("\u00a76Mapped Health: \u00a7f" + String.format("%.1f", SpeciesMapper.mapMaxHealth(stats.hp())) + " HP"), false);
        src.sendFeedback(() -> Text.literal("\u00a76Attack: \u00a7f" + String.format("%.1f", atkDmg) + " dmg, " + atkCd + " tick cd, " + projCount + " projectile(s)"), false);

        AbilityType t1 = AbilityType.fromTypeName(stats.type1());
        src.sendFeedback(() -> Text.literal("\u00a76Ability: \u00a7f" + t1.displayName + " \u00a77(" + t1.typeId + ")"), false);

        return 1;
    }

    // ── NPC Transform Commands ────────────────────────────────────────────────

    private static int doTransformNpc(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        Entity target = getLookedAtEntity(player, 5.0);

        if (target == null) {
            ctx.getSource().sendError(Text.translatable("cobbleshift.npc.look_required").formatted(Formatting.RED));
            return 0;
        }
        if (!NpcTransformManager.isEasyNpc(target)) {
            ctx.getSource().sendError(Text.translatable("cobbleshift.npc.not_easynpc").formatted(Formatting.RED));
            return 0;
        }

        String raw = StringArgumentType.getString(ctx, "pokemon");
        String[] parsed = parseTransformArgs(raw);
        String species = parsed[0];
        boolean shiny = "true".equals(parsed[1]);
        String form = parsed[2];

        NpcTransformManager mgr = CobbleShiftMod.INSTANCE.getNpcTransformManager();
        boolean ok = mgr.transformNpc(target, species, shiny, form);
        if (ok) {
            String desc = species + (shiny ? " shiny" : "") + (form != null ? " " + form : "");
            ctx.getSource().sendFeedback(() -> Text.translatable("cobbleshift.npc.transform_success", desc).formatted(Formatting.GREEN), true);
        } else {
            ctx.getSource().sendError(Text.translatable("cobbleshift.npc.transform_failed", species).formatted(Formatting.RED));
        }
        return ok ? 1 : 0;
    }

    private static int doRevertNpc(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        Entity target = getLookedAtEntity(player, 5.0);

        if (target == null) {
            ctx.getSource().sendError(Text.translatable("cobbleshift.npc.revert_look_required").formatted(Formatting.RED));
            return 0;
        }
        if (!NpcTransformManager.isEasyNpc(target)) {
            ctx.getSource().sendError(Text.translatable("cobbleshift.npc.not_easynpc").formatted(Formatting.RED));
            return 0;
        }

        NpcTransformManager mgr = CobbleShiftMod.INSTANCE.getNpcTransformManager();
        if (!mgr.isNpcTransformed(target.getUuid())) {
            ctx.getSource().sendError(Text.translatable("cobbleshift.npc.not_transformed").formatted(Formatting.RED));
            return 0;
        }

        boolean ok = mgr.revertNpc(target);
        if (ok) {
            ctx.getSource().sendFeedback(() -> Text.translatable("cobbleshift.npc.revert_success").formatted(Formatting.GRAY), true);
        }
        return ok ? 1 : 0;
    }

    private static int doRevertAllNpc(CommandContext<ServerCommandSource> ctx) {
        NpcTransformManager mgr = CobbleShiftMod.INSTANCE.getNpcTransformManager();
        int count = mgr.revertAll();
        ctx.getSource().sendFeedback(() -> Text.translatable("cobbleshift.npc.revert_all", count).formatted(Formatting.GRAY), true);
        return count;
    }

    // ── Scale Commands ─────────────────────────────────────────────────────────

    private static int doScale(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(ctx, "player");
        float value = FloatArgumentType.getFloat(ctx, "value");

        TransformManager mgr = CobbleShiftMod.INSTANCE.getTransformManager();
        if (!mgr.isTransformed(player.getUuid())) {
            ctx.getSource().sendError(Text.translatable("cobbleshift.scale.not_transformed", player.getName().getString()).formatted(Formatting.RED));
            return 0;
        }

        boolean ok = mgr.setPlayerScale(player.getUuid(), value);
        if (ok) {
            ctx.getSource().sendFeedback(() -> Text.translatable("cobbleshift.scale.set", player.getName().getString(), String.format("%.1f", value)).formatted(Formatting.GREEN), true);
        }
        return ok ? 1 : 0;
    }

    private static int doSpeed(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(ctx, "player");
        float value = FloatArgumentType.getFloat(ctx, "value");

        TransformManager mgr = CobbleShiftMod.INSTANCE.getTransformManager();
        if (!mgr.isTransformed(player.getUuid())) {
            ctx.getSource().sendError(Text.translatable("cobbleshift.scale.not_transformed", player.getName().getString()).formatted(Formatting.RED));
            return 0;
        }

        boolean ok = mgr.setPlayerSpeed(player.getUuid(), value);
        if (ok) {
            ctx.getSource().sendFeedback(() -> Text.translatable("cobbleshift.speed.set", player.getName().getString(), String.format("%.1f", value)).formatted(Formatting.GREEN), true);
        }
        return ok ? 1 : 0;
    }

    private static int doScaleNpc(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        float value = FloatArgumentType.getFloat(ctx, "value");

        Entity target = getLookedAtEntity(player, 5.0);
        if (target == null) {
            ctx.getSource().sendError(Text.translatable("cobbleshift.npc.look_required_scale").formatted(Formatting.RED));
            return 0;
        }

        NpcTransformManager mgr = CobbleShiftMod.INSTANCE.getNpcTransformManager();
        if (!mgr.isNpcTransformed(target.getUuid())) {
            ctx.getSource().sendError(Text.translatable("cobbleshift.npc.not_transformed").formatted(Formatting.RED));
            return 0;
        }

        boolean ok = mgr.setNpcScale(target.getUuid(), value);
        if (ok) {
            ctx.getSource().sendFeedback(() -> Text.translatable("cobbleshift.npc.scale_set", String.format("%.1f", value)).formatted(Formatting.GREEN), true);
        }
        return ok ? 1 : 0;
    }

    private static int doSpeedNpc(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        float value = FloatArgumentType.getFloat(ctx, "value");

        Entity target = getLookedAtEntity(player, 5.0);
        if (target == null) {
            ctx.getSource().sendError(Text.translatable("cobbleshift.npc.look_required_speed").formatted(Formatting.RED));
            return 0;
        }

        NpcTransformManager mgr = CobbleShiftMod.INSTANCE.getNpcTransformManager();
        if (!mgr.isNpcTransformed(target.getUuid())) {
            ctx.getSource().sendError(Text.translatable("cobbleshift.npc.not_transformed").formatted(Formatting.RED));
            return 0;
        }

        boolean ok = mgr.setNpcSpeed(target.getUuid(), value);
        if (ok) {
            ctx.getSource().sendFeedback(() -> Text.translatable("cobbleshift.npc.speed_set", String.format("%.1f", value)).formatted(Formatting.GREEN), true);
        }
        return ok ? 1 : 0;
    }

    // ── Raycast helper ────────────────────────────────────────────────────────

    /**
     * Find the entity the player is looking at within the given range.
     * Uses eye position + look direction raycast against entity bounding boxes.
     */
    private static Entity getLookedAtEntity(ServerPlayerEntity player, double range) {
        Vec3d eyePos = player.getEyePos();
        Vec3d lookDir = player.getRotationVec(1.0f);
        Vec3d endPos = eyePos.add(lookDir.multiply(range));
        Box searchBox = player.getBoundingBox().expand(range);

        Entity closest = null;
        double closestDist = range;

        for (Entity entity : player.getWorld().getOtherEntities(player, searchBox)) {
            Box entityBox = entity.getBoundingBox().expand(0.3);
            Optional<Vec3d> hit = entityBox.raycast(eyePos, endPos);
            if (hit.isPresent()) {
                double dist = eyePos.distanceTo(hit.get());
                if (dist < closestDist) {
                    closestDist = dist;
                    closest = entity;
                }
            }
        }
        return closest;
    }
}
