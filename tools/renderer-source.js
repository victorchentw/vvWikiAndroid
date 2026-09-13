/*
 * Browser renderer for VV Wiki.
 *
 * This is deliberately built from the same markdown-it family and syntax
 * extensions used by Obsidian_mini. The Android app ships the generated IIFE
 * in assets, so rendering is offline and never evaluates note HTML as code.
 */
const MarkdownIt = require("/mnt/ssd/github/Obsidian_mini/node_modules/markdown-it");
const mark = require("/mnt/ssd/github/Obsidian_mini/node_modules/markdown-it-mark");
const ins = require("/mnt/ssd/github/Obsidian_mini/node_modules/markdown-it-ins");
const sub = require("/mnt/ssd/github/Obsidian_mini/node_modules/markdown-it-sub");
const sup = require("/mnt/ssd/github/Obsidian_mini/node_modules/markdown-it-sup");
const deflist = require("/mnt/ssd/github/Obsidian_mini/node_modules/markdown-it-deflist");
const footnote = require("/mnt/ssd/github/Obsidian_mini/node_modules/markdown-it-footnote");
const abbr = require("/mnt/ssd/github/Obsidian_mini/node_modules/markdown-it-abbr");
const container = require("/mnt/ssd/github/Obsidian_mini/node_modules/markdown-it-container");
const taskLists = require("/mnt/ssd/github/Obsidian_mini/node_modules/markdown-it-task-lists");
const emoji = require("/mnt/ssd/github/Obsidian_mini/node_modules/markdown-it-emoji");
const katex = require("/mnt/ssd/github/Obsidian_mini/node_modules/markdown-it-katex");
const hljs = require("/mnt/ssd/github/Obsidian_mini/node_modules/highlight.js/lib/core");
const languages = {
  javascript: require("/mnt/ssd/github/Obsidian_mini/node_modules/highlight.js/lib/languages/javascript"),
  typescript: require("/mnt/ssd/github/Obsidian_mini/node_modules/highlight.js/lib/languages/typescript"),
  python: require("/mnt/ssd/github/Obsidian_mini/node_modules/highlight.js/lib/languages/python"),
  kotlin: require("/mnt/ssd/github/Obsidian_mini/node_modules/highlight.js/lib/languages/kotlin"),
  java: require("/mnt/ssd/github/Obsidian_mini/node_modules/highlight.js/lib/languages/java"),
  swift: require("/mnt/ssd/github/Obsidian_mini/node_modules/highlight.js/lib/languages/swift"),
  json: require("/mnt/ssd/github/Obsidian_mini/node_modules/highlight.js/lib/languages/json"),
  bash: require("/mnt/ssd/github/Obsidian_mini/node_modules/highlight.js/lib/languages/bash"),
  sql: require("/mnt/ssd/github/Obsidian_mini/node_modules/highlight.js/lib/languages/sql"),
  xml: require("/mnt/ssd/github/Obsidian_mini/node_modules/highlight.js/lib/languages/xml"),
  css: require("/mnt/ssd/github/Obsidian_mini/node_modules/highlight.js/lib/languages/css"),
  yaml: require("/mnt/ssd/github/Obsidian_mini/node_modules/highlight.js/lib/languages/yaml"),
};
Object.keys(languages).forEach((name) => hljs.registerLanguage(name, languages[name]));

