(ns app.commonmark
  "A compact, dependency-free Markdown -> HTML renderer covering the common
  CommonMark constructs. Pure Clojure (clojure.string only), so it runs on Jolt
  with no JVM and no external deps.

  Two phases: `parse-blocks` turns lines into a block tree (headings, paragraphs,
  lists, blockquotes, code, thematic breaks); `inline->html` resolves inline
  markup (emphasis, code spans, links, images, autolinks, hard breaks). See the
  README for the subset that is and isn't supported."
  (:require [clojure.string :as str]))

;; ------------------------------------------------------------------
;; HTML escaping
;; ------------------------------------------------------------------

(defn esc
  "Escape text for an HTML body context."
  [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn esc-attr
  "Escape a URL/attribute value (ampersands and double quotes)."
  [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "\"" "&quot;")))

;; ------------------------------------------------------------------
;; Inline parsing
;; ------------------------------------------------------------------

(def ^:private punct (set "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"))

(defn- punct? [c] (and c (contains? punct c)))
(defn- ws? [c] (or (nil? c) (= c \space) (= c \tab) (= c \newline)))

(defn- flanking
  "CommonMark left/right-flanking flags for a delimiter run, given the char
  immediately before and after it (nil at a string boundary counts as space)."
  [before after]
  (let [next-ws (ws? after)  next-pu (punct? after)
        prev-ws (ws? before) prev-pu (punct? before)
        left  (and (not next-ws) (or (not next-pu) prev-ws prev-pu))
        right (and (not prev-ws) (or (not prev-pu) next-ws next-pu))]
    [left right]))

(declare inline->html)

(defn- run-len
  "Length of the run of char `c` in `s` starting at `i`."
  [s i c]
  (loop [j i] (if (and (< j (count s)) (= (get s j) c)) (recur (inc j)) (- j i))))

