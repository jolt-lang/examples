(ns app.db
  "Guestbook storage: sqlite through jolt-lang/db's jdbc.core, queries
  written as honeysql data."
  (:require [jdbc.core :as jdbc]
            [honey.sql :as sql]))

(defn connect
  "Open the guestbook database and ensure the schema. db-path comes from
  config (:database-url), e.g. \"guestbook.sqlite3\" or \":memory:\"."
  [db-path]
  (let [conn (jdbc/connection (str "sqlite:" db-path))]
    (jdbc/execute! conn
      (sql/format {:create-table [:greetings :if-not-exists]
                   :with-columns [[:id :integer :primary-key]
                                  [:name :text [:not nil]]
                                  [:created-at :text
                                   [:default [:raw "CURRENT_TIMESTAMP"]]]]}))
    conn))

(defn add-greeting! [conn name]
  (jdbc/execute! conn (sql/format {:insert-into :greetings
                                   :values [{:name name}]}))
  (jdbc/last-insert-id conn))

(defn recent-greetings [conn n]
  (jdbc/fetch conn (sql/format {:select [:name :created-at]
                                :from [:greetings]
                                :order-by [[:id :desc]]
                                :limit n})))

(defn greeting-count [conn]
  (:n (jdbc/fetch-one conn (sql/format {:select [[[:count :*] :n]]
                                        :from [:greetings]}))))
