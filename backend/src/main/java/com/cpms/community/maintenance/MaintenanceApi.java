package com.cpms.community.maintenance;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.List;

@RestController @RequestMapping("/api/maintenance")
public class MaintenanceApi {
    public record Create(@NotBlank @Size(max=80) String category,@NotBlank @Size(max=200) String location,
                         @NotBlank @Size(max=3000) String description,@Size(max=3) List<@NotBlank @Size(max=2000) String> imageUrls,
                         @Future Instant preferredTime) {}
    public record Assign(@NotNull Long version,@NotNull Long assigneeId,@NotNull MaintenanceTicket.Priority priority,
                         @Future Instant targetCompletionTime,@Size(max=1000) String note) {}
    public record Action(@NotNull Long version,@Size(max=2000) String note,MaintenanceTicket.Outcome outcome) {}
    private final MaintenanceService service;
    public MaintenanceApi(MaintenanceService service){this.service=service;}
    @GetMapping public List<MaintenanceTicket> list(Authentication a){return service.list(a.getName());}
    /** Paged and filtered list used by the Maintenance page; from/to are instants, to is exclusive. */
    @GetMapping("/page") public MaintenanceService.TicketPage page(Authentication a,@RequestParam(required=false) MaintenanceTicket.Status status,
            @RequestParam(required=false) MaintenanceTicket.Priority priority,@RequestParam(required=false) String category,
            @RequestParam(required=false) Long assigneeId,@RequestParam(defaultValue="false") boolean unassigned,
            @RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to,
            @RequestParam(required=false) Integer page,@RequestParam(required=false) Integer size){
        return service.page(a.getName(),new MaintenanceService.Filter(status,priority,category,assigneeId,unassigned,from,to),page,size);
    }
    @GetMapping("/{id}") public MaintenanceTicket get(Authentication a,@PathVariable Long id){return service.get(a.getName(),id);}
    @GetMapping("/assignees")public List<MaintenanceService.Assignee> assignees(Authentication a){return service.assignees(a.getName());}
    @PostMapping(consumes="application/json") @ResponseStatus(HttpStatus.CREATED)
    public MaintenanceTicket create(Authentication a,@Valid @RequestBody Create body){return service.create(a.getName(),body);}
    @PostMapping(consumes="multipart/form-data") @ResponseStatus(HttpStatus.CREATED)
    public MaintenanceTicket upload(Authentication a,@Valid @RequestPart("request") Create body,
            @RequestPart(value="photos",required=false) List<org.springframework.web.multipart.MultipartFile> photos){
        return service.createWithPhotos(a.getName(),body,photos==null?List.of():photos);
    }
    @GetMapping("/{id}/photos/{key}")
    public org.springframework.http.ResponseEntity<byte[]> photo(Authentication a,@PathVariable Long id,@PathVariable String key){
        return org.springframework.http.ResponseEntity.ok().header("Cache-Control","private, no-store")
                .contentType(org.springframework.http.MediaType.IMAGE_PNG).body(service.photo(a.getName(),id,key));
    }
    @GetMapping("/{id}/history") public List<MaintenanceEvent> history(Authentication a,@PathVariable Long id){return service.history(a.getName(),id);}
    @PostMapping("/{id}/assign") public MaintenanceTicket assign(Authentication a,@PathVariable Long id,@Valid @RequestBody Assign body){return service.assign(a.getName(),id,body);}
    @PostMapping("/{id}/reject") public MaintenanceTicket reject(Authentication a,@PathVariable Long id,@Valid @RequestBody Action body){return service.act(a.getName(),id,"reject",body);}
    @PostMapping("/{id}/start") public MaintenanceTicket start(Authentication a,@PathVariable Long id,@Valid @RequestBody Action body){return service.act(a.getName(),id,"start",body);}
    @PostMapping("/{id}/resolve") public MaintenanceTicket resolve(Authentication a,@PathVariable Long id,@Valid @RequestBody Action body){return service.act(a.getName(),id,"resolve",body);}
    @PostMapping("/{id}/reopen") public MaintenanceTicket reopen(Authentication a,@PathVariable Long id,@Valid @RequestBody Action body){return service.act(a.getName(),id,"reopen",body);}
    @PostMapping("/{id}/close") public MaintenanceTicket close(Authentication a,@PathVariable Long id,@Valid @RequestBody Action body){return service.act(a.getName(),id,"close",body);}
    @PostMapping("/{id}/confirm") public MaintenanceTicket confirm(Authentication a,@PathVariable Long id,@Valid @RequestBody Action body){return service.act(a.getName(),id,"confirm",body);}
}
