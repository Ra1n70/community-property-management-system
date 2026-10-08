import React, { useRef, useState } from 'react';
import { api } from './api';
import { usd } from './money';
export default function ReservationForm({ amenity, startAt, user, onBooked, onConflict }) {
  const [busy,setBusy]=useState(false),[error,setError]=useState(''),[uncertain,setUncertain]=useState(false);
  const inFlight=useRef(false);
  async function submit(e) {
    e.preventDefault(); if(inFlight.current||uncertain)return;
    inFlight.current=true;setBusy(true);setError('');
    try {const result=await api('/resident/reservations',{method:'POST',data:{amenityId:amenity.id,startAt}});onBooked(result);}
    catch(e){if(e.status===409){onConflict(e.message);return;}if(!e.status||e.status>=500){setUncertain(true);setError('The result is uncertain. Check My reservations before booking again.');}else setError(e.message);}
    finally{inFlight.current=false;setBusy(false);}
  }
  return <form className="reservation-form" onSubmit={submit}><h3>Confirm your reservation</h3>
    <p><strong>{amenity.name}</strong> · {new Intl.DateTimeFormat('en-US',{timeZone:'America/Los_Angeles',dateStyle:'medium',timeStyle:'short'}).format(new Date(startAt))} (Pacific Time)</p>
    <p>{user.name} · Room {user.room} · {amenity.slotDurationMinutes} minutes</p>
    <p>{amenity.chargeable?`Fee: ${usd(amenity.fee)}. Online payment is not available yet.`:'Free'}</p>
    <p className="hint">Cancel more than two hours before the start time.</p>
    {error&&<p className="message error" role="alert">{error}</p>}
    <button className="primary" disabled={busy||uncertain}>{busy?'Reserving…':'Confirm reservation'}</button>
    {uncertain&&<a className="reservation-history-link" href="#my-reservations">Check My reservations</a>}
  </form>;
}
