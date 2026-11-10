(ns app.host
  "Where the machine's counters come from: the one platform seam.

  The parsers in `app.metrics` read Linux `/proc` text. On Linux this namespace
  slurps those files. On macOS there is no procfs, so it asks the kernel
  directly (Mach host statistics and sysctl through jolt.ffi, interface
  counters from `netstat`) and renders the answers in the same text formats,
  so everything downstream is the same code on both."
  (:require [clojure.string :as str]
            [babashka.process :as p]
            [jolt.ffi :as ffi]))

(def platform
  (let [os (str/lower-case (or (System/getProperty "os.name") ""))]
    (cond (str/includes? os "linux") :linux
          (or (str/includes? os "mac") (str/includes? os "darwin")) :macos
          :else :other)))

;; ------------------------------------------------------------------ producer

(defn producer-script
  "The shell loop behind lanes A and B: one sequence-numbered cpu line every
  20ms. On Linux `read -r line < /proc/stat` gets the line without forking a
  process per sample. macOS has no file to read at that rate, and the lanes
  only use the sequence number, so the line carries the label alone."
  []
  ;; `|| exit 0` is the orphan guard: once our end of the pipe is gone the echo
  ;; fails with EPIPE and the producer exits instead of looping forever on
  ;; broken-pipe errors. It covers the exits a shutdown hook does not -- and on
  ;; this jolt the hook does not run on SIGINT at all.
  (str "i=0; while :; do i=$((i+1)); "
       (if (= platform :linux) "read -r line < /proc/stat" "line=cpu")
       "; echo \"$i $line\" || exit 0; sleep 0.02; done"))

;; --------------------------------------------------------------------- macOS

;; Bound lazily: the Mach symbols exist only on macOS, and nothing on another
;; platform ever forces these.
(def ^:private mac
  (delay
    {:host-self       (ffi/foreign-fn "mach_host_self" [] :uint32)
     :processor-info  (ffi/foreign-fn "host_processor_info"
                                      [:uint32 :int :pointer :pointer :pointer] :int)
     :statistics64    (ffi/foreign-fn "host_statistics64"
                                      [:uint32 :int :pointer :pointer] :int)
     :vm-deallocate   (ffi/foreign-fn "vm_deallocate" [:uint32 :uptr :uptr] :int)
     :sysctlbyname    (ffi/foreign-fn "sysctlbyname"
                                      [:string :pointer :pointer :pointer :size_t] :int)}))

(defn- mac-call [k & args] (apply (get @mac k) args))

;; Every mach_host_self() call adds a send right to the port, so take it once.
(def ^:private host-port (delay (mac-call :host-self)))
(def ^:private task-port
  (delay (ffi/read (ffi/find-symbol "mach_task_self_") :uint32)))

(def ^:private PROCESSOR_CPU_LOAD_INFO 2)
(def ^:private HOST_VM_INFO64 4)
(def ^:private HOST_VM_INFO64_COUNT 38)   ; sizeof(vm_statistics64_data_t) / 4

(defn- mac-cpu-ticks
  "Per-core cumulative [user system idle nice] ticks."
  []
  (ffi/with-out [ncpu :uint32]
    (ffi/with-out [info :pointer]
      (ffi/with-out [cnt :uint32]
        (when-not (zero? (mac-call :processor-info @host-port PROCESSOR_CPU_LOAD_INFO ncpu info cnt))
          (throw (ex-info "host_processor_info failed" {})))
        (let [n (ffi/read ncpu :uint32)
              p (ffi/read info :pointer)]
          (try
            (vec (for [c (range n)]
                   (mapv #(ffi/read p :uint32 (* 4 (+ (* 4 c) %))) (range 4))))
            (finally
              (mac-call :vm-deallocate @task-port p (* 4 (ffi/read cnt :uint32))))))))))

(defn- mac-stat-text []
  (let [cores (mac-cpu-ticks)
        line  (fn [label [user system idle nice]]
                (str label " " user " " nice " " system " " idle " 0 0 0 0"))]
    (str/join "\n" (cons (line "cpu" (apply mapv + cores))
                         (map-indexed #(line (str "cpu" %1) %2) cores)))))

(defn- sysctl-u64 [name]
  (ffi/with-out [v :uint64]
    (ffi/with-out [sz :size_t]
      (ffi/write sz :size_t 8)
      (when-not (zero? (mac-call :sysctlbyname name v sz 0 0))
        (throw (ex-info "sysctlbyname failed" {:name name})))
      (ffi/read v :uint64))))

(defn- mac-meminfo-text
  "Available is free + inactive + speculative + purgeable pages: memory the
  kernel hands out without paging anything to disk, the same idea as Linux's
  MemAvailable."
  []
  (let [page  (sysctl-u64 "hw.pagesize")
        total (quot (sysctl-u64 "hw.memsize") 1024)
        [free inactive purgeable speculative]
        (ffi/with-alloc [buf 256]
          (ffi/with-out [cnt :uint32]
            (ffi/write cnt :uint32 HOST_VM_INFO64_COUNT)
            (when-not (zero? (mac-call :statistics64 @host-port HOST_VM_INFO64 buf cnt))
              (throw (ex-info "host_statistics64 failed" {})))
            ;; vm_statistics64: free_count @0, inactive_count @8,
            ;; purgeable_count @88, speculative_count @92
            (mapv #(ffi/read buf :uint32 %) [0 8 88 92])))
        kb    #(quot (* % page) 1024)]
    (str "MemTotal: " total " kB\n"
         "MemFree: " (kb free) " kB\n"
         "MemAvailable: " (kb (+ free inactive purgeable speculative)) " kB\n"
         "Cached: " (kb inactive) " kB\n")))

(defn netstat->netdev
  "`netstat -ibn` text -> /proc/net/dev text. Only the `<Link#n>` rows carry
  one count per interface; the rest repeat it per address. Their last seven
  columns are Ipkts Ierrs Ibytes Opkts Oerrs Obytes Coll (the address column
  before them is blank for interfaces without a MAC)."
  [text]
  (str "Inter-|   Receive\n face |bytes\n"
       (str/join "\n"
                 (for [line (str/split-lines text)
                       :let [tokens (str/split (str/trim line) #"\s+")]
                       :when (and (> (count tokens) 7)
                                  (str/starts-with? (nth tokens 2) "<Link#"))
                       :let [[_ _ ibytes _ _ obytes] (take-last 7 tokens)]]
                   (str (first tokens) ": " ibytes " 0 0 0 0 0 0 0 " obytes " 0")))))

(defn- mac-netdev-text []
  (netstat->netdev (:out (p/shell {:out :string :err :string} "netstat" "-ibn"))))

;; ------------------------------------------------------------------- public

(defn stat-text
  "CPU counters as /proc/stat text."
  []
  (if (= platform :macos) (mac-stat-text) (slurp "/proc/stat")))

(defn meminfo-text
  "Memory as /proc/meminfo text."
  []
  (if (= platform :macos) (mac-meminfo-text) (slurp "/proc/meminfo")))

(defn netdev-text
  "Interface byte counters as /proc/net/dev text."
  []
  (if (= platform :macos) (mac-netdev-text) (slurp "/proc/net/dev")))
