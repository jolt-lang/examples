(ns tui-demo.core
  "A reactive terminal app: a browser for the jolt libraries.

  Everything on screen is derived from one reactive cell, and nothing in this
  namespace draws anything. The pieces worth copying:

    state     one `atom` holding the whole UI state, with `cursor`s as writable
              lenses over the parts of it that individual components own
    reaction  `matches`, a read-only derived cell recomputed when the query or
              the native-only toggle changes; the list and the header count both
              read it, and `current` is a reaction over that one
    keys      the pinned row is keyed by library name, so pinning and unpinning
              reuse each button's widget instead of rebuilding the row by
              position — which is what keeps the keyboard focus where it was
    timers    `tui/every!` drives the spinner and the fake install; a background
              thread drives the uptime counter. Both are safe: a reactive cell
              written off the UI thread has its repaint marshalled onto the
              thread that owns the terminal, and a timer thunk runs there already
    on-key    the root box binds the application's own keys, and sees only what
              the focused widget did not want — which is why `q` quits but typing
              q into the filter types a q

  Only the `glimmer-tui.core` require names a toolkit. Swap it for
  `glimmer-gtk.core` and the same components render as GTK widgets."
  (:require [clojure.string :as str]
            [glimmer.ratom :as r :refer [atom cursor reaction]]
            [glimmer.core :as ui]
            [glimmer-tui.core :as tui]
            [glimmer-tui.keys :as k]))

;; --- data --------------------------------------------------------------------
(def accent "#7aa2f7")
(def ok-color "#9ece6a")
(def warn-color "#e0af68")

(def libraries
  [{:name "db"          :kind "native" :blurb "Postgres and SQLite over libpq/libsqlite3"
    :releases [{:version "0.4.0" :date "2025-11-02" :size "182 kB"}
               {:version "0.3.2" :date "2025-08-14" :size "175 kB"}
               {:version "0.3.1" :date "2025-07-30" :size "174 kB"}]}
   {:name "http-client" :kind "native" :blurb "HTTP over POSIX sockets, OpenSSL and zlib"
    :releases [{:version "0.6.1" :date "2025-12-01" :size "240 kB"}
               {:version "0.6.0" :date "2025-10-19" :size "236 kB"}]}
   {:name "crypto"      :kind "native" :blurb "hashing, HMAC and ciphers over OpenSSL"
    :releases [{:version "0.2.0" :date "2025-09-06" :size "96 kB"}]}
   {:name "time"        :kind "pure"   :blurb "the formatting and zone layer of java.time"
    :releases [{:version "1.1.0" :date "2025-11-21" :size "310 kB"}
               {:version "1.0.0" :date "2025-06-02" :size "298 kB"}]}
   {:name "instaparse"  :kind "pure"   :blurb "EBNF/ABNF grammars turned into parsers"
    :releases [{:version "1.5.0" :date "2025-10-08" :size "204 kB"}]}
   {:name "otel"        :kind "pure"   :blurb "OpenTelemetry tracing and metrics over OTLP"
    :releases [{:version "0.3.0" :date "2025-12-11" :size "158 kB"}]}
   {:name "nrepl"       :kind "pure"   :blurb "nREPL server and client for editors"
    :releases [{:version "0.9.1" :date "2025-11-28" :size "88 kB"}
               {:version "0.9.0" :date "2025-11-04" :size "87 kB"}]}
   {:name "fressian"    :kind "pure"   :blurb "the Fressian binary format, reader and writer"
    :releases [{:version "0.2.1" :date "2025-07-19" :size "64 kB"}]}
   {:name "transit"     :kind "pure"   :blurb "Transit over the jolt reader"
    :releases [{:version "0.1.4" :date "2025-05-30" :size "72 kB"}]}
   {:name "glimmer"     :kind "pure"   :blurb "reactive UI toolkit; you are looking at it"
    :releases [{:version "0.2.1" :date "2025-12-18" :size "120 kB"}
               {:version "0.1.1" :date "2025-09-27" :size "104 kB"}]}
   {:name "glimmer-gtk" :kind "native" :blurb "the GTK4 backend for glimmer"
    :releases [{:version "0.1.0" :date "2025-10-30" :size "140 kB"}]}
   {:name "glimmer-tui" :kind "native" :blurb "the terminal backend; this window"
    :releases [{:version "0.2.1" :date "2025-12-20" :size "96 kB"}
               {:version "0.1.1" :date "2025-11-15" :size "58 kB"}]}])

