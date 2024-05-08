(ns ti-yong.alpha.dyna-map-test
  (:require
   [clojure.test :refer [deftest is]]
   [ti-yong.alpha.dyna-map :as dm]))

(deftest dyna-map-build-test
  (is (= dm/PersistentDynamicMap (type (dm/dyna-map))))
  (is (= {:a 1, :b 2} (dm/dyna-map :a 1, :b 2)))
  (is (= {:a 1, :b 2} (dm/dyna-map :b 2, :a 1)))
  (is (= {:a 1, :b 2, :c 3} (dm/dyna-map :a 1, :b 2, :c 3)))
  (is (= {:a 1, :b 2, :c 3} (dm/dyna-map :c 3, :a 1, :b 2)))
  (is (= {:a 1, :b 2, :c 3} (dm/dyna-map :c 3, :b 2, :a 1)))
  (is (= {:a 1, :b 2, :c 3} (dm/dyna-map :b 2, :c 3, :a 1))))

(deftest dyna-map-arity-test
  (is (= "Error: Invalid arity: 0"
         (try ((dm/dyna-map)) (catch :default e (str e)))))
  (is (= 1 ((dm/dyna-map :a 1) :a)))
  (is (= nil ((dm/dyna-map :a 1) :b)))
  (is (= "Error: Invalid arity: 3"
         (try ((dm/dyna-map) 1 2 3) (catch :default e (str e)))))
  (is (= "Error: Invalid arity: 4"
         (try ((dm/dyna-map) 1 2 3 4) (catch :default e (str e))))))

(deftest dyna-map-assoc-dissoc-test
  (is (= {:a 1, :b 2} (assoc (dm/dyna-map :a 1) :b 2)))
  (is (= dm/PersistentDynamicMap
         (type (assoc (dm/dyna-map :a 1) :b 2))))
  
  (is (= {:a 1} (dissoc (dm/dyna-map :a 1 :b 2) :b)))
  (is (= dm/PersistentDynamicMap
         (type (dissoc (dm/dyna-map :a 1 :b 2) :b))))

  (is (= {:a 1, :b 2} (merge (dm/dyna-map :a 1) {:b 2})))
  (is (= dm/PersistentDynamicMap
         (type (merge (dm/dyna-map :a 1) {:b 2})))))

(deftest dyna-map-conj-test
  (is (= (conj (dm/dyna-map) {}) (dm/dyna-map)))
  (is (= (conj (dm/dyna-map) {:a 1}) (dm/dyna-map :a 1)))
  (is (= (conj (dm/dyna-map) {:a 1} {:b 2}) (dm/dyna-map :a 1 :b 2)))
  (is (= (conj (dm/dyna-map) {:a 1} {:b 2 :c 3}) (dm/dyna-map :a 1 :b 2 :c 3)))

  (is (= (conj (dm/dyna-map :a 1) {}) (dm/dyna-map :a 1)))
  (is (= (conj (dm/dyna-map :a 1) {:b 2}) (dm/dyna-map :a 1 :b 2)))
  (is (= (conj (dm/dyna-map :a 1) {:b 2} {:c 3}) (dm/dyna-map :a 1 :b 2 :c 3)))

  (is (= (conj (dm/dyna-map) (first (dm/dyna-map :a 1)))
         (dm/dyna-map :a 1)))
  (is (= (conj (dm/dyna-map :b 2) (first (dm/dyna-map :a 1)))
         (dm/dyna-map :a 1 :b 2)))
  (is (= (conj (dm/dyna-map :b 2) (first (dm/dyna-map :a 1)) (first (dm/dyna-map :c 3)))
         (dm/dyna-map :a 1 :b 2 :c 3)))

  (is (= (conj (dm/dyna-map) [:a 1])
         (dm/dyna-map :a 1)))
  (is (= (conj (dm/dyna-map :b 2) [:a 1])
         (dm/dyna-map :a 1 :b 2)))
  (is (= (conj (dm/dyna-map :b 2) [:a 1] [:c 3])
         (dm/dyna-map :a 1 :b 2 :c 3)))

  (is (= (conj (dm/dyna-map) (dm/dyna-map nil (dm/dyna-map)))
         (dm/dyna-map nil (dm/dyna-map))))
  (is (= (conj (dm/dyna-map) (dm/dyna-map (dm/dyna-map) nil))
         (dm/dyna-map (dm/dyna-map) nil)))
  (is (= (conj (dm/dyna-map) (dm/dyna-map (dm/dyna-map) (dm/dyna-map)))
         (dm/dyna-map (dm/dyna-map) (dm/dyna-map)))))

