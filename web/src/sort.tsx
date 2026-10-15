// Click-to-sort table headers, shared by every table in the web admin. A header cycles ascending,
// descending, then back to the page's own order. Paged tables (Channels, Catalog) sort on the
// companion; the rest sort the rows they already have with useSorted.
import { useMemo, useState } from "react";

export type SortDir = "asc" | "desc";
export interface SortState<C extends string = string> { sort?: C; dir?: SortDir }

/** What a click on [column]'s header changes the sort to. */
export function nextSort<C extends string>(current: SortState<C>, column: C): SortState<C> {
  if (current.sort !== column) return { sort: column, dir: "asc" };
  return current.dir === "asc" ? { sort: column, dir: "desc" } : { sort: undefined, dir: undefined };
}

export function SortHeader<C extends string>({ column, label, state, onSort, className }: {
  column: C; label: string; state: SortState<C>; onSort: (next: SortState<C>) => void; className?: string;
}) {
  const active = state.sort === column;
  return (
    <th className={className} aria-sort={active ? (state.dir === "desc" ? "descending" : "ascending") : "none"}>
      <button className="sort" onClick={() => onSort(nextSort(state, column))}>{label}<span className="arrow" aria-hidden>{active ? (state.dir === "desc" ? "▼" : "▲") : ""}</span></button>
    </th>
  );
}

export type SortValue = string | number | boolean | null | undefined;

/** Text in natural order ("Channel 2" before "Channel 10"), numbers by value; empty values last either way. */
export function compareValues(a: SortValue, b: SortValue): number {
  const aEmpty = a === null || a === undefined || a === "";
  const bEmpty = b === null || b === undefined || b === "";
  if (aEmpty || bEmpty) return aEmpty === bEmpty ? 0 : aEmpty ? 1 : -1;
  if (typeof a === "string" || typeof b === "string") return String(a).localeCompare(String(b), undefined, { numeric: true, sensitivity: "base" });
  return Number(a) - Number(b);
}

/** [rows] sorted by the clicked column; unsorted, they keep the order they came in. */
export function sortRows<T, C extends string>(rows: readonly T[], keys: Record<C, (row: T) => SortValue>, state: SortState<C>): T[] {
  const key = state.sort ? keys[state.sort] : undefined;
  if (!key) return rows.slice();
  const sign = state.dir === "desc" ? -1 : 1;
  // Empty values stay last when descending too.
  return rows.slice().sort((x, y) => {
    const a = key(x), b = key(y);
    const empty = (v: SortValue) => v === null || v === undefined || v === "";
    if (empty(a) || empty(b)) return compareValues(a, b);
    return sign * compareValues(a, b);
  });
}

/** Client-side sorting for a table whose rows are all loaded. */
export function useSorted<T, C extends string>(rows: readonly T[] | null | undefined, keys: Record<C, (row: T) => SortValue>) {
  const [state, setState] = useState<SortState<C>>({});
  // keys is a literal at each call site; the sort only needs redoing when the rows or the column change.
  const sorted = useMemo(() => (rows ? sortRows(rows, keys, state) : []), [rows, state]); // eslint-disable-line react-hooks/exhaustive-deps
  const header = (column: C, label: string, className?: string) => <SortHeader column={column} label={label} state={state} onSort={setState} className={className} />;
  return { rows: sorted, sorted: !!state.sort, state, setState, header };
}
