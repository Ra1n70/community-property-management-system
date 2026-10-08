import React, { useEffect, useState } from 'react';
import { api, refreshCsrf, portalRole } from './api';

const portals = {
  RESIDENT: { label: 'Resident', path: '/resident/login', title: 'Welcome home.', subtitle: 'Your community, all in one place.' },
  MANAGER: { label: 'Property manager', path: '/manager/login', title: 'Manage your community.', subtitle: 'A dedicated space for property management.' },
  PROVIDER: { label: 'Service provider', path: '/provider/login', title: 'Ready to make a difference.', subtitle: 'Sign in to your service workspace.' },
};
const roleAtPath = portalRole;
export const formValues = e => Object.fromEntries(new FormData(e.currentTarget));
export function Input({ label, name, type = 'text', ...props }) {
  return <label>{label}<input name={name} type={type} required {...props}/></label>;
}
function NewPasswords() {
  return <><Input label="New password" name="newPassword" type="password" minLength={10} maxLength={64} autoComplete="new-password"/><Input label="Confirm new password" name="confirmation" type="password" minLength={10} maxLength={64} autoComplete="new-password"/><p className="hint">Use 10–64 characters. Your new password must differ from your current password.</p></>;
}
export function SavedCode({ code, onContinue }) {
  const [saved, setSaved] = useState(false), [feedback, setFeedback] = useState('');
  async function copy() { try {await navigator.clipboard.writeText(code);setFeedback('Copied. Save it somewhere safe.');} catch {setFeedback('Copy unavailable. Select the code or download it.');} }
  function download() {
    const url=URL.createObjectURL(new Blob([`Community Property Management System recovery code\n${code}\nKeep this private. A newly generated code replaces the previous code.\n`],{type:'text/plain'}));
    const a=document.createElement('a');a.href=url;a.download='community-property-management-system-recovery-code.txt';a.click();URL.revokeObjectURL(url);
  }
  return <section className="card recovery-card"><span className="eyebrow">ACCOUNT SECURITY</span><h2>Save your recovery code.</h2>
    <div className="message error">This code is shown only once. Keep it in a password manager or another safe place. Never share it. If you lose both your password and this code, you will need identity verification and manual assistance.</div>
    <p>Any previous recovery code is now invalid.</p><code className="recovery-code">{code}</code>
    <div className="actions"><button onClick={copy}>Copy code</button><button onClick={download}>Download</button></div><p role="status">{feedback}</p>
    <label className="check-label"><input type="checkbox" checked={saved} onChange={e=>setSaved(e.target.checked)}/>I have saved my recovery code in a safe place.</label>
    <button className="primary" disabled={!saved} onClick={onContinue}>Saved — continue</button></section>;
}

