package com.cpms.community;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
@RestController
@RequestMapping("/api/manager")
public class ManagerApi {
    private final ManagerService service;
    public ManagerApi(ManagerService service){this.service=service;}
    public record Code(@NotBlank @Size(max=100) String code) {}
    public record Room(@NotBlank @Size(max=30) String room,@Size(max=1000) String reason) {}
    public record Provider(@NotBlank @Size(max=100) String name,@NotBlank @Email @Size(max=254) String email,@NotNull Account.ProviderType providerType) {}
    public record ServiceType(@NotNull Account.ProviderType providerType) {}
    @GetMapping("/invite-code") public Map<String,String> code(Authentication a){return Map.of("code",service.code(a.getName()));}
    @PutMapping("/invite-code") public Map<String,String> code(Authentication a,@Valid @RequestBody Code p){service.code(a.getName(),p.code());return code(a);}
    @PutMapping("/residents/{id}/room") public Api.AccountView room(Authentication a,@PathVariable Long id,@Valid @RequestBody Room p){return service.room(a.getName(),id,p.room(),p.reason());}
    @GetMapping("/residents/{id}/room-history") public List<RoomChange> history(Authentication a,@PathVariable Long id){return service.history(a.getName(),id);}
    @GetMapping("/providers") public List<Api.AccountView> providers(Authentication a){return service.providers(a.getName());}
    @PostMapping("/providers") @ResponseStatus(HttpStatus.CREATED) public ManagerService.ProviderCreated provider(Authentication a,@Valid @RequestBody Provider p){return service.provider(a.getName(),p.name(),p.email(),p.providerType());}
    @PutMapping("/providers/{id}/service-type") public Api.AccountView serviceType(Authentication a,@PathVariable Long id,@Valid @RequestBody ServiceType p){return service.providerType(a.getName(),id,p.providerType());}
}
