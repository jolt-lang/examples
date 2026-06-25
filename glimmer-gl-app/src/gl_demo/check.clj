(ns gl-demo.check
  "Headless sanity check: confirms the data-defined shader renders to GLSL with
   its declarations generated, the glimmer-gl geometry pipeline produces a
   well-formed vertex buffer for each shape, and the glimmer-gl.gtk extension
   loads (its :gl-area / :scale widgets register into glimmer). Needs no GL
   context and no display. Run with `joltc -M:check`."
  (:require [clojure.string :as str]
            [gl-demo.scene :as scene]
            [glimmer-gl.shader :as sh]
            [glimmer-gl.primitives :as p]
            [glimmer-gl.mesh :as mesh]
            [glimmer.widget :as w]
            [glimmer-gl.gtk]))            ; loading registers the widgets

(defn- check-shape [label m]
  (let [{:keys [data count stride]} (mesh/->floats m {:shading :smooth})]
    (println (format "  %-7s faces=%d tris=%d verts=%d floats=%d stride=%d"
                     label (clojure.core/count (mesh/faces m))
                     (clojure.core/count (mesh/triangles m))
                     count (clojure.core/count data) stride))
    (assert (pos? count) (str label " produced no vertices"))
    (assert (= (clojure.core/count data) (* count stride)) "buffer layout mismatch")))

(defn -main [& _]
  (let [{:keys [vs-src fs-src]} (sh/sources scene/shader-spec)]
    (println "shader: vs" (count vs-src) "chars, fs" (count fs-src) "chars")
    (assert (str/includes? vs-src "layout(location=0) in vec3 a_pos;") "a_pos attrib missing")
    (assert (str/includes? fs-src "uniform float u_time;") "u_time uniform missing")
    (assert (str/includes? fs-src "vec3 palette(") "palette snippet missing")
    (assert (str/includes? fs-src "float plasma(") "plasma snippet missing"))
  (println "geometry:")
  (check-shape "cube"   (p/cuboid 1.5))
  (check-shape "sphere" (p/sphere 1.0 28 18))
  (check-shape "tetra"  (p/tetrahedron 1.35))
  (println "widgets registered:"
           (every? #(contains? @w/specs %) [:gl-area :scale]))
  (assert (every? #(contains? @w/specs %) [:gl-area :scale]) "widgets not registered")
  (println "check: ok"))
