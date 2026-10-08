import React, {useEffect, useRef, useState} from 'react';

// Native date, time and file controls follow the browser/OS language even when lang="en" is set,
// so these controls render their own English labels and emit the same string formats as the native inputs.
const months=['January','February','March','April','May','June','July','August','September','October','November','December'];
const pad=n=>String(n).padStart(2,'0');
const parseDate=value=>{const m=/^(\d{4})-(\d{2})-(\d{2})$/.exec(value||'');return m?{year:+m[1],month:+m[2],day:+m[3]}:null;};
const formatDate=({year,month,day})=>`${year}-${pad(month)}-${pad(day)}`;
const daysIn=(year,month)=>new Date(Date.UTC(year,month,0)).getUTCDate();
const dateLabel=value=>new Date(`${value}T12:00:00Z`).toLocaleDateString('en-US',{timeZone:'UTC',weekday:'short',month:'short',day:'numeric',year:'numeric'});
const hourLabel=h=>`${h%12||12} ${h<12?'AM':'PM'}`;

function datesBetween(min,max){const out=[];for(let d=new Date(`${min}T12:00:00Z`);d<=new Date(`${max}T12:00:00Z`)&&out.length<370;d.setUTCDate(d.getUTCDate()+1))out.push(d.toISOString().slice(0,10));return out;}

export function EnglishDate({label,value,onChange,min,max,disabled,className}) {
 // A short bounded range (such as the 7-day booking window) reads best as one list of dates.
 if(min&&max&&datesBetween(min,max).length<=62) {
  const options=datesBetween(min,max);
  return <label className={className} lang="en">{label}<select value={options.includes(value)?value:''} disabled={disabled} onChange={e=>onChange(e.target.value)}>{!options.includes(value)&&<option value="" disabled>Select a date</option>}{options.map(d=><option key={d} value={d}>{dateLabel(d)}</option>)}</select></label>;
 }
 const now=new Date(),parts=parseDate(value)||{year:now.getFullYear(),month:now.getMonth()+1,day:now.getDate()};
 const firstYear=Math.min(parts.year,min?+min.slice(0,4):now.getFullYear()-1),lastYear=Math.max(parts.year,max?+max.slice(0,4):now.getFullYear()+2);
 function change(key,next){const p={...parts,[key]:Number(next)};p.day=Math.min(p.day,daysIn(p.year,p.month));onChange(formatDate(p));}
 const select=(key,title,options)=><select aria-label={`${label}: ${title}`} value={parts[key]} disabled={disabled} onChange={e=>change(key,e.target.value)}>{options.map(([v,text])=><option key={v} value={v}>{text}</option>)}</select>;
 return <fieldset className={`english-input ${className||''}`} lang="en" disabled={disabled}><legend>{label}</legend><div className="english-input-parts">
  {select('month','Month',months.map((m,i)=>[i+1,m]))}
  {select('day','Day',Array.from({length:daysIn(parts.year,parts.month)},(_,i)=>[i+1,i+1]))}
  {select('year','Year',Array.from({length:lastYear-firstYear+1},(_,i)=>[firstYear+i,firstYear+i]))}
 </div></fieldset>;
}

export function EnglishTime({label,value,onChange,disabled,className}) {
 const [h,m]=(/^\d{2}:\d{2}/.test(value||'')?value:'09:00').split(':').map(Number);
 const minutes=[...new Set([...Array.from({length:12},(_,i)=>i*5),m])].sort((a,b)=>a-b);
 return <fieldset className={`english-input ${className||''}`} lang="en" disabled={disabled}><legend>{label}</legend><div className="english-input-parts">
  <select aria-label={`${label}: Hour`} value={h} onChange={e=>onChange(`${pad(e.target.value)}:${pad(m)}`)}>{Array.from({length:24},(_,i)=><option key={i} value={i}>{hourLabel(i)}</option>)}</select>
  <select aria-label={`${label}: Minute`} value={m} onChange={e=>onChange(`${pad(h)}:${pad(e.target.value)}`)}>{minutes.map(i=><option key={i} value={i}>:{pad(i)}</option>)}</select>
 </div></fieldset>;
}

export function EnglishDateTimeField({label,value,onChange,disabled,className}) {
 const [date,time]=(value||'').split('T');
 return <fieldset className={`english-input english-date-time-field ${className||''}`} lang="en" disabled={disabled}><legend>{label}</legend><div className="english-input-row">
  <EnglishDate label="Date" value={date} disabled={disabled} onChange={d=>onChange(`${d}T${time||'09:00'}`)}/>
  <EnglishTime label="Time" value={time} disabled={disabled} onChange={t=>onChange(`${date||new Date().toISOString().slice(0,10)}T${t}`)}/>
 </div></fieldset>;
}

export function EnglishFileInput({label,name,accept,disabled,multiple,hint}) {
 const input=useRef(null),[files,setFiles]=useState([]);
 useEffect(()=>{const form=input.current?.form;if(!form)return;const clear=()=>setFiles([]);form.addEventListener('reset',clear);return()=>form.removeEventListener('reset',clear);},[]);
 return <div className="english-file" lang="en"><span className="english-file-label">{label}</span>
  <input ref={input} className="english-file-native" type="file" name={name} accept={accept} multiple={multiple} disabled={disabled} tabIndex={-1} aria-hidden="true" onChange={e=>setFiles([...e.target.files].map(f=>f.name))}/>
  <div className="english-file-row"><button type="button" disabled={disabled} onClick={()=>input.current?.click()}>{files.length?'Change file':'Choose file'}</button><span aria-live="polite">{files.length?files.join(', '):'No file selected'}</span></div>
  {hint&&<small>{hint}</small>}
 </div>;
}
