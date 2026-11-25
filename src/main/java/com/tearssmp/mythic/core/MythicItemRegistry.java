package com.tearssmp.mythic.core;

import com.tearssmp.mythic.TearsMythicPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public final class MythicItemRegistry {

    public enum MythicType {
        ARACHNAID_AXE,
        DRAGON_EGG,
        CLERICS_BAG
    }

    private final TearsMythicPlugin plugin;
    private final NamespacedKey arachnaidKey;
    private final NamespacedKey eggKey;
    private final NamespacedKey bagKey;

    public MythicItemRegistry(TearsMythicPlugin plugin) {
        this.plugin = plugin;
        this.arachnaidKey = new NamespacedKey(plugin, "arachnaid_axe");
        this.eggKey = new NamespacedKey(plugin, "mythic_dragon_egg");
        this.bagKey = new NamespacedKey(plugin, "clerics_bag");
    }

    public NamespacedKey getArachnaidKey() { return arachnaidKey; }
    public NamespacedKey getEggKey() { return eggKey; }
    public NamespacedKey getBagKey() { return bagKey; }

    public MythicType identify(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                var pdc = meta.getPersistentDataContainer();
                if (item.getType() == Material.NETHERITE_AXE && pdc.has(arachnaidKey, PersistentDataType.BYTE)) {
                    return MythicType.ARACHNAID_AXE;
                }
                if (item.getType() == Material.DRAGON_EGG && pdc.has(eggKey, PersistentDataType.BYTE)) {
                    return MythicType.DRAGON_EGG;
                }
                if (Tag.ITEMS_BUNDLES.isTagged(item.getType()) && pdc.has(bagKey, PersistentDataType.BYTE)) {
                    return MythicType.CLERICS_BAG;
                }
            }
        }
        // Any Dragon Egg (such as the vanilla one dropped by defeating the Ender Dragon) is a Mythic Dragon Egg
        if (item.getType() == Material.DRAGON_EGG) {
            return MythicType.DRAGON_EGG;
        }
        return null;
    }

    public boolean isMythicItem(ItemStack item) {
        return identify(item) != null;
    }

    public String getFriendlyLabel(MythicType type) {
        return switch (type) {
            case ARACHNAID_AXE -> "<dark_red><bold>ARACHNAID AXE</bold></dark_red>";
            case DRAGON_EGG -> "<dark_purple><bold>Dragon Egg</bold></dark_purple>";
            case CLERICS_BAG -> "<gold><bold>Cleric's Bag</bold></gold>";
        };
    }

    public void broadcastWielder(MythicType type, Player wielder) {
        if (wielder == null || !plugin.getConfig().getBoolean("global.broadcast-wielder-changes", true)) {
            return;
        }
        String cfgKey = switch (type) {
            case ARACHNAID_AXE -> "arachnaid-axe.broadcast-wielder";
            case DRAGON_EGG -> "dragon-egg.broadcast-wielder";
            case CLERICS_BAG -> "clerics-bag.broadcast-wielder";
        };
        if (!plugin.getConfig().getBoolean(cfgKey, true)) {
            return;
        }

        String prefix = plugin.getConfig().getString("messages.prefix", "");
        String raw = plugin.getConfig().getString("messages.wielder-changed-broadcast", "")
                .replace("{mythic}", getFriendlyLabel(type))
                .replace("{player}", wielder.getName());
        Bukkit.broadcast(plugin.parse(prefix + raw));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.55f, 1.3f);
        }
    }
}
