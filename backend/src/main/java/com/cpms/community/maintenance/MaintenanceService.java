package com.cpms.community.maintenance;

import com.cpms.community.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.net.URI;
import java.util.*;
import static com.cpms.community.maintenance.MaintenanceTicket.Status.*;

@Service @Transactional
public class MaintenanceService {
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entities;
    @org.springframework.beans.factory.annotation.Autowired private MaintenancePhotos photos;
    public MaintenanceTicket createWithPhotos(String email,MaintenanceApi.Create body,List<org.springframework.web.multipart.MultipartFile> files){
        if(body.imageUrls()!=null&&!body.imageUrls().isEmpty())throw AccountService.fail(HttpStatus.BAD_REQUEST,"Use file uploads instead of photo links.");
        MaintenanceTicket t=create(email,body);
        t.imageUrls=photos.save(t.id,files);
        return tickets.saveAndFlush(t);
    }
    @Transactional(readOnly=true)
    public byte[] photo(String email,Long id,String key){
        Account a=account(email);MaintenanceTicket t=tickets.findByIdAndCommunity(id,a.community).orElseThrow(this::missing);visible(a,t);
        if(!t.imageUrls.contains("/api/maintenance/"+id+"/photos/"+key))throw missing();
        return photos.read(key);
    }
    private final MaintenanceRepository tickets;
    private final MaintenanceEventRepository events;
    private final AccountService accountService;
    private final AccountRepository accounts;
    public MaintenanceService(MaintenanceRepository tickets,MaintenanceEventRepository events,AccountService accountService,AccountRepository accounts){this.tickets=tickets;this.events=events;this.accountService=accountService;this.accounts=accounts;}
    private Account account(String email){Account a=accountService.current(email);if(a.status!=Account.Status.APPROVED)throw AccountService.fail(HttpStatus.FORBIDDEN,"Approved account required.");if(a.role==Account.Role.PROVIDER && a.providerType!=Account.ProviderType.MAINTENANCE)throw AccountService.fail(HttpStatus.FORBIDDEN,"Maintenance provider access required.");return a;}
    private void role(Account a,Account.Role role){if(a.role!=role)throw AccountService.fail(HttpStatus.FORBIDDEN,"This action is not available for your role.");}
    private RuntimeException missing(){return AccountService.fail(HttpStatus.NOT_FOUND,"Maintenance request not found.");}
    private void visible(Account a,MaintenanceTicket t){if(a.role==Account.Role.MANAGER)return;if(a.role==Account.Role.RESIDENT && a.id.equals(t.residentId))return;if(a.role==Account.Role.PROVIDER && a.id.equals(t.assigneeId))return;throw missing();}
    private void requireState(MaintenanceTicket t,MaintenanceTicket.Status... allowed){if(!Arrays.asList(allowed).contains(t.status))throw AccountService.fail(HttpStatus.CONFLICT,"This request has changed. Refresh to see available actions.");}
    private void version(MaintenanceTicket t,Long version){if(!Objects.equals(t.version,version))throw AccountService.fail(HttpStatus.CONFLICT,"This request was updated by someone else. Refresh before continuing.");}
    private String note(String text,int limit){if(text==null||text.isBlank()||text.strip().length()>limit)throw AccountService.fail(HttpStatus.BAD_REQUEST,"Please provide a note of 1 to "+limit+" characters.");return text.strip();}
    private String optionalNote(String text,int limit){if(text==null||text.isBlank())return "";if(text.strip().length()>limit)throw AccountService.fail(HttpStatus.BAD_REQUEST,"Notes can contain up to "+limit+" characters.");return text.strip();}
    private void record(MaintenanceTicket t,Account a,String action,String note){MaintenanceEvent e=new MaintenanceEvent();e.ticketId=t.id;e.actorId=a.id;e.actorName=a.name;e.action=action;e.note=note;events.save(e);}
    @Transactional(readOnly=true)
    public List<MaintenanceTicket> list(String email){Account a=account(email);return switch(a.role){case MANAGER->tickets.findByCommunityOrderByUpdatedAtDesc(a.community);case RESIDENT->tickets.findByCommunityAndResidentIdOrderByUpdatedAtDesc(a.community,a.id);case PROVIDER->tickets.findByCommunityAndAssigneeIdOrderByUpdatedAtDesc(a.community,a.id);};}
    public record Filter(MaintenanceTicket.Status status,MaintenanceTicket.Priority priority,String category,Long assigneeId,boolean unassigned,Instant from,Instant to){}
    public record Worker(Long id,String name){}
    /** One page of the caller's requests; managers also get every category and assignee for the filter menus. */
    public record TicketPage(List<MaintenanceTicket> items,int page,int size,long total,int totalPages,List<String> categories,List<Worker> assignees){}
    @Transactional(readOnly=true)
    public TicketPage page(String email,Filter f,Integer page,Integer size){
        Account a=account(email);boolean manager=a.role==Account.Role.MANAGER;
        if(f.from()!=null&&f.to()!=null&&!f.from().isBefore(f.to()))throw AccountService.fail(HttpStatus.BAD_REQUEST,"Start date must be on or before end date.");
        // Managers see urgent requests first, as the list always did; everyone sees the latest activity first.
        org.springframework.data.domain.Sort sort=manager?org.springframework.data.domain.Sort.by("priority").descending().and(org.springframework.data.domain.Sort.by("updatedAt","id").descending()):org.springframework.data.domain.Sort.by("updatedAt","id").descending();
        org.springframework.data.jpa.domain.Specification<MaintenanceTicket> spec=(t,q,cb)->{
            List<jakarta.persistence.criteria.Predicate> p=new ArrayList<>();
            p.add(cb.equal(t.get("community"),a.community));
            if(a.role==Account.Role.RESIDENT)p.add(cb.equal(t.get("residentId"),a.id));
            if(a.role==Account.Role.PROVIDER)p.add(cb.equal(t.get("assigneeId"),a.id));
            if(f.status()!=null)p.add(cb.equal(t.get("status"),f.status()));
            if(manager&&f.priority()!=null)p.add(cb.equal(t.get("priority"),f.priority()));
            if(manager&&f.category()!=null&&!f.category().isBlank())p.add(cb.equal(t.get("category"),f.category()));
            if(manager&&f.unassigned())p.add(cb.isNull(t.get("assigneeId")));
            else if(manager&&f.assigneeId()!=null)p.add(cb.equal(t.get("assigneeId"),f.assigneeId()));
            if(manager&&f.from()!=null)p.add(cb.greaterThanOrEqualTo(t.get("createdAt"),f.from()));
            if(manager&&f.to()!=null)p.add(cb.lessThan(t.get("createdAt"),f.to()));
            return cb.and(p.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        var found=tickets.findAll(spec,PageView.request(page,size,sort));
        List<String> categories=manager?tickets.categories(a.community):List.of();
        List<Worker> workers=manager?tickets.assignees(a.community).stream().map(r->new Worker((Long)r[0],r[1]==null?"Worker #"+r[0]:(String)r[1])).sorted(Comparator.comparing(Worker::name)).toList():List.of();
        return new TicketPage(found.getContent(),found.getNumber(),found.getSize(),found.getTotalElements(),found.getTotalPages(),categories,workers);
    }
    @Transactional(readOnly=true)
    public MaintenanceTicket get(String email,Long id){Account a=account(email);MaintenanceTicket t=tickets.findByIdAndCommunity(id,a.community).orElseThrow(this::missing);visible(a,t);return t;}
    public record Assignee(Long id,String name,String type){}
    @Transactional(readOnly=true)
    public List<Assignee> assignees(String email){Account a=account(email);role(a,Account.Role.MANAGER);return java.util.stream.Stream.of(Account.Role.MANAGER,Account.Role.PROVIDER).flatMap(r->accounts.findByCommunityAndRoleOrderBySubmittedAtDesc(a.community,r).stream()).filter(x->x.status==Account.Status.APPROVED && (x.role==Account.Role.MANAGER||x.providerType==Account.ProviderType.MAINTENANCE)).map(x->new Assignee(x.id,x.name,x.role==Account.Role.MANAGER?"INTERNAL":"THIRD_PARTY")).toList();}
    public MaintenanceTicket create(String email,MaintenanceApi.Create body){
        Account a=account(email);role(a,Account.Role.RESIDENT);
        entities.refresh(a,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        List<String> photos=body.imageUrls()==null?List.of():body.imageUrls().stream().map(String::strip).toList();
        for(String url:photos){try{URI uri=URI.create(url);if(!("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme()))||uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw AccountService.fail(HttpStatus.BAD_REQUEST,"Photo links must be valid HTTP or HTTPS URLs.");}}
        MaintenanceTicket t=new MaintenanceTicket();t.ticketNo="TK-"+UUID.randomUUID();t.community=a.community;t.residentId=a.id;t.residentName=a.name;t.room=a.room;t.category=body.category().strip();t.location=body.location().strip();t.description=body.description().strip();t.preferredTime=body.preferredTime();t.imageUrls=new ArrayList<>(photos);
        tickets.saveAndFlush(t);record(t,a,"SUBMITTED","Request submitted.");return t;
    }
    @Transactional(readOnly=true)
    public List<MaintenanceEvent> history(String email,Long id){Account a=account(email);MaintenanceTicket t=tickets.findByIdAndCommunity(id,a.community).orElseThrow(this::missing);visible(a,t);return events.findByTicketIdOrderByCreatedAtAscIdAsc(id);}
    public MaintenanceTicket assign(String email,Long id,MaintenanceApi.Assign body){
        Account a=account(email);role(a,Account.Role.MANAGER);
        MaintenanceTicket t=tickets.lock(id,a.community).orElseThrow(this::missing);version(t,body.version());requireState(t,PENDING,ACCEPTED,IN_PROGRESS,UNABLE_TO_FIX);
        Account target=accounts.lockById(body.assigneeId()).orElseThrow(()->AccountService.fail(HttpStatus.BAD_REQUEST,"Choose a valid assignee."));
        if(!a.community.equals(target.community)||target.status!=Account.Status.APPROVED||(target.role!=Account.Role.MANAGER&&target.role!=Account.Role.PROVIDER))throw AccountService.fail(HttpStatus.BAD_REQUEST,"Choose an approved manager or provider in your community.");
        if(target.role==Account.Role.PROVIDER && target.providerType!=Account.ProviderType.MAINTENANCE)throw AccountService.fail(HttpStatus.BAD_REQUEST,"Choose a maintenance provider; delivery providers cannot be assigned repairs.");
        String reason=optionalNote(body.note(),1000);String action=t.assigneeId==null?"ASSIGNED":"REASSIGNED";
        t.assigneeId=target.id;t.assigneeName=target.name;t.assigneeType=target.role==Account.Role.MANAGER?"INTERNAL":"THIRD_PARTY";t.priority=body.priority();t.targetCompletionTime=body.targetCompletionTime();t.status=ACCEPTED;t.updatedAt=Instant.now();
        tickets.saveAndFlush(t);record(t,a,action,("Assigned to "+target.name+" (#"+target.id+"). "+reason).strip());return t;
    }
    public MaintenanceTicket act(String email,Long id,String action,MaintenanceApi.Action body){
        Account a=account(email);MaintenanceTicket t=tickets.lock(id,a.community).orElseThrow(this::missing);visible(a,t);version(t,body.version());String text;
        switch(action){
            case "reject"->{role(a,Account.Role.MANAGER);requireState(t,PENDING);text=note(body.note(),1000);t.rejectionReason=text;t.status=REJECTED;}
            case "start","resolve"->{if((a.role!=Account.Role.PROVIDER&&a.role!=Account.Role.MANAGER)||!a.id.equals(t.assigneeId))throw AccountService.fail(HttpStatus.FORBIDDEN,"Only the assigned worker can update this request.");
                if(action.equals("start")){requireState(t,ACCEPTED);t.status=IN_PROGRESS;text="Work started.";}
                else{requireState(t,IN_PROGRESS);text=note(body.note(),2000);t.result=text;
                    if(body.outcome()==MaintenanceTicket.Outcome.UNABLE_TO_FIX){t.status=UNABLE_TO_FIX;action="unable_to_fix";}else t.status=PENDING_CONFIRMATION;}}
            case "reopen"->{role(a,Account.Role.RESIDENT);if(!a.id.equals(t.residentId))throw missing();requireState(t,PENDING_CONFIRMATION);text=note(body.note(),1000);t.status=IN_PROGRESS;}
            case "close"->{role(a,Account.Role.MANAGER);requireState(t,UNABLE_TO_FIX);text=note(body.note(),1000);t.status=UNRESOLVED;t.completedAt=Instant.now();}
            case "confirm"->{role(a,Account.Role.RESIDENT);if(!a.id.equals(t.residentId))throw missing();requireState(t,PENDING_CONFIRMATION);t.status=COMPLETED;t.completedAt=Instant.now();text="Resident confirmed completion.";}
            default->throw AccountService.fail(HttpStatus.BAD_REQUEST,"Unknown action.");
        }
        t.updatedAt=Instant.now();tickets.saveAndFlush(t);record(t,a,action.toUpperCase(Locale.ROOT),text);return t;
    }
}
