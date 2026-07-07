(ns fps-demo.overlay
  "Pure overlay geometry builders for the q1k3 port. Produces flat interleaved
  [x y z r g b ...] float lists for the flat shader (pos3 + color3, stride 6).
  GL-free and unit-tested; render.clj uploads and draws these.

  All builders return a Clojure vector of Float, 6 per vertex (2 triangles = 6
  verts = 36 floats per quad). Coordinate spaces:
    - status-bar / number / crosshair / viewmodel: screen pixels (ortho)
    - particle-point-verts:                        world units (proj*view)")

(def ^:const statusbar-h    64.0)   ; px height of the bottom status bar
(def ^:const crosshair-arm  10.0)   ; px half-length of each crosshair arm
(def ^:const crosshair-thick 2.0)   ; px crosshair stroke

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

(defn status-bar-verts
  "Quake-style bottom status bar: one dark strip across the screen width."
  [w _h]
  (quad 0.0 0.0 (double w) statusbar-h 0.05 [0.07 0.06 0.06]))

;; --- seven-segment numbers (no font atlas) ----------------------------------
;; q1k3/Quake show health & ammo as big digits. With only the flat pos+color
;; shader, each digit is drawn as up to seven rectangle segments (a..g).
(def ^:private seg-of
  {0 #{:a :b :c :d :e :f}    1 #{:b :c}
   2 #{:a :b :g :e :d}       3 #{:a :b :g :c :d}
   4 #{:f :g :b :c}          5 #{:a :f :g :c :d}
   6 #{:a :f :g :e :c :d}    7 #{:a :b :c}
   8 #{:a :b :c :d :e :f :g} 9 #{:a :b :c :d :f :g}})

(defn- digit-verts
  "One seven-segment digit `d` (0-9) with bottom-left at (x,y), cell height `s`,
  colored `col`. Cell width is 0.62*s, stroke 0.16*s."
  [d x y s col]
  (let [w   (* s 0.62) th (* s 0.16) z 0.3
        segs (seg-of d #{})
        mid (+ y (* 0.5 (- s th)))
        add (fn [acc on q] (if on (into acc q) acc))]
    (-> []
        (add (segs :a) (quad x            (- (+ y s) th) (+ x w)      (+ y s)        z col))
        (add (segs :g) (quad x            mid            (+ x w)      (+ mid th)     z col))
        (add (segs :d) (quad x            y              (+ x w)      (+ y th)       z col))
        (add (segs :f) (quad x            (+ y (* 0.5 s))(+ x th)     (+ y s)        z col))
        (add (segs :b) (quad (- (+ x w) th)(+ y (* 0.5 s))(+ x w)     (+ y s)        z col))
        (add (segs :e) (quad x            y              (+ x th)     (+ y (* 0.5 s))z col))
        (add (segs :c) (quad (- (+ x w) th) y            (+ x w)      (+ y (* 0.5 s))z col)))))

(defn- int->digits [n]
  (if (zero? n) [0]
      (loop [n n acc ()] (if (zero? n) (vec acc) (recur (quot n 10) (cons (mod n 10) acc))))))

(defn number-verts
  "Non-negative integer `n` as seven-segment digits, left to right from (x,y),
  cell height `s`, colored `col`."
  [n x y s col]
  (let [adv (* s 0.82)]
    (loop [ds (int->digits (max 0 (int n))) i 0 acc []]
      (if (empty? ds) acc
          (recur (rest ds) (inc i)
                 (into acc (digit-verts (first ds) (+ x (* i adv)) y s col)))))))

(defn crosshair-verts
  "A small centered `+` crosshair in screen pixels (ortho)."
  [w h]
  (let [cx (* 0.5 (double w)) cy (* 0.5 (double h))
        a crosshair-arm t (* 0.5 crosshair-thick) z 0.2 col [0.85 0.9 0.85]]
    (into (quad (- cx a) (- cy t) (+ cx a) (+ cy t) z col)
          (quad (- cx t) (- cy a) (+ cx t) (+ cy a) z col))))

;; 36 corner refs (3 per tri × 12 tris) into cube-corners, in emit order, so the
;; per-particle loop is a flat aset stream with no intermediate allocation.
(def ^:private part-corner-idx
  (into [] (for [[a b c] cube-tris t [a b c]] t)))

;; Flat unit-cube template: the 36 emit-order corner offsets as one primitive
;; double-array [x0 y0 z0  x1 y1 z1 …] (108 doubles). The hot box builders read
;; it with aget instead of chaining persistent-vector nth per vertex — jolt's
;; vector nth is O(n), so the old ~5 nth/vertex dominated the per-frame cost.
(def ^:private cube-template
  (double-array
    (for [i (range 36)
          :let [c (nth cube-corners (nth part-corner-idx i))]
          k (range 3)]
      (double (nth c k)))))

(defn particle-point-verts
  "One GL_POINTS vertex per particle: [x y z r g b], dark red. 6 floats each
  (vs 216 for a cube) — jolt's per-element aset/upload is the per-frame cost, so
  points keep blood/debris cheap even at high counts. gl_PointSize (flat shader)
  gives each a perspective size."
  [particles]
  (let [n   (count particles)
        r   0.60 g 0.05 b 0.05
        out (float-array (* n 6))]
    (loop [ps particles k 0]
      (if (empty? ps) out
          (let [pos (:pos (first ps))]
            (aset out k       (double (nth pos 0)))
            (aset out (+ k 1) (double (nth pos 1)))
            (aset out (+ k 2) (double (nth pos 2)))
            (aset out (+ k 3) r)
            (aset out (+ k 4) g)
            (aset out (+ k 5) b)
            (recur (rest ps) (+ k 6)))))))

;; --- in-flight projectiles (world space) ------------------------------------
;; q1k3 draws each projectile with its own model + texture; the flat pipeline
;; has no textures, so nail/plasma/grenade/gib render as small colored cubes and
;; shells (invisible & near-instant in the original) are skipped. Same aset-into-
;; a-float-array approach as particle-box-verts since the count changes per frame.

(def ^:const projectile-half 3.0)   ; default cube half-size in world units

(def ^:private projectile-look
  ;; per-kind [half-size [r g b]]. Shells are omitted (rendered nothing).
  {:nail    [2.0 [0.80 0.85 1.00]]
   :plasma  [3.0 [1.00 0.55 0.10]]
   :grenade [4.0 [0.20 0.50 0.15]]
   :gib     [3.5 [0.55 0.12 0.10]]})

(defn projectile-box-verts
  "One colored cube per visible projectile at its :pos. Skips :shell (invisible).
  36 verts × 6 floats per drawn projectile; returns a flat float-array."
  [projectiles]
  (let [drawn (filterv #(projectile-look (:kind %)) projectiles)
        tmpl  ^doubles cube-template
        out   (float-array (* (count drawn) 216))]
    (loop [ps drawn base 0]
      (if (empty? ps) out
          (let [pr  (first ps)
                pos (:pos pr)
                [s [r g b]] (projectile-look (:kind pr))
                s   (double s) r (double r) g (double g) b (double b)
                px (double (nth pos 0)) py (double (nth pos 1)) pz (double (nth pos 2))
                nb (loop [i 0 k base ti 0]
                     (if (>= i 36) k
                         (do
                           (aset out k       (+ px (* (aget tmpl ti)       s)))
                           (aset out (+ k 1) (+ py (* (aget tmpl (+ ti 1)) s)))
                           (aset out (+ k 2) (+ pz (* (aget tmpl (+ ti 2)) s)))
                           (aset out (+ k 3) r)
                           (aset out (+ k 4) g)
                           (aset out (+ k 5) b)
                           (recur (inc i) (+ k 6) (+ ti 3)))))]
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
