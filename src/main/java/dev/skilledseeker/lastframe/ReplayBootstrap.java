/*
 * LastFrame - death replays for Paper. Watch the fight that killed you, from any angle.
 * Made by skilledseeker (skilledmc on Discord).
 */

package dev.skilledseeker.lastframe;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.event.RegistryEvents;
import org.bukkit.configuration.file.YamlConfiguration;

import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs before the server loads its registries, which is the only time a dialog can be added to
 * them. It registers the controls dialog and a tiny built-in datapack that puts it in the vanilla
 * {@code #minecraft:quick_actions} tag, so the Quick Actions key (G by default) opens it.
 *
 * <p>The tag entry is optional ({@code "required": false}), so if the dialog is ever missing the
 * datapack still loads and the server starts normally - the key just does nothing.
 */
public final class ReplayBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(BootstrapContext context) {
        if (!quickActionsWanted(context.getDataDirectory())) {
            context.getLogger().info("quick-actions-key is off: the controls stay on /replay only.");
            return;
        }
        try {
            context.getLifecycleManager().registerEventHandler(RegistryEvents.DIALOG.compose().newHandler(event ->
                    event.registry().register(TypedKey.create(RegistryKey.DIALOG, ControlsDialog.KEY), ControlsDialog::fill)));
            context.getLifecycleManager().registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY.newHandler(event -> {
                try {
                    URL pack = ReplayBootstrap.class.getResource("/quick_actions_pack");
                    if (pack == null) {
                        context.getLogger().warn("Built-in quick actions datapack is missing from the jar.");
                        return;
                    }
                    URI uri = pack.toURI();
                    event.registrar().discoverPack(uri, "quick_actions");
                } catch (Exception e) {
                    context.getLogger().warn("Could not add the quick actions datapack; G will not open the controls.", e);
                }
            }));
        } catch (RuntimeException | LinkageError e) {
            context.getLogger().warn("Could not set up the Quick Actions key; the controls stay on /replay only.", e);
        }
    }

    /** config.yml is not loaded yet at bootstrap, so read the one switch directly. */
    private static boolean quickActionsWanted(Path dataDirectory) {
        Path file = dataDirectory.resolve("config.yml");
        if (!Files.isRegularFile(file)) {
            return true;
        }
        try {
            return YamlConfiguration.loadConfiguration(file.toFile()).getBoolean("quick-actions-key", true);
        } catch (RuntimeException | LinkageError e) {
            return true;
        }
    }
}
