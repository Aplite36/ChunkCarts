# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `/chunkcarts reload` now shows the config warnings to whoever ran it, not just to the console.
  Someone runs reload to find out what their edit did, and got back "Config reloaded. Enforcement:
  STRICT." while everything that would answer the question — each clamp naming the key, the value
  they wrote and what it became — went to the log. It now reports the count even when it is zero,
  because silence and success previously looked identical, and caps the list at eight lines with a
  pointer to the console so a config full of mistyped keys cannot spew into chat.
- `/chunkcarts list` now colours the held-chunk load level red unless every chunk is
  `ENTITY_TICKING`. That tally is the only in-game evidence for the one claim no unit test can
  reach — that the chunks the mod holds are actually ticking — and it rendered as plain text, so
  `(TICKING x9)` and `(ENTITY_TICKING x9)` were the same shape and nobody noticed the difference.
  Anything other than `ENTITY_TICKING` means carts sit motionless in loaded chunks.
- `/chunkcarts list` also reports the tally when there are no active journeys but chunks are still
  held, which is the leak shape and previously the one case where the number was hidden; names
  where each cluster is *now* rather than only where it set off, which could be thousands of blocks
  behind it; and pages, at eight rows a page, so the header carrying the totals is not the first
  line to scroll away on a busy server.
- `/chunkcarts help`, on every platform, listing each subcommand and what it is for rather than
  just naming them. There was no help before, and what an unrecognised word did depended on which
  jar you had installed: Paper printed a usage line for anything it did not recognise, so
  `/chunkcarts help` happened to work, while Brigadier answered the same guess on Fabric, NeoForge
  and Forge with "Unknown or incomplete command". Paper now answers an unknown subcommand with the
  full help instead of a usage string, which was a second list of subcommands to forget to update.

### Changed

- The AFK-dispenser guard is now documented as what it is. `CartEvent` carried `HOPPER_INSERT`,
  `DISPENSER_DISPATCH` and `PLAYER_NEARBY`, classified as not-interaction, and said that
  classification was what stopped an AFK player beside a dispenser cycling a cart forever. No
  adapter ever emitted any of the three, so it decided nothing, and the three tests that named the
  policy passed by feeding the engine values nothing produces. What actually holds the guard up is
  that no adapter listens for a hopper or a dispenser at all, so a dispenser-fired cart gets no
  waiver and keeps its cooldown. The three constants and the three tests are gone, replaced by one
  that exercises the path a dispenser really takes. No behaviour changes.

### Removed

- `CartView.lastInteractor()`, which was implemented on both platforms and called by nothing.
  `notify-last-interactor` is served by each tracker's own map, read where the message is sent, so
  every cart view built for a poll was paying a map lookup to fill a field no one read.

### Fixed

- A failed `/chunkcarts reload` no longer leaves the mod dead. The command tore the runtime down
  before it knew a replacement could be built, so one unreadable config file released every ticket,
  shut the engine down and left nothing running, recoverable only by restarting the server. The
  config is now read and validated first, and a file that cannot be used leaves the settings already
  in force running untouched. The command says which of the three things happened instead of
  reporting success either way — and on the mod loaders, a second reload after a failed one used to
  answer with a NullPointerException that hid the real error.
- `/chunkcarts list` on Paper now says "Not running." when the plugin is inert, as it always has on
  the mod loaders. Paper kept its fields after a shutdown, so a plugin that had stopped working
  reported no active journeys, zero chunks held and the old enforcement mode, with nothing in the
  output to say anything was wrong.
- An error inside the travel loop no longer takes a Fabric, NeoForge or Forge server down. Task
  bodies ran with no exception isolation, so anything the engine threw went straight through the
  adapter into the loader's tick event and became a crash report — while the same bug on Paper was
  a stack trace and a plugin that limped on, because Bukkit's scheduler already catches. A fault is
  now caught, logged against the task that threw, and the server keeps ticking. Repeats are
  throttled rather than the task being disabled: the travel loop is what releases held chunks, so a
  loop that stopped running would leave every chunk it had already claimed held indefinitely. The
  ticket backend's per-tick sweep is guarded the same way.
