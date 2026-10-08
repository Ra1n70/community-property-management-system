package com.cpms.community.report;

import com.cpms.community.Account;
import com.cpms.community.AccountService;
import com.cpms.community.amenity.entity.Amenity;
import com.cpms.community.amenity.service.SlotGenerator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/**
 * Management reports for the manager home page: activity between two dates (Pacific time, inclusive)
 * plus the current state of open work, as JSON or a CSV download. Every number is limited to the
 * manager's community.
 */
@RestController
@RequestMapping("/api/manager/statistics")
@Transactional(readOnly = true)
public class StatisticsApi {
    public static final ZoneId ZONE = ZoneId.of("America/Los_Angeles");
    public record Metric(String key, String label, Number value, String unit) {}
    public record Section(String key, String title, List<Metric> metrics, List<Metric> breakdown) {}
    public record Report(LocalDate from, LocalDate to, Instant generatedAt, List<Section> sections) {}

    @PersistenceContext private EntityManager entities;
    private final AccountService accountService;
    private final SlotGenerator slots;

    public StatisticsApi(AccountService accountService, SlotGenerator slots) { this.accountService = accountService; this.slots = slots; }

    /** part ÷ whole as a percentage with one decimal place; null ("—") when there is nothing to divide by. */
    static BigDecimal percent(long part, long whole) {
        return whole == 0 ? null : BigDecimal.valueOf(part * 100).divide(BigDecimal.valueOf(whole), 1, java.math.RoundingMode.HALF_UP);
    }

