package com.tearssmp.mythic.command;

import com.tearssmp.mythic.TearsMythicPlugin;
import com.tearssmp.mythic.axe.ArachnaidAxeModule;
import com.tearssmp.mythic.bag.ClericsBagModule;
import com.tearssmp.mythic.core.MythicItemRegistry;
import com.tearssmp.mythic.core.ResourcePackModule;
import com.tearssmp.mythic.egg.DragonEggModule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TearsMythicCommand implements CommandExecutor, TabCompleter {

    private final TearsMythicPlugin plugin;
    private final MythicItemRegistry registry;
    private final ResourcePackModule packModule;
    private final ArachnaidAxeModule axeModule;
    private final DragonEggModule eggModule;
    private final ClericsBagModule bagModule;

    public TearsMythicCommand(
            TearsMythicPlugin plugin,
            MythicItemRegistry registry,
            ResourcePackModule packModule,
            ArachnaidAxeModule axeModule,
            DragonEggModule eggModule,
            ClericsBagModule bagModule
    ) {
        this.plugin = plugin;
        this.registry = registry;
        this.packModule = packModule;
        this.axeModule = axeModule;
        this.eggModule = eggModule;
        this.bagModule = bagModule;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(plugin.parse("<dark_red><bold>TearsSMPMythicItem Commands:</bold></dark_red>"));
            sender.sendMessage(plugin.parse("<gray>/tearssmp give <axe|egg|bag> [player] [color]</gray>"));
            sender.sendMessage(plugin.parse("<gray>/tearssmp open</gray>"));
            sender.sendMessage(plugin.parse("<gray>/tearssmp charge <0-100></gray>"));
            sender.sendMessage(plugin.parse("<gray>/tearssmp reset [player]</gray>"));
            sender.sendMessage(plugin.parse("<gray>/tearssmp unlock</gray>"));
            sender.sendMessage(plugin.parse("<gray>/tearssmp status</gray>"));
            sender.sendMessage(plugin.parse("<gray>/tearssmp pack [player]</gray>"));
            sender.sendMessage(plugin.parse("<gray>/tearssmp reload</gray>"));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> handleGive(sender, args);
            case "open" -> handleOpen(sender);
            case "charge" -> handleCharge(sender, args);
            case "reset" -> handleReset(sender, args);
            case "unlock" -> {
                if (!sender.hasPermission("tearssmp.admin.unlock")) { sender.sendMessage(plugin.msg("no-permission")); return true; }
                axeModule.forceUnlock();
                sender.sendMessage(plugin.parse("<green>Arachnaid Axe recipe unlocked.</green>"));
            }
            case "status" -> {
                if (!sender.hasPermission("tearssmp.admin.status")) { sender.sendMessage(plugin.msg("no-permission")); return true; }
                sender.sendMessage(plugin.parse("<gold>Axe Wielder:</gold> <white>" + axeModule.getCurrentWielderName()
                        + "</white> <dark_gray>|</dark_gray> <dark_purple>Egg Wielder:</dark_purple> <white>" + eggModule.getCurrentWielderName()
                        + "</white> <gray>(" + eggModule.getChargePercent() + "%)</gray>"));
            }
            case "pack" -> handlePack(sender, args);
            case "reload" -> {
                if (!sender.hasPermission("tearssmp.admin.reload")) { sender.sendMessage(plugin.msg("no-permission")); return true; }
                plugin.reloadAll();
                sender.sendMessage(plugin.msg("reloaded"));
            }
            default -> sender.sendMessage(plugin.parse("<gray>Usage: /tearssmp <give|open|charge|reset|unlock|status|pack|reload></gray>"));
        }
        return true;
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("tearssmp.admin.give")) {
            sender.sendMessage(plugin.msg("no-permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(plugin.parse("<red>Usage: /tearssmp give <axe|egg|bag> [player] [color]</red>"));
            return;
        }
        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage(plugin.parse("<red>Player not found: " + args[2] + "</red>"));
                return;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(plugin.parse("<red>Specify a player from console.</red>"));
            return;
        }

        ItemStack stack;
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "axe", "arachnaid", "arachnaid_axe" -> {
                stack = axeModule.createAxe();
                axeModule.claimOrGive(target, false);
            }
            case "egg", "dragonegg", "dragon_egg" -> {
                stack = eggModule.createDragonEgg();
                eggModule.updateWielder(target);
            }
            case "bag", "cleric", "clericsbag", "clerics_bag" -> {
                Material mat = Material.BUNDLE;
                if (args.length >= 4) {
                    Material parsed = parseBundle(args[3]);
                    if (parsed != null) mat = parsed;
                }
                stack = bagModule.createBag(mat);
                registry.broadcastWielder(MythicItemRegistry.MythicType.CLERICS_BAG, target);
            }
            default -> {
                sender.sendMessage(plugin.parse("<red>Item must be: axe, egg, or bag</red>"));
                return;
            }
        }

        Map<Integer, ItemStack> ov = target.getInventory().addItem(stack);
        ov.values().forEach(d -> target.getWorld().dropItemNaturally(target.getLocation(), d));
        sender.sendMessage(plugin.parse("<green>Gave mythic item to <white>" + target.getName() + "</white>.</green>"));
    }

    private void handleOpen(CommandSender sender) {
        if (!(sender instanceof Player p)) return;
        ItemStack held = p.getInventory().getItemInMainHand();
        if (!bagModule.isBag(held)) held = p.getInventory().getItemInOffHand();
        if (!bagModule.isBag(held)) {
            p.sendMessage(plugin.parse("<red>Hold a Cleric's Bag in your hand first.</red>"));
            return;
        }
        bagModule.openGui(p, held);
    }

    private void handleCharge(CommandSender sender, String[] args) {
        if (!sender.hasPermission("tearssmp.admin.charge")) { sender.sendMessage(plugin.msg("no-permission")); return; }
        if (args.length < 2) { sender.sendMessage(plugin.parse("<red>Usage: /tearssmp charge <0-100></red>")); return; }
        try {
            int pct = Integer.parseInt(args[1]);
            eggModule.setChargePercent(pct);
            sender.sendMessage(plugin.parse("<green>Set Dragon's Throw charge to <white>" + eggModule.getChargePercent() + "%</white>.</green>"));
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.parse("<red>Enter a number 0-100.</red>"));
        }
    }

    private void handleReset(CommandSender sender, String[] args) {
        if (!sender.hasPermission("tearssmp.admin.reset")) { sender.sendMessage(plugin.msg("no-permission")); return; }
        eggModule.resetBreathCooldown();
        if (args.length >= 2) {
            Player t = Bukkit.getPlayerExact(args[1]);
            if (t != null) axeModule.resetCooldown(t.getUniqueId());
        } else if (sender instanceof Player p) {
            axeModule.resetCooldown(p.getUniqueId());
        }
        sender.sendMessage(plugin.parse("<green>Reset mythic cooldowns.</green>"));
    }

    private void handlePack(CommandSender sender, String[] args) {
        if (!sender.hasPermission("tearssmp.admin.pack")) { sender.sendMessage(plugin.msg("no-permission")); return; }
        Player t = (args.length >= 2) ? Bukkit.getPlayerExact(args[1]) : (sender instanceof Player p ? p : null);
        if (t != null) {
            packModule.sendPack(t);
            sender.sendMessage(plugin.parse("<green>Sent pack to <white>" + t.getName() + "</white>.</green>"));
        }
    }

    private Material parseBundle(String in) {
        String k = in.trim().toUpperCase(Locale.ROOT);
        if (k.equals("DEFAULT") || k.equals("NORMAL")) return Material.BUNDLE;
        Material d = Material.matchMaterial(k);
        if (d != null && Tag.ITEMS_BUNDLES.isTagged(d)) return d;
        Material s = Material.matchMaterial(k + "_BUNDLE");
        if (s != null && Tag.ITEMS_BUNDLES.isTagged(s)) return s;
        return null;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("give", "open", "charge", "reset", "unlock", "status", "pack", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return List.of("axe", "egg", "bag").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            List<String> out = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(p.getName());
            }
            return out;
        }
        return List.of();
    }
}
