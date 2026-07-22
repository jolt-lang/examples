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
            [app.widgets :as w]))

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
                      (str "No " (name filter-val) " todos — try another filter."))]
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
(defn- footer [remaining done-count filter-cursor sort-cursor clear-completed]
  [:vbox {:spacing 8 :margin-start 14 :margin-end 14 :margin-top 4 :margin-bottom 14}
   [:hbox {:spacing 10 :valign :center}
    [:label {:markup (count-markup remaining) :hexpand true :halign :start :xalign 0.0}]
    [:checkbutton {:label      "done last"
                   :active     @sort-cursor
                   :on-toggled (fn [] (swap! sort-cursor not))
                   :tooltip    "sort completed todos to the bottom"}]]
   [:hbox {:spacing 8 :valign :center}
    [w/filter-bar filter-cursor]
    [:button {:label     "clear completed"
              :on-click  clear-completed
              :sensitive (pos? done-count)
              :tooltip   "remove completed todos"}]]])

;; State lives at the top level in defonce reactive cells, not inside the
;; component. That is what lets REPL hot-reload preserve it: re-evaluating this
;; namespace leaves the defonce cells untouched, and glimmer.core/reload! rebuilds
;; the component tree against the same state — reagent-style. (State kept in a
;; component's own let would reset on every reload.)
(defonce state (atom {:tasks   [{:id 1 :text "Read the glimmer README"  :done true}
                                {:id 2 :text "Build the showcase app"   :done false}
                                {:id 3 :text "Wire a signal to an atom" :done false}
                                {:id 4 :text "Ship something with it"   :done false}]
                      :filter  :all
                      :sort-done-last false
                      :draft   ""
                      :next-id 5}))

;; cursors: writable lenses over the root atom
(defonce draft (cursor state [:draft]))
(defonce flt   (cursor state [:filter]))
(defonce sort? (cursor state [:sort-done-last]))

;; reactions: read-only derived cells
(defonce remaining  (reaction (count (remove :done (:tasks @state)))))
(defonce done-count (reaction (count (filter :done (:tasks @state)))))
(defonce visible    (reaction
                      (let [ts     (:tasks @state)
                            picked (case @flt
                                     :active (remove :done ts)
                                     :done   (filter :done ts)
                                     ts)]
                        (if @sort?
                          (sort-by (fn [t] [(if (:done t) 1 0) (:id t)]) picked)
                          picked))))

;; mutations: plain fns over the root atom (redefinable in the REPL)
(defn add-todo []
  (let [text (str/trim @draft)]
    (when (seq text)
      (swap! state (fn [s]
                     (-> s
                         (update :tasks conj {:id (:next-id s) :text text :done false})
                         (assoc :draft "")
                         (update :next-id inc)))))))
(defn toggle-todo [id]
  (swap! state update :tasks
         (fn [ts] (mapv (fn [t] (if (= (:id t) id) (update t :done not) t)) ts))))
(defn delete-todo [id]
  (swap! state update :tasks
         (fn [ts] (vec (remove (fn [t] (= (:id t) id)) ts)))))
(defn toggle-all []
  (swap! state update :tasks
         (fn [ts] (let [target (not (every? :done ts))]
                    (mapv (fn [t] (assoc t :done target)) ts)))))
(defn clear-completed [] (swap! state update :tasks #(vec (remove :done %))))

;; The root component: a plain render over the reactive cells above. It derefs
;; them, so it re-renders when any changes; redefine it (or any widget it calls)
;; and (glimmer.core/reload!) to see the change in the running window.
(defn todo-app []
  (let [total     (+ @remaining @done-count)
        all-done? (and (pos? total) (zero? @remaining))]
    [:vbox {:spacing 0}
     [title]
     [add-bar total all-done? toggle-all draft add-todo]
     [:separator]
     [todo-list @visible total @flt toggle-todo delete-todo]
     [:separator]
     [footer @remaining @done-count flt sort? clear-completed]]))

(defn -main [& _]
  (ui/run todo-app
          :title "glimmer · todos"
          :width 460 :height 600
          :app-id "glimmer.app.todos"))

;; Live development from the REPL (reagent-style), all in one window:
;;   1. `joltc nrepl-server` in this directory, then connect your editor.
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