package com.example.autocoinpurse.mixin;

import com.example.autocoinpurse.AutoCoinPurseClient;
import com.example.autocoinpurse.client.AutoCollectButton;
import com.example.autocoinpurse.network.ToggleAutoCollectPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screen.ingame.AbstractInventoryScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin
        extends AbstractInventoryScreen<PlayerScreenHandler> {

    public InventoryScreenMixin(PlayerScreenHandler handler,
                                PlayerInventory inventory,
                                Text title) {
        super(handler, inventory, title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void autocoinpurse$injectToggleButton(CallbackInfo ci) {
        // GUI inventaire : 176x166 px, centré sur l'écran
        int guiLeft = (this.width  - 176) / 2;
        int guiTop  = (this.height - 166) / 2;

        // Zone personnage 3D : x+8 à x+57 (largeur 49), y+8 à y+76 (hauteur 68)
        // Centre horizontal : guiLeft + 8 + (49/2) = guiLeft + 32
        // On place le bouton (12x12) centré sous le personnage, bord inférieur à y+76
        int s = AutoCollectButton.SIZE;
        int btnX = guiLeft + 32 - s / 2 + 1;
        int btnY = guiTop  + 76 - s - 55 - 27 + 27;
        
        AutoCollectButton button = new AutoCollectButton(btnX, btnY, () ->
            ClientPlayNetworking.send(
                    new ToggleAutoCollectPayload(AutoCoinPurseClient.autoCollectEnabled)
            )
        );

        addDrawableChild(button);
    }
}