(deftest dyna-map-find-test
  (is (= (conj (dm/dyna-map) {}) (dm/dyna-map)))
  (is (= (find (dm/dyna-map) :a) nil))
  (is (= (find (dm/dyna-map :a 1) :a) [:a 1]))
  (is (= (find (dm/dyna-map :a 1) :b) nil))
  (is (= (find (dm/dyna-map nil 1) nil) [nil 1]))
  (is (= (find (dm/dyna-map :a 1 :b 2) :a) [:a 1]))
  (is (= (find (dm/dyna-map :a 1 :b 2) :b) [:b 2]))
  (is (= (find (dm/dyna-map :a 1 :b 2) :c) nil))
  (is (= (find (dm/dyna-map) nil) nil))
  (is (= (find (dm/dyna-map :a 1) nil) nil))
  (is (= (find (dm/dyna-map :a 1 :b 2) nil) nil)))

(deftest dyna-map-contains-test
  (is (= (contains? (dm/dyna-map) :a) false))
  (is (= (contains? (dm/dyna-map) nil) false))
  (is (= (contains? (dm/dyna-map :a 1) :a) true))
  (is (= (contains? (dm/dyna-map :a 1) :b) false))
  (is (= (contains? (dm/dyna-map :a 1) nil) false))
  (is (= (contains? (dm/dyna-map nil 1) nil) true))
  (is (= (contains? (dm/dyna-map :a 1 :b 2) :a) true))
  (is (= (contains? (dm/dyna-map :a 1 :b 2) :b) true))
  (is (= (contains? (dm/dyna-map :a 1 :b 2) :c) false))
  (is (= (contains? (dm/dyna-map :a 1 :b 2) nil) false)))

(deftest dyna-map-keys-vals-test
  (is (= (keys (dm/dyna-map)) nil))
  (is (= (keys (dm/dyna-map :a 1)) '(:a)))
  (is (= (keys (dm/dyna-map nil 1)) '(nil)))
  (is (= (vals (dm/dyna-map)) nil))
  (is (= (vals (dm/dyna-map :a 1)) '(1)))
  (is (= (vals (dm/dyna-map nil 1)) '(1))))

(deftest dyna-map-get-test
  (let [m (dm/dyna-map :a 1, :b 2, :c {:d 3, :e 4}, :f nil, :g false, nil {:h 5})]
    (is (= (get m :a) 1))
    (is (= (get m :e) nil))
    (is (= (get m :e 0) 0))
    (is (= (get m nil) {:h 5}))
    (is (= (get m :b 0) 2))
    (is (= (get m :f 0) nil))
    (is (= (get-in m [:c :e]) 4))
    (is (= (get-in m '(:c :e)) 4))
    (is (= (get-in m [:c :x]) nil))
    (is (= (get-in m [:f]) nil))
    (is (= (get-in m [:g]) false))
    (is (= (get-in m [:h]) nil))
    (is (= (get-in m []) m))
    (is (= (get-in m nil) m))
    (is (= (get-in m [:c :e] 0) 4))
    (is (= (get-in m '(:c :e) 0) 4))
    (is (= (get-in m [:c :x] 0) 0))
    (is (= (get-in m [:b] 0) 2))
    (is (= (get-in m [:f] 0) nil))
    (is (= (get-in m [:g] 0) false))
    (is (= (get-in m [:h] 0) 0))
    (is (= (get-in m [:x :y] {:y 1}) {:y 1}))
    (is (= (get-in m [] 0) m))
    (is (= (get-in m nil 0) m))))

(deftest dyna-map-destructure-test
  (let [sample-map (dm/dyna-map :a 1 :b {:a 2})
        {ao1 :a {ai1 :a} :b} sample-map
        {ao2 :a {ai2 :a :as m1} :b :as m2} sample-map
        {ao3 :a {ai3 :a :as m} :b :as m} sample-map
        {{ai4 :a :as m} :b ao4 :a :as m} sample-map]
    (is (and (= 2 ai1) (= 1 ao1)))
    (is (and (= 2 ai2) (= 1 ao2)))
    (is (and (= 2 ai3) (= 1 ao3)))
    (is (and (= 2 ai4) (= 1 ao4)))))
