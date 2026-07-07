(ns fps-demo.textured
  "Textured axis-aligned geometry for the q1k3 render pipeline: vertices are
  interleaved [x y z u v nx ny nz] (stride 8), so a single buffer carries
  position + texture coordinate + normal. glimmer-gl's mesh model is UV-free
  (faces are bare Vec3 lists), so textured boxes — and later model meshes — are
  produced here rather than via glimmer-gl.mesh.

  Corner numbering is (x-bit, y-bit, z-bit): 0=(0,0,0) 1=(X,0,0) 2=(0,Y,0)
  3=(X,Y,0) 4=(0,0,Z) 5=(X,0,Z) 6=(0,Y,Z) 7=(X,Y,Z). Each face lists its four
  corners in CCW order as seen from outside, so back-face culling + a CCW front
  face show a closed box.")

(defn- corners
  [ox oy oz sx sy sz]
  {0 [ox         oy         oz]
   1 [(+ ox sx)  oy         oz]
   2 [ox         (+ oy sy)  oz]
   3 [(+ ox sx)  (+ oy sy)  oz]
   4 [ox         oy         (+ oz sz)]
   5 [(+ ox sx)  oy         (+ oz sz)]
   6 [ox         (+ oy sy)  (+ oz sz)]
   7 [(+ ox sx)  (+ oy sy)  (+ oz sz)]})

;; World units spanned by one texture tile. q1k3 tiles each face by
;; face-world-dim / texture-width; the atlas resamples every texture to 64, so a
;; single constant gives uniform (square) texel density and, crucially, tiles U
;; and V independently — using one `tile` for a whole box stretched the texture
;; on non-cubic walls.
(def ^:const texel-world 64.0)

;; Each entry: [i0 i1 i2 i3 nx ny nz u-idx v-idx]. (i0..i3) wind CCW viewed from
;; outside (cross(v1-v0, v2-v0) == the declared normal, verified in check). u-idx
;; / v-idx select which of the box's [sx sy sz] world dimensions the face's U and
;; V axes span (0=x 1=y 2=z), matching the p0->p1 (U) and p0->p3 (V) corner edges.
(def ^:private faces
  [[1 3 7 5  1.0  0.0  0.0  1 2]   ; +X : U=Y V=Z
   [0 4 6 2 -1.0  0.0  0.0  2 1]   ; -X : U=Z V=Y
   [2 6 7 3  0.0  1.0  0.0  2 0]   ; +Y : U=Z V=X
   [0 1 5 4  0.0 -1.0  0.0  0 2]   ; -Y : U=X V=Z
   [4 5 7 6  0.0  0.0  1.0  0 1]   ; +Z : U=X V=Y
   [0 2 3 1  0.0  0.0 -1.0  1 0]]) ; -Z : U=Y V=X

(defn- quad
  "Two triangles (6 verts, 9 floats each) for one face: the 4 CCW corner indices,
  the face normal, per-face U/V tile counts, and the texture-array layer. UVs run
  [0..urep] x [0..vrep] across the face."
  [c i0 i1 i2 i3 nx ny nz urep vrep tex-index]
  (let [p0 (c i0) p1 (c i1) p2 (c i2) p3 (c i3)
        emit (fn [[x y z] u v] [x y z u v nx ny nz tex-index])]
    (concat
     (emit p0 0.0   0.0)
     (emit p1 urep  0.0)
     (emit p2 urep  vrep)
     (emit p0 0.0   0.0)
     (emit p2 urep  vrep)
     (emit p3 0.0   vrep))))

(defn box
  "Textured axis-aligned box. Returns {:data [...floats] :count 36 :stride 9}.
  Six faces tessellated to 36 vertices; each vertex is
  [x y z u v nx ny nz tex-index]. Each face tiles the texture by its own world
  dimensions / texel-world (uniform density, no stretch); `tex-index` selects the
  atlas layer for every face."
  [[ox oy oz] [sx sy sz] tex-index]
  (let [c    (corners ox oy oz sx sy sz)
        size [sx sy sz]
        data (->> faces
                  (mapcat (fn [[i0 i1 i2 i3 nx ny nz u-idx v-idx]]
                            (quad c i0 i1 i2 i3 nx ny nz
                                  (/ (double (nth size u-idx)) texel-world)
                                  (/ (double (nth size v-idx)) texel-world)
                                  tex-index)))
                  vec)]
    {:data data :count 36 :stride 9}))
