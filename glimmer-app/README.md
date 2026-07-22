# glimmer-app

A standalone example app for [glimmer](https://github.com/jolt-lang/glimmer) — a reagent-style reactive
GUI toolkit over GTK4 for the [Jolt](https://github.com/jolt-lang/jolt) Clojure
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
- **keyed list rendering** driven by a reaction — each row is keyed by task id,
  so adding, deleting, reordering (sort), and filtering reuse the right widgets
  instead of recreating by position.
- **interactive rows** — every row has its own toggle checkbox and delete button
  whose handlers close over the task id (not a list index), plus bulk mutations
  (`complete all` / `mark all active`, `clear completed`) and live counts.

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

## Develop it live from your editor

Start an nREPL server and connect your editor (Calva, CIDER, Cursive):

```sh
joltc nrepl-server        # writes .nrepl-port; ^C to stop
```

Then evaluate `(app.core/-main)` to open the window. The eval returns right away
and the window keeps running, so you can keep working in the same session. Change
the app the way you would with reagent: mutate a ratom (`(swap! ...)` / `(reset!
...)`) and the parts of the UI that deref it re-render in the live window. To
change a component's shape, redefine it and re-mount the root with
`(glimmer.core/on-gui #(...))`.

The GUI runs on the process main thread while your evaluations run on nREPL
worker threads. glimmer marshals every reactive re-render back onto the main loop
for you, since GTK (and AppKit on macOS) reject widget mutation off the main
thread.

## Build a standalone binary

```sh
joltc build -m app.core
./target/release/glimmer-app
```

The binary loads the GTK4/glib shared libraries at startup; they must be
installed (Homebrew on macOS: `brew install gtk4`).

## Design note

Task rows are keyed by task id (`[task-row {:key id} ...]`). glimmer's reconciler
matches keyed children by identity rather than position, so a row's widgets and
its once-wired signals follow the same task as the list is added to, deleted
from, reordered (the done-last sort), or filtered. That's what lets each row own
a toggle and delete handler bound to its id without ever capturing a stale index.
Signals are still wired once at mount; the handlers close over the id and the
root atom (stable), never over a position or a value. See glimmer's README for
how keyed reconciliation works.
