import {portalHeaders} from './api';
import React, { useEffect, useState } from "react";
import CancelReservation from "./CancelReservation";
import { usd } from "./money";

const statusLabels = {
  UPCOMING: "Upcoming",
  COMPLETED: "Completed",
  CANCELLED: "Cancelled",
};

function formatDateTime(value) {
  return new Intl.DateTimeFormat("en-US", {
    timeZone: "America/Los_Angeles",
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value));
}

export default function MyReservations({ refreshVersion, onReservationsChanged }) {
  const [reservations, setReservations] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [refreshCount, setRefreshCount] = useState(0);
  const [notice, setNotice] = useState("");

  useEffect(() => {
    const controller = new AbortController();

    async function loadReservations() {
      setLoading(true);
      setError("");

      try {
        const response = await fetch('/api/resident/reservations', {
          headers: portalHeaders(), signal: controller.signal,
        });

        if (!response.ok) {
          const body = await response.json().catch(() => ({}));
          throw new Error(body.message || "Unable to load reservations.");
        }

        const data = await response.json();

        if (!controller.signal.aborted) {
          setReservations(data);
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

    loadReservations();
    return () => controller.abort();
  }, [refreshVersion, refreshCount]);

  return (
    <section className="card booking-panel" id="my-reservations">
      <div className="panel-heading">
        <h2>My reservations</h2>
        <button
          disabled={loading}
          onClick={() => setRefreshCount((value) => value + 1)}
        >
          {loading ? "Loading…" : "Refresh records"}
        </button>
      </div>

      {loading ? (
        <p role="status">Loading reservations…</p>
      ) : error ? (
        <p className="message error" role="alert">
          {error}
        </p>
      ) : reservations.length === 0 ? (
        <p>No reservations yet. Choose a facility and time slot to get started.</p>
      ) : (
        <div className="reservation-list">
          {reservations.map((reservation) => (
            <article className="reservation-record" key={`${reservation.id}-${refreshCount}-${refreshVersion}`}>
              <div className="panel-heading">
                <h3>
                  #{reservation.id} · {reservation.amenityName}
                </h3>
                <span className={`badge reservation-status-${reservation.status}`}>
                  {statusLabels[reservation.status] || reservation.status}
                </span>
              </div>

              <p>
                {formatDateTime(reservation.startAt)}
                <br />
                to {formatDateTime(reservation.endAt)}
                <br />
                Pacific Time
              </p>

              <p>
                Resident: {reservation.guestName}
                <br />
                Fee:{" "}
                {Number(reservation.fee) === 0 ? "Free" : usd(reservation.fee)}
              </p>

              {reservation.cancelReason && (
                <p>Cancellation reason: {reservation.cancelReason}</p>
              )}
              <CancelReservation
                reservation={reservation}
                onCancelled={(updated) => {
                  setNotice(
                    `Reservation #${updated.id} cancelled successfully.`,
                  );
                  setRefreshCount((value) => value + 1);
                  onReservationsChanged();
                }}
              />
            </article>
          ))}
        </div>
      )}
      {notice && (
        <p className="reservation-notice" role="status">
          {notice}
        </p>
      )}
    </section>
  );
}
