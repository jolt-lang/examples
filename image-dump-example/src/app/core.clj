(ns app.core
  "A standard TodoMVC built with glimmer — a dynamic list of todos you can add,
  check off, delete, filter, and bulk-clear, all reactive.

  State model — one reactive atom is the source of truth, with focused views
  derived from it. It lives in top-level defonce cells (not inside a component)
  so REPL hot-reload preserves it: redefine components and call
  glimmer.core/reload! to re-render the running window against the same state.
    - atom     : the whole app (todos, filter, sort flag, draft, next id)
    - cursors  : writable lenses over [:draft], [:filter], [:sort-done-last]
    - reactions: read-only derived cells — remaining, done count, visible todos

  Components — Form-1 leaves live in app.widgets; the root render lives here and
  reads the shared reactive cells. Events — :on-change / :on-activate (entry),
  :on-click (buttons), :on-toggled (checkbutton) all appear.

  Rows are keyed by todo :id and interactive: each carries its own toggle and
  delete handlers, bound to that id. glimmer's keyed reconciler matches rows by
  :key, so a row's widgets and once-wired signals follow its todo across
  add/remove/reorder/filter instead of capturing a stale position — that is what
  lets the list grow, shrink, and reorder safely while you interact with it."
  (:require [clojure.string :as str]
            [glimmer.ratom :refer [atom cursor reaction]]
            [glimmer.core :as ui]
            [app.persist :as persist]
            [app.widgets :as w]))

(defrecord Task [id text done])

;; The three filters are named functions, so they can live in the state itself
;; and be written to the image by name.
(defn any?    [_] true)
(defn active? [t] (not (:done t)))
(defn done?   [t] (boolean (:done t)))

(defn- title []
  [:label {:markup [:span {:size "30000" :weight "bold" :foreground "#b83f45"} "todos"]
           :halign :center :margin-top 16 :margin-bottom 8}])

;; Toggle-all chevron + the add entry, on one row. The chevron flips every todo
;; between done and active; the entry adds on Enter (or the add button).
(defn- add-bar [total all-done? toggle-all draft-cursor on-add]
  [:hbox {:spacing 8 :valign :center :margin-start 14 :margin-end 14 :margin-bottom 6}
   [:button {:label     "⌄"
             :on-click  toggle-all
             :sensitive (pos? total)
             :tooltip   (if all-done? "mark all active" "mark all complete")
             :valign    :center}]
   [w/command-bar draft-cursor on-add]])

(defn- empty-state [total filter-val]
  [:label {:markup [:span {:foreground "#8e939d"}
                    (if (zero? total)
                      "No todos yet — add one above to get started."
                      (str "No " filter-val " todos — try another filter."))]
           :halign :center :margin 24}])

(defn- todo-list [visible total filter-val toggle-todo delete-todo]
  [:scrolled {:vexpand true
              :margin-start 14 :margin-end 14}
   [:vbox {:spacing 4 :margin 4}
    (if (seq visible)
      (for [{:keys [id text done]} visible]
        ;; {:key id} matches this row to its todo by identity; the leading map is
        ;; stripped before task-row is called. Handlers close over id, not index.
        [w/task-row {:key id} text done
         (fn [] (toggle-todo id))
         (fn [] (delete-todo id))])
      [empty-state total filter-val])]])

(defn- count-markup [remaining]
  [:span [:b remaining] (str " item" (when (not= remaining 1) "s") " left")])

;; The footer: items-left count, the three filters, a done-last sort toggle, and
;; clear-completed. Two compact rows so nothing is cramped.
(defn- footer [remaining done-count filter-name set-filter sort-cursor clear-completed]
  [:vbox {:spacing 8 :margin-start 14 :margin-end 14 :margin-top 4 :margin-bottom 14}
   [:hbox {:spacing 10 :valign :center}
    [:label {:markup (count-markup remaining) :hexpand true :halign :start :xalign 0.0}]
    [:checkbutton {:label      "done last"
                   :active     @sort-cursor
                   :on-toggled (fn [] (swap! sort-cursor not))
                   :tooltip    "sort completed todos to the bottom"}]]
   [:hbox {:spacing 8 :valign :center}
    [w/filter-bar filter-name set-filter any? active? done?]
    [:button {:label     "clear completed"
              :on-click  clear-completed
              :sensitive (pos? done-count)
              :tooltip   "remove completed todos"}]]])

;; State lives at the top level in defonce reactive cells, not inside the
;; component. That is what lets REPL hot-reload preserve it, and it is also what
;; makes the whole thing imageable: `dump-world!` walks the var table, so every
;; def below is in the image without anything listing them.
;;
;; The board deliberately holds things EDN could not carry:
;;   - Task RECORDS, which come back as Tasks, not as maps
;;   - a live FUNCTION in :filter-fn, which comes back callable
;;   - :index, sharing the very same Task objects as :tasks (identity survives)
;; plus an undo history whose snapshots share structure with the current board.
(defn- index-by-id [tasks] (into {} (map (fn [t] [(:id t) t])) tasks))

(defn- board [tasks]
  {:tasks tasks
   :index (index-by-id tasks)          ; same Task objects, two ways in
   :filter-fn any?                     ; a live function, not a keyword
   :filter-name "all"
   :sort-done-last false
   :draft ""
   :next-id (inc (reduce max 0 (map :id tasks)))})

(defonce state (atom (board [(->Task 1 "Read the glimmer README" true)
                             (->Task 2 "Build the showcase app" false)
                             (->Task 3 "Wire a signal to an atom" false)
                             (->Task 4 "Ship something with it" false)])))

;; Undo snapshots share structure with the board they came from; the image keeps
;; that sharing rather than writing N independent copies.
(defonce undo (clojure.core/atom []))

