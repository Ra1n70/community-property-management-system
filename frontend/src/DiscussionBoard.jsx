import React, {useEffect, useState} from 'react';
import {api} from './api';
import {messagesLink} from './DirectMessages';
import './discussion-board.css';

const categories={LIFE:'Community life',FEEDBACK:'Feedback',TRADING:'Trading',ACTIVITY:'Activities'};
const reasons={AD:'Advertising / spam',ATTACK:'Harassment / personal attack',FALSE_INFO:'False information',OTHER:'Other'};
const date=value=>new Date(value).toLocaleString('en-US',{month:'short',day:'numeric',year:'numeric',hour:'numeric',minute:'2-digit'});
const edited=item=>item.updatedAt&&item.createdAt&&new Date(item.updatedAt)-new Date(item.createdAt)>1000;
const initial=name=>(name||'?').trim().charAt(0).toUpperCase()||'?';

// chat: show a "Message" link that opens a private chat with this resident author (residents only, never for one's own content).
function Author({item,chat}) {
 const property=item.authorRole==='MANAGER';
 const canChat=chat&&!item.deleted&&!item.owned&&item.authorRole==='RESIDENT'&&item.authorAccountId;
 return <span className="discussion-author"><span className={`discussion-avatar ${property?'property':''}`} aria-hidden="true">{initial(item.authorName)}</span><span><strong>{item.authorName||'Unknown author'}</strong>{property&&<span className="discussion-tag property">Property</span>}<small>{date(item.createdAt)}{edited(item)&&` · Edited ${date(item.updatedAt)}`}</small></span>{canChat&&<a className="discussion-chat" href={messagesLink({chatWith:item.authorAccountId,name:item.authorName})} target="_blank" rel="noopener noreferrer" title={`Message ${item.authorName} privately`}>✉ Message</a>}</span>;
}
function Tags({item}) {
 return <span className="discussion-tags">{item.pinned&&<span className="discussion-tag pinned">Pinned</span>}{item.deleted&&<span className="discussion-tag deleted">Deleted</span>}{item.category&&<span className={`discussion-tag cat-${item.category}`}>{categories[item.category]}</span>}</span>;
}
function Counted({label,value,max,children}) {
 return <label><span className="discussion-label-row"><span>{label}</span><small aria-live="polite">{value.length}/{max}</small></span>{children}</label>;
}
function PostForm({post,busy,onSave,onCancel}) {
 const [title,setTitle]=useState(post?.title||''),[content,setContent]=useState(post?.content||''),[category,setCategory]=useState(post?.category||'LIFE');
 return <form className="discussion-editor" onSubmit={e=>{e.preventDefault();onSave({title:title.trim(),content:content.trim(),category});}}>
  <h3>{post?'Edit post':'New post'}</h3>
  <div className="discussion-editor-row"><Counted label="Title" value={title} max={25}><input value={title} onChange={e=>setTitle(e.target.value)} maxLength={25} required disabled={busy} placeholder="A short, clear title"/></Counted>
  <label>Category<select value={category} onChange={e=>setCategory(e.target.value)} disabled={busy}>{Object.entries(categories).map(([key,label])=><option key={key} value={key}>{label}</option>)}</select></label></div>
  <Counted label="Content" value={content} max={1000}><textarea value={content} onChange={e=>setContent(e.target.value)} maxLength={1000} rows={5} required disabled={busy} placeholder="What would you like to share with your neighbors?"/></Counted>
  <div className="actions"><button className="primary" disabled={busy||!title.trim()||!content.trim()}>{busy?'Saving...':post?'Save changes':'Publish post'}</button><button type="button" disabled={busy} onClick={onCancel}>Cancel</button></div>
 </form>;
}
function CommentForm({comment,busy,onSave,onCancel}) {
 const [content,setContent]=useState(comment?.content||'');
 return <form className="discussion-editor compact" onSubmit={async e=>{e.preventDefault();if(await onSave({content:content.trim()}))setContent('');}}>
  <Counted label={comment?'Edit comment':'Add a comment'} value={content} max={1000}><textarea value={content} onChange={e=>setContent(e.target.value)} maxLength={1000} rows={3} required disabled={busy} placeholder="Write a comment..."/></Counted>
  <div className="actions"><button className="primary" disabled={busy||!content.trim()}>{comment?'Save comment':'Post comment'}</button>{onCancel&&<button type="button" disabled={busy} onClick={onCancel}>Cancel</button>}</div>
 </form>;
}
function ActionForm({action,busy,onConfirm,onCancel}) {
 const [reason,setReason]=useState(action.kind==='report'?'OTHER':'');
 const report=action.kind==='report',needsReason=action.kind==='moderate'||action.kind==='resolveDelete';
 return <section className="card discussion-confirm" aria-label="Confirm action"><form onSubmit={e=>{e.preventDefault();onConfirm(reason.trim());}}>
  <h3>{report?'Report content':action.kind==='ignore'?'Dismiss report':'Delete content'}</h3>
  <blockquote className="discussion-text">{action.label}</blockquote>
  {report?<><label>Report reason<select value={reason} onChange={e=>setReason(e.target.value)} disabled={busy}>{Object.entries(reasons).map(([key,label])=><option key={key} value={key}>{label}</option>)}</select></label><p className="hint">The content stays visible while property management reviews the report.</p></>:needsReason?<label>Deletion reason<textarea required maxLength={500} value={reason} onChange={e=>setReason(e.target.value)} disabled={busy} placeholder="Explain which community rule this content breaks."/></label>:<p>{action.kind==='ignore'?'The report will be marked as processed; the content will remain.':'This content will be removed from the community board.'}</p>}
  <div className="actions"><button className="primary" disabled={busy||(needsReason&&!reason.trim())}>{report?'Submit report':action.kind==='ignore'?'Dismiss report':'Confirm deletion'}</button><button type="button" disabled={busy} onClick={onCancel}>Cancel</button></div>
 </form></section>;
}

