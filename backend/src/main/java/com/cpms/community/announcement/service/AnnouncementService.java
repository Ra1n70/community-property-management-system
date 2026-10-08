package com.cpms.community.announcement.service;
import com.cpms.community.*;
import com.cpms.community.announcement.dto.*;
import com.cpms.community.announcement.model.Announcement;
import com.cpms.community.announcement.repository.AnnouncementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import java.util.*;

@Service @Transactional(readOnly=true)
public class AnnouncementService {
    private final AnnouncementRepository repository;
    private final AccountService accounts;
    public AnnouncementService(AnnouncementRepository repository,AccountService accounts) {
        this.repository=repository;this.accounts=accounts;
    }
    private Account participant(String email,boolean write) {
        Account a=accounts.current(email);
        if(a.status!=Account.Status.APPROVED || (write?a.role!=Account.Role.MANAGER:
            a.role!=Account.Role.MANAGER && a.role!=Account.Role.RESIDENT))
            throw AccountService.fail(HttpStatus.FORBIDDEN,write?"Approved manager access required.":"Approved resident or manager access required.");
        return a;
    }
    private RuntimeException missing(){return AccountService.fail(HttpStatus.NOT_FOUND,"Announcement not found.");}
    private void version(Announcement a,Long version) {
        if(version==null || !Objects.equals(a.getVersion(),version))
            throw AccountService.fail(HttpStatus.CONFLICT,"This announcement has changed. Refresh before continuing.");
    }
    public List<AnnouncementResponse> findAll(String email) {
        Account a=participant(email,false);
        return repository.findByCommunityOrderByPublishedAtDescIdDesc(a.community).stream().map(AnnouncementResponse::from).toList();
    }
    public AnnouncementResponse findById(String email,Long id) {
        Account a=participant(email,false);
        return AnnouncementResponse.from(repository.findByIdAndCommunity(id,a.community).orElseThrow(this::missing));
    }
    @Transactional public AnnouncementResponse create(String email,AnnouncementRequest request) {
        Account a=participant(email,true);
        return AnnouncementResponse.from(repository.saveAndFlush(new Announcement(request.title().strip(),a.name,
            request.publishedAt(),request.content().strip(),a.community,a.id)));
    }
    @Transactional public AnnouncementResponse update(String email,Long id,AnnouncementRequest request) {
        Account a=participant(email,true);
        Announcement item=repository.lock(id,a.community).orElseThrow(this::missing);
        version(item,request.version());
        item.update(request.title().strip(),request.publishedAt(),request.content().strip());
        return AnnouncementResponse.from(repository.saveAndFlush(item));
    }
    @Transactional public void delete(String email,Long id,Long version) {
        Account a=participant(email,true);
        Announcement item=repository.lock(id,a.community).orElseThrow(this::missing);
        version(item,version);repository.delete(item);
    }
}
