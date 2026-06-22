(ns app.db
  "Guestbook storage on jolt's built-in SQLite (jolt.sqlite, over the system
  libsqlite3 via FFI)."
  (:require [jolt.sqlite :as db]))

(defn connect
  "Open the guestbook database and ensure the schema. db-path comes from config
  (:database-url), e.g. \"guestbook.sqlite3\" or \":memory:\"."
  [db-path]
  (let [conn (db/open db-path)]
    (db/execute! conn
      (str "create table if not exists greetings ("
           "  id integer primary key,"
           "  name text not null,"
           "  created_at text default CURRENT_TIMESTAMP)"))
    conn))

(defn add-greeting! [conn name]
  (db/execute! conn "insert into greetings (name) values (?)" [name])
  (db/last-insert-id conn))

(defn recent-greetings [conn n]
  (db/query conn "select name, created_at as \"created-at\" from greetings order by id desc limit ?" [n]))

(defn greeting-count [conn]
  (:n (first (db/query conn "select count(*) as n from greetings"))))
