(ns step-up.alpha.dyna-map)

(defn identities [& args]
  (case (count args)
    0 nil
    1 (first args)
    args))

(defn- ids [& {:as m :keys [args]}]
  (let [[_this & args] args]
    (apply identities args)))

(declare PersistentDynamicMap TransientDynamicMap)

(defn dyna-invoke
  [env & args]
  (if-let [diy-transformer-transformer (::dyna-invoke (:m env))]
    (apply diy-transformer-transformer env args)
    (throw (js/Error. "No default transformer transformer added"))))

(defn handle-invoke [m & args]
  ;; (println :handle-invoke :m m)
  (let [the-default-invoke (get-in m [:m ::dyna-invoke])]

    ;; (println :the-default-invoke the-default-invoke)
    (if the-default-invoke
      (the-default-invoke m args)
      (apply dyna-invoke m args))))

(declare get-methods)

(defn PTHM [facaded-coll methods]
  (let [pthm (PersistentDynamicMap. facaded-coll methods)
        tform-pre (::tform-pre pthm identity)
        new-pthm (or (tform-pre pthm) pthm)]
    (set! (.-cljs$lang$applyTo new-pthm)
          (fn [args]
            (apply handle-invoke (merge new-pthm {:m (get-methods new-pthm)}) args)))
    new-pthm))

(defn TTHM [facaded-coll methods edit]
  (let [tthm (TransientDynamicMap. facaded-coll methods edit)
        tform-pre (::Transient.tform-pre tthm identity)
        new-tthm (or (tform-pre tthm) tthm)]
    (set! (.-cljs$lang$applyTo new-tthm)
          (fn [& args]
            (apply handle-invoke (merge facaded-coll new-tthm) args)))
    new-tthm))

(defprotocol IDynamicAssociative
  (-assoc-method [coll k v])
  (-contains-method? [coll k])
  (-method [coll k])
  (-get-methods [coll])
  (-set-methods [coll new-methods])
  (-get-coll [coll])
  (-dissoc-method [coll k]))

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
  (let [res (->> kvs
                 (partition 2)
                 (reduce (fn [c [k v]]
                           (-assoc-method c k v))
                         coll))
        fcoll (get-coll res)
        new-dyna (PTHM fcoll res)]
    (set! (.-cljs$lang$applyTo new-dyna)
          (fn [& args]
            (apply handle-invoke new-dyna args)))
    res))

(def PTHM-fields
  #{::dyna-invoke
    :Object/pr-str* :Object/-equiv :Object/es6-iterator-keys :Object/es6-entries-iterator
    :Object/es6-iterator-values :Object/contains? :Object/-lookup :Object/forEach
    :ICloneable/PersistentDynamicMap :IIterable/-iterator :IWithMeta/-with-meta
    :IMeta/-meta :ICollection/-conj :IEmptyableCollection/-empty :IEquiv/-equiv
    :IHash/-hash :ISeqable/-seq :ICounted/-count :ILookup/-lookup2 :ILookup/-lookup3
    :IAssociative/-assoc :IAssociative/-contains-key? :IFind/-find :IMap/-dissoc
    :IKVReduce/-kv-reduce :IEditableCollection/-as-transient
    :IFn/-invoke0 :IFn/-invoke1 :IFn/-invoke2 :IFn/-invoke3 :IFn/-invoke4 :IFn/-invoke5
    :IFn/-invoke6 :IFn/-invoke7 :IFn/-invoke8 :IFn/-invoke9 :IFn/-invoke10 :IFn/-invoke11
    :IFn/-invoke12 :IFn/-invoke13 :IFn/-invoke14 :IFn/-invoke15 :IFn/-invoke16 :IFn/-invoke17
    :IFn/-invoke18 :IFn/-invoke19 :IFn/-invoke20 :IFn/-invoke-rest-args :IFn/-invoke-any})

