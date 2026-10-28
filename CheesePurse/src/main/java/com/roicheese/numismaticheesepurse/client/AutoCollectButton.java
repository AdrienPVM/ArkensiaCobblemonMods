package com.roicheese.numismaticheesepurse.client;

import com.roicheese.numismaticheesepurse.NumismaticCheesePurseClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

@Environment(EnvType.CLIENT)
public class AutoCollectButton extends ButtonWidget {

    public static final Identifier TEX_ON =
            Identifier.of("numismatic-cheesepurse", "textures/gui/auto_collect_on.png");
    public static final Identifier TEX_OFF =
            Identifier.of("numismatic-cheesepurse", "textures/gui/auto_collect_off.png");

    public static final int SIZE = 12;

    public AutoCollectButton(int x, int y, Runnable onToggle) {
        super(x, y, SIZE, SIZE, Text.empty(),
                btn -> {
                    NumismaticCheesePurseClient.autoCollectEnabled = !NumismaticCheesePurseClient.autoCollectEnabled;
                    refreshTooltipOn(btn);
                    onToggle.run();
                },
                btn -> Text.translatable(
                    NumismaticCheesePurseClient.autoCollectEnabled
                        ? "numismaticheesepurse.button.tooltip.enabled"
                        : "numismaticheesepurse.button.tooltip.disabled"
                )
        );
        refreshTooltipOn(this);
    }

    private static void refreshTooltipOn(ButtonWidget btn) {
        if (NumismaticCheesePurseClient.autoCollectEnabled) {
            btn.setTooltip(Tooltip.of(Text.translatable("numismaticheesepurse.button.tooltip.enabled")));
        } else {
            btn.setTooltip(Tooltip.of(Text.translatable("numismaticheesepurse.button.tooltip.disabled")));
        }
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        Identifier tex = NumismaticCheesePurseClient.autoCollectEnabled ? TEX_ON : TEX_OFF;

        // Pas de fond — background transparent, juste la texture PNG
        context.drawTexture(tex, getX(), getY(), 0, 0, SIZE, SIZE, SIZE, SIZE);
    }
}
