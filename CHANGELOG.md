# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Minecraft 26.3. There are new Fabric (Fabric API 0.165.0+26.3), NeoForge (26.3.0.99) and Forge
  (66.1.2) jars, and the Paper jar now covers 1.21 to 26.3.
- `/chunkcarts help` lists each subcommand and what it does, on every platform. On Paper an
  unknown subcommand now prints the same help. Fabric, NeoForge and Forge still answer an unknown
  word with the usual "Unknown or incomplete command".
- `/chunkcarts why`. Run it in game while looking at a cart, or standing next to one. It tells you
  what the cart is doing, why its last journey ended, or why it is being refused a departure. For a
  cooldown it also says how long is left. Before this, the end reason only reached the console and
  the refusal reason only showed up with `/chunkcarts debug` on.
- `/chunkcarts list` rows can be clicked to teleport to the journey. The click runs
  `/execute in <dimension> run tp @s <x> ~ <z>` to the row's position and keeps your current
  height. Only players who could run that teleport themselves get the click: on Paper that means
  `minecraft.command.execute` and `minecraft.command.teleport`, on the mod loaders permission
  level 2. Everyone else, the console included, gets plain text.
- bStats gets a third chart, `platform`, so Fabric, NeoForge and Forge servers stop showing up as
  "Unknown" on the dashboard. See Changed for what else is different in what bStats receives.

### Changed

- `/chunkcarts list` is easier to read:
  - The held-chunk tally is red unless every held chunk is at `ENTITY_TICKING`. Anything less means
    carts sit still in chunks that are loaded but not ticking entities.
  - The tally shows whenever chunks are held, including when there are no active journeys. Chunks
    held with no journey to hold them are a leak, and that was the one case where the tally was
    hidden.
  - Each row gives where the journey is now (`near`), not only where it started.
  - Output is paged at eight rows, so the header with the totals stays on screen.
- `/chunkcarts reload` reports what it did. You get one of three answers: the new config is in
  force; the file could not be used and the old settings are still running; or the rebuild failed
  and ChunkCarts is stopped. It also shows the config warnings to whoever ran it, not just the
  console, with the count even when it is zero, capped at eight lines with the rest in the console.
- Config values that cannot work are now raised to a minimum with a warning, in both `STRICT` and
  `PERMISSIVE`. **A config that loaded without complaint on 0.1.0 may now be adjusted:**
  - `poll-interval-ticks` and `region-radius`: minimum 1. A `region-radius` of 0 held only the
    cart's own chunk, so carts stopped at the first chunk boundary.
  - `journey-budget-chunks`, `progress-slack-chunks` and `max-concurrent-journeys`: minimum 1.
    `max-concurrent-journeys: 0` used to refuse every departure, in `PERMISSIVE` too. It now
    means 1.
  - `journey-cooldown` is capped at 24h in `STRICT`. It had no ceiling before.
  - A whole number too large to store, such as `journey-budget-chunks: 3000000000`, falls back to
    the default with a warning. On 0.1.0 it wrapped round to a negative number and every journey
    ended on its first check.
  - A duration too long to measure, such as `journey-cooldown: 999999999999999h`, falls back to the
    default with a warning. On 0.1.0 it was accepted and then stopped every cart from departing.
- A train now keeps its journey when its lead goes through a portal or its chunk unloads. On 0.1.0,
  losing the lead for any reason ended the journey for every cart in the train. Now only a lead
  that is destroyed ends it:
  - A lead that crosses a portal picks its journey back up on the far side. Followers still on the
    near side stay on the journey until they cross. A follower that goes through before the lead
    is kept while the lead stays near the portal.
  - A lead whose chunk unloads releases the journey's chunks and resumes with its train when the
    chunk loads again. A follower that unloads rejoins the same journey when it loads.
  - A lead that is not seen again for `pause-tolerance` releases its chunks. The journey stays on
    the cart and resumes if the cart turns up later.
- On Fabric, NeoForge and Forge, the server software sent to bStats is now the server's own brand
  instead of the name of the loader the jar was built for. On a plain loader server that is the
  same name in lower case (`fabric`, `neoforge`, `forge`). A hybrid server running a loader jar
  now reports its own name. Nothing new is sent for this; it is the same field with a different
  value.