(def TTHM-fields
  #{:Transient.Object/conj! :Transient.Object/assoc! :Transient.Object/without! :Transient.Object/persistent!
    :Transient.ICounted/-count :Transient.ILookup/-lookup2 :Transient.ILookup/-lookup3 :Transient.ITransientCollection/-conj!
    :Transient.ITransientCollection/-persistent! :Transient.ITransientAssociative/-assoc! :Transient.ITransientMap/-dissoc!
    :Transient.IFn/-invoke0 :Transient.IFn/-invoke1 :Transient.IFn/-invoke2 :Transient.IFn/-invoke3 :Transient.IFn/-invoke4
    :Transient.IFn/-invoke5 :Transient.IFn/-invoke6 :Transient.IFn/-invoke7 :Transient.IFn/-invoke8 :Transient.IFn/-invoke9
    :Transient.IFn/-invoke10 :Transient.IFn/-invoke11 :Transient.IFn/-invoke12 :Transient.IFn/-invoke13 :Transient.IFn/-invoke14
    :Transient.IFn/-invoke15 :Transient.IFn/-invoke16 :Transient.IFn/-invoke17 :Transient.IFn/-invoke18 :Transient.IFn/-invoke19
    :Transient.IFn/-invoke20 :Transient.IFn/-invoke-rest-args
    :Transient.IFn/-invoke-any :Transient.IAssociative/-assoc})

