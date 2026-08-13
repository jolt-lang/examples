# image-dump-example

A reactive todo board that saves and reloads **the whole running program** —
an example of [`jolt.image`](https://jolt-lang.github.io/docs/rfc/0009-program-image-dump-restore.html),
built with [glimmer](https://github.com/jolt-lang/glimmer), a reagent-style
reactive GUI toolkit for the [Jolt](https://github.com/jolt-lang/jolt) Clojure
dialect, rendering through its GTK4 backend,
[glimmer-gtk](https://github.com/jolt-lang/glimmer-gtk).

## Saving the world

The navbar has **save image** and **load image**. They do not save a variable —
they save the *program*, the way a Smalltalk image or Common Lisp's
`save-lisp-and-die` does:

```clojure
(jolt.image/dump-world! "todos.jimg" ["app.core"])
(jolt.image/restore-world! "todos.jimg")
```

Nothing in `app/persist.clj` lists what the state consists of. `dump-world!`
walks the var table and writes every var's root, so adding a new `def` to
`app.core` tomorrow puts it in the image without touching the saving code.

Two things make that affordable on a runtime with no heap dump.

**Code does not travel.** A var holding a function is skipped — the process
reading the image is the same build and already has every `defn`, protocol impl
and multimethod. Only data moves.

**What can't be written gets a handler.** glimmer's reactive cells are tagged
maps holding watch closures, and a reaction holds its body function; an anonymous
closure has no name to write. So `app.persist` registers a handler that writes a
cell as its current value, and an after-restore hook re-derives the cursors and
reactions from the restored root and re-renders. That is the same shape as
Common Lisp's `*save-hooks*` / `*init-hooks*` pair: quiesce on the way out,
rebuild on the way in.

## Why this isn't just EDN

The board deliberately holds things a data format cannot carry, so that
restoring it is a real test rather than a pretty-printed map:

- **`Task` records** come back as `Task`, not as maps — `(instance? Task t)` still
  holds, so protocol dispatch keeps working.
- **A live function** sits in `:filter-fn`. The filter buttons store `any?`,
  `active?` or `done?` *as functions*; after a load the stored one is the same var
  and is immediately callable.
- **`:index` shares the very same `Task` objects** as `:tasks`. After a restore
  `(identical? (get index id) ...)` is still true — one object, two ways in, not
  two equal copies.
- **The undo history shares structure** with the boards it came from, and the
  image preserves that sharing instead of writing N independent copies.

EDN would flatten every one of those: records become maps, the function becomes
unprintable, and shared objects become duplicates.

## What else it showcases

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
image-dump-example/
├── deps.edn             ; :local/root ../../glimmer + ../../glimmer-gtk (source + GTK4 native libs)
└── src/app/
    ├── core.clj         ; state, cursors, reactions, mutations, run
    ├── persist.clj      ; save/load the world: the cell handler + the rebuild hook
    └── widgets.clj      ; Form-1 reusable components, incl. the navbar
```

## Run it

```sh
jolt -M:run        # or: jolt run
```

This opens the window and blocks until you close it.

## Develop it live from your editor

Start an nREPL server and connect your editor (Calva, CIDER, Cursive):

```sh
jolt nrepl-server        # writes .nrepl-port; ^C to stop
```

Then evaluate `(app.core/-main)` to open the window. The eval returns right away
and the window keeps running, so you can keep working in the same session.

Two kinds of edits show up live, both in the same window:

- **State**, the reagent way: mutate a ratom (`(swap! ...)` / `(reset! ...)`) and
  the parts of the UI that deref it re-render.
- **Component code**: redefine a component function, re-evaluate it, then call
  `(glimmer.core/reload!)` to re-render the running window in place.
  `reload!` re-runs the root and re-resolves the child components it renders, so
  redefined children take effect. To swap the root itself after redefining it,
  pass it: `(glimmer.core/reload! app.core/todo-app)`. Reloading rebuilds the
  tree, so the task list resets to its defaults.

The GUI runs on the process main thread while your evaluations run on nREPL
worker threads. glimmer marshals every re-render (reactive updates and `reload!`)
back onto the main loop for you, since GTK (and AppKit on macOS) reject widget
mutation off the main thread.

## Build a standalone binary

```sh
jolt build -m app.core
./target/release/image-dump-example
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
