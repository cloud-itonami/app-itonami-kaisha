(ns kaisha.chat
  "社内の会話: チャンネル、スレッド、DM、メンション、既読。

  意味は kotoba-lang/app-itonami-kaisha（kaisha.model、a1f9099）に合わせる:
  スレッドは 1 段だけ、非公開チャンネルはメンバーにだけ見える、既読は人ごと・チャンネルごと、
  DM の id は 2 人から決まる（長さ付きの id で、別の組と衝突しない）。
  メッセージは会議・稟議などの記録に :ref で結びつけられる。"
  (:require [clojure.string :as str]))

(defn dm-id
  "DM の id。2 人の id を並べ替え、長さを前に付ける（dm:3:jun:rin）。"
  [a b]
  (let [[x y] (sort [a b])]
    (str "dm:" (count x) ":" x ":" y)))

(defn channel [id {:keys [name visibility members kind]}]
  {:channel/id id
   :channel/name (or name id)
   :channel/kind (or kind :channel)
   :channel/visibility (or visibility :public)
   :channel/members (set members)
   :channel/messages []})

(defn add-channel [space ch] (assoc-in space [:channels (:channel/id ch)] ch))

(defn open-dm
  "DM を開く。すでにあれば何もしない。"
  [space a b]
  (let [id (dm-id a b)]
    (if (get-in space [:channels id])
      space
      (add-channel space (channel id {:kind :dm :visibility :private :members [a b]})))))

(defn visible? [ch member]
  (or (= :public (:channel/visibility ch))
      (contains? (:channel/members ch) member)))

(defn visible-channels [space member]
  (->> (vals (:channels space)) (filter #(visible? % member)) (sort-by :channel/name) vec))

(defn mentions
  "本文の @id を集める。"
  [body]
  (set (map second (re-seq #"@([^\s@,、。!?！？]+)" (or body "")))))

(defn- find-message [ch id] (some #(when (= id (:message/id %)) %) (:channel/messages ch)))

(defn post
  "メッセージを書く。parent を渡すと返信（返信への返信は拒む）。返り値 {:ok space} か {:error kw}。"
  [space channel-id {:message/keys [id author body at parent] :as m}]
  (let [ch (get-in space [:channels channel-id])]
    (cond
      (nil? ch) {:error :unknown-channel}
      (not (visible? ch author)) {:error :not-a-member}
      (str/blank? body) {:error :empty-message}
      (find-message ch id) {:error :duplicate-message}
      (and parent (nil? (find-message ch parent))) {:error :unknown-parent}
      (and parent (:message/parent (find-message ch parent))) {:error :nested-thread}
      :else {:ok (update-in space [:channels channel-id :channel/messages] conj
                            (assoc m :message/mentions (mentions body)))})))

(defn top-level [space channel-id]
  (->> (get-in space [:channels channel-id :channel/messages])
       (remove :message/parent) (sort-by :message/at) vec))

(defn replies [space channel-id parent-id]
  (->> (get-in space [:channels channel-id :channel/messages])
       (filter #(= parent-id (:message/parent %))) (sort-by :message/at) vec))

(defn mark-read [space member channel-id at]
  (assoc-in space [:read member channel-id] at))

(defn unread-count [space member channel-id]
  (let [since (get-in space [:read member channel-id])]
    (count (filter #(and (not= member (:message/author %))
                         (or (nil? since) (pos? (compare (:message/at %) since))))
                   (get-in space [:channels channel-id :channel/messages])))))
