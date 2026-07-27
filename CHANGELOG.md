# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
