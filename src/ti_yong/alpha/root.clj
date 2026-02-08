(ns ti-yong.alpha.root
  (:require
   [ti-yong.alpha.util :as u]
   [ti-yong.alpha.async :as async]
   [clojure.spec.alpha :as s]
   [com.jolygon.wrap-map :as w]))

;; Forward declaration for preform
(declare preform)

(defn- async-reduce
  "Like reduce, but if f returns a deferred value, chains remaining steps.
   For the sync path, this is a normal loop/recur with only a deferred? check
   per step (~5ns protocol dispatch on Object). When any step returns a deferred,
   the remainder is chained via async/then."
  [f init coll]
  (loop [acc init
         remaining (seq coll)]
    (if-not remaining
      acc
      (if (async/deferred? acc)
        ;; Gone async — chain the rest via then
        (reduce (fn [d step]
                  (async/then d #(f % step)))
                acc
                remaining)
        ;; Still sync — normal reduce step
        (recur (f acc (first remaining))
               (next remaining))))))

(defn- chain-stages
  "Execute a sequence of (fn [value] -> value-or-deferred) stages.
   If any stage returns a deferred, chain remaining stages via async/then.
   For the sync path, this is a normal loop/recur."
  [init stages]
  (loop [v init
         remaining (seq stages)]
    (if-not remaining
      v
      (if (async/deferred? v)
        ;; Gone async — chain rest
        (reduce (fn [d stage] (async/then d stage))
                v
                remaining)
        ;; Still sync
        (recur ((first remaining) v)
               (next remaining))))))

(defn transformer-invoke [original-env & args]
  (chain-stages
   (preform original-env)
   [;; Stage 1: ins + tform
    (fn [env]
      (let [combined-args (concat (:args env) args)
            tf* (update env :op #(or % u/identities))
            this (:this (:params tf*))
            tf* (merge tf* this)
            ins-fn (::ins tf* identity)
            tform-fn (::tform tf* identity)
            processed-args (if-not (seq (:in tf*))
                             combined-args
                             (ins-fn tf* combined-args))
            continue (fn [proc-args]
                       (let [arg-env (assoc tf* :args proc-args)]
                         (if-not tform-fn
                           arg-env
                           (tform-fn arg-env))))]
        (if (async/deferred? processed-args)
          (async/then processed-args continue)
          (continue processed-args))))

    ;; Stage 2: op/env-op -> outs
    (fn [tf-env]
      (let [tf-args (:args tf-env [])
            op (or (:op tf-env) u/identities)
            env-op (:env-op tf-env)
            res (if env-op (env-op tf-env) (apply op tf-args))
            outs-fn (::outs tf-env)
            continue (fn [r]
                       (let [out-res (if-not outs-fn
                                      r
                                      (outs-fn (assoc tf-env :args tf-args :res r) r))]
                         (if (async/deferred? out-res)
                           (async/then out-res #(assoc tf-env :args tf-args :res %))
                           (assoc tf-env :args tf-args :res out-res))))]
        (if (async/deferred? res)
          (async/then res continue)
          (continue res))))

    ;; Stage 3: tf-end -> extract :res
    (fn [res-env]
      (let [tform-end-fn (::tform-end res-env identity)
            end-env (if-not tform-end-fn
                      res-env
                      (tform-end-fn res-env))]
        (if (async/deferred? end-env)
          (async/then end-env :res)
          (:res end-env))))]))

(defn ins [env current-args]
  (let [pipeline (u/uniq-by-pairwise-first (:in env))]
    (if-not (seq pipeline)
      current-args
      (async-reduce (fn [acc-args in-fn]
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
  (let [tf-pre-data (:tf-pre env)]
    (if (not (seq tf-pre-data))
      env
      (async-reduce (fn [current-env [_id tf-fn]]
                      (tf-fn current-env))
                    env
                    (u/uniq-by first (partition 2 tf-pre-data))))))

(defn tform [env]
  (if-not (seq (:tf env))
    env
    (let [pipeline (u/uniq-by-pairwise-first (:tf env))]
      (if-not (seq pipeline)
        env
        (async-reduce (fn [current-env tf-fn]
                        ((or tf-fn identity) current-env))
                      env
                      pipeline)))))

(defn endform [env]
  (let [pipeline (u/uniq-by-pairwise-first (:tf-end env))]
    (if-not (seq pipeline)
      env
      (async-reduce (fn [current-env tf-fn]
                      ((or tf-fn identity) current-env))
                    env
                    pipeline))))

(defn outs [{:as env-with-res} current-res]
  (let [pipeline (u/uniq-by-pairwise-first (:out env-with-res))]
    (if-not (seq pipeline)
      current-res
      (async-reduce (fn [acc-res out-fn]
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
      (w/assoc :invoke transformer-invoke
               :assoc (fn [m k v]
                        (let [new-m (assoc m k v)]
                          (if-let [excluded (::excluded-keys new-m)]
                            (if (excluded k)
                              (assoc new-m ::excluded-keys (disj excluded k))
                              new-m)
                            new-m)))
               :dissoc (fn [m k]
                         (-> (dissoc m k)
                             (update ::excluded-keys (fnil conj #{}) k))))))
