/*
 * LastFrame - death replays for Paper. Watch the fight that killed you, from any angle.
 * Made by skilledseeker (skilledmc on Discord).
 */

package dev.skilledseeker.lastframe;

import dev.skilledseeker.lastframe.playback.ReplaySession;
import dev.skilledseeker.lastframe.playback.SelfTest;
import dev.skilledseeker.lastframe.record.Clip;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * /replay and the controls dialog. Both end up in {@link #run}: the dialog's buttons arrive as
 * custom clicks named {@code lastframe:<action>}, the chat buttons as {@code /replay <action>}.
 */
final class ReplayCommand implements BasicCommand, Listener {

    private static final List<String> PLAYER_ACTIONS = List.of(
            "watch", "controls", "pause", "back", "forward", "restart", "speed", "killer", "you", "free", "skip", "exit", "toggle");
    private static final List<String> ADMIN_ACTIONS = List.of("reload", "selftest");
    private static final List<String> INFO_ACTIONS = List.of("about", "version", "credits");

    private final LastFrame plugin;

    ReplayCommand(LastFrame plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "reload" -> {
                if (sender.hasPermission("lastframe.admin")) {
                    plugin.reload();
                    plugin.text().send(sender, "reloaded");
                }
                return;
            }
            case "selftest" -> {
                if (sender.hasPermission("lastframe.admin")) {
                    SelfTest.run(plugin, sender);
                }
                return;
            }
            case "about", "version", "credits" -> {
                sendAbout(sender);
                return;
            }
            default -> {
            }
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Usage: /replay <about|reload|selftest>");
            return;
        }
        run(player, action.isEmpty() ? "menu" : action);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length > 1) {
            return List.of();
        }
        String typed = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String action : PLAYER_ACTIONS) {
            if (action.startsWith(typed)) {
                out.add(action);
            }
        }
        for (String action : INFO_ACTIONS) {
            if (action.startsWith(typed)) {
                out.add(action);
            }
        }
        if (source.getSender().hasPermission("lastframe.admin")) {
            for (String action : ADMIN_ACTIONS) {
                if (action.startsWith(typed)) {
                    out.add(action);
                }
            }
        }
        return out;
    }

    void run(Player player, String action) {
        ReplaySession session = plugin.sessions().session(player);
        Text text = plugin.text();
        switch (action) {
            case "menu" -> {
                if (session != null || !plugin.offers().hasOpenOffer(player)) {
                    player.showDialog(ControlsDialog.create());
                } else {
                    watch(player);
                }
            }
            case "controls" -> player.showDialog(ControlsDialog.create());
            case "watch" -> {
                if (session != null) {
                    session.restart();
                } else {
                    watch(player);
                }
            }
            case "toggle" -> text.send(player, plugin.offers().toggleOffers(player) ? "toggled-on" : "toggled-off");
            case "pause", "play", "back", "forward", "restart", "speed", "killer", "you", "free", "skip", "exit" -> {
                if (session == null) {
                    text.send(player, "not-watching");
                    return;
                }
                switch (action) {
                    case "pause", "play" -> session.togglePause();
                    case "back" -> session.skip(-3);
                    case "forward" -> session.skip(3);
                    case "restart" -> session.restart();
                    case "speed" -> session.cycleSpeed();
                    case "killer", "you" -> {
                        if (!session.lookThrough(action.equals("killer"))) {
                            text.send(player, "pov-missing");
                        }
                    }
                    case "free" -> session.freeCamera();
                    default -> plugin.sessions().end(session, true);
                }
            }
            default -> player.sendMessage(Text.parse("&7/replay &f" + String.join("&7, &f", PLAYER_ACTIONS) + "&7, &fabout"));
        }
    }

    /** Name, version and who made it. */
    private void sendAbout(CommandSender sender) {
        sender.sendMessage(Text.parse("&c&l" + LastFrame.NAME + " &7v" + plugin.getPluginMeta().getVersion()
                + " &8• &7made by &f" + LastFrame.AUTHOR + " &8(&f" + LastFrame.DISCORD + " &7on Discord&8)"));
    }

    private void watch(Player player) {
        Text text = plugin.text();
        if (!plugin.offers().hasOpenOffer(player)) {
            text.send(player, "no-replay");
            return;
        }
        if (!plugin.compat().canWatch(player)) {
            text.send(player, "unsupported-client");
            return;
        }
        if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR || player.isInsideVehicle()
                || player.isSleeping()) {
            text.send(player, "not-now");
            return;
        }
        Clip clip = plugin.offers().take(player);
        if (clip == null || !plugin.sessions().start(player, clip)) {
            text.send(player, "not-now");
        }
    }

    /** The controls dialog (Quick Actions key or /replay controls). */
    @EventHandler
    public void onCustomClick(PlayerCustomClickEvent event) {
        Key id = event.getIdentifier();
        if (!ControlsDialog.NAMESPACE.equals(id.namespace())
                || !(event.getCommonConnection() instanceof PlayerGameConnection connection)) {
            return;
        }
        Player player = connection.getPlayer();
        String action = id.value();
        if (!PLAYER_ACTIONS.contains(action)) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            run(player, action);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    run(player, action);
                }
            });
        }
    }
}
