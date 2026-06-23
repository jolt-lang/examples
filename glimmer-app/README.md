# glimmer-app

A standalone example app for [glimmer](../../glimmer) — a reagent-style reactive
GUI toolkit over GTK4 for the [Jolt](https://github.com/jolt-lang) Clojure
dialect. It exercises the whole feature set in one cohesive app: a reactive task
board.

## What it showcases

- **reactive atom** — one `atom` holds the entire board (tasks, filter, sort
  flag, draft, next id); it's the single source of truth.
- **cursors** — writable lenses over `[:draft]`, `[:filter]`, and
  `[:sort-done-last]`; writing a cursor updates the root atom and everything
  derived from it.
- **reactions** — read-only derived cells: `remaining`, `done-count`, and the
  `visible` task list (depends on the filter and sort cells at once).
- **Form-1 components** (`app.widgets`) — `stat-card`, `filter-bar`,
  `command-bar`; plain functions returning hiccup.
- **Form-2 component** (`app.core/task-board`) — creates state, cursors, and
  reactions once on mount, then renders from them.
- **every event kind** — `:on-change` and `:on-activate` (entry), `:on-click`
  (buttons), `:on-toggled` (checkbutton).
- **list rendering** driven by a reaction, with live counts and bulk mutations
  (`complete all`, `clear done`).

## Layout

```
glimmer-app/
├── deps.edn             ; :local/root ../../glimmer inherits its source + GTK4 native libs
└── src/app/
    ├── core.clj         ; Form-2 root: state, cursors, reactions, mutations, run
    └── widgets.clj      ; Form-1 reusable components
```

## Run it

```sh
joltc -M:run        # or: joltc run
```

This opens the window and blocks until you close it.

## Build a standalone binary

```sh
joltc build -m app.core
./target/release/glimmer-app
```

The binary loads the GTK4/glib shared libraries at startup; they must be
installed (Homebrew on macOS: `brew install gtk4`).

## Design note

Task rows are rendered read-only. glimmer's reconciler is positional and wires
signals once at mount, so a row handler can't safely capture a per-item identity
once filtering can shrink or reorder the list. All mutation therefore flows
through global controls that close over the root atom. See glimmer's README for
the reconciler limitation and the keyed-children roadmap.
