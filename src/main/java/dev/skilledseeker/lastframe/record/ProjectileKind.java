/*
 * LastFrame - death replays for Paper. Watch the fight that killed you, from any angle.
 * Made by skilledseeker (skilledmc on Discord).
 */

package dev.skilledseeker.lastframe.record;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Egg;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.ThrowableProjectile;
import org.bukkit.entity.ThrownExpBottle;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.Trident;
import org.bukkit.entity.WindCharge;
import org.bukkit.inventory.ItemStack;

/** The projectiles a replay draws. Anything else (rockets, fishing hooks, fireballs) is skipped. */
public enum ProjectileKind {
    ARROW(true, 0.45f, Sound.ENTITY_ARROW_SHOOT),
    TRIDENT(true, 0.75f, Sound.ITEM_TRIDENT_THROW),
    PEARL(false, 0.4f, Sound.ENTITY_ENDER_PEARL_THROW),
    POTION(false, 0.4f, Sound.ENTITY_SPLASH_POTION_THROW),
    WIND_CHARGE(false, 0.45f, Sound.ENTITY_WIND_CHARGE_THROW),
    THROWN(false, 0.35f, Sound.ENTITY_SNOWBALL_THROW);

    /** Drawn pointing along its flight (two crossed sprites, like the vanilla arrow model), not facing the camera. */
    public final boolean directional;
    public final float scale;
    public final Sound launchSound;

    ProjectileKind(boolean directional, float scale, Sound launchSound) {
        this.directional = directional;
        this.scale = scale;
        this.launchSound = launchSound;
    }

    public static ProjectileKind of(Projectile projectile) {
        if (projectile instanceof Trident) {
            return TRIDENT;
        }
        if (projectile instanceof AbstractArrow) {
            return ARROW;
        }
        if (projectile instanceof EnderPearl) {
            return PEARL;
        }
        if (projectile instanceof ThrownPotion) {
            return POTION;
        }
        if (projectile instanceof WindCharge) {
            return WIND_CHARGE;
        }
        if (projectile instanceof Snowball || projectile instanceof Egg || projectile instanceof ThrownExpBottle) {
            return THROWN;
        }
        return null;
    }

    /** The item that represents it on screen. */
    public static ItemStack itemOf(Projectile projectile) {
        if (projectile instanceof Trident trident) {
            return trident.getItemStack().clone();
        }
        if (projectile instanceof AbstractArrow arrow) {
            return arrow.getItemStack().clone();
        }
        if (projectile instanceof ThrowableProjectile throwable) {
            return throwable.getItem().clone();
        }
        if (projectile instanceof WindCharge) {
            return new ItemStack(Material.WIND_CHARGE);
        }
        return new ItemStack(Material.SNOWBALL);
    }
}