export function LoginPages({ run, busy, onLogin, onCode }) {
  const [role,setRole]=useState(roleAtPath),[page,setPage]=useState(()=>location.pathname==='/recover-link'?'link':location.hash==='#register'&&roleAtPath()==='RESIDENT'?'register':'login');
  const [token,setToken]=useState(()=>new URLSearchParams(location.hash.slice(1)).get('token') || '');
  useEffect(()=>{const sync=()=>{if(location.pathname==='/recover-link'){setToken(new URLSearchParams(location.hash.slice(1)).get('token')||'');setPage('link');}};window.addEventListener('hashchange',sync);return()=>window.removeEventListener('hashchange',sync);},[]);
  useEffect(()=>{const back=()=>{setRole(roleAtPath());setPage(location.pathname==='/recover-link'?'link':'login');};window.addEventListener('popstate',back);return()=>window.removeEventListener('popstate',back);},[]);
  // Check the link as soon as it opens so replaced, revoked, used or expired links never show a usable form.
  const [linkState,setLinkState]=useState({status:'checking'});
  const [checks,setChecks]=useState(0);
  useEffect(()=>{
    if(page!=='link')return;
    if(!token){setLinkState({status:'missing'});return;}
    let active=true;setLinkState({status:'checking'});
    api('/auth/recovery-link/status',{method:'POST',data:{token}})
      .then(r=>{if(active)setLinkState(r.valid?{status:'valid',expiresAt:r.expiresAt}:{status:'invalid'});})
      .catch(e=>{if(active)setLinkState({status:'error',message:e.message});});
    return()=>{active=false;};
  },[page,token,checks]);
  useEffect(()=>{
    if(linkState.status!=='valid')return;
    const left=new Date(linkState.expiresAt)-Date.now();
    const timer=setTimeout(()=>setLinkState({status:'invalid'}),Math.max(0,Math.min(left,2147483647)));
    return()=>clearTimeout(timer);
  },[linkState]);
  const linkUsable=page!=='link'||linkState.status==='valid';
  // Each role has its own sign-in page and session, so switching entrance loads that page.
  function portal(next) {if(next!==role){location.assign(portals[next].path);return;}setPage('login');history.replaceState({},'',portals[next].path);}
  async function submit(e) {
    e.preventDefault();const data=formValues(e);
    await run(async()=>{
      if(page==='login') {await api('/auth/login',{method:'POST',form:true,data:{...data,role}});await refreshCsrf();await onLogin();}
      else if(page==='register') {const result=await api('/auth/register',{method:'POST',data});setPage('login');onCode(result.recoveryCode);}
      else {
        if(data.newPassword!==data.confirmation)throw new Error('New passwords do not match.');
        let result;
        try {result=await api(page==='link'?'/auth/recovery-link':'/auth/recover',{method:'POST',data:page==='link'?{...data,token}:data});}
        catch(error) {if(page==='link')setChecks(n=>n+1);throw error;}
        const nextRole=result.role || role;
        setRole(nextRole);history.replaceState({},'',portals[nextRole].path);setPage('login');onCode(result.recoveryCode);
      }
    });
  }
  return <section className={`card auth portal-${role.toLowerCase()}`}>
    {page!=='link'&&<nav className="portal-tabs" aria-label="Login entrances">{Object.entries(portals).map(([key,p])=><button key={key} disabled={busy} aria-pressed={role===key} className={role===key?'active':''} onClick={()=>portal(key)}>{p.label}</button>)}</nav>}
    <span className="eyebrow">{page==='link'?'ACCOUNT RECOVERY':portals[role].label.toUpperCase()+' ACCESS'}</span>
    <h2>{page==='register'?'Join your community.':page==='forgot'?'Recover your account.':page==='link'?'Set a new password.':portals[role].title}</h2>
    <p>{page==='login'?portals[role].subtitle:page==='link'?'Use the recovery link provided after your identity was verified.':'Your role and approval status will remain unchanged.'}</p>
    {page==='link'&&linkState.status==='checking'&&<p role="status">Checking your recovery link…</p>}
    {page==='link'&&linkState.status==='valid'&&<p className="hint">This one-time link expires {new Date(linkState.expiresAt).toLocaleString('en-US',{dateStyle:'medium',timeStyle:'short'})}.</p>}
    {page==='link'&&linkState.status==='invalid'&&<div className="message error" role="alert">This recovery link is no longer valid. It may have expired after 30 minutes, been replaced by a newer link, been revoked, or already been used. Ask your property manager for a new link.</div>}
    {page==='link'&&linkState.status==='error'&&<div className="message error" role="alert">{linkState.message} <button type="button" className="quiet" onClick={()=>setChecks(n=>n+1)}>Try again</button></div>}
    {linkUsable&&<form key={`${role}-${page}`} onSubmit={submit}>
      {page==='register'&&<Input label="Full name" name="name" maxLength={100}/>}
      {page!=='link'&&<Input label="Email address" name="email" type="email" maxLength={254} autoComplete="username"/>}
      {['login','register'].includes(page)?<Input label="Password" name="password" type="password" minLength={page==='register'?10:undefined} maxLength={64} autoComplete={page==='login'?'current-password':'new-password'}/>:<>
        {page==='forgot'&&<Input label="Recovery code" name="code" maxLength={100} autoComplete="off" spellCheck={false}/>}
        <NewPasswords/>
      </>}
      {page==='register'&&<><div className="grid"><Input label="Room / unit" name="room" maxLength={30}/><Input label="Community code" name="inviteCode"/></div><p className="hint">Registration requires property manager approval. Save your recovery code after submitting.</p></>}
      <button className="primary" disabled={busy || (page==='link'&&!token)}>{busy?'Please wait…':page==='login'?`Sign in as ${portals[role].label.toLowerCase()}`:page==='register'?'Submit application':'Reset password'}</button>
    </form>}
    {page==='login'?<div className="access-links"><button disabled={busy} className="quiet" onClick={()=>setPage('forgot')}>Forgot password?</button>{role==='RESIDENT'&&<button disabled={busy} className="quiet" onClick={()=>setPage('register')}>Join the community</button>}</div>:<button disabled={busy} className="quiet" onClick={()=>portal(role)}>Back to sign in</button>}
    {page==='forgot'&&<div className="help-box"><strong>Lost your recovery code too?</strong><p>{role==='MANAGER'?'Contact the project administrator for identity verification and assisted recovery.':'Contact your property manager. After identity verification, they can provide a one-time recovery link.'}</p></div>}
    {page==='link'&&linkState.status==='missing'&&<p role="alert">This recovery link is incomplete. Request a new link from your property manager.</p>}
  </section>;
}

