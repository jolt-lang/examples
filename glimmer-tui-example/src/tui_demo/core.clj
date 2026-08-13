(ns tui-demo.core
  "A small reactive terminal app: a searchable library list with a live counter.

  Everything on screen is derived from two reactive cells, and nothing in this
  namespace draws anything. The pieces worth copying:

    state    one `atom` holding the whole UI state (query, selection, uptime)
    cursors  writable lenses over `[:query]` and `[:selected]`
    reaction `matches`, a read-only derived cell recomputed when the query or the
             library list changes; the row list and the counts both read it
    keys     rows are keyed by library name, so filtering reuses each row's
             widget instead of rebuilding the list by position — which is what
             keeps the keyboard focus on the row you were looking at

  The uptime counter is incremented from a background thread. A ratom written off
  the UI thread is safe: glimmer marshals the re-render onto the loop through the
  backend, so the repaint happens on the thread that owns the terminal.

  Only the `glimmer-tui.core` require names a toolkit. Swap it for
  `glimmer-gtk.core` and the same components render as GTK widgets."
  (:require [clojure.string :as str]
            [glimmer.ratom :as r :refer [atom cursor reaction]]
            [glimmer.core :as ui]
            [glimmer-tui.core :as tui]))

;; Kept short on purpose: glimmer-tui has no scrolling viewport yet, so a list
;; whose natural height exceeds the terminal pushes whatever follows it off the
;; bottom. Eight rows leave room for the buttons and footer in an 80x24 window.
(def libraries
  [{:name "db"          :blurb "Postgres and SQLite over libpq/libsqlite3"}
   {:name "http-client" :blurb "HTTP over POSIX sockets, OpenSSL and zlib"}
   {:name "crypto"      :blurb "hashing, HMAC and ciphers over OpenSSL"}
   {:name "time"        :blurb "the formatting and zone layer of java.time"}
   {:name "instaparse"  :blurb "EBNF/ABNF grammars turned into parsers"}
   {:name "otel"        :blurb "OpenTelemetry tracing and metrics over OTLP"}
   {:name "nrepl"       :blurb "nREPL server and client for editors"}
   {:name "glimmer"     :blurb "reactive UI toolkit; you are looking at it"}])

;; --- components --------------------------------------------------------------
;; A row is a Form-1 component: a plain function of its arguments returning
;; hiccup. It closes over the library's name rather than its position, so the
;; handler stays correct however the filtered list moves around.
(defn- library-row [selected {:keys [name]}]
  [:button {:label name :on-click #(reset! selected name)}])

(defn- detail [selected]
  (let [lib (first (filter #(= @selected (:name %)) libraries))]
    [:vbox {:spacing 0 :margin 1}
     (if lib
       [:vbox {:spacing 0}
        [:label {:label (:name lib) :bold true}]
        [:label {:label (:blurb lib) :dim true}]]
       [:label {:label "Pick a library on the left." :dim true}])]))

(defn app
  "Form-2: the outer fn runs once on mount, so the state, cursors, reaction and
  background thread are all created a single time. The returned fn is what
  re-renders."
  []
  (let [state (atom {:query "" :selected nil :uptime 0})
        query (cursor state [:query])
        selected (cursor state [:selected])
        uptime (cursor state [:uptime])
        matches (reaction
                  (let [q (str/lower-case (str @query))]
                    (if (str/blank? q)
                      libraries
                      (filterv #(str/includes? (str/lower-case (:name %)) q)
                               libraries))))]
    ;; A worker thread writing a reactive cell: the repaint is marshalled onto
    ;; the UI thread by glimmer, so this needs no ceremony here.
    (future
      (loop []
        (Thread/sleep 1000)
        (swap! uptime inc)
        (recur)))
    (fn []
      [:vbox {:spacing 1 :margin 1}
       [:hbox {:spacing 2}
        [:label {:label "jolt libraries" :bold true}]
        [:label {:label (str (count @matches) " of " (count libraries))
                 :dim true}]
        [:label {:label (str "up " @uptime "s") :dim true :halign :end :hexpand true}]]

       [:hbox {:spacing 1}
        [:label {:label "filter:"}]
        [:entry {:text @query :placeholder "type to narrow…" :hexpand true
                 :on-change #(reset! query %)}]]

       [:hbox {:spacing 2 :vexpand true}
        [:frame {:label "libraries" :width-request 20}
         (into [:vbox {:spacing 0 :margin 1}]
               (if (empty? @matches)
                 [[:label {:label "no matches" :dim true}]]
                 (for [lib @matches]
                   ^{:key (:name lib)} [library-row selected lib])))]
        [:frame {:label "detail" :hexpand true}
         [detail selected]]]

       [:hbox {:spacing 2}
        [:button {:label "Clear filter" :on-click #(reset! query "")}]
        [:button {:label "Quit" :on-click tui/quit!}]]

       [:label {:label "tab: move   enter/space: press   click: select   ctrl-c: quit"
                :dim true}]])))

(defn -main [& _]
  (ui/run app))
