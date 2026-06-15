(ns app.core
  (:require [malli.core :as m]))

;; metosin/malli running on jolt — schema validation.
(def User
  [:map
   [:name :string]
   [:age [:int {:min 0 :max 130}]]
   [:roles {:optional true} [:vector :keyword]]])

(defn -main [& _]
  (println "int? 5            =>" (m/validate int? 5))
  (println "[:enum :a :b] :a  =>" (m/validate [:enum :a :b] :a))
  (println "[:vector :int]    =>" (m/validate [:vector :int] [1 2 3]))
  (println "User (valid)      =>" (m/validate User {:name "Ada" :age 40 :roles [:dev]}))
  (println "User (no roles)   =>" (m/validate User {:name "Ada" :age 40}))
  (println "User (bad age)    =>" (m/validate User {:name "Ada" :age -1}))
  (println "User (missing)    =>" (m/validate User {:name "Ada"})))
