(ns gl-demo.level
  "Declarative first-person level: geometry + collision AABBs + a reactive scene
  builder. Layout along +X:

    ENTRY (spawn) --corridor-- ROTUNDA (button + rotating gate) --exit--> win
                          |
                       alcove (non-Euclidean loop: stepping into its dead end
                               teleports you back to the corridor entrance)

  Walk into the glowing button in the rotunda to swing the gate open (the
  'realign to open a pathway' puzzle), then reach the light past the gate."
  (:require [glimmer-gl.matrix    :as m]
            [glimmer-gl.primitives :as p]
            [glimmer-gl.renderer  :as renderer]
            [gl-demo.player       :as player]))

(def ^:const wall-h 3.0)     ; wall / room height
(def ^:const wall-t 0.3)     ; wall thickness

;; Custom palette merged over the renderer's built-in material colours.
(def materials
  (merge renderer/material-colors
         {:wall     [0.80 0.77 0.71]
          :floor    [0.26 0.23 0.20]
          :ceiling  [0.18 0.17 0.15]
          :accent   [0.50 0.42 0.32]
          :panel    [0.82 0.66 0.30]    ; the brass gate
          :button   [0.85 0.20 0.16]    ; glowing red button
          :goal     [0.96 0.90 0.55]})) ; the exit light

;; --- mesh helpers ------------------------------------------------------------

(defn- mesh-box
  "A solid box mesh spanning [x0,x1] x [y0,y1] x [z0,z1] (min-corner + extent
  for primitives/cuboid)."
  [mat x0 y0 z0 x1 y1 z1]
  [:mesh {:geom     (p/cuboid [x0 y0 z0] [(- x1 x0) (- y1 y0) (- z1 z0)])
          :material mat
          :cast-shadow true}])

(defn- wall
  "A full-height wall segment; returns its collision AABB and its mesh."
  [mat xmin zmin xmax zmax]
  {:aabb [xmin xmax zmin zmax]
   :mesh (mesh-box mat xmin 0 zmin xmax wall-h zmax)})

;; --- level geometry ----------------------------------------------------------

(def ^:private wall-specs
  [(wall :wall -6.3 -3.0  -6.0  3.0)   ; entry: west wall
   (wall :wall  0.0  1.0   0.3  3.0)   ; entry: east wall, above doorway
   (wall :wall  0.0 -3.0   0.3 -1.0)   ; entry: east wall, below doorway
   (wall :wall -6.0  3.0   0.0  3.3)   ; entry: north wall
   (wall :wall -6.0 -3.3   0.0 -3.0)   ; entry: south wall
   (wall :wall  0.0  1.0   4.0  1.3)   ; corridor: north wall
   (wall :wall  0.0 -1.3   1.5 -1.0)   ; corridor: south wall (left of alcove)
   (wall :wall  2.5 -1.3   4.0 -1.0)   ; corridor: south wall (right of alcove)
   (wall :wall  1.2 -4.0   1.5 -1.0)   ; alcove: west wall
   (wall :wall  2.5 -4.0   2.8 -1.0)   ; alcove: east wall
   (wall :wall  1.5 -4.3   2.5 -4.0)   ; alcove: far (south) wall
   (wall :wall  4.0 -3.0   4.3 -1.0)   ; rotunda: west wall, below doorway
   (wall :wall  4.0  1.0   4.3  3.0)   ; rotunda: west wall, above doorway
   (wall :wall 12.0 -3.0  12.3 -1.0)   ; rotunda: east wall, below exit
   (wall :wall 12.0  1.0  12.3  3.0)   ; rotunda: east wall, above exit
   (wall :wall  4.0  3.0  12.0  3.3)   ; rotunda: north wall
   (wall :wall  4.0 -3.3  12.0 -3.0)]) ; rotunda: south wall

(def ^:private floors
  [(mesh-box :floor   -6.0 0.0 -3.0  0.0 0.05 3.0)   ; entry
   (mesh-box :floor    0.0 0.0 -1.0  4.0 0.05 1.0)   ; corridor
   (mesh-box :floor    1.5 0.0 -4.0  2.5 0.05 -1.0)  ; alcove
   (mesh-box :floor    4.0 0.0 -3.0 12.0 0.05 3.0)]) ; rotunda

