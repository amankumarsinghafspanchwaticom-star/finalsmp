package com.tearssmp.mythic.bag;

import com.tearssmp.mythic.TearsMythicPlugin;
import com.tearssmp.mythic.core.MythicItemRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.entity.LingeringPotion;
import org.bukkit.entity.Player;
import org.bukkit.entity.SplashPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ClericsBagModule implements Listener {

    public static final int CAPACITY = 16;
    public static final int GUI_SIZE = 27;
    public static final int BTN_CYCLE = 18;
    public static final int BTN_REVERSE = 20;
    public static final int BTN_INFO = 22;
    public static final int BTN_COMPACT = 24;
    public static final int BTN_CLOSE = 26;

    private final TearsMythicPlugin plugin;
    private final MythicItemRegistry registry;
    private final NamespacedKey uidKey;
    private final NamespacedKey contentsKey;
    private final NamespacedKey recipeKey;

    private final Map<UUID, String> openSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastThrowTime = new ConcurrentHashMap<>();
    private UUID lastWielderUuid = null;

    public ClericsBagModule(TearsMythicPlugin plugin, MythicItemRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.uidKey = new NamespacedKey(plugin, "clerics_bag_uid");
        this.contentsKey = new NamespacedKey(plugin, "clerics_bag_contents");
        this.recipeKey = new NamespacedKey(plugin, "clerics_bag_recipe");
    }

    public int getMaxPotions() {
        return Math.max(1, Math.min(CAPACITY, plugin.getConfig().getInt("clerics-bag.max-potions", CAPACITY)));
    }

    public void enable() {
        registerRecipe();
    }

    public void reload() {
        registerRecipe();
    }

    public void disable() {
        for (UUID pid : new ArrayList<>(openSessions.keySet())) {
            Player p = Bukkit.getPlayer(pid);
            if (p != null && p.isOnline()) {
                Inventory top = p.getOpenInventory().getTopInventory();
                if (top.getHolder() instanceof BagGuiHolder h) {
                    saveGuiToBag(p, top, h.getBagUid());
                }
                p.closeInventory();
            }
        }
        openSessions.clear();
        Bukkit.removeRecipe(recipeKey);
    }

    @SuppressWarnings("deprecation")
    public void registerRecipe() {
        Bukkit.removeRecipe(recipeKey);
        if (!plugin.getConfig().getBoolean("clerics-bag.crafting.enabled", true)) return;

        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createBag(Material.BUNDLE));
        recipe.shape("RBR", "WUW", "DXD");
        recipe.setIngredient('R', Material.BLAZE_ROD);
        recipe.setIngredient('B', Material.BREWING_STAND);
        recipe.setIngredient('W', Material.NETHER_WART);
        recipe.setIngredient('U', new RecipeChoice.MaterialChoice(Tag.ITEMS_BUNDLES));
        recipe.setIngredient('D', Material.DISPENSER);
        recipe.setIngredient('X', Material.DIAMOND_BLOCK);

        Bukkit.addRecipe(recipe);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.discoverRecipe(recipeKey);
        }
    }

    public ItemStack createBag(Material bundleColorMat) {
        Material mat = (bundleColorMat != null && Tag.ITEMS_BUNDLES.isTagged(bundleColorMat))
                ? bundleColorMat : Material.BUNDLE;
        ItemStack bag = new ItemStack(mat, 1);
        ItemMeta meta = bag.getItemMeta();
        if (meta == null) return bag;

        if (meta instanceof BundleMeta bm) bm.setItems(List.of());
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(registry.getBagKey(), PersistentDataType.BYTE, (byte) 1);
        pdc.set(uidKey, PersistentDataType.STRING, UUID.randomUUID().toString());
        bag.setItemMeta(meta);

        writeSlots(bag, createEmptySlots());
        return bag;
    }

    public boolean isBag(ItemStack item) {
        return registry.identify(item) == MythicItemRegistry.MythicType.CLERICS_BAG;
    }

    public boolean isPotion(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        Material t = item.getType();
        return t == Material.POTION || t == Material.SPLASH_POTION || t == Material.LINGERING_POTION;
    }

    public String getOrAssignUid(ItemStack bag) {
        if (!isBag(bag)) return null;
        ItemMeta meta = bag.getItemMeta();
        if (meta == null) return null;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String uid = pdc.get(uidKey, PersistentDataType.STRING);
        if (uid == null || uid.isBlank()) {
            uid = UUID.randomUUID().toString();
            pdc.set(uidKey, PersistentDataType.STRING, uid);
            bag.setItemMeta(meta);
        }
        return uid;
    }

    public boolean ejectVanillaBundleItems(Player player, ItemStack bag) {
        if (!isBag(bag)) return false;
        ItemMeta meta = bag.getItemMeta();
        if (!(meta instanceof BundleMeta bm) || !bm.hasItems()) return false;
        List<ItemStack> trapped = new ArrayList<>(bm.getItems());
        bm.setItems(List.of());
        bag.setItemMeta(bm);
        if (player != null && player.isOnline()) {
            for (ItemStack item : trapped) {
                if (item != null && !item.getType().isAir()) {
                    Map<Integer, ItemStack> ov = player.getInventory().addItem(item.clone());
                    ov.values().forEach(d -> player.getWorld().dropItemNaturally(player.getLocation(), d));
                }
            }
        }
        return !trapped.isEmpty();
    }

    public ItemStack[] createEmptySlots() {
        ItemStack[] arr = new ItemStack[CAPACITY];
        for (int i = 0; i < CAPACITY; i++) arr[i] = new ItemStack(Material.AIR);
        return arr;
    }

    public ItemStack[] readSlots(ItemStack bag) {
        ItemStack[] res = createEmptySlots();
        if (!isBag(bag)) return res;
        ItemMeta meta = bag.getItemMeta();
        if (meta == null) return res;
        byte[] raw = meta.getPersistentDataContainer().get(contentsKey, PersistentDataType.BYTE_ARRAY);
        if (raw == null || raw.length == 0) return res;
        try {
            ItemStack[] dec = ItemStack.deserializeItemsFromBytes(raw);
            int lim = Math.min(CAPACITY, dec.length);
            for (int i = 0; i < lim; i++) {
                if (isPotion(dec[i])) {
                    ItemStack single = dec[i].clone();
                    single.setAmount(1);
                    res[i] = single;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Deserialize Cleric's Bag error: " + e.getMessage());
        }
        return res;
    }

    public void writeSlots(ItemStack bag, ItemStack[] slots) {
        if (!isBag(bag)) return;
        int max = getMaxPotions();
        ItemStack[] clean = createEmptySlots();
        int count = 0;
        if (slots != null) {
            for (int i = 0; i < Math.min(CAPACITY, slots.length); i++) {
                if (count < max && isPotion(slots[i])) {
                    ItemStack single = slots[i].clone();
                    single.setAmount(1);
                    clean[i] = single;
                    count++;
                }
            }
        }

        ItemMeta meta = bag.getItemMeta();
        if (meta == null) return;
        if (meta instanceof BundleMeta bm) bm.setItems(List.of());

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(registry.getBagKey(), PersistentDataType.BYTE, (byte) 1);
        if (!pdc.has(uidKey, PersistentDataType.STRING)) {
            pdc.set(uidKey, PersistentDataType.STRING, UUID.randomUUID().toString());
        }
        pdc.set(contentsKey, PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(clean));

        String rawName = plugin.getConfig().getString("clerics-bag.name", "<gold><bold>Cleric's Bag</bold></gold>");
        meta.displayName(plugin.parse(rawName).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        meta.setEnchantmentGlintOverride(plugin.getConfig().getBoolean("clerics-bag.enchantment-glint", true));

        List<Component> lore = new ArrayList<>();
        List<ItemStack> ordered = orderedPotions(clean);
        int preview = Math.max(1, plugin.getConfig().getInt("clerics-bag.lore-preview-count", 5));

        if (ordered.isEmpty()) {
            lore.add(noItalic("<gray>Potions: <white>0/" + max + "</white> <dark_gray>(Empty)</dark_gray>"));
        } else {
            lore.add(noItalic("<gray>Potions: <white>" + ordered.size() + "/" + max + "</white> <dark_gray>| Order:</dark_gray>"));
            int show = Math.min(preview, ordered.size());
            for (int i = 0; i < show; i++) {
                lore.add(noItalic((i == 0 ? "<yellow>▸ #1 " : "<gray>  #" + (i + 1) + " ")
                        + "<white>" + describePotion(ordered.get(i)) + "</white>" + (i == 0 ? "</yellow>" : "</gray>")));
            }
            if (ordered.size() > show) {
                lore.add(noItalic("<dark_gray>  +" + (ordered.size() - show) + " more...</dark_gray>"));
            }
        }
        lore.add(Component.empty());
        lore.add(noItalic("<gold>Right-Click:</gold> <gray>Throw next potion</gray>"));
        lore.add(noItalic("<aqua>Shift + Right-Click:</aqua> <gray>Open Potion Menu</gray>"));
        lore.add(noItalic("<dark_gray>Potions Only — Inventory Only</dark_gray>"));
        meta.lore(lore);
        bag.setItemMeta(meta);
    }

    private Component noItalic(String s) {
        return plugin.parse(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public List<ItemStack> orderedPotions(ItemStack[] slots) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack it : slots) {
            if (isPotion(it)) {
                ItemStack s = it.clone();
                s.setAmount(1);
                out.add(s);
            }
        }
        return out;
    }

    public void openGui(Player player, ItemStack bag) {
        if (player == null || !isBag(bag)) return;
        ejectVanillaBundleItems(player, bag);
        String uid = getOrAssignUid(bag);
        if (uid == null) return;

        BagGuiHolder holder = new BagGuiHolder(uid);
        Inventory inv = Bukkit.createInventory(holder, GUI_SIZE, plugin.parse("<dark_gray>Cleric's Bag <dark_gray>»</dark_gray> <gold>Potion Order</gold></dark_gray>"));
        holder.setInventory(inv);

        ItemStack[] slots = readSlots(bag);
        for (int i = 0; i < CAPACITY; i++) {
            inv.setItem(i, isPotion(slots[i]) ? slots[i].clone() : null);
        }
        paintBar(inv, slots);
        openSessions.put(player.getUniqueId(), uid);
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.ITEM_BUNDLE_INSERT, 0.85f, 1.15f);
    }

    private void paintBar(Inventory inv, ItemStack[] slots) {
        ItemStack pane = btn(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        inv.setItem(16, pane);
        inv.setItem(17, pane);
        for (int s = 18; s < GUI_SIZE; s++) inv.setItem(s, pane);

        inv.setItem(BTN_CYCLE, btn(Material.HOPPER, "<aqua><bold>Cycle Queue (+1)</bold></aqua>",
                List.of("<gray>Moves #1 to the back so #2 throws next.</gray>")));
        inv.setItem(BTN_REVERSE, btn(Material.ARROW, "<light_purple><bold>Reverse Order</bold></light_purple>",
                List.of("<gray>Flips potion order without emptying.</gray>")));

        List<ItemStack> ord = orderedPotions(slots);
        List<String> info = new ArrayList<>();
        info.add("<gray>Loaded: <white>" + ord.size() + "/" + getMaxPotions() + "</white></gray>");
        info.add("<gray>Slots <white>1-16</white> throw left-to-right.</gray>");
        for (int i = 0; i < Math.min(5, ord.size()); i++) {
            info.add("<yellow>#" + (i + 1) + " <white>" + describePotion(ord.get(i)) + "</white></yellow>");
        }
        inv.setItem(BTN_INFO, btn(Material.BREWING_STAND, "<gold><bold>Throw Order</bold></gold>", info));

        inv.setItem(BTN_COMPACT, btn(Material.COMPARATOR, "<green><bold>Compact Gaps</bold></green>",
                List.of("<gray>Packs potions left keeping exact order.</gray>")));
        inv.setItem(BTN_CLOSE, btn(Material.BARRIER, "<red><bold>Save & Close</bold></red>",
                List.of("<gray>Saves potion order.</gray>")));
    }

    private ItemStack btn(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat, 1);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.displayName(noItalic(name));
            if (!lore.isEmpty()) {
                m.lore(lore.stream().map(this::noItalic).toList());
            }
            it.setItemMeta(m);
        }
        return it;
    }

    private ItemStack[] extractGuiSlots(Inventory inv) {
        ItemStack[] arr = createEmptySlots();
        for (int i = 0; i < CAPACITY; i++) {
            ItemStack item = inv.getItem(i);
            if (isPotion(item)) {
                ItemStack s = item.clone();
                s.setAmount(1);
                arr[i] = s;
            }
        }
        return arr;
    }

    private void saveGuiToBag(Player player, Inventory inv, String uid) {
        ItemStack bag = findBag(player, uid);
        if (bag != null) {
            writeSlots(bag, extractGuiSlots(inv));
        }
    }

    private ItemStack findBag(Player p, String uid) {
        if (p == null || uid == null) return null;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (isBag(it) && uid.equals(getOrAssignUid(it))) return it;
        }
        if (isBag(inv.getItemInOffHand()) && uid.equals(getOrAssignUid(inv.getItemInOffHand()))) return inv.getItemInOffHand();
        if (isBag(p.getItemOnCursor()) && uid.equals(getOrAssignUid(p.getItemOnCursor()))) return p.getItemOnCursor();
        return null;
    }

    private void launchPotion(Player thrower, ItemStack pot) {
        double speed = plugin.getConfig().getDouble("clerics-bag.throw-speed", 1.25);
        Vector vel = thrower.getEyeLocation().getDirection().normalize().multiply(speed);
        if (pot.getType() == Material.LINGERING_POTION) {
            ItemStack l = pot.clone();
            l.setAmount(1);
            LingeringPotion ent = thrower.launchProjectile(LingeringPotion.class, vel);
            ent.setShooter(thrower);
            ent.setItem(l);
        } else {
            ItemStack sp = toSplash(pot);
            SplashPotion ent = thrower.launchProjectile(SplashPotion.class, vel);
            ent.setShooter(thrower);
            ent.setItem(sp);
        }
        if (plugin.getConfig().getBoolean("clerics-bag.show-particles", true)) {
            thrower.getWorld().spawnParticle(Particle.ENTITY_EFFECT,
                    thrower.getEyeLocation().add(thrower.getEyeLocation().getDirection().multiply(0.6)),
                    8, 0.15, 0.15, 0.15, resolveColor(pot));
        }
    }

    private ItemStack toSplash(ItemStack src) {
        if (src.getType() == Material.SPLASH_POTION) {
            ItemStack c = src.clone();
            c.setAmount(1);
            return c;
        }
        ItemStack sp = new ItemStack(Material.SPLASH_POTION, 1);
        if (src.hasItemMeta() && src.getItemMeta() instanceof PotionMeta pm && sp.getItemMeta() instanceof PotionMeta dm) {
            if (pm.getBasePotionType() != null) dm.setBasePotionType(pm.getBasePotionType());
            for (PotionEffect eff : pm.getCustomEffects()) dm.addCustomEffect(eff, true);
            if (pm.hasColor()) dm.setColor(pm.getColor());
            if (pm.hasDisplayName()) dm.displayName(pm.displayName());
            sp.setItemMeta(dm);
        }
        return sp;
    }

    private Color resolveColor(ItemStack pot) {
        if (pot.hasItemMeta() && pot.getItemMeta() instanceof PotionMeta pm) {
            if (pm.hasColor() && pm.getColor() != null) return pm.getColor();
            if (!pm.getCustomEffects().isEmpty()) return pm.getCustomEffects().get(0).getType().getColor();
            if (pm.getBasePotionType() != null && !pm.getBasePotionType().getPotionEffects().isEmpty()) {
                return pm.getBasePotionType().getPotionEffects().get(0).getType().getColor();
            }
        }
        return Color.fromRGB(160, 100, 255);
    }

    public String describePotion(ItemStack pot) {
        if (pot == null || pot.getType().isAir()) return "Empty";
        String prefix = switch (pot.getType()) {
            case SPLASH_POTION -> "Splash ";
            case LINGERING_POTION -> "Lingering ";
            default -> "";
        };
        if (pot.hasItemMeta() && pot.getItemMeta() instanceof PotionMeta pm) {
            if (pm.hasDisplayName() && pm.displayName() != null) {
                String txt = PlainTextComponentSerializer.plainText().serialize(pm.displayName());
                if (!txt.isBlank()) return txt;
            }
            if (pm.getBasePotionType() != null) {
                return prefix + formatEnum(pm.getBasePotionType().name());
            }
        }
        return formatEnum(pot.getType().name());
    }

    private String formatEnum(String raw) {
        String[] parts = raw.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    // --- Events ---
    @EventHandler(priority = EventPriority.HIGH)
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND && event.getHand() != EquipmentSlot.OFF_HAND) return;
        Action act = event.getAction();
        if (act != Action.RIGHT_CLICK_AIR && act != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack bag = event.getItem();
        if (!isBag(bag)) return;

        event.setCancelled(true);
        Player p = event.getPlayer();
        ejectVanillaBundleItems(p, bag);

        if (p.isSneaking()) {
            openGui(p, bag);
            return;
        }

        long now = System.currentTimeMillis();
        long cd = plugin.getConfig().getLong("clerics-bag.throw-cooldown-ms", 150L);
        Long prev = lastThrowTime.put(p.getUniqueId(), now);
        if (prev != null && (now - prev) < cd) return;

        List<ItemStack> ord = orderedPotions(readSlots(bag));
        if (ord.isEmpty()) {
            p.sendActionBar(plugin.rawMsg("empty-bag"));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.8f);
            return;
        }

        ItemStack thrown = ord.remove(0);
        ItemStack next = ord.isEmpty() ? null : ord.get(0);
        ItemStack[] upd = createEmptySlots();
        for (int i = 0; i < ord.size(); i++) upd[i] = ord.get(i);
        writeSlots(bag, upd);

        launchPotion(p, thrown);
        p.playSound(p.getLocation(), Sound.ENTITY_SPLASH_POTION_THROW, 0.9f, 1.05f);

        String name = describePotion(thrown);
        if (next != null) {
            p.sendActionBar(plugin.parse(plugin.getConfig().getString("messages.threw-potion", "")
                    .replace("{potion}", name)
                    .replace("{next}", describePotion(next))
                    .replace("{remaining}", String.valueOf(ord.size()))
                    .replace("{max}", String.valueOf(getMaxPotions()))));
        } else {
            p.sendActionBar(plugin.parse(plugin.getConfig().getString("messages.threw-last-potion", "")
                    .replace("{potion}", name)));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCreative(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (isBag(event.getCursor()) && ejectVanillaBundleItems(p, event.getCursor())) {
            event.setCursor(event.getCursor());
            p.sendActionBar(plugin.rawMsg("menu-only"));
            Bukkit.getScheduler().runTask(plugin, p::updateInventory);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;

        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof BagGuiHolder holder) {
            handleGui(event, p, top, holder);
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        ClickType click = event.getClick();
        boolean cBag = isBag(clicked);
        boolean uBag = isBag(cursor);
        boolean cNonEmpty = clicked != null && !clicked.getType().isAir();
        boolean uNonEmpty = cursor != null && !cursor.getType().isAir();

        if (cBag && uNonEmpty) {
            if (click == ClickType.LEFT) {
                event.setCancelled(true);
                if (canSwap(event)) {
                    ItemStack bCopy = clicked.clone();
                    ItemStack cCopy = cursor.clone();
                    event.setCurrentItem(cCopy);
                    p.setItemOnCursor(bCopy);
                    if (isPotion(cCopy)) p.sendActionBar(plugin.rawMsg("menu-only"));
                } else {
                    p.sendActionBar(plugin.rawMsg("menu-only"));
                }
                Bukkit.getScheduler().runTask(plugin, p::updateInventory);
                return;
            }
            if (click == ClickType.RIGHT) {
                event.setCancelled(true);
                p.sendActionBar(plugin.rawMsg("menu-only"));
                Bukkit.getScheduler().runTask(plugin, p::updateInventory);
                return;
            }
        }

        if (uBag && cNonEmpty) {
            if (click == ClickType.LEFT) {
                event.setCancelled(true);
                if (canSwap(event)) {
                    ItemStack sCopy = clicked.clone();
                    ItemStack bCopy = cursor.clone();
                    event.setCurrentItem(bCopy);
                    p.setItemOnCursor(sCopy);
                    if (isPotion(sCopy)) p.sendActionBar(plugin.rawMsg("menu-only"));
                } else {
                    p.sendActionBar(plugin.rawMsg("menu-only"));
                }
                Bukkit.getScheduler().runTask(plugin, p::updateInventory);
                return;
            }
            if (click == ClickType.RIGHT) {
                event.setCancelled(true);
                p.sendActionBar(plugin.rawMsg("menu-only"));
                Bukkit.getScheduler().runTask(plugin, p::updateInventory);
                return;
            }
        }

        if (cBag && !uNonEmpty && click == ClickType.RIGHT) {
            event.setCancelled(true);
            ejectVanillaBundleItems(p, clicked);
            Bukkit.getScheduler().runTask(plugin, () -> openGui(p, clicked));
        }
    }

    private boolean canSwap(InventoryClickEvent e) {
        if (e.getClickedInventory() == null) return false;
        InventoryType.SlotType st = e.getSlotType();
        return st == InventoryType.SlotType.CONTAINER || st == InventoryType.SlotType.QUICKBAR;
    }

    private void handleGui(InventoryClickEvent event, Player p, Inventory top, BagGuiHolder holder) {
        String uid = holder.getBagUid();
        if (lockedBag(event.getCurrentItem(), uid) || lockedBag(event.getCursor(), uid)) {
            event.setCancelled(true);
            return;
        }
        if (event.getClick() == ClickType.NUMBER_KEY && lockedBag(p.getInventory().getItem(event.getHotbarButton()), uid)) {
            event.setCancelled(true);
            return;
        }
        if (event.getClick() == ClickType.SWAP_OFFHAND && lockedBag(p.getInventory().getItemInOffHand(), uid)) {
            event.setCancelled(true);
            return;
        }

        int raw = event.getRawSlot();
        boolean inTop = raw >= 0 && raw < top.getSize();

        if (inTop && raw >= CAPACITY) {
            event.setCancelled(true);
            ItemStack[] cur = extractGuiSlots(top);
            List<ItemStack> ord = orderedPotions(cur);
            if (raw == BTN_CYCLE && ord.size() > 1) {
                ord.add(ord.remove(0));
                applyOrd(top, p, uid, ord);
            } else if (raw == BTN_REVERSE && ord.size() > 1) {
                List<ItemStack> rev = new ArrayList<>(ord.size());
                for (int i = ord.size() - 1; i >= 0; i--) rev.add(ord.get(i));
                applyOrd(top, p, uid, rev);
            } else if (raw == BTN_COMPACT) {
                applyOrd(top, p, uid, ord);
            } else if (raw == BTN_CLOSE) {
                p.closeInventory();
            }
            return;
        }

        if (inTop) {
            if (raw >= getMaxPotions()) {
                event.setCancelled(true);
                return;
            }
            if (event.getClick() == ClickType.NUMBER_KEY) {
                ItemStack hb = p.getInventory().getItem(event.getHotbarButton());
                if (hb != null && !hb.getType().isAir() && (!isPotion(hb) || hb.getAmount() > 1)) {
                    event.setCancelled(true);
                    p.sendActionBar(plugin.rawMsg("potions-only"));
                    return;
                }
            } else if (event.getClick() == ClickType.SWAP_OFFHAND) {
                ItemStack oh = p.getInventory().getItemInOffHand();
                if (!oh.getType().isAir() && (!isPotion(oh) || oh.getAmount() > 1)) {
                    event.setCancelled(true);
                    p.sendActionBar(plugin.rawMsg("potions-only"));
                    return;
                }
            } else {
                ItemStack cur = event.getCursor();
                if (cur != null && !cur.getType().isAir() && !isPotion(cur)) {
                    event.setCancelled(true);
                    p.sendActionBar(plugin.rawMsg("potions-only"));
                    return;
                }
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) {
                    paintBar(top, extractGuiSlots(top));
                    saveGuiToBag(p, top, uid);
                }
            });
            return;
        }

        if (event.isShiftClick()) {
            event.setCancelled(true);
            ItemStack moving = event.getCurrentItem();
            if (!isPotion(moving)) {
                if (moving != null && !moving.getType().isAir()) p.sendActionBar(plugin.rawMsg("potions-only"));
                return;
            }
            int free = -1;
            for (int i = 0; i < getMaxPotions(); i++) {
                ItemStack slot = top.getItem(i);
                if (slot == null || slot.getType().isAir()) {
                    free = i;
                    break;
                }
            }
            if (free == -1) {
                p.sendActionBar(plugin.parse(plugin.getConfig().getString("messages.bag-full", "")
                        .replace("{max}", String.valueOf(getMaxPotions()))));
                return;
            }
            ItemStack single = moving.clone();
            single.setAmount(1);
            top.setItem(free, single);
            moving.setAmount(moving.getAmount() - 1);
            if (moving.getAmount() <= 0) event.setCurrentItem(new ItemStack(Material.AIR));
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) {
                    paintBar(top, extractGuiSlots(top));
                    saveGuiToBag(p, top, uid);
                }
            });
        }
    }

    private void applyOrd(Inventory top, Player p, String uid, List<ItemStack> ord) {
        ItemStack[] arr = createEmptySlots();
        for (int i = 0; i < Math.min(getMaxPotions(), ord.size()); i++) arr[i] = ord.get(i);
        for (int i = 0; i < CAPACITY; i++) top.setItem(i, isPotion(arr[i]) ? arr[i].clone() : null);
        paintBar(top, arr);
        saveGuiToBag(p, top, uid);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.25f);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof BagGuiHolder h)) return;
        if (lockedBag(event.getOldCursor(), h.getBagUid())) {
            event.setCancelled(true);
            return;
        }
        boolean touchesTop = false;
        for (int raw : event.getRawSlots()) {
            if (raw < top.getSize()) {
                touchesTop = true;
                if (raw >= getMaxPotions() || !isPotion(event.getOldCursor())) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
        if (touchesTop) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) {
                    paintBar(top, extractGuiSlots(top));
                    saveGuiToBag(p, top, h.getBagUid());
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player p && event.getInventory().getHolder() instanceof BagGuiHolder h) {
            saveGuiToBag(p, event.getInventory(), h.getBagUid());
            openSessions.remove(p.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDropOpen(PlayerDropItemEvent e) {
        String uid = openSessions.get(e.getPlayer().getUniqueId());
        if (uid != null && lockedBag(e.getItemDrop().getItemStack(), uid)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapOpen(PlayerSwapHandItemsEvent e) {
        String uid = openSessions.get(e.getPlayer().getUniqueId());
        if (uid != null && (lockedBag(e.getMainHandItem(), uid) || lockedBag(e.getOffHandItem(), uid))) e.setCancelled(true);
    }

    @EventHandler public void onDeath(PlayerDeathEvent e) {
        Inventory top = e.getEntity().getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof BagGuiHolder h) {
            saveGuiToBag(e.getEntity(), top, h.getBagUid());
            openSessions.remove(e.getEntity().getUniqueId());
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        lastThrowTime.remove(e.getPlayer().getUniqueId());
        Inventory top = e.getPlayer().getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof BagGuiHolder h) {
            saveGuiToBag(e.getPlayer(), top, h.getBagUid());
            openSessions.remove(e.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBagPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (isBag(e.getItem().getItemStack()) && !p.getUniqueId().equals(lastWielderUuid)) {
            lastWielderUuid = p.getUniqueId();
            registry.broadcastWielder(MythicItemRegistry.MythicType.CLERICS_BAG, p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoinSync(PlayerJoinEvent e) {
        registerRecipe();
        if (hasBag(e.getPlayer()) && !e.getPlayer().getUniqueId().equals(lastWielderUuid)) {
            lastWielderUuid = e.getPlayer().getUniqueId();
            registry.broadcastWielder(MythicItemRegistry.MythicType.CLERICS_BAG, e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBagClickWielder(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && hasBag(p) && !p.getUniqueId().equals(lastWielderUuid)) {
                lastWielderUuid = p.getUniqueId();
                registry.broadcastWielder(MythicItemRegistry.MythicType.CLERICS_BAG, p);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepCraft(PrepareItemCraftEvent event) {
        Recipe r = event.getRecipe();
        if (!(r instanceof Keyed keyed) || !keyed.getKey().equals(recipeKey)) return;
        CraftingInventory inv = event.getInventory();
        ItemStack[] m = inv.getMatrix();
        if (m.length < 9) return;
        ItemStack center = m[4];
        if (center == null || !Tag.ITEMS_BUNDLES.isTagged(center.getType()) || isBag(center)) {
            inv.setResult(null);
            return;
        }
        if (plugin.getConfig().getBoolean("clerics-bag.crafting.require-empty-bundle", true)
                && center.hasItemMeta() && center.getItemMeta() instanceof BundleMeta bm && bm.hasItems()) {
            inv.setResult(null);
            return;
        }
        inv.setResult(createBag(center.getType()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrafted(CraftItemEvent event) {
        Recipe r = event.getRecipe();
        if (!(r instanceof Keyed keyed) || !keyed.getKey().equals(recipeKey)) return;
        if (event.getWhoClicked() instanceof Player p) {
            lastWielderUuid = p.getUniqueId();
            registry.broadcastWielder(MythicItemRegistry.MythicType.CLERICS_BAG, p);
        }
    }

    private boolean hasBag(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isBag(it)) return true;
        }
        return isBag(p.getItemOnCursor());
    }

    private boolean lockedBag(ItemStack it, String uid) {
        return uid != null && isBag(it) && uid.equals(getOrAssignUid(it));
    }
}
