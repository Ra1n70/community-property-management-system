import CommunityHub from './CommunityHub';
import MyAccount from './MyAccount';
import {SupportPanel} from './Support';
import React, {useEffect,useState} from 'react';
import {useMessageEvents} from './messageEvents';
import {api,portalUrl,portalLogin} from './api';
import DiscussionBoard from './DiscussionBoard';
import IdleSignOut from './IdleSignOut';
import {ResidentMessages,ManagerDirectMessages} from './DirectMessages';
import './community-workspace.css';

// Unread messages for the home page: loaded once, then refreshed by pushed events, on focus and every minute.
// Residents also count unread chats with neighbors.
function useUnreadMessages(resident) {
 const [count,setCount]=useState(0);
 const refresh=()=>Promise.all([api('/direct-messages/unread').then(r=>r.unread),resident?api('/resident-chats/conversations').then(list=>list.reduce((sum,c)=>sum+c.unreadCount,0)):0])
  .then(([own,neighbors])=>setCount(own+neighbors)).catch(()=>{});
 useMessageEvents(refresh);
 useEffect(()=>{refresh();const timer=setInterval(refresh,60000);window.addEventListener('focus',refresh);return()=>{clearInterval(timer);window.removeEventListener('focus',refresh);};},[]); // eslint-disable-line react-hooks/exhaustive-deps
 return count;
}
// Each page opened from the home cards: heading text and who may use it.
const pages={
 hub:{eyebrow:'COMMUNITY HUB',title:'Community Hub',text:'Announcements, community votes and local perks in one place.'},
 messages:{eyebrow:'YOUR PRIVATE INBOX',title:'Messages',text:'Conversations with property management and with neighbors, with office contacts and answers to common questions.'},
 discussions:{eyebrow:'NEIGHBORHOOD CONVERSATIONS',title:'Discussion Board',text:'A shared space for community life, feedback, trading, and activities.'},
 account:{eyebrow:'MY ACCOUNT',title:'My Account',text:'Your lease documents and bills from property management.',residentOnly:true},
};
export function CommunityAppLinks({user}) {
 const resident=user?.role==='RESIDENT';
 const unread=useUnreadMessages(resident);
 return <nav className="community-app-links" aria-label="Community apps">
  <a href={portalUrl('/community-hub')} target="_blank" rel="noopener noreferrer"><span className="eyebrow">COMMUNITY HUB</span><strong>Community Hub <span aria-hidden="true">↗</span></strong><span>Announcements, community votes and local perks.</span><small>Opens in a new tab</small></a>
  <a href={portalUrl('/messages')} target="_blank" rel="noopener noreferrer"><span className="eyebrow">CONTACT & SUPPORT</span><strong>Messages {unread>0&&<span className="app-link-badge" aria-label={`${unread} unread`}>{unread>99?'99+':unread} new</span>}<span aria-hidden="true">↗</span></strong><span>{resident?'Message property management or neighbors, and find office contacts and common answers.':'Reply to residents and keep contact details up to date.'}</span><small>Opens in a new tab</small></a>
  <a href={portalUrl('/discussion-board')} target="_blank" rel="noopener noreferrer"><span className="eyebrow">COMMUNITY SPACE</span><strong>Discussion Board <span aria-hidden="true">↗</span></strong><span>Share ideas and join your neighbors in conversation.</span><small>Opens in a new tab</small></a>
  {resident&&<a href={portalUrl('/my-account')} target="_blank" rel="noopener noreferrer"><span className="eyebrow">LEASE & BILLING</span><strong>My Account <span aria-hidden="true">↗</span></strong><span>Your lease documents, bills and payment history.</span><small>Opens in a new tab</small></a>}
 </nav>;
}
export default function CommunityWorkspace({kind}) {
 const [user,setUser]=useState(null),[error,setError]=useState(''),[loading,setLoading]=useState(true),[attempt,setAttempt]=useState(0);
 const page=pages[kind];
 useEffect(()=>{let active=true;setLoading(true);setError('');api('/auth/me').then(account=>{if(active)setUser(account);}).catch(e=>{if(active)setError(e.message);}).finally(()=>{if(active)setLoading(false);});return()=>{active=false;};},[attempt]);
 useEffect(()=>{document.title=`${page.title} · Community Property Management System`;},[page.title]);
 const allowed=user?.status==='APPROVED'&&(page.residentOnly?user.role==='RESIDENT':['RESIDENT','MANAGER'].includes(user.role));
 return <div className="community-workspace-page"><header><a className="brand" href={portalUrl('/')} target="_blank" rel="noopener noreferrer">CP <span>Community Property <small>MANAGEMENT SYSTEM</small></span></a><span>{user?.name}</span></header><main className="community-workspace-main"><div className="workspace-heading"><span className="eyebrow">{page.eyebrow}</span><h1>{page.title}</h1><p>{page.text}</p></div>
 {loading?<p role="status">Connecting...</p>:error?<section className="card"><p className="message error" role="alert">{error}</p><div className="actions"><button onClick={()=>setAttempt(n=>n+1)}>Retry</button><a href={portalLogin()}>Sign in</a></div></section>
  :!allowed?<section className="card"><p>{page.residentOnly?'My Account is for approved residents. Managers share lease documents from each resident’s details and create bills under Payments.':'Approved resident or manager access is required.'}</p></section>
  :kind==='hub'?<CommunityHub user={user}/>:kind==='account'?<MyAccount user={user}/>
  :kind==='messages'?(user.role==='MANAGER'?<div className="messages-with-support manager"><ManagerDirectMessages user={user}/><SupportPanel user={user}/></div>:<ResidentMessages user={user} aside={<SupportPanel user={user}/>}/>)
  :<DiscussionBoard user={user}/>}
 </main>{user&&<IdleSignOut/>}</div>;
}
