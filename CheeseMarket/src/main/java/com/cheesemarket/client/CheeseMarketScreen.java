package com.cheesemarket.client;

import com.cheesemarket.network.ShopPayloads.*;
import com.google.gson.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.*;
import java.util.function.Consumer;

/**
 * Full-screen admin dashboard for CheeseMarket — v3.
 *
 * Admin fixes:
 *  1. Shop edit: bg/border/accent color pickers
 *  2. Display modes: redesigned UI with descriptions + mini-previews
 *  3. Sets tab: fixed text positioning
 *  4. Preview tab: renders actual item cards
 *  5. Shop list: added "▶" button to open shop as player
 *  6. Item grid: smaller cells (60×68), translated item names
 *  7. Auto-icon: first output = default; "auto" clears custom; preview shows correct names
 */
public class CheeseMarketScreen extends Screen {

    // ── Colors ────────────────────────────────────────────────────────────────
    private static final int C_BG           = 0xFF1A1A2E;
    private static final int C_BG2          = 0xFF16213E;
    private static final int C_PANEL        = 0xFF0F3460;
    private static final int C_PANEL_LIGHT  = 0xFF1A4A7A;
    private static final int C_HEADER       = 0xFF0D2137;
    private static final int C_BORDER       = 0xFF2A5A8A;
    private static final int C_TAB_ACTIVE   = 0xFF0F3460;
    private static final int C_TAB_HOVER    = 0xFF1A4A7A;
    private static final int C_TAB_INACTIVE = 0xFF0A1A30;
    private static final int C_TEXT         = 0xFFE8EAF0;
    private static final int C_TEXT_DIM     = 0xFF8899AA;
    private static final int C_TEXT_ACCENT  = 0xFF4AC8EA;
    private static final int C_BTN          = 0xFF1E3A5F;
    private static final int C_BTN_H        = 0xFF2A5A8F;
    private static final int C_BTN_GREEN    = 0xFF1A5C2A;
    private static final int C_BTN_GREEN_H  = 0xFF2A8C3A;
    private static final int C_BTN_RED      = 0xFF5C1A1A;
    private static final int C_BTN_RED_H    = 0xFF8C2A2A;
    private static final int C_BTN_ORANGE   = 0xFF5C3A1A;
    private static final int C_BTN_ORANGE_H = 0xFF8C5A2A;
    private static final int C_BTN_PURPLE   = 0xFF3A1A5C;
    private static final int C_BTN_PURPLE_H = 0xFF5A2A8C;
    private static final int C_INPUT_BG     = 0xFF0A1525;
    private static final int C_INPUT_BORDER = 0xFF2A4A6A;
    private static final int C_SEP          = 0xFF2A4A6A;
    private static final int C_ROW_A        = 0x10FFFFFF;
    private static final int C_ROW_B        = 0x08FFFFFF;
    private static final int C_ROW_HOVER    = 0x20FFFFFF;
    private static final int C_ENABLED      = 0xFF1A5C2A;
    private static final int C_DISABLED     = 0xFF5C1A1A;

    // ── Layout ────────────────────────────────────────────────────────────────
    private static final int HEADER_H    = 34;
    private static final int TAB_H       = 26;
    private static final int FOOTER_H    = 30;
    // Smaller item grid (60×68) — fits more items per row
    private static final int GRID_W      = 62;
    private static final int GRID_H      = 72;
    private static final int GRID_PAD    = 5;

    // ── State ─────────────────────────────────────────────────────────────────
    private String     viewMode    = "SHOP_LIST";
    private JsonObject data        = new JsonObject();
    private String     currentTab  = "config";
    private boolean    dirty       = false;

    private final Map<String,Integer> scrollY    = new HashMap<>();
    private final Map<String,Integer> maxScrollY = new HashMap<>();
    private final Map<String,String>  iv         = new HashMap<>(); // inputValues
    private final List<Widget>        widgets     = new ArrayList<>();

    private String pickerQuery   = "";
    private String pickerContext = "";
    private com.google.gson.JsonArray pickerAllItems = null; // full item list cached client-side

    private String ctxItemId = null;
    private int    ctxX, ctxY;

    private String dragItemId    = null;
    private int    dragMouseX    = 0, dragMouseY = 0;
    private int    dragSourceIdx = -1, dragTargetIdx = -1;

    private int contentY, contentH;
    private String tooltip;

    // ── Constructor ───────────────────────────────────────────────────────────
    public CheeseMarketScreen() { super(Text.translatable("cheesemarket.screen.admin")); }

    @Override public boolean shouldPause()      { return false; }
    @Override public boolean shouldCloseOnEsc() { return true;  }

    // ── Server data ───────────────────────────────────────────────────────────
    public void receiveSync(String view, JsonObject newData) {
        if ("ITEM_PICKER".equals(view) && newData.has("allItems") && newData.get("allItems").getAsBoolean()) {
            // Cache ALL items for client-side filtering
            pickerAllItems = newData.has("results") ? newData.getAsJsonArray("results") : new com.google.gson.JsonArray();
            pickerContext  = newData.has("context") ? newData.get("context").getAsString() : pickerContext;
            viewMode = view; data = newData; dirty = false;
            return;
        }
        viewMode = view; data = newData; dirty = false;
        ctxItemId = null; dragItemId = null;
        if ("SET_EDIT".equals(view)) currentTab = "items";
        syncInputValues();
    }

