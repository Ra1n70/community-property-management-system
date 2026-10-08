import React, { useEffect, useState } from "react";
import { keepInView } from "./keepInView";
import { api, isCancelled } from "./api";
import Pager from "./Pager";
import { confirmAction } from "./englishUi";
import "./package-management.css";

const statuses = [
  ["", "All statuses"],
  ["PENDING_PICKUP", "Pending pickup"],
  ["PICKED_UP", "Picked up"],
  ["EXPIRED", "Expired"],
  ["RETRIEVED", "Retrieved"],
];

function formatDate(value) {
  return value ? new Date(value).toLocaleString('en-US') : "—";
}

const PAGE_SIZE = 20;

// focus = { id, seq } shows and highlights that package (e.g. from Search) above the list, whatever page it is on.
export default function ManagerParcels({ focus }) {
  const [data, setData] = useState(null);
  const [page, setPage] = useState(0);
  const [lockers, setLockers] = useState([]);
  const [statusFilter, setStatusFilter] = useState("");
  const [lockerId, setLockerId] = useState("");
  const [loading, setLoading] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);
  const [busyId, setBusyId] = useState(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [focused, setFocused] = useState(null);
  const parcels = data?.items || [];

  const params = new URLSearchParams({ page, size: PAGE_SIZE });
  if (statusFilter) params.set("status", statusFilter);
  if (lockerId) params.set("lockerId", lockerId);
  const listQuery = params.toString();
  // Filters apply at once; a newer filter or page cancels the request still running.
  useEffect(() => {
    const request = new AbortController();
    setLoading(true);
    setError("");
    api(`/manager/parcels/page?${listQuery}`, { signal: request.signal })
      .then((found) => {
        if (!found.items.length && found.page > 0 && found.totalPages > 0) {
          setPage(found.totalPages - 1);
          return;
        }
        setData(found);
        setLoading(false);
      })
      .catch((err) => {
        if (isCancelled(err)) return;
        setError(err.message);
        setLoading(false);
      });
    return () => request.abort();
  }, [listQuery, reloadKey]);
  const reload = () => setReloadKey((key) => key + 1);
  const filterBy = (setter) => (event) => {
    setter(event.target.value);
    setPage(0);
  };

  useEffect(() => {
    if (!focus?.id) return;
    setError("");
    api(`/manager/parcels/${focus.id}`)
      .then((parcel) => {
        setFocused(parcel);
        keepInView(() => document.getElementById(`parcel-focus-${focus.id}`));
      })
      .catch((err) => {
        setFocused(null);
        setError(err.status === 404 ? "That package is no longer available." : err.message);
      });
  }, [focus?.seq]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    api("/manager/lockers")
      .then(setLockers)
      .catch((err) => setError(err.message));
  }, []);

  async function performAction(parcel, action) {
    if (
      action === "retrieve" &&
      !(await confirmAction({
        title: "Mark package as retrieved?",
        message: "Have you physically removed this package from the locker?",
        confirmLabel: "Yes, mark retrieved",
      }))
    ) {
      return;
    }

    setBusyId(parcel.id);
    setError("");
    setNotice("");

    try {
      await api(`/manager/parcels/${parcel.id}/${action}`, {
        method: "POST",
      });

      if (action === "regenerate-code") {
        setNotice(
          "New pickup code is available in the resident’s My Packages. The old code is invalid.",
        );
      } else {
        setNotice("Package marked as retrieved. The cell is now available.");
      }

      if (focused?.id === parcel.id) setFocused(await api(`/manager/parcels/${parcel.id}`));
      reload();
    } catch (err) {
      setError(err.message);
    } finally {
      setBusyId(null);
    }
  }

  const record = (parcel, idPrefix) => (
    <article className={`package-record ${focused?.id === parcel.id ? "package-record-focus" : ""}`} id={`${idPrefix}${parcel.id}`} key={`${idPrefix}${parcel.id}`}>
      <div className="section-head"><h3>{parcel.carrierName} <small>#{parcel.id}</small></h3><span className={`badge package-status-${parcel.status}`}>{statuses.find(([value]) => value === parcel.status)?.[1] || parcel.status}</span></div><div className="package-record-details">

      <p>
        Resident: {parcel.residentName} · Room {parcel.room}
      </p>
      <p>
        Locker: {parcel.lockerLocation} · Cell{" "}
        {parcel.cellNumber}
      </p>
      {parcel.courierName && <p>Stored by courier: {parcel.courierName}</p>}
      <p>
        Source: {parcel.intakeSource.replaceAll("_", " ")}
        {parcel.registeredBy
          ? ` · Registered by account #${parcel.registeredBy}`
          : ""}
      </p>
      <p>Stored: {formatDate(parcel.storedAt)}</p>
      <p>Expires: {formatDate(parcel.expiresAt)}</p></div>

      {parcel.status === "PENDING_PICKUP" && (
        <div className="actions">
          <button
            type="button"
            disabled={busyId !== null}
            onClick={() => performAction(parcel, "regenerate-code")}
          >
            Regenerate Code
          </button>
        </div>
      )}

      {parcel.status === "EXPIRED" && (
        <button
          type="button"
          disabled={busyId !== null}
          onClick={() => performAction(parcel, "retrieve")}
        >
          Mark Retrieved
        </button>
      )}
    </article>
  );

  return (
    <section className="card package-card package-directory">
      <span className="eyebrow">PACKAGE MANAGEMENT</span>
      <h2>Packages <span className="count">{data?.total ?? ""}</span></h2>
      <p className="hint">Track deliveries, regenerate pickup codes and manage expired packages.</p>

      {error && (
        <p className="message error" role="alert">
          {error}
        </p>
      )}
      {notice && (
        <p className="message" role="status">
          {notice}
        </p>
      )}

      <div className="package-filters">
        <label>
          Status
          <select
            value={statusFilter}
            onChange={filterBy(setStatusFilter)}
          >
            {statuses.map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>

        <label>
          Locker
          <select
            value={lockerId}
            onChange={filterBy(setLockerId)}
          >
            <option value="">All lockers</option>
            {lockers.map((locker) => (
              <option key={locker.id} value={locker.id}>
                {locker.location}
              </option>
            ))}
          </select>
        </label>
      <button type="button" onClick={reload} disabled={loading}>
        {loading ? "Loading…" : "Refresh"}
      </button></div>

      {!loading && parcels.length === 0 && !error && <div className="package-empty"><strong>No packages found</strong><p>Register a delivery above, or adjust the filters.</p></div>}

      {focused && (
        <div className="package-focus">
          <div className="section-head"><strong>Opened from Search</strong><button type="button" onClick={() => setFocused(null)}>Close</button></div>
          {record(focused, "parcel-focus-")}
        </div>
      )}

      {parcels.map((parcel) => record(parcel, "parcel-"))}
      <Pager data={data} noun="packages" busy={loading} onPage={setPage} />
    </section>
  );
}
