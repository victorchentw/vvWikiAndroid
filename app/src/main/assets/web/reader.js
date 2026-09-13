(() => {
  const root = document.getElementById('reader');
  const state = { source: '', repo: '', path: '', docs: '' };
  const searchState = { term: '', index: -1, count: 0 };
  const escapeHtml = (value) => String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  const bridge = () => window.Android || {};

  function contentRoot() {
    return root.querySelector('article') || root;
  }

  function textNodes() {
    const result = [];
    const walker = document.createTreeWalker(contentRoot(), NodeFilter.SHOW_TEXT, {
      acceptNode(node) {
        const parent = node.parentElement;
        if (!parent || /^(SCRIPT|STYLE|NOSCRIPT)$/i.test(parent.tagName)) return NodeFilter.FILTER_REJECT;
        return node.nodeValue ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
      }
    });
    let node;
    while ((node = walker.nextNode())) result.push(node);
    return result;
  }

  function allText() {
    return textNodes().map((node) => node.nodeValue || '').join('');
  }

  function pointOffset(container, offset) {
    try {
      const range = document.createRange();
      range.selectNodeContents(contentRoot());
      range.setEnd(container, offset);
      return range.toString().length;
    } catch (_) {
      return 0;
    }
  }

  function rangeForOffsets(start, end) {
    const nodes = textNodes();
    let cursor = 0;
    let startNode = null;
    let endNode = null;
    let startOffset = 0;
    let endOffset = 0;
    for (const node of nodes) {
      const next = cursor + (node.nodeValue || '').length;
      if (!startNode && start >= cursor && start <= next) {
        startNode = node;
        startOffset = Math.max(0, start - cursor);
      }
      if (end >= cursor && end <= next) {
        endNode = node;
        endOffset = Math.max(0, end - cursor);
        break;
      }
      cursor = next;
    }
    if (!startNode || !endNode) return null;
    const range = document.createRange();
    range.setStart(startNode, startOffset);
    range.setEnd(endNode, endOffset);
    return range;
  }

  function wrapAt(start, end, className, id) {
    const range = rangeForOffsets(start, end);
    if (!range || range.collapsed) return false;
    const marker = document.createElement('mark');
    marker.className = className;
    if (id) marker.dataset.commentId = id;
    try {
      marker.appendChild(range.extractContents());
      range.insertNode(marker);
      return true;
    } catch (_) {
      return false;
    }
  }

  function unwrap(selector) {
    root.querySelectorAll(selector).forEach((element) => {
      const parent = element.parentNode;
      if (!parent) return;
      while (element.firstChild) parent.insertBefore(element.firstChild, element);
      parent.removeChild(element);
      parent.normalize();
    });
  }

  function clearSearchMarks() {
    unwrap('mark.search-hit');
    searchState.term = '';
    searchState.index = -1;
    searchState.count = 0;
  }

  function occurrences(text, term) {
    const result = [];
    if (!term) return result;
    let from = 0;
    while (from <= text.length) {
      const index = text.indexOf(term, from);
      if (index < 0) break;
      result.push(index);
      from = index + Math.max(1, term.length);
    }
    return result;
  }

  function chooseCommentOffset(text, comment) {
    const candidates = occurrences(text, String(comment.quote || ''));
    if (!candidates.length) return -1;
    let best = candidates[0];
    let bestScore = -1;
    candidates.forEach((index) => {
      let score = 0;
      const prefix = String(comment.prefix || '');
      const suffix = String(comment.suffix || '');
      if (prefix && text.slice(Math.max(0, index - prefix.length), index).endsWith(prefix)) score += 2;
      if (suffix && text.slice(index + String(comment.quote || '').length,
          index + String(comment.quote || '').length + suffix.length).startsWith(suffix)) score += 2;
      if (score > bestScore) {
        best = index;
        bestScore = score;
      }
    });
    return best;
  }

  function applyComments(comments) {
    unwrap('mark.comment-highlight');
    let values = comments;
    if (typeof values === 'string') {
      try { values = JSON.parse(values); } catch (_) { values = []; }
    }
    if (!Array.isArray(values)) return;
    const text = allText();
    // Apply from the end so an earlier marker does not change later offsets.
    const matches = values.map((comment) => ({
      comment,
      start: chooseCommentOffset(text, comment)
    })).filter((item) => item.start >= 0).sort((a, b) => b.start - a.start);
    matches.forEach(({ comment, start }) => {
      wrapAt(start, start + String(comment.quote || '').length, 'comment-highlight', String(comment.id || ''));
    });
  }

  function focusComment(commentId) {
    const id = String(commentId || '');
    if (!id) return;
    const target = Array.from(root.querySelectorAll('mark.comment-highlight'))
      .find((node) => node.dataset.commentId === id);
    if (!target) return;
    target.scrollIntoView({ behavior: 'smooth', block: 'center' });
    target.classList.add('comment-focus');
    window.setTimeout(() => target.classList.remove('comment-focus'), 1800);
  }

  function searchText(term, direction) {
    const value = String(term || '');
    if (!value.trim()) {
      clearSearchMarks();
      return JSON.stringify({ index: 0, count: 0 });
    }
    const forward = Number(direction || 1) >= 0 ? 1 : -1;
    if (searchState.term !== value) {
      clearSearchMarks();
      const text = allText();
      const starts = occurrences(text.toLocaleLowerCase(), value.toLocaleLowerCase());
      starts.slice().reverse().forEach((start) => wrapAt(start, start + value.length, 'search-hit'));
      searchState.term = value;
      searchState.count = starts.length;
      searchState.index = forward > 0 ? -1 : 0;
    }
    const hits = Array.from(root.querySelectorAll('mark.search-hit'));
    if (!hits.length) return JSON.stringify({ index: 0, count: 0 });
    searchState.index = (searchState.index + forward + hits.length) % hits.length;
    hits.forEach((hit, index) => hit.classList.toggle('search-active', index === searchState.index));
    hits[searchState.index].scrollIntoView({ behavior: 'smooth', block: 'center' });
    return JSON.stringify({ index: searchState.index + 1, count: hits.length });
  }

  function selectionPayload() {
    const selection = window.getSelection();
    if (!selection || !selection.rangeCount || selection.isCollapsed) return JSON.stringify({});
    const range = selection.getRangeAt(0);
    if (!contentRoot().contains(range.commonAncestorContainer)) return JSON.stringify({});
    const quote = selection.toString().trim();
    if (!quote) return JSON.stringify({});
    const text = allText();
    const start = pointOffset(range.startContainer, range.startOffset);
    const quoteStart = Math.max(0, Math.min(text.length, start));
    return JSON.stringify({
      text: quote,
      prefix: text.slice(Math.max(0, quoteStart - 80), quoteStart),
      suffix: text.slice(quoteStart + quote.length, quoteStart + quote.length + 80)
    });
  }

  function render() {
    clearSearchMarks();
    root.className = 'dark';
    try {
      root.innerHTML = '<article>' + VVWikiRenderer.render(state.source, {
        repo: state.repo, path: state.path, docs: state.docs
      }) + '</article>';
      renderMermaid();
    } catch (error) {
      root.innerHTML = '<div class="render-error"><strong>Rendering failed; showing raw Markdown.</strong><pre class="raw-source">' + escapeHtml(state.source) + '</pre></div>';
      try { bridge().reportRenderError(String(error)); } catch (_) {}
    }
  }

  function renderMermaid() {
    const nodes = Array.from(root.querySelectorAll('.mermaid'));
    if (!nodes.length || !window.mermaid) return;
    try {
      window.mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: 'dark' });
      window.mermaid.run({ nodes }).catch((error) => {
        nodes.forEach((node) => {
          node.classList.add('mermaid-source');
          node.textContent = 'Mermaid rendering failed; source retained.\n\n' + (node.textContent || '');
        });
        try { bridge().reportRenderError(String(error)); } catch (_) {}
      });
    } catch (error) {
      nodes.forEach((node) => node.classList.add('mermaid-source'));
    }
  }

  root.addEventListener('click', (event) => {
    const comment = event.target.closest && event.target.closest('mark.comment-highlight');
    if (comment) {
      event.preventDefault();
      try { bridge().openComment(comment.dataset.commentId || ''); } catch (_) {}
      return;
    }
    const anchor = event.target.closest && event.target.closest('a');
    if (!anchor) return;
    const href = anchor.getAttribute('href') || '';
    if (href.startsWith('wiki://')) {
      event.preventDefault();
      try { bridge().openWikiLink(href); } catch (_) {}
    } else if (href.startsWith('#')) {
      event.preventDefault();
      const id = decodeURIComponent(href.slice(1));
      document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
      try { bridge().rememberFragment(id); } catch (_) {}
    } else if (/^(https?:|mailto:)/i.test(href)) {
      event.preventDefault();
      try { bridge().openExternal(href); } catch (_) {}
    } else if (href && !href.startsWith('javascript:')) {
      event.preventDefault();
      try { bridge().openMarkdownLink(href); } catch (_) {}
    }
  });

  let scrollTimer = null;
  window.addEventListener('scroll', () => {
    if (scrollTimer) clearTimeout(scrollTimer);
    scrollTimer = setTimeout(() => {
      const max = Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
      try { bridge().rememberScroll(window.scrollY || 0, max ? (window.scrollY || 0) / max : 0); } catch (_) {}
    }, 120);
  }, { passive: true });

  window.renderWiki = (source, repo, path, docs) => {
    state.source = String(source || ''); state.repo = String(repo || ''); state.path = String(path || '');
    state.docs = Array.isArray(docs) ? docs : []; render();
  };
  window.findText = (text) => searchText(text, 1);
  window.searchText = (text, direction) => searchText(text, direction);
  window.clearSearch = () => clearSearchMarks();
  window.getSelectionPayload = () => selectionPayload();
  window.applyComments = (comments) => applyComments(comments);
  window.focusComment = (commentId) => focusComment(commentId);
  window.scrollToFragment = (fragment) => {
    const id = decodeURIComponent(String(fragment || '').replace(/^#/, ''));
    document.getElementById(id)?.scrollIntoView({ block: 'start' });
  };
  window.restoreReaderPosition = (y, fraction) => {
    const max = Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
    const target = Number(fraction || 0) > 0 ? max * Number(fraction) : Number(y || 0);
    window.scrollTo(0, Math.max(0, target));
  };
})();
