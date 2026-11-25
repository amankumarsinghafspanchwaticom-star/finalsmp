package com.tearssmp.mythic.listener;

import com.tearssmp.mythic.TearsMythicPlugin;
import com.tearssmp.mythic.core.MythicItemRegistry;
import com.tearssmp.mythic.core.ResourcePackModule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.ChestBoat;
import org.bukkit.entity.ChestedHorse;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

public final class MythicRestrictionListener implements Listener {

    private final TearsMythicPlugin plugin;
    private final MythicItemRegistry registry;
    private final ResourcePackModule resourcePackModule;

    public MythicRestrictionListener(
            TearsMythicPlugin plugin,
            MythicItemRegistry registry,
            ResourcePackModule resourcePackModule
    ) {
        this.plugin = plugin;
        this.registry = registry;
        this.resourcePackModule = resourcePackModule;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                resourcePackModule.sendPack(player);
            }
        }, 15L);
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        PlayerResourcePackStatusEvent.Status status = event.getStatus();
        if ((status == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD
                || status == PlayerResourcePackStatusEvent.Status.INVALID_URL)
                && player.hasPermission("tearssmp.admin.pack")
                && !resourcePackModule.isUsingCustomUrl()) {
            player.sendMessage(plugin.parse(
                    "<dark_red>[Tears SMP]</dark_red> <red>Resource pack download failed (port "
                            + plugin.getConfig().getInt("resource-pack.port", 25580) + " blocked by host).</red> "
                            + "<yellow>Upload <white>plugins/TearsSMPMythicItem/TearsSMP-ResourcePack.zip</white> to "
                            + "<white>mc-packs.net</white> and set <white>resource-pack.custom-url</white> in config.yml.</yellow>"
            ));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onContainerBreak(BlockBreakEvent event) {
        if (!plugin.getConfig().getBoolean("global.inventory-only-storage", true)) {
            return;
        }
        Block block = event.getBlock();
        if (block.getState() instanceof Container container) {
            for (ItemStack item : container.getInventory().getContents()) {
                if (registry.isMythicItem(item)) {
                    event.setCancelled(true);
                    event.getPlayer().sendActionBar(plugin.rawMsg("container-blocked"));
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlaceContainer(BlockPlaceEvent event) {
        if (!plugin.getConfig().getBoolean("global.inventory-only-storage", true)) {
            return;
        }
        ItemStack inHand = event.getItemInHand();
        if (!inHand.hasItemMeta()) {
            return;
        }
        if (inHand.getItemMeta() instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof Container container) {
            for (ItemStack item : container.getInventory().getContents()) {
                if (registry.isMythicItem(item)) {
                    event.setCancelled(true);
                    event.getPlayer().sendActionBar(plugin.rawMsg("container-blocked"));
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!plugin.getConfig().getBoolean("global.inventory-only-storage", true)) {
            return;
        }
        if (!(event.getRightClicked() instanceof ItemFrame)) {
            return;
        }
        Player player = event.getPlayer();
        if (registry.isMythicItem(player.getInventory().getItemInMainHand())
                || registry.isMythicItem(player.getInventory().getItemInOffHand())) {
            event.setCancelled(true);
            denyStorage(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (plugin.getConfig().getBoolean("global.prevent-rename", true)
                && event.getInventory() instanceof AnvilInventory inv
                && event.getRawSlot() == 2) {
            ItemStack first = inv.getFirstItem();
            ItemStack second = inv.getSecondItem();
            ItemStack clicked = event.getCurrentItem();

            if (registry.isMythicItem(first) || registry.isMythicItem(second) || registry.isMythicItem(clicked)) {
                boolean allowedAxeRepair = registry.identify(first) == MythicItemRegistry.MythicType.ARACHNAID_AXE
                        && plugin.getConfig().getBoolean("arachnaid-axe.allow-netherite-repair", true)
                        && second != null && second.getType() == Material.NETHERITE_INGOT
                        && sameDisplayName(first, clicked);

                if (!allowedAxeRepair) {
                    event.setCancelled(true);
                    player.sendActionBar(plugin.rawMsg("rename-blocked"));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.75f);
                    return;
                }
            }
        }

        if (!plugin.getConfig().getBoolean("global.inventory-only-storage", true)) {
            return;
        }

        Inventory top = event.getView().getTopInventory();
        if (isBlockedContainer(top)) {
            int rawSlot = event.getRawSlot();
            boolean clickedTop = rawSlot >= 0 && rawSlot < top.getSize();

            if (clickedTop) {
                if (registry.isMythicItem(event.getCursor()) || isSwappedMythic(event, player)) {
                    event.setCancelled(true);
                    denyStorage(player);
                    return;
                }
            } else {
                if (event.isShiftClick() && registry.isMythicItem(event.getCurrentItem())) {
                    event.setCancelled(true);
                    denyStorage(player);
                    return;
                }
            }
        }

        ItemStack clicked = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        boolean clickedIsBundle = clicked != null && Tag.ITEMS_BUNDLES.isTagged(clicked.getType());
        boolean cursorIsBundle = cursor != null && Tag.ITEMS_BUNDLES.isTagged(cursor.getType());

        if (clickedIsBundle && registry.isMythicItem(cursor)
                && (event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT)) {
            event.setCancelled(true);
            denyStorage(player);
            return;
        }
        if (cursorIsBundle && registry.isMythicItem(clicked)
                && (event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT)) {
            event.setCancelled(true);
            denyStorage(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!plugin.getConfig().getBoolean("global.inventory-only-storage", true)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!registry.isMythicItem(event.getOldCursor())) {
            return;
        }

        Inventory top = event.getView().getTopInventory();
        boolean blockedTop = isBlockedContainer(top);

        for (int rawSlot : event.getRawSlots()) {
            if (blockedTop && rawSlot < top.getSize()) {
                event.setCancelled(true);
                denyStorage(player);
                return;
            }
            ItemStack target = event.getView().getItem(rawSlot);
            if (target != null && Tag.ITEMS_BUNDLES.isTagged(target.getType())) {
                event.setCancelled(true);
                denyStorage(player);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!plugin.getConfig().getBoolean("global.prevent-rename", true)) {
            return;
        }
        AnvilInventory inv = event.getInventory();
        ItemStack first = inv.getFirstItem();
        ItemStack second = inv.getSecondItem();
        if (!registry.isMythicItem(first) && !registry.isMythicItem(second)) {
            return;
        }
        if (second == null || second.getType().isAir()) {
            event.setResult(null);
            return;
        }
        if (registry.identify(first) == MythicItemRegistry.MythicType.ARACHNAID_AXE) {
            return;
        }
        event.setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (plugin.getConfig().getBoolean("global.inventory-only-storage", true)
                && registry.isMythicItem(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStoragePickup(InventoryPickupItemEvent event) {
        if (plugin.getConfig().getBoolean("global.inventory-only-storage", true)
                && registry.isMythicItem(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpenContainerEjectLegacy(InventoryOpenEvent event) {
        if (!plugin.getConfig().getBoolean("global.inventory-only-storage", true)) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (!isBlockedContainer(top)) {
            return;
        }

        boolean ejectedAny = false;
        for (int i = 0; i < top.getSize(); i++) {
            ItemStack item = top.getItem(i);
            if (registry.isMythicItem(item)) {
                top.setItem(i, null);
                var overflow = player.getInventory().addItem(item);
                overflow.values().forEach(drop -> player.getWorld().dropItemNaturally(player.getLocation(), drop));
                ejectedAny = true;
            }
        }
        if (ejectedAny) {
            player.sendMessage(plugin.msg("container-blocked"));
        }
    }

    private boolean sameDisplayName(ItemStack a, ItemStack b) {
        if (a == null || b == null || !a.hasItemMeta() || !b.hasItemMeta()) {
            return false;
        }
        var na = a.getItemMeta().displayName();
        var nb = b.getItemMeta().displayName();
        return na != null && na.equals(nb);
    }

    private boolean isSwappedMythic(InventoryClickEvent event, Player player) {
        if (event.getClick() == ClickType.NUMBER_KEY) {
            ItemStack hotbar = player.getInventory().getItem(event.getHotbarButton());
            return registry.isMythicItem(hotbar);
        }
        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            return registry.isMythicItem(player.getInventory().getItemInOffHand());
        }
        return false;
    }

    private boolean isBlockedContainer(Inventory inv) {
        if (inv == null) {
            return false;
        }
        InventoryHolder holder = inv.getHolder();
        if (holder != null && holder.getClass().getName().equals("com.tearssmp.mythic.bag.BagGuiHolder")) {
            return false;
        }
        if (holder instanceof ChestedHorse || holder instanceof ChestBoat || holder instanceof StorageMinecart) {
            return true;
        }
        return switch (inv.getType()) {
            case CHEST, ENDER_CHEST, BARREL, SHULKER_BOX, HOPPER, DROPPER, DISPENSER,
                 BLAST_FURNACE, FURNACE, SMOKER, BREWING, CRAFTER, CHISELED_BOOKSHELF, DECORATED_POT -> true;
            default -> false;
        };
    }

    private void denyStorage(Player player) {
        player.sendActionBar(plugin.rawMsg("container-blocked"));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.75f);
        Bukkit.getScheduler().runTask(plugin, player::updateInventory);
    }
}
