import React, {useEffect, useRef, useState} from 'react';
import {api,portalUrl,portalRole} from './api';
import {useMessageEvents,useMessageStreamConnected} from './messageEvents';
import './direct-messages.css';

const PAGE_SIZE=50;
// New messages arrive by push; polling is only a fallback (slow while the push connection is up).
const POLL_MS=5000,HIDDEN_POLL_MS=30000,PUSH_FALLBACK_MS=60000;
const MAX_FILES=3,MAX_FILE_BYTES=5*1024*1024;
const fileOk=file=>/^(image\/(jpeg|png)|application\/pdf)$/.test(file.type)||/\.(jpe?g|png|pdf)$/i.test(file.name);
const fileSize=bytes=>bytes<1024*1024?`${Math.max(1,Math.round(bytes/1024))} KB`:`${(bytes/1024/1024).toFixed(1)} MB`;
const attachmentUrl=id=>portalUrl(`/api/direct-messages/attachments/${id}`);
// Text messages go as JSON; messages with files go as multipart (a JSON "request" part plus "files").
function sendBody({content,id,files}) {
 if(!files?.length)return {content,clientRequestId:id};
 const form=new FormData();
 form.append('request',new Blob([JSON.stringify({content,clientRequestId:id})],{type:'application/json'}));
 files.forEach(file=>form.append('files',file));
 return form;
}
function Attachments({items}) {
 if(!items?.length)return null;
 const images=items.filter(a=>a.image),others=items.filter(a=>!a.image);
 return <div className="direct-attachments">
  {images.length>0&&<div className="direct-photos">{images.map(a=><a key={a.id} href={attachmentUrl(a.id)} target="_blank" rel="noopener noreferrer" title={a.filename}><img src={attachmentUrl(a.id)} alt={a.filename} loading="lazy"/></a>)}</div>}
  {others.map(a=><a key={a.id} className="direct-file" href={attachmentUrl(a.id)} target="_blank" rel="noopener noreferrer"><span aria-hidden="true">📄</span><span><strong>{a.filename}</strong><small>PDF · {fileSize(a.size)}</small></span></a>)}
 </div>;
}
const time=value=>new Date(value).toLocaleTimeString('en-US',{hour:'numeric',minute:'2-digit'});
const dayKey=value=>new Date(value).toDateString();
function dayLabel(value) {
 const day=new Date(value),today=new Date(),yesterday=new Date();yesterday.setDate(today.getDate()-1);
 if(day.toDateString()===today.toDateString())return 'Today';
 if(day.toDateString()===yesterday.toDateString())return 'Yesterday';
 return day.toLocaleDateString('en-US',{weekday:'short',month:'short',day:'numeric',year:day.getFullYear()===today.getFullYear()?undefined:'numeric'});
}
function shortWhen(value) {
 const day=new Date(value);
 return day.toDateString()===new Date().toDateString()?time(value):day.toLocaleDateString('en-US',{month:'short',day:'numeric'});
}
const initial=name=>(name||'?').trim().charAt(0).toUpperCase()||'?';
const mergeMessages=(current,incoming)=>Array.from(
 new Map([...current,...incoming].map(message=>[message.messageId,message])).values()
).sort((a,b)=>a.messageId-b.messageId);

