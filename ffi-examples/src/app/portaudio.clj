(ns app.portaudio
  "An arpeggio played from a REALTIME AUDIO CALLBACK, through PortAudio.

      jolt portaudio

  `ffi/foreign-callable` wraps a jolt fn as a C function pointer, so C can call
  back INTO jolt. Here the trailing `:collect-safe` is the whole point, and it is
  not optional. PortAudio does not call your callback on the thread that opened
  the stream: it runs an audio thread of its own, at realtime priority, and calls
  in from there. That thread is one the jolt runtime never started, so entering
  jolt code on it has to activate it with the collector first — which is what the
  flag does. Without it the process dies in a nonrecoverable memory fault that no
  handler can catch, some time after the stream starts, with a stack trace that
  points nowhere useful.

  The same flag covers the other off-thread case: a callback arriving on a jolt
  thread that is currently parked inside a `:blocking` foreign call, which is
  how a GUI toolkit's main loop delivers its events.

  The rest of the file is realtime discipline rather than FFI. The callback has a
  fixed budget — frames/sample-rate, about 5.8 ms at 256 frames and 44.1 kHz —
  and overrunning it is an audible click, so it allocates nothing: the oscillator
  phase and the counters live in primitive arrays with type hints, so `aget` and
  `aset` compile to array access, and samples go straight into PortAudio's output
  buffer with `ffi/write` rather than through an intermediate array. Underruns
  are counted, not printed; the callback does no I/O.

  The main thread writes the note into an atom while the audio thread reads it.
  One deref per callback, no lock, and a torn read is impossible because a jolt
  atom holds one immutable value."
  (:require
   [app.native :as native]
   [jolt.ffi :as ffi]))

(ffi/defcfn pa-initialize "Pa_Initialize" [] :int)
(ffi/defcfn pa-terminate "Pa_Terminate" [] :int)
(ffi/defcfn pa-error-text "Pa_GetErrorText" [:int] :string)
(ffi/defcfn pa-open-default-stream "Pa_OpenDefaultStream"
  [:pointer :int :int :ulong :double :ulong :pointer :pointer] :int)
(ffi/defcfn pa-start-stream "Pa_StartStream" [:pointer] :int)
(ffi/defcfn pa-stop-stream "Pa_StopStream" [:pointer] :int)
(ffi/defcfn pa-close-stream "Pa_CloseStream" [:pointer] :int)

(def ^:const PA-FLOAT32 1)
(def ^:const PA-CONTINUE 0)
(def ^:const PA-OUTPUT-UNDERFLOW 0x4)

(def ^:const SAMPLE-RATE 44100.0)
(def ^:const FRAMES 256)
(def ^:const AMPLITUDE 0.2)
(def TAU (* 2.0 Math/PI))

(defn check
  [rc what]
  (when-not (zero? rc)
    (throw (ex-info (str what ": " (pa-error-text rc)) {:rc rc :call what})))
  rc)

;; The note the callback plays. The main thread writes it; the audio thread reads
;; it once per callback.
(def freq (atom 440.0))

;; Callback state, allocated once at namespace load, so the realtime path
;; neither allocates nor boxes.
(def ^double/1 phase (double-array 1))
(def ^long/1 calls (long-array 1))
(def ^long/1 underruns (long-array 1))

(defn stream-callback
  [_input output frame-count _time-info status-flags _user-data]
  (let [n (int frame-count)
        step (/ (* TAU (double @freq)) SAMPLE-RATE)]
    (loop [i 0
           p (aget phase 0)]
      (if (< i n)
        (do
          ;; float32 samples, four bytes apart, written where PortAudio asked.
          (ffi/write output :float (* AMPLITUDE (Math/sin p)) (* i 4))
          (recur (inc i)
                 (let [p (+ p step)]
                   (if (> p TAU) (- p TAU) p))))
        (aset phase 0 p))))
  (aset calls 0 (inc (aget calls 0)))
  (when-not (zero? (bit-and status-flags PA-OUTPUT-UNDERFLOW))
    (aset underruns 0 (inc (aget underruns 0))))
  PA-CONTINUE)

;; :collect-safe — see the namespace docstring. This is the flag the example
;; exists to show. The callable stays live until ffi/free-callable.
(def stream-cb
  (ffi/foreign-callable stream-callback
                        [:pointer :pointer :ulong :pointer :ulong :pointer] :int
                        :collect-safe))

(defn open-output-stream
  "One mono float32 output stream driven by stream-cb. The PaStream** out
  parameter is scoped to this call; the handle it yields outlives it."
  []
  (ffi/with-out [pp :pointer]
    (check (pa-open-default-stream pp 0 1 PA-FLOAT32 SAMPLE-RATE FRAMES
                                   stream-cb ffi/null)
           "Pa_OpenDefaultStream")
    (ffi/read pp :pointer)))

;; A major triad up and back down, in semitones from A440.
(def arpeggio [0 4 7 12 7 4 0 -5])

(defn -main
  [& _]
  (native/need! "Pa_Initialize"
                "brew install portaudio, or on Debian/Ubuntu apt install libportaudio2")
  (check (pa-initialize) "Pa_Initialize")
  (let [stream (open-output-stream)]
    (check (pa-start-stream stream) "Pa_StartStream")
    ;; This thread writes the note; the audio thread reads it.
    (doseq [semitone arpeggio]
      (reset! freq (* 440.0 (Math/pow 2.0 (/ semitone 12.0))))
      (Thread/sleep 350))
    (check (pa-stop-stream stream) "Pa_StopStream")
    (check (pa-close-stream stream) "Pa_CloseStream"))
  (check (pa-terminate) "Pa_Terminate")
  (ffi/free-callable stream-cb)

  (println "callbacks:" (aget calls 0))
  (println "budget:" (format "%.2f ms" (* 1000.0 (/ (double FRAMES) SAMPLE-RATE))))
  (println "underruns:" (aget underruns 0))
  (println (if (and (pos? (aget calls 0)) (zero? (aget underruns 0)))
             "PORTAUDIO OK" "PORTAUDIO FAIL")))
