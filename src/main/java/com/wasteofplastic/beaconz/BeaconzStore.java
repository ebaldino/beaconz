/*
 * Copyright (c) 2015 tastybento
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.wasteofplastic.beaconz;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import org.apache.commons.lang.math.NumberUtils;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.wasteofplastic.beaconz.listeners.BeaconListeners;

/**
 * This class provides inventory, health, xp and food level swapping between games and between worlds.
 * Players who enter the beaconz world go to the lobby and then onto the games. Their external inventory
 * is all stored under the lobby tag. When the player exits the beaconz world, they receive their lobby
 * items again.
 * As writing a plugin similar to MultiInv looked especially difficult and would require clever serialization
 * of every item in the game, I decided to create another flat world where player's items are stored in
 * chests. The first item in the chest is a piece of paper with lore on it that records whose chest it is
 * and the player's stats. This can be used as a fall back to find stuff if required and is used when
 * rebuilding the index.
 * The index is a double hashmap of player/gamename/location that is stored in a YML for quick access. If
 * it is lost, or deleted, it is recovered by a systematic scan of the chests.
 * Chests are currently stored at 0,0 going vertically until they get to the top of the sky. Then they move
 * in the positive X direction. TODO: write a better packing algorithm to make them go in a spiral or something.
 * If a game is deleted, then the chest is marked as empty and can be reused.
 * 
 * @author tastybento
 *
 */
public class BeaconzStore extends BeaconzPluginDependent {
    private World beaconzStoreWorld;
    private HashMap<UUID,HashMap<String,Location>> index = new HashMap<UUID, HashMap<String,Location>>();
    private List<Location> emptyChests = new ArrayList<Location>();
    private int lastX;
    private int lastY;
    private int lastZ;
    private YamlConfiguration ymlIndex;
    private File indexFile;
    private static final boolean DEBUG = false;

    public BeaconzStore(Beaconz beaconzPlugin) {
        super(beaconzPlugin);
        // Make the world if it doesn't exist already
        beaconzStoreWorld = WorldCreator.name(Settings.worldName + "_store").type(WorldType.FLAT).environment(World.Environment.NORMAL).createWorld();
        beaconzStoreWorld.setDifficulty(Difficulty.PEACEFUL);              
        beaconzStoreWorld.setSpawnFlags(false, false);
        beaconzStoreWorld.setPVP(false);
        beaconzStoreWorld.setAutoSave(true);
        indexFile = new File(beaconzPlugin.getDataFolder(),"inventories.yml");
        // See if there is an index file
        if (indexFile.exists()) {
            loadIndex();
        } else {
            rebuildIndex();
            saveIndex();
        }
    }

    /**
     * Saves the location of all the chests to a file
     */
    public void saveIndex() {
        ymlIndex = new YamlConfiguration();
        try {
            // Save the index
            HashMap<String, Location> locations = new HashMap<String, Location>();
            for (UUID uuid: index.keySet()) {
                locations = index.get(uuid);
                for (String gameName: locations.keySet()) {
                    ymlIndex.set("index." + uuid.toString() + "." + gameName, Beaconz.getStringLocation(locations.get(gameName)));
                }
            }
            // Save the empty chest locations
            List<String> tempList = new ArrayList<String>();
            for (Location loc : emptyChests) {
                tempList.add(Beaconz.getStringLocation(loc));
            }
            ymlIndex.set("emptyChests", tempList);
            // Save file
            ymlIndex.save(indexFile);
        } catch (Exception e) {
            // Something went wrong
            getLogger().severe("Could not save inventory index file!");
            e.printStackTrace();
        }
    }

