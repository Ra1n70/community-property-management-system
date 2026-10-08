package com.cpms.community.locker;

import com.cpms.community.locker.controller.LockerPanelApi;
import com.cpms.community.locker.entity.Courier;
import com.cpms.community.locker.entity.Locker;
import com.cpms.community.locker.repository.LockerRepository;
import com.cpms.community.locker.service.CarrierIntakeService;
import com.cpms.community.locker.service.CarrierRecipientService;
import com.cpms.community.locker.service.CourierService;
import com.cpms.community.locker.service.PickupService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LockerPanelApiTest {

    @Test
    void carrierConfirmDoesNotExposePickupCode() throws Exception {
        CarrierIntakeService carrier = mock(CarrierIntakeService.class);
        when(carrier.confirm(7L, "temporary-session", 99L))
                .thenReturn(new CarrierIntakeService.ConfirmResult(42L, "A01", "Main Lobby", "123456"));

        Locker locker = new Locker();
        locker.id = 7L;
        locker.community = "Test";
        LockerRepository lockers = mock(LockerRepository.class);
        when(lockers.findById(7L)).thenReturn(Optional.of(locker));

        Courier courier = new Courier();
        courier.id = 99L;
        CourierService couriers = mock(CourierService.class);
        when(couriers.authenticate(eq("12345678"), same(locker), anyString())).thenReturn(courier);

        LockerPanelApi controller = new LockerPanelApi(mock(PickupService.class), mock(CarrierRecipientService.class),
                carrier, couriers, lockers);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/api/locker-panel/7/intake/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeCode":"12345678","sessionToken":"temporary-session"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("STORED"))
                .andExpect(jsonPath("$.cellNumber").value("A01"))
                .andExpect(jsonPath("$.pickupCode").doesNotExist())
                .andExpect(content().string(not(containsString("123456"))));
    }
}
