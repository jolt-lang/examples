(ns gl-demo.gothic
  "A Gothic cathedral nave built as a *declarative scene tree* from glimmer-gl
  primitives — the GL content equivalent of a glimmer widget tree. Every element
  is a [:mesh {:geom .. :material ..}] node inside [:group {:transform ..}]
  containers that position it. Nothing here touches OpenGL; `glimmer-gl.scene/
  flatten` compiles this into a render plan the renderer consumes.

  Composition is plain data: a column is a group of a base/shaft/capital
  (cuboids) topped by an octahedron finial; the nave places columns in rows,
  spans them with a ribbed ceiling, and anchors a gilded altar spire and rose
  window at the far end. The light + camera live in core.clj (they are reactive)."
  (:require [glimmer-gl.matrix    :as m]
            [glimmer-gl.primitives :as p]
            [glimmer-gl.polyhedra :as ph]
            [glimmer-gl.scene     :as scene]
            [clojure.math         :as math]))

;; material keywords -> base colors are resolved by the renderer; geometry here
;; only tags meshes with a material name.
(def ^:private stone    :stone)
(def ^:private dark     :dark-stone)
(def ^:private ground   :ground)
(def ^:private gold     :gold)
(def ^:private glass    :glass)

;; --- small scene-graph helpers ----------------------------------------------
(defn- mesh-node "A [:mesh] node with material + shadow opt merged in."
  ([mat geom] (mesh-node mat geom nil))
  ([mat geom opts]
   [:mesh (merge {:geom geom :material mat :cast-shadow true} opts)]))

(defn- place "Wrap children in a group translated to (x,y,z)."
  [x y z & kids]
  (apply scene/group (m/translation x y z) kids))

(defn- at-y "Wrap children in a group translated only vertically."
  [y & kids]
  (apply scene/group (m/translation 0.0 y 0.0) kids))

;; a box spanning [x0,x1]×[y0,y1]×[z0,z1] (min-corner + extent form of cuboid)
(defn- box [[x0 y0 z0] [x1 y1 z1] mat]
  (mesh-node mat (p/cuboid [x0 y0 z0] [(- x1 x0) (- y1 y0) (- z1 z0)])))

;; --- one Gothic column: base, shaft, capital, finial ------------------------
;; Built once at the local origin (base on the floor, y grows up); placed many
;; times. Reusing the same immutable subtree at several positions is fine —
;; `flatten` walks each occurrence and the renderer uploads its meshes once.
(defn column []
  (scene/group (m/ident)
    ;; base plinth
    (box [-0.9 0.0 -0.9] [0.9 0.7 0.9] stone)
    ;; slender shaft
    (box [-0.45 0.7 -0.45] [0.45 8.4 0.45] stone)
    ;; capital (flares wider)
    (box [-0.9 9.1 -0.9] [0.9 9.7 0.9] stone)
    ;; gilded octahedron finial (the pointed Gothic knob)
    (place 0.0 10.3 0.0 (mesh-node gold (ph/octahedron 0.75)))))

;; --- pointed arch rib spanning the nave at a given z ------------------------
;; Two beams leaning inward to a ridge apex — a pointed-arch silhouette. Each
;; beam is a long thin cuboid rotated about Z toward the apex.
(defn- pointed-rib [z span-x apex-y shaft-y]
  (let [half   span-x
        rise   (- apex-y shaft-y)
        run    half
        len    (Math/sqrt (+ (* rise rise) (* run run)))
        angle  (math/atan2 rise run)]
    (scene/group (m/ident)
      ;; left beam: from (-half, shaft-y, z) up to (0, apex-y, z)
      (scene/group
        (m/mul (m/translation (- half) shaft-y 0.0) (m/rotate-z angle))
        (box [-0.0 -0.3 -0.3] [len 0.3 0.6] dark))
      ;; right beam
      (scene/group
        (m/mul (m/translation half shaft-y 0.0) (m/rotate-z (- angle)))
        (box [(- len) -0.3 -0.3] [0.0 0.3 0.6] dark))
      ;; apex keystone
      (place 0.0 apex-y 0.0 (mesh-node gold (ph/tetrahedron 0.5))))))

;; --- the cathedral ----------------------------------------------------------
;; Geometry only; the reactive camera and light are attached by core.clj when it
;; wraps this subtree (plus [:camera] and [:light] nodes) in a root group.
;; Repeated elements are built with `for` (which returns the nodes) and spliced
;; into the group via concat — never `doseq`, which returns nil and drops them.
(defn cathedral []
  (let [nave-half 5.0
        ceiling-y 12.0
        z-far     -24.0
        col-zs    [8.0 4.0 0.0 -4.0 -8.0 -12.0 -16.0]
        ribs-z    [4.0 -2.0 -8.0 -14.0]
        static    [(box [-22.0 -0.5 -44.0] [22.0 0.0 14.0] ground)         ; floor
                   (box [4.5 0.0 -44.0] [5.5 ceiling-y 14.0] dark)          ; side wall R
                   (box [-5.5 0.0 -44.0] [-4.5 ceiling-y 14.0] dark)        ; side wall L
                   (box [-22.0 0.0 -24.5] [22.0 ceiling-y -23.5] dark)      ; far wall
                   (box [-5.5 ceiling-y -44.0] [5.5 (+ ceiling-y 0.5) 14.0] dark)  ; ceiling
                   (box [-0.25 (- ceiling-y 0.6) -44.0] [0.25 ceiling-y 14.0] stone) ; long rib
                   (place 0.0 0.0 z-far
                     (scene/group (m/ident)
                       (box [-1.6 0.0 -0.8] [1.6 1.2 0.8] stone)
                       (place 0.0 1.2 0.0 (mesh-node gold (p/tetrahedron 2.6))))) ; altar
                   (place 0.0 7.0 (+ z-far 0.3)
                     (scene/group (m/scaling 1.0 1.0 0.25)
                       (mesh-node glass (ph/dodecahedron 2.4))
                       (mesh-node gold  (ph/dodecahedron 1.1))))]           ; rose window
        trans-ribs (for [z ribs-z]
                     (box [-5.5 (- ceiling-y 0.6) (- z 0.25)] [5.5 ceiling-y (+ z 0.25)] stone))
        cols-r    (for [z col-zs] (place  nave-half 0.0 z (column)))
        cols-l    (for [z col-zs] (place (- nave-half) 0.0 z (column)))
        arches    (for [z ribs-z] (place 0.0 0.0 z (pointed-rib z nave-half 11.6 9.7)))]
    (apply scene/group (m/ident)
           (concat static trans-ribs cols-r cols-l arches))))
