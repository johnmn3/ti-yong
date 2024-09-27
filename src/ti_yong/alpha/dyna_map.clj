;; ported from ti-yong.alpha.dyna-map.cljs

(ns ti-yong.alpha.dyna-map)

(defn default-map-invoke [env & args]
  (case (count args)
    0 (throw (ex-info "Invalid arity: 0"
                      {:error :invalid-arity
                       :arity 0
                       :args args
                       :env env}))
    1 (get env (first args))
    2 (get env (first args) (second args))
    (throw 
     (let [arity (count args)]
       (ex-info (str "Invalid arity: " arity)
                {:error :invalid-arity
                 :arity arity
                 :args args
                 :env env})))))

(defn default-invoke-handler
  [env & args]
  (let [env (assoc (into {} env) :instantiated? true)]
    (if-let [diy-default-invoke (or (get-in env [::methods ::default-invoke])
                                  (get env ::default-invoke))]
     (apply diy-default-invoke env (or (:apply-args env) args))
     (throw (ex-info "No default invoke added" {:args args :env env})))))

(defn handle-invoke [env & args]
  (let [env (assoc (into {} env) :instantiated? true)]
    (if-let [dynamic-invoke (or (get-in env [::methods ::dyna-invoke])
                                (get env ::dyna-invoke))]
      (apply dynamic-invoke env (or (:apply-args env) args))
      (apply default-invoke-handler env (or (:apply-args env) args)))))

(defn PDM [facaded-coll methods]
  (let [tform-pre (:ti-yong.alpha.root/tform-pre facaded-coll)
        pre-env (if tform-pre
                  (tform-pre (assoc facaded-coll ::methods methods))
                  facaded-coll)
        meths (or (::methods pre-env) methods)
        user-invoke (or (::default-invoke meths) default-map-invoke)
        new-meths (assoc meths ::default-invoke user-invoke)]
    [pre-env new-meths]))

(defprotocol IDynamicAssociative
  (-assoc-method [coll k v])
  (-contains-method? [coll k])
  (-method [coll k])
  (-get-methods [coll])
  (-set-methods [coll new-methods])
  (-get-coll [coll])
  (-dissoc-method [coll k]))

;; todo, add tests for these methods
(defn contains-method? [coll k]
  (-contains-method? coll k))

(defn method [coll k]
  (-method coll k))

(defn get-methods [coll]
  (-get-methods coll))

(defn set-methods [coll new-methods]
  (-set-methods coll new-methods))

(defn get-coll [coll]
  (-get-coll coll))

(defn dissoc-method [coll & ks]
  (->> ks
       (reduce (fn [c k]
                 (-dissoc-method c k))
               coll)))

(defn assoc-method [coll & kvs]
  (->> kvs
       (partition 2)
       (reduce (fn [c [k v]]
                 (-assoc-method c k v))
               coll)))

