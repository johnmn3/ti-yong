(ns pedestal.bench-pedestal
  "Stock Pedestal benchmark server with plaintext, JSON, and routing endpoints."
  (:require
   [io.pedestal.http :as http]
   [io.pedestal.http.route :as route]
   [clojure.data.json :as json]))

;; --- Handlers ---

(defn plaintext-handler [_request]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body "Hello, World!"})

(defn json-handler [_request]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/write-str {"message" "Hello, World!"})})

(defn echo-handler [request]
  (let [id (get-in request [:path-params :id])]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/write-str {"id" id "method" (name (:request-method request))})}))

(defn list-handler [_request]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/write-str (mapv (fn [i] {"id" i "name" (str "item-" i)}) (range 10)))})

;; --- Routes ---

(def routes
  (route/expand-routes
   #{["/plaintext" :get plaintext-handler :route-name ::plaintext]
     ["/json"      :get json-handler      :route-name ::json]
     ["/items"     :get list-handler      :route-name ::items]
     ["/items/:id" :get echo-handler      :route-name ::item]}))

;; --- Server ---

(defn create-server []
  (-> {::http/routes routes
       ::http/type :jetty
       ::http/port 8080
       ::http/join? false}
      http/default-interceptors
      http/create-server))

(defn -main [& _args]
  (println "Starting Pedestal benchmark server on port 8080...")
  (let [server (create-server)]
    (http/start server)
    (println "Pedestal server running. Press Ctrl+C to stop.")
    ;; Keep alive
    @(promise)))
