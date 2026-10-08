package com.cpms.community.locker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cpms.community.Account;
import com.cpms.community.AccountRepository;
import com.cpms.community.locker.entity.*;
import com.cpms.community.locker.enums.*;
import com.cpms.community.locker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Couriers store packages at the kiosk with a personal store code managed by their company (or the manager). */
@SpringBootTest(properties = {"locker.pickup-code-secret=test-only-secret", "demo.manager-password="})
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class CarrierPanelFlowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AccountRepository accounts;
    @Autowired LockerRepository lockers;
    @Autowired LockerCellRepository cells;
    @Autowired ParcelRepository parcels;
    @Autowired PickupEmailJobRepository emailJobs;

    String community, kioskIp;
    Account resident, manager, ups, fedex, repair;
    Locker locker;

    Account account(String community, Account.Role role, Account.ProviderType type, String name) {
        Account a = new Account();
        a.email = UUID.randomUUID() + "@test.local";
        a.passwordHash = "test-only";
        a.name = name;
        a.room = role == Account.Role.RESIDENT ? "101" : null;
        a.community = community;
        a.role = role;
        a.status = Account.Status.APPROVED;
        a.providerType = type;
        return accounts.saveAndFlush(a);
    }

    void cell(String number) {
        LockerCell cell = new LockerCell();
        cell.locker = locker;
        cell.cellNumber = number;
        cell.size = CellSize.SMALL;
        cell.status = CellStatus.AVAILABLE;
        cells.saveAndFlush(cell);
    }

    @BeforeEach void setup() {
        community = "Community-" + UUID.randomUUID();
        kioskIp = "198.51.100." + (int) (Math.random() * 250 + 1);
        resident = account(community, Account.Role.RESIDENT, null, "Test Resident");
        manager = account(community, Account.Role.MANAGER, null, "Manager");
        ups = account(community, Account.Role.PROVIDER, Account.ProviderType.DELIVERY, "UPS");
        fedex = account(community, Account.Role.PROVIDER, Account.ProviderType.DELIVERY, "FedEx");
        repair = account(community, Account.Role.PROVIDER, Account.ProviderType.MAINTENANCE, "Repair Co");
        locker = new Locker();
        locker.community = community;
        locker.lockerNumber = UUID.randomUUID().toString();
        locker.location = "Main Lobby";
        locker = lockers.saveAndFlush(locker);
        cell("A01");
        cell("A02");
    }

    JsonNode call(Account actor, MockHttpServletRequestBuilder request, Object body, int expected) throws Exception {
        if (actor != null) request.with(user(actor.email).roles(actor.role.name()));
        request.with(csrf()).with(r -> { r.setRemoteAddr(kioskIp); return r; });
        if (body != null) request.contentType("application/json").content(mapper.writeValueAsString(body));
        String response = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return response.isBlank() ? mapper.nullNode() : mapper.readTree(response);
    }

    JsonNode kiosk(String action, Map<String, Object> body, int expected) throws Exception {
        return call(null, post("/api/locker-panel/" + locker.id + action), body, expected);
    }

    JsonNode store(String code, int expectedStart, int expectedConfirm) throws Exception {
        JsonNode started = kiosk("/intake/start", Map.of("storeCode", code, "room", "101", "residentId", resident.id, "packageSize", "SMALL"), expectedStart);
        if (expectedStart != 201) return started;
        return kiosk("/intake/confirm", Map.of("storeCode", code, "sessionToken", started.get("sessionToken").asText()), expectedConfirm);
    }

    @Test void companyCourierStoresWithPersonalCodeAndIsTraced() throws Exception {
        JsonNode issued = call(ups, post("/api/provider/deliveries/couriers"), Map.of("name", "Alex Courier", "phone", "555-0100"), 201);
        String code = issued.get("storeCode").asText();
        assertThat(code).matches("\\d{8}");
        assertThat(issued.get("courier").get("companyName").asText()).isEqualTo("UPS");
        assertThat(issued.get("courier").get("codeHint").asText()).isEqualTo(code.substring(4));

        kiosk("/courier/verify", Map.of("storeCode", "00000000"), 401);
        JsonNode who = kiosk("/courier/verify", Map.of("storeCode", code), 200);
        assertThat(who.get("name").asText()).isEqualTo("Alex Courier");
        assertThat(who.get("companyName").asText()).isEqualTo("UPS");

        JsonNode recipients = kiosk("/courier/residents", Map.of("storeCode", code, "room", "101"), 200);
        assertThat(recipients.get(0).get("residentId").asLong()).isEqualTo(resident.id);
        assertThat(recipients.get(0).has("email")).isFalse();
        kiosk("/courier/residents", Map.of("storeCode", "12345678", "room", "101"), 401);

        long jobsBefore = emailJobs.count();
        JsonNode started = kiosk("/intake/start", Map.of("storeCode", code, "room", "101", "residentId", resident.id, "packageSize", "SMALL"), 201);
        JsonNode other = call(fedex, post("/api/provider/deliveries/couriers"), Map.of("name", "Sam Other"), 201);
        kiosk("/intake/confirm", Map.of("storeCode", other.get("storeCode").asText(), "sessionToken", started.get("sessionToken").asText()), 403);
        JsonNode confirmed = kiosk("/intake/confirm", Map.of("storeCode", code, "sessionToken", started.get("sessionToken").asText()), 201);
        assertThat(confirmed.get("status").asText()).isEqualTo("STORED");
        assertThat(confirmed.has("pickupCode")).isFalse();
        kiosk("/intake/confirm", Map.of("storeCode", code, "sessionToken", started.get("sessionToken").asText()), 409);

        List<Parcel> stored = parcels.findByResidentIdOrderByStoredAtDesc(resident.id);
        assertThat(stored).hasSize(1);
        Parcel parcel = stored.get(0);
        assertThat(parcel.registeredBy).isEqualTo(ups.id);
        assertThat(parcel.courierId).isEqualTo(issued.get("courier").get("id").asLong());
        assertThat(parcel.courierName).isEqualTo("Alex Courier");
        assertThat(parcel.carrierName).isEqualTo("UPS");
        assertThat(parcel.intakeSource).isEqualTo(IntakeSource.CARRIER_SELF_SERVICE);
        assertThat(emailJobs.count()).isEqualTo(jobsBefore);

        JsonNode deliveries = call(ups, get("/api/provider/deliveries/parcels"), null, 200);
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.get(0).get("courierName").asText()).isEqualTo("Alex Courier");
        assertThat(deliveries.toString()).doesNotContain("Test Resident", resident.email);
        assertThat(call(fedex, get("/api/provider/deliveries/parcels"), null, 200)).isEmpty();
        JsonNode managerView = call(manager, get("/api/manager/parcels"), null, 200);
        assertThat(managerView.toString()).contains("Alex Courier");
    }

    @Test void companyManagesItsOwnCodesAndManagerOnlyManagesIndependentCouriers() throws Exception {
        JsonNode issued = call(ups, post("/api/provider/deliveries/couriers"), Map.of("name", "Alex Courier"), 201);
        long id = issued.get("courier").get("id").asLong();
        String oldCode = issued.get("storeCode").asText();

        call(fedex, post("/api/provider/deliveries/couriers/" + id + "/store-code"), null, 404);
        call(manager, post("/api/manager/couriers/" + id + "/store-code"), null, 403);
        call(manager, put("/api/manager/couriers/" + id + "/active"), Map.of("active", false), 403);
        call(repair, get("/api/provider/deliveries/couriers"), null, 403);
        call(resident, get("/api/provider/deliveries/couriers"), null, 403);
        JsonNode managerList = call(manager, get("/api/manager/couriers"), null, 200);
        assertThat(managerList.get(0).get("manageable").asBoolean()).isFalse();
        assertThat(managerList.get(0).get("storeCode").isNull()).isTrue();
        assertThat(call(ups, get("/api/provider/deliveries/couriers"), null, 200).get(0).get("storeCode").asText()).isEqualTo(oldCode);
        assertThat(call(fedex, get("/api/provider/deliveries/couriers"), null, 200)).isEmpty();

        String newCode = call(ups, post("/api/provider/deliveries/couriers/" + id + "/store-code"), null, 200).get("storeCode").asText();
        kiosk("/courier/verify", Map.of("storeCode", oldCode), 401);
        kiosk("/courier/verify", Map.of("storeCode", newCode), 200);
        assertThat(call(ups, get("/api/provider/deliveries/couriers"), null, 200).get(0).get("storeCode").asText()).isEqualTo(newCode);
        call(ups, put("/api/provider/deliveries/couriers/" + id + "/active"), Map.of("active", false), 200);
        kiosk("/courier/verify", Map.of("storeCode", newCode), 401);
        call(ups, put("/api/provider/deliveries/couriers/" + id + "/active"), Map.of("active", true), 200);
        kiosk("/courier/verify", Map.of("storeCode", newCode), 200);

        // A company that is no longer an approved delivery provider cannot have its couriers store packages.
        ups.status = Account.Status.REJECTED;
        accounts.saveAndFlush(ups);
        kiosk("/courier/verify", Map.of("storeCode", newCode), 401);

        JsonNode independent = call(manager, post("/api/manager/couriers"), Map.of("name", "Jordan Runner"), 201);
        assertThat(independent.get("courier").get("companyId").isNull()).isTrue();
        assertThat(independent.get("courier").get("manageable").asBoolean()).isTrue();
        long independentId = independent.get("courier").get("id").asLong();
        call(fedex, put("/api/provider/deliveries/couriers/" + independentId + "/active"), Map.of("active", false), 404);
        store(independent.get("storeCode").asText(), 201, 201);
        Parcel parcel = parcels.findByResidentIdOrderByStoredAtDesc(resident.id).get(0);
        assertThat(parcel.registeredBy).isNull();
        assertThat(parcel.carrierName).isEqualTo("Independent courier");
        assertThat(parcel.courierName).isEqualTo("Jordan Runner");
    }

    @Test void courierContactDetailsCanBeEditedWithoutChangingTheCode() throws Exception {
        JsonNode issued = call(ups, post("/api/provider/deliveries/couriers"), Map.of("name", "Jordan Lee", "phone", "555-0100"), 201);
        long id = issued.get("courier").get("id").asLong();
        String code = issued.get("storeCode").asText();
        store(code, 201, 201);

        call(fedex, put("/api/provider/deliveries/couriers/" + id), Map.of("name", "Taken Over"), 404);
        call(manager, put("/api/manager/couriers/" + id), Map.of("name", "Taken Over"), 403);
        call(ups, put("/api/provider/deliveries/couriers/" + id), Map.of("name", " "), 400);
        call(ups, put("/api/provider/deliveries/couriers/" + id), Map.of("name", "Shawn", "carrier", "Other Co"), 403);
        JsonNode edited = call(ups, put("/api/provider/deliveries/couriers/" + id), Map.of("name", "  Shawn ", "phone", ""), 200);
        assertThat(edited.get("name").asText()).isEqualTo("Shawn");
        assertThat(edited.get("phone").isNull()).isTrue();
        assertThat(edited.get("companyName").asText()).isEqualTo("UPS");
        assertThat(edited.get("storeCode").asText()).isEqualTo(code);
        assertThat(kiosk("/courier/verify", Map.of("storeCode", code), 200).get("name").asText()).isEqualTo("Shawn");
        assertThat(parcels.findByResidentIdOrderByStoredAtDesc(resident.id).get(0).courierName).isEqualTo("Jordan Lee");

        JsonNode independent = call(manager, post("/api/manager/couriers"), Map.of("name", "Walk In", "carrier", "Local Co"), 201);
        long independentId = independent.get("courier").get("id").asLong();
        JsonNode renamed = call(manager, put("/api/manager/couriers/" + independentId), Map.of("name", "Walk In", "phone", "555-0199", "carrier", "City Express"), 200);
        assertThat(renamed.get("companyName").asText()).isEqualTo("City Express");
        assertThat(renamed.get("phone").asText()).isEqualTo("555-0199");
        assertThat(call(manager, put("/api/manager/couriers/" + independentId), Map.of("name", "Walk In"), 200).get("companyName").isNull()).isTrue();
    }

    @Autowired CourierRepository couriers;

    @Test void managerIssuesTemporaryCodesForWalkInCouriersThatExpire() throws Exception {
        call(ups, post("/api/provider/deliveries/couriers"), Map.of("name", "Self Issued", "temporary", true), 403);
        call(ups, post("/api/provider/deliveries/couriers"), Map.of("name", "Self Issued", "carrier", "Other"), 403);

        JsonNode issued = call(manager, post("/api/manager/couriers"), Map.of("name", "Sam Walk-in", "carrier", "Amazon", "temporary", true), 201);
        JsonNode courier = issued.get("courier");
        assertThat(courier.get("companyId").isNull()).isTrue();
        assertThat(courier.get("companyName").asText()).isEqualTo("Amazon");
        java.time.Instant expires = java.time.Instant.parse(courier.get("expiresAt").asText());
        assertThat(expires).isAfter(java.time.Instant.now()).isBefore(java.time.Instant.now().plus(java.time.Duration.ofHours(25)));

        String code = issued.get("storeCode").asText();
        assertThat(kiosk("/courier/verify", Map.of("storeCode", code), 200).get("companyName").asText()).isEqualTo("Amazon");
        store(code, 201, 201);
        Parcel parcel = parcels.findByResidentIdOrderByStoredAtDesc(resident.id).get(0);
        assertThat(parcel.carrierName).isEqualTo("Amazon");
        assertThat(parcel.courierName).isEqualTo("Sam Walk-in");
        assertThat(parcel.registeredBy).isNull();

        Courier stored = couriers.findById(courier.get("id").asLong()).orElseThrow();
        stored.expiresAt = java.time.Instant.now().minusSeconds(1);
        couriers.saveAndFlush(stored);
        kiosk("/courier/verify", Map.of("storeCode", code), 401);

        JsonNode reissued = call(manager, post("/api/manager/couriers/" + stored.id + "/store-code"), null, 200);
        assertThat(java.time.Instant.parse(reissued.get("courier").get("expiresAt").asText())).isAfter(java.time.Instant.now());
        kiosk("/courier/verify", Map.of("storeCode", reissued.get("storeCode").asText()), 200);

        JsonNode permanent = call(manager, post("/api/manager/couriers"), Map.of("name", "Jordan Runner"), 201);
        assertThat(permanent.get("courier").get("expiresAt").isNull()).isTrue();
    }

    @Test void codesOnlyWorkInTheCouriersCommunity() throws Exception {
        Account foreignCompany = account("Other-" + UUID.randomUUID(), Account.Role.PROVIDER, Account.ProviderType.DELIVERY, "DHL");
        String foreignCode = call(foreignCompany, post("/api/provider/deliveries/couriers"), Map.of("name", "Far Away"), 201).get("storeCode").asText();
        kiosk("/courier/verify", Map.of("storeCode", foreignCode), 401);
        store(foreignCode, 401, 0);
        Map<String, Object> missingCode = new HashMap<>(Map.of("room", "101"));
        kiosk("/courier/residents", missingCode, 400);
        mvc.perform(post("/api/locker-panel/" + locker.id + "/courier/verify").contentType("application/json")
                .content("{\"storeCode\":\"12345678\"}")).andExpect(status().isForbidden()); // CSRF still required
    }
}
