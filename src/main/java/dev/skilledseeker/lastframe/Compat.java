/*
 * LastFrame - death replays for Paper. Watch the fight that killed you, from any angle.
 * Made by skilledseeker (skilledmc on Discord).
 */

package dev.skilledseeker.lastframe;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Optional links to other plugins, all by reflection so none of them is needed at compile or run
 * time: ViaVersion (which client version a player runs), Floodgate (Bedrock players) and
 * UnstableCore, if the server runs it (whether a death was a duel). Every lookup fails safe.
 */
final class Compat {

    private final LastFrame plugin;
    private Object viaApi;
    private Method viaProtocol;
    private Method viaProtocolNumber;
    private Method viaLegacyVersion;
    private Object floodgateApi;
    private Method floodgateCheck;

    Compat(LastFrame plugin) {
        this.plugin = plugin;
        hookVia();
        hookFloodgate();
    }

    /** Whether this player's client can draw a replay (mannequins arrived in Java 1.21.9). */
    boolean canWatch(Player player) {
        if (isBedrock(player)) {
            return plugin.settings().bedrock;
        }
        int protocol = protocol(player);
        return protocol < 0 || protocol >= plugin.settings().minProtocol;
    }

    boolean isBedrock(Player player) {
        UUID id = player.getUniqueId();
        if (floodgateCheck != null) {
            try {
                return (boolean) floodgateCheck.invoke(floodgateApi, id);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // fall through to the UUID check
            }
        }
        // Floodgate's default UUIDs have an all-zero top half; a Java account never does.
        return id.getMostSignificantBits() == 0L;
    }

    /** The client's protocol number, or -1 when ViaVersion is not there to say. */
    int protocol(Player player) {
        if (viaApi == null) {
            return -1;
        }
        try {
            if (viaProtocol != null) {
                Object version = viaProtocol.invoke(viaApi, player.getUniqueId());
                return (int) viaProtocolNumber.invoke(version);
            }
            return (int) viaLegacyVersion.invoke(viaApi, player.getUniqueId());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    /** True while UnstableCore has this player in a started duel. */
    boolean inDuel(Player player) {
        Plugin core = Bukkit.getPluginManager().getPlugin("UnstableCore");
        if (core == null || !core.isEnabled()) {
            return false;
        }
        try {
            Object duels = core.getClass().getMethod("getDuelManager").invoke(core);
            if (duels == null) {
                return false;
            }
            Method check;
            try {
                // isInDuel() is also true while a request is merely pending; the core itself uses this one.
                check = duels.getClass().getMethod("isInCombatDuel", UUID.class);
            } catch (NoSuchMethodException old) {
                check = duels.getClass().getMethod("isInDuel", UUID.class);
            }
            return (boolean) check.invoke(duels, player.getUniqueId());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private void hookVia() {
        Plugin via = Bukkit.getPluginManager().getPlugin("ViaVersion");
        if (via == null) {
            return;
        }
        try {
            ClassLoader loader = via.getClass().getClassLoader();
            Class<?> viaClass = Class.forName("com.viaversion.viaversion.api.Via", true, loader);
            Class<?> apiClass = Class.forName("com.viaversion.viaversion.api.ViaAPI", true, loader);
            viaApi = viaClass.getMethod("getAPI").invoke(null);
            try {
                viaProtocol = apiClass.getMethod("getPlayerProtocolVersion", UUID.class);
                viaProtocolNumber = viaProtocol.getReturnType().getMethod("getVersion");
            } catch (NoSuchMethodException old) {
                viaProtocol = null;
                viaLegacyVersion = apiClass.getMethod("getPlayerVersion", UUID.class);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            viaApi = null;
            plugin.getLogger().log(Level.WARNING, "ViaVersion found but its API could not be read; every Java client may watch.", e);
        }
    }

    private void hookFloodgate() {
        Plugin floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
        if (floodgate == null) {
            return;
        }
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi", true,
                    floodgate.getClass().getClassLoader());
            floodgateApi = apiClass.getMethod("getInstance").invoke(null);
            floodgateCheck = apiClass.getMethod("isFloodgatePlayer", UUID.class);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            floodgateCheck = null;
        }
    }
}
