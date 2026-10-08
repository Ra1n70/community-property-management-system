package com.cpms.community.search;

import com.cpms.community.Account;
import com.cpms.community.AccountService;
import com.cpms.community.amenity.entity.Amenity;
import com.cpms.community.announcement.model.Announcement;
import com.cpms.community.discussion.entity.DiscussionEntity;
import com.cpms.community.locker.entity.Parcel;
import com.cpms.community.locker.enums.ParcelStatus;
import com.cpms.community.maintenance.MaintenanceTicket;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Community search runs in the database: each type is filtered, counted and paged by its own query,
 * so a search never loads every record. Every word must appear in one of the type's fields
 * (or match its status); results from the visible types are merged by relevance or date.
 */
@Service
@Transactional(readOnly = true)
public class SearchService {
    public record Result(Long id, String type, String title, String content, String status, Instant updatedAt) {}
    public record Page(List<Result> results, int page, int size, long total, int totalPages, Map<String, Long> counts) {}

    public static final List<String> TYPES = List.of("AMENITY", "PACKAGE", "MAINTENANCE", "ANNOUNCEMENT", "DISCUSSION");
    private static final int MAX_WORDS = 8, MAX_CONTENT = 2000;

    private final EntityManager entities;
    private final AccountService accounts;

    public SearchService(EntityManager entities, AccountService accounts) {
        this.entities = entities;
        this.accounts = accounts;
    }

    /** JPQL pieces built for one query, with numbered parameters. */
    private static final class Jpql {
        final List<String> where = new ArrayList<>();
        final Map<String, Object> params = new HashMap<>();
        String param(Object value) { String name = "p" + params.size(); params.put(name, value); return ":" + name; }
        <T> TypedQuery<T> bind(TypedQuery<T> query) { params.forEach(query::setParameter); return query; }
        String clause() { return where.isEmpty() ? "" : " where " + String.join(" and ", where); }
    }

    /**
     * One searchable type. root/joins are JPQL; scope adds the viewer's visibility rules; fields are matched by
     * every word; titleFields rank "best match" results; status maps a word to an extra condition (or null).
     */
    private record Source(String type, String root, String joins, String fetchJoins, String updated,
                          java.util.function.BiConsumer<Jpql, Account> scope, List<String> fields, List<String> titleFields,
                          java.util.function.BiFunction<Jpql, String, String> status, Function<Object, Result> map) {
        String count(Jpql q) { return "select count(x) from " + root + joins + q.clause(); }
        String rows(Jpql q) { return "select x from " + root + fetchJoins + q.clause(); }
    }

    private static String text(Object... values) {
        String joined = Arrays.stream(values).filter(Objects::nonNull).map(Object::toString).filter(s -> !s.isBlank())
                .collect(Collectors.joining(" · "));
        return joined.length() > MAX_CONTENT ? joined.substring(0, MAX_CONTENT) + "…" : joined;
    }

    private static final java.time.format.DateTimeFormatter WHEN = java.time.format.DateTimeFormatter
            .ofPattern("MMM d, yyyy h:mm a", Locale.US).withZone(java.time.ZoneId.of("America/Los_Angeles"));
    private static String when(Instant time) { return time == null ? null : WHEN.format(time); }

    /** Enum values whose readable name contains the word, e.g. "picked" -> PICKED_UP. */
    private static <E extends Enum<E>> String statusIn(Jpql q, String word, E[] values) {
        List<E> hits = Arrays.stream(values).filter(v -> v.name().replace('_', ' ').toLowerCase(Locale.ROOT).contains(word)).toList();
        return hits.isEmpty() ? null : "x.status in " + q.param(hits);
    }

