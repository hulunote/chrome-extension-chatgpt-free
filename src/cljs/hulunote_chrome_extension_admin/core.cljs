(ns hulunote-chrome-extension-admin.core
  (:require [hulunote-chrome-extension-admin.utils :as u]
            [cljs.core.async :refer [go go-loop timeout <!]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [clojure.string :as strings]
            [cljs.reader :refer [read-string]]))

(declare open-chatgpt-service
         on-getting-chatgpt-request)

(defn select-open-service
  "查看服务并开启" []
  (let [curr-url (.. js/window -location -href)]
    (cond
      (= curr-url "https://chat.openai.com/chat")
      (u/send-chrome-message "open-chatgpt-service" ""))))

;; 状态 :waiting :getting-request :calculating :sending-result
(def state (atom :waiting))
;; 等待状态的计时
(def waiting-times (atom 0))

(defn open-chatgpt-service []
  (let [input-doms (u/get-elements-by-class-name "m-0 w-full resize-none border-0 bg-transparent focus:ring-0 focus-visible:ring-0 dark:bg-transparent py-[10px] pr-10 md:py-3.5 md:pr-12 max-h-[25dvh] max-h-52 placeholder-black/50 dark:placeholder-white/50 pl-4 md:pl-6")]
    (when-not (empty? input-doms)
      (println "成功开启chatgpt的服务")
      (go-loop [last-ts (.getTime (js/Date.))]
        (let [curr-ts (.getTime (js/Date.))
              diff (- last-ts curr-ts)
              expired? (>= diff (* 30 60 1000))]
          (cond
        ;; 半小时重刷
            (and (= @state :waiting)
                 expired?)
            (do
              (println "半小时自动刷新")
              (.reload js/location))

        ;; 正常请求
            (= @state :waiting)
            (do
              (println "获取chatgpt请求中...(" @waiting-times ")")
              (swap! waiting-times inc)
              (if (>= @waiting-times 120)
                ;; 10分钟发送心跳
                (do
                  (reset! waiting-times 0)
                  (u/send-chrome-message "get-chatgpt-request-heartbeat" ""))
                (u/send-chrome-message "get-chatgpt-request" ""))
              (reset! state :getting-request)
              (<! (timeout 5000))
              (recur last-ts))

        ;; 上一个请求处理中
            :else
            (do
              (println "正在处理上一个请求中，继续等待..." @state)
              (<! (timeout 5000))
              (recur last-ts))))))))

(defn on-getting-none-chatgpt-request
  "来自background的获取chatgpt request，没有获取到请求"
  []
  (println "没有未处理的chatgpt请求")
  (reset! state :waiting))

(defn- may-error? [may-error-doms curr-result-text]
  (or
   ;; 红字错误部份
   (not (empty? may-error-doms))
   ;; 特定错误部份
   (strings/includes? curr-result-text "Something went wrong")
   (= curr-result-text "err")
   (strings/includes? curr-result-text "An error occurred.")
   (strings/includes? curr-result-text "Request timed out")
   (strings/includes? curr-result-text "network error")
   (strings/includes? curr-result-text "The server experienced an error")
   (strings/includes? curr-result-text "That model is currently overloaded with other requests.")
   (strings/includes? curr-result-text "Conversation not found")
   (strings/includes? curr-result-text "something seems to have gone wrong.")
   (strings/includes? curr-result-text "Only one message at a time")
   (strings/includes? curr-result-text "Sorry,")
   (strings/includes? curr-result-text "You requested a model that is not compatible with this engine.")
   (strings/includes? curr-result-text "That model does not exist")
   (strings/includes? curr-result-text "The server had an error while processing your request.")))

(defn on-getting-chatgpt-request
  "来自background的获取chatgpt request"
  [id speaker-id speaker ws-uuid bot-uuid message]
  (go
    (let [input-dom-class "m-0 w-full resize-none border-0 bg-transparent focus:ring-0 focus-visible:ring-0 dark:bg-transparent py-[10px] pr-10 md:py-3.5 md:pr-12 max-h-[25dvh] max-h-52 placeholder-black/50 dark:placeholder-white/50 pl-4 md:pl-6"
          input-dom (last (u/get-elements-by-class-name input-dom-class))
          enter-btn (last (u/get-elements-by-class-name "absolute bottom-1.5 right-2 rounded-lg border border-black bg-black p-0.5 text-white transition-colors enabled:bg-black disabled:text-gray-400 disabled:opacity-10 dark:border-white dark:bg-white dark:hover:bg-white md:bottom-3 md:right-3"))]

      ;; 没有获取到input-dom，提交重试
      (when-not input-dom
        (println "Warning: 没有获取到input-dom，重新提交，刷新")
        (let [param {:id id
                     :ws-uuid ws-uuid
                     :bot-uuid bot-uuid
                     :message message
                     :speaker-id speaker-id
                     :speaker speaker
                     :calculating true}]
          (u/send-chrome-message "send-chatgpt-error-item" param)))

      (reset! state :calculating)
      (reset! waiting-times 0)
      ;; 输入内容
      (<! (timeout 1500))
      (set! (.-value input-dom) message)
      ;; 点击enter
      (<! (timeout 1500))
      (.click enter-btn)
      ;; 等待出结果
      (<! (timeout 1500))
      (loop [caling-times 0]
        (println "计算looping...")
        (if-let [curr-result-dom (last (u/get-elements-by-class-name "w-full border-b border-black/10 dark:border-gray-900/50 text-gray-800 dark:text-gray-100 group bg-gray-50 dark:bg-[#444654]"))]
          ;; 已发送成功，检测完成计算
          (let [question-dom (last (u/get-elements-by-class-name "w-full border-b border-black/10 dark:border-gray-900/50 text-gray-800 dark:text-gray-100 group dark:bg-gray-800"))
                question-text (strings/trim (.-innerText question-dom))
                curr-result-text (strings/trim (.-innerText curr-result-dom))
                disabled-btns (u/get-elements-by-class-name "btn relative btn-neutral border-0 md:border")
                disabled-btn-text (if-not (empty? disabled-btns)
                                    (.-innerText (first disabled-btns))
                                    "")
                calculating-dom (last (u/get-elements-by-class-name "text-2xl"))
                may-error-doms (u/get-elements-by-class-name-in curr-result-dom "text-red-500 markdown prose w-full break-words dark:prose-invert")

                param  {:id id
                        :ws-uuid ws-uuid
                        :bot-uuid bot-uuid
                        :message message
                        :speaker-id speaker-id
                        :speaker speaker
                        :result curr-result-text}

                t-question-text (-> question-text
                                    (strings/replace "\n" "")
                                    (strings/replace "\r" "")
                                    (strings/trim))
                t-message-text (-> message
                                   (strings/replace "\n" "")
                                   (strings/replace "\r" "")
                                   (strings/trim))]

            (when-not (strings/includes? t-question-text t-message-text)

              ;; 重新检查input-dom
              (let [input-dom (last (u/get-elements-by-class-name input-dom-class))]
                (when-not input-dom
                  (println "Warning: 没有获取到input-dom，重新提交，刷新")
                  (u/send-chrome-message "send-chatgpt-error-item" param)))
              (<! (timeout 500))
              (println "没有输入成功，重试")
              (<! (timeout 1500))
              (set! (.-value input-dom) message)
              (<! (timeout 1500))
              (.click enter-btn)
              (recur caling-times))

            (if (and #_(or (empty? disabled-btns)
                           (not= disabled-btn-text "Regenerate response"))
                 (some? calculating-dom)
                     (< caling-times 150))
              ;; 计算过程短或者没有停止按钮时，继续计算
              (cond

                ;; too many request，需要等待20分钟后再刷新
                (strings/includes? curr-result-text "Too many request")
                (do
                  (println "触发Too many request，\n等待10分钟")
                  (u/send-chrome-message "send-chatgpt-error-too-many" param))

                ;; 计算有错误，刷新重试
                (may-error? may-error-doms curr-result-text)
                (do
                  (println "报错，刷新页面")
                  (u/send-chrome-message "send-chatgpt-error-item" param))

                ;; ws端，发送中间状态
                ws-uuid
                (do
                  (u/send-chrome-message "send-chatgpt-ws-result-state" (assoc param :calculating true))
                  (<! (timeout 3000))
                  (recur caling-times))

                ;; 非WS端，等待
                :else
                (do (println "正在计算中:" curr-result-text "\n" (js/Date.) "\n <times:" caling-times ">")
                    (<! (timeout 3000))
                    (recur (inc caling-times))))

              ;; 出结果了，发送
              (do
                (println "curr:" curr-result-text ":end")

                ;; 如果出结果了，还有终止的按钮，先点击终止
                (when (= disabled-btn-text "Stop generating")
                  (println "Warning: 长时间卡住生成，这里直接返回")
                  (.click (first disabled-btns)))

                (cond
                ;; too many request，需要等待20分钟后再刷新
                  (strings/includes? curr-result-text "Too many request")
                  (do
                    (println "触发Too many request，\n等待10分钟")
                    (u/send-chrome-message "send-chatgpt-error-too-many" param))

                ;; 报错情况，用刷新解决
                  (may-error? may-error-doms curr-result-text)
                  (do
                    (println "报错，刷新页面")
                    (u/send-chrome-message "send-chatgpt-error-item" param))

                ;; 正常发送
                  :else
                  (do
                    (println "完成计算，准备发送")
                    (reset! state :sending-result)
                    (u/send-chrome-message "send-chatgpt-result" param))))))
          ;; 等待发送成功
          (do
            (println "没有成功发送的标志信息，重新输入发送")
            (<! (timeout 1500))
            (set! (.-value input-dom) message)
            (<! (timeout 1500))
            (.click enter-btn)
            (recur caling-times)))))))

