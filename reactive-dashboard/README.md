# reactive-dashboard

A live system monitor for jolt that renders in the browser over server-sent
events. CPU, memory and network figures come out of `/proc`, so the page shows
what the machine is doing. The burst control spawns real processes rather than
simulating load.

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

The page shows a sparkline per metric, a pressure gauge, an alert banner and a
rail of controls along the bottom. Four panels sit on the
right. **Lanes** reports what each ingestion lane is up to, **Cascade** lists
the paths the last transaction wrote in execution order, **Model** draws the
event graph straight from the schema and lets you drag along it or scroll to
zoom, then **Log** interleaves domino transactions with ebb task lifecycle so
the plumbing stays visible. That same
graph is served as mermaid source at `/model.mmd`.

## What it demonstrates

**Backpressure, resolved two ways.** Two lanes read the same 50Hz producer
under different rules. Lane A pushes through `m/observe` into `m/relieve`, so
the producer never waits and a slow consumer loses samples; the page counts
the drops. Lane B pulls one line per unit of demand through
`m/via m/blk`, so nothing is dropped and the pressure lands on the OS pipe
instead. Drag **consumer delay** up to 150ms, then watch lane A's dropped count
climb while lane B's producer stalls hundreds of samples behind real time.

**Cancelling that reaches the operating system.** Hit *pause lane A*. The flow
is cancelled, its cleanup destroys the child process, and the pid disappears
from the lane card. Moving the **sample interval** slider does the same to the
polling lane, then starts it again at the new rate. Stopping works by cancellation,
not by a flag the loop polls.

**One transaction, a whole derivation.** Each sample arrives as a single domino
transact, and from there the cascade runs from raw sample to window stats to a
composite pressure index to an alert level, all in one pass. The Cascade panel
shows exactly which paths it wrote. Thresholds and the window are model paths
too, so dragging those sliders re-runs the same pure events without a new
sample arriving.

**Effects enqueue requests.** A domino effect never performs IO. It posts a
request onto an ebb mailbox, one supervisor fiber drains that mailbox and
spawns the matching task, and each task transacts its own result back. Alert
delivery retries with backoff against a sink whose failure rate is a slider,
and each attempt is shown in the banner. The export writes in chunks, so
cancelling it halfway leaves no partial file on disk.

**Degrading instead of hanging.** Switch the probe from *fast* to *slow* and
the enrichment step takes three seconds against a 250ms timeout. That one
metric goes stale on the page, but sampling continues at full rate.

**Real load.** The burst control spawns `yes` processes. The CPU readings
rise because the machine is busier, the pressure index crosses the threshold,
and the alert fires on measured data rather than a scripted value.

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

Two of the lanes are push-driven. A child process writes one line per sample
into a pipe, and ebb reads that pipe as a flow. Lane C is different: procfs
builds its files at read time and leaves nothing to subscribe to. The kernel
can push pressure events through PSI at `/proc/pressure`, but arming a trigger
requires privileges, so lane C polls on a timer and its lane card is labeled as
a poll loop.

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

The server runs the fiber strategy. Each open tab holds an SSE stream until
the tab closes, and a worker pool would be fully subscribed by a handful of
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
