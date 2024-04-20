(ns step-up.alpha.root-test
  (:require
   [clojure.test :refer [deftest is]]
   [step-up.alpha.dyna-map :as dm]
   [step-up.alpha.root :as r]
   [step-up.alpha.util :as u]))

(deftest root-call-arities-test
  (is (= nil (r/root)))
  (is (= 1 (r/root 1)))
  (is (= false (r/root false)))
  (is (= :anything-else (r/root :anything-else)))
  (is (= '(1 2) (r/root 1 2)))
  (is (= '(1 2 3) (r/root 1 2 3)))
  (is (= '(1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21)
         (r/root 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21)))
  (is (= '(1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22)
        (apply r/root 1 (range 2 23))))
  (is (= (range 100)
        (apply r/root 0 1 (range 2 100)))))

(deftest root-basic-test
  (is (= dm/PersistentDynamicMap (type r/root)))
  (is (= {:a 1, :b 2} (r/root {:b 2, :a 1}))))

(deftest root-op-test
  (is (= 3 (-> r/root (assoc :op +) (apply [1 2]))))
  (is (= -4 (-> r/root (assoc :op -) (apply [1 2 3]))))
  (is (= 24 (-> r/root (assoc :op *) (apply [1 2 3 4]))))
  (is (= 0.041666666666666664 (-> r/root (assoc :op /) (apply [1 2 3 4]))))
  (is (= '(:k1 :k2) (-> r/root (assoc :op keys) (apply [{:k1 1 :k2 2}])))))

(deftest root-in-test
  (is (= 5 (-> r/root
               (update :in conj ::root-in-test-1 #(mapv inc %))
               (assoc :op +)
               (apply [1 2]))))
  (is (= 3 (-> r/root
               (update :in conj
                       ::root-in-test-2.1 #(mapv inc %)
                       ::root-in-test-2.2 #(mapv dec %))
               (assoc :op +)
               (apply [1 2]))))
  (is (= 3 (-> r/root
               (assoc :op +)
               (update :in conj
                       ::root-in-test-2.2 #(mapv dec %)
                       ::root-in-test-2.1 #(mapv inc %))
               (apply [1 2]))))
  (is (= 28 (-> r/root
                (assoc :op *)
                (update :in conj
                        ::root-in-test-3.1 #(mapv (partial * 3) %)
                        ::root-in-test-3.2 #(mapv inc %))
                (apply [1 2]))))
  (is (= [3 6]
         (let [args [1 2]]
           (-> r/root
               (assoc :env-op (fn [env] env))
               (update :in conj ::root-in-test-4.1 #(mapv (partial * 3) %))
               (apply args)
               :args
               vec))))
  (is (= ["hello" "world"]
         (let [args [:hello :world]]
           (-> r/root
               (assoc :env-op (fn [env] env))
               (update :in conj ::root-in-test-5.1 #(mapv name %))
               (apply args)
               :args
               vec)))))

(deftest root-out-test
  (is (= 4 (-> r/root
               (update :out conj ::root-out-test-1 inc)
               (assoc :op +)
               (apply [1 2]))))
  (is (= 3 (-> r/root
               (update :out conj
                       ::root-out-test-2.1 inc
                       ::root-out-test-2.2 dec)
               (assoc :op +)
               (apply [1 2]))))
  (is (= 3 (-> r/root
               (assoc :op +)
               (update :out conj
                       ::root-out-test-2.2 inc
                       ::root-out-test-2.1 dec)
               (apply [1 2]))))
  (is (= 7 (-> r/root
               (assoc :op *)
               (update :out conj
                       ::root-out-test-3.1 (partial * 3)
                       ::root-out-test-3.2 inc)
               (apply [1 2]))))
  (is (= 9
         (let [args [1 2]]
           (-> r/root
               (assoc :op +)
               (update :out conj ::root-out-test-4.1 (partial * 3))
               (apply args)))))
  (is (= "hello world"
         (let [args [:hello :world]]
           (-> r/root
               (assoc :op (fn [& rargs] (mapv name rargs)))
               (update :out conj
                       ::root-out-test-5.2 (partial interpose " ")
                       ::root-out-test-5.1 (partial apply str))
               (apply args)))))
  (is (= "hello world"
         (let [args [:hello :world]]
           (-> r/root
               (update :in conj
                       ::root-in-out-test-6.1 #(mapv name %))
               (assoc :op u/identities)
               (update :out conj
                       ::root-out-test-5.2 (partial interpose " ")
                       ::root-out-test-5.1 (partial apply str))
               (apply args))))))
