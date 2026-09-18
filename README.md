# TownNPC

TownNPC is a Paper plugin that adds player-like NPCs which patrol a looped path of waypoints. Each NPC is sent to clients purely through packets. Nothing is spawned on the server, so NPCs are never ticked by the game loop, never saved into world files, and never collide with real entities.

## Requirements

- Paper 1.21.11 or Folia 1.21.11
- Java 21 or newer

## Compatibility

| Platform | Status |
|---|---|
| Paper 1.21.11 | Supported and tested |
| Folia 1.21.11 | Supported and tested, NPCs tick on their own region |
| Online-mode servers | Supported |
| Offline-mode servers | Supported. In-server skins require a skin plugin such as SkinsRestorer to provide textures |
| Geyser / Floodgate | Supported. Bedrock players see the NPC, its skin and its name tag |
| Other server versions | Not supported. The plugin uses internal server classes and is built for one version |

## Features

- Looped waypoint paths, one path per NPC, any number of NPCs
- A* route planning between waypoints: NPCs walk around walls and use stairs, slabs and one-block jumps to reach a different floor
- Ground snapping while walking: stairs, slabs and carpets are climbed without extra setup
- Automatic jumping over one-block obstacles and automatic crouching under low ceilings
- Per-waypoint actions: `jump`, `sneak` until the next waypoint, and `wait` for a number of seconds
- Head tracking of the nearest player in front of the NPC, toggleable per NPC
- Skins from any Mojang account or from a player currently online
- Name tag shown above the NPC, hidden from the tab list
- Packets are only sent to players within a configurable distance; unseen NPCs are not simulated
- Native Folia support: each NPC ticks on the region that owns its chunk

## Installation

1. Download `townnpc-<version>.jar` or build it yourself (see below).
2. Place the jar in your server's `plugins` folder.
3. Restart the server. The configuration is written to `plugins/townnpc/`.

## Building

Requires JDK 21 or newer. Gradle downloads the Paper dev bundle on the first run.

```bash
./gradlew build
```

The jar is written to `build/libs/`.

## Usage

Stand where the NPC should start and create it:

```
/tnpc create guard
```

Walk to the next point of the route and add a waypoint. Repeat until the route is complete. The last waypoint connects back to the first one automatically.

```
/tnpc path guard add
```

Waypoint options can be combined:

```
/tnpc path guard add wait 3
/tnpc path guard add jump
/tnpc path guard add sneak wait 2
```

Give the NPC a skin:

```
/tnpc skin guard mojang Notch
/tnpc skin guard player Steve
```

Use `/tnpc path guard show` to preview the route with particles.

### Tips

- Add waypoints while standing on the ground. The NPC follows the terrain under it; the waypoint height is only used as a fallback.
- The route between two waypoints is planned automatically, including the way back from the last waypoint to the first one. Walls are avoided and floors are changed through stairs, slabs or one-block jumps. If no route exists within `pathfinding.max-distance` and `pathfinding.max-nodes`, the NPC walks in a straight line instead.
- Routes are recomputed at most once per minute per segment, so give the NPC a moment after changing the terrain, or run `/tnpc reload`.
- `/tnpc path <name> show` draws the planned routes once the NPC has walked them. `/tnpc route <name>` prints the next route as text.
- NPCs need the same clearance as a player: two blocks of headroom, or one and a half while sneaking. Staircases between floors work as long as a player can walk them.
- By default an NPC stands still while no player is within `view-distance`. Set `simulate-without-viewers: true` if the route must keep running regardless.

## Commands

All commands require the `townnpc.admin` permission (granted to operators by default). `/tnpc` is an alias of `/townnpc`.

