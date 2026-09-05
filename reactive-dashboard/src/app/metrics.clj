(ns app.metrics
  "Pure parsers for the raw text the ingestion lanes carry.

  Nothing in here does IO. Every fn takes text (or two snapshots of it) and
  returns data, so the ingestion layer can be tested against fixtures rather
  than against the machine it runs on."
  (:require [clojure.string :as str]))

(defn- nums
  "Every unsigned integer in `s`, in order, as longs."
  [s]
  (mapv parse-long (re-seq #"\d+" s)))

(defn- safe-div [a b]
  (if (and b (pos? b)) (double (/ a b)) 0.0))

;; ---------------------------------------------------------------- /proc/stat

(defn- cpu-line
  "One `cpuN user nice system idle iowait irq softirq steal ...` line as
  {:id :busy :total}. Busy is everything that is not idle or iowait."
  [line]
  (let [[label fields] (str/split (str/trim line) #"\s+" 2)
        [user nice system idle iowait irq softirq steal] (nums fields)
        idle-all (+ (or idle 0) (or iowait 0))
        busy     (+ (or user 0) (or nice 0) (or system 0)
                    (or irq 0) (or softirq 0) (or steal 0))]
    {:id label :busy busy :iowait (or iowait 0) :total (+ busy idle-all)}))

(defn parse-stat
  "/proc/stat text -> {:aggregate {:busy :total} :cores [{:id :busy :total} ...]}.

  The counters are cumulative since boot, so a single snapshot says nothing on
  its own -- `cpu-load` turns two of them into a fraction."
  [text]
  (let [lines (->> (str/split-lines text)
                   (filter #(re-matches #"cpu\d*\s.*" %))
                   (map cpu-line))]
    {:aggregate (first (filter #(= "cpu" (:id %)) lines))
     :cores     (vec (remove #(= "cpu" (:id %)) lines))}))

(defn- delta-frac [prev cur]
  (safe-div (- (:busy cur) (:busy prev)) (- (:total cur) (:total prev))))

(defn cpu-load
  "Two `parse-stat` snapshots -> {:cpu 0..1 :cores [0..1 ...] :io-wait 0..1}.
  With no previous snapshot every reading is 0.0: the first sample of a counter
  is a baseline, not a measurement."
  [prev cur]
  (if-not prev
    {:cpu 0.0 :io-wait 0.0 :cores (mapv (constantly 0.0) (:cores cur))}
    {:cpu   (delta-frac (:aggregate prev) (:aggregate cur))
     :io-wait (safe-div (- (:iowait (:aggregate cur)) (:iowait (:aggregate prev)))
                        (- (:total (:aggregate cur)) (:total (:aggregate prev))))
     :cores (mapv (fn [{:keys [id] :as core}]
                    (if-let [p (first (filter #(= id (:id %)) (:cores prev)))]
                      (delta-frac p core)
                      0.0))
                  (:cores cur))}))

;; ------------------------------------------------------------- /proc/meminfo

(defn parse-meminfo
  "/proc/meminfo text -> the few fields the dashboard shows, in kB, plus the
  used fraction. Used is total minus available (the number `free -h` reports),
  not total minus free -- page cache is not memory pressure."
  [text]
  (let [kv (into {} (for [line (str/split-lines text)
                          :let [[k v] (str/split line #":\s*")]
                          :when (and k v)]
                      [k (first (nums v))]))
        total     (get kv "MemTotal" 0)
        available (get kv "MemAvailable" (get kv "MemFree" 0))]
    {:total-kb     total
     :free-kb      (get kv "MemFree" 0)
     :available-kb available
     :cached-kb    (get kv "Cached" 0)
     :used-kb      (- total available)
     :used-frac    (safe-div (- total available) total)}))

;; ------------------------------------------------------------ /proc/net/dev

(defn parse-netdev
  "/proc/net/dev text -> {iface {:rx-bytes n :tx-bytes n}}, cumulative."
  [text]
  (into {}
        (for [line (drop 2 (str/split-lines text))
              :let [[iface rest-of] (str/split (str/trim line) #":\s*" 2)]
              :when (and iface rest-of (seq (nums rest-of)))]
          (let [f (nums rest-of)]
            ;; rx: bytes packets errs drop fifo frame compressed multicast
            ;; tx: bytes packets ...  -- so tx bytes is field 8
            [iface {:rx-bytes (nth f 0 0) :tx-bytes (nth f 8 0)}]))))

(defn net-rates
  "Two `parse-netdev` snapshots and the gap between them -> bytes per second,
  per interface plus a total across all of them. Loopback is excluded from the
  total: it is real traffic, but it does not traverse the network."
  [prev cur dt-ms]
  (let [dt (max (or dt-ms 0) 1)
        per (into {}
                  (for [[iface {:keys [rx-bytes tx-bytes]}] cur
                        :let [p (get prev iface)]]
                    [iface {:rx-bps (if p (max 0.0 (* 1000.0 (safe-div (- rx-bytes (:rx-bytes p)) dt))) 0.0)
                            :tx-bps (if p (max 0.0 (* 1000.0 (safe-div (- tx-bytes (:tx-bytes p)) dt))) 0.0)}]))
        wired (remove (fn [[iface _]] (= "lo" iface)) per)]
    {:interfaces per
     :rx-bps (reduce + 0.0 (map (comp :rx-bps val) wired))
     :tx-bps (reduce + 0.0 (map (comp :tx-bps val) wired))}))

;; -------------------------------------------------------------- loadavg

(defn parse-loadavg
  "/proc/loadavg text -> {:1m :5m :15m} as doubles."
  [text]
  (let [[a b c] (map parse-double (take 3 (str/split (str/trim text) #"\s+")))]
    {:1m a :5m b :15m c}))

;; --------------------------------------------------------------- vmstat

(def ^:private vmstat-fields
  [:r :b :swpd :free :buff :cache :si :so :bi :bo :in :cs :us :sy :id :wa :st])

(defn parse-vmstat
  "One `vmstat 1` data line -> a field map. Header lines (`procs -----`, `r  b
  swpd ...`) return nil, so a lane can just keep the non-nil ones."
  [line]
  (let [tokens (remove str/blank? (str/split (str/trim (or line "")) #"\s+"))]
    (when (and (= (count tokens) (count vmstat-fields))
               (every? #(re-matches #"\d+" %) tokens))
      (zipmap vmstat-fields (map parse-long tokens)))))

(defn vmstat-sample
  "A parsed vmstat row as the dashboard's sample shape: cpu busy fraction (100
  minus idle) and free memory."
  [{:keys [us sy wa id] :as row}]
  (when row
    {:cpu     (safe-div (+ us sy) (max 1 (+ us sy wa id)))
     :io-wait (safe-div wa (max 1 (+ us sy wa id)))
     :free-kb (:free row)}))
