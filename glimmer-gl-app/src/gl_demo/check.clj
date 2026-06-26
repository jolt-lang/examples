(ns gl-demo.check
  "Headless verification (no GL context, no display) that the declarative scene
  compiles to a render plan, the depth + lit shaders emit the expected GLSL
  (shadow sampler, fog, the two attrib locations), and the gothic geometry
  produces meshes whose materials all resolve. Real GL is exercised only at
  runtime via core.clj; this is the smoke test that catches data/shape bugs."
  (:require [clojure.string   :as str]
            [gl-demo.core        :as demo]
            [gl-demo.gothic     :as gothic]
            [glimmer-gl.renderer :as renderer]
            [glimmer-gl.shader  :as sh]
            [glimmer-gl.matrix  :as m]
            [glimmer-gl.scene   :as gscene]
            [glimmer-gl.mesh    :as mesh]
            [glimmer.widget     :as w]
            [glimmer-gl.gtk]))                       ; side-effect: widget registry

(defn -main [& _]
  ;; compile-check the app modules that need a GL context at runtime: requiring
  ;; them catches symbol/arity errors without starting the GUI.
  (require 'gl-demo.core)
  ;; --- shaders emit the expected GLSL ---------------------------------------
  (let [{dvs :vs-src dfs :fs-src} (sh/sources renderer/depth-spec)
        {lvs :vs-src lfs :fs-src} (sh/sources renderer/lit-spec)]
    (println "depth shader: vs" (count dvs) "chars  fs" (count dfs) "chars")
    (assert (str/includes? dvs "gl_Position = u_mvp") "depth shader must transform a_pos by u_mvp")
    (println "lit shader:   vs" (count lvs) "chars  fs" (count lfs) "chars")
    (assert (str/includes? lvs "layout(location=0) in vec3 a_pos;")   "lit shader: a_pos attrib @ loc 0")
    (assert (str/includes? lvs "layout(location=1) in vec3 a_normal;") "lit shader: a_normal attrib @ loc 1")
    (assert (str/includes? lvs "uniform sampler2DShadow u_shadow_map;")  "lit shader: shadow sampler uniform")
    (assert (str/includes? lfs "texture(u_shadow_map")                   "lit shader: shadow lookup")
    (assert (str/includes? lfs "u_fog_far")                               "lit shader: distance fog"))
  ;; --- declarative scene -> render plan -------------------------------------
  ;; NB: mirror the app pipeline exactly — expand (splices (for..) seqs, resolves
  ;; [component] invocations) THEN flatten. Skipping expand silently drops seq
  ;; children in flatten's catch-all.
  (let [plan   (gscene/flatten
                 (gscene/expand
                   (gscene/group (m/ident)
                     (gscene/camera {:eye [0 5 16] :target [0 4 -8] :up [0 1 0]
                                     :fov 55 :near 0.1 :far 200})
                     (gscene/light {:dir [-0.5 -0.6 -0.5] :color [1 0.95 0.82]})
                     (gothic/cathedral))))
        items  (:items plan)
        unique (into #{} (map :geom items))
        tris   (reduce + (map (fn [g] (count (mesh/triangles g))) unique))]
    (println "gothic scene:")
    (println (format "  items=%d  unique meshes=%d  triangles=%d  lights=%d  camera=%s"
                     (count items) (count unique) tris (count (:lights plan))
                     (some? (:camera plan))))
    (assert (pos? (count items)) "scene produced no render items")
    (assert (:camera plan)       "scene has no camera")
    (assert (= 1 (count (:lights plan))) "scene should have exactly one light")
    (let [used (into #{} (map :material items))]
      (assert (every? #(contains? renderer/material-colors %) used)
              (str "unresolved materials: " used))
      (println "  materials:" (str/join " " (sort used)))))
  ;; --- reactivity: a cell change recomputes the plan -------------------------
  ;; The path the mouse takes each frame: :on-motion reset!s a scene-driving cell,
  ;; and scene/plan (a glimmer reaction) recomputes on the write. No GL/GTK here.
  (let [rxn (gscene/plan #(demo/scene-root))
        y1  (nth (get-in @rxn [:camera :eye]) 1)]
    (reset! demo/cam-elev 12.0)
    (let [y2 (nth (get-in @rxn [:camera :eye]) 1)]
      (println "reactive plan:  eye.y" y1 "->" y2 "after cam-elev <- 12")
      (assert (not= y1 y2) "cam-elev change did not recompute the plan / move the camera")))
  ;; --- glimer widget specs registered ---------------------------------------
  (let [registered (every? #(contains? @w/specs %) [:gl-area :scale])]
    (println "widgets registered:" registered)
    (assert registered "gl-area/scale widget specs not registered"))
  (println "check: ok"))