    /**
     * Loads the location of all the chests from a file
     */
    public void loadIndex() {
        index.clear();
        emptyChests.clear();
        ymlIndex = new YamlConfiguration();
        if (!indexFile.exists()) {
            getLogger().info("No inventory index file found, creating...");
            rebuildIndex();
            saveIndex();
            return;
        }
        try {
            ymlIndex.load(indexFile);
            // Parse
            ConfigurationSection players = ymlIndex.getConfigurationSection("index");
            if (players != null) {
                HashMap<String, Location> locations = new HashMap<String, Location>();
                for (String uuid : players.getValues(false).keySet()) {
                    UUID playerUUID = UUID.fromString(uuid);
                    locations.clear();
                    ConfigurationSection chestLocations = players.getConfigurationSection(uuid);
                    for (String gameName : chestLocations.getValues(false).keySet()) {
                        locations.put(gameName, Beaconz.getLocationString(chestLocations.getString(gameName)));
                    }
                    index.put(playerUUID, locations);
                }
            }
            // Get empty chests
            List<String> tempList = ymlIndex.getStringList("emptyChests");
            for (String loc : tempList) {
                emptyChests.add(Beaconz.getLocationString(loc)); 
            }
        } catch (Exception e) {
            // Something went wrong
            getLogger().severe("Could not load inventory index file, rebuilding");
            rebuildIndex();
            saveIndex();
        } 
    }

    /**
     * Rebuilds the index by going through chests in the world
     */
    public void rebuildIndex() {
        if (DEBUG) {
            getLogger().info("DEBUG: Rebuilding index");
        }
        index.clear();
        emptyChests.clear();
        // Load the store
        Block chestBlock = beaconzStoreWorld.getBlockAt(0, 4, 0);
        lastX = 0;
        lastY = 4;
        lastZ = 0;
        while (chestBlock.getType().equals(Material.CHEST)) {
            Chest chest = (Chest)chestBlock.getState();
            InventoryHolder ih = chest.getInventory().getHolder();
            if (ih instanceof DoubleChest){
                DoubleChest dc = (DoubleChest) ih;
                ItemStack indexItem = dc.getInventory().getItem(0);
                if (indexItem != null) {
                    ItemMeta meta = indexItem.getItemMeta();
                    List<String> info = meta.getLore();
                    // UUID, gameName, name
                    if (!info.isEmpty()) {
                        UUID uuid = UUID.fromString(info.get(0));
                        HashMap<String, Location> temp = new HashMap<String, Location>();
                        if (index.containsKey(uuid)) {
                            temp = index.get(uuid);              
                        }
                        temp.put(info.get(1), chestBlock.getLocation());
                        index.put(uuid, temp);
                    }
                } else {
                    // Make a note of these for use later
                    emptyChests.add(chestBlock.getLocation());
                }
            }
            // Get next block
            lastY++;
            if (lastY == beaconzStoreWorld.getMaxHeight()) {
                lastY = 4;
                lastX++;
            }
            chestBlock = beaconzStoreWorld.getBlockAt(lastX, lastY, lastZ);
        }
        // After this, lastX, Y, Z should point to the next free spot
        if (DEBUG) {
            getLogger().info("DEBUG: last = " + lastX + "," + lastY + "," + lastZ);
        }
    }

