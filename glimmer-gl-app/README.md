# glimmer-gl demo

A GTK4 app that renders a rotating 3D solid — cube, sphere, or tetrahedron —
painted with **two composed procedural shaders** (a domain-warped plasma and
animated gold stripes) in a `GtkGLArea`, with a reactive control panel. It ties
the two sibling libraries together: the UI is built with **glimmer**
(reagent-style hiccup components), and the geometry, matrices, shader, and GL
plumbing come from **glimmer-gl**.

The whole window is one glimmer hiccup tree. `glimmer-gl.gtk` registers a
`:gl-area` and `:scale` widget into glimmer, so the slider panel and the GL pane
are reconciled together; there is no raw FFI in the app for the UI. The mesh is
composed from `glimmer-gl.primitives` data, tessellated by `glimmer-gl.mesh` to
an interleaved position+normal buffer, and drawn with a model-view-projection
wound from `glimmer-gl.matrix`. The shader is **data**: `gl-demo.scene` defines a
plasma module and a stripes module — each a map with its own uniforms and GLSL
function — and combines them with `glimmer-gl.shader/merge-specs` into one
program whose uniforms are set by name. The **Blend** slider mixes between the
two effects.

## Run

From this directory:

```sh
joltc -M:run
```

A window opens with a control panel above the GL pane:

- **Cube / Sphere / Tetra** — pick the solid (the active one is greyed out). The
  VBO is rebuilt from glimmer-gl primitives when you switch.
- **Speed** — rotation / plasma animation rate (0 freezes).
- **Zoom** — model scale.
- **Scale** — plasma pattern frequency.
- **Warp** — plasma domain-warp strength.
- **Blend** — mix between the plasma and the stripe shader (0 = plasma, 1 = stripes).
- **Smooth shading** — per-vertex averaged normals (rounded) vs per-face normals
  (faceted).
- **Pause / Resume** — freeze the clock.

## Headless sanity check

`gl-demo.check` has no GL or display dependency: it confirms the composed shader
renders to GLSL (palette + plasma snippets present, declarations generated), the
glimmer-gl geometry pipeline produces a well-formed vertex buffer for each shape,
and the glimmer-gl.gtk widgets register into glimmer.

```sh
joltc -M:check
```

## How it's wired

- `gl-demo.scene` — the shader as data. A `base` (vertex stage + framing
  uniforms), a `plasma-module` and a `stripes-module` (each a map with its own
  uniforms and a GLSL function), and a `main-module` that blends the two by
  `u_mix` and lights the result, combined with `merge-specs`. Drop a module from
  the merge, or add a third, to change the look — it's just data.
- `gl-demo.core` — the app. The reactive panel (`control-panel`, `slider`,
  `shape-button`) is plain glimmer hiccup over `glimmer.ratom` cells. The GLArea
  is imperative, so its `on-realize`/`on-render`/`on-resize`/`on-tick` handlers
  are passed as fns on the `[:gl-area …]` element; `glimmer-gl.gtk` wires them to
  the GTK signals. `on-realize` builds the program/VAO/VBO and uploads the mesh;
  `on-render` rebuilds the VBO when the shape or shading changes, then sets the
  uniforms by name and draws; `on-tick` advances the clock (rotation + plasma
  time) and queues a redraw each frame.

The native libraries — OpenGL (from glimmer-gl) and the GTK4/GLib stack (from
glimmer) — are pulled in transitively; `deps.edn` only lists the two local deps.
