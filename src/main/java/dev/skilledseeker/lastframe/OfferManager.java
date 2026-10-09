/*
 * LastFrame - death replays for Paper. Watch the fight that killed you, from any angle.
 * Made by skilledseeker (skilledmc on Discord).
 */

package dev.skilledseeker.lastframe;

import dev.skilledseeker.lastframe.record.ActorInfo;
import dev.skilledseeker.lastframe.record.Clip;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What happens to a clip after the death. With the killcam on it simply plays as soon as the player
 * is back in the world (respawned by the plugin itself, if auto-respawn is on). Otherwise there is a
 * window in which the replay can be watched: the offer (chat button, boss bar countdown) opens once
 * the player respawns, and when it runs out, the clip is thrown away. Getting into a fight in the
 * meantime throws it away too, so a replay can never be used to slip out of combat.
 */
public final class OfferManager implements Listener {

    private static final class Offer {
        final Clip clip;
        final long createdTick;
        boolean shown;
        /** Chosen from the death screen menu (or a killcam): start the replay as soon as the respawn has settled. */
        boolean watchOnRespawn;
        /** BO3-style: it plays by itself, from the killer's eyes, sneak to skip. */
        boolean killcam;
        int remaining;
        BossBar bar;

        Offer(Clip clip, long createdTick) {
            this.clip = clip;
            this.createdTick = createdTick;
        }
    }

    private final LastFrame plugin;
    private final NamespacedKey offersOff;
    private final Map<UUID, Offer> offers = new HashMap<>();
    private final Set<UUID> duelDeaths = new HashSet<>();
    private long tick;

    OfferManager(LastFrame plugin) {
        this.plugin = plugin;
        this.offersOff = new NamespacedKey(plugin, "offers_off");
    }

    /** Asked before the duel plugin's own death handling can end the duel. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeathEarly(PlayerDeathEvent event) {
        if (plugin.compat().inDuel(event.getPlayer())) {
            duelDeaths.add(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        boolean duel = duelDeaths.remove(victim.getUniqueId());
        drop(victim);
        plugin.recorder().noteDeath(victim);

        Settings settings = plugin.settings();
        if (!settings.enabled || duel && !settings.includeDuels) {
            return;
        }
        Player killer = victim.getKiller();
        if (settings.onlyPlayerKills && (killer == null || killer.equals(victim))) {
            return;
        }
        if (offersDisabled(victim) || !plugin.compat().canWatch(victim)) {
            return;
        }
        Clip clip = plugin.recorder().cut(victim, killer);
        if (clip == null || clip.frames().size() < 2) {
            return;
        }
        Offer offer = new Offer(clip, tick);
        offers.put(victim.getUniqueId(), offer);
        if (settings.killcam) {
            // no offer: the replay starts by itself as soon as they are back in the world
            offer.killcam = true;
            offer.watchOnRespawn = true;
            if (settings.killcamAutoRespawn) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> autoRespawn(victim, offer), settings.killcamRespawnDelayTicks);
            }
            return;
        }
        if (settings.deathScreenMenu) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> showDeathMenu(victim, offer), 1L);
        }
    }

    /** Killcam: leaves the death screen by itself so the replay can start. */
    private void autoRespawn(Player player, Offer offer) {
        if (player.isOnline() && player.isDead() && offers.get(player.getUniqueId()) == offer) {
            player.spigot().respawn();
        }
    }

