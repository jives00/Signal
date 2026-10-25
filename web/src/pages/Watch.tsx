import Hls, { type ErrorData } from "hls.js";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { clock, getChannel, getChannels, getGroups, getLists, getWatchStream } from "../admin";
import { stored, store, useProfiles } from "../context";
import type { ChannelRow, GroupView, ListsView } from "../schema";

/**
 * Watch (web-player.md): live TV in the browser, straight from the provider's HLS, so the companion
 * serves lists and URLs but never relays video. Favorites, playlists, groups and search on the left;
 * the player on the right. Up/Down change channel, F is full screen, M mutes.
 */
export default function Watch() {
  const { profile } = useProfiles();
  const [lists, setLists] = useState<ListsView | null>(null);
  const [groups, setGroups] = useState<GroupView[]>([]);
  const [list, setList] = useState(() => stored("signal.watch.list") ?? "favorites");
  const [group, setGroup] = useState(() => stored("signal.watch.group") ?? "");
  const [query, setQuery] = useState("");
  const [rows, setRows] = useState<ChannelRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [playing, setPlaying] = useState<ChannelRow | null>(null);

  useEffect(() => {
    getLists(profile).then(setLists).catch((e: Error) => setError(e.message));
    getGroups(profile).then((g) => setGroups(g.groups.filter((x) => !x.hidden && x.count > 0))).catch(() => undefined);
  }, [profile]);

  useEffect(() => { store("signal.watch.list", list); store("signal.watch.group", group); }, [list, group]);

  // What the left column shows: a search, a list, or one group's channels.
  useEffect(() => {
    let live = true;
    const text = query.trim();
    const load = (): Promise<ChannelRow[]> | null => {
      if (text) return getChannels(profile, { q: text, hidden: "visible", limit: 100 }).then((p) => p.rows);
      if (list === "favorites") return lists ? Promise.resolve(lists.favorites) : null;
      if (list.startsWith("playlist:")) return lists ? Promise.resolve(lists.playlists.find((p) => p.id === list.slice(9))?.channels ?? []) : null;
      const g = groups.find((x) => `${x.sourceId}\u0000${x.name}` === group);
      return g ? getChannels(profile, { source: g.sourceId, group: g.name, hidden: "visible", limit: 500 }).then((p) => p.rows) : Promise.resolve([]);
    };
    const timer = setTimeout(() => {
      load()?.then((r) => { if (live) { setRows(r.filter((c) => !c.unavailable)); setError(null); } }).catch((e: Error) => { if (live) setError(e.message); });
    }, text ? 300 : 0);
    return () => { live = false; clearTimeout(timer); };
  }, [profile, list, group, query, lists, groups]);

  const step = useCallback((by: number) => {
    if (!rows?.length) return;
    const i = playing ? rows.findIndex((r) => r.streamId === playing.streamId) : -1;
    setPlaying(rows[(i + by + rows.length) % rows.length]);
  }, [rows, playing]);

  return (
    <div className="page watch">
      <div className="watch-list">
        <div className="filters">
          <select value={list} onChange={(e) => setList(e.target.value)} aria-label="List">
            <option value="favorites">Favorites</option>
            {lists?.playlists.map((p) => <option key={p.id} value={`playlist:${p.id}`}>{p.name}</option>)}
            <option value="group">Groups</option>
          </select>
          {list === "group" && (
            <select value={group} onChange={(e) => setGroup(e.target.value)} aria-label="Group">
              <option value="">Choose a group</option>
              {groups.map((g) => <option key={`${g.sourceId}\u0000${g.name}`} value={`${g.sourceId}\u0000${g.name}`}>{g.title} ({g.count})</option>)}
            </select>
          )}
          <input type="search" placeholder="Search channels" value={query} onChange={(e) => setQuery(e.target.value)} />
        </div>
        {error && <p className="error-text">{error}</p>}
        {rows && rows.length === 0 && <p className="detail">{query ? "No channels match." : list === "favorites" ? "No favorites yet. Add them on the TV or on the Channels page." : "No channels here."}</p>}
        <ul className="watch-rows">
          {rows?.map((r) => (
            <li key={r.streamId}>
              <button className={playing?.streamId === r.streamId ? "selected" : ""} onClick={() => setPlaying(r)}>
                <span className="number">{r.number}</span>
                {r.logo ? <img className="logo" src={r.logo} alt="" loading="lazy" /> : <span className="logo" />}
                <span className="grow">
                  <span className="name">{r.name}</span>
                  {r.now && <span className="detail small now">{r.now.title}</span>}
                </span>
              </button>
            </li>
          ))}
        </ul>
      </div>
      <Player channel={playing} profile={profile} onStep={step} />
    </div>
  );
}

/** Why a stream didn't play, in words: the provider's refusals mostly mean the account's connection is taken. */
function describe(data: ErrorData): string {
  const code = data.response?.code;
  if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
    if (code === 0 || code === undefined) return "The provider didn't answer, or the browser blocked the request.";
    if (code === 405 || code === 404) return `The provider doesn't have this channel right now (HTTP ${code}).`;
    return `The provider refused the stream (HTTP ${code}). If a TV is watching this source, it may be using the account's only connection.`;
  }
  if (data.type === Hls.ErrorTypes.MEDIA_ERROR) return "The browser can't decode this channel's video or audio.";
  return `Playback failed (${data.details}).`;
}

