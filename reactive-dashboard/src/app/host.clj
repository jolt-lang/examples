(ns app.host
  "Where the machine's figures come from, per OS.

  Linux reads procfs. macOS has no /proc: per-core CPU ticks come from Mach's
  host_processor_info through jolt.ffi, memory from `vm_stat`, network from
  `netstat -ib`. Both return the same snapshot shape, so the lane that polls
  this and everything after it is shared."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [jolt.ffi :as ffi]
            [app.metrics :as metrics]))

(def os
  (let [n (str/lower-case (or (System/getProperty "os.name") ""))]
    (cond (str/includes? n "mac") :darwin
          (str/includes? n "linux") :linux
          :else :unknown)))

;; ------------------------------------------------------------------- Linux

(defn- linux-snapshot []
  {:stat (metrics/parse-stat (slurp "/proc/stat"))
   :mem  (metrics/parse-meminfo (slurp "/proc/meminfo"))
   :net  (metrics/parse-netdev (slurp "/proc/net/dev"))})

;; ------------------------------------------------------------------- macOS
;;
;; The bindings are built on first use, inside a delay: the Mach symbols do not
;; exist on Linux, and this namespace loads there too.

(def ^:private PROCESSOR_CPU_LOAD_INFO 2)
(def ^:private CPU_STATE_MAX 4)

(def ^:private mach
  (delay
    {:host-self (ffi/foreign-fn "mach_host_self" [] :uint)
     :processor-info (ffi/foreign-fn "host_processor_info"
                                     [:uint :int :pointer :pointer :pointer] :int)
     :deallocate (ffi/foreign-fn "vm_deallocate" [:uint :uptr :uptr] :int)
     :sysctlbyname (ffi/foreign-fn "sysctlbyname"
                                   [:string :pointer :pointer :pointer :size_t] :int)}))

;; mach_host_self hands out a port right per call: take one and keep it
(def ^:private host-port (delay ((:host-self @mach))))

(defn- task-self
  "mach_task_self() is a macro over this global."
  []
  (ffi/read (ffi/find-symbol "mach_task_self_") :uint32))

(defn- cpu-ticks
  "Per core [user system idle nice], cumulative since boot."
  []
  (let [{:keys [processor-info deallocate]} @mach]
    (ffi/with-out [ncpu :uint32]
      (ffi/with-out [info :pointer]
        (ffi/with-out [ninfo :uint32]
          (let [kr (processor-info @host-port PROCESSOR_CPU_LOAD_INFO ncpu info ninfo)]
            (when-not (zero? kr)
              (throw (ex-info "host_processor_info failed" {:kern-return kr})))
            (let [n   (ffi/read ncpu :uint32)
                  arr (ffi/read info :pointer)
                  ;; natural_t is unsigned; read-array gives signed ints
                  ticks (mapv #(bit-and % 0xffffffff)
                              (ffi/read-array arr :uint32 (* n CPU_STATE_MAX)))]
              (deallocate (task-self) arr (* (ffi/read ninfo :uint32) 4))
              (mapv vec (partition CPU_STATE_MAX ticks)))))))))

(def ^:private memsize
  (delay
    (ffi/with-out [v :uint64]
      (ffi/with-out [len :size_t]
        (ffi/write len :size_t 8)
        (when (zero? ((:sysctlbyname @mach) "hw.memsize" v len ffi/null 0))
          (ffi/read v :uint64))))))

(defn- sh [& args]
  (:out (apply p/shell {:out :string :err :string} args)))

(defn- darwin-snapshot []
  {:stat (metrics/cpu-ticks->stat (cpu-ticks))
   :mem  (metrics/parse-vm-stat (sh "vm_stat") @memsize)
   :net  (metrics/parse-netstat-ib (sh "netstat" "-ib" "-n"))})

;; -------------------------------------------------------------------- shared

(defn snapshot
  "{:stat :mem :net} in the procfs parsers' shapes. Blocking: run it off the
  flow's thread."
  []
  (case os
    :darwin (darwin-snapshot)
    (linux-snapshot)))

(def source-label
  (case os
    :darwin "mach + vm_stat · polled sampler"
    "/proc · polled sampler"))

;; A producer fast enough to overwhelm a browser: one line every 20ms,
;; sequence-numbered so the consumer can tell how far behind real time the
;; producer has fallen. On Linux the payload is a /proc/stat line, read with
;; `read -r line < /proc/stat` so only `sleep` forks. macOS has nothing to read
;; without a fork, so it carries bash's seconds counter: the lanes measure the
;; sequence number, not the payload.
;;
;; `|| exit 0` is the orphan guard: once our end of the pipe is gone the echo
;; fails with EPIPE and the producer exits instead of looping forever on
;; broken-pipe errors. It covers the exits a shutdown hook does not -- and on
;; this jolt the hook does not run on SIGINT at all.
(def producer-script
  (case os
    :darwin "i=0; while :; do i=$((i+1)); echo \"$i tick $SECONDS\" || exit 0; sleep 0.02; done"
    "i=0; while :; do i=$((i+1)); read -r line < /proc/stat; echo \"$i $line\" || exit 0; sleep 0.02; done"))
