package com.cpms.community.locker.controller;

import com.cpms.community.locker.entity.Locker;
import com.cpms.community.locker.entity.LockerCell;
import com.cpms.community.locker.enums.CellSize;
import com.cpms.community.locker.enums.CellStatus;
import com.cpms.community.locker.enums.LockerStatus;
import com.cpms.community.locker.service.LockerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/manager/lockers")
public class LockerApi {

    public record CreateLocker(
            @Size(max = 50)
            String lockerNumber,

            @NotBlank
            @Size(max = 200)
            String location
    ) {}

    public record CreateCell(
            @NotBlank
            @Size(max = 30)
            String cellNumber,

            @NotNull
            CellSize size
    ) {}

    public record LockerView(
            Long id,
            String lockerNumber,
            String location,
            LockerStatus status,
            Instant createdAt
    ) {
        public static LockerView of(Locker locker) {
            return new LockerView(
                    locker.id,
                    locker.lockerNumber,
                    locker.location,
                    locker.status,
                    locker.createdAt
            );
        }
    }

    public record CellView(
            Long id,
            Long lockerId,
            String cellNumber,
            CellSize size,
            CellStatus status,
            Instant createdAt
    ) {
        public static CellView of(LockerCell cell, Long lockerId) {
            return new CellView(
                    cell.id,
                    lockerId,
                    cell.cellNumber,
                    cell.size,
                    cell.status,
                    cell.createdAt
            );
        }
    }

    private final LockerService service;

    public LockerApi(LockerService service) {
        this.service = service;
    }

    @GetMapping
    public List<LockerView> listLockers(
            Authentication auth
    ) {
        return service.listLockers(auth.getName())
                .stream()
                .map(LockerView::of)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LockerView createLocker(
            Authentication auth,
            @Valid @RequestBody CreateLocker input
    ) {
        return LockerView.of(
                service.createLocker(
                        auth.getName(),
                        input.lockerNumber(),
                        input.location()
                )
        );
    }

    @GetMapping("/{lockerId}/cells")
    public List<CellView> listCells(
            Authentication auth,
            @PathVariable Long lockerId
    ) {
        return service.listCells(
                        auth.getName(),
                        lockerId
                )
                .stream()
                .map(cell -> CellView.of(cell, lockerId))
                .toList();
    }

    @PostMapping("/{lockerId}/cells")
    @ResponseStatus(HttpStatus.CREATED)
    public CellView createCell(
            Authentication auth,
            @PathVariable Long lockerId,
            @Valid @RequestBody CreateCell input
    ) {
        return CellView.of(
                service.createCell(
                        auth.getName(),
                        lockerId,
                        input.cellNumber(),
                        input.size()
                ),
                lockerId
        );
    }
    public record UpdateCell(
            CellSize size,
            Boolean disabled
    ) {}
    @PatchMapping("/{lockerId}/cells/{cellId}")
    public CellView updateCell(
            Authentication auth,
            @PathVariable Long lockerId,
            @PathVariable Long cellId,
            @RequestBody UpdateCell input
    ) {
        return CellView.of(
                service.updateCell(
                        auth.getName(),
                        lockerId,
                        cellId,
                        input.size(),
                        input.disabled()
                ),
                lockerId
        );
    }
    public record UpdateLocker(
            String location,
            LockerStatus status
    ) {}

    @PatchMapping("/{lockerId}")
    public LockerView updateLocker(
            Authentication auth,
            @PathVariable Long lockerId,
            @RequestBody UpdateLocker input
    ) {
        return LockerView.of(
                service.updateLocker(
                        auth.getName(),
                        lockerId,
                        input.location(),
                        input.status()
                )
        );
    }
    @GetMapping("/{lockerId}/available-cells")
    public List<CellView> availableCells(
            Authentication auth,
            @PathVariable Long lockerId,
            @RequestParam CellSize size
    ) {
        return service.availableCells(auth.getName(), lockerId, size)
                .stream()
                .map(cell -> CellView.of(cell, lockerId))
                .toList();
    }
    @DeleteMapping("/{lockerId}/cells/{cellId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCell(Authentication auth, @PathVariable Long lockerId, @PathVariable Long cellId) {
        service.deleteCell(auth.getName(), lockerId, cellId);
    }
    @DeleteMapping("/{lockerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLocker(Authentication auth, @PathVariable Long lockerId) {
        service.deleteLocker(auth.getName(), lockerId);
    }
}