function Player({ channel, profile, onStep }: { channel: ChannelRow | null; profile: string; onStep: (by: number) => void }) {
  const video = useRef<HTMLVideoElement>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const [detail, setDetail] = useState<ChannelRow | null>(null);
  const [attempt, setAttempt] = useState(0);

  // One player per channel: a channel change destroys the old one first, so the browser never holds two of the provider's connections.
  useEffect(() => {
    const el = video.current;
    if (!channel || !el) return;
    let live = true;
    let hls: Hls | null = null;
    setStatus("Tuning…"); setFailed(false); setNote(null);
    const fail = (message: string) => { if (live) { setStatus(message); setFailed(true); } };
    const play = () => el.play().catch((e: DOMException) => {
      // Autoplay with sound needs a click on the page; the channel list is one, but a reload isn't.
      if (e.name === "NotAllowedError") { el.muted = true; el.play().catch(() => undefined); }
    });
    getWatchStream(profile, channel.streamId).then((w) => {
      if (!live) return;
      if (w.headersDropped) setNote("This source sets a User-Agent or Referer, which a browser can't send, so the provider may refuse it.");
      if (!w.hls) return fail("This channel isn't HLS, and browsers only play HLS.");
      if (Hls.isSupported()) {
        hls = new Hls({ manifestLoadingMaxRetry: 1, levelLoadingMaxRetry: 2, fragLoadingMaxRetry: 2 });
        let recovered = false;
        hls.on(Hls.Events.ERROR, (_e, data) => {
          if (!data.fatal) return;
          // A decode hiccup is often recoverable once; a second one isn't.
          if (data.type === Hls.ErrorTypes.MEDIA_ERROR && !recovered) { recovered = true; hls?.recoverMediaError(); return; }
          hls?.destroy(); hls = null;
          fail(describe(data));
        });
        hls.loadSource(w.url);
        hls.attachMedia(el);
        play();
      } else if (el.canPlayType("application/vnd.apple.mpegurl")) {
        el.src = w.url; // Safari plays HLS itself.
        play();
      } else fail("This browser can't play HLS.");
    }).catch((e: Error) => fail(e.message));
    const onPlaying = () => { if (live) setStatus(null); };
    const onWaiting = () => { if (live) setStatus("Buffering…"); };
    el.addEventListener("playing", onPlaying);
    el.addEventListener("waiting", onWaiting);
    const stop = () => { hls?.destroy(); hls = null; el.removeAttribute("src"); el.load(); };
    window.addEventListener("pagehide", stop);
    return () => {
      live = false;
      el.removeEventListener("playing", onPlaying);
      el.removeEventListener("waiting", onWaiting);
      window.removeEventListener("pagehide", stop);
      stop();
    };
  }, [channel, profile, attempt]);

  // Now and next, refreshed each minute (the list rows from Favorites don't carry programs).
  useEffect(() => {
    if (!channel) { setDetail(null); return; }
    let live = true;
    const load = () => getChannel(profile, channel.streamId).then((d) => { if (live) setDetail(d); }).catch(() => undefined);
    load();
    const timer = setInterval(load, 60_000);
    return () => { live = false; clearInterval(timer); };
  }, [channel, profile]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement;
      if (t.tagName === "INPUT" || t.tagName === "SELECT" || t.tagName === "TEXTAREA" || e.ctrlKey || e.metaKey || e.altKey) return;
      const el = video.current;
      if (e.key === "ArrowUp" || e.key === "ArrowDown") { e.preventDefault(); onStep(e.key === "ArrowDown" ? 1 : -1); }
      else if (e.key === "f" && el) { if (document.fullscreenElement) void document.exitFullscreen(); else void el.requestFullscreen().catch(() => undefined); }
      else if (e.key === "m" && el) el.muted = !el.muted;
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onStep]);

  const now = detail?.now;
  const next = detail?.next;
  const progress = useMemo(() => now ? Math.min(100, Math.max(0, ((Date.now() - now.start) / (now.stop - now.start)) * 100)) : 0, [now]);

  return (
    <div className="watch-player">
      <div className="watch-video">
        <video ref={video} controls playsInline />
        {!channel && <div className="watch-overlay detail">Pick a channel.</div>}
        {channel && status && (
          <div className={failed ? "watch-overlay error-text" : "watch-overlay"}>
            <p>{status}</p>
            {failed && <button onClick={() => setAttempt((a) => a + 1)}>Try again</button>}
          </div>
        )}
      </div>
      {channel && (
        <div className="watch-info">
          <div className="row">
            <span className="section-title grow">{channel.number} · {channel.name}</span>
            <button onClick={() => onStep(-1)} title="Previous channel (Up)">Previous</button>
            <button onClick={() => onStep(1)} title="Next channel (Down)">Next</button>
          </div>
          {now ? (
            <>
              <div className="row"><span className="grow">{now.title}</span><span className="detail nowrap">{clock(now.start)}–{clock(now.stop)}</span></div>
              <div className="watch-progress"><div style={{ width: `${progress}%` }} /></div>
            </>
          ) : <p className="detail">No guide information.</p>}
          {next && <p className="detail">Next: {next.title} at {clock(next.start)}</p>}
          {note && <p className="detail small">{note}</p>}
          <p className="detail small">Up and Down change channel, F is full screen, M mutes. The browser uses one of the source's connections while it plays.</p>
        </div>
      )}
    </div>
  );
}
