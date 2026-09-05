(ns app.ui
  "Rendering. Every function here is a pure function of the Domino db (plus the
  event log), which is the whole point: no component owns state, and the SSE
  stream re-renders this same markup whenever the model changes."
  (:require [clojure.string :as str]
            [hiccup.core :as h]
            [jolt.datastar.core :as ds]
            [app.diagram :as diagram]
            [app.state :as state]))

;; ------------------------------------------------------------------ helpers

(defn- pct [x] (str (Math/round (* 100.0 (double (or x 0.0)))) "%"))

(defn- bytes-per-s [x]
  (let [x (double (or x 0.0))]
    (cond
      (>= x 1048576) (format "%.1f MB/s" (/ x 1048576))
      (>= x 1024)    (format "%.0f kB/s" (/ x 1024))
      :else          (format "%.0f B/s" x))))

(defn- ago [ms]
  (let [s (long (/ (- (System/currentTimeMillis) (or ms 0)) 1000))]
    (cond (< s 1) "just now"
          (< s 60) (str s "s ago")
          :else (str (long (/ s 60)) "m ago"))))

;; ---------------------------------------------------------------- sparkline

(defn sparkline
  "An SVG sparkline for a series, scaled to `scale-max` (or the series max)."
  [series {:keys [width height scale-max class]
           :or {width 340 height 52 class "spark"}}]
  (let [xs   (vec (map double series))
        n    (count xs)
        top  (double (or scale-max (max 0.001 (apply max 0.0 xs))))
        step (if (> n 1) (/ (double width) (dec n)) width)
        pt   (fn [i v] (str (Math/round (* i step)) ","
                            (Math/round (- height (* height (min 1.0 (/ v top)))))))
        line (str/join " " (map-indexed pt xs))]
    [:svg {:class class :viewBox (str "0 0 " width " " height)
           :preserveAspectRatio "none" :role "img"}
     (when (> n 1)
       (list
        [:polygon {:points (str "0," height " " line " " width "," height)
                   :class "spark-fill"}]
        [:polyline {:points line :class "spark-line"}]))]))

(defn- core-bars [cores]
  [:div.cores
   (for [[i load] (map-indexed vector cores)]
     [:div.core {:title (str "cpu" i " " (pct load))}
      [:div.core-fill {:style (str "height:" (Math/round (* 100.0 (min 1.0 (double (or load 0.0))))) "%")}]])])

(defn- metric [{:keys [label value sub series scale-max extra]}]
  [:section.metric
   [:header [:h2 label] [:span.value value] (when sub [:span.sub sub])]
   (sparkline series {:scale-max scale-max})
   extra])

;; ------------------------------------------------------------------ panels

(defn- alert-banner [{:keys [level since delivery]}]
  (let [{:keys [status attempt max error]} delivery]
    [:div.banner {:class (name (or level :ok))}
     [:div.banner-level (str/upper-case (name (or level :ok)))]
     [:div.banner-detail
      (case (or level :ok)
        :ok       "pressure within thresholds"
        :warn     (str "elevated since " (ago since))
        :critical (str "critical since " (ago since))
        "")]
     [:div.banner-delivery
      (case status
        :sending   (str "notifying sink · attempt " attempt "/" max)
        :retrying  (str "retry " attempt "/" max " · " error)
        :delivered (str "notified on attempt " attempt)
        :failed    (str "delivery failed after " attempt " attempts · " error)
        "")]]))

(defn- gauge [pressure warn crit]
  [:div.gauge
   [:div.gauge-track
    [:div.gauge-fill {:style (str "width:" (Math/round (* 100.0 (double (or pressure 0.0)))) "%")}]
    [:div.gauge-mark {:style (str "left:" (Math/round (* 100.0 (double (or warn 0.55)))) "%")}]
    [:div.gauge-mark.crit {:style (str "left:" (Math/round (* 100.0 (double (or crit 0.85)))) "%")}]]
   [:div.gauge-label "pressure index " [:b (pct pressure)]]])

