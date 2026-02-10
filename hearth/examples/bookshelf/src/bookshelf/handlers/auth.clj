(ns bookshelf.handlers.auth
  "Authentication handlers — login, logout, register.
   All handlers are transformers, composable with middleware after definition."
  (:require
   [bookshelf.db :as db]
   [ti-yong.alpha.transformer :as t]))

(def login
  (-> t/transformer
      (update :id conj ::login)
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
  (-> t/transformer
      (update :id conj ::logout)
      (update :tf conj
              ::logout
              (fn [env]
                (update env :res assoc
                        :body {:message "Logged out"}
                        :session {})))))

(def register
  (-> t/transformer
      (update :id conj ::register)
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
  (-> t/transformer
      (update :id conj ::whoami)
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
