(ns ti-yong.alpha.async
  "Deferred value protocol for async pipeline support (CLJS).
   Uses js/Promise as the native deferred type.")

(defprotocol IDeferred
  (deferred? [x] "Returns true if x is a deferred/async value.")
  (then [x f] "Chain f onto x: when x resolves, call (f resolved-value)."))

(extend-protocol IDeferred
  js/Promise
  (deferred? [_] true)
  (then [x f] (.then x f))

  default
  (deferred? [_] false)
  (then [_ _] (throw (js/Error. "Not a deferred value"))))

(defn resolved
  "Create a Promise that is already resolved with v."
  [v]
  (js/Promise.resolve v))

(defn ->deferred
  "Convert a value to a Promise if it isn't one already."
  [v]
  (if (deferred? v) v (resolved v)))
