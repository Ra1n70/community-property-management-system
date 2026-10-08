import CommunityWorkspace, {CommunityAppLinks} from './CommunityWorkspace';
import {portalUrl} from './api';
import Payments from './Payments';
import CommunitySearch from './CommunitySearch';
import MaintenancePanel from './MaintenancePanel';
import ResidentCommunity from './ResidentCommunity';
import LockerPanel from './LockerPanel';
import React, { useEffect, useState } from 'react';
import PageErrorBoundary from './PageErrorBoundary';
import IdleSignOut from './IdleSignOut';
import ProviderWorkspace from './ProviderWorkspace';
import { installEnglishValidation } from './englishUi';
import { createRoot } from 'react-dom/client';
import { api, refreshCsrf, portalHome, portalLogin } from './api';
import { ManagerDashboard } from './ManagerDashboard';
import './style.css';
import './account-access.css';
import { LoginPages, SavedCode, AccountSecurity } from './AccountAccess';

function Field({ label, name, type = 'text', defaultValue, ...props }) {
  return <label>{label}<input name={name} type={type} defaultValue={defaultValue} required {...props}/></label>;
}
const values = event => Object.fromEntries(new FormData(event.currentTarget));
// Sign-in pages and role homes are separate pages. A sign-in page never reads or shows the current session;
// a successful sign-in loads the role's home (/resident, /manager, /provider) as a new page.
const isSignInPage = () => location.pathname === '/' || location.pathname === '/recover-link' || /^\/(resident|manager|provider)\/login$/.test(location.pathname);
const leaveNotices = {
  'signed-out': 'You have signed out.',
  'session-ended': 'Your session ended. Please sign in again.',
  'password-changed': 'Password changed. Please sign in with your new password.',
  'idle': 'You were signed out after 60 minutes without activity.',
};
function App() {
  const [signInPage] = useState(isSignInPage);
  const [user, setUser] = useState(null);
  const [recoveryCode,setRecoveryCode] = useState('');
  const [loading, setLoading] = useState(true), [busy, setBusy] = useState(false);
  const [error, setError] = useState(''), [notice, setNotice] = useState(() => leaveNotices[location.hash.slice(1)] || '');
  async function loadUser() {
    const current = await api('/auth/me'); setUser(current);

    return current;
  }
  // Leaving a home always goes to that role's sign-in page; the reason is shown there.
  function leave(reason) { location.replace(portalLogin() + (reason ? `#${reason}` : '')); }
  async function run(action) {
    setBusy(true); setError(''); setNotice('');
    try { await action(); } catch (e) {setError(e.message); if(e.status === 401 && !signInPage)leave('session-ended');} finally {setBusy(false);}
  }
  useEffect(() => {
    if (signInPage) {
      const hash = leaveNotices[location.hash.slice(1)] ? '' : location.hash;
      if (location.pathname !== '/recover-link' && location.pathname + location.hash !== portalLogin() + hash) history.replaceState(null, '', portalLogin() + hash);
    }
    (async () => {
      try { await refreshCsrf(); if (!signInPage) await loadUser(); }
      catch(e) { if (e.status === 401) { leave(); return; } setError(e.message); }
      finally {setLoading(false);}
    })();
  }, []);
  // Keep resident and provider views current after a manager approves, rejects, changes a room or service type.
  const userKey=u=>u&&[u.status,u.room,u.name,u.community,u.providerType,u.rejectionReason,u.hasRecoveryCode].join('|');
  useEffect(() => {
    if (!user || user.role === 'MANAGER' || recoveryCode) return;
    let active = true, running = false;
    async function sync() {
      if (running || document.visibilityState === 'hidden') return;
      running = true;
      try {
        const current = await api('/auth/me');
        if (!active || userKey(current) === userKey(user)) return;
        if (current.status !== user.status) setNotice(current.status === 'APPROVED' ? 'Your application has been approved. Community features are now available.' : current.status === 'REJECTED' ? 'Your application needs changes. Review the reason below.' : 'Your application status was updated.');
        else if (current.room !== user.room) setNotice(`Your room was updated to ${current.room}.`);
        else if (current.providerType !== user.providerType) setNotice('Your service type was updated.');
        setUser(current);
      } catch (e) { if (active && e.status === 401) leave('session-ended'); }
      finally { running = false; }
    }
    const timer = setInterval(sync, 15000);
    window.addEventListener('focus', sync); document.addEventListener('visibilitychange', sync);
    return () => { active = false; clearInterval(timer); window.removeEventListener('focus', sync); document.removeEventListener('visibilitychange', sync); };
  }, [user, recoveryCode]);
  function signedOut() {
    // The server rejects the old session on its next request. Refresh the CSRF token before leaving.
    refreshCsrf().catch(()=>refreshCsrf()).catch(()=>{}).finally(()=>leave('password-changed'));
  }
  function showCode(code) {setRecoveryCode(code);}
  const signedIn = () => location.assign(portalHome());
  return <><header><a className="brand" href={signInPage ? portalLogin() : portalHome()}>CP <span>Community Property <small>MANAGEMENT SYSTEM</small></span></a><div className="header-actions">{user?.status==='APPROVED'&&user.role==='MANAGER'&&<a className="locker-entry" href={portalUrl("/payments")} target="_blank" rel="noopener noreferrer">Payments ↗</a>}{user?.status==='APPROVED'&&user.role==='RESIDENT'&&<a className="locker-entry" href={portalUrl("/my-account")} target="_blank" rel="noopener noreferrer">My Account ↗</a>}<a className="locker-entry" href="/locker-panel" target="_blank" rel="noopener noreferrer">Package locker <span aria-hidden="true">↗</span></a>{user && <div className="identity">{user.name}<button className="locker-entry" disabled={busy} onClick={() => run(async()=>{await api('/auth/logout',{method:'POST'});leave('signed-out');})}>Sign out</button></div>}</div></header>
    <main className={user?.role==='MANAGER'?'manager-main':undefined}><div className="intro"><span className="eyebrow">A PLACE TO BELONG</span><h1>{user?.role==='MANAGER'?'Welcome, manager.':user?'Your community starts here.':'Welcome to your community.'}</h1><p>{user?.role==='MANAGER'?'Review resident applications and help your community get connected.':'One account for community updates, shared spaces and everyday support.'}</p></div>
    {user?.status==='APPROVED'&&['RESIDENT','MANAGER'].includes(user.role)&&<CommunityAppLinks user={user}/>}
    {error && <div className="message error" role="alert">{error}</div>}{notice && <div className="message" role="status">{notice}</div>}
    {loading ? <p role="status">Connecting…</p> : recoveryCode ? <SavedCode code={recoveryCode} onContinue={()=>{setRecoveryCode('');if(user)run(loadUser);}}/> : signInPage ? <LoginPages run={run} busy={busy} onLogin={signedIn} onCode={showCode}/> : !user ? <p role="status">Connecting…</p>
    :!user.hasRecoveryCode?<AccountSecurity user={user} run={run} busy={busy} onCode={showCode} onSignedOut={signedOut}/>
    :user.role==='MANAGER'? <ManagerDashboard user={user} run={run} busy={busy} onCode={showCode} onSignedOut={signedOut}/>:user.role==='RESIDENT'?<section className={`card status ${user.status==='APPROVED'?'resident-status-approved':''}`}><span className={`badge ${user.status}`}>{user.status}</span><h2>{user.status==='PENDING'?'Your application is being reviewed.':user.status==='REJECTED'?'A few details need your attention.':'You’re part of the community.'}</h2><p>{user.name} · Room {user.room} · {user.community}</p>{user.status==='PENDING'&&<p>Your property manager will verify your information. Community features become available after approval.</p>}{user.status==='REJECTED'&&<><div className="message error">{user.rejectionReason}</div><form onSubmit={e=>{e.preventDefault();const data=values(e);run(async()=>{setUser(await api('/auth/resubmit',{method:'POST',data}));setNotice('Your updated application has been submitted.');});}}><div className="grid"><Field label="Full name" name="name" defaultValue={user.name} maxLength={100}/><Field label="Room / unit" name="room" defaultValue={user.room} maxLength={30}/></div><button className="primary" disabled={busy}>Resubmit application</button></form></>}{user.status==='APPROVED'&&<><ResidentCommunity user={user}/></>}<button className="quiet" disabled={busy} onClick={()=>run(loadUser)}>Refresh status</button></section>:<ProviderWorkspace user={user}/>}
    {user && user.role!=='MANAGER' && user.hasRecoveryCode && !recoveryCode && <><AccountSecurity user={user} run={run} busy={busy} onCode={showCode} onSignedOut={signedOut}/></>}
    {user && <IdleSignOut/>}
    <footer>Community Property Management System</footer></main></>;
}
installEnglishValidation();
// Pages that open in their own tab from the home page. /announcements is the Community Hub's first tab (older links keep working).
const workspacePages = {'/messages':'messages','/discussion-board':'discussions','/community-hub':'hub','/announcements':'hub','/my-account':'account'};
createRoot(document.getElementById('root')).render(<PageErrorBoundary>{workspacePages[window.location.pathname] ? <CommunityWorkspace kind={workspacePages[window.location.pathname]}/> : window.location.pathname === '/payments' ? <Payments/> : window.location.pathname === '/locker-panel' ? <LockerPanel/> : <App/>}</PageErrorBoundary>);
