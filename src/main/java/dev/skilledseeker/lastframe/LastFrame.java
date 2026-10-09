/*
 * LastFrame - death replays for Paper. Watch the fight that killed you, from any angle.
 * Made by skilledseeker (skilledmc on Discord).
 */

package dev.skilledseeker.lastframe;

import dev.skilledseeker.lastframe.playback.SessionManager;
import dev.skilledseeker.lastframe.record.Recorder;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.keys.tags.DialogTagKeys;
import org.bukkit.Registry;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.logging.Level;

/**
 * Death replays. Every tick the {@link Recorder} notes where everyone in the recorded worlds is and what they
 * do; when a player is killed, the {@link OfferManager} cuts the fight out and offers it to them; if
 * they take it, the {@link SessionManager} plays it back around them with mannequins.
 */
public final class LastFrame extends JavaPlugin {

    /** Who made this. Compile-time constants, so other classes can show them without loading the plugin class. */
    public static final String NAME = "LastFrame";
    public static final String AUTHOR = "skilledseeker";
    public static final String DISCORD = "skilledmc";
    public static final String CREDIT = NAME + " by " + AUTHOR + " (" + DISCORD + " on Discord)";

    private Settings settings;
    private Text text;
    private Compat compat;
    private SessionManager sessions;
    private Recorder recorder;
    private OfferManager offers;
    private boolean quickActionsReady;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.load(getConfig());
        text = new Text(this);
        compat = new Compat(this);
        sessions = new SessionManager(this);
        recorder = new Recorder(this);
        offers = new OfferManager(this);
        ReplayCommand command = new ReplayCommand(this);

        getServer().getPluginManager().registerEvents(recorder, this);
        getServer().getPluginManager().registerEvents(offers, this);
        getServer().getPluginManager().registerEvents(sessions, this);
        getServer().getPluginManager().registerEvents(command, this);
        registerCommand("replay", "Watch the replay of your last death.", List.of("deathreplay", "lastframe"), command);

        quickActionsReady = checkQuickActions();
        sessions.sweepStrays();
        getServer().getScheduler().runTaskTimer(this, () -> {
            sessions.tick();
            recorder.record();
            offers.tick();
        }, 1L, 1L);
        getLogger().info(CREDIT + " - v" + getPluginMeta().getVersion() + " enabled.");
        getLogger().info("Recording " + (settings.enabled ? "on" : "off") + ", " + settings.clipTicks / 20
                + "s replays, " + (settings.killcam ? "killcam" : "offer") + " mode. Quick Actions key: " + (quickActionsReady ? "ready" : "not set up (controls on /replay)") + ".");
    }

    @Override
    public void onDisable() {
        if (sessions != null) {
            sessions.endAll();
        }
        if (offers != null) {
            offers.clear();
        }
    }

    void reload() {
        reloadConfig();
        settings = Settings.load(getConfig());
        recorder.resize();
    }

    /** True when the controls dialog is registered and in the vanilla quick actions tag. */
    private boolean checkQuickActions() {
        if (!getConfig().getBoolean("quick-actions-key", true)) {
            return false;
        }
        try {
            Registry<Dialog> dialogs = RegistryAccess.registryAccess().getRegistry(RegistryKey.DIALOG);
            if (dialogs.get(ControlsDialog.KEY) == null) {
                getLogger().warning("The controls dialog is not registered (was the plugin loaded without a restart?).");
                return false;
            }
            boolean tagged = dialogs.getTag(DialogTagKeys.QUICK_ACTIONS)
                    .contains(TypedKey.create(RegistryKey.DIALOG, ControlsDialog.KEY));
            if (!tagged) {
                getLogger().warning("The controls dialog is not in #minecraft:quick_actions; the datapack did not load.");
            }
            return tagged;
        } catch (RuntimeException e) {
            getLogger().log(Level.WARNING, "Could not check the Quick Actions key", e);
            return false;
        }
    }

    public Settings settings() {
        return settings;
    }

    public Text text() {
        return text;
    }

    Compat compat() {
        return compat;
    }

    public SessionManager sessions() {
        return sessions;
    }

    public Recorder recorder() {
        return recorder;
    }

    OfferManager offers() {
        return offers;
    }

    public boolean quickActionsReady() {
        return quickActionsReady;
    }
}
