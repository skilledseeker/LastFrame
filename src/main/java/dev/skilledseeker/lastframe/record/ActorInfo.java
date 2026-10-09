/*
 * LastFrame - death replays for Paper. Watch the fight that killed you, from any angle.
 * Made by skilledseeker (skilledmc on Discord).
 */

package dev.skilledseeker.lastframe.record;

import com.destroystokyo.paper.ClientOption;
import com.destroystokyo.paper.SkinParts;
import com.destroystokyo.paper.profile.PlayerProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.MainHand;

import java.util.UUID;

/** How a player looks in a replay: skin, the name over their head, and which hand is their main one. */
public record ActorInfo(UUID id, Component name, String plainName, PlayerProfile profile,
                        SkinParts skinParts, MainHand mainHand) {

    public static ActorInfo of(Player player) {
        SkinParts parts = null;
        try {
            parts = player.getClientOption(ClientOption.SKIN_PARTS);
        } catch (RuntimeException ignored) {
            // the client has not sent its options yet: the mannequin keeps its default layers
        }
        // displayName() carries a nickname plugin's /nick, so a nicked player is not unmasked by a replay.
        Component name = player.displayName();
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(name);
        return new ActorInfo(player.getUniqueId(), name, plain.isBlank() ? player.getName() : plain,
                player.getPlayerProfile(), parts, player.getMainHand());
    }
}
