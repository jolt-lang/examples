(ns app.native
  "The startup guard every example shares: is the library it needs installed?

  The platform library names live in deps.edn under `:jolt/native`, and both are
  `:optional true`, so a missing one is not fatal at startup. Each example
  therefore checks its own by asking whether one of its symbols resolved.
  `ffi/dlsym-native` answers from the handles the declared natives were dlopen'd
  into, so a hit means the library loaded and exports the symbol."
  (:require
   [jolt.ffi :as ffi]))

(defn need!
  "Exit with an install hint unless `symbol-name` resolves in a loaded native.

  Called before the first foreign call rather than before the defcfns: jolt
  resolves a foreign symbol lazily, on first use, so declaring bindings against
  a library that never loaded is harmless right up to the call."
  [symbol-name hint]
  (when-not (ffi/dlsym-native symbol-name)
    (binding [*out* *err*]
      (println (str "missing shared library: no native exports " symbol-name))
      (println (str "  " hint)))
    (System/exit 1)))
