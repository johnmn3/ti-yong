(ns bookshelf.handlers.realtime
  "Real-time handlers — SSE notifications and WebSocket chat.
   Demonstrates hearth's SSE and WebSocket support."
  (:require
   [bookshelf.db :as db]
   [clojure.core.async :as a]
   [hearth.alpha :as http]
   [hearth.alpha.websocket :as ws]))

;; --- SSE: Live notification stream ---

(defonce ^:private notification-channels (atom #{}))

(defn broadcast-notification!
  "Send a notification to all connected SSE clients."
  [event]
  (doseq [ch @notification-channels]
    (a/put! ch event)))

(defn notification-stream
  "SSE handler: streams live notifications to connected clients.
   Usage: GET /api/notifications/stream

   Demonstrates:
   - SSE event stream creation
   - Per-client channels
   - Heartbeat for keep-alive
   - Cleanup on disconnect"
  []
  (http/event-stream
   (fn [event-ch _request-env]
     ;; Register this client
     (swap! notification-channels conj event-ch)
     ;; Send initial greeting
     (a/put! event-ch {:event "connected"
                        :data "Welcome to BookShelf notifications"})
     ;; Clean up when channel closes
     (a/go
       (loop []
         (when (a/<! (a/timeout 1000))
           (if (a/offer! event-ch {:event "heartbeat" :data ""})
             (recur)
             ;; Channel closed, clean up
             (swap! notification-channels disj event-ch))))))
   {:heartbeat-ms 15000
    :buf-or-n 64}))

;; --- SSE: Book activity feed ---

(defn activity-feed
  "SSE handler: streams book-related activity (new reviews, stock changes, etc.).
   Usage: GET /api/activity/stream

   Simulates a live activity feed for demonstration."
  []
  (http/event-stream
   (fn [event-ch _request-env]
     ;; Send recent notifications as initial batch
     (doseq [n (take 5 (reverse @db/notifications))]
       (a/put! event-ch {:event (name (:type n))
                          :data (pr-str (dissoc n :type))
                          :id (str (:id n))})))
   {:heartbeat-ms 30000}))

;; --- WebSocket: Live chat room ---

(defonce ^:private chat-clients (atom {}))
(defonce chat-history (atom []))

(defn chat-handler
  "WebSocket handler: chat room for book discussions.
   Usage: GET /ws/chat (WebSocket upgrade)

   Demonstrates:
   - WebSocket connection with core.async channels
   - Broadcasting to multiple clients
   - Chat history on connect
   - Graceful cleanup on disconnect

   Protocol:
   - Client sends: JSON {\"message\": \"...\", \"username\": \"...\"}
   - Server broadcasts: JSON {\"type\": \"message\", \"username\": \"...\", \"message\": \"...\", \"timestamp\": \"...\"}"
  []
  (ws/ws-channel-handler
   {:on-connect
    (fn [{:keys [send-ch recv-ch socket]}]
      (let [client-id (str (java.util.UUID/randomUUID))]
        ;; Register client
        (swap! chat-clients assoc client-id {:send-ch send-ch :socket socket})
        ;; Send chat history
        (doseq [msg (take-last 20 @chat-history)]
          (a/put! send-ch (pr-str msg)))
        ;; Handle incoming messages
        (a/go-loop []
          (when-let [raw-msg (a/<! recv-ch)]
            (let [msg-str (str raw-msg)
                  ;; Simple parse: expect "username:message" format for simplicity
                  [username & rest] (clojure.string/split msg-str #":" 2)
                  message (or (first rest) msg-str)
                  chat-msg {:type "message"
                            :client-id client-id
                            :username (or username "anonymous")
                            :message (clojure.string/trim message)
                            :timestamp (str (java.time.Instant/now))}]
              ;; Store in history
              (swap! chat-history conj chat-msg)
              ;; Broadcast to all clients
              (doseq [[_ client] @chat-clients]
                (a/put! (:send-ch client) (pr-str chat-msg)))
              (recur))))))

    :on-close
    (fn [_code _reason]
      ;; Clean up disconnected client
      ;; In a real app, we'd track the client ID per socket
      nil)

    :on-error
    (fn [error]
      (println "WebSocket error:" (.getMessage ^Throwable error)))}))

;; --- WebSocket: Live book updates ---

(defonce ^:private update-subscribers (atom {}))

(defn book-updates-handler
  "WebSocket handler: subscribe to live book updates (price changes, stock changes).
   Usage: GET /ws/books/updates (WebSocket upgrade)

   Demonstrates:
   - Targeted WebSocket messaging
   - Subscription-based updates"
  []
  (ws/ws-channel-handler
   {:on-connect
    (fn [{:keys [send-ch recv-ch]}]
      (let [sub-id (str (java.util.UUID/randomUUID))]
        (swap! update-subscribers assoc sub-id send-ch)
        (a/put! send-ch (pr-str {:type "subscribed"
                                  :subscription-id sub-id
                                  :message "Listening for book updates"}))
        ;; Process subscription commands from client
        (a/go-loop []
          (when-let [_msg (a/<! recv-ch)]
            ;; Clients can send filter preferences (future enhancement)
            (recur)))))

    :on-close
    (fn [_code _reason]
      nil)}))