    private List<Source> sources(Account a) {
        boolean manager = a.role == Account.Role.MANAGER;
        boolean maintenanceProvider = a.role == Account.Role.PROVIDER && a.providerType == Account.ProviderType.MAINTENANCE;
        List<Source> list = new ArrayList<>();
        if (a.role != Account.Role.PROVIDER) {
            list.add(new Source("ANNOUNCEMENT", "Announcement x", "", "", "x.publishedAt",
                    (q, me) -> q.where.add("x.community = " + q.param(me.community)),
                    List.of("x.title", "x.author", "x.content"), List.of("x.title"),
                    (q, w) -> "published".contains(w) ? "1 = 1" : null,
                    o -> { Announcement t = (Announcement) o; return new Result(t.getId(), "ANNOUNCEMENT", t.getTitle(), text(t.getAuthor(), t.getContent()), "PUBLISHED", t.getPublishedAt()); }));
            list.add(new Source("DISCUSSION", "DiscussionEntity x", "", "", "x.updatedAt",
                    (q, me) -> { q.where.add("x.community = " + q.param(me.community)); q.where.add("x.deleted = false"); },
                    List.of("x.title", "x.authorName", "x.category", "x.content"), List.of("x.title"),
                    (q, w) -> "pinned".contains(w) ? "x.pinned = true" : "open".contains(w) ? "x.pinned = false" : null,
                    o -> { DiscussionEntity t = (DiscussionEntity) o; return new Result(t.id, "DISCUSSION", t.title, text(t.authorName, t.category, t.content), t.pinned ? "PINNED" : "OPEN", t.updatedAt); }));
            list.add(new Source("AMENITY", "Amenity x", "", "", "x.updatedAt",
                    (q, me) -> q.where.add("x.community = " + q.param(me.community)),
                    List.of("x.name", "x.type", "x.location", "x.description"), List.of("x.name"),
                    (q, w) -> "available".contains(w) ? "1 = 1" : null,
                    o -> { Amenity t = (Amenity) o; return new Result(t.id, "AMENITY", t.name, text(t.type, t.location, t.description, "Capacity: " + t.capacity), "AVAILABLE", t.updatedAt); }));
            List<String> parcelFields = manager
                    ? List.of("x.carrierName", "x.trackingNumber", "x.courierName", "r.name", "r.room", "l.location", "c.cellNumber")
                    : List.of("x.carrierName", "x.trackingNumber", "l.location", "c.cellNumber");
            list.add(new Source("PACKAGE", "Parcel x", " join x.resident r join x.cell c join c.locker l",
                    " join fetch x.resident r join fetch x.cell c join fetch c.locker l", "x.storedAt",
                    (q, me) -> q.where.add(manager ? "x.community = " + q.param(me.community) : "r.id = " + q.param(me.id)),
                    parcelFields, List.of("x.carrierName"),
                    (q, w) -> statusIn(q, w, ParcelStatus.values()),
                    o -> { Parcel t = (Parcel) o; return new Result(t.id, "PACKAGE", t.carrierName + " package",
                            manager ? text(t.resident.name, "Room " + t.resident.room, t.cell.locker.location, "Cell " + t.cell.cellNumber, t.courierName == null ? null : "Courier " + t.courierName, "Expires " + when(t.expiresAt))
                                    : text(t.trackingNumber, t.cell.locker.location, "Cell " + t.cell.cellNumber, "Expires " + when(t.expiresAt)),
                            t.status.name(), t.storedAt); }));
        }
        if (a.role != Account.Role.PROVIDER || maintenanceProvider) {
            list.add(new Source("MAINTENANCE", "MaintenanceTicket x", "", "", "x.updatedAt",
                    (q, me) -> {
                        q.where.add("x.community = " + q.param(me.community));
                        if (me.role == Account.Role.RESIDENT) q.where.add("x.residentId = " + q.param(me.id));
                        if (me.role == Account.Role.PROVIDER) q.where.add("x.assigneeId = " + q.param(me.id));
                    },
                    List.of("x.category", "x.location", "x.room", "x.residentName", "x.description", "x.assigneeName", "x.result", "x.rejectionReason"),
                    List.of("x.category", "x.location"),
                    (q, w) -> statusIn(q, w, MaintenanceTicket.Status.values()),
                    o -> { MaintenanceTicket t = (MaintenanceTicket) o; return new Result(t.id, "MAINTENANCE", text(t.category, t.location),
                            text("Room " + t.room, t.description, t.assigneeName, t.result, t.rejectionReason), t.status.name(), t.updatedAt); }));
        }
        return list;
    }

    static List<String> words(String query) {
        return Arrays.stream(query.strip().toLowerCase(Locale.ROOT).split("\\s+")).filter(w -> !w.isEmpty()).distinct().limit(MAX_WORDS).toList();
    }

