(ns ti-yong.alpha.root
  (:require
   [ti-yong.alpha.util :as u]
   [clojure.spec.alpha :as s]
   [com.jolygon.wrap-map :as w])) ; Removed :refer
  ;; (:import com.jolygon.wrap_map.api_0.impl.WrapMap)) ; For instance? check - REMOVING DIAGNOSTICS

;; Forward declaration for preform
(declare preform)

(defn transformer-invoke [original-env & args]
  (let [env (if (:instantiated? original-env)
              original-env
              (preform original-env))
        ;; Ensure args from the env are combined with passed-in args
        combined-args (concat (:args env) args)
        tf* (update env :op #(or % u/identities))
        ;; `this` and `params` are not standard wrap-map concepts,
        ;; assuming they are part of the `env` structure for ti-yong's logic
        this (:this (:params tf*))
        tf* (merge tf* this)

        ins-fn (::ins tf* identity)
        tform-fn (::tform tf* identity)
        outs-fn (::outs tf*)
        tform-end-fn (::tform-end tf* identity)

        processed-args (if-not (seq (:in tf*))
                         combined-args
                         (ins-fn tf* combined-args))

        arg-env (assoc tf* :args processed-args)

        tf-env (if-not tform-fn
                 arg-env
                 (tform-fn arg-env))

        tf-args (:args tf-env [])
        op (or (:op tf-env) u/identities)
        env-op (:env-op tf-env)

        run-op (if env-op
                 (partial env-op tf-env) ;; If env-op exists, it takes the whole env
                 (partial op)) ;; Otherwise, op takes tf-args

        res (if env-op
              (run-op) ;; env-op is called with no args as tf-env is curried
              (apply run-op tf-args)) ;; op is applied to tf-args

        out-res (if-not outs-fn
                  res
                  (outs-fn (assoc tf-env :args tf-args :res res) res))

        res-env (assoc tf-env :args tf-args :res out-res)

        end-env (if-not tform-end-fn
                  res-env
                  (tform-end-fn res-env))

        new-res (:res end-env)]
    new-res))

(defn ins [env current-args]
  (let [pipeline (u/uniq-by-pairwise-first (:in env))] ;; Returns [fn1 fn2 ...]
    (if-not (seq pipeline)
      current-args
      (reduce (fn [acc-args in-fn] ;; in-fn is now the function
                ((or in-fn identity) acc-args))
              current-args
              pipeline))))

;; Specs remain largely the same, as they describe the data structure (env)
;; processed by transformer-invoke and its helper functions.
;; The ::root spec might need adjustment based on how preform is integrated.
(s/def ::id vector?)
(s/def ::args vector?)
(s/def ::tform-pre fn?) ;; This is the preform function itself
(s/def ::tf-pre vector?) ;; This is the data vector of [id fn] pairs for pre-processing
(s/def ::ins fn?)
(s/def ::in vector?)
(s/def ::tform fn?)
(s/def ::tf vector?)
(s/def ::outs fn?)
(s/def ::out vector?)
(s/def ::tform-end fn?)
(s/def ::tf-end vector?)

(s/def ::root-data ; Renamed to avoid conflict with the `root` var
  (s/keys :req [::tform-pre ::ins ::tform ::outs ::tform-end]
          :req-un [::id ::args ::tf-pre ::in ::tf ::out ::tf-end]))

(defn preform [env]
  (when-not (s/valid? ::root-data env)
    (throw
     (let [edata (s/explain-data ::root-data env)
           problems (:clojure.spec.alpha/problems edata)]
       (ex-info
        (str "Invalid env for preform: " (seq problems) "\n\n env:" env #_#_ " edata:" edata)
        {:error :invalid-env-preform
         :problems problems
         :env env}))))
  (let [tf-pre-data (:tf-pre env) ;; This is the vector of [id fn] pairs
        initialized-set (or (get env :init-set) #{})]
    (if (or (:instantiated? env) (not (seq tf-pre-data)))
      (assoc env :instantiated? true) ;; Mark as instantiated even if no tf-pre
      (let [processed-env (reduce
                           (fn [current-env [id tf-fn]]
                             (if-not (initialized-set id)
                               (let [next-env (tf-fn current-env)] ; result of spec-tf or with-tf
                                 (-> next-env
                                     (assoc :done-pre true)
                                     (update :init-set (fnil conj #{}) id)))
                               current-env))
                           env
                           (u/uniq-by first (partition 2 tf-pre-data)))]
        (assoc processed-env :instantiated? true)))))

(defn tform [env]
  (if-not (seq (:tf env))
    env
    (let [pipeline (u/uniq-by-pairwise-first (:tf env))]
      (if-not (seq pipeline)
        env
        (reduce (fn [current-env tf-fn]
                  ((or tf-fn identity) current-env))
                env
                pipeline)))))

(defn endform [env]
  (let [pipeline (u/uniq-by-pairwise-first (:tf-end env))]
    (if-not (seq pipeline)
      env
      (reduce (fn [current-env tf-fn]
                ((or tf-fn identity) current-env))
              env
              pipeline))))

(defn outs [{:as env-with-res} current-res]
  (let [pipeline (u/uniq-by-pairwise-first (:out env-with-res))] ;; Returns [fn1 fn2 ...]
    (if-not (seq pipeline)
      current-res
      (reduce (fn [acc-res out-fn] ;; out-fn is now the function
                ((or out-fn identity) acc-res))
              current-res
              pipeline))))


(def root
  (-> (w/wrap { ;; Initial data for the root transformer
               :id [::root]
               :args []
               :tf-pre [] ;; Data for pre-processing steps
               ::ins ins
               :in []
               ::tform tform
               :tf []
               ::outs outs
               :out []
               ::tform-end endform
               :tf-end []
               ;; ::tform-pre is the function preform itself,
               ;; it's called by transformer-invoke, not stored as data in the same way.
               ;; However, the spec ::root-data expects it.
               ;; We ensure preform is available in the calling context (transformer-invoke).
               ;; For spec validation within preform itself, we pass the env.
               ;; Let's add the preform function itself to the map if needed by spec validation elsewhere,
               ;; or adjust spec. For now, transformer-invoke calls preform directly.
               ::tform-pre preform ;; Storing the function itself for spec validation if needed by other parts
              })
      (w/assoc :invoke transformer-invoke)))