// Links to discussion posts (and other same-site pages) become clickable; everything else stays plain text.
function MessageText({text}) {
 // A post link sits on the line right below the text, without an empty line in between.
 const parts=text.replace(/\s+(https?:\/\/\S+\/discussion-board\?post=\d+\S*)/g,'\n$1').split(/(https?:\/\/[^\s]+)/g);
 return <p>{parts.map((part,i)=>{
  if(!/^https?:\/\//.test(part))return part;
  let url;try{url=new URL(part);}catch{return part;}
  const local=url.origin===location.origin,post=local&&url.pathname==='/discussion-board'&&url.searchParams.get('post');
  // Same-site links open in the reader's own portal so a manager stays signed in as manager.
  if(local)url.searchParams.set('portal',portalRole());
  return post?<a key={i} className="direct-post-link" href={url.href} target="_blank" rel="noopener noreferrer"><span aria-hidden="true">💬</span> Discussion post ↗</a>
   :<a key={i} href={url.href} target="_blank" rel="noopener noreferrer">{part}</a>;
 })}</p>;
}
const postUrl=id=>`${location.origin}/discussion-board?post=${id}`;
const POST_LINK=/https?:\/\/\S+\/discussion-board\?post=\d+\S*/g;
// Conversation previews show "Discussion post" instead of the raw link.
const previewText=text=>(text||'').replace(POST_LINK,'💬 Discussion post').replace(/\s+/g,' ').trim();

// Hash parameters let other pages open Messages with a prepared draft or an attached discussion post (post, title).
export function messagesLink(params) {return `${portalUrl('/messages')}#${new URLSearchParams(params)}`;}
function hashParams() {return new URLSearchParams(location.hash.slice(1));}
function clearHash() {history.replaceState(null,'',location.pathname+location.search);}
function hashAttachment(p) {const id=Number(p.get('post'));return id?{id,title:p.get('title')||'Discussion post'}:null;}

function usePageVisible() {
 const [visible,setVisible]=useState(()=>document.visibilityState!=='hidden');
 useEffect(()=>{const sync=()=>setVisible(document.visibilityState!=='hidden');document.addEventListener('visibilitychange',sync);return()=>document.removeEventListener('visibilitychange',sync);},[]);
 return visible;
}
// Fallback refresh: every 60 seconds while push works; otherwise every 5 seconds while visible and 30 in the background.
function usePolling(callback,deps,visible,pushed) {
 useEffect(()=>{
  callback();
  const timer=setInterval(callback,pushed?PUSH_FALLBACK_MS:visible?POLL_MS:HIDDEN_POLL_MS);
  return()=>clearInterval(timer);
 },[...deps,visible,pushed]); // eslint-disable-line react-hooks/exhaustive-deps
}

function Composer({initialDraft='',initialAttachment=null,disabled,onSend,placeholder,allowFiles=true}) {
 const [draft,setDraft]=useState(initialDraft),[attachment,setAttachment]=useState(initialAttachment),[pending,setPending]=useState(null),[sending,setSending]=useState(false),[error,setError]=useState('');
 const [files,setFiles]=useState([]);
 const box=useRef(null),picker=useRef(null);
 useEffect(()=>{if((initialDraft||initialAttachment)&&box.current){box.current.focus();box.current.setSelectionRange(initialDraft.length,initialDraft.length);}},[initialDraft,initialAttachment]);
 useEffect(()=>{setAttachment(initialAttachment);},[initialAttachment]);
 // An attached post is sent as a link on its own line after the text; the reader sees it as a "Discussion post" chip.
 const suffix=attachment?`\n\n${postUrl(attachment.id)}`:'',limit=1000-suffix.length;
 const content=draft.trim()?draft.trim()+suffix:files.length?suffix.trim():draft.trim()+suffix;
 const ready=(draft.trim()||files.length>0)&&draft.length<=limit;
 function change(value){setDraft(value);setError('');}
 function addFiles(list) {
  const picked=[...list];
  if(picker.current)picker.current.value='';
  if(picked.some(file=>!fileOk(file))){setError('Attach JPG or PNG photos, or PDF files.');return;}
  if(picked.some(file=>file.size>MAX_FILE_BYTES)){setError('Each file must be 5 MB or smaller.');return;}
  const next=[...files,...picked];
  if(next.length>MAX_FILES){setError('Attach at most 3 files.');return;}
  setFiles(next);setError('');setPending(null);
 }
 async function submit(event) {
  event?.preventDefault();
  if(sending||!ready)return;
  const attempt=pending?.content===content&&pending.files===files?pending:{content,files,id:crypto.randomUUID()};
  setPending(attempt);setSending(true);setError('');
  try {const ok=await onSend(attempt);if(ok!==false){setDraft('');setAttachment(null);setFiles([]);setPending(null);}}
  catch(e){setError(e?.status===400&&e.message?e.message:'Message failed to send. Try again.');}
  finally {setSending(false);}
 }
 return <form className="direct-compose" onSubmit={submit}>
  {attachment&&<div className="direct-attachment"><span aria-hidden="true">💬</span><span><small>Discussion post</small><strong>{attachment.title}</strong></span>
   <button type="button" className="direct-attachment-remove" disabled={sending} aria-label="Remove attached post" onClick={()=>setAttachment(null)}>×</button></div>}
  {files.length>0&&<ul className="direct-picked" aria-label="Files to send">{files.map((file,i)=><li key={`${file.name}-${i}`}>
   <span aria-hidden="true">{/\.pdf$/i.test(file.name)||file.type==='application/pdf'?'📄':'🖼'}</span><span className="direct-picked-name">{file.name}</span><small>{fileSize(file.size)}</small>
   <button type="button" className="direct-attachment-remove" disabled={sending} aria-label={`Remove ${file.name}`} onClick={()=>{setFiles(files.filter((_,j)=>j!==i));setPending(null);}}>×</button></li>)}</ul>}
  <label className="direct-visually-hidden" htmlFor="direct-draft">Message</label>
  <textarea id="direct-draft" ref={box} value={draft} onChange={e=>change(e.target.value)} disabled={sending||disabled} maxLength={limit} rows={3} placeholder={attachment?'Add your question about this post...':placeholder}
   onKeyDown={e=>{if(e.key==='Enter'&&!e.shiftKey&&!e.nativeEvent.isComposing){e.preventDefault();submit();}}}/>
  <div className="direct-compose-actions">
   <div className="direct-compose-tools">
    {allowFiles&&<><input ref={picker} className="direct-visually-hidden" type="file" multiple accept="image/jpeg,image/png,application/pdf,.jpg,.jpeg,.png,.pdf" tabIndex={-1} aria-hidden="true" onChange={e=>addFiles(e.target.files)}/>
    <button type="button" className="direct-attach" disabled={sending||disabled||files.length>=MAX_FILES} onClick={()=>picker.current?.click()}><span aria-hidden="true">📎</span> Attach</button></>}
    <small>Enter to send · Shift+Enter for a new line · {draft.length}/{limit}{allowFiles&&' · Photos or PDF, up to 3 × 5 MB'}</small>
   </div>
   <button className="primary" disabled={sending||disabled||!ready}>{sending?'Sending...':error&&pending?'Retry':'Send'}</button></div>
  {error&&<p className="direct-error" role="alert">{error}</p>}
 </form>;
}

// live: the thread gets pushed events (property-management messages). Neighbor chats have no push, so they keep polling.
function MessageThread({user,messagesPath,readPath,sendPath,title,subtitle,initialDraft,initialAttachment,onChanged,onUnread,emptyText,live=true,allowFiles=true}) {
 const [messages,setMessages]=useState([]),[nextBeforeId,setNextBeforeId]=useState(null),[unreadCount,setUnreadCount]=useState(0);
 const [loading,setLoading]=useState(true),[loadingOlder,setLoadingOlder]=useState(false),[loadError,setLoadError]=useState('');
 const [readError,setReadError]=useState(''),[newBelow,setNewBelow]=useState(false);
 const conversationId=useRef(null);
 const active=useRef(false),firstPageLoaded=useRef(false),lastReadAttempt=useRef(null),latestVisible=useRef(null),messageList=useRef(null),seenLatest=useRef(null);
 const latestId=messages.at(-1)?.messageId;
 latestVisible.current=latestId;
 const nearBottom=()=>{const list=messageList.current;return !list||list.scrollHeight-list.scrollTop-list.clientHeight<80;};
 const scrollDown=()=>{const list=messageList.current;if(list)list.scrollTop=list.scrollHeight;setNewBelow(false);};

 async function refreshLatest() {
  try {
   const page=await api(`${messagesPath}?size=${PAGE_SIZE}`);
   if(!active.current)return;
   conversationId.current=page.conversationId;
   setMessages(old=>mergeMessages(old,page.messages));
   setUnreadCount(page.unreadCount);onUnread?.(page.unreadCount);
   if(!firstPageLoaded.current){setNextBeforeId(page.nextBeforeId);firstPageLoaded.current=true;}
   setLoadError('');
  } catch {if(active.current)setLoadError('Failed to load messages.');}
  finally {if(active.current)setLoading(false);}
 }
 useEffect(()=>{active.current=true;return()=>{active.current=false;};},[]);
 const visible=usePageVisible(),pushed=useMessageStreamConnected();
 usePolling(refreshLatest,[messagesPath],visible,live&&pushed);
 // A pushed "message" event for this conversation (or the resident's first one) loads it right away.
 useMessageEvents(event=>{if(live&&event.type==='message'&&(conversationId.current==null||event.conversationId===conversationId.current))refreshLatest();});

 // Keep the newest message in view unless the person scrolled up to read history.
 useEffect(()=>{
  if(loading||!latestId||seenLatest.current===latestId)return;
  const first=seenLatest.current==null;seenLatest.current=latestId;
  if(first||nearBottom()||messages.at(-1)?.senderId===user.id)requestAnimationFrame(scrollDown);else setNewBelow(true);
 },[loading,latestId]);

 useEffect(()=>{
  if(loading||!latestId||lastReadAttempt.current===latestId||!visible)return;
  lastReadAttempt.current=latestId;
  api(readPath,{method:'POST',data:{throughMessageId:latestId}})
   .then(()=>{if(!active.current)return;if(latestVisible.current===latestId){setUnreadCount(0);onUnread?.(0);}setReadError('');onChanged?.();})
   .catch(()=>{if(!active.current)return;lastReadAttempt.current=null;setReadError('Could not update read status. It will retry on refresh.');});
 },[messages,loading,latestId,readPath,visible]);

 async function loadOlder() {
  if(nextBeforeId==null||loadingOlder)return;
  setLoadingOlder(true);setLoadError('');
  const list=messageList.current,before=list?list.scrollHeight-list.scrollTop:0;
  try {
   const page=await api(`${messagesPath}?size=${PAGE_SIZE}&beforeId=${nextBeforeId}`);
   if(!active.current)return;
   setMessages(old=>mergeMessages(old,page.messages));
   setNextBeforeId(page.nextBeforeId);
   requestAnimationFrame(()=>{if(list)list.scrollTop=list.scrollHeight-before;});
  } catch {if(active.current)setLoadError('Failed to load older messages.');}
  finally {if(active.current)setLoadingOlder(false);}
 }

 async function send(attempt) {
  const message=await api(sendPath,{method:'POST',data:sendBody(attempt)});
  if(!active.current)return false;
  setMessages(old=>mergeMessages(old,[message]));
  onChanged?.(message);
 }

 let lastDay=null;
 return <section className="card direct-thread" aria-label={title}>
  <div className="direct-thread-head"><span className="direct-avatar large" aria-hidden="true">{initial(title)}</span><div><span className="eyebrow">PRIVATE MESSAGES</span><h2>{title}</h2>{subtitle&&<small>{subtitle}</small>}</div>{unreadCount>0&&<span className="direct-unread">{unreadCount} unread</span>}</div>
  {loadError&&<div className="message error" role="alert">{loadError} <button type="button" onClick={refreshLatest}>Retry</button></div>}
  <div className="direct-scroll">
   {loading?<p role="status" className="direct-status">Loading messages...</p>:<>
    {messages.length?<ol ref={messageList} className="direct-message-list" aria-label="Message history" aria-live="polite" onScroll={()=>{if(nearBottom())setNewBelow(false);}}>
     {nextBeforeId!=null&&<li className="direct-older"><button type="button" disabled={loadingOlder} onClick={loadOlder}>{loadingOlder?'Loading...':'Load older messages'}</button></li>}
     {messages.map(message=>{
      const own=message.senderId===user.id,property=message.senderRole==='MANAGER';
      const showDay=dayKey(message.createdAt)!==lastDay;lastDay=dayKey(message.createdAt);
      return <React.Fragment key={message.messageId}>
       {showDay&&<li className="direct-day" aria-hidden="true"><span>{dayLabel(message.createdAt)}</span></li>}
       <li className={own?'direct-own':'direct-other'}>
        {!own&&<span className={`direct-avatar ${property?'property':''}`} aria-hidden="true">{initial(message.senderName)}</span>}
        <div className="direct-bubble"><small>{own?'You':property?`${message.senderName} · Property Management`:message.senderName} · {time(message.createdAt)}</small>{message.content&&<MessageText text={message.content}/>}<Attachments items={message.attachments}/></div>
       </li>
      </React.Fragment>;
     })}
    </ol>:<p className="empty direct-status">{emptyText}</p>}
   </>}
   {newBelow&&<button type="button" className="direct-new-below" onClick={scrollDown}>New messages ↓</button>}
  </div>
  {readError&&<p className="direct-error" role="alert">{readError}</p>}
  <Composer initialDraft={initialDraft} initialAttachment={initialAttachment} onSend={send} placeholder="Type a message..." allowFiles={allowFiles}/>
 </section>;
}

function useTitleCount(count) {
 useEffect(()=>{const base='Messages · Community Property Management System';document.title=count>0?`(${count}) ${base}`:base;},[count]);
}

export function ResidentDirectMessages({user,onUnread,ownTitle=true}) {
 const [initial]=useState(()=>{const p=hashParams();if(p.toString())clearHash();return {draft:p.get('draft')||'',attachment:hashAttachment(p)};});
 const [unread,setUnread]=useState(0);
 useTitleCount(ownTitle?unread:0);
 useEffect(()=>{onUnread?.(unread);},[unread]); // eslint-disable-line react-hooks/exhaustive-deps
 return <>
  {(initial.draft||initial.attachment)&&<p className="message direct-banner" role="status">The discussion post is attached below. Add your question and press Send.</p>}
  <MessageThread user={user} title="Property Management" subtitle="Questions for the property office. For emergencies, call the emergency number." messagesPath="/direct-messages/me/messages"
   readPath="/direct-messages/me/read" sendPath="/direct-messages/me/messages" initialDraft={initial.draft} initialAttachment={initial.attachment} onUnread={setUnread}
   emptyText="No messages yet. Send a message to property management to start the conversation."/>
 </>;
}

export function ManagerDirectMessages({user}) {
 const [target]=useState(()=>{const p=hashParams();const id=Number(p.get('resident'));if(p.toString())clearHash();return id?{residentId:id,name:p.get('name')||'Resident',draft:p.get('draft')||'',attachment:hashAttachment(p)}:null;});
 const [conversations,setConversations]=useState([]),[selectedId,setSelectedId]=useState(null),[newConversation,setNewConversation]=useState(null);
 const [loading,setLoading]=useState(true),[error,setError]=useState(''),[query,setQuery]=useState(''),[unreadOnly,setUnreadOnly]=useState(false);
 const active=useRef(false),targetHandled=useRef(false),[draftUsed,setDraftUsed]=useState(false);
 const [limit,setLimit]=useState(100),[totalUnread,setTotalUnread]=useState(0);
 const filters=useRef({query:'',unreadOnly:false,limit:100});
 filters.current={query,unreadOnly,limit};

 async function refreshConversations() {
  try {
   // Search and the unread filter run on the server; the total unread count covers every conversation.
   const params=new URLSearchParams({q:filters.current.query.trim(),unreadOnly:filters.current.unreadOnly,limit:filters.current.limit});
   const [rows,unread]=await Promise.all([api(`/direct-messages/conversations?${params}`),api('/direct-messages/unread')]);
   if(!active.current)return;
   setConversations(rows);setTotalUnread(unread.unread);setError('');
   if(target&&!targetHandled.current){
    targetHandled.current=true;
    const existing=rows.find(row=>row.residentId===target.residentId);
    if(existing){setSelectedId(existing.conversationId);setNewConversation(null);}else setNewConversation(target);
   }
  } catch {if(active.current)setError('Failed to load conversations.');}
  finally {if(active.current)setLoading(false);}
 }
 useEffect(()=>{active.current=true;return()=>{active.current=false;};},[]);
 const visible=usePageVisible(),pushed=useMessageStreamConnected();
 usePolling(refreshConversations,[],visible,pushed);
 // Any pushed message or read in the community changes the inbox; bursts are combined into one refresh.
 const pushTimer=useRef(0);
 useMessageEvents(()=>{clearTimeout(pushTimer.current);pushTimer.current=setTimeout(refreshConversations,150);});
 useEffect(()=>()=>clearTimeout(pushTimer.current),[]);
 // Typing in the search box waits 250 ms; the filter and "Show more" apply at once.
 const firstFilter=useRef(true);
 useEffect(()=>{if(firstFilter.current){firstFilter.current=false;return;}const timer=setTimeout(refreshConversations,250);return()=>clearTimeout(timer);},[query,unreadOnly,limit]); // eslint-disable-line react-hooks/exhaustive-deps


 useTitleCount(totalUnread);
 const q=query.trim().toLowerCase();
 const shown=conversations;
 const selected=conversations.find(row=>row.conversationId===selectedId);
 const forTarget=!draftUsed&&selected&&target&&selected.residentId===target.residentId;
 const draftFor=forTarget?target.draft:'',attachmentFor=forTarget?target.attachment:null;

 async function startConversation(attempt) {
  const message=await api(`/direct-messages/residents/${newConversation.residentId}/messages`,{method:'POST',data:sendBody(attempt)});
  setDraftUsed(true);
  await refreshConversations();
  setNewConversation(null);setSelectedId(message.conversationId);
 }

 return <div className="direct-layout">
  <section className="card direct-inbox" aria-label="Conversations">
   <div className="section-head"><div><span className="eyebrow">SHARED INBOX</span><h2>Conversations</h2></div>{totalUnread>0&&<span className="direct-unread">{totalUnread} unread</span>}</div>
   <div className="direct-filters"><input type="search" aria-label="Search conversations" placeholder="Search name, room or message" value={query} onChange={e=>setQuery(e.target.value)}/>
    <label className="direct-toggle"><input type="checkbox" checked={unreadOnly} onChange={e=>setUnreadOnly(e.target.checked)}/>Unread only</label></div>
   {error&&<div className="message error" role="alert">{error} <button type="button" onClick={refreshConversations}>Retry</button></div>}
   {loading?<p role="status">Loading conversations...</p>:shown.length?<ul className="direct-conversations">{shown.map(row=><li key={row.conversationId}>
    <button type="button" className={`${selectedId===row.conversationId?'direct-selected':''} ${row.unreadCount>0?'direct-has-unread':''}`} aria-pressed={selectedId===row.conversationId} onClick={()=>{setSelectedId(row.conversationId);setNewConversation(null);}}>
     <span className="direct-avatar" aria-hidden="true">{initial(row.residentName)}</span>
     <span className="direct-conversation-body">
      <span className="direct-conversation-head"><strong>{row.residentName}</strong><small>{shortWhen(row.lastMessageAt)}</small></span>
      {row.residentRoom&&<small>Room {row.residentRoom}</small>}
      <span className="direct-preview-row"><span className="direct-preview">{previewText(row.lastMessageContent)}</span>{row.unreadCount>0&&<span className="direct-unread">{row.unreadCount}</span>}</span>
     </span>
    </button>
   </li>)}</ul>:<p className="empty">{q||unreadOnly?'No conversations match your search.':'No resident conversations yet.'}</p>}
   {conversations.length===limit&&<button type="button" className="direct-more" onClick={()=>setLimit(n=>n+100)}>Show more conversations</button>}
  </section>
  {newConversation?<section className="card direct-thread" aria-label={`New conversation with ${newConversation.name}`}>
    <div className="direct-thread-head"><span className="direct-avatar large" aria-hidden="true">{initial(newConversation.name)}</span><div><span className="eyebrow">NEW CONVERSATION</span><h2>{newConversation.name}</h2><small>This resident has not messaged property management yet. Your message starts the conversation.</small></div></div>
    <div className="direct-scroll"><p className="empty direct-status">No messages yet.</p></div>
    <Composer initialDraft={newConversation.draft} initialAttachment={newConversation.attachment} onSend={startConversation} placeholder={`Message ${newConversation.name}...`}/>
   </section>
   :selected?<MessageThread key={selectedId} user={user} title={selected.residentName} subtitle={selected.residentRoom?`Resident · Room ${selected.residentRoom}`:'Resident'}
   messagesPath={`/direct-messages/conversations/${selectedId}/messages`}
   readPath={`/direct-messages/conversations/${selectedId}/read`}
   sendPath={`/direct-messages/conversations/${selectedId}/messages`}
   initialDraft={draftFor} initialAttachment={attachmentFor}
   onChanged={refreshConversations}
   emptyText="No messages in this conversation yet."/>:<section className="card direct-placeholder"><p>Select a conversation to read and reply.</p></section>}
 </div>;
}

/**
 * Private chats between two approved residents of the same community, started from a resident's post or comment
 * on the Discussion Board. start = {residentId, name} opens (or creates) the chat with that resident.
 */
export function NeighborMessages({user,onUnread,start}) {
 const [chats,setChats]=useState([]),[selectedId,setSelectedId]=useState(null),[loading,setLoading]=useState(true),[error,setError]=useState('');
 const visible=usePageVisible(),active=useRef(false);
 async function load(){
  try{const list=await api('/resident-chats/conversations');if(!active.current)return;setChats(list);setError('');onUnread?.(list.reduce((sum,c)=>sum+c.unreadCount,0));setSelectedId(old=>old??list[0]?.conversationId??null);}
  catch(e){if(active.current)setError(e.message||'Failed to load chats.');}
  finally{if(active.current)setLoading(false);}
 }
 useEffect(()=>{active.current=true;return()=>{active.current=false;};},[]);
 useEffect(()=>{
  if(!start)return;
  api('/resident-chats/conversations',{method:'POST',data:{residentId:start.residentId}})
   .then(chat=>{if(!active.current)return;setSelectedId(chat.conversationId);load();})
   .catch(e=>{if(active.current)setError(e.status===404?`${start.name||'This resident'} can no longer receive messages.`:e.message);});
 },[start]); // eslint-disable-line react-hooks/exhaustive-deps
 usePolling(load,[],visible,false);
 const selected=chats.find(c=>c.conversationId===selectedId);
 return <div className="neighbor-chats">
  <aside className="card neighbor-list" aria-label="Neighbor chats">
   <span className="eyebrow">NEIGHBORS</span><h2>Chats</h2>
   <p className="hint">Start a chat from a neighbor's post or comment on the Discussion Board (✉ Message).</p>
   {error&&<p className="message error" role="alert">{error}</p>}
   {loading?<p role="status">Loading chats...</p>:!chats.length?<p className="empty">No chats yet.</p>:
    <ul>{chats.map(c=><li key={c.conversationId}>
     <button type="button" className={c.conversationId===selectedId?'active':''} aria-pressed={c.conversationId===selectedId} onClick={()=>setSelectedId(c.conversationId)}>
      <span className="direct-avatar" aria-hidden="true">{initial(c.otherResidentName)}</span>
      <span className="neighbor-list-text"><strong>{c.otherResidentName}</strong><small>{c.lastMessageContent?previewText(c.lastMessageContent):'No messages yet'}</small></span>
      <span className="neighbor-list-meta">{c.lastMessageAt&&<small>{shortWhen(c.lastMessageAt)}</small>}{c.unreadCount>0&&<span className="direct-unread">{c.unreadCount}</span>}</span>
     </button></li>)}</ul>}
  </aside>
  {selected?<MessageThread key={selected.conversationId} user={user} title={selected.otherResidentName} subtitle="Private chat between neighbors. Property management cannot see it."
    messagesPath={`/resident-chats/conversations/${selected.conversationId}/messages`} readPath={`/resident-chats/conversations/${selected.conversationId}/read`}
    sendPath={`/resident-chats/conversations/${selected.conversationId}/messages`} live={false} allowFiles={false} onChanged={load}
    emptyText={`Say hello to ${selected.otherResidentName}.`}/>
   :<section className="card direct-thread"><p className="empty">{loading?'':'Choose a chat, or start one from the Discussion Board.'}</p></section>}
 </div>;
}

/** The resident's Messages page: the property-management conversation and chats with neighbors, in two tabs. */
export function ResidentMessages({user,aside}) {
 const [start]=useState(()=>{const p=hashParams();const id=Number(p.get('chatWith'));if(!id)return null;clearHash();return {residentId:id,name:p.get('name')||''};});
 const [tab,setTab]=useState(start?'neighbors':'property'),[propertyUnread,setPropertyUnread]=useState(0),[neighborUnread,setNeighborUnread]=useState(0);
 useTitleCount(propertyUnread+neighborUnread);
 // Neighbor chats are also counted while the property tab is open, for the tab badge.
 useEffect(()=>{
  let live=true;
  const count=()=>api('/resident-chats/conversations').then(list=>{if(live)setNeighborUnread(list.reduce((sum,c)=>sum+c.unreadCount,0));}).catch(()=>{});
  count();const timer=setInterval(count,30000);
  return()=>{live=false;clearInterval(timer);};
 },[]);
 const badge=n=>n>0&&<span className="direct-unread">{n}</span>;
 return <>
  <nav className="hub-tabs messages-tabs" aria-label="Messages">
   <button type="button" aria-pressed={tab==='property'} onClick={()=>setTab('property')}>Property Management {badge(propertyUnread)}</button>
   <button type="button" aria-pressed={tab==='neighbors'} onClick={()=>setTab('neighbors')}>Neighbors {badge(neighborUnread)}</button>
  </nav>
  <div hidden={tab!=='property'} className="messages-with-support"><ResidentDirectMessages user={user} onUnread={setPropertyUnread} ownTitle={false}/>{aside}</div>
  {tab==='neighbors'&&<NeighborMessages user={user} start={start} onUnread={setNeighborUnread}/>}
 </>;
}
