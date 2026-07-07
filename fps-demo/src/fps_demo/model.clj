(ns fps-demo.model
  "Pure Clojure port of q1k3/source/model.js — the Retarded Model Format (.rmf)
  parser. No GL here so it can be unit-tested headlessly.

  Container = concatenation of model blobs. Each blob:
    [u8 frames][u8 verts/frame][u8 faces]
    verts:   frames*verts*3 bytes  -> (b-15)*scale per axis
    indices: faces*3 bytes         -> [a_delta, b_abs, c_abs]; a accumulates

  init-model returns:
    :frames   [vertex-offset per frame ...] ; vertex units (== JS r_num_verts)
    :verts    [float ...]                   ; flat: pos3 normal3 uv2 per vertex
    :stride   8                             ; floats per vertex
    :num-verts faces*3                      ; vertices per frame"
  (:require [jolt.ffi :as ffi]))

;; --- vec3 helpers (ports of the subset of q1k3 math_utils.js we need) --------

(defn- vsub [a b] [(- (a 0) (b 0)) (- (a 1) (b 1)) (- (a 2) (b 2))])

(defn- vcross [a b]
  [(- (* (a 1) (b 2)) (* (a 2) (b 1)))
   (- (* (a 2) (b 0)) (* (a 0) (b 2)))
   (- (* (a 0) (b 1)) (* (a 1) (b 0)))])

(defn- vlen [a]
  (Math/sqrt (+ (* (a 0) (a 0)) (* (a 1) (a 1)) (* (a 2) (a 2)))))

(defn- vnorm [a]
  (let [l (vlen a)]
    (if (zero? l) a [(/ (a 0) l) (/ (a 1) l) (/ (a 2) l)])))

(defn- face-normal
  "Port of vec3_face_normal(v0,v1,v2) = normalize(cross(v0-v1, v2-v1))."
  [v0 v1 v2]
  (vnorm (vcross (vsub v0 v1) (vsub v2 v1))))

;; --- container splitting -----------------------------------------------------

(def ^:private header-size 3)

(defn load-container
  "Split a flat byte seq into model blobs (header + body each). Returns a vector
  of subvecs, one per blob — matching q1k3 model_load_container's subarrays."
  [data]
  (let [v (vec data)]
    (loop [i 0 out []]
      (if (>= i (count v))
        out
        (let [nf   (v i)
              nv   (v (inc i))
              ni   (v (+ i 2))
              size (* (+ (* nf nv) ni) 3)        ; body byte count
              end  (+ i header-size size)]
          (recur end (conj out (subvec v i end))))))))

;; --- single-model init ------------------------------------------------------

(defn init-model
  "Port of q1k3 model_init(data, sx, sy, sz). `blob` is one model's bytes
  (header + body). Returns the map described in the ns doc. sx/sy/sz are
  per-axis vertex scales."
  ([blob] (init-model blob 1 1 1))
  ([blob sx sy sz]
   (let [d           (vec blob)
         num-frames  (d 0)
         num-verts   (d 1)
         num-indices (d 2)
         vert-bytes  (* num-verts num-frames 3)
         frame0-end  (* num-verts 3)            ; frame-0 bounds use first frame only

         decoded     (loop [p header-size i 0 acc []
                            mn-x 16 mx-x -16 mn-y 16 mx-y -16]
                       (if (>= i vert-bytes)
                         {:verts acc :min-x mn-x :max-x mx-x :min-y mn-y :max-y mx-y}
                         (let [x   (* (- (d p) 15) sx)
                               y   (* (- (d (inc p)) 15) sy)
                               z   (* (- (d (+ p 2)) 15) sz)
                               f0? (< i frame0-end)]
                           (recur (+ p 3) (+ i 3) (conj acc x y z)
                                  (if f0? (min mn-x x) mn-x)
                                  (if f0? (max mx-x x) mx-x)
                                  (if f0? (min mn-y y) mn-y)
                                  (if f0? (max mx-y y) mx-y)))))
         verts       (:verts decoded)
         min-x       (:min-x decoded)
         max-x       (:max-x decoded)
         min-y       (:min-y decoded)
         max-y       (:max-y decoded)

         idx-start   (+ header-size vert-bytes)
         indices     (loop [p idx-start i 0 incr 0 acc []]
                       (if (>= i (* num-indices 3))
                         acc
                         (let [a (+ incr (d p))
                               b (d (inc p))
                               c (d (+ p 2))]
                           (recur (+ p 3) (+ i 3) a (conj acc a b c)))))

         span-x      (- max-x min-x)
         span-y      (- max-y min-y)
         uf          (if (zero? span-x) 0 (/ 1.0 span-x))
         vf          (if (zero? span-y) 0 (/ -1.0 span-y))
         u-offset    (* (- min-x) uf)
         v-offset    (* max-y vf)

         stride      8
         get-vert    (fn [voff idx]
                       [(verts (+ voff (* idx 3)))
                        (verts (+ voff (* idx 3) 1))
                        (verts (+ voff (* idx 3) 2))])
         get-uv      (fn [idx]
                       [(+ (* (verts (* idx 3)) uf) u-offset)
                        (+ (* (verts (+ (* idx 3) 1)) vf) v-offset)])]

     ;; emit, per frame: for each face push mv2,mv1,mv0 (JS order) with the
     ;; shared face normal and each vertex's frame-0 uv.
     (loop [fi 0 out-verts [] frame-offs []]
        (if (>= fi num-frames)
          ;; jolt persistent-vector nth is O(n), so the per-frame blend can't
          ;; index :verts. Copy the emitted verts into a native float buffer once
          ;; (seq walk is O(1)/step); mix-frame-buffer reads it via ffi/read.
          (let [nfloats (count out-verts)
                vert-buf (ffi/alloc (* nfloats 4))]
            (loop [s (seq out-verts) i 0]
              (when s
                (ffi/write vert-buf :float (* i 4) (double (first s)))
                (recur (next s) (inc i))))
            {:verts out-verts :vert-buf vert-buf :frames frame-offs
             :stride stride :num-verts (* num-indices 3)
             :y-min min-y :y-max max-y})
         (let [voff     (* fi num-verts 3)
               new-verts (loop [face 0 acc out-verts]
                            (if (>= face num-indices)
                              acc
                              (let [i   (* face 3)
                                    ia  (indices i)
                                    ib  (indices (inc i))
                                    ic  (indices (+ i 2))
                                    mv0 (get-vert voff ia)
                                    mv1 (get-vert voff ib)
                                    mv2 (get-vert voff ic)
                                    uv0 (get-uv ia)
                                    uv1 (get-uv ib)
                                    uv2 (get-uv ic)
                                    n   (face-normal mv2 mv1 mv0)]
                                (recur (inc face)
                                       (conj acc
                                             (mv2 0) (mv2 1) (mv2 2)
                                             (n 0) (n 1) (n 2)
                                             (uv2 0) (uv2 1)
                                             (mv1 0) (mv1 1) (mv1 2)
                                             (n 0) (n 1) (n 2)
                                             (uv1 0) (uv1 1)
                                             (mv0 0) (mv0 1) (mv0 2)
                                             (n 0) (n 1) (n 2)
                                             (uv0 0) (uv0 1))))))]
            (recur (inc fi) new-verts
                   (conj frame-offs (quot (count out-verts) stride)))))))))

