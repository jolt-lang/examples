(ns app.widgets
  "Reusable Form-1 components — plain functions returning hiccup, re-invoked on
  every render. They read reactive cells with @, so glimmer subscribes them and
  re-renders just that component when a cell it derefs changes. Handlers close
  over reactive cells (cursors) and constants only — never over values captured
  by position — so they stay correct for the widget's life even as the list
  re-renders. See glimmer's reconciler note in its README."
  (:require [glimmer.widget :as gw]))

;; A read-only KPI tile: the caption rides the frame border, the value is a big
;; bold number centered inside the bordered card. Pure — no reactive reads — so
;; it only re-renders when its parent does.
(defn stat-card [caption value tooltip]
  [:frame {:label caption}
   [:label {:markup (str "<span size='xx-large' weight='bold'>"
                         value "</span>")
            :tooltip tooltip
            :halign :center :valign :center
            :margin-top 6 :margin-bottom 10 :margin-start 16 :margin-end 16}]])

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
                            :tooltip   (str "show " label " tasks")}])]
    [:hbox {:spacing 6 :homogeneous true :hexpand true}
     (button :all    "all")
     (button :active "active")
     (button :done   "done")]))

;; A single read-only task line. The text is wrapped and its natural width is
;; capped (:max-width-chars) so a long task can't push the window wider — the
;; fix for the "grows wider on every add" bug. Completed tasks render struck
;; through and muted via Pango markup; the text is escaped first.
(defn task-row [text done]
  (let [body (gw/escape-markup text)]
    [:label {:markup (if done
                       (str "☑  <span strikethrough='true' foreground='#9aa0ad'>" body "</span>")
                       (str "☐  " body))
             :wrap true
             :max-width-chars 50
             :hexpand true :halign :fill :xalign 0.0 :valign :start
             :margin-top 2 :margin-bottom 2}]))

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
