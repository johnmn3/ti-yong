(ns ti-yong.alpha.root
  (:require
   [ti-yong.alpha.util :as u]
   [clojure.spec.alpha :as s]
   [ti-yong.alpha.dyna-map :as dm
    :refer [dyna-map assoc-method]]))

(defn transformer-invoke [env & args]
  (let [args (concat (:args env) args)
        tf* (update env :op #(or % u/identities))
        this (:this (:params tf*))
        tf* (merge tf* this)
        ins (::ins tf* identity)
        tform (::tform tf* identity)
        outs (::outs tf*)
        tform-end (::tform-end tf* identity)
        argss (if-not (seq (:in tf*))
                args
                (ins tf* args))
        arg-env (merge tf* (assoc tf* :args argss))
        tf-env (if-not tform
                 arg-env
                 (tform arg-env))
        tf-args (:args tf-env [])
        op (or (:op tf-env) u/identities)
        env-op (:env-op tf-env)
        run-op (if env-op
                 (partial env-op tf-env)
                 op)
        res (apply run-op tf-args)
        out-res (if-not outs
                  res
                  (outs (assoc tf-env :args tf-args :res res) res))
        res-env (assoc tf-env :args tf-args :res out-res)
        end-env (if-not tform-end
                  res-env
                  (tform-end res-env))
        new-res (:res end-env)]
    new-res))

(defn ins [env args]
  (some->> env :in u/uniq-by-pairwise-first (reduce (fn [argss in] ((or in identity) argss)) args) seq))

(s/def ::id vector?)
(s/def ::args vector?)
(s/def ::tform-pre fn?)
(s/def ::tf-pre vector?)
(s/def ::ins fn?)
(s/def ::in vector?)
(s/def ::tform fn?)
(s/def ::tf vector?)
(s/def ::outs fn?)
(s/def ::out vector?)
(s/def ::tform-end fn?)
(s/def ::tf-end vector?)

(s/def ::root
  (s/keys :req [::tform-pre ::ins ::tform ::outs ::tform-end]
          :req-un [::id ::args ::tf-pre ::in ::tf ::out ::tf-end]))

(defn preform [env]
  (when-not (s/valid? ::root env)
    (throw
     (let [edata (s/explain-data ::root env)
           problems (:clojure.spec.alpha/problems edata)]
       (ex-info
        (str "Invalid env: " (seq problems) "\n\n env:" env #_#_ " edata:" edata)
        {:error :invalid-env
         :problems problems
         :env env}))))
  (let [tf-pre (:tf-pre env)
        initialized-set (or (get env :init-set) #{})
        meths (::dm/methods env)]
    (if (or (:instantiated? env) (not (seq tf-pre)))
      (dissoc env :instantiated?)
      (let [pre-env (some->> tf-pre
                             (partition 2)
                             (u/uniq-by first)
                             (reduce (fn [e [id tf]]
                                       (if-not (initialized-set id)
                                         (let [res (tf e)]
                                           (-> res (assoc :done-pre true)))
                                         e))
                                     env)
                             (mapv vec)
                             (into {}))]
        (-> (or pre-env (into {} env))
            (assoc ::dm/methods meths))))))

(defn tform [env]
  (if-not (seq (:tf env))
    env
    (let [tf-env (some->> env :tf u/uniq-by-pairwise-first (reduce (fn [e tf] (tf e)) env) (into {}))]
      (if (seq tf-env)
        tf-env
        env))))

(defn endform [env]
  (let [end-env (some->> env :tf-end u/uniq-by-pairwise-first (reduce (fn [e tf] (tf e)) env) (mapv vec) (into {}))]
    (or end-env env)))

(defn outs [{:as env} args]
  (->> env
       :out
       u/uniq-by-pairwise-first
       (reduce (fn [argss out]
                 ((or out identity)
                  argss))
               args)))

(def root
  (let [r (dyna-map
           :id [::root]
           :args []
           :tf-pre []
           ::ins ins
           :in []
           ::tform tform
           :tf []
           ::outs outs
           :out []
           ::tform-end endform
           :tf-end []
           ::tform-pre preform)]
    (-> r (assoc-method ::dm/dyna-invoke transformer-invoke))))