(def ^:private ceilings
  [(mesh-box :ceiling -6.0 2.95 -3.0  0.0 3.0 3.0)
   (mesh-box :ceiling  0.0 2.95 -1.0  4.0 3.0 1.0)
   (mesh-box :ceiling  1.5 2.95 -4.0  2.5 3.0 -1.0)
   (mesh-box :ceiling  4.0 2.95 -3.0 12.0 3.0 3.0)])

;; button: a short pedestal against the rotunda's north wall + a glowing cap.
(def ^:private button-meshes
  [(mesh-box :accent 9.60 0.0 2.45 10.40 1.15 3.00)
   (mesh-box :button 9.72 1.15 2.55 10.28 1.45 2.92)])

;; a glowing panel past the exit — the light you walk toward.
(def ^:private goal-mesh
  (mesh-box :goal 12.3 0.0 -1.0 14.0 wall-h 1.0))

;; the rotating gate: a brass panel hinged at the top of the exit doorway.
;; Closed (angle 0) it spans the doorway z in [-1,1] at x~12; swung open (+pi/2)
;; it folds along the rotunda's east wall. See player/tick! for the angle drive.
(def ^:private gate-pivot-x 12.0)
(def ^:private gate-pivot-z 1.0)
(def ^:private gate-panel
  (p/cuboid [-0.08 0.0 -2.0] [0.16 wall-h 2.0]))

(defn- gate-group [angle]
  [:group {:transform (m/mul (m/translation gate-pivot-x 0.0 gate-pivot-z)
                             (m/rotate-y angle))}
   [:mesh {:geom gate-panel :material :panel :cast-shadow true}]])

;; --- collision + interaction volumes -----------------------------------------

(def ^:private walls
  (mapv :aabb wall-specs))

(def ^:private gate-aabb
  [11.8 12.2 -1.05 1.05])   ; blocks the exit doorway while the gate is closed

(def ^:private button-aabb
  [9.3 10.7 2.1 3.05])      ; walk into this volume to press the button

(def ^:private seam
  ;; stepping into the alcove's dead end drops you back at the corridor entrance
  ;; — a non-adjacent (impossible) connection. dest is [x z yaw].
  {:trigger [1.5 2.5 -4.0 -3.4]
   :dest    [0.4 0.0 0.0]})

(def ^:private win-aabb
  [12.35 16.0 -1.0 1.0])    ; past the open gate = escape

(def ^:private wall-meshes
  (mapv :mesh wall-specs))

(def ctx
  "Level context handed to player/tick! each frame."
  {:walls       walls
   :gate-aabb   gate-aabb
   :button-aabb button-aabb
   :seam        seam
   :win-aabb    win-aabb})

(def spawn [-3.0 player/eye-height 0.0])

;; --- reactive scene builder --------------------------------------------------

(defn- static-geometry []
  (vec (concat floors ceilings wall-meshes button-meshes [goal-mesh])))

(defn scene-root
  "Return a zero-arg scene function that derefs the player's cells and emits the
  hiccup scene tree (camera + light + level geometry, with the gate swinging)."
  [p]
  (fn []
    (let [[px py pz] @(:pos p)
          yaw        @(:yaw p)
          pitch      @(:pitch p)
          [fx fy fz] (player/forward-vert yaw pitch)
          gate-angle @(:gate-angle p)]
      (into [:group {:transform (m/ident)}
             [:camera {:eye    [px py pz]
                       :target [(+ px fx) (+ py fy) (+ pz fz)]
                       :up     [0 1 0]
                       :fov 68 :near 0.05 :far 120}]
             [:light {:dir [-0.35 -0.8 -0.5] :color [1.0 0.95 0.82]
                      :eye-dist 28 :bounds 24 :near 1 :far 90}]]
            (conj (static-geometry) (gate-group gate-angle))))))