(defn- lane-card [{:keys [label mode running? pid] :as lane} body]
  [:div.lane {:class (if running? "on" "off")}
   [:header
    [:span.lane-mode (name (or mode :poll))]
    [:h3 label]
    [:span.pid (if running? (str "pid " (or pid "—")) "stopped")]]
   body])

(defn- lanes-panel [{:keys [a b c]} probe sample-count interval]
  [:div.lanes
   (lane-card a
              [:dl
               [:dt "produced"] [:dd (:produced a 0)]
               [:dt "delivered"] [:dd (:delivered a 0)]
               [:dt "dropped"] [:dd {:class (when (pos? (:dropped a 0)) "hot")} (:dropped a 0)]
               [:dt "discipline"] [:dd "m/observe → m/relieve"]])
   (lane-card b
              [:dl
               [:dt "consumed"] [:dd (:consumed b 0)]
               [:dt "producer at"] [:dd (str "#" (:producer-seq b 0))]
               [:dt "free-running"] [:dd (str "#" (:free-running b 0))]
               [:dt "stalled by"] [:dd {:class (when (pos? (:stalled-by b 0)) "hot")}
                                   (str (:stalled-by b 0) " samples")]
               [:dt "discipline"] [:dd "m/via m/blk (demand)"]])
   (lane-card c
              [:dl
               [:dt "samples"] [:dd sample-count]
               [:dt "interval"] [:dd (str interval "ms")]
               [:dt "probe"] [:dd {:class (when (= :stale (:status probe)) "hot")}
                              (str (name (:status probe :off))
                                   (when (:took probe) (str " · " (:took probe) "ms")))]
               [:dt "discipline"] [:dd "m/ap + m/sleep (procfs polls)"]])])

