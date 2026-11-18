(ns app.metrics-test
  "Parser tests against captured fixture text: no /proc, no machine dependence."
  (:require [clojure.test :refer [deftest is testing]]
            [app.metrics :as metrics]))

(def stat-t0
  "cpu  1000 10 500 8000 100 0 20 0 0 0
cpu0 500 5 250 4000 50 0 10 0 0 0
cpu1 500 5 250 4000 50 0 10 0 0 0
intr 12345 0 0
ctxt 999
")

;; cpu0 burns 100 busy jiffies against 100 idle; cpu1 is idle for the interval.
(def stat-t1
  "cpu  1100 10 500 8100 100 0 20 0 0 0
cpu0 600 5 250 4100 50 0 10 0 0 0
cpu1 500 5 250 4000 50 0 10 0 0 0
intr 12999 0 0
ctxt 1500
")

(deftest parse-stat-splits-aggregate-from-cores
  (let [{:keys [aggregate cores]} (metrics/parse-stat stat-t0)]
    (is (= "cpu" (:id aggregate)))
    (is (= ["cpu0" "cpu1"] (mapv :id cores)))
    (testing "busy excludes idle and iowait"
      (is (= 1530 (:busy aggregate)))
      (is (= 9630 (:total aggregate))))
    (testing "the digit in a cpuN label is not read as a field"
      (is (= 765 (:busy (first cores)))))))

(deftest cpu-load-needs-two-snapshots
  (let [t0 (metrics/parse-stat stat-t0)
        t1 (metrics/parse-stat stat-t1)]
    (testing "the first snapshot is a baseline, not a measurement"
      (is (= 0.0 (:cpu (metrics/cpu-load nil t0))))
      (is (= [0.0 0.0] (:cores (metrics/cpu-load nil t0)))))
    (testing "busy delta over total delta"
      ;; aggregate: 100 busy jiffies of 200 elapsed
      (is (= 0.5 (:cpu (metrics/cpu-load t0 t1))))
      ;; cpu0 did all of it, cpu1 none
      (is (= [0.5 0.0] (:cores (metrics/cpu-load t0 t1)))))
    (testing "a core that appears mid-run reads 0 rather than throwing"
      (is (= [0.5 0.0 0.0]
             (:cores (metrics/cpu-load t0 (metrics/parse-stat (str stat-t1 "cpu2 1 1 1 1 1 1 1 0 0 0\n")))))))))

(def meminfo
  "MemTotal:        8000000 kB
MemFree:          500000 kB
MemAvailable:    2000000 kB
Buffers:           28228 kB
Cached:          1500000 kB
SwapTotal:       2000000 kB
")

(deftest meminfo-uses-available-not-free
  (let [m (metrics/parse-meminfo meminfo)]
    (is (= 8000000 (:total-kb m)))
    (is (= 6000000 (:used-kb m)))
    (is (= 0.75 (:used-frac m)))
    (testing "page cache is not counted as pressure"
      (is (= 1500000 (:cached-kb m))))))

(def netdev-t0
  "Inter-|   Receive                                                |  Transmit
 face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
    lo:    1000      10    0    0    0     0          0         0     1000      10    0    0    0     0       0          0
 eth0:  100000     100    0    0    0     0          0         0    50000      50    0    0    0     0       0          0
")

(def netdev-t1
  "Inter-|   Receive                                                |  Transmit
 face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
    lo:    2000      20    0    0    0     0          0         0     2000      20    0    0    0     0       0          0
 eth0:  110000     110    0    0    0     0          0         0    55000      55    0    0    0     0       0          0
")

(deftest netdev-rates-are-per-second-and-skip-loopback
  (let [t0 (metrics/parse-netdev netdev-t0)
        t1 (metrics/parse-netdev netdev-t1)]
    (is (= {:rx-bytes 100000 :tx-bytes 50000} (get t0 "eth0")))
    (let [{:keys [rx-bps tx-bps interfaces]} (metrics/net-rates t0 t1 500)]
      (testing "10000 bytes in 500ms is 20000 B/s"
        (is (= 20000.0 rx-bps))
        (is (= 10000.0 tx-bps)))
      (testing "lo is measured but kept out of the total"
        (is (= 2000.0 (:rx-bps (get interfaces "lo"))))))
    (testing "counter resets read as 0 rather than negative"
      (is (= 0.0 (:rx-bps (metrics/net-rates t1 t0 500)))))))

(deftest vmstat-lines-parse-and-headers-do-not
  (is (nil? (metrics/parse-vmstat "procs -----------memory---------- ---swap--")))
  (is (nil? (metrics/parse-vmstat " r  b   swpd   free   buff  cache   si   so")))
  (is (nil? (metrics/parse-vmstat "")))
  (let [row (metrics/parse-vmstat " 1  0 982240 5378260  28228 975312    0    0     0     0 163  400  2  1 97  0  0")]
    (is (= 982240 (:swpd row)))
    (is (= 97 (:id row)))
    (let [s (metrics/vmstat-sample row)]
      (is (= 0.03 (Double/parseDouble (format "%.2f" (:cpu s)))))
      (is (= 5378260 (:free-kb s))))))

(deftest loadavg
  (is (= {:1m 0.67 :5m 1.1 :15m 1.35}
         (metrics/parse-loadavg "0.67 1.10 1.35 2/1234 5678\n"))))

;; ---------------------------------------------------------------- macOS

(deftest cpu-ticks-take-the-parse-stat-shape
  ;; host_processor_info: per core [user system idle nice], cumulative
  (let [t0 (metrics/cpu-ticks->stat [[100 50 800 0] [10 10 980 0]])
        t1 (metrics/cpu-ticks->stat [[200 50 900 0] [10 10 1080 0]])]
    (is (= {:id "cpu" :busy 170 :iowait 0 :total 1950} (:aggregate t0)))
    (is (= ["cpu0" "cpu1"] (map :id (:cores t0))))
    (is (= {:id "cpu0" :busy 150 :iowait 0 :total 950} (first (:cores t0))))
    (testing "and feed cpu-load like /proc/stat snapshots"
      (let [{:keys [cpu cores io-wait]} (metrics/cpu-load t0 t1)]
        (is (= 0.5 (first cores)))
        (is (= 0.0 (second cores)))
        (is (= (/ 100.0 300) cpu))
        (is (= 0.0 io-wait))))))

(def vm-stat-text
  "Mach Virtual Memory Statistics: (page size of 16384 bytes)
Pages free:                               1000.
Pages active:                             5000.
Pages inactive:                           2000.
Pages speculative:                         500.
Pages throttled:                             0.
Pages wired down:                         1500.
Pages purgeable:                           100.
\"Translation faults\":               52490319762.
File-backed pages:                        2200.
Pages occupied by compressor:              300.
")

(deftest vm-stat-reports-like-meminfo
  ;; available is free + inactive + speculative pages, as kB
  (let [m (metrics/parse-vm-stat vm-stat-text (* 16 1024 1024 10))]
    (is (= 163840 (:total-kb m)))
    (is (= 16000 (:free-kb m)))
    (is (= 56000 (:available-kb m)))
    (is (= 35200 (:cached-kb m)))
    (is (= 107840 (:used-kb m)))
    (is (= (/ 107840.0 163840) (:used-frac m)))))

(def netstat-text
  "Name       Mtu   Network       Address            Ipkts Ierrs     Ibytes    Opkts Oerrs     Obytes  Coll
lo0        16384 <Link#1>                      137822315     0 145173407503 137822315     0 145173407503     0
lo0        16384 127           127.0.0.1       137822315     - 145173407503 137822315     - 145173407503     -
gif0*      1280  <Link#2>                             0     0          0        0     0          0     0
en0        1500  <Link#11>   a2:10:4c:69:74:c0  5000     0    9000000     4000     0    1200000     0
en0        1500  192.168.1     192.168.1.20      5000     -    9000000     4000     -    1200000     -
")

(deftest netstat-reads-link-rows-only
  (is (= {"lo0"  {:rx-bytes 145173407503 :tx-bytes 145173407503}
          "gif0" {:rx-bytes 0 :tx-bytes 0}
          "en0"  {:rx-bytes 9000000 :tx-bytes 1200000}}
         (metrics/parse-netstat-ib netstat-text))))

(deftest net-rates-skip-darwin-loopback
  (let [prev {"lo0" {:rx-bytes 0 :tx-bytes 0} "en0" {:rx-bytes 0 :tx-bytes 0}}
        cur  {"lo0" {:rx-bytes 5000 :tx-bytes 5000} "en0" {:rx-bytes 1000 :tx-bytes 500}}
        {:keys [rx-bps tx-bps]} (metrics/net-rates prev cur 1000)]
    (is (= 1000.0 rx-bps))
    (is (= 500.0 tx-bps))))