| Command | Description |
|---|---|
| `/tnpc create <name> [world x y z]` | Create an NPC at your position, or at the given coordinates |
| `/tnpc remove <name>` | Delete an NPC |
| `/tnpc list` | List all NPCs |
| `/tnpc info <name>` | Show position, skin, path size and current state |
| `/tnpc tp <name>` | Teleport to an NPC |
| `/tnpc skin <name> mojang <account>` | Use the skin of a Mojang account |
| `/tnpc skin <name> player <player>` | Use the skin of an online player |
| `/tnpc skin <name> clear` | Remove the skin |
| `/tnpc speed <name> <blocks per second>` | Set the walking speed (0.1 to 10) |
| `/tnpc look <name> on\|off` | Enable or disable head tracking |
| `/tnpc pause <name>` / `/tnpc resume <name>` | Stop or continue the patrol |
| `/tnpc path <name> add [jump] [sneak] [wait <seconds>] [at x y z [yaw]]` | Append a waypoint |
| `/tnpc path <name> insert <index> [options] [at x y z [yaw]]` | Insert a waypoint before the given index |
| `/tnpc path <name> set <index> jump\|sneak <true\|false>` | Change a waypoint flag |
| `/tnpc path <name> set <index> wait <seconds>` | Change the wait time of a waypoint |
| `/tnpc path <name> move <index> [at x y z]` | Move a waypoint to your position |
| `/tnpc path <name> remove <index>` | Delete a waypoint |
| `/tnpc path <name> list` | List waypoints with their index and options |
| `/tnpc path <name> clear` | Delete every waypoint except the first |
| `/tnpc path <name> show` | Show the route with particles for 15 seconds |
| `/tnpc route <name>` | Print the planned route from the NPC's current position to its next waypoint |
| `/tnpc reload` | Reload `config.yml` and `npcs.yml` |

## Configuration

`plugins/townnpc/config.yml`

| Key | Default | Description |
|---|---|---|
| `view-distance` | `48` | Players farther away than this do not receive NPC packets |
| `viewer-check-interval` | `10` | Ticks between checks of which players can see each NPC |
| `simulate-without-viewers` | `false` | Keep NPCs moving while nobody is watching. On Folia this also keeps the NPC's chunk loaded |
| `movement.default-speed` | `2.5` | Walking speed in blocks per second for new NPCs |
| `movement.sneak-multiplier` | `0.3` | Speed multiplier while sneaking |
| `movement.step-height` | `0.6` | Height an NPC climbs without jumping |
| `movement.max-jump-height` | `1.25` | Highest obstacle an NPC will jump onto |
| `look.enabled` | `true` | Global switch for head tracking |
| `look.radius` | `6.0` | Distance in blocks at which players are tracked |
| `look.fov` | `140` | Field of view in degrees, centered on the walking direction |
| `skin.timeout-seconds` | `5` | Timeout for Mojang API requests |
| `skin.cache-days` | `7` | How long fetched skins are cached |
| `limits.max-npcs` | `100` | Maximum number of NPCs |
| `limits.max-waypoints` | `500` | Maximum waypoints per NPC |
| `pathfinding.enabled` | `true` | Plan routes around obstacles between waypoints |
| `pathfinding.max-nodes` | `4000` | Search budget per route |
| `pathfinding.max-distance` | `96` | Waypoints farther apart than this are connected by a straight line |
| `pathfinding.max-drop` | `3` | Highest drop the NPC will walk off while following a route |

NPC definitions are stored in `plugins/townnpc/npcs.yml`. The file can be edited by hand; run `/tnpc reload` afterwards.

## Performance

Each moving NPC reads about a dozen blocks per tick, only from chunks that are already loaded, and sends one relative movement packet per viewer. Head rotation and pose packets are sent only when they change. NPCs without viewers or in unloaded chunks are skipped entirely.

Route planning runs once when an NPC leaves a waypoint and the result is cached for a minute. A search is capped by `pathfinding.max-nodes`, so a single plan stays in the low milliseconds even when no route exists.

In a local test, 50 NPCs walking planned routes around obstacles at the same time added roughly 0.5 ms per server tick.

On Folia, every NPC schedules its own tick on the region that owns its current chunk, so NPCs in different regions are processed in parallel and never touch a chunk from the wrong thread. An NPC with no viewers drops to a light global check every `viewer-check-interval` ticks after five seconds and resumes region ticking as soon as a player comes within `view-distance`.

## Security notes

- The plugin never executes commands on behalf of players, and clicking an NPC has no effect.
- NPC names and Mojang account names are validated against `[A-Za-z0-9_]{1,16}` before use.
- Skin lookups only contact `api.mojang.com` and `sessionserver.mojang.com` over HTTPS, with a timeout, no redirects and a response size limit. Textures without a valid signature are rejected.
- Every value read from `config.yml` and `npcs.yml` is clamped to a safe range. Invalid entries are skipped with a warning instead of failing the plugin.
- Network requests run off the main thread; results are applied on the main thread only while the plugin is enabled.