(deftype DynamicMap [^clojure.lang.IPersistentMap fcoll
                     ^clojure.lang.IPersistentMap m]
  clojure.lang.IPersistentMap
  clojure.lang.IHashEq
  clojure.lang.MapEquivalence
  (hashCode [_this]
    (.hashCode fcoll))
  (equiv [this o]
    (or (identical? this o)
        (and (instance? java.util.Map o)
             (= (count fcoll) (.size ^java.util.Map o))
             (every? (fn [[k v]]
                       (and (.containsKey ^java.util.Map o k)
                            (= v (.get ^java.util.Map o k))))
                     fcoll))))
  (hasheq [_this]
    (.hasheq fcoll))
  Object
  (equals [_this o]
    (.equiv fcoll o))
  (toString [_coll]
    (if-let [f-pr-str (:Object/pr-str m)]
      (f-pr-str {:fcoll fcoll ::methods m})
      (str fcoll)))

  IDynamicAssociative
  (-assoc-method [_coll k v]
    (let [[pe nm] (PDM fcoll (assoc m k v))] (DynamicMap. pe nm)))
  (-contains-method? [_coll k]
    (contains? m k))
  (-method [_coll k]
    (get m k))
  (-get-methods [_coll]
    m)
  (-set-methods [_coll new-methods]
    (let [[pe nm] (PDM fcoll new-methods)] (DynamicMap. pe nm)))
  (-get-coll [_coll]
    fcoll)
  (-dissoc-method [_coll k]
    (let [[pe nm] (PDM fcoll (dissoc m k))] (DynamicMap. pe nm)))

  clojure.lang.Associative
  (containsKey [_this k]
    (if-let [f-contains-key? (:clojure.lang.Associative/containsKey m)]
      (f-contains-key? {:fcoll fcoll ::methods m :k k})
      (contains? fcoll k)))
  (entryAt [_this k]
    (if-let [f-entry-at (:clojure.lang.Associative/entryAt m)]
      (f-entry-at {:fcoll fcoll ::methods m :k k})
      (if-let [v (.get fcoll k)]
        (clojure.lang.MapEntry. k v)
        nil)))
  (assoc [_this key val]
    (if-let [f-assoc (:clojure.lang.Associative/assoc m)]
      (f-assoc {:fcoll fcoll ::methods m :key key :val val})
      (let [[pe nm] (PDM (assoc fcoll key val) m)]
        (DynamicMap. (if (map? pe) pe (into {} (vec pe))) nm))))

  clojure.lang.IKVReduce
  (kvreduce [_this f init]
    (if-let [f-kv-reduce (:clojure.lang.IKVReduce/kvreduce m)]
      (f-kv-reduce {:fcoll fcoll ::methods m :f f :init init})
      (reduce-kv f init fcoll)))

  clojure.lang.ILookup
  (valAt [_this k]
    (if-let [f-val-at (:clojure.lang.ILookup/valAt m)]
      (f-val-at {:fcoll fcoll ::methods m :k k})
      (get fcoll k)))
  (valAt [_this k not-found]
    (if-let [f-val-at (:clojure.lang.ILookup/valAt2 m)]
      (f-val-at {:fcoll fcoll ::methods m :k k :not-found not-found})
      (get fcoll k not-found)))

  clojure.lang.IMapIterable
  (keyIterator [_this]
    (if-let [f-key-iterator (:clojure.lang.IMapIterable/keyIterator m)]
      (f-key-iterator {:fcoll fcoll ::methods m})
      (keys fcoll)))
  (valIterator [_this]
    (if-let [f-val-iterator (:clojure.lang.IMapIterable/valIterator m)]
      (f-val-iterator {:fcoll fcoll ::methods m})
      (vals fcoll)))


  clojure.lang.Counted
  (count [_this]
    (if-let [f-count (:clojure.lang.IPersistentCollection/count m)]
      (f-count {:fcoll fcoll ::methods m})
      (count fcoll)))

  clojure.lang.IPersistentCollection
  (empty [_this]
    (if-let [f-empty (:clojure.lang.IPersistentCollection/empty m)]
      (f-empty {:fcoll fcoll ::methods m})
      (DynamicMap. (empty fcoll) {})))
  (cons [_this o]
    (if-let [f-cons (:clojure.lang.IPersistentCollection/cons m)]
      (f-cons {:fcoll fcoll ::methods m :o o})
      (let [[pe nm] (PDM (conj fcoll o) m)]
        (DynamicMap. pe nm))))
  (assocEx [_this key val]
    (if-let [f-assoc-ex (:clojure.lang.IPersistentMap/assocEx m)]
      (f-assoc-ex {:fcoll fcoll ::methods m :key key :val val})
      (let [[pe nm] (PDM (assoc fcoll key val) m)] (DynamicMap. pe nm))))
  (without [_this key]
    (if-let [f-without (:clojure.lang.IPersistentMap/without m)]
      (f-without {:fcoll fcoll ::methods m :key key})
      (let [[pe nm] (PDM (dissoc fcoll key) m)] (DynamicMap. pe nm))))

  clojure.lang.Seqable
  (seq [_this]
    (if-let [f-seq (:clojure.lang.Seqable/seq m)]
      (f-seq {:fcoll fcoll ::methods m})
      (seq fcoll)))

  java.lang.Iterable
  (iterator [_this]
    (if-let [f-iterator (:java.lang.Iterable/iterator m)]
      (f-iterator {:fcoll fcoll ::methods m})
      (.iterator
       ^java.lang.Iterable
       (.seq fcoll))))

  clojure.lang.IFn
  (invoke [this]
    (handle-invoke (merge fcoll (assoc this :fcoll fcoll ::methods m))))
  (invoke [this k]
    (if (::dyna-invoke m)
      (handle-invoke
       (merge fcoll (assoc this :fcoll fcoll ::methods m))
       k)
      (.valAt this k)))
  (invoke [this k not-found]
    (if (::dyna-invoke m)
      (handle-invoke
       (merge fcoll (assoc this :fcoll fcoll ::methods m))
       k not-found)
      (.valAt this k not-found)))
  (invoke [this arg1 arg2 arg3]
    (handle-invoke
     (merge fcoll (assoc this :fcoll fcoll ::methods m))
     arg1 arg2 arg3))
  (invoke [this arg1 arg2 arg3 arg4]
    (handle-invoke
     (merge fcoll (assoc this :fcoll fcoll ::methods m))
     arg1 arg2 arg3 arg4))
  (invoke [this arg1 arg2 arg3 arg4 arg5]
    (handle-invoke
     (merge fcoll (assoc this :fcoll fcoll ::methods m))
     arg1 arg2 arg3 arg4 arg5))
  (invoke [this arg1 arg2 arg3 arg4 arg5 arg6]
    (handle-invoke
     (merge fcoll (assoc this :fcoll fcoll ::methods m))
     arg1 arg2 arg3 arg4 arg5 arg6))
  (invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7]
    (handle-invoke
     (merge fcoll (assoc this :fcoll fcoll ::methods m))
     arg1 arg2 arg3 arg4 arg5 arg6 arg7))
  (invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8]
    (handle-invoke
     (merge fcoll (assoc this :fcoll fcoll ::methods m))
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8))
  (invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9]
    (handle-invoke
     (merge fcoll (assoc this :fcoll fcoll ::methods m))
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9))
  (invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10]
    (handle-invoke
     (merge fcoll (assoc this :fcoll fcoll ::methods m))
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10))
  (invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11]
    (handle-invoke
     (merge fcoll (assoc this :fcoll fcoll ::methods m))
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11))
  (invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12]
    (handle-invoke
     (merge fcoll (assoc this :fcoll fcoll ::methods m))
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12))
  (invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13]
    (handle-invoke
     (merge fcoll (assoc this :fcoll fcoll ::methods m))
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13))
  (invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14]
    (handle-invoke
     (merge fcoll (assoc this :fcoll fcoll ::methods m))
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14))
  (invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15]
    (handle-invoke
     (merge fcoll (assoc this :fcoll fcoll ::methods m))
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15))
  (invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16]
    (handle-invoke
     (merge fcoll (assoc this :fcoll fcoll ::methods m))
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16))
  (invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17]
    (handle-invoke
     (merge fcoll (assoc this :fcoll fcoll ::methods m))
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17))
  (invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18]
    (handle-invoke
     (merge fcoll (assoc this :fcoll fcoll ::methods m))
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18))
  (invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18 arg19]
    (handle-invoke
     (merge fcoll (assoc this :fcoll fcoll ::methods m))
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18 arg19))
  (invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18 arg19 arg20]
    (handle-invoke
     (merge fcoll (assoc this :fcoll fcoll ::methods m))
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18 arg19 arg20))
  (invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18 arg19 arg20 rest-args]
    (apply handle-invoke
           (merge fcoll (assoc this :fcoll fcoll ::methods m))
           arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18 arg19 arg20 rest-args))
  (applyTo [this args]
    (if (::dyna-invoke m)
      (apply handle-invoke (merge fcoll (assoc this :fcoll fcoll ::methods m :apply-args args))
             args)
      (apply fcoll args)))

  clojure.lang.IObj
  (withMeta [_this meta]
    (if-let [f-with-meta (:clojure.lang.IObj/withMeta m)]
      (f-with-meta {:fcoll fcoll ::methods m :meta meta})
      (let [[pe nm] (PDM (.withMeta fcoll meta) m)] (DynamicMap. pe nm))))
  (meta [_this]
    (if-let [f-meta (:clojure.lang.IObj/meta m)]
      (f-meta {:fcoll fcoll ::methods m})
      (meta fcoll)))

  clojure.core.protocols.CollReduce
  (coll-reduce [_this f]
    (if-let [f-coll-reduce (:clojure.core.protocols.CollReduce/coll-reduce-1 m)]
      (f-coll-reduce {:fcoll fcoll ::methods m :f f})
      (reduce f fcoll)))
  (coll-reduce [_this f init]
    (if-let [f-coll-reduce (:clojure.core.protocols.CollReduce/coll-reduce-2 m)]
      (f-coll-reduce {:fcoll fcoll ::methods m :f f :init init})
      (reduce f init fcoll)))

  clojure.core.protocols.IKVReduce
  (kv-reduce [_this f init]
    (if-let [f-kv-reduce (:clojure.core.protocols.IKVReduce/kv-reduce m)]
      (f-kv-reduce {:fcoll fcoll ::methods m :f f :init init})
      (reduce-kv f init fcoll)))

  java.util.Map
  (size [_this]
    (if-let [f-size (:java.util.Map/size m)]
      (f-size {:fcoll fcoll ::methods m})
      (count fcoll)))
  (isEmpty [_this]
    (if-let [f-is-empty (:java.util.Map/isEmpty m)]
      (f-is-empty {:fcoll fcoll ::methods m})
      (empty? fcoll)))
  (containsValue [_this v]
    (contains? fcoll v))
  (get [_this k] (.valAt fcoll k))
  (put [_this k v]
    (if-let [f-put (:java.util.Map/put m)]
      (f-put {:fcoll fcoll ::methods m :k k :v v})
      (.assoc fcoll k v)))
  (remove [_this k]
    (if-let [f-remove (:java.util.Map/remove m)]
      (f-remove {:fcoll fcoll ::methods m :k k})
      (.without fcoll k)))
  (entrySet [_this]
    (if-let [f-entry-set (:java.util.Map/entrySet m)]
      (f-entry-set {:fcoll fcoll ::methods m})
      (map (fn [[k v]] [k v]) fcoll)))
  (keySet [_this]
    (if-let [f-key-set (:java.util.Map/keySet m)]
      (f-key-set {:fcoll fcoll ::methods m})
      (keys fcoll)))
  (values [_this]
    (if-let [f-values (:java.util.Map/values m)]
      (f-values {:fcoll fcoll ::methods m})
      (vals fcoll)))
  (putAll [_this mx]
    (if-let [f-put-all (:java.util.Map/putAll m)]
      (f-put-all {:fcoll fcoll ::methods m :mx mx})
      (reduce (fn [mi [k v]] (.put mi k v)) fcoll mx)))
  (clear [_this]
    (if-let [f-clear (:java.util.Map/clear m)]
      (f-clear {:fcoll fcoll ::methods m})
      (empty fcoll))))

