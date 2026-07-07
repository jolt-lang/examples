(ns fps-demo.overlay
  "Pure overlay geometry builders for the q1k3 port. Produces flat interleaved
  [x y z r g b ...] float lists for the flat shader (pos3 + color3, stride 6).
  GL-free and unit-tested; render.clj uploads and draws these.

  All builders return a Clojure vector of Float, 6 per vertex (2 triangles = 6
  verts = 36 floats per quad). Coordinate spaces:
    - hud-bar-verts:        screen pixels (drawn under an ortho projection)
    - particle-box-verts:   world units (drawn under proj*view)
    - viewmodel-verts:      screen pixels (drawn under ortho), bottom-right")

(def ^:const hud-margin   20.0)   ; px inset from bottom-left
(def ^:const hud-bar-w  200.0)
(def ^:const hud-bar-h   16.0)

(def ^:const particle-half 1.6)   ; blood blob half-size in world units

;; Unit cube corners in [-1,1]^3.
(def ^:private cube-corners
  [[-1.0 -1.0 -1.0] [1.0 -1.0 -1.0] [1.0 1.0 -1.0] [-1.0 1.0 -1.0]
   [-1.0 -1.0  1.0] [1.0 -1.0  1.0] [1.0 1.0  1.0] [-1.0 1.0  1.0]])

;; 12 triangles (CCW outward) as index triples into cube-corners.
(def ^:private cube-tris
  [[0 1 2] [0 2 3]   ; -Z
   [4 6 5] [4 7 6]   ; +Z
   [0 3 7] [0 7 4]   ; -X
   [1 5 6] [1 6 2]   ; +X
   [0 4 5] [0 5 1]   ; -Y
   [3 2 6] [3 6 7]]) ; +Y

(defn- quad
  "Two-triangle XY quad from (x0,y0) to (x1,y1) at depth z, colored [r g b].
  Returns 6 vertices × 6 floats (36 floats)."
  [x0 y0 x1 y1 z [r g b]]
  [x0 y0 z r g b
   x1 y0 z r g b
   x1 y1 z r g b
   x0 y0 z r g b
   x1 y1 z r g b
   x0 y1 z r g b])

(defn hud-bar-verts
  "Health bar: a dark background quad + a colored fill quad whose width tracks
  `fill` (0..1). Anchored bottom-left with a margin. 2 quads = 72 floats."
  [w h fill]
  (let [x0 hud-margin
        y0 hud-margin
        x1 (+ hud-margin hud-bar-w)
        y1 (+ hud-margin hud-bar-h)
        fill-x1 (+ x0 (* hud-bar-w (double fill)))
        bg   (quad x0 y0 x1 y1 0.0 [0.12 0.12 0.14])
        fcol (if (<= fill 0.25) [0.85 0.15 0.10] [0.15 0.75 0.20])
        fg   (if (<= fill 0.0) [] (quad x0 y0 fill-x1 y1 0.1 fcol))]
    (into bg fg)))

;; 36 corner refs (3 per tri × 12 tris) into cube-corners, in emit order, so the
;; per-particle loop is a flat aset stream with no intermediate allocation.
(def ^:private part-corner-idx
  (into [] (for [[a b c] cube-tris t [a b c]] t)))

(defn particle-box-verts
  "One axis-aligned cube per particle at its :pos, dark red, half-size
  particle-half. 36 verts × 6 floats per particle. Emits straight into a
  float-array (aset) rather than building Clojure vectors — the particle count
  changes every frame, so this runs hot."
  [particles]
  (let [s   (double particle-half)
        r   0.60 g 0.05 b 0.05
        out (float-array (* (count particles) 216))]
    (loop [ps particles base 0]
      (if (empty? ps) out
          (let [pos (:pos (first ps))
                px (double (pos 0)) py (double (pos 1)) pz (double (pos 2))
                nb (loop [i 0 k base]
                     (if (>= i 36) k
                         (let [c (nth cube-corners (nth part-corner-idx i))]
                           (aset out k       (+ px (* (double (c 0)) s)))
                           (aset out (inc k) (+ py (* (double (c 1)) s)))
                           (aset out (+ k 2) (+ pz (* (double (c 2)) s)))
                           (aset out (+ k 3) r)
                           (aset out (+ k 4) g)
                           (aset out (+ k 5) b)
                           (recur (inc i) (+ k 6)))))]
            (recur (rest ps) nb))))))

(defn viewmodel-verts
  "Procedural shotgun viewmodel in screen pixels (ortho), bottom-right. A few
  stacked quads suggest a barrel + pump + stock; when `flash?` is true an extra
  bright muzzle quad is appended near the barrel tip."
  [flash?]
  ;; Gun body: stock, receiver, barrel, pump — drawn bottom-right of the screen.
  (let [gun-gray   [0.20 0.20 0.22]
        gun-dark   [0.12 0.12 0.14]
        stock-brn  [0.30 0.20 0.12]
        quads [;; stock (lower-left of the gun)
               (quad  840.0  0.0 940.0 70.0 0.1 stock-brn)
               ;; receiver
               (quad  840.0 60.0 980.0 110.0 0.1 gun-dark)
               ;; barrel
               (quad  970.0 80.0 1180.0 98.0 0.1 gun-gray)
               ;; pump grip
               (quad  980.0 50.0 1040.0 82.0 0.1 gun-gray)]
         base   (reduce into [] quads)
         muzzle (quad 1175.0 78.0 1215.0 100.0 0.2 [1.0 0.85 0.30])]
    (if flash? (into base muzzle) base)))

;; --- ammo readout (#65): procedural infinity glyph --------------------------
;; q1k3 shows the active weapon's ammo as DOM text — `∞` for the shotgun
;; (needs-ammo=0). The overlay has no text renderer, so the GL analog draws ∞ as
;; two touching ring outlines, each a strip of rotated quads. Only the shotgun
;; exists in the port, so ammo is always ∞. Drawn in screen px under the ortho
;; flat shader alongside the health bar + viewmodel.

(def ^:const ammo-radius    14.0)   ; px radius of each ring
(def ^:const ammo-thickness  4.0)   ; px stroke thickness
(def ^:const ammo-segs      12)     ; quad segments per ring

(defn- quad4
  "Two-triangle quad from four arbitrary [x y] corners (order: 0,1,2 + 0,2,3) at
  depth z, colored [r g b]. Returns 6 verts × 6 floats (36 floats)."
  [p0 p1 p2 p3 z [r g b]]
  (let [[x0 y0] p0 [x1 y1] p1 [x2 y2] p2 [x3 y3] p3]
    [x0 y0 z r g b
     x1 y1 z r g b
     x2 y2 z r g b
     x0 y0 z r g b
     x2 y2 z r g b
     x3 y3 z r g b]))

(defn- ring-verts
  "A ring outline of radius `r`, stroke `t`, centered at (ox, oy), as `segs`
  rotated quad segments. Returns a flat [x y z r g b ...] float vector."
  [ox oy r t segs z col]
  (let [ir (- r (/ t 2.0)) or (+ r (/ t 2.0))
        two-pi (* 2.0 Math/PI)]
    (loop [i 0 acc []]
      (if (>= i segs) acc
          (let [a0 (* two-pi (/ (double i) (double segs)))
                a1 (* two-pi (/ (double (inc i)) (double segs)))
                c0 (Math/cos a0) s0 (Math/sin a0)
                c1 (Math/cos a1) s1 (Math/sin a1)
                p0 [(+ ox (* ir c0)) (+ oy (* ir s0))]
                p1 [(+ ox (* or c0)) (+ oy (* or s0))]
                p2 [(+ ox (* or c1)) (+ oy (* or s1))]
                p3 [(+ ox (* ir c1)) (+ oy (* ir s1))]]
            (recur (inc i) (into acc (quad4 p0 p1 p2 p3 z col))))))))

(defn ammo-verts
  "Procedural infinity (∞) glyph centered at [cx cy] (screen px), the GL analog
  of q1k3's `∞` ammo text for the infinite-ammo shotgun. Two touching rings. Flat
  [x y z r g b ...] float vector."
  [[cx cy]]
  (let [col [0.85 0.85 0.85] z 0.1]
    (-> (ring-verts (- cx ammo-radius) cy ammo-radius ammo-thickness ammo-segs z col)
        (into (ring-verts (+ cx ammo-radius) cy ammo-radius ammo-thickness ammo-segs z col)))))
