(ns babashka.http-client-test
  (:require
   [babashka.fs :as fs]
   [babashka.http-client.internal.version :as iv]
   [borkdude.deflet :refer [deflet]]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [org.httpkit.server :as server])
  (:import
   (clojure.lang ExceptionInfo)))

(def !server (atom nil))

(defn run-server []
  (let [server
        (server/run-server
         (fn [{:keys [uri body] :as req}]
           (let [status (parse-long (subs uri 1))
                 json? (some-> req :headers (get "accept") (str/includes? "application/json"))]
             (case status
               200 (let [body (if json?
                                (json/generate-string {:code 200})
                                (if body body
                                    "200 OK"))]
                     {:status 200
                      :body body})
               404 {:status 404
                    :body "404 Not Found"}
               302 {:status 302
                    :headers {"location" "/200"}}
               {:status status
                :body (str status)})))
         {:port 12233
          :legacy-return-value? false})]
    (reset! !server server)))

(defn stop-server []
  (server/server-stop! @!server))

(defn my-test-fixture [f]
  (println "Spinning up server")
  (run-server)
  (f)
  (println "Tearing down server")
  (stop-server))

(clojure.test/use-fixtures :once my-test-fixture)

;; reload client so we're not testing the built-in namespace in bb

(require '[babashka.http-client.interceptors :as interceptors] :reload
         '[babashka.http-client :as http] :reload)

(defmethod clojure.test/report :begin-test-var [m]
  (println "===" (-> m :var meta :name))
  (println))

(deftest get-test
  (is (str/includes? (:body (http/get "http://localhost:12233/200"))
                     "200"))
  (is (= 200
         (-> (http/get "http://localhost:12233/200"
                       {:headers {"Accept" "application/json"}})
             :body
             (json/parse-string true)
             :code)))
  (testing "query params"
    (is (= {:foo1 "bar1" :foo2 "bar2" :foo3 "bar3" :not-string "42" :namespaced/key "foo"}
           (-> (http/get "https://postman-echo.com/get" {:query-params {"foo1" "bar1" "foo2" "bar2" :foo3 "bar3" :not-string 42 :namespaced/key "foo"}})
               :body
               (json/parse-string true)
               :args)))
    (is (= {:foo1 ["bar1" "bar2"]}
           (-> (http/get "https://postman-echo.com/get" {:query-params {"foo1" ["bar1" "bar2"]}})
               :body
               (json/parse-string true)
               :args))))
  (testing "can pass uri"
    (is (= 200
           (-> (http/get (java.net.URI. "http://localhost:12233/200")
                         {:headers {"Accept" "application/json"}})
               :body
               (json/parse-string true)
               :code)))))

(deftest delete-test
  (is (= 200 (:status (http/delete "https://postman-echo.com/delete")))))

(deftest head-test
  (is (= 200 (:status (http/head "https://postman-echo.com/head"))))
  ;; github apparently sets encoding despite HEAD request, which returns empty
  ;; body and then causes GZIP error
  (is (= 200 (:status (http/head "https://github.com/babashka/http-client")))))

(deftest post-test
  (is (subs (:body (http/post "https://postman-echo.com/post"))
            0 10))
  (is (str/includes?
       (:body (http/post "https://postman-echo.com/post"
                         {:body "From Clojure"}))
       "From Clojure"))
  (testing "text file body"
    (is (str/includes?
         (:body (http/post "https://postman-echo.com/post"
                           {:body (io/file "README.md")}))
         "babashka")))
  (testing "binary file body"
    (let [file-bytes (fs/read-all-bytes (io/file "icon.png"))
          body1 (:body (http/post "http://localhost:12233/200"
                                  {:body (io/file "icon.png")
                                   :as :bytes}))
          body2 (:body (http/post "http://localhost:12233/200"
                                  {:body (fs/path "icon.png")
                                   :as :bytes}))]
      (is (java.util.Arrays/equals file-bytes body1))
      (is (java.util.Arrays/equals file-bytes body2))))
  (testing "JSON body"
    (let [response (http/post "https://postman-echo.com/post"
                              {:headers {"Content-Type" "application/json"}
                               :body (json/generate-string {:a "foo"})})
          body (:body response)
          body (json/parse-string body true)
          json (:json body)]
      (is (= {:a "foo"} json))))
  (testing "stream body"
    (is (str/includes?
         (:body (http/post "https://postman-echo.com/post"
                           {:body (io/input-stream "README.md")}))
         "babashka")))
  (testing "form-params"
    (let [body (:body (http/post "https://postman-echo.com/post"
                                 {:form-params {"name" "Michiel Borkent"
                                                :location "NL"
                                                :this-isnt-a-string 42}}))
          body (json/parse-string body true)
          headers (:headers body)
          content-type (:content-type headers)]
      (is (= "application/x-www-form-urlencoded" content-type))))
  ;; TODO:
  #_(testing "multipart"
      (testing "posting file"
        (let [tmp-file (java.io.File/createTempFile "foo" "bar")
              _ (spit tmp-file "Michiel Borkent")
              _ (.deleteOnExit tmp-file)
              body (:body (client/post "https://postman-echo.com/post"
                                       {:multipart [{:name "file"
                                                     :content (io/file tmp-file)}
                                                    {:name "filename" :content (.getPath tmp-file)}
                                                    ["file2" (io/file tmp-file)]]}))
              body (json/parse-string body true)
              headers (:headers body)
              content-type (:content-type headers)]
          (is (str/starts-with? content-type "multipart/form-data"))
          (is (:files body))
          (is (str/includes? (-> body :form :filename) "foo"))
          (prn body)))))

