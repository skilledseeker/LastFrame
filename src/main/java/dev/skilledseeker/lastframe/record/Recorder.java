/*
 * LastFrame - death replays for Paper. Watch the fight that killed you, from any angle.
 * Made by skilledseeker (skilledmc on Discord).
 */

package dev.skilledseeker.lastframe.record;

import dev.skilledseeker.lastframe.Settings;
import dev.skilledseeker.lastframe.LastFrame;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps the last few seconds of every recorded world in a ring buffer: where each player stood,
 * what they held, and what happened (swings, hits, throws, blocks). When someone dies,
 * {@link #cut} copies the part of the buffer around them into a {@link Clip}.
 *
 * <p>Runs entirely on the main thread. Cost per tick is one small object per player (equipment is
 * shared between frames until it changes) plus one per projectile in the air.
 */
public final class Recorder implements Listener {

    private static final class Tracked {
        final Projectile entity;
        final int id;
        final ProjectileKind kind;
        final ItemStack item;
        int stuckTicks;

        Tracked(Projectile entity, int id, ProjectileKind kind, ItemStack item) {
            this.entity = entity;
            this.id = id;
            this.kind = kind;
            this.item = item;
        }
    }

    private record Departed(ActorInfo info, long tick) {
    }

    private final LastFrame plugin;
    private TickRecord[] ring = new TickRecord[0];
    private int head = -1;
    private int count;
    private long tick;
    private final List<ReplayEvent> pending = new ArrayList<>();
    private final Map<UUID, Equipment> equipment = new HashMap<>();
    private final Map<UUID, Tracked> projectiles = new LinkedHashMap<>();
    private final Map<UUID, Departed> departed = new HashMap<>();
    private int nextProjectileId;

    public Recorder(LastFrame plugin) {
        this.plugin = plugin;
        resize();
    }

    /** Matches the buffer to record.buffer-seconds, keeping as much of the recording as fits. */
    public void resize() {
        int capacity = plugin.settings().bufferTicks + 2;
        if (ring.length == capacity) {
            return;
        }
        List<TickRecord> old = latest(count);
        ring = new TickRecord[capacity];
        head = -1;
        count = 0;
        for (int i = Math.max(0, old.size() - capacity); i < old.size(); i++) {
            push(old.get(i));
        }
    }

    /** Called once per tick. */
    public void record() {
        tick++;
        List<PlayerFrame> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (recordable(player)) {
                players.add(frame(player));
            } else {
                equipment.remove(player.getUniqueId());
            }
        }
        ReplayEvent[] events = pending.isEmpty() ? TickRecord.NO_EVENTS : pending.toArray(TickRecord.NO_EVENTS);
        pending.clear();
        push(new TickRecord(tick, players.toArray(TickRecord.NO_PLAYERS), projectileFrames(), events));
        if (tick % 200 == 0) {
            departed.values().removeIf(d -> tick - d.tick() > ring.length);
        }
    }

    /**
     * Alive, in a recorded world, and not a spectator. Creative counts: staff keep creative across world
     * changes (the network's staff script), and a creative killer missing from the replay leaves the
     * victim being hit by nobody. Replay viewers are spectators, so they are never recorded.
     */
    public boolean recordable(Player player) {
        return !player.isDead()
                && player.getGameMode() != GameMode.SPECTATOR
                && plugin.settings().records(player.getWorld());
    }

    private PlayerFrame frame(Player player) {
        Location at = player.getLocation();
        int flags = 0;
        if (player.isSneaking()) {
            flags |= PlayerFrame.SNEAKING;
        }
        if (player.isSprinting()) {
            flags |= PlayerFrame.SPRINTING;
        }
        if (player.isSwimming()) {
            flags |= PlayerFrame.SWIMMING;
        }
        if (player.isGliding()) {
            flags |= PlayerFrame.GLIDING;
        }
        if (player.isRiptiding()) {
            flags |= PlayerFrame.RIPTIDING;
        }
        if (player.isInvisible()) {
            flags |= PlayerFrame.INVISIBLE;
        }
        if (player.isGlowing()) {
            flags |= PlayerFrame.GLOWING;
        }
        if (player.getFireTicks() > 0) {
            flags |= PlayerFrame.ON_FIRE;
        }
        UUID id = player.getUniqueId();
        Equipment eq = equipment.get(id);
        if (eq == null || !eq.matches(player)) {
            eq = Equipment.of(player);
            equipment.put(id, eq);
        }
        EquipmentSlot hand = player.hasActiveItem() ? player.getActiveItemHand() : null;
        return new PlayerFrame(id, at.getWorld().getUID(), at.getX(), at.getY(), at.getZ(),
                at.getYaw(), at.getPitch(), player.getBodyYaw(), flags, hand, eq, (float) player.getHealth());
    }

    private ProjectileFrame[] projectileFrames() {
        if (projectiles.isEmpty()) {
            return TickRecord.NO_PROJECTILES;
        }
        List<ProjectileFrame> out = new ArrayList<>(projectiles.size());
        Iterator<Tracked> it = projectiles.values().iterator();
        while (it.hasNext()) {
            Tracked tracked = it.next();
            Projectile entity = tracked.entity;
            if (!entity.isValid()) {
                it.remove();
                continue;
            }
            // An arrow stuck for longer than the buffer is just part of the map now.
            if (entity instanceof AbstractArrow arrow && arrow.isInBlock() && ++tracked.stuckTicks > ring.length) {
                it.remove();
                continue;
            }
            Location at = entity.getLocation();
            Vector v = entity.getVelocity();
            out.add(new ProjectileFrame(tracked.id, tracked.kind, tracked.item, at.getWorld().getUID(),
                    at.getX(), at.getY(), at.getZ(), (float) v.getX(), (float) v.getY(), (float) v.getZ()));
        }
        return out.toArray(TickRecord.NO_PROJECTILES);
    }

    private void push(TickRecord record) {
        head = (head + 1) % ring.length;
        ring[head] = record;
        count = Math.min(count + 1, ring.length);
    }

    /** The newest {@code n} records (fewer if the buffer holds fewer), oldest first, in a new mutable list. */
    private List<TickRecord> latest(int n) {
        int take = Math.min(n, count);
        List<TickRecord> out = new ArrayList<>(take + 1);
        for (int i = take - 1; i >= 0; i--) {
            out.add(ring[Math.floorMod(head - i, ring.length)]);
        }
        return out;
    }

    // ------------------------------------------------------------------ cutting a clip

    /** Marks the death in the recording. Called before {@link #cut} for the same death. */
    public void noteDeath(Player victim) {
        if (plugin.settings().records(victim.getWorld())) {
            pending.add(new ReplayEvent.Death(victim.getUniqueId()));
        }
    }

    /**
     * The replay of {@code victim}'s death, which is happening right now. Null when the world is not
     * recorded or nothing was recorded yet.
     */
    public Clip cut(Player victim, Player killer) {
        Settings settings = plugin.settings();
        World world = victim.getWorld();
        if (!settings.records(world) || count == 0) {
            return null;
        }
        UUID worldId = world.getUID();
        UUID victimId = victim.getUniqueId();
        UUID killerId = killer == null || killer.equals(victim) ? null : killer.getUniqueId();
        Location death = victim.getLocation();
        double cx = death.getX();
        double cy = death.getY();
        double cz = death.getZ();

        List<TickRecord> window = latest(settings.clipTicks - 1);
        window.add(snapshotNow(victim));

        // Everyone who came within the radius of the victim at any point.
        double r2 = settings.radius * settings.radius;
        Set<UUID> ids = new LinkedHashSet<>();
        ids.add(victimId);
        if (killerId != null) {
            ids.add(killerId);
        }
        for (TickRecord record : window) {
            PlayerFrame anchor = record.player(victimId);
            double ax = anchor != null ? anchor.x() : cx;
            double ay = anchor != null ? anchor.y() : cy;
            double az = anchor != null ? anchor.z() : cz;
            for (PlayerFrame frame : record.players()) {
                if (frame.world().equals(worldId) && frame.distanceSquared(ax, ay, az) <= r2) {
                    ids.add(frame.id());
                }
            }
        }
        // A replay shows only what the victim could see: vanished staff stay vanished.
        ids.removeIf(id -> {
            Player other = Bukkit.getPlayer(id);
            return other != null && !other.equals(victim) && !victim.canSee(other);
        });

        int start = 0;
        if (settings.leadInTicks > 0) {
            int first = firstHit(window, victimId);
            if (first > 0) {
                start = Math.max(0, first - settings.leadInTicks);
            }
        }

        double keep = settings.radius * 1.5;
        double keep2 = keep * keep;
        List<TickRecord> frames = new ArrayList<>(window.size() - start);
        Set<UUID> seen = new LinkedHashSet<>();
        for (int i = start; i < window.size(); i++) {
            TickRecord filtered = filter(window.get(i), ids, worldId, cx, cy, cz, keep2);
            for (PlayerFrame frame : filtered.players()) {
                seen.add(frame.id());
            }
            frames.add(filtered);
        }

        Map<UUID, ActorInfo> actors = new HashMap<>();
        for (UUID id : seen) {
            ActorInfo info = info(id);
            if (info != null) {
                actors.put(id, info);
            }
        }

        int killerHits = 0;
        int victimHits = 0;
        if (killerId != null) {
            for (TickRecord record : frames) {
                for (ReplayEvent event : record.events()) {
                    if (event instanceof ReplayEvent.Hurt hurt && !hurt.blocked() && hurt.damage() > 0) {
                        if (hurt.actor().equals(victimId) && killerId.equals(hurt.attacker())) {
                            killerHits++;
                        } else if (hurt.actor().equals(killerId) && victimId.equals(hurt.attacker())) {
                            victimHits++;
                        }
                    }
                }
            }
        }
        float killerHealth = killer != null && killerId != null ? (float) killer.getHealth() : 0f;
        return new Clip(worldId, cx, cy, cz, victimId, killerId, List.copyOf(frames), Map.copyOf(actors),
                killerHealth, killerHits, victimHits);
    }

    /** This instant, mid-tick: everyone's position now and the events not yet flushed into the ring. */
    private TickRecord snapshotNow(Player victim) {
        List<PlayerFrame> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.equals(victim) || recordable(player)) {
                players.add(frame(player));
            }
        }
        return new TickRecord(tick, players.toArray(TickRecord.NO_PLAYERS), projectileFrames(),
                pending.toArray(TickRecord.NO_EVENTS));
    }

    private static int firstHit(List<TickRecord> window, UUID victim) {
        for (int i = 0; i < window.size(); i++) {
            for (ReplayEvent event : window.get(i).events()) {
                if (event instanceof ReplayEvent.Hurt hurt
                        && (hurt.actor().equals(victim) || victim.equals(hurt.attacker()))) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static TickRecord filter(TickRecord record, Set<UUID> ids, UUID world,
                                     double cx, double cy, double cz, double keep2) {
        List<PlayerFrame> players = new ArrayList<>();
        for (PlayerFrame frame : record.players()) {
            if (ids.contains(frame.id()) && frame.world().equals(world)) {
                players.add(frame);
            }
        }
        List<ProjectileFrame> projectiles = new ArrayList<>();
        for (ProjectileFrame frame : record.projectiles()) {
            if (frame.world().equals(world) && frame.distanceSquared(cx, cy, cz) <= keep2) {
                projectiles.add(frame);
            }
        }
        List<ReplayEvent> events = new ArrayList<>();
        for (ReplayEvent event : record.events()) {
            if (event instanceof ReplayEvent.ActorEvent actorEvent) {
                if (ids.contains(actorEvent.actor())) {
                    events.add(event);
                }
            } else if (event instanceof ReplayEvent.PlacedEvent placed) {
                double dx = placed.x() - cx;
                double dy = placed.y() - cy;
                double dz = placed.z() - cz;
                if (placed.world().equals(world) && dx * dx + dy * dy + dz * dz <= keep2) {
                    events.add(event);
                }
            }
        }
        return new TickRecord(record.tick(), players.toArray(TickRecord.NO_PLAYERS),
                projectiles.toArray(TickRecord.NO_PROJECTILES), events.toArray(TickRecord.NO_EVENTS));
    }

    private ActorInfo info(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            return ActorInfo.of(online);
        }
        Departed gone = departed.get(id);
        return gone == null ? null : gone.info();
    }

    // ------------------------------------------------------------------ what happened

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerArmSwingEvent event) {
        if (recordable(event.getPlayer())) {
            pending.add(new ReplayEvent.Swing(event.getPlayer().getUniqueId(), event.getHand() == EquipmentSlot.OFF_HAND));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim) || !recordable(victim)) {
            return;
        }
        UUID attacker = null;
        double fromX = Double.NaN;
        double fromZ = Double.NaN;
        boolean critical = false;
        boolean melee = false;
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            Location from = damager.getLocation();
            fromX = from.getX();
            fromZ = from.getZ();
            if (damager instanceof Player hitter) {
                attacker = hitter.getUniqueId();
                melee = event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                        || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK;
                critical = byEntity.isCritical();
                if (melee && hitter.getInventory().getItemInMainHand().getType() == Material.MACE
                        && hitter.getFallDistance() > 1.5f) {
                    Location at = victim.getLocation();
                    Block ground = at.clone().subtract(0, 0.5, 0).getBlock();
                    pending.add(new ReplayEvent.Smash(at.getWorld().getUID(), at.getX(), at.getY(), at.getZ(),
                            hitter.getFallDistance() > 5f, ground.getType().isAir() ? null : ground.getBlockData()));
                }
            } else if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
                attacker = shooter.getUniqueId();
            }
        }
        boolean blocked = victim.isBlocking() && event.getFinalDamage() <= 0;
        pending.add(new ReplayEvent.Hurt(victim.getUniqueId(), attacker, (float) event.getFinalDamage(),
                critical, blocked, melee, fromX, fromZ));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTotem(EntityResurrectEvent event) {
        if (event.getEntity() instanceof Player player && recordable(player)) {
            pending.add(new ReplayEvent.Totem(player.getUniqueId()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (recordable(event.getPlayer())) {
            Material type = event.getItem().getType();
            boolean drink = type == Material.POTION || type == Material.MILK_BUCKET
                    || type == Material.HONEY_BOTTLE || type == Material.OMINOUS_BOTTLE;
            pending.add(new ReplayEvent.Consume(event.getPlayer().getUniqueId(), drink));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (!plugin.settings().records(projectile.getWorld())) {
            return;
        }
        ProjectileKind kind = ProjectileKind.of(projectile);
        if (kind == null) {
            return;
        }
        projectiles.put(projectile.getUniqueId(),
                new Tracked(projectile, ++nextProjectileId, kind, ProjectileKind.itemOf(projectile)));
        Location at = projectile.getLocation();
        pending.add(new ReplayEvent.Launch(kind, at.getWorld().getUID(), at.getX(), at.getY(), at.getZ()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onImpact(ProjectileHitEvent event) {
        Tracked tracked = projectiles.get(event.getEntity().getUniqueId());
        if (tracked == null) {
            return;
        }
        Location at = event.getEntity().getLocation();
        Color color = event.getEntity() instanceof ThrownPotion potion ? potionColor(potion) : null;
        pending.add(new ReplayEvent.Impact(tracked.kind, at.getWorld().getUID(), at.getX(), at.getY(), at.getZ(),
                color, event.getHitEntity() instanceof Player));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (plugin.settings().records(block.getWorld())) {
            pending.add(new ReplayEvent.BlockChange(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ(),
                    event.getBlockReplacedState().getBlockData(), block.getBlockData(), true));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (plugin.settings().records(block.getWorld())) {
            pending.add(new ReplayEvent.BlockChange(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ(),
                    block.getBlockData(), Material.AIR.createBlockData(), false));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        equipment.remove(player.getUniqueId());
        departed.put(player.getUniqueId(), new Departed(ActorInfo.of(player), tick));
    }

    private static Color potionColor(ThrownPotion potion) {
        PotionMeta meta = potion.getPotionMeta();
        if (meta.hasColor()) {
            return meta.getColor();
        }
        PotionType base = meta.getBasePotionType();
        if (base != null) {
            for (PotionEffect effect : base.getPotionEffects()) {
                return effect.getType().getColor();
            }
        }
        for (PotionEffect effect : meta.getCustomEffects()) {
            return effect.getType().getColor();
        }
        return Color.fromRGB(0x385DC6);
    }
}