;; cursors and reactions are DERIVED from the root atom. They hold closures, so
;; they cannot travel in an image — they are rebuilt after a restore instead.
;; declare-then-build keeps the vars stable across a rebuild.
(defonce draft (cursor state [:draft]))
(defonce sort? (cursor state [:sort-done-last]))
(defonce remaining  (reaction (count (remove :done (:tasks @state)))))
(defonce done-count (reaction (count (filter :done (:tasks @state)))))
(defonce visible    (reaction
                      (let [s      @state
                            picked (filterv (:filter-fn s) (:tasks s))]
                        (if (:sort-done-last s)
                          (sort-by (fn [t] [(if (:done t) 1 0) (:id t)]) picked)
                          picked))))

;; The after-restore half: the handler hands back plain stand-in ratoms for the
;; derived cells, so re-derive them from the restored root and rebind the vars.
(defn rebuild-cells! []
  (alter-var-root #'draft      (constantly (cursor state [:draft])))
  (alter-var-root #'sort?      (constantly (cursor state [:sort-done-last])))
  (alter-var-root #'remaining  (constantly (reaction (count (remove :done (:tasks @state))))))
  (alter-var-root #'done-count (constantly (reaction (count (filter :done (:tasks @state))))))
  (alter-var-root #'visible    (constantly (reaction
                                             (let [s      @state
                                                   picked (filterv (:filter-fn s) (:tasks s))]
                                               (if (:sort-done-last s)
                                                 (sort-by (fn [t] [(if (:done t) 1 0) (:id t)]) picked)
                                                 picked)))))
  nil)

;; mutations: plain fns over the root atom. Each one keeps :index in step with
;; :tasks, so the two always hold the SAME Task objects rather than equal copies.
(defn- resync [s tasks]
  (assoc s :tasks tasks :index (index-by-id tasks)))

(defn- snapshot! []
  (clojure.core/swap! undo conj @state))

(defn add-todo []
  (let [text (str/trim @draft)]
    (when (seq text)
      (snapshot!)
      (swap! state (fn [s]
                     (-> s
                         (resync (conj (:tasks s) (->Task (:next-id s) text false)))
                         (assoc :draft "")
                         (update :next-id inc)))))))
(defn toggle-todo [id]
  (snapshot!)
  (swap! state (fn [s] (resync s (mapv (fn [t] (if (= (:id t) id) (update t :done not) t))
                                       (:tasks s))))))
(defn delete-todo [id]
  (snapshot!)
  (swap! state (fn [s] (resync s (vec (remove (fn [t] (= (:id t) id)) (:tasks s)))))))
(defn toggle-all []
  (snapshot!)
  (swap! state (fn [s] (let [target (not (every? :done (:tasks s)))]
                         (resync s (mapv (fn [t] (assoc t :done target)) (:tasks s)))))))
(defn clear-completed []
  (snapshot!)
  (swap! state (fn [s] (resync s (vec (remove :done (:tasks s)))))))

(defn set-filter [f nm]
  (swap! state assoc :filter-fn f :filter-name nm))

(defn undo! []
  (when-let [prev (peek @undo)]
    (clojure.core/swap! undo pop)
    (reset! state prev)))

;; --- saving and reloading the world --------------------------------------------
;; app.persist does the work; these just thread the result into the status line.
(defonce status (atom ""))

(defn save-image! [] (reset! status (persist/save!)))
;; bind the message BEFORE touching status: restore-world! replaces the status
;; var itself, and (reset! status (persist/load!)) derefs the OLD cell before
;; load! runs — the confirmation would land on a cell nothing renders anymore.
(defn load-image! []
  (let [msg (persist/load!)]
    (reset! status msg)))

;; Re-derive the reactive graph after a restore, then re-render.
(persist/install-rebuild-hook! rebuild-cells!)

;; The root component: a plain render over the reactive cells above. It derefs
;; them, so it re-renders when any changes; redefine it (or any widget it calls)
;; and (glimmer.core/reload!) to see the change in the running window.
(defn todo-app []
  (let [total     (+ @remaining @done-count)
        all-done? (and (pos? total) (zero? @remaining))]
    [:vbox {:spacing 0}
     [title]
     [w/nav-bar save-image! load-image! undo! (count @undo) @status]
     [:separator]
     [add-bar total all-done? toggle-all draft add-todo]
     [:separator]
     [todo-list @visible total (:filter-name @state) toggle-todo delete-todo]
     [:separator]
     [footer @remaining @done-count (:filter-name @state) set-filter sort? clear-completed]]))

(defn -main [& _]
  (ui/run todo-app
          :title "glimmer · todos"
          :width 460 :height 600
          :app-id "glimmer.app.todos"))

;; Live development from the REPL (reagent-style), all in one window:
;;   1. `jolt nrepl-server` in this directory, then connect your editor.
;;   2. Evaluate (-main) to open the window. The eval returns; the app keeps
;;      running, so the session stays live.
;;   3. Reactive edits show up on their own: (swap! ...) / (reset! ...) a ratom the
;;      UI derefs and the affected widgets re-render.
;;   4. To pick up redefined component functions, re-evaluate them and then call
;;      reload! to re-render the SAME window:
;;        (ui/reload!)          re-runs the root and re-resolves the child widgets
;;                              it renders, so redefined children take effect.
;;        (ui/reload! todo-app) also swaps in a redefined root component.
;;      State lives in the defonce cells above, so your current todos survive the
;;      reload. Re-evaluating this whole namespace also keeps them (defonce);
;;      reset them explicitly with (reset! state ...) when you want a clean slate.
(comment
  (-main)
  (ui/reload!)
  (ui/reload! todo-app))