package com.cheeseguard.logic;

import net.minecraft.server.MinecraftServer;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.*;

public class ServerManager {

    public enum Mode {
        RESTART("Redemarrage", Formatting.AQUA),
        STOP("Arret", Formatting.GOLD),
        MAINTENANCE("Maintenance", Formatting.LIGHT_PURPLE);

        public final String label;
        public final Formatting color;
        Mode(String label, Formatting color) { this.label = label; this.color = color; }

        public static Mode fromId(String id) {
            try { return valueOf(id.toUpperCase()); } catch (Exception e) { return RESTART; }
        }
    }

    private Timer currentTimer;
    private Mode activeMode;
    private int activeMinutes;
    private long activeStartTime;

    public boolean isActive() { return currentTimer != null; }
    public Mode getActiveMode() { return activeMode; }
    public int getActiveMinutes() { return activeMinutes; }

    public int getRemainingSeconds() {
        if (currentTimer == null) return 0;
        long elapsed = (System.currentTimeMillis() - activeStartTime) / 1000;
        return Math.max(0, (int)(activeMinutes * 60 - elapsed));
    }

    public void cancel() {
        if (currentTimer != null) { currentTimer.cancel(); currentTimer = null; }
        activeMode = null; activeMinutes = 0;
    }

    public void schedule(MinecraftServer server, Mode mode, int minutes) {
        cancel();
        activeMode = mode;
        activeMinutes = minutes;
        activeStartTime = System.currentTimeMillis();
        currentTimer = new Timer("cg-server-" + mode.name().toLowerCase());

        broadcast(server, ">> " + mode.label + " du serveur dans " + minutes + " minute" + (minutes > 1 ? "s" : "") + " !", mode.color, true);

        // Discord webhook notification
        switch (mode) {
            case RESTART -> DiscordWebhook.serverRestart(minutes);
            case STOP -> DiscordWebhook.serverStop(minutes);
            case MAINTENANCE -> DiscordWebhook.serverMaintenance(minutes);
        }

        // Minute warnings
        for (int i = 1; i < minutes; i++) {
            final int remaining = minutes - i;
            currentTimer.schedule(new TimerTask() {
                @Override public void run() {
                    broadcast(server, ">> " + mode.label + " dans " + remaining + " minute" + (remaining > 1 ? "s" : "") + " !", mode.color, true);
                }
            }, i * 60_000L);
        }

        // Last 10 seconds countdown
        for (int s = 10; s > 0; s--) {
            final int sec = s;
            long delay = (minutes * 60L - sec) * 1000L;
            if (delay < 0) continue;
            currentTimer.schedule(new TimerTask() {
                @Override public void run() {
                    Formatting c = sec <= 5 ? Formatting.RED : Formatting.YELLOW;
                    broadcast(server, ">> " + mode.label + " dans " + sec + " seconde" + (sec > 1 ? "s" : "") + "...", c, true);
                }
            }, delay);
        }

        // Execute
        currentTimer.schedule(new TimerTask() {
            @Override public void run() {
                broadcast(server, ">> " + mode.label + " en cours...", Formatting.RED, true);
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}

                // Save all worlds BEFORE stopping — wait for completion (max 30s)
                java.util.concurrent.CountDownLatch saveLatch = new java.util.concurrent.CountDownLatch(1);
                server.execute(() -> {
                    try {
                        server.save(false, true, true);
                        broadcast(server, ">> Sauvegarde terminee.", Formatting.GREEN, false);
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        saveLatch.countDown();
                    }
                });
                try { saveLatch.await(30, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException ignored) {}

                if (mode == Mode.RESTART) {
                    try { restartProcess(); } catch (Exception e) { e.printStackTrace(); }
                }
                server.execute(() -> server.stop(false));
                currentTimer = null;
            }
        }, minutes * 60_000L);
    }

    private void broadcast(MinecraftServer server, String message, Formatting color, boolean bold) {
        MutableText text = Text.literal(message).formatted(color);
        if (bold) text = text.formatted(Formatting.BOLD);
        final MutableText ft = text;
        server.execute(() -> server.getPlayerManager().broadcast(ft, false));
    }

    private void restartProcess() throws Exception {
        String javaBin = ProcessHandle.current().info().command()
                .orElse(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();

        String jarPath = null;
        List<String> processArgs = ProcessHandle.current().info().arguments().map(List::of).orElse(List.of());
        for (int i = 0; i < processArgs.size() - 1; i++) {
            if (processArgs.get(i).equals("-jar")) { jarPath = processArgs.get(i + 1); break; }
        }
        if (jarPath == null) {
            File workDir = new File(System.getProperty("user.dir"));
            File[] files = workDir.listFiles();
            if (files != null) for (File f : files) {
                String name = f.getName().toLowerCase();
                if (name.startsWith("fabric-server-launch") && name.endsWith(".jar")) { jarPath = f.getName(); break; }
                if (name.startsWith("server") && name.endsWith(".jar")) jarPath = f.getName();
            }
        }
        if (jarPath == null) throw new Exception("Cannot find server jar for restart");

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin); cmd.addAll(jvmArgs); cmd.add("-jar"); cmd.add(jarPath); cmd.add("nogui");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(System.getProperty("user.dir")));
        pb.inheritIO();
        pb.start();
    }
}
