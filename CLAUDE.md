# DATUM-Editor — エージェントへの指示

## 最初にやること

1. **`HANDOFF.md` を読む。** 役割分担、現在の状態、決まったこと、次にやることが書いてある。
2. 作業が終わったら、`HANDOFF.md` の「現在の状態」「決まったこと」「次にやること」を更新する。

## 役割（詳しくは HANDOFF.md）

- **コミットと push は、主役（通しのエージェント）だけが行う。** 並列エージェントは、担当ファイルを決められた作業だけを行い、終わったら変えたファイルと内容を `HANDOFF.md` に書いて主役に渡す。担当外のファイルは変えない。
- Windows での確認は、push のたびに GitHub Actions（`.github/workflows/ci.yml`）が自動で行う。結果は `gh run list` / `gh run view <id> --log-failed` で見られる。画面の操作が要る確認だけは、`HANDOFF.md` の「Windows で未確認」に書いて、利用者に確認してもらう。

## ビルドとテスト

- ビルド: `mvn -q -B package`（JDK 17 以上。依存の部品はない）。
- テストは JUnit を使わず main で動かす。`mvn package` では走らない。
  - `mvn -q test-compile`
  - `java -cp target/classes:target/test-classes com.example.minieditor.RubyTest`
  - `java -cp target/classes:target/test-classes com.example.minieditor.SaveTest`
  - Windows では `:` を `;` にする。1つでも失敗すると終了コード 1 で終わる。
- push する前に、上のテストが通ることを確かめる。

## 守ること

- **PATH に関わる変更（`CommandRegistration`、`packaging/windows/command-path.ps1`）は、利用者の承認なしに変えない。** PATH はユーザー全体に効く。テスト用に登録したら、最後に必ず元に戻す。
- Google ドライブ（`マイドライブ\小説`）にある実際の原稿を、テストで書き換えない。テストは専用のフォルダを作って、終わったら消す。
- `Build-DATUM-Editor.bat` は利用者が置いた未追跡のファイル。勝手に消さない・コミットしない。
- 既存のコードの書き方に合わせる。コメントは日本語で、量も周りと同じくらいにする。
- 配布 ZIP は、Windows の確認が済んでから、4種類（Lite・Writer・Studio・AI）とも同じ名前で作り直す（`sh packaging/build-all.sh`）。