(defn- library [name] (first (filter #(= name (:name %)) libraries)))

(defn- notes
  "Deliberately longer than any terminal: this is what the :scroll is for."
  [lib]
  (str (:name lib) "\n"
       (apply str (repeat (count (:name lib)) "─")) "\n"
       "\n"
       (:blurb lib) "\n"
       "\n"
       "Kind: " (:kind lib) "\n"
       "Releases: " (count (:releases lib)) "\n"
       "\n"
       "This pane is a :scroll. Its child is laid out at full height and the\n"
       "viewport shows a window onto it, so nothing is re-measured while you\n"
       "scroll — a long document costs what a short one does.\n"
       "\n"
       "It is also why the footer below is still on screen. Layout gives every\n"
       "node two sizes: what it would like, and what it can live on. A scroll\n"
       "can live on nothing, so when the window is short it gives up its rows\n"
       "instead of pushing the help bar off the bottom.\n"
       "\n"
       "Tab to this pane and it scrolls. While the list on the left has the\n"
       "focus those same keys move the list instead, because a key goes to the\n"
       "focused widget first and only reaches the container around it if the\n"
       "widget did not want it. The wheel needs no focus at all — it scrolls\n"
       "whatever is under the pointer.\n"
       "\n"
       "  j / k or arrows    one line\n"
       "  ctrl-d / ctrl-u    half a page\n"
       "  pgdn / pgup        a page\n"
       "  g / G              the ends\n"
       "  the mouse wheel    three lines\n"
       "\n"
       "Widths are counted in grapheme clusters, so these line up whatever\n"
       "they are made of:\n"
       "\n"
       "  ascii   |x|\n"
       "  CJK     |日|\n"
       "  emoji   |👍🏽|\n"
       "  flag    |🇯🇵|\n"
       "\n"
       "· end of " (:name lib) " ·"))

(def tabs ["about" "releases" "install"])

;; --- components --------------------------------------------------------------
;; Form-1 components: plain functions of their arguments, returning hiccup.

(defn- header [ui matches]
  [:hbox {:spacing 2}
   ;; the spinner does not animate itself: :tick is a prop like any other, and
   ;; a timer in `app` moves it
   [:spinner {:tick @(:tick ui) :color accent}]
   [:label {:label "jolt libraries" :bold true :color accent}]
   [:label {:label (str (count @matches) " of " (count libraries)) :dim true}]
   [:label {:label (str "up " @(:uptime ui) "s") :dim true :halign :end :hexpand true}]])

(defn- filter-bar [ui]
  (let [query (:query ui)
        native? (:native? ui)]
    [:hbox {:spacing 2}
     [:label {:label "filter"}]
     ;; the entry owns its caret; :text is what the component believes it holds,
     ;; and readline editing (ctrl-a/e, ctrl-w, ctrl-u/k, alt-b/f) comes free
     [:entry {:text @query :placeholder "type to narrow…" :hexpand true
              :char-limit 24
              :on-change #(reset! query %)}]
     ;; the handler owns the state: the checkbutton never toggles itself, it
     ;; flips the cell it is told to and :active comes back down as a prop
     [:checkbutton {:label "native only" :active @native?
                    :on-toggled #(swap! native? not)}]]))

(defn- pinned-bar [ui]
  (let [pinned (:pinned ui)
        selected (:selected ui)]
    (if (empty? @pinned)
      [:label {:label "nothing pinned — press p" :dim true}]
      ;; Keyed rows: each button is keyed by the library it stands for, so
      ;; pinning and unpinning reuse widgets instead of rebuilding by position.
      (into [:hbox {:spacing 1}]
            (for [name @pinned]
              ^{:key name}
              [:button {:label name :color warn-color
                        :on-click #(reset! selected name)}])))))

(defn- sidebar [ui matches]
  (let [selected (:selected ui)
        tab (:tab ui)
        index (or (first (keep-indexed (fn [i lib] (when (= @selected (:name lib)) i))
                                       @matches))
                  0)]
    [:frame {:label "libraries" :border :rounded :width-request 26 :padding [0 1]}
     (if (empty? @matches)
       [:label {:label "no matches" :dim true}]
       ;; a list keeps its own cursor and offset, and can be given one row and
       ;; still work — so it never pushes the rest of the window off the bottom
       [:listbox {:items (mapv (fn [lib] {:label (:name lib) :desc (:kind lib)})
                               @matches)
                  :selected index
                  :descriptions true
                  :cursor-prefix "▸ "
                  :item-prefix "  "
                  :on-select (fn [i _] (reset! selected (:name (nth @matches i))))
                  :autofocus true
                  :on-activate #(reset! tab 2)}])]))

(defn- about-tab [ui lib]
  (let [line (:line ui)]
    ;; a scroll takes focus only when nothing inside it can, so this one is in
    ;; the tab ring: Tab here and j/k scroll it. The wheel needs no focus, and a
    ;; key the focused widget declines still reaches the scroll around it
    [:scroll {:vexpand true
              :on-scroll (fn [{:keys [y]}] (reset! line y))}
     [:label {:label (notes lib)}]]))

(defn- releases-tab [lib]
  [:vbox {:spacing 1 :vexpand true}
   [:label {:label "columns size themselves to their contents" :dim true}]
   [:table {:columns [{:title "version" :key :version}
                      {:title "released" :key :date}
                      {:title "size" :key :size :align :end}]
            :rows (:releases lib)
            :vexpand true}]])

(defn- install-tab [ui lib install!]
  (let [progress (:install ui)]
    [:vbox {:spacing 1 :vexpand true}
     [:label {:label (str "install " (:name lib))}]
     [:progress {:value (or @progress 0) :bar :blocks :bar-color ok-color
                 :show-percent true :width-request 30 :halign :start}]
     [:label {:label (cond
                       (nil? @progress) "press i, or the button below"
                       (>= @progress 1) "done"
                       :else "a timer is driving this, not a keypress")
              :dim true}]
     [:hbox {:spacing 2}
      [:button {:label "install" :on-click install!}]
      [:button {:label "remove" :color warn-color
                :on-click #(reset! (:dialog? ui) true)}]]]))

(defn- detail [ui current install!]
  (let [tab (:tab ui)
        line (:line ui)
        lib @current]
    [:frame {:label (str (nth tabs @tab)
                         (when (zero? @tab) (str " — line " (inc (or @line 0)))))
             :label-align :start :border :rounded
             :hexpand true :vexpand true :padding [0 1]}
     [:vbox {:vexpand true}
      ;; The tab content changes tag as you switch tabs — a :scroll, a :table, a
      ;; :vbox — so it is wrapped in a container that does not, and the
      ;; paginator below keeps its place in the box whatever is above it.
      [:vbox {:vexpand true}
       (if (nil? lib)
         [:label {:label "nothing matches the filter" :dim true :vexpand true}]
         (case @tab
           0 [about-tab ui lib]
           1 [releases-tab lib]
           2 [install-tab ui lib install!]))]
      [:hbox {:spacing 1}
       [:paginator {:page @tab :total-pages (count tabs) :color accent}]
       [:label {:label "  [ and ] change tab" :dim true}]]]]))

(defn- footer []
  [:vbox {}
   [:separator {}]
   [:hbox {:spacing 2}
    ;; no :bindings of its own: it renders what the FOCUSED widget answers to,
    ;; read off the live tree, so it cannot drift from what the keys do
    [:help {:hexpand true}]
    [:label {:label "p pin  i install  d remove  ? keys  q quit" :dim true}]]])

(defn- confirm-dialog [ui]
  (let [selected (:selected ui)
        dialog? (:dialog? ui)]
    ;; modal (the default): while it is up, Tab cannot leave it and Esc closes it
    [:overlay {:anchor :center :on-close #(reset! dialog? false)}
     [:frame {:label "confirm" :border :double :padding 1}
      [:vbox {:spacing 1}
       [:label {:label (str "Remove " @selected "?")}]
       [:label {:label "Nothing is actually removed." :dim true}]
       [:hbox {:spacing 2 :halign :center}
        [:button {:label "yes" :autofocus true :color warn-color
                  :on-click #(do (reset! (:removed ui) @selected)
                                 (reset! dialog? false))}]
        [:button {:label "no" :on-click #(reset! dialog? false)}]]]]]))

(defn- help-panel []
  ;; NOT modal: focus stays on the widget behind it, which is the whole point —
  ;; the panel is showing that widget's own bindings
  [:overlay {:anchor :center :modal false}
   [:frame {:label "keys in effect" :border :thick :padding 1}
    [:vbox {:spacing 1}
     [:help {:full true :width-request 34}]
     [:label {:label "? or esc closes this" :dim true}]]]])

;; --- the app -----------------------------------------------------------------
(defn app
  "Form-2: the outer fn runs once on mount, so the state, cursors, reaction,
  timers and background thread are created a single time. The returned fn is
  what re-renders.

  Cursors belong out here, not inside a component. A cursor subscribes to the
  atom it lenses, so building one during a render adds a subscription on every
  render — the UI gets slower with every keystroke and eventually re-renders
  itself in a loop. Build them once and pass them down, the way this does."
  []
  (let [state (atom {:query "" :selected "db" :native-only? false
                     :tab 0 :line 0 :pinned [] :install nil
                     :dialog? false :help? false :removed nil
                     :tick 0 :uptime 0})
        ui {:query    (cursor state [:query])
            :native?  (cursor state [:native-only?])
            :selected (cursor state [:selected])
            :tab      (cursor state [:tab])
            :line     (cursor state [:line])
            :pinned   (cursor state [:pinned])
            :install  (cursor state [:install])
            :dialog?  (cursor state [:dialog?])
            :help?    (cursor state [:help?])
            :removed  (cursor state [:removed])
            :tick     (cursor state [:tick])
            :uptime   (cursor state [:uptime])}
        ;; a plain cell nothing renders: writing it starts no repaint, which is
        ;; what a timer handle wants to be
        install-timer (atom nil)
        matches (reaction
                  (let [q (str/lower-case (str (:query @state)))
                        native? (:native-only? @state)]
                    (filterv (fn [lib]
                               (and (or (str/blank? q)
                                        (str/includes? (str/lower-case (:name lib)) q))
                                    (or (not native?) (= "native" (:kind lib)))))
                             libraries)))
        ;; A reaction over another reaction: the library the detail pane shows.
        ;; The selection is a NAME, and a name can be filtered out from under
        ;; you — so what is shown is the selected library if it is still in the
        ;; list, and the first match otherwise. Without this the cursor in the
        ;; list and the pane beside it would drift apart as you type.
        current (reaction (or (first (filter #(= (:selected @state) (:name %)) @matches))
                              (first @matches)))
        install! (fn []
                   (when-not @install-timer
                     (reset! (:install ui) 0.0)
                     (reset! (:tab ui) 2)
                     (reset! install-timer
                             (tui/every! 80
                               (fn []
                                 (swap! (:install ui) #(min 1.0 (+ (or % 0) 0.08)))
                                 (when (>= @(:install ui) 1.0)
                                   (tui/cancel! @install-timer)
                                   (reset! install-timer nil)
                                   ;; a one-shot timer, to clear the bar after a beat
                                   (tui/after! 1200 #(reset! (:install ui) nil))))))))
        toggle-pin! (fn []
                      (let [name @(:selected ui)]
                        (swap! (:pinned ui)
                               (fn [ps]
                                 (if (some #{name} ps)
                                   (vec (remove #{name} ps))
                                   (conj (vec ps) name))))))
        ;; The root box's own key handler. It sees only what the focused widget
        ;; did not want, which is why these single letters do not fight the
        ;; filter field: type in the field and they are just letters.
        on-key (fn [event]
                 (cond
                   (k/match? event "p") (do (toggle-pin!) true)
                   (k/match? event "i") (do (install!) true)
                   (k/match? event "d") (do (reset! (:dialog? ui) true) true)
                   (k/match? event "?") (do (swap! (:help? ui) not) true)
                   (k/match? event "[") (do (swap! (:tab ui) #(mod (dec %) (count tabs))) true)
                   (k/match? event "]") (do (swap! (:tab ui) #(mod (inc %) (count tabs))) true)
                   ;; the help panel is not modal, so closing it is ours to do
                   (and @(:help? ui) (k/match? event :escape))
                   (do (reset! (:help? ui) false) true)
                   :else false))]

    ;; A timer thunk runs ON the loop thread, which is the only thread allowed to
    ;; touch widgets. This is what animates the spinner with nobody at the
    ;; keyboard.
    (tui/every! 120 #(swap! (:tick ui) inc))

    ;; A worker thread writing a reactive cell, for contrast: the repaint is
    ;; marshalled onto the UI thread by glimmer, so this needs no ceremony here.
    (future
      (loop []
        (Thread/sleep 1000)
        (swap! (:uptime ui) inc)
        (recur)))

    (fn []
      [:vbox {:spacing 1 :margin 1 :vexpand true :hexpand true :on-key on-key}
       [header ui matches]
       [filter-bar ui]
       [pinned-bar ui]
       [:hbox {:spacing 1 :vexpand true}
        [sidebar ui matches]
        [detail ui current install!]]
       [footer]
       (when @(:removed ui)
         [:label {:label (str "removed " @(:removed ui)) :color warn-color}])
       (when @(:dialog? ui) [confirm-dialog ui])
       (when @(:help? ui) [help-panel])])))

(defn -main [& _]
  (ui/run app :quit-keys #{"ctrl+c" "ctrl+q" "q"}))
