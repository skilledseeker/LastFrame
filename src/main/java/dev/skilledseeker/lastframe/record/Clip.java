/*
 * LastFrame - death replays for Paper. Watch the fight that killed you, from any angle.
 * Made by skilledseeker (skilledmc on Discord).
 */

package dev.skilledseeker.lastframe.record;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One death, cut out of the recording: the frames from a little before the fight to the kill,
 * holding only the players, projectiles and events near it. Lives in memory until it is watched
 * or the offer runs out.
 *
 * @param frames     oldest first; the last one is the moment of death
 * @param actors     how each player in the frames looks
 * @param killer     null when nobody killed them (only possible with only-player-kills off)
 * @param killerHits hits the killer landed on the victim inside the clip
 * @param victimHits hits the victim landed on the killer inside the clip
 */
public record Clip(UUID world, double centerX, double centerY, double centerZ,
                   UUID victim, UUID killer, List<TickRecord> frames, Map<UUID, ActorInfo> actors,
                   float killerHealth, int killerHits, int victimHits) {

    public int lastIndex() {
        return frames.size() - 1;
    }

    public World bukkitWorld() {
        return Bukkit.getWorld(world);
    }

    public Location center() {
        World w = bukkitWorld();
        return w == null ? null : new Location(w, centerX, centerY, centerZ);
    }

    public ActorInfo actor(UUID id) {
        return id == null ? null : actors.get(id);
    }
}
