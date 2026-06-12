# Native-executable entry point. `jpm build` (run via ./build.sh) imports this
# file, runs its top level, and marshals the resulting image into a standalone
# binary — the Jolt context built below, with app.core and its deps (ring,
# Selmer, config) already loaded and compiled, is baked in at build time.

# jolt's stdlib_embed collects the embedded stdlib relative to the jolt repo
# root at module-load time, so hop there for the import. build.sh sets
# JOLT_REPO and puts <jolt-repo>/src on JANET_PATH.
(def- build-cwd (os/cwd))
(os/cd (os/getenv "JOLT_REPO" "../../jolt"))
(import jolt/api :as api)
(os/cd build-cwd)

(def- ctx
  (let [c (api/init {:compile? true})]
    (api/eval-string c "(require '[app.core])")
    c))

(defn- clj-args [args]
  (string "[" (string/join (map |(string/format "%j" $) args) " ") "]"))

(defn- run-nrepl [addr]
  (var host "127.0.0.1")
  (var port 7888)
  (when addr
    (if-let [i (string/find ":" addr)]
      (do (when (> i 0) (set host (string/slice addr 0 i)))
          (set port (scan-number (string/slice addr (+ i 1)))))
      (set port (scan-number addr))))
  (api/eval-string ctx "(require '[jolt.nrepl])")
  (api/eval-string ctx (string "(jolt.nrepl/start-server! {:host \"" host "\" :port " port "})"))
  (spit ".nrepl-port" (string port))
  (def cleanup (fn [&] (protect (os/rm ".nrepl-port"))))
  (os/sigaction :int (fn [&] (cleanup) (os/exit 0)) true)
  (os/sigaction :term (fn [&] (cleanup) (os/exit 0)) true)
  (print "ring-app nREPL server started on " host ":" port)
  (flush)
  (defer (cleanup)
    (forever (ev/sleep 60))))

(defn main [& raw]
  (def argv (if (> (length raw) 1) (array/slice raw 1) @[]))
  (if (= "nrepl" (get argv 0))
    (run-nrepl (get argv 1))
    (api/eval-string ctx
      (string "(apply app.core/-main " (clj-args argv) ")"))))
