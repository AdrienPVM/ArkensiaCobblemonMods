package com.cheeseguard.data;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for a zone sale listing.
 * Stored as part of a Region in regions.json.
 */
public class SaleConfig {

    private boolean enabled;
    private long price;
    private String currency;        // "NUMISMATIC", "ITEMS", "FROMAGE"
    private String itemId;          // For ITEMS currency (e.g., "minecraft:diamond")
    private int itemCount;          // For ITEMS currency
    private List<String> signText = new ArrayList<>();  // Up to 4 lines (hologram text)
    private String signLine1;       // Sign custom line 2 (default: zone name)
    private String signLine2;       // Sign custom line 3 (default: empty)
    private String signType;        // Wood type: oak, spruce, birch, jungle, acacia, dark_oak, mangrove, cherry, bamboo, crimson, warped
    private boolean sold;
    private String buyerUuid;
    private String buyerName;
    private String hologramUuid;    // UUID of spawned TextDisplayEntity

    public SaleConfig() {}

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getPrice() { return price; }
    public void setPrice(long price) { this.price = Math.max(0, price); }

    public String getCurrency() { return currency != null ? currency : "NUMISMATIC"; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getItemId() { return itemId; }
    public void setItemId(String itemId) { this.itemId = itemId; }

    public int getItemCount() { return itemCount; }
    public void setItemCount(int itemCount) { this.itemCount = Math.max(1, itemCount); }

    public List<String> getSignText() { return signText != null ? signText : (signText = new ArrayList<>()); }
    public void setSignText(List<String> signText) { this.signText = signText != null ? signText : new ArrayList<>(); }

    public String getSignLine1() { return signLine1; }
    public void setSignLine1(String signLine1) { this.signLine1 = signLine1; }

    public String getSignLine2() { return signLine2; }
    public void setSignLine2(String signLine2) { this.signLine2 = signLine2; }

    public String getSignType() { return signType != null ? signType : "oak"; }
    public void setSignType(String signType) { this.signType = signType; }

    public boolean isSold() { return sold; }
    public void setSold(boolean sold) { this.sold = sold; }

    public String getBuyerUuid() { return buyerUuid; }
    public void setBuyerUuid(String buyerUuid) { this.buyerUuid = buyerUuid; }

    public String getBuyerName() { return buyerName; }
    public void setBuyerName(String buyerName) { this.buyerName = buyerName; }

    public String getHologramUuid() { return hologramUuid; }
    public void setHologramUuid(String hologramUuid) { this.hologramUuid = hologramUuid; }

    public SaleConfig copy() {
        SaleConfig c = new SaleConfig();
        c.enabled = this.enabled;
        c.price = this.price;
        c.currency = this.currency;
        c.itemId = this.itemId;
        c.itemCount = this.itemCount;
        c.signText = new ArrayList<>(this.getSignText());
        c.signLine1 = this.signLine1;
        c.signLine2 = this.signLine2;
        c.signType = this.signType;
        c.sold = this.sold;
        c.buyerUuid = this.buyerUuid;
        c.buyerName = this.buyerName;
        c.hologramUuid = this.hologramUuid;
        return c;
    }
}