(defn on-sent-result-reply
  "来自background的发送结果的反馈"
  []
  (println "已发送chatgpt结果")
  (reset! state :waiting))

(defn on-chatgpt-refresh
  "刷新页面"
  []
  (.reload js/location))

(defn on-chatgpt-too-many-refresh
  "延迟刷新"
  []
  (go
    (<! (timeout 600000))
    (.reload js/location)))

(defn init-message-event []
  ;; 初始化消息
  (.. js/chrome -runtime -onMessage
      (addListener (fn [message sender response]
                     (let [message (js->clj message)
                           type (get message "type")
                           message (get message "message")]
                       (cond
                         ;; 开启chatgpt服务
                         (= type "open-chatgpt-service")
                         (open-chatgpt-service)

                         ;; 获取chatgpt request，没有内容
                         (= type "get-chatgpt-request-none")
                         (on-getting-none-chatgpt-request)

                         ;; 获取chatgpt request
                         (= type "get-chatgpt-request")
                         (let [id (get message "id")
                               speaker-id (get message "speaker-id")
                               speaker (get message "speaker")
                               ws-uuid (get message "ws-uuid")
                               bot-uuid (get message "bot-uuid")
                               message (get message "message")]
                           (if (empty? message)
                             (on-getting-none-chatgpt-request)
                             (on-getting-chatgpt-request id speaker-id speaker ws-uuid bot-uuid message)))

                         ;; 发送结果完毕
                         (= type "send-chatgpt-result")
                         (on-sent-result-reply)

                         ;; 发送重新刷新页面
                         (= type "send-chatgpt-result-refresh")
                         (on-chatgpt-refresh)

                         (= type "send-chatgpt-error-too-many")
                         (on-chatgpt-too-many-refresh)))))))

(defn run-on-window-load []
  (select-open-service)
  (init-message-event))

(set! (.-onload js/window) run-on-window-load)
