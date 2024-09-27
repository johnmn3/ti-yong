(ns ti-yong.alpha.transformer-test
  (:require
   [clojure.spec.alpha :as s]
   [clojure.test :refer [deftest is]]
   [ti-yong.alpha.dyna-map :as dm]
   [ti-yong.alpha.transformer :as t]
   [ti-yong.alpha.util :as u]))

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
  (is (= (type dm/empty-dyna-map) (type t/transformer)))
  (is (= {:a 1, :b 2} (t/transformer {:b 2, :a 1}))))

(deftest root-op-test
  (is (= 3 (-> t/transformer (assoc :op +) (apply [1 2]))))
  (is (= -4 (-> t/transformer (assoc :op -) (apply [1 2 3]))))
  (is (= 24 (-> t/transformer (assoc :op *) (apply [1 2 3 4]))))
  (is (= 1/24 (-> t/transformer (assoc :op /) (apply [1 2 3 4]))))
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
               (assoc :env-op (fn [env & _r] env))
               (update :in conj ::root-in-test-4.1 #(mapv (partial * 3) %))
               (apply args)
               :args
               vec))))
  (is (= ["hello" "world"]
         (let [args [:hello :world]]
           (-> t/transformer
               (assoc :env-op (fn [env & _r] env))
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
#_ (::a (with-transformer-for-key ::a 1)) ;=> 1
#_ (count (with-transformer-for-key ::a 1)) ;=> 16


(deftest transformer-with-test
  (let [a (with-transformer-for-key ::a 1)
        b (with-transformer-for-key ::b 2 a)
        c (with-transformer-for-key ::c 3 b)
        x (with-transformer-for-key ::x 7 a c)
        y (with-transformer-for-key ::y 8 c x b a)
        z (with-transformer-for-key ::z 9 y)
        r (with-transformer-for-key ::r 10 z c)]
    (is (= :root|transformer|r|c|b|a ; :root|transformer|a|b|c|x|y|z|r ; <- fix these to aligh with cljs version
           (->> r :id (mapv name) (interpose "|") (apply str) keyword)))
    (is (= #::{:r 10, :c 3, :b 2, :a 1} ; #::{:r 10, :z 9, :x 7, :y 8, :c 3, :b 2, :a 1}
           (select-keys r [::r ::z ::x ::y ::c ::b ::a])))
    (is (= [:a :b :c :r] ; [:a :b :c :x :y :z :r]
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
    (is (= ":tf-pre\n:tf-pre\n:tf-pre\n:tf-pre\n:tf-pre\n:tf-pre\n:tf-pre\n:tf-pre\n:in\n:tf\n:out\n:tf-end\n"
           (with-out-str (-> x 
                             (assoc :a 1 :b 2 :c 3 :d 4 :e 5)
                             (apply 1 [2 3])))))))

(defn with-transformer-for-spec [kname k n sspec & mixins]
  (-> t/transformer
      (update :id conj k)
      (update :with into mixins)
      (assoc kname n)
      (update :specs conj k sspec)
      (update :tf conj k (fn [env] (println ::tf k :env env) env))))

(s/def ::a int?)
(s/def ::a-spec (s/keys :req-un [::a]))
(s/def ::b int?)
(s/def ::b-spec (s/keys :req-un [::b]))
(s/def ::c int?)
(s/def ::c-spec (s/keys :req-un [::c]))
(s/def ::x int?)
(s/def ::x-spec (s/keys :req-un [::x]))
(s/def ::y int?)
(s/def ::y-spec (s/keys :req-un [::y]))
(s/def ::z int?)
(s/def ::z-spec (s/keys :req-un [::z]))
(s/def ::r int?)
(s/def ::r-spec (s/keys :req-un [::r]))

(deftest transformer-spec-test
  (let [a (with-transformer-for-spec :a ::a 1 ::a-spec)
        b (with-transformer-for-spec :b ::b 2 ::b-spec a)
        c (with-transformer-for-spec :c ::c 3 ::c-spec b)
        x (with-transformer-for-spec :x ::x 7 ::x-spec a c)
        y (with-transformer-for-spec :y ::y 8 ::y-spec a b c x)
        z (with-transformer-for-spec :z ::z 9 ::z-spec y)
        r (with-transformer-for-spec :r ::r 10 ::r-spec c z)]
    (is (= {:failed ".lang.ExceptionInfo: :ti-yong.alpha.transformer-test/a-spec"}
           (try (dissoc a :a)
                (catch Exception e
                  {:failed (->> e str (drop 7) (take 59) (apply str))}))))

    (is (= {:failed ".lang.ExceptionInfo: :ti-yong.alpha.transformer-test/a-spec"}
           (try (dissoc x :a)
                (catch Exception e
                  {:failed (->> e str (drop 7) (take 59) (apply str))}))))
    (is (= {:failed ".lang.ExceptionInfo: :ti-yong.alpha.transformer-test/x-spec"}
           (try (dissoc x :x)
                (catch Exception e
                  {:failed (->> e str (drop 7) (take 59) (apply str))}))))

    (is (= {:failed ".lang.ExceptionInfo: :ti-yong.alpha.transformer-test/a-spec"}
           (try (dissoc r :a)
                (catch Exception e
                  {:failed (->> e str (drop 7) (take 59) (apply str))}))))
    (is (= {:failed ".lang.ExceptionInfo: :ti-yong.alpha.transformer-test/r-spec"}
           (try (dissoc r :r)
                (catch Exception e
                  {:failed (->> e str (drop 7) (take 59) (apply str))}))))))
