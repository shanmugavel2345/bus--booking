package com.shank.busbooking;

import com.shank.busbooking.repository.BusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class BusApiTests {
    @Autowired private MockMvc mvc;
    @Autowired private BusRepository repository;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void preservesCrudAndExposesRestfulAliases() throws Exception {
        String request = """
                {"busName":"Express","busNumber":"TN01","source":"Chennai","destination":"Madurai",
                 "totalSeates":40,"fare":123.45}
                """;
        mvc.perform(post("/api/buses").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.fare").value(123.45));
        Long id = repository.findAll().get(0).getId();
        mvc.perform(get("/api/buses/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSeates").value(40));
        mvc.perform(get("/api/buses")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/buses/getAllBus")).andExpect(status().isOk());
        mvc.perform(put("/api/buses/{id}", id).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/buses/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/buses/{id}", id)).andExpect(status().isNotFound());
    }

    @Test
    void validatesRequestAndPublishesSwagger() throws Exception {
        mvc.perform(post("/api/buses/createBus").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}