const escapeHtml = (value) => String(value)
  .replace(/&/g, "&amp;")
  .replace(/</g, "&lt;")
  .replace(/>/g, "&gt;")
  .replace(/\"/g, "&quot;")
  .replace(/'/g, "&#39;");
const escapeAttr = escapeHtml;

function normalizePath(value) {
  const raw = String(value || "").replace(/\\/g, "/").replace(/^\.\//, "");
  if (!raw || raw.startsWith("/") || raw.split("/").includes("..")) return undefined;
  const parts = [];
  for (const part of raw.split("/")) {
    if (!part || part === ".") continue;
    parts.push(part);
  }
  return parts.join("/");
}

function isImagePath(path) {
  return /\.(?:png|jpe?g|gif|webp|bmp|svg|avif)$/i.test(path);
}

function splitTarget(raw) {
  const value = String(raw || "").trim();
  const pipe = value.indexOf("|");
  const left = pipe >= 0 ? value.slice(0, pipe) : value;
  const alias = pipe >= 0 ? value.slice(pipe + 1).trim() : "";
  const hash = left.indexOf("#");
  return {
    path: hash >= 0 ? left.slice(0, hash) : left,
    heading: hash >= 0 ? left.slice(hash + 1) : "",
    alias,
  };
}

function slugify(value) {
  return String(value || "")
    .toLowerCase()
    .trim()
    .replace(/[`*_~]/g, "")
    .replace(/[^\p{L}\p{N}\s-]/gu, "")
    .replace(/\s+/g, "-")
    .replace(/-+/g, "-") || "section";
}

function resolveTarget(target, context, allowImages = false) {
  const parsed = splitTarget(target);
  let wanted = normalizePath(parsed.path);
  if (!wanted) return { ...parsed, path: undefined };
  const docs = new Set(context.docs || []);
  const imageCandidate = wanted;
  const candidates = [];
  if (context.path && (wanted.startsWith("./") || !wanted.includes("/"))) {
    const base = context.path.includes("/") ? context.path.slice(0, context.path.lastIndexOf("/")) : "";
    if (base) candidates.push(normalizePath(`${base}/${wanted}`));
  }
  candidates.push(wanted);
  if (!/\.[A-Za-z0-9]+$/.test(wanted)) {
    candidates.push(...candidates.map((x) => `${x}.md`));
  }
  const exact = candidates.find((x) => x && docs.has(x));
  if (exact) return { ...parsed, path: exact };
  const basename = wanted.split("/").pop() || wanted;
  const basenameMatches = (context.docs || []).filter((x) => {
    const stem = x.slice(x.lastIndexOf("/") + 1).replace(/\.[^.]+$/, "");
    return stem === basename || x === wanted || x === `${wanted}.md`;
  });
  if (basenameMatches.length === 1) return { ...parsed, path: basenameMatches[0] };
  if (allowImages && isImagePath(imageCandidate)) {
    const image = candidates.find((x) => x && docs.has(x));
    if (image) return { ...parsed, path: image };
  }
  return { ...parsed, path: undefined };
}

function wikiHref(repo, path, heading) {
  const encodedPath = String(path).split("/").map(encodeURIComponent).join("/");
  const hash = heading ? `#${encodeURIComponent(heading)}` : "";
  return `wiki://${encodeURIComponent(repo)}/${encodedPath}${hash}`;
}

function resourceHref(repo, path) {
  const encodedPath = String(path).split("/").map(encodeURIComponent).join("/");
  return `wiki-resource://${encodeURIComponent(repo)}/${encodedPath}`;
}

function installSafeInlineSyntax(md) {
  md.inline.ruler.before("emphasis", "vvwiki_mark_tag", (state, silent) => {
    if (!state.src.startsWith("<mark>", state.pos)) return false;
    const start = state.pos + 6;
    const close = state.src.indexOf("</mark>", start);
    if (close < 0) return false;
    if (!silent) {
      state.push("mark_open", "mark", 1);
      const inner = [];
      md.inline.parse(state.src.slice(start, close), md, state.env, inner);
      state.tokens.push(...inner);
      state.push("mark_close", "mark", -1);
    }
    state.pos = close + 7;
    return true;
  });
  md.inline.ruler.before("html_inline", "vvwiki_anchor_tag", (state, silent) => {
    if (!state.src.startsWith("<a", state.pos)) return false;
    const match = /^<a\s+id=(?:"([^"<>]+)"|'([^'<>]+)')\s*><\/a>/.exec(state.src.slice(state.pos));
    if (!match) return false;
    if (!silent) {
      const token = state.push("html_inline", "", 0);
      token.content = `<span class="ob-anchor" id="${escapeAttr(match[1] || match[2])}"></span>`;
    }
    state.pos += match[0].length;
    return true;
  });
  md.inline.ruler.before("html_inline", "vvwiki_deletion_tag", (state, silent) => {
    if (state.src[state.pos] !== "<") return false;
    const match = /^<(del|s)>([\s\S]*?)<\/\1\s*>/i.exec(state.src.slice(state.pos));
    if (!match) return false;
    if (!silent) {
      const token = state.push("html_inline", "", 0);
      token.content = `<del>${escapeHtml(match[2])}</del>`;
    }
    state.pos += match[0].length;
    return true;
  });
}

function installDetailsSyntax(md) {
  md.block.ruler.before("html_block", "vvwiki_details", (state, startLine, endLine, silent) => {
    const line = (n) => state.src.slice(state.bMarks[n] + state.tShift[n], state.eMarks[n]);
    const opening = /^<details(?:\s+(open))?\s*>\s*$/i.exec(line(startLine));
    if (!opening) return false;
    let closeLine = startLine + 1;
    while (closeLine < endLine && !/^<\/details>\s*$/i.test(line(closeLine))) closeLine++;
    if (closeLine >= endLine) return false;
    if (silent) return true;
    let contentStart = startLine + 1;
    let summary = "Details";
    const summaryMatch = /^<summary>([\s\S]*)<\/summary>\s*$/i.exec(line(contentStart));
    if (summaryMatch) { summary = summaryMatch[1]; contentStart++; }
    const token = state.push("vvwiki_details", "details", 0);
    token.meta = { open: Boolean(opening[1]), summary, content: state.getLines(contentStart, closeLine, 0, false) };
    token.map = [startLine, closeLine + 1];
    state.line = closeLine + 1;
    return true;
  });
  md.renderer.rules.vvwiki_details = (tokens, idx, _options, env) => {
    const meta = tokens[idx].meta;
    return `<details class="obsimini-details"${meta.open ? " open" : ""}><summary>${md.renderInline(meta.summary, env)}</summary>${md.render(meta.content, env)}</details>\n`;
  };
}

function installCallouts(md) {
  const titleFor = (type) => type.replace(/[-_]+/g, " ").replace(/\b\w/g, (x) => x.toUpperCase());
  const iconFor = (type) => ({ note: "📝", info: "ℹ️", tip: "💡", success: "✅", question: "❓", warning: "⚠️", danger: "⚠️", failure: "❌", bug: "🐛" }[type] || "📌");
  md.core.ruler.after("inline", "vvwiki_callout", (state) => {
    for (let i = 0; i < state.tokens.length; i++) {
      const open = state.tokens[i];
      if (open.type !== "blockquote_open") continue;
      let inlineIndex = -1, closeIndex = -1, depth = open.nesting;
      for (let j = i + 1; j < state.tokens.length; j++) {
        depth += state.tokens[j].nesting;
        if (inlineIndex < 0 && state.tokens[j].type === "inline") inlineIndex = j;
        if (depth === 0) { closeIndex = j; break; }
      }
      if (inlineIndex < 0 || closeIndex < 0) continue;
      const inline = state.tokens[inlineIndex];
      const match = /^\[!([\w-]+)\]([+-]?)(?:\s+([^\n]+))?(?:\n|$)/.exec(inline.content);
      if (!match) continue;
      const type = match[1].toLowerCase();
      const meta = { type, title: (match[3] || titleFor(type)).trim(), collapsed: match[2] === "-", expandable: Boolean(match[2]) };
      open.meta = { ...(open.meta || {}), vvwikiCallout: meta };
      state.tokens[closeIndex].meta = { ...(state.tokens[closeIndex].meta || {}), vvwikiCallout: meta };
      const rest = inline.content.slice(match[0].length);
      inline.content = rest;
      inline.children = [];
      md.inline.parse(rest, md, state.env, inline.children);
    }
  });
  const renderToken = (tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options);
  const openDefault = md.renderer.rules.blockquote_open || renderToken;
  const closeDefault = md.renderer.rules.blockquote_close || renderToken;
  md.renderer.rules.blockquote_open = (tokens, idx, options, env, self) => {
    const meta = tokens[idx].meta?.vvwikiCallout;
    if (!meta) return openDefault(tokens, idx, options, env, self);
    const header = `<span class="obsimini-callout-icon">${iconFor(meta.type)}</span><span class="obsimini-callout-title">${escapeHtml(meta.title)}</span>`;
    if (meta.expandable) return `<details class="obsimini-callout obsimini-callout-${escapeAttr(meta.type)}"${meta.collapsed ? "" : " open"}><summary class="obsimini-callout-header">${header}</summary>`;
    tokens[idx].attrJoin("class", `obsimini-callout obsimini-callout-${meta.type}`);
    return openDefault(tokens, idx, options, env, self) + `<div class="obsimini-callout-header">${header}</div>`;
  };
  md.renderer.rules.blockquote_close = (tokens, idx, options, env, self) => {
    const meta = tokens[idx].meta?.vvwikiCallout;
    return meta?.expandable ? "</details>\n" : closeDefault(tokens, idx, options, env, self);
  };
}

function createMarkdown() {
  const md = new MarkdownIt({ html: false, linkify: true, breaks: false, typographer: true });
  md.use(mark);
  md.use(ins).use(sub).use(sup).use(deflist).use(footnote).use(abbr);
  md.use(emoji.full);
  md.use(taskLists, { enabled: false, label: true });
  md.use(katex);
  md.use(container, "obsimini", {
    validate: (params) => new Set(["note", "info", "tip", "success", "question", "warning", "danger", "failure", "bug"]).has(params.trim().split(/\s+/, 1)[0].toLowerCase()),
    render: (tokens, idx, _options, env) => {
      if (tokens[idx].nesting < 0) return "</div>\n";
      const match = /^(\S+)(?:\s+([\s\S]+))?$/.exec(tokens[idx].info.trim());
      const type = match?.[1]?.toLowerCase() || "note";
      const title = match?.[2]?.trim();
      return `<div class="obsimini-container obsimini-container-${escapeAttr(type)}">${title ? `<div class="obsimini-container-title">${md.renderInline(title, env)}</div>` : ""}\n`;
    },
  });
  installSafeInlineSyntax(md);
  installDetailsSyntax(md);
  installCallouts(md);

  md.inline.ruler.before("link", "vvwiki_embed", (state, silent) => {
    if (!state.src.startsWith("![[", state.pos)) return false;
    const end = state.src.indexOf("]]", state.pos + 3);
    if (end < 0) return false;
    const raw = state.src.slice(state.pos + 3, end);
    if (silent) { state.pos = end + 2; return true; }
    const target = resolveTarget(raw, state.env.vvwiki || {}, true);
    const parsed = splitTarget(raw);
    const label = parsed.alias || parsed.path;
    let html;
    if (target.path && isImagePath(target.path)) {
      html = `<img class="ob-embedded-image" src="${resourceHref(state.env.vvwiki.repo, target.path)}" alt="${escapeAttr(label)}" loading="lazy">`;
    } else if (target.path) {
      html = `<a class="ob-note-embed" href="${wikiHref(state.env.vvwiki.repo, target.path, target.heading)}"><strong>${escapeHtml(label)}</strong><span>Open embedded note</span></a>`;
    } else {
      html = `<span class="wl broken">![[${escapeHtml(raw)}]]</span>`;
    }
    const token = state.push("html_inline", "", 0); token.content = html;
    state.pos = end + 2;
    return true;
  });
  md.inline.ruler.before("link", "vvwiki_wikilink", (state, silent) => {
    if (!state.src.startsWith("[[", state.pos)) return false;
    const end = state.src.indexOf("]]", state.pos + 2);
    if (end < 0) return false;
    const raw = state.src.slice(state.pos + 2, end);
    if (silent) { state.pos = end + 2; return true; }
    const target = resolveTarget(raw, state.env.vvwiki || {});
    const parsed = splitTarget(raw);
    const label = parsed.alias || parsed.path || raw;
    const html = target.path
      ? `<a class="wl" href="${wikiHref(state.env.vvwiki.repo, target.path, target.heading)}">${escapeHtml(label)}</a>`
      : `<span class="wl broken" title="Unresolved wiki link">[[${escapeHtml(raw)}]]</span>`;
    const token = state.push("html_inline", "", 0); token.content = html;
    state.pos = end + 2;
    return true;
  });

  const defaultImage = md.renderer.rules.image;
  md.renderer.rules.image = (tokens, idx, options, env, self) => {
    const token = tokens[idx];
    const src = token.attrGet("src") || "";
    if (/^https?:\/\//i.test(src)) {
      return `<a class="blocked-resource" href="${escapeAttr(src)}">External image blocked; open link</a>`;
    }
    const target = resolveTarget(src, env.vvwiki || {}, true);
    if (!target.path) return `<span class="blocked-resource">Image not found: ${escapeHtml(src)}</span>`;
    token.attrSet("src", resourceHref(env.vvwiki.repo, target.path));
    token.attrSet("loading", "lazy");
    return defaultImage ? defaultImage(tokens, idx, options, env, self) : self.renderToken(tokens, idx, options);
  };

  md.options.highlight = (source, language) => {
    const aliases = { js: "javascript", jsx: "javascript", ts: "typescript", tsx: "typescript", py: "python", sh: "bash", shell: "bash", yml: "yaml", html: "xml" };
    const lang = aliases[String(language || "").trim().toLowerCase()] || String(language || "").trim().toLowerCase();
    if (!lang || !hljs.getLanguage(lang)) return "";
    try { return hljs.highlight(source, { language: lang }).value; } catch { return ""; }
  };
  const defaultFence = md.renderer.rules.fence || ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options));
  md.renderer.rules.fence = (tokens, idx, options, env, self) => {
    const token = tokens[idx];
    const language = (token.info || "").trim().split(/\s+/, 1)[0].toLowerCase();
    if (language === "mermaid") {
      return `<div class="obsimini-mermaid"><div class="mermaid">${escapeHtml(token.content)}</div></div>`;
    }
    return defaultFence(tokens, idx, options, env, self);
  };

  md.core.ruler.after("inline", "vvwiki_heading_ids", (state) => {
    const counts = Object.create(null);
    for (let i = 0; i < state.tokens.length; i++) {
      if (state.tokens[i].type !== "heading_open") continue;
      const inline = state.tokens[i + 1];
      const base = slugify(inline?.content || "section");
      const count = counts[base] || 0;
      counts[base] = count + 1;
      state.tokens[i].attrSet("id", count ? `${base}-${count + 1}` : base);
    }
  });
  return md;
}

const markdown = createMarkdown();

function splitFrontmatter(source) {
  const lines = String(source || "").split(/\r?\n/);
  if (lines[0]?.trim() !== "---") return { body: String(source || ""), frontmatter: "" };
  const end = lines.indexOf("---", 1);
  if (end <= 0) return { body: String(source || ""), frontmatter: "" };
  const candidate = lines.slice(1, end).join("\n");
  if (!candidate.split("\n").some((line) => /^[^:#][^:]*:\s*/.test(line.trim()))) return { body: String(source || ""), frontmatter: "" };
  return { frontmatter: candidate, body: lines.slice(end + 1).join("\n") };
}

function renderFrontmatter(source) {
  const rows = String(source).split(/\r?\n/).filter(Boolean).map((line) => {
    const pos = line.indexOf(":");
    if (pos < 0) return `<tr><td colspan="2"><code>${escapeHtml(line)}</code></td></tr>`;
    return `<tr><th>${escapeHtml(line.slice(0, pos).trim())}</th><td>${escapeHtml(line.slice(pos + 1).trim())}</td></tr>`;
  }).join("");
  return `<details class="ob-frontmatter"><summary>Frontmatter</summary><table>${rows}</table></details>`;
}

function render(source, options) {
  const context = {
    repo: String(options?.repo || "vvdoc"),
    path: String(options?.path || ""),
    docs: Array.isArray(options?.docs) ? options.docs : [],
  };
  const split = splitFrontmatter(source);
  return (split.frontmatter ? renderFrontmatter(split.frontmatter) : "") + markdown.render(split.body, { vvwiki: context });
}

globalThis.VVWikiRenderer = { render, slugify };
