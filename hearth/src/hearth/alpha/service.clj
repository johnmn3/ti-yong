(ns hearth.alpha.service
  (:require
   [ti-yong.alpha.transformer :as t]
   [ti-yong.alpha.root :as r]
   [ti-yong.alpha.util :as u]
   [hearth.alpha.route :as route]))

;; Service transformer: the root pipeline that combines
;; a router with global middleware.
;;
;; The service injects res-aware pipeline execution so that
;; middleware can short-circuit by setting :res on the env
;; (like Pedestal's :response on context). When :res is set:
;;   - remaining :tf steps are skipped
;;   - the handler (env-op) is skipped
;;   - :tf-end (leave) steps still run

(defn- res-aware-tform
  "Replacement for ti-yong.alpha.root/tform that checks :res between
   :tf pipeline steps. If any step sets :res, remaining steps are skipped
   via `reduced`."
  [env]
  (if-not (seq (:tf env))
    env
    (let [pipeline (u/uniq-by-pairwise-first (:tf env))]
      (if-not (seq pipeline)
        env
        (reduce (fn [current-env tf-fn]
                  (if (:res current-env)
                    (reduced current-env)
                    ((or tf-fn identity) current-env)))
                env
                pipeline)))))

(defn- res-aware-env-op
  "Wraps an env-op so it returns the existing :res when already set,
   skipping the handler."
  [original-env-op]
  (fn [env]
    (if (:res env)
      (:res env)
      (original-env-op env))))

(defn service
  "Create a service transformer from a config map.
   Config keys:
     :routes - vector of route definition vectors
     :with   - vector of global middleware transformers (optional)

   Middleware can short-circuit the pipeline by setting :res on the env.
   When :res is set, remaining :tf steps and the handler are skipped,
   but :tf-end (leave) steps still run."
  [{:keys [routes with]}]
  (let [router (route/router routes)]
    (cond-> (-> t/transformer
                (update :id conj ::service)
                (assoc :env-op (res-aware-env-op (:env-op router))
                       :routes (:routes router)
                       ::r/tform res-aware-tform))
      (seq with)
      (update :with into with))))

(defn response-for
  "Test helper: invoke the service with a given method, path, and optional extras.
   Returns the response (whatever the service's pipeline produces)."
  ([svc method path]
   (response-for svc method path {}))
  ([svc method path extras]
   (-> svc
       (merge {:request-method method
               :uri path
               :headers {}
               :scheme :http
               :server-name "localhost"
               :server-port 80}
              extras)
       (apply []))))
