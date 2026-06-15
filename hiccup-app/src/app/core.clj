(ns app.core
  (:require [hiccup.core :as h]))

(defn page []
  (h/html
    [:html
     [:head [:title "Hiccup on Jolt"]]
     [:body
      [:h1 "Hello from hiccup"]
      [:p {:class "lead"} "Rendered with weavejester/hiccup."]
      [:ul (for [x ["alpha" "beta" "gamma"]] [:li x])]]]))

(defn -main [& _]
  (println (page)))
