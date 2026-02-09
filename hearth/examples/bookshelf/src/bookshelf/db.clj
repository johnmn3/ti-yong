(ns bookshelf.db
  "In-memory database for the BookShelf example.
   Stores books, authors, reviews, users, and reading lists.
   Uses atoms for simplicity — a real app would use a database.")

;; --- ID generation ---

(defonce ^:private id-counter (atom 1000))

(defn next-id [] (swap! id-counter inc))

;; --- Stores ---

(defonce users (atom {}))
(defonce authors (atom {}))
(defonce books (atom {}))
(defonce reviews (atom {}))
(defonce reading-lists (atom {}))
(defonce notifications (atom []))
(defonce api-keys (atom {}))

;; --- Seed Data ---

(defn seed!
  "Reset all stores and load sample data."
  []
  (reset! id-counter 1000)

  ;; Users
  (reset! users
    {1 {:id 1 :username "alice" :email "alice@example.com" :role :admin
        :password-hash "hashed-password-1" :created-at "2024-01-15T10:00:00Z"}
     2 {:id 2 :username "bob" :email "bob@example.com" :role :user
        :password-hash "hashed-password-2" :created-at "2024-02-01T14:30:00Z"}
     3 {:id 3 :username "carol" :email "carol@example.com" :role :user
        :password-hash "hashed-password-3" :created-at "2024-03-10T09:15:00Z"}
     4 {:id 4 :username "dave" :email "dave@example.com" :role :moderator
        :password-hash "hashed-password-4" :created-at "2024-04-05T16:00:00Z"}})

  ;; Authors
  (reset! authors
    {1 {:id 1 :name "George Orwell" :bio "English novelist and essayist."
        :born "1903-06-25" :died "1950-01-21"
        :nationality "British" :genres ["dystopian" "political fiction"]}
     2 {:id 2 :name "Ursula K. Le Guin" :bio "American author of science fiction and fantasy."
        :born "1929-10-21" :died "2018-01-22"
        :nationality "American" :genres ["science fiction" "fantasy"]}
     3 {:id 3 :name "Octavia Butler" :bio "American science fiction writer."
        :born "1947-06-22" :died "2006-02-24"
        :nationality "American" :genres ["science fiction" "afrofuturism"]}
     4 {:id 4 :name "Liu Cixin" :bio "Chinese science fiction writer."
        :born "1963-06-23"
        :nationality "Chinese" :genres ["hard science fiction"]}
     5 {:id 5 :name "Stanislaw Lem" :bio "Polish writer of science fiction and philosophy."
        :born "1921-09-12" :died "2006-03-27"
        :nationality "Polish" :genres ["science fiction" "philosophy"]}})

  ;; Books
  (reset! books
    {1 {:id 1 :title "1984" :author-id 1 :isbn "978-0451524935"
        :published "1949-06-08" :pages 328 :language "English"
        :genres ["dystopian" "political fiction"]
        :summary "A dystopian novel set in a totalitarian society."
        :cover-url "/static/covers/1984.jpg"
        :price 12.99 :in-stock true :stock-count 42}
     2 {:id 2 :title "The Left Hand of Darkness" :author-id 2 :isbn "978-0441478125"
        :published "1969-03-01" :pages 304 :language "English"
        :genres ["science fiction" "feminist fiction"]
        :summary "A groundbreaking exploration of gender and politics on an alien world."
        :cover-url "/static/covers/left-hand.jpg"
        :price 14.99 :in-stock true :stock-count 28}
     3 {:id 3 :title "Kindred" :author-id 3 :isbn "978-0807083697"
        :published "1979-06-01" :pages 287 :language "English"
        :genres ["science fiction" "historical fiction"]
        :summary "A modern Black woman is pulled back in time to the antebellum South."
        :cover-url "/static/covers/kindred.jpg"
        :price 13.99 :in-stock true :stock-count 35}
     4 {:id 4 :title "The Three-Body Problem" :author-id 4 :isbn "978-0765382030"
        :published "2008-01-01" :pages 400 :language "English"
        :genres ["hard science fiction"]
        :summary "Set against the backdrop of China's Cultural Revolution, a secret military project sends signals into space."
        :cover-url "/static/covers/three-body.jpg"
        :price 15.99 :in-stock true :stock-count 20}
     5 {:id 5 :title "Solaris" :author-id 5 :isbn "978-0156027601"
        :published "1961-01-01" :pages 204 :language "English"
        :genres ["science fiction" "philosophy"]
        :summary "Scientists study a mysterious ocean-covered planet that appears to be sentient."
        :cover-url "/static/covers/solaris.jpg"
        :price 11.99 :in-stock true :stock-count 15}
     6 {:id 6 :title "Animal Farm" :author-id 1 :isbn "978-0451526342"
        :published "1945-08-17" :pages 112 :language "English"
        :genres ["political satire" "allegory"]
        :summary "A farm is taken over by its overworked animals."
        :cover-url "/static/covers/animal-farm.jpg"
        :price 9.99 :in-stock true :stock-count 50}
     7 {:id 7 :title "The Dispossessed" :author-id 2 :isbn "978-0061054884"
        :published "1974-05-01" :pages 387 :language "English"
        :genres ["science fiction" "utopian fiction"]
        :summary "A physicist tries to tear down the walls of hatred that separate worlds."
        :cover-url "/static/covers/dispossessed.jpg"
        :price 14.99 :in-stock false :stock-count 0}
     8 {:id 8 :title "Parable of the Sower" :author-id 3 :isbn "978-1538732182"
        :published "1993-10-01" :pages 345 :language "English"
        :genres ["science fiction" "dystopian"]
        :summary "In 2024 California, society has largely collapsed due to climate change."
        :cover-url "/static/covers/sower.jpg"
        :price 14.99 :in-stock true :stock-count 22}
     9 {:id 9 :title "The Dark Forest" :author-id 4 :isbn "978-0765386694"
        :published "2008-05-01" :pages 512 :language "English"
        :genres ["hard science fiction"]
        :summary "Earth reels from the revelation of a coming alien invasion."
        :cover-url "/static/covers/dark-forest.jpg"
        :price 15.99 :in-stock true :stock-count 18}
     10 {:id 10 :title "His Master's Voice" :author-id 5 :isbn "978-0156403009"
         :published "1968-01-01" :pages 199 :language "English"
         :genres ["science fiction" "philosophy"]
         :summary "Scientists attempt to decode a signal from outer space."
         :cover-url "/static/covers/masters-voice.jpg"
         :price 12.99 :in-stock true :stock-count 8}})

  ;; Reviews
  (reset! reviews
    {1 {:id 1 :book-id 1 :user-id 2 :rating 5 :title "A timeless warning"
        :body "Orwell's masterpiece remains chillingly relevant." :created-at "2024-02-15T10:00:00Z"}
     2 {:id 2 :book-id 1 :user-id 3 :rating 4 :title "Powerful but bleak"
        :body "Brilliantly written, though deeply unsettling." :created-at "2024-03-20T14:00:00Z"}
     3 {:id 3 :book-id 2 :user-id 2 :rating 5 :title "Revolutionary"
        :body "Changed how I think about gender and society." :created-at "2024-02-28T11:00:00Z"}
     4 {:id 4 :book-id 3 :user-id 3 :rating 5 :title "Unforgettable"
        :body "Butler's exploration of power and race through time travel is unmatched." :created-at "2024-04-01T09:00:00Z"}
     5 {:id 5 :book-id 4 :user-id 2 :rating 4 :title "Mind-bending"
        :body "Hard SF at its finest, though the cultural references take getting used to." :created-at "2024-03-15T16:00:00Z"}
     6 {:id 6 :book-id 5 :user-id 3 :rating 5 :title "Philosophical SF at its best"
        :body "Lem asks questions that no one else dares to." :created-at "2024-04-10T12:00:00Z"}})

  ;; Reading lists
  (reset! reading-lists
    {1 {:id 1 :user-id 2 :name "Sci-Fi Essentials" :public? true
        :book-ids [1 2 3 4 5] :created-at "2024-02-01T10:00:00Z"}
     2 {:id 2 :user-id 3 :name "Feminist SF" :public? true
        :book-ids [2 3 8] :created-at "2024-03-15T10:00:00Z"}
     3 {:id 3 :user-id 2 :name "To Read Next" :public? false
        :book-ids [8 9 10] :created-at "2024-04-01T10:00:00Z"}})

  ;; API keys for external integrations
  (reset! api-keys
    {"bk-live-abc123" {:user-id 1 :scope :admin :created-at "2024-01-15"}
     "bk-live-def456" {:user-id 2 :scope :read :created-at "2024-02-01"}})

  ;; Notifications
  (reset! notifications [])

  :seeded)

;; --- Query helpers ---

(defn find-user-by-username [username]
  (first (filter #(= username (:username %)) (vals @users))))

(defn find-books-by-author [author-id]
  (filter #(= author-id (:author-id %)) (vals @books)))

(defn find-reviews-for-book [book-id]
  (filter #(= book-id (:book-id %)) (vals @reviews)))

(defn find-reviews-by-user [user-id]
  (filter #(= user-id (:user-id %)) (vals @reviews)))

(defn find-reading-lists-for-user [user-id]
  (filter #(= user-id (:user-id %)) (vals @reading-lists)))

(defn search-books
  "Search books by title, author name, genre, or ISBN."
  [{:keys [q genre author min-price max-price in-stock]}]
  (let [all-books (vals @books)
        q-lower (when q (clojure.string/lower-case q))]
    (cond->> all-books
      q-lower (filter (fn [b]
                        (or (clojure.string/includes?
                             (clojure.string/lower-case (:title b)) q-lower)
                            (clojure.string/includes?
                             (clojure.string/lower-case (:isbn b "")) q-lower)
                            (when-let [a (get @authors (:author-id b))]
                              (clojure.string/includes?
                               (clojure.string/lower-case (:name a)) q-lower)))))
      genre (filter #(some #{genre} (:genres %)))
      author (filter #(= (Integer/parseInt (str author)) (:author-id %)))
      min-price (filter #(>= (:price %) (Double/parseDouble (str min-price))))
      max-price (filter #(<= (:price %) (Double/parseDouble (str max-price))))
      (= "true" (str in-stock)) (filter :in-stock))))

(defn paginate
  "Apply offset/limit pagination to a collection."
  [coll {:keys [page per-page] :or {page 1 per-page 10}}]
  (let [page (if (string? page) (Integer/parseInt page) page)
        per-page (if (string? per-page) (Integer/parseInt per-page) per-page)
        total (count coll)
        offset (* (dec page) per-page)
        items (->> coll (drop offset) (take per-page) vec)]
    {:items items
     :page page
     :per-page per-page
     :total total
     :total-pages (int (Math/ceil (/ total (double per-page))))}))

;; --- Mutation helpers ---

(defn add-notification! [notification]
  (swap! notifications conj
         (assoc notification
                :id (next-id)
                :timestamp (str (java.time.Instant/now)))))
