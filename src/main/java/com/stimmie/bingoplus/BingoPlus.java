package com.stimmie.bingoplus;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class BingoPlus extends JavaPlugin implements Listener, CommandExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Component PREFIX = MM.deserialize("<gradient:gold:yellow><b>[BingoPlus]</b></gradient> ");
    private static final String INVENTORY_TITLE = "§6§lBingoPlus Gamble Wheel";

    // Track active gamble sessions
    private final Map<UUID, GambleSession> activeSessions = new HashMap<>();

    // Gamble configuration (45% win rate -> expected value 0.9 -> house always wins)
    private static final double WIN_CHANCE = 0.45;

    @Override
    public void onEnable() {
        // Register command and listener
        Objects.requireNonNull(getCommand("bingoplus")).setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("BingoPlus plugin enabled! Let the gambling begin.");
    }

    @Override
    public void onDisable() {
        // Clean up active sessions
        for (UUID playerId : new ArrayList<>(activeSessions.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                // Instantly finish or cancel the session to prevent item loss
                cancelSessionAndReturnItem(player);
            }
        }
        activeSessions.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MM.deserialize("<red>Only players can run this command.</red>"));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("hand")) {
            gambleHand(player);
            return true;
        }

        openGambleGUI(player);
        return true;
    }

    private void gambleHand(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() == Material.AIR) {
            player.sendMessage(PREFIX.append(MM.deserialize("<red>You must be holding an item to gamble!</red>")));
            return;
        }

        // Take 1 item from hand
        ItemStack gambleItem = item.clone();
        gambleItem.setAmount(1);
        item.setAmount(item.getAmount() - 1);

        player.sendMessage(PREFIX.append(MM.deserialize("<gray>Flipping coin for <yellow>" + gambleItem.getType().name() + "</yellow>...</gray>")));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);

        new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                ticks++;
                if (ticks < 5) {
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f + (ticks * 0.2f));
                    player.spawnParticle(Particle.CRIT, player.getLocation().add(0, 1, 0), 5, 0.2, 0.2, 0.2, 0.1);
                    return;
                }

                // Final roll
                if (Math.random() < WIN_CHANCE) {
                    // Win! Give 2 items back
                    ItemStack reward = gambleItem.clone();
                    reward.setAmount(2);
                    HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(reward);
                    for (ItemStack left : leftover.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), left);
                    }

                    player.sendMessage(PREFIX.append(MM.deserialize("<green><b>WIN!</b> You got 2x <yellow>" + gambleItem.getType().name() + "</yellow> back!</green>")));
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                    player.spawnParticle(Particle.HAPPY_VILLAGER, player.getLocation().add(0, 1, 0), 20, 0.5, 0.5, 0.5, 0.1);
                } else {
                    // Lose!
                    player.sendMessage(PREFIX.append(MM.deserialize("<red><b>LOSS!</b> Your <yellow>" + gambleItem.getType().name() + "</yellow> was lost.</red>")));
                    player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.5f);
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 0.8f);
                    player.spawnParticle(Particle.SMOKE, player.getLocation().add(0, 1, 0), 20, 0.3, 0.3, 0.3, 0.05);
                }
                cancel();
            }
        }.runTaskTimer(this, 0L, 3L);
    }

    private void openGambleGUI(Player player) {
        if (activeSessions.containsKey(player.getUniqueId())) {
            player.sendMessage(PREFIX.append(MM.deserialize("<red>You already have an active gambling session!</red>")));
            return;
        }

        Inventory gui = Bukkit.createInventory(player, 27, Component.text(INVENTORY_TITLE));

        // Create background panes
        ItemStack borderPane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta borderMeta = borderPane.getItemMeta();
        if (borderMeta != null) {
            borderMeta.displayName(Component.empty());
            borderPane.setItemMeta(borderMeta);
        }

        // Fill background
        for (int i = 0; i < gui.getSize(); i++) {
            if (i != 13 && i != 22) { // 13 is Input slot, 22 is Spin Button
                gui.setItem(i, borderPane);
            }
        }

        // Create Spin Button
        ItemStack spinButton = new ItemStack(Material.GOLD_BLOCK);
        ItemMeta spinMeta = spinButton.getItemMeta();
        if (spinMeta != null) {
            spinMeta.displayName(Component.text("§a§l⚡ SPIN WHEEL ⚡"));
            List<Component> lore = new ArrayList<>();
            lore.add(MM.deserialize("<gray>Place 1 item in the slot above.</gray>"));
            lore.add(MM.deserialize("<gray>Expected win chance: <yellow>45%</yellow></gray>"));
            spinMeta.lore(lore);
            spinButton.setItemMeta(spinMeta);
        }
        gui.setItem(22, spinButton);

        player.openInventory(gui);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(INVENTORY_TITLE)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        Inventory gui = event.getInventory();
        int slot = event.getRawSlot();

        GambleSession session = activeSessions.get(player.getUniqueId());

        // If currently spinning, lock everything
        if (session != null && session.isSpinning()) {
            event.setCancelled(true);
            return;
        }

        // If they click outside the inventory, let them
        if (slot >= gui.getSize() || slot < 0) {
            return;
        }

        // Prevent taking the spin button or border panes
        if (slot != 13) {
            event.setCancelled(true);

            // Handle Spin Button Click
            if (slot == 22) {
                triggerSpin(player, gui);
            }
            return;
        }

        // Allow slot 13 modification, but ensure they can only place 1 item
        // Wait, to make it simple, when they click/place, we can handle it or let standard click happen.
        // Let's ensure they can place items. But we will validate quantity when they spin.
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTitle().equals(INVENTORY_TITLE)) {
            Player player = (Player) event.getWhoClicked();
            GambleSession session = activeSessions.get(player.getUniqueId());

            if (session != null && session.isSpinning()) {
                event.setCancelled(true);
                return;
            }

            // Only allow dragging into slot 13
            for (int slot : event.getRawSlots()) {
                if (slot < event.getInventory().getSize() && slot != 13) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!event.getView().getTitle().equals(INVENTORY_TITLE)) {
            return;
        }

        Player player = (Player) event.getPlayer();
        GambleSession session = activeSessions.remove(player.getUniqueId());

        if (session != null) {
            if (session.isSpinning()) {
                // If they closed while spinning, resolve immediately!
                ItemStack gambleItem = session.getGambleItem();
                if (gambleItem != null && gambleItem.getType() != Material.AIR) {
                    player.sendMessage(PREFIX.append(MM.deserialize("<yellow>Inventory closed. Spin resolved instantly!</yellow>")));
                    if (Math.random() < WIN_CHANCE) {
                        ItemStack reward = gambleItem.clone();
                        reward.setAmount(2);
                        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(reward);
                        for (ItemStack left : leftover.values()) {
                            player.getWorld().dropItemNaturally(player.getLocation(), left);
                        }
                        player.sendMessage(PREFIX.append(MM.deserialize("<green><b>WIN!</b> You got 2x <yellow>" + gambleItem.getType().name() + "</yellow> back!</green>")));
                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
                    } else {
                        player.sendMessage(PREFIX.append(MM.deserialize("<red><b>LOSS!</b> Your <yellow>" + gambleItem.getType().name() + "</yellow> was lost.</red>")));
                        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.5f);
                    }
                }
                session.cancelTask();
            } else {
                // If they just closed without spinning, return any item in slot 13
                ItemStack inputItem = event.getInventory().getItem(13);
                if (inputItem != null && inputItem.getType() != Material.AIR) {
                    HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(inputItem);
                    for (ItemStack left : leftover.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), left);
                    }
                    event.getInventory().setItem(13, null);
                }
            }
        } else {
            // Just in case they had no active session object but left an item in the slot
            ItemStack inputItem = event.getInventory().getItem(13);
            if (inputItem != null && inputItem.getType() != Material.AIR) {
                HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(inputItem);
                for (ItemStack left : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), left);
                }
                event.getInventory().setItem(13, null);
            }
        }
    }

    private void triggerSpin(Player player, Inventory gui) {
        ItemStack inputItem = gui.getItem(13);
        if (inputItem == null || inputItem.getType() == Material.AIR) {
            player.sendMessage(PREFIX.append(MM.deserialize("<red>Please place an item in the center slot first!</red>")));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        if (inputItem.getAmount() > 1) {
            player.sendMessage(PREFIX.append(MM.deserialize("<red>You can only gamble 1 item at a time! Split the stack.</red>")));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        ItemStack gambleItem = inputItem.clone();

        // Create session
        GambleSession session = new GambleSession(gambleItem);
        activeSessions.put(player.getUniqueId(), session);
        session.setSpinning(true);

        // Run the wheel animation
        BukkitRunnable runTask = new BukkitRunnable() {
            int ticks = 0;
            final int[] borderSlots = {3, 4, 5, 12, 14, 21, 23};
            final Material[] cycleMaterials = {
                    Material.RED_STAINED_GLASS_PANE,
                    Material.ORANGE_STAINED_GLASS_PANE,
                    Material.YELLOW_STAINED_GLASS_PANE,
                    Material.LIME_STAINED_GLASS_PANE,
                    Material.GREEN_STAINED_GLASS_PANE,
                    Material.CYAN_STAINED_GLASS_PANE,
                    Material.BLUE_STAINED_GLASS_PANE,
                    Material.PURPLE_STAINED_GLASS_PANE
            };

            @Override
            public void run() {
                ticks++;

                if (ticks < 12) {
                    // Update border colors dynamically
                    for (int i = 0; i < borderSlots.length; i++) {
                        int index = (ticks + i) % cycleMaterials.length;
                        ItemStack colorPane = new ItemStack(cycleMaterials[index]);
                        ItemMeta meta = colorPane.getItemMeta();
                        if (meta != null) {
                            meta.displayName(MM.deserialize("<obfuscated>GAMBLE</obfuscated>"));
                            colorPane.setItemMeta(meta);
                        }
                        gui.setItem(borderSlots[i], colorPane);
                    }

                    // Play rolling sound
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1.0f, 0.5f + (ticks * 0.1f));
                    player.spawnParticle(Particle.PORTAL, player.getLocation().add(0, 1, 0), 3, 0.2, 0.2, 0.2, 0.1);
                    return;
                }

                // Restore borders to gray
                ItemStack grayPane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
                ItemMeta grayMeta = grayPane.getItemMeta();
                if (grayMeta != null) {
                    grayMeta.displayName(Component.empty());
                    grayPane.setItemMeta(grayMeta);
                }
                for (int slot : borderSlots) {
                    gui.setItem(slot, grayPane);
                }

                // Resolve gamble
                boolean win = Math.random() < WIN_CHANCE;

                if (win) {
                    // Give double
                    ItemStack reward = gambleItem.clone();
                    reward.setAmount(2);
                    gui.setItem(13, reward);

                    player.sendMessage(PREFIX.append(MM.deserialize("<green><b>WIN!</b> You got 2x <yellow>" + gambleItem.getType().name() + "</yellow>!</green>")));
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
                    player.spawnParticle(Particle.HAPPY_VILLAGER, player.getLocation().add(0, 1, 0), 30, 0.5, 0.5, 0.5, 0.1);
                } else {
                    // Remove item
                    gui.setItem(13, null);

                    player.sendMessage(PREFIX.append(MM.deserialize("<red><b>LOSS!</b> Lost your <yellow>" + gambleItem.getType().name() + "</yellow>.</red>")));
                    player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.2f);
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 0.8f);
                    player.spawnParticle(Particle.SMOKE, player.getLocation().add(0, 1, 0), 30, 0.3, 0.3, 0.3, 0.05);
                }

                // Clear session spinning state
                activeSessions.remove(player.getUniqueId());
                cancel();
            }
        };

        session.setRunnable(runTask);
        runTask.runTaskTimer(this, 0L, 2L);
    }

    private void cancelSessionAndReturnItem(Player player) {
        GambleSession session = activeSessions.remove(player.getUniqueId());
        if (session != null) {
            session.cancelTask();
            ItemStack gambleItem = session.getGambleItem();
            if (gambleItem != null && gambleItem.getType() != Material.AIR) {
                HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(gambleItem);
                for (ItemStack left : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), left);
                }
            }
        }
    }

    // Gamble Session tracking class
    private static class GambleSession {
        private final ItemStack gambleItem;
        private boolean spinning;
        private BukkitRunnable runnable;

        public GambleSession(ItemStack gambleItem) {
            this.gambleItem = gambleItem;
            this.spinning = false;
        }

        public ItemStack getGambleItem() {
            return gambleItem;
        }

        public boolean isSpinning() {
            return spinning;
        }

        public void setSpinning(boolean spinning) {
            this.spinning = spinning;
        }

        public void setRunnable(BukkitRunnable runnable) {
            this.runnable = runnable;
        }

        public void cancelTask() {
            if (runnable != null) {
                try {
                    runnable.cancel();
                } catch (IllegalStateException ignored) {}
            }
        }
    }
}
