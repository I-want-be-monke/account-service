/* UI enhancements only; finance commands stay server-side POST forms. */
(() => {
  'use strict';
  document.body.classList.add('ui-enhanced');
  const body = document.body, sidebar = document.querySelector('[data-sidebar]');
  const toggle = document.querySelector('[data-nav-toggle]'), collapse = document.querySelector('[data-sidebar-collapse]');
  const backdrop = document.querySelector('[data-nav-backdrop]'), stage = document.querySelector('.app-stage');
  let returnFocus;
  const navOpen = open => {
    if (!sidebar || !toggle) return;
    body.classList.toggle('nav-open', open); toggle.setAttribute('aria-expanded', String(open));
    toggle.setAttribute('aria-label', open ? 'Закрыть основное меню' : 'Открыть основное меню');
    if (backdrop) backdrop.hidden = !open;
    if (stage) stage.inert = open;
    if (open) { returnFocus = document.activeElement; sidebar.querySelector('[data-nav-close]')?.focus(); }
    else if (returnFocus && document.contains(returnFocus)) { returnFocus.focus(); returnFocus = null; }
  };
  toggle?.addEventListener('click', () => navOpen(!body.classList.contains('nav-open')));
  backdrop?.addEventListener('click', () => navOpen(false));
  document.querySelector('[data-nav-close]')?.addEventListener('click', () => navOpen(false));
  sidebar?.addEventListener('keydown', event => {
    if (!body.classList.contains('nav-open')) return;
    if (event.key === 'Escape') { event.preventDefault(); navOpen(false); }
    if (event.key === 'Tab') {
      const focusable = [...sidebar.querySelectorAll('a[href],button:not([disabled])')].filter(el => el.getClientRects().length);
      const first = focusable[0], last = focusable.at(-1);
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    }
  });
  const collapsed = value => {
    body.classList.toggle('nav-collapsed', value); collapse?.setAttribute('aria-expanded', String(!value));
    collapse?.setAttribute('aria-label', value ? 'Развернуть боковое меню' : 'Свернуть боковое меню');
  };
  try { collapsed(localStorage.getItem('account-service.sidebar') === 'collapsed'); } catch { }
  collapse?.addEventListener('click', () => {
    const value = !body.classList.contains('nav-collapsed'); collapsed(value);
    try { localStorage.setItem('account-service.sidebar', value ? 'collapsed' : 'expanded'); } catch { }
  });
  window.matchMedia('(max-width: 991px)').addEventListener('change', () => navOpen(false));
  document.querySelectorAll('[data-client-picker]').forEach(picker => {
    const input = picker.querySelector('[data-client-query]'), id = picker.querySelector('[data-client-id]');
    const list = picker.querySelector('[data-client-results]'), status = picker.querySelector('[data-client-status]');
    if (!input || !id || !list || !status) return;
    picker.setAttribute('data-enhanced', '');
    let timer, request, selected = -1, items = [], sequence = 0;
    const close = () => { list.hidden = true; input.setAttribute('aria-expanded', 'false'); input.removeAttribute('aria-activedescendant'); selected = -1; };
    const announce = message => { status.textContent = message; };
    const select = index => {
      const item = items[index]; if (!item) return;
      id.value = item.id; input.value = item.name; announce('Выбран клиент: ' + item.name); close(); input.focus();
      input.dispatchEvent(new Event('change', { bubbles: true }));
    };
    const highlight = index => {
      if (!items.length) return; selected = (index + items.length) % items.length;
      [...list.children].forEach((option, i) => option.setAttribute('aria-selected', String(i === selected)));
      input.setAttribute('aria-activedescendant', list.children[selected].id); list.children[selected].scrollIntoView({ block: 'nearest' });
    };
    const search = async () => {
      const query = input.value.trim(); request?.abort(); const current = ++sequence;
      if (query.length < 2) { close(); announce(query ? 'Введите не менее двух символов.' : (picker.dataset.activeOnly === 'true' ? 'Выберите активную карточку для клиентской учётной записи.' : 'Пустое поле включает всех клиентов.')); return; }
      request = new AbortController(); announce('Поиск клиентов…');
      try {
        const url = new URL(picker.dataset.searchUrl, location.origin); url.searchParams.set('q', query);
        if (picker.dataset.activeOnly === 'true') url.searchParams.set('activeOnly', 'true');
        const response = await fetch(url, { credentials: 'same-origin', headers: { Accept: 'application/json' }, signal: request.signal });
        if (response.redirected) throw new Error('Сеанс завершён. Обновите страницу и войдите снова.');
        if (!response.ok) { const problem = await response.json().catch(() => null); throw new Error(problem?.detail || (response.status === 403 ? 'Поиск клиента недоступен для вашей роли.' : 'Не удалось выполнить поиск. Повторите попытку.')); }
        const results = await response.json(); if (current !== sequence) return;
        items = Array.isArray(results) ? results.slice(0, 20) : []; list.replaceChildren(); selected = -1;
        items.forEach((item, index) => {
          const option = document.createElement('li'); option.id = list.id + '-' + index;
          option.setAttribute('role', 'option'); option.setAttribute('aria-selected', 'false');
          option.textContent = item.name + (item.identifier ? ' · ' + item.identifier : '');
          option.addEventListener('pointerdown', event => event.preventDefault()); option.addEventListener('click', () => select(index)); list.append(option);
        });
        list.hidden = items.length === 0; input.setAttribute('aria-expanded', String(items.length > 0));
        announce(items.length ? 'Найдено: ' + items.length + '. Выберите клиента стрелками и Enter.' + (items.length === 20 ? ' Уточните имя для точного поиска.' : '') : 'Клиенты не найдены. Измените запрос.');
      } catch (error) { if (error.name !== 'AbortError' && current === sequence) { close(); picker.removeAttribute('data-enhanced'); announce(error.message + ' Используйте кнопку «Найти» для серверного поиска.'); } }
    };
    input.addEventListener('input', () => { id.value = ''; ++sequence; request?.abort(); clearTimeout(timer); close(); timer = setTimeout(search, 250); });
    input.addEventListener('keydown', event => {
      if (event.key === 'ArrowDown' && !list.hidden) { event.preventDefault(); highlight(selected + 1); }
      else if (event.key === 'ArrowUp' && !list.hidden) { event.preventDefault(); highlight(selected - 1); }
      else if (event.key === 'Enter' && !list.hidden && selected >= 0) { event.preventDefault(); select(selected); }
      else if (event.key === 'Escape' && !list.hidden) { event.preventDefault(); close(); }
    });
    input.addEventListener('blur', () => setTimeout(close, 150));
    picker.querySelector('[data-client-clear]')?.addEventListener('click', () => { clearTimeout(timer); ++sequence; request?.abort(); input.value = ''; id.value = ''; close(); announce(picker.dataset.activeOnly === 'true' ? 'Клиент не выбран. Для клиентской роли выберите карточку.' : 'Все клиенты. Фильтр сброшен.'); input.focus(); });
    input.form?.addEventListener('submit', event => {
      if (input.value.trim() && !id.value && event.submitter?.name !== 'searchClients') { event.preventDefault(); announce('Выберите клиента из результатов поиска или очистите поле для всех клиентов.'); input.focus(); search(); }
    });
  });
  document.querySelector('#print-statement')?.addEventListener('click', () => window.print());
  document.querySelectorAll('form input[required],form select[required],form textarea[required]').forEach((field, index) => {
    if (field.closest('.login-form')) return;
    const label = field.labels?.[0];
    if (label) { const mark = document.createElement('span'); mark.className = 'required-mark'; mark.textContent = '*'; mark.setAttribute('aria-hidden', 'true'); if (label.contains(field)) label.insertBefore(mark, field); else label.append(mark); }
    let message;
    field.addEventListener('invalid', () => {
      if (!message) { message = document.createElement('p'); message.className = 'field-error'; message.id = 'validation-' + index; message.setAttribute('aria-live', 'polite'); field.insertAdjacentElement('afterend', message); }
      message.textContent = field.validationMessage;
      field.setAttribute('aria-invalid', 'true');
      const described = new Set((field.getAttribute('aria-describedby') || '').split(/\s+/).filter(Boolean)); described.add(message.id); field.setAttribute('aria-describedby', [...described].join(' '));
    });
    field.addEventListener('input', () => { if (message && field.validity.valid) { field.removeAttribute('aria-invalid'); message.textContent = ''; } });
  });
})();
