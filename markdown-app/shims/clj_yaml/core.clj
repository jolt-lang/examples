(ns clj-yaml.core)
;; Stub: jolt-deps doesn't resolve the Maven clj-yaml dep, and YAML front-matter
;; metadata is optional. Only :parse-meta? uses this; the stub keeps the require
;; satisfiable. Returns an empty map.
(defn parse-string [s & _] {})
