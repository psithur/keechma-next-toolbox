(ns keechma.next.controllers.router.protocols)

(defprotocol IRouterApi
  (redirect! [this params])
  (replace! [this params])
  (update! [this params])
  (back! [this])
  (get-url [this params]))
