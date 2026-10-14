/** A card's detail, one data point per line; empty points are skipped, [empty] shows when none are left. */
export default function Detail({ lines, empty }: { lines: (string | false | null | undefined)[]; empty?: string }) {
  const shown = lines.filter((l): l is string => !!l);
  if (shown.length === 0) return empty ? <div className="detail">{empty}</div> : null;
  return <div className="detail">{shown.map((l, i) => <div key={i}>{l}</div>)}</div>;
}
