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

;; Each entry: [i0 i1 i2 i3 nx ny nz]. (i0,i1,i2,i3) wind CCW viewed from
;; outside, so cross(v1-v0, v2-v0) == the declared normal (verified in check).
(def ^:private faces
  [[1 3 7 5  1.0  0.0  0.0]   ; +X
   [0 4 6 2 -1.0  0.0  0.0]   ; -X
   [2 6 7 3  0.0  1.0  0.0]   ; +Y
   [0 1 5 4  0.0 -1.0  0.0]   ; -Y
   [4 5 7 6  0.0  0.0  1.0]   ; +Z
   [0 2 3 1  0.0  0.0 -1.0]]) ; -Z

(defn- quad
  "Two triangles (6 verts, 9 floats each) for one face: the 4 CCW corner indices,
  the face normal, the texture tile count, and the texture-array layer index.
  UVs run [0..tile] across the face."
  [c i0 i1 i2 i3 nx ny nz tile tex-index]
  (let [p0 (c i0) p1 (c i1) p2 (c i2) p3 (c i3)
        emit (fn [[x y z] u v] [x y z u v nx ny nz tex-index])]
    (concat
     (emit p0 0.0   0.0)
     (emit p1 tile  0.0)
     (emit p2 tile  tile)
     (emit p0 0.0   0.0)
     (emit p2 tile  tile)
     (emit p3 0.0   tile))))

(defn box
  "Textured axis-aligned box. Returns {:data [...floats] :count 36 :stride 9}.
  Six faces tessellated to 36 vertices; each vertex is
  [x y z u v nx ny nz tex-index]. `tile` is how many times the texture repeats
  across each face edge; `tex-index` selects the texture-array layer for every
  face of this box."
  [[ox oy oz] [sx sy sz] tile tex-index]
  (let [c (corners ox oy oz sx sy sz)
        data (->> faces
                  (mapcat (fn [[i0 i1 i2 i3 nx ny nz]]
                            (quad c i0 i1 i2 i3 nx ny nz tile tex-index)))
                  vec)]
    {:data data :count 36 :stride 9}))
