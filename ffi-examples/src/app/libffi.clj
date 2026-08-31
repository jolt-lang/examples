(ns app.libffi
  "libffi bound through jolt.ffi, then used to make a call jolt.ffi cannot make
  on its own.

      jolt libffi

  `defcfn` needs its signature as LITERAL data at compile time, because jolt
  lowers it to a Chez `foreign-procedure` while compiling. That is what makes a
  foreign call cheap, and it is also a limit: a signature that is only known
  when the program runs has nowhere to go.

  libffi is the way out, and binding it needs nothing special —
  `ffi_prep_cif` and `ffi_call` are plain pointer/int signatures. Describe a
  call in memory (its ABI, its return type, its argument types), and `ffi_call`
  will make it. The example does that twice: once for `div_t div(int, int)`,
  a struct returned by value, and once for `ldexp`, to price the runtime path
  against the compiled one.

  Along the way it shows the memory API. `ffi/alloc` is malloc and `ffi/free` is
  free, with scoped wrappers — `ffi/with-alloc`, `ffi/with-out`,
  `ffi/with-layout` — for the single-value cases. A cif is not a single value:
  it is the cif itself, an ffi_type per argument, and the arrays pointing at
  them, all becoming garbage together. `arena` below is that group, in about
  fifteen lines.

  Note the argument order of a write: `(ffi/write pointer type offset value)`,
  offset before value."
  (:require
   [app.native :as native]
   [jolt.ffi :as ffi]))

;; --- a group of allocations with one lifetime --------------------------------

(defn arena
  "A group of allocations to be freed together."
  []
  (atom []))

(defn arena-alloc
  "`byte-count` bytes owned by `a`."
  [a byte-count]
  (let [pointer (ffi/alloc byte-count)]
    (swap! a conj pointer)
    pointer))

(defn arena-release!
  "Free everything `a` owns, once. Emptying the atom as it reads it means a
  second release, or one racing the first, frees nothing twice."
  [a]
  (doseq [pointer (first (swap-vals! a empty))]
    (ffi/free pointer)))

(defn with-arena
  "Call `f` with a fresh arena, releasing it on the way out — including when `f`
  throws, which is the point of routing through here rather than allocating in
  place."
  [f]
  (let [a (arena)]
    (try (f a) (finally (arena-release! a)))))

;; --- libffi ------------------------------------------------------------------

(ffi/defcfn prep-cif "ffi_prep_cif" [:pointer :int :uint :pointer :pointer] :int)
(ffi/defcfn ffi-call "ffi_call" [:pointer :pointer :pointer :pointer] :void)
(ffi/defcfn c-dlsym "dlsym" [:pointer :string] :pointer)

;; ffi_type — {size_t size; unsigned short alignment; unsigned short type;
;; ffi_type **elements}. A layout rather than four hand-counted offsets: Chez
;; computes the padding between `type` at 10 and `elements` at 16, so this
;; description stays right on an ABI where the padding differs.
(def ffi-type-t
  (ffi/layout [:struct [[:size :size_t]
                        [:alignment :ushort]
                        [:type :ushort]
                        [:elements :pointer]]]))

;; libffi's type codes, from ffi.h.
(def ^:const FFI-TYPE-DOUBLE 3)
(def ^:const FFI-TYPE-SINT32 10)
(def ^:const FFI-TYPE-STRUCT 13)

;; FFI_DEFAULT_ABI is per-architecture: FFI_SYSV is 1 on aarch64, FFI_UNIX64 is
;; 2 on x86-64.
(def FFI-DEFAULT-ABI
  (if (= "aarch64" (System/getProperty "os.arch")) 1 2))

;; RTLD_DEFAULT — "search every loaded object" — is a pseudo-handle rather than
;; an address: 0 on Linux, (void*) -2 on macOS. A jolt pointer IS an integer, so
;; it is written as one, and `ffi/null` is the integer 0.
(def RTLD-DEFAULT
  (if (= "Mac OS X" (System/getProperty "os.name")) -2 ffi/null))

(defn sym-addr
  "The address of `name` in any loaded object."
  [name]
  (let [pointer (c-dlsym RTLD-DEFAULT name)]
    (when (ffi/null? pointer)
      (throw (ex-info (str "symbol not found: " name) {:symbol name})))
    pointer))

(defn ffi-type
  "An ffi_type owned by `a`."
  [a {:keys [size alignment code elements]}]
  (let [t (arena-alloc a (ffi/layout-size ffi-type-t))]
    (ffi/write-field t ffi-type-t :size size)
    (ffi/write-field t ffi-type-t :alignment alignment)
    (ffi/write-field t ffi-type-t :type code)
    (ffi/write-field t ffi-type-t :elements (or elements ffi/null))
    t))

(defn scalar-type
  "An ffi_type for a scalar of `size` bytes with libffi type code `code`."
  [a size code]
  (ffi-type a {:size size :alignment size :code code :elements ffi/null}))

(defn struct-type
  "An FFI_TYPE_STRUCT over `element-types`, NULL-terminated as libffi wants.
  Size and alignment are left at zero: ffi_prep_cif fills them in."
  [a element-types]
  (let [width (ffi/sizeof :pointer)
        elements (arena-alloc a (* width (inc (count element-types))))]
    (doseq [[i t] (map-indexed vector element-types)]
      (ffi/write elements :pointer (* width i) t))
    (ffi/write elements :pointer (* width (count element-types)) ffi/null)
    (ffi-type a {:size 0 :alignment 0 :code FFI-TYPE-STRUCT :elements elements})))

