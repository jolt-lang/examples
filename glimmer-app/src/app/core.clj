(ns app.core
  "A standard TodoMVC built with glimmer — a dynamic list of todos you can add,
  check off, delete, filter, and bulk-clear, all reactive.

  State model — one reactive atom is the source of truth, with focused views
  derived from it:
    - atom     : the whole app (todos, filter, sort flag, draft, next id)
    - cursors  : writable lenses over [:draft], [:filter], [:sort-done-last]
    - reactions: read-only derived cells — remaining, done count, visible todos

  Components — Form-1 leaves live in app.widgets; the Form-2 root lives here.
  Events — :on-change / :on-activate (entry), :on-click (buttons),
  :on-toggled (checkbutton) all appear.

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
  [:label {:markup "<span size='30000' weight='bold' foreground='#b83f45'>todos</span>"
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
  [:label {:markup (if (zero? total)
                     "<span foreground='#8e939d'>No todos yet — add one above to get started.</span>"
                     (str "<span foreground='#8e939d'>No "
                          (name filter-val)
                          " todos — try another filter.</span>"))
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
  (str "<b>" remaining "</b> item" (when (not= remaining 1) "s") " left"))

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

(defn todo-app []
  ;; outer let runs once on mount: state, derived cursors/reactions, and the
  ;; mutation fns that close over the root atom.
  (let [state (atom {:tasks   [{:id 1 :text "Read the glimmer README"  :done true}
                               {:id 2 :text "Build the showcase app"   :done false}
                               {:id 3 :text "Wire a signal to an atom" :done false}
                               {:id 4 :text "Ship something with it"   :done false}]
                     :filter  :all
                     :sort-done-last false
                     :draft   ""
                     :next-id 5})

        draft (cursor state [:draft])
        flt   (cursor state [:filter])
        sort? (cursor state [:sort-done-last])

        remaining  (reaction (count (remove :done (:tasks @state))))
        done-count (reaction (count (filter :done (:tasks @state))))
        visible    (reaction
                     (let [ts     (:tasks @state)
                           picked (case @flt
                                    :active (remove :done ts)
                                    :done   (filter :done ts)
                                    ts)]
                       (if @sort?
                         (sort-by (fn [t] [(if (:done t) 1 0) (:id t)]) picked)
                         picked)))

        add-todo (fn []
                   (let [text (str/trim @draft)]
                     (when (seq text)
                       (swap! state (fn [s]
                                      (-> s
                                          (update :tasks conj {:id   (:next-id s)
                                                               :text text
                                                               :done false})
                                          (assoc :draft "")
                                          (update :next-id inc)))))))
        toggle-todo  (fn [id]
                       (swap! state update :tasks
                              (fn [ts] (mapv (fn [t] (if (= (:id t) id) (update t :done not) t)) ts))))
        delete-todo  (fn [id]
                       (swap! state update :tasks
                              (fn [ts] (vec (remove (fn [t] (= (:id t) id)) ts)))))
        toggle-all   (fn []
                       (swap! state update :tasks
                              (fn [ts] (let [target (not (every? :done ts))]
                                         (mapv (fn [t] (assoc t :done target)) ts)))))
        clear-completed (fn [] (swap! state update :tasks #(vec (remove :done %))))]
    ;; inner fn = render; re-runs when any reactive cell it derefs changes.
    (fn []
      (let [total     (+ @remaining @done-count)
            all-done? (and (pos? total) (zero? @remaining))]
        [:vbox {:spacing 0}
         [title]
         [add-bar total all-done? toggle-all draft add-todo]
         [:separator]
         [todo-list @visible total @flt toggle-todo delete-todo]
         [:separator]
         [footer @remaining @done-count flt sort? clear-completed]]))))

(defn -main [& _]
  (ui/run todo-app
          :title "glimmer · todos"
          :width 460 :height 600
          :app-id "glimmer.app.todos"))
