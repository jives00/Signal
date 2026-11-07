import { useMemo } from "react";
import { getChannel, getChannels, getGroups, getLists, getWatchStream, watchPlaying, watchStopped } from "../admin";
import { useProfiles } from "../context";
import WatchScreen, { type WatchApi } from "../watch/WatchScreen";

/** The web admin's Live TV → Watch: the shared screen, for the profile chosen at the top of the page. */
export default function Watch() {
  const { profile } = useProfiles();
  const api = useMemo<WatchApi>(() => ({
    lists: () => getLists(profile),
    groups: () => getGroups(profile),
    channels: (f) => getChannels(profile, { ...f, hidden: "visible" }).then((p) => p.rows),
    channel: (id) => getChannel(profile, id),
    stream: (id) => getWatchStream(profile, id),
    playing: () => watchPlaying(profile),
    stopped: (id) => watchStopped(profile, id),
  }), [profile]);
  return <WatchScreen api={api} />;
}
