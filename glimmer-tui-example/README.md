# glimmer-tui-example

A reactive terminal app built with
[glimmer](https://github.com/jolt-lang/glimmer) and its terminal backend,
[glimmer-tui](https://github.com/jolt-lang/glimmer-tui), on
[Jolt](https://github.com/jolt-lang/jolt). A browser for the jolt libraries: a
filterable list, a scrolling detail pane, a table of releases, a progress bar
driven by a timer, and a modal dialog — in about 350 lines, most of which are
data and comments.

```
 ⠋  jolt libraries  3 of 12  up 1s

 filter  gl                                                       [ ] native only

 [ glimmer-tui ]

 ╭ libraries ─────────────╮ ╭ about — line 13 ──────────────────────────────────╮
 │   glimmer              │ │ It is also why the footer below is still on scre│ │
 │   pure                 │ │ node two sizes: what it would like, and what it █ │
 │   glimmer-gtk          │ │ can live on nothing, so when the window is short│ │
 │   native               │ │ instead of pushing the help bar off the bottom. │ │
 │ ▸ glimmer-tui          │ │                                                 │ │
 │   native               │ │ ●○○   [ and ] change tab                          │
 ╰────────────────────────╯ ╰───────────────────────────────────────────────────╯

 ────────────────────────────────────────────────────────────────────────────────
 ↑/k line up  ↓/j line down  ctrl+u …  p pin  i install  d remove  ? keys  q quit
```

## Running

```sh
jolt run     # or jolt -M:run
```

Nothing to install: the backend binds the ncurses that macOS and every Linux
already ship, and `deps.edn` pulls glimmer-tui (and glimmer through it) from git.

It does want **jolt 0.7.24 or newer**. Earlier binaries export the ncurses the
Chez kernel is linked against, which takes priority over the one the backend
loads, and `initscr` then fails with "Error opening terminal" or segfaults —
[jolt#728](https://github.com/jolt-lang/jolt/pull/728). Nothing to work around;
it wants the newer jolt.

| Key | Does |
|---|---|
| `Tab` / `Shift-Tab` | move the focus; `Enter` / `Space` press what has it |
| `↑` `↓` `j` `k` `pgup` `pgdn` `g` `G` | drive whatever is focused — the list, the table, the about pane |
| typing | goes to the filter field when it has the focus (`ctrl-w`, `ctrl-u`, `alt-b` … all work) |
| `p` | pin or unpin the selected library |
| `i` | fake-install it, on a timer |
| `d` | a modal confirm dialog; `Esc` closes it |
| `[` `]` | change tab |
| `?` | a panel of the keys currently in effect |
| `q` / `ctrl-c` | quit — unless you are typing in the filter, where `q` is a q |
| mouse | click to select a row or press a button; wheel scrolls what is under it |

## What it showcases

**Reactive state.** One `atom` holds the whole UI state; `cursor`s are writable
lenses over its parts, and `matches` is a `reaction` — a derived cell recomputed
when the query or the native-only toggle changes, which both the list and the
header count read.

> Cursors are built **once**, in `app`'s outer `let`, and passed down. A cursor
> subscribes to the atom it lenses, so building one inside a component adds a
> subscription on every render: the UI gets slower with every keystroke and
> eventually re-renders itself in a loop. Same rule as Reagent.

**Form-2 and Form-1 components.** `app`'s outer fn runs once on mount and creates
the state, the cursors, the reaction, the timers and the background thread; the
inner fn is what re-renders. `header`, `sidebar`, `detail` and the rest are plain
functions of their arguments.

**Keyed rows.** The pinned row is keyed by library name, so pinning and unpinning
reuse each button's widget instead of rebuilding the row by position — which is
what keeps the keyboard focus where it was.

**Two kinds of timer.** `tui/every!` runs a thunk on the loop thread, which is
what animates the spinner and the install bar with nobody at the keyboard, and
`tui/after!` clears the bar a beat after it fills. For contrast the uptime counter
is a background thread writing a reactive cell: that is safe too, because glimmer
marshals the repaint onto the thread that owns the terminal.

**Widgets that hold their own state.** The `:listbox` keeps a cursor and a scroll
offset, the `:table` keeps a row cursor, the `:entry` keeps an edit buffer and a
caret. In every case the *handler* owns the value — `:on-select` writes the cell
the component reads and `:selected` comes back down as a prop — so the widget and
the component never disagree.

**Scrolling that does not push things off the screen.** The about pane is a
`:scroll`, and the footer below it is still visible. Layout gives every node two
sizes: what it would like, and what it can live on. A scroll can live on nothing,
so a window too short for everything takes the rows from the scroll rather than
from the help bar.

**Keys that route.** A key goes to the focused widget, then outwards through its
containers, and only what nobody wanted reaches the application. That is why `j`
moves the list when the list has the focus and scrolls the about pane when the
pane does, why `p` and `i` are application keys everywhere *except* inside the
filter field, and why `q` can quit while still being a letter you can type.
`:autofocus` on the list is what makes those keys live at startup instead of
after the first Tab.

**Overlays, both kinds.** The confirm dialog is modal: Tab cannot leave it and
`Esc` closes it through `:on-close`. The `?` panel is not, because it is showing
the bindings of the widget behind it and taking the focus would defeat that.

**A help bar that cannot drift.** `[:help {}]` has no bindings of its own — it
renders whatever the focused widget answers to, read off the live tree. Change a
widget's `:keys` and the bar changes with it.

**Colour and borders.** Accents are hex (`"#7aa2f7"`), reduced to a palette index
and then to whatever this terminal actually has, so the same source is legible on
a 256-colour terminal and an eight-colour one. Frames use `:rounded`, the dialog
`:double`, the help panel `:thick`.

**Portability.** Only the `glimmer-tui.core` require names a toolkit. Swap it for
`glimmer-gtk.core` and the same components render as GTK widgets.