export function AccountSecurity({ user, run, busy, onCode, onSignedOut }) {
  const [mode,setMode]=useState('');
  async function submit(e) {
    e.preventDefault();const data=formValues(e);
    await run(async()=>{
      if(mode==='password') {
        if(data.newPassword!==data.confirmation)throw new Error('New passwords do not match.');
        await api('/auth/password',{method:'POST',data});onSignedOut();
      } else {const result=await api('/auth/recovery-code',{method:'POST',data});onCode(result.recoveryCode);}
      setMode('');
    });
  }
  if(!user.hasRecoveryCode)return <section className="card security-panel"><h2>Protect your account.</h2><p>Before continuing, create and save your recovery code. You will need it if you forget your password.</p><button className="primary" disabled={busy} onClick={()=>run(async()=>onCode((await api('/auth/recovery-code',{method:'POST',data:{}})).recoveryCode))}>Create recovery code</button></section>;
  return <section className="card security-panel"><h2>Account security</h2><div className="actions"><button disabled={busy} onClick={()=>setMode('password')}>Change password</button><button disabled={busy} onClick={()=>setMode('code')}>Replace recovery code</button></div>
    {mode&&<form className="security-form" onSubmit={submit}><h3>{mode==='password'?'Change your password':'Replace your recovery code'}</h3><Input label="Current password" name="currentPassword" type="password" maxLength={64} autoComplete="current-password"/>
      {mode==='password'?<><NewPasswords/><p className="hint">All current sessions will be signed out. Your recovery code remains valid.</p></>:<p>Your previous recovery code and pending recovery links will stop working. Save the new code when it appears.</p>}
      <div className="actions"><button className="primary" disabled={busy}>Confirm</button><button type="button" disabled={busy} onClick={()=>setMode('')}>Cancel</button></div></form>}
  </section>;
}

