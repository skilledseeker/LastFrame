/*
 * LastFrame - death replays for Paper. Watch the fight that killed you, from any angle.
 * Made by skilledseeker (skilledmc on Discord).
 */

package dev.skilledseeker.lastframe.record;

import org.bukkit.inventory.EquipmentSlot;

import java.util.UUID;

/**
 * One player in one tick. {@code equipment} is shared between frames until something in the
 * player's hands or armour changes, so a quiet fight costs one small object per player per tick.
 */
public record PlayerFrame(UUID id, UUID world,
                          double x, double y, double z,
                          float yaw, float pitch, float bodyYaw,
                          int flags, EquipmentSlot activeHand,
                          Equipment equipment, float health) {

    public static final int SNEAKING = 1;
    public static final int SPRINTING = 1 << 1;
    public static final int SWIMMING = 1 << 2;
    public static final int GLIDING = 1 << 3;
    public static final int RIPTIDING = 1 << 4;
    public static final int INVISIBLE = 1 << 5;
    public static final int GLOWING = 1 << 6;
    public static final int ON_FIRE = 1 << 7;

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    public double distanceSquared(double ox, double oy, double oz) {
        double dx = x - ox;
        double dy = y - oy;
        double dz = z - oz;
        return dx * dx + dy * dy + dz * dz;
    }
}
