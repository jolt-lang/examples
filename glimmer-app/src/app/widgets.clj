(ns app.widgets
  "Reusable Form-1 components — plain functions returning hiccup, re-invoked on
  every render. They read reactive cells with @, so glimmer subscribes them and
  re-renders just that component when a cell it derefs changes. Handlers close
  over reactive cells (cursors) and constants only — never over values captured
  by position — so they stay correct for the widget's life even as the list
  re-renders. See glimmer's reconciler note in its README."
  (:require [glimmer.widget :as gw]))

;; Three filter buttons driven by a cursor over [:filter], laid out as a
;; segmented control. The active filter's button is disabled (:sensitive false)
;; so it reads as the current selection. Each :on-click writes the cursor — a
;; reactive write — and the bar re-renders.
(defn filter-bar [filter-cursor]
  (let [current @filter-cursor
        button  (fn [kw label]
                  [:button {:label     label
                            :sensitive (not= current kw)
                            :on-click  #(reset! filter-cursor kw)
                            :tooltip   (str "show " label " todos")}])]
    [:hbox {:spacing 6 :homogeneous true :hexpand true}
     (button :all    "all")
     (button :active "active")
     (button :done   "done")]))

;; A single interactive task line: a checkbutton that toggles done, the wrapped
;; text (struck through and muted when done), and a delete button. The handlers
;; are passed in already bound to the task's id, and the row is mounted with a
;; stable :key, so glimmer's keyed reconciler keeps each row's widgets and wired
;; signals attached to the same task across add/remove/reorder/filter — the
;; handlers can't capture a stale position. The text's natural width is capped
;; (:max-width-chars) so a long task can't push the window wider.
(defn task-row [text done on-toggle on-delete]
  (let [body (gw/escape-markup text)]
    [:hbox {:spacing 8 :valign :center :margin-top 1 :margin-bottom 1}
     [:checkbutton {:active     done
                    :valign     :center
                    :on-toggled on-toggle
                    :tooltip    "toggle done"}]
     [:label {:markup (if done
                        (str "<span strikethrough='true' foreground='#9aa0ad'>" body "</span>")
                        body)
              :wrap true
              :max-width-chars 44
              :hexpand true :halign :fill :xalign 0.0 :valign :center}]
     [:button {:label    "✕"
               :on-click on-delete
               :valign   :center
               :tooltip  "delete this task"}]]))

;; The add bar: an entry bound to the draft cursor plus an add button. The entry
;; fills the available width (:hexpand/:halign :fill) so the button keeps a fixed
;; size. :on-change writes every keystroke into the cursor; :on-activate (Enter)
;; and the button both call on-add. The button is disabled while the draft is
;; empty.
(defn command-bar [draft-cursor on-add]
  [:hbox {:spacing 8 :hexpand true}
   [:entry {:text         @draft-cursor
            :placeholder  "Add a task and press Enter..."
            :tooltip      "type a task, then press Enter"
            :hexpand      true :halign :fill
            :on-change    #(reset! draft-cursor %)
            :on-activate  on-add}]
   [:button {:label     "add"
             :sensitive (boolean (seq @draft-cursor))
             :on-click  on-add
             :tooltip   "add the task"}]])