const englishTime=value=>new Date(value).toLocaleString('en-US',{dateStyle:'medium',timeStyle:'short'});
// Server status is a snapshot; a pending link becomes expired on screen when its 30 minutes pass.
export function linkStatus(link,now=Date.now()) {return link.status==='PENDING'&&new Date(link.expiresAt).getTime()<=now?'EXPIRED':link.status;}
function useNow(){const [now,setNow]=useState(Date.now());useEffect(()=>{const t=setInterval(()=>setNow(Date.now()),15000);return()=>clearInterval(t);},[]);return now;}
const linkLabels={PENDING:'Active',EXPIRED:'Expired',REVOKED:'Revoked',COMPLETED:'Used'};
export function RecoveryLinkList({ links }) {
  const now=useNow();
  if(!links.length)return <p>No recovery links yet.</p>;
  return <ul className="recovery-links">{links.map(l=>{const s=linkStatus(l,now);return <li key={l.id}><span className={`badge link-${s}`}>{linkLabels[s]||s}</span> Created {englishTime(l.createdAt)} · {s==='PENDING'?'expires':'expiry'} {englishTime(l.expiresAt)} · Manager #{l.managerId}{l.verificationNote&&<small>{l.verificationNote}</small>}</li>;})}</ul>;
}
export function IssuedLink({ issued, links, busy, onCopy, copied }) {
  const now=useNow();
  const current=links.find(l=>l.id===issued.linkId)||links.find(l=>new Date(l.expiresAt).getTime()===new Date(issued.expiresAt).getTime());
  const status=current?linkStatus(current,now):new Date(issued.expiresAt).getTime()<=now?'EXPIRED':'PENDING';
  if(status!=='PENDING')return <div className="help-box"><strong>The link for {issued.email} is no longer usable ({(linkLabels[status]||status).toLowerCase()}).</strong><p>Create a new link if the person still needs to reset their password.</p></div>;
  return <div className="help-box"><strong>One-time link for {issued.email}</strong><input aria-label="One-time setup or recovery link" readOnly value={issued.url}/><p>Expires {englishTime(issued.expiresAt)}. Copy before closing this panel; share through a verified private contact. Creating another link or revoking replaces this one. Localhost links work only on this computer.</p><button disabled={busy} onClick={onCopy}>{copied?'Copied':'Copy link'}</button></div>;
}

export function AssistedRecovery({ run,busy }) {
  const [targets,setTargets]=useState([]),[selected,setSelected]=useState(null),[events,setEvents]=useState([]),[issued,setIssued]=useState(null),[copied,setCopied]=useState(false);
  const endpoint=id=>`/manager/accounts/${id}/recovery`;
  async function select(a){setIssued(null);setCopied(false);setSelected(a);setEvents(await api(endpoint(a.id)));}
  return <section className="card security-panel"><h2>Assisted account recovery</h2><p>Verify the person's identity against resident or provider records before creating a link. Knowing an email address is not proof of identity.</p>
    <button disabled={busy} onClick={()=>run(async()=>setTargets(await api('/manager/recovery-accounts')))}>Load resident and provider accounts</button>
    <div className="recovery-targets">{targets.map(a=><button key={a.id} disabled={busy} onClick={()=>run(()=>select(a))}>{a.name} · {a.email} · {a.role}</button>)}</div>
    {selected&&<div className="detail"><h3>{selected.name} · {selected.email}</h3><form onSubmit={e=>{e.preventDefault();const d=formValues(e);run(async()=>{const result=await api(endpoint(selected.id),{method:'POST',data:{note:d.note,verified:d.verified==='on'}});setIssued({...result,url:`${location.origin}/recover-link#token=${result.token}`});setCopied(false);setEvents(await api(endpoint(selected.id)));});}}>
      <label>Identity verification notes<textarea name="note" required maxLength={1000}/></label><label className="check-label"><input name="verified" type="checkbox" required/>I have verified that the requester owns this account.</label>
      <button className="primary" disabled={busy}>Create 30-minute recovery link</button></form>
      {issued&&<IssuedLink issued={{...issued,email:selected.email}} links={events} busy={busy} copied={copied} onCopy={()=>run(async()=>{await navigator.clipboard.writeText(issued.url);setCopied(true);})}/>}
      <div className="actions"><button disabled={busy} onClick={()=>run(async()=>{await api(endpoint(selected.id),{method:'DELETE'});setEvents(await api(endpoint(selected.id)));})}>Revoke unused links</button><button disabled={busy} onClick={()=>run(async()=>setEvents(await api(endpoint(selected.id))))}>Refresh history</button><button onClick={()=>{setSelected(null);setIssued(null);}}>Close</button></div>
      <h3>Recovery links</h3><RecoveryLinkList links={events}/>
    </div>}
  </section>;
}
