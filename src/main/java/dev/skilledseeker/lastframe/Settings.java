/*
 * LastFrame - death replays for Paper. Watch the fight that killed you, from any angle.
 * Made by skilledseeker (skilledmc on Discord).
 */

package dev.skilledseeker.lastframe;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** config.yml, read once per load/reload. All durations are converted to ticks here. */
public final class Settings {

    public final boolean enabled;
    private final Set<String> worlds;
    private final Set<String> excludedWorlds;
    public final int bufferTicks;

    public final int clipTicks;
    public final double radius;
    public final int leadInTicks;
    public final boolean onlyPlayerKills;
    public final boolean includeDuels;

    public final int offerTicks;
    public final int respawnWaitTicks;
    public final boolean deathScreenMenu;

    public final boolean killcam;
    public final boolean killcamAutoRespawn;
    public final int killcamRespawnDelayTicks;
    public final boolean killcamKillerPov;
    public final boolean killcamSneakSkips;

    public final boolean slowMotion;
    public final int slowMotionTicks;
    public final double slowMotionSpeed;
    public final double maxDistance;
    public final int endHoldTicks;
    public final boolean hideLiveWorld;
    public final boolean damageNumbers;
    public final boolean projectileSounds;

    public final int minProtocol;
    public final boolean bedrock;

    private final Map<String, String> messages;

    private Settings(FileConfiguration c) {
        enabled = c.getBoolean("enabled", true);
        worlds = lower(c.getStringList("record.worlds"));
        excludedWorlds = lower(c.getStringList("record.excluded-worlds"));

        clipTicks = seconds(c.getDouble("clip.seconds", 10), 2);
        bufferTicks = Math.max(clipTicks, seconds(c.getDouble("record.buffer-seconds", 12), 2));
        radius = Math.max(4, c.getDouble("clip.radius", 40));
        leadInTicks = seconds(c.getDouble("clip.lead-in-seconds", 3), 0);
        onlyPlayerKills = c.getBoolean("clip.only-player-kills", true);
        includeDuels = c.getBoolean("clip.include-duels", false);

        offerTicks = seconds(c.getDouble("offer.seconds", 10), 1);
        respawnWaitTicks = seconds(c.getDouble("offer.respawn-wait-seconds", 60), 1);
        deathScreenMenu = c.getBoolean("offer.death-screen-menu", true);

        killcam = c.getBoolean("killcam.enabled", true);
        killcamAutoRespawn = c.getBoolean("killcam.auto-respawn", true);
        killcamRespawnDelayTicks = seconds(c.getDouble("killcam.respawn-delay-seconds", 1), 1);
        killcamKillerPov = c.getBoolean("killcam.killer-pov", true);
        killcamSneakSkips = c.getBoolean("killcam.sneak-skips", true);

        slowMotion = c.getBoolean("playback.slow-motion", false);
        slowMotionTicks = seconds(c.getDouble("playback.slow-motion-seconds", 1.5), 0);
        slowMotionSpeed = clamp(c.getDouble("playback.slow-motion-speed", 0.35), 0.05, 1);
        maxDistance = Math.max(8, c.getDouble("playback.max-distance", 48));
        endHoldTicks = seconds(c.getDouble("playback.end-hold-seconds", 5), 1);
        hideLiveWorld = c.getBoolean("playback.hide-live-world", true);
        damageNumbers = c.getBoolean("playback.damage-numbers", true);
        projectileSounds = c.getBoolean("playback.projectile-sounds", false);

        minProtocol = c.getInt("clients.min-protocol", 773);
        bedrock = c.getBoolean("clients.bedrock", false);

        // The keys of the server's file plus the jar's defaults: a message added in a newer version is
        // missing from an older config.yml (Bukkit never rewrites it) and falls back to the default.
        messages = new HashMap<>();
        Set<String> keys = new HashSet<>();
        ConfigurationSection section = c.getConfigurationSection("messages");
        if (section != null) {
            keys.addAll(section.getKeys(false));
        }
        if (c.getDefaults() != null && c.getDefaults().getConfigurationSection("messages") != null) {
            keys.addAll(c.getDefaults().getConfigurationSection("messages").getKeys(false));
        }
        for (String key : keys) {
            messages.put(key, c.getString("messages." + key, ""));
        }
    }

    public static Settings load(FileConfiguration config) {
        return new Settings(config);
    }

    public boolean records(World world) {
        String name = world.getName().toLowerCase(Locale.ROOT);
        if (!worlds.isEmpty()) {
            return worlds.contains(name);
        }
        return !excludedWorlds.contains(name);
    }

    /** The raw message, or "" when the key is missing (a message removed from config is just not sent). */
    public String message(String key) {
        return messages.getOrDefault(key, "");
    }

    private static Set<String> lower(Iterable<String> values) {
        Set<String> out = new HashSet<>();
        for (String value : values) {
            out.add(value.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static int seconds(double seconds, int minTicks) {
        return Math.max(minTicks, (int) Math.round(seconds * 20));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
