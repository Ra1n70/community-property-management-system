import React, {useEffect,useState} from 'react';
import {api,isCancelled,portalUrl} from './api';
import './community-search.css';
const types={PACKAGE:'Packages',AMENITY:'Amenities',MAINTENANCE:'Maintenance',ANNOUNCEMENT:'Announcements',DISCUSSION:'Discussion Board'};
// Announcements and discussions have their own pages; the other types open inside the home page.
const pageLinks={ANNOUNCEMENT:id=>`/announcements?id=${id}`,DISCUSSION:id=>`/discussion-board?post=${id}`};
const words=query=>query.trim().toLowerCase().split(/\s+/).filter(Boolean).slice(0,8);
const escape=text=>text.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');

let focusSeq=0;
/** Maps a search result to the home-page section that shows it; seq lets the same record be opened twice. */
export function searchFocus(result,sections) {
 const section=sections[result.type];
 return section?{section,focus:{type:result.type,id:result.id,seq:++focusSeq}}:null;
}

function Highlight({text,terms}) {
 if(!terms.length)return text;
 const pattern=new RegExp(`(${terms.map(escape).join('|')})`,'gi');
 return text.split(pattern).map((part,i)=>i%2?<mark key={i}>{part}</mark>:part);
}
// A short excerpt around the first matching word.
function snippet(text,terms,length=150) {
 const lower=text.toLowerCase(),hit=Math.min(...terms.map(t=>lower.indexOf(t)).filter(i=>i>=0),Infinity);
 const start=hit===Infinity||hit<50?0:hit-50,end=start+length;
 return (start?'…':'')+text.slice(start,end).trim()+(end<text.length?'…':'');
}

export default function CommunitySearch({user,onOpen}) {
 const provider=user.role==='PROVIDER';
 // query is what is typed; searched is what the results are for (typing waits 300 ms, Enter searches at once).
 const [query,setQuery]=useState(''),[searched,setSearched]=useState(''),[type,setType]=useState(''),[sort,setSort]=useState('latest'),[page,setPage]=useState(0),[again,setAgain]=useState(0);
 const [data,setData]=useState(null),[error,setError]=useState(''),[busy,setBusy]=useState(false);
 useEffect(()=>{if(query===searched)return;const timer=setTimeout(()=>{setSearched(query);setPage(0);},300);return()=>clearTimeout(timer);},[query]); // eslint-disable-line react-hooks/exhaustive-deps
 // Every change of words, type, sort or page cancels the request still running, so an older answer can never replace a newer one.
 useEffect(()=>{
  const request=new AbortController();setBusy(true);setError('');
  api('/search?'+new URLSearchParams({q:searched,type,sort,page,size:10}),{signal:request.signal})
   .then(result=>{setData(result);setBusy(false);})
   .catch(e=>{if(isCancelled(e))return;setError(e.message);setData(null);setBusy(false);});
  return()=>request.abort();
 },[searched,type,sort,page,again]);
 const terms=words(searched),counts=data?.counts||{};
 const shownTypes=Object.entries(types).filter(([key])=>!provider||key==='MAINTENANCE');
 const allCount=shownTypes.reduce((sum,[key])=>sum+(counts[key]||0),0);
 function chooseType(next){setType(next);setPage(0);}
 return <section className="card community-search"><span className="eyebrow">COMMUNITY SEARCH</span><h2>Search</h2>
  <p>Find {provider?'your assigned maintenance requests':'packages, amenities, maintenance, announcements and discussions you can access'}. Every word you type must appear.</p>
  <form className="search-bar" onSubmit={e=>{e.preventDefault();setSearched(query);setPage(0);setAgain(n=>n+1);}}>
   <label className="search-input">Keywords<input type="search" value={query} maxLength={200} onChange={e=>setQuery(e.target.value)} placeholder="e.g. lounge, leaking tap, UPS 601"/></label>
   <label className="search-sort">Sort<select value={sort} onChange={e=>{setSort(e.target.value);setPage(0);}}><option value="latest">Latest</option><option value="relevance">Best match</option></select></label>
  </form>
  {!provider&&<div className="search-types" role="group" aria-label="Filter by type">
   <button type="button" aria-pressed={!type} className={!type?'active':''} onClick={()=>chooseType('')}>All <span>{allCount}</span></button>
   {shownTypes.map(([key,label])=><button type="button" key={key} aria-pressed={type===key} className={type===key?'active':''} onClick={()=>chooseType(key)}>{label} <span>{counts[key]||0}</span></button>)}
  </div>}
  {error&&<p className="message error" role="alert">{error}</p>}
  <p className="search-summary" role="status">{busy?'Searching…':data?`${data.total} result${data.total===1?'':'s'}${terms.length?` for “${searched.trim()}”`:''}`:''}</p>
  {data&&<>{data.results.length?<ul className="search-results">{data.results.map(item=>{
    const link=pageLinks[item.type];
    return <li className="search-result" key={`${item.type}-${item.id}`}>
     <div className="search-result-body">
      <div className="search-result-head"><span className="badge">{types[item.type]}</span><h3><Highlight text={item.title} terms={terms}/></h3></div>
      {item.content&&<p><Highlight text={snippet(item.content,terms)} terms={terms}/></p>}
      <small>{item.status.replaceAll('_',' ').toLowerCase()} · {new Date(item.updatedAt).toLocaleString('en-US',{dateStyle:'medium',timeStyle:'short'})}</small>
     </div>
     {link?<a className="search-open" href={portalUrl(link(item.id))} target="_blank" rel="noopener noreferrer">View details ↗</a>
      :<button type="button" className="search-open" onClick={()=>onOpen?.(item)}>View details</button>}
    </li>;})}</ul>
   :<p className="empty">No matching results. Try fewer or different words, or another type.</p>}
   {data.totalPages>1&&<div className="pagination"><button disabled={busy||page===0} onClick={()=>setPage(page-1)}>Previous</button><span>Page {page+1} of {data.totalPages}</span><button disabled={busy||page+1>=data.totalPages} onClick={()=>setPage(page+1)}>Next</button></div>}</>}
 </section>;
}