    /**
     * Respawn / Watch Replay / Title Screen, over the death screen. A server cannot add a button to the
     * vanilla death screen, so this menu sits on top of it. Title Screen just closes the menu: the vanilla
     * screen underneath has the real button (a server cannot send a client to its title screen, and a
     * kick would only move the player to the proxy's next server).
     */
    private void showDeathMenu(Player player, Offer offer) {
        if (!player.isOnline() || !player.isDead() || offers.get(player.getUniqueId()) != offer) {
            return;
        }
        Text text = plugin.text();
        ActorInfo killer = offer.clip.actor(offer.clip.killer());
        Map<String, String> values = Map.of("killer", killer == null ? "?" : killer.plainName());
        ClickCallback.Options once = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build();
        List<ActionButton> buttons = List.of(
                ActionButton.builder(text.get("death-respawn", values)).width(200)
                        .action(DialogAction.customClick((view, audience) -> onMain(() -> respawn(player, offer, false)), once))
                        .build(),
                ActionButton.builder(text.get("death-watch", values)).width(200)
                        .action(DialogAction.customClick((view, audience) -> onMain(() -> respawn(player, offer, true)), once))
                        .build(),
                ActionButton.builder(text.get("death-title-screen", values)).width(200).build());
        player.showDialog(Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(text.get("death-title", values))
                        .canCloseWithEscape(false)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(List.of(DialogBody.plainMessage(text.get("death-cause", values), 200)))
                        .build())
                .type(DialogType.multiAction(buttons).columns(1).build())));
    }

    /** A death menu button: respawn, and either watch the replay or let it go. */
    private void respawn(Player player, Offer offer, boolean watch) {
        if (!player.isOnline()) {
            return;
        }
        boolean current = offers.get(player.getUniqueId()) == offer;
        if (current && watch) {
            offer.watchOnRespawn = true;
        } else if (current) {
            drop(player); // they picked Respawn: no chat offer afterwards
        }
        if (player.isDead()) {
            player.spigot().respawn();
        } else if (current && watch) {
            watchNow(player, offer); // something else respawned them while the menu was open
        }
        if (watch && !current) {
            plugin.text().send(player, "no-replay");
        }
    }

    private void watchNow(Player player, Offer offer) {
        if (!player.isOnline() || offers.get(player.getUniqueId()) != offer) {
            return;
        }
        offers.remove(player.getUniqueId());
        hideBar(player.getUniqueId(), offer);
        if (player.isDead() || !plugin.sessions().start(player, offer.clip, offer.killcam)) {
            plugin.text().send(player, "not-now");
        }
    }

    private void onMain(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Offer offer = offers.get(player.getUniqueId());
        if (offer != null && offer.watchOnRespawn) {
            // after Multiverse (1 tick) and the staff script (3 ticks) have set the spawn world's game mode
            Bukkit.getScheduler().runTaskLater(plugin, () -> watchNow(player, offer), 5L);
            return;
        }
        if (offer != null && !offer.shown) {
            // after the respawn has settled (and the core has handed out the respawn kit)
            Bukkit.getScheduler().runTaskLater(plugin, () -> show(player, offer), 2L);
        }
    }

    private void show(Player player, Offer offer) {
        if (!player.isOnline() || offers.get(player.getUniqueId()) != offer || offer.shown) {
            return;
        }
        offer.shown = true;
        offer.remaining = plugin.settings().offerTicks;
        Text text = plugin.text();
        ActorInfo killer = offer.clip.actor(offer.clip.killer());
        String seconds = String.valueOf((offer.clip.frames().size() + 19) / 20);
        Map<String, String> values = Map.of(
                "killer", killer == null ? "?" : killer.plainName(),
                "seconds", seconds);
        Component button = text.get("offer-button", values)
                .hoverEvent(text.get("offer-hover", values))
                .clickEvent(ClickEvent.runCommand("/replay watch"));
        Component line = text.prefix().append(text.get("offer", values)).append(button);
        if (plugin.quickActionsReady()) {
            line = line.append(text.get("offer-key-hint", values));
        }
        player.sendMessage(line);
        offer.bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
        updateBar(offer);
        player.showBossBar(offer.bar);
    }

    void tick() {
        tick++;
        if (offers.isEmpty()) {
            return;
        }
        Settings settings = plugin.settings();
        Iterator<Map.Entry<UUID, Offer>> it = offers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Offer> entry = it.next();
            Offer offer = entry.getValue();
            if (!offer.shown) {
                if (tick - offer.createdTick > settings.respawnWaitTicks) {
                    it.remove();
                }
                continue;
            }
            if (--offer.remaining <= 0) {
                it.remove();
                hideBar(entry.getKey(), offer);
                continue;
            }
            updateBar(offer);
        }
    }

    private void updateBar(Offer offer) {
        int total = Math.max(1, plugin.settings().offerTicks);
        offer.bar.progress(Math.max(0f, Math.min(1f, offer.remaining / (float) total)));
        if (offer.remaining % 20 == 0 || offer.remaining == total) {
            String key = plugin.quickActionsReady() ? "offer-bar" : "offer-bar-no-key";
            offer.bar.name(plugin.text().get(key, Map.of("seconds", String.valueOf((offer.remaining + 19) / 20))));
        }
    }

    /** Takes the open offer's clip, or null if there is none to watch right now. */
    Clip take(Player player) {
        Offer offer = offers.get(player.getUniqueId());
        if (offer == null || !offer.shown) {
            return null;
        }
        offers.remove(player.getUniqueId());
        hideBar(player.getUniqueId(), offer);
        return offer.clip;
    }

    boolean hasOpenOffer(Player player) {
        Offer offer = offers.get(player.getUniqueId());
        return offer != null && offer.shown;
    }

    private void drop(Player player) {
        Offer offer = offers.remove(player.getUniqueId());
        if (offer != null) {
            hideBar(player.getUniqueId(), offer);
        }
    }

    private void hideBar(UUID id, Offer offer) {
        Player player = Bukkit.getPlayer(id);
        if (player != null && offer.bar != null) {
            player.hideBossBar(offer.bar);
        }
    }

    void clear() {
        for (Map.Entry<UUID, Offer> entry : offers.entrySet()) {
            hideBar(entry.getKey(), entry.getValue());
        }
        offers.clear();
    }

    /** A fight closes the offer for both sides. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFight(EntityDamageByEntityEvent event) {
        if (offers.isEmpty() || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = null;
        if (event.getDamager() instanceof Player hitter) {
            attacker = hitter;
        } else if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            attacker = shooter;
        }
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        dropForFight(victim);
        dropForFight(attacker);
    }

    private void dropForFight(Player player) {
        if (hasOpenOffer(player)) {
            drop(player);
            plugin.text().send(player, "offer-dropped");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        drop(event.getPlayer());
        duelDeaths.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ /replay toggle

    boolean offersDisabled(Player player) {
        return player.getPersistentDataContainer().has(offersOff);
    }

    /** Flips the player's choice; returns true when offers are now on. */
    boolean toggleOffers(Player player) {
        if (offersDisabled(player)) {
            player.getPersistentDataContainer().remove(offersOff);
            return true;
        }
        player.getPersistentDataContainer().set(offersOff, PersistentDataType.BYTE, (byte) 1);
        drop(player);
        return false;
    }
}
