'use strict';

// ---------- helpers ----------
const $ = (sel) => document.querySelector(sel);
const state = {
    accountId: localStorage.getItem('accountId') || '',
    instrument: null,
    quote: null,
    expanded: new Set(),
};

async function api(path, options = {}) {
    const res = await fetch(path, {
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        ...options,
        body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
    });
    const text = await res.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch { data = text; }
    if (!res.ok) {
        const msg = (data && (data.message || data.detail || data.title)) || text || res.statusText;
        throw new Error(`${res.status}: ${msg}`);
    }
    return data;
}

function esc(v) {
    return String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function num(v, digits = 2) {
    if (v === null || v === undefined || v === '') return '—';
    const n = Number(v);
    return Number.isFinite(n) ? n.toLocaleString(undefined, { minimumFractionDigits: digits, maximumFractionDigits: Math.max(digits, 4) }) : esc(v);
}

function qty(v) {
    if (v === null || v === undefined) return '—';
    return Number(v).toLocaleString(undefined, { maximumFractionDigits: 9 });
}

function money(v) {
    if (v === null || v === undefined || v === '') return '—';
    return Number(v).toLocaleString(undefined, { style: 'currency', currency: 'USD' });
}

function time(v) {
    if (!v) return '—';
    const d = new Date(v);
    return d.toLocaleString(undefined, { month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function badge(v) {
    return v ? `<span class="badge ${esc(v)}">${esc(String(v).replaceAll('_', ' '))}</span>` : '—';
}

let toastTimer;
function toast(message, isError = false) {
    const el = $('#toast');
    el.textContent = message;
    el.className = 'toast' + (isError ? ' error' : '');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.add('hidden'), isError ? 8000 : 3500);
}

function formData(form) {
    const out = {};
    for (const [k, v] of new FormData(form).entries()) {
        if (v !== '') out[k] = v;
    }
    return out;
}

async function withBusy(button, fn) {
    const label = button.textContent;
    button.disabled = true;
    button.textContent = '…';
    try { return await fn(); } finally { button.disabled = false; button.textContent = label; }
}

// ---------- accounts ----------
async function loadAccounts() {
    const accounts = await api('/api/app/accounts');
    const select = $('#account-select');
    select.innerHTML = accounts.length
        ? accounts.map((a) => `<option value="${esc(a.id)}">${esc(a.given_name ?? '')} ${esc(a.family_name ?? '')} · ${esc(a.account_number ?? 'pending')} · ${esc(a.status)}</option>`).join('')
        : '<option value="">— no accounts —</option>';
    if (!accounts.some((a) => a.id === state.accountId)) {
        state.accountId = accounts[0]?.id || '';
    }
    select.value = state.accountId;
    await selectAccount(state.accountId);
}

async function selectAccount(id) {
    state.accountId = id;
    state.expanded.clear();
    localStorage.setItem('accountId', id);
    await Promise.all([loadAccountDetail(), loadFunding(), loadOrders()]);
}

async function loadAccountDetail() {
    const detail = $('#account-detail');
    const balances = $('#balances');
    if (!state.accountId) {
        detail.innerHTML = 'Select or create an account.';
        balances.innerHTML = '';
        return;
    }
    const a = await api(`/api/app/accounts/${state.accountId}`);
    const t = a.trading;
    detail.classList.remove('muted');
    detail.innerHTML = `<dl>
        <dt>Name</dt><dd>${esc(a.given_name)} ${esc(a.family_name)}</dd>
        <dt>Email</dt><dd>${esc(a.email)}</dd>
        <dt>Account no.</dt><dd>${esc(a.account_number ?? '—')}</dd>
        <dt>Status</dt><dd>${badge(a.status)}</dd>
        <dt>Alpaca id</dt><dd class="small">${esc(a.id)}</dd>
    </dl>`;
    balances.innerHTML = t ? [
        ['Cash', t.cash], ['Buying power', t.buying_power], ['Equity', t.equity], ['Positions', t.long_market_value],
    ].map(([k, v]) => `<div class="balance"><span>${k}</span><span>${money(v)}</span></div>`).join('') : '';
}

async function loadFunding() {
    const body = $('#funding-table tbody');
    if (!state.accountId) { body.innerHTML = ''; return; }
    const rows = await api(`/api/app/accounts/${state.accountId}/funding`);
    body.innerHTML = rows.map((f) => `<tr><td>${time(f.created_at)}</td><td class="num">${money(f.amount)}</td><td>${badge(f.status)}</td></tr>`).join('')
        || '<tr><td colspan="3" class="muted">No deposits yet</td></tr>';
}

$('#account-select').addEventListener('change', (e) => selectAccount(e.target.value).catch((err) => toast(err.message, true)));

$('#create-account-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const btn = e.submitter;
    await withBusy(btn, async () => {
        try {
            const acc = await api('/api/app/accounts', { method: 'POST', body: formData(e.target) });
            state.accountId = acc.id;
            toast(`Account created (${acc.status})`);
            e.target.closest('details').open = false;
            await loadAccounts();
        } catch (err) { toast(err.message, true); }
    });
});

$('#import-account-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    await withBusy(e.submitter, async () => {
        try {
            const acc = await api('/api/app/accounts/import', { method: 'POST', body: formData(e.target) });
            state.accountId = acc.id;
            toast('Account imported');
            e.target.reset();
            await loadAccounts();
        } catch (err) { toast(err.message, true); }
    });
});

