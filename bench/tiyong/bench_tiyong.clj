(ns tiyong.bench-tiyong
  "ti-yong-http benchmark server with plaintext, JSON, and routing endpoints."
  (:require
   [hearth.alpha :as http]
   [hearth.alpha.middleware :as mw]))

;; --- Handlers ---

(defn plaintext-handler [_request]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body "Hello, World!"})

(defn json-handler [_request]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (mw/serialize-json-string {"message" "Hello, World!"})})

(defn echo-handler [request]
  (let [id (get-in request [:path-params-values "id"])]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (mw/serialize-json-string {"id" id "method" (name (:request-method request))})}))

(defn list-handler [_request]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (mw/serialize-json-string (mapv (fn [i] {"id" i "name" (str "item-" i)}) (range 10)))})

;; --- Routes ---

(def routes
  [["/plaintext" :get plaintext-handler]
   ["/json"      :get json-handler]
   ["/items"     :get list-handler]
   ["/items/:id" :get echo-handler]])

;; --- Server ---

(defn create-server []
  (http/create-server
   {::http/routes routes
    ::http/port 8080
    ::http/join? false}))

(defn -main [& _args]
  (println "Starting ti-yong-http benchmark server on port 8080...")
  (let [server (create-server)]
    (http/start server)
    (println "ti-yong-http server running. Press Ctrl+C to stop.")
    @(promise)))
