import React, {useEffect,useState} from 'react';
import {api,isCancelled} from './api';
import {confirmAction} from './englishUi';
import {EnglishDate} from './EnglishInputs';

export const perkCategories=[['DINING','Dining'],['SHOPPING','Shopping'],['SERVICES','Services'],['ENTERTAINMENT','Entertainment'],['HEALTH','Health'],['OTHER','Other']];
const categoryLabel=value=>perkCategories.find(([v])=>v===value)?.[1]||value;
const states=[['active','Active'],['upcoming','Upcoming'],['expired','Expired'],['all','All']];
const pad=n=>String(n).padStart(2,'0');
const dateOf=instant=>{if(!instant)return '';const d=new Date(instant);return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}`;};
const today=()=>dateOf(new Date());
const inDays=days=>dateOf(new Date(Date.now()+days*86400000));
const day=instant=>new Date(instant).toLocaleDateString('en-US',{dateStyle:'medium'});
const state=p=>p.active?'active':p.startAt&&new Date(p.startAt)>new Date()?'upcoming':'expired';
const safeWebsite=url=>/^https?:\/\//i.test(url||'')?url:null;

/** Local perks: offers from nearby businesses with a validity window. Managers publish, hide and edit them; residents see published ones. */
export default function LocalPerks({user}) {
 const manager=user.role==='MANAGER',base=manager?'/manager/perks':'/perks';
 const [perks,setPerks]=useState([]),[loading,setLoading]=useState(true),[busy,setBusy]=useState(false),[again,setAgain]=useState(0);
 const [error,setError]=useState(''),[notice,setNotice]=useState(''),[editing,setEditing]=useState(null);
 const [category,setCategory]=useState(''),[status,setStatus]=useState(manager?'all':'active'),[sort,setSort]=useState('new');
 const [details,setDetails]=useState({}),[open,setOpen]=useState(null);
 useEffect(()=>{
  const cancel=new AbortController();setLoading(true);setError('');
  const query=new URLSearchParams({status,sort});if(category)query.set('category',category);
  api(`${base}?${query}`,{signal:cancel.signal}).then(setPerks).catch(e=>{if(!isCancelled(e))setError(e.message);}).finally(()=>{if(!cancel.signal.aborted)setLoading(false);});
  return()=>cancel.abort();
 },[base,category,status,sort,again]);
 async function run(work,message){setBusy(true);setError('');setNotice('');try{await work();if(message)setNotice(message);}catch(e){setError(e.message);}finally{setBusy(false);}}
 const reload=()=>{setDetails({});setOpen(null);setAgain(n=>n+1);};
 async function detail(id){if(details[id])return details[id];const d=await api(`${base}/${id}`);setDetails(old=>({...old,[id]:d}));return d;}
 const toggle=id=>run(async()=>{if(open===id){setOpen(null);return;}await detail(id);setOpen(id);});
 const edit=id=>run(async()=>{setEditing(await detail(id));});
 async function save(data){await api(editing.id?`${base}/${editing.id}`:base,{method:editing.id?'PUT':'POST',data});setEditing(null);reload();}
 const publish=p=>run(async()=>{await api(`${base}/${p.id}/publish`,{method:'POST',data:{published:!p.published}});reload();},p.published?'Perk hidden from residents.':'Perk published.');
 const remove=p=>run(async()=>{if(!await confirmAction({title:'Delete this perk?',message:`"${p.title}" will be removed for everyone.`,confirmLabel:'Delete perk',danger:true}))return;await api(`${base}/${p.id}`,{method:'DELETE'});setPerks(old=>old.filter(x=>x.id!==p.id));});
 return <div className="local-perks">
  <div className="hub-toolbar"><p>{manager?'Share discounts and offers from local businesses with residents.':'Discounts and offers from businesses near your community.'}</p>
   <div className="actions"><button disabled={busy} onClick={reload}>Refresh</button>{manager&&<button className="primary" disabled={busy} onClick={()=>setEditing(editing?null:{})}>{editing?'Close form':'New perk'}</button>}</div></div>
  {error&&<p className="message error" role="alert">{error}</p>}{notice&&<p className="message" role="status">{notice}</p>}
  {editing&&<PerkForm key={editing.id||'new'} perk={editing} busy={busy} onCancel={()=>setEditing(null)} onSave={data=>run(()=>save(data),editing.id?'Perk updated.':'Perk published.')}/>}
  <div className="perk-controls">
   <div className="hub-filters" role="group" aria-label="Filter by category"><button type="button" aria-pressed={!category} onClick={()=>setCategory('')}>All</button>
    {perkCategories.map(([v,text])=><button key={v} type="button" aria-pressed={category===v} onClick={()=>setCategory(v)}>{text}</button>)}</div>
   <div className="perk-selects"><label>Show<select value={status} onChange={e=>setStatus(e.target.value)}>{states.map(([v,text])=><option key={v} value={v}>{text}</option>)}</select></label>
    <label>Sort<select value={sort} onChange={e=>setSort(e.target.value)}><option value="new">Newest</option><option value="ending">Ending soonest</option></select></label></div>
  </div>
  {loading?<p role="status">Loading perks…</p>:!perks.length?<p className="empty">{manager&&status==='all'&&!category?'No perks yet. Add an offer from a local business.':'No offers match these filters.'}</p>
   :<div className="perk-grid">{perks.map(p=>{const s=state(p),d=open===p.id&&details[p.id],website=safeWebsite(d?.website);return <article key={p.id} className={`card perk-card ${s!=='active'?'expired':''}`}>
     <span className="eyebrow">{categoryLabel(p.category)}</span>
     <h3>{p.title}</h3><strong className="perk-business">{p.businessName}</strong>
     <small>{s==='expired'?<span className="badge REJECTED">Ended {day(p.endAt)}</span>:s==='upcoming'?<><span className="badge">Starts {day(p.startAt)}</span> · until {day(p.endAt)}</>:`Valid through ${day(p.endAt)}`}
      {manager&&!p.published&&<> <span className="badge">Hidden from residents</span></>}</small>
     {d&&<div className="perk-details"><p>{d.description}</p>
      {(d.contact||d.address||website)&&<small>{[d.contact,d.address].filter(Boolean).join(' · ')}{(d.contact||d.address)&&website&&' · '}{website&&<a href={website} target="_blank" rel="noopener noreferrer">Website ↗</a>}</small>}</div>}
     <div className="actions"><button disabled={busy} aria-expanded={open===p.id} onClick={()=>toggle(p.id)}>{open===p.id?'Hide details':'Details'}</button>
      {manager&&<><button disabled={busy} onClick={()=>edit(p.id)}>Edit</button><button disabled={busy} onClick={()=>publish(p)}>{p.published?'Hide':'Publish'}</button><button disabled={busy} onClick={()=>remove(p)}>Delete</button></>}</div>
    </article>;})}</div>}
 </div>;
}

function PerkForm({perk,busy,onSave,onCancel}) {
 const [hasStart,setHasStart]=useState(Boolean(perk.startAt)),[startOn,setStartOn]=useState(dateOf(perk.startAt)||today()),[endOn,setEndOn]=useState(dateOf(perk.endAt)||inDays(30));
 const [error,setError]=useState('');
 function submit(e){
  e.preventDefault();setError('');
  if(hasStart&&endOn<startOn){setError('The end date must be on or after the start date.');return;}
  const data=Object.fromEntries(new FormData(e.currentTarget));
  onSave({...data,startAt:hasStart?new Date(`${startOn}T00:00:00`).toISOString():null,endAt:new Date(`${endOn}T23:59:59`).toISOString()});
 }
 return <form className="card hub-form" onSubmit={submit}><h3>{perk.id?'Edit perk':'Add a local perk'}</h3>
  <div className="grid"><label>Business name<input name="businessName" required maxLength={60} defaultValue={perk.businessName}/></label>
   <label>Category<select name="category" required defaultValue={perk.category||''}><option value="" disabled>Choose a category</option>{perkCategories.map(([v,text])=><option key={v} value={v}>{text}</option>)}</select></label></div>
  <label>Offer<input name="title" required maxLength={80} defaultValue={perk.title} placeholder="e.g. 15% off any coffee"/></label>
  <label>Details<textarea name="description" required maxLength={1000} defaultValue={perk.description} placeholder="What residents get and how to redeem it"/></label>
  <div className="grid"><label>Contact (optional)<input name="contact" maxLength={40} defaultValue={perk.contact} placeholder="Phone or email"/></label>
   <label>Address (optional)<input name="address" maxLength={160} defaultValue={perk.address}/></label></div>
  <label>Website (optional)<input name="website" type="url" maxLength={200} defaultValue={perk.website} placeholder="https://"/></label>
  <div className="grid"><div><label className="check-label"><input type="checkbox" checked={hasStart} onChange={e=>setHasStart(e.target.checked)}/> Starts on a later date</label>
   {hasStart&&<EnglishDate label="Starts" value={startOn} onChange={setStartOn}/>}</div>
   <EnglishDate label="Valid through" value={endOn} onChange={setEndOn}/></div>
  {error&&<p className="message error" role="alert">{error}</p>}
  <div className="actions"><button className="primary" disabled={busy}>{perk.id?'Save changes':'Publish perk'}</button><button type="button" disabled={busy} onClick={onCancel}>Cancel</button></div>
 </form>;
}
