# Test checklist

1. Install `app-release.apk`; import the SSH private key in Settings (the APK must not contain it).
2. On first launch, wait for startup sync; Library status should show a short commit for both `vvdoc` and `radoc`, not `fixtures`.
3. Tap `Sync` again with no remote changes; it should finish without replacing the cache and keep the same commit.
4. Library: open `vvdoc / wiki/Obsidian_mini_test.md` or any synced `wiki/` document.
5. Confirm frontmatter, headings, callout, table, task list, code, Mermaid and
   KaTeX render without network.
6. In Reader, confirm the visible `−`/`+` buttons change rendered text size, and `Search` opens document search. Reader is dark-only; there is no Raw or light-mode toggle.
7. Return to Library, Search `Mermaid`, open the result and confirm source line
   metadata/highlight.
8. Open `radoc / wiki/START_HERE.md`, tap `[[topics/rendering]]`, then use Reader
   back/forward.
9. Settings: import a folder and verify Markdown/text appears; key/certificate
   looking files and `..` paths must not appear. Test SSH key import with a copy
   of `id_rsa`; the UI must show only configured/key count state, never key text.
10. Verify APK:

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --verbose --print-certs app-release.apk
$ANDROID_HOME/build-tools/35.0.0/aapt dump badging app-release.apk
```
