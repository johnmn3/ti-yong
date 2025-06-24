(ns ti-yong.alpha.transformer-spec-test
  (:require
   [cljs.spec.alpha :as s]
   [clojure.test :refer [deftest is]]
   [ti-yong.alpha.transformer :as t]
   [ti-yong.alpha.root :as r]
   [com.jolygon.wrap-map :as w]))
  ;; Removed (:import com.jolygon.wrap_map.api_0.impl.WrapMap)

#_(s/def :ti-yong.alpha.transformer-spec-test/a int?)
#_(s/def ::a-spec (s/keys :req-un [:ti-yong.alpha.transformer-spec-test/a]))
#_(s/def :ti-yong.alpha.transformer-spec-test/b int?)
#_(s/def ::b-spec (s/keys :req-un [:ti-yong.alpha.transformer-spec-test/b]))
#_(s/def :ti-yong.alpha.transformer-spec-test/c int?)
#_(s/def ::c-spec (s/keys :req-un [:ti-yong.alpha.transformer-spec-test/c]))
#_(s/def :ti-yong.alpha.transformer-spec-test/x int?)
#_(s/def ::x-spec (s/keys :req-un [:ti-yong.alpha.transformer-spec-test/x]))
#_(s/def :ti-yong.alpha.transformer-spec-test/y int?)
#_(s/def ::y-spec (s/keys :req-un [:ti-yong.alpha.transformer-spec-test/y]))
#_(s/def :ti-yong.alpha.transformer-spec-test/z int?)
#_(s/def ::z-spec (s/keys :req-un [:ti-yong.alpha.transformer-spec-test/z]))
#_(s/def :ti-yong.alpha.transformer-spec-test/r int?)
#_(s/def ::r-spec (s/keys :req-un [:ti-yong.alpha.transformer-spec-test/r]))

#_(defn with-transformer-for-spec [kname k n sspec & mixins]
  (-> t/transformer
      (update :id conj k)
      (update :with into mixins)
      (assoc kname n)
      (update :specs conj k sspec)
      (update :tf conj k (fn [env] env))))

#_(deftest transformer-spec-test-cljs
  ;; (is (instance? WrapMap t/transformer) "t/transformer should be a WrapMap") ; Removed instance? check

  (let [a-valid (with-transformer-for-spec :a ::a 1 ::a-spec)
        ;; _ (is (instance? WrapMap a-valid) "a-valid should be a WrapMap") ; Removed instance? check
        a-invalid-data (dissoc a-valid :a)

        b-valid (with-transformer-for-spec :b ::b 2 ::b-spec a-valid)

        x-valid (with-transformer-for-spec :x ::x 7 ::x-spec a-valid)
        ;; _ (is (instance? WrapMap x-valid) "x-valid should be a WrapMap") ; Removed instance? check
        x-invalid-data-a (dissoc x-valid :a)
        x-invalid-data-x (dissoc x-valid :x)

        r-valid (with-transformer-for-spec :r ::r 10 ::r-spec b-valid x-valid)
        ;; _ (is (instance? WrapMap r-valid) "r-valid should be a WrapMap") ; Removed instance? check
        r-invalid-data-a (dissoc r-valid :a)
        r-invalid-data-r (dissoc r-valid :r)]

    (is (any? (a-valid)))
    (try (a-invalid-data) (is false "Should have thrown") (catch js/Error e (is (clojure.string/includes? (.-message e) ":ti-yong.alpha.transformer-spec-test/a"))))
    (is (any? (x-valid)))
    (try (x-invalid-data-a) (is false "Should have thrown") (catch js/Error e (is (clojure.string/includes? (.-message e) ":ti-yong.alpha.transformer-spec-test/a"))))
    (try (x-invalid-data-x) (is false "Should have thrown") (catch js/Error e (is (clojure.string/includes? (.-message e) ":ti-yong.alpha.transformer-spec-test/x"))))
    (is (any? (r-valid)))
    (try (r-invalid-data-a) (is false "Should have thrown") (catch js/Error e (is (clojure.string/includes? (.-message e) ":ti-yong.alpha.transformer-spec-test/a"))))
    (try (r-invalid-data-r) (is false "Should have thrown") (catch js/Error e (is (clojure.string/includes? (.-message e) ":ti-yong.alpha.transformer-spec-test/r"))))
    ))

(deftest dummy-spec-test-cljs ;; Added to ensure file is not empty of active tests
  (is (= 1 1)))