(deftype PersistentDynamicMap [fcoll m]
  Object
  (toString [_coll]
    (if-let [f-pr-str* (:Object/pr-str* m)]
      (f-pr-str* {:fcoll fcoll :m m})
      (pr-str* fcoll)))
  (equiv [_this other]
    (if-let [f-equiv (:Object/-equiv m)]
      (f-equiv {:fcoll fcoll :m m :other other})
      (-equiv fcoll other)))
  (keys [_coll]
    (if-let [f-es6-iterator (:Object/es6-iterator-keys m)]
      (f-es6-iterator {:fcoll fcoll :m m})
      (es6-iterator (keys fcoll))))
  (entries [_coll]
    (if-let [f-es6-entries-iterator (:Object/es6-entries-iterator m)]
      (f-es6-entries-iterator {:fcoll fcoll :m m})
      (es6-entries-iterator (seq fcoll))))
  (values [_coll]
    (if-let [f-es6-iterator (:Object/es6-iterator-values m)]
      (f-es6-iterator {:fcoll fcoll :m m})
      (es6-iterator (vals fcoll))))
  (has [_coll k]
    (if-let [f-contains? (:Object/contains? m)]
      (f-contains? {:fcoll fcoll :m m :k k})
      (contains? fcoll k)))
  (get [_coll k not-found]
    (if-let [f-lookup (:Object/-lookup m)]
      (f-lookup {:fcoll fcoll :m m :k k :not-found not-found})
      (-lookup fcoll k not-found)))
  (forEach [_coll f]
    (if-let [f-forEach (:Object/forEach m)]
      (f-forEach {:fcoll fcoll :m m :f f})
      (doseq [[k v] fcoll]
        (f v k))))

  ICloneable
  (-clone [_]
    (if-let [f-clone (:ICloneable/-clone m)]
      (f-clone {:fcoll fcoll :m m})
      (PTHM (clone fcoll) (clone m))))

  IIterable
  (-iterator [_coll]
    (if-let [f-iterator (:IIterable/-iterator m)]
      (f-iterator {:fcoll fcoll :m m})
      (-iterator fcoll)))

  IWithMeta
  (-with-meta [_coll new-meta]
    (if-let [f-with-meta (:IWithMeta/-with-meta m)]
      (f-with-meta {:fcoll fcoll :m m :new-meta new-meta})
      (PTHM (with-meta fcoll new-meta) m)))

  IMeta
  (-meta [_coll]
    (if-let [f-meta (:IMeta/-meta m)]
      (f-meta {:fcoll fcoll :m m})
      (meta fcoll)))

  ICollection
  (-conj [_coll entry]
    (if-let [f-conj (:ICollection/-conj m)]
      (f-conj {:fcoll fcoll :m m :entry entry})
      (let [k (first entry)
            field? (or (PTHM-fields k) (TTHM-fields k))]
        (if false ;field?
          (PTHM fcoll (conj m entry))
          (PTHM (conj fcoll entry) m)))))

  IEmptyableCollection
  (-empty [_coll]
    (if-let [f-empty (:IEmptyableCollection/-empty m)]
      (f-empty {:fcoll fcoll :m m})
      (with-meta (.-EMPTY PersistentDynamicMap) (meta fcoll))))

  IEquiv
  (-equiv [_coll other]
    (if-let [f-equiv (:IEquiv/-equiv m)]
      (f-equiv {:fcoll fcoll :m m :other other})
      (-equiv fcoll other)))

  IHash
  (-hash [_coll]
    (if-let [f-hash (:IHash/-hash m)]
      (f-hash {:fcoll fcoll :m m})
      (hash fcoll)))

  ISeqable
  (-seq [_coll]
    (if-let [f-seq (:ISeqable/-seq m)]
      (f-seq {:fcoll fcoll :m m})
      (seq fcoll)))

  ICounted
  (-count [_coll]
    (if-let [f-count (:ICounted/-count m)]
      (f-count {:fcoll fcoll :m m})
      (count fcoll)))

  ILookup
  (-lookup [_coll k]
    (if-let [f-lookup (:ILookup/-lookup2 m)]
      (f-lookup {:fcoll fcoll :m m :k k})
      (-lookup fcoll k nil)))

  (-lookup [_coll k not-found]
    (if-let [f-lookup (:ILookup/-lookup3 m)]
      (f-lookup {:fcoll fcoll :m m :k k :not-found not-found})
      (-lookup fcoll k not-found)))

  IDynamicAssociative
  (-assoc-method [_coll k v]
    (PTHM fcoll (assoc m k v)))
  (-contains-method? [_coll k]
    (contains? m k))
  (-method [_coll k]
    (get m k))
  (-get-methods [_coll]
    m)
  (-set-methods [_coll new-methods]
    (PTHM fcoll new-methods))
  (-get-coll [_coll]
    fcoll)
  (-dissoc-method [_coll k]
    (PTHM fcoll (dissoc m k)))

  IAssociative
  (-assoc [_coll k v]
    (if-let [f-assoc (:IAssociative/-assoc m)]
      (f-assoc {:fcoll fcoll :m m :k k :v v})
      (let [field? (or (PTHM-fields k) (TTHM-fields k) (= ::dyna-invoke k))]
        (if false #_field?
            (PTHM fcoll (assoc m k v))
            (PTHM (assoc fcoll k v) m)))))
  (-contains-key? [_coll k]
    (if-let [f-contains-key? (:IAssociative/-contains-key? m)]
      (f-contains-key? {:fcoll fcoll :m m :k k})
      (contains? fcoll k)))

  IFind
  (-find [_coll k]
    (if-let [f-find (:IFind/-find m)]
      (f-find {:fcoll fcoll :m m :k k})
      (-find fcoll k)))

  IMap
  (-dissoc [_coll k]
    (if-let [f-dissoc (:IMap/-dissoc m)]
      (f-dissoc {:fcoll fcoll :m m :k k})
      (let [field? (or (PTHM-fields k) (TTHM-fields k))]
        (if false ;field?
          (PTHM fcoll (dissoc m k))
          (PTHM (dissoc fcoll k) m)))))

  IKVReduce
  (-kv-reduce [_coll f init]
    (if-let [f-kv-reduce (:IKVReduce/-kv-reduce m)]
      (f-kv-reduce {:fcoll fcoll :m m :f f :init init})
      (-kv-reduce fcoll f init)))

  IFn
  (-invoke [this]
    (handle-invoke (merge fcoll (assoc this :fcoll fcoll :m m))))
  (-invoke [this arg1]
    (handle-invoke
     (merge fcoll (assoc this :fcoll fcoll :m m))
     arg1))
  (-invoke [this arg1 arg2]
    (handle-invoke
     (merge fcoll (assoc this :fcoll fcoll :m m))
     arg1 arg2))
  (-invoke [this arg1 arg2 arg3]
    (handle-invoke
     (merge fcoll (assoc this :fcoll fcoll :m m))
     arg1 arg2 arg3))
  (-invoke [this arg1 arg2 arg3 arg4]
    (handle-invoke
     (merge fcoll (assoc this :fcoll fcoll :m m))
     arg1 arg2 arg3 arg4))
  (-invoke [this arg1 arg2 arg3 arg4 arg5]
    (handle-invoke
     (merge fcoll (assoc this :fcoll fcoll :m m))
     arg1 arg2 arg3 arg4 arg5))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6]
    (handle-invoke
     (assoc this :fcoll fcoll :m m)
     arg1 arg2 arg3 arg4 arg5 arg6))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7]
    (handle-invoke
     (assoc this :fcoll fcoll :m m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8]
    (handle-invoke
     (assoc this :fcoll fcoll :m m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9]
    (handle-invoke
     (assoc this :fcoll fcoll :m m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10]
    (handle-invoke
     (assoc this :fcoll fcoll :m m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11]
    (handle-invoke
     (assoc this :fcoll fcoll :m m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12]
    (handle-invoke
     (assoc this :fcoll fcoll :m m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13]
    (handle-invoke
     (assoc this :fcoll fcoll :m m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14]
    (handle-invoke
     (assoc this :fcoll fcoll :m m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15]
    (handle-invoke
     (assoc this :fcoll fcoll :m m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16]
    (handle-invoke
     (assoc this :fcoll fcoll :m m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17]
    (handle-invoke
     (assoc this :fcoll fcoll :m m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18]
    (handle-invoke
     (assoc this :fcoll fcoll :m m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18 arg19]
    (handle-invoke
     (assoc this :fcoll fcoll :m m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18 arg19))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18 arg19 arg20]
    (handle-invoke
     (assoc this :fcoll fcoll :m m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18 arg19 arg20))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18 arg19 arg20 rest-args]
    (handle-invoke
     (assoc this :fcoll fcoll :m m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18 arg19 arg20 rest-args))

  IEditableCollection
  (-as-transient [_coll]
    (if-let [f-as-transient (:IEditableCollection/-as-transient m)]
      (f-as-transient {:fcoll fcoll :m m})
      (TTHM (transient fcoll) (transient m) true))))

(extend-protocol IPrintWithWriter
  PersistentDynamicMap
  (-pr-writer [coll writer opts]
    (print-map coll cljs.core/pr-writer writer opts)))

(set! (.-EMPTY PersistentDynamicMap)
      (let [pthm (PTHM {} {})]
        (set! (.-cljs$lang$applyTo pthm) ids)
        pthm))

(set! (.-fromArray PersistentDynamicMap)
      (fn [arr ^boolean no-clone]
        (let [arr (if no-clone arr (aclone arr))
              len (alength arr)
              pthm (loop [i 0 ret (transient (.-EMPTY PersistentDynamicMap))]
                     (if (< i len)
                       (recur (+ i 2)
                              (-assoc! ret (aget arr i) (aget arr (inc i))))
                       (-persistent! ret)))]
          (set! (.-cljs$lang$applyTo pthm) ids)
          pthm)))

(set! (.-fromArrays PersistentDynamicMap)
      (fn [ks vs]
        (let [len (alength ks)
              pthm (loop [i 0 ^not-native out (transient (.-EMPTY PersistentDynamicMap))]
                     (if (< i len)
                       (if (<= (alength vs) i)
                         (throw (js/Error. (str "No value supplied for key: " (aget ks i))))
                         (recur (inc i) (-assoc! out (aget ks i) (aget vs i))))
                       (persistent! out)))]
          (set! (.-cljs$lang$applyTo pthm) ids)
          pthm)))

(set! (.-createWithCheck PersistentDynamicMap)
      (fn [arr]
        (let [len (alength arr)
              ret (transient (.-EMPTY PersistentDynamicMap))
              pthm (do (loop [i 0]
                         (when (< i len)
                           (-assoc! ret (aget arr i) (aget arr (inc i)))
                           (if (not= (-count ret) (inc (/ i 2)))
                             (throw (js/Error. (str "Duplicate key: " (aget arr i))))
                             (recur (+ i 2)))))
                       (-persistent! ret))]
          (set! (.-cljs$lang$applyTo pthm) ids)
          pthm)))

(es6-iterable PersistentDynamicMap)

(deftype TransientDynamicMap [ftcoll m ^:mutable ^boolean edit]
  Object
  (conj! [_tcoll o]
    (if-let [f-conj! (:Transient.Object/conj! m)]
      (f-conj! {:fcoll ftcoll :m m :o o})
      (conj! ftcoll o)))
  (assoc! [_tcoll k v]
    (if-let [f-assoc! (:Transient.Object/assoc! m)]
      (f-assoc! {:fcoll ftcoll :m m :k k :v v})
      (let [field? (or (PTHM-fields k) (TTHM-fields k))]
        (if false ;field?
          (assoc! m k v)
          (assoc! ftcoll k v)))))
  (without! [_tcoll k]
    (if-let [f-without! (:Transient.Object/without! m)]
      (f-without! {:fcoll ftcoll :m m :k k})
      ^js (.without! ftcoll k)))
  (persistent! [_tcoll]
    (if-let [f-persistent! (:Transient.Object/persistent! m)]
      (f-persistent! {:fcoll ftcoll :m m})
      (if edit
        (do (set! edit nil)
            (PTHM (persistent! ftcoll) (persistent! m)))
        (throw (js/Error. "persistent! called twice")))))

  ICounted
  (-count [_coll]
    (if-let [f-count (:Transient.ICounted/-count m)]
      (f-count {:fcoll ftcoll :m m})
      (if edit
        (count ftcoll)
        (throw (js/Error. "count after persistent!")))))

  ILookup
  (-lookup [_tcoll k]
    (if-let [f-lookup (:Transient.ILookup/-lookup2 m)]
      (f-lookup {:fcoll ftcoll :m m :k k})
      (-lookup ftcoll k)))

  (-lookup [_tcoll k not-found]
    (if-let [f-lookup (:Transient.ILookup/-lookup3 m)]
      (f-lookup {:fcoll ftcoll :m m :k k :not-found not-found})
      (-lookup ftcoll k not-found)))

  ITransientCollection
  (-conj! [_tcoll val]
    (if-let [f-conj! (:Transient.ITransientCollection/-conj! m)]
      (f-conj! {:fcoll ftcoll :m m :val val})
      (conj! ftcoll val)))

  (-persistent! [_tcoll]
    (if-let [f-persistent! (:Transient.ITransientCollection/-persistent! m)]
      (f-persistent! {:fcoll ftcoll :m m})
      (persistent! ftcoll)))

  ITransientAssociative
  (-assoc! [_tcoll key val]
    (if-let [f-assoc! (:Transient.ITransientAssociative/-assoc! m)]
      (f-assoc! {:fcoll ftcoll :m m :key key :val val})
      (assoc! ftcoll key val)))

  ITransientMap
  (-dissoc! [_tcoll key]
    (if-let [f-dissoc! (:Transient.ITransientMap/-dissoc! m)]
      (f-dissoc! {:fcoll ftcoll :m m :key key})
      (dissoc! ftcoll key))))

(defn default-invoke [env args]
  (case (count args)
    0 (throw (js/Error. "Invalid arity: 0"))
    1 (get env (first args))
    2 (get env (first args) (second args))
    (throw (js/Error. (str "Invalid arity: " (count args))))))

(def empty-transformer-map
  (let [etm (.-EMPTY PersistentDynamicMap)]
    etm))

(defn dyna-map [& args]
  (-> (->> args
           (partition 2)
           (map vec)
           (into {})
           (merge (.-EMPTY PersistentDynamicMap)))
      (assoc-method ::dyna-invoke default-invoke))
  #_(loop [in (seq args), out (transient (.-EMPTY PersistentDynamicMap))]
      (if in
        (let [in' (next in)]
          (if (nil? in')
            (throw (js/Error. (str "No value supplied for key: " (first in))))
            (recur (next in') (assoc! out (first in) (first in')))))
        (persistent! out))))
#_
(comment

  (def a (dyna-map :a 1))
  a
  (type a)
  (def b (assoc a :x 1))
  b
  (type b)

  (def root (dyna-map))
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
  ; constructor ; tran-map ; tfmr-map ; tform
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
  
  
