import {portalUrl} from './api';
import {keepInView} from './keepInView';
import React, {useEffect,useRef,useState} from 'react';
import {api} from './api';
import {usd} from './money';
import AmenitySlots from './AmenitySlots';
import MyReservations from './MyReservations';
import './amenity.css';
// focus = { id, seq } opens that amenity's time slots (e.g. from Search).
export default function ResidentAmenities({user,focus}) {
 const [rows,setRows]=useState([]),[selected,setSelected]=useState(null),[loading,setLoading]=useState(true),[error,setError]=useState(''),[version,setVersion]=useState(0);
 useEffect(()=>{let active=true;setLoading(true);api('/resident/amenities').then(r=>{if(active){setRows(r);setError('');}}).catch(e=>{if(active)setError(e.message);}).finally(()=>{if(active)setLoading(false);});return()=>{active=false;};},[version]);
 const changed=()=>setVersion(v=>v+1);
 const slotsRef=useRef(null);
 useEffect(()=>{if(!focus?.id||loading)return;const a=rows.find(x=>x.id===focus.id);if(a){setSelected(a);keepInView(()=>slotsRef.current,{block:'start'});}else setError('That amenity is no longer available.');},[focus?.seq,loading]); // eslint-disable-line react-hooks/exhaustive-deps
 return <div className="amenity-workspace"><section className="card"><div className="section-head"><div><span className="eyebrow">SHARED SPACES</span><h2>Community amenities</h2></div><button onClick={changed} disabled={loading}>Refresh</button></div><p>Choose a space and reserve a time. All times use Pacific Time.</p>
 {error&&<p className="message error" role="alert">{error}</p>}{loading?<p role="status">Loading amenities…</p>:!rows.length?<p className="empty">No amenities have been added to your community yet.</p>:<div className="amenity-grid">{rows.map(a=><article className="amenity-card" key={a.id}>{a.imageUrl&&<img className="amenity-image" src={portalUrl(a.imageUrl)} alt={a.name}/>}<span className="eyebrow">{a.type}</span><h3>{a.name}</h3><p>{a.description}</p><dl className="amenity-facts"><div><dt>Location</dt><dd>{a.location}</dd></div><div><dt>Capacity</dt><dd>{a.capacity} {a.capacity===1?'household':'households'} per time slot</dd></div><div><dt>Session</dt><dd>{a.slotDurationMinutes} minutes</dd></div><div><dt>Fee</dt><dd>{a.chargeable?usd(a.fee)+' per session':'Free'}</dd></div></dl><p className="hint">{a.hasAvailableSlotsToday?'Available today':'No available slots today'}</p><button className="primary" onClick={()=>setSelected(a)}>View time slots</button></article>)}</div>}</section>
 {selected&&<div ref={slotsRef}><AmenitySlots key={selected.id} amenity={rows.find(a=>a.id===selected.id)||selected} user={user} refreshVersion={version} onReservationsChanged={changed} onClose={()=>setSelected(null)}/></div>}
 <MyReservations refreshVersion={version} onReservationsChanged={changed}/></div>;
}
