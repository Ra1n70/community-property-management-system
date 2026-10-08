import React, {useState} from 'react';
import CommunitySearch, {searchFocus} from './CommunitySearch';
import MaintenancePanel from './MaintenancePanel';
import CourierManager from './CourierManager';
export default function ProviderWorkspace({user}) {
  const [focus,setFocus]=useState(null);
  if (user.status !== 'APPROVED' || !user.providerType) return <section className="card"><h2>Service provider access</h2><p>Your property manager must approve your account and select a service type before you can access assignments.</p></section>;
  if (user.providerType === 'DELIVERY') return <><section className="card"><span className="eyebrow">DELIVERY PARTNER</span><h2>{user.name} · Courier management</h2><p>Your couriers store packages at the community locker kiosk with their own store codes. Manage their codes and follow your deliveries here.</p></section><CourierManager scope="company"/></>;
  return <><section className="card"><span className="eyebrow">MAINTENANCE PARTNER</span><h2>{user.name} · Repair assignments</h2><p>View your assigned requests, start work and submit repair results for resident confirmation.</p></section><CommunitySearch user={user} onOpen={result=>setFocus(searchFocus(result,{MAINTENANCE:'maintenance'})?.focus||null)}/><MaintenancePanel user={user} focus={focus}/></>;
}
