(() => {
  const root = document.getElementById('reader');
  const state = { source: '', repo: '', path: '', docs: [] };
  const escapeHtml = (value) => String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  const bridge = () => window.Android || {};

  function render() {
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

  document.addEventListener('click', (event) => {
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

  window.renderWiki = (source, repo, path, docs) => {
    state.source = String(source || ''); state.repo = String(repo || ''); state.path = String(path || '');
    state.docs = Array.isArray(docs) ? docs : []; render();
  };
  window.findText = (text) => { if (text) window.find(String(text)); };
  window.scrollToFragment = (fragment) => {
    const id = decodeURIComponent(String(fragment || '').replace(/^#/, ''));
    document.getElementById(id)?.scrollIntoView({ block: 'start' });
  };
})();
