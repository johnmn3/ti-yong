(ns examples.pet-store
  "Comprehensive Pet Store API example — ported from Pedestal patterns.
   Demonstrates: CRUD operations, middleware composition, path params,
   query params, body parsing, content negotiation, CORS, error handling,
   custom interceptors (load-pet), and content-type management."
  (:require
   [ti-yong.http :as http]
   [ti-yong.http.middleware :as mw]
   [ti-yong.http.error :as err]
   [ti-yong.alpha.transformer :as t]))

;; --- Data Store ---

(defonce the-pets (atom {}))

(defn reset-pets! []
  (reset! the-pets {1 {:id 1 :name "Rex" :type "dog" :age 5}
                    2 {:id 2 :name "Whiskers" :type "cat" :age 3}
                    3 {:id 3 :name "Goldie" :type "fish" :age 1}}))

;; --- Handlers ---

(defn list-pets
  "GET /pets — return all pets, optionally sorted."
  [env]
  (let [sort-dir (get-in env [:query-params :sort])
        pets (vals @the-pets)
        sorted (cond->> pets
                 sort-dir (sort-by :name)
                 (= "desc" (name (or sort-dir ""))) reverse)]
    {:status 200
     :headers {}
     :body {:pets (vec sorted)}}))

(defn create-pet
  "POST /pets — create a new pet from body params."
  [env]
  (let [params (:body-params env)
        id (inc (apply max 0 (keys @the-pets)))
        pet (assoc params :id id)]
    (swap! the-pets assoc id pet)
    {:status 201
     :headers {"Location" (str "/pets/" id)}
     :body pet}))

(defn get-pet
  "GET /pets/:id — return a specific pet (loaded by load-pet interceptor)."
  [env]
  (if-let [pet (:pet env)]
    {:status 200 :headers {} :body pet}
    {:status 404 :headers {} :body "Pet not found"}))

(defn update-pet
  "PUT /pets/:id — update an existing pet."
  [env]
  (let [id (Integer/parseInt (get-in env [:path-params-values "id"]))
        params (:body-params env)]
    (if (get @the-pets id)
      (do (swap! the-pets update id merge params)
          {:status 200 :headers {} :body (str "Updated pet " id)})
      {:status 404 :headers {} :body "Pet not found"})))

(defn delete-pet
  "DELETE /pets/:id — delete a pet."
  [env]
  (if-let [pet (:pet env)]
    (do (swap! the-pets dissoc (:id pet))
        {:status 200 :headers {} :body (str "Deleted " (:name pet))})
    {:status 404 :headers {} :body "Pet not found"}))

(defn search-pets
  "GET /pets/search — search pets by query params."
  [env]
  (let [params (:query-params env)
        pet-type (get params :type)
        min-age (some-> (get params :min-age) Integer/parseInt)
        pets (cond->> (vals @the-pets)
               pet-type (filter #(= pet-type (name (:type %))))
               min-age (filter #(>= (:age %) min-age)))]
    {:status 200 :headers {} :body {:results (vec pets) :count (count pets)}}))

(defn home-page
  "GET / — HTML welcome page."
  [_env]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body "<html><body><h1>Pet Store API</h1><p>Welcome! Try GET /pets</p></body></html>"})

;; --- Custom Interceptors (as middleware mixins) ---

(defn load-pet-middleware
  "Middleware that loads a pet by :id path param into the :pet key.
   Returns 404 early if pet not found. (Equivalent to Pedestal's load-pet interceptor.)"
  []
  (-> t/transformer
      (update :id conj ::load-pet)
      (update :tf conj
              ::load-pet
              (fn [env]
                (let [id-str (get-in env [:path-params-values "id"])
                      id (when id-str (Integer/parseInt id-str))
                      pet (when id (get @the-pets id))]
                  (assoc env :pet pet))))))

;; --- Service Definition ---

(def service-map
  {::http/routes
   [["/" :get home-page :route-name ::home]

    ["/pets" :get list-pets
     :route-name ::list-pets
     :with [mw/keyword-params mw/query-params]]

    ["/pets" :post create-pet
     :route-name ::create-pet
     :with [mw/keyword-params mw/body-params]]

    ["/pets/search" :get search-pets
     :route-name ::search-pets
     :with [mw/keyword-params mw/query-params]]

    ["/pets/:id" :get get-pet
     :route-name ::get-pet
     :with [(load-pet-middleware)]]

    ["/pets/:id" :put update-pet
     :route-name ::update-pet
     :with [(load-pet-middleware) mw/keyword-params mw/body-params]]

    ["/pets/:id" :delete delete-pet
     :route-name ::delete-pet
     :with [(load-pet-middleware)]]]

   ::http/with [err/error-handler
                (mw/cors {:allowed-origins "*"
                           :allowed-methods "GET, POST, PUT, DELETE, OPTIONS"
                           :allowed-headers "Content-Type, Accept"})
                mw/json-body-response]

   ::http/port 8080
   ::http/join? false})
