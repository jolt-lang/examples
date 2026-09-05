(ns app.bus
  "The seam between the pure state layer and the effectful one.

  Domino effects never do IO. They post a request map here; `app.tasks` runs a
  supervisor fiber that drains the mailbox and spawns the matching Ebb task,
  and each task transacts its result back into Domino.

  One rule governs how that post happens, and it comes from ebb's ADR-001: a
  mailbox post hands the value straight to a waiting consumer and DRIVES that
  process until it parks again, on the posting thread. So a post is not a
  fire-and-forget send -- it runs the supervisor's next step inline, and if
  that step needs a lock the poster is holding, both sides stop forever.

  Effects run inside `app.state/transact!`, which holds the write lock, and
  supervisor handlers routinely transact. So during a transaction `request!`
  only COLLECTS; `app.state/transact!` posts the collected requests after it
  has released the lock."
  (:require [ebb.core :as m]))

(defonce ^{:doc "Requests from domino effects to the ebb supervisor."}
  requests (m/mbx))

(def ^:dynamic ^{:doc "When bound, requests are collected here instead of
  posted -- see the namespace docstring."}
  *collector* nil)

(defn request!
  "Post a request, or collect it if a transaction is in progress."
  [req]
  (if-let [collector *collector*]
    (swap! collector conj req)
    (requests req))
  nil)

(defn post-all!
  "Post collected requests. Must be called with no lock held."
  [reqs]
  (doseq [req reqs] (requests req))
  nil)