(deftest patch-test
  (is (str/includes?
       (:body (http/patch "https://postman-echo.com/patch"
                          {:body "hello"}))
       "hello")))

(deftest put-test
  (is (str/includes?
       (:body (http/put "https://postman-echo.com/put"
                        {:body "hello"}))
       "hello")))

(deftest basic-auth-test
  (is (re-find #"authenticated.*true"
               (:body
                (http/get "https://postman-echo.com/basic-auth"
                          {:basic-auth ["postman" "password"]})))))

(deftest get-response-object-test
  (let [response (http/get "http://localhost:12233/200")]
    (is (map? response))
    (is (= 200 (:status response)))
    (is (= "200 OK" (:body response)))
    (is (string? (get-in response [:headers "server"]))))

  (testing "response object as stream"
    (let [response (http/get "http://localhost:12233/200" {:as :stream})]
      (is (map? response))
      (is (= 200 (:status response)))
      (is (instance? java.io.InputStream (:body response)))
      (is (= "200 OK" (slurp (:body response))))))

  (testing "response object with following redirect"
    (let [response (http/get "https://httpbin.org/redirect-to?url=https://www.httpbin.org")]
      (is (map? response))
      (is (= 200 (:status response)))))

  (testing "response object without fully following redirects"
    ;; (System/getProperty "jdk.httpclient.redirects.retrylimit" "0")
    (let [response (http/get "https://httpbin.org/redirect-to?url=https://www.httpbin.org"
                             {:client (http/client {:follow-redirects :never})})]
      (is (map? response))
      (is (= 302 (:status response)))
      (is (= "" (:body response)))
      (is (= "https://www.httpbin.org" (get-in response [:headers "location"])))
      (is (empty? (:redirects response))))))

(deftest accept-header-test
  (is (= 200
         (-> (http/get "http://localhost:12233/200"
                       {:accept :json})
             :body
             (json/parse-string true)
             :code))))

(deftest url-encode-query-params-test
  (is (= {"my query param?" "hello there"}
         (-> (http/get "https://postman-echo.com/get" {:query-params {"my query param?" "hello there"}})
             :body
             (json/parse-string)
             (get "args")))))

(deftest request-uri-test
  (is (= 200 (:status (http/head "http://localhost:12233/200"))))
  (is (= 200 (:status (http/head {:scheme "http"
                                  :host "localhost"
                                  :port 12233
                                  :path "/200"}))))
  (is (= 200 (:status (http/head (java.net.URI. "http://localhost:12233/200"))))))

