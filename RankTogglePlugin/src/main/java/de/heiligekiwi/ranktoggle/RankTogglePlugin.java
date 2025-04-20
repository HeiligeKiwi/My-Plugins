package de.heiligekiwi.ranktoggle;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class RankTogglePlugin extends JavaPlugin implements Listener {
    private LuckPerms luckPerms;
    private File configFile;
    private FileConfiguration config;
    private Map<String, FileConfiguration> languageConfigs;
    private Map<UUID, GUISession> activeGUIs = new HashMap<>();

    @Override
    public void onEnable() {
        // Load LuckPerms API
        luckPerms = LuckPermsProvider.get();

        // Create and load config file
        configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            configFile.getParentFile().mkdirs();
            saveResource("config.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(configFile);

        // Load language files
        languageConfigs = new HashMap<>();
        loadLanguageFile("de.yml");
        loadLanguageFile("en.yml");

        // Register command and tab completer
        getCommand("ranktoggle").setExecutor(new RankToggleCommand(this));
        getCommand("ranktoggle").setTabCompleter(new RankToggleTabCompleter(this));

        // Register GUI event listeners
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    private void loadLanguageFile(String fileName) {
        File langFile = new File(getDataFolder(), "lang/" + fileName);
        if (!langFile.exists()) {
            langFile.getParentFile().mkdirs();
            saveResource("lang/" + fileName, false);
        }
        FileConfiguration langConfig = YamlConfiguration.loadConfiguration(langFile);
        languageConfigs.put(fileName.replace(".yml", ""), langConfig);
    }

    public LuckPerms getLuckPerms() {
        return luckPerms;
    }

    @Override
    public FileConfiguration getConfig() {
        return config;
    }

    @Override
    public void saveConfig() {
        try {
            config.save(configFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public FileConfiguration getLanguageConfig(String language) {
        return languageConfigs.getOrDefault(language, languageConfigs.get("en")); // Default to English if language not found
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;

        Player player = (Player) event.getWhoClicked();
        GUISession session = activeGUIs.get(player.getUniqueId());

        if (session != null && event.getInventory().equals(session.inventory)) {
            event.setCancelled(true);

            if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) return;

            if (session.type == GUIType.MAIN_MENU) {
                handleMainMenuClick(player, event.getSlot(), session);
            } else if (session.type == GUIType.TIME_SELECTION) {
                handleTimeSelectionClick(player, event.getSlot(), session);
            } else if (session.type == GUIType.PLAYER_SELECTION) {
                handlePlayerSelectionClick(player, event.getSlot(), session);
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        activeGUIs.remove(player.getUniqueId());
    }

    private void handleMainMenuClick(Player player, int slot, GUISession session) {
        String language = getPlayerLanguage(player);
        FileConfiguration langConfig = getLanguageConfig(language);

        if (slot < config.getConfigurationSection("roles").getKeys(false).size()) {
            String roleName = session.roles.get(slot);
            session.selectedRole = roleName;

            // Open action menu
            Inventory actionMenu = Bukkit.createInventory(null, 9, ChatColor.DARK_PURPLE + langConfig.getString("gui.action_title") + " " + roleName);

            // Add action buttons
            ItemStack addButton = createGuiItem(Material.GREEN_WOOL, langConfig.getString("gui.add_role"));
            ItemStack removeButton = createGuiItem(Material.RED_WOOL, langConfig.getString("gui.remove_role"));
            ItemStack addTempButton = createGuiItem(Material.LIME_CONCRETE, langConfig.getString("gui.add_temp_role"));
            ItemStack removeTempButton = createGuiItem(Material.RED_CONCRETE, langConfig.getString("gui.remove_temp_role"));
            ItemStack listButton = createGuiItem(Material.BOOK, langConfig.getString("gui.list_players"));
            ItemStack backButton = createGuiItem(Material.ARROW, langConfig.getString("gui.back"));

            actionMenu.setItem(0, addButton);
            actionMenu.setItem(1, removeButton);
            actionMenu.setItem(2, addTempButton);
            actionMenu.setItem(3, removeTempButton);
            actionMenu.setItem(4, listButton);
            actionMenu.setItem(8, backButton);

            session.inventory = actionMenu;
            session.type = GUIType.ACTION_MENU;
            player.openInventory(actionMenu);
        }
    }

    private void handleActionMenuClick(Player player, int slot, GUISession session) {
        String language = getPlayerLanguage(player);
        FileConfiguration langConfig = getLanguageConfig(language);

        switch (slot) {
            case 0: // Add role
                applyRoleChange(player, session.selectedRole, false, false, 0);
                player.closeInventory();
                break;
            case 1: // Remove role
                applyRoleChange(player, session.selectedRole, true, false, 0);
                player.closeInventory();
                break;
            case 2: // Add temp role
                session.action = "add_temp";
                openTimeSelectionMenu(player, session);
                break;
            case 3: // Remove temp role
                session.action = "remove_temp";
                openTimeSelectionMenu(player, session);
                break;
            case 4: // List players
                player.closeInventory();
                listPlayersWithRole(player, session.selectedRole);
                break;
            case 8: // Back
                openMainMenu(player);
                break;
        }
    }

    private void handleTimeSelectionClick(Player player, int slot, GUISession session) {
        String language = getPlayerLanguage(player);
        FileConfiguration langConfig = getLanguageConfig(language);

        if (slot >= 0 && slot < 6) { // Time options
            long duration = 0;
            switch (slot) {
                case 0: duration = TimeUnit.MINUTES.toMillis(30); break;
                case 1: duration = TimeUnit.HOURS.toMillis(1); break;
                case 2: duration = TimeUnit.HOURS.toMillis(6); break;
                case 3: duration = TimeUnit.DAYS.toMillis(1); break;
                case 4: duration = TimeUnit.DAYS.toMillis(7); break;
                case 5: duration = TimeUnit.DAYS.toMillis(30); break;
            }

            session.duration = duration;

            // If player has admin permissions, let them select a player
            if (player.hasPermission("ranktoggle.admin")) {
                openPlayerSelectionMenu(player, session);
            } else {
                // Apply to self
                boolean isRemove = "remove_temp".equals(session.action);
                applyRoleChange(player, session.selectedRole, isRemove, true, duration);
                player.closeInventory();
            }
        } else if (slot == 8) { // Back
            // Return to action menu
            openActionMenu(player, session.selectedRole);
        }
    }

    private void handlePlayerSelectionClick(Player player, int slot, GUISession session) {
        String language = getPlayerLanguage(player);
        FileConfiguration langConfig = getLanguageConfig(language);

        if (slot < session.players.size()) {
            Player targetPlayer = Bukkit.getPlayer(session.players.get(slot));
            if (targetPlayer != null) {
                boolean isRemove = "remove_temp".equals(session.action);
                applyRoleChange(targetPlayer, session.selectedRole, isRemove, true, session.duration, player);
                player.closeInventory();
            }
        } else if (slot == 8) { // Back
            openTimeSelectionMenu(player, session);
        }
    }

    private void openMainMenu(Player player) {
        String language = getPlayerLanguage(player);
        FileConfiguration langConfig = getLanguageConfig(language);

        Set<String> roleKeys = config.getConfigurationSection("roles").getKeys(false);
        int size = ((roleKeys.size() + 8) / 9) * 9; // Round up to nearest multiple of 9

        Inventory menu = Bukkit.createInventory(null, size, ChatColor.DARK_PURPLE + langConfig.getString("gui.main_title"));

        List<String> roles = new ArrayList<>(roleKeys);
        for (int i = 0; i < roles.size(); i++) {
            String roleName = roles.get(i);
            String permission = config.getString("roles." + roleName + ".permission");

            Material material = Material.valueOf(config.getString("roles." + roleName + ".material", "PAPER").toUpperCase());
            String displayName = config.getString("roles." + roleName + ".display_name", roleName);

            // Check if player has the role
            boolean hasRole = false;
            if (player != null) {
                User user = luckPerms.getUserManager().getUser(player.getUniqueId());
                if (user != null) {
                    hasRole = hasGroup(user, permission);
                }
            }

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + langConfig.getString("gui.permission") + ": " + permission);
            lore.add(hasRole ?
                    ChatColor.GREEN + langConfig.getString("gui.role_active") :
                    ChatColor.RED + langConfig.getString("gui.role_inactive"));

            ItemStack item = createGuiItem(material, ChatColor.YELLOW + displayName, lore);
            menu.setItem(i, item);
        }

        GUISession session = new GUISession();
        session.inventory = menu;
        session.type = GUIType.MAIN_MENU;
        session.roles = roles;

        activeGUIs.put(player.getUniqueId(), session);
        player.openInventory(menu);
    }

    private void openActionMenu(Player player, String roleName) {
        String language = getPlayerLanguage(player);
        FileConfiguration langConfig = getLanguageConfig(language);

        Inventory actionMenu = Bukkit.createInventory(null, 9, ChatColor.DARK_PURPLE + langConfig.getString("gui.action_title") + " " + roleName);

        // Add action buttons
        ItemStack addButton = createGuiItem(Material.GREEN_WOOL, langConfig.getString("gui.add_role"));
        ItemStack removeButton = createGuiItem(Material.RED_WOOL, langConfig.getString("gui.remove_role"));
        ItemStack addTempButton = createGuiItem(Material.LIME_CONCRETE, langConfig.getString("gui.add_temp_role"));
        ItemStack removeTempButton = createGuiItem(Material.RED_CONCRETE, langConfig.getString("gui.remove_temp_role"));
        ItemStack listButton = createGuiItem(Material.BOOK, langConfig.getString("gui.list_players"));
        ItemStack backButton = createGuiItem(Material.ARROW, langConfig.getString("gui.back"));

        actionMenu.setItem(0, addButton);
        actionMenu.setItem(1, removeButton);
        actionMenu.setItem(2, addTempButton);
        actionMenu.setItem(3, removeTempButton);
        actionMenu.setItem(4, listButton);
        actionMenu.setItem(8, backButton);

        GUISession session = activeGUIs.get(player.getUniqueId());
        if (session == null) {
            session = new GUISession();
            activeGUIs.put(player.getUniqueId(), session);
        }

        session.inventory = actionMenu;
        session.type = GUIType.ACTION_MENU;
        session.selectedRole = roleName;

        player.openInventory(actionMenu);
    }

    private void openTimeSelectionMenu(Player player, GUISession session) {
        String language = getPlayerLanguage(player);
        FileConfiguration langConfig = getLanguageConfig(language);

        Inventory timeMenu = Bukkit.createInventory(null, 9, ChatColor.DARK_PURPLE + langConfig.getString("gui.time_title"));

        // Add time options
        ItemStack option30m = createGuiItem(Material.CLOCK, "30 " + langConfig.getString("gui.minutes"));
        ItemStack option1h = createGuiItem(Material.CLOCK, "1 " + langConfig.getString("gui.hour"));
        ItemStack option6h = createGuiItem(Material.CLOCK, "6 " + langConfig.getString("gui.hours"));
        ItemStack option1d = createGuiItem(Material.CLOCK, "1 " + langConfig.getString("gui.day"));
        ItemStack option7d = createGuiItem(Material.CLOCK, "7 " + langConfig.getString("gui.days"));
        ItemStack option30d = createGuiItem(Material.CLOCK, "30 " + langConfig.getString("gui.days"));
        ItemStack backButton = createGuiItem(Material.ARROW, langConfig.getString("gui.back"));

        timeMenu.setItem(0, option30m);
        timeMenu.setItem(1, option1h);
        timeMenu.setItem(2, option6h);
        timeMenu.setItem(3, option1d);
        timeMenu.setItem(4, option7d);
        timeMenu.setItem(5, option30d);
        timeMenu.setItem(8, backButton);

        session.inventory = timeMenu;
        session.type = GUIType.TIME_SELECTION;

        player.openInventory(timeMenu);
    }

    private void openPlayerSelectionMenu(Player player, GUISession session) {
        String language = getPlayerLanguage(player);
        FileConfiguration langConfig = getLanguageConfig(language);

        List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        int size = Math.min(54, ((onlinePlayers.size() + 8) / 9) * 9); // Max size 54

        Inventory playerMenu = Bukkit.createInventory(null, size, ChatColor.DARK_PURPLE + langConfig.getString("gui.player_title"));

        List<String> playerNames = new ArrayList<>();
        for (int i = 0; i < onlinePlayers.size() && i < size - 1; i++) {
            Player targetPlayer = onlinePlayers.get(i);
            playerNames.add(targetPlayer.getName());

            ItemStack playerHead = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta meta = playerHead.getItemMeta();
            meta.setDisplayName(ChatColor.YELLOW + targetPlayer.getName());
            playerHead.setItemMeta(meta);

            playerMenu.setItem(i, playerHead);
        }

        ItemStack backButton = createGuiItem(Material.ARROW, langConfig.getString("gui.back"));
        playerMenu.setItem(size - 1, backButton);

        session.inventory = playerMenu;
        session.type = GUIType.PLAYER_SELECTION;
        session.players = playerNames;

        player.openInventory(playerMenu);
    }

    private ItemStack createGuiItem(Material material, String name) {
        return createGuiItem(material, name, null);
    }

    private ItemStack createGuiItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore != null) {
            meta.setLore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    private String getPlayerLanguage(Player player) {
        // Here you would implement your language detection logic
        // For now, we'll return the default language
        return "en";
    }

    private void applyRoleChange(Player targetPlayer, String role, boolean remove, boolean temporary, long duration) {
        applyRoleChange(targetPlayer, role, remove, temporary, duration, null);
    }

    private void applyRoleChange(Player targetPlayer, String role, boolean remove, boolean temporary, long duration, Player sender) {
        String language = sender != null ? getPlayerLanguage(sender) : getPlayerLanguage(targetPlayer);
        FileConfiguration langConfig = getLanguageConfig(language);
        CommandSender messageSender = sender != null ? sender : targetPlayer;

        String permission = config.getString("roles." + role + ".permission");
        if (permission == null) {
            messageSender.sendMessage(langConfig.getString("messages.role_not_found"));
            return;
        }

        User user = luckPerms.getUserManager().getUser(targetPlayer.getUniqueId());
        if (user == null) return;

        // Handle the role change based on the operation type
        if (remove) {
            if (temporary) {
                // Remove temporary role
                user.data().toCollection().stream()
                        .filter(node -> node.getType() == NodeType.INHERITANCE)
                        .filter(node -> ((InheritanceNode) node).getGroupName().equals(permission))
                        .filter(Node::hasExpiry)
                        .forEach(node -> user.data().remove(node));

                messageSender.sendMessage(langConfig.getString("messages.removed_temp_role")
                        .replace("{role}", role)
                        .replace("{player}", targetPlayer.getName()));
            } else {
                // Remove permanent role
                user.data().remove(InheritanceNode.builder(permission).build());
                messageSender.sendMessage(langConfig.getString("messages.removed_role")
                        .replace("{role}", role)
                        .replace("{player}", targetPlayer.getName()));
            }
        } else {
            if (temporary) {
                // Add temporary role
                Duration tempDuration = Duration.ofMillis(duration);
                user.data().add(InheritanceNode.builder(permission)
                        .expiry(tempDuration.getSeconds())
                        .build());

                String timeDescription = formatDuration(duration);
                messageSender.sendMessage(langConfig.getString("messages.added_temp_role")
                        .replace("{role}", role)
                        .replace("{player}", targetPlayer.getName())
                        .replace("{time}", timeDescription));
            } else {
                // Add permanent role
                user.data().add(InheritanceNode.builder(permission).build());
                messageSender.sendMessage(langConfig.getString("messages.added_role")
                        .replace("{role}", role)
                        .replace("{player}", targetPlayer.getName()));
            }
        }

        luckPerms.getUserManager().saveUser(user);
    }

    private String formatDuration(long millis) {
        if (millis < TimeUnit.HOURS.toMillis(1)) {
            return TimeUnit.MILLISECONDS.toMinutes(millis) + " minutes";
        } else if (millis < TimeUnit.DAYS.toMillis(1)) {
            return TimeUnit.MILLISECONDS.toHours(millis) + " hours";
        } else {
            return TimeUnit.MILLISECONDS.toDays(millis) + " days";
        }
    }

    private void listPlayersWithRole(Player player, String role) {
        String language = getPlayerLanguage(player);
        FileConfiguration langConfig = getLanguageConfig(language);

        String permission = config.getString("roles." + role + ".permission");
        if (permission == null) {
            player.sendMessage(langConfig.getString("messages.role_not_found"));
            return;
        }

        player.sendMessage(langConfig.getString("messages.listing_roles").replace("{role}", role));

        luckPerms.getUserManager().getUniqueUsers().thenAccept(uniqueUsers -> {
            List<User> usersWithRole = new ArrayList<>();
            for (UUID uuid : uniqueUsers) {
                User user = luckPerms.getUserManager().loadUser(uuid).join();
                if (user != null && hasGroup(user, permission)) {
                    usersWithRole.add(user);
                }
            }

            if (usersWithRole.isEmpty()) {
                player.sendMessage(langConfig.getString("messages.no_players_with_role").replace("{role}", role));
            } else {
                for (User user : usersWithRole) {
                    // Check if the role is temporary and get expiry time if it is
                    Optional<Node> tempNode = user.getNodes().stream()
                            .filter(node -> node.getType() == NodeType.INHERITANCE)
                            .filter(node -> ((InheritanceNode) node).getGroupName().equals(permission))
                            .filter(Node::hasExpiry)
                            .findFirst();

                    if (tempNode.isPresent()) {
                        long expiryTime = tempNode.get().getExpiry().toEpochMilli();
                        long currentTime = System.currentTimeMillis();
                        long remainingTime = expiryTime - currentTime;

                        if (remainingTime > 0) {
                            String timeDescription = formatDuration(remainingTime);
                            player.sendMessage(ChatColor.YELLOW + user.getUsername() + ChatColor.GRAY +
                                    " (" + langConfig.getString("messages.expires_in") + " " + timeDescription + ")");
                        } else {
                            player.sendMessage(user.getUsername());
                        }
                    } else {
                        player.sendMessage(user.getUsername());
                    }
                }
            }
        });
    }

    private boolean hasGroup(User user, String groupName) {
        return user.getInheritedGroups(user.getQueryOptions()).stream()
                .map(net.luckperms.api.model.group.Group::getName)
                .anyMatch(groupName::equals);
    }

    private class RankToggleCommand implements CommandExecutor {
        private final RankTogglePlugin plugin;

        public RankToggleCommand(RankTogglePlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            String language = "en"; // Default language
            if (sender instanceof Player) {
                Player player = (Player) sender;
                language = getPlayerLanguage(player);
            }

            FileConfiguration langConfig = plugin.getLanguageConfig(language);

            if (args.length == 0) {
                if (sender instanceof Player) {
                    // Open GUI
                    openMainMenu((Player) sender);
                } else {
                    sender.sendMessage(langConfig.getString("messages.usage"));
                }
                return true;
            }

            // GUI command
            if (args[0].equalsIgnoreCase("gui")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(langConfig.getString("messages.must_be_player"));
                    return true;
                }
                openMainMenu((Player) sender);
                return true;
            }

            String role = args[0];
            String permission = plugin.getConfig().getString("roles." + role + ".permission");
            if (permission == null) {
                sender.sendMessage(langConfig.getString("messages.role_not_found"));
                return true;
            }

            if (args.length == 1) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(langConfig.getString("messages.must_be_player"));
                    return true;
                }
                Player player = (Player) sender;
                User user = luckPerms.getUserManager().getUser(player.getUniqueId());
                if (user != null) {
                    if (hasGroup(user, permission)) {
                        user.data().remove(InheritanceNode.builder(permission).build());
                        sender.sendMessage(langConfig.getString("messages.removed_role").replace("{role}", role));
                    } else {
                        user.data().add(InheritanceNode.builder(permission).build());
                        sender.sendMessage(langConfig.getString("messages.added_role").replace("{role}", role));
                    }
                    luckPerms.getUserManager().saveUser(user);
                }
                return true;
            }

            String subCommand = args[1];
            switch (subCommand.toLowerCase()) {
                case "add":
                    if (args.length < 3) {
                        sender.sendMessage(langConfig.getString("messages.usage"));
                        return true;
                    }
                    String playerName = args[2];
                    Player targetPlayer = Bukkit.getPlayerExact(playerName);
                    if (targetPlayer == null) {
                        sender.sendMessage(langConfig.getString("messages.player_not_found"));
                        return true;
                    }

                    // Check for temporary role assignment
                    if (args.length >= 5 && args[3].equalsIgnoreCase("temp")) {
                        try {
                            long duration = parseDuration(args[4]);
                            applyRoleChange(targetPlayer, role, false, true, duration, sender instanceof Player ? (Player) sender : null);
                        } catch (IllegalArgumentException e) {
                            sender.sendMessage(langConfig.getString("messages.invalid_duration"));
                        }
                    } else {
                        applyRoleChange(targetPlayer, role, false, false, 0, sender instanceof Player ? (Player) sender : null);
                    }
                    break;

                case "remove":
                    if (args.length < 3) {
                        sender.sendMessage(langConfig.getString("messages.usage"));
                        return true;
                    }
                    playerName = args[2];
                    targetPlayer = Bukkit.getPlayerExact(playerName);
                    if (targetPlayer == null) {
                        sender.sendMessage(langConfig.getString("messages.player_not_found"));
                        return true;
                    }

                    // Check if we're removing temporary permissions specifically
                    boolean removeTemp = args.length >= 4 && args[3].equalsIgnoreCase("temp");
                    applyRoleChange(targetPlayer, role, true, removeTemp, 0, sender instanceof Player ? (Player) sender : null);
                    break;

                case "list":
                    if (!(sender instanceof Player)) {
                        // Implement console version of the list command
                        String finalPermission = permission;
                        luckPerms.getUserManager().getUniqueUsers().thenAccept(uniqueUsers -> {
                            List<User> usersWithRole = new ArrayList<>();
                            for (UUID uuid : uniqueUsers) {
                                User user = luckPerms.getUserManager().loadUser(uuid).join();
                                if (user != null && hasGroup(user, finalPermission)) {
                                    usersWithRole.add(user);
                                }
                            }

                            if (usersWithRole.isEmpty()) {
                                sender.sendMessage(langConfig.getString("messages.no_players_with_role").replace("{role}", role));
                            } else {
                                sender.sendMessage(langConfig.getString("messages.listing_roles").replace("{role}", role));
                                for (User user : usersWithRole) {
                                    sender.sendMessage("- " + user.getUsername());
                                }
                            }
                        });
                    } else {
                        listPlayersWithRole((Player) sender, role);
                    }
                    break;

                default:
                    sender.sendMessage(langConfig.getString("messages.unknown_subcommand"));
                    break;
            }
            return true;
        }

        private long parseDuration(String durationStr) {
            try {
                String numPart = durationStr.replaceAll("[^0-9]", "");
                String unitPart = durationStr.replaceAll("[0-9]", "").toLowerCase();

                int value = Integer.parseInt(numPart);

                switch (unitPart) {
                    case "s":
                    case "sec":
                    case "second":
                    case "seconds":
                        return TimeUnit.SECONDS.toMillis(value);
                    case "m":
                    case "min":
                    case "minute":
                    case "minutes":
                        return TimeUnit.MINUTES.toMillis(value);
                    case "h":
                    case "hour":
                    case "hours":
                        return TimeUnit.HOURS.toMillis(value);
                    case "d":
                    case "day":
                    case "days":
                        return TimeUnit.DAYS.toMillis(value);
                    case "w":
                    case "week":
                    case "weeks":
                        return TimeUnit.DAYS.toMillis(value * 7);
                    case "mo":
                    case "month":
                    case "months":
                        return TimeUnit.DAYS.toMillis(value * 30);
                    default:
                        throw new IllegalArgumentException("Unknown time unit: " + unitPart);
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid duration format: " + durationStr);
            }
        }
    }

    private class RankToggleTabCompleter implements TabCompleter {
        private final RankTogglePlugin plugin;

        public RankToggleTabCompleter(RankTogglePlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            List<String> completions = new ArrayList<>();

            if (args.length == 1) {
                // Add "gui" command
                completions.add("gui");
                // Add roles
                plugin.getConfig().getConfigurationSection("roles").getKeys(false).forEach(completions::add);
            } else if (args.length == 2) {
                completions.add("add");
                completions.add("remove");
                completions.add("list");
            } else if (args.length == 3 && ("add".equalsIgnoreCase(args[1]) || "remove".equalsIgnoreCase(args[1]))) {
                Bukkit.getOnlinePlayers().forEach(player -> completions.add(player.getName()));
            } else if (args.length == 4 && "add".equalsIgnoreCase(args[1])) {
                completions.add("temp");
            } else if (args.length == 4 && "remove".equalsIgnoreCase(args[1])) {
                completions.add("temp");
            } else if (args.length == 5 && "add".equalsIgnoreCase(args[1]) && "temp".equalsIgnoreCase(args[3])) {
                // Add common time durations
                completions.add("30m");
                completions.add("1h");
                completions.add("6h");
                completions.add("12h");
                completions.add("1d");
                completions.add("7d");
                completions.add("30d");
            }

            return completions;
        }
    }

    // Enums and helper classes
    private enum GUIType {
        MAIN_MENU,
        ACTION_MENU,
        TIME_SELECTION,
        PLAYER_SELECTION
    }

    private class GUISession {
        public Inventory inventory;
        public GUIType type;
        public List<String> roles = new ArrayList<>();
        public List<String> players = new ArrayList<>();
        public String selectedRole;
        public String action;
        public long duration;
    }
}