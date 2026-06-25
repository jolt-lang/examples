# gl-demo

A small GTK4 app that renders an animated **fullscreen plasma shader** in a
`GtkGLArea`, driven by the **geom-gl** library. It exercises geom-gl's
shader/VAO plumbing and uniform uploads end-to-end.

The vertex shader builds a single oversized triangle from `gl_VertexID` — no
vertex buffer, no camera, no MVP — so it always fills the pane. The fragment
shader paints a domain-warped, palette-cycling field driven by `u_time`,
`u_scale`, and `u_warp`.

## Run

From this directory:

```sh
joltc -M:run
```

A window opens with a control panel above the GL pane:

- **Speed** — animation rate (0 freezes, 3 is fast).
- **Scale** — pattern frequency.
- **Warp** — domain-warp strength.
- **Pause / Resume** — freeze the clock.
- **Reset time** — zero `u_time`.

## Headless sanity check

`gl-demo.scene` (the GLSL sources) and `gl-demo.check` have no GL or display
dependency, so the sources can be sanity-checked without a window:

```sh
joltc -M:check
```

## How it's wired

- `gl-demo.scene` — the vertex/fragment GLSL: fullscreen-triangle vertex
  shader, IQ cosine palette, and a 4-octave domain-warped fragment shader.
- `gl-demo.gtk` — the GTK4 bindings glimmer omits: `GtkGLArea`, `GtkScale`,
  and `gtk_widget_add_tick_callback`.
- `gl-demo.core` — the app. The GLArea is imperative (its `realize`/`render`/
  `resize` signals build GL objects and `render` returns a gboolean), so it's
  driven with raw `foreign-callable` callbacks rather than glimmer's reactive
  reconciler. A tick callback advances `u_time` each frame and queues a redraw;
  GTK passes the area pointer to every tick, so the redraw target is always the
  mapped, realized widget.

The native libraries — OpenGL (from geom-gl) and the GTK4/GLib stack (from
glimmer) — are pulled in transitively; `deps.edn` only lists the two local deps.
