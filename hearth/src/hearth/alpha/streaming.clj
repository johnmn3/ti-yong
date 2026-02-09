(ns hearth.alpha.streaming
  "Streaming response body support.
   Provides a protocol-based approach for writing response bodies
   to output streams without buffering the full response in memory."
  (:import
   [java.io OutputStream InputStream]))

(defprotocol IStreamableBody
  (stream-body [body output-stream]
    "Write body content to the output-stream. Implementations should flush
     and close the output-stream when done."))

(extend-protocol IStreamableBody
  String
  (stream-body [s ^OutputStream os]
    (.write os (.getBytes s "UTF-8"))
    (.flush os)
    (.close os))

  InputStream
  (stream-body [is ^OutputStream os]
    (let [buf (byte-array 8192)]
      (loop []
        (let [n (.read is buf)]
          (when (pos? n)
            (.write os buf 0 n)
            (.flush os)
            (recur))))
      (.close is)
      (.close os)))

  clojure.lang.IFn
  (stream-body [f ^OutputStream os]
    (f os)))

;; byte array — extend-type with the actual class
(extend-type (Class/forName "[B")
  IStreamableBody
  (stream-body [^bytes bs ^OutputStream os]
    (.write os bs)
    (.flush os)
    (.close os)))

(defn streamable?
  "Returns true if the body supports streaming via IStreamableBody."
  [body]
  (satisfies? IStreamableBody body))
