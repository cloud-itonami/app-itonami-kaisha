(ns kaisha.screen
  "画面の状態の投影。state と画面 id から、表示に使う状態を毎回計算する。

  状態は :ready / :empty / :error / :undetermined / :blocked。
  :error は state が schema v2 を満たさない場合だけ。:undetermined は制度・記録から
  判定しない場合、:blocked は前提を満たさず操作できない場合を表す。
  投影は state に保存しない（:screen/* のような派生値を足さない）。"
  (:require [kaisha.app :as app]
            [kaisha.books :as books]
            [kaisha.chat :as chat]
            [kaisha.company :as co]
            [kaisha.meeting :as mt]
            [kaisha.ringi :as ringi]))

(def screens
  "投影できる画面。"
  #{:kaisha.schedule :kaisha.meeting :kaisha.ringi :kaisha.books
    :kaisha.chat :kaisha.procedures :kaisha.settings})

(defn- first-with-status [status xs]
  (some #(when (= status (:status %)) %) xs))

(defn- schedule-status [state]
  (let [c (:company state)
        terms (map #(co/officer-term c %) (:company/officers c))]
    (cond
      (first-with-status :undetermined terms)
      {:status :undetermined :reason (:reason (first-with-status :undetermined terms))}

      (not (or (:company/fy-end-month c) (seq (:company/officers c))
               (seq (:meetings state)) (seq (:registrations state))))
      {:status :empty}

      :else {:status :ready})))

(defn- meeting-status [state]
  (let [c (:company state)
        ms (:meetings state)
        checks (map #(mt/notice-check c %) ms)]
    (cond
      (empty? ms) {:status :empty}
      (first-with-status :undetermined checks)
      {:status :undetermined :reason (:reason (first-with-status :undetermined checks))}
      (first-with-status :blocked checks)
      {:status :blocked :reason (:reason (first-with-status :blocked checks))}
      :else {:status :ready})))

(defn- ringi-status [state]
  (let [c (:company state)
        rs (:ringi state)]
    (cond
      (empty? rs) {:status :empty}
      (some #(= :awaiting-board (ringi/status c %)) rs)
      {:status :blocked :reason :board-resolution-required}
      :else {:status :ready})))

(defn- books-status [state]
  (let [rows (get-in state [:financials :fs/rows])]
    (cond
      (empty? rows) {:status :empty :reason :no-trial-balance}
      (not (:balanced? (books/summarize rows)))
      {:status :blocked :reason :unbalanced-trial-balance}
      :else {:status :ready})))

(defn- chat-status [state]
  (if (seq (chat/visible-channels (:space state) (:actor/id (:actor state))))
    {:status :ready}
    {:status :empty}))

(defn- representative-check [c]
  (some (fn [o]
          (let [r (co/representative? c (:officer/id o))]
            (when (#{:ok :unverified} (:status r)) r)))
        (:company/officers c)))

(defn- procedures-status [state]
  (let [c (:company state)
        rep (representative-check c)]
    (cond
      (empty? (:procedures state)) {:status :empty}
      (not (contains? co/supported-forms (:company/form c)))
      {:status :undetermined :reason :unsupported-entity-form}
      (nil? rep) {:status :blocked :reason :no-representative-authority}
      (= :unverified (:status rep)) {:status :blocked :reason (:reason rep)}
      :else {:status :ready})))

(defn- settings-status [state]
  (if (contains? co/supported-forms (get-in state [:company :company/form]))
    {:status :ready}
    {:status :undetermined :reason :unsupported-entity-form}))

(defn status
  "state と画面 id から {:status kw :reason kw?} を返す。state は変更しない。"
  [state screen-id]
  (cond
    (not (app/valid-state? state)) {:status :error :reason :invalid-state}
    (not (contains? screens screen-id)) {:status :error :reason :unknown-screen}
    :else
    (case screen-id
      :kaisha.schedule (schedule-status state)
      :kaisha.meeting (meeting-status state)
      :kaisha.ringi (ringi-status state)
      :kaisha.books (books-status state)
      :kaisha.chat (chat-status state)
      :kaisha.procedures (procedures-status state)
      :kaisha.settings (settings-status state))))