    @GetMapping
    public Report report(Authentication auth, @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        Account a = accountService.current(auth.getName());
        if (a.role != Account.Role.MANAGER || a.status != Account.Status.APPROVED)
            throw AccountService.fail(HttpStatus.FORBIDDEN, "Manager access required.");
        LocalDate today = LocalDate.now(ZONE);
        LocalDate end = to == null ? today : to, start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end)) throw AccountService.fail(HttpStatus.BAD_REQUEST, "The start date must be on or before the end date.");
        if (start.isBefore(end.minusYears(3))) throw AccountService.fail(HttpStatus.BAD_REQUEST, "Choose a range of at most three years.");
        return new Builder(a.community, start, end, today).build();
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> export(Authentication auth, @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        Report r = report(auth, from, to);
        StringBuilder csv = new StringBuilder("Section,Metric,Value,Unit\r\n");
        for (Section s : r.sections()) {
            for (Metric m : s.metrics()) row(csv, s.title(), m);
            for (Metric m : s.breakdown()) row(csv, s.title(), m);
        }
        csv.append(cell("Report")).append(',').append(cell("Period")).append(',').append(cell(r.from() + " to " + r.to())).append(",\r\n");
        String name = "community-report-" + r.from() + "-to-" + r.to() + ".csv";
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .header("Content-Disposition", ContentDisposition.attachment().filename(name).build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                // A byte order mark lets Excel open the UTF-8 file with names intact.
                .body(("﻿" + csv).getBytes(StandardCharsets.UTF_8));
    }

    private static void row(StringBuilder csv, String section, Metric m) {
        csv.append(cell(section)).append(',').append(cell(m.label())).append(',')
                .append(m.value() == null ? "" : m.value() instanceof BigDecimal d ? d.toPlainString() : String.valueOf(m.value())).append(',')
                .append(cell(m.unit() == null ? "" : m.unit())).append("\r\n");
    }

    /** Quotes a CSV cell and stops spreadsheet formulas from running ("=", "+", "-", "@"). */
    private static String cell(String value) {
        String text = value.matches("^[=+\\-@\\t\\r].*") ? "'" + value : value;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private final class Builder {
        final String community; final LocalDate from, to, today; final Instant start, end, now = Instant.now();

        Builder(String community, LocalDate from, LocalDate to, LocalDate today) {
            this.community = community; this.from = from; this.to = to; this.today = today;
            start = from.atStartOfDay(ZONE).toInstant(); end = to.plusDays(1).atStartOfDay(ZONE).toInstant();
        }

        <T> T one(String jpql, Class<T> type, Object... params) {
            TypedQuery<T> q = entities.createQuery(jpql, type).setParameter("community", community);
            for (int i = 0; i < params.length; i += 2) q.setParameter((String) params[i], params[i + 1]);
            return q.getSingleResult();
        }

        long count(String jpql, Object... params) { return one(jpql, Long.class, params); }
        long inRange(String jpql) { return count(jpql, "start", start, "end", end); }
        BigDecimal money(String jpql, Object... params) { BigDecimal v = one(jpql, BigDecimal.class, params); return v == null ? BigDecimal.ZERO.setScale(2) : v.setScale(2); }

        List<Metric> grouped(String jpql, String prefix) {
            return entities.createQuery(jpql, Object[].class).setParameter("community", community)
                    .setParameter("start", start).setParameter("end", end).getResultList().stream()
                    .map(r -> new Metric(prefix + r[0], String.valueOf(r[0]), (Long) r[1], "requests")).toList();
        }

        Report build() {
            List<Section> sections = new ArrayList<>();
            sections.add(new Section("residents", "Residents", List.of(
                    new Metric("approved", "Approved residents now", count("select count(a) from Account a where a.community=:community and a.role=com.cpms.community.Account.Role.RESIDENT and a.status=com.cpms.community.Account.Status.APPROVED"), "residents"),
                    new Metric("pending", "Applications waiting for review now", count("select count(a) from Account a where a.community=:community and a.role=com.cpms.community.Account.Role.RESIDENT and a.status=com.cpms.community.Account.Status.PENDING"), "applications"),
                    new Metric("applied", "New applications", inRange("select count(a) from Account a where a.community=:community and a.role=com.cpms.community.Account.Role.RESIDENT and a.submittedAt>=:start and a.submittedAt<:end"), "applications")), List.of()));

            String finished = "(com.cpms.community.maintenance.MaintenanceTicket.Status.COMPLETED, com.cpms.community.maintenance.MaintenanceTicket.Status.REJECTED, com.cpms.community.maintenance.MaintenanceTicket.Status.UNRESOLVED)";
            // Every period figure counts the requests created in the period, so completed ≤ submitted and the rate is at most 100%.
            long submitted = inRange("select count(t) from MaintenanceTicket t where t.community=:community and t.createdAt>=:start and t.createdAt<:end");
            List<Object[]> durations = entities.createQuery("select t.createdAt, t.completedAt from MaintenanceTicket t where t.community=:community and t.status=com.cpms.community.maintenance.MaintenanceTicket.Status.COMPLETED and t.createdAt>=:start and t.createdAt<:end", Object[].class)
                    .setParameter("community", community).setParameter("start", start).setParameter("end", end).getResultList();
            // No completed requests means no average (null), not an average of zero hours.
            BigDecimal hours = durations.stream().noneMatch(r -> r[1] != null) ? null : BigDecimal.valueOf(durations.stream().filter(r -> r[1] != null)
                    .mapToLong(r -> Duration.between((Instant) r[0], (Instant) r[1]).toMinutes()).average().orElse(0) / 60.0).setScale(1, java.math.RoundingMode.HALF_UP);
            sections.add(new Section("maintenance", "Maintenance", List.of(
                    new Metric("submitted", "Requests submitted", submitted, "requests"),
                    new Metric("completed", "Requests completed", durations.size(), "requests"),
                    new Metric("completionRate", "Completion rate", percent(durations.size(), submitted), "percent"),
                    new Metric("resolutionHours", "Average time to complete", hours, "hours"),
                    new Metric("open", "Open requests now", count("select count(t) from MaintenanceTicket t where t.community=:community and t.status not in " + finished), "requests"),
                    new Metric("urgentOpen", "Urgent open requests now", count("select count(t) from MaintenanceTicket t where t.community=:community and t.priority=com.cpms.community.maintenance.MaintenanceTicket.Priority.URGENT and t.status not in " + finished), "requests")),
                    grouped("select t.category, count(t) from MaintenanceTicket t where t.community=:community and t.createdAt>=:start and t.createdAt<:end group by t.category order by count(t) desc, t.category", "category:")));

            sections.add(new Section("packages", "Packages", List.of(
                    new Metric("received", "Packages received", inRange("select count(p) from Parcel p where p.community=:community and p.storedAt>=:start and p.storedAt<:end"), "packages"),
                    new Metric("pickedUp", "Packages picked up", inRange("select count(p) from Parcel p where p.community=:community and p.pickedUpAt>=:start and p.pickedUpAt<:end"), "packages"),
                    new Metric("waiting", "Waiting for pickup now", count("select count(p) from Parcel p where p.community=:community and p.status=com.cpms.community.locker.enums.ParcelStatus.PENDING_PICKUP"), "packages"),
                    new Metric("expired", "Expired, still in a locker now", count("select count(p) from Parcel p where p.community=:community and p.status=com.cpms.community.locker.enums.ParcelStatus.EXPIRED"), "packages")), List.of()));

            // Reservations count by start time; active + cancelled = total. Grouped by id so two facilities with one name stay apart.
            String cancelledStatus = "com.cpms.community.amenity.enums.ReservationStatus.CANCELLED";
            List<Metric> amenities = entities.createQuery("select a.id, a.name, count(r) from Reservation r, Amenity a where a.id=r.amenityId and r.community=:community and r.startAt>=:start and r.startAt<:end and r.status<>" + cancelledStatus + " group by a.id, a.name order by count(r) desc, a.name, a.id", Object[].class)
                    .setParameter("community", community).setParameter("start", start).setParameter("end", end).getResultList().stream()
                    .map(r -> new Metric("amenity:" + r[0], String.valueOf(r[1]), (Long) r[2], "reservations")).toList();
            long booked = inRange("select count(r) from Reservation r where r.community=:community and r.startAt>=:start and r.startAt<:end");
            long cancelled = inRange("select count(r) from Reservation r where r.community=:community and r.status=" + cancelledStatus + " and r.startAt>=:start and r.startAt<:end");
            // Places = open slots (current weekly hours, minus closures) × households per slot, over the chosen dates.
            long places = entities.createQuery("select a from Amenity a where a.community=:community", Amenity.class)
                    .setParameter("community", community).getResultList().stream()
                    .mapToLong(a -> slots.openSlots(a, from, to) * a.capacity).sum();
            sections.add(new Section("amenities", "Amenities", List.of(
                    new Metric("reservations", "Reservations", booked, "reservations"),
                    new Metric("active", "Active reservations", booked - cancelled, "reservations"),
                    new Metric("cancelled", "Cancelled reservations", cancelled, "reservations"),
                    new Metric("utilization", "Utilization", percent(booked - cancelled, places), "percent"),
                    new Metric("upcoming", "Upcoming reservations now", count("select count(r) from Reservation r where r.community=:community and r.status=com.cpms.community.amenity.enums.ReservationStatus.UPCOMING and r.startAt>=:now", "now", now), "reservations")),
                    amenities));

            sections.add(new Section("payments", "Payments", List.of(
                    new Metric("billed", "Bills created", inRange("select count(b) from Bill b where b.community=:community and b.createdAt>=:start and b.createdAt<:end"), "bills"),
                    new Metric("billedAmount", "Amount billed", money("select sum(b.amount) from Bill b where b.community=:community and b.createdAt>=:start and b.createdAt<:end", "start", start, "end", end), "USD"),
                    // Paid and overdue amounts are for the bills created in the period, by their status now.
                    new Metric("paidAmount", "Amount paid", money("select sum(b.amount) from Bill b where b.community=:community and b.status='PAID' and b.createdAt>=:start and b.createdAt<:end", "start", start, "end", end), "USD"),
                    new Metric("overdueAmount", "Overdue unpaid amount", money("select sum(b.amount) from Bill b where b.community=:community and b.status='UNPAID' and b.dueDate<:today and b.createdAt>=:start and b.createdAt<:end", "start", start, "end", end, "today", today), "USD"),
                    new Metric("outstanding", "Unpaid now", money("select sum(b.amount) from Bill b where b.community=:community and b.status='UNPAID'"), "USD"),
                    new Metric("overdue", "Overdue bills now", count("select count(b) from Bill b where b.community=:community and b.status='UNPAID' and b.dueDate<:today", "today", today), "bills")), List.of()));

            sections.add(new Section("community", "Community activity", List.of(
                    new Metric("announcements", "Announcements published", inRange("select count(n) from Announcement n where n.community=:community and n.publishedAt>=:start and n.publishedAt<:end"), "announcements"),
                    new Metric("posts", "Discussion posts", inRange("select count(d) from DiscussionEntity d where d.community=:community and d.deletedAt is null and d.createdAt>=:start and d.createdAt<:end"), "posts"),
                    new Metric("messages", "Messages from residents", inRange("select count(m) from DirectMessage m where m.conversation.community=:community and m.sender.role=com.cpms.community.Account.Role.RESIDENT and m.createdAt>=:start and m.createdAt<:end"), "messages"),
                    new Metric("polls", "Votes started", inRange("select count(p) from Poll p where p.community=:community and p.createdAt>=:start and p.createdAt<:end"), "votes"),
                    new Metric("ballots", "Ballots cast", inRange("select count(v) from PollVote v, Poll p where p.id=v.pollId and p.community=:community and v.createdAt>=:start and v.createdAt<:end"), "ballots")), List.of()));
            return new Report(from, to, now, sections);
        }
    }
}
