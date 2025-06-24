(ns ti-yong.alpha.transformer-spec-test
  (:require
   [clojure.spec.alpha :as s]
   [clojure.test :refer [deftest is]]
   [ti-yong.alpha.transformer :as t]
   [ti-yong.alpha.root :as r]
   [com.jolygon.wrap-map :as w]) ; Added w
  (:import com.jolygon.wrap_map.api_0.impl.WrapMap))

(deftest wrap-map-assoc-preserves-type-test
  (let [wm (w/wrap {:original :data}) ; Simple WrapMap, no custom methods needed for this test
        wm-assoc (assoc wm :new-key :new-val)]
    (is (instance? WrapMap wm-assoc)
        "assoc on a WrapMap should return a WrapMap")
    (is (= :new-val (:new-key wm-assoc)))
    (is (= :data (:original wm-assoc)))))

;; Specs used in the test
(s/def :ti-yong.alpha.transformer-spec-test/a int?)
(s/def ::a-spec (s/keys :req-un [:ti-yong.alpha.transformer-spec-test/a]))
(s/def :ti-yong.alpha.transformer-spec-test/b int?)
(s/def ::b-spec (s/keys :req-un [:ti-yong.alpha.transformer-spec-test/b]))
(s/def :ti-yong.alpha.transformer-spec-test/c int?)
(s/def ::c-spec (s/keys :req-un [:ti-yong.alpha.transformer-spec-test/c]))
(s/def :ti-yong.alpha.transformer-spec-test/x int?)
(s/def ::x-spec (s/keys :req-un [:ti-yong.alpha.transformer-spec-test/x]))
(s/def :ti-yong.alpha.transformer-spec-test/y int?)
(s/def ::y-spec (s/keys :req-un [:ti-yong.alpha.transformer-spec-test/y]))
(s/def :ti-yong.alpha.transformer-spec-test/z int?)
(s/def ::z-spec (s/keys :req-un [:ti-yong.alpha.transformer-spec-test/z]))
(s/def :ti-yong.alpha.transformer-spec-test/r int?)
(s/def ::r-spec (s/keys :req-un [:ti-yong.alpha.transformer-spec-test/r]))

(defn with-transformer-for-spec [kname k n sspec & mixins]
  (-> t/transformer
      (update :id conj k) ;; k is the id like ::a
      (update :with into mixins)
      (assoc kname n) ;; kname is the keyword like :a, n is its value
      (update :specs conj k sspec) ;; k is the id, sspec is the spec name like ::a-spec
      (update :tf conj k (fn [env] env)))) ;; Add a dummy tf for this key for completeness if needed

(deftest transformer-spec-test
  ;; Specs are checked by `spec-tf` (a :tf-pre function within preform)
  ;; when the transformer is invoked.
  (is (instance? WrapMap t/transformer) "t/transformer should be a WrapMap")

  (let [a-valid (with-transformer-for-spec :a ::a 1 ::a-spec)
        _ (is (instance? WrapMap a-valid) "a-valid should be a WrapMap")
        a-invalid-data (dissoc a-valid :a) ; :a is the data key, now removed

        b-valid (with-transformer-for-spec :b ::b 2 ::b-spec a-valid)

        x-valid (with-transformer-for-spec :x ::x 7 ::x-spec a-valid)
        _ (is (instance? WrapMap x-valid) "x-valid should be a WrapMap")
        x-invalid-data-a (dissoc x-valid :a) ; Remove :a, should fail ::a-spec (inherited)
        x-invalid-data-x (dissoc x-valid :x) ; Remove :x, should fail ::x-spec

        r-valid (with-transformer-for-spec :r ::r 10 ::r-spec b-valid x-valid)
        _ (is (instance? WrapMap r-valid) "r-valid should be a WrapMap")
        r-invalid-data-a (dissoc r-valid :a) ; Remove :a, inherited, should fail ::a-spec
        r-invalid-data-r (dissoc r-valid :r)] ; Remove :r, should fail ::r-spec

    (is (any? (a-valid))) ; Invoking valid transformer should not throw
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":ti-yong.alpha.transformer-spec-test/a" (a-invalid-data)))

    (is (any? (x-valid)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":ti-yong.alpha.transformer-spec-test/a" (x-invalid-data-a)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":ti-yong.alpha.transformer-spec-test/x" (x-invalid-data-x)))

    (is (any? (r-valid)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":ti-yong.alpha.transformer-spec-test/a" (r-invalid-data-a)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":ti-yong.alpha.transformer-spec-test/r" (r-invalid-data-r)))
    ))
