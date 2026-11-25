# 設計の決定（タブ・承認カード・plan/record・`:blocked`/`:undetermined`）

issue [#9](https://github.com/cloud-itonami/app-itonami-kaisha/issues/9)（P0）として、設計 [#8](https://github.com/cloud-itonami/app-itonami-kaisha/issues/8) のコメント [7159115311](https://github.com/cloud-itonami/app-itonami-kaisha/issues/8#issuecomment-7159115311) で実装側が決めた内容を固定する。基準は `app-itonami-kaisha@74c40cc` のコード。**この文書は設計の記録で、コードは変えない。** 以降の P1〜P6 はここを前提にする。

README の画面表は「できること」の一覧、Figma は画面の見た目の参照。タブの数・操作名・state の扱いは、実装側の決定としてこの文書に固定し、Figma 側はこの決定に合わせて更新する。法令の根拠は既存の `src/kaisha/law.cljc` と `docs/evidence/` のままで、ここに新しい法令規則は足さない。

## 決定

1. **タブは 5 つ**（予定・会議・稟議・会計・会話）。行政手続きは「予定」の中、設定は歯車。7 タブにはしない。
2. **承認カード**は、不可逆・外部・権限/所有の変更にだけ、dry-run 付きで出す。read-only と可逆はカードなし。
3. **カード対象 op** は `ringi.decide` / `data.reset-demo` / `officer.verify` / `member.permissions.set` / `meeting.vote` / `books.advance` の 6 つ。
4. 端末内の不可逆操作は **app 内のカード**。外部操作（AwaiFS・e-Gov・kaikei）は **kotoba-client-runtime broker の操作ごとの同意だけ**を使う（`AGENTS.md`）。app 独自の承認 UI・proxy は作らない。
5. `procedure` は **`plan`（純粋、state を変えない）** と **`record`（`:procedures` 追記と監査）** に分ける。
6. egov の **`:blocked`（要対応）** と **`:undetermined`（制度外・未確定）** を混同しない。代表権未確認は `:blocked`。
7. state に派生値（`:needs-board-resolution`、`:ops`）を持たない。画面状態は **`:screen/status`** として投影し、永続化しない。
8. **`:actor`** を state と監査に持つ（`:actor/id`、`:actor/kind` は `:human` / `:agent`）。member に agent を混ぜない。
9. 表示は **phone 幅（375px 以上）** で操作できること。1280×800 の設計だけで判断しない。

## タブ

| タブ | 画面 ID | 中身 |
|---|---|---|
| 予定 | `kaisha.schedule` | 任期満了の見込み、変更登記の期限、事業年度末、定時総会の最終日、法人税の申告期限、招集通知の最終日。行政手続き（本店移転の候補・期限）もこの中 |
| 会議 | `kaisha.meeting` | 招集通知、決議要件、議事録の保存期間 |
| 稟議 | `kaisha.ringi` | 承認人数、自己承認の禁止、取締役会決議が要る種類、締結できる代表者 |
| 会計 | `kaisha.books` | 試算表の取り込みと貸借確認、作成 → 監査 → 取締役会承認 → 定時総会承認 → 公告 |
| 会話 | `kaisha.chat` | チャンネル、非公開チャンネル、1 段のスレッド、DM、メンション、既読 |

設定は各タブの右上の歯車から開く（ログインではない）。設計 SVG の 7 タブ案は採らない。README の画面表にある「行政手続き」「設定」の行は機能の一覧であり、タブが 7 つあるという意味ではない。

## 承認カードと op 表

### 方針

- **不可逆**（確定記録を作る・state を壊す）、**外部**（端末外に効力が及ぶ）、**権限/所有の変更**（代表権・メンバー権限）は、dry-run 付きのカードを出してから確定する。
- **read-only** と**可逆**（既存の確定記録を消さず、同じ op か別の op で値を戻せる／追記だけ）はカードを出さない。
- dry-run は state を変えない純関数。カードを閉じた・キャンセルしたときは state も監査も変えない。確定したときだけ `dispatch` が state を変え、監査に 1 行足す。
- 今回の 22 op はすべて端末内。外部操作はまだ `descriptor.json` の `operations` に出していない（空のまま）。将来の外部操作は、この app のカードではなく broker の同意で行う。**app 内カードを外部提出の同意の代わりにしない。**

### 一覧

`kaisha.app/dispatch` の `step` が受けるのは次の 21 イベント（`:unknown-event` は拒否）。`data.reset-demo` は P3 で足す 22 行目で、現行のリセットは dispatch を通らない（後述）。

| op | dispatch イベント | 画面 | 可逆性 | カード |
|---|---|---|---|---|
| `session.act-as` | `:session/as` | 設定 | 可逆（別の member に切り替え直せる。監査対象外） | なし |
| `company.update` | `:company/set` | 設定 | 可逆（同じ op で戻せる） | なし |
| `company.address.set` | `:company/address` | 設定 | 可逆（同じ op で戻せる） | なし |
| `officer.add` | `:officer/add` | 設定 | 可逆（`company.update` で役員一覧を戻せる。変更登記の期限を予定に足す） | なし |
| `officer.verify` | `:officer/verify` | 設定 | 権限/所有（代表権の確認。取り消し専用 op はない） | **あり（dry-run）** |
| `member.add` | `:member/add` | 設定 | 可逆（`company.update` で戻せる） | なし |
| `member.permissions.set` | `:member/set-permissions` | 設定 | 権限（同じ op で戻せる） | **あり（dry-run）** |
| `registration.add` | `:registration/add` | 予定 | 可逆（予定の追記。既存の確定記録を変えない） | なし |
| `procedure.head-office-move.record` | `:procedure/head-office-move` | 予定（行政手続き） | 可逆（追記のみ。plan は read） | なし |
| `meeting.create` | `:meeting/add` | 会議 | 可逆（開催前の予定の追記） | なし |
| `meeting.notice.mark-sent` | `:meeting/notice-sent` | 会議 | 可逆（日付を入れ直せる） | なし |
| `meeting.vote` | `:meeting/vote` | 会議 | 不可逆（議事録の決議を確定する） | **あり（dry-run）** |
| `ringi.create` | `:ringi/create` | 稟議 | 可逆（下書きの追記） | なし |
| `ringi.submit` | `:ringi/submit` | 稟議 | 可逆（提出の印。承認記録は `decide` まで作らない） | なし |
| `ringi.decide` | `:ringi/decide` | 稟議 | 不可逆（承認・却下の記録を確定する） | **あり（dry-run）** |
| `ringi.link-board` | `:ringi/link-board` | 稟議 | 可逆（追記のみ。承認は `decide`） | なし |
| `books.import` | `:books/import` | 会計 | 可逆（`advance` 前は取り込み直せる。`advance` 後は `:financials-in-progress` で拒否） | なし |
| `books.advance` | `:books/advance` | 会計 | 不可逆（計算書類の段階を進め、履歴に残す） | **あり（dry-run）** |
| `chat.post` | `:chat/post` | 会話 | 可逆（追記のみ。削除専用 op はない） | なし |
| `chat.read` | `:chat/read` | 会話 | read-only（既読の更新のみ。監査対象外） | なし |
| `chat.open-dm` | `:chat/open-dm` | 会話 | 可逆（DM を開く。既存の会話を変えない） | なし |
| `data.reset-demo` | `:data/reset-demo`（P3） | 設定 | 不可逆（state を架空データに置き換える） | **あり（dry-run）** |

`procedure.head-office-move.plan` は dispatch イベントを持たない read op なので、この表の外に置く（後述の「`procedure` の `plan` と `record`」）。`schedule.list` も同じく read op で、この表には入れない。

「可逆（追記のみ）」は、取り消し専用の op がないことを認めたうえで、既存の確定記録を消さない操作を指す。`meeting.vote` と `ringi.decide` と `books.advance` は確定記録を作るので不可逆、`officer.verify` と `member.permissions.set` は権限/所有の変更なので、可逆でもカードにする。

### dry-run で見せるもの

| op | dry-run の内容 |
|---|---|
| `ringi.decide` | 対象の稟議、金額、起案者、現在の決議、必要な承認数、取締役会決議の要否（`ringi/route` から都度計算）、自己承認・権限なし・二重判断の拒否理由、決定後の状態 |
| `data.reset-demo` | 消える件数（会社・会議・稟議・会計履歴・会話メッセージ・登録・手続き・監査行）と、戻す先（架空商事株式会社の fixture）。件数は state からの純関数で出す |
| `officer.verify` | 対象の役員、確認日、何を見て確認したか（登記事項証明書など）、代表権表示の変化（`:unverified` → `:ok`） |
| `member.permissions.set` | 対象の member、変更前 → 変更後、影響する操作（`:approve` / `:post`） |
| `meeting.vote` | 会議・議案、票数、定足数と必要な賛成数、結果。`:blocked` / `:undetermined` では実行できないことを表示する |
| `books.advance` | 現在の段階 → 次の段階、必要な決議の参照、貸借不一致・段階飛ばしの拒否理由 |

`officer.verify` の現行実装は `:officer/authority-verified-on`（日付）しか残さない（`src/kaisha/app.cljc:107-112`）。カードにする P2 で「何を見たか」の欄を足す。それまでは、日付だけで代表権を確認したことにしない。

現行の `company.update`（`:company/set`）は `:company/officers` と `:company/members` を丸ごと差し替えられる（`src/kaisha/app.cljc:101-106`）。このままだと `officer.verify` / `member.permissions.set` のカードを迂回できるので、P2 で patch の許可項目を絞るか、役員・メンバーの変更を専用 op に寄せる。カードは UI だけでなく dispatch の入口でも守る。

`data.reset-demo` は現行では dispatch を通らず、UI が `app/sample` で state を差し替える（`ui/kaisha/ui.cljs:1007-1008`）。`:reset/body` は文章だけで、失われる件数の dry-run も監査もない（`src/kaisha/i18n.cljc:261`）。P3 で `:data/reset-demo` を第一級の dispatch イベントにし、件数の純関数と、新しい state に 1 行の監査を足す。本 P0 ではコードを変えない。

## `procedure` の `plan` と `record`

| op | 役割 | state | 監査 |
|---|---|---|---|
| `procedure.head-office-move.plan` | 会社・移転内容・今日から候補手続きを計算する（`proc/head-office-move-plan`） | 変えない | 書かない |
| `procedure.head-office-move.record` | 移転の記録を `:procedures` に追記する（dispatch イベント `:procedure/head-office-move`） | 追記する | 1 行書く |

現行は UI が先に記録し、そのあと plan を計算している（`ui/kaisha/ui.cljs:657`）。P4 で分離する。`plan` は記録前の入力にも呼べる純粋な read op とし、`record` は明示的な 1 回だけの追記とする。`plan` の結果は `:status` が `:candidates` / `:blocked` / `:undetermined` のいずれかで、記録の有無とは独立する。

## `:blocked` と `:undetermined`

egov（`vendor/egov/src/itonami/egov/core.cljc`）の返す 2 つを混同しない。

| status | 意味 | 例 | 画面 |
|---|---|---|---|
| `:blocked` | 前提が欠けているので止める。人の対応や記録の修正で解消しうる | 代表権が未確認（`:representative-unconfirmed`）、招集通知の期限が足りない、制度パックが古い・権限がない、票数が数えられない | 理由と、次に開く画面（例: 役員）を出す |
| `:undetermined` | 制度が収録されていない・事実が足りないので結論を出さない。推測しない | 国をまたぐ移転、制度にない市町村、対象外の会社形態、手続きがパックにない、特別決議、事実の不足 | 理由を出して止める。「確認すれば進める」とは書かない |

代表権未確認は `:blocked :representative-unconfirmed`（`vendor/egov/src/itonami/egov/core.cljc` の `kaisha-plan`）。設計 SVG の `procedures_undetermined.svg` が `:undetermined` と表示しているのは誤りで、この文書を正とする。`meeting.vote` は `:blocked` / `:undetermined` のどちらでも `:vote-not-countable` で拒否する（`src/kaisha/app.cljc:189`）。

`:unreviewed` は専門家レビューの状態（`:review/status`）であり、`:blocked` / `:undetermined` とは別の軸。画面の loading / empty / error にも混ぜない。

## state に持たない派生値と `:screen/status`

- `:needs-board-resolution` は state に持たない。`ringi/route` が `:ringi/category` と `:ringi/amount` から毎回計算する（`src/kaisha/ringi.cljc:25-30`）。state に置くとドリフトする。
- `:ops` と `:disabled-reason` も state に持たない。画面・`:actor`・権限・現在の state から都度投影する。
- 画面の状態は `:screen/status` として投影する。値は `:loading` / `:ready` / `:empty` / `:error` と、ドメイン結果の `:blocked` / `:undetermined`（理由付き）。永続化しない。
- `:screen/status` にするのは、既存の `:agenda/result :status` や plan の `:status` と衝突させないため。`:status` を画面 state のトップレベルに置かない。
- 現行の `valid-state?` は `:schema` `:company` `:space` `:procedures` `:meetings` `:ringi` しか見ない（`src/kaisha/app.cljc:283-286`）。`:screen/status` や `:actor` を足す P1 では schema v2 と v1 からの移行（または作り直し）を用意する。localStorage の key も v1 固定のままにしない（`ui/kaisha/ui.cljs:17`）。

## `:actor`（人と agent）

state と監査の両方に、操作した人・agent を持つ。

```edn
{:actor {:actor/id "yamada" :actor/kind :human}}
```

- `:actor/kind` は `:human` / `:agent`。
- 監査の 1 行は `{:at … :actor {…} :type … :target …}`。現行は `:actor` に member id の文字列を入れている（`src/kaisha/app.cljc:278`）。P1 で map にする。
- `:company/members` は人だけ。agent を member に混ぜない。`:session/as` で切り替えるのも人だけ。
- 内部の役職は代表権の証明にならない（`AGENTS.md`）。`:actor/kind` が `:agent` でも `:human` でも、登記事項で確認するまで代表権は `:unverified`。
- agent が起案・提出まで行い、`ringi.decide` を人が行う案（P2）は、`kind` だけでは実装できない。権限の判定と agent 面（P6、broker が前提）は別に決める。本 P0 では決めない。

## 語彙

- 試算表の貸借不一致は **`:unbalanced-trial-balance`** を正本とする（`src/kaisha/books.cljc:87`、`src/kaisha/i18n.cljc:78`）。設計 SVG の `:books-unbalanced` は使わない。docs・SVG・i18n を足すときもこの語に合わせる。
- `schedule.list` は **read op** であり、`dispatch` イベントからは作れない。予定は会社・会議・登録・手続きの state から投影する一覧で、状態を変えるイベントを持たない。SVG の Spec/ops に `schedule.list` があっても、21 イベントの op 表には入れない。必要なら `schedule.list` を read-only の投影として別に定義する。

## 表示

- phone 幅（375px 以上）で、予定・会議・稟議・会計・会話・設定・行政手続きのすべてを操作できること。1280×800 の設計だけで判断しない。
- 日本語と英語の両方を phone 幅で確認する（`AGENTS.md`）。
- 状態は loading / empty / error に加えて、`:blocked` / `:undetermined` / `:unreviewed` を出す。`:blocked` は次の行動を、`:undetermined` は「判定しない」理由を、`:unreviewed` は専門家レビュー前であることを示す。

## 次の PR

本 P0 は docs のみ。コードは次の順で分ける（各 PR は新しいブランチ、マージは Kougi）。

| 段階 | 内容 |
|---|---|
| P1 | state v2（`:screen/status`・`:actor`）と v1 からの移行、test |
| P2 | 承認カードと dry-run の純関数（この文書の 6 op） |
| P3 | `data.reset-demo` を op 化（件数の dry-run、監査） |
| P4 | `procedure` の plan / record 分離 |
| P5 | 表示の突き合わせ（phone 幅を含む） |
| P6 | agent 面（descriptor / broker 経路）。broker の合意が前提の別 issue。app 独自 API は作らない |

不可逆操作の dry-run と人の承認カードは外さない。

## 参照

- issue #9: https://github.com/cloud-itonami/app-itonami-kaisha/issues/9
- 設計 #8 の実装側コメント: https://github.com/cloud-itonami/app-itonami-kaisha/issues/8#issuecomment-7159115311
- `src/kaisha/app.cljc`（`step` / `dispatch` / `valid-state?`）
- `src/kaisha/ringi.cljc`（`route` / `decide`）
- `src/kaisha/books.cljc`（`advance`、`:unbalanced-trial-balance`）
- `src/kaisha/procedures.cljc`（`head-office-move-plan`）
- `vendor/egov/src/itonami/egov/core.cljc`（`blocked` / `undetermined` / `kaisha-plan`）
- `ui/kaisha/ui.cljs`（`reset-dialog`、`procedure-view`）
- `src/kaisha/i18n.cljc`（`:error/unbalanced-trial-balance`、`:reset/body`）
- README・AGENTS.md（外部操作は broker、代表権は登記事項で確認、phone 幅）
