(ns app.db
  "Guestbook storage: SQLite through clojure.jdbc, which the jolt-lang/db library
  runs on a java.sql shim over the system libsqlite3 via FFI. Queries are written
  as honeysql data."
  ;; db.jdbc registers the java.sql shim clojure.jdbc compiles against and points
  ;; connection construction at the native driver, so it has to load before
  ;; jdbc.core.
  (:require [db.jdbc]
            [jdbc.core :as jdbc]
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
  ;; clojure.jdbc has no last-insert-id: the generated key comes back from the
  ;; insert itself when :returning is asked for.
  (:id (first (jdbc/insert! conn :greetings {:name name} {:returning true}))))

(defn recent-greetings [conn n]
  (jdbc/fetch conn (sql/format {:select [:name :created-at]
                                :from [:greetings]
                                :order-by [[:id :desc]]
                                :limit n})))

(defn greeting-count [conn]
  (:n (jdbc/fetch-one conn (sql/format {:select [[[:count :*] :n]]
                                        :from [:greetings]}))))
