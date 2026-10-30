# ChunkCarts

Minecarts load the chunks they travel through, so you can send items across long distances on a
server where nobody is standing at the far end.

**Closed and looping tracks stop loading automatically.** That is the point of the mod, and it is
what the rest of this page is about.

---

## The guarantee

On the default settings, **a minecart cannot be used as a chunk loader.**

A cart on a real journey gets further from where it started, chunk after chunk, so it keeps
travelling. A cart on a loop reaches its furthest point on the first lap and can never beat it — so
it runs out of slack and ChunkCarts releases every chunk it was holding. Same for a cart shuttling
back and forth between two points, and same for a self-propelled furnace cart with nobody watching.

That is not a heuristic bolted on afterwards. It is the whole design: a journey is only ever
*allowed to continue* while the cart is making progress away from where it set off.

There is one way to turn it off, and it is a single setting with a warning printed at every startup:
`enforcement: PERMISSIVE`. If you set it, minecarts on your server **can** be used as chunk loaders,
by anyone who can build a loop of powered rail. Set it only if you trust everyone who plays there.

---

## Verify it yourself

Don't take the claim on trust — it takes about five minutes to try to break, and finding a way to is
more useful to both of us than believing it.

1. In `server.properties`, set `simulation-distance=2`. This is the whole trick: it shrinks what a
   player loads from several hundred chunks to a handful, so ChunkCarts' own loading is the only
   thing left moving and you can actually see it.
2. Build a closed loop of powered rail — a 32×32 square is plenty — and put a cart on it.
3. Watch what is held. `/chunkcarts list` reports exactly which chunks ChunkCarts is holding, for
   which journeys, on any platform.
4. Run `/tick sprint 72000` (an hour of game time, in a couple of minutes) and watch the held chunk
   count fall to zero and stay there while the cart is still going round.
5. Check the log for `SLACK_EXHAUSTED`. That is the loop detector firing, and it means the cart
   stopped loading chunks without anyone intervening.

Then try to defeat it. Wind the loop into a figure of eight, make it enormous, put the cart on a
long there-and-back shuttle, use a furnace cart so no redstone is involved at all. If you find a
track shape that keeps chunks loaded indefinitely on default settings, that is a bug and a serious
one — please open an issue.

---

## Install

Server side only. There is nothing to install on clients and they do not need the mod to join.

| Platform | File | Notes |
| --- | --- | --- |
| Paper (and Paper forks) | `chunkcarts-<version>+bukkit.jar` | One jar covers 1.21 → 26.3 |
| Fabric | `chunkcarts-<version>+<mcver>-fabric.jar` | One jar per game version; needs Fabric API |
| NeoForge | `chunkcarts-<version>+<mcver>-neoforge.jar` | One jar per game version |
| Forge | `chunkcarts-<version>+<mcver>-forge.jar` | One jar per game version |

Game versions: `1.21`, `1.21.1`, `1.21.3` … `1.21.11`, `26.1`, `26.1.1`, `26.1.2`, `26.2`, `26.3`.
(1.21.2 is skipped — it was superseded within days and is not worth carrying.)

**Paper, not Spigot.** The Bukkit jar requires Paper or a Paper fork. It uses Paper's asynchronous
chunk loading; on Spigot, every chunk boundary a cart crossed would stall the main thread instead.
Shipping a silently worse version of the mod to chase that platform is not a trade worth making, so
it is refused rather than degraded quietly.

---

## Configuration

The shipped `config.yml` documents every setting in full, in plain language, next to the setting
itself. **The defaults are the safe recommended settings — you can leave the file alone.**

The four worth knowing about:

- `enforcement` — `STRICT` (default) or `PERMISSIVE`. See the guarantee, above.
- `departure-requirement` — how much human involvement a cart needs before it may set off.
  `PLAYER_SEEN` (default) means a player has to have been near it since its last trip;
  `PLAYER_ONLINE` means your rail network runs while anyone is online and goes quiet overnight;
  `NONE` means fully automatic shuttles with nobody present.
- `progress-slack-chunks` — the loop detector's tolerance. Raise it a little if very winding but
  legitimate track is being cut off; lowering it kills loops faster.
- `journey-cooldown` — minimum time between journeys for the same cart. Waived if a player has
  touched the cart since, so "stop it, top it up, send it off again" just works.

In `STRICT` mode the abusable settings are clamped to sane ceilings rather than trusted. That is
deliberate: `STRICT` is a promise about behaviour, so it cannot also be a set of dials that quietly
undo the promise.

