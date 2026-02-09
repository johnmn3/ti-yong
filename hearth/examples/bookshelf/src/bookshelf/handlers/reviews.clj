(ns bookshelf.handlers.reviews
  "Review resource handlers — users can review books."
  (:require
   [bookshelf.db :as db]))

(defn list-reviews
  "GET /api/books/:id/reviews — list reviews for a specific book."
  [env]
  (let [book (:book env)
        reviews (db/find-reviews-for-book (:id book))
        enriched (mapv (fn [r]
                         (let [user (get @db/users (:user-id r))]
                           (assoc r :username (:username user))))
                       reviews)]
    {:status 200
     :headers {}
     :body {:book-id (:id book)
            :book-title (:title book)
            :reviews enriched
            :count (count enriched)
            :average-rating (when (seq reviews)
                              (/ (reduce + (map :rating reviews))
                                 (double (count reviews))))}}))

(defn create-review
  "POST /api/books/:id/reviews — submit a review for a book. Requires auth."
  [env]
  (let [book (:book env)
        user (:current-user env)
        params (:body-params env)
        ;; Check if user already reviewed this book
        existing (first (filter #(and (= (:book-id %) (:id book))
                                      (= (:user-id %) (:id user)))
                                (vals @db/reviews)))]
    (if existing
      {:status 409
       :headers {}
       :body {:error "You already reviewed this book"
              :existing-review-id (:id existing)}}
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
        {:status 201
         :headers {"Location" (str "/api/reviews/" id)}
         :body (assoc review :username (:username user))}))))

(defn get-review
  "GET /api/reviews/:id — fetch a single review."
  [env]
  (let [review (:review env)
        user (get @db/users (:user-id review))
        book (get @db/books (:book-id review))]
    {:status 200
     :headers {}
     :body (assoc review
                   :username (:username user)
                   :book-title (:title book))}))

(defn update-review
  "PUT /api/reviews/:id — update a review. Only the review author can update."
  [env]
  (let [review (:review env)
        user (:current-user env)
        params (:body-params env)]
    (if (not= (:id user) (:user-id review))
      {:status 403
       :headers {}
       :body {:error "You can only edit your own reviews"}}
      (let [updated (merge review
                           (select-keys params [:rating :title :body])
                           {:updated-at (str (java.time.Instant/now))})]
        (swap! db/reviews assoc (:id review) updated)
        {:status 200
         :headers {}
         :body updated}))))

(defn delete-review
  "DELETE /api/reviews/:id — delete a review. Author or admin/moderator can delete."
  [env]
  (let [review (:review env)
        user (:current-user env)]
    (if (and (not= (:id user) (:user-id review))
             (not (#{:admin :moderator} (:role user))))
      {:status 403
       :headers {}
       :body {:error "Insufficient permissions to delete this review"}}
      (do (swap! db/reviews dissoc (:id review))
          {:status 200
           :headers {}
           :body {:message "Review deleted" :id (:id review)}}))))

(defn user-reviews
  "GET /api/users/:id/reviews — list reviews by a specific user."
  [env]
  (let [user (:target-user env)
        reviews (db/find-reviews-by-user (:id user))
        enriched (mapv (fn [r]
                         (let [book (get @db/books (:book-id r))]
                           (assoc r :book-title (:title book))))
                       reviews)]
    {:status 200
     :headers {}
     :body {:user-id (:id user)
            :username (:username user)
            :reviews enriched
            :count (count enriched)}}))
