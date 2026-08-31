(ns app.check
  "Load every example without running it, and report which libraries are here.

      jolt check

  jolt resolves a foreign symbol lazily, so a namespace full of `defcfn`s
  against a library that never loaded still compiles. That makes this a real
  check of the bindings' shape — literal signatures, arities, layouts, and the
  code around them — on a machine with none of the libraries installed. What it
  cannot check is a signature against the real C function; only calling it can."
  (:require
   [jolt.ffi :as ffi]))

(def examples
  '[app.native
    app.structs
    app.libffi
    app.portaudio])

;; One symbol per declared library. libc, which app.structs uses, is always
;; there and has no entry.
(def libraries
  [["libffi" "ffi_prep_cif" "libffi"]
   ["portaudio" "Pa_Initialize" "portaudio"]])

(defn -main
  [& _]
  (let [failures (reduce (fn [failures ns-sym]
                           (let [error (try (require ns-sym) nil
                                            (catch :default e (ex-message e)))]
                             (println (if error "FAIL" "  ok") ns-sym)
                             (when error (println "      " error))
                             (cond-> failures error (conj ns-sym))))
                         []
                         examples)]
    (println)
    (println "declared native libraries on this machine:")
    (doseq [[name symbol-name used-by] libraries]
      (println (format "  %-10s %-8s (%s)"
                       name
                       (if (ffi/dlsym-native symbol-name) "present" "missing")
                       used-by)))
    (println)
    (if (seq failures)
      (do (println (count failures) "namespace(s) failed to load")
          (System/exit 1))
      (println "CHECK OK -" (count examples) "namespaces loaded"))))
