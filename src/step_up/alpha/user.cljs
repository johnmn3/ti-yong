(ns step-up.alpha.user
  (:require
   [cljs.math :as math]
   [clojure.edn :as edn]
   [step-up.alpha.dyna-map :as fm :refer [dyna-map]]
   [step-up.alpha.root :as r :refer [root]]
   [step-up.alpha.transformer :refer [transformer]]
   [perc.core])) ; <- adds #%( ... %:some-key ...) anonymous fn syntax sugur

(defn main [& args]
  (println :main :args args)
  args)

(comment

  (def user (dyna-map :first-name "John" :last-name "Doe"))

  (def u1
    (-> user
        (update :id conj ::u1)
        (update :tf conj
                ::u1 #(-> % (assoc :hello :world)))))

  u1 ;=> {:first-name "John", :last-name "Doe", :id (:dev.user/u1), :tf (#object [Function] :dev.user/u1)}
  (u1 1) ;=> nil

  (def t1
    (-> root
        (update :id conj ::t1)
        (update :tf conj
                ::t1 #(-> % (assoc :hello :world)))))

  t1 ;=> {:args [], :tf-pre [], :step-up.alpha.dyna-map/tform-pre #object [step-up$alpha$root$preform], :step-up.alpha.dyna-map/outs ...
  (t1 1) ;=> 1

  ;;
  ;; :with mixin inheritance
  ;;

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

  (+s "1" 2) ;=> 3

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

  ;; clojure multi method example:
  (defmulti area :Shape)
  (defn mk-rect [wd ht] {:Shape :Rect :wd wd :ht ht})
  (defn mk-circle [radius] {:Shape :Circle :radius radius})
  (defmethod area :Rect [r]
    (* (:wd r) (:ht r)))
  (defmethod area :Circle [c]
    (* math/PI (* (:radius c) (:radius c))))
  (defmethod area :default [x] :oops)
  (def r (mk-rect 4 13))
  (def c (mk-circle 12))
  (area r) ;=> 52
  (area c) ;=> 452.3893421169302
  (area {}) ;=> :oops


 ;; Stateful Transformers

 ;; Dispatch Patterns

 ;; One example might be a stateless multi method system that accumulates methods via transformations

 ;; Each transformation creates a new multi method transformer
 ;; Each multi method extends the available methods in the prior transformer

 ;; Methods only accumulate. They are not shared up the ancestry chain.
 ;; Nor are they shared laterally between chains, only down a chain, unless behaviors
 ;; are manually composed in using regular data update tools available in Clojure.

 ;; first, a utility function to extract a given implementation from those available

  (defn get-fn [methods env args]
    (or (->> methods
             (map (fn [[pred afn]]
                    (when (pred (merge env (first args)))
                      afn)))
             (filter identity)
             first)
        (:default methods)))

 ;; `ancestral-multi-methods` adds a transform that finds an implementation
 ;; for a given input, similar to multi methods.

 ;; Here, we're taking a `transformer`
 ;; and transforming it with a transform that associates to the environment an
 ;; `:op` that dispatches on predicates defined by ancestors.

  (def ancestral-multi-methods
    (-> transformer
        (update :id conj ::ancestral-multi-methods)
        (update :tf conj
                ::ancestral-multi-methods
                #%(assoc % :op #%%(if-let [op (get-fn %:methods % [%%])]
                                    (apply op (((or %:multi-env identity) %) [%%]))
                                    (throw (js/Error. (str "No default impl provided"))))))))

  (ancestral-multi-methods {:what :does :this :do?})
 ; Execution error (Error) at (<cljs repl>:1).
 ; No default impl provided

 ;; Now we can use `ancestral-multi-methods` in a similar way as multi functions, providing
 ;; a multi fn and a default implementation.

  (def shape
    (-> ancestral-multi-methods
        (update :id conj ::shape)
        (assoc :multi-env (fn [env] #%(into [(merge %first env)] %rest)))
        (update :methods assoc
                :default #%(println "no matching method for" %:args%first
                                    "\nin multi function ancestry:\n"
                                    %:id))))

  (shape {:radius 20}) ;=> nil
 ; no matching method for {:radius 20} 
 ; in multi function ancestry:
 ;  [:step-up.alpha/root :step-up.alpha/transformer :dev.user/ancestral :dev.user/shape]

 ;; Now that we have a `shape` we can create chains hierarchically:

  (def box
    (-> shape
        (update :id conj ::box)
        (update :methods assoc
                #%(and %:wd %:ht %:ln) #%(* %:wd %:ht %:ln))))

 ;; Great, now we have an implementation. Let's try it:

  (box {:wd 12 :ht 12 :ln 1}) ;=> 144

 ;; And of course a failure to match a predicate gives a hint:

  (box {:wd 12 :ht 12}) ;=> nil
 ; no matching method for {:wd 12, :ht 12} 
 ; in multi function ancestry:
 ;  [:step-up.alpha/root :step-up.alpha/transformer :dev.user/ancestral :dev.user/shape :dev.user/box]

 ;; We can simulate a 2D space with a box by keeping the length dimension as 1.

 ;; Let's do that to simulate a rectangle:

  (def rect
    (-> box
        (update :id conj ::rect)
        (assoc :ln 1)))

 ;; Now we can satisfy a rect semantic without having to add another dispatch.
 ;; Notice, this new function behavior was added purely through data transformation.

  (rect {:wd 5 :ht 5}) ;=> 25

 ;; The way we've defined it here, the `box`'s `:ln` takes precedence, enforcing a
 ;; rectangle semantic.

  (rect {:wd 5 :ht 5 :ln 2}) ;=> 25

 ;; And box still doesn't know how to act like a rectangle:

  (box {:wd 5 :ht 5}) ;=> nil
 ; no matching method for {:wd 5, :ht 5} 
 ; in multi function ancestry:
 ;  [:step-up.alpha/root :step-up.alpha/transformer :dev.user/ancestral :dev.user/shape :dev.user/box]

 ;; For `square`, let's add a new dispatch:

  (def square
    (-> rect
        (update :id conj ::square)
        (update :methods assoc #% %:size #%(* %:size %:size))))

  (square {:size 5}) ;=> 25

 ;; Notice, `square` is polymorphic like a multimethod over all of its ancestors'
 ;; implementations:

  (square {:wd 5 :ht 2}) ;=> 10

 ;; While keeping `rect`'s over-riding of `box`'s `:ln` value with 1:

  (square {:wd 5 :ht 2 :ln 5}) ;=> 10

  (square {:wd 5 :height 2 :ln 5}) ;=> nil
 ; no matching method for {:wd 5, :height 2, :ln 5} 
 ; in multi function ancestry:
 ;  [:step-up.alpha/root :step-up.alpha/transformer :dev.user/ancestral :dev.user/shape :dev.user/box :dev.user/rect :dev.user/square]

 ;; Let's add a `cube` implementation off of box:

  (def cube
    (-> box
        (update :id conj ::cube)
        (update :methods assoc
                #% %:size #%(* %:size %:size %:size))))

  (cube {:size 5}) ;=> 125

 ;; Uh oh, didn't we already implement a dispatch for `:size`?

  (square {:size 5}) ;=> 25

 ;; Still works! `square` and `cube` belong to different ancestries, so their
 ;; predicates don't shadow each other.

 ;; contrary to `rect`'s stricter `:ln` semantic, `cube`'s `:ln` can take
 ;; precedence over `box`'s:

  (cube {:wd 5 :ht 5 :ln 3}) ;=> 75

 ;; I mean, I wouldn't call it a `cube`, but you can, if you want.

  (square {:wd 5 :ht 5 :ln 3}) ;=> 25

 ;; Still squarish.

 ;; For completeness sake, here's the circle impl:

  (def circle
    (-> shape
        (update :id conj ::circle)
        (update :methods assoc #% %:radius #%(* math/PI %:radius %:radius))))

  (circle {:radius 17}) ;=> 907.9202768874503

 ;; `circle` isn't in the box hierarchy

  (circle {:wd 5 :ht 5 :ln 3}) ;=> nil
 ; no matching method for {:wd 5, :ht 5, :ln 3} 
 ; in multi function ancestry:
 ;  [:step-up.alpha/root :step-up.alpha/transformer :dev.user/ancestral :dev.user/shape :dev.user/circle]

  (circle {:size 2}) ;=> nil
 ; no matching method for {:size 2} 
 ; in multi function ancestry:
 ;  [:step-up.alpha/root :step-up.alpha/transformer :dev.user/ancestral :dev.user/shape :dev.user/circle]

  (circle {:radius 17.001}) ;=> 908.0270941792651

  :end)
