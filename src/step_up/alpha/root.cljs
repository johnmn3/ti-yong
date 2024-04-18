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
                             (merge dm/empty-transformer-map)
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
  (get-methods r1)
  (type r1)
  (r1 1 2 3 4)
  (r1 1 2 3)
  (r1 1 2)
  (r1 1)
  (r1 1 2 3 [4 5])
  (apply r1 1 2 3 [4 5])
  (apply r1 1 2 3 (range 35))


  :end)
#_
(comment

  (def a (dyna-map :a 1))
  a
  (type a)
  (def b (assoc a :x 1))
  b
  (type b)

  ;; (def root (dyna-map))
  root
  (type root)
  (root)
  (root :args)
  (root 1)
  (root 1 2)
  (root 1 2 3)
  (apply root 1 [2])

  (apply root 3 [1 2 3])

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

  ;; (defn a+ [& args] (println :args args) (apply + args))
  (def tm (dyna-map :op + :a 1 :b 2))
  (def tm (dyna-map :a 1 :b 2))
  tm
  (type tm)
  (tm 1)
  (tm 1 2 3)
  (tm 1 [2 3])
  (apply tm 1 [2 3])
  (apply tm 1 (range 25))

  (def x
    (assoc root
           :op +
          ;;  :x 1 :y 2))
           :tf-pre [::tf-pre (fn [x] (println :tf-pre x) x)]
           :in     [::in     (fn [x] (println :in     x) x)]
           :tf     [::tf     (fn [x] (println :tf     x) x)]
           :out    [::out    (fn [x] (println :out    x) x)]
           :tf-end [::tf-end (fn [x] (println :tf-end x) x)]))

  ;;  (fn [x] (println :tf 2 :x x) x)))
  ; constructor ; tran-map ; tdmr-map ; tform
  ; finally
  (:invoke-any x)
  (x :IFn/-invoke-any)
  (type x)
  x
  (x)
  (apply x [1])
  (apply x 1 [2])
  (x 1 2 4)
  (apply x 1 2 3 (range 25))
  (apply x 1 (range 5))

  (def y (assoc x :a 1 :b 2))
  y
  (y 1 2)

  (apply y 1 (range 5))

  (def z (assoc y :r 1 :k 2))
  z
  (z 1 2)
  (apply z 1 (range 25))

  ;; add ;tf, :in (which is :args), :out,
  ;; (defn tform [env & args]
  ;;   (->> env :tf (reduce (fn [arg tf] (map tf arg))
  ;;                  args)))

  ;; (take 3 (tform x 1 2 3 4))

  ; transformer fnt step-up dtf form tran
  ; (tfn + :in #() :out #())
  ; (tfn + :in #() :out #())
  ; tfmr ; tfn ; hash-map ;

  dyna-map ;=> #object [step-up$alpha$pthm$tf]
  (dyna-map) ;=> {:args [], :invoke-any #object [G__57451], :step-up.alpha.pthm/tform-end #object [step-up$alpha$pthm$endform], :step-up.alpha.pthm/ins ...
  (def a+ (dyna-map :op +)) ;=> #'step-up.alpha.pthm/a+
  (apply a+ 1 2 [3 4]) ;=> 10

  (def b+ (dyna-map :op +)) ;=> #'step-up.alpha.pthm/a+
  (apply b+ 1 2 [3 4]) ;=> 10

  (def x+ (dyna-map :op + :x 1 :y 2))

  x+
  (type x+)
  (x+)
  (x+ 1)
  (x+ 1 2)
  (apply x+ 1 2 (range 23))
  (apply x+ 1 2 (range 2))
  (apply x+ [2 1])
  (apply {} [2 1])


  ;; (def m (.-EMPTY PersistentDynamicMap))

  ;; m
  ;; (type m)

  (def a1 (assoc empty-transformer-map :a 1))
  a1
  (type a1)
  (a1 1 2 3 4 5)

  (def b1 (assoc a1 :hello :world))
  (type b1)
  b1
  (apply b1 [1 2 3 4])
  (b1 [1 2 3 4])
  (apply b1 (range 24))
  (apply b1 (range 21))
  (b1 1 2)
  (b1 1)

  (def c1 (assoc b1 :good :stuff))
  c1
  (type c1)

  (c1 :hello) ;=> :hello
  (:hello c1) ;=> :world
  (c1 :hello :big :blue :world)

  :end)
  
  
