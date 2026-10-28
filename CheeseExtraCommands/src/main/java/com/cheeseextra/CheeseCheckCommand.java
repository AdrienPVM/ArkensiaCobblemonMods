package com.cheeseextra;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * /cheesecheck — verification commands for NPC quest system.
 *
 * All subcommands follow the same pattern:
 *   /cheesecheck <type> <player> <args...> <scoreboard> <value>
 * If the check PASSES → sets scoreboard to value + sends success message.
 * If it FAILS → sends failure message, sets nothing.
 *
 * This allows EasyNPC buttons to run the command; if check fails, progress stays
 * at odd (waiting) and the NPC shows the "still working on it" dialog next time.
 */
public class CheeseCheckCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("cheeseextra");

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {

        dispatcher.register(CommandManager.literal("cheesecheck")
            .requires(src -> src.hasPermissionLevel(2))

            // /cheesecheck ev <player> <minEV> <scoreboard> <value> [statCount] [teamCount]
            .then(CommandManager.literal("ev")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                .then(CommandManager.argument("minEV", IntegerArgumentType.integer(1, 252))
                .then(CommandManager.argument("scoreboard", StringArgumentType.word())
                .then(CommandManager.argument("value", IntegerArgumentType.integer())
                    .executes(ctx -> checkEv(ctx, 1, 1))
                    .then(CommandManager.argument("statCount", IntegerArgumentType.integer(1, 6))
                        .executes(ctx -> checkEv(ctx, IntegerArgumentType.getInteger(ctx, "statCount"), 1))
                        .then(CommandManager.argument("teamCount", IntegerArgumentType.integer(1, 6))
                            .executes(ctx -> checkEv(ctx, IntegerArgumentType.getInteger(ctx, "statCount"), IntegerArgumentType.getInteger(ctx, "teamCount"))))))))))

            // /cheesecheck iv <player> <count> <minIV> <scoreboard> <value> [teamCount]
            .then(CommandManager.literal("iv")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 6))
                .then(CommandManager.argument("minIV", IntegerArgumentType.integer(1, 31))
                .then(CommandManager.argument("scoreboard", StringArgumentType.word())
                .then(CommandManager.argument("value", IntegerArgumentType.integer())
                .executes(ctx -> checkIv(ctx, 1))
                .then(CommandManager.argument("teamCount", IntegerArgumentType.integer(1, 6))
                    .executes(ctx -> checkIv(ctx, IntegerArgumentType.getInteger(ctx, "teamCount"))))))))))

            // /cheesecheck pokemon <player> <species> <scoreboard> <value>
            .then(CommandManager.literal("pokemon")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                .then(CommandManager.argument("species", StringArgumentType.word())
                .then(CommandManager.argument("scoreboard", StringArgumentType.word())
                .then(CommandManager.argument("value", IntegerArgumentType.integer())
                .executes(CheeseCheckCommand::checkPokemon))))))

            // /cheesecheck ballvariety <player> <count> <scoreboard> <value>
            .then(CommandManager.literal("ballvariety")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 20))
                .then(CommandManager.argument("scoreboard", StringArgumentType.word())
                .then(CommandManager.argument("value", IntegerArgumentType.integer())
                .executes(CheeseCheckCommand::checkBallVariety))))))
        );

        LOGGER.info("[CheeseExtra] /cheesecheck command registered (ev, iv, pokemon, ballvariety)");
    }

    // ── /cheesecheck ev ───────────────────────────────────────────────────

    private static int checkEv(CommandContext<ServerCommandSource> ctx, int requiredStatCount, int requiredTeamCount) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(ctx, "player");
        int minEV = IntegerArgumentType.getInteger(ctx, "minEV");
        String scoreboard = StringArgumentType.getString(ctx, "scoreboard");
        int value = IntegerArgumentType.getInteger(ctx, "value");

        if (!CobblemonReflect.isAvailable()) {
            player.sendMessage(Text.literal("[CheeseCheck] Cobblemon non disponible.").formatted(Formatting.RED), false);
            return 0;
        }

        List<Object> party = CobblemonReflect.getPartyPokemon(player);
        int matchingPokemon = 0;
        for (Object poke : party) {
            int[] evs = CobblemonReflect.getEvs(poke);
            int matchingStats = 0;
            for (int ev : evs) {
                if (ev >= minEV) matchingStats++;
            }
            if (matchingStats >= requiredStatCount) matchingPokemon++;
        }

        if (matchingPokemon >= requiredTeamCount) {
            setScore(ctx, player, scoreboard, value);
            if (requiredTeamCount == 1) {
                player.sendMessage(Text.literal("V\u00E9rification r\u00E9ussie ! Un de tes Pok\u00E9mon a " + requiredStatCount + " stats \u00E0 " + minEV + "+ EVs.")
                    .formatted(Formatting.GREEN), false);
            } else {
                player.sendMessage(Text.literal("V\u00E9rification r\u00E9ussie ! " + matchingPokemon + "/" + requiredTeamCount + " Pok\u00E9mon dans ton \u00E9quipe remplissent le crit\u00E8re (" + requiredStatCount + " stats \u00E0 " + minEV + "+ EVs).")
                    .formatted(Formatting.GREEN), false);
            }
            return 1;
        }

        if (requiredTeamCount == 1) {
            if (requiredStatCount == 1) {
                player.sendMessage(Text.literal("Aucun Pok\u00E9mon dans ton \u00E9quipe n'a " + minEV + " EVs dans une stat. Continue l'entra\u00EEnement !")
                    .formatted(Formatting.RED), false);
            } else {
                player.sendMessage(Text.literal("Aucun Pok\u00E9mon dans ton \u00E9quipe n'a " + requiredStatCount + " stats \u00E0 " + minEV + "+ EVs. Continue l'entra\u00EEnement !")
                    .formatted(Formatting.RED), false);
            }
        } else {
            player.sendMessage(Text.literal(matchingPokemon + "/" + requiredTeamCount + " Pok\u00E9mon dans ton \u00E9quipe remplissent le crit\u00E8re (" + requiredStatCount + " stats \u00E0 " + minEV + "+ EVs). Continue l'entra\u00EEnement !")
                .formatted(Formatting.RED), false);
        }
        return 0;
    }

    // ── /cheesecheck iv ───────────────────────────────────────────────────

    private static int checkIv(CommandContext<ServerCommandSource> ctx, int requiredTeamCount) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(ctx, "player");
        int count = IntegerArgumentType.getInteger(ctx, "count");
        int minIV = IntegerArgumentType.getInteger(ctx, "minIV");
        String scoreboard = StringArgumentType.getString(ctx, "scoreboard");
        int value = IntegerArgumentType.getInteger(ctx, "value");

        if (!CobblemonReflect.isAvailable()) {
            player.sendMessage(Text.literal("[CheeseCheck] Cobblemon non disponible.").formatted(Formatting.RED), false);
            return 0;
        }

        List<Object> party = CobblemonReflect.getPartyPokemon(player);
        int matchingPokemon = 0;
        for (Object poke : party) {
            int[] ivs = CobblemonReflect.getIvs(poke);
            int matchingIvs = 0;
            for (int iv : ivs) {
                if (iv >= minIV) matchingIvs++;
            }
            if (matchingIvs >= count) matchingPokemon++;
        }

        if (matchingPokemon >= requiredTeamCount) {
            setScore(ctx, player, scoreboard, value);
            if (requiredTeamCount == 1) {
                player.sendMessage(Text.literal("V\u00E9rification r\u00E9ussie ! Un de tes Pok\u00E9mon a " + count + " IVs \u00E0 " + minIV + "+.")
                    .formatted(Formatting.GREEN), false);
            } else {
                player.sendMessage(Text.literal("V\u00E9rification r\u00E9ussie ! " + matchingPokemon + "/" + requiredTeamCount + " Pok\u00E9mon dans ton \u00E9quipe remplissent le crit\u00E8re (" + count + " IVs \u00E0 " + minIV + "+).")
                    .formatted(Formatting.GREEN), false);
            }
            return 1;
        }

        if (requiredTeamCount == 1) {
            player.sendMessage(Text.literal("Aucun Pok\u00E9mon dans ton \u00E9quipe n'a " + count + " IVs \u00E0 " + minIV + "+. Continue l'\u00E9levage !")
                .formatted(Formatting.RED), false);
        } else {
            player.sendMessage(Text.literal(matchingPokemon + "/" + requiredTeamCount + " Pok\u00E9mon dans ton \u00E9quipe remplissent le crit\u00E8re (" + count + " IVs \u00E0 " + minIV + "+). Continue l'\u00E9levage !")
                .formatted(Formatting.RED), false);
        }
        return 0;
    }

    // ── /cheesecheck pokemon ──────────────────────────────────────────────

    private static int checkPokemon(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(ctx, "player");
        String species = StringArgumentType.getString(ctx, "species").toLowerCase();
        String scoreboard = StringArgumentType.getString(ctx, "scoreboard");
        int value = IntegerArgumentType.getInteger(ctx, "value");

        if (!CobblemonReflect.isAvailable()) {
            player.sendMessage(Text.literal("[CheeseCheck] Cobblemon non disponible.").formatted(Formatting.RED), false);
            return 0;
        }

        List<Object> party = CobblemonReflect.getPartyPokemon(player);
        for (Object poke : party) {
            String speciesId = CobblemonReflect.getSpeciesId(poke);
            if (speciesId.equals(species) || speciesId.endsWith(":" + species)) {
                setScore(ctx, player, scoreboard, value);
                player.sendMessage(Text.literal("Vérification réussie ! Tu as bien un " + species + " dans ton équipe.")
                    .formatted(Formatting.GREEN), false);
                return 1;
            }
        }

        player.sendMessage(Text.literal("Tu n'as pas de " + species + " dans ton équipe. Capture-en un et reviens !")
            .formatted(Formatting.RED), false);
        return 0;
    }

    // ── /cheesecheck ballvariety ──────────────────────────────────────────

    private static int checkBallVariety(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(ctx, "player");
        int count = IntegerArgumentType.getInteger(ctx, "count");
        String scoreboard = StringArgumentType.getString(ctx, "scoreboard");
        int value = IntegerArgumentType.getInteger(ctx, "value");

        if (!CobblemonReflect.isAvailable()) {
            player.sendMessage(Text.literal("[CheeseCheck] Cobblemon non disponible.").formatted(Formatting.RED), false);
            return 0;
        }

        List<Object> party = CobblemonReflect.getPartyPokemon(player);
        Set<String> ballTypes = new HashSet<>();
        for (Object poke : party) {
            String ball = CobblemonReflect.getCaughtBall(poke);
            if (!ball.isEmpty()) ballTypes.add(ball);
        }

        if (ballTypes.size() >= count) {
            setScore(ctx, player, scoreboard, value);
            player.sendMessage(Text.literal("Vérification réussie ! " + ballTypes.size() + " types de Balls différentes dans ton équipe.")
                .formatted(Formatting.GREEN), false);
            return 1;
        }

        player.sendMessage(Text.literal("Tu as " + ballTypes.size() + " types de Balls différentes, il en faut " + count + ". Capture avec des Balls variées !")
            .formatted(Formatting.RED), false);
        return 0;
    }

    // ── Scoreboard helper ─────────────────────────────────────────────────

    private static void setScore(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity player,
                                  String objectiveName, int value) {
        Scoreboard scoreboard = player.getServer().getScoreboard();
        ScoreboardObjective objective = scoreboard.getNullableObjective(objectiveName);
        if (objective == null) {
            LOGGER.warn("[CheeseExtra] Scoreboard objective '{}' not found — creating it", objectiveName);
            objective = scoreboard.addObjective(objectiveName,
                net.minecraft.scoreboard.ScoreboardCriterion.DUMMY,
                Text.literal(objectiveName),
                net.minecraft.scoreboard.ScoreboardCriterion.RenderType.INTEGER,
                true, null);
        }
        ScoreHolder holder = ScoreHolder.fromName(player.getName().getString());
        scoreboard.getOrCreateScore(holder, objective).setScore(value);
        LOGGER.info("[CheeseExtra] Set {} = {} for {}", objectiveName, value, player.getName().getString());
    }
}