export default function DiscussionBoard({user}) {
 const manager=user.role==='MANAGER';
 const [view,setView]=useState('posts'),[category,setCategory]=useState(''),[sort,setSort]=useState('new'),[status,setStatus]=useState('PENDING');
 const [rows,setRows]=useState([]),[selectedId,setSelectedId]=useState(()=>Number(new URLSearchParams(location.search).get('post'))||null),[detail,setDetail]=useState(null);
 const [loading,setLoading]=useState(true),[detailLoading,setDetailLoading]=useState(false),[listError,setListError]=useState(''),[detailError,setDetailError]=useState('');
 const [busy,setBusy]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState(''),[revision,setRevision]=useState(0);
 const [editor,setEditor]=useState(null),[editingComment,setEditingComment]=useState(null),[action,setAction]=useState(null),[reported,setReported]=useState(()=>new Set());
 const base=manager?'/manager/discussions':'/discussions';
 useEffect(()=>{
  let active=true;setLoading(true);setListError('');setRows([]);
  const path=view==='reports'?`/manager/reports?status=${status}`:manager?base:`${base}?${new URLSearchParams({category,sort})}`;
  api(path).then(data=>{if(active)setRows(data);}).catch(e=>{if(active)setListError(e.message);}).finally(()=>{if(active)setLoading(false);});
  return()=>{active=false;};
 },[view,category,sort,status,revision,base,manager]);
 useEffect(()=>{
  let active=true;setDetail(null);setDetailError('');
  if(selectedId==null){setDetailLoading(false);return()=>{active=false;};}
  setDetailLoading(true);
  api(`${base}/${selectedId}`).then(data=>{if(active)setDetail(data);}).catch(e=>{if(active)setDetailError(e.message);}).finally(()=>{if(active)setDetailLoading(false);});
  return()=>{active=false;};
 },[selectedId,base,revision]);
 async function mutate(work,message) {
  if(busy)return false;setBusy(true);setError('');setNotice('');
  try {await work();setNotice(message||'');setRevision(n=>n+1);return true;}
  catch(e){setError(e.message);return false;}finally{setBusy(false);}
 }
 function open(id){setSelectedId(id);setEditor(null);setEditingComment(null);setAction(null);setError('');setNotice('');}
 function changeView(next){setView(next);open(null);}
 function deleteAction(item,type){setAction({kind:manager?'moderate':'delete',type,id:item.id,label:item.title||item.content});}
 const reportKey=(type,id)=>`${type}:${id}`;
 const markReported=a=>setReported(old=>new Set(old).add(reportKey(a.type,a.id)));
 async function confirm(reason) {
  const a=action;
  await mutate(async()=>{
   if(a.kind==='report'){
    try{await api('/reports',{method:'POST',data:{targetType:a.type,targetId:a.id,reason}});}
    catch(e){if(e.status===409){markReported(a);setAction(null);}throw e;}
    markReported(a);
   }
   else if(a.kind==='ignore'||a.kind==='resolveDelete')await api(`/manager/reports/${a.id}/handle`,{method:'POST',data:{action:a.kind==='ignore'?'IGNORE':'DELETE',...(a.kind==='resolveDelete'?{deleteReason:{reason}}:{})}});
   else {
    const path=`${a.kind==='moderate'?'/manager':''}/${a.type==='DISCUSSION'?'discussions':'comments'}/${a.id}`;
    await api(path,{method:'DELETE',...(a.kind==='moderate'?{data:{reason}}:{})});
    if(a.type==='DISCUSSION'&&!manager)setSelectedId(null);
   }
   setAction(null);
  },a.kind==='report'?'Report submitted to property management.':a.kind==='ignore'?'Report dismissed.':'Content deleted.');
 }
 function reportButton(type,item,label) {
  const done=reported.has(reportKey(type,item.id));
  return <button className="discussion-quiet" disabled={busy||done} onClick={()=>setAction({kind:'report',type,id:item.id,label:item.title||item.content})}>{done?'Reported':label}</button>;
 }
 // A like only changes the heart and count; no confirmation message.
 function likeButton(item,path) {
  return <button className={`discussion-like ${item.liked?'liked':''}`} disabled={busy} aria-pressed={item.liked} aria-label={`${item.liked?'Unlike':'Like'} · ${item.likeCount} likes`} onClick={()=>mutate(()=>api(path,{method:'POST'}))}><span aria-hidden="true">{item.liked?'♥':'♡'}</span> {item.likeCount}</button>;
 }
 let posts=rows;
 if(manager&&view==='posts')posts=rows.filter(p=>!category||p.category===category).slice().sort((a,b)=>Number(b.pinned)-Number(a.pinned)||(sort==='likes'?b.likeCount-a.likeCount:new Date(b.createdAt)-new Date(a.createdAt))||b.id-a.id);
 return <div className="discussion-board">
  <section className="card discussion-hero"><div className="section-head"><div><span className="eyebrow">{(user.community||'Your community').toUpperCase()}</span><h2>Community posts</h2><p>Share ideas, ask questions, and connect with your neighbors.</p></div><div className="actions"><button disabled={busy} onClick={()=>setRevision(n=>n+1)}>Refresh</button><button className="primary" disabled={busy} onClick={()=>{setView('posts');setEditor('new');setAction(null);}}>New post</button></div></div>
   {manager&&<nav className="person-tabs" aria-label="Discussion management"><button disabled={busy} aria-pressed={view==='posts'} onClick={()=>changeView('posts')}>Posts</button><button disabled={busy} aria-pressed={view==='reports'} onClick={()=>changeView('reports')}>Reports</button></nav>}
   {view==='posts'?<div className="discussion-filters"><div className="discussion-chips" role="group" aria-label="Filter by category">{[['','All'],...Object.entries(categories)].map(([key,label])=><button key={key||'all'} type="button" disabled={busy} aria-pressed={category===key} className={category===key?'active':''} onClick={()=>setCategory(key)}>{label}</button>)}</div><label className="discussion-sort">Sort<select disabled={busy} value={sort} onChange={e=>setSort(e.target.value)}><option value="new">Newest first</option><option value="likes">Most liked</option></select></label></div>:<label className="discussion-sort">Report status<select disabled={busy} value={status} onChange={e=>setStatus(e.target.value)}><option value="PENDING">Pending</option><option value="PROCESSED">Processed</option></select></label>}
  </section>
  {error&&<div className="message error" role="alert">{error}<button className="discussion-dismiss" type="button" aria-label="Dismiss message" onClick={()=>setError('')}>×</button></div>}
  {notice&&<div className="message" role="status">{notice}<button className="discussion-dismiss" type="button" aria-label="Dismiss message" onClick={()=>setNotice('')}>×</button></div>}
  {editor==='new'&&<section className="card"><PostForm busy={busy} onCancel={()=>setEditor(null)} onSave={data=>mutate(async()=>{const p=await api('/discussions',{method:'POST',data});setEditor(null);setSelectedId(p.id);},'Post published.')}/></section>}
  {action&&<ActionForm key={`${action.kind}-${action.id}`} action={action} busy={busy} onConfirm={confirm} onCancel={()=>setAction(null)}/>}
  {listError&&<div className="message error" role="alert">{listError} <button onClick={()=>setRevision(n=>n+1)}>Retry</button></div>}
  <div className={`discussion-workspace ${selectedId!=null&&view==='posts'?'has-selection':''}`}>
  {loading?<p role="status" className="card empty">Loading {view}...</p>:!listError&&view==='posts'?<section aria-label="Discussion posts">{posts.length?<ul className="discussion-posts">{posts.map(p=><li key={p.id} className={`${p.pinned?'pinned':''} ${selectedId===p.id?'selected':''}`}><button className="discussion-post" disabled={busy} aria-label={`Open post: ${p.title}`} aria-current={selectedId===p.id||undefined} onClick={()=>open(p.id)}><Tags item={p}/><h3>{p.title}</h3><span className="discussion-post-meta"><Author item={p}/><span className="discussion-stats"><span title="Likes">{p.liked?'♥':'♡'} {p.likeCount}</span><span title="Comments">💬 {p.commentCount}</span></span></span></button></li>)}</ul>:<p className="card empty">No posts in this category yet. Be the first to start a conversation.</p>}</section>:!listError&&<section className="card" aria-label="Reports">{rows.length?rows.map(r=><article className="discussion-report" key={r.id}><div className="section-head"><h3>{r.targetType==='DISCUSSION'?'Post':'Comment'} report · {reasons[r.reason]}</h3><span className={`discussion-tag ${r.status==='PENDING'?'pinned':''}`}>{r.status==='PENDING'?'Pending':'Processed'}</span></div><small>{date(r.createdAt)}</small><blockquote className="discussion-text">{r.targetContent}</blockquote>{r.status==='PENDING'?<div className="actions">{r.targetAuthorId&&<a className="discussion-contact inline" href={messagesLink({resident:r.targetAuthorId,name:r.targetAuthorName,draft:`Hi ${r.targetAuthorName}, a neighbor reported your ${r.targetType==='DISCUSSION'?'post':'comment'} on the discussion board. `})} target="_blank" rel="noopener noreferrer">✉ Message author ↗</a>}<button disabled={busy} onClick={()=>setAction({kind:'ignore',id:r.id,label:r.targetContent})}>Dismiss report</button><button className="primary" disabled={busy} onClick={()=>setAction({kind:'resolveDelete',id:r.id,label:r.targetContent})}>Delete reported content</button></div>:<p>{r.handledAction==='DELETE'?'Content deleted':'Report dismissed'} by {r.handledByName} · {date(r.handledAt)}</p>}</article>):<p className="empty">No {status.toLowerCase()} reports.</p>}</section>}
  {detailLoading&&<p role="status" className="card empty">Loading post...</p>}{detailError&&<div className="message error" role="alert">{detailError} <button onClick={()=>setRevision(n=>n+1)}>Retry</button></div>}
  {detail&&view==='posts'&&<section className="card discussion-detail" aria-label="Post details"><div className="section-head"><Tags item={detail}/><button className="discussion-quiet" disabled={busy} onClick={()=>open(null)}>Close ✕</button></div>
   <h2>{detail.title}</h2><Author item={detail} chat={!manager}/>
   {detail.deleted&&<div className="message">This post has been deleted.{manager&&detail.deleteReason&&<> Reason: {detail.deleteReason}</>}</div>}
   {editor==='edit'?<PostForm key={detail.id} post={detail} busy={busy} onCancel={()=>setEditor(null)} onSave={data=>mutate(async()=>{await api(`/discussions/${detail.id}`,{method:'PUT',data});setEditor(null);},'Post updated.')}/>:<p className="discussion-text discussion-body">{detail.content}</p>}
   {!detail.deleted&&<div className="discussion-toolbar">{!manager&&likeButton(detail,`/discussions/${detail.id}/like`)}<span className="discussion-count">💬 {detail.commentCount} comments</span><span className="discussion-spacer"/>{manager&&<button disabled={busy} onClick={()=>mutate(()=>api(`/manager/discussions/${detail.id}/pin`,{method:'POST',data:{pinned:!detail.pinned}}),detail.pinned?'Post unpinned.':'Post pinned.')}>{detail.pinned?'Unpin':'Pin post'}</button>}{detail.owned&&<button className="discussion-quiet" disabled={busy} onClick={()=>setEditor('edit')}>Edit</button>}{(detail.owned||manager)&&<button className="discussion-quiet danger" disabled={busy} onClick={()=>deleteAction(detail,'DISCUSSION')}>Delete</button>}{!manager&&!detail.owned&&reportButton('DISCUSSION',detail,'Report')}</div>}
   {!detail.deleted&&(manager?detail.authorAccountId&&<a className="discussion-contact" href={messagesLink({resident:detail.authorAccountId,name:detail.authorName,post:detail.id,title:detail.title})} target="_blank" rel="noopener noreferrer">✉ Message the author privately ↗</a>
    :<a className="discussion-contact" href={messagesLink({post:detail.id,title:detail.title})} target="_blank" rel="noopener noreferrer">✉ Ask property management about this post ↗</a>)}
   <h3 className="discussion-comments-title">Comments · {detail.commentCount}</h3>
   {detail.comments.length===0&&<p className="hint">No comments yet.</p>}
   {detail.comments.map(c=><article className="discussion-comment" key={c.id}><Author item={c} chat={!manager}/>{c.deleted?<p className="discussion-text discussion-removed">This comment has been removed by property management.{manager&&c.deleteReason&&<> Reason: {c.deleteReason}</>}</p>:editingComment===c.id?<CommentForm comment={c} busy={busy} onCancel={()=>setEditingComment(null)} onSave={data=>mutate(async()=>{await api(`/comments/${c.id}`,{method:'PUT',data});setEditingComment(null);},'Comment updated.')}/>:<p className="discussion-text">{c.content}</p>}{!c.deleted&&!detail.deleted&&<div className="discussion-toolbar small">{!manager&&likeButton(c,`/comments/${c.id}/like`)}{c.owned&&<button className="discussion-quiet" disabled={busy} onClick={()=>setEditingComment(c.id)}>Edit</button>}{(c.owned||manager)&&<button className="discussion-quiet danger" disabled={busy} onClick={()=>deleteAction(c,'COMMENT')}>Delete</button>}{!manager&&!c.owned&&reportButton('COMMENT',c,'Report')}</div>}</article>)}
   {!detail.deleted&&<CommentForm key={detail.id} busy={busy} onSave={data=>mutate(()=>api(`/discussions/${detail.id}/comments`,{method:'POST',data}),'Comment posted.')}/>}
  </section>}
 </div></div>;
}
