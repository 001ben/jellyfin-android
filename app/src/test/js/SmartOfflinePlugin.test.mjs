import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const source = await readFile(new URL('../../main/assets/native/SmartOfflinePlugin.js', import.meta.url), 'utf8');
const { SmartOfflinePlugin, offlineUuid, offlineBadge, offlineDetailRoute } =
    await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

const SERVER = '11111111-1111-1111-1111-111111111111';
const USER = '22222222-2222-2222-2222-222222222222';
const OTHER = '33333333-3333-3333-3333-333333333333';
const EPISODE = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const SERIES = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
const compact = id => id.replaceAll('-', '');

// A deliberately small DOM fixture for the selectors used by both classic and React Jellyfin Web 10.11.
class Element {
    constructor(tagName = 'div', className = '', dataset = {}) {
        this.tagName = tagName.toUpperCase();
        this.className = className;
        this.dataset = dataset;
        this.children = [];
        this.listeners = new Map();
        this.attributes = {};
        this.textContent = '';
        this.classList = { contains: value => this.className.split(/\s+/).includes(value) };
    }

    appendChild(child) { this.children.push(child); child.parentElement = this; return child; }
    remove() {
        this.parentElement.children = this.parentElement.children.filter(child => child !== this);
        this.parentElement = null;
    }
    setAttribute(name, value) { this.attributes[name] = value; }
    addEventListener(name, listener) { this.listeners.set(name, listener); }
    matches(selector) {
        if (selector.startsWith('#')) return this.id === selector.slice(1);
        const className = /^\.([\w-]+)/.exec(selector)?.[1];
        if (className && !this.classList.contains(className)) return false;
        for (const [, attr] of selector.matchAll(/\[([^\]]+)\]/g)) {
            if (attr === 'hidden' && !this.hidden) return false;
            if (attr.startsWith('data-') && this.dataset[attr.slice(5)] === undefined) return false;
        }
        return Boolean(className || selector.startsWith('['));
    }
    closest(selector) {
        if (selector.split(',').some(part => this.matches(part.trim()))) return this;
        return this.parentElement?.closest(selector) || null;
    }
    querySelectorAll(selector) {
        return this.children.flatMap(child => [
            ...(selector.split(',').some(part => child.matches(part.trim())) ? [child] : []),
            ...child.querySelectorAll(selector),
        ]);
    }
    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
    click(trusted = true) {
        const event = { isTrusted: trusted, prevented: false, stopped: false,
            preventDefault() { this.prevented = true; }, stopPropagation() { this.stopped = true; } };
        this.listeners.get('click')?.(event);
        return event;
    }
}

function fixture({ bridgeEnabled = true, hash = '', autoReply = true, item = null } = {}) {
    const root = new Element();
    const head = root.appendChild(new Element('head'));
    const body = root.appendChild(new Element('body'));
    root.head = head;
    root.body = body;
    root.visibilityState = 'visible';
    root.createElement = tag => new Element(tag);
    root.getElementById = id => root.querySelector(`#${id}`);
    const timers = new Map();
    const messages = [];
    const eventHandlers = new Map();
    let timerId = 0;
    let activeUser = USER;
    const api = { getCurrentUserId: () => activeUser, serverInfo: () => ({ Id: SERVER }),
        getItem: async () => item };
    const bridge = {
        postMessage(raw) {
            const message = JSON.parse(raw);
            messages.push(message);
            if (autoReply) bridge.reply(message, message.action === 'snapshot' ?
                message.itemIds.map(id => ({ id, phase: 'COMPLETE', canPlayLocal: id === EPISODE })) : []);
        },
        reply(message, items, error) {
            bridge.onmessage?.({ data: JSON.stringify({ requestId: message.requestId, items, error }) });
        },
    };
    globalThis.document = root;
    globalThis.window = { location: { hash }, addEventListener: (event, callback) => eventHandlers.set(event, callback),
        setTimeout: callback => { timers.set(++timerId, callback); return timerId; },
        clearTimeout: id => timers.delete(id), setInterval: () => ++timerId,
        ...(bridgeEnabled ? { SmartOfflineBridge: bridge } : {}) };
    globalThis.MutationObserver = class { constructor(callback) { this.callback = callback; } observe() {} };
    const dependencies = { ServerConnections: { currentApiClient: () => api },
        events: { on: (_, name, callback) => eventHandlers.set(name, callback) } };
    const plugin = new SmartOfflinePlugin(dependencies);
    const card = (id, type = 'Episode', serverId = SERVER, className = 'card', tag = 'div') => {
        const node = body.appendChild(new Element(tag, className, {
            id: compact(id), type, serverid: compact(serverId),
        }));
        node.appendChild(new Element('div', 'cardImageContainer'));
        return node;
    };
    return { plugin, root, body, bridge, messages, card, eventHandlers,
        setUser: value => { activeUser = value; } };
}

