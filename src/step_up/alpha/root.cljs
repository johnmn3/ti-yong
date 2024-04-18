(ns step-up.alpha.root
  (:require
   [step-up.alpha.util :as u]
   [step-up.alpha.dyna-map :as dm
    :refer [dyna-map assoc-method
            ;; method contains-method? dissoc-method
            get-methods set-methods]]))

(defn transformer-invoke [env args]
  (let [args (concat (:args env) args)
        tf* (update env :op #(or % u/identities))
        this (:this (:params tf*))
        tf* (merge tf* this)
        ins (::dm/ins tf* identity)
        tform (::dm/tform tf* identity)
        outs (::dm/outs tf*)
        tform-end (::dm/tform-end tf* identity)
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

(defn preform [env]
  (let [meths (or (get-methods env) (:m env))]
    (if-not (seq (:tf-pre env))
      env
      (let [pre-env (some->> env
                             :tf-pre
                             u/uniq-by-pairwise-first
                             (reduce (fn [e tf] (tf e)) env)
                             (mapv vec)
                             (into {})
                             (#(dissoc % :tf-pre))
                             (merge dm/empty-dyna-map)
                             (#(set-methods % meths)))]
        (or pre-env env)))))

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
           ::dm/tform-pre preform
           :tf-pre []
           ::dm/ins ins
           :in []
           ::dm/tform tform
           :tf []
           ::dm/outs outs
           :out []
           ::dm/tform-end endform
           :end [])]
    (-> r (assoc-method ::dm/dyna-invoke transformer-invoke))))

#_
(comment

  (contains-method? root ::dm/dyna-invoke)
  (method root ::dm/dyna-invoke)
  (get-methods root)
  (root)
  (root 1)
  (root :tf-pre)
  (root :tf-pre-blah :not-found-here)
  (root 1 2 3)
  (apply root 1 [2 3])

  (def r1 (-> root (assoc :op +)))
  r1
  (type r1)
  (r1 1 2 3 4)
  (r1 1 2 3)
  (r1 1 2)
  (r1 1)
  (r1 1 2 3 [4 5])
  (apply r1 1 2 3 [4 5])
  (time (apply r1 1 2 3 (range 100000))) ;=> 4999950006
  ; "Elapsed time: 12.000000 msecs"

  (time (apply + 1 2 3 (range 100000))) ;=> 4999950006
  ; "Elapsed time: 13.000000 msecs"

  (def x
    (assoc root
           :op +
          ;;  :x 1 :y 2))
           :tf-pre [::tf-pre (fn [x] (println :tf-pre x) x)]
           :in     [::in     (fn [x] (println :in     x) x)]
           :tf     [::tf     (fn [x] (println :tf     x) x)]
           :out    [::out    (fn [x] (println :out    x) x)]
           :tf-end [::tf-end (fn [x] (println :tf-end x) x)]))

  x
  (type x)
  (x) ;=> 0
  (apply x [1]) ;=> 1
  (apply x 1 [2]) ;=> 3
  (x 1 2 4) ;=> 7
  (apply x 1 2 3 (range 25)) ;=> 306
  (apply x 1 (range 5)) ;=> 11

  (def y (assoc x :a 1 :b 2))
  y
  (y 1 2) ;=> 3

  (apply y 1 (range 5)) ;=> 11

  (def z (assoc y :r 1 :k 2))
  z
  (z 1 2) ;=> 3
  (apply z 1 (range 25)) ;=> 301

  root
  (root) ;=> nil
  (def a+ (assoc root :op +)) ;=> #'step-up.alpha.pthm/a+
  (apply a+ 1 2 [3 4]) ;=> 10

  (def x+ (assoc a+ :x 1 :y 2))

  x+
  (type x+)
  (x+) ;=> 0
  (x+ 1) ;=> 1
  (x+ 1 2) ;=> 3
  (apply x+ 1 2 (range 23)) ;=> 256
  (apply x+ 1 2 (range 2)) ;=> 4
  (apply x+ [2 1]) ;=> 3

  :end)