- Two carts on separate journeys no longer strand one another where their routes run close
  together. Wherever their held areas overlapped, the first cart to move on released a chunk the
  second was still relying on, and the second never took it back — so it could roll into unloaded
  terrain and stop, with nothing in the log to explain it. A chunk is now held for as long as any
  journey still needs it, and only released once the last one has moved on.
- A negative `poll-interval-ticks` no longer disables the travel loop outright on Paper. The value
  reached the scheduler unchecked, which reads a negative period as "run once and never again", so
  the plugin started cleanly, reported nothing wrong, and then did nothing at all while every cart
  stranded. `poll-interval-ticks` and `region-radius` now have a floor, applied in both enforcement
  modes: a travel loop that never runs is not a permission an admin can grant themselves.
- Breaking one cart out of a travelling group no longer ends the journey for the rest of it.
  Removing any member released the whole cluster's chunks and forgot the journey, leaving the lead
  cart still on the rails with nothing held for it and a saved record that still claimed it was
  travelling. A follower is now dropped on its own, exactly as one that drifts out of the group
  already was; only losing the lead ends the journey.
- The unload hold no longer ends in the same moment it sees a cart's contents still draining. The
  completion check was made before the poll's own observation was recorded, so a first decrease
  arriving on the boundary of the quiet period was measured against a timer that still pointed at
  the start of the hold. Requires permissive enforcement, where the unload hold applies at all.
- NeoForge jars now declare the minimum NeoForge version they were built against, instead of
  accepting any version ever released. Installing a jar on an older loader is refused with a clear
  message rather than loading and then failing on a missing method with nothing to say why. The
  release checks now assert this, so it cannot quietly return.
- A TNT, command block or spawner minecart parked against a cart that is about to depart no longer
  travels with it. Only the cart at the front of a group was checked against the permitted types,
  and whatever was touching it came along unexamined — chunks held for it, its whole route loaded.
  These three types can never be enabled in the config, and standing next to something that can is
  no longer a way around that.
- Breaking the front cart of a travelling group now ends the journey properly for the carts left
  behind. Their chunks were released and the journey dropped, but each survivor kept a saved record
  that still said it was travelling, so the next time one of them unloaded and loaded again the
  finished journey came back — re-holding its area without spending any of the travel budget or
  needing a fresh departure. This is the other side of the group fix above, which covered a
  follower being broken but not the lead.
- A `cart-types` entry that is not `true` or `false` is now reported as disabling that cart type,
  which is what actually happens. The warning previously said the default was used, telling an
  admin the opposite of what became of the cart type they were trying to debug.
- A whole number too large to be one is now a warning instead of a silently different setting.
  `journey-budget-chunks: 3000000000` was applied as `-1294967296`, so every journey died on its
  first check with nothing in the log connecting the two. Out-of-range values now fall back to the
  default and say so, and `journey-budget-chunks`, `progress-slack-chunks` and
  `max-concurrent-journeys` have a floor of 1 in both enforcement modes.
- A config key that YAML reads as something other than text no longer stops the plugin loading. A
  bare `on`, `off`, `yes`, `no`, `y` or `n` key, or a numeric one, is a boolean or a number to
  every YAML parser, and the reader threw on it instead of warning — on the mod loaders, during
  server startup, which took the server down with it. Such a key is now reported as unrecognised,
  like any other typo.
- A duration too large to be measured in milliseconds is now refused at load. `journey-cooldown:
  999999999999999h` parsed cleanly and then threw on every departure attempt, forever, so no cart
  ever set off. It now falls back to the default with a warning, and `journey-cooldown` is capped
  at 24h in STRICT mode alongside the other durations.
- `region-radius: 0` is no longer accepted. It held the cart's own chunk and nothing else, so the
  cart stopped at the first chunk boundary; it tore apart any group of carts straddling one; and
  it pinned the travel loop to a check every tick while `/chunkcarts list` blamed cart speed. The
  minimum is now 1, in both enforcement modes, and a lower value is raised with a warning.

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
