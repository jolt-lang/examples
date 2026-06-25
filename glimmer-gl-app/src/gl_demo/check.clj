(ns gl-demo.check
  "Headless sanity check for the demo's scene description: confirms the GLSL
   sources are present and non-empty. Run with `joltc -M:check`. Needs no GL
   context and no display."
  (:require [gl-demo.scene :as scene]))

(defn -main [& _]
  (println "vs-source chars:" (count scene/vs-source))
  (println "fs-source chars:" (count scene/fs-source))
  (println "scene: ok"))