;; (deftest download-binary-file-as-stream-test
;;   (testing "download image"
;;     (let [tmp-file (java.io.File/createTempFile "icon" ".png")]
;;       (.deleteOnExit tmp-file)
;;       (io/copy (:body (client/get "https://github.com/babashka/babashka/raw/master/logo/icon.png" {:as :stream}))
;;                tmp-file)
;;       (is (= (.length (io/file "test" "icon.png"))
;;              (.length tmp-file)))))
;;   (testing "download image with response headers"
;;     (let [tmp-file (java.io.File/createTempFile "icon" ".png")]
;;       (.deleteOnExit tmp-file)
;;       (let [resp (client/get "https://github.com/babashka/babashka/raw/master/logo/icon.png" {:as :stream})]
;;         (is (= 200 (:status resp)))
;;         (io/copy (:body resp) tmp-file))
;;       (is (= (.length (io/file "test" "icon.png"))
;;              (.length tmp-file)))))
;;   (testing "direct bytes response"
;;     (let [tmp-file (java.io.File/createTempFile "icon" ".png")]
;;       (.deleteOnExit tmp-file)
;;       (let [resp (client/get "https://github.com/babashka/babashka/raw/master/logo/icon.png" {:as :bytes})]
;;         (is (= 200 (:status resp)))
;;         (is (= (Class/forName "[B") (class (:body resp))))
;;         (io/copy (:body resp) tmp-file)
;;         (is (= (count (:body resp))
;;                (.length (io/file "test" "icon.png"))
;;                (.length tmp-file)))))))

(deftest stream-test
  ;; This test aims to test what is tested manually as follows:
  ;; - from https://github.com/enkot/SSE-Fake-Server: npm install sse-fake-server
  ;; - start with: PORT=1668 node fakeserver.js
  ;; - ./bb '(let [resp (client/get "http://localhost:1668/stream" {:as :stream}) body (:body resp) proc (:process resp)] (prn (take 1 (line-seq (io/reader body)))) (.destroy proc))'
  ;; ("data: Stream Hello!")
  (let [server (java.net.ServerSocket. 1668)
        port (.getLocalPort server)]
    (future (try (with-open
                  [socket (.accept server)
                   out (io/writer (.getOutputStream socket))]
                   (binding [*out* out]
                     (println "HTTP/1.1 200 OK")
                     (println "Content-Type: text/event-stream")
                     (println "Connection: keep-alive")
                     (println)
                     (try (loop []
                            (println "data: Stream Hello!")
                            (Thread/sleep 20)
                            (recur))
                          (catch Exception _ nil))))
                 (catch Exception e
                   (prn e))))
    (let [resp (http/get (str "http://localhost:" port)
                         {:as :stream})
          status (:status resp)
          headers (:headers resp)
          body (:body resp)]
      (is (= 200 status))
      (is (= "text/event-stream" (get headers "content-type")))
      (is (= (repeat 2 "data: Stream Hello!") (take 2 (line-seq (io/reader body)))))
      (is (= (repeat 10 "data: Stream Hello!") (take 10 (line-seq (io/reader body))))))))

(deftest exceptional-status-test
  (testing "should throw"
    (let [ex (is (thrown? ExceptionInfo (http/get "http://localhost:12233/404")))
          response (ex-data ex)]
      (is (= 404 (:status response)))))
  (testing "should throw when streaming based on status code"
    (let [ex (is (thrown? ExceptionInfo (http/get "http://localhost:12233/404" {:throw true
                                                                                :as :stream})))
          response (ex-data ex)]
      (is (= 404 (:status response)))
      (is (= "404 Not Found" (slurp (:body response))))))
  (testing "should not throw"
    (let [response (http/get "http://localhost:12233/404" {:throw false})]
      (is (= 404 (:status response))))))

(deftest compressed-test
  (let [resp (http/get "https://api.stackexchange.com/2.2/sites"
                       {:headers {"Accept-Encoding" ["gzip" "deflate"]}})]
    (is (-> resp :body (json/parse-string true) :items))))

(deftest default-client-test
  (let [resp (http/get "https://postman-echo.com/get")
        headers (-> resp :body (json/parse-string true) :headers)]
    (is (= "*/*" (:accept headers)))
    (is (= "gzip, deflate" (:accept-encoding headers)))
    (is (= (str "babashka.http-client/" iv/version) (:user-agent headers)))))

