(ns app.persist
  "Saving and reloading the running program, the way a Smalltalk image or a
  Common Lisp `save-lisp-and-die` does it: you do not name the one value you
  remembered to keep, you save the world and get it back.

  `jolt.image/dump-world!` walks the var table and writes every var's root.
  Nothing here lists what the application's state consists of — add a new
  `def` to app.core tomorrow and it is in the image without this file changing.

  Two things make that work on a runtime with no heap dump:

  Code does not travel. A var holding a function is skipped, because the process
  reading the image is the same build and already has every `defn`, protocol
  impl and multimethod. Only data moves.

  Things that cannot be written get a handler. glimmer's reactive cells are
  tagged maps holding watch closures and, for a reaction, its body function —
  and an anonymous closure has no name to write. So a cell is written as its
  current VALUE, and the after-restore hook below rebuilds the live graph."
  (:require [glimmer.ratom :as ratom]
            [glimmer.core :as ui]
            [jolt.image :as jimg]))

(def image-path "todos.jimg")

(defn reactive-cell? [x]
  (and (map? x) (contains? x :glimmer/kind)))

;; Written form of a cell is just its kind and its current value. A cursor or a
;; reaction is derived, so its value is not worth keeping — it is recomputed from
;; the root on the other side. Only the root ratom's value actually matters.
(defn- dump-cell [c]
  {:kind (:glimmer/kind c)
   :value (when (= :ratom (:glimmer/kind c)) (ratom/deref c))})

(defn- restore-cell [d]
  (if (:kind d)
    (ratom/atom (:value d))
    ;; not ours — handlers are tried in order and the first that accepts wins
    (throw (ex-info "not a glimmer cell" {:data d}))))

(defonce ^:private installed
  (do
    (jimg/register-handler! reactive-cell? dump-cell restore-cell)
    true))

;; The derived cells come back from the handler as plain stand-in ratoms, because
;; a cursor's link and a reaction's body are closures the image cannot carry.
;; This is the `after-restore` half of the Common Lisp save-hook/init-hook pair:
;; rebuild what could not travel, then re-render against it.
(defn install-rebuild-hook!
  "Register REBUILD-FN to run after a world restore, then re-render the window.
  app.core passes the function that re-derives its cursors and reactions."
  [rebuild-fn]
  (jimg/add-after-restore-hook!
    (fn []
      (rebuild-fn)
      (ui/reload!)))
  nil)

(defn save!
  "Write the whole application world to `image-path`. Returns a status string."
  []
  (try
    (jimg/dump-world! image-path ["app.core"])
    (str "saved the image to " image-path)
    (catch Exception e
      (str "save failed: " (ex-message e)))))

(defn load!
  "Restore the application world from `image-path`. Returns a status string.
  The after-restore hook rebuilds the reactive graph and re-renders."
  []
  (try
    (let [n (jimg/restore-world! image-path)]
      (str "restored " n " vars from " image-path))
    (catch Exception e
      (str "load failed: " (ex-message e)))))

(defn check
  "What the image cannot write, without writing anything — a vector of
  {:path :object}. Empty means the world is saveable."
  []
  (jimg/scan-world ["app.core"]))
