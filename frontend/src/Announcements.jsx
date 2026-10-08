import React, {useEffect,useMemo,useState} from 'react';
import {api} from './api';
import './announcements.css';
const formatDate=value=>new Date(value).toLocaleString('en-US');
export default function Announcements({user}) {
 const manager=user.role==='MANAGER';
 // ?id= (e.g. from Search) opens that announcement.
 const [items,setItems]=useState([]),[selectedId,setSelectedId]=useState(()=>Number(new URLSearchParams(location.search).get('id'))||null),[search,setSearch]=useState('');
 const [loading,setLoading]=useState(true),[error,setError]=useState(''),[notice,setNotice]=useState(''),[revision,setRevision]=useState(0);
 const [editor,setEditor]=useState(null),[deleting,setDeleting]=useState(null),[busy,setBusy]=useState(false);
 const filtered=useMemo(()=>{const query=search.trim().toLowerCase();return items.filter(a=>`${a.title} ${a.author} ${a.content}`.toLowerCase().includes(query));},[items,search]);
 const selected=filtered.find(a=>a.id===selectedId)||filtered[0];
 useEffect(()=>{let active=true;setLoading(true);setError('');
  api('/announcements').then(rows=>{if(active)setItems(rows);}).catch(e=>{if(active){setItems([]);setError(e.message);}}).finally(()=>{if(active)setLoading(false);});
  return()=>{active=false;};
 },[revision]);
 async function save(data) {
  setBusy(true);setError('');setNotice('');
  try {
   const edit=editor.id!=null;
   const row=await api(edit?`/announcements/${editor.id}`:'/announcements',{method:edit?'PUT':'POST',data:{...data,...(edit?{version:editor.version}:{})}});
   setItems(old=>[row,...old.filter(a=>a.id!==row.id)].sort((a,b)=>new Date(b.publishedAt)-new Date(a.publishedAt)||b.id-a.id));
   setSearch('');setSelectedId(row.id);setEditor(null);setNotice(edit?'Announcement updated.':'Announcement published.');return true;
  }catch(e){setError(e.message);return false;}finally{setBusy(false);}
 }
 async function remove() {
  setBusy(true);setError('');setNotice('');
  try{await api(`/announcements/${deleting.id}?version=${deleting.version}`,{method:'DELETE'});setItems(old=>old.filter(a=>a.id!==deleting.id));setDeleting(null);setNotice('Announcement deleted.');}
  catch(e){setError(e.message);}finally{setBusy(false);}
 }
 return <div className="announcements">
  <div className="announcement-toolbar"><p>Notices published by your property management team.</p><div className="actions"><button disabled={busy||loading} onClick={()=>setRevision(n=>n+1)}>Refresh</button>{manager&&<button className="primary" disabled={busy} onClick={()=>{setEditor({});setDeleting(null);setError('');}}>New announcement</button>}</div></div>
  {error&&<div className="message error" role="alert">{error} <button disabled={busy} onClick={()=>setRevision(n=>n+1)}>Reload announcements</button></div>}{notice&&<div className="message" role="status">{notice}</div>}
  {editor&&<section className="card"><AnnouncementForm key={editor.id||'new'} item={editor} user={user} busy={busy} onSave={save} onCancel={()=>setEditor(null)}/></section>}
  {deleting&&<section className="card announcement-confirm" aria-label="Confirm deletion"><h2>Delete announcement?</h2><p>{deleting.title}</p><p>This permanently removes this announcement from your community.</p><div className="actions"><button disabled={busy} onClick={remove}>Confirm deletion</button><button disabled={busy} onClick={()=>setDeleting(null)}>Cancel</button></div></section>}
  <section className="announcement-workspace">
   <aside className="notice-list-panel"><span className="eyebrow">NOTICE BOARD</span><h2>All announcements <span className="count">{items.length}</span></h2><label>Search announcements<input type="search" value={search} onChange={e=>setSearch(e.target.value)} placeholder="Title, author, or content"/></label>
    <div className="notice-list">{loading?<p role="status">Loading announcements...</p>:filtered.length?filtered.map(item=><button key={item.id} className={`notice-item ${selected?.id===item.id?'active':''}`} aria-pressed={selected?.id===item.id} disabled={busy} onClick={()=>setSelectedId(item.id)}><strong>{item.title}</strong><small>{item.content}</small><time>{formatDate(item.publishedAt)}</time></button>):<p className="empty">{error?'Announcements could not be loaded.':'No announcements found.'}</p>}</div>
   </aside>
   <article className="notice-detail">{!loading&&selected?<><span className="eyebrow">ANNOUNCEMENT DETAILS</span><h2>{selected.title}</h2><div className="author-line"><span className="author-avatar">{selected.author.slice(0,1).toUpperCase()}</span><div><strong>{selected.author}</strong><time>{formatDate(selected.publishedAt)}</time></div></div><p className="notice-content">{selected.content}</p>{manager&&<div className="actions"><button disabled={busy} onClick={()=>{setEditor(selected);setDeleting(null);setError('');}}>Edit announcement</button><button disabled={busy} onClick={()=>{setDeleting(selected);setEditor(null);setError('');}}>Delete announcement</button></div>}</>:<div className="empty"><h2>{loading?'Loading...':search?'No matching announcement':'Community updates'}</h2><p>{manager?'Publish a notice to keep your community informed.':'Announcements from property management will appear here.'}</p></div>}</article>
  </section>
 </div>;
}
function AnnouncementForm({item,user,busy,onSave,onCancel}) {
 const [title,setTitle]=useState(item.title||''),[content,setContent]=useState(item.content||'');
 const [formError,setFormError]=useState('');
 async function submit(e){e.preventDefault();setFormError('');try{await onSave({title:title.trim(),content:content.trim(),publishedAt:item.publishedAt||new Date().toISOString()});}catch(err){setFormError(err.message);}}
 return <form className="announcement-editor" onSubmit={submit} noValidate><h2>{item.id?'Edit announcement':'Publish a community update'}</h2><p>Author: {item.author||user.name}</p>
  <label>Title<input disabled={busy} maxLength={160} value={title} onChange={e=>setTitle(e.target.value)}/></label>
  <label>Announcement content<textarea disabled={busy} rows={8} maxLength={20000} value={content} onChange={e=>setContent(e.target.value)}/></label><small>{content.length}/20000</small>
  {formError&&<p className="message error" role="alert">{formError}</p>}<div className="actions"><button className="primary" disabled={busy||!title.trim()||!content.trim()}>{busy?'Saving...':item.id?'Save changes':'Publish announcement'}</button><button type="button" disabled={busy} onClick={onCancel}>Cancel</button></div>
 </form>;
}
