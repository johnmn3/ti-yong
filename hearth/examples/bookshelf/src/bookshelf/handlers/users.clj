(ns bookshelf.handlers.users
  "User resource handlers — profiles, reading lists, and user management.
   All handlers are transformers, composable with middleware after definition."
  (:require
   [bookshelf.db :as db]
   [ti-yong.alpha.transformer :as t]))

(def list-users
  (-> t/transformer
      (update :id conj ::list-users)
      (update :tf conj
              ::list-users
              (fn [env]
                (let [pagination (:pagination env {:page 1 :per-page 20})
                      users-list (->> (vals @db/users)
                                      (map #(dissoc % :password-hash))
                                      (sort-by :username))
                      result (db/paginate users-list pagination)]
                  (update env :res assoc :body result))))))

(def get-user
  (-> t/transformer
      (update :id conj ::get-user)
      (update :tf conj
              ::get-user
              (fn [env]
                (let [user (:target-user env)
                      reviews (db/find-reviews-by-user (:id user))
                      reading-lists (filter :public? (db/find-reading-lists-for-user (:id user)))]
                  (update env :res assoc
                          :body {:id (:id user)
                                 :username (:username user)
                                 :role (:role user)
                                 :created-at (:created-at user)
                                 :review-count (count reviews)
                                 :reading-lists (count reading-lists)}))))))

(def get-profile
  (-> t/transformer
      (update :id conj ::get-profile)
      (update :tf conj
              ::get-profile
              (fn [env]
                (let [user (:current-user env)
                      reviews (db/find-reviews-by-user (:id user))
                      lists (db/find-reading-lists-for-user (:id user))]
                  (update env :res assoc
                          :body {:id (:id user)
                                 :username (:username user)
                                 :email (:email user)
                                 :role (:role user)
                                 :created-at (:created-at user)
                                 :review-count (count reviews)
                                 :reading-lists (mapv #(select-keys % [:id :name :public? :book-ids]) lists)}))))))

(def update-profile
  (-> t/transformer
      (update :id conj ::update-profile)
      (update :tf conj
              ::update-profile
              (fn [env]
                (let [user (:current-user env)
                      params (:body-params env)
                      allowed-keys [:email :username]
                      updates (select-keys params allowed-keys)
                      username (:username updates)]
                  (if (and username
                           (not= username (:username user))
                           (db/find-user-by-username username))
                    (update env :res assoc
                            :status 409
                            :body {:error "Username already taken"})
                    (let [updated (merge (get @db/users (:id user)) updates)]
                      (swap! db/users assoc (:id user) updated)
                      (update env :res assoc
                              :body (dissoc updated :password-hash)))))))))
