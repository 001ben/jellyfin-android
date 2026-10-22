const MAX_ITEMS = 80;
const CARD_SELECTOR = '.card[data-id][data-type], .listItem[data-id][data-type]';
const OWN_CLASS = 'smartOfflineNative';

// Jellyfin commonly emits compact GUIDs; the native bridge receives canonical UUIDs.
export function offlineUuid(value) {
    if (typeof value !== 'string') return null;
    if (!/^(?:[0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/i.test(value)) return null;
    const compact = value.replace(/-/g, '').toLowerCase();
    return `${compact.slice(0, 8)}-${compact.slice(8, 12)}-${compact.slice(12, 16)}-${compact.slice(16, 20)}-${compact.slice(20)}`;
}

export function offlineBadge(item) {
    if (!item) return '';
    const phase = String(item.phase || '').toUpperCase();
    let label = {
        QUEUED: 'Queued', DOWNLOADING: 'Downloading', PARTIAL: 'Partly downloaded',
        WAITING: 'Waiting to download', COMPLETE: 'Downloaded', REMOVED: 'Local copy removed',
    }[phase] || '';
    if (phase === 'DOWNLOADING' && typeof item.progress === 'number' && Number.isFinite(item.progress)) {
        label += ` ${Math.floor(Math.max(0, Math.min(1, item.progress)) * 100)}%`;
    }
    if (!label && item.selected) label = 'Selected for automatic downloads';
    if (item.watched && label) label += ' · Watched';
    if (item.pendingSync && label) label += ' · Sync pending';
    if (item.cleanupEligible && label) label += ' · Cleanup eligible';
    return label;
}

export function offlineDetailRoute(hash) {
    const route = String(hash || '').replace(/^#!?/, '');
    const [path, query] = route.split('?');
    if (path !== '/details') return null;
    const params = new URLSearchParams(query || '');
    const id = offlineUuid(params.get('id'));
    const serverId = offlineUuid(params.get('serverId'));
    return id ? { id, serverId } : null;
}

export class SmartOfflinePlugin {
    constructor({ events, ServerConnections }) {
        this.connections = ServerConnections;
        this.bridge = window.SmartOfflineBridge;
        this.pending = new Map();
        this.snapshots = new Map();
        this.rendered = new WeakMap();
        this.bindings = new WeakMap();
        this.sequence = 0;
        this.epoch = 0;
        this.contextKey = '';
        this.detail = null;
        this.refresh = this.refresh.bind(this);
        window.SmartOfflinePlayback = { canPlay: item => this.canPlay(item) };
        // Old WebViews retain the native downloads screen without a less restricted bridge fallback.
        if (!this.bridge || typeof this.bridge.postMessage !== 'function') return;
        this.bridge.onmessage = event => this.onMessage(event);
        this.installStyle();
        events.on(ServerConnections, 'localusersignedin', () => this.invalidate());
        events.on(ServerConnections, 'localusersignedout', () => this.invalidate());
        window.addEventListener('hashchange', () => this.invalidate());
        document.addEventListener('viewshow', () => this.schedule());
        document.addEventListener('visibilitychange', () => this.schedule());
        this.observer = new MutationObserver(records => {
            const external = records.some(record => {
                if (record.target.closest?.(`.${OWN_CLASS}`)) return false;
                const nodes = [...record.addedNodes, ...record.removedNodes];
                return !nodes.length || nodes.some(node => !node.classList?.contains(OWN_CLASS));
            });
            if (external) this.schedule();
        });
        this.observer.observe(document.body, { childList: true, subtree: true, attributes: true,
            attributeFilter: ['data-id', 'data-serverid', 'data-type', 'class'] });
        this.interval = window.setInterval(() => this.schedule(), 5000);
        this.schedule();
    }

    identity() {
        try {
            const api = this.connections.currentApiClient();
            const userId = offlineUuid(api?.getCurrentUserId());
            const serverId = offlineUuid(api?.serverInfo()?.Id);
            return userId && serverId ? { api, userId, serverId, key: `${serverId}/${userId}` } : null;
        } catch {
            return null;
        }
    }

    canPlay(item) {
        const context = this.identity();
        const id = offlineUuid(item?.Id);
        if (!context || context.key !== this.contextKey || offlineUuid(item?.ServerId) !== context.serverId) return false;
        const cached = this.snapshots.get(id);
        return cached?.item.canPlayLocal === true && Date.now() - cached.at < 15000;
    }

    invalidate() {
        this.epoch++;
        this.contextKey = '';
        this.detail = null;
        this.snapshots.clear();
        this.rendered = new WeakMap();
        this.bindings = new WeakMap();
        this.clearNodes();
        for (const pending of this.pending.values()) {
            window.clearTimeout(pending.timer);
            pending.resolve(null);
        }
        this.pending.clear();
        this.schedule();
    }

    schedule() {
        if (this.timer || !this.bridge) return;
        this.timer = window.setTimeout(() => {
            this.timer = null;
            this.refresh();
        }, 120);
    }

    async refresh() {
        if (document.visibilityState === 'hidden') return;
        const context = this.identity();
        if (!context) {
            if (this.contextKey) this.invalidate();
            return;
        }
        if (this.contextKey !== context.key) {
            this.invalidate();
            this.contextKey = context.key;
        }
        const epoch = this.epoch;
        const refreshSequence = this.refreshSequence = (this.refreshSequence || 0) + 1;
        this.resolveDetail(context);
        const cards = [...document.querySelectorAll(CARD_SELECTOR)].filter(card =>
            ['Series', 'Season', 'Episode'].includes(card.dataset.type) &&
            offlineUuid(card.dataset.serverid) === context.serverId &&
            offlineUuid(card.dataset.id) && !card.closest('.hide, [hidden]')
        ).slice(0, MAX_ITEMS);
        const visibleCards = new Set(cards);
        // Virtualized/React rows may reuse an element for another server or item kind.
        for (const node of document.querySelectorAll(`.${OWN_CLASS}`)) {
            const card = node.closest('.card, .listItem');
            if (card && !visibleCards.has(card)) {
                this.removeOwned(card);
                this.rendered.delete(card);
            }
        }
        for (const card of cards) {
            const binding = `${context.key}/${offlineUuid(card.dataset.id)}/${card.dataset.type}`;
            if (this.bindings.has(card) && this.bindings.get(card) !== binding) {
                this.removeOwned(card);
                this.rendered.delete(card);
            }
            this.bindings.set(card, binding);
        }
        const ids = new Set();
        if (this.detail?.item && this.detail.context === context.key) ids.add(offlineUuid(this.detail.item.Id));
        for (const card of cards) if (ids.size < MAX_ITEMS) ids.add(offlineUuid(card.dataset.id));
        if (!ids.size) return;
        const response = await this.request('snapshot', context, { itemIds: [...ids].slice(0, MAX_ITEMS) });
        if (!response || response.error || this.epoch !== epoch || this.identity()?.key !== context.key ||
            this.refreshSequence !== refreshSequence) return;
        const requested = new Set(ids);
        const items = new Map();
        for (const item of (Array.isArray(response.items) ? response.items : []).slice(0, MAX_ITEMS)) {
            const id = offlineUuid(item?.id);
            if (!id || !requested.has(id)) continue;
            items.set(id, item);
            this.snapshots.set(id, { item, at: Date.now() });
        }
        for (const id of ids) if (!items.has(id)) this.snapshots.delete(id);
        while (this.snapshots.size > MAX_ITEMS * 3) this.snapshots.delete(this.snapshots.keys().next().value);
        for (const card of cards) {
            const id = offlineUuid(card.dataset.id);
            if (!requested.has(id) || offlineUuid(card.dataset.serverid) !== context.serverId ||
                !['Series', 'Season', 'Episode'].includes(card.dataset.type) || card.closest('.hide, [hidden]')) continue;
            this.renderCard(card, items.get(id), context);
        }
        this.renderDetail(items, context);
    }

    resolveDetail(context) {
        const route = offlineDetailRoute(window.location.hash);
        if (!route || (route.serverId && route.serverId !== context.serverId)) return;
        const key = `${context.key}/${window.location.hash}`;
        if (this.detail?.key === key) return;
        const epoch = this.epoch;
        this.detail = { key, context: context.key, item: null };
        Promise.resolve().then(() => context.api.getItem(context.userId, route.id)).then(item => {
            if (this.epoch !== epoch || this.detail?.key !== key || this.identity()?.key !== context.key) return;
            if (offlineUuid(item?.Id) !== route.id ||
                (item.ServerId && offlineUuid(item.ServerId) !== context.serverId)) return;
            if (!['Series', 'Season', 'Episode'].includes(item.Type)) return;
            this.detail.item = item;
            this.schedule();
        }).catch(() => {
            // Remote detail metadata is optional; card badges and native offline browsing remain usable.
        });
    }

    request(action, context, fields) {
        if (!this.bridge || this.identity()?.key !== context.key) return Promise.resolve(null);
        const requestId = `offline-${++this.sequence}`;
        return new Promise(resolve => {
            const timer = window.setTimeout(() => {
                this.pending.delete(requestId);
                resolve(null);
            }, 10000);
            this.pending.set(requestId, { resolve, timer, context: context.key, epoch: this.epoch });
            try {
                this.bridge.postMessage(JSON.stringify({ requestId, action, userId: context.userId,
                    serverId: context.serverId, ...fields }));
            } catch {
                window.clearTimeout(timer);
                this.pending.delete(requestId);
                resolve(null);
            }
        });
    }

    onMessage(event) {
        if (typeof event.data !== 'string' || event.data.length > 131072) return;
        let reply;
        try { reply = JSON.parse(event.data); } catch { return; }
        const pending = this.pending.get(reply?.requestId);
        if (!pending) return;
        window.clearTimeout(pending.timer);
        this.pending.delete(reply.requestId);
        pending.resolve(pending.epoch === this.epoch && pending.context === this.identity()?.key ? reply : null);
    }

    renderCard(card, item, context) {
        const label = offlineBadge(item);
        const signature = JSON.stringify([context.key, card.dataset.id, card.dataset.type, label, item?.canPlayLocal]);
        if (this.rendered.get(card) === signature && card.querySelector(`.${OWN_CLASS}`)) return;
        this.rendered.set(card, signature);
        const image = card.querySelector('.cardImageContainer, .listItemImage');
        this.removeOwned(card);
        if (label) {
            const badge = document.createElement('span');
            badge.className = `${OWN_CLASS} ${image ? 'smartOfflineBadge' : 'smartOfflineInlineBadge'}`;
            badge.textContent = label;
            (image || card).appendChild(badge);
        }
        // Image containers are often links and TV card wrappers can be buttons; keep actions outside both.
        if (card.tagName === 'BUTTON' || card.tagName === 'A') return;
        const id = offlineUuid(card.dataset.id);
        if (card.dataset.type === 'Series') {
            card.appendChild(this.action('Automatic downloads', () => this.request('manage', context, { seriesId: id })));
        }
        if (card.dataset.type === 'Episode' && item?.canPlayLocal === true) {
            card.appendChild(this.action('Play downloaded', () => this.request('play', context, { itemId: id })));
        }
    }

    renderDetail(items, context) {
        const item = this.detail?.item;
        const route = offlineDetailRoute(window.location.hash);
        if (!item || !route || offlineUuid(item.Id) !== route.id || this.detail.context !== context.key) return;
        const page = document.querySelector('#itemDetailPage');
        if (!page || page.closest('.hide, [hidden]')) return;
        const container = page.querySelector('.mainDetailButtons');
        if (!container) return;
        const id = offlineUuid(item.Id);
        const seriesId = offlineUuid(item.Type === 'Series' ? item.Id : item.SeriesId);
        const status = items.get(id);
        const signature = JSON.stringify([context.key, id, seriesId, offlineBadge(status), status?.canPlayLocal]);
        if (this.rendered.get(container) === signature && container.querySelector(`.${OWN_CLASS}`)) return;
        this.rendered.set(container, signature);
        this.removeOwned(container);
        if (seriesId) container.appendChild(this.action('Automatic downloads', () => this.request('manage', context, { seriesId })));
        if (item.Type === 'Episode' && status?.canPlayLocal === true) {
            container.appendChild(this.action('Play downloaded', () => this.request('play', context, { itemId: id })));
        }
        const label = offlineBadge(status);
        if (label) {
            const badge = document.createElement('span');
            badge.className = `${OWN_CLASS} smartOfflineDetailStatus`;
            badge.textContent = label;
            badge.setAttribute('role', 'status');
            container.appendChild(badge);
        }
    }

    action(label, callback) {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = `${OWN_CLASS} smartOfflineAction emby-button raised`;
        button.textContent = label;
        button.addEventListener('click', event => {
            event.preventDefault();
            event.stopPropagation();
            // Synthetic DOM clicks are not native user authorization for playback or opening an editor.
            if (!event.isTrusted) return;
            button.disabled = true;
            Promise.resolve().then(callback).then(reply => {
                if (!reply || reply.error) this.actionError(button);
            }, () => this.actionError(button)).finally(() => { button.disabled = false; this.schedule(); });
        });
        return button;
    }

    actionError(button) {
        const parent = button.parentElement;
        if (!parent) return;
        for (const previous of parent.querySelectorAll('.smartOfflineFeedback')) previous.remove();
        const feedback = document.createElement('span');
        feedback.className = `${OWN_CLASS} smartOfflineFeedback`;
        feedback.setAttribute('role', 'status');
        feedback.textContent = 'Could not open item. Open Downloads and try again.';
        parent.appendChild(feedback);
    }

    removeOwned(container) {
        for (const node of container.querySelectorAll(`.${OWN_CLASS}`)) node.remove();
    }

    clearNodes() {
        this.removeOwned(document);
    }

    installStyle() {
        if (document.getElementById('smartOfflineNativeStyle')) return;
        const style = document.createElement('style');
        style.id = 'smartOfflineNativeStyle';
        style.textContent = '.smartOfflineBadge{display:block;position:absolute;bottom:0;left:0;right:0;' +
            'z-index:2;padding:.35em .5em;' +
            'background:#172a38;color:#fff;font-size:.8em;pointer-events:none}' +
            '.smartOfflineInlineBadge{display:block;padding:.35em .5em;background:#172a38;color:#fff;font-size:.8em}' +
            '.smartOfflineFeedback{display:block;padding:.5em;color:#ffd6a0;font-size:.85em}' +
            '.smartOfflineAction{position:relative;z-index:3;white-space:normal;font-size:.85em;margin:.25em;' +
            'min-height:44px;min-width:44px}.smartOfflineDetailStatus{display:inline-block;padding:.5em}';
        document.head.appendChild(style);
    }
}
