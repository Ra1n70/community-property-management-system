import React, {useState} from 'react';
import LeaseDocuments from './LeaseDocuments';
import {PaymentsPanel} from './Payments';
import './community-hub.css';

const tabs=[['lease','Lease documents'],['bills','Bills & payments']];
function tabFromUrl(){const tab=new URLSearchParams(location.search).get('tab');return tabs.some(([key])=>key===tab)?tab:'lease';}

/** The resident's account page: lease documents and bills in one place. Each keeps its own records and permissions. */
export default function MyAccount({user}) {
 const [tab,setTab]=useState(tabFromUrl);
 function choose(next){setTab(next);const url=new URL(location.href);url.searchParams.set('tab',next);history.replaceState(null,'',url.pathname+url.search);}
 return <div className="my-account">
  <p className="my-account-identity">{user.name} · Room {user.room} · {user.community}</p>
  <nav className="hub-tabs" aria-label="My Account">{tabs.map(([key,label])=><button key={key} type="button" aria-pressed={tab===key} onClick={()=>choose(key)}>{label}</button>)}</nav>
  {tab==='bills'?<><p className="hint">Demo payment — no real charge. No card or bank details are collected.</p><PaymentsPanel user={user}/></>:<LeaseDocuments/>}
 </div>;
}
