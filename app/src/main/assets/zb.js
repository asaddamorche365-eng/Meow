(function () {
  if (window.ZB) return;
  function q(s, r) { return (r || document).querySelector(s); }
  function qa(s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); }
  function rectOf(el) {
    if (!el) return null;
    var r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return null;
    return { x: r.left + r.width / 2, y: r.top + r.height / 2, w: r.width, h: r.height,
             iw: window.innerWidth, ih: window.innerHeight };
  }
  function articles() { return qa('article[data-testid="tweet"]'); }
  function idOf(a) {
    var ts = qa('a[href*="/status/"] time', a);
    for (var i = 0; i < ts.length; i++) {
      var an = ts[i].closest('a');
      var m = an && (an.getAttribute('href') || '').match(/^\/([^\/]+)\/status\/(\d+)/);
      if (m) return { handle: m[1], id: m[2] };
    }
    return { handle: '', id: '' };
  }
  function info(a) {
    var k = idOf(a);
    var t = q('[data-testid="tweetText"]', a);
    var ad = false;
    var lines = (a.innerText || '').split('\n');
    for (var i = 0; i < lines.length; i++) {
      var l = lines[i].trim();
      if (l === 'Ad' || l === 'Promoted') { ad = true; break; }
    }
    return { id: k.id, handle: k.handle, text: t ? t.innerText : '', ad: ad,
             liked: !!q('[data-testid="unlike"]', a) };
  }
  function find(id) {
    var as = articles();
    for (var i = 0; i < as.length; i++) if (idOf(as[i]).id === id) return as[i];
    return null;
  }
  function scope() {
    if (location.pathname.indexOf('/compose') === 0) return document;
    return q('[aria-modal="true"]') || q('[role="dialog"]');
  }
  window.ZB = {
    own: function () {
      var a = q('[data-testid="AppTabBar_Profile_Link"]');
      var h = a && a.getAttribute('href');
      return h ? h.replace('/', '') : '';
    },
    posts: function () {
      var out = [];
      articles().forEach(function (a) { var i = info(a); if (i.id) out.push(i); });
      return out;
    },
    rect: function (id, what) {
      var a = find(id);
      if (!a) return null;
      var el = what === 'like' ? q('[data-testid="like"]', a) : q('[data-testid="reply"]', a);
      if (!el) return null;
      var H = window.innerHeight;
      var r = el.getBoundingClientRect();
      if (r.top < 70 || r.bottom > H - 70) {
        window.scrollBy(0, r.top - H / 2);
      }
      return rectOf(el);
    },
    composer: function () {
      var s = scope();
      var box = s ? q('[data-testid="tweetTextarea_0"]', s) : null;
      var send = s ? (q('[data-testid="tweetButton"]', s) || q('[data-testid="tweetButtonInline"]', s)) : null;
      return {
        open: !!box, box: rectOf(box), send: rectOf(send),
        sendEnabled: !!send && send.getAttribute('aria-disabled') !== 'true' && !send.disabled,
        len: box ? box.innerText.trim().length : 0
      };
    },
    type: function (text) {
      var s = scope();
      var box = s ? q('[data-testid="tweetTextarea_0"]', s) : null;
      if (!box) return -1;
      box.focus();
      var ok = false;
      try { ok = document.execCommand('insertText', false, text); } catch (e) {}
      if (!ok || !box.innerText.trim()) {
        try {
          var dt = new DataTransfer();
          dt.setData('text/plain', text);
          box.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }));
        } catch (e) {}
      }
      return box.innerText.trim().length;
    },
    scroll: function (px) { window.scrollBy(0, px); return window.scrollY; },
    state: function () {
      var t = (document.body && document.body.innerText) || '';
      var p = location.pathname;
      var err = '';
      if (p.indexOf('/i/flow/login') === 0 || p.indexOf('/login') === 0 || q('[data-testid="loginButton"]')) err = 'login';
      else if (t.indexOf('might be automated') >= 0) err = 'automated';
      else if (t.indexOf('Something went wrong') >= 0) err = 'wrong';
      return { url: location.href, err: err };
    }
  };
})();
