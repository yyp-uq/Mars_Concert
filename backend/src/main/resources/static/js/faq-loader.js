/* 从后端加载 FAQ 并渲染（复用 functional.css 的 .faq-item 样式与手风琴交互）
 *
 * SPA 适配：artist 视图片段由 router.js 通过 fetch + innerHTML 注入，
 * 片段内的 <script> 不会执行，且 #faqList 在首页加载时并不存在。
 * 因此这里用 MutationObserver 监听 #faqList 出现后再拉取数据渲染。
 */
(function () {
  let started = false;

  function esc(s) {
    // 简单转义，防止 XSS（answer 来自后台，仍按不可信文本处理）
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function render(listEl, faqs) {
    if (!faqs.length) {
      listEl.innerHTML = '<div class="faq-loading">No questions yet.</div>';
      return;
    }
    listEl.innerHTML = faqs.map(f => `
      <div class="faq-item">
        <button class="faq-question">
          ${esc(f.questionEn)}
          <span class="faq-question__icon"></span>
        </button>
        <div class="faq-answer">
          <div class="faq-answer__inner">${esc(f.answer)}</div>
        </div>
      </div>
    `).join('');

    // 绑定手风琴：与 functional.js 中相同的同步测量逻辑
    const items = listEl.querySelectorAll('.faq-item');
    items.forEach(item => {
      const q = item.querySelector('.faq-question');
      const a = item.querySelector('.faq-answer');
      q.addEventListener('click', () => {
        const isOpen = item.classList.contains('is-open');
        items.forEach(other => {
          other.classList.remove('is-open');
          other.querySelector('.faq-answer').style.maxHeight = '0px';
        });
        if (!isOpen) {
          item.classList.add('is-open');
          a.style.maxHeight = 'none';
          const inner = a.querySelector('.faq-answer__inner');
          const h = inner ? inner.offsetHeight : a.scrollHeight;
          a.style.maxHeight = (h || 200) + 'px';
        }
      });
    });
  }

  async function load(listEl) {
    try {
      const data = await MarsApi.get('/faqs', { status: 1, pageNum: 1, pageSize: 50 });
      render(listEl, data.list || []);
    } catch (e) {
      listEl.innerHTML = '<div class="faq-loading">Failed to load: ' + esc(e.message) + '</div>';
    }
  }

  function tryStart() {
    if (started) return;
    const listEl = document.getElementById('faqList');
    if (!listEl) return;
    started = true;
    load(listEl);
  }

  // defer 脚本执行时 body 已解析完毕；监听后续注入的 artist 视图
  tryStart();
  const observer = new MutationObserver(() => tryStart());
  observer.observe(document.body, { childList: true, subtree: true });
})();
