package me.leonrobi.vanillabosses;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import com.tcoded.folialib.FoliaLib;
import io.papermc.paper.event.entity.EntityMoveEvent;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class VanillaBosses extends JavaPlugin implements Listener {

    public static class GameYMLException extends Exception {
        public GameYMLException(@NotNull String msg) {
            super(msg);
        }
    }

    public static class GameYMLNotFoundException extends GameYMLException {
        public final ConfigurationSection section;
        public final String path;

        public GameYMLNotFoundException(@NotNull ConfigurationSection section, @NotNull String path) {
            super("Path '" + path + "' not found in config section " +
                    "'" + section.getCurrentPath() + "'");

            this.section = section;
            this.path = path;
        }
    }

    public static class GameYMLMissingRequiredFieldException extends GameYMLException {
        public GameYMLMissingRequiredFieldException(@NotNull GameYMLNotFoundException e) {
            this(e.section, e.path);
        }

        public GameYMLMissingRequiredFieldException(@NotNull ConfigurationSection section, @NotNull String path) {
            super("Missing required field at path '" + path + "', not found in config section " +
                    "'" + section.getCurrentPath() + "'");
        }
    }

    private @NotNull String getPath(@NotNull String key, @NotNull String field) {
        return key + "." + field;
    }

    private <E extends Enum<E>> @NotNull E valueOfEnum(Class<E> clazz, @NotNull String value) throws GameYMLException {
        try {
            return Enum.valueOf(clazz, value);
        } catch (IllegalArgumentException e) {
            throw new GameYMLException("Value '" + value + "' not found in enum '" + clazz.getName() + "' (Check case or spelling?)");
        }
    }

    private <E extends Enum<E>> @NotNull E getEnum(@NotNull ConfigurationSection section, @NotNull Class<E> clazz,
                                                   @NotNull String path) throws GameYMLException {
        if (!clazz.isEnum()) {
            throw new RuntimeException(clazz.getName() + " is not enum");
        }

        String value = section.getString(path);

        if (value == null) {
            throw new GameYMLNotFoundException(section, path);
        }

        return valueOfEnum(clazz, value);
    }

    private <E extends Enum<E>> @NotNull List<E> getEnumList(@NotNull ConfigurationSection section, @NotNull Class<E> clazz,
                                                             @NotNull String path) throws GameYMLException {
        List<String> names = section.getStringList(path);
        List<E> enums = new ArrayList<>();

        for (String name : names) {
            enums.add(valueOfEnum(clazz, name));
        }

        return enums;
    }

    private <T> T getStaticVariable(@NotNull String variableName, @NotNull Class<?> clazz,
                                    @NotNull Class<T> expectedType) throws GameYMLException {
        try {
            Field field = clazz.getField(variableName);
            field.setAccessible(true);

            if (!field.getType().equals(expectedType)) {
                throw new GameYMLException("Static variable '" + variableName + "' is not expected type '" +
                        expectedType.getName() + "' (from class '" + clazz.getName() + "')");
            }

            return expectedType.cast(field.get(null));
        } catch (NoSuchFieldException e) {
            throw new GameYMLException("Static variable '" + variableName + "' not found in class '" +
                    clazz.getName() + "'");
        } catch (IllegalAccessException e) {
            throw new GameYMLException("Failed to access static variable '" + variableName + "' from class '" +
                    clazz.getName() + "'");
        }
    }

    private <T> T getStaticVariable(@NotNull ConfigurationSection section, @NotNull Class<?> clazz,
                                    @NotNull Class<T> expectedType, @NotNull String path) throws GameYMLException {
        String variableName = getString(section, path);
        return getStaticVariable(variableName, clazz, expectedType);
    }

    private @NotNull String getString(@NotNull ConfigurationSection section, @NotNull String path)
            throws GameYMLException {
        String value = section.getString(path);

        if (value == null) {
            throw new GameYMLNotFoundException(section, path);
        }

        return value;
    }

    private int getInt(@NotNull ConfigurationSection section, @NotNull String path) throws GameYMLException {
        if (!section.contains(path)) {
            throw new GameYMLNotFoundException(section, path);
        }

        return section.getInt(path);
    }

    private double getDouble(@NotNull ConfigurationSection section, @NotNull String path) throws GameYMLException {
        if (!section.contains(path)) {
            throw new GameYMLNotFoundException(section, path);
        }

        return section.getDouble(path);
    }

    private float getFloat(@NotNull ConfigurationSection section, @NotNull String path)
            throws GameYMLException {
        return (float) getDouble(section, path);
    }

    private boolean getBoolean(@NotNull ConfigurationSection section, @NotNull String path)
            throws GameYMLException {
        if (!section.contains(path)) {
            throw new GameYMLNotFoundException(section, path);
        }

        return section.getBoolean(path);
    }

    private @NotNull PotionEffect getPotionEffect(@NotNull ConfigurationSection section, @NotNull String path)
            throws GameYMLException {
        ConfigurationSection potionSection = section.getConfigurationSection(path);

        if (potionSection == null) {
            throw new GameYMLNotFoundException(section, path);
        }

        try {
            return new PotionEffect(
                    getStaticVariable(potionSection, PotionEffectType.class, PotionEffectType.class,
                            "type"),
                    getInt(potionSection, "duration"),
                    getInt(potionSection, "amplifier"),
                    getBoolean(potionSection, "ambient"),
                    getBoolean(potionSection, "particles")
            );
        } catch (GameYMLNotFoundException e) {
            throw new GameYMLMissingRequiredFieldException(e);
        }
    }

    private @NotNull Location getLocation(@NotNull ConfigurationSection section, @NotNull String path)
            throws GameYMLException {
        ConfigurationSection locationSection = section.getConfigurationSection(path);

        if (locationSection == null) {
            throw new GameYMLNotFoundException(section, path);
        }

        try {
            return new Location(
                    Bukkit.getWorld(getString(locationSection, "world")),
                    getDouble(locationSection, "x"),
                    getDouble(locationSection, "y"),
                    getDouble(locationSection, "z"),
                    getFloatOrDefault(locationSection, "yaw", 0.0F),
                    getFloatOrDefault(locationSection, "pitch", 0.0F)
            );
        } catch (GameYMLNotFoundException e) {
            throw new GameYMLMissingRequiredFieldException(e);
        }
    }

    public record RuntimeItem(ItemStack itemStack, HashMap<Enchantment, Integer> enchantments, float dropChance) {}

    private @NotNull RuntimeItem getItem(@NotNull ConfigurationSection section, @NotNull String path) throws GameYMLException {
        ConfigurationSection itemSection = section.getConfigurationSection(path);

        if (itemSection == null) {
            throw new GameYMLNotFoundException(section, path);
        }

        Material material = getEnum(itemSection, Material.class, "item");
        int dyeColor = getIntOrDefault(itemSection, "dye-color", 10511680);
        TrimMaterial trimMaterial = getStaticVariableOrDefault(itemSection, TrimMaterial.class, TrimMaterial.class,
                "trim-material", null);
        TrimPattern trimPattern = getStaticVariableOrDefault(itemSection, TrimPattern.class, TrimPattern.class,
                "trim-pattern", null);

        if (trimMaterial != null && trimPattern == null) {
            throw new GameYMLException(path + ": trim-material is not null but trim-pattern is?");
        }
        if (trimPattern != null && trimMaterial == null) {
            throw new GameYMLException(path + ": trim-pattern is not null but trim-material is?");
        }

        ItemStack itemStack = new ItemStack(material);

        ItemMeta itemMeta = itemStack.getItemMeta();

        float dropChance = getFloatOrDefault(itemSection, "drop-chance", 0.1F);

        if (itemMeta == null) {
            return new RuntimeItem(itemStack, null, dropChance);
        }

        if (itemMeta instanceof LeatherArmorMeta leatherArmorMeta) {
            leatherArmorMeta.setColor(Color.fromRGB(dyeColor));
        }

        if (itemMeta instanceof ArmorMeta armorMeta) {
            if (trimMaterial != null && trimPattern != null) {
                armorMeta.setTrim(new ArmorTrim(
                        trimMaterial,
                        trimPattern
                ));
            }
        }

        HashMap<Enchantment, Integer> enchantmentsMap = new HashMap<>();
        ConfigurationSection enchantments = itemSection.getConfigurationSection("enchantments");
        if (enchantments != null) {
            for (String enchantmentKey : enchantments.getKeys(false)) {
                ConfigurationSection enchantment = enchantments.getConfigurationSection(enchantmentKey);
                if (enchantment == null) {
                    throw new GameYMLMissingRequiredFieldException(new GameYMLNotFoundException(
                            enchantments, "enchantment"
                    ));
                }

                enchantmentsMap.put(getStaticVariable(enchantmentKey.toUpperCase(), Enchantment.class, Enchantment.class),
                        getInt(enchantment, "lvl")
                );
            }
        }

        itemStack.setItemMeta(itemMeta);

        return new RuntimeItem(itemStack, enchantmentsMap, dropChance);
    }

    public static @NotNull ItemStack createRuntimeItemStack(@NotNull RuntimeItem runtimeItem) {
        ItemStack itemStack = runtimeItem.itemStack;
        ItemMeta itemMeta = itemStack.getItemMeta();

        if (itemMeta == null) return itemStack;
        if (runtimeItem.enchantments == null) return itemStack;

        runtimeItem.enchantments.forEach((enchantment, level) -> {
            if (level == -1) {
                itemMeta.addEnchant(enchantment, ThreadLocalRandom.current().nextInt(enchantment.getStartLevel(), enchantment.getMaxLevel() + 1), true);
            } else {
                itemMeta.addEnchant(enchantment, level, true);
            }
        });

        itemStack.setItemMeta(itemMeta);

        return itemStack;
    }

    public record Equipment(@Nullable RuntimeItem helmet, @Nullable RuntimeItem chestplate, @Nullable RuntimeItem leggings, @Nullable RuntimeItem boots,
                            @Nullable RuntimeItem mainHand, @Nullable RuntimeItem offHand) {
        public void putOn(@NotNull LivingEntity livingEntity) {
            EntityEquipment equipment = livingEntity.getEquipment();
            if (equipment == null) return;

            if (helmet != null) equipment.setHelmet(VanillaBosses.createRuntimeItemStack(helmet));
            if (chestplate != null) equipment.setChestplate(VanillaBosses.createRuntimeItemStack(chestplate));
            if (leggings != null) equipment.setLeggings(VanillaBosses.createRuntimeItemStack(leggings));
            if (boots != null) equipment.setBoots(VanillaBosses.createRuntimeItemStack(boots));
            if (mainHand != null) equipment.setItemInMainHand(VanillaBosses.createRuntimeItemStack(mainHand));
            if (offHand != null) equipment.setItemInOffHand(VanillaBosses.createRuntimeItemStack(offHand));

            if (helmet != null) equipment.setHelmetDropChance(helmet.dropChance);
            if (chestplate != null) equipment.setChestplateDropChance(chestplate.dropChance);
            if (leggings != null) equipment.setLeggingsDropChance(leggings.dropChance);
            if (boots != null) equipment.setBootsDropChance(boots.dropChance);
            if (mainHand != null) equipment.setItemInMainHandDropChance(mainHand.dropChance);
            if (offHand != null) equipment.setItemInOffHandDropChance(offHand.dropChance);
        }
    }

    private @NotNull Equipment getEquipment(@NotNull ConfigurationSection section, @NotNull String path)
            throws GameYMLException {
        ConfigurationSection equipmentSection = section.getConfigurationSection(path);

        if (equipmentSection == null) {
            throw new GameYMLNotFoundException(section, path);
        }

        try {
            return new Equipment(
                    getItemOrDefault(equipmentSection, "helmet", null),
                    getItemOrDefault(equipmentSection, "chestplate", null),
                    getItemOrDefault(equipmentSection, "leggings", null),
                    getItemOrDefault(equipmentSection, "boots", null),
                    getItemOrDefault(equipmentSection, "main-hand", null),
                    getItemOrDefault(equipmentSection, "off-hand", null)
            );
        } catch (GameYMLNotFoundException e) {
            throw new GameYMLMissingRequiredFieldException(e);
        }
    }

    private <E extends Enum<E>> @Nullable E getEnumOrDefault(@NotNull ConfigurationSection section, @NotNull Class<E> clazz,
                                                             @NotNull String path, E def) throws GameYMLException {
        try {
            return getEnum(section, clazz, path);
        } catch (GameYMLNotFoundException e) {
            return def;
        }
    }

    private <T> T getStaticVariableOrDefault(@NotNull ConfigurationSection section, @NotNull Class<?> clazz,
                                             @NotNull Class<T> expectedType, @NotNull String path,
                                             T def) throws GameYMLException {
        try {
            return getStaticVariable(section, clazz, expectedType, path);
        } catch (GameYMLNotFoundException e) {
            return def;
        }
    }

    private @Nullable String getStringOrDefault(@NotNull ConfigurationSection section, @NotNull String path,
                                                String def)
            throws GameYMLException {
        try {
            return getString(section, path);
        } catch (GameYMLNotFoundException e) {
            return def;
        }
    }

    private int getIntOrDefault(@NotNull ConfigurationSection section, @NotNull String path,
                                int def) throws GameYMLException {
        try {
            return getInt(section, path);
        } catch (GameYMLNotFoundException e) {
            return def;
        }
    }

    private double getDoubleOrDefault(@NotNull ConfigurationSection section, @NotNull String path,
                                      double def) throws GameYMLException {
        try {
            return getDouble(section, path);
        } catch (GameYMLNotFoundException e) {
            return def;
        }
    }

    private float getFloatOrDefault(@NotNull ConfigurationSection section, @NotNull String path,
                                    float def)
            throws GameYMLException {
        return (float) getDoubleOrDefault(section, path, def);
    }

    private boolean getBooleanOrDefault(@NotNull ConfigurationSection section, @NotNull String path,
                                        boolean def)
            throws GameYMLException {
        try {
            return getBoolean(section, path);
        } catch (GameYMLNotFoundException e) {
            return def;
        }
    }

    private @Nullable PotionEffect getPotionEffectOrDefault(@NotNull ConfigurationSection section,
                                                            @NotNull String path, PotionEffect def) throws GameYMLException {
        try {
            return getPotionEffect(section, path);
        } catch (GameYMLNotFoundException e) {
            return def;
        }
    }

    private @Nullable Equipment getEquipmentOrDefault(@NotNull ConfigurationSection section,
                                                      @NotNull String path, Equipment def) throws GameYMLException {
        try {
            return getEquipment(section, path);
        } catch (GameYMLNotFoundException e) {
            return def;
        }
    }

    private @Nullable RuntimeItem getItemOrDefault(@NotNull ConfigurationSection section,
                                                 @NotNull String path, RuntimeItem def) throws GameYMLException {
        try {
            return getItem(section, path);
        } catch (GameYMLNotFoundException e) {
            return def;
        }
    }

    private static double PHANTOM_CHANCE;
    private static double WITHERED_SKELETON_CHANCE;
    private static double FIRE_SKELETON_CHANCE;
    private static double SUPER_CREEPER_CHANCE;
    private static double SPECIAL_ZOMBIE_CHANCE;

    private static Particle PHANTOM_PARTICLE;
    private static double PHANTOM_EXTRA_DAMAGE;
    private static double PHANTOM_EXTRA_SPEED;

    private static Equipment WITHERED_SKELETON_EQUIPMENT;

    private static Equipment FIRE_SKELETON_EQUIPMENT;

    private static Particle SUPER_CREEPER_PARTICLE;
    private static double SUPER_CREEPER_EXTRA_DAMAGE;
    private static int SUPER_CREEPER_PARTICLE_AMOUNT;

    private static Equipment SPECIAL_ZOMBIE_EQUIPMENT;
    private static double SPECIAL_ZOMBIE_EXTRA_SPEED;
    private static double SPECIAL_ZOMBIE_EXTRA_DAMAGE;

    private static double BOSS_BAR_RADIUS;

    private static int BOSS_BLOCK_RADIUS;
    private static Material BOSS_BLOCK;
    private static boolean BOSS_BLOCK_RANDOM;
    private static boolean BOSS_BLOCK_ENABLED;
    private static int BOSS_BLOCK_RADIUS_Y;

    private FileConfiguration config = this.getConfig();
    private FoliaLib foliaLib;

    private List<Material> randomBlocks;

    @Override
    public void onEnable() {
        this.foliaLib = new FoliaLib(this);
        randomBlocks = new ArrayList<>();

        for (Material material : Material.values()) {
            if (material.isBlock() && material.isSolid()) {
                randomBlocks.add(material);
            }
        }

        this.foliaLib.getImpl().runTimer(() -> bossBars.forEach((le, kBossBar) -> fixNearbyPlayers(kBossBar.bossBar, le, true)), 20L, 20L);

        config.addDefault("boss-block-enabled", true);
        config.addDefault("boss-block", "NETHERRACK");
        config.addDefault("boss-block-radius", 4);
        config.addDefault("boss-block-radius-y", 2);

        config.addDefault("boss-bar-radius", 20.0);

        config.addDefault("phantom.chance", 0.5);
        config.addDefault("withered-skeleton.chance", 0.5);
        config.addDefault("fire-skeleton.chance", 0.5);
        config.addDefault("super-creeper.chance", 0.5);
        config.addDefault("special-zombie.chance", 0.5);

        config.addDefault("phantom.particle", "WAX_ON");
        config.addDefault("phantom.extra-damage", 2.5);
        config.addDefault("phantom.extra-speed", 0.3);

        config.addDefault("withered-skeleton.equipment.helmet.item", "DIAMOND_HELMET");
        config.addDefault("withered-skeleton.equipment.helmet.enchantments.THORNS.lvl", 1);

        config.addDefault("fire-skeleton.equipment.chestplate.item", "LEATHER_CHESTPLATE");
        config.addDefault("fire-skeleton.equipment.chestplate.drop-chance", 0.2F);
        config.addDefault("fire-skeleton.equipment.chestplate.dye-color", 0xFF0000);
        config.addDefault("fire-skeleton.equipment.chestplate.enchantments.PROTECTION_ENVIRONMENTAL.lvl", 1);
        config.addDefault("fire-skeleton.equipment.main-hand.item", "BOW");
        config.addDefault("fire-skeleton.equipment.main-hand.enchantments.ARROW_FIRE.lvl", 1);

        config.addDefault("super-creeper.particle", "ELECTRIC_SPARK");
        config.addDefault("super-creeper.particle-amount", 10);
        config.addDefault("super-creeper.extra-damage", 2.5);

        config.addDefault("special-zombie.equipment.leggings.item", "DIAMOND_LEGGINGS");
        config.addDefault("special-zombie.equipment.leggings.trim-material", "QUARTZ");
        config.addDefault("special-zombie.equipment.leggings.trim-pattern", "EYE");
        config.addDefault("special-zombie.equipment.main-hand.item", "DIAMOND_SWORD");
        config.addDefault("special-zombie.extra-speed", 0.2);
        config.addDefault("special-zombie.extra-damage", 2.5);

        config.options().copyDefaults(true);
        this.saveConfig();

        getServer().getPluginManager().registerEvents(this, this);

        try {
            reloadConfigPls();
        } catch (Exception e) {
            Bukkit.getLogger().severe("Couldn't load the server because you have configuration problems:");
            e.printStackTrace(System.err);
            Bukkit.shutdown();
            return;
        }

        getCommand("vbreload").setExecutor((commandSender, command, s, strings) -> {
            try {
                reloadConfigPls();
                commandSender.sendMessage("Reloaded config successfully.");
            } catch (Exception e) {
                commandSender.sendMessage(ChatColor.RED + "Failed to reload config because of: " + e.getMessage());
                commandSender.sendMessage(ChatColor.RED + "More info in console.");
                e.printStackTrace(System.err);
            }
            return false;
        });
    }

    public void reloadConfigPls() throws GameYMLException {
        config = YamlConfiguration.loadConfiguration(new File(getDataFolder() + "/config.yml"));

        PHANTOM_CHANCE = getDouble(config, "phantom.chance");
        WITHERED_SKELETON_CHANCE = getDouble(config, "withered-skeleton.chance");
        FIRE_SKELETON_CHANCE = getDouble(config, "fire-skeleton.chance");
        SUPER_CREEPER_CHANCE = getDouble(config, "super-creeper.chance");
        SPECIAL_ZOMBIE_CHANCE = getDouble(config, "special-zombie.chance");

        PHANTOM_PARTICLE = getEnumOrDefault(config, Particle.class, "phantom.particle", null);
        PHANTOM_EXTRA_DAMAGE = getDouble(config, "phantom.extra-damage");
        PHANTOM_EXTRA_SPEED = getDouble(config, "phantom.extra-speed");

        WITHERED_SKELETON_EQUIPMENT = getEquipmentOrDefault(config, "withered-skeleton.equipment", null);
        FIRE_SKELETON_EQUIPMENT = getEquipmentOrDefault(config, "fire-skeleton.equipment", null);

        SUPER_CREEPER_PARTICLE = getEnumOrDefault(config, Particle.class, "super-creeper.particle", null);
        SUPER_CREEPER_EXTRA_DAMAGE = getDouble(config, "super-creeper.extra-damage");
        SUPER_CREEPER_PARTICLE_AMOUNT = getInt(config, "super-creeper.particle-amount");

        SPECIAL_ZOMBIE_EQUIPMENT = getEquipmentOrDefault(config, "special-zombie.equipment", null);
        SPECIAL_ZOMBIE_EXTRA_SPEED = getDouble(config, "special-zombie.extra-speed");
        SPECIAL_ZOMBIE_EXTRA_DAMAGE = getDouble(config, "special-zombie.extra-damage");

        BOSS_BAR_RADIUS = getInt(config, "boss-bar-radius");

        BOSS_BLOCK_ENABLED = getBoolean(config, "boss-block-enabled");
        if (getString(config, "boss-block").equalsIgnoreCase("RANDOM")) {
            BOSS_BLOCK = Material.AIR;
            BOSS_BLOCK_RANDOM = true;
        } else {
            BOSS_BLOCK = getEnum(config, Material.class, "boss-block");
            BOSS_BLOCK_RANDOM = false;
        }
        BOSS_BLOCK_RADIUS = getInt(config, "boss-block-radius");
        BOSS_BLOCK_RADIUS_Y = getInt(config, "boss-block-radius-y");
    }

    private final NamespacedKey PHANTOM_BOSS_KEY = new NamespacedKey(this, "phantom_boss");
    private final NamespacedKey WITHERED_SKELETON_BOSS_KEY = new NamespacedKey(this, "withered_skeleton");
    private final NamespacedKey FIRE_SKELETON_BOSS_KEY = new NamespacedKey(this, "fire_skeleton");
    private final NamespacedKey SUPER_CREEPER_BOSS_KEY = new NamespacedKey(this, "super_creeper");
    private final NamespacedKey SPECIAL_ZOMBIE_BOSS_KEY = new NamespacedKey(this, "special_zombie");

    private record KeyedBossBar(@NotNull BossBar bossBar, @NotNull NamespacedKey namespacedKey) {}

    private final HashMap<LivingEntity, KeyedBossBar> bossBars = new HashMap<>();

    public void increaseAttribute(@NotNull LivingEntity entity, @NotNull Attribute attribute, double amount) {
        AttributeInstance instance = entity.getAttribute(attribute);
        double oldAmount = instance.getValue();
        instance.setBaseValue(oldAmount + amount);
    }

    public void bossBlocks(@NotNull LivingEntity livingEntity) {
        if (BOSS_BLOCK_ENABLED) {
            int radius = BOSS_BLOCK_RADIUS;
            Vector entityLocation = livingEntity.getLocation().toVector().subtract(new Vector(0, 1, 0));
            int entityY = livingEntity.getLocation().subtract(0.0, 1.0, 0.0).getBlockY();

            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + z * z <= radius * radius) {
                        for (int y = entityY - BOSS_BLOCK_RADIUS_Y; y < entityY + BOSS_BLOCK_RADIUS_Y; y++) {
                            Block currentBlock = livingEntity.getLocation().getWorld().getBlockAt(entityLocation.getBlockX() + x, y, entityLocation.getBlockZ() + z);

                            if (!currentBlock.getType().isAir() && currentBlock.getType() != Material.LIGHT) {
                                if (BOSS_BLOCK_RANDOM) {
                                    currentBlock.setType(randomBlocks.get(ThreadLocalRandom.current().nextInt(randomBlocks.size())), false);
                                } else {
                                    currentBlock.setType(BOSS_BLOCK, false);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    public void onEntitySpawn(@NotNull EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof LivingEntity entity)) return;

        switch (entity.getType()) {
            case PHANTOM -> {
                if (ThreadLocalRandom.current().nextDouble(1.0) < PHANTOM_CHANCE) {
                    entity.getPersistentDataContainer().set(PHANTOM_BOSS_KEY, PersistentDataType.BOOLEAN, true);
                    increaseAttribute(entity, Attribute.GENERIC_ATTACK_DAMAGE, PHANTOM_EXTRA_DAMAGE);
                    increaseAttribute(entity, Attribute.GENERIC_MOVEMENT_SPEED, PHANTOM_EXTRA_SPEED);
                    bossBlocks(entity);
                }
            }
            case SKELETON -> {
                if (ThreadLocalRandom.current().nextDouble(1.0) < FIRE_SKELETON_CHANCE) {
                    entity.getPersistentDataContainer().set(FIRE_SKELETON_BOSS_KEY, PersistentDataType.BOOLEAN, true);
                    if (FIRE_SKELETON_EQUIPMENT != null) {
                        FIRE_SKELETON_EQUIPMENT.putOn(entity);
                    }
                    bossBlocks(entity);
                } else if (ThreadLocalRandom.current().nextDouble(1.0) < WITHERED_SKELETON_CHANCE) {
                    entity.getPersistentDataContainer().set(WITHERED_SKELETON_BOSS_KEY, PersistentDataType.BOOLEAN, true);
                    if (WITHERED_SKELETON_EQUIPMENT != null) {
                        WITHERED_SKELETON_EQUIPMENT.putOn(entity);
                    }
                    bossBlocks(entity);
                }
            }
            case CREEPER -> {
                if (ThreadLocalRandom.current().nextDouble(1.0) < SUPER_CREEPER_CHANCE) {
                    ((Creeper) entity).setPowered(true);
                    entity.getPersistentDataContainer().set(SUPER_CREEPER_BOSS_KEY, PersistentDataType.BOOLEAN, true);
                    bossBlocks(entity);
                }
            }
            case ZOMBIE -> {
                if (ThreadLocalRandom.current().nextDouble(1.0) < SPECIAL_ZOMBIE_CHANCE) {
                    entity.getPersistentDataContainer().set(SPECIAL_ZOMBIE_BOSS_KEY, PersistentDataType.BOOLEAN, true);
                    if (SPECIAL_ZOMBIE_EQUIPMENT != null) {
                        SPECIAL_ZOMBIE_EQUIPMENT.putOn(entity);
                    }
                    increaseAttribute(entity, Attribute.GENERIC_MOVEMENT_SPEED, SPECIAL_ZOMBIE_EXTRA_SPEED);
                    increaseAttribute(entity, Attribute.GENERIC_ATTACK_DAMAGE, SPECIAL_ZOMBIE_EXTRA_DAMAGE);
                    bossBlocks(entity);
                }
            }
        }
    }

    @EventHandler
    public void onEntityMove(@NotNull EntityMoveEvent event) {
        LivingEntity entity = event.getEntity();

        switch (entity.getType()) {
            case PHANTOM -> {
                if (entity.getTicksLived() % 2 == 0) {
                    if (entity.getPersistentDataContainer().has(PHANTOM_BOSS_KEY)) {
                        entity.getWorld().spawnParticle(PHANTOM_PARTICLE, event.getFrom(), 1);
                    }
                }
            }
            case CREEPER -> {
                if (entity.getTicksLived() % 2 == 0) {
                    if (entity.getPersistentDataContainer().has(SUPER_CREEPER_BOSS_KEY)) {
                        entity.getWorld().spawnParticle(SUPER_CREEPER_PARTICLE, entity.getLocation().add(0.0, entity.getHeight() / 2.0, 0.0),
                                SUPER_CREEPER_PARTICLE_AMOUNT
                        );
                    }
                }
            }
        }

        if (event.getTo().getBlockX() == event.getFrom().getBlockX() &&
                event.getTo().getBlockY() == event.getFrom().getBlockY() &&
                event.getTo().getBlockZ() == event.getFrom().getBlockZ()) {
            return;
        }

        if (getType(entity) != null) {
            bossBlocks(entity);
        }
    }

    public @Nullable BossType getType(@NotNull LivingEntity livingEntity) {
        if (livingEntity.getPersistentDataContainer().has(PHANTOM_BOSS_KEY)) return BossType.PHANTOM;
        if (livingEntity.getPersistentDataContainer().has(WITHERED_SKELETON_BOSS_KEY)) return BossType.WITHERED_SKELETON;
        if (livingEntity.getPersistentDataContainer().has(FIRE_SKELETON_BOSS_KEY)) return BossType.FIRE_SKELETON;
        if (livingEntity.getPersistentDataContainer().has(SUPER_CREEPER_BOSS_KEY)) return BossType.SUPER_CREEPER;
        if (livingEntity.getPersistentDataContainer().has(SPECIAL_ZOMBIE_BOSS_KEY)) return BossType.SPECIAL_ZOMBIE;

        return null;
    }

    public enum BossType {
        PHANTOM("Stronger Phantom"),
        WITHERED_SKELETON("Withered Skeleton"),
        FIRE_SKELETON("Fire Skeleton"),
        SUPER_CREEPER("Supercharged Creeper"),
        SPECIAL_ZOMBIE("Specialized Zombie");

        public final String name;

        BossType(@NotNull String name) {
            this.name = name;
        }
    }

    public void fixNearbyPlayers(@NotNull BossBar bossBar, @NotNull LivingEntity livingEntity, boolean add) {
        HashSet<Player> nearby = new HashSet<>();

        if (add) {
            foliaLib.getImpl().runAtEntity(livingEntity, wrappedTask -> {
                for (Entity e : livingEntity.getNearbyEntities(BOSS_BAR_RADIUS, BOSS_BAR_RADIUS, BOSS_BAR_RADIUS)) {
                    if (e instanceof Player p) {
                        nearby.add(p);
                        bossBar.addPlayer(p);
                    }
                }
            });
        }

        ArrayList<? extends Player> online = new ArrayList<>(Bukkit.getOnlinePlayers().stream().toList());
        online.removeIf(nearby::contains);

        for (Player p : online) {
            bossBar.removePlayer(p);
        }
    }

    @EventHandler
    public void onEntityAddToWorld(@NotNull EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof LivingEntity livingEntity)) return;

        BossType bossType = getType(livingEntity);
        if (bossType == null) return;

        NamespacedKey namespacedKey = new NamespacedKey(this, UUID.randomUUID().toString());
        BossBar bossBar = Bukkit.createBossBar(namespacedKey, bossType.name, BarColor.PINK, BarStyle.SOLID);
        bossBar.setVisible(true);
        bossBar.setProgress(1.0);
        bossBars.put(livingEntity, new KeyedBossBar(bossBar, namespacedKey));

        fixNearbyPlayers(bossBar, livingEntity, true);
    }

    @EventHandler
    public void onEntityDamage(@NotNull EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity livingEntity)) return;

        BossType bossType = getType(livingEntity);

        if (bossType == null) return;

        KeyedBossBar bossBar = bossBars.get(livingEntity);
        double health = (livingEntity.getHealth() - event.getFinalDamage());
        if (health < 0)
            health = 0;
        bossBar.bossBar.setProgress(health / livingEntity.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue());
    }

    public void handleEntityRemoval(@NotNull LivingEntity livingEntity, boolean remove) {
        KeyedBossBar keyedBossBar = bossBars.get(livingEntity);

        if (keyedBossBar == null) return;

        keyedBossBar.bossBar.removeAll();
        Bukkit.removeBossBar(keyedBossBar.namespacedKey);
        if (remove)
            bossBars.remove(livingEntity);
    }

    @EventHandler
    public void onEntityRemoval(@NotNull EntityRemoveFromWorldEvent event) {
        if (!(event.getEntity() instanceof LivingEntity livingEntity)) return;

        handleEntityRemoval(livingEntity, true);
    }

    @Override
    public void onDisable() {
        bossBars.forEach((le, unused) -> handleEntityRemoval(le, false));
    }

    @EventHandler
    public void onBowShoot(@NotNull EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Skeleton skeleton)) return;
        if (!skeleton.getPersistentDataContainer().has(WITHERED_SKELETON_BOSS_KEY)) return;

        Vector direction = event.getProjectile().getVelocity();
        Location loc = event.getProjectile().getLocation();
        event.getProjectile().remove();

        direction.setY(direction.getY() - 0.15);
        direction.setX(direction.getX() * 2);
        direction.setZ(direction.getZ() * 2);

        skeleton.getWorld().spawnEntity(loc, EntityType.WITHER_SKULL).setVelocity(direction);
    }

    @EventHandler
    public void onEntityDamageByEntity(@NotNull EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Creeper creeper) {
            if (creeper.getPersistentDataContainer().has(SUPER_CREEPER_BOSS_KEY)) {
                event.setDamage(event.getDamage() + SUPER_CREEPER_EXTRA_DAMAGE);
            }
        }
    }

}
