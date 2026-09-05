
a compelling demo idea that combines Domino (for state logic) and Ebb (Jolt's version of Missionary, for effectful streaming) in a way that clearly showcases their combined power.

https://github.com/domino-clj/domino
https://github.com/jlt-commons/ebb

The core idea is to build a "Smart Dashboard for a Live Data Pipeline" . This demo would visually illustrate how Domino's predictable state management and Ebb's robust concurrency handle a realistic, dynamic data scenario.

🎯 Demo Concept: Live Pipeline Dashboard

The demo will be a dashboard that visualizes a stream of incoming data (e.g., local system readings like CPU, memory, network) in real-time similar to Apple Activity monitor. The dashboard isn't just a passive display; it allows users to interactively apply filters, set thresholds, and trigger actions.

🏗️ Architecture & The "Power" Demonstration

The demo would be built in three clear layers, each highlighting a strength of the libraries:

1. Data Ingestion Layer (Ebb's Strength: Streaming & Backpressure)

    What it does: An Ebb flow handles an incoming data stream. This stream emits data items at a variable rate, sometimes in bursts.

    The "Power" Shown:

        Backpressure: The dashboard will not crash or freeze, even during simulated data bursts. Ebb's backpressure naturally controls the flow, demonstrating system resilience.

        Cancellation: Users can "pause" the live stream. This cleanly cancels the Ebb flow, showing robust resource management.

        Transformation: Raw data is parsed and validated using Ebb's functional operators before being passed to the state layer.

2. Application State Layer (Domino's Strength: Pure Logic & Orchestration)

    What it does: Domino manages the application's state, which includes:

        The list of current data items.

        User-defined filters (e.g., "show only items > value X").

        Derived statistics (e.g., running total, moving average).

        Alert thresholds.

    The "Power" Shown:

        Declarative Logic: When a new data item arrives, a Domino event is transacted. This event triggers a pure cascade: the new item is added, totals are recalculated, and filter conditions are re-evaluated—all in one predictable, glitch-free transaction.

        Separation of Concerns: The UI simply renders the state from (:domino.core/db @ctx). Business logic (like calculating an average) lives in pure Domino events, not in UI components or side-effect handlers.

3. Interactive Control Layer (Domino + Ebb Integration)

    What it does: This layer connects user interactions to both state changes and external actions.

    The "Power" Shown:

        User sets a filter: A UI event calls domino.core/transact to update the :filter-threshold in the state. Domino's reactive graph automatically recalculates which items are displayed, demonstrating the power of its dataflow engine.

        User clicks "Export Data": A Domino effect, triggered by the state change (e.g., :export-requested? becomes true), returns an Ebb task. This task performs the asynchronous export (e.g., writing to a file or sending an HTTP request). Once the task completes, it can transact a success/failure status back into Domino.

        Alert Trigger: When a derived statistic (like the average) exceeds a threshold, a Domino effect returns an Ebb task to send a notification. If the notification fails, Ebb's error handling can retry, demonstrating sophisticated async flows driven by state.

📊 Visualizing the Demo's Impact

The demo interface could be a single page showing:

    Live Stream: A scrolling list of incoming items (controlled by Ebb).

    Dashboard Stats: Key metrics (computed by Domino) that update in real-time.

    Controls: Buttons and sliders to pause the stream, set filters, and trigger exports.

    Log/Events Panel: A console showing the sequence of Domino transactions and Ebb task completions, making the "plumbing" visible to the observer.

✨ Why This Demo is Convincing

    Solves a Real Problem: It models a common, non-trivial challenge in modern apps: handling live data with user interaction.

    Shows Composition: It doesn't just use Domino or Ebb; it demonstrates a clear, elegant integration pattern where each library does what it does best.

    Highlights Key Features: It explicitly showcases backpressure, cancellation, pure state management, and asynchronous side effects in a tangible way.

    Runs on Jolt: Using Ebb makes this demo relevant for the Jolt ecosystem, showing how Clojure principles can be applied in this environment.

To build this, you would structure the project as a single Jolt application with namespaces for the Domino schema, the Ebb stream definitions, and a simple UI layer (which could be a Reagent-like wrapper or a simple terminal-based display).
