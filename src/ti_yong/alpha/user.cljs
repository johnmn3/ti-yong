(ns ti-yong.alpha.user
  (:require
   [cljs.math :as math]
   [clojure.edn :as edn]
   [ti-yong.alpha.dyna-map :as dm :refer [dyna-map]]
   [ti-yong.alpha.root :as r :refer [root]]
   [ti-yong.alpha.transformer :refer [transformer]]
   [perc.core] ; <- adds #%( ... %:some-key ...) anonymous fn syntax sugur
   [clojure.spec.alpha :as s]))

(def t1
 {:id [::root]
  :args []
  ::dm/tform-pre +
  :tf-pre []
  ::dm/ins +
  :in []
  ::dm/tform +
  :tf []
  ::dm/outs +
  :out []
  ::dm/tform-end +
  :tf-end []})

;; :id :args ::dm/tform-pre :tf-pre ::dm/ins :in ::dm/tform :tf ::dm/outs :out ::dm/tform-end :tf-end
(s/def ::dm/id vector?)
(s/def ::dm/args vector?)
(s/def ::dm/tform-pre fn?)
(s/def ::dm/tf-pre vector?)
(s/def ::dm/ins fn?)
(s/def ::dm/in vector?)
(s/def ::dm/tform fn?)
(s/def ::dm/tf vector?)
(s/def ::dm/outs fn?)
(s/def ::dm/out vector?)
(s/def ::dm/tform-end fn?)
(s/def ::dm/tf-end vector?)

(s/def ::transformer
  (s/keys :req [::dm/tform-pre ::dm/ins ::dm/tform ::dm/outs ::dm/tform-end]
          :req-un [::dm/id ::dm/args ::dm/tf-pre ::dm/in ::dm/tf ::dm/out ::dm/tf-end]))

