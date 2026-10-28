package com.cheeseguard.mixin;

import com.cheeseguard.CheeseGuardMod;
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

import java.util.Set;

/**
 * Blocks all commands for imprisoned players except a small read-only whitelist.
 * Operators are NOT exempted on purpose — an imprisoned op should still be confined.
 */
@Mixin(CommandManager.class)
public class PrisonCommandMixin {

    private static final Set<String> WHITELIST = Set.of("help");

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private void cheeseguard$blockPrisonerCommands(ParseResults<ServerCommandSource> parseResults,
                                                    String command, CallbackInfo ci) {
        try {
            ServerCommandSource source = parseResults.getContext().getSource();
            ServerPlayerEntity player = source.getPlayer();
            if (player == null) return;

            var mod = CheeseGuardMod.getInstance();
            if (mod == null) return;
            var pm = mod.getPrisonManager();
            if (pm == null) return;
            if (!pm.isImprisoned(player.getUuid().toString())) return;

            String trimmed = command.trim();
            int sp = trimmed.indexOf(' ');
            String head = (sp > 0 ? trimmed.substring(0, sp) : trimmed).toLowerCase();
            if (WHITELIST.contains(head)) return;

            player.sendMessage(Text.translatable("cheeseguard.prison.command_blocked")
                .formatted(Formatting.RED), false);
            ci.cancel();
        } catch (Exception ignored) {}
    }
}
