(ns gl-demo.core
  "Reactive Gothic shadow demo — the GL view fills the window and the mouse
  drives the camera through reactive atoms.

  This file is pure data + cells. The 3D scene is a declarative tree — camera +
  light + gothic cathedral as glimmer-gl.scene nodes, returned by a component
  that derefs those atoms. The mouse's normalized position is written into
  cam-orbit/cam-elev by :on-motion; scene/plan's reaction recomputes the plan,
  and the library's render loop redraws. Nothing here calls GL or GTK: the whole
  render loop (plan reaction, matrix derivation, two-pass shadow draw, the motion
  event controller) is owned by glimmer-gl.app/reactive-area. Reactivity flows
  from mouse atoms into scene data; interpretation stays in the library."
  (:require [glimmer.core         :as ui]
            [glimmer.ratom        :as r]
            [glimmer-gl.gtk]               ; side-effect: registers :gl-area + :scale
            [glimmer-gl.matrix    :as m]
            [glimmer-gl.app       :as glapp]
            [glimmer-gl.renderer  :as renderer]
            [gl-demo.gothic       :as gothic]))

(def ^:private ^double deg->rad (/ Math/PI 180.0))   ; Jolt has no Math/toRadians
(def ^:private target [0.0 4.0 -8.0])
(def ^:private ^double cam-radius 22.0)

;; --- reactive controls (cells the scene component derefs) --------------------
;; The mouse writes cam-orbit/cam-elev via :on-motion; scene/plan registers as
;; their watcher, so each write recomputes only the plan, never the renderer.
;; light/ambient/bias stay as parameter atoms (read each frame, no panel writes).
(defonce light-az    (r/atom 38.0))    ; sun azimuth (deg)
(defonce light-el    (r/atom 42.0))    ; sun elevation (deg)
(defonce ambient     (r/atom 0.10))
(defonce shadow-bias (r/atom 0.002))
(defonce cam-orbit   (r/atom 0.0))     ; camera azimuth around the nave (deg)
(defonce cam-elev    (r/atom 35.0))    ; camera elevation above the floor (deg)

;; --- mouse -> cells ----------------------------------------------------------
;; reactive-area normalizes pointer coords to [-1,1] over the GL area and calls
;; this on every motion. X sweeps the camera around the nave (centre = front
;; view); Y sets the elevation (top of the window = high vantage, bottom = low).
(defn- on-motion [nx ny]
  (let [az (let [a (* nx 180.0)] (if (neg? a) (+ a 360.0) a))   ; [-1,1] -> [0,360)
        el (- 75.0 (* (+ ny 1.0) 35.0))]                        ; [-1,1] -> [75,5]
    (reset! cam-orbit az)
    (reset! cam-elev el)))

;; --- the scene, as a reactive component --------------------------------------
;; Derefs of light/camera cells here register the plan (built by glapp/reactive-area
;; via scene/plan) as their watcher — changing one recomputes only the plan.
;; Camera and light are declared once, as nodes; the library derives view/proj and
;; the shadow frustum from them, so there is no duplicate per-frame matrix math.
;; The eye is spherical: yaw (cam-orbit) + pitch (cam-elev) around `target`.
(defn scene-root []
  (let [to-rad #(* (double %) deg->rad)
        laz    (to-rad @light-az)
        lel    (to-rad @light-el)
        yaw    (to-rad @cam-orbit)
        pitch  (to-rad @cam-elev)
        cp     (Math/cos pitch)
        eye    [(+ (nth target 0) (* cp (Math/sin yaw) cam-radius))
                (+ (nth target 1) (* (Math/sin pitch) cam-radius))
                (+ (nth target 2) (* cp (Math/cos yaw) cam-radius))]
        sx     (* (Math/cos lel) (Math/sin laz))
        sy     (Math/sin lel)
        sz     (* (Math/cos lel) (Math/cos laz))
        ldir   [(- sx) (- sy) (- sz)]]     ; direction the light travels
    [:group {:transform (m/ident)}
     [:camera {:eye eye :target target :up [0 1 0]
               :fov 55 :near 0.1 :far 200}]
     [:light {:dir ldir :color [1.0 0.95 0.82]
              :eye-dist 42.0 :bounds 32.0 :near 1.0 :far 130.0}]
     [gothic/cathedral]]))

;; --- the app: just the GL view filling the window ----------------------------
;; No control panel — the mouse is the interface. reactive-area owns the GL
;; lifecycle + the motion event controller; the scene reads the cells it mutates.
(defn app []
  [:gl-area (glapp/reactive-area scene-root
              {:bg          [0.03 0.03 0.04]
               :ambient     ambient            ; cell -> dereffed each frame
               :shadow-bias shadow-bias        ; cell -> dereffed each frame
               :fog         {:near 8.0 :far 50.0 :color [0.03 0.03 0.04]}
               :materials   renderer/material-colors
               :on-motion   on-motion})])

(defn -main [& _]
  ;; GLIMMER_GL_DEMO_QUIT_MS auto-closes the window after N ms (smoke testing);
  ;; unset, the window stays open until closed.
  (let [quit-ms (some-> (System/getenv "GLIMMER_GL_DEMO_QUIT_MS") Integer/parseInt)]
    (apply ui/run app
           :app-id "dev.jolt.glimmer-gl-demo"
           :title  "glimmer-gl • gothic shadows"
           :width  960 :height 660
           (when quit-ms [:auto-quit-ms quit-ms]))))