(s/conform ::transformer t1)
(s/explain ::transformer t1)

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

  t1 ;=> {:args [], :tf-pre [], :ti-yong.alpha.dyna-map/tform-pre #object [ti-yong$alpha$root$preform], :ti-yong.alpha.dyna-map/outs ...
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
        (update :doc conj "Flattens vectors in args int args.")
        (update :pre conj (partial every? pos?))
        (update :post conj #(-> % :args (nth 1) (* 2) (= (:res %))))
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

  (def add
    (-> transformer
        (assoc :op +)))

  (add 2 2) ;=> 4
  (def add-and-inc
    (-> add
        (update :out conj ::add-and-inc inc)))
  (add-and-inc 2 2) ;=> 5

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
 ;  [:ti-yong.alpha/root :ti-yong.alpha/transformer :dev.user/ancestral :dev.user/shape]

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
 ;  [:ti-yong.alpha/root :ti-yong.alpha/transformer :dev.user/ancestral :dev.user/shape :dev.user/box]

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
 ;  [:ti-yong.alpha/root :ti-yong.alpha/transformer :dev.user/ancestral :dev.user/shape :dev.user/box]

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
 ;  [:ti-yong.alpha/root :ti-yong.alpha/transformer :dev.user/ancestral :dev.user/shape :dev.user/box :dev.user/rect :dev.user/square]

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
 ;  [:ti-yong.alpha/root :ti-yong.alpha/transformer :dev.user/ancestral :dev.user/shape :dev.user/circle]

  (circle {:size 2}) ;=> nil
 ; no matching method for {:size 2} 
 ; in multi function ancestry:
 ;  [:ti-yong.alpha/root :ti-yong.alpha/transformer :dev.user/ancestral :dev.user/shape :dev.user/circle]

  (circle {:radius 17.001}) ;=> 908.0270941792651

  :end
  )


;; Bob works at Acme Widgets as a Staff Engineer in the Weather Widgets
;; department.

;; They've got'im working on a widget that converts celcius to fahrenheit
;; - pretty simple, right? Should have been, but... management... They thought
;; they were better programmers than Bob.

;; Bob warned them, "I need more requirements. It can't just be
;; `(* (/ 5 9) (- c 32))` from spreadsheet you gave me. How will it be used?"

;; "No, it's just that!" they said. "It'll be easy," they said. "Fine," he
;; said.

(defn fahrenheit [c]
  (* (/ 5 9) (- c 32)))
#_(fahrenheit 104) ;=> 40

;; A week after the widget hit prod though, here they come:

;; "Bob, we need it to recognize numbers ending in c, Bob," they said. "It's
;; just thas one more thing," they said. "Oh, and the output needs an f at the
;; end," they also said.

;; By this time, though, the fahrenheit component had been added to twelve
;; different weather components, spread across other components in 37 different
;; namespaces, which has already been showing up in hundreds of pages through
;; out multiple apps running in prod. Changing fahreinheit now is not a trivial
;; thing. It'll take tons of testing across many different teams that depend on
;; how it currently looks and works.

;; So Bob did the logical thing and just wrapped it:

(defn parse-c [c]
  (if (= \c (last (str c)))
    (parse-long (apply str (butlast (str c))))
    (if (string? c)
      (parse-long c)
      c)))
#_(parse-c "104c") ;=> 104

(defn f2 [c]
  (let [c2 (parse-c c)]
    (str (fahrenheit c2) "f")))
#_(f2 "104c") ;=> "40f"

;; #### Implementation diagram so far
;; ```
;; fahrenheit    parse-c    
;;           \  /           
;;            f2            
;; ```

;; There. Now all existing callers can continue to function and look as they
;; are and only new widgets that the higherups need the new behavior in can
;; depend on the newly wrapped funciton. That's normally how we reuse
;; implementation details in functional programming - we wrap it in another
;; function.

;; But, uh oh, the higherups came back with another request...
;; They wanted Bob to peg all inputs below 0 to 0 now. They say it's for
;; simplified widgets view, for views that don't involve temperatures below
;; zero celsius.

;; ""Fine, whatever," Bob thinks. "I'm done trying to reason with these
;; people."

;; Against Bob's better judgement, he went and made the thing that can't
;; calculate temperature correctly. But he now he had a problem.

;; In the time between their previous request and this one, both the original
;; farenheit and the f2 versions of the calculator have ended up in twice as
;; many namespaces and web pages in prod. So, if we need to change how either
;; one of those work, we're going to need to test all of those downstream apps
;; very thoroughly.

;; Can't we just do what we did last time? Can't we just wrap f2 like we did
;; with fahreinheit? Then only have new callers are calling the feature-wrapped
;; versions?

;; "No," Bob realized. We need the parser to run before we can compare the
;; input to 0. We don't need to change the value of f2's input (c) or its
;; return value - we need to alter a value inside of f2 (c2).

;; So, sure, he can defensively make a f3 but, unfortunately, Bob's going to
;; have to reimplement most of c2 in his new f3 function. So that's what he
;; did:

(defn f3 [c]
  (let [c2 (parse-c c)]
    (if (< c2 0)
      (str (fahrenheit 0) "f")
      (str (fahrenheit c2) "f"))))
#_(f3 "-19") ;=> "-17.77777777777778f"
#_(f3 "-30") ;=> "-17.77777777777778f"

;; #### Implementation diagram so far
;; ```
;; fahrenheit-1  parse-c-1   fahrenheit-1  parse-c-1    
;;           \  /                      \  /             
;;            f2                        f3              
;; ```

;; There we go. That looks great! But like all great things, management came
;; back with another request. They say that some customers say that they only
;; want even significand (to the left of the decimal) outputs, otherwise they'd
;; like to have 1 added to the output.

;; Weird flex, but okay, right? Thinks stopped making sense a long time ago, as
;; far as Bob was concerned. Again, since our new f3 has been created, Terry
;; has added 23 widgets that depend on it. And, again, we don't need to risk
;; breaking those downstream consumers of f3, so Bob can just make an f3-even,
;; for those widgets that need the new feature.

;; All is not lost though, right? We can still reuse all of the implementation
;; from f3, right? No, f3 returns strings... We'd have to either turn the
;; strings back into numbers, so that we can add 1 to the even numbers, or we'd
;; have to reimplement f3 entirely. Dang it!

;; "Fine," Bob surmises, "Clojure makes this super easy anyway." And he's right
;; - these  functions are very simple and not hard to understand. It looks a
;; little messy, but it's a mess we can handle. It is far better to defensively
;; grow our code and risk implementation redundancy and code duplication than
;; it is to change existing code and risk breaking the whole world of
;; downstream consumers.

;; So, again, he again reimplements a bespoke solution, very similar to
;; existing solutions, but just slightly different:

(defn f3-even [c]
  (let [c2 (parse-c c)
        res (if (< c2 0)
              (fahrenheit 0)
              (fahrenheit c2))]
    (str (if (even? (int res))
           res
           (inc res))
         "f")))
#_(f4 "50") ;=> 10
#_(f4 "51") ;=> 10.555555555555555
#_(f4 "52") ;=> 12.11111111111111


;; #### Implementation diagram so far
;; ```
;; fahrenheit    parse-c     fahrenheit    parse-c     fahrenheit    parse-c    
;;           \  /                      \  /                      \  /           
;;            f2                        f3                        f3-even       
;; ```

;; Not pretty, but whatever, this is a one off. It's a pure function. It does
;; what it needs to, let's just leave alone and stop talking about it, k?

;; Fortunately, because everything is so stable and Acme Widgets has this ethic
;; of always defensively duplicating code over risking the breakage of
;; downstream callers in important components, Bob is right. He can mostly set
;; it and forget it and it'll keep working 100 years from now.

;; Oh, suprise, suprise... Now the higherups have a new requirement: fahrenheit
;; is going multi tenent. I know, it sounds drastic but we can migrate
;; incrementally.

;; One of our premium customers wants to be able to put capital "C"s, in
;; addition to lowercase "c"s, for inputs. The higherups think it'd be a great
;; idea if, as a test, we introduce multi-tenancy first in our weather widgets
;; and, specifically, in Bob's farenheit widgets. If non-premium customers want
;; that feature, they'll have to pay extra.

;; Bob would prefer not to support duplicative code over time unnecessarily, so
;; he reuses parse-c's implementation in parse-C. The new behavior can wrap
;; the semantics of parse-c cleanly, so no big deal:

(defn parse-C [c]
  (if (= \C (last (str c)))
    (parse-long (apply str (butlast (str c))))
    (parse-c c)))

;; parse-c was implemented way back before f2 was implemented though. Do we
;; have to make a new f2? And do we also have to make a new f3, so that we
;; can have a new f3-even?

;; Bob ponders parameterizing the parser. Again, he sees no point in risking
;; damange to downstream callers of f3 and f3-even, so he implements fresh,
;; bespoke versions of f3 and f3-even with the parser parameterized:
(defn f3-with-parser [c & [parser]]
  (let [c2 ((or parser parse-c) c)] ; <- let's still fall back to parse-c
    (if (< c2 0)
      (str (fahrenheit 0) "f")
      (str (fahrenheit c2) "f"))))
#_(f3-even-with-parser "104C" parse-C) ;=> "40f"

(defn f3-even-with-parser [c & [parser]]
  (let [c2 ((or parser parse-c) c)
        res (if (< c2 0)
              (fahrenheit 0)
              (fahrenheit c2))]
    (str (if (even? (int res))
           res
           (inc res))
         "f")))
#_(f3-even-with-parser "104C" parse-C) ;=> "40f"

;; #### Implementation diagram so far
;; ```
;; fahrenheit    parse-c     fahrenheit    parse-c     fahrenheit    parse-c  
;;           \  /                      \  /                      \  /         
;;            f2                        f3                        f3-even     
;;                                                                            
;;                                                                            
;; fahrenheit----parse-c____               fahrenheit----parse-c              
;;           \  /           \                        \  /                     
;;      f3-with-parser       parse-C          f3-even-with-parser             
;; ```

;; Awesome. A bit of a PITA, having to expand our signature like that, but
;; it's more robust now. Also sucks that we have to maintain the parallel
;; implementations like this. Maybe Bob should refactor this.

;; He'll get to that soon, but the higherups have another feature request
;; that has to get done quick. Remember that "never less than zero""
;; constraint from f3? Now they want to let prememium members have any number
;; less than "n" or any other condition, for that matter.

;; Okay, so what do we do now?

;; Can we wrap f3-with-parser? Again, no, because that conditional's logic
;; (`(< c2 0)`) is closed over, inside f2 and f3. And, again, Sally already
;; made 15 widgets that depend on them, living in prod. Dependencies have
;; already leaked everywhere for f3-with-parser and f3-even-with-parser.

;; We can't keep doing this. Bob needs to parameterize that conditional so we
;; can stop reimplementing it. Then we can start reusing these functions by
;; simply composing them with function wrappers that pass in the conditional
;; as a parameter.

;; And, we still don't want to unnecessarily risk affecting consumers of f2,
;; f3, f3-even, f3-with-parser and f3-even-with-parser, so let's not delete
;; those. Let's just make the new parameterized versions for new callers. One
;; day, when we have the resources, we may migrate all those old consumers to
;; the new functions. For now, we have to move fast without breaking things so
;; let's just have Bob make some new functions:

(defn f3-with-parser-and-conditional [c & [parser conditional-fn]]
  (let [c2 ((or parser parse-c) c)] ; <- let's still fall back to parse-c
    (if conditional-fn
      (if (conditional-fn c2)
        (str (fahrenheit 0) "f")
        (str (fahrenheit c2) "f"))
      (if (< c2 0)
        (str (fahrenheit 0) "f")
        (str (fahrenheit c2) "f")))))

(defn f3-even-with-parser-and-conditional [c & [parser conditional-fn]]
  (let [c2 ((or parser parse-c) c)
        res (if conditional-fn
              (if (conditional-fn c2)
                (fahrenheit 0)
                (fahrenheit c2))
              (if (< c2 0)
                (fahrenheit 0)
                (fahrenheit c2)))]
    (str (if (even? (int res))
           res
           (inc res))
         "f")))
(f3-even-with-parser-and-conditional 104 parse-C #(< % 0)) ;=> "40f"

;; #### Implementation diagram so far
;; ```
;; fahrenheit    parse-c     fahrenheit    parse-c     fahrenheit    parse-c       
;;           \  /                      \  /                      \  /              
;;            f2                        f3                        f3-even          
;;                                                                                 
;;                                                                                 
;; fahrenheit----parse-c________               fahrenheit----parse-c                   
;;           \  /               \                        \  /                          
;;            f3-with-parser     parse-C           f3-even-with-parser    
;;                                                                                 
;;                                                                                 
;; fahrenheit----parse-c                   fahrenheit----parse-c                     
;;           \  /                                    \  /                          
;;  f3-with-parser-and-conditional            f3-even-with-parser-and-conditional             
;; ```

;; Okay, hmm. That's a bit much. There's gotta be a better way. Sure, we could
;; parameterize everything about this function, but where do we stop? This is
;; getting out of hand.

;; Just for shits-and-giggles, Bob decides to take this parameterization a step
;; further and parameterize even the even? branch of the second function. This
;; allows him to reduce both functions back down into a single function that
;; optionally defaults to the even? testing branch unless it's overridden by
;; the caller. So Bob makes an f4 that parameterizes what to do with the
;; conditional's result:

(defn f4 [c & [parser conditional-fn dunzo]]
  (let [c2 ((or parser parse-c) c)
        res (if conditional-fn
              (if (conditional-fn c2)
                (fahrenheit 0)
                (fahrenheit c2))
              (if (< c2 0)
                (fahrenheit 0)
                (fahrenheit c2)))]
    (str (if dunzo
           (dunzo res)
           (if (even? (int res))
             res
             (inc res)))
         "f")))
#_(f4 104 parse-C #(< 40 %) #(if (even? (int %)) % (inc %))) ;=> "-16.77777777777778f"

;; #### Implementation diagram so far
;; ```
;; fahrenheit    parse-c     fahrenheit    parse-c     fahrenheit    parse-c       
;;           \  /                      \  /                      \  /              
;;            f2                        f3                        f3-even          
;;                                                                                 
;;                                                                                 
;; fahrenheit----parse-c________               fahrenheit---parse-c                   
;;           \  /               \                        \  /                          
;;            f3-with-parser     parse-C           f3-even-with-parser    
;;                                                                                 
;;                                                                                 
;; fahrenheit----parse-c                   fahrenheit----parse-c                 
;;           \  /                                    \  /                          
;;  f3-with-parser-and-conditional     f3-even-with-parser-and-conditional
;;                                                                                 
;;                                                                                 
;;                   fahrenheit----parse-c   
;;                             \  /          
;;                              f4   
;; ```

;; Now we can do (f4 104 parse-C #(< 40 %) #(if (odd? (int %)) % (inc %)))

(f4 104 parse-C #(< % 0) #(if (odd? (int %)) % (inc %))) ;=> "41f"

;; Okay, but this is getting pretty ugly now. The function is moreso
;; parameterization than it is business logic.

;; We're parameterizing the handling of the inputs. We're parameterizing what
;; to do with the inputs after they're handled. We're parameterizing almost
;; everything but the function that's actually operating on the data.

;; Why not parameterize that too? Why not parameterize everything about a
;; function?

;; Below is an "abstract function" which is structured into these internal
;; function stages that we've been discussing:

(def data {:args [] :in [] :tf [] :op nil :out [] :tf-end [] :res nil})

(defn apply-env-fns [env k]
  (if-not (seq (get env k))
    env
    (reduce (fn [e f] (f e)) env (get env k))))

(defn abstract-function [d & args]
  (let [env1 (update d :args into (reduce (fn [a f] [(apply f a)]) args (:in d)))
        env2 (apply-env-fns env1 :tf)
        res (if-not (:op env2) nil (apply (:op env2) (:args env2)))
        env3 (assoc d :res (reduce (fn [r f] (f r)) res (:out env2)))
        env4 (apply-env-fns env3 :tf-end)]
    (:res env4)))
#_(abstract-function (-> data (assoc :op +)) 1 2 3) ;=> 6

;; Okay, so what we have here is a data model of a function. With it, we're
;; able to do everything we were able to do above, but with just data.

;; Okay, let's try it first with fahrenheit as the operator function:
(->> 104
     (abstract-function (-> data (assoc :op fahrenheit)))) ;=> 40

;; Now let's add parsing:
(->> "-103c"
     (abstract-function
      (-> data
          (update :in conj parse-c)
          (assoc :op fahrenheit)
          (update :tf-end conj #(assoc % :res (str (:res %) "F")))))) ;=> "-75f"

;; Now, let's define f2 again but, this time, lets store the function as data:
(def f-with-parse ;; was f2
  (-> data
      (update :in conj parse-c)
      (assoc :op fahrenheit)
      (update :tf-end conj #(assoc % :res (str (:res %) "F")))))

;; #### Implementation diagram so far
;; ```
;; fahrenheit    parse-c  
;;           \  /         
;;        f-with-parse    
;; ```

;; Now we can run the data function:
(abstract-function f-with-parse "-103c") ;=> "-75F"

;; Great, now let's add the below zero gaurd from f3:
(def f-with-parse-and-cond
  (-> f-with-parse
      (update :tf conj
              #(assoc % :args
                      (if (< (first (:args %)) 0)
                        [0]
                        (:args %))))))

;; #### Implementation diagram so far
;; ```
;; fahrenheit-1  parse-c-1                            
;;           \  /                                     
;;        f-with-parse-1 ----- f-with-parse-and-cond  
;; ```

;; Remember that in the original f3 we had to reimplement everything about f2.
;; Here, were only adding the update to the args. Same result:

(abstract-function f-with-parse-and-cond "-103c") ;=> "-17.77777777777778F"

;; And for increasing the evens by one? Instead of becoming more complex with
;; wrappers, things become _less_ complex with transformers:

(def f4*
  (-> f-with-parse-and-cond
      (update :out conj #(if (even? (int %)) % (inc %)))))

;; #### Implementation diagram so far
;; ```
;; fahrenheit-1  parse-c-1                                  
;;           \  /                                           
;;        f-with-parse-1 ----- f-with-parse-and-cond -- f4*  
;; ```

;; Well, that was easy. We didn't have to go reimplementing f2 and f3 all over
;; again!

;; Works the same:

(abstract-function f4* "-103c") ;=> "-16.77777777777778F"

;; Notice how we never had to go back and rewrite old functions, parameterizing
;; behaviors we didn't anticipate at design stage.

;; The f-with-parse-and-cond-and-out function fully reuses the
;; f-with-parse-and-cond function. The f-with-parse-and-cond function fully
;; reuses the f-with-parse function.

;; Oh, but we need a version of f4* that doesn't convert the result to a string
;; with "f" at the end!

;; How hard would it be for us to transform f4* into on that acts as if
;; f-with-parse never coupled the formating behavior in its implementation?

;; Pretty easy!

(def f4-raw
  (-> f4* (update :tf-end empty)))

(abstract-function f4-raw "-103c") ;=> -16.77777777777778

;; And Bob didn't necessarily have to make `f4-raw`. Nobody on team Weather
;; Widgets needs to be able to make changes, or have write access to the 
;; weather-widgets repo, in order to make that above f4-raw. Any downstream
;; consumer of f4* can transform its implementation in the way above. So doesn't
;; necessarily have to support all these features, just a good core set of
;; widgets that can be easily transformed into other widgets.

;; That's basically what transformers are, but just with a few more bells and
;; whistles. But, when you invoke the transformer map, it's as if
;; `abstract-function` is preceding the map in every invocation. Transformers
;; provide a way to more easily compose functions and declaratively recompose
;; them after they've already been defined. They parameterize every part of the
;; insides of a function, turning its semantics inside out, so that those 
;; implementation parts are not coupled together by the function closure.

;; Transformers allow you to share implementation without forcing
;; specific abstractions. It's an abstract function, so your code doesn't
;; have to be.

;; People are complaining that "reusable" code is problematic.

;; “duplication is far cheaper than the wrong abstraction” they say
;; “prefer duplication over the wrong abstraction” they say

;; The main method of implementation reuse in clojure is by convention of making
;; small, single purpose functions. We make small lego blocks out of functions
;; that can be easily composed together to create any large scale behavior
;; we want.

;; This has one downside though - while it's easy to swap out one function
;; anywhere in an heirarchy of functions composed together with another
;; function with the new feature, we still have to recompose the entire
;; heirarchy downstream of the new function. Easy to do - you can essentially
;; copy and paste the same composition logic downstream of the new function.

;; In other words, for function C in A->B->C->D->E, if we need a new E that
;; needs a new feature from C, then when we go to make a new C we have to also
;; go make a new D that wraps the new C, so that the new E can wrap the new
;; D.

;; So, we're not really getting to reuse D in this new C/new D relationship.
;; Technically, there's no reason we should have to rewrite all the wrappers
;; between a new function and an old one that is getting a new feature. We
;; can't do that though, because by "closing over" our implementations we are
;; preventing the sharing of their compositions. 
