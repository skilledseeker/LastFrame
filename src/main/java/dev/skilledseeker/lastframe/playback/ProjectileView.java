/*
 * LastFrame - death replays for Paper. Watch the fight that killed you, from any angle.
 * Made by skilledseeker (skilledmc on Discord).
 */

package dev.skilledseeker.lastframe.playback;

import dev.skilledseeker.lastframe.record.ProjectileFrame;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A projectile drawn with item displays. Thrown things (pearls, potions, wind charges) are a sprite
 * facing the camera. Arrows and tridents point along their flight: two copies of the item sprite,
 * crossed at a right angle around the shaft, the same trick the vanilla arrow model uses.
 */
final class ProjectileView {

    private static final float TIP_TO_X = (float) (-Math.PI / 4);
    private static final float QUARTER = (float) (Math.PI / 2);

    final ItemDisplay[] displays;
    private final boolean directional;
    private final float scale;
    private final Vector3f lastDirection = new Vector3f();

    ProjectileView(ItemDisplay[] displays, boolean directional, float scale) {
        this.displays = displays;
        this.directional = directional;
        this.scale = scale;
    }

    void apply(World world, ProjectileFrame a, ProjectileFrame b, double t) {
        double x = a.x();
        double y = a.y();
        double z = a.z();
        float vx = a.vx();
        float vy = a.vy();
        float vz = a.vz();
        if (b != null && t > 0) {
            x = Actor.lerp(a.x(), b.x(), t);
            y = Actor.lerp(a.y(), b.y(), t);
            z = Actor.lerp(a.z(), b.z(), t);
            vx = (float) Actor.lerp(a.vx(), b.vx(), t);
            vy = (float) Actor.lerp(a.vy(), b.vy(), t);
            vz = (float) Actor.lerp(a.vz(), b.vz(), t);
        }
        Location at = new Location(world, x, y, z, 0f, 0f);
        for (ItemDisplay display : displays) {
            display.teleport(at);
        }
        if (directional) {
            Vector3f direction = new Vector3f(vx, vy, vz);
            if (direction.lengthSquared() > 1.0e-4f) {
                direction.normalize();
                if (direction.distanceSquared(lastDirection) > 1.0e-4f) {
                    lastDirection.set(direction);
                    orient(direction);
                }
            }
        }
    }

    /** First turns the sprite so its tip points along +X, then +X onto the flight direction. */
    private void orient(Vector3f direction) {
        Quaternionf along = new Quaternionf().rotationTo(1f, 0f, 0f, direction.x, direction.y, direction.z);
        Vector3f size = new Vector3f(scale);
        for (int i = 0; i < displays.length; i++) {
            Quaternionf rotation = new Quaternionf(along);
            if (i == 1) {
                rotation.rotateX(QUARTER);
            }
            rotation.rotateZ(TIP_TO_X);
            displays[i].setInterpolationDelay(0);
            displays[i].setTransformation(new Transformation(new Vector3f(), rotation, size, new Quaternionf()));
        }
    }
}
