/*
 * LastFrame - death replays for Paper. Watch the fight that killed you, from any angle.
 * Made by skilledseeker (skilledmc on Discord).
 */

package dev.skilledseeker.lastframe.playback;

import dev.skilledseeker.lastframe.LastFrame;
import dev.skilledseeker.lastframe.record.Equipment;
import dev.skilledseeker.lastframe.record.PlayerFrame;
import dev.skilledseeker.lastframe.record.ProjectileFrame;
import dev.skilledseeker.lastframe.record.ProjectileKind;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import org.bukkit.Bukkit;
import org.bukkit.EntityEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * /replay selftest: drives every server call a replay makes - a mannequin with a skin, gear, poses,
 * swings, a hurt flash and a death, an arrow display and a damage number - on throwaway entities that
 * are removed right after, and reports which calls failed. Proves the API works on this server
 * without needing a real fight.
 */
public final class SelfTest {

    private SelfTest() {
    }

    public static void run(LastFrame plugin, CommandSender sender) {
        SessionManager manager = plugin.sessions();
        Location base = sender instanceof Player player ? player.getLocation()
                : Bukkit.getWorlds().get(0).getSpawnLocation();
        World world = base.getWorld();
        // with nobody online the chunk may be unloaded, and nothing can be added to an unloaded chunk
        int cx = base.getBlockX() >> 4;
        int cz = base.getBlockZ() >> 4;
        world.addPluginChunkTicket(cx, cz, plugin);
        List<Entity> spawned = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int passed = 0;

        Mannequin mannequin = null;
        try {
            mannequin = world.spawn(base, Mannequin.class, entity -> {
                manager.prepare(entity);
                entity.setImmovable(true);
                entity.setCollidable(false);
                entity.setProfile(ResolvableProfile.resolvableProfile(Bukkit.createProfile(UUID.randomUUID(), "ReplayTest")));
                entity.setDescription(null);
                entity.customName(net.kyori.adventure.text.Component.text("ReplayTest"));
                entity.setCustomNameVisible(true);
            });
            spawned.add(mannequin);
            if (!mannequin.isValid()) {
                failures.add("mannequin is not valid after spawning (dead=" + mannequin.isDead()
                        + ", health=" + mannequin.getHealth() + ", inWorld=" + mannequin.isInWorld()
                        + ", chunkLoaded=" + mannequin.getLocation().isChunkLoaded() + ")");
            } else {
                passed++;
            }
        } catch (RuntimeException e) {
            failures.add("mannequin spawn: " + e);
        }

        if (mannequin != null && mannequin.isValid()) {
            Mannequin m = mannequin;
            Actor actor = new Actor(UUID.randomUUID(), m);
            Equipment gear = new Equipment(new ItemStack(Material.DIAMOND_SWORD), new ItemStack(Material.SHIELD),
                    new ItemStack(Material.NETHERITE_HELMET), new ItemStack(Material.ELYTRA),
                    new ItemStack(Material.IRON_LEGGINGS), new ItemStack(Material.DIAMOND_BOOTS));
            UUID worldId = world.getUID();
            PlayerFrame standing = new PlayerFrame(actor.id, worldId, base.getX(), base.getY(), base.getZ(),
                    10f, 5f, 10f, 0, null, gear, 20f);
            PlayerFrame sneakingBlocking = new PlayerFrame(actor.id, worldId, base.getX() + 0.3, base.getY(), base.getZ(),
                    40f, -10f, 35f, PlayerFrame.SNEAKING | PlayerFrame.GLOWING | PlayerFrame.ON_FIRE,
                    EquipmentSlot.OFF_HAND, gear, 12f);
            PlayerFrame gliding = new PlayerFrame(actor.id, worldId, base.getX() + 0.6, base.getY() + 1, base.getZ(),
                    90f, 30f, 90f, PlayerFrame.GLIDING | PlayerFrame.INVISIBLE, null, gear, 12f);
            passed += step(failures, "frame: standing + gear", () -> actor.apply(world, standing, null, 0));
            passed += step(failures, "frame: interpolated", () -> actor.apply(world, standing, sneakingBlocking, 0.5));
            passed += step(failures, "frame: sneaking, blocking, glowing, burning", () -> actor.apply(world, sneakingBlocking, null, 0));
            passed += step(failures, "frame: gliding, invisible", () -> actor.apply(world, gliding, null, 0));
            passed += step(failures, "swing", () -> {
                m.swingMainHand();
                m.swingOffHand();
            });
            passed += step(failures, "hurt animation", () -> m.playHurtAnimation(90f));
            passed += step(failures, "totem effect", () -> m.playEffect(EntityEffect.PROTECTED_FROM_DEATH));
            passed += step(failures, "death effect", () -> m.playEffect(EntityEffect.ENTITY_DEATH));
            sender.sendMessage("[LastFrame] mannequin poses: " + Mannequin.validPoses());
        }

        try {
            ItemDisplay[] displays = new ItemDisplay[2];
            for (int i = 0; i < 2; i++) {
                displays[i] = world.spawn(base, ItemDisplay.class, entity -> {
                    manager.prepare(entity);
                    entity.setItemStack(new ItemStack(Material.ARROW));
                    entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                    entity.setBillboard(Display.Billboard.FIXED);
                    entity.setTeleportDuration(1);
                    entity.setInterpolationDuration(1);
                });
                spawned.add(displays[i]);
            }
            ProjectileView view = new ProjectileView(displays, true, ProjectileKind.ARROW.scale);
            ProjectileFrame a = new ProjectileFrame(1, ProjectileKind.ARROW, new ItemStack(Material.ARROW), world.getUID(),
                    base.getX(), base.getY() + 1.5, base.getZ(), 1.2f, 0.3f, -0.4f);
            ProjectileFrame b = new ProjectileFrame(1, ProjectileKind.ARROW, new ItemStack(Material.ARROW), world.getUID(),
                    base.getX() + 1.2, base.getY() + 1.7, base.getZ() - 0.4, 1.1f, 0.1f, -0.4f);
            view.apply(world, a, b, 0.5);
            passed++;
        } catch (RuntimeException e) {
            failures.add("arrow display: " + e);
        }

        try {
            TextDisplay text = world.spawn(base.clone().add(0, 2.2, 0), TextDisplay.class, entity -> {
                manager.prepare(entity);
                entity.text(net.kyori.adventure.text.Component.text("-3.5❤"));
                entity.setBillboard(Display.Billboard.CENTER);
                entity.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
                entity.setShadowed(true);
                entity.setTeleportDuration(14);
            });
            spawned.add(text);
            text.teleport(text.getLocation().add(0, 0.8, 0));
            passed++;
        } catch (RuntimeException e) {
            failures.add("damage number: " + e);
        }

        boolean visibleToOthers = false;
        for (Entity entity : spawned) {
            if (entity.isVisibleByDefault()) {
                visibleToOthers = true;
            }
            entity.remove();
        }
        if (visibleToOthers) {
            failures.add("a replay entity was visible to everyone");
        }
        world.removePluginChunkTicket(cx, cz, plugin);

        sender.sendMessage("[LastFrame] selftest: " + passed + " passed, " + failures.size() + " failed. "
                + "Quick Actions key: " + (plugin.quickActionsReady() ? "ready" : "not set up"));
        for (String failure : failures) {
            sender.sendMessage("[LastFrame]   FAILED " + failure);
        }
    }

    private static int step(List<String> failures, String name, Runnable action) {
        try {
            action.run();
            return 1;
        } catch (RuntimeException e) {
            failures.add(name + ": " + e);
            return 0;
        }
    }
}
