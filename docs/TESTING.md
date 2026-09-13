# Test checklist

1. Install the personal `app-release.apk` built with `VVWIKI_SSH_KEY_PATH` (or import an SSH key in Settings for a no-key build).
2. On first launch, wait for startup sync; Library status should show a short commit for both `vvdoc` and `radoc`, not `not synced`.
3. Confirm the APK contains no Markdown fixture files; documents appear only after sync/import.
4. Tap `Sync` again with no remote changes; it should finish without replacing the cache and keep the same commit.
5. Library: open any synced `wiki/` Markdown document.
6. Confirm frontmatter, headings, callout, table, task list, code, Mermaid and
   KaTeX render without network.
7. In Reader, confirm the visible `−`/`+` buttons change rendered text size, and `Search` opens document search. Reader is dark-only; there is no Raw or light-mode toggle.
8. Return to Library, Search `Mermaid`, open the result and confirm source line
   metadata/highlight.
9. Open `radoc / wiki/START_HERE.md`, tap `[[topics/rendering]]`, then use Reader
   back/forward.
10. Settings: import a folder and verify Markdown/text appears; key/certificate
   looking files and `..` paths must not appear. Test SSH key import with a copy
   of `id_rsa`; the UI must show only configured/key count state, never key text.
11. Verify APK:

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --verbose --print-certs app-release.apk
$ANDROID_HOME/build-tools/35.0.0/aapt dump badging app-release.apk
```
