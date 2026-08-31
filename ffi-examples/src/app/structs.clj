(ns app.structs
  "libc `div_t div(int, int)` — a function that returns a STRUCT BY VALUE.

      jolt structs

  Two pieces of jolt.ffi do the work.

  `ffi/layout` compiles a literal struct descriptor into ABI layout data: Chez
  supplies the size, the alignment and each field's offset, so fields are
  addressed by name instead of by hand-counted arithmetic.

  `[:by-value descriptor]` in a signature is the aggregate marker. As a RETURN
  type it makes the binding take a caller-owned destination pointer as its first
  argument, write C's return value there, and hand that pointer back — so the
  caller owns the memory the struct lands in, and decides whether to allocate
  one per call or reuse a single buffer across a loop.

  The benchmark at the bottom splits that decision three ways, because only one
  of the three is the FFI. See the comment above it.

  libc needs no `:jolt/native` entry: `div` and `abs` are already in the
  process, and a defcfn falls back to global symbol resolution."
  (:require
   [jolt.ffi :as ffi]))

;; The compiled layout — used for reads and for sizeof, and never in a
;; signature: the FFI macros need LITERAL type data at compile time, because
;; jolt lowers a signature to a Chez foreign-procedure while compiling. So the
;; descriptor is spelled out again inside defcfn rather than referred to by name.
(def div-t (ffi/layout [:struct [[:quot :int] [:rem :int]]]))

;; div_t div(int, int). Called as (c-div-into out numer denom): `out` is the
;; 8-byte destination, and the return value is that same pointer.
(ffi/defcfn ^:private c-div-into "div" [:int :int]
  [:by-value [:struct [[:quot :int] [:rem :int]]]])

(ffi/defcfn c-abs "abs" [:int] :int)

(defn div-fields
  "The {:quot :rem} map for a div_t already written at `out`."
  [out]
  {:quot (ffi/read-field out div-t :quot)
   :rem  (ffi/read-field out div-t :rem)})

(defn c-div
  "div(numer, denom) as a map, allocating the destination for this one call.
  `ffi/with-layout` allocates one instance of a layout and frees it on the way
  out of the body, including when the body throws."
  [numer denom]
  (ffi/with-layout [out div-t]
    (c-div-into out numer denom)
    (div-fields out)))

;; --- benchmark ---------------------------------------------------------------
;; "How fast is a struct return?" turns out to be three questions:
;;
;;   1. the foreign call itself, writing into a destination that already exists.
;;      This is the aggregate ABI path, and the only line that measures the FFI.
;;   2. the call plus reading the fields back BY NAME. read-field resolves the
;;      field path against the layout at RUNTIME — walk the path, look up the
;;      offset, look up the type — so it dominates everything else here.
;;      Convenient at the edges, wrong in a hot loop.
;;   3. the call plus reads at offsets hoisted OUT of the loop with
;;      ffi/field-offset. Same field names in the source, none of the lookup.
;;
;; `abs` is the floor: a scalar call, no aggregate and no memory anywhere.

(def N 200000)

(defn bench [label f]
  (dotimes [_ 20000] (f))
  (let [t0 (System/nanoTime)]
    (dotimes [_ N] (f))
    (println (format "  %-34s %5d ns/call" label (quot (- (System/nanoTime) t0) N)))))

(defn -main
  [& _]
  (println "div(7, 2) =" (c-div 7 2))
  (println "sizeof div_t =" (ffi/layout-size div-t)
           "bytes, aligned to" (ffi/layout-alignment div-t))
  (println "offsets: quot @" (ffi/field-offset div-t :quot)
           ", rem @" (ffi/field-offset div-t :rem))
  (println)

  (let [quot-off (ffi/field-offset div-t :quot)
        rem-off (ffi/field-offset div-t :rem)]
    (ffi/with-layout [out div-t]
      (bench "div, call only" #(c-div-into out 7 2))
      (bench "div, call + read-field" #(do (c-div-into out 7 2) (div-fields out)))
      (bench "div, call + hoisted offsets"
             #(do (c-div-into out 7 2)
                  {:quot (ffi/read out :int quot-off)
                   :rem (ffi/read out :int rem-off)}))))
  (bench "div, destination allocated per call" #(c-div 7 2))
  (bench "abs, primitives only" #(c-abs -7))
  (println)

  (println (if (= {:quot 3 :rem 1} (c-div 7 2)) "STRUCTS OK" "STRUCTS FAIL")))
