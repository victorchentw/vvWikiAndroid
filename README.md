# vv知識酷 Android

可離線瀏覽兩個 Wiki 的 Android MVP（v0.1.7）。Markdown Reader 以
`/mnt/ssd/github/Obsidian_mini` Wiki View 語法為相容性基準，Reader 為
Reader 不會直接修改來源 Wiki；註解可另存到明確指定的暫存 Git branch。現在已加入
GitHub/GitLab 的 read-only SSH sync；預設同步兩個 remote 的 `wiki/`，不是把整個大型
repository clone 到手機。

## 已可測試功能

- `vvdoc`／`radoc` 同步後的 Library 瀏覽（APK 不內建 Markdown）
- Android file picker 匯入 Wiki 資料夾
- 本機全文搜尋（中英文、repo filter、snippet、行號）
- dark-only rendered Markdown 閱讀、頁內 Search（上一筆／下一筆與暗色高亮）、文字縮放（−／＋）與雙指縮放
- portrait safe-area、landscape 隱藏 status bar、旋轉後恢復閱讀位置；文件位置會保存供下次開啟
- 最近閱讀與 Pin 快速入口；表格會依目前 WebView 寬度限制並換行
- 長壓文字可使用系統 Process Text 選單（例如翻譯），也可加入 comment／question，高亮後從 Notes 查閱
- headings、tables、task lists、code highlight、KaTeX、Mermaid、callout、
  footnote、safe HTML subset、`[[wiki links]]` 與本機 note/image resource
- back/forward navigation、fragment jump、外部 HTTP(S) link 交由瀏覽器開啟
- path traversal、secret-looking path、任意 HTML/script 與 WebView network load
  防護
- 啟動時與手動 Git sync／rescan loading：`victorchentw/vvdoc@vv_note`、
  `victor.chen.tw/ra_doc@main`；顯示 commit SHA、檔案數與錯誤狀態
- JGit partial/blob-filter sync：`vvdoc` 只抓 `wiki/` 下 Markdown，`radoc` 依
  allowlist 抓取；處理刪除與 commit unchanged fast path

## Build

```bash
./gradlew :app:assembleDebug
```

Release 必須由本機環境提供 wiki 紀錄的 signing material；secret 不在 repo：

```bash
export VVWIKI_SIGNING_STORE=/path/to/victor.keystore.jks
export VVWIKI_SIGNING_PASSWORD='(from local secret store)'
export VVWIKI_SIGNING_ALIAS=victor
# personal APK: bundle the SSH key so startup/manual sync works without import
export VVWIKI_SSH_KEY_PATH=/mnt/ssd/vvdoc/key/id_rsa
./gradlew :app:assembleRelease
```

Release task 會拒絕沒有 signing key 的 build。產物為
`app/build/outputs/apk/release/app-release.apk`。

Renderer source 在 `tools/renderer-source.js`；generated browser bundle 在
`app/src/main/assets/web/markdown-renderer.js`。Bundle 使用 Obsidian_mini
目前安裝的 markdown-it/plugin family 產生，APK 不依賴 CDN。

## Security boundary

- 這是個人自用 APK；可用 `VVWIKI_SSH_KEY_PATH` 將 SSH private key 注入 APK，
  讓啟動／手動 sync 直接可用。key 不進 Git、source 或 log；不要把這個 APK
  分享給別人，若外流要立即 revoke/rotate key。也可不注入，改在 Settings 匯入。
- Wiki sync 只做 SSH clone/fetch/read；註解匯出另使用固定的
  `vvwiki/android-comments` branch，Settings／Notes 內可明確上傳或刪除；host key 使用
  bundled pinned `known_hosts`，不使用 `StrictHostKeyChecking=no`。
- APK 不內建任何 Markdown／Wiki fixture。Git sync 只 materialize allowlisted `wiki/`
  files；`vvdoc` 只同步 Markdown，`.git`、credential、certificate 與 traversal
  paths 不進 offline cache。
- Gemini function-calling、Room/FTS5 indexing 與 WorkManager 尚未實作。
- 實際 release fingerprint 應以 `apksigner verify --print-certs` 驗證。
