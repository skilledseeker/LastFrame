/*
 * LastFrame - death replays for Paper. Watch the fight that killed you, from any angle.
 * Made by skilledseeker (skilledmc on Discord).
 */

package dev.skilledseeker.lastframe.record;

/** Everything recorded in one server tick. */
public record TickRecord(long tick, PlayerFrame[] players, ProjectileFrame[] projectiles, ReplayEvent[] events) {

    static final PlayerFrame[] NO_PLAYERS = new PlayerFrame[0];
    static final ProjectileFrame[] NO_PROJECTILES = new ProjectileFrame[0];
    static final ReplayEvent[] NO_EVENTS = new ReplayEvent[0];

    public PlayerFrame player(java.util.UUID id) {
        for (PlayerFrame frame : players) {
            if (frame.id().equals(id)) {
                return frame;
            }
        }
        return null;
    }

    public ProjectileFrame projectile(int id) {
        for (ProjectileFrame frame : projectiles) {
            if (frame.id() == id) {
                return frame;
            }
        }
        return null;
    }
}
