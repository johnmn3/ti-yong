(ns hearth.alpha.error
  (:refer-clojure :exclude [error-handler])
  (:require
   [ti-yong.alpha.transformer :as t]))

;; Error handler mixin: wraps :op/:env-op in try/catch during the :tf phase,
;; converting unhandled exceptions into error response maps.

(defn- default-error-response
  "Convert an exception to a default 500 error response map."
  [^Throwable e _env]
  {:status 500
   :headers {}
   :body (str "Internal Server Error: " (.getMessage e))})

(defn error-handler-with
  "Create an error handler mixin with a custom error-to-response function.
   The fn receives (exception, env) and should return a Ring response map."
  [error-fn]
  (-> t/transformer
      (update :id conj ::error-handler)
      (update :tf conj
              ::error-handler
              (fn [env]
                ;; Wrap existing :op or :env-op in try/catch
                (let [original-op (:op env)
                      original-env-op (:env-op env)]
                  (cond
                    original-env-op
                    (assoc env :env-op
                           (fn [e]
                             (try
                               (original-env-op e)
                               (catch Throwable t
                                 (error-fn t e)))))

                    original-op
                    (assoc env :op
                           (fn [& args]
                             (try
                               (apply original-op args)
                               (catch Throwable t
                                 (error-fn t env)))))

                    :else env))))))

(def error-handler
  "Default error handler mixin: catches exceptions and returns 500 response."
  (error-handler-with default-error-response))
