(ns ti-yong.alpha.dyna-map)

(defn default-map-invoke [env & args]
  (case (count args)
    0 (throw (js/Error. "Invalid arity: 0"))
    1 (get env (first args))
    2 (get env (first args) (second args))
    (throw (js/Error. (str "Invalid arity: " (count args))))))

(defn default-invoke-handler
  [env & args]
  (let [env (assoc (into {} env) :instantiated? true)]
    (if-let [diy-default-invoke (or (get-in env [::methods ::default-invoke])
                                    (get env ::default-invoke))]
      (apply diy-default-invoke env args)
      (throw (js/Error. "No default invoke added")))))

(defn handle-invoke [env & args]
  (let [env (assoc (into {} env) :instantiated? true)]
    (if-let [dynamic-invoke (or (get-in env [::methods ::dyna-invoke])
                                (get env ::dyna-invoke))]
      (apply dynamic-invoke env args)
      (apply default-invoke-handler env args))))

(declare get-methods assoc-method PersistentDynamicMap TransientDynamicMap)

(defn attach-applyer! [pdm]
  (set! (.-cljs$lang$applyTo pdm)
        (fn [args]
          (let [adm (assoc (into {} pdm) :instantiated? true)]
            (apply handle-invoke (assoc adm ::methods (or (::methods adm) (get-methods pdm))) args))))
  pdm)

(defn PDM [facaded-coll methods]
  (let [tform-pre (:ti-yong.alpha.root/tform-pre facaded-coll)
        pre-env (if tform-pre
                  (tform-pre (assoc facaded-coll ::methods methods))
                  facaded-coll)
        meths (or (::methods pre-env) methods)
        user-invoke (or (::default-invoke meths) default-map-invoke)
        new-meths (assoc meths ::default-invoke user-invoke)
        pdm (PersistentDynamicMap. pre-env new-meths)]
    (attach-applyer! pdm)
    pdm))

(defn TDM [facaded-coll methods edit]
  (let [tform-pre (::Transient.tform-pre methods)
        dm (if tform-pre
             (tform-pre facaded-coll)
             facaded-coll)
        tdm (TransientDynamicMap. dm methods edit)]
    (attach-applyer! tdm)
    tdm))

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
  (->> kvs
       (partition 2)
       (reduce (fn [c [k v]]
                 (-assoc-method c k v))
               coll)))