$('#fund-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!state.accountId) return toast('Select an account first', true);
    await withBusy(e.submitter, async () => {
        try {
            await api(`/api/app/accounts/${state.accountId}/funding`, { method: 'POST', body: { amount: Number(formData(e.target).amount) } });
            toast('Wire queued — the deposit is confirmed when Alpaca sends the CSD activity');
            await loadFunding();
        } catch (err) { toast(err.message, true); }
    });
});

// ---------- instruments ----------
let searchTimer;
$('#instrument-search').addEventListener('input', (e) => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => searchInstruments(e.target.value), 200);
});

async function searchInstruments(q) {
    const data = await api(`/api/app/instruments?q=${encodeURIComponent(q)}&limit=25`);
    $('#instrument-count').textContent = `${data.total.toLocaleString()} instruments in database`;
    $('#instrument-results').innerHTML = data.results.map((i) => `
        <li data-symbol="${esc(i.symbol)}" class="${state.instrument?.symbol === i.symbol ? 'active' : ''}">
            <span class="sym">${esc(i.symbol)}</span>
            <span class="name">${esc(i.name)}</span>
            <span class="tag">${esc(i.exchange)}${i.fractionable ? ' · fractional' : ''}${i.tradable ? '' : ' · not tradable'}</span>
        </li>`).join('');
}

$('#instrument-results').addEventListener('click', (e) => {
    const li = e.target.closest('li[data-symbol]');
    if (li) selectInstrument(li.dataset.symbol).catch((err) => toast(err.message, true));
});

$('#sync-instruments').addEventListener('click', async (e) => {
    await withBusy(e.currentTarget, async () => {
        try {
            const r = await api('/api/app/instruments/sync', { method: 'POST' });
            toast(`Synced ${r.synced.toLocaleString()} instruments`);
            await searchInstruments($('#instrument-search').value);
        } catch (err) { toast(err.message, true); }
    });
});

async function selectInstrument(symbol) {
    state.instrument = await api(`/api/app/instruments/${encodeURIComponent(symbol)}`);
    $('#ticket').classList.remove('hidden');
    $('#ticket-symbol').textContent = state.instrument.symbol;
    $('#ticket-name').textContent = `${state.instrument.name} · ${state.instrument.exchange}`;
    document.querySelectorAll('#instrument-results li').forEach((li) => li.classList.toggle('active', li.dataset.symbol === symbol));
    orderForm.limitPrice.value = '';
    orderForm.stopPrice.value = '';
    state.quote = null;
    $('#quote').innerHTML = '<div class="meta">Loading quote…</div>';
    updateTicketFields();
    await refreshQuote();
}

async function refreshQuote() {
    if (!state.instrument) return;
    const q = await api(`/api/app/instruments/${encodeURIComponent(state.instrument.symbol)}/quote`, { method: 'POST' });
    state.quote = q;
    const change = q.last_price && q.prev_close ? ((q.last_price - q.prev_close) / q.prev_close) * 100 : null;
    $('#quote').innerHTML = `
        <div class="cell bid"><span>Bid × size</span><span>${num(q.bid_price)} × ${qty(q.bid_size)}</span></div>
        <div class="cell ask"><span>Ask × size</span><span>${num(q.ask_price)} × ${qty(q.ask_size)}</span></div>
        <div class="cell"><span>Last</span><span>${num(q.last_price)}</span></div>
        <div class="cell"><span>Day change</span><span>${change === null ? '—' : (change >= 0 ? '+' : '') + change.toFixed(2) + '%'}</span></div>
        <div class="meta">Day range ${num(q.daily_low)} – ${num(q.daily_high)} · volume ${qty(q.daily_volume)} ·
            feed ${esc(q.feed)} · quote ${time(q.quote_time)} · stored as quote #${esc(q.id)}</div>`;
    const limit = $('#order-form [name=limitPrice]');
    if (!limit.value && q.last_price) limit.value = q.last_price;
    updateEstimate();
}

