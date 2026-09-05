# reactive-dashboard

A live system monitor for jolt that renders in the browser over server-sent
events. CPU, memory and network figures come out of `/proc`, so what you see on
the page is what the machine is really doing. You can also make it sweat from
the controls, which spawn actual processes rather than faking a spike.

Three libraries split the work. [ebb](https://github.com/jlt-commons/ebb), the
jolt port of missionary, handles streaming and concurrency.
[domino](https://github.com/domino-clj/domino) keeps the state logic as a
data-flow graph. [glimmer-datastar](https://github.com/jolt-lang/glimmer-datastar)
pushes a re-rendered fragment to the page whenever that state changes.

## Running it

```
jolt serve       # http://127.0.0.1:3000
jolt -M:test     # 34 tests, a few of which spawn real child processes
jolt build -m app.core
```

Open the page and you'll find a sparkline per metric, a pressure gauge, an
alert banner and a rail of controls along the bottom. Four panels sit on the
right. **Lanes** reports what each ingestion lane is up to, **Cascade** lists
the paths the last transaction wrote in execution order, **Model** draws the
event graph straight from the schema and lets you drag along it or scroll to
zoom, then **Log** interleaves domino transactions with ebb task lifecycle so
the plumbing stays visible. That same
graph is served as mermaid source at `/model.mmd`.

## What it demonstrates

**Backpressure, resolved two ways.** Two lanes read the same 50Hz producer
under different rules. Lane A pushes through `m/observe` into `m/relieve`, so
the producer never waits and a slow consumer simply loses samples, which the
page counts for you. Lane B pulls one line per unit of demand through
`m/via m/blk`, so nothing is dropped and the pressure lands on the OS pipe
instead. Drag **consumer delay** up to 150ms, then watch lane A's dropped count
climb while lane B's producer stalls hundreds of samples behind real time.

**Cancelling that reaches the operating system.** Hit *pause lane A*. The flow
is cancelled, its cleanup destroys the child process, and the pid disappears
from the lane card. Moving the **sample interval** slider does the same to the
polling lane, then starts it again at the new rate. Nothing here flips a flag
and hopes for the best.

**One transaction, a whole derivation.** Each sample arrives as a single domino
transact, and from there the cascade runs from raw sample to window stats to a
composite pressure index to an alert level, all in one pass. The Cascade panel
shows exactly which paths it wrote. Thresholds and the window are model paths
too, so dragging those sliders re-runs the same pure events without a new
sample arriving.

**Effects that ask instead of act.** A domino effect never performs IO. It
posts a request onto an ebb mailbox, one supervisor fiber drains that mailbox
and spawns the matching task, and each task transacts its own result back.
Alert delivery retries with backoff against a sink whose failure rate you
control, and every attempt lands in the banner. The export writes in chunks, so
you can cancel it halfway and find nothing half-written left on disk.

**Degrading instead of hanging.** Flip *probe: fast* over to *probe: slow* and
the enrichment probe starts taking three seconds against a 250ms timeout. That
one metric goes stale on the page, but sampling carries on at full rate.

**Real load.** The burst control spawns `yes` processes. CPU genuinely climbs,
the pressure index crosses the threshold on its own, then the alert path fires
for an honest reason.

## How it's wired

```
        ebb                      domino                    datastar
 ┌───────────────────┐   ┌────────────────────┐   ┌──────────────────┐
 │ lane A  push      │   │ sample             │   │                  │
 │ lane B  pull      ├──▶│   → history        │   │  SSE re-render   │
 │ lane C  poll      │   │   → window stats   ├──▶│  of one fragment │
 └───────────────────┘   │   → pressure index │   │                  │
          ▲              │   → alert level    │   └──────────────────┘
          │              └─────────┬──────────┘
          │                        │ effect
          │                        ▼
          │                 ┌─────────────┐
          └─────────────────┤  m/mbx bus  │
           transact result  └──────┬──────┘
                                   │
                            ┌──────▼──────────────────┐
                            │ supervisor fiber        │
                            │ alert retry, export,    │
                            │ burst, retune           │
                            └─────────────────────────┘
```

- `app.pipeline` is the ebb layer. It owns the three lanes plus a supervisor
  that holds each lane's canceller and child process handle.
- `app.state` is the domino layer, where the model, the events and the effects
  live. Nothing in it does IO, spawns a fiber or reads a clock.
- `app.tasks` is the effectful edge, so the retrying alert delivery, the
  cancellable export and the load generator all sit there.
- `app.ui` and `app.core` render hiccup from one published snapshot, then serve
  it through ring.
- `app.metrics` and `app.diagram` are pure. One parses `/proc` text, the other
  lays out the event graph that the Model panel draws.

Domino stays pure and synchronous, which leaves every fiber, retry, timeout and
cancel on the ebb side. The UI is a function of the published snapshot and
nothing else.

## Streamed and polled

Two of the lanes are genuinely pushed. A child process writes one line per
sample into a pipe, and ebb reads that pipe as a flow. Lane C works differently,
because procfs builds its files at read time and leaves nothing to subscribe to.
The kernel does offer a real push through PSI at `/proc/pressure`, but arming a
trigger needs privileges this example doesn't assume. So lane C polls on a
timer, and its card on the page says as much.

| lane | how it reads | what you see |
|---|---|---|
| A | `m/observe` into `m/relieve` | the producer runs free, and a slow consumer drops samples |
| B | `m/via m/blk` per demand | nothing is dropped, so the producer stalls instead |
| C | `m/ap` with `m/sleep` | the poll loop feeding the dashboard's metrics |

## Configuration

`config.edn` is read at runtime, so keep it next to the binary. It holds
`:port`, `:sample-interval-ms`, `:window`, `:consumer-delay-ms`,
`:warn-threshold`, `:crit-threshold`, `:alert-sink-failure-rate`,
`:alert-max-attempts`, `:publish-hz` and `:export-dir`.

The server runs the fiber strategy. Every open tab holds an SSE stream for as
long as it stays open, and a worker pool would fill up after a handful of
viewers.

## Tests

```
jolt -M:test
```

The parser tests and the diagram layout check are pure. `app.state-test` drives
the cascade with fixed inputs and touches no IO at all. `app.pipeline-test`
spawns real producers to prove that dropping, throttling and reaping all behave.
`app.tasks-test` covers the retry loop, a cancelled export, the spinner
processes and the supervisor's ordering. `app.ui-test` renders the page from a
fixture db.
