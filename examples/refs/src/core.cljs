(ns examples.refs.core
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]))

(enable-console-print!)

(defprotocol IResolve
  (-resolve [this id]))

(def app-state
  (atom {:my-ref {:count 0}
         :view0  {:text "View 0"}
         :view1  {:text "View 1"}}))

(defn ref-widget [widget owner]
  (reify
    om/IRenderProps
    (render-props [_ widget _]
      (println "Render ref widget!")
      (dom/div nil
        (dom/button
          #js {:onClick #(om/transact! widget :count inc)}
          "+")
        (dom/p nil (str "Count: " (:count widget)))))))

(defn aview [view owner]
  (reify
    om/IRender
    (render [_]
      (println "Render" (:text view) "!")
      (let [my-ref (-resolve view :my-ref)]
       (dom/div nil
         (dom/h2 nil (:text view))
         (om/build ref-widget my-ref))))))

(defn my-app [global owner]
  (reify
    om/IRender
    (render [_]
      (println "Render root!")
      (dom/div nil
        (om/build aview (:view0 global))
        (om/build aview (:view1 global))))))

;; resolution support

(declare resolvable)

(defn allocate-storage* [id]
  (atom {}))

(def allocate-storage (memoize allocate-storage*))

;; the following code need some thought wrt to how to
;; best preserve previously specified behavior

(defn ref-cursor [cursor id state]
  (let [storage (allocate-storage id)]
    (specify cursor
      om/ICursorDerive
      (-derive [this derived state path]
        (let [cursor' (om/to-cursor derived state path)]
          (if (om/cursor? cursor')
            (ref-cursor
              (resolvable
                (om/tag-root-key
                  cursor'
                  (om/-root-key cursor)))
              id state)
            cursor')))
      om/IOmRef
      (-add-dep! [_ c]
        (swap! storage assoc (om/id c) c))
      (-remove-dep! [_ c]
        (swap! storage dissoc (om/id c)))
      (-get-deps [_]
        @storage)
      om/ITransact
      (-transact! [cursor korks f tag]
        (println "transact!" (om/value cursor))
        (om/commit! cursor korks f)
        (doseq [c (vals @storage)]
          (om/refresh-props! c))))))

(defn resolve-id [id cursor resolve-fn]
  (let [state  (om/state cursor)
        cursor (resolvable
                 (om/tag-root-key
                   (om/to-cursor (get @state id) state [])
                   (om/-root-key cursor))
                 resolve-fn)]
    (if (= id :my-ref)
      (ref-cursor cursor id state)
      cursor)))

(defn resolvable [x resolve-fn]
  (if (om/cursor? x)
    (specify x
      ICloneable
      (-clone [this]
        (resolvable (-clone x) resolve-fn))
      IResolve
      (-resolve [_ id]
        (resolve-fn id x resolve-fn))
      om/ICursorDerive
      (-derive [_ derived state path]
        (resolvable
          (om/tag-root-key
            (om/to-cursor derived state path)
            (om/-root-key x))
          resolve-fn)))
    x))

(om/root my-app app-state
  {:target (.getElementById js/document "app")
   :adapt  (fn [cursor] (resolvable cursor resolve-id))})