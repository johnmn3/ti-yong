(ns bookshelf.handlers.auth
  "Authentication handlers — login, logout, register, whoami.
   All handlers extend the auth-ns base transformer.
   Auth handlers manage cookies and sessions."
  (:require
   [bookshelf.db :as db]
   [bookshelf.middleware :as app-mw]
   [hearth.alpha.middleware :as mw]
   [ti-yong.alpha.transformer :as t]))

;; --- Namespace transformer ---

(def auth-ns
  "Base transformer for auth handlers. JSON response + cookie/session support."
  (-> t/transformer
      (update :id conj ::auth)
      (update :with into [mw/json-body-response])))

;; --- Handlers ---

(def login
  (-> auth-ns
      (update :id conj ::login)
      (update :with into [mw/body-params mw/keyword-params
                          mw/cookies (mw/session)])
      (update :tf conj
              ::login
              (fn [env]
                (let [params (:body-params env)
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
      (update :id conj ::logout)
      (update :with into [mw/cookies (mw/session)])
      (update :tf conj
              ::logout
              (fn [env]
                (update env :res assoc
                        :body {:message "Logged out"}
                        :session {})))))

(def register
  (-> auth-ns
      (update :id conj ::register)
      (update :with into [mw/body-params mw/keyword-params
                          mw/cookies (mw/session)])
      (update :tf conj
              ::register
              (fn [env]
                (let [params (:body-params env)
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
      (update :id conj ::whoami)
      (update :with into [mw/cookies (mw/session)
                          app-mw/authenticate])
      (update :tf conj
              ::whoami
              (fn [env]
                (if-let [user (:current-user env)]
                  (update env :res assoc
                          :body {:authenticated true
                                 :user (select-keys user [:id :username :email :role])
                                 :auth-method (:auth-method env)})
                  (update env :res assoc
                          :body {:authenticated false}))))))
