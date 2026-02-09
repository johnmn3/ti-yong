(ns bookshelf.handlers.auth
  "Authentication handlers — login, logout, register."
  (:require
   [bookshelf.db :as db]))

(defn login
  "POST /api/auth/login — authenticate with username/password.
   Sets session :user-id on success."
  [env]
  (let [params (:body-params env)
        username (:username params)
        password (:password params)
        user (db/find-user-by-username username)]
    (if (and user
             ;; In a real app: (bcrypt/check password (:password-hash user))
             (= (str "hashed-" password) (:password-hash user)))
      {:status 200
       :headers {}
       :body {:message "Login successful"
              :user (select-keys user [:id :username :role])}
       ;; hearth session middleware picks this up
       :session {:user-id (:id user)}}
      {:status 401
       :headers {}
       :body {:error "Invalid username or password"}})))

(defn logout
  "POST /api/auth/logout — clear the session."
  [_env]
  {:status 200
   :headers {}
   :body {:message "Logged out"}
   :session {}})

(defn register
  "POST /api/auth/register — create a new user account."
  [env]
  (let [params (:body-params env)
        {:keys [username email password]} params]
    (cond
      (or (nil? username) (nil? email) (nil? password))
      {:status 400
       :headers {}
       :body {:error "username, email, and password are required"}}

      (db/find-user-by-username username)
      {:status 409
       :headers {}
       :body {:error "Username already taken"}}

      (< (count password) 8)
      {:status 400
       :headers {}
       :body {:error "Password must be at least 8 characters"}}

      :else
      (let [id (db/next-id)
            user {:id id
                  :username username
                  :email email
                  :role :user
                  :password-hash (str "hashed-" password)
                  :created-at (str (java.time.Instant/now))}]
        (swap! db/users assoc id user)
        {:status 201
         :headers {"Location" (str "/api/users/" id)}
         :body {:message "Registration successful"
                :user (select-keys user [:id :username :role])}
         :session {:user-id id}}))))

(defn whoami
  "GET /api/auth/whoami — return info about the current authenticated user."
  [env]
  (if-let [user (:current-user env)]
    {:status 200
     :headers {}
     :body {:authenticated true
            :user (select-keys user [:id :username :email :role])
            :auth-method (:auth-method env)}}
    {:status 200
     :headers {}
     :body {:authenticated false}}))
