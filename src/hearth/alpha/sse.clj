(ns hearth.alpha.sse
  "Server-Sent Events support for hearth.
   Provides event-stream handler creation and SSE event formatting.
   Uses core.async channels for event delivery."
  (:require
   [clojure.core.async :as a]
   [hearth.alpha.middleware :as mw]))

;; Re-export format-sse-event from middleware
(def format-event mw/format-sse-event)

(defn event-stream
  "Create an SSE handler function.
   `stream-fn` is called with (event-ch, request-env) when a client connects.
   Put events on event-ch with core.async/>! or >!!.
   Close event-ch to end the stream.

   Options:
     :heartbeat-ms - heartbeat interval in ms (default: 10000, nil to disable)
     :on-close     - (fn []) called when the stream ends
     :headers      - extra headers to merge into the SSE response
     :buf-or-n     - buffer size for event channel (default: 32)"
  [stream-fn & [{:keys [heartbeat-ms on-close headers buf-or-n]
                  :or {heartbeat-ms 10000
                       buf-or-n 32}}]]
  (fn [request-env]
    (let [event-ch (a/chan buf-or-n)
          body-fn (fn [^java.io.OutputStream os]
                    (try
                      (let [writer (java.io.OutputStreamWriter. os "UTF-8")
                            timeout-ch (when heartbeat-ms
                                         (a/timeout heartbeat-ms))]
                        (loop [timeout-ch timeout-ch]
                          (let [ports (cond-> [event-ch]
                                       timeout-ch (conj timeout-ch))
                                [v port] (a/alts!! ports)]
                            (cond
                              ;; Event channel closed (v=nil, port=event-ch)
                              (and (nil? v) (= port event-ch))
                              nil ;; exit loop

                              ;; Heartbeat timeout
                              (and timeout-ch (= port timeout-ch))
                              (do (.write writer ":\n\n")
                                  (.flush writer)
                                  (recur (when heartbeat-ms
                                           (a/timeout heartbeat-ms))))

                              ;; Normal event
                              :else
                              (do (.write writer (format-event v))
                                  (.flush writer)
                                  (recur (if (and timeout-ch (= port event-ch))
                                           (when heartbeat-ms
                                             (a/timeout heartbeat-ms))
                                           timeout-ch)))))))
                      (catch java.io.IOException _
                        ;; Client disconnected
                        (a/close! event-ch))
                      (finally
                        (when on-close (on-close)))))]
      ;; Call the user's stream-ready function with the channel
      (stream-fn event-ch request-env)
      ;; Return SSE response with streaming body
      {:status 200
       :headers (merge {"Content-Type" "text/event-stream"
                        "Cache-Control" "no-cache, no-store"
                        "Connection" "keep-alive"}
                       headers)
       :body body-fn})))
