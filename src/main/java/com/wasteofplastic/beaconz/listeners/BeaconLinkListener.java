/*
 * Copyright (c) 2015 - 2016 tastybento
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

package com.wasteofplastic.beaconz.listeners;

import java.awt.geom.Line2D;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.scoreboard.Team;

import com.wasteofplastic.beaconz.BeaconObj;
import com.wasteofplastic.beaconz.Beaconz;
import com.wasteofplastic.beaconz.BeaconzPluginDependent;
import com.wasteofplastic.beaconz.DefenseBlock;
import com.wasteofplastic.beaconz.Lang;
import com.wasteofplastic.beaconz.LinkResult;
import com.wasteofplastic.beaconz.Settings;
import com.wasteofplastic.beaconz.map.BeaconMap;
import com.wasteofplastic.beaconz.map.TerritoryMapRenderer;

public class BeaconLinkListener extends BeaconzPluginDependent implements Listener {

    private final static boolean DEBUG = false;

    public BeaconLinkListener(Beaconz plugin) {
        super(plugin);
    }

    /**
     * Handles the event of hitting a beacon with paper or a map
     * @param event
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled=true)
    public void onPaperMapUse(final PlayerInteractEvent event) {
        if (DEBUG)
            getLogger().info("DEBUG: paper map " + event.getEventName());

        if (!event.hasItem()) {
            return;
        }
        if (!event.getItem().getType().equals(Material.PAPER) && !event.getItem().getType().equals(Material.MAP)) {
            return;
        }
        if (!event.getAction().equals(Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        World world = event.getClickedBlock().getWorld();
        if (!world.equals(getBeaconzWorld())) {
            return;
        }
        Player player = event.getPlayer();
        // Ignore player in lobby
        if (getGameMgr().isPlayerInLobby(player)) {
            return;
        }
        // Check that there is paper or map in the MAIN hand
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (!mainHand.equals(event.getItem())) {
            return;
        }
        // Get the player's team
        Team team = getGameMgr().getPlayerTeam(player);
        if (team == null) {
            if (player.isOp()) {
                return;
            } else {
                event.setCancelled(true);
                return;
            }
        }
        // Check if the block is a beacon or the surrounding pyramid
        Block b = event.getClickedBlock();
        final BeaconObj beacon = getRegister().getBeacon(b);
        if (beacon == null) {
            if (DEBUG)
                getLogger().info("DEBUG: not a beacon");
            return;
        }
        // Check the team
        if (beacon.getOwnership() == null || !beacon.getOwnership().equals(team)) {
            player.sendMessage(ChatColor.RED + Lang.beaconYouMustCapturedBeacon);
            event.setCancelled(true);
            return;
        }
        if (event.getItem().getType().equals(Material.PAPER)) {
            // Give map to player
            // Remove one paper
            event.getItem().setAmount(event.getItem().getAmount() - 1);
            giveBeaconMap(player, beacon);
            // Stop the beacon inventory opening
            event.setCancelled(true);
            return;
        } else {
            // Map!
            BeaconObj mappedBeacon = getRegister().getBeaconMap(event.getItem().getDurability());
            if (mappedBeacon == null) {
                // This is not a beacon map
                return;
            }
            // Check the team
            if (mappedBeacon.getOwnership() == null || !mappedBeacon.getOwnership().equals(team)) {
                player.sendMessage(ChatColor.RED + Lang.beaconOriginNotOwned.replace("[team]",team.getDisplayName()));
                return;
            }
            event.setCancelled(true);
            if (Settings.expDistance > 0) {
                // Check if the beaconz can be linked.
                int linkDistance = checkBeaconDistance(beacon, mappedBeacon);
                if (linkDistance > Settings.linkLimit) {
                    player.sendMessage(ChatColor.RED + Lang.errorTooFar.replace("[max]", String.valueOf(Settings.linkLimit)));
                    return;
                }
                // Check if the player has sufficient experience to link the beacons
                int expRequired = getReqExp(beacon, mappedBeacon); 
                if (expRequired > 0) {
                    if (!testForExp(player, (int)(expRequired))) {
                        player.sendMessage(ChatColor.RED + Lang.errorNotEnoughExperience);
                        player.sendMessage(ChatColor.RED + Lang.beaconYouNeedThisMuchExp.replace("[number]", String.format(Locale.US, "%,d",(int)(expRequired))));
                        player.sendMessage(ChatColor.RED + Lang.beaconYouHaveThisMuchExp.replace("[number]", String.format(Locale.US, "%,d",getTotalExperience(player))));
                        return;
                    }
                }
                if (linkBeacons(player, team, beacon, mappedBeacon)) {
                    player.sendMessage(ChatColor.GREEN + Lang.beaconTheMapDisintegrates);
                    player.getInventory().setItemInMainHand(null);
                    removeExp(player, expRequired);
                    // Save for safety
                    getRegister().saveRegister();
                    // Update score
                    getGameMgr().getGame(team).getScorecard().refreshScores(team);
                    getGameMgr().getGame(team).getScorecard().refreshSBdisplay(team);
                }
            } else {
                // No exp required
                if (linkBeacons(player, team, beacon, mappedBeacon)) {
                    player.sendMessage(ChatColor.GREEN + Lang.beaconTheMapDisintegrates);
                    player.getInventory().setItemInMainHand(null);
                    // Save for safety
                    getRegister().saveRegister();
                    // Update score
                    getGameMgr().getGame(team).getScorecard().refreshScores(team);
                    getGameMgr().getGame(team).getScorecard().refreshSBdisplay(team);
                }
            }

        }
    }

    /**
     * @param beacon
     * @param mappedBeacon
     * @return distance between the two beaconz less any link blocks (value could be negative)
     */
    private int checkBeaconDistance(BeaconObj beacon, BeaconObj mappedBeacon) {
        int distance = (int)beacon.getPoint().distance(mappedBeacon.getPoint());
        for (DefenseBlock block :beacon.getDefenseBlocks().values()) {
            //getLogger().info("DEBUG: Blocks on beacon = " + block);
            if (Settings.linkBlocks.containsKey(block.getBlock().getType())) {
                distance -= Settings.linkBlocks.get(block.getBlock().getType());
            }
        }
        for (DefenseBlock block :mappedBeacon.getDefenseBlocks().values()) {
            //getLogger().info("DEBUG: Blocks on beacon = " + block);
            if (Settings.linkBlocks.containsKey(block.getBlock().getType())) {
                distance -= Settings.linkBlocks.get(block.getBlock().getType());
            }
        }
        return distance;
    }
    
    /**
     * Returns how much experience is required to make a link
     * @param beacon
     * @param mappedBeacon
     * @return exp required
     */
    public int getReqExp(BeaconObj beacon, BeaconObj mappedBeacon) {
        double distance = beacon.getPoint().distance(mappedBeacon.getPoint());
        return (int) (distance/Settings.expDistance);
    }

    /**
     * Puts a beacon map in the player's main hand
     * @param player
     * @param beacon
     */
    @SuppressWarnings("deprecation")
    private void giveBeaconMap(Player player, BeaconObj beacon) {
        // Make a map!
        player.sendMessage(ChatColor.GREEN + Lang.beaconYouHaveAMap);
        MapView map = Bukkit.createMap(getBeaconzWorld());
        //map.setWorld(getBeaconzWorld());
        map.setCenterX(beacon.getX());
        map.setCenterZ(beacon.getZ());
        map.getRenderers().clear();
        map.addRenderer(new TerritoryMapRenderer(getBeaconzPlugin()));
        map.addRenderer(new BeaconMap(getBeaconzPlugin()));
        ItemStack newMap = new ItemStack(Material.MAP);
        newMap.setDurability(map.getId());
        ItemMeta meta = newMap.getItemMeta();
        meta.setDisplayName("Beacon map for " + beacon.getName());
        newMap.setItemMeta(meta);
        // Each map is unique and the durability defines the map ID, register it
        getRegister().addBeaconMap(map.getId(), beacon);
        //getLogger().info("DEBUG: beacon id = " + beacon.getId());
        // Put map into hand
        ItemStack inHand = player.getInventory().getItemInMainHand();
        player.getInventory().setItemInMainHand(newMap);
        player.getInventory().addItem(inHand);
    }

    /**
     * Make sure all player held maps have triangle overlays. (todo: make sure all maps on item frames do as well)
     * There seem to be some bugs around this. It doesn't always take on the first try.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled=true)
    public void onMapHold(final PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        ItemStack itemInHand = player.getInventory().getItem(event.getNewSlot());
        if (itemInHand == null) return;
        if (!Material.MAP.equals(itemInHand.getType())) {
            return;
        }
        if (!player.getWorld().equals(getBeaconzWorld())) {
            return;
        }
        @SuppressWarnings("deprecation")
        MapView map = Bukkit.getMap(itemInHand.getDurability());
        for (MapRenderer renderer : map.getRenderers()) {
            if (renderer instanceof TerritoryMapRenderer) {
                return;
            }
        }
        map.addRenderer(new TerritoryMapRenderer(getBeaconzPlugin()));
    }

    /**
     * Tries to link two beacons. Failure reasons could be:
     * 1. trying to link a beacon to itself
     * 2. beacon having Settings.maxLinks links already
     * 3. link already exists
     * 4.link crosses opposition team's links
     *
     * @param player
     * @param team
     * @param beacon
     * @param otherBeacon
     * @return true if link is made successfully
     */
    private boolean linkBeacons(Player player, Team team, BeaconObj beacon,
            BeaconObj otherBeacon) {
        if (beacon.equals(otherBeacon)) {
            player.sendMessage(ChatColor.RED + Lang.beaconYouCannotLinkToSelf);
            return false;
        }
        if (beacon.getNumberOfLinks() == Settings.maxLinks) {
            player.sendMessage(ChatColor.RED + Lang.beaconMaxLinks.replace("[number]", String.valueOf(Settings.maxLinks)));
            return false;
        }
        // Check if this link already exists
        if (beacon.getLinks().contains(otherBeacon)) {
            player.sendMessage(ChatColor.RED + Lang.beaconLinkAlreadyExists);
            return false;
        }
        // Proposed link
        Line2D proposedLink = new Line2D.Double(beacon.getPoint(), otherBeacon.getPoint());
        // Check if the link crosses opposition team's links
        if (DEBUG)
            getLogger().info("DEBUG: Check if the link crosses opposition team's links");
        for (Line2D line : getRegister().getEnemyLinks(team)) {
            if (DEBUG)
                getLogger().info("DEBUG: checking line " + line.getP1() + " to " + line.getP2());
            if (line.intersectsLine(proposedLink)) {
                player.sendMessage(ChatColor.RED + Lang.beaconLinkCannotCrossEnemy);
                return false;
            }
        }

        // Link the two beacons!
        LinkResult result = getRegister().addBeaconLink(beacon, otherBeacon);
        if (result.isSuccess()) {
            player.sendMessage(ChatColor.GREEN + Lang.beaconLinkCreated);
            player.sendMessage(ChatColor.GREEN + Lang.beaconNowHasLinks.replace("[number]", String.valueOf(beacon.getNumberOfLinks())));
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_FIREWORK_LARGE_BLAST, 1F, 1F);
            // Tell the team
            getMessages().tellTeam(player, ChatColor.GREEN + Lang.beaconNameCreatedALink.replace("[name]", player.getDisplayName()));
        } else {
            player.sendMessage(ChatColor.RED + Lang.beaconLinkCouldNotBeCreated);
            return false;
        }
        if (result.getFieldsMade() > 0) {
            if (result.getFieldsMade() == 1) {
                player.sendMessage(ChatColor.GOLD + Lang.beaconTriangleCreated + " " + Lang.scoreNewScore + " = " + String.format(Locale.US, "%,d",getGameMgr().getSC(player).getScore(team, "area")));
                getMessages().tellTeam(player, ChatColor.GREEN + Lang.beaconNameCreateATriangle.replace("[name]", player.getDisplayName()) + " " + Lang.scoreNewScore + " = " + String.format(Locale.US, "%,d", getGameMgr().getSC(player).getScore(team, "area")));
                // Taunt other teams
                getMessages().tellOtherTeams(team, ChatColor.RED + Lang.beaconNameCreateATriangle.replace("[name]", team.getDisplayName()));
            } else {
                String message = (Lang.beaconNameCreateTriangles.replace("[name]", player.getDisplayName())).replace("[number]", String.valueOf(result.getFieldsMade()));
                String newScore = Lang.scoreNewScore + " " + String.format(Locale.US, "%,d", getGameMgr().getSC(player).getScore(team, "area"));
                player.sendMessage(ChatColor.GOLD + message + " " + newScore);
                getMessages().tellTeam(player, ChatColor.GREEN + message + " " + newScore);
                // Taunt other teams
                getMessages().tellOtherTeams(team, ChatColor.RED + message);
            }
        }
        if (result.getFieldsFailedToMake() > 0) {
            if (result.getFieldsFailedToMake() == 1) {
                player.sendMessage(ChatColor.RED + Lang.triangleCouldNotMakeTriangle);
            } else {
                player.sendMessage(ChatColor.RED + Lang.triangleCouldNotMakeTriangles.replace("[number]", String.valueOf(result.getFieldsFailedToMake())));
            }
        }
        // Give rewards
        List<ItemStack> rewards = giveItems(player, Settings.linkRewards);
        if (!rewards.isEmpty()) {
            player.sendMessage(ChatColor.GREEN + Lang.beaconYouReceivedAReward);
        }
        // Run commands
        runCommands(player,Settings.linkCommands);
        return true;
    }


    /**
     * Tests if a player has the required experience to perform the action. If so, the experience
     * is deducted. This function updates the client's UI exp bar.
     * @param player
     * @param xpRequired
     * @return true if sufficient experience points otherwise false
     */
    public static boolean testForExp(Player player , int xpRequired){
        return getTotalExperience(player) >= xpRequired ? true : false;
    }

    /**
     * Removes the experience from the player
     * @param player
     * @param xpRequired
     * @return
     */
    public static void removeExp(Player player , int xpRequired){
        int xp = getTotalExperience(player);
        if (xp >= xpRequired) {
            setTotalExperience(player, xp - xpRequired);
        }
    }

    // These next methods are taken from Essentials code

    //This method is used to update both the recorded total experience and displayed total experience.
    //We reset both types to prevent issues.
    public static void setTotalExperience(final Player player, final int exp)
    {
        if (exp < 0)
        {
            throw new IllegalArgumentException("Experience is negative!");
        }
        player.setExp(0);
        player.setLevel(0);
        player.setTotalExperience(0);

        //This following code is technically redundant now, as bukkit now calulcates levels more or less correctly
        //At larger numbers however... player.getExp(3000), only seems to give 2999, putting the below calculations off.
        int amount = exp;
        while (amount > 0)
        {
            final int expToLevel = getExpAtLevel(player);
            amount -= expToLevel;
            if (amount >= 0)
            {
                // give until next level
                player.giveExp(expToLevel);
            }
            else
            {
                // give the rest
                amount += expToLevel;
                player.giveExp(amount);
                amount = 0;
            }
        }
    }

    private static int getExpAtLevel(final Player player)
    {
        return getExpAtLevel(player.getLevel());
    }

    //new Exp Math from 1.8
    private  static  int getExpAtLevel(final int level)
    {
        if (level <= 15)
        {
            return (2*level) + 7;
        }
        if ((level >= 16) && (level <=30))
        {
            return (5 * level) -38;
        }
        return (9*level)-158;

    }

    //This method is required because the bukkit player.getTotalExperience() method, shows exp that has been 'spent'.
    //Without this people would be able to use exp and then still sell it.
    public static int getTotalExperience(final Player player)
    {
        int exp = Math.round(getExpAtLevel(player) * player.getExp());
        int currentLevel = player.getLevel();

        while (currentLevel > 0)
        {
            currentLevel--;
            exp += getExpAtLevel(currentLevel);
        }
        if (exp < 0)
        {
            exp = Integer.MAX_VALUE;
        }
        return exp;
    }
}
