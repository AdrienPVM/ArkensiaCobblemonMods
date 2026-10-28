package com.example.scheduledrestart;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.ServerCommandSource;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class ScheduledRestart implements ModInitializer {
    private static Timer currentTimer = null;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(CommandManager.literal("schedulerestart")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.argument("minutes", IntegerArgumentType.integer(1, 60))
                    .executes(context -> executeRestart(context, false))
                    .then(CommandManager.argument("mode", StringArgumentType.word())
                        .executes(context -> {
                            String mode = StringArgumentType.getString(context, "mode");
                            return executeRestart(context, mode.equalsIgnoreCase("stop"));
                        })
                    )
                )
            )
        );
    }

    private int executeRestart(CommandContext<ServerCommandSource> context, boolean isStop) {
        int minutes = IntegerArgumentType.getInteger(context, "minutes");
        MinecraftServer server = context.getSource().getServer();

        if (currentTimer != null) {
            currentTimer.cancel();
        }

        currentTimer = new Timer();
        String actionCap = isStop ? "Arrêt" : "Redémarrage";
        // Orange pour arrêt, bleu ciel pour redémarrage
        Formatting actionColor = isStop ? Formatting.GOLD : Formatting.AQUA;

        broadcastColored(server, "⚠ " + actionCap + " du serveur dans " + minutes + " minute" + (minutes > 1 ? "s" : "") + " !", actionColor, true);

        for (int i = 1; i < minutes; i++) {
            final int remaining = minutes - i;
            currentTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    broadcastColored(server, "⚠ " + actionCap + " dans " + remaining + " minute" + (remaining > 1 ? "s" : "") + " !", actionColor, true);
                }
            }, i * 60 * 1000L);
        }

        for (int i = 10; i > 0; i--) {
            final int seconds = i;
            long delay = (minutes * 60 - seconds) * 1000L;
            // Rouge si <= 5 secondes, jaune sinon
            currentTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    Formatting countColor = seconds <= 5 ? Formatting.RED : Formatting.YELLOW;
                    broadcastColored(server, "⏱ " + actionCap + " dans " + seconds + " seconde" + (seconds > 1 ? "s" : "") + "...", countColor, true);
                }
            }, delay);
        }

        currentTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                broadcastColored(server, "🔄 " + actionCap + " en cours...", Formatting.RED, true);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                if (!isStop) {
                    try {
                        restartServer();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                server.execute(() -> server.stop(false));
            }
        }, minutes * 60 * 1000L);

        // Message de confirmation en vert pour l'OP qui a tapé la commande
        MutableText confirm = Text.literal("✅ " + actionCap + " planifié dans " + minutes + " minute" + (minutes > 1 ? "s" : ""))
                .formatted(Formatting.GREEN);
        context.getSource().sendFeedback(() -> confirm, true);
        return 1;
    }

    private void broadcastColored(MinecraftServer server, String message, Formatting color, boolean bold) {
        MutableText text = Text.literal(message).formatted(color);
        if (bold) text = text.formatted(Formatting.BOLD);
        final MutableText finalText = text;
        server.execute(() ->
            server.getPlayerManager().broadcast(finalText, false)
        );
    }

    private void restartServer() throws Exception {
        String javaBin = ProcessHandle.current()
                .info()
                .command()
                .orElse(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");

        List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();

        String jarPath = null;
        List<String> processArgs = ProcessHandle.current().info().arguments()
                .map(List::of)
                .orElse(List.of());

        for (int i = 0; i < processArgs.size() - 1; i++) {
            if (processArgs.get(i).equals("-jar")) {
                jarPath = processArgs.get(i + 1);
                break;
            }
        }

        if (jarPath == null) {
            File workDir = new File(System.getProperty("user.dir"));
            for (File f : workDir.listFiles()) {
                String name = f.getName().toLowerCase();
                if (name.startsWith("fabric-server-launch") && name.endsWith(".jar")) {
                    jarPath = f.getName();
                    break;
                }
                if (name.startsWith("server") && name.endsWith(".jar")) {
                    jarPath = f.getName();
                }
            }
        }

        if (jarPath == null) {
            throw new Exception("Impossible de trouver le jar du serveur pour le redémarrage !");
        }

        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.addAll(jvmArgs);
        command.add("-jar");
        command.add(jarPath);
        command.add("nogui");

        System.out.println("[ScheduledRestart] Relancement : " + String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(System.getProperty("user.dir")));
        pb.inheritIO();
        pb.start();
    }
}
