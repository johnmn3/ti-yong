(ns step-up.alpha.transformer
  (:require
;;    [clojure.edn :as edn]
   [step-up.alpha.util :as u]
   [step-up.alpha.root :as r]))

(defn- combine
  [m & maps]
  (if-not (map? (first maps))
    (combine m (into {} (mapv vec (partition 2 maps))))
    (->> maps
         (concat [m])
         (apply merge-with
                (fn [& args]
                  (if (every? map? args)
                    (apply combine args)
                    (if (->> args (filter vector?) seq nil? not)
                      (vec (apply concat (mapv u/muff args)))
                      (last args)))))
         (mapv (fn [[k v]]
                 (if-not (and (vector? v) (keyword? (first v)) (not (keyword? (second v))))
                   [k v]
                   (let [nv (->> v (partition 2) (u/uniq-by first) (apply concat) vec)]
                     [k nv]))))
         (into {})
         (merge m))))

(defn- separate
  [& ctxs]
  (->> ctxs
       (partition 2)
       (u/uniq-by first)
       (map
        (fn [[k ctx]]
          (if-let [with (:with ctx)]
            (if (not (seq with))
              [k ctx]
              (into [k (dissoc ctx :with)]
                    (apply separate (mapcat #(do [(:id %) %]) (u/muff with)))))
            [k ctx])))
       (u/uniq-by first)
       (mapcat identity)
       vec))

(def transformer
  (-> r/root
      (update :id conj ::transformer)
      (assoc :with [])
      (update :tf-pre conj
              ::with
              (fn with-tf [{:as env :keys [with]}]
                (if-not (seq with)
                  env
                  (let [separated (->> with reverse (mapcat #(do [(last (:id %)) %])) (apply separate))
                        merges  (if-not (seq separated)
                                  env
                                  (->> separated
                                       (partition 2)
                                       (mapv #(second %))
                                       (#(apply combine (into % [(dissoc env :with)])))))
                        merges (-> merges
                                   (update :id (comp vec distinct)))]
                    merges))))))

#_(type transformer)
#_(step-up.alpha.dyna-map/-get-methods r/root)
#_(step-up.alpha.dyna-map/-get-methods transformer)
#_(step-up.alpha.dyna-map/-get-coll transformer)
#_(transformer 1) ;=> 1
#_
(comment

  (def a
    (-> transformer
        (update :id conj ::a)
        (assoc :a 1)
        (update :tf conj ::a (fn [env] (println ::a-tf :env env) env))))
  #_(:tf a)
  #_(:id a)

  (def b
    (-> transformer
        (update :id conj ::b)
        (assoc :b 2)
        (update :with conj a)
        (update :tf conj ::b (fn [env] (println ::b-tf :env env) env))))
  #_(:tf b)
  #_(:id b)
  #_b

  (def c
    (-> transformer
        (update :id conj ::c)
        (assoc :c 3)
        (update :with conj b)
        (update :tf conj ::c (fn [env] (println ::c-tf :env env) env))))
  #_(:tf c)
  #_(:id c)

  (def x
    (-> transformer
        (update :id conj ::x)
        (update :with conj a c)
        (assoc :x 20)
        (update :tf conj ::x (fn [env] (println ::x-tf :env env) env))))
  #_(:tf x)
  #_(:id x)
  x

  (def y
    (-> transformer
        (update :id conj ::y)
        (update :with conj c x b a)
                       ;; ^^^ can be in any order because their orders are already defined in x and c 
        (assoc :y 21)
        (update :tf conj ::y (fn [env] (println ::y-tf :env env) env))))
  #_(:id y)


  (def z
    (-> transformer
        (update :id conj ::z)
        (update :with conj y)
        (assoc :z 22)
        (update :tf conj ::z (fn [env] (println ::z-tf :env env) env))))
  #_z

  (def r
    (-> transformer
        (update :id conj ::r)
        (update :with conj z c)
        (assoc :r 21)
        (update :tf conj ::r (fn [env] (println ::r-tf :env env) env))))

  r
  (= :root|transformer|a|b|c|x|y|z|r
     (->> r :id (mapv name) (interpose "|") (apply str) keyword))

  (= {:r 21, :z 22, :x 20, :y 21, :c 3, :b 2, :a 1}
     (select-keys r [:r :z :x :y :c :b :a]))

  (= [:a :b :c :x :y :z :r]
     (->> r :tf (partition 2) (map first) (map name) (map keyword) vec))

  (require '[clojure.pprint :as pp])

  (pp/pprint r)

  (def tm (assoc transformer :a 1 :b 2))
  tm
  (type tm)
  (def r1
    (-> transformer #_r/root
        (update :id conj ::r1)))

  r1
  (type r1)

  (def x
    (assoc transformer
           :op + :x 1 :y 2
           :tf-pre [::tf-pre (fn [x] (println :tf-pre x) x)]
           :in     [::in     (fn [x] (println :in     x) x)]
           :tf     [::tf     (fn [x] (println :tf     x) x)]
           :out    [::out    (fn [x] (println :out    x) x)]
           :tf-end [::tf-end (fn [x] (println :tf-end x) x)]))

  ;;  (fn [x] (println :tf 2 :x x) x)))
  ; constructor ; tran-map ; tfmr-map ; tform
  ; finally
  (:invoke-any x)
  (x :IFn/-invoke-any)
  (type x)
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

  transformer
  (transformer)
  (def a+ (assoc transformer :op +))
  (apply a+ 1 2 [3 4]) ;=> 10

  (def x+ (assoc transformer :op + :x 1 :y 2))

  x+
  (type x+)
  (x+) ;=> 0
  (x+ 1) ;=> 1
  (x+ 1 2) ;=> 3
  (apply x+ 1 2 (range 23)) ;=> 256
  (apply x+ 1 2 (range 2)) ;=> 4
  (apply x+ [2 1 4]) ;=> 7

  (defn failure-message [data input output actual]
    (str "Failure in "   (last (:id data))
         " with mock inputs " (pr-str input)
         " when expecting "    (pr-str output)
         " but actually got "     (pr-str actual)))

  (def mocker
    (-> transformer
        (update :id conj ::mocker)
        (update :tf-pre conj
                ::mocker
                (fn [{:as env :keys [id mock mocks mocked instantiated?] :or {mocked #{} mocks [] mock []}}]
                ;;   (println :mocker :instantiated? instantiated?)
                  (if (or (mocked id) instantiated? (not (seq mock)))
                    env
                    #_(assoc env
                            ;; :mocks (into mocks mock)
                             :mocked (disj mocked id))
                    (let [;_ (println :running-mocker :for (last id))
                          mocks (into mocks mock)
                        ;;   _ (println :new-mocks mocks)
                          mocked (conj mocked id)
                          this (-> env (dissoc :mock) (->> (merge transformer)))
                          failures (->> mocks
                                        (partition 2)
                                        (mapv (fn [[in* out*]]
                                                (assert (coll? in*))
                                                (let [;_ (println :in* in*)
                                                      result (apply (dissoc this :args) in*)]
                                                ;;   (println :res result)
                                                  (when (and result (not= result out*))
                                                    (failure-message env in* out* result)))))
                                        (filter (complement nil?)))]
                      (when (seq failures)
                        (->> failures (mapv (fn [er] (throw (ex-info (str er) {}))))))
                      (assoc env :mocked mocked :mocks mocks :mock [])))))))

  (defn strings->ints [& string-ints]
    (->> string-ints (map str) (mapv edn/read-string)))

  (def +s
    (-> transformer
        (update :id conj ::+s)
        (assoc :op +)
        (update :with conj mocker)
        (update :tf conj
                ::+s
                #%(merge % (when-let [args (apply strings->ints %:args)]
                             {:args args})))
        (assoc :mock [[1 "2" 3 4 "5" 6] 21])))

  (step-up.alpha.dyna-map/get-methods +s)
  (+s 100 1)

  (defn vecs->ints [& s]
    (->> s (reduce #(if (vector? %2) (into %1 %2) (conj %1 %2)) [])))

  (def +sv
    (-> +s
        (update :id conj ::+sv)
        (update :tf conj
                ::+sv
                #%(merge % (when-let [args (apply vecs->ints %:args)]
                             {:args args})))
        (update :mock conj ["1" [2]] 3))) ;=> #'step-up.alpha.transformer/+sv

  (+sv "1" [2] 3 [4 5]) ;=> 15
  +sv

  (def +sv-failure
    (-> +s
        (update :id conj ::+sv)
        (update :tf conj
                ::+sv
                #%(merge % (when-let [args (apply vecs->ints %:args)]
                             {:args (mapv str args)})))
        (update :mock conj ["1" [2]] 3))) ;=> :repl/exception!
  ; Execution error (ExceptionInfo) at (<cljs repl>:1).
  ; Failure in :step-up.alpha.transformer/+sv with mock inputs [1 "2" 3 4 "5" 6] when expecting 21 but actually got "123456"

  :end)
  
