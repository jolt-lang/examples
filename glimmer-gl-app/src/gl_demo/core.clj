(ns gl-demo.core
  "Reactive Gothic shadow demo. The control panel is ordinary glimmer hiccup
  (sliders/buttons whose atoms the renderer reads each frame), and the 3D scene
  is a *declarative tree* — a camera + light + gothic cathedral assembled as
  glimmer-gl.scene nodes. Nothing in this file calls GL imperatively to build
  the world: on-render flattens the tree to a plan and hands it to the renderer,
  which is the only place raw draw calls live. Reactivity flows from the panel
  atoms into the scene data; interpretation stays separate, exactly as glimmer
  keeps hiccup apart from the reconciler."
  (:require [glimmer.core     :as ui]
            [glimmer.ratom    :as r]
            [glimmer-gl.gtk    :as glx]     ; side-effect: registers :gl-area + :scale
            [glimmer-gl.gl     :as gl]
            [glimmer-gl.matrix :as m]
            [glimmer-gl.scene  :as gscene]
            [gl-demo.gothic    :as gothic]
            [gl-demo.renderer  :as renderer]))

(def ^:private ^double deg->rad (/ Math/PI 180.0))   ; Jolt has no Math/toRadians

;; --- reactive controls (sliders -> atoms the scene reads each frame) --------
(defonce light-az    (r/atom 38.0))    ; sun azimuth (deg)
(defonce light-el    (r/atom 42.0))    ; sun elevation (deg)
(defonce ambient     (r/atom 0.10))
(defonce shadow-bias (r/atom 0.002))
(defonce cam-orbit   (r/atom 0.0))     ; camera azimuth around the nave (deg)
(defonce auto-rotate (r/atom true))
(defonce paused      (r/atom false))

;; --- per-area GL state ------------------------------------------------------
(defonce reported?    (r/atom false)) ; print the first render failure once, then stay quiet
(defonce the-renderer (r/atom nil))   ; created on realize
(defonce viewport     (r/atom [960 600]))

(defn on-realize [area]
  (gl/gl-enable gl/GL-DEPTH-TEST)
  (gl/gl-enable gl/GL-CULL-FACE)
  (gl/gl-front-face gl/GL-CCW)
  (reset! the-renderer (renderer/make-renderer!)))

(defn on-resize [_area w h]
  (reset! viewport [(max w 1) (max h 1)]))

(defn on-tick [_area]
  (when (and @auto-rotate (not @paused))
    ;; Jolt's `mod` is integer-only, so wrap by hand (increment is small & positive)
    (swap! cam-orbit (fn [v] (let [w (+ v 0.25)] (if (>= w 360.0) (- w 360.0) w))))))

(defn on-render [_area]
  (let [st @the-renderer]
    (when st
      (try
       (let [to-rad #(* (double %) deg->rad)
            laz    (to-rad @light-az)
            lel    (to-rad @light-el)
            orb    (to-rad @cam-orbit)
            amb    (double @ambient)
            bias   (double @shadow-bias)
            target [0.0 4.0 -8.0]
            eye    [(+ (* (Math/sin orb) 16.0) (nth target 0))
                    5.5
                    (+ (* (Math/cos orb) 16.0) (nth target 2))]
            [cw ch] @viewport
            view   (m/look-at eye target [0.0 1.0 0.0])
            proj   (m/perspective 55.0 (/ (double cw) (double ch)) 0.1 200.0)
            ;; sun direction (scene -> sun); light travels along its negation
            sx     (* (Math/cos lel) (Math/sin laz))
            sy     (Math/sin lel)
            sz     (* (Math/cos lel) (Math/cos laz))
            ldir   [(- sx) (- sy) (- sz)]
            leye   [(+ (nth target 0) (* sx 42.0))
                    (+ (nth target 1) (* sy 42.0))
                    (+ (nth target 2) (* sz 42.0))]
            lview  (m/look-at leye target [0.0 1.0 0.0])
            lproj  (m/ortho -32.0 32.0 -32.0 32.0 1.0 130.0)
            ;; declarative scene: camera + light + gothic geometry as one tree
            plan   (gscene/flatten
                     (gscene/group (m/ident)
                       (gscene/camera {:eye eye :target target :up [0 1 0]
                                      :fov 55 :near 0.1 :far 200})
                       (gscene/light {:dir ldir :color [1.0 0.95 0.82]})
                       (gothic/cathedral)))]
         (renderer/draw! st
           {:plan        plan
            :view        view :proj proj :eye eye
            :canvas      [cw ch] :bg [0.03 0.03 0.04]
            :light       {:dir ldir :color [1.0 0.95 0.82]
                          :lview lview :lproj lproj}
            :ambient     [amb amb amb]
            :shadow-bias bias
            :fog         {:near 8.0 :far 50.0 :color [0.03 0.03 0.04]}}))
       (catch Exception e
         ;; Jolt GL errors surface as host conditions, not ex-info; condition-message
         ;; is the reliable extractor. Print once to avoid flooding the tick loop.
         (when (compare-and-set! reported? false true)
           (let [msg (or (ex-message e)
                         (try ((resolve 'jolt.host/condition-message) e)
                              (catch :default _ nil)))]
             (println "[on-render] FAILED:" (or msg (pr-str e))))))))))

;; --- control panel (glimmer hiccup; atoms above are the source of truth) -----
(defn- slider [cell min max step digits]
  [:scale {:min min :max max :step step :value @cell :digits digits
           :hexpand true :on-value #(reset! cell %)}])

(defn- row [caption control]
  [:hbox {:spacing 8}
   [:label {:label caption :width 130 :xalign 0.0}] control])

(defn control-panel []
  [:vbox {:spacing 4 :margin 6}
   (row "light azimuth"   (slider light-az 0 360 1 0))
   (row "light elevation" (slider light-el 8 80 1 0))
   (row "ambient"         (slider ambient 0 0.4 0.01 2))
   (row "shadow bias"     (slider shadow-bias 0 0.02 0.0005 4))
   (row "camera orbit"    (slider cam-orbit 0 360 1 0))
   [:hbox {:spacing 8}
    [:button {:label (if @auto-rotate "auto-rotate: on" "auto-rotate: off")
              :on-click #(swap! auto-rotate not)}]
    [:button {:label (if @paused "running (frozen)" "paused")
              :on-click #(swap! paused not)}]]])

(defn app []
  [:vbox {:spacing 0}
   [control-panel]
   [:separator {}]
   [:gl-area {:version [3 2] :depth-buffer true :hexpand true :vexpand true
              :on-realize on-realize :on-render on-render
              :on-resize on-resize :on-tick on-tick}]])

(defn -main [& _]
  ;; GLIMMER_GL_DEMO_QUIT_MS auto-closes the window after N ms (smoke testing);
  ;; unset, the window stays open until closed.
  (let [quit-ms (some-> (System/getenv "GLIMMER_GL_DEMO_QUIT_MS") Integer/parseInt)]
    (apply ui/run app
           :app-id "dev.jolt.glimmer-gl-demo"
           :title  "glimmer-gl • gothic shadows"
           :width  960 :height 660
           (when quit-ms [:auto-quit-ms quit-ms]))))
