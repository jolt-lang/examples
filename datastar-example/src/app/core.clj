(ns app.core
  "A datastar demo on jolt: a counter and a greeting list. All page state
  lives in one glimmer ratom; the datastar middleware's SSE stream re-renders
  the page fragment whenever it changes, so the UI updates without a reload."
  (:require [jolt.datastar.core :as ds]
            [glimmer.ratom :as ratom]
            [ring-chez.adapter :as adapter]
            [clojure.edn :as edn]
            [hiccup.core :as h]))

(defonce state
  (ratom/atom {:count     0
               :name      ""
               :greetings []}))

(defn- fragment
  "The live region, as hiccup: patched by the SSE stream whenever `state`
  changes."
  []
  [:div
   [:div.row
    [:button {"data-on:click" "@get('/count/inc')"} "+"]
    [:span.count {:data-text "$count"} (:count @state)]
    [:button {"data-on:click" "@get('/count/dec')"} "−"]]
   [:div.row
    [:input {:type "text" :data-bind "name" :placeholder "Name"}]
    [:button {"data-on:click" "@get('/greet')"} "Greet"]]
   [:ul (for [g (:greetings @state)] [:li g])]])

(defn- page
  "The full page. init-opts seeds the signals (count/name/greetings plus the
  per-tab id) and opens the SSE stream on #app."
  []
  (let [opts (ds/init-opts {:selector "#app"
                            :signals {:count 0 :name "" :greetings []}})]
    (str "<!DOCTYPE html>"
         (h/html
          [:html
           [:head
            [:meta {:charset "utf-8"}]
            [:title "datastar + jolt"]
            [:script {:type "module" :src "/js/datastar.js"}]
            [:style "body{font-family:system-ui,sans-serif;margin:2rem}"
             ".row{display:flex;gap:.5rem;align-items:center;margin-bottom:1rem}"
             "button{font-size:1.1rem;padding:.3rem .7rem}"
             ".count{font-size:1.3rem;min-width:3ch;text-align:center}"
             "li{margin-bottom:.3rem}"]]
           [:body
            [:h1 "datastar + jolt"]
            [:div#app opts (fragment)]]]))))

(defn app
  "Ring handler. The SSE request re-renders the fragment; the action routes
  mutate the atom (which the SSE stream picks up) and patch the client's
  signals with the new state."
  [{:keys [uri jolt.datastar/sse-request jolt.datastar/signals] :as req}]
  (cond
    (and sse-request (= uri "/")) {:status 200 :body (h/html (fragment))}
    (= uri "/")                   {:status 200 :body (page)}
    (= uri "/count/inc")          (ds/patch-signals (swap! state update :count inc))
    (= uri "/count/dec")          (ds/patch-signals (swap! state update :count dec))
    (= uri "/greet")
    (do (swap! state (fn [s] (-> s
                                 (update :greetings conj (:name signals))
                                 (assoc :name ""))))
        (ds/patch-signals (select-keys @state [:name :greetings])))
    (= uri "/js/datastar.js")
    {:status 200
     :headers {"Content-Type" "application/javascript"}
     :body    (slurp "resources/public/js/datastar.js")}
    :else {:status 404 :body "not found"}))

(defn -main [& _]
  (jolt.host/block-sigint)
  (let [handler (ds/wrap-datastar app {:rate-limit-ms 15})
        port    (or (some-> "config.edn" slurp edn/read-string :port) 3000)
        server  (adapter/run-server handler {:port port})]
    (println "datastar demo on http://localhost:" port)
    (jolt.host/add-shutdown-hook #(adapter/stop-server server))
    (jolt.host/park-until-interrupt)
    (adapter/stop-server server)))
