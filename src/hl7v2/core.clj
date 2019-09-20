(ns hl7v2.core
  (:require [clojure.string :as str]
            [flatland.ordered.map :refer [ordered-map]]
            [hl7v2.schema.core :as schema]
            [hl7v2.schema.parsec :as parsec]
            [hl7v2.schema.types :as types])
  (:import [java.util.regex Pattern]))



(defn pre-condigion [msg]
  (cond-> []
    (not (str/starts-with? msg "MSH")) (conj "Message should start with MSH segment")
    (< (.length msg) 8) (conj "Message is too short (MSH truncated)")))

(defn separators [msg]
  {:segment #"(\r\n|\r|\n)"
   :field (get msg 3)
   :component (get msg 4)
   :subcomponet (get msg 7)
   :repetition (get msg 5)
   :escape (get msg 6)})

(defn split-by [s sep]
  (if (= java.util.regex.Pattern (type sep))
    (str/split s sep)
    (str/split s (re-pattern (Pattern/quote (str sep))))))

(defn indexed-map [coll]
  (loop [[x & xs] coll acc {} idx 1]
    (if (empty? xs)
      (if (nil? x) acc (assoc acc idx x))
      (recur xs
             (if (nil? x) acc (assoc acc idx x))
             (inc idx)))))

(defn not-empty? [v]
  (if (or (map? v) (sequential? v))
    (not (empty? v))
    (if (string? v)
      (not (str/blank? v))
      (not (nil? v)))))

(defn parse-value [{sch :schema sep :separators :as ctx} tp v]
  (if-let [parse-fn (get types/typed-formatters (keyword (:type tp)))]
    (parse-fn v)
    (if (get schema/primitives (keyword (:type tp)))
      v
      (if-let [sub-tp (get-in sch [:types (keyword (:type tp))])]
        (let [sub-cmps (split-by v (:subcomponet sep))
              sub-types (:components sub-tp)]
          (loop [[c & cs] sub-cmps
                 [s & ss] sub-types
                 res {}]
            (let [res (if-not (str/blank? c)
                        (let [v (parse-value ctx s c)]
                          (if (not-empty? v)
                            (assoc res (keyword (:key s)) v)
                            res))
                        res)]
              (if (empty? cs)
                res
                (recur cs ss res)))))
        (do 
          (println "WARN:" (pr-str (merge {} tp) v))
          v)))))


(defn parse-component [ctx tp v]
  (if (:components tp)
    (let [cmps (split-by v (get-in ctx [:separators :component]))]
      (loop [[c & cs] cmps
             [s & ss] (:components tp)
             res {}]
        (let [res (if-not (str/blank? c)
                    (let [v (parse-value ctx s c)]
                      (if (not-empty? v)
                        (assoc res (keyword (:key s)) v)
                        res))
                    res)]
          (if (empty? cs)
            res
            (recur cs ss res)))))
    v))

(defn parse-field [{sch :schema seps :separators :as ctx} {tpn :type c? :coll v :value :as f}]
  (let [tp (get-in sch [:types (keyword tpn)])
        vv (if c?
             (->> (split-by v (:repetition seps))
                  (mapv #(parse-component ctx tp %))
                  (filterv not-empty?))
             (parse-component ctx tp v))]
    vv))

(defn parse-segment [{sch :schema seps :separators :as ctx} seg]
  (let [fields (split-by seg (:field seps))
        [seg-name & fields] fields
        fields (if (= "MSH" seg-name)
                 (into ["|"] fields)
                 fields)

        seg-sch (get-in sch [:segments (keyword seg-name)])]
    [seg-name
     (loop [[f & fs] fields
            [s & ss] seg-sch
            acc {}]
       (let [s (merge s (get-in sch [:fields (keyword (:field s))]))]
         (if (str/blank? f)
           (recur fs ss acc)
           (let [v (parse-field ctx (assoc (or s {}) :value f))
                 acc  (if (not-empty? v) 
                        (assoc acc (keyword (:key s)) v)
                        acc)]
             (if (empty? fs)
               acc
               (recur fs ss acc))))))]))

;; FIX: grammar desc is a list?
;; if after is nil = conj
(defn append [after coll x]
  (if after
    (let [pattern (re-pattern (str after ".?"))]
      (flatten (mapv #(if (re-find pattern %) [% x] %) coll)))
    (if (vector? coll)
      (conj coll x)
      (seq (conj (vec coll) x)))))

;; extension proposal
;; [:ADT_A01 :ZBC [[:name "ZBC.1" :type ST :key "zbc1"]]]
;; [:ADT_A01 :ZBC [[:name "ZBC.1" :type ST :key "zbc1"]] {:after #"PID"}]
;; TODO: support "put after", not only append
(defn apply-extension [schema [grammar segment-name segment-desc {after :after quant :quant}]]
  (let [[grammar rule] (if (sequential? grammar) grammar [grammar :msg])
        rule (or rule :msg)
        messages-path (conj [:messages] grammar rule)
        desc (map #(apply ordered-map %) segment-desc)]
    (-> schema
        (update-in messages-path (partial append after) (str (name segment-name) (or quant "?")))
        (assoc-in [:segments segment-name] desc))))

(defn parse
  ([msg] (parse msg {}))
  ([msg {extensions :extensions :as opts}]
   (let [errs (pre-condigion msg)]
     (when-not (empty? errs)
       (throw (Exception. (str/join "; " errs))))
     (let [sch (schema/schema)
           sch (reduce apply-extension sch extensions)
           seps (separators msg)
           ctx {:separators seps
                :schema sch}
           segments (->> (split-by msg (:segment seps))
                         (mapv str/trim)
                         (filter #(> (.length %) 0))
                         (mapv #(parse-segment ctx %)))
           {c :code e :event} (get-in segments [0 1 :type])
           msg-key (get-in sch [:messages :idx (keyword c) (keyword e)])
           grammar (get-in sch [:messages (when [msg-key] (keyword msg-key))])]
       (when-not grammar
         (throw (Exception. (str "Do not know how to parse: " c "|" e " " (first segments)) )))
       #_(println "GR"  (keyword (str c "_" e)) grammar)
       (parsec/parse grammar (mapv #(keyword (first %)) segments) (fn [idx] (get-in segments [idx 1])))))))
