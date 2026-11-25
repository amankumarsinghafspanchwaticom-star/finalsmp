package com.tearssmp.mythic.axe;

import com.tearssmp.mythic.TearsMythicPlugin;
import com.tearssmp.mythic.core.MythicItemRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Input;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.GrindstoneInventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ArachnaidAxeModule implements Listener {

    private enum StopReason {
        JUMP_OFF, SNEAK_DROP, TOGGLED_OFF, MANTLED, LANDED, LEFT_WALL, OTHER
    }

    private static final float DEFAULT_FLY_SPEED = 0.1f;
    private static final BlockFace[] CARDINALS = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private final TearsMythicPlugin plugin;
    private final MythicItemRegistry registry;
    private final NamespacedKey recipeKey;

    private final File stateFile;
    private final File cooldownFile;
    private final File websFile;

    private boolean axeActive = false;
    private UUID currentWielderUuid = null;
    private String currentWielderName = "None";

    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Set<BlockKey> specialWebs = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ClimbSession> climbSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastWallClick = new ConcurrentHashMap<>();

    private BukkitTask climbTask;

    public ArachnaidAxeModule(TearsMythicPlugin plugin, MythicItemRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.recipeKey = new NamespacedKey(plugin, "arachnaid_axe_recipe");
        this.stateFile = new File(plugin.getDataFolder(), "arachnaid_state.yml");
        this.cooldownFile = new File(plugin.getDataFolder(), "arachnaid_cooldowns.yml");
        this.websFile = new File(plugin.getDataFolder(), "arachnaid_webs.yml");
    }

    public NamespacedKey getRecipeKey() { return recipeKey; }
    public boolean isAxeActive() { return axeActive; }
    public String getCurrentWielderName() { return currentWielderName; }

    public int getSharpnessLevel() {
        return Math.max(1, plugin.getConfig().getInt("arachnaid-axe.sharpness-level", 6));
    }

    public void enable() {
        loadAllData();
        climbTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickClimb, 1L, 1L);
    }

    public void reload() {
        syncRecipeState();
    }

    public void disable() {
        if (climbTask != null) {
            climbTask.cancel();
            climbTask = null;
        }
        for (UUID id : new ArrayList<>(climbSessions.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                stopClimbing(p, StopReason.OTHER);
            }
        }
        climbSessions.clear();
        saveAllData();
    }

    public ItemStack createAxe() {
        ItemStack axe = new ItemStack(Material.NETHERITE_AXE, 1);
        applyMeta(axe, 0);
        return axe;
    }

    public boolean isAxe(ItemStack item) {
        return registry.identify(item) == MythicItemRegistry.MythicType.ARACHNAID_AXE;
    }

    public void applyMeta(ItemStack axe, int damage) {
        if (axe == null || axe.getType() != Material.NETHERITE_AXE) {
            return;
        }
        ItemMeta meta = axe.getItemMeta();
        if (meta == null) {
            return;
        }

        String nameRaw = plugin.getConfig().getString(
                "arachnaid-axe.name",
                "<gradient:#990018:#FF2A4D><bold>ARACHNAID AXE</bold></gradient>"
        );
        meta.displayName(plugin.parse(nameRaw).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));

        List<String> loreRaw = plugin.getConfig().getStringList("arachnaid-axe.lore");
        if (!loreRaw.isEmpty()) {
            List<Component> lore = loreRaw.stream()
                    .map(line -> plugin.parse(line).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE))
                    .toList();
            meta.lore(lore);
        }

        for (Enchantment existing : List.copyOf(meta.getEnchants().keySet())) {
            meta.removeEnchant(existing);
        }
        meta.addEnchant(Enchantment.SHARPNESS, getSharpnessLevel(), true);

        if (plugin.getConfig().getBoolean("arachnaid-axe.hide-enchantment-tooltip", true)) {
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        } else {
            meta.removeItemFlags(ItemFlag.HIDE_ENCHANTS);
        }

        String modelId = plugin.getConfig().getString("arachnaid-axe.custom-model-data", "arachnaid_axe");
        if (modelId != null && !modelId.isBlank()) {
            CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
            cmd.setStrings(List.of(modelId));
            meta.setCustomModelDataComponent(cmd);
        }

        if (meta instanceof Damageable dmg) {
            dmg.setDamage(Math.max(0, damage));
        }

        meta.getPersistentDataContainer().set(registry.getArachnaidKey(), PersistentDataType.BYTE, (byte) 1);
        axe.setItemMeta(meta);
    }

    public void ensureUpToDate(ItemStack item) {
        if (!isAxe(item)) {
            return;
        }
        boolean hideEnch = plugin.getConfig().getBoolean("arachnaid-axe.hide-enchantment-tooltip", true);
        boolean hasSharp = Integer.valueOf(getSharpnessLevel()).equals(item.getEnchantments().get(Enchantment.SHARPNESS))
                && item.getEnchantments().size() == 1;
        boolean flagOk = !hideEnch || item.getItemMeta().hasItemFlag(ItemFlag.HIDE_ENCHANTS);
        if (hasSharp && flagOk) {
            return;
        }
        int dmg = (item.getItemMeta() instanceof Damageable d) ? d.getDamage() : 0;
        applyMeta(item, dmg);
    }

    public void claimOrGive(Player player, boolean fromCraft) {
        this.axeActive = true;
        if (player != null) {
            this.currentWielderUuid = player.getUniqueId();
            this.currentWielderName = player.getName();
        }
        saveState();
        syncRecipeState();
        if (!fromCraft && player != null) {
            registry.broadcastWielder(MythicItemRegistry.MythicType.ARACHNAID_AXE, player);
        }
    }

    public void updateWielder(Player newWielder) {
        if (newWielder == null) {
            return;
        }
        UUID id = newWielder.getUniqueId();
        boolean changed = !id.equals(this.currentWielderUuid);
        this.axeActive = true;
        this.currentWielderUuid = id;
        this.currentWielderName = newWielder.getName();
        saveState();
        syncRecipeState();

        if (changed) {
            registry.broadcastWielder(MythicItemRegistry.MythicType.ARACHNAID_AXE, newWielder);
        }
    }

    public void unlockOnLost(String reason) {
        if (!this.axeActive) {
            return;
        }
        this.axeActive = false;
        this.currentWielderUuid = null;
        this.currentWielderName = "None";
        saveState();
        syncRecipeState();

        if (plugin.getConfig().getBoolean("arachnaid-axe.broadcast-on-unlock", true)) {
            String prefix = plugin.getConfig().getString("messages.prefix", "");
            String raw = plugin.getConfig().getString("messages.craft-unlocked-broadcast", "")
                    .replace("{reason}", reason);
            Bukkit.broadcast(plugin.parse(prefix + raw));
        }
    }

    public void forceUnlock() {
        this.axeActive = false;
        this.currentWielderUuid = null;
        this.currentWielderName = "None";
        saveState();
        syncRecipeState();
    }

    public void resetCooldown(UUID playerId) {
        cooldowns.remove(playerId);
        saveCooldowns();
    }

    public long getRemainingCooldown(UUID playerId) {
        Long exp = cooldowns.get(playerId);
        if (exp == null) return 0L;
        long rem = exp - System.currentTimeMillis();
        if (rem <= 0L) {
            cooldowns.remove(playerId);
            return 0L;
        }
        return (long) Math.ceil(rem / 1000.0);
    }

    public void syncRecipeState() {
        Bukkit.removeRecipe(recipeKey);
        boolean enabled = plugin.getConfig().getBoolean("arachnaid-axe.crafting.enabled", true);
        boolean oneCraft = plugin.getConfig().getBoolean("arachnaid-axe.one-craft-limit", true);

        if (enabled && (!oneCraft || !axeActive)) {
            ShapedRecipe recipe = buildRecipe();
            if (recipe != null) {
                Bukkit.addRecipe(recipe);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.discoverRecipe(recipeKey);
                }
            }
        } else {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.undiscoverRecipe(recipeKey);
            }
        }
    }

    private ShapedRecipe buildRecipe() {
        List<String> shape = plugin.getConfig().getStringList("arachnaid-axe.crafting.shape");
        if (shape.size() != 3) {
            shape = List.of("GEG", "FAF", "GEG");
        }
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createAxe());
        recipe.shape(shape.get(0), shape.get(1), shape.get(2));

        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("arachnaid-axe.crafting.ingredients");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                if (key.length() != 1) continue;
                Material mat = Material.matchMaterial(sec.getString(key, ""));
                if (mat != null && !mat.isAir()) {
                    recipe.setIngredient(key.charAt(0), mat);
                }
            }
        } else {
            recipe.setIngredient('G', Material.ENCHANTED_GOLDEN_APPLE);
            recipe.setIngredient('E', Material.EMERALD_ORE);
            recipe.setIngredient('F', Material.FERMENTED_SPIDER_EYE);
            recipe.setIngredient('A', Material.DIAMOND_AXE);
        }
        return recipe;
    }

    // --- Spider Web Trap ---
    @EventHandler(priority = EventPriority.HIGH)
    public void onSpiderWebClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        if (!isAxe(event.getItem())) return;

        Player caster = event.getPlayer();
        double minPitch = plugin.getConfig().getDouble("arachnaid-axe.spider-web.min-look-down-pitch", 45.0);
        if (!caster.isSneaking() || caster.getLocation().getPitch() < minPitch) return;

        event.setCancelled(true);

        long rem = getRemainingCooldown(caster.getUniqueId());
        if (rem > 0L) {
            String raw = plugin.getConfig().getString("messages.web-on-cooldown", "")
                    .replace("{time}", String.valueOf(rem));
            caster.sendActionBar(plugin.parse(raw));
            caster.playSound(caster.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
            return;
        }

        double halfWidth = plugin.getConfig().getDouble("arachnaid-axe.spider-web.range-half-width", 4.0);
        double maxVertical = plugin.getConfig().getDouble("arachnaid-axe.spider-web.range-vertical", 4.0);
        boolean targetCreative = plugin.getConfig().getBoolean("arachnaid-axe.spider-web.target-creative", false);
        boolean targetMobs = plugin.getConfig().getBoolean("arachnaid-axe.spider-web.target-mobs", false);

        Location origin = caster.getLocation();
        World world = caster.getWorld();

        List<LivingEntity> targets = new ArrayList<>();
        for (Entity entity : world.getNearbyEntities(origin, halfWidth + 0.5, maxVertical + 0.5, halfWidth + 0.5)) {
            if (!(entity instanceof LivingEntity living) || living.isDead()) continue;
            if (living.getUniqueId().equals(caster.getUniqueId())) continue;
            if (living instanceof Player p) {
                if (p.getGameMode() == GameMode.SPECTATOR) continue;
                if (!targetCreative && p.getGameMode() == GameMode.CREATIVE) continue;
            } else if (!targetMobs) {
                continue;
            }
            Location loc = living.getLocation();
            if (Math.abs(loc.getX() - origin.getX()) <= halfWidth
                    && Math.abs(loc.getZ() - origin.getZ()) <= halfWidth
                    && Math.abs(loc.getY() - origin.getY()) <= maxVertical) {
                targets.add(living);
            }
        }

        List<Block> placedAll = new ArrayList<>();
        int affected = 0;
        for (LivingEntity target : targets) {
            List<Block> placed = placeFourGroundedWebs(target);
            if (!placed.isEmpty()) {
                placedAll.addAll(placed);
                affected++;
                world.spawnParticle(Particle.BLOCK, target.getLocation().clone().add(0, 0.3, 0),
                        22, 0.55, 0.25, 0.55, Material.COBWEB.createBlockData());
            }
        }

        if (placedAll.isEmpty()) {
            caster.sendActionBar(plugin.rawMsg("web-no-targets"));
            caster.playSound(caster.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            if (plugin.getConfig().getBoolean("arachnaid-axe.spider-web.consume-cooldown-on-miss", false)) {
                setWebCooldown(caster.getUniqueId());
            }
            return;
        }

        for (Block b : placedAll) {
            specialWebs.add(BlockKey.of(b));
        }
        saveWebs();
        setWebCooldown(caster.getUniqueId());

        world.playSound(origin, Sound.ENTITY_SPIDER_AMBIENT, 1.0f, 0.9f);
        world.playSound(origin, Sound.BLOCK_COBWEB_PLACE, 1.0f, 1.0f);

        String raw = plugin.getConfig().getString("messages.web-activated", "")
                .replace("{count}", String.valueOf(affected));
        caster.sendActionBar(plugin.parse(raw));
    }

    private void setWebCooldown(UUID id) {
        long seconds = plugin.getConfig().getLong("arachnaid-axe.spider-web.cooldown-seconds", 60L);
        cooldowns.put(id, System.currentTimeMillis() + (seconds * 1000L));
        saveCooldowns();
    }

    private List<Block> placeFourGroundedWebs(LivingEntity target) {
        Location loc = target.getLocation();
        World world = loc.getWorld();
        if (world == null) return List.of();

        int maxSearch = plugin.getConfig().getInt("arachnaid-axe.spider-web.max-ground-search-depth", 5);
        Integer groundY = null;
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        for (int dy = 0; dy <= maxSearch; dy++) {
            int y = loc.getBlockY() - dy;
            if (y <= world.getMinHeight()) break;
            Block below = world.getBlockAt(x, y - 1, z);
            if (below.getType().isSolid() && below.getType() != Material.COBWEB) {
                groundY = y;
                break;
            }
        }
        if (groundY == null) return List.of();

        int baseX = (loc.getX() - Math.floor(loc.getX()) >= 0.5) ? loc.getBlockX() : loc.getBlockX() - 1;
        int baseZ = (loc.getZ() - Math.floor(loc.getZ()) >= 0.5) ? loc.getBlockZ() : loc.getBlockZ() - 1;

        int[][] offsets = {{0, 0}, {1, 0}, {0, 1}, {1, 1}, {-1, 0}, {1, -1}, {-1, 1}, {0, -1}};
        List<Block> placed = new ArrayList<>(4);
        for (int[] off : offsets) {
            if (placed.size() >= 4) break;
            Block b = world.getBlockAt(baseX + off[0], groundY, baseZ + off[1]);
            Block below = b.getRelative(BlockFace.DOWN);
            if (isReplaceable(b.getType()) && below.getType().isSolid() && below.getType() != Material.COBWEB) {
                b.setType(Material.COBWEB, false);
                placed.add(b);
            }
        }
        return placed;
    }

    private boolean isReplaceable(Material type) {
        return type.isAir() || type == Material.SHORT_GRASS || type == Material.TALL_GRASS
                || type == Material.FERN || type == Material.LARGE_FERN || type == Material.SNOW;
    }

    // --- Scaffolding Wall Crawl ---
    @EventHandler(priority = EventPriority.HIGH)
    public void onWallClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        if (!isAxe(event.getItem())) return;

        Player player = event.getPlayer();
        double minPitch = plugin.getConfig().getDouble("arachnaid-axe.spider-web.min-look-down-pitch", 45.0);
        if (player.isSneaking() && player.getLocation().getPitch() >= minPitch) return;

        double maxAim = plugin.getConfig().getDouble("arachnaid-axe.wall-climb.max-wall-aim-distance", 3.2);
        RayTraceResult ray = player.rayTraceBlocks(maxAim, FluidCollisionMode.NEVER);
        if (ray == null || ray.getHitBlock() == null || ray.getHitBlockFace() == null) return;

        Block hitBlock = ray.getHitBlock();
        BlockFace hitFace = ray.getHitBlockFace();
        if (!isClimbable(hitBlock) || !isHorizontal(hitFace)) return;

        event.setCancelled(true);

        long now = System.currentTimeMillis();
        long window = plugin.getConfig().getLong("arachnaid-axe.wall-climb.double-click-window-ms", 420L);
        Long prev = lastWallClick.put(player.getUniqueId(), now);

        if (prev != null && (now - prev) <= window) {
            lastWallClick.remove(player.getUniqueId());
            if (climbSessions.containsKey(player.getUniqueId())) {
                stopClimbing(player, StopReason.TOGGLED_OFF);
            } else {
                startClimbing(player, hitFace);
            }
        }
    }

    private void startClimbing(Player player, BlockFace hitFace) {
        UUID id = player.getUniqueId();
        if (climbSessions.containsKey(id)) return;

        BlockFace intoWall = (hitFace != null) ? hitFace.getOppositeFace() : findWallDir(player, 1.0);
        if (intoWall == null) return;

        Input init = player.getCurrentInput();
        boolean initJump = init != null && init.isJump();
        boolean initSneak = init != null && init.isSneak();

        boolean prevAllowFlight = player.getAllowFlight();
        boolean prevFlying = player.isFlying();
        float prevFlySpeed = player.getFlySpeed() > 0.005f ? player.getFlySpeed() : DEFAULT_FLY_SPEED;

        climbSessions.put(id, new ClimbSession(intoWall, System.currentTimeMillis(), prevAllowFlight, prevFlying, prevFlySpeed, initJump, initSneak));

        player.setAllowFlight(true);
        player.setFlySpeed(0.0f);
        player.setFlying(true);
        player.setVelocity(new Vector(0, 0, 0));
        player.setFallDistance(0f);

        player.playSound(player.getLocation(), Sound.ENTITY_SPIDER_AMBIENT, 0.7f, 1.35f);
        player.sendActionBar(plugin.rawMsg("climb-start"));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInput(PlayerInputEvent event) {
        Player player = event.getPlayer();
        ClimbSession session = climbSessions.get(player.getUniqueId());
        if (session == null || event.getInput() == null) return;

        Input input = event.getInput();
        long graceMs = plugin.getConfig().getLong("arachnaid-axe.wall-climb.cancel-grace-ms", 180L);
        boolean pastGrace = (System.currentTimeMillis() - session.startTimeMs) >= graceMs;

        boolean jumpNow = input.isJump();
        boolean sneakNow = input.isSneak();
        boolean jumpPressed = jumpNow && !session.prevJump;
        boolean sneakPressed = sneakNow && !session.prevSneak;
        session.prevJump = jumpNow;
        session.prevSneak = sneakNow;

        if (!pastGrace) return;
        if (jumpPressed) {
            stopClimbing(player, StopReason.JUMP_OFF);
        } else if (sneakPressed) {
            stopClimbing(player, StopReason.SNEAK_DROP);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (!climbSessions.containsKey(player.getUniqueId())) return;
        event.setCancelled(true);
        stopClimbing(player, StopReason.JUMP_OFF);
    }

    private void stopClimbing(Player player, StopReason reason) {
        ClimbSession session = climbSessions.remove(player.getUniqueId());
        if (session == null) return;

        player.setFlySpeed(session.prevFlySpeed);
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            player.setAllowFlight(true);
            player.setFlying(session.prevFlying);
        } else {
            player.setFlying(false);
            player.setAllowFlight(session.prevAllowFlight);
        }
        player.setFallDistance(0f);

        BlockFace intoWall = session.intoWall;
        switch (reason) {
            case JUMP_OFF -> {
                double push = plugin.getConfig().getDouble("arachnaid-axe.wall-climb.jump-off-push", 0.36);
                double up = plugin.getConfig().getDouble("arachnaid-axe.wall-climb.jump-off-upward", 0.38);
                player.setVelocity(new Vector(-intoWall.getModX() * push, up, -intoWall.getModZ() * push));
                player.sendActionBar(plugin.rawMsg("climb-cancelled"));
            }
            case SNEAK_DROP, TOGGLED_OFF -> {
                player.setVelocity(new Vector(0, -0.05, 0));
                player.sendActionBar(plugin.rawMsg("climb-cancelled"));
            }
            case MANTLED -> {
                player.setVelocity(new Vector(intoWall.getModX() * 0.28, 0.40, intoWall.getModZ() * 0.28));
                player.sendActionBar(plugin.rawMsg("climb-stop"));
            }
            case LANDED, LEFT_WALL, OTHER -> {
                if (player.isOnline()) {
                    player.setVelocity(new Vector(0, 0, 0));
                    player.sendActionBar(plugin.rawMsg("climb-stop"));
                }
            }
        }
    }

    private void tickClimb() {
        if (climbSessions.isEmpty()) return;

        double attachDist = plugin.getConfig().getDouble("arachnaid-axe.wall-climb.wall-attach-distance", 0.95);
        double upSpeed = plugin.getConfig().getDouble("arachnaid-axe.wall-climb.climb-up-speed", 0.25);
        double downSpeed = plugin.getConfig().getDouble("arachnaid-axe.wall-climb.climb-down-speed", 0.24);
        double strafeSpeed = plugin.getConfig().getDouble("arachnaid-axe.wall-climb.strafe-speed", 0.22);
        double hugForce = plugin.getConfig().getDouble("arachnaid-axe.wall-climb.wall-hug-force", 0.08);

        Iterator<Map.Entry<UUID, ClimbSession>> it = climbSessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ClimbSession> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            ClimbSession session = entry.getValue();

            if (player == null || !player.isOnline() || player.isDead()) {
                it.remove();
                continue;
            }
            if (!isAxe(player.getInventory().getItemInMainHand())) {
                stopClimbing(player, StopReason.OTHER);
                continue;
            }

            if (!player.getAllowFlight()) player.setAllowFlight(true);
            if (player.getFlySpeed() != 0.0f) player.setFlySpeed(0.0f);
            if (!player.isFlying()) player.setFlying(true);

            Input input = player.getCurrentInput();
            boolean w = input != null && input.isForward();
            boolean s = input != null && input.isBackward();
            boolean a = input != null && input.isLeft();
            boolean d = input != null && input.isRight();

            BlockFace intoWall = session.intoWall;

            if (!hasWall(player, intoWall, attachDist)) {
                if (w && hasLedge(player, intoWall, attachDist)) {
                    stopClimbing(player, StopReason.MANTLED);
                    continue;
                }
                BlockFace alt = findWallDir(player, attachDist);
                if (alt != null) {
                    session.intoWall = alt;
                    intoWall = alt;
                } else {
                    stopClimbing(player, StopReason.LEFT_WALL);
                    continue;
                }
            }

            int vertical = (w ? 1 : 0) - (s ? 1 : 0);
            int strafe = (d ? 1 : 0) - (a ? 1 : 0);

            if (vertical < 0 && touchingFloor(player)) {
                stopClimbing(player, StopReason.LANDED);
                continue;
            }

            player.setFallDistance(0f);

            // Instant scaffolding freeze when no WASD keys are pressed
            if (vertical == 0 && strafe == 0) {
                player.setVelocity(new Vector(0, 0, 0));
                continue;
            }

            double rightX = -intoWall.getModZ();
            double rightZ = intoWall.getModX();
            Vector look = player.getLocation().getDirection();
            if (look.getX() * intoWall.getModX() + look.getZ() * intoWall.getModZ() < -0.25) {
                rightX = -rightX;
                rightZ = -rightZ;
            }

            double vy = (vertical > 0) ? upSpeed : (vertical < 0 ? -downSpeed : 0.0);
            double vx = rightX * strafe * strafeSpeed;
            double vz = rightZ * strafe * strafeSpeed;

            if (vertical != 0 && strafe != 0) {
                vx *= 0.78;
                vy *= 0.78;
                vz *= 0.78;
            }

            vx += intoWall.getModX() * hugForce;
            vz += intoWall.getModZ() * hugForce;
            player.setVelocity(new Vector(vx, vy, vz));

            session.moveTicks++;
            if (session.moveTicks % 7 == 0) {
                Location loc = player.getLocation();
                World world = player.getWorld();
                world.playSound(loc, Sound.ENTITY_SPIDER_STEP, 0.35f, 1.3f);
                world.spawnParticle(Particle.BLOCK, loc.clone().add(0, 0.5, 0), 4,
                        0.18, 0.18, 0.18, Material.COBWEB.createBlockData());
            }
        }
    }

    private boolean hasWall(Player p, BlockFace dir, double dist) {
        Location base = p.getLocation();
        World w = base.getWorld();
        if (w == null || dir == null) return false;
        for (double yOff : new double[]{0.2, 1.1}) {
            Block b = w.getBlockAt(Location.locToBlock(base.getX() + dir.getModX() * dist),
                    Location.locToBlock(base.getY() + yOff),
                    Location.locToBlock(base.getZ() + dir.getModZ() * dist));
            if (isClimbable(b)) return true;
        }
        return false;
    }

    private boolean hasLedge(Player p, BlockFace dir, double dist) {
        Location base = p.getLocation();
        World w = base.getWorld();
        if (w == null || dir == null) return false;
        Block ledge = w.getBlockAt(Location.locToBlock(base.getX() + dir.getModX() * dist),
                Location.locToBlock(base.getY() - 0.35),
                Location.locToBlock(base.getZ() + dir.getModZ() * dist));
        return isClimbable(ledge) && !ledge.getRelative(BlockFace.UP).getType().isSolid();
    }

    private boolean touchingFloor(Player p) {
        Location loc = p.getLocation();
        World w = loc.getWorld();
        if (w == null) return false;
        Block below = w.getBlockAt(Location.locToBlock(loc.getX()), Location.locToBlock(loc.getY() - 0.15), Location.locToBlock(loc.getZ()));
        return isClimbable(below);
    }

    private BlockFace findWallDir(Player p, double dist) {
        for (BlockFace f : CARDINALS) {
            if (hasWall(p, f, dist)) return f;
        }
        return null;
    }

    private boolean isClimbable(Block b) {
        return b != null && b.getType().isSolid() && b.getType() != Material.COBWEB;
    }

    private boolean isHorizontal(BlockFace f) {
        return f == BlockFace.NORTH || f == BlockFace.SOUTH || f == BlockFace.EAST || f == BlockFace.WEST;
    }

    // --- Enchant / Grindstone / Smithing / Combat Hit / Lifecycle ---
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrepareEnchant(PrepareItemEnchantEvent event) {
        if (isAxe(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        if (isAxe(event.getItem())) {
            event.setCancelled(true);
            event.getEnchanter().sendActionBar(plugin.rawMsg("enchant-blocked"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareGrindstone(PrepareGrindstoneEvent event) {
        GrindstoneInventory inv = event.getInventory();
        if (isAxe(inv.getUpperItem()) || isAxe(inv.getLowerItem())) event.setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGrindstoneClick(InventoryClickEvent event) {
        if (event.getInventory() instanceof GrindstoneInventory inv && event.getRawSlot() == 2
                && (isAxe(inv.getUpperItem()) || isAxe(inv.getLowerItem()))) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player p) {
                p.sendActionBar(plugin.rawMsg("enchant-blocked"));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        for (ItemStack item : event.getInventory().getContents()) {
            if (isAxe(item)) {
                event.setResult(null);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        AnvilInventory inv = event.getInventory();
        ItemStack first = inv.getFirstItem();
        ItemStack second = inv.getSecondItem();
        if (!isAxe(first) && !isAxe(second)) return;

        if (isAxe(first) && plugin.getConfig().getBoolean("arachnaid-axe.allow-netherite-repair", true)
                && second != null && second.getType() == Material.NETHERITE_INGOT && event.getResult() != null) {
            ItemStack res = event.getResult().clone();
            int dmg = (res.getItemMeta() instanceof Damageable d) ? d.getDamage() : 0;
            applyMeta(res, dmg);
            event.setResult(res);
            return;
        }
        event.setResult(null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!plugin.getConfig().getBoolean("arachnaid-axe.combat-particles", true)) return;
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof LivingEntity victim)) return;
        ItemStack main = attacker.getInventory().getItemInMainHand();
        if (!isAxe(main)) return;
        ensureUpToDate(main);

        Particle.DustOptions red = new Particle.DustOptions(Color.fromRGB(220, 22, 54), 1.15f);
        victim.getWorld().spawnParticle(Particle.DUST, victim.getLocation().add(0, victim.getHeight() * 0.55, 0),
                10, 0.3, 0.35, 0.3, red);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Item item && isAxe(item.getItemStack())
                && event.getCause() != EntityDamageEvent.DamageCause.VOID) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemCombust(EntityCombustEvent event) {
        if (event.getEntity() instanceof Item item && isAxe(item.getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDespawn(ItemDespawnEvent event) {
        if (isAxe(event.getEntity().getItemStack()) && plugin.getConfig().getBoolean("arachnaid-axe.one-craft-limit", true)) {
            unlockOnLost("despawned");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveEvent event) {
        if (!(event.getEntity() instanceof Item item) || !isAxe(item.getItemStack())) return;
        if (!plugin.getConfig().getBoolean("arachnaid-axe.one-craft-limit", true)) return;
        if (event.getCause() == EntityRemoveEvent.Cause.DESPAWN) {
            unlockOnLost("despawned");
        } else if (event.getCause() == EntityRemoveEvent.Cause.OUT_OF_WORLD) {
            unlockOnLost("fell into the void");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(PlayerItemBreakEvent event) {
        if (isAxe(event.getBrokenItem()) && plugin.getConfig().getBoolean("arachnaid-axe.one-craft-limit", true)) {
            unlockOnLost("broke");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack stack = event.getItem().getItemStack();
        if (isAxe(stack)) {
            ensureUpToDate(stack);
            event.getItem().setItemStack(stack);
            updateWielder(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInvClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && syncPlayerAxe(player)) {
                updateWielder(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInvDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (isAxe(event.getOldCursor())) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && syncPlayerAxe(player)) {
                    updateWielder(player);
                }
            });
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (p.getFlySpeed() < 0.005f && !climbSessions.containsKey(p.getUniqueId())) {
            p.setFlySpeed(DEFAULT_FLY_SPEED);
            if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
                p.setFlying(false);
                p.setAllowFlight(false);
            }
        }
        syncRecipeState();
        if (syncPlayerAxe(p)) {
            updateWielder(p);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        Recipe r = event.getRecipe();
        if (!(r instanceof Keyed keyed) || !keyed.getKey().equals(recipeKey)) return;
        if (plugin.getConfig().getBoolean("arachnaid-axe.one-craft-limit", true) && axeActive) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        Recipe r = event.getRecipe();
        if (!(r instanceof Keyed keyed) || !keyed.getKey().equals(recipeKey)) return;
        if (!(event.getWhoClicked() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }
        boolean oneCraft = plugin.getConfig().getBoolean("arachnaid-axe.one-craft-limit", true);
        if (oneCraft && axeActive) {
            event.setCancelled(true);
            player.sendMessage(plugin.msg("craft-locked"));
            return;
        }
        if (event.getAction() == InventoryAction.NOTHING) return;

        if (event.isShiftClick()) {
            event.setCancelled(true);
            ItemStack[] matrix = event.getInventory().getMatrix();
            for (int i = 0; i < matrix.length; i++) {
                ItemStack s = matrix[i];
                if (s != null && !s.getType().isAir()) {
                    if (s.getAmount() > 1) s.setAmount(s.getAmount() - 1);
                    else matrix[i] = null;
                }
            }
            event.getInventory().setMatrix(matrix);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(createAxe());
            overflow.values().forEach(d -> player.getWorld().dropItemNaturally(player.getLocation(), d));
        } else {
            ClickType click = event.getClick();
            if ((click == ClickType.LEFT || click == ClickType.RIGHT) && event.getCursor() != null && !event.getCursor().getType().isAir()) {
                event.setCancelled(true);
                return;
            }
            if (click == ClickType.NUMBER_KEY) {
                ItemStack hb = player.getInventory().getItem(event.getHotbarButton());
                if (hb != null && !hb.getType().isAir()) {
                    event.setCancelled(true);
                    return;
                }
            }
            if (click == ClickType.SWAP_OFFHAND && !player.getInventory().getItemInOffHand().getType().isAir()) {
                event.setCancelled(true);
                return;
            }
        }

        claimOrGive(player, true);
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        if (plugin.getConfig().getBoolean("arachnaid-axe.broadcast-on-craft", true)) {
            String prefix = plugin.getConfig().getString("messages.prefix", "");
            String raw = plugin.getConfig().getString("messages.craft-claimed-broadcast", "")
                    .replace("{player}", player.getName());
            Bukkit.broadcast(plugin.parse(prefix + raw));
        }
    }

    // --- Special Cobweb Protection ---
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWebBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!specialWebs.contains(BlockKey.of(block))) return;
        Player p = event.getPlayer();
        boolean shears = p.getInventory().getItemInMainHand().getType() == Material.SHEARS;
        boolean adminCreative = plugin.getConfig().getBoolean("arachnaid-axe.special-webs.creative-admin-can-break", true)
                && p.getGameMode() == GameMode.CREATIVE && p.hasPermission("tearssmp.admin");
        if (shears || adminCreative) {
            if (!plugin.getConfig().getBoolean("arachnaid-axe.special-webs.drop-items-on-break", false)) {
                event.setDropItems(false);
            }
            specialWebs.remove(BlockKey.of(block));
            saveWebs();
            return;
        }
        event.setCancelled(true);
        p.sendActionBar(plugin.rawMsg("web-protected"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWaterFlow(BlockFromToEvent event) {
        if (event.getBlock().getType() == Material.WATER && specialWebs.remove(BlockKey.of(event.getToBlock()))) {
            if (!plugin.getConfig().getBoolean("arachnaid-axe.special-webs.drop-items-on-break", false)) {
                event.getToBlock().setType(Material.AIR);
            }
            saveWebs();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (event.getBucket() == Material.WATER_BUCKET && specialWebs.remove(BlockKey.of(event.getBlock()))) {
            saveWebs();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        removeBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        removeBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExt(BlockPistonExtendEvent event) {
        removeBlocks(event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRet(BlockPistonRetractEvent event) {
        removeBlocks(event.getBlocks());
    }

    private void removeBlocks(List<Block> list) {
        boolean changed = false;
        for (Block b : list) {
            if (specialWebs.remove(BlockKey.of(b))) changed = true;
        }
        if (changed) saveWebs();
    }

    @EventHandler public void onSlot(PlayerItemHeldEvent e) { if (climbSessions.containsKey(e.getPlayer().getUniqueId()) && !isAxe(e.getPlayer().getInventory().getItem(e.getNewSlot()))) stopClimbing(e.getPlayer(), StopReason.OTHER); }
    @EventHandler public void onSwap(PlayerSwapHandItemsEvent e) { if (climbSessions.containsKey(e.getPlayer().getUniqueId()) && !isAxe(e.getMainHandItem())) stopClimbing(e.getPlayer(), StopReason.OTHER); }
    @EventHandler public void onDrop(PlayerDropItemEvent e) { if (climbSessions.containsKey(e.getPlayer().getUniqueId())) stopClimbing(e.getPlayer(), StopReason.OTHER); }
    @EventHandler public void onDeath(PlayerDeathEvent e) { stopClimbing(e.getPlayer(), StopReason.OTHER); }
    @EventHandler public void onGm(PlayerGameModeChangeEvent e) { stopClimbing(e.getPlayer(), StopReason.OTHER); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { lastWallClick.remove(e.getPlayer().getUniqueId()); stopClimbing(e.getPlayer(), StopReason.OTHER); }

    private boolean syncPlayerAxe(Player p) {
        boolean found = false;
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack item = p.getInventory().getItem(i);
            if (isAxe(item)) {
                ensureUpToDate(item);
                p.getInventory().setItem(i, item);
                found = true;
            }
        }
        ItemStack cursor = p.getItemOnCursor();
        if (isAxe(cursor)) {
            ensureUpToDate(cursor);
            p.setItemOnCursor(cursor);
            found = true;
        }
        return found;
    }

    private void loadAllData() {
        if (stateFile.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(stateFile);
            this.axeActive = y.getBoolean("axe-active", false);
            String u = y.getString("wielder-uuid", "");
            if (u != null && !u.isBlank()) {
                try { this.currentWielderUuid = UUID.fromString(u); } catch (IllegalArgumentException ignored) { this.currentWielderUuid = null; }
            }
            this.currentWielderName = y.getString("wielder-name", "None");
        }
        syncRecipeState();

        cooldowns.clear();
        if (cooldownFile.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(cooldownFile);
            long now = System.currentTimeMillis();
            for (String k : y.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(k);
                    long exp = y.getLong(k, 0L);
                    if (exp > now) cooldowns.put(id, exp);
                } catch (IllegalArgumentException ignored) {}
            }
        }

        specialWebs.clear();
        if (websFile.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(websFile);
            for (String raw : y.getStringList("webs")) {
                BlockKey bk = BlockKey.parse(raw);
                if (bk != null) specialWebs.add(bk);
            }
        }
    }

    public void saveAllData() {
        saveState();
        saveCooldowns();
        saveWebs();
    }

    private void saveState() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        YamlConfiguration y = new YamlConfiguration();
        y.set("axe-active", axeActive);
        y.set("wielder-uuid", currentWielderUuid != null ? currentWielderUuid.toString() : "");
        y.set("wielder-name", currentWielderName != null ? currentWielderName : "None");
        try { y.save(stateFile); } catch (IOException e) { plugin.getLogger().warning("Save arachnaid_state.yml error: " + e.getMessage()); }
    }

    private void saveCooldowns() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        YamlConfiguration y = new YamlConfiguration();
        long now = System.currentTimeMillis();
        cooldowns.entrySet().removeIf(e -> e.getValue() <= now);
        for (Map.Entry<UUID, Long> e : cooldowns.entrySet()) {
            y.set(e.getKey().toString(), e.getValue());
        }
        try { y.save(cooldownFile); } catch (IOException e) { plugin.getLogger().warning("Save arachnaid_cooldowns.yml error: " + e.getMessage()); }
    }

    private void saveWebs() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        YamlConfiguration y = new YamlConfiguration();
        List<String> list = new ArrayList<>(specialWebs.size());
        for (BlockKey bk : specialWebs) list.add(bk.serialize());
        y.set("webs", list);
        try { y.save(websFile); } catch (IOException e) { plugin.getLogger().warning("Save arachnaid_webs.yml error: " + e.getMessage()); }
    }

    private record BlockKey(UUID worldId, int x, int y, int z) {
        static BlockKey of(Block b) { return new BlockKey(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ()); }
        String serialize() { return worldId + ":" + x + ":" + y + ":" + z; }
        static BlockKey parse(String raw) {
            String[] p = raw.split(":");
            if (p.length != 4) return null;
            try { return new BlockKey(UUID.fromString(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])); }
            catch (IllegalArgumentException e) { return null; }
        }
    }

    private static final class ClimbSession {
        BlockFace intoWall;
        final long startTimeMs;
        final boolean prevAllowFlight;
        final boolean prevFlying;
        final float prevFlySpeed;
        boolean prevJump;
        boolean prevSneak;
        int moveTicks = 0;

        ClimbSession(BlockFace intoWall, long startTimeMs, boolean prevAllowFlight, boolean prevFlying, float prevFlySpeed, boolean prevJump, boolean prevSneak) {
            this.intoWall = intoWall;
            this.startTimeMs = startTimeMs;
            this.prevAllowFlight = prevAllowFlight;
            this.prevFlying = prevFlying;
            this.prevFlySpeed = prevFlySpeed;
            this.prevJump = prevJump;
            this.prevSneak = prevSneak;
        }
    }
}
