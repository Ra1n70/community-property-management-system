import React from 'react';

/** "1–20 of 153 requests" with Previous/Next for lists the server returns one page at a time. */
export default function Pager({data, noun, busy, onPage}) {
 if (!data?.total) return null;
 const first = data.page * data.size + 1, last = Math.min(data.total, first + data.items.length - 1);
 return <div className="list-pager">
  <span>{first}–{last} of {data.total} {noun}</span>
  {data.totalPages > 1 && <span className="list-pager-buttons">
   <button type="button" disabled={busy || data.page === 0} onClick={() => onPage(data.page - 1)}>Previous</button>
   <span>Page {data.page + 1} of {data.totalPages}</span>
   <button type="button" disabled={busy || data.page + 1 >= data.totalPages} onClick={() => onPage(data.page + 1)}>Next</button>
  </span>}
 </div>;
}
