import React, { useEffect, useRef, useState } from "react";
import { keepInView } from "./keepInView";
import { api } from "./api";
import Pager from "./Pager";

const statusLabels = {
  PENDING_PICKUP: "Ready for pickup",
  PICKED_UP: "Picked up",
  EXPIRED: "Expired",
  RETRIEVED: "Retrieved by management",
};

// focus = { id, seq } opens that package's details (e.g. from Search).
export default function ResidentPackages({ focus }) {
  const [data, setData] = useState(null);
  const [page, setPage] = useState(0);
  const parcels = data?.items || [];
  const [detail, setDetail] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const detailRef = useRef(null);

  async function load(nextPage = page) {
    setLoading(true);
    setError("");
    try {
      setData(await api(`/resident/parcels/page?page=${nextPage}&size=20`));
      setPage(nextPage);
      if (detail) setDetail(await api(`/resident/parcels/${detail.parcel.id}`));
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }

  async function openParcel(id) {
    setError("");
    try {
      setDetail(await api(`/resident/parcels/${id}`));
      keepInView(() => detailRef.current);
    } catch (err) {
      setError(err.message);
    }
  }
  useEffect(() => {
    if (focus?.id) openParcel(focus.id);
  }, [focus?.seq]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    load();
  }, []);

  return (
    <section className="card">
      <div className="section-head">
        <h2>My Packages</h2>
        <button onClick={() => load()}>Refresh</button>
      </div>

      {error && (
        <p role="alert">
          {error} <button onClick={() => load()}>Retry</button>
        </p>
      )}

      {loading ? (
        <p>Loading packages…</p>
      ) : parcels.length === 0 ? (
        <p className="empty">You have no packages yet.</p>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Carrier</th>
                <th>Locker Location</th>
                <th>Cell</th>
                <th>Status</th>
                <th>Expires</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {parcels.map((parcel) => (
                <tr key={parcel.id}>
                  <td>{parcel.carrierName}</td>
                  <td>{parcel.lockerLocation}</td>
                  <td>{parcel.cellNumber}</td>
                  <td>{statusLabels[parcel.status] ?? parcel.status}</td>
                  <td>{new Date(parcel.expiresAt).toLocaleString('en-US')}</td>
                  <td>
                    <button onClick={() => openParcel(parcel.id)}>
                      View details
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <Pager data={data} noun="packages" busy={loading} onPage={load} />

      {detail && (
        <div className="detail" ref={detailRef}>
          <button onClick={() => setDetail(null)}>Close</button>
          <h3>
            {detail.parcel.carrierName} · {detail.parcel.lockerLocation}
          </h3>
          <p>Cell: {detail.parcel.cellNumber}</p>
          <p>Expires: {new Date(detail.parcel.expiresAt).toLocaleString("en-US")}</p>
          {detail.pickupCode ? (
            <>
              <p>
                Pickup code: <strong>{detail.pickupCode}</strong>
              </p>
              <img
                src={detail.qrCodeDataUrl}
                alt="Pickup QR code"
                width="180"
              />
            </>
          ) : (
            <p>
              No active pickup code. If your package has expired, contact
              management.
            </p>
          )}
        </div>
      )}
    </section>
  );
}