    private void syncInputValues() {
        iv.clear();
        switch (viewMode) {
            case "SHOP_EDIT" -> {
                pi("name",               gs("name",""));
                pi("description",        gs("description",""));
                pi("displayMode",        gs("displayMode","TABS"));
                pi("showWelcomeMessage", String.valueOf(gb("showWelcomeMessage",false)));
                pi("welcomeMessage",     gs("welcomeMessage",""));
                pi("titleColor",         gs("titleColor","gold"));
                pi("bgColor",            gs("bgColor","1A1A2E"));
                pi("borderColor",        gs("borderColor","2A5A8A"));
                pi("accentColor",        gs("accentColor","4AC8EA"));
                pi("btnBuyColor",        gs("btnBuyColor","1A5C2A"));
                pi("btnBuyTextColor",    gs("btnBuyTextColor","FFFFFF"));
                pi("btnCloseColor",      gs("btnCloseColor","1E3A5F"));
                pi("btnCloseTextColor",  gs("btnCloseTextColor","CCCCCC"));
            }
            case "SET_EDIT" -> {
                pi("name", gs("name",""));
                JsonObject r = data.has("rotation")?data.getAsJsonObject("rotation"):new JsonObject();
                pi("rotMode",       r.has("mode")           ? r.get("mode").getAsString()          : "ALWAYS");
                pi("scheduleStart", r.has("scheduleStart")  ? r.get("scheduleStart").getAsString() : "08:00");
                pi("scheduleEnd",   r.has("scheduleEnd")    ? r.get("scheduleEnd").getAsString()   : "20:00");
                pi("manualActive",  String.valueOf(r.has("manualActive")&&r.get("manualActive").getAsBoolean()));
            }
            case "ITEM_EDIT" -> {
                pi("displayItem",       gs("displayItem",""));
                pi("displayName",       gs("displayName",""));
                pi("enabled",           String.valueOf(gb("enabled",true)));
                JsonObject s = data.has("stock")?data.getAsJsonObject("stock"):new JsonObject();
                pi("stockMode",         s.has("mode")               ? s.get("mode").getAsString()               : "UNLIMITED");
                pi("globalStock",       s.has("globalStock")        ? s.get("globalStock").getAsString()        : "100");
                pi("perPlayerLimit",    s.has("perPlayerLimit")     ? s.get("perPlayerLimit").getAsString()     : "1");
                pi("resetType",         s.has("resetType")          ? s.get("resetType").getAsString()          : "DAILY");
                pi("resetIntervalSecs", s.has("resetIntervalSeconds")? s.get("resetIntervalSeconds").getAsString(): "86400");
            }
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────
    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        widgets.clear(); tooltip = null;
        int W=this.width, H=this.height;
        contentY = HEADER_H+TAB_H; contentH = H-contentY-FOOTER_H;
        ctx.fill(0,0,W,H,C_BG);

        renderFooter(ctx,mx,my,W,H);
        renderHeader(ctx,mx,my,W);
        renderTabs(ctx,mx,my,W);

        int scroll = scrollY.getOrDefault(scrollKey(),0);
        int startY = contentY-scroll;

        ctx.enableScissor(0,contentY,W,contentY+contentH);
        switch (viewMode) {
            case "SHOP_LIST"   -> renderShopList(ctx,mx,my,W,startY);
            case "SHOP_EDIT"   -> { if("config".equals(currentTab)) renderShopConfig(ctx,mx,my,W,startY); else if("sets".equals(currentTab)) renderSetsEmbed(ctx,mx,my,W,startY); else renderShopPreview(ctx,mx,my,W,startY); }
            case "SET_LIST"    -> renderSetsEmbed(ctx,mx,my,W,startY);
            case "SET_EDIT"    -> { if("config".equals(currentTab)) renderSetConfig(ctx,mx,my,W,startY); else renderItemGrid(ctx,mx,my,W,startY); }
            case "ITEM_LIST"   -> renderItemGrid(ctx,mx,my,W,startY);
            case "ITEM_EDIT"   -> renderItemEditor(ctx,mx,my,W,startY);
            case "ITEM_PICKER" -> renderItemPicker(ctx,mx,my,W,startY);
        }
        ctx.disableScissor();

        if (dragItemId!=null) renderDragGhost(ctx,mx,my);
        if (ctxItemId!=null)  renderContextMenu(ctx,mx,my);
        if (tooltip!=null)    renderTooltipBox(ctx,tooltip,mx+10,my-14);
    }

    // ── Header & Tabs ─────────────────────────────────────────────────────────
    private void renderHeader(DrawContext ctx,int mx,int my,int W){
        ctx.fill(0,0,W,HEADER_H,C_HEADER); ctx.fill(0,HEADER_H-1,W,HEADER_H,C_BORDER);
        dt(ctx,"🏪 "+breadcrumb(),10,9,C_TEXT_ACCENT);
        if(dirty) dt(ctx,"● non sauvegardé",W-tw("● non sauvegardé")-10,12,0xFFFF8844);
    }
    private String breadcrumb(){ return switch(viewMode){
        case"SHOP_LIST"->"Tous les shops"; case"SHOP_EDIT"->"Shops › "+gs("name",gs("id","?"));
        case"SET_LIST"->gs("shopName","?")+" › Sets"; case"SET_EDIT"->gs("shopName","?")+" › "+gs("name","?");
        case"ITEM_LIST"->gs("setName","?")+" › Items"; case"ITEM_EDIT"->gs("setName","?")+" › "+gs("displayName",gs("displayItem","Item"));
        case"ITEM_PICKER"->"Sélecteur d'item"; default->viewMode; }; }

    private void renderTabs(DrawContext ctx,int mx,int my,int W){
        int y=HEADER_H; ctx.fill(0,y,W,y+TAB_H,C_BG2); ctx.fill(0,y+TAB_H-1,W,y+TAB_H,C_BORDER);
        String[] ids,labels;
        switch(viewMode){
            case"SHOP_EDIT"->{ ids=new String[]{"config","sets"};            labels=new String[]{"⚙ Config","📦 Sets"}; }
            case"SET_EDIT" ->{ ids=new String[]{"config","items"};           labels=new String[]{"⚙ Config","🛒 Items"}; }
            default->{ dt(ctx,"CheeseMarket Admin",10,y+7,C_TEXT_DIM); return; }
        }
        int x=6;
        for(int i=0;i<ids.length;i++){
            boolean active=currentTab.equals(ids[i]); int tw=tw(labels[i])+18;
            boolean hov=hov(mx,my,x,y+2,tw,TAB_H-2);
            ctx.fill(x,y+2,x+tw,y+TAB_H,active?C_TAB_ACTIVE:hov?C_TAB_HOVER:C_TAB_INACTIVE);
            if(active) ctx.fill(x,y+TAB_H-2,x+tw,y+TAB_H,C_TEXT_ACCENT);
            dt(ctx,labels[i],x+9,y+7,active?C_TEXT_ACCENT:C_TEXT_DIM);
            addZone(x,y+2,tw,TAB_H-2,"_tab:"+ids[i]); x+=tw+3;
        }
    }

    // ── Footer ────────────────────────────────────────────────────────────────
    private void renderFooter(DrawContext ctx,int mx,int my,int W,int H){
        int y=H-FOOTER_H; ctx.fill(0,y,W,H,C_HEADER); ctx.fill(0,y,W,y+1,C_BORDER);
        String shopId=gs("shopId",gs("id","")), setId=gs("setId",gs("id",""));
        switch(viewMode){
            case"SHOP_LIST" -> btn(ctx,mx,my,6,y+5,88,20,"＋ Nouveau shop",C_BTN_GREEN,C_BTN_GREEN_H,"_new_shop");
            case"SHOP_EDIT" -> { btn(ctx,mx,my,6,y+5,65,20,"← Retour",C_BTN,C_BTN_H,"open_shop_list"); btn(ctx,mx,my,76,y+5,75,20,"💾 Sauver",C_BTN_GREEN,C_BTN_GREEN_H,"_save_shop"); if(dirty) btn(ctx,mx,my,156,y+5,75,20,"↩ Annuler",C_BTN_ORANGE,C_BTN_ORANGE_H,"_reload_shop"); btn(ctx,mx,my,W-84,y+5,78,20,"🗑 Supprimer",C_BTN_RED,C_BTN_RED_H,"_delete_shop"); }
            case"SET_EDIT"  -> { btn(ctx,mx,my,6,y+5,65,20,"← Shop",C_BTN,C_BTN_H,"open_shop:"+shopId); btn(ctx,mx,my,76,y+5,75,20,"💾 Sauver",C_BTN_GREEN,C_BTN_GREEN_H,"_save_set"); if(dirty) btn(ctx,mx,my,156,y+5,75,20,"↩ Annuler",C_BTN_ORANGE,C_BTN_ORANGE_H,"_reload_set"); if("items".equals(currentTab)) btn(ctx,mx,my,W-184,y+5,90,20,"＋ Nouvel item",C_BTN_GREEN,C_BTN_GREEN_H,"item_create:"+shopId+":"+setId); btn(ctx,mx,my,W-84,y+5,78,20,"🗑 Set",C_BTN_RED,C_BTN_RED_H,"_delete_set"); }
            case"ITEM_EDIT" -> { btn(ctx,mx,my,6,y+5,65,20,"← Set",C_BTN,C_BTN_H,"open_set:"+shopId+":"+setId); btn(ctx,mx,my,76,y+5,75,20,"💾 Sauver",C_BTN_GREEN,C_BTN_GREEN_H,"_save_item"); if(dirty) btn(ctx,mx,my,156,y+5,75,20,"↩ Annuler",C_BTN_ORANGE,C_BTN_ORANGE_H,"_reload_item"); btn(ctx,mx,my,W-84,y+5,78,20,"🗑 Item",C_BTN_RED,C_BTN_RED_H,"_delete_item"); }
            case"ITEM_PICKER" -> btn(ctx,mx,my,6,y+5,65,20,"← Annuler",C_BTN,C_BTN_H,"_picker_cancel");
            case"SET_LIST" -> { btn(ctx,mx,my,6,y+5,65,20,"← Shop",C_BTN,C_BTN_H,"open_shop:"+shopId); btn(ctx,mx,my,76,y+5,90,20,"＋ Nouveau set",C_BTN_GREEN,C_BTN_GREEN_H,"set_create:"+shopId); }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ── SHOP LIST — with ▶ open button ────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════════
    private void renderShopList(DrawContext ctx,int mx,int my,int W,int startY){
        int y=startY+8; y=sect(ctx,8,y,W-16,"🏪 Shops disponibles");
        JsonArray shops=data.has("shops")?data.getAsJsonArray("shops"):new JsonArray();
        if(shops.isEmpty()){ dt(ctx,"Aucun shop.",16,y+8,C_TEXT_DIM); y+=28; }
        for(int i=0;i<shops.size();i++){
            JsonObject s=shops.get(i).getAsJsonObject();
            String id=sp(s,"id","?"),name=sp(s,"name","?"),mode=sp(s,"displayMode","TABS");
            int sets=s.has("setCount")?s.get("setCount").getAsInt():0;
            boolean hov=hov(mx,my,8,y,W-110,28);
            ctx.fill(8,y,W-8,y+28,hov?C_ROW_HOVER:(i%2==0?C_ROW_A:C_ROW_B));
            ctx.fill(8,y,11,y+28,C_TEXT_ACCENT);
            dt(ctx,name,18,y+5,C_TEXT);
            dt(ctx,id,18,y+16,C_TEXT_DIM);
            // Sets + mode — properly positioned
            dt(ctx,sets+" set"+(sets!=1?"s":"")+" · "+displayModeLabel(mode),W-200,y+10,C_TEXT_DIM);
            // ▶ Open as player button
            btn(ctx,mx,my,W-106,y+4,46,20,"▶ Voir",C_BTN_PURPLE,C_BTN_PURPLE_H,"open_shop_player:"+id);
            // Edit button
            btn(ctx,mx,my,W-56,y+4,48,20,"✏ Éditer",C_BTN,C_BTN_H,"open_shop:"+id);
            ctx.fill(8,y+27,W-8,y+28,C_SEP); y+=29;
        }
        updateMaxScroll(startY,y+20);
    }

    // ── SHOP CONFIG — with custom colors ──────────────────────────────────────
    private void renderShopConfig(DrawContext ctx,int mx,int my,int W,int startY){
        int half=W/2-10,col2=W/2+5,x=8,y=startY+8;

        // LEFT column
        y=sect(ctx,x,y,half,"🏷 Identité");
        y=field(ctx,mx,my,x,y,half,"Nom du shop","name");
        y=field(ctx,mx,my,x,y,half,"Description","description");
        y+=6;
        y=sect(ctx,x,y,half,"💬 Message d'accueil");
        y=tog(ctx,mx,my,x,y,half,"Activer message","showWelcomeMessage");
        if("true".equals(iv.get("showWelcomeMessage"))) y=field(ctx,mx,my,x,y,half,"Message","welcomeMessage");
        y+=6;
        y=sect(ctx,x,y,half,"🎨 Couleur titre");
        y=colPickMC(ctx,mx,my,x,y,half,"Couleur","titleColor");
        // Preview title
        ctx.fill(x,y+4,x+half,y+24,C_PANEL);
        String ti=iv.getOrDefault("name","Shop");
        dt(ctx,ti,x+half/2-tw(ti)/2,y+9,parseMCColor(iv.getOrDefault("titleColor","gold")));
        y+=30;

        // RIGHT column
        x=col2; y=startY+8;
        y=sect(ctx,x,y,half,"🖼 Couleurs de la fenêtre");
        y=fieldHex(ctx,mx,my,x,y,half,"Fond","bgColor");
        y=fieldHex(ctx,mx,my,x,y,half,"Bordure","borderColor");
        y=fieldHex(ctx,mx,my,x,y,half,"Accent","accentColor");
        y=fieldHex(ctx,mx,my,x,y,half,"Btn Acheter (fond)","btnBuyColor");
        y=fieldHex(ctx,mx,my,x,y,half,"Btn Acheter (texte)","btnBuyTextColor");
        y=fieldHex(ctx,mx,my,x,y,half,"Btn Fermer (fond)","btnCloseColor");
        y=fieldHex(ctx,mx,my,x,y,half,"Btn Fermer (texte)","btnCloseTextColor");
        // Color preview box
        y+=4;
        int previewH=54;
        int bgC    = parseHex(iv.getOrDefault("bgColor","1A1A2E"));
        int borC   = parseHex(iv.getOrDefault("borderColor","2A5A8A"));
        int accC   = parseHex(iv.getOrDefault("accentColor","4AC8EA"));
        ctx.fill(x,y,x+half,y+previewH,bgC);
        ctx.fill(x,y,x+half,y+1,borC); ctx.fill(x,y+previewH-1,x+half,y+previewH,borC);
        ctx.fill(x,y,x+1,y+previewH,accC); ctx.fill(x+half-1,y,x+half,y+previewH,borC);
        ctx.fill(x,y,x+half,y+3,accC);
        String pt=iv.getOrDefault("name","Aperçu");
        dt(ctx,pt,x+half/2-tw(pt)/2,y+8,parseMCColor(iv.getOrDefault("titleColor","gold")));
        dt(ctx,"Aperçu des couleurs",x+half/2-tw("Aperçu des couleurs")/2,y+20,applyAlpha(C_TEXT,180));
        dt(ctx,"░░░░░░░ items ░░░░░░░",x+half/2-tw("░░░░░░░ items ░░░░░░░")/2,y+32,applyAlpha(borC,200));
        y+=previewH+6;
        // Button preview
        int buyC  =parseHex(iv.getOrDefault("btnBuyColor","1A5C2A"));
        int buyTC =parseHex(iv.getOrDefault("btnBuyTextColor","FFFFFF"));
        int closeC=parseHex(iv.getOrDefault("btnCloseColor","1E3A5F"));
        int closeTC=parseHex(iv.getOrDefault("btnCloseTextColor","CCCCCC"));
        int pbx=x, pbw=(half-4)/2;
        ctx.fill(pbx,y+2,pbx+pbw,y+18,buyC);   ctx.fill(pbx,y+2,pbx+pbw,y+3,0x22FFFFFF);
        dt(ctx,"✅ Acheter",pbx+pbw/2-tw("✅ Acheter")/2,y+5,buyTC);
        ctx.fill(pbx+pbw+4,y+2,pbx+half,y+18,closeC); ctx.fill(pbx+pbw+4,y+2,pbx+half,y+3,0x22FFFFFF);
        dt(ctx,"✕ Fermer",pbx+pbw+4+(pbw/2)-tw("✕ Fermer")/2,y+5,closeTC);
        y+=22;
        y+=6;

        // Display mode section (right col, continued)
        y=sect(ctx,x,y,half,"📐 Mode d'affichage");
        y=renderDisplayModeSelector(ctx,mx,my,x,y,half);

        updateMaxScroll(startY,Math.max(startY+8+300, y+20));
    }

    /** Redesigned display mode selector with description for each mode */
    private int renderDisplayModeSelector(DrawContext ctx,int mx,int my,int x,int y,int w){
        String[] modes={"TABS","FLAT","FEATURED"};
        String[] icons={"🗂","☰","⭐"};
        String[] names={"Onglets","Liste plate","Mise en avant"};
        String[] descs={"Un onglet par set actif.\nParfait pour séparer clairement plusieurs catégories.",
                        "Tous les items dans une seule liste déroulante.\nSimple et compact.",
                        "Le premier set affiché en grand.\nLes autres sets en dessous en plus petit."};
        String cur=iv.getOrDefault("displayMode","TABS");
        for(int i=0;i<modes.length;i++){
            boolean sel=modes[i].equals(cur);
            boolean hov=hov(mx,my,x,y,w,28)&&!sel;
            ctx.fill(x,y,x+w,y+28,sel?C_PANEL_LIGHT:hov?0xFF1A2A4A:C_PANEL);
            ctx.fill(x,y,x+3,y+28,sel?C_TEXT_ACCENT:C_BORDER);
            if(sel) ctx.fill(x+w-3,y,x+w,y+28,C_TEXT_ACCENT);
            dt(ctx,icons[i]+" "+names[i],x+8,y+5,sel?C_TEXT_ACCENT:C_TEXT);
            dt(ctx,descs[i].split("\n")[0],x+8,y+16,C_TEXT_DIM);
            if(!sel) addZone(x,y,w,28,"field_cycle:displayMode:"+modes[i]);
            y+=30;
        }
        return y+4;
    }

    // ── SETS EMBED ────────────────────────────────────────────────────────────
    private void renderSetsEmbed(DrawContext ctx,int mx,int my,int W,int startY){
        String shopId=gs("id",gs("shopId",""));
        int y=startY+8; y=sect(ctx,8,y,W-16,"📦 Sets");
        JsonArray sets=data.has("sets")?data.getAsJsonArray("sets"):new JsonArray();
        if(sets.isEmpty()){ dt(ctx,"Aucun set. '+ Nouveau set' ci-dessous.",16,y+8,C_TEXT_DIM); y+=28; }
        for(int i=0;i<sets.size();i++){
            JsonObject s=sets.get(i).getAsJsonObject();
            String sid=sp(s,"id","?"),name=sp(s,"name","?");
            boolean active=s.has("active")&&s.get("active").getAsBoolean();
            int ic=s.has("items")?s.getAsJsonArray("items").size():(s.has("itemCount")?s.get("itemCount").getAsInt():0);
            String rotLabel=sp(s,"rotationLabel","");

            boolean hov=hov(mx,my,8,y,W-108,30);
            ctx.fill(8,y,W-8,y+30,hov?C_ROW_HOVER:(i%2==0?C_ROW_A:C_ROW_B));
            ctx.fill(8,y,11,y+30,active?C_ENABLED:C_DISABLED);
            // Name + ID
            dt(ctx,name,18,y+5,C_TEXT);
            dt(ctx,sid,18,y+16,C_TEXT_DIM);
            // Active badge
            dt(ctx,active?"● Actif":"○ Inactif",W-290,y+10,active?0xFF55FF55:0xFF888888);
            // Items count + rotation (right-aligned before button, well separated)
            String info=ic+" item"+(ic!=1?"s":"")+(rotLabel.isBlank()?"":" · "+rotLabel);
            dt(ctx,info,W-200,y+10,C_TEXT_DIM);
            // Edit button
            btn(ctx,mx,my,W-100,y+5,90,20,"✏ Éditer",C_BTN,C_BTN_H,"open_set:"+shopId+":"+sid);
            addZone(8,y,W-108,30,"open_set:"+shopId+":"+sid);
            ctx.fill(8,y+29,W-8,y+30,C_SEP); y+=31;
        }
        y+=4;
        btn(ctx,mx,my,8,y,100,22,"＋ Nouveau set",C_BTN_GREEN,C_BTN_GREEN_H,"set_create:"+shopId);
        updateMaxScroll(startY,y+40);
    }

    // ── SHOP PREVIEW — with actual items ─────────────────────────────────────
    private void renderShopPreview(DrawContext ctx,int mx,int my,int W,int startY){
        int y=startY+8;
        y=sect(ctx,8,y,W-16,"👁 Aperçu joueur");

        // Resolve colors from current inputs
        int bgC   = parseHex(iv.getOrDefault("bgColor","1A1A2E"));
        int borC  = parseHex(iv.getOrDefault("borderColor","2A5A8A"));
        int accC  = parseHex(iv.getOrDefault("accentColor","4AC8EA"));
        int titleC= parseMCColor(iv.getOrDefault("titleColor","gold"));
        String mode=iv.getOrDefault("displayMode","TABS");

        int pw=W-40, px2=20;
        // Window frame
        ctx.fill(px2,y,px2+pw,y+26,0xFF0D2137);
        ctx.fill(px2,y,px2+pw,y+1,borC); ctx.fill(px2,y+25,px2+pw,y+26,borC);
        ctx.fill(px2,y,px2+1,y+26,accC); ctx.fill(px2+pw-1,y,px2+pw,y+26,borC);
        ctx.fill(px2,y,px2+pw,y+3,accC); // top accent
        String name=iv.getOrDefault("name","Shop");
        dt(ctx,name,px2+pw/2-tw(name)/2,y+8,titleC);
        ctx.fill(px2+pw-18,y+4,px2+pw-4,y+20,0xFF3A1A1A);
        dt(ctx,"✕",px2+pw-16,y+6,C_TEXT_DIM);
        y+=28;

        // Welcome message
        if("true".equals(iv.get("showWelcomeMessage"))){
            String msg=iv.getOrDefault("welcomeMessage","");
            if(!msg.isBlank()){ ctx.fill(px2,y,px2+pw,y+14,0xFF0A1525); dt(ctx,msg,px2+pw/2-tw(msg)/2,y+3,C_TEXT_DIM); y+=14; }
        }
        // Mode label
        dt(ctx,"Mode: "+displayModeLabel(mode)+"  — items du/des sets ci-dessous",px2+6,y+4,C_TEXT_DIM); y+=18;

        // Content: render actual items from sets
        ctx.fill(px2,y,px2+pw,y+1,borC);
        y+=4;
        JsonArray sets=data.has("sets")?data.getAsJsonArray("sets"):new JsonArray();
        boolean hasItems=false;
        // Collect items from active sets (all sets for preview)
        for(JsonElement se:sets){
            JsonObject setObj=se.getAsJsonObject();
            JsonArray items=setObj.has("items")?setObj.getAsJsonArray("items"):new JsonArray();
            if(items.isEmpty()) continue;
            hasItems=true;
            // Set label
            ctx.fill(px2+4,y,px2+pw-4,y+14,C_PANEL); ctx.fill(px2+4,y,px2+7,y+14,accC);
            dt(ctx,sp(setObj,"name","Set"),px2+12,y+2,C_TEXT_ACCENT); y+=16;
            // Small card row (read-only preview)
            int cols=Math.max(1,(pw-12)/(54+4));
            for(int i=0;i<Math.min(items.size(),cols*2);i++){
                JsonObject item=items.get(i).getAsJsonObject();
                String iconId=effectiveIconAdmin(item);
                String rawName=sp(item,"displayName","");
                String dispName=rawName.isBlank()?getTranslatedName(iconId):rawName;
                int col=i%cols,row=i/cols;
                int cx2=px2+6+col*(54+4), cy2=y+row*(54+4);
                ctx.fill(cx2,cy2,cx2+54,cy2+54,C_PANEL);
                ctx.fill(cx2,cy2,cx2+54,cy2+1,borC); ctx.fill(cx2,cy2,cx2+1,cy2+54,accC);
                ctx.fill(cx2+53,cy2,cx2+54,cy2+54,borC); ctx.fill(cx2,cy2+53,cx2+54,cy2+54,borC);
                renderItem3D(ctx,iconId,cx2+11,cy2+4,32);
                String n=truncate(dispName,7);
                dt(ctx,n,cx2+27-tw(n)/2,cy2+38,C_TEXT_DIM);
            }
            int rows=(Math.min(items.size(),cols*2)+cols-1)/cols;
            y+=rows*(54+4)+4;
        }
        if(!hasItems){ dt(ctx,"Aucun item dans les sets.",px2+6,y+6,C_TEXT_DIM); y+=24; }

        updateMaxScroll(startY,y+20);
    }

    // ── SET CONFIG ────────────────────────────────────────────────────────────
    private void renderSetConfig(DrawContext ctx,int mx,int my,int W,int startY){
        int half=W/2-10,col2=W/2+5,x=8,y=startY+8;
        y=sect(ctx,x,y,half,"🏷 Identité"); y=field(ctx,mx,my,x,y,half,"Nom du set","name");
        y+=6; y=sect(ctx,x,y,half,"🔄 Rotation");
        y=cycleBtn(ctx,mx,my,x,y,half,"Mode","rotMode",
            new String[]{"ALWAYS","SCHEDULE","MANUAL"},
            new String[]{"Toujours actif","Horaire","Manuel"});
        String rm=iv.getOrDefault("rotMode","ALWAYS");
        if("SCHEDULE".equals(rm)){ y=fieldSmall(ctx,mx,my,x,y,half,"Début (HH:mm)","scheduleStart"); y=fieldSmall(ctx,mx,my,x,y,half,"Fin   (HH:mm)","scheduleEnd"); dt(ctx,"ℹ Plusieurs sets actifs simultanément possible.",x,y+2,C_TEXT_DIM); y+=14; }
        if("MANUAL".equals(rm)) y=tog(ctx,mx,my,x,y,half,"Actif manuellement","manualActive");
        x=col2; y=startY+8; y=sect(ctx,x,y,half,"📊 Statut");
        boolean active=switch(rm){case"ALWAYS"->true;case"MANUAL"->"true".equals(iv.get("manualActive"));default->true;};
        ctx.fill(x,y+2,x+half,y+24,C_PANEL); dt(ctx,active?"● Actuellement ACTIF":"○ Actuellement INACTIF",x+8,y+9,active?0xFF55FF55:0xFFFF5555);
        updateMaxScroll(startY,y+60);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ── ITEM GRID — smaller cells, translated names ───────────────────────────
    // ══════════════════════════════════════════════════════════════════════════
    private void renderItemGrid(DrawContext ctx,int mx,int my,int W,int startY){
        String shopId=gs("shopId",gs("id","")), setId=gs("setId",gs("id",""));
        int y=startY+8;
        y=sect(ctx,8,y,W-16,"🛒 Items — glisser pour réordonner · clic droit = menu");
        JsonArray items=data.has("items")?data.getAsJsonArray("items"):new JsonArray();
        if(items.isEmpty()){ dt(ctx,"Aucun item. '+ Nouvel item' dans le footer.",16,y+12,C_TEXT_DIM); updateMaxScroll(startY,y+50); return; }

        int cols=Math.max(1,(W-16)/(GRID_W+GRID_PAD));
        int gridX=8, gridY0=y;

        for(int i=0;i<items.size();i++){
            JsonObject item=items.get(i).getAsJsonObject();
            String itemId=sp(item,"id","?");
            boolean enabled=!item.has("enabled")||item.get("enabled").getAsBoolean();
            String stockMode=sp(item,"stockMode","UNLIMITED");
            String iconId=effectiveIconAdmin(item);
            String translName=effectiveTranslatedName(item);

            int col=i%cols,row=i/cols;
            int cx=gridX+col*(GRID_W+GRID_PAD), cy=gridY0+row*(GRID_H+GRID_PAD);

            // Drag placeholder
            if(itemId.equals(dragItemId)){ ctx.fill(cx,cy,cx+GRID_W,cy+GRID_H,0x22FFFFFF); ctx.fill(cx,cy,cx+GRID_W,cy+1,C_BORDER); continue; }

            boolean isDropTarget=dragItemId!=null&&dragTargetIdx==i;
            boolean hov=hov(mx,my,cx,cy,GRID_W,GRID_H)&&dragItemId==null;

            // Card background
            ctx.fill(cx,cy,cx+GRID_W,cy+GRID_H,isDropTarget?C_PANEL_LIGHT:hov?0xFF1E2E4E:C_PANEL);
            int bc=enabled?C_BORDER:0xFF5A2A2A;
            ctx.fill(cx,cy,cx+GRID_W,cy+1,bc); ctx.fill(cx,cy,cx+1,cy+GRID_H,bc);
            ctx.fill(cx+GRID_W-1,cy,cx+GRID_W,cy+GRID_H,bc); ctx.fill(cx,cy+GRID_H-1,cx+GRID_W,cy+GRID_H,bc);

            // 3D icon 24px
            int iconSz=24;
            renderItem3D(ctx,iconId,cx+GRID_W/2-iconSz/2,cy+5,iconSz);

            // Translated name (truncated)
            String n=truncate(translName,8);
            dt(ctx,n,cx+GRID_W/2-tw(n)/2,cy+5+iconSz+3,enabled?C_TEXT:C_TEXT_DIM);

            // Enabled badge (10×10) top-right
            ctx.fill(cx+GRID_W-12,cy+2,cx+GRID_W-2,cy+12,enabled?C_ENABLED:C_DISABLED);
            dt(ctx,enabled?"✓":"✕",cx+GRID_W-11,cy+2,0xFFFFFFFF);

            // Stock icon bottom-left (small)
            dt(ctx,stockModeIcon(stockMode),cx+2,cy+GRID_H-11,C_TEXT_DIM);

            // Hover action strip
            // Register grid zone FIRST (lower index) so hover buttons (higher index)
            // are checked first in reverse iteration and take priority over drag start
            addZone(cx,cy,GRID_W,GRID_H,"_grid:"+itemId+":"+i+":"+shopId+":"+setId);

            if(hov){
                ctx.fill(cx+1,cy+GRID_H-17,cx+GRID_W-1,cy+GRID_H-1,0xCC0D2137);
                int bw=(GRID_W-4)/2;
                btn(ctx,mx,my,cx+2,cy+GRID_H-16,bw,14,"✏",C_BTN,C_BTN_H,"open_item:"+shopId+":"+setId+":"+itemId);
                btn(ctx,mx,my,cx+2+bw,cy+GRID_H-16,bw,14,enabled?"OFF":"ON",enabled?C_BTN_RED:C_BTN_GREEN,enabled?C_BTN_RED_H:C_BTN_GREEN_H,"item_toggle:"+shopId+":"+setId+":"+itemId);
                tooltip=translName;
            }
        }

        // "Add" card
        { int i=items.size(),col=i%cols,row=i/cols;
          int cx=gridX+col*(GRID_W+GRID_PAD),cy=gridY0+row*(GRID_H+GRID_PAD);
          boolean hov=hov(mx,my,cx,cy,GRID_W,GRID_H);
          ctx.fill(cx,cy,cx+GRID_W,cy+GRID_H,hov?C_PANEL_LIGHT:0xFF0A1525);
          for(int z=0;z<4;z++) ctx.fill(z<2?cx:cx+GRID_W-(z==3?1:0),z<2?cy:cy+GRID_H-(z==3?1:0),(z<2?cx+GRID_W:cx+GRID_W),(z<2?cy+1:cy+GRID_H),C_SEP);
          ctx.fill(cx,cy,cx+GRID_W,cy+1,C_SEP); ctx.fill(cx,cy,cx+1,cy+GRID_H,C_SEP);
          ctx.fill(cx+GRID_W-1,cy,cx+GRID_W,cy+GRID_H,C_SEP); ctx.fill(cx,cy+GRID_H-1,cx+GRID_W,cy+GRID_H,C_SEP);
          dt(ctx,"＋",cx+GRID_W/2-4,cy+GRID_H/2-10,hov?C_TEXT_ACCENT:C_TEXT_DIM);
          dt(ctx,"Ajouter",cx+GRID_W/2-tw("Ajouter")/2,cy+GRID_H/2+2,C_TEXT_DIM);
          addZone(cx,cy,GRID_W,GRID_H,"item_create:"+shopId+":"+setId); }

        int totalRows=(items.size()+1+cols-1)/cols;
        updateMaxScroll(startY,gridY0+totalRows*(GRID_H+GRID_PAD)+20);
    }

    private void renderDragGhost(DrawContext ctx,int mx,int my){
        int gx=mx-GRID_W/2,gy=my-GRID_H/2;
        ctx.fill(gx,gy,gx+GRID_W,gy+GRID_H,0xBB0F3460);
        ctx.fill(gx,gy,gx+GRID_W,gy+1,C_TEXT_ACCENT); ctx.fill(gx,gy,gx+1,gy+GRID_H,C_TEXT_ACCENT);
        ctx.fill(gx+GRID_W-1,gy,gx+GRID_W,gy+GRID_H,C_TEXT_ACCENT); ctx.fill(gx,gy+GRID_H-1,gx+GRID_W,gy+GRID_H,C_TEXT_ACCENT);
        JsonArray items=data.has("items")?data.getAsJsonArray("items"):new JsonArray();
        for(JsonElement el:items){ JsonObject it=el.getAsJsonObject(); if(dragItemId.equals(sp(it,"id",""))){ renderItem3D(ctx,effectiveIconAdmin(it),gx+GRID_W/2-12,gy+5,24); break; } }
    }

    private void renderContextMenu(DrawContext ctx,int mx,int my){
        int mw=134,mh=46;
        int cx2=Math.min(ctxX,this.width-mw-4),cy2=Math.min(ctxY,this.height-mh-4);
        ctx.fill(cx2-1,cy2-1,cx2+mw+1,cy2+mh+1,C_BORDER); ctx.fill(cx2,cy2,cx2+mw,cy2+mh,C_HEADER);
        boolean en=true;
        JsonArray items=data.has("items")?data.getAsJsonArray("items"):new JsonArray();
        String shopId=gs("shopId",gs("id","")),setId=gs("setId",gs("id",""));
        for(JsonElement el:items){ JsonObject it=el.getAsJsonObject(); if(ctxItemId.equals(sp(it,"id",""))){ en=!it.has("enabled")||it.get("enabled").getAsBoolean(); break; } }
        btn(ctx,mx,my,cx2+2,cy2+2,mw-4,19,en?"🔒 Désactiver":"✅ Activer",en?C_BTN_RED:C_BTN_GREEN,en?C_BTN_RED_H:C_BTN_GREEN_H,"item_toggle:"+shopId+":"+setId+":"+ctxItemId);
        btn(ctx,mx,my,cx2+2,cy2+24,mw-4,19,"🗑 Supprimer",C_BTN_RED,C_BTN_RED_H,"_ctx_delete:"+shopId+":"+setId+":"+ctxItemId);
        addZone(0,0,this.width,this.height,"_close_ctx");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ── ITEM EDITOR — auto-icon fix, preview with translated names ────────────
    // ══════════════════════════════════════════════════════════════════════════
    private void renderItemEditor(DrawContext ctx,int mx,int my,int W,int startY){
        String shopId=gs("shopId",""),setId=gs("setId",""),itemId=gs("id","");
        int half=W/2-10,col2=W/2+5;

        // ── LEFT COLUMN ───────────────────────────────────────────────────────
        int x=8,y=startY+8;
        y=sect(ctx,x,y,half,"🏷 Identité");

        // Auto-icon logic:
        //  - If displayItem is blank → auto mode (shows first output icon)
        //  - "Choisir icône" → opens picker, sets custom displayItem
        //  - "✕ Auto" → clears displayItem, back to auto
        String customIcon=iv.getOrDefault("displayItem","");
        String firstOutputIcon=firstOutputIconFromData();
        boolean isAuto=customIcon.isBlank();
        String iconId=isAuto?firstOutputIcon:customIcon;
        String iconName=iconId.isBlank()?"—":getTranslatedName(iconId);

        dt(ctx,"Icône:",x,y,C_TEXT_DIM);
        ctx.fill(x,y+12,x+40,y+52,C_INPUT_BG);
        if(!iconId.isBlank()) renderItem3D(ctx,iconId,x+4,y+14,32);
        String autoLabel=isAuto?"§7auto: "+truncate(iconName,14):truncate(iconName,16);
        dt(ctx,autoLabel,x+44,y+18,isAuto?C_TEXT_DIM:C_TEXT);
        // Buttons
        btn(ctx,mx,my,x+44,y+34,half-44,16,"🔍 Choisir icône",C_BTN_PURPLE,C_BTN_PURPLE_H,"_open_picker:displayItem");
        if(!isAuto) btn(ctx,mx,my,x+44,y+12,half-44,16,"✕ Revenir auto",C_BTN,C_BTN_H,"_clear_icon");
        y+=58;

        y=field(ctx,mx,my,x,y,half,"Nom affiché (vide = nom auto de l'item)","displayName");
        y=tog(ctx,mx,my,x,y,half,"Item activé","enabled");
        y+=8;

        // Outputs
        JsonArray outputs=data.has("outputs")?data.getAsJsonArray("outputs"):new JsonArray();
        y=sect(ctx,x,y,half,"📤 Outputs — items reçus");
        if(outputs.isEmpty()){ dt(ctx,"Aucun output défini.",x+4,y+6,0xFFFF8844); y+=20; }
        for(int i=0;i<outputs.size();i++){
            JsonObject e=outputs.get(i).getAsJsonObject();
            String eid=sp(e,"itemId","?"); int cnt=e.has("count")?e.get("count").getAsInt():1;
            ctx.fill(x,y,x+half,y+22,i%2==0?C_ROW_A:C_ROW_B);
            renderItem3D(ctx,eid,x+2,y+3,16);
            dt(ctx,getTranslatedName(eid)+" x"+cnt,x+22,y+6,C_TEXT);
            btn(ctx,mx,my,x+half-26,y+2,24,18,"✕",C_BTN_RED,C_BTN_RED_H,"item_output_remove:"+shopId+":"+setId+":"+itemId+":"+i);
            ctx.fill(x,y+21,x+half,y+22,C_SEP); y+=23;
        }
        btn(ctx,mx,my,x,y,half,20,"＋ Ajouter output",C_BTN_GREEN,C_BTN_GREEN_H,"_open_picker:output:"+shopId+":"+setId+":"+itemId);
        y+=26;

        // Costs
        JsonArray costs=data.has("costs")?data.getAsJsonArray("costs"):new JsonArray();
        y=sect(ctx,x,y,half,"💰 Coûts — items payés");
        if(costs.isEmpty()){ dt(ctx,"Gratuit (aucun coût).",x+4,y+6,0xFF55FF55); y+=20; }
        for(int i=0;i<costs.size();i++){
            JsonObject e=costs.get(i).getAsJsonObject();
            String eid=sp(e,"itemId","?"); int cnt=e.has("count")?e.get("count").getAsInt():1;
            ctx.fill(x,y,x+half,y+22,i%2==0?C_ROW_A:C_ROW_B);
            renderItem3D(ctx,eid,x+2,y+3,16);
            dt(ctx,getTranslatedName(eid)+" x"+cnt,x+22,y+6,C_TEXT);
            btn(ctx,mx,my,x+half-26,y+2,24,18,"✕",C_BTN_RED,C_BTN_RED_H,"item_cost_remove:"+shopId+":"+setId+":"+itemId+":"+i);
            ctx.fill(x,y+21,x+half,y+22,C_SEP); y+=23;
        }
        btn(ctx,mx,my,x,y,half,20,"＋ Ajouter coût",C_BTN_ORANGE,C_BTN_ORANGE_H,"_open_picker:cost:"+shopId+":"+setId+":"+itemId);
        y+=26;
        int leftBottom=y;

        // ── RIGHT COLUMN ──────────────────────────────────────────────────────
        x=col2; y=startY+8;
        y=sect(ctx,x,y,half,"📊 Stock");
        y=cycleBtn(ctx,mx,my,x,y,half,"Mode","stockMode",
            new String[]{"UNLIMITED","GLOBAL","PER_PLAYER","PER_PLAYER_RESET"},
            new String[]{"Illimité","Global serveur","Par joueur","Par joueur (reset)"});
        String sm=iv.getOrDefault("stockMode","UNLIMITED");
        if("GLOBAL".equals(sm)) y=fieldSmall(ctx,mx,my,x,y,half,"Quantité totale","globalStock");
        if("PER_PLAYER".equals(sm)||"PER_PLAYER_RESET".equals(sm)){
            y=fieldSmall(ctx,mx,my,x,y,half,"Limite par joueur","perPlayerLimit");
            if("PER_PLAYER_RESET".equals(sm)){
                y=cycleBtn(ctx,mx,my,x,y,half,"Reset","resetType",new String[]{"DAILY","WEEKLY","MONTHLY","CUSTOM"},new String[]{"Quotidien","Hebdomadaire","Mensuel","Custom"});
                if("CUSTOM".equals(iv.get("resetType"))) y=fieldSmall(ctx,mx,my,x,y,half,"Intervalle (sec)","resetIntervalSecs");
            }
        }
        y+=8;

        // Live preview card — with translated names
        y=sect(ctx,x,y,half,"🔍 Aperçu carte joueur");
        int cardH=96,cardX=x;
        ctx.fill(cardX,y+2,cardX+half,y+2+cardH,C_PANEL);
        ctx.fill(cardX,y+2,cardX+half,y+3,C_TEXT_ACCENT);
        ctx.fill(cardX,y+2,cardX+1,y+2+cardH,C_TEXT_ACCENT);
        // Big 3D icon
        if(!iconId.isBlank()) renderItem3D(ctx,iconId,cardX+6,y+8,40);
        // Display name: custom or MC translated
        String customName=iv.getOrDefault("displayName","");
        String previewName=customName.isBlank()?getTranslatedName(iconId):customName;
        dt(ctx,truncate(previewName,18),cardX+52,y+12,C_TEXT);
        // Outputs (translated)
        StringBuilder os=new StringBuilder();
        for(int i2=0;i2<outputs.size()&&i2<3;i2++){
            JsonObject e=outputs.get(i2).getAsJsonObject(); if(i2>0) os.append(", ");
            os.append(getTranslatedName(sp(e,"itemId","?")));
            if(e.has("count")&&e.get("count").getAsInt()>1) os.append(" x").append(e.get("count").getAsInt());
        }
        dt(ctx,"📤 "+truncate(os.toString().isBlank()?"—":os.toString(),22),cardX+52,y+26,C_TEXT_DIM);
        // Costs (translated)
        StringBuilder cs=new StringBuilder();
        for(int i2=0;i2<costs.size()&&i2<3;i2++){
            JsonObject e=costs.get(i2).getAsJsonObject(); if(i2>0) cs.append(", ");
            cs.append(getTranslatedName(sp(e,"itemId","?")));
            if(e.has("count")&&e.get("count").getAsInt()>1) cs.append(" x").append(e.get("count").getAsInt());
        }
        String costLabel=cs.toString().isBlank()?"Gratuit":cs.toString();
        dt(ctx,"💰 "+truncate(costLabel,22),cardX+52,y+40,0xFFFFAA00);
        dt(ctx,"📦 "+stockModeIcon(sm),cardX+52,y+54,C_TEXT_DIM);
        boolean en="true".equals(iv.get("enabled"));
        ctx.fill(cardX+half-14,y+4,cardX+half-2,y+16,en?C_ENABLED:C_DISABLED);
        dt(ctx,en?"✓":"✕",cardX+half-13,y+4,0xFFFFFFFF);
        y+=cardH+12;

        updateMaxScroll(startY,Math.max(leftBottom,y)+20);
    }

    // ── Item picker ───────────────────────────────────────────────────────────
    private void renderItemPicker(DrawContext ctx,int mx,int my,int W,int startY){
        int y=startY+8; y=sect(ctx,8,y,W-16,"🔍 Sélecteur d'item Minecraft");

        // Search bar — supports translated name AND raw ID
        dt(ctx,"Recherche par nom ou ID (ex: roche, diamond, torch):",8,y+2,C_TEXT_DIM);
        boolean hovS=hov(mx,my,8,y+14,W-20,22);
        ctx.fill(8,y+14,W-12,y+36,C_INPUT_BG); ctx.fill(8,y+14,W-12,y+15,hovS?C_TEXT_ACCENT:C_INPUT_BORDER);
        dt(ctx,pickerQuery.isEmpty()?"§8Tapez pour filtrer...":pickerQuery+"▌",14,y+19,pickerQuery.isEmpty()?C_TEXT_DIM:C_TEXT);
        addZone(8,y+14,W-20,22,"_picker_search_input"); y+=42;

        // Client-side filter using translated names from cached pickerAllItems
        com.google.gson.JsonArray source = pickerAllItems != null ? pickerAllItems
            : (data.has("results") ? data.getAsJsonArray("results") : new JsonArray());

        java.util.List<String> filtered = new java.util.ArrayList<>();
        String q = pickerQuery.toLowerCase().trim();
        for (JsonElement el : source) {
            String id = sp(el.getAsJsonObject(),"id","?");
            if (q.isEmpty()) { filtered.add(id); }
            else {
                String translated = getTranslatedName(id).toLowerCase();
                if (translated.contains(q) || id.toLowerCase().contains(q)) filtered.add(id);
            }
            if (filtered.size() >= 120) break;
        }

        dt(ctx,filtered.size()+" résultat"+(filtered.size()!=1?"s":""),8,y,C_TEXT_DIM); y+=14;
        int cols=4, colW=(W-24)/cols;
        for (int i=0; i<filtered.size(); i++) {
            String id=filtered.get(i);
            String name=getTranslatedName(id);
            int col=i%cols, row=i/cols, ix=8+col*(colW+4), iy=y+row*38;
            boolean hov=hov(mx,my,ix,iy,colW,36);
            ctx.fill(ix,iy,ix+colW,iy+36,hov?C_PANEL_LIGHT:C_ROW_A); ctx.fill(ix,iy,ix+3,iy+36,C_TEXT_ACCENT);
            renderItem3D(ctx,id,ix+6,iy+10,16);
            dt(ctx,truncate(name,colW/6+2),ix+26,iy+6,hov?C_TEXT_ACCENT:C_TEXT);
            String shortId=id.contains(":")?id.substring(id.indexOf(':')+1):id;
            dt(ctx,truncate(shortId,colW/6+2),ix+26,iy+18,C_TEXT_DIM);
            addZone(ix,iy,colW,36,"_picker_select:"+id);
        }
        int totalRows=(filtered.size()+cols-1)/cols;
        updateMaxScroll(startY,y+totalRows*38+20);
    }
    // Cache for parsed ItemStacks with components (avoid re-parsing every frame)
    private static final java.util.Map<String, ItemStack> componentStackCache = new java.util.HashMap<>();

    private void renderItem3D(DrawContext ctx,String itemId,int x,int y,int sizePx){
        if(itemId==null||itemId.isBlank()) return;
        try {
            ItemStack stack;
            if (itemId.contains("[")) {
                // Item with components — parse with MC's item reader for correct model/color
                stack = componentStackCache.computeIfAbsent(itemId, s -> {
                    try {
                        var client = net.minecraft.client.MinecraftClient.getInstance();
                        if (client.getNetworkHandler() == null) return ItemStack.EMPTY;
                        var lookup = client.getNetworkHandler().getRegistryManager();
                        var parser = new net.minecraft.command.argument.ItemStringReader(lookup);
                        var result = parser.consume(new com.mojang.brigadier.StringReader(s));
                        return new ItemStack(result.item(), 1, result.components());
                    } catch (Exception e) {
                        try {
                            String baseId = s.substring(0, s.indexOf('['));
                            Identifier bid = Identifier.tryParse(baseId);
                            if (bid != null) return new ItemStack(Registries.ITEM.get(bid), 1);
                        } catch (Exception ignored) {}
                        return ItemStack.EMPTY;
                    }
                });
                if (stack.isEmpty()) return;
            } else {
                Identifier id=Identifier.tryParse(itemId); if(id==null) return;
                Item item=Registries.ITEM.get(id); if(item==null) return;
                stack=new ItemStack(item,1);
            }
            float scale=sizePx/16.0f;
            ctx.getMatrices().push();
            ctx.getMatrices().translate(x,y,100);
            ctx.getMatrices().scale(scale,scale,1f);
            ctx.drawItem(stack,0,0);
            ctx.getMatrices().pop();
        } catch(Exception ignored){ ctx.fill(x,y,x+sizePx,y+sizePx,0x44FFFFFF); }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ── CLICK / DRAG ──────────────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════════
    @Override
    public boolean mouseClicked(double mx,double my,int button){
        if(button==1){
            ctxItemId=null;
            for(int i=widgets.size()-1;i>=0;i--){ Widget w=widgets.get(i); if(w.contains((int)mx,(int)my)&&w.action().startsWith("_grid:")){ String[] p=w.action().substring(6).split(":",5); ctxItemId=p[0]; ctxX=(int)mx; ctxY=(int)my; return true; } }
            return false;
        }
        if(button==0){
            if(ctxItemId!=null){ ctxItemId=null; }
            for(int i=widgets.size()-1;i>=0;i--){ Widget w=widgets.get(i); if(w.contains((int)mx,(int)my)){ if(w.action().startsWith("_grid:")){ String[] p=w.action().substring(6).split(":",5); dragItemId=p[0]; dragSourceIdx=parseInt(p[1]); dragMouseX=(int)mx; dragMouseY=(int)my; dragTargetIdx=dragSourceIdx; return true; } if(!w.action().isEmpty()){ handleAction(w.action()); return true; } } }
        }
        return super.mouseClicked(mx,my,button);
    }

    @Override
    public boolean mouseDragged(double mx,double my,int button,double dx,double dy){
        if(dragItemId!=null&&button==0){ dragMouseX=(int)mx; dragMouseY=(int)my; dragTargetIdx=hitTestGrid((int)mx,(int)my); return true; }
        return super.mouseDragged(mx,my,button,dx,dy);
    }

    @Override
    public boolean mouseReleased(double mx,double my,int button){
        if(dragItemId!=null&&button==0){
            boolean wasDrag=Math.abs((int)mx-dragMouseX)>4||Math.abs((int)my-dragMouseY)>4;
            if(wasDrag){ int t=hitTestGrid((int)mx,(int)my); if(t>=0&&t!=dragSourceIdx) send("item_reorder:"+gs("shopId",gs("id",""))+":"+gs("setId",gs("id",""))+":"+dragItemId+":"+t); }
            else handleAction("open_item:"+gs("shopId",gs("id",""))+":"+gs("setId",gs("id",""))+":"+dragItemId);
            dragItemId=null; dragTargetIdx=-1; return true;
        }
        return super.mouseReleased(mx,my,button);
    }

    @Override
    public boolean mouseScrolled(double mx,double my,double h,double v){
        String key=scrollKey(); int cur=scrollY.getOrDefault(key,0),max=maxScrollY.getOrDefault(key,0);
        scrollY.put(key,Math.max(0,Math.min(max,cur-(int)(v*12)))); return true;
    }

    private int hitTestGrid(int mx,int my){
        JsonArray items=data.has("items")?data.getAsJsonArray("items"):new JsonArray();
        int W=this.width, cols=Math.max(1,(W-16)/(GRID_W+GRID_PAD));
        int scroll2=scrollY.getOrDefault(scrollKey(),0);
        int gridY0=contentY+28-scroll2, gridX=8;
        for(int i=0;i<=items.size();i++){
            int col=i%cols,row=i/cols;
            int cx=gridX+col*(GRID_W+GRID_PAD),cy=gridY0+row*(GRID_H+GRID_PAD);
            if(mx>=cx&&mx<cx+GRID_W&&my>=cy&&my<cy+GRID_H) return i;
        }
        return -1;
    }

    // ── Action dispatch ───────────────────────────────────────────────────────
    private void handleAction(String action){
        if(action.isEmpty()) return;
        if(action.equals("_close_ctx")){ ctxItemId=null; return; }
        if(action.startsWith("_tab:")){ currentTab=action.substring(5); ctxItemId=null; return; }
        if(action.equals("_clear_icon")){ iv.put("displayItem",""); dirty=true; return; }
        if(action.startsWith("_ctx_delete:")){
            String[] p=action.substring(12).split(":",3);
            if(p.length==3) send("item_delete:"+p[0]+":"+p[1]+":"+p[2]);
            ctxItemId=null; return;
        }
        if(action.startsWith("_input:")){
            String[] p=action.substring(7).split("\\|",3);
            if(p.length>=2) openTextInput(p[0],p.length>2?p[2]:iv.getOrDefault(p[1],""),val->{ iv.put(p[1],val); dirty=true; }); return;
        }
        if(action.startsWith("field_toggle:")){ String k=action.substring(13); iv.put(k,String.valueOf(!"true".equals(iv.getOrDefault(k,"false")))); dirty=true; return; }
        if(action.startsWith("field_cycle:")){ String[] p=action.substring(12).split(":",2); if(p.length==2){iv.put(p[0],p[1]);dirty=true;} return; }
        if(action.startsWith("color_cycle:")){ String[] p=action.substring(12).split(":",2); if(p.length==2){iv.put(p[0],p[1]);dirty=true;} return; }
        if(action.equals("_picker_search_input")){ openTextInput("Rechercher",pickerQuery,q->{ pickerQuery=q; }); return; }
        if(action.startsWith("_picker_select:")){
            String selId=action.substring(15);
            if("displayItem".equals(pickerContext)){ iv.put("displayItem",selId); dirty=true; }
            else if(pickerContext.startsWith("output:")){ String[] p=pickerContext.substring(7).split(":",3); if(p.length==3) openTextInput("Quantité","1",c2->send("item_output_add:"+p[0]+"|"+p[1]+"|"+p[2]+"|"+selId+"|"+safeCount(c2))); }
            else if(pickerContext.startsWith("cost:")){ String[] p=pickerContext.substring(5).split(":",3); if(p.length==3) openTextInput("Quantité","1",c2->send("item_cost_add:"+p[0]+"|"+p[1]+"|"+p[2]+"|"+selId+"|"+safeCount(c2))); }
            return;
        }
        if(action.equals("_picker_cancel")){ send("open_item:"+gs("shopId","")+":"+gs("setId","")+":"+gs("id","")); return; }
        if(action.startsWith("_open_picker:")){ pickerContext=action.substring(13); pickerQuery=""; pickerAllItems=null; send("picker_open:"+pickerContext); return; }
        if(action.equals("_new_shop"))    { openTextInput("ID du nouveau shop","",id->{ if(!id.isBlank()) send("shop_create:"+id); }); return; }
        if(action.equals("_save_shop"))   { send("shop_save:"+gs("id","")+"|"+buildShopJson()); return; }
        if(action.equals("_reload_shop")) { send("open_shop:"+gs("id","")); return; }
        if(action.equals("_delete_shop")) { openTextInput("Confirmer (tapez l'ID)","",v->{ if(v.equals(gs("id",""))) send("shop_delete:"+gs("id","")); }); return; }
        if(action.equals("_save_set"))    { send("set_save:"+gs("shopId","")+":"+gs("id","")+"|"+buildSetJson()); return; }
        if(action.equals("_reload_set"))  { send("open_set:"+gs("shopId","")+":"+gs("id","")); return; }
        if(action.equals("_delete_set"))  { openTextInput("Confirmer (tapez le nom)","",v->{ if(v.equals(gs("name",""))) send("set_delete:"+gs("shopId","")+":"+gs("id","")); }); return; }
        if(action.equals("_save_item"))   { send("item_save:"+gs("shopId","")+":"+gs("setId","")+":"+gs("id","")+"|"+buildItemJson()); return; }
        if(action.equals("_reload_item")) { send("open_item:"+gs("shopId","")+":"+gs("setId","")+":"+gs("id","")); return; }
        if(action.equals("_delete_item")) { send("item_delete:"+gs("shopId","")+":"+gs("setId","")+":"+gs("id","")); return; }
        send(action);
    }

    // ── JSON builders ─────────────────────────────────────────────────────────
    private String buildShopJson(){
        JsonObject j=new JsonObject();
        j.addProperty("name",iv.getOrDefault("name",""));
        j.addProperty("description",iv.getOrDefault("description",""));
        j.addProperty("displayMode",iv.getOrDefault("displayMode","TABS"));
        j.addProperty("showWelcomeMessage","true".equals(iv.get("showWelcomeMessage")));
        j.addProperty("welcomeMessage",iv.getOrDefault("welcomeMessage",""));
        j.addProperty("titleColor",iv.getOrDefault("titleColor","gold"));
        j.addProperty("bgColor",iv.getOrDefault("bgColor","1A1A2E"));
        j.addProperty("borderColor",iv.getOrDefault("borderColor","2A5A8A"));
        j.addProperty("accentColor",iv.getOrDefault("accentColor","4AC8EA"));
        j.addProperty("btnBuyColor",iv.getOrDefault("btnBuyColor","1A5C2A"));
        j.addProperty("btnBuyTextColor",iv.getOrDefault("btnBuyTextColor","FFFFFF"));
        j.addProperty("btnCloseColor",iv.getOrDefault("btnCloseColor","1E3A5F"));
        j.addProperty("btnCloseTextColor",iv.getOrDefault("btnCloseTextColor","CCCCCC"));
        return j.toString();
    }
    private String buildSetJson(){
        JsonObject j=new JsonObject(); j.addProperty("name",iv.getOrDefault("name",""));
        JsonObject r=new JsonObject(); r.addProperty("mode",iv.getOrDefault("rotMode","ALWAYS")); r.addProperty("scheduleStart",iv.getOrDefault("scheduleStart","08:00")); r.addProperty("scheduleEnd",iv.getOrDefault("scheduleEnd","20:00")); r.addProperty("manualActive","true".equals(iv.get("manualActive")));
        j.add("rotation",r); return j.toString();
    }
    private String buildItemJson(){
        JsonObject j=new JsonObject();
        j.addProperty("displayItem",iv.getOrDefault("displayItem",""));
        j.addProperty("displayName",iv.getOrDefault("displayName",""));
        j.addProperty("enabled","true".equals(iv.get("enabled")));
        JsonObject s=new JsonObject(); s.addProperty("mode",iv.getOrDefault("stockMode","UNLIMITED")); s.addProperty("globalStock",parseInt(iv.getOrDefault("globalStock","100"))); s.addProperty("perPlayerLimit",parseInt(iv.getOrDefault("perPlayerLimit","1"))); s.addProperty("resetType",iv.getOrDefault("resetType","DAILY")); s.addProperty("resetIntervalSeconds",parseInt(iv.getOrDefault("resetIntervalSecs","86400")));
        j.add("stock",s); return j.toString();
    }

    private void send(String a){ ClientPlayNetworking.send(new ShopActionPayload(a)); }

    // ── Name / icon helpers ───────────────────────────────────────────────────
    /** Get the first output's itemId from current server data (for auto-icon) */
    private String firstOutputIconFromData(){
        if(!data.has("outputs")) return "";
        JsonArray o=data.getAsJsonArray("outputs");
        if(o.isEmpty()) return "";
        return sp(o.get(0).getAsJsonObject(),"itemId","");
    }

    /** Effective icon for a grid item: displayStack (with components) → displayItem → first output */
    private static String effectiveIconAdmin(JsonObject item){
        // Prefer displayStack (full item string with bracket components) for correct model rendering
        String ds=sp(item,"displayStack",""); if(!ds.isBlank()) return ds;
        // Check first output for components
        if(item.has("outputs")){
            JsonArray out=item.getAsJsonArray("outputs");
            if(!out.isEmpty()){
                JsonObject first=out.get(0).getAsJsonObject();
                String comp=sp(first,"components","");
                if(!comp.isBlank()) return sp(first,"itemId","") + comp;
            }
        }
        String di=sp(item,"displayItem",""); if(!di.isBlank()) return di;
        if(item.has("outputs")){JsonArray out=item.getAsJsonArray("outputs"); if(!out.isEmpty()) return sp(out.get(0).getAsJsonObject(),"itemId","");}
        return "";
    }

    /** Effective display name for grid: custom displayName → translated icon name */
    private static String effectiveTranslatedName(JsonObject item){
        String dn=sp(item,"displayName",""); if(!dn.isBlank()) return dn;
        return getTranslatedName(effectiveIconAdmin(item));
    }

    /** MC translated name from item ID string */
    private static String getTranslatedName(String itemId){
        if(itemId==null||itemId.isBlank()) return "?";
        try {
            Identifier id=Identifier.tryParse(itemId); if(id==null) return shortRaw(itemId);
            Item item=Registries.ITEM.get(id); if(item==null) return shortRaw(itemId);
            return new ItemStack(item).getName().getString();
        } catch(Exception e){ return shortRaw(itemId); }
    }

    private static String shortRaw(String id){ if(id==null||id.isBlank()) return "?"; String s=id.contains(":")?id.substring(id.indexOf(':')+1):id; return s.replace('_',' '); }

    // ── Render helpers ────────────────────────────────────────────────────────
    private void btn(DrawContext ctx,int mx,int my,int x,int y,int w,int h,String lbl,int bg,int bgh,String action){
        boolean hov=hov(mx,my,x,y,w,h); ctx.fill(x,y,x+w,y+h,hov?bgh:bg); ctx.fill(x,y,x+w,y+1,0x33FFFFFF);
        dt(ctx,lbl,x+w/2-tw(lbl.replaceAll("§.",""))/2,y+h/2-4,C_TEXT);
        if(!action.isEmpty()) widgets.add(new Widget(x,y,w,h,action));
    }
    private void addZone(int x,int y,int w,int h,String a){ widgets.add(new Widget(x,y,w,h,a)); }
    private int sect(DrawContext ctx,int x,int y,int w,String t){ ctx.fill(x,y,x+w,y+18,C_PANEL); ctx.fill(x,y+17,x+w,y+18,C_BORDER); ctx.fill(x,y,x+3,y+18,C_TEXT_ACCENT); dt(ctx,t,x+7,y+3,C_TEXT_ACCENT); return y+20; }
    private int field(DrawContext ctx,int mx,int my,int x,int y,int w,String label,String key){
        dt(ctx,label+":",x,y,C_TEXT_DIM); String val=iv.getOrDefault(key,""); boolean foc=hov(mx,my,x,y+12,w,18);
        ctx.fill(x,y+12,x+w,y+30,C_INPUT_BG); ctx.fill(x,y+12,x+w,y+13,foc?C_TEXT_ACCENT:C_INPUT_BORDER);
        dt(ctx,val.isEmpty()?"§8—":truncate(val,50),x+4,y+16,val.isEmpty()?C_TEXT_DIM:C_TEXT);
        addZone(x,y+12,w,18,"_input:"+label+"|"+key+"|"+val); return y+33;
    }
    private int fieldSmall(DrawContext ctx,int mx,int my,int x,int y,int w,String label,String key){
        int lw=Math.min(w*3/5,tw(label)+6); dt(ctx,label+":",x,y+3,C_TEXT_DIM); String val=iv.getOrDefault(key,"");
        ctx.fill(x+lw,y,x+w,y+18,C_INPUT_BG); ctx.fill(x+lw,y,x+w,y+1,C_INPUT_BORDER);
        dt(ctx,val,x+lw+4,y+4,C_TEXT); addZone(x+lw,y,w-lw,18,"_input:"+label+"|"+key+"|"+val); return y+21;
    }
    /** Hex color field (shows colored swatch) */
    private int fieldHex(DrawContext ctx,int mx,int my,int x,int y,int w,String label,String key){
        int lw=tw(label+":")+4; dt(ctx,label+":",x,y+3,C_TEXT_DIM); String val=iv.getOrDefault(key,"");
        int fw=w-lw-22; ctx.fill(x+lw,y,x+lw+fw,y+18,C_INPUT_BG); ctx.fill(x+lw,y,x+lw+fw,y+1,C_INPUT_BORDER);
        dt(ctx,val,x+lw+4,y+4,C_TEXT);
        int swatchC=parseHex(val); ctx.fill(x+lw+fw+2,y+1,x+w,y+17,swatchC);
        addZone(x+lw,y,fw,18,"_input:"+label+"|"+key+"|"+val); return y+21;
    }
    private int cycleBtn(DrawContext ctx,int mx,int my,int x,int y,int w,String label,String key,String[] vals,String[] lbls){
        dt(ctx,label+":",x,y,C_TEXT_DIM); String cur=iv.getOrDefault(key,vals[0]); String curLbl=cur; int ni=0;
        for(int i=0;i<vals.length;i++) if(vals[i].equals(cur)){curLbl=lbls[i];ni=(i+1)%vals.length;break;}
        boolean hov=hov(mx,my,x,y+12,w,20); ctx.fill(x,y+12,x+w,y+32,hov?C_PANEL_LIGHT:C_PANEL); ctx.fill(x,y+12,x+w,y+13,C_BORDER);
        dt(ctx,curLbl+" ▾",x+4,y+17,hov?C_TEXT_ACCENT:C_TEXT); addZone(x,y+12,w,20,"field_cycle:"+key+":"+vals[ni]); return y+35;
    }
    private int tog(DrawContext ctx,int mx,int my,int x,int y,int w,String label,String key){
        boolean val="true".equals(iv.getOrDefault(key,"false")); boolean hov=hov(mx,my,x,y,w,20);
        if(hov) ctx.fill(x,y,x+w,y+20,C_ROW_A); dt(ctx,label,x,y+4,val?C_TEXT:C_TEXT_DIM);
        int tx=x+w-34; ctx.fill(tx,y+4,tx+28,y+16,val?0xFF1A7A1A:0xFF3A3A3A); ctx.fill(val?tx+15:tx+2,y+5,val?tx+25:tx+12,y+15,0xFFFFFFFF);
        addZone(x,y,w,20,"field_toggle:"+key); return y+22;
    }
    private int colPickMC(DrawContext ctx,int mx,int my,int x,int y,int w,String label,String key){
        dt(ctx,label+":",x,y,C_TEXT_DIM); String cur=iv.getOrDefault(key,"gold"); int c=parseMCColor(cur);
        ctx.fill(x,y+12,x+w-22,y+28,C_INPUT_BG); dt(ctx,cur,x+4,y+16,c); ctx.fill(x+w-20,y+12,x+w,y+28,c);
        String[] cols={"gold","yellow","green","dark_green","aqua","blue","dark_blue","light_purple","red","dark_red","white","gray"};
        int ni=0; for(int i=0;i<cols.length;i++) if(cols[i].equals(cur)){ni=(i+1)%cols.length;break;}
        addZone(x,y+12,w,16,"color_cycle:"+key+":"+cols[ni]); return y+32;
    }
    private void updateMaxScroll(int sY,int bot){ String k=scrollKey(); int t=bot-sY+40,v=contentH-8,m=Math.max(0,t-v); maxScrollY.put(k,m); scrollY.put(k,Math.min(scrollY.getOrDefault(k,0),m)); }
    private String scrollKey(){ return viewMode+":"+currentTab; }
    private void openTextInput(String l,String init,Consumer<String> cb){ MinecraftClient.getInstance().setScreen(new TextInput(this,l,init,cb)); }
    private void dt(DrawContext ctx,String t,int x,int y,int c){ if(textRenderer!=null) ctx.drawText(textRenderer,t,x,y,c,false); }
    private int  tw(String t){ return textRenderer!=null?textRenderer.getWidth(t):t.length()*6; }
    private static boolean hov(int mx,int my,int x,int y,int w,int h){ return mx>=x&&mx<x+w&&my>=y&&my<y+h; }
    private void renderTooltipBox(DrawContext ctx,String t,int x,int y){ int tw=tw(t)+8; int tx=Math.min(x,width-tw-4); ctx.fill(tx-2,y-2,tx+tw+2,y+11,0xFF1A1A2E); ctx.fill(tx-2,y-2,tx+tw+2,y-1,C_BORDER); dt(ctx,t,tx+2,y+1,C_TEXT); }

    private String gs(String k,String d){ return data.has(k)?data.get(k).getAsString():d; }
    private boolean gb(String k,boolean d){ return data.has(k)?data.get(k).getAsBoolean():d; }
    private static String sp(JsonObject o,String k,String d){ return o.has(k)?o.get(k).getAsString():d; }
    private void pi(String k,String v){ iv.put(k,v); }
    private static int parseInt(String s){ try{return Integer.parseInt(s.trim());}catch(Exception e){return 0;} }
    private static int safeCount(String s){ try{return Math.max(1,Integer.parseInt(s.trim()));}catch(Exception e){return 1;} }
    private static String truncate(String s,int m){ return s.length()>m?s.substring(0,m)+"…":s; }
    private static String displayModeLabel(String m){ return switch(m){case"TABS"->"Onglets";case"FLAT"->"Liste plate";case"FEATURED"->"Mise en avant";default->m;}; }
    private static String stockModeIcon(String m){ return switch(m){case"UNLIMITED"->"∞";case"GLOBAL"->"🌐";case"PER_PLAYER"->"👤";case"PER_PLAYER_RESET"->"🔄";default->"?";}; }
    private static int parseMCColor(String n){ return switch(n){case"black"->0xFF000000;case"dark_blue"->0xFF0000AA;case"dark_green"->0xFF00AA00;case"dark_aqua"->0xFF00AAAA;case"dark_red"->0xFFAA0000;case"dark_purple"->0xFFAA00AA;case"gold"->0xFFFFAA00;case"gray"->0xFFAAAAAA;case"dark_gray"->0xFF555555;case"blue"->0xFF5555FF;case"green"->0xFF55FF55;case"aqua"->0xFF55FFFF;case"red"->0xFFFF5555;case"light_purple"->0xFFFF55FF;case"yellow"->0xFFFFFF55;default->0xFFFFFFFF;}; }
    private static int parseHex(String h){ try{return 0xFF000000|(int)Long.parseLong(h.replaceAll("[^0-9A-Fa-f]",""),16);}catch(Exception e){return 0xFF2A5A8A;} }
    private static int applyAlpha(int color,int alpha){ return (color&0x00FFFFFF)|(alpha<<24); }

    private record Widget(int x,int y,int w,int h,String action){ boolean contains(int mx,int my){return mx>=x&&mx<x+w&&my>=y&&my<y+h;} }

    private static class TextInput extends Screen {
        private final Screen parent; private final String label; private final Consumer<String> cb; private final StringBuilder buf;
        TextInput(Screen p,String l,String init,Consumer<String> cb){ super(Text.literal("")); parent=p;label=l;this.cb=cb;buf=new StringBuilder(init); }
        @Override public void render(DrawContext ctx,int mx,int my,float d){
            ctx.fill(0,0,width,height,0xAA000000); int bw=380,bh=82,bx=width/2-bw/2,by=height/2-bh/2;
            ctx.fill(bx,by,bx+bw,by+bh,0xFF1A1A2E); ctx.fill(bx,by,bx+bw,by+1,0xFF4AC8EA); ctx.fill(bx,by+bh-1,bx+bw,by+bh,0xFF4AC8EA);
            if(textRenderer!=null){ ctx.drawText(textRenderer,label,bx+10,by+10,0xFF4AC8EA,false); ctx.fill(bx+10,by+28,bx+bw-10,by+50,0xFF0A1525); ctx.fill(bx+10,by+28,bx+bw-10,by+29,0xFF2A4A6A); String d2=buf.toString(); if(d2.length()>52) d2="…"+d2.substring(d2.length()-52); ctx.drawText(textRenderer,d2+"▌",bx+14,by+33,0xFFFFFFFF,false); ctx.drawText(textRenderer,"Entrée = valider   Echap = annuler",bx+10,by+60,0xFF667788,false); }
        }
        @Override public boolean keyPressed(int k,int s,int m){ if(k==257||k==335){cb.accept(buf.toString());MinecraftClient.getInstance().setScreen(parent);return true;} if(k==256){MinecraftClient.getInstance().setScreen(parent);return true;} if(k==259&&!buf.isEmpty()){buf.deleteCharAt(buf.length()-1);return true;} if(k==67&&hasControlDown()){buf.setLength(0);return true;} return super.keyPressed(k,s,m); }
        @Override public boolean charTyped(char c,int m){ if(c>=32){buf.append(c);return true;} return false; }
    }
}
