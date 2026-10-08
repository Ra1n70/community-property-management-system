import React, {useEffect,useState} from 'react';
import {api} from './api';
import {confirmAction} from './englishUi';
import {EnglishDateTimeField} from './EnglishInputs';

const when=value=>new Date(value).toLocaleString('en-US',{dateStyle:'medium',timeStyle:'short'});
const pad=n=>String(n).padStart(2,'0');
// Default closing time: a week from today at 6 PM, in the browser's time zone.
function nextWeek(){const d=new Date();d.setDate(d.getDate()+7);return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}T18:00`;}

/** Community voting: managers start and close votes; each resident votes once and then sees the results. */
export default function Polls({user}) {
 const manager=user.role==='MANAGER';
 const [polls,setPolls]=useState([]),[loading,setLoading]=useState(true),[busy,setBusy]=useState(false);
 const [error,setError]=useState(''),[notice,setNotice]=useState(''),[creating,setCreating]=useState(false),[filter,setFilter]=useState('open');
 async function load(){setPolls(await api('/polls'));}
 async function run(work,message){setBusy(true);setError('');setNotice('');try{await work();if(message)setNotice(message);}catch(e){setError(e.message);}finally{setBusy(false);}}
 useEffect(()=>{load().catch(e=>setError(e.message)).finally(()=>setLoading(false));},[]);
 const replace=poll=>setPolls(old=>old.map(p=>p.id===poll.id?poll:p));
 const shown=polls.filter(p=>filter==='all'||(filter==='open'?p.open:!p.open));
 return <div className="polls">
  <div className="hub-toolbar"><p>{manager?'Ask residents for their opinion. Each approved resident can vote once.':'Have your say on community decisions. You can vote once in each vote.'}</p>
   <div className="actions"><button disabled={busy} onClick={()=>run(load)}>Refresh</button>{manager&&<button className="primary" disabled={busy} onClick={()=>setCreating(!creating)}>{creating?'Close form':'New vote'}</button>}</div></div>
  {error&&<p className="message error" role="alert">{error}</p>}{notice&&<p className="message" role="status">{notice}</p>}
  {creating&&<PollForm busy={busy} onCancel={()=>setCreating(false)} onSave={data=>run(async()=>{const poll=await api('/polls',{method:'POST',data});setPolls(old=>[poll,...old]);setCreating(false);setFilter('open');},'Vote published. Residents can vote now.')}/>}
  <div className="hub-filters" role="group" aria-label="Show votes">{[['open','Open'],['closed','Closed'],['all','All']].map(([key,label])=><button key={key} type="button" aria-pressed={filter===key} onClick={()=>setFilter(key)}>{label} <span>{polls.filter(p=>key==='all'||(key==='open'?p.open:!p.open)).length}</span></button>)}</div>
  {loading?<p role="status">Loading votes…</p>:!shown.length?<p className="empty">{filter==='open'?'No open votes right now.':'No votes in this view.'}</p>
   :<div className="poll-list">{shown.map(p=><PollCard key={p.id} poll={p} manager={manager} busy={busy}
     onVote={optionId=>run(async()=>replace(await api(`/polls/${p.id}/vote`,{method:'POST',data:{optionId}})),'Thanks — your vote was recorded.')}
     onClose={()=>run(async()=>{if(!await confirmAction({title:'Close this vote now?',message:'Residents will no longer be able to vote, and everyone will see the results.',confirmLabel:'Close vote'}))return;replace(await api(`/polls/${p.id}/close`,{method:'POST'}));},'')}
     onDelete={()=>run(async()=>{if(!await confirmAction({title:'Delete this vote?',message:'The vote and all of its ballots will be removed permanently.',confirmLabel:'Delete vote',danger:true}))return;await api(`/polls/${p.id}`,{method:'DELETE'});setPolls(old=>old.filter(x=>x.id!==p.id));},'')}/>)}</div>}
 </div>;
}

function PollCard({poll,manager,busy,onVote,onClose,onDelete}) {
 const [choice,setChoice]=useState(null);
 const canVote=!manager&&poll.open&&poll.myOptionId==null;
 const max=Math.max(1,...poll.options.map(o=>o.votes||0));
 return <article className={`card poll-card ${poll.open?'':'closed'}`}>
  <div className="section-head"><div><span className={`badge ${poll.open?'APPROVED':''}`}>{poll.open?'Open':'Closed'}</span><h3>{poll.title}</h3></div>
   {manager&&<div className="actions">{poll.open&&<button disabled={busy} onClick={onClose}>Close now</button>}<button disabled={busy} onClick={onDelete}>Delete</button></div>}</div>
  {poll.description&&<p className="poll-description">{poll.description}</p>}
  <small>{poll.open?`Closes ${when(poll.closesAt)}`:`Closed ${when(poll.closedAt||poll.closesAt)}`} · Started by {poll.createdByName}</small>
  {canVote?<form className="poll-options" onSubmit={e=>{e.preventDefault();if(choice)onVote(choice);}}>
    {poll.options.map(o=><label key={o.id} className="check-label"><input type="radio" name={`poll-${poll.id}`} value={o.id} checked={choice===o.id} onChange={()=>setChoice(o.id)}/> {o.label}</label>)}
    <button className="primary" disabled={busy||!choice}>Submit vote</button><small>Votes cannot be changed. Results appear after you vote.</small></form>
   :poll.resultsVisible?<ul className="poll-results">{poll.options.map(o=>{const share=poll.totalVotes?Math.round(o.votes*100/poll.totalVotes):0;return <li key={o.id} className={poll.myOptionId===o.id?'mine':''}>
     <div><span>{o.label}{poll.myOptionId===o.id&&<em> · your vote</em>}</span><strong>{share}% <small>({o.votes})</small></strong></div>
     <span className="poll-bar" aria-hidden="true"><i style={{width:`${o.votes*100/max}%`}}/></span></li>;})}
     <li className="poll-turnout">{poll.totalVotes} of {poll.eligibleVoters} residents voted</li></ul>
   :<p className="hint">Results will appear when the vote closes.</p>}
 </article>;
}

function PollForm({busy,onSave,onCancel}) {
 const [options,setOptions]=useState(['','']),[closesAt,setClosesAt]=useState(nextWeek);
 const setOption=(i,value)=>setOptions(old=>old.map((o,j)=>j===i?value:o));
 function submit(e){e.preventDefault();const data=Object.fromEntries(new FormData(e.currentTarget));onSave({title:data.title,description:data.description,options:options.map(o=>o.trim()).filter(Boolean),closesAt:new Date(closesAt).toISOString()});}
 return <form className="card hub-form" onSubmit={submit}><h3>Start a vote</h3>
  <label>Question<input name="title" required maxLength={160} placeholder="e.g. Which day should the pool open earlier?"/></label>
  <label>Details (optional)<textarea name="description" maxLength={2000}/></label>
  <fieldset className="poll-option-editor"><legend>Options (2 to 10)</legend>
   {options.map((o,i)=><div key={i} className="poll-option-row"><input aria-label={`Option ${i+1}`} value={o} maxLength={120} required={i<2} onChange={e=>setOption(i,e.target.value)} placeholder={`Option ${i+1}`}/>{options.length>2&&<button type="button" onClick={()=>setOptions(old=>old.filter((_,j)=>j!==i))}>Remove</button>}</div>)}
   {options.length<10&&<button type="button" onClick={()=>setOptions(old=>[...old,''])}>Add option</button>}
  </fieldset>
  <EnglishDateTimeField label="Voting closes" value={closesAt} onChange={setClosesAt}/>
  <div className="actions"><button className="primary" disabled={busy}>Publish vote</button><button type="button" disabled={busy} onClick={onCancel}>Cancel</button></div>
 </form>;
}
