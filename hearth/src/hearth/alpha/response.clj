(ns hearth.alpha.response
  (:require
   [ti-yong.alpha.transformer :as t]))

;; Response transformer: wraps a Ring response map as a callable transformer.
;; The :op returns the body by default, and :out/:tf-end can be used
;; for serialization and final header injection.

(defn response
  "Create a response transformer with the given status, body, and optional headers."
  ([status body]
   (response status body {}))
  ([status body headers]
   (-> t/transformer
       (update :id conj ::response)
       (assoc :status status
              :body body
              :headers headers
              :op (fn [] body)))))

(defn ok
  "200 OK response."
  [body]
  (response 200 body))

(defn created
  "201 Created response with Location header."
  [location body]
  (response 201 body {"Location" location}))

(defn accepted
  "202 Accepted response."
  [body]
  (response 202 body))

(defn no-content
  "204 No Content response."
  []
  (response 204 nil))

(defn redirect
  "302 Found redirect response."
  [location]
  (response 302 nil {"Location" location}))

(defn bad-request
  "400 Bad Request response."
  [body]
  (response 400 body))

(defn unauthorized
  "401 Unauthorized response."
  [body]
  (response 401 body))

(defn forbidden
  "403 Forbidden response."
  [body]
  (response 403 body))

(defn not-found
  "404 Not Found response."
  [body]
  (response 404 body))

(defn internal-error
  "500 Internal Server Error response."
  [body]
  (response 500 body))

(defn content-type
  "Set the Content-Type header on a response transformer."
  [resp ct]
  (assoc-in resp [:headers "Content-Type"] ct))

(defn to-ring
  "Extract a plain Ring response map from a response transformer."
  [resp]
  {:status  (:status resp)
   :headers (:headers resp)
   :body    (:body resp)})