(defn- scan-code-span
  "Try to read a backtick code span at `i`. Returns [node next-i] or nil."
  [s i]
  (let [n (run-len s i \`)
        open-end (+ i n)]
    (loop [j open-end]
      (cond
        (>= j (count s)) nil                          ; no closing run -> not code
        (= (get s j) \`)
        (let [m (run-len s j \`)]
          (if (= m n)
            (let [raw (str/replace (subs s open-end j) "\n" " ")
                  content (if (and (> (count raw) 1)
                                   (= \space (first raw)) (= \space (last raw))
                                   (not (every? #(= % \space) raw)))
                            (subs raw 1 (dec (count raw)))
                            raw)]
              [{:t :html :v (str "<code>" (esc content) "</code>")} (+ j m)])
            (recur (+ j m))))
        :else (recur (inc j))))))

(def ^:private autolink-uri #"^<[a-zA-Z][a-zA-Z0-9+.-]{1,31}:[^<>\x00-\x20]*>")
(def ^:private autolink-email
  #"^<[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*>")
(def ^:private inline-html
  #"(?s)^</?[a-zA-Z][a-zA-Z0-9-]*(?:\s+[a-zA-Z_:][a-zA-Z0-9_.:-]*(?:\s*=\s*(?:[^\s\"'=<>`]+|'[^']*'|\"[^\"]*\"))?)*\s*/?>|^<!--.*?-->")

(defn- scan-angle
  "Try an autolink or raw inline HTML at a `<`. Returns [node next-i] or nil."
  [s i]
  (let [tail (subs s i)]
    (if-let [m (re-find autolink-uri tail)]
      (let [url (subs m 1 (dec (count m)))]
        [{:t :html :v (str "<a href=\"" (esc-attr url) "\">" (esc url) "</a>")} (+ i (count m))])
      (if-let [m (re-find autolink-email tail)]
        (let [addr (subs m 1 (dec (count m)))]
          [{:t :html :v (str "<a href=\"mailto:" (esc-attr addr) "\">" (esc addr) "</a>")} (+ i (count m))])
        (when-let [m (re-find inline-html tail)]
          [{:t :html :v m} (+ i (count m))])))))

(defn- match-bracket
  "Index of the `]` closing the `[` at `open`, honouring escapes and nesting."
  [s open]
  (loop [j (inc open) depth 1]
    (cond
      (>= j (count s)) nil
      (= (get s j) \\) (recur (+ j 2) depth)
      (= (get s j) \[) (recur (inc j) (inc depth))
      (= (get s j) \]) (if (= depth 1) j (recur (inc j) (dec depth)))
      :else (recur (inc j) depth))))

(defn- skip-ws [s i]
  (loop [j i] (if (and (< j (count s)) (ws? (get s j))) (recur (inc j)) j)))

(defn- read-destination
  "Read a link destination at `i` (past the `(`). Returns [dest next-i] or nil.
  Supports <...> and a bare run with balanced parens."
  [s i]
  (if (and (< i (count s)) (= (get s i) \<))
    (when-let [close (str/index-of s ">" i)]
      [(subs s (inc i) close) (inc close)])
    (loop [j i depth 0]
      (let [c (get s j)]
        (cond
          (or (nil? c) (ws? c)) (when (>= j i) [(subs s i j) j])
          (= c \() (recur (inc j) (inc depth))
          (= c \)) (if (zero? depth) [(subs s i j) j] (recur (inc j) (dec depth)))
          :else (recur (inc j) depth))))))

(defn- read-title
  "Optionally read a quoted link title at `i`. Returns [title-or-nil next-i]."
  [s i]
  (let [c (get s i)]
    (if (contains? #{\" \' \(} c)
      (let [close (if (= c \() \) c)
            end (loop [j (inc i)]
                  (cond (>= j (count s)) nil
                        (= (get s j) \\) (recur (+ j 2))
                        (= (get s j) close) j
                        :else (recur (inc j))))]
        (if end [(subs s (inc i) end) (inc end)] [nil i]))
      [nil i])))

(defn- scan-link
  "Try an inline link/image at `i` (which points at the `[`). `image?` selects
  <img>. Returns [node next-i] or nil. Only inline `[..](..)` links are handled."
  [s i image?]
  (when-let [close (match-bracket s i)]
    (let [text (subs s (inc i) close)]
      (when (and (< (inc close) (count s)) (= (get s (inc close)) \())
        (let [p (skip-ws s (+ close 2))]
          (when-let [[dest dp] (read-destination s p)]
            (let [[title tp] (read-title s (skip-ws s dp))
                  p3 (skip-ws s tp)]
              (when (and (< p3 (count s)) (= (get s p3) \)))
                (let [href (esc-attr dest)
                      ta (if title (str " title=\"" (esc-attr title) "\"") "")]
                  (if image?
                    [{:t :html :v (str "<img src=\"" href "\" alt=\"" (esc text) "\"" ta " />")} (inc p3)]
                    [{:t :html :v (str "<a href=\"" href "\"" ta ">" (inline->html text) "</a>")} (inc p3)]))))))))))

(defn- tokenize
  "Scan inline text into nodes: {:t :str}, {:t :html}, {:t :delim} (emphasis),
  {:t :br}."
  [s]
  (let [n (count s)]
    (loop [i 0 buf [] out []]
      (let [flush (fn [o] (if (seq buf) (conj o {:t :str :v (apply str buf)}) o))]
        (if (>= i n)
          (flush out)
          (let [c (get s i)]
            (cond
              (and (= c \\) (< (inc i) n) (punct? (get s (inc i))))
              (recur (+ i 2) (conj buf (get s (inc i))) out)

              (and (= c \\) (< (inc i) n) (= (get s (inc i)) \newline))
              (recur (+ i 2) [] (conj (flush out) {:t :br}))

              (= c \`)
              (if-let [[node ni] (scan-code-span s i)]
                (recur ni [] (conj (flush out) node))
                (let [r (run-len s i \`)] (recur (+ i r) (into buf (repeat r \`)) out)))

              (and (= c \!) (< (inc i) n) (= (get s (inc i)) \[))
              (if-let [[node ni] (scan-link s (inc i) true)]
                (recur ni [] (conj (flush out) node))
                (recur (inc i) (conj buf \!) out))

              (= c \[)
              (if-let [[node ni] (scan-link s i false)]
                (recur ni [] (conj (flush out) node))
                (recur (inc i) (conj buf \[) out))

              (= c \<)
              (if-let [[node ni] (scan-angle s i)]
                (recur ni [] (conj (flush out) node))
                (recur (inc i) (conj buf \<) out))

              (or (= c \*) (= c \_))
              (let [r (run-len s i c)
                    before (when (> i 0) (get s (dec i)))
                    after (get s (+ i r))
                    [left right] (flanking before after)
                    open (if (= c \_) (and left (or (not right) (punct? before))) left)
                    close (if (= c \_) (and right (or (not left) (punct? after))) right)]
                (recur (+ i r) [] (conj (flush out) {:t :delim :ch c :len r :open open :close close})))

              ;; two+ trailing spaces before a newline => hard break
              (and (= c \space)
                   (let [j (loop [j i] (if (and (< j n) (= (get s j) \space)) (recur (inc j)) j))]
                     (and (>= (- j i) 2) (< j n) (= (get s j) \newline))))
              (let [j (loop [j i] (if (and (< j n) (= (get s j) \space)) (recur (inc j)) j))]
                (recur (inc j) [] (conj (flush out) {:t :br})))

              (= c \newline)
              (recur (inc i) [] (conj (flush out) {:t :str :v " "}))

              :else (recur (inc i) (conj buf c) out))))))))

(defn- delim->text [{:keys [ch len]}] (apply str (repeat len ch)))

(defn- find-closer [toks]
  (loop [k 0]
    (cond (>= k (count toks)) nil
          (let [t (get toks k)] (and (= (:t t) :delim) (:close t) (pos? (:len t)))) k
          :else (recur (inc k)))))

(defn- find-opener [toks ci]
  (let [closer (get toks ci)]
    (loop [k (dec ci)]
      (cond
        (< k 0) nil
        (let [o (get toks k)]
          (and (= (:t o) :delim) (:open o) (pos? (:len o)) (= (:ch o) (:ch closer))
               ;; rule of 3
               (or (not (or (and (:open o) (:close o)) (and (:open closer) (:close closer))))
                   (not (zero? (mod (+ (:len o) (:len closer)) 3)))
                   (and (zero? (mod (:len o) 3)) (zero? (mod (:len closer) 3))))))
        k
        :else (recur (dec k))))))

(defn- process-emphasis
  "Resolve emphasis/strong over the token vector with a delimiter stack, then
  demote any leftover delimiters to literal text."
  [tokens]
  (loop [toks (vec tokens)]
    (let [ci (find-closer toks)]
      (if (nil? ci)
        (mapv (fn [t] (if (= (:t t) :delim) {:t :str :v (delim->text t)} t)) toks)
        (let [oi (find-opener toks ci)]
          (if (nil? oi)
            ;; closer with no opener: demote it (clear :close so we skip it)
            (recur (assoc toks ci (assoc (get toks ci) :close false)))
            (let [opener (get toks oi) closer (get toks ci)
                  use (if (and (>= (:len opener) 2) (>= (:len closer) 2)) 2 1)
                  tag (if (= use 2) "strong" "em")
                  inner (subvec toks (inc oi) ci)
                  o-rem (- (:len opener) use) c-rem (- (:len closer) use)
                  mid (concat (when (pos? o-rem) [(assoc opener :len o-rem)])
                              [{:t :html :v (str "<" tag ">")}]
                              inner
                              [{:t :html :v (str "</" tag ">")}]
                              (when (pos? c-rem) [(assoc closer :len c-rem)]))]
              (recur (vec (concat (subvec toks 0 oi) mid (subvec toks (inc ci))))))))))))

(defn inline->html
  "Render a string of inline Markdown to HTML."
  [s]
  (->> (tokenize s)
       process-emphasis
       (map (fn [t] (case (:t t)
                      :str (esc (:v t))
                      :html (:v t)
                      :br "<br />\n"
                      "")))
       (apply str)))

;; ------------------------------------------------------------------
;; Block parsing
;; ------------------------------------------------------------------

(def ^:private re-atx #"^ {0,3}(#{1,6})(?:[ \t]+(.*?))?(?:[ \t]+#+)?[ \t]*$")
(def ^:private re-thematic #"^ {0,3}([-*_])[ \t]*(?:\1[ \t]*){2,}$")
(def ^:private re-fence #"^( {0,3})(`{3,}|~{3,})[ \t]*([^`]*?)[ \t]*$")
(def ^:private re-bullet #"^( {0,3})([-+*])(?:[ \t]+(.*)|[ \t]*)$")
(def ^:private re-ordered #"^( {0,3})(\d{1,9})([.)])(?:[ \t]+(.*)|[ \t]*)$")
(def ^:private re-blockquote #"^ {0,3}>[ ]?(.*)$")
(def ^:private re-setext #"^ {0,3}(=+|-+)[ \t]*$")
(def ^:private re-blank #"^[ \t]*$")

(defn- blank? [line] (boolean (re-matches re-blank line)))
(defn- indent-of [line] (count (take-while #(= % \space) line)))
(defn- digits->int [s] (reduce (fn [a c] (+ (* 10 a) (- (int c) (int \0)))) 0 s))

(declare parse-blocks)

(defn- collect-fenced
  "Gather a fenced code block from its opening fence at `i`. Returns [node next-i]."
  [lines i match]
  (let [[_ pad fence info] match
        fence-ch (first fence)
        re-close (re-pattern (str "^ {0,3}[" fence-ch "]{" (count fence) ",}[ \\t]*$"))
        plen (count pad)]
    (loop [j (inc i) acc []]
      (if (or (>= j (count lines)) (re-matches re-close (nth lines j)))
        [{:t :code-block :info info :content (str/join "\n" acc)}
         (if (< j (count lines)) (inc j) j)]
        (let [ln (nth lines j)]
          (recur (inc j) (conj acc (subs ln (min plen (indent-of ln))))))))))

(defn- collect-indented
  "Gather an indented (>=4 space) code block at `i`. Returns [node next-i]."
  [lines i]
  (loop [j i acc []]
    (if (and (< j (count lines))
             (let [ln (nth lines j)] (or (blank? ln) (>= (indent-of ln) 4))))
      (recur (inc j) (conj acc (nth lines j)))
      (let [trimmed (->> (reverse acc) (drop-while blank?) reverse)
            content (str/join "\n" (map #(if (blank? %) "" (subs % (min 4 (count %)))) trimmed))]
        [{:t :code-block :info "" :content content} j]))))

(defn- collect-blockquote
  "Gather consecutive `>` lines at `i`, strip one level, recurse. [node next-i]."
  [lines i]
  (loop [j i acc []]
    (if (and (< j (count lines)) (re-matches re-blockquote (nth lines j)))
      (recur (inc j) (conj acc (second (re-matches re-blockquote (nth lines j)))))
      [{:t :blockquote :children (parse-blocks acc)} j])))

(defn- item-match
  "Parse a list-item marker at the start of `line`, or nil."
  [line]
  (or (when-let [m (re-matches re-bullet line)]
        {:ordered false :marker (nth m 2) :indent (count (nth m 1)) :content (or (nth m 3) "")})
      (when-let [m (re-matches re-ordered line)]
        {:ordered true :delim (nth m 3) :start (nth m 2) :indent (count (nth m 1)) :content (or (nth m 4) "")})))

(defn- marker-str [m]
  (if (:ordered m) (str (:start m) (:delim m)) (str (:marker m))))

(defn- collect-list
  "Parse a list starting at `i` (first item already matched). Returns [node next-i].
  A different marker family starts a new list; deeper-indented content nests via
  recursive block parsing of each item's lines."
  [lines i first-item]
  (let [ordered (:ordered first-item)
        same? (fn [m] (and m (= (:ordered m) ordered) (= (:indent m) (:indent first-item))
                           (if ordered (= (:delim m) (:delim first-item))
                               (= (:marker m) (:marker first-item)))))]
    (loop [j i items [] loose false]
      ;; peek past blank lines so a loose list survives blank separators
      (let [j2 (loop [k j] (if (and (< k (count lines)) (blank? (nth lines k))) (recur (inc k)) k))
            between-blank (> j2 j)
            line (when (< j2 (count lines)) (nth lines j2))
            m (when line (item-match line))]
        (if (and m (same? m))
          (let [cont-indent (+ (:indent m) (count (marker-str m)) 1)
                [acc next-j item-blank]
                (loop [k (inc j2) acc [(:content m)] saw-blank false had-blank false]
                  (let [ln (when (< k (count lines)) (nth lines k))
                        mm (when ln (item-match ln))]
                    (cond
                      (nil? ln) [acc k had-blank]
                      (blank? ln) (recur (inc k) (conj acc "") true had-blank)
                      (and mm (<= (:indent mm) (:indent m)))
                      ;; a blank only loosens the list when the NEXT item continues
                      ;; THIS list; a blank before a different list is just a separator
                      [acc k (or had-blank (and saw-blank (same? mm)))]
                      (>= (indent-of ln) cont-indent)
                      (recur (inc k) (conj acc (subs ln cont-indent)) false (or had-blank saw-blank))
                      (not saw-blank)                ; lazy paragraph continuation
                      (recur (inc k) (conj acc (str/triml ln)) false had-blank)
                      :else [acc k had-blank])))]
            (recur next-j
                   (conj items {:t :item :children (parse-blocks acc)})
                   (or loose between-blank item-blank)))
          [{:t :list :ordered ordered
            :start (when ordered (digits->int (:start first-item)))
            :tight (not loose) :items items}
           j])))))                                    ; leave trailing blanks for the parent

(defn- collect-paragraph
  "Gather a paragraph at `i`; a setext underline turns it into a heading.
  Returns [node next-i]."
  [lines i]
  (loop [j i acc []]
    (let [ln (when (< j (count lines)) (nth lines j))]
      (cond
        (and ln (seq acc) (re-matches re-setext ln))
        [{:t :heading :level (if (= \= (first (str/trim ln))) 1 2)
          :text (str/join "\n" acc)} (inc j)]

        (or (nil? ln) (blank? ln)
            (re-matches re-thematic ln) (re-matches re-atx ln)
            (re-matches re-fence ln) (re-matches re-blockquote ln)
            (item-match ln))
        [{:t :para :text (str/join "\n" acc)} j]

        ;; strip leading indent only — trailing spaces matter for hard breaks
        :else (recur (inc j) (conj acc (str/triml ln)))))))

(defn parse-blocks
  "Parse a vector of lines into a vector of block nodes."
  [lines]
  (loop [i 0 out []]
    (if (>= i (count lines))
      out
      (let [line (nth lines i)]
        (cond
          (blank? line) (recur (inc i) out)
          (re-matches re-thematic line) (recur (inc i) (conj out {:t :thematic-break}))
          (re-matches re-atx line)
          (let [m (re-matches re-atx line)]
            (recur (inc i) (conj out {:t :heading :level (count (nth m 1)) :text (or (nth m 2) "")})))
          (re-matches re-fence line)
          (let [[node ni] (collect-fenced lines i (re-matches re-fence line))] (recur ni (conj out node)))
          (re-matches re-blockquote line)
          (let [[node ni] (collect-blockquote lines i)] (recur ni (conj out node)))
          (item-match line)
          (let [[node ni] (collect-list lines i (item-match line))] (recur ni (conj out node)))
          (>= (indent-of line) 4)
          (let [[node ni] (collect-indented lines i)] (recur ni (conj out node)))
          :else
          (let [[node ni] (collect-paragraph lines i)] (recur ni (conj out node))))))))

;; ------------------------------------------------------------------
;; Rendering
;; ------------------------------------------------------------------

(declare render-nodes)

(defn- render-node [node tight]
  (case (:t node)
    :heading (str "<h" (:level node) ">" (inline->html (:text node)) "</h" (:level node) ">\n")
    :para (if tight
            (str (inline->html (:text node)) "\n")
            (str "<p>" (inline->html (:text node)) "</p>\n"))
    :thematic-break "<hr />\n"
    :code-block (let [lang (first (str/split (str/trim (:info node)) #"\s+"))
                      cls (if (seq lang) (str " class=\"language-" (esc-attr lang) "\"") "")]
                  (str "<pre><code" cls ">" (esc (:content node)) "\n</code></pre>\n"))
    :blockquote (str "<blockquote>\n" (render-nodes (:children node) false) "</blockquote>\n")
    :list (let [tag (if (:ordered node) "ol" "ul")
                start (when (and (:ordered node) (:start node) (not= (:start node) 1))
                        (str " start=\"" (:start node) "\""))
                tght (:tight node)]
            (str "<" tag (or start "") ">\n"
                 (apply str (map (fn [it]
                                   ;; tight items inline their content; loose items
                                   ;; keep block-level <p> wrappers on their own lines
                                   (if tght
                                     (str "<li>" (str/trim (render-nodes (:children it) true)) "</li>\n")
                                     (str "<li>\n" (render-nodes (:children it) false) "</li>\n")))
                                 (:items node)))
                 "</" tag ">\n"))
    ""))

(defn- render-nodes [nodes tight]
  (apply str (map #(render-node % tight) nodes)))

(defn md->html
  "Render a Markdown string to an HTML fragment."
  [s]
  (let [normalized (-> (str s)
                       (str/replace "\r\n" "\n")
                       (str/replace "\r" "\n")
                       (str/replace "\t" "    "))
        ;; -1 keeps trailing empty lines so blank-line block boundaries survive
        lines (vec (str/split normalized #"\n" -1))]
    (render-nodes (parse-blocks lines) false)))