- The README's Metrics section now lists every field ChunkCarts sends. The 0.1.0 README named
  only server software, game version and player count among the standard bStats fields; bStats
  also sends online mode, Java version, OS name, architecture and version, and CPU core count, and
  0.1.0 already sent them.

### Fixed

- A failed `/chunkcarts reload` no longer stops ChunkCarts. On 0.1.0 the reload shut everything
  down before reading the new file, so one bad config released every held chunk and left nothing
  running until a server restart. On the mod loaders a second reload then failed with a
  NullPointerException, and on Paper `/chunkcarts list` went on reporting as if nothing was wrong.
  The file is now read and checked first, and a file that cannot be used leaves the running
  settings alone. That includes a YAML syntax error, which 0.1.0 read as an empty file, so the
  reload swapped in the defaults and said it had succeeded. If ChunkCarts does end up stopped,
  `/chunkcarts list` says "Not running." on every platform. At server start, a broken file starts
  ChunkCarts on the built-in defaults (`STRICT`) with an error in the console. Fix the file and run
  `/chunkcarts reload`.
- `/chunkcarts reload` no longer throws away departure tokens and cooldown waivers. A cart a player
  had been near lost its permission to depart, and a right-click no longer waived the cooldown. Both
  are now saved on the cart, so they also survive the cart's chunk unloading and a server restart.
- A `/chunkcarts reload` could stop bStats reporting for the rest of the server's uptime.
- On Paper, a cart that comes out of a portal is now tracked on the far side, and a cart whose
  chunk unloads keeps its journey. Before, a train that crossed a portal was never seen again, and
  every chunk unload counted as the cart being destroyed.
- On Fabric, NeoForge and Forge, a cart whose chunk unloads no longer counts as destroyed. The
  game stops tracking a chunk's carts before it marks them unloaded, and the mod took a cart with
  no reason given for a destroyed one. So the train behaviour above did not hold for unloads on
  these platforms: a train member whose chunk unloaded lost its journey instead of rejoining it.
- An error inside the travel loop no longer crashes a Fabric, NeoForge or Forge server. It is
  logged, repeats are throttled, and the loop keeps running so held chunks still get released.
  Paper already survived this.
- Two carts on separate journeys whose held areas overlap no longer strand each other. The first
  cart to move on used to release chunks the second still needed, so the second could roll into
  unloaded ground and stop. A chunk is now held until the last journey that needs it moves on.
- A negative `poll-interval-ticks` no longer stops the travel loop on Paper. Paper ran the loop
  once and never again, with nothing in the log.
- Breaking a follower in a train now drops only that cart, the same as a follower that falls
  behind. On 0.1.0 it ended the journey for the whole train.
- Breaking the lead cart of a train now ends the journey cleanly for the rest. The surviving
  carts kept a saved record that said they were still travelling, so the journey could come back
  when they reloaded, holding chunks again without spending travel budget or needing a new
  departure.
- A TNT, command block or spawner minecart touching a cart that departs no longer travels with
  it. Only the front cart was checked against the allowed types.
- With `PERMISSIVE` and `unload-hold`, the hold no longer ends in the same poll that sees the
  cart's contents starting to drain.
- A config key that YAML reads as a boolean or a number (a bare `on`, `off`, `yes`, `no`, `y`, `n`,
  or a numeric key) no longer stops ChunkCarts loading. On the mod loaders this happened during
  server startup and took the server down. It is now reported as an unrecognised key.
- A `cart-types` value that is not `true` or `false` is now reported as disabling that cart type,
  which is what happens. The warning used to say the default was used.
- A cart with corrupted ChunkCarts data saved on it no longer breaks loading its chunk on Fabric,
  NeoForge or Forge. The bad data is ignored and the cart is treated as one with no journey.
- Forge: a player without ChunkCarts can now join a server running it. The jar did not tell Forge
  that clients can do without it, so Forge clients without the mod were refused and the server
  showed as incompatible in their server list.
- Forge and NeoForge: ChunkCarts no longer clashes with other mods that bundle SnakeYAML. Both
  loaders share one copy between mods, so a mod with an older copy could crash ChunkCarts at config
  load, which took the server down. ChunkCarts now carries its copy under its own package name.
- NeoForge: the jars now require at least the NeoForge version they were built against. An older
  NeoForge refuses the jar with a clear message instead of loading it and failing later on a
  missing method.

## [0.1.0] - 2026-07-27

### Added

