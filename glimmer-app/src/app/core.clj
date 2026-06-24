(ns app.core
  "A reactive task board that exercises glimmer's full feature set.

  State model — one reactive atom is the source of truth, with focused views
  derived from it:
    - atom     : the whole board (tasks, filter, sort flag, draft, next id)
    - cursors  : writable lenses over [:draft], [:filter], [:sort-done-last]
    - reactions: read-only derived cells — remaining, done-count, visible tasks

  Components — Form-1 leaves live in app.widgets; the Form-2 root lives here.
  Events — :on-change / :on-activate (entry), :on-click (buttons),
  :on-toggled (checkbutton) all appear.

  Rows are keyed by task :id and interactive: each carries its own toggle and
  delete handlers, bound to that id. glimmer's keyed reconciler matches rows by
  :key, so a row's widgets and once-wired signals follow its task across
  add/remove/reorder/filter instead of a stale position — exactly what makes a
  dynamic todo list safe.

  Layout — the board is a fixed-width column: title + subtitle, framed KPI cards,
  a filter/sort row, a scrollable task list (rows wrap and cap their width so the
  window never grows wider as tasks are added), and a command/actions footer.
  Each band is a small helper so the render body stays flat and easy to read."
  (:require [clojure.string :as str]
            [glimmer.ratom :refer [atom cursor reaction]]
            [glimmer.core :as ui]
            [app.widgets :as w]))

(defn- subtitle-markup
  "Pango markup for the one-line status under the title."
  [total remaining done]
  (cond
    (zero? total)     "<span foreground='#8e939d'>Nothing to do yet — add a task below.</span>"
    (zero? remaining) (str "<span foreground='#8e939d'>All done — "
                           done " task" (when (not= done 1) "s") " complete!</span>")
    :else             (str "<span foreground='#8e939d'>"
                           remaining " of " total " remaining  ·  " done " done</span>")))

(defn- header [total remaining done]
  [:vbox {:spacing 2 :margin 14 :margin-bottom 10}
   [:label {:markup "<span size='xx-large' weight='bold'>Tasks</span>"
            :halign :start :xalign 0.0}]
   [:label {:markup (subtitle-markup total remaining done)
            :halign :start :xalign 0.0}]])

(defn- kpi-cards [remaining done-count]
  [:hbox {:spacing 12 :homogeneous true
          :margin-start 14 :margin-end 14 :margin-top 10}
   [w/stat-card "remaining" remaining "tasks not yet done"]
   [w/stat-card "done"      done-count "tasks completed"]])

(defn- filter-row [filter-cursor sort-cursor]
  [:hbox {:spacing 10 :valign :center
          :margin-start 14 :margin-end 14 :margin-top 12}
   [w/filter-bar filter-cursor]
   [:checkbutton {:label      "done last"
                  :active     @sort-cursor
                  :on-toggled #(swap! sort-cursor not)
                  :tooltip    "sort completed tasks to the bottom"}]])

(defn- empty-state [total filter-val]
  [:label {:markup (if (zero? total)
                     "<span foreground='#8e939d'>No tasks yet — add one above to get started.</span>"
                     (str "<span foreground='#8e939d'>No "
                          (name filter-val)
                          " tasks — try another filter.</span>"))
           :halign :center :margin 24}])

(defn- task-list [visible total filter-val toggle-task delete-task]
  [:scrolled {:vexpand true
              :margin-start 14 :margin-end 14 :margin-top 12}
   [:vbox {:spacing 6 :margin 4}
    (if (seq visible)
      (for [{:keys [id text done]} visible]
        ;; {:key id} matches this row to its task by identity; the leading map is
        ;; stripped before task-row is called. Handlers close over id, not index.
        [w/task-row {:key id} text done
         (fn [] (toggle-task id))
         (fn [] (delete-task id))])
      [empty-state total filter-val])]])

(defn- actions [total remaining done-count toggle-all clear-completed]
  [:hbox {:spacing 8}
   [:button {:label     (if (and (pos? total) (zero? remaining)) "mark all active" "complete all")
             :on-click  toggle-all
             :sensitive (pos? total)
             :tooltip   "toggle every task done / active"}]
   [:button {:label     "clear completed"
             :on-click  clear-completed
             :sensitive (pos? done-count)
             :tooltip   "remove completed tasks"}]])

(defn- footer [draft-cursor on-add total remaining done-count toggle-all clear-completed]
  [:vbox {:spacing 8 :margin 14}
   [w/command-bar draft-cursor on-add]
   [actions total remaining done-count toggle-all clear-completed]])

(defn task-board []
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

        draft  (cursor state [:draft])
        filter (cursor state [:filter])
        sort?  (cursor state [:sort-done-last])

        remaining  (reaction (count (remove :done (:tasks @state))))
        done-count (reaction (count (filter  :done (:tasks @state))))
        visible    (reaction
                     (let [ts (:tasks @state)
                           picked (case @filter
                                    :active (remove :done ts)
                                    :done   (filter  :done ts)
                                    ts)]
                       (if @sort?
                         (sort-by (fn [t] [(if (:done t) 1 0) (:id t)]) picked)
                         picked)))

        add-task (fn []
                   (let [text (str/trim @draft)]
                     (when (seq text)
                       (swap! state (fn [s]
                                      (-> s
                                          (update :tasks conj {:id (:next-id s)
                                                               :text text
                                                               :done false})
                                          (assoc :draft "")
                                          (update :next-id inc)))))))
        toggle-task  (fn [id]
                       (swap! state update :tasks
                              (fn [ts] (mapv (fn [t] (if (= (:id t) id) (update t :done not) t)) ts))))
        delete-task  (fn [id]
                       (swap! state update :tasks
                              (fn [ts] (vec (remove (fn [t] (= (:id t) id)) ts)))))
        toggle-all   (fn []
                       (swap! state update :tasks
                              (fn [ts] (let [target (not (every? :done ts))]
                                         (mapv (fn [t] (assoc t :done target)) ts)))))
        clear-completed (fn [] (swap! state update :tasks #(vec (remove :done %))))]
    ;; inner fn = render; re-runs when any reactive cell it derefs changes.
    (fn []
      (let [total (+ @remaining @done-count)]
        [:vbox {:spacing 0}
         [header total @remaining @done-count]
         [:separator]
         [kpi-cards @remaining @done-count]
         [filter-row filter sort?]
         [task-list @visible total @filter toggle-task delete-task]
         [:separator]
         [footer draft add-task total @remaining @done-count toggle-all clear-completed]]))))

(defn -main [& _]
  (ui/run task-board
          :title "glimmer — task board"
          :width 480 :height 560
          :app-id "glimmer.app.taskboard"))