(defn- cascade-panel [changes]
  (let []
    [:div.cascade
     [:p.hint "The paths the last transaction wrote, in execution order — one
               sample in, the whole derivation out."]
     [:ol
      (for [[path value] changes]
        [:li
         [:code (str/join " → " (map name path))]
         [:span.val (let [s (pr-str value)]
                      (if (> (count s) 70) (str (subs s 0 70) "…") s))]])]]))

(defn- log-panel [entries]
  [:div.log
   (for [{:keys [kind text at]} (reverse entries)]
     [:div.line {:class (name kind)}
      [:span.tag (case kind :domino "domino" :ebb "ebb" "error")]
      [:span.txt text]
      [:span.when (ago at)]])])

(defn- diagram-panel []
  (let [{:keys [nodes edges width height]} (diagram/layout state/schema)]
    [:div.diagram
     [:p.hint "Generated from the schema at render time: model paths, events
               and effects, laid out by dependency depth."]
     [:div.diagram-scroll
      [:svg {:viewBox (str "0 0 " width " " height)
             :width width :height height}
       (for [{:keys [x1 y1 x2 y2]} edges]
         [:path {:d (str "M" x1 "," y1 " C" (+ x1 30) "," y1 " " (- x2 30) "," y2 " " x2 "," y2)
                 :class "edge"}])
       (for [{:keys [x y w h kind label]} nodes]
         (list
          [:rect {:x x :y y :width w :height h :rx 6 :class (str "node " (name kind))}]
          [:text {:x (+ x (/ w 2)) :y (+ y 18) :class "node-label"} label]))]]]))

;; ---------------------------------------------------------------- controls

(defn- slider [{:keys [label signal route min max step suffix value]}]
  [:label.control
   [:span.control-label label]
   [:input {:type "range" :min min :max max :step step
            :data-bind signal
            "data-on:change" (str "@get('" route "')")}]
   [:output {:data-text (str "$" signal)} value]
   (when suffix [:span.suffix suffix])])

(defn- controls-panel
  "NB: not `controls` -- the fragment destructures a local of that name out of
  the db, and a shadowed render fn silently renders nothing."
  [db]
  (let [{:keys [controls burst export]} db]
    [:div.controls
     [:div.control-group
      [:h3 "Ingestion"]
      [:div.buttons
       (for [[k lane-label] [[:a "lane A"] [:b "lane B"] [:c "lane C"]]]
         [:button {"data-on:click" (str "@get('/lanes/" (name k) "/toggle')")
                   :class (if (get-in db [:lanes k :running?]) "on" "off")}
          (if (get-in db [:lanes k :running?]) (str "pause " lane-label) (str "start " lane-label))])]
      (slider {:label "sample interval" :signal "interval" :route "/controls/interval"
               :min 100 :max 2000 :step 100 :suffix "ms" :value (:interval-ms controls)})
      (slider {:label "consumer delay" :signal "delay" :route "/controls/delay"
               :min 0 :max 400 :step 10 :suffix "ms" :value (:consumer-delay controls)})]

     [:div.control-group
      [:h3 "Derivation"]
      (slider {:label "window" :signal "window" :route "/controls/window"
               :min 5 :max 200 :step 5 :suffix "samples" :value (:window controls)})
      (slider {:label "warn at" :signal "warn" :route "/controls/warn"
               :min 0.05 :max 1 :step 0.05 :value (:warn controls)})
      (slider {:label "critical at" :signal "crit" :route "/controls/crit"
               :min 0.05 :max 1 :step 0.05 :value (:crit controls)})]

     [:div.control-group
      [:h3 "Effects"]
      (slider {:label "sink failure rate" :signal "sink" :route "/controls/sink"
               :min 0 :max 1 :step 0.1 :value (:sink-failure controls)})
      [:div.buttons
       [:button {"data-on:click" "@get('/controls/mute')"
                 :class (if (:muted? controls) "on" "off")}
        (if (:muted? controls) "unmute alerts" "mute alerts")]
       [:button {"data-on:click" "@get('/controls/probe')"
                 :class (if (:slow-probe? controls) "on" "off")}
        (if (:slow-probe? controls) "probe: slow" "probe: fast")]]
      (slider {:label "burst (spinners)" :signal "burst" :route "/burst"
               :min 0 :max 8 :step 1 :value (:count burst 0)})
      [:div.buttons
       (if (= :running (:state export))
         [:button.danger {"data-on:click" "@get('/export/cancel')"}
          (str "cancel export · " (:written export 0) "/" (:total export 0))]
         [:button {"data-on:click" "@get('/export')"} "export snapshot"])]
      [:p.export-status
       (case (:state export)
         :done      (str "wrote " (:path export))
         :cancelled "export cancelled, partial file removed"
         :failed    (str "export failed: " (:error export))
         :running   "writing…"
         "")]]]))

;; ---------------------------------------------------------------- fragment

(defn fragment
  "The live region, rendered from one published snapshot.

  Reading a single ratom is also what keeps each SSE connection to a single
  watcher registration per render -- see the note in `app.state`."
  []
  (let [{:keys [db cascade log]} @state/view
        {:keys [sample history stats alert pressure controls probe lanes]} db
        cpu     (map :cpu history)
        mem     (map :mem history)
        net     (map :net history)]
    [:div#app-body
     (alert-banner alert)
     (gauge pressure (:warn controls) (:crit controls))
     [:div.grid
      [:div.metrics
       (metric {:label "CPU" :value (pct (:cpu sample))
                :sub (str "avg " (pct (get-in stats [:cpu :avg])) " over " (:window controls))
                :series cpu :scale-max 1.0
                :extra (core-bars (:cores sample))})
       (metric {:label "Memory" :value (pct (:mem sample))
                :sub (str (long (/ (:mem-used-kb sample 0) 1024)) " MB of "
                          (long (/ (:mem-total-kb sample 0) 1024)) " MB")
                :series mem :scale-max 1.0})
       (metric {:label "Network" :value (bytes-per-s (+ (:net-rx sample 0.0) (:net-tx sample 0.0)))
                :sub (str "rx " (bytes-per-s (:net-rx sample)) " · tx " (bytes-per-s (:net-tx sample)))
                :series net})]
      [:div.side
       [:nav.tabs
        (for [[k label] [["lanes" "Lanes"] ["cascade" "Cascade"] ["model" "Model"] ["log" "Log"]]]
          [:button {"data-on:click" (str "$tab = '" k "'")
                    "data-class" (str "{active: $tab == '" k "'}")}
           label])]
       [:div.panel {"data-show" "$tab == 'lanes'"}
        (lanes-panel lanes probe (count history) (:interval-ms controls))]
       [:div.panel {"data-show" "$tab == 'cascade'"} (cascade-panel cascade)]
       [:div.panel {"data-show" "$tab == 'model'"} (diagram-panel)]
       [:div.panel {"data-show" "$tab == 'log'"} (log-panel log)]]]
     (controls-panel db)]))

;; -------------------------------------------------------------------- page

(def styles "
:root{--bg:#0e1116;--panel:#161b22;--line:#232b36;--fg:#e6edf3;--dim:#8b949e;
      --accent:#58a6ff;--ok:#3fb950;--warn:#d29922;--crit:#f85149}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);
     font:13px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace}
h1{font-size:15px;margin:0;letter-spacing:.08em;text-transform:uppercase}
h2{font-size:12px;margin:0;color:var(--dim);letter-spacing:.1em;text-transform:uppercase}
h3{font-size:11px;margin:0 0 8px;color:var(--dim);letter-spacing:.1em;text-transform:uppercase}
header.top{display:flex;align-items:baseline;gap:16px;padding:14px 20px;
           border-bottom:1px solid var(--line);background:var(--panel)}
header.top .sub{color:var(--dim);font-size:12px}
main{padding:16px 20px 40px;max-width:1500px;margin:0 auto}
.banner{display:grid;grid-template-columns:110px 1fr auto;gap:12px;align-items:center;
        padding:10px 14px;border-radius:8px;border:1px solid var(--line);margin-bottom:12px}
.banner.ok{border-color:#1f3d29}.banner.warn{border-color:#5c4610;background:#2a220c}
.banner.critical{border-color:#6e2b26;background:#2d1513}
.banner-level{font-weight:700;letter-spacing:.12em}
.banner.ok .banner-level{color:var(--ok)}.banner.warn .banner-level{color:var(--warn)}
.banner.critical .banner-level{color:var(--crit)}
.banner-delivery{color:var(--dim)}
.gauge{margin-bottom:16px}
.gauge-track{position:relative;height:8px;background:#0b0e13;border:1px solid var(--line);
             border-radius:4px;overflow:hidden}
.gauge-fill{height:100%;background:linear-gradient(90deg,#1f6feb,#f85149)}
.gauge-mark{position:absolute;top:-3px;width:2px;height:14px;background:var(--warn)}
.gauge-mark.crit{background:var(--crit)}
.gauge-label{color:var(--dim);margin-top:6px}
.grid{display:grid;grid-template-columns:minmax(340px,1fr) minmax(420px,1.2fr);gap:16px}
.metric{background:var(--panel);border:1px solid var(--line);border-radius:8px;
        padding:12px;margin-bottom:12px}
.metric header{display:flex;align-items:baseline;gap:10px;margin-bottom:8px}
.metric .value{font-size:20px;margin-left:auto}
.metric .sub{color:var(--dim);font-size:11px;width:100%;order:3}
.spark{width:100%;height:52px;display:block}
.spark-line{fill:none;stroke:var(--accent);stroke-width:1.5;vector-effect:non-scaling-stroke}
.spark-fill{fill:rgba(88,166,255,.14);stroke:none}
.cores{display:flex;gap:3px;height:26px;margin-top:8px;align-items:flex-end}
.core{flex:1;height:100%;background:#0b0e13;border-radius:2px;display:flex;align-items:flex-end}
.core-fill{width:100%;background:var(--accent);border-radius:2px;min-height:2px}
.side{background:var(--panel);border:1px solid var(--line);border-radius:8px;overflow:hidden}
.tabs{display:flex;border-bottom:1px solid var(--line)}
.tabs button{flex:1;background:none;border:0;border-bottom:2px solid transparent;
             color:var(--dim);padding:10px;cursor:pointer;font:inherit}
.tabs button.active{color:var(--fg);border-bottom-color:var(--accent)}
.panel{padding:12px;max-height:560px;overflow:auto}
.hint{color:var(--dim);margin:0 0 10px}
.lanes{display:flex;flex-direction:column;gap:10px}
.lane{border:1px solid var(--line);border-radius:6px;padding:10px}
.lane.off{opacity:.55}
.lane header{display:flex;align-items:center;gap:8px;margin-bottom:8px}
.lane h3{margin:0;color:var(--fg);text-transform:none;letter-spacing:0;font-size:12px}
.lane-mode{background:#1f6feb33;color:var(--accent);border-radius:4px;padding:1px 6px;font-size:10px}
.lane .pid{margin-left:auto;color:var(--dim);font-size:11px}
.lane dl{display:grid;grid-template-columns:auto 1fr;gap:2px 12px;margin:0}
.lane dt{color:var(--dim)}.lane dd{margin:0;text-align:right}
.lane dd.hot{color:var(--crit)}
.cascade ol{margin:0;padding-left:20px}
.cascade li{margin-bottom:3px}
.cascade code{color:var(--accent)}
.cascade .val{color:var(--dim);margin-left:8px}
.log .line{display:flex;gap:8px;padding:2px 0;border-bottom:1px solid #1b212a}
.log .tag{width:52px;color:var(--dim)}
.log .line.ebb .tag{color:var(--accent)}
.log .line.domino .tag{color:var(--ok)}
.log .line.error .tag{color:var(--crit)}
.log .txt{flex:1}
.log .when{color:var(--dim)}
.diagram-scroll{overflow:auto}
.node{fill:#0b0e13;stroke:var(--line)}
.node.event{fill:#132133;stroke:var(--accent)}
.node.effect{fill:#2a1c10;stroke:var(--warn)}
.node-label{fill:var(--fg);font-size:11px;text-anchor:middle;font-family:inherit}
.edge{fill:none;stroke:#30363d;stroke-width:1}
.controls{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:16px;
          margin-top:16px}
.control-group{background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:12px}
.control{display:flex;align-items:center;gap:8px;margin-bottom:8px}
.control-label{width:120px;color:var(--dim)}
.control input[type=range]{flex:1;accent-color:var(--accent)}
.control output{width:48px;text-align:right}
.suffix{color:var(--dim);width:52px}
.buttons{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:10px}
button{background:#1b212a;color:var(--fg);border:1px solid var(--line);border-radius:6px;
       padding:6px 10px;cursor:pointer;font:inherit}
button:hover{border-color:var(--accent)}
button.on{border-color:var(--accent);color:var(--accent)}
button.danger{border-color:var(--crit);color:var(--crit)}
.export-status{color:var(--dim);margin:6px 0 0;min-height:18px}
")

(defn page
  "The full document. init-opts seeds the datastar signals (slider positions
  and the selected tab, which are per-tab UI state the server has no business
  owning) and opens the SSE stream on #app."
  []
  (let [{:keys [controls burst]} (:db @state/view)
        opts (ds/init-opts
              {:selector "#app"
               :signals {:tab      "lanes"
                         :window   (:window controls)
                         :interval (:interval-ms controls)
                         :warn     (:warn controls)
                         :crit     (:crit controls)
                         :delay    (:consumer-delay controls)
                         :sink     (:sink-failure controls)
                         :burst    (:count burst 0)}})]
    (str "<!DOCTYPE html>"
         (h/html
          [:html
           [:head
            [:meta {:charset "utf-8"}]
            [:meta {:name "viewport" :content "width=device-width,initial-scale=1"}]
            [:title "live pipeline dashboard · domino + ebb"]
            [:script {:type "module" :src "/js/datastar.js"}]
            [:style styles]]
           [:body
            [:header.top
             [:h1 "live pipeline dashboard"]
             [:span.sub "ebb streams · domino derives · datastar renders"]]
            [:main [:div#app opts (fragment)]]]]))))
