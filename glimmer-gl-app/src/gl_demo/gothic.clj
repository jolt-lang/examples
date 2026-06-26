(ns gl-demo.gothic
  "A Gothic cathedral nave built as a *declarative scene tree* of glimmer-gl
  primitives — the GL content analogue of a glimmer widget tree, authored in the
  same reagent-style hiccup the widget demos use. Every node is a literal
  [:group {:transform ..} child...] / [:mesh {:geom .. :material ..}] vector;
  sub-trees are embedded as [component args] invocations (reagent Form-1
  components); repeated parts are (for ..) seqs spliced into their parent. There
  is no apply/concat assembly — the tree structure is the data, exactly like a
  glimmer [:vbox {:spacing 0} [title] [add-bar] (for [t ts] [row t])] tree.

  Nothing here touches OpenGL; glimmer-gl.scene/flatten compiles the tree into a
  render plan the renderer consumes. A column is a group of a base/shaft/capital
  (cuboids) topped by an octahedron finial; the nave places columns in rows,
  spans them with a ribbed ceiling, and anchors a gilded altar spire and rose
  window at the far end. The reactive camera + light live in core.clj; geometry
  here is static data, embedded into the reactive root via [cathedral]."
  (:require [glimmer-gl.matrix    :as m]
            [glimmer-gl.primitives :as p]
            [glimmer-gl.polyhedra :as ph]
            [clojure.math         :as math]))

;; material keywords -> base colors are resolved by the renderer; geometry here
;; only tags meshes with a material name.
(def ^:private stone  :stone)
(def ^:private dark   :dark-stone)
(def ^:private ground :ground)
(def ^:private gold   :gold)
(def ^:private glass  :glass)

;; --- leaf helpers (return hiccup, like glimmer-app's button/count-markup) -----
;; A [:mesh] node with material + shadow opt merged in.
(defn- mesh-node
  ([mat geom] (mesh-node mat geom nil))
  ([mat geom opts]
   [:mesh (merge {:geom geom :material mat :cast-shadow true} opts)]))

;; A [:mesh] box spanning [x0,x1]×[y0,y1]×[z0,z1] (min-corner + extent for cuboid).
(defn- box [[x0 y0 z0] [x1 y1 z1] mat]
  (mesh-node mat (p/cuboid [x0 y0 z0] [(- x1 x0) (- y1 y0) (- z1 z0)])))

;; --- one Gothic column: base, shaft, capital, finial -------------------------
;; A reagent Form-1 component: a fn returning hiccup. Placed many times as
;; [column] inside translated groups. Reusing the same component at several
;; positions is fine — flatten walks each occurrence and the renderer uploads its
;; meshes once.
(defn column []
  [:group {:transform (m/ident)}
   (box [-0.9 0.0 -0.9] [0.9 0.7 0.9] stone)          ; base plinth
   (box [-0.45 0.7 -0.45] [0.45 8.4 0.45] stone)      ; slender shaft
   (box [-0.9 9.1 -0.9] [0.9 9.7 0.9] stone)          ; capital
   [:group {:transform (m/translation 0.0 10.3 0.0)}  ; gilded finial
    (mesh-node gold (ph/octahedron 0.75))]])

;; --- pointed arch rib spanning the nave at a given z -------------------------
;; Two beams leaning inward to a ridge apex — a pointed-arch silhouette. Each
;; beam is a long thin cuboid rotated about Z toward the apex. Embedded as
;; [pointed-rib z ..] from the cathedral.
(defn- pointed-rib [z span-x apex-y shaft-y]
  (let [half  span-x
        rise  (- apex-y shaft-y)
        run   half
        len   (Math/sqrt (+ (* rise rise) (* run run)))
        angle (math/atan2 rise run)]
    [:group {:transform (m/ident)}
     [:group {:transform (m/mul (m/translation (- half) shaft-y 0.0) (m/rotate-z angle))}
      (box [0.0 -0.3 -0.3] [len 0.3 0.6] dark)]
     [:group {:transform (m/mul (m/translation half shaft-y 0.0) (m/rotate-z (- angle)))}
      (box [(- len) -0.3 -0.3] [0.0 0.3 0.6] dark)]
     [:group {:transform (m/translation 0.0 apex-y 0.0)}
      (mesh-node gold (ph/tetrahedron 0.5))]]))

;; --- the cathedral -----------------------------------------------------------
;; One hiccup [:group] whose children are positional: leaf helpers (box/mesh-node)
;; that return hiccup, nested [:group {:transform ..}] for placement, [component]
;; invocations for sub-trees, and (for ..) seqs spliced in for repetition. The
;; tree structure is the data — no apply/concat. Geometry only; core.clj wraps
;; this subtree (plus [:camera]/[:light]) in the reactive root.
(defn cathedral []
  (let [nave-half 5.0
        ceiling-y 12.0
        z-far     -24.0
        col-zs    [8.0 4.0 0.0 -4.0 -8.0 -12.0 -16.0]
        ribs-z    [4.0 -2.0 -8.0 -14.0]]
    [:group {:transform (m/ident)}
     ;; --- static structure ---
     (box [-22.0 -0.5 -44.0] [22.0 0.0 14.0] ground)                       ; floor
     (box [4.5 0.0 -44.0] [5.5 ceiling-y 14.0] dark)                       ; side wall R
     (box [-5.5 0.0 -44.0] [-4.5 ceiling-y 14.0] dark)                     ; side wall L
     (box [-22.0 0.0 -24.5] [22.0 ceiling-y -23.5] dark)                   ; far wall
     (box [-5.5 ceiling-y -44.0] [5.5 (+ ceiling-y 0.5) 14.0] dark)        ; ceiling
     (box [-0.25 (- ceiling-y 0.6) -44.0] [0.25 ceiling-y 14.0] stone)     ; long rib
     ;; --- altar at the far end ---
     [:group {:transform (m/translation 0.0 0.0 z-far)}
      (box [-1.6 0.0 -0.8] [1.6 1.2 0.8] stone)
      [:group {:transform (m/translation 0.0 1.2 0.0)}
       (mesh-node gold (p/tetrahedron 2.6))]]
     ;; --- rose window ---
     [:group {:transform (m/translation 0.0 7.0 (+ z-far 0.3))}
      [:group {:transform (m/scaling 1.0 1.0 0.25)}
       (mesh-node glass (ph/dodecahedron 2.4))
       (mesh-node gold  (ph/dodecahedron 1.1))]]
     ;; --- transverse ceiling ribs ---
     (for [z ribs-z]
       (box [-5.5 (- ceiling-y 0.6) (- z 0.25)] [5.5 ceiling-y (+ z 0.25)] stone))
     ;; --- nave columns, both aisles ---
     (for [z col-zs]
       [:group {:transform (m/translation nave-half 0.0 z)} [column]])
     (for [z col-zs]
       [:group {:transform (m/translation (- nave-half) 0.0 z)} [column]])
     ;; --- pointed arches spanning the nave ---
     (for [z ribs-z]
       [:group {:transform (m/translation 0.0 0.0 z)} [pointed-rib z nave-half 11.6 9.7]])]))
