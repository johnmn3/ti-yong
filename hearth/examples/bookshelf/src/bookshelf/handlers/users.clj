(ns bookshelf.handlers.users
  "User resource handlers — profiles, reading lists, and user management."
  (:require
   [bookshelf.db :as db]))

(defn list-users
  "GET /api/users — list all users (admin only). Omits sensitive fields."
  [env]
  (let [pagination (:pagination env {:page 1 :per-page 20})
        users-list (->> (vals @db/users)
                        (map #(dissoc % :password-hash))
                        (sort-by :username))
        result (db/paginate users-list pagination)]
    {:status 200
     :headers {}
     :body result}))

(defn get-user
  "GET /api/users/:id — fetch user profile with public info."
  [env]
  (let [user (:target-user env)
        reviews (db/find-reviews-by-user (:id user))
        reading-lists (filter :public? (db/find-reading-lists-for-user (:id user)))]
    {:status 200
     :headers {}
     :body {:id (:id user)
            :username (:username user)
            :role (:role user)
            :created-at (:created-at user)
            :review-count (count reviews)
            :reading-lists (count reading-lists)}}))

(defn get-profile
  "GET /api/profile — current user's own profile (requires auth)."
  [env]
  (let [user (:current-user env)
        reviews (db/find-reviews-by-user (:id user))
        lists (db/find-reading-lists-for-user (:id user))]
    {:status 200
     :headers {}
     :body {:id (:id user)
            :username (:username user)
            :email (:email user)
            :role (:role user)
            :created-at (:created-at user)
            :review-count (count reviews)
            :reading-lists (mapv #(select-keys % [:id :name :public? :book-ids]) lists)}}))

(defn update-profile
  "PUT /api/profile — update current user's profile."
  [env]
  (let [user (:current-user env)
        params (:body-params env)
        allowed-keys [:email :username]
        updates (select-keys params allowed-keys)
        ;; Check username uniqueness
        username (:username updates)]
    (if (and username
             (not= username (:username user))
             (db/find-user-by-username username))
      {:status 409
       :headers {}
       :body {:error "Username already taken"}}
      (let [updated (merge (get @db/users (:id user)) updates)]
        (swap! db/users assoc (:id user) updated)
        {:status 200
         :headers {}
         :body (dissoc updated :password-hash)}))))
