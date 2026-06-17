(ns app.commonmark-test
  "A tiny self-contained test runner (no external test lib, so it runs on Jolt).
  Each case is [description input expected-html]. Run with `jolt run -m
  app.commonmark-test` (or via the deps.edn `test` task)."
  (:require [app.commonmark :as md]
            [clojure.string :as str]))

(def cases
  [;; --- headings ---
   ["atx h1"            "# Title"                "<h1>Title</h1>\n"]
   ["atx h3"            "### Three"              "<h3>Three</h3>\n"]
   ["atx closing #"     "## Title ##"            "<h2>Title</h2>\n"]
   ["setext h1"         "Title\n====="           "<h1>Title</h1>\n"]
   ["setext h2"         "Title\n-----"           "<h2>Title</h2>\n"]

   ;; --- paragraphs & breaks ---
   ["paragraph"         "Hello world."           "<p>Hello world.</p>\n"]
   ["soft break"        "a\nb"                   "<p>a b</p>\n"]
   ["hard break spaces" "a  \nb"                 "<p>a<br />\nb</p>\n"]
   ["thematic break"    "---"                    "<hr />\n"]
   ["two paragraphs"    "one\n\ntwo"             "<p>one</p>\n<p>two</p>\n"]

   ;; --- emphasis ---
   ["emphasis *"        "*em*"                   "<p><em>em</em></p>\n"]
   ["emphasis _"        "_em_"                   "<p><em>em</em></p>\n"]
   ["strong **"         "**strong**"             "<p><strong>strong</strong></p>\n"]
   ["strong __"         "__strong__"             "<p><strong>strong</strong></p>\n"]
   ["both ***"          "***x***"                "<p><em><strong>x</strong></em></p>\n"]
   ["nested"            "**a *b* c**"            "<p><strong>a <em>b</em> c</strong></p>\n"]
   ["intraword _"       "a_b_c"                  "<p>a_b_c</p>\n"]
   ["intraword *"       "a*b*c"                  "<p>a<em>b</em>c</p>\n"]

   ;; --- code ---
   ["code span"         "`x = 1`"                "<p><code>x = 1</code></p>\n"]
   ["code span escapes" "`a < b`"                "<p><code>a &lt; b</code></p>\n"]
   ["fenced code"       "```\nline\n```"         "<pre><code>line\n</code></pre>\n"]
   ["fenced lang"       "```clj\n(+ 1 2)\n```"   "<pre><code class=\"language-clj\">(+ 1 2)\n</code></pre>\n"]
   ["indented code"     "    code"               "<pre><code>code\n</code></pre>\n"]

   ;; --- links & images ---
   ["link"              "[t](http://e.com)"      "<p><a href=\"http://e.com\">t</a></p>\n"]
   ["link title"        "[t](/u \"hi\")"         "<p><a href=\"/u\" title=\"hi\">t</a></p>\n"]
   ["link emphasis"     "[*t*](/u)"              "<p><a href=\"/u\"><em>t</em></a></p>\n"]
   ["image"             "![alt](/i.png)"         "<p><img src=\"/i.png\" alt=\"alt\" /></p>\n"]
   ["autolink"          "<http://e.com>"         "<p><a href=\"http://e.com\">http://e.com</a></p>\n"]

   ;; --- escaping ---
   ["escape amp"        "a & b"                  "<p>a &amp; b</p>\n"]
   ["escape lt"         "a < b"                  "<p>a &lt; b</p>\n"]
   ["backslash escape"  "\\*not em\\*"           "<p>*not em*</p>\n"]

   ;; --- blockquote ---
   ["blockquote"        "> quoted"               "<blockquote>\n<p>quoted</p>\n</blockquote>\n"]
   ["blockquote multi"  "> a\n> b"               "<blockquote>\n<p>a b</p>\n</blockquote>\n"]

   ;; --- lists ---
   ["tight ul"          "- a\n- b"               "<ul>\n<li>a</li>\n<li>b</li>\n</ul>\n"]
   ["tight ol"          "1. a\n2. b"             "<ol>\n<li>a</li>\n<li>b</li>\n</ol>\n"]
   ["ol start"          "3. a\n4. b"             "<ol start=\"3\">\n<li>a</li>\n<li>b</li>\n</ol>\n"]
   ["loose ul"          "- a\n\n- b"             "<ul>\n<li>\n<p>a</p>\n</li>\n<li>\n<p>b</p>\n</li>\n</ul>\n"]
   ["nested list"       "- a\n  - b"             "<ul>\n<li>a\n<ul>\n<li>b</li>\n</ul></li>\n</ul>\n"]])

(defn run []
  (let [results (for [[desc in expected] cases]
                  (let [actual (md/md->html in)]
                    [desc (= actual expected) in expected actual]))
        passed (count (filter second results))
        failed (remove second results)]
    (doseq [[desc _ in expected actual] failed]
      (println "FAIL:" desc)
      (println "  input:   " (pr-str in))
      (println "  expected:" (pr-str expected))
      (println "  actual:  " (pr-str actual)))
    (println (str "\n" passed "/" (count cases) " passed"))
    (when (seq failed) (System/exit 1))))

(defn -main [& _] (run))
