(ns hearth.alpha.websocket-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.core.async :as a]
   [ring.websocket :as ws]
   [hearth.alpha.websocket :as hws]
   [hearth.alpha.service :as svc]))

(deftest listener-creates-ring-listener-test
  (testing "listener creates an object satisfying Ring's Listener protocol"
    (let [l (hws/listener {:on-open (fn [s]) :on-message (fn [s m])})]
      (is (satisfies? ring.websocket.protocols/Listener l)))))

(deftest ws-response-creates-upgrade-response-test
  (testing "ws-response creates a Ring WebSocket upgrade response"
    (let [l (hws/listener {:on-message (fn [s m])})
          resp (hws/ws-response l)]
      (is (ws/websocket-response? resp))
      (is (some? (::ws/listener resp))))))

(deftest ws-response-with-protocol-test
  (testing "ws-response supports subprotocol selection"
    (let [l (hws/listener {})
          resp (hws/ws-response l "graphql-ws")]
      (is (= "graphql-ws" (::ws/protocol resp))))))

(deftest ws-handler-returns-upgrade-on-call-test
  (testing "ws-handler returns a handler fn that produces upgrade responses"
    (let [handler (hws/ws-handler {:on-message (fn [s m])})
          resp (handler {:request-method :get :uri "/ws"})]
      (is (ws/websocket-response? resp)))))

(deftest ws-channel-handler-creates-channels-test
  (testing "ws-channel-handler calls on-connect with channel map"
    (let [connect-info (promise)
          handler (hws/ws-channel-handler
                   {:on-connect (fn [info] (deliver connect-info info))})
          resp (handler {:request-method :get :uri "/ws"})
          ;; Simulate Ring/Jetty calling on-open
          l (::ws/listener resp)
          ;; Create a mock socket that satisfies the Socket protocol
          sent (atom [])
          mock-socket (reify
                        ring.websocket.protocols/Socket
                        (-send [_ msg] (swap! sent conj msg))
                        (-close [_ _ _])
                        (-open? [_] true)
                        (-ping [_ _])
                        (-pong [_ _]))]
      ;; Trigger on-open
      (ring.websocket.protocols/on-open l mock-socket)
      ;; Wait for on-connect callback
      (let [info (deref connect-info 1000 :timeout)]
        (is (not= :timeout info))
        (is (some? (:send-ch info)))
        (is (some? (:recv-ch info)))
        (is (some? (:socket info)))))))

(deftest ws-upgrade-middleware-test
  (testing "ws-upgrade middleware short-circuits for WS upgrade requests"
    (let [upgrade-mw (hws/ws-upgrade
                      (fn [env]
                        (hws/ws-response
                         (hws/listener {:on-message (fn [s m])}))))
          svc (svc/service {:routes [["/ws" :get
                                      (fn [_] {:status 200 :body "not-ws"})
                                      :route-name ::ws-endpoint]]
                             :with [upgrade-mw]})]
      ;; Normal GET request passes through to handler
      (let [resp (svc/response-for svc :get "/ws")]
        (is (= 200 (:status resp)))
        (is (= "not-ws" (:body resp))))
      ;; WebSocket upgrade request gets intercepted
      (let [resp (svc/response-for svc :get "/ws"
                   {:headers {"upgrade" "websocket"
                              "connection" "upgrade"}})]
        (is (ws/websocket-response? resp))))))
