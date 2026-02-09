(ns hearth.alpha.sse
  "Server-Sent Events support for hearth.
   Provides event-stream handler creation and SSE event formatting.
   Uses core.async channels for event delivery.

   NOTE: The body function blocks a thread for the lifetime of each SSE connection
   (using alts!! for core.async channel reads). This means each connected SSE client
   consumes one platform thread. With Jetty's default thread pool (~200), this limits
   concurrent SSE clients. For higher concurrency, use `virtual-event-stream` which
   runs the blocking loop on a virtual thread (Java 21+), freeing the request thread."
  (:require
   [clojure.core.async :as a]
   [hearth.alpha.middleware :as mw]))

;; Re-export format-sse-event from middleware
(def format-event mw/format-sse-event)

(defn- sse-loop
  "Blocking SSE write loop. Reads from event-ch, writes SSE-formatted data to writer.
   Sends heartbeat comments on timeout. Exits when event-ch closes or IOException."
  [^java.io.OutputStreamWriter writer event-ch heartbeat-ms on-close]
  (try
    (let [timeout-ch (when heartbeat-ms (a/timeout heartbeat-ms))]
      (loop [timeout-ch timeout-ch]
        (let [ports (cond-> [event-ch]
                      timeout-ch (conj timeout-ch))
              [v port] (a/alts!! ports)]
          (cond
            ;; Event channel closed
            (and (nil? v) (= port event-ch))
            nil

            ;; Heartbeat timeout
            (and timeout-ch (= port timeout-ch))
            (do (.write writer ":\n\n")
                (.flush writer)
                (recur (when heartbeat-ms (a/timeout heartbeat-ms))))

            ;; Normal event
            :else
            (do (.write writer ^String (format-event v))
                (.flush writer)
                (recur (if (and timeout-ch (= port event-ch))
                         (when heartbeat-ms (a/timeout heartbeat-ms))
                         timeout-ch)))))))
    (catch java.io.IOException _
      (a/close! event-ch))
    (finally
      (when on-close (on-close)))))

(defn event-stream
  "Create an SSE handler function.
   `stream-fn` is called with (event-ch, request-env) when a client connects.
   Put events on event-ch with core.async/>! or >!!.
   Close event-ch to end the stream.

   NOTE: The body function blocks the calling thread for the connection lifetime.
   Each SSE client consumes one platform thread. For high-concurrency SSE, use
   `virtual-event-stream` instead (requires Java 21+).

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
                    (let [writer (java.io.OutputStreamWriter. os "UTF-8")]
                      (sse-loop writer event-ch heartbeat-ms on-close)))]
      (stream-fn event-ch request-env)
      {:status 200
       :headers (merge {"Content-Type" "text/event-stream"
                        "Cache-Control" "no-cache, no-store"
                        "Connection" "keep-alive"}
                       headers)
       :body body-fn})))

(defn virtual-event-stream
  "Create an SSE handler that runs the blocking write loop on a virtual thread
   (Java 21+), freeing the request thread for other work. API is identical
   to `event-stream`.

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
                    (let [writer (java.io.OutputStreamWriter. os "UTF-8")
                          latch (java.util.concurrent.CountDownLatch. 1)]
                      ;; Run the blocking loop on a virtual thread
                      (.start (Thread/ofVirtual)
                              (reify Runnable
                                (run [_]
                                  (try
                                    (sse-loop writer event-ch heartbeat-ms on-close)
                                    (finally
                                      (.countDown latch))))))
                      ;; Block until the virtual thread completes
                      ;; (Ring expects body-fn to block until done)
                      (.await latch)))]
      (stream-fn event-ch request-env)
      {:status 200
       :headers (merge {"Content-Type" "text/event-stream"
                        "Cache-Control" "no-cache, no-store"
                        "Connection" "keep-alive"}
                       headers)
       :body body-fn})))