(def empty-dyna-map (DynamicMap. {} {}))

(defn dyna-map
  "keyval => key val
  Returns a new dynamic map with supplied mappings.  If any keys are
  equal, they are handled as if by repeated uses of assoc."
  [& kvs]
  (->> kvs (partition 2) (mapv vec) (into empty-dyna-map)))

(defmethod print-method DynamicMap [dmap ^java.io.Writer w]
  (.write w "{")
  (->> dmap
       seq
       (interpose ", ")
       (mapv (fn [x]
               (if (string? x)
                 x
                 (str (first x) " " (second x)))))
       (mapv #(.write w (str %))))
  (.write w "}"))

(comment

  (def a (dyna-map :a 1))
  a ;=> {:a 1}
  (type a) ;=> ti_yong.alpha.dyna_map/DynamicMap
  (def b (assoc a :x 1))
  b ;=> {:a 1, :x 1}
  (type b) ;=> ti_yong.alpha.dyna_map/DynamicMap

  (def root (dyna-map))
  root ;=> {}
  (type root) ;=> ti_yong.alpha.dyna_map/DynamicMap
  (root)
  ; Execution error (ExceptionInfo) at ti-yong.alpha.dyna-map/default-invoke-handler (dyna_map.clj:29).
  ; No default invoke added
  (root :args) ;=> nil
  (root 1) ;=> nil
  (root 1 2) ;=> 2  ; <- 1 is the lookup key and 2 is the non-found value (for two arity calls on maps)
  (root 1 2 3)
  ; Execution error (ExceptionInfo) at ti-yong.alpha.dyna-map/default-invoke-handler (dyna_map.clj:29).
  ; No default invoke added

  (apply root 3 [1 2 3]) \
  ; Execution error (ArityException) at ti_yong.alpha.dyna_map.DynamicMap/applyTo (dyna_map.clj:472).
  ; Wrong number of args (4) passed to: clojure.lang.PersistentArrayMap

  (def a1 (assoc empty-dyna-map :a 1))
  a1 ;=> {:a 1}
  (type a1) ;=> ti_yong.alpha.dyna_map/DynamicMap
  (class DynamicMap)
  (a1 1 2 3 4 5) ;=> :repl/exception!
  ; Execution error (ExceptionInfo) at ti-yong.alpha.dyna-map/default-map-invoke (dyna_map.clj:16).
  ; Invalid arity: 5
  (a1 1) ;=> nil
  (empty-dyna-map 1) ;=> nil

  (def b1 (assoc a1 :hello :world))
  (type b1) ;=> ti_yong.alpha.dyna_map/DynamicMap
  b1 ;=> {:a 1, :hello :world}
  (apply b1 1 [2]) ;=> 2  ;<- not-found
  (b1 [1 2 3 4]) ;=> nil
  (apply b1 (range 24)) ;=> :repl/exception!
  ; Execution error (ArityException) at ti_yong.alpha.dyna_map.DynamicMap/applyTo (dyna_map.clj:472).
  ; Wrong number of args (21) passed to: clojure.lang.PersistentArrayMap
  (apply b1 (range 21)) ;=> :repl/exception!
  ; Execution error (ArityException) at ti_yong.alpha.dyna_map.DynamicMap/applyTo (dyna_map.clj:472).
  ; Wrong number of args (21) passed to: clojure.lang.PersistentArrayMap
  (b1 1 2) ;=> 2
  (b1 1) ;=> nil

  (def c1 (assoc b1 :good :stuff))
  c1 ;=> {:a 1, :hello :world, :good :stuff}
  (type c1) ;=> ti_yong.alpha.dyna_map/DynamicMap
  (c1 :hello) ;=> :world
  (:hello c1) ;=> :world
  (c1 :hello :big :blue :world) ;=> :repl/exception!
  ; Execution error (ExceptionInfo) at ti-yong.alpha.dyna-map/default-map-invoke (dyna_map.clj:16).
  ; Invalid arity: 4

  :end)
