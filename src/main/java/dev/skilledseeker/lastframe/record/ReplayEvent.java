/*
 * LastFrame - death replays for Paper. Watch the fight that killed you, from any angle.
 * Made by skilledseeker (skilledmc on Discord).
 */

package dev.skilledseeker.lastframe.record;

import org.bukkit.Color;
import org.bukkit.block.data.BlockData;

import java.util.UUID;

/**
 * Something that happened in a tick, beyond where everyone stood. Events tied to a player
 * ({@link ActorEvent}) are kept in a clip when that player is; the rest are kept when they
 * happened near the death.
 */
public sealed interface ReplayEvent {

    sealed interface ActorEvent extends ReplayEvent {
        UUID actor();
    }

    sealed interface PlacedEvent extends ReplayEvent {
        UUID world();

        double x();

        double y();

        double z();
    }

    record Swing(UUID actor, boolean offHand) implements ActorEvent {
    }

    /**
     * A hit taken. {@code fromX/fromZ} is where it came from (the attacker or projectile), NaN when
     * nothing hit them (fall, fire...).
     */
    record Hurt(UUID actor, UUID attacker, float damage, boolean critical, boolean blocked, boolean melee,
                double fromX, double fromZ) implements ActorEvent {
    }

    record Death(UUID actor) implements ActorEvent {
    }

    record Totem(UUID actor) implements ActorEvent {
    }

    record Consume(UUID actor, boolean drink) implements ActorEvent {
    }

    record Launch(ProjectileKind kind, UUID world, double x, double y, double z) implements PlacedEvent {
    }

    /** A projectile landing. {@code color} is the potion colour for splash potions, else null. */
    record Impact(ProjectileKind kind, UUID world, double x, double y, double z, Color color, boolean hitPlayer)
            implements PlacedEvent {
    }

    record Smash(UUID world, double x, double y, double z, boolean heavy, BlockData ground) implements PlacedEvent {
    }

    record BlockChange(UUID world, int bx, int by, int bz, BlockData before, BlockData after, boolean placed)
            implements PlacedEvent {
        @Override
        public double x() {
            return bx + 0.5;
        }

        @Override
        public double y() {
            return by + 0.5;
        }

        @Override
        public double z() {
            return bz + 0.5;
        }
    }
}
