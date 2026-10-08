import React, { useEffect, useState } from "react";
import { api } from "./api";
import "./package-management.css";

export default function ManagerParcelIntake() {
  const [query, setQuery] = useState("");
  const [residents, setResidents] = useState([]);
  const [residentId, setResidentId] = useState("");
  const [lockers, setLockers] = useState([]);
  const [lockerId, setLockerId] = useState("");
  const [cells, setCells] = useState([]);
  const [cellId, setCellId] = useState("");
  const [packageSize, setPackageSize] = useState("SMALL");
  const [carrierName, setCarrierName] = useState("UPS");
  const [trackingNumber, setTrackingNumber] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [created, setCreated] = useState(null);

  async function loadLockers() {
    try {
      const found = await api("/manager/lockers");
      const active = found.filter((locker) => locker.status === "ACTIVE");
      setLockers(active);
      setLockerId((current) =>
        active.some((locker) => String(locker.id) === current)
          ? current
          : String(active[0]?.id ?? ""),
      );
    } catch (err) {
      setError(err.message);
    }
  }

  useEffect(() => {
    loadLockers();
  }, []);

  useEffect(() => {
    let current = true;
    setCells([]);
    setCellId("");

    if (lockerId) {
      api(`/manager/lockers/${lockerId}/available-cells?size=${packageSize}`)
        .then((found) => {
          if (current) setCells(found);
        })
        .catch((err) => {
          if (current) setError(err.message);
        });
    }

    return () => {
      current = false;
    };
  }, [lockerId, packageSize]);

  async function searchResidents(event) {
    event.preventDefault();
    setBusy(true);
    setError("");
    setResidents([]);
    setResidentId("");

    try {
      const found = await api(
        `/manager/parcels/residents?query=${encodeURIComponent(query.trim())}`,
      );
      setResidents(found);
      if (found.length === 0) setError("No approved resident found.");
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError("");
    setCreated(null);

    try {
      const response = await api("/manager/parcels", {
        method: "POST",
        data: {
          residentId: Number(residentId),
          cellId: Number(cellId),
          carrierName,
          trackingNumber: trackingNumber.trim(),
          packageSize,
        },
      });

      setCreated(response);
      setCellId("");
      setCells(
        await api(
          `/manager/lockers/${lockerId}/available-cells?size=${packageSize}`,
        ),
      );
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card package-card package-intake">
      <span className="eyebrow">PROPERTY INTAKE</span>
      <h2>Register a package</h2>
      <p className="hint">Find the resident, enter the delivery details and choose an available cell.</p>

      {error && (
        <p className="message error" role="alert">
          {error}
        </p>
      )}

      <form className="package-search" onSubmit={searchResidents}>
        <label>
          Search resident by name or room
          <input
            placeholder="Resident name or room number"
            value={query}
            onChange={(event) => {
              setQuery(event.target.value);
              setResidents([]);
              setResidentId("");
            }}
            maxLength={100}
            required
          />
        </label>
        <button disabled={busy || !query.trim()}>
          {busy ? "Searching…" : "Search"}
        </button>
      </form>

      <form className="package-intake-form" onSubmit={submit}>
        <h3 className="package-step"><span>1</span> Recipient</h3>
        <label>
          Resident
          <select
            value={residentId}
            onChange={(event) => setResidentId(event.target.value)}
            required
          >
            <option value="">Select a resident</option>
            {residents.map((resident) => (
              <option key={resident.id} value={resident.id}>
                {resident.name} · Room {resident.room}
              </option>
            ))}
          </select>
        </label>

        <h3 className="package-step"><span>2</span> Delivery details</h3>
        <div className="grid">
          <label>
            Carrier
            <select
              value={carrierName}
              onChange={(event) => setCarrierName(event.target.value)}
            >
              {["UPS", "FedEx", "USPS", "Amazon", "DHL", "Other"].map(
                (name) => (
                  <option key={name} value={name}>
                    {name}
                  </option>
                ),
              )}
            </select>
          </label>

          <label>
            Tracking Number (optional)
            <input
              value={trackingNumber}
              onChange={(event) => setTrackingNumber(event.target.value)}
              maxLength={100}
            />
          </label>
        </div>

        <div className="section-head package-storage-heading"><h3 className="package-step"><span>3</span> Storage</h3><button type="button" disabled={busy} onClick={loadLockers}>Refresh lockers</button></div>
        <div className="grid">
          <label>
            Package Size
            <select
              value={packageSize}
              onChange={(event) => setPackageSize(event.target.value)}
            >
              <option value="SMALL">Small</option>
              <option value="MEDIUM">Medium</option>
              <option value="LARGE">Large</option>
            </select>
          </label>

          <label>
            Locker
            <select
              value={lockerId}
              onChange={(event) => setLockerId(event.target.value)}
              required
            >
              {lockers.length === 0 && (
                <option value="">No active lockers</option>
              )}
              {lockers.map((locker) => (
                <option key={locker.id} value={locker.id}>
                  {locker.location}
                </option>
              ))}
            </select>
          </label>
        </div>

        <label>
          Available Cell
          <select
            value={cellId}
            onChange={(event) => setCellId(event.target.value)}
            required
          >
            <option value="">
              {cells.length === 0 ? "No available cells" : "Select a cell"}
            </option>
            {cells.map((cell) => (
              <option key={cell.id} value={cell.id}>
                {cell.cellNumber} · {cell.size}
              </option>
            ))}
          </select>
        </label>

        <div className="package-submit"><p className="hint">{!residentId ? "Select an approved resident to continue." : !cellId ? "Choose an available cell matching the package size." : "Ready to register this delivery."}</p><button className="primary" disabled={busy || !residentId || !cellId}>
          {busy ? "Saving…" : "Store Package"}
        </button></div>
      </form>

      {created && (
        <div className="message" role="status">
          <h3>Package stored</h3>
          <p>
            {created.parcel.lockerLocation} · Cell {created.parcel.cellNumber}
          </p>
          <p>
            Pickup code: <strong>{created.pickupCode}</strong>
          </p>
          <p>Pickup details are now available in the resident’s My Packages.</p>
        </div>
      )}
    </section>
  );
}
