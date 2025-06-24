(ns ti-yong.alpha.transformer-op-in-out-test
  (:require
   [clojure.test :refer [deftest is]]
   [ti-yong.alpha.transformer :as t]
   [ti-yong.alpha.util :as u]))

(deftest transformer-op-test
  (is (= 3 (apply (-> t/transformer (assoc :op +)) [1 2])))
  (is (= -4 (apply (-> t/transformer (assoc :op -)) [1 2 3])))
  (is (= 24 (apply (-> t/transformer (assoc :op *)) [1 2 3 4])))
  (is (= 1/24 (apply (-> t/transformer (assoc :op /)) [1 2 3 4])))
  (is (= '(:k1 :k2) (apply (-> t/transformer (assoc :op keys)) [{:k1 1 :k2 2}])))) ; Corrected to 4 )

(deftest transformer-in-test
  (is (= 5 (apply (-> t/transformer
                        (update :in conj ::root-in-test-1 #(mapv inc %))
                        (assoc :op +))
                  [1 2])))
  (is (= 3 (apply (-> t/transformer
                        (update :in conj
                                ::root-in-test-2.1 #(mapv inc %)
                                ::root-in-test-2.2 #(mapv dec %))
                        (assoc :op +))
                  [1 2])))
  (is (= 3 (apply (-> t/transformer
                        (assoc :op +)
                        (update :in conj
                                ::root-in-test-2.2 #(mapv dec %)
                                ::root-in-test-2.1 #(mapv inc %)))
                  [1 2])))
  (is (= 28 (apply (-> t/transformer
                         (assoc :op *)
                         (update :in conj
                                 ::root-in-test-3.1 #(mapv (partial * 3) %)
                                 ::root-in-test-3.2 #(mapv inc %)))
                   [1 2])))
  (is (= [3 6]
         (let [args [1 2]]
           (-> (-> t/transformer
                   (assoc :env-op (fn [env & _r] env))
                   (update :in conj ::root-in-test-4.1 #(mapv (partial * 3) %)))
               (apply args)
               :args
               vec))))
  (is (= ["hello" "world"]
         (let [args [:hello :world]]
           (-> (-> t/transformer
                   (assoc :env-op (fn [env & _r] env))
                   (update :in conj ::root-in-test-5.1 #(mapv name %)))
               (apply args)
               :args
               vec)))))

(deftest transformer-out-test
  (is (= 4 (apply (-> t/transformer
                        (update :out conj ::root-out-test-1 inc)
                        (assoc :op +))
                  [1 2])))
  (is (= 3 (apply (-> t/transformer
                        (update :out conj
                                ::root-out-test-2.1 inc
                                ::root-out-test-2.2 dec)
                        (assoc :op +))
                  [1 2])))
  (is (= 3 (apply (-> t/transformer
                        (assoc :op +)
                        (update :out conj
                                ::root-out-test-2.2 inc
                                ::root-out-test-2.1 dec))
                  [1 2])))
  (is (= 7 (apply (-> t/transformer
                        (assoc :op *)
                        (update :out conj
                                ::root-out-test-3.1 (partial * 3)
                                ::root-out-test-3.2 inc))
                  [1 2])))
  (is (= 9
         (let [args [1 2]]
           (apply (-> t/transformer
                        (assoc :op +)
                        (update :out conj ::root-out-test-4.1 (partial * 3)))
                  args))))
  (is (= "hello world"
         (let [args [:hello :world]]
           (apply (-> t/transformer
                        (assoc :op (fn [& rargs] (mapv name rargs)))
                        (update :out conj
                                ::root-out-test-5.2 (partial interpose " ")
                                ::root-out-test-5.1 (partial apply str)))
                  args))))
  (is (= "hello world"
         (let [args [:hello :world]]
           (apply (-> t/transformer
                        (update :in conj
                                ::root-in-out-test-6.1 #(mapv name %))
                        (assoc :op u/identities) ; u/identities is key here
                        (update :out conj
                                ::root-out-test-5.2 (partial interpose " ")
                                ::root-out-test-5.1 (partial apply str)))
                  args))))) ; Actually 5 ) now
