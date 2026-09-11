package com.elementalpassives;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.logging.Level;

/**
 * ElementalPassives Plugin
 * 
 * Provides permanent, seamless, non-flickering passive elemental buffs
 * and max health upgrades to specific designated players.
 * 
 * Key Features:
 * - Particles disabled (particles: false, no swirl particles)
 * - Anti-flicker architecture: infinite duration + non-destructive refresh
 * - Seamless persistence across death, respawn, milk buckets, and world changes
 * - Case-insensitive player matching (.equalsIgnoreCase())
 * - Backwards and forwards compatible with Spigot/Paper 1.20 - 1.21+
 */
public class ElementalPassives extends JavaPlugin implements Listener, CommandExecutor {

    // Preset infinite duration: ~3.4 years of game ticks
    private static final int PASSIVE_DURATION = 100_000_000;
    // Re-apply threshold: only touch effects if less than 10,000 ticks (~8.3 minutes) remaining
    private static final int RENEW_THRESHOLD = 10_000;

    private static final List<PassiveProfile> PROFILES = new ArrayList<>();
    private BukkitTask watchdogTask;

    public static class EffectSpec {
        private final String effectName;
        private final int amplifier; // 0 = Level 1, 1 = Level 2
        private final String displayName;

        public EffectSpec(String effectName, int amplifier, String displayName) {
            this.effectName = effectName;
            this.amplifier = amplifier;
            this.displayName = displayName;
        }

        public String getEffectName() { return effectName; }
        public int getAmplifier() { return amplifier; }
        public String getDisplayName() { return displayName; }

        public PotionEffectType resolveType() {
            // Check direct name
            PotionEffectType type = PotionEffectType.getByName(effectName);
            if (type != null) return type;

            // Legacy fallbacks for compatibility across Spigot versions
            switch (effectName.toUpperCase(Locale.ROOT)) {
                case "SPEED":
                    return PotionEffectType.getByName("SPEED");
                case "HASTE":
                    return PotionEffectType.getByName("FAST_DIGGING");
                case "STRENGTH":
                    return PotionEffectType.getByName("INCREASE_DAMAGE");
                case "JUMP_BOOST":
                    return PotionEffectType.getByName("JUMP");
                case "RESISTANCE":
                    return PotionEffectType.getByName("DAMAGE_RESISTANCE");
                case "FIRE_RESISTANCE":
                    return PotionEffectType.getByName("FIRE_RESISTANCE");
                case "WATER_BREATHING":
                    return PotionEffectType.getByName("WATER_BREATHING");
                case "DOLPHINS_GRACE":
                    type = PotionEffectType.getByName("DOLPHINS_GRACE");
                    if (type == null) type = PotionEffectType.getByName("DOLPHIN_GRACE");
                    return type;
                default:
                    return null;
            }
        }
    }

    public static class PassiveProfile {
        private final String username;
        private final double maxHealth;
        private final String title;
        private final List<EffectSpec> effects;

        public PassiveProfile(String username, double maxHealth, String title, List<EffectSpec> effects) {
            this.username = username;
            this.maxHealth = maxHealth;
            this.title = title;
            this.effects = effects;
        }

        public String getUsername() { return username; }
        public double getMaxHealth() { return maxHealth; }
        public String getTitle() { return title; }
        public List<EffectSpec> getEffects() { return effects; }
    }

    @Override
    public void onEnable() {
        registerProfiles();
        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("elementalpassives") != null) {
            getCommand("elementalpassives").setExecutor(this);
        }

