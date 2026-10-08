import React, {useEffect, useState} from 'react';
import {api} from './api';
import Pager from './Pager';
import {confirmAction} from './englishUi';
import './locker-management.css';

const date=value=>value?new Date(value).toLocaleString('en-US',{dateStyle:'medium',timeStyle:'short'}):'—';
const expired=c=>c.expiresAt&&new Date(c.expiresAt)<=new Date();
const statusLabels={PENDING_PICKUP:'Waiting for pickup',PICKED_UP:'Picked up',EXPIRED:'Expired',RETRIEVED:'Retrieved'};

/**
 * Delivery companies (scope "company") manage their own couriers' kiosk store codes and see their deliveries.
 * The property manager (scope "manager") manages independent couriers and can review every courier.
 */
export default function CourierManager({scope}) {
 const company=scope==='company';
 const base=company?'/provider/deliveries/couriers':'/manager/couriers';
 const [rows,setRows]=useState([]),[deliveries,setDeliveries]=useState(null),[deliveryPage,setDeliveryPage]=useState(0),[issued,setIssued]=useState(null);
 const [busy,setBusy]=useState(false),[ready,setReady]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState(''),[adding,setAdding]=useState(false),[editing,setEditing]=useState(null);
 async function load(page=deliveryPage){
  const [couriers,parcels]=await Promise.all([api(base),company?api(`/provider/deliveries/parcels/page?page=${page}&size=20`):Promise.resolve(null)]);
  setRows(couriers);setDeliveries(parcels);setDeliveryPage(page);setReady(true);
 }
 const showDeliveries=page=>run(async()=>{setDeliveries(await api(`/provider/deliveries/parcels/page?page=${page}&size=20`));setDeliveryPage(page);});
 async function run(work,message){setBusy(true);setError('');setNotice('');try{await work();if(message)setNotice(message);}catch(e){setError(e.message);}finally{setBusy(false);}}
 useEffect(()=>{run(load);},[]);
 function add(event){
  event.preventDefault();const form=event.currentTarget;const data=Object.fromEntries(new FormData(form));data.temporary=data.temporary==='on';
  run(async()=>{const result=await api(base,{method:'POST',data});setIssued(result);form.reset();setAdding(false);await load();},'Courier added. Give them the store code privately.');
 }
 // Name, phone and (for independent couriers) company can change; the store code stays the same.
 function save(event,c){
  event.preventDefault();const data=Object.fromEntries(new FormData(event.currentTarget));
  run(async()=>{await api(`${base}/${c.id}`,{method:'PUT',data});setEditing(null);await load();},`Saved changes for ${data.name.trim()}.`);
 }
 const managed=rows.filter(c=>c.manageable),others=rows.filter(c=>!c.manageable);
 const table=(list,manageable)=><div className="table-wrap"><table><thead><tr><th>Courier</th>{!company&&<th>Company</th>}<th>Store code</th><th>Status</th>{manageable&&<th>Actions</th>}</tr></thead><tbody>
  {list.map(c=><React.Fragment key={c.id}><tr><td><strong>{c.name}</strong>{c.phone&&<small>{c.phone}</small>}<small>Added {date(c.createdAt)}</small></td>
   {!company&&<td>{c.companyId?c.companyName:c.companyName?`${c.companyName} · independent`:'Independent'}</td>}
   <td>{c.storeCode?<span className="courier-store-code"><code>{c.storeCode}</code><button className="courier-copy" disabled={busy} aria-label={`Copy store code for ${c.name}`} onClick={()=>run(()=>navigator.clipboard.writeText(c.storeCode),`Code for ${c.name} copied.`)}>Copy</button></span>
    :<><code>••••{c.codeHint}</code>{c.manageable&&<small>Create a new code to view it here</small>}</>}</td>
   <td><span className={`badge ${c.active&&!expired(c)?'APPROVED':'REJECTED'}`}>{!c.active?'Disabled':expired(c)?'Expired':'Active'}</span>{c.expiresAt&&<small>{expired(c)?'Temporary code expired':'Temporary · until'} {date(c.expiresAt)}</small>}</td>
   {manageable&&<td><div className="actions">
    <button disabled={busy} aria-expanded={editing===c.id} onClick={()=>setEditing(id=>id===c.id?null:c.id)}>{editing===c.id?'Close':'Edit'}</button>
    <button disabled={busy} onClick={async()=>{if(await confirmAction({title:'Create a new store code?',message:`The current code for ${c.name} stops working immediately.`,confirmLabel:'Create new code'}))run(async()=>{setIssued(await api(`${base}/${c.id}/store-code`,{method:'POST'}));await load();},'New store code created. The old code no longer works.');}}>New code</button>
    <button disabled={busy} onClick={()=>run(async()=>{await api(`${base}/${c.id}/active`,{method:'PUT',data:{active:!c.active}});await load();},c.active?'Courier disabled. Their code no longer works.':'Courier enabled.')}>{c.active?'Disable':'Enable'}</button>
   </div></td>}
  </tr>
  {manageable&&editing===c.id&&<tr className="courier-edit-row"><td colSpan={company?4:5}><form className="courier-edit" onSubmit={e=>save(e,c)} aria-label={`Edit ${c.name}`}>
   <div className="grid"><label>Courier name<input name="name" required maxLength={100} defaultValue={c.name}/></label><label>Phone (optional)<input name="phone" maxLength={30} defaultValue={c.phone||''}/></label>
    {!company&&<label>Delivery company (optional)<input name="carrier" maxLength={100} defaultValue={c.companyName||''} placeholder="Leave blank for an independent courier"/></label>}</div>
   <p className="hint">The store code stays the same. Packages already stored keep the name recorded at delivery.</p>
   <div className="actions"><button className="primary" disabled={busy}>Save changes</button><button type="button" disabled={busy} onClick={()=>setEditing(null)}>Cancel</button></div>
  </form></td></tr>}
  </React.Fragment>)}
 </tbody></table></div>;
 return <section className="card courier-manager">
  <div className="section-head"><div><span className="eyebrow">{company?'DELIVERY PARTNER':'PACKAGE LOCKERS'}</span><h2>{company?'Couriers & store codes':'Couriers'}</h2>
   <p>{company?'Each courier stores packages at the locker kiosk with a personal eight-digit store code. Add your couriers, give each one their code privately, and disable a code when someone leaves.':'Delivery companies manage their own couriers. Add independent couriers here, or give a walk-in courier without a code a temporary code that works for the rest of today.'}</p></div>
   <button className="primary" disabled={busy} onClick={()=>{setAdding(v=>!v);setIssued(null);}}>{adding?'Cancel':'Add courier'}</button></div>
  {error&&<div className="message error" role="alert">{error}</div>}{notice&&<div className="message" role="status">{notice}</div>}
  {adding&&<form className="detail" onSubmit={add}><div className="grid"><label>Courier name<input name="name" required maxLength={100}/></label><label>Phone (optional)<input name="phone" maxLength={30}/></label></div>
   {!company&&<><label>Delivery company (optional)<input name="carrier" maxLength={100} placeholder="e.g. Amazon, a local courier service"/></label>
    <label className="check-label"><input type="checkbox" name="temporary" defaultChecked/>Temporary code for a walk-in courier · works until the end of today</label></>}
   <button className="primary" disabled={busy}>Create store code</button></form>}
  {issued&&<div className="help-box courier-code"><strong>Store code for {issued.courier.name}</strong><code aria-label="Store code">{issued.storeCode}</code><p>Share it with the courier privately; they enter it at the locker kiosk under “I’m a courier”.{issued.courier.expiresAt?` It works until ${date(issued.courier.expiresAt)}.`:''} You can see it again in the list below anytime.</p><div className="actions"><button onClick={()=>run(()=>navigator.clipboard.writeText(issued.storeCode),'Code copied.')}>Copy code</button><button onClick={()=>setIssued(null)}>Done</button></div></div>}
  {!ready?<p role="status">Loading couriers…</p>:<>
   {company||managed.length?<>{!company&&<h3>Independent couriers</h3>}{managed.length?table(managed,true):<p className="empty">{company?'No couriers yet. Add your first courier to create a store code.':'No independent couriers yet.'}</p>}</>:null}
   {!company&&<><h3>Company couriers</h3><p className="hint">Managed by their delivery company. You can see who stores packages, but codes are managed by the company.</p>{others.length?table(others,false):<p className="empty">No company couriers yet.</p>}</>}
  </>}
  {company&&ready&&<><h3>Recent deliveries</h3>{deliveries?.items.length?<><div className="table-wrap"><table><thead><tr><th>Stored</th><th>Courier</th><th>Locker</th><th>Status</th></tr></thead><tbody>{deliveries.items.map(d=><tr key={d.id}><td>{date(d.storedAt)}</td><td>{d.courierName||'—'}</td><td>{d.lockerLocation} · Cell {d.cellNumber}</td><td>{statusLabels[d.status]||d.status}</td></tr>)}</tbody></table></div><Pager data={deliveries} noun="packages" busy={busy} onPage={showDeliveries}/></>:<p className="empty">No packages stored by your couriers yet.</p>}</>}
 </section>;
}
