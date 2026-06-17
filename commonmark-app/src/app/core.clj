(ns app.core
  (:require [app.commonmark :as md]))

(def sample
  (str "# CommonMark on Jolt\n\n"
       "A dependency-free parser. This is **bold**, *italic*, and `inline code`.\n\n"
       "## Lists\n\n"
       "- alpha\n"
       "- beta\n"
       "  - nested\n"
       "- gamma\n\n"
       "1. first\n"
       "2. second\n\n"
       "## Code\n\n"
       "```clojure\n(defn square [x] (* x x))\n```\n\n"
       "## Links & quotes\n\n"
       "See the [Jolt repo](https://github.com/jolt-lang/jolt).\n\n"
       "> Markdown rendered with no JVM and no external deps.\n\n"
       "---\n"))

(defn -main [& _]
  (println (md/md->html sample)))
