(ns swank.commands.xref
  (:use clojure.walk swank.util)
  (:import (clojure.lang RT)
           (java.io LineNumberReader InputStreamReader PushbackReader)))

;; Yoinked and modified from clojure.contrib.repl-utils.
;; Now takes a var instead of a sym in the current ns
(defn- get-source-from-var
  "Returns a string of the source code for the given symbol, if it can
find it. This requires that the symbol resolve to a Var defined in
a namespace for which the .clj is in the classpath. Returns nil if
it can't find the source.
Example: (get-source-from-var 'filter)"
  [v] (when-let [filepath (:file (meta v))]
        (when-let [strm (.getResourceAsStream (RT/baseLoader) filepath)]
          (with-open [rdr (LineNumberReader. (InputStreamReader. strm))]
            (dotimes [_ (dec (:line (meta v)))] (.readLine rdr))
            (let [text (StringBuilder.)
                  pbr (proxy [PushbackReader] [rdr]
                        (read [] (let [i (proxy-super read)]
                                   (.append text (char i))
                                   i)))]
              (read (PushbackReader. pbr))
              (str text))))))

(defn- contains-sub-nodes? [tree]
  (or (sequential? tree) (map? tree)))

(defn- expand-sub-nodes [tree]
  (if (map? tree)
    (interleave (keys tree) (vals tree))
    (seq tree)))

(defn- sub-nodes [tree]
  (tree-seq contains-sub-nodes?
            expand-sub-nodes tree))

(defn- regex? [obj]
  (= (class obj) java.util.regex.Pattern))

(defn- replace-with-string [node]
  (if (regex? node)
    (.toString node)
    node))

(defn- replace-regex [coll]
  "Returns a copy of coll with all regex replaced by the string given by calling toString on them"
  (postwalk
   replace-with-string
   coll))

(defn- maybe-replace-regex [obj]
  (if (seq? obj)
    (replace-regex obj)
    obj))

(defn- recursive-contains? [coll obj]
  "True if coll contains obj at some level of nesting"
  (some #{(maybe-replace-regex obj)}
        (sub-nodes (maybe-replace-regex coll))))

(defn- does-var-call-fn [var fn]
  "Checks if a var calls a function named 'fn"
  (if-let [source (get-source-from-var var)]
    (let [node (read-string source)]
     (if (recursive-contains? node fn)
       var
       false))))

(defn- does-ns-refer-to-var? [ns var]
  (ns-resolve ns var))

(defn all-vars-who-call [sym]
  (filter
   ifn?
   (filter
    #(identity %)
    (map #(does-var-call-fn % sym)
         (flatten
          (map vals
               (map ns-interns
                    (filter #(does-ns-refer-to-var? % sym)
                            (all-ns)))))))))