    private static String like(String word) {
        return "%" + word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private Jpql filtered(Source s, Account a, List<String> words) {
        Jpql q = new Jpql();
        s.scope().accept(q, a);
        for (String w : words) {
            String pattern = q.param(like(w));
            List<String> any = new ArrayList<>(s.fields().stream().map(f -> "lower(" + f + ") like " + pattern + " escape '\\'").toList());
            String status = s.status().apply(q, w);
            if (status != null) any.add(status);
            q.where.add("(" + String.join(" or ", any) + ")");
        }
        return q;
    }

    /** Number of words found in the title fields (used by "Best match"). */
    private String score(Source s, Jpql q, List<String> words) {
        return "(" + words.stream().map(w -> {
            String pattern = q.param(like(w));
            return "case when " + s.titleFields().stream().map(f -> "lower(" + f + ") like " + pattern + " escape '\\'")
                    .collect(Collectors.joining(" or ")) + " then 1 else 0 end";
        }).collect(Collectors.joining(" + ")) + ")";
    }

    private Account viewer(String email) {
        Account a = accounts.current(email);
        if (a.status != Account.Status.APPROVED) throw AccountService.fail(HttpStatus.FORBIDDEN, "An approved account is required.");
        return a;
    }

    /** Sort keys of one row; rows are loaded only for the requested page. */
    private record Key(Source source, Long id, Instant updated, int score) {}

    public Page search(String email, String query, Set<String> types, boolean relevance, int page, int size) {
        Account a = viewer(email);
        List<String> words = words(query);
        boolean ranked = relevance && !words.isEmpty();
        Map<String, Long> counts = new LinkedHashMap<>();
        List<Source> selected = new ArrayList<>();
        for (Source s : sources(a)) {
            Jpql q = filtered(s, a, words);
            counts.put(s.type(), q.bind(entities.createQuery(s.count(q), Long.class)).getSingleResult());
            if (types.contains(s.type())) selected.add(s);
        }
        long total = selected.stream().mapToLong(s -> counts.get(s.type())).sum();
        long offset = (long) page * size;
        int pages = (int) ((total + size - 1) / size);
        if (offset >= total) return new Page(List.of(), page, size, total, pages, counts);
        int needed = (int) Math.min(offset + size, total);
        // Each type returns the sort keys of its best `needed` rows in the final order; merging them gives the page.
        Comparator<Key> byDate = Comparator.comparing(Key::updated, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(k -> k.source().type()).thenComparing(Key::id, Comparator.reverseOrder());
        Comparator<Key> order = ranked ? Comparator.comparingInt(Key::score).reversed().thenComparing(byDate) : byDate;
        List<Key> keys = selected.stream().flatMap(s -> {
            Jpql q = filtered(s, a, words);
            String score = ranked ? score(s, q, words) : null;
            String jpql = "select x.id, " + s.updated() + (ranked ? ", " + score : "") + " from " + s.root() + s.joins() + q.clause()
                    + " order by " + (ranked ? score + " desc, " : "") + s.updated() + " desc, x.id desc";
            return q.bind(entities.createQuery(jpql, Object[].class)).setMaxResults(needed).getResultList().stream()
                    .map(row -> new Key(s, (Long) row[0], (Instant) row[1], ranked ? ((Number) row[2]).intValue() : 0));
        }).sorted(order).toList();
        List<Key> pageKeys = keys.subList((int) offset, Math.min(keys.size(), needed));
        Map<String, Map<Long, Result>> loaded = new HashMap<>();
        pageKeys.stream().collect(Collectors.groupingBy(Key::source, Collectors.mapping(Key::id, Collectors.toList())))
                .forEach((s, ids) -> {
                    Jpql q = new Jpql();
                    q.where.add("x.id in " + q.param(ids));
                    Map<Long, Result> byId = new HashMap<>();
                    q.bind(entities.createQuery(s.rows(q), Object.class)).getResultList().forEach(row -> {
                        Result r = s.map().apply(row);
                        byId.put(r.id(), r);
                    });
                    loaded.put(s.type(), byId);
                });
        List<Result> results = pageKeys.stream().map(k -> loaded.get(k.source().type()).get(k.id())).filter(Objects::nonNull).toList();
        return new Page(results, page, size, total, pages, counts);
    }

    public Result detail(String email, String type, Long id) {
        Account a = viewer(email);
        Source s = sources(a).stream().filter(x -> x.type().equals(type)).findFirst()
                .orElseThrow(() -> AccountService.fail(HttpStatus.NOT_FOUND, "Search result is no longer available."));
        Jpql q = filtered(s, a, List.of());
        q.where.add("x.id = " + q.param(id));
        return q.bind(entities.createQuery(s.rows(q), Object.class)).setMaxResults(1).getResultList().stream()
                .findFirst().map(s.map())
                .orElseThrow(() -> AccountService.fail(HttpStatus.NOT_FOUND, "Search result is no longer available."));
    }

    static Set<String> types(String type) {
        if (type == null || type.isBlank()) return new LinkedHashSet<>(TYPES);
        Set<String> types = new LinkedHashSet<>();
        for (String item : type.split(",", -1)) {
            String value = item.trim().toUpperCase(Locale.ROOT);
            if (!TYPES.contains(value)) throw AccountService.fail(HttpStatus.BAD_REQUEST, "Unknown search type.");
            types.add(value);
        }
        return types;
    }

}
