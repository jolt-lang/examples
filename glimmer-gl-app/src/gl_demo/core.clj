(ns gl-demo.core
  "Fullscreen procedural plasma shader in a GtkGLArea, with a small GTK4
   control panel.

   geom-gl supplies the GL shader plumbing; glimmer.ffi provides the raw GTK4
   bindings; gl-demo.gtk adds the few GLArea + slider + tick-callback calls
   glimmer omits; gl-demo.scene holds the GLSL.

   The GLArea is imperative (its realize/render/resize signals build GL objects
   and 'render' returns a gboolean), so it's driven with raw ffi callbacks
   rather than glimmer's reactive reconciler. Per-frame animation runs through
   gtk_widget_add_tick_callback: GTK passes the area pointer to every invocation,
   so the redraw target is always valid (and the callback only fires while the
   area is mapped + realized).

   Run with: joltc -M:run"
  (:require [geom-gl.gl    :as gl]
            [glimmer.ffi   :as gtk]
            [gl-demo.gtk   :as ext]
            [gl-demo.scene :as scene]
            [jolt.ffi      :as ffi]))

;; --- state -------------------------------------------------------------------
;; Per-GLArea GL handles, keyed by the area widget pointer (the first arg every
;; GTK signal handler receives).
(defonce gl-state (atom {}))
;; Strong refs to every foreign-callable we hand to GTK, for process lifetime.
(defonce retained-cbs (atom []))

;; Animation + control state (touched only from the UI thread).
(defonce time     (atom 0.0))
(defonce speed    (atom 1.0))    ; multiplies time accumulation
(defonce pattern-scale (atom 3.0))   ; u_scale: pattern frequency
(defonce warp     (atom 0.5))    ; u_warp: domain-warp strength
(defonce paused   (atom false))
(defonce viewport (atom [800 606]))

(def ^:private frame-dt 0.016)   ; ~60fps

;; --- GLArea signal handlers --------------------------------------------------
(defn on-realize [area _data]
  (ext/gtk-gl-area-make-current area)
  (when-let [err (ext/gl-area-error-message area)]
    (println "GLArea context error:" err))
  (let [prog (gl/make-program scene/vs-source scene/fs-source)]
    (if-not prog
      (println "gl-demo: failed to build GL program (see info log above)")
      (let [id-ptr (ffi/alloc (ffi/sizeof :uint))]   ; glGen* write target, reused
        (gl/gl-gen-vertex-arrays 1 id-ptr)
        (let [vao (ffi/read id-ptr :uint)]
          (ffi/free id-ptr)
          ;; No vertex buffer / attributes: the fullscreen triangle is generated
          ;; from gl_VertexID in the vertex shader. Core profile still requires a
          ;; bound VAO, so we create one and leave it empty.
          (gl/gl-bind-vertex-array vao)
          (swap! gl-state assoc area
                 {:program prog
                  :vao     vao
                  :loc-t   (gl/gl-get-uniform-location prog "u_time")
                  :loc-s   (gl/gl-get-uniform-location prog "u_scale")
                  :loc-w   (gl/gl-get-uniform-location prog "u_warp")})
          (println "gl-demo: GL ready — program" prog "vao" vao))))))

(defn on-resize [area width height _data]
  (reset! viewport [width height])
  (gl/gl-viewport 0 0 width height))

(defn on-render [area _data]
  (ext/gtk-gl-area-make-current area)   ; GTK4 already current; harmless + safe
  (if-let [st (get @gl-state area)]
    (let [prog (:program st)]
      (gl/gl-clear-color 0.04 0.05 0.07 1.0)
      (gl/gl-clear gl/GL-COLOR-BUFFER-BIT)
      (gl/gl-use-program prog)
      (gl/gl-uniform-1f (:loc-t st) (double @time))
      (gl/gl-uniform-1f (:loc-s st) (double @pattern-scale))
      (gl/gl-uniform-1f (:loc-w st) (double @warp))
      (gl/gl-bind-vertex-array (:vao st))
      ;; one oversized triangle covering the pane
      (gl/gl-draw-arrays gl/GL-TRIANGLES 0 3))
    1)   ; nothing built yet — realize will populate; keep rendering
  1)     ; gboolean TRUE → we handled the render

;; gtk_widget_add_tick_callback: (GtkWidget*, GdkFrameClock*, gpointer) → gboolean.
(defn on-tick [area _clock _data]
  (when-not @paused
    (swap! time + (* (double @speed) frame-dt)))
  (ext/gtk-gl-area-queue-render area)
  1)   ; gboolean TRUE → keep the callback alive

;; --- control panel -----------------------------------------------------------
;; foreign-callable is a macro needing literal argtypes/rettype, so every
;; callback is built inline; connect-cb just wires a pre-built pointer.
(defn- retain [cb] (swap! retained-cbs conj cb) cb)

