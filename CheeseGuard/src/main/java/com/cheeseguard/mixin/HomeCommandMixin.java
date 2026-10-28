package com.cheeseguard.mixin;

import com.cheeseguard.CheeseGuardMod;
import com.cheeseguard.data.DimensionConfig;
import com.mojang.brigadier.ParseResults;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CommandManager.class)
public class HomeCommandMixin {

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private void interceptHomeCommand(ParseResults<ServerCommandSource> parseResults, String command, CallbackInfo ci) {
        try {
            ServerCommandSource source = parseResults.getContext().getSource();
            if (source.getPlayer() == null) return;
            ServerPlayerEntity player = source.getPlayer();

            // Only intercept /home commands (home set, home tp, home delete, etc.)
            String cmd = command.trim().toLowerCase();
            if (!cmd.startsWith("home")) return;

            // Ops bypass the restriction
            if (player.hasPermissionLevel(2)) return;

            String worldId = player.getServerWorld().getRegistryKey().getValue().toString();
            var dimManager = CheeseGuardMod.getInstance().getDimensionManager();
            if (dimManager == null) return;

            DimensionConfig config = dimManager.getConfigByWorldId(worldId);
            if (config != null && !config.isAllowHome()) {
                player.sendMessage(Text.translatable("cheeseguard.home.blocked").formatted(Formatting.RED), false);
                ci.cancel();
            }
        } catch (Exception ignored) {
            // Don't crash if CheeseGuard isn't ready yet
        }
    }
}
