const fs = require("fs");
const vm = require("vm");
const path = require("path");

const bundle = fs.readFileSync(path.join(__dirname, "../app/src/main/assets/web/markdown-renderer.js"), "utf8");
const context = { console, globalThis: null };
context.globalThis = context;
vm.runInNewContext(bundle, context, { timeout: 15_000 });
if (!context.VVWikiRenderer) throw new Error("renderer API missing");

const fixturePath = process.env.VVWIKI_RENDER_FIXTURE || "/mnt/ssd/github/Obsidian_mini/test.md";
const fixture = fs.readFileSync(fixturePath, "utf8");
const html = context.VVWikiRenderer.render(fixture, {
  repo: "vvdoc",
  path: "wiki/Obsidian_mini_test.md",
  docs: ["wiki/README.md", "wiki/Obsidian_mini_test.md"],
});
for (const marker of ["<table", "task-list-item", "katex", "obsimini-callout", "obsimini-mermaid"]) {
  if (!html.includes(marker)) throw new Error(`fixture missing ${marker}`);
}
const malicious = "<script>alert(1)</script>\n\n[[README]]";
const safe = context.VVWikiRenderer.render(malicious, {
  repo: "vvdoc", path: "wiki/README.md", docs: ["wiki/README.md"],
});
if (safe.includes("<script>")) throw new Error("raw script was not escaped");
if (!safe.includes("wiki://vvdoc/wiki/README.md")) throw new Error("wiki link missing");
console.log(`renderer tests passed (${html.length} bytes)`);
