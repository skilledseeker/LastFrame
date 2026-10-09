/*
 * LastFrame - death replays for Paper. Watch the fight that killed you, from any angle.
 * Made by skilledseeker (skilledmc on Discord).
 */

package dev.skilledseeker.lastframe.record;

import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** What a player held and wore. Immutable: the stacks are private copies. */
public record Equipment(ItemStack mainHand, ItemStack offHand,
                        ItemStack head, ItemStack chest, ItemStack legs, ItemStack feet) {

    static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HAND, EquipmentSlot.OFF_HAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    public ItemStack get(EquipmentSlot slot) {
        return switch (slot) {
            case HAND -> mainHand;
            case OFF_HAND -> offHand;
            case HEAD -> head;
            case CHEST -> chest;
            case LEGS -> legs;
            case FEET -> feet;
            default -> null;
        };
    }

    static Equipment of(Player player) {
        PlayerInventory inv = player.getInventory();
        return new Equipment(
                copy(inv.getItemInMainHand()), copy(inv.getItemInOffHand()),
                copy(inv.getHelmet()), copy(inv.getChestplate()),
                copy(inv.getLeggings()), copy(inv.getBoots()));
    }

    /** True when the player still holds and wears exactly this. */
    boolean matches(Player player) {
        PlayerInventory inv = player.getInventory();
        return same(mainHand, inv.getItemInMainHand())
                && same(offHand, inv.getItemInOffHand())
                && same(head, inv.getHelmet())
                && same(chest, inv.getChestplate())
                && same(legs, inv.getLeggings())
                && same(feet, inv.getBoots());
    }

    private static ItemStack copy(ItemStack stack) {
        return stack == null || stack.getType().isAir() ? null : stack.clone();
    }

    private static boolean same(ItemStack recorded, ItemStack live) {
        boolean liveEmpty = live == null || live.getType().isAir();
        if (recorded == null) {
            return liveEmpty;
        }
        return !liveEmpty && recorded.equals(live);
    }
}
