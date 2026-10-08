import {portalHeaders} from './api';
import React, { useEffect, useState } from "react";
import ReservationForm from "./ReservationForm";
import { EnglishDate } from "./EnglishInputs";

const TIME_ZONE = "America/Los_Angeles";

const statusLabels = {
  AVAILABLE: "Available",
  BOOKED: "Booked",
  CLOSED: "Closed",
  PAST: "Past",
};

function getPacificToday() {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: TIME_ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date());

  const values = Object.fromEntries(
    parts.map(({ type, value }) => [type, value]),
  );

  return `${values.year}-${values.month}-${values.day}`;
}

function addCalendarDays(date, days) {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

function formatTime(instant) {
  return new Intl.DateTimeFormat("en-US", {
    timeZone: TIME_ZONE,
    hour: "numeric",
    minute: "2-digit",
    timeZoneName: "short",
  }).format(new Date(instant));
}

function SlotList({ amenity, date, refreshVersion, user, onReservationsChanged }) {
  const [slots, setSlots] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [retry, setRetry] = useState(0);
  const [selectedStart, setSelectedStart] = useState(null);
  const [notice, setNotice] = useState("");

  useEffect(() => {
    const controller = new AbortController();

    async function loadSlots() {
      setLoading(true);
      setError("");
      setSelectedStart(null);

      try {
        const query = new URLSearchParams({
          date,
        });

        const response = await fetch(
          `/api/resident/amenities/${amenity.id}/slots?${query}`,
          { headers: portalHeaders(), signal: controller.signal },
        );

        if (!response.ok) {
          const body = await response.json().catch(() => ({}));
          throw new Error(body.message || "Unable to load time slots.");
        }

        const data = await response.json();

        if (!controller.signal.aborted) {
          setSlots(data);
        }
      } catch (err) {
        if (!controller.signal.aborted) {
          setError(err.message || "Unable to connect to the server.");
        }
      } finally {
        if (!controller.signal.aborted) {
          setLoading(false);
        }
      }
    }

    loadSlots();
    return () => controller.abort();
  }, [amenity.id, amenity.community, date, retry, refreshVersion]);

  if (loading) {
    return <p role="status">Loading time slots…</p>;
  }

  if (error) {
    return (
      <div className="message error" role="alert">
        <p>{error}</p>
        <button onClick={() => setRetry((value) => value + 1)}>
          Try again
        </button>
      </div>
    );
  }

  if (slots.length === 0) {
    return <p>No time slots are scheduled for this date.</p>;
  }

  return (
    <>
      {notice && (
        <p className="reservation-notice" role="status">
          {notice}
        </p>
      )}
      {!slots.some((slot) => slot.status === "AVAILABLE") && (
        <p>No available slots remain for this date. Try another date.</p>
      )}

      <div className="slot-grid">
        {slots.map((slot) => (
          <button
            key={slot.startAt}
            className="slot-button"
            disabled={slot.status !== "AVAILABLE"}
            aria-pressed={selectedStart === slot.startAt}
            onClick={() => setSelectedStart(slot.startAt)}
          >
            <span>
              {formatTime(slot.startAt)} – {formatTime(slot.endAt)}
            </span>
            <small>
              {statusLabels[slot.status] || slot.status}
              {slot.status === "AVAILABLE" && amenity.capacity > 1 && ` · ${slot.remaining} of ${amenity.capacity} left`}
            </small>
          </button>
        ))}
      </div>

      {selectedStart && (
        <ReservationForm
          key={selectedStart}
          amenity={amenity}
          user={user}
          startAt={selectedStart}
          onBooked={(reservation) => {
            onReservationsChanged();
            setNotice(
              `Reservation #${reservation.id} created successfully. Status: ${reservation.status}.`,
            );

            setSelectedStart(null);
            setRetry((value) => value + 1);
          }}
          onConflict={(message) => {
            setNotice(
              message || "This slot is no longer available. Please select another time.",
            );

            setSelectedStart(null);
            setRetry((value) => value + 1);
          }}
        />
      )}
    </>
  );
}

export default function AmenitySlots({ amenity, onClose, refreshVersion, user, onReservationsChanged }) {
  const today = getPacificToday();
  const [date, setDate] = useState(getPacificToday);
  const lastDate = addCalendarDays(today, amenity.maxAdvanceDays);
  const validDate = date && date >= today && date <= lastDate;

  return (
    <section className="card booking-panel" aria-labelledby="slots-heading">
      <div className="panel-heading">
        <h2 id="slots-heading">{amenity.name} — Time slots</h2>
        <button onClick={onClose}>Close</button>
      </div>

      <p>
        All dates and times use Pacific Time. Up to {amenity.maxSlotsPerDay}{" "}
        slots per household per day.
      </p>

      <EnglishDate
        className="date-field"
        label="Reservation date"
        value={date}
        min={today}
        max={lastDate}
        onChange={setDate}
      />

      {validDate ? (
        <SlotList
          key={date}
          amenity={amenity}
          date={date}
          refreshVersion={refreshVersion}
          user={user}
          onReservationsChanged={onReservationsChanged}
        />
      ) : (
        <p>
          Please select a date between {today} and {lastDate}.
        </p>
      )}
    </section>
  );
}
