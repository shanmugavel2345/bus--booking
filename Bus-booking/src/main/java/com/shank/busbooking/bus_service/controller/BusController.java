package com.shank.busbooking.bus_service.controller;

import com.shank.busbooking.dto.BusRequest;
import com.shank.busbooking.dto.BusResponse;
import com.shank.busbooking.service.BusService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// [AI GENERATED] Added: needed for List<BusResponse> return type in getAllBuses()
import java.util.List;

@RestController
@RequestMapping("/api/buses")
public class BusController {

    private final BusService busService;

    public BusController(BusService busService){
        this.busService = busService;
    }
    @PostMapping({"", "/createBus"})
    public ResponseEntity<BusResponse> createBus(
            @Valid @RequestBody BusRequest request){
        BusResponse response = busService.createBus(request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    // ===== [YOUR ORIGINAL CODE] =====
    // Bug: "/getAllBus" has no {id} in the URL, so @PathVariable id can't be filled -> 500 error.
    // It also called getBusById instead of getAllBuses, so "get all" was never wired up.
    //
    // @GetMapping("/getAllBus")
    // public ResponseEntity<BusResponse> getBusById(
    //         @PathVariable Long id){
    //     BusResponse response = busService.getBusById(id);
    //     return ResponseEntity.ok(response);
    // }
    // ===== [END YOUR ORIGINAL CODE] =====

    // ===== [AI GENERATED] =====
    // Fix 1: no path variable needed; returns a List because there can be many buses.
    @GetMapping({"", "/getAllBus"})
    public ResponseEntity<List<BusResponse>> getAllBuses(){
        return ResponseEntity.ok(busService.getAllBuses());
    }

    // Fix 2: separate endpoint for one bus; {id} in the URL maps to @PathVariable id.
    @GetMapping("/{id}")
    public ResponseEntity<BusResponse> getBusById(@PathVariable Long id){
        return ResponseEntity.ok(busService.getBusById(id));
    }
    // ===== [END AI GENERATED] =====


    @PutMapping("/{id}")
    public ResponseEntity<BusResponse> updateBus(
            @PathVariable Long id,
            @Valid @RequestBody BusRequest request){
        BusResponse response = busService.updateBus(id, request);

        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBus(
            @PathVariable Long id) {
        busService.deleteBus(id);

        return ResponseEntity.noContent().build();
    }


}
