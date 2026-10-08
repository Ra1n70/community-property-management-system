import React, {useState} from 'react';
import {portalUrl} from './api';
import Announcements from './Announcements';
import Polls from './Polls';
import LocalPerks from './LocalPerks';
import './community-hub.css';

export const hubTabs=[['announcements','Announcements'],['voting','Voting'],['perks','Local perks']];
// The tab lives in the URL (?tab=voting), so links and refreshes keep it; /announcements opens the first tab.
function tabFromUrl(){const tab=new URLSearchParams(location.search).get('tab');return hubTabs.some(([key])=>key===tab)?tab:'announcements';}

/** Community Hub: announcements, voting and local perks in one page, with a way into the Discussion Board. */
export default function CommunityHub({user}) {
 const [tab,setTab]=useState(tabFromUrl);
 function choose(next){
  setTab(next);
  const url=new URL(location.href);url.searchParams.set('tab',next);url.searchParams.delete('id');
  history.replaceState(null,'',url.pathname+url.search);
 }
 return <div className="community-hub">
  <nav className="hub-tabs" aria-label="Community Hub">
   {hubTabs.map(([key,label])=><button key={key} type="button" aria-pressed={tab===key} onClick={()=>choose(key)}>{label}</button>)}
   <a href={portalUrl('/discussion-board')} target="_blank" rel="noopener noreferrer">Discussion Board <span aria-hidden="true">↗</span></a>
  </nav>
  {tab==='voting'?<Polls user={user}/>:tab==='perks'?<LocalPerks user={user}/>:<Announcements user={user}/>}
 </div>;
}
