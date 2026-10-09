# LastFrame

**BO3-style killcam for Paper. Die, and watch the kill through your killer's eyes.**

Made by **skilledseeker** (`skilledmc` on Discord).

LastFrame keeps the last few seconds of every fight in memory. The moment a player is killed, the
fight plays again for them as a killcam: they are respawned, the camera is put inside the killer's
head, and they see exactly how they died. Sneaking skips it. The vanilla Quick Actions key (G)
opens the full controls at any time, so they can also pause it, rewind it, slow it down or fly
around it as a spectator. Playback uses mannequins that only the viewer can see. Nothing is ever
written to disk.

## Features

- **Killcam**: plays by itself after every death, through the killer's eyes, with a KILLCAM title,
  the killer's name and a progress bar. Sneak to skip.
- Free camera, the killer's point of view, or your own
- Pause, restart, skip back and forward, speed from 0.25x to 2x, optional slow motion before the kill
- Floating damage numbers, projectiles (arrows, pearls, wind charges...) and block changes
- Kill summary at the end: how much health the killer had left and how many hits each side landed
- Controls open on the vanilla Quick Actions key (G by default) or with `/replay`
- Hides the live world while watching, so only the replay is on screen
- Players can turn death replays off for themselves with `/replay toggle`
- Offer mode as an alternative: with the killcam off, players are offered the replay in chat and on
  the death screen instead, and only watch it if they click

## Requirements

- Paper 1.21.9 or newer (mannequins). Built against 1.21.11.
- Java 21
- Players need Minecraft Java 1.21.9 or newer to watch. With ViaVersion installed, older clients
  are told so instead of getting a broken replay.

## Optional hooks

All of these are detected at runtime and none of them is required:

- **ViaVersion** - know which client version each player runs
- **Floodgate** - detect Bedrock players (replays are off for them by default, see `clients.bedrock`)
- **UnstableCore** - treat duel deaths separately (`clip.include-duels`)

## Commands

`/replay` has the aliases `/deathreplay` and `/lastframe`.

| Command | What it does |
|---|---|
| `/replay` | Watch your last death while an offer is open, otherwise open the controls |
| `/replay skip` | Skip the killcam / leave the replay (sneaking does the same in a killcam) |
| `/replay watch` | Watch your last death (starts it over if you are already watching) |
| `/replay controls` | Open the controls dialog |
| `/replay pause` | Pause or resume |
| `/replay back` / `forward` | Skip 3 seconds |
| `/replay restart` | Start the replay over |
| `/replay speed` | Cycle 0.25x, 0.5x, 1x, 2x |
| `/replay killer` / `you` | See through the killer's eyes or your own |
| `/replay free` | Back to the free camera |
| `/replay toggle` | Turn death replays on or off for yourself |
| `/replay about` | Plugin name, version and author |
| `/replay reload` | Reload config.yml (admin) |
| `/replay selftest` | Run the built-in self test (admin) |

## Permissions

| Permission | What it allows | Default |
|---|---|---|
| `lastframe.admin` | `/replay reload` and `/replay selftest` | op |
| `lastframe.bypass` | Run any command while watching a replay | op |

## Configuration

Everything lives in `config.yml` and is documented inline. The main knobs:

- `killcam.enabled` - the killcam (on) or the offer mode (off)
- `killcam.auto-respawn`, `killcam.respawn-delay-seconds` - leave the death screen by itself
- `killcam.killer-pov` - start inside the killer's head, or beside the fight
- `killcam.sneak-skips` - sneaking skips the killcam
- `record.worlds` / `record.excluded-worlds` - where to record
- `clip.seconds`, `clip.radius`, `clip.lead-in-seconds` - how long and how wide a replay is
- `clip.only-player-kills` - only replay deaths caused by another player
- `offer.*` - how the replay is offered when the killcam is off
- `playback.*` - slow motion, camera range, damage numbers, end hold, hiding the live world
- `clients.min-protocol`, `clients.bedrock` - who is allowed to watch
- `quick-actions-key` - bind the controls to the Quick Actions key (needs a restart)
- `messages.*` - every message, with `&` colour codes and `&#rrggbb` hex colours

`/replay reload` applies everything except `quick-actions-key`.

## Building

```
mvn package
```

The jar lands in `target/LastFrame-<version>.jar`. Drop it into the server's `plugins` folder and
restart (a restart, not a reload: the controls dialog has to be registered before the server loads
its registries).

## Credits

LastFrame is made by **skilledseeker** (`skilledmc` on Discord).
