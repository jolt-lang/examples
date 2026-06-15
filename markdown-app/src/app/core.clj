(ns app.core
  (:require [markdown.core :as md]))

;; A small document exercising the features markdown-clj renders on jolt:
;; headings, inline emphasis/code, lists, tables, links and strikethrough.
(def sample
  (str "# Markdown on Jolt\n\n"
       "This is **bold**, *italic*, and `inline code`.\n\n"
       "A [link](https://example.com) and some ~~struck~~ text.\n\n"
       "- alpha\n- beta\n- gamma\n\n"
       "| name | role |\n|------|------|\n| ada  | dev  |\n\n"
       "> a closing blockquote\n"))

(defn -main [& _]
  (println (md/md-to-html-string sample)))
