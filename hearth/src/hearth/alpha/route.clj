(ns hearth.alpha.route
  (:require
   [clojure.string :as str]
   [ti-yong.alpha.transformer :as t]
   [ti-yong.alpha.root :as r]
   [hearth.alpha.pipeline :as pipeline]))

;; Route expansion and router transformer.
;; Routes are defined as data vectors, expanded into route maps,
;; and matched against incoming requests.
;;
;; Handlers can be either:
;;   - plain functions: (fn [env] -> response-map)
;;   - transformers: (-> t/transformer ... (update :tf conj ...))
;;
;; Transformer handlers get the request env merged in and are invoked
;; directly. Their :tf steps update :res on the env. This allows
;; adding middleware to handlers after definition.

(defn- extract-path-params
  "Extract path parameter names from a path pattern like '/items/:id'."
  [path]
  (->> (str/split path #"/")
       (filter #(str/starts-with? % ":"))
       (mapv #(keyword (subs % 1)))))

(defn- path-to-regex
  "Convert a path pattern like '/items/:id' to a regex pattern and param names."
  [path]
  (if (= "/" path)
    {:regex #"^/$" :param-names []}
    (let [segments (str/split path #"/")
          param-names (atom [])
          regex-parts (mapv (fn [seg]
                              (if (str/starts-with? seg ":")
                                (do (swap! param-names conj (subs seg 1))
                                    "([^/]+)")
                                (java.util.regex.Pattern/quote seg)))
                            segments)]
      {:regex (re-pattern (str "^" (str/join "/" regex-parts) "$"))
       :param-names @param-names})))

(defn expand-routes
  "Expand route definition vectors into route maps.
   Each route vector: [path method handler & {:keys [route-name with]}]"
  [route-defs]
  (mapv (fn [route-def]
          (let [path (nth route-def 0)
                method (nth route-def 1)
                handler (nth route-def 2)
                opts (->> (drop 3 route-def)
                          (partition 2)
                          (map (fn [[k v]] [k v]))
                          (into {}))
                path-params (extract-path-params path)
                {:keys [regex param-names]} (path-to-regex path)]
            (cond-> {:path path
                     :method method
                     :handler handler
                     :path-regex regex
                     :path-param-names param-names}
              (seq path-params)
              (assoc :path-params path-params)
              (:route-name opts)
              (assoc :route-name (:route-name opts))
              (:with opts)
              (assoc :with (:with opts)))))
        route-defs))

(defn- url-decode
  "URL-decode a string value."
  [s]
  (java.net.URLDecoder/decode s "UTF-8"))

(defn match-route
  "Find the first route matching the given method and path.
   Returns the route map with :path-params-values populated (URL-decoded) if matched."
  [routes method path]
  (first
   (for [route routes
         :when (= method (:method route))
         :let [matcher (re-matcher (:path-regex route) path)]
         :when (re-find matcher)]
     (let [param-names (:path-param-names route)
           param-values (if (seq param-names)
                          (zipmap param-names
                                  (map #(url-decode
                                         (nth (re-find (re-matcher (:path-regex route) path))
                                              (inc %)))
                                       (range (count param-names))))
                          {})]
       (cond-> route
         (seq param-values)
         (assoc :path-params-values param-values))))))

(defn- transformer-handler?
  "Returns true if the handler is a transformer (map with :id), not a plain fn."
  [handler]
  (and (map? handler) (contains? handler :id)))

(defn router
  "Create a router transformer from route definitions.
   The router uses :env-op to dispatch to the matched route's handler.

   Handlers can be plain functions (fn [env] -> response) or transformers.
   Transformer handlers get the request env merged in and are invoked directly,
   allowing middleware to be composed onto handlers after definition."
  [route-defs]
  (let [routes (expand-routes route-defs)]
    (-> t/transformer
        (update :id conj ::router)
        (assoc :routes routes)
        (assoc :env-op
               (fn [env]
                 (let [method (:request-method env)
                       path (:uri env)
                       match (match-route (:routes env) method path)]
                   (if match
                     (let [handler (:handler match)
                           route-with (:with match)
                           handler-env (cond-> env
                                         (seq (:path-params-values match))
                                         (assoc :path-params-values (:path-params-values match))
                                         true
                                         (assoc :route-name (:route-name match)))
                           req-keys (select-keys handler-env
                                                 [:uri :request-method :query-string
                                                  :headers :body :scheme :server-name
                                                  :server-port :remote-addr :path-params-values
                                                  :route-name])]
                       (cond
                         ;; Transformer handler: merge request data in, compose :with, invoke
                         (transformer-handler? handler)
                         (let [handler-tf (-> handler
                                             (merge req-keys)
                                             ;; Propagate any env keys set by global middleware
                                             (merge (select-keys handler-env
                                                                 [:current-user :auth-method :api-key-scope
                                                                  :session :query-params :body-params
                                                                  :form-params :csrf-token :pagination
                                                                  :request-id :multipart-params]))
                                             (assoc ::r/tform pipeline/res-aware-tform
                                                    :env-op pipeline/handler-env-op)
                                             ;; Prepend default-response so :res is initialized
                                             ;; before handler's :tf step runs
                                             (update :with #(vec (cons pipeline/default-response %)))
                                             (cond->
                                               (seq route-with)
                                               (update :with into route-with)))]
                           (handler-tf))

                         ;; Plain fn handler with :with middleware
                         (seq route-with)
                         (let [mw-tf (-> t/transformer
                                         (merge req-keys)
                                         (update :with into route-with)
                                         (assoc :env-op (pipeline/res-aware-env-op handler)
                                                ::r/tform pipeline/res-aware-tform))]
                           (mw-tf))

                         ;; Plain fn handler, no middleware
                         :else
                         (handler handler-env)))
                     {:status 404 :headers {} :body "Not Found"})))))))
