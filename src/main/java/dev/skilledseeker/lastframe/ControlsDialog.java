/*
 * LastFrame - death replays for Paper. Watch the fight that killed you, from any angle.
 * Made by skilledseeker (skilledmc on Discord).
 */

package dev.skilledseeker.lastframe;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;

import java.util.List;

/**
 * The replay controls. The same dialog is registered at startup (so the vanilla Quick Actions key,
 * G by default, opens it) and shown by /replay. It is static - the registry cannot hold one per
 * player - so every button sends a custom click ({@code lastframe:<action>}) that
 * {@link ReplayCommand#run} handles for whoever pressed it.
 */
public final class ControlsDialog {

    public static final String NAMESPACE = "lastframe";
    public static final Key KEY = Key.key(NAMESPACE, "controls");

    private static final int WIDE = 300;
    private static final int HALF = 148;

    private ControlsDialog() {
    }

    /** Fills the registry entry; called from the bootstrapper. */
    static void fill(DialogRegistryEntry.Builder builder) {
        builder.base(base()).type(type());
    }

    /** A runtime copy, for /replay controls. */
    static Dialog create() {
        return Dialog.create(builder -> builder.empty().base(base()).type(type()));
    }

    private static DialogBase base() {
        return DialogBase.builder(Text.parse("&c&lDeath Replay"))
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(List.of(
                        DialogBody.plainMessage(Text.parse(
                                "&7Fly anywhere while it plays. &fLeft-click&7 a player to see through their eyes;"
                                        + " &fsneak&7 skips a killcam or lets go of the eyes."), WIDE),
                        DialogBody.plainMessage(Text.parse("&8" + LastFrame.CREDIT), WIDE)))
                .build();
    }

    private static DialogType type() {
        List<ActionButton> buttons = List.of(
                button("&c⏭ Skip replay", "Leave the replay right away.", "skip", WIDE),
                button("&a▶ Watch my last death", "Starts it over while one is playing; otherwise only while the offer after a death is open.", "watch", WIDE),
                button("&e⏯ Pause / Play", null, "pause", HALF),
                button("&f⟲ Restart", null, "restart", HALF),
                button("&f⏪ Back 3s", null, "back", HALF),
                button("&f⏩ Forward 3s", null, "forward", HALF),
                button("&b⏱ Speed", "0.25x → 0.5x → 1x → 2x", "speed", HALF),
                button("&f✈ Free camera", "Fly around freely.", "free", HALF),
                button("&c☠ Killer's eyes", "See the fight as your killer saw it.", "killer", HALF),
                button("&a◉ Your eyes", "See the fight as you saw it.", "you", HALF));
        return DialogType.multiAction(buttons).columns(2).build();
    }

    private static ActionButton button(String label, String tooltip, String action, int width) {
        ActionButton.Builder builder = ActionButton.builder(Text.parse(label))
                .width(width)
                .action(DialogAction.customClick(Key.key(NAMESPACE, action), null));
        if (tooltip != null) {
            builder.tooltip(Component.text(tooltip));
        }
        return builder.build();
    }
}
