(ns ti-yong.alpha.transformer-with-test
  (:require
   [cljs.spec.alpha :as s]
   [clojure.test :refer [deftest is]]
   [ti-yong.alpha.transformer :as t]
   [ti-yong.alpha.root :as r]
   [ti-yong.alpha.util :as u]
   [com.jolygon.wrap-map :as w]))

#_(defn with-transformer-for-key [k n & mixins]
  (-> t/transformer
      (update :id conj k)
      (assoc k n)
      (update :with into mixins)
      (update :tf conj k (fn [env] env))))

#_(deftest transformer-with-test-cljs
  (let [a (with-transformer-for-key ::a 1)
        b (with-transformer-for-key ::b 2 a)
        c (with-transformer-for-key ::c 3 b)
        x (with-transformer-for-key ::x 7 a c)
        y (with-transformer-for-key ::y 8 c x b a)
        z (with-transformer-for-key ::z 9 y)
        r-uninvoked (with-transformer-for-key ::r 10 z c)
        env-after-preform (r/preform r-uninvoked)]

    (is (= :root|transformer|r|c|b|a|z|y|x
           (->> env-after-preform :id (mapv name) (interpose "|") (apply str) keyword)))
    (is (= {::a 1, ::b 2, ::c 3, ::x 7, ::y 8, ::z 9, ::r 10}
           (select-keys env-after-preform [::a ::b ::c ::x ::y ::z ::r])))
    (is (= [:r :c :b :a :z :y :x]
           (->> env-after-preform :tf (partition 2) (map first) (map name) (map keyword) vec)))))
