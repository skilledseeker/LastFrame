/*
 * LastFrame - death replays for Paper. Watch the fight that killed you, from any angle.
 * Made by skilledseeker (skilledmc on Discord).
 */

package dev.skilledseeker.lastframe.playback;

import dev.skilledseeker.lastframe.record.Clip;
import dev.skilledseeker.lastframe.record.ReplayEvent;
import dev.skilledseeker.lastframe.record.TickRecord;
import io.papermc.paper.math.Position;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Blocks placed or broken during the clip (cobwebs, mostly). The viewer is sent fake block changes
 * so the world looks as it did at the replay's current moment, and the real blocks again at the end.
 */
final class BlockTimeline {

    private record Pos(int x, int y, int z) {
        Position position() {
            return Position.block(x, y, z);
        }
    }

    private record Change(int frame, Pos pos, BlockData after) {
    }

    private final Map<Pos, BlockData> initial = new LinkedHashMap<>();
    private final List<Change> changes = new ArrayList<>();

    BlockTimeline(Clip clip) {
        List<TickRecord> frames = clip.frames();
        for (int i = 0; i < frames.size(); i++) {
            for (ReplayEvent event : frames.get(i).events()) {
                if (event instanceof ReplayEvent.BlockChange change) {
                    Pos pos = new Pos(change.bx(), change.by(), change.bz());
                    initial.putIfAbsent(pos, change.before());
                    changes.add(new Change(i, pos, change.after()));
                }
            }
        }
    }

    /** Sends every changed block as it was at {@code frame}. */
    void sendAt(Player viewer, int frame) {
        if (initial.isEmpty()) {
            return;
        }
        Map<Pos, BlockData> state = new HashMap<>(initial);
        for (Change change : changes) {
            if (change.frame() > frame) {
                break;
            }
            state.put(change.pos(), change.after());
        }
        Map<Position, BlockData> packet = new HashMap<>();
        state.forEach((pos, data) -> packet.put(pos.position(), data));
        viewer.sendMultiBlockChange(packet);
    }

    /** Sends the real blocks back. */
    void restore(Player viewer, World world) {
        if (initial.isEmpty() || world == null) {
            return;
        }
        Map<Position, BlockData> packet = new HashMap<>();
        for (Pos pos : initial.keySet()) {
            packet.put(pos.position(), world.getBlockAt(pos.x(), pos.y(), pos.z()).getBlockData());
        }
        viewer.sendMultiBlockChange(packet);
    }
}
