(ns greeter.core
  (:require [config.core :as config]
            [selmer.parser :as selmer]))

(def template
  (str "Hello {{name|upper}}!\n"
       "{% if motd %}motd: {{motd}}\n{% endif %}"
       "{% for f in features %}  - {{f}}\n{% endfor %}"))

(defn greeting
  "Render the greeting template for a config map."
  [{:keys [name motd features] :or {name "world"}}]
  (selmer/render template {:name name :motd motd :features features}))

(defn -main [& args]
  ;; The compiled binary bakes namespace state at build time, so re-read the
  ;; runtime environment + config.edn before rendering. A no-op-ish refresh
  ;; when running from source.
  (config/reload-env)
  (let [cfg (cond-> config/env
              (first args) (assoc :name (first args)))]
    (print (greeting cfg))
    (flush)))
