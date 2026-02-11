(ns bookshelf.handlers.pages
  "HTML page handlers — landing page, API docs, and health check.
   Pages use different response types (HTML vs JSON), so each handler
   defines its own response middleware rather than sharing a ns transformer.
   All request data lives on :ctx."
  (:require
   [bookshelf.db :as db]
   [hearth.alpha.middleware :as mw]
   [ti-yong.alpha.transformer :as t]))

;; --- Handlers ---

(def home-page
  (-> t/transformer
      (assoc :doc "HTML landing page with links to API documentation.")
      (update :id conj ::home-page)
      (update :with conj mw/html-body)
      (update :tf conj
              ::home-page
              (fn [env]
                (update env :res assoc
                        :body (str "<html><head><title>BookShelf API</title></head>"
                                   "<body><h1>BookShelf API</h1>"
                                   "<p>Welcome to the BookShelf API.</p>"
                                   "<ul>"
                                   "<li><a href=\"/api/books\">Books</a></li>"
                                   "<li><a href=\"/api/authors\">Authors</a></li>"
                                   "<li><a href=\"/api/reading-lists\">Reading Lists</a></li>"
                                   "<li><a href=\"/api-info\">API Info</a></li>"
                                   "<li><a href=\"/health\">Health Check</a></li>"
                                   "</ul></body></html>"))))))

(def health-check
  (-> t/transformer
      (assoc :doc "Health check endpoint. Returns 200 with status and entity counts.")
      (update :id conj ::health-check)
      (update :with conj mw/json-body-response)
      (update :tf conj
              ::health-check
              (fn [env]
                (update env :res assoc
                        :body {:status "ok"
                               :books (count @db/books)
                               :authors (count @db/authors)
                               :users (count @db/users)})))))

(def api-info
  (-> t/transformer
      (assoc :doc "API information endpoint. Lists available endpoint groups and version.")
      (update :id conj ::api-info)
      (update :with conj mw/json-body-response)
      (update :tf conj
              ::api-info
              (fn [env]
                (update env :res assoc
                        :body {:name "BookShelf API"
                               :version "1.0.0"
                               :endpoints ["/api/books"
                                           "/api/authors"
                                           "/api/reviews"
                                           "/api/users"
                                           "/api/reading-lists"
                                           "/api/admin"]})))))
