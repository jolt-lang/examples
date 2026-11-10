(ns app.host-test
  "The macOS translation into /proc text, checked against captured output so
  it runs on any machine."
  (:require [clojure.test :refer [deftest is testing]]
            [app.host :as host]
            [app.metrics :as metrics]))

(def netstat-sample
  "Name       Mtu   Network       Address            Ipkts Ierrs     Ibytes    Opkts Oerrs     Obytes  Coll
lo0        16384 <Link#1>                      96216834     0 139045385263 96216834     0 139045385263     0
lo0        16384 127           127.0.0.1       96216834     - 139045385263 96216834     - 139045385263     -
en0        1500  <Link#11>   aa:bb:cc:dd:ee:ff 512345     0  726426868344   401234     0  114141857896     0
en0        1500  192.168.1     192.168.1.20    512345     -  726426868344   401234     -  114141857896     -
")

(deftest netstat-becomes-netdev
  (let [dev (metrics/parse-netdev (host/netstat->netdev netstat-sample))]
    (testing "one entry per interface, from the link rows"
      (is (= #{"lo0" "en0"} (set (keys dev)))))
    (testing "byte columns land where parse-netdev reads them"
      (is (= {:rx-bytes 726426868344 :tx-bytes 114141857896} (get dev "en0")))
      (is (= {:rx-bytes 139045385263 :tx-bytes 139045385263} (get dev "lo0"))))))

(deftest macos-loopback-is-loopback
  (let [t0 {"lo0" {:rx-bytes 0 :tx-bytes 0} "en0" {:rx-bytes 0 :tx-bytes 0}}
        t1 {"lo0" {:rx-bytes 5000 :tx-bytes 5000} "en0" {:rx-bytes 1000 :tx-bytes 0}}]
    (is (= 2000.0 (:rx-bps (metrics/net-rates t0 t1 500))))))

(deftest the-host-answers-in-proc-formats
  (when (#{:linux :macos} host/platform)
    (let [stat (metrics/parse-stat (host/stat-text))
          mem  (metrics/parse-meminfo (host/meminfo-text))]
      (is (pos? (:total (:aggregate stat))))
      (is (seq (:cores stat)))
      (is (< 0 (:used-kb mem) (:total-kb mem)))
      (is (map? (metrics/parse-netdev (host/netdev-text)))))))

