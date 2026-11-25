package com.tearssmp.mythic.egg;

import com.tearssmp.mythic.TearsMythicPlugin;
import com.tearssmp.mythic.core.MythicItemRegistry;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class DragonEggModule implements Listener {

    private static final double THRUST_VELOCITY = 3.2;
    private static final double COAST_HANDOFF_BLOCKS = 45.0;

    private final TearsMythicPlugin plugin;
    private final MythicItemRegistry registry;
    private final File stateFile;

    private UUID currentWielderUuid = null;
    private String currentWielderName = "None";
    private int chargePercent = 0;
    private int secondsAcc = 0;
    private long breathCooldownExpiryMs = 0L;

    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> activeLaunches = new ConcurrentHashMap<>();
    private final Set<BukkitTask> activeBreathZones = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ClickRecord> lastLeftClick = new ConcurrentHashMap<>();
    private final Map<UUID, Long> leftDedupe = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRightClick = new ConcurrentHashMap<>();

    private BukkitTask tickerTask;

    public DragonEggModule(TearsMythicPlugin plugin, MythicItemRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.stateFile = new File(plugin.getDataFolder(), "egg_state.yml");
    }

    public String getCurrentWielderName() { return currentWielderName; }
    public int getChargePercent() { return chargePercent; }

    public int getMaxCharge() {
        return Math.max(1, plugin.getConfig().getInt("dragon-egg.dragons-throw.max-charge", 100));
    }

    public void setChargePercent(int val) {
        this.chargePercent = Math.max(0, Math.min(getMaxCharge(), val));
        this.secondsAcc = 0;
        saveState();
        refreshAllBossBars();
    }

    public void resetBreathCooldown() {
        this.breathCooldownExpiryMs = 0L;
        saveState();
        refreshAllBossBars();
    }

    public void enable() {
        loadState();
        tickerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickSecond, 20L, 20L);
    }

    public void reload() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            sanitizePlayerEggs(p);
        }
        refreshAllBossBars();
    }

    public void disable() {
        if (tickerTask != null) {
            tickerTask.cancel();
            tickerTask = null;
        }
        for (BukkitTask t : activeLaunches.values()) t.cancel();
        activeLaunches.clear();
        for (BukkitTask t : activeBreathZones) t.cancel();
        activeBreathZones.clear();
        for (Map.Entry<UUID, BossBar> e : bossBars.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) p.hideBossBar(e.getValue());
        }
        bossBars.clear();
        saveState();
    }

    public ItemStack createDragonEgg() {
        ItemStack egg = new ItemStack(Material.DRAGON_EGG, 1);
        applyNormalAppearance(egg);
        return egg;
    }

    public boolean isEgg(ItemStack item) {
        return item != null && item.getType() == Material.DRAGON_EGG;
    }

    /**
     * Keeps the Dragon Egg completely normal in name and enchantment glint — zero special custom name
     * and zero fake glint, while still keeping lore (optional) and its mythic PDC marker.
     */
    public void applyNormalAppearance(ItemStack egg) {
        if (!isEgg(egg)) return;
        ItemMeta meta = egg.getItemMeta();
        if (meta == null) return;

        if (plugin.getConfig().getBoolean("dragon-egg.keep-normal-name", true)) {
            meta.displayName(null);
            meta.lore(null);
        }
        if (plugin.getConfig().getBoolean("dragon-egg.keep-normal-glint", true)) {
            meta.setEnchantmentGlintOverride(false);
        }

        meta.getPersistentDataContainer().set(registry.getEggKey(), PersistentDataType.BYTE, (byte) 1);
        egg.setItemMeta(meta);
    }

    public boolean sanitizePlayerEggs(Player player) {
        if (player == null) return false;
        boolean found = false;
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (isEgg(item)) {
                found = true;
                applyNormalAppearance(item);
                inv.setItem(i, item);
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        if (isEgg(cursor)) {
            found = true;
            applyNormalAppearance(cursor);
            player.setItemOnCursor(cursor);
        }
        return found;
    }

    public boolean playerHasEgg(Player p) {
        if (p == null) return false;
        for (ItemStack item : p.getInventory().getContents()) {
            if (isEgg(item)) return true;
        }
        return isEgg(p.getItemOnCursor());
    }

    public boolean playerHoldingEgg(Player p) {
        if (p == null) return false;
        return isEgg(p.getInventory().getItemInMainHand()) || isEgg(p.getInventory().getItemInOffHand());
    }

    public void updateWielder(Player newWielder) {
        if (newWielder == null) return;
        UUID newId = newWielder.getUniqueId();
        boolean changed = !newId.equals(this.currentWielderUuid);
        this.currentWielderUuid = newId;
        this.currentWielderName = newWielder.getName();
        saveState();

        if (changed) {
            registry.broadcastWielder(MythicItemRegistry.MythicType.DRAGON_EGG, newWielder);
        }
        updateBossBar(newWielder);
    }

    private void tickSecond() {
        boolean onlyHeld = plugin.getConfig().getBoolean("dragon-egg.charge-only-when-held", false);
        int interval = Math.max(1, plugin.getConfig().getInt("dragon-egg.dragons-throw.charge-interval-seconds", 2));
        int gain = Math.max(1, plugin.getConfig().getInt("dragon-egg.dragons-throw.charge-per-interval", 1));
        int max = getMaxCharge();

        Player carrier = findOnlineCarrier();
        if (carrier != null) {
            if (!carrier.getUniqueId().equals(currentWielderUuid)) {
                sanitizePlayerEggs(carrier);
                updateWielder(carrier);
            }
            boolean canCharge = !onlyHeld || playerHoldingEgg(carrier);
            if (canCharge && chargePercent < max) {
                secondsAcc++;
                if (secondsAcc >= interval) {
                    secondsAcc = 0;
                    chargePercent = Math.min(max, chargePercent + gain);
                    if (chargePercent == max) {
                        carrier.playSound(carrier.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.5f);
                    }
                    if (chargePercent % 10 == 0 || chargePercent == max) {
                        saveState();
                    }
                }
            }
        }
        refreshAllBossBars();
    }

    private Player findOnlineCarrier() {
        if (currentWielderUuid != null) {
            Player cur = Bukkit.getPlayer(currentWielderUuid);
            if (cur != null && cur.isOnline() && playerHasEgg(cur)) {
                return cur;
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (playerHasEgg(p)) return p;
        }
        return null;
    }

    public void refreshAllBossBars() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            updateBossBar(p);
        }
    }

    public void updateBossBar(Player player) {
        if (player == null || !player.isOnline()) return;
        boolean show = plugin.getConfig().getBoolean("dragon-egg.show-bossbar-while-held", true);
        if (!show || !playerHoldingEgg(player)) {
            BossBar removed = bossBars.remove(player.getUniqueId());
            if (removed != null) player.hideBossBar(removed);
            return;
        }

        int max = getMaxCharge();
        float prog = Math.max(0f, Math.min(1f, (float) chargePercent / (float) max));
        double maxH = plugin.getConfig().getDouble("dragon-egg.dragons-throw.max-launch-height-blocks", 200.0);
        int estH = (int) Math.round((chargePercent / (double) max) * maxH);

        long rem = Math.max(0L, (long) Math.ceil((breathCooldownExpiryMs - System.currentTimeMillis()) / 1000.0));
        String breathStr = (rem <= 0L) ? "<green>READY</green>" : "<red>" + rem + "s</red>";

        Component title = plugin.parse(
                "<dark_purple><bold>🐉 Dragon's Throw:</bold></dark_purple> <white>" + chargePercent + "%</white>"
                        + " <gray>(" + estH + " blks)</gray> <dark_gray>|</dark_gray> <dark_purple>Breath:</dark_purple> " + breathStr
        );
        BossBar.Color color = (chargePercent >= max) ? BossBar.Color.PINK : BossBar.Color.PURPLE;

        BossBar bar = bossBars.computeIfAbsent(player.getUniqueId(), id -> {
            BossBar created = BossBar.bossBar(title, prog, color, BossBar.Overlay.NOTCHED_10);
            player.showBossBar(created);
            return created;
        });
        bar.name(title);
        bar.progress(prog);
        bar.color(color);
    }

    // --- Abilities ---
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!isEgg(event.getItem())) return;

        Player player = event.getPlayer();
        Action action = event.getAction();

        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            if (!player.isSneaking()) return;
            handleThrowClick(player, null);
            return;
        }

        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            double minPitch = plugin.getConfig().getDouble("dragon-egg.dragons-breath.min-look-down-pitch", 45.0);
            if (!player.isSneaking() || player.getLocation().getPitch() < minPitch) return;

            event.setCancelled(true);
            long now = System.currentTimeMillis();
            long win = plugin.getConfig().getLong("dragon-egg.dragons-breath.double-click-window-ms", 450L);
            Long prev = lastRightClick.put(player.getUniqueId(), now);
            if (prev != null && (now - prev) <= win) {
                lastRightClick.remove(player.getUniqueId());
                triggerBreath(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMeleeHitEgg(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player p) || !p.isSneaking()) return;
        if (!isEgg(p.getInventory().getItemInMainHand())) return;
        if (event.getEntity() instanceof LivingEntity living && isValidThrowTarget(p, living)) {
            handleThrowClick(p, living);
        }
    }

    private void handleThrowClick(Player caster, LivingEntity direct) {
        long now = System.currentTimeMillis();
        Long lastEv = leftDedupe.get(caster.getUniqueId());
        if (lastEv != null && (now - lastEv) < 40L) return;
        leftDedupe.put(caster.getUniqueId(), now);

        LivingEntity target = (direct != null) ? direct : findAimedTarget(caster);
        if (target == null) return;

        long win = plugin.getConfig().getLong("dragon-egg.dragons-throw.double-click-window-ms", 450L);
        ClickRecord prev = lastLeftClick.put(caster.getUniqueId(), new ClickRecord(target.getUniqueId(), now));
        if (prev != null && (now - prev.ts) <= win && prev.targetId.equals(target.getUniqueId())) {
            lastLeftClick.remove(caster.getUniqueId());
            triggerThrow(caster, target);
        }
    }

    private void triggerThrow(Player caster, LivingEntity target) {
        int min = Math.max(1, plugin.getConfig().getInt("dragon-egg.dragons-throw.min-charge-to-fire", 1));
        if (chargePercent < min) {
            caster.sendActionBar(plugin.parse(plugin.getConfig().getString("messages.throw-no-charge", "").replace("{min}", String.valueOf(min))));
            return;
        }

        int used = chargePercent;
        int max = getMaxCharge();
        this.chargePercent = 0;
        this.secondsAcc = 0;
        saveState();
        refreshAllBossBars();

        double maxH = plugin.getConfig().getDouble("dragon-egg.dragons-throw.max-launch-height-blocks", 200.0);
        int height = (int) Math.round(Math.max(2.0, ((double) used / (double) max) * maxH));
        launchVertical(target, height);

        String tName = (target instanceof Player tp) ? tp.getName() : target.getType().name();
        caster.sendActionBar(plugin.parse(plugin.getConfig().getString("messages.throw-launched-caster", "")
                .replace("{target}", tName)
                .replace("{height}", String.valueOf(height))
                .replace("{charge}", String.valueOf(used))));

        if (target instanceof Player victim) {
            victim.sendActionBar(plugin.parse(plugin.getConfig().getString("messages.throw-launched-victim", "")
                    .replace("{caster}", caster.getName())
                    .replace("{height}", String.valueOf(height))));
        }
    }

    private void launchVertical(LivingEntity target, double desiredHeight) {
        BukkitTask prev = activeLaunches.remove(target.getUniqueId());
        if (prev != null) prev.cancel();

        World w = target.getWorld();
        Location start = target.getLocation();
        w.playSound(start, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.35f);
        w.playSound(start, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.1f, 0.85f);
        spawnBreathParticle(w, start.clone().add(0, 0.3, 0), 25, 0.5, 0.2, 0.5, 0.08);

        if (desiredHeight <= COAST_HANDOFF_BLOCKS) {
            target.setVelocity(new Vector(0, solveCoastVy(desiredHeight), 0));
            return;
        }

        double apexY = start.getY() + desiredHeight;
        UUID id = target.getUniqueId();
        final int[] ticks = {0};

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            ticks[0]++;
            if (!target.isValid() || target.isDead() || ticks[0] > 160) {
                cancelLaunch(id);
                return;
            }
            Location loc = target.getLocation();
            if (loc.getWorld() == null || !loc.getWorld().equals(w)) {
                cancelLaunch(id);
                return;
            }
            Block above = w.getBlockAt(loc.getBlockX(), Location.locToBlock(loc.getY() + target.getHeight() + 0.35), loc.getBlockZ());
            if (above.getType().isSolid()) {
                cancelLaunch(id);
                return;
            }
            double rem = apexY - loc.getY();
            if (rem <= 0.5) {
                cancelLaunch(id);
                return;
            }
            if (rem <= COAST_HANDOFF_BLOCKS) {
                target.setVelocity(new Vector(0, solveCoastVy(rem), 0));
                cancelLaunch(id);
                return;
            }
            target.setVelocity(new Vector(0, THRUST_VELOCITY, 0));
            w.spawnParticle(Particle.REVERSE_PORTAL, loc, 6, 0.25, 0.1, 0.25, 0.04);
        }, 0L, 1L);

        activeLaunches.put(id, task);
    }

    private void cancelLaunch(UUID id) {
        BukkitTask t = activeLaunches.remove(id);
        if (t != null) t.cancel();
    }

    private double solveCoastVy(double h) {
        double lo = 0.0, hi = 3.85;
        for (int i = 0; i < 28; i++) {
            double mid = (lo + hi) * 0.5;
            double simY = 0.0, v = mid;
            while (v > 0.0) {
                simY += v;
                v = (v - 0.08) * 0.98;
            }
            if (simY < h) lo = mid; else hi = mid;
        }
        return (lo + hi) * 0.5;
    }

    private void triggerBreath(Player caster) {
        long now = System.currentTimeMillis();
        if (now < breathCooldownExpiryMs) {
            long rem = (long) Math.ceil((breathCooldownExpiryMs - now) / 1000.0);
            caster.sendActionBar(plugin.parse(plugin.getConfig().getString("messages.breath-on-cooldown", "")
                    .replace("{time}", String.valueOf(rem))));
            return;
        }

        long cdSec = plugin.getConfig().getLong("dragon-egg.dragons-breath.cooldown-seconds", 60L);
        breathCooldownExpiryMs = now + (cdSec * 1000L);
        saveState();
        refreshAllBossBars();

        UUID casterId = caster.getUniqueId();
        Location center = caster.getLocation().clone();
        World w = center.getWorld();
        if (w == null) return;

        double halfW = plugin.getConfig().getDouble("dragon-egg.dragons-breath.area-half-width", 4.0);
        int durTicks = Math.max(20, plugin.getConfig().getInt("dragon-egg.dragons-breath.duration-seconds", 10) * 20);
        int dmgInterval = Math.max(2, plugin.getConfig().getInt("dragon-egg.dragons-breath.damage-interval-ticks", 10));
        double dmg = plugin.getConfig().getDouble("dragon-egg.dragons-breath.damage-per-pulse", 3.0);
        boolean targetCreative = plugin.getConfig().getBoolean("dragon-egg.dragons-breath.target-creative", false);
        boolean targetMobs = plugin.getConfig().getBoolean("dragon-egg.dragons-breath.target-mobs", false);

        w.playSound(center, Sound.ENTITY_ENDER_DRAGON_SHOOT, 1.2f, 0.9f);
        caster.sendActionBar(plugin.parse(plugin.getConfig().getString("messages.breath-activated", "")
                .replace("{duration}", String.valueOf(plugin.getConfig().getInt("dragon-egg.dragons-breath.duration-seconds", 10)))));

        final int[] elapsed = {0};
        final BukkitTask[] ref = new BukkitTask[1];
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (elapsed[0] >= durTicks || !w.isChunkLoaded(center.getBlockX() >> 4, center.getBlockZ() >> 4)) {
                if (ref[0] != null) {
                    ref[0].cancel();
                    activeBreathZones.remove(ref[0]);
                }
                return;
            }

            if (elapsed[0] % 2 == 0) {
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                for (int i = 0; i < 24; i++) {
                    double rx = center.getX() + rng.nextDouble(-halfW, halfW);
                    double rz = center.getZ() + rng.nextDouble(-halfW, halfW);
                    spawnBreathParticle(w, new Location(w, rx, center.getY() + 0.2, rz), 1, 0.15, 0.1, 0.15, 0.02);
                }
            }

            if (elapsed[0] % dmgInterval == 0) {
                Player onlineCaster = Bukkit.getPlayer(casterId);
                DamageSource.Builder sb = DamageSource.builder(DamageType.DRAGON_BREATH);
                if (onlineCaster != null && onlineCaster.isOnline()) {
                    sb.withCausingEntity(onlineCaster).withDirectEntity(onlineCaster);
                }
                DamageSource src = sb.build();

                for (Entity ent : w.getNearbyEntities(center, halfW + 0.6, 3.5, halfW + 0.6)) {
                    if (!(ent instanceof LivingEntity living) || living.isDead()) continue;
                    // Wielder is 100% immune
                    if (living.getUniqueId().equals(casterId)) continue;
                    if (living instanceof Player tp) {
                        if (tp.getGameMode() == GameMode.SPECTATOR) continue;
                        if (!targetCreative && tp.getGameMode() == GameMode.CREATIVE) continue;
                    } else if (!targetMobs) {
                        continue;
                    }
                    Location loc = living.getLocation();
                    if (Math.abs(loc.getX() - center.getX()) <= halfW && Math.abs(loc.getZ() - center.getZ()) <= halfW) {
                        living.damage(dmg, src);
                    }
                }
            }
            elapsed[0]++;
        }, 0L, 1L);

        ref[0] = task;
        activeBreathZones.add(task);
    }

    private LivingEntity findAimedTarget(Player caster) {
        World w = caster.getWorld();
        Location eye = caster.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        double range = plugin.getConfig().getDouble("dragon-egg.dragons-throw.target-range-blocks", 24.0);
        RayTraceResult blk = w.rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
        double maxD = (blk != null) ? Math.min(range, blk.getHitPosition().distance(eye.toVector())) : range;
        RayTraceResult hit = w.rayTraceEntities(eye, dir, maxD, 0.75, e -> isValidThrowTarget(caster, e));
        return (hit != null && hit.getHitEntity() instanceof LivingEntity living) ? living : null;
    }

    private boolean isValidThrowTarget(Player caster, Entity e) {
        if (!(e instanceof LivingEntity living) || living.isDead() || !living.isValid()) return false;
        if (living.getUniqueId().equals(caster.getUniqueId())) return false;
        if (living instanceof Player tp) {
            if (tp.getGameMode() == GameMode.SPECTATOR) return false;
            return plugin.getConfig().getBoolean("dragon-egg.dragons-throw.target-creative", false) || tp.getGameMode() != GameMode.CREATIVE;
        }
        return plugin.getConfig().getBoolean("dragon-egg.dragons-throw.target-mobs", false);
    }

    private void spawnBreathParticle(World w, Location loc, int count, double ox, double oy, double oz, double speed) {
        if (Particle.DRAGON_BREATH.getDataType() == Float.class) {
            w.spawnParticle(Particle.DRAGON_BREATH, loc, count, ox, oy, oz, speed, 1.0f);
        } else {
            w.spawnParticle(Particle.DRAGON_BREATH, loc, count, ox, oy, oz, speed);
        }
    }

    // --- Egg Lifecycle ---
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        if (isEgg(item.getItemStack())) {
            ItemStack st = item.getItemStack();
            applyNormalAppearance(st);
            item.setItemStack(st);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (plugin.getConfig().getBoolean("dragon-egg.prevent-place", true) && isEgg(event.getItemInHand())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(plugin.rawMsg("place-blocked"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDmg(EntityDamageEvent event) {
        if (plugin.getConfig().getBoolean("dragon-egg.indestructible-drop", true)
                && event.getEntity() instanceof Item item && isEgg(item.getItemStack())
                && event.getCause() != EntityDamageEvent.DamageCause.VOID) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCombust(EntityCombustEvent event) {
        if (plugin.getConfig().getBoolean("dragon-egg.indestructible-drop", true)
                && event.getEntity() instanceof Item item && isEgg(item.getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        if (isEgg(event.getItem().getItemStack())) {
            ItemStack st = event.getItem().getItemStack();
            applyNormalAppearance(st);
            event.getItem().setItemStack(st);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) {
                    sanitizePlayerEggs(p);
                    updateWielder(p);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInvClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            if (sanitizePlayerEggs(p)) updateWielder(p);
            else updateBossBar(p);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInvDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (isEgg(event.getOldCursor())) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline() && sanitizePlayerEggs(p)) updateWielder(p);
            });
        }
    }

    @EventHandler public void onHeld(PlayerItemHeldEvent e) { Bukkit.getScheduler().runTask(plugin, () -> { if (e.getPlayer().isOnline()) updateBossBar(e.getPlayer()); }); }
    @EventHandler public void onSwap(PlayerSwapHandItemsEvent e) { Bukkit.getScheduler().runTask(plugin, () -> { if (e.getPlayer().isOnline()) updateBossBar(e.getPlayer()); }); }
    @EventHandler public void onDrop(PlayerDropItemEvent e) { Bukkit.getScheduler().runTask(plugin, () -> { if (e.getPlayer().isOnline()) updateBossBar(e.getPlayer()); }); }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (sanitizePlayerEggs(p)) updateWielder(p);
        else updateBossBar(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        lastLeftClick.remove(id);
        leftDedupe.remove(id);
        lastRightClick.remove(id);
        BossBar bar = bossBars.remove(id);
        if (bar != null) e.getPlayer().hideBossBar(bar);
    }

    private void loadState() {
        if (!stateFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(stateFile);
        String u = y.getString("wielder-uuid", "");
        if (u != null && !u.isBlank()) {
            try { this.currentWielderUuid = UUID.fromString(u); } catch (IllegalArgumentException ignored) { this.currentWielderUuid = null; }
        }
        this.currentWielderName = y.getString("wielder-name", "None");
        this.chargePercent = Math.max(0, Math.min(getMaxCharge(), y.getInt("throw-charge", 0)));
        this.breathCooldownExpiryMs = Math.max(0L, y.getLong("breath-cooldown-expiry", 0L));
    }

    public void saveState() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        YamlConfiguration y = new YamlConfiguration();
        y.set("wielder-uuid", currentWielderUuid != null ? currentWielderUuid.toString() : "");
        y.set("wielder-name", currentWielderName != null ? currentWielderName : "None");
        y.set("throw-charge", chargePercent);
        y.set("breath-cooldown-expiry", breathCooldownExpiryMs);
        try { y.save(stateFile); } catch (IOException e) { plugin.getLogger().warning("Save egg_state.yml error: " + e.getMessage()); }
    }

    private record ClickRecord(UUID targetId, long ts) {}
}
