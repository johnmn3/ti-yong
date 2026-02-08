(ns ti-yong.alpha.transformer
  (:require
   [clojure.spec.alpha :as s]
   [ti-yong.alpha.util :as u]
   [ti-yong.alpha.root :as r]
   [com.jolygon.wrap-map :as w]))
  ;; (:import com.jolygon.wrap_map.api_0.impl.WrapMap) ; Import removed again

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

(s/def ::id (s/coll-of qualified-keyword? :kind vector?))
(s/def ::specs (s/coll-of qualified-keyword? :kind vector?))
(s/def ::with (s/coll-of ::transformer :kind vector?))

(s/def ::transformer
  (s/keys :opt-un [::specs ::with ::id]))

(def transformer
  (-> r/root
      (update :id conj ::transformer)
      (assoc :with [] :specs [::transformer ::transformer])
      (update :tf-pre conj
              ::with
              (fn with-tf [{:as env :keys [with]}]
                (if-not (seq with)
                  env
                  (let [separated (->> with reverse (mapcat #(do [(last (:id %)) %])) (apply separate))
                        excluded (::r/excluded-keys env #{})
                        specs (:specs env [])
                        plain-env-data (if (instance? com.jolygon.wrap_map.api_0.impl.WrapMap env)
                                         (w/unwrap (dissoc env :with :specs ::r/excluded-keys))
                                         (dissoc env :with :specs ::r/excluded-keys))
                        seconds (->> separated
                                     (partition 2)
                                     (mapv #(second %))
                                     (cons plain-env-data)
                                     vec)
                        combined (apply combine seconds)
                        merges (if-not (seq separated)
                                 plain-env-data
                                 combined)
                        merges (reduce dissoc merges excluded)
                        merges (-> merges
                                   (update :id (comp vec distinct)))]
                    (update merges :specs into specs))))
              ::spec
              (fn spec-tf [{:as env :keys [specs]}]
                (if-not (seq specs)
                  env
                  (let [s (->> specs (partition 2) (u/uniq-by first) (map vec))]
                    (doseq [spec (map second s)]
                      (when-not (s/valid? spec env)
                        (throw
                         (let [error-str (->> env
                                              (s/explain-data spec)
                                              :clojure.spec.alpha/problems
                                              first
                                              :pred
                                              (str spec " "))]
                           (ex-info error-str {:error error-str :env env :spec spec})))))
                    (assoc env :specs (vec (mapcat identity s)))))))))

(comment

  (s/def ::a int?)
  (s/def ::a-spec (s/keys :req-un [::a]))
  (def a
    (-> transformer
        (update :id conj ::a)
        ;; (assoc :a 1)
        (update :specs conj ::a ::a-spec)
        (update :tf conj ::a (fn [env] (println ::a-tf :env env) env)))) ;=> :repl/exception!
  ; Execution error (Error) at (<cljs repl>:1).
  ; :ti-yong.alpha.transformer/a-spec (cljs.core/fn [%] (cljs.core/contains? % :a))

  (def a
    (-> transformer
        (update :id conj ::a)
        (assoc :a 1)
        (update :specs conj ::a ::a-spec)
        (update :tf conj ::a (fn [env] (println ::a-tf :env env) env)))) ;=> #'ti-yong.alpha.transformer/a

  #_(dissoc a :a) ;=> :repl/exception!
  ; Execution error (Error) at (<cljs repl>:1).
  ; :ti-yong.alpha.transformer/a-spec

  (s/def ::b int?)
  (s/def ::b-spec (s/keys :req-un [::b]))
  (assoc transformer :with [:hi])
  (assoc transformer :with [])
  (update transformer :with #(do (println :1 %1 :type-2 (type %2) :2 %2) (vec (conj %1 %2))) a)
  (def b
    (-> transformer
        (update :id conj ::b)
        (assoc :b 2)
        (update :specs conj ::b ::b-spec)
        (update :with conj a)
        (update :tf conj ::b (fn [env] (println ::b-tf :env env) env))))
  #_(dissoc b :a)
  #_(:tf b)
  #_(:id b)
  #_(:specs b)
  #_(:b b)
  #_b

  (s/def ::c int?)
  (s/def ::c-spec (s/keys :req-un [::c]))
  (def c
    (-> transformer
        (update :id conj ::c)
        (assoc :c 3)
        (update :specs conj ::c ::c-spec)
        (update :with conj b)
        (update :tf conj ::c (fn [env] (println ::c-tf :env env) env))))
  #_(:tf c)
  #_(:id c)
  #_(:specs c)

  (s/def ::x int?)
  (s/def ::x-spec (s/keys :req-un [::x]))
  (def x
    (-> transformer
        (update :id conj ::x)
        (update :with conj a c)
        (assoc :x 20)
        (update :specs conj ::x ::x-spec)
        (update :tf conj ::x (fn [env] (println ::x-tf :env env) env))))
  #_(:tf x)
  #_(:id x)
  #_(:specs x)

  (s/def ::y int?)
  (s/def ::y-spec (s/keys :req-un [::y]))
  (def y
    (-> transformer
        (update :id conj ::y)
        (update :with conj a b c x)
        (assoc :y 21)
        (update :specs conj ::y ::y-spec)
        (update :tf conj ::y (fn [env] (println ::y-tf :env env) env))))
  #_(:id y)
  #_(:specs y)


  (s/def ::z int?)
  (s/def ::z-spec (s/keys :req-un [::z]))
  (def z
    (-> x
        (update :id conj ::z)
        (update :with conj y)
        (assoc :z 22)
        (update :specs conj ::z ::z-spec)
        (update :tf conj ::z (fn [env] (println ::z-tf :env env) env))))
  #_z

  (s/def ::r int?)
  (s/def ::r-spec (s/keys :req-un [::r]))
  (def r
    (-> b
        (update :id conj ::r)
        (update :with conj c z)
        (assoc :r 21)
        (update :specs conj ::r ::r-spec)
        (update :tf conj ::r (fn [env] (println ::r-tf :env env) env))))
  #_(= (:specs r)
       [:ti-yong.alpha.transformer/transformer :ti-yong.alpha.transformer/transformer :ti-yong.alpha.transformer/a :ti-yong.alpha.transformer/a-spec :ti-yong.alpha.transformer/b :ti-yong.alpha.transformer/b-spec :ti-yong.alpha.transformer/c :ti-yong.alpha.transformer/c-spec :ti-yong.alpha.transformer/x :ti-yong.alpha.transformer/x-spec :ti-yong.alpha.transformer/y :ti-yong.alpha.transformer/y-spec :ti-yong.alpha.transformer/z :ti-yong.alpha.transformer/z-spec :ti-yong.alpha.transformer/r :ti-yong.alpha.transformer/r-spec])

  (= {:failed "clojure.lang.ExceptionInfo: :ti-yong.alpha.transformer/a-spec"}
     (try (dissoc r :a)
          (catch Exception e
            {:failed (->> e str (take 61) (apply str))})))

  (= {:failed "clojure.lang.ExceptionInfo: :ti-yong.alpha.transformer/x-spec"}
     (try (dissoc r :x)
          (catch Exception e
            {:failed (->> e str (take 61) (apply str))})))

  r
  (:id r)
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
  (def y
    (assoc transformer
           :op + :x 1 :y 2))
  (def z (assoc y :z "z"))
  (:z (merge y z)) ;=> "z"
  (into y z)
  (type (first y))
  
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
  (apply y 1 2 3 (range 25))
  (apply x 1 (range 5))

  (def y (assoc x :a 1 :b 2))
  y
  (y 1 2)
  (merge x y)
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
                  (if (or (mocked id) instantiated? (not (seq mock)))
                    env
                    #_(assoc env
                            ;; :mocks (into mocks mock)
                             :mocked (disj mocked id))
                    (let [mocks (into mocks mock)
                          mocked (conj mocked id)
                          _ (println :mocks mocks :mocked mocked)
                          mockless (dissoc env :mock)
                          _ (println :mockless mockless)
                          ;; this (merge transformer mockless)
                          this (into transformer mockless)
                          _ (println :this this)
                          failures (->> mocks
                                        (partition 2)
                                        (mapv (fn [[in* out*]]
                                                (assert (coll? in*))
                                                (let [result (apply (dissoc this :args) in*)]
                                                  (when (and result (not= result out*))
                                                    (failure-message env in* out* result)))))
                                        (filter (complement nil?)))]
                      (when (seq failures)
                        (->> failures (mapv (fn [er] (throw (ex-info (str er) {}))))))
                      (assoc env :mocked mocked :mocks mocks :mock [])))))))

  (require '[clojure.edn :as edn])
  (defn strings->ints [& string-ints]
    (->> string-ints (map str) (mapv edn/read-string)))

  (def +s
    (-> transformer
        (update :id conj ::+s)
        (assoc :op +)
        (update :with conj mocker)
        (update :tf conj
                ::+s
                #(merge % (when-let [args (apply strings->ints (% :args))]
                            {:args args})))
        (assoc :mock [[1 "2" 3 4 "5" 6] 21])))

  (ti-yong.alpha.dyna-map/get-methods +s)
  (+s 100 1)

  (defn vecs->ints [& s]
    (->> s (reduce #(if (vector? %2) (into %1 %2) (conj %1 %2)) [])))

  (def +sv
    (-> +s
        (update :id conj ::+sv)
        (update :tf conj
                ::+sv
                #(merge % (when-let [args (apply vecs->ints (% :args))]
                            {:args args})))
        (update :mock conj ["1" [2]] 3))) ;=> #'ti-yong.alpha.transformer/+sv

  (+sv "1" [2] 3 [4 5]) ;=> 15
  +sv

  (def +sv-failure
    (-> +s
        (update :id conj ::+sv)
        (update :tf conj
                ::+sv
                #(merge % (when-let [args (apply vecs->ints (% :args))]
                            {:args (mapv str args)})))
        (update :mock conj ["1" [2]] 3))) ;=> :repl/exception!
  ; Execution error (ExceptionInfo) at (<cljs repl>:1).
  ; Failure in :ti-yong.alpha.transformer/+sv with mock inputs [1 "2" 3 4 "5" 6] when expecting 21 but actually got "123456"

  :end)
  
