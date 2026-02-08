(ns ti-yong.alpha.transformer-with-test
  (:require
   [clojure.test :refer [deftest is]]
   [ti-yong.alpha.transformer :as t]
   [ti-yong.alpha.root :as r] ;; Required for ti-yong.alpha.root/preform
   [ti-yong.alpha.util :as u])) ;; Required by t/transformer's default op indirectly

(defn with-transformer-for-key [k n & mixins]
  (-> t/transformer
      (update :id conj k)
      (assoc k n) ;; assoc the actual key-value pair for data
      (update :with into mixins)
      (update :tf conj k (fn [env] env)))) ; No println for cleaner test runs

(deftest transformer-with-test
  (let [a (with-transformer-for-key ::a 1)
        b (with-transformer-for-key ::b 2 a)
        c (with-transformer-for-key ::c 3 b)
        x (with-transformer-for-key ::x 7 a c)
        y (with-transformer-for-key ::y 8 c x b a)
        z (with-transformer-for-key ::z 9 y)
        r-uninvoked (with-transformer-for-key ::r 10 z c)
        ;; ti-yong.alpha.root/preform is the function that processes :tf-pre,
        ;; including the ::with transform from ti-yong.alpha.transformer.
        env-after-preform (r/preform r-uninvoked)]

    ;; :with is processed left-to-right (natural order), base merged last.
    ;; Dependencies appear before dependents; last-wins dedup determines final position.
    (is (= :root|transformer|z|y|c|b|a|x|r
           (->> env-after-preform :id (mapv name) (interpose "|") (apply str) keyword)))
    (is (= {::a 1, ::b 2, ::c 3, ::x 7, ::y 8, ::z 9, ::r 10}
           (select-keys env-after-preform [::a ::b ::c ::x ::y ::z ::r])))
    (is (= [:z :y :x :c :b :a :r]
           (->> env-after-preform :tf (partition 2) (map first) (map name) (map keyword) vec)))))
