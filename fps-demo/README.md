# fps-demo

A first-person walk-through of the real **q1k3** level (the Slipgate Complex),
running in a `GtkGLArea` inside a GTK4 window. It is a port of the q1k3
JavaScript FPS to **glimmer-gl** (OpenGL behind glimmer's GTK4 reactive UI),
written in **jolt** (native Clojure — no JVM).

This is the Phase 2 vertical slice: free-look camera + full q1k3 player
physics (accel/friction, gravity, jumping) and voxel collision against the
actual level geometry, rendered with a textured, lit shader. Combat is
projectile-based, matching q1k3: the shotgun throws eight shell pellets, and
each enemy type fires its own projectile (grunt shells, enforcer plasma, ogre
grenades, zombie gibs) while hounds lunge for melee contact. Projectiles carry
gravity/friction/bounce and collide against the level and entity AABBs;
grenades explode with radial falloff. Enemies walk, collide, stair-step and turn
off ledges, and the world is lit by q1k3's dynamic point-light array (player
lamp, muzzle flash, projectile glow, explosion flashes). Still open (tracked as
beads issues): torches and other world objects, distinct per-type enemy meshes,
more weapons/pickups, the second level, and audio.

## Run

From this directory:

```sh
joltc -M:run                                       # opens a 640x480 window
FPS_DEMO_AUTO_QUIT_MS=3000 joltc -M:run            # smoke test: auto-close after 3s
```

Controls:

- **W A S D / arrow keys** — move (forward/back/strafe, q1k3 air+ground accel).
- **Space** — jump (edge-triggered, only from the ground).
- **mouse drag** — look around (drag-to-look, in lieu of pointer lock).

You spawn at the level's `info_player_start`, with collision against the
`128³` voxel set decoded from the level.

### Environment variables

- `FPS_DEMO_AUTO_QUIT_MS` — if set to a positive integer, the app closes itself
  after that many milliseconds (used by headless smoke tests in CI).
- `FPS_DEMO_HOLD` — pre-seed a held movement key for the smoke test so the
  input→accel→physics→position chain runs without a person at the keyboard
  (`W`, `A`, `S`, or `D`).
- `FPS_DEMO_SHOT` — path to dump one settled frame as raw RGBA (bottom-row
  first, `glReadPixels`), for headless visual verification. Convert to PNG with
  a tiny script (any raw-RGBA → PNG encoder; the drawable size is printed to
  stderr as `SHOT <w> <h> <path>`). `FPS_DEMO_SHOT_AT` sets the game-time
  (seconds) at which the frame is captured (default `1.5`).

## Headless sanity check

`fps-demo.check` has no GL or display dependency: it verifies the shader specs
emit GLSL, the textured geometry has the right vertex/stride counts and UV
bounds, the q1k3 level container decodes to a valid solid-cell set, the player
physics integrates correctly, and the `.rmf` model parser decodes vertices,
normals, UVs, and multi-frame bounds.

```sh
joltc -M:check
```

## How it's wired

- `fps-demo.core` — the app and `-main` entry point. Input is mapped into a
  game atom (`:keys`, look angles, physics state); the fixed `1/60 s` tick
  integrates player physics, and `on-render` draws the frame from the camera.
- `fps-demo.render` — the GL renderer. Builds the shader program and buffers on
  realize; this is the only place raw GL draw calls live.
- `fps-demo.player` — q1k3 player physics: wish-dir accel/friction (separate
  ground vs. air control), gravity, jump velocity, half-extents, and the
  drag-to-look angle update.
- `fps-demo.map` — decodes the q1k3 level container into blocks and builds the
  `128³` solid-cell voxel set used for collision.
- `fps-demo.entity` — the enemy AI FSM (idle/patrol/follow/attack/evade) and the
  per-tick folds `step-enemies` (collecting who fired) and `step-melee` (hound
  contact).
- `fps-demo.projectile` — the projectile simulation (shell/nail/plasma/grenade/
  gib): q1k3 physics, wall/entity collision, grenade bounce + area explosion,
  and the `player-shotgun` / `enemy-attack` spawn helpers. GL-free and tested in
  `check.clj`.
- `fps-demo.maps.l` — the Slipgate Complex (`m1`) level bytes.
- `fps-demo.textured` — interleaved pos/uv/normal box geometry.
- `fps-demo.shaders` — the lit/textured shader spec as plain data (the lit
  shader's prelude carries q1k3's dynamic point-light accumulation loop).
- `fps-demo.light` — the dynamic point-light buffer: distance fade + packing of
  up to 16 lights into the array the shader samples. GL-free and tested.
- `fps-demo.model` — the `.rmf` model parser (vertices, normals, UVs, frames),
  feeding Phase 3 combat meshes.

The native libraries — OpenGL (from glimmer-gl) and the GTK4/GLib stack (from
glimmer) — are pulled in transitively; `deps.edn` only lists the two local deps.
