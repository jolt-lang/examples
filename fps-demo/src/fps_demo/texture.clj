(ns fps-demo.texture
  "q1k3 TTT (Tiny Texture Tumbler) DSL interpreter.

  Decodes q1k3's packed texture definitions (q1k3/source/ttt.js + textures.js)
  into flat RGBA8 byte buffers — pure and GL-free, so the check suite verifies the
  whole pipeline without a context. A definition is a flat vector [w h bg op...
  op...]; an op is an opcode followed by inline args.

  Colours pack 16-bit RGBA4: r,g,b,a = nibble*17 (0-255). Compositing is
  straight-alpha source-over (the canvas 2D default). Coordinates are floored to
  whole pixels — no canvas subpixel coverage, which suits the pixel aesthetic.
  Op 3 (text) needs a font rasterizer jolt lacks, so it is a no-op that still
  consumes its 6 args; the few decorative text glyphs come out blank.")

(defn- rgba4
  "Unpack a 16-bit RGBA4 colour to [r g b a] bytes (nibble*17)."
  [c]
  [(* (bit-and (bit-shift-right c 12) 15) 17)
   (* (bit-and (bit-shift-right c 8)  15) 17)
   (* (bit-and (bit-shift-right c 4)  15) 17)
   (* (bit-and c 15) 17)])

(defn- blend-chan
  "src-over blend of one channel: round((sc*sw + dc*dw)/oa), where sw/dw are the
  premultiplied src/dst weights and oa the out alpha (always >0 on the partial
  path)."
  [sc dc sw dw oa]
  (int (+ 0.5 (/ (+ (* sc sw) (* dc dw)) oa))))

(defn- fi
  "Floor to an int (pixel coordinate)."
  [x]
  (int (Math/floor (double x))))

(defn- put-bytes!
  "Composite source-over of unpacked RGBA bytes (sr sg sb sa) into pixel (px,py)
  of `buf` (int-array w*h*4), scaling src alpha by `amul` (draw-prev
  globalAlpha). Opaque source (sa==255, amul==1.0) writes directly; the partial
  case blends in place via blend-chan. No per-pixel allocation. No-op off-canvas."
  [buf w h px py sr sg sb sa amul]
  (when (and (<= 0 px) (< px w) (<= 0 py) (< py h))
    (let [o   (* (+ (* py w) px) 4)
          sa1 (* (/ sa 255.0) amul)]
      (cond
        (zero? sa1)  nil
        (== sa1 1.0) (do (aset buf o sr) (aset buf (inc o) sg)
                         (aset buf (+ o 2) sb) (aset buf (+ o 3) 255))
        :else (let [da  (aget buf (+ o 3))
                    da1 (/ da 255.0)
                    oa  (+ sa1 (* da1 (- 1.0 sa1)))
                    sw  sa1
                    dw  (* da1 (- 1.0 sa1))]
                (aset buf o         (blend-chan sr (aget buf o) sw dw oa))
                (aset buf (inc o)   (blend-chan sg (aget buf (inc o)) sw dw oa))
                (aset buf (+ o 2)   (blend-chan sb (aget buf (+ o 2)) sw dw oa))
                (aset buf (+ o 3)   (int (+ 0.5 (* oa 255.0)))))))))

(defn- region!
  "Fill rect [x,x+rw) x [y,y+rh) (floored) with packed colour `c`, alpha-scaled by
  `amul`, clipped to the canvas. Unpacks `c` once and composites per pixel."
  [buf w h x y rw rh c amul]
  (let [xi (fi x)  yi (fi y)
        x0 (max 0 xi)            y0 (max 0 yi)
        x1 (min w (+ xi (fi rw))) y1 (min h (+ yi (fi rh)))
        [r g b a] (rgba4 c)]
    (loop [py y0]
      (when (< py y1)
        (loop [px x0]
          (when (< px x1)
            (put-bytes! buf w h px py r g b a amul)
            (recur (inc px))))
        (recur (inc py))))))

(defn- fill-rect!
  "ttt fill_rect emboss: top colour at (-1,-1), bottom at (+1,+1), fill centred.
  The offset colours paint a 1px bevel; an alpha-0 colour paints nothing."
  [buf w h x y rw rh top bottom fill]
  (region! buf w h (- x 1) (- y 1) rw rh top    1.0)
  (region! buf w h (+ x 1) (+ y 1) rw rh bottom 1.0)
  (region! buf w h x        y        rw rh fill  1.0))

(declare decode-one)

(defn- blit!
  "Draw decoded texture `src` into `buf` at dest rect [dx,dx+dw) x [dy,dy+dh]
  (floored), nearest-neighbour sampled, alpha-scaled by globalAlpha = alpha/15."
  [buf w h dx dy dw dh src alpha]
  (let [{sw :w sh :h sp :pixels} src
        amul (/ alpha 15.0)
        dwi (fi dw) dhi (fi dh)
        x0 (fi dx) y0 (fi dy)
        x1 (+ x0 dwi) y1 (+ y0 dhi)]
    (when (and (pos? dwi) (pos? dhi) (pos? sw) (pos? sh))
      (loop [py y0]
        (when (< py y1)
          (let [sy (min (dec sh) (fi (* (/ (- py y0) dhi) sh)))]
            (loop [px x0]
              (when (< px x1)
                (let [sx (min (dec sw) (fi (* (/ (- px x0) dwi) sw)))
                      so (* (+ (* sy sw) sx) 4)]
                  (put-bytes! buf w h px py (aget sp so) (aget sp (inc so))
                              (aget sp (+ so 2)) (aget sp (+ so 3)) amul))
                (recur (inc px))))
            (recur (inc py))))))))

(defn- decode-one
  "Decode a single texture `def` into {:w :h :pixels}. `lookup` maps a draw-prev
  index to an already-decoded {:w :h :pixels} (or nil); `rng` a 0-arg float
  [0,1). decode-all passes a cache-backed lookup so op 4 blits the baked earlier
  texture instead of re-decoding it — matching q1k3, which bakes each texture
  once. (The old recursive decode re-rolled procedural noise on every reference
  and made decode-all O(n^2).)"
  [def lookup rng]
  (let [w   (int (nth def 0))
        h   (int (nth def 1))
        buf (int-array (* w h 4))
        n   (count def)]
    (fill-rect! buf w h 0 0 w h 0 0 (nth def 2))   ; bg
    (loop [i 3]
      (if (>= i n)
        {:w w :h h :pixels buf}
        (let [op (nth def i)]
          (cond
            (= op 0)
            (let [x (nth def (+ i 1)) y (nth def (+ i 2))
                  rw (nth def (+ i 3)) rh (nth def (+ i 4))
                  top (nth def (+ i 5)) bot (nth def (+ i 6)) fill (nth def (+ i 7))]
              (fill-rect! buf w h x y rw rh top bot fill)
              (recur (+ i 8)))
            (= op 1)
            (let [sx (nth def (+ i 1)) sy (nth def (+ i 2))
                  rw (nth def (+ i 3)) rh (nth def (+ i 4))
                  ix (nth def (+ i 5)) iy (nth def (+ i 6))
                  top (nth def (+ i 7)) bot (nth def (+ i 8)) fill (nth def (+ i 9))]
              (when (and (pos? (fi ix)) (pos? (fi iy)))
                (loop [x sx]
                  (when (< x w)
                    (loop [y sy]
                      (when (< y h)
                        (fill-rect! buf w h x y rw rh top bot fill)
                        (recur (+ y iy))))
                    (recur (+ x ix)))))
              (recur (+ i 10)))
            (= op 2)
            (let [color (nth def (+ i 1)) size (nth def (+ i 2))
                  is (fi size)]
              (when (pos? is)
                (loop [x 0]
                  (when (< x w)
                    (loop [y 0]
                      (when (< y h)
                        (let [nc (bit-or (bit-and color 0xFFF0)
                                         (int (* (rng) (bit-and color 15))))]
                          (fill-rect! buf w h x y is is 0 0 nc))
                        (recur (+ y size))))
                    (recur (+ x size)))))
              (recur (+ i 3)))
            (= op 3)
            (recur (+ i 7))
            (= op 4)
            (let [idx (int (nth def (+ i 1)))
                  dx (nth def (+ i 2)) dy (nth def (+ i 3))
                  dw (nth def (+ i 4)) dh (nth def (+ i 5))
                  alpha (nth def (+ i 6))]
              (when-let [src (lookup idx)]
                (blit! buf w h dx dy dw dh src alpha))
              (recur (+ i 7)))
            :else (recur (inc i))))))))

(defn decode-texture
  "Decode one texture `def` (a flat [w h bg op...] vector) into {:w :h :pixels},
  where :pixels is a flat RGBA8 byte vector (w*h*4). `all-defs` is the full
  definition list — needed for op 4 draw-prev; pass [] when the def uses none.
  `rng` is a 0-arg function returning a float in [0,1). draw-prev resolves by
  decoding the referenced def on demand (standalone use; decode-all uses a
  cache instead)."
  [def all-defs rng]
  (let [lookup (fn lookup [idx]
                 (when (< idx (count all-defs))
                   (decode-one (nth all-defs idx) lookup rng)))]
    (decode-one def lookup rng)))

(defn decode-all
  "Decode every texture def in `defs` (in order) into a vector of {:w :h :pixels}.
  Decoded textures accumulate in a cache passed as each def's draw-prev `lookup`,
  so op 4 blits the already-baked earlier texture instead of re-decoding it
  (q1k3 bakes each texture exactly once). `rng` as above."
  [defs rng]
  (loop [remaining defs cache [] out []]
    (if (empty? remaining)
      out
      (let [lookup (fn [idx] (get cache idx))
            tex    (decode-one (first remaining) lookup rng)]
        (recur (next remaining) (conj cache tex) (conj out tex))))))