;; ffi_cif is opaque and its size is not in the public headers; 128 bytes is
;; comfortably more than any current ABI needs.
(def ^:const CIF-BYTES 128)

(defn make-cif
  "A prepared ffi_cif for (ret-type)(arg-types...), owned by `a`."
  [a ret-type arg-types]
  (let [width (ffi/sizeof :pointer)
        n (count arg-types)
        atypes (arena-alloc a (max width (* width n)))
        cif (arena-alloc a CIF-BYTES)]
    (doseq [[i t] (map-indexed vector arg-types)]
      (ffi/write atypes :pointer (* width i) t))
    (when-not (zero? (prep-cif cif FFI-DEFAULT-ABI n ret-type atypes))
      (throw (ex-info "ffi_prep_cif failed" {:args n})))
    cif))

;; --- a struct returned by value, described at runtime ------------------------
;; ffi_call writes the return value into rvalue, so an aggregate return costs
;; nothing extra here: a struct is just more bytes.

(defn call-div
  [numer denom]
  (with-arena
    (fn [a]
      (let [sint32 (scalar-type a 4 FFI-TYPE-SINT32)
            div-t (struct-type a [sint32 sint32])
            cif (make-cif a div-t [sint32 sint32])
            width (ffi/sizeof :pointer)
            a0 (arena-alloc a (ffi/sizeof :int))
            a1 (arena-alloc a (ffi/sizeof :int))
            avalues (arena-alloc a (* 2 width))
            rvalue (arena-alloc a 8)]
        ;; avalues is an array of POINTERS TO the arguments, not the arguments.
        (ffi/write avalues :pointer 0 a0)
        (ffi/write avalues :pointer width a1)
        (ffi/write a0 :int 0 numer)
        (ffi/write a1 :int 0 denom)
        (ffi-call cif (sym-addr "div") rvalue avalues)
        {:quot (ffi/read rvalue :int 0)
         :rem (ffi/read rvalue :int 4)}))))

;; --- the runtime cif against the compiled binding ---------------------------

(def N 200000)

(def c-ldexp (ffi/foreign-fn "ldexp" [:double :int] :double))

(defn bench-libffi-ldexp
  []
  (with-arena
    (fn [a]
      (let [double-t (scalar-type a 8 FFI-TYPE-DOUBLE)
            sint32 (scalar-type a 4 FFI-TYPE-SINT32)
            cif (make-cif a double-t [double-t sint32])
            fnp (sym-addr "ldexp")
            width (ffi/sizeof :pointer)
            a0 (arena-alloc a (ffi/sizeof :double))
            a1 (arena-alloc a (ffi/sizeof :int))
            avalues (arena-alloc a (* 2 width))
            rvalue (arena-alloc a 8)]
        (ffi/write avalues :pointer 0 a0)
        (ffi/write avalues :pointer width a1)
        (ffi/write a0 :double 0 1.5)
        (ffi/write a1 :int 0 3)
        (ffi-call cif fnp rvalue avalues)
        (println "ldexp(1.5, 3) via libffi =" (ffi/read rvalue :double 0))
        (let [t0 (System/nanoTime)]
          (dotimes [i N]
            (ffi/write a0 :double 0 1.5)
            (ffi/write a1 :int 0 i)
            (ffi-call cif fnp rvalue avalues)
            (ffi/read rvalue :double 0))
          (println "  libffi, runtime cif    "
                   (quot (- (System/nanoTime) t0) N) "ns/call"))))))

(defn bench-compiled-ldexp
  []
  (c-ldexp 1.5 3)
  (let [t0 (System/nanoTime)]
    (dotimes [i N] (c-ldexp 1.5 i))
    (println "  foreign-fn, compiled   "
             (quot (- (System/nanoTime) t0) N) "ns/call")))

;; --- what the allocations cost ----------------------------------------------
;; The arena is a convenience over alloc and free, not a faster allocator: it
;; calls both exactly as often. What costs is HOW OFTEN, so measure a fresh
;; allocation per iteration against one buffer reused, over identical work.

(def M 50000)

(defn bench-alloc-per-iteration
  []
  (let [t0 (System/nanoTime)]
    (dotimes [i M]
      (ffi/with-alloc [p (ffi/sizeof :int)]
        (ffi/write p :int 0 i)
        (ffi/read p :int 0)))
    (println "  alloc/free per iteration"
             (quot (- (System/nanoTime) t0) M) "ns")))

(defn bench-one-buffer
  []
  (ffi/with-alloc [p (ffi/sizeof :int)]
    (let [t0 (System/nanoTime)]
      (dotimes [i M]
        (ffi/write p :int 0 i)
        (ffi/read p :int 0))
      (println "  one buffer, reused      "
               (quot (- (System/nanoTime) t0) M) "ns"))))

(defn -main
  [& _]
  (native/need! "ffi_prep_cif"
                "libffi ships with macOS and with most Linux distributions; on Debian/Ubuntu: apt install libffi8")
  (let [result (call-div 7 2)]
    (println "div(7, 2) =" result (if (= {:quot 3 :rem 1} result) "OK" "FAIL")))
  (println)
  (bench-libffi-ldexp)
  (bench-compiled-ldexp)
  (println)
  (bench-alloc-per-iteration)
  (bench-one-buffer)
  (println)
  (println (if (= {:quot 3 :rem 1} (call-div 7 2)) "LIBFFI OK" "LIBFFI FAIL")))
