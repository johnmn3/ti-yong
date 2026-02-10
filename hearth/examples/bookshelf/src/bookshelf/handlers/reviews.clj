(ns bookshelf.handlers.reviews
  "Review resource handlers — users can review books.
   All handlers are transformers, composable with middleware after definition."
  (:require
   [bookshelf.db :as db]
   [ti-yong.alpha.transformer :as t]))

(def list-reviews
  (-> t/transformer
      (update :id conj ::list-reviews)
      (update :tf conj
              ::list-reviews
              (fn [env]
                (let [book (:book env)
                      reviews (db/find-reviews-for-book (:id book))
                      enriched (mapv (fn [r]
                                       (let [user (get @db/users (:user-id r))]
                                         (assoc r :username (:username user))))
                                     reviews)]
                  (update env :res assoc
                          :body {:book-id (:id book)
                                 :book-title (:title book)
                                 :reviews enriched
                                 :count (count enriched)
                                 :average-rating (when (seq reviews)
                                                   (/ (reduce + (map :rating reviews))
                                                      (double (count reviews))))}))))))

(def create-review
  (-> t/transformer
      (update :id conj ::create-review)
      (update :tf conj
              ::create-review
              (fn [env]
                (let [book (:book env)
                      user (:current-user env)
                      params (:body-params env)
                      existing (first (filter #(and (= (:book-id %) (:id book))
                                                    (= (:user-id %) (:id user)))
                                              (vals @db/reviews)))]
                  (if existing
                    (update env :res assoc
                            :status 409
                            :body {:error "You already reviewed this book"
                                   :existing-review-id (:id existing)})
                    (let [id (db/next-id)
                          review {:id id
                                  :book-id (:id book)
                                  :user-id (:id user)
                                  :rating (min 5 (max 1 (or (:rating params) 3)))
                                  :title (:title params "")
                                  :body (:body params "")
                                  :created-at (str (java.time.Instant/now))}]
                      (swap! db/reviews assoc id review)
                      (db/add-notification! {:type :new-review
                                             :book-id (:id book)
                                             :review-id id
                                             :by (:username user)})
                      (update env :res assoc
                              :status 201
                              :headers {"Location" (str "/api/reviews/" id)}
                              :body (assoc review :username (:username user))))))))))

(def get-review
  (-> t/transformer
      (update :id conj ::get-review)
      (update :tf conj
              ::get-review
              (fn [env]
                (let [review (:review env)
                      user (get @db/users (:user-id review))
                      book (get @db/books (:book-id review))]
                  (update env :res assoc
                          :body (assoc review
                                       :username (:username user)
                                       :book-title (:title book))))))))

(def update-review
  (-> t/transformer
      (update :id conj ::update-review)
      (update :tf conj
              ::update-review
              (fn [env]
                (let [review (:review env)
                      user (:current-user env)
                      params (:body-params env)]
                  (if (not= (:id user) (:user-id review))
                    (update env :res assoc
                            :status 403
                            :body {:error "You can only edit your own reviews"})
                    (let [updated (merge review
                                         (select-keys params [:rating :title :body])
                                         {:updated-at (str (java.time.Instant/now))})]
                      (swap! db/reviews assoc (:id review) updated)
                      (update env :res assoc :body updated))))))))

(def delete-review
  (-> t/transformer
      (update :id conj ::delete-review)
      (update :tf conj
              ::delete-review
              (fn [env]
                (let [review (:review env)
                      user (:current-user env)]
                  (if (and (not= (:id user) (:user-id review))
                           (not (#{:admin :moderator} (:role user))))
                    (update env :res assoc
                            :status 403
                            :body {:error "Insufficient permissions to delete this review"})
                    (do (swap! db/reviews dissoc (:id review))
                        (update env :res assoc
                                :body {:message "Review deleted" :id (:id review)}))))))))

(def user-reviews
  (-> t/transformer
      (update :id conj ::user-reviews)
      (update :tf conj
              ::user-reviews
              (fn [env]
                (let [user (:target-user env)
                      reviews (db/find-reviews-by-user (:id user))
                      enriched (mapv (fn [r]
                                       (let [book (get @db/books (:book-id r))]
                                         (assoc r :book-title (:title book))))
                                     reviews)]
                  (update env :res assoc
                          :body {:user-id (:id user)
                                 :username (:username user)
                                 :reviews enriched
                                 :count (count enriched)}))))))
