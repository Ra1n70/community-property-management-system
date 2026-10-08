package com.cpms.community.search;

import com.cpms.community.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.time.*;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:searchflow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","locker.pickup-code-secret=test-only","demo.manager-password=","demo.invite-code=test"})
@ActiveProfiles("demo") @AutoConfigureMockMvc
class SearchFlowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountRepository accounts;
    Account manager,resident,other,pending,provider;
    @BeforeEach void setup(){String community=UUID.randomUUID().toString();manager=account(community,Account.Role.MANAGER,Account.Status.APPROVED);resident=account(community,Account.Role.RESIDENT,Account.Status.APPROVED);other=account(UUID.randomUUID().toString(),Account.Role.RESIDENT,Account.Status.APPROVED);pending=account(community,Account.Role.RESIDENT,Account.Status.PENDING);provider=account(community,Account.Role.PROVIDER,Account.Status.APPROVED);}
    Account account(String community,Account.Role role,Account.Status status){Account a=new Account();a.email=UUID.randomUUID()+"@test.local";a.name="Test user";a.passwordHash="test";a.community=community;a.room="101";a.role=role;a.status=status;if(role==Account.Role.PROVIDER)a.providerType=Account.ProviderType.MAINTENANCE;return accounts.saveAndFlush(a);}
    JsonNode call(Account a,MockHttpServletRequestBuilder req,Object body,int expected)throws Exception{
        if(a!=null)req.with(user(a.email).roles(a.role.name()));
        req.with(csrf());if(body!=null)req.contentType("application/json").content(json.writeValueAsString(body));
        String response=mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return response.isBlank()?json.nullNode():json.readTree(response);
    }


    JsonNode ticket(Account owner,String description)throws Exception{return call(owner,post("/api/maintenance"),Map.of("category","Plumbing","location","Kitchen","description",description),201);}
    @Test void searchUsesRealDataAndProtectsOwnership()throws Exception{
        JsonNode own=ticket(resident,"Unique leaking tap");
        Account neighbor=account(manager.community,Account.Role.RESIDENT,Account.Status.APPROVED);
        ticket(neighbor,"Unique private issue");ticket(other,"Unique other community");
        JsonNode mine=call(resident,get("/api/search?q=Unique&communityId="+other.community),null,200);
        assertThat(mine.get("total").asInt()).isEqualTo(1);
        assertThat(mine.get("results").get(0).get("id").asLong()).isEqualTo(own.get("id").asLong());
        assertThat(call(manager,get("/api/search?q=Unique"),null,200).get("total").asInt()).isEqualTo(2);
        call(neighbor,get("/api/search/MAINTENANCE/"+own.get("id").asLong()),null,404);
        call(other,get("/api/search/MAINTENANCE/"+own.get("id").asLong()),null,404);
        assertThat(call(provider,get("/api/search"),null,200).get("total").asInt()).isZero();
        call(manager,post("/api/maintenance/"+own.get("id").asLong()+"/assign"),Map.of("version",own.get("version").asLong(),"assigneeId",provider.id,"priority","NORMAL","note","Fix tap"),200);
        assertThat(call(provider,get("/api/search?q=Unique"),null,200).get("total").asInt()).isEqualTo(1);
        call(provider,get("/api/search/MAINTENANCE/"+own.get("id").asLong()),null,200);
    }
    @Test void validatesPaginationAndAuthorization()throws Exception{
        call(null,get("/api/search"),null,401);call(pending,get("/api/search"),null,403);
        for(String query:List.of("page=-1","size=0","size=51","type=POST","sort=unknown"))call(resident,get("/api/search?"+query),null,400);
        ticket(resident,"First");ticket(resident,"Second");
        JsonNode page=call(resident,get("/api/search?size=1&page=0&type=MAINTENANCE"),null,200);
        assertThat(page.get("total").asInt()).isEqualTo(2);assertThat(page.get("results").size()).isEqualTo(1);
        assertThat(call(resident,get("/api/search?page=2147483647&size=50"),null,200).get("results").size()).isZero();
    }
    long total(Account a,String query)throws Exception{return call(a,get("/api/search").param("q",query),null,200).get("total").asLong();}
    @Test void everyWordMustMatchInAnyOrderIncludingStatusWords()throws Exception{
        ticket(resident,"Leaking tap under the sink, 50% worse today");
        assertThat(total(resident,"tap kitchen")).isEqualTo(1);
        assertThat(total(resident,"KITCHEN   leaking")).isEqualTo(1);
        assertThat(total(resident,"tap garage")).isZero();
        assertThat(total(resident,"pending tap")).isEqualTo(1);
        assertThat(total(resident,"completed tap")).isZero();
        // % and _ are matched literally, not as wildcards.
        assertThat(total(resident,"0%")).isEqualTo(1);
        assertThat(total(resident,"5%")).isZero();
        assertThat(total(resident,"t_p")).isZero();
        JsonNode result=call(resident,get("/api/search").param("q","sink").param("type","AMENITY"),null,200);
        assertThat(result.get("total").asInt()).isZero();
        assertThat(result.get("counts").get("MAINTENANCE").asInt()).isEqualTo(1);
        assertThat(result.get("counts").get("AMENITY").asInt()).isZero();
        assertThat(call(provider,get("/api/search"),null,200).get("counts").has("AMENITY")).isFalse();
    }
    @Test void bestMatchRanksTitlesFirstAndPagesMergeAcrossTypes()throws Exception{
        var body=Map.of("name","Garden Room","type","Event","location","Rooftop","capacity",8,"slotDurationMinutes",60,"maxSlotsPerDay",1,"maxAdvanceDays",7);
        call(manager,post("/api/manager/amenities"),body,201);
        for(int i=0;i<3;i++)ticket(resident,"Garden hose leak "+i);
        JsonNode latest=call(resident,get("/api/search").param("q","garden"),null,200);
        assertThat(latest.get("total").asInt()).isEqualTo(4);
        assertThat(latest.get("results").get(0).get("type").asText()).isEqualTo("MAINTENANCE");
        JsonNode best=call(resident,get("/api/search").param("q","garden").param("sort","relevance"),null,200);
        assertThat(best.get("results").get(0).get("type").asText()).isEqualTo("AMENITY");
        for(String sort:List.of("latest","relevance")){
            List<String> all=new ArrayList<>(),paged=new ArrayList<>();
            call(resident,get("/api/search").param("q","garden").param("sort",sort).param("size","50"),null,200).get("results").forEach(r->all.add(r.get("type").asText()+r.get("id").asText()));
            for(int p=0;p<2;p++)call(resident,get("/api/search").param("q","garden").param("sort",sort).param("size","2").param("page",""+p),null,200).get("results").forEach(r->paged.add(r.get("type").asText()+r.get("id").asText()));
            assertThat(paged).as(sort).containsExactlyElementsOf(all);
        }
    }
    @Test void amenitiesAreScopedToCommunity()throws Exception{
        var body=Map.of("name","Shared Gym","type","Fitness","location","Lobby","capacity",8,"slotDurationMinutes",60,"maxSlotsPerDay",1,"maxAdvanceDays",7);
        JsonNode amenity=call(manager,post("/api/manager/amenities"),body,201);
        assertThat(call(resident,get("/api/search?q=Gym&type=AMENITY"),null,200).get("total").asInt()).isEqualTo(1);
        assertThat(call(other,get("/api/search?q=Gym&type=AMENITY"),null,200).get("total").asInt()).isZero();
        call(provider,get("/api/search/AMENITY/"+amenity.get("id").asLong()),null,404);
    }
}
