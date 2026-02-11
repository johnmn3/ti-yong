(ns bookshelf.handlers.auth
  "Authentication handlers — login, logout, register, whoami.
   All handlers extend the auth-ns base transformer, which provides
   JSON response + cookie/session support for all auth endpoints.
   All request data lives on :ctx."
  (:require
   [bookshelf.db :as db]
   [bookshelf.middleware :as app-mw]
   [hearth.alpha.middleware :as mw]
   [ti-yong.alpha.transformer :as t]))

;; --- Namespace transformer ---

(def auth-ns
  "Base transformer for auth handlers. JSON response + cookie/session support.
   Cookies and session are pushed here since every auth handler needs them."
  (-> t/transformer
      (update :id conj ::auth)
      (update :with conj mw/json-body-response mw/cookies (mw/session))))

;; --- Handlers ---

(def login
  (-> auth-ns
      (assoc :doc "Authenticate user with username/password. Returns session cookie on success.")
      (update :id conj ::login)
      (update :with conj mw/body-params mw/keyword-params)
      (update :tf conj
              ::login
              (fn [env]
                (let [ctx (:ctx env)
                      params (:body-params ctx)
                      username (:username params)
                      password (:password params)
                      user (db/find-user-by-username username)]
                  (if (and user
                           (= (str "hashed-" password) (:password-hash user)))
                    (update env :res assoc
                            :body {:message "Login successful"
                                   :user (select-keys user [:id :username :role])}
                            :session {:user-id (:id user)})
                    (update env :res assoc
                            :status 401
                            :body {:error "Invalid username or password"})))))))

(def logout
  (-> auth-ns
      (assoc :doc "Clear session and log user out.")
      (update :id conj ::logout)
      (update :tf conj
              ::logout
              (fn [env]
                (update env :res assoc
                        :body {:message "Logged out"}
                        :session {})))))

(def register
  (-> auth-ns
      (assoc :doc "Register a new user. Requires username, email, password (min 8 chars). Returns 201.")
      (update :id conj ::register)
      (update :with conj mw/body-params mw/keyword-params)
      (update :tf conj
              ::register
              (fn [env]
                (let [ctx (:ctx env)
                      params (:body-params ctx)
                      {:keys [username email password]} params]
                  (cond
                    (or (nil? username) (nil? email) (nil? password))
                    (update env :res assoc
                            :status 400
                            :body {:error "username, email, and password are required"})

                    (db/find-user-by-username username)
                    (update env :res assoc
                            :status 409
                            :body {:error "Username already taken"})

                    (< (count password) 8)
                    (update env :res assoc
                            :status 400
                            :body {:error "Password must be at least 8 characters"})

                    :else
                    (let [id (db/next-id)
                          user {:id id
                                :username username
                                :email email
                                :role :user
                                :password-hash (str "hashed-" password)
                                :created-at (str (java.time.Instant/now))}]
                      (swap! db/users assoc id user)
                      (update env :res assoc
                              :status 201
                              :headers {"Location" (str "/api/users/" id)}
                              :body {:message "Registration successful"
                                     :user (select-keys user [:id :username :role])}
                              :session {:user-id id}))))))))

(def whoami
  (-> auth-ns
      (assoc :doc "Check current authentication status. Returns user info if authenticated.")
      (update :id conj ::whoami)
      (update :with conj app-mw/authenticate)
      (update :tf conj
              ::whoami
              (fn [env]
                (let [ctx (:ctx env)]
                  (if-let [user (:current-user ctx)]
                    (update env :res assoc
                            :body {:authenticated true
                                   :user (select-keys user [:id :username :email :role])
                                   :auth-method (:auth-method ctx)})
                    (update env :res assoc
                            :body {:authenticated false})))))))
