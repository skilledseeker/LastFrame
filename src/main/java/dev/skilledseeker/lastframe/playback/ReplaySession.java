/*
 * LastFrame - death replays for Paper. Watch the fight that killed you, from any angle.
 * Made by skilledseeker (skilledmc on Discord).
 */

package dev.skilledseeker.lastframe.playback;

import com.destroystokyo.paper.profile.PlayerProfile;
import dev.skilledseeker.lastframe.Settings;
import dev.skilledseeker.lastframe.LastFrame;
import dev.skilledseeker.lastframe.record.ActorInfo;
import dev.skilledseeker.lastframe.record.Clip;
import dev.skilledseeker.lastframe.record.PlayerFrame;
import dev.skilledseeker.lastframe.record.ProjectileFrame;
import dev.skilledseeker.lastframe.record.ProjectileKind;
import dev.skilledseeker.lastframe.record.ReplayEvent;
import dev.skilledseeker.lastframe.record.TickRecord;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Effect;
import org.bukkit.EntityEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One player watching one clip. The viewer becomes a spectator at the fight; every recorded player
 * is a mannequin and every projectile an item display, all visible to the viewer alone, moved each
 * tick to where the recording says. Pausing, seeking and speed only move the {@link #cursor}.
 */
public final class ReplaySession {

    private static final double[] SPEEDS = {0.25, 0.5, 1, 2};
    private static final int TEXT_LIFE = 18;
    private static final int CORPSE_FRAMES = 20;
    /** Ticks after the start in which a sneak cannot skip the killcam (it may still be the one held from before the death). */
    private static final int SKIP_GRACE = 10;

    private final LastFrame plugin;
    private final SessionManager manager;
    final Player viewer;
    final Clip clip;
    private final World world;
    private final Location center;
    private final ViewerState saved;
    private final BlockTimeline blocks;
    private final Map<UUID, Actor> actors = new HashMap<>();
    private final Map<Integer, ProjectileView> projectiles = new HashMap<>();
    private final List<FloatingText> texts = new ArrayList<>();
    private final Set<UUID> hiddenPlayers = new HashSet<>();
    private final Set<UUID> hiddenEntities = new HashSet<>();
    /** Chunks this replay holds loaded (a replay entity cannot exist in an unloaded chunk). */
    private final Set<Long> tickets = new HashSet<>();
    private final BossBar bar;
    /** BO3-style: started by itself right after the death, through the killer's eyes, sneak to skip. */
    private final boolean killcam;

    private double cursor;
    private double speed = 1;
    private boolean paused;
    private boolean ended;
    private int endHold;
    private int age;
    /** The actor the camera is in, or null for the free camera. */
    private UUID pov;
    /** An actor to put the camera back in once the client has it (after a seek respawned it). */
    private UUID pendingPov;
    private int pendingPovDelay;
    private String barKey = "";
    /** Killcam: shift was already held when it started; skipping needs a fresh press after letting go. */
    private boolean awaitingRelease;

    private static final class FloatingText {
        final TextDisplay entity;
        int age;

        FloatingText(TextDisplay entity) {
            this.entity = entity;
        }
    }

    ReplaySession(LastFrame plugin, SessionManager manager, Player viewer, Clip clip, boolean killcam) {
        this.plugin = plugin;
        this.manager = manager;
        this.viewer = viewer;
        this.clip = clip;
        this.killcam = killcam;
        this.world = clip.bukkitWorld();
        this.center = clip.center();
        this.saved = ViewerState.of(viewer);
        this.blocks = new BlockTimeline(clip);
        this.bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
    }

    // ------------------------------------------------------------------ start and stop

    boolean begin() {
        if (world == null || center == null) {
            return false;
        }
        if (plugin.settings().hideLiveWorld) {
            hideLiveWorld();
        }
        // every chunk the fight touched stays loaded for the whole replay
        for (TickRecord record : clip.frames()) {
            for (PlayerFrame frame : record.players()) {
                hold(frame.x(), frame.z());
            }
            for (ProjectileFrame frame : record.projectiles()) {
                hold(frame.x(), frame.z());
            }
        }
        Location camera = startCamera();
        manager.internal(viewer, () -> {
            viewer.setGameMode(GameMode.SPECTATOR);
            viewer.teleport(camera);
        });
        seek(0);
        if (killcam) {
            beginKillcam();
        } else {
            viewer.showTitle(Title.title(plugin.text().get("start-title"), plugin.text().get("start-subtitle"),
                    Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1800), Duration.ofMillis(400))));
            viewer.sendMessage(controlsLine());
            viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.6f, 1.4f);
        }
        updateBar();
        viewer.showBossBar(bar);
        return true;
    }

    /**
     * The killcam opens inside the killer's head. The client only gets the mannequin on its next
     * tracker update, so the camera is aimed a few ticks later (and again until the killer shows up,
     * if they were not in the first frame). Shift still held from before the death would make the
     * server throw the camera straight out again, so then the camera waits for it to be let go.
     */
    private void beginKillcam() {
        Settings settings = plugin.settings();
        awaitingRelease = settings.killcamSneakSkips && viewer.isSneaking();
        if (settings.killcamKillerPov && clip.killer() != null && !awaitingRelease) {
            pendingPov = clip.killer();
            pendingPovDelay = 3;
        }
        Map<String, String> values = Map.of("killer", killerName());
        viewer.showTitle(Title.title(plugin.text().get("killcam-title", values), plugin.text().get("killcam-subtitle", values),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2000), Duration.ofMillis(500))));
        if (!plugin.quickActionsReady()) {
            viewer.sendMessage(controlsLine());
        }
        viewer.playSound(viewer.getLocation(), Sound.UI_TOAST_IN, SoundCategory.MASTER, 0.8f, 0.9f);
    }

    /** Removes everything this session created and puts the viewer back. */
    void finish(boolean restoreLocation) {
        manager.internal(viewer, () -> {
            if (viewer.getSpectatorTarget() != null) {
                viewer.setSpectatorTarget(null);
            }
        });
        clearEntities();
        if (world != null) {
            for (long key : tickets) {
                world.removePluginChunkTicket((int) (key >> 32), (int) key, plugin);
            }
        }
        tickets.clear();
        if (viewer.isOnline()) {
            blocks.restore(viewer, world);
            for (UUID id : hiddenPlayers) {
                Player other = Bukkit.getPlayer(id);
                if (other != null) {
                    viewer.showPlayer(plugin, other);
                }
            }
            for (UUID id : hiddenEntities) {
                Entity entity = Bukkit.getEntity(id);
                if (entity != null) {
                    viewer.showEntity(plugin, entity);
                }
            }
            viewer.hideBossBar(bar);
            viewer.clearTitle();
        }
        hiddenPlayers.clear();
        hiddenEntities.clear();
        manager.internal(viewer, () -> saved.restore(viewer, restoreLocation));
    }

    private void hideLiveWorld() {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(viewer) && viewer.canSee(other)) {
                viewer.hidePlayer(plugin, other);
                hiddenPlayers.add(other.getUniqueId());
            }
        }
        double r = plugin.settings().radius * 1.5;
        for (Entity entity : world.getNearbyEntities(center, r, r, r)) {
            hideIfLoose(entity);
        }
    }

    void onPlayerJoin(Player joined) {
        if (plugin.settings().hideLiveWorld && !joined.equals(viewer)) {
            viewer.hidePlayer(plugin, joined);
            hiddenPlayers.add(joined.getUniqueId());
        }
    }

    void onEntityAdded(Entity entity) {
        if (!plugin.settings().hideLiveWorld || !entity.getWorld().equals(world)) {
            return;
        }
        double r = plugin.settings().radius * 1.5;
        if (entity.getLocation().distanceSquared(center) <= r * r) {
            hideIfLoose(entity);
        }
    }

    /** Live drops and projectiles would give the ending away (the victim's loot is already on the floor). */
    private void hideIfLoose(Entity entity) {
        if ((entity instanceof Item || entity instanceof ExperienceOrb || entity instanceof Projectile)
                && !manager.isOurs(entity)) {
            viewer.hideEntity(plugin, entity);
            hiddenEntities.add(entity.getUniqueId());
        }
    }

    /** Beside the fight, looking at it: across the line between victim and killer, a little above. */
    private Location startCamera() {
        TickRecord first = clip.frames().get(0);
        PlayerFrame victim = first.player(clip.victim());
        PlayerFrame killer = clip.killer() == null ? null : first.player(clip.killer());
        Vector target = victim != null ? new Vector(victim.x(), victim.y() + 1.2, victim.z())
                : center.toVector().add(new Vector(0, 1.2, 0));
        Vector side;
        if (victim != null && killer != null) {
            target = target.clone().add(new Vector(killer.x(), killer.y() + 1.2, killer.z())).multiply(0.5);
            Vector line = new Vector(killer.x() - victim.x(), 0, killer.z() - victim.z());
            side = line.lengthSquared() < 1.0e-3 ? new Vector(1, 0, 0) : new Vector(-line.getZ(), 0, line.getX()).normalize();
        } else if (victim != null) {
            double rad = Math.toRadians(victim.yaw());
            side = new Vector(Math.sin(rad), 0, -Math.cos(rad));
        } else {
            side = new Vector(1, 0, 0);
        }
        Location best = null;
        for (Vector offset : new Vector[]{side.clone().multiply(6), side.clone().multiply(-6)}) {
            Location candidate = target.clone().add(offset).add(new Vector(0, 3, 0)).toLocation(world);
            if (best == null) {
                best = candidate;
            }
            if (candidate.getBlock().isPassable()) {
                best = candidate;
                break;
            }
        }
        best.setDirection(target.clone().subtract(best.toVector()));
        return best;
    }

    // ------------------------------------------------------------------ playing

    void tick() {
        age++;
        // Multiverse's enforce-flight can take flight away on entering the arena world; a spectator
        // without it would sink through the map.
        if (!viewer.getAllowFlight()) {
            viewer.setAllowFlight(true);
        }
        if (!viewer.isFlying()) {
            viewer.setFlying(true);
        }
        if (pendingPov != null && --pendingPovDelay <= 0) {
            Actor actor = actors.get(pendingPov);
            if (actor != null) {
                pendingPov = null;
                look(actor);
            } else if (killcam && !ended && age < 100) {
                pendingPovDelay = 5; // the killer is not in the clip yet; try again shortly
            } else {
                pendingPov = null;
            }
        }
        if (ended) {
            if (!paused && --endHold <= 0) {
                manager.end(this, true);
                return;
            }
        } else if (!paused) {
            advance();
        }
        tickTexts();
        if (age % 10 == 0) {
            leash();
        }
        updateBar();
    }

    private void advance() {
        Settings settings = plugin.settings();
        int last = clip.lastIndex();
        double step = speed;
        if (settings.slowMotion && clip.killer() != null && cursor >= last - settings.slowMotionTicks) {
            step *= settings.slowMotionSpeed;
        }
        double next = Math.min(cursor + step, last);
        int from = (int) Math.floor(cursor);
        int to = (int) Math.floor(next);
        cursor = next;
        pose(cursor);
        for (int f = from + 1; f <= to; f++) {
            play(clip.frames().get(f), f);
        }
        if (cursor >= last) {
            reachEnd();
        }
    }

    private void reachEnd() {
        ended = true;
        endHold = plugin.settings().endHoldTicks;
        freezeActors();
        if (clip.killer() == null) {
            return;
        }
        ActorInfo killer = clip.actor(clip.killer());
        Map<String, String> values = Map.of(
                "killer", killer == null ? "?" : killer.plainName(),
                "health", hearts(clip.killerHealth()),
                "your_hits", String.valueOf(clip.victimHits()),
                "their_hits", String.valueOf(clip.killerHits()));
        viewer.showTitle(Title.title(plugin.text().get("end-title", values), plugin.text().get("end-subtitle", values),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(endHold * 50L), Duration.ofMillis(500))));
    }

    /** Puts every actor and projectile where it was at {@code at} (a fractional frame index). */
    private void pose(double at) {
        List<TickRecord> frames = clip.frames();
        int a = (int) Math.floor(at);
        int b = Math.min(a + 1, clip.lastIndex());
        double t = at - a;
        TickRecord fa = frames.get(a);
        TickRecord fb = frames.get(b);

        Set<UUID> present = new HashSet<>();
        for (PlayerFrame pa : fa.players()) {
            ActorInfo info = clip.actor(pa.id());
            if (info == null) {
                continue;
            }
            present.add(pa.id());
            Actor actor = actors.get(pa.id());
            if (actor == null) {
                actor = spawnActor(info, pa);
                if (actor == null) {
                    continue;
                }
            }
            if (actor.deadAt < 0) {
                actor.apply(world, pa, t > 0 ? fb.player(pa.id()) : null, t);
            }
        }
        Iterator<Actor> it = actors.values().iterator();
        while (it.hasNext()) {
            Actor actor = it.next();
            if (present.contains(actor.id) || actor.deadAt >= 0 && a - actor.deadAt <= CORPSE_FRAMES) {
                continue;
            }
            removeActor(actor);
            it.remove();
        }

        Set<Integer> flying = new HashSet<>();
        for (ProjectileFrame pa : fa.projectiles()) {
            flying.add(pa.id());
            ProjectileView view = projectiles.get(pa.id());
            if (view == null) {
                view = spawnProjectile(pa);
                projectiles.put(pa.id(), view);
            }
            view.apply(world, pa, t > 0 ? fb.projectile(pa.id()) : null, t);
        }
        Iterator<Map.Entry<Integer, ProjectileView>> pit = projectiles.entrySet().iterator();
        while (pit.hasNext()) {
            Map.Entry<Integer, ProjectileView> entry = pit.next();
            if (!flying.contains(entry.getKey())) {
                for (ItemDisplay display : entry.getValue().displays) {
                    manager.release(display);
                }
                pit.remove();
            }
        }
    }

    /** Jumps to a frame: everything is rebuilt there, without replaying the sounds in between. */
    void seek(double target) {
        UUID keepPov = pov;
        manager.internal(viewer, () -> {
            if (viewer.getSpectatorTarget() != null) {
                viewer.setSpectatorTarget(null);
            }
        });
        pov = null;
        clearEntities();
        cursor = Math.max(0, Math.min(target, clip.lastIndex()));
        ended = cursor >= clip.lastIndex();
        if (ended) {
            endHold = plugin.settings().endHoldTicks;
        } else {
            viewer.clearTitle();
        }
        blocks.sendAt(viewer, (int) Math.floor(cursor));
        pose(cursor);
        if (ended) {
            freezeActors();
        }
        if (keepPov != null) {
            // the client gets the respawned mannequin on the next tracker update; aim the camera after that
            pendingPov = keepPov;
            pendingPovDelay = 3;
        }
    }

    private void clearEntities() {
        for (Actor actor : actors.values()) {
            removeActor(actor);
        }
        actors.clear();
        for (ProjectileView view : projectiles.values()) {
            for (ItemDisplay display : view.displays) {
                manager.release(display);
            }
        }
        projectiles.clear();
        for (FloatingText text : texts) {
            manager.release(text.entity);
        }
        texts.clear();
    }

    /** Nothing moves while paused or on the last frame, so nobody keeps drinking either. */
    private void freezeActors() {
        for (Actor actor : actors.values()) {
            actor.freeze();
        }
    }

    private void removeActor(Actor actor) {
        if (actor.id.equals(pov)) {
            manager.internal(viewer, () -> {
                if (actor.entity.equals(viewer.getSpectatorTarget())) {
                    viewer.setSpectatorTarget(null);
                }
            });
            pov = null;
        }
        manager.release(actor.entity);
    }

    /** Loads the chunk at x/z (if needed) and keeps it loaded until the replay ends. */
    private void hold(double x, double z) {
        int cx = (int) Math.floor(x) >> 4;
        int cz = (int) Math.floor(z) >> 4;
        if (tickets.add(((long) cx << 32) | (cz & 0xFFFFFFFFL))) {
            world.addPluginChunkTicket(cx, cz, plugin);
        }
    }

    private Actor spawnActor(ActorInfo info, PlayerFrame frame) {
        Location at = new Location(world, frame.x(), frame.y(), frame.z(), frame.yaw(), frame.pitch());
        hold(frame.x(), frame.z());
        PlayerProfile profile = info.profile();
        Mannequin mannequin = world.spawn(at, Mannequin.class, entity -> {
            manager.prepare(entity);
            entity.setImmovable(true);
            entity.setCollidable(false);
            if (profile != null) {
                entity.setProfile(ResolvableProfile.resolvableProfile(profile));
            }
            if (info.skinParts() != null) {
                entity.setSkinParts(info.skinParts());
            }
            if (info.mainHand() != null) {
                entity.setMainHand(info.mainHand());
            }
            entity.setDescription(null);
            entity.customName(info.name());
            entity.setCustomNameVisible(true);
        });
        if (!mannequin.isValid()) {
            return null;
        }
        manager.adopt(mannequin);
        viewer.showEntity(plugin, mannequin);
        Actor actor = new Actor(info.id(), mannequin);
        actors.put(info.id(), actor);
        actor.apply(world, frame, null, 0);
        return actor;
    }

    private ProjectileView spawnProjectile(ProjectileFrame frame) {
        ProjectileKind kind = frame.kind();
        int count = kind.directional ? 2 : 1;
        ItemDisplay[] displays = new ItemDisplay[count];
        Location at = new Location(world, frame.x(), frame.y(), frame.z(), 0f, 0f);
        hold(frame.x(), frame.z());
        for (int i = 0; i < count; i++) {
            displays[i] = world.spawn(at, ItemDisplay.class, entity -> {
                manager.prepare(entity);
                entity.setItemStack(frame.item());
                entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                entity.setBillboard(kind.directional ? Display.Billboard.FIXED : Display.Billboard.CENTER);
                entity.setTeleportDuration(1);
                entity.setInterpolationDuration(1);
                entity.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(),
                        new org.joml.Quaternionf(), new org.joml.Vector3f(kind.scale), new org.joml.Quaternionf()));
            });
            manager.adopt(displays[i]);
            viewer.showEntity(plugin, displays[i]);
        }
        return new ProjectileView(displays, kind.directional, kind.scale);
    }

    // ------------------------------------------------------------------ events

    private void play(TickRecord record, int index) {
        for (ReplayEvent event : record.events()) {
            switch (event) {
                case ReplayEvent.Swing swing -> {
                    Actor actor = actors.get(swing.actor());
                    if (actor != null) {
                        if (swing.offHand()) {
                            actor.entity.swingOffHand();
                        } else {
                            actor.entity.swingMainHand();
                        }
                    }
                }
                case ReplayEvent.Hurt hurt -> hurt(hurt);
                case ReplayEvent.Death death -> {
                    Actor actor = actors.get(death.actor());
                    if (actor != null && actor.deadAt < 0) {
                        actor.deadAt = index;
                        actor.freeze();
                        actor.entity.playEffect(EntityEffect.ENTITY_DEATH);
                        sound(actor.entity.getLocation(), Sound.ENTITY_PLAYER_DEATH, 1f, 1f);
                    }
                }
                case ReplayEvent.Totem totem -> {
                    Actor actor = actors.get(totem.actor());
                    if (actor != null) {
                        actor.entity.playEffect(EntityEffect.PROTECTED_FROM_DEATH);
                    }
                }
                case ReplayEvent.Consume consume -> {
                    Actor actor = actors.get(consume.actor());
                    if (actor != null && !consume.drink()) {
                        sound(actor.entity.getLocation(), Sound.ENTITY_PLAYER_BURP, 0.5f, 1f);
                    }
                }
                case ReplayEvent.Launch launch -> {
                    if (plugin.settings().projectileSounds) {
                        sound(at(launch.x(), launch.y(), launch.z()), launch.kind().launchSound, 0.8f, 0.9f);
                    }
                }
                case ReplayEvent.Impact impact -> impact(impact);
                case ReplayEvent.Smash smash -> {
                    Location where = at(smash.x(), smash.y(), smash.z());
                    sound(where, smash.heavy() ? Sound.ITEM_MACE_SMASH_GROUND_HEAVY : Sound.ITEM_MACE_SMASH_GROUND, 1f, 1f);
                    if (smash.ground() != null) {
                        viewer.spawnParticle(Particle.DUST_PILLAR, where, 40, 0.8, 0.1, 0.8, 0.1, smash.ground());
                    }
                }
                case ReplayEvent.BlockChange change -> {
                    Location where = at(change.bx(), change.by(), change.bz());
                    viewer.sendBlockChange(where, change.after());
                    BlockData shown = change.placed() ? change.after() : change.before();
                    Sound sound = change.placed() ? shown.getSoundGroup().getPlaceSound() : shown.getSoundGroup().getBreakSound();
                    sound(where.clone().add(0.5, 0.5, 0.5), sound, 1f, 0.8f);
                    if (!change.placed()) {
                        viewer.spawnParticle(Particle.BLOCK, where.clone().add(0.5, 0.5, 0.5), 30, 0.25, 0.25, 0.25, 0, change.before());
                    }
                }
            }
        }
    }

    private void hurt(ReplayEvent.Hurt hurt) {
        Actor actor = actors.get(hurt.actor());
        if (actor == null) {
            return;
        }
        Location at = actor.entity.getLocation();
        if (hurt.blocked()) {
            sound(at, Sound.ITEM_SHIELD_BLOCK, 1f, 1f);
            return;
        }
        float from = 0f;
        if (!Double.isNaN(hurt.fromX())) {
            double towardYaw = Math.toDegrees(Math.atan2(-(hurt.fromX() - at.getX()), hurt.fromZ() - at.getZ()));
            from = (float) (((towardYaw - actor.entity.getBodyYaw()) % 360 + 360) % 360);
        }
        actor.entity.playHurtAnimation(from);
        sound(at, Sound.ENTITY_PLAYER_HURT, 1f, 1f);
        if (hurt.critical()) {
            sound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1f);
            viewer.spawnParticle(Particle.CRIT, at.clone().add(0, 1.1, 0), 14, 0.35, 0.45, 0.35, 0.25);
        } else if (hurt.melee()) {
            sound(at, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1f, 1f);
        }
        if (plugin.settings().damageNumbers && hurt.damage() > 0) {
            damageNumber(at, hurt.damage(), hurt.critical());
        }
    }

    /** What a landing projectile looks like; its sound only with playback.projectile-sounds on. */
    private void impact(ReplayEvent.Impact impact) {
        Location where = at(impact.x(), impact.y(), impact.z());
        boolean audible = plugin.settings().projectileSounds;
        switch (impact.kind()) {
            case WIND_CHARGE -> {
                viewer.spawnParticle(Particle.GUST_EMITTER_SMALL, where, 1);
                if (audible) {
                    sound(where, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1f, 1f);
                }
            }
            case POTION -> {
                if (audible) {
                    potionSplash(where, impact.color());
                } else {
                    Color c = impact.color() == null ? Color.fromRGB(0x385DC6) : impact.color();
                    if (Particle.ENTITY_EFFECT.getDataType() == Color.class) {
                        viewer.spawnParticle(Particle.ENTITY_EFFECT, where, 40, 0.6, 0.4, 0.6, 1, c);
                    }
                }
            }
            case PEARL -> {
                viewer.spawnParticle(Particle.PORTAL, where, 32, 0.3, 0.5, 0.3, 0.6);
                if (audible) {
                    sound(where, Sound.ENTITY_PLAYER_TELEPORT, 0.8f, 1f);
                }
            }
            case ARROW -> {
                if (audible) {
                    sound(where, impact.hitPlayer() ? Sound.ENTITY_ARROW_HIT_PLAYER : Sound.ENTITY_ARROW_HIT, 0.8f, 1f);
                }
            }
            case TRIDENT -> {
                if (audible) {
                    sound(where, Sound.ITEM_TRIDENT_HIT, 1f, 1f);
                }
            }
            case THROWN -> viewer.spawnParticle(Particle.POOF, where, 4, 0.1, 0.1, 0.1, 0.02);
        }
    }

    /** The vanilla splash (particles and sound in one), coloured like the potion. */
    private void potionSplash(Location where, Color color) {
        Color c = color == null ? Color.fromRGB(0x385DC6) : color;
        try {
            Class<?> data = Effect.POTION_BREAK.getData();
            if (data == Color.class) {
                viewer.playEffect(where, Effect.POTION_BREAK, c);
            } else {
                viewer.playEffect(where, Effect.POTION_BREAK, c.asRGB());
            }
        } catch (RuntimeException e) {
            sound(where, Sound.ENTITY_SPLASH_POTION_BREAK, 1f, 1f);
        }
    }

    private void damageNumber(Location at, float damage, boolean critical) {
        double jitter = (Math.random() - 0.5) * 0.6;
        Location spawn = at.clone().add(jitter, 2.2, (Math.random() - 0.5) * 0.6);
        hold(spawn.getX(), spawn.getZ());
        String amount = "-" + hearts(damage) + "❤";
        Component text = critical
                ? Component.text("✦ " + amount, NamedTextColor.GOLD)
                : Component.text(amount, NamedTextColor.RED);
        TextDisplay display = world.spawn(spawn, TextDisplay.class, entity -> {
            manager.prepare(entity);
            entity.text(text);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            entity.setShadowed(true);
            entity.setTeleportDuration(TEXT_LIFE - 4);
        });
        manager.adopt(display);
        viewer.showEntity(plugin, display);
        texts.add(new FloatingText(display));
    }

    private void tickTexts() {
        Iterator<FloatingText> it = texts.iterator();
        while (it.hasNext()) {
            FloatingText text = it.next();
            text.age++;
            if (text.age == 2) {
                // one long teleport the client glides through: the number rises and fades out of sight
                text.entity.teleport(text.entity.getLocation().add(0, 0.8, 0));
            } else if (text.age > TEXT_LIFE) {
                manager.release(text.entity);
                it.remove();
            }
        }
    }

    // ------------------------------------------------------------------ controls

    public void togglePause() {
        paused = !paused;
        if (paused) {
            freezeActors();
        }
        click();
    }

    public void skip(double seconds) {
        seek(cursor + seconds * 20);
        paused = false;
        click();
    }

    public void restart() {
        seek(0);
        paused = false;
        click();
    }

    public void cycleSpeed() {
        int index = 0;
        for (int i = 0; i < SPEEDS.length; i++) {
            if (SPEEDS[i] == speed) {
                index = i;
            }
        }
        speed = SPEEDS[(index + 1) % SPEEDS.length];
        click();
    }

    /** Puts the camera in the killer's (or the victim's) head. False when they are not on screen now. */
    public boolean lookThrough(boolean killer) {
        UUID id = killer ? clip.killer() : clip.victim();
        Actor actor = id == null ? null : actors.get(id);
        if (actor == null) {
            return false;
        }
        look(actor);
        click();
        return true;
    }

    public void freeCamera() {
        manager.internal(viewer, () -> viewer.setSpectatorTarget(null));
        pov = null;
        click();
    }

    private void look(Actor actor) {
        manager.internal(viewer, () -> viewer.setSpectatorTarget(actor.entity));
        pov = actor.id;
    }

    /** The viewer left-clicked a mannequin (vanilla spectator behaviour) - follow it. */
    void clickedInto(Entity entity) {
        for (Actor actor : actors.values()) {
            if (actor.entity.equals(entity)) {
                pov = actor.id;
                return;
            }
        }
    }

    void leftPov() {
        pov = null;
    }

    /**
     * Killcam: a sneak while inside someone's eyes skips it (vanilla would only drop the camera).
     * Shift held since before the killcam started has to be let go first; letting it go also puts
     * the camera in the killer's head if it was waiting for that. In the free camera sneaking just
     * flies down, as usual.
     */
    void sneaked(boolean sneaking) {
        if (!killcam || !plugin.settings().killcamSneakSkips) {
            return;
        }
        if (sneaking) {
            if (awaitingRelease || age <= SKIP_GRACE) {
                awaitingRelease = true;
            } else if (pov != null || pendingPov != null) {
                skipKillcam();
            }
            return;
        }
        if (awaitingRelease) {
            awaitingRelease = false;
            if (pov == null && !ended && plugin.settings().killcamKillerPov && clip.killer() != null) {
                pendingPov = clip.killer();
                pendingPovDelay = 1;
            }
        }
    }

    /** Ends the killcam right away. */
    public void skipKillcam() {
        viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.5f, 1.2f);
        manager.end(this, true);
    }

    boolean isActor(Entity entity) {
        for (Actor actor : actors.values()) {
            if (actor.entity.equals(entity)) {
                return true;
            }
        }
        return false;
    }

    boolean isNear(Location location) {
        if (location == null || !world.equals(location.getWorld())) {
            return false;
        }
        double max = plugin.settings().maxDistance * 2;
        return location.distanceSquared(center) <= max * max;
    }

    // ------------------------------------------------------------------ helpers

    /** Keeps the free camera near the fight. */
    private void leash() {
        if (pov != null) {
            return;
        }
        Location at = viewer.getLocation();
        double max = plugin.settings().maxDistance;
        if (!world.equals(at.getWorld())) {
            return;
        }
        Vector offset = at.toVector().subtract(center.toVector());
        if (offset.lengthSquared() <= max * max) {
            return;
        }
        Location back = center.clone().add(offset.normalize().multiply(max - 2));
        back.setYaw(at.getYaw());
        back.setPitch(at.getPitch());
        manager.internal(viewer, () -> viewer.teleport(back));
    }

    private void updateBar() {
        int last = clip.lastIndex();
        bar.progress((float) Math.max(0, Math.min(1, cursor / Math.max(1, last))));
        String state = ended ? "bar-ended" : paused ? "bar-paused" : killcamBar();
        String time = clock(cursor);
        String speedText = speedLabel();
        String closing = String.valueOf(Math.max(0, (endHold + 19) / 20));
        String key = state + time + speedText + (ended ? closing : "");
        if (key.equals(barKey)) {
            return;
        }
        barKey = key;
        bar.color(ended ? BossBar.Color.WHITE : paused ? BossBar.Color.YELLOW : BossBar.Color.RED);
        bar.name(plugin.text().get(state, Map.of(
                "time", time,
                "total", clock(last),
                "speed", speedText,
                "seconds", closing,
                "killer", killerName())));
    }

    /** The killcam bar while the camera is in (or on its way into) someone's eyes; the usual one otherwise. */
    private String killcamBar() {
        if (!killcam || (pov == null && pendingPov == null)) {
            return "bar-playing";
        }
        return plugin.settings().killcamSneakSkips ? "bar-killcam" : "bar-killcam-no-sneak";
    }

    private String killerName() {
        ActorInfo killer = clip.actor(clip.killer());
        return killer == null ? "?" : killer.plainName();
    }

    private String speedLabel() {
        if (speed == 0.25) {
            return "0.25x";
        }
        if (speed == 0.5) {
            return "0.5x";
        }
        return (int) speed + "x";
    }

    private Component controlsLine() {
        return plugin.text().prefix()
                .append(control("&e[⏯ Pause]", "pause", "Pause or play"))
                .append(Component.space())
                .append(control("&f[⏪ 3s]", "back", "Back 3 seconds"))
                .append(Component.space())
                .append(control("&f[⏩ 3s]", "forward", "Forward 3 seconds"))
                .append(Component.space())
                .append(control("&f[⟲]", "restart", "Restart"))
                .append(Component.space())
                .append(control("&b[⏱ Speed]", "speed", "0.25x → 0.5x → 1x → 2x"))
                .append(Component.newline())
                .append(plugin.text().prefix())
                .append(control("&c[☠ Killer's eyes]", "killer", "See it as your killer saw it"))
                .append(Component.space())
                .append(control("&a[◉ Your eyes]", "you", "See it as you saw it"))
                .append(Component.space())
                .append(control("&f[✈ Free cam]", "free", "Fly around freely"))
                .append(Component.space())
                .append(control("&c[✖ Exit]", "exit", "Leave the replay"));
    }

    private static Component control(String label, String action, String hover) {
        return dev.skilledseeker.lastframe.Text.parse(label)
                .hoverEvent(Component.text(hover))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/replay " + action));
    }

    private void click() {
        viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.4f, 1.6f);
        barKey = "";
    }

    private void sound(Location where, Sound sound, float volume, float pitch) {
        viewer.playSound(where, sound, SoundCategory.PLAYERS, volume, pitch);
    }

    private Location at(double x, double y, double z) {
        return new Location(world, x, y, z);
    }

    private static String clock(double frames) {
        int seconds = (int) Math.floor(frames / 20);
        return seconds / 60 + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
    }

    /** Half-points of damage as hearts: 7 -> "3.5", 4 -> "2". */
    static String hearts(float points) {
        double hearts = Math.round(points) / 2.0;
        return hearts == Math.floor(hearts) ? String.valueOf((int) hearts) : String.valueOf(hearts);
    }
}
