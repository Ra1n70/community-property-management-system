import React, {useState} from 'react';

const months=['January','February','March','April','May','June','July','August','September','October','November','December'];
const pad=n=>String(n).padStart(2,'0');
// Native date pickers use the browser/OS language even when lang="en" is set.
export default function EnglishDateTime({name,label}) {
 const now=new Date();
 const [enabled,setEnabled]=useState(false);
 const [parts,setParts]=useState({year:now.getFullYear(),month:now.getMonth()+1,day:now.getDate(),hour:now.getHours(),minute:now.getMinutes()});
 const days=new Date(parts.year,parts.month,0).getDate();
 function change(key,value){setParts(old=>{const next={...old,[key]:Number(value)};next.day=Math.min(next.day,new Date(next.year,next.month,0).getDate());return next;});}
 const value=enabled?`${parts.year}-${pad(parts.month)}-${pad(parts.day)}T${pad(parts.hour)}:${pad(parts.minute)}`:'';
 const select=(key,title,values)=> <label>{title}<select aria-label={`${label}: ${title}`} disabled={!enabled} value={parts[key]} onChange={e=>change(key,e.target.value)}>{values.map(([v,text])=><option key={v} value={v}>{text}</option>)}</select></label>;
 const numbers=(start,count)=>Array.from({length:count},(_,i)=>[start+i,pad(start+i)]);
 return <fieldset className="english-date-time" lang="en"><legend>{label}</legend><div className="actions" role="group" aria-label={label}><button type="button" aria-pressed={!enabled} onClick={()=>setEnabled(false)}>No preference</button><button type="button" aria-pressed={enabled} onClick={()=>setEnabled(true)}>Choose a time</button></div><input type="hidden" name={name} value={value}/>{enabled&&<div className="english-date-parts">
 {select('month','Month',months.map((m,i)=>[i+1,m]))}
 {select('day','Day',numbers(1,days))}
 {select('year','Year',numbers(now.getFullYear(),6))}
 {select('hour','Hour (24h)',numbers(0,24))}
 {select('minute','Minute',numbers(0,60))}
 </div>}</fieldset>;
}
