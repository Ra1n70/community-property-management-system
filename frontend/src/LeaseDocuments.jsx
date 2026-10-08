import React, {useEffect,useState} from 'react';
import {api,portalUrl} from './api';
import {confirmAction} from './englishUi';
import {EnglishFileInput} from './EnglishInputs';
import './community-hub.css';

const day=value=>value?new Date(`${value}T12:00:00`).toLocaleDateString('en-US',{dateStyle:'medium'}):'';
const size=bytes=>bytes<1024*1024?`${Math.max(1,Math.round(bytes/1024))} KB`:`${(bytes/1024/1024).toFixed(1)} MB`;
const term=d=>d.startsOn||d.endsOn?`${day(d.startsOn)||'…'} – ${day(d.endsOn)||'…'}`:'';
const fileUrl=d=>portalUrl(`/api/lease-documents/${d.id}/file`);

function DocumentList({documents,onDelete,busy,emptyText}) {
 if(!documents.length)return <p className="empty">{emptyText}</p>;
 return <ul className="lease-list">{documents.map(d=><li key={d.id}>
  <span className="lease-icon" aria-hidden="true">PDF</span>
  <div><strong>{d.title}</strong><small>{[term(d),`${d.filename} · ${size(d.sizeBytes)}`,`Shared ${new Date(d.uploadedAt).toLocaleDateString('en-US',{dateStyle:'medium'})} by ${d.uploadedByName}`].filter(Boolean).join(' · ')}</small></div>
  <div className="actions"><a className="button-link" href={fileUrl(d)}>Download</a>{onDelete&&<button disabled={busy} onClick={()=>onDelete(d)}>Delete</button>}</div>
 </li>)}</ul>;
}

/** The resident's own lease documents, shared by property management. */
export default function LeaseDocuments() {
 const [documents,setDocuments]=useState([]),[loading,setLoading]=useState(true),[error,setError]=useState('');
 const load=()=>{setLoading(true);setError('');api('/lease-documents').then(setDocuments).catch(e=>setError(e.message)).finally(()=>setLoading(false));};
 useEffect(load,[]);
 return <section className="card"><div className="section-head"><div><span className="eyebrow">LEASE</span><h2>Lease documents</h2><p>Your lease and related documents from property management. Only you and the property office can open them.</p></div><button disabled={loading} onClick={load}>Refresh</button></div>
  {error&&<p className="message error" role="alert">{error}</p>}
  {loading?<p role="status">Loading documents…</p>:<DocumentList documents={documents} emptyText="No documents yet. Property management will share your lease here."/>}
 </section>;
}

/** Manager view inside a resident's account details: upload, download and delete that resident's documents. */
export function ResidentLeaseDocuments({resident}) {
 const [documents,setDocuments]=useState([]),[busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
 async function run(work,message){setBusy(true);setError('');setNotice('');try{await work();if(message)setNotice(message);}catch(e){setError(e.message);}finally{setBusy(false);}}
 const load=async()=>setDocuments(await api(`/lease-documents?residentId=${resident.id}`));
 useEffect(()=>{run(load);},[resident.id]); // eslint-disable-line react-hooks/exhaustive-deps
 function upload(e){
  e.preventDefault();const form=e.currentTarget,data=new FormData(form),file=data.get('file');
  if(!file||!file.size){setError('Choose a PDF file.');return;}
  if(file.size>5*1024*1024){setError('The PDF must be 5 MB or smaller.');return;}
  const body=new FormData();
  body.append('request',new Blob([JSON.stringify({residentId:resident.id,title:data.get('title'),startsOn:data.get('startsOn')||null,endsOn:data.get('endsOn')||null})],{type:'application/json'}));
  body.append('file',file);
  run(async()=>{await api('/lease-documents',{method:'POST',data:body});form.reset();await load();},'Document shared. The resident can download it from My Account.');
 }
 return <div className="lease-manager">
  <p>Share lease PDFs with {resident.name}. Only this resident and managers of your community can open them.</p>
  {error&&<p className="message error" role="alert">{error}</p>}{notice&&<p className="message" role="status">{notice}</p>}
  <form className="detail" onSubmit={upload}><h3>Upload a document</h3>
   <label>Title<input name="title" required maxLength={160} placeholder="e.g. Lease agreement 2026–2027"/></label>
   <div className="grid"><label>Lease starts (optional)<input name="startsOn" placeholder="YYYY-MM-DD" pattern="\d{4}-\d{2}-\d{2}"/></label><label>Lease ends (optional)<input name="endsOn" placeholder="YYYY-MM-DD" pattern="\d{4}-\d{2}-\d{2}"/></label></div>
   <EnglishFileInput label="PDF file" name="file" accept="application/pdf" disabled={busy} hint="PDF only, up to 5 MB."/>
   <button className="primary" disabled={busy}>Upload document</button>
  </form>
  <h3>Shared documents</h3>
  <DocumentList documents={documents} busy={busy} emptyText="No documents shared with this resident yet."
   onDelete={d=>run(async()=>{if(!await confirmAction({title:'Delete this document?',message:`"${d.title}" will no longer be available to the resident.`,confirmLabel:'Delete document',danger:true}))return;await api(`/lease-documents/${d.id}`,{method:'DELETE'});await load();},'')}/>
 </div>;
}
