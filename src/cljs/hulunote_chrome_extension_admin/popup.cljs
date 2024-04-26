(ns hulunote-chrome-extension-admin.popup
  (:require [hulunote-chrome-extension-admin.utils :as u]))

(defn click-to-open-service []
  (let [token-dom (u/get-element-by-id "token-input")
        save-btn (u/get-element-by-id "save")
        open-chatgpt-btn (u/get-element-by-id "open-chatgpt-service")]
    
    ;; 保存token 
    (u/with-chrome-storage-value "token"
      #(set! (.-text token-dom) %))
    (set! (.-onclick save-btn)
          #(let [token (.-value token-dom)]
             (u/set-chrome-storage-value "token" token)
             (u/notify-message (str "已设置token:" token))))
    
    ;; 开启chatgpt服务
    (u/with-chrome-storage-value "open-chatgpt"
      #(if %
         (set! (.-innerText open-chatgpt-btn) "关闭chatgpt服务")
         (set! (.-innerText open-chatgpt-btn) "开启chatgpt服务")))
    (set! (.-onclick open-chatgpt-btn)
          (fn [] (u/with-chrome-storage-value "open-chatgpt"
                   #(if %
                      (do
                        (u/set-chrome-storage-value "open-chatgpt" false)
                        (set! (.-innerText open-chatgpt-btn) "开启chatgpt服务")
                        (u/notify-message (str "已关闭chatgpt服务，需要刷新页面")))
                      (do
                        (u/set-chrome-storage-value "open-chatgpt" true)
                        (set! (.-innerText open-chatgpt-btn) "关闭chatgpt服务")
                        (u/notify-message (str "已开启chatgpt服务，需要刷新页面")))))))))

(u/add-event-listener "DOMContentLoaded" click-to-open-service)
