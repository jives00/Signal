import { StrictMode, useEffect, useMemo, useState, type FormEvent } from "react";
import { createRoot } from "react-dom/client";
import WatchScreen from "../watch/WatchScreen";
import { pairingStatus, recordingsApi, savedKey, saveKey, startPairing, viewerApi, type Pairing } from "./api";
import Recordings from "./Recordings";
import "../theme.css";

function guessName(): string {
  const ua = navigator.userAgent;
  if (/iPhone/.test(ua)) return "iPhone";
  if (/iPad/.test(ua)) return "iPad";
  if (/Android/.test(ua)) return "Android phone";
  return "Browser";
}

/** First visit: ask for a code, and wait for someone to enter it on the web admin's Devices page. */
function Pair({ onPaired }: { onPaired: (key: string) => void }) {
  const [name, setName] = useState(guessName);
  const [pairing, setPairing] = useState<Pairing | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!pairing) return;
    const timer = setInterval(() => {
      pairingStatus(pairing.pairingId).then((s) => {
        if (s.state === "APPROVED" && s.apiKey) { saveKey(s.apiKey); onPaired(s.apiKey); }
        else if (s.state === "EXPIRED") { setPairing(null); setError("That code ran out. Get a new one."); }
      }).catch(() => undefined);
    }, 3000);
    return () => clearInterval(timer);
  }, [pairing, onPaired]);

  async function start(event: FormEvent) {
    event.preventDefault();
    setError(null);
    try { setPairing(await startPairing(name.trim() || guessName())); } catch (e) { setError((e as Error).message); }
  }

  if (pairing) {
    return (
      <div className="pair">
        <h1>Signal</h1>
        <p>Ask whoever runs Signal to approve this code:</p>
        <div className="code">{pairing.code}</div>
        <p className="detail">This page moves on by itself once it's approved. The code lasts 10 minutes.</p>
      </div>
    );
  }
  return (
    <form className="pair" onSubmit={start}>
      <h1>Signal</h1>
      <label>
        <span className="detail">Name this device</span>
        <input value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />
      </label>
      <button type="submit">Get a code</button>
      {error && <p className="error-text">{error}</p>}
    </form>
  );
}

function Viewer() {
  const [key, setKey] = useState(savedKey);
  const [tab, setTab] = useState<"live" | "recordings">("live");
  const api = useMemo(() => key ? viewerApi(key, () => setKey(null)) : null, [key]);
  const recordings = useMemo(() => key ? recordingsApi(key, () => setKey(null)) : null, [key]);
  if (!api || !recordings) return <Pair onPaired={setKey} />;
  return (
    <>
      <div className="filters viewer-tabs">
        <button className={tab === "live" ? "selected" : ""} onClick={() => setTab("live")}>Live TV</button>
        <button className={tab === "recordings" ? "selected" : ""} onClick={() => setTab("recordings")}>Recordings</button>
      </div>
      {tab === "live" ? <WatchScreen api={api} /> : <Recordings api={recordings} />}
    </>
  );
}

createRoot(document.getElementById("root")!).render(<StrictMode><Viewer /></StrictMode>);