$('#refresh-quote').addEventListener('click', (e) => withBusy(e.currentTarget, () => refreshQuote().catch((err) => toast(err.message, true))));

// ---------- order ticket ----------
const orderForm = $('#order-form');

function updateTicketFields() {
    const type = orderForm.type.value;
    orderForm.querySelector('.limit-field').classList.toggle('hidden', !['limit', 'stop_limit'].includes(type));
    orderForm.querySelector('.stop-field').classList.toggle('hidden', !['stop', 'stop_limit'].includes(type));
    const btn = $('#submit-order');
    const side = orderForm.side.value;
    btn.textContent = `${side === 'buy' ? 'Buy' : 'Sell'} ${state.instrument?.symbol ?? ''}`;
    btn.style.background = side === 'buy' ? 'var(--buy)' : 'var(--sell)';
    btn.style.borderColor = btn.style.background;
    updateEstimate();
}

function updateEstimate() {
    const q = state.quote;
    const size = Number(orderForm.size.value);
    const est = $('#estimate');
    if (!q || !size) { est.textContent = ''; return; }
    const price = ['limit', 'stop_limit'].includes(orderForm.type.value) && orderForm.limitPrice.value
        ? Number(orderForm.limitPrice.value)
        : Number((orderForm.side.value === 'buy' ? q.ask_price : q.bid_price) || q.last_price);
    est.textContent = orderForm.sizeMode.value === 'qty'
        ? `Estimated ${orderForm.side.value === 'buy' ? 'cost' : 'proceeds'}: ${money(size * price)} at ${num(price)}`
        : `Estimated quantity: ${(size / price).toFixed(4)} shares at ${num(price)}`;
}

orderForm.addEventListener('input', updateTicketFields);
orderForm.addEventListener('change', updateTicketFields);

orderForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!state.accountId) return toast('Select an account first', true);
    if (!state.instrument) return toast('Select an instrument first', true);
    const f = formData(orderForm);
    const body = {
        symbol: state.instrument.symbol,
        side: f.side,
        type: f.type,
        timeInForce: f.timeInForce,
        [f.sizeMode]: Number(f.size),
    };
    if (['limit', 'stop_limit'].includes(f.type)) body.limitPrice = Number(f.limitPrice);
    if (['stop', 'stop_limit'].includes(f.type)) body.stopPrice = Number(f.stopPrice);
    await withBusy($('#submit-order'), async () => {
        try {
            const order = await api(`/api/app/accounts/${state.accountId}/orders`, { method: 'POST', body });
            toast(`Order sent: ${order.side} ${order.symbol} — ${order.status}`);
            state.expanded.add(order.id);
            await loadOrders();
        } catch (err) { toast(err.message, true); }
    });
    updateTicketFields();
});

// ---------- orders & executions ----------
const OPEN = new Set(['new', 'accepted', 'pending_new', 'partially_filled', 'pending_replace', 'accepted_for_bidding', 'held']);

async function loadOrders() {
    const body = $('#orders-table tbody');
    if (!state.accountId) { body.innerHTML = ''; $('#orders-empty').classList.remove('hidden'); return; }
    const orders = await api(`/api/app/accounts/${state.accountId}/orders?limit=50`);
    $('#orders-empty').classList.toggle('hidden', orders.length > 0);
    $('#orders-updated').textContent = `updated ${new Date().toLocaleTimeString()}`;
    body.innerHTML = orders.map(renderOrder).join('');
}

