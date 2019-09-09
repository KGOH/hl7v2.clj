(ns hl7v2.schema.parsec-test
  (:require [hl7v2.schema.parsec :as sut]
            [clojure.test :refer :all]))

(defmacro parsed-as [g a b]
  `(let [res# (sut/parse ~g ~a)]
     (is (= res# ~b))
     res#))

(deftest test-parser 

  (parsed-as
   {:msg ["A" "B*" "C"]}
   [:A :B :B :C]
   {:A 0, :B [1 2] :C 3})


  (parsed-as
   {:msg ["A" "C"]}
   [:A :B :C]
   [:error "Rule :msg [A C] at C expected  [C] got [B] segment position 1"])

  (parsed-as
   {:msg ["A" "B+"]}
   [:A :C]
   [:error "Rule :msg [A B+] at B+ expected  [B] got [C] segment position 1"])

  (parsed-as
   {:msg ["A" "B+" "C"]}
   [:A :B :B :C]
   {:A 0, :B [1 2] :C 3})


  (parsed-as
   {:msg ["A" "B+"]}
   [:A :B :B]
   {:A 0, :B [1 2]})


  (parsed-as
   {:msg ["A" "B?" "C"]}
   [:A  :C]
   {:A 0 :C 1})

  (def grm1 {:msg     ["M" "pt*"]
             :pt      ["A" "visit*" "F"]
             :visit   ["B" "details?"]
             :details ["D"]})


  (parsed-as
   grm1
   [:M :A :B :B :D :B :F :A :B :B :D :B :F]
   {:M 0,
    :pt [{:A 1, :visit [{:B 2} {:B 3, :details {:D 4}} {:B 5}], :F 6}
         {:A 7, :visit [{:B 8} {:B 9, :details {:D 10}} {:B 11}], :F 12}]})

  (def oru  {:msg ["MSH" "patient" "order+"]
             :patient ["PID" "PD1?" "visit?"]
             :visit ["PV1" "PV2?"]
             :order ["ORC?" "OBR" "result"]
             :result ["OBX" "NTE*"]})

  (parsed-as
   oru
   [:MSH :PID :PV1]
   {:MSH 0, :patient {:PID 1, :visit {:PV1 2}}})

  (parsed-as
   oru
   [:MSH :PID :PV1 :OBR :OBX :OBX :NTE]
   1)

  )
