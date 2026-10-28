package com.cheesecompat.mixin;

import battle.tower.data.ShopItem;
import battle.tower.platform.fabric.NetworkHelperImpl;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fixes pour la boutique BP de la Battle Tower :
 *
 * 1. Exploit overflow quantité : un client malveillant peut envoyer
 *    quantity=MAX_INT → bpCost*quantity déborde en négatif → BP infinis et
 *    potentiel gel serveur. On borne la quantité à [1, 64].
 *
 * 2. Item id invalide → BP perdus silencieusement (bug observé : "scroll of
 *    waters" acheté, jamais reçu). {@code handlePurchase} débite les BP AVANT
 *    de résoudre l'item, et {@code Registries.ITEM.get(id)} sur un id non
 *    enregistré retourne {@code minecraft:air} (≠ null) dans une DefaultedRegistry
 *    → le stack vide s'insère "avec succès", aucune exception, pas de rollback.
 *    Résultat : BP débités, rien livré, aucun message. On valide ici, AVANT tout
 *    débit (l'injection HEAD annule {@code handlePurchase} en entier), que l'item
 *    de la boutique est bien enregistré ; sinon achat annulé + message, zéro BP perdu.
 *
 *    ATTENTION : le paramètre {@code itemId} de {@code handlePurchase} est le
 *    SLUG du {@code ShopItem} ({@code ShopItem.id()}), PAS un id d'item Minecraft.
 *    Le vrai id MC est {@code ShopItem.itemId()}. On résout donc d'abord le
 *    ShopItem par son slug, puis on valide SON {@code itemId()} — sans quoi on
 *    annulerait tous les achats légitimes. Les "command items" (récompense =
 *    commande, pas d'item MC) sont exemptés de la validation registre.
 */
@Mixin(value = NetworkHelperImpl.class, remap = false)
public class NetworkHelperImplMixin {

    @Inject(method = "handlePurchase", at = @At("HEAD"), cancellable = true)
    private static void cheesecompat$validatePurchase(ServerPlayerEntity player, String itemId,
                                                      int quantity, CallbackInfo ci) {
        // 1. Borne anti-overflow (le slug est ignoré ici).
        if (quantity <= 0 || quantity > 64) {
            ci.cancel();
            return;
        }

        try {
            // 2. Résoudre le ShopItem depuis son slug (le param "itemId").
            ShopItem shopItem = ShopItem.getById(itemId);
            if (shopItem == null) {
                // Slug inconnu : aucun achat légitime ne correspond → annuler sans débit.
                notifyAndCancel(player, ci, "Article inconnu (" + itemId + ")");
                return;
            }
            // 2b. Les command items ne livrent pas un item MC → ne pas valider le registre.
            if (shopItem.isCommandItem()) {
                return;
            }
            // 2c. Valider l'id d'item MC RÉEL (ShopItem.itemId()). Réflexion pour éviter
            //     le conflit de type intermediary (class_2960) vs yarn (Identifier) à la
            //     compilation : le jar Battle Tower est compileOnly non remappé.
            Object mcItemId = ShopItem.class.getMethod("itemId").invoke(shopItem);
            if (mcItemId == null) {
                notifyAndCancel(player, ci, "Article mal configuré (" + itemId + ")");
                return;
            }
            Identifier id = Identifier.tryParse(mcItemId.toString());
            if (id == null || !Registries.ITEM.containsId(id)) {
                notifyAndCancel(player, ci, "Article indisponible (item inexistant : " + mcItemId + ")");
            }
        } catch (Throwable t) {
            // Fail-open : si la validation elle-même échoue, on NE bloque PAS l'achat
            // (on ne casse pas la boutique). Le pire cas redevient le bug d'origine,
            // jamais une régression sur les achats légitimes.
        }
    }

    private static void notifyAndCancel(ServerPlayerEntity player, CallbackInfo ci, String reason) {
        if (player != null) {
            player.sendMessage(Text.literal("[Battle Tower] " + reason
                    + ". Achat annulé, aucun BP débité.").formatted(Formatting.RED), false);
        }
        ci.cancel();
    }
}
