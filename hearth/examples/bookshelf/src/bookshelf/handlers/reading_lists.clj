(ns bookshelf.handlers.reading-lists
  "Reading list handlers — curated book lists per user."
  (:require
   [bookshelf.db :as db]))

(defn list-public-lists
  "GET /api/reading-lists — list all public reading lists."
  [env]
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
    {:status 200
     :headers {}
     :body result}))

(defn get-reading-list
  "GET /api/reading-lists/:id — fetch a reading list with book details."
  [env]
  (let [rl (:reading-list env)
        user (get @db/users (:user-id rl))
        current-user (:current-user env)]
    ;; Check access: public, or belongs to current user
    (if (and (not (:public? rl))
             (or (nil? current-user)
                 (not= (:id current-user) (:user-id rl))))
      {:status 404
       :headers {}
       :body {:error "Reading list not found"}}
      (let [books (mapv (fn [bid]
                          (when-let [b (get @db/books bid)]
                            (select-keys b [:id :title :author-id :price :genres])))
                        (:book-ids rl))
            books (filterv some? books)]
        {:status 200
         :headers {}
         :body (assoc rl :username (:username user)
                         :books books)}))))

(defn create-reading-list
  "POST /api/reading-lists — create a new reading list (requires auth)."
  [env]
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
    {:status 201
     :headers {"Location" (str "/api/reading-lists/" id)}
     :body rl}))

(defn update-reading-list
  "PUT /api/reading-lists/:id — update a reading list (owner only)."
  [env]
  (let [rl (:reading-list env)
        user (:current-user env)
        params (:body-params env)]
    (if (not= (:id user) (:user-id rl))
      {:status 403
       :headers {}
       :body {:error "You can only edit your own reading lists"}}
      (let [updated (merge rl (select-keys params [:name :public? :book-ids]))]
        (swap! db/reading-lists assoc (:id rl) updated)
        {:status 200
         :headers {}
         :body updated}))))

(defn delete-reading-list
  "DELETE /api/reading-lists/:id — delete a reading list."
  [env]
  (let [rl (:reading-list env)
        user (:current-user env)]
    (if (and (not= (:id user) (:user-id rl))
             (not= :admin (:role user)))
      {:status 403
       :headers {}
       :body {:error "Insufficient permissions"}}
      (do (swap! db/reading-lists dissoc (:id rl))
          {:status 200
           :headers {}
           :body {:message "Reading list deleted" :id (:id rl)}}))))

(defn add-book-to-list
  "POST /api/reading-lists/:id/books — add a book to a reading list."
  [env]
  (let [rl (:reading-list env)
        user (:current-user env)
        params (:body-params env)
        book-id (:book-id params)]
    (cond
      (not= (:id user) (:user-id rl))
      {:status 403
       :headers {}
       :body {:error "You can only modify your own reading lists"}}

      (nil? (get @db/books book-id))
      {:status 404
       :headers {}
       :body {:error "Book not found"}}

      (some #{book-id} (:book-ids rl))
      {:status 409
       :headers {}
       :body {:error "Book already in list"}}

      :else
      (let [updated (update rl :book-ids conj book-id)]
        (swap! db/reading-lists assoc (:id rl) updated)
        {:status 200
         :headers {}
         :body updated}))))

(defn remove-book-from-list
  "DELETE /api/reading-lists/:id/books/:book-id — remove a book from a reading list."
  [env]
  (let [rl (:reading-list env)
        user (:current-user env)
        book-id-str (get-in env [:path-params-values "book_id"])
        book-id (when book-id-str (Integer/parseInt book-id-str))]
    (if (not= (:id user) (:user-id rl))
      {:status 403
       :headers {}
       :body {:error "You can only modify your own reading lists"}}
      (let [updated (update rl :book-ids #(vec (remove #{book-id} %)))]
        (swap! db/reading-lists assoc (:id rl) updated)
        {:status 200
         :headers {}
         :body updated}))))
