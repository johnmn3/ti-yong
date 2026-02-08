(ns hearth.alpha.sse
  "Server-Sent Events support for hearth.
   Provides event-stream handler creation and SSE event formatting.
   Uses JDK concurrency primitives (no core.async dependency)."
  (:require [hearth.alpha.middleware :as mw])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

;; Re-export format-sse-event from middleware
(def format-event mw/format-sse-event)

(defn event-channel
  "Create an event channel backed by a LinkedBlockingQueue.
   Returns a map with:
     :put!  - (fn [event]) puts an event on the channel
     :close! - (fn []) closes the channel
     :queue  - the underlying queue (for internal use)"
  ([] (event-channel 256))
  ([capacity]
   (let [q (LinkedBlockingQueue. (int capacity))
         closed? (atom false)]
     {:put! (fn [event]
              (when-not @closed?
                (.offer q event)))
      :close! (fn []
                (reset! closed? true)
                (.offer q ::closed))
      :closed? closed?
      :queue q})))

(defn event-stream
  "Create an SSE handler function.
   `stream-fn` is called with (event-channel, request-env) when a client connects.
   The stream-fn should use (:put! ch) to send events and (:close! ch) to end.

   Options:
     :heartbeat-ms - heartbeat interval in ms (default: 10000, nil to disable)
     :on-close     - (fn []) called when the stream ends
     :headers      - extra headers to merge into the SSE response"
  [stream-fn & [{:keys [heartbeat-ms on-close headers]
                  :or {heartbeat-ms 10000}}]]
  (fn [request-env]
    (let [ch (event-channel)
          q (:queue ch)
          body-fn (fn [^java.io.OutputStream os]
                    (try
                      (let [writer (java.io.OutputStreamWriter. os "UTF-8")]
                        (loop []
                          (let [event (if heartbeat-ms
                                        (.poll q heartbeat-ms TimeUnit/MILLISECONDS)
                                        (.take q))]
                            (cond
                              ;; Channel closed
                              (= ::closed event)
                              nil ;; exit loop

                              ;; Heartbeat (timeout, nil from poll)
                              (nil? event)
                              (do (.write writer ":\n\n")
                                  (.flush writer)
                                  (recur))

                              ;; Normal event
                              :else
                              (do (.write writer (format-event event))
                                  (.flush writer)
                                  (recur))))))
                      (catch java.io.IOException _
                        ;; Client disconnected
                        nil)
                      (finally
                        (when on-close (on-close)))))]
      ;; Call the user's stream-ready function
      (stream-fn ch request-env)
      ;; Return SSE response
      {:status 200
       :headers (merge {"Content-Type" "text/event-stream"
                        "Cache-Control" "no-cache, no-store"
                        "Connection" "keep-alive"}
                       headers)
       :body body-fn})))
