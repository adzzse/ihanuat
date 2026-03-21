package com.ihanuat.mod.modules.profitTracker;

import com.ihanuat.mod.MacroConfig;
import com.ihanuat.mod.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Detects whether the player has an Agronomy Sack (or Enchanted variant)
 * in their Sack of Sacks. When present, crop tracking is routed through
 * this tracker instead of {@link InventoryTracker}.
 *
 * <p>Detection flow:
 * <ol>
 *   <li>{@link #startSackDetection(Minecraft)} sends {@code /sacks}</li>
 *   <li>{@link #handleSackMenu(Minecraft, AbstractContainerScreen)} scans
 *       the opened GUI for Agronomy Sack items</li>
 *   <li>{@link #sackDetectionComplete} is set and the GUI is closed</li>
 * </ol>
 */
public class SackTracker {

    // ── Detection state ─────────────────────────────────────────────────────
    /** True when we have sent /sacks and are waiting for the GUI to open. */
    public static volatile boolean isScanningMenu = false;
    /** True once detection has finished (regardless of result). */
    public static volatile boolean sackDetectionComplete = false;
    /** True if an Agronomy Sack was found in the Sack of Sacks GUI. */
    public static volatile boolean hasAgronomySack = false;

    // ── Detection API ───────────────────────────────────────────────────────

    /**
     * Sends {@code /sacks} to open the Sack of Sacks GUI.
     * Must be called from the render/main thread.
     */
    public static void startSackDetection(Minecraft client) {
        isScanningMenu = true;
        sackDetectionComplete = false;
        hasAgronomySack = false;
        ClientUtils.sendCommand(client, "/sacks");
    }

    /**
     * Called every screen-tick while a container GUI is open and
     * {@link #isScanningMenu} is {@code true}.  Scans the "Sack of Sacks"
     * inventory for items whose display name contains
     * "Enchanted Agronomy Sack" or "Agronomy Sack".
     */
    public static void handleSackMenu(Minecraft client, AbstractContainerScreen<?> screen) {
        if (!isScanningMenu) return;

        String title = screen.getTitle().getString().trim();
        if (!title.toLowerCase().contains("sack of sacks")) return;

        var menu = screen.getMenu();
        if (menu == null) return;

        // Scan every slot in the container (excluding player inventory)
        int totalSlots = menu.slots.size();
        int playerInvStart = totalSlots - 36;
        boolean found = false;

        for (int i = 0; i < playerInvStart; i++) {
            if (i >= menu.slots.size()) break;
            Slot slot = menu.slots.get(i);
            if (slot == null || !slot.hasItem()) continue;

            ItemStack stack = slot.getItem();
            String itemName = ClientUtils.stripColor(stack.getHoverName().getString()).trim();

            if (itemName.contains("Enchanted Agronomy Sack") || itemName.contains("Agronomy Sack")) {
                found = true;
                break;
            }
        }

        hasAgronomySack = found;
        sackDetectionComplete = true;
        isScanningMenu = false;

        if (MacroConfig.showDebug) {
            if (found) {
                ClientUtils.sendDebugMessage(client,
                        "Sack detection: found Agronomy Sack, using SackTracker");
            } else {
                ClientUtils.sendDebugMessage(client,
                        "Sack detection: no Agronomy Sack found, using InventoryTracker");
            }
        }

        // Close the sack GUI
        int containerId = menu.containerId;
        client.setScreen(null);
        var connection = client.getConnection();
        if (client.player != null && connection != null) {
            connection.send(new ServerboundContainerClosePacket(containerId));
        }
    }

    // ── Crop tracking (placeholder) ─────────────────────────────────────────

    /**
     * Tick-based update for sack-based crop tracking.
     * Only called when {@link #hasAgronomySack} is {@code true}.
     * <p>
     * TODO: implement sack content delta tracking.
     */
    public static void update(Minecraft client) {
        // Placeholder -- sack-based tracking logic goes here
    }

    // ── Reset ───────────────────────────────────────────────────────────────

    public static void reset() {
        isScanningMenu = false;
        sackDetectionComplete = false;
        hasAgronomySack = false;
    }
}
