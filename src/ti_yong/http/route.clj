(ns ti-yong.http.route
  (:require
   [clojure.string :as str]
   [ti-yong.alpha.transformer :as t]))

;; Route expansion and router transformer.
;; Routes are defined as data vectors, expanded into route maps,
;; and matched against incoming requests.

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

(defn match-route
  "Find the first route matching the given method and path.
   Returns the route map with :path-params-values populated if matched."
  [routes method path]
  (first
   (for [route routes
         :when (= method (:method route))
         :let [matcher (re-matcher (:path-regex route) path)]
         :when (re-find matcher)]
     (let [param-names (:path-param-names route)
           param-values (if (seq param-names)
                          (zipmap param-names
                                  (map #(nth (re-find (re-matcher (:path-regex route) path))
                                             (inc %))
                                       (range (count param-names))))
                          {})]
       (cond-> route
         (seq param-values)
         (assoc :path-params-values param-values))))))

(defn router
  "Create a router transformer from route definitions.
   The router uses :env-op to dispatch to the matched route's handler."
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
                     (let [route-with (:with match)
                           ;; Build a handler transformer with route middleware
                           handler-env (cond-> env
                                         (seq (:path-params-values match))
                                         (assoc :path-params-values (:path-params-values match))
                                         true
                                         (assoc :route-name (:route-name match)))
                           ;; If the route has :with middleware, compose them
                           handler-env (if (seq route-with)
                                         (let [;; Build a transformer with middleware and request data
                                               req-keys (select-keys handler-env
                                                          [:uri :request-method :query-string
                                                           :headers :body :scheme :server-name
                                                           :server-port :remote-addr :path-params-values
                                                           :route-name])
                                               mw-tf (-> t/transformer
                                                         (merge req-keys)
                                                         (update :with into route-with)
                                                         (assoc :env-op (:handler match)))]
                                           ;; Invoke the middleware-wrapped handler
                                           (mw-tf))
                                         ;; Call handler directly with env
                                         ((:handler match) handler-env))]
                       handler-env)
                     {:status 404 :headers {} :body "Not Found"})))))))
