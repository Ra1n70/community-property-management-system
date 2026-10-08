import CommunitySearch, {searchFocus} from './CommunitySearch';
import MaintenancePanel from './MaintenancePanel';
import React,{useState} from 'react';
import ResidentPackages from './ResidentPackages';
import ResidentAmenities from './ResidentAmenities';
import './amenity.css';
export default function ResidentCommunity({user}) {
 const [tab,setTab]=useState('packages'),[focus,setFocus]=useState(null);
 // Search results open the record in its own tab.
 function openResult(result){const target=searchFocus(result,{PACKAGE:'packages',AMENITY:'amenities',MAINTENANCE:'maintenance'});if(target){setFocus(target.focus);setTab(target.section);}}
 return <div className="resident-modules"><nav className="person-tabs" aria-label="Community features">{[['search','Search'],['packages','My packages'],['amenities','Amenities & reservations'],['maintenance','Maintenance']].map(([key,label])=><button key={key} aria-pressed={tab===key} onClick={()=>{setFocus(null);setTab(key);}}>{label}</button>)}</nav><React.Fragment key={`${user.room}-${user.status}`}>{tab==='search'?<CommunitySearch user={user} onOpen={openResult}/>:tab==='packages'?<ResidentPackages focus={focus}/>:tab==='maintenance'?<MaintenancePanel user={user} focus={focus}/>:<ResidentAmenities user={user} focus={focus}/>}</React.Fragment></div>;
}
