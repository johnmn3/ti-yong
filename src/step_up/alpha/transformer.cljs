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
       (mapcat
        (fn [[k ctx]]
          (if-let [with (:with ctx)]
            (if (not (seq with))
              [k ctx]
              (into [k (dissoc ctx :with)]
                    (apply separate (mapcat #(do [(:id %) %]) (u/muff with)))))
            [k ctx])))
       (partition 2)
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
                (println :running :with with)
                (if-not (seq with)
                  env
                  (let [separated (->> with (mapcat #(do [(last (:id %)) %])) (apply separate))
                        merges  (if-not (seq separated)
                                  env
                                  (->> separated
                                       (partition 2)
                                       (mapv #(second %))
                                       (#(apply combine (into % [(dissoc env :with)])))))
                        merges (-> merges
                                   (dissoc :with)
                                   (update :id (comp vec distinct)))]
                    merges))))))

#_(type transformer)
#_(step-up.alpha.dyna-map/-get-methods r/root)
#_(step-up.alpha.dyna-map/-get-methods transformer)
#_(step-up.alpha.dyna-map/-get-coll transformer)
#_(transformer 1) ;=> 1
#_
(comment

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
        (update :tf conj
                ::mocker
                (fn [{:as env :keys [mock]}]
                  (if-not mock
                    env
                    (let [this (-> env (dissoc :mock) (->> (merge transformer)))
                          failures (->> mock
                                        (partition 2)
                                        (mapv (fn [[in out]]
                                                (assert (coll? in))
                                                (let [result (apply this in)]
                                                  (when (and result (not= result out))
                                                    (failure-message env in out result)))))
                                        (filter (complement nil?)))]
                      (when (seq failures)
                        (->> failures (mapv (fn [er] (throw (ex-info (str er) {}))))))
                      env))))))

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
  (+s "1" 2)

  (defn vecs->ints [& s]
    (->> s (reduce #(if (vector? %2) (into %1 %2) (conj %1 %2)) [])))

  (def +sv
    (-> +s
        (update :id conj ::+sv)
        (update :tf conj
                ::+sv
                #%(merge % (when-let [args (apply vecs->ints %:args)]
                             {:args args})))
        (update :mock conj ["1" [2]] 3)))

  (+sv "1" [2] 3 [4 5]) ;=> 15


  :end)
  
  
