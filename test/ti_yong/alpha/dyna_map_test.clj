(ns ti-yong.alpha.dyna-map-test
  (:require
   [clojure.test :refer [deftest is]]
   [ti-yong.alpha.dyna-map :as dm]))


(deftest dyna-map-build-test
  (is (= (type dm/empty-dyna-map) (type (dm/dyna-map))))
  (is (= {:a 1, :b 2} (dm/dyna-map :a 1, :b 2)))
  (is (= {:a 1, :b 2} (dm/dyna-map :b 2, :a 1)))
  (is (= {:a 1, :b 2, :c 3} (dm/dyna-map :a 1, :b 2, :c 3)))
  (is (= {:a 1, :b 2, :c 3} (dm/dyna-map :c 3, :a 1, :b 2)))
  (is (= {:a 1, :b 2, :c 3} (dm/dyna-map :c 3, :b 2, :a 1)))
  (is (= {:a 1, :b 2, :c 3} (dm/dyna-map :b 2, :c 3, :a 1))))

(deftest dyna-map-arity-test
  (is (= "clojure.lang.ExceptionInfo: No default invoke added {:args nil, :env {:fcoll {}, :ti-yong.alpha.dyna-map/methods {}, :instantiated? true}}"
         (try ((dm/dyna-map)) (catch Exception e (str e)))))
  (is (= 1 ((dm/dyna-map :a 1) :a)))
  (is (= nil ((dm/dyna-map :a 1) :b)))
  (is (= "clojure.lang.ExceptionInfo: No default invoke added {:args (1 2 3), :env {:fcoll {}, :ti-yong.alpha.dyna-map/methods {}, :instantiated? true}}"
         (try ((dm/dyna-map) 1 2 3) (catch Exception e (str e)))))
  (is (= "clojure.lang.ExceptionInfo: No default invoke added {:args (1 2 3 4), :env {:fcoll {}, :ti-yong.alpha.dyna-map/methods {}, :instantiated? true}}"
         (try ((dm/dyna-map) 1 2 3 4) (catch Exception e (str e))))))

(deftest dyna-map-assoc-dissoc-test
  (is (= {:a 1, :b 2} (assoc (dm/dyna-map :a 1) :b 2)))
  (is (= (type dm/empty-dyna-map)
         (type (assoc (dm/dyna-map :a 1) :b 2))))

  (is (= {:a 1} (dissoc (dm/dyna-map :a 1 :b 2) :b)))
  (is (= (type dm/empty-dyna-map)
         (type (dissoc (dm/dyna-map :a 1 :b 2) :b))))

  (is (= {:a 1, :b 2} (merge (dm/dyna-map :a 1) {:b 2})))
  (is (= (type dm/empty-dyna-map)
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
        {ao2 :a {ai2 :a :as _m1} :b :as _m2} sample-map
        {ao3 :a {ai3 :a :as _m} :b :as _m} sample-map
        {{ai4 :a :as _m} :b ao4 :a :as _m} sample-map]
    (is (and (= 2 ai1) (= 1 ao1)))
    (is (and (= 2 ai2) (= 1 ao2)))
    (is (and (= 2 ai3) (= 1 ao3)))
    (is (and (= 2 ai4) (= 1 ao4)))))

(deftest test-dyna-map-methods
  (let [dm (dm/->DynamicMap {:a 1 :b 2} {::dm/dyna-invoke (fn [_env & args] (apply + args))})]

    (is (dm/contains-method? dm ::dm/dyna-invoke) "Should contain ::dyna-invoke method")
    (is (not (dm/contains-method? dm :non-existent)) "Should not contain :non-existent method")

    (is (fn? (dm/method dm ::dm/dyna-invoke)) "::dyna-invoke method should be a function")
    (is (nil? (dm/method dm :non-existent)) "Non-existent method should return nil")

    (let [methods (dm/get-methods dm)]
      (is (map? methods) "get-methods should return a map")
      (is (contains? methods ::dm/dyna-invoke) "Methods should contain ::dyna-invoke"))

    (let [new-methods {::new-method (fn [] "new")}
          updated-dm (dm/set-methods dm new-methods)]
      (is (dm/contains-method? updated-dm ::new-method) "Should contain new method after set-methods")
      (is (not (dm/contains-method? updated-dm ::dm/dyna-invoke)) "Should not contain old method after set-methods"))

    (is (= {:a 1 :b 2} (dm/get-coll dm)) "get-coll should return the underlying collection")

    (let [updated-dm (dm/dissoc-method dm ::dm/dyna-invoke)]
      (is (not (dm/contains-method? updated-dm ::dm/dyna-invoke)) "Method should be removed after dissoc-method"))

    (let [updated-dm (dm/assoc-method dm ::new-method (fn [] "new"))]
      (is (dm/contains-method? updated-dm ::new-method) "New method should be added after assoc-method")
      (is (= "new" ((dm/method updated-dm ::new-method))) "New method should be callable"))))