(deftype PersistentDynamicMap [fcoll m]
  Object
  (toString [_coll]
    (if-let [f-pr-str* (:Object/pr-str* m)]
      (f-pr-str* {:fcoll fcoll ::methods m})
      (pr-str* fcoll)))
  (equiv [_this other]
    (if-let [f-equiv (:Object/-equiv m)]
      (f-equiv {:fcoll fcoll ::methods m :other other})
      (-equiv fcoll other)))
  (keys [_coll]
    (if-let [f-es6-iterator (:Object/es6-iterator-keys m)]
      (f-es6-iterator {:fcoll fcoll ::methods m})
      (keys fcoll)))
  (entries [_coll]
    (if-let [f-es6-entries-iterator (:Object/es6-entries-iterator m)]
      (f-es6-entries-iterator {:fcoll fcoll ::methods m})
      (seq fcoll)))
  (values [_coll]
    (if-let [f-es6-iterator (:Object/es6-iterator-values m)]
      (f-es6-iterator {:fcoll fcoll ::methods m})
      (vals fcoll)))
  (has [_coll k]
    (if-let [f-contains? (:Object/contains? m)]
      (f-contains? {:fcoll fcoll ::methods m :k k})
      (contains? fcoll k)))
  (get [_coll k not-found]
    (if-let [f-lookup (:Object/-lookup m)]
      (f-lookup {:fcoll fcoll ::methods m :k k :not-found not-found})
      (-lookup fcoll k not-found)))
  (forEach [_coll f]
    (if-let [f-forEach (:Object/forEach m)]
      (f-forEach {:fcoll fcoll ::methods m :f f})
      (doseq [[k v] fcoll]
        (f v k))))

  ICloneable
  (-clone [_]
    (if-let [f-clone (:ICloneable/-clone m)]
      (f-clone {:fcoll fcoll ::methods m})
      (PDM (clone fcoll) (clone m))))

  IIterable
  (-iterator [_coll]
    (if-let [f-iterator (:IIterable/-iterator m)]
      (f-iterator {:fcoll fcoll ::methods m})
      (-iterator fcoll)))

  IWithMeta
  (-with-meta [_coll new-meta]
    (if-let [f-with-meta (:IWithMeta/-with-meta m)]
      (f-with-meta {:fcoll fcoll ::methods m :new-meta new-meta})
      (PDM (with-meta fcoll new-meta) m)))

  IMeta
  (-meta [_coll]
    (if-let [f-meta (:IMeta/-meta m)]
      (f-meta {:fcoll fcoll ::methods m})
      (meta fcoll)))

  ICollection
  (-conj [_coll entry]
    (if-let [f-conj (:ICollection/-conj m)]
      (f-conj {:fcoll fcoll ::methods m :entry entry})
      (PDM (conj fcoll entry) m)))

  IEmptyableCollection
  (-empty [_coll]
    (if-let [f-empty (:IEmptyableCollection/-empty m)]
      (f-empty {:fcoll fcoll ::methods m})
      (with-meta (.-EMPTY PersistentDynamicMap) (meta fcoll))))

  IEquiv
  (-equiv [_coll other]
    (if-let [f-equiv (:IEquiv/-equiv m)]
      (f-equiv {:fcoll fcoll ::methods m :other other})
      (-equiv fcoll other)))

  IHash
  (-hash [_coll]
    (if-let [f-hash (:IHash/-hash m)]
      (f-hash {:fcoll fcoll ::methods m})
      (hash fcoll)))

  ISeqable
  (-seq [_coll]
    (if-let [f-seq (:ISeqable/-seq m)]
      (f-seq {:fcoll fcoll ::methods m})
      (seq fcoll)))

  ICounted
  (-count [_coll]
    (if-let [f-count (:ICounted/-count m)]
      (f-count {:fcoll fcoll ::methods m})
      (count fcoll)))

  ILookup
  (-lookup [_coll k]
    (if-let [f-lookup (:ILookup/-lookup2 m)]
      (f-lookup {:fcoll fcoll ::methods m :k k})
      (-lookup fcoll k nil)))

  (-lookup [_coll k not-found]
    (if-let [f-lookup (:ILookup/-lookup3 m)]
      (f-lookup {:fcoll fcoll ::methods m :k k :not-found not-found})
      (-lookup fcoll k not-found)))

  IDynamicAssociative
  (-assoc-method [_coll k v]
    (PDM fcoll (assoc m k v)))
  (-contains-method? [_coll k]
    (contains? m k))
  (-method [_coll k]
    (get m k))
  (-get-methods [_coll]
    m)
  (-set-methods [_coll new-methods]
    (PDM fcoll new-methods))
  (-get-coll [_coll]
    fcoll)
  (-dissoc-method [_coll k]
    (PDM fcoll (dissoc m k)))

  IAssociative
  (-assoc [_coll k v]
    (if-let [f-assoc (:IAssociative/-assoc m)]
      (f-assoc {:fcoll fcoll ::methods m :k k :v v})
      (PDM (assoc fcoll k v) m)))
  (-contains-key? [_coll k]
    (if-let [f-contains-key? (:IAssociative/-contains-key? m)]
      (f-contains-key? {:fcoll fcoll ::methods m :k k})
      (contains? fcoll k)))

  IFind
  (-find [_coll k]
    (if-let [f-find (:IFind/-find m)]
      (f-find {:fcoll fcoll ::methods m :k k})
      (-find fcoll k)))

  IMap
  (-dissoc [_coll k]
    (if-let [f-dissoc (:IMap/-dissoc m)]
      (f-dissoc {:fcoll fcoll ::methods m :k k})
      (PDM (dissoc fcoll k) m)))

  IKVReduce
  (-kv-reduce [_coll f init]
    (if-let [f-kv-reduce (:IKVReduce/-kv-reduce m)]
      (f-kv-reduce {:fcoll fcoll ::methods m :f f :init init})
      (-kv-reduce fcoll f init)))

  IFn
  (-invoke [this]
    (handle-invoke (merge fcoll (assoc this :fcoll fcoll ::methods m))))
  (-invoke [this arg1]
    (handle-invoke
     (merge fcoll (assoc this :fcoll fcoll ::methods m))
     arg1))
  (-invoke [this arg1 arg2]
    (handle-invoke
     (merge fcoll (assoc this :fcoll fcoll ::methods m))
     arg1 arg2))
  (-invoke [this arg1 arg2 arg3]
    (handle-invoke
     (merge fcoll (assoc this :fcoll fcoll ::methods m))
     arg1 arg2 arg3))
  (-invoke [this arg1 arg2 arg3 arg4]
    (handle-invoke
     (merge fcoll (assoc this :fcoll fcoll ::methods m))
     arg1 arg2 arg3 arg4))
  (-invoke [this arg1 arg2 arg3 arg4 arg5]
    (handle-invoke
     (merge fcoll (assoc this :fcoll fcoll ::methods m))
     arg1 arg2 arg3 arg4 arg5))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6]
    (handle-invoke
     (assoc this :fcoll fcoll ::methods m)
     arg1 arg2 arg3 arg4 arg5 arg6))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7]
    (handle-invoke
     (assoc this :fcoll fcoll ::methods m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8]
    (handle-invoke
     (assoc this :fcoll fcoll ::methods m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9]
    (handle-invoke
     (assoc this :fcoll fcoll ::methods m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10]
    (handle-invoke
     (assoc this :fcoll fcoll ::methods m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11]
    (handle-invoke
     (assoc this :fcoll fcoll ::methods m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12]
    (handle-invoke
     (assoc this :fcoll fcoll ::methods m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13]
    (handle-invoke
     (assoc this :fcoll fcoll ::methods m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14]
    (handle-invoke
     (assoc this :fcoll fcoll ::methods m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15]
    (handle-invoke
     (assoc this :fcoll fcoll ::methods m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16]
    (handle-invoke
     (assoc this :fcoll fcoll ::methods m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17]
    (handle-invoke
     (assoc this :fcoll fcoll ::methods m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18]
    (handle-invoke
     (assoc this :fcoll fcoll ::methods m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18 arg19]
    (handle-invoke
     (assoc this :fcoll fcoll ::methods m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18 arg19))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18 arg19 arg20]
    (handle-invoke
     (assoc this :fcoll fcoll ::methods m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18 arg19 arg20))
  (-invoke [this arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18 arg19 arg20 rest-args]
    (handle-invoke
     (assoc this :fcoll fcoll ::methods m)
     arg1 arg2 arg3 arg4 arg5 arg6 arg7 arg8 arg9 arg10 arg11 arg12 arg13 arg14 arg15 arg16 arg17 arg18 arg19 arg20 rest-args))

  IEditableCollection
  (-as-transient [_coll]
    (if-let [f-as-transient (:IEditableCollection/-as-transient m)]
      (f-as-transient {:fcoll fcoll ::methods m})
      (TDM (transient fcoll) (transient m) true))))

(extend-protocol IPrintWithWriter
  PersistentDynamicMap
  (-pr-writer [coll writer opts]
    (print-map coll cljs.core/pr-writer writer opts)))

(set! (.-EMPTY PersistentDynamicMap) (PDM {} {::default-invoke default-map-invoke}))

(set! (.-fromArray PersistentDynamicMap)
      (fn [arr ^boolean no-clone]
        (let [arr (if no-clone arr (aclone arr))
              len (alength arr)]
          (loop [i 0 ret (transient (.-EMPTY PersistentDynamicMap))]
            (if (< i len)
              (recur (+ i 2)
                     (-assoc! ret (aget arr i) (aget arr (inc i))))
              (-persistent! ret))))))
 
(set! (.-fromArrays PersistentDynamicMap)
      (fn [ks vs]
        (let [len (alength ks)]
          (loop [i 0 ^not-native out (transient (.-EMPTY PersistentDynamicMap))]
            (if (< i len)
              (if (<= (alength vs) i)
                (throw (js/Error. (str "No value supplied for key: " (aget ks i))))
                (recur (inc i) (-assoc! out (aget ks i) (aget vs i))))
              (persistent! out))))))

(set! (.-createWithCheck PersistentDynamicMap)
      (fn [arr]
        (let [len (alength arr)
              ret (transient (.-EMPTY PersistentDynamicMap))]
          (loop [i 0]
            (when (< i len)
              (-assoc! ret (aget arr i) (aget arr (inc i)))
              (if (not= (-count ret) (inc (/ i 2)))
                (throw (js/Error. (str "Duplicate key: " (aget arr i))))
                (recur (+ i 2)))))
          (-persistent! ret))))

(es6-iterable PersistentDynamicMap)

(deftype TransientDynamicMap [ftcoll m ^:mutable ^boolean edit]
  Object
  (conj! [_tcoll o]
    (if-let [f-conj! (:Transient.Object/conj! m)]
      (f-conj! {:fcoll ftcoll ::methods m :o o})
      (conj! ftcoll o)))
  (assoc! [_tcoll k v]
    (if-let [f-assoc! (:Transient.Object/assoc! m)]
      (f-assoc! {:fcoll ftcoll ::methods m :k k :v v})
      (assoc! ftcoll k v)))
  (without! [_tcoll k]
    (if-let [f-without! (:Transient.Object/without! m)]
      (f-without! {:fcoll ftcoll ::methods m :k k})
      ^js (.without! ftcoll k)))
  (persistent! [_tcoll]
    (if-let [f-persistent! (:Transient.Object/persistent! m)]
      (f-persistent! {:fcoll ftcoll ::methods m})
      (if edit
        (do (set! edit nil)
            (PDM (persistent! ftcoll) (persistent! m)))
        (throw (js/Error. "persistent! called twice")))))

  ICounted
  (-count [_coll]
    (if-let [f-count (:Transient.ICounted/-count m)]
      (f-count {:fcoll ftcoll ::methods m})
      (if edit
        (count ftcoll)
        (throw (js/Error. "count after persistent!")))))

  ILookup
  (-lookup [_tcoll k]
    (if-let [f-lookup (:Transient.ILookup/-lookup2 m)]
      (f-lookup {:fcoll ftcoll ::methods m :k k})
      (-lookup ftcoll k)))

  (-lookup [_tcoll k not-found]
    (if-let [f-lookup (:Transient.ILookup/-lookup3 m)]
      (f-lookup {:fcoll ftcoll ::methods m :k k :not-found not-found})
      (-lookup ftcoll k not-found)))

  ITransientCollection
  (-conj! [_tcoll val]
    (if-let [f-conj! (:Transient.ITransientCollection/-conj! m)]
      (f-conj! {:fcoll ftcoll ::methods m :val val})
      (conj! ftcoll val)))

  (-persistent! [_tcoll]
    (if-let [f-persistent! (:Transient.ITransientCollection/-persistent! m)]
      (f-persistent! {:fcoll ftcoll ::methods m})
      (persistent! ftcoll)))

  ITransientAssociative
  (-assoc! [_tcoll key val]
    (if-let [f-assoc! (:Transient.ITransientAssociative/-assoc! m)]
      (f-assoc! {:fcoll ftcoll ::methods m :key key :val val})
      (assoc! ftcoll key val)))

  ITransientMap
  (-dissoc! [_tcoll key]
    (if-let [f-dissoc! (:Transient.ITransientMap/-dissoc! m)]
      (f-dissoc! {:fcoll ftcoll ::methods m :key key})
      (dissoc! ftcoll key))))

(def empty-dyna-map (.-EMPTY PersistentDynamicMap))

(defn dyna-map [& args]
  (merge empty-dyna-map
         (loop [in (seq args), out (transient empty-dyna-map)]
           (if in
             (let [in' (next in)]
               (if (nil? in')
                 (throw (js/Error. (str "No value supplied for key: " (first in))))
                 (recur (next in') (assoc! out (first in) (first in')))))
             (-persistent! out))))) ; <- something broken here - should be returning a dyn map
#_
(comment

  (def a (dyna-map :a 1))
  a ;=> {:a 1}
  (type a) ;=> ti-yong.alpha.dyna-map/PersistentDynamicMap
  (def b (assoc a :x 1))
  b ;=> {:a 1, :x 1}
  (type b) ;=> ti-yong.alpha.dyna-map/PersistentDynamicMap

  (def root (dyna-map))
  root ;=> {}
  (type root) ;=> ti-yong.alpha.dyna-map/PersistentDynamicMap
  (root) ;=> :repl/exception!
  ; Execution error (Error) at (<cljs repl>:1).
  ; Invalid arity: 0
  (root :args) ;=> nil
  (root 1) ;=> nil
  (root 1 2) ;=> 2  ; <- 1 is the lookup key and 2 is the non-found value (for two arity calls on maps)
  (root 1 2 3) ;=> :repl/exception!
  ; Execution error (Error) at (<cljs repl>:1).
  ; Invalid arity: 3
  (apply root 1 [2]) ;=> 2

  (apply root 3 [1 2 3]) ;=> :repl/exception!
   ; Execution error (Error) at (<cljs repl>:1).
   ; Invalid arity: 4

  (def a1 (assoc empty-dyna-map :a 1))
  a1 ;=> {:a 1}
  (type a1) ;=> ti-yong.alpha.dyna-map/PersistentDynamicMap
  (a1 1 2 3 4 5) ;=> :repl/exception!
  ; Execution error (Error) at (<cljs repl>:1).
  ; No default invoke added
  (a1 1) ;=> nil
  (empty-dyna-map 1) ;=> nil

  (def b1 (assoc a1 :hello :world))
  (type b1) ;=> ti-yong.alpha.dyna-map/PersistentDynamicMap
  b1 ;=> {:a 1, :hello :world}
  (apply b1 1 [2]) ;=> 2  ;<- not-found
  (b1 [1 2 3 4]) ;=> nil
  (apply b1 (range 24)) ;=> :repl/exception!
  ; Execution error (Error) at (<cljs repl>:1).
  ; Invalid arity: 24
  (apply b1 (range 21)) ;=> :repl/exception!
  ; Execution error (Error) at (<cljs repl>:1).
  ; Invalid arity: 21
  (b1 1 2) ;=> 2
  (b1 1) ;=> nil

  (def c1 (assoc b1 :good :stuff))
  c1 ;=> {:a 1, :hello :world, :good :stuff}
  (type c1) ;=> ti-yong.alpha.dyna-map/PersistentDynamicMap
  (c1 :hello) ;=> :world
  (:hello c1) ;=> :world
  (c1 :hello :big :blue :world) ;=> :repl/exception!
  ; Execution error (Error) at (<cljs repl>:1).
  ; Invalid arity: 21

  :end)
  
  
