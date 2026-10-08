import React, {useEffect,useState} from 'react';
import {api,isCancelled,portalUrl} from './api';
import {EnglishDate} from './EnglishInputs';
import './community-hub.css';
import './manager-reports.css';

// Report dates are calendar days in the community's time zone (Pacific), as the server counts them.
function pacificToday(){const p=Object.fromEntries(new Intl.DateTimeFormat('en-US',{timeZone:'America/Los_Angeles',year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(new Date()).map(x=>[x.type,x.value]));return `${p.year}-${p.month}-${p.day}`;}
function shift(date,days){const d=new Date(`${date}T12:00:00Z`);d.setUTCDate(d.getUTCDate()+days);return d.toISOString().slice(0,10);}
const presets=[['7','Last 7 days',t=>[shift(t,-6),t]],['30','Last 30 days',t=>[shift(t,-29),t]],['month','This month',t=>[`${t.slice(0,8)}01`,t]],['90','Last 90 days',t=>[shift(t,-89),t]],['year','This year',t=>[`${t.slice(0,4)}-01-01`,t]]];
const longDay=value=>new Date(`${value}T12:00:00`).toLocaleDateString('en-US',{dateStyle:'medium'});
function show(m){
 if(m.value==null)return '—';
 if(m.unit==='USD')return new Intl.NumberFormat('en-US',{style:'currency',currency:'USD'}).format(m.value);
 if(m.unit==='percent')return `${m.value}%`;
 if(m.unit==='hours')return m.value>=48?`${(m.value/24).toFixed(1)} days`:`${m.value} h`;
 return new Intl.NumberFormat('en-US').format(m.value);
}
// "Now" figures describe the present, not the chosen period; they are marked so the two are not confused.
const isNow=m=>/now$/i.test(m.label);

/** Management reports: the manager home's statistics, filtered by date and exportable as CSV. */
export default function ManagerReports() {
 const [today]=useState(pacificToday);
 const [preset,setPreset]=useState('30'),[range,setRange]=useState(()=>presets[1][2](today));
 const [report,setReport]=useState(null),[loading,setLoading]=useState(true),[error,setError]=useState(''),[again,setAgain]=useState(0);
 const [from,to]=range,valid=/^\d{4}-\d{2}-\d{2}$/.test(from)&&/^\d{4}-\d{2}-\d{2}$/.test(to)&&from<=to;
 const query=new URLSearchParams({from,to}).toString();
 useEffect(()=>{
  if(!valid)return;
  const request=new AbortController();setLoading(true);setError('');
  api(`/manager/statistics?${query}`,{signal:request.signal}).then(r=>{setReport(r);setLoading(false);}).catch(e=>{if(isCancelled(e))return;setError(e.message);setLoading(false);});
  return()=>request.abort();
 },[query,again]); // eslint-disable-line react-hooks/exhaustive-deps
 function choosePreset(key){setPreset(key);if(key!=='custom')setRange(presets.find(([k])=>k===key)[2](today));}
 return <section className="card manager-reports">
  <div className="section-head"><div><span className="eyebrow">MANAGEMENT REPORTS</span><h2>Community overview</h2><p>Activity for the chosen dates (Pacific time). Figures marked “now” show the current state.</p></div>
   <div className="actions"><button disabled={loading} onClick={()=>setAgain(n=>n+1)}>Refresh</button><a className={`button-link ${valid?'':'disabled'}`} href={valid?portalUrl(`/api/manager/statistics/export?${query}`):undefined} aria-disabled={!valid}>Export CSV</a></div></div>
  <div className="report-filters">
   <div className="hub-filters" role="group" aria-label="Date range">{[...presets.map(([key,label])=>[key,label]),['custom','Custom']].map(([key,label])=><button key={key} type="button" aria-pressed={preset===key} onClick={()=>choosePreset(key)}>{label}</button>)}</div>
   {preset==='custom'&&<div className="report-custom"><EnglishDate label="From" value={from} max={today} onChange={v=>setRange([v,to])}/><EnglishDate label="Through" value={to} max={today} onChange={v=>setRange([from,v])}/></div>}
   {!valid&&<p className="message error" role="alert">The start date must be on or before the end date.</p>}
  </div>
  {error&&<p className="message error" role="alert">{error}</p>}
  {report&&<p className="report-period" role="status">{loading?'Updating…':`${longDay(report.from)} – ${longDay(report.to)}`}</p>}
  {!report?<p role="status">Loading reports…</p>:<div className={`report-sections ${loading?'loading':''}`}>{report.sections.map(s=><article key={s.key} className="report-section">
   <h3>{s.title}</h3>
   <div className="report-tiles">{s.metrics.map(m=><div key={m.key} className={`report-tile ${isNow(m)?'now':''}`}><strong>{show(m)}</strong><span>{m.label}</span></div>)}</div>
   {s.breakdown.length>0&&<Breakdown section={s}/>}
  </article>)}</div>}
 </section>;
}

function Breakdown({section}) {
 const max=Math.max(1,...section.breakdown.map(m=>m.value||0));
 return <div className="report-breakdown"><h4>{section.key==='maintenance'?'Requests by category':'Reservations by amenity'}</h4>
  <ul>{section.breakdown.slice(0,8).map(m=><li key={m.key}><span>{m.label}</span><span className="report-bar" aria-hidden="true"><i style={{width:`${m.value*100/max}%`}}/></span><strong>{m.value}</strong></li>)}</ul></div>;
}
