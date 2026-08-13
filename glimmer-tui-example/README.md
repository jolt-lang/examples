# glimmer-tui-example

A small reactive terminal app built with
[glimmer](https://github.com/jolt-lang/glimmer) and its terminal backend,
[glimmer-tui](https://github.com/jolt-lang/glimmer-tui), on
[Jolt](https://github.com/jolt-lang/jolt). A filterable list of jolt libraries
with a detail pane and a live uptime counter, in about a hundred lines.

```
 jolt libraries  3 of 8                                              up 12s

 filter: gl________________________________________________________________

 ┌ libraries ───────┐┌ detail ─────────────────────────────────────────────┐
 │[ glimmer ]       ││ glimmer                                             │
 │[ logging ]       ││ reactive UI toolkit; you are looking at it           │
 │[ instaparse ]    ││                                                     │
 └──────────────────┘└─────────────────────────────────────────────────────┘

 [ Clear filter ]  [ Quit ]

 tab: move   enter/space: press   click: select   ctrl-c: quit
```

## Running

```sh
jolt run     # or jolt -M:run
```

Nothing to install: the backend binds the ncurses that macOS and every Linux
already ship, and `deps.edn` pulls glimmer-tui (and glimmer through it) from git.

Tab moves the focus, Enter or Space presses the focused widget, typing goes to
the focused entry, clicking selects a row, and ctrl-c or the Quit button exits.

## What it showcases

- **one reactive atom** holding all of the UI state (query, selection, uptime),
  with **cursors** as writable lenses over `[:query]` and `[:selected]`.
- **a reaction** — `matches` recomputes the filtered list when the query changes,
  and both the row list and the header count read it.
- **Form-2 and Form-1 components** — `app` creates the state, cursors, reaction
  and background thread once on mount; `library-row` and `detail` are plain
  functions of their arguments.
- **keyed rows** — each row is keyed by library name, so filtering reuses the
  right widgets instead of rebuilding the list by position, and the keyboard
  focus stays on the row you were looking at.
- **an off-thread state change** — a background thread ticks the uptime counter
  every second. Writing a reactive cell from another thread is safe: glimmer
  marshals the re-render onto the thread that owns the terminal, the same way it
  does for an nREPL eval.
- **portability** — only the `glimmer-tui.core` require names a toolkit. Swap it
  for `glimmer-gtk.core` and the same components render as GTK widgets.

## Notes

The library list is deliberately short. glimmer-tui has no scrolling viewport
yet, so a list whose natural height exceeds the terminal pushes whatever follows
it off the bottom rather than scrolling.