- Minecarts now hold the chunks they travel through, so items and passengers can be sent across
  long distances on a server where nobody is standing at the far end.
- **Closed and looping tracks stop loading automatically.** A journey is only allowed to continue
  while a cart keeps getting further from where it set off; a loop reaches its furthest point on
  the first lap and can never beat it, so it runs out of slack and every chunk it was holding is
  released. The same detector catches back-and-forth shuttles and self-propelled furnace carts
  with nobody watching. On default settings a minecart cannot be turned into a permanent chunk
  loader.
- A travel budget backstops the loop detector with a hard ceiling on how many chunks a single
  journey may load in total, independent of loop shape.
- Departure requires a token that is only issued to carts a real player has recently been near, so
  an unattended rail line cannot cycle itself forever. Two looser tiers are available for automated
  routes: one that only requires a player online somewhere on the server, and one with no human
  requirement at all, for servers that want fully automatic shuttles.
- A per-cart journey cooldown rate-limits how often the same cart may depart, measured from when
  its last journey started. The cooldown is waived if a player has interacted with the cart since,
  so stopping it, topping it up, and sending it off again just works without waiting out a timer.
- Carts that pause mid-route — at a sorting junction, a signal stop, or labouring up a hill — keep
  their chunks loaded and their journey intact for a configurable tolerance, so the journey simply
  continues rather than ending the moment a cart stops moving. Only once that tolerance expires
  without the cart resuming does the journey end.
- Carts travelling together (a train) are tracked as a single cluster and released together; a
  member that drifts more than the configured region radius from the rest of the group is dropped
  from the cluster and accounted for on its own rather than silently vanishing from the count.
- Arriving carts get a short settle hold so a detector rail or station redstone has time to fire
  and shunt them onward, in every enforcement mode. A separate, opt-in unload hold can extend that
  window so hoppers have time to drain a cart, but it requires permissive enforcement and is capped
  to protect against turning a station into a disguised chunk loader.
- In-flight journeys are written to persistent storage and survive a server restart: a cart that
  was travelling before a restart resumes its journey afterward instead of losing its progress or
  quietly becoming an unmanaged loader.
- A master `enforcement` switch: `STRICT` (default) runs the full loop detector, travel budget, and
  departure gates and is the setting the no-chunk-loader guarantee applies to; `PERMISSIVE` turns
  all of it off, prints a startup warning, and unlocks the unload hold, for admins who trust every
  player on their server.
- In `STRICT` mode every abusable setting is clamped to a hard ceiling, so `STRICT` stays a promise
  about behaviour rather than a set of dials that can quietly undo it.
- Per-cart-type control over which minecarts may depart at all (normal, chest, hopper, furnace);
  TNT and command-block carts can never be enabled, on either enforcement mode.
- A world blocklist or allowlist to include or exclude specific worlds or dimensions from
  ChunkCarts entirely.
- A cart whose journey ends early is left exactly where it stopped, with nothing lost, and the
  event can be logged with the specific reason it ended — reached its destination, ran into the
  loop detector, got stuck, ran out of budget, headed into unexplored terrain, or derailed — so an
  admin can tell working-as-intended apart from an actual problem. Optionally the last player who
  interacted with the stranded cart can be notified of where it stopped.
- `/chunkcarts` (alias `/ccarts`) with `list`, `reload`, and `debug` subcommands, gated behind the
  single `chunkcarts.admin` permission: `list` reports every active journey and exactly which
  chunks are held and why, `reload` re-reads the configuration without interrupting running
  journeys, and `debug` toggles per-poll logging so a journey's decisions can be watched live.
- A fully annotated `config.yml` documenting every setting in plain language next to the setting
  itself; the shipped defaults are the safe, recommended configuration and require no changes to
  use.
- Anonymous, opt-out usage statistics (server software, game version, player count, and the
  enforcement and departure-requirement settings in use) to guide which platforms and versions are
  worth continuing to support.
- Server-side support for Paper (and Paper forks) with a single jar covering Minecraft 1.21 through
  26.2, built on Paper's asynchronous chunk loading; Spigot is refused rather than degraded, since
  it lacks that API and would stall the main thread on every chunk boundary a cart crossed.
- Server-side support for Fabric, NeoForge, and Forge, built per game version across the same
  1.21–26.2 range (1.21.2 excluded, superseded within days of release).
