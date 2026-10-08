package com.cpms.community;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.springframework.http.HttpStatus.*;

@Service
public class ManagerService {
    private final AccountService people;
    private final AccountRepository accounts;
    private final CommunitySettingRepository settings;
    private final RoomChangeRepository changes;
    private final PasswordEncoder encoder;
    private final RecoveryService recovery;
    private final String fallback;
    private final ResidentRoomSync rooms;
    private final com.cpms.community.maintenance.MaintenanceRepository tickets;
    public ManagerService(AccountService people,AccountRepository accounts,CommunitySettingRepository settings,
            RoomChangeRepository changes,PasswordEncoder encoder,RecoveryService recovery,@Value("${demo.invite-code}") String fallback,
            ResidentRoomSync rooms,com.cpms.community.maintenance.MaintenanceRepository tickets) {
        this.people=people;this.accounts=accounts;this.settings=settings;this.changes=changes;this.encoder=encoder;this.recovery=recovery;this.fallback=fallback;this.rooms=rooms;this.tickets=tickets;
    }
    private Account manager(String email) {
        Account a=people.current(email);
        if(a.role!=Account.Role.MANAGER || a.status!=Account.Status.APPROVED)throw AccountService.fail(FORBIDDEN,"Manager access required.");
        return a;
    }
    public String code(String email) {
        String community=manager(email).community;
        return settings.findById(community).map(s->s.inviteCode).orElse(community.equals("Demo Community")?fallback:"");
    }
    @Transactional public void code(String email,String code) {
        Account m=manager(email);
        // Serialize changes made by the same manager; the community key also prevents duplicate settings.
        accounts.lockById(m.id).orElseThrow();
        CommunitySetting s=settings.findById(m.community).orElseGet(CommunitySetting::new);
        s.community=m.community;s.inviteCode=code.strip();settings.save(s);
    }
    private Account resident(String email,Long id,boolean lock) {
        Account m=manager(email);
        Account a=(lock?accounts.lockById(id):accounts.findById(id)).orElseThrow(()->AccountService.fail(NOT_FOUND,"Resident not found."));
        if(a.role!=Account.Role.RESIDENT || !a.community.equals(m.community))throw AccountService.fail(NOT_FOUND,"Resident not found.");
        return a;
    }
    @Transactional public Api.AccountView room(String email,Long id,String room,String reason) {
        Account a=resident(email,id,true);String next=room.strip();
        if(Objects.equals(a.room,next))throw AccountService.fail(BAD_REQUEST,"Enter a different room.");
        RoomChange event=new RoomChange();event.accountId=a.id;event.managerId=manager(email).id;
        event.oldRoom=a.room;event.newRoom=next;event.reason=reason==null||reason.isBlank()?null:reason.strip();changes.save(event);
        a.room=next;rooms.update(a.id,a.community,next);return Api.AccountView.of(a);
    }
    public List<RoomChange> history(String email,Long id){resident(email,id,false);return changes.findByAccountIdOrderByCreatedAtDesc(id);}
    public List<Api.AccountView> providers(String email){return accounts.findByCommunityAndRoleOrderBySubmittedAtDesc(manager(email).community,Account.Role.PROVIDER).stream().map(Api.AccountView::of).toList();}
    public record ProviderCreated(Api.AccountView account,RecoveryService.IssuedLink setup) {}
    @Transactional public ProviderCreated provider(String email,String name,String address,Account.ProviderType type) {
        Account m=manager(email);String normalized=AccountService.normalize(address);
        if(accounts.findByEmail(normalized).isPresent())throw AccountService.fail(CONFLICT,"This email is already registered.");
        Account a=new Account();a.name=name.strip();a.email=normalized;a.community=m.community;
        a.role=Account.Role.PROVIDER;a.status=Account.Status.APPROVED;a.providerType=Objects.requireNonNull(type);
        a.passwordHash=encoder.encode(UUID.randomUUID().toString());accounts.saveAndFlush(a);
        return new ProviderCreated(Api.AccountView.of(a),recovery.createLink(email,a.id,"Provider account created by property manager; send setup link through verified contact.",true));
    }
    @Transactional public Api.AccountView providerType(String email,Long id,Account.ProviderType type) {
        Account m=manager(email);
        Account a=accounts.lockById(id).orElseThrow(()->AccountService.fail(NOT_FOUND,"Provider not found."));
        if(a.role!=Account.Role.PROVIDER||!m.community.equals(a.community))throw AccountService.fail(NOT_FOUND,"Provider not found.");
        if(type!=Account.ProviderType.MAINTENANCE && tickets.findByCommunityAndAssigneeIdOrderByUpdatedAtDesc(m.community,id).stream()
            .anyMatch(t->java.util.Set.of(com.cpms.community.maintenance.MaintenanceTicket.Status.ACCEPTED,
                com.cpms.community.maintenance.MaintenanceTicket.Status.IN_PROGRESS,
                com.cpms.community.maintenance.MaintenanceTicket.Status.PENDING_CONFIRMATION).contains(t.status)))
            throw AccountService.fail(CONFLICT,"Reassign or complete this provider's active maintenance requests before changing service type.");
        a.providerType=type;return Api.AccountView.of(a);
    }
}
