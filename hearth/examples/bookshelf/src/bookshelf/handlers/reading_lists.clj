(ns bookshelf.handlers.reading-lists
  "Reading list handlers — curated book lists per user.
   All handlers extend the reading-lists-ns base transformer."
  (:require
   [bookshelf.db :as db]
   [bookshelf.middleware :as app-mw]
   [hearth.alpha.middleware :as mw]
   [ti-yong.alpha.transformer :as t]))

;; --- Entity loader ---

(def ^:private load-reading-list
  (app-mw/load-entity db/reading-lists :reading-list "Reading list"))

;; --- Namespace transformer ---

(def reading-lists-ns
  "Base transformer for all reading list handlers. JSON response serialization."
  (-> t/transformer
      (update :id conj ::reading-lists)
      (update :with into [mw/json-body-response])))

;; --- Read handlers ---

(def list-public-lists
  (-> reading-lists-ns
      (update :id conj ::list-public-lists)
      (update :with into [mw/query-params mw/keyword-params
                          (app-mw/pagination-params)])
      (update :tf conj
              ::list-public-lists
              (fn [env]
                (let [pagination (:pagination env {:page 1 :per-page 10})
                      lists (->> (vals @db/reading-lists)
                                 (filter :public?)
                                 (map (fn [rl]
                                        (let [user (get @db/users (:user-id rl))]
                                          (assoc rl :username (:username user)
                                                    :book-count (count (:book-ids rl))))))
                                 (sort-by :created-at)
                                 reverse)
                      result (db/paginate lists pagination)]
                  (update env :res assoc :body result))))))

(def get-reading-list
  (-> reading-lists-ns
      (update :id conj ::get-reading-list)
      (update :with into [mw/cookies (mw/session)
                          app-mw/authenticate
                          load-reading-list])
      (update :tf conj
              ::get-reading-list
              (fn [env]
                (let [rl (:reading-list env)
                      user (get @db/users (:user-id rl))
                      current-user (:current-user env)]
                  (if (and (not (:public? rl))
                           (or (nil? current-user)
                               (not= (:id current-user) (:user-id rl))))
                    (update env :res assoc
                            :status 404
                            :body {:error "Reading list not found"})
                    (let [books (mapv (fn [bid]
                                        (when-let [b (get @db/books bid)]
                                          (select-keys b [:id :title :author-id :price :genres])))
                                      (:book-ids rl))
                          books (filterv some? books)]
                      (update env :res assoc
                              :body (assoc rl :username (:username user)
                                              :books books)))))))))

;; --- Write handlers (require auth) ---

(def create-reading-list
  (-> reading-lists-ns
      (update :id conj ::create-reading-list)
      (update :with into [mw/body-params mw/keyword-params
                          app-mw/authenticate app-mw/require-auth])
      (update :tf conj
              ::create-reading-list
              (fn [env]
                (let [user (:current-user env)
                      params (:body-params env)
                      id (db/next-id)
                      rl {:id id
                          :user-id (:id user)
                          :name (:name params "Untitled")
                          :public? (boolean (:public params false))
                          :book-ids (vec (or (:book-ids params) []))
                          :created-at (str (java.time.Instant/now))}]
                  (swap! db/reading-lists assoc id rl)
                  (update env :res assoc
                          :status 201
                          :headers {"Location" (str "/api/reading-lists/" id)}
                          :body rl))))))

(def update-reading-list
  (-> reading-lists-ns
      (update :id conj ::update-reading-list)
      (update :with into [mw/body-params mw/keyword-params
                          app-mw/authenticate app-mw/require-auth
                          load-reading-list])
      (update :tf conj
              ::update-reading-list
              (fn [env]
                (let [rl (:reading-list env)
                      user (:current-user env)
                      params (:body-params env)]
                  (if (not= (:id user) (:user-id rl))
                    (update env :res assoc
                            :status 403
                            :body {:error "You can only edit your own reading lists"})
                    (let [updated (merge rl (select-keys params [:name :public? :book-ids]))]
                      (swap! db/reading-lists assoc (:id rl) updated)
                      (update env :res assoc :body updated))))))))

(def delete-reading-list
  (-> reading-lists-ns
      (update :id conj ::delete-reading-list)
      (update :with into [app-mw/authenticate app-mw/require-auth
                          load-reading-list])
      (update :tf conj
              ::delete-reading-list
              (fn [env]
                (let [rl (:reading-list env)
                      user (:current-user env)]
                  (if (and (not= (:id user) (:user-id rl))
                           (not= :admin (:role user)))
                    (update env :res assoc
                            :status 403
                            :body {:error "Insufficient permissions"})
                    (do (swap! db/reading-lists dissoc (:id rl))
                        (update env :res assoc
                                :body {:message "Reading list deleted" :id (:id rl)}))))))))

(def add-book-to-list
  (-> reading-lists-ns
      (update :id conj ::add-book-to-list)
      (update :with into [mw/body-params mw/keyword-params
                          app-mw/authenticate app-mw/require-auth
                          load-reading-list])
      (update :tf conj
              ::add-book-to-list
              (fn [env]
                (let [rl (:reading-list env)
                      user (:current-user env)
                      params (:body-params env)
                      book-id (:book-id params)]
                  (cond
                    (not= (:id user) (:user-id rl))
                    (update env :res assoc
                            :status 403
                            :body {:error "You can only modify your own reading lists"})

                    (nil? (get @db/books book-id))
                    (update env :res assoc
                            :status 404
                            :body {:error "Book not found"})

                    (some #{book-id} (:book-ids rl))
                    (update env :res assoc
                            :status 409
                            :body {:error "Book already in list"})

                    :else
                    (let [updated (update rl :book-ids conj book-id)]
                      (swap! db/reading-lists assoc (:id rl) updated)
                      (update env :res assoc :body updated))))))))

(def remove-book-from-list
  (-> reading-lists-ns
      (update :id conj ::remove-book-from-list)
      (update :with into [app-mw/authenticate app-mw/require-auth
                          load-reading-list])
      (update :tf conj
              ::remove-book-from-list
              (fn [env]
                (let [rl (:reading-list env)
                      user (:current-user env)
                      book-id-str (get-in env [:path-params-values "book_id"])
                      book-id (when book-id-str (Integer/parseInt book-id-str))]
                  (if (not= (:id user) (:user-id rl))
                    (update env :res assoc
                            :status 403
                            :body {:error "You can only modify your own reading lists"})
                    (let [updated (update rl :book-ids #(vec (remove #{book-id} %)))]
                      (swap! db/reading-lists assoc (:id rl) updated)
                      (update env :res assoc :body updated))))))))
