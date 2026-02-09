(ns bookshelf.server
  "BookShelf server entry point.

   Usage:
     # Start with default settings:
     clojure -M:dev

     # Or from the REPL:
     (require '[bookshelf.server :as server])
     (def srv (server/start!))
     ;; ... make requests ...
     (server/stop! srv)
     (server/restart! srv)"
  (:require
   [bookshelf.service :as svc]
   [bookshelf.db :as db]
   [hearth.alpha :as http]))

(defonce ^:private server (atom nil))

(defn start!
  "Start the BookShelf server.
   Returns the server instance."
  ([]
   (start! svc/service-map))
  ([service-map]
   (db/seed!)
   (System/setProperty "bookshelf.start-time" (str (System/currentTimeMillis)))
   (let [srv (-> service-map
                 http/create-server
                 http/start)]
     (reset! server srv)
     (println (str "\n"
                   "  ____              _    ____  _          _  __\n"
                   " | __ )  ___   ___ | | _/ ___|| |__   ___| |/ _|\n"
                   " |  _ \\ / _ \\ / _ \\| |/ \\___ \\| '_ \\ / _ \\ | |_\n"
                   " | |_) | (_) | (_) |   < ___) | | | |  __/ |  _|\n"
                   " |____/ \\___/ \\___/|_|\\_\\____/|_| |_|\\___|_|_|\n"
                   "\n"
                   "  Powered by hearth.alpha + ti-yong transformers\n"
                   "  Server running on http://localhost:" (::http/port service-map 8080) "\n"
                   "  " (count @db/books) " books, " (count @db/authors) " authors, "
                   (count @db/users) " users loaded\n"))
     srv)))

(defn stop!
  "Stop the BookShelf server."
  ([]
   (when-let [srv @server]
     (stop! srv)))
  ([srv]
   (http/stop srv)
   (reset! server nil)
   (println "BookShelf server stopped.")))

(defn restart!
  "Restart the server with fresh data."
  ([]
   (stop!)
   (start!))
  ([srv]
   (stop! srv)
   (start!)))

(defn -main
  "Main entry point for running the server."
  [& _args]
  (start!)
  ;; Block the main thread
  (.join (Thread/currentThread)))
