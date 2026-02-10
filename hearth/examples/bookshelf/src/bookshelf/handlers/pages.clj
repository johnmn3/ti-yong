(ns bookshelf.handlers.pages
  "HTML page handlers — landing page, API docs, and health check.
   Demonstrates: HTML responses, content types, static content serving.
   All handlers are transformers, composable with middleware after definition."
  (:require
   [bookshelf.db :as db]
   [ti-yong.alpha.transformer :as t]))

(def home-page
  (-> t/transformer
      (update :id conj ::home-page)
      (update :tf conj
              ::home-page
              (fn [env]
                (update env :res assoc
                        :headers {"Content-Type" "text/html"}
                        :body (str
                               "<!DOCTYPE html><html><head><title>BookShelf API</title>"
                               "<style>body{font-family:system-ui;max-width:800px;margin:40px auto;padding:0 20px;line-height:1.6}"
                               "h1{color:#2d3748}h2{color:#4a5568;border-bottom:2px solid #e2e8f0;padding-bottom:8px}"
                               "code{background:#f7fafc;padding:2px 6px;border-radius:3px;font-size:0.9em}"
                               "pre{background:#2d3748;color:#e2e8f0;padding:16px;border-radius:8px;overflow-x:auto}"
                               "a{color:#3182ce}table{border-collapse:collapse;width:100%}"
                               "th,td{text-align:left;padding:8px;border-bottom:1px solid #e2e8f0}"
                               "th{background:#f7fafc}</style></head><body>"
                               "<h1>BookShelf API</h1>"
                               "<p>A comprehensive example application built with <strong>hearth.alpha</strong>, "
                               "demonstrating transformers as HTTP middleware.</p>"
                               "<h2>Quick Stats</h2>"
                               "<table>"
                               "<tr><td>Books</td><td>" (count @db/books) "</td></tr>"
                               "<tr><td>Authors</td><td>" (count @db/authors) "</td></tr>"
                               "<tr><td>Users</td><td>" (count @db/users) "</td></tr>"
                               "<tr><td>Reviews</td><td>" (count @db/reviews) "</td></tr>"
                               "</table>"
                               "<h2>API Endpoints</h2>"
                               "<pre>"
                               "# Books\n"
                               "GET    /api/books              — list books (paginated, filterable)\n"
                               "GET    /api/books/:id          — get book details\n"
                               "POST   /api/books              — create book (admin)\n"
                               "PUT    /api/books/:id          — update book (admin)\n"
                               "PATCH  /api/books/:id          — partial update (admin)\n"
                               "DELETE /api/books/:id          — delete book (admin)\n"
                               "GET    /api/books/search       — search books\n"
                               "GET    /api/books/:id/stock    — check stock\n"
                               "POST   /api/books/:id/stock    — update stock (admin)\n"
                               "GET    /api/books/:id/reviews  — book reviews\n"
                               "POST   /api/books/:id/reviews  — submit review (auth)\n\n"
                               "# Authors\n"
                               "GET    /api/authors            — list authors\n"
                               "GET    /api/authors/:id        — get author\n"
                               "POST   /api/authors            — create author (admin)\n"
                               "PUT    /api/authors/:id        — update author (admin)\n"
                               "DELETE /api/authors/:id        — delete author (admin)\n"
                               "GET    /api/authors/:id/books  — author bibliography\n\n"
                               "# Reviews\n"
                               "GET    /api/reviews/:id        — get review\n"
                               "PUT    /api/reviews/:id        — update review (owner)\n"
                               "DELETE /api/reviews/:id        — delete review (owner/admin)\n\n"
                               "# Users\n"
                               "GET    /api/users              — list users (admin)\n"
                               "GET    /api/users/:id          — user profile\n"
                               "GET    /api/users/:id/reviews  — user's reviews\n"
                               "GET    /api/profile            — my profile (auth)\n"
                               "PUT    /api/profile            — update profile (auth)\n\n"
                               "# Auth\n"
                               "POST   /api/auth/login         — login\n"
                               "POST   /api/auth/logout        — logout\n"
                               "POST   /api/auth/register      — register\n"
                               "GET    /api/auth/whoami        — check auth status\n\n"
                               "# Reading Lists\n"
                               "GET    /api/reading-lists      — public lists\n"
                               "GET    /api/reading-lists/:id  — get list\n"
                               "POST   /api/reading-lists      — create list (auth)\n"
                               "PUT    /api/reading-lists/:id  — update list (owner)\n"
                               "DELETE /api/reading-lists/:id  — delete list (owner/admin)\n"
                               "POST   /api/reading-lists/:id/books         — add book\n"
                               "DELETE /api/reading-lists/:id/books/:book_id — remove book\n\n"
                               "# Admin\n"
                               "GET    /api/admin/dashboard    — admin dashboard\n"
                               "GET    /api/admin/export/catalog  — export catalog\n"
                               "GET    /api/admin/export/users    — export users\n"
                               "POST   /api/admin/bulk/prices     — bulk price update\n"
                               "GET    /api/admin/request-log     — view request log\n"
                               "DELETE /api/admin/request-log     — clear request log\n\n"
                               "# Real-time\n"
                               "GET    /api/notifications/stream  — SSE notifications\n"
                               "GET    /api/activity/stream       — SSE activity feed\n"
                               "GET    /ws/chat                   — WebSocket chat\n"
                               "GET    /ws/books/updates          — WebSocket book updates\n"
                               "</pre>"
                               "<h2>Authentication</h2>"
                               "<p>Use session cookies (POST /api/auth/login) or API key "
                               "(Authorization: Bearer bk-live-xxx).</p>"
                               "<p>Test credentials: <code>alice</code> / <code>password-1</code> (admin), "
                               "<code>bob</code> / <code>password-2</code> (user)</p>"
                               "<h2>Powered by</h2>"
                               "<p><a href=\"https://github.com/johnmn3/ti-yong\">ti-yong</a> transformers + "
                               "<strong>hearth.alpha</strong> HTTP framework</p>"
                               "</body></html>"))))))

(def health
  (-> t/transformer
      (update :id conj ::health)
      (update :tf conj
              ::health
              (fn [env]
                (update env :res assoc
                        :body {:status "ok"
                               :uptime-ms (- (System/currentTimeMillis)
                                             (Long/parseLong
                                              (or (System/getProperty "bookshelf.start-time") "0")))})))))

(def api-info
  (-> t/transformer
      (update :id conj ::api-info)
      (update :tf conj
              ::api-info
              (fn [env]
                (update env :res assoc
                        :body {:name "BookShelf API"
                               :version "1.0.0"
                               :framework "hearth.alpha"
                               :engine "ti-yong transformers"})))))
