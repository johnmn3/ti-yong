(ns bookshelf.handlers.reviews
  "Review resource handlers — users can review books.
   All handlers extend the reviews-ns base transformer.
   Handlers nested under books include their own load-book middleware.
   All request data lives on :ctx."
  (:require
   [bookshelf.db :as db]
   [bookshelf.middleware :as app-mw]
   [hearth.alpha.middleware :as mw]
   [ti-yong.alpha.transformer :as t]))

;; --- Entity loaders ---

(def ^:private load-book
  (app-mw/load-entity db/books :book "Book"))

(def ^:private load-review
  (app-mw/load-entity db/reviews :review "Review"))

;; --- Namespace transformer ---

(def reviews-ns
  "Base transformer for all review handlers. JSON response serialization."
  (-> t/transformer
      (update :id conj ::reviews)
      (update :with conj mw/json-body-response)))

;; --- Read handlers ---

(def list-book-reviews
  (-> reviews-ns
      (assoc :doc "List all reviews for a specific book, with reviewer usernames.")
      (update :id conj ::list-book-reviews)
      (update :with conj load-book
              (app-mw/cache-control "public, max-age=120"))
      (update :tf conj
              ::list-book-reviews
              (fn [env]
                (let [book (get-in env [:ctx :book])
                      reviews (db/find-reviews-for-book (:id book))
                      enriched (mapv (fn [r]
                                       (let [user (get @db/users (:user-id r))]
                                         (assoc r :username (:username user))))
                                     reviews)]
                  (update env :res assoc :body enriched))))))

(def get-review
  (-> reviews-ns
      (assoc :doc "Get a single review by ID with reviewer and book info.")
      (update :id conj ::get-review)
      (update :with conj load-review)
      (update :tf conj
              ::get-review
              (fn [env]
                (let [review (get-in env [:ctx :review])
                      user (get @db/users (:user-id review))
                      book (get @db/books (:book-id review))]
                  (update env :res assoc
                          :body (assoc review
                                       :username (:username user)
                                       :book-title (:title book))))))))

;; --- Write handlers ---

(def create-review
  (-> reviews-ns
      (assoc :doc "Create a review for a book. One review per user per book. Returns 201.")
      (update :id conj ::create-review)
      (update :with conj mw/body-params mw/keyword-params
              app-mw/authenticate app-mw/require-auth
              load-book)
      (update :tf conj
              ::create-review
              (fn [env]
                (let [ctx (:ctx env)
                      book (:book ctx)
                      user (:current-user ctx)
                      params (:body-params ctx)
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
                                  :rating (or (:rating params) 3)
                                  :title (:title params "")
                                  :body (:body params "")
                                  :created-at (str (java.time.Instant/now))}]
                      (swap! db/reviews assoc id review)
                      (update env :res assoc
                              :status 201
                              :body (assoc review :username (:username user))))))))))

(def update-review
  (-> reviews-ns
      (assoc :doc "Update own review by ID. Cannot update other users' reviews.")
      (update :id conj ::update-review)
      (update :with conj mw/body-params mw/keyword-params
              app-mw/authenticate app-mw/require-auth
              load-review)
      (update :tf conj
              ::update-review
              (fn [env]
                (let [ctx (:ctx env)
                      review (:review ctx)
                      user (:current-user ctx)
                      params (:body-params ctx)]
                  (if (not= (:user-id review) (:id user))
                    (update env :res assoc
                            :status 403
                            :body {:error "Cannot edit another user's review"})
                    (let [updated (merge review (select-keys params [:rating :title :body]))]
                      (swap! db/reviews assoc (:id review) updated)
                      (update env :res assoc :body updated))))))))

(def delete-review
  (-> reviews-ns
      (assoc :doc "Delete own review by ID. Admins can delete any review.")
      (update :id conj ::delete-review)
      (update :with conj app-mw/authenticate app-mw/require-auth
              load-review)
      (update :tf conj
              ::delete-review
              (fn [env]
                (let [ctx (:ctx env)
                      review (:review ctx)
                      user (:current-user ctx)]
                  (if (and (not= (:user-id review) (:id user))
                           (not= :admin (:role user)))
                    (update env :res assoc
                            :status 403
                            :body {:error "Cannot delete another user's review"})
                    (do
                      (swap! db/reviews dissoc (:id review))
                      (update env :res assoc
                              :body {:message "Review deleted"
                                     :id (:id review)}))))))))
