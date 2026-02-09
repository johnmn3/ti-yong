(ns hearth.alpha.websocket
  "WebSocket support for hearth, built on Ring 1.13+ WebSocket protocols.
   Provides ws-handler for creating WebSocket handlers and ws-upgrade
   middleware for routing WebSocket upgrade requests."
  (:require
   [clojure.core.async :as a]
   [ring.websocket :as ws]
   [ring.websocket.protocols :as wsp]
   [ti-yong.alpha.transformer :as t]))

(defn listener
  "Create a Ring WebSocket listener from callback functions.
   Callbacks:
     :on-open    - (fn [socket])
     :on-message - (fn [socket message])
     :on-close   - (fn [socket code reason])
     :on-error   - (fn [socket error])
     :on-pong    - (fn [socket data])"
  [{:keys [on-open on-message on-close on-error on-pong]}]
  (reify
    wsp/Listener
    (on-open [_ socket]
      (when on-open (on-open socket)))
    (on-message [_ socket message]
      (when on-message (on-message socket message)))
    (on-close [_ socket code reason]
      (when on-close (on-close socket code reason)))
    (on-error [_ socket error]
      (when on-error (on-error socket error)))
    (on-pong [_ socket data]
      (when on-pong (on-pong socket data)))))

(defn ws-response
  "Create a WebSocket upgrade response from a listener.
   The response is a map with ::ws/listener that Ring's Jetty adapter
   recognizes and upgrades to a WebSocket connection."
  ([ws-listener]
   (ws-response ws-listener nil))
  ([ws-listener protocol]
   (cond-> {::ws/listener ws-listener}
     protocol (assoc ::ws/protocol protocol))))

(defn ws-handler
  "Create a WebSocket handler from callback functions.
   Returns a handler fn that, when invoked with a request, returns a
   WebSocket upgrade response.

   Callbacks:
     :on-open    - (fn [socket])
     :on-message - (fn [socket message])
     :on-close   - (fn [socket code reason])
     :on-error   - (fn [socket error])
     :on-pong    - (fn [socket data])"
  [callbacks]
  (let [l (listener callbacks)]
    (fn [_request]
      (ws-response l))))

(defn ws-channel-handler
  "Create a WebSocket handler that exposes send/receive as core.async channels.
   Returns a handler fn. When invoked:
     - Calls `on-connect` with a map of {:send-ch :recv-ch :socket}
     - Messages from the client appear on recv-ch
     - Put messages on send-ch to send to the client
     - Close send-ch to close the WebSocket

   Options:
     :on-connect - (fn [{:keys [send-ch recv-ch socket]}]) called on open
     :on-close   - (fn [code reason]) called on close
     :on-error   - (fn [error]) called on error
     :send-buf   - send channel buffer (default: 32)
     :recv-buf   - receive channel buffer (default: 32)"
  [{:keys [on-connect on-close on-error send-buf recv-buf]
    :or {send-buf 32 recv-buf 32}}]
  (fn [_request]
    (let [send-ch (a/chan send-buf)
          recv-ch (a/chan recv-buf)]
      (ws-response
       (listener
        {:on-open
         (fn [socket]
           ;; Forward send-ch to socket
           (a/go-loop []
             (if-let [msg (a/<! send-ch)]
               (do (ws/send socket msg)
                   (recur))
               ;; send-ch closed, close socket
               (ws/close socket)))
           (when on-connect
             (on-connect {:send-ch send-ch
                          :recv-ch recv-ch
                          :socket socket})))

         :on-message
         (fn [_socket message]
           (a/put! recv-ch message))

         :on-close
         (fn [_socket code reason]
           (a/close! recv-ch)
           (a/close! send-ch)
           (when on-close (on-close code reason)))

         :on-error
         (fn [_socket error]
           (when on-error (on-error error)))})))))

(defn ws-upgrade
  "Middleware that upgrades WebSocket requests to WebSocket connections.
   Non-WebSocket requests pass through unchanged.
   `handler-fn` is called with the request env and should return a
   WebSocket response (via ws-handler or ws-channel-handler)."
  [handler-fn]
  (-> t/transformer
      (update :id conj ::ws-upgrade)
      (update :tf conj
              ::ws-upgrade
              (fn [env]
                (if (ws/upgrade-request? env)
                  (let [resp (handler-fn env)]
                    (assoc env :env-op (constantly resp)))
                  env)))))
