(ns hulunote-chrome-extension-admin.background
  (:require [hulunote-chrome-extension-admin.utils :as u]
            [cljs.core.async :refer [go go-loop timeout <!]]
            [cljs.core.async.interop :refer-macros [<p!]]))

(defn on-to-open-chatgpt-service
  "检查并开启chatgpt服务" [tab-id]
  (u/with-chrome-storage-value "open-chatgpt"
    #(when %
       (u/send-chrome-message-to-tab tab-id "open-chatgpt-service" ""))))

(defn on-getting-chatgpt-request
  "请求获取chatgpt request"
  [tab-id]
  (go
    (let [result (<p! (u/post "https://www.yourserver.com/chatgpt/get-chatgpt-message", {}))
          error (get result "error")
          item (get result "item")]
      (if error
        (do
          (println error)
          (u/send-chrome-message-to-tab tab-id "get-chatgpt-request-none" ""))
        (do
          (println "getting:" item)
          (u/send-chrome-message-to-tab tab-id "get-chatgpt-request" item))))))

(defn on-getting-chatgpt-request-heartbeat
  "以正常的逻辑形式请求获取以发送心跳"
  [tab-id]
  (let [a-rand (rand-int 100)
        b-rand (rand-int 100)
        text (str "计算" a-rand " + " b-rand " = ")
        item {"message" text
              "bot-uuid" "wxid_dykrehtiguat22"
              "speaker" "<ChatGPT心跳消息>"}]
    (println "heartbeat:" item)
    (u/send-chrome-message-to-tab tab-id "get-chatgpt-request" item)))

(defn on-sending-chatgpt-result
  "发送返回chatgpt结果"
  [tab-id message]
  (go
    (let [message (get message "message")]
      (loop [n 0]
        (if (= n 2)
          ;; 重新提交，post提交3次失败
          (let [re-request (<p! (u/post "https://www.yourserver.com/chatgpt/save-chatgpt-request" message))]
            (println "发送POST错误，重新提交：" re-request)
            (<p! (u/post "https://www.yourserver.com/chatgpt/send-post-error" {}))
            (u/send-chrome-message-to-tab tab-id "send-chatgpt-result-refresh" ""))

          ;; 重试提交post请求
          (let [result (<p! (u/post "https://www.yourserver.com/chatgpt/send-chatgpt-result" message))]
            (if (nil? result)
              (recur (inc n))
              (do
                (println "sending:" message " result:" result)
                (u/send-chrome-message-to-tab tab-id "send-chatgpt-result" "")))))))))

(defn on-sending-chatgpt-ws-result-state
  "发送返回的chatgpt结果中间状态"
  [tab-id message]
  (go
    ;; 这里不用重复提交，因为是还没有计算完成的
    (let [message (get message "message")
          result (<p! (u/post "https://www.yourserver.com/chatgpt/send-chatgpt-result" message))]
      (println "sending-state:" message " result:" result))))

(defn on-sending-chatgpt-error
  "发送chatgpt错误的结果"
  [tab-id message]
  ;; 重新提交队列
  (go
    (let [message (get message "message")
          result (<p! (u/post "https://www.yourserver.com/chatgpt/save-chatgpt-request" message))]
      (println "发生错误，重新提交:" result)
      (u/send-chrome-message-to-tab tab-id "send-chatgpt-result-refresh" ""))))

(defn on-sending-chatgpt-error-too-many
  "发送chatgpt的too many错误结果"
  [tab-id message]
  (go
    (let [message (get message "message")
          message (assoc message "is-too-many" true)
          result (<p! (u/post "https://www.yourserver.com/chatgpt/save-chatgpt-request" message))]
      (println "发生错误，重新提交:" result)
      (u/send-chrome-message-to-tab tab-id "send-chatgpt-error-too-many" ""))))

;; 这里先设置一个永久的token，567
(u/with-chrome-storage-value "token"
  #(when-not %
     (u/set-chrome-storage-value "token" "YOUR_SERVERS_TOKEN")))


(.. js/chrome -runtime -onMessage
    (addListener (fn [message sender response]
                   (let [tab (aget sender "tab")
                         tab-id (aget tab "id")
                         message (js->clj message)
                         type (get message "type")]
                     (cond
                       ;; 开启chatgpt
                       (= type "open-chatgpt-service")
                       (on-to-open-chatgpt-service tab-id)

                       ;; 心跳
                       (= type "get-chatgpt-request-heartbeat")
                       (on-getting-chatgpt-request-heartbeat tab-id)

                       ;; 获取chatgpt请求
                       (= type "get-chatgpt-request")
                       (on-getting-chatgpt-request tab-id)

                       ;; 发送结果
                       (= type "send-chatgpt-result")
                       (on-sending-chatgpt-result tab-id message)

                       ;; 发送WS的中间状态
                       (= type "send-chatgpt-ws-result-state")
                       (on-sending-chatgpt-ws-result-state tab-id message)

                       ;; 发送错误结果
                       (= type "send-chatgpt-error-item")
                       (on-sending-chatgpt-error tab-id message)

                       ;; 发送错误结果，延迟刷新
                       (= type "send-chatgpt-error-too-many")
                       (on-sending-chatgpt-error-too-many tab-id message))))))
