/*
 * LastFrame - death replays for Paper. Watch the fight that killed you, from any angle.
 * Made by skilledseeker (skilledmc on Discord).
 */

package dev.skilledseeker.lastframe.record;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** One projectile in one tick. {@code id} is stable for the projectile's whole flight. */
public record ProjectileFrame(int id, ProjectileKind kind, ItemStack item, UUID world,
                              double x, double y, double z,
                              float vx, float vy, float vz) {

    public double distanceSquared(double ox, double oy, double oz) {
        double dx = x - ox;
        double dy = y - oy;
        double dz = z - oz;
        return dx * dx + dy * dy + dz * dz;
    }
}
