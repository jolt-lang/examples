(ns fps-demo.capture
  "macOS CoreGraphics mouse capture for first-person look. Dissociates the
  hardware mouse from the system cursor (CGAssociateMouseAndMouseCursorPosition)
  so the player can turn indefinitely — the cursor can't reach a screen edge and
  movement keeps accumulating — and reads the raw per-frame delta via
  CGGetLastMouseDelta. GTK4/GDK4 removed gdk_device_warp, so the classic
  warp-to-center pointer-lock trick is impossible through GDK; CoreGraphics
  association is the native equivalent (this is how native Mac FPS games capture
  the mouse). The library load + defcfns are guarded to macOS so the ns stays
  load-safe elsewhere — the functions are inert no-ops off macOS."
  (:require [jolt.ffi       :as ffi]
            [clojure.string :as str]))

(def ^:private macos?
  (str/includes? (str/lower-case (or (System/getProperty "os.name") "")) "mac"))

;; kCGNullDirectDisplay == 0 selects all displays.
(def ^:private kCGNullDirectDisplay (int 0))

;; Load CoreGraphics and bind the two symbols we need. defcfn resolves the C
;; entry point when the def runs (at ns load), so the library must be loaded
;; first. jolt interns the vars from both if-branches at analysis time, so the
;; guarded references below resolve either way; off macOS the branch never runs
;; and the no-op guards keep them uncalled. Mirrors jolt.nrepl's socket branch.
(if macos?
  (do
    (ffi/load-library "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics")
    (ffi/defcfn ^:private cg-associate
      "CGAssociateMouseAndMouseCursorPosition" [:uint :int] :int)
    (ffi/defcfn ^:private cg-mouse-delta
      "CGGetLastMouseDelta" [:pointer :pointer] :int))
  nil)

(defn enter!
  "Dissociate the mouse from the cursor so movement accumulates as raw deltas
  instead of moving (and clipping at) the cursor. Inert no-op off macOS."
  []
  (when macos?
    (cg-associate kCGNullDirectDisplay 0)))

(defn exit!
  "Reassociate the mouse with the cursor (capture released). Inert off macOS."
  []
  (when macos?
    (cg-associate kCGNullDirectDisplay 1)))

(defn delta
  "Return [dx dy] (doubles) of mouse motion accumulated since the last call,
  then clear the accumulator. Returns [0 0] off macOS."
  []
  (if-not macos?
    [0.0 0.0]
    (let [p (ffi/alloc (* 2 (ffi/sizeof :int)))]
      (cg-mouse-delta p (+ (long p) 4))
      (let [dx (double (ffi/read p :int))
            dy (double (ffi/read p :int 4))]
        (ffi/free p)
        [dx dy]))))
