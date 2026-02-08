(ns hearth.alpha.request
  (:require
   [ti-yong.alpha.transformer :as t]))

;; Request transformer: wraps a Ring request map as a callable transformer.
;; All Ring request keys (:uri, :request-method, :headers, :body, etc.)
;; are preserved as transformer data.

(def ^:private ring-request-keys
  "Standard Ring request keys to extract for to-ring."
  [:server-port :server-name :remote-addr :uri :query-string
   :scheme :request-method :headers :body :content-type
   :content-length :character-encoding :ssl-client-cert
   :protocol])

(defn request
  "Wrap a Ring request map as a request transformer."
  [ring-req]
  (-> t/transformer
      (update :id conj ::request)
      (merge ring-req)))

(defn path
  "Return the URI path of a request."
  [req]
  (:uri req))

(defn method
  "Return the request method keyword."
  [req]
  (:request-method req))

(defn header
  "Return the value of a specific header."
  [req header-name]
  (get (:headers req) header-name))

(defn mock-request
  "Create a minimal mock request transformer for testing.
   Optionally merge extra data (e.g. {:body ...})."
  ([method uri]
   (mock-request method uri {}))
  ([method uri extras]
   (request (merge {:server-port 80
                    :server-name "localhost"
                    :remote-addr "127.0.0.1"
                    :uri uri
                    :scheme :http
                    :request-method method
                    :headers {}}
                   extras))))

(defn to-ring
  "Extract a plain Ring request map from a request transformer."
  [req]
  (select-keys req ring-request-keys))
