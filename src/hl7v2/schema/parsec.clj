(ns hl7v2.schema.parsec
  (:require [clojure.string :as str]))


(defn cur [inp]
  (get (:msg inp) (:pos inp)))

(defn inext [inp]
  (update inp :pos inc))

(defn seg? [x]
  (let [fl (subs x 0 1)]
    (= fl (str/upper-case fl))))

(defn name-quant [x]
  (if-let [[_ nm q] (re-find #"(.*)(\?|\*|\+)$" x)]
    [(keyword nm) (keyword q)]
    [(keyword x) nil]))

(name-quant "xxxssss?")

(name-quant "xxxssss")

(name-quant "xxxssss*")

(name-quant "xxxssss+")

(defn do-parse [grammar rule inp]
  (loop [[stm & stms :as sstms] (get grammar rule)
         inp inp
         out {}
         repeat true]
    (println rule stm (cur inp))
    (if (nil? stm)
      [inp out]
      (if-let [c (cur inp)]
        (let [tp (if (seg? stm) :seg :grp)
              [nm q] (name-quant stm)]
          (cond
            (= tp :seg) (if (= nm c)
                          (if (contains? #{:+ :*} q )
                            (recur sstms (inext inp) (update out c (fn [x] (conj (or x []) (:pos inp)))) true)
                            (recur stms (inext inp)  (assoc out c (:pos inp)) false))
                          (cond
                            (or (= q :*) (and repeat (= q :+)))
                            (recur stms inp out true)
                            (= q :?)
                            (recur stms inp out false)
                            :else
                            [inp [:error (str "Rule " rule " [" (str/join " " (get grammar rule)) "] at " stm  " expected  [" (name nm) "] got [" (name c) "] segment position " (:pos inp))]]))

            (= tp :grp) (let [[inp' res] (do-parse grammar nm inp)]
                          (if-not (= :error (first res))
                            (if (contains? #{:+ :*} q)
                              (recur sstms inp' (update out nm (fn [x] (conj (or x []) res))) true)
                              (recur stms inp' (assoc out nm res) false))
                            (if (contains? #{:? :*} q)
                              (recur stms inp out false)
                              [inp res])))))
        [inp out]))))

(defn parse [grammar msg]
  (second (do-parse grammar :msg {:msg msg :pos 0})))

