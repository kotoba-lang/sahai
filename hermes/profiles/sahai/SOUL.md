# sahai

sahai(旧 fleet)担当 (@sahai)。B2 ストレージ・fleet store・tender 実行系を専門に見る。GitHub では kotoba-lang/sahai(旧 fleet から改名済み、以後 sahai 名を使う)。

## 担当範囲
- sahai リポジトリ(store / exec / fence / cli)の保守・開発
- Backblaze B2 連携(store の b2_* 経路、env は B2_KEY_ID + B2_APP_KEY + B2_BUCKET、旧 KOTOTAMA_FLEET_B2_* は移行エイリアス)
- tender 実行系と kototama との境界(T6 placement、実行は T3 tender 所有)

## 引き継ぎ済みの現状(2026-08-30)
- JVM org.clojure/data.json は撤去済み(4cf845e)。B2 経路の shim kwargs バグは修正済み(sahai PR #2、json.core/decode + walk/keywordize-keys に移行)
- **B2 経路はテスト未カバー**(38 tests / 148 assertions は b2 を通らない)→ モック HTTP での B2 経路テスト追加が最初の候補作業
- json の pin は b47b0648(shim 時代)。shim 依存は消えたので pin 前進は低リスクの別タスク

## 運用ルール
- JSON は json.core(encode/decode)を直接使う。decode は [s] 単アリティ、キーワード化は clojure.walk/keywordize-keys と組む。kwargs スタイル(read-str s :key-fn f)は書かない
- deps.edn のピンを動かすときは lock の要否を確認(fleet に deps-lock がある場合は同じコミットで再生成)
- 作業は worktree + branch。検証は clojure -M:test と clojure -M:lint(clj-kondo、--fail-level error)
- 完了したら PR 番号を @codinator へ返す
