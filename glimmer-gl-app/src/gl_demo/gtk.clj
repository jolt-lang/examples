(ns gl-demo.gtk
  "GTK4 bindings glimmer.ffi doesn't cover: GtkGLArea (the OpenGL drawing
   surface) and GtkScale (sliders) for the demo's control panel."
  (:require [jolt.ffi :as ffi]))

;; GtkOrientation enum (GTK_ORIENTATION_HORIZONTAL = 0, _VERTICAL = 1).
(def ORIENTATION-HORIZONTAL 0)
(def ORIENTATION-VERTICAL 1)

;; --- GtkGLArea ---------------------------------------------------------------
(ffi/defcfn gtk-gl-area-new
  "gtk_gl_area_new" [] :pointer)
(ffi/defcfn gtk-gl-area-make-current
  "gtk_gl_area_make_current" [:pointer] :void)
(ffi/defcfn gtk-gl-area-queue-render
  "gtk_gl_area_queue_render" [:pointer] :void)
(ffi/defcfn gtk-gl-area-set-required-version
  "gtk_gl_area_set_required_version" [:pointer :int :int] :void)
(ffi/defcfn gtk-gl-area-get-error
  "gtk_gl_area_get_error" [:pointer] :pointer)

(defn gl-area-error-message
  "Decode a GtkGLArea's GError (if any) to a string; nil when there is no error.
   GError is { guint32 domain; gint32 code; gchar *message; } — message at byte 8."
  [area]
  (let [err (gtk-gl-area-get-error area)]
    (when-not (ffi/null? err)
      (let [msg (ffi/read err :pointer 8)]
        (when-not (ffi/null? msg)
          (ffi/ptr->string msg))))))

;; --- per-frame animation -----------------------------------------------------
;; gtk_widget_add_tick_callback registers a callback synced to the widget's
;; GdkFrameClock. GTK passes the widget pointer to every invocation, so the
;; area we queue_render on is always the right object (and only fires while the
;; widget is mapped + realized — no early/spurious calls).
(ffi/defcfn gtk-widget-add-tick-callback
  "gtk_widget_add_tick_callback" [:pointer :pointer :pointer :pointer] :uint)

;; --- GtkScale / GtkRange (sliders) -------------------------------------------
(ffi/defcfn gtk-scale-new-with-range
  "gtk_scale_new_with_range" [:int :double :double :double] :pointer)
(ffi/defcfn gtk-range-get-value
  "gtk_range_get_value" [:pointer] :double)
(ffi/defcfn gtk-range-set-value
  "gtk_range_set_value" [:pointer :double] :void)
(ffi/defcfn gtk-scale-set-digits
  "gtk_scale_set_digits" [:pointer :int] :void)