const owned = node => node.querySelectorAll('.smartOfflineNative');
const action = (node, label) => owned(node).find(element => element.tagName === 'BUTTON' && element.textContent === label);
const settle = async () => { for (let i = 0; i < 6; i++) await Promise.resolve(); };

test('UUID and route policies accept canonical/compact IDs and reject malformed routes', () => {
    assert.equal(offlineUuid(compact(EPISODE).toUpperCase()), EPISODE);
    assert.equal(offlineUuid(`-${compact(EPISODE)}`), null);
    assert.equal(offlineUuid('https://example.invalid'), null);
    assert.deepEqual(offlineDetailRoute(`#!/details?id=${compact(EPISODE)}&serverId=${SERVER}`), { id: EPISODE, serverId: SERVER });
    assert.equal(offlineDetailRoute(`#/list?id=${EPISODE}`), null);
    assert.equal(offlineDetailRoute('#/details?seriesTimerId=123'), null);
});

test('badges distinguish phases, clamp known progress and never invent a queued episode from selection', () => {
    assert.equal(offlineBadge({ selected: true }), 'Selected for automatic downloads');
    assert.equal(offlineBadge({ phase: 'DOWNLOADING', progress: 1.9 }), 'Downloading 100%');
    assert.equal(offlineBadge({ phase: 'PARTIAL' }), 'Partly downloaded');
    assert.equal(offlineBadge({ phase: 'WAITING' }), 'Waiting to download');
    assert.equal(offlineBadge({ phase: 'COMPLETE', watched: true, pendingSync: true }),
        'Downloaded · Watched · Sync pending');
    assert.equal(offlineBadge({ phase: 'COMPLETE', watched: true, cleanupEligible: true }),
        'Downloaded · Watched · Cleanup eligible');
    assert.equal(offlineBadge({ phase: '<script>' }), '');
});

test('unsupported bridge leaves the page untouched and never creates a broad fallback', async () => {
    const f = fixture({ bridgeEnabled: false });
    const card = f.card(EPISODE);
    assert.equal(f.messages.length, 0);
    assert.deepEqual(owned(card), []);
    assert.equal(window.SmartOfflinePlayback.canPlay({ Id: EPISODE, ServerId: SERVER }), false);
});

test('classic/React cards and list items show account-bound badges and explicit actions only', async () => {
    const f = fixture();
    const card = f.card(EPISODE);
    const list = f.card(SERIES, 'Series', SERVER, 'listItem');
    const foreign = f.card(EPISODE, 'Episode', OTHER);
    const unrelated = f.card(EPISODE, 'Movie');
    await f.plugin.refresh();
    assert.deepEqual(f.messages.map(message => message.action), ['snapshot']);
    assert.equal(f.messages[0].userId, USER);
    assert.equal(f.messages[0].serverId, SERVER);
    assert.ok(action(card, 'Play downloaded'));
    assert.ok(action(list, 'Automatic downloads'));
    assert.equal(owned(foreign).length, 0);
    assert.equal(owned(unrelated).length, 0);
    assert.equal(window.SmartOfflinePlayback.canPlay({ Id: EPISODE, ServerId: SERVER }), true);
    assert.equal(window.SmartOfflinePlayback.canPlay({ Id: EPISODE, ServerId: OTHER }), false);
    const event = action(card, 'Play downloaded').click();
    await settle();
    assert.ok(event.prevented && event.stopped);
    assert.equal(f.messages.at(-1).action, 'play');
    assert.equal(f.messages.at(-1).itemId, EPISODE);
    action(list, 'Automatic downloads').click();
    await settle();
    assert.equal(f.messages.at(-1).action, 'manage');
    assert.equal(f.messages.at(-1).seriesId, SERIES);
    assert.ok(f.messages.every(message => !('url' in message) && !('token' in message)));
});

