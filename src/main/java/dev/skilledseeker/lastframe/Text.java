/*
 * LastFrame - death replays for Paper. Watch the fight that killed you, from any angle.
 * Made by skilledseeker (skilledmc on Discord).
 */

package dev.skilledseeker.lastframe;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;

import java.util.Map;
import java.util.regex.Pattern;

/** Turns config strings (& codes, &#rrggbb) into components, with {placeholders}. */
public final class Text {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();
    private static final Pattern KEY = Pattern.compile("\\{key}");

    private final LastFrame plugin;

    Text(LastFrame plugin) {
        this.plugin = plugin;
    }

    public static Component parse(String raw) {
        return LEGACY.deserialize(raw == null ? "" : raw).decoration(TextDecoration.ITALIC, false);
    }

    /** A config message with text placeholders filled in and {key} shown as the player's Quick Actions key. */
    public Component get(String key, Map<String, String> placeholders) {
        return format(plugin.settings().message(key), placeholders);
    }

    public Component get(String key) {
        return get(key, Map.of());
    }

    public Component format(String raw, Map<String, String> placeholders) {
        String filled = raw;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            filled = filled.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        Component out = parse(filled);
        if (filled.contains("{key}")) {
            out = out.replaceText(TextReplacementConfig.builder().match(KEY).replacement(controlsKey()).build());
        }
        return out;
    }

    /** The key that opens the controls: the client's own Quick Actions binding, or /replay without it. */
    public Component controlsKey() {
        if (plugin.quickActionsReady()) {
            return Component.text("[").append(Component.keybind("key.quickActions")).append(Component.text("]"));
        }
        return Component.text("/replay");
    }

    /** Sends a config message with the prefix; empty messages are not sent. */
    public void send(CommandSender to, String key, Map<String, String> placeholders) {
        String raw = plugin.settings().message(key);
        if (raw.isEmpty()) {
            return;
        }
        to.sendMessage(parse(plugin.settings().message("prefix")).append(format(raw, placeholders)));
    }

    public void send(CommandSender to, String key) {
        send(to, key, Map.of());
    }

    public Component prefix() {
        return parse(plugin.settings().message("prefix"));
    }
}
