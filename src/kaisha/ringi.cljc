(ns kaisha.ringi
  "稟議（社内の承認）と職務分掌。

  承認者の人数は社内規程（:company/approval-policy）で決める。法律が求めるのは、
  取締役会設置会社での重要な財産の処分・多額の借財などを取締役に委任できないこと（362条4項）で、
  その種類の稟議は取締役会の決議がなければ承認済みにしない。
  稟議が承認されても、契約の締結は代表権のある人が行う（349条）。"
  (:require [kaisha.company :as co]))

(def board-matters
  "取締役会の決議を要する種類（362条4項の一部）。"
  #{:important-asset :large-borrowing :important-employee :branch})

(def default-policy
  [{:up-to 100000 :approvers 1}
   {:up-to 1000000 :approvers 2}
   {:up-to nil :approvers 3}])

(defn required-approvers [policy amount]
  (:approvers (first (filter #(or (nil? (:up-to %)) (<= amount (:up-to %)))
                             (or (seq policy) default-policy)))))

(defn- member [c id] (some #(when (= id (:member/id %)) %) (:company/members c)))

(defn route
  "稟議に必要な承認を返す。"
  [c {:ringi/keys [amount category]}]
  (let [board? (and (co/kk? c) (:company/board? c) (contains? board-matters category))]
    {:approvers (required-approvers (:company/approval-policy c) (or amount 0))
     :board-resolution? board?
     :source (when board? :kaisha/a362)}))

(defn status
  "稟議の状態: :draft / :pending / :awaiting-board / :approved / :rejected"
  [c r]
  (let [{:keys [approvers board-resolution?]} (route c r)
        approvals (filter #(= :approve (:decision %)) (:ringi/decisions r))]
    (cond
      (some #(= :reject (:decision %)) (:ringi/decisions r)) :rejected
      (not (:ringi/submitted-on r)) :draft
      (< (count approvals) approvers) :pending
      (and board-resolution?
           (not= :passed (get-in r [:ringi/board-resolution :result :status]))) :awaiting-board
      :else :approved)))

(defn decide
  "承認または却下を記録する。起案者自身、承認権限のない人、二重の判断は拒む。
  返り値 {:ok r} または {:error kw}"
  [c r {:keys [by decision on]}]
  (let [m (member c by)]
    (cond
      (nil? (:ringi/submitted-on r)) {:error :not-submitted}
      (#{:approved :rejected} (status c r)) {:error :already-closed}
      (= by (:ringi/drafter r)) {:error :self-approval}
      (nil? m) {:error :unknown-member}
      (not (contains? (set (:member/permissions m)) :approve)) {:error :no-approval-permission}
      (some #(= by (:by %)) (:ringi/decisions r)) {:error :already-decided}
      (not (#{:approve :reject} decision)) {:error :invalid-decision}
      :else {:ok (update r :ringi/decisions (fnil conj []) {:by by :decision decision :on on})})))

(defn link-board-resolution
  "取締役会の決議を稟議に結びつける。可決した決議だけを受け付ける。"
  [c r resolution]
  (cond
    (not (:board-resolution? (route c r))) {:error :board-resolution-not-required}
    (not= :passed (get-in resolution [:result :status])) {:error :resolution-not-passed}
    (not= (:ringi/category r) (:matter resolution)) {:error :resolution-matter-mismatch}
    :else {:ok (assoc r :ringi/board-resolution resolution)}))

(defn signer-check
  "承認済みの稟議を、誰が会社を代表して締結できるか。"
  [c r officer-id]
  (if (not= :approved (status c r))
    {:status :blocked :reason :ringi-not-approved}
    (co/representative? c officer-id)))
