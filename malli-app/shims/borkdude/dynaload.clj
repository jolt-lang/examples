(ns borkdude.dynaload)
;; Minimal shim: malli uses dynaload only to lazily locate optional deps (sci,
;; test.check), which aren't available on jolt. Return a deref-able that yields
;; the :default, so malli takes its no-optional-dep fallback paths.
(defn dynaload [_sym & [opts]]
  (delay (:default opts)))
