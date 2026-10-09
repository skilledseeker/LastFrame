/*
 * LastFrame - death replays for Paper. Watch the fight that killed you, from any angle.
 * Made by skilledseeker (skilledmc on Discord).
 */

package dev.skilledseeker.lastframe.playback;

import dev.skilledseeker.lastframe.record.Equipment;
import dev.skilledseeker.lastframe.record.PlayerFrame;
import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Set;
import java.util.UUID;

/** One recorded player, acted out by a mannequin only the viewer can see. */
final class Actor {

    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HAND, EquipmentSlot.OFF_HAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    /** Long enough that a mannequin never finishes eating on its own; the recording says when it stops. */
    private static final int USE_FOREVER = 72000;

    final UUID id;
    final Mannequin entity;
    private Equipment equipment;
    private int flags = -1;
    private EquipmentSlot using;
    private double x = Double.NaN;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private float bodyYaw = Float.NaN;
    /** Frame index of this player's death in the clip, -1 while alive. A dead actor lies still. */
    int deadAt = -1;

    Actor(UUID id, Mannequin entity) {
        this.id = id;
        this.entity = entity;
    }

    /**
     * Stops the eat/drink animation. The client loops its gulps and potion particles for as long as
     * the entity keeps the item raised, so a mannequin left mid-drink (paused, dead, or on the frozen
     * last frame) would keep sounding. The next frame with the item in use starts it again. Bows,
     * shields and the like have no such effects and keep their pose.
     */
    void freeze() {
        if (using == null) {
            return;
        }
        ItemStack held = equipment == null ? null : equipment.get(using);
        if (held != null && held.hasData(DataComponentTypes.CONSUMABLE)) {
            entity.clearActiveItem();
            using = null;
        }
    }

    /** Moves to the point {@code t} of the way from frame {@code a} to frame {@code b} (b may be null). */
    void apply(World world, PlayerFrame a, PlayerFrame b, double t) {
        double nx = a.x();
        double ny = a.y();
        double nz = a.z();
        float nyaw = a.yaw();
        float npitch = a.pitch();
        float nbody = a.bodyYaw();
        if (b != null && t > 0) {
            nx = lerp(a.x(), b.x(), t);
            ny = lerp(a.y(), b.y(), t);
            nz = lerp(a.z(), b.z(), t);
            nyaw = lerpAngle(a.yaw(), b.yaw(), t);
            npitch = (float) lerp(a.pitch(), b.pitch(), t);
            nbody = lerpAngle(a.bodyYaw(), b.bodyYaw(), t);
        }
        if (nx != x || ny != y || nz != z || nyaw != yaw || npitch != pitch) {
            entity.teleport(new Location(world, nx, ny, nz, nyaw, npitch));
            x = nx;
            y = ny;
            z = nz;
            yaw = nyaw;
            pitch = npitch;
        }
        if (nbody != bodyYaw) {
            entity.setBodyYaw(nbody);
            bodyYaw = nbody;
        }
        if (a.equipment() != equipment) {
            wear(a.equipment());
        }
        if (a.flags() != flags) {
            applyFlags(a);
        } else if (a.has(PlayerFrame.GLIDING) && !entity.isGliding()) {
            // the server drops the gliding flag on entities that are not really falling; keep it on
            entity.setGliding(true);
        }
        if (a.activeHand() != using) {
            using = a.activeHand();
            if (using == null) {
                entity.clearActiveItem();
            } else {
                entity.startUsingItem(using);
                entity.setActiveItemRemainingTime(USE_FOREVER);
            }
        }
    }

    private void wear(Equipment next) {
        equipment = next;
        EntityEquipment gear = entity.getEquipment();
        for (EquipmentSlot slot : SLOTS) {
            gear.setItem(slot, next == null ? null : next.get(slot), true);
        }
    }

    private void applyFlags(PlayerFrame frame) {
        flags = frame.flags();
        entity.setSneaking(frame.has(PlayerFrame.SNEAKING));
        entity.setGliding(frame.has(PlayerFrame.GLIDING));
        entity.setRiptiding(frame.has(PlayerFrame.RIPTIDING));
        entity.setInvisible(frame.has(PlayerFrame.INVISIBLE));
        entity.setGlowing(frame.has(PlayerFrame.GLOWING));
        entity.setVisualFire(frame.has(PlayerFrame.ON_FIRE));
        Pose pose = Pose.STANDING;
        if (frame.has(PlayerFrame.GLIDING)) {
            pose = Pose.FALL_FLYING;
        } else if (frame.has(PlayerFrame.RIPTIDING)) {
            pose = Pose.SPIN_ATTACK;
        } else if (frame.has(PlayerFrame.SWIMMING)) {
            pose = Pose.SWIMMING;
        } else if (frame.has(PlayerFrame.SNEAKING)) {
            pose = Pose.SNEAKING;
        }
        Set<Pose> valid = Mannequin.validPoses();
        entity.setPose(valid.contains(pose) ? pose : Pose.STANDING, true);
    }

    static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    static float lerpAngle(float a, float b, double t) {
        float delta = ((b - a) % 360f + 540f) % 360f - 180f;
        return (float) (a + delta * t);
    }
}