---

## Commands

One permission, `chunkcarts.admin`, which operators have by default. `/ccarts` works as an alias.

| Command | What it does |
| --- | --- |
| `/chunkcarts list` | Active journeys, and exactly which chunks are held and why. In game, click a row to teleport to it |
| `/chunkcarts reload` | Re-read `config.yml`; running journeys are not interrupted |
| `/chunkcarts debug` | Toggle per-poll logging, so you can watch a journey decide |
| `/chunkcarts why` | Run in game, looking at a cart: why it stopped, or why it will not depart |
| `/chunkcarts help` | List the subcommands and what each one is for |

---

## Termination reasons

Every journey that ends logs one line: `Journey ended: <REASON> — <type> at <world> x,y,z`,
whenever `stranded.log` is on (the default). `/chunkcarts why` shows the last termination reason
for the cart you are looking at. Nine reasons exist, and an admin seeing one needs to know whether
it is a problem or the mod doing its job:

| Reason | What it means | What to do |
| --- | --- | --- |
| `ARRIVED` | Cart slowed below `min-speed` and stayed there past `pause-tolerance` — a journey ending normally. | Nothing. This is success; the cart earns an arrival hold (`arrival-hold-ticks`) before its chunks release. |
| `STALLED` | Cart was still above `min-speed` — wedged, blocked, or oscillating — when `pause-tolerance` ran out. | Check the track at the reported coordinates. |
| `DROPPED` | A cart travelling with others fell more than `region-radius` behind the lead and was dropped from the cluster; it releases its own chunks at once rather than earning a hold. | Usually nothing. If it keeps happening, look at what is separating the cart from the rest of the train. |
| `SLACK_EXHAUSTED` | The loop detector fired: no new distance record within `progress-slack-chunks`. | Nothing — this is the mod working as intended. See "Verify it yourself", above. If it fires on track that is not actually a loop, raise `progress-slack-chunks` a little. |
| `BUDGET_EXHAUSTED` | The journey used up `journey-budget-chunks` — more chunks than the configured travel budget allows. | Raise `journey-budget-chunks` if your rail lines are genuinely this long. It is a backstop, not the main protection — loop detection is. |
| `UNGENERATED_CHUNK` | The route reached terrain that has not been generated yet, and ChunkCarts refused to generate it. | Walk the route yourself once to generate the chunks ahead of time, or accept the limit. |
| `LEFT_RAILS` | The cart derailed. | Check the track at the reported coordinates. |
| `DIMENSION_LIMIT` | The journey already used its one allowed dimension crossing and tried to cross again. | Nothing to fix — a journey may cross dimensions once by design. Split a route with more than one portal into separate journeys. |
| `WORLD_DISABLED` | The cart's world is excluded under the `worlds` setting (blocklist or allowlist). | Nothing, if that is what you configured. Check `config.yml`'s `worlds` section if it is not. |

`ARRIVED` and `WORLD_DISABLED` only reach the log with `/chunkcarts debug` on — the other seven
always log when `stranded.log` is on, since those are the ones worth noticing without asking.

---

## Unattended unloading, and why `/forceload` is the better answer

A cart arriving at an unattended station is held just long enough to settle and let a detector rail
fire (`arrival-hold-ticks`, 3 seconds by default). It is **not** held long enough for hoppers to
empty it, and in `STRICT` mode it cannot be.

This is a design position, not an oversight. One hopper moves about 2.5 items a second and only one
hopper fits under a stationary cart, so draining a full chest cart takes roughly **eleven minutes**.
Holding a station loaded for eleven minutes, on repeat, *is* chunk loading — the exact thing
`STRICT` exists to prevent. There is no value that is both long enough to be useful and safe.

If you want a station that unloads carts unattended, there are two honest options:

- Use vanilla **`/forceload add`** on the station chunk. One chunk, permanently, entirely under your
  control and visible in `/forceload query`. It doesn't touch ChunkCarts' guarantees at all, and for
  most servers it is simply the better tool.
- Or set `enforcement: PERMISSIVE` along with `unload-hold`, and accept everything else that
  permissive mode brings with it.

---

## When ChunkCarts does nothing at all

If your `simulation-distance` is high and your rail lines are short, players already load the whole
route and ChunkCarts has nothing to add. It will still track journeys, but every chunk it would hold
is one a player is holding anyway — and those are free, so they don't count against the travel
budget either.

Worth saying out loud, because it is the most common reason someone installs the mod and sees no
difference. ChunkCarts is for long routes on servers with a modest simulation distance. If that is
not you, you may not need it.