test('synthetic clicks do not issue actions and TV button wrappers avoid nested buttons', async () => {
    const f = fixture();
    const card = f.card(EPISODE);
    const tv = f.card(SERIES, 'Series', SERVER, 'card', 'button');
    await f.plugin.refresh();
    action(card, 'Play downloaded').click(false);
    assert.equal(f.messages.length, 1);
    assert.ok(owned(tv).some(node => node.tagName === 'SPAN'));
    assert.equal(owned(tv).some(node => node.tagName === 'BUTTON'), false);
});

test('stale account replies and previously rendered account controls are rejected', async () => {
    const f = fixture({ autoReply: false });
    const card = f.card(EPISODE);
    const waiting = f.plugin.refresh();
    f.setUser(OTHER);
    f.bridge.reply(f.messages[0], [{ id: EPISODE, phase: 'COMPLETE', canPlayLocal: true }]);
    await waiting;
    assert.equal(owned(card).length, 0);
    assert.equal(window.SmartOfflinePlayback.canPlay({ Id: EPISODE, ServerId: SERVER }), false);
});

test('out-of-order snapshots cannot overwrite the latest state and responses cannot inject unsolicited IDs', async () => {
    const f = fixture({ autoReply: false });
    const card = f.card(EPISODE);
    const first = f.plugin.refresh();
    const second = f.plugin.refresh();
    f.bridge.reply(f.messages[1], [{ id: EPISODE, phase: 'PARTIAL' }, { id: OTHER, canPlayLocal: true }]);
    await second;
    f.bridge.reply(f.messages[0], [{ id: EPISODE, phase: 'COMPLETE', canPlayLocal: true }]);
    await first;
    assert.equal(owned(card)[0].textContent, 'Partly downloaded');
    assert.equal(action(card, 'Play downloaded'), undefined);
    assert.equal(f.plugin.snapshots.has(OTHER), false);
});

test('detail route resolves an episode parent and only opens native management on explicit tap', async () => {
    const f = fixture({ hash: `#/details?id=${EPISODE}&serverId=${SERVER}`,
        item: { Id: EPISODE, ServerId: SERVER, SeriesId: SERIES, Type: 'Episode' } });
    const page = f.body.appendChild(new Element('div', 'itemDetailPage'));
    page.id = 'itemDetailPage';
    const buttons = page.appendChild(new Element('div', 'mainDetailButtons'));
    await f.plugin.refresh();
    await settle();
    await f.plugin.refresh();
    assert.ok(action(buttons, 'Automatic downloads'));
    assert.ok(action(buttons, 'Play downloaded'));
    assert.ok(f.messages.every(message => message.action === 'snapshot'));
    action(buttons, 'Automatic downloads').click();
    await settle();
    assert.equal(f.messages.at(-1).seriesId, SERIES);
    page.className += ' hide';
    f.plugin.invalidate();
    assert.equal(owned(page).length, 0);
});

test('batches are bounded and duplicate refreshes never duplicate badges', async () => {
    const f = fixture();
    for (let i = 0; i < 130; i++) f.card(i.toString(16).padStart(32, '0'));
    await f.plugin.refresh();
    assert.equal(f.messages[0].itemIds.length, 80);
    const first = f.body.children[0];
    const before = owned(first).length;
    await f.plugin.refresh();
    assert.equal(owned(first).length, before);
});

test('native action errors have visible mobile feedback and recover the button', async () => {
    const f = fixture({ autoReply: false });
    const card = f.card(SERIES, 'Series');
    const refresh = f.plugin.refresh();
    f.bridge.reply(f.messages[0], []);
    await refresh;
    const button = action(card, 'Automatic downloads');
    button.click();
    await settle();
    f.bridge.reply(f.messages.at(-1), [], 'Busy');
    await settle();
    const feedback = card.querySelector('.smartOfflineFeedback');
    assert.equal(feedback.textContent, 'Could not open item. Open Downloads and try again.');
    assert.equal(feedback.attributes.role, 'status');
    assert.equal(button.disabled, false);
});

test('recycled foreign-server and unrelated-type rows lose their old native controls', async () => {
    const f = fixture();
    const foreign = f.card(EPISODE);
    const unrelated = f.card(SERIES, 'Series');
    await f.plugin.refresh();
    assert.ok(action(foreign, 'Play downloaded'));
    assert.ok(action(unrelated, 'Automatic downloads'));
    foreign.dataset.serverid = OTHER;
    unrelated.dataset.type = 'Movie';
    await f.plugin.refresh();
    assert.equal(owned(foreign).length, 0);
    assert.equal(owned(unrelated).length, 0);
});