(deftest client-request-opts-test
  (let [client (http/client {:request {:headers {"x-my-header" "yolo"}}})
        resp (http/get "https://postman-echo.com/get"
                       {:client client})
        header (-> resp :body (json/parse-string true) :headers :x-my-header)]
    (is (= "yolo" header))
    (let [resp (http/get "https://postman-echo.com/get"
                         {:client client :headers {"x-my-header" "dude"}})
          header (-> resp :body (json/parse-string true) :headers :x-my-header)]
      (is (= "dude" header)))))

(deftest header-with-keyword-key-test
  (is (= 200
         (-> (http/get "http://localhost:12233/200"
                       {:headers {:accept "application/json"}})
             :body
             (json/parse-string true)
             :code))))

(deftest follow-redirects-test
  (testing "default behaviour of following redirects automatically"
    (is (= 200 (:status (http/get "http://localhost:12233/302")))))

  (testing "follow redirects set to false"
    (is (= 302 (:status (http/get "http://localhost:12233/302" {:client (http/client {:follow-redirects false})}))))))

(deftest interceptor-test
  (let [json-interceptor
        {:name ::json
         :description
         "A request with `:as :json` will automatically get the
         \"application/json\" accept header and the response is decoded as JSON."
         :request (fn [request]
                    (if (= :json (:as request))
                      (-> (assoc-in request [:headers :accept] "application/json")
                          ;; Read body as :string
                          ;; Mark request as amenable to json decoding
                          (assoc :as :string ::json true))
                      request))
         :response (fn [response]
                     (if (get-in response [:request ::json])
                       (update response :body #(json/parse-string % true))
                       response))}
        ;; Add json interceptor add beginning of chain
        ;; It will be the first to see the request and the last to see the response
        interceptors (cons json-interceptor interceptors/default-interceptors)]
    (testing "interceptors on request"
      (let [resp (http/get "http://localhost:12233/200"
                           {:interceptors interceptors
                            :as :json})]
        (is (= 200 (-> resp :body
                       ;; response as JSON
                       :code)))))
    (testing "interceptors on client"
      (let [client (http/client (assoc-in http/default-client-opts
                                          [:request :interceptors] interceptors))
            resp (http/get "http://localhost:12233/200"
                           {:client client
                            :as :json})]
        (is (= 200 (-> resp :body
                       ;; response as JSON
                       :code)))))))

(deftest multipart-test
  (let [uuid (.toString (random-uuid))
        _ (spit (doto (io/file ".test-data")
                  (.deleteOnExit)) uuid)
        resp (http/post "https://postman-echo.com/post"
                        {:multipart [{:name "title" :content "My Awesome Picture"}
                                     {:name "Content/type" :content "image/jpeg"}
                                     {:name "foo.txt" :part-name "eggplant" :content "Eggplants"}
                                     {:name "file" :content (io/file ".test-data") :file-name "dude"}]})
        resp-body (:body resp)
        resp-body (json/parse-string resp-body true)
        headers (:headers resp-body)]
    (is (str/starts-with? (:content-type headers) "multipart/form-data; boundary=babashka_http_client_Boundary"))
    (is (some? (:dude (:files resp-body))))
    (is (= "My Awesome Picture" (-> resp-body :form :title)))))

(deftest async-test
  (deflet
    (def async-resp (http/get "http://localhost:12233/200" {:async true}))
    (is (instance? java.util.concurrent.CompletableFuture async-resp))
    (is (= 200 (:status @async-resp)))
    (def async-resp (http/get "http://localhost:12233/200" {:async true
                                                            :async-then (fn [resp]
                                                                          (:status resp))}))
    (is (= 200 @async-resp))
    (def async-resp (http/get "http://localhost:12233/404" {:async true
                                                            :async-then (fn [resp]
                                                                          (:status resp))
                                                            :async-catch (fn [e]
                                                                           (:ex-data e))}))
    (is (= 404 (:status @async-resp)))))

(comment
  (run-server)
  (stop-server))
