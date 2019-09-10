(ns hl7v2.core-test
  (:require [hl7v2.core :as sut]
            [clj-yaml.core]
            [hl7v2.schema.core :as schema]
            [matcho.core :refer [match]]
            [zprint.core :as zp]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer :all])
  (:import [java.io File]))

(def msg
  "MSH|^~\\&|AccMgr|1|||20151015200643||ADT^A01|599102|P|2.3|foo||
EVN|A01|20151010045502|||||
PID|1|010107111^^^MS4^PN^|1609220^^^MS4^MR^001|1609220^^^MS4^MR^001|BARRETT^JEAN^SANDY^^||19440823|F||C|STRAWBERRY AVE^FOUR OAKS LODGE^ALBUKERKA^CA^98765^USA^^||(111)222-3333||ENG|W|CHR|111155555550^^^MS4001^AN^001|123-22-1111||||OKLAHOM
PV1|1|I|PREOP^101^1^1^^^S|3|||37^REID^TIMOTHY^Q^^^^^^AccMgr^^^^CI|||01||||1|||37^REID^TIMOTHY^Q^^^^^^AccMgr^^^^CI|2|40007716^^^AccMgr^VN|4|||||||||||||||||||1||G|||20050110045253||||||
GT1|1|010107127^^^MS4^PN^|BARRETT^JEAN^S^^|BARRETT^LAWRENCE^E^^|2820 SYCAMORE AVE^TWELVE OAKS LODGE^MONTROSE^CA^91214^USA^|(818)111-3361||19301013|F||A|354-22-1840||||RETIRED|^^^^00000^|||||||20130711|||||0000007496|W||||||||Y|||CHR||||||||RETIRED||||||C
IN1|1|0423|MEDICARE IP|^^^^     |||||||19951001|||MCR|BARRETT^JEAN^S^^|A|19301013|2820 SYCAMORE AVE^TWELVE OAKS LODGE^MONTROSE^CA^91214^USA^^^|||1||||||||||||||354221840A|||||||F|^^^^00000^|N||||010107127
IN2||354221840|0000007496^RETIRED|||354221840A||||||||||||||||||||||||||||||Y|||CHR||||W|||RETIRED|||||||||||||||||(818)249-3361||||||||C
IN1|2|0423|2304|AETNA PPO|PO BOX 14079^PO BOX 14079^LEXINGTON^KY^40512|||081140101400020|RETIRED|||20130101|||COM|BARRETT^JEAN^S^^|A|19301013|2820 SYCAMORE AVE^TWELVE OAKS LODGE^MONTROSE^CA^91214^USA^^^|||2||||||||||||||811001556|||||||F|^^^^00000^|N||||010107127
IN2||354221840|0000007496^RETIRED|||||||||||||||||||||||||||||||||Y|||CHR||||W|||RETIRED|||||||||||||||||(818)249-3361||||||||C
")

(deftest test-parsing
  (reset! schema/*schema nil)

  (def schema (schema/schema))
  (def seps {:field \|,
             :component \^,
             :subcomponet \&,
             :repetition \~,
             :escape \\})

  (def ctx 
    {:schema schema
     :separators seps})

  (is (= (dissoc (sut/separators msg) :segment) seps))

  (testing "segments"
    (match 
     (sut/parse-segment
      ctx "MSH|^~\\&|AccMgr|1|||20151015200643||ADT^A01|599102|P|2.3|foo||")
     ["MSH"
      {:type {:code "ADT", :event "A01"},
       :id "599102",
       :encoding "^~\\&",
       :seqnum "foo",
       :version {:id "2.3"},
       :proc_id {:proc_id "P"},
       :datetime {:time "20151015200643"},
       :facility {:ns "1"},
       :app {:ns "AccMgr"}
       :separator "|"
       }])

    (match
     (sut/parse-segment
      ctx "PID|1|010107111^^^MS4^PN^|1609220^^^MS4^MR^001|1609220^^^MS4^MR^001|BARRETT^JEAN^SANDY^^||19440823|F||C|STRAWBERRY AVE^FOUR OAKS LODGE^ALBUKERKA^CA^98765^USA^^||(111)222-3333||ENG|W|CHR|111155555550^^^MS4001^AN^001|123-22-1111||||OKLAHOMA|||||||N")
     ["PID"
      {:religion {:code "CHR"},
       :patient_id  {:id "010107111",
                    :authority {:ns "MS4"},
                    :type "PN"},
       :alternate_id [{:id "1609220",
                       :authority {:ns "MS4"},
                       :type "MR",
                       :facility {:ns "001"}}],
       :account_number {:id "111155555550",
                        :authority {:ns "MS4001"},
                        :type "AN",
                        :facility {:ns "001"}},
       :race [{:code "C"}],
       :gender "F",
       :birth_date {:time "19440823"},
       :primary_language  {:code "ENG"},
       :home_phone [{:phone "(111)222-3333"}],
       :ssn_number "123-22-1111",
       :birth_place "OKLAHOMA",
       :address [{:street {:text "STRAWBERRY AVE"}
                  :text "FOUR OAKS LODGE",
                  :city "ALBUKERKA",
                  :state "CA",
                  :postal_code "98765",
                  :country "USA"}],
       :set_id "1",
       :name [{:family {:surname "BARRETT"},
               :given "JEAN",
               :initials "SANDY"}],
       :identifiers [{:id "1609220",
                      :authority {:ns "MS4"},
                      :type "MR",
                      :facility {:ns "001"}}],
       :marital_status {:code "W"},
       :death_indicator "N"}])

    (match 
     (sut/parse-segment
      ctx "PID|1|312626^^^^^Main Lab&05D0557149&CLIA|0362855^^^^^Main Lab&05D0557149&CLIA|^^^^^Main Lab&05D0557149&CLIA|LOPEZ^ADALBERTO||19450409|M|||8753 APPERSON ST^^SUNLAND^CA^91040||(818)429-5631|||||000016715153|572458313")

     ["PID" {:identifiers [{:id "0362855",
                            :facility {:ns "Main Lab", :uid "05D0557149", :type "CLIA"}}]}]

     )

    ))


(defn expectation-file [^File f]
  (let [name (.getName f)
        parent (-> f .getParentFile .getParentFile .getAbsoluteFile)]
    (str parent "/edns/" name ".edn")))

(defn match-file [fname content]
  (match (-> fname slurp edn/read-string)
         content))

(defmacro foreach-hl7 [[input expected] & body]
  `(for [^File input-file# (->> "messages" io/resource io/file file-seq (filter #(.isFile %)))]
     (let [~input (slurp input-file#)
           ~expected (expectation-file input-file#)]
       ~@body)))

(deftest test-parse-examples
  (foreach-hl7 [input expected]
               (testing (str "with " expected)
                 (match-file expected (sut/parse input)))))


(comment

  ;; overrride all files
  (foreach-hl7 [input expected]
               (spit expected (-> input sut/parse zp/zprint-str)))


  (spit "test/results/adt.yaml" (clj-yaml.core/generate-string (sut/parse msg {})))


  (def oru "MSH|^~\\&|LAb|Lab|Lab|Lab|20100222024516||ORU^R30|87226A1476977481|P|2.4|2010022217399||AL|NE
PID|1|312626^^^^^Main Lab&05D0557149&CLIA|0362855^^^^^Main Lab&05D0557149&CLIA|^^^^^Main Lab&05D0557149&CLIA|LOPEZ^ADALBERTO||19450409|M|||8753 APPERSON ST^^SUNLAND^CA^91040||(818)429-5631|||||000016715153|572458313
ORC|NW||||||^^^|||||^^^||||||L7173SB00037^^VHH^Med Surg-6^^|
OBR|1|||CHEM8+
NTE|1|||Sample Type=MIX||20110529130917-04:00
NTE|2|||CPB=Yes||20110529130917-04:00
OBX|1|NM|8625-6^P-R interval^LN||<10|%PCV||N|||F|||||APOC3214||equipment|20120529130917-04:00|MIX
OBX|2|DT|41651-1^date^LN||20110529130917-04:00|mg/dL|||||F|||20150529130917-04:00||APOC3214||||MIX
OBX|3|ST|45541-1^GJU^LN||100500 mm||||||F|||||APOC3214|||20110529130917-04:00|MIX
OBX|4|CE|45541-1^GJU^LN||43434-1^8890 jkjsdfk^BV||||||F|||||APOC3214|||20110529130917-04:00|MIX
NTE|1||DSN=314237||20110529130917-04:00
NTE|2||HCT=LOW||20110529130917-04:00
")

  (spit "test/results/oru.yaml" (clj-yaml.core/generate-string (sut/parse oru {})))

  )

