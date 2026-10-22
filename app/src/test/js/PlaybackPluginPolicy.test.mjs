import { readFile } from 'node:fs/promises';
import test from 'node:test';
import assert from 'node:assert/strict';

async function plugin(name) {
    const source = await readFile(new URL(`../../main/assets/native/${name}.js`, import.meta.url), 'utf8');
    return (await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`))[name];
}

const Exo = await plugin('ExoPlayerPlugin');
const External = await plugin('ExternalPlayerPlugin');

test('cached verified local items use native playback across web and external player settings', () => {
    const item = { Id: 'episode-1' };
    globalThis.window = {
        NativePlayer: { isEnabled: () => false },
        ExternalPlayer: { isEnabled: () => true },
        SmartOfflinePlayback: { canPlay: candidate => candidate === item },
    };
    const playbackManager = { syncPlayEnabled: false };
    const exo = new Exo({ playbackManager });
    const external = new External({ playbackManager });
    assert.equal(exo.canPlayItem(item, { fullscreen: true }), true);
    assert.equal(external.canPlayItem(item, { fullscreen: true }), false);
    assert.equal(exo.canPlayItem({ Id: 'remote' }, { fullscreen: true }), false);
    assert.equal(external.canPlayItem({ Id: 'remote' }, { fullscreen: true }), true);
    assert.equal(exo.canPlayItem(item, { fullscreen: false }), false);
    playbackManager.syncPlayEnabled = true;
    assert.equal(exo.canPlayItem(item, { fullscreen: true }), false);
    assert.equal(external.canPlayItem(item, { fullscreen: true }), false);
});

test('missing capability bridge preserves configured players and is never treated as an async capability', () => {
    globalThis.window = { NativePlayer: { isEnabled: () => true }, ExternalPlayer: { isEnabled: () => true } };
    const playbackManager = { syncPlayEnabled: false };
    assert.equal(new Exo({ playbackManager }).canPlayItem({}, { fullscreen: true }), true);
    assert.equal(new External({ playbackManager }).canPlayItem({}, { fullscreen: true }), true);
    window.NativePlayer.isEnabled = () => false;
    assert.equal(Boolean(new Exo({ playbackManager }).canPlayItem({}, { fullscreen: true })), false);
});
