(ns ti-yong.alpha.root
  (:require
   [ti-yong.alpha.util :as u]
   [cljs.spec.alpha :as s]
   [com.jolygon.wrap-map :as w])) ;; Ensure no :refer for assoc here either

;; Forward declaration for preform
(declare preform)

(defn transformer-invoke [original-env & args]
  (let [env (preform original-env)
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
    (throw (js/Error. (str "Invalid env for preform: " (:cljs.spec.alpha/problems (s/explain-data ::root-data env))))))
  (let [tf-pre-data (:tf-pre env)]
    (if (not (seq tf-pre-data))
      env
      (reduce (fn [current-env [_id tf-fn]]
                (tf-fn current-env))
              env
              (u/uniq-by first (partition 2 tf-pre-data))))))

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
               ::tform-pre preform ;; Storing the function itself
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

(comment
  ;; Commented out dyna-map specific requires and examples
  ;; (require '[ti-yong.alpha.dyna-map :refer [contains-method? method get-methods]])

  ;; (dissoc root :args) ;=> :repl/exception!
  ;; (dyna-map :a 1) ;=> {:a 1}
  ;; (type (dyna-map :a 1)) ;=> ti-yong.alpha.dyna-map/PersistentDynamicMap
  ;; (contains-method? root ::dm/dyna-invoke)
  ;; (method root ::dm/dyna-invoke)
  ;; (get-methods root)
  root
  (root) ; Invokes transformer-invoke, which calls preform, then proceeds. Expect behavior based on default root data.
  ;; (type root) ;=> com.jolygon.wrap-map/WrapMap (or similar, depending on wrap-map's internal types)
  (root 1) ;=> Calls (transformer-invoke root 1) -> get behavior if not overridden by :invoke
  (root :tf-pre) ;=> Value of :tf-pre in the root map
  (root :tf-pre-blah :not-found-here) ;=> :not-found-here
  ;; (root 1 2 3) ;=> This will call (transformer-invoke root 1 2 3)

  (def r1 (-> root (assoc :op +)))
  r1
  ;; (type r1) ;=> com.jolygon.wrap-map/WrapMap
  (r1) ;=> 0
  (r1 1 2 3 4) ;=> 10
  (r1 1) ;=> 1
  ;; (r1 1 2 3 [4 5]) ;=> This behavior might change based on how + handles mixed args or if :in transforms them.
                     ;; Assuming + is clojure.core/+ and no :in transforms, it would error on [4 5].
                     ;; If an :in transform processes args into a flat list of numbers, it would sum them.
                     ;; Given current root, no :in transforms, so + would be applied to (1 2 3 [4 5]), likely erroring.
  (apply r1 1 2 3 [4 5]) ;=> Assuming + and default :in, this would be like (apply + 1 2 3 [4 5]) -> error.
                         ;; If :in processed [4 5] into 4 5, then (apply + 1 2 3 4 5) -> 15
  ;; Performance comparison would still be relevant.
  ;; (time (apply r1 1 2 3 (range 100000)))
  ;; (time (apply + 1 2 3 (range 100000)))


  (def x
    (assoc root
           :op +
           :tf-pre [::x-tf-pre (fn [env] (println :x-tf-pre env) env)]
           :in     [::x-in     (fn [args] (println :x-in args) args)] ;; :in fns take args
           :tf     [::x-tf     (fn [env] (println :x-tf env) env)]
           :out    [::x-out    (fn [res] (println :x-out res) res)] ;; :out fns take result
           :tf-end [::x-tf-end (fn [env] (println :x-tf-end env) env)]))

  x
  ;; (type x) ;=> com.jolygon.wrap-map/WrapMap
  (x) ;=> 0 (prints lifecycle messages)
  (apply x [1]) ;=> 1
  (apply x 1 [2]) ;=> 3
  (x 1 2 4) ;=> 7
  (apply x 1 2 3 (range 25)) ;=> 306 (sum of 1,2,3 and 0..24)
  (apply x 1 (range 5)) ;=> 11 (sum of 1 and 0..4)

  (def y (assoc x :a 1 :b 2))
  y
  (y 1 2) ;=> 3

  (apply y 1 (range 5)) ;=> 11

  (def z (assoc y :r 1 :k 2))
  z
  (z 1 2) ;=> 3
  (apply z 1 (range 25)) ;=> 301

  root
  (root) ; Should now invoke transformer-invoke and likely return nil or based on empty :args and default :op
  (def a+ (assoc root :op +))
  (apply a+ 1 2 [3 4]) ; If :in processes [3 4] to 3 4, then 10. Otherwise error.
                       ; Current `ins` function takes env and current-args.
                       ; The :in vector contains [id fn] where fn takes acc-args.
                       ; Default `ins` will pass current-args through if :in is empty.
                       ; So, (apply + 1 2 [3 4]) -> error.

  (def x+ (assoc a+ :x 1 :y 2)) ; :x and :y are just data here, not directly used by + unless :in processes them.

  x+
  ;; (type x+)
  (x+) ;=> 0
  (x+ 1) ;=> 1
  (x+ 1 2) ;=> 3
  (apply x+ 1 2 (range 23)) ;=> 256
  (apply x+ 1 2 (range 2)) ;=> 4
  (apply x+ [2 1]) ;=> 3 (assuming clojure.core/+ which sums elements of a single seq arg)

  :end)
