# glimmer-gl demo

A GTK4 app that renders a **lit gothic cathedral with real-time shadows** in a
`GtkGLArea`, driven by a reactive control panel. It exercises the two sibling
libraries together: the UI is **glimmer** (reagent-style hiccup over
`glimmer.ratom` cells), and the geometry, matrices, shader, and GL plumbing
come from **glimmer-gl**.

The whole window is one glimmer hiccup tree. `glimmer-gl.gtk` registers a
`:gl-area` and `:scale` widget into glimmer, so the slider panel and the GL
pane are reconciled together — no raw FFI in the app for the UI. The 3D world
is a *declarative scene tree* (a camera + light + cathedral built from
`glimmer-gl.scene` nodes); `on-render` flattens it to a render plan and hands
it to the renderer, which is the only place raw GL draw calls live.

## Run

From this directory:

```sh
joltc -M:run            # opens the window
GLIMMER_GL_DEMO_QUIT_MS=3000 joltc -M:run   # smoke test: auto-close after 3s
```

The control panel above the GL pane:

- **light azimuth / elevation** — sun direction (the shadow recasts live).
- **ambient** — fill light intensity.
- **shadow bias** — offsets the depth comparison to kill shadow acne.
- **camera orbit** — orbits the camera around the cathedral.
- **auto-rotate** — toggles continuous camera orbit.
- **paused** — freezes the frame clock.

## Headless sanity check

`gl-demo.check` has no GL or display dependency: it confirms both shaders emit
GLSL (the lit shader carries the shadow sampler and fog uniforms), the gothic
scene flattens to a valid render plan (meshes, triangles, lights, camera), all
materials resolve, and the `glimmer-gl.gtk` widgets register into glimmer.

```sh
joltc -M:check
```

## How it's wired

- `gl-demo.scene` — the shaders as data. `depth-spec` is the depth-only
  program used by the shadow pass; `lit-spec` is the lit program that samples
  the shadow map and applies fog + ambient. Both are plain maps; edit the
  GLSL fragments to change the look.
- `gl-demo.gothic` — the cathedral: floor, pillars, arches, and ornaments
  assembled as `glimmer-gl.scene` group nodes from `glimmer-gl.primitives`
  and `glimmer-gl.polyhedra` meshes, each tagged with a material
  (`:dark-stone`, `:glass`, `:gold`, `:ground`, `:stone`).
- `gl-demo.renderer` — the GL renderer. Builds the shadow framebuffer and
  both shader programs on realize; each frame runs a **shadow pass** (render
  the scene depth from the light's point of view into the shadow map) then a
  **lit pass** (draw the scene to the default framebuffer, sampling the
  shadow map). Mesh VBOs are cached and uploaded once per unique mesh.
- `gl-demo.core` — the app. The reactive panel is plain glimmer hiccup over
  `glimmer.ratom` cells. The GLArea is imperative, so its
  `on-realize`/`on-render`/`on-resize`/`on-tick` handlers are passed as fns
  on the `[:gl-area …]` element; `glimmer-gl.gtk` wires them to the GTK
  signals.

The native libraries — OpenGL (from glimmer-gl) and the GTK4/GLib stack (from
glimmer) — are pulled in transitively; `deps.edn` only lists the two local deps.
