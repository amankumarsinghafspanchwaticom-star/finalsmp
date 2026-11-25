package com.tearssmp.mythic;

import com.tearssmp.mythic.axe.ArachnaidAxeModule;
import com.tearssmp.mythic.bag.ClericsBagModule;
import com.tearssmp.mythic.command.TearsMythicCommand;
import com.tearssmp.mythic.core.MythicItemRegistry;
import com.tearssmp.mythic.core.ResourcePackModule;
import com.tearssmp.mythic.egg.DragonEggModule;
import com.tearssmp.mythic.listener.MythicRestrictionListener;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class TearsMythicPlugin extends JavaPlugin {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private MythicItemRegistry registry;
    private ResourcePackModule resourcePackModule;
    private ArachnaidAxeModule arachnaidAxe;
    private DragonEggModule dragonEgg;
    private ClericsBagModule clericsBag;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.registry = new MythicItemRegistry(this);
        this.resourcePackModule = new ResourcePackModule(this);
        this.arachnaidAxe = new ArachnaidAxeModule(this, registry);
        this.dragonEgg = new DragonEggModule(this, registry);
        this.clericsBag = new ClericsBagModule(this, registry);

        this.arachnaidAxe.enable();
        this.dragonEgg.enable();
        this.clericsBag.enable();
        this.resourcePackModule.start();

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new MythicRestrictionListener(this, registry, resourcePackModule), this);
        pm.registerEvents(arachnaidAxe, this);
        pm.registerEvents(dragonEgg, this);
        pm.registerEvents(clericsBag, this);

        PluginCommand cmd = getCommand("tearssmp");
        if (cmd != null) {
            TearsMythicCommand executor = new TearsMythicCommand(this, registry, resourcePackModule, arachnaidAxe, dragonEgg, clericsBag);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        getLogger().info("TearsSMPMythicItem v" + getPluginMeta().getVersion() + " enabled (Arachnaid Axe, Dragon Egg, Cleric's Bag).");
    }

    @Override
    public void onDisable() {
        if (clericsBag != null) {
            clericsBag.disable();
        }
        if (dragonEgg != null) {
            dragonEgg.disable();
        }
        if (arachnaidAxe != null) {
            arachnaidAxe.disable();
        }
        if (resourcePackModule != null) {
            resourcePackModule.stop();
        }
    }

    public void reloadAll() {
        reloadConfig();
        arachnaidAxe.reload();
        dragonEgg.reload();
        clericsBag.reload();
        resourcePackModule.start();
    }

    public MythicItemRegistry getRegistry() {
        return registry;
    }

    public ArachnaidAxeModule getArachnaidAxe() {
        return arachnaidAxe;
    }

    public DragonEggModule getDragonEgg() {
        return dragonEgg;
    }

    public ClericsBagModule getClericsBag() {
        return clericsBag;
    }

    public Component msg(String path) {
        String prefix = getConfig().getString("messages.prefix", "");
        String raw = getConfig().getString("messages." + path, "");
        return MINI_MESSAGE.deserialize(prefix + raw);
    }

    public Component rawMsg(String path) {
        String raw = getConfig().getString("messages." + path, "");
        return MINI_MESSAGE.deserialize(raw);
    }

    public Component parse(String miniMessage) {
        return MINI_MESSAGE.deserialize(miniMessage);
    }
}
