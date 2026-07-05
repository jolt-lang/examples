(ns fps-demo.map
  "Decoder for q1k3's packed map container format (see q1k3/source/map.js).

  Container = one or more concatenated maps. Each map:
    u16 blocks_size       (little-endian)
    blocks[blocks_size]   block records interleaved with texture sentinels;
                          a 255 byte is followed by a texture index that applies
                          to every subsequent block until the next sentinel;
                          a block is 6 bytes: x y z sx sy sz (grid coords)
    u16 num_entities
    entities[]            each 6 bytes: type x y z data1 data2

  World scaling: X and Z run at 32 units/cell (<<5), Y at 16 units/cell (<<4),
  so a block's world min is (x*32, y*16, z*32) and world size (sx*32, sy*16, sz*32).
  The collision grid is map-size^3 = 128^3 cells.")

(def ^:const map-size 128)

(defn- u16le
  "Little-endian u16 at index i of a byte seq."
  [data i]
  (bit-or (nth data i) (bit-shift-left (nth data (inc i)) 8)))

(defn world-box
  "World-space AABB of a block: {:min [x y z] :size [sx sy sz]} with q1k3 scaling
  (X/Z *32, Y *16). Returned as doubles so the result feeds straight into geometry."
  [b]
  (let [{:keys [x y z sx sy sz]} b]
    {:min  [(double (* x 32)) (double (* y 16)) (double (* z 32))]
     :size [(double (* sx 32)) (double (* sy 16)) (double (* sz 32))]}))

(defn- parse-blocks
  "Parse `size` bytes of block data starting at `start`. Returns [blocks tex]
  where blocks is a vector of {:tex :x :y :z :sx :sy :sz}; a 255 sentinel sets
  the running texture for all following blocks."
  [data start size]
  (loop [j start end (+ start size) tex 0 acc []]
    (if (>= j end)
      [acc tex]
      (if (= 255 (nth data j))
        (recur (+ j 2) end (nth data (inc j)) acc)
        (let [b {:tex tex
                 :x  (nth data j)
                 :y  (nth data (inc j))
                 :z  (nth data (+ j 2))
                 :sx (nth data (+ j 3))
                 :sy (nth data (+ j 4))
                 :sz (nth data (+ j 5))}]
          (recur (+ j 6) end tex (conj acc b)))))))

(defn- parse-entities
  "Parse `n` entities (6 bytes each) starting at `start`. Returns [entities i]
  where i is the index just past the last entity byte."
  [data start n]
  (loop [i start k 0 acc []]
    (if (>= k n)
      [acc i]
      (let [e {:type  (nth data i)
               :x     (nth data (inc i))
               :y     (nth data (+ i 2))
               :z     (nth data (+ i 3))
               :data1 (nth data (+ i 4))
               :data2 (nth data (+ i 5))}]
        (recur (+ i 6) (inc k) (conj acc e))))))

(defn decode-container
  "Decode a q1k3 map container (a seq of byte values 0-255) into a vector of
  maps, each {:blocks [...] :entities [...]}. Mirrors map_load_container()."
  [data]
  (loop [i 0 acc ()]
    (if (>= i (count data))
      (reverse acc)
      (let [bsize          (u16le data i)
            i              (+ i 2)
            [blocks _]     (parse-blocks data i bsize)
            i              (+ i bsize)
            nent           (u16le data i)
            i              (+ i 2)
            [entities i]   (parse-entities data i nent)]
        (recur i (conj acc {:blocks blocks :entities entities}))))))

(defn solid-cells
  "Set of [x y z] grid cells occupied by `blocks` — the collision map. Each
  block fills the full [x,x+sx) x [y,y+sy) x [z,z+sz) grid box. Equivalent to
  q1k3's collision bitmap, expressed as a pure set for clarity."
  [blocks]
  (into #{}
        (mapcat (fn [b]
                  (let [{:keys [x y z sx sy sz]} b]
                    (for [cz (range z (+ z sz))
                          cy (range y (+ y sy))
                          cx (range x (+ x sx))]
                      [cx cy cz])))
                blocks)))

;; --- collision / line-of-sight queries (q1k3 map.js) ------------------------
;; Cells are 32x16x32 (x>>5, y>>4, z>>5). block-at? maps a world point to its
;; cell and tests membership in the solid-cells set.
(defn block-at?
  "True if the grid cell containing world point (wx,wy,wz) is in `cells`."
  [cells wx wy wz]
  (let [cx (int (Math/floor (/ wx 32.0)))
        cy (int (Math/floor (/ wy 16.0)))
        cz (int (Math/floor (/ wz 32.0)))]
    (contains? cells [cx cy cz])))

(defn map-trace
  "Ray-march from `a` to `b` in 16-unit steps; returns the world-space hit point
  [x y z] where the ray first enters a solid cell, or nil if the path is clear.
  Mirrors q1k3 map_trace: the first sample is one step AFTER the start, and the
  number of steps is floor(length/16)."
  [cells a b]
  (let [ax (nth a 0) ay (nth a 1) az (nth a 2)
        bx (nth b 0) by (nth b 1) bz (nth b 2)
        dx (- bx ax) dy (- by ay) dz (- bz az)
        len (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))]
    (if (zero? len)
      nil
      (let [sx (* (/ dx len) 16.0)
            sy (* (/ dy len) 16.0)
            sz (* (/ dz len) 16.0)
            n  (int (Math/floor (/ len 16.0)))]
        (loop [i 0 x ax y ay z az]
          (if (>= i n)
            nil
            (let [x' (+ x sx) y' (+ y sy) z' (+ z sz)]
              (if (block-at? cells x' y' z')
                [x' y' z']
                (recur (inc i) x' y' z')))))))))