;; --- render-interleaved buffer transforms -----------------------------------
;; model.clj stores each vertex as [pos3 normal3 uv2] (stride 8); render.clj binds
;; attributes as [a_pos3 a_uv2 a_normal3]. These reorder into render order and,
;; for mix-frame-buffer, lerp two frames' POSITIONS by t (uv + normal from frame
;; A) — the vertex-anim blend q1k3 uses for its .rmf models.

(defn frame-buffer
  "Return one frame as a render-interleaved buffer {:data [float] :count N} in
  [pos3 uv2 normal3] order. `frame-idx` indexes into :frames."
  [m frame-idx]
  (let [vert-buf (:vert-buf m)
        stride   (:stride m)
        n        (:num-verts m)
        base     (* (get (:frames m) frame-idx) stride)
        rd       (fn [idx] (ffi/read vert-buf :float (* idx 4)))]
    (loop [i 0 out []]
      (if (>= i n)
        {:data out :count n}
        (let [o (+ base (* i stride))]
          (recur (inc i)
                 (conj out
                       (rd o) (rd (+ o 1)) (rd (+ o 2))
                       (rd (+ o 6)) (rd (+ o 7))
                       (rd (+ o 3)) (rd (+ o 4)) (rd (+ o 5)))))))))

(defn mix-frame-buffer
  "Blend frame `a` toward frame `b` by t∈[0,1] into a render-interleaved buffer.
  Positions are lerped; uv and normal are taken from frame `a` (matching q1k3's
  position-only vertex blend). Returns {:data [float] :count N}."
  [m a b t]
  (let [vert-buf (:vert-buf m)
        stride   (:stride m)
        n        (:num-verts m)
        base-a   (* (get (:frames m) a) stride)
        base-b   (* (get (:frames m) b) stride)
        rd       (fn [idx] (ffi/read vert-buf :float (* idx 4)))
        lp       (fn [k oa ob]
                   (let [va (rd (+ oa k)) vb (rd (+ ob k))]
                     (+ va (* t (- vb va)))))]
    (loop [i 0 out []]
      (if (>= i n)
        {:data out :count n}
        (let [oa (+ base-a (* i stride))
              ob (+ base-b (* i stride))]
          (recur (inc i)
                 (conj out
                       (lp 0 oa ob) (lp 1 oa ob) (lp 2 oa ob)
                       (rd (+ oa 6)) (rd (+ oa 7))
                       (rd (+ oa 3)) (rd (+ oa 4)) (rd (+ oa 5)))))))))

;; --- anim-state -> frame pair + mix (q1k3 r_u_frame_mix) --------------------
;; Maps an enemy's animation state to a model frame pair and blend factor for
;; mix-frame-buffer. unit.rmf: frame 0 idle, 1..4 run cycle, 5 fire. anim-frames
;; is the list of frame indices used by the current anim (e.g. [0] or [1 2 3 4]).

(defn enemy-frame-anim
  "Return [frame-a frame-b mix] for an enemy's anim state. frame-a/frame-b are
  model frame indices; mix ∈ [0,1] blends them. Single-frame anims return
  [f f 0.0]. Mirrors q1k3's r_u_frame_mix time->frame mapping."
  [enemy]
  (let [frames (:anim-frames enemy)
        period (:anim-period enemy)
        ttime  (:anim-time enemy)]
    (if (== (count frames) 1)
      [(frames 0) (frames 0) 0.0]
      (let [nf  (count frames)
            f   (* (/ ttime period) nf)
            i0  (mod (int f) nf)
            i1  (mod (+ i0 1) nf)
            mix (- f (int f))]
        [(frames i0) (frames i1) (double mix)]))))
