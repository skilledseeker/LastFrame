/*
 * LastFrame - death replays for Paper. Watch the fight that killed you, from any angle.
 * Made by skilledseeker (skilledmc on Discord).
 */

package dev.skilledseeker.lastframe.playback;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent;
import dev.skilledseeker.lastframe.LastFrame;
import dev.skilledseeker.lastframe.record.Clip;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Every replay being watched, plus the rules that keep a viewer inside theirs: no commands, no
 * spectator-menu teleports, no looking through anything but the replay's own players. The
 * mannequins and displays it spawns exist only for their viewer, so they are also shielded from the
 * live world (they cannot block arrows, take damage, or be stopped from spawning in a protected arena).
 */
public final class SessionManager implements Listener {

    private static final Set<String> ALLOWED_COMMANDS = Set.of("replay", "deathreplay", "lastframe");

    private final LastFrame plugin;
    private final NamespacedKey entityKey;
    private final Map<UUID, ReplaySession> sessions = new HashMap<>();
    private final Set<UUID> ours = new HashSet<>();
    /** Viewers this plugin is moving right now, so its own teleports and mode changes are not treated as outside ones. */
    private final Set<UUID> internal = new HashSet<>();

    public SessionManager(LastFrame plugin) {
        this.plugin = plugin;
        this.entityKey = new NamespacedKey(plugin, "replay_entity");
    }

    public boolean isWatching(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    public ReplaySession session(Player player) {
        return sessions.get(player.getUniqueId());
    }

    public boolean start(Player viewer, Clip clip) {
        return start(viewer, clip, false);
    }

    /** {@code killcam}: it plays by itself from the killer's eyes and a sneak skips it. */
    public boolean start(Player viewer, Clip clip, boolean killcam) {
        if (isWatching(viewer)) {
            return false;
        }
        ReplaySession session = new ReplaySession(plugin, this, viewer, clip, killcam);
        sessions.put(viewer.getUniqueId(), session);
        boolean started;
        try {
            started = session.begin();
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Replay for " + viewer.getName() + " failed to start", e);
            started = false;
        }
        if (!started) {
            sessions.remove(viewer.getUniqueId());
            session.finish(true);
        }
        return started;
    }

    public void end(ReplaySession session, boolean restoreLocation) {
        if (sessions.remove(session.viewer.getUniqueId(), session)) {
            session.finish(restoreLocation);
        }
    }

    public void endAll() {
        for (ReplaySession session : List.copyOf(sessions.values())) {
            end(session, true);
        }
    }

    public void tick() {
        if (sessions.isEmpty()) {
            return;
        }
        for (ReplaySession session : List.copyOf(sessions.values())) {
            if (!session.viewer.isOnline()) {
                end(session, true);
                continue;
            }
            try {
                session.tick();
            } catch (RuntimeException e) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Replay for " + session.viewer.getName() + " broke; closing it", e);
                end(session, true);
            }
        }
    }

    void internal(Player player, Runnable action) {
        UUID id = player.getUniqueId();
        boolean outer = internal.add(id);
        try {
            action.run();
        } finally {
            if (outer) {
                internal.remove(id);
            }
        }
    }

    private boolean isInternal(Player player) {
        return internal.contains(player.getUniqueId());
    }

    // ------------------------------------------------------------------ replay entities

    /** Settings every replay entity gets before it is added to the world (so nobody else ever sees it). */
    void prepare(Entity entity) {
        entity.setVisibleByDefault(false);
        entity.setPersistent(false);
        entity.setSilent(true);
        entity.setInvulnerable(true);
        entity.setGravity(false);
        entity.getPersistentDataContainer().set(entityKey, PersistentDataType.BYTE, (byte) 1);
    }

    void adopt(Entity entity) {
        ours.add(entity.getUniqueId());
    }

    void release(Entity entity) {
        ours.remove(entity.getUniqueId());
        entity.remove();
    }

    public boolean isOurs(Entity entity) {
        return ours.contains(entity.getUniqueId()) || entity.getPersistentDataContainer().has(entityKey);
    }

    /** Replay entities are never saved, but a crash mid-replay could leave some loaded; clear them. */
    public void sweepStrays() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getPersistentDataContainer().has(entityKey)) {
                    entity.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            plugin.getLogger().info("Removed " + removed + " leftover replay entities.");
        }
    }

    // ------------------------------------------------------------------ keeping replay entities out of the live game

    /**
     * WorldGuard (block-plugin-spawning) and similar cancel plugin spawns in protected arenas. A replay
     * entity is invisible to everyone but its viewer, so let it through.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSpawn(EntitySpawnEvent event) {
        if (event.isCancelled() && event.getEntity().getPersistentDataContainer().has(entityKey)) {
            event.setCancelled(false);
        }
    }

    /** The mannequins still have a hitbox on the server: real arrows fly through them. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (event.getHitEntity() != null && isOurs(event.getHitEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (isOurs(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (isOurs(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ keeping the viewer in the replay

    /** Left-clicking a replay player looks through their eyes; nothing else can be spectated. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStartSpectating(PlayerStartSpectatingEntityEvent event) {
        ReplaySession session = session(event.getPlayer());
        if (session == null || isInternal(event.getPlayer())) {
            return;
        }
        if (!session.isActor(event.getNewSpectatorTarget())) {
            event.setCancelled(true);
            return;
        }
        session.clickedInto(event.getNewSpectatorTarget());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStopSpectating(PlayerStopSpectatingEntityEvent event) {
        ReplaySession session = session(event.getPlayer());
        if (session != null && !isInternal(event.getPlayer())) {
            session.leftPov();
        }
    }

    /** Killcam: a sneak skips it. The session decides (free camera, or shift held since the death). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        ReplaySession session = session(event.getPlayer());
        if (session != null) {
            session.sneaked(event.isSneaking());
        }
    }

    /**
     * The spectator hotbar can teleport to any player on the server: blocked. Any other plugin's
     * teleport away from the fight ends the replay where that plugin put them.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        ReplaySession session = session(player);
        if (session == null || isInternal(player) || session.isNear(event.getTo())) {
            return;
        }
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE) {
            event.setCancelled(true);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> end(session, false));
    }

    /**
     * A viewer stays a spectator until the replay ends. Multiverse (enforce-gamemode) puts anyone who
     * enters an arena world back into survival one tick later, and WorldGuard's game-mode flag does the
     * same per region; both are refused here. The plugin's own restore runs after the session is gone,
     * so it is never blocked.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        if (isWatching(event.getPlayer()) && event.getNewGameMode() != GameMode.SPECTATOR) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!isWatching(player) || player.hasPermission("lastframe.bypass")) {
            return;
        }
        String label = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }
        if (!ALLOWED_COMMANDS.contains(label)) {
            event.setCancelled(true);
            plugin.text().send(player, "blocked-command");
        }
    }

    /**
     * Put the player back before anything saves them. HuskSync saves on quit at HIGHEST; doing this at
     * LOWEST means the saved copy is never a spectator in the arena.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        ReplaySession session = session(event.getPlayer());
        if (session != null) {
            end(session, true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        for (ReplaySession session : sessions.values()) {
            session.onPlayerJoin(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityAdded(EntityAddToWorldEvent event) {
        if (sessions.isEmpty()) {
            return;
        }
        Entity entity = event.getEntity();
        if (entity instanceof Player || isOurs(entity)) {
            return;
        }
        for (ReplaySession session : sessions.values()) {
            session.onEntityAdded(entity);
        }
    }
}
