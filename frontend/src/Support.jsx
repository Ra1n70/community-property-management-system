import React, {useEffect,useState} from 'react';
import {api} from './api';
import {confirmAction} from './englishUi';
import './community-hub.css';

const contactFields=[['officePhone','Office phone',40,'tel'],['email','Office email',254,'email'],['officeHours','Office hours',200,'text'],['address','Office address',200,'text'],['emergencyPhone','Emergency phone (after hours)',40,'tel']];

/** Contact details and FAQ beside the Messages conversation. Questions are asked through the conversation itself. */
export function SupportPanel({user}) {
 const [support,setSupport]=useState(null),[error,setError]=useState('');
 useEffect(()=>{api('/support').then(setSupport).catch(e=>setError(e.message));},[]);
 if(error)return <aside className="card support-panel"><p className="message error" role="alert">{error}</p></aside>;
 if(!support)return <aside className="card support-panel"><p role="status">Loading contact details…</p></aside>;
 const c=support.contact,hasContact=contactFields.some(([key])=>c[key]);
 return <aside className="card support-panel" aria-label="Contact and support">
  <span className="eyebrow">CONTACT & SUPPORT</span><h2>Property office</h2>
  {hasContact?<dl className="support-contact">
   {c.officePhone&&<><dt>Phone</dt><dd><a href={`tel:${c.officePhone}`}>{c.officePhone}</a></dd></>}
   {c.email&&<><dt>Email</dt><dd><a href={`mailto:${c.email}`}>{c.email}</a></dd></>}
   {c.officeHours&&<><dt>Hours</dt><dd>{c.officeHours}</dd></>}
   {c.address&&<><dt>Office</dt><dd>{c.address}</dd></>}
   {c.emergencyPhone&&<><dt>Emergencies</dt><dd><a href={`tel:${c.emergencyPhone}`}>{c.emergencyPhone}</a></dd></>}
  </dl>:<p className="hint">{user.role==='MANAGER'?'No contact details yet. Add them under Community settings.':'Contact details have not been added yet.'}</p>}
  <p className="hint">{user.role==='MANAGER'?'Residents see this panel next to their conversation.':'For anything else, send a message — property management replies here. Repairs go through Maintenance on your home page.'}</p>
  <h3>Frequently asked questions</h3>
  {support.faqs.length?<div className="support-faqs">{support.faqs.map(f=><details key={f.id}><summary>{f.question}</summary><p>{f.answer}</p></details>)}</div>
   :<p className="hint">{user.role==='MANAGER'?'No questions yet. Add them under Community settings.':'No questions have been added yet.'}</p>}
 </aside>;
}

/** Manager editor for the contact details and FAQ, shown under Community settings. */
export function SupportSettings() {
 const [support,setSupport]=useState(null),[busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState(''),[editing,setEditing]=useState(null);
 async function run(work,message){setBusy(true);setError('');setNotice('');try{await work();if(message)setNotice(message);}catch(e){setError(e.message);}finally{setBusy(false);}}
 const load=async()=>setSupport(await api('/support'));
 useEffect(()=>{run(load);},[]);
 if(!support)return <section className="card"><span className="eyebrow">CONTACT & SUPPORT</span><h2>Contact details and FAQ</h2>{error?<p className="message error" role="alert">{error}</p>:<p role="status">Loading…</p>}</section>;
 const faqs=support.faqs;
 function move(index,step){const ids=faqs.map(f=>f.id);[ids[index],ids[index+step]]=[ids[index+step],ids[index]];run(async()=>{const next=await api('/support/faqs/order',{method:'PUT',data:{ids}});setSupport(old=>({...old,faqs:next}));});}
 return <section className="card support-settings"><span className="eyebrow">CONTACT & SUPPORT</span><h2>Contact details and FAQ</h2>
  <p>Residents see these on the Messages page, next to their conversation with property management.</p>
  {error&&<p className="message error" role="alert">{error}</p>}{notice&&<p className="message" role="status">{notice}</p>}
  <form key={support.contact.version??'new'} onSubmit={e=>{e.preventDefault();const data=Object.fromEntries(new FormData(e.currentTarget));run(async()=>{const contact=await api('/support/contact',{method:'PUT',data:{...data,version:support.contact.version}});setSupport(old=>({...old,contact}));},'Contact details saved.');}}>
   <div className="grid">{contactFields.map(([key,label,max,type])=><label key={key}>{label} (optional)<input name={key} type={type} maxLength={max} defaultValue={support.contact[key]||''}/></label>)}</div>
   <button className="primary" disabled={busy}>Save contact details</button>
  </form>
  <h3>Frequently asked questions <span className="count">{faqs.length}</span></h3>
  <ol className="faq-editor">{faqs.map((f,i)=><li key={f.id}>{editing?.id===f.id?<FaqForm faq={f} busy={busy} onCancel={()=>setEditing(null)} onSave={data=>run(async()=>{await api(`/support/faqs/${f.id}`,{method:'PUT',data:{...data,version:f.version}});await load();setEditing(null);},'Question updated.')}/>
   :<><div><strong>{f.question}</strong><p>{f.answer}</p></div><div className="actions"><button disabled={busy||i===0} onClick={()=>move(i,-1)} aria-label={`Move "${f.question}" up`}>↑</button><button disabled={busy||i===faqs.length-1} onClick={()=>move(i,1)} aria-label={`Move "${f.question}" down`}>↓</button><button disabled={busy} onClick={()=>setEditing(f)}>Edit</button><button disabled={busy} onClick={()=>run(async()=>{if(!await confirmAction({title:'Delete this question?',message:f.question,confirmLabel:'Delete question',danger:true}))return;await api(`/support/faqs/${f.id}`,{method:'DELETE'});await load();},'')}>Delete</button></div></>}</li>)}</ol>
  {editing?.id==null&&(editing?<FaqForm faq={{}} busy={busy} onCancel={()=>setEditing(null)} onSave={data=>run(async()=>{await api('/support/faqs',{method:'POST',data});await load();setEditing(null);},'Question added.')}/>
   :<button disabled={busy||faqs.length>=50} onClick={()=>setEditing({})}>Add a question</button>)}
 </section>;
}

function FaqForm({faq,busy,onSave,onCancel}) {
 return <form className="detail faq-form" onSubmit={e=>{e.preventDefault();onSave(Object.fromEntries(new FormData(e.currentTarget)));}}>
  <label>Question<input name="question" required maxLength={200} defaultValue={faq.question} placeholder="e.g. Where do I park visitors?"/></label>
  <label>Answer<textarea name="answer" required maxLength={2000} defaultValue={faq.answer}/></label>
  <div className="actions"><button className="primary" disabled={busy}>{faq.id?'Save question':'Add question'}</button><button type="button" disabled={busy} onClick={onCancel}>Cancel</button></div>
 </form>;
}