(defn- connect-cb [widget signal cb]
  (gtk/g-signal-connect-data widget signal cb
    ffi/null ffi/null gtk/CONNECT-DEFAULT)
  widget)

(defn- slider [text min max step init on-change]
  (let [row  (gtk/gtk-box-new ext/ORIENTATION-HORIZONTAL 6)
        lbl  (gtk/gtk-label-new text)
        ;; jolt's foreign-procedure needs inexact flonums for :double args
        min  (double min)  max (double max)  step (double step)
        scl  (ext/gtk-scale-new-with-range ext/ORIENTATION-HORIZONTAL min max step)
        cb   (retain
               (ffi/foreign-callable
                 (fn [s _] (on-change (ext/gtk-range-get-value s)))
                 [:pointer :pointer] :void :collect-safe))]
    (gtk/gtk-widget-set-hexpand scl 1)
    (gtk/gtk-widget-set-margin-end lbl 8)
    (ext/gtk-range-set-value scl (double init))
    (ext/gtk-scale-set-digits scl 2)
    (gtk/gtk-box-append row lbl)
    (gtk/gtk-box-append row scl)
    (connect-cb scl "value-changed" cb)
    row))

(defn- control-panel []
  (let [panel (gtk/gtk-box-new ext/ORIENTATION-VERTICAL 6)]
    (gtk/gtk-box-set-spacing panel 6)
    (gtk/gtk-widget-set-margin-start panel 8)
    (gtk/gtk-widget-set-margin-end panel 8)
    (gtk/gtk-widget-set-margin-top panel 6)
    (gtk/gtk-widget-set-margin-bottom panel 6)
    (gtk/gtk-box-append panel
      (slider "Speed" 0.0 3.0 0.05 @speed #(reset! speed %)))
    (gtk/gtk-box-append panel
      (slider "Scale" 0.5 8.0 0.1 @pattern-scale #(reset! pattern-scale %)))
    (gtk/gtk-box-append panel
      (slider "Warp" 0.0 1.5 0.05 @warp #(reset! warp %)))
    (let [pause (gtk/gtk-button-new-with-label "Pause")
          cb    (retain
                  (ffi/foreign-callable
                    (fn [b _]
                      (swap! paused not)
                      (gtk/gtk-button-set-label b (if @paused "Resume" "Pause")))
                    [:pointer :pointer] :void :collect-safe))]
      (gtk/gtk-box-append panel pause)
      (connect-cb pause "clicked" cb))
    (let [reset (gtk/gtk-button-new-with-label "Reset time")
          cb    (retain
                  (ffi/foreign-callable
                    (fn [_ _] (reset! time 0.0))
                    [:pointer :pointer] :void :collect-safe))]
      (gtk/gtk-box-append panel reset)
      (connect-cb reset "clicked" cb))
    panel))

;; --- app ---------------------------------------------------------------------
(defn -main [& _]
  (let [app (gtk/gtk-application-new "dev.jolt.gldemo" gtk/APPLICATION-DEFAULT-FLAGS)
        activate
        (fn [app _]
          (let [win    (gtk/gtk-application-window-new app)
                root   (gtk/gtk-box-new ext/ORIENTATION-VERTICAL 0)
                glarea (ext/gtk-gl-area-new)]
            (gtk/gtk-window-set-title win "geom-gl • plasma shader")
            (gtk/gtk-window-set-default-size win 800 600)
            (ext/gtk-gl-area-set-required-version glarea 3 2)
            (gtk/gtk-widget-set-vexpand glarea 1)
            (gtk/gtk-widget-set-hexpand glarea 1)
            (connect-cb glarea "realize"
              (retain (ffi/foreign-callable on-realize [:pointer :pointer] :void :collect-safe)))
            (connect-cb glarea "render"
              (retain (ffi/foreign-callable on-render [:pointer :pointer] :int :collect-safe)))
            (connect-cb glarea "resize"
              (retain (ffi/foreign-callable on-resize [:pointer :int :int :pointer] :void :collect-safe)))
            ;; Per-frame: GTK hands us the area pointer on every tick, so the
            ;; redraw target is always the right (mapped, realized) object.
            (ext/gtk-widget-add-tick-callback glarea
              (retain (ffi/foreign-callable on-tick
                       [:pointer :pointer :pointer] :int :collect-safe))
              ffi/null ffi/null)
            (gtk/gtk-box-append root (control-panel))
            (gtk/gtk-box-append root glarea)
            (gtk/gtk-window-set-child win root)
            (gtk/gtk-window-present win)))]
    (connect-cb app "activate"
      (retain (ffi/foreign-callable activate [:pointer :pointer] :void :collect-safe)))
    (let [code (gtk/g-application-run app 0 ffi/null)]
      (when-not (zero? code)
        (println "gl-demo: g_application_run exited with code" code)))))
