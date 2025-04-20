package de.heiligekiwi.playerdata;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;
import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Collectors;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public class PlayerDataPlugin extends JavaPlugin implements Listener, TabCompleter {
    private File playerDataFile;
    private File whitelistFile;
    private File messagesFile;
    private File lastLoginFile;
    private File configFile; // Neue Datei für config.yml
    private Map<String, Map<String, Object>> playerDataMap = new HashMap<>();
    private Map<String, List<String>> playerNotesMap = new HashMap<>();
    private Map<String, Object> whitelistMap = new HashMap<>();
    private LuckPerms luckPermsApi;
    private Map<String, String> messagesMap = new HashMap<>();
    private Economy econ = null;
    private static final DateTimeFormatter DATE_TIME_FORMATTER;
    private Map<String, LocalDateTime> lastLoginMap = new HashMap<>();
    private Gson gson;
    private static final byte XOR_KEY = -91;

    public PlayerDataPlugin() {
    }

    @Override
    public void onEnable() {
        // Banner-Text als Multiline-String definieren
        String banner = ""
                + "loading YMT\n"
                + " __     __ __  __  _______       _____   _____  \n"
                + " \\ \\   / /|  \\/  ||__   __|     |  __ \\ |  __ \\ \n"
                + "  \\ \\_/ / | \\  / |   | | ______ | |__) || |  | |\n"
                + "   \\   /  | |\\/| |   | ||______||  ___/ | |  | |\n"
                + "    | |   | |  | |   | |        | |     | |__| |\n"
                + "    |_|   |_|  |_|   |_|        |_|     |_____/\n"
                + "                                                \n"
                + "YMT-Playerdata\n";
        // Zeige das Banner im Log
        this.getLogger().info(banner);

        // Überprüfe, ob LuckPerms installiert ist
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            this.getLogger().severe("LuckPerms is required but not found! Disabling plugin...");
            Bukkit.getPluginManager().disablePlugin(this);
            return; // Beende die Ausführung von onEnable(), wenn LuckPerms nicht gefunden wird
        } else {
            this.luckPermsApi = LuckPermsProvider.get();
        }

        // Überprüfe, ob Vault installiert ist
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            this.getLogger().warning("Vault plugin not found! Money tracking will be disabled.");
        } else {
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (rsp != null) {
                this.econ = rsp.getProvider();
            }
        }

        // Initialisiere Dateien
        this.playerDataFile = new File(this.getDataFolder(), "playerdata.yml");
        this.whitelistFile = new File(this.getDataFolder(), "whitelist.yml");
        this.messagesFile = new File(this.getDataFolder(), "messages.yml");
        this.lastLoginFile = new File(this.getDataFolder(), "lastlogin.json");
        this.configFile = new File(this.getDataFolder(), "config.yml"); // Initialisiere config.yml

        this.ensureResourceFileExists(this.messagesFile, "messages.yml");
        this.ensureFileExists(this.playerDataFile);
        this.ensureFileExists(this.whitelistFile);
        this.ensureFileExists(this.lastLoginFile);
        this.ensureConfigFileExists(); // Stelle sicher, dass config.yml existiert

        // Initialisiere Gson mit LocalDateTime-Serailizer/Deserializer
        this.gson = (new GsonBuilder())
                .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeSerializer())
                .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeDeserializer())
                .create();

        // Lade Daten
        this.loadPlayerData();
        this.loadWhitelistData();
        this.loadMessages();
        this.loadLastLoginData();
        this.loadConfig(); // Lade die Konfigurationswerte aus config.yml

        // Synchronisiere mit Minecraft-Whitelist
        this.synchronizeWithMinecraftWhitelist();

        // Registriere Events und Befehle
        Bukkit.getPluginManager().registerEvents(this, this);
        this.getCommand("playerip").setExecutor(this);
        this.getCommand("note").setExecutor(this);
        this.getCommand("note").setTabCompleter(this);
        this.getCommand("pdp").setExecutor(this);
        this.getCommand("pdp").setTabCompleter(this);

        // Starte Timer für wiederkehrende Aufgaben
        (new BukkitRunnable() {
            public void run() {
                PlayerDataPlugin.this.updatePlayerMoney();
            }
        }).runTaskTimer(this, 0L, 12000L);
        (new BukkitRunnable() {
            public void run() {
                PlayerDataPlugin.this.removeOldPlayerData();
            }
        }).runTaskTimer(this, 0L, 1728000L);

        // Bestätige, dass das Plugin aktiviert wurde
        this.getLogger().info("PlayerDataPlugin has been enabled!");
    }

    @Override
    public void onDisable() {
        this.savePlayerData();
        this.saveWhitelistData();
        this.saveLastLoginData();
        this.getLogger().info("PlayerDataPlugin has been disabled!");
    }

    private void ensureConfigFileExists() {
        if (!this.configFile.exists()) {
            try {
                if (!this.configFile.getParentFile().exists()) {
                    this.configFile.getParentFile().mkdirs();
                }
                InputStream inputStream = this.getResource("config.yml");
                if (inputStream != null) {
                    Files.copy(inputStream, this.configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    this.getLogger().info("Copied default config.yml to: " + this.configFile.getAbsolutePath());
                } else {
                    this.createDefaultConfig();
                }
            } catch (IOException e) {
                this.getLogger().log(Level.SEVERE, "Failed to create config.yml", e);
            }
        } else {
            this.getLogger().info("config.yml already exists: " + this.configFile.getAbsolutePath());
        }
    }

    private void createDefaultConfig() {
        try (FileWriter writer = new FileWriter(this.configFile)) {
            writer.write("# Config für PlayerDataPlugin\n");
            writer.write("\n");
            writer.write("# 9. Automatisches Löschen alter Daten deaktivieren?\n");
            writer.write("disable_data_cleanup: false\n");
            writer.write("\n");
            writer.write("# 6. Intervall für das Aktualisieren von Spieler-Geldständen (in Minuten)\n");
            writer.write("money_update_interval: 6\n");
            writer.write("\n");
            writer.write("# 9. Anzahl der Tage, nach denen alte Daten gelöscht werden sollen\n");
            writer.write("data_cleanup_days_threshold: 6\n");
            writer.write("\n");
            writer.write("# 8. Banner beim Start anzeigen?\n");
            writer.write("show_banner_on_startup: true\n");
            writer.write("\n");
            writer.write("# 2. Verschlüsselung aktivieren/deaktivieren\n");
            writer.write("enable_encryption: true\n");
            writer.write("\n");
            writer.write("# 4. IP-Änderungsbenachrichtigungen aktivieren?\n");
            writer.write("notify_ip_changes: true\n");
            writer.write("\n");
            writer.write("# 5. Whitelist-Synchronisation aktivieren?\n");
            writer.write("enable_whitelist_sync: true\n");
            writer.write("\n");
            writer.write("# Beispiel für zusätzliche Nachrichten (kann angepasst werden)\n");
            writer.write("messages:\n");
            writer.write("  no_permission: \"&cDu hast keine Berechtigung, diesen Befehl auszuführen!\"\n");
            writer.write("  usage_playerip: \"&cVerwendung: /playerip <Spielername>\"\n");
            writer.write("  player_ip_found: \"&aDie IP von %player% ist %ip%.\"\n");
            writer.write("  player_ip_not_found: \"&cDie IP von %player% wurde nicht gefunden.\"\n");
            writer.write("  player_not_found: \"&cDer Spieler %player% wurde nicht gefunden.\"\n");
            writer.write("\n");
            writer.write("# Beispiel für eine Liste von erlaubten Befehlen (optional)\n");
            writer.write("allowed_commands:\n");
            writer.write("  - playerip\n");
            writer.write("  - note\n");
            writer.write("  - pdp\n");

            this.getLogger().info("Created default config.yml at: " + this.configFile.getAbsolutePath());
        } catch (IOException e) {
            this.getLogger().log(Level.SEVERE, "Failed to create default config.yml", e);
        }
    }

    private void loadConfig() {
        if (this.configFile.exists()) {
            try {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(this.configFile);

                // Laden Sie die Werte aus der Konfiguration
                boolean disableDataCleanup = config.getBoolean("disable_data_cleanup", false);
                int moneyUpdateInterval = config.getInt("money_update_interval", 6);
                int dataCleanupDaysThreshold = config.getInt("data_cleanup_days_threshold", 6);
                boolean showBannerOnStartup = config.getBoolean("show_banner_on_startup", true);
                boolean enableEncryption = config.getBoolean("enable_encryption", true);
                boolean notifyIpChanges = config.getBoolean("notify_ip_changes", true);
                boolean enableWhitelistSync = config.getBoolean("enable_whitelist_sync", true);

                // Beispiel für das Laden von Nachrichten
                ConfigurationSection messagesSection = config.getConfigurationSection("messages");
                if (messagesSection != null) {
                    for (String key : messagesSection.getKeys(false)) {
                        this.messagesMap.put(key, messagesSection.getString(key, "Message not found"));
                    }
                }

                this.getLogger().info("Loaded config.yml successfully!");
            } catch (Exception e) {
                this.getLogger().log(Level.WARNING, "Failed to load config.yml", e);
            }
        } else {
            this.getLogger().warning("config.yml not found! Creating a new one...");
            this.ensureConfigFileExists();
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();
        UUID playerUUID = player.getUniqueId();
        String playerIP;
        try {
            playerIP = player.getAddress().getAddress().getHostAddress();
        } catch (Exception var15) {
            this.getLogger().warning("Failed to retrieve IP address for player: " + playerName);
            playerIP = "Unknown";
        }
        if ("on".equals(this.whitelistMap.get("whitelist")) && !this.isPlayerWhitelisted(playerName)) {
            player.kickPlayer(this.getMessage("not_whitelisted", Map.of("player", playerName)));
        } else {
            if (!this.playerDataMap.containsKey(playerName)) {
                Map<String, Object> stats = new HashMap<>();
                stats.put("playtime", 0);
                stats.put("kills", 0);
                stats.put("deaths", 0);
                Map<String, Object> playerInfo = new HashMap<>();
                playerInfo.put("stats", stats);
                this.playerDataMap.put(playerName, playerInfo);
            }
            Map<String, Object> playerInfo = (Map<String, Object>) this.playerDataMap.getOrDefault(playerName, new HashMap<>());
            Map<String, Object> stats = (Map<String, Object>) playerInfo.get("stats");
            if (stats == null) {
                stats = new HashMap<>();
                playerInfo.put("stats", stats);
            }
            double playerMoney = 0.0F;
            if (this.econ != null) {
                playerMoney = this.econ.getBalance(player);
            }
            stats.put("money", playerMoney);
            String previousIP = (String) playerInfo.get("Playerip");
            boolean ipChanged = previousIP != null && !previousIP.equals(playerIP);
            if (ipChanged) {
                this.notifyIPChange(playerName, previousIP, playerIP);
            }
            List<String> ipHistory = (List<String>) playerInfo.get("ip_history");
            if (ipHistory == null) {
                ipHistory = new ArrayList<>();
                playerInfo.put("ip_history", ipHistory);
            }
            String ipWithLocation = playerIP + " (" + this.fetchGeolocation(playerIP) + ")";
            if (!ipHistory.contains(ipWithLocation)) {
                ipHistory.add(ipWithLocation);
            }
            playerInfo.put("Status", this.getPlayerBanStatus(player));
            playerInfo.put("Playeruuid", playerUUID.toString());
            playerInfo.put("Playerip", playerIP);
            playerInfo.put("Rank", this.getPlayerRank(playerName));
            playerInfo.put("ip_history", ipHistory);
            LocalDateTime lastLogin = LocalDateTime.now();
            this.lastLoginMap.put(playerName, lastLogin);
            this.playerDataMap.put(playerName, playerInfo);
            this.savePlayerData();
            this.saveLastLoginData();
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();
        Map<String, Object> playerInfo = (Map<String, Object>) this.playerDataMap.getOrDefault(playerName, new HashMap<>());
        Map<String, Object> stats = (Map<String, Object>) playerInfo.get("stats");
        if (stats == null) {
            stats = new HashMap<>();
            playerInfo.put("stats", stats);
        }
        double playerMoney = 0.0F;
        if (this.econ != null) {
            playerMoney = this.econ.getBalance(player);
        }
        stats.put("money", playerMoney);
        this.playerDataMap.put(playerName, playerInfo);
        this.savePlayerData();
    }

    private boolean isPlayerWhitelisted(String playerName) {
        Object whitelistedPlayersObj = this.whitelistMap.get("whitelisted_players");
        if (!(whitelistedPlayersObj instanceof List<?>)) {
            return false;
        } else {
            return ((List<?>) whitelistedPlayersObj).contains(playerName);
        }
    }

    private String getPlayerBanStatus(Player player) {
        return player.isBanned() ? "Banned" : "Unbanned";
    }

    private String getPlayerRank(String playerName) {
        try {
            User user = this.luckPermsApi.getUserManager().getUser(playerName);
            if (user != null) {
                return user.getPrimaryGroup();
            }
        } catch (Exception var3) {
        }
        return "default";
    }

    private void loadPlayerData() {
        if (this.playerDataFile.exists()) {
            try {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(this.playerDataFile);
                this.playerDataMap = new HashMap<>();
                for (String playerName : config.getKeys(false)) {
                    ConfigurationSection section = config.getConfigurationSection(playerName);
                    if (section != null) {
                        Map<String, Object> playerInfo = new HashMap<>();
                        for (String key : section.getKeys(false)) {
                            if (key.equals("stats")) {
                                ConfigurationSection statsSection = section.getConfigurationSection(key);
                                if (statsSection != null) {
                                    Map<String, Object> stats = new HashMap<>();
                                    for (String statKey : statsSection.getKeys(false)) {
                                        stats.put(statKey, statsSection.get(statKey));
                                    }
                                    playerInfo.put(key, stats);
                                }
                            } else {
                                playerInfo.put(key, section.get(key));
                            }
                        }
                        this.playerDataMap.put(playerName, playerInfo);
                        String notesStr = (String) playerInfo.get("notes");
                        if (notesStr != null && !notesStr.isEmpty()) {
                            this.playerNotesMap.put(playerName, new ArrayList<>(Arrays.asList(notesStr.split(", "))));
                        } else {
                            this.playerNotesMap.put(playerName, new ArrayList<>());
                        }
                        Object ipHistoryObj = playerInfo.get("ip_history");
                        if (ipHistoryObj instanceof List) {
                            playerInfo.put("ip_history", new ArrayList<>((List<String>) ipHistoryObj));
                        } else if (ipHistoryObj instanceof String) {
                            playerInfo.put("ip_history", new ArrayList<>(Arrays.asList(((String) ipHistoryObj).split(", "))));
                        } else {
                            playerInfo.put("ip_history", new ArrayList<>());
                        }
                    }
                }
            } catch (Exception e) {
                this.getLogger().log(Level.WARNING, "Failed to load playerdata.yml", e);
                this.playerDataMap = new HashMap<>();
            }
        } else {
            this.playerDataMap = new HashMap<>();
        }
    }

    private void savePlayerData() {
        try {
            YamlConfiguration config = new YamlConfiguration();
            for (Map.Entry<String, Map<String, Object>> entry : this.playerDataMap.entrySet()) {
                Map<String, Object> playerInfo = entry.getValue();
                Map<String, Object> stats = (Map<String, Object>) playerInfo.get("stats");
                if (stats == null) {
                    Map<String, Object> var8 = new HashMap<>();
                    playerInfo.put("stats", var8);
                }
                List<String> notes = this.playerNotesMap.getOrDefault(entry.getKey(), new ArrayList<>());
                playerInfo.put("notes", String.join(", ", notes));
                config.createSection(entry.getKey(), playerInfo);
            }
            config.save(this.playerDataFile);
        } catch (Exception e) {
            this.getLogger().log(Level.WARNING, "Failed to save playerdata.yml", e);
        }
    }

    private void loadWhitelistData() {
        if (this.whitelistFile.exists()) {
            try {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(this.whitelistFile);
                String whitelistStatus = config.getString("whitelist", "off");
                List<String> whitelistedPlayers = config.getStringList("whitelisted_players");
                if (whitelistedPlayers == null) {
                    whitelistedPlayers = new ArrayList<>();
                }
                this.whitelistMap.put("whitelist", whitelistStatus);
                this.whitelistMap.put("whitelisted_players", whitelistedPlayers);
            } catch (Exception e) {
                this.getLogger().log(Level.WARNING, "Failed to load whitelist.yml", e);
                this.whitelistMap = new HashMap<>();
            }
        } else {
            this.whitelistMap.put("whitelist", "off");
            this.whitelistMap.put("whitelisted_players", new ArrayList<>());
            this.saveWhitelistData();
        }
    }

    private void saveWhitelistData() {
        try {
            YamlConfiguration config = new YamlConfiguration();
            config.set("whitelist", this.whitelistMap.get("whitelist"));
            config.set("whitelisted_players", this.whitelistMap.get("whitelisted_players"));
            config.save(this.whitelistFile);
        } catch (Exception e) {
            this.getLogger().log(Level.WARNING, "Failed to save whitelist.yml", e);
        }
    }

    private void initializeWhitelistFile() throws IOException {
        this.whitelistMap.put("whitelist", "off");
        this.whitelistMap.put("whitelisted_players", Lists.newArrayList());
        this.saveWhitelistData();
    }

    private void synchronizeWithMinecraftWhitelist() {
        File whitelistJson = new File(Bukkit.getServer().getWorldContainer(), "whitelist.json");
        if (!whitelistJson.exists()) {
            this.getLogger().warning("Minecraft whitelist.json not found!");
        } else {
            try {
                Gson gson = new Gson();
                Type type = new TypeToken<List<Map<String, Object>>>() {}.getType();
                List<Map<String, Object>> whitelistEntries = gson.fromJson(new FileReader(whitelistJson), type);
                if (whitelistEntries == null) {
                    whitelistEntries = Lists.newArrayList();
                }
                List<String> playerNames = whitelistEntries.stream()
                        .filter(entry -> entry.containsKey("uuid") && entry.containsKey("name"))
                        .map(entry -> (String) entry.get("name"))
                        .collect(Collectors.toList());
                boolean isServerWhitelistEnabled = Bukkit.hasWhitelist();
                this.whitelistMap.put("whitelist", isServerWhitelistEnabled ? "on" : "off");
                this.whitelistMap.put("whitelisted_players", playerNames);
                this.saveWhitelistData();
            } catch (Exception e) {
                this.getLogger().log(Level.WARNING, "Failed to synchronize with Minecraft whitelist", e);
            }
        }
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("playerip")) {
            if (!sender.hasPermission("nationfight.playerdata.playerip")) {
                sender.sendMessage(this.getMessage("no_permission", Collections.emptyMap()));
                return true;
            } else if (args.length != 1) {
                sender.sendMessage(this.getMessage("usage_playerip", Collections.emptyMap()));
                return true;
            } else {
                String playerName = args[0];
                Map<String, Object> playerInfo = this.playerDataMap.get(playerName);
                if (playerInfo != null) {
                    String playerIP = (String) playerInfo.get("Playerip");
                    if (playerIP != null) {
                        sender.sendMessage(this.getMessage("player_ip_found", Map.of("player", playerName, "ip", playerIP)));
                    } else {
                        sender.sendMessage(this.getMessage("player_ip_not_found", Map.of("player", playerName)));
                    }
                } else {
                    sender.sendMessage(this.getMessage("player_not_found", Map.of("player", playerName)));
                }
                return true;
            }
        } else if (command.getName().equalsIgnoreCase("note")) {
            if (!sender.hasPermission("nationfight.playerdata.note")) {
                sender.sendMessage(this.getMessage("no_permission", Collections.emptyMap()));
                return true;
            } else if (args.length < 2) {
                sender.sendMessage(this.getMessage("usage_note", Collections.emptyMap()));
                return true;
            } else {
                String subCommand = args[0];
                if (subCommand.equalsIgnoreCase("add")) {
                    if (args.length < 3) {
                        sender.sendMessage(this.getMessage("usage_note_add", Collections.emptyMap()));
                        return true;
                    }
                    String targetPlayer = args[1];
                    String note = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                    ((List<String>) this.playerNotesMap.computeIfAbsent(targetPlayer, k -> new ArrayList<>())).add(note);
                    sender.sendMessage(this.getMessage("note_added", Map.of("player", targetPlayer)));
                    this.savePlayerData();
                } else if (subCommand.equalsIgnoreCase("view")) {
                    if (args.length != 2) {
                        sender.sendMessage(this.getMessage("usage_note_view", Collections.emptyMap()));
                        return true;
                    }
                    String targetPlayer = args[1];
                    List<String> notes = this.playerNotesMap.getOrDefault(targetPlayer, new ArrayList<>());
                    sender.sendMessage(this.getMessage("notes_for_player", Map.of("player", targetPlayer, "notes", String.join(", ", notes))));
                } else if (subCommand.equalsIgnoreCase("delete")) {
                    if (args.length != 2) {
                        sender.sendMessage(this.getMessage("usage_note_delete", Collections.emptyMap()));
                        return true;
                    }
                    String targetPlayer = args[1];
                    if (this.playerNotesMap.containsKey(targetPlayer)) {
                        ((List<String>) this.playerNotesMap.get(targetPlayer)).clear();
                        sender.sendMessage(this.getMessage("notes_deleted", Map.of("player", targetPlayer)));
                        this.savePlayerData();
                    } else {
                        sender.sendMessage(this.getMessage("no_notes_found", Map.of("player", targetPlayer)));
                    }
                } else {
                    sender.sendMessage(this.getMessage("invalid_subcommand", Collections.emptyMap()));
                }
                return true;
            }
        } else if (command.getName().equalsIgnoreCase("pdp")) {
            if (!sender.hasPermission("nationfight.playerdata.pdp")) {
                sender.sendMessage(this.getMessage("no_permission", Collections.emptyMap()));
                return true;
            } else if (args.length < 1) {
                sender.sendMessage(this.getMessage("usage_pdp", Collections.emptyMap()));
                return true;
            } else {
                String subCommand = args[0];
                if (subCommand.equalsIgnoreCase("iphistory")) {
                    if (args.length < 3) {
                        sender.sendMessage(this.getMessage("usage_pdp_iphistory", Collections.emptyMap()));
                        return true;
                    }
                    String action = args[1];
                    String playerName = args[2];
                    if (action.equalsIgnoreCase("show")) {
                        this.showIPHistory(sender, playerName);
                    } else if (action.equalsIgnoreCase("delete")) {
                        if (args.length < 4) {
                            sender.sendMessage(this.getMessage("usage_pdp_iphistory_delete", Collections.emptyMap()));
                            return true;
                        }
                        String ipToDelete = args[3];
                        this.deleteIPFromHistory(sender, playerName, ipToDelete);
                    } else {
                        sender.sendMessage(this.getMessage("invalid_subcommand", Collections.emptyMap()));
                    }
                } else if (subCommand.equalsIgnoreCase("discord")) {
                    sender.sendMessage("Join our Discord: https://discord.com/invite/cvk2yB5DP4");
                } else {
                    sender.sendMessage(this.getMessage("invalid_subcommand", Collections.emptyMap()));
                }
                return true;
            }
        } else {
            return false;
        }
    }

    private void showIPHistory(CommandSender sender, String playerName) {
        Map<String, Object> playerInfo = (Map<String, Object>) this.playerDataMap.getOrDefault(playerName, new HashMap<>());
        List<String> ipHistory = (List<String>) playerInfo.get("ip_history");
        if (ipHistory != null && !ipHistory.isEmpty()) {
            sender.sendMessage(this.getMessage("ip_history_show", Map.of("player", playerName, "ip_history", String.join("\n", ipHistory))));
        } else {
            sender.sendMessage(this.getMessage("no_ip_history", Map.of("player", playerName)));
        }
    }

    private void deleteIPFromHistory(CommandSender sender, String playerName, String ipToDelete) {
        Map<String, Object> playerInfo = (Map<String, Object>) this.playerDataMap.getOrDefault(playerName, new HashMap<>());
        List<String> ipHistory = (List<String>) playerInfo.get("ip_history");
        if (ipHistory != null && !ipHistory.isEmpty()) {
            if (ipToDelete.equalsIgnoreCase("all")) {
                ipHistory.clear();
                playerInfo.put("ip_history", ipHistory);
                this.playerDataMap.put(playerName, playerInfo);
                this.savePlayerData();
                sender.sendMessage(this.getMessage("ip_history_deleted_all", Map.of("player", playerName)));
            } else {
                boolean ipFoundAndDeleted = false;
                Iterator<String> iterator = ipHistory.iterator();
                while (iterator.hasNext()) {
                    String ipEntry = iterator.next();
                    if (ipEntry.contains(ipToDelete)) {
                        iterator.remove();
                        ipFoundAndDeleted = true;
                        break;
                    }
                }
                if (ipFoundAndDeleted) {
                    playerInfo.put("ip_history", ipHistory);
                    this.playerDataMap.put(playerName, playerInfo);
                    this.savePlayerData();
                    sender.sendMessage(this.getMessage("ip_history_deleted", Map.of("ip", ipToDelete, "player", playerName)));
                } else {
                    sender.sendMessage(this.getMessage("ip_not_found", Map.of("ip", ipToDelete, "player", playerName)));
                }
            }
        } else {
            sender.sendMessage(this.getMessage("no_ip_history", Map.of("player", playerName)));
        }
    }

    private void notifyIPChange(String playerName, String oldIP, String newIP) {
        String message = this.getMessage("ip_change", Map.of("player", playerName, "old_ip", oldIP, "new_ip", newIP));
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("nationfight.playerdata.ipchange")) {
                player.sendMessage(message);
            }
        }
    }

    private String fetchGeolocation(String ip) {
        return "Unknown";
    }

    private void loadMessages() {
        if (this.messagesFile.exists()) {
            try {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(this.messagesFile);
                this.messagesMap = new HashMap<>();
                for (String key : config.getKeys(false)) {
                    Object value = config.get(key);
                    if (value instanceof String) {
                        this.messagesMap.put(key, (String) value);
                    } else {
                        this.getLogger().warning("Invalid value for key '" + key + "' in messages.yml. Expected a String but found: " + value.getClass().getName());
                    }
                }
            } catch (Exception e) {
                this.getLogger().log(Level.WARNING, "Failed to load messages.yml", e);
            }
        } else {
            this.saveDefaultMessages();
        }
    }

    private void ensureResourceFileExists(File file, String resourceName) {
        if (!file.exists()) {
            try {
                if (!file.getParentFile().exists()) {
                    file.getParentFile().mkdirs();
                }
                InputStream inputStream = this.getResource(resourceName);
                if (inputStream != null) {
                    Files.copy(inputStream, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    this.getLogger().info("Copied resource file: " + file.getAbsolutePath());
                } else {
                    this.getLogger().severe("Resource file '" + resourceName + "' not found!");
                }
            } catch (IOException e) {
                this.getLogger().log(Level.SEVERE, "Failed to copy resource file " + resourceName, e);
            }
        } else {
            this.getLogger().info("File already exists: " + file.getAbsolutePath());
        }
    }

    private void saveDefaultMessages() {
        // Diese Methode wird nicht mehr benötigt, da die Datei als Ressource kopiert wird
    }

    private String getMessage(String key, Map<String, Object> placeholders) {
        String message = this.messagesMap.getOrDefault(key, "Message not found");
        for (Map.Entry<String, Object> entry : placeholders.entrySet()) {
            message = message.replace("%" + entry.getKey() + "%", entry.getValue().toString());
        }
        return message;
    }

    private void updatePlayerMoney() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            String playerName = player.getName();
            Map<String, Object> playerInfo = (Map<String, Object>) this.playerDataMap.getOrDefault(playerName, new HashMap<>());
            Map<String, Object> stats = (Map<String, Object>) playerInfo.get("stats");
            if (stats == null) {
                stats = new HashMap<>();
                playerInfo.put("stats", stats);
            }
            double playerMoney = 0.0F;
            if (this.econ != null) {
                playerMoney = this.econ.getBalance(player);
            }
            stats.put("money", playerMoney);
            this.playerDataMap.put(playerName, playerInfo);
            this.savePlayerData();
        }
    }

    private void removeOldPlayerData() {
        LocalDateTime sixDaysAgo = LocalDateTime.now().minus(6L, ChronoUnit.DAYS);
        for (Map.Entry<String, Map<String, Object>> entry : this.playerDataMap.entrySet()) {
            String playerName = entry.getKey();
            Map<String, Object> playerInfo = entry.getValue();
            String lastLoginStr = (String) playerInfo.get("last_login");
            if (lastLoginStr != null) {
                LocalDateTime lastLogin = LocalDateTime.parse(lastLoginStr, DATE_TIME_FORMATTER);
                if (lastLogin.isBefore(sixDaysAgo)) {
                    playerInfo.remove("Playerip");
                    playerInfo.remove("ip_history");
                    this.playerDataMap.put(playerName, playerInfo);
                    this.savePlayerData();
                }
            }
        }
    }

    private void ensureFileExists(File file) {
        if (!file.exists()) {
            try {
                if (!file.getParentFile().exists()) {
                    file.getParentFile().mkdirs();
                }
                file.createNewFile();
                this.getLogger().info("Created file: " + file.getAbsolutePath());
            } catch (IOException e) {
                this.getLogger().log(Level.SEVERE, "Failed to create " + file.getName(), e);
            }
        } else {
            this.getLogger().info("File already exists: " + file.getAbsolutePath());
        }
    }

    private void loadLastLoginData() {
        if (this.lastLoginFile.exists()) {
            try {
                Type type = new TypeToken<Map<String, String>>() {}.getType();
                Map<String, String> encryptedLastLoginMap = this.gson.fromJson(new FileReader(this.lastLoginFile), type);
                if (encryptedLastLoginMap == null) {
                    encryptedLastLoginMap = new HashMap<>();
                }
                this.lastLoginMap = new HashMap<>();
                for (Map.Entry<String, String> entry : encryptedLastLoginMap.entrySet()) {
                    String playerName = entry.getKey();
                    String encryptedLastLogin = entry.getValue();
                    String decryptedLastLogin = this.decrypt(encryptedLastLogin);
                    this.lastLoginMap.put(playerName, LocalDateTime.parse(decryptedLastLogin, DATE_TIME_FORMATTER));
                }
            } catch (Exception e) {
                this.getLogger().log(Level.WARNING, "Failed to load lastlogin.json", e);
                this.lastLoginMap = new HashMap<>();
            }
        } else {
            this.lastLoginMap = new HashMap<>();
        }
    }

    private void saveLastLoginData() {
        try {
            Map<String, String> encryptedLastLoginMap = new HashMap<>();
            for (Map.Entry<String, LocalDateTime> entry : this.lastLoginMap.entrySet()) {
                String playerName = entry.getKey();
                String lastLoginStr = entry.getValue().format(DATE_TIME_FORMATTER);
                String encryptedLastLogin = this.encrypt(lastLoginStr);
                encryptedLastLoginMap.put(playerName, encryptedLastLogin);
            }
            String json = this.gson.toJson(encryptedLastLoginMap);
            FileWriter writer = new FileWriter(this.lastLoginFile);
            writer.write(json);
            writer.close();
        } catch (IOException e) {
            this.getLogger().log(Level.WARNING, "Failed to save lastlogin.json", e);
        }
    }

    private String encrypt(String data) {
        byte[] bytes = data.getBytes();
        for (int i = 0; i < bytes.length; ++i) {
            bytes[i] ^= XOR_KEY;
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    private String decrypt(String encryptedData) {
        byte[] bytes = Base64.getDecoder().decode(encryptedData);
        for (int i = 0; i < bytes.length; ++i) {
            bytes[i] ^= XOR_KEY;
        }
        return new String(bytes);
    }

    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("note")) {
            if (args.length == 1) {
                completions.addAll(Arrays.asList("add", "view", "delete"));
            } else if (args.length == 2) {
                if (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("view") || args[0].equalsIgnoreCase("delete")) {
                    completions.addAll(this.playerDataMap.keySet());
                }
            } else if (args.length == 3) {
            }
        } else if (command.getName().equalsIgnoreCase("pdp")) {
            if (args.length == 1) {
                completions.addAll(Arrays.asList("iphistory", "discord"));
            } else if (args.length == 2) {
                if (args[0].equalsIgnoreCase("iphistory")) {
                    completions.addAll(Arrays.asList("show", "delete"));
                }
            } else if (args.length == 3) {
                if (args[0].equalsIgnoreCase("iphistory") && (args[1].equalsIgnoreCase("show") || args[1].equalsIgnoreCase("delete"))) {
                    completions.addAll(this.playerDataMap.keySet());
                }
            } else if (args.length == 4 && args[0].equalsIgnoreCase("iphistory") && args[1].equalsIgnoreCase("delete")) {
                String playerName = args[2];
                Map<String, Object> playerInfo = this.playerDataMap.getOrDefault(playerName, new HashMap<>());
                List<String> ipHistory = (List<String>) playerInfo.get("ip_history");
                if (ipHistory != null) {
                    completions.addAll(ipHistory);
                }
            }
        }
        return completions;
    }

    static {
        DATE_TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    }

    private static class LocalDateTimeSerializer implements JsonSerializer<LocalDateTime> {
        private LocalDateTimeSerializer() {
        }

        public JsonElement serialize(LocalDateTime src, Type typeOfSrc, JsonSerializationContext context) {
            return context.serialize(src.format(PlayerDataPlugin.DATE_TIME_FORMATTER));
        }
    }

    private static class LocalDateTimeDeserializer implements JsonDeserializer<LocalDateTime> {
        private LocalDateTimeDeserializer() {
        }

        public LocalDateTime deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            return LocalDateTime.parse(json.getAsString(), PlayerDataPlugin.DATE_TIME_FORMATTER);
        }
    }
}