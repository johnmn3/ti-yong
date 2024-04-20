(ns step-up.alpha.transformer-test
  (:require
   [clojure.test :refer [deftest is]]
   [clojure.edn :as edn]
   [step-up.alpha.dyna-map :as dm]
   [step-up.alpha.transformer :as t]
   [step-up.alpha.util :as u]))

(deftest root-call-arities-test
  (is (= nil (t/transformer)))
  (is (= 1 (t/transformer 1)))
  (is (= false (t/transformer false)))
  (is (= :anything-else (t/transformer :anything-else)))
  (is (= '(1 2) (t/transformer 1 2)))
  (is (= '(1 2 3) (t/transformer 1 2 3)))
  (is (= '(1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21)
         (t/transformer 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21)))
  (is (= '(1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22)
         (apply t/transformer 1 (range 2 23))))
  (is (= (range 100)
         (apply t/transformer 0 1 (range 2 100)))))

(deftest root-basic-test
  (is (= dm/PersistentDynamicMap (type t/transformer)))
  (is (= {:a 1, :b 2} (t/transformer {:b 2, :a 1}))))

(deftest root-op-test
  (is (= 3 (-> t/transformer (assoc :op +) (apply [1 2]))))
  (is (= -4 (-> t/transformer (assoc :op -) (apply [1 2 3]))))
  (is (= 24 (-> t/transformer (assoc :op *) (apply [1 2 3 4]))))
  (is (= 0.041666666666666664 (-> t/transformer (assoc :op /) (apply [1 2 3 4]))))
  (is (= '(:k1 :k2) (-> t/transformer (assoc :op keys) (apply [{:k1 1 :k2 2}])))))

(deftest root-in-test
  (is (= 5 (-> t/transformer
               (update :in conj ::root-in-test-1 #(mapv inc %))
               (assoc :op +)
               (apply [1 2]))))
  (is (= 3 (-> t/transformer
               (update :in conj
                       ::root-in-test-2.1 #(mapv inc %)
                       ::root-in-test-2.2 #(mapv dec %))
               (assoc :op +)
               (apply [1 2]))))
  (is (= 3 (-> t/transformer
               (assoc :op +)
               (update :in conj
                       ::root-in-test-2.2 #(mapv dec %)
                       ::root-in-test-2.1 #(mapv inc %))
               (apply [1 2]))))
  (is (= 28 (-> t/transformer
                (assoc :op *)
                (update :in conj
                        ::root-in-test-3.1 #(mapv (partial * 3) %)
                        ::root-in-test-3.2 #(mapv inc %))
                (apply [1 2]))))
  (is (= [3 6]
         (let [args [1 2]]
           (-> t/transformer
               (assoc :env-op (fn [env] env))
               (update :in conj ::root-in-test-4.1 #(mapv (partial * 3) %))
               (apply args)
               :args
               vec))))
  (is (= ["hello" "world"]
         (let [args [:hello :world]]
           (-> t/transformer
               (assoc :env-op (fn [env] env))
               (update :in conj ::root-in-test-5.1 #(mapv name %))
               (apply args)
               :args
               vec)))))

(deftest root-out-test
  (is (= 4 (-> t/transformer
               (update :out conj ::root-out-test-1 inc)
               (assoc :op +)
               (apply [1 2]))))
  (is (= 3 (-> t/transformer
               (update :out conj
                       ::root-out-test-2.1 inc
                       ::root-out-test-2.2 dec)
               (assoc :op +)
               (apply [1 2]))))
  (is (= 3 (-> t/transformer
               (assoc :op +)
               (update :out conj
                       ::root-out-test-2.2 inc
                       ::root-out-test-2.1 dec)
               (apply [1 2]))))
  (is (= 7 (-> t/transformer
               (assoc :op *)
               (update :out conj
                       ::root-out-test-3.1 (partial * 3)
                       ::root-out-test-3.2 inc)
               (apply [1 2]))))
  (is (= 9
         (let [args [1 2]]
           (-> t/transformer
               (assoc :op +)
               (update :out conj ::root-out-test-4.1 (partial * 3))
               (apply args)))))
  (is (= "hello world"
         (let [args [:hello :world]]
           (-> t/transformer
               (assoc :op (fn [& rargs] (mapv name rargs)))
               (update :out conj
                       ::root-out-test-5.2 (partial interpose " ")
                       ::root-out-test-5.1 (partial apply str))
               (apply args)))))
  (is (= "hello world"
         (let [args [:hello :world]]
           (-> t/transformer
               (update :in conj
                       ::root-in-out-test-6.1 #(mapv name %))
               (assoc :op u/identities)
               (update :out conj
                       ::root-out-test-5.2 (partial interpose " ")
                       ::root-out-test-5.1 (partial apply str))
               (apply args))))))

(defn with-transformer-for-key [k n & mixins]
  (-> t/transformer
      (update :id conj k)
      (assoc k n)
      (update :with into mixins)
      (update :tf conj k (fn [env] (println ::tf k :env env) env))))
#_ (::a (with-test-for-key ::a 1)) ;=> 1
#_ (count (with-test-for-key ::a 1)) ;=> 16


(deftest transformer-with-test
  (let [a (with-transformer-for-key ::a 1)
        b (with-transformer-for-key ::b 2 a)
        c (with-transformer-for-key ::c 3 b)
        x (with-transformer-for-key ::x 7 a c)
        y (with-transformer-for-key ::y 8 c x b a)
        z (with-transformer-for-key ::z 9 y)
        r (with-transformer-for-key ::r 10 z c)]
    (is (= :root|transformer|a|b|c|x|y|z|r
           (->> r :id (mapv name) (interpose "|") (apply str) keyword)))
    (is (= #::{:r 10, :z 9, :x 7, :y 8, :c 3, :b 2, :a 1}
           (select-keys r [::r ::z ::x ::y ::c ::b ::a])))
    (is (= [:a :b :c :x :y :z :r]
           (->> r :tf (partition 2) (map first) (map name) (map keyword) vec)))))

(deftest transformer-pipeline-test
  (let [x (assoc t/transformer
                 :x 1 :y 2
                 :tf-pre [::tf-pre (fn [x] (println :tf-pre #_x) x)]
                 :in     [::in     (fn [x] (println :in     #_x) x)]
                 :tf     [::tf     (fn [x] (println :tf     #_x) x)]
                 :op     +
                 :out    [::out    (fn [x] (println :out    #_x) x)]
                 :tf-end [::tf-end (fn [x] (println :tf-end #_x) x)])]
    (is (= ":tf-pre\n"
           (with-out-str (assoc x :o2 2))))
    (is (= ":tf-pre\n:tf-pre\n:in\n:tf\n:out\n:tf-end\n"
           (with-out-str (x 1 2 3))))
    (is (= ":tf-pre\n:tf-pre\n:tf-pre\n:tf-pre\n:tf-pre\n:in\n:tf\n:out\n:tf-end\n"
           (with-out-str (-> x 
                             (assoc :a 1 :b 2 :c 3 :d 4 :e 5)
                             (apply 1 [2 3])))))))

(comment
  
  ;; todo: convert mock example to test cases

  (defn failure-message [data input output actual]
    (str "Failure in "   (last (:id data))
         " with mock inputs " (pr-str input)
         " when expecting "    (pr-str output)
         " but actually got "     (pr-str actual)))

  (def mocker
    (-> t/transformer
        (update :id conj ::mocker)
        (update :tf-pre conj
                ::mocker
                (fn [{:as env :keys [id mock mocks mocked instantiated?] :or {mocked #{} mocks [] mock []}}]
                  (if (or (mocked id) instantiated? (not (seq mock)))
                    env
                    (let [mocks (into mocks mock)
                          mocked (conj mocked id)
                          this (-> env (dissoc :mock) (->> (merge t/transformer)))
                          failures (->> mocks
                                        (partition 2)
                                        (mapv (fn [[in* out*]]
                                                (assert (coll? in*))
                                                (let [result (apply (dissoc this :args) in*)]
                                                  (when (and result (not= result out*))
                                                    (failure-message env in* out* result)))))
                                        (filter (complement nil?)))]
                      (when (seq failures)
                        (->> failures (mapv (fn [er] (throw (ex-info (str er) {}))))))
                      (assoc env :mocked mocked :mocks mocks :mock [])))))))
  
  (defn strings->ints [& string-ints]
    (->> string-ints (map str) (mapv edn/read-string)))

  (def +s
    (-> t/transformer
        (update :id conj ::+s)
        (assoc :op +)
        (update :with conj mocker)
        (update :tf conj
                ::+s
                #%(merge % (when-let [args (apply strings->ints %:args)]
                             {:args args})))
        (assoc :mock [[1 "2" 3 4 "5" 6] 21])))

  (step-up.alpha.dyna-map/get-methods +s)
  (+s 100 1)

  (defn vecs->ints [& s]
    (->> s (reduce #(if (vector? %2) (into %1 %2) (conj %1 %2)) [])))

  (def +sv
    (-> +s
        (update :id conj ::+sv)
        (update :tf conj
                ::+sv
                #%(merge % (when-let [args (apply vecs->ints %:args)]
                             {:args args})))
        (update :mock conj ["1" [2]] 3))) ;=> #'step-up.alpha.transformer/+sv

  (+sv "1" [2] 3 [4 5]) ;=> 15
  +sv

  (def +sv-failure
    (-> +s
        (update :id conj ::+sv)
        (update :tf conj
                ::+sv
                #%(merge % (when-let [args (apply vecs->ints %:args)]
                             {:args (mapv str args)})))
        (update :mock conj ["1" [2]] 3))) ;=> :repl/exception!
  ; Execution error (ExceptionInfo) at (<cljs repl>:1).
  ; Failure in :step-up.alpha.transformer/+sv with mock inputs [1 "2" 3 4 "5" 6] when expecting 21 but actually got "123456"

  :end)
  
  
