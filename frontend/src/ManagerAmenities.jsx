import {portalUrl} from './api';
import {keepInView} from './keepInView';
import React, {useEffect, useRef, useState} from 'react';
import {api} from './api';
import './amenity.css';
import {EnglishDate,EnglishTime,EnglishDateTimeField,EnglishFileInput} from './EnglishInputs';

const days=['MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY'];
const blank=()=>({name:'',type:'',location:'',capacity:1,slotDurationMinutes:60,chargeable:false,fee:0,description:'',maxSlotsPerDay:2,maxAdvanceDays:7});
const today=()=>new Intl.DateTimeFormat('en-CA',{timeZone:'America/Los_Angeles',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());
const blankClosure=()=>({startAt:`${today()}T09:00`,endAt:`${today()}T10:00`,reason:''});
const time=value=>new Intl.DateTimeFormat('en-US',{timeZone:'America/Los_Angeles',dateStyle:'medium',timeStyle:'short'}).format(new Date(value));

// focus = { id, seq } opens that facility for editing (e.g. from Search).
export default function ManagerAmenities({focus}) {
  const [rows,setRows]=useState([]),[selected,setSelected]=useState(null),[draft,setDraft]=useState(blank),[hours,setHours]=useState([]);
  const [busy,setBusy]=useState(false),[ready,setReady]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
  const [date,setDate]=useState(today),[reservations,setReservations]=useState([]),[loadedDate,setLoadedDate]=useState(''),[affected,setAffected]=useState([]);
  const [closure,setClosure]=useState(blankClosure),[closures,setClosures]=useState([]),[cancelling,setCancelling]=useState(null),[cancelReason,setCancelReason]=useState('');
  async function run(action){setBusy(true);setError('');setNotice('');try{await action();}catch(e){setError(e.message);}finally{setBusy(false);}}
  const detailRef=useRef(null);
  useEffect(()=>{if(!focus?.id||!ready)return;const a=rows.find(x=>x.id===focus.id);if(a)run(async()=>{await choose(a);keepInView(()=>detailRef.current,{block:'start'});});else setError('That facility is no longer available.');},[focus?.seq,ready]); // eslint-disable-line react-hooks/exhaustive-deps
  async function load(){const found=await api('/manager/amenities');setRows(found);setReady(true);return found;}
  useEffect(()=>{let active=true;api('/manager/amenities').then(r=>{if(active){setRows(r);setReady(true);}}).catch(e=>{if(active)setError(e.message);});return()=>{active=false;};},[]);
  function reset(){setSelected(null);setDraft(blank());setHours([]);setAffected([]);setClosure(blankClosure());setClosures([]);}
  async function choose(a){const [h,c]=await Promise.all([api(`/manager/amenities/${a.id}/hours`),api(`/manager/amenities/${a.id}/closures`)]);setSelected(a);setDraft({...a,description:a.description||''});setHours(h);setClosures(c);setAffected([]);setClosure(blankClosure());}
  async function loadClosures(){setClosures(await api(`/manager/amenities/${selected.id}/closures`));}
  async function removeClosure(c){await run(async()=>{await api(`/manager/amenities/${selected.id}/closures/${c.id}`,{method:'DELETE'});await loadClosures();await load();setNotice('Closure removed. Those times can be booked again.');});}
  async function save(e){e.preventDefault();await run(async()=>{const body={...draft};for(const k of ['capacity','slotDurationMinutes','fee','maxSlotsPerDay','maxAdvanceDays'])body[k]=Number(body[k]);const result=await api(selected?`/manager/amenities/${selected.id}`:'/manager/amenities',{method:selected?'PUT':'POST',data:body});setSelected(result);setDraft({...result,description:result.description||''});await load();setNotice(hours.length?'Facility saved.':'Facility saved. Configure opening hours to make time slots available.');});}
  async function listReservations(){if(!date)return;const found=await api(`/manager/reservations?date=${date}`);setReservations(found);setLoadedDate(date);}
  // Inline confirmation instead of window.prompt: works in every browser and keeps the buttons in English.
  function cancel(r){setCancelling(r);setCancelReason('');}
  async function confirmCancel(){const r=cancelling;await run(async()=>{await api(`/manager/reservations/${r.id}/cancel`,{method:'POST',data:{reason:cancelReason.trim()}});setCancelling(null);setAffected(a=>a.filter(item=>item.id!==r.id));if(loadedDate)await listReservations();setNotice('Reservation cancelled.');});}
  const cancelPanel=r=>cancelling?.id===r.id&&<div className="reservation-cancel-panel" role="group" aria-label={`Cancel reservation #${r.id}`}><p><strong>Cancel reservation #{r.id}</strong> · {r.guestName} · {time(r.startAt)}</p><label>Reason for the resident (optional)<textarea maxLength={1000} value={cancelReason} onChange={e=>setCancelReason(e.target.value)} disabled={busy}/></label><div className="actions"><button className="primary" disabled={busy} onClick={confirmCancel}>Confirm cancellation</button><button disabled={busy} onClick={()=>setCancelling(null)}>Keep reservation</button></div></div>;
  const field=(name,value)=>setDraft(d=>({...d,[name]:value}));
  return <div className="amenity-workspace">
    <section className="card"><div className="section-head"><div><span className="eyebrow">SHARED SPACES</span><h2>Amenities <span className="count">{rows.length}</span></h2></div><div className="actions"><button disabled={busy} onClick={()=>run(load)}>Refresh</button><button className="primary" disabled={busy} onClick={reset}>New facility</button></div></div>
      <p>Manage facilities, opening hours and reservations in your community.</p>
      {error&&<div className="message error" role="alert">{error}</div>}{notice&&<div className="message" role="status">{notice}</div>}
      {!ready?<p role="status">{error?'Unable to load facilities. Try Refresh.':'Loading facilities…'}</p>:!rows.length?<p className="empty">No facilities yet. Add your first facility below.</p>:<div className="amenity-picker">{rows.map(a=><button key={a.id} disabled={busy} aria-pressed={selected?.id===a.id} onClick={()=>run(()=>choose(a))}><strong>{a.name}</strong><small>{a.type} · {a.location}</small></button>)}</div>}
    </section>
    <section className="card" ref={detailRef}><span className="eyebrow">FACILITY DETAILS</span><h2>{selected?`Edit ${selected.name}`:'Add a facility'}</h2>
      <form onSubmit={save}><fieldset disabled={busy}><div className="grid">
        <label>Name<input value={draft.name} onChange={e=>field('name',e.target.value)} required maxLength={25}/></label>
        <label>Type<input value={draft.type} onChange={e=>field('type',e.target.value)} required maxLength={50} placeholder="Fitness, common room…"/></label>
        <label>Location<input value={draft.location} onChange={e=>field('location',e.target.value)} required maxLength={100}/></label>
        <label>Households per time slot<input type="number" min="1" step="1" value={draft.capacity} onChange={e=>field('capacity',e.target.value)} required/></label>
        <label>Session length (minutes)<input type="number" min="15" max="240" step="1" value={draft.slotDurationMinutes} onChange={e=>field('slotDurationMinutes',e.target.value)} required/></label>
        <label>Daily slots per household<input type="number" min="1" max="24" step="1" value={draft.maxSlotsPerDay} onChange={e=>field('maxSlotsPerDay',e.target.value)} required/></label>
        <label>Booking window (days)<input type="number" min="1" max="365" step="1" value={draft.maxAdvanceDays} onChange={e=>field('maxAdvanceDays',e.target.value)} required/></label>
        <label>Fee per session<input type="number" min="0" step="0.01" value={draft.fee} disabled={!draft.chargeable} onChange={e=>field('fee',e.target.value)} required/></label>
      </div><label className="check-label"><input type="checkbox" checked={draft.chargeable} onChange={e=>field('chargeable',e.target.checked)}/>Chargeable facility</label><p className="hint">Fees are recorded only. Online payment is not connected.</p>
      <label>Description<textarea value={draft.description} onChange={e=>field('description',e.target.value)} maxLength={1000}/></label><button className="primary">Save facility</button></fieldset></form>
    </section>
    {selected&&<>
      <section className="card"><span className="eyebrow">AVAILABILITY</span><h2>Opening hours · {selected.name}</h2><p>Weekly hours use Pacific Time. An empty schedule means the facility is closed.</p>
        <form onSubmit={e=>{e.preventDefault();run(async()=>{await api(`/manager/amenities/${selected.id}/hours`,{method:'PUT',data:{hours}});await load();setNotice('Opening hours saved.');});}}><fieldset disabled={busy}>
          {hours.map((h,i)=><div className="amenity-hours-row" key={i}><label>Day<select value={h.dayOfWeek} onChange={e=>setHours(all=>all.map((v,j)=>j===i?{...v,dayOfWeek:e.target.value}:v))}>{days.map(d=><option key={d}>{d}</option>)}</select></label><EnglishTime label="Opens" value={h.openTime} onChange={t=>setHours(all=>all.map((v,j)=>j===i?{...v,openTime:t}:v))}/><EnglishTime label="Closes" value={h.closeTime} onChange={t=>setHours(all=>all.map((v,j)=>j===i?{...v,closeTime:t}:v))}/><button type="button" aria-label={`Remove opening hours row ${i+1}`} onClick={()=>setHours(all=>all.filter((_,j)=>j!==i))}>Remove</button></div>)}
          <div className="actions"><button type="button" onClick={()=>setHours(all=>[...all,{dayOfWeek:'MONDAY',openTime:'09:00',closeTime:'18:00'}])}>Add hours</button><button className="primary">Save hours</button></div></fieldset></form>
      </section>
      <section className="card"><span className="eyebrow">FACILITY IMAGE</span><h2>Photo · {selected.name}</h2>{selected.imageUrl&&<img className="amenity-image" src={portalUrl(`/api/manager/amenities/${selected.id}/image?version=${encodeURIComponent(selected.imageUrl)}`)} alt={selected.name}/>}
        <form onSubmit={e=>{e.preventDefault();const form=e.currentTarget;const data=new FormData(form);run(async()=>{if(!data.get('file')?.size)throw new Error('Choose a JPG or PNG photo to upload.');const a=await api(`/manager/amenities/${selected.id}/image`,{method:'POST',data});setSelected({...a,imageUrl:a.imageUrl+'#'+Date.now()});await load();form.reset();setNotice('Photo uploaded.');});}}><EnglishFileInput label="Amenity photo" name="file" accept="image/jpeg,image/png" disabled={busy} hint="JPG or PNG, up to 5 MB."/><div className="actions"><button disabled={busy}>Upload photo</button>{selected.imageUrl&&<button type="button" disabled={busy} onClick={()=>run(async()=>{await api(`/manager/amenities/${selected.id}/image`,{method:'DELETE'});setSelected(a=>({...a,imageUrl:null}));await load();setNotice('Photo removed.');})}>Remove photo</button>}</div></form>
      </section>
      <section className="card"><span className="eyebrow">TEMPORARY CLOSURE</span><h2>Close a time range</h2><p>Times use Pacific Time. Existing reservations are listed for review; they are not automatically cancelled.</p>
        <form onSubmit={e=>{e.preventDefault();run(async()=>{setAffected(await api(`/manager/amenities/${selected.id}/closures`,{method:'POST',data:closure}));await loadClosures();await load();setNotice('Closure saved. Review any affected reservations below.');});}}><fieldset disabled={busy}><div className="english-range"><EnglishDateTimeField label="From (Pacific Time)" value={closure.startAt} onChange={v=>setClosure(c=>({...c,startAt:v}))}/><EnglishDateTimeField label="Until (Pacific Time)" value={closure.endAt} onChange={v=>setClosure(c=>({...c,endAt:v}))}/></div><label>Reason (optional)<textarea maxLength={1000} value={closure.reason} onChange={e=>setClosure(c=>({...c,reason:e.target.value}))}/></label><button>Save closure</button></fieldset></form>
        <h3>Current and upcoming closures</h3>{!closures.length?<p className="empty">No closures scheduled.</p>:<ul className="amenity-closures">{closures.map(c=><li key={c.id} className="section-head"><p>{time(c.startAt)} – {time(c.endAt)}{c.reason&&<> · {c.reason}</>}</p><button type="button" disabled={busy} onClick={()=>removeClosure(c)}>Remove closure</button></li>)}</ul>}
        {affected.length>0&&<div className="help-box"><h3>Affected reservations</h3>{affected.map(r=><React.Fragment key={r.id}><div className="section-head"><p>#{r.id} · {r.guestName} · {time(r.startAt)}</p><button disabled={busy} onClick={()=>cancel(r)}>Cancel reservation</button></div>{cancelPanel(r)}</React.Fragment>)}</div>}
      </section>
    </>}
    <section className="card"><span className="eyebrow">RESERVATION MANAGEMENT</span><h2>Community reservations</h2><form className="amenity-filter" onSubmit={e=>{e.preventDefault();run(listReservations);}}><EnglishDate label="Date (Pacific Time)" value={date} onChange={setDate}/><button disabled={busy}>View / refresh</button></form>
      {loadedDate&&<><p className="hint">Showing {loadedDate}</p>{!reservations.length?<p className="empty">No reservations on this date.</p>:<div className="table-wrap"><table><thead><tr><th>Facility</th><th>Resident</th><th>Time (Pacific)</th><th>Status</th><th>Actions</th></tr></thead><tbody>{reservations.map(r=><React.Fragment key={r.id}><tr><td>{r.amenityName}</td><td>{r.guestName}<small>Room {r.room}</small></td><td>{time(r.startAt)}</td><td><span className={`badge reservation-status-${r.status}`}>{r.status}</span></td><td>{r.status==='UPCOMING'&&<button disabled={busy} onClick={()=>cancel(r)}>Cancel</button>}</td></tr>{cancelling?.id===r.id&&<tr><td colSpan={5}>{cancelPanel(r)}</td></tr>}</React.Fragment>)}</tbody></table></div>}</>}
    </section>
  </div>;
}
