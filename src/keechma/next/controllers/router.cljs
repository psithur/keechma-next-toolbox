(ns keechma.next.controllers.router
  (:require [keechma.next.controller :as ctrl]
            [clojure.string]
            [router.v2.core :as router]
            [goog.history.EventType :as EventType]
            [keechma.next.protocols :as keechma-pt]
            [oops.core :refer [ocall ocall+]]
            [keechma.next.controllers.router.protocols :as pt :refer [IRouterApi]]
            [keechma.next.toolbox.event :as event])
  (:import goog.History))

(derive :keechma/router :keechma/controller)

(defn get-history []
  (History. false nil
            (.getElementById js/document "history_state0")
            (.getElementById js/document "history_iframe0")))

(defn url-no-hashbang [url]
  (str "/a/" url))

(defn map->url [routes params]
  (->> params
       (router/map->url routes)
       url-no-hashbang))

(defn bind-listener [ctrl routes]
  (let [history (get-history)
        handler #(ctrl/handle ctrl :keechma.router.on/route-change (router/url->map routes ^js/String (.-state %)))
        listener (.addEventListener
                   js/window
                   "popstate"
                   handler)]

    (.setEnabled history true)
    (fn []
      (.removeEventListener js/window "popstate" listener))))

(defn default-router-processor [next-params _]
  next-params)

(defn get-params-processor [{:keys [state*] :as ctrl}]
  (let [processor (or (:router.params/processor ctrl) default-router-processor)]
    (fn [next-params]
      (let [params (:data @state*)]
        (processor next-params params)))))

(defmethod ctrl/init :keechma/router [ctrl]
  (let [routes (router/expand-routes (:keechma/routes ctrl))]
    (assoc ctrl ::unlisten (bind-listener ctrl routes)
           ::routes routes)))

(defmethod ctrl/api :keechma/router [ctrl]
  (let [params-processor (get-params-processor ctrl)
        routes (::routes ctrl)
        state* (:state* ctrl)
        do-transition (fn [hfn payload]
                        (let [transaction
                              (fn []
                                  ;; Do a double conversion payload -> url -> url-payload
                                  ;; to get the identical result to one we would get if the
                                  ;; user clicked on the link
                                  (let [url (->> payload params-processor (router/map->url routes))
                                        url-payload (router/url->map routes url)]
                                    (ocall+ js/history hfn url "" (url-no-hashbang url))
                                    (reset! state* url-payload)))]
                          (ctrl/transact ctrl transaction)))]

    (reify
      IRouterApi
      (redirect! [_ payload]
        (do-transition "pushState" payload))
      (replace! [_ payload]
        (do-transition "replaceState" payload))
      (update! [_ payload]
        (do-transition "replaceState" (merge (:data @state*) payload)))
      (back! [_]
        (.back js/history))
      (get-url [_ params]
        (->> params params-processor (map->url routes))))))

(defmethod ctrl/start :keechma/router [ctrl]
  (let [url (subs (.. js/window -location -pathname) 3)
        params (.. js/window -location -search)
        full (str url params)
        routes (::routes ctrl)]
    (ocall+ js/history "replaceState" full "" (url-no-hashbang full))
    (router/url->map routes full)))

(defmethod ctrl/handle :keechma/router [{:keys [state*] :as ctrl} cmd payload]
  (let [params-processor (get-params-processor ctrl)
        routes (::routes ctrl)]
    (case cmd
      :keechma.router.on/route-change (reset! state* payload)
      :keechma.router.on/redirect (set! (.-hash js/location) (->> payload params-processor (map->url routes)))
      nil)))

(defmethod ctrl/derive-state :keechma/router [_ state _]
  (:data state))

(defmethod ctrl/terminate :keechma/router [ctrl]
  (let [unlisten (::unlisten ctrl)]
    (unlisten)))

(defn make-api-proxy [api-fn]
  (fn [{:keechma/keys [app]} controller-name & args]
    (let [api* (keechma-pt/-get-api* app controller-name)]
      (apply api-fn @api* args))))

(def redirect! (make-api-proxy pt/redirect!))
(def replace! (make-api-proxy pt/replace!))
(def update! (make-api-proxy pt/update!))
(def back! (make-api-proxy pt/back!))
(def get-url (make-api-proxy pt/get-url))

(defn to-redirect-event [controller-name payload]
  (event/to-dispatch controller-name :keechma.router.on/redirect payload))