    /**
     * Gets items from a storage chest in the storage world. Changes the inventory of player immediately.
     * @param player
     * @param gameName
     * @return last location of the player in the game or null if there is none
     */
    public Location getInventory(Player player, String gameName) {
        Game game = getGameMgr().getGame(gameName);
        if (!gameName.equalsIgnoreCase("lobby") && game == null) {
            getLogger().severe("Asked to get inventory for " + gameName + " but a game by that name does not exist!");   
            return null;
        }
        Location lastLoc = null;
        if (DEBUG) {
            getLogger().info("DEBUG: getInventory for " + player.getName() + " going to " + gameName);
            // Clear the inventory
            getLogger().info("DEBUG: clearing player's inventory - I hope it's saved somewhere!");
        }
        player.getInventory().clear();
        if (index.containsKey(player.getUniqueId())) {
            if (DEBUG) {
                getLogger().info("DEBUG: player has chest(s)");
            }
            if (index.get(player.getUniqueId()).containsKey(gameName)) {
                if (DEBUG) {
                    getLogger().info("DEBUG: chest is for this game " + gameName);
                }
                // They have a chest for this game, so give them the contents
                Block chestBlock = beaconzStoreWorld.getBlockAt(index.get(player.getUniqueId()).get(gameName));
                Chest chest = (Chest)chestBlock.getState();
                InventoryHolder ih = chest.getInventory().getHolder();
                if (ih instanceof DoubleChest) {
                    Inventory chestInv = ((DoubleChest) ih).getInventory();
                    // Get the experience
                    ItemStack paper = chestInv.getItem(0);
                    if (paper != null) {
                        if (DEBUG) {
                            getLogger().info("DEBUG: index item found");
                        }
                        ItemMeta itemMeta = paper.getItemMeta();
                        List<String> lore = itemMeta.getLore();
                        if (lore.size() == 7) {
                            // Exp
                            String[] split = lore.get(3).split(":");
                            if (split.length == 2 && NumberUtils.isNumber(split[1])) {
                                int xp = Integer.valueOf(split[1]);
                                if (DEBUG) {
                                    getLogger().info("DEBUG: Setting player's xp to " + xp);
                                }
                                BeaconListeners.setTotalExperience(player, xp);
                            }
                            // Health
                            split = lore.get(4).split(":");
                            if (split.length == 2 && NumberUtils.isNumber(split[1])) {
                                double health = Double.valueOf(split[1]);
                                if (DEBUG)
                                    getLogger().info("DEBUG: Setting player's health to " + health);
                                player.setHealth(health);
                            }
                            // Food
                            split = lore.get(5).split(":");
                            if (split.length == 2 && NumberUtils.isNumber(split[1])) {
                                int food = Integer.valueOf(split[1]);
                                if (DEBUG)
                                    getLogger().info("DEBUG: Setting player's food to " + food);
                                player.setFoodLevel(food);
                            }
                            // Location
                            lastLoc = Beaconz.getLocationString(lore.get(6));
                            if (!gameName.equalsIgnoreCase("lobby") && (getGameMgr().getGame(lastLoc) == null || !getGameMgr().getGame(lastLoc).equals(game))) {
                                getLogger().warning("Last location for " + player.getName() + " for game " + gameName + " was not in the game and instead at " + lore.get(6));
                                lastLoc = null;
                            }
                            if (DEBUG)
                                getLogger().info("DEBUG: Player's location " + lastLoc);
                        }
                    }
                    for (int i = 1; i < chestInv.getSize(); i++) {
                        // Give to player
                        player.getInventory().setItem(i-1, chestInv.getItem(i));
                        // Remove from chest
                        chestInv.setItem(i, null);
                    }                    
                }
            } else {
                if (DEBUG)
                    getLogger().info("DEBUG: chest is not for game " + gameName);
            }
        } else {
            if (DEBUG)
                getLogger().info("DEBUG: player does not have any chests");
        }
        return lastLoc;
    }

