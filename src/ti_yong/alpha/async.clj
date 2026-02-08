(ns ti-yong.alpha.async
  "Deferred value protocol for async pipeline support.
   Provides zero-overhead detection on the sync path via protocol dispatch."
  (:import
   [java.util.concurrent CompletableFuture CompletionStage]
   [java.util.function Function]))

(defprotocol IDeferred
  (deferred? [x] "Returns true if x is a deferred/async value.")
  (then [x f] "Chain f onto x: when x resolves, call (f resolved-value)."))

(extend-protocol IDeferred
  CompletionStage
  (deferred? [_] true)
  (then [x f]
    (.thenApply x (reify Function
                    (apply [_ v] (f v)))))

  Object
  (deferred? [_] false)
  (then [_ _] (throw (ex-info "Not a deferred value" {})))

  nil
  (deferred? [_] false)
  (then [_ _] (throw (ex-info "Not a deferred value" {}))))

(defn resolved
  "Create a CompletableFuture that is already completed with v."
  [v]
  (CompletableFuture/completedFuture v))

(defn ->deferred
  "Convert a value to a CompletableFuture if it isn't one already."
  [v]
  (if (deferred? v) v (resolved v)))
