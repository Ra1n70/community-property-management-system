import React, { useEffect, useRef, useState } from "react";
import { api } from "./api";

const TWO_HOURS = 2 * 60 * 60 * 1000;

export default function CancelReservation({ reservation, onCancelled }) {
  const [now, setNow] = useState(Date.now);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [uncertain, setUncertain] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const inFlight = useRef(false);

  // 页面长时间打开时，定期更新取消资格。
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 30000);
    return () => clearInterval(timer);
  }, []);

  const beforeDeadline =
    new Date(reservation.startAt).getTime() - now > TWO_HOURS;

  async function cancelReservation() {
    if (inFlight.current || uncertain) return;

    // 点击时再检查一次，避免只依赖上一次渲染的时间。
    if (new Date(reservation.startAt).getTime() - Date.now() <= TWO_HOURS) {
      setNow(Date.now());
      setError("This reservation is now within the cancellation deadline.");
      return;
    }

    setConfirming(false);
    inFlight.current = true;
    setBusy(true);
    setError("");

    try {
      const result = await api(`/resident/reservations/${reservation.id}/cancel`, {method:'POST'});
      onCancelled(result);
    } catch (err) {
      if(err.status && err.status < 500){setError(err.message);return;}
      setUncertain(true);
      setError(
        "We could not confirm the result. Refresh records to check the latest status before trying again.",
      );
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  if (reservation.status !== "UPCOMING") return null;

  return (
    <div>
      {/* Inline confirmation instead of window.confirm, so the buttons stay in English in every browser. */}
      {confirming ? (
        <div className="reservation-cancel-panel" role="group" aria-label="Confirm cancellation">
          <p>Cancel reservation #{reservation.id} for {reservation.amenityName}?</p>
          <div className="actions">
            <button type="button" className="primary" disabled={busy} onClick={cancelReservation}>
              {busy ? "Cancelling…" : "Yes, cancel it"}
            </button>
            <button type="button" disabled={busy} onClick={() => setConfirming(false)}>Keep reservation</button>
          </div>
        </div>
      ) : (
        <button
          type="button"
          disabled={busy || uncertain || !beforeDeadline}
          onClick={() => { setError(""); setConfirming(true); }}
        >
          {busy ? "Cancelling…" : "Cancel reservation"}
        </button>
      )}

      {!beforeDeadline && (
        <p className="hint">
          Cancellation is unavailable within two hours of the start time.
        </p>
      )}

      {error && (
        <p className="message error" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
