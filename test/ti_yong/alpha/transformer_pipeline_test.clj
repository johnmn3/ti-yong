(ns ti-yong.alpha.transformer-pipeline-test
  (:require
   [clojure.test :refer [deftest is]]
   [ti-yong.alpha.transformer :as t]
   [ti-yong.alpha.root :as r])) ; For r/preform if needed, though t/transformer uses it.

(deftest transformer-pipeline-test
  (let [base-transformer (assoc t/transformer :op +) ; Base op for the test
        x (-> base-transformer
              (assoc :x 1 :y 2) ; Data, not directly used by op unless :in processes it
              (update :tf-pre conj ::tf-pre (fn [env] (print ":tf-pre") env))
              (update :in conj ::in (fn [args] (print ":in") args))
              (update :tf conj ::tf (fn [env] (print ":tf") env))
              ;; :op is already +
              (update :out conj ::out (fn [res] (print ":out") res))
              (update :tf-end conj ::tf-end (fn [env] (print ":tf-end") env)))]

    ;; (assoc x :o2 2) creates a new transformer but doesn't invoke it, so no prints from pipeline.
    ;; Preform might run if assoc is overridden to call it, but default wrap-map assoc doesn't.
    ;; Default t/transformer has :tf-pre for ::spec and ::with.
    ;; When (assoc x :o2 2) is created, and if that new map were *invoked*,
    ;; its preform would run those. However, simple assoc does not invoke.
    (is (= "" (with-out-str (assoc x :o2 2))))

    ;; (x 1 2 3) invokes the transformer.
    ;; 1. preform runs (all :tf-pre from t/transformer (::spec, ::with) and our added ::tf-pre).
    ;;    Our added ::tf-pre prints ":tf-pre".
    ;; 2. Then :in prints ":in".
    ;; 3. Then :tf prints ":tf".
    ;; 4. Then :op (+) executes.
    ;; 5. Then :out prints ":out".
    ;; 6. Then :tf-end prints ":tf-end".
    (is (= ":tf-pre:in:tf:out:tf-end"
           (with-out-str (x 1 2 3))))

    ;; (-> x (assoc :a 1...) (apply [1 2 3]))
    ;; Assocs create intermediate transformers. None are invoked until `apply`.
    ;; When `apply` calls the final transformer, preform runs once on that final state.
    (is (= ":tf-pre:in:tf:out:tf-end"
           (with-out-str (-> x
                             (assoc :a 1 :b 2 :c 3 :d 4 :e 5)
                             (apply [1 2 3])))))))