        // Apply immediately to any matching players already online (e.g. during /reload)
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyPassives(player, false);
        }

        // Watchdog scheduler: runs every 5 seconds (100 ticks).
        // Only checks online configured players, ensuring zero performance overhead.
        watchdogTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (getProfile(player.getName()) != null) {
                    applyPassives(player, false);
                }
            }
        }, 100L, 100L);

        getLogger().info("[ElementalPassives] Loaded " + PROFILES.size() + " elemental passive profiles successfully!");
    }

    @Override
    public void onDisable() {
        if (watchdogTask != null) {
            watchdogTask.cancel();
            watchdogTask = null;
        }
        getLogger().info("[ElementalPassives] Plugin disabled.");
    }

    private void registerProfiles() {
        PROFILES.clear();

        // 1. .galaxyRYN: 24.0 Max Health (12 hearts), Resistance 1, Strength 1
        PROFILES.add(new PassiveProfile(
            ".galaxyRYN",
            24.0,
            "Cosmic Titan",
            Arrays.asList(
                new EffectSpec("RESISTANCE", 0, "Resistance I"),
                new EffectSpec("STRENGTH", 0, "Strength I")
            )
        ));

        // 2. .Aashif2676: Haste 1, Speed 2
        PROFILES.add(new PassiveProfile(
            ".Aashif2676",
            20.0,
            "Lightning Striker",
            Arrays.asList(
                new EffectSpec("HASTE", 0, "Haste I"),
                new EffectSpec("SPEED", 1, "Speed II")
            )
        ));

        // 3. Iamfaadil: Speed 1, Jump Boost 1
        PROFILES.add(new PassiveProfile(
            "Iamfaadil",
            20.0,
            "Acrobatic Wind",
            Arrays.asList(
                new EffectSpec("SPEED", 0, "Speed I"),
                new EffectSpec("JUMP_BOOST", 0, "Jump Boost I")
            )
        ));

        // 4. SkrytlMC: Strength 2, Fire Resistance
        PROFILES.add(new PassiveProfile(
            "SkrytlMC",
            20.0,
            "Volcanic Berserker",
            Arrays.asList(
                new EffectSpec("STRENGTH", 1, "Strength II"),
                new EffectSpec("FIRE_RESISTANCE", 0, "Fire Resistance I")
            )
        ));

        // 5. Itzziamnotzen10: Dolphin's Grace, Water Breathing, Speed 1
        PROFILES.add(new PassiveProfile(
            "Itzziamnotzen10",
            20.0,
            "Ocean Guardian",
            Arrays.asList(
                new EffectSpec("DOLPHINS_GRACE", 0, "Dolphin's Grace I"),
                new EffectSpec("WATER_BREATHING", 0, "Water Breathing I"),
                new EffectSpec("SPEED", 0, "Speed I")
            )
        ));

        // 6. cryforyou: Speed 1, Resistance 1
        PROFILES.add(new PassiveProfile(
            "cryforyou",
            20.0,
            "Frost Bulwark",
            Arrays.asList(
                new EffectSpec("SPEED", 0, "Speed I"),
                new EffectSpec("RESISTANCE", 0, "Resistance I")
            )
        ));

        // 7. PixelatedMystic: Speed 1, Strength 1
        PROFILES.add(new PassiveProfile(
            "PixelatedMystic",
            20.0,
            "Mystic Duelist",
            Arrays.asList(
                new EffectSpec("SPEED", 0, "Speed I"),
                new EffectSpec("STRENGTH", 0, "Strength I")
            )
        ));

        // 8. .ForcedOne891915: Jump Boost 2, Speed 1
        PROFILES.add(new PassiveProfile(
            ".ForcedOne891915",
            20.0,
            "Storm Acrobat",
            Arrays.asList(
                new EffectSpec("JUMP_BOOST", 1, "Jump Boost II"),
                new EffectSpec("SPEED", 0, "Speed I")
            )
        ));
    }

    /**
     * Case-insensitive matching using .equalsIgnoreCase()
     */
    public static PassiveProfile getProfile(String playerName) {
        if (playerName == null) return null;
        String clean = playerName.trim();
        for (PassiveProfile profile : PROFILES) {
            if (profile.getUsername().equalsIgnoreCase(clean)) {
                return profile;
            }
        }
        return null;
    }

    /**
     * Applies passive buffs and max health to player.
     */
    public void applyPassives(Player player, boolean forceIfActive) {
        if (player == null || !player.isOnline()) return;

        PassiveProfile profile = getProfile(player.getName());
        if (profile == null) return;

        // 1. Max Health Persistence
        applyMaxHealth(player, profile.getMaxHealth());

        // 2. Potion Effects (particles: false, ambient: false, icon: true)
        for (EffectSpec spec : profile.getEffects()) {
            PotionEffectType type = spec.resolveType();
            if (type == null) {
                getLogger().log(Level.WARNING, "Unable to resolve PotionEffectType for: " + spec.getEffectName());
                continue;
            }

            PotionEffect current = player.getPotionEffect(type);

            // ANTI-FLICKER LOGIC:
            // Do NOT remove or re-apply if effect is already active with equal/higher amplifier
            // and has more than RENEW_THRESHOLD ticks remaining.
            boolean needsApply = forceIfActive;
            if (!needsApply) {
                if (current == null) {
                    needsApply = true;
                } else if (current.getAmplifier() < spec.getAmplifier()) {
                    needsApply = true;
                } else if (current.getDuration() < RENEW_THRESHOLD) {
                    needsApply = true;
                }
            }

            if (needsApply) {
                PotionEffect effect = new PotionEffect(
                    type,
                    PASSIVE_DURATION,
                    spec.getAmplifier(),
                    false, // ambient
                    false, // particles (hidden!)
                    true   // icon
                );

                player.addPotionEffect(effect, true);
            }
        }
    }

    /**
     * Safe max health attribution across Paper/Spigot 1.20 and 1.21+
     */
    private void applyMaxHealth(Player player, double maxHealth) {
        Attribute healthAttr = getMaxHealthAttribute();
        if (healthAttr == null) return;

        AttributeInstance instance = player.getAttribute(healthAttr);
        if (instance != null) {
            if (Math.abs(instance.getBaseValue() - maxHealth) > 0.001) {
                instance.setBaseValue(maxHealth);
                if (player.getHealth() > maxHealth) {
                    player.setHealth(maxHealth);
                }
            }
        }
    }

    private static Attribute getMaxHealthAttribute() {
        try {
            return Attribute.valueOf("MAX_HEALTH");
        } catch (Throwable t1) {
            try {
                return Attribute.valueOf("GENERIC_MAX_HEALTH");
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (getProfile(player.getName()) != null) {
            Bukkit.getScheduler().runTaskLater(this, () -> applyPassives(player, true), 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (getProfile(player.getName()) != null) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                applyPassives(player, true);
                PassiveProfile profile = getProfile(player.getName());
                if (profile != null && profile.getMaxHealth() > 20.0) {
                    player.setHealth(profile.getMaxHealth());
                }
            }, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (getProfile(player.getName()) != null) {
            Bukkit.getScheduler().runTaskLater(this, () -> applyPassives(player, false), 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        PassiveProfile profile = getProfile(player.getName());
        if (profile == null) return;

        if (event.getAction() == EntityPotionEffectEvent.Action.CLEANSED ||
            event.getAction() == EntityPotionEffectEvent.Action.REMOVED ||
            event.getCause() == EntityPotionEffectEvent.Cause.MILK) {

            Bukkit.getScheduler().runTaskLater(this, () -> applyPassives(player, false), 1L);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("list")) {
            sender.sendMessage(ChatColor.GOLD + "=== " + ChatColor.YELLOW + "ElementalPassives Profiles" + ChatColor.GOLD + " ===");
            for (PassiveProfile profile : PROFILES) {
                Player target = Bukkit.getPlayerExact(profile.getUsername());
                boolean online = (target != null && target.isOnline());
                String status = online ? ChatColor.GREEN + "[ONLINE]" : ChatColor.RED + "[OFFLINE]";
                sender.sendMessage(ChatColor.AQUA + "• " + ChatColor.WHITE + profile.getUsername() + " " + status +
                        ChatColor.DARK_AQUA + " (" + profile.getTitle() + ")");
                sender.sendMessage(ChatColor.GRAY + "  Max Health: " + ChatColor.RED + profile.getMaxHealth() + " HP " +
                        (profile.getMaxHealth() > 20.0 ? "(+2 Extra Hearts)" : ""));
                StringBuilder effectsDesc = new StringBuilder(ChatColor.GRAY + "  Passives: ");
                for (int i = 0; i < profile.getEffects().size(); i++) {
                    EffectSpec eff = profile.getEffects().get(i);
                    effectsDesc.append(ChatColor.LIGHT_PURPLE).append(eff.getDisplayName());
                    if (i < profile.getEffects().size() - 1) effectsDesc.append(ChatColor.GRAY).append(", ");
                }
                sender.sendMessage(effectsDesc.toString());
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            registerProfiles();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (getProfile(player.getName()) != null) {
                    applyPassives(player, true);
                }
            }
            sender.sendMessage(ChatColor.GREEN + "[ElementalPassives] Configuration reloaded and passives refreshed for all online users!");
            return true;
        }

        if (args[0].equalsIgnoreCase("apply") && args.length > 1) {
            String targetName = args[1];
            Player target = Bukkit.getPlayer(targetName);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player '" + targetName + "' is not online.");
                return true;
            }
            PassiveProfile profile = getProfile(target.getName());
            if (profile == null) {
                sender.sendMessage(ChatColor.YELLOW + "Player '" + target.getName() + "' is not registered in ElementalPassives.");
                return true;
            }
            applyPassives(target, true);
            sender.sendMessage(ChatColor.GREEN + "Force-applied passives to " + target.getName() + " (" + profile.getTitle() + ").");
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Usage: /" + label + " [list|info|reload|apply <player>]");
        return true;
    }

    public static List<PassiveProfile> getAllProfiles() {
        return Collections.unmodifiableList(PROFILES);
    }
}
