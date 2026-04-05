package me.bebo.graveBox;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class GraveBox extends JavaPlugin implements Listener {

    private final Map<Location, Grave> graveLocations = new HashMap<>();
    private final Map<UUID, Grave> openVirtualGraves = new HashMap<>(); // Player UUID -> Grave
    private final Map<UUID, Integer> deathStats = new HashMap<>();
    private Connection database;

    private File gravesFolder;
    private Material graveMaterial;
    private boolean explosionProtection;
    private boolean indestructible;
    private boolean autoRemove;
    private boolean dropItemsOnDestroy;
    private boolean allowOthersToOpen;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();
        setupDatabase();
        loadGravesFromFiles();

        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("gravestats").setExecutor(this);

        printBanner();
        getLogger().info("GraveBox v" + getDescription().getVersion() + " (Virtual Inventory Mode) has been enabled!");
    }

    @Override
    public void onDisable() {
        for (Map.Entry<UUID, Grave> entry : openVirtualGraves.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.getOpenInventory().getTopInventory() != null) {
                saveGrave(entry.getValue(), player.getOpenInventory().getTopInventory());
            }
        }
        closeDatabase();
        getLogger().info("GraveBox has been disabled!");
    }

    private void loadConfigValues() {
        gravesFolder = new File(getDataFolder(), "graves");
        if (!gravesFolder.exists()) {
            gravesFolder.mkdirs();
        }

        graveMaterial = Material.matchMaterial(getConfig().getString("grave.material", "CHEST"));
        if (graveMaterial == null || !graveMaterial.isBlock()) {
            getLogger().warning("Invalid grave material in config.yml! Defaulting to CHEST.");
            graveMaterial = Material.CHEST;
        }
        explosionProtection = getConfig().getBoolean("grave.explosion-protection", true);
        indestructible = getConfig().getBoolean("grave.indestructible", true);
        autoRemove = getConfig().getBoolean("grave.auto-remove", true);
        dropItemsOnDestroy = getConfig().getBoolean("grave.drop-items-on-destroy", false);
        allowOthersToOpen = getConfig().getBoolean("grave.allow-others-to-open", false);
    }

    private void loadGravesFromFiles() {
        File[] graveFiles = gravesFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (graveFiles == null) return;

        int loadedCount = 0;
        for (File file : graveFiles) {
            try {
                UUID graveId = UUID.fromString(file.getName().replace(".yml", ""));
                FileConfiguration graveConfig = YamlConfiguration.loadConfiguration(file);
                Location loc = graveConfig.getLocation("location");
                UUID ownerId = UUID.fromString(graveConfig.getString("owner-uuid"));

                if (loc != null && ownerId != null && loc.isWorldLoaded()) {
                    if (loc.getBlock().getType() == graveMaterial) {
                        Grave grave = new Grave(graveId, ownerId, loc);
                        graveLocations.put(loc, grave);
                        loadedCount++;
                    } else {
                        getLogger().warning("Grave file " + file.getName() + " points to a location where the grave block is missing. Deleting file.");
                        file.delete();
                    }
                }
            } catch (Exception e) {
                getLogger().warning("Failed to load grave from file: " + file.getName() + ". It might be corrupted.");
            }
        }
        if (loadedCount > 0) {
            getLogger().info("Loaded " + loadedCount + " graves from disk.");
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Location deathLocation = player.getLocation().getBlock().getLocation();

        if (!deathLocation.getBlock().getType().isAir() && !deathLocation.getBlock().isLiquid()) {
            deathLocation = deathLocation.add(0, 1, 0);
            if (!deathLocation.getBlock().getType().isAir()) {
                getLogger().warning("Could not place grave for " + player.getName() + " at " + deathLocation + ", location is obstructed. Items will drop normally.");
                return;
            }
        }

        List<ItemStack> itemsToStore = new ArrayList<>();
        for (ItemStack item : player.getInventory().getArmorContents()) if (isValidItem(item)) itemsToStore.add(item);
        if (isValidItem(player.getInventory().getItemInOffHand())) itemsToStore.add(player.getInventory().getItemInOffHand());
        for (ItemStack item : player.getInventory().getStorageContents()) if (isValidItem(item)) itemsToStore.add(item);

        if (itemsToStore.isEmpty()) {
            return;
        }

        UUID graveId = UUID.randomUUID();
        Grave grave = new Grave(graveId, player.getUniqueId(), deathLocation);
        saveGraveToFile(grave, itemsToStore);
        graveLocations.put(deathLocation, grave);

        deathLocation.getBlock().setType(graveMaterial);

        updatePlayerStats(player);
        sendGraveLocationMessage(player, deathLocation);
        sendDiscordNotification(player, deathLocation);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
        player.getInventory().clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGraveInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }

        Grave grave = graveLocations.get(event.getClickedBlock().getLocation());
        if (grave == null) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (!allowOthersToOpen && !grave.getOwnerId().equals(player.getUniqueId()) && !player.hasPermission("gravebox.bypass")) {
            player.sendMessage(tl("messages.not-your-grave"));
            return;
        }

        if (openVirtualGraves.containsKey(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "You already have a grave open!");
            return;
        }

        File graveFile = new File(gravesFolder, grave.getId().toString() + ".yml");
        if (!graveFile.exists()) {
            player.sendMessage(ChatColor.RED + "This grave's data file is missing! Removing the marker.");
            event.getClickedBlock().setType(Material.AIR);
            graveLocations.remove(event.getClickedBlock().getLocation());
            return;
        }

        FileConfiguration graveConfig = YamlConfiguration.loadConfiguration(graveFile);
        List<?> itemsList = graveConfig.getList("items", new ArrayList<>());

        String graveName = tl("grave.custom-name", "&6{player}'s Grave").replace("{player}", Bukkit.getOfflinePlayer(grave.getOwnerId()).getName());
        Inventory virtualInventory = Bukkit.createInventory(null, 54, graveName);

        for(Object obj : itemsList) {
            if (obj instanceof ItemStack) {
                virtualInventory.addItem((ItemStack) obj);
            }
        }

        openVirtualGraves.put(player.getUniqueId(), grave);
        player.openInventory(virtualInventory);
    }

    @EventHandler
    public void onVirtualGraveClose(InventoryCloseEvent event) {
        Player player = (Player) event.getPlayer();
        Grave grave = openVirtualGraves.remove(player.getUniqueId());

        if (grave == null) {
            return;
        }

        Inventory inv = event.getInventory();
        if (autoRemove && isInventoryEmpty(inv)) {
            deleteGrave(grave);
            player.sendMessage(tl("messages.grave-emptied"));
        } else {
            saveGrave(grave, inv);
        }
    }

    @EventHandler
    public void onGraveBreak(BlockBreakEvent event) {
        // Check if the broken block is a registered grave location
        Grave grave = graveLocations.get(event.getBlock().getLocation());
        if (grave == null) {
            return; // Not a grave, do nothing.
        }

        // If graves are set to be indestructible in the config, ALWAYS cancel the event.
        // This now applies to all players, including OPs, ignoring any permissions.
        if (indestructible) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(tl("messages.cannot-destroy"));
            return;
        }

        // The code below will only run if 'indestructible' is set to 'false' in the config.

        // If configured, drop the items on the ground.
        if (dropItemsOnDestroy) {
            List<ItemStack> items = getItemsFromGraveFile(grave);
            for(ItemStack item : items) {
                event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), item);
            }
        }

        // Delete the grave data file and remove the block from the world.
        deleteGrave(grave);
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!explosionProtection) return;
        event.blockList().removeIf(block -> graveLocations.containsKey(block.getLocation()));
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!explosionProtection) return;
        event.blockList().removeIf(block -> graveLocations.containsKey(block.getLocation()));
    }

    private void saveGrave(Grave grave, Inventory inventory) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : inventory.getContents()) {
            if (isValidItem(item)) {
                items.add(item);
            }
        }
        saveGraveToFile(grave, items);
    }

    private void saveGraveToFile(Grave grave, List<ItemStack> items) {
        File graveFile = new File(gravesFolder, grave.getId().toString() + ".yml");
        FileConfiguration graveConfig = new YamlConfiguration();
        graveConfig.set("grave-id", grave.getId().toString());
        graveConfig.set("owner-uuid", grave.getOwnerId().toString());
        graveConfig.set("location", grave.getLocation());
        graveConfig.set("items", items);
        try {
            graveConfig.save(graveFile);
        } catch (IOException e) {
            getLogger().severe("Could not save grave file: " + graveFile.getName());
            e.printStackTrace();
        }
    }

    private List<ItemStack> getItemsFromGraveFile(Grave grave) {
        File graveFile = new File(gravesFolder, grave.getId().toString() + ".yml");
        if (!graveFile.exists()) return new ArrayList<>();
        FileConfiguration config = YamlConfiguration.loadConfiguration(graveFile);
        List<?> list = config.getList("items");
        List<ItemStack> items = new ArrayList<>();
        if (list != null) {
            for (Object o : list) {
                if (o instanceof ItemStack) {
                    items.add((ItemStack) o);
                }
            }
        }
        return items;
    }

    private void deleteGrave(Grave grave) {
        graveLocations.remove(grave.getLocation());
        if (grave.getLocation().getBlock().getType() == graveMaterial) {
            grave.getLocation().getBlock().setType(Material.AIR);
        }
        File graveFile = new File(gravesFolder, grave.getId().toString() + ".yml");
        if (graveFile.exists()) {
            graveFile.delete();
        }
    }

    private boolean isInventoryEmpty(Inventory inv) {
        for (ItemStack item : inv.getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                return false;
            }
        }
        return true;
    }

    private boolean isValidItem(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) return false;
        List<String> blacklist = getConfig().getStringList("advanced.blacklisted-items");
        return !blacklist.contains(item.getType().toString());
    }

    private void setupDatabase() {
        if (!getConfig().getBoolean("stats.enabled", false)) return;
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            String dbPath = "jdbc:sqlite:" + getDataFolder().getAbsolutePath() + "/" + getConfig().getString("stats.database.file", "stats.db");
            database = DriverManager.getConnection(dbPath);
            try (Statement stmt = database.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS death_stats (uuid TEXT PRIMARY KEY, deaths INTEGER NOT NULL, last_death TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
                try (ResultSet rs = stmt.executeQuery("SELECT uuid, deaths FROM death_stats")) {
                    while (rs.next()) {
                        deathStats.put(UUID.fromString(rs.getString("uuid")), rs.getInt("deaths"));
                    }
                }
            }
        } catch (SQLException e) {
            getLogger().severe("Database setup failed! Statistics will not work.");
            e.printStackTrace();
        }
    }

    private void closeDatabase() {
        try {
            if (database != null && !database.isClosed()) database.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void updatePlayerStats(Player player) {
        if (!getConfig().getBoolean("stats.enabled", false) || database == null) return;
        UUID uuid = player.getUniqueId();
        int newDeathCount = deathStats.getOrDefault(uuid, 0) + 1;
        deathStats.put(uuid, newDeathCount);
        try (PreparedStatement ps = database.prepareStatement("INSERT INTO death_stats (uuid, deaths, last_death) VALUES(?, ?, CURRENT_TIMESTAMP) ON CONFLICT(uuid) DO UPDATE SET deaths = excluded.deaths, last_death = excluded.last_death")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, newDeathCount);
            ps.executeUpdate();
        } catch (SQLException e) {
            getLogger().warning("Failed to update player stats in database: " + e.getMessage());
        }
    }

    private void sendGraveLocationMessage(Player player, Location loc) {
        String message = tl("messages.grave-created").replace("{x}", String.valueOf(loc.getBlockX())).replace("{y}", String.valueOf(loc.getBlockY())).replace("{z}", String.valueOf(loc.getBlockZ())).replace("{world}", loc.getWorld().getName());
        player.sendMessage(message);
    }

    private void sendDiscordNotification(Player player, Location loc) {
        if (!getConfig().getBoolean("discord.enabled", false)) return;
        String webhookUrl = getConfig().getString("discord.webhook-url");
        if (webhookUrl == null || webhookUrl.isEmpty() || webhookUrl.contains("your_webhook_here")) return;

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String message = getConfig().getString("discord.message", "**{player}** died at `{x}, {y}, {z}` in world `{world}`").replace("{player}", player.getName()).replace("{x}", String.valueOf(loc.getBlockX())).replace("{y}", String.valueOf(loc.getBlockY())).replace("{z}", String.valueOf(loc.getBlockZ())).replace("{world}", loc.getWorld().getName());
                long color = Long.parseLong(getConfig().getString("discord.embed-color", "FF0000").replace("#", ""), 16);
                String jsonPayload = "{\"embeds\": [{\"title\": \"Grave Created\",\"description\": \"%s\",\"color\": %d,\"footer\": {\"text\": \"GraveBox v%s\"}}]}".formatted(message, color, getDescription().getVersion());
                HttpURLConnection connection = (HttpURLConnection) new URL(webhookUrl).openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json; utf-8");
                connection.setDoOutput(true);
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
                }
                connection.getResponseCode();
                connection.disconnect();
            } catch (Exception e) {
                getLogger().warning("Failed to send Discord notification: " + e.getMessage());
            }
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("gravestats")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
                return true;
            }
            int deaths = deathStats.getOrDefault(player.getUniqueId(), 0);
            player.sendMessage(tl("messages.stats-message") + deaths);
            return true;
        }
        return false;
    }

    private String tl(String path, String def) {
        return ChatColor.translateAlternateColorCodes('&', getConfig().getString(path, def));
    }

    private String tl(String path) {
        return tl(path, "&cMissing message from config.yml: " + path);
    }

    private void printBanner() {
        String banner = """

                ██████╗ ███████╗██████╗  ██████╗      ██████╗ ██████╗ ██████╗ ███████╗\s
                ██╔══██╗██╔════╝██╔══██╗██╔═══██╗    ██╔════╝██╔═══██╗██╔══██╗██╔════╝\s
                ██████╔╝█████╗  ██████╔╝██║   ██║    ██║     ██║   ██║██║  ██║█████╗  \s
                ██╔══██╗██╔══╝  ██╔══██╗██║   ██║    ██║     ██║   ██║██║  ██║██╔══╝  \s
                ██████╔╝███████╗██████╔╝╚██████╔╝    ╚██████╗╚██████╔╝██████╔╝███████╗
                ╚═════╝ ╚══════╝╚═════╝  ╚═════╝      ╚═════╝ ╚═════╝ ╚═════╝ ╚══════╝
                               GraveBox v%s - Complete Item Protection
                ============================================================================
                """.formatted(getDescription().getVersion());
        Bukkit.getConsoleSender().sendMessage(ChatColor.GOLD + banner);
    }

    private record Grave(UUID id, UUID ownerId, Location location) {
        public UUID getId() { return id; }
        public UUID getOwnerId() { return ownerId; }
        public Location getLocation() { return location; }
    }
}