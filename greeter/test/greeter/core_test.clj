(ns greeter.core-test
  (:require [greeter.core :as core]))

(def failures (atom 0))

(defn check [label expected actual]
  (if (= expected actual)
    (println "  ok  " label)
    (do (swap! failures inc)
        (println "  FAIL" label "— expected" (pr-str expected) "got" (pr-str actual)))))

(defn -main [& _]
  (println "greeter.core/greeting")
  (check "renders the name through the upper filter"
         "Hello JOLT!\n"
         (core/greeting {:name "jolt"}))
  (check "defaults the name to world"
         "Hello WORLD!\n"
         (core/greeting {}))
  (check "renders motd and features when present"
         "Hello JOLT!\nmotd: hi\n  - a\n  - b\n"
         (core/greeting {:name "jolt" :motd "hi" :features ["a" "b"]}))
  (if (pos? @failures)
    (do (println @failures "failing check(s)")
        ;; no JVM here — exit through the janet host bridge
        (janet.os/exit 1))
    (println "all checks passed")))
