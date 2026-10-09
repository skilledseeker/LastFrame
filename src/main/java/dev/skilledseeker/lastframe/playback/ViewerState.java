/*
 * LastFrame - death replays for Paper. Watch the fight that killed you, from any angle.
 * Made by skilledseeker (skilledmc on Discord).
 */

package dev.skilledseeker.lastframe.playback;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** What watching a replay changes on the viewer, so it can be put back exactly. */
record ViewerState(Location location, GameMode mode, boolean allowFlight, boolean flying, float flySpeed) {

    static ViewerState of(Player player) {
        return new ViewerState(player.getLocation().clone(), player.getGameMode(), player.getAllowFlight(),
                player.isFlying(), player.getFlySpeed());
    }

    /**
     * Game mode first, then the teleport, in the same tick (so no tick passes as a survival player in the
     * arena). The order matters: world-change listeners read the game mode as the player arrives - the
     * network's staff script saves it and puts it back 3 ticks later - so it must already be the real one.
     */
    void restore(Player player, boolean teleport) {
        player.setGameMode(mode);
        if (teleport && location.getWorld() != null) {
            player.teleport(location);
        }
        player.setAllowFlight(allowFlight);
        if (allowFlight) {
            player.setFlying(flying);
        }
        player.setFlySpeed(flySpeed);
        player.setFallDistance(0f);
    }
}