function renderOrder(o) {
    const open = state.expanded.has(o.id);
    const size = o.qty !== null ? qty(o.qty) : money(o.notional);
    const quote = o.quote_id ? `${num(o.quote_bid)} / ${num(o.quote_ask)}` : '—';
    const row = `
        <tr class="order ${open ? 'open' : ''}" data-id="${esc(o.id)}">
            <td><span class="caret">▶</span></td>
            <td>${time(o.created_at)}</td>
            <td><strong>${esc(o.symbol)}</strong></td>
            <td class="side-${esc(o.side)}">${esc(o.side)}</td>
            <td>${esc(o.order_type)} · ${esc(o.time_in_force)}</td>
            <td class="num">${size}</td>
            <td class="num">${num(o.limit_price)}</td>
            <td>${badge(o.status)}</td>
            <td class="num">${qty(o.filled_qty)}</td>
            <td class="num">${num(o.filled_avg_price)}</td>
            <td class="num">${quote}</td>
            <td>${OPEN.has(o.status) ? `<button class="danger cancel" data-id="${esc(o.id)}">Cancel</button>` : ''}</td>
        </tr>`;
    if (!open) return row;
    return row + `<tr class="details"><td colspan="12">${renderDetails(o)}</td></tr>`;
}

function renderDetails(o) {
    const executions = o.executions.length ? `
        <table>
            <thead><tr><th>Executed</th><th class="num">Qty</th><th class="num">Price</th><th class="num">Amount</th>
                <th>Trade date</th><th>Settle date</th><th>Settlement</th><th>Ledger (FILL activity)</th></tr></thead>
            <tbody>${o.executions.map((x) => `
                <tr>
                    <td>${time(x.execution_time)}</td>
                    <td class="num">${qty(x.qty)}</td>
                    <td class="num">${num(x.price)}</td>
                    <td class="num">${money(x.gross_amount)}</td>
                    <td>${esc(x.trade_date)}</td>
                    <td>${esc(x.settle_date ?? '—')}</td>
                    <td>${badge(x.settlement_status)}</td>
                    <td>${x.activity_event_id ? badge(x.activity_status || 'booked') : '<span class="muted">waiting</span>'}</td>
                </tr>`).join('')}
            </tbody>
        </table>` : '<div class="muted">No executions reported yet.</div>';

    const events = o.events.length ? `<ul class="timeline">${o.events.map((ev) => `
        <li class="${esc(ev.event_type)}">${badge(ev.event_type)}
            ${ev.qty ? `${qty(ev.qty)} @ ${num(ev.price)}` : ''}
            <span class="when">${time(ev.event_at)}</span></li>`).join('')}</ul>`
        : '<div class="muted">Waiting for trade events…</div>';

    return `<div class="details-grid details">
        <div><h4>Executions reported by Alpaca</h4>${executions}</div>
        <div><h4>Order lifecycle (trade events)</h4>${events}
            <div class="muted small" style="margin-top:8px">client_order_id ${esc(o.client_order_id)}<br>order id ${esc(o.id)}</div></div>
    </div>`;
}

$('#orders-table').addEventListener('click', async (e) => {
    const cancel = e.target.closest('button.cancel');
    if (cancel) {
        e.stopPropagation();
        try {
            await api(`/api/app/accounts/${state.accountId}/orders/${cancel.dataset.id}`, { method: 'DELETE' });
            toast('Cancel requested — status updates when Alpaca confirms');
        } catch (err) { toast(err.message, true); }
        return;
    }
    const row = e.target.closest('tr.order');
    if (!row) return;
    const id = row.dataset.id;
    state.expanded.has(id) ? state.expanded.delete(id) : state.expanded.add(id);
    loadOrders().catch((err) => toast(err.message, true));
});

// ---------- event stream status ----------
async function loadStreams() {
    try {
        const streams = await api('/api/app/streams');
        $('#streams').innerHTML = streams.map((s) =>
            `<span title="${esc(s.path)}${s.lastError ? '\n' + esc(s.lastError) : ''}\nreceived ${s.received}, stored ${s.stored}">
                <span class="dot ${s.connected ? 'on' : ''}"></span>${esc(s.stream)}</span>`).join('');
    } catch { /* ignore */ }
}

// ---------- boot ----------
async function boot() {
    try {
        await loadAccounts();
        await searchInstruments('');
        const count = await api('/api/app/instruments?q=&limit=1');
        $('#instrument-count').textContent = `${count.total.toLocaleString()} instruments in database`;
    } catch (err) { toast(err.message, true); }
    updateTicketFields();
    loadStreams();
    setInterval(() => loadOrders().catch(() => {}), 3000);
    setInterval(() => { loadAccountDetail().catch(() => {}); loadFunding().catch(() => {}); }, 15000);
    setInterval(loadStreams, 5000);
}

boot();