    /**
     * Puts the player's inventory into the right chest
     * @param player
     * @param gameName - the game name for the inventory being store
     * @param from - the last position of the player in this game
     */
    public void storeInventory(Player player, String gameName, Location from) {
        if (DEBUG)
            getLogger().info("DEBUG: storeInventory for " + player.getName() + " leaving " + gameName + " from " + from);
        Block chestBlock = beaconzStoreWorld.getBlockAt(lastX, lastY, lastZ);
        // Find out if they have a chest already
        if (!index.containsKey(player.getUniqueId())) {
            if (DEBUG)
                getLogger().info("DEBUG: player has no chest");
            // Make a chest            
            // Check if there are any free chests
            if (!emptyChests.isEmpty()) {
                if (DEBUG)
                    getLogger().info("DEBUG: there is an empty chest available");
                chestBlock = emptyChests.get(0).getBlock();
                emptyChests.remove(0);
            } else {
                // There is no chest, so make one 
                if (DEBUG)
                    getLogger().info("DEBUG: Making chest");
                chestBlock.setType(Material.CHEST);
                chestBlock.getRelative(0, 0, 1).setType(Material.CHEST);
                lastY++;
                if (lastY == beaconzStoreWorld.getMaxHeight()) {
                    lastY = 4;
                    lastX++;
                }
                if (DEBUG)
                    getLogger().info("DEBUG: last = " + lastX + "," + lastY + "," + lastZ);
            }
            // Store in index
            HashMap<String,Location> placeHolder = new HashMap<String,Location>();
            placeHolder.put(gameName,chestBlock.getLocation());
            index.put(player.getUniqueId(), placeHolder);
        } else {
            if (DEBUG)
                getLogger().info("DEBUG: player has chest");
            if (index.get(player.getUniqueId()).containsKey(gameName)) {
                if (DEBUG)
                    getLogger().info("DEBUG: player has chest for " + gameName);
                // There is a chest already! So use it
                chestBlock = index.get(player.getUniqueId()).get(gameName).getBlock();
                if (!chestBlock.getType().equals(Material.CHEST)) {
                    getLogger().severe("Chest at " + index.get(player.getUniqueId()).get(gameName) + " is not there anymore!");
                    index.get(player.getUniqueId()).remove(gameName);
                    rebuildIndex();
                    storeInventory(player, gameName, from);
                    return;
                }
            } else {
                if (DEBUG)
                    getLogger().info("DEBUG: Player does not have chest for " + gameName);
                // Check if there are any free chests
                if (!emptyChests.isEmpty()) {
                    if (DEBUG)
                        getLogger().info("DEBUG: there is an empty chest available");
                    chestBlock = emptyChests.get(0).getBlock();
                    emptyChests.remove(0);
                } else {
                    // There is no chest, so make one 
                    if (DEBUG)
                        getLogger().info("DEBUG: Making chest");
                    chestBlock.setType(Material.CHEST);
                    chestBlock.getRelative(0, 0, 1).setType(Material.CHEST);
                    lastY++;
                    if (lastY == beaconzStoreWorld.getMaxHeight()) {
                        lastY = 0;
                        lastX++;
                    }
                    if (DEBUG)
                        getLogger().info("DEBUG: last = " + lastX + "," + lastY + "," + lastZ);
                }
                HashMap<String,Location> placeHolder = index.get(player.getUniqueId());
                placeHolder.put(gameName,chestBlock.getLocation());
                index.put(player.getUniqueId(), placeHolder);
            }
        }
        // Actually store the items in the chest
        Chest chest = (Chest)chestBlock.getState();
        InventoryHolder ih = chest.getInventory().getHolder();
        if (ih instanceof DoubleChest){
            Inventory chestInv = ((DoubleChest) ih).getInventory();
            // Clear any current inventory
            chestInv.clear();
            // Create the index item
            if (DEBUG)
                getLogger().info("DEBUG: creating index item");
            ItemStack indexItem = new ItemStack(Material.PAPER);
            List<String> lore = new ArrayList<String>();
            lore.add(player.getUniqueId().toString());
            lore.add(gameName);
            lore.add(player.getName());
            lore.add("xp:" + BeaconListeners.getTotalExperience(player));
            lore.add("health:" + player.getHealth());
            lore.add("food:" + player.getFoodLevel());
            // Check the player's location is in this game
            if (gameName.equalsIgnoreCase("lobby") || (getGameMgr().getGame(from) != null && getGameMgr().getGame(from).getName().equals(gameName))) {
                lore.add(Beaconz.getStringLocation(from));
            } else {
                getLogger().warning("Asked to store inventory for " + player.getName() + " for game name " + gameName + " but player's last location was not in the game. It was at " + from);
            }
            ItemMeta meta = indexItem.getItemMeta();
            meta.setLore(lore);
            indexItem.setItemMeta(meta);
            int itemIndex = 0;
            chestInv.setItem(itemIndex++, indexItem);
            if (DEBUG)
                getLogger().info("DEBUG: copying player's inventory to chest");
            // Copy the player's items to the chest
            for (ItemStack item: player.getInventory()) {
                chestInv.setItem(itemIndex++, item);
            }
            // Clear the player's inventory
            player.getInventory().clear();
            BeaconListeners.setTotalExperience(player, 0);

            // Done!
            if (DEBUG)
                getLogger().info("DEBUG: Done!");
        }
    }

    /**
     * Marks all the chests related to a particular game as empty. Chests are not actually emptied until they are reused.
     * @param gameName
     */
    public void removeGame(String gameName) {
        for (UUID uuid: index.keySet()) {
            Iterator<String> it = index.get(uuid).keySet().iterator();
            while (it.hasNext()) {
                String game = it.next();
                if (game.equals(gameName)) {
                    // Add to empty chest location
                    emptyChests.add(index.get(uuid).get(gameName));
                    it.remove();                    
                }
            }
        }
    }

}