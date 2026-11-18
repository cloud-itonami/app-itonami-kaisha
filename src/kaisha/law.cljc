;; 法令の出典。e-Gov 法令 API v2 で 2026-11-18 に取得し、応答 JSON の sha256 を記録した。
;; 専門家レビューは未実施（:review/status :unreviewed）。判定は「候補」と「確認事項」までで、
;; 法的な判断・代理・提出を装わない（AWAI-155 / AWAI-3423）。
(ns kaisha.law)

(def pack
  {:pack/id :jp/kaisha-corporate-operations
   :pack/version "2026.11.18-1"
   :pack/jurisdiction [:jp]
   :pack/checked-on "2026-11-18"
   :pack/review-by "2027-02-18"
   :review/status :unreviewed
   :review/reviewer nil})

(def sources
  {:kaisha/a296
   {:title "会社法 第296条（定時株主総会の招集）"
    :url "https://laws.e-gov.go.jp/law/417AC0000000086"
    :api "https://laws.e-gov.go.jp/api/2/law_data/417AC0000000086?law_full_text_format=json&elm=MainProvision-Article_296"
    :law-revision-id "417AC0000000086_20260812_508AC0000000064"
    :in-force-from "2026-08-12"
    :retrieved-on "2026-11-18"
    :sha256 "37d4da89d425cd9f0a9159dae3dca0e9cb9d17d68bddf11e30a432556c56de57"}
   :kaisha/a299
   {:title "会社法 第299条（株主総会の招集の通知）"
    :url "https://laws.e-gov.go.jp/law/417AC0000000086"
    :api "https://laws.e-gov.go.jp/api/2/law_data/417AC0000000086?law_full_text_format=json&elm=MainProvision-Article_299"
    :law-revision-id "417AC0000000086_20260812_508AC0000000064"
    :in-force-from "2026-08-12"
    :retrieved-on "2026-11-18"
    :sha256 "7dbd07384398c5c001b72325c616ef1a3f10e6f9f138f15aab41810fc331f2e5"}
   :kaisha/a309
   {:title "会社法 第309条（株主総会の決議）"
    :url "https://laws.e-gov.go.jp/law/417AC0000000086"
    :api "https://laws.e-gov.go.jp/api/2/law_data/417AC0000000086?law_full_text_format=json&elm=MainProvision-Article_309"
    :law-revision-id "417AC0000000086_20260812_508AC0000000064"
    :in-force-from "2026-08-12"
    :retrieved-on "2026-11-18"
    :sha256 "96ec37f7f94ea6afc3b0b9e779bddf55938f8cb8e754c49516846cd201bec92d"}
   :kaisha/a318
   {:title "会社法 第318条（株主総会の議事録）"
    :url "https://laws.e-gov.go.jp/law/417AC0000000086"
    :api "https://laws.e-gov.go.jp/api/2/law_data/417AC0000000086?law_full_text_format=json&elm=MainProvision-Article_318"
    :law-revision-id "417AC0000000086_20260812_508AC0000000064"
    :in-force-from "2026-08-12"
    :retrieved-on "2026-11-18"
    :sha256 "385f643fa6829b4c85c9aa319f4fce13cce755962b3bf14bb6039f7ccdfd5feb"}
   :kaisha/a332
   {:title "会社法 第332条（取締役の任期）"
    :url "https://laws.e-gov.go.jp/law/417AC0000000086"
    :api "https://laws.e-gov.go.jp/api/2/law_data/417AC0000000086?law_full_text_format=json&elm=MainProvision-Article_332"
    :law-revision-id "417AC0000000086_20260812_508AC0000000064"
    :in-force-from "2026-08-12"
    :retrieved-on "2026-11-18"
    :sha256 "34d30c81837eb6afb14d95eee4a08c269ca98cac81b17a1c41cf1c31d21d6d72"}
   :kaisha/a336
   {:title "会社法 第336条（監査役の任期）"
    :url "https://laws.e-gov.go.jp/law/417AC0000000086"
    :api "https://laws.e-gov.go.jp/api/2/law_data/417AC0000000086?law_full_text_format=json&elm=MainProvision-Article_336"
    :law-revision-id "417AC0000000086_20260812_508AC0000000064"
    :in-force-from "2026-08-12"
    :retrieved-on "2026-11-18"
    :sha256 "58c2184e547aae309b44cb4e568653c9c378478205ea6bee73fb3a656ca33371"}
   :kaisha/a341
   {:title "会社法 第341条（役員の選任及び解任の決議）"
    :url "https://laws.e-gov.go.jp/law/417AC0000000086"
    :api "https://laws.e-gov.go.jp/api/2/law_data/417AC0000000086?law_full_text_format=json&elm=MainProvision-Article_341"
    :law-revision-id "417AC0000000086_20260812_508AC0000000064"
    :in-force-from "2026-08-12"
    :retrieved-on "2026-11-18"
    :sha256 "cdfdad7752fbcc7f1f31563b0c556c2707511c88dff01ee302a9f1848057c0bf"}
   :kaisha/a349
   {:title "会社法 第349条（株式会社の代表）"
    :url "https://laws.e-gov.go.jp/law/417AC0000000086"
    :api "https://laws.e-gov.go.jp/api/2/law_data/417AC0000000086?law_full_text_format=json&elm=MainProvision-Article_349"
    :law-revision-id "417AC0000000086_20260812_508AC0000000064"
    :in-force-from "2026-08-12"
    :retrieved-on "2026-11-18"
    :sha256 "c7f337b02b367aaaba160b7216e4ec4083f76a3ac1512d4be78adfa3a4007373"}
   :kaisha/a362
   {:title "会社法 第362条（取締役会の権限等）"
    :url "https://laws.e-gov.go.jp/law/417AC0000000086"
    :api "https://laws.e-gov.go.jp/api/2/law_data/417AC0000000086?law_full_text_format=json&elm=MainProvision-Article_362"
    :law-revision-id "417AC0000000086_20260812_508AC0000000064"
    :in-force-from "2026-08-12"
    :retrieved-on "2026-11-18"
    :sha256 "b16077e4db542a52dd8489a13c08f9b926d0254a9e247ace30edc72ae56214b0"}
   :kaisha/a368
   {:title "会社法 第368条（取締役会の招集手続）"
    :url "https://laws.e-gov.go.jp/law/417AC0000000086"
    :api "https://laws.e-gov.go.jp/api/2/law_data/417AC0000000086?law_full_text_format=json&elm=MainProvision-Article_368"
    :law-revision-id "417AC0000000086_20260812_508AC0000000064"
    :in-force-from "2026-08-12"
    :retrieved-on "2026-11-18"
    :sha256 "b283e6c58c611c61d53f026a6954b643e63574169490a25281403940d6f51990"}
   :kaisha/a369
   {:title "会社法 第369条（取締役会の決議）"
    :url "https://laws.e-gov.go.jp/law/417AC0000000086"
    :api "https://laws.e-gov.go.jp/api/2/law_data/417AC0000000086?law_full_text_format=json&elm=MainProvision-Article_369"
    :law-revision-id "417AC0000000086_20260812_508AC0000000064"
    :in-force-from "2026-08-12"
    :retrieved-on "2026-11-18"
    :sha256 "9563e12c08c4e5d5fcdcf935fc315fa714eeb2b8dc7422a347726beb7b52005b"}
   :kaisha/a371
   {:title "会社法 第371条（取締役会の議事録等）"
    :url "https://laws.e-gov.go.jp/law/417AC0000000086"
    :api "https://laws.e-gov.go.jp/api/2/law_data/417AC0000000086?law_full_text_format=json&elm=MainProvision-Article_371"
    :law-revision-id "417AC0000000086_20260812_508AC0000000064"
    :in-force-from "2026-08-12"
    :retrieved-on "2026-11-18"
    :sha256 "6b555c4a3cffd2612f1b9c2b296811d241875553ce0a830bbd926d2c02587626"}
   :kaisha/a435
   {:title "会社法 第435条（計算書類等の作成及び保存）"
    :url "https://laws.e-gov.go.jp/law/417AC0000000086"
    :api "https://laws.e-gov.go.jp/api/2/law_data/417AC0000000086?law_full_text_format=json&elm=MainProvision-Article_435"
    :law-revision-id "417AC0000000086_20260812_508AC0000000064"
    :in-force-from "2026-08-12"
    :retrieved-on "2026-11-18"
    :sha256 "86bbc361cc1919df70df81e0abd29246a5d62a38c86c1f1e7459a925c38806c7"}
   :kaisha/a436
   {:title "会社法 第436条（計算書類等の監査等）"
    :url "https://laws.e-gov.go.jp/law/417AC0000000086"
    :api "https://laws.e-gov.go.jp/api/2/law_data/417AC0000000086?law_full_text_format=json&elm=MainProvision-Article_436"
    :law-revision-id "417AC0000000086_20260812_508AC0000000064"
    :in-force-from "2026-08-12"
    :retrieved-on "2026-11-18"
    :sha256 "8692555d7bc3c64aabfe384dc37a96c7612e66000b884f0523136770e7eabddc"}
   :kaisha/a438
   {:title "会社法 第438条（計算書類等の定時株主総会への提出等）"
    :url "https://laws.e-gov.go.jp/law/417AC0000000086"
    :api "https://laws.e-gov.go.jp/api/2/law_data/417AC0000000086?law_full_text_format=json&elm=MainProvision-Article_438"
    :law-revision-id "417AC0000000086_20260812_508AC0000000064"
    :in-force-from "2026-08-12"
    :retrieved-on "2026-11-18"
    :sha256 "3d6a97a7d9f0daff292bfe11e442efee126fa8c13961e3466aefaa21664ab7b6"}
   :kaisha/a440
   {:title "会社法 第440条（計算書類の公告）"
    :url "https://laws.e-gov.go.jp/law/417AC0000000086"
    :api "https://laws.e-gov.go.jp/api/2/law_data/417AC0000000086?law_full_text_format=json&elm=MainProvision-Article_440"
    :law-revision-id "417AC0000000086_20260812_508AC0000000064"
    :in-force-from "2026-08-12"
    :retrieved-on "2026-11-18"
    :sha256 "975bb35251715cf22cc5af7aaa1ad6ccdcefaf26de37edc7437a5e32ae32f1d3"}
   :kaisha/a915
   {:title "会社法 第915条（変更の登記）"
    :url "https://laws.e-gov.go.jp/law/417AC0000000086"
    :api "https://laws.e-gov.go.jp/api/2/law_data/417AC0000000086?law_full_text_format=json&elm=MainProvision-Article_915"
    :law-revision-id "417AC0000000086_20260812_508AC0000000064"
    :in-force-from "2026-08-12"
    :retrieved-on "2026-11-18"
    :sha256 "48bdca4c76f3685e4463760d511a9cb9aae5d5c981d6b4ee03b57501aab6321a"}
   :hojinzei/a74
   {:title "法人税法 第74条（確定申告）"
    :url "https://laws.e-gov.go.jp/law/340AC0000000034"
    :api "https://laws.e-gov.go.jp/api/2/law_data/340AC0000000034?law_full_text_format=json&elm=MainProvision-Article_74"
    :law-revision-id "340AC0000000034_20261001_508AC0000000012"
    :in-force-from "2026-10-01"
    :retrieved-on "2026-11-18"
    :sha256 "ad9c80c7e324e20d5d322f2d2d4c68e2f81be259a96705e40e9b33b5b257b9f8"}})

(defn cite
  "出典 id から表示用の {:id :title :url} を返す。未知の id は例外（出典なしの判定を作らないため）。"
  [id]
  (if-let [s (get sources id)]
    {:id id :title (:title s) :url (:url s)}
    (throw (ex-info "unknown source" {:id id}))))

(defn stale?
  "パックの review-by を過ぎていれば true。"
  [today]
  (neg? (compare (:pack/review-by pack) today)))


(defn short-title
  "画面に常に出す短い出典名（「会社法 第368条」）。括弧の見出しは除く。"
  [id]
  (let [t (:title (cite id))
        i (.indexOf ^String t "（")]
    (if (pos? i) (subs t 0 i) t)))