---

## What ChunkCarts will not do

Deliberate refusals, not a roadmap:

- **No craftable chunk-loader item.** The entire premise is that chunk loading is a side effect of
  genuine travel. An item that loads chunks is just a chunk loader with extra steps.
- **No station signs, no route definitions, no scheduling.**
- **No speed boosting.**
- **No per-player permissions** beyond the single `chunkcarts.admin`.

Those are [TrainCarts](https://github.com/bergerhealer/TrainCarts)' territory, and it does them well.
ChunkCarts does one thing and makes a promise about it.

Folia is out of scope too. Its regionised tick loop means genuinely concurrent chunk-ticket state,
and races there surface on other people's servers weeks later rather than in testing.

---

## Support expectations

Best effort, by one person, for free.

Supported in practice: **the current Minecraft release and the one before it.** Older versions in the
list above keep working and keep getting builds for as long as they build unchanged, but a bug that
only reproduces on 1.21.3 will sit behind one that reproduces on the current release.

Every published jar is built by CI from a tagged public commit, so "this jar came from this source"
is a claim you can check rather than take on trust.

---

## Redistribution and modpacks

**You may include ChunkCarts in a modpack, public or private, free or paid, without asking.** No
permission request, no credit requirement beyond the licence, no notification. A link back here is
appreciated so people can find the config documentation and report bugs against the real thing.

ChunkCarts is licensed under the **LGPL v3 or later** — see `COPYING.LESSER`. In short: redistribute
it, bundle it, ship it alongside proprietary plugins; if you modify ChunkCarts itself, those
modifications stay under the same licence.

**Forks must rename.** If you publish a modified build, call it something else and give it a
different mod id. The reason is specific rather than territorial: this mod's whole value is a safety
claim about its default behaviour, and a fork with the loop detector loosened would still be called
ChunkCarts, still answer `/chunkcarts list`, and still be what people's trust and bug reports
attach to. Rename it and the claim stays attached to the thing that actually makes it.

---

## Metrics

ChunkCarts reports anonymous statistics to [bStats](https://bstats.org). The payload is bStats'
standard environment fields, the ones every plugin on the server already sends: server software,
game version, player count, online-mode setting, Java version, host OS name/architecture/version,
and CPU core count. Alongside those, ChunkCarts reports its own version and which `enforcement` and
`departure-requirement` settings you use.

No player data, no world data, no IP addresses, no coordinates. The two settings are collected for
one reason — deciding which platforms and game versions are worth carrying, and whether anyone
actually uses permissive mode, from evidence instead of guesswork.

The server software sent is the brand the server gives itself, the same name its clients see. On
Paper and on a plain Fabric, NeoForge or Forge server that is just the platform's name; a hybrid
server running a loader jar reports its own name instead.

bStats' own Server Software chart only recognises Bukkit-family names, so a Fabric, NeoForge or
Forge server renders there as "Unknown". ChunkCarts reports a second `platform` chart naming which
ChunkCarts jar is running (Paper, Fabric, NeoForge or Forge), so those installs show up correctly
somewhere on the dashboard.

Set `metrics: false` to opt out, or disable bStats globally on your server.

---

## Building from source

```bash
./gradlew :core:test                        # the policy engine's test suite — fast
./gradlew :bukkit:test                      # the Paper command and listener
./gradlew :loader:fabric:26.3:test          # loader/common's tests, on the one node that runs them
./gradlew :bukkit:build                     # the Paper jar
./gradlew :loader:fabric:1.21.11:build      # one loader target
./gradlew :loader:buildAll                  # every loader target — slow, see below
```

Build **one loader target per Gradle invocation** unless the machine has plenty of memory to spare.
Each target keeps a decompiled Minecraft toolchain resident and ForgeGradle provisions JDKs of its
own; `buildAll` wants `--no-parallel --max-workers=1` at minimum.

The loader modules need both **JDK 21** (for the 1.21.x targets) and **JDK 25** (for 26.x, which
requires it). `core` and `bukkit` build on 21.

---

## License

Copyright (C) 2026 Aplite36

ChunkCarts is free software: you can redistribute it and/or modify it under the terms
of the GNU Lesser General Public License as published by the Free Software Foundation,
either version 3 of the License, or (at your option) any later version.

ChunkCarts is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
See the GNU Lesser General Public License for more details.

You should have received a copy of the GNU Lesser General Public License along with
ChunkCarts. If not, see <https://www.gnu.org/licenses/>.
