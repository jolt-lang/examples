(ns app.diagram
  "The event graph, drawn from the schema itself.

  Domino's schema is data, so the picture of the application's logic does not
  have to be maintained by hand: the nodes are the model ids and the event and
  effect ids, the edges are each event's declared :inputs and :outputs, and
  the columns are longest-path depth. Everything here is pure -- it takes a
  schema and returns geometry -- so the UI just renders what it is given."
  (:require [clojure.string :as str]))

(defn graph
  "Schema -> {:nodes {id node} :edges #{[from to]}}."
  [{:keys [events effects]}]
  (let [handlers (concat (map #(assoc % :kind :event) events)
                         (map #(assoc % :kind :effect) effects))]
    (reduce
     (fn [g {:keys [id kind inputs outputs]}]
       (let [hid (keyword (str (name (or id :anonymous)) "!"))]
         (as-> g g
           (assoc-in g [:nodes hid] {:id hid :kind kind :label (name (or id :anonymous))})
           (reduce (fn [g in]
                     (-> g
                         (update :nodes update in #(or % {:id in :kind :path :label (name in)}))
                         (update :edges (fnil conj #{}) [in hid])))
                   g inputs)
           (reduce (fn [g out]
                     (-> g
                         (update :nodes update out #(or % {:id out :kind :path :label (name out)}))
                         (update :edges (fnil conj #{}) [hid out])))
                   g outputs))))
     {:nodes {} :edges #{}}
     handlers)))

(defn depths
  "Longest-path depth per node. Domino event graphs are acyclic by
  construction, but the iteration is bounded anyway, so a malformed schema
  draws a partial picture rather than hanging."
  [{:keys [nodes edges]}]
  (let [preds (reduce (fn [m [from to]] (update m to (fnil conj #{}) from)) {} edges)]
    (loop [depth (zipmap (keys nodes) (repeat 0))
           pass  0]
      (let [next-depth (reduce (fn [d id]
                                 (assoc d id (if-let [ps (seq (preds id))]
                                               (inc (apply max (map #(get d % 0) ps)))
                                               0)))
                               depth (keys nodes))]
        (if (or (= next-depth depth) (> pass (count nodes)))
          next-depth
          (recur next-depth (inc pass)))))))

(def ^:private col-width 165)
(def ^:private row-height 46)
(def ^:private node-height 28)
(def ^:private margin 16)

(defn- node-width [label] (+ 24 (* 7.2 (count label))))

(defn layout
  "Schema -> everything a renderer needs: positioned nodes and edge endpoints."
  [schema]
  (let [{:keys [nodes edges] :as g} (graph schema)
        depth   (depths g)
        columns (->> (vals nodes)
                     (group-by #(get depth (:id %) 0))
                     (into (sorted-map)))
        placed  (into {}
                      (for [[col members] columns
                            [row node]    (map-indexed vector (sort-by :label members))]
                        [(:id node)
                         (assoc node
                                :x (+ margin (* col col-width))
                                :y (+ margin (* row row-height))
                                :w (node-width (:label node))
                                :h node-height)]))
        width   (+ margin (apply max (map #(+ (:x %) (:w %)) (vals placed))))
        height  (+ margin (apply max (map #(+ (:y %) (:h %)) (vals placed))))]
    {:nodes (vec (sort-by (juxt :x :y) (vals placed)))
     :edges (vec (for [[from to] edges
                       :let [a (placed from) b (placed to)]
                       :when (and a b)]
                   {:x1 (+ (:x a) (:w a)) :y1 (+ (:y a) (/ (:h a) 2))
                    :x2 (:x b)            :y2 (+ (:y b) (/ (:h b) 2))
                    :from from :to to}))
     :width width
     :height height}))

(defn mermaid
  "The same graph as mermaid source, for rendering elsewhere."
  [schema]
  (let [{:keys [edges]} (graph schema)]
    (str/join "\n"
              (cons "stateDiagram-v2"
                    (for [[from to] (sort edges)]
                      (str "  " (str/replace (name from) #"[^A-Za-z0-9_]" "_")
                           " --> "
                           (str/replace (name to) #"[^A-Za-z0-9_]" "_")))))))
