package com.cheesecompat;

import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.CarpetBlock;
import net.minecraft.block.MapColor;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;

public class CheeseCompatMod implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("CheeseCompatModsFix");
    private static CheeseCompatConfig config;

    public static CheeseCompatConfig getConfig() { return config; }

    @Override
    public void onInitialize() {
        config = CheeseCompatConfig.load();
        registerCobblemonMissingBlocks();
        registerStopBattleCommand();
        restrictStopBattleArgs();
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("cobblesafari")) {
            SafariBanFix.register();
            SafariBallFix.register();
            SlingshotBlockFix.register();
            DungeonCaptureFix.register();
            com.cheesecompat.block.CobbleSafariBlocks.register();
            registerSafariSpawnInfluences();
        }
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("cobblemonraiddens")) {
            RaidDenFix.register();
        }
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("rad-gyms")) {
            RadGymsFix.register();
        }
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("cobblemon_battle_tower")) {
            BattleTowerDisconnectFix.register();
        }
        // Watchdog "force-choice" : DÉSACTIVÉ en v1.2.52 (15 juin 2026).
        // Régression critique en prod : isStuckNpcActor() marquait "stuck" TOUT acteur IA
        // non-joueur avec un request actif + responses vide — l'état NORMAL de chaque tour
        // pendant que le joueur réfléchit (seuil 60 ticks = 3 s). Résultat : le watchdog
        // rejouait onChoiceRequested() au milieu de combats sains (sauvages ET dresseurs),
        // désynchronisait Showdown et FIGEAIT tous les combats. La forensique prod a aussi
        // montré qu'il ne débloquait même pas les vrais force-switch qu'il ciblait (re-jet
        // "Can't pass"). Aucun bénéfice, dommage massif → désactivé. Le cas Perish Song /
        // double-KO sera retraité proprement (filtre force-switch strict) si nécessaire.
        // if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("cobblemon")) {
        //     RctForcedSwitchFix.register();
        // }
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("cobblewarden_boss")) {
            com.cheesecompat.cobbleboss.ShriekerBossAggroKeeper.register();
            com.cheesecompat.cobbleboss.BossDarknessAura.register();
        }
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("daycareplus")) {
            ServerLifecycleEvents.SERVER_STARTED.register(com.cheesecompat.daycare.DaycareSimpleModeStore::init);
            ServerLifecycleEvents.SERVER_STOPPING.register(server -> com.cheesecompat.daycare.DaycareSimpleModeStore.shutdown());
        }
        // Force EC à matérialiser sa donnée lunaire pour chaque level chargé, sinon les
        // dims rarement visitées n'ont jamais d'évènements (la factory EC est lazy).
        EnhancedCelestialsWarmup.register();
        // Extend MegaShowdown's mega_aspects with "mega_z" so hasMega() / Devolve() work for Mega-Z forms.
        MegaShowdownAspectPatch.apply();
        // Map CacademyMons custom Mega Stones (minecraft:brick + custom_model_data) to
        // their Showdown stone ids so canMegaEvo() can find them at battle time.
        CacademyMegaStoneRemapPatch.apply();
        LOGGER.info("[CheeseCompat] Loaded (v1.2.53). Arc Phone heal={}, PC={}, Sanctuary fix={}",
            config.arcPhoneHealEnabled ? "enabled" : "DISABLED",
            config.arcPhonePCEnabled ? "enabled" : "DISABLED",
            config.sanctuaryOverflowFix ? "ON" : "OFF");
    }

    /**
     * Register blocks added in Cobblemon 1.7.x that are referenced by CobbleSafari's worldgen.
     * Without these, CobbleSafari's underground dimension fails to load.
     */
    private void registerCobblemonMissingBlocks() {
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("cobblesafari")) return;
        // Only register if Cobblemon didn't already register them (future-proofing)
        if (Registries.BLOCK.containsId(Identifier.of("cobblemon", "tatami_mat"))) return;

        // tatami_mat — CarpetBlock (flat mat on ground), used in underground dimension worldgen
        Block tatamiMat = new CarpetBlock(AbstractBlock.Settings.create()
            .noCollision()
            .mapColor(MapColor.PALE_YELLOW)
            .strength(0.1f)
            .sounds(BlockSoundGroup.WOOL));
        Registry.register(Registries.BLOCK, Identifier.of("cobblemon", "tatami_mat"), tatamiMat);
        Registry.register(Registries.ITEM, Identifier.of("cobblemon", "tatami_mat"),
            new BlockItem(tatamiMat, new Item.Settings()));

        // tatami_block — full block variant
        Block tatamiBlock = new Block(AbstractBlock.Settings.create()
            .mapColor(MapColor.PALE_YELLOW)
            .strength(0.1f)
            .sounds(BlockSoundGroup.WOOL));
        Registry.register(Registries.BLOCK, Identifier.of("cobblemon", "tatami_block"), tatamiBlock);
        Registry.register(Registries.ITEM, Identifier.of("cobblemon", "tatami_block"),
            new BlockItem(tatamiBlock, new Item.Settings()));

        LOGGER.info("[CheeseCompat] Registered cobblemon:tatami_mat and tatami_block for CobbleSafari compatibility");
    }

    /**
     * Register 1.6.1-compatible spawn influences to replace CobbleSafari's broken ones.
     * Uses Proxy pattern to avoid intermediary/yarn name conflicts.
     */
    private void registerSafariSpawnInfluences() {
        SafariSpawnInfluenceFix.register();
    }

    private void registerStopBattleCommand() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // /stopbattle (no args) — any player can stop their own battle
            dispatcher.register(CommandManager.literal("stopbattle")
                .executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                    String name = player.getGameProfile().getName();

                    context.getSource().getServer().getCommandManager().executeWithPrefix(
                        context.getSource().getServer().getCommandSource(),
                        "stopbattle " + name
                    );

                    player.sendMessage(
                        Text.literal("Battle stop requested.").formatted(Formatting.GREEN), false
                    );

                    LOGGER.info("[CheeseCompat] {} used /stopbattle on themselves", name);
                    return 1;
                })
            );
        });
    }

    /**
     * After all mods register commands, replace BattleTower's /stopbattle argument nodes
     * with op-restricted copies so non-ops cannot target other players.
     */
    private void restrictStopBattleArgs() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                var dispatcher = server.getCommandManager().getDispatcher();
                var stopbattleNode = dispatcher.getRoot().getChild("stopbattle");
                if (stopbattleNode == null) return;

                Field childrenField = CommandNode.class.getDeclaredField("children");
                childrenField.setAccessible(true);
                Field argumentsField = CommandNode.class.getDeclaredField("arguments");
                argumentsField.setAccessible(true);

                @SuppressWarnings("unchecked")
                Map<String, CommandNode<ServerCommandSource>> children =
                    (Map<String, CommandNode<ServerCommandSource>>) childrenField.get(stopbattleNode);
                @SuppressWarnings("unchecked")
                Map<String, ArgumentCommandNode<ServerCommandSource, ?>> arguments =
                    (Map<String, ArgumentCommandNode<ServerCommandSource, ?>>) argumentsField.get(stopbattleNode);

                boolean modified = false;
                for (var entry : new ArrayList<>(children.entrySet())) {
                    if (entry.getValue() instanceof ArgumentCommandNode<ServerCommandSource, ?> argNode) {
                        var restricted = restrictArgNode(argNode);
                        children.put(entry.getKey(), restricted);
                        arguments.put(entry.getKey(), restricted);
                        modified = true;
                    }
                }

                if (modified) {
                    LOGGER.info("[CheeseCompat] Restricted /stopbattle <player> to operators");
                }
            } catch (Exception e) {
                LOGGER.warn("[CheeseCompat] Could not restrict /stopbattle <player>: {}", e.getMessage());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> ArgumentCommandNode<ServerCommandSource, T> restrictArgNode(
            ArgumentCommandNode<ServerCommandSource, T> node) {
        var builder = RequiredArgumentBuilder.<ServerCommandSource, T>argument(node.getName(), node.getType())
            .requires(src -> src.hasPermissionLevel(2));
        if (node.getCommand() != null) builder.executes(node.getCommand());
        if (node.getCustomSuggestions() != null) builder.suggests(node.getCustomSuggestions());
        var newNode = builder.build();
        for (var child : node.getChildren()) {
            newNode.addChild(child);
        }
        return newNode;
    }
}
