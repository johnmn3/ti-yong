(ns bookshelf.handlers.users
  "User resource handlers — profiles, reading lists, and user management.
   All handlers extend the users-ns base transformer.
   All request data lives on :ctx."
  (:require
   [bookshelf.db :as db]
   [bookshelf.middleware :as app-mw]
   [hearth.alpha.middleware :as mw]
   [ti-yong.alpha.transformer :as t]))

;; --- Entity loader ---

(def ^:private load-user
  (app-mw/load-entity db/users :user "User"))

;; --- Namespace transformer ---

(def users-ns
  "Base transformer for all user handlers. JSON response serialization."
  (-> t/transformer
      (update :id conj ::users)
      (update :with conj mw/json-body-response)))

;; --- Read handlers ---

(def get-user-profile
  (-> users-ns
      (assoc :doc "Get public user profile by ID with review count and reading list count.")
      (update :id conj ::get-user-profile)
      (update :with conj load-user
              (app-mw/cache-control "public, max-age=120"))
      (update :tf conj
              ::get-user-profile
              (fn [env]
                (let [user (get-in env [:ctx :user])
                      reviews (db/find-reviews-by-user (:id user))
                      lists (db/find-reading-lists-for-user (:id user))
                      public-lists (filter :public? lists)]
                  (update env :res assoc
                          :body {:id (:id user)
                                 :username (:username user)
                                 :role (:role user)
                                 :created-at (:created-at user)
                                 :review-count (count reviews)
                                 :public-reading-lists (count public-lists)}))))))

(def user-reviews
  (-> users-ns
      (assoc :doc "List all reviews by a specific user, with book titles.")
      (update :id conj ::user-reviews)
      (update :with conj load-user)
      (update :tf conj
              ::user-reviews
              (fn [env]
                (let [user (get-in env [:ctx :user])
                      reviews (db/find-reviews-by-user (:id user))
                      enriched (mapv (fn [r]
                                       (let [book (get @db/books (:book-id r))]
                                         (assoc r :book-title (:title book))))
                                     reviews)]
                  (update env :res assoc :body enriched))))))

(def user-reading-lists
  (-> users-ns
      (assoc :doc "List reading lists for a user. Shows only public lists unless the viewer is the owner.")
      (update :id conj ::user-reading-lists)
      (update :with conj load-user
              app-mw/authenticate)
      (update :tf conj
              ::user-reading-lists
              (fn [env]
                (let [ctx (:ctx env)
                      user (:user ctx)
                      viewer (:current-user ctx)
                      lists (db/find-reading-lists-for-user (:id user))
                      visible (if (and viewer (= (:id viewer) (:id user)))
                                lists
                                (filter :public? lists))]
                  (update env :res assoc :body (vec visible)))))))

;; --- Write handlers ---

(def update-user-profile
  (-> users-ns
      (assoc :doc "Update own profile. Only email allowed. Must be logged in as the user.")
      (update :id conj ::update-user-profile)
      (update :with conj mw/body-params mw/keyword-params
              app-mw/authenticate app-mw/require-auth
              load-user)
      (update :tf conj
              ::update-user-profile
              (fn [env]
                (let [ctx (:ctx env)
                      user (:user ctx)
                      current (:current-user ctx)
                      params (:body-params ctx)]
                  (if (and (not= (:id user) (:id current))
                           (not= :admin (:role current)))
                    (update env :res assoc
                            :status 403
                            :body {:error "Cannot update another user's profile"})
                    (let [allowed-keys [:email]
                          updates (select-keys params allowed-keys)
                          updated (merge user updates)]
                      (swap! db/users assoc (:id user) updated)
                      (update env :res assoc
                              :body (dissoc updated :password-hash)))))))